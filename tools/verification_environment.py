"""Read-only macOS verification prerequisites; unsupported hosts are explicitly reported.

An observation gap is not proof of sleep. Preserve the gap and any matching macOS
Sleep/Wake log lines rather than subtracting guessed sleep time from test timings.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path


class VerificationEnvironmentError(RuntimeError):
    pass


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
            "displayAvailable": parse_display(_read(["system_profiler", "-json", "SPDisplaysDataType"]))}


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
                 sample_interval: float = 15, gap_seconds: float = 45):
        self.probe, self.clock, self.evidence = probe, clock, evidence
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
                    "events": list(self.events)}

    def close(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=15)

    def write_report(self, path: Path, outcomes: list[dict]):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({**self.report(), "outcomes": outcomes}, indent=2) + "\n")
