"""Run and capture the three AI4 run-feature demos from aiFeatures.md.

Uses real model requests in an owned temporary Field workspace and an independent browser,
not the user's Electron tab. No screenshots are written until their corresponding runtime
checks pass. Use --demo controls|events|context to select one. The context demo requires a
stateless Responses provider; it fails rather than fabricating a reduction on a hosted provider.
"""

import argparse
import asyncio
import json
import re
import tempfile
import time
from pathlib import Path

from field_python_env import ensure_repo_python

ensure_repo_python()
import field_backdoor as fb  # noqa: E402

PAGE = fb.REPO_ROOT / "public/docs/aiFeatures.md"
ASSETS = fb.REPO_ROOT / "public/docs/assets/ai4_run_features"
PROMPTS = {
    "controls": "Call demo.tick five times, one at a time, with labels red-1 through red-5. Do not batch or use a tool program.",
    "events": "Call demo.verdict once with pass=true and once with pass=false, then report both outcomes. Do not retry the false verdict.",
    "context": "Call demo.samples and tell me the last square.",
}


def demo_code(name: str) -> str:
    match = re.search(r"<!-- ai4-demo: " + re.escape(name) + r" -->\n```diffstatic \{data-lang=kotlin\}\n(.*?)\n```", PAGE.read_text(), re.S)
    if not match:
        raise ValueError(f"No {name} demo on {PAGE}")
    return match.group(1) + "\n"


