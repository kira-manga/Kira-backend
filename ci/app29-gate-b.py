#!/usr/bin/env python3
"""Inert public-source active-wire-cancel1 derivative; two-file statics and sole test, never qualification."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import stat
import subprocess
import time
from xml.etree import ElementTree as ET

ADMIN = Path(__file__).resolve().parent.parent  # Carrier controls/inputs, never a private archive.
BACKEND = ADMIN.parent / 'backend'
GATE = 'app-29-active-wire-cancel-01'
REQUEST_SCHEMA = 'app29-active-wire-cancel-public-v1'
REPOSITORY = 'kira-manga/Kira-backend'
BRANCH = 'remediation/app-29-active-wire-cancel-validation-01'
SOURCE_HEAD = '40cd36b81c387a73f68f2eba77374c8cd909aee8'
SOURCE_TREE = 'e69bb23c5b47b28dadd16de05cc37048f46d0da4'
PUBLIC_PARENT = 'bffe162f36be666b19a07e6f993b5d2dea743635'
SOURCE_PINS_SHA = 'd05eb79b4aaa2d105213439a120e789f1f033352eafa61a9c1220c92b6674d78'
PROFILE_ID = 'app-29-active-wire-cancel-profile-01'
PROFILE_FILE = 'profile/profile.json'
PROFILE_SHA = '116d0da4033e631daa9c5829f128f244ba7a9b8c718b36b79ef572baed1bcc86'
PROFILE_INIT = 'profile/profile.init.gradle'
PROFILE_INIT_SHA = 'ed961d99fc94b37f04a45e4a468e08fa645a868162a48a9556942b054f929c3f'
CREATOR_CLASS = 'me.manga.kira.backend.common.infrastructure.persistence.PoolLeaseDispatchCreatorIntegrationTest'
DIAGNOSTIC_CASES = ('RETURN_SAMPLE', 'FAILED_POST_CONSENT_TAIL')  # Syntax only; zero rows are required.
METHODS = {CREATOR_CLASS: ('real active query cancellation preserves native user request failure and RETURN waits for the foreign outer tail',)}
TEST_COUNTS = {CREATOR_CLASS: 1}
EXPECTED_TESTS = [{'class': name, 'method': method, 'display_name': method + '()'}
                  for name, methods in METHODS.items() for method in methods]
TEST_SELECTORS = [name + '.' + method for name, methods in METHODS.items() for method in methods]
TASKS = ['ktlintTestSourceSetCheck', 'detekt', 'test'] + [arg for selector in TEST_SELECTORS for arg in ('--tests', selector)]
OWNER_SHA = '56b66cfe8799123c719eaf048f81c542e5e4129d71c490cae99a38396c2a3385'
LOCAL_SHA = 'c67fcc5fe64a9a795373c4683c7c1edd6407146e3cd07609fa7018a8a98db79a'
INIT_SHA = '429961b98254b89f7ce1d7ba1efa61b8cba28a3bae35353bf3a8ba85f4a1c839'
IMAGES = {'postgres:17.6-alpine', 'testcontainers/ryuk:0.12.0'}
CONTROL_PATHS = ('ci/app29-gate-b.py', '.github/workflows/app29-gate-b.yml',
                 PROFILE_FILE, PROFILE_INIT, 'ci/app29_owned_children.py',
                 'profile/w01-local-dependencies.init.gradle', 'inputs/local-inputs.sha256')
REQUEST_PATH = 'ci/app29-gate-b.request.json'
CACHE_PATHS = ('.gradle', '.kotlin', 'build', 'buildSrc/build', 'out')
CAPTURE_FOLDER = 'reports/active-wire-cancel-01'
XML_PATH = 'test-results/test/TEST-' + CREATOR_CLASS + '.xml'
# A finite current-run upload surface. Command execution retains its original64MiB cap;
# smaller publication caps can fail retention, never turn truncation into a passing result.
PUBLIC_FILES = {
    'result.json': 1048576, 'request.json': 16384, 'jdk.json': 65536,
    'before-source-hashes.json': 131072, 'after-source-hashes.json': 131072,
    'owned-containers.json': 131072, XML_PATH: 4194304,
    CAPTURE_FOLDER + '/effective-classpath.txt': 1048576,
    CAPTURE_FOLDER + '/class-load.txt': 8388608,
    CAPTURE_FOLDER + '/worker-runtime.txt': 1048576,
    'gradle-test.log': 8388608, 'gradle-stop-immediate.log': 1048576,
    'gradle-stop-final.log': 1048576, 'container-events.log': 1048576,
    'image-0.log': 65536, 'image-1.log': 65536, 'jdk-version.log': 65536,
    'docker-version.log': 131072, 'containers-before.log': 16384,
    'containers-after-cleanup.log': 16384, 'containers-final.log': 16384,
    'remove-owned-containers.log': 16384,
    **{'containers-normal-' + str(index) + '.log': 16384 for index in range(21)},
}
PUBLIC_TOTAL_BYTES = 32 * 1024**2
GUARD_IDS = {
    'Unsafe bound path': 'BOUND_PATH',
    'Symlink in bound input/output': 'BOUND_LINK',
    'Missing/oversized regular input': 'BOUNDED_REGULAR_FILE',
    'Duplicate JSON key': 'DUPLICATE_KEY',
    'Required PostgreSQL/Ryuk topology missing': 'CONTAINER_REQUIRED_TOPOLOGY',
    'Required test/report root missing': 'CAPTURE_ROOT',
    'Report link': 'CAPTURE_LINK',
    'Report entry is not a regular file/directory': 'CAPTURE_TYPE',
    'Evidence copy drift': 'CAPTURE_COPY_DRIFT',
    'Required raw evidence inventory missing': 'CAPTURE_REQUIRED_INVENTORY',
    'Wrong diagnostic XML inventory': 'XML_INVENTORY',
    'Require exactly the1 fixed active-wire-cancel identity': 'XML_EXPECTED_IDENTITIES',
    'Unsafe XML': 'XML_SAFE',
    'Missing, extra or duplicate diagnostic testcase': 'XML_ACTUAL_IDENTITIES',
    'Contradictory diagnostic testcase outcome': 'XML_OUTCOME',
    'Diagnostic XML count mismatch': 'XML_COUNTS',
    'Malformed fixed diagnostic record': 'DIAGNOSTIC_FORMAT',
    'Wrong fixed diagnostic labels or scalar fields': 'DIAGNOSTIC_FIELDS',
    'PG14 fixed diagnostic rows are nonapplicable to this selection': 'DIAGNOSTIC_NOT_APPLICABLE',
    'Wrong actual diagnostic classpath/profile/JDK': 'CONTEXT_CLASSPATH_BINDING',
    'Stock/duplicate classpath supplier': 'CONTEXT_STOCK_OR_DUPLICATE',
    'Wrong explicit class supplier': 'CONTEXT_SUPPLIER',
    'Owned output became a non-directory': 'OWNED_PATH_TYPE',
    'Wrong active-wire static scope': 'STATIC_SCOPE',
    'Malformed active-wire static record': 'STATIC_RECORD_FORMAT',
    'Wrong active-wire static record sequence': 'STATIC_RECORD_SEQUENCE',
    'Wrong active-wire static task/input/configuration': 'STATIC_TASK_INPUTS',
    'Wrong active-wire static policy': 'STATIC_POLICY',
    'Wrong active-wire static tool runtime': 'STATIC_TOOL_RUNTIME',
    'Active-wire checker did not genuinely complete': 'STATIC_COMPLETION',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def require_profile_pins():
    require(BRANCH != 'UNBOUND', 'Public branch binding is UNBOUND')
    require(isinstance(SOURCE_HEAD, str) and re.fullmatch('[0-9a-f]{40}', SOURCE_HEAD)
            and SOURCE_HEAD != '0' * 40, 'Public source binding is UNBOUND')
    require(all(isinstance(sha, str) and re.fullmatch('[0-9a-f]{64}', sha) and sha != '0' * 64
                for sha in (PROFILE_SHA, PROFILE_INIT_SHA)), 'Public profile/init pins are UNBOUND')


def safe(root, relative):
    parts = PurePosixPath(relative).parts
    require(isinstance(relative, str) and parts and not relative.startswith('/')
            and all(p not in ('', '.', '..') for p in parts) and '\\' not in relative
            and PurePosixPath(relative).as_posix() == relative, 'Unsafe bound path')
    path = root
    for part in parts:
        path = path / part
        require(not path.is_symlink(), 'Symlink in bound input/output')
    return path


def digest(path, limit=512 * 1024**2):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= limit, 'Missing/oversized regular input')
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def read_json(path):
    digest(path, 16 * 1024**2)
    return json.loads(path.read_text(), object_pairs_hook=unique)


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def remove_owned(root, relative):
    path = safe(root, relative)
    if path.exists():
        require(path.is_dir(), 'Owned output became a non-directory')
        shutil.rmtree(path)


def verify_runtime_inputs(profile):
    manifest_path = safe(ADMIN, 'inputs/local-inputs.sha256')
    require(digest(manifest_path, 16384) == LOCAL_SHA, 'Changed exact local input manifest')
    entries = [line.split('  ', 1) for line in manifest_path.read_text(encoding='ascii').splitlines()]
    require(len(entries) == 18 and all(len(row) == 2 for row in entries)
            and len({row[1] for row in entries}) == 18, 'Expected the exact 18-member manifest')
    expected = {'inputs/repository/' + relative for sha, relative in entries}
    expected.update((profile['final_jar']['path'], profile['checker']['path']))
    runtime = profile['runtime_inputs']
    require(len(runtime) == len(expected) == 20 and set(runtime) == expected
            and sum(row['bytes'] for row in runtime.values()) == 1845531, 'Wrong exact20 runtime input closure')
    for sha, relative in entries:
        require(re.fullmatch('[0-9a-f]{64}', sha) and relative.startswith('me/manga/kira/source/')
                and runtime['inputs/repository/' + relative]['sha256'] == sha, 'Unsafe local input')
        safe(ADMIN / 'inputs/repository', relative)
    require(profile['final_jar'] == {
                'sha256': '50f7dc4a6314cc5105f4990be26ba8be65ae242e1f7e62652cc60d6a2c0e03df',
                'bytes': 1200036, 'qualification': 'UNQUALIFIED',
                'path': 'inputs/postgresql-42.7.12-kira.1-osgi.jar'}
            and profile['checker'] == {
                'sha256': '857658c8bdcbff794949a8d9922f88d63ee3751c203c7cd7d21ea97f9e745288',
                'bytes': 241885, 'path': 'inputs/checker-qual-3.55.1.jar'}, 'Changed explicit unqualified driver/Checker')
    for relative, row in runtime.items():
        path = safe(ADMIN, relative)
        require(path.stat().st_size == row['bytes'] and digest(path) == row['sha256'], 'Changed exact runtime input')
    require(profile['original_init'] == {'path': 'profile/w01-local-dependencies.init.gradle', 'sha256': INIT_SHA}
            and digest(safe(ADMIN, profile['original_init']['path']), 16384) == INIT_SHA, 'Changed frozen local input init')
    require(profile['local_repository'] == {'path': 'inputs/repository', 'manifest': 'inputs/local-inputs.sha256',
                'manifest_sha256': LOCAL_SHA, 'file_count': 18, 'bytes': 403610}, 'Changed local repository identity')
    actual = set()
    for folder, directories, files in os.walk(ADMIN / 'inputs', followlinks=False):
        require(not any((Path(folder) / name).is_symlink() for name in directories + files), 'Symlink in bound input/output')
        actual.update((Path(folder) / name).relative_to(ADMIN).as_posix() for name in files)
        require(len(actual) <= 21, 'Extra staged runtime input')
    require(actual == expected | {'inputs/local-inputs.sha256'}, 'Extra/missing staged runtime input')
    return entries


def verify_source_profile(profile):
    require(profile['source_inventory'] == {
        'kind': 'public-source-tree-and-content-pins', 'source_sha': SOURCE_HEAD,
        'source_tree': SOURCE_TREE, 'source_parent': PUBLIC_PARENT, 'pinned_file_count': 83,
        'pins_sha256': SOURCE_PINS_SHA, 'expected_test_count': 1}, 'Wrong public source content binding')
    pins = profile['source_pins']
    require(len(pins) == 83 and all(isinstance(sha, str) and re.fullmatch('[0-9a-f]{64}', sha)
            and (name.startswith('src/') or name.startswith('vendor/pgjdbc-owned-cut/')) for name, sha in pins.items()),
            'Wrong reviewed83 source pin set')
    data = ''.join(name + '\t' + pins[name] + '\n' for name in sorted(pins)).encode('utf-8')
    require(hashlib.sha256(data).hexdigest() == SOURCE_PINS_SHA, 'Changed reviewed83 source pin map')
    return pins


def request():
    require_profile_pins()  # No helper, checkout target or runtime acquisition before primary binding.
    value = read_json(ADMIN / REQUEST_PATH)
    require(set(value) == {'schema', 'authorized', 'acquisition_authorization', 'attempts', 'backend_sha',
                          'backend_tree', 'budgets', 'tool_sha256'}, 'Unknown/missing public request field')
    require(value['schema'] == REQUEST_SCHEMA and value['authorized'] is True
            and value['acquisition_authorization'] == 'PUBLIC_ACTIVE_WIRE_CANCEL1_STATIC2_TEST1_GRADLE_NATIVE05_W01_DOCKER_ONLY'
            and type(value['attempts']) is int and value['attempts'] == 1,
            'Primary execution/acquisition authority and one attempt are required')
    require(value['backend_sha'] == SOURCE_HEAD and value['backend_tree'] == SOURCE_TREE, 'Unbound public source checkpoint')
    require(value['budgets'] == {'job_minutes': 25, 'controller_seconds': 1200,
                                'preflight_seconds': 120, 'validation_seconds': 900, 'cleanup_seconds': 180},
            'Separately approved fixed phase/total budgets required')
    context = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': REPOSITORY,
               'GITHUB_REF': 'refs/heads/' + BRANCH, 'GITHUB_EVENT_NAME': 'push',
               'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64',
               'RUNNER_ENVIRONMENT': 'github-hosted',
               'GITHUB_WORKFLOW_REF': REPOSITORY + '/.github/workflows/app29-gate-b.yml@refs/heads/' + BRANCH}
    require(all(os.environ.get(key) == item for key, item in context.items()), 'Wrong public hosted invocation')
    require(re.fullmatch('[1-9][0-9]*', os.environ.get('GITHUB_RUN_ID', ''))
            and re.fullmatch('[0-9a-f]{40}', os.environ.get('GITHUB_SHA', ''))
            and os.environ['GITHUB_SHA'] != '0' * 40
            and os.environ.get('GITHUB_WORKFLOW_SHA') == os.environ['GITHUB_SHA'], 'Wrong run/workflow carrier identity')
    event = read_json(Path(os.environ['GITHUB_EVENT_PATH']))
    require(event['repository']['private'] is False and event['repository']['full_name'] == REPOSITORY
            and event['after'] == event['head_commit']['id'] == os.environ['GITHUB_SHA']
            and event['ref'] == os.environ['GITHUB_REF'] and event['deleted'] is False and event['forced'] is False,
            'Wrong public push event')
    workspace = Path(os.environ['GITHUB_WORKSPACE']).resolve()
    require(ADMIN == workspace / 'control' and BACKEND == workspace / 'backend', 'Wrong separate control/source checkouts')
    require(set(value['tool_sha256']) == set(CONTROL_PATHS), 'Wrong dedicated tooling inventory')
    for relative, sha in value['tool_sha256'].items():
        require(isinstance(sha, str) and re.fullmatch('[0-9a-f]{64}', sha)
                and digest(safe(ADMIN, relative)) == sha, 'Changed dedicated tool')
    require(digest(safe(ADMIN, PROFILE_FILE)) == PROFILE_SHA and digest(safe(ADMIN, PROFILE_INIT)) == PROFILE_INIT_SHA
            and digest(ADMIN / 'ci/app29_owned_children.py') == OWNER_SHA, 'Changed profile/init/owned helper')
    profile = read_json(safe(ADMIN, PROFILE_FILE))
    verify_source_profile(profile)
    verify_runtime_inputs(profile)
    return value


def stage_inputs(w01, profile):
    # The two explicit classpath providers stay in the immutable carrier, as in the donor.
    # Stage only the exact18 Maven files plus their original manifest/init into owned scratch.
    entries = verify_runtime_inputs(profile)
    (w01 / 'local-inputs.sha256').write_bytes(safe(ADMIN, 'inputs/local-inputs.sha256').read_bytes())
    (w01 / 'original.init.gradle').write_bytes(safe(ADMIN, profile['original_init']['path']).read_bytes())
    staged = ['local-inputs.sha256', 'original.init.gradle']
    for sha, relative in entries:
        destination = safe(w01 / 'repository', relative)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(safe(ADMIN / 'inputs/repository', relative).read_bytes())
        require(digest(destination) == sha, 'Staged input copy drift')
        staged.append('repository/' + relative)
    require(digest(w01 / 'local-inputs.sha256') == LOCAL_SHA and digest(w01 / 'original.init.gradle') == INIT_SHA,
            'Staged input control copy drift')
    return {relative: digest(safe(w01, relative)) for relative in staged}


def bind_source(binding, profile):
    pins = verify_source_profile(profile)
    require({relative: digest(safe(BACKEND, relative)) for relative in pins} == pins, 'Public source content drift')
    verify_runtime_inputs(profile)
    return {'scope': 'CURRENT_PUBLIC_SOURCE_AND_RUNTIME_BYTES_ONLY_NOT_QUALIFICATION',
            'source_inventory': profile['source_inventory'], 'runtime_file_count': 20, 'runtime_bytes': 1845531,
            'profile_sha256': PROFILE_SHA, 'tool_sha256': binding['tool_sha256']}


class Gate:
    def __init__(self, binding):
        require_profile_pins()  # Also refuse direct execute() use while the author pins are unbound.
        from app29_owned_children import OwnedChildren  # Hash checked first; immutable import has no actions.
        self.binding, self.owner = binding, None
        self.start = time.monotonic()
        self.end = self.start + binding['budgets']['controller_seconds']
        self.phase_end = self.start + binding['budgets']['preflight_seconds']
        self.cancelled, self.started, self.clean_daemon, self.events_since = False, False, False, None
        self.run = Path(os.environ['RUNNER_TEMP']) / ('app29-active-wire-cancel-01-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT'])
        self.run.mkdir(mode=0o700, exist_ok=False)
        for name in ('reports', 'gradle', 'w01', 'tmp', 'home', 'project-cache', 'kotlin-cache'):
            (self.run / name).mkdir(mode=0o700)
        self.reports, self.w01 = self.run / 'reports', self.run / 'w01'
        self.result = {'status': 'FAIL', 'gate': GATE, 'backend_sha': binding['backend_sha'],
                       'carrier_sha': os.environ['GITHUB_SHA'], 'commands': [], 'failures': [], 'processes': [],
                       'containers': 'UNKNOWN', 'container_topology_complete': False, 'outputs_absent': False,
                       'capture_complete': False, 'required_capture_complete': False, 'capture_roots': {},
                       'captured_file_hashes': {}, 'owned_output_cleanup': {}, 'diagnostics': {'status': 'NOT_EVALUATED'},
                       'consumer': {'status': 'NOT_EVALUATED', 'scope': 'OUTSIDE_ACTIVE_WIRE_CANCEL_VALIDATION'}}
        self.java = Path(os.environ['JAVA_HOME']).resolve()
        self.env = {'PATH': str(self.java / 'bin') + ':/usr/bin:/bin', 'JAVA_HOME': str(self.java),
                    'HOME': str(self.run / 'home'), 'GRADLE_USER_HOME': str(self.run / 'gradle'),
                    'W01_RUN': str(self.w01), 'TMPDIR': str(self.run / 'tmp'), 'LANG': 'C.UTF-8', 'TZ': 'UTC',
                    'GATE_B_ADMIN': str(ADMIN), 'GATE_B_BACKEND': str(BACKEND), 'GATE_B_RUN': str(self.run),
                    'DOCKER_HOST': 'unix:///var/run/docker.sock', 'TESTCONTAINERS_REUSE_ENABLE': 'false',
                    'TESTCONTAINERS_RYUK_DISABLED': 'false'}
        self.owner_type = OwnedChildren  # Acquisition happens inside execute's protected try/finally.
        save(self.reports / 'request.json', binding)
        save(self.reports / 'result.json', self.result)

    def fail(self, stage, error):
        row = {'stage': stage, 'type': type(error).__name__}
        # Never retain exception text; only translate exact literal require() guards.
        if type(error) is ValueError and len(error.args) == 1 and type(error.args[0]) is str:
            guard = GUARD_IDS.get(error.args[0])
            if guard is not None:
                row['guard'] = guard
        if stage == 'diagnostics' and type(error) is ET.ParseError:
            row['guard'] = 'XML_PARSE'
        self.result['failures'].append(row)

    def attempt(self, stage, action):
        try:
            return action()
        except BaseException as error:
            self.fail(stage, error)
            return None

    def cleanup_outputs(self, root, paths, stage, allowed, skip_reason):
        for relative in paths:
            row = {'cleanup_stage': stage, 'cleanup_attempted': bool(allowed), 'cleanup_complete': False,
                   'cleanup_skipped_reason': None if allowed else skip_reason, 'absent': None}
            self.result['owned_output_cleanup'][str(root / relative)] = row
            if allowed:
                row['cleanup_complete'] = self.attempt(stage, lambda p=relative: (remove_owned(root, p), True)[1]) is True

    def output_absence(self):
        for root, paths in ((BACKEND, CACHE_PATHS), (self.run, ('w01', 'project-cache', 'kotlin-cache', 'gradle', 'tmp', 'home'))):
            for relative in paths:
                row = self.result['owned_output_cleanup'][str(root / relative)]
                row['absent'] = self.attempt('output-absence', lambda r=root, p=relative: not safe(r, p).exists())
        return all(row['absent'] is True for row in self.result['owned_output_cleanup'].values())

    def drain(self, stage):
        receipt = self.attempt(stage, self.owner.drain) if self.owner is not None else None
        absent = isinstance(receipt, dict) and receipt.get('ok') is True
        outcome = 'UNKNOWN' if not absent else ('FORCED_ABSENT' if receipt.get('term') or receipt.get('kill') else 'NORMAL_ABSENT')
        self.result['processes'].append({'stage': stage, 'outcome': outcome, 'receipt': receipt})
        if outcome != 'NORMAL_ABSENT':
            self.fail(stage, RuntimeError())
        if absent and any(row.get('actual_exit') != 0 for row in receipt.get('leaders', []) + receipt.get('adopted', [])):
            self.fail(stage + '-nonzero-child', RuntimeError())
        return absent

    def command(self, name, argv, seconds=30, cleaning=False, cwd=BACKEND, api=False):
        require(self.owner is not None, 'No owned child capability')
        deadline = min(self.end, time.monotonic() + seconds, self.end if cleaning else self.phase_end)
        require(deadline > time.monotonic() and (cleaning or not self.cancelled), 'Phase/cancellation budget exhausted')
        path = self.reports / (name + '.log')
        entry = {'name': name, 'argv': argv, 'cwd': str(cwd), 'actual_exit': None, 'outcome': 'UNKNOWN'}
        self.result['commands'].append(entry)
        save(self.reports / 'result.json', self.result)
        with path.open('xb') as log:
            process = subprocess.Popen(argv, cwd=cwd, env=self.env | ({'DOCKER_API_VERSION': '1.32'} if api else {}),
                                       stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            self.owner.active[process.pid] = process  # Protect the handle BEFORE any fallible collection/publication.
            entry['pid'] = process.pid
            try:
                while process.poll() is None:
                    require(time.monotonic() < deadline and (cleaning or not self.cancelled), 'Command deadline/cancellation')
                    require(path.stat().st_size <= 64 * 1024**2, 'Command output limit')
                    time.sleep(0.05)
                entry['actual_exit'] = process.returncode
                require(process.returncode == 0, 'Nonzero owned command')
                entry['outcome'] = 'NORMAL'
            except BaseException:
                self.drain('interrupted-' + name)
                entry['actual_exit'] = process.returncode
                raise
        digest(path, 64 * 1024**2)
        return path.read_bytes()

    def carrier(self, label, cleaning=False):
        carrier = self.command(label + '-carrier-sha', ['git', 'rev-parse', 'HEAD'], cwd=ADMIN, cleaning=cleaning).decode().strip()
        commit = self.command(label + '-carrier-commit', ['git', '--no-replace-objects', 'cat-file', 'commit', 'HEAD'],
                              cwd=ADMIN, cleaning=cleaning)
        header, separator, _ = commit.partition(b'\n\n')
        parents = [row[7:].decode('ascii') for row in header.split(b'\n') if row.startswith(b'parent ')]
        source_tree = self.command(label + '-carrier-source-tree',
                                  ['git', '--no-replace-objects', 'rev-parse', SOURCE_HEAD + '^{tree}'],
                                  cwd=ADMIN, cleaning=cleaning).decode().strip()
        require(carrier == os.environ['GITHUB_SHA'] and separator and parents == [SOURCE_HEAD]
                and source_tree == SOURCE_TREE, 'Carrier must add only controls/inputs to the exact public source')
        profile = read_json(safe(ADMIN, PROFILE_FILE))
        expected = set(CONTROL_PATHS) | {REQUEST_PATH} | set(profile['runtime_inputs'])
        require(len(expected) == 28, 'Wrong public addition roster')
        changes = self.command(label + '-carrier-additions',
                               ['git', '--no-replace-objects', 'diff-tree', '--no-commit-id', '--name-status',
                                '--no-renames', '-r', SOURCE_HEAD, 'HEAD'], cwd=ADMIN, cleaning=cleaning).decode().splitlines()
        require(sorted(changes) == sorted('A\t' + name for name in expected), 'Carrier has extra/missing/non-addition changes')
        require(not self.command(label + '-carrier-status', ['git', 'status', '--porcelain=v1', '--untracked-files=all'],
                                 cwd=ADMIN, cleaning=cleaning), 'Carrier is dirty')
        self.result.setdefault('carrier_binding', {})[label] = {
            'carrier_sha': carrier, 'source_sha': SOURCE_HEAD, 'source_tree': source_tree,
            'added_path_count': len(expected), 'added_paths': sorted(expected)}
        return True

    def materialize_source(self):
        # The workflow already checked out this public commit. No import/fetch/private history.
        initial = {
            'head': self.command('initial-backend-sha', ['git', 'rev-parse', 'HEAD']).decode().strip(),
            'branch': self.command('initial-backend-branch', ['git', 'branch', '--show-current']).decode().strip(),
            'status': self.command('initial-backend-status', ['git', 'status', '--porcelain=v1', '--untracked-files=all']).decode()}
        self.result['source_checkout_initial'] = initial
        require(initial == {'head': SOURCE_HEAD, 'branch': '', 'status': ''},
                'Require exact clean detached public source')

    def sources(self, label, cleaning=False):
        sha = self.command(label + '-backend-sha', ['git', 'rev-parse', 'HEAD'], cleaning=cleaning).decode().strip()
        branch = self.command(label + '-backend-branch', ['git', 'branch', '--show-current'], cleaning=cleaning).decode().strip()
        commit = self.command(label + '-backend-commit', ['git', '--no-replace-objects', 'cat-file', 'commit', 'HEAD'], cleaning=cleaning)
        header, separator, _ = commit.partition(b'\n\n')
        parents = [row[7:].decode('ascii') for row in header.split(b'\n') if row.startswith(b'parent ')]
        trees = [row[5:].decode('ascii') for row in header.split(b'\n') if row.startswith(b'tree ')]
        self.result.setdefault('source_checkout_final', {})[label] = {
            'head': sha, 'branch': branch, 'parents': parents, 'trees': trees}
        require(sha == self.binding['backend_sha'] == SOURCE_HEAD and branch == ''
                and separator and parents == [PUBLIC_PARENT] and trees == [SOURCE_TREE],
                'Require exact public source tip/tree with sole public parent')
        require(not self.command(label + '-status', ['git', 'status', '--porcelain=v1', '--untracked-files=all'], cleaning=cleaning), 'Checkpoint is dirty')
        names = self.command(label + '-tracked', ['git', 'ls-files', '-z'], cleaning=cleaning).decode().split('\0')[:-1]
        hashes = {p: digest(safe(BACKEND, p)) for p in names}
        actual = set()
        for folder, directories, files in os.walk(BACKEND, followlinks=False):
            relative = Path(folder).relative_to(BACKEND)
            directories[:] = [d for d in directories if (relative / d).as_posix() not in ('.git', *CACHE_PATHS)]
            require(not any((Path(folder) / d).is_symlink() for d in directories), 'Unexpected source directory link')
            actual.update((relative / f).as_posix() for f in files)
        require(actual == set(hashes), 'Extra/untracked/ignored effective source input')
        self.result['source_checkout_final'][label]['tracked_path_count'] = len(hashes)
        profile = read_json(safe(ADMIN, PROFILE_FILE))
        pins = verify_source_profile(profile)
        require(all(hashes.get(name) == value for name, value in pins.items()), 'Reviewed public source content drift')
        full_map = ''.join(name + '\t' + hashes[name] + '\n' for name in sorted(hashes)).encode('utf-8')
        # Compare the full current tracked map in memory; publish only its aggregate and the reviewed82 subset.
        save(self.reports / (label + '-source-hashes.json'), {
            'source_sha': sha, 'source_tree': SOURCE_TREE, 'source_parent': PUBLIC_PARENT,
            'tracked_path_count': len(hashes), 'full_map_sha256': hashlib.sha256(full_map).hexdigest(),
            'source_pins': pins, 'pins_sha256': SOURCE_PINS_SHA})
        return hashes

    def census(self, label, cleaning=True):
        data = self.command(label, ['docker', 'ps', '-aq', '--no-trunc'], cleaning=cleaning, api=True).decode().splitlines()
        require(all(re.fullmatch('[0-9a-f]{64}', cid) for cid in data) and len(data) == len(set(data)), 'Invalid container census')
        return set(data)

    def containers(self):
        require(self.clean_daemon and self.events_since is not None, 'No clean dedicated-daemon/run binding')
        until = time.monotonic() + 20
        for index in range(21):
            remaining = self.census('containers-normal-' + str(index))
            if not remaining or time.monotonic() >= until:
                break
            time.sleep(1)
        journal = self.command('container-events', ['docker', 'events', '--since', str(self.events_since),
                               '--until', str(int(time.time()) + 1), '--filter', 'type=container', '--format', '{{json .}}'],
                               cleaning=True, api=True).decode().splitlines()
        created, destroyed = {}, set()
        for line in journal:
            event = json.loads(line, object_pairs_hook=unique)
            if event.get('Action') not in ('create', 'destroy'):
                continue
            actor = event['Actor']
            cid, labels = actor['ID'], actor['Attributes']
            require(re.fullmatch('[0-9a-f]{64}', cid) and labels.get('image') in IMAGES
                    and labels.get('org.testcontainers') == 'true' and labels.get('org.testcontainers.version') == '1.21.4',
                    'Unknown container custody; do not remove')
            if event['Action'] == 'create':
                require(cid not in created, 'Duplicate container creation identity')
                created[cid] = labels
            else:
                destroyed.add(cid)
        if not created:
            require(not journal and not destroyed and not remaining, 'Incomplete/unexpected container journal')
            # Compilation may fail before any fixture exists: absence permits disposal, not test success.
            self.fail('container-required-topology', ValueError('Required PostgreSQL/Ryuk topology missing'))
            save(self.reports / 'owned-containers.json', {'created': created, 'destroyed': [], 'remaining': []})
            require(not self.census('containers-after-cleanup'), 'Container absence unproved')
            self.result['containers'] = 'NORMAL_ABSENT'
            return True
        # The sole selected method uses one PER_CLASS fixture; paired pools do not create PostgreSQL containers.
        require(len(created) == 2 and sum(r['image'] == 'postgres:17.6-alpine' for r in created.values()) == 1
                and sum(r['image'] == 'testcontainers/ryuk:0.12.0' for r in created.values()) == 1
                and destroyed <= created.keys() and remaining <= created.keys(), 'Incomplete/unexpected container journal')
        pg = [r for r in created.values() if r['image'] == 'postgres:17.6-alpine']
        ryuk = next(r for r in created.values() if r['image'] != 'postgres:17.6-alpine')
        sessions = {row.get('org.testcontainers.sessionId', '') for row in pg}
        require(len(sessions) == 1, 'The selected class fixture must belong to the one Test worker session')
        session = sessions.pop()
        require(re.fullmatch('[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', session)
                and ryuk.get('name') == 'testcontainers-ryuk-' + session, 'Unknown fixture/Ryuk session')
        save(self.reports / 'owned-containers.json', {'created': created, 'destroyed': sorted(destroyed), 'remaining': sorted(remaining)})
        for index, image in enumerate(sorted(IMAGES)):
            raw = self.command('image-' + str(index), ['docker', 'image', 'inspect', '--format',
                               '{"id":{{json .Id}},"tags":{{json .RepoTags}},"digests":{{json .RepoDigests}}}', image], cleaning=True, api=True)
            identity = json.loads(raw, object_pairs_hook=unique)
            require(re.fullmatch('sha256:[0-9a-f]{64}', identity['id']) and image in identity['tags']
                    and identity['digests'] and all(re.fullmatch(r'[^\s]+@sha256:[0-9a-f]{64}', d) for d in identity['digests']),
                    'Incomplete resolved image identity')
        self.result['container_topology_complete'] = True
        if remaining:
            self.result['containers'] = 'FORCED_PENDING'
            self.result['container_forced'] = True
            self.fail('container-normal-cleanup', RuntimeError())
            # Known created IDs only; clean dedicated daemon and same Testcontainers session are prerequisites.
            self.command('remove-owned-containers', ['docker', 'rm', '-fv', *sorted(remaining)], cleaning=True, api=True)
        else:
            require(destroyed == created.keys(), 'Missing normal destroy events')
        require(not self.census('containers-after-cleanup'), 'Container absence unproved')
        self.result['containers'] = 'FORCED_ABSENT' if remaining else 'NORMAL_ABSENT'
        return True


def capture_reports(gate):
    inventory = gate.result['captured_file_hashes']
    roots = gate.result['capture_roots']
    build = safe(gate.run, 'w01/backend-build')
    reports = safe(gate.run, 'reports')

    def copy_selected(source_relative, destination_relative):
        require(destination_relative in PUBLIC_FILES, 'Unlisted public evidence destination')
        path = safe(build, source_relative)
        try:
            mode = path.lstat().st_mode
        except FileNotFoundError:
            return
        require(stat.S_ISREG(mode), 'Report entry is not a regular file/directory')
        row = {'bytes': None, 'sha256': None, 'source_relative': source_relative}
        inventory[destination_relative] = row
        before = digest(path, 64 * 1024**2)
        row['bytes'] = path.stat().st_size
        destination = safe(reports, destination_relative)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        require(digest(destination, 64 * 1024**2) == before == digest(path, 64 * 1024**2), 'Evidence copy drift')
        row['sha256'] = before

    def flat_entries(relative):
        folder = safe(build, relative)
        try:
            mode = folder.lstat().st_mode
        except FileNotFoundError:
            return []
        require(stat.S_ISDIR(mode), 'Required test/report root missing')
        entries = sorted(folder.iterdir())
        for path in entries:
            mode = path.lstat().st_mode
            require(not stat.S_ISLNK(mode), 'Report link')
            require(stat.S_ISDIR(mode) or stat.S_ISREG(mode), 'Report entry is not a regular file/directory')
        return entries

    for root_name in ('test-results', 'reports'):
        roots[root_name] = 'UNKNOWN'
        source = safe(build, root_name)
        try:
            mode = source.lstat().st_mode
        except FileNotFoundError:
            roots[root_name] = 'ABSENT'
            continue
        require(stat.S_ISDIR(mode), 'Required test/report root missing')
        if root_name == 'test-results':
            entries = flat_entries('test-results/test')
            copy_selected(XML_PATH, XML_PATH)
            # Never turn extra actual tests into an apparently one-case run by filtering XML away.
            require(all(path.name == PurePosixPath(XML_PATH).name for path in entries if path.suffix == '.xml'),
                    'Wrong diagnostic XML inventory')
        else:
            entries = flat_entries(CAPTURE_FOLDER)
            for name in ('effective-classpath.txt', 'worker-runtime.txt'):
                copy_selected(CAPTURE_FOLDER + '/' + name, CAPTURE_FOLDER + '/' + name)
            class_logs = [path for path in entries if path.name.startswith('class-load')]
            require(len(class_logs) <= 1 and all(re.fullmatch(r'class-load-[1-9][0-9]*\.txt', path.name)
                                               for path in class_logs), 'Required raw evidence inventory missing')
            for path in class_logs:
                copy_selected(path.relative_to(build).as_posix(), CAPTURE_FOLDER + '/class-load.txt')
        roots[root_name] = 'CAPTURED'
    # All available selected evidence copied, or its root positively absent; required success evidence is separate.
    return True


def required_capture_inventory(gate, profile):
    inventory = gate.result['captured_file_hashes']
    require(gate.result['capture_roots'] == {'test-results': 'CAPTURED', 'reports': 'CAPTURED'},
            'Required test/report root missing')
    require(profile['report_directory'] == CAPTURE_FOLDER, 'Wrong fixed report directory')
    required = {XML_PATH, CAPTURE_FOLDER + '/effective-classpath.txt', CAPTURE_FOLDER + '/class-load.txt'}
    class_log = inventory.get(CAPTURE_FOLDER + '/class-load.txt', {})
    original_name = class_log.get('source_relative', '')
    # The public alias retains exact bytes/hash and the original one-worker PID filename, never a fabricated witness.
    require(required <= inventory.keys()
            and PurePosixPath(original_name).parent.as_posix() == CAPTURE_FOLDER
            and re.fullmatch(r'class-load-[1-9][0-9]*\.txt', PurePosixPath(original_name).name),
            'Required raw evidence inventory missing')
    return True


def inventory_public_reports(gate):
    inventory = gate.result['retained_file_hashes']
    total = PUBLIC_FILES['result.json']  # Reserve the final result bound before final serialization.
    reports = safe(gate.run, 'reports')
    for relative, limit in PUBLIC_FILES.items():
        if relative == 'result.json':
            continue
        row = {'bytes': None, 'sha256': None, 'error': None}
        try:
            path = safe(reports, relative)
            try:
                mode = path.lstat().st_mode
            except FileNotFoundError:
                continue  # Availability is not required-success inventory.
            inventory[relative] = row
            require(stat.S_ISREG(mode), 'Retained evidence is not a regular file')
            row['bytes'] = path.stat().st_size
            total += row['bytes']
            row['sha256'] = digest(path, limit)
            if relative in gate.result['captured_file_hashes']:
                require(row['sha256'] == gate.result['captured_file_hashes'][relative]['sha256'], 'Evidence copy drift')
        except BaseException as error:
            row['error'] = type(error).__name__
            inventory[relative] = row
            gate.fail('retained-file:' + relative, error)
    gate.result['public_retention']['bytes_with_result_reserve'] = total
    require(total <= PUBLIC_TOTAL_BYTES, 'Public evidence total byte limit')
    return all(row['error'] is None for row in inventory.values())


def static_diagnostics(reports, profile):
    checks = profile['static_checks']
    tasks = [':runKtlintCheckOverTestSourceSet', ':ktlintTestSourceSetCheck', ':detekt']
    require(checks['requested_tasks'] == TASKS[:2] and list(checks['source_tasks']) == tasks
            and len(checks['source_paths']) == len(set(checks['source_paths'])) == 2, 'Wrong active-wire static scope')
    sources = {path: profile['source_pins'][path] for path in checks['source_paths']}
    log = safe(reports, 'gradle-test.log')
    digest(log, PUBLIC_FILES['gradle-test.log'])
    marker, records = checks['log_marker'], []
    for line in log.read_text(encoding='utf-8').splitlines():
        if not line.startswith(marker.rstrip()):
            continue
        require(line.startswith(marker) and len(line) <= 16384, 'Malformed active-wire static record')
        try:
            row = json.loads(line[len(marker):], object_pairs_hook=unique)
        except (ValueError, TypeError):
            raise ValueError('Malformed active-wire static record') from None
        require(type(row) is dict and len(records) < 6, 'Malformed active-wire static record')
        records.append(row)
    require([(row.get('task'), row.get('phase')) for row in records] ==
            [(task, phase) for task in tasks for phase in ('before-task-action', 'after-task')],
            'Wrong active-wire static record sequence')
    runtime, task_types, completions = None, {}, []
    for before, after in zip(records[::2], records[1::2]):
        task = before['task']
        prefix = checks['task_type_prefixes'][task]
        require(set(before) == {'schema', 'phase', 'task', 'task_type', 'source_task', 'source_sha256',
                                'configuration_sha256', 'policy', 'tool_runtime'}
                and before['schema'] == checks['log_schema'] and before['task_type'] in (prefix, prefix + '_Decorated')
                and before['source_task'] == checks['source_tasks'][task] and before['source_sha256'] == sources
                and before['configuration_sha256'] == checks['configuration_sha256'],
                'Wrong active-wire static task/input/configuration')
        require(before['policy'] == checks['policy']
                and all(type(before['policy'][key]) is type(value) for key, value in checks['policy'].items()),
                'Wrong active-wire static policy')
        actual = before['tool_runtime']
        require(type(actual) is dict and set(actual) == {'ktlint_engine', 'detekt_cli', 'detekt_kotlin_components',
                                                        'detekt_classpath_matches_configuration', 'detekt_plugin_classpath_empty'}
                and actual['ktlint_engine'] == 'com.pinterest.ktlint:ktlint-rule-engine:1.8.0'
                and actual['detekt_cli'] == 'io.gitlab.arturbosch.detekt:detekt-cli:1.23.8'
                and actual['detekt_classpath_matches_configuration'] is True
                and actual['detekt_plugin_classpath_empty'] is True, 'Wrong active-wire static tool runtime')
        components = actual['detekt_kotlin_components']
        require(type(components) is list and 1 <= len(components) <= 32
                and all(isinstance(item, str) and re.fullmatch(
                    r'org\.jetbrains\.kotlin:[a-z0-9][a-z0-9-]*:' + re.escape(checks['detekt_kotlin_version']), item)
                        for item in components)
                and components == sorted(set(components))
                and 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21' in components
                and (runtime is None or actual == runtime), 'Wrong active-wire static tool runtime')
        runtime, task_types[task] = actual, before['task_type']
        positive = ('started', 'action_completed', 'executed', 'did_work')
        negative = ('skipped', 'no_source', 'up_to_date', 'failed')
        require(set(after) == {'schema', 'phase', 'task', 'skip_message'} | set(positive) | set(negative)
                and after['schema'] == checks['log_schema'] and after['skip_message'] is None
                and all(after[key] is True for key in positive) and all(after[key] is False for key in negative),
                'Active-wire checker did not genuinely complete')
        completions.append(after)
    return {'status': 'PASS', 'evidence': 'gradle-test.log', 'record_count': len(records),
            'requested_tasks': checks['requested_tasks'], 'source_sha256': sources, 'task_types': task_types,
            'configuration_sha256': checks['configuration_sha256'], 'policy': checks['policy'],
            'tool_runtime': runtime, 'completions': completions}


def diagnostics(reports, profile, java):
    static_checks = static_diagnostics(reports, profile)
    xmls = list((reports / 'test-results/test').glob('*.xml'))
    expected_files = ['TEST-' + name + '.xml' for name in TEST_COUNTS]
    require(sorted(p.name for p in xmls) == sorted(expected_files) == sorted(profile['expected_xml_files']),
            'Wrong diagnostic XML inventory')
    expected = [(row['class'], row['display_name']) for row in EXPECTED_TESTS]
    require(profile['expected_test_count'] == 1 and profile['test_classes'] == list(TEST_COUNTS)
            and profile['expected_witnesses'] == [] and profile['expected_tests'] == EXPECTED_TESTS,
            'Require exactly the1 fixed active-wire-cancel identity')
    identities, outcomes, suites, suite_results = [], [], [], []
    for name, count in TEST_COUNTS.items():
        path = safe(reports, 'test-results/test/TEST-' + name + '.xml')
        digest(path, 64 * 1024**2)
        raw = path.read_text(encoding='utf-8-sig')
        require('\x00' not in raw and '<!DOCTYPE' not in raw.upper() and '<!ENTITY' not in raw.upper(), 'Unsafe XML')
        suite = ET.fromstring(raw)
        cases = suite.findall('testcase')
        actual = [(case.get('classname'), case.get('name')) for case in cases]
        required = [(row['class'], row['display_name']) for row in EXPECTED_TESTS if row['class'] == name]
        require(suite.tag == 'testsuite' and suite.get('name') == name and suite.get('tests') == str(count)
                and len(cases) == count and sorted(actual) == sorted(required),
                'Missing, extra or duplicate diagnostic testcase')
        suite_outcomes = []
        for case in cases:
            states = [state for tag, state in (('failure', 'FAIL'), ('error', 'ERROR'), ('skipped', 'SKIP')) if case.find(tag) is not None]
            require(len(states) <= 1, 'Contradictory diagnostic testcase outcome')
            suite_outcomes.append({'class': case.get('classname'), 'display_name': case.get('name'),
                                   'status': states[0] if states else 'PASS'})
        suite_counts = {state: sum(row['status'] == state for row in suite_outcomes) for state in ('PASS', 'FAIL', 'ERROR', 'SKIP')}
        require(all(suite.get(field) == str(suite_counts[state]) for field, state in
                    (('failures', 'FAIL'), ('errors', 'ERROR'), ('skipped', 'SKIP'))), 'Diagnostic XML count mismatch')
        identities.extend(actual)
        outcomes.extend(suite_outcomes)
        suites.append(suite)
        suite_results.append({'class': name, 'tests': count, 'counts': suite_counts})
    require(len(identities) == len(set(identities)) == 1 and sorted(identities) == sorted(expected),
            'Missing, extra or duplicate diagnostic testcase')
    counts = {state: sum(row['status'] == state for row in outcomes) for state in ('PASS', 'FAIL', 'ERROR', 'SKIP')}
    phases = ('BEFORE_SHUTDOWN', 'AFTER_SHUTDOWN', 'OBSERVATION_FAILED')
    scalars = set(('budget_remaining_ms invocation_result first_close observation actor_capacity actor_retained '
                   'actor_constructing actor_retired actor_factory_sealed actor_fault future_lease_entries active_operations '
                   'close_queue_size close_pool_size close_active_count close_completed_tasks close_shutdown close_terminated').split())
    records = []
    for suite in suites:
        for output in (*suite.iter('system-out'), *suite.iter('system-err')):
            for line in (output.text or '').splitlines():
                if not line.startswith('OWNED_CUT_SHUTDOWN_DIAGNOSTIC'):
                    continue
                tokens = line.split()
                pairs = [token.split('=', 1) for token in tokens[1:]]
                require(tokens[0] == 'OWNED_CUT_SHUTDOWN_DIAGNOSTIC' and all(len(pair) == 2 for pair in pairs),
                        'Malformed fixed diagnostic record')
                fields = unique(pairs)
                require(fields.get('case') in DIAGNOSTIC_CASES and fields.get('phase') in phases
                        and fields.get('status') in ('CAPTURED', 'UNAVAILABLE')
                        and fields.keys() == {'case', 'phase', 'status'} | (scalars if fields.get('status') == 'CAPTURED' else set())
                        and all(re.fullmatch(r'[A-Z][A-Z0-9_]*|-?[0-9]+|true|false', value) for value in fields.values()),
                        'Wrong fixed diagnostic labels or scalar fields')
                records.append({'raw': line, 'fields': fields})
    require(profile['shutdown_diagnostics'] == {'status': 'NOT_APPLICABLE', 'expected_record_count': 0}
            and not records, 'PG14 fixed diagnostic rows are nonapplicable to this selection')
    coverage = {}  # No required PG14 case/phase slots in the selected suite.
    folder = safe(reports, profile['report_directory'])
    cp = read_json(folder / 'effective-classpath.txt')
    require(cp['profile'] == PROFILE_ID and cp['java_home'] == str(java)
            and cp['source_inventory'] == profile['source_inventory'] and cp['expected_test_count'] == 1
            and cp['expected_witnesses'] == [], 'Wrong actual diagnostic classpath/profile/JDK')
    entries = cp['entries']
    require(len({e['path'] for e in entries}) == len(entries)
            and cp['removed_stock']['sha256'] == profile['removed_stock']['sha256']
            and cp['removed_stock']['path'] not in {e['path'] for e in entries}, 'Stock/duplicate classpath supplier')
    for key, flag in (('final_jar', 'postgres_classes'), ('checker', 'checker_classes')):
        rows = [e for e in entries if e[flag] > 0]
        require(len(rows) == 1 and rows[0]['path'] == str(safe(ADMIN, profile[key]['path']))
                and rows[0]['sha256'] == profile[key]['sha256'], 'Wrong explicit class supplier')
    return {'status': 'RECORDED', 'test_status': 'PASS' if counts == {'PASS': 1, 'FAIL': 0, 'ERROR': 0, 'SKIP': 0} else 'FAIL',
            'tests': 1, 'classes': TEST_COUNTS, 'identities': sorted(identities), 'outcomes': outcomes, 'counts': counts,
            'suites': suite_results, 'profile': PROFILE_ID, 'fixed_scalar_capture': 'NOT_APPLICABLE',
            'raw_shutdown_diagnostics': records, 'diagnostic_coverage': coverage,
            'diagnostic_limit': profile['shutdown_diagnostics_note'], 'diagnostic_scope': profile['shutdown_diagnostics'],
            'candidate_qualification': 'UNQUALIFIED', 'static_checks': static_checks}

def execute(binding):
    gate = Gate(binding)
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: setattr(gate, 'cancelled', True))
    profile, baseline, local, jdk = None, None, None, None
    try:
        gate.owner = gate.owner_type()  # Before the first child, inside failure/cleanup protection.
        require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY
                and os.environ.get('GITHUB_RUN_ATTEMPT') == '1', 'Dedicated public carrier/attempt required')
        require(not any(k in os.environ for k in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')),
                'Inherited Java override is outside this profile')
        require(shutil.disk_usage(gate.run).free >= 8 * 1024**3, 'Require at least 8 GiB free')
        require(not any(safe(BACKEND, p).exists() for p in CACHE_PATHS), 'Preexisting outputs/caches; do not clean')
        gate.carrier('before')
        profile = read_json(safe(ADMIN, PROFILE_FILE))
        require(profile['profile_id'] == PROFILE_ID and profile['task_args'] == TASKS
                and profile['expected_test_count'] == 1 and profile['expected_witnesses'] == []
                and profile['test_selectors'] == TEST_SELECTORS
                and profile['expected_tests'] == EXPECTED_TESTS and profile['test_classes'] == list(TEST_COUNTS)
                and profile['shutdown_diagnostics'] == {'status': 'NOT_APPLICABLE', 'expected_record_count': 0}
                and profile['final_jar']['qualification'] == 'UNQUALIFIED',
                'Wrong exact1 active-wire-cancel profile/task/candidate scope')
        gate.materialize_source()
        baseline = gate.sources('before')
        gate.result['source_comparison_timing'] = 'BEFORE_VALIDATION_VS_AFTER_CAPTURE_BEFORE_SCOPED_OUTPUT_DISPOSAL'
        gate.result['source_binding_before'] = bind_source(binding, profile)
        local = stage_inputs(gate.w01, profile)
        release = (gate.java / 'release').read_text()
        require('IMPLEMENTOR="Eclipse Adoptium"' in release and re.search(r'^JAVA_VERSION="21(?:\.|\")', release, re.MULTILINE), 'Expected hosted Temurin21')
        jdk = {p: digest(safe(gate.java, p)) for p in ('bin/java', 'lib/modules', 'release')}
        save(gate.reports / 'jdk.json', {'home': str(gate.java), 'files': jdk, 'release': release})
        gate.command('jdk-version', [str(gate.java / 'bin/java'), '-Djava.io.tmpdir=' + str(gate.run / 'tmp'),
                                    '-Duser.home=' + str(gate.run / 'home'), '-XshowSettings:properties', '-version'])
        gate.command('docker-version', ['docker', 'version'], api=True)
        require(not gate.census('containers-before', cleaning=False), 'Preexisting containers; refuse daemon custody')
        require(time.monotonic() < gate.phase_end, 'Preflight phase expired')
        gate.clean_daemon = True
        gate.events_since = int(time.time())
        gate.phase_end = min(gate.end - binding['budgets']['cleanup_seconds'], time.monotonic() + binding['budgets']['validation_seconds'])
        gate.started = True
        gate.command('gradle-test', ['./gradlew', '--no-daemon', '--no-parallel', '--no-build-cache', '--no-configuration-cache',
                     '--dependency-verification=strict', '--max-workers=1', '--console=plain', '--project-cache-dir', str(gate.run / 'project-cache'),
                     '-Djava.io.tmpdir=' + str(gate.run / 'tmp'), '-Duser.home=' + str(gate.run / 'home'),
                     '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -Djava.io.tmpdir=' + str(gate.run / 'tmp') + ' -Duser.home=' + str(gate.run / 'home'),
                     '-Dorg.gradle.vfs.watch=false', '-PkiraUseMavenLocal=false', '-Pkotlin.compiler.execution.strategy=in-process',
                     '-Pkotlin.project.persistent.dir=' + str(gate.run / 'kotlin-cache'), '-Porg.gradle.java.installations.auto-download=false',
                     '-Porg.gradle.java.installations.auto-detect=false', '-Porg.gradle.java.installations.paths=' + str(gate.java),
                     '--init-script', str(safe(ADMIN, PROFILE_INIT)), *TASKS], seconds=binding['budgets']['validation_seconds'])
    except BaseException as error:
        gate.fail('validation', error)
    finally:
        gate.phase_end = gate.end  # Cleanup is bounded by the original total; signals only latch failure.
        if gate.started:
            gate.attempt('gradle-stop-immediate', lambda: gate.command('gradle-stop-immediate', ['./gradlew', '--stop'], seconds=60, cleaning=True))
        workers_gone = gate.drain('after-immediate-stop')
        containers_gone = gate.attempt('containers', gate.containers) if workers_gone and gate.started else False
        if workers_gone:
            gate.result['capture_complete'] = gate.attempt('capture-reports', lambda: capture_reports(gate)) is True
            if gate.result['capture_complete']:
                gate.result['required_capture_complete'] = gate.attempt('required-capture-inventory', lambda: required_capture_inventory(gate, profile)) is True
                if gate.result['required_capture_complete']:
                    gate.result['diagnostics'] = gate.attempt('diagnostics', lambda: diagnostics(gate.reports, profile, gate.java)) or {'status': 'FAIL'}
            def postflight():
                require(baseline is not None and gate.sources('after', cleaning=True) == baseline, 'Effective source changed')
                require(request() == binding, 'Initially accepted request changed')
                gate.carrier('after', cleaning=True)
                gate.result['source_binding_after'] = bind_source(binding, profile)
                require(local is not None and all(digest(safe(gate.w01, p)) == sha for p, sha in local.items()), 'Exact staged dependency input drift')
                require(jdk is not None and all(digest(safe(gate.java, p)) == sha for p, sha in jdk.items()), 'JDK input drift')
            gate.result['inputs_preserved'] = gate.attempt('postflight', lambda: (postflight(), True)[1]) is True
        before_file_cleanup = workers_gone and containers_gone and gate.drain('before-file-cleanup')
        files_safe = before_file_cleanup and gate.result['capture_complete']
        file_skip = ('AFTER_IMMEDIATE_STOP_NOT_ABSENT' if not workers_gone else
                     'CONTAINER_CLEANUP_NOT_ABSENT' if not containers_gone else
                     'BEFORE_FILE_CLEANUP_NOT_ABSENT' if not before_file_cleanup else
                     'CAPTURE_INCOMPLETE' if not gate.result['capture_complete'] else None)
        gate.cleanup_outputs(BACKEND, CACHE_PATHS, 'backend-output-cleanup', files_safe, file_skip)
        gate.cleanup_outputs(gate.run, ('w01', 'project-cache', 'kotlin-cache'), 'private-output-cleanup', files_safe, file_skip)
        if gate.started:
            gate.attempt('gradle-stop-final', lambda: gate.command('gradle-stop-final', ['./gradlew', '--stop'], seconds=60, cleaning=True))
        final_children = gate.drain('after-final-stop')
        final_containers = gate.attempt('final-container-census', lambda: not gate.census('containers-final')) if gate.clean_daemon else False
        gate.result['final_children_absent'] = final_children
        gate.result['final_containers_absent'] = final_containers
        if final_containers is not True or not containers_gone:
            gate.result['containers'] = 'UNKNOWN'
        after_commands = gate.drain('before-home-cleanup')
        home_safe = files_safe and final_children and final_containers is True and after_commands
        home_skip = (file_skip if not files_safe else
                     'AFTER_FINAL_STOP_NOT_ABSENT' if not final_children else
                     'FINAL_CONTAINER_CENSUS_NOT_ABSENT' if final_containers is not True else
                     'BEFORE_HOME_CLEANUP_NOT_ABSENT' if not after_commands else None)
        gate.cleanup_outputs(gate.run, ('gradle', 'tmp', 'home'), 'private-home-cleanup', home_safe, home_skip)
        gate.result['outputs_absent'] = gate.attempt('output-absence', gate.output_absence) is True
        gate.result['cancelled'] = gate.cancelled
        gate.result['retained_file_hashes'] = {}
        gate.result['public_retention'] = {
            'retention_ready': False, 'literal_file_limits': PUBLIC_FILES,
            'total_byte_limit': PUBLIC_TOTAL_BYTES, 'result_byte_reserve': PUBLIC_FILES['result.json'],
            'scope': 'CURRENT_RUN_LITERAL_FILES_ONLY_NO_RECURSIVE_OR_HISTORICAL_PAYLOAD',
            'owned_writers_absent_after_commands': after_commands}
        gate.result['retained_inventory_complete'] = gate.attempt(
            'retained-file-inventory', lambda: inventory_public_reports(gate)) is True
        gate.result['elapsed_seconds'] = time.monotonic() - gate.start
        passed = (not gate.result['failures'] and not gate.cancelled and gate.result['elapsed_seconds'] < 1200
                  and gate.result['capture_complete'] and gate.result['required_capture_complete']
                  and gate.result['container_topology_complete']
                  and gate.result['outputs_absent'] and gate.result['retained_inventory_complete'] and gate.result.get('inputs_preserved') is True
                  and gate.result.get('diagnostics', {}).get('test_status') == 'PASS'
                  and gate.result['diagnostics'].get('fixed_scalar_capture') == 'NOT_APPLICABLE' and gate.result['containers'] == 'NORMAL_ABSENT'
                  and all(r['outcome'] == 'NORMAL' for r in gate.result['commands']) and final_containers is True)
        gate.result['status'] = 'PASS' if passed else 'FAIL'
        gate.result['limitation'] = 'One literal active-wire filter/5 task argv/1 case plus configured Ktlint1.8.0/Detekt1.23.8 on exactly two changed Kotlin files, in one normal Gradle invocation with ordinary main/test compilation; no MODEL/Core47/Native73/mixed-pool/enum/prior16/consumer replay. Static input/completion records and original stacks are current-run evidence, not broader qualification. Available evidence or known absence may permit scoped disposal but cannot replace required topology/XML/class-loading context or qualify PASS. Full current-source equality is checked after capture and before exact owned-output disposal; final owned stop/drains/census remain separate mandatory gates. PG14 fixed rows are NOT_APPLICABLE. First-failure/restoration, suppression-disabled/pre-callback EMF-init, registered-tail/MODEL fatal/ClientInfo/declared-stream limits remain. NORMAL_ABSENT is not graceful-shutdown or zero-kill proof; retain raw container kill/die events. UNKNOWN grants no retry authority. Native05 remains UNQUALIFIED; no driver/consumer/liveness/W03/full-P3/production/new-data or installation-recovery qualification. No main/deploy/Store/package-release/W06 or issue-acceptance authority. Public retention is literal and bounded; incomplete/oversized capture or unsettled writers never grant broad upload authority.'
        gate.result['public_retention']['retention_ready'] = (
            gate.result['retained_inventory_complete'] and after_commands is True)
        def save_bounded_result():
            save(gate.reports / 'result.json', gate.result)
            digest(gate.reports / 'result.json', PUBLIC_FILES['result.json'])
            return True
        if gate.attempt('bounded-final-result', save_bounded_result) is not True:
            # A late retention error is sticky: never leave a previously computed PASS or upload authority.
            gate.result['status'] = 'FAIL'
            gate.result['public_retention']['retention_ready'] = False
            save(gate.reports / 'result.json', gate.result)
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
            output.write('retention_ready=' + ('true' if gate.result['public_retention']['retention_ready'] else 'false') + '\n')
    return 0 if gate.result['status'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emit-target', action='store_true')
    args = parser.parse_args()
    binding = request()
    if args.emit_target:
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
            output.write('backend_sha=' + binding['backend_sha'] + '\n')
        return 0
    return execute(binding)


if __name__ == '__main__':
    raise SystemExit(main())
