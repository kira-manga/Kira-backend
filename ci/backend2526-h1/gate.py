#!/usr/bin/env python3
"""Fixed hosted-only B25/26 diagnostic gate. No release/production authorization.

The unbound preparation cannot run. Only the primary may bind its independently
reviewed carrier. Commands, images, paths and the H2 entry point are not inputs.
"""
import hashlib
import importlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tarfile
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CONTROL = ROOT / "ci/backend2526-h1"
WORKFLOW = ".github/workflows/backend2526-image-gate.yml"
BRANCH = "remediation/backend2526-image-gate-20260915-05"
OWNER_LABEL = "io.kira.backend2526.owner"
STAGE_LABEL = "io.kira.backend2526.stage"
IMAGE_HELPER = "scripts/ci/image_release.py"
IMAGE_HELPER_SHA = "e677d40f0e391ddaea0342e2da9915a1448164204e0cea7bd645172f750a3fa6"
RELEASE_HELPER_SHA = "7ad7d24115b63d763053e008196e13ebc4af89f6baeafbf08fb6030dbc852a02"
PG = "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94"
BASE_CONTROLS = {WORKFLOW, *("ci/backend2526-h1/" + p for p in (
    "gate.py", "docker-owner.py", "admission.json", "source-pins.json"))}
H2_CONTROLS = {"ci/backend2526-h1/h2-recovery.py", "ci/backend2526-h1/h2-request.json"}
JARS = {
    "BOOT-INF/lib/source-contract-jvm-0.1.0.jar": (139562, "817555990e1822c2ff33c45ea66af5af92b406a239d53bc120b211b5be83017d"),
    "BOOT-INF/lib/source-engine-jvm-0.1.0.jar": (109366, "764233f4a177970d6473559445221b02ff8a93f7d10ab3a42a2a20eceedb6460"),
}
MAX_RECORD = 16 * 1024
MAX_JAR = 128 * 1024 * 1024
H1_SECONDS, H2_SECONDS, FINAL_SECONDS = 1800, 600, 180
TOTAL_SECONDS = 2700
CLEANUP_END = None
RUN_END = None


class GateRefused(Exception):
    pass


def need(ok, message="gate condition failed"):
    if not ok:
        raise GateRefused(message)


def raw(path, maximum):
    need(RUN_END is None or time.monotonic() < RUN_END, "fixed work deadline exhausted")
    info = path.lstat()
    need(stat.S_ISREG(info.st_mode) and info.st_size <= maximum, "invalid bounded file")
    with path.open("rb") as stream:
        result = stream.read(maximum + 1)
    need(len(result) <= maximum, "file bound exceeded")
    return result


def digest(value):
    return hashlib.sha256(value).hexdigest()


def read_json(path, maximum=MAX_RECORD):
    def unique(items):
        result = {}
        for key, value in items:
            need(key not in result, "duplicate JSON field")
            result[key] = value
        return result
    return json.loads(raw(path, maximum), object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(GateRefused("nonfinite JSON")))


