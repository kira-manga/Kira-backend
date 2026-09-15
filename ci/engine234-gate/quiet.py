"""Check only Java processes carrying this run's exact environment marker; pidfds prevent PID-reuse kills."""
import json
import os
import select
import signal
import sys
import time
from pathlib import Path

root = Path(os.environ["ENGINE234_RUN_ROOT"]).resolve()
marker = ("ENGINE234_RUN_ROOT=" + str(root)).encode()
def inspect():
    owned, unknown = [], []
    for proc in Path("/proc").glob("[0-9]*"):
        fd, same_user = None, False
        try:
            if proc.stat().st_uid != os.getuid():
                continue
            same_user = True
            if proc.joinpath("comm").read_text().strip() != "java":
                continue
            fd = os.pidfd_open(int(proc.name))
            if marker in proc.joinpath("environ").read_bytes().split(b"\0"):
                owned.append((int(proc.name), fd))
                fd = None
        except (FileNotFoundError, ProcessLookupError):
            pass
        except OSError as failure:
            if same_user:
                unknown.append({"pid": int(proc.name), "errno": failure.errno})
        finally:
            if fd is not None:
                os.close(fd)
    return owned, unknown


owned, unknown = inspect()
poll = select.poll()
for _, fd in owned:
    poll.register(fd, select.POLLIN)
deadline = time.monotonic() + 10
alive = {fd for _, fd in owned}
while alive and time.monotonic() < deadline:
    alive.difference_update(fd for fd, _ in poll.poll(100))
forced = [pid for pid, fd in owned if fd in alive]
for sig in (signal.SIGTERM, signal.SIGKILL):
    for fd in alive:
        try:
            signal.pidfd_send_signal(fd, sig)
        except ProcessLookupError:
            pass
    if alive:
        time.sleep(2)
    alive.difference_update(fd for fd, _ in poll.poll(0))
for _, fd in owned:
    os.close(fd)
late, late_unknown = inspect()
for _, fd in late:
    os.close(fd)
unknown += late_unknown
root.joinpath("evidence", sys.argv[1] + "-java-cleanup.json").write_text(json.dumps({
    "owned_pids": [pid for pid, _ in owned], "forced_pids": forced, "remaining_count": len(alive),
    "late_owned_pids": [pid for pid, _ in late], "uninspectable": unknown,
}) + "\n")
sys.exit(1 if forced or alive or late or unknown else 0)
