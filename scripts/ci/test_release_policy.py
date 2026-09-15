"""Offline stdlib fixtures only: no GitHub, GPG/SSH, Docker, Gradle or publication.

Crypto/daemon commands are recording stubs, not claims about installed tools or
native approval. Reuse the existing tiny Docker-save byte fixture, not a service.
"""

import base64
import copy
import datetime as dt
import gzip
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import image_release as image
import release_policy as policy
from test_image_release import tiny_image, docker_bytes

SHA, MAIN, TREE = 'a' * 40, 'c' * 40, 'b' * 40
NOW = 1800000000
COMPLETED = dt.datetime.fromtimestamp(NOW - 60, dt.timezone.utc).isoformat()
PUBLIC_KEY = 'ssh-ed25519 ' + base64.b64encode(b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20' + b'x' * 32).decode()
TRUST = {'name': 'BACKEND_RELEASE_SIGNERS', 'value': json.dumps([{'principal': 'fixture@example.invalid', 'key': PUBLIC_KEY}])}
TAG = (f'object {SHA}\ntype commit\ntag v1.0.0\ntagger Fixture <fixture@example.invalid> 1700000000 +0000\n\n'
       'Fixture only\n-----BEGIN SSH SIGNATURE-----\nZml4dHVyZQ==\n-----END SSH SIGNATURE-----\n').encode()
TAG_OID = policy.git_oid('tag', TAG)
SOURCE = {'sha': SHA, 'tree': TREE, 'tag': 'v1.0.0', 'tag_oid': TAG_OID, 'version': '1.0.0'}
CONTRACT_ENV = {'GITHUB_SERVER_URL': 'https://github.com', 'GITHUB_API_URL': 'https://api.github.com',
                'GITHUB_REPOSITORY_OWNER_ID': '77', 'CI_RUN_ID': '100', 'CI_RUN_ATTEMPT': '1',
                'CI_SHA': SHA, 'RELEASE_TAG': 'v1.0.0'}


def native_fixture():
    repository = {'id': policy.REPOSITORY_ID, 'name': policy.REPOSITORY, 'owner_id': 77}
    environment = {'id': 30, 'name': 'production', 'can_admins_bypass': False,
                   'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True},
                   'protection_rules': [{'type': 'required_reviewers', 'id': 31, 'prevent_self_review': True,
                       'reviewers': [{'type': 'User', 'reviewer': {'id': 40, 'login': 'fixture-reviewer', 'type': 'User'}}]}]}
    branches = {'total_count': 1, 'branch_policies': [{'id': 32, 'name': 'main', 'type': 'branch'}]}
    protection = {'enforce_admins': {'enabled': True}, 'allow_force_pushes': {'enabled': False},
                  'allow_deletions': {'enabled': False}, 'required_pull_request_reviews': {
                      'required_approving_review_count': 1, 'dismiss_stale_reviews': True, 'require_last_push_approval': True,
                      'bypass_pull_request_allowances': {'users': [], 'teams': [], 'apps': []}},
                  'required_status_checks': {'strict': True, 'contexts': list(policy.CHECKS),
                      'checks': [{'context': name, 'app_id': 15368} for name in policy.CHECKS]}}
    return [repository, environment, branches, protection]


def tags_fixture():
    common = {'target': 'tag', 'source_type': 'Repository', 'source': policy.REPOSITORY, 'enforcement': 'active',
              'conditions': {'ref_name': {'include': ['refs/tags/v*.*.*'], 'exclude': []}}}
    return [{**copy.deepcopy(common), 'id': 51, 'rules': [{'type': 'creation'}],
             'bypass_actors': [{'actor_type': 'Team', 'actor_id': 80, 'bypass_mode': 'always'}]},
            {**copy.deepcopy(common), 'id': 52, 'rules': [{'type': 'update'}, {'type': 'deletion'}], 'bypass_actors': []}]


def legacy_fixture():
    # Synthetic response for the pinned real identity, not installed retirement evidence.
    return {'id': 315951352, 'path': '.github/workflows/release.yml', 'state': 'disabled_manually'}


def change(value, path, replacement):
    for part in path[:-1]:
        value = value[part]
    value[path[-1]] = replacement


class NativePolicyTests(unittest.TestCase):
    def test_legacy_snapshot_requires_exact_id_path_and_manual_disable(self):
        expected = legacy_fixture()
        self.assertEqual(policy.LEGACY_RELEASE_ID, 315951352)
        self.assertEqual(policy.LEGACY_RELEASE, '.github/workflows/release.yml')
        self.assertEqual(policy.legacy_release_disabled(expected), expected)
        cases = [('id', 315951353), ('id', '315951352'), ('id', True), ('id', 315951352.0),
                 ('path', policy.RELEASE), ('path', 'release.yml'), ('path', None),
                 *[('state', state) for state in ('active', 'disabled_inactivity', 'disabled_fork', 'deleted',
                                                 'disabled', 'DISABLED_MANUALLY', 'disabled_manually ', None)]]
        for field, value in cases:
            with self.subTest(field=field, value=value), self.assertRaises(policy.Refused):
                wrong = legacy_fixture(); wrong[field] = value; policy.legacy_release_disabled(wrong)
        for wrong in (None, {}, [], [expected, expected], {k: v for k, v in expected.items() if k != 'id'}):
            with self.subTest(record=wrong), self.assertRaises(policy.Refused): policy.legacy_release_disabled(wrong)

    def test_complete_native_policy_and_optional_marker(self):
        fixture = native_fixture()
        expected = policy.native_policy(*fixture)
        self.assertEqual(expected['strict_checks'], [[x, 15368] for x in policy.CHECKS])
        fixture[1]['protection_rules'].append({'type': 'branch_policy', 'id': 33, 'node_id': 'fixture-marker'})
        self.assertEqual(policy.native_policy(*fixture), expected)

    def test_missing_relaxed_ambiguous_or_extra_native_policy_refused(self):
        cases = [([1, 'can_admins_bypass'], True), ([1, 'deployment_branch_policy'], None),
                 ([1, 'protection_rules'], []), ([1, 'protection_rules', 0, 'prevent_self_review'], False),
                 ([1, 'protection_rules', 0, 'reviewers'], []),
                 ([1, 'protection_rules', 0, 'reviewers', 0, 'type'], 'Team'),
                 ([1, 'protection_rules', 0, 'reviewers', 0, 'reviewer', 'id'], True),
                 ([2, 'branch_policies', 0, 'type'], 'tag'), ([2, 'branch_policies', 0, 'name'], '*'),
                 ([2, 'total_count'], 2), ([3, 'enforce_admins', 'enabled'], False),
                 ([3, 'allow_force_pushes', 'enabled'], True), ([3, 'allow_deletions', 'enabled'], True),
                 ([3, 'required_pull_request_reviews', 'required_approving_review_count'], 0),
                 ([3, 'required_pull_request_reviews', 'dismiss_stale_reviews'], False),
                 ([3, 'required_pull_request_reviews', 'require_last_push_approval'], False),
                 ([3, 'required_pull_request_reviews', 'bypass_pull_request_allowances', 'apps'], [{'id': 80}]),
                 ([3, 'required_status_checks', 'strict'], False),
                 ([3, 'required_status_checks', 'checks', 0, 'app_id'], -1),
                 ([3, 'required_status_checks', 'contexts'], ['verify'])]
        for path, replacement in cases:
            with self.subTest(path=path), self.assertRaises(policy.Refused):
                fixture = native_fixture(); change(fixture, path, replacement)
                policy.native_policy(*fixture)
        for extra in ({'type': 'wait_timer'}, native_fixture()[1]['protection_rules'][0],
                      {'type': 'branch_policy', 'bypass': True}):
            with self.subTest(extra=extra), self.assertRaises(policy.Refused):
                fixture = native_fixture(); fixture[1]['protection_rules'].append(extra)
                policy.native_policy(*fixture)
        for key in ('checks', 'contexts'):
            with self.subTest(key=key), self.assertRaises(policy.Refused):
                fixture = native_fixture(); fixture[3]['required_status_checks'][key].pop(1)
                policy.native_policy(*fixture)  # observability-rules is not optional.

    def test_changed_native_id_or_review_strength_changes_fingerprint(self):
        baseline = policy.native_policy(*native_fixture())
        for path in ([1, 'id'], [1, 'protection_rules', 0, 'id'], [2, 'branch_policies', 0, 'id'],
                     [1, 'protection_rules', 0, 'reviewers', 0, 'reviewer', 'id'],
                     [3, 'required_pull_request_reviews', 'required_approving_review_count']):
            fixture = native_fixture(); change(fixture, path, 2)
            self.assertNotEqual(policy.native_policy(*fixture), baseline)

    def test_separate_creation_exceptions_never_bypass_immutability(self):
        expected = policy.tag_policy(tags_fixture())
        self.assertEqual(json.loads(image.canonical(expected)), expected)
        for path, value in [([0, 'bypass_actors'], []), ([0, 'bypass_actors', 0, 'actor_type'], 'RepositoryRole'),
                            ([1, 'bypass_actors'], tags_fixture()[0]['bypass_actors']),
                            ([1, 'enforcement'], 'evaluate'), ([1, 'source_type'], 'Organization'),
                            ([1, 'conditions', 'ref_name', 'exclude'], ['refs/tags/v1.*']),
                            ([1, 'rules'], [{'type': 'deletion'}]), ([1, 'id'], 51)]:
            with self.subTest(path=path), self.assertRaises(policy.Refused):
                fixture = tags_fixture(); change(fixture, path, value); policy.tag_policy(fixture)
        with self.assertRaises(policy.Refused):
            policy.tag_policy(tags_fixture() + [tags_fixture()[1]])


class Response:
    def __init__(self, status=200, raw=b'{}', headers=None):
        self.status, self.raw = status, raw
        self.headers = {'Content-Type': 'application/json', **(headers or {})}

    def getheader(self, name, default=None):
        return self.headers.get(name, default)

    def getheaders(self):
        return self.headers.items()

    def read(self, limit):
        return self.raw[:limit]


def source_records(blobs, sha=SHA, tree=TREE):
    """Self-consistent Git API bytes; no Git process or source execution."""
    listing = {'sha': tree, 'truncated': False, 'tree': []}
    records = {'/git/commits/' + sha: {'sha': sha, 'tree': {'sha': tree}},
               '/git/trees/' + tree + '?recursive=1': listing}
    for name, raw in blobs.items():
        oid = policy.git_oid('blob', raw)
        listing['tree'].append({'path': name, 'sha': oid, 'mode': '100644', 'type': 'blob'})
        records['/git/blobs/' + oid] = {'sha': oid, 'size': len(raw), 'encoding': 'base64',
                                      'content': base64.b64encode(raw).decode()}
    return records


class ApiTests(unittest.TestCase):
    def test_policy_token_is_confined_and_signers_are_read_again(self):
        with patch.object(policy, 'http_get', return_value=(200, {'Content-Type': 'application/json'}, b'{}')) as get:
            api = policy.Api('ordinary-fixture', 'policy-fixture')
            for path in ('', policy.ENVIRONMENT, policy.BRANCHES, '/git/ref/heads/main',
                         '/actions/workflows/publish-release.yml', policy.LEGACY_RELEASE_ENDPOINT, policy.LEGACY_RELEASE_ENDPOINT):
                api.get(path); self.assertEqual(get.call_args.args[2], 'Bearer ordinary-fixture')
            for path in (policy.PROTECTION, policy.RULESETS, '/rulesets/51', policy.SIGNERS, policy.SIGNERS):
                api.get(path); self.assertEqual(get.call_args.args[2], 'Bearer policy-fixture')
            self.assertEqual(sum(x.args[1].endswith(policy.SIGNERS) for x in get.call_args_list), 2)
            self.assertEqual(sum(x.args[1].endswith('/actions/workflows/315951352') for x in get.call_args_list), 2)
            calls = get.call_count
            for path in ('https://elsewhere.invalid/', '//elsewhere.invalid/', '/contents/.env', '/actions/variables/OTHER',
                         '/actions/workflows/release.yml', '/actions/workflows/315951353',
                         '/actions/workflows/315951352/enable', '/actions/workflows/315951352/disable',
                         '/actions/workflows/315951352?page=1', '/actions/workflows?per_page=100&page=1'):
                with self.assertRaises(policy.Refused): api.get(path)
            with self.assertRaises(policy.Refused): policy.Api('ordinary-fixture').get(policy.SIGNERS)
            self.assertEqual(get.call_count, calls)

    def test_legacy_get_missing_or_ambiguous_response_has_no_fallback(self):
        duplicate = (b'{"id":315951352,"id":315951352,"path":".github/workflows/release.yml",'
                     b'"state":"disabled_manually"}')
        for response in [Response(status=n) for n in (302, 403, 404, 500)] + [Response(raw=duplicate), Response(raw=b'[]')]:
            with self.subTest(status=response.status, raw=response.raw), \
                 patch.object(policy.http.client, 'HTTPSConnection') as connection, self.assertRaises(policy.Refused):
                connection.return_value.getresponse.return_value = response
                policy.legacy_release_disabled(policy.Api('ordinary-fixture', 'policy-fixture').get(policy.LEGACY_RELEASE_ENDPOINT))
            connection.assert_called_once()
            request = connection.return_value.request
            request.assert_called_once()
            self.assertEqual(request.call_args.args, ('GET', '/repos/' + policy.REPOSITORY + '/actions/workflows/315951352'))
            self.assertEqual(request.call_args.kwargs['headers']['Authorization'], 'Bearer ordinary-fixture')
            connection.return_value.close.assert_called_once()

    def test_http_fail_closed_no_redirect_or_fallback_and_finite_bytes(self):
        for response in [Response(status=n) for n in (302, 403, 404, 429, 500)] + [
                Response(headers={'Link': '<next>; rel="next"'}),
                Response(headers={'Content-Length': '9999999999'}), Response(headers={'Content-Length': '3'}),
                Response(headers={'Content-Encoding': 'gzip'}), Response(raw=b'{"id":1,"id":2}'),
                Response(raw=b'{"id":NaN}'), Response(headers={'Content-Type': 'text/html'})]:
            with self.subTest(status=response.status, headers=response.headers), \
                 patch.object(policy.http.client, 'HTTPSConnection') as connection, self.assertRaises(policy.Refused):
                connection.return_value.getresponse.return_value = response
                policy.Api('ordinary-fixture', 'policy-fixture').get(policy.PROTECTION)
            connection.assert_called_once()
            connection.return_value.request.assert_called_once()
            self.assertEqual(connection.return_value.request.call_args.args[0], 'GET')
            self.assertEqual(connection.call_args.args[0], 'api.github.com')
            connection.return_value.close.assert_called_once()
        with patch.object(policy.http.client, 'HTTPSConnection') as connection, self.assertRaises(policy.Refused):
            connection.return_value.request.side_effect = TimeoutError('fixture transport detail must not leak')
            policy.Api('ordinary-fixture').get('')

    def test_complete_tree_not_count_only_or_selected_file_comparison(self):
        blobs = {name: ('fixture ' + name).encode() for name in (*policy.CONTROLS, 'build.gradle.kts', 'CHANGELOG.md')}
        blobs.update({name: (policy.ROOT / name).read_bytes() for name in image.STATE_MIGRATIONS})
        records = source_records(blobs)
        api = policy.Api('ordinary-fixture')
        with patch.object(api, 'get', side_effect=lambda x: copy.deepcopy(records[x])):
            self.assertEqual(api.source(SHA), (TREE, blobs))
        for mutation in ('truncated', 'duplicate', 'missing', 'bytes'):
            wrong = copy.deepcopy(records)
            listing = wrong['/git/trees/' + TREE + '?recursive=1']
            if mutation == 'truncated': listing['truncated'] = True
            if mutation == 'duplicate': listing['tree'].append(listing['tree'][0])
            if mutation == 'missing': listing['tree'].pop()
            if mutation == 'bytes': wrong['/git/blobs/' + listing['tree'][0]['sha']]['content'] = 'eA=='
            api = policy.Api('ordinary-fixture')
            with patch.object(api, 'get', side_effect=lambda x: copy.deepcopy(wrong[x])), self.assertRaises(policy.Refused):
                api.source(SHA)

    def test_source_reader_applies_real_migration_inventory_and_byte_guards_before_cache(self):
        blobs = {name: (policy.ROOT / name).read_bytes()
                 for name in (*policy.CONTROLS, 'build.gradle.kts', 'CHANGELOG.md')}
        migration = next(iter(image.STATE_MIGRATIONS))
        for mutation in ('extra', 'missing', 'nested', 'revised'):
            wrong = copy.deepcopy(blobs)
            if mutation == 'extra': wrong[image.MIGRATION_DIRECTORY + '/V14__fixture.sql'] = b'-- future fixture\n'
            if mutation == 'missing': del wrong[migration]
            if mutation == 'nested': wrong[image.MIGRATION_DIRECTORY + '/nested/V14__fixture.sql'] = b'-- nested fixture\n'
            if mutation == 'revised': wrong[migration] += b'\n-- changed fixture bytes\n'
            # Revised content receives matching new Git blob identity/base64/size. Only the
            # real pinned SHA-256 migration guard can reject that self-consistent response.
            records = source_records(wrong)
            api = policy.Api('ordinary-fixture')
            diagnostic = 'migration bytes' if mutation == 'revised' else 'migration inventory'
            with self.subTest(mutation=mutation), patch.object(api, 'get', side_effect=lambda p: copy.deepcopy(records[p])):
                with self.assertRaisesRegex(policy.Refused, diagnostic): api.source(SHA)
                self.assertNotIn(SHA, api.sources)
                records = source_records(blobs)
                self.assertEqual(api.source(SHA), (TREE, blobs))

    def test_metadata_request_and_elapsed_budgets_do_not_retry(self):
        with patch.object(policy, 'http_get') as get, patch.object(policy.time, 'monotonic', return_value=0):
            self.assertEqual(policy.MAX_API_REQUESTS, 85)  # Fixed ceiling, not automatic contract growth.
            api = policy.Api('ordinary-fixture'); api.count = policy.MAX_API_REQUESTS
            with self.assertRaises(policy.Refused): api.get('')
            api = policy.Api('ordinary-fixture'); self.assertEqual(api.end, 180); api.end = 0
            with self.assertRaises(policy.Refused): api.get('')
            get.assert_not_called()


class ContractTests(unittest.TestCase):
    def test_literal_version_and_top_released_heading(self):
        gradle, changelog = b'version = "1.0.0"\n', b'## [Unreleased]\n\n## [1.0.0] - 2026-07-19\n'
        self.assertEqual(policy.tag_contract('v1.0.0', gradle, changelog), '1.0.0')
        for tag in ('1.0.0', 'v01.0.0', 'v1.00.0', 'v1.0.00', 'v1.0.0-rc1', 'v1.0.0+meta',
                    'v1.0.0\n', ' v1.0.0', 'refs/tags/v1.0.0', 'v1.0.0/evil', 'v1.0.0;id', 'v2.0.0'):
            with self.subTest(tag=tag), self.assertRaises(policy.Refused): policy.tag_contract(tag, gradle, changelog)
        for wrong in (b'', gradle + gradle, b'version = providers.gradleProperty("release")',
                      b'version = "1.0." + "0"', gradle + b'project.version = "1.0.0"',
                      gradle + b'setVersion("1.0.0")', b'val version = "1.0.0"'):
            with self.subTest(gradle=wrong), self.assertRaises(policy.Refused): policy.tag_contract('v1.0.0', wrong, changelog)
        for wrong in (b'', b'## [Unreleased]', changelog + b'## [1.0.0] - 2026-07-19\n',
                      b'## [2.0.0] - 2026-07-19\n' + changelog, b'## 1.0.0\n',
                      changelog + b'## [2.0.0] - 2026-07-19\n'):
            with self.subTest(changelog=wrong), self.assertRaises(policy.Refused): policy.tag_contract('v1.0.0', gradle, wrong)

    def test_owner_provisioned_trust_has_no_default_or_unsigned_fallback(self):
        self.assertEqual(policy.signer_trust(TRUST)[0]['key'], PUBLIC_KEY)
        for value in ('', '[]', '{}', '[{"principal":"*","key":"' + PUBLIC_KEY + '"}]',
                      json.dumps([{'principal': 'fixture', 'key': 'ssh-rsa AAAA'}]),
                      json.dumps([{'principal': 'fixture', 'key': PUBLIC_KEY}] * 2)):
            with self.subTest(value=value), self.assertRaises((policy.Refused, ValueError)):
                policy.signer_trust({'name': 'BACKEND_RELEASE_SIGNERS', 'value': value})
        with tempfile.TemporaryDirectory() as name:
            for wrong in (TAG.replace(b'-----BEGIN SSH SIGNATURE-----', b'-----BEGIN PGP SIGNATURE-----'),
                          TAG.split(b'-----BEGIN')[0], TAG + b'extra', TAG.replace(b'type commit', b'type tag')):
                with self.subTest(raw=wrong), patch.object(image, 'command') as command, self.assertRaises(policy.Refused):
                    policy.signed_tag(wrong, policy.git_oid('tag', wrong), 'v1.0.0', SHA, policy.signer_trust(TRUST), Path(name))
                command.assert_not_called()

    def test_signature_commands_have_fixed_tool_namespace_allowlist_and_payload(self):
        calls = []
        def command(argv, **kwargs):
            calls.append(argv)
            self.assertEqual(argv[0], '/usr/bin/ssh-keygen')
            allowed = Path(argv[argv.index('-f') + 1]).read_text()
            self.assertIn('fixture@example.invalid namespaces="git" ' + PUBLIC_KEY, allowed)
            if argv[2] == 'find-principals': return b'fixture@example.invalid\n'
            self.assertEqual(argv[argv.index('-n') + 1], 'git')
            self.assertEqual(kwargs['stdin'].read(), TAG.split(b'-----BEGIN SSH SIGNATURE-----')[0])
            return b''
        with tempfile.TemporaryDirectory() as name, patch.object(image, 'command', side_effect=command):
            policy.signed_tag(TAG, TAG_OID, 'v1.0.0', SHA, policy.signer_trust(TRUST), Path(name))
            self.assertEqual(list(Path(name).iterdir()), [])
        self.assertEqual(len(calls), 2)
        for result in (b'unknown@example.invalid\n', b'', b'fixture@example.invalid\nsecond\n'):
            with tempfile.TemporaryDirectory() as name, patch.object(image, 'command', return_value=result), self.assertRaises(policy.Refused):
                policy.signed_tag(TAG, TAG_OID, 'v1.0.0', SHA, policy.signer_trust(TRUST), Path(name))
        with tempfile.TemporaryDirectory() as name, patch.object(image, 'command', side_effect=[
                b'fixture@example.invalid\n', policy.Refused('invalid signature')]), self.assertRaises(policy.Refused):
            policy.signed_tag(TAG, TAG_OID, 'v1.0.0', SHA, policy.signer_trust(TRUST), Path(name))


class FakeApi:
    def __init__(self, root, sha=SHA, main=MAIN):
        self.token = 'fixture-only-token'
        self.sha, self.main = sha, main
        self.tag = TAG.replace(SHA.encode(), sha.encode())
        self.tag_oid = policy.git_oid('tag', self.tag)
        self.ctx = {'repository': policy.REPOSITORY, 'repository_id': policy.REPOSITORY_ID, 'sha': main,
                    'run_id': 200, 'attempt': 1, 'event': 'workflow_dispatch', 'ref': 'refs/heads/main',
                    'workflow_ref': policy.REPOSITORY + '/' + policy.RELEASE + '@refs/heads/main', 'workflow_sha': main}
        repo = {'id': policy.REPOSITORY_ID, 'full_name': policy.REPOSITORY, 'default_branch': 'main',
                'owner': {'id': 77, 'login': 'kira-manga'}, 'fork': False, 'archived': False, 'disabled': False}
        run = {'id': 100, 'run_attempt': 1, 'workflow_id': 10, 'path': image.CI, 'event': 'push',
               'head_branch': 'main', 'head_sha': sha, 'status': 'completed', 'conclusion': 'success',
               'repository': repo, 'head_repository': repo}
        own = {**run, 'id': 200, 'workflow_id': 20, 'path': policy.RELEASE, 'event': 'workflow_dispatch',
               'head_sha': main, 'status': 'in_progress', 'conclusion': None}
        jobs = [{'id': index + 1, 'name': name, 'run_id': 100, 'run_attempt': 1, 'head_sha': sha,
                 'status': 'completed', 'conclusion': 'success', 'completed_at': COMPLETED} for index, name in enumerate(policy.CHECKS)]
        self.records = {'': repo, '/actions/workflows/ci.yml': {'id': 10, 'path': image.CI, 'state': 'active'},
                        '/actions/workflows/publish-release.yml': {'id': 20, 'path': policy.RELEASE, 'state': 'active'},
                        policy.LEGACY_RELEASE_ENDPOINT: legacy_fixture(),
                        '/actions/runs/100': run, '/actions/runs/100/attempts/1': copy.deepcopy(run),
                        '/actions/runs/200': own, '/actions/runs/200/attempts/1': copy.deepcopy(own),
                        '/actions/runs/100/attempts/1/jobs?per_page=100': {'total_count': 4, 'jobs': jobs},
                        '/git/ref/heads/main': {'ref': 'refs/heads/main', 'object': {'type': 'commit', 'sha': main}},
                        '/git/ref/tags/v1.0.0': {'ref': 'refs/tags/v1.0.0', 'object': {'type': 'tag', 'sha': self.tag_oid}},
                        '/git/tags/' + self.tag_oid: {'sha': self.tag_oid, 'tag': 'v1.0.0', 'object': {'type': 'commit', 'sha': sha}},
                        policy.SIGNERS: copy.deepcopy(TRUST), policy.RULESETS: tags_fixture(),
                        '/releases?per_page=100&page=1': []}
        for record in tags_fixture(): self.records['/rulesets/' + str(record['id'])] = record
        _, env, branches, protection = native_fixture()
        self.records.update({policy.ENVIRONMENT: env, policy.BRANCHES: branches, policy.PROTECTION: protection})
        blobs = {p: (policy.ROOT / p).read_bytes() for p in policy.CONTROLS}
        blobs.update({'build.gradle.kts': b'version = "1.0.0"\n', 'CHANGELOG.md': b'## [1.0.0] - 2026-07-19\n'})
        self.sources = {sha: (TREE, blobs), main: (TREE, copy.deepcopy(blobs))}
        self.ancestor, self.root, self.calls = True, root, []

    def get(self, path):
        self.calls.append(path)
        return copy.deepcopy(self.records[path])

    def source(self, sha):
        return copy.deepcopy(self.sources[sha])

    def git(self, *args, **kwargs):
        if args == ('rev-parse', 'HEAD'): return self.main.encode()
        if args == ('rev-parse', 'HEAD^{tree}'): return TREE.encode()
        if args == ('rev-parse', self.sha + '^{tree}'): return TREE.encode()
        if args == ('rev-parse', '--is-shallow-repository'): return b'false'
        if args == ('rev-parse', '--path-format=absolute', '--git-path', 'info/grafts'): return str(self.root / 'absent-grafts').encode()
        if args == ('merge-base', '--is-ancestor', self.sha, self.main): return 0 if self.ancestor else 1
        if args == ('cat-file', 'tag', self.tag_oid): return self.tag
        raise AssertionError('unexpected Git invocation: ' + repr(args))


class SourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.api = FakeApi(self.root)
        environment = patch.dict(os.environ, CONTRACT_ENV); environment.start(); self.addCleanup(environment.stop)
        clock = patch.object(policy.time, 'time', return_value=NOW); clock.start(); self.addCleanup(clock.stop)
        git = patch.object(policy, 'git', side_effect=self.api.git); git.start(); self.addCleanup(git.stop)
        signature = patch.object(policy, 'signed_tag'); signature.start(); self.addCleanup(signature.stop)

    def select(self):
        return policy.selection(self.api, self.api.ctx, self.root)

    def test_full_freeze_real_metadata_reader_fits_exact_joined_budget(self):
        # Real Api.get/source, selection and both artifact_metadata passes; only transport,
        # Git/signature and artifact payload work are fixtures. No native/retirement proof.
        self.assertNotEqual(SHA, MAIN)
        self.assertEqual(len(image.CONTRACT), 23)
        selected = self.select()
        for sha, (tree, blobs) in self.api.sources.items():
            self.api.records.update(source_records(blobs, sha, tree))
        artifact = {'id': 300, 'name': 'backend-release-200-1', 'size_in_bytes': 123,
                    'digest': 'sha256:' + 'f' * 64, 'expired': False, 'created_at': COMPLETED,
                    'expires_at': dt.datetime.fromtimestamp(NOW + 3600, dt.timezone.utc).isoformat(),
                    'workflow_run': {'id': 200, 'repository_id': policy.REPOSITORY_ID,
                        'head_repository_id': policy.REPOSITORY_ID, 'head_sha': MAIN, 'head_branch': 'main'}}
        artifact_path = '/actions/runs/200/artifacts?per_page=100'
        jobs_path = '/actions/runs/200/attempts/1/jobs?per_page=100'
        self.api.records[artifact_path] = {'total_count': 1, 'artifacts': [artifact]}
        self.api.records['/actions/artifacts/300'] = copy.deepcopy(artifact)
        self.api.records[jobs_path] = {'total_count': 2, 'jobs': [
            {'id': i + 10, 'name': name, 'run_id': 200, 'run_attempt': 1, 'head_sha': MAIN,
             'status': 'completed', 'conclusion': 'success', 'completed_at': COMPLETED}
            for i, name in enumerate(('preflight', 'build'))]}
        (self.root / 'manifest.json').write_bytes(b'{}')
        prefix = '/repos/' + policy.REPOSITORY
        def transport(host, path, authorization):
            self.assertEqual(host, 'api.github.com')
            self.assertTrue(path.startswith(prefix))
            return 200, {'Content-Type': 'application/json'}, image.canonical(self.api.records[path[len(prefix):]])
        with patch.object(policy.time, 'monotonic', return_value=0):
            api = policy.Api('ordinary-fixture', 'policy-fixture')
            with patch.dict(os.environ, {'EXPECTED_SELECTION': image.canonical(selected).decode(),
                    'BUILT_ARTIFACT_ID': '300', 'BUILT_ZIP_SHA256': 'f' * 64,
                    'GITHUB_STEP_SUMMARY': str(self.root / 'summary')}), \
                 patch.object(image.sys, 'argv', ['release_policy.py', 'freeze']), \
                 patch.object(image, 'owned_directory', return_value=self.root), \
                 patch.object(image, 'context', return_value=self.api.ctx), patch.object(policy, 'Api', return_value=api), \
                 patch.object(policy.os, 'umask'), patch.object(image, 'output') as output, \
                 patch.object(policy, 'http_get', side_effect=transport) as get, \
                 patch.object(api, 'download') as download, patch.object(policy, 'unpack_packet'), \
                 patch.object(policy, 'validate_packet', return_value={'image': {}, 'files': {}}):
                policy.main()
                self.assertEqual(api.count, 85)
                self.assertEqual(get.call_count, 85)
                calls = [x.args[1][len(prefix):] for x in get.call_args_list]
                self.assertEqual(calls[-6:], [artifact_path, '/actions/artifacts/300', jobs_path] * 2)
                self.assertEqual(calls.count(policy.LEGACY_RELEASE_ENDPOINT), 1)
                self.assertEqual(calls.count('/git/commits/' + SHA), 1)
                self.assertEqual(calls.count('/git/commits/' + MAIN), 1)
                download.assert_called_once_with(artifact, self.root / 'artifact.zip')
                self.assertEqual(output.call_args.args[0], 'envelope')
                self.assertEqual(json.loads(output.call_args.args[1])['artifact'], artifact)
                with self.assertRaisesRegex(policy.Refused, 'request budget'): api.get('')
                self.assertEqual(api.count, 86)
                self.assertEqual(get.call_count, 85)  # Refuse before HTTP, never retry/fallback.

    def test_actual_main_ancestor_and_current_ci_are_bound(self):
        selected = self.select()
        self.assertEqual(selected['source'], SOURCE)
        self.assertEqual(selected['consumer']['sha'], MAIN)
        self.assertEqual(selected['consumer']['policy']['legacy_release'], legacy_fixture())
        self.assertEqual([x['name'] for x in selected['ci']['jobs']], list(policy.CHECKS))
        self.assertEqual(selected, json.loads(image.canonical(selected)))
        self.select()
        self.assertEqual(self.api.calls.count(policy.SIGNERS), 2)
        self.assertEqual(self.api.calls.count(policy.LEGACY_RELEASE_ENDPOINT), 2)

    def test_both_trusted_contexts_reread_legacy_state_and_require_distinct_identity(self):
        for release in (True, False):
            api = FakeApi(self.root)
            path, event = (policy.RELEASE, 'workflow_dispatch') if release else (image.DEPLOY, 'workflow_run')
            endpoint = '/actions/workflows/' + path.split('/')[-1]
            api.ctx.update(event=event, workflow_ref=policy.REPOSITORY + '/' + path + '@refs/heads/main')
            api.records[endpoint] = {'id': 20, 'path': path, 'state': 'active'}
            for suffix in ('/actions/runs/200', '/actions/runs/200/attempts/1'):
                api.records[suffix].update(path=path, event=event)
            with self.subTest(release=release), patch.object(policy, 'git', side_effect=api.git):
                selected = policy.trusted_context(api, api.ctx, release)
                self.assertEqual(selected['policy']['legacy_release'], legacy_fixture())
                api.records[policy.LEGACY_RELEASE_ENDPOINT]['state'] = 'active'
                with self.assertRaisesRegex(policy.Refused, 'legacy release'):
                    policy.trusted_context(api, api.ctx, release)
                self.assertEqual(api.calls.count(policy.LEGACY_RELEASE_ENDPOINT), 2)
                api.records[policy.LEGACY_RELEASE_ENDPOINT] = legacy_fixture()
                api.records[endpoint]['id'] = policy.LEGACY_RELEASE_ID
                for suffix in ('/actions/runs/200', '/actions/runs/200/attempts/1'):
                    api.records[suffix]['workflow_id'] = policy.LEGACY_RELEASE_ID
                with self.assertRaisesRegex(policy.Refused, 'distinct identity'):
                    policy.trusted_context(api, api.ctx, release)

    def test_post_approval_recheck_refuses_reenabled_legacy_before_stamp(self):
        selected = self.select()
        self.api.records[policy.LEGACY_RELEASE_ENDPOINT]['state'] = 'active'
        with patch.dict(os.environ, {'EXPECTED_SELECTION': image.canonical(selected).decode()}), \
             patch.object(image.sys, 'argv', ['release_policy.py', 'recheck']), \
             patch.object(image, 'owned_directory', return_value=self.root), \
             patch.object(image, 'context', return_value=self.api.ctx), patch.object(policy, 'Api', return_value=self.api), \
             patch.object(policy.os, 'umask'), patch.object(policy, 'artifact_metadata') as artifact, \
             patch.object(policy, 'validate_packet') as packet, self.assertRaisesRegex(policy.Refused, 'legacy release'):
            policy.main()
        self.assertEqual(self.api.calls.count(policy.LEGACY_RELEASE_ENDPOINT), 2)
        artifact.assert_not_called(); packet.assert_not_called()
        self.assertFalse((self.root / 'authorization-check.json').exists())

    def test_current_main_and_reviewed_rebase_use_their_actual_sha_not_old_ci(self):
        for sha in (MAIN, 'd' * 40):
            api = FakeApi(self.root, sha=sha, main=sha)
            with self.subTest(sha=sha), patch.dict(os.environ, {'CI_SHA': sha}), \
                 patch.object(policy, 'git', side_effect=api.git):
                selected = policy.selection(api, api.ctx, self.root)
                self.assertEqual(selected['source']['sha'], sha)
                self.assertEqual(selected['source']['tag_oid'], api.tag_oid)
                self.assertEqual(selected['consumer']['sha'], sha)
                api.records['/actions/runs/100']['head_sha'] = SHA
                with self.assertRaises(policy.Refused): policy.selection(api, api.ctx, self.root)

    def test_same_tree_off_main_sibling_and_unrelated_history_are_refused(self):
        self.assertEqual(self.api.sources[SHA][0], self.api.sources[MAIN][0])
        self.api.ancestor = False
        with self.assertRaisesRegex(policy.Refused, 'ancestry'): self.select()
        self.api.sources[SHA] = ('d' * 40, self.api.sources[SHA][1])
        with self.assertRaises(policy.Refused): self.select()

    def test_forks_failed_skipped_gate_wrong_attempt_and_current_main_drift(self):
        cases = [('/actions/runs/100', ['head_branch'], 'other'), ('/actions/runs/100', ['event'], 'pull_request'),
                 ('/actions/runs/100', ['repository', 'id'], 99), ('/actions/runs/100', ['run_attempt'], 2),
                 ('/actions/runs/100/attempts/1', ['conclusion'], 'failure'),
                 ('/actions/workflows/ci.yml', ['path'], '.github/workflows/other.yml'),
                 ('/actions/runs/100/attempts/1/jobs?per_page=100', ['jobs', 1, 'conclusion'], 'skipped'),
                 ('/actions/runs/100/attempts/1/jobs?per_page=100', ['total_count'], 5),
                 ('/git/ref/heads/main', ['object', 'sha'], 'e' * 40),
                 ('/git/ref/tags/v1.0.0', ['object', 'type'], 'commit'),
                 ('/git/tags/' + TAG_OID, ['object', 'sha'], MAIN)]
        baseline = copy.deepcopy(self.api.records)
        for endpoint, path, value in cases:
            with self.subTest(endpoint=endpoint, path=path), self.assertRaises(policy.Refused):
                self.api.records = copy.deepcopy(baseline); change(self.api.records[endpoint], path, value); self.select()

    def test_source_controls_policy_signer_drift_and_consumer_rerun(self):
        baseline = self.select()
        self.api.records[policy.ENVIRONMENT]['id'] += 1
        self.assertNotEqual(self.select(), baseline)
        self.api.records[policy.SIGNERS]['value'] = json.dumps([{'principal': 'rotated@example.invalid', 'key': PUBLIC_KEY}])
        self.assertNotEqual(self.select()['signers_sha256'], baseline['signers_sha256'])
        self.api.sources[SHA][1]['scripts/ci/release_policy.py'] += b'\n# drift'
        with self.assertRaises(policy.Refused): self.select()
        self.api.ctx['attempt'] = 2
        with self.assertRaises(policy.Refused): self.select()


def packet_fixture(directory):
    selected = {'source': SOURCE}
    identity, _, files = tiny_image()
    (directory / 'image.tar.gz').write_bytes(gzip.compress(docker_bytes(files), mtime=0))
    with zipfile.ZipFile(directory / 'kira-backend-1.0.0.jar', 'w') as jar:
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\n'
                     f'Kira-Source-SHA: {SHA}\r\nStart-Class: me.manga.kira.backend.KiraBackendApplicationKt\r\n\r\n')
    (directory / 'bom.json').write_bytes(b'{"bomFormat":"CycloneDX"}')
    (directory / 'bom.xml').write_bytes(b'<bom/>')
    metadata, archive = policy.release_image(directory / 'image.tar.gz', SOURCE, identity)
    manifest = {'schema': 1, 'purpose': 'backend-release-candidate', 'selection': selected, 'image': metadata,
                'archive': archive, 'files': {name: image.file_identity(directory / name, limit)
                    for name, limit in policy.packet_files(SOURCE).items() if name != 'manifest.json'},
                'smoke': {'policy': image.POLICY, 'image_id': identity, 'result': 'pass'}}
    (directory / 'manifest.json').write_bytes(image.canonical(manifest))
    return selected, manifest


