"""Read-only verification prerequisites: cross-platform disk, macOS power/display.

An observation gap is not proof of sleep. Preserve the gap and any matching macOS
Sleep/Wake log lines rather than subtracting guessed sleep time from test timings.
"""
from __future__ import annotations

import json
import math
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path


class VerificationEnvironmentError(RuntimeError):
    pass


GIB = 1024 ** 3


def _disk_setting(name: str, value: float | None, default: float) -> int:
    try:
        gib = float(os.environ.get(name, default) if value is None else value)
        if not math.isfinite(gib) or gib < 0:
            raise ValueError()
        return int(gib * GIB)
    except (ValueError, TypeError, OverflowError):
        raise VerificationEnvironmentError(f"{name} must be a finite non-negative GiB value") from None


class DiskBudget:
    """Admission budgets the whole run; sampling protects the floor throughout it.

    Volume-wide free-space changes include other writers. Never present them as
    this checkout's footprint, reclaimable bytes, or an attribution of consumption.
    This observer neither reserves filesystem blocks nor removes anything.
    """
    def __init__(self, path: Path, *, probe=shutil.disk_usage, floor_gib=None,
                 reserve_gib=None, planned_gib=None):
        self.path, self.probe = path.resolve(), probe
        self.floor = _disk_setting("FIELD_VERIFY_DISK_FLOOR_GIB", floor_gib, 8)
        self.reserve = _disk_setting("FIELD_VERIFY_DISK_RESERVE_GIB", reserve_gib, 0)
        self.planned = _disk_setting("FIELD_VERIFY_DISK_PLANNED_GIB", planned_gib, 0)
        self.admitted = False
        self.start_free: int | None = None
        self.minimum_free: int | None = None

    def sample(self) -> dict:
        result = {"path": str(self.path), "freeBytes": None,
                  "protectedBytes": self.floor + self.reserve}
        try:
            free = self.probe(self.path).free
            if not isinstance(free, int) or free < 0:
                raise ValueError("invalid free-space reading")
            result["freeBytes"] = free
            if self.start_free is None:
                self.start_free = free
            self.minimum_free = free if self.minimum_free is None else min(free, self.minimum_free)
        except (OSError, ValueError) as exc:
            result["probeError"] = str(exc)
        return result

    def admit(self, snapshot: dict):
        if self.admitted:
            return
        required = self.floor + self.reserve + self.planned
        free = snapshot.get("freeBytes")
        if free is None or free < required:
            available = "unknown" if free is None else f"{free / GIB:.2f} GiB"
            raise VerificationEnvironmentError(
                f"disk budget: {available} available at {self.path}; need {required / GIB:.2f} GiB "
                f"(floor {self.floor / GIB:g} + reserve {self.reserve / GIB:g} + "
                f"planned whole-run growth {self.planned / GIB:g}). No cleanup was attempted.")
        self.admitted = True

    def report(self) -> dict:
        return {"path": str(self.path), "floorBytes": self.floor, "reserveBytes": self.reserve,
                "plannedBytes": self.planned, "admitted": self.admitted,
                "initialFreeBytes": self.start_free, "minimumFreeBytes": self.minimum_free,
                "maxObservedVolumeLossBytes": (None if self.start_free is None else
                                              self.start_free - self.minimum_free),
                "attribution": "whole volume, including other writers; not checkout consumption"}


def parse_power(text: str) -> dict:
    ac = True if "'AC Power'" in text else False if "'Battery Power'" in text else None
    percent = re.search(r"\b(\d{1,3})%;", text)
    return {"onAC": ac, "batteryPercent": int(percent[1]) if percent else None}


def parse_display(text: str) -> bool | None:
    try:
        data = json.loads(text)["SPDisplaysDataType"]
        if not isinstance(data, list):
            return None
        displays = [d for gpu in data for d in gpu.get("spdisplays_ndrvs", [])]
        return any(d.get("spdisplays_online") == "spdisplays_yes" and
                   d.get("spdisplays_main") == "spdisplays_yes" for d in displays)
    except (ValueError, KeyError, TypeError, AttributeError):
        return None


def _read(command: list[str], timeout: float = 5) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
        return result.stdout if result.returncode == 0 else ""
    except (OSError, subprocess.SubprocessError):
        return ""


def probe_environment() -> dict:
    if sys.platform != "darwin":
        return {"supported": False, "platform": sys.platform}
    return {"supported": True, "platform": sys.platform,
            **parse_power(_read(["pmset", "-g", "batt"])),
            # 20 s, not the 5 s default: on a loaded machine system_profiler overran 5 s often enough
            # (2026-09-29) that "display state unknown" skipped 21 of 55 GPU smoke tests with the
            # display on the whole time. Unknown still fails closed; it just happens less falsely.
            "displayAvailable": parse_display(_read(["system_profiler", "-json", "SPDisplaysDataType"], timeout=20))}


def sleep_evidence(start: float, end: float) -> list[str]:
    if sys.platform != "darwin":
        return []
    rows = []
    for line in _read(["pmset", "-g", "log"]).splitlines():
        if not re.search(r"\b(Sleep|Wake|DarkWake)\b", line):
            continue
        try:
            stamp = datetime.strptime(line[:25], "%Y-%m-%d %H:%M:%S %z").timestamp()
        except ValueError:
            continue
        if start <= stamp <= end:
            rows.append(line)
    return rows[-100:]


