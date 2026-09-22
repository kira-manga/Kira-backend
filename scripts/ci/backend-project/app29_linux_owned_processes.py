#!/usr/bin/env python3
"""Private Linux child ownership; import and construction do not change the kernel.

Activate before the first child. Track immediately after Popen (inside the caller's
cleanup protection), and use barrier before reading/deleting mutable child outputs.
Known Popen handles should be joined before barrier, which also reaps adoptees.
Only /proc stat identities are inspected; no process environment or command line.
Every signal uses a short-lived pidfd, with identity AND full-tree checks after open.
An absence receipt is not a success receipt if forced or errors is nonempty.
"""

from __future__ import annotations

import ctypes
import os
from pathlib import Path
import re
import select
import signal
import sys
import time


class OwnershipError(RuntimeError):
    def __init__(self, message, receipt=None):
        super().__init__(message)
        self.receipt = receipt or {"absent": False, "forced": False, "errors": []}


def same_identity(expected, current):
    return expected is not None and current is not None and all(
        expected.get(key) == current.get(key) for key in ("pid", "start_ticks", "boot_id"))


def identity_key(identity):
    return identity["pid"], identity["start_ticks"], identity["boot_id"]


def process_identity(pid, *, proc_root=Path("/proc"), boot_id=None):
    """Stat-only identity; a disappearing PID is absence, unreadability is not."""
    if boot_id is None:
        boot_id = (proc_root / "sys/kernel/random/boot_id").read_text().strip()
    if not re.fullmatch(r"[0-9a-f-]{36}", boot_id):
        raise OwnershipError("Invalid host boot identity")
    try:
        line = (proc_root / str(pid) / "stat").read_text()
    except (FileNotFoundError, ProcessLookupError):
        return None
    try:
        name, values = line.rsplit(") ", 1)
        fields = values.split()
        if int(name.split("(", 1)[0]) != pid or len(fields) < 20 or len(fields[0]) != 1:
            raise ValueError()
        return {"pid": pid, "ppid": int(fields[1]), "pgid": int(fields[2]),
                "session": int(fields[3]), "start_ticks": int(fields[19]),
                "state": fields[0], "boot_id": boot_id}
    except (ValueError, IndexError) as error:
        raise OwnershipError("Unparseable process stat identity") from error


def descendants_from(table, parent):
    """All sessions/groups below parent, not just immediate children or adoptees."""
    found, owners = {}, {parent}
    while True:
        more = {pid: row for pid, row in table.items()
                if row["ppid"] in owners and pid != parent and pid not in found}
        if not more:
            return found
        found.update(more)
        owners.update(more)


class _Linux:
    """Small injectable syscall seam. Tests replace it; constructors are inert."""
    clock = staticmethod(time.monotonic)
    sleep = staticmethod(time.sleep)
    getpid = staticmethod(os.getpid)

    def supported(self):
        return sys.platform.startswith("linux") and hasattr(os, "pidfd_open") and hasattr(signal, "pidfd_send_signal")

    def table(self):
        root = Path("/proc")
        boot = (root / "sys/kernel/random/boot_id").read_text().strip()
        table = {}
        for path in root.iterdir():
            if path.name.isdecimal():
                row = process_identity(int(path.name), proc_root=root, boot_id=boot)
                if row is not None:
                    table[row["pid"]] = row
        return table

    def get_subreaper(self):
        previous = ctypes.c_int()
        if ctypes.CDLL(None, use_errno=True).prctl(37, ctypes.byref(previous), 0, 0, 0) != 0:
            raise OwnershipError("Cannot read child-subreaper state")
        return previous.value

    def set_subreaper(self, value):
        if ctypes.CDLL(None, use_errno=True).prctl(36, int(value), 0, 0, 0) != 0:
            raise OwnershipError("Cannot set child-subreaper state")

    def open_pidfd(self, pid):
        return os.pidfd_open(pid, 0)

    def close_pidfd(self, fd):
        os.close(fd)

    def exited(self, fd):
        poller = select.poll()
        poller.register(fd, select.POLLIN)
        events = poller.poll(0)
        if any(event & (select.POLLERR | select.POLLNVAL) for _, event in events):
            raise OwnershipError("Cannot inspect pinned pidfd")
        return bool(events)

    def send(self, fd, signum):
        signal.pidfd_send_signal(fd, signum, None, 0)

    def reap(self, pid):
        try:
            result, _status = os.waitpid(pid, os.WNOHANG)
            return result == pid
        except ChildProcessError:
            return False  # Still a grandchild: owned parent must reap/exit first.


