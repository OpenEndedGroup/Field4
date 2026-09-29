#!/usr/bin/env python3
"""Capture the stack.auto conversation page's pictures of Field itself.

``starter.png`` is the starter's window as it opens: the polytope whole and grey, both slots inert.
The rest come from the recorded polytope document, reopened: ``burst.mp4`` its window across two of
the burst's 4.8-second cycles, ``card.png`` the polytope box with the explode slot's part of its
numbers card, ``in-shader.png`` face_decay as the card's Show it in your shader draws it, pressed early in a
burst, and
``saved-chat.png`` the box with its saved AI4 chat restored, at the
model's last reply.

Both documents are copies in a temporary workspace, on a Field this script starts and owns, and
nothing here makes a model call: the card's sliders are named from the names stored with each slot
(``knob-names.json``, written by the fast model when the demo was recorded). Before capturing, the
script checks that the chat Field restores is byte for byte the saved chat.

The clip is real time, not frame by frame: a box added to the copy captures the window as fast as the
window can be read back, logging when each frame was taken, and ffmpeg holds each frame for the time
until the next, so the clip runs at the speed the window did.

The window must be able to open (an awake display). Run the packager afterwards; ``--check`` requires
every picture to exist.
Use: uv run --project . --group backdoor python tools/capture_stackauto_conversation.py [--port 9853]
"""

from __future__ import annotations

import argparse
import asyncio
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

from field_python_env import ensure_repo_python

ensure_repo_python()
import field_backdoor as fb  # noqa: E402
from PIL import Image  # noqa: E402

ROOT = fb.REPO_ROOT
DEMOS = ROOT / "dev_docs/agents/demos"
ASSETS = ROOT / "public/docs/assets/stackauto_conversation"
STARTER = "stackauto_conversation"
EXAMPLE = "stackauto_conversation_polytope"
CHAT_FILE = "polytope_source_ai4_chat_history_.md"
BOX = "polytope_source"
RENDERER = "polytope_renderer"
# Two cycles of the burst.
CLIP_SECONDS = 9.6
# When, after the polytope box runs, the card's Show it in your shader is pressed: early in a burst.
PRESS_AT = 0.3
IGNORED = shutil.ignore_patterns("backups", "history_screenshots", "observations", "thumbnails", "images")

# The recorder box: beneath the renderer, so the window is its context. Capturing waits for a frame the
# main thread draws, so it runs on a thread of its own.
RECORDER = """import afield.four.Renderer
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()
val frames = java.io.File("FRAMES")
frames.mkdirs()
Thread {
    val start = System.nanoTime()
    var i = 0
    val times = StringBuilder()
    while ((System.nanoTime() - start) / 1e9 < SECONDS) {
        val at = (System.nanoTime() - start) / 1e9
        kotlinx.coroutines.runBlocking { renderer.captureWindowToFile(renderer.windows[0], java.io.File(frames, "f%05d.png".format(i)).absolutePath, 8000) }
        times.append("$i $at\\n")
        i++
    }
    java.io.File(frames, "times.txt").writeText(times.toString())
    java.io.File(frames, "done").writeText("$i")
}.start()
"""

CARD_READY = """(async () => {
  for (let i = 0; i < 600; i++) {
    const card = document.querySelector('.stack-auto-card');
    if (card && card.querySelectorAll('.slot').length === 2) return {slots: card.querySelectorAll('.slot').length,
      sliders: card.querySelectorAll('.field-slider').length};
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('The numbers card did not show both slots');
})()"""

