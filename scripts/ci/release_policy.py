#!/usr/bin/env python3
"""Backend-only release authorization checks; stdlib, no policy/settings writes.

Native production approval is authority. A fingerprint is only a drift check.
The server3 wire protocol/receiver stays in image_release.py, unchanged. Release
JAR/SBOM/image packets are separate, and only the protected publisher may mutate
GHCR/releases. Never import or execute a selected source's checker in that job.
"""

import argparse
import base64
import datetime as dt
import gzip
import hashlib
import http.client
import os
from pathlib import Path
import re
import shutil
import ssl
import stat
import struct
import tarfile
import tempfile
import time
import zipfile

import image_release as image

need, Refused = image.need, image.Refused
ROOT, REPOSITORY, REPOSITORY_ID = image.ROOT, image.REPOSITORY, image.REPOSITORY_ID
RELEASE = '.github/workflows/publish-release.yml'
LEGACY_RELEASE_ID = 315951352
LEGACY_RELEASE = '.github/workflows/release.yml'
LEGACY_RELEASE_ENDPOINT = '/actions/workflows/' + str(LEGACY_RELEASE_ID)
CONTROLS = (*image.CONTRACT, image.DEPLOY, RELEASE, 'scripts/ci/release_policy.py')
CHECKS = ('container', 'observability-rules', 'supply-chain', 'verify')
PROTECTION = '/branches/main/protection'
ENVIRONMENT = '/environments/production'
BRANCHES = ENVIRONMENT + '/deployment-branch-policies?per_page=100&page=1'
RULESETS = '/rulesets?includes_parents=true&per_page=100&page=1'
SIGNERS = '/actions/variables/BACKEND_RELEASE_SIGNERS'
STABLE = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'
REGISTRY = 'ghcr.io/kira-manga/kira-backend'
MAX_PACKET = image.MAX_IMAGE
MAX_JAR, MAX_BOM = 128 * 1024 * 1024, 16 * 1024 * 1024
MAX_RECORD = 32 * 1024
AGE = 72 * 60 * 60
# Fixed joined-contract ceiling, not a limit derived from future CONTRACT growth:
# two sources * (commit/tree2 + controls23 + release controls3 + version/changelog2)
# + trusted-context9 + remaining selection10 + two artifact-metadata passes *3 =85.
MAX_API_REQUESTS = 85


def obj(value):
    need(type(value) is dict, 'missing metadata object')
    return value


def http_get(host, path, authorization, maximum=image.MAX_API):
    """Fixed callers/hosts; no proxy, redirect, alternate credential or retry."""
    connection = http.client.HTTPSConnection(host, timeout=10, context=ssl.create_default_context())
    try:
        with image.deadline(20):
            connection.request('GET', path, headers={
                'Authorization': authorization, 'Accept-Encoding': 'identity',
                'Accept': ('application/vnd.github+json' if host == 'api.github.com' else
                           'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'),
                'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'kira-backend-release-policy',
            })
            response = connection.getresponse()
            need(not response.getheader('Link') and response.getheader('Content-Encoding', 'identity') == 'identity',
                 'paginated or encoded response refused')
            length = response.getheader('Content-Length')
            need(length is None or (re.fullmatch(r'[0-9]{1,10}', length) and int(length) <= maximum),
                 'metadata byte limit')
            raw = response.read(maximum + 1)
            need(len(raw) <= maximum and (length is None or len(raw) == int(length)), 'truncated or oversized metadata')
            return response.status, dict(response.getheaders()), raw
    except (OSError, http.client.HTTPException):
        raise Refused('metadata transport unavailable') from None
    finally:
        connection.close()


def token(value):
    need(type(value) is str and 0 < len(value) <= 4096 and all(33 <= ord(x) <= 126 for x in value),
         'missing read authority')
    return value


class Api(image.GitHub):
    """Finite GET reader. The special token is confined to native policy/trust."""

    def __init__(self, read_token, policy_token=None):
        super().__init__(token(read_token))
        self.policy_token = policy_token
        self.end, self.count = time.monotonic() + 180, 0

    def get(self, suffix):
        policy = suffix in {PROTECTION, RULESETS, SIGNERS} or bool(re.fullmatch(r'/rulesets/[1-9][0-9]*', suffix))
        static = {'', PROTECTION, RULESETS, SIGNERS, ENVIRONMENT, BRANCHES, LEGACY_RELEASE_ENDPOINT, '/git/ref/heads/main',
                  '/releases?per_page=100&page=1', *('/actions/workflows/' + x.split('/')[-1]
                                                 for x in (image.CI, image.DEPLOY, RELEASE))}
        dynamic = (r'/rulesets/[1-9][0-9]*|/git/(?:commits|blobs|tags)/[0-9a-f]{40}|'
                   r'/git/trees/[0-9a-f]{40}\?recursive=1|/git/ref/tags/v' + STABLE + r'|'
                   r'/actions/runs/[1-9][0-9]*(?:/attempts/[1-9][0-9]*(?:/jobs\?per_page=100)?|'
                   r'/artifacts\?per_page=100)?|/actions/artifacts/[1-9][0-9]*|/releases/tags/v' + STABLE)
        need(suffix in static or re.fullmatch(dynamic, suffix), 'unsupported metadata endpoint')
        self.count += 1
        need(self.count <= MAX_API_REQUESTS and time.monotonic() < self.end, 'metadata request budget exhausted')
        credential = token(self.policy_token) if policy else self.token
        status, headers, raw = http_get('api.github.com', '/repos/' + REPOSITORY + suffix, 'Bearer ' + credential)
        need(status == 200, 'GitHub metadata unavailable')
        need(next((v for k, v in headers.items() if k.lower() == 'content-type'), '').split(';')[0]
             in ('application/json', 'application/vnd.github+json'), 'non-JSON GitHub metadata')
        return image.parse_json(raw, image.MAX_API)

    def source(self, sha):
        sha = image.hex_value(sha, 40)
        if sha not in self.sources:
            commit = obj(self.get('/git/commits/' + sha))
            need(commit.get('sha') == sha, 'wrong source commit')
            tree = image.hex_value(obj(commit.get('tree')).get('sha'), 40)
            listing = obj(self.get('/git/trees/' + tree + '?recursive=1'))
            rows = listing.get('tree')
            need(listing.get('sha') == tree and listing.get('truncated') is False
                 and type(rows) is list and 0 < len(rows) <= 20000, 'incomplete source tree')
            entries = {obj(row).get('path'): row for row in rows}
            need(len(entries) == len(rows) and all(type(p) is str for p in entries), 'ambiguous source tree')
            image.migration_inventory(entries)
            blobs = {}
            for name in (*CONTROLS, 'build.gradle.kts', 'CHANGELOG.md'):
                item = obj(entries.get(name))
                need(item.get('type') == 'blob' and item.get('mode') in ('100644', '100755'), 'missing source control')
                oid = image.hex_value(item.get('sha'), 40)
                record = obj(self.get('/git/blobs/' + oid))
                need(record.get('sha') == oid and record.get('encoding') == 'base64'
                     and image.integer(record.get('size')) <= image.MAX_CONFIG, 'unsupported source blob')
                raw = base64.b64decode(record['content'].replace('\n', ''), validate=True)
                need(len(raw) == record['size'] and git_oid('blob', raw) == oid, 'source blob changed')
                blobs[name] = raw
            image.migration_bytes({name: image.digest(raw) for name, raw in blobs.items()})
            self.sources[sha] = (tree, blobs)
        return self.sources[sha]


