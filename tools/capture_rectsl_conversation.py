#!/usr/bin/env python3
"""Capture the RectSL conversation page's four screenshots of Field itself.

All four come from the recorded lanterns document, reopened: ``card.png`` is the scene box with its
study card, ``window.png`` the scene's own window, held at one moment with the card's scrubber,
``outline.png`` the same window with the editor's cursor on the flame's line, which outlines every
lantern's flame, and ``saved-chat.png`` the scene box with its saved AI4 chat restored. The document
is a copy in a temporary workspace, on a Field this script starts and owns, and nothing here makes a
model call: the card's sliders are named from the names stored with the study (``knob-names.json``,
written by the fast model when the demo was recorded).
Before capturing, the script checks that the chat Field restores is byte-for-byte the saved chat,
which ``metadata.edn`` holds only as a ``#afield/file`` reference to its sidecar, and that the card's
frames were drawn on the GPU. The blank starter is opened too, as a check that it comes up by itself
(window and scene); it is not pictured.

The window must be able to open (an awake display). Run the packager afterwards; ``--check`` requires
all four screenshots to exist.
Use: uv run --project . --group backdoor python tools/capture_rectsl_conversation.py [--port 9798]
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
ASSETS = ROOT / "public/docs/assets/rectsl_conversation"
CHAT_FILE = "rectsl_source_ai4_chat_history_.md"
SCENE = "rectsl_source"
EXAMPLE = "rectsl_conversation_lanterns"
# The moment the window is held at: four lanterns spread across the dusk sky.
HELD_AT = 13.5
# Everything a capture copy should not carry: live backups and caches.
IGNORED = shutil.ignore_patterns("backups", "observations", "thumbnails", "files")

CARD_READY = """(async () => {
  for (let i = 0; i < 600; i++) {
    const card = document.querySelector('.rectsl-study');
    const film = card ? [...card.querySelectorAll('.film img')] : [];
    // Drawn, and drawn on the GPU: a card drawn before the window was up is redrawn once it is.
    const summary = card?.querySelector('div.muted')?.textContent ?? '';
    if (film.length && film.every(img => img.complete && img.naturalWidth > 0) && summary.includes('GPU')) {
      await new Promise(r => setTimeout(r, 1200));
      return {text: card.querySelector('div.muted')?.textContent ?? '', frames: film.length,
              sliders: card.querySelectorAll('.field-slider[data-knob]').length, scrub: !!card.querySelector('.field-slider[data-scrub]')};
    }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('The study card was not drawn on the GPU: ' + (document.querySelector('.rectsl-study div.muted')?.textContent ?? 'no card'));
})()"""

# The scene box and one of its outputs, measured in the page: the card itself (its output area can
# stay taller than the card) or the chat's output area.
REGION = """((inner) => {
  const box = [...document.querySelectorAll('.box')].find(b => b.uid === 'rectsl_source');
  const card = box.querySelector('.rectsl-study');
  const part = inner === 'card' ? card : document.querySelector('.ai-chat-window[data-uid="rectsl_source"]').closest('.disclosure');
  const rects = [box, part].map(e => e.getBoundingClientRect());
  // A long card scrolls inside its output area; stop at what the area shows.
  const shown = part.closest('.disclosure-content')?.getBoundingClientRect();
  const bottom = Math.max(...rects.map(r => r.bottom));
  return {width: window.innerWidth, region: [Math.min(...rects.map(r => r.left)), Math.min(...rects.map(r => r.top)),
          Math.max(...rects.map(r => r.right)), shown ? Math.min(bottom, shown.bottom) : bottom]};
})"""


async def capture(port: int, workspace: Path) -> None:
    log = fb.SessionLogger(fb.LOG_ROOT, ["rectsl-conversation-docs-capture"])
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
        # The window opens and the scene starts by themselves (autorun); running the scene here
        # redraws its card for this browser as well.
        await command("wait-native-renderer rectsl_window --frames 5 --timeout 120")
        await command(f"run {SCENE} --timeout 240")
        card = await evaluate(CARD_READY)
        assert "GPU" in card["text"], "The card's frames were not drawn on the GPU: " + card["text"]
        print(name, "card", card, flush=True)
        return card

    async def native(name: str) -> None:
        """The scene's own window, as it is drawing now, at half size on a high-density display."""
        await command(f"native-screenshot {ASSETS / name} --uid rectsl_window")
        with Image.open(ASSETS / name) as shot:
            if shot.width > 1600:
                shot.resize((shot.width // 2, shot.height // 2), Image.LANCZOS).save(ASSETS / name)

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
        for name in ("rectsl_conversation", EXAMPLE):
            shutil.copytree(DEMOS / name, workspace / name, ignore=IGNORED)

        await open_study("rectsl_conversation")

        await c.open_document(EXAMPLE, scratch=False, must_exist=True, keep_running=True, timeout=60)
        reply = await c.call_for_single_reply("ai-chat-history", {"uid": SCENE}, timeout=30)
        saved = (workspace / EXAMPLE / CHAT_FILE).read_text()
        assert reply["payload"]["history"] == saved, "The restored chat differs from the saved sidecar"
        print("restored chat matches its sidecar:", len(saved), "characters", flush=True)
        await open_study(EXAMPLE)

        # Hold the window with the card's scrubber, as a person would, and photograph the window itself.
        await evaluate(f"""(async () => {{
          const scrub = document.querySelector('.rectsl-study .field-slider[data-scrub]');
          window.updateFieldSlider(scrub, {HELD_AT});
          await new Promise(r => setTimeout(r, 1500)); return scrub.dataset.value; }})()""")
        await native("window.png")
        print("captured window.png at", HELD_AT, "s", flush=True)

        # The editor and the window: with the cursor on the flame's line, the window outlines every
        # lantern's flame. The cursor is put there as a person's click in the editor would put it.
        source = (workspace / EXAMPLE / "rectsl_source_rectSL_.rectsl").read_text().splitlines()
        flame = next(n for n, line in enumerate(source, 1) if '"flame" rect' in line)
        await command(f"click-box {SCENE}")
        # The editor loads the box's text, and the view state it last had, after the click; place the
        # cursor once that has happened, and see that it stays.
        at = await evaluate(f"""(async () => {{
          const ed = window.globalEditor;
          for (let i = 0; i < 100; i++) {{
            if (ed.getModel().getLanguageId() === 'rectsl' && ed.getModel().getLineContent({flame}).includes('"flame" rect')) break;
            await new Promise(r => setTimeout(r, 100));
          }}
          for (let i = 0; i < 10; i++) {{
            ed.focus(); ed.setPosition({{lineNumber: {flame}, column: 3}});
            await new Promise(r => setTimeout(r, 1000));
            if (ed.getPosition().lineNumber === {flame}) break;
          }}
          await new Promise(r => setTimeout(r, 1000));
          return {{line: ed.getPosition().lineNumber, lang: ed.getModel().getLanguageId()}}; }})()""")
        assert at == {"line": flame, "lang": "rectsl"}, f"the editor is not on the flame's line of the scene: {at}"
        await native("outline.png")
        print("captured outline.png, the cursor on line", flame, flush=True)

        await evaluate("""(async () => {
          const content = document.querySelector('.rectsl-study').closest('.disclosure-content');
          content.style.width = '1100px'; content.style.height = '1180px';
          content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
          await new Promise(r => setTimeout(r, 800)); return true; })()""")
        await shoot("card", "card.png")

        await evaluate(f"window.openAiChatForBox('{SCENE}', 'rectSL'); true")
        restored = await evaluate("""(async () => {
          for (let i = 0; i < 300; i++) {
            const panel = document.querySelector('.ai-chat-window[data-uid="rectsl_source"]');
            if (panel?.querySelectorAll('.ai-chat-turn.you').length === 5) {
              const images = [...panel.querySelectorAll('img')];
              await Promise.all(images.map(img => img.complete ? null : new Promise(r => { img.onload = r; img.onerror = r; })));
              if (images.some(img => img.naturalWidth === 0)) throw new Error('A chat image did not load');
              // The card closes so the chat sits under the box; a disclosure keeps its dragged size.
              window.disclosureInterface(document.querySelector('.rectsl-study').closest('.disclosure-group')).close('study');
              const content = panel.closest('.disclosure-content');
              content.style.width = '640px'; content.style.height = '900px';
              content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
              await new Promise(r => setTimeout(r, 800));
              const reply = [...panel.querySelectorAll('.ai-chat-turn.ai')].find(t => t.textContent.includes('already loops seamlessly'));
              if (!reply) throw new Error('The recorded loop reply is missing');
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
    parser.add_argument("--port", type=int, default=9798)
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="field-rectsl-chat-capture-") as workspace:
        asyncio.run(capture(args.port, Path(workspace)))
