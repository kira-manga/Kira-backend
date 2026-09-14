"""One public Backend5 PG batch; no dispatch, dependency fallback, release, or deployment."""
import hashlib
import json
import os
import re
import select
import shlex
import shutil
import signal
import stat
import subprocess
import time
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

PG = 'postgres:17.6-alpine'
PREFIX = 'me.manga.kira.backend.tutorial.'
CLASSES = {PREFIX + 'TutorialMediaTransactionIT': 10,
           PREFIX + 'TutorialMediaReconciliationIT': 10,
           PREFIX + 'TutorialMediaIntegrityIT': 8}
CHILD_SHA = '56b66cfe8799123c719eaf048f81c542e5e4129d71c490cae99a38396c2a3385'
PAYLOAD_SHA = '98ff698995c61cfeb8542c84333ff197989595c44c0ac5d0365df71cce803948'
CANCELLED = False


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def interrupted(_signum, _frame):
    global CANCELLED
    CANCELLED = True  # Do not interrupt Popen registration or the finite cleanup phase.


class Batch:
    def __init__(self):
        self.gate = Path(__file__).resolve().parents[2]
        self.backend = self.gate.parent / 'backend'
        self.request = json.loads(Path(__file__).with_suffix('.request.json').read_text())
        name = 'backend5-media-pg-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
        self.run = Path(os.environ['RUNNER_TEMP']) / name
        require(Path(os.environ['BACKEND5_RUN']) == self.run and self.run.is_absolute(), 'Wrong owned run directory')
        self.run.mkdir(mode=0o700, exist_ok=False)
        self.reports = self.run / 'reports'
        self.reports.mkdir(mode=0o700)
        self.owned = [self.run / n for n in ('build', 'gradle', 'project-cache', 'kotlin-cache', 'tmp', 'home', 'bin')]
        for path in self.owned:
            path.mkdir(mode=0o700)
        self.directory_ids = {path: (path.stat().st_dev, path.stat().st_ino) for path in self.owned}
        self.source_outputs = [self.backend / n for n in ('build', '.gradle', '.kotlin')]
        self.token = os.environ.pop('KIRA_PACKAGES_READ_TOKEN', '')
        self.user = os.environ.get('KIRA_PACKAGES_USER', '')
        self.env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME')}
        self.env.update(HOME=str(self.run / 'home'), LANG='C.UTF-8', LC_ALL='C.UTF-8', TZ='UTC',
                        BACKEND5_RUN=str(self.run), GRADLE_USER_HOME=str(self.run / 'gradle'),
                        TMPDIR=str(self.run / 'tmp'), PYTHONDONTWRITEBYTECODE='1',
                        JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=' + str(self.run / 'tmp') + ' -Duser.home=' + str(self.run / 'home'),
                        DOCKER_HOST='unix:///var/run/docker.sock', TESTCONTAINERS_REUSE_ENABLE='false',
                        TESTCONTAINERS_RYUK_DISABLED='false', TESTCONTAINERS_CHECKS_DISABLE='false')
        self.owner = None
        self.commands, self.drains, self.tc = [], [], {}
        self.resources = []
        self.failed, self.forced, self.preserved = False, False, False
        self.gradle_started = self.immediate_stop = self.docker_ready = False
        self.gradle_exit = None
        self.sql_name, self.sql_id = 'backend5-sql-' + os.environ['GITHUB_RUN_ID'], None
        self.sql_requested, self.sql_ok, self.xml_ok, self.clean = False, False, False, False
        self.image_id, self.pg_version = None, None
        self.work_until = time.monotonic() + 22 * 60  # Leave six minutes inside the step for cleanup/readback.

    def resources_now(self, phase):
        available = next((int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines()
                          if line.startswith('MemAvailable:')), None)
        free = shutil.disk_usage(self.run).free
        self.resources.append({'phase': phase, 'free_disk_bytes': free, 'available_ram_bytes': available})
        (self.reports / 'resources.json').write_text(json.dumps(self.resources, indent=2) + '\n')
        require(free >= 8 * 1024**3, '8GiB free-disk floor reached; stop owned work and clean')

    def note(self, message):
        if self.token:
            message = message.replace(self.token, '[REDACTED]')
        print(message, flush=True)
        with (self.reports / 'result.log').open('a') as stream:
            stream.write(message + '\n')

    def drain(self, phase):
        try:
            receipt = self.owner.drain() if self.owner else {'ok': False, 'reason': 'ownership not established'}
        except Exception as failure:
            receipt = {'ok': False, 'reason': type(failure).__name__}
        self.drains.append({'phase': phase, **receipt})
        self.forced |= bool(receipt.get('term') or receipt.get('kill'))
        self.failed |= not receipt['ok'] or self.forced
        return receipt['ok']

    def command(self, argv, phase, seconds=30, extra=None, cleaning=False, sample=False):
        require(self.owner is not None, 'No owned-child capability')
        if not cleaning:
            require(not CANCELLED, 'Cancelled before ' + phase)
            seconds = min(seconds, self.work_until - time.monotonic())
            require(seconds > 0, 'Overall work deadline reached before ' + phase)
        process = self.owner.track(subprocess.Popen(argv, cwd=self.backend, env=self.env | (extra or {}),
                                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                                   stderr=subprocess.STDOUT, start_new_session=True))
        deadline, next_sample = time.monotonic() + seconds, time.monotonic() + 5
        next_resources = time.monotonic() + 30
        output, pending = bytearray(), b''
        needle = self.token.encode() if self.token else b''
        keep = max(0, len(needle) - 1)
        policy, eof = None, False
        try:
            while not eof or process.poll() is None:
                if time.monotonic() >= deadline or (CANCELLED and not cleaning):
                    policy = 'deadline_or_cancel'
                    self.drain('interrupted-' + phase)
                    break
                ready, _, _ = select.select([] if eof else [process.stdout], [], [], 0.1)
                if ready:
                    block = os.read(process.stdout.fileno(), 65536)
                    eof = not block
                    pending += block
                    if needle:
                        pending = pending.replace(needle, b'[REDACTED]')
                    count = max(0, len(pending) - keep)
                    output.extend(pending[:count])
                    pending = pending[count:]
                    require(len(output) + len(pending) <= 16 * 1024 * 1024, 'Bounded command output exceeded')
                if sample and time.monotonic() >= next_resources:
                    self.resources_now(phase)
                    next_resources = time.monotonic() + 30
                if sample and time.monotonic() >= next_sample and process.poll() is None:
                    self.observe_testcontainers()
                    next_sample = time.monotonic() + 5
            output.extend(pending.replace(needle, b'[REDACTED]') if needle else pending)
        except BaseException:
            self.drain('failed-command-' + phase)
            raise
        finally:
            process.stdout.close()
        code = process.poll() if policy is None else 124
        self.commands.append({'phase': phase, 'argv': argv, 'pid': process.pid, 'actual_exit': process.poll(),
                              'policy_exit': code, 'seconds_limit': seconds, 'terminal_reason': policy})
        if phase in {'gradle', 'source-before', 'source-after', 'java', 'docker', 'docker-api132',
                     'pull-postgres', 'sql-witness', 'stop-immediate', 'stop-final'}:
            (self.reports / (phase + '.log')).write_bytes(output)
        return code, bytes(output)

    def capture(self, argv, phase, seconds=30, **kwargs):
        code, output = self.command(argv, phase, seconds, **kwargs)
        require(code == 0, 'Command failed: ' + phase + ' (see scoped receipt/log; no automatic retry)')
        return output.decode().strip()

    def ids(self, *filters, cleaning=False):
        args = ['docker', 'ps', '-aq', '--no-trunc']
        for value in filters:
            args += ['--filter', value]
        raw = self.capture(args, 'container-list', cleaning=cleaning)
        values = raw.splitlines() if raw else []
        require(all(re.fullmatch('[0-9a-f]{64}', item) for item in values), 'Invalid container ID')
        return set(values)

    def inspect(self, cid, cleaning=False):
        template = '{{json .Id}}\t{{json .Config.Image}}\t{{json .Image}}\t{{json .Config.Labels}}\t{{json .State}}'
        raw = self.capture(['docker', 'inspect', '--format', template, cid], 'container-inspect', cleaning=cleaning)
        values = [json.loads(part) for part in raw.split('\t')]
        require(len(values) == 5 and values[0] == cid, 'Container identity changed')
        return dict(zip(('id', 'image', 'image_id', 'labels', 'state'), values))

    def observe_testcontainers(self):
        if self.pg_version is not None:
            return
        for cid in sorted(self.ids('label=org.testcontainers=true', 'ancestor=' + PG)):
            if cid not in self.tc:
                item = self.inspect(cid)
                require(item['labels'].get('org.testcontainers') == 'true', 'Unowned Testcontainers resource')
                self.tc[cid] = item
            item = self.tc[cid]
            if item['image'] == PG and self.pg_version is None:
                require(item['image_id'] == self.image_id, 'Testcontainers used an unexpected PostgreSQL image')
                code, value = self.command(['docker', 'exec', cid, 'psql', '--no-psqlrc', '--no-password',
                                            '--host=/var/run/postgresql', '--username=test', '--dbname=test',
                                            '--tuples-only', '--no-align', '--quiet',
                                            "--command=SELECT current_setting('server_version_num')"],
                                           'testcontainers-server-version', seconds=8)
                if code == 0:
                    require(value.strip() == b'170006', 'Testcontainers actual server is not PostgreSQL 17.6')
                    self.pg_version = '170006'

    def source_state(self, phase):
        request = self.request
        require(self.capture(['git', 'rev-parse', 'HEAD'], 'source-sha-' + phase, cleaning=True) == request['backend_sha'],
                'Source commit differs')
        require(self.capture(['git', 'rev-parse', 'HEAD^{tree}'], 'source-tree-' + phase, cleaning=True) == request['backend_tree'],
                'Source tree differs')
        require(not self.capture(['git', 'status', '--porcelain=v1', '--untracked-files=all'], 'source-' + phase, cleaning=True),
                'Source checkout is not clean')
        for relative, digest in (request['source_pins'] | request['compatibility_pins']).items():
            path = self.backend / relative
            require(not path.is_symlink() and path.is_file() and sha(path) == digest, 'Source/compatibility pin differs: ' + relative)

    def validate(self):
        request = self.request
        require(request['authorization'] == 'BACKEND5_ONE_PUBLIC_PG_BATCH_AUTHORIZED', 'Draft is unauthorized')
        for key in ('backend_sha', 'backend_tree'):
            require(re.fullmatch('[0-9a-f]{40}', request[key]) and request[key] != '0' * 40, 'Unbound source target')
        require(os.environ.get('GITHUB_REPOSITORY') == 'kira-manga/Kira-backend'
                and os.environ.get('GITHUB_REF') == 'refs/heads/verify/backend-5-media-pg-02'
                and os.environ.get('GITHUB_EVENT_NAME') == 'push'
                and os.environ.get('GITHUB_RUN_ATTEMPT') == '1', 'Wrong repository/branch/event or rerun')
        require(request['classes'] == CLASSES and all(type(n) is int for n in request['classes'].values()), 'Wrong IT selection')
        require(len(request['source_pins']) == 22 and request['carried_local']['accepted_by_primary'] is True, 'Unaccepted local evidence')
        for relative, digest in (request['source_pins'] | request['compatibility_pins']).items():
            path = Path(relative)
            require(not path.is_absolute() and '..' not in path.parts and re.fullmatch('[0-9a-f]{64}', digest), 'Unsafe source pin')
        for key, count in CLASSES.items():
            require(len(request['case_names'][key]) == count, 'Wrong expected invocation count')
        require(bool(self.token) and bool(self.user), 'Configured published-package read credential is unavailable; no fallback')
        require(sha(Path(__file__).with_name('backend5_owned_children.py')) == CHILD_SHA, 'Owned-child helper differs')
        from backend5_owned_children import OwnedChildren
        self.owner = OwnedChildren()
        require(shutil.rmtree.avoids_symlink_attacks, 'Owned-tree safe removal unavailable')
        (self.reports / 'request.json').write_text(json.dumps(request, indent=2) + '\n')
        require(self.capture(['git', '-C', str(self.gate), 'rev-parse', 'HEAD'], 'carrier-sha') == os.environ['GITHUB_SHA'],
                'Gate carrier checkout differs')
        self.source_state('before')
        self.resources_now('before-build')
        require(not any(path.exists() or path.is_symlink() for path in self.source_outputs), 'Preexisting source output/cache; retain it')
        require(not (self.backend / 'gradle/verification-metadata.xml').exists(), 'Verification metadata changed; rebind compatibility premise')
        java = self.capture(['java', '-XshowSettings:properties', '-version'], 'java')
        require(re.findall(r'^\s*java\.io\.tmpdir\s*=\s*(.*?)\s*$', java, re.M) == [str(self.run / 'tmp')], 'JVM temp escaped')
        require(re.findall(r'^\s*user\.home\s*=\s*(.*?)\s*$', java, re.M) == [str(self.run / 'home')], 'JVM home escaped')
        self.capture(['docker', 'version'], 'docker')
        self.capture(['docker', 'ps', '-aq', '--no-trunc'], 'docker-api132', extra={'DOCKER_API_VERSION': '1.32'})
        require(not self.ids(), 'Dedicated empty Docker runner required; preexisting containers are never touched')
        self.docker_ready = True
        self.capture(['docker', 'pull', PG], 'pull-postgres', seconds=180)
        image = self.capture(['docker', 'image', 'inspect', '--format', '{{json .Id}}\t{{json .RepoDigests}}', PG], 'postgres-image')
        image_id, digests = map(json.loads, image.split('\t'))
        require(re.fullmatch('sha256:[0-9a-f]{64}', image_id) and digests, 'Missing pulled-image identity')
        self.image_id = image_id
        (self.reports / 'postgres-image.json').write_text(json.dumps({'tag': PG, 'image_id': image_id, 'repo_digests': digests}) + '\n')
        tasks = ['test'] + [value for name in CLASSES for value in ('--tests', name)] + ['-x', 'jacocoTestReport']
        args = ['./gradlew', '--dependency-verification=strict', '--no-daemon', '--no-parallel', '--max-workers=1',
                '--no-build-cache', '--no-configuration-cache', '--console=plain', '--project-cache-dir', str(self.run / 'project-cache'),
                '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m', '-Dorg.gradle.vfs.watch=false',
                '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.project.persistent.dir=' + str(self.run / 'kotlin-cache'),
                '-Porg.gradle.java.installations.auto-download=false', '-PkiraUseMavenLocal=false',
                '-I', str(Path(__file__).with_suffix('.init.gradle')), *tasks]
        self.gradle_started = True
        self.gradle_exit, _ = self.command(args, 'gradle', seconds=20 * 60,
                                           extra={'KIRA_PACKAGES_USER': self.user, 'KIRA_PACKAGES_READ_TOKEN': self.token,
                                                  'BACKEND5_ENGINE_PAYLOADS': str(Path(__file__).with_name('backend5-engine-payloads.json'))}, sample=True)
        self.stop('immediate')
        require(self.drain('after-immediate-stop'), 'Workers not joined')
        self.cleanup_testcontainers()
        require(self.gradle_exit == 0 and self.pg_version == '170006' and not self.failed, 'IT execution/real-server/ownership gate failed')
        self.sql_witness()

    def stop(self, phase):
        if self.gradle_started:
            self.capture(['./gradlew', '--stop'], 'stop-' + phase, seconds=45, cleaning=True)
            if phase == 'immediate':
                self.immediate_stop = True

    def cleanup_testcontainers(self):
        if not self.docker_ready:
            return
        until = time.monotonic() + 20
        while True:
            remaining = self.ids('label=org.testcontainers=true', cleaning=True)
            if not remaining or time.monotonic() >= until:
                break
            time.sleep(1)
        if remaining:
            # Empty dedicated baseline + exact Testcontainers label; never remove unlabeled resources.
            require(self.drain('before-scoped-container-removal'), 'Process ownership unknown; no container deletion')
            self.failed = self.forced = True
            self.capture(['docker', 'rm', '-fv', *sorted(remaining)], 'forced-testcontainers-removal', cleaning=True)
        require(not self.ids('label=org.testcontainers=true', cleaning=True), 'Owned Testcontainers remain')

    def sql_witness(self):
        self.sql_requested = True
        self.sql_id = self.capture(['docker', 'create', '--name', self.sql_name,
                                   '--label', 'org.kira.backend5.sql=' + self.sql_name,
                                   '--network', 'none', '--memory', '384m', '--cpus', '1',
                                   '--tmpfs', '/var/lib/postgresql/data:rw,nosuid,nodev,noexec,size=268435456',
                                   '--env', 'POSTGRES_USER=backend5_sql', '--env', 'POSTGRES_DB=backend5_sql',
                                   '--env', 'POSTGRES_HOST_AUTH_METHOD=trust', PG,
                                   '-c', "listen_addresses=", '-c', 'max_connections=10', '-c', 'shared_buffers=16MB'],
                                  'create-sql-container', seconds=30)
        require(re.fullmatch('[0-9a-f]{64}', self.sql_id), 'SQL container ID missing')
        require(self.inspect(self.sql_id)['image_id'] == self.image_id, 'SQL image differs from pulled identity')
        self.capture(['docker', 'start', self.sql_id], 'start-sql-container')
        until = time.monotonic() + 30
        while True:
            code, _ = self.command(['docker', 'exec', self.sql_id, 'sh', '-c',
                                    'test "$(cat /proc/1/comm)" = postgres && exec pg_isready '
                                    '--host=/var/run/postgresql --username=backend5_sql --dbname=backend5_sql'],
                                   'sql-ready', seconds=5)
            if code == 0:
                break
            require(time.monotonic() < until, 'Owned PostgreSQL readiness timed out')
            time.sleep(1)
        docker = shutil.which('docker', path=self.env['PATH'])
        require(docker is not None and Path(docker).is_absolute(), 'Docker executable missing')
        shim = self.run / 'bin/psql'
        # Only transport changes: all psql argv, including the actual module's fixed SQL, are forwarded unchanged.
        shim.write_text('#!/bin/sh\nexec ' + shlex.quote(docker) + ' exec -i --env PGCONNECT_TIMEOUT=5 '
                        "--env 'PGOPTIONS=-c statement_timeout=10000' --env PGCLIENTENCODING=UTF8 "
                        '--env PGSSLMODE=disable "$BACKEND5_SQL_CONTAINER" psql "$@"\n')
        shim.chmod(0o700)
        self.capture(['python3', '-B', str(Path(__file__).with_name('backend5-sql-witness.py')),
                      str(self.backend), str(self.reports / 'sql-witness.json')], 'sql-witness', seconds=90,
                     extra={'PATH': str(shim.parent) + ':' + self.env['PATH'], 'BACKEND5_SQL_CONTAINER': self.sql_id,
                            'BACKEND5_BACKUP_SHA256': self.request['source_pins']['scripts/db/backup_bundle.py'],
                            'PGHOST': '/var/run/postgresql', 'PGPORT': '5432', 'PGUSER': 'backend5_sql',
                            'PGDATABASE': 'backend5_sql', 'PGSSLMODE': 'disable'})
        receipt = json.loads((self.reports / 'sql-witness.json').read_text())
        require(receipt['status'] == 'PASS' and len(receipt['cases']) == 5
                and all(case['status'] == 'PASS' for case in receipt['cases']), 'SQL readback incomplete')
        self.sql_ok = True

    def cleanup_sql(self):
        if not self.sql_requested or not self.docker_ready:
            return
        ids = self.ids('label=org.kira.backend5.sql=' + self.sql_name, cleaning=True)
        require(len(ids) <= 1 and (self.sql_id is None or ids <= {self.sql_id}), 'SQL container ownership ambiguous')
        for cid in ids:
            item = self.inspect(cid, cleaning=True)
            require(item['labels'].get('org.kira.backend5.sql') == self.sql_name, 'SQL ownership label differs')
            if item['state']['Running']:
                self.capture(['docker', 'stop', '--time', '15', cid], 'stop-sql-container', seconds=25, cleaning=True)
                item = self.inspect(cid, cleaning=True)
            if item['state']['Running'] or item['state']['ExitCode'] != 0:
                self.failed = self.forced = True
            require(not item['state']['Running'], 'SQL processes still run; retain outputs')
            (self.reports / 'sql-container-final.json').write_text(json.dumps(item, indent=2) + '\n')
            self.capture(['docker', 'rm', '-v', cid], 'remove-stopped-sql-container', cleaning=True)
        require(not self.ids('label=org.kira.backend5.sql=' + self.sql_name, cleaning=True), 'SQL container remains')

    def preserve(self):
        files = sorted((self.run / 'build/test-results/test').glob('*.xml'))
        for path in files:
            info = path.lstat()
            require(stat.S_ISREG(info.st_mode) and info.st_size <= 16 * 1024 * 1024, 'Unsafe/oversized JUnit report')
            raw = path.read_bytes()
            require(not self.token or self.token.encode() not in raw, 'Refusing an artifact containing a package credential')
            (self.reports / path.name).write_bytes(raw)
        self.preserved = True  # Missing/failed tests still preserve existing evidence, but cannot pass below.
        require({p.name for p in files} == {'TEST-' + name + '.xml' for name in CLASSES}, 'Wrong/missing exact IT XML files')
        observations = {}
        for name, count in CLASSES.items():
            suite = ET.parse(self.reports / ('TEST-' + name + '.xml')).getroot()
            cases = suite.findall('testcase')
            require(suite.tag == 'testsuite' and suite.get('name') == name and int(suite.get('tests', '-1')) == len(cases) == count
                    and all(int(suite.get(key, '-1')) == 0 for key in ('failures', 'errors', 'skipped')), 'IT totals/failure/skip differ')
            require(all(case.get('classname') == name for case in cases)
                    and Counter(case.get('name') for case in cases) == Counter(self.request['case_names'][name])
                    and not any(node.tag in ('failure', 'error', 'skipped') for node in suite.iter()), 'Wrong exact IT invocation identities/results')
            observations[name] = [case.attrib for case in cases]
        (self.reports / 'junit-readback.json').write_text(json.dumps(observations, indent=2) + '\n')
        binding = json.loads((self.reports / 'engine-payload-expectations.json').read_text())
        require(binding['status'] == 'PASS' and binding['sha256'] == binding['expected_sha256'] == PAYLOAD_SHA,
                'Missing/failed pinned Engine payload inventory binding')
        for config in ('compileClasspath', 'testCompileClasspath', 'testRuntimeClasspath', 'testWorkerClasspath'):
            receipt = json.loads((self.reports / ('dependencies-' + config + '.json')).read_text())
            require(receipt['schema_version'] == 2 and receipt['configuration'] == config and receipt['status'] == 'PASS'
                    and receipt['expected_payloads_sha256'] == PAYLOAD_SHA and receipt['observed_engine_count'] == 2
                    and all(value is True for value in receipt['predicates'].values()),
                    'Missing/failed exact classpath identity/predicate readback: ' + config)
        self.xml_ok = True

    def finish(self):
        def guarded(phase, function):
            try:
                function()
                return True
            except BaseException as failure:
                self.failed = True
                self.note(phase + ': FAIL ' + str(failure))
                return False
        if not self.immediate_stop:
            guarded('immediate-stop', lambda: self.stop('immediate'))
        workers = self.drain('before-preservation-and-container-cleanup')
        containers = False
        if workers:
            guarded('preserve-readback', self.preserve)
            tc = guarded('testcontainers-cleanup', self.cleanup_testcontainers)
            sql = guarded('sql-cleanup', self.cleanup_sql)
            containers = tc and sql and (not self.docker_ready or guarded('all-container-absence',
                          lambda: require(not self.ids(cleaning=True), 'Unowned/unknown container remains; retain outputs')))
            self.clean = guarded('source-after', lambda: self.source_state('after'))
        guarded('final-stop', lambda: self.stop('final'))
        workers = self.drain('after-final-stop') and workers
        if workers and containers and self.preserved:
            for path in self.owned + self.source_outputs:
                if path in self.source_outputs and not self.gradle_started:
                    continue
                if path.exists() or path.is_symlink():
                    def remove(path=path):
                        info = path.lstat()
                        require(stat.S_ISDIR(info.st_mode), 'Output root was replaced; retain it')
                        if path in self.directory_ids:
                            require((info.st_dev, info.st_ino) == self.directory_ids[path], 'Output ownership changed; retain it')
                        shutil.rmtree(path)
                    guarded('owned-output-removal', remove)
        else:
            self.note('RETAIN owned outputs: worker/container ownership or evidence preservation is not proved; runner disposal is not a join claim')
        if workers:
            self.clean = guarded('final-source-readback', lambda: self.source_state('after')) and self.clean
        workers = self.drain('final-owned-command-barrier') and workers
        absent = not any(path.exists() or path.is_symlink() for path in self.owned + self.source_outputs)
        passed = (not self.failed and not self.forced and not CANCELLED and self.gradle_exit == 0
                  and self.pg_version == '170006' and self.xml_ok and self.sql_ok and self.clean
                  and self.preserved and workers and containers and absent)
        result = {'status': 'PASS' if passed else 'FAIL', 'backend_sha': self.request['backend_sha'],
                  'backend_tree': self.request['backend_tree'], 'carrier_sha': os.environ.get('GITHUB_SHA'),
                  'classes': CLASSES, 'gradle_exit': self.gradle_exit, 'junit_exact': self.xml_ok,
                  'testcontainers_server_version_num': self.pg_version, 'testcontainers': self.tc,
                  'sql_witness': self.sql_ok, 'source_unchanged': self.clean, 'reports_preserved': self.preserved,
                  'forced_cleanup': self.forced, 'workers_absent': workers, 'containers_absent': containers,
                  'owned_outputs_absent': absent, 'cancelled': CANCELLED, 'drains': self.drains,
                  'commands': self.commands, 'carried_local': self.request['carried_local'],
                  'dependency_verification': 'strict requested; no comprehensive checksum manifest; locks/exact Engine coordinates retained; whole-JAR SHA or complete entry payload inventory enforced'}
        (self.reports / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        self.note('Backend5 hosted PG gate: ' + result['status'])
        return 0 if passed else 1


def main():
    os.umask(0o077)
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    batch = Batch()
    try:
        batch.validate()
    except BaseException as failure:
        batch.failed = True
        batch.note('VALIDATION FAIL: ' + str(failure))
    return batch.finish()


if __name__ == '__main__':
    raise SystemExit(main())
