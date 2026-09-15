"""Gate09 receipts narrowed to the accepted artifact and one locked Backend17 graph."""
import collections
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
from pathlib import Path, PurePosixPath

control = Path(__file__).resolve().parent
inputs = json.loads(control.joinpath("inputs.json").read_text())
hosted = json.loads(control.joinpath("hosted.json").read_text())
root = Path(os.environ["ENGINE234_RUN_ROOT"]).resolve()
evidence = root / "evidence"
checkout = Path(os.environ["GITHUB_WORKSPACE"]) / "inputs/backend"
version = root.joinpath("version.txt").read_text().strip()
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
save = lambda name, data: evidence.joinpath(name).write_text(json.dumps(data, indent=2) + "\n")


def source():
    git = lambda *args: subprocess.check_output(
        ["git", "-C", str(checkout), *args], env=dict(os.environ, GIT_OPTIONAL_LOCKS="0")
    ).decode().strip()
    spec = hosted["source"]
    assert git("rev-parse", "HEAD") == spec["head"] and git("rev-parse", "HEAD^{tree}") == spec["tree"], "source identity"
    assert not git("status", "--porcelain=v1", "--untracked-files=no"), "tracked source/lock drift"
    assert all(p.startswith(("build/", ".gradle/", ".kotlin/")) for p in
               git("ls-files", "--others", "--exclude-standard").splitlines()), "untracked source drift"
    assert all(sha(checkout / p) == h for p, h in spec["protected_sha256"].items()), "reviewed five-path drift"
    return {**spec, "tracked_clean": True, "lock_sha256": sha(checkout / "gradle.lockfile")}


def repository():
    files = sorted(p for p in root.joinpath("maven").rglob("*") if p.is_file())
    assert len(files) == 420 and sum(p.stat().st_size for p in files) <= 128 * 1024**2, "repository bounds"
    assert all(not p.is_symlink() and p.resolve().is_relative_to(root / "maven") for p in files), "repository escape"
    return {str(p.relative_to(root / "maven")): {"sha256": sha(p), "bytes": p.stat().st_size} for p in files}


def candidate():
    spec = hosted["artifact"]
    run = json.loads(evidence.joinpath("producer-run.log").read_text())
    assert run["id"] == inputs["candidate"]["run"] and run["run_attempt"] == 1
    assert run["repository"] == {"full_name": hosted["repository"], "private": False}
    assert run["head_sha"] == spec["producer_carrier"] and run["head_branch"] == spec["producer_branch"]
    assert (run["status"], run["conclusion"], run["event"]) == ("completed", "success", "push")
    metadata = json.loads(evidence.joinpath("artifact-metadata.log").read_text())
    assert all(metadata[k] == spec[k] for k in ["id", "name", "size_in_bytes", "digest"]), "artifact identity"
    assert metadata["expired"] is False and metadata["workflow_run"]["id"] == run["id"]
    assert metadata["workflow_run"]["head_sha"] == spec["producer_carrier"]
    archive = root / "candidate.zip"
    assert archive.stat().st_size == spec["size_in_bytes"] and "sha256:" + sha(archive) == spec["digest"], "download bytes"
    with zipfile.ZipFile(archive) as zipped:
        members = zipped.infolist()
        assert len(members) <= 64 and len({m.filename for m in members}) == len(members)
        assert sum(m.file_size for m in members) <= 4 * 1024**2, "bounded accepted zip"
        manifest_bytes = zipped.read("publications.json")
        native_bytes = zipped.read("candidate-maven.tar.gz")
    assert hashlib.sha256(manifest_bytes).hexdigest() == inputs["candidate"]["publications_sha256"]
    assert len(native_bytes) == spec["native_archive_bytes"]
    assert hashlib.sha256(native_bytes).hexdigest() == inputs["candidate"]["archive_sha256"]
    manifest = json.loads(manifest_bytes)
    assert manifest["version"] == version == inputs["candidate"]["version"]
    assert manifest["producer"] == inputs["engine"]["head"] and len(manifest["components"]) == 15
    assert len(manifest["files"]) == 420 and not any(root.joinpath("maven").iterdir()), "one fresh extraction only"
    evidence.joinpath("publications.json").write_bytes(manifest_bytes)
    with tarfile.open(fileobj=io.BytesIO(native_bytes), mode="r:gz") as native:
        members = native.getmembers()
        expected = {"maven/" + p for p in manifest["files"]}
        assert len(members) == 420 and {m.name for m in members} == expected, "exact native members"
        for member in members:
            path = PurePosixPath(member.name)
            assert member.isfile() and not path.is_absolute() and ".." not in path.parts
            relative = str(path.relative_to("maven"))
            expected_file = manifest["files"][relative]
            assert member.size == expected_file["bytes"] and member.size <= 8 * 1024**2
            target = root / member.name
            assert not target.exists() and target.resolve().is_relative_to(root / "maven")
            target.parent.mkdir(parents=True, exist_ok=True)
            with native.extractfile(member) as stream:
                data = stream.read(member.size + 1)
            assert len(data) == member.size and hashlib.sha256(data).hexdigest() == expected_file["sha256"]
            target.write_bytes(data)
    assert repository() == manifest["files"], "candidate extraction readback"
    save("candidate-result.json", {
        "status": "PASS_ACCEPTED_ARTIFACT_READBACK", "artifact": spec, "version": version,
        "archive_sha256": inputs["candidate"]["archive_sha256"],
        "publications_sha256": inputs["candidate"]["publications_sha256"],
        "files_verified": 420, "extraction_count": 1, "producer_rebuilt": False, "registry_publication": False,
    })


