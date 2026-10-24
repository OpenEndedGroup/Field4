#!/usr/bin/env python3
"""How fast Field feels: time to first run, second run, warm runs, edits, shader compiles.

Boots its own Field on a fresh port with a bench workspace holding copies of the first-contact
templates (`templates/first-steps`, `templates/first-graphics`), then drives a few starting
points the way a person does, step by step, through the backdoor's own command dispatcher:

  kotlin-println   one box, `println`: first run in a cold JVM, the same run again, warm
                   reruns, and edit-and-rerun (a different literal each time).
  first-steps      the "Start here" FLine drawing: first launch to its first drawing, the
                   Alt+Enter edit of `petals` while it runs, stop and relaunch.
  first-graphics   "Next: native graphics": renderer launch to its first frame, the triangle's
                   launch (shader compile + pipeline), a fragment-shader edit and relaunch,
                   a Kotlin edit and relaunch.

Each step records the client's wall time (command sent -> verdict or condition, what a person
waits for), the time to the box's first output, and the server's `LatencySpans` that started
inside the step (engine build, bootstrap, compile, eval, shader compile, pipeline create, ...),
from `GET /api/perf/spans`.

Results go to `build/feel-bench/<stamp>.json`, and a summary line is appended to the
machine-local ledger `~/.afield/feel-bench/ledger.jsonl` (override with `--ledger`), keyed by
commit and host, so `compare` can show a change against the previous run on this machine.

  uv run --project . --group backdoor python tools/feel_bench.py run
  uv run --project . --group backdoor python tools/feel_bench.py run --scenario kotlin-println --reps 8
  uv run --project . --group backdoor python tools/feel_bench.py compare            # last two on this host
  uv run --project . --group backdoor python tools/feel_bench.py compare A.json B.json

The ratchet: a committed baseline per machine (`dev_docs/testing/feel_baselines/<host>.json`), made
from several runs so each step's band reflects how much that step moves between runs, and a check
that fails when a run falls outside it. `dev_docs/testing/FEEL_BENCHMARK_BASELINES.md` is generated
from the baselines.

  ... feel_bench.py run --reps 100 --cold-runs 10 --require-ac --max-load 4     # a nightly-sized run
  ... feel_bench.py noise R1.json R2.json R3.json     # step-by-step spread of several runs
  ... feel_bench.py baseline R1.json R2.json R3.json  # write this host's baseline, regenerate the page
  ... feel_bench.py check [RESULT] [--page OUT.md]    # exit 1 on a regression against the baseline

Numbers are only comparable on one machine with the same load: close the Electron app and other
Fields first, and keep the laptop on power (long verifications are power-gated).
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import platform
import re
import shlex
import shutil
import socket
import statistics
import subprocess
import sys
import time
import urllib.request
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path
from typing import Any, Awaitable, Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from .field_python_env import ensure_repo_python  # noqa: E402
except ImportError:  # Executed as tools/feel_bench.py rather than imported as a package.
    from field_python_env import ensure_repo_python  # noqa: E402

ensure_repo_python()

import field_backdoor as fb  # noqa: E402

REPO_ROOT = fb.REPO_ROOT
OUT_ROOT = REPO_ROOT / "build" / "feel-bench"
DEFAULT_LEDGER = Path.home() / ".afield" / "feel-bench" / "ledger.jsonl"
SCHEMA = 1


# --------------------------------------------------------------------------- #
# Measurement
# --------------------------------------------------------------------------- #
@dataclass
class Step:
    scenario: str
    label: str
    phase: str  # first | second | warm | edit | shader-reload | shader-update | frame | ...
    ok: bool
    wallMs: float
    firstOutputMs: float | None = None
    spans: dict[str, dict[str, float]] = field(default_factory=dict)
    note: str = ""


class Bench:
    def __init__(self, client: fb.FieldBackdoor, port: int, timeout: float, think: float) -> None:
        self.client = client
        self.think = think
        self.port = port
        self.timeout = timeout
        self.ssl = fb.make_unverified_ssl_context()
        self.span_cursor = 0
        self.steps: list[Step] = []
        # Server clock minus this one, in seconds; see [calibrate].
        self.clock_offset = 0.0

    # -- server spans -------------------------------------------------------
    def spans_since(self, cursor: int) -> dict[str, Any]:
        url = f"https://localhost:{self.port}/api/perf/spans?since={cursor}"
        with urllib.request.urlopen(url, context=self.ssl, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))

    def calibrate(self, samples: int = 9) -> float:
        """Measure the server's clock against this one: the offset from the round trip with the
        smallest delay, assuming the server read its clock halfway through (NTP's estimate).
        Span times are converted with it, so a step's window and a span's start agree."""
        best: tuple[float, float] | None = None
        for _ in range(samples):
            sent = time.time()
            now = self.spans_since(1 << 62)["nowEpochMicros"] / 1e6
            received = time.time()
            if best is None or received - sent < best[0]:
                best = (received - sent, now - (sent + received) / 2)
        assert best is not None
        self.clock_offset = best[1]
        return self.clock_offset

    def span_start(self, span: dict[str, Any]) -> float:
        """A span's start on this clock, in seconds."""
        return span["startEpochMicros"] / 1e6 - self.clock_offset

    def drain_spans(self) -> list[dict[str, Any]]:
        snap = self.spans_since(self.span_cursor)
        self.span_cursor = int(snap.get("nextSeq", self.span_cursor))
        return snap.get("spans", [])

    # -- one step -------------------------------------------------------------
    async def command(self, line: str) -> dict[str, Any]:
        parsed = fb.parse_command(line)
        return await fb.dispatch_command(self.client, parsed, line)

    async def step(
        self,
        scenario: str,
        label: str,
        phase: str,
        action: Callable[[], Awaitable[dict[str, Any]]],
        *,
        box: str | None = None,
        settle: float = 0.0,
    ) -> Step:
        """Time [action]; attribute the spans that started inside it and the box's first output.

        First waits [think] seconds, off the clock: a person reads the result and types the next edit
        before running again, and work Field does in that gap (a spare engine) is work they never wait
        for. A `frame` step follows its launch at once, so it does not wait.
        """
        if phase != "frame" and self.think > 0:
            await asyncio.sleep(self.think)
        self.drain_spans()
        start_seq = self.client.event_seq
        t0 = time.time()
        end: float | None = None
        try:
            result = await action()
            ok = bool(result.get("ok"))
            end = result.get("endEpoch")
            note = "" if ok else json.dumps(result.get("data"))[:400]
        except Exception as exc:  # noqa: BLE001 - a failed step is a measurement, not a crash
            ok, note = False, f"{type(exc).__name__}: {exc}"
        # An action that finishes on the server with no reply reports when it ended (`endEpoch`);
        # the client only noticed later, by polling.
        observed = time.time()
        t1 = end if end is not None else observed
        if settle:
            await asyncio.sleep(settle)
        first_output = None
        for event in self.client.events_since(start_seq):
            name = event.get("name") or event.get("messageName")
            if name in {"output", "set-output"} and (box is None or event.get("uid") == box):
                first_output = (float(event["receivedAt"]) - t0) * 1000
                break
        spans: dict[str, dict[str, float]] = {}
        # Every span recorded since the drain at the start of the step belongs to it, except one that
        # began well before it (background work finishing). Not a tight window on start times: a span
        # that starts as the server receives the message sits within the clock offset's error of t0.
        for span in self.drain_spans():
            if self.span_start(span) < t0 - 0.05:
                continue
            agg = spans.setdefault(span["name"], {"ms": 0.0, "count": 0, "maxMs": 0.0})
            ms = span["durationMicros"] / 1000
            agg["ms"] = round(agg["ms"] + ms, 2)
            agg["count"] += 1
            agg["maxMs"] = round(max(agg["maxMs"], ms), 2)
        step = Step(scenario, label, phase, ok, round((t1 - t0) * 1000, 1),
                    None if first_output is None else round(first_output, 1), spans, note)
        self.steps.append(step)
        flag = "ok " if ok else "FAIL"
        out = "" if step.firstOutputMs is None else f"  first-output {step.firstOutputMs:8.1f} ms"
        print(f"  [{flag}] {scenario:15s} {label:34s} {step.wallMs:9.1f} ms{out}", flush=True)
        if not ok:
            print(f"         {note}", flush=True)
        return step

    # -- helpers ---------------------------------------------------------------
    async def execute_fragment(self, uid: str, code: str, line: int) -> dict[str, Any]:
        """What Alt+Enter on a line sends: `execute-fragment`, awaited to its verdict."""
        client = self.client
        reply_name = f"frag_{os.urandom(8).hex()}"
        start_seq = client.event_seq
        await client.send_message(
            "execute-fragment",
            {"uid": uid, "type": "code", "line": line, "code": code, "singleLine": True,
             "property": "code", "requestId": reply_name},
            reply_to=reply_name,
            reply_context={"uid": uid, "command": "execute-fragment"},
        )

        def predicate():
            for event in client.events_since(start_seq):
                payload = event.get("payload") or {}
                if event.get("name") == "execution-verdict" and payload.get("requestId") == reply_name:
                    return payload
            return None

        try:
            verdict = await client.wait_for(predicate, self.timeout, f"fragment:{uid}")
        except asyncio.TimeoutError:
            return {"ok": False, "data": {"error": "no verdict"}}
        finally:
            client.reply_contexts.pop(reply_name, None)
        return {"ok": bool(verdict.get("success")), "data": verdict}

    async def wait_span(self, name: str, timeout: float, *, box: str | None = None) -> dict[str, Any]:
        """Poll the server until a span called [name] has been recorded (for actions with no verdict).

        `ok` is the span's own `success=` detail when it has one; `endEpoch` is when it ended.
        """
        deadline = time.monotonic() + timeout
        cursor = self.span_cursor
        while time.monotonic() < deadline:
            snap = self.spans_since(cursor)
            for span in snap.get("spans", []):
                if span["name"] == name and (box is None or span.get("box") == box):
                    end = self.span_start(span) + span["durationMicros"] / 1e6
                    return {"ok": "success=false" not in (span.get("detail") or ""), "endEpoch": end,
                            "data": {"detail": span.get("detail")}}
            await asyncio.sleep(0.01)
        return {"ok": False, "data": {"error": f"no {name} span within {timeout}s"}}

    async def edit_and_act(self, uid: str, prop: str, value: str, action_id: str, span: str) -> dict[str, Any]:
        """What Alt+Enter (Reload Shader) or Alt+Shift+Enter (Update Shader) in a shader editor sends:
        the edited text as `set-string`, then `perform-action`; done when the action's span ends."""
        await self.client.send_message("set-string", {"uid": uid, "property": prop, "value": value})
        await self.client.send_message(
            "perform-action", {"uid_currentSelection": [uid], "uid_action": action_id, "uid_property": prop})
        return await self.wait_span(span, self.timeout, box=uid)


# --------------------------------------------------------------------------- #
# Scenarios
# --------------------------------------------------------------------------- #
def template_code(template: str, uid: str, prop: str, ext: str) -> str:
    return (REPO_ROOT / "templates" / template / f"{uid}_{prop}_.{ext}").read_text(encoding="utf-8")


async def scenario_kotlin_println(b: Bench, reps: int) -> None:
    name = "kotlin-println"
    await b.command("open feel_println --create")
    created = await b.command("new-box --name feel_println")
    uid = ((created.get("data") or {}).get("box") or {}).get("uid") or "@last"
    code = 'println("hello")'
    run = lambda value: (lambda: b.command(f"run {uid} --value {shlex.quote(value)}"))  # noqa: E731
    await b.step(name, "first run", "first", run(code), box=uid)
    await b.step(name, "same code again", "second", run(code), box=uid)
    for i in range(reps):
        await b.step(name, f"warm rerun {i + 1}", "warm", run(code), box=uid)
    for i in range(reps):
        await b.step(name, f"edit + rerun {i + 1}", "edit", run(f'println("hello {i + 1}")'), box=uid)


async def scenario_first_steps(b: Bench, reps: int) -> None:
    name = "first-steps"
    uid = "first_steps_drawing"
    await b.command("open feel_first_steps")
    launch = lambda: b.command(f"launch {uid}")  # noqa: E731
    stop = lambda: b.command(f"stop {uid}")  # noqa: E731
    # The first drawing is the moment a newcomer sees something; settle long enough to catch it.
    await b.step(name, "first launch", "first", launch, box=uid, settle=0.5)
    for i in range(reps):
        petals = 6 + i % 3
        await b.step(name, f"Alt+Enter petals={petals} {i + 1}", "edit",
                     lambda petals=petals: b.execute_fragment(uid, f"var petals = {petals}", 1), box=uid)
    await b.step(name, "stop", "stop", stop)
    await b.step(name, "second launch", "second", launch, box=uid, settle=0.5)
    for i in range(reps):
        await stop()
        await b.step(name, f"warm relaunch {i + 1}", "warm", launch, box=uid, settle=0.3)
    await stop()


async def scenario_first_graphics(b: Bench, reps: int) -> None:
    name = "first-graphics"
    renderer, tri = "first_graphics_renderer", "first_graphics_triangle"
    await b.command("open feel_first_graphics")
    fragment = template_code("first-graphics", tri, "fragment", "glsl")
    code = template_code("first-graphics", tri, "code", "kt")
    await b.step(name, "renderer launch", "first", lambda: b.command(f"launch {renderer}"))
    await b.step(name, "renderer first frames", "frame",
                 lambda: b.command(f"wait-native-renderer {renderer} --frames 3"))
    await b.step(name, "triangle first launch", "first", lambda: b.command(f"launch {tri}"), box=tri)
    await b.step(name, "triangle frames", "frame",
                 lambda: b.command(f"wait-native-renderer {renderer} --frames 3"))
    await b.step(name, "triangle relaunch (same)", "second", lambda: b.command(f"launch {tri}"), box=tri)

    def tinted(i: int) -> str:
        return fragment.replace("vec4(1.0, 0.8, 0.4, 1.0)", f"vec4(1.0, {0.5 + 0.1 * (i % 5):.1f}, 0.4, 1.0)")

    # The shader loop: edit the fragment shader, Alt+Enter (Reload Shader), or Alt+Shift+Enter
    # (Update Shader, which keeps the module when the interface is unchanged).
    for i in range(reps):
        await b.step(name, f"fragment edit + Reload Shader {i + 1}", "shader-reload",
                     lambda i=i: b.edit_and_act(tri, "fragment", tinted(i), "moduleHere_reload", "shader.reload"))
    for i in range(reps):
        await b.step(name, f"fragment edit + Update Shader {i + 1}", "shader-update",
                     lambda i=i: b.edit_and_act(tri, "fragment", tinted(i + 1), "moduleHere_update", "shader.update"))
    for i in range(reps):
        async def shader_relaunch(edited: str = tinted(i + 2)) -> dict[str, Any]:
            written = await b.command(f"set {tri} fragment {shlex.quote(edited)}")
            return written if not written.get("ok") else await b.command(f"launch {tri}")

        await b.step(name, f"fragment edit + relaunch {i + 1}", "shader-relaunch", shader_relaunch, box=tri)
    for i in range(reps):
        edited = code.replace("0.5, 0.5, 0", f"0.5, {0.4 + 0.02 * (i + 1):.2f}, 0")
        await b.step(name, f"kotlin edit + relaunch {i + 1}", "edit",
                     lambda edited=edited: b.command(f"launch {tri} --value {shlex.quote(edited)}"), box=tri)
    await b.command(f"stop {tri}")
    await b.command(f"stop {renderer}")


SCENARIOS: dict[str, Callable[[Bench, int], Awaitable[None]]] = {
    "kotlin-println": scenario_kotlin_println,
    "first-steps": scenario_first_steps,
    "first-graphics": scenario_first_graphics,
}
GRAPHICS = {"first-graphics"}


# --------------------------------------------------------------------------- #
# Server lifecycle
# --------------------------------------------------------------------------- #
def free_port(start: int = 9530) -> int:
    for port in range(start, start + 200):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return port
    raise RuntimeError("no free port")


def prepare_workspace(root: Path) -> Path:
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    for template, doc in (("first-steps", "feel_first_steps"), ("first-graphics", "feel_first_graphics")):
        shutil.copytree(REPO_ROOT / "templates" / template, root / doc)
    return root


def start_field(port: int, workspace: Path, log_path: Path) -> tuple[subprocess.Popen[Any], dict[str, Any]]:
    """`./gradlew runJvm` on [port]; returns the process and its boot phases in ms."""
    log_file = log_path.open("w", encoding="utf-8")
    cmd = fb.gradle_command("runJvm")
    cmd.append(f"--args=--workspace {shlex.quote(str(workspace))}")
    t0 = time.monotonic()
    proc = subprocess.Popen(
        cmd, cwd=str(REPO_ROOT), stdout=log_file, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        text=True, env=fb.automation_server_environment(port), start_new_session=True,
    )
    watch = fb.ManagedStartupWatch(log_path, ceiling=900.0, jvm_timeout=240.0,
                                   ceiling_knob="feel_bench's 900 s", jvm_knob="feel_bench's 240 s")
    ssl_ctx = fb.make_unverified_ssl_context()
    while True:
        watch.observe()
        if proc.poll() is not None:
            raise RuntimeError(f"Field exited early ({proc.returncode}); see {log_path}")
        if watch.expired():
            proc.terminate()
            raise RuntimeError(f"Field did not become healthy: {watch.timeout_reason()}; see {log_path}")
        if fb.health_check(port, ssl_ctx):
            break
        time.sleep(0.1)
    healthy = time.monotonic()
    jvm_at = watch.jvm_started_at or t0
    return proc, {
        "gradleMs": round((jvm_at - t0) * 1000),
        "jvmToHealthMs": round((healthy - jvm_at) * 1000),
        "spawnToHealthMs": round((healthy - t0) * 1000),
    }


def stop_field(proc: subprocess.Popen[Any], port: int) -> None:
    """SIGINT the JVM listening on [port] (Field's clean shutdown), then the Gradle wrapper."""
    try:
        pids = subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
                              capture_output=True, text=True, timeout=10).stdout.split()
    except (OSError, subprocess.SubprocessError):
        pids = []
    for pid in pids:
        try:
            os.kill(int(pid), 2)
        except OSError:
            pass
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