def write_json(path, value, maximum=MAX_RECORD):
    data = (json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()
    need(len(data) <= maximum, "record bound exceeded")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write(data)


def load_helpers():
    # Check the two reviewed helpers before any product import/execution.
    need(digest(raw(ROOT / IMAGE_HELPER, 128 * 1024)) == IMAGE_HELPER_SHA, "image helper drift")
    need(digest(raw(ROOT / "scripts/ci/release_policy.py", 128 * 1024)) == RELEASE_HELPER_SHA,
         "release helper drift")
    sys.path.insert(0, str(ROOT / "scripts/ci"))
    return importlib.import_module("image_release"), importlib.import_module("release_policy")


image, release = load_helpers()


def git(*args):
    return image.command(["/usr/bin/git", "--no-optional-locks", "--no-replace-objects", "-C", str(ROOT),
                          "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null", *args],
                         seconds=bounded_seconds(15), maximum=image.MAX_CONFIG)


def admission():
    record = read_json(CONTROL / "admission.json")
    need(record.get("schema") == "backend2526-admission-v1", "admission schema")
    need(record.get("primary_admission") in ("H1_ONLY", "H1_H2"), "primary admission UNBOUND")
    image.hex_value(record.get("independent_review_sha256"))
    image.hex_value(record.get("source_sha"), 40)
    image.hex_value(record.get("source_tree"), 40)
    pins_raw = raw(CONTROL / "source-pins.json", 256 * 1024)
    need(digest(pins_raw) == record.get("source_pins_sha256"), "source manifest drift")
    pins = read_json(CONTROL / "source-pins.json", 256 * 1024)
    need(pins.get("schema") == "backend2526-source-pins-v1" and len(pins.get("files", [])) == 476,
         "source manifest shape")
    expected_controls = BASE_CONTROLS | (H2_CONTROLS if record["primary_admission"] == "H1_H2" else set())
    # admission.json is sealed by the external carrier launch packet, never self-hashed.
    need(set(record.get("control_sha256", {})) == expected_controls - {"ci/backend2526-h1/admission.json"},
         "finite controls binding")
    for path, expected in record["control_sha256"].items():
        image.hex_value(expected)
        need(digest(raw(ROOT / path, 256 * 1024)) == expected, "control bytes drift")
    need(record.get("h1_seconds") == H1_SECONDS and record.get("smoke_seconds") == 600
         and record.get("total_seconds") == TOTAL_SECONDS, "fixed scope cap drift")
    if record["primary_admission"] == "H1_H2":
        need(record.get("h2_seconds") == H2_SECONDS and record.get("h2_work_seconds") == 480
             and record.get("h2_cleanup_seconds") == 120, "H2 cap not separately bound")
    return record, pins, expected_controls


def source_binding(record, pins, controls):
    ctx = image.context()  # Authentic runner environment, never manufactured push/main values.
    need(ctx["repository"] == image.REPOSITORY and ctx["repository_id"] == image.REPOSITORY_ID
         and ctx["event"] == "push" and ctx["ref"] == "refs/heads/" + BRANCH
         and ctx["attempt"] == 1 and ctx["workflow_sha"] == ctx["sha"]
         and ctx["workflow_ref"] == f"{image.REPOSITORY}/{WORKFLOW}@refs/heads/{BRANCH}"
         and os.environ.get("GITHUB_SERVER_URL") == "https://github.com"
         and os.environ.get("GITHUB_API_URL") == "https://api.github.com", "wrong carrier context")
    event = read_json(Path(os.environ["GITHUB_EVENT_PATH"]), 4 * 1024 * 1024)
    repo = event.get("repository", {})
    need(repo.get("id") == image.REPOSITORY_ID and repo.get("full_name") == image.REPOSITORY
         and repo.get("private") is False and repo.get("fork") is False
         and event.get("after") == ctx["sha"] and event.get("ref") == ctx["ref"]
         and event.get("deleted") is False and event.get("forced") is False, "wrong public push event")
    need(git("rev-parse", "HEAD").decode().strip() == ctx["sha"], "carrier checkout drift")
    need(not git("for-each-ref", "refs/replace").strip(), "replacement history refused")
    grafts = Path(git("rev-parse", "--path-format=absolute", "--git-path", "info/grafts").decode().strip())
    need(not grafts.exists(), "grafted history refused")
    headers = git("cat-file", "-p", "HEAD").split(b"\n\n", 1)[0].splitlines()
    need([x[7:].decode() for x in headers if x.startswith(b"parent ")] == [record["source_sha"]],
         "carrier needs exactly the reviewed public product parent")
    need(git("rev-parse", record["source_sha"] + "^{tree}").decode().strip() == record["source_tree"],
         "product source tree drift")
    parent_headers = git("cat-file", "-p", record["source_sha"]).split(b"\n\n", 1)[0].splitlines()
    need([x[7:].decode() for x in parent_headers if x.startswith(b"parent ")] == [pins["reviewed_base_sha"]],
         "source parent outside reviewed public base")
    need(not git("status", "--porcelain=v1", "--untracked-files=all").strip(), "dirty carrier checkout")

    def tree(sha):
        result = {}
        for entry in git("ls-tree", "-r", "-z", sha).split(b"\0"):
            if not entry:
                continue
            metadata, name = entry.split(b"\t", 1)
            mode, kind, oid = metadata.decode().split()
            need(kind == "blob" and mode in ("100644", "100755"), "unsupported source tree object")
            result[name.decode()] = (mode, oid)
        return result

    product_tree, carrier_tree = tree(record["source_sha"]), tree("HEAD")
    rows = pins["files"]
    need(len({x["path"] for x in rows}) == 476
         and set(product_tree) == {x["path"] for x in rows}
         and set(carrier_tree) == set(product_tree) | controls
         and not set(product_tree) & controls, "carrier non-control path inventory drift")
    for item in rows:
        name = item["path"]
        need(re.fullmatch(r"[A-Za-z0-9_.\-/]+", name) and all(x not in ("", ".", "..") for x in name.split("/")),
             "invalid pinned path")
        data = raw(ROOT / name, 8 * 1024 * 1024)
        oid = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        expected = (item["git_mode"], oid)
        need(len(data) == item["bytes"] and digest(data) == item["sha256"]
             and product_tree[name] == carrier_tree[name] == expected
             and bool((ROOT / name).stat().st_mode & 0o111) == (item["git_mode"] == "100755"),
             "one of the 476 product bytes/modes changed")
    delta = git("diff-tree", "--no-commit-id", "--name-status", "-r", record["source_sha"], "HEAD").decode().splitlines()
    need(set(delta) == {"A\t" + p for p in controls}, "carrier delta is not finite additions only")
    image.local_contract()
    return {"carrier_sha": ctx["sha"], "carrier_tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
            "source_sha": record["source_sha"], "source_tree": record["source_tree"],
            "source_pins_sha256": record["source_pins_sha256"], "product_paths": 476,
            "run_id": ctx["run_id"], "run_attempt": 1, "workflow": WORKFLOW,
            "primary_admission": record["primary_admission"],
            "independent_review_sha256": record["independent_review_sha256"]}


def root_state():
    path = Path(os.environ["B2526_SCRATCH"])
    os.environ["SCRATCH"] = str(path)
    need(image.owned_directory() == path and path.resolve() == path, "scratch ownership drift")
    state = read_json(path / "owner.json", image.MAX_CONFIG)
    ctx = image.context()
    need(state["scratch"] == str(path) and state["binding"]["carrier_sha"] == ctx["sha"]
         and state["binding"]["run_id"] == ctx["run_id"] and state["binding"]["run_attempt"] == ctx["attempt"],
         "scratch context drift")
    need(state["owner"] == f"{ctx['run_id']}.1.{ctx['sha'][:12]}"
         and state["prefix"] == f"kb2526-{ctx['run_id']}-1", "scratch owner marker drift")
    return path, state


def save_state(root, state):
    write_json(root / "owner.json", state, image.MAX_CONFIG)


def left(state, phase="h1"):
    cap = H1_SECONDS if phase == "h1" else TOTAL_SECONDS - FINAL_SECONDS - 60
    return state["started"] + cap - time.monotonic()


def reserve(state, seconds, phase="h1"):
    need(left(state, phase) >= seconds, "fixed scope budget exhausted; no retry or cap increase")


def bounded_seconds(seconds):
    ends = [x for x in (CLEANUP_END, RUN_END) if x is not None]
    if ends:
        remaining = min(ends) - time.monotonic()
        need(remaining > 4, "fixed work/cleanup deadline exhausted")
        seconds = min(seconds, remaining - 4)
    return seconds


def docker(state, *args, seconds=30, maximum=image.MAX_CONFIG, output=None, return_status=False):
    return image.command([state["docker"], "--config", state["scratch"] + "/docker", *args],
                         seconds=bounded_seconds(seconds), maximum=maximum, output=output, return_status=return_status)


def census(state):
    result = {}
    for kind, args in {"containers": ("ps", "--all", "--quiet", "--no-trunc"),
                       "networks": ("network", "ls", "--quiet", "--no-trunc"),
                       "volumes": ("volume", "ls", "--quiet"),
                       "images": ("image", "ls", "--all", "--quiet", "--no-trunc")}.items():
        values = set(docker(state, *args, maximum=256 * 1024).decode().splitlines())
        need(len(values) <= 4096 and all(re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", x) for x in values),
             "Docker census bound")
        result[kind] = sorted(values)
    return result


def inspect(state, kind, target):
    value = image.parse_json(docker(state, kind, "inspect", target), image.MAX_CONFIG)
    need(type(value) is list and len(value) == 1, "Docker identity ambiguous")
    return value[0]


def prepare(record, pins, controls):
    binding = source_binding(record, pins, controls)
    started = float(os.environ["B2526_STARTED"])
    need(math.isfinite(started) and 0 <= time.monotonic() - started <= 300, "fixed start clock invalid")
    for name in ("build", ".gradle", ".kotlin"):
        need(not os.path.lexists(ROOT / name), "preexisting product build path refused")
    runner = Path(os.environ["RUNNER_TEMP"]).resolve()
    executable = Path(shutil.which("docker") or "missing").resolve()
    need(executable.is_file() and os.access(executable, os.X_OK), "Docker unavailable")
    prefix = f"kb2526-{binding['run_id']}-1"
    evidence = runner / (prefix + "-evidence")
    need(not os.path.lexists(evidence), "preexisting evidence refused")
    scratch = Path(tempfile.mkdtemp(prefix="kira-backend-image-", dir=runner))
    state = {"scratch": str(scratch), "checkout": str(ROOT), "binding": binding, "started": started, "prefix": prefix,
             "owner": f"{binding['run_id']}.1.{binding['carrier_sha'][:12]}", "docker": str(executable),
             "docker_sha256": digest(raw(executable, 128 * 1024 * 1024)), "evidence": str(evidence),
             "builder": prefix, "candidate": None, "h1": "NOT_RUN", "h2": "NOT_RUN", "baseline": None,
             "builder_closed": False, "prepared": False, "source_build_owned": False, "shim_active": False}
    save_state(scratch, state)
    # Publish the owned handle before later probes, so the always-cleanup step can close failures.
    image.output("scratch", str(scratch))
    image.output("evidence", str(evidence))
    image.output("builder", prefix)
    image.output("h2", "bound" if binding["primary_admission"] == "H1_H2" else "not-admitted")
    with open(os.environ["GITHUB_ENV"], "a") as stream:
        stream.write(f"B2526_SCRATCH={scratch}\nDOCKER_CONFIG={scratch}/docker\n")
    for child in ("docker", "home", "tmp", "bin", "h1", "h1/objects"):
        (scratch / child).mkdir(mode=0o700)
    evidence.mkdir(mode=0o700)
    write_json(evidence / "bindings.json", binding)
    state["baseline"] = census(state)
    names = docker(state, "ps", "--all", "--format", "{{.Names}}", maximum=256 * 1024).decode().splitlines()
    network_names = docker(state, "network", "ls", "--format", "{{.Name}}", maximum=256 * 1024).decode().splitlines()
    volumes = state["baseline"]["volumes"]
    need(not any(x.startswith((prefix, "kira-release-smoke-", "buildx_buildkit_" + prefix))
                 for x in [*names, *network_names, *volumes]), "preexisting reserved name refused")
    tags = docker(state, "image", "ls", "--format", "{{.Repository}}:{{.Tag}}", maximum=256 * 1024).decode().splitlines()
    need("kira-backend:" + binding["carrier_sha"] not in tags, "preexisting candidate tag refused")
    need(shutil.disk_usage(scratch).free >= 12 * 1024**3, "insufficient bounded workspace capacity")
    versions = docker(state, "version", "--format", "{{.Client.Version}} {{.Server.Version}}", maximum=256).decode().strip()
    need(re.fullmatch(r"[0-9A-Za-z.+_-]{1,64} [0-9A-Za-z.+_-]{1,64}", versions), "Docker version record")
    state["docker_versions"] = versions.split()
    state["prepared"] = True
    save_state(scratch, state)
    print("B25/26 fixed public source/control binding passed; not a production candidate")


def candidate(state):
    need(RUN_END is None or RUN_END - time.monotonic() >= 70, "candidate inspection budget exhausted")
    identity = image.image_id(state["candidate"])
    tag = "kira-backend:" + state["binding"]["carrier_sha"]
    need(image.inspect_image(tag, identity) == identity, "candidate identity drift")
    value = inspect(state, "image", identity)
    config = value.get("Config", {})
    labels = config.get("Labels") or {}
    need(value.get("RepoTags") == [tag] and not value.get("RepoDigests")
         and config.get("User") == "10001:10001" and not config.get("Volumes")
         and labels.get("org.opencontainers.image.version") == "1.0.0"
         and labels.get("org.opencontainers.image.created") == "1970-01-01T00:00:00Z"
         and config.get("Entrypoint") == ["java", "-jar", "/app/app.jar"]
         and "SPRING_PROFILES_ACTIVE=prod" in config.get("Env", []), "unchanged production profile required")
    need(identity not in state["baseline"]["images"], "preexisting image is not owned")
    return {"id": identity, "tag": tag, "platform": "linux/amd64", "user": "10001:10001",
            "state_contract": image.STATE_PROFILE, "version": "1.0.0", "revision": state["binding"]["carrier_sha"]}


def owned_containers(state, stage=None):
    args = ["ps", "--all", "--quiet", "--no-trunc", "--filter", "label=" + OWNER_LABEL + "=" + state["owner"]]
    if stage:
        args += ["--filter", "label=" + STAGE_LABEL + "=" + stage]
    values = docker(state, *args, maximum=65536).decode().splitlines()
    need(len(values) <= 32 and all(re.fullmatch(r"[0-9a-f]{64}", x) for x in values), "owned container census bound")
    return values


def close_fixture(state, stage=None):
    removed = {"containers": [], "networks": [], "volumes": []}
    volumes = set()
    for identity in owned_containers(state, stage):
        need(identity not in state["baseline"]["containers"], "preexisting container refused")
        item = inspect(state, "container", identity)
        labels = item.get("Config", {}).get("Labels") or {}
        need(labels.get(OWNER_LABEL) == state["owner"], "container ownership drift")
        for mount in item.get("Mounts", []):
            if mount.get("Type") == "volume":
                name = mount.get("Name")
                need(name not in state["baseline"]["volumes"] and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name),
                     "preexisting or malformed fixture volume refused")
                volumes.add(name)
        docker(state, "container", "rm", "--force", "--volumes", identity)
        removed["containers"].append(identity)
    # The shim records anonymous volume custody BEFORE the unchanged smoke deletes its container.
    for record in (Path(state["scratch"]) / "h1/objects").glob("*.json"):
        entry = read_json(record)
        if stage in (None, "h1"):
            volumes.update(entry.get("anonymous_volumes", []))
    for kind in ("network", "volume"):
        args = [kind, "ls", "--quiet", "--filter", "label=" + OWNER_LABEL + "=" + state["owner"]]
        if stage:
            args += ["--filter", "label=" + STAGE_LABEL + "=" + stage]
        identifiers = docker(state, *args, maximum=65536).decode().splitlines()
        need(len(identifiers) <= 16, "owned network/volume census bound")
        if kind == "network":
            for identity in identifiers:
                item = inspect(state, kind, identity)
                need(item.get("Id") not in state["baseline"]["networks"]
                     and (item.get("Labels") or {}).get(OWNER_LABEL) == state["owner"]
                     and not item.get("Containers"), "network still used or not owned")
                docker(state, "network", "rm", item["Id"])
                removed["networks"].append(item["Id"])
        else:
            volumes.update(identifiers)
    remaining = set(docker(state, "volume", "ls", "--quiet").decode().splitlines())
    for name in sorted(volumes & remaining):
        need(name not in state["baseline"]["volumes"] and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name),
             "unowned volume refused")
        # docker volume rm without --force refuses every still-attached volume.
        docker(state, "volume", "rm", name)
        removed["volumes"].append(name)
    need(not owned_containers(state, stage), "owned container residue")
    for kind in ("network", "volume"):
        args = [kind, "ls", "--quiet", "--filter", "label=" + OWNER_LABEL + "=" + state["owner"]]
        if stage:
            args += ["--filter", "label=" + STAGE_LABEL + "=" + stage]
        need(not docker(state, *args).strip(), "owned network/volume residue")
    need(not (volumes & set(docker(state, "volume", "ls", "--quiet").decode().splitlines())),
         "anonymous fixture volume residue")
    return removed


