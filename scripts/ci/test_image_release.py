"""Offline stdlib checks; never Docker, SSH, CI, production or a product build.

Receiver tests execute a disposable copy of the REAL Bash control flow. Only its
fixed root/helper/lock/PATH literals and root UID predicate are relocated to the
fixture's current UID. Production has no environment-controlled root bypass.
Docker, sudo, mv/rm-failure and link-mode fault commands are fixed fixture executables.
"""

import contextlib
import copy
import ctypes
import ctypes.util
import datetime as dt
import errno
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

import image_release as release

SHA = 'a' * 40
TREE = 'b' * 40
ROOT = Path(__file__).resolve().parents[2]


def permissive_default_acl(directory):
    """Linux-only regression condition, installed solely on an owned temp directory."""
    library = ctypes.util.find_library('acl') if sys.platform == 'linux' else None
    if not library:
        raise unittest.SkipTest('Linux libacl unavailable; default-ACL regression NOT VERIFIED')
    try:
        acl = ctypes.CDLL(library, use_errno=True)
    except OSError:
        raise unittest.SkipTest('libacl cannot load; default-ACL regression NOT VERIFIED') from None
    details = directory.lstat()
    assert stat.S_ISDIR(details.st_mode) and details.st_uid == os.getuid() and stat.S_IMODE(details.st_mode) == 0o700
    acl.acl_from_text.argtypes = [ctypes.c_char_p]; acl.acl_from_text.restype = ctypes.c_void_p
    acl.acl_set_file.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p]; acl.acl_set_file.restype = ctypes.c_int
    acl.acl_free.argtypes = [ctypes.c_void_p]; acl.acl_free.restype = ctypes.c_int
    value = acl.acl_from_text(b'user::rwx,group::rwx,other::rwx')
    if not value:
        raise OSError(ctypes.get_errno(), 'fixture ACL construction failed')
    try:
        if acl.acl_set_file(os.fsencode(directory), 0x4000, value) != 0:  # ACL_TYPE_DEFAULT on Linux.
            code = ctypes.get_errno()
            if code in (errno.ENOTSUP, errno.ENOSYS):
                raise unittest.SkipTest('filesystem default ACL unavailable; regression NOT VERIFIED')
            raise OSError(code, 'owned fixture default ACL installation failed')
    finally:
        acl.acl_free(value)


def tiny_image(component='backend', variant='A', *, oci=False, legacy=False):
    layer = io.BytesIO()
    with tarfile.open(fileobj=layer, mode='w', format=tarfile.USTAR_FORMAT) as bundle:
        data = variant.encode()
        info = tarfile.TarInfo('fixture'); info.size = len(data)
        bundle.addfile(info, io.BytesIO(data))
        # Legitimate filesystem link INSIDE the opaque layer must not be rejected.
        link = tarfile.TarInfo('fixture-link'); link.type = tarfile.SYMTYPE; link.linkname = 'fixture'
        bundle.addfile(link)
    layer = layer.getvalue()
    diff = release.digest(layer)
    tag = 'kira-' + component + ':' + SHA
    config = {'os': 'linux', 'architecture': 'amd64',
              'config': {'User': '10001:10001' if component == 'backend' else 'node'},
              'rootfs': {'type': 'layers', 'diff_ids': ['sha256:' + diff]}}
    if component == 'backend':
        config['config']['Labels'] = {'org.opencontainers.image.revision': SHA,
                                     'org.opencontainers.image.version': '1.0.0'}
    config_raw = release.canonical(config)
    identity = 'sha256:' + release.digest(config_raw)
    config_name = 'blobs/sha256/' + identity[7:] if oci else identity[7:] + '.json'
    layer_name = 'blobs/sha256/' + diff
    files = {config_name: config_raw, layer_name: layer}
    manifest = [{'Config': config_name, 'RepoTags': [tag], 'Layers': [layer_name]}]
    if oci:
        layer_desc = {'mediaType': 'application/vnd.oci.image.layer.v1.tar', 'digest': 'sha256:' + diff, 'size': len(layer)}
        image = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                 'config': {'mediaType': 'application/vnd.oci.image.config.v1+json', 'digest': identity, 'size': len(config_raw)},
                 'layers': [layer_desc]}
        raw = release.canonical(image)
        files['blobs/sha256/' + release.digest(raw)] = raw
        files['oci-layout'] = b'{"imageLayoutVersion":"1.0.0"}'
        files['index.json'] = release.canonical({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json',
            'manifests': [{'mediaType': image['mediaType'], 'digest': 'sha256:' + release.digest(raw), 'size': len(raw),
                          'annotations': {'io.containerd.image.name': 'docker.io/library/' + tag,
                                          'org.opencontainers.image.ref.name': SHA}}]})
        manifest[0]['LayerSources'] = {'sha256:' + diff: layer_desc}
        files['repositories'] = release.canonical({'kira-' + component: {SHA: diff}})
    if legacy:
        value = release.canonical({'id': 'c' * 64, 'os': 'linux', 'config': config['config']})
        files['blobs/sha256/' + release.digest(value)] = value
    files['manifest.json'] = release.canonical(manifest)
    return identity, tag, files


def docker_bytes(files, extras=()):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w', format=tarfile.USTAR_FORMAT) as bundle:
        for name, value in files.items():
            info = tarfile.TarInfo(name); info.size = len(value)
            bundle.addfile(info, io.BytesIO(value))
        for info, value in extras:
            bundle.addfile(info, io.BytesIO(value))
    return output.getvalue()


def write_image(directory, files):
    archive = directory / 'image.tar.gz'
    archive.write_bytes(gzip.compress(docker_bytes(files), mtime=0))
    return archive


def receipt_for(directory, metadata, files):
    write_image(directory, files)
    image, archive = release.validate_archive(directory / 'image.tar.gz', 'kira-backend:' + SHA, backend=True)
    value = {'schema': 1, 'purpose': 'production-candidate',
             **{k: metadata[k] for k in ('source', 'producer', 'contract')}, 'image': image, 'archive': archive,
             'smoke': {'policy': release.POLICY, 'image_id': image['id'], 'result': 'pass'}}
    (directory / 'receipt.json').write_bytes(release.canonical(value))
    return value