def machine_info() -> dict[str, Any]:
    def sh(*cmd: str) -> str:
        try:
            return subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return ""

    info: dict[str, Any] = {"host": platform.node(), "os": platform.platform(), "machine": platform.machine()}
    if sys.platform == "darwin":
        info["cpu"] = sh("sysctl", "-n", "machdep.cpu.brand_string")
        info["memBytes"] = int(sh("sysctl", "-n", "hw.memsize") or 0)
        info["onPower"] = "AC Power" in sh("pmset", "-g", "batt")
    info["cores"] = os.cpu_count()
    info["loadAverage"] = [round(x, 2) for x in os.getloadavg()]
    return info


def environment_problem(args: argparse.Namespace) -> str | None:
    """Why this machine should not be measured right now, or None. Numbers taken on battery or under
    load measure the machine, not Field, so a gated run is skipped rather than recorded."""
    info = machine_info()
    if args.require_ac and info.get("onPower") is False:
        return "on battery"
    if args.max_load is not None and info["loadAverage"][0] > args.max_load:
        return f"load average {info['loadAverage'][0]} > --max-load {args.max_load}"
    return None


def git_info() -> dict[str, Any]:
    def git(*args: str) -> str:
        return subprocess.run(["git", *args], cwd=REPO_ROOT, capture_output=True, text=True).stdout.strip()

    return {
        "commit": git("rev-parse", "HEAD"),
        "branch": git("branch", "--show-current"),
        "dirty": bool(git("status", "--porcelain", "--untracked-files=no")),
    }