def close_builder(state):
    if state["builder_closed"]:
        return {"status": "ALREADY_CLOSED"}
    name = "buildx_buildkit_" + state["builder"] + "0"
    volume = name + "_state"
    names = docker(state, "ps", "--all", "--format", "{{.Names}}").decode().splitlines()
    identity = None
    if name in names:
        item = inspect(state, "container", name)
        identity = item["Id"]
        need(identity not in state["baseline"]["containers"]
             and item.get("Name") == "/" + name
             and all(x.get("Name") == volume for x in item.get("Mounts", []) if x.get("Type") == "volume"),
             "builder ownership drift")
    need(volume not in state["baseline"]["volumes"], "preexisting builder state refused")
    code = docker(state, "buildx", "rm", "--force", state["builder"], seconds=45, return_status=True)
    current = census(state)
    # If setup failed halfway, only these pre-reserved, freshly observed exact objects may be removed.
    if identity and identity in current["containers"]:
        docker(state, "container", "rm", "--force", "--volumes", identity)
    if volume in current["volumes"]:
        docker(state, "volume", "rm", volume)
    after = census(state)
    need((not identity or identity not in after["containers"]) and volume not in after["volumes"],
         "owned builder/cache residue")
    instance = Path(state["scratch"]) / "docker/buildx/instances" / state["builder"]
    need(not instance.exists(), "builder instance configuration residue")
    state["builder_closed"] = True
    return {"status": "PASS", "remove_exit": code, "container_id": identity, "cache_volume": volume}


