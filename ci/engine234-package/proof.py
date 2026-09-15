"""Gate09 source/artifact receipt derivative; Engine6's full15 native model, never relabelled bytes."""
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

control = Path(__file__).resolve().parent
carrier = control.parents[1]
root = Path(os.environ['ENGINE234_PACKAGE_RUN'])
reports = root / 'reports'
inputs = json.loads((control / 'inputs.json').read_text())
version = inputs['version_prefix'] + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
group = 'me.manga.kira.source'
modules = inputs['modules']
publications = inputs['publications']
repository = root / 'repository'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def save(name, data):
    payload = (json.dumps(data, indent=2, sort_keys=True) + '\n').encode()
    require(len(payload) <= 1024**2, 'Receipt bound')
    (reports / name).write_bytes(payload)


def git(path, *arguments):
    return subprocess.check_output(['git', '-C', str(path), *arguments],
        env=dict(os.environ, GIT_OPTIONAL_LOCKS='0'), timeout=10).decode().strip()


def source_map(path):
    expected = inputs['engine']['files']
    require(all((path / name).is_file() and not (path / name).is_symlink() for name in expected), 'Source file missing/symlink')
    actual = {name: sha(path / name) for name in expected}
    require(actual == expected, 'Exact8cb source bytes changed')
    generated = [prefix + name + '/' for prefix in [''] + [m + '/' for m in modules]
                 for name in ['build', '.gradle', '.kotlin']]
    extras = [str(p.relative_to(path)) for p in path.rglob('*') if p.is_file() and
              str(p.relative_to(path)) not in expected and
              not any(str(p.relative_to(path)).startswith(prefix) for prefix in generated)]
    require(not extras, 'Unbound source input')
    return actual


def bind():
    spec = inputs['engine']
    checkout = Path(os.environ['GITHUB_WORKSPACE']) / 'inputs/engine'
    require(git(checkout, 'rev-parse', 'HEAD') == spec['head'] and
        git(checkout, 'rev-parse', 'HEAD^{tree}') == spec['tree'], 'Source commit/tree identity')
    require(not git(checkout, 'status', '--porcelain=v1', '--untracked-files=no'), 'Tracked source drift')
    require(set(git(checkout, 'ls-files').splitlines()) == set(spec['files']), 'Exact tracked source set')
    require(not git(checkout, 'ls-files', '--others', '--exclude-standard'), 'Unexpected checkout input')
    require(git(carrier, 'rev-parse', 'HEAD') == os.environ['GITHUB_SHA'], 'Carrier identity')
    destination = root / 'neutral'
    destination.mkdir(mode=0o700, exist_ok=False)
    for name, expected in spec['files'].items():
        source = checkout / name
        require(source.is_file() and not source.is_symlink() and sha(source) == expected, 'Unbound source bytes')
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o700 if source.stat().st_mode & 0o111 else 0o600)
    save('source-before.json', source_map(destination))
    save('inputs.json', inputs)
    paths = [*control.iterdir(), carrier / '.github/workflows/engine234-package.yml']
    save('bindings.json', {'engine': {k: spec[k] for k in ('repository', 'head', 'tree')},
        'candidate': version, 'carrier': os.environ['GITHUB_SHA'], 'run': os.environ['GITHUB_RUN_ID'],
        'attempt': os.environ['GITHUB_RUN_ATTEMPT'],
        'controls': {str(p.relative_to(carrier)): sha(p) for p in paths if p.is_file()},
        'producer_tests': '137 accepted JVM cases carried; zero tests selected here', 'shipping': False})


def inventory():
    paths = sorted(p for p in repository.rglob('*') if p.is_file())
    require(len(paths) <= 512 and sum(p.stat().st_size for p in paths) <= 64 * 1024**2, 'Repository bounds')
    require(all(not p.is_symlink() and p.resolve().is_relative_to(repository.resolve()) for p in paths), 'Repository escape')
    return {str(p.relative_to(repository)): {'bytes': p.stat().st_size, 'sha256': sha(p)} for p in paths}


def linked(path, url):
    require(isinstance(url, str) and re.fullmatch(r'(?:\.\./){0,2}[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*', url),
        'Unexpected native module link')
    target = (path.parent / url).resolve()
    require(target.is_relative_to(repository.resolve()) and target.is_file() and not target.is_symlink(), 'Missing/escaping module link')
    return target


def metadata_versions(node):
    if isinstance(node, dict):
        if node.get('group') == group and 'version' in node:
            value = node['version']
            require(value == version or isinstance(value, dict) and value and all(v == version for v in value.values()),
                'Source metadata points to another version')
        for child in node.values():
            metadata_versions(child)
    elif isinstance(node, list):
        for child in node:
            metadata_versions(child)


