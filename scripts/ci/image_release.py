#!/usr/bin/env python3
"""Finite Backend CI promotion packet; also installed as the root archive checker.

No registry, build fallback, candidate execution in the consumer, or sibling imports.
The small bounded I/O / GitHub redirect primitives follow the reviewed Admin utility.
"""

import argparse
import base64
import contextlib
import datetime as dt
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPOSITORY = 'kira-manga/Kira-backend'
REPOSITORY_ID = 1304735394
CI = '.github/workflows/ci.yml'
DEPLOY = '.github/workflows/deploy-server3.yml'
SMOKE = 'scripts/smoke/container-smoke.sh'
CONTRACT = (CI, 'scripts/ci/image_release.py', SMOKE)
ROOT = Path(__file__).resolve().parents[2]
MAX_IMAGE = 512 * 1024 * 1024
MAX_TAR = 2 * 1024 * 1024 * 1024
MAX_RECEIPT = 16 * 1024
MAX_MANIFEST = 64 * 1024
MAX_CONFIG = 1024 * 1024
MAX_API = 4 * 1024 * 1024
FILES = {'image.tar.gz': MAX_IMAGE, 'receipt.json': MAX_RECEIPT}
POLICY = 'backend-production-profile-v1'


class Refused(Exception):
    """Only fixed diagnostics, never API bodies, env values or signed URLs."""


def need(condition, message):
    if not condition:
        raise Refused(message)


def integer(value):
    need(type(value) is int and 0 < value <= 2**53 - 1, 'invalid numeric identity')
    return value


def number_input(value):
    need(isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,15}', value), 'invalid numeric input')
    return integer(int(value))


def hex_value(value, length=64):
    need(isinstance(value, str) and re.fullmatch('[0-9a-f]{' + str(length) + '}', value), 'invalid digest')
    return value


def image_id(value):
    need(isinstance(value, str) and value.startswith('sha256:'), 'invalid image ID')
    hex_value(value[7:])
    return value


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def parse_json(data, maximum):
    need(len(data) <= maximum, 'JSON byte limit')

    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, 'duplicate JSON field')
            result[key] = value
        return result

    def constant(_):
        raise Refused('invalid JSON number')

    def finite(value):
        number = float(value)
        need(math.isfinite(number), 'nonfinite JSON number')
        return number

    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=constant, parse_float=finite)
    except (ValueError, UnicodeError, RecursionError):
        raise Refused('invalid JSON') from None


def keys(value, required, optional=''):
    need(type(value) is dict and set(required.split()) <= set(value)
         and set(value) <= set((required + ' ' + optional).split()), 'unexpected object fields')


def file_bytes(path, maximum):
    need(stat.S_ISREG(path.lstat().st_mode) and path.stat().st_size <= maximum, 'nonregular or oversized file')
    with path.open('rb') as stream:
        data = stream.read(maximum + 1)
    need(len(data) <= maximum, 'file byte limit')
    return data


def copy_bounded(source, target, maximum):
    size, checksum = 0, hashlib.sha256()
    while chunk := source.read(min(65536, maximum - size + 1)):
        size += len(chunk)
        need(size <= maximum, 'stream byte limit')
        checksum.update(chunk)
        if target is not None:
            target.write(chunk)
    return {'bytes': size, 'sha256': checksum.hexdigest()}


def file_identity(path, maximum):
    need(stat.S_ISREG(path.lstat().st_mode), 'nonregular archive')
    with path.open('rb') as stream:
        return copy_bounded(stream, None, maximum)