def remove_source_build(state):
    if not state["source_build_owned"]:
        return
    path = ROOT / "build"
    need(path.is_dir() and not path.is_symlink() and raw(path / ".backend2526-owner", 128).decode() == state["owner"],
         "source scratch ownership drift")
    shutil.rmtree(path)  # rmtree unlinks symlinks; it does not follow them outside the owned root.
    need(not os.path.lexists(path), "source smoke scratch residue")
    state["source_build_owned"] = False


def reclaim_empty_h2_media(state):
    """On child interruption, reclaim only the two fixed empty-media mount roots."""
    root = Path(state["scratch"]) / "h2"
    if not root.exists():
        return "ABSENT"
    need(root.resolve() == root and root.is_dir() and root.stat().st_uid == os.getuid()
         and stat.S_IMODE(root.stat().st_mode) == 0o700, "H2 scratch custody unknown")
    need(state["candidate"] is not None and not owned_containers(state), "H2 writer not closed")
    for index, leaf in enumerate(("source-media", "restored-media")):
        path = root / leaf
        if not os.path.lexists(path):
            continue
        info = path.lstat()
        need(path.resolve() == path and stat.S_ISDIR(info.st_mode), "H2 media mount custody unknown")
        identity = docker(state, "create", "--name", state["prefix"] + "-final-media-" + str(index),
            "--network", "none", "--read-only", "--user", "0:0", "--entrypoint", "sh",
            "--label", OWNER_LABEL + "=" + state["owner"], "--label", STAGE_LABEL + "=h2",
            "--mount", "type=bind,src=" + str(path) + ",dst=/owned-media", state["candidate"],
            "-ec", 'test -z "$(ls -A /owned-media)"; chown "$1:$2" /owned-media; chmod 700 /owned-media',
            "cleanup", str(os.getuid()), str(os.getgid()), maximum=1024).decode().strip()
        need(re.fullmatch(r"[0-9a-f]{64}", identity), "media cleanup container identity")
        try:
            docker(state, "start", "--attach", identity, seconds=10, maximum=1024)
            after = path.lstat()
            need((after.st_dev, after.st_ino) == (info.st_dev, info.st_ino)
                 and after.st_uid == os.getuid() and stat.S_IMODE(after.st_mode) == 0o700
                 and not list(path.iterdir()), "empty media root handoff unverified")
        finally:
            docker(state, "container", "rm", "--force", "--volumes", identity)
    return "EMPTY_OWNED_MOUNT_ROOTS_RECLAIMED"


