#!/usr/bin/env python3
"""Proposed single-job controller; reuses the unchanged owned-child helper, not the Linux03 gate."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
from datetime import datetime

control = Path(__file__).resolve().parent
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
binding = json.loads((control / 'binding.json').read_text())
assert binding['status'] == 'BOUND' and binding['primary_dispatch_authority'] == 'ADMITTED_BY_PRIMARY'
assert binding['profile'] == 'PUBLIC_SOURCE_RECONSTRUCTION_NONSHIPPING_APP29_FOCUSED_25'
assert os.environ['RUNNER_ENVIRONMENT'] == 'github-hosted'
assert sha(control / 'app29_linux_owned_processes.py') == binding['original_owned_process_helper_sha256']
from app29_linux_owned_processes import OwnedChildren

source = Path(os.environ['KIRA_PUBLIC_SOURCE']).resolve(strict=True)
run = Path(os.environ['KIRA_PUBLIC_RUN']).resolve(strict=True)
evidence = Path(os.environ['KIRA_PUBLIC_EVIDENCE']).resolve(strict=True)
assert run.name.startswith('.kira-public-backend-') and evidence.name.startswith('kira-public-backend-evidence-')
identity = (run.stat().st_dev, run.stat().st_ino, run.stat().st_uid)
assert identity[2] == os.geteuid() and run.stat().st_mode & 0o777 == 0o700
runtime = Path(os.environ['KIRA_PUBLIC_RUNTIME']).resolve(strict=True)
assert (runtime / 'runtime-manifest.json').is_file()
runtime_identity = {'device': runtime.stat().st_dev, 'inode': runtime.stat().st_ino, 'uid': runtime.stat().st_uid}
selection = json.loads((control / 'selection.json').read_text())
inventory_pin = json.loads((control / 'source-inventory.json').read_text())
assert sha(control / 'selection.json') == binding['selection_sha256']
assert sha(control / 'source-inventory.json') == binding['source']['inventory_sha256']
assert sha(control / 'historical-source-paths.txt') == binding['historical_paths_sha256']
historical = (control / 'historical-source-paths.txt').read_text().splitlines()
assert len(set(historical)) == 348
selectors = selection['test_selectors']
assert len(set(selectors)) == len(selectors) == selection['expected_selected_methods'] == 25
assert selection['expected_total'] == 25

def emit(name, value):
    (evidence / name).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')

def failure(stage, error):
    frames = traceback.extract_tb(error.__traceback__)
    return {'stage': stage, 'type': type(error).__name__,
        'assertion': str(error)[:300] if isinstance(error, AssertionError) else None,
        'control_line': frames[-1].lineno if frames else None}

def partial_diagnostics():
    # Bounded snapshots, explicitly unstable/incomplete; no claim that their writers ended.
    folder = evidence / 'partial-unstable'
    folder.mkdir(exist_ok=True)
    paths = [run / 'gradle.log'] + [run / 'backend-build/test-results/test' / ('TEST-' + c + '.xml')
        for c in selection['test_counts']]
    captured = []
    for path in paths:
        try:
            if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(run):
                continue
            limit = 2 * 1024 * 1024 if path.name == 'gradle.log' else 1024 * 1024
            with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as src:
                data = src.read(limit + 1)
            (folder / path.name).write_bytes(data[:limit])
            captured.append({'name': path.name, 'bytes': min(len(data), limit), 'truncated': len(data) > limit})
        except OSError:
            captured.append({'name': path.name, 'read': 'UNKNOWN'})
    emit('partial-diagnostics.json', {'stable': False, 'complete': False, 'files': captured})

def unhandled(kind, error, tb):
    record = failure('controller-unhandled-or-bootstrap', error)
    try:
        partial_diagnostics()
        emit('controller-failure.json', record)
    finally:
        print(json.dumps(record), file=sys.stderr)
sys.excepthook = unhandled

samples = []
def sample_resources(stage):
    memory = {line.split(':')[0]: int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines()}
    samples.append({'stage': stage, 'unix_seconds': time.time(), 'mem_available_bytes': memory['MemAvailable'],
        'disk_free_bytes': shutil.disk_usage(run.parent).free})
    emit('resource-observations.json', samples)  # Observations, not borrowed Linux03 floor/admission receipts.

def command(args, *, env=None, timeout=15):
    return subprocess.check_output(args, cwd=source, env=env, timeout=timeout, stderr=subprocess.DEVNULL).decode().strip()

def inventory():
    assert command(['git', 'rev-parse', 'HEAD']) == binding['source']['sha']
    assert command(['git', 'rev-parse', 'HEAD^{tree}']) == binding['source']['tree']
    assert not command(['git', 'status', '--porcelain=v1', '--untracked-files=all'])
    names = command(['git', 'ls-files', '-z']).rstrip('\0').split('\0')
    assert all((source / name).is_file() and not (source / name).is_symlink() for name in names)
    current = {name: sha(source / name) for name in names}
    assert len(current) == inventory_pin['file_count'] == 2125
    assert hashlib.sha256(json.dumps(current, sort_keys=True, separators=(',', ':')).encode()).hexdigest() == inventory_pin['canonical_sha256']
    assert set(historical) <= set(current), 'Historical348 source paths omitted'
    return current

def docker(args):
    return command(['docker', *args], timeout=4)

def ids(kind):
    return set(docker([kind, 'ls', '-q', '--no-trunc'] if kind != 'volume' else ['volume', 'ls', '-q']).split())

CONTAINER_IMAGES = {'postgres:17.6-alpine', 'redis:7.4.7-alpine', 'testcontainers/ryuk:0.12.0'}
CONTAINER_LABELS = {'org.testcontainers': 'true', 'org.testcontainers.lang': 'java', 'org.testcontainers.version': '1.21.4'}
UUID_TEXT = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

def container_metadata(row):
    # Never retain arbitrary labels, names, environment, mounts or registry references.
    config, host = row.get('Config') or {}, row.get('HostConfig') or {}
    labels = config.get('Labels') or {}
    session, name, created = labels.get('org.testcontainers.sessionId'), row.get('Name'), row.get('Created')
    try:
        timestamp = datetime.fromisoformat(created.replace('Z', '+00:00'))
        created = timestamp.isoformat() if timestamp.tzinfo is not None else None
        created_unix = timestamp.timestamp() if created is not None else None
    except (AttributeError, TypeError, ValueError, OverflowError):
        created, created_unix = None, None
    return {'id': row.get('Id') if re.fullmatch(r'[0-9a-f]{64}', str(row.get('Id'))) else None,
        'image_id': row.get('Image') if re.fullmatch(r'sha256:[0-9a-f]{64}', str(row.get('Image'))) else None,
        'image_ref': config.get('Image') if config.get('Image') in CONTAINER_IMAGES else 'OTHER',
        'labels': {key: value if labels.get(key) == value else ('OTHER' if key in labels else None)
            for key, value in CONTAINER_LABELS.items()},
        'session': session if re.fullmatch(UUID_TEXT, str(session)) else ('OTHER' if 'org.testcontainers.sessionId' in labels else None),
        'ryuk_name': name if re.fullmatch('/testcontainers-ryuk-' + UUID_TEXT, str(name)) else None,
        'auto_remove': host.get('AutoRemove') if type(host.get('AutoRemove')) is bool else None,
        'created': created, 'created_unix': created_unix}

def current_container_records():
    # Only this fresh run's fixed Gradle log and selected XML files, with finite read/output caps.
    records, scanned, budget = set(), [], 32 * 1024 * 1024
    paths = [run / 'gradle.log'] + [run / 'backend-build/test-results/test' / ('TEST-' + cls + '.xml')
        for cls in selection['test_counts']]
    pattern = re.compile(r'tc\.(postgres:17\.6-alpine|redis:7\.4\.7-alpine|testcontainers/ryuk:0\.12\.0)(?: -- |[ \t]+: )Container \1 is starting: ([0-9a-f]{64})(?=\r?$)', re.MULTILINE)
    for path in paths:
        if not path.is_file():
            continue
        assert not path.is_symlink() and path.resolve().is_relative_to(run), 'Unowned container log input'
        limit = min(budget, 8 * 1024 * 1024 if path.name == 'gradle.log' else 1024 * 1024)
        assert limit > 0, 'Container provenance read budget exhausted'
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
            raw = stream.read(limit + 1)
        assert len(raw) <= limit, 'Oversized container provenance input; refuse truncated records'
        budget -= min(len(raw), limit)
        records.update(pattern.findall(raw[:limit].decode('utf-8', errors='replace')))
        assert len(records) <= 128, 'Unexpected container provenance footprint'
        scanned.append({'name': path.name, 'bytes': min(len(raw), limit), 'truncated': len(raw) > limit})
    emit('container-provenance.json', {'scanned': scanned, 'records': [{'image': image, 'id': ident} for image, ident in sorted(records)]})
    return records

def classify_container(row, records, started, observed):
    if (row['id'] is None or row['image_id'] is None or row['labels'] != CONTAINER_LABELS or
            row['created_unix'] is None or not started <= row['created_unix'] <= observed or
            (row['image_ref'], row['id']) not in records):
        return None
    if row['image_ref'] == 'postgres:17.6-alpine' and re.fullmatch(UUID_TEXT, str(row['session'])):
        return 'product-postgres'
    if row['image_ref'] == 'redis:7.4.7-alpine' and re.fullmatch(UUID_TEXT, str(row['session'])):
        return 'product-redis'
    if row['image_ref'] == 'testcontainers/ryuk:0.12.0' and row['ryuk_name'] and row['auto_remove'] is True:
        session = row['ryuk_name'].removeprefix('/testcontainers-ryuk-')
        if row['session'] in (None, session):  # RyukResourceReaper deliberately omits its own session label.
            return 'infrastructure-ryuk'
    return None

def java_quiescence(ended=False):
    # Initial absence is retained BEFORE Docker checks; only the final successful barrier upgrades it.
    emit('backend-java-quiescence.json', {'schema': 1, 'state': 'JAVA_ENDED' if ended else 'UNKNOWN',
        'backendRuntimeUseEnded': ended,
        'rootIdentity': dict(zip(('device', 'inode', 'uid'), identity)), 'runtimeIdentity': runtime_identity,
        'children': ledger.get('children'), 'final_children': ledger.get('final_children'), 'subreaper': ledger.get('subreaper')})

before = inventory()
sample_resources('before')
assert samples[-1]['mem_available_bytes'] >= 6 * 1024**3 and samples[-1]['disk_free_bytes'] >= 8 * 1024**3, 'Dedicated hosted resource admission'
emit('source-before.json', before)
(run / 'source-inventory.json').write_text(json.dumps({'files': before}, sort_keys=True) + '\n')
assert not docker(['ps', '-aq']), 'Use an otherwise container-empty dedicated hosted job'
image_before = ids('image')
volumes_before = ids('volume')
networks_before = ids('network')
started = time.time()
for name in ('test-home', 'tmp', 'gradle-home', 'project-cache', 'kotlin'):
    (run / name).mkdir(mode=0o700)
env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME')}
env['HOME'] = str(run / 'test-home')
env.update(LANG='C.UTF-8', TZ='UTC', CI='true', GRADLE_USER_HOME=str(run / 'gradle-home'),
    KIRA_PUBLIC_RUNTIME=str(runtime), KIRA_PUBLIC_RUN=str(run), KIRA_PUBLIC_CONTROL=str(control),
    JAVA_TOOL_OPTIONS='-Duser.home=' + str(run / 'test-home') + ' -Djava.io.tmpdir=' + str(run / 'tmp'),
    TMPDIR=str(run / 'tmp'), TMP=str(run / 'tmp'), TEMP=str(run / 'tmp'), PYTHONDONTWRITEBYTECODE='1',
    TESTCONTAINERS_REUSE_ENABLE='false')  # No token, LOCAL_RUN, external Docker endpoint or JVM override inherited.
common = ['--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache', '--console=plain',
    '--project-cache-dir', str(run / 'project-cache'), '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=768m -Djava.io.tmpdir=' + str(run / 'tmp'),
    '-Dorg.gradle.vfs.watch=false', '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false',
    '-Pkotlin.project.persistent.dir=' + str(run / 'kotlin'), '-Porg.gradle.java.installations.auto-download=false',
    '-PkiraUseMavenLocal=false', '-PkiraOwnedPgVerificationRepository=' + str(runtime / 'maven'),
    '-I', str(control / 'runtime-repositories.init.gradle'), '-I', str(control / 'normal-runtime.init.gradle'), '-x', 'jacocoTestReport']
args = ['./gradlew', *common, 'compileKotlin', 'compileTestKotlin', 'test']
for selector in selectors:
    args += ['--tests', selector]
scope = OwnedChildren()
ledger = {'profile': binding['profile'], 'build_exit': None, 'errors': [], 'container_forced': [], 'ryuk_cleanup': [], 'scratch_removed': False}
safe_files = False
try:
    (run / 'run-started.marker').write_text('Original public verification controller has started; no blind fallback deletion.\n')
    scope.activate()
    with (run / 'gradle.log').open('xb') as log:
        child = subprocess.Popen(args, cwd=source, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        scope.track()
        deadline = time.monotonic() + binding['gradle_seconds']
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired('normal Gradle graph', binding['gradle_seconds'])
            try:
                ledger['build_exit'] = child.wait(timeout=min(60, remaining))
                break
            except subprocess.TimeoutExpired:
                sample_resources('periodic60s')
                scope.track()
                assert (run / 'gradle.log').stat().st_size <= 32 * 1024 * 1024, 'Bounded public log limit'
                assert samples[-1]['mem_available_bytes'] >= 2 * 1024**3 and samples[-1]['disk_free_bytes'] >= 3 * 1024**3, 'Hosted resource floor'
except BaseException as error:
    ledger['errors'].append(failure('build', error))
finally:
    try:
        assert scope.active, 'Never launch a cleanup child after failed acquisition'
        stop = subprocess.Popen(['./gradlew', '--offline', '--stop', '--console=plain'], cwd=source, env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        scope.track()
        ledger['gradle_stop_exit'] = stop.wait(timeout=25)
        assert ledger['gradle_stop_exit'] == 0
    except BaseException as error:
        ledger['errors'].append(failure('owned-gradle-stop', error))
    try:
        ledger['children'] = scope.barrier(natural_timeout=5, term_timeout=10, kill_timeout=5)
        java_quiescence()
        # Docker actors are outside the Java descendant tree. Only new Testcontainers IDs on this empty dedicated job are eligible.
        # Ryuk 0.12.0 waits 10s after its last client disconnects before pruning.
        deadline = time.monotonic() + 30
        while docker(['ps', '-aq']) and time.monotonic() < deadline:
            time.sleep(0.5)
        remaining = docker(['ps', '-aq', '--no-trunc']).split()
        observed, metadata = time.time(), []
        snapshot = {'count': len(remaining), 'truncated': len(remaining) > 8, 'containers': metadata}
        emit('container-observations.json', snapshot)
        for container in remaining[:8]:
            try:
                row = container_metadata(json.loads(docker(['inspect', container]))[0])
                metadata.append(row)
                emit('container-observations.json', snapshot)  # Actual allowlisted fields survive a later refusal.
                assert row['id'] == container, 'Container inspection identity differs'
            except BaseException as error:
                snapshot['inspection_error'] = failure('container-inspection', error)
                emit('container-observations.json', snapshot)
                raise
        assert len(remaining) <= 8, 'Unexpected container footprint; refuse broad cleanup'
        records = current_container_records() if remaining else set()
        kinds = [classify_container(row, records, started, observed) for row in metadata]
        assert all(kinds), 'Unproved current-run container; refuse cleanup'
        for row, kind in zip(metadata, kinds):
            target = ledger['ryuk_cleanup'] if kind == 'infrastructure-ryuk' else ledger['container_forced']
            target.append({'id': row['id'], 'session': row['session'], 'image': row['image_id']})
            docker(['rm', '-fv', row['id']])
        assert not docker(['ps', '-aq'])
        ledger['ryuk_absent_after_cleanup'] = True
        safe_files = True
        # No system prune, baseline image deletion, or unlabelled volume/network guesses.
        for image in ids('image') - image_before:
            row = json.loads(docker(['image', 'inspect', image]))[0]
            tags = row.get('RepoTags') or []
            assert tags and all(tag in CONTAINER_IMAGES for tag in tags)
            docker(['image', 'rm', image])
        assert ids('volume') == volumes_before and ids('network') == networks_before and ids('image') == image_before
        ledger['docker_absent_and_caches_restored'] = True
    except BaseException as error:
        ledger['errors'].append(failure('actual-cleanup', error))
    try:
        ledger['final_children'] = scope.barrier(natural_timeout=2, term_timeout=5, kill_timeout=3)
        ledger['subreaper'] = scope.restore()
        java_quiescence(ended=(ledger.get('children', {}).get('absent') is True and
            ledger['final_children']['absent'] is True and not ledger['children']['errors'] and
            not ledger['final_children']['errors'] and ledger['subreaper']['active'] is False))
    except BaseException as error:
        safe_files = False
        ledger['errors'].append(failure('final-child-barrier', error))
        java_quiescence()

sample_resources('after-owned-stop')
if not safe_files:
    partial_diagnostics()

if safe_files:
    try:
        emit('source-after.json', inventory())
        output = run / 'backend-build'
        for relative in ('test-results/test', 'public-source-verification'):
            if (output / relative).is_dir():
                shutil.copytree(output / relative, evidence / Path(relative).name)
        with (run / 'gradle.log').open('rb') as stream:
            closed_log = stream.read(32 * 1024 * 1024 + 1)
        (evidence / 'gradle.log').write_bytes(closed_log[:32 * 1024 * 1024])
        assert len(closed_log) <= 32 * 1024 * 1024, 'Closed public log exceeded retained evidence cap'
        shutil.copy2(runtime / 'runtime-manifest.json', evidence / 'runtime-manifest.json')
        shutil.copy2(control / 'binding.json', evidence / 'binding.json')
        shutil.copy2(control / 'selection.json', evidence / 'selection.json')
        cases = []
        xml_files = sorted((evidence / 'test').glob('*.xml'))
        assert {p.name for p in xml_files} == {'TEST-' + cls + '.xml' for cls in selection['test_counts']}
        for path in xml_files:
            assert path.stat().st_size <= 64 * 1024 * 1024
            raw = path.read_bytes()
            assert b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper()
            suite = ET.fromstring(raw)
            assert all(suite.get(key) == '0' for key in ('failures', 'errors', 'skipped'))
            for case in suite.findall('testcase'):
                assert all(case.find(tag) is None for tag in ('failure', 'error', 'skipped'))
                cases.append((case.get('classname'), case.get('name')))
        wanted = [(r['class'], name) for r in selection['declarations'] for name in r['xml_names']]
        assert sorted(cases) == sorted(wanted) and len(cases) == 25
        states = json.loads((evidence / 'public-source-verification/task-outcomes.json').read_text())
        assert set(states) == {'compileKotlin', 'compileTestKotlin', 'test'}
        assert all(s['executed'] and s['didWork'] and not any(s[k] for k in ('skipped', 'upToDate', 'noSource', 'failure')) for s in states.values())
        ledger['selected_methods_and_actual_compilation'] = True
    except BaseException as error:
        partial_diagnostics()
        ledger['errors'].append(failure('source-and-test-evidence', error))
    finally:
        assert (run.stat().st_dev, run.stat().st_ino, run.stat().st_uid) == identity and shutil.rmtree.avoids_symlink_attacks
        shutil.rmtree(run)
        ledger['scratch_removed'] = not os.path.lexists(run)
ledger['selected_methods_pass'] = (ledger['build_exit'] == 0 and not ledger['errors'] and ledger['scratch_removed'] and
    not ledger.get('children', {}).get('forced', True) and not ledger.get('final_children', {}).get('forced', True) and
    not ledger['container_forced'] and ledger.get('selected_methods_and_actual_compilation', False))
emit('actual-cleanup-and-result.json', ledger)
print('Public nonshipping selected-method verification:', 'FOCUSED_PASS' if ledger['selected_methods_pass'] else 'NOT_ACCEPTED')
sys.exit(0 if ledger['selected_methods_pass'] else 1)
