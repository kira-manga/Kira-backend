"""Drain one bounded gate log; overflow is an explicit failure, never a silent truncated pass."""
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
limit, seen = (8 if path.stem in {"engine", "backend", "pgjdbc"} else 1) * 1024 * 1024, 0
with path.open("wb") as output:
    while chunk := sys.stdin.buffer.read(65536):
        output.write(chunk[:max(0, limit - seen)])
        seen += len(chunk)
path.with_suffix(".capture.json").write_text(json.dumps({
    "observed_bytes": seen, "retained_bytes": min(limit, seen), "truncated": seen > limit,
}) + "\n")
sys.exit(86 if seen > limit else 0)