@contextlib.contextmanager
def deadline(seconds):
    def expired(_signal, _frame):
        raise Refused('operation deadline exceeded')
    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def command(argv, *, seconds=60, output=None, maximum=MAX_CONFIG, stdin=None, return_status=False):
    # No shell; secrets are not inherited. Bound total time, bytes and owned child cleanup.
    # Only transfer opts into exit statuses; other callers still require success.
    need(hasattr(os, 'waitid') and hasattr(os, 'WNOWAIT'), 'non-reaping child observation unavailable')
    env = {k: os.environ[k] for k in ('PATH', 'HOME', 'TMPDIR', 'LANG') if k in os.environ}
    process = subprocess.Popen(argv, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               env=env, start_new_session=True)
    end, count, result = time.monotonic() + seconds, 0, bytearray()
    owned = True

    def exited():
        nonlocal owned
        need(owned, 'owned command ownership lost')
        try:
            observed = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        except OSError:
            owned = False
            raise Refused('owned command ownership lost') from None
        if observed is not None and (observed.si_pid != process.pid or
                observed.si_code not in (os.CLD_EXITED, os.CLD_KILLED, os.CLD_DUMPED)):
            owned = False
            raise Refused('owned command ownership lost')
        return observed is not None

    def wait_exit(until):
        while not exited():
            remaining = until - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(0.01, remaining))
        return True

    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                remaining = end - time.monotonic()
                need(remaining > 0, 'command deadline exceeded')
                ready = selector.select(remaining)
                need(ready, 'command deadline exceeded')
                for key, _ in ready:
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    count += len(chunk)
                    need(count <= maximum, 'command output byte limit')
                    if output is None:
                        result.extend(chunk)
                    else:
                        output.write(chunk)
        need(wait_exit(max(end, time.monotonic() + 0.1)), 'command deadline exceeded')
    finally:
        try:
            # Single wait owner: WNOWAIT pins the leader's PID through BOTH signals.
            # Keep the original total four-second cleanup allowance, including absence.
            cleanup_end, signal_failed = time.monotonic() + 4, False
            term_end = min(cleanup_end, time.monotonic() + 2)
            for sig in (signal.SIGTERM, signal.SIGKILL):
                exited()  # Ownership loss forbids any subsequent numeric group signal.
                try:
                    os.killpg(process.pid, sig)
                except ProcessLookupError:
                    pass
                except OSError:
                    signal_failed = True
                observed_exit = wait_exit(term_end if sig == signal.SIGTERM else cleanup_end)
                if sig == signal.SIGKILL:
                    need(observed_exit, 'owned command cleanup failed')
            # One actual reap, after the final mutating signal. Popen.wait() would
            # hide ECHILD; require our own waitable child and set its real exit status.
            try:
                waited, status = os.waitpid(process.pid, os.WNOHANG)
            except OSError:
                owned = False
                raise Refused('owned command ownership lost') from None
            owned = False
            need(waited == process.pid, 'owned command cleanup failed')
            process.returncode = os.waitstatus_to_exitcode(status)
            # Read-only probes only after reap: reuse can refuse, never signal others.
            # This joins the local group, not escaped sessions or remote/daemon work.
            while True:
                need(time.monotonic() < cleanup_end, 'owned command cleanup failed')
                try:
                    os.killpg(process.pid, 0)
                except ProcessLookupError:
                    break
                remaining = cleanup_end - time.monotonic()
                need(remaining > 0, 'owned command cleanup failed')
                time.sleep(min(0.01, remaining))
            need(not signal_failed, 'owned command cleanup failed')
        except OSError:
            raise Refused('owned command cleanup failed') from None
        finally:
            process.stdout.close()
    need(return_status or process.returncode == 0, 'command failed')
    return process.returncode if return_status else bytes(result)