def zip_bytes(members):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as bundle:
        for name, value, mode in members:
            info = zipfile.ZipInfo(name); info.external_attr = mode << 16
            bundle.writestr(info, value)
    return output.getvalue()


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_supported_docker_profiles_and_sibling_user(self):
        for component, oci, legacy in [('backend', False, False), ('backend', True, True), ('web', True, True), ('admin', False, False)]:
            with self.subTest(component=component, oci=oci):
                identity, tag, files = tiny_image(component, oci=oci, legacy=legacy)
                archive = write_image(self.root, files)
                image, result = release.validate_archive(archive, tag, backend=component == 'backend', expected_id=identity)
                self.assertEqual(image['id'], identity)
                self.assertEqual(result['sha256'], release.digest(archive.read_bytes()))
                if component != 'backend': self.assertIsNone(image['revision'])

    def test_tar_identity_conflicts_are_rejected_before_load(self):
        identity, tag, original = tiny_image(oci=True)
        mutators = [
            lambda f: f.update({'manifest.json': release.canonical([json.loads(f['manifest.json'])[0]] * 2)}),
            lambda f: f.update({'manifest.json': f['manifest.json'].replace(tag.encode(), b'kira-backend:' + b'f' * 40)}),
            lambda f: f.update({'../outside': b'bad'}),
            lambda f: f.update({'index.json': f['index.json'].replace(SHA.encode(), b'f' * 40)}),
            lambda f: f.update({'blobs/sha256/' + 'f' * 64: b'{"rootfs":{}}'}),
            lambda f: f.update({json.loads(f['manifest.json'])[0]['Layers'][0]: b'wrong layer'}),
        ]
        for index, mutate in enumerate(mutators):
            with self.subTest(case=index):
                files = copy.deepcopy(original); mutate(files)
                with self.assertRaises(release.Refused):
                    release.validate_archive(write_image(self.root, files), tag, backend=True, expected_id=identity)
        with self.assertRaises(release.Refused):
            release.validate_archive(write_image(self.root, original), tag, expected_id='sha256:' + 'e' * 64)

    def test_outer_tar_links_duplicate_headers_and_hidden_pax_refused(self):
        identity, tag, files = tiny_image()
        for kind in ('link', 'duplicate', 'pax'):
            with self.subTest(kind=kind):
                info = tarfile.TarInfo('link' if kind == 'link' else 'manifest.json')
                value = b''
                if kind == 'link': info.type = tarfile.SYMTYPE; info.linkname = '../outside'
                if kind == 'pax': info.type = tarfile.XHDTYPE; value = b'20 path=../outside\n'; info.size = len(value)
                archive = self.root / 'image.tar.gz'
                archive.write_bytes(gzip.compress(docker_bytes(files, [(info, value)])))
                with self.assertRaises(release.Refused): release.validate_archive(archive, tag)

    def test_gzip_tamper_truncation_expansion_and_eof(self):
        _, tag, files = tiny_image()
        archive = write_image(self.root, files); original = archive.read_bytes()
        for raw in (original[:-5], original[:-8] + b'\0' * 8, original + b'not-gzip'):
            archive.write_bytes(raw)
            with self.assertRaises((release.Refused, OSError, EOFError)):
                release.validate_archive(archive, tag)
        archive.write_bytes(original)
        with patch.object(release, 'MAX_TAR', 1024), self.assertRaises(release.Refused):
            release.validate_archive(archive, tag)
        with self.assertRaises(release.Refused): release.validate_archive(archive, tag, expected_digest='0' * 64)
        raw = docker_bytes(files) + b'bad'.ljust(512, b'\0')
        archive.write_bytes(gzip.compress(raw))
        with self.assertRaises(release.Refused): release.validate_archive(archive, tag)

    def test_zip_exact_members_links_traversal_duplicates_and_limits(self):
        valid = [('image.tar.gz', b'gzip-fixture', stat.S_IFREG | 0o600), ('receipt.json', b'{}', stat.S_IFREG | 0o600)]
        cases = [valid + [valid[0]], [valid[0], ('../receipt.json', b'{}', stat.S_IFREG | 0o600)],
                 [valid[0], ('receipt.json', b'{}', stat.S_IFLNK | 0o777)],
                 [valid[0], ('receipt.json', b'x' * (release.MAX_RECEIPT + 1), stat.S_IFREG | 0o600)]]
        for index, members in enumerate(cases):
            with self.subTest(case=index):
                source = self.root / 'input.zip'; source.write_bytes(zip_bytes(members))
                dest = self.root / str(index); dest.mkdir()
                with self.assertRaises(release.Refused): release.unpack_zip(source, dest)
        source = self.root / 'valid.zip'; source.write_bytes(zip_bytes(valid)); dest = self.root / 'valid'; dest.mkdir()
        release.unpack_zip(source, dest)
        self.assertEqual({p.name for p in dest.iterdir()}, {'image.tar.gz', 'receipt.json'})

    def test_strict_json(self):
        for raw in (b'{"id":1,"id":1}', b'{"number":NaN}', b'{"number":1e999}', b'{} trailing'):
            with self.subTest(raw=raw), self.assertRaises(release.Refused): release.parse_json(raw, 100)

    def test_receive_cli_private_bytes_exclusive_paths_and_empty_refusal(self):
        def receive(target, data):
            return subprocess.run([sys.executable, '-I', '-B', str(ROOT / 'scripts/ci/image_release.py'), 'receive', str(target)],
                                  input=data, capture_output=True, timeout=5)
        target = self.root / 'image.tar.gz'; data = b'opaque archive bytes\0\xff'
        self.assertEqual(receive(target, data).returncode, 0)
        self.assertEqual(target.read_bytes(), data)
        before = target.lstat()
        self.assertTrue(stat.S_ISREG(before.st_mode))
        self.assertEqual(stat.S_IMODE(before.st_mode), 0o600)
        self.assertEqual(receive(target, b'replacement').returncode, 1)
        self.assertEqual(target.lstat(), before)
        self.assertEqual(target.read_bytes(), data)
        for destination in (target, self.root / 'absent'):
            link = self.root / ('link-' + destination.name); link.symlink_to(destination)
            self.assertEqual(receive(link, b'replacement').returncode, 1)
            self.assertTrue(link.is_symlink())
        self.assertFalse(self.root.joinpath('absent').exists())
        self.assertEqual(target.read_bytes(), data)
        empty = self.root / 'empty.tar.gz'
        self.assertEqual(receive(empty, b'').returncode, 1)
        self.assertEqual(empty.stat().st_size, 0)
        # Keep the existing streaming primitive's exact limit / extra-byte refusal.
        output = io.BytesIO()
        self.assertEqual(release.copy_bounded(io.BytesIO(data), output, len(data))['bytes'], len(data))
        self.assertEqual(output.getvalue(), data)
        with self.assertRaisesRegex(release.Refused, '^stream byte limit$'):
            release.copy_bounded(io.BytesIO(data + b'x'), io.BytesIO(), len(data))

    def test_receive_fdopen_failure_closes_exclusively_created_descriptor(self):
        target = self.root / 'handoff.tar.gz'; descriptors = []
        actual_open = os.open
        def create(*args, **kwargs):
            descriptor = actual_open(*args, **kwargs); descriptors.append(descriptor); return descriptor
        try:
            with io.TextIOWrapper(io.BytesIO(b'fixture')) as source, \
                 patch.object(release.sys, 'argv', ['image_release.py', 'receive', str(target)]), \
                 patch.object(release.sys, 'stdin', source), patch.object(release.os, 'umask'), \
                 patch.object(release.os, 'open', side_effect=create) as opened, \
                 patch.object(release.os, 'fdopen', side_effect=OSError('synthetic fdopen failure')), \
                 self.assertRaisesRegex(OSError, '^synthetic fdopen failure$'):
                release.main()
            opened.assert_called_once_with(str(target), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with self.assertRaises(OSError) as failure: os.fstat(descriptors[0])
            self.assertEqual(failure.exception.errno, errno.EBADF)
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
        finally:
            for descriptor in descriptors:
                try: os.close(descriptor)
                except OSError as error:
                    if error.errno != errno.EBADF: raise


class FakeAPI:
    def __init__(self):
        self.ctx = {'repository': release.REPOSITORY, 'repository_id': release.REPOSITORY_ID, 'sha': SHA,
                    'run_id': 200, 'attempt': 1, 'event': 'workflow_run', 'ref': 'refs/heads/main',
                    'workflow_ref': f'{release.REPOSITORY}/{release.DEPLOY}@refs/heads/main', 'workflow_sha': SHA}
        self.selected = {'run_id': 100, 'attempt': 1, 'sha': SHA}
        repo = {'id': release.REPOSITORY_ID, 'full_name': release.REPOSITORY, 'private': False,
                'default_branch': 'main', 'archived': False, 'disabled': False}
        run = {'id': 100, 'run_attempt': 1, 'workflow_id': 10, 'path': release.CI, 'event': 'push',
               'head_branch': 'main', 'head_sha': SHA, 'status': 'completed', 'conclusion': 'success',
               'repository': repo, 'head_repository': repo}
        own = {**run, 'id': 200, 'workflow_id': 20, 'path': release.DEPLOY, 'event': 'workflow_run',
               'status': 'in_progress', 'conclusion': None}
        created = dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)
        artifact = {'id': 300, 'name': 'backend-image-100-1', 'size_in_bytes': 1000, 'digest': 'sha256:' + 'c' * 64,
                    'expired': False, 'created_at': created.isoformat(), 'expires_at': (created + dt.timedelta(days=3)).isoformat(),
                    'workflow_run': {'id': 100, 'repository_id': release.REPOSITORY_ID,
                                     'head_repository_id': release.REPOSITORY_ID, 'head_sha': SHA, 'head_branch': 'main'}}
        self.data = {'': repo,
            '/actions/workflows/ci.yml': {'id': 10, 'path': release.CI, 'state': 'active'},
            '/actions/workflows/deploy-server3.yml': {'id': 20, 'path': release.DEPLOY, 'state': 'active'},
            '/actions/runs/100': run, '/actions/runs/100/attempts/1': copy.deepcopy(run),
            '/actions/runs/200': own,
            '/actions/runs/100/attempts/1/jobs?per_page=100': {'total_count': 3, 'jobs': [
                {'id': index + 1, 'name': name, 'run_id': 100, 'run_attempt': 1, 'head_sha': SHA,
                 'status': 'completed', 'conclusion': 'success'} for index, name in enumerate(('verify', 'supply-chain', 'container'))]},
            '/git/ref/heads/main': {'ref': 'refs/heads/main', 'object': {'type': 'commit', 'sha': SHA}},
            '/actions/runs/100/artifacts?per_page=100': {'total_count': 1, 'artifacts': [copy.deepcopy(artifact)]},
            '/actions/artifacts/300': artifact}
        self.downloads = []
        self.packet = None

    def get(self, suffix): return copy.deepcopy(self.data[suffix])
    def source(self, sha): return TREE, release.local_contract()
    def metadata(self): return release.candidate_metadata(self, self.ctx, self.selected)
    def download(self, artifact, destination):
        self.downloads.append(artifact['id'])
        release.need(len(self.packet) == artifact['size_in_bytes'] and 'sha256:' + release.digest(self.packet) == artifact['digest'],
                     'outer ZIP digest or size mismatch')
        destination.write_bytes(self.packet)

    def make_packet(self, root):
        metadata = self.metadata()
        _, _, files = tiny_image(oci=True, legacy=True)
        receipt_for(root, metadata, files)
        self.packet = zip_bytes([(name, (root / name).read_bytes(), stat.S_IFREG | 0o600) for name in release.FILES])
        for artifact in (self.data['/actions/artifacts/300'], self.data['/actions/runs/100/artifacts?per_page=100']['artifacts'][0]):
            artifact['size_in_bytes'] = len(self.packet); artifact['digest'] = 'sha256:' + release.digest(self.packet)

    def env(self):
        return {'CI_RUN_ID': '100', 'CI_RUN_ATTEMPT': '1', 'CI_SHA': SHA, 'GITHUB_TOKEN': 'synthetic-token'}


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_real_receipt_shape_and_cross_fields(self):
        metadata = self.api.metadata(); _, _, files = tiny_image()
        receipt = receipt_for(self.root, metadata, files)
        self.assertEqual(release.validate_receipt(self.root, metadata), receipt)
        mutations = [lambda r: r.update({'unknown': True}), lambda r: r.update({'schema': True}),
                     lambda r: r['producer'].update({'run_attempt': True}), lambda r: r['source'].update({'repository_id': True}),
                     lambda r: r['smoke'].update({'image_id': 'sha256:' + 'f' * 64}),
                     lambda r: r['archive'].update({'bytes': True}), lambda r: r['image'].update({'revision': 'd' * 40}),
                     lambda r: r.update({'purpose': 'no-deploy-rehearsal'})]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                changed = copy.deepcopy(receipt); mutate(changed)
                (self.root / 'receipt.json').write_bytes(release.canonical(changed))
                with self.assertRaises(release.Refused): release.validate_receipt(self.root, metadata)

    def test_authenticated_envelope_refusals(self):
        cases = [('', 'id', True), ('', 'full_name', 'attacker/fork'),
            ('/actions/workflows/ci.yml', 'id', 11), ('/actions/workflows/ci.yml', 'path', '.github/workflows/other.yml'),
            ('/actions/runs/100', 'run_attempt', 2), ('/actions/runs/100/attempts/1', 'head_sha', 'f' * 40),
            ('/actions/runs/100', 'event', 'pull_request'), ('/actions/runs/200', 'run_attempt', 2),
            ('/actions/runs/100/attempts/1/jobs?per_page=100', 'total_count', 4),
            ('/actions/runs/100/artifacts?per_page=100', 'total_count', 2),
            ('/actions/artifacts/300', 'digest', None), ('/actions/artifacts/300', 'expired', True),
            ('/actions/artifacts/300', 'size_in_bytes', release.MAX_IMAGE + 1)]
        self.assertEqual(self.api.metadata()['source']['repository_id'], release.REPOSITORY_ID)
        for route, key, value in cases:
            with self.subTest(route=route, key=key):
                api = FakeAPI(); api.data[route][key] = value
                with self.assertRaises(release.Refused): api.metadata()
        for mutation in ('skipped-job', 'duplicate-job', 'duplicate-artifact', 'changed-main', 'old-artifact', 'expired-artifact', 'fork'):
            with self.subTest(mutation=mutation):
                api = FakeAPI()
                if mutation == 'skipped-job': api.data['/actions/runs/100/attempts/1/jobs?per_page=100']['jobs'][1]['conclusion'] = 'skipped'
                if mutation == 'duplicate-job': api.data['/actions/runs/100/attempts/1/jobs?per_page=100']['jobs'][1]['name'] = 'verify'
                if mutation == 'duplicate-artifact':
                    listing = api.data['/actions/runs/100/artifacts?per_page=100']; listing['total_count'] = 2
                    listing['artifacts'].append({**listing['artifacts'][0], 'id': 301})
                if mutation == 'changed-main': api.data['/git/ref/heads/main']['object']['sha'] = 'e' * 40
                if mutation in ('old-artifact', 'expired-artifact'):
                    key = 'created_at' if mutation == 'old-artifact' else 'expires_at'
                    value = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=4)).isoformat()
                    api.data['/actions/artifacts/300'][key] = value
                    api.data['/actions/runs/100/artifacts?per_page=100']['artifacts'][0][key] = value
                if mutation == 'fork': api.data['/actions/runs/100']['head_repository'] = {'id': 1, 'full_name': 'attacker/fork'}
                with self.assertRaises(release.Refused): api.metadata()
        self.api.ctx['attempt'] = 2
        with self.assertRaises(release.Refused): self.api.metadata()

    def test_same_frozen_candidate_twice_streams_original_bytes_without_docker(self):
        source = self.root / 'source'; source.mkdir(); self.api.make_packet(source)
        first = self.root / 'first'; first.mkdir(); second = self.root / 'second'; second.mkdir()
        outputs = {}
        with patch.object(release, 'GitHub', return_value=self.api), patch.object(release, 'output', side_effect=outputs.__setitem__), \
             patch.dict(os.environ, self.api.env(), clear=True), patch.object(release, 'command') as command:
            release.verify(first, self.api.ctx)
            expected = {'EXPECTED_CANDIDATE': outputs['candidate'], 'EXPECTED_ARTIFACT_ID': outputs['artifact_id'],
                        'EXPECTED_ZIP_SHA256': outputs['zip_sha256'], 'EXPECTED_ZIP_BYTES': outputs['zip_bytes']}
            with patch.dict(os.environ, expected): release.verify(second, self.api.ctx)
            self.assertEqual(first.joinpath('image.tar.gz').read_bytes(), second.joinpath('image.tar.gz').read_bytes())
            self.assertEqual(self.api.downloads, [300, 300]); command.assert_not_called()
            streams = []
            def ssh(argv, **kwargs):
                self.assertEqual(argv[0], 'ssh')
                self.assertTrue(argv[-1].startswith('deploy backend ' + SHA + ' '))
                streams.append(kwargs['stdin'].read())
                self.assertEqual(kwargs['seconds'], 300)
                self.assertTrue(kwargs['return_status'])
                return 0
            with patch.dict(os.environ, {**expected, 'DEPLOY_USER': 'kira-deploy', 'DEPLOY_HOST': 'fixture.invalid',
                                         'DEPLOY_KEY': 'synthetic-key', 'KNOWN_HOSTS': 'synthetic-host-pin'}), \
                 patch.object(release, 'command', side_effect=ssh):
                release.transfer(second, self.api.ctx)
            self.assertEqual(streams, [source.joinpath('image.tar.gz').read_bytes()])
            self.assertFalse(list(second.glob('ssh-*')))
            self.api.data['/git/ref/heads/main']['object']['sha'] = 'f' * 40
            with patch.dict(os.environ, expected), self.assertRaises(release.Refused): release.transfer(second, self.api.ctx)
            self.assertFalse(list(second.glob('ssh-*')))

    def test_replacement_or_tampered_outer_never_becomes_approved(self):
        source = self.root / 'source'; source.mkdir(); self.api.make_packet(source)
        destination = self.root / 'target'; destination.mkdir()
        with patch.object(release, 'GitHub', return_value=self.api), patch.dict(os.environ, {
                **self.api.env(), 'EXPECTED_CANDIDATE': 'c' * 64, 'EXPECTED_ARTIFACT_ID': '301',
                'EXPECTED_ZIP_SHA256': release.digest(self.api.packet), 'EXPECTED_ZIP_BYTES': str(len(self.api.packet))}, clear=True):
            with self.assertRaises(release.Refused): release.verify(destination, self.api.ctx)
        self.assertEqual(self.api.downloads, [])
        self.api.packet += b'tamper'
        with patch.object(release, 'GitHub', return_value=self.api), patch.dict(os.environ, self.api.env(), clear=True):
            with self.assertRaises(release.Refused): release.verify(destination, self.api.ctx)

    def test_download_redirect_strips_token_and_rejects_unexpected_hosts(self):
        payload = b'actual-outer-bytes'
        artifact = {'id': 300, 'size_in_bytes': len(payload), 'digest': 'sha256:' + release.digest(payload)}
        calls = []
        class Response(io.BytesIO):
            status = 200
        def open_request(request, **kwargs):
            calls.append(request)
            if len(calls) == 1:
                raise urllib.error.HTTPError(request.full_url, 302, 'redirect',
                    {'Location': 'https://fixture.blob.core.windows.net/artifact?sig=synthetic'}, None)
            return Response(payload)
        with patch.object(release.OPENER, 'open', side_effect=open_request):
            release.GitHub('synthetic-token').download(artifact, self.root / 'download.zip')
        self.assertEqual(calls[0].get_header('Authorization'), 'Bearer synthetic-token')
        self.assertIsNone(calls[1].get_header('Authorization'))
        for url in ('http://api.github.com/', 'https://attacker.invalid/', 'https://api.github.com@attacker.invalid/'):
            with self.assertRaises(release.Refused): release.request(url, 'synthetic-token')
        with patch.object(release.OPENER, 'open', side_effect=urllib.error.HTTPError('synthetic', 302, '',
                    {'Location': 'https://attacker.invalid/artifact'}, None)), self.assertRaises(release.Refused):
            release.GitHub('synthetic-token').download(artifact, self.root / 'rejected.zip')

    def test_producer_smokes_id_before_single_export_and_preserves_version(self):
        identity, tag, files = tiny_image(oci=True, legacy=True)
        raw = docker_bytes(files); calls = []
        inspect = [{'Id': identity, 'RepoTags': [tag], 'Os': 'linux', 'Architecture': 'amd64',
                    'Config': {'User': '10001:10001', 'Labels': {'org.opencontainers.image.revision': SHA,
                                                               'org.opencontainers.image.version': '1.0.0'}}}]
        def command(argv, **kwargs):
            calls.append(argv)
            if argv[:3] == ['docker', 'image', 'inspect']: return release.canonical(inspect)
            if argv[0].endswith('container-smoke.sh'): self.assertEqual(argv[1], identity); return b''
            if argv[:2] == ['git', 'rev-parse']: return (SHA if argv[2] == 'HEAD' else TREE).encode()
            if argv[:2] == ['docker', 'save']: self.assertEqual(argv[2], tag); kwargs['output'].write(raw); return b''
            if argv[0] == 'gzip': kwargs['output'].write(gzip.compress(raw, mtime=0)); return b''
            self.fail('unexpected command')
        ctx = {**self.api.ctx, 'event': 'push', 'workflow_ref': f'{release.REPOSITORY}/{release.CI}@refs/heads/main'}
        with patch.object(release, 'command', side_effect=command), patch.dict(os.environ, {'BUILDX_IMAGE_ID': identity}):
            release.produce(self.root, ctx)
        save = [i for i, call in enumerate(calls) if call[:2] == ['docker', 'save']]
        smoke = [i for i, call in enumerate(calls) if call[0].endswith('container-smoke.sh')]
        self.assertEqual(len(save), 1); self.assertLess(smoke[0], save[0]); self.assertTrue((self.root / 'receipt.json').is_file())
        failed = self.root / 'failed'; failed.mkdir()
        with patch.object(release, 'inspect_image', return_value=identity), patch.object(release, 'command', side_effect=release.Refused('smoke failed')), \
             patch.dict(os.environ, {'BUILDX_IMAGE_ID': identity}), self.assertRaises(release.Refused):
            release.produce(failed, ctx)
        self.assertEqual(list(failed.iterdir()), [])

    def test_workflow_has_no_deployment_build_and_keeps_all_gates(self):
        producer = (ROOT / release.CI).read_text(); consumer = (ROOT / release.DEPLOY).read_text()
        for forbidden in ('docker/', 'docker save', 'build-push', 'packages:', 'KIRA_PACKAGES', 'head_sha }}\n          persist'):
            self.assertNotIn(forbidden, consumer)
        self.assertIn('ref: ${{ github.sha }}', consumer)
        self.assertIn('environment: production', consumer); self.assertIn('needs: preflight', consumer)
        self.assertIn('actions: read', consumer); self.assertIn('EXPECTED_ARTIFACT_ID', consumer)
        self.assertEqual(producer.count('docker/build-push-action@'), 1)
        for required in ('VERSION=1.0.0', 'platforms: linux/amd64', 'steps.image.outputs.imageid', 'overwrite: false',
                         'retention-days: 3', 'if-no-files-found: error', 'backend-image-${{ github.run_id }}-${{ github.run_attempt }}',
                         'supply-chain:', 'container:', 'verify:', 'container-smoke'):
            # Smoke invocation moved into the helper, not another shell tag invocation.
            if required != 'container-smoke': self.assertIn(required, producer)
        self.assertLess(producer.index('image_release.py produce'), producer.index('Retain only'))
        self.assertLess(producer.index('Validate deployment manifests'), producer.index('Retain only'))


