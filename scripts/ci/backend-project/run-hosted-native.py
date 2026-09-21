#!/usr/bin/python3.12
"""INERT hosted-only successor: pinned PG source build, ordinary compilation, native class3."""
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
PACKET = Path(__file__).resolve().parent
CLASS = 'me.manga.kira.backend.common.infrastructure.persistence.LogicalBackupCaptureIT'
METHODS = [
    'different actual pg dump bytes refuse before a child or guard is started()',
    'real native import releases exporter while dump continues and excludes later rows()',
    'blocked guard is bounded retained unknown and never refunded by elapsed time()',
]
PROFILE = 'HOSTED_NATIVE_CLASS3_UNQUALIFIED'
LOCK_FD = None
PUBLIC_FILES = ('initial-roots.json', 'host-admission.json', 'execution-roots.json', 'result.json',
    'local-runtime-consumption.json', 'runtime-manifest.json', 'execution-binding.json', 'selection.json',
    'source-before.json', 'source-after.json', 'pg-supplier.json', 'native-inputs.json',
    'supplier-children.json', 'preparation-children.json', 'disposable-source-witness.json', 'owned-quiescence.json')
RECEIPTS = ('source-sets.json', 'normal-classpaths.json', 'effective-test-classpath.json',
            'task-graph.json', 'task-outcomes.json')


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked(path):
    require(path.is_absolute() and path == Path(os.path.normpath(path)) and
            all(ord(c) >= 32 and ord(c) != 127 for c in str(path)), 'Absolute normalized custody path required')
    for part in [*reversed(path.parents), path]:
        row = part.lstat()
        require(not stat.S_ISLNK(row.st_mode) and row.st_uid in (0, os.geteuid()) and not row.st_mode & 0o022, 'Untrusted ancestry')
    return path


def identity(path):
    row = path.lstat()
    return {'device': row.st_dev, 'inode': row.st_ino, 'uid': row.st_uid}


def exact_owned_root(path, original, observations, mismatches):
    observed = {'unobserved': True}
    try:
        try:
            observed = identity(path)
        except FileNotFoundError:
            observed = {'absent': True}
        observations[str(path)] = observed
        checked(path)
        row = path.lstat()
        require(observed == original and stat.S_ISDIR(row.st_mode) and row.st_mode & 0o777 == 0o700,
                'Original private root custody changed')
    except BaseException as error:
        observations[str(path)] = observed
        mismatches.setdefault(str(path), {'observed': observed, 'type': type(error).__name__})
        raise
    require(str(path) not in mismatches, 'Earlier custody mismatch remains a refusal')