def publications_receipt():
    files = inventory()
    expected_coordinates = {f'{group}:{module}{spec["suffix"]}:{version}' for module in modules for spec in publications.values()}
    model = [line.split('\t') for line in (reports / 'publications-model.tsv').read_text().splitlines()]
    require(len(model) == 15 and all(len(row) == 2 for row in model) and
        {row[1] for row in model} == expected_coordinates, 'Full15 observed native publication model')
    identities, klibs = [], []
    for module in modules:
        for publication, spec in publications.items():
            suffix = spec['suffix']
            name = module + suffix
            prefix = repository / 'me/manga/kira/source' / name / version / (name + '-' + version)
            pom_path, module_path, binary = (Path(str(prefix) + ext) for ext in ('.pom', '.module', spec['binary']))
            require(all(p.is_file() for p in (pom_path, module_path, binary)), 'Missing native publication bytes')
            pom = ET.parse(pom_path).getroot()
            require([pom.findtext('{*}' + tag) for tag in ('groupId', 'artifactId', 'version')] ==
                [group, name, version], 'Generated POM GAV drift')
            expected_edges = [] if module == 'source-contract' else ['source-contract'] if module == 'source-engine' else ['source-contract', 'source-engine']
            actual_edges = [(d.findtext('{*}artifactId'), d.findtext('{*}version'), d.findtext('{*}scope'))
                for d in pom.findall('./{*}dependencies/{*}dependency') if d.findtext('{*}groupId') == group]
            require(sorted(actual_edges) == sorted((edge + suffix, version, 'runtime' if not suffix else 'compile')
                for edge in expected_edges), 'Generated POM target-coordinate rewrite differs from native Engine6 model')
            require(not pom.findall('./{*}dependencyManagement'), 'Unexpected POM dependency-management overlay')
            metadata = json.loads(module_path.read_text())
            component = metadata['component']
            require(all(component.get(k) == v for k, v in {'group': group, 'module': module, 'version': version}.items()),
                'Native module component identity')
            root_link = f'../../{module}/{version}/{module}-{version}.module'
            require(component.get('url') == root_link if suffix else 'url' not in component, 'Native root backlink')
            if suffix:
                linked(module_path, component['url'])
            metadata_versions(metadata)
            root_targets = set()
            for variant in metadata['variants']:
                if 'available-at' in variant:
                    edge = variant['available-at']
                    target = linked(module_path, edge['url'])
                    require(edge['group'] == group and edge['version'] == version and
                        f'{group}:{edge["module"]}:{version}' in expected_coordinates and
                        target.name == edge['module'] + '-' + version + '.module', 'Incorrect native target link')
                    root_targets.add(edge['module'])
                for file in variant.get('files', []):
                    target = linked(module_path, file['url'])
                    require(target.stat().st_size == file['size'], 'Gradle module file size mismatch')
                    for algorithm in ('sha512', 'sha256', 'sha1', 'md5'):
                        if algorithm in file:
                            require(hashlib.new(algorithm, target.read_bytes()).hexdigest() == file[algorithm], 'Gradle module file digest mismatch')
            if not suffix:
                require(root_targets == {module + p['suffix'] for p in publications.values() if p['suffix']},
                    'Root metadata does not expose exactly Android/JVM/two Apple targets')
            if spec['binary'] == '.klib':
                with zipfile.ZipFile(binary) as archive:
                    manifest = dict(line.split('=', 1) for line in archive.read('default/manifest').decode().splitlines() if '=' in line)
                    require(manifest.get('builtins_platform') == 'NATIVE' and manifest.get('native_targets') == spec['native_target'] and
                        manifest.get('compiler_version') == '2.2.21' and
                        manifest.get('unique_name', '').replace('\\:', ':') == group + ':' + module and
                        any(p.startswith('default/ir/') for p in archive.namelist()), 'Wrong/empty native KLIB')
                    klibs.append({'module': name, 'sha256': sha(binary), 'nativeTarget': manifest['native_targets'],
                        'compiler': manifest['compiler_version'], 'abiVersion': manifest.get('abi_version')})
            identities.append({'publication': publication, 'gav': f'{group}:{name}:{version}',
                'binary': str(binary.relative_to(repository)), 'sha256': sha(binary)})
    # Presence is not whole-library ABI equivalence: actual Kotlin2.1/2.4 consumer compilation follows.
    engine_dir = repository / 'me/manga/kira/source'
    jvm = engine_dir / f'source-engine-jvm/{version}/source-engine-jvm-{version}.jar'
    android = engine_dir / f'source-engine-android/{version}/source-engine-android-{version}.aar'
    with zipfile.ZipFile(jvm) as archive:
        require(archive.read(inputs['api_class']).startswith(b'\xca\xfe\xba\xbe'), 'Missing new JVM declaration API')
    with zipfile.ZipFile(android) as aar, zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as archive:
        require(archive.read(inputs['api_class']).startswith(b'\xca\xfe\xba\xbe'), 'Missing new Android declaration API')
    # Sidecars are checked, not synthesized; mutable Maven indexes remain a distinct non-release concern.
    for relative in files:
        path = repository / relative
        if path.suffix in ('.md5', '.sha1', '.sha256', '.sha512'):
            original = Path(str(path).rsplit('.', 1)[0])
            require(original.is_file() and path.read_text().strip() == hashlib.new(path.suffix[1:], original.read_bytes()).hexdigest(),
                'Native publisher sidecar mismatch')
    outcomes = dict(line.split('\t', 1) for line in (reports / 'task-outcomes.tsv').read_text().splitlines())
    for module in modules:
        tasks = ['compileAndroidMain', 'compileKotlinJvm', 'compileCommonMainKotlinMetadata',
            'compileKotlinIosArm64', 'compileKotlinIosSimulatorArm64', 'bundleAndroidMainAar', 'jvmJar',
            'iosArm64Klib', 'iosSimulatorArm64Klib']
        tasks += ['publish' + p[0].upper() + p[1:] + 'PublicationToEngine234CandidateRepository' for p in publications]
        require(all(outcomes.get(':' + module + ':' + task) == 'SUCCESS' for task in tasks), 'Required actual producer/publication task did not execute')
    parser = json.loads((reports / 'producer-parser.json').read_text())
    require(parser['module'] == 'com.fleeksoft.ksoup:ksoup-jvm:0.2.5' and
        parser['sha256'] == inputs['parser']['producer_jvm_sha256'], 'Producer parser receipt')
    save('publications.json', {'producer': inputs['engine']['head'], 'version': version, 'files': files,
        'components': identities, 'appleKlibs': klibs, 'apiPresenceOnly': inputs['api_class'],
        'consumerQualification': 'NOT_RUN', 'registryPublication': 'NOT_AUTHORIZED'})
    archive_path = reports / 'candidate-maven.tar.gz'
    with tarfile.open(archive_path, 'w:gz') as archive:
        for relative in files:
            archive.add(repository / relative, arcname='maven/' + relative, recursive=False)
    require(archive_path.stat().st_size <= 64 * 1024**2 and inventory() == files, 'Archive bound/repository drift')
    save('candidate-archive.json', {'path': archive_path.name, 'bytes': archive_path.stat().st_size,
        'sha256': sha(archive_path), 'files': len(files), 'version': version, 'nativeGradleBytesUnmodified': True})


