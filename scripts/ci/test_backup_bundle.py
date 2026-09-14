"""Selected-byte/receipt regressions in the existing stdlib unittest suite.

Actual helper and executable wrappers, owned temporary files, bounded tar fixtures
and explicit PostgreSQL/tar command stubs. PATH cannot fall through to host PG.
These are NOT PostgreSQL, installation, writer-drain or storage-durability proofs;
DatabaseBackupRestoreIT separately exercises real PostgreSQL 17.6 file bytes.
"""

import contextlib
import copy
import ctypes
import errno
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zlib


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'scripts/db/backup_bundle.py'
SPEC = importlib.util.spec_from_file_location('backup_bundle_under_test', HELPER)
bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle)
BAD_ARCHIVE = (bundle.Refused, tarfile.TarError, OSError, EOFError, UnicodeError, zlib.error)


def put(path, raw):
    path.write_bytes(raw)
    path.chmod(0o600)
    return path


def tar_header(name='item', size=0, kind=tarfile.REGTYPE):
    info = tarfile.TarInfo(name)
    info.size, info.type = size, kind
    return info.tobuf(format=tarfile.GNU_FORMAT)


def tar_bytes(members=(), root=True):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w', format=tarfile.PAX_FORMAT) as archive:
        if root:
            info = tarfile.TarInfo('./')
            info.type, info.mode = tarfile.DIRTYPE, 0o7777
            archive.addfile(info)
        for name, raw in members:
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.uid, info.gid = len(raw), 0o7777, 123, 456
            archive.addfile(info, io.BytesIO(raw))
    return gzip.compress(output.getvalue(), mtime=0)


def raw_tar(*blocks):
    return gzip.compress(b''.join(blocks) + bytes(1024), mtime=0)


def metadata(kind, raw):
    return tar_header('metadata', len(raw), kind) + raw + bytes((-len(raw)) % 512)


def pax(key, value):
    body = (' ' + key + '=' + value + '\n').encode('utf-8')
    length = len(body) + 1
    while len(str(length)) + len(body) != length:
        length = len(str(length)) + len(body)
    return str(length).encode('ascii') + body


def history(version='12'):
    return [{'rank': 1, 'version': '1', 'type': 'SQL', 'script': 'V1__users.sql',
             'checksum': -123, 'success': True},
            {'rank': 2, 'version': version, 'type': 'SQL', 'script': 'V' + version + '__fixture.sql',
             'checksum': 456, 'success': True}]


def make_pair(directory, stem='selected', marker=b'A', members=None):
    directory.mkdir(mode=0o700, exist_ok=True)
    dump = put(directory / (stem + '.dump'), b'PGDMP\x01\x00fixture-' + marker)
    media = put(directory / (stem + '.media.tar.gz'),
                tar_bytes([('tutorial/item.bin', b'\x00\xff' + marker)] if members is None else members))
    manifest = directory / (stem + '.bundle.json')
    pin = bundle.create(manifest, dump, media)
    return SimpleNamespace(bundle=manifest, dump=dump, media=media, pin=pin)


STUB = r'''#!@PYTHON@
import hashlib, json, os, sys
from pathlib import Path
base = Path(@BASE@)
name, args = Path(__file__).name, sys.argv[1:]
state_file = base / 'commands.json'
state = json.loads(state_file.read_text())
flags = state['flags']
state['calls'].append({'name': name, 'args': args})
def done(code=0, output=b''):
    state_file.write_text(json.dumps(state))
    sys.stdout.buffer.write(output)
    raise SystemExit(code)
if name not in ('pg_dump', 'pg_restore', 'psql', 'tar'):
    done(99)
if args == ['--version'] and name != 'tar':
    done(output=(name + ' (PostgreSQL) ' + ('16.9' if flags.get('old_client') else '17.6') + '\n').encode())
if name == 'pg_dump':
    files = [a[7:] for a in args if a.startswith('--file=')]
    if len(files) != 1 or '--format=custom' not in args or '--no-acl' not in args:
        done(98)
    Path(files[0]).write_bytes(b'PGDMP\x01\x00produced-fixture')
    done(1 if flags.get('dump_failure') else 0)
if name == 'tar':
    if len(args) != 5 or args[0] != '-C' or args[2] != '-czf' or args[4] != '.':
        done(98)
    Path(args[3]).write_bytes((base / 'producer.media.tar.gz').read_bytes())
    done(1 if flags.get('tar_failure') else 0)
if name == 'pg_restore':
    data = sys.stdin.buffer.read(1024 * 1024 + 1)
    if len(data) > 1024 * 1024 or not data.startswith(b'PGDMP'):
        done(98)
    if args == ['--list']:
        state['listed_sha256'] = hashlib.sha256(data).hexdigest()
        if flags.get('replace_originals'):
            for path in state['originals']:
                Path(path).write_bytes(b'replaced-original-after-snapshot')
        done(1 if flags.get('list_failure') else 0, b'; custom fixture TOC\n')
    required = {'--exit-on-error', '--clean', '--if-exists', '--no-owner', '--no-acl', '--single-transaction'}
    if not required.issubset(args) or '--dbname=kira_restore_fixture' not in args:
        done(98)
    # Deliberately model a possible partial effect even for a nonzero return.
    (base / 'database-effect.bin').write_bytes(data)
    state['restored_sha256'] = hashlib.sha256(data).hexdigest()
    state['restore_tls'] = os.environ.get('PGSSLMODE')
    done(1 if flags.get('restore_failure') else 0)
if name == 'psql':
    if '--no-psqlrc' not in args or '--set=ON_ERROR_STOP=1' not in args or not any(a.startswith('--command=SELECT ') for a in args):
        done(98)
    if flags.get('sql_failure'):
        done(1)
    if flags.get('sql_oversize'):
        done(output=b' ' * (1024 * 1024 + 1))
    if flags.get('sql_raw'):
        done(output=flags['sql_raw'].encode())
    response = {'database': 'kira_restore_fixture', 'user': 'fixture', 'oid': state['oid'],
                'address': '127.0.0.1', 'port': 5432, 'history': state['history']}
    done(output=json.dumps(response).encode() + b'\n')
done(99)
'''


