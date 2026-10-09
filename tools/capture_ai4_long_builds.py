"""Capture the two figures of ``public/docs/ai4_long_builds.md``.

``waiting.png`` is a box's AI4 chat waiting for the build it started, with the box's build card;
``resumed.png`` is the same chat after Field resumed it with the build's outcome. Both come from one live
conversation on a Field this script starts and owns, in a temporary workspace. The script runs the page's own
box code (its first Kotlin fence), types the page's request into the chat as a person would, waits for the
turn to end waiting, captures, waits for Field to resume the conversation and for that turn to end, and
captures again.

It makes live model requests (two turns'). It writes the second figure only if the request ledger shows exactly
those two turns, and no request from this Field between the first turn's suspension and the build's end.
Use: uv run --project . --group backdoor python tools/capture_ai4_long_builds.py [--port 9881]
"""

import argparse
import asyncio
import datetime
import glob
import json
import re
import tempfile
import time
from pathlib import Path

from field_python_env import ensure_repo_python

ensure_repo_python()
import field_backdoor as fb  # noqa: E402
from PIL import Image  # noqa: E402

ROOT = fb.REPO_ROOT
PAGE = ROOT / "public/docs/ai4_long_builds.md"
ASSETS = ROOT / "public/docs/assets/ai4_long_builds"
LEDGER = Path.home() / ".afield/logs/ai_usage"
DOCUMENT = "long-builds"
REQUEST = "Run the π estimate build on this box. It takes a few minutes; tell me what it finds."

# The box and its open output panels (the build card and the chat), as one rectangle in CSS pixels.
REGION = """((uid) => {
  const box = [...document.querySelectorAll('.box')].find(b => b.uid === uid);
  const parts = [box, ...document.querySelectorAll('.disclosure.open')].filter(e => e && e.getBoundingClientRect().width > 0);
  const rects = parts.map(e => e.getBoundingClientRect());
  return {width: window.innerWidth, region: [Math.min(...rects.map(r => r.left)), Math.min(...rects.map(r => r.top)),
          Math.max(...rects.map(r => r.right)), Math.max(...rects.map(r => r.bottom))]};
})"""


def page_code() -> str:
    """The page's first Kotlin fence: the box the figures show is the box the reader is given."""
    text = PAGE.read_text()
    assert REQUEST in text, "the page quotes a different request from the one this script sends"
    match = re.search(r"```diffstatic \{data-lang=kotlin\}\n(.*?)\n```", text, re.S)
    assert match, "no Kotlin fence on the page"
    return match.group(1) + "\n"


def ledger(port: int, since: float) -> list[dict]:
    """This Field's model requests since [since] (epoch seconds), from the machine-wide request ledger."""
    rows = []
    for path in glob.glob(str(LEDGER / "*.jsonl")):
        if Path(path).stat().st_mtime < since:
            continue
        for line in open(path):
            try:
                row = json.loads(line)
            except ValueError:
                continue
            if row.get("port") == str(port) and row.get("event") in ("attempt_started", "attempt_finished"):
                at = datetime.datetime.fromisoformat(row["timestamp"].replace("Z", "+00:00")).timestamp()
                if at >= since:
                    rows.append({**row, "at": at})
    return sorted(rows, key=lambda r: r["at"])


def epoch(iso: str) -> float:
    return datetime.datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp()


