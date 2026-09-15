#!/usr/bin/env bash
set -euo pipefail

# Private, disposable-fixture companion to container-smoke.sh, not a production repair tool.
# No URL, database, credentials or alternative policy may be supplied by a release receipt.
if [[ $# -ne 3 ]]; then
  echo "usage: $0 initialize|recover LOOPBACK_PORT PRIVATE_SMOKE_DIRECTORY" >&2
  exit 64
fi
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
exec python3 - "$@" "$repository" <<'PY'
import base64
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
from urllib.parse import quote

MAX_BODY = 5 * 1024 * 1024
ADMIN = '/api/v1/admin'
SOURCES = ADMIN + '/sources'
CUTOVER = ADMIN + '/source-catalog-v2/cutover'
DOCUMENT = '/api/v1/source-config/document'
MANIFEST = '/api/v2/source-config/manifest'
EMAIL = 'release-smoke@kira.invalid'
CONFIRMATION = {'X-Kira-Bootstrap-Confirmation': 'WITHHOLD_33_LEGACY_SOURCES'}
RECEIPT_FIELDS = set('policyId referenceSha256 payloadSha256 documentRevision documentChecksum '
                     'catalogRevision catalogChecksum completedAt actorId'.split())


class ProbeFailure(Exception):
    pass


def need(condition, message):
    if not condition:
        raise ProbeFailure(message)  # Only fixed diagnostics, never requests, bodies or tokens.


def alarm(_signal, _frame):
    raise ProbeFailure('phase deadline exceeded')


def decode(data):
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, 'duplicate JSON field')
            result[key] = value
        return result

    def constant(_value):
        raise ProbeFailure('nonfinite JSON value')

    return json.loads(data, object_pairs_hook=pairs, parse_constant=constant)


def read(path, maximum=MAX_BODY):
    info = path.lstat()
    need(stat.S_ISREG(info.st_mode) and info.st_size <= maximum, 'invalid fixture file')
    with path.open('rb') as stream:
        data = stream.read(maximum + 1)
    need(len(data) <= maximum, 'fixture file limit')
    return data


def save(name, data):
    descriptor = os.open(state / name, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write(data)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def positive(value):
    need(type(value) is int and 0 < value <= 2**63 - 1, 'invalid publication revision')
    return value


def request(method, path, expected=200, *, value=None, raw=None, authenticated=False, bearer=None, headers=None):
    need(path.startswith('/api/') and not path.startswith('//'), 'invalid probe route')
    fields = dict(headers or {})
    if authenticated:
        bearer = token
    if bearer is not None:
        fields['Authorization'] = 'Bearer ' + bearer
    if value is not None:
        raw = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()
    if raw is not None:
        need(len(raw) <= MAX_BODY, 'request byte limit')
        fields['Content-Type'] = 'application/json'
    # Direct loopback only: no environment proxies, redirects or credential forwarding.
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    try:
        connection.request(method, path, body=raw, headers=fields)
        response = connection.getresponse()
        need(response.status == expected, 'unexpected HTTP status')
        body = response.read(MAX_BODY + 1)
        need(len(body) <= MAX_BODY, 'response byte limit')
        result = {}
        for key, value in response.getheaders():
            key = key.lower()
            if key == 'etag' or key.startswith(('x-config-', 'x-source-')):
                need(key not in result, 'duplicate artifact header')
                result[key] = value
        return body, result
    finally:
        connection.close()


def api(method, path, **arguments):
    return decode(request(method, path, authenticated=True, **arguments)[0])


def live_claims(bearer):
    claims = decode(base64.urlsafe_b64decode(bearer.split('.')[1] + '==='))
    # A 401 from an already-expired JWT would not demonstrate credential-generation revocation.
    need(type(claims['exp']) is int and claims['exp'] > time.time() + 5, 'fixture token is too near expiry')
    return claims


def login():
    value = decode(request('POST', '/api/v1/auth/login', value={'email': EMAIL, 'password': password})[0])
    need(value['role'] == 'ADMIN' and value['tokenType'] == 'Bearer', 'fixture admin login failed')
    bearer = value['accessToken']
    need(type(bearer) is str and len(bearer) <= 8192, 'invalid fixture token')
    save('current-token', bearer.encode())
    claims = live_claims(bearer)
    version = claims['credential_version']
    need(type(version) is str and re.fullmatch(r'0|[1-9][0-9]{0,18}', version)
         and int(version) <= 2**63 - 1, 'credential version is not canonical')
    return bearer, int(version)


def verify(payload, signature):
    signature = base64.b64decode(signature, validate=True)
    need(len(signature) == 64, 'invalid Ed25519 signature length')
    save('verify.payload', payload)
    save('verify.signature', signature)
    result = subprocess.run(['openssl', 'pkeyutl', '-verify', '-pubin', '-keyform', 'DER',
                             '-inkey', str(state / 'verify.public.der'), '-rawin',
                             '-in', str(state / 'verify.payload'), '-sigfile', str(state / 'verify.signature')],
                            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, timeout=10, check=False)
    need(result.returncode == 0, 'detached signature verification failed')


def conditional(path, etag, authenticated=False):
    body, headers = request('GET', path, 304, authenticated=authenticated, headers={'If-None-Match': etag})
    need(body == b'' and headers['etag'] == etag, 'conditional artifact read failed')


def envelope(path, signature_format, revision_key, authenticated=False):
    body, headers = request('GET', path, authenticated=authenticated)
    value = decode(body)
    revision = positive(value[revision_key])
    checksum = digest(body)
    need(headers['x-config-revision'] == str(revision) and headers['x-config-checksum'] == checksum
         and headers['etag'] == '"' + checksum + '"', 'publication byte identity mismatch')
    need(headers['x-config-signature-format'] == signature_format
         and headers['x-config-signature-algorithm'] == 'Ed25519'
         and headers['x-config-signing-key-id'] == 'smoke', 'publication signing profile mismatch')
    previous = headers.get('x-config-previous-revision')
    previous_checksum = headers.get('x-config-previous-checksum')
    need((previous is None) == (previous_checksum is None), 'incomplete publication ancestry')
    if previous is not None:
        need(str(positive(int(previous))) == previous and int(previous) < revision
             and re.fullmatch('[0-9a-f]{64}', previous_checksum), 'invalid publication ancestry')
    created = headers['x-config-created-at']
    need(value['generatedAt'] == created and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', created),
         'publication instant mismatch')
    metadata = '\n'.join((signature_format, str(revision), previous or '0', previous_checksum or '-', checksum, created)) + '\n'
    verify(metadata.encode() + body, headers['x-config-signature'])
    conditional(path, headers['etag'], authenticated)
    return {'body': body.decode(), 'headers': headers}


def source_path(api_name, revision):
    return '/api/v2/source-config/sources/' + quote(api_name, safe='') + '/revisions/' + str(revision)


def source_artifact(entry):
    path = source_path(entry['api'], positive(entry['sourceRevision']))
    body, headers = request('GET', path)
    checksum = digest(body)
    value = decode(body)
    need(value['api'] == entry['api'] and value['engine'] == 'generic' and 'lifecycle' not in value,
         'source artifact is not lifecycle neutral')
    need(checksum == entry['checksum'] and headers['x-source-checksum'] == checksum
         and headers['x-source-api'] == entry['api'] and headers['x-source-revision'] == str(entry['sourceRevision'])
         and headers['x-source-canon-version'] == 'kcj-1' and headers['etag'] == '"' + checksum + '"',
         'source artifact byte identity mismatch')
    need(entry['sourceSigningKeyId'] == 'smoke', 'source signing key mismatch')
    metadata = '\n'.join(('kira-source-revision-v1', entry['api'], str(entry['sourceRevision']), checksum)) + '\n'
    verify(metadata.encode() + body, entry['sourceSignature'])
    conditional(path, headers['etag'])
    result = {'body': body.decode(), 'headers': headers, 'entry': entry}
    # Lifecycle/order can evolve; the immutable tuple's bytes and original detached signature cannot.
    if path in archive:
        need(archive[path]['body'] == result['body'] and archive[path]['headers'] == result['headers']
             and archive[path]['entry']['sourceSignature'] == entry['sourceSignature'], 'immutable source changed')
    else:
        archive[path] = result


def snapshot():
    document = envelope(DOCUMENT, 'kira-source-signature-v1', 'revision')
    manifest = envelope(MANIFEST, 'kira-source-catalog-manifest-v1', 'catalogRevision')
    doc, catalog = decode(document['body']), decode(manifest['body'])
    need(doc['schemaVersion'] == catalog['schemaVersion'] == catalog['sourceSchemaVersion'] == 1
         and doc['revision'] == catalog['catalogRevision'] and doc['generatedAt'] == catalog['generatedAt'],
         'v1/v2 publication generation mismatch')
    heads = api('GET', SOURCES)
    need(type(heads) is list and len(heads) == len(expected), 'admin inventory mismatch')
    need([head['api'] for head in heads] == list(expected), 'admin inventory/order changed')
    heads_by_api = {head['api']: head for head in heads}
    for head in heads:
        lifecycle, engine = expected[head['api']]
        need(head['status'] == lifecycle and head['engine'] == engine, 'admin lifecycle/engine mismatch')
    public = [name for name, (lifecycle, engine) in expected.items()
              if lifecycle in ('active', 'disabled', 'retired') and engine == 'generic']
    need([entry['api'] for entry in catalog['sources']] == public
         and [source['api'] for source in doc['sources']] == public, 'public exclusion/order mismatch')
    summaries = decode(request('GET', '/api/v1/sources')[0])
    need([source['api'] for source in summaries] == public, 'public summary exclusion mismatch')
    for order, (entry, source) in enumerate(zip(catalog['sources'], doc['sources'])):
        lifecycle = expected[entry['api']][0]
        need(entry['lifecycle'] == lifecycle and entry['engine'] == source['engine'] == 'generic'
             and entry['sourceRevision'] == heads_by_api[entry['api']]['currentPublishedRevisionNumber']
             and entry['order'] == order and source.get('lifecycle', 'active') ==
             ('removed' if lifecycle == 'retired' else lifecycle), 'public lifecycle mismatch')
        source_artifact(entry)
    removed = [name for name, (lifecycle, _engine) in expected.items() if lifecycle == 'removed']
    tombstones = catalog.get('removedSources', [])
    need([item['api'] for item in tombstones] == sorted(removed)
         and all(set(item) <= {'api', 'lifecycle'} and item.get('lifecycle', 'removed') == 'removed'
                 for item in tombstones), 'catalog tombstone mismatch')
    history = api('GET', ADMIN + '/documents?size=100')
    need(type(history) is list and 0 < len(history) < 100, 'unbounded fixture publication history')
    return {'document': document, 'manifest': manifest, 'heads': heads, 'history': history}


def advanced(before, after):
    for name in ('document', 'manifest'):
        old, new = before[name]['headers'], after[name]['headers']
        need(int(new['x-config-revision']) > int(old['x-config-revision'])
             and new['x-config-previous-revision'] == old['x-config-revision']
             and new['x-config-previous-checksum'] == old['x-config-checksum'], 'publication did not advance coherently')


def replay(origin, current):
    need(api('POST', CUTOVER + '/import-bundled', raw=payload, headers=CONFIRMATION) == origin,
         'origin receipt replay changed')
    request('POST', CUTOVER + '/import-bundled', 409, authenticated=True, raw=payload + b'\n', headers=CONFIRMATION)
    advisory = api('GET', CUTOVER)
    need(advisory['phase'] == 'COMPLETE' and advisory['ready'] is True and advisory['receipt'] == origin,
         'durable completion receipt mismatch')
    need(snapshot() == current, 'origin replay rewound or mutated visible state')
    historical = envelope(ADMIN + '/documents/' + str(origin['documentRevision']),
                          'kira-source-signature-v1', 'revision', authenticated=True)
    need(historical == origin_document, 'immutable origin document changed')
    for item in list(archive.values()):
        source_artifact(item['entry'])


try:
    phase, port_text, directory, repository = sys.argv[1:]
    need(phase in ('initialize', 'recover') and re.fullmatch('[1-9][0-9]{0,4}', port_text)
         and int(port_text) <= 65535, 'invalid private probe invocation')
    port, root = int(port_text), Path(directory)
    state = root / 'state'
    for path in (root, state):
        info = path.lstat()
        need(stat.S_ISDIR(info.st_mode) and info.st_uid == os.geteuid() and info.st_mode & 0o077 == 0,
             'probe requires an owned private temporary directory')
    signal.signal(signal.SIGALRM, alarm)
    signal.alarm(120)
    password = read(state / 'admin-password', 72).decode()
    save('verify.public.der', base64.b64decode(read(root / 'signing/smoke.public.b64', 1024), validate=True))
    token, version = login()
    actor = api('GET', '/api/v1/auth/me')
    need(actor['email'] == EMAIL and actor['role'] == 'ADMIN', 'fixture principal mismatch')
    if phase == 'initialize':
        need(version == 0, 'disposable fixture is not fresh')
        repository = Path(repository)
        historical = decode(read(repository / 'src/test/resources/fixtures/bundled-full.json'))
        reference_bytes = read(repository / 'src/main/resources/source-config/bootstrap/app-bundle-v6-generic.json')
        reference = decode(reference_bytes)
        # Keep the original 45 models. The real bootstrap parser/policy checks default-expanded
        # generic equality; Python must not silently replace or "repair" the historical models.
        document = dict(reference, sources=historical['sources'])
        payload = json.dumps(document, ensure_ascii=False, separators=(',', ':')).encode()
        save('bootstrap.json', payload)  # Original request bytes, reused verbatim after restart.
        expected = {source['api']: ['active' if source.get('engine', 'legacy') == 'generic' else 'withheld',
                                    source.get('engine', 'legacy')] for source in document['sources']}
        need(len(expected) == 45 and sum(value[0] == 'withheld' for value in expected.values()) == 33,
             'bootstrap fixture inventory drift')
        advisory = api('GET', CUTOVER)
        need(advisory['phase'] == 'PENDING' and advisory['ready'] is True and advisory.get('receipt') is None
             and api('GET', SOURCES) == [], 'fresh catalog is not pending')
        request('POST', SOURCES + '/import-bundled', 409, authenticated=True, raw=payload)
        request('POST', ADMIN + '/documents/republish', 409, authenticated=True)
        request('POST', CUTOVER, 409, authenticated=True, value={'confirmation': CONFIRMATION['X-Kira-Bootstrap-Confirmation']})
        need(api('GET', SOURCES) == [] and api('GET', ADMIN + '/documents?size=100') == [], 'pending gate mutated catalog')
        origin = api('POST', CUTOVER + '/import-bundled', raw=payload, headers=CONFIRMATION)
        need(set(origin) == RECEIPT_FIELDS and origin['policyId'] == 'app-bundle-v6-initial-catalog-v1'
             and origin['referenceSha256'] == digest(reference_bytes) and origin['payloadSha256'] == digest(payload)
             and positive(origin['documentRevision']) > reference['revision']
             and origin['actorId'] == actor['id'], 'bootstrap origin receipt mismatch')
        archive = {}
        current = snapshot()
        need(len(current['history']) == 1 and all(head['currentPublishedRevisionNumber'] == 1
             and head['latestRevisionNumber'] == 1 for head in current['heads']), 'bootstrap wrote unexpected history')
        origin_document = current['document']
        for name, prefix in (('document', 'document'), ('manifest', 'catalog')):
            headers = current[name]['headers']
            need(origin[prefix + 'Revision'] == int(headers['x-config-revision'])
                 and origin[prefix + 'Checksum'] == headers['x-config-checksum']
                 and origin['completedAt'] == headers['x-config-created-at']
                 and 'x-config-previous-revision' not in headers, 'bootstrap receipt/artifact disagreement')
        imported = api('POST', SOURCES + '/import-bundled', raw=payload)
        need(set(imported['unchanged']) == set(expected) and len(imported['unchanged']) == 45
             and all(imported[name] == [] for name in ('created', 'updated', 'reordered', 'skippedRemoved', 'skippedRetired', 'skippedDraft'))
             and imported.get('documentRevision') is None, 'ordinary no-op import mutated bootstrap')
        need(snapshot() == current, 'ordinary import changed withheld state')
        request('GET', '/api/v1/sources/Lavatoons', 404)
        request('GET', source_path('Lavatoons', 1), 404)
        need(api('GET', SOURCES + '/Lavatoons')['status'] == 'withheld', 'withheld admin read failed')
        request('POST', SOURCES + '/Lavatoons/enable', 409, authenticated=True)

        # A generic draft cannot be fetched merely by guessing its tuple. Publishing while
        # WITHHELD still excludes it; only the separate engine-gated enable admits membership.
        converted = copy.deepcopy(reference['sources'][0])
        converted.update(api='Lavatoons', displayName='Release smoke generic conversion')
        revision = positive(api('POST', SOURCES + '/Lavatoons/revisions', expected=201, value=converted)['revisionNumber'])
        need(revision > 1, 'source revision did not advance')
        request('GET', source_path('Lavatoons', revision), 404)
        api('POST', SOURCES + '/Lavatoons/revisions/' + str(revision) + '/publish')
        expected['Lavatoons'] = ['withheld', 'generic']
        after = snapshot()
        advanced(current, after)
        current = after
        request('GET', source_path('Lavatoons', revision), 404)
        request('GET', '/api/v1/sources/Lavatoons', 404)
        api('POST', SOURCES + '/Lavatoons/enable')
        expected['Lavatoons'][0] = 'active'
        after = snapshot()
        advanced(current, after)
        current = after
        need(api('GET', SOURCES + '/Lavatoons')['currentPublishedRevisionNumber'] == revision,
             'explicit enable selected the wrong source revision')
        request('GET', source_path('Lavatoons', 1), 404)  # Never a public member, even after conversion.
        request('GET', source_path('Lavatoons', revision + 1000), 404)

        retired = reference['sources'][0]['api']
        for action, lifecycle in (('disable', 'disabled'), ('retire', 'retired'), ('remove', 'removed')):
            api('POST', SOURCES + '/' + quote(retired, safe='') + '/' + action,
                value={'confirm': retired} if action == 'remove' else None)
            expected[retired][0] = lifecycle
            after = snapshot()
            advanced(current, after)
            current = after
        replay(origin, current)

        live_claims(token)
        need(api('GET', '/api/v1/auth/me') == actor, 'pre-reset fixture token is not active')
        save('revoked-token', token.encode())
        request('POST', ADMIN + '/users/' + actor['id'] + '/reset-password', authenticated=True,
                value={'newPassword': password})
        request('GET', '/api/v1/auth/me', 401, bearer=token)
        token, new_version = login()
        need(new_version == version + 1 and api('GET', '/api/v1/auth/me') == actor, 'password reset did not advance credential generation')
        save('checkpoint.json', json.dumps({'origin': origin, 'origin_document': origin_document, 'current': current,
                                          'expected': expected, 'archive': archive, 'actor': actor,
                                          'credential_version': new_version}, ensure_ascii=False).encode())
    else:
        checkpoint = decode(read(state / 'checkpoint.json'))
        expected, archive = checkpoint['expected'], checkpoint['archive']
        origin, origin_document = checkpoint['origin'], checkpoint['origin_document']
        payload = read(state / 'bootstrap.json')
        need(version == checkpoint['credential_version'] and actor == checkpoint['actor'], 'credential generation did not survive restart')
        revoked = read(state / 'revoked-token', 8192).decode()
        live_claims(revoked)
        request('GET', '/api/v1/auth/me', 401, bearer=revoked)
        current = snapshot()
        need(current == checkpoint['current'], 'state did not survive exact-image seed-disabled restart')
        replay(origin, current)
        # The restarted image must also be a compatible writer, not just a healthy reader.
        api('POST', ADMIN + '/documents/republish')
        after = snapshot()
        advanced(current, after)
        replay(origin, after)
    signal.alarm(0)
    print('source-catalog and credential state probe passed: ' + phase)
except ProbeFailure as error:
    print('source-catalog and credential state probe failed: ' + str(error), file=sys.stderr)
    sys.exit(1)
except Exception:
    # This boundary intentionally suppresses exception text/tracebacks (which may contain HTTP
    # bodies, filesystem context or credentials). The enclosing smoke prints its fixed stage.
    print('source-catalog and credential state probe failed', file=sys.stderr)
    sys.exit(1)
PY