def docker_tar(path, tag, backend):
    """Single-image Docker-save profile, not a general OCI importer or layer extractor.

    Scan ordinary tar headers ourselves before reading any metadata: tarfile's PAX/
    long-name processing otherwise allocates hidden, unbounded metadata first.
    Layer filesystem links are opaque bytes; only OUTER links are forbidden.
    """
    size = path.stat().st_size
    need(1024 <= size <= MAX_TAR and size % 512 == 0, 'invalid Docker tar size')
    entries, directories = {}, set()
    with path.open('rb') as stream:
        while True:
            block = stream.read(512)
            need(len(block) == 512, 'truncated Docker tar')
            if block == bytes(512):
                need(stream.read(512) == bytes(512), 'missing tar EOF')
                while tail := stream.read(65536):
                    need(not any(tail), 'data after tar EOF')
                break
            member = tarfile.TarInfo.frombuf(block, 'utf-8', 'strict')
            name = member.name.removeprefix('./').rstrip('/') if member.isdir() else member.name.removeprefix('./')
            need(len(entries) + len(directories) < 4096 and len(name) <= 200
                 and re.fullmatch(r'[A-Za-z0-9_.\-/]+', name)
                 and all(part not in ('', '.', '..') for part in name.split('/'))
                 and name not in entries and name not in directories
                 and member.type in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE)
                 and not member.linkname and not member.pax_headers
                 and 0 <= member.size <= MAX_TAR, 'unsupported Docker tar member')
            if member.isdir():
                need(member.size == 0, 'nonempty tar directory')
                directories.add(name)
                continue
            offset, remaining, checksum = stream.tell(), member.size, hashlib.sha256()
            while remaining:
                chunk = stream.read(min(65536, remaining))
                need(chunk, 'truncated tar member')
                remaining -= len(chunk)
                checksum.update(chunk)
            padding = (-member.size) % 512
            need(stream.read(padding) == bytes(padding), 'invalid tar padding')
            entries[name] = (offset, member.size, checksum.hexdigest())

        def raw(name, limit):
            need(name in entries and entries[name][1] <= limit, 'missing or oversized image metadata')
            stream.seek(entries[name][0])
            data = stream.read(entries[name][1])
            need(len(data) == entries[name][1], 'truncated metadata')
            return data

        def document(name, limit=MAX_MANIFEST):
            return parse_json(raw(name, limit), limit)

        manifest = document('manifest.json')
        need(type(manifest) is list and len(manifest) == 1, 'archive must contain exactly one image')
        item = manifest[0]
        keys(item, 'Config RepoTags Layers', 'LayerSources Parent')
        need(item['RepoTags'] == [tag] and not item.get('Parent'), 'wrong or additional image tag/parent')
        config_name, layers = item['Config'], item['Layers']
        need(isinstance(config_name, str) and type(layers) is list and 0 < len(layers) <= 512
             and all(isinstance(x, str) for x in layers) and len(set(layers)) == len(layers), 'invalid image paths')
        config_raw = raw(config_name, MAX_CONFIG)
        identity = 'sha256:' + digest(config_raw)
        need(config_name in (identity[7:] + '.json', 'blobs/sha256/' + identity[7:]), 'config path/ID mismatch')
        config = parse_json(config_raw, MAX_CONFIG)
        need(type(config) is dict and type(config.get('config')) is dict, 'invalid image config')
        rootfs = config.get('rootfs')
        keys(rootfs, 'type diff_ids')
        need(rootfs['type'] == 'layers' and type(rootfs['diff_ids']) is list
             and len(rootfs['diff_ids']) == len(layers), 'invalid image layer chain')
        for layer, expected in zip(layers, rootfs['diff_ids']):
            image_id(expected)
            need(layer in entries and entries[layer][2] == expected[7:]
                 and (layer == 'blobs/sha256/' + expected[7:]
                      or re.fullmatch(r'[0-9a-f]{64}/layer.tar', layer)), 'layer bytes/DiffID mismatch')
        # This supported Docker-save profile carries uncompressed layer tars. Do
        # not accept a compressed/foreign layer via an unverified alternative index.
        platform = {'os': config.get('os'), 'architecture': config.get('architecture')}
        need(all(isinstance(x, str) and re.fullmatch(r'[a-z0-9_]{1,32}', x) for x in platform.values()),
             'invalid image platform')
        labels = config['config'].get('Labels') or {}
        need(type(labels) is dict, 'invalid image labels')
        revision = labels.get('org.opencontainers.image.revision')
        if backend:
            need(platform == {'os': 'linux', 'architecture': 'amd64'} and revision == tag.split(':')[1]
                 and re.fullmatch(r'[0-9]+:[0-9]+', config['config'].get('User', '')), 'backend image policy mismatch')

        used = {'manifest.json', config_name, *layers}

        def descriptor(value, target, media_types, annotated=False):
            keys(value, 'mediaType digest size', 'annotations platform' if annotated else '')
            need(target in entries and value['digest'] == 'sha256:' + entries[target][2]
                 and integer(value['size']) == entries[target][1] and value['mediaType'] in media_types,
                 'alternative image descriptor mismatch')
            if 'platform' in value:
                need(value['platform'] == platform, 'alternative platform mismatch')
            if 'annotations' in value:
                annotations = value['annotations']
                keys(annotations, '', 'io.containerd.image.name org.opencontainers.image.ref.name')
                for key, val in annotations.items():
                    accepted = (tag, 'docker.io/library/' + tag)
                    if key == 'org.opencontainers.image.ref.name':
                        accepted += (tag.split(':')[1],)
                    need(val in accepted, 'alternative tag mismatch')

        layer_types = ('application/vnd.oci.image.layer.v1.tar', 'application/vnd.docker.image.rootfs.diff.tar')
        sources = item.get('LayerSources') or {}
        need(type(sources) is dict and set(sources) <= set(rootfs['diff_ids']), 'unexpected layer sources')
        for layer, diff_id in zip(layers, rootfs['diff_ids']):
            if diff_id in sources:
                descriptor(sources[diff_id], layer, layer_types)
        if 'index.json' in entries or 'oci-layout' in entries:
            need(document('oci-layout') == {'imageLayoutVersion': '1.0.0'}, 'unsupported OCI layout')
            index = document('index.json')
            keys(index, 'schemaVersion manifests', 'mediaType')
            need(type(index['schemaVersion']) is int and index['schemaVersion'] == 2
                 and index.get('mediaType', 'application/vnd.oci.image.index.v1+json') == 'application/vnd.oci.image.index.v1+json'
                 and type(index['manifests']) is list and len(index['manifests']) == 1, 'additional OCI image subject')
            desc = index['manifests'][0]
            image_id(desc.get('digest'))
            oci_path = 'blobs/sha256/' + desc['digest'][7:]
            image_types = ('application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json')
            descriptor(desc, oci_path, image_types, annotated=True)
            oci = document(oci_path)
            keys(oci, 'schemaVersion config layers', 'mediaType')
            need(type(oci['schemaVersion']) is int and oci['schemaVersion'] == 2
                 and oci.get('mediaType', desc['mediaType']) == desc['mediaType']
                 and type(oci['layers']) is list and len(oci['layers']) == len(layers), 'inconsistent OCI image')
            descriptor(oci['config'], config_name,
                       ('application/vnd.oci.image.config.v1+json', 'application/vnd.docker.container.image.v1+json'))
            for value, layer in zip(oci['layers'], layers):
                descriptor(value, layer, layer_types)
            used.update(('index.json', 'oci-layout', oci_path))
        if 'repositories' in entries:
            repository, revision_tag = tag.split(':')
            last = layers[-1].split('/')[-1] if layers[-1].startswith('blobs/') else layers[-1].split('/')[0]
            need(document('repositories') == {repository: {revision_tag: last}}, 'alternative repository/tag mismatch')
            used.add('repositories')
        # Moby 28 also emits one inert V1 compatibility config per layer. They
        # have legacy id/parent fields, NOT rootfs or OCI subjects/indexes.
        extras = set(entries) - used
        need(len(extras) <= 2 * len(layers), 'too many compatibility records')
        legacy_ids = set()
        for name in extras:
            if name.endswith('/VERSION'):
                need(name[:-7] + 'layer.tar' in layers and raw(name, 4) in (b'1.0', b'1.0\n'), 'unknown compatibility version')
                continue
            value = document(name, MAX_CONFIG)
            need(type(value) is dict and 'rootfs' not in value and 'schemaVersion' not in value
                 and 'manifests' not in value and 'subject' not in value, 'additional image config/subject')
            legacy_id = hex_value(value.get('id'))
            need(legacy_id not in legacy_ids and value.get('os', platform['os']) == platform['os'], 'ambiguous compatibility config')
            legacy_ids.add(legacy_id)
            if value.get('parent'):
                hex_value(value['parent'])
            need(name == 'blobs/sha256/' + entries[name][2]
                 or (name == legacy_id + '/json' and legacy_id + '/layer.tar' in layers), 'unknown compatibility path')
        need(len(legacy_ids) <= len(layers)
             and all(any(name.startswith(directory + '/') for name in entries) for directory in directories),
             'unreferenced image directories/configs')
        return {'id': identity, 'tag': tag, **platform, 'revision': revision}