# Isolated TEST process only: adopt/reap exactly the recorded fixture orphan, not
# command()'s leader. No subreaper, /proc scanner or extra wait owner in production.
CLOSED_STDIO_REAPER = r'''
import ctypes, json, os, signal, subprocess, sys, threading, time
from pathlib import Path
sys.path.insert(0, sys.argv[2])
import image_release as release
base = Path(sys.argv[1])
assert ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) == 0  # PR_SET_CHILD_SUBREAPER
result, errors = {}, []

def reap_orphan():
    end = time.monotonic() + 12
    while time.monotonic() < end:
        if (base / 'orphan.pid').exists():
            pid = int((base / 'orphan.pid').read_text())
            try:
                observed = os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            except ChildProcessError:
                observed = None  # Its leader has not exited/adoption has not happened yet.
            if observed is not None:
                # Keep a real group member waitable briefly: SIGKILL is not a join.
                time.sleep(0.15)
                waited, status = os.waitpid(pid, 0)
                assert waited == pid
                result.update(orphan=pid, status=os.waitstatus_to_exitcode(status))
                return
        time.sleep(0.005)
    errors.append('owned orphan reaper deadline')

worker = r"""
import os, select, signal, sys, time
from pathlib import Path
base = Path(sys.argv[1])
(base / 'leader.pid').write_text(str(os.getpid()))
read_fd, write_fd = os.pipe()
child = os.fork()
if child == 0:
    os.close(read_fd)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    null = os.open(os.devnull, os.O_RDWR)
    for fd in (0, 1, 2): os.dup2(null, fd)
    os.close(null)
    os.write(write_fd, b'R'); os.close(write_fd)
    time.sleep(10)  # Natural bounded exit lets the test reaper clean even on failure.
    os._exit(0)
os.close(write_fd)
(base / 'orphan.pid.tmp').write_text(str(child))
(base / 'orphan.pid.tmp').replace(base / 'orphan.pid')
assert select.select([read_fd], [], [], 2)[0] and os.read(read_fd, 1) == b'R'
os.close(read_fd)
print('owned-output', flush=True)
os._exit(0)
"""
reaper = threading.Thread(target=reap_orphan)
reaper.start()
try:
    data = release.command([sys.executable, '-c', worker, str(base)], seconds=2)
    leader = int((base / 'leader.pid').read_text())
    try: os.killpg(leader, 0)
    except ProcessLookupError: pass
    else: raise AssertionError('command returned before group absence')
finally:
    reaper.join(14)
assert not reaper.is_alive() and not errors and result['status'] == -signal.SIGKILL
for pid in (leader, result['orphan']):
    try: os.kill(pid, 0)
    except ProcessLookupError: pass
    else: raise AssertionError('owned fixture process not reaped')
assert data == b'owned-output\n'
print(json.dumps({'leader_reaped': True, 'orphan_reaped': True, 'group_absent': True,
                  'orphan_exit': result['status']}))
'''