def jar_pins(state, suffix):
    root = Path(state["scratch"])
    name = state["prefix"] + "-jar-" + suffix
    identity = docker(state, "create", "--name", name, "--network", "none",
                      "--label", OWNER_LABEL + "=" + state["owner"], "--label", STAGE_LABEL + "=h1",
                      state["candidate"], maximum=1024).decode().strip()
    need(re.fullmatch(r"[0-9a-f]{64}", identity), "JAR extraction container identity")
    archive, jar_path = root / ("jar-" + suffix + ".tar"), root / ("app-" + suffix + ".jar")
    try:
        item = inspect(state, "container", identity)
        need(item["Image"] == state["candidate"] and item["State"]["Running"] is False,
             "JAR copy must not run application code")
        with archive.open("xb") as target:
            docker(state, "cp", identity + ":/app/app.jar", "-", seconds=60,
                   maximum=MAX_JAR + image.MAX_CONFIG, output=target)
        # Bound raw extension records BEFORE tarfile can interpret PAX metadata.
        with archive.open("rb") as stream:
            count = 0
            while True:
                header = stream.read(512)
                need(len(header) == 512, "JAR copy tar truncated")
                if header == bytes(512):
                    break
                entry = tarfile.TarInfo.frombuf(header, "utf-8", "strict")
                count += 1
                need(count <= 4 and not entry.linkname and entry.type in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.XHDTYPE)
                     and 0 <= entry.size <= (4096 if entry.type == tarfile.XHDTYPE else MAX_JAR),
                     "JAR copy tar profile")
                stream.seek((entry.size + 511) // 512 * 512, 1)
        with tarfile.open(archive, mode="r:") as bundle:
            members = bundle.getmembers()
            need(len(members) == 1 and members[0].isfile() and members[0].name == "app.jar"
                 and not members[0].linkname and 0 < members[0].size <= MAX_JAR, "JAR copy member")
            with bundle.extractfile(members[0]) as source, jar_path.open("xb") as target:
                whole = image.copy_bounded(source, target, MAX_JAR)
        release.zip_directory(jar_path, MAX_JAR, 20000, 2 * 1024 * 1024)
        pair = {}
        with zipfile.ZipFile(jar_path) as jar:
            entries = jar.infolist()
            need(len({x.filename for x in entries}) == len(entries), "duplicate JAR entry")
            source_entries = {x.filename for x in entries if x.filename.startswith((
                "BOOT-INF/lib/source-contract", "BOOT-INF/lib/source-engine"))}
            need(source_entries == set(JARS), "unexpected source dependency family")
            for name, (length, checksum) in JARS.items():
                entry = jar.getinfo(name)
                need(entry.file_size == length and not entry.flag_bits & 1
                     and entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "nested JAR metadata")
                with jar.open(entry) as source:
                    actual = image.copy_bounded(source, None, length)
                need(actual == {"bytes": length, "sha256": checksum}, "published 0.1.0 nested JAR mismatch")
                pair[name] = actual
        return {"app_jar": whole, "nested": pair}
    finally:
        docker(state, "container", "rm", "--volumes", identity)
        need(identity not in census(state)["containers"], "JAR container residue")
        for path in (archive, jar_path):
            if path.exists():
                path.unlink()


def h1(root, state, record, pins, controls):
    need(state["prepared"] and state["h1"] == "NOT_RUN", "H1 is one-shot")
    need(source_binding(record, pins, controls) == state["binding"], "pre-build source binding drift")
    need(os.environ.get("BUILDX_BUILDER") == state["builder"], "builder output identity drift")
    state["candidate"] = image.image_id(os.environ.get("BUILDX_IMAGE_ID"))
    state["h1"] = "RUNNING"
    save_state(root, state)
    reserve(state, 780)
    metadata = candidate(state)
    buildx_version = docker(state, "buildx", "version", maximum=512).decode().strip()
    need(re.fullmatch(r"[A-Za-z0-9._/@:+ -]{1,250}", buildx_version), "Buildx version record")
    builder = close_builder(state)  # Remove all build-derived cache while retaining the one output image.
    save_state(root, state)
    with image.deadline(bounded_seconds(90)):
        before = jar_pins(state, "before")
    need(not os.path.lexists(ROOT / "build"), "preexisting smoke scratch refused")
    (ROOT / "build").mkdir(mode=0o700)
    (ROOT / "build/.backend2526-owner").write_text(state["owner"])
    state["source_build_owned"] = True
    save_state(root, state)
    shim = root / "bin/docker"
    shutil.copyfile(CONTROL / "docker-owner.py", shim)
    shim.chmod(0o700)
    reserve(state, 700)  # Existing produce: 600s smoke, two 30s inspections, their reaps, local rechecks.
    previous_path = os.environ["PATH"]
    state["shim_active"] = True
    save_state(root, state)
    try:
        os.environ["PATH"] = str(root / "bin") + os.pathsep + previous_path
        with image.deadline(700):
            image.produce(image.owned_directory(), image.context())
    finally:
        os.environ["PATH"] = previous_path
        state["shim_active"] = False
        save_state(root, state)
    need(not (root / "receipt.json").exists() and not (root / "image.tar.gz").exists(),
         "targeted non-main produce must not manufacture a production packet")
    fixture_cleanup = close_fixture(state, "h1")
    remove_source_build(state)
    save_state(root, state)
    need(candidate(state) == metadata, "post-smoke image drift")
    plain, archive = root / "diagnostic-image.tar", root / "diagnostic-image.tar.gz"
    reserve(state, 184)
    with plain.open("xb") as target:
        docker(state, "save", metadata["tag"], seconds=180, maximum=image.MAX_TAR, output=target)
    reserve(state, 94)
    with image.deadline(90):
        image.docker_tar(plain, metadata["tag"], True, state_contract=True)
    reserve(state, 184)
    with archive.open("xb") as target:
        image.command(["gzip", "--no-name", "--stdout", str(plain)], seconds=180, maximum=image.MAX_IMAGE, output=target)
    reserve(state, 94)
    image_meta, archive_meta = image.validate_archive(archive, metadata["tag"], backend=True,
                                                     expected_id=metadata["id"], state_contract=True)
    plain.unlink()
    reserve(state, 94)
    release_meta, release_archive = release.release_image(archive, {"sha": state["binding"]["carrier_sha"],
                                                                  "version": "1.0.0"}, expected=metadata["id"])
    need(release_archive["sha256"] == archive_meta["sha256"], "archive validators disagree")
    for mode, extra in (("archive", ["backend"]), ("backend-state", [])):
        reserve(state, 94)
        image.command([sys.executable, "-I", "-B", str(ROOT / IMAGE_HELPER), mode, *extra,
                       state["binding"]["carrier_sha"], str(archive), archive_meta["sha256"], metadata["id"]],
                      seconds=90, maximum=1024)
    reserve(state, 280)
    need(not owned_containers(state), "fixture still references candidate")
    need(candidate(state) == metadata, "pre-removal tag drift")
    docker(state, "image", "rm", metadata["tag"])
    need(metadata["id"] not in census(state)["images"], "image not genuinely absent before load")
    docker(state, "load", "--input", str(archive), seconds=180, maximum=65536)
    need(candidate(state) == metadata, "real loaded image drift")
    with image.deadline(bounded_seconds(90)):
        after = jar_pins(state, "after")
    need(after == before, "loaded nested JAR or app bytes drift")
    archive.unlink()
    need(source_binding(record, pins, controls) == state["binding"], "H1 source binding changed")
    reserve(state, 1)
    state["h1"] = "PASS"
    save_state(root, state)
    write_json(Path(state["evidence"]) / "h1.json", {
        "schema": "backend2526-h1-result-v1", "status": "PASS", "scope": "non-production exact-image diagnostic",
        "image": metadata, "nested_jar_checks": before, "archive": archive_meta,
        "image_archive_check": image_meta, "release_image_check": release_meta,
        "produce": "PASS_NON_MAIN_NO_PRODUCTION_RECEIPT", "smoke_seconds_cap": 600,
        "actual_remove_absence_load_recheck": "PASS", "loaded_jar_check": "IDENTICAL",
        "fixture_cleanup": fixture_cleanup, "build_cache_cleanup": builder,
        "tools": {"docker_client": state["docker_versions"][0], "docker_server": state["docker_versions"][1],
                  "buildx": buildx_version, "python": ".".join(str(x) for x in sys.version_info[:3])},
        "candidate_retained_for_bound_h2": state["binding"]["primary_admission"] == "H1_H2",
        "elapsed_since_job_start_seconds": round(time.monotonic() - state["started"], 3)})
    print("H1 passed; same exact image retained for the bound scope only")


def h2(root, state, record, pins, controls):
    need(record["primary_admission"] == "H1_H2" and state["h1"] == "PASS" and state["h2"] == "NOT_RUN",
         "H2 is fixed, separately admitted, and one-shot after H1PASS")
    # Helper's fixed <=4s group cleanup is not extra H2 work. Leave outer cleanup/artifact margins.
    reserve(state, H2_SECONDS + 4, "all")
    need(source_binding(record, pins, controls) == state["binding"], "H2 source binding drift")
    candidate(state)
    context = {"schema": "backend2526-h2-context-v1", "candidate_image_id": state["candidate"],
               "source_sha": record["source_sha"], "source_tree": record["source_tree"],
               "source_pins_sha256": record["source_pins_sha256"], "run_id": state["binding"]["run_id"],
               "run_attempt": 1, "scratch": str(root), "h2_root": str(root / "h2"),
               "resource_prefix": state["prefix"] + "-h2", "result": str(root / "h2-result.json"), "h1_pass": True}
    for name in ("h2-context.json", "h2", "h2-result.json"):
        need(not os.path.lexists(root / name), "H2 must be a fresh attempt")
    write_json(root / "h2-context.json", context)
    state["h2"] = "RUNNING"
    save_state(root, state)
    reserve(state, H2_SECONDS + 4, "all")
    image.command([sys.executable, "-I", "-B", str(CONTROL / "h2-recovery.py"), str(root / "h2-context.json")],
                  seconds=H2_SECONDS, maximum=image.MAX_CONFIG)
    result = read_json(root / "h2-result.json")
    need(result.get("schema") == "backend2526-h2-result-v1" and result.get("status") == "PASS"
         and result.get("cleanup") == "PASS", "fixed H2 adapter did not verify success/cleanup")
    for key in ("candidate_image_id", "source_sha", "source_tree", "source_pins_sha256", "run_id", "run_attempt"):
        need(result.get(key) == context[key], "H2 result identity drift")
    need(not owned_containers(state, "h2"), "H2 owned container residue")
    candidate(state)
    need(source_binding(record, pins, controls) == state["binding"], "H2 source binding changed")
    # Only the fixed separately reviewed adapter's bounded sanitized schema is retained.
    write_json(Path(state["evidence"]) / "h2.json", result)
    state["h2"] = "PASS"
    save_state(root, state)
    print("H2 fixed disposable recovery passed; this is not live-host or predecessor proof")


def finish(root, state):
    global CLEANUP_END
    end = time.monotonic() + FINAL_SECONDS
    CLEANUP_END = end - 15  # Always reserve a final filesystem-only private-scratch erasure window.
    errors, cleanup = [], {}

    def attempt(name, operation):
        try:
            need(name == "private_scratch" or time.monotonic() < end - 15, "final cleanup deadline exhausted")
            cleanup[name] = operation()
        except Exception:
            errors.append(name)  # Only finite stage names; no command text or exception payload.

    if state["baseline"] is not None:
        attempt("fixtures", lambda: close_fixture(state))
        attempt("empty_h2_media", lambda: reclaim_empty_h2_media(state))
        attempt("post_media_fixture_census", lambda: close_fixture(state))
        attempt("builder", lambda: close_builder(state))
        attempt("source_scratch", lambda: remove_source_build(state))

        def remove_image():
            identity = state["candidate"]
            tag = "kira-backend:" + state["binding"]["carrier_sha"]
            current = census(state)
            if identity is None:
                # A build action may have loaded its output before failing to emit imageid.
                tags = docker(state, "image", "ls", "--format", "{{.Repository}}:{{.Tag}}").decode().splitlines()
                if tag in tags:
                    identity = inspect(state, "image", tag)["Id"]
                    state["candidate"] = identity
            if identity is None or identity not in current["images"]:
                return "ABSENT"
            need(identity not in state["baseline"]["images"], "preexisting image refused")
            item = inspect(state, "image", identity)
            need(set(item.get("RepoTags") or []) <= {tag} and not item.get("RepoDigests")
                 and (item.get("Config", {}).get("Labels") or {}).get("org.opencontainers.image.revision")
                 == state["binding"]["carrier_sha"], "candidate cleanup ownership drift")
            docker(state, "image", "rm", identity)
            need(identity not in census(state)["images"], "candidate image residue")
            return "REMOVED_AND_ABSENT"

        attempt("candidate", remove_image)

        def final_census():
            current = census(state)
            for kind in ("containers", "networks", "volumes", "images"):
                need(set(state["baseline"][kind]) <= set(current[kind]), "preexisting Docker object disappeared")
            for kind in ("containers", "networks", "volumes"):
                # Unknown new objects are preserved and fail credit, never globally pruned.
                need(set(current[kind]) == set(state["baseline"][kind]), "unattributed Docker residue")
            return {"preexisting_ids_preserved": True, "new_container_network_volume_count": 0,
                    "global_third_party_images_preserved": True}

        attempt("independent_census", final_census)
    else:
        cleanup["docker"] = "NOT_STARTED"
    evidence = Path(state["evidence"])
    need(evidence.parent == Path(os.environ["RUNNER_TEMP"]).resolve()
         and evidence.name == state["prefix"] + "-evidence" and not evidence.is_symlink(), "evidence path drift")
    if not evidence.exists():
        evidence.mkdir(mode=0o700)
    if not (evidence / "bindings.json").exists():
        write_json(evidence / "bindings.json", state["binding"])
    if (root / "h2-result.json").exists():
        try:
            result = read_json(root / "h2-result.json")
            need(result.get("schema") == "backend2526-h2-result-v1"
                 and result.get("status") in ("PASS", "FAIL", "NOT_RUN")
                 and result.get("candidate_image_id") == state["candidate"]
                 and all(result.get(key) == state["binding"][key] for key in
                         ("source_sha", "source_tree", "source_pins_sha256", "run_id", "run_attempt")),
                 "H2 diagnostic binding drift")
            write_json(evidence / "h2.json", result)
        except Exception:
            errors.append("h2_diagnostic")
    h1_ok = state["h1"] == "PASS"
    h2_ok = state["h2"] == "PASS" or state["binding"]["primary_admission"] == "H1_ONLY"
    # Archive/dumps/tokens/checkpoints/config/cache live only under the owned scratch being erased.
    attempt("private_scratch", lambda: shutil.rmtree(image.owned_directory()))
    if os.path.lexists(root):
        errors.append("private_scratch_absence")
    cleanup["private_scratch_absent"] = not os.path.lexists(root)
    cleanup["status"] = "PASS" if not errors else "FAIL"
    cleanup["failed_stages"] = errors
    write_json(evidence / "cleanup.json", cleanup)
    for phase in ("h1", "h2"):
        if not (evidence / (phase + ".json")).exists():
            write_json(evidence / (phase + ".json"), {"schema": f"backend2526-{phase}-result-v1",
                                                     "status": state[phase], "details": "not credited"})
    write_json(evidence / "result.json", {"schema": "backend2526-gate-result-v1",
        "status": "PASS" if h1_ok and h2_ok and not errors else "FAIL", "h1": state["h1"], "h2": state["h2"],
        "cleanup": cleanup["status"], "binding": state["binding"], "candidate_image_id": state["candidate"],
        "production_candidate": False, "archive_published": False, "predecessor_or_live_host_proof": False,
        "full_ci_or_offline_suite_replay": False})
    expected = {"bindings.json", "result.json", "h1.json", "h2.json", "cleanup.json"}
    need({p.name for p in evidence.iterdir()} == expected, "unexpected evidence member")
    total = sum(len(raw(evidence / name, MAX_RECORD)) for name in expected)
    need(total <= 64 * 1024, "sanitized evidence total bound")
    (evidence / "SHA256SUMS").write_text("".join(digest(raw(evidence / name, MAX_RECORD)) + "  " + name + "\n"
                                                  for name in sorted(expected)))
    need(h1_ok and h2_ok and not errors, "gate incomplete; preserve failure and do not retry")
    print("B25/26 admitted diagnostic scope verified; owned resources absent; no production authorization")


def main():
    global RUN_END
    os.umask(0o077)
    need(len(sys.argv) == 2 and sys.argv[1] in ("prepare", "h1", "h2", "finish"), "fixed gate stage required")
    mode = sys.argv[1]
    if mode == "prepare":
        record, pins, controls = admission()
        prepare(record, pins, controls)
        return
    root, state = root_state()
    os.environ["HOME"], os.environ["TMPDIR"] = str(root / "home"), str(root / "tmp")
    os.environ["LANG"] = "C.UTF-8"
    need(digest(raw(Path(state["docker"]), 128 * 1024 * 1024)) == state["docker_sha256"], "Docker executable drift")
    if mode == "finish":
        finish(root, state)
        return
    RUN_END = state["started"] + (H1_SECONDS if mode == "h1" else TOTAL_SECONDS - FINAL_SECONDS - 60)
    try:
        record, pins, controls = admission()
        {"h1": h1, "h2": h2}[mode](root, state, record, pins, controls)
    except Exception:
        if state[mode] != "NOT_RUN":
            state[mode] = "FAIL"
        save_state(root, state)
        raise


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        # No arbitrary exceptions, Docker output, fixture state or credentials enter public logs.
        print("B25/26 diagnostic gate refused or incomplete; no retry or production credit", file=sys.stderr)
        sys.exit(1)
