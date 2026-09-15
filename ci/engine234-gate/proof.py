"""Small public-input, native-artifact and actual-JUnit receipts for this one gate."""
import collections
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

control = Path(__file__).resolve().parent
inputs = json.loads(control.joinpath("inputs.json").read_text())
root = Path(os.environ["ENGINE234_RUN_ROOT"]).resolve()
evidence = root / "evidence"
version = root.joinpath("version.txt").read_text().strip()
modules = ["source-contract", "source-engine", "source-testkit"]
group = "me.manga.kira.source"
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
save = lambda name, data: evidence.joinpath(name).write_text(json.dumps(data, indent=2) + "\n")


def source(role):
    path = Path(os.environ["GITHUB_WORKSPACE"]) / "inputs" / role
    git = lambda *args: subprocess.check_output(["git", "-C", str(path), *args], env=dict(os.environ, GIT_OPTIONAL_LOCKS="0")).decode().strip()
    spec = inputs[role]
    assert git("rev-parse", "HEAD") == spec["head"] and git("rev-parse", "HEAD^{tree}") == spec["tree"], "source identity"
    assert not git("status", "--porcelain=v1", "--untracked-files=no"), "tracked source drift"
    generated = [prefix + name + "/" for prefix in [""] + [m + "/" for m in modules] for name in ["build", ".gradle", ".kotlin"]]
    assert all(any(p.startswith(a) for a in generated) for p in git("ls-files", "--others", "--exclude-standard").splitlines()), "untracked source drift"
    assert all(sha(path / p) == h for p, h in spec["protected_sha256"].items()), "protected build/lock drift"
    return path, {"head": spec["head"], "tree": spec["tree"], "protected_sha256": spec["protected_sha256"], "tracked_clean": True}


def repository():
    files = sorted(p for p in root.joinpath("maven").rglob("*") if p.is_file())
    assert len(files) <= 512 and sum(p.stat().st_size for p in files) <= 128 * 1024**2, "repository bounds"
    assert all(not p.is_symlink() and p.resolve().is_relative_to(root / "maven") for p in files), "repository escape"
    return {str(p.relative_to(root / "maven")): {"sha256": sha(p), "bytes": p.stat().st_size} for p in files}


def check_versions(node):
    if isinstance(node, dict):
        if node.get("group") == group and "version" in node:
            value = node["version"]
            assert value == version or isinstance(value, dict) and value and all(v == version for v in value.values()), "source metadata version"
        for child in node.values():
            check_versions(child)
    elif isinstance(node, list):
        for child in node:
            check_versions(child)


def identity_value(value, *, path=False, rejected=False):
    grammar = r"(?:\.\./){0,2}[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*" if path else r"[A-Za-z0-9_.-]+"
    if not rejected and isinstance(value, str) and len(value) <= (512 if path else 160) and re.fullmatch(grammar, value):
        return value
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
    return {"rejected_type": type(value).__name__, "json_bytes": len(encoded), "sha256": hashlib.sha256(encoded).hexdigest()}


def check_component(metadata, module, jvm):
    component = metadata.get("component") if isinstance(metadata, dict) else None
    assert isinstance(component, dict) and all(component.get(k) == v for k, v in
        {"group": group, "module": module, "version": version}.items()), "native module identity"
    backlink = f"../../{module}/{version}/{module}-{version}.module"
    assert (component.get("url") == backlink if jvm else "url" not in component), "native module backlink"