class OwnedChildren:
    """One initially-childless process's private subreaper scope; no numeric PID kill."""
    def __init__(self, ops=None):
        self.ops = _Linux() if ops is None else ops
        self.active = False
        # Separate launch readiness from an attempted syscall's restoration debt.
        # prctl can have taken effect even if Python is interrupted before return.
        self.restoration_pending = False
        self.previous = None
        self.parent = None
        self.observed = {}
        self.signals = []
        self.reaped = []
        self.errors = []
        self.barrier_generation = 0
        self.last_receipt = self._receipt(False)

    def _receipt(self, absent):
        return {"absent": absent, "forced": bool(self.signals), "barrier_generation": self.barrier_generation,
                "signals": list(self.signals), "observed": list(self.observed.values()),
                "reaped": list(self.reaped), "errors": list(self.errors)}

    def _error(self, stage, error):
        # Fixed stage/type metadata only. Never include arbitrary exception text.
        record = {"stage": stage, "type": type(error).__name__}
        if record not in self.errors:
            self.errors.append(record)
        self.last_receipt = self._receipt(False)

    def _scan(self):
        table = self.ops.table()
        if self.parent is None or not same_identity(self.parent, table.get(self.parent["pid"])):
            raise OwnershipError("Owning parent identity changed")
        children = descendants_from(table, self.parent["pid"])
        for known in self.observed.values():
            current = table.get(known["pid"])
            if same_identity(known, current) and known["pid"] not in children:
                raise OwnershipError("Previously owned process escaped the descendant census")
        return table, children

    def activate(self):
        self.last_receipt = self._receipt(False)
        if self.active or self.parent is not None:
            raise OwnershipError("Child scope cannot be activated twice", self.last_receipt)
        if not self.ops.supported():
            raise OwnershipError("Linux pidfd ownership support is required", self.last_receipt)
        try:
            table = self.ops.table()
            pid = self.ops.getpid()
            if pid not in table or descendants_from(table, pid):
                raise OwnershipError("Scope must start with a known parent and no children")
            self.parent = table[pid]
            self.previous = self.ops.get_subreaper()
            if self.previous not in (0, 1):
                raise OwnershipError("Unexpected prior child-subreaper state")
            # Prove pidfd syscall/permission support on SELF before any child launch.
            fd = self.ops.open_pidfd(pid)
            try:
                _, children = self._scan()
                if children or self.ops.exited(fd):
                    raise OwnershipError("Ownership changed during acquisition")
                self.ops.send(fd, 0)
            finally:
                self.ops.close_pidfd(fd)
            self.restoration_pending = True  # Before syscall, not after its fallible return.
            self.ops.set_subreaper(1)
            if self.ops.get_subreaper() != 1:
                raise OwnershipError("Child-subreaper activation readback differs")
            self.active = True  # Launch-safe only AFTER successful kernel readback.
            self.track()
            return {"parent": self.parent, "previous_setting": self.previous}
        except BaseException as error:
            self._error("activate", error)
            raise OwnershipError("Child scope acquisition failed", self.last_receipt) from error

    def track(self):
        self.last_receipt = self._receipt(False)  # New children invalidate any previous absence receipt.
        if not self.active:
            raise OwnershipError("Child scope is not active", self.last_receipt)
        try:
            _table, children = self._scan()
            for row in children.values():
                self.observed.setdefault(identity_key(row), dict(row))
            return list(children.values())
        except BaseException as error:
            self._error("census", error)
            raise OwnershipError("Owned descendant census is unknown", self.last_receipt) from error

    def signal_owned(self, expected, signum):
        """Signal one verified descendant through pidfd, closing it on every path.

        Returns False only if the pinned process exited during acquisition; identity
        replacement or lost tree membership is an error, never a foreign signal.
        Successful TERM/KILL stays `forced` on every later barrier receipt.
        """
        self.last_receipt = self._receipt(False)
        if not self.active or signum not in (signal.SIGTERM, signal.SIGKILL):
            raise OwnershipError("Invalid owned signal request", self.last_receipt)
        fd = None
        try:
            table, children = self._scan()
            current = table.get(expected["pid"])
            if current is None:
                return False
            if not same_identity(expected, current) or expected["pid"] not in children:
                raise OwnershipError("Refusing changed/foreign process identity")
            self.observed.setdefault(identity_key(current), dict(current))
            try:
                fd = self.ops.open_pidfd(current["pid"])
            except ProcessLookupError:
                return False
            # Recheck after open; pidfd remains bound even if the numeric PID is reused later.
            table, children = self._scan()
            current = table.get(expected["pid"])
            if current is None and self.ops.exited(fd):
                return False
            if not same_identity(expected, current) or expected["pid"] not in children:
                raise OwnershipError("Ownership changed after pidfd acquisition")
            if self.ops.exited(fd):
                return False
            try:
                self.ops.send(fd, signum)
            except ProcessLookupError:
                return False
            self.signals.append({"identity": dict(expected), "signal": signal.Signals(signum).name})
            return True
        except BaseException as error:
            self._error("signal", error)
            raise OwnershipError("Owned descendant signalling failed", self.last_receipt) from error
        finally:
            if fd is not None:
                try:
                    self.ops.close_pidfd(fd)
                except BaseException as error:
                    self._error("pidfd-close", error)
                    raise OwnershipError("Could not close owned pidfd", self.last_receipt) from error

    def barrier(self, natural_timeout=10, term_timeout=15, kill_timeout=10):
        """A thrown current call NEVER exposes an authoritative older absence.

        The outer guard includes budget validation, clock/sleep and asynchronous
        interruptions, not only census/syscall failures inside the disposal loop.
        Even an observed final absence on an error path is diagnostic, not authority.
        """
        self.last_receipt = self._receipt(False)
        try:
            self.barrier_generation += 1
            self.last_receipt = self._receipt(False)
            return self._dispose(natural_timeout, term_timeout, kill_timeout)
        except BaseException as error:
            observed_absence = self.last_receipt.get("absent") is True
            self._error("barrier-operation", error)
            self.last_receipt["absence_observed_before_error"] = observed_absence
            raise OwnershipError("Current child barrier did not complete safely", self.last_receipt) from error

    def _dispose(self, natural_timeout, term_timeout, kill_timeout):
        """Bound natural exit → once-per-identity TERM/KILL → join, across ALL sessions.

        Census/signalling errors do not skip later safe attempts. Two independent
        empty censuses plus no still-live anchored identity prove absence. A forced
        but absent receipt allows safe capture, NOT PASS; errors/unknown raise.
        """
        if not self.active or any(not isinstance(t, (int, float)) or not 0 <= t <= 600
                                  for t in (natural_timeout, term_timeout, kill_timeout)):
            raise OwnershipError("Invalid child barrier state/budget", self.last_receipt)
        empty = 0
        for signum, budget in ((None, natural_timeout), (signal.SIGTERM, term_timeout), (signal.SIGKILL, kill_timeout)):
            deadline, sent = self.ops.clock() + budget, set()
            while True:
                try:
                    children = self.track()
                except BaseException as error:
                    self._error("barrier-census", error)
                    children = None
                if children == []:
                    empty += 1
                    if empty >= 2:
                        self.last_receipt = self._receipt(True)
                        if self.errors:
                            raise OwnershipError("Child disposal had an ownership error", self.last_receipt)
                        return self.last_receipt
                else:
                    empty = 0
                if children:
                    # Reverse PID is not an ownership decision; fresh full-tree checks
                    # in signal_owned decide each target, including newly adopted children.
                    for row in sorted(children, key=lambda r: r["pid"], reverse=True):
                        try:
                            key = identity_key(row)
                            if signum is not None and row["state"] != "Z" and key not in sent:
                                sent.add(key)  # At most one attempt per identity/phase, even on error.
                                self.signal_owned(row, signum)
                            if self.ops.reap(row["pid"]):
                                self.reaped.append(row["pid"])
                        except BaseException as error:
                            self._error("barrier-child", error)
                if self.ops.clock() >= deadline:
                    break
                self.ops.sleep(min(0.05, max(0, deadline - self.ops.clock())))
        self.last_receipt = self._receipt(False)
        raise OwnershipError("Owned descendants remain/are unknown after bounded disposal", self.last_receipt)

    def restore(self):
        if not self.active and not self.restoration_pending:
            return {"active": False, "never_acquired_or_already_restored": True}
        try:
            # A previous failure is sticky evidence, but safe proven disposal still
            # permits restoring this process-local setting. Unknown/residue does not.
            for _ in range(2):
                _table, children = self._scan()
                if children:
                    raise OwnershipError("Cannot restore while descendants remain")
            if self.ops.get_subreaper() not in (0, 1):
                raise OwnershipError("Cannot reconcile current child-subreaper state")
            self.ops.set_subreaper(self.previous)
            if self.ops.get_subreaper() != self.previous:
                raise OwnershipError("Child-subreaper restoration readback differs")
            # An interrupted restoration return/readback keeps the debt for a retry.
            self.active = False
            self.restoration_pending = False
            return {"active": False, "restored_previous_setting": self.previous}
        except BaseException as error:
            self._error("restore", error)
            raise OwnershipError("Child ownership cannot yet be released", self.last_receipt) from error