def execution():
    assert evidence.joinpath("backend.exit").read_text().strip() == "0 0", "Gradle/deadline/capture failure"
    assert evidence.joinpath("test-lock-before.sha256").read_text().strip() == hosted["lock_sha256"]
    assert sha(checkout / "gradle.lockfile") == hosted["lock_sha256"], "native lock changed"
    graph = evidence.joinpath("test-task-graph.txt").read_text().splitlines()
    outcomes = [line.split("\t") for line in evidence.joinpath("test-task-outcomes.tsv").read_text().splitlines()]
    assert len(graph) == len(set(graph)) == len(outcomes) and {r[0] for r in outcomes} == set(graph), "task evidence mismatch"
    observed = dict(outcomes)
    assert all(observed.get(t) == "SUCCESS" for t in [":compileKotlin", ":compileTestKotlin", ":test"]), "unexecuted compiler/Test task"
    assert set(evidence.joinpath("backend-test-filter.txt").read_text().splitlines()) == set(inputs["backend_test_selectors"])
    manifest = json.loads(evidence.joinpath("publications.json").read_text())
    assert sha(evidence / "publications.json") == inputs["candidate"]["publications_sha256"]
    assert repository() == manifest["files"], "accepted repository drift"
    components = {c["gav"]: c for c in manifest["components"]}
    rows = [line.split("\t") for line in evidence.joinpath("test-resolved-artifacts.tsv").read_text().splitlines()]
    assert all(len(row) == 4 for row in rows)
    counts = dict(collections.Counter(r[0] for r in rows))
    assert counts == hosted["resolved_configurations"], "actual nine-configuration inventory"
    logged = {c: int(n) for c, n in re.findall(r"^ENGINE234_STRICT_RESOLVE (\S+) PASS files=(\d+)$",
                                            evidence.joinpath("backend.log").read_text(), re.M)}
    assert logged == counts, "real resolution PASS records"
    locks = {line.split("=", 1)[0]: set(line.split("=", 1)[1].split(","))
             for line in checkout.joinpath("gradle.lockfile").read_text().splitlines() if "=" in line and not line.startswith("#")}
    for config, gav, name, digest in rows:
        assert config in locks[gav], "resolved artifact outside native lock"
        if gav.startswith("me.manga.kira.source:"):
            component = components[gav]
            assert digest == component["sha256"] and gav.endswith(":" + version)
            base = root / "maven" / Path(component["binary"]).parent
            module = gav.split(":")[1]
            model = json.loads((base / (module + "-" + version + ".module")).read_text())
            # KMP root metadata may use logical names different from the physical Maven filename.
            matches = {(base / f["url"]).resolve() for v in model["variants"] for f in v.get("files", [])
                       if f["name"] == name and f["sha256"] == digest}
            assert matches == {(root / "maven" / component["binary"]).resolve()}, "native metadata file binding"
    runtime = [line.split("\t") for line in evidence.joinpath("test-runtime.tsv").read_text().splitlines()]
    jars = {Path(c["binary"]).name: c["sha256"] for c in manifest["components"] if c["gav"].split(":")[1].endswith("-jvm")}
    parser = {"ksoup-jvm-0.2.5.jar": inputs["parser"]["sha256"]}
    for label in [":test", "runtimeClasspath", "productionRuntimeClasspath", "testRuntimeClasspath"]:
        actual = [r for r in runtime if r[:2] == ["backend", label]]
        wanted = jars if label in [":test", "testRuntimeClasspath"] else {n: h for n, h in jars.items() if not n.startswith("source-testkit-")}
        assert {r[2]: r[3] for r in actual} == wanted | parser and all(r[3] == (wanted | parser)[r[2]] for r in actual), "actual source/parser Test files"
        if label == ":test":
            assert len(actual) == 4, "one actual Test classpath observation"
    for config in ["runtimeClasspath", "productionRuntimeClasspath", "testRuntimeClasspath"]:
        for module, version_required in [("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm", "1.9.0"),
                                         ("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm", "1.9.0"),
                                         ("org.jetbrains.kotlin:kotlin-stdlib", "2.1.21"), ("org.jetbrains.kotlin:kotlin-reflect", "2.1.21")]:
            assert [r[1] for r in rows if r[0] == config and r[1].rsplit(":", 1)[0] == module] == [module + ":" + version_required]
    assert evidence.joinpath("docker-events.exit").read_text().strip() == "0 0", "Docker event capture"
    events = [line.split() for line in evidence.joinpath("docker-events.log").read_text().splitlines()]
    started = {r[2].removeprefix("docker.io/").removeprefix("library/") for r in events if len(r) >= 3 and r[0] == "start"}
    assert set(hosted["images"]).issubset(started), "actual pinned PostgreSQL start required"
    assert not any(i.startswith("redis:") for i in started), "no Redis fixture admitted"
    return {"configurations": counts, "artifact_rows": len(rows), "runtime_rows": len(runtime), "images_started": sorted(started)}