def validate_archive(archive, tag, *, backend=False, expected_id=None, expected_digest=None):
    with deadline(90):
        compressed = file_identity(archive, MAX_IMAGE)
        need(compressed['bytes'] > 0 and (expected_digest is None or compressed['sha256'] == hex_value(expected_digest)),
             'gzip stream digest mismatch')
        with tempfile.NamedTemporaryFile(prefix='expanded-', dir=archive.parent) as plain:
            with gzip.open(archive, 'rb') as stream:
                expanded = copy_bounded(stream, plain, MAX_TAR)
            plain.flush()
            image = docker_tar(Path(plain.name), tag, backend)
        need(expected_id is None or image['id'] == image_id(expected_id), 'archive image ID mismatch')
        return image, {**compressed, 'expanded_bytes': expanded['bytes'], 'expanded_sha256': expanded['sha256']}


def unpack_zip(archive, destination):
    # Bound the directory BEFORE ZipFile allocates it. ZIP64/comments are not needed.
    size = archive.stat().st_size
    need(22 <= size <= MAX_IMAGE, 'outer ZIP byte limit')
    with archive.open('rb') as stream:
        stream.seek(-22, 2)
        end = struct.unpack('<4s4H2LH', stream.read(22))
    need(end[:5] == (b'PK\x05\x06', 0, 0, 2, 2) and end[5] <= 65536
         and end[6] + end[5] == size - 22 and end[7] == 0, 'unsupported ZIP directory')
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        need(len(entries) == 2 and {x.filename for x in entries} == set(FILES), 'unexpected ZIP members')
        for item in entries:
            need(stat.S_ISREG(item.external_attr >> 16) and not item.is_dir() and not item.flag_bits & 1
                 and item.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                 and 0 < item.file_size <= FILES[item.filename], 'nonregular or oversized ZIP member')
            with bundle.open(item) as source, (destination / item.filename).open('xb') as target:
                actual = copy_bounded(source, target, FILES[item.filename])
            need(actual['bytes'] == item.file_size, 'ZIP member size mismatch')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, _request, _fp, _code, _message, _headers, _url):
        return None


OPENER = urllib.request.build_opener(NoRedirect())


def request(url, token=None):
    headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
               'User-Agent': 'kira-backend-exact-image'}
    if token:
        parsed = urllib.parse.urlsplit(url)
        need(parsed.scheme == 'https' and parsed.hostname == 'api.github.com' and parsed.port in (None, 443)
             and not parsed.username and not parsed.password, 'refusing token forwarding')
        headers['Authorization'] = 'Bearer ' + token
    return urllib.request.Request(url, headers=headers)


class GitHub:
    def __init__(self, token):
        need(isinstance(token, str) and bool(token), 'missing read-only GitHub token')
        self.token, self.sources = token, {}

    def get(self, suffix):
        need(suffix == '' or suffix.startswith('/'), 'invalid API path')
        with deadline(30), OPENER.open(request('https://api.github.com/repos/' + REPOSITORY + suffix,
                                               self.token), timeout=15) as response:
            need(response.status == 200 and 'rel="next"' not in response.headers.get('Link', ''), 'incomplete API response')
            return parse_json(response.read(MAX_API + 1), MAX_API)

    def download(self, identity, destination):
        url = f"https://api.github.com/repos/{REPOSITORY}/actions/artifacts/{integer(identity['id'])}/zip"
        with deadline(180):
            try:
                unexpected = OPENER.open(request(url, self.token), timeout=15)
            except urllib.error.HTTPError as response:
                try:
                    need(response.code == 302, 'artifact download unavailable')
                    location = response.headers.get('Location', '')
                finally:
                    response.close()
            else:
                unexpected.close()
                raise Refused('unexpected artifact download response')
            parsed = urllib.parse.urlsplit(location)
            host = parsed.hostname or ''
            need(parsed.scheme == 'https' and parsed.port in (None, 443) and not parsed.username
                 and not parsed.password and not parsed.fragment
                 and (host.endswith('.blob.core.windows.net') or host.endswith('.actions.githubusercontent.com')),
                 'unexpected artifact storage destination')
            # A new request WITHOUT Authorization; no second redirect or URL logging.
            with OPENER.open(request(location), timeout=15) as response, destination.open('xb') as target:
                need(response.status == 200, 'artifact storage refused download')
                actual = copy_bounded(response, target, MAX_IMAGE)
            need(actual == {'bytes': identity['size_in_bytes'], 'sha256': identity['digest'][7:]},
                 'outer ZIP digest or size mismatch')

    def source(self, sha):
        sha = hex_value(sha, 40)
        if sha not in self.sources:
            commit = self.get('/git/commits/' + sha)
            need(commit.get('sha') == sha, 'Git commit mismatch')
            tree = hex_value(commit['tree']['sha'], 40)
            listing = self.get('/git/trees/' + tree + '?recursive=1')
            need(listing.get('sha') == tree and listing.get('truncated') is False, 'incomplete Git tree')
            entries = {x['path']: x for x in listing['tree']}
            need(len(entries) == len(listing['tree']), 'ambiguous Git tree')
            hashes = {}
            for name in (*CONTRACT, DEPLOY):
                item = entries.get(name, {})
                need(item.get('type') == 'blob' and item.get('mode') in ('100644', '100755'), 'missing contract blob')
                blob = hex_value(item.get('sha'), 40)
                record = self.get('/git/blobs/' + blob)
                need(record.get('sha') == blob and record.get('encoding') == 'base64'
                     and integer(record.get('size')) <= MAX_CONFIG, 'invalid contract blob')
                raw = base64.b64decode(record['content'].replace('\n', ''), validate=True)
                need(len(raw) == record['size'] and hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == blob,
                     'Git blob content mismatch')
                hashes[name] = digest(raw)
            self.sources[sha] = (tree, hashes)
        return self.sources[sha]


