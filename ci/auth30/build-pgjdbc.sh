#!/usr/bin/env bash
# One fresh native pgjdbc publication, from public pinned source, for this run only.
# No retained binary, Maven-local fallback, remote publication, or native test run.
set -euo pipefail
umask 077
[[ $# == 0 && ${GITHUB_RUN_ID:?} =~ ^[0-9]+$ && ${GITHUB_RUN_ATTEMPT:?} =~ ^[0-9]+$ ]]
C=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
W=$(realpath -- "${GITHUB_WORKSPACE:?}")
T=$(realpath -- "${RUNNER_TEMP:?}")
R="$T/auth30-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
[[ ${AUTH30_RUN_ROOT:?} == "$R" && ! -L $R && $(realpath -- "$R") == "$R" ]]
[[ $(realpath -- "$C/../..") == "$W/backend" && ! -L $R/.owner &&
   $(cat "$R/.owner") == "$GITHUB_RUN_ID:$GITHUB_RUN_ATTEMPT" && -x ${JAVA_HOME:?}/bin/java ]]
E="$R/evidence"
S="$R/pgjdbc"
G="$R/pgjdbc-gradle"
A="$R/pgjdbc-archive.tar.gz"
OWNED=("$S" "$G" "$R/pgjdbc-cache" "$R/pgjdbc-kotlin" "$A")
for name in evidence maven tmp home; do
  [[ -d $R/$name && ! -L $R/$name && $(realpath -- "$R/$name") == "$R/$name" ]]
done
for path in "${OWNED[@]}" "$E/pgjdbc.started" "$E/pgjdbc.exit" "$E/pgjdbc-cleanup.exit" \
    "$R/maven/me/manga/kira/internal/postgresql-owned-cut"; do
  [[ ! -e $path && ! -L $path ]]
done
CLEAN_ENV=(env -i "PATH=$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin" "JAVA_HOME=$JAVA_HOME"
  "HOME=$R/home" "GRADLE_USER_HOME=$G" "TMPDIR=$R/tmp" "XDG_CACHE_HOME=$R/home/.cache"
  "LANG=C.UTF-8" "LC_ALL=C.UTF-8" "TZ=UTC" "AUTH30_RUN_ROOT=$R"
  "JAVA_TOOL_OPTIONS=-Xmx768m -Djava.io.tmpdir=$R/tmp -Duser.home=$R/home"
  GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0)

capture() {
  local label=$1 duration=$2
  shift 2
  set +e
  timeout --signal=TERM --kill-after=10s "$duration" "${CLEAN_ENV[@]}" "$@" 2>&1 |
    python3 "$C/cap.py" "$E/$label.log"
  local codes=("${PIPESTATUS[@]}")
  set -e
  printf '%s\n' "${codes[0]}" > "$E/$label-command.exit"
  printf '%s\n' "${codes[1]}" > "$E/$label-capture.exit"
  if [[ ${codes[0]} != 0 || ${codes[1]} != 0 ]]; then
    tail -60 "$E/$label.log"
    return 1
  fi
}

STOPPED=0
STOP_RC=0
QUIET=0
stop_and_quiet() {
  [[ $STOPPED == 0 ]] || return "$STOP_RC"
  STOPPED=1
  shopt -s nullglob
  local launchers=("$G/wrapper/dists/gradle-9.4.1-bin/"*/gradle-9.4.1/bin/gradle)
  shopt -u nullglob
  if ((${#launchers[@]} == 1)); then
    capture pgjdbc-stop-immediate 60s "${launchers[0]}" --stop -g "$G" --console=plain || STOP_RC=1
  elif ((${#launchers[@]} == 0)); then
    printf 'NO_INSTALLED_OWNED_DISTRIBUTION\n' > "$E/pgjdbc-stop-immediate.txt"
  else
    STOP_RC=1
  fi
  if python3 "$C/quiet.py" pgjdbc-immediate; then QUIET=1; else STOP_RC=1; fi
  return "$STOP_RC"
}
cleanup() {
  local rc=$? cleanup_rc=0
  trap - EXIT
  stop_and_quiet || cleanup_rc=1
  if [[ $QUIET == 1 && ! -L $R && $(realpath -- "$R") == "$R" && ! -L $R/.owner &&
        $(cat "$R/.owner") == "$GITHUB_RUN_ID:$GITHUB_RUN_ATTEMPT" ]]; then
    cd -- "$R"
    for path in "${OWNED[@]}"; do
      if [[ ! -L $path && $(realpath -m -- "$path") == "$path" ]]; then
        rm -rf --one-file-system -- "$path" || cleanup_rc=1
        [[ ! -e $path && ! -L $path ]] || cleanup_rc=1
      else cleanup_rc=1; fi
    done
  else cleanup_rc=1; fi
  printf '%s\n' "$cleanup_rc" > "$E/pgjdbc-cleanup.exit"
  if [[ $rc != 0 || $cleanup_rc != 0 ]]; then exit 1; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
date +%s > "$E/pgjdbc.started"
printf '1\n' > "$E/pgjdbc.exit"
mkdir -- "$S" "$G" "$R/pgjdbc-cache" "$R/pgjdbc-kotlin"
URL=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["upstream"]["archive_url"])' "$C/pgjdbc-inputs.json")
capture pgjdbc-fetch 65s curl --disable --fail --silent --show-error --location \
  --proto '=https' --proto-redir '=https' --max-redirs 2 --connect-timeout 15 --max-time 60 \
  --max-filesize 2378289 --output "$A" "$URL"
capture pgjdbc-source 45s python3 - "$C" <<'PY'
import hashlib, json, os, shutil, subprocess, sys, tarfile
from pathlib import Path, PurePosixPath

C = Path(sys.argv[1])
R = Path(os.environ["AUTH30_RUN_ROOT"])
S, V = R / "pgjdbc", C.parent.parent / "vendor/pgjdbc-owned-cut"
inputs = json.loads((C / "pgjdbc-inputs.json").read_text())
def identity(path):
    assert path.is_file() and not path.is_symlink(), "Non-regular input"
    return {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size}
def inventory(root):
    result = {}
    for path in sorted(root.rglob("*")):
        assert not path.is_symlink(), "Symlink in input tree"
        if path.is_file():
            result[str(path.relative_to(root))] = identity(path)
    return result

upstream = inputs["upstream"]
assert identity(R / "pgjdbc-archive.tar.gz") == {"sha256": upstream["archive_sha256"], "bytes": upstream["archive_bytes"]}
for name, pin in inputs["vendor_files"].items():
    assert identity(V / name) == pin, "Source patch or notice changed: " + name
assert (V / "series").read_text().splitlines() == inputs["series"]
with tarfile.open(R / "pgjdbc-archive.tar.gz", "r:gz") as archive:
    seen, count = set(), 0
    for member in archive:
        parts = PurePosixPath(member.name).parts
        assert parts and parts[0] == inputs["archive_root"] and ".." not in parts
        assert member.isdir() or member.isfile(), "Archive link or special file"
        assert member.name not in seen, "Duplicate archive member"
        seen.add(member.name)
        target = S.joinpath(*parts[1:])
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output)
            target.chmod(0o700 if member.mode & 0o111 else 0o600)
            count += 1
assert count == upstream["files"]
for name in inputs["series"]:
    for check in (True, False):
        subprocess.run(["git", "apply", *(["--check"] if check else []), str(V / name)], cwd=S, check=True, timeout=10)
for name, pin in inputs["postimages"].items():
    assert identity(S / name) == pin, "Patched source differs: " + name
for name, sha in inputs["wrapper"]["files"].items():
    assert identity(S / name)["sha256"] == sha, "Wrapper changed: " + name
properties = (S / "gradle/wrapper/gradle-wrapper.properties").read_text()
assert "distributionSha256Sum=" + inputs["wrapper"]["distribution_sha256"] in properties
assert 'JAVA_VERSION="21.' in Path(os.environ["JAVA_HOME"], "release").read_text()
sources = inventory(S)
assert len(sources) == inputs["patched_files"]
assert not any(set(Path(name).parts) & {"build", ".gradle", ".kotlin", ".git"} for name in sources)
(R / "evidence/pgjdbc-source.json").write_text(json.dumps({
    "source_commit": upstream["commit"], "recipe": identity(C / "pgjdbc-inputs.json"),
    "files": sources, "maven_before": inventory(R / "maven"),
}, sort_keys=True) + "\n")
print("Pinned public archive, six patches, source postimages and wrapper verified.")
PY

cd -- "$S"
capture pgjdbc 720s ./gradlew \
  --no-daemon --no-parallel --max-workers=1 --no-build-cache --no-configuration-cache \
  --no-scan --console=plain --stacktrace --project-cache-dir "$R/pgjdbc-cache" \
  "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$R/tmp -Duser.home=$R/home" \
  "-Dorg.gradle.java.home=$JAVA_HOME" -Dorg.gradle.vfs.watch=false "-Djava.io.tmpdir=$R/tmp" \
  -Porg.gradle.java.installations.auto-download=false -Porg.gradle.java.installations.auto-detect=false \
  "-Porg.gradle.java.installations.paths=$JAVA_HOME" -Pkotlin.compiler.execution.strategy=in-process \
  "-Pkotlin.project.persistent.dir=$R/pgjdbc-kotlin" -Prelease=true -Ppgjdbc.version=42.7.12-kira.1 \
  -Psigning.pgp.enabled=OFF -PjdkBuildVersion=21 -PjdkTestVersion=21 -PtargetJavaVersion=8 \
  -PenableMavenLocal=false -Ps3.build.cache=false -PenableGettext=false \
  "-PkiraOwnedPgVerificationRepository=$R/maven" \
  :postgresql:publishKiraOwnedCutPublicationToKiraOwnedPgVerificationRepository
stop_and_quiet
capture pgjdbc-readback 45s python3 - "$C" <<'PY'
import hashlib, json, os, re, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

C = Path(sys.argv[1])
R = Path(os.environ["AUTH30_RUN_ROOT"])
E, S, M = R / "evidence", R / "pgjdbc", R / "maven"
inputs = json.loads((C / "pgjdbc-inputs.json").read_text())
before = json.loads((E / "pgjdbc-source.json").read_text())
def identity(path):
    assert path.is_file() and not path.is_symlink(), "Missing or non-regular output"
    return {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size}
def inventory(root, source=False):
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if source and set(relative.parts) & {"build", ".gradle", ".kotlin"}:
            continue
        assert not path.is_symlink(), "Symlink in readback"
        if path.is_file():
            result[str(relative)] = identity(path)
    return result

assert identity(C / "pgjdbc-inputs.json") == before["recipe"]
assert inventory(S, source=True) == before["files"], "Source changed during build"
tasks = dict(re.findall(r"^> Task (:\S+)([^\n]*)$", (E / "pgjdbc.log").read_text(), re.MULTILINE))
for task in (":postgresql:compileJava", ":postgresql:osgiJar", inputs["publication"]["task"]):
    assert task in tasks and not tasks[task].strip(), "Native task did not freshly succeed: " + task
prefix = "me/manga/kira/internal/postgresql-owned-cut/"
stem = prefix + "42.7.12-kira.1/postgresql-owned-cut-42.7.12-kira.1"
jar, pom = M / (stem + ".jar"), M / (stem + ".pom")
files = inventory(M)
published = {p: v for p, v in files.items() if p.startswith(prefix)}
assert {p: v for p, v in files.items() if not p.startswith(prefix)} == before["maven_before"], "Other Maven outputs changed"
base_files = {stem + ".jar", stem + ".pom", prefix + "maven-metadata.xml"}
assert set(published) == {p + suffix for p in base_files for suffix in ("", ".md5", ".sha1")}
for name in base_files:
    for algorithm in ("md5", "sha1"):
        assert (M / (name + "." + algorithm)).read_text().strip() == hashlib.new(algorithm, (M / name).read_bytes()).hexdigest()
assert identity(S / "pgjdbc/build/libs/postgresql-42.7.12-kira.1-osgi.jar") == identity(jar), "Fresh native/published JAR mismatch"
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(pom).getroot()
gav = ":".join(root.findtext("m:" + key, namespaces=ns) for key in ("groupId", "artifactId", "version"))
assert gav == inputs["publication"]["gav"]
assert root.findtext("m:properties/m:kira.qualification", namespaces=ns) == inputs["publication"]["qualification"]
deps = [{key: item.findtext("m:" + key, namespaces=ns) for key in ("groupId", "artifactId", "version", "scope")}
        for item in root.findall("m:dependencies/m:dependency", ns)]
assert deps == [{"groupId": "org.checkerframework", "artifactId": "checker-qual", "version": "3.55.1", "scope": "runtime"}]
checkers = list((R / "pgjdbc-gradle").glob("caches/modules-2/files-2.1/org.checkerframework/checker-qual/3.55.1/*/checker-qual-3.55.1.jar"))
assert len(checkers) == 1 and identity(checkers[0])["sha256"] == inputs["checker"]["sha256"], "Resolved Checker identity differs"
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)) and names.count("org/postgresql/Driver.class") == 1
    assert archive.read("META-INF/services/java.sql.Driver") == b"org.postgresql.Driver\n"
    manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\n ", "")
    attrs = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    assert attrs["Automatic-Module-Name"] == attrs["Bundle-SymbolicName"] == "org.postgresql.jdbc"
    assert attrs["Multi-Release"] == "true"
    assert "org/postgresql/jdbc/KiraOwnedJdbcCut.class" in names
    assert "org/postgresql/core/v3/KiraOwnedParameterBridge.class" in names
    classes = {name: int.from_bytes(archive.read(name)[6:8], "big") for name in names if name.endswith(".class")}
    assert all(major <= 52 for name, major in classes.items() if not name.startswith("META-INF/versions/"))
    mr = {name: major for name, major in classes.items() if name.startswith("META-INF/versions/")}
    assert mr and all(name.startswith("META-INF/versions/11/") and major == 55 for name, major in mr.items())
    assert not any(name.startswith("com/ongres/") for name in names)
    assert any(name.startswith("org/postgresql/shaded/com/ongres/") for name in classes)
    assert "META-INF/LICENSE" in names
    for group in ("com.ongres.scram", "com.ongres.stringprep"):
        assert any(name.startswith("META-INF/licenses/" + group + "/") and not name.endswith("/") for name in names)
(E / "pgjdbc-publications.json").write_text(json.dumps({
    "files": published, "gav": gav, "source_commit": inputs["upstream"]["commit"],
    "fresh_osgi_jar_matches": True, "source_unchanged": True,
    "native_java8_mr11_provider_shading_licenses": True,
    "checker": {"gav": inputs["checker"]["gav"], **identity(checkers[0])},
    "qualification": inputs["publication"]["qualification"], "qualified": False,
}, indent=2, sort_keys=True) + "\n")
print("Fresh native JAR/POM, Java8/MR11, Checker dependency and unchanged source verified.")
PY
printf '0\n' > "$E/pgjdbc.exit"
