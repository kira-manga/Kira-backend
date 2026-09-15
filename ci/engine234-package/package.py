"""Closed full15 package derivative of the retained Engine5 Darwin ownership/capture runner.
No tests, simulator, App checkout, release route or registry writer. NOT_RUN pending admission.
"""
import hashlib, json, os, platform, re, select, shutil, signal, subprocess, sys, time
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree as ET

CONTROL = Path(__file__).resolve().parents[2]
RUN = Path(os.environ['ENGINE234_PACKAGE_RUN'])
REPORTS = RUN / 'reports'
OWNER = os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
VERSION = '0.1.0-engine234-8cb9ead84983-macos-' + OWNER
XCODE = {'neutral': '/Applications/Xcode_26.0.1.app/Contents/Developer'}
MIB = 1024 * 1024
JSON_LIMIT, TOOL_CAPTURE_LIMIT, TOOL_LOG_LIMIT = MIB, MIB, 4 * MIB
GRADLE_LOG_LIMIT, XML_LIMIT, EVIDENCE_LIMIT = 8 * MIB, 4 * MIB, 80 * MIB
DISK_FLOOR = 8 * 1024 * MIB
TOOL_SIGNALS = []
DEADLINE_SECONDS = {'sdk': 480, 'publish': 1200}
DEADLINE_GRACE_SECONDS, DEADLINE_REAP_SECONDS = 10, 2
PROCESS_IDENTITY_LIMIT = 32

def deadline_capabilities():
    # CPython documents waitid on macOS from 3.13; reject an older/different interpreter, never install one.
    require(all(hasattr(os, name) for name in ('waitid', 'P_PID', 'WEXITED', 'WNOHANG', 'WNOWAIT', 'CLD_EXITED', 'killpg')),
            'Python lacks non-reaping POSIX deadline ownership APIs; no installation/fallback')
    require(signal.getsignal(signal.SIGCHLD) == signal.SIG_DFL,
            'Deadline requires default SIGCHLD handling; an automatic reaper would lose group ownership')


def identity_rows(raw):
    # Numeric fields plus ps comm, not argv/environment. Callers retain only their already-owned targets.
    for row in raw.splitlines():
        parts = row.split(None, 5)
        require(len(parts) == 6 and all(value.isdigit() for value in parts[:4]), 'Malformed process identity census')
        yield {'pid': int(parts[0]), 'uid': int(parts[1]), 'ppid': int(parts[2]), 'pgid': int(parts[3]),
               'state': parts[4][:16], 'executable': PurePosixPath(parts[5]).name[:256]}


def deadline_live_pids(pgid, seconds=1, identity=None):
    # Reuse bounded small-tool capture, never pipe/buffer Gradle through command(). No census during TERM grace.
    rows = command(['ps', '-axww', '-o', 'pid=,uid=,ppid=,pgid=,stat=,comm='], seconds=seconds, log_output=False)
    live, owned = [], []
    for row in identity_rows(rows):
        if row['pgid'] == pgid:
            row['basis'] = ['process-group-held-by-unreaped-leader']
            owned.append(row) # Keep zombie identity too, without changing the live/quiet predicate.
            if not row['state'].startswith('Z'): live.append(row['pid'])
    require(len(live) <= 1024, 'Oversized deadline group inventory')
    if identity is not None:
        identity.update(observedMonotonicSeconds=time.monotonic(), ownedCount=len(owned),
                        omittedOwnedCount=max(0, len(owned) - PROCESS_IDENTITY_LIMIT),
                        processes=sorted(owned, key=lambda row: row['pid'])[:PROCESS_IDENTITY_LIMIT])
    return sorted(live)