# --------------------------------------------------------------------------- #
# Summary
# --------------------------------------------------------------------------- #
def pct(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(q * (len(ordered) - 1))))]


# Phases measured once per scenario, summarized under their own label; the rest repeat and group by phase.
SINGLE_PHASES = {"first", "second", "frame", "stop"}
# What a cold round keeps: the steps whose cost depends on the JVM being fresh.
COLD_PHASES = {"first", "frame"}


def summarize(steps: list[Step]) -> dict[str, dict[str, Any]]:
    """Per scenario and phase: median/p90 wall time, first output, and the median of each span."""
    out: dict[str, dict[str, Any]] = {}
    groups: dict[tuple[str, str], list[Step]] = {}
    for s in steps:
        groups.setdefault((s.scenario, s.label if s.phase in SINGLE_PHASES else s.phase), []).append(s)
    for (scenario, key), group in groups.items():
        good = [s for s in group if s.ok]
        wall = [s.wallMs for s in good]
        entry: dict[str, Any] = {"n": len(group), "failed": len(group) - len(good)}
        if wall:
            entry["wallMs"] = round(statistics.median(wall), 1)
            if len(wall) > 2:
                entry["wallP90Ms"] = round(pct(wall, 0.9), 1)
            if len(wall) >= 50:
                # Below ~50 samples the 99th percentile is just the slowest run.
                entry["wallP99Ms"] = round(pct(wall, 0.99), 1)
            outs = [s.firstOutputMs for s in good if s.firstOutputMs is not None]
            if outs:
                entry["firstOutputMs"] = round(statistics.median(outs), 1)
            names = sorted({n for s in good for n in s.spans})
            entry["spansMs"] = {
                n: round(statistics.median([s.spans.get(n, {}).get("ms", 0.0) for s in good]), 1) for n in names
            }
        out.setdefault(scenario, {})[key] = entry
    return out


