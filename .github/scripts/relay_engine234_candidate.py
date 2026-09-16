"""Copy one accepted archive into Actions storage; never build, repack, or publish it."""

import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import zipfile


REPOSITORY = "kira-manga/Kira-backend"
REPOSITORY_ID = "1304735394"
REF = "refs/heads/verification/engine234-candidate-relay-20260916-01"
WORKFLOW = ".github/workflows/engine234-candidate-relay.yml"
ZIP_BYTES = 1694426
ZIP_SHA256 = "61a7af3571380cf83594b44f66db1edeaabf925e2a4e030eef1114948506ab2e"
ARCHIVE_BYTES = 1650160
ARCHIVE_SHA256 = "00719730667701e9c6ef661d12eb6f8d19d58b74dee2c590ddb8834e308c487f"
ARCHIVE_NAME = "candidate-maven.tar.gz"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    env = os.environ
    require(len(sys.argv) == 2, "Expected one owned relay directory")
    require(env["GITHUB_REPOSITORY"] == REPOSITORY, "Wrong repository")
    require(env["GITHUB_REPOSITORY_ID"] == REPOSITORY_ID, "Wrong repository identity")
    require(env["GITHUB_EVENT_NAME"] == "push" and env["GITHUB_REF"] == REF, "Wrong event/ref")
    require(env["GITHUB_RUN_ATTEMPT"] == "1", "Reruns are not admitted")
    run_id = env["GITHUB_RUN_ID"]
    require(re.fullmatch(r"[1-9][0-9]{0,19}", run_id), "Invalid storage run")
    require(run_id != "35018546402", "Storage run cannot replace producer identity")
    sha = env["GITHUB_SHA"]
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "Invalid storage source")
    require(env["GITHUB_WORKFLOW_SHA"] == sha, "Wrong workflow source")
    require(env["GITHUB_WORKFLOW_REF"] == f"{REPOSITORY}/{WORKFLOW}@{REF}", "Wrong workflow ref")
    root = Path(sys.argv[1])
    expected_root = Path(env["RUNNER_TEMP"]) / f"engine234-relay-{run_id}-1"
    require(root == expected_root and root.is_dir() and not root.is_symlink(), "Wrong owned directory")

    with (root / "original.zip").open("rb") as source:
        raw_zip = source.read(ZIP_BYTES + 1)
    require(len(raw_zip) == ZIP_BYTES, "Original ZIP size mismatch")
    require(hashlib.sha256(raw_zip).hexdigest() == ZIP_SHA256, "Original ZIP hash mismatch")
    # The authenticated ZIP is not extracted. Only this single bounded member is read.
    with zipfile.ZipFile(io.BytesIO(raw_zip)) as original:
        members = [entry for entry in original.infolist() if entry.filename == ARCHIVE_NAME]
        require(len(members) == 1 and members[0].file_size == ARCHIVE_BYTES, "Wrong candidate member")
        with original.open(members[0]) as source:
            candidate = source.read(ARCHIVE_BYTES + 1)
    require(len(candidate) == ARCHIVE_BYTES, "Candidate size mismatch")
    require(hashlib.sha256(candidate).hexdigest() == ARCHIVE_SHA256, "Candidate hash mismatch")

    origin = {
        "schema": "engine234-exact-candidate-storage-v1",
        "purpose": "Nonrelease exact-byte candidate preservation only",
        "original_producer": {
            "repository": REPOSITORY,
            "repository_id": int(REPOSITORY_ID),
            "run_id": 35018546402,
            "run_attempt": 1,
            "carrier_sha": "7059fa13043ae8ae6bacc15f0feaf414f053bfc7",
            "artifact_id": 10416542685,
            "artifact_name": "engine234-package-35018546402-1",
            "artifact_zip_bytes": ZIP_BYTES,
            "artifact_zip_sha256": ZIP_SHA256,
            "engine_source_sha": "8cb9ead849831a2048601580f985305a8361027c",
            "engine_source_tree": "19ff19fa24d7865e496b07e1ad2321a608d65bdc",
        },
        "candidate": {
            "version": "0.1.0-engine234-8cb9ead84983-macos-35018546402-1",
            "path": ARCHIVE_NAME,
            "bytes": ARCHIVE_BYTES,
            "sha256": ARCHIVE_SHA256,
            "rebuilt_or_repacked": False,
        },
        "storage_run": {
            "repository": REPOSITORY,
            "run_id": int(run_id),
            "run_attempt": 1,
            "source_sha": sha,
            "workflow_ref": env["GITHUB_WORKFLOW_REF"],
            "requested_retention_days": 7,
        },
        "limits": [
            "Storage identity does not replace original producer provenance or acceptance.",
            "No new native/compiler/test qualification, package publication, release, or activation.",
            "Actions retention is finite; this is not a shipping Maven repository or Engine6 StageC authority.",
        ],
    }
    public = root / "public"
    public.mkdir()
    with (public / ARCHIVE_NAME).open("xb") as output:
        output.write(candidate)
    with (public / "origin.json").open("x", encoding="utf-8") as output:
        output.write(json.dumps(origin, indent=2) + "\n")
    print("Original ZIP and unchanged candidate verified; two allowlisted storage files ready.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, zipfile.BadZipFile):
        # No headers, signed URLs, environment dump, source archive, or raw exception text.
        raise SystemExit("Exact candidate relay refused; nothing is authorized for upload.") from None