# The box and one part of its outputs, measured in the page: the explode slot's section of the card, the
# face_decay slot's picture in the shader (without the box), or the chat's output area.
REGION = """((inner) => {
  const box = [...document.querySelectorAll('.box')].find(b => b.uid === 'polytope_source');
  const part = inner === 'card' ? document.querySelector('.stack-auto-card .slot[data-slot=explode]')
    : inner === 'in-shader' ? document.querySelector('.stack-auto-card .slot[data-slot=face_decay] .views')
    : document.querySelector('.ai-chat-window[data-uid="polytope_source"]').closest('.disclosure');
  if (inner === 'in-shader') part.scrollIntoView({block: 'center'});
  const rects = (inner === 'in-shader' ? [part] : [box, part]).map(e => e.getBoundingClientRect());
  const shown = part.closest('.disclosure-content')?.getBoundingClientRect();
  const bottom = Math.max(...rects.map(r => r.bottom));
  return {width: window.innerWidth, region: [Math.min(...rects.map(r => r.left)), Math.min(...rects.map(r => r.top)),
          Math.max(...rects.map(r => r.right)), shown ? Math.min(bottom, shown.bottom) : bottom]};
})"""


def encode(frames: Path, out: Path) -> dict:
    """The frames as an H.264 clip, each held until the next was taken."""
    times = [float(line.split()[1]) for line in (frames / "times.txt").read_text().splitlines()]
    assert len(times) >= 40, f"only {len(times)} frames in {CLIP_SECONDS} s"
    listing = frames / "frames.txt"
    with listing.open("w") as f:
        for i, at in enumerate(times):
            f.write(f"file 'f{i:05d}.png'\n")
            f.write(f"duration {(times[i + 1] if i + 1 < len(times) else CLIP_SECONDS) - at:.4f}\n")
        f.write(f"file 'f{len(times) - 1:05d}.png'\n")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-vf", "scale=720:-2:flags=lanczos,format=yuv420p", "-r", "30", "-c:v", "libx264", "-crf", "20",
                    "-movflags", "+faststart", str(out)], check=True)
    return {"frames": len(times), "fps": round(len(times) / CLIP_SECONDS, 1)}