def print_summary(result: dict[str, Any]) -> None:
    boot = result["boot"]
    print("\n=== Field feel benchmark ===")
    print(f"commit {result['git']['commit'][:10]}{' (dirty)' if result['git']['dirty'] else ''}  "
          f"host {result['machine']['host']}  {result['machine'].get('cpu', '')}")
    print(f"boot: gradle {boot['gradleMs']} ms, JVM->/health {boot['jvmToHealthMs']} ms, "
          f"JVM->runloop ready {boot.get('readyMillis')} ms")
    for scenario, phases in result["summary"].items():
        print(f"\n{scenario}")
        for key, e in phases.items():
            if "wallMs" not in e:
                print(f"  {key:30s}  FAILED x{e['failed']}")
                continue
            p90 = f" p90 {e['wallP90Ms']:8.1f}" if "wallP90Ms" in e else " " * 13
            fo = f"  out {e['firstOutputMs']:8.1f}" if "firstOutputMs" in e else " " * 15
            top = sorted(e["spansMs"].items(), key=lambda kv: -kv[1])[:4]
            spans = ", ".join(f"{k} {v:.0f}" for k, v in top if v >= 1)
            fail = f" ({e['failed']} failed)" if e["failed"] else ""
            print(f"  {key:30s} {e['wallMs']:9.1f} ms{p90}{fo}   {spans}{fail}")