class PacketTests(unittest.TestCase):
    def test_release_archive_and_forged_packet_require_exact_state_profile(self):
        for profile in (None, 'kira-backend-state-v2', image.STATE_PROFILE + ' '):
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as name:
                root = Path(name); selected, manifest = packet_fixture(root)
                self.assertEqual(policy.validate_packet(root, selected), manifest)
                identity, _, files = tiny_image(state_profile=profile)
                plain = docker_bytes(files); compressed = gzip.compress(plain, mtime=0)
                (root / 'image.tar.gz').write_bytes(compressed)
                with self.assertRaisesRegex(policy.Refused, 'backend state contract unproven'):
                    policy.release_image(root / 'image.tar.gz', SOURCE, identity)
                # Consistent file hashes/image ID and a claimed new-policy PASS cannot
                # substitute for the label in the actual immutable archive configuration.
                manifest['image']['id'] = identity
                manifest['files']['image.tar.gz'] = {'bytes': len(compressed), 'sha256': image.digest(compressed)}
                manifest['archive'] = {**manifest['files']['image.tar.gz'],
                                       'expanded_bytes': len(plain), 'expanded_sha256': image.digest(plain)}
                manifest['smoke']['image_id'] = identity
                (root / 'manifest.json').write_bytes(image.canonical(manifest))
                with self.assertRaisesRegex(policy.Refused, 'backend state contract unproven'):
                    policy.validate_packet(root, selected)

    def test_separate_five_member_packet_and_exact_bytes(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); selected, manifest = packet_fixture(root)
            self.assertEqual(policy.validate_packet(root, selected), manifest)
            bundle = root / 'artifact.zip'
            with zipfile.ZipFile(bundle, 'w') as archive:
                for filename in policy.packet_files(SOURCE): archive.write(root / filename, filename)
            target = root / 'unpacked'; target.mkdir()
            policy.unpack_packet(bundle, target, SOURCE)
            self.assertEqual(policy.validate_packet(target, selected), manifest)
            (target / 'bom.json').write_bytes(b'changed')
            with self.assertRaises(policy.Refused): policy.validate_packet(target, selected)
            with self.assertRaises(policy.Refused): image.unpack_zip(bundle, root)  # Not a #24 packet.

    def test_zip_duplicate_link_and_traversal_are_refused(self):
        for bad in ('../manifest.json', 'symlink', 'duplicate'):
            with tempfile.TemporaryDirectory() as name:
                root = Path(name); packet_fixture(root)
                bundle = root / 'artifact.zip'
                with zipfile.ZipFile(bundle, 'w') as archive:
                    for filename in policy.packet_files(SOURCE):
                        if filename == 'manifest.json' and bad == 'symlink':
                            info = zipfile.ZipInfo(filename); info.external_attr = 0o120777 << 16
                            archive.writestr(info, b'target')
                        else: archive.write(root / filename, filename)
                    if bad != 'symlink': archive.writestr('../manifest.json' if bad != 'duplicate' else 'manifest.json', b'{}')
                target = root / 'unpacked'; target.mkdir()
                with self.subTest(bad=bad), self.assertRaises(policy.Refused): policy.unpack_packet(bundle, target, SOURCE)

    def test_jar_version_source_and_actual_image_version_are_not_labels_in_a_receipt(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); selected, manifest = packet_fixture(root)
            for changed in ({**SOURCE, 'version': '2.0.0'}, {**SOURCE, 'sha': MAIN}):
                with self.subTest(source=changed), self.assertRaises(policy.Refused):
                    policy.jar_contract(root / 'kira-backend-1.0.0.jar', changed)
            with self.assertRaises(policy.Refused): policy.release_image(root / 'image.tar.gz', {**SOURCE, 'version': '2.0.0'})
            with self.assertRaises(policy.Refused): policy.validate_packet(root, selected, 'e' * 64)

    def test_jar_duplicate_case_insensitive_manifest_attribute_is_refused(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); packet_fixture(root)
            path = root / 'kira-backend-1.0.0.jar'
            with zipfile.ZipFile(path) as jar: manifest = jar.read('META-INF/MANIFEST.MF')
            with zipfile.ZipFile(path, 'w') as jar:
                jar.writestr('META-INF/MANIFEST.MF', manifest.replace(
                    b'Implementation-Version: 1.0.0', b'Implementation-Version: 1.0.0\r\nimplementation-version: 9.0.0'))
            with self.assertRaises(policy.Refused): policy.jar_contract(path, SOURCE)

    def test_immutable_artifact_id_digest_length_source_attempt_and_freshness(self):
        with tempfile.TemporaryDirectory() as name, patch.object(policy.time, 'time', return_value=NOW):
            api = FakeApi(Path(name))
            artifact = {'id': 300, 'name': 'backend-release-200-1', 'size_in_bytes': 123, 'digest': 'sha256:' + 'f' * 64,
                        'expired': False, 'created_at': COMPLETED,
                        'expires_at': dt.datetime.fromtimestamp(NOW + 3600, dt.timezone.utc).isoformat(),
                        'workflow_run': {'id': 200, 'repository_id': policy.REPOSITORY_ID,
                            'head_repository_id': policy.REPOSITORY_ID, 'head_sha': MAIN, 'head_branch': 'main'}}
            api.records['/actions/runs/200/artifacts?per_page=100'] = {'total_count': 1, 'artifacts': [artifact]}
            api.records['/actions/artifacts/300'] = copy.deepcopy(artifact)
            with patch.object(policy, 'successful_jobs'):
                self.assertEqual(policy.artifact_metadata(api, api.ctx), artifact)
                for key, value in [('digest', 'sha256:' + 'e' * 64), ('size_in_bytes', 124), ('expired', True),
                                   ('expires_at', COMPLETED), ('id', 301)]:
                    with self.subTest(key=key), self.assertRaises(policy.Refused):
                        wrong = copy.deepcopy(artifact); wrong[key] = value
                        api.records['/actions/artifacts/300'] = wrong
                        policy.artifact_metadata(api, api.ctx, artifact)
                for path, value in [(['expired'], True), (['size_in_bytes'], policy.MAX_PACKET + 1),
                                    (['created_at'], '2020-01-01T00:00:00Z'), (['expires_at'], COMPLETED),
                                    (['workflow_run', 'head_sha'], SHA), (['workflow_run', 'head_branch'], 'other'),
                                    (['workflow_run', 'head_repository_id'], 99)]:
                    with self.subTest(path=path), self.assertRaises(policy.Refused):
                        wrong = copy.deepcopy(artifact); change(wrong, path, value)
                        api.records['/actions/artifacts/300'] = wrong
                        api.records['/actions/runs/200/artifacts?per_page=100']['artifacts'] = [wrong]
                        policy.artifact_metadata(api, api.ctx)