class CommandTests(unittest.TestCase):
    @contextlib.contextmanager
    def leader(self):
        # All leaders here are tiny terminating Python commands; cleanup never
        # sends a numeric signal or manipulates anything outside the owned fixture.
        real_popen, processes = subprocess.Popen, []
        def spawn(*args, **kwargs):
            process = real_popen(*args, **kwargs); processes.append(process)
            return process
        with patch.object(release.subprocess, 'Popen', side_effect=spawn):
            try: yield processes
            finally:
                for process in processes:
                    process.stdout.close()
                    if process.returncode is None: process.wait(timeout=2)

    def assert_absent(self, process):
        self.assertTrue(process.stdout.closed)
        with self.assertRaises(ProcessLookupError): os.kill(process.pid, 0)
        with self.assertRaises(ProcessLookupError): os.killpg(process.pid, 0)

    def test_nonreaping_observation_pins_both_signals_then_one_reap_and_readonly_probe(self):
        real_waitid, real_waitpid, real_killpg = os.waitid, os.waitpid, os.killpg
        events = []
        with self.leader() as processes:
            def observe(kind, pid, flags):
                self.assertEqual((kind, pid, flags), (os.P_PID, processes[0].pid, os.WEXITED | os.WNOHANG | os.WNOWAIT))
                events.append(('observe', pid))
                return real_waitid(kind, pid, flags)
            def reap(pid, flags):
                self.assertEqual([x[1] for x in events if x[0] == 'signal'], [signal.SIGTERM, signal.SIGKILL])
                self.assertEqual((pid, flags), (processes[0].pid, os.WNOHANG))
                events.append(('reap', pid))
                return real_waitpid(pid, flags)
            def group(pid, sig):
                if sig:
                    self.assertFalse(any(x[0] == 'reap' for x in events))
                    self.assertEqual(events[-1], ('observe', pid))
                    self.assertIsNone(processes[0].returncode)
                    real_waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    events.append(('signal', sig))
                else:
                    self.assertTrue(any(x[0] == 'reap' for x in events))
                    self.assertIsNotNone(processes[0].returncode)
                    events.append(('probe', pid))
                return real_killpg(pid, sig)
            with patch.object(release.os, 'waitid', side_effect=observe), patch.object(release.os, 'waitpid', side_effect=reap), \
                 patch.object(release.os, 'killpg', side_effect=group):
                self.assertEqual(release.command([sys.executable, '-c', 'print("owned-output")']), b'owned-output\n')
            self.assertEqual(sum(x[0] == 'reap' for x in events), 1)
            self.assertTrue(any(x[0] == 'probe' for x in events))
            self.assert_absent(processes[0])

    def test_ownership_loss_never_retries_mutating_signals(self):
        real_waitid, real_waitpid, real_killpg = os.waitid, os.waitpid, os.killpg
        for when in ('observe', 'after-term', 'reap'):
            with self.subTest(when=when), self.leader() as processes:
                events = []
                def stolen():
                    processes[0].wait(timeout=2)  # Controlled test-only other wait owner.
                    events.append(('external-reap', processes[0].pid))
                def observe(kind, pid, flags):
                    if when == 'observe' and not events: stolen()
                    return real_waitid(kind, pid, flags)
                def group(pid, sig):
                    events.append(('signal', sig))
                    value = real_killpg(pid, sig)
                    if when == 'after-term' and sig == signal.SIGTERM: stolen()
                    return value
                def reap(pid, flags):
                    if when == 'reap':
                        waited, status = real_waitpid(pid, flags)
                        self.assertEqual(waited, pid)
                        processes[0].returncode = os.waitstatus_to_exitcode(status)
                        events.append(('external-reap', pid))
                    return real_waitpid(pid, flags)
                with patch.object(release.os, 'waitid', side_effect=observe), patch.object(release.os, 'waitpid', side_effect=reap), \
                     patch.object(release.os, 'killpg', side_effect=group), \
                     self.assertRaisesRegex(release.Refused, '^owned command ownership lost$'):
                    release.command([sys.executable, '-c', 'print("owned-output")'])
                index = next(i for i, event in enumerate(events) if event[0] == 'external-reap')
                self.assertFalse(any(event[0] == 'signal' and event[1] for event in events[index + 1:]))
                self.assertEqual([event[1] for event in events if event[0] == 'signal'],
                                 {'observe': [], 'after-term': [signal.SIGTERM], 'reap': [signal.SIGTERM, signal.SIGKILL]}[when])
                self.assert_absent(processes[0])

    def test_signal_error_refuses_even_when_leader_and_group_are_gone(self):
        real_killpg = os.killpg
        for failed in (signal.SIGTERM, signal.SIGKILL):
            with self.subTest(signal=failed), self.leader() as processes:
                def group(pid, sig):
                    if sig == failed: raise PermissionError('synthetic-signal-exception-body')
                    return real_killpg(pid, sig)
                with patch.object(release.os, 'killpg', side_effect=group), \
                     self.assertRaisesRegex(release.Refused, '^owned command cleanup failed$'):
                    release.command([sys.executable, '-c', 'print("owned-output")'])
                self.assert_absent(processes[0])

    def test_closed_stdio_descendant_is_killed_reaped_and_absent_before_return(self):
        with tempfile.TemporaryDirectory() as root:
            result = subprocess.run([sys.executable, '-B', '-c', CLOSED_STDIO_REAPER, root, str(ROOT / 'scripts/ci')],
                                    capture_output=True, timeout=25)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            self.assertEqual(json.loads(result.stdout), {'leader_reaped': True, 'orphan_reaped': True,
                             'group_absent': True, 'orphan_exit': -signal.SIGKILL})