def environment_problem(snapshot: dict, *, require_display: bool) -> str | None:
    disk = snapshot.get("disk")
    if disk is not None:
        if disk.get("freeBytes") is None:
            return f"disk state unknown at {disk['path']} (probe failed)"
        if disk["freeBytes"] < disk["protectedBytes"]:
            return (f"disk floor: {disk['freeBytes'] / GIB:.2f} GiB available at {disk['path']}; "
                    f"preserve {disk['protectedBytes'] / GIB:.2f} GiB including reserve. "
                    "No cleanup was attempted.")
    if not snapshot.get("supported"):
        return None
    if snapshot.get("onAC") is None:
        return "power state unknown (probe failed)"
    if not snapshot["onAC"] and (snapshot.get("batteryPercent") is None or snapshot["batteryPercent"] < 80):
        return f"power: battery {snapshot.get('batteryPercent')}%; long verification requires AC or at least 80%"
    if require_display and snapshot.get("displayAvailable") is not True:
        return "no display" if snapshot.get("displayAvailable") is False else "display state unknown (probe failed)"
    return None


class VerificationEnvironment:
    """One sampler per run, including startup and gaps between individual tests.

    Every action gets a checkpoint. Even if power/display recovers before the
    action returns, an observed loss during that action invalidates its result.
    """
    def __init__(self, *, probe=probe_environment, clock=time.time, evidence=sleep_evidence,
                 sample_interval: float = 15, gap_seconds: float = 45,
                 disk_path: Path | None = None, disk_probe=shutil.disk_usage,
                 disk_floor_gib=None, disk_reserve_gib=None, disk_planned_gib=None):
        self.probe, self.clock, self.evidence = probe, clock, evidence
        self.disk = DiskBudget(disk_path or Path.cwd(), probe=disk_probe,
                               floor_gib=disk_floor_gib, reserve_gib=disk_reserve_gib,
                               planned_gib=disk_planned_gib)
        self.started_at = clock()
        self.sample_interval, self.gap_seconds = sample_interval, gap_seconds
        self.snapshot: dict = {}
        self.events: list[dict] = []
        self.last_tick: float | None = None
        self.last_sample: float | None = None
        self.sample_count = 0
        self._lock = threading.RLock()
        self._sample_lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def sample(self, *, force=False):
        with self._sample_lock:
            self._sample(force=force)

    def _sample(self, *, force=False):
        now = self.clock()
        with self._lock:
            previous = self.last_tick
            self.last_tick = now
            if previous is not None and (now - previous > self.gap_seconds or now < previous):
                self.events.append({"kind": "observation-gap", "from": previous, "to": now,
                                    "wallSeconds": now - previous,
                                    "message": "unobserved interval or clock change; not a runtime measurement",
                                    "sleepWakeEvidence": self.evidence(previous, now)})
            if not force and self.last_sample is not None and now - self.last_sample < self.sample_interval:
                return
        snapshot = self.probe()
        with self._lock:
            snapshot = {**snapshot, "disk": self.disk.sample()}
            self.sample_count += 1
            self.last_sample = now
            if snapshot != self.snapshot:
                self.snapshot = dict(snapshot)
                self.events.append({"kind": "state", "at": now, "snapshot": dict(snapshot)})

    def start(self):
        self.sample(force=True)
        def loop():
            while not self._stop.wait(1):
                try:
                    self.sample()
                except Exception as exc:
                    with self._lock:
                        self.snapshot = {"supported": True, "probeError": str(exc)}
                        self.events.append({"kind": "state", "at": self.clock(), "snapshot": dict(self.snapshot)})
        self._thread = threading.Thread(target=loop, name="verification-environment", daemon=True)
        self._thread.start()
        return self

    def checkpoint(self, *, require_display: bool) -> int:
        self.sample()
        with self._lock:
            problem = environment_problem(self.snapshot, require_display=require_display)
            if problem:
                raise VerificationEnvironmentError(problem)
            self.disk.admit(self.snapshot["disk"])
            return len(self.events)

    def check(self, checkpoint: int, *, require_display: bool, force: bool = False):
        self.sample(force=force)
        with self._lock:
            for event in self.events[checkpoint:]:
                problem = (event["message"] if event["kind"] == "observation-gap" else
                           environment_problem(event["snapshot"], require_display=require_display))
                if problem:
                    raise VerificationEnvironmentError(problem)
            problem = environment_problem(self.snapshot, require_display=require_display)
            if problem:
                raise VerificationEnvironmentError(problem)

    def report(self) -> dict:
        with self._lock:
            return {"startedAtEpochSec": self.started_at, "reportedAtEpochSec": self.clock(),
                    "sampleIntervalSec": self.sample_interval, "gapThresholdSec": self.gap_seconds,
                    "sampleCount": self.sample_count, "latest": dict(self.snapshot),
                    "diskBudget": self.disk.report(),
                    "events": list(self.events)}

    def close(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=15)

    def write_report(self, path: Path, outcomes: list[dict]):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({**self.report(), "outcomes": outcomes}, indent=2) + "\n")