class RegistryTests(unittest.TestCase):
    def test_actual_registry_digest_is_computed_from_manifest_bytes_not_image_id(self):
        identity = 'sha256:' + 'a' * 64
        raw = image.canonical({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                               'config': {'digest': identity}, 'layers': []})
        digest = 'sha256:' + image.digest(raw)
        with patch.dict(os.environ, {'GITHUB_ACTOR': 'fixture'}), patch.object(policy, 'http_get', side_effect=[
                (200, {}, b'{"token":"registry-fixture"}'), (200, {'Docker-Content-Digest': digest}, raw)]) as get:
            self.assertEqual(policy.Registry('read-fixture').manifest('1.0.0'), (digest, identity))
        self.assertNotEqual(digest, identity)
        self.assertTrue(all(x.args[0] == 'ghcr.io' for x in get.call_args_list))
        self.assertEqual(get.call_args_list[1].args[2], 'Bearer registry-fixture')

    def test_only_authenticated_explicit_not_found_means_absent(self):
        unknown = b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'
        for status, raw, accepted in ((404, unknown, True), (401, unknown, False), (403, unknown, False),
                                      (302, unknown, False), (200, b'{}', False), (404, b'{}', False),
                                      (404, b'{"errors":[{"code":"UNAUTHORIZED"}]}', False)):
            with self.subTest(status=status, raw=raw), patch.dict(os.environ, {'GITHUB_ACTOR': 'fixture'}), \
                 patch.object(policy, 'http_get', side_effect=[(200, {}, b'{"token":"registry-fixture"}'), (status, {}, raw)]):
                registry = policy.Registry('read-fixture')
                if accepted: self.assertIsNone(registry.manifest('1.0.0', absent=True))
                else:
                    with self.assertRaises(policy.Refused): registry.manifest('1.0.0', absent=True)


