"""Capture the function walkthrough's two screenshots of Field itself.

``starter.png`` is the blank starter's study box with its viewer; ``saved-chat.png`` is the worked
example's study box with its saved AI4 chat restored beside the viewer. Both come from copies in a
temporary workspace, on a Field this script starts and owns, and neither makes a model call. Before
capturing, the script checks that the chat Field restores is byte-for-byte the saved chat. Run the
packager afterwards; its ``--check`` requires both screenshots to exist.
Use: uv run --project . --group backdoor python tools/capture_function_conversation.py [--port 9796]
"""

import argparse
import asyncio
import json
import shutil
import tempfile
from pathlib import Path

from field_python_env import ensure_repo_python

ensure_repo_python()
import field_backdoor as fb  # noqa: E402
from PIL import Image  # noqa: E402

ROOT = fb.REPO_ROOT
ASSETS = ROOT / "public/docs/assets/function_conversation"
BOX = "function_source"

REGION = """((withChat) => {
  const box = [...document.querySelectorAll('.box')].find(b => b.uid === 'function_source');
  const parts = [box, ...box.querySelectorAll('.disclosure.open')];
  if (withChat) parts.push(document.querySelector('.ai-chat-window[data-uid="function_source"]').closest('.disclosure'));
  const rects = parts.map(e => e.getBoundingClientRect());
  return {width: window.innerWidth, region: [Math.min(...rects.map(r => r.left)), Math.min(...rects.map(r => r.top)),
          Math.max(...rects.map(r => r.right)), Math.max(...rects.map(r => r.bottom))]};
})"""


async def capture(port, workspace):
    work = Path(workspace)
    log = fb.SessionLogger(fb.LOG_ROOT, ["function-conversation-docs-capture"])
    c = fb.FieldBackdoor(port=port, workspace=str(work), logger=log, default_timeout=120)

    async def evaluate(code):
        r = await c.playwright_input(action="pageEval", script=code, script_mode="expression", timeout=90)
        if not r.get("ok"):
            raise RuntimeError(json.dumps(r)[:2000])
        return r.get("result")

    async def command(line):
        r = await fb.dispatch_command(c, fb.parse_command(line), line)
        if not r["ok"]:
            raise RuntimeError(json.dumps(r)[:2000])
        return r

    async def shoot(name, with_chat):
        measured = await evaluate(REGION + f"({'true' if with_chat else 'false'})")
        window = work / f"window-{name}"
        await command(f"screenshot-window {window} --no-pan --preserve-selection")
        # Cut the box and its outputs out of one capture of the whole window; nothing inside is altered.
        with Image.open(window) as shot:
            scale = shot.width / measured["width"]
            left, top, right, bottom = (round(v * scale) for v in measured["region"])
            margin = round(16 * scale)
            shot.crop((max(0, left - margin), max(0, top - margin), min(shot.width, right + margin),
                       min(shot.height, bottom + margin))).save(ASSETS / name)
        print("captured", name, measured["region"], flush=True)

    try:
        await c.ensure_server()
        assert c.managed_process is not None, "Use a free port; capture must own its server"
        await c.connect()
        for name in ("function_conversation", "function_conversation_example"):
            shutil.copytree(ROOT / "dev_docs/agents/demos" / name, work / name, ignore=shutil.ignore_patterns("backups"))
        for name, output in (("function_conversation", "starter.png"), ("function_conversation_example", "saved-chat.png")):
            await c.open_document(name, scratch=False, must_exist=True, timeout=60)
            reply = await c.call_for_single_reply("ai-chat-history", {"uid": BOX}, timeout=30)
            chat = work / name / "function_source_ai4_chat_history_.md"
            assert reply["payload"]["history"] == (chat.read_text() if chat.is_file() else ""), "Saved chat differs from the archive"
            await c.open_playwright(timeout=90)
            # The viewer was drawn when the document opened, before this browser attached; an
            # observation of the whole domain draws it again.
            result = await c.ai_tool_call(name="ObserveFunctionStudy", arguments={}, timeout=180, focus_box=BOX)
            print(name, "revision", result["result"]["revision"], flush=True)
            if output == "saved-chat.png":
                await evaluate(f"window.openAiChatForBox('{BOX}', 'code'); true")
                await evaluate("""(async () => {
                  for (let i = 0; i < 200; i++) {
                    const panel = document.querySelector('.ai-chat-window[data-uid="function_source"]');
                    if (panel?.querySelectorAll('.ai-chat-turn.you').length === 7) {
                      await Promise.all([...panel.querySelectorAll('img')].map(img => img.complete ? null : new Promise(r => { img.onload = r; img.onerror = r; })));
                      const content = panel.closest('.disclosure-content');
                      content.style.width = '660px'; content.style.height = '760px'; content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
                      await new Promise(r => setTimeout(r, 600));
                      const answer = [...panel.querySelectorAll('.ai-chat-turn.ai')].find(t => t.textContent.includes('Moved e3'));
                      if (!answer) throw new Error('Recorded move reply missing');
                      answer.scrollIntoView({block: 'start'});
                      return true;
                    }
                    await new Promise(r => setTimeout(r, 100));
                  }
                  throw new Error('Saved conversation did not restore');
                })()""")
            await evaluate("new Promise(r => setTimeout(() => r(true), 800))")
            await shoot(output, output == "saved-chat.png")
    finally:
        await c.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9796)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="field-function-chat-capture-") as workspace:
        asyncio.run(capture(args.port, workspace))