def timestamp(value):
    need(isinstance(value, str) and len(value) <= 40, 'invalid timestamp')
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    need(result.tzinfo is not None, 'timestamp has no timezone')
    return result.timestamp()


def local_contract():
    return {name: digest(file_bytes(ROOT / name, MAX_CONFIG)) for name in (*CONTRACT, DEPLOY)}


def context():
    env = os.environ
    return {'sha': hex_value(env.get('GITHUB_SHA'), 40),
            'run_id': number_input(env.get('GITHUB_RUN_ID', '')),
            'attempt': number_input(env.get('GITHUB_RUN_ATTEMPT', '')),
            'repository': env.get('GITHUB_REPOSITORY'),
            'repository_id': number_input(env.get('GITHUB_REPOSITORY_ID', '')),
            'event': env.get('GITHUB_EVENT_NAME'), 'ref': env.get('GITHUB_REF'),
            'workflow_ref': env.get('GITHUB_WORKFLOW_REF'), 'workflow_sha': env.get('GITHUB_WORKFLOW_SHA')}


def workflow_context(ctx, path, event):
    need(ctx['repository'] == REPOSITORY and ctx['repository_id'] == REPOSITORY_ID
         and ctx['event'] == event and ctx['ref'] == 'refs/heads/main'
         and ctx['workflow_ref'] == f'{REPOSITORY}/{path}@refs/heads/main'
         and ctx['workflow_sha'] == ctx['sha'], 'wrong trusted workflow context')


def run_identity(run, run_id, attempt, workflow_id, path, event, sha, *, consumer=False):
    need(integer(run.get('id')) == run_id and integer(run.get('run_attempt')) == attempt
         and integer(run.get('workflow_id')) == workflow_id and run.get('path') == path
         and run.get('event') == event and run.get('head_branch') == 'main' and run.get('head_sha') == sha
         and run.get('status') == ('in_progress' if consumer else 'completed')
         and run.get('conclusion') == (None if consumer else 'success'), 'ineligible workflow run/attempt')
    for name in ('repository', 'head_repository'):
        need(integer(run.get(name, {}).get('id')) == REPOSITORY_ID
             and run[name].get('full_name') == REPOSITORY, 'wrong workflow repository')


def complete_list(value, key):
    items = value.get(key)
    need(type(items) is list and type(value.get('total_count')) is int
         and value['total_count'] == len(items) <= 100
         and len({integer(x.get('id')) for x in items}) == len(items), 'incomplete or ambiguous API list')
    return items


def artifact_identity(value):
    result = {key: value.get(key) for key in
              ('id', 'name', 'size_in_bytes', 'digest', 'expired', 'created_at', 'expires_at')}
    result['workflow_run'] = {key: value.get('workflow_run', {}).get(key) for key in
                              ('id', 'repository_id', 'head_repository_id', 'head_sha', 'head_branch')}
    return result