def collect():
    errors = []
    try:
        save('source-after.json', source_map(root / 'neutral'))
        require(os.environ.get('ENGINE234_PRODUCER_OUTCOME') == 'success', 'Producer step did not succeed')
        for name in ('publish-deadline.json', 'neutral-capture.json', 'sdk.json'):
            result = json.loads((reports / name).read_text())
            if name == 'publish-deadline.json':
                require(result['deadline'] == 'PASS', 'Producer deadline failed/forced')
            elif name == 'neutral-capture.json':
                require(result['complete'] and not result['truncated'], 'Producer log incomplete')
        for name, expected in inputs['sdk']['platform_sha256'].items():
            require(sha(root / 'android-sdk/platforms/android-37.0' / name) == expected, 'SDK platform mutated')
        publications_receipt()
    except Exception as failure:
        errors.append(type(failure).__name__ + ': ' + str(failure)[:1000])
    save('result.json', {'status': 'PASS_REQUIRES_PRIMARY_REVIEW' if not errors else 'FAIL_OR_INCOMPLETE',
        'errors': errors, 'version': version, 'engine': inputs['engine']['head'], 'testsExecuted': 0,
        'carriedProducerCases': 137, 'appConsumer': 'NOT_RUN', 'backendConsumer': 'NOT_RUN',
        'frameworkLinkOrDeviceQualification': 'NOT_RUN', 'registryOrReleaseAuthority': False})
    require(not errors, 'Incomplete native candidate; no consumer/publication acceptance')


if __name__ == '__main__':
    require(sys.argv[1] in ('bind', 'collect'), 'Unknown fixed receipt phase')
    globals()[sys.argv[1]]()
