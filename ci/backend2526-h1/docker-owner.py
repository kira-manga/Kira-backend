#!/usr/bin/python3 -I
"""H1-only ownership annotations around REAL Docker; never return synthetic results.

Only add labels/names/cidfiles on creates and -v on owned container removal.
Image/config/auth/command arguments and Docker's stdout/stderr/exit are preserved.
The unchanged smoke has three unnamed --rm helpers and one anonymous PGDATA volume.
"""
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import stat
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
OWNER_LABEL, STAGE_LABEL = "io.kira.backend2526.owner", "io.kira.backend2526.stage"
HELPER_SHA = "e677d40f0e391ddaea0342e2da9915a1448164204e0cea7bd645172f750a3fa6"
PG = "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94"


def need(ok):
    if not ok:
        raise RuntimeError("ownership refusal")


def read(path, maximum, owned=True):
    info = path.lstat()
    need(stat.S_ISREG(info.st_mode) and (not owned or info.st_uid == os.getuid()) and info.st_size <= maximum)
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    need(len(data) <= maximum)
    return data


need(ROOT.is_dir() and not ROOT.is_symlink() and ROOT.stat().st_uid == os.getuid()
     and ROOT.stat().st_mode & 0o077 == 0)
state = json.loads(read(ROOT / "owner.json", 1024 * 1024))
need(state["scratch"] == str(ROOT) and state["h1"] == "RUNNING" and state["shim_active"] is True
     and re.fullmatch(r"[1-9][0-9]{0,15}\.1\.[0-9a-f]{12}", state["owner"])
     and re.fullmatch(r"sha256:[0-9a-f]{64}", state["candidate"]))
checkout = Path(state["checkout"])
helper = checkout / "scripts/ci/image_release.py"
need(hashlib.sha256(read(helper, 128 * 1024)).hexdigest() == HELPER_SHA)
sys.path.insert(0, str(helper.parent))
image = importlib.import_module("image_release")
real = state["docker"]
need(hashlib.sha256(read(Path(real), 128 * 1024 * 1024, owned=False)).hexdigest() == state["docker_sha256"])
objects = ROOT / "h1/objects"


def command(*args):
    return image.command([real, "--config", str(ROOT / "docker"), *args], seconds=10, maximum=image.MAX_CONFIG)


def record(kind, name):
    need(kind in ("container", "network", "volume") and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name))
    path = objects / (hashlib.sha256((kind + ":" + name).encode()).hexdigest() + ".json")
    value = json.loads(read(path, 16384)) if path.exists() else {
        "kind": kind, "name": name, "ids": [], "cidfiles": [], "anonymous_volumes": []}
    need(value["kind"] == kind and value["name"] == name)
    return path, value


def save(path, value):
    data = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()
    need(len(data) <= 16384)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)


def present(kind, name):
    args = ("ps", "--all", "--format", "{{.Names}}") if kind == "container" else (kind, "ls", "--format", "{{.Name}}")
    return name in command(*args).decode().splitlines()


def owned(kind, name):
    path, receipt = record(kind, name)
    need(path.exists())
    if not present(kind, name):
        return None
    values = image.parse_json(command(kind, "inspect", name), image.MAX_CONFIG)
    need(type(values) is list and len(values) == 1)
    item = values[0]
    labels = (item.get("Config", {}) if kind == "container" else item).get("Labels") or {}
    need(labels.get(OWNER_LABEL) == state["owner"] and labels.get(STAGE_LABEL) == "h1")
    if kind != "volume":
        identity = item["Id"]
        need(re.fullmatch(r"[0-9a-f]{64}", identity) and identity not in state["baseline"][kind + "s"])
        receipt["ids"] = sorted(set(receipt["ids"]) | {identity})
    if kind == "container":
        need(item["Image"] == state["candidate"] or receipt.get("image") == PG)
        for mount in item.get("Mounts", []):
            if mount.get("Type") != "volume":
                continue
            volume = mount["Name"]
            need(volume not in state["baseline"]["volumes"])
            if re.fullmatch(r"[0-9a-f]{64}", volume):
                # Proven from an actual attached mount while the owned PG container still exists.
                receipt["anonymous_volumes"] = sorted(set(receipt["anonymous_volumes"]) | {volume})
            else:
                owned("volume", volume)
    save(path, receipt)  # Persist anonymous-volume custody BEFORE Docker can delete the container.
    return item


