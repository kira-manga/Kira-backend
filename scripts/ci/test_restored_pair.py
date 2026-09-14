"""Read-only selected-pair semantic checks, using Backup29's owned offline fixtures.

The real helper/CLI and restore wrappers operate on disposable files; all PG calls
use explicit no-fallthrough stubs. This is not real PostgreSQL, writer-custody,
image-decoding, installed storage or disaster-recovery qualification.
"""

import contextlib
import copy
import json
import os
import shutil
import stat
import subprocess
import sys
import unittest
from unittest.mock import patch

import test_backup_bundle as backup


bundle = backup.bundle
DRAFT_ID = '11111111-1111-4111-8111-111111111111'
PUBLISHED_ID = '22222222-2222-4222-8222-222222222222'
OTHER_ID = 'abcdefab-cdef-4abc-8def-abcdefabcdef'
QUARANTINE_ID = '33333333-3333-4333-8333-333333333333'
DRAFT = b'opaque draft PNG fixture bytes'
PUBLISHED = b'opaque published JPEG fixture bytes'


def media_row(identifier, raw, published=False):
    return {'id': identifier, 'storage_filename': identifier + ('.jpg' if published else '.png'),
            'content_type': 'image/jpeg' if published else 'image/png', 'byte_size': len(raw),
            'width': 1, 'height': 1, 'sha256': bundle.digest(raw), 'published': published}


def default_rows():
    return [media_row(DRAFT_ID, DRAFT), media_row(PUBLISHED_ID, PUBLISHED, True)]


def default_members():
    return [(DRAFT_ID + '.png', DRAFT), (PUBLISHED_ID + '.jpg', PUBLISHED)]


def quarantine_name(original, raw, identifier=QUARANTINE_ID):
    return 'quarantine/' + identifier + '--' + original + '--' + bundle.digest(raw)


# Extend only the stub's JSON SELECT response. The inherited restore/CLI, client
# version checks, bounded failures and no host-PG PATH fallback remain real code.
MEDIA_RESPONSE = """    if any('FROM public.tutorial_media' in arg for arg in args):
        if flags.get('media_sql_failure'):
            done(1)
        if flags.get('media_sql_oversize'):
            done(output=b' ' * (8 * 1024 * 1024 + 1))
        if 'media_sql_raw' in flags:
            done(output=flags['media_sql_raw'].encode())
        response.update(flags.get('media_identity', {}))
        response = {'state': response, 'rows': state['media_rows']}
        if 'oid_after_media' in flags:
            state['oid'] = flags['oid_after_media']
    done(output=json.dumps(response).encode() + b'\\n')"""


class SemanticFixture(backup.Fixture):
    def __init__(self, base, members=None, rows=None):
        super().__init__(base)
        self.pair = backup.make_pair(base / 'semantic-input', members=default_members() if members is None else members)
        self.update(media_rows=default_rows() if rows is None else rows,
                    originals=[str(self.pair.dump), str(self.pair.media)])
        marker = "    done(output=json.dumps(response).encode() + b'\\n')"
        if backup.STUB.count(marker) != 1:
            raise AssertionError('Backup29 stub SELECT anchor changed; review the fixture extension')
        script = backup.STUB.replace(marker, MEDIA_RESPONSE)
        (self.bin / 'psql').write_text(script.replace('@PYTHON@', sys.executable).replace('@BASE@', repr(str(base))))

    def verify(self, environment=None, extra=()):
        return subprocess.run([sys.executable, '-I', '-B', str(backup.HELPER), 'verify-restored-pair',
                               '--attempt', str(self.attempt), *extra],
                              env=self.env if environment is None else environment, capture_output=True, timeout=20)

    def direct_verify(self):
        with patch.dict(os.environ, self.env, clear=True):
            return bundle.verify_restored_pair(self.attempt)


