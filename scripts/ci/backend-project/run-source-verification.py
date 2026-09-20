#!/usr/bin/env python3
"""Proposed single-job controller; reuses the unchanged owned-child helper, not the Linux03 gate."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

control = Path(__file__).resolve().parent
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
binding = json.loads((control / 'binding.json').read_text())
assert binding['status'] == 'BOUND' and binding['primary_dispatch_authority'] == 'ADMITTED_BY_PRIMARY'
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
selection = json.loads((control / 'selection.json').read_text())
inventory_pin = json.loads((control / 'source-inventory.json').read_text())
assert sha(control / 'selection.json') == binding['selection_sha256']
assert sha(control / 'source-inventory.json') == binding['source']['inventory_sha256']
assert sha(control / 'historical-source-paths.txt') == binding['historical_paths_sha256']
historical = (control / 'historical-source-paths.txt').read_text().splitlines()
assert len(set(historical)) == 348
selectors = selection['test_selectors']
assert len(set(selectors)) == len(selectors) == selection['expected_total'] == 217

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
    assert len(current) == inventory_pin['file_count'] == 1841
    assert hashlib.sha256(json.dumps(current, sort_keys=True, separators=(',', ':')).encode()).hexdigest() == inventory_pin['canonical_sha256']
    assert set(historical) <= set(current), 'Historical348 source paths omitted'
    return current

def docker(args):
    return command(['docker', *args], timeout=4)

def ids(kind):
    return set(docker([kind, 'ls', '-q', '--no-trunc'] if kind != 'volume' else ['volume', 'ls', '-q']).split())

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
ledger = {'profile': binding['profile'], 'build_exit': None, 'errors': [], 'container_forced': [], 'scratch_removed': False}
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
        # Docker actors are outside the Java descendant tree. Only new Testcontainers IDs on this empty dedicated job are eligible.
        deadline = time.monotonic() + 5
        while docker(['ps', '-aq']) and time.monotonic() < deadline:
            time.sleep(0.5)
        remaining = docker(['ps', '-aq']).split()
        assert len(remaining) <= 8, 'Unexpected container footprint; refuse broad cleanup'
        for container in remaining:
            row = json.loads(docker(['inspect', container]))[0]
            labels = row['Config'].get('Labels') or {}
            assert labels.get('org.testcontainers') == 'true' and labels.get('org.testcontainers.sessionId')
            assert row['Config']['Image'] == 'postgres:17.6-alpine' or row['Config']['Image'].startswith('testcontainers/ryuk:')
            from datetime import datetime
            assert datetime.fromisoformat(row['Created'].replace('Z', '+00:00')).timestamp() >= started
            ledger['container_forced'].append({'id': row['Id'], 'session': labels['org.testcontainers.sessionId'], 'image': row['Image']})
            docker(['rm', '-fv', row['Id']])
        assert not docker(['ps', '-aq'])
        safe_files = True
        # No system prune, baseline image deletion, or unlabelled volume/network guesses.
        for image in ids('image') - image_before:
            row = json.loads(docker(['image', 'inspect', image]))[0]
            tags = row.get('RepoTags') or []
            assert tags and all(tag == 'postgres:17.6-alpine' or tag.startswith('testcontainers/ryuk:') for tag in tags)
            docker(['image', 'rm', image])
        assert ids('volume') == volumes_before and ids('network') == networks_before and ids('image') == image_before
        ledger['docker_absent_and_caches_restored'] = True
    except BaseException as error:
        ledger['errors'].append(failure('actual-cleanup', error))
    try:
        ledger['final_children'] = scope.barrier(natural_timeout=2, term_timeout=5, kill_timeout=3)
        ledger['subreaper'] = scope.restore()
    except BaseException as error:
        safe_files = False
        ledger['errors'].append(failure('final-child-barrier', error))

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
        assert sorted(cases) == sorted(wanted) and len(cases) == 217
        states = json.loads((evidence / 'public-source-verification/task-outcomes.json').read_text())
        assert set(states) == {'compileKotlin', 'compileTestKotlin', 'test'}
        assert all(s['executed'] and s['didWork'] and not any(s[k] for k in ('skipped', 'upToDate', 'noSource', 'failure')) for s in states.values())
        ledger['exact217_and_actual_compilation'] = True
    except BaseException as error:
        partial_diagnostics()
        ledger['errors'].append(failure('source-and-test-evidence', error))
    finally:
        assert (run.stat().st_dev, run.stat().st_ino, run.stat().st_uid) == identity and shutil.rmtree.avoids_symlink_attacks
        shutil.rmtree(run)
        ledger['scratch_removed'] = not os.path.lexists(run)
ledger['source_verification_pass'] = (ledger['build_exit'] == 0 and not ledger['errors'] and ledger['scratch_removed'] and
    not ledger.get('children', {}).get('forced', True) and not ledger.get('final_children', {}).get('forced', True) and
    not ledger['container_forced'] and ledger.get('exact217_and_actual_compilation', False))
emit('actual-cleanup-and-result.json', ledger)
print('Public nonshipping source verification:', 'PASS' if ledger['source_verification_pass'] else 'NOT_ACCEPTED')
sys.exit(0 if ledger['source_verification_pass'] else 1)