def intent(kind, name, image_name=None):
    need(not present(kind, name))
    path, value = record(kind, name)
    if image_name is not None:
        need(value.get("image", image_name) == image_name)
        value["image"] = image_name
    save(path, value)
    return path, value


def main():
    args = sys.argv[1:]
    need(1 <= len(args) <= 100)
    operation = args[0]
    if operation == "run":
        value_options = {"--name", "--network", "--env", "--volume", "--entrypoint", "--user", "--tmpfs", "--publish"}
        flags = {"--detach", "--rm", "--read-only"}
        index, name = 1, None
        while index < len(args) and args[index].startswith("-"):
            flag = args[index]
            if flag in flags:
                index += 1
            else:
                need(flag in value_options and index + 1 < len(args))
                if flag == "--name":
                    need(name is None)
                    name = args[index + 1]
                index += 2
        need(index < len(args) and args[index] in (state["candidate"], PG))
        image_name = args[index]
        addition = ["--label", OWNER_LABEL + "=" + state["owner"], "--label", STAGE_LABEL + "=h1"]
        if name is None:
            # Names are ownership-only; no container environment/image/auth semantics change.
            name = state["prefix"] + "-h1-" + os.urandom(6).hex()
            addition += ["--name", name]
        else:
            need(re.fullmatch(r"kira-release-smoke-(?:app|postgres)-[1-9][0-9]*", name))
        # The candidate's Config.Volumes is independently required empty before produce.
        # Thus all --rm helpers have no anonymous volume; PGDATA is captured before rm.
        need("--rm" not in args[1:index] or image_name == state["candidate"])
        path, value = intent("container", name, image_name)
        cidfile = "h1/cid-" + os.urandom(8).hex()
        value["cidfiles"].append(cidfile)
        need(len(value["cidfiles"]) <= 4)
        save(path, value)
        args[1:1] = addition + ["--cidfile", str(ROOT / cidfile)]
    elif args[:2] in (["network", "create"], ["volume", "create"]):
        kind = args[0]
        need(len(args) == 3 and re.fullmatch(r"kira-release-smoke-(?:media-)?[1-9][0-9]*", args[2]))
        intent(kind, args[2])
        args[2:2] = ["--label", OWNER_LABEL + "=" + state["owner"], "--label", STAGE_LABEL + "=h1"]
    elif operation == "rm":
        targets = [x for x in args[1:] if x != "--force"]
        need(1 <= len(targets) <= 2)
        for name in targets:
            owned("container", name)
        args.insert(1, "--volumes")  # Named media persists until its explicit volume rm.
    elif args[:2] in (["network", "rm"], ["volume", "rm"]):
        targets = [x for x in args[2:] if x != "--force"]
        need(len(targets) == 1)
        owned(args[0], targets[0])
    elif operation == "stop":
        need(len(args) == 4 and args[1:3] == ["--time", "30"])
        owned("container", args[3])
    elif operation in ("exec", "logs"):
        need(len(args) >= 2)
        owned("container", args[1])
    elif operation == "inspect":
        need(len(args) in (2, 4) and (len(args) == 2 or args[1] == "--format"))
        owned("container", args[-1])
    elif args[:2] == ["image", "inspect"]:
        need(len(args) in (3, 5) and (len(args) == 3 or args[2] == "--format")
             and args[-1] in (state["candidate"], "kira-backend:" + state["binding"]["carrier_sha"]))
    else:
        need(False)
    # Exec the REAL client. No success strings, fake IDs, or stdout/exit rewriting.
    os.execv(real, [real, "--config", str(ROOT / "docker"), *args])


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        print("H1 Docker ownership operation refused", file=sys.stderr)
        sys.exit(1)