async def capture(port: int, workspace: Path) -> None:
    log = fb.SessionLogger(fb.LOG_ROOT, ["ai4-long-builds-docs-capture"])
    c = fb.FieldBackdoor(port=port, workspace=str(workspace), logger=log, default_timeout=120)

    async def command(line: str) -> dict:
        result = await fb.dispatch_command(c, fb.parse_command(line), line)
        if not result["ok"]:
            raise RuntimeError(line + ": " + json.dumps(result)[:2000])
        return result

    async def evaluate(script: str):
        result = await c.playwright_input(action="pageEval", script=script, script_mode="expression", timeout=120)
        if not result.get("ok"):
            raise RuntimeError(json.dumps(result)[:2000])
        return result.get("result")

    async def chat(uid: str) -> dict:
        return (await c.call_for_single_reply("ai-chat-history", {"uid": uid}, timeout=60))["payload"]

    async def shoot(uid: str, name: str) -> None:
        measured = await evaluate(REGION + f"('{uid}')")
        window = workspace / f"window-{name}"
        await command(f"screenshot-window {window} --no-pan --preserve-selection")
        # Cut the box and its outputs out of one capture of the whole window; nothing inside is altered.
        with Image.open(window) as shot:
            scale = shot.width / measured["width"]
            left, top, right, bottom = (round(v * scale) for v in measured["region"])
            margin = round(16 * scale)
            shot.crop((max(0, left - margin), max(0, top - margin), min(shot.width, right + margin),
                       min(shot.height, bottom + margin))).save(ASSETS / name)
        print("captured", name, measured["region"], flush=True)

    async def arrange(uid: str, scroll_to: str, most: int) -> None:
        """The chat panel as tall as its conversation, up to [most] px, scrolled so the last element matching the
        CSS selector [scroll_to] ends the view."""
        await evaluate(f"""(async () => {{
          const panel = document.querySelector('.ai-chat-window[data-uid="{uid}"]');
          const content = panel.closest('.disclosure-content');
          const resize = async (h) => {{
            content.style.width = '700px'; content.style.height = h + 'px';
            content.dispatchEvent(new MouseEvent('mouseup', {{bubbles: true}}));
            await new Promise(r => setTimeout(r, 800));
          }};
          await resize({most});
          // No empty chat under the conversation: shrink the panel by the messages' unused height.
          const messages = panel.querySelector('.ai-chat-messages');
          const last = messages.lastElementChild;
          const used = last ? last.getBoundingClientRect().bottom - messages.getBoundingClientRect().top + messages.scrollTop : 0;
          const spare = Math.floor(messages.clientHeight - used);
          if (spare > 0) await resize({most} - spare + 24);
          const target = [...panel.querySelectorAll({json.dumps(scroll_to)})].pop();
          if (!target) throw new Error('nothing in the chat matches ' + {json.dumps(scroll_to)});
          target.scrollIntoView({{block: 'end'}});
          await new Promise(r => setTimeout(r, 600));
          return true; }})()""")

    started = time.time()
    try:
        await c.ensure_server()
        assert c.managed_process is not None, "Use a free port; the capture must own its server"
        await c.connect()
        await c.open_document(DOCUMENT, scratch=False, keep_running=True, timeout=60)
        await c.open_playwright(timeout=90)
        uid = (await command("new-box --name Estimate"))["data"]["box"]["uid"]
        code = workspace / "estimate.kt"
        code.write_text(page_code())
        await command(f"run {uid} --file {code} --timeout 240")

        # The request, typed into the box's chat and sent with Enter.
        await evaluate(f"window.openAiChatForBox('{uid}', 'code'); true")
        await evaluate(f"""(async () => {{
          for (let i = 0; i < 300; i++) {{
            const area = document.querySelector('.ai-chat-window[data-uid="{uid}"] textarea');
            if (area) {{
              area.focus(); area.value = {json.dumps(REQUEST)};
              area.dispatchEvent(new KeyboardEvent('keydown', {{key: 'Enter', bubbles: true}}));
              return true;
            }}
            await new Promise(r => setTimeout(r, 100));
          }}
          throw new Error('the chat did not open'); }})()""")

        # The turn ends waiting: not running, and suspended on the build it started.
        deadline = time.time() + 600
        while True:
            state = await chat(uid)
            if state.get("suspendedOn") and not state.get("running"):
                break
            assert time.time() < deadline, "the turn did not end waiting for a build: " + json.dumps(state)[-1500:]
            await asyncio.sleep(2)
        waiting = state["suspendedOn"]
        job = waiting["jobs"][0]["jobId"]
        print("suspended on", json.dumps(waiting), flush=True)
        # A frame part way through the build, so the card shows it running.
        while True:
            inspected = await c.ai_tool_call(name="InspectStudyBuildsRequest", arguments={"boxId": uid, "name": "estimate", "jobId": job}, timeout=60)
            record = inspected["result"]["details"]["job"]
            if record["status"] != "RUNNING" or record["progress"] >= 0.2:
                break
            await asyncio.sleep(3)
        assert record["status"] == "RUNNING", "the build finished before it could be shown running; make it longer"
        await arrange(uid, ".ai-chat-progress", 900)
        await shoot(uid, "waiting.png")

        # Field resumes the conversation when the build ends; the resumed turn then runs and ends.
        deadline = time.time() + 1800
        while True:
            state = await chat(uid)
            if not state.get("running") and not state.get("suspendedOn") and state["history"].count("<<you>>\n") >= 2:
                break
            assert time.time() < deadline, "the conversation did not resume: " + json.dumps(state)[-1500:]
            await asyncio.sleep(5)
        inspected = await c.ai_tool_call(name="InspectStudyBuildsRequest", arguments={"boxId": uid, "name": "estimate", "jobId": job}, timeout=60)
        record = inspected["result"]["details"]["job"]
        assert record["status"] == "SUCCEEDED", record

        # The claim the page makes: nothing was asked of the model while the build ran.
        suspended, finished = epoch(waiting["since"]), epoch(record["updated"])
        requests = [r for r in ledger(port, started) if r["event"] == "attempt_started"]
        during = [r for r in requests if suspended < r["at"] < finished]
        turns = {}
        for r in requests:
            turns.setdefault(r["turnId"], []).append(r)
        print(f"{len(requests)} model requests in {len(turns)} turns; build ran {finished - suspended:.0f} s after the turn "
              f"suspended; requests while it ran: {len(during)}", flush=True)
        assert not during, "model requests while the build ran: " + json.dumps(during)[:2000]
        assert len(turns) == 2, f"expected the waiting turn and the resumed one, saw {len(turns)}"

        await arrange(uid, ".ai-chat-turn.ai", 1000)
        await shoot(uid, "resumed.png")
    finally:
        await c.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9881)
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="field-ai4-long-builds-capture-") as workspace:
        asyncio.run(capture(args.port, Path(workspace)))