# This executable replaces SSH itself, not command() or its subprocess boundary.
# Its mode/record paths are literal test-owned files. There is no real SSH fallback.
SSH_STUB = r'''#!/usr/bin/python3
import json, os, signal, stat, subprocess, sys, time
from pathlib import Path
BASE = Path(@BASE@)
args = sys.argv[1:]
key = Path(args[args.index('-i') + 1])
known = Path(next(x.split('=', 1)[1] for x in args if x.startswith('UserKnownHostsFile=')))
(BASE / 'received').write_bytes(sys.stdin.buffer.read())
record = {'args': args, 'env': dict(os.environ), 'pid': os.getpid(), 'group': os.getpgrp(),
          'session': os.getsid(0), 'key_dir': str(key.parent),
          'directory_mode': stat.S_IMODE(key.parent.stat().st_mode),
          'key_mode': stat.S_IMODE(key.stat().st_mode), 'known_mode': stat.S_IMODE(known.stat().st_mode),
          'key': key.read_text(), 'known': known.read_text()}
mode = (BASE / 'ssh-mode').read_text()
if mode == 'timeout':
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
    record['child'] = child.pid
    def stopped(sig, frame):
        # The real command() must terminate the whole group, not just this parent.
        child.wait(timeout=2)
        sys.exit(128 + sig)
    signal.signal(signal.SIGTERM, stopped)
(BASE / 'ssh-record.json').write_text(json.dumps(record))
print('arbitrary-remote-stdout', flush=True)
print('arbitrary-remote-stderr', file=sys.stderr, flush=True)
if mode == 'signal': os.kill(os.getpid(), signal.SIGTERM)
if mode == 'timeout': time.sleep(60)
if mode == 'byte-limit':
    sys.stdout.buffer.write(b'x' * 65537); sys.stdout.buffer.flush()
    sys.exit(0)
sys.exit(int(mode))
'''


class TransferTests(unittest.TestCase):
    UNKNOWN = 'Receiver outcome unknown; inspect actual host state before retrying; no restoration or daemon-stop claim'

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.api = FakeAPI(); self.api.make_packet(self.root)
        metadata = self.api.metadata()
        self.receipt = release.validate_receipt(self.root, metadata)
        self.root.joinpath('candidate.json').write_bytes(release.canonical({'metadata': metadata, 'receipt': self.receipt}))
        self.bin = self.root / 'bin'; self.bin.mkdir()
        ssh = self.bin / 'ssh'; ssh.write_text(SSH_STUB.replace('@BASE@', repr(str(self.root)))); ssh.chmod(0o755)
        self.env = {**self.api.env(), 'PATH': str(self.bin), 'HOME': str(self.root), 'TMPDIR': str(self.root), 'LANG': 'C.UTF-8',
                    'EXPECTED_CANDIDATE': release.freeze(metadata, self.receipt),
                    'EXPECTED_ARTIFACT_ID': str(metadata['artifact']['id']),
                    'EXPECTED_ZIP_SHA256': metadata['artifact']['digest'][7:],
                    'EXPECTED_ZIP_BYTES': str(metadata['artifact']['size_in_bytes']),
                    'DEPLOY_USER': 'kira-deploy', 'DEPLOY_HOST': 'fixture.invalid',
                    'DEPLOY_KEY': 'synthetic-private-key', 'KNOWN_HOSTS': 'synthetic-host-pin'}

    def transfer(self, mode):
        self.root.joinpath('ssh-mode').write_text(str(mode))
        real_command = release.command
        def bounded(argv, **kwargs):
            self.assertEqual(argv[0], 'ssh')
            self.assertEqual(kwargs['seconds'], 300)
            self.assertEqual(kwargs['maximum'], 65536)
            self.assertTrue(kwargs['return_status'])
            # Shorten only this offline deadline; use the real process/status/cleanup path.
            if mode == 'timeout': kwargs['seconds'] = 1
            return real_command(argv, **kwargs)
        test = self
        class AfterCleanup(io.StringIO):
            def write(self, value):
                test.assertFalse(any(path.is_dir() for path in test.root.glob('ssh-*')))
                return super().write(value)
        stdout, stderr, reason = AfterCleanup(), io.StringIO(), None
        with patch.object(release, 'GitHub', return_value=self.api), patch.dict(os.environ, self.env, clear=True), \
             patch.object(release, 'command', side_effect=bounded), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try: release.transfer(self.root, self.api.ctx)
            except release.Refused as error: reason = str(error)
        record = json.loads(self.root.joinpath('ssh-record.json').read_text())
        self.assertFalse(Path(record['key_dir']).exists())
        self.assertEqual(self.root.joinpath('received').read_bytes(), self.root.joinpath('image.tar.gz').read_bytes())
        self.assertEqual(record['directory_mode'], 0o700)
        self.assertEqual((record['key_mode'], record['known_mode']), (0o600, 0o600))
        self.assertEqual(record['key'], self.env['DEPLOY_KEY'] + '\n')
        self.assertEqual(record['known'], self.env['KNOWN_HOSTS'] + '\n')
        self.assertLessEqual(set(record['env']), {'PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_CTYPE'})
        self.assertEqual((record['group'], record['session']), (record['pid'], record['pid']))
        for pid in (record['pid'], *([record['child']] if 'child' in record else [])):
            with self.assertRaises(ProcessLookupError): os.kill(pid, 0)
        with self.assertRaises(ProcessLookupError): os.killpg(record['group'], 0)
        self.assertEqual(record['args'][-2:], ['kira-deploy@fixture.invalid', 'deploy backend ' + SHA + ' '
                         + self.receipt['archive']['sha256'] + ' ' + self.receipt['image']['id']])
        self.assertEqual(record['args'][:2], ['-F', '/dev/null'])
        for option in ('IdentityAgent=none', 'StrictHostKeyChecking=yes', 'GlobalKnownHostsFile=/dev/null',
                       'ForwardAgent=no', 'ClearAllForwardings=yes', 'PasswordAuthentication=no'):
            self.assertIn(option, record['args'])
        transcript = stdout.getvalue() + stderr.getvalue() + (reason or '')
        for secret in ('arbitrary-remote-stdout', 'arbitrary-remote-stderr', 'synthetic-cleanup-exception-body',
                       self.env['DEPLOY_KEY'], self.env['KNOWN_HOSTS'], self.env['GITHUB_TOKEN']):
            self.assertNotIn(secret, transcript)
        self.assertEqual(stderr.getvalue(), '')
        return stdout.getvalue(), reason

    def test_actual_transfer_status_mapping_secrecy_and_owned_cleanup(self):
        outcomes = {
            0: None,
            70: 'Receiver refused before activation; existing host health is not asserted',
            71: 'Receiver deployment failed; healthy immutable predecessor and persistence restored; migrations remain forward-only',
            72: 'Receiver first deployment failed; no predecessor existed and owned candidate removal was verified',
            73: 'Receiver recovery incomplete or indeterminate; inspect actual host state and pending transaction before retrying',
            74: 'Receiver activation/no-op verified, but owned post-activation cleanup failed; inspect host before retrying',
        }
        outcomes.update({mode: self.UNKNOWN for mode in (1, 64, 255, 130, 143, 'signal', 'timeout', 'byte-limit')})
        for mode, expected in outcomes.items():
            with self.subTest(mode=mode):
                stdout, reason = self.transfer(mode)
                self.assertEqual(reason, expected)
                self.assertEqual(stdout, 'Receiver reported verified exact-image activation/no-op: '
                                 + self.receipt['image']['id'] + '\n' if mode == 0 else '')

    def test_local_key_cleanup_error_withholds_any_remote_checked_claim(self):
        real_cleanup = tempfile.TemporaryDirectory.cleanup
        def failed_cleanup(directory):
            real_cleanup(directory)  # Do not leave synthetic key material behind.
            raise OSError('synthetic-cleanup-exception-body')
        for mode in (0, 71, 72, 74):
            with self.subTest(mode=mode), patch.object(tempfile.TemporaryDirectory, 'cleanup', failed_cleanup):
                self.assertEqual(self.transfer(mode), ('', self.UNKNOWN))

    def test_command_ownership_loss_and_group_absence_failure_remain_unknown(self):
        real_popen, real_waitid, real_waitpid = subprocess.Popen, os.waitid, os.waitpid
        real_killpg, real_clock = os.killpg, release.time.monotonic
        for failure in ('ownership', 'probe-permission', 'group-remains'):
            with self.subTest(failure=failure):
                processes, state = [], {'applied': False, 'clock_offset': 0, 'late_signals': []}
                def spawn(*args, **kwargs):
                    process = real_popen(*args, **kwargs); processes.append(process)
                    return process
                def observe(kind, pid, flags):
                    observed = real_waitid(kind, pid, flags)
                    if failure == 'ownership' and not state['applied'] and observed is not None:
                        waited, status = real_waitpid(pid, os.WNOHANG)
                        self.assertEqual(waited, pid)
                        processes[0].returncode = os.waitstatus_to_exitcode(status)
                        state['applied'] = True  # A controlled external test reap, not PID reuse.
                        return real_waitid(kind, pid, flags)  # Actual ECHILD must refuse.
                    return observed
                def group(pid, sig):
                    if sig and processes[0].returncode is not None:
                        state['late_signals'].append(sig)
                        raise AssertionError('mutating signal after reap')
                    if sig == 0 and failure != 'ownership' and not state['applied']:
                        state['applied'] = True
                        if failure == 'probe-permission': raise PermissionError('synthetic-group-exception-body')
                        # Deterministic remaining-group observation/exhausted cleanup clock;
                        # no real unrelated group and no production timeout override.
                        state['clock_offset'] = 5
                        return None
                    return real_killpg(pid, sig)
                with patch.object(release.subprocess, 'Popen', side_effect=spawn), \
                     patch.object(release.os, 'waitid', side_effect=observe), patch.object(release.os, 'killpg', side_effect=group), \
                     patch.object(release.time, 'monotonic', side_effect=lambda: real_clock() + state['clock_offset']):
                    self.assertEqual(self.transfer(0), ('', self.UNKNOWN))
                self.assertTrue(state['applied'])
                self.assertEqual(state['late_signals'], [])
                self.assertEqual(len(processes), 1)
                self.assertTrue(processes[0].stdout.closed)

    def test_command_default_still_returns_bytes_or_refuses_nonzero(self):
        argv = [sys.executable, '-c', 'import sys; sys.stdout.write("fixed-output"); sys.exit(int(sys.argv[1]))']
        self.assertEqual(release.command([*argv, '0']), b'fixed-output')
        output = io.BytesIO()
        self.assertEqual(release.command([*argv, '0'], output=output), b'')
        self.assertEqual(output.getvalue(), b'fixed-output')
        with self.assertRaisesRegex(release.Refused, '^command failed$'): release.command([*argv, '70'])


