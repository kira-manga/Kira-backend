"""Linux-only owned-child cleanup for the one App29 CI process; import has no actions."""
import ctypes, os, signal, time
from pathlib import Path

class OwnedChildren:
    def __init__(self):
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER, before any Popen.
            raise OSError(ctypes.get_errno(), 'Linux child subreaper unavailable; refusing CI')
        self.active, self.leaders, self.adopted, self.unknown = {}, [], [], False
        self.children_file = Path(f'/proc/self/task/{os.getpid()}/children')
        if self.children_file.read_text().strip():
            raise RuntimeError('Unexpected children before the first owned command; refusing CI')

    def collect_leaders(self):
        for pid, process in list(self.active.items()):
            status = process.poll()  # Only this Popen reaps its own leader, nonblocking.
            if status is not None:
                self.leaders.append({'pid': pid, 'actual_exit': status})
                del self.active[pid]

    def track(self, process):
        self.collect_leaders()
        self.active[process.pid] = process
        return process

    def drain(self):
        start = time.monotonic()
        deadline, term_until = start + 8, start + 1
        termed, killed, remaining, errors, enumerated = set(), set(), set(), {}, False
        empty_proved = False
        while True:
            self.collect_leaders()
            try:
                remaining = set(map(int, self.children_file.read_text().split()))
                enumerated = True
            except (OSError, ValueError) as failure:
                errors['children'] = str(failure)
                self.unknown = True
                enumerated = False
                break
            for pid in remaining - self.active.keys():
                if time.monotonic() >= deadline: break
                try: child, status = os.waitpid(pid, os.WNOHANG)  # Explicit adopted child, never -1.
                except OSError as failure:
                    errors[str(pid)] = str(failure)
                    self.unknown = True
                    continue
                if child:
                    self.adopted.append({'pid': child, 'actual_exit': os.waitstatus_to_exitcode(status)})
                    remaining.remove(child)
            if not remaining and not self.active:
                try: os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                except ChildProcessError:  # ECHILD confirms no newly adopted child missed by /proc.
                    empty_proved = True
                    break
                except OSError as failure:
                    errors['waitid'] = str(failure)
                    self.unknown = True
                    break
            if time.monotonic() >= deadline: break
            for pid in remaining:
                if time.monotonic() >= deadline: break
                try:
                    if pid not in termed:
                        os.kill(pid, signal.SIGTERM)
                        termed.add(pid)
                    if time.monotonic() >= term_until:
                        os.kill(pid, signal.SIGKILL)
                        killed.add(pid)
                except ProcessLookupError: pass  # Reap/confirm on the next iteration, not assumed empty.
                except OSError as failure:
                    errors[str(pid)] = str(failure)
                    self.unknown = True
            time.sleep(min(0.05, max(0, deadline - time.monotonic())))
        empty = empty_proved if enumerated and not self.unknown else None
        return {'ok': empty is True and not self.unknown, 'empty': empty, 'remaining': sorted(remaining),
                'active_popen': sorted(self.active), 'leaders': list(self.leaders), 'adopted': list(self.adopted),
                'term': sorted(termed), 'kill': sorted(killed), 'errors': errors, 'elapsed_seconds': time.monotonic() - start}