def native_policy(repository, environment, branches, protection):
    environment, protection = obj(environment), obj(protection)
    need(environment.get('name') == 'production' and environment.get('can_admins_bypass') is False,
         'permanent non-bypassable production approval required')
    flags = obj(environment.get('deployment_branch_policy'))
    need(flags == {'protected_branches': False, 'custom_branch_policies': True}, 'exact-main environment policy required')
    refs = image.complete_list(obj(branches), 'branch_policies')
    need(len(refs) == 1 and refs[0].get('name') == 'main' and refs[0].get('type') == 'branch', 'extra deployment ref')
    rules = environment.get('protection_rules')
    need(type(rules) is list and 1 <= len(rules) <= 2, 'missing native approval rules')
    by_type = {}
    for rule in rules:
        rule = obj(rule)
        kind = rule.get('type')
        need(kind in ('required_reviewers', 'branch_policy') and kind not in by_type, 'unknown or duplicate native rule')
        if kind == 'branch_policy':
            image.keys(rule, 'type', 'id node_id')  # Optional marker is NOT approval.
            if 'id' in rule:
                image.integer(rule['id'])
            if 'node_id' in rule:
                need(type(rule['node_id']) is str and 0 < len(rule['node_id']) <= 200, 'invalid native branch marker')
        by_type[kind] = rule
    approval = obj(by_type.get('required_reviewers'))
    need(approval.get('prevent_self_review') is True, 'self approval is forbidden')
    reviewers = approval.get('reviewers')
    need(type(reviewers) is list and 1 <= len(reviewers) <= 6, 'explicit human reviewer identities required')
    users = []
    for entry in reviewers:
        entry, user = obj(entry), obj(obj(entry).get('reviewer'))
        need(entry.get('type') == 'User' and user.get('type') == 'User'
             and type(user.get('login')) is str and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}', user['login']),
             'only explicit User reviewers are supported')
        users.append(image.integer(user.get('id')))
    need(len(set(users)) == len(users), 'duplicate reviewer')
    for name, expected in (('enforce_admins', True), ('allow_force_pushes', False), ('allow_deletions', False)):
        need(obj(protection.get(name)).get('enabled') is expected, 'main protection is bypassable')
    reviews = obj(protection.get('required_pull_request_reviews'))
    count = image.integer(reviews.get('required_approving_review_count'))
    need(count <= 6 and reviews.get('dismiss_stale_reviews') is True and reviews.get('require_last_push_approval') is True
         and reviews.get('bypass_pull_request_allowances') == {'users': [], 'teams': [], 'apps': []},
         'enforced independent main review required')
    checks = obj(protection.get('required_status_checks'))
    required = checks.get('checks')
    need(checks.get('strict') is True and type(required) is list and len(required) == len(CHECKS)
         and {(obj(x).get('context'), image.integer(x.get('app_id'))) for x in required} == {(x, 15368) for x in CHECKS}
         and type(checks.get('contexts')) is list and sorted(checks['contexts']) == list(CHECKS),
         'all four app-bound strict CI contexts required')
    return {'repository': repository, 'environment_id': image.integer(environment.get('id')),
            'approval_rule_id': image.integer(approval.get('id')), 'reviewers': sorted(users),
            'prevent_self_review': True, 'can_admins_bypass': False, 'branch_policy': refs[0]['id'],
            'ref': 'refs/heads/main', 'approving_reviews': count, 'dismiss_stale_reviews': True,
            'require_last_push_approval': True, 'review_bypass': False, 'enforce_admins': True,
            'force_pushes': False, 'deletions': False, 'strict_checks': [[x, 15368] for x in CHECKS]}


