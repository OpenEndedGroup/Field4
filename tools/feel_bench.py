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

Numbers are only comparable on one machine with the same load: close the Electron app and other
Fields first, and keep the laptop on power (long verifications are power-gated).
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import platform
import shlex
import shutil
import socket
import statistics
import subprocess
import sys
import time
import urllib.request
from dataclasses import asdict, dataclass, field
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
    phase: str  # first | second | warm | edit | shader-edit | frame | probe | ...
    ok: bool
    wallMs: float
    firstOutputMs: float | None = None
    spans: dict[str, dict[str, float]] = field(default_factory=dict)
    note: str = ""


class Bench:
    def __init__(self, client: fb.FieldBackdoor, port: int, timeout: float) -> None:
        self.client = client
        self.port = port
        self.timeout = timeout
        self.ssl = fb.make_unverified_ssl_context()
        self.span_cursor = 0
        self.steps: list[Step] = []

    # -- server spans -------------------------------------------------------
    def spans_since(self, cursor: int) -> dict[str, Any]:
        url = f"https://localhost:{self.port}/api/perf/spans?since={cursor}"
        with urllib.request.urlopen(url, context=self.ssl, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))

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
        """Time [action]; attribute the spans that started inside it and the box's first output."""
        self.drain_spans()
        start_seq = self.client.event_seq
        t0 = time.time()
        try:
            result = await action()
            ok = bool(result.get("ok"))
            note = "" if ok else json.dumps(result.get("data"))[:400]
        except Exception as exc:  # noqa: BLE001 - a failed step is a measurement, not a crash
            ok, note = False, f"{type(exc).__name__}: {exc}"
        t1 = time.time()
        if settle:
            await asyncio.sleep(settle)
        first_output = None
        for event in self.client.events_since(start_seq):
            name = event.get("name") or event.get("messageName")
            if name in {"output", "set-output"} and (box is None or event.get("uid") == box):
                first_output = (float(event["receivedAt"]) - t0) * 1000
                break
        spans: dict[str, dict[str, float]] = {}
        for span in self.drain_spans():
            if span["startEpochMicros"] / 1e6 < t0 - 0.002 or span["startEpochMicros"] / 1e6 > t1:
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

    async def wait_span(self, name: str, timeout: float) -> dict[str, Any]:
        """Poll the server until a span called [name] appears (for edits with no verdict)."""
        deadline = time.monotonic() + timeout
        cursor = self.span_cursor
        while time.monotonic() < deadline:
            snap = self.spans_since(cursor)
            if any(s["name"] == name for s in snap.get("spans", [])):
                return {"ok": True}
            await asyncio.sleep(0.02)
        return {"ok": False, "data": {"error": f"no {name} span within {timeout}s"}}


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
    # Does writing the shader alone recompile it, with no relaunch? A probe: "no" is a finding,
    # reported as a failed step, and does not fail the run.
    live = fragment.replace("vec4(1.0, 0.8, 0.4, 1.0)", "vec4(0.9, 0.8, 0.4, 1.0)")

    async def live_edit() -> dict[str, Any]:
        written = await b.command(f"set {tri} fragment {shlex.quote(live)}")
        return written if not written.get("ok") else await b.wait_span("shader.compile", 5.0)

    await b.step(name, "fragment edit, no relaunch", "probe", live_edit)
    for i in range(reps):
        tint = f"vec4(1.0, {0.5 + 0.1 * (i % 5):.1f}, 0.4, 1.0)"
        edited = fragment.replace("vec4(1.0, 0.8, 0.4, 1.0)", tint)

        async def shader_edit(edited: str = edited) -> dict[str, Any]:
            written = await b.command(f"set {tri} fragment {shlex.quote(edited)}")
            return written if not written.get("ok") else await b.command(f"launch {tri}")

        await b.step(name, f"fragment edit + relaunch {i + 1}", "shader-edit", shader_edit, box=tri)
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
    return info


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
SINGLE_PHASES = {"first", "second", "frame", "stop", "probe"}


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
async def run_bench(args: argparse.Namespace) -> int:
    names = args.scenario or [n for n in SCENARIOS if not (args.no_graphics and n in GRAPHICS)]
    stamp = time.strftime("%Y%m%d-%H%M%S")
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    workspace = prepare_workspace(OUT_ROOT / "workspace")
    port = args.port or free_port()
    groups = [[n] for n in names] if args.fresh_jvm_per_scenario else [names]

    steps: list[Step] = []
    boots: list[dict[str, Any]] = []
    for group in groups:
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
            bench = Bench(client, port, args.timeout)
            boot["readyMillis"] = bench.spans_since(0).get("readyMillis")
            boot["scenarios"] = group
            boots.append(boot)
            print(f"  boot {boot}", flush=True)
            try:
                for name in group:
                    await SCENARIOS[name](bench, args.reps)
            finally:
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
        "freshJvmPerScenario": args.fresh_jvm_per_scenario,
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
        handle.write(json.dumps({k: result[k] for k in ("schema", "stamp", "git", "machine", "reps",
                                                        "freshJvmPerScenario", "boot", "summary")}) + "\n")
    print_summary(result)
    print(f"\nwrote {out_path}\nappended {ledger}")
    return 0 if all(s.ok for s in steps if s.phase != "probe") else 1


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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="mode", required=True)
    p_run = sub.add_parser("run", help="Boot Field and run the scenarios.")
    p_run.add_argument("--scenario", action="append", choices=sorted(SCENARIOS))
    p_run.add_argument("--reps", type=int, default=5)
    p_run.add_argument("--port", type=int)
    p_run.add_argument("--timeout", type=float, default=180.0)
    p_run.add_argument("--no-graphics", action="store_true", help="Skip scenarios that need Vulkan.")
    p_run.add_argument("--fresh-jvm-per-scenario", action="store_true",
                       help="Boot a new Field for each scenario, so every one sees a cold JVM.")
    p_run.add_argument("--ledger", default=str(DEFAULT_LEDGER))
    p_cmp = sub.add_parser("compare", help="Compare two results (default: last two ledger entries on this host).")
    p_cmp.add_argument("files", nargs="*")
    p_cmp.add_argument("--ledger", default=str(DEFAULT_LEDGER))
    args = parser.parse_args()
    if args.mode == "run":
        return asyncio.run(run_bench(args))
    return compare(args)


if __name__ == "__main__":
    raise SystemExit(main())