def candidate_metadata(api, ctx, selected, now=None):
    workflow_context(ctx, DEPLOY, 'workflow_run')
    need(ctx['attempt'] == 1, 'consumer reruns are refused; no selection may be re-resolved')
    repository = api.get('')
    # Backend is intentionally public. API authentication is provenance, not confidentiality.
    need(integer(repository.get('id')) == REPOSITORY_ID and repository.get('full_name') == REPOSITORY
         and repository.get('default_branch') == 'main' and repository.get('archived') is False
         and repository.get('disabled') is False, 'repository identity/state mismatch')
    workflow_ids = {}
    for path in (CI, DEPLOY):
        workflow = api.get('/actions/workflows/' + path.split('/')[-1])
        need(workflow.get('path') == path and workflow.get('state') == 'active', 'inactive or wrong workflow')
        workflow_ids[path] = integer(workflow.get('id'))
    run_id, attempt, sha = selected['run_id'], selected['attempt'], selected['sha']
    for suffix in (f'/actions/runs/{run_id}', f'/actions/runs/{run_id}/attempts/{attempt}'):
        run_identity(api.get(suffix), run_id, attempt, workflow_ids[CI], CI, 'push', sha)
    own = api.get(f"/actions/runs/{ctx['run_id']}")
    run_identity(own, ctx['run_id'], 1, workflow_ids[DEPLOY], DEPLOY, 'workflow_run', ctx['sha'], consumer=True)
    jobs = complete_list(api.get(f'/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100'), 'jobs')
    for name in ('verify', 'supply-chain', 'container'):
        matches = [job for job in jobs if job.get('name') == name]
        need(len(matches) == 1, 'missing or ambiguous required producer job')
        job = matches[0]
        need(integer(job.get('run_id')) == run_id and integer(job.get('run_attempt')) == attempt
             and job.get('head_sha') == sha and job.get('status') == 'completed'
             and job.get('conclusion') == 'success', 'required producer job did not succeed in this attempt')
    main = api.get('/git/ref/heads/main')
    need(main.get('ref') == 'refs/heads/main' and main.get('object', {}).get('type') == 'commit'
         and main['object'].get('sha') == sha, 'candidate is no longer current main')
    tree, trusted = api.source(sha)
    need(api.source(ctx['sha'])[1] == trusted and local_contract() == trusted, 'stale producer/consumer contract')
    artifacts = complete_list(api.get(f'/actions/runs/{run_id}/artifacts?per_page=100'), 'artifacts')
    matches = [x for x in artifacts if x.get('name') == f'backend-image-{run_id}-{attempt}']
    need(len(matches) == 1, 'missing or ambiguous attempt artifact')
    listed = artifact_identity(matches[0])
    artifact = artifact_identity(api.get(f"/actions/artifacts/{integer(listed['id'])}"))
    need(listed == artifact and artifact['expired'] is False
         and integer(artifact['size_in_bytes']) <= MAX_IMAGE, 'artifact changed or expired')
    image_id(artifact['digest'])
    current = time.time() if now is None else now
    created, expires = timestamp(artifact['created_at']), timestamp(artifact['expires_at'])
    need(created <= current < expires and current - created < 72 * 60 * 60, 'artifact freshness expired')
    binding = artifact['workflow_run']
    need(integer(binding['id']) == run_id and integer(binding['repository_id']) == REPOSITORY_ID
         and integer(binding['head_repository_id']) == REPOSITORY_ID and binding['head_sha'] == sha
         and binding['head_branch'] == 'main', 'artifact producer binding mismatch')
    return {'source': {'repository': REPOSITORY, 'repository_id': REPOSITORY_ID, 'sha': sha, 'tree': tree},
            'producer': {'workflow': CI, 'run_id': run_id, 'run_attempt': attempt, 'event': 'push', 'ref': 'refs/heads/main'},
            'contract': {name: trusted[name] for name in CONTRACT}, 'artifact': artifact}


def validate_receipt(directory, metadata):
    receipt = parse_json(file_bytes(directory / 'receipt.json', MAX_RECEIPT), MAX_RECEIPT)
    keys(receipt, 'schema purpose source producer contract image archive smoke')
    need(type(receipt['schema']) is int and receipt['schema'] == 1
         and receipt['purpose'] == 'production-candidate', 'ineligible receipt version/purpose')
    keys(receipt['source'], 'repository repository_id sha tree')
    integer(receipt['source']['repository_id'])
    keys(receipt['producer'], 'workflow run_id run_attempt event ref')
    integer(receipt['producer']['run_id'])
    integer(receipt['producer']['run_attempt'])
    need(all(receipt[key] == metadata[key] for key in ('source', 'producer', 'contract')), 'receipt provenance mismatch')
    keys(receipt['image'], 'id tag os architecture revision')
    image_id(receipt['image']['id'])
    keys(receipt['archive'], 'bytes sha256 expanded_bytes expanded_sha256')
    need(integer(receipt['archive']['bytes']) <= MAX_IMAGE and integer(receipt['archive']['expanded_bytes']) <= MAX_TAR,
         'receipt archive byte limit')
    for key in ('sha256', 'expanded_sha256'):
        hex_value(receipt['archive'][key])
    image, archive = validate_archive(directory / 'image.tar.gz', 'kira-backend:' + metadata['source']['sha'],
                                      backend=True, expected_id=receipt['image']['id'],
                                      expected_digest=receipt['archive']['sha256'])
    need(receipt['image'] == image and receipt['archive'] == archive, 'receipt image/archive mismatch')
    keys(receipt['smoke'], 'policy image_id result')
    need(receipt['smoke'] == {'policy': POLICY, 'image_id': image['id'], 'result': 'pass'}, 'smoke receipt mismatch')
    return receipt


def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(name + '=' + value + '\n')


def owned_directory():
    path = Path(os.environ.get('SCRATCH', ''))
    root = Path(os.environ['RUNNER_TEMP']).resolve()
    need(path.is_absolute() and path.parent == root and re.fullmatch(r'kira-backend-image-[A-Za-z0-9_\-]+', path.name)
         and stat.S_ISDIR(path.lstat().st_mode) and path.stat().st_uid == os.getuid()
         and path.stat().st_mode & 0o077 == 0, 'not owned workflow scratch')
    return path


def selected_run():
    return {'run_id': number_input(os.environ.get('CI_RUN_ID', '')),
            'attempt': number_input(os.environ.get('CI_RUN_ATTEMPT', '')),
            'sha': hex_value(os.environ.get('CI_SHA'), 40)}


def freeze(metadata, receipt):
    # Includes immutable API ID, outer bytes/digest and every inner/source identity.
    return digest(canonical({'metadata': metadata, 'receipt': receipt}))