# --------------------------------------------------------------------------- #
# Commands
# --------------------------------------------------------------------------- #
SKIPPED = 3  # exit code: the environment refused the run; see environment_problem


async def run_bench(args: argparse.Namespace) -> int:
    names = args.scenario or [n for n in SCENARIOS if not (args.no_graphics and n in GRAPHICS)]
    stamp = time.strftime("%Y%m%d-%H%M%S")
    problem = environment_problem(args)
    if problem:
        print(f"feel_bench: skipped, {problem}", flush=True)
        ledger = Path(args.ledger).expanduser()
        ledger.parent.mkdir(parents=True, exist_ok=True)
        with ledger.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"schema": SCHEMA, "stamp": stamp, "skipped": problem,
                                     "git": git_info(), "machine": machine_info()}) + "\n")
        return SKIPPED
    start_load = os.getloadavg()[0]
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    workspace = prepare_workspace(OUT_ROOT / "workspace")
    port = args.port or free_port()
    # (scenarios, reps, cold): the measured run, then --cold-runs rounds of one fresh JVM per
    # scenario at one rep, kept only for the steps a fresh JVM measures (first launch, its frames).
    rounds = [([n], args.reps, False) for n in names] if args.fresh_jvm_per_scenario else [(names, args.reps, False)]
    rounds += [([n], 1, True) for _ in range(args.cold_runs) for n in names]

    steps: list[Step] = []
    boots: list[dict[str, Any]] = []
    for group, reps, cold in rounds:
        log_path = OUT_ROOT / f"field-{stamp}-{port}.log"
        print(f"Starting Field on {port} (log {log_path}) ...", flush=True)
        proc, boot = start_field(port, workspace, log_path)
        try:
            logger = fb.SessionLogger(fb.LOG_ROOT, ["feel_bench", f"--port={port}"])
            # Attaching: the workspace is the server's, so name none (a disagreeing one is an error).
            client = fb.FieldBackdoor(port=port, workspace=None, default_timeout=args.timeout,
                                      logger=logger, attach_existing=True)
            await client.ensure_server()
            await client.connect()
            bench = Bench(client, port, args.timeout, args.think)
            boot["readyMillis"] = bench.spans_since(0).get("readyMillis")
            boot["clockOffsetMs"] = round(bench.calibrate() * 1000, 2)
            boot["scenarios"] = group
            boot["cold"] = cold
            boots.append(boot)
            print(f"  boot {boot}" + ("  (cold round)" if cold else ""), flush=True)
            try:
                for name in group:
                    await SCENARIOS[name](bench, reps)
            finally:
                if cold:
                    steps.extend(replace(s, label="cold " + s.label) for s in bench.steps if s.phase in COLD_PHASES)
                else:
                    steps.extend(bench.steps)
                await client.close()
        finally:
            stop_field(proc, port)

    result = {
        "schema": SCHEMA,
        "stamp": stamp,
        "git": git_info(),
        "machine": machine_info(),
        "reps": args.reps,
        "thinkSeconds": args.think,
        "freshJvmPerScenario": args.fresh_jvm_per_scenario,
        "coldRuns": args.cold_runs,
        "loadAverageStartEnd": [round(start_load, 2), round(os.getloadavg()[0], 2)],
        "boot": boots[0],
        "boots": boots,
        "summary": summarize(steps),
        "steps": [asdict(s) for s in steps],
    }
    out_path = OUT_ROOT / f"{stamp}.json"
    out_path.write_text(json.dumps(result, indent=2), encoding="utf-8")
    ledger = Path(args.ledger).expanduser()
    ledger.parent.mkdir(parents=True, exist_ok=True)
    with ledger.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({k: result[k] for k in ("schema", "stamp", "git", "machine", "reps", "thinkSeconds",
                                                        "freshJvmPerScenario", "coldRuns", "loadAverageStartEnd",
                                                        "boot", "summary")}) + "\n")
    print_summary(result)
    print(f"\nwrote {out_path}\nappended {ledger}")
    return 0 if all(s.ok for s in steps) else 1


def load(path_or_entry: Any) -> dict[str, Any]:
    if isinstance(path_or_entry, dict):
        return path_or_entry
    return json.loads(Path(path_or_entry).read_text(encoding="utf-8"))