def tag_policy(records):
    """Creation exceptions must never bypass the separate immutability ruleset."""
    need(type(records) is list and len(records) == 2, 'two explicit release-tag rulesets required')
    normalized, ids = {}, set()
    for record in records:
        record = obj(record)
        identity = image.integer(record.get('id'))
        need(identity not in ids and record.get('target') == 'tag' and record.get('enforcement') == 'active'
             and record.get('source_type') == 'Repository' and record.get('source') == REPOSITORY,
             'unsupported release-tag enforcement')
        ids.add(identity)
        conditions = {'ref_name': {'include': ['refs/tags/v*.*.*'], 'exclude': []}}
        need(record.get('conditions') == conditions, 'release-tag coverage must be complete')
        rules, bypass = record.get('rules'), record.get('bypass_actors')
        need(type(rules) is list and type(bypass) is list, 'missing tag rules/bypass evidence')
        for rule in rules:
            image.keys(rule, 'type')
        kinds = sorted(rule['type'] for rule in rules)
        if kinds == ['creation']:
            need(1 <= len(bypass) <= 20, 'explicit release-tag creators required')
            creators = []
            for actor in bypass:
                image.keys(actor, 'actor_id actor_type bypass_mode')
                need(actor['actor_type'] in ('Team', 'Integration') and actor['bypass_mode'] == 'always',
                     'only explicit Team/Integration tag creators are supported')
                creators.append((actor['actor_type'], image.integer(actor['actor_id'])))
            need(len(set(creators)) == len(creators), 'duplicate tag creator')
            kind, actors = 'creation', [list(x) for x in sorted(creators)]
        else:
            need(kinds == ['deletion', 'update'] and bypass == [], 'tag immutability must have no bypass')
            kind, actors = 'immutable', []
        need(kind not in normalized, 'duplicate tag policy')
        normalized[kind] = {'id': identity, 'rules': kinds, 'creators': actors, 'conditions': conditions}
    need(set(normalized) == {'creation', 'immutable'}, 'incomplete tag policy')
    return normalized


def signer_trust(record):
    need(obj(record).get('name') == 'BACKEND_RELEASE_SIGNERS' and type(record.get('value')) is str,
         'missing explicit public signer trust')
    values = image.parse_json(record['value'].encode(), image.MAX_RECEIPT)
    need(type(values) is list and 1 <= len(values) <= 6, 'empty or oversized signer allowlist')
    signers, principals, keys = [], set(), set()
    for value in values:
        image.keys(value, 'principal key')
        principal, key = value['principal'], value['key']
        need(type(principal) is str and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._@+\-]{0,199}', principal)
             and type(key) is str and re.fullmatch(r'ssh-ed25519 [A-Za-z0-9+/]{68}', key), 'unsupported signer identity/key')
        raw = base64.b64decode(key.split(' ')[1], validate=True)
        need(len(raw) == 51 and raw[:19] == b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20'
             and principal not in principals and key not in keys, 'invalid or duplicate signer')
        principals.add(principal)
        keys.add(key)
        signers.append({'principal': principal, 'key': key})
    return sorted(signers, key=lambda x: x['principal'])