async def capture(port: int, workspace: Path) -> None:
    log = fb.SessionLogger(fb.LOG_ROOT, ["stackauto-conversation-docs-capture"])
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

    async def open_polytope(name: str) -> None:
        await c.open_document(name, scratch=False, must_exist=True, keep_running=True, timeout=60)
        await c.open_playwright(timeout=90)
        await command(f"launch {RENDERER} --timeout 120")
        await command(f"wait-native-renderer {RENDERER} --frames 5 --timeout 120")
        await command(f"run {BOX} --timeout 240")
        print(name, "card", await evaluate(CARD_READY), flush=True)

    async def native(name: str) -> None:
        await command(f"native-screenshot {ASSETS / name} --uid {RENDERER}")
        with Image.open(ASSETS / name) as shot:
            if shot.width > 1000:
                shot.resize((720, round(shot.height * 720 / shot.width)), Image.LANCZOS).save(ASSETS / name)

    async def shoot(inner: str, name: str) -> None:
        measured = await evaluate(REGION + f"('{inner}')")
        window = workspace / f"window-{inner}.png"
        await command(f"screenshot-window {window} --no-pan --preserve-selection")
        # Cut the region out of one capture of the whole window, plus a margin of canvas. Nothing inside is altered.
        with Image.open(window) as shot:
            scale = shot.width / measured["width"]
            left, top, right, bottom = (round(v * scale) for v in measured["region"])
            # The in-shader picture sits right under the card's buttons, so it is cut without a margin.
            margin = 0 if inner == "in-shader" else round(16 * scale)
            shot.crop((max(0, left - margin), max(0, top - margin), min(shot.width, right + margin),
                       min(shot.height, bottom + margin))).save(ASSETS / name)
        print("captured", name, measured["region"], flush=True)

    try:
        await c.ensure_server()
        assert c.managed_process is not None, "Use a free port; the capture must own its server"
        await c.connect()
        for name in (STARTER, EXAMPLE):
            shutil.copytree(DEMOS / name, workspace / name, ignore=IGNORED)

        await open_polytope(STARTER)
        await asyncio.sleep(2)
        await native("starter.png")
        await command(f"stop {RENDERER}")

        await c.open_document(EXAMPLE, scratch=False, must_exist=True, keep_running=True, timeout=60)
        reply = await c.call_for_single_reply("ai-chat-history", {"uid": BOX}, timeout=30)
        saved = (workspace / EXAMPLE / CHAT_FILE).read_text()
        assert reply["payload"]["history"] == saved, "The restored chat differs from the saved sidecar"
        print("restored chat matches its sidecar:", len(saved), "characters", flush=True)
        await open_polytope(EXAMPLE)

        # The clip, from a box added to this copy only; the burst starts when the polytope box runs.
        frames = workspace / "frames"
        recorder = (await command("new-box --name recorder"))["data"]["box"]["uid"]
        await command(f"connect {RENDERER} {recorder}")
        code = workspace / "recorder.kt"
        code.write_text(RECORDER.replace("FRAMES", str(frames)).replace("SECONDS", str(CLIP_SECONDS)))
        await command(f"set {recorder} code --file {code}")
        await command(f"run {BOX} --timeout 240")
        await command(f"run {recorder} --timeout 120")
        await command(f"wait-file {frames / 'done'} --timeout 120")
        print("burst.mp4", encode(frames, ASSETS / "burst.mp4"), flush=True)
        await command(f"delete-box {recorder}")

        # The card, with face_decay shown in the shader as its button shows it, pressed a moment into a
        # burst: the cycle counts from when the box runs.
        await command(f"run {BOX} --timeout 240")
        await asyncio.sleep(PRESS_AT)
        await evaluate("""(async () => {
          const b = document.querySelector('.stack-auto-card button[data-act=in-shader][data-slot=face_decay]');
          b.click();
          for (let i = 0; i < 300; i++) {
            const img = document.querySelector('.stack-auto-card .slot[data-slot=face_decay] .views img');
            if (img && img.complete && img.naturalWidth > 0) { await new Promise(r => setTimeout(r, 800)); return true; }
            await new Promise(r => setTimeout(r, 100));
          }
          throw new Error('face_decay was not shown in the shader'); })()""")
        await evaluate("""(async () => {
          const content = document.querySelector('.stack-auto-card').closest('.disclosure-content');
          content.style.width = '900px'; content.style.height = '1100px';
          content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
          await new Promise(r => setTimeout(r, 800)); return true; })()""")
        await shoot("card", "card.png")
        await shoot("in-shader", "in-shader.png")

        await evaluate(f"window.openAiChatForBox('{BOX}', 'code'); true")
        restored = await evaluate("""(async () => {
          for (let i = 0; i < 300; i++) {
            const panel = document.querySelector('.ai-chat-window[data-uid="polytope_source"]');
            if (panel?.querySelectorAll('.ai-chat-turn.you').length === 6) {
              const images = [...panel.querySelectorAll('img')];
              await Promise.all(images.map(img => img.complete ? null : new Promise(r => { img.onload = r; img.onerror = r; })));
              if (images.some(img => img.naturalWidth === 0)) throw new Error('A chat image did not load');
              window.disclosureInterface(document.querySelector('.stack-auto-card').closest('.disclosure-group')).close('stack.auto numbers');
              const content = panel.closest('.disclosure-content');
              content.style.width = '640px'; content.style.height = '900px';
              content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
              await new Promise(r => setTimeout(r, 800));
              const turns = [...panel.querySelectorAll('.ai-chat-turn.you')];
              const reply = [...panel.querySelectorAll('.ai-chat-turn.ai')].find(t => t.textContent.includes('the live cycle now matches'));
              if (!reply) throw new Error('The recorded last reply is missing');
              reply.scrollIntoView({block: 'end'});
              return {turns: turns.length, images: images.length};
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
    parser.add_argument("--port", type=int, default=9853)
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="field-stackauto-chat-capture-") as workspace:
        asyncio.run(capture(args.port, Path(workspace)))