# The fixture executable cannot fall through to Docker/SSH. Unrecognized commands
# fail. Its paths are literal per-test values, never production environment knobs.
STUB = r'''#!/usr/bin/python3
import gzip, hashlib, io, json, os, signal
from pathlib import Path
import subprocess, sys, tarfile
BASE = Path(@BASE@)
state_path = BASE / 'docker-state.json'
s = json.loads(state_path.read_text())
args = sys.argv[1:]
name = Path(sys.argv[0]).name

def save(): state_path.write_text(json.dumps(s))
def done(code=0, output=''):
    save()
    if output: print(output)
    sys.exit(code)

def flag(key): return s.get('flags', {}).get(key, False)

if name == 'sudo':
    print(json.dumps(args)); sys.exit(0)
if name == 'ln':
    result = subprocess.call(['/usr/bin/ln'] + args)
    if result == 0:
        s.setdefault('links', []).append(args[-1])
        if flag('unsafe_new_archive'): Path(args[-1]).chmod(0o666)
        save()
    sys.exit(result)
if name in ('mv', 'rm'):
    c = s.get('container')
    if name == 'mv' and args[-1].endswith('/images.env') and c and c['image'] == s.get('B') and flag('fail_persist_once'):
        s['flags']['fail_persist_once'] = False; done(1)
    if name == 'rm' and any('/.incoming.' in arg for arg in args) and flag('fail_cleanup'):
        done(1)
    if name == 'rm' and any('/.incoming.' in arg for arg in args) and flag('signal_cleanup'):
        subprocess.check_call(['/usr/bin/rm'] + args)
        os.kill(os.getppid(), signal.SIGTERM); done()
    if name == 'rm' and args[-1].endswith('/pending') and flag('fail_pending_cleanup'):
        done(1)
    if name == 'rm' and args[-1].endswith('.tar.gz') and flag('fail_archive_cleanup'):
        done(1)
    sys.exit(subprocess.call(['/usr/bin/' + name] + args))

s.setdefault('calls', []).append({'args': args, 'image': os.environ.get('KIRA_' + s['component'].upper() + '_IMAGE')})
component = s['component']
container = s.get('container')

def info(ref):
    identity = s['tags'].get(ref, ref)
    return s['images'].get(identity)

def container_text():
    if not container: done(1)
    return ' '.join([container['id'], container['image'], str(container.get('running', True)).lower(),
                     container.get('health', 'healthy'), container.get('project', 'kira'), container.get('service', component)])

if args[:2] == ['container', 'ls']:
    done(output=container['id'] if container else '')
if args[:2] == ['container', 'inspect']:
    done(output=container_text())
if args[:2] == ['image', 'inspect']:
    image = info(args[-1])
    if image is None: done(1)
    form = args[3] if '--format' in args else ''
    if form == '{{.Id}}': done(output=image['Id'])
    if form == '{{.Config.User}}': done(output=image['Config']['User'])
    if '{{.Os}}' in form:
        done(output=' '.join([image['Id'], image['Os'], image['Architecture'], image['Config']['User'],
                             image['Config'].get('Labels', {}).get('org.opencontainers.image.revision', '')]))
    done(output=json.dumps([image]))
if args and args[0] == 'load':
    with tarfile.open(args[-1], 'r:gz') as bundle:
        manifest = json.load(bundle.extractfile('manifest.json'))[0]
        raw = bundle.extractfile(manifest['Config']).read(); config = json.loads(raw)
    identity = 'sha256:' + hashlib.sha256(raw).hexdigest()
    image = {'Id': identity, 'Config': config['config'], 'Os': config['os'], 'Architecture': config['architecture'],
             'RepoTags': manifest['RepoTags']}
    s['images'][identity] = image
    for tag in manifest['RepoTags']: s['tags'][tag] = identity
    s['loads'] = s.get('loads', 0) + 1
    if flag('unsafe_received_archive') and identity == s.get('B'): Path(args[-1]).chmod(0o666)
    if flag('missing_rollback') and identity == s.get('B'): s['images'].pop(s['A'], None)
    done()
if args and args[0] == 'compose':
    tail = args[args.index('--env-file') + 2:]
    if 'config' in tail: done(1 if flag('fail_preflight') else 0)
    service = args[-1]
    image = os.environ.get('KIRA_' + component.upper() + '_IMAGE')
    if not image:
        for line in (BASE / 'root/images.env').read_text().splitlines():
            if line.startswith('KIRA_' + component.upper() + '_IMAGE='): image = line.split('=', 1)[1]
    if service == 'postgres': done()
    if service == 'backend-migrate':
        s.setdefault('migrations', []).append(image)
        done(1 if flag('fail_migration') else 0)
    if service != component or 'up' not in tail: done(92)
    if image not in s['images']: done(1)
    if flag('fail_rollback') and image == s.get('A') and s.get('loads', 0) > 1: done(1)
    actual = s['A'] if flag('wrong_runtime') and image == s.get('B') else image
    s['container'] = {'id': hashlib.sha256((component + actual).encode()).hexdigest(), 'image': actual,
                      'running': True, 'health': 'healthy', 'project': 'kira', 'service': component}
    if image == s.get('B') and (flag('fail_activation') or flag('fail_health')):
        s['container']['health'] = 'unhealthy'
        done(1 if flag('fail_activation') else 0)
    done()
if args and args[0] == 'inspect':
    if args[-1] == 'kira-postgres':
        done(output='postgres:fixture' if '{{.Config.Image}}' in args else 'healthy')
    if not container: done(1)
    done(output='true' if '{{.State.Running}}' in args else json.dumps([container]))
if args[:2] == ['volume', 'create']: done(output='kira-tutorial-media')
if args and args[0] == 'run':
    if 'postgres:fixture' in args:
        volume = next(x for x in args if x.endswith(':/backup'))
        destination = Path(volume[:-8]) / Path(args[args.index('-czf') + 1]).name
        with tarfile.open(destination, 'w:gz'): pass
    done()
if args and args[0] == 'exec':
    if flag('fail_backup'): done(1)
    done(output='synthetic-dump-or-manifest')
if args and args[0] in ('pause', 'unpause'): done()
if args and args[0] == 'rm':
    if not container or args[-1] != container['id']: done(1)
    s['container'] = None; done()
done(93)
'''


