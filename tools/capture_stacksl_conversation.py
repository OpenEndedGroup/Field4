#!/usr/bin/env python3
"""Capture the StackSL conversation page's three screenshots of Field itself.

All three come from the recorded aurora document, reopened: ``card.png`` is the program box with its
study card and the window running, ``stage.png`` the program's own window held at one stage by a
click on that stage's tile in the card, and ``saved-chat.png`` the same box with its saved AI4 chat
restored. The
document is a copy in a temporary workspace, on a Field this script starts and owns, and nothing here
makes a model call: the card's sliders are named from the names stored with the study
(``knob-names.json``, written by the fast model when the demo was recorded). Before capturing, the script checks that the chat Field restores is byte-for-byte
the saved chat, which ``metadata.edn`` holds only as a ``#afield/file`` reference to its sidecar, and
that the card's live WebGL preview is running. The blank starter is opened too, as a check
that it comes up by itself (window and program); it is not pictured.

The window must be able to open (an awake display). Run the packager afterwards; ``--check`` requires
all three screenshots to exist.
Use: uv run --project . --group backdoor python tools/capture_stacksl_conversation.py [--port 9796]
"""

from __future__ import annotations

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
DEMOS = ROOT / "dev_docs/agents/demos"
ASSETS = ROOT / "public/docs/assets/stacksl_conversation"
CHAT_FILE = "stacksl_source_ai4_chat_history_.md"
PROGRAM = "stacksl_source"
# Everything a capture copy should not carry: live backups and caches.
IGNORED = shutil.ignore_patterns("backups", "observations", "thumbnails", "files")

CARD_READY = """(async () => {
  for (let i = 0; i < 300; i++) {
    const card = document.querySelector('.stacksl-study');
    const canvas = card?.querySelector('canvas');
    // Drawn: the live preview running (a card whose program does not translate shows its CPU still instead).
    const still = card?.querySelector('img.still');
    const drawn = canvas && (canvas.style.display !== 'none' ? card.querySelector('.time')?.textContent !== 't = 0.00 s'
                                                             : still && still.complete && still.naturalWidth > 0);
    if (card && drawn) {
      await new Promise(r => setTimeout(r, 1200));
      return {preview: card.querySelector('.glerror')?.textContent || 'live', sliders: card.querySelectorAll('.field-slider[data-knob]').length};
    }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('The study card did not finish drawing');
})()"""

# The program box and one of its outputs, measured in the page: the card itself (its output area can
# stay taller than the card) or the chat's output area.
REGION = """((inner) => {
  const box = [...document.querySelectorAll('.box')].find(b => b.uid === 'stacksl_source');
  const part = inner === 'card' ? box.querySelector('.stacksl-study') : document.querySelector('.ai-chat-window[data-uid="stacksl_source"]').closest('.disclosure');
  const rects = [box, part].map(e => e.getBoundingClientRect());
  return {width: window.innerWidth, region: [Math.min(...rects.map(r => r.left)), Math.min(...rects.map(r => r.top)),
          Math.max(...rects.map(r => r.right)), Math.max(...rects.map(r => r.bottom))]};
})"""