class Fixture:
    def __init__(self, base):
        self.base = base
        self.bin = base / 'bin'
        self.bin.mkdir(mode=0o700)
        for name in ('pg_dump', 'pg_restore', 'psql', 'tar'):
            command = self.bin / name
            command.write_text(STUB.replace('@PYTHON@', sys.executable).replace('@BASE@', repr(str(base))))
            command.chmod(0o755)
        # Only these non-PG executables can be found. No real host client fallback.
        (self.bin / 'python3').symlink_to(sys.executable)
        (self.bin / 'dirname').symlink_to(shutil.which('dirname'))
        self.env = {'PATH': str(self.bin), 'PGHOST': '127.0.0.1', 'PGPORT': '5432',
                    'PGDATABASE': 'kira_restore_fixture', 'PGUSER': 'fixture', 'PGSSLMODE': 'disable',
                    'KIRA_ENVIRONMENT': 'test', 'KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST': 'yes',
                    'KIRA_RESTORE_CUSTODY_CONFIRMED': 'yes'}
        self.pair = make_pair(base / 'input')
        self.attempt, self.target = base / 'attempt', base / 'restored-media'
        self.state_file = base / 'commands.json'
        self.state_file.write_text(json.dumps({'calls': [], 'flags': {}, 'history': history(), 'oid': 12345,
                                             'originals': [str(self.pair.dump), str(self.pair.media)]}))
        put(base / 'producer.media.tar.gz', tar_bytes([('item', b'produced')]))

    def state(self):
        return json.loads(self.state_file.read_text())

    def update(self, **values):
        state = self.state()
        state.update(values)
        self.state_file.write_text(json.dumps(state))

    def flags(self, **values):
        flags = self.state()['flags']
        flags.update(values)
        self.update(flags=flags)

    def argv(self, pair=None, version='12'):
        selected = pair or self.pair
        return [str(selected.bundle), selected.pin, str(selected.dump), str(selected.media),
                version, str(self.attempt), str(self.target)]

    def run(self, script, arguments, env=None):
        return subprocess.run([str(ROOT / 'scripts/db' / script), *arguments],
                              env=self.env if env is None else env, capture_output=True, timeout=20)

    def db(self, **kwargs):
        return self.run('verify-restore.sh', self.argv(**kwargs))

    def media(self):
        return self.run('restore-media.sh', [str(self.attempt)])

    def direct_db(self):
        with patch.dict(os.environ, self.env, clear=True):
            bundle.restore_database(*self.argv())

    def direct_media(self):
        with patch.dict(os.environ, self.env, clear=True):
            bundle.restore_media(self.attempt)


