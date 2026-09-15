#!/usr/bin/env python3
"""One admitted H1 image, genuine disposable V13.2 DB+EMPTY-media recovery; no release authority."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import stat
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'ci/backend2526-h1'
PG = 'postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94'
OWNER, STAGE = 'io.kira.backend2526.owner', 'io.kira.backend2526.stage'
BASE_PATH = '/usr/local/bin:/usr/bin:/bin'
MOUNT = '/var/lib/kira/tutorial-media'
VERSIONS = [str(n) for n in range(1, 14)] + ['13.1', '13.2']


def need(ok):
    if not ok:
        raise RuntimeError('H2 fixed guard refused')


def read(path, limit=16 * 1024, private=False):
    need(path.is_absolute() and path.resolve() == path)
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
        info = os.fstat(stream.fileno())
        need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size <= limit)
        need(not private or (info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o600))
        raw = stream.read(limit + 1)
    need(len(raw) <= limit)
    return raw


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def save(path, raw):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
        stream.write(raw)


def load_helper(relative):
    spec = importlib.util.spec_from_file_location('h2_' + Path(relative).stem, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def post_start_summary(obj, image_id, seed):
    """Fixed, bounded allowlist: never retain raw inspect, env values, labels or mounts."""
    obj = obj if type(obj) is dict else {}
    config = obj.get('Config')
    config = config if type(config) is dict else {}
    environment = config.get('Env')
    environment = environment if type(environment) is list else []
    env = dict(item.split('=', 1) for item in environment if type(item) is str and '=' in item)
    network = obj.get('NetworkSettings')
    ports = network.get('Ports') if type(network) is dict else None

    def bindings(key):
        if type(ports) is not dict or key not in ports:
            return {'kind': 'missing'}
        value = ports[key]
        if value is None:
            return {'kind': 'null'}
        if type(value) is not list:
            return {'kind': 'invalid'}
        entries = []
        for item in value[:4]:
            item = item if type(item) is dict else {}
            ip, port = item.get('HostIp'), item.get('HostPort')
            entries.append({'host_ip': ip if type(ip) is str and ip in ('127.0.0.1', '0.0.0.0', '::', '::1') else 'OTHER_OR_INVALID',
                            'host_port': port if type(port) is str and re.fullmatch('[0-9]{1,5}', port) else 'INVALID'})
        return {'kind': 'list', 'count': len(value), 'entries': entries}

    state = obj.get('State')
    state = state if type(state) is dict else {}
    status = state.get('Status')
    return {'image_matches': obj.get('Image') == image_id, 'user_matches': config.get('User') == '10001:10001',
            'profile_prod': env.get('SPRING_PROFILES_ACTIVE') == 'prod',
            'seed_flag_matches': env.get('KIRA_ADMIN_SEED_ENABLED') == str(seed).lower(),
            'admin_credentials_present': bool({'KIRA_ADMIN_EMAIL', 'KIRA_ADMIN_PASSWORD'}.intersection(env)),
            'running': state.get('Running') if type(state.get('Running')) is bool else None,
            'state': status if type(status) is str and status in ('created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead') else 'OTHER_OR_INVALID',
            'ports_object': type(ports) is dict, 'port_8080': bindings('8080/tcp'), 'port_9090': bindings('9090/tcp'),
            'unexpected_published_ports': type(ports) is dict and any(value for key, value in ports.items() if key not in ('8080/tcp', '9090/tcp'))}


def main():
    began = time.monotonic()
    deadline = began + 480
    os.umask(0o077)
    os.environ['PATH'] = BASE_PATH
    need(len(sys.argv) == 2 and os.getuid() > 0 and sys.version_info >= (3, 11) and Path.cwd().resolve() == ROOT)
    request = json.loads(read(HERE / 'h2-request.json'))
    need(set(request) == set('schema admission purpose source_sha source_tree source_pins_sha256 outer_seconds work_seconds cleanup_seconds'.split()))
    need(request['schema'] == 'backend2526-h2-request-v1' and request['admission'] == 'ADMITTED_TEST_ONLY'
         and request['purpose'] == 'test-only-db-empty-media-recovery'
         and (request['outer_seconds'], request['work_seconds'], request['cleanup_seconds']) == (600, 480, 120))
    context_path = Path(sys.argv[1])
    ctx = json.loads(read(context_path, private=True))
    need(set(ctx) == set('schema h1_pass candidate_image_id source_sha source_tree source_pins_sha256 run_id run_attempt scratch h2_root resource_prefix result'.split()))
    need(ctx['schema'] == 'backend2526-h2-context-v1' and type(ctx['run_id']) is int and 0 < ctx['run_id'] <= 2**53 - 1
         and type(ctx['run_attempt']) is int and ctx['run_attempt'] == 1 and ctx['h1_pass'] is True)
    for key, length in (('source_sha', 40), ('source_tree', 40), ('source_pins_sha256', 64)):
        need(ctx[key] == request[key] and re.fullmatch('[0-9a-f]{' + str(length) + '}', ctx[key]))
    image_id = ctx['candidate_image_id']
    need(re.fullmatch('sha256:[0-9a-f]{64}', image_id))
    scratch, root, result = (Path(ctx[key]) for key in ('scratch', 'h2_root', 'result'))
    info = scratch.lstat()
    need(scratch.is_absolute() and scratch.resolve() == scratch and stat.S_ISDIR(info.st_mode)
         and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700
         and re.fullmatch('/[A-Za-z0-9_./-]+', str(scratch))
         and context_path == scratch / 'h2-context.json' and root == scratch / 'h2'
         and result == scratch / 'h2-result.json' and not os.path.lexists(root) and not os.path.lexists(result))
    prefix = ctx['resource_prefix']
    need(prefix == f"kb2526-{ctx['run_id']}-1-h2")
    raw_pins = read(HERE / 'source-pins.json', 256 * 1024)
    need(sha(raw_pins) == ctx['source_pins_sha256'])
    pins = json.loads(raw_pins)
    need(pins['schema'] == 'backend2526-source-pins-v1' and len(pins['files']) == 476)
    for row in pins['files']:
        path = ROOT / row['path']
        need(path.is_relative_to(ROOT) and '..' not in Path(row['path']).parts)
        raw = read(path, row['bytes'])
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256']
             and row['git_mode'] == ('100755' if path.stat().st_mode & 0o111 else '100644'))
    # Only now import the unchanged, pinned command controller and backup's pure history checker.
    image = load_helper('scripts/ci/image_release.py')
    backup = load_helper('scripts/db/backup_bundle.py')
    docker_bin = shutil.which('docker')
    need(docker_bin and Path(docker_bin).is_absolute())
    report = {key: ctx[key] for key in ('candidate_image_id', 'source_sha', 'source_tree', 'source_pins_sha256', 'run_id', 'run_attempt')}
    report.update(schema='backend2526-h2-result-v1', status='NOT_RUN', cleanup='NOT_RUN', scope=request['purpose'],
                  owned={'containers': [], 'networks': [], 'volumes': []}, phases=[], commands={})
    stage, baseline, ambiguous = 'source-and-image', False, False
    owner, labels, pg_id, root_identity = None, [], None, None
    attempted = {kind: set() for kind in ('container', 'network', 'volume')}
    media = {}

    def record(label, code, started):
        entry = report['commands'].setdefault(label, {'calls': 0, 'elapsed_ms': 0, 'exit_counts': {}})
        entry['calls'] += 1
        entry['elapsed_ms'] += round(1000 * (time.monotonic() - started))
        entry['exit_counts'][str(code)] = entry['exit_counts'].get(str(code), 0) + 1

    def run(label, argv, seconds=30, status=False):
        remaining = deadline - time.monotonic() - 4  # Existing command helper owns its final four-second reap.
        need(remaining > 0)
        started = time.monotonic()
        try:
            value = image.command([str(arg) for arg in argv], seconds=min(seconds, remaining),
                                  maximum=1024 * 1024, return_status=status)
        except BaseException:
            record(label, 'NOT_VERIFIED', started)
            raise
        record(label, value if status else 0, started)
        return value

    def dock(label, args, **kwargs):
        return run(label, [docker_bin, *args], **kwargs)

    def inspect(kind, identity):
        value = image.parse_json(dock('inspect-' + kind, [kind, 'inspect', identity]), 1024 * 1024)
        need(type(value) is list and len(value) == 1)
        return value[0]

    def inventory(kind):
        listing = ['container', 'ls', '-aq', '--no-trunc'] if kind == 'container' else [kind, 'ls', '-q']
        if kind == 'network':
            listing.append('--no-trunc')
        by_name = dock('inventory-' + kind, [*listing, '--filter', 'name=' + prefix]).decode().split()
        by_label = dock('inventory-' + kind, [*listing, '--filter', f'label={OWNER}={owner}', '--filter', f'label={STAGE}=h2']).decode().split()
        return sorted(set(by_name + by_label))

    def owned(kind, identity):
        return check_owned(kind, identity, inspect(kind, identity))

    def check_owned(kind, identity, obj):
        actual = obj['Name'] if kind == 'volume' else obj['Id']
        name = obj['Name'].removeprefix('/')
        marks = obj['Config']['Labels'] if kind == 'container' else obj['Labels']
        need(actual == identity and name in attempted[kind] and marks.get(OWNER) == owner and marks.get(STAGE) == 'h2')
        if kind == 'container':
            need(obj['Image'] in (image_id, pg_id))
        key = {'container': 'containers', 'network': 'networks', 'volume': 'volumes'}[kind]
        if identity not in report['owned'][key]:
            report['owned'][key].append(identity)
        return obj

    def create(kind, suffix, args=()):
        nonlocal ambiguous
        name = prefix + '-' + suffix
        need(name not in attempted[kind])
        attempted[kind].add(name)
        command = [kind, 'create', *labels]
        if kind == 'container':
            command += ['--name', name, '--log-driver', 'none', '--pull', 'never', *args]
        else:
            command += [*args, name]
        try:
            identity = dock('create-' + suffix, command).decode().strip()
            need(identity == name if kind == 'volume' else re.fullmatch('[0-9a-f]{64}', identity))
            owned(kind, identity)  # Register the full ID before any start, including one-shots.
            return identity
        except BaseException:
            ambiguous = True  # A killed CLI cannot disprove a late daemon-side create.
            raise

    def remove_container(identity, graceful=False):
        obj = owned('container', identity)
        if graceful:
            dock('drain-stop', ['container', 'stop', '--time', '30', identity], seconds=35)
            obj = owned('container', identity)
            need(not obj['State']['Running'] and obj['State']['Status'] == 'exited'
                 and obj['State']['ExitCode'] == 0 and not obj['State']['OOMKilled'])
        dock('remove-container', ['container', 'rm', '--force', identity])
        need(not dock('container-absence', ['container', 'ls', '-aq', '--no-trunc', '--filter', 'id=' + identity]).strip())

    def once(suffix, args, seconds=60):
        identity = create('container', suffix, args)
        output = dock('run-' + suffix, ['container', 'start', '--attach', identity], seconds=seconds)
        state = owned('container', identity)['State']
        need(state['Status'] == 'exited' and not state['Running'] and state['ExitCode'] == 0)
        remove_container(identity)
        return output

    def handoff(path, suffix, user, mode):
        current = path.lstat()
        need(stat.S_ISDIR(current.st_mode) and path.resolve() == path)
        if path in media:
            need((current.st_dev, current.st_ino) == media[path])
        media[path] = (current.st_dev, current.st_ino)
        once(suffix, ['--network', 'none', '--read-only', '--user', '0:0', '--entrypoint', 'sh',
                     '--volume', f'{path}:{MOUNT}', image_id, '-ec',
                     'test -d "$1"; chown "$2" "$1"; chmod "$3" "$1"', 'h2-media', MOUNT, user, mode], 20)
        after = path.lstat()
        need((after.st_dev, after.st_ino) == media[path]
             and f'{after.st_uid}:{after.st_gid}' == user and stat.S_IMODE(after.st_mode) == int(mode, 8))

    try:
        carrier = run('carrier-parent', ['git', '--no-optional-locks', 'rev-list', '--parents', '-n', '1', 'HEAD']).decode().split()
        need(len(carrier) == 2 and re.fullmatch('[0-9a-f]{40}', carrier[0]) and carrier[1] == ctx['source_sha'])
        need(run('source-tree', ['git', '--no-optional-locks', 'rev-parse', ctx['source_sha'] + '^{tree}']).decode().strip() == ctx['source_tree'])
        need(not run('clean-source', ['git', '--no-optional-locks', 'status', '--porcelain', '--untracked-files=all']).strip())
        report['carrier_sha'] = carrier[0]
        owner = f"{ctx['run_id']}.1.{carrier[0][:12]}"
        labels = ['--label', f'{OWNER}={owner}', '--label', f'{STAGE}=h2']
        image.local_contract()
        need(image.inspect_image('kira-backend:' + carrier[0], image_id) == image_id)
        config = inspect('image', image_id)['Config']
        need(config['User'] == '10001:10001' and not config.get('Volumes'))
        pg = inspect('image', PG)
        pg_id = pg['Id']
        need(re.fullmatch('sha256:[0-9a-f]{64}', pg_id)
             and any(repository + '@' + PG.split('@')[1] in pg['RepoDigests'] for repository in ('postgres', 'docker.io/library/postgres'))
             and pg['Os'] == 'linux' and pg['Architecture'] == 'amd64'
             and set(pg['Config']['Volumes']) == {'/var/lib/postgresql/data'})
        report['postgres_image_id'] = pg_id
        need(all(not inventory(kind) for kind in ('container', 'network', 'volume')))
        baseline = True
        root.mkdir(mode=0o700)
        root_identity = (root.stat().st_dev, root.stat().st_ino)
        report['status'] = 'FAIL'
        for name in ('tls', 'state', 'capture', 'source-media'):
            (root / name).mkdir(mode=0o700)
        tls, capture = root / 'tls', root / 'capture'
        source_media, target_media = root / 'source-media', root / 'restored-media'
        os.environ['TMPDIR'] = str(root)
        stage = 'fixture-setup'
        names = {role: prefix + '-db-' + role for role in ('source', 'restore')}
        run('tls-ca', ['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-keyout', tls / 'ca.key', '-out', tls / 'ca.crt', '-subj', '/CN=Kira H2 fixture CA'])
        run('tls-csr', ['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-keyout', tls / 'server.key', '-out', tls / 'server.csr', '-subj', '/CN=' + names['source']])
        save(tls / 'server.ext', ('subjectAltName=DNS:' + names['source'] + ',DNS:' + names['restore'] + '\nextendedKeyUsage=serverAuth\n').encode())
        run('tls-cert', ['openssl', 'x509', '-req', '-days', '1', '-sha256', '-in', tls / 'server.csr', '-CA', tls / 'ca.crt', '-CAkey', tls / 'ca.key', '-CAcreateserial', '-extfile', tls / 'server.ext', '-out', tls / 'server.crt'])
        (tls / 'ca.crt').chmod(0o644)
        run('fixture-signing', [ROOT / image.SIGNING_KEY_HELPER, 'smoke', root / 'signing'])
        admin, jwt = secrets.token_hex(24), secrets.token_urlsafe(48)
        save(root / 'state/admin-password', admin.encode())
        # Like H1, host-loopback publication needs an ordinary dedicated bridge.
        network = create('network', 'network', ['--driver', 'bridge'])
        network_state = owned('network', network)
        need(network_state['Driver'] == 'bridge' and network_state['Internal'] is False)
        client_user = f'{os.getuid()}:{os.getgid()}'

        def envfile(name, values):
            path = root / (name + '.env')
            save(path, ''.join(key + '=' + value + '\n' for key, value in values.items()).encode())
            return path

        def database(role):
            password, dbname = secrets.token_hex(24), 'kira' if role == 'source' else 'kira_restore_h2'
            volume = create('volume', 'pgdata-' + role)
            pgpass = root / (role + '.pgpass')
            save(pgpass, f'{names[role]}:5432:{dbname}:kira:{password}\n'.encode())
            args = ['--network', network, '--env-file', envfile('postgres-' + role, {'POSTGRES_DB': dbname, 'POSTGRES_USER': 'kira', 'POSTGRES_PASSWORD': password}),
                    '--volume', f'{volume}:/var/lib/postgresql/data', '--volume', f'{tls}:/source-certs:ro', '--volume', f'{pgpass}:/run/kira-client/pgpass:ro']
            if role == 'source':
                args += ['--volume', f'{capture}:{capture}']
            identity = create('container', 'db-' + role, [*args, PG, 'sh', '-ec',
                'mkdir -p /run/kira-certs; cp /source-certs/ca.crt /source-certs/server.crt /source-certs/server.key /run/kira-certs/; chown -R postgres:postgres /run/kira-certs; chmod 600 /run/kira-certs/server.key; exec docker-entrypoint.sh postgres -c ssl=on -c ssl_ca_file=/run/kira-certs/ca.crt -c ssl_cert_file=/run/kira-certs/server.crt -c ssl_key_file=/run/kira-certs/server.key'])
            mounts = owned('container', identity)['Mounts']
            need([m['Name'] for m in mounts if m['Type'] == 'volume'] == [volume])
            dock('start-db-' + role, ['container', 'start', identity])
            need(all(not value for value in owned('container', identity)['NetworkSettings']['Ports'].values()))
            for _ in range(45):
                if dock('ready-db-' + role, ['exec', identity, 'sh', '-ec', 'test "$(cat /proc/1/comm)" = postgres && exec psql --no-psqlrc --set=ON_ERROR_STOP=1 --username=kira --dbname="$1" --tuples-only --command="SELECT 1"', 'h2-ready', dbname], seconds=5, status=True) == 0:
                    break
                need(owned('container', identity)['State']['Running'])
                time.sleep(1)
            else:
                need(False)
            bridges = root / ('clients-' + role)
            bridges.mkdir(mode=0o700)
            for client in ('pg_dump', 'pg_restore', 'psql'):
                path = bridges / client
                save(path, ('#!/bin/sh\nset -eu\nexec ' + shlex.quote(docker_bin) + ' exec -i --user ' + client_user
                            + ' --env PGPASSFILE --env PGSSLROOTCERT --env PGSSLMODE --env PGCLIENTENCODING '
                            + identity + ' ' + client + ' "$@"\n').encode())
                path.chmod(0o700)
            environment = {'PATH': str(bridges) + ':' + BASE_PATH, 'PGHOST': names[role], 'PGPORT': '5432',
                           'PGDATABASE': dbname, 'PGUSER': 'kira', 'PGPASSFILE': '/run/kira-client/pgpass',
                           'PGSSLROOTCERT': '/run/kira-certs/ca.crt', 'PGSSLMODE': 'verify-full', 'PGCLIENTENCODING': 'UTF8', 'KIRA_ENVIRONMENT': 'test'}
            jdbc = f'jdbc:postgresql://{names[role]}:5432/{dbname}?sslmode=verify-full&sslrootcert=/run/kira-smoke/ca.crt'
            return identity, environment, jdbc, password

        def pgcall(label, environment, args, seconds=60):
            return run(label, ['/usr/bin/env', *[key + '=' + value for key, value in environment.items()], *args], seconds)

        def query(environment, sql):
            return pgcall('read-only-sql', environment, ['psql', '--no-psqlrc', '--set=ON_ERROR_STOP=1', '--tuples-only', '--no-align', '--quiet', *backup.pg_arguments(environment), '--command=' + sql]).strip()

        def history(environment):
            state = image.parse_json(query(environment, backup.HISTORY_SQL), 1024 * 1024)
            checked = backup.checked_database_state(state, environment, '13.2')
            rows = state['history']
            need([row['version'] for row in rows] == VERSIONS and [row['rank'] for row in rows] == list(range(1, 16))
                 and [row['script'] for row in rows] == [Path(path).name for path in image.STATE_MIGRATIONS]
                 and all(row['type'] == 'SQL' and type(row['checksum']) is int for row in rows))
            return rows, checked['history_sha256']

        def migrate(role, jdbc, password, applied):
            values = {'KIRA_MIGRATION_DB_URL': jdbc, 'KIRA_MIGRATION_DB_USERNAME': 'kira', 'KIRA_MIGRATION_DB_PASSWORD': password}
            raw = once('migrate-' + role, ['--network', network, '--entrypoint', 'java', '--volume', f'{tls}/ca.crt:/run/kira-smoke/ca.crt:ro',
                                         '--env-file', envfile('migration-' + role, values), image_id,
                                         '-Dloader.main=me.manga.kira.backend.database.DatabaseMigrationMain', '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'], 90)
            need(re.findall(rb'^Database migration complete; applied=([0-9]+) target=13\.2$', raw, re.M) == [str(applied).encode()])

        def application(role, jdbc, password, media_path, seed):
            values = {'SPRING_PROFILES_ACTIVE': 'prod', 'SPRING_DATASOURCE_URL': jdbc, 'SPRING_DATASOURCE_USERNAME': 'kira',
                      'SPRING_DATASOURCE_PASSWORD': password, 'KIRA_JWT_SECRET': jwt, 'KIRA_SECURITY_EXTERNAL_BASE_URL': 'https://api.smoke.invalid',
                      'KIRA_SECURITY_THROTTLE_BACKEND': 'memory', 'KIRA_SECURITY_THROTTLE_INSTANCE_COUNT': '1', 'KIRA_ADMIN_SEED_ENABLED': str(seed).lower(),
                      'KIRA_COMPLETION_ENABLED': 'false', 'KIRA_SIGNING_ACTIVE_KEY_ID': 'smoke', 'KIRA_SIGNING_PRIVATE_KEY': read(root / 'signing/smoke.private.b64').decode(),
                      'KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID': 'smoke', 'KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY': read(root / 'signing/smoke.public.b64').decode(),
                      'KIRA_TUTORIAL_MEDIA_DIRECTORY': MOUNT}
            if seed:
                values.update(KIRA_ADMIN_EMAIL='release-smoke@kira.invalid', KIRA_ADMIN_PASSWORD=admin)
            identity = create('container', 'app-' + role, ['--network', network, '--read-only', '--tmpfs', '/tmp:size=128m,mode=1777', '--publish', '127.0.0.1::8080',
                              '--volume', f'{tls}/ca.crt:/run/kira-smoke/ca.crt:ro', '--volume', f'{media_path}:{MOUNT}', '--env-file', envfile('application-' + role, values), image_id])
            dock('start-app-' + role, ['container', 'start', identity])
            obj = inspect('container', identity)
            report.setdefault('application_post_start', {})[role] = post_start_summary(obj, image_id, seed)
            check_owned('container', identity, obj)
            need(obj['Image'] == image_id and obj['Config']['User'] == '10001:10001')
            actual_env = dict(item.split('=', 1) for item in obj['Config']['Env'])
            need(actual_env['SPRING_PROFILES_ACTIVE'] == 'prod' and actual_env['KIRA_ADMIN_SEED_ENABLED'] == str(seed).lower())
            need(seed or not {'KIRA_ADMIN_EMAIL', 'KIRA_ADMIN_PASSWORD'}.intersection(actual_env))
            binding = obj['NetworkSettings']['Ports']['8080/tcp']
            need(len(binding) == 1 and binding[0]['HostIp'] == '127.0.0.1' and 0 < int(binding[0]['HostPort']) <= 65535)
            need(all(not value for key, value in obj['NetworkSettings']['Ports'].items() if key != '8080/tcp'))
            for _ in range(60):
                if dock('ready-app-' + role, ['exec', identity, 'wget', '-q', '-O', '/dev/null', 'http://127.0.0.1:9090/actuator/health/readiness'], seconds=5, status=True) == 0:
                    break
                need(owned('container', identity)['State']['Running'])
                time.sleep(2)
            else:
                need(False)
            for endpoint in ('health/liveness', 'prometheus'):
                dock('health-app-' + role, ['exec', identity, 'wget', '-q', '-O', '/dev/null', 'http://127.0.0.1:9090/actuator/' + endpoint], seconds=5)
            return identity, binding[0]['HostPort']

        source, source_env, jdbc, password = database('source')
        need(query(source_env, 'SELECT ssl FROM pg_catalog.pg_stat_ssl WHERE pid=pg_backend_pid()') == b't')
        stage = 'fresh-migration-and-initialize'
        migrate('source', jdbc, password, 15)
        original_history, history_hash = history(source_env)
        handoff(source_media, 'media-source-app', '10001:10001', '750')
        app, port = application('source', jdbc, password, source_media, True)
        run('initialize-once', [ROOT / image.STATE_SMOKE, 'initialize', port, root], 120)
        checkpoint = sha(read(root / 'state/checkpoint.json', 5 * 1024 * 1024, private=True))
        report['phases'].append('fresh-v13.2-initialize-pass')
        stage = 'writer-fence-and-backup'
        remove_container(app, graceful=True)
        need(set(owned('network', network)['Containers']) == {source})
        need(query(source_env, "SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid()") == b'0')
        need(query(source_env, 'SELECT (SELECT count(*) FROM public.tutorials)+(SELECT count(*) FROM public.tutorial_media)') == b'0')
        handoff(source_media, 'media-source-host', client_user, '700')
        need(not list(source_media.iterdir()))
        nonce = secrets.token_hex(16)
        probe = capture / ('.mount-probe-' + nonce)
        dock('same-path-client-probe', ['exec', '--user', client_user, source, 'sh', '-ec', 'set -C; umask 077; printf %s "$1" > "$2"', 'h2-probe', nonce, probe])
        need(read(probe, private=True) == nonce.encode() and probe.stat().st_gid == os.getgid())
        probe.unlink()
        source_env['KIRA_BACKUP_WRITERS_FROZEN_AND_DRAINED'] = 'yes'  # Only after the real fence and completed requests.
        produced = pgcall('real-backup', source_env, [ROOT / 'scripts/db/backup.sh', capture / 'catalog.dump', source_media], 90)
        pin = re.fullmatch(rb'backup bundle created; inventory SHA-256: ([0-9a-f]{64})\n', produced)
        need(pin and sha(read(capture / 'catalog.bundle.json')) == pin[1].decode())
        with (capture / 'catalog.dump').open('rb') as stream:
            need(stream.read(5) == b'PGDMP')
        need(history(source_env)[0] == original_history)
        remove_container(source, graceful=True)
        report['phases'].append('only-writer-stopped-real-backup-pass')
        stage = 'fresh-custody-restore-and-pair'
        target, target_env, jdbc, password = database('restore')
        need(target != source and set(owned('network', network)['Containers']) == {target})
        need(query(target_env, 'SELECT ssl FROM pg_catalog.pg_stat_ssl WHERE pid=pg_backend_pid()') == b't')
        need(query(target_env, "SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid()") == b'0')
        need(query(target_env, "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname='public'") == b'0')
        attempt = root / 'restore-attempt'
        need(not os.path.lexists(attempt) and not os.path.lexists(target_media))
        target_env.update(KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST='yes', KIRA_RESTORE_CUSTODY_CONFIRMED='yes')
        pgcall('real-restore-db', target_env, [ROOT / 'scripts/db/verify-restore.sh', capture / 'catalog.bundle.json', pin[1].decode(), capture / 'catalog.dump', capture / 'catalog.media.tar.gz', '13.2', attempt, target_media], 90)
        pgcall('real-restore-media', target_env, [ROOT / 'scripts/db/restore-media.sh', attempt], 60)
        pair = image.parse_json(pgcall('verify-restored-pair', target_env, [sys.executable, '-I', '-B', ROOT / 'scripts/db/backup_bundle.py', 'verify-restored-pair', '--attempt', attempt], 60), 1024 * 1024)
        need(pair['schema'] == 'kira.restored-pair-media-verification.v1' and pair['verified'] is True and pair['issues'] == []
             and all(pair[key] == 0 for key in ('rows', 'draft_verified', 'published_verified', 'quarantine_files', 'entries_checked')))
        need(history(target_env)[0] == original_history and sha(read(root / 'state/checkpoint.json', 5 * 1024 * 1024, private=True)) == checkpoint)
        report.update(history_sha256=history_hash, versions=VERSIONS, fixture_inventory_sha256=pin[1].decode(),
                      fixture_dump_bytes=(capture / 'catalog.dump').stat().st_size,
                      fixture_media_bytes=(capture / 'catalog.media.tar.gz').stat().st_size,
                      restored_pair_verified=True, tutorial_rows=0, media_entries=0)
        report['phases'].append('as-restored-v13.2-empty-pair-pass')
        stage = 'restored-migration-and-seed-disabled-recover'
        migrate('restore', jdbc, password, 0)
        need(history(target_env)[0] == original_history)
        report['migration_applied_counts'] = [15, 0]
        handoff(target_media, 'media-restored-app', '10001:10001', '750')
        app, port = application('restored', jdbc, password, target_media, False)
        run('recover-once', [ROOT / image.STATE_SMOKE, 'recover', port, root], 120)
        need(sha(read(root / 'state/checkpoint.json', 5 * 1024 * 1024, private=True)) == checkpoint)
        remove_container(app, graceful=True)
        report['phases'].append('same-image-seed-disabled-forward-publication-pass')
        report['status'] = 'PASS'
    except BaseException:
        report['failure_stage'] = stage  # Never exception text, candidate logs, env, requests or checkpoint bytes.
    finally:
        deadline = min(began + 600, time.monotonic() + 120)
        if baseline:
            report['cleanup'] = 'FAIL'
            try:
                for identity in inventory('container'):
                    remove_container(identity)
                for name in ('source-media', 'restored-media'):
                    path = root / name
                    if os.path.lexists(path):
                        handoff(path, 'media-final-' + name, f'{os.getuid()}:{os.getgid()}', '700')
                        need(not list(path.iterdir()))
                for kind in ('volume', 'network'):
                    for identity in inventory(kind):
                        owned(kind, identity)
                        dock('remove-' + kind, [kind, 'rm', identity])
                need(all(not inventory(kind) for kind in ('container', 'network', 'volume')) and not ambiguous)
                current = root.lstat()
                need(shutil.rmtree.avoids_symlink_attacks and root.resolve() == root
                     and (current.st_dev, current.st_ino) == root_identity and current.st_uid == os.getuid()
                     and stat.S_IMODE(current.st_mode) == 0o700)
                shutil.rmtree(root)
                need(not os.path.lexists(root))
                report['cleanup'] = 'PASS'
            except BaseException:
                report['cleanup'] = 'FAIL'  # Preserve private files on uncertain daemon/leaf custody; H1 independently checks.
        if report['cleanup'] != 'PASS':
            report['status'] = 'FAIL' if baseline else 'NOT_RUN'
        report['elapsed_ms'] = round(1000 * (time.monotonic() - began))
        raw = image.canonical(report)
        need(len(raw) <= 16 * 1024)
        save(result, raw)
    print('H2 DB+empty-media recovery ' + report['status'] + '; cleanup ' + report['cleanup'])
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception:
        print('H2 refused or incomplete; no verified recovery result', file=sys.stderr)
        raise SystemExit(1)
