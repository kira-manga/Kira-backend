#!/usr/bin/env python3
"""Inert until admitted. Two fresh public source builds; no private bytes or distribution."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import time
import traceback
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
CONTROL = Path(__file__).resolve().parent
BINDING = json.loads((CONTROL / 'runtime-binding.json').read_text())
GENERATED = {'.git', '.gradle', '.kotlin', 'build'}


def require(ok, reason):
    if not ok:
        raise RuntimeError(reason)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def files(root, source=False):
    result = {}
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if source and any(part in GENERATED for part in relative.parts):
            continue
        require(not path.is_symlink(), 'No symlink input/output')
        if path.is_dir():
            continue
        require(path.is_file(), 'Only regular input/output files')
        row = {'sha256': sha(path), 'bytes': path.stat().st_size}
        if source:
            row['mode'] = '100755' if path.stat().st_mode & 0o111 else '100644'
        result[relative.as_posix()] = row
    return result


def emit(path, value):
    temporary = path.with_name(path.name + '.writing')
    with temporary.open('x') as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write('\n')
    temporary.replace(path)


def identity(path):
    row = path.stat()
    return {'device': row.st_dev, 'inode': row.st_ino, 'uid': row.st_uid}


def problem(stage, error):
    frames = traceback.extract_tb(error.__traceback__)
    return {'stage': stage, 'type': type(error).__name__,
            'reason': str(error)[:240] if isinstance(error, RuntimeError) else None,
            'controlLine': frames[-1].lineno if frames else None}


def sample_resources(directory, resources, phase, admission=False):
    limits = BINDING['limits']
    memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    require('MemAvailable' in memory, 'Hosted MemAvailable observation unavailable')
    available = int(memory['MemAvailable'].split()[0]) * 1024
    disk = shutil.disk_usage(directory).free
    logs = {}
    for name in ('prepare.log', 'gradle.log', 'stop.log', 'publication-result.json', 'javap-abi.txt'):
        path = directory / name
        require(not path.is_symlink(), 'No stage diagnostic symlink')
        if path.exists():
            require(path.is_file(), 'Stage diagnostic must be regular')
            logs[name] = path.stat().st_size
    row = {'elapsed_seconds': round(time.monotonic() - resources['started_monotonic'], 3), 'phase': phase,
           'mem_available_bytes': available, 'disk_free_bytes': disk, 'stage_log_bytes': sum(logs.values()), 'logs': logs}
    prefix = 'admit_' if admission else 'floor_'
    row['violations'] = [name for name, observed in (('mem_available_bytes', available), ('disk_free_bytes', disk))
                         if observed < limits[prefix + name]]
    if row['stage_log_bytes'] > limits['active_stage_log_bytes']:
        row['violations'].append('active_stage_log_bytes')
    resources['samples'].append(row)
    require(not row['violations'], 'Hosted runtime resource/log threshold crossed: ' + ','.join(row['violations']))


def execute(argv, cwd, env, log, scope, seconds, commands, resources=None):
    record = {'argv': argv, 'seconds': seconds, 'exit': None}
    commands.append(record)
    if resources is not None:
        sample_resources(Path(env['KIRA_RUNTIME_STAGE_ROOT']), resources, 'before-command')
    with log.open('ab') as output:
        child = subprocess.Popen(argv, cwd=cwd, env=env, stdout=output,
                                 stderr=subprocess.STDOUT, start_new_session=True)
        scope.track()
        deadline = time.monotonic() + seconds
        while child.poll() is None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired('owned runtime reconstruction command', seconds)
            try:
                child.wait(timeout=min(BINDING['limits']['resource_sample_seconds'], remaining))
            except subprocess.TimeoutExpired:
                scope.track()
            if resources is not None:
                sample_resources(Path(env['KIRA_RUNTIME_STAGE_ROOT']), resources, 'command-wait')
    record['exit'] = child.returncode
    if resources is not None:
        sample_resources(Path(env['KIRA_RUNTIME_STAGE_ROOT']), resources, 'after-command')
    require(child.returncode == 0, 'Runtime command failed; see closed bounded stage log')


def extract_public_archive(archive, destination, pin):
    require(archive.stat().st_size == pin['archive_bytes'] and sha(archive) == pin['archive_sha256'],
            'Public pgjdbc archive differs from frozen upstream')
    seen, total = set(), 0
    with tarfile.open(archive, 'r:gz') as bundle:
        for member in bundle:
            parts = PurePosixPath(member.name).parts
            require(parts and parts[0] == 'pgjdbc-' + pin['commit'] and
                    all(part not in ('..', '.', '') for part in parts), 'Invalid public archive path')
            require(member.isdir() or member.isfile(), 'No archive links/devices')
            relative = Path(*parts[1:])
            target = destination / relative
            if member.isdir():
                target.mkdir(mode=0o700, parents=True, exist_ok=True)
                continue
            require(relative.parts and relative.as_posix() not in seen, 'Duplicate archive file')
            seen.add(relative.as_posix())
            total += member.size
            require(len(seen) <= pin['upstream_files'] and total <= pin['archive_uncompressed_limit'],
                    'Public archive extraction bound exceeded')
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            with bundle.extractfile(member) as src, target.open('xb') as dst:
                shutil.copyfileobj(src, dst, 65536)
            target.chmod(0o755 if member.mode & 0o111 else 0o644)
    require(len(seen) == pin['upstream_files'], 'Incomplete public pgjdbc archive')


def prepare_source(stage, directory, env, scope, commands, recipe, resources):
    pin = BINDING[stage]
    source = directory / 'source'
    source.mkdir(mode=0o700)
    log = directory / 'prepare.log'
    deadline = time.monotonic() + BINDING['limits']['fetch_seconds']

    def run(args):
        remaining = deadline - time.monotonic()
        require(remaining > 0, 'Single source-preparation allowance exhausted')
        execute(args, source, env, log, scope, remaining, commands, resources)
    if stage == 'engine':
        git = ['git', '-c', 'core.hooksPath=/dev/null', '-c', 'credential.helper=']
        run([*git, 'init', '--quiet'])
        run([*git, 'fetch', '--depth=1', '--no-tags', '--no-recurse-submodules', pin['repository'], pin['commit']])
        run([*git, 'checkout', '--detach', '--force', pin['commit']])
        require((source / '.git/HEAD').read_text().strip() == pin['commit'], 'Engine checkout is not the exact commit')
        require(files(source, True) == pin['files'], 'Engine complete public source/build postimages differ')
    else:
        archive = directory / 'upstream.tar.gz'
        run(['curl', '--fail', '--silent', '--show-error', '--proto', '=https', '--proto-redir', '=https',
             '--connect-timeout', '20', '--max-time', '150', '--max-filesize', str(pin['archive_bytes']),
             '--output', str(archive), pin['archive_url']])
        extract_public_archive(archive, source, pin)
        archive.unlink()
        expected = files(source, True)
        require(len(expected) == pin['upstream_files'], 'Unexpected public upstream source inventory')
        require(sha(source / 'LICENSE') == sha(recipe / 'LICENSE.pgjdbc'), 'Upstream BSD license differs')
        main = json.loads((recipe / 'source-manifest.json').read_text())
        tests = json.loads((recipe / 'test-source-manifest.json').read_text())
        for row in main['files'] + main['build_files'] + tests['files']:
            previous = expected.get(row['path'])
            require((previous is None) == (row['before'] is None), 'Unexpected native preimage presence')
            if previous:
                require(previous['sha256'] == row['before']['sha256'], 'Unexpected native source preimage')
            expected[row['path']] = {**row['after'], 'mode': previous['mode'] if previous else '100644'}
        series = (recipe / 'series').read_text().splitlines()
        require(len(series) == 6 and set(series) == {name for name in pin['recipe_files'] if name.endswith('.patch')},
                'Exact six-patch recipe required')
        for name in series:
            run(['git', 'apply', '--check', str(recipe / name)])
            run(['git', 'apply', str(recipe / name)])
        require(files(source, True) == expected, 'Six-patch postimages or unaffected native sources differ')
    require(time.monotonic() < deadline, 'Single source-preparation allowance exhausted')
    return source


def publication_products(directory, staging):
    result = json.loads((directory / 'publication-result.json').read_text())
    require(result['buildFailure'] is None, 'Local publication graph failed')
    require({row['task'] for row in result['publications']} == set(BINDING[result['stage']]['tasks']),
            'Missing actual publication task outcome')
    for row in result['publications']:
        require(row['executed'] and row['didWork'] and not any(row[key] for key in ('skipped', 'upToDate', 'failure')),
                'Local publication was not actually executed')
        version = row['version']
        folder = staging / row['group'].replace('.', '/') / row['module'] / version
        for artifact in row['artifacts']:
            built = Path(artifact['file'])
            require(built.resolve().is_relative_to(directory / 'source') and built.is_file() and not built.is_symlink(),
                    'Publication must originate from this fresh normal source build')
            suffix = '-' + artifact['classifier'] if artifact['classifier'] else ''
            published = folder / (row['module'] + '-' + version + suffix + '.' + artifact['extension'])
            require(sha(built) == artifact['sha256'] == sha(published), 'Staged artifact differs from actual publication input')
    return result


def pom_coordinates(path, wanted):
    raw = path.read_bytes()
    require(len(raw) <= 1024 * 1024 and b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper(), 'Invalid POM')
    root = ET.fromstring(raw)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    require(tuple(root.findtext('m:' + key, namespaces=ns) for key in ('groupId', 'artifactId', 'version')) == wanted,
            'Normal published Maven coordinate changed')
    return root, ns


def engine_artifacts(staging):
    records = []
    for module in ('source-contract', 'source-contract-jvm', 'source-engine', 'source-engine-jvm'):
        folder = staging / 'me/manga/kira/source' / module / '0.1.0'
        base = module + '-0.1.0'
        pom_coordinates(folder / (base + '.pom'), ('me.manga.kira.source', module, '0.1.0'))
        metadata = json.loads((folder / (base + '.module')).read_text())
        require(tuple(metadata['component'][key] for key in ('group', 'module', 'version')) == ('me.manga.kira.source', module, '0.1.0'),
                'Gradle module component changed')
        if not module.endswith('-jvm'):
            targets = [v.get('available-at', {}) for v in metadata['variants']]
            require(any(t.get('group') == 'me.manga.kira.source' and t.get('module') == module + '-jvm' and
                        t.get('version') == '0.1.0' for t in targets), 'Missing normal KMP-to-JVM metadata redirect')
        for variant in metadata['variants']:
            for item in variant.get('files', []):
                require(Path(item['url']).name == item['url'], 'Metadata artifact must be local to its version directory')
                artifact = folder / item['url']
                require(artifact.stat().st_size == item['size'], 'Gradle metadata artifact size mismatch')
                for algorithm in ('sha256', 'sha512', 'sha1', 'md5'):
                    if algorithm in item:
                        require(hashlib.new(algorithm, artifact.read_bytes()).hexdigest() == item[algorithm],
                                'Gradle module metadata content hash mismatch')
        for extension, kind in (('.jar', 'binary'), ('.pom', 'pom'), ('.module', 'gradle-module'), ('-sources.jar', 'sources')):
            path = folder / (base + extension)
            records.append({'group': 'me.manga.kira.source', 'module': module, 'version': '0.1.0', 'kind': kind,
                            'path': path.relative_to(staging).as_posix(), 'sha256': sha(path), 'bytes': path.stat().st_size})
    return records


def driver_artifacts(directory, staging, env, scope, commands, resources):
    folder = staging / 'me/manga/kira/internal/postgresql-owned-cut/42.7.12-kira.1'
    jar = folder / 'postgresql-owned-cut-42.7.12-kira.1.jar'
    pom = jar.with_suffix('.pom')
    root, ns = pom_coordinates(pom, ('me.manga.kira.internal', 'postgresql-owned-cut', '42.7.12-kira.1'))
    require(root.findtext('m:properties/m:kira.qualification', namespaces=ns) == 'UNQUALIFIED_RUN_OWNED_VERIFICATION',
            'Driver must remain explicitly unqualified')
    dependencies = [{key: d.findtext('m:' + key, namespaces=ns) for key in ('groupId', 'artifactId', 'version', 'scope')}
                    for d in root.findall('m:dependencies/m:dependency', ns)]
    require(dependencies == [{'groupId': 'org.checkerframework', 'artifactId': 'checker-qual', 'version': '3.55.1', 'scope': 'runtime'}],
            'Driver POM dependencies or shading changed')
    require(not jar.with_suffix('.module').exists(), 'Owned cut uses its normal POM, not shadow component metadata')
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and names.count('org/postgresql/Driver.class') == 1, 'Duplicate native provider')
        require(archive.read('META-INF/services/java.sql.Driver') == b'org.postgresql.Driver\n', 'Driver service changed')
        attrs = dict(line.split(': ', 1) for line in archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').splitlines() if ': ' in line)
        require(attrs['Automatic-Module-Name'] == attrs['Bundle-SymbolicName'] == 'org.postgresql.jdbc' and attrs['Multi-Release'] == 'true',
                'Driver module/OSGi metadata changed')
        classes = {name: int.from_bytes(archive.read(name)[6:8], 'big') for name in names if name.endswith('.class')}
        require(all(value <= 52 for name, value in classes.items() if not name.startswith('META-INF/versions/')), 'Driver must retain Java8')
        mr = {name: value for name, value in classes.items() if name.startswith('META-INF/versions/')}
        require(mr and all(name.startswith('META-INF/versions/11/') and value == 55 for name, value in mr.items()), 'Driver MR11 changed')
        require(not any(name.startswith('com/ongres/') for name in names) and any(name.startswith('org/postgresql/shaded/com/ongres/') for name in classes),
                'SCRAM must remain shaded, never an unshaded transitive replacement')
        require('META-INF/LICENSE' in names and all(any(name.startswith('META-INF/licenses/' + group + '/') and not name.endswith('/') for name in names)
                                                   for group in ('com.ongres.scram', 'com.ongres.stringprep')), 'Required driver/dependency licenses missing')
        handles = ('1', 'Opening', 'Root', 'Owner', 'Life', 'Invocation')
        require(all('org/postgresql/jdbc/KiraOwnedJdbcCut$' + name + '.class' in names for name in handles), 'Missing opaque native ABI classes')
        require('org/postgresql/core/v3/KiraOwnedParameterBridge.class' in names, 'Missing real native parameter bridge')
    abi = directory / 'javap-abi.txt'
    execute([str(Path(env['JAVA_HOME']) / 'bin/javap'), '-public', '-s', '-classpath', str(jar), 'org.postgresql.jdbc.KiraOwnedJdbcCut'],
            directory, env, abi, scope, 20, commands, resources)
    signatures = dict(re.findall(r'public static [^;\n]+? (\w+)\([^\n]*\)(?: throws [^;\n]+)?;\s+descriptor: (\S+)', abi.read_text()))
    require(all(signatures.get(name) == value for name, value in BINDING['driver']['abi_descriptors'].items()),
            'Native/core23 public descriptors changed; new bytes are not historical ABI qualification')
    return [{'path': path.relative_to(staging).as_posix(), 'sha256': sha(path), 'bytes': path.stat().st_size} for path in (jar, pom)]


def run_stage(stage, runtime, evidence, recipe, java_home):
    from app29_linux_owned_processes import OwnedChildren
    directory = runtime / stage
    directory.mkdir(mode=0o700)
    original = identity(directory)
    for name in ('home', 'gradle-home', 'project-cache', 'kotlin', 'konan', 'tmp'):
        (directory / name).mkdir(mode=0o700)
    env = {'PATH': str(java_home / 'bin') + ':/usr/bin:/bin', 'JAVA_HOME': str(java_home), 'HOME': str(directory / 'home'),
           'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC', 'CI': 'true', 'GRADLE_USER_HOME': str(directory / 'gradle-home'),
           'KONAN_DATA_DIR': str(directory / 'konan'), 'TMPDIR': str(directory / 'tmp'), 'TMP': str(directory / 'tmp'),
           'TEMP': str(directory / 'tmp'), 'KIRA_RUNTIME_CONTROL': str(CONTROL), 'KIRA_RUNTIME_STAGE': stage,
           'KIRA_RUNTIME_STAGE_ROOT': str(directory), 'KIRA_PUBLIC_RUNTIME': str(runtime), 'PYTHONDONTWRITEBYTECODE': '1',
           'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_TERMINAL_PROMPT': '0', 'GIT_ALLOW_PROTOCOL': 'https'}
    if stage == 'engine':
        for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
            if os.environ.get(key):
                env[key] = os.environ[key]
        env['ANDROID_USER_HOME'] = str(directory / 'home/.android')
    scope, commands = OwnedChildren(), []
    resources = {'stage': stage, 'started_monotonic': time.monotonic(), 'limits': BINDING['limits'], 'samples': []}
    record = {'stage': stage, 'errors': [], 'safeToRemove': False, 'removed': False, 'buildExit': None}
    produced, before = None, None
    previous_maven = files(runtime / 'maven')
    try:
        scope.activate()
        sample_resources(directory, resources, 'admission', admission=True)
        source = prepare_source(stage, directory, env, scope, commands, recipe, resources)
        before = files(source, True)
        emit(evidence / (stage + '-source-before.json'), before)
        jvm = '-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=' + str(directory / 'tmp')
        common = ['--no-daemon', '--no-parallel', '--max-workers=2' if stage == 'engine' else '--max-workers=1',
                  '--no-build-cache', '--no-configuration-cache', '--no-scan', '--console=plain', '--stacktrace',
                  '--project-cache-dir', str(directory / 'project-cache'), '-Dorg.gradle.jvmargs=' + jvm,
                  '-Duser.home=' + str(directory / 'home'), '-Dorg.gradle.java.home=' + str(java_home), '-Dorg.gradle.vfs.watch=false',
                  '-Porg.gradle.java.installations.auto-download=false', '-Porg.gradle.java.installations.auto-detect=false',
                  '-Porg.gradle.java.installations.paths=' + str(java_home), '-Pkotlin.compiler.execution.strategy=in-process',
                  '-Pkotlin.project.persistent.dir=' + str(directory / 'kotlin'), '-I', str(CONTROL / 'runtime-publication.init.gradle')]
        if stage == 'engine':
            common += ['-PVERSION_NAME=0.1.0', '-Pandroid.builder.sdkDownload=false', '-Dmaven.repo.local=' + str(runtime / 'maven')]
        else:
            common += ['-Prelease=true', '-Ppgjdbc.version=42.7.12-kira.1', '-Psigning.pgp.enabled=OFF', '-PjdkBuildVersion=21',
                       '-PjdkTestVersion=21', '-PtargetJavaVersion=8', '-PenableMavenLocal=false', '-Ps3.build.cache=false',
                       '-PenableGettext=false', '-PkiraOwnedPgVerificationRepository=' + str(runtime / 'maven')]
        execute(['./gradlew', *common, *BINDING[stage]['tasks']], source, env, directory / 'gradle.log', scope,
                BINDING[stage]['build_seconds'], commands, resources)
        record['buildExit'] = 0
        publication_products(directory, runtime / 'maven')
        produced = engine_artifacts(runtime / 'maven') if stage == 'engine' else driver_artifacts(directory, runtime / 'maven', env, scope, commands, resources)
        now = files(runtime / 'maven')
        require(all(now.get(path) == row for path, row in previous_maven.items()), 'Later stage altered earlier Maven outputs')
        prefix = 'me/manga/kira/source/' if stage == 'engine' else 'me/manga/kira/internal/postgresql-owned-cut/'
        require(all(path.startswith(prefix) for path in set(now) - set(previous_maven)), 'Unexpected publication coordinate')
    except BaseException as error:
        record['errors'].append(problem('prepare/build/artifact', error))
    finally:
        try:
            require(scope.active, 'No cleanup child without acquired original ownership')
            version = BINDING[stage]['gradle_version']
            installed = list((directory / 'gradle-home').glob('wrapper/dists/gradle-' + version + '-bin/*/gradle-' + version + '/bin/gradle'))
            require(len(installed) <= 1, 'Ambiguous owned Gradle wrapper installation')
            if installed:
                # Admission/floor trips must not prevent the original owner's normal cleanup.
                execute([str(installed[0]), '--gradle-user-home', str(directory / 'gradle-home'), '--stop', '--console=plain'],
                        directory, env, directory / 'stop.log', scope, 30, commands)
                record['gradleStop'] = 0
            else:
                record['gradleStop'] = 'NOT_INSTALLED'
                require(record['buildExit'] is None, 'Successful build must have its exact owned wrapper')
        except BaseException as error:
            record['errors'].append(problem('owned-gradle-stop', error))
        try:
            record['children'] = scope.barrier(natural_timeout=5, term_timeout=10, kill_timeout=5)
            record['finalChildren'] = scope.barrier(natural_timeout=2, term_timeout=5, kill_timeout=3)
            record['subreaper'] = scope.restore()
            record['safeToRemove'] = True
        except BaseException as error:
            record['errors'].append(problem('original-child-cleanup', error))
    record['commands'] = commands
    if record['safeToRemove']:
        try:
            if before is not None:
                after = files(directory / 'source', True)
                emit(evidence / (stage + '-source-after.json'), after)
                require(before == after, 'Fresh source changed during build')
            for name in ('prepare.log', 'gradle.log', 'stop.log', 'publication-result.json', 'javap-abi.txt'):
                path = directory / name
                if path.is_file() and not path.is_symlink():
                    with path.open('rb') as src:
                        raw = src.read(BINDING['limits']['stage_log_bytes'] + 1)
                    (evidence / (stage + '-' + name)).write_bytes(raw[:BINDING['limits']['stage_log_bytes']])
                    if len(raw) > BINDING['limits']['stage_log_bytes']:
                        record.setdefault('truncatedDiagnostics', []).append(name)
        except BaseException as error:
            record['errors'].append(problem('closed-source/evidence', error))
        finally:
            require(identity(directory) == original and shutil.rmtree.avoids_symlink_attacks, 'Changed stage identity; refuse deletion')
            shutil.rmtree(directory)
            record['removed'] = not os.path.lexists(directory)
    record['pass'] = (record['buildExit'] == 0 and produced is not None and not record['errors'] and record['removed'] and
                      record.get('gradleStop') == 0 and not record.get('children', {}).get('forced', True) and
                      not record.get('finalChildren', {}).get('forced', True))
    emit(evidence / (stage + '-resources.json'), resources)
    emit(evidence / (stage + '-result.json'), record)
    return record, produced


def main():
    require(BINDING['status'] == 'BOUND' and BINDING['primary_authority'] == 'ADMITTED_BY_PRIMARY', 'INERT: runtime reconstruction not admitted')
    require(os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'Dedicated hosted reconstruction only')
    require(sha(CONTROL / 'app29_linux_owned_processes.py') == BINDING['owned_helper_sha256'], 'Owned-child helper changed')
    os.umask(0o077)
    runtime = Path(os.environ['KIRA_PUBLIC_RUNTIME']).resolve(strict=True)
    evidence = Path(os.environ['KIRA_PUBLIC_RUNTIME_EVIDENCE']).resolve(strict=True)
    temporary = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    require(runtime.parent == temporary and evidence.parent == temporary and runtime != evidence, 'Two fresh dedicated runner-temp roots required')
    for path in (runtime, evidence):
        require(path.stat().st_uid == os.geteuid() and path.stat().st_mode & 0o777 == 0o700 and not list(path.iterdir()), 'Fresh0700 owned root required')
    original = identity(runtime)
    recipe = Path(os.environ['KIRA_PUBLIC_SOURCE']).resolve(strict=True) / BINDING['driver']['recipe_path']
    require(files(recipe) == BINDING['driver']['recipe_files'], 'Backend public recipe bytes differ')
    java = Path(os.environ['JAVA_HOME']).resolve(strict=True)
    require(re.search(r'^JAVA_VERSION="21[.\"]', (java / 'release').read_text(), re.M), 'JDK21 required')
    emit(evidence / 'jdk-inputs.json', {name: sha(java / name) for name in ('release', 'bin/java', 'lib/modules', 'lib/server/libjvm.so')})
    (runtime / 'runtime-started.marker').write_text('Original reconstruction owner started; no blind fallback deletion.\n')
    (runtime / 'maven').mkdir(mode=0o700)
    stages, produced = {}, {}
    for stage in ('engine', 'driver'):
        # Never leave an earlier stage's positive cleanup receipt while a later actor is starting.
        emit(runtime / 'runtime-cleanup.json', {'schema': 1, 'state': 'RUNNING', 'activeStage': stage,
                                               'rootIdentity': original, 'safeToRemove': False})
        record, artifacts = run_stage(stage, runtime, evidence, recipe, java)
        stages[stage], produced[stage] = record, artifacts
        cleanup = {'schema': 1, 'state': 'ENDED', 'rootIdentity': original,
                   'safeToRemove': all(row['safeToRemove'] and row['removed'] for row in stages.values()),
                   'stages': {name: {'pass': row['pass'], 'safeToRemove': row['safeToRemove'], 'removed': row['removed']} for name, row in stages.items()}}
        if not record['pass']:
            emit(runtime / 'runtime-cleanup.json', cleanup)
            emit(evidence / 'runtime-cleanup.json', cleanup)
            print('Fresh runtime NOT_ACCEPTED; stage failed; no backend admission.')
            return 1
    require(identity(runtime) == original and files(recipe) == BINDING['driver']['recipe_files'], 'Runtime root/public recipe changed')
    manifest = {'schema': 1, 'status': 'NEW_UNQUALIFIED_VERIFICATION_RUNTIME', 'qualified': False, 'historicalBinaryIdentityClaimed': False,
                'driverJar': produced['driver'][0], 'driverPom': produced['driver'][1], 'engineArtifacts': produced['engine'],
                'mavenFiles': files(runtime / 'maven'), 'sources': {'engineCommit': BINDING['engine']['commit'],
                'pgjdbcCommit': BINDING['driver']['commit'], 'recipePins': BINDING['driver']['recipe_files']}, 'cleanup': {'engine': True, 'driver': True}}
    emit(runtime / 'runtime-manifest.json', manifest)
    emit(evidence / 'runtime-manifest.json', manifest)
    emit(runtime / 'runtime-cleanup.json', cleanup)
    emit(evidence / 'runtime-cleanup.json', cleanup)
    print('Fresh unqualified runtime prepared; no tests or production qualification claimed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