def compare(args: argparse.Namespace) -> int:
    if args.files:
        if len(args.files) != 2:
            print("compare takes two result files, or none for the last two ledger entries on this host")
            return 2
        base, head = load(args.files[0]), load(args.files[1])
    else:
        host = platform.node()
        ledger = Path(args.ledger).expanduser()
        entries = [json.loads(l) for l in ledger.read_text(encoding="utf-8").splitlines() if l.strip()]
        entries = [e for e in entries if e.get("machine", {}).get("host") == host]
        if len(entries) < 2:
            print(f"need two ledger entries for {host} in {ledger}")
            return 2
        base, head = entries[-2], entries[-1]
    print(f"base {base['stamp']} {base['git']['commit'][:10]}  ->  head {head['stamp']} {head['git']['commit'][:10]}")
    if base.get("thinkSeconds") != head.get("thinkSeconds"):
        print(f"  note: think time differs ({base.get('thinkSeconds')} s -> {head.get('thinkSeconds')} s); rerun timings are not comparable")
    for key in ("gradleMs", "jvmToHealthMs", "readyMillis"):
        a, b = base["boot"].get(key), head["boot"].get(key)
        if a and b:
            print(f"  boot {key:26s} {a:9.0f} -> {b:9.0f}  {100 * (b - a) / a:+6.1f}%")
    for scenario, phases in head["summary"].items():
        print(scenario)
        for key, e in phases.items():
            old = base["summary"].get(scenario, {}).get(key, {})
            a, b = old.get("wallMs"), e.get("wallMs")
            if a and b:
                print(f"  {key:30s} {a:9.1f} -> {b:9.1f} ms  {100 * (b - a) / a:+6.1f}%")
            else:
                print(f"  {key:30s} {a} -> {b}")
    return 0


# --------------------------------------------------------------------------- #
# Baselines, the ratchet, and the page
# --------------------------------------------------------------------------- #
BASELINE_ROOT = REPO_ROOT / "dev_docs" / "testing" / "feel_baselines"
BASELINE_PAGE = REPO_ROOT / "dev_docs" / "testing" / "FEEL_BENCHMARK_BASELINES.md"
# The narrowest band a step gets, whatever its measured spread: a median may move this much and a
# p90 this much before `check` calls it a regression. A step's own band is three times the largest
# deviation of one run from the others in the runs its baseline was made from, if that is wider.
MIN_MEDIAN_PCT = 15.0
MIN_P90_PCT = 25.0
SPREAD_MULTIPLE = 3.0
# Below this many milliseconds a change is not a regression however large in percent: a 10 ms
# shader reload that becomes 13 ms is +30% and nothing anyone feels.
FLOOR_MS = 5.0