def publications():
    files = repository()
    model = set(line.split("\t")[1] for line in evidence.joinpath("publications-model.tsv").read_text().splitlines())
    coordinates = {f"{group}:{m}{suffix}:{version}" for m in modules for suffix in ["", "-jvm"]}
    assert model == coordinates, "native publication model"
    metadata_by_name, diagnostic = {}, []
    for module in modules:
        for suffix in ["", "-jvm"]:
            name = module + suffix
            path = root / "maven/me/manga/kira/source" / name / version / (name + "-" + version + ".module")
            relative = str(path.relative_to(root / "maven"))
            note = {"file": identity_value(relative, path=True), **files.get(relative, {})}
            try:
                metadata = json.loads(path.read_text())
            except (OSError, ValueError, RecursionError) as error:
                metadata, note["read_error"] = None, type(error).__name__
            else:
                component = metadata.get("component") if isinstance(metadata, dict) else None
                note.update(metadata_type=type(metadata).__name__, component_present=isinstance(metadata, dict) and "component" in metadata,
                    component={k: identity_value(component[k], path=k == "url") if k in component else {"missing": True}
                               for k in ["group", "module", "version", "url"]} if isinstance(component, dict)
                              else identity_value(component, rejected=True))
            metadata_by_name[name] = metadata
            diagnostic.append(note)
    # Preserve all six bounded identities before any identity refusal; never publish raw rejected values.
    assert len(diagnostic) == 6 and len((json.dumps(diagnostic, indent=2) + "\n").encode()) <= 16384, "module diagnostic bounds"
    save("module-components.json", diagnostic)
    for module in modules:
        for suffix in ["", "-jvm"]:
            name = module + suffix
            prefix = root / "maven/me/manga/kira/source" / name / version / (name + "-" + version)
            pom = ET.parse(str(prefix) + ".pom").getroot()
            assert [pom.findtext("{*}" + tag) for tag in ["groupId", "artifactId", "version"]] == [group, name, version], "native POM"
            for dependency in pom.findall(".//{*}dependency"):
                if dependency.findtext("{*}groupId") == group:
                    assert dependency.findtext("{*}version") == version, "native POM dependency"
            metadata = metadata_by_name[name]
            check_component(metadata, module, suffix == "-jvm")
            check_versions(metadata)
            assert Path(str(prefix) + ".jar").is_file(), "native root/JVM jar"
    outcomes = dict(line.split("\t", 1) for line in evidence.joinpath("neutral-task-outcomes.tsv").read_text().splitlines())
    for module in modules:
        for task in ["jvmTest", "publishJvmPublicationToEngine234CandidateRepository", "publishKotlinMultiplatformPublicationToEngine234CandidateRepository"]:
            assert outcomes.get(f":{module}:{task}") == "SUCCESS", "unexecuted JVM gate/publication"
    save("publications.json", {"producer": inputs["engine"]["head"], "version": version, "files": files, "native_model": sorted(model)})


phase = sys.argv[1]
if phase == "bind":
    save("inputs.json", inputs)
    save("sources-before.json", {role: source(role)[1] for role in ["engine", "backend"]})
    carrier = control.parents[1]
    assert subprocess.check_output(["git", "-C", str(carrier), "rev-parse", "HEAD"]).decode().strip() == os.environ["GITHUB_SHA"], "carrier identity"
    control_files = [p for p in control.iterdir() if p.is_file()] + [carrier / ".github/workflows/engine234-backend14-gate.yml"]
    save("control-sha256.json", {str(p.relative_to(carrier)): sha(p) for p in control_files})
    save("carrier.json", {"sha": os.environ["GITHUB_SHA"], "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"]})
    sys.exit(0)