def approved_envelope(metadata):
    if 'EXPECTED_CANDIDATE' in os.environ:
        hex_value(os.environ.get('EXPECTED_CANDIDATE'))
        artifact = metadata['artifact']
        need(integer(artifact['id']) == number_input(os.environ.get('EXPECTED_ARTIFACT_ID', ''))
             and artifact['digest'] == 'sha256:' + hex_value(os.environ.get('EXPECTED_ZIP_SHA256'))
             and artifact['size_in_bytes'] == number_input(os.environ.get('EXPECTED_ZIP_BYTES', '')),
             'frozen artifact ID/digest/length changed')


def verify(directory, ctx):
    api, selected = GitHub(os.environ.get('GITHUB_TOKEN')), selected_run()
    metadata = candidate_metadata(api, ctx, selected)
    approved_envelope(metadata)  # Refuse a replacement BEFORE downloading another ID.
    api.download(metadata['artifact'], directory / 'artifact.zip')
    unpack_zip(directory / 'artifact.zip', directory)
    receipt = validate_receipt(directory, metadata)
    need(candidate_metadata(api, ctx, selected) == metadata, 'candidate changed during verification')
    fingerprint = freeze(metadata, receipt)
    expected = os.environ.get('EXPECTED_CANDIDATE')
    need(not expected or hex_value(expected) == fingerprint, 'approved frozen candidate changed')
    (directory / 'candidate.json').write_bytes(canonical({'metadata': metadata, 'receipt': receipt}))
    output('candidate', fingerprint)
    output('artifact_id', str(metadata['artifact']['id']))
    output('zip_sha256', metadata['artifact']['digest'][7:])
    output('zip_bytes', str(metadata['artifact']['size_in_bytes']))
    print('Verified immutable candidate:', fingerprint)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as stream:
            stream.write('### Backend exact-image candidate (not deployment proof)\n```json\n'
                         + json.dumps({'fingerprint': fingerprint, **metadata,
                                       'image': receipt['image'], 'archive': receipt['archive']}, indent=2) + '\n```\n')


def inspect_image(tag, expected=None):
    result = parse_json(command(['docker', 'image', 'inspect', tag], seconds=30), MAX_CONFIG)
    need(type(result) is list and len(result) == 1, 'invalid Docker inspection')
    image = result[0]
    identity = image_id(image.get('Id'))
    need((expected is None or identity == image_id(expected)) and tag in image.get('RepoTags', [])
         and image.get('Os') == 'linux' and image.get('Architecture') == 'amd64'
         and image.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision') == tag.split(':')[1]
         and re.fullmatch(r'[0-9]+:[0-9]+', image.get('Config', {}).get('User', '')), 'built image/tag policy mismatch')
    return identity


def produce(directory, ctx):
    tag = 'kira-backend:' + ctx['sha']
    identity = inspect_image(tag, image_id(os.environ.get('BUILDX_IMAGE_ID')))
    command([str(ROOT / SMOKE), identity], seconds=600, maximum=MAX_CONFIG)
    inspect_image(tag, identity)  # The tag saved below still identifies the smoked object.
    if ctx['event'] != 'push' or ctx['ref'] != 'refs/heads/main':
        print('Exact-ID smoke passed; non-main/PR output is not a production candidate')
        return
    workflow_context(ctx, CI, 'push')
    need(command(['git', 'rev-parse', 'HEAD']).decode().strip() == ctx['sha'], 'producer checkout changed')
    tree = hex_value(command(['git', 'rev-parse', 'HEAD^{tree}']).decode().strip(), 40)
    plain = directory / 'image.tar'
    with plain.open('xb') as target:
        command(['docker', 'save', tag], seconds=180, maximum=MAX_TAR, output=target)
    docker_tar(plain, tag, True)
    with (directory / 'image.tar.gz').open('xb') as target:
        command(['gzip', '--no-name', '--stdout', str(plain)], seconds=180, maximum=MAX_IMAGE, output=target)
    image, archive = validate_archive(directory / 'image.tar.gz', tag, backend=True, expected_id=identity)
    plain.unlink()
    receipt = {'schema': 1, 'purpose': 'production-candidate',
               'source': {'repository': REPOSITORY, 'repository_id': REPOSITORY_ID, 'sha': ctx['sha'], 'tree': tree},
               'producer': {'workflow': CI, 'run_id': ctx['run_id'], 'run_attempt': ctx['attempt'], 'event': 'push', 'ref': ctx['ref']},
               'contract': {name: local_contract()[name] for name in CONTRACT}, 'image': image, 'archive': archive,
               'smoke': {'policy': POLICY, 'image_id': identity, 'result': 'pass'}}
    raw = canonical(receipt)
    need(len(raw) <= MAX_RECEIPT, 'receipt byte limit')
    (directory / 'receipt.json').write_bytes(raw)
    print('Exact-ID smoke/export passed:', identity, archive['sha256'])