def tree_facts(path):
    """Bytes and ownership identity, excluding read-access times (not a no-atime claim)."""
    found = {}

    def visit(current, relative):
        info = current.lstat()
        facts = (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid,
                 info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        if stat.S_ISREG(info.st_mode):
            facts += (current.read_bytes(),)
        elif stat.S_ISLNK(info.st_mode):
            facts += (os.readlink(current),)
        found[relative] = facts
        if stat.S_ISDIR(info.st_mode):
            for child in sorted(current.iterdir()):
                visit(child, relative + '/' + child.name)

    if path.exists() or path.is_symlink():
        visit(path, '.')
    return found


class RestoredPairTests(backup.OwnedCase):
    def fixture(self, members=None, rows=None, complete=True):
        root = self.base / ('fixture-' + str(len(list(self.base.iterdir()))))
        root.mkdir(mode=0o700)
        fixture = SemanticFixture(root, members, rows)
        if complete:
            self.ok(fixture.db())
            self.ok(fixture.media())
        return fixture

    def facts(self, fixture):
        state = fixture.state()
        return (tree_facts(fixture.attempt), tree_facts(fixture.target),
                tree_facts(fixture.base / 'database-effect.bin'),
                {key: state[key] for key in ('oid', 'history', 'media_rows')})

    def unchanged_check(self, fixture, succeeds, environment=None):
        before = self.facts(fixture)
        result = fixture.verify(environment)
        (self.ok if succeeds else self.failed)(result)
        self.assertEqual(before, self.facts(fixture))
        return json.loads(result.stdout) if result.stdout else None

    def assert_issue(self, report, scope, kind):
        self.assertFalse(report['verified'])
        self.assertTrue(any(issue['scope'] == scope and issue['kind'] == kind for issue in report['issues']), report)

    def test_cli_verifies_every_draft_and_published_row_and_is_repeatably_read_only(self):
        fixture = self.fixture()
        calls_before = len(fixture.state()['calls'])
        first = self.unchanged_check(fixture, True)
        second = self.unchanged_check(fixture, True)
        self.assertEqual(first, second)
        self.assertEqual((2, 1, 1, 0, []),
                         (first['rows'], first['draft_verified'], first['published_verified'],
                          first['quarantine_files'], first['issues']))
        self.assertEqual(bundle.digest((fixture.attempt / 'request.json').read_bytes()), first['request_sha256'])
        self.assertEqual(bundle.digest((fixture.attempt / 'db.json').read_bytes()), first['database_receipt_sha256'])
        calls = fixture.state()['calls'][calls_before:]
        self.assertEqual(8, len(calls))  # PG17, state, bounded rows+same-connection state, final state; twice.
        for call in calls:
            self.assertEqual('psql', call['name'])
            if call['args'] != ['--version']:
                sql = next(arg for arg in call['args'] if arg.startswith('--command='))
                self.assertTrue(sql.startswith('--command=SELECT '))
                self.assertNotIn('WHERE published', sql)
        self.assertFalse((fixture.attempt / 'STOP.json').exists())

    def test_manifest_valid_missing_truncated_and_same_size_corruption_fail_for_both_states(self):
        for index, scope in ((0, 'draft'), (1, 'published')):
            for kind in ('missing', 'size_mismatch', 'checksum_mismatch'):
                with self.subTest(scope=scope, kind=kind):
                    members = default_members()
                    name, raw = members[index]
                    if kind == 'missing':
                        members.pop(index)
                    else:
                        members[index] = (name, raw[:-1] if kind == 'size_mismatch' else b'X' * len(raw))
                    fixture = self.fixture(members=members)
                    selected = bundle.selected(fixture.pair.bundle, fixture.pair.dump, fixture.pair.media, fixture.pair.pin)
                    self.assertEqual(fixture.pair.pin, selected['manifest']['sha256'])
                    report = self.unchanged_check(fixture, False)
                    self.assert_issue(report, scope, kind)
                    self.assertEqual(1, report['draft_verified'] + report['published_verified'])
                    self.assertFalse((fixture.attempt / 'STOP.json').exists())

    def test_invalid_recorded_metadata_never_becomes_path_authority_or_rowless_cleanup_authority(self):
        variants = ({'id': OTHER_ID}, {'id': OTHER_ID.upper()}, {'storage_filename': '../outside.png'},
                    {'storage_filename': DRAFT_ID + '.jpg'}, {'storage_filename': DRAFT_ID + '.PNG'},
                    {'content_type': 'image/gif'}, {'content_type': {}}, {'byte_size': 0},
                    {'byte_size': True}, {'byte_size': 1.0}, {'byte_size': 4194305},
                    {'sha256': 'A' * 64}, {'sha256': '0' * 63}, {'width': True},
                    {'width': 4097}, {'width': 4096, 'height': 4096}, {'published': 1}, {'extra': 1})
        for changed in variants:
            with self.subTest(changed=changed):
                fixture = self.fixture()
                rows = default_rows()
                rows[0].update(changed)
                fixture.update(media_rows=rows)
                report = self.unchanged_check(fixture, False)
                self.assertTrue(any(issue['kind'] == 'invalid_metadata' for issue in report['issues']))
                self.assertEqual(1, report['published_verified'])
                if set(changed) <= {'sha256', 'byte_size', 'width', 'height', 'content_type', 'published', 'extra'}:
                    self.assertFalse(any(issue['kind'] == 'rowless_final' for issue in report['issues']))
        fixture = self.fixture()
        fixture.update(media_rows=[default_rows()[0], default_rows()[0]])
        self.assert_issue(self.unchanged_check(fixture, False), 'draft', 'invalid_metadata')

    def test_row_files_reject_symlinks_hardlinks_directories_fifos_and_nonprivate_modes(self):
        for kind in ('symlink', 'hardlink', 'directory', 'fifo', 'mode'):
            with self.subTest(kind=kind):
                fixture = self.fixture()
                target = fixture.target / (DRAFT_ID + '.png')
                outside = backup.put(fixture.base / 'outside', DRAFT)
                if kind != 'mode':
                    target.unlink()
                if kind == 'symlink':
                    target.symlink_to(outside)
                elif kind == 'hardlink':
                    os.link(outside, target)
                elif kind == 'directory':
                    target.mkdir(mode=0o700)
                elif kind == 'fifo':
                    os.mkfifo(target, 0o600)
                else:
                    target.chmod(0o666)
                outside_before = tree_facts(outside)
                self.assert_issue(self.unchanged_check(fixture, False), 'draft', 'unsafe_file')
                self.assertEqual(outside_before, tree_facts(outside))

    def test_exact_quarantine_shape_survives_unchanged_backup29_tar_restore_profile(self):
        final = quarantine_name(OTHER_ID + '.png', b'old-corrupt-bytes') + '/content'
        staging = quarantine_name('.upload-restart-123.jpg', b'', OTHER_ID) + '/content'
        fixture = self.fixture(members=default_members() + [(final, b'old-corrupt-bytes'), (staging, b'')])
        report = self.unchanged_check(fixture, True)
        self.assertEqual(2, report['quarantine_files'])
        self.assertEqual([], report['issues'])
        self.assertEqual(b'', (fixture.target / staging).read_bytes())

    def test_incomplete_unknown_and_corrupt_quarantine_remain_failed_retained_evidence(self):
        directory = quarantine_name(OTHER_ID + '.png', b'old')
        variants = ((directory + '/content', b'bad', 'checksum_mismatch'),
                    (directory + '/partial', b'old', 'incomplete_quarantine'),
                    ('quarantine/unrecognized/content', b'old', 'unknown_entry'),
                    (directory + '/content', b'x' * (4194304 + 1), 'size_mismatch'))
        for path, raw, kind in variants:
            with self.subTest(kind=kind):
                fixture = self.fixture(members=default_members() + [(path, raw)])
                self.assert_issue(self.unchanged_check(fixture, False), 'quarantine', kind)
        fixture = self.fixture(members=default_members() + [(directory + '/content', b'old'),
                                                          (directory + '/extra', b'keep')])
        self.assert_issue(self.unchanged_check(fixture, False), 'quarantine', 'incomplete_quarantine')
        fixture = self.fixture(members=default_members() + [(directory + '/content', b'old')])
        (fixture.target / directory / 'content').unlink()
        self.assert_issue(self.unchanged_check(fixture, False), 'quarantine', 'incomplete_quarantine')
        fixture = self.fixture(members=default_members() + [(directory + '/content', b'old')])
        leaf = fixture.target / directory / 'content'
        leaf.unlink()
        leaf.symlink_to(backup.put(fixture.base / 'outside', b'old'))
        self.assert_issue(self.unchanged_check(fixture, False), 'quarantine', 'unsafe_file')

    def test_rowless_final_staging_and_unknown_extras_are_explicit_failures_not_silently_clean(self):
        for path, kind in ((OTHER_ID + '.jpg', 'rowless_final'), ('.upload-123.png', 'staging'),
                           ('notes.txt', 'unknown_entry'), ('unknown/retained.bin', 'unsafe_entry'),
                           ('.upload-' + 'a' * 81 + '.png', 'unknown_entry')):
            with self.subTest(path=path):
                fixture = self.fixture(members=default_members() + [(path, b'keep')])
                self.assert_issue(self.unchanged_check(fixture, False), 'root', kind)

    def test_sticky_stop_and_each_missing_receipt_refuse_without_creating_or_rewriting_records(self):
        for name in ('.lock', 'request.json', 'db-started.json', 'db.json', 'media-started.json', 'pair.json', 'STOP.json'):
            with self.subTest(name=name):
                fixture = self.fixture()
                if name == 'STOP.json':
                    backup.put(fixture.attempt / name, b'{"retain":"existing STOP evidence"}\n')
                else:
                    (fixture.attempt / name).unlink()
                self.unchanged_check(fixture, False)
                self.assertEqual(name == 'STOP.json', (fixture.attempt / 'STOP.json').exists())

    def test_cross_attempt_selection_target_and_typed_pair_identity_correspondence(self):
        variants = (('request.json', 'id', OTHER_ID), ('db-started.json', 'id', OTHER_ID),
                    ('db.json', 'id', OTHER_ID), ('db.json', 'request_sha256', '0' * 64),
                    ('media-started.json', 'target', '/not-selected'), ('pair.json', 'id', OTHER_ID),
                    ('pair.json', 'database_receipt_sha256', '0' * 64),
                    ('pair.json', 'target', {'path': '/not-selected', 'device': 1, 'inode': 1}),
                    ('pair.json', 'target', {'path': 'unchanged', 'device': True, 'inode': 1}),
                    ('pair.json', 'target', {'path': 'unchanged', 'device': 1, 'inode': True}))
        for name, key, value in variants:
            with self.subTest(name=name, key=key, value=value):
                fixture = self.fixture()
                record_path = fixture.attempt / name
                record = json.loads(record_path.read_bytes())
                record[key] = copy.deepcopy(value)
                if key == 'target' and type(value) is dict and value['path'] == 'unchanged':
                    record[key]['path'] = str(fixture.target)
                backup.put(record_path, bundle.canonical(record))
                self.unchanged_check(fixture, False)
        fixture = self.fixture()
        request_path = fixture.attempt / 'request.json'
        request = json.loads(request_path.read_bytes())
        request['selection']['dump']['name'] = '../escape.dump'
        backup.put(request_path, bundle.canonical(request))
        self.unchanged_check(fixture, False)

    def test_each_frozen_member_tampering_and_symlink_is_refused(self):
        for role in ('bundle', 'dump', 'media'):
            with self.subTest(role=role):
                fixture = self.fixture()
                path = fixture.attempt / getattr(fixture.pair, role).name
                backup.put(path, path.read_bytes() + b'changed')
                self.unchanged_check(fixture, False)
        fixture = self.fixture()
        frozen = fixture.attempt / fixture.pair.dump.name
        frozen.unlink()
        frozen.symlink_to(fixture.pair.dump)
        self.unchanged_check(fixture, False)

    def test_target_identity_private_custody_and_attempt_lock_are_still_required(self):
        for variant in ('replacement', 'symlink', 'target-mode', 'attempt-mode', 'parent-mode', 'record-mode'):
            with self.subTest(variant=variant):
                fixture = self.fixture()
                retained = fixture.base / 'retained-original'
                if variant in ('replacement', 'symlink'):
                    fixture.target.rename(retained)
                    if variant == 'replacement':
                        shutil.copytree(retained, fixture.target)
                    else:
                        fixture.target.symlink_to(retained, target_is_directory=True)
                elif variant == 'target-mode':
                    fixture.target.chmod(0o755)
                elif variant == 'attempt-mode':
                    fixture.attempt.chmod(0o755)
                elif variant == 'parent-mode':
                    fixture.base.chmod(0o777)
                else:
                    (fixture.attempt / 'pair.json').chmod(0o644)
                retained_before = tree_facts(retained)
                self.unchanged_check(fixture, False)
                self.assertEqual(retained_before, tree_facts(retained))
        fixture = self.fixture()
        with bundle.directory(fixture.attempt) as parent, bundle.attempt_lock(parent):
            self.unchanged_check(fixture, False)
        with bundle.directory(fixture.target.parent) as parent, bundle.locked(parent):
            self.unchanged_check(fixture, False)

    def test_environment_guards_and_runtime_checks_precede_any_pg_command(self):
        fixture = self.fixture()
        variants = ({'PGDATABASE': 'kira'}, {'PGDATABASE': 'kira_restore_'}, {'PGPORT': '65536'},
                    {'PGHOST': ''}, {'PGUSER': ''}, {'KIRA_ENVIRONMENT': 'production'},
                    {'KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST': 'no'}, {'KIRA_RESTORE_CUSTODY_CONFIRMED': 'no'})
        calls = fixture.state()['calls']
        for changed in variants:
            with self.subTest(changed=changed):
                self.unchanged_check(fixture, False, {**fixture.env, **changed})
                with patch.dict(os.environ, {**fixture.env, **changed}, clear=True), self.assertRaises(bundle.Refused):
                    bundle.verify_restored_pair(fixture.attempt)
                self.assertEqual(calls, fixture.state()['calls'])
        for attribute, value in (('platform', 'darwin'), ('version_info', (3, 10))):
            with patch.object(bundle.sys, attribute, value), self.assertRaises(bundle.Refused):
                fixture.direct_verify()
            self.assertEqual(calls, fixture.state()['calls'])
        fixture.flags(old_client=True)
        self.unchanged_check(fixture, False)

    def test_database_identity_full_history_and_media_query_connection_are_rechecked(self):
        for field in ('oid', 'rank', 'version', 'type', 'script', 'checksum', 'success'):
            with self.subTest(field=field):
                fixture = self.fixture()
                if field == 'oid':
                    fixture.update(oid=12346)
                else:
                    history = backup.history()
                    history[0][field] = {'rank': 3, 'version': '0', 'type': 'JDBC', 'script': 'changed.sql',
                                         'checksum': 999, 'success': False}[field]
                    fixture.update(history=history)
                self.unchanged_check(fixture, False)
        for identity in ({'oid': 12346}, {'oid': True}, {'database': 'kira_restore_other'}, {'user': 'other'},
                         {'history': []}):
            fixture = self.fixture()
            fixture.flags(media_identity=identity)
            self.unchanged_check(fixture, False)
        fixture = self.fixture()
        fixture.flags(oid_after_media=12346)
        before = (tree_facts(fixture.attempt), tree_facts(fixture.target))
        self.failed(fixture.verify())
        self.assertEqual(before, (tree_facts(fixture.attempt), tree_facts(fixture.target)))
        self.assertEqual(12346, fixture.state()['oid'])  # Explicit competing identity change, not checker mutation.

    def test_row_response_inventory_and_aggregate_bounds_are_incomplete_not_clean(self):
        for name, value in (('TUTORIAL_ROW_LIMIT', 1), ('TUTORIAL_RESPONSE_LIMIT', 32),
                            ('TUTORIAL_ENTRY_LIMIT', 1), ('TOTAL_LIMIT', len(DRAFT))):
            with self.subTest(name=name):
                fixture = self.fixture()
                before = self.facts(fixture)
                with patch.object(bundle, name, value), self.assertRaises(bundle.Refused):
                    fixture.direct_verify()
                self.assertEqual(before, self.facts(fixture))
        fixture = self.fixture()
        with patch.object(bundle, 'TUTORIAL_ENTRY_LIMIT', 2):
            self.assertTrue(fixture.direct_verify()['verified'])
        for flags in ({'media_sql_failure': True}, {'media_sql_oversize': True},
                      {'media_sql_raw': '{"state":{},"rows":[],"rows":[]}'},
                      {'media_sql_raw': '{"state":{},"rows":null}'}):
            fixture = self.fixture()
            fixture.flags(**flags)
            self.unchanged_check(fixture, False)

    def test_listing_and_read_failures_cannot_become_empty_or_verified_media(self):
        fixture = self.fixture()
        before = self.facts(fixture)
        with patch.object(bundle.os, 'scandir', side_effect=PermissionError('fixture')):
            with self.assertRaises(PermissionError):
                fixture.direct_verify()
        self.assertEqual(before, self.facts(fixture))
        original = bundle.regular

        @contextlib.contextmanager
        def denied(parent, name, private=False):
            if name == DRAFT_ID + '.png':
                raise PermissionError('fixture')
            with original(parent, name, private=private) as value:
                yield value

        with patch.object(bundle, 'regular', denied):
            self.assert_issue(fixture.direct_verify(), 'draft', 'io_failure')
        self.assertEqual(before, self.facts(fixture))

    def test_read_uses_bounded_opened_bytes_and_detects_midread_identity_change(self):
        fixture = self.fixture()
        path = fixture.target / (DRAFT_ID + '.png')
        with bundle.directory(fixture.target, private=True) as parent:
            observed = os.stat(path.name, dir_fd=parent, follow_symlinks=False)
            original = bundle.regular
            sizes = []

            @contextlib.contextmanager
            def changed_after_read(fd, name, private=False):
                with original(fd, name, private=private) as (stream, info):
                    class Reader:
                        def fileno(self):
                            return stream.fileno()

                        def read(self, size):
                            sizes.append(size)
                            raw = stream.read(size)
                            replacement = backup.put(fixture.base / 'replacement', b'X' * len(DRAFT))
                            replacement.replace(path)
                            return raw

                    yield Reader(), info

            with patch.object(bundle, 'regular', changed_after_read):
                issue = bundle.tutorial_file_issue(parent, path.name, observed, len(DRAFT), bundle.digest(DRAFT),
                                                   {'entries': 0, 'bytes': 0})
            self.assertEqual('identity_changed', issue)
            self.assertEqual([len(DRAFT) + 1], sizes)
            self.assertEqual(b'X' * len(DRAFT), path.read_bytes())
        self.assertFalse((fixture.attempt / 'STOP.json').exists())

    def test_pass_semantic_failure_and_stop_never_invoke_restore_or_any_file_mutator(self):
        for case in ('pass', 'corrupt', 'stop'):
            with self.subTest(case=case):
                fixture = self.fixture()
                if case == 'corrupt':
                    backup.put(fixture.target / (DRAFT_ID + '.png'), b'X' * len(DRAFT))
                elif case == 'stop':
                    backup.put(fixture.attempt / 'STOP.json', b'keep STOP')
                before = self.facts(fixture)
                real_open = os.open

                def read_only_open(path, flags, *args, **kwargs):
                    if os.fspath(path) != os.devnull:  # subprocess stderr sink, not attempt/DB/media state.
                        self.assertEqual(os.O_RDONLY, flags & os.O_ACCMODE)
                        self.assertEqual(0, flags & (os.O_CREAT | os.O_EXCL | os.O_TRUNC | os.O_APPEND))
                    return real_open(path, flags, *args, **kwargs)

                def forbidden(*args, **kwargs):
                    raise AssertionError('read-only checker reached a mutator')

                with contextlib.ExitStack() as guards:
                    for name in ('restore_database', 'restore_media', 'stop_attempt', 'record', 'publish_bytes',
                                 'publish_directory', 'remove_stage', 'child_directory', 'sync_tree', 'command'):
                        guards.enter_context(patch.object(bundle, name, side_effect=forbidden))
                    for name in ('mkdir', 'unlink', 'rename', 'replace', 'link', 'symlink', 'chmod', 'fchmod',
                                 'fsync', 'write', 'truncate', 'ftruncate'):
                        guards.enter_context(patch.object(bundle.os, name, side_effect=forbidden))
                    guards.enter_context(patch.object(bundle.os, 'open', side_effect=read_only_open))
                    if case == 'stop':
                        with self.assertRaises(bundle.Refused):
                            fixture.direct_verify()
                    else:
                        self.assertEqual(case == 'pass', fixture.direct_verify()['verified'])
                self.assertEqual(before, self.facts(fixture))

    def test_admitted_legacy_selection_is_reused_not_resealed_or_selected_again(self):
        fixture = self.fixture(complete=False)
        lines = [bundle.digest(path.read_bytes()) + '  /old/location/' + path.name + '\n'
                 for path in (fixture.pair.dump, fixture.pair.media)]
        fixture.pair.bundle = backup.put(fixture.pair.bundle.with_suffix('.sha256'), ''.join(lines).encode())
        fixture.pair.pin = bundle.digest(fixture.pair.bundle.read_bytes())
        self.ok(fixture.run('verify-restore.sh', fixture.argv() + ['--legacy-two-record']))
        self.ok(fixture.media())
        shutil.rmtree(fixture.pair.bundle.parent)
        self.assertTrue(self.unchanged_check(fixture, True)['verified'])
        for extra in (('--legacy-two-record',), ('--target', str(fixture.base / 'other')), ('--attempt-extra', 'x')):
            self.failed(fixture.verify(extra=extra))

    def test_empty_rows_and_media_do_not_claim_seed_or_application_readiness(self):
        fixture = self.fixture(members=[], rows=[])
        report = self.unchanged_check(fixture, True)
        self.assertEqual((0, 0, 0), (report['rows'], report['draft_verified'], report['published_verified']))
        self.assertEqual('kira.restored-pair-media-verification.v1', report['schema'])
        self.assertNotIn('ready', report)


if __name__ == '__main__':
    unittest.main()