async def run(port: int, workspace: Path, demos: list[str], capture: bool) -> None:
    log = fb.SessionLogger(fb.LOG_ROOT, ["ai4-run-features"])
    client = fb.FieldBackdoor(port=port, workspace=str(workspace), logger=log, default_timeout=120)

    async def command(line):
        result = await fb.dispatch_command(client, fb.parse_command(line), line)
        if not result["ok"]:
            raise RuntimeError(line + ": " + json.dumps(result)[:2000])
        return result

    async def evaluate(script):
        result = await client.playwright_input(action="pageEval", script=script, script_mode="expression", timeout=120)
        if not result.get("ok"):
            raise RuntimeError(json.dumps(result)[:2000])
        return result.get("result")

    async def submit(uid, text, follow_up=False):
        await evaluate(f"""(() => {{
          const root = document.querySelector('.ai-chat-window[data-uid="{uid}"]');
          if (!root) throw new Error('Chat not open');
          const input = root.querySelector('.ai-chat-input textarea');
          input.value = {json.dumps(text)};
          input.dispatchEvent(new KeyboardEvent('keydown', {{key:'Enter', altKey:{str(follow_up).lower()}, bubbles:true}}));
          return true;
        }})()""")

    def rows(uid):
        import hashlib
        path = workspace / "run-features" / "ai4_events" / (hashlib.sha256(uid.encode()).hexdigest() + ".jsonl")
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    async def click(uid, label):
        await evaluate(f"""(() => {{
          const root = document.querySelector('.ai-chat-window[data-uid="{uid}"]');
          const menu = root.querySelector('.ai-chat-actions-menu');
          if (!menu.matches(':popover-open')) root.querySelector('.ai-chat-actions-toggle').click();
          const button = [...menu.querySelectorAll('button')].find(b => b.textContent === {json.dumps(label)});
          if (!button) throw new Error('Missing run control');
          button.click(); return true;
        }})()""")

    async def wait_for(predicate, label, seconds=300):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            await asyncio.sleep(0.1)
        raise TimeoutError(label)

    async def idle(uid):
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            state = (await client.call_for_single_reply("ai-chat-history", {"uid": uid}, timeout=30))["payload"]
            if not state.get("running") and any(r["type"] == "run.ended" for r in rows(uid)):
                return
            await asyncio.sleep(0.5)
        raise TimeoutError("AI turn did not finish")

    try:
        await client.ensure_server()
        if client.managed_process is None:
            raise RuntimeError("Use a free port: this runner must own its server")
        await client.connect()
        await client.open_document("run-features", scratch=False, keep_running=True, timeout=60)
        await client.open_playwright(timeout=90)
        for name in demos:
            uid = (await command(f"new-box --name {name.title()}"))["data"]["box"]["uid"]
            source = workspace / f"{name}.kt"
            source.write_text(demo_code(name))
            await command(f"run {uid} --file {source} --timeout 240")
            await evaluate(f"window.openAiChatForBox('{uid}', 'code'); true")
            await asyncio.sleep(1)
            await submit(uid, PROMPTS[name])
            await wait_for(lambda: any(r["type"] == "run.started" for r in rows(uid)), "chat submission did not start an AI run", 30)
            if name == "controls":
                await wait_for(lambda: any(r["type"] == "tool.started" and r.get("tool") in ("demo.tick", "demo_tick") for r in rows(uid)), "model never called demo.tick")
                await submit(uid, "Use blue for the remaining labels.")
                await wait_for(lambda: any(r["type"] == "input.delivered" for r in rows(uid)), "correction was not delivered")
                await submit(uid, "Explain the labels you used, without calling tools.", follow_up=True)
                await wait_for(lambda: any(r["type"] == "input.queued" and r.get("mode") == "follow-up" for r in rows(uid)), "follow-up was not queued")
                await click(uid, "Recall queued")
                await wait_for(lambda: any(r["type"] == "input.withdrawn" for r in rows(uid)), "follow-up was not recalled")
                recalled = await evaluate(f"""(async () => {{
                  for (let i = 0; i < 100; i++) {{
                    const value = document.querySelector('.ai-chat-window[data-uid="{uid}"] .ai-chat-input textarea').value;
                    if (value.includes('Explain the labels')) return value;
                    await new Promise(resolve => setTimeout(resolve, 50));
                  }}
                  throw new Error('Recalled draft did not reach the composer');
                }})()""")
                await submit(uid, recalled, follow_up=True)
                await wait_for(lambda: len([r for r in rows(uid) if r["type"] == "run.ended"]) >= 2, "follow-up did not finish", 600)
                events = rows(uid)
                delivered = next(i for i, r in enumerate(events) if r["type"] == "input.delivered")
                first_end = next(i for i, r in enumerate(events) if r["type"] == "run.ended")
                assert delivered < first_end
                import gzip
                blob_root = workspace / "run-features/ai4_events/blobs"
                calls_in_first_run = [r for r in events[:first_end] if r["type"] == "tool.started" and r.get("tool") == "demo.tick"]
                labels = [json.loads(gzip.decompress((blob_root / (r["argumentsHash"] + ".gz")).read_bytes()))["label"] for r in calls_in_first_run]
                assert labels == ["red-1", "blue-2", "blue-3", "blue-4", "blue-5"], labels
                assert any(r["type"] == "input.queued" and r.get("mode") == "follow-up" for r in events)
                await idle(uid)
                ended = len([r for r in rows(uid) if r["type"] == "run.ended"])
                calls = len([r for r in rows(uid) if r["type"] == "tool.started" and r.get("tool") == "demo.tick"])
                await submit(uid, "Call demo.tick five more times, one at a time, with labels stop-1 through stop-5.")
                await wait_for(lambda: len([r for r in rows(uid) if r["type"] == "tool.started" and r.get("tool") == "demo.tick"]) > calls, "stop exercise never called the tool")
                await click(uid, "Stop")
                await wait_for(lambda: len([r for r in rows(uid) if r["type"] == "run.ended"]) > ended, "stopped run never ended")
                stopped = next(i for i, r in enumerate(rows(uid)) if r["type"] == "run.stop-requested")
                assert not any(r["type"] == "tool.started" for r in rows(uid)[stopped + 1:]), "a tool started after Stop"
            else:
                await idle(uid)
            if name == "events":
                verdicts = [r.get("success") for r in rows(uid) if r["type"] == "tool.verdict" and r.get("tool") in ("demo.verdict", "demo_verdict")]
                assert "true" in verdicts and "false" in verdicts, verdicts
            if name == "context":
                previous = len([r for r in rows(uid) if r["type"] == "run.ended"])
                await submit(uid, "What was the first sample?")
                await wait_for(lambda: len([r for r in rows(uid) if r["type"] == "run.ended"]) > previous, "second turn did not end", 600)
                reductions = [r for r in rows(uid) if r["type"] == "context.reduced"]
                assert reductions, "No local reduction: use a stateless Responses provider"
                reduction = reductions[-1]
                assert reduction["before"] != reduction["after"], reduction
                for reference in (reduction["before"], reduction["after"]):
                    inspected = (await client.call_for_single_reply("ai-chat-context", {"uid": uid, "action": "snapshot:" + reference}, timeout=30))["payload"]
                    assert "Context snapshot" in inspected["markdown"] and reference in inspected["markdown"]
            response = (await client.call_for_single_reply("ai-chat-context", {"uid": uid}, timeout=30))["payload"]
            assert "durable run events" in response["markdown"]
            if capture:
                # Capture only the actual chat panel, with the relevant live passage in view.
                # Cropping is framing inside this runner; no content or pixels are substituted.
                await command("viewport 900x1100")
                await evaluate(f"""(() => {{
                  const root = document.querySelector('.ai-chat-window[data-uid="{uid}"]');
                  const content = root.closest('.disclosure-content');
                  content.style.width = '620px'; content.style.height = {json.dumps('760px' if name == 'controls' else '560px')};
                  content.dispatchEvent(new MouseEvent('mouseup', {{bubbles:true}}));
                  return true;
                }})()""")
                if name != "controls":
                    await click(uid, "Context")
                await asyncio.sleep(1)
                await evaluate(f"""(() => {{
                  const root = document.querySelector('.ai-chat-window[data-uid="{uid}"]');
                  const messages = root.querySelector('.ai-chat-messages');
                  const needle = {json.dumps('settle-old-output' if name == 'context' else 'success=false')};
                  const row = [...messages.querySelectorAll('tr')].find(r => r.textContent.includes(needle));
                  if ({str(name != 'controls').lower()} && !row) throw new Error('Expected evidence row is not visible');
                  if (row) row.scrollIntoView({{block:'center'}});
                  else messages.scrollTop = messages.scrollHeight;
                  return true;
                }})()""")
                window = workspace / f"{name}-window.png"
                async def window_shot(pan):
                    return await client.screenshot_window(path=str(window), uid=uid, property_name="code",
                        show_editor=False, editor_only=False, open_outputs=False, padding=16,
                        pan_into_view=pan, wait_ms=500, timeout=120, preserve_selection=True)
                await window_shot(True)
                region = await evaluate(f"""(() => {{
                  const r = document.querySelector('.ai-chat-window[data-uid="{uid}"]').getBoundingClientRect();
                  return {{width:innerWidth,height:innerHeight,left:r.left,top:r.top,right:r.right,bottom:r.bottom}};
                }})()""")
                assert region["left"] >= 0 and region["top"] >= 0 and region["right"] <= region["width"] and region["bottom"] <= region["height"], region
                if name == "controls":
                    await evaluate(f"""(() => {{
                      const root = document.querySelector('.ai-chat-window[data-uid="{uid}"]');
                      root.querySelector('.ai-chat-actions-toggle').click();
                      return true;
                    }})()""")
                await window_shot(False)
                from PIL import Image
                ASSETS.mkdir(parents=True, exist_ok=True)
                with Image.open(window) as image:
                    scale = image.width / region["width"]
                    bounds = tuple(round(region[k] * scale) for k in ("left", "top", "right", "bottom"))
                    image.crop(bounds).save(ASSETS / (name + ".png"))
            print(f"PASS {name}: {len(rows(uid))} durable events", flush=True)
    except BaseException as failure:
        import shutil
        diagnostic = fb.REPO_ROOT / "build/ai4-run-features" / str(time.time_ns())
        diagnostic.mkdir(parents=True, exist_ok=True)
        (diagnostic / "failure.txt").write_text(repr(failure))
        shutil.copytree(workspace, diagnostic / "workspace", dirs_exist_ok=True)
        print(f"Failure evidence: {diagnostic}", flush=True)
        raise
    finally:
        await client.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9884)
    parser.add_argument("--demo", choices=list(PROMPTS), action="append")
    parser.add_argument("--capture", action="store_true")
    parser.add_argument("--stateless-openai", action="store_true", help="Exercise the real stateless bridge against OpenAI, without needing a local model server; requires --model and normal OpenAI credentials")
    parser.add_argument("--model", help="Explicit model for --stateless-openai")
    args = parser.parse_args()
    if args.stateless_openai:
        import os
        if not args.model:
            parser.error("--stateless-openai requires --model")
        key = os.environ.get("OPENAI_PLATFORM_KEY") or os.environ.get("OPENAI_API_KEY")
        key_file = Path.home() / ".afield/openai-platform-key.txt"
        if not key and key_file.is_file():
            key = key_file.read_text().strip()
        if not key:
            parser.error("No normal OpenAI platform credential is configured")
        # Only this owned child server uses this transport configuration. The provider's real API
        # performs inference; Field's actual stateless-history code performs the reduction.
        os.environ["AFIELD_AI_PROVIDER"] = "local"
        os.environ["AFIELD_LOCAL_AI_BASE_URL"] = os.environ.get("AFIELD_AI_BASE_URL", "https://api.openai.com/v1")
        os.environ["AFIELD_LOCAL_AI_API_KEY"] = key
        os.environ["AFIELD_AI_MODEL"] = args.model
        print("Using the stateless Responses bridge against the real OpenAI endpoint (not a local-model capture).", flush=True)
    with tempfile.TemporaryDirectory(prefix="field-ai4-run-features-") as temporary:
        asyncio.run(run(args.port, Path(temporary), args.demo or list(PROMPTS), args.capture))
