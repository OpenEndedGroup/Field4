"""Capture ai4_painting.md's States tree figure from the saved harbour painting chat.

The chat is the real ai4_paint_studio conversation recorded with gpt-6-astra and committed under
``public/docs/assets/ai4_chat_archive/chats/harbour`` (its session file holds the saved canvas
states). The script copies it into a temporary workspace on a Field it starts and owns, checks the
chat Field restores is byte-for-byte the saved one, opens the AI chat, opens **States**, selects
the first painting's node, and cuts the chat panel out of one capture of the whole window. No model
is called.
Use: uv run --project . --group backdoor python tools/capture_ai4_painting_states.py [--port 9791]
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
SOURCE = ROOT / "public/docs/assets/ai4_chat_archive/chats/harbour"
OUTPUT = ROOT / "public/docs/assets/ai4_painting/states.png"
DOC = "harbour"
BOX = "studio"

OPEN_STATES = """(async () => {
  window.openAiChatForBox('studio', 'code');
  const wait = async (test, what) => {
    for (let i = 0; i < 300; i++) { const v = test(); if (v) return v; await new Promise(r => setTimeout(r, 100)); }
    throw new Error('Timed out waiting for ' + what);
  };
  const chat = await wait(() => document.querySelector('.ai-chat-window[data-uid="studio"]'), 'the chat panel');
  const toggle = await wait(() => [...chat.querySelectorAll('.state-tree-toolbar:not([hidden]) button')].find(b => b.textContent === 'States'), 'the States button');
  const content = chat.closest('.disclosure-content');
  content.style.width = '720px'; content.style.height = '1000px'; content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
  toggle.click();
  const rows = await wait(() => { const r = chat.querySelectorAll('.state-tree-node'); return r.length >= 3 ? [...r] : null; }, 'three saved states');
  // The first painting as it was hung: an earlier node than the current one, holding the dusk canvas.
  const row = rows.find(r => r.querySelector('.state-tree-node-title').textContent === 'Harbour at dusk');
  if (!row) throw new Error('Dusk node missing: ' + rows.map(r => r.textContent).join(' | '));
  row.click();
  await wait(() => chat.querySelector('.state-tree-detail')?.textContent.startsWith('Conversation state:'), 'the selected state');
  chat.querySelector('.state-tree-browser').scrollIntoView({block: 'start'});
  const graph = chat.querySelector('.state-tree-graph');
  graph.scrollTop = Math.max(0, row.offsetTop - 43);
  await new Promise(r => setTimeout(r, 600));
  const r = chat.querySelector('.state-tree-browser').getBoundingClientRect();
  if (!r.width) throw new Error('States panel not laid out: ' + JSON.stringify(chat.getBoundingClientRect()) + ' ' + JSON.stringify(content.getBoundingClientRect()));
  const bar = chat.querySelector('.state-tree-toolbar').getBoundingClientRect();
  return {width: window.innerWidth, region: [Math.min(r.left, bar.left), Math.min(r.top, bar.top), r.right, r.bottom],
          rows: rows.map(x => x.textContent)};
})()"""


async def capture(port, workspace):
    work = Path(workspace)
    log = fb.SessionLogger(fb.LOG_ROOT, ["ai4-painting-states-capture"])
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

    try:
        await c.ensure_server()
        assert c.managed_process is not None, "Use a free port; capture must own its server"
        await c.connect()
        shutil.copytree(SOURCE, work / DOC)
        # The archive's box carries no frame; place it on the canvas so its chat panel can open.
        meta = work / DOC / "metadata.edn"
        meta.write_text(meta.read_text().replace('{"name" "Studio"', '{"name" "Studio" "frame" #afield/rect [40.0 40.0 180.0 50.0]'))
        await c.open_document(DOC, scratch=False, must_exist=True, timeout=60)
        reply = await c.call_for_single_reply("ai-chat-history", {"uid": BOX}, timeout=30)
        saved = (SOURCE / "studio_ai4_chat_history_.md").read_text()
        assert reply["payload"]["history"] == saved, "Saved chat differs from the archive"
        await c.open_playwright(timeout=90)
        measured = await evaluate(OPEN_STATES)
        print("rows", measured["rows"], flush=True)
        window = work / "window.png"
        await command(f"screenshot-window {window} --no-pan --preserve-selection")
        # Cut the panel out of one capture of the whole window; nothing inside is altered.
        with Image.open(window) as shot:
            scale = shot.width / measured["width"]
            left, top, right, bottom = (round(v * scale) for v in measured["region"])
            margin = round(4 * scale)
            shot.crop((max(0, left - margin), max(0, top - margin), min(shot.width, right + margin),
                       min(shot.height, bottom + margin))).save(OUTPUT)
        print("captured", OUTPUT, measured["region"], flush=True)
    finally:
        await c.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9791)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="field-ai4-states-capture-", ignore_cleanup_errors=True) as workspace:
        asyncio.run(capture(args.port, workspace))