def deadline():
    scope()
    require(len(sys.argv) >= 4 and sys.argv[2] in DEADLINE_SECONDS, 'Expected closed deadline sdk/publish role and argv')
    deadline_capabilities()
    role, argv = sys.argv[2], sys.argv[3:]
    started = time.monotonic()
    expires = started + DEADLINE_SECONDS[role]
    receipt = {'deadline': 'INCOMPLETE', 'role': role, 'argv': argv, 'seconds': DEADLINE_SECONDS[role],
               'graceSeconds': DEADLINE_GRACE_SECONDS, 'reapSeconds': DEADLINE_REAP_SECONDS,
               'startNewSession': True, 'childPid': None, 'pgid': None, 'childExitCode': None,
               'timedOut': False, 'forced': False, 'groupQuiet': False, 'leaderReaped': False,
               'signalAttempts': [], 'errors': {}, 'startMonotonicSeconds': started, 'lastGroupIdentity': {}}
    process, quiet, leader_owned = None, False, True
    first_signal, repeated_signal = None, False
    handlers = {}

    def interrupted(signum, _frame):
        # Never raise across Popen's spawn-to-assignment window; repeated signals cannot reset the grace clock.
        nonlocal first_signal, repeated_signal
        if first_signal is None: first_signal = signum
        else: repeated_signal = True

    def error(phase, failure): receipt['errors'][phase] = str(failure)[:1000]

    def checkpoint():
        try:
            receipt.update(elapsedSeconds=time.monotonic() - started,
                           interruptedBy=signal.Signals(first_signal).name if first_signal is not None else None,
                           repeatedInterruption=repeated_signal)
            save(REPORTS / (role + '-deadline.json'), receipt)
        except BaseException as failure:
            error('receipt', failure)
            return False
        return True

    def peek():
        nonlocal leader_owned
        try: return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        except ChildProcessError:
            leader_owned = False
            raise RuntimeError('Leader was unexpectedly reaped; refuse signaling a potentially recycled PGID')

    def send(attempt, sig):
        attempt['attemptedAfterSeconds'] = time.monotonic() - started
        try:
            os.killpg(process.pid, sig)
            attempt['outcome'] = 'sent'
        except ProcessLookupError: attempt['outcome'] = 'alreadyGone'
        except BaseException as failure:
            attempt['outcome'] = 'failed'
            error(sig.name, failure)

    def force_group():
        receipt['forced'] = True
        # No wait()/poll() until all group signaling is finished: even an exit-0 leader pins the numeric PGID.
        try: peek()
        except BaseException as failure: error('ownership', failure)
        if not leader_owned: return
        attempts = [{'pgid': process.pid, 'signal': sig.name, 'outcome': 'planned'}
                    for sig in (signal.SIGTERM, signal.SIGKILL)]
        receipt['signalAttempts'] = attempts
        checkpoint() # Both intents before TERM. Receipt failure must not abandon this explicitly owned group.
        # Diagnostic snapshot at the existing signal-receipt boundary, after ownership/intents and before grace.
        # This is not the KILL-instant membership; a diagnostic failure must not abandon the owned group.
        receipt['beforeSignalGroupIdentity'] = {}
        try: deadline_live_pids(process.pid, identity=receipt['beforeSignalGroupIdentity'])
        except BaseException as failure: receipt['beforeSignalGroupIdentity']['error'] = str(failure)[:1000]
        checkpoint()
        grace_end = time.monotonic() + DEADLINE_GRACE_SECONDS
        try:
            send(attempts[0], signal.SIGTERM)
            while time.monotonic() < grace_end:
                time.sleep(min(0.1, max(0, grace_end - time.monotonic())))
        finally:
            # No census, receipt writes or subprocess waits between TERM and KILL; grace never restarts.
            send(attempts[1], signal.SIGKILL)
        checkpoint()

    try:
        require(checkpoint(), 'Cannot record deadline start')
        for sig in (signal.SIGTERM, signal.SIGINT):
            handlers[sig] = signal.signal(sig, interrupted)
        require(first_signal is None, 'Interrupted before deadline child spawn')
        # Inherit stdout/stderr into the unchanged external bounded capture; argv is not interpreted by a shell.
        process = subprocess.Popen(argv, start_new_session=True)
        receipt.update(childPid=process.pid, pgid=process.pid)
        require(checkpoint(), 'Cannot record deadline child ownership')
        while True:
            if first_signal is not None:
                receipt['reason'] = 'interrupted'
                break
            if time.monotonic() >= expires:
                receipt.update(timedOut=True, reason='timeout')
                break
            info = peek()
            if info is not None:
                remaining = expires - time.monotonic()
                if remaining <= 0: continue
                live = deadline_live_pids(process.pid, seconds=min(1, remaining), identity=receipt['lastGroupIdentity'])
                receipt['lastLivePids'] = live
                if first_signal is not None or time.monotonic() >= expires: continue
                if not live:
                    quiet = True
                    break
                if info.si_code != os.CLD_EXITED or info.si_status != 0:
                    receipt['reason'] = 'child-failed-with-live-group'
                    break
            time.sleep(min(0.1, max(0, expires - time.monotonic())))
    except BaseException as failure:
        error('run', failure)
    finally:
        try:
            if process is not None:
                try:
                    if not quiet:
                        force_group()
                        receipt['lastLivePids'] = deadline_live_pids(
                            process.pid, identity=receipt['lastGroupIdentity'] if leader_owned else None)
                        quiet = not receipt['lastLivePids']
                except BaseException as failure: error('groupCleanup', failure)
                finally:
                    # Signaling is now closed forever, including if reaping/census/receipt recording fails.
                    try:
                        receipt['childExitCode'] = process.wait(timeout=DEADLINE_REAP_SECONDS)
                        receipt['leaderReaped'] = True
                    except BaseException as failure: error('reap', failure)
            receipt['groupQuiet'] = quiet
            receipt['deadline'] = 'PASS' if (quiet and receipt['leaderReaped'] and receipt['childExitCode'] == 0
                and not receipt['forced'] and first_signal is None and not receipt['errors']) else 'FAIL'
            checkpoint()
        finally:
            for sig, handler in handlers.items(): signal.signal(sig, handler)
    # Also reject an interruption or receipt failure in the final checkpoint/handler-restoration window.
    if first_signal is not None or receipt['errors']:
        receipt['deadline'] = 'FAIL'
        checkpoint()
    require(receipt['deadline'] == 'PASS', 'Deadline phase failed: ' + role)