def host_slug(host: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", host.lower().removesuffix(".local")).strip("-")


def baseline_path(host: str) -> Path:
    return BASELINE_ROOT / f"{host_slug(host)}.json"


def newest_result() -> Path:
    results = sorted(OUT_ROOT.glob("2*.json"))
    if not results:
        raise SystemExit(f"no results in {OUT_ROOT}; run `feel_bench.py run` first")
    return results[-1]


def step_entries(result: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
    return {(scenario, key): entry for scenario, phases in result["summary"].items()
            for key, entry in phases.items() if "wallMs" in entry}


def spread(runs: list[dict[str, Any]]) -> dict[tuple[str, str], dict[str, float]]:
    """Per step present in every run: the largest deviation, in percent, of one run's median (and
    p90) from the median of all of them."""
    entries = [step_entries(r) for r in runs]
    common = set(entries[0]).intersection(*entries[1:])
    out: dict[tuple[str, str], dict[str, float]] = {}
    for key in common:
        row: dict[str, float] = {}
        for field_name, label in (("wallMs", "medianPct"), ("wallP90Ms", "p90Pct")):
            values = [e[key][field_name] for e in entries if field_name in e[key]]
            if len(values) < 2:
                continue
            centre = statistics.median(values)
            row[label] = round(max(abs(v - centre) for v in values) / centre * 100, 1) if centre else 0.0
        out[key] = row
    return out


def make_baseline(runs: list[dict[str, Any]]) -> dict[str, Any]:
    hosts = {r["machine"]["host"] for r in runs}
    thinks = {r.get("thinkSeconds") for r in runs}
    if len(hosts) != 1 or len(thinks) != 1:
        raise SystemExit(f"a baseline is one host at one think time; got hosts {hosts}, think {thinks}")
    entries = [step_entries(r) for r in runs]
    noise = spread(runs) if len(runs) > 1 else {}
    steps: dict[str, dict[str, Any]] = {}
    common = set(entries[0]).intersection(*entries[1:])
    # In the order the last run met them: first launch before its frames before the reruns.
    for key in [k for k in entries[-1] if k in common]:
        def mid(field_name: str) -> float | None:
            values = [e[key][field_name] for e in entries if field_name in e[key]]
            return round(statistics.median(values), 1) if values else None
        last = entries[-1][key]
        row: dict[str, Any] = {"n": last["n"], "wallMs": mid("wallMs")}
        for field_name in ("wallP90Ms", "wallP99Ms"):
            if (value := mid(field_name)) is not None:
                row[field_name] = value
        measured = noise.get(key, {})
        row["spreadPct"] = measured
        row["tolMedianPct"] = max(MIN_MEDIAN_PCT, round(SPREAD_MULTIPLE * measured.get("medianPct", 0.0), 1))
        row["tolP90Pct"] = max(MIN_P90_PCT, round(SPREAD_MULTIPLE * measured.get("p90Pct", 0.0), 1))
        row["spansMs"] = dict(sorted(last.get("spansMs", {}).items(), key=lambda kv: -kv[1])[:3])
        steps.setdefault(key[0], {})[key[1]] = row
    head = runs[-1]
    return {
        "schema": SCHEMA,
        "host": head["machine"]["host"],
        "machine": {k: head["machine"].get(k) for k in ("cpu", "cores", "memBytes", "os")},
        "commit": head["git"]["commit"],
        "stamps": [r["stamp"] for r in runs],
        "reps": head["reps"],
        "coldRuns": head.get("coldRuns", 0),
        "thinkSeconds": head.get("thinkSeconds"),
        "loadAverages": [r.get("loadAverageStartEnd") for r in runs],
        "steps": steps,
    }


def check_against(result: dict[str, Any], baseline: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    """Each baseline step's verdict for [result]: ok, faster, regressed or missing; and problems that
    make the comparison meaningless (another host, another think time)."""
    problems = []
    if result["machine"]["host"] != baseline["host"]:
        problems.append(f"result is from {result['machine']['host']}, baseline from {baseline['host']}")
    if result.get("thinkSeconds") != baseline.get("thinkSeconds"):
        problems.append(f"think time {result.get('thinkSeconds')} s against the baseline's {baseline.get('thinkSeconds')} s")
    head = {(sc, k): e for (sc, k), e in step_entries(result).items()}
    failed = {(sc, k) for sc, phases in result["summary"].items() for k, e in phases.items() if e.get("failed")}
    rows = []
    for scenario, phases in baseline["steps"].items():
        for key, base in phases.items():
            now = head.get((scenario, key))
            row: dict[str, Any] = {"scenario": scenario, "step": key, "base": base, "now": now}
            if now is None or (scenario, key) in failed:
                row["verdict"] = "missing" if now is None else "failed"
            else:
                verdict = "ok"
                for field_name, tol_name, floor in (("wallMs", "tolMedianPct", FLOOR_MS), ("wallP90Ms", "tolP90Pct", 2 * FLOOR_MS)):
                    if field_name not in base or field_name not in now:
                        continue
                    b, n, tol = base[field_name], now[field_name], base[tol_name] / 100
                    if n > b * (1 + tol) and n - b > floor:
                        verdict = "regressed"
                    elif field_name == "wallMs" and n < b * (1 - tol) and b - n > floor and verdict == "ok":
                        verdict = "faster"
                row["verdict"] = verdict
            rows.append(row)
    return rows, problems


def pct_change(base: float, now: float) -> str:
    return f"{100 * (now - base) / base:+.0f}%" if base else "n/a"


def ms(value: float | None) -> str:
    return "" if value is None else f"{value:,.0f}" if value >= 100 else f"{value:.1f}"


def render_baseline(baseline: dict[str, Any], rows: list[dict[str, Any]] | None = None, result: dict[str, Any] | None = None) -> str:
    """One host's baseline as Markdown; with [rows] from `check_against`, the result beside it."""
    m = baseline["machine"]
    out = [f"## {baseline['host']}", ""]
    out.append(f"{m.get('cpu') or ''}, {m.get('cores')} cores, {(m.get('memBytes') or 0) // 2**30} GB. "
               f"Commit `{baseline['commit'][:9]}`; runs {', '.join(baseline['stamps'])}; "
               f"{baseline['reps']} reps, {baseline.get('coldRuns', 0)} cold rounds, think {baseline.get('thinkSeconds')} s.")
    if result is not None:
        out.append("")
        out.append(f"Compared with run {result['stamp']} at commit `{result['git']['commit'][:9]}`"
                   f"{' (dirty)' if result['git']['dirty'] else ''}, load {result.get('loadAverageStartEnd')}.")
    by_step = {(r["scenario"], r["step"]): r for r in rows or []}
    for scenario, phases in baseline["steps"].items():
        out += ["", f"### `{scenario}`", ""]
        header = "| Step | n | median ms | p90 ms | p99 ms | band (median / p90) | where the time goes |"
        rule = "| --- | ---: | ---: | ---: | ---: | ---: | --- |"
        if rows is not None:
            header += " now median | now p90 | change | verdict |"
            rule += " ---: | ---: | ---: | --- |"
        out += [header, rule]
        for key, b in phases.items():
            spans = ", ".join(f"{k} {v:,.0f}" for k, v in b.get("spansMs", {}).items() if v >= 1)
            line = (f"| {key} | {b['n']} | {ms(b['wallMs'])} | {ms(b.get('wallP90Ms'))} | {ms(b.get('wallP99Ms'))} | "
                    f"±{b['tolMedianPct']:.0f}% / ±{b['tolP90Pct']:.0f}% | {spans} |")
            if rows is not None:
                r = by_step.get((scenario, key), {})
                now = r.get("now") or {}
                change = pct_change(b["wallMs"], now["wallMs"]) if "wallMs" in now else ""
                verdict = r.get("verdict", "")
                line += f" {ms(now.get('wallMs'))} | {ms(now.get('wallP90Ms'))} | {change} | {'**' + verdict + '**' if verdict not in ('ok', '') else verdict} |"
            out.append(line)
    return "\n".join(out) + "\n"


PAGE_HEADER = """<!-- Generated by `tools/feel_bench.py page` from dev_docs/testing/feel_baselines/*.json. Do not edit. -->
# Feel benchmark baselines

The committed baseline for each machine that has one, and the band `tools/feel_bench.py check`
allows each step before calling it a regression. Method, scenarios and spans:
[FEEL_BENCHMARKS.md](FEEL_BENCHMARKS.md). Times are client wall clock, command sent to verdict.
A baseline changes only by `feel_bench.py baseline`, in its own commit.
"""


CHECK_PAGE_HEADER = """<!-- Generated by `tools/feel_bench.py check --page`. Do not edit. -->
# Feel benchmark: a run against the baseline

One run of `tools/feel_bench.py` beside this machine's committed baseline. A step is **regressed**
when its median or p90 leaves the band and moved by more than a few milliseconds, **faster** when its
median left the band downwards (re-baseline if that was deliberate). Method:
[FEEL_BENCHMARKS.md](FEEL_BENCHMARKS.md).
"""


def render_page() -> str:
    parts = [PAGE_HEADER]
    for path in sorted(BASELINE_ROOT.glob("*.json")):
        parts.append(render_baseline(json.loads(path.read_text(encoding="utf-8"))))
    if len(parts) == 1:
        parts.append("No baselines yet.\n")
    return "\n".join(parts)


def write_page() -> None:
    BASELINE_PAGE.write_text(render_page(), encoding="utf-8")
    print(f"wrote {BASELINE_PAGE}")


def cmd_baseline(args: argparse.Namespace) -> int:
    runs = [load(f) for f in args.results] if args.results else [load(newest_result())]
    baseline = make_baseline(runs)
    out = Path(args.out) if args.out else baseline_path(baseline["host"])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(baseline, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out} from {len(runs)} run(s)")
    write_page()
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    result = load(args.result or newest_result())
    path = Path(args.baseline) if args.baseline else baseline_path(result["machine"]["host"])
    if not path.exists():
        print(f"no baseline at {path}; make one with `feel_bench.py baseline`")
        return 2
    baseline = json.loads(path.read_text(encoding="utf-8"))
    rows, problems = check_against(result, baseline)
    for problem in problems:
        print(f"not comparable: {problem}")
    if problems:
        return 2
    for r in rows:
        now = r["now"] or {}
        print(f"  {r['verdict']:9s} {r['scenario']:15s} {r['step']:30s} {ms(r['base']['wallMs']):>8s} -> {ms(now.get('wallMs')):>8s} ms")
    if args.page:
        Path(args.page).write_text(CHECK_PAGE_HEADER + "\n" + render_baseline(baseline, rows, result), encoding="utf-8")
        print(f"wrote {args.page}")
    bad = [r for r in rows if r["verdict"] in ("regressed", "failed", "missing")]
    faster = [r for r in rows if r["verdict"] == "faster"]
    if faster:
        print(f"{len(faster)} step(s) faster than the band: if that is a deliberate improvement, re-baseline.")
    print(f"{len(bad)} regression(s)" if bad else "no regressions")
    return 1 if bad else 0


def cmd_noise(args: argparse.Namespace) -> int:
    runs = [load(f) for f in args.results]
    for (scenario, key), row in sorted(spread(runs).items()):
        print(f"  {scenario:15s} {key:30s} median ±{row.get('medianPct', 0):5.1f}%   p90 ±{row.get('p90Pct', 0):5.1f}%")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="mode", required=True)
    p_run = sub.add_parser("run", help="Boot Field and run the scenarios.")
    p_run.add_argument("--scenario", action="append", choices=sorted(SCENARIOS))
    p_run.add_argument("--reps", type=int, default=5)
    p_run.add_argument("--port", type=int)
    p_run.add_argument("--timeout", type=float, default=180.0)
    p_run.add_argument("--think", type=float, default=1.0,
                       help="Seconds between steps, off the clock (a person reading and typing); 0 for back to back.")
    p_run.add_argument("--no-graphics", action="store_true", help="Skip scenarios that need Vulkan.")
    p_run.add_argument("--fresh-jvm-per-scenario", action="store_true",
                       help="Boot a new Field for each scenario, so every one sees a cold JVM.")
    p_run.add_argument("--cold-runs", type=int, default=0,
                       help="Extra rounds of one fresh JVM per scenario, for first-launch samples (labelled 'cold ...').")
    p_run.add_argument("--require-ac", action="store_true", help="Skip (exit 3) on battery.")
    p_run.add_argument("--max-load", type=float, help="Skip (exit 3) when the 1-minute load average is above this.")
    p_run.add_argument("--ledger", default=str(DEFAULT_LEDGER))
    p_cmp = sub.add_parser("compare", help="Compare two results (default: last two ledger entries on this host).")
    p_cmp.add_argument("files", nargs="*")
    p_cmp.add_argument("--ledger", default=str(DEFAULT_LEDGER))
    p_base = sub.add_parser("baseline", help="Write this host's baseline from one or more results, and the page.")
    p_base.add_argument("results", nargs="*", help="Result files (default: the newest); several set each step's band from their spread.")
    p_base.add_argument("--out")
    p_check = sub.add_parser("check", help="The ratchet: compare a result with this host's baseline; exit 1 on a regression.")
    p_check.add_argument("result", nargs="?")
    p_check.add_argument("--baseline")
    p_check.add_argument("--page", help="Also write the comparison as a Markdown page here.")
    sub.add_parser("page", help=f"Regenerate {BASELINE_PAGE.relative_to(REPO_ROOT)} from the committed baselines.")
    p_noise = sub.add_parser("noise", help="How far apart several runs are, step by step.")
    p_noise.add_argument("results", nargs="+")
    args = parser.parse_args()
    if args.mode == "run":
        return asyncio.run(run_bench(args))
    if args.mode == "baseline":
        return cmd_baseline(args)
    if args.mode == "check":
        return cmd_check(args)
    if args.mode == "page":
        write_page()
        return 0
    if args.mode == "noise":
        return cmd_noise(args)
    return compare(args)


if __name__ == "__main__":
    raise SystemExit(main())