phase = sys.argv[1]
if phase == "bind":
    assert hosted["images"] == ["postgres:17.6-alpine"] and sum(inputs["backend_tests"].values()) == 17
    assert version == inputs["candidate"]["version"] and hosted["lock_sha256"] == hosted["source"]["protected_sha256"]["gradle.lockfile"]
    for name in ["engine234.init.gradle", "inputs.json", "backend-test-classes.txt"]:
        assert sha(control / name) == hosted["source"]["protected_sha256"]["ci/engine234-gate/" + name], "frozen consumer control drift"
    save("sources-before.json", source())
    assert not any((checkout / name).exists() for name in ["build", ".gradle", ".kotlin"]), "fresh checkout required"
    evidence.joinpath("inputs.json").write_bytes(control.joinpath("inputs.json").read_bytes())
    evidence.joinpath("hosted.json").write_bytes(control.joinpath("hosted.json").read_bytes())
    carrier = control.parents[1]
    assert subprocess.check_output(["git", "-C", str(carrier), "rev-parse", "HEAD"]).decode().strip() == os.environ["GITHUB_SHA"]
    files = [p for p in control.iterdir() if p.is_file()] + [carrier / ".github/workflows/engine234-backend17-gate.yml"]
    save("control-sha256.json", {str(p.relative_to(carrier)): sha(p) for p in files})
    save("carrier.json", {"sha": os.environ["GITHUB_SHA"], "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"]})
    sys.exit(0)
if phase == "candidate":
    candidate()
    sys.exit(0)