def require(ok, message):
    if not ok: raise RuntimeError(message)


def digest(path):
    with path.open('rb') as stream:
        value = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''): value.update(block)
        return value.hexdigest()


def save(path, value):
    data = (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()
    require(len(data) <= JSON_LIMIT, 'Oversized JSON; refuse partial receipt: ' + path.name)
    path.write_bytes(data)


def limit_receipt(name, limit, observed):
    save(REPORTS / (name + '.limit.json'), {'status': 'FAIL', 'truncated': True,
         'file': name, 'limitBytes': limit, 'observedBytesAtLeast': observed})


def append_log(name, data, limit=TOOL_LOG_LIMIT):
    path = REPORTS / name
    size = path.stat().st_size if path.exists() else 0
    with path.open('ab') as log: log.write(data[:max(0, limit - size)])
    if size + len(data) > limit: limit_receipt(name, limit, size + len(data))


def capture():
    # Drain the bounded Gradle pipeline even after the cap, retaining separate real-link receipts.
    scope()
    path = Path(sys.argv[2]) # The exact owned path also makes a stranded capture visible to cleanup census.
    require(path in {REPORTS / 'neutral.log', REPORTS / 'sdk.log'}, 'Unknown Gradle log')
    name = path.stem
    observed = 0
    with path.open('xb') as log:
        for block in iter(lambda: sys.stdin.buffer.read(65536), b''):
            room = max(0, GRADLE_LOG_LIMIT - observed)
            log.write(block[:room])
            if observed <= GRADLE_LOG_LIMIT < observed + len(block):
                limit_receipt(name + '.log', GRADLE_LOG_LIMIT, observed + len(block))
            observed += len(block)
    truncated = observed > GRADLE_LOG_LIMIT
    save(REPORTS / (name + '-capture.json'), {'complete': True, 'truncated': truncated,
         'observedBytes': observed, 'retainedBytes': min(observed, GRADLE_LOG_LIMIT)})
    require(not truncated, 'Gradle log cap exceeded: ' + name)


def command(argv, seconds=20, role='neutral', log='tools.log', log_output=True):
    process = subprocess.Popen(argv, env=dict(os.environ, DEVELOPER_DIR=XCODE[role],
                               GRADLE_USER_HOME=str(RUN / 'gradle-home')),
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    data, observed = bytearray(), 0
    record = {'argv': argv, 'developerDir': XCODE[role], 'timeoutSeconds': seconds}
    deadline = time.monotonic() + seconds
    try:
        while True:
            left = deadline - time.monotonic()
            if left <= 0 or not select.select([process.stdout], [], [], left)[0]:
                raise subprocess.TimeoutExpired(argv, seconds)
            block = os.read(process.stdout.fileno(), 65536)
            if not block: break
            data.extend(block[:max(0, TOOL_CAPTURE_LIMIT - len(data))])
            if observed <= TOOL_CAPTURE_LIMIT < observed + len(block):
                limit_receipt(log, TOOL_CAPTURE_LIMIT, observed + len(block))
            observed += len(block)
        process.wait(timeout=max(0, deadline - time.monotonic()))
    except Exception as failure:
        record['error'] = str(failure)[:1000]
        raise
    finally:
        if process.poll() is None:
            attempt = {'pid': process.pid, 'signal': 'SIGKILL', 'outcome': 'attempting'}
            TOOL_SIGNALS.append(attempt)
            signal_path = REPORTS / (sys.argv[1] + '-command-signals.json')
            save(signal_path, TOOL_SIGNALS) # Persist intent before every explicit timeout signal.
            try:
                process.kill()
                attempt['outcome'] = 'sent'
                process.wait(timeout=2)
            except Exception as failure: attempt['outcome'] = str(failure)[:1000]
            finally: save(signal_path, TOOL_SIGNALS)
        process.stdout.close()
        record.update(exit=process.returncode, observedBytes=observed, truncated=observed > TOOL_CAPTURE_LIMIT)
        append_log(log, (json.dumps(record) + '\n').encode() + (bytes(data) if log_output else b'[output not logged]') + b'\n',
                   TOOL_CAPTURE_LIMIT if log == 'gradle-stop.log' else TOOL_LOG_LIMIT)
    require(observed <= TOOL_CAPTURE_LIMIT, 'Command output cap exceeded; refuse partial output')
    if process.returncode: raise subprocess.CalledProcessError(process.returncode, argv)
    return data.decode('utf-8', errors='replace')


def scope():
    require(RUN.is_absolute() and RUN.parent.resolve() == Path(os.environ['RUNNER_TEMP']).resolve()
            and RUN.name == 'engine234-package-' + OWNER and not RUN.is_symlink(), 'Unsafe run root')
    require((RUN / 'owner').read_text() == OWNER, 'Missing run ownership marker')
    require(REPORTS.is_dir() and not REPORTS.is_symlink(), 'Unsafe report directory')


def prepare():
    require(os.environ['GITHUB_REPOSITORY'] == 'kira-manga/Kira-backend'
            and os.environ['GITHUB_EVENT_NAME'] == 'push'
            and os.environ['GITHUB_REF'] == 'refs/heads/remediation/engine234-package-candidate-20260915-01'
            and os.environ['GITHUB_RUN_ATTEMPT'] == '1', 'Wrong one-attempt public validation lane')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    require(event['repository']['full_name'] == 'kira-manga/Kira-backend'
            and event['repository']['private'] is False, 'Public-source artifact lane only')
    require(RUN.is_absolute() and RUN.parent.resolve() == Path(os.environ['RUNNER_TEMP']).resolve()
            and RUN.name == 'engine234-package-' + OWNER, 'Unsafe new run root')
    RUN.mkdir(mode=0o700, exist_ok=False)
    REPORTS.mkdir(mode=0o700)
    (RUN / 'owner').write_text(OWNER)
    scope()
    require(sys.platform == 'darwin' and platform.machine() == 'arm64', 'Standard ARM macOS runner required')
    deadline_capabilities()
    for name in ('gradle-home', 'konan', 'repository', 'home', 'tmp', 'android-sdk'):
        (RUN / name).mkdir(mode=0o700)
    free = shutil.disk_usage(RUN).free
    save(REPORTS / 'disk.json', {'freeBytes': free, 'minimumFreeBytes': DISK_FLOOR})
    require(free >= DISK_FLOOR, 'Less than 8 GiB free')
    require(Path(XCODE['neutral']).is_dir(), 'Required retained producer Xcode26.0.1 absent; no fallback')
    java = command([os.environ['JAVA_HOME'] + '/bin/java', '-version'])
    require(re.search(r'version "21[.\"]', java), 'Java21 required')
    xcode = command(['xcodebuild', '-version'])
    sdks = {target: command(['xcrun', '--sdk', target, '--show-sdk-version']).strip()
            for target in ('iphoneos', 'iphonesimulator')}
    require(xcode.splitlines()[0] == 'Xcode 26.0.1' and set(sdks.values()) == {'26.0'}, 'Wrong producer Xcode/SDK')
    stock = Path(os.environ['ANDROID_HOME'])
    manager = stock / 'cmdline-tools/latest/bin/sdkmanager'
    require(manager.is_file() and os.access(manager, os.X_OK), 'Stock Android command-line sdkmanager absent')
    licenses = stock / 'licenses/android-sdk-license'
    require(licenses.is_file() and not licenses.is_symlink() and licenses.stat().st_size < 65536,
            'Stock accepted Android SDK license required; no broad --licenses acceptance')
    (RUN / 'android-sdk/licenses').mkdir()
    shutil.copyfile(licenses, RUN / 'android-sdk/licenses/android-sdk-license')
    with Path(os.environ['GITHUB_ENV']).open('a') as env:
        env.write('ENGINE234_SDKMANAGER=' + str(manager) + '\n')
    save(REPORTS / 'tools.json', {'machine': platform.machine(), 'macOS': platform.mac_ver()[0],
        'python': sys.version, 'logicalCPUs': os.cpu_count(), 'memoryBytes': command(['sysctl', '-n', 'hw.memsize']).strip(),
        'ImageOS': os.environ.get('ImageOS'), 'ImageVersion': os.environ.get('ImageVersion'),
        'java': java, 'xcode': xcode, 'appleSDKs': sdks, 'sdkmanager': str(manager),
        'sdkmanagerSha256': digest(manager), 'sdkLicenseSha256': digest(licenses)})
    command([sys.executable, '-B', str(CONTROL / 'ci/engine234-package/proof.py'), 'bind'])
    require(not list(REPORTS.glob('*.limit.json')), 'Preparation output was truncated')


def sdk():
    scope()
    spec = json.loads((CONTROL / 'ci/engine234-package/inputs.json').read_text())['sdk']
    platform_dir = RUN / 'android-sdk/platforms/android-37.0'
    require(all(digest(platform_dir / name) == expected for name, expected in spec['platform_sha256'].items()),
            'SDK37.0 revision2 extension22 platform bytes differ; no alternate package or alias')
    installed = {}
    for path in (RUN / 'android-sdk').rglob('package.xml'):
        local = ET.parse(path).getroot().find('{*}localPackage')
        require(local is not None, 'Missing installed SDK package identity')
        installed[local.attrib['path']] = {'packageXmlSha256': digest(path), 'directory': str(path.parent.relative_to(RUN))}
    require(set(installed) == set(spec['packages']), 'Unexpected or missing SDK package; no broad upgrade')
    props = dict(line.split('=', 1) for line in (RUN / 'android-sdk/build-tools/36.0.0/source.properties').read_text().splitlines()
                 if '=' in line and not line.startswith('#'))
    require(props.get('Pkg.Revision') == '36.0.0', 'Wrong macOS build-tools revision')
    for name in ('sdk-deadline.json', 'sdk-capture.json'):
        result = json.loads((REPORTS / name).read_text())
        require(result.get('deadline') == 'PASS' if name.startswith('sdk-deadline')
                else result.get('complete') is True and result.get('truncated') is False, 'SDK setup incomplete')
    save(REPORTS / 'sdk.json', {'packages': installed, 'platformSha256': spec['platform_sha256'],
        'buildToolsSourcePropertiesSha256': digest(RUN / 'android-sdk/build-tools/36.0.0/source.properties')})


def owned_pids():
    marker = '-Dengine234.package.owner=' + OWNER
    rows = command(['ps', '-axww', '-o', 'pid=,command='], seconds=5, log_output=False).splitlines()
    owned = {}
    for row in rows:
        if row.strip() and (marker in row.split() or str(RUN) + '/' in row):
            pid = int(row.split(None, 1)[0])
            if pid != os.getpid():
                owned[pid] = [basis for basis, matched in (
                    ('exact-owner-argument', marker in row.split()), ('owned-run-path', str(RUN) + '/' in row)) if matched]
    return owned # Same PID selection; values record the existing match basis, never full command text.


def worker_identity(owned):
    # Only the first 32 already-owned PIDs, one bounded query at an existing census/signal boundary, no sampler.
    selected = sorted(owned)[:PROCESS_IDENTITY_LIMIT]
    snapshot = {'ownedCount': len(owned), 'omittedOwnedCount': max(0, len(owned) - PROCESS_IDENTITY_LIMIT),
                'processes': [], 'unobservedPids': selected}
    try:
        if selected:
            raw = command(['ps', '-ww', '-p', ','.join(map(str, selected)), '-o', 'pid=,uid=,ppid=,pgid=,stat=,comm='],
                          seconds=1, log_output=False)
            found = {}
            for row in identity_rows(raw):
                pid = row['pid']
                require(pid in selected and pid not in found, 'Unexpected/duplicate targeted identity row')
                row['basis'] = owned[pid]
                found[pid] = row
            snapshot['processes'] = [found[pid] for pid in sorted(found)]
            snapshot['unobservedPids'] = sorted(set(selected) - found.keys())
    except Exception as failure:
        snapshot['error'] = str(failure)[:1000] # Observation only; does not authorize or suppress any worker signal.
    snapshot['observedMonotonicSeconds'] = time.monotonic()
    return snapshot


def stage_evidence():
    # Called only after cleanup validated ownership. Upload only this byte-bounded snapshot.
    target = REPORTS / 'upload'
    target.mkdir(mode=0o700, exist_ok=False)
    reserve = 64 * 1024
    receipt = {'budget': 'FAIL', 'complete': False, 'limitBytes': EVIDENCE_LIMIT, 'files': [], 'omitted': []}
    save(target / 'evidence-budget.json', receipt)
    core = {'bindings.json', 'tools.json', 'publications.json', 'sdk.json', 'result.json', 'cleanup.json', 'candidate-maven.tar.gz'}
    files = sorted([*REPORTS.glob('*.json'), *(REPORTS / 'xml').glob('*.xml'), *REPORTS.glob('*.log'), *REPORTS.glob('*.tsv'), *REPORTS.glob('*.tar.gz')],
                   key=lambda p: (p.name not in core and p.suffix != '.xml', p.suffix == '.log', str(p)))
    receipt['discoveredFiles'] = len(files)
    receipt['omittedByFileCountCap'] = max(0, len(files) - 64)
    used = 0
    for path in files[:64]:
        name = str(path.relative_to(REPORTS))
        cap = 64 * MIB if path.name == 'candidate-maven.tar.gz' else JSON_LIMIT if path.suffix == '.json' else XML_LIMIT if path.suffix == '.xml' else GRADLE_LOG_LIMIT
        reason = None
        if path.is_symlink() or not path.is_file() or (REPORTS / 'xml').is_symlink() or not path.resolve().is_relative_to(REPORTS.resolve()):
            reason = 'not an owned regular receipt'
        elif path.stat().st_size > min(cap, EVIDENCE_LIMIT - reserve - used):
            reason = 'file/aggregate byte cap'
        else:
            with path.open('rb') as source: data = source.read(min(cap, EVIDENCE_LIMIT - reserve - used) + 1)
            if len(data) > min(cap, EVIDENCE_LIMIT - reserve - used): reason = 'grew beyond byte cap'
        if reason:
            receipt['omitted'].append({'file': name[:256], 'reason': reason})
            continue
        output = target / name
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data) # Never truncate a required JSON/XML receipt to manufacture a PASS.
        used += len(data)
        receipt['files'].append({'file': name[:256], 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    receipt.update(complete=True, copiedBytes=used, truncatedInputs=bool(list(REPORTS.glob('*.limit.json'))))
    receipt['budget'] = 'PASS' if len(files) <= 64 and not receipt['omitted'] and not receipt['truncatedInputs'] else 'FAIL'
    encoded = (json.dumps(receipt, indent=2, sort_keys=True) + '\n').encode()
    require(len(encoded) <= reserve, 'Evidence inventory exceeded its reserved bound')
    (target / 'evidence-budget.json').write_bytes(encoded)
    return receipt['budget'] == 'PASS'


def cleanup():
    scope()
    receipt = {'cleanup': 'INCOMPLETE', 'errors': {}, 'signalAttempts': [], 'commandSignalAttempts': TOOL_SIGNALS,
               'workersAbsent': False, 'simulator': 'NOT_CREATED; package-only', 'scratchRemoved': False, 'workerIdentity': {}}
    def error(phase, failure): receipt['errors'][phase] = str(failure)[:1000]
    def checkpoint():
        try: save(REPORTS / 'cleanup.json', receipt)
        except Exception as failure:
            error('receipt', failure)
            return False
        return True
    checkpoint()
    try:
        distributions = list((RUN / 'gradle-home/wrapper/dists').glob('gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle'))
        require(len(distributions) <= 1, 'Unexpected Gradle distribution inventory')
        if distributions:
            # Never invoke a missing wrapper or address a shared Gradle home.
            command([str(distributions[0]), '--stop', '--gradle-user-home', str(RUN / 'gradle-home')],
                    seconds=20, log='gradle-stop.log')
        receipt['gradleStop'] = 'PASS' if distributions else 'NOT_INSTALLED'
    except Exception as failure: error('gradleStop', failure)
    checkpoint()
    try:
        for sig in (signal.SIGTERM, signal.SIGKILL):
            candidates = owned_pids()
            identity = worker_identity(candidates)
            confirmed = owned_pids() # Fresh confirmation stays AFTER diagnostics; a failed census never authorizes a signal.
            targets = candidates.keys() & confirmed.keys()
            for row in identity['processes']: row['confirmedForSignal'] = row['pid'] in targets
            receipt['workerIdentity'][sig.name] = identity # Earlier observation, not identity at the signal instant.
            for pid in sorted(targets):
                attempt = {'pid': pid, 'signal': sig.name, 'outcome': 'attempting', 'basis': confirmed[pid]}
                receipt['signalAttempts'].append(attempt)
                require(checkpoint(), 'Cannot record signal intent; refuse unrecorded signal')
                try:
                    os.kill(pid, sig)
                    attempt['outcome'] = 'sent'
                except ProcessLookupError: attempt['outcome'] = 'alreadyGone'
                except Exception as failure:
                    attempt['outcome'] = str(failure)[:1000]
                    error('signal-' + str(pid), failure)
                checkpoint()
            time.sleep(3) # Also re-census initially empty sets; late KILL targets are recorded above.
        remaining = owned_pids()
        receipt['remainingPids'] = sorted(remaining)
        receipt['workerIdentity']['remaining'] = worker_identity(remaining)
        receipt['workersAbsent'] = not receipt['remainingPids']
        require(receipt['workersAbsent'], 'Owned workers remain; retain scratch')
    except Exception as failure: error('workers', failure)
    checkpoint()
    try:
        require(receipt['workersAbsent'], 'Worker absence not proven; retain scratch')
        receipt['workersAbsent'] = False
        before_remove = owned_pids()
        receipt['workersAbsent'] = not before_remove
        receipt['workerIdentity']['beforeScratchRemoval'] = worker_identity(before_remove)
        require(receipt['workersAbsent'], 'Owned workers appeared during cleanup; retain scratch')
        for path in RUN.iterdir():
            if path == REPORTS: continue
            if path.is_dir() and not path.is_symlink(): shutil.rmtree(path)
            else: path.unlink()
        receipt['scratchRemoved'] = set(RUN.iterdir()) == {REPORTS}
    except Exception as failure: error('scratch', failure)
    receipt['cleanup'] = 'PASS' if (receipt['scratchRemoved'] and not receipt['errors']
        and not receipt['signalAttempts'] and not TOOL_SIGNALS and not list(REPORTS.glob('*.limit.json'))) else 'FAIL'
    receipt_ok = checkpoint()
    evidence_ok = stage_evidence()
    require(receipt_ok and receipt['cleanup'] == 'PASS' and evidence_ok,
            'Forced/failed cleanup or incomplete/bounded-out evidence is not a PASS')


if __name__ == '__main__':
    phase = sys.argv[1]
    require(phase in {'prepare', 'sdk', 'cleanup', 'capture', 'deadline'}, 'Unknown closed package phase')
    try:
        globals()[phase]()
    except Exception as failure:
        if REPORTS.is_dir():
            save(REPORTS / (phase + '-failure.json'), {'failure': type(failure).__name__, 'message': str(failure)[:1000]})
        raise