class OwnedCase(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.base.chmod(0o700)

    def fixture(self):
        root = self.base / ('fixture-' + str(len(list(self.base.iterdir()))))
        root.mkdir(mode=0o700)
        return Fixture(root)

    def ok(self, result):
        self.assertEqual(0, result.returncode, result.stderr.decode())

    def failed(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout.decode())

    def inspect_tar(self, raw, extract=None):
        with put(self.base / 'inspect.tar.gz', raw).open('rb') as stream:
            return bundle.safe_tar(stream, extract=extract)


class BundleSelectionTests(OwnedCase):
    def test_public_cli_requires_pin_and_prints_only_exact_digest(self):
        selected = make_pair(self.base / 'original')
        command = [sys.executable, '-I', '-B', str(HELPER), 'verify', '--bundle', str(selected.bundle),
                   '--dump', str(selected.dump), '--media', str(selected.media)]
        self.failed(subprocess.run(command, capture_output=True, timeout=10))
        self.failed(subprocess.run(command + ['--expected-sha256', '0' * 64], capture_output=True, timeout=10))
        result = subprocess.run(command + ['--expected-sha256', selected.pin], capture_output=True, timeout=10)
        self.ok(result)
        self.assertEqual((selected.pin + '\n').encode(), result.stdout)
        selected.bundle.unlink()
        result = subprocess.run([sys.executable, '-I', '-B', str(HELPER), 'create', *command[5:]],
                                capture_output=True, timeout=10)
        self.ok(result)
        self.assertEqual((selected.pin + '\n').encode(), result.stdout)

    def test_v1_relocation_surviving_originals_tampering_and_pair_mixing(self):
        selected = make_pair(self.base / 'old')
        moved = self.base / 'new'
        shutil.copytree(selected.dump.parent, moved)
        arguments = [moved / path.name for path in (selected.bundle, selected.dump, selected.media)]
        self.assertEqual(selected.pin, bundle.selected(*arguments, selected.pin)['manifest']['sha256'])
        put(arguments[1], b'PGDMP-mutated-selected')
        with self.assertRaises(bundle.Refused):
            bundle.selected(*arguments, selected.pin)
        shutil.copyfile(selected.dump, arguments[1])
        put(arguments[2], tar_bytes([('different', b'B')]))
        with self.assertRaises(bundle.Refused):
            bundle.selected(*arguments, selected.pin)
        shutil.copyfile(selected.media, arguments[2])
        shutil.rmtree(selected.dump.parent)
        self.assertEqual(selected.pin, bundle.selected(*arguments, selected.pin)['manifest']['sha256'])

    def test_source_symlinks_parent_symlinks_nonregular_and_names_are_refused(self):
        selected = make_pair(self.base / 'selected')
        saved = selected.dump.read_bytes()
        selected.dump.unlink()
        target = put(self.base / 'actual.dump', saved)
        selected.dump.symlink_to(target)
        with self.assertRaises(OSError):
            bundle.selected(selected.bundle, selected.dump, selected.media, selected.pin)
        selected.dump.unlink()
        os.mkfifo(selected.dump, 0o600)
        with self.assertRaises(bundle.Refused):
            bundle.selected(selected.bundle, selected.dump, selected.media, selected.pin)
        selected.dump.unlink()
        put(selected.dump, saved)
        alias = self.base / 'alias'
        alias.symlink_to(selected.dump.parent, target_is_directory=True)
        with self.assertRaises(OSError):
            bundle.selected(alias / selected.bundle.name, alias / selected.dump.name,
                            alias / selected.media.name, selected.pin)
        for stem in ('.hidden', 'a.b', 'a' * 97, 'a b', 'a\\b'):
            with self.subTest(stem=stem), self.assertRaises(bundle.Refused):
                bundle.paths(stem + '.bundle.json', stem + '.dump', stem + '.media.tar.gz')
        bundle.paths('a' * 96 + '.bundle.json', 'a' * 96 + '.dump', 'a' * 96 + '.media.tar.gz')

    def test_strict_manifest_shapes_types_digests_and_size_boundaries(self):
        selected = make_pair(self.base / 'selected')
        valid = json.loads(selected.bundle.read_bytes())
        variants = []
        for role in ('dump', 'media'):
            for value in (True, False, 0, -1, 1.0, 2**63, '1', None):
                changed = copy.deepcopy(valid)
                changed[role]['bytes'] = value
                variants.append(changed)
            for value in ('A' * 64, 'a' * 63, 'g' * 64, '0' * 64 + '\n', None):
                changed = copy.deepcopy(valid)
                changed[role]['sha256'] = value
                variants.append(changed)
            changed = copy.deepcopy(valid)
            changed[role]['extra'] = 1
            variants.append(changed)
            changed = copy.deepcopy(valid)
            changed[role]['name'] = '../' + changed[role]['name']
            variants.append(changed)
        variants += [{**valid, 'extra': 1}, {**valid, 'schema': 'unknown'}, {'dump': valid['dump']}, []]
        for value in variants:
            with self.subTest(value=value), self.assertRaises(bundle.Refused):
                bundle.manifest(bundle.canonical(value), selected.dump.name, selected.media.name)
        raw = selected.bundle.read_bytes()
        for broken in (raw[:-2] + b',"schema":"kira.backup-bundle.v1"}\n', raw + b'{}', b'\xff',
                       b'{"schema":NaN}', b'{"dump":{"name":"a","name":"a"}}'):
            with self.subTest(raw=broken), self.assertRaises(bundle.Refused):
                bundle.manifest(broken, selected.dump.name, selected.media.name)
        boundary = raw.rstrip() + b' ' * (bundle.MANIFEST_LIMIT - len(raw.rstrip()))
        bundle.manifest(boundary, selected.dump.name, selected.media.name)
        with self.assertRaises(bundle.Refused):
            bundle.manifest(boundary + b' ', selected.dump.name, selected.media.name)
        valid['dump']['bytes'] = 2**63 - 1
        bundle.manifest(bundle.canonical(valid), selected.dump.name, selected.media.name)

    def test_explicit_legacy_two_record_uses_only_selected_relocated_bytes(self):
        selected = make_pair(self.base / 'old')
        rows = [hashlib.sha256(path.read_bytes()).hexdigest() + separator + str(path) + '\n'
                for path, separator in ((selected.dump, '  '), (selected.media, ' *'))]
        legacy = put(selected.bundle.with_suffix('.sha256'), ''.join(rows).encode())
        moved = self.base / 'relocated'
        shutil.copytree(selected.dump.parent, moved)
        pin = bundle.digest(legacy.read_bytes())
        args = [moved / legacy.name, moved / selected.dump.name, moved / selected.media.name]
        with self.assertRaises(bundle.Refused):
            bundle.selected(*args, pin)
        facts = bundle.selected(*args, pin, legacy=True)
        self.assertEqual('legacy-two-record', facts['profile'])
        put(args[1], b'modified while valid originals survive')
        with self.assertRaises(bundle.Refused):
            bundle.selected(*args, pin, legacy=True)
        shutil.copyfile(selected.dump, args[1])
        shutil.rmtree(selected.dump.parent)
        self.assertEqual(facts, bundle.selected(*args, pin, legacy=True))

    def test_legacy_rejects_extra_swapped_escaped_unsafe_and_oversize_records(self):
        first = 'a' * 64 + '  /old/selected.dump\n'
        second = 'b' * 64 + ' *' + '/old/selected.media.tar.gz\n'
        valid = first + second
        variants = (second + first, first + first, valid + second, first, valid.rstrip('\n'),
                    valid.replace('/old/', '/old/../'), valid.replace('/old/', '/old\\name/'),
                    valid.replace('/old/', '/old\t/'), valid.replace('  /', ' /', 1),
                    valid.replace('a' * 64, 'A' * 64), valid.replace('/old/', 'relative/'),
                    valid.replace('/old/', '/é/'), valid.replace('\n', '\r\n'))
        for raw in variants:
            with self.subTest(raw=raw), self.assertRaises(bundle.Refused):
                bundle.manifest(raw.encode(), 'selected.dump', 'selected.media.tar.gz', legacy=True)
        padding = 'x' * (bundle.LEGACY_LIMIT - len(valid))
        boundary = (first.replace('/old/', '/' + padding + 'old/') + second).encode()
        self.assertEqual(bundle.LEGACY_LIMIT, len(boundary))
        bundle.manifest(boundary, 'selected.dump', 'selected.media.tar.gz', legacy=True)
        with self.assertRaises(bundle.Refused):
            bundle.manifest(boundary + b'\n', 'selected.dump', 'selected.media.tar.gz', legacy=True)

    def test_snapshot_retains_verified_bytes_after_original_replacement(self):
        selected = make_pair(self.base / 'original')
        snapshot = self.base / 'snapshot'
        snapshot.mkdir(mode=0o700)
        with bundle.directory(snapshot, private=True) as fd:
            facts = bundle.selected(selected.bundle, selected.dump, selected.media, selected.pin, snapshot=fd)
        put(selected.dump, b'replaced')
        put(selected.media, b'replaced')
        bundle.frozen(snapshot, self.open_directory(snapshot), facts)
        self.assertNotEqual(selected.dump.read_bytes(), (snapshot / selected.dump.name).read_bytes())
        for member in snapshot.iterdir():
            self.assertEqual(0o600, stat.S_IMODE(member.stat().st_mode))

    def open_directory(self, path):
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        self.addCleanup(os.close, fd)
        return fd

    def test_snapshot_space_and_midread_mutation_fail_before_frozen_publication(self):
        selected = make_pair(self.base / 'original')
        snapshot = self.base / 'snapshot'
        snapshot.mkdir(mode=0o700)
        space = SimpleNamespace(f_bavail=0, f_frsize=4096, f_files=100, f_favail=100)
        with bundle.directory(snapshot) as fd, patch.object(bundle.os, 'fstatvfs', return_value=space):
            with self.assertRaises(bundle.Refused):
                bundle.selected(selected.bundle, selected.dump, selected.media, selected.pin, snapshot=fd)
        self.assertEqual([], list(snapshot.iterdir()))
        original_regular = bundle.regular

        @contextlib.contextmanager
        def changing(parent, name, private=False):
            with original_regular(parent, name, private) as (source, info):
                if name != selected.dump.name:
                    yield source, info
                    return
                first = True

                def read(size):
                    nonlocal first
                    raw = source.read(size)
                    if first:
                        first = False
                        with selected.dump.open('ab') as writer:
                            writer.write(b'changed-during-read')
                    return raw

                yield SimpleNamespace(read=read, fileno=source.fileno, seek=source.seek), info

        with bundle.directory(snapshot) as fd, patch.object(bundle, 'regular', changing), patch.object(bundle, 'CHUNK', 4):
            with self.assertRaises(bundle.Refused):
                bundle.selected(selected.bundle, selected.dump, selected.media, selected.pin, snapshot=fd)
        self.assertFalse((snapshot / selected.bundle.name).exists())

    def test_tampered_request_cannot_escape_attempt_via_selection_name(self):
        selected = make_pair(self.base / 'selected')
        facts = bundle.selected(selected.bundle, selected.dump, selected.media, selected.pin)
        for prefix in ('../', '/', './', 'dir/', '\\'):
            changed = copy.deepcopy(facts)
            for role in ('manifest', 'dump', 'media'):
                changed[role]['name'] = prefix + changed[role]['name']
            with self.subTest(prefix=prefix), self.assertRaises(bundle.Refused):
                bundle.selection_record(changed)


class TarProfileTests(OwnedCase):
    def test_empty_root_and_regular_tree_extract_privately_ignoring_modes(self):
        self.assertEqual(0, self.inspect_tar(tar_bytes(root=False))['files'])
        self.assertEqual(1, self.inspect_tar(tar_bytes())['entries'])
        stage = self.base / 'stage'
        stage.mkdir(mode=0o700)
        with bundle.directory(stage) as fd:
            facts = self.inspect_tar(tar_bytes([('./nested/file', b'\x00\xff')]), extract=fd)
        self.assertEqual(2, facts['bytes'])
        self.assertEqual(b'\x00\xff', (stage / 'nested/file').read_bytes())
        self.assertEqual(0o700, stat.S_IMODE((stage / 'nested').stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((stage / 'nested/file').stat().st_mode))
        self.assertEqual(os.geteuid(), (stage / 'nested/file').stat().st_uid)

    def test_traversal_links_sparse_devices_duplicates_and_file_parents_fail(self):
        bad_names = ('/outside', '../outside', 'a/../b', 'a/./b', 'a//b', 'a\\b', 'a\nb', 'a\x85b', '', '.')
        for name in bad_names:
            with self.subTest(name=name), self.assertRaises(BAD_ARCHIVE):
                self.inspect_tar(raw_tar(tar_header(name)))
        kinds = (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.GNUTYPE_SPARSE,
                 tarfile.CHRTYPE, tarfile.BLKTYPE, tarfile.FIFOTYPE, tarfile.GNUTYPE_LONGLINK, b'Z')
        for kind in kinds:
            with self.subTest(kind=kind), self.assertRaises(BAD_ARCHIVE):
                self.inspect_tar(raw_tar(tar_header('x', kind=kind)))
        for names in (('a', './a'), ('a', 'a/b'), ('a/b', 'a')):
            with self.subTest(names=names), self.assertRaises(BAD_ARCHIVE):
                self.inspect_tar(raw_tar(*(tar_header(name) for name in names)))
        with self.assertRaises(BAD_ARCHIVE):
            self.inspect_tar(raw_tar(tar_header('.', kind=tarfile.DIRTYPE),
                                     tar_header('./', kind=tarfile.DIRTYPE)))

    def test_gnu_pax_resolved_names_and_closed_metadata_keys(self):
        good = metadata(tarfile.GNUTYPE_LONGNAME, b'nested/item\0') + tar_header('placeholder')
        self.assertEqual(1, self.inspect_tar(raw_tar(good))['files'])
        good = metadata(tarfile.XHDTYPE, pax('path', 'nested/pax')) + tar_header('placeholder')
        self.assertEqual(1, self.inspect_tar(raw_tar(good))['files'])
        for kind, raw in ((tarfile.GNUTYPE_LONGNAME, b'../outside\0'),
                          (tarfile.GNUTYPE_LONGNAME, b'no-terminator'),
                          (tarfile.XHDTYPE, pax('path', '../outside')),
                          (tarfile.XGLTYPE, pax('path', '/outside')),
                          (tarfile.XHDTYPE, pax('GNU.sparse.size', '0')),
                          (tarfile.XHDTYPE, pax('size', '-1')),
                          (tarfile.XHDTYPE, pax('path', 'a') + pax('path', 'b'))):
            with self.subTest(kind=kind, raw=raw), self.assertRaises(BAD_ARCHIVE):
                self.inspect_tar(raw_tar(metadata(kind, raw), tar_header('placeholder')))
        with self.assertRaises(BAD_ARCHIVE):
            self.inspect_tar(raw_tar(metadata(tarfile.XHDTYPE, pax('path', 'orphan'))))

    def test_path_and_depth_boundaries_use_utf8_bytes_not_character_count(self):
        self.assertEqual(1024, bundle.PATH_LIMIT)
        self.assertEqual(32, bundle.DEPTH_LIMIT)
        self.assertEqual('a' * 1024, bundle.tar_name('a' * 1024, False))
        bundle.tar_name('é' * 512, False)
        bundle.tar_name('/'.join(['a'] * 32), False)
        for name in ('a' * 1025, 'é' * 513, '/'.join(['a'] * 33)):
            with self.subTest(name=name), self.assertRaises(bundle.Refused):
                bundle.tar_name(name, False)

    def test_metadata_limit_enforced_before_declared_payload_read(self):
        self.assertEqual(65536, bundle.METADATA_LIMIT)
        prefix = b'65536 uname='
        raw = prefix + b'a' * (65536 - len(prefix) - 1) + b'\n'
        self.assertEqual(1, self.inspect_tar(raw_tar(metadata(tarfile.XHDTYPE, raw), tar_header()))['files'])
        original = bundle.TarReader.exact
        requested = []

        def tracked(reader, size, sink=None):
            requested.append(size)
            return original(reader, size, sink)

        for kind in (tarfile.XHDTYPE, tarfile.XGLTYPE, tarfile.GNUTYPE_LONGNAME):
            requested.clear()
            with patch.object(bundle.TarReader, 'exact', tracked), self.assertRaisesRegex(bundle.Refused, 'metadata limit'):
                self.inspect_tar(raw_tar(tar_header('metadata', 65537, kind)))
            self.assertEqual([512], requested)

    def test_file_and_total_cap_declarations_without_giant_payload_fixtures(self):
        self.assertEqual(512 * 1024 * 1024, bundle.FILE_LIMIT)
        self.assertEqual(32 * 1024 * 1024 * 1024, bundle.TOTAL_LIMIT)
        original = bundle.TarReader.exact
        payload_calls = []

        def omitted_payload(reader, size, sink=None):
            if sink is not None:
                payload_calls.append(size)
                return b''  # Declaration gate only; real small payload tests remain above.
            return original(reader, size, sink)

        with patch.object(bundle.TarReader, 'exact', omitted_payload):
            self.assertEqual(bundle.FILE_LIMIT, self.inspect_tar(raw_tar(tar_header(size=bundle.FILE_LIMIT)))['bytes'])
            payload_calls.clear()
            with self.assertRaisesRegex(bundle.Refused, 'member size'):
                self.inspect_tar(raw_tar(tar_header(size=bundle.FILE_LIMIT + 1)))
            self.assertEqual([], payload_calls)
            maximum = [tar_header(str(index), bundle.FILE_LIMIT) for index in range(64)]
            self.assertEqual(bundle.TOTAL_LIMIT, self.inspect_tar(raw_tar(*maximum))['bytes'])
            with self.assertRaisesRegex(bundle.Refused, 'expanded-byte limit'):
                self.inspect_tar(raw_tar(*maximum, tar_header('over', 1)))

    def test_entry_limit_boundary_uses_generated_headers_not_a_giant_archive(self):
        self.assertEqual(100_000, bundle.ENTRY_LIMIT)
        for count in (100_000, 100_001):
            index = 0

            def generated(reader, size, sink=None):
                nonlocal index
                if size == 512:
                    index += 1
                    return tar_header(f'{index:06}') if index <= count else bytes(512)
                return b''

            with patch.object(bundle.TarReader, 'exact', generated), patch.object(bundle.TarReader, 'read', return_value=b''):
                if count == 100_000:
                    self.assertEqual(count, self.inspect_tar(tar_bytes(root=False))['entries'])
                else:
                    with self.assertRaisesRegex(bundle.Refused, 'entry limit'):
                        self.inspect_tar(tar_bytes(root=False))

    def test_bounded_reads_crc_truncation_padding_and_space_failures(self):
        reader = bundle.TarReader(io.BytesIO(b'1234'))
        with self.assertRaises(bundle.Refused):
            reader.read(bundle.CHUNK + 1)
        with self.assertRaises(bundle.Refused):
            reader.exact(bundle.METADATA_LIMIT + 1)
        reader.maximum = 3
        with self.assertRaises(bundle.Refused):
            reader.read(4)
        valid = tar_bytes([('file', b'content')])
        bad_crc = bytearray(valid)
        bad_crc[-8] ^= 1
        bad_header = bytearray(tar_header())
        bad_header[0] ^= 1
        for raw in (b'', b'not gzip', valid[:-1], bytes(bad_crc), raw_tar(bytes(bad_header)), raw_tar(tar_header(size=1024)),
                    gzip.compress(bytes(1024) + b'extra'), gzip.compress(bytes(1024 + 10241))):
            with self.subTest(raw=raw[:32]), self.assertRaises(BAD_ARCHIVE):
                self.inspect_tar(raw)
        # Valid gzip header, reserved DEFLATE BTYPE=3, then an inert trailer.
        # Pin the decoder exception too: an EOF/CRC failure would not exercise C1.
        bad_deflate = bytes.fromhex('1f8b0800000000000003') + b'\x07' + bytes(8)
        with self.assertRaises(zlib.error):
            self.inspect_tar(bad_deflate)
        selected = make_pair(self.base / 'corrupt-deflate')
        selected.bundle.unlink()
        put(selected.media, bad_deflate)
        result = subprocess.run(
            [sys.executable, '-I', '-B', str(HELPER), 'create', '--bundle', str(selected.bundle),
             '--dump', str(selected.dump), '--media', str(selected.media)],
            capture_output=True, timeout=10)
        self.failed(result)
        self.assertEqual(b'', result.stdout)
        self.assertTrue(result.stderr.startswith(b'backup_bundle: REFUSED/STOP;'))
        self.assertNotIn(b'Traceback', result.stderr)
        self.assertFalse(selected.bundle.exists())
        stage = self.base / 'stage'
        stage.mkdir(mode=0o700)
        space = SimpleNamespace(f_bavail=0, f_frsize=4096, f_files=1, f_favail=0)
        with bundle.directory(stage) as fd, patch.object(bundle.os, 'fstatvfs', return_value=space):
            with self.assertRaises(bundle.Refused):
                self.inspect_tar(valid, extract=fd)
        self.assertEqual([], list(stage.iterdir()))


class PublicationTests(OwnedCase):
    def stage(self, name='stage'):
        stage = self.base / name
        stage.mkdir(mode=0o700)
        put(stage / 'payload', b'complete bytes')
        put(stage / 'ancillary-receiver-obligation.json', b'{}\n')
        return stage

    def test_directory_noreplace_and_ancillary_private_files(self):
        stage, target = self.stage(), self.base / 'target'
        bundle.publish_directory(stage, target)
        self.assertFalse(stage.exists())
        self.assertEqual(b'complete bytes', (target / 'payload').read_bytes())
        self.assertTrue((target / 'ancillary-receiver-obligation.json').is_file())
        again = self.stage('again')
        inode = target.stat().st_ino
        with self.assertRaises(bundle.Refused):
            bundle.publish_directory(again, target)
        self.assertEqual(inode, target.stat().st_ino)
        self.assertTrue(again.exists())
        empty = self.base / 'empty'
        empty.mkdir(mode=0o700)
        with self.assertRaises(bundle.Refused):
            bundle.publish_directory(again, empty)
        self.assertEqual([], list(empty.iterdir()))

    def test_missing_primitive_exdev_and_untrusted_stage_have_no_fallback(self):
        stage, target = self.stage(), self.base / 'target'
        with patch.object(bundle.ctypes, 'CDLL', return_value=SimpleNamespace()):
            with self.assertRaises(bundle.Refused):
                bundle.publish_directory(stage, target)

        def cross_device(*_):
            ctypes.set_errno(errno.EXDEV)
            return -1

        with patch.object(bundle.ctypes, 'CDLL', return_value=SimpleNamespace(renameat2=cross_device)), \
                patch.object(bundle.os, 'rename', side_effect=AssertionError('unsafe fallback')):
            with self.assertRaises(OSError):
                bundle.publish_directory(stage, target)
        self.assertTrue(stage.exists())
        self.assertFalse(target.exists())
        (stage / 'payload').chmod(0o644)
        with self.assertRaises(bundle.Refused):
            bundle.publish_directory(stage, target)
        (stage / 'payload').unlink()
        (stage / 'payload').symlink_to('/outside')
        with self.assertRaises(OSError):
            bundle.publish_directory(stage, target)
        stage.chmod(0o777)
        with self.assertRaises(bundle.Refused):
            bundle.publish_directory(stage, target)

    def test_target_race_preserves_the_unrelated_directory(self):
        stage, target = self.stage(), self.base / 'target'
        real = ctypes.CDLL(None, use_errno=True).renameat2
        real.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        real.restype = ctypes.c_int

        def race(*arguments):
            target.mkdir(mode=0o700)
            put(target / 'unrelated', b'not ours')
            return real(*arguments)

        with patch.object(bundle.ctypes, 'CDLL', return_value=SimpleNamespace(renameat2=race)):
            with self.assertRaises(OSError):
                bundle.publish_directory(stage, target)
        self.assertEqual(b'not ours', (target / 'unrelated').read_bytes())
        self.assertTrue(stage.exists())

    def test_actual_rename_then_fsync_failure_retains_published_target(self):
        stage, target = self.stage(), self.base / 'target'
        fsync = os.fsync

        def fail_after_rename(fd):
            if target.exists() and not stage.exists():
                raise OSError(errno.EIO, 'injected after real rename')
            fsync(fd)

        with patch.object(bundle.os, 'fsync', side_effect=fail_after_rename):
            with self.assertRaises(bundle.PublishedButUnconfirmed):
                bundle.publish_directory(stage, target)
        self.assertFalse(stage.exists())
        self.assertEqual(b'complete bytes', (target / 'payload').read_bytes())

    def test_receipt_link_and_each_directory_fsync_partial_effect_are_cleaned(self):
        for point in ('link', 'first-parent-fsync', 'last-parent-fsync'):
            root = self.base / point
            root.mkdir(mode=0o700)
            put(root / 'unrelated', b'keep')
            link, fsync = os.link, os.fsync
            calls = 0

            def partial_link(*args, **kwargs):
                link(*args, **kwargs)
                if point == 'link':
                    raise OSError(errno.EIO, 'injected AFTER actual link')

            def partial_fsync(fd):
                nonlocal calls
                calls += 1
                if calls == (2 if point == 'first-parent-fsync' else 3) and point != 'link':
                    raise OSError(errno.EIO, 'injected after receipt became visible')
                fsync(fd)

            with bundle.directory(root) as fd, patch.object(bundle.os, 'link', side_effect=partial_link), \
                    patch.object(bundle.os, 'fsync', side_effect=partial_fsync):
                with self.assertRaises(OSError):
                    bundle.publish_bytes(fd, 'receipt.json', b'{}\n')
            self.assertEqual(['unrelated'], [path.name for path in root.iterdir()])
            self.assertEqual(b'keep', (root / 'unrelated').read_bytes())

    def test_storage_can_prevent_negative_proof_but_cannot_make_call_successful(self):
        fsync = os.fsync
        with bundle.directory(self.base) as parent:
            def failed_directory_sync(fd):
                if fd == parent:
                    raise OSError(errno.EIO, 'storage unavailable')
                fsync(fd)

            with patch.object(bundle.os, 'fsync', side_effect=failed_directory_sync), \
                    patch.object(bundle, 'exact_unlink', side_effect=OSError(errno.EIO, 'unlink unavailable')):
                with self.assertRaises(OSError):
                    bundle.publish_bytes(parent, 'receipt.json', b'{}\n')
            # Expected limitation: visibility is NOT completion or durable truth.
            self.assertTrue((self.base / 'receipt.json').exists())
            with self.assertRaises(bundle.Refused):
                bundle.publish_bytes(parent, 'receipt.json', b'new')
        self.assertEqual(b'{}\n', (self.base / 'receipt.json').read_bytes())


class PortableRestoreTests(OwnedCase):
    def assert_stopped(self, fixture, db_receipt=False):
        self.assertTrue((fixture.attempt / 'STOP.json').is_file())
        self.assertEqual(db_receipt, (fixture.attempt / 'db.json').is_file())
        self.assertFalse((fixture.attempt / 'pair.json').exists())
        self.assertFalse(fixture.target.exists())

    def test_exact_wrappers_freeze_pair_then_issue_bound_stage_receipts(self):
        fixture = self.fixture()
        fixture.flags(replace_originals=True)
        expected_dump = fixture.pair.dump.read_bytes()
        self.ok(fixture.db())
        self.assertEqual(expected_dump, (fixture.base / 'database-effect.bin').read_bytes())
        self.assertNotEqual(expected_dump, fixture.pair.dump.read_bytes())
        self.assertFalse(fixture.target.exists())
        request = json.loads((fixture.attempt / 'request.json').read_bytes())
        db = json.loads((fixture.attempt / 'db.json').read_bytes())
        self.assertEqual('kira.restore-attempt.v1', request['schema'])
        self.assertEqual(bundle.digest((fixture.attempt / 'request.json').read_bytes()), db['request_sha256'])
        self.assertEqual(request['id'], db['id'])
        self.assertEqual(request['selection'], db['selection'])
        self.assertEqual('12', db['source_version'])
        self.assertEqual(12345, db['identity']['oid'])
        self.assertEqual(bundle.digest(bundle.canonical(history())), db['history_sha256'])
        self.ok(fixture.media())
        pair = json.loads((fixture.attempt / 'pair.json').read_bytes())
        self.assertEqual('kira.restore-pair.v1', pair['schema'])
        self.assertEqual(bundle.digest((fixture.attempt / 'db.json').read_bytes()), pair['database_receipt_sha256'])
        self.assertEqual(str(fixture.target), pair['target']['path'])
        self.assertEqual(fixture.target.stat().st_ino, pair['target']['inode'])
        self.assertEqual(b'\x00\xffA', (fixture.target / 'tutorial/item.bin').read_bytes())
        self.assertFalse((fixture.attempt / 'STOP.json').exists())
        before = {path.name: path.read_bytes() for path in fixture.attempt.iterdir()}
        self.failed(fixture.media())
        self.failed(fixture.db())
        self.assertEqual(before, {path.name: path.read_bytes() for path in fixture.attempt.iterdir()})

    def test_all_guards_enforced_by_wrappers_and_private_functions_before_pg(self):
        variants = ({'PGDATABASE': 'kira'}, {'PGDATABASE': 'kira_restore_'},
                    {'KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST': 'no'}, {'KIRA_ENVIRONMENT': 'production'},
                    {'KIRA_RESTORE_CUSTODY_CONFIRMED': 'no'}, {'PGHOST': ''}, {'PGUSER': ''},
                    {'PGPORT': '0'}, {'PGPORT': '65536'})
        for changed in variants:
            fixture = self.fixture()
            environment = {**fixture.env, **changed}
            with self.subTest(changed=changed):
                self.failed(fixture.run('verify-restore.sh', fixture.argv(), environment))
                self.failed(fixture.run('restore-media.sh', [str(fixture.attempt)], environment))
                with patch.dict(os.environ, environment, clear=True):
                    with self.assertRaises(bundle.Refused):
                        bundle.restore_database(*fixture.argv())
                    with self.assertRaises(bundle.Refused):
                        bundle.restore_media(fixture.attempt)
                self.assertEqual([], fixture.state()['calls'])
                self.assertFalse(fixture.attempt.exists())
        fixture = self.fixture()
        fixture.flags(old_client=True)
        self.failed(fixture.db())
        self.assertFalse(fixture.attempt.exists())

    def test_old_independent_cli_forms_are_refused_and_tls_defaults_are_retained(self):
        fixture = self.fixture()
        self.failed(fixture.run('verify-restore.sh', [str(fixture.pair.dump)]))
        self.failed(fixture.run('restore-media.sh', [str(fixture.pair.media), str(fixture.target)]))
        self.assertEqual([], fixture.state()['calls'])
        fixture.env.pop('PGSSLMODE')
        fixture.env.pop('PGPORT')
        fixture.env['PGPASSWORD'] = 'fixture-secret-never-in-receipt'
        self.ok(fixture.db())
        self.assertEqual('verify-full', fixture.state()['restore_tls'])
        request = (fixture.attempt / 'request.json').read_bytes()
        receipt = (fixture.attempt / 'db.json').read_bytes()
        self.assertNotIn(b'fixture-secret-never-in-receipt', request + receipt)
        self.assertEqual('5432', json.loads(request)['endpoint']['port'])

    def test_private_functions_reject_unsupported_runtime_before_commands(self):
        fixture = self.fixture()
        for attribute, value in (('platform', 'darwin'), ('version_info', (3, 10))):
            with patch.object(bundle.sys, attribute, value), patch.dict(os.environ, fixture.env, clear=True):
                with self.assertRaises(bundle.Refused):
                    bundle.restore_database(*fixture.argv())
                with self.assertRaises(bundle.Refused):
                    bundle.restore_media(fixture.attempt)
                with self.assertRaises(bundle.Refused):
                    bundle.portable_backup(str(fixture.base / 'out.dump'), str(fixture.base))
        self.assertEqual([], fixture.state()['calls'])

    def test_explicit_legacy_wrapper_snapshots_pair_and_empty_media_publishes(self):
        fixture = self.fixture()
        fixture.pair = make_pair(fixture.base / 'empty-input', members=[])
        lines = [bundle.digest(path.read_bytes()) + '  ' + str(path) + '\n'
                 for path in (fixture.pair.dump, fixture.pair.media)]
        fixture.pair.bundle = put(fixture.pair.bundle.with_suffix('.sha256'), ''.join(lines).encode())
        fixture.pair.pin = bundle.digest(fixture.pair.bundle.read_bytes())
        self.ok(fixture.run('verify-restore.sh', fixture.argv() + ['--legacy-two-record']))
        request = json.loads((fixture.attempt / 'request.json').read_bytes())
        self.assertEqual('legacy-two-record', request['selection']['profile'])
        shutil.rmtree(fixture.pair.bundle.parent)
        self.ok(fixture.media())
        self.assertEqual([], list(fixture.target.iterdir()))
        self.assertTrue((fixture.attempt / 'pair.json').is_file())

    def test_existing_empty_target_untrusted_parent_and_bad_pin_prevent_restore(self):
        fixture = self.fixture()
        fixture.target.mkdir(mode=0o700)
        self.failed(fixture.db())
        self.assertFalse(fixture.attempt.exists())
        fixture.target.rmdir()
        fixture.pair.pin = '0' * 64
        self.failed(fixture.db())
        self.assert_stopped(fixture)
        self.assertFalse((fixture.base / 'database-effect.bin').exists())
        other = self.fixture()
        unsafe = other.base / 'unsafe'
        unsafe.mkdir(mode=0o777)
        unsafe.chmod(0o777)
        other.target = unsafe / 'media'
        self.failed(other.db())
        self.assertFalse(other.attempt.exists())

    def test_bad_selected_pair_and_unsafe_tar_fail_before_database_started(self):
        for failure in ('mixed', 'unsafe-tar'):
            fixture = self.fixture()
            if failure == 'mixed':
                put(fixture.pair.media, tar_bytes([('B', b'other bundle')]))
            else:
                put(fixture.pair.media, raw_tar(tar_header('../escape')))
                value = json.loads(fixture.pair.bundle.read_bytes())
                value['media'] = {'name': fixture.pair.media.name, 'bytes': fixture.pair.media.stat().st_size,
                                  'sha256': bundle.digest(fixture.pair.media.read_bytes())}
                put(fixture.pair.bundle, bundle.canonical(value))
                fixture.pair.pin = bundle.digest(fixture.pair.bundle.read_bytes())
            self.failed(fixture.db())
            self.assert_stopped(fixture)
            self.assertFalse((fixture.attempt / 'db-started.json').exists())
            self.assertFalse((fixture.base / 'database-effect.bin').exists())

    def test_list_restore_and_post_restore_failures_do_not_infer_rollback(self):
        for flag in ('list_failure', 'restore_failure', 'sql_failure', 'sql_oversize'):
            fixture = self.fixture()
            fixture.flags(**{flag: True})
            self.failed(fixture.db())
            self.assert_stopped(fixture)
            self.assertEqual(flag != 'list_failure', (fixture.attempt / 'db-started.json').exists())
            self.assertEqual(flag != 'list_failure', (fixture.base / 'database-effect.bin').exists())
            calls = len(fixture.state()['calls'])
            self.failed(fixture.media())
            # A stopped attempt never queries the database again; --version is harmless.
            later = fixture.state()['calls'][calls:]
            self.assertTrue(all(call['args'] == ['--version'] for call in later))

    def test_empty_false_earlier_failure_wrong_last_rank_and_sql_values(self):
        failed_first = history()
        failed_first[0]['success'] = False
        no_version = history()
        for row in no_version:
            row['version'] = None
        wrong_rank = history('9')
        wrong_rank[0]['version'] = '12'
        boolean_rank = history()
        boolean_rank[0]['rank'] = True
        numeric_success = history()
        numeric_success[0]['success'] = 1
        cases = ([], failed_first, no_version, wrong_rank, boolean_rank, numeric_success)
        for rows in cases:
            fixture = self.fixture()
            fixture.update(history=rows)
            self.failed(fixture.db())
            self.assert_stopped(fixture)
            self.assertTrue((fixture.base / 'database-effect.bin').exists())
        for raw in ('', 'f', '[]', '{}', 'null', 'true', '{invalid'):
            fixture = self.fixture()
            fixture.flags(sql_raw=raw or '\n')
            self.failed(fixture.db())
            self.assert_stopped(fixture)

    def test_numeric_version_grammar_and_last_installed_not_lexical_max(self):
        fixture = self.fixture()
        rows = history('10')
        rows[0]['version'] = '9'
        fixture.update(history=rows)
        self.ok(fixture.db(version='10'))
        self.assertEqual('10', json.loads((fixture.attempt / 'db.json').read_bytes())['source_version'])
        bundle.source_version('1' * 64)
        bundle.source_version('12.0_1')
        for version in ('', '1' * 65, '1;SELECT 1', ' 12', '12.', '١٢', '12\n', 'v12'):
            other = self.fixture()
            self.failed(other.db(version=version))
            self.assertFalse(other.attempt.exists())
            self.assertEqual([], other.state()['calls'])

    def test_media_rejects_mixed_snapshot_cross_attempt_and_boolean_receipt_oid(self):
        for change in ('snapshot', 'id', 'oid-bool', 'request-target'):
            fixture = self.fixture()
            fixture.update(oid=1)
            self.ok(fixture.db())
            if change == 'snapshot':
                put(fixture.attempt / fixture.pair.media.name, tar_bytes([('B', b'valid but other media')]))
            elif change == 'request-target':
                path = fixture.attempt / 'request.json'
                record = json.loads(path.read_bytes())
                record['media_target'] = str(fixture.base / 'other-target')
                put(path, bundle.canonical(record))
            else:
                path = fixture.attempt / 'db.json'
                record = json.loads(path.read_bytes())
                if change == 'id':
                    record['id'] = '00000000-0000-0000-0000-000000000000'
                else:
                    record['identity']['oid'] = True  # True == 1 must NOT pass correspondence.
                put(path, bundle.canonical(record))
            self.failed(fixture.media())
            self.assert_stopped(fixture, db_receipt=True)

    def test_media_rechecks_oid_and_every_history_field_including_success(self):
        for field in ('oid', 'rank', 'version', 'type', 'script', 'checksum', 'success'):
            fixture = self.fixture()
            self.ok(fixture.db())
            if field == 'oid':
                fixture.update(oid=12346)
            else:
                rows = history()
                changes = {'rank': -10, 'version': '0', 'type': 'JDBC', 'script': 'changed.sql',
                           'checksum': 999, 'success': False}
                rows[0][field] = changes[field]
                fixture.update(history=rows)
            self.failed(fixture.media())
            self.assert_stopped(fixture, db_receipt=True)
            self.assertFalse((fixture.attempt / 'media-started.json').exists())

    def test_attempt_lock_started_interruption_and_existing_target_stop(self):
        fixture = self.fixture()
        self.ok(fixture.db())
        with bundle.directory(fixture.attempt) as parent, bundle.attempt_lock(parent):
            self.failed(fixture.media())
        self.assertFalse((fixture.attempt / 'STOP.json').exists())
        put(fixture.attempt / 'media-started.json', b'{}\n')
        self.failed(fixture.media())
        self.assertFalse(fixture.target.exists())
        other = self.fixture()
        self.ok(other.db())
        other.target.mkdir(mode=0o700)
        put(other.target / 'unrelated', b'keep')
        self.failed(other.media())
        self.assertEqual(b'keep', (other.target / 'unrelated').read_bytes())
        self.assertFalse((other.attempt / 'pair.json').exists())

    def test_extract_failure_cleans_only_its_finished_private_stage(self):
        fixture = self.fixture()
        fixture.direct_db()
        original = bundle.safe_tar

        def partial(stream, extract=None):
            if extract is not None:
                fd = os.open('partial', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600, dir_fd=extract)
                os.write(fd, b'partial')
                os.close(fd)
                raise OSError(errno.ENOSPC, 'injected after extraction effect')
            return original(stream)

        unrelated = fixture.base / '.unrelated-stage'
        unrelated.mkdir(mode=0o700)
        put(unrelated / 'keep', b'keep')
        with patch.object(bundle, 'safe_tar', side_effect=partial):
            with self.assertRaises(bundle.Refused):
                fixture.direct_media()
        self.assert_stopped(fixture, db_receipt=True)
        self.assertTrue((fixture.attempt / 'media-started.json').is_file())
        self.assertEqual([], list(fixture.base.glob('.kira-media-*')))
        self.assertEqual(b'keep', (unrelated / 'keep').read_bytes())

    def test_second_history_recheck_blocks_publication_after_extraction(self):
        fixture = self.fixture()
        fixture.direct_db()
        original = bundle.database_state
        calls = 0

        def changed(environment, version):
            nonlocal calls
            calls += 1
            state = original(environment, version)
            if calls == 2:
                state['identity']['oid'] += 1
            return state

        with patch.object(bundle, 'database_state', side_effect=changed):
            with self.assertRaises(bundle.Refused):
                fixture.direct_media()
        self.assert_stopped(fixture, db_receipt=True)
        self.assertEqual([], list(fixture.base.glob('.kira-media-*')))

    def test_database_receipt_partial_link_failure_has_no_success_credit(self):
        fixture = self.fixture()
        link = os.link

        def partial(source, target, **kwargs):
            link(source, target, **kwargs)
            if target == 'db.json':
                raise OSError(errno.EIO, 'after real database receipt link')

        with patch.object(bundle.os, 'link', side_effect=partial):
            with self.assertRaises(bundle.Refused):
                fixture.direct_db()
        self.assert_stopped(fixture)
        self.assertTrue((fixture.base / 'database-effect.bin').is_file())

    def test_real_media_publication_then_receipt_failure_preserves_target_and_stop(self):
        fixture = self.fixture()
        fixture.direct_db()
        link = os.link

        def partial(source, target, **kwargs):
            link(source, target, **kwargs)
            if target == 'pair.json':
                raise OSError(errno.EIO, 'after real pair receipt link')

        with patch.object(bundle.os, 'link', side_effect=partial):
            with self.assertRaisesRegex(bundle.Refused, 'target published but pair receipt failed'):
                fixture.direct_media()
        self.assertTrue((fixture.attempt / 'STOP.json').exists())
        self.assertFalse((fixture.attempt / 'pair.json').exists())
        self.assertEqual(b'\x00\xffA', (fixture.target / 'tutorial/item.bin').read_bytes())
        self.failed(fixture.media())
        self.assertEqual(b'\x00\xffA', (fixture.target / 'tutorial/item.bin').read_bytes())

    def test_media_rename_then_fsync_failure_is_ambiguous_not_rollback(self):
        fixture = self.fixture()
        fixture.direct_db()
        fsync = os.fsync
        failed = False

        def partial(fd):
            nonlocal failed
            if fixture.target.exists() and not failed:
                failed = True
                raise OSError(errno.EIO, 'after actual directory publication')
            fsync(fd)

        with patch.object(bundle.os, 'fsync', side_effect=partial):
            with self.assertRaisesRegex(bundle.Refused, 'publication ambiguous'):
                fixture.direct_media()
        self.assertTrue((fixture.attempt / 'STOP.json').exists())
        self.assertFalse((fixture.attempt / 'pair.json').exists())
        self.assertTrue((fixture.target / 'tutorial/item.bin').is_file())

    def test_receipt_unlink_and_stop_storage_failure_still_returns_failure(self):
        fixture = self.fixture()
        fixture.direct_db()
        fsync, unlink, record = os.fsync, bundle.exact_unlink, bundle.record

        def failed_sync(fd):
            if (fixture.attempt / 'pair.json').exists():
                raise OSError(errno.EIO, 'storage unavailable after visible receipt')
            fsync(fd)

        def failed_unlink(parent, name, identity):
            if name == 'pair.json':
                raise OSError(errno.EIO, 'cannot remove visible receipt')
            return unlink(parent, name, identity)

        def failed_stop(parent, name, value):
            if name == 'STOP.json':
                raise OSError(errno.EIO, 'cannot record durable negative proof')
            return record(parent, name, value)

        with patch.object(bundle.os, 'fsync', side_effect=failed_sync), \
                patch.object(bundle, 'exact_unlink', side_effect=failed_unlink), \
                patch.object(bundle, 'record', side_effect=failed_stop):
            with self.assertRaises(bundle.Refused):
                fixture.direct_media()
        self.assertTrue((fixture.attempt / 'pair.json').exists())
        self.assertFalse((fixture.attempt / 'STOP.json').exists())
        self.assertTrue(fixture.target.exists())
        # The failed invocation cannot be advanced from link visibility. Even a
        # repeat is refused rather than returning cached success.
        self.failed(fixture.media())


class PortableProducerTests(OwnedCase):
    def producer(self, fixture):
        output = fixture.base / 'output'
        output.mkdir(mode=0o700)
        media = fixture.base / 'live-media'
        media.mkdir(mode=0o700)
        put(media / 'file', b'writer-frozen-fixture')
        fixture.env['KIRA_BACKUP_WRITERS_FROZEN_AND_DRAINED'] = 'yes'
        return output / 'produced.dump', media

    def test_attestation_is_mandatory_before_commands_even_private_entrypoint(self):
        fixture = self.fixture()
        output, media = self.producer(fixture)
        fixture.env.pop('KIRA_BACKUP_WRITERS_FROZEN_AND_DRAINED')
        self.failed(fixture.run('backup.sh', [str(output), str(media)]))
        with patch.dict(os.environ, fixture.env, clear=True), self.assertRaises(bundle.Refused):
            bundle.portable_backup(str(output), str(media))
        self.assertEqual([], fixture.state()['calls'])
        self.assertEqual([], list(output.parent.iterdir()))

    def test_manifest_published_last_no_overwrite_and_no_legacy_authority_emitted(self):
        fixture = self.fixture()
        output, media = self.producer(fixture)
        link = os.link
        published = []

        def observed(source, target, **kwargs):
            result = link(source, target, **kwargs)
            if (output.parent / target).exists():
                published.append(target)
            return result

        with patch.dict(os.environ, fixture.env, clear=True), patch.object(bundle.os, 'link', side_effect=observed):
            pin = bundle.portable_backup(str(output), str(media))
        manifest = output.with_suffix('.bundle.json')
        self.assertEqual(manifest.name, published[-1])
        self.assertEqual(1, published.count(manifest.name))
        self.assertFalse(output.with_suffix('.bundle.sha256').exists())
        self.assertEqual(pin, bundle.selected(manifest, output, output.with_suffix('.media.tar.gz'), pin)['manifest']['sha256'])
        before = {path.name: path.read_bytes() for path in output.parent.iterdir()}
        self.failed(fixture.run('backup.sh', [str(output), str(media)]))
        self.assertEqual(before, {path.name: path.read_bytes() for path in output.parent.iterdir()})

    def test_producer_command_failures_leave_unselectable_stage_not_manifest(self):
        for flag in ('dump_failure', 'list_failure', 'tar_failure'):
            fixture = self.fixture()
            output, media = self.producer(fixture)
            fixture.flags(**{flag: True})
            self.failed(fixture.run('backup.sh', [str(output), str(media)]))
            self.assertFalse(output.with_suffix('.bundle.json').exists())
            self.assertFalse(output.exists())
            self.assertEqual(1, len(list(output.parent.glob('.kira-backup-*'))))

    def test_producer_partial_link_failure_removes_only_newly_owned_outputs(self):
        fixture = self.fixture()
        output, media = self.producer(fixture)
        keep = put(output.parent / 'older.dump', b'older backup')
        link = os.link

        def partial(source, target, **kwargs):
            link(source, target, **kwargs)
            if target == output.with_suffix('.bundle.json').name and (output.parent / target).exists():
                raise OSError(errno.EIO, 'manifest final link effected, confirmation lost')

        with patch.dict(os.environ, fixture.env, clear=True), patch.object(bundle.os, 'link', side_effect=partial):
            with self.assertRaises(bundle.Refused):
                bundle.portable_backup(str(output), str(media))
        self.assertEqual(b'older backup', keep.read_bytes())
        self.assertFalse(output.with_suffix('.bundle.json').exists())
        self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