async def capture(port: int, workspace: Path) -> None:
    log = fb.SessionLogger(fb.LOG_ROOT, ["stacksl-conversation-docs-capture"])
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

    async def open_study(name: str) -> dict:
        await c.open_document(name, scratch=False, must_exist=True, keep_running=True, timeout=60)
        await c.open_playwright(timeout=90)
        # The window opens and the program starts by themselves (autorun); running the program here
        # redraws its card for this browser, which attached after the document opened.
        await command("wait-native-renderer stacksl_window --frames 5 --timeout 120")
        await command(f"run {PROGRAM} --timeout 240")
        card = await evaluate(CARD_READY)
        assert card["preview"] == "live", "The card's live preview is not running: " + card["preview"]
        print(name, "card", card, flush=True)
        return card

    async def shoot(inner: str, name: str) -> None:
        measured = await evaluate(REGION + f"('{inner}')")
        window = workspace / f"window-{inner}.png"
        await command(f"screenshot-window {window} --no-pan --preserve-selection")
        # Cut the region out of one capture of the whole window, plus a margin of canvas. Nothing inside is altered.
        with Image.open(window) as shot:
            scale = shot.width / measured["width"]
            left, top, right, bottom = (round(v * scale) for v in measured["region"])
            margin = round(16 * scale)
            shot.crop((max(0, left - margin), max(0, top - margin), min(shot.width, right + margin),
                       min(shot.height, bottom + margin))).save(ASSETS / name)
        print("captured", name, measured["region"], flush=True)

    try:
        await c.ensure_server()
        assert c.managed_process is not None, "Use a free port; the capture must own its server"
        await c.connect()
        for name in ("stacksl_conversation", "stacksl_conversation_aurora"):
            shutil.copytree(DEMOS / name, workspace / name, ignore=IGNORED)

        await open_study("stacksl_conversation")

        name = "stacksl_conversation_aurora"
        await c.open_document(name, scratch=False, must_exist=True, keep_running=True, timeout=60)
        reply = await c.call_for_single_reply("ai-chat-history", {"uid": PROGRAM}, timeout=30)
        saved = (workspace / name / CHAT_FILE).read_text()
        assert reply["payload"]["history"] == saved, "The restored chat differs from the saved sidecar"
        print("restored chat matches its sidecar:", len(saved), "characters", flush=True)
        await open_study(name)
        await evaluate("""(async () => {
          const content = document.querySelector('.stacksl-study').closest('.disclosure-content');
          content.style.width = '1080px'; content.style.height = '1260px';
          content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
          await new Promise(r => setTimeout(r, 800)); return true; })()""")
        await shoot("card", "card.png")

        # The window held at one stage, as a click on the stage's tile in the card holds it: the line
        # that stores the aurora's curtain, which the tile — and so the window — shows by the value
        # stored; failing that, the last stage the card shows that stores anything.
        held = await evaluate("""(async () => {
          const cells = [...document.querySelectorAll('.stacksl-study .cell.stage')];
          const stores = cells.filter(c => c.textContent.includes('\u2192'));
          const cell = cells.find(c => c.textContent.includes('\u2192 curtain')) || stores[stores.length - 1] || cells[Math.floor(cells.length / 2)];
          cell.click(); await new Promise(r => setTimeout(r, 2500));
          return {stage: cell.querySelector('span').textContent, text: cell.querySelector('span.muted').textContent,
                  pinned: cell.classList.contains('pinned')}; })()""")
        assert held["pinned"], f"the stage tile did not take the click: {held}"
        await command(f"native-screenshot {ASSETS / 'stage.png'} --uid stacksl_window")
        with Image.open(ASSETS / "stage.png") as shot:
            if shot.width > 1000:
                shot.resize((shot.width // 2, shot.height // 2), Image.LANCZOS).save(ASSETS / "stage.png")
        print("captured stage.png:", held, flush=True)
        await evaluate("document.querySelector('.stacksl-study .cell.stage.pinned').click(); true")

        await evaluate(f"window.openAiChatForBox('{PROGRAM}', 'stackSL'); true")
        restored = await evaluate("""(async () => {
          for (let i = 0; i < 300; i++) {
            const panel = document.querySelector('.ai-chat-window[data-uid="stacksl_source"]');
            if (panel?.querySelectorAll('.ai-chat-turn.you').length === 5) {
              const images = [...panel.querySelectorAll('img')];
              await Promise.all(images.map(img => img.complete ? null : new Promise(r => { img.onload = r; img.onerror = r; })));
              if (images.some(img => img.naturalWidth === 0)) throw new Error('A chat image did not load');
              // The card closes so the chat sits under the box; a disclosure keeps its dragged size.
              window.disclosureInterface(document.querySelector('.stacksl-study').closest('.disclosure-group')).close('study');
              const content = panel.closest('.disclosure-content');
              content.style.width = '640px'; content.style.height = '900px';
              content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
              await new Promise(r => setTimeout(r, 800));
              const reply = [...panel.querySelectorAll('.ai-chat-turn.ai')].find(t => t.textContent.includes('negative space'));
              if (!reply) throw new Error('The recorded mound reply is missing');
              reply.scrollIntoView({block: 'start'});
              return {turns: 5, images: images.length};
            }
            await new Promise(r => setTimeout(r, 100));
          }
          throw new Error('The saved conversation did not restore');
        })()""")
        print("restored chat", restored, flush=True)
        await shoot("chat", "saved-chat.png")
    finally:
        await c.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9796)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="field-stacksl-chat-capture-") as workspace:
        asyncio.run(capture(args.port, Path(workspace)))