def read_bytes(path, limit=2 * 1024 * 1024):
    checked(path)
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        row = os.fstat(stream.fileno())
        require(stat.S_ISREG(row.st_mode) and row.st_nlink == 1 and row.st_size <= limit, 'Bounded original regular input required')
        raw = stream.read(limit + 1)
        fields = ('st_dev', 'st_ino', 'st_uid', 'st_gid', 'st_mode', 'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
        after = os.fstat(stream.fileno())
        require(len(raw) == row.st_size and all(getattr(row, key) == getattr(after, key) for key in fields) and
                identity(path) == {'device': row.st_dev, 'inode': row.st_ino, 'uid': row.st_uid}, 'Bounded input changed while reading')
    return raw


def read_json(path, limit=2 * 1024 * 1024):
    return json.loads(read_bytes(path, limit))


def write_bytes(path, raw):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as target:
        target.write(raw)
        target.flush()
        os.fsync(target.fileno())


def emit(path, value):
    write_bytes(path, (json.dumps(value, sort_keys=True, indent=2) + '\n').encode())


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def release_lane():
    global LOCK_FD
    if LOCK_FD is not None:
        fcntl.flock(LOCK_FD, fcntl.LOCK_UN)
        os.close(LOCK_FD)
        LOCK_FD = None


def admitted():
    cfg = read_json(PACKET / 'hosted-binding.json')
    require(cfg['status'] == 'BOUND' and cfg['primary_authority'] == cfg['primary_dispatch_authority'] == 'ADMITTED_BY_PRIMARY',
            'INERT: primary admission absent')
    require(cfg['profile'] == PROFILE and sys.platform == 'linux' and sys.version_info[:2] == (3, 12) and
            not sys.flags.optimize and os.geteuid() != 0 and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted' and
            os.environ.get('GITHUB_REPOSITORY') == 'kira-manga/Kira-backend' and os.environ.get('GITHUB_RUN_ATTEMPT') == '1' and
            os.environ.get('GITHUB_REF') == cfg['branch'], 'Exact nonroot hosted one-shot invocation required')
    require(Path(sys.executable).resolve(strict=True) == checked(Path(cfg['host']['tools']['python']).resolve(strict=True)),
            'The runner and native helper must use the actual admitted system Python')
    require(sha(Path(__file__)) == cfg['runner_sha256'] and sha(PACKET / 'compile-only.init.gradle') == cfg['compile_only_init_sha256'],
            'Runner/compile guard changed')
    require(sha(PACKET / 'runtime-reuse.json') == cfg['runtime_reuse_sha256'] and
            cfg['producer_inputs']['controls'] == cfg['control_sha256'], 'Original producer/control binding differs')
    for name, pin in cfg['control_sha256'].items():
        require(sha(checked(PACKET / name)) == pin, 'Original control bytes changed')
    os.umask(0o077)
    return cfg


def github_outputs(values):
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
        for key, value in values.items():
            require('\n' not in str(value) and '\r' not in str(value), 'Single-line output required')
            output.write(f'{key}={value}\n')


def admit_outputs(cfg):
    home = checked(Path(os.environ['HOME']))
    run_id = os.environ['GITHUB_RUN_ID']
    require(re.fullmatch('[1-9][0-9]{1,20}', run_id), 'Numeric hosted run identity required')
    work, evidence = (home / (prefix + run_id + '-1') for prefix in ('.kira-qcap-native3-', 'kira-qcap-native3-evidence-'))
    require(not any(os.path.lexists(p) for p in (work, evidence)), 'Fresh one-shot roots required')
    initial, observations, mismatches = {}, {}, {}
    try:
        for path in (evidence, work, work / 'reuse-input'):
            path.mkdir(mode=0o700)
            initial[str(path)] = identity(path)
            if path == evidence:
                github_outputs({'evidence': evidence, 'evidence_identity': json.dumps(initial[str(evidence)], sort_keys=True)})
        emit(evidence / 'initial-roots.json', initial)
        github_outputs({'work': work, 'artifact_input': work / 'reuse-input'})
        host = cfg['host']
        os_release = checked(Path('/etc/os-release').resolve(strict=True))
        system = dict(line.split('=', 1) for line in read_bytes(os_release, 16384).decode().splitlines() if '=' in line)
        require(system['ID'].strip('"') == host['os_id'] and system['VERSION_ID'].strip('"') == host['os_version'] and
                os.uname().machine == host['architecture'] and os.environ.get('ImageOS') == host['image_os'], 'Admitted Ubuntu24/amd64 image required')
        image_version = os.environ.get('ImageVersion', '')
        require(re.fullmatch('[0-9.]{1,40}', image_version), 'Actual hosted image version unavailable')
        jdk = checked(Path(os.environ[host['jdk_environment']]).resolve(strict=True))
        require(re.search(r'^JAVA_VERSION="21[.\"]', read_bytes(jdk / 'release', 65536).decode(), re.M), 'Actual JDK21 required')
        jdk_pins = {name: sha(checked(jdk / name)) for name in ('release', 'bin/java', 'lib/modules', 'lib/server/libjvm.so')}
        tools = {name: {'path': str(checked(Path(path).resolve(strict=True)))} for name, path in host['tools'].items()}
        libraries = {path: str(checked(Path(path).resolve(strict=True))) for path in host['libraries']}
        for row in tools.values():
            row['sha256'] = sha(Path(row['path']))
        admission = {'schema': 1, 'profile': PROFILE, 'uid': os.geteuid(), 'gid': os.getegid(), 'initialRoots': initial,
            'paths': {'work': str(work), 'evidence': str(evidence),
                      'source': str(checked(Path(os.environ['GITHUB_WORKSPACE']) / 'backend')), 'control': str(checked(PACKET))},
            'consumer_jdk': {'path': str(jdk), 'sha256': jdk_pins}, 'tools': tools,
            'libraries': {name: {'path': path, 'sha256': sha(Path(path))} for name, path in libraries.items()},
            'host': {'imageOS': host['image_os'], 'imageVersion': image_version, 'osReleaseSha256': sha(os_release),
                     'machine': os.uname().machine, 'kernel': os.uname().release},
            'carrier': os.environ['GITHUB_SHA'], 'source': cfg['source'], 'bindingSha256': sha(PACKET / 'hosted-binding.json')}
        emit(evidence / 'host-admission.json', admission)
        github_outputs({'admission': evidence / 'host-admission.json', 'admission_sha256': sha(evidence / 'host-admission.json')})
    except BaseException as error:
        failures = [{'stage': 'host-admission', 'type': type(error).__name__}]
        removed = False
        for name, original in initial.items():
            try:
                exact_owned_root(Path(name), original, observations, mismatches)
            except BaseException as cleanup_error:
                failures.append({'stage': 'admission-root-custody', 'type': type(cleanup_error).__name__})
        try:
            if str(work) in initial:
                require(not mismatches and shutil.rmtree.avoids_symlink_attacks, 'Admission custody/removal unavailable')
                shutil.rmtree(work)  # No child or download exists during admission.
                removed = not os.path.lexists(work)
        except BaseException as cleanup_error:
            failures.append({'stage': 'admission-disposal', 'type': type(cleanup_error).__name__})
        try:
            if str(evidence) in initial:
                exact_owned_root(evidence, initial[str(evidence)], observations, mismatches)
                if not (evidence / 'initial-roots.json').exists():
                    emit(evidence / 'initial-roots.json', initial)
                emit(evidence / 'result.json', {'profile': PROFILE, 'passed': False, 'childrenEverStarted': False,
                     'workRemoved': removed, 'errors': failures, 'originalRootIdentities': initial,
                     'observedRootIdentities': observations, 'rootCustodyMismatches': mismatches})
        except BaseException as receipt_error:
            print('Host-admission receipt refused; exception type:', type(receipt_error).__name__, file=sys.stderr)
        raise


def publish_outputs():
    evidence = checked(Path(os.environ['KIRA_QCAP_EVIDENCE']))
    expected = json.loads(os.environ['KIRA_QCAP_EVIDENCE_IDENTITY'])
    require(identity(evidence) == expected, 'Never publish a substituted evidence root')
    names = list(PUBLIC_FILES) + [phase + '/' + name for phase in ('preparation', 'native') for name in RECEIPTS]
    names += ['native/TEST-' + CLASS + '.xml']
    paths = []
    for name in names:
        path = evidence / name
        if not os.path.lexists(path):
            continue
        raw = read_bytes(path)
        if name.endswith('.xml'):
            require(b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper(), 'No XML declarations')
            ET.fromstring(raw)
        else:
            json.loads(raw)
        paths.append(str(path))
    require(paths and identity(evidence) == expected, 'Original bounded evidence required')
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
        output.write('paths<<KIRA_QCAP_PATHS\n' + '\n'.join(paths) + '\nKIRA_QCAP_PATHS\n')
    github_outputs({'upload': 'true'})  # File allowlist only, never a test or cleanup verdict.


def main(cfg):
    global LOCK_FD
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    admission_path = checked(Path(os.environ['KIRA_QCAP_ADMISSION']))
    raw = read_bytes(admission_path, 256 * 1024)
    require(hashlib.sha256(raw).hexdigest() == os.environ['KIRA_QCAP_ADMISSION_SHA256'], 'Original hosted admission changed')
    host = json.loads(raw)
    require(host['uid'] == os.geteuid() and host['gid'] == os.getegid() and host['source'] == cfg['source'] and
            host['bindingSha256'] == sha(PACKET / 'hosted-binding.json') and host['carrier'] == os.environ['GITHUB_SHA'], 'Hosted binding differs')
    source, work, evidence = (Path(host['paths'][name]) for name in ('source', 'work', 'evidence'))
    dbroot, PG, pgbuild = (work / name for name in ('database', 'native-pg', 'pg-build'))
    control, run, runtime, private = (work / name for name in ('control', 'run', 'runtime', 'capture-private'))
    owned = {Path(path): value for path, value in host['initialRoots'].items()}
    root_observations, root_mismatches = {}, {}

    def exact_root(path):
        exact_owned_root(path, owned[path], root_observations, root_mismatches)

    try:
        for path in owned:
            exact_root(path)
        checked(source)
        require(sha(PACKET / 'source-inventory.json') == cfg['source']['inventory_sha256'], 'Source inventory changed')
        expected = read_json(PACKET / 'source-inventory.json')['files']
        require(len(expected) == cfg['source']['file_count'] == 2448 and
                hashlib.sha256(json.dumps(expected, sort_keys=True, separators=(',', ':')).encode()).hexdigest() == cfg['source']['canonical_files_sha256'], 'Source inventory identity differs')
        require(expected['src/test/kotlin/me/manga/kira/backend/common/infrastructure/persistence/LogicalBackupCaptureIT.kt'] == cfg['source']['native_test_sha256'] and
                expected['scripts/db/backup_bundle.py'] == cfg['source']['bundle_helper_sha256'], 'Native/helper source binding differs')
        jdk = checked(Path(host['consumer_jdk']['path']))
        for relative, pin in host['consumer_jdk']['sha256'].items():
            require(sha(checked(jdk / relative)) == pin, 'Actual consumer JDK changed')
        for item in (*host['tools'].values(), *host['libraries'].values()):
            require(sha(checked(Path(item['path']))) == item['sha256'], 'Actual hosted image changed')
        reuse = read_json(PACKET / 'runtime-reuse.json')
        LOCK_FD = os.open(work / 'batch.lock', os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW, 0o600)
        fcntl.flock(LOCK_FD, fcntl.LOCK_EX | fcntl.LOCK_NB)  # Dedicated job lock, never the local host's lane.
        for path in (control, run, runtime, private, work / 'runtime-import', dbroot, PG, pgbuild,
                     evidence / 'preparation', evidence / 'native'):
            path.mkdir(mode=0o700)
            owned[path] = identity(path)
        emit(evidence / 'execution-roots.json', {str(path): value for path, value in owned.items()})
        for name in cfg['control_sha256']:
            write_bytes(control / name, read_bytes(PACKET / name))
        emit(control / 'runtime-reuse.json', reuse)
        emit(control / 'binding.json', {'status': 'BOUND', 'source': {**cfg['source'], 'path': str(source)}, 'profile': PROFILE})
        selection = {'test_selectors': [CLASS], 'expected_total': 3, 'expected_selected_methods': 3, 'test_counts': {CLASS: 3}}
        emit(control / 'selection.json', selection)
        imp = module('qcap_reused_runtime_parser', control / 'reconstruct-runtime.py')
        ownership = module('qcap_unchanged_child_owner', control / 'app29_linux_owned_processes.py')
        owner = ownership.OwnedChildren()
    except BaseException as error:
        # No child launch/owner activation exists above this point; imported pinned module tops are inert.
        failures = [{'stage': 'private-preflight', 'type': type(error).__name__}]
        removed = False
        for path in owned:
            try:
                exact_root(path)
            except BaseException as cleanup_error:
                failures.append({'stage': 'preflight-root-custody', 'type': type(cleanup_error).__name__})
        try:
            require(not root_mismatches and shutil.rmtree.avoids_symlink_attacks, 'Safe owned removal unavailable')
            shutil.rmtree(work)
            removed = not os.path.lexists(work)
        except BaseException as cleanup_error:
            failures.append({'stage': 'preflight-disposal', 'type': type(cleanup_error).__name__})
        release_lane()
        try:
            exact_root(evidence)
            if not (evidence / 'execution-roots.json').exists():
                emit(evidence / 'execution-roots.json', {str(path): value for path, value in owned.items()})
            emit(evidence / 'result.json', {'profile': PROFILE, 'passed': False, 'errors': failures, 'childrenEverStarted': False,
                 'workRemoved': removed, 'jobLockReleased': True,
                 'originalRootIdentities': {str(path): value for path, value in owned.items()},
                 'observedRootIdentities': root_observations, 'rootCustodyMismatches': root_mismatches})
        except BaseException as receipt_error:
            print('Private-preflight receipt refused; exception type:', type(receipt_error).__name__, file=sys.stderr)
        raise
    handles, records, errors, observations = [], [], [], []
    server = server_identity = None
    gradle_attempted = False
    barrier = restored = None
    barrier_completed = False
    native_ok = native_started = preparation_saved = False
    secret_values = []
    stage, phase_deadline, cleanup_deadline = 'seed-download', None, None
    work_deadline = time.monotonic() + cfg['limits']['work_seconds']
    env = {'PATH': str(jdk / 'bin') + ':/usr/bin:/bin', 'JAVA_HOME': str(jdk), 'HOME': str(run / 'test-home'),
           'LANG': 'C.UTF-8', 'TZ': 'UTC', 'CI': 'true', 'GRADLE_USER_HOME': str(run / 'gradle-home'),
           'KIRA_PUBLIC_RUNTIME': str(runtime), 'KIRA_PUBLIC_RUN': str(run), 'KIRA_PUBLIC_CONTROL': str(control),
           'JAVA_TOOL_OPTIONS': '-Duser.home=' + str(run / 'test-home') + ' -Djava.io.tmpdir=' + str(run / 'tmp'),
           'TMPDIR': str(run / 'tmp'), 'TMP': str(run / 'tmp'), 'TEMP': str(run / 'tmp'), 'PYTHONDONTWRITEBYTECODE': '1'}

    def resources(admission=False):
        require(time.monotonic() < min(work_deadline, phase_deadline or work_deadline), 'Bounded hosted work/stage deadline exceeded')
        available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:'))) * 1024
        free = min(shutil.disk_usage(work).free, shutil.disk_usage(dbroot.parent).free)
        require(len(observations) < 4096, 'Resource observation bound exceeded')
        observations.append({'unixSeconds': time.time(), 'memoryAvailableBytes': available, 'diskFreeBytes': free, 'admission': admission})
        prefix = 'admission' if admission else 'floor'
        require(available >= cfg['limits'][prefix + '_memory_bytes'] and free >= cfg['limits'][prefix + '_disk_bytes'], 'Hosted resource admission/floor refused')

    def command(args, label, seconds=30, *, child_env=None, payload=None, collect=False, cwd=None, log=False, cleanup=False):
        require(owner.active, 'Never start a child outside acquired ownership')
        if not cleanup:
            resources()
        exact_root(run)
        target = (run / (label + '.log')).open('xb') if log else None  # Raw logs never enter public evidence.
        try:
            deadline = time.monotonic() + seconds
            if cleanup and cleanup_deadline is not None:
                deadline = min(deadline, cleanup_deadline)
            if not cleanup:
                deadline = min(deadline, work_deadline, phase_deadline or work_deadline)
            require(time.monotonic() < deadline, 'Command phase budget exhausted')
            process = subprocess.Popen(args, cwd=cwd or source, env=child_env or env,
                stdin=subprocess.PIPE if payload is not None else subprocess.DEVNULL,
                stdout=target if target else (subprocess.PIPE if collect else subprocess.DEVNULL), stderr=subprocess.STDOUT if log else subprocess.DEVNULL,
                start_new_session=True)
            handles.append(process); owner.track()
            row = {'command': label, 'pid': process.pid, 'exit': None}
            records.append(row)
            first_communication = True
            while True:
                try:
                    wait = max(0.001, min(10, deadline - time.monotonic()))
                    if payload is not None or collect:
                        raw, _ = process.communicate(input=payload if first_communication else None, timeout=wait)
                    else:
                        process.wait(timeout=wait)
                        raw = None
                    break
                except subprocess.TimeoutExpired as timeout:
                    first_communication = False  # communicate resumes its existing pipe; never resend SQL.
                    owner.track()
                    require(time.monotonic() < deadline, 'Bounded command timed out')
                    require(timeout.output is None or len(timeout.output) <= 2 * 1024 * 1024, 'Bounded fixed command reply exceeded')
                    if not cleanup:
                        resources()
                    if target:
                        require(target.tell() <= cfg['limits']['gradle_log_bytes'], 'Gradle log budget exceeded')
            require(raw is None or len(raw) <= 2 * 1024 * 1024, 'Bounded fixed command reply exceeded')
            row['exit'] = process.returncode
            if target:
                require(target.tell() <= cfg['limits']['gradle_log_bytes'], 'Closed Gradle log exceeded budget')
            require(process.returncode == 0, 'Fixed command failed: ' + label)
            return raw
        finally:
            if target:
                target.close()

    def inventory(cleanup=False):
        def git(*args):
            return command([host['tools']['git']['path'], '-c', 'core.fsmonitor=false', *args], 'source-' + args[0], 10, collect=True, cleanup=cleanup)
        require(git('rev-parse', '--abbrev-ref', 'HEAD').decode().strip() == 'HEAD' and
                git('rev-parse', 'HEAD').decode().strip() == cfg['source']['sha'] and
                git('rev-parse', 'HEAD^{tree}').decode().strip() == cfg['source']['tree'] and
                not git('status', '--porcelain=v1', '--untracked-files=all').strip(), 'Exact clean source checkpoint required')
        names = git('ls-files', '-z').decode().rstrip('\0').split('\0')
        actual = {name: sha(checked(source / name)) for name in names}
        require(actual == expected, 'Complete source bytes changed')
        return actual

    def stop_gradle():
        if gradle_attempted:
            command(['./gradlew', '--offline', '--stop', '--console=plain'], 'owned-gradle-stop', cleanup=True)

    def copy_receipts(stage):
        folder = evidence / stage
        exact_root(folder)
        for name in RECEIPTS:
            path = run / 'backend-build/public-source-verification' / name
            if os.path.lexists(path):
                raw = read_bytes(path)
                require(not any(secret in raw for secret in secret_values), 'Private receipt refused')
                json.loads(raw)
                if os.path.lexists(folder / name):
                    require(read_bytes(folder / name) == raw, 'Never overwrite a changed retained receipt')
                else:
                    write_bytes(folder / name, raw)

    def build_native():
        nonlocal phase_deadline
        phase_deadline = time.monotonic() + cfg['limits']['pg_stage_seconds']
        resources(admission=True)
        tools, pin = host['tools'], cfg['pg_source']
        pg_env = {'PATH': '/usr/bin:/bin', 'HOME': str(pgbuild), 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC',
            **{name.upper(): tools[name.lower()]['path'] for name in ('CC', 'AR', 'RANLIB', 'BISON', 'FLEX', 'M4')}}
        packages = {}
        raw = command([tools['dpkg-query']['path'], '-W', '-f=${Package}\t${Version}\t${Architecture}\t${Status}\n',
                       *cfg['host']['packages']], 'host-build-packages', collect=True, child_env=pg_env).decode('ascii')
        for line in raw.splitlines():
            name, version, arch, status = line.split('\t')
            require(name in cfg['host']['packages'] and name not in packages and re.fullmatch('[A-Za-z0-9:.+~_-]{1,100}', version) and
                    arch in ('amd64', 'all') and status == 'install ok installed', 'Actual installed build input refused')
            packages[name] = {'version': version, 'architecture': arch}
        require(set(packages) == set(cfg['host']['packages']), 'No missing-package install/fallback')
        archive = pgbuild / 'postgresql-17.6.tar.bz2'
        command([tools['curl']['path'], '--fail', '--location', '--silent', '--show-error', '--proto', '=https', '--proto-redir', '=https',
                 '--connect-timeout', '15', '--max-time', '120', '--retry', '0', '--max-filesize', str(pin['bytes']), '--output', str(archive), pin['url']],
                'pg-source-download', 125, child_env=pg_env, log=True)
        require(archive.stat().st_size == pin['bytes'] and sha(archive) == pin['sha256'], 'Exact official PG source required')
        entries = files = total = 0
        with tarfile.open(archive, 'r|bz2') as source_tar:
            for member in source_tar:
                entries += 1; total += member.size
                path = PurePosixPath(member.name)
                require(entries <= pin['archive_entries'] and total <= pin['archive_uncompressed_bytes'] and member.pax_headers == pin['archive_pax_headers'] and
                    path.parts and path.parts[0] == pin['top_directory'] and not path.is_absolute() and all(part not in ('', '.', '..') for part in path.parts) and
                    path.as_posix() == member.name.rstrip('/') and (member.isdir() and member.size == 0 or member.isreg()), 'Closed regular PG source archive only')
                target = pgbuild.joinpath(*path.parts)
                if member.isdir():
                    target.mkdir(mode=0o700)
                else:
                    require(0 <= member.size <= 2 * 1024 * 1024, 'Bounded PG source member required')
                    with source_tar.extractfile(member) as stream:
                        raw = stream.read(member.size + 1)
                    require(len(raw) == member.size, 'PG source member size changed')
                    write_bytes(target, raw)
                    if member.mode & 0o111:
                        target.chmod(0o700)
                    files += 1
                if entries % 64 == 0:
                    resources()
        require((entries, files, total) == (pin['archive_entries'], pin['archive_files'], pin['archive_uncompressed_bytes']) and sha(archive) == pin['sha256'],
                'PG archive changed/incomplete')
        pg_source = pgbuild / pin['top_directory']
        require(sha(pg_source / 'src/bin/pg_dump/pg_dump.c') == pin['pg_dump_c_sha256'], 'Reviewed REL_17_6 dump source differs')
        recipe = [str(pg_source / 'configure'), '--prefix=' + str(PG), *pin['configure']]
        command(recipe, 'pg-configure', 90, cwd=pg_source, child_env=pg_env, log=True)
        command([tools['make']['path'], '-j2'], 'pg-make', 420, cwd=pg_source, child_env=pg_env, log=True)
        command([tools['make']['path'], '-j2', 'install'], 'pg-install', 60, cwd=pg_source, child_env=pg_env, log=True)
        supplier_barrier = owner.barrier(natural_timeout=5, term_timeout=10, kill_timeout=5)
        emit(evidence / 'supplier-children.json', supplier_barrier)
        require(supplier_barrier['absent'] and not supplier_barrier['forced'] and not supplier_barrier['errors'], 'PG supplier children did not naturally end')
        exact_root(PG)
        installed, size = {}, 0
        for path in sorted(PG.rglob('*')):
            row = path.lstat(); name = path.relative_to(PG).as_posix()
            require(len(installed) < cfg['limits']['pg_installed_paths'] and row.st_uid == os.geteuid(), 'Untrusted/unbounded installed PG tree')
            if stat.S_ISLNK(row.st_mode):
                require(not PurePosixPath(os.readlink(path)).is_absolute() and path.resolve(strict=True).is_relative_to(PG), 'Installed PG link escapes prefix')
                installed[name] = {'link': os.readlink(path)}
            else:
                require(not row.st_mode & 0o022, 'Writable installed PG object')
                if stat.S_ISREG(row.st_mode):
                    size += row.st_size
                    require(row.st_nlink == 1 and size <= cfg['limits']['pg_installed_bytes'], 'Installed PG byte/link bound exceeded')
                    installed[name] = {'sha256': sha(path), 'bytes': row.st_size}
                else:
                    require(stat.S_ISDIR(row.st_mode), 'Nonregular installed PG object')
                    installed[name] = {'directory': True}
            if len(installed) % 64 == 0:
                resources()
        images = {str(PG / name): installed[name]['sha256'] for name in
                  ('bin/psql', 'bin/pg_dump', 'bin/pg_restore', 'bin/postgres', 'bin/initdb', 'bin/pg_ctl', 'lib/libpq.so.5.17')}
        images[tools['python']['path']] = tools['python']['sha256']
        resources()
        emit(evidence / 'pg-supplier.json', {'schema': 1, 'status': 'NEW_HOSTED_PG176_SOURCE_BUILD', 'qualified': False,
            'historicalBinaryIdentityClaimed': False, 'source': pin, 'recipe': recipe, 'makeJobs': 2, 'packages': packages,
            'installedPrefix': str(PG), 'installed': installed, 'images': images,
            'configurationSha256': {name: sha(pg_source / name) for name in ('config.log', 'config.status', 'src/Makefile.global')},
            'buildExit': 0, 'childrenEndedNaturally': True})
        emit(evidence / 'native-inputs.json', {'images': images, 'supplierReceiptSha256': sha(evidence / 'pg-supplier.json'),
             'consumerJdk': host['consumer_jdk'], 'systemLibraries': host['libraries'], 'bundleHelperSha256': cfg['source']['bundle_helper_sha256']})
        phase_deadline = None
        return images

    common = ['--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache', '--console=plain',
        '--project-cache-dir', str(run / 'project-cache'), '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=768m -Djava.io.tmpdir=' + str(run / 'tmp'),
        '-Dorg.gradle.vfs.watch=false', '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false',
        '-Pkotlin.project.persistent.dir=' + str(run / 'kotlin'), '-Porg.gradle.java.installations.auto-download=false',
        '-PkiraUseMavenLocal=false', '-PkiraOwnedPgVerificationRepository=' + str(runtime / 'maven'),
        '-I', str(control / 'runtime-repositories.init.gradle'), '-I', str(control / 'normal-runtime.init.gradle'), '-x', 'jacocoTestReport']
    tasks = ['compileKotlin', 'compileTestKotlin', 'test', '--tests', CLASS]
    try:
        # The always-run workflow step handles a failed download only as an unstarted cleanup.
        require(os.environ.get('KIRA_QCAP_PRIOR_STATUS') == 'success', 'Earlier workflow acquisition failed or was cancelled')
        stage = 'seed-import'
        resources(admission=True)
        producer = imp.reuse_pin()
        artifact = checked(work / 'reuse-input')
        require({p.name for p in artifact.iterdir()} == set(imp.REUSE_FILES), 'Exact three artifact inputs only')
        reuse_input = work / 'runtime-import/reuse-input'
        reuse_input.mkdir(mode=0o700)
        for name, limit in imp.REUSE_FILES.items():
            write_bytes(reuse_input / name, read_bytes(artifact / name, limit))
        (runtime / 'maven').mkdir(mode=0o700)
        # Deliberate hosted FILE-ONLY consumption: these are independently pinned ORIGINAL producer
        # inputs, NOT current inputs. The unchanged hosted main and its JDK-equality rule are not run.
        manifest = imp.import_runtime(runtime, work / 'runtime-import', producer, cfg['producer_inputs'])
        require(imp.files(source / imp.BINDING['driver']['recipe_path']) == imp.BINDING['driver']['recipe_files'], 'Admitted supplier recipe changed')
        emit(runtime / 'runtime-manifest.json', manifest)
        emit(evidence / 'local-runtime-consumption.json', {'producer': producer, 'producerInputs': cfg['producer_inputs'],
             'consumerJdk': host['consumer_jdk'], 'consumerJdkEqualsProducer': host['consumer_jdk']['sha256'] == cfg['producer_inputs']['jdk'],
             'mode': 'HOSTED_FILE_ONLY_EXACT31_IMPORT', 'qualified': False, 'historicalBinaryIdentityClaimed': False})
        for name in ('test-home', 'tmp', 'gradle-home', 'project-cache', 'kotlin'):
            (run / name).mkdir(mode=0o700)
        owner.activate()
        stage = 'exact-source'
        require(command([host['tools']['git']['path'], 'rev-parse', 'HEAD'], 'carrier-sha', collect=True, cwd=PACKET).decode().strip() == host['carrier'] and
                command([host['tools']['git']['path'], 'rev-parse', 'HEAD^@'], 'carrier-parent', collect=True, cwd=PACKET).decode().strip() == cfg['source']['sha'],
                'Exact single source-parent carrier required')
        before = inventory()
        emit(run / 'source-inventory.json', {'files': before})
        emit(evidence / 'source-before.json', before)
        stage = 'native-supplier'
        image_pins = build_native()
        stage = 'full-source-compilation'
        resources(admission=True)
        gradle_attempted = True
        command(['./gradlew', *common, '-I', str(PACKET / 'compile-only.init.gradle'), *tasks], 'gradle-prepare',
                cfg['limits']['compile_seconds'], child_env={**env, 'KIRA_QCAP_COMPILE_ONLY': 'true'}, log=True)
        stop_gradle()
        prepare_barrier = owner.barrier(natural_timeout=5, term_timeout=10, kill_timeout=5)
        emit(evidence / 'preparation-children.json', prepare_barrier)
        require(prepare_barrier['absent'] and not prepare_barrier['forced'] and not prepare_barrier['errors'], 'Preparation workers did not naturally end')
        copy_receipts('preparation')
        preparation_saved = True
        states = read_json(run / 'backend-build/public-source-verification/task-outcomes.json')
        require(all(states[name]['executed'] and states[name]['didWork'] and not any(states[name][key] for key in ('skipped', 'upToDate', 'noSource', 'failure'))
                    for name in ('compileKotlin', 'compileTestKotlin')) and not states['test']['didWork'] and states['test']['skipped'], 'Actual full compilation / no native preparation test required')
        require(not list((run / 'backend-build/test-results/test').glob('*.xml')), 'Preparation must not run tests')
        resources(admission=True)  # Server starts only after exact supplier inputs and real full compilation.

        stage = 'private-pg-setup'
        exact_root(dbroot)
        for path, pin in image_pins.items():
            require(sha(checked(Path(path))) == pin, 'New native supplier image changed')
        data = dbroot / 'data'
        pg_env = {'PATH': str(PG / 'bin') + ':/usr/bin:/bin', 'HOME': str(dbroot), 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC'}
        boot_password, password = secrets.token_hex(32), secrets.token_hex(32)
        secret_values.extend((boot_password.encode(), password.encode()))
        write_bytes(dbroot / 'init-password', (boot_password + '\n').encode())
        command([str(PG / 'bin/initdb'), '-D', str(data), '--locale=C', '--encoding=UTF8', '--auth-local=scram-sha-256',
                 '--auth-host=scram-sha-256', '--username=qcap_fixture_boot', '--pwfile=' + str(dbroot / 'init-password'), '--no-instructions'],
                'private-initdb', 90, child_env=pg_env, cwd=dbroot)
        (dbroot / 'init-password').unlink()
        command([host['tools']['openssl']['path'], 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1', '-config', '/dev/null',
                 '-subj', '/CN=Kira disposable loopback fixture', '-addext', 'subjectAltName=IP:127.0.0.1',
                 '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign',
                 '-addext', 'extendedKeyUsage=serverAuth', '-keyout', str(dbroot / 'server.key'), '-out', str(dbroot / 'server.crt')],
                'private-tls-certificate', child_env=pg_env, cwd=dbroot)
        trust = private / 'trust.pem'
        write_bytes(trust, read_bytes(dbroot / 'server.crt', 16384))
        port, database, username = cfg['port'], cfg['database'], cfg['username']
        require(type(port) is int and 1024 < port < 65536 and re.fullmatch(r'kira_qcap_it_[a-z0-9_]{1,40}', database) and username == 'kira_qcap_fixture', 'Fixed disposable fixture only')
        with (data / 'postgresql.conf').open('a') as target:
            target.write("\nlisten_addresses='127.0.0.1'\nport=" + str(port) + "\nunix_socket_directories=''\nmax_connections=16\nshared_buffers='32MB'\n"
                         "ssl=on\nssl_cert_file='" + str(dbroot / 'server.crt') + "'\nssl_key_file='" + str(dbroot / 'server.key') + "'\n"
                         "password_encryption='scram-sha-256'\nssl_min_protocol_version='TLSv1.2'\nlogging_collector=off\nlog_statement='none'\nlog_min_error_statement='panic'\n")
        (data / 'pg_hba.conf').write_text('hostssl postgres qcap_fixture_boot 127.0.0.1/32 scram-sha-256\n' +
            f'hostssl {database} {username} 127.0.0.1/32 scram-sha-256\nhost all all 0.0.0.0/0 reject\nhost all all ::/0 reject\n')
        require(owner.active, 'Never start the server outside acquired ownership')
        resources(admission=True)
        server = subprocess.Popen([str(PG / 'bin/postgres'), '-D', str(data)], cwd=dbroot, env=pg_env, stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        handles.append(server); owner.track(); server_identity = ownership.process_identity(server.pid)
        require(os.readlink(f'/proc/{server.pid}/ns/net') == os.readlink('/proc/self/ns/net'), 'Direct same-host PG namespace required')
        deadline = time.monotonic() + 20
        while True:
            resources()
            require(server.poll() is None and time.monotonic() < deadline, 'Owned PG readiness failed')
            lines = (data / 'postmaster.pid').read_text().splitlines() if (data / 'postmaster.pid').is_file() else []
            if len(lines) >= 8 and lines[0] == str(server.pid) and lines[3] == str(port) and lines[7].strip() == 'ready':
                break
            time.sleep(0.1)
        boot_pass = private / 'bootstrap.pgpass'
        write_bytes(boot_pass, f'127.0.0.1:{port}:postgres:qcap_fixture_boot:{boot_password}\n'.encode())
        native_env = {'PATH': '/nonexistent', 'HOME': str(private), 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC',
            'PGHOST': '127.0.0.1', 'PGHOSTADDR': '127.0.0.1', 'PGPORT': str(port), 'PGDATABASE': 'postgres', 'PGUSER': 'qcap_fixture_boot',
            'PGPASSFILE': str(boot_pass), 'PGSSLMODE': 'verify-full', 'PGSSLROOTCERT': str(trust), 'PGGSSENCMODE': 'disable',
            'PGCHANNELBINDING': 'require', 'PGREQUIREAUTH': 'scram-sha-256', 'PGCONNECT_TIMEOUT': '2'}
        psql = [str(PG / 'bin/psql'), '-X', '-q', '-A', '-t', '--no-password', '-v', 'ON_ERROR_STOP=1']
        # Password travels only on this private pipe; argv/environment/logs never contain it.
        sql = f"CREATE ROLE {username} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '{password}';\nCREATE DATABASE {database} OWNER {username};\n"
        command(psql, 'private-bootstrap-sql', child_env=native_env, payload=sql.encode())
        boot_pass.unlink()
        passfile = private / 'fixture.pgpass'
        write_bytes(passfile, f'127.0.0.1:{port}:{database}:{username}:{password}\n'.encode())
        native_env.update(PGDATABASE=database, PGUSER=username, PGPASSFILE=str(passfile))
        witness = command(psql, 'disposable-source-witness', child_env=native_env, collect=True,
            payload=b"SELECT current_setting('server_version_num')::int=170006 AND inet_server_addr()='127.0.0.1'::inet AND NOT pg_is_in_recovery() AND (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()) AND NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public');\n")
        require(witness == b't\n', 'Real version/TLS/empty/nonsuperuser source witness failed')
        emit(evidence / 'disposable-source-witness.json', {'version': 170006, 'address': '127.0.0.1', 'port': port, 'tls': True, 'trustSha256': sha(trust),
            'requiredClientAuth': 'SCRAM_SHA_256_PLUS', 'emptyPublicSchema': True, 'superuser': False, 'serverIdentity': server_identity})
        values = {'PORT': str(port), 'DATABASE': database, 'USER': username, 'PASSFILE': str(passfile), 'TRUST_FILE': str(trust),
                  'TRUST_SHA256': sha(trust), 'ROOT': str(private), 'BUNDLE_HELPER': str(source / 'scripts/db/backup_bundle.py')}
        for key, name in (('PSQL', PG / 'bin/psql'), ('PG_DUMP', PG / 'bin/pg_dump'), ('PG_RESTORE', PG / 'bin/pg_restore'), ('PYTHON', Path(host['tools']['python']['path']))):
            values[key], values[key + '_SHA256'] = str(name), image_pins[str(name)]
        test_env = {**env, 'KIRA_QCAP_IT': 'disposable-local-17.6', **{'KIRA_QCAP_IT_' + key: value for key, value in values.items()}}
        resources(admission=True)
        stage, native_started = 'native-class3', True
        command(['./gradlew', *common, *tasks], 'gradle-native', cfg['limits']['native_seconds'], child_env=test_env, log=True)
        native_ok = True
    except BaseException as error:
        errors.append({'stage': stage, 'type': type(error).__name__})
    finally:
        cleanup_started = time.monotonic()
        cleanup_deadline = cleanup_started + cfg['limits']['cleanup_reserve_seconds']
        handles_ended = False
        if owner.active:
            try:
                stop_gradle()
            except BaseException as error:
                errors.append({'stage': 'gradle-stop', 'type': type(error).__name__})
            try:
                if server is not None:
                    require(server.poll() is None, 'Owned PG exited before explicit cleanup')
                    exact_root(dbroot)
                    require(ownership.same_identity(server_identity, ownership.process_identity(server.pid)), 'PG original custody changed')
                    require((dbroot / 'data/postmaster.pid').read_text().splitlines()[0] == str(server.pid), 'Never stop a substituted postmaster')
                    command([str(PG / 'bin/pg_ctl'), '-D', str(dbroot / 'data'), '-m', 'fast', '-w', '-t', '30', 'stop'],
                            'owned-pg-stop', 35, child_env=pg_env, cwd=dbroot, cleanup=True)
                    require(server.wait(timeout=5) == 0, 'Owned PG did not stop cleanly')
            except BaseException as error:
                errors.append({'stage': 'pg-stop', 'type': type(error).__name__})
            try:
                emit(evidence / 'source-after.json', inventory(cleanup=True))
            except BaseException as error:
                errors.append({'stage': 'complete-source-after', 'type': type(error).__name__})
            try:
                barrier = owner.barrier(natural_timeout=5, term_timeout=10, kill_timeout=5)
                barrier_completed = True
            except BaseException as error:
                barrier = getattr(error, 'receipt', None)  # Diagnostic only; a thrown barrier is never disposal authority.
                errors.append({'stage': 'final-owned-barrier', 'type': type(error).__name__})
            try:
                for handle in handles:
                    require(handle.poll() is not None, 'Known child handle did not end')
                handles_ended = True
            except BaseException as error:
                errors.append({'stage': 'known-child-handles', 'type': type(error).__name__})
            try:
                # Restore has its own fresh descendant checks and must be attempted even on a barrier error.
                restored = owner.restore()
            except BaseException as error:
                errors.append({'stage': 'owned-subreaper-restoration', 'type': type(error).__name__})
        else:
            try:
                restored = owner.restore()
                require(not handles and server is None, 'Inactive scope must never have launched a child')
                handles_ended = True
                barrier = {'absent': True, 'forced': False, 'errors': [], 'neverActivated': True}
                barrier_completed = True
            except BaseException as error:
                errors.append({'stage': 'failed-acquisition-restoration', 'type': type(error).__name__})
        safe = bool(barrier_completed and barrier and barrier['absent'] and handles_ended and restored and restored.get('active') is False)
        disposal = {'generatedOutputsRemoved': False, 'syntheticCaptureRemoved': False, 'databaseRemoved': False}
        quiescence_retained = False
        if safe:
            try:
                exact_root(evidence)
                emit(evidence / 'owned-quiescence.json', {'children': barrier, 'subreaper': restored,
                     'knownHandlesEnded': handles_ended, 'nativeInvocationAttempted': native_started, 'productCleanupClaimed': False})
                quiescence_retained = True  # Retain actual writer/child evidence BEFORE disposable synthetic files.
                for path in owned:
                    exact_root(path)
                require(not root_mismatches, 'Changed custody never authorizes mutable-output reads')
                if gradle_attempted and not preparation_saved:
                    copy_receipts('preparation')
                if native_started:
                    copy_receipts('native')
                for path, name in ((control / 'binding.json', 'execution-binding.json'), (control / 'selection.json', 'selection.json'),
                                   (runtime / 'runtime-manifest.json', 'runtime-manifest.json')):
                    if os.path.lexists(path):
                        write_bytes(evidence / name, read_bytes(path))
                if native_started:
                    files = list((run / 'backend-build/test-results/test').glob('*.xml'))
                    require([p.name for p in files] == ['TEST-' + CLASS + '.xml'], 'Exact native XML set required')
                    raw = read_bytes(files[0], 1024 * 1024)
                    require(b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper() and
                            b'PRIVATE KEY' not in raw and not any(secret in raw for secret in secret_values), 'Bounded nonsecret native XML required')
                    suite = ET.fromstring(raw)
                    write_bytes(evidence / 'native' / files[0].name, raw)  # Exact bytes, never raw logs/dumps or rewritten XML.
                if native_ok:
                    require(suite.get('tests') == '3' and all(suite.get(key) == '0' for key in ('failures', 'errors', 'skipped')), 'Three real native passes required')
                    require(sorted((row.get('classname'), row.get('name')) for row in suite.findall('testcase')) == sorted((CLASS, name) for name in METHODS), 'Actual native method identities differ')
                    require(all(not any(row.find(key) is not None for key in ('failure', 'error', 'skipped')) for row in suite.findall('testcase')), 'Native testcase was not a pass')
                    states = read_json(run / 'backend-build/public-source-verification/task-outcomes.json')['test']
                    require(states['executed'] and states['didWork'] and not any(states[key] for key in ('skipped', 'upToDate', 'noSource', 'failure')), 'Real normal test task required')
                # Source files are immutable inputs; no new subprocess is launched after owner.restore.
                require({name: sha(checked(source / name)) for name in expected} == expected, 'Source bytes changed after execution')
            except BaseException as error:
                errors.append({'stage': 'closed-evidence-validation', 'type': type(error).__name__})
            # Disposal depends on exact ownership + ended writers, NOT test/evidence validation success.
            try:
                require(quiescence_retained and shutil.rmtree.avoids_symlink_attacks, 'Owned evidence/disposal unavailable')
                for path in owned:
                    exact_root(path)  # Sticky initial identities, including the capture root; never rebaseline.
                require(not root_mismatches, 'An earlier custody mismatch forbids disposal')
                shutil.rmtree(work)  # Explicit owner decision: this job's synthetic stages only, after ended writers.
                require(not os.path.lexists(work), 'Owned temporary root remains')
                disposal.update(generatedOutputsRemoved=True, syntheticCaptureRemoved=True, databaseRemoved=True)
            except BaseException as error:
                errors.append({'stage': 'owned-files-disposal', 'type': type(error).__name__})
        if time.monotonic() > cleanup_deadline:
            errors.append({'stage': 'cleanup-reserve', 'type': 'TimeoutError'})
        release_lane()
        exact_root(evidence)
        emit(evidence / 'result.json', {'profile': PROFILE, 'source': cfg['source'], 'commands': records,
             'errors': errors, 'children': barrier, 'subreaper': restored, 'disposal': disposal, 'shippingAccepted': False,
             'jobLockReleased': True, 'knownHandlesEnded': handles_ended, 'resources': observations,
             'originalRootIdentities': {str(path): value for path, value in owned.items()}, 'observedRootIdentities': root_observations,
             'rootCustodyMismatches': root_mismatches, 'barrierCompleted': barrier_completed, 'ownershipLastReceipt': owner.last_receipt,
             'nativeInvocationAttempted': native_started, 'nativeGradleExitZero': native_ok,
             'cleanupElapsedSeconds': time.monotonic() - cleanup_started, 'vmDisposalIsProductCleanup': False,
             'passed': native_ok and safe and not errors and not barrier['forced'] and not barrier['errors']})
    return 0 if native_ok and safe and not errors and not barrier['forced'] and not barrier['errors'] else 1


if __name__ == '__main__':
    try:
        require(len(sys.argv) == 2 and sys.argv[1] in ('--admit-outputs', '--run', '--publish-outputs'), 'Exact hosted mode required')
        cfg = admitted()
        if sys.argv[1] == '--admit-outputs':
            admit_outputs(cfg)
        elif sys.argv[1] == '--publish-outputs':
            publish_outputs()
        else:
            raise SystemExit(main(cfg))
    except (Exception, KeyboardInterrupt) as error:
        print('Hosted native3 refused; exception type:', type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
    finally:
        release_lane()