def transfer(directory, ctx):
    frozen = parse_json(file_bytes(directory / 'candidate.json', MAX_RECEIPT), MAX_RECEIPT)
    keys(frozen, 'metadata receipt')
    receipt = validate_receipt(directory, frozen['metadata'])
    expected = hex_value(os.environ.get('EXPECTED_CANDIDATE'))
    need(receipt == frozen['receipt'] and freeze(frozen['metadata'], receipt) == expected, 'local candidate changed')
    # Last network freshness/run/artifact check BEFORE materializing privileged keys.
    metadata = candidate_metadata(GitHub(os.environ.get('GITHUB_TOKEN')), ctx, selected_run())
    approved_envelope(metadata)
    need(metadata == frozen['metadata'], 'candidate changed after approval')
    user, host = os.environ.get('DEPLOY_USER', ''), os.environ.get('DEPLOY_HOST', '')
    port = os.environ.get('DEPLOY_PORT') or '22'
    need(re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', user) and len(host) <= 253
         and re.fullmatch(r'([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?', host)
         and re.fullmatch(r'[1-9][0-9]{0,4}', port) and int(port) <= 65535, 'invalid SSH destination')
    key, known = os.environ.get('DEPLOY_KEY', ''), os.environ.get('KNOWN_HOSTS', '')
    need(0 < len(key.encode()) <= 65536 and 0 < len(known.encode()) <= 65536, 'missing or oversized SSH material')
    unknown = 'Receiver outcome unknown; inspect actual host state before retrying; no restoration or daemon-stop claim'
    try:
        with tempfile.TemporaryDirectory(prefix='ssh-', dir=directory) as name:
            key_dir = Path(name)
            (key_dir / 'key').write_text(key + '\n')
            (key_dir / 'known_hosts').write_text(known + '\n')
            for path in key_dir.iterdir():
                path.chmod(0o600)
            argv = ['ssh', '-F', '/dev/null', '-i', str(key_dir / 'key'), '-p', port]
            for option in ('BatchMode=yes', 'IdentitiesOnly=yes', 'IdentityAgent=none', 'StrictHostKeyChecking=yes',
                           'UserKnownHostsFile=' + str(key_dir / 'known_hosts'), 'GlobalKnownHostsFile=/dev/null',
                           'UpdateHostKeys=no', 'PasswordAuthentication=no', 'KbdInteractiveAuthentication=no',
                           'ForwardAgent=no', 'ClearAllForwardings=yes', 'PermitLocalCommand=no',
                           'ConnectTimeout=15', 'ConnectionAttempts=1', 'ServerAliveInterval=15', 'ServerAliveCountMax=3'):
                argv.extend(('-o', option))
            argv += [user + '@' + host, 'deploy backend ' + receipt['source']['sha'] + ' '
                     + receipt['archive']['sha256'] + ' ' + receipt['image']['id']]
            with (directory / 'image.tar.gz').open('rb') as original:
                status = command(argv, seconds=300, maximum=65536, stdin=original, return_status=True)
    except Exception:
        # No transport/timeout/cleanup exception or arbitrary remote text is an outcome.
        raise Refused(unknown) from None
    # Interpret only fixed receiver exits, after owned process AND key cleanup.
    messages = {
        70: 'Receiver refused before activation; existing host health is not asserted',
        71: 'Receiver deployment failed; healthy immutable predecessor and persistence restored; migrations remain forward-only',
        72: 'Receiver first deployment failed; no predecessor existed and owned candidate removal was verified',
        73: 'Receiver recovery incomplete or indeterminate; inspect actual host state and pending transaction before retrying',
        74: 'Receiver activation/no-op verified, but owned post-activation cleanup failed; inspect host before retrying',
    }
    need(status == 0, messages.get(status, unknown))
    print('Receiver reported verified exact-image activation/no-op:', receipt['image']['id'])


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('scratch', 'cleanup', 'produce', 'verify', 'transfer', 'receive', 'archive'))
    parser.add_argument('args', nargs='*')
    args = parser.parse_args()
    if args.action == 'receive':
        need(len(args.args) == 1, 'receive requires one fixed scratch path')
        with deadline(90), Path(args.args[0]).open('xb') as target:
            result = copy_bounded(sys.stdin.buffer, target, MAX_IMAGE)
            need(result['bytes'] > 0, 'empty image stream')
            target.flush()
            os.fsync(target.fileno())
        return
    if args.action == 'archive':
        need(len(args.args) in (3, 5), 'archive requires component, source and fixed archive path')
        component, sha, name = args.args[:3]
        need(component in ('backend', 'web', 'admin'), 'invalid component')
        tag = 'kira-' + component + ':' + hex_value(sha, 40)
        expected_digest, expected_id = args.args[3:] if len(args.args) == 5 else (None, None)
        image, archive = validate_archive(Path(name), tag, backend=component == 'backend',
                                          expected_id=expected_id, expected_digest=expected_digest)
        print(image['id'], archive['sha256'])
        return
    need(not args.args, 'unexpected arguments')
    if args.action == 'scratch':
        path = tempfile.mkdtemp(prefix='kira-backend-image-', dir=Path(os.environ['RUNNER_TEMP']).resolve())
        output('path', path)
    elif args.action == 'cleanup':
        shutil.rmtree(owned_directory())
    else:
        directory, ctx = owned_directory(), context()
        {'produce': produce, 'verify': verify, 'transfer': transfer}[args.action](directory, ctx)


if __name__ == '__main__':
    try:
        main()
    except Refused as error:
        print('Image operation refused:', error, file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Never print arbitrary archive/API/subprocess exception text or tracebacks.
        print('Image operation refused: malformed input, unavailable operation or cleanup failure', file=sys.stderr)
        sys.exit(1)