class ReceiverFixture:
    def __init__(self, base, component):
        self.base, self.component = base, component
        self.root = base / 'root'; self.root.mkdir(mode=0o700)
        self.bin = base / 'bin'; self.bin.mkdir()
        self.helper = base / 'image_release.py'; self.helper.write_bytes((ROOT / 'scripts/ci/image_release.py').read_bytes()); self.helper.chmod(0o644)
        for name in ('compose.yaml', 'images.env', 'ingress.env'):
            path = self.root / name; path.write_text('' if name != 'images.env' else
                '\n'.join('KIRA_' + c.upper() + '_IMAGE=kira-' + c + ':' + SHA for c in ('backend', 'web', 'admin')) + '\n')
            path.chmod(0o600)
        source = (ROOT / 'deploy/server3/kira-deploy').read_text()
        substitutions = {'deployment_root=/opt/kira': 'deployment_root=' + str(self.root),
                         'archive_helper=/usr/local/libexec/kira-image-release.py': 'archive_helper=' + str(self.helper),
                         '/run/lock/kira-deploy.lock': str(base / 'deployment.lock'),
                         'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin':
                             'export PATH=' + str(self.bin) + ':/usr/bin:/bin'}
        for old, new in substitutions.items():
            assert source.count(old) == 1
            source = source.replace(old, new)
        # Only the installed-root UID comparison changes for unprivileged CI tests.
        source = source.replace('$(stat -c %u "$1") == 0', '$(stat -c %u "$1") == ' + str(os.getuid()))
        source = source.replace('$EUID -eq 0', '$EUID -eq ' + str(os.getuid()))
        self.receiver = base / 'kira-deploy'; self.receiver.write_text(source); self.receiver.chmod(0o755)
        for name in ('docker', 'sudo', 'mv', 'rm', 'ln'):
            path = self.bin / name; path.write_text(STUB.replace('@BASE@', repr(str(base)))); path.chmod(0o755)
        self.state_path = base / 'docker-state.json'
        self.A, _, a = tiny_image(component, 'A', oci=True, legacy=True)
        self.B, _, b = tiny_image(component, 'B', oci=True, legacy=True)
        self.archives = {'A': gzip.compress(docker_bytes(a), mtime=0), 'B': gzip.compress(docker_bytes(b), mtime=0)}
        self.state_path.write_text(json.dumps({'component': component, 'images': {}, 'tags': {}, 'container': None,
                                             'flags': {}, 'calls': [], 'A': self.A, 'B': self.B}))

    def state(self): return json.loads(self.state_path.read_text())
    def update(self, mutate):
        state = self.state(); mutate(state); self.state_path.write_text(json.dumps(state))
    def flags(self, **flags): self.update(lambda s: s['flags'].update(flags))
    def args(self, variant):
        return ['deploy', self.component, SHA] + ([release.digest(self.archives[variant]), getattr(self, variant)] if self.component == 'backend' else [])
    def run(self, variant='A', args=None, data=None):
        return subprocess.run(['bash', str(self.receiver), *(self.args(variant) if args is None else args)],
                              input=self.archives[variant] if data is None else data, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=30)
    def activation(self): return self.root / 'releases' / self.component / 'activation'
    def archive(self, variant): return self.root / 'releases' / self.component / (release.digest(self.archives[variant]) + '.tar.gz')
    def configured(self):
        return next(x.split('=', 1)[1] for x in (self.root / 'images.env').read_text().splitlines()
                    if x.startswith('KIRA_' + self.component.upper() + '_IMAGE='))


