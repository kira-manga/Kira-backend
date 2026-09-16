"""Bound public product sources, fresh native publications and the exact focused reports."""
import collections
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

C = Path(__file__).resolve().parent
R = Path(os.environ["AUTH30_RUN_ROOT"]).resolve()
E = R / "evidence"
W = Path(os.environ["GITHUB_WORKSPACE"]).resolve()
B = W / "backend"
ENGINE = W / "auth30-inputs/engine"
REPO = R / "maven"
SELECTION = json.loads((C / "selection.json").read_text())
ENGINE_INPUTS = json.loads((C / "engine-inputs.json").read_text())
BACKEND_INPUTS = json.loads((C / "backend-source.json").read_text())["files"]


def identity(path):
    assert path.is_file() and not path.is_symlink(), f"Missing/unsafe file: {path.name}"
    data = path.read_bytes()
    return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}


def write(name, value):
    (E / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def git(path, *arguments):
    return subprocess.check_output(["git", "-C", str(path), *arguments], text=True).strip()


def backend_source():
    for path, expected in BACKEND_INPUTS.items():
        assert identity(B / path) == expected, f"Backend input changed: {path}"
    for base in ("src", "config", "gradle", "vendor/pgjdbc-owned-cut"):
        actual = {str(p.relative_to(B)) for p in (B / base).rglob("*") if p.is_file()}
        expected = {p for p in BACKEND_INPUTS if p.startswith(base + "/")}
        assert actual == expected, f"Backend source inventory changed: {base}"


def engine_source():
    assert git(ENGINE, "rev-parse", "HEAD") == ENGINE_INPUTS["commit"]
    assert git(ENGINE, "rev-parse", "HEAD^{tree}") == ENGINE_INPUTS["tree"]
    assert git(ENGINE, "diff", "--name-only", "HEAD") == "", "Supplier source changed"
    for path, expected in ENGINE_INPUTS["protected_sha256"].items():
        assert identity(ENGINE / path)["sha256"] == expected, f"Engine input changed: {path}"


def outcome(stage):
    return dict(line.split("\t", 1) for line in (E / f"{stage}-task-outcomes.tsv").read_text().splitlines())


def exit_zero(name):
    assert (E / name).read_text().split() in (["0"], ["0", "0"]), f"Nonzero gate/cleanup: {name}"


def bind():
    assert git(B, "rev-parse", "HEAD") == os.environ["GITHUB_SHA"]
    backend_source()
    engine_source()
    write("source-binding.json", {
        "carrier_commit": os.environ["GITHUB_SHA"],
        "backend_input_manifest": identity(C / "backend-source.json"),
        "backend_product_files": len(BACKEND_INPUTS),
        "engine_commit": ENGINE_INPUTS["commit"], "engine_tree": ENGINE_INPUTS["tree"],
        "engine_wrapper_provenance_limitation": ENGINE_INPUTS["wrapper_provenance_limitation"],
        "selection": identity(C / "selection.json"),
        "scope": "Verification-only effective product snapshot; no deployment or release qualification.",
    })


def engine():
    exit_zero("engine.exit")
    engine_source()
    tasks = outcome("engine")
    for module in ENGINE_INPUTS["modules"]:
        for task in ("compileKotlinJvm", "publishKotlinMultiplatformPublicationToW01OnlyRepository", "publishJvmPublicationToW01OnlyRepository"):
            assert tasks[f":{module}:{task}"] == "SUCCESS", "Native supplier task did not run successfully"
    source_root = REPO / "me/manga/kira/source"
    expected_modules = {"source-contract", "source-engine", "source-contract-jvm", "source-engine-jvm"}
    assert {p.name for p in source_root.iterdir() if p.is_dir()} == expected_modules
    for module in expected_modules:
        base = source_root / module / "0.1.0"
        for suffix in ("jar", "pom", "module"):
            assert (base / f"{module}-0.1.0.{suffix}").is_file(), "Incomplete native publication"
        metadata = json.loads((base / f"{module}-0.1.0.module").read_text())
        assert metadata["component"]["group"] == "me.manga.kira.source"
        assert metadata["component"]["version"] == "0.1.0"
        for variant in metadata["variants"]:
            for entry in variant.get("files", []):
                assert Path(entry["url"]).name == entry["url"], "Unexpected publication file URL"
                actual = identity(base / entry["url"])
                assert actual == {"sha256": entry["sha256"], "bytes": entry["size"]}, "Native metadata content mismatch"
    # Authenticate fresh JVM source archives against the exact supplier checkout, not retained jars.
    for module in ENGINE_INPUTS["modules"]:
        path = source_root / f"{module}-jvm/0.1.0/{module}-jvm-0.1.0-sources.jar"
        with zipfile.ZipFile(path) as jar:
            actual = {}
            for entry in jar.namelist():
                if entry.endswith(".kt"):
                    source_set, relative = entry.split("/", 1)
                    source = ENGINE / module / "src" / source_set / "kotlin" / relative
                    assert source.read_bytes() == jar.read(entry), "Source archive differs from supplier commit"
                    actual[str(source.relative_to(ENGINE))] = hashlib.sha256(jar.read(entry)).hexdigest()
            expected = {str(p.relative_to(ENGINE)) for p in (ENGINE / module / "src/commonMain/kotlin").rglob("*.kt")}
            assert set(actual) == expected, "Incomplete JVM source archive"
    files = {str(p.relative_to(REPO)): identity(p) for p in sorted(source_root.rglob("*")) if p.is_file()}
    write("engine-publications.json", {"files": files, "version": "0.1.0", "source_commit": ENGINE_INPUTS["commit"],
                                       "qualified": False, "origin": "Fresh native root/JVM file publications; no retained binaries."})


def preserve_backend_reports():
    marker = E / "backend-reports-preserved.json"
    marker.unlink(missing_ok=True)
    reports = sorted((B / "build/test-results/test").glob("TEST-*.xml"))
    lint_reports = [B / f"build/reports/ktlint/ktlint{kind}SourceSetCheck/ktlint{kind}SourceSetCheck.txt" for kind in ("Main", "Test")]
    detekt = B / "build/reports/detekt/detekt.xml"
    target = E / "tests"
    assert not target.is_symlink(), "Unsafe report destination"
    target.mkdir(exist_ok=True)
    preserved = {}
    for report in reports + lint_reports + [detekt]:
        if not report.exists() and not report.is_symlink():
            continue  # Earlier compilation/static failure may mean a later report was never produced.
        assert report.is_file() and not report.is_symlink() and report.stat().st_size <= 5 * 1024 * 1024, "Unsafe/oversized raw report"
        pin = identity(report)
        destination = target / report.name
        assert not destination.is_symlink(), "Unsafe report destination"
        used = sum(p.stat().st_size for p in E.rglob("*") if p.is_file())
        replaced = destination.stat().st_size if destination.exists() else 0
        assert used - replaced + pin["bytes"] <= 48 * 1024 * 1024, "Evidence size bound exceeded before report copy"
        shutil.copyfile(report, destination)
        assert identity(destination) == pin, "Raw report copy changed"
        preserved[report.name] = pin
    payload = json.dumps({"all_existing_reports_preserved": True, "files": preserved}, indent=2, sort_keys=True) + "\n"
    used = sum(p.stat().st_size for p in E.rglob("*") if p.is_file())
    assert used + len(payload.encode("utf-8")) <= 48 * 1024 * 1024, "Evidence size bound exceeded before preservation marker"
    marker.write_text(payload)
    return ([target / p.name for p in reports], [target / p.name for p in lint_reports], target / detekt.name)


def backend():
    # Preserve bounded raw diagnostics even when Gradle or a later result assertion failed.
    reports, lint_reports, detekt = preserve_backend_reports()
    exit_zero("backend.exit")
    backend_source()
    tasks = outcome("backend")
    for task in ("compileKotlin", "compileTestKotlin", "test", "detekt", "runKtlintCheckOverMainSourceSet", "runKtlintCheckOverTestSourceSet"):
        assert tasks[f":{task}"] == "SUCCESS", f"Required task did not freshly succeed: {task}"
    expected = collections.Counter((x["class"], x["method"]) for x in SELECTION["runtime_methods"])
    observed = collections.Counter()
    assert len(reports) == len(SELECTION["class_counts"]), "Missing or unexpected test class reports"
    for report in reports:
        assert report.stat().st_size <= 5 * 1024 * 1024, "Oversized focused test report"
        suite = ET.parse(report).getroot()
        assert all(int(suite.get(k, "0")) == 0 for k in ("failures", "errors", "skipped")), "Required test did not pass"
        for case in suite.findall("testcase"):
            assert not any(case.find(k) is not None for k in ("failure", "error", "skipped")), "Failed/skipped focused case"
            observed[(case.attrib["classname"], case.attrib["name"].removesuffix("()"))] += 1
    assert observed == expected and sum(observed.values()) == 33, "Exact33 case multiset mismatch"
    assert all(p.read_bytes() == b"" for p in lint_reports), "Ktlint findings or report absent"
    assert not ET.parse(detekt).getroot().findall(".//error"), "Detekt findings"
    for kind in ("detekt", "main", "test"):
        scope = json.loads((E / f"static-{kind}-scope.json").read_text())
        expected_paths = [p for p in SELECTION["static_paths"] if kind == "detekt" or p.startswith(f"src/{kind}/")]
        assert scope["files"] == expected_paths and scope["ignore_failures"] is False
    runtime = json.loads((E / "normal-runtime.json").read_text())
    assert runtime["test_classpath_mutated"] is False and runtime["local_run_environment_present"] is False
    assert runtime["selectors"] == SELECTION["selectors"] and len(runtime["actual_driver_providers"]) == 1
    assert set(runtime["configurations"]) == {"productionRuntimeClasspath", "runtimeClasspath", "testRuntimeClasspath"}
    for supplier in ("engine", "pgjdbc"):
        for path, pin in json.loads((E / f"{supplier}-publications.json").read_text())["files"].items():
            assert identity(REPO / path) == {"sha256": pin["sha256"], "bytes": pin["bytes"]}, "Supplier output mutated"
    write("focused-result.json", {"status": "PASS_NORMAL33_STATIC11_WHOLE_SOURCE_COMPILE", "tests": 33,
                                  "class_counts": dict(collections.Counter(c for c, _ in observed.elements())),
                                  "static_files": 11, "source_inputs_unchanged": True,
                                  "deployment_or_release_qualified": False})


def seal():
    checks = {}
    for name in ("engine.exit", "pgjdbc.exit", "backend.exit", "engine-cleanup.exit", "pgjdbc-cleanup.exit",
                 "backend-cleanup.exit", "docker-cleanup.exit", "final-cleanup.exit"):
        try:
            exit_zero(name)
            checks[name] = True
        except (AssertionError, OSError):
            checks[name] = False
    checks["focused_result"] = (E / "focused-result.json").is_file()
    for path in E.rglob("*.capture.json"):
        checks[path.name] = json.loads(path.read_text())["truncated"] is False
    files = {}
    for path in sorted(E.rglob("*")):
        assert not path.is_symlink(), "Evidence must not contain symlinks"
        if path.is_file() and path.name != "result.json":
            assert path.suffix not in {".jar", ".zip", ".gz", ".tar", ".class"}, "Binary supplier outputs must never be uploaded"
            files[str(path.relative_to(E))] = identity(path)
    assert sum(p["bytes"] for p in files.values()) <= 48 * 1024 * 1024, "Evidence size bound exceeded"
    passed = all(checks.values())
    write("result.json", {"status": "PASS_FOCUSED_VERIFICATION_ONLY" if passed else "FAILED_OR_INCOMPLETE",
                          "checks": checks, "files": files,
                          "scope": "Auth30 only. No installed migration reconciliation, Redis cutover, deployment, restore, W03 or App29 closure."})
    assert passed, "Gate incomplete or cleanup unverified"


phase = sys.argv[1]
assert phase in {"bind", "engine", "backend", "seal"}
try:
    globals()[phase]()
except Exception as failure:
    write(phase + "-proof-failure.json", {"type": type(failure).__name__, "reason": str(failure)[:500]})
    raise