def git_oid(kind, raw):
    return hashlib.sha1(kind.encode() + b' ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def git(*args, root=ROOT, **kwargs):
    return image.command(['/usr/bin/git', '--no-replace-objects', '-c', 'core.hooksPath=/dev/null',
                          '-c', 'core.fsmonitor=false', '-C', str(root), *args], **kwargs)


def tag_contract(tag, gradle, changelog):
    need(type(tag) is str and len(tag) <= 64 and re.fullmatch('v' + STABLE, tag), 'strict stable release tag required')
    text = gradle.decode('utf-8')
    # This supports the current single literal, not evaluation of candidate Gradle.
    declarations = re.findall(r'(?m)^\s*(?:(?:project|rootProject|this)\s*\.\s*)?version\s*=.*$', text)
    need(len(declarations) == 1 and re.fullmatch(r'\s*version[ \t]*=[ \t]*"' + STABLE + r'"[ \t]*', declarations[0])
         and not re.search(r'\bsetVersion\s*\(|\b(?:project|rootProject|this)\s*\.\s*version\b|\bversion\s*\(', text),
         'one unambiguous literal project version required')
    version = declarations[0].split('"')[1]
    headings = [line for line in changelog.decode('utf-8').splitlines() if line.startswith('## ')]
    if headings and headings[0] == '## [Unreleased]':
        headings.pop(0)
    versions = []
    for heading in headings:
        match = re.fullmatch(r'## \[(' + STABLE + r')\] - ([0-9]{4}-[0-9]{2}-[0-9]{2})', heading)
        need(match is not None, 'ambiguous released changelog heading')
        dt.date.fromisoformat(match[2])
        versions.append(match[1])
    need(versions and len(set(versions)) == len(versions) and versions[0] == version == tag[1:]
         and [tuple(map(int, v.split('.'))) for v in versions] == sorted(
             (tuple(map(int, v.split('.'))) for v in versions), reverse=True), 'tag/project/latest changelog mismatch')
    return version


def signed_tag(raw, oid, tag, sha, signers, directory):
    need(git_oid('tag', raw) == oid, 'annotated tag object changed')
    header = raw.split(b'\n\n', 1)[0].split(b'\n')
    need(len(header) == 4 and header[:3] == [b'object ' + sha.encode(), b'type commit', b'tag ' + tag.encode()]
         and header[3].startswith(b'tagger '), 'only directly annotated commit tags are supported')
    marker = b'-----BEGIN SSH SIGNATURE-----\n'
    need(raw.count(marker) == 1 and raw.endswith(b'-----END SSH SIGNATURE-----\n'), 'SSH-signed annotated tag required')
    payload, signature = raw.split(marker)
    with tempfile.TemporaryDirectory(prefix='signers-', dir=directory) as name:
        work = Path(name)
        allowed = work / 'allowed-signers'
        allowed.write_text(''.join(x['principal'] + ' namespaces="git" ' + x['key'] + '\n' for x in signers))
        (work / 'signature').write_bytes(marker + signature)
        (work / 'payload').write_bytes(payload)
        command = ['/usr/bin/ssh-keygen', '-Y']
        found = image.command(command + ['find-principals', '-f', str(allowed), '-s', str(work / 'signature')],
                              maximum=2048).decode().splitlines()
        need(len(found) == 1 and found[0] in {x['principal'] for x in signers}, 'signature signer is not explicitly trusted')
        with (work / 'payload').open('rb') as source:
            image.command(command + ['verify', '-f', str(allowed), '-I', found[0], '-n', 'git',
                                     '-s', str(work / 'signature')], stdin=source, maximum=2048)


def legacy_release_disabled(record):
    """Fresh-state gate only, not retirement or durable creator-capability proof."""
    record = obj(record)
    need(image.integer(record.get('id')) == LEGACY_RELEASE_ID and record.get('path') == LEGACY_RELEASE
         and record.get('state') == 'disabled_manually', 'exact legacy release workflow must be disabled_manually')
    return {name: record[name] for name in ('id', 'path', 'state')}


def trusted_context(api, ctx, release):
    path, event = (RELEASE, 'workflow_dispatch') if release else (image.DEPLOY, 'workflow_run')
    image.workflow_context(ctx, path, event)
    need(ctx['attempt'] == 1 and os.environ.get('GITHUB_SERVER_URL') == 'https://github.com'
         and os.environ.get('GITHUB_API_URL') == 'https://api.github.com', 'use a fresh trusted-main invocation')
    repository = obj(api.get(''))
    owner = obj(repository.get('owner'))
    need(repository.get('id') == REPOSITORY_ID and repository.get('full_name') == REPOSITORY
         and owner.get('login') == 'kira-manga'
         and image.integer(owner.get('id')) == image.number_input(os.environ.get('GITHUB_REPOSITORY_OWNER_ID', ''))
         and all(repository.get(x) is False for x in ('fork', 'archived', 'disabled'))
         and repository.get('default_branch') == 'main', 'repository identity/state mismatch')
    legacy = legacy_release_disabled(api.get(LEGACY_RELEASE_ENDPOINT))  # No cached/filename-only retirement inference.
    workflow = obj(api.get('/actions/workflows/' + path.split('/')[-1]))
    need(workflow.get('path') == path and workflow.get('state') == 'active', 'inactive trusted workflow')
    workflow_id = image.integer(workflow.get('id'))
    need(workflow_id != LEGACY_RELEASE_ID, 'trusted workflow must have a distinct identity')
    for suffix in (f"/actions/runs/{ctx['run_id']}", f"/actions/runs/{ctx['run_id']}/attempts/1"):
        image.run_identity(api.get(suffix), ctx['run_id'], 1, workflow_id, path, event, ctx['sha'], consumer=True)
    main = obj(api.get('/git/ref/heads/main'))
    need(main.get('ref') == 'refs/heads/main' and obj(main.get('object')).get('type') == 'commit'
         and main['object'].get('sha') == ctx['sha'],
         'trusted workflow must still be current main')
    tree, blobs = api.source(ctx['sha'])
    need(git('rev-parse', 'HEAD').decode().strip() == ctx['sha']
         and git('rev-parse', 'HEAD^{tree}').decode().strip() == tree
         and all(image.file_bytes(ROOT / p, image.MAX_CONFIG) == blobs[p] for p in CONTROLS),
         'trusted checkout/control bytes changed')
    policy = native_policy({'id': REPOSITORY_ID, 'name': REPOSITORY, 'owner_id': owner['id']},
                           api.get(ENVIRONMENT), api.get(BRANCHES), api.get(PROTECTION))
    policy['legacy_release'] = legacy
    return {'workflow': path, 'workflow_id': workflow_id, 'sha': ctx['sha'], 'tree': tree,
            'run_id': ctx['run_id'], 'attempt': 1, 'policy': policy}


def successful_jobs(api, run_id, attempt, sha, names):
    jobs = image.complete_list(api.get(f'/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100'), 'jobs')
    identities = []
    for name in names:
        matches = [job for job in jobs if job.get('name') == name]
        need(len(matches) == 1, 'missing or ambiguous verification job')
        job = matches[0]
        need(job.get('run_id') == run_id and job.get('run_attempt') == attempt and job.get('head_sha') == sha
             and job.get('status') == 'completed' and job.get('conclusion') == 'success', 'unsuccessful required job')
        need(0 <= time.time() - image.timestamp(job.get('completed_at')) < AGE, 'verification freshness expired')
        identities.append({'name': name, 'id': image.integer(job.get('id')), 'completed_at': job['completed_at']})
    return identities


def selection(api, ctx, directory):
    consumer = trusted_context(api, ctx, True)
    selected = image.selected_run()
    tag = os.environ.get('RELEASE_TAG')
    sha = selected['sha']
    tree, blobs = api.source(sha)
    version = tag_contract(tag, blobs['build.gradle.kts'], blobs['CHANGELOG.md'])
    need(all(blobs[p] == api.source(ctx['sha'])[1][p] for p in CONTROLS), 'stale candidate authorization/CI controls')
    # Full object/tree identities AND real ancestry. Identical trees are not ancestry.
    need(git('rev-parse', '--is-shallow-repository').strip() == b'false'
         and not Path(git('rev-parse', '--path-format=absolute', '--git-path', 'info/grafts').decode().strip()).exists(),
         'incomplete or rewritten local history')
    need(git('rev-parse', sha + '^{tree}').decode().strip() == tree, 'candidate full tree mismatch')
    need(git('merge-base', '--is-ancestor', sha, ctx['sha'], return_status=True) == 0, 'tag target is not protected-main ancestry')
    listing = api.get(RULESETS)
    need(type(listing) is list and len(listing) < 100
         and len({image.integer(obj(x).get('id')) for x in listing}) == len(listing), 'incomplete ruleset list')
    records = []
    for item in listing:
        need(item.get('target') in ('branch', 'tag', 'push'), 'unknown ruleset target')
        if item['target'] == 'tag':
            record = obj(api.get('/rulesets/' + str(item['id'])))
            need(record.get('id') == item['id'] and record.get('target') == 'tag', 'ruleset identity changed')
            records.append(record)
    tags = tag_policy(records)
    trust = signer_trust(api.get(SIGNERS))  # Fresh API read, not cached workflow vars.
    ref = obj(api.get('/git/ref/tags/' + tag))
    need(ref.get('ref') == 'refs/tags/' + tag and obj(ref.get('object')).get('type') == 'tag', 'lightweight tag refused')
    oid = image.hex_value(ref['object'].get('sha'), 40)
    annotated = obj(api.get('/git/tags/' + oid))
    need(annotated.get('sha') == oid and annotated.get('tag') == tag
         and obj(annotated.get('object')).get('type') == 'commit' and annotated['object'].get('sha') == sha,
         'tag object/target mismatch')
    signed_tag(git('cat-file', 'tag', oid, maximum=image.MAX_RECEIPT), oid, tag, sha, trust, directory)
    workflow = obj(api.get('/actions/workflows/ci.yml'))
    need(workflow.get('path') == image.CI and workflow.get('state') == 'active', 'inactive CI workflow')
    workflow_id = image.integer(workflow.get('id'))
    run_id, attempt = selected['run_id'], selected['attempt']
    for suffix in (f'/actions/runs/{run_id}', f'/actions/runs/{run_id}/attempts/{attempt}'):
        image.run_identity(api.get(suffix), run_id, attempt, workflow_id, image.CI, 'push', sha)
    jobs = successful_jobs(api, run_id, attempt, sha, CHECKS)
    return {'schema': 1, 'source': {'sha': sha, 'tree': tree, 'tag': tag, 'tag_oid': oid, 'version': version},
            'ci': {'run_id': run_id, 'attempt': attempt, 'workflow_id': workflow_id, 'jobs': jobs},
            'consumer': consumer, 'tag_policy': tags, 'signers_sha256': image.digest(image.canonical(trust))}


def from_env(name):
    return obj(image.parse_json(os.environ.get(name, '').encode(), MAX_RECORD))


def selected_source():
    selected = from_env('EXPECTED_SELECTION')
    source = obj(selected.get('source'))
    for key in ('sha', 'tree', 'tag_oid'):
        image.hex_value(source.get(key), 40)
    need(type(source.get('tag')) is str and re.fullmatch('v' + STABLE, source['tag'])
         and source.get('version') == source['tag'][1:], 'invalid frozen release source')
    return selected, source


def artifact_metadata(api, ctx, expected=None):
    values = image.complete_list(api.get(f"/actions/runs/{ctx['run_id']}/artifacts?per_page=100"), 'artifacts')
    matches = [x for x in values if x.get('name') == f"backend-release-{ctx['run_id']}-1"]
    need(len(matches) == 1, 'missing or ambiguous immutable release artifact')
    listed = image.artifact_identity(matches[0])
    artifact = image.artifact_identity(api.get('/actions/artifacts/' + str(image.integer(listed['id']))))
    need(artifact == listed and (expected is None or artifact == expected)
         and artifact['expired'] is False and image.integer(artifact['size_in_bytes']) <= MAX_PACKET,
         'release artifact replaced/expired')
    image.image_id(artifact['digest'])
    need(0 <= time.time() - image.timestamp(artifact['created_at']) < AGE
         and time.time() < image.timestamp(artifact['expires_at']), 'release artifact freshness expired')
    need(artifact['workflow_run'] == {'id': ctx['run_id'], 'repository_id': REPOSITORY_ID,
         'head_repository_id': REPOSITORY_ID, 'head_sha': ctx['sha'], 'head_branch': 'main'}, 'artifact producer mismatch')
    successful_jobs(api, ctx['run_id'], 1, ctx['sha'], ('preflight', 'build'))
    return artifact


def packet_files(source):
    return {'image.tar.gz': image.MAX_IMAGE, 'kira-backend-' + source['version'] + '.jar': MAX_JAR,
            'bom.json': MAX_BOM, 'bom.xml': MAX_BOM, 'manifest.json': MAX_RECORD}


def zip_directory(path, maximum, count, directory_limit):
    size = path.stat().st_size
    need(22 <= size <= maximum, 'ZIP size limit')
    with path.open('rb') as stream:
        stream.seek(-22, 2)
        end = struct.unpack('<4s4H2LH', stream.read(22))
    need(end[:3] == (b'PK\x05\x06', 0, 0) and 0 < end[3] == end[4] <= count
         and end[5] <= directory_limit and end[6] + end[5] == size - 22 and end[7] == 0,
         'unsupported or unbounded ZIP directory')


def unpack_packet(path, directory, source):
    files = packet_files(source)
    zip_directory(path, MAX_PACKET, len(files), 65536)
    with zipfile.ZipFile(path) as bundle:
        entries = bundle.infolist()
        need(len(entries) == len(files) and {x.filename for x in entries} == set(files), 'unexpected release packet member')
        for entry in entries:
            need(stat.S_ISREG(entry.external_attr >> 16) and not entry.flag_bits & 1
                 and entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                 and 0 < entry.file_size <= files[entry.filename], 'nonregular or oversized release member')
            with bundle.open(entry) as stream, (directory / entry.filename).open('xb') as target:
                actual = image.copy_bounded(stream, target, files[entry.filename])
            need(actual['bytes'] == entry.file_size, 'release packet member truncated')


def release_image(path, source, expected=None):
    """Require the fixed Backend state profile through the full Docker-save validator."""
    with image.deadline(90):
        compressed = image.file_identity(path, image.MAX_IMAGE)
        with tempfile.NamedTemporaryFile(prefix='release-expanded-', dir=path.parent) as plain:
            with gzip.open(path, 'rb') as stream:
                expanded = image.copy_bounded(stream, plain, image.MAX_TAR)
            plain.flush()
            metadata = image.docker_tar(Path(plain.name), 'kira-backend:' + source['sha'], True, state_contract=True)
            need(expected is None or metadata['id'] == image.image_id(expected), 'sealed image ID mismatch')
            # The validated tar has no links/PAX/extensions, duplicate paths or unbounded metadata.
            with tarfile.open(plain.name) as archive:
                manifest = image.parse_json(archive.extractfile('manifest.json').read(image.MAX_MANIFEST + 1), image.MAX_MANIFEST)
                config = image.parse_json(archive.extractfile(manifest[0]['Config']).read(image.MAX_CONFIG + 1), image.MAX_CONFIG)
            need(config['config'].get('Labels', {}).get('org.opencontainers.image.version') == source['version'],
                 'numeric image version mismatch')
        return {**metadata, 'version': source['version']}, {**compressed, 'expanded_bytes': expanded['bytes'],
                                                          'expanded_sha256': expanded['sha256']}


def jar_contract(path, source):
    zip_directory(path, MAX_JAR, 20000, 2 * 1024 * 1024)
    with zipfile.ZipFile(path) as jar:
        entries = [x for x in jar.infolist() if x.filename == 'META-INF/MANIFEST.MF']
        need(len(entries) == 1 and not entries[0].flag_bits & 1
             and entries[0].compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
             and 0 < entries[0].file_size <= image.MAX_RECEIPT, 'missing/ambiguous JAR manifest')
        with jar.open(entries[0]) as stream:
            raw = stream.read(image.MAX_RECEIPT + 1)
        need(len(raw) == entries[0].file_size, 'JAR manifest size mismatch')
    lines = raw.decode('utf-8').replace('\r\n', '\n').replace('\n ', '').split('\n\n', 1)[0].splitlines()
    attributes = {}
    for line in lines:
        parts = line.split(': ', 1)
        need(len(parts) == 2 and re.fullmatch(r'[A-Za-z0-9_-]{1,70}', parts[0])
             and parts[0].lower() not in attributes, 'ambiguous JAR manifest attribute')
        attributes[parts[0].lower()] = parts[1]
    need(attributes.get('implementation-version') == source['version']
         and attributes.get('kira-source-sha') == source['sha']
         and attributes.get('start-class') == 'me.manga.kira.backend.KiraBackendApplicationKt', 'JAR version/source mismatch')


def validate_packet(directory, selected, manifest_sha=None):
    source = selected['source']
    raw = image.file_bytes(directory / 'manifest.json', MAX_RECORD)
    need(manifest_sha is None or image.digest(raw) == image.hex_value(manifest_sha), 'release manifest changed')
    manifest = image.parse_json(raw, MAX_RECORD)
    image.keys(manifest, 'schema purpose selection image archive files smoke')
    need(type(manifest['schema']) is int and manifest['schema'] == 1
         and manifest['purpose'] == 'backend-release-candidate' and manifest['selection'] == selected,
         'release manifest provenance mismatch')
    limits = packet_files(source)
    records = obj(manifest['files'])
    need(set(records) == set(limits) - {'manifest.json'}, 'release file inventory mismatch')
    for name, record in records.items():
        need(image.file_identity(directory / name, limits[name]) == record, 'sealed release bytes changed')
    metadata, archive = release_image(directory / 'image.tar.gz', source)
    need(manifest['image'] == metadata and manifest['archive'] == archive and manifest['smoke'] == {
        'policy': image.POLICY, 'image_id': metadata['id'], 'result': 'pass'}, 'release image/smoke binding mismatch')
    jar_contract(directory / ('kira-backend-' + source['version'] + '.jar'), source)
    return manifest


def prepare_build(directory, selected):
    source, root = selected['source'], Path(os.environ['SOURCE_DIR'])
    need(root.is_absolute() and git('rev-parse', 'HEAD', root=root).decode().strip() == source['sha']
         and git('rev-parse', 'HEAD^{tree}', root=root).decode().strip() == source['tree'], 'wrong builder checkout')
    git('diff-index', '--quiet', 'HEAD', '--', root=root)
    need(tag_contract(source['tag'], image.file_bytes(root / 'build.gradle.kts', image.MAX_CONFIG),
                      image.file_bytes(root / 'CHANGELOG.md', image.MAX_CONFIG)) == source['version'], 'builder version changed')
    # Runtime build metadata, not a new repository build setting. Check the actual
    # evaluated project version before stamping the independently source-bound JAR.
    script = ("gradle.projectsEvaluated {\n"
              f"    if (rootProject.version.toString() != '{source['version']}') throw new GradleException('Release version changed')\n"
              "    rootProject.tasks.named('bootJar').configure {\n"
              "        manifest.attributes('Implementation-Version': rootProject.version.toString(),\n"
              f"                            'Kira-Source-SHA': '{source['sha']}')\n"
              "    }\n}\n")
    (directory / 'release.init.gradle').write_text(script)


def seal(directory, selected):
    prepare_build(directory, selected)  # Verify the checkout is still unchanged after the build.
    source, root = selected['source'], Path(os.environ['SOURCE_DIR'])
    tag = 'kira-backend:' + source['sha']
    identity = image.inspect_image(tag, image.image_id(os.environ.get('BUILDX_IMAGE_ID')))
    image.command([str(ROOT / image.SMOKE), identity], seconds=600)
    image.inspect_image(tag, identity)
    package = directory / 'package'
    package.mkdir(mode=0o700)
    jar_name = 'kira-backend-' + source['version'] + '.jar'
    for name, path in {jar_name: root / 'build/libs' / jar_name,
                       'bom.json': root / 'build/reports/cyclonedx/bom.json',
                       'bom.xml': root / 'build/reports/cyclonedx/bom.xml'}.items():
        image.file_identity(path, packet_files(source)[name])
        with path.open('rb') as stream, (package / name).open('xb') as target:
            image.copy_bounded(stream, target, packet_files(source)[name])
    plain = directory / 'release-image.tar'
    with plain.open('xb') as target:
        image.command(['docker', 'save', tag], seconds=180, maximum=image.MAX_TAR, output=target)
    with (package / 'image.tar.gz').open('xb') as target:
        image.command(['gzip', '--no-name', '--stdout', str(plain)], seconds=180, maximum=image.MAX_IMAGE, output=target)
    plain.unlink()
    metadata, archive = release_image(package / 'image.tar.gz', source, identity)
    manifest = {'schema': 1, 'purpose': 'backend-release-candidate', 'selection': selected,
                'image': metadata, 'archive': archive, 'files': {
                    name: image.file_identity(package / name, limit) for name, limit in packet_files(source).items()
                    if name != 'manifest.json'}, 'smoke': {'policy': image.POLICY, 'image_id': identity, 'result': 'pass'}}
    (package / 'manifest.json').write_bytes(image.canonical(manifest))
    validate_packet(package, selected)


def release_absent(api, source):
    records = api.get('/releases?per_page=100&page=1')
    need(type(records) is list and len(records) < 100
         and len({image.integer(obj(x).get('id')) for x in records}) == len(records), 'incomplete release inventory')
    need(not any(x.get('tag_name') == source['tag'] for x in records), 'release already exists; no overwrite or automatic repair')


class Registry:
    def __init__(self, credential):
        actor = os.environ.get('GITHUB_ACTOR', '')
        need(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9\[\]\-]{0,99}', actor), 'invalid registry identity')
        auth = base64.b64encode((actor + ':' + token(credential)).encode()).decode()
        status, _, raw = http_get('ghcr.io', '/token?service=ghcr.io&scope=repository:kira-manga/kira-backend:pull',
                                  'Basic ' + auth, image.MAX_RECEIPT)
        need(status == 200, 'registry read authority unavailable')
        self.auth = 'Bearer ' + token(obj(image.parse_json(raw, image.MAX_RECEIPT)).get('token'))

    def manifest(self, tag, *, absent=False):
        need(re.fullmatch(STABLE, tag) or re.fullmatch(r'sha-[0-9a-f]{40}', tag), 'invalid registry tag')
        status, headers, raw = http_get('ghcr.io', '/v2/kira-manga/kira-backend/manifests/' + tag, self.auth, image.MAX_CONFIG)
        value = obj(image.parse_json(raw, image.MAX_CONFIG))
        if absent:
            errors = value.get('errors')
            need(status == 404 and type(errors) is list and len(errors) == 1
                 and obj(errors[0]).get('code') in ('MANIFEST_UNKNOWN', 'NAME_UNKNOWN'), 'registry target exists or is unreadable')
            return None
        need(status == 200 and type(value.get('schemaVersion')) is int and value['schemaVersion'] == 2
             and value.get('mediaType') in ('application/vnd.oci.image.manifest.v1+json',
                                          'application/vnd.docker.distribution.manifest.v2+json')
             and 'subject' not in value and 'manifests' not in value, 'not one published image manifest')
        digest = 'sha256:' + image.digest(raw)
        need(next((v for k, v in headers.items() if k.lower() == 'docker-content-digest'), '') == digest,
             'registry manifest digest mismatch')
        return digest, image.image_id(obj(value.get('config')).get('digest'))


def registry_tags(source):
    return (source['version'], 'sha-' + source['sha'])


def require_fresh_check(directory, envelope):
    value = image.parse_json(image.file_bytes(directory / 'authorization-check.json', MAX_RECORD), MAX_RECORD)
    need(obj(value).get('envelope_sha256') == image.digest(image.canonical(envelope))
         and type(value.get('checked_at')) in (int, float) and 0 <= time.time() - value['checked_at'] < 120,
         'fresh post-approval native-policy check required')


def publish_image(directory, selected, envelope, api):
    manifest = validate_packet(directory, selected, envelope['manifest_sha256'])
    source, identity = selected['source'], manifest['image']['id']
    image.inspect_image('kira-backend:' + source['sha'], identity)
    release_absent(api, source)
    registry = Registry(api.token)
    for tag in registry_tags(source):
        registry.manifest(tag, absent=True)
    require_fresh_check(directory, envelope)
    config = directory / 'docker-config'
    config.mkdir(mode=0o700)
    docker = ['docker', '--config', str(config)]
    try:
        with tempfile.TemporaryFile(dir=directory) as password:
            password.write(api.token.encode() + b'\n')
            password.seek(0)
            image.command(docker + ['login', 'ghcr.io', '--username', os.environ['GITHUB_ACTOR'], '--password-stdin'], stdin=password)
        for tag in registry_tags(source):
            image.command(docker + ['tag', identity, REGISTRY + ':' + tag])
            registry.manifest(tag, absent=True)
            require_fresh_check(directory, envelope)
            image.command(docker + ['push', REGISTRY + ':' + tag], seconds=180, maximum=image.MAX_CONFIG)
        published = [registry.manifest(tag) for tag in registry_tags(source)]
        need(published[0] == published[1] and published[0][1] == identity, 'published manifest does not name the sealed image')
        (directory / 'published.json').write_bytes(image.canonical({'digest': published[0][0], 'image_id': identity}))
        image.output('digest', published[0][0])  # Registry manifest digest, NEVER the local image ID.
    except BaseException:
        shutil.rmtree(config)
        raise
    # The pinned attestation action needs registry auth too. Its explicit DOCKER_CONFIG
    # points here; the workflow's always-cleanup removes this owned directory afterward.


def prepare_release(directory, selected, envelope, api):
    manifest = validate_packet(directory, selected, envelope['manifest_sha256'])
    published = obj(image.parse_json(image.file_bytes(directory / 'published.json', MAX_RECORD), MAX_RECORD))
    source = selected['source']
    registry = Registry(api.token)
    for tag in registry_tags(source):
        need(registry.manifest(tag) == (published['digest'], manifest['image']['id']), 'published target changed')
    release_absent(api, source)
    require_fresh_check(directory, envelope)
    (directory / 'release-notes.md').write_text(
        f"Source: `{source['sha']}`; signed tag object: `{source['tag_oid']}`.\n\n"
        f"Image: `{REGISTRY}@{published['digest']}`.\n\n"
        f"Candidate artifact: `{envelope['artifact']['id']}`; manifest SHA-256: `{envelope['manifest_sha256']}`.\n\n"
        'See manifest.json for the separate source, CI attempt and trusted-main workflow identities.\n')


def confirm_release(directory, selected, api):
    source = selected['source']
    record = obj(api.get('/releases/tags/' + source['tag']))
    need(record.get('tag_name') == source['tag'] and record.get('target_commitish') == source['sha']
         and record.get('draft') is False and record.get('prerelease') is False, 'release publication not confirmed')
    expected = {name: image.file_identity(directory / name, limit) for name, limit in packet_files(source).items()
                if name != 'image.tar.gz'}
    assets = record.get('assets')
    need(type(assets) is list and len(assets) == len(expected) and {obj(x).get('name') for x in assets} == set(expected),
         'release asset inventory mismatch')
    for asset in assets:
        target = expected[asset['name']]
        need(asset.get('state') == 'uploaded' and asset.get('size') == target['bytes']
             and asset.get('digest') == 'sha256:' + target['sha256'], 'release asset bytes not confirmed')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('deploy', 'select', 'prepare-build', 'seal', 'freeze', 'load',
                                         'recheck', 'publish-image', 'prepare-release', 'confirm-release'))
    action = parser.parse_args().action
    directory, ctx = image.owned_directory(), image.context()
    if action in ('prepare-build', 'seal'):
        selected, _ = selected_source()
        {'prepare-build': prepare_build, 'seal': seal}[action](directory, selected)
        return
    api = Api(os.environ.get('GITHUB_TOKEN'), os.environ.get('BACKEND_POLICY_READ_TOKEN'))
    if action == 'deploy':
        policy = trusted_context(api, ctx, False)
        fingerprint = image.digest(image.canonical(policy))
        expected = os.environ.get('EXPECTED_POLICY')
        need(expected is None or image.hex_value(expected) == fingerprint, 'deployment authorization policy/source changed')
        image.output('policy', fingerprint)
        return
    image.workflow_context(ctx, RELEASE, 'workflow_dispatch')
    need(ctx['attempt'] == 1, 'release reruns are refused')
    if action == 'select':
        selected = selection(api, ctx, directory)
        release_absent(api, selected['source'])
        registry = Registry(api.token)
        for tag in registry_tags(selected['source']):
            registry.manifest(tag, absent=True)
        image.output('selection', image.canonical(selected).decode())
        for key in ('sha', 'tag', 'version'):
            image.output(key, selected['source'][key])
        return
    selected, source = selected_source()
    checked_at = time.time()  # Age starts BEFORE network reads, never after archive work.
    if action in ('freeze', 'recheck'):
        need(selection(api, ctx, directory) == selected, 'frozen source/tag/CI/native policy/signer trust changed')
    if action == 'freeze':
        artifact = artifact_metadata(api, ctx)
        need(artifact['id'] == image.number_input(os.environ.get('BUILT_ARTIFACT_ID', ''))
             and artifact['digest'] == 'sha256:' + image.hex_value(os.environ.get('BUILT_ZIP_SHA256')), 'builder artifact substitution')
        api.download(artifact, directory / 'artifact.zip')
        unpack_packet(directory / 'artifact.zip', directory, source)
        manifest = validate_packet(directory, selected)
        envelope = {'artifact': artifact, 'manifest_sha256': image.digest(image.file_bytes(directory / 'manifest.json', MAX_RECORD))}
        need(artifact_metadata(api, ctx, artifact) == artifact, 'artifact changed during verification')
        image.output('envelope', image.canonical(envelope).decode())
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write('### Release candidate — NOT approval/publication proof\n```json\n' + image.canonical({
                'selection': selected, **envelope, 'image': manifest['image'], 'files': manifest['files']}).decode() + '\n```\n')
        return
    envelope = from_env('EXPECTED_ENVELOPE')
    if action in ('load', 'recheck'):
        artifact_metadata(api, ctx, obj(envelope.get('artifact')))
    if action == 'load':
        api.download(envelope['artifact'], directory / 'artifact.zip')
        unpack_packet(directory / 'artifact.zip', directory, source)
        manifest = validate_packet(directory, selected, envelope['manifest_sha256'])
        image.command(['docker', 'load', '--input', str(directory / 'image.tar.gz')], seconds=180)
        image.inspect_image('kira-backend:' + source['sha'], manifest['image']['id'])
    elif action == 'recheck':
        validate_packet(directory, selected, envelope['manifest_sha256'])
        (directory / 'authorization-check.json').write_bytes(image.canonical({
            'envelope_sha256': image.digest(image.canonical(envelope)), 'checked_at': checked_at}))
    elif action == 'publish-image':
        publish_image(directory, selected, envelope, api)
    elif action == 'prepare-release':
        prepare_release(directory, selected, envelope, api)
    elif action == 'confirm-release':
        confirm_release(directory, selected, api)


if __name__ == '__main__':
    try:
        main()
    except (Refused, OSError, ValueError, KeyError, TypeError, AttributeError, EOFError, zipfile.BadZipFile, tarfile.TarError) as error:
        print('REFUSED: ' + (str(error) if isinstance(error, Refused) else 'invalid or unavailable release evidence'),
              file=image.sys.stderr)
        raise SystemExit(1)