if phase == "seal":
    errors = []
    try:
        carrier = control.parents[1]
        recorded = json.loads(evidence.joinpath("control-sha256.json").read_text())
        assert all(sha(carrier / name) == digest for name, digest in recorded.items())
    except Exception:
        errors.append("carrier control drift or missing binding")
    for filename, status in [("backend-result.json", "PASS_REQUIRES_PRIMARY_REVIEW"), ("candidate-result.json", "PASS_ACCEPTED_ARTIFACT_READBACK")]:
        try:
            assert json.loads(evidence.joinpath(filename).read_text())["status"] == status
        except Exception:
            errors.append(filename + " missing, failed or incomplete")
    for stage in ["backend", "docker", "final"]:
        try:
            assert evidence.joinpath(stage + "-cleanup.exit").read_text().strip() == "0"
        except Exception:
            errors.append(stage + " cleanup missing, failed or forced")
    for path in evidence.glob("*.capture.json"):
        if json.loads(path.read_text())["truncated"]:
            errors.append(path.name + " overflow")
    for name in ["backend-gradle", "backend-cache", "tmp", "home", "maven", "candidate.zip"]:
        if (root / name).exists() or (root / name).is_symlink():
            errors.append(name + " owned residue")
    if checkout.exists() or checkout.is_symlink():
        errors.append("owned source checkout residue")
    save("gate-result.json", {
        "status": "PASS_REQUIRES_PRIMARY_REVIEW" if not errors else "FAIL_OR_INCOMPLETE", "errors": errors,
        "backend": hosted["source"]["head"], "lock_sha256": hosted["lock_sha256"], "version": version,
        "scope": "Backend validator16 plus unchanged FullBundledParityIT1 only",
        "producer_replayed": False, "lock_updates": False, "registry_release_publication": False, "app_acceptance": "NOT_RUN",
    })
    files = sorted(p for p in evidence.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
    assert all(not p.is_symlink() and p.resolve().is_relative_to(evidence) for p in files), "evidence escape"
    assert len(files) <= 128 and sum(p.stat().st_size for p in files) <= 64 * 1024**2, "evidence bounds"
    evidence.joinpath("SHA256SUMS").write_text("".join(f"{sha(p)}  {p.relative_to(evidence)}\n" for p in files))
    print("Consumer17 receipts sealed; " + ("primary review required" if not errors else "gate failed or incomplete"))
    sys.exit(1 if errors else 0)
assert phase == "backend"
errors, counts, cases, copied = [], collections.Counter(), collections.defaultdict(list), 0
try:
    save("backend-source-after.json", source())
except Exception as failure:
    errors.append("source: " + str(failure))
for path in sorted(checkout.joinpath("build/test-results/test").glob("TEST-*.xml")):
    try:
        copied += path.stat().st_size
        assert not path.is_symlink() and path.stat().st_size <= 4 * 1024**2 and copied <= 32 * 1024**2, "XML bounds"
        assert path.stat().st_mtime >= int(evidence.joinpath("backend.started").read_text()), "stale XML"
        target = evidence / "xml/backend" / path.relative_to(checkout)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        suite = ET.parse(path).getroot()
        assert all(int(suite.attrib.get(k, 0)) == 0 for k in ["failures", "errors", "skipped"]), "XML failure/error/skip totals"
        for case in suite.iter("testcase"):
            cls = case.attrib.get("classname", "")
            counts[cls] += 1
            cases[cls].append(case.attrib.get("name", ""))
            if any(case.find(tag) is not None for tag in ["failure", "error", "skipped"]):
                errors.append("failed/error/skipped: " + cls + " " + case.attrib.get("name", ""))
    except Exception as failure:
        errors.append(path.name + ": " + str(failure))
if dict(counts) != inputs["backend_tests"]:
    errors.append("required exact 16+1 invocation counts mismatch")
expected_cases = inputs["backend_cases"]
if set(cases) != set(expected_cases) or any(collections.Counter(cases[cls]) != collections.Counter(names) for cls, names in expected_cases.items()):
    errors.append("required exact Backend method invocation mismatch")
observations = {}
try:
    observations = execution()
except Exception as failure:
    errors.append("execution/artifacts: " + str(failure))
save("backend-result.json", {
    "status": "PASS_REQUIRES_PRIMARY_REVIEW" if not errors else "FAIL_OR_INCOMPLETE",
    "observed_tests": sum(counts.values()), "classes": dict(counts), "cases": dict(cases), "errors": errors, **observations,
})
sys.exit(1 if errors else 0)