class PublicationTests(unittest.TestCase):
    def test_failed_native_recheck_or_existing_target_never_invokes_mutating_command(self):
        for reason in ('missing', 'stale', 'changed', 'existing-release', 'existing-image'):
            with tempfile.TemporaryDirectory() as name, patch.object(policy.time, 'time', return_value=NOW):
                root = Path(name); selected, manifest = packet_fixture(root)
                envelope = {'manifest_sha256': image.digest((root / 'manifest.json').read_bytes())}
                if reason != 'missing':
                    (root / 'authorization-check.json').write_bytes(image.canonical({
                        'envelope_sha256': 'f' * 64 if reason == 'changed' else image.digest(image.canonical(envelope)),
                        'checked_at': NOW - 121 if reason == 'stale' else NOW}))
                api = FakeApi(root)
                if reason == 'existing-release': api.records['/releases?per_page=100&page=1'] = [{'id': 400, 'tag_name': SOURCE['tag']}]
                with patch.object(image, 'inspect_image', return_value=manifest['image']['id']), \
                     patch.object(image, 'command') as command, patch.object(policy, 'Registry') as registry:
                    if reason == 'existing-image': registry.return_value.manifest.side_effect = policy.Refused('existing registry target')
                    with self.assertRaises((policy.Refused, FileNotFoundError)):
                        policy.publish_image(root, selected, envelope, api)
                    command.assert_not_called()
                    self.assertFalse((root / 'docker-config').exists())

    def test_publisher_promotes_exact_image_and_reports_actual_registry_manifest_digest(self):
        with tempfile.TemporaryDirectory() as name, patch.object(policy.time, 'time', return_value=NOW):
            root = Path(name); selected, manifest = packet_fixture(root)
            envelope = {'manifest_sha256': image.digest((root / 'manifest.json').read_bytes())}
            (root / 'authorization-check.json').write_bytes(image.canonical({
                'envelope_sha256': image.digest(image.canonical(envelope)), 'checked_at': NOW}))
            identity, digest = manifest['image']['id'], 'sha256:' + 'f' * 64
            self.assertNotEqual(identity, digest)
            def registry_manifest(tag, *, absent=False):
                self.assertIn(tag, policy.registry_tags(SOURCE))
                return None if absent else (digest, identity)
            calls = []
            def command(argv, **kwargs):
                calls.append(argv)
                self.assertNotIn('run', argv); self.assertNotIn('build', argv)
                if 'login' in argv: self.assertEqual(kwargs['stdin'].read(), b'fixture-only-token\n')
                if 'tag' in argv: self.assertEqual(argv[argv.index('tag') + 1], identity)
                return b''
            with patch.object(image, 'inspect_image', return_value=identity), patch.object(image, 'command', side_effect=command), \
                 patch.object(policy, 'Registry') as registry, patch.dict(os.environ, {
                     'GITHUB_ACTOR': 'fixture', 'GITHUB_OUTPUT': str(root / 'output')}):
                registry.return_value.manifest.side_effect = registry_manifest
                policy.publish_image(root, selected, envelope, FakeApi(root))
            self.assertEqual(sum('push' in x for x in calls), 2)
            self.assertIn('digest=' + digest, (root / 'output').read_text())
            self.assertTrue((root / 'docker-config').is_dir())  # Needed only by the next pinned attestation step.
            self.assertEqual((root / 'docker-config').stat().st_mode & 0o077, 0)

    def test_lost_freshness_before_second_push_stops_it_and_cleans_owned_credentials(self):
        with tempfile.TemporaryDirectory() as name, patch.object(policy.time, 'time', return_value=NOW):
            root = Path(name); selected, manifest = packet_fixture(root)
            envelope = {'manifest_sha256': image.digest((root / 'manifest.json').read_bytes())}
            calls = []
            with patch.object(image, 'inspect_image', return_value=manifest['image']['id']), \
                 patch.object(image, 'command', side_effect=lambda argv, **kwargs: calls.append(argv) or b''), \
                 patch.object(policy, 'Registry'), patch.dict(os.environ, {'GITHUB_ACTOR': 'fixture'}), \
                 patch.object(policy, 'require_fresh_check', side_effect=[None, None, policy.Refused('expired')]):
                with self.assertRaises(policy.Refused): policy.publish_image(root, selected, envelope, FakeApi(root))
            self.assertEqual(sum('push' in x for x in calls), 1)  # Partial publication is NOT undone or retried.
            self.assertFalse((root / 'docker-config').exists())