class ReceiverTests(unittest.TestCase):
    def fixture(self, component='web'):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        return ReceiverFixture(Path(temp.name), component)

    def assert_ok(self, result): self.assertEqual(result.returncode, 0, result.stderr.decode())
    def assert_failed(self, result, message=None, code=70):
        self.assertEqual(result.returncode, code, (result.stdout + result.stderr).decode())
        if message: self.assertIn(message, result.stderr.decode())

    def test_receive_cli_and_retained_archive_are_private_under_default_acl(self):
        fixture = self.fixture()
        directory = fixture.root / 'releases' / fixture.component
        directory.mkdir(parents=True, mode=0o700)
        permissive_default_acl(directory)
        # A controlled counterexample to umask-only creation, NOT a claim about run05's ACL.
        control = directory / 'umask-only-control'
        legacy = subprocess.run([sys.executable, '-I', '-B', '-c',
            "import os, sys; from pathlib import Path; os.umask(0o077); Path(sys.argv[1]).open('xb').close()", str(control)],
            capture_output=True, timeout=5)
        self.assertEqual(legacy.returncode, 0, legacy.stderr.decode())
        self.assertEqual(stat.S_IMODE(control.stat().st_mode), 0o666)
        control.unlink()
        target = directory / 'direct-receive'
        received = subprocess.run([sys.executable, '-I', '-B', str(fixture.helper), 'receive', str(target)],
                                  input=fixture.archives['A'], capture_output=True, timeout=5)
        self.assert_ok(received)
        self.assertEqual(target.read_bytes(), fixture.archives['A'])
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
        target.unlink()
        # The real receiver's incoming directory inherits this default ACL too.
        self.assert_ok(fixture.run('A'))
        retained = fixture.archive('A').lstat()
        self.assertTrue(stat.S_ISREG(retained.st_mode))
        self.assertEqual(retained.st_uid, os.getuid())
        self.assertEqual(stat.S_IMODE(retained.st_mode), 0o600)
        self.assertEqual(retained.st_nlink, 1)  # Only the retained link survives owned incoming cleanup.
        self.assertEqual(fixture.archive('A').read_bytes(), fixture.archives['A'])
        self.assertFalse(list(directory.glob('.incoming.*')))

    def test_retention_refuses_unsafe_source_or_link_without_repairing_existing_archive(self):
        for fault in ('source', 'new-link', 'existing-target'):
            with self.subTest(fault=fault):
                fixture = self.fixture(); self.assert_ok(fixture.run('A'))
                record = fixture.activation().read_bytes(); links = len(fixture.state()['links'])
                if fault == 'existing-target':
                    fixture.archive('B').write_bytes(fixture.archives['B']); fixture.archive('B').chmod(0o666)
                else:
                    fixture.flags(**{'unsafe_received_archive' if fault == 'source' else 'unsafe_new_archive': True})
                result = fixture.run('B')
                self.assert_failed(result, 'primary=archive-persistence; rollback=restored', code=71)
                self.assertEqual(fixture.state()['container']['image'], fixture.A)
                self.assertEqual(fixture.configured(), fixture.A)
                self.assertEqual(fixture.activation().read_bytes(), record)
                self.assertEqual(fixture.archive('A').read_bytes(), fixture.archives['A'])
                self.assertEqual(stat.S_IMODE(fixture.archive('A').stat().st_mode), 0o600)
                self.assertEqual(len(fixture.state()['links']), links + (fault == 'new-link'))
                if fault == 'existing-target':
                    self.assertEqual(fixture.archive('B').read_bytes(), fixture.archives['B'])
                    self.assertEqual(stat.S_IMODE(fixture.archive('B').stat().st_mode), 0o666)
                else: self.assertFalse(fixture.archive('B').exists())
                self.assertFalse(fixture.activation().parent.joinpath('pending').exists())
                self.assertFalse(list(fixture.activation().parent.glob('.incoming.*')))

    def test_gateway_exact_new_backend_and_unchanged_siblings(self):
        fixture = self.fixture()
        good = ['deploy backend ' + SHA + ' ' + 'b' * 64 + ' sha256:' + 'c' * 64,
                'deploy web ' + SHA, 'deploy admin ' + SHA]
        bad = ['deploy backend ' + SHA, 'deploy web ' + SHA + ' extra', 'deploy admin ' + SHA + '\n',
               ' deploy web ' + SHA, 'deploy  web ' + SHA, 'deploy web "' + SHA + '"',
               'deploy web ' + SHA.upper(), 'deploy web ' + SHA[:-1], 'activate web sha256:' + 'c' * 64,
               'backup', 'deploy backend ' + SHA + ' ' + 'b' * 63 + ' sha256:' + 'c' * 64,
               'deploy web ' + SHA + ';id', good[0] + ' extra', good[0].replace('sha256:', 'SHA256:')]
        gateway = ROOT / 'deploy/server3/kira-deploy-gateway'
        for value in good + bad:
            with self.subTest(command=value):
                result = subprocess.run(['bash', str(gateway)], env={**os.environ, 'SSH_ORIGINAL_COMMAND': value,
                    'PATH': str(fixture.bin) + ':/usr/bin:/bin'}, capture_output=True, timeout=5)
                if value in good:
                    self.assert_ok(result)
                    self.assertEqual(json.loads(result.stdout), ['/usr/local/sbin/kira-deploy', *value.split()])
                else: self.assertEqual(result.returncode, 64)
        for args in (['deploy', 'backend', SHA], ['deploy', 'web', SHA, 'extra'], ['backup', 'extra'],
                     ['deploy', 'backend', SHA, 'b' * 64, 'SHA256:' + 'c' * 64]):
            self.assert_failed(fixture.run(args=args, data=b''), code=1 if args[0] == 'backup' else 70)
        self.assertEqual(fixture.state()['calls'], [])

    def test_same_sha_failed_B_restores_actual_A_for_every_component(self):
        for component in ('backend', 'web', 'admin'):
            with self.subTest(component=component):
                fixture = self.fixture(component)
                self.assert_ok(fixture.run('A'))
                # Recreate the legacy mutable configured reference while retaining a
                # valid archive mapping: runtime A is the authority BEFORE B loads.
                fixture.root.joinpath('images.env').write_text(fixture.root.joinpath('images.env').read_text().replace(fixture.A, 'kira-' + component + ':' + SHA))
                fixture.flags(fail_activation=True)
                result = fixture.run('B')
                self.assert_failed(result, 'rollback=restored healthy immutable predecessor ' + fixture.A, code=71)
                state = fixture.state()
                self.assertEqual(state['tags']['kira-' + component + ':' + SHA], fixture.B)
                self.assertEqual(state['container']['image'], fixture.A)
                self.assertEqual(state['container']['health'], 'healthy')
                self.assertEqual(fixture.configured(), fixture.A)
                self.assertIn(fixture.A, fixture.activation().read_text())
                self.assertEqual(fixture.archive('A').read_bytes(), fixture.archives['A'])
                self.assertFalse(fixture.archive('B').exists())
                self.assertFalse(list(fixture.activation().parent.glob('.incoming.*')))
                if component == 'backend':
                    self.assertEqual(state['migrations'], [fixture.A, fixture.B])
                    volume_runs = [x['args'] for x in state['calls'] if x['args'] and x['args'][0] == 'run' and '--entrypoint' in x['args']]
                    self.assertTrue(any(fixture.B in args for args in volume_runs))
                    self.assertFalse(any('kira-backend:' + SHA in args for args in volume_runs))
                app_up = [x for x in state['calls'] if x['args'][0] == 'compose' and x['args'][-1] == component and 'up' in x['args']]
                self.assertEqual(app_up[-1]['image'], fixture.A)
                for call in app_up:
                    self.assertIn('--no-build', call['args']); self.assertIn('--no-deps', call['args']); self.assertIn('never', call['args'])
                # Repeating restored A is an identical-image no-op even though B
                # overwrote the same-SHA mutable tag; retained bytes do not rotate.
                record = fixture.activation().read_bytes(); loads = state['loads']
                repeated = fixture.run('A')
                self.assert_ok(repeated)
                self.assertIn(b'already healthy at immutable image ' + fixture.A.encode(), repeated.stdout)
                self.assertEqual(fixture.state()['loads'], loads)
                self.assertEqual(fixture.activation().read_bytes(), record)
                self.assertEqual(fixture.archive('A').read_bytes(), fixture.archives['A'])

    def test_successful_transition_and_repeat_retain_previous_distinct_archive(self):
        fixture = self.fixture()
        self.assert_ok(fixture.run('A')); self.assert_ok(fixture.run('B'))
        before = fixture.activation().read_bytes(); loads = fixture.state()['loads']
        self.assert_ok(fixture.run('B'))
        self.assertEqual(fixture.state()['loads'], loads)
        self.assertEqual(fixture.activation().read_bytes(), before)
        self.assertIn('previous ' + SHA + ' ' + fixture.A, before.decode())
        self.assertEqual({p.name for p in fixture.activation().parent.glob('*.tar.gz')},
                         {fixture.archive('A').name, fixture.archive('B').name})
        self.assert_ok(fixture.run(args=['activate', 'web', fixture.A], data=b''))
        self.assertEqual(fixture.state()['container']['image'], fixture.A)
        self.assertEqual(fixture.configured(), fixture.A)

    def test_invalid_stream_stops_before_load_or_image_state_writes_even_with_expected_cache(self):
        fixture = self.fixture('backend'); self.assert_ok(fixture.run('A'))
        before = fixture.state()['loads']; images = fixture.root.joinpath('images.env').read_bytes()
        # A is cached, but the supplied stream actually contains B. Post-load cache
        # lookup of A must never stand in for archive identity verification.
        args = ['deploy', 'backend', SHA, release.digest(fixture.archives['B']), fixture.A]
        self.assert_failed(fixture.run('B', args=args), 'archive validation failed')
        self.assertEqual(fixture.state()['loads'], before)
        self.assertEqual(fixture.root.joinpath('images.env').read_bytes(), images)
        self.assertEqual(fixture.state()['migrations'], [fixture.A])
        self.assert_failed(fixture.run('B', data=b'invalid-gzip'), 'archive validation failed')
        self.assertEqual(fixture.state()['loads'], before)

    def test_runtime_drift_unowned_unhealthy_and_duplicate_configuration_stop(self):
        for kind in ('drift', 'unowned', 'unhealthy', 'duplicate', 'ambiguous', 'missing-archive', 'missing-record', 'preflight'):
            with self.subTest(kind=kind):
                fixture = self.fixture(); self.assert_ok(fixture.run())
                if kind == 'drift': fixture.update(lambda s: s['tags'].update({'kira-web:' + SHA: fixture.B})); fixture.root.joinpath('images.env').write_text(fixture.root.joinpath('images.env').read_text().replace(fixture.A, 'kira-web:' + SHA))
                if kind == 'unowned': fixture.update(lambda s: s['container'].update({'project': 'other'}))
                if kind == 'unhealthy': fixture.update(lambda s: s['container'].update({'health': 'unhealthy'}))
                if kind in ('duplicate', 'ambiguous'):
                    with fixture.root.joinpath('images.env').open('a') as stream:
                        stream.write(('' if kind == 'duplicate' else 'export ') + 'KIRA_WEB_IMAGE=' + fixture.A + '\n')
                if kind == 'missing-archive': fixture.archive('A').unlink()
                if kind == 'missing-record': fixture.activation().unlink()
                if kind == 'preflight': fixture.flags(fail_preflight=True)
                before = fixture.state()['loads']
                self.assert_failed(fixture.run('B'))
                self.assertEqual(fixture.state()['loads'], before)

    def test_failed_rollback_wrong_runtime_persistence_and_cleanup_are_not_green(self):
        for flags, expected, code in [({'fail_activation': True, 'fail_rollback': True}, 'rollback=FAILED', 73),
                                      ({'fail_activation': True, 'missing_rollback': True}, 'rollback=FAILED', 73),
                                      ({'wrong_runtime': True}, 'rollback=restored', 71),
                                      ({'fail_health': True}, 'rollback=restored', 71),
                                      ({'fail_persist_once': True}, 'rollback=restored', 71),
                                      ({'fail_pending_cleanup': True}, 'rollback=FAILED', 73),
                                      ({'fail_cleanup': True}, 'owned incoming cleanup failed', 74)]:
            with self.subTest(flags=flags):
                fixture = self.fixture(); self.assert_ok(fixture.run())
                fixture.flags(**flags)
                self.assert_failed(fixture.run('B'), expected, code=code)
                if expected == 'rollback=restored':
                    self.assertEqual(fixture.state()['container']['image'], fixture.A)
                    self.assertEqual(fixture.configured(), fixture.A)
                    self.assertFalse(fixture.archive('B').exists())
                if expected == 'rollback=FAILED': self.assertTrue(fixture.activation().parent.joinpath('pending').exists())

    def test_first_failed_deployment_has_no_fake_previous_runtime(self):
        fixture = self.fixture()
        fixture.flags(fail_activation=True)
        result = fixture.run('B')
        self.assert_failed(result, 'rollback=none (no previous healthy runtime)', code=72)
        self.assertIsNone(fixture.state()['container'])
        self.assertFalse(fixture.activation().exists())
        self.assertFalse(list(fixture.root.glob('releases/web/.incoming.*')))

    def test_explicit_bounded_legacy_archive_adoption_and_wrong_legacy_stop(self):
        for wrong in (False, True):
            with self.subTest(wrong=wrong):
                fixture = self.fixture(); self.assert_ok(fixture.run())
                fixture.activation().unlink(); fixture.archive('A').unlink()
                fixture.root.joinpath('images.env').write_text(fixture.root.joinpath('images.env').read_text().replace(fixture.A, 'kira-web:' + SHA))
                legacy = fixture.root / 'releases' / ('web-' + SHA + '.tar.gz')
                legacy.write_bytes(fixture.archives['B' if wrong else 'A']); legacy.chmod(0o600)
                result = fixture.run(args=['adopt', 'web', SHA], data=b'')
                if wrong:
                    self.assert_failed(result, 'does not contain the actual running image', code=1)
                    self.assertFalse(fixture.activation().exists())
                else:
                    self.assert_ok(result)
                    self.assertEqual(fixture.configured(), fixture.A)
                    self.assertEqual(fixture.archive('A').read_bytes(), fixture.archives['A'])
                self.assertEqual(fixture.state()['loads'], 1)

    def test_backend_backup_or_migration_failure_never_claims_success(self):
        for flag in ('fail_backup', 'fail_migration'):
            with self.subTest(flag=flag):
                fixture = self.fixture('backend'); self.assert_ok(fixture.run())
                fixture.flags(**{flag: True})
                self.assert_failed(fixture.run('B'), 'rollback=restored', code=71)
                self.assertEqual(fixture.state()['container']['image'], fixture.A)
                if flag == 'fail_backup': self.assertEqual(fixture.state()['migrations'], [fixture.A])

    def test_exit_cleanup_finalizes_refused_restored_removed_noop_and_prune_outcomes(self):
        for kind, code in [('refused', 73), ('restored', 73), ('removed', 73), ('noop', 74), ('prune', 74)]:
            with self.subTest(kind=kind):
                fixture = self.fixture()
                if kind in ('restored', 'noop', 'prune'): self.assert_ok(fixture.run('A'))
                if kind == 'prune':
                    unused = fixture.activation().parent / ('f' * 64 + '.tar.gz')
                    unused.write_bytes(b'owned unreferenced fixture'); unused.chmod(0o600)
                    fixture.flags(fail_archive_cleanup=True)
                else:
                    fixture.flags(fail_cleanup=True, fail_preflight=kind == 'refused',
                                  fail_activation=kind in ('restored', 'removed'))
                result = fixture.run('A' if kind == 'noop' else 'B')
                self.assert_failed(result, 'cleanup', code=code)
                self.assertEqual(result.stdout, b'')
                for provisional in (b'rollback=restored', b'rollback=none', b'already healthy', b'activated source='):
                    self.assertNotIn(provisional, result.stderr)
                if kind == 'prune':
                    self.assertEqual(fixture.state()['container']['image'], fixture.B)
                    self.assertEqual(fixture.configured(), fixture.B)
                    self.assertFalse(fixture.activation().parent.joinpath('pending').exists())
                    self.assertFalse(list(fixture.activation().parent.glob('.incoming.*')))
                    self.assertTrue(unused.exists())

    def test_unexpected_exit_and_signal_cannot_reuse_a_checked_outcome(self):
        fixture = self.fixture()
        # An unhandled lock-file redirection error, not an explicit refusal.
        fixture.base.joinpath('deployment.lock').mkdir()
        result = fixture.run(data=b'')
        self.assert_failed(result, 'indeterminate', code=73)
        self.assertEqual(fixture.state()['calls'], [])
        fixture = self.fixture(); self.assert_ok(fixture.run('A'))
        fixture.flags(signal_cleanup=True)
        result = fixture.run('B')
        self.assert_failed(result, code=143)
        self.assertEqual(result.stdout, b'')
        self.assertNotIn(b'activated source=', result.stderr)
        self.assertEqual(fixture.state()['container']['image'], fixture.B)
        self.assertFalse(list(fixture.activation().parent.glob('.incoming.*')))


if __name__ == '__main__':
    unittest.main()