if phase == "seal":
    errors = []
    for stage in ["engine", "backend"]:
        try:
            assert json.loads(evidence.joinpath(stage + "-result.json").read_text())["status"] == "PASS_REQUIRES_PRIMARY_REVIEW"
        except Exception:
            errors.append(stage + " gate missing, failed or incomplete")
    for stage in ["engine", "backend", "docker", "final"]:
        try:
            assert evidence.joinpath(stage + "-cleanup.exit").read_text().strip() == "0"
        except Exception:
            errors.append(stage + " cleanup missing, failed or forced")
    for path in evidence.glob("*.capture.json"):
        if json.loads(path.read_text())["truncated"]:
            errors.append(path.name + " overflow")
    save("gate-result.json", {
        "status": "PASS_REQUIRES_PRIMARY_REVIEW" if not errors else "FAIL_OR_INCOMPLETE", "errors": errors,
        "engine": inputs["engine"]["head"], "backend": inputs["backend"]["head"], "version": version,
        "app_acceptance": "HELD; Backend does not establish App shared-refresh/no-success-stamp acceptance",
        "platform_acceptance": "NOT_RUN; empty native metadata prerequisites are not Apple compilation or qualification",
    })
    files = sorted(p for p in evidence.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
    assert all(not p.is_symlink() and p.resolve().is_relative_to(evidence) for p in files), "evidence escape"
    assert len(files) <= 512 and sum(p.stat().st_size for p in files) <= 100 * 1024**2, "evidence bounds"
    evidence.joinpath("SHA256SUMS").write_text("".join(f"{sha(p)}  {p.relative_to(evidence)}\n" for p in files))
    print("Gate receipts sealed; " + ("primary review required" if not errors else "gate failed or incomplete"))
    sys.exit(1 if errors else 0)
assert phase in ["engine", "backend"]
errors, counts, cases, copied = [], collections.Counter(), collections.defaultdict(list), 0
checkout = Path(os.environ["GITHUB_WORKSPACE"]) / "inputs" / phase
try:
    save(phase + "-source-after.json", source(phase)[1])
except Exception as failure:
    errors.append("source: " + str(failure))
paths = [checkout / m / "build/test-results/jvmTest" for m in modules] if phase == "engine" else [checkout / "build/test-results/test"]
for directory in paths:
    for path in sorted(directory.glob("TEST-*.xml")):
        try:
            copied += path.stat().st_size
            assert not path.is_symlink() and path.stat().st_size <= 4 * 1024**2 and copied <= 32 * 1024**2, "XML bounds"
            assert path.stat().st_mtime >= int(evidence.joinpath(phase + ".started").read_text()), "stale XML"
            target = evidence / "xml" / phase / path.relative_to(checkout)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            for case in ET.parse(path).getroot().iter("testcase"):
                cls = case.attrib.get("classname", "")
                counts[cls] += 1
                cases[cls].append(case.attrib.get("name", ""))
                if any(case.find(tag) is not None for tag in ["failure", "error", "skipped"]):
                    errors.append("failed/error/skipped: " + cls + " " + case.attrib.get("name", ""))
        except Exception as failure:
            errors.append(path.name + ": " + str(failure))
expected = inputs[phase + "_tests"]
if set(counts) != set(expected) or any(counts[c] < 1 or n is not None and counts[c] != n for c, n in expected.items()):
    errors.append("required class/method invocation mismatch")
if phase == "backend":
    expected_cases = inputs["backend_cases"]
    if set(cases) != set(expected_cases) or any(collections.Counter(cases[cls]) != collections.Counter(names) for cls, names in expected_cases.items()):
        errors.append("required exact Backend method invocation mismatch")
try:
    assert evidence.joinpath(phase + ".exit").read_text().strip() == "0 0", "Gradle/deadline/capture failure"
    if phase == "engine":
        publications()
    else:
        assert repository() == json.loads(evidence.joinpath("publications.json").read_text())["files"], "repository drift"
        rows = [line.split("\t") for line in evidence.joinpath("runtime.tsv").read_text().splitlines()]
        expected_jars = {m + "-jvm-" + version + ".jar" for m in modules}
        assert {r[2] for r in rows if r[:2] == ["backend", ":test"] and r[2].startswith("source-")} == expected_jars, "actual Test jars"
        parser = [r for r in rows if r[2] == "ksoup-jvm-0.2.5.jar"]
        assert {r[0] for r in parser} == {"neutral", "backend"} and len({r[3] for r in parser}) == 1, "actual Ksoup binding"
        assert evidence.joinpath("docker-events.exit").read_text().strip() == "0 0", "Docker event capture"
        events = [line.split() for line in evidence.joinpath("docker-events.log").read_text().splitlines()]
        started = {r[2].removeprefix("docker.io/").removeprefix("library/") for r in events if len(r) >= 3 and r[0] == "start"}
        assert set(inputs["images"]).issubset(started), "actual pinned PostgreSQL/Redis container starts required"
except Exception as failure:
    errors.append("execution/artifacts: " + str(failure))
save(phase + "-result.json", {"status": "PASS_REQUIRES_PRIMARY_REVIEW" if not errors else "FAIL_OR_INCOMPLETE", "observed_tests": sum(counts.values()), "classes": dict(counts), "cases": dict(cases), "errors": errors})
sys.exit(1 if errors else 0)