class WorkflowTests(unittest.TestCase):
    def assert_release_boundary(self, text):
        header, body = text.split('jobs:\n', 1)
        self.assertIn('workflow_dispatch:', header); self.assertNotIn('  push:', header)
        self.assertNotRegex(header, r'\bwrite\b')
        self.assertNotIn('secrets.', header)
        self.assertEqual(set(re.findall(r'^      ([a-z_]+):$', header, re.M)),
                         {'tag', 'source_sha', 'ci_run_id', 'ci_run_attempt'})
        jobs = dict(re.findall(r'^  ([a-z]+):\n(.*?)(?=^  [a-z]+:\n|\Z)', body, re.M | re.S))
        self.assertEqual(set(jobs), {'preflight', 'build', 'freeze', 'publish'})
        for name, job in jobs.items():
            for guard in ("github.ref == 'refs/heads/main'", "github.run_attempt == '1'",
                          "github.repository == 'kira-manga/Kira-backend'", "github.repository_id == '1304735394'",
                          "github.event_name == 'workflow_dispatch'", 'github.workflow_sha == github.sha',
                          "github.workflow_ref == 'kira-manga/Kira-backend/.github/workflows/publish-release.yml@refs/heads/main'"):
                self.assertIn(guard, job)
            self.assertNotIn('continue-on-error', job)
            self.assertEqual(set(re.findall(r'secrets\.([A-Z0-9_]+)', job)),
                             {'KIRA_PACKAGES_READ_TOKEN'} if name == 'build' else {'BACKEND_POLICY_READ_TOKEN'})
            for checkout in re.findall(r'uses: actions/checkout@.*?(?=      - |\Z)', job, re.S):
                self.assertIn('persist-credentials: false', checkout)
            if name != 'publish':
                self.assertNotIn(': write', job); self.assertNotIn('environment:', job)
                self.assertNotIn('PRODUCTION_SSH', job)
        self.assertNotIn('BACKEND_POLICY_READ_TOKEN', jobs['build'])
        self.assertIn('push: false', jobs['build']); self.assertIn('overwrite: false', jobs['build'])
        self.assertIn('needs: preflight', jobs['build'])
        self.assertIn("needs.preflight.result == 'success'", jobs['build'])
        self.assertIn('needs: [preflight, build]', jobs['freeze'])
        self.assertIn("needs.build.result == 'success'", jobs['freeze'])
        publish = jobs['publish']
        for required in ('needs: [preflight, freeze]', "needs.freeze.result == 'success'", 'environment: production',
                         'EXPECTED_ENVELOPE:', 'release_policy.py recheck', '--verify-tag', '--target "$RELEASE_SOURCE_SHA"',
                         'subject-digest: ${{ steps.image.outputs.digest }}',
                         'DOCKER_CONFIG: ${{ steps.scratch.outputs.path }}/docker-config', 'image_release.py cleanup'):
            self.assertIn(required, publish)
        for forbidden in ('./gradlew', 'docker/build-push', 'container-smoke.sh', 'KIRA_PACKAGES_READ_TOKEN',
                          'docker run', 'docker build',
                          'PRODUCTION_SSH', 'ref: ${{ needs.preflight.outputs.sha }}', 'source/', 'action-gh-release',
                          'gh release edit', '--clobber', 'if: always()\n'):
            self.assertNotIn(forbidden, publish)
        self.assertLess(publish.index('release_policy.py recheck'), publish.index('release_policy.py publish-image'))
        self.assertLess(publish.rindex('release_policy.py recheck'), publish.index('gh release create'))
        self.assertIn('cancel-in-progress: false', header)
        # No shell interpolation of raw dispatch inputs; selectors are step env only.
        for run in re.findall(r'        run:.*?(?=      - |\Z)', text, re.S): self.assertNotIn('${{ inputs.', run)

    def test_current_release_boundary_and_guard_mutations(self):
        self.assertEqual(policy.RELEASE, '.github/workflows/publish-release.yml')
        self.assertFalse((policy.ROOT / policy.LEGACY_RELEASE).exists())
        self.assertIn(policy.RELEASE, policy.CONTROLS); self.assertNotIn(policy.LEGACY_RELEASE, policy.CONTROLS)
        text = (policy.ROOT / policy.RELEASE).read_text()
        self.assert_release_boundary(text)
        mutations = [text.replace('    environment: production\n', ''),
                     text.replace('    needs: [preflight, freeze]\n', ''),
                     text.replace("github.ref == 'refs/heads/main'", 'true'),
                     text.replace('publish-release.yml@refs/heads/main', 'release.yml@refs/heads/main'),
                     text.replace('  workflow_dispatch:', '  push:'),
                     text.replace('  contents: read', '  contents: write', 1),
                     text.replace('persist-credentials: false', 'persist-credentials: true'),
                     text.replace('      tag:', '      approved:'),
                     text.replace('    needs: preflight\n', ''),
                     text.replace('secrets.KIRA_PACKAGES_READ_TOKEN', 'secrets.BACKEND_PRODUCTION_SSH_PRIVATE_KEY'),
                     text.replace('run: python3 -B scripts/ci/release_policy.py publish-image', 'run: ./gradlew bootJar'),
                     text.replace("needs.freeze.result == 'success'", 'always()'),
                     text.replace('          subject-digest: ${{ steps.image.outputs.digest }}',
                                  '          subject-digest: ${{ steps.image.outputs.imageid }}')]
        for index, mutation in enumerate(mutations):
            with self.subTest(index=index), self.assertRaises(AssertionError): self.assert_release_boundary(mutation)

    def test_rollout_contract_does_not_equate_rename_or_snapshot_with_retirement(self):
        # Source/runbook contract only: none of these assertions proves installed GitHub enforcement.
        sections = [
            ('docs/RELEASE.md', '## Legacy workflow retirement', '## External native policy'),
            ('docs/DEPLOYMENT.md', 'The separate [manual release path]', 'The host captures'),
            ('deploy/server3/README.md', '**Legacy tag-writer block:**', 'Coordinate host/key retirement')]
        required = ('315951352', '.github/workflows/release.yml', '.github/workflows/publish-release.yml',
                    'disabled_manually', 'new ancestor-tag invocations', 'historical reruns',
                    're-enable', 'alternate writers', 'not retirement', 'owner', 'capabilit', 'unknown', 'blocked')
        def contract(text):
            for value in required: self.assertIn(value, text)
        for name, start, end in sections:
            text = (policy.ROOT / name).read_text().split(start, 1)[1].split(end, 1)[0].replace('**', '').lower()
            with self.subTest(path=name):
                contract(text)
                for removed in ('new ancestor-tag invocations', 'historical reruns', 're-enable',
                                'alternate writers', 'not retirement', 'blocked'):
                    with self.subTest(removed=removed), self.assertRaises(AssertionError):
                        contract(text.replace(removed, '<removed>'))

    def test_server3_adds_policy_without_replacing_image_promotion_or_ci(self):
        text = (policy.ROOT / image.DEPLOY).read_text()
        self.assertEqual(text.count('release_policy.py deploy'), 2)
        self.assertIn('EXPECTED_POLICY: ${{ needs.preflight.outputs.policy }}', text)
        self.assertIn('environment: production', text); self.assertIn('needs: preflight', text)
        self.assertIn('EXPECTED_ARTIFACT_ID:', text); self.assertIn('image_release.py transfer', text)
        self.assertIn('secrets.BACKEND_PRODUCTION_SSH_PRIVATE_KEY', text)
        self.assertNotIn('secrets.SERVER3_SSH_PRIVATE_KEY', text)
        self.assertLess(text.rindex('release_policy.py deploy'), text.index('image_release.py transfer'))
        for forbidden in ('packages:', 'docker/', 'build-push', 'continue-on-error'): self.assertNotIn(forbidden, text)
        ci = (policy.ROOT / image.CI).read_text()
        for name in policy.CHECKS: self.assertIn('  ' + name + ':\n', ci)
        self.assertIn("-m unittest discover -s scripts/ci -p 'test_*.py'", ci)


if __name__ == '__main__':
    unittest.main()
