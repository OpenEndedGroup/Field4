"""Capture the figures of ``public/docs/kits.md`` and check the command output the page quotes.

On a Field this script starts, in a temporary workspace with a temporary personal-templates root, it
builds the page's document from the page's own Kotlin fences, then runs the page's ``field_kit.py``
commands in the page's order: make a kit, publish it, open the starting point, change the kit, migrate,
have an agent edit a source and watch the migration refuse, fold the edit into the kit and migrate.
Each figure is the ``showKit()`` card of the document at that moment, or the Welcome screen's Templates
section as Field serves it at ``/welcome``. Each ``text`` fence on the page must equal what the command
it follows printed, with this run's temporary paths written as the page writes them; ``--update-page``
writes the real output into those fences instead of failing.

Use: uv run --project . --group backdoor python tools/capture_kits.py [--port 9884] [--update-page]
"""

import argparse
import asyncio
import json
import os
import re
import ssl
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

from field_python_env import ensure_repo_python

ensure_repo_python()
import field_backdoor as fb  # noqa: E402
from PIL import Image  # noqa: E402

ROOT = fb.REPO_ROOT
PAGE = ROOT / "public/docs/kits.md"
ASSETS = ROOT / "public/docs/assets/kits"
TOOL = ROOT / "tools/field_kit.py"
ROOM_RULES = "minRoom = 4\nmaxRoom = 9\n"
AGENT_RULES = "minRoom = 4\nmaxRoom = 12\n"
PALETTE = "#3a6ea5\n#e0c068\n"
FENCE = re.compile(r"```diffstatic \{data-lang=(\w+)\}\n(.*?)\n```", re.S)

# The rectangle of the first element matching a selector (in the page, or inside the welcome iframe), in CSS pixels.
REGION = """((selector, inFrame, uid) => {
  const doc = inFrame ? document.getElementById('kits-welcome').contentDocument : document;
  const within = uid ? [...doc.querySelectorAll('.box')].find(b => b.uid === uid) : doc;
  const found = [...within.querySelectorAll(selector)].filter(e => e.getBoundingClientRect().width > 0);
  if (!found.length) throw new Error('nothing matches ' + selector);
  const e = found[found.length - 1], r = e.getBoundingClientRect();
  return {width: window.innerWidth, region: [r.left, r.top, r.right, r.top + Math.max(r.height, e.scrollHeight)]};
})"""


def page_fences() -> list[tuple[str, str]]:
    return FENCE.findall(PAGE.read_text())


def kotlin(marker: str) -> str:
    """The page's Kotlin fence that contains [marker]: the box the figure shows is the box the reader is given."""
    found = [body for lang, body in page_fences() if lang == "kotlin" and marker in body]
    assert len(found) == 1, f"expected one Kotlin fence containing {marker!r}, found {len(found)}"
    return found[0] + "\n"


class Run:
    def __init__(self, port: int, temp: Path) -> None:
        self.port = port
        self.workspace = temp / "workspace"
        self.kits = temp / "kits"
        self.templates = temp / "templates"
        self.workspace.mkdir()
        self.outputs: list[str] = []
        self.log = fb.SessionLogger(fb.LOG_ROOT, ["kits-docs-capture"])

    def kit_tool(self, *args: str, expect: int = 0) -> str:
        """Run field_kit.py as the page shows it, and keep its output in this run's order."""
        run = subprocess.run([sys.executable, str(TOOL), *args], capture_output=True, text=True, cwd=ROOT,
                             env={**os.environ, "PORT": str(self.port)})
        text = (run.stdout + run.stderr).strip("\n").rstrip()
        print(f"$ field_kit.py {' '.join(args)}\n{text}", flush=True)
        assert run.returncode == expect, f"field_kit.py {args[0]} exited {run.returncode}, expected {expect}"
        for real, written in [(str(self.kits), "~/kits"), (str(self.templates), "~/.afield/templates")]:
            text = text.replace(real, written)
        self.outputs.append(text)
        return text

    def client(self) -> "fb.FieldBackdoor":
        return fb.FieldBackdoor(port=self.port, workspace=str(self.workspace), logger=self.log, default_timeout=120)

    async def tool(self, c, tool_name: str, **arguments) -> dict:
        reply = await c.ai_tool_call(name=tool_name, arguments=arguments, timeout=180)
        assert reply.get("ok") and reply["result"].get("success", True), f"{tool_name}: {json.dumps(reply)[:1500]}"
        return reply["result"]

    async def evaluate(self, c, script: str):
        result = await c.playwright_input(action="pageEval", script=script, script_mode="expression", timeout=120)
        if not result.get("ok"):
            raise RuntimeError(json.dumps(result)[:2000])
        return result.get("result")

    async def shoot(self, c, name: str, selector: str, in_frame: bool = False, uid: str | None = None) -> None:
        measured = await self.evaluate(c, REGION + f"({json.dumps(selector)}, {json.dumps(in_frame)}, {json.dumps(uid)})")
        window = self.workspace / f"window-{name}"
        result = await fb.dispatch_command(c, fb.parse_command(f"screenshot-window {window} --no-pan --preserve-selection"), "")
        assert result["ok"], json.dumps(result)[:2000]
        # Cut the element out of one capture of the whole window; nothing inside is altered.
        with Image.open(window) as shot:
            scale = shot.width / measured["width"]
            left, top, right, bottom = (round(v * scale) for v in measured["region"])
            margin = round(12 * scale)
            shot.crop((max(0, left - margin), max(0, top - round(4 * scale)), min(shot.width, right + margin),
                       min(shot.height, bottom + margin))).save(ASSETS / name)
        print("captured", name, measured["region"], flush=True)

    async def card(self, c, kit_box: str, name: str) -> None:
        """Run the document's Kit box and capture its card, with the output panel as tall as the card."""
        await self.tool(c, "RunBoxRequest", box=kit_box, timeoutSeconds=60)
        summary = (await self.tool(c, "InspectKitRequest"))["message"]
        await self.evaluate(c, """(async () => {
          const box = [...document.querySelectorAll('.box')].find(b => b.uid === UID);
          let card = null;
          for (let i = 0; i < 300 && !card; i++) {
            card = [...box.querySelectorAll('[data-kit]')].find(e => e.textContent.includes(SUMMARY));
            if (!card) await new Promise(r => setTimeout(r, 100));
          }
          if (!card) throw new Error('the card does not show ' + SUMMARY);
          await new Promise(r => setTimeout(r, 1000));
          const content = card.closest('.disclosure-content');
          // A panel keeps the size of its first card; a later, longer card would scroll inside it.
          for (let e = card; e && e !== content; e = e.parentElement) { e.style.height = 'auto'; e.style.maxHeight = 'none'; }
          content.style.height = (card.scrollHeight + 24) + 'px';
          content.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
          await new Promise(r => setTimeout(r, 800)); return true; })()""".replace("UID", json.dumps(kit_box)).replace("SUMMARY", json.dumps(summary)))
        await self.shoot(c, name, "[data-kit]", uid=kit_box)

    def open_starting_point(self, name: str) -> str:
        request = urllib.request.Request(f"https://localhost:{self.port}/api/starting-points/open", data=json.dumps({"name": name}).encode(),
                                         headers={"Content-Type": "application/json", "Origin": f"https://localhost:{self.port}"}, method="POST")
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with urllib.request.urlopen(request, context=context) as response:
            return json.load(response)["filename"]


async def capture(port: int, update_page: bool) -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kits-capture-") as temp:
        run = Run(port, Path(temp))
        # The published starting point goes to a templates root of this run's own, not the user's ~/.afield/templates.
        os.environ["AFIELD_PERSONAL_TEMPLATES_DIR"] = str(run.templates)
        kit = run.kits / "level-kit"
        c = run.client()
        try:
            await c.ensure_server()
            assert c.managed_process is not None, "Use a free port; the capture must own its server"
            await c.connect()
            await c.open_document("level-one", scratch=False, keep_running=True, timeout=60)
            await c.open_playwright(timeout=90)
            rules = (await run.tool(c, "NewBoxRequest", name="Rules", code=kotlin("roomRules")))["uid"]
            plan = (await run.tool(c, "NewBoxRequest", name="Plan", code=kotlin("three rooms")))["uid"]
            card = (await run.tool(c, "NewBoxRequest", name="Kit", code=kotlin("showKit()")))["uid"]
            for prop in ("roomRules", "rulesBaseline"):
                await run.tool(c, "SetBoxPropertyRequest", box=rules, property=prop, value=ROOM_RULES)
            files = run.workspace / "level-one/files"
            files.mkdir(parents=True, exist_ok=True)
            (files / "palette.txt").write_text(PALETTE)
            for box in (rules, plan):
                await run.tool(c, "RunBoxRequest", box=box, timeoutSeconds=60)
            await c.save_document()

            # Make a kit from the document, and publish it.
            run.kit_tool("make", str(kit), "--document", "level-one", "--role", "rules=Rules", "--role", "plan=Plan",
                         "--source", "rules.roomRules", "--frozen", "rules.rulesBaseline=rules.roomRules", "--asset", "palette.txt")
            await run.card(c, card, "made.png")
            run.kit_tool("publish", "--document", "level-one")
            await run.evaluate(c, """(async () => {
              const frame = document.createElement('iframe');
              frame.id = 'kits-welcome'; frame.src = '/welcome';
              frame.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;border:0;z-index:100000;background:#111';
              document.body.appendChild(frame);
              await new Promise(r => frame.onload = r);
              for (let i = 0; i < 200; i++) {
                const section = frame.contentDocument.querySelector('details[data-section="templates"]');
                if (section && section.textContent.includes('level-kit')) {
                  section.open = true; section.scrollIntoView({block: 'center'});
                  await new Promise(r => setTimeout(r, 800)); return true;
                }
                await new Promise(r => setTimeout(r, 100));
              }
              throw new Error('the Welcome screen does not list level-kit'); })()""")
            await run.shoot(c, "welcome.png", 'details[data-section="templates"]', in_frame=True)
            await run.evaluate(c, "document.getElementById('kits-welcome').remove(); true")
            opened = run.open_starting_point("level-kit")
            assert opened == "level-kit", opened
            run.kit_tool("activate", str(kit), "--document", "level-kit")

            # The kit changes; a document made from it changes only when migrated.
            (kit / "roles/plan/code.kt").write_text(kotlin("four rooms"))
            run.kit_tool("rehash", str(kit))
            second = run.client()
            try:
                await second.ensure_server()
                await second.connect()
                await second.open_document("level-kit", scratch=False, keep_running=True, timeout=60, must_exist=True)
                await second.open_playwright(timeout=90)
                copied_card = next(b["uid"] for b in (await run.tool(second, "ListBoxesRequest"))["boxes"] if b["name"] == "Kit")
                await run.card(second, copied_card, "upgrade.png")
                run.kit_tool("migrate", str(kit), "--document", "level-kit", "--dry-run")
                run.kit_tool("migrate", str(kit), "--document", "level-kit")
            finally:
                await second.close()
            # The capture browser has one page, and the second session took it; bring it back to level-one.
            await c.open_playwright(timeout=90)

            # An agent edits the rules in the first document; the migration refuses to overwrite them.
            await run.tool(c, "SetBoxPropertyRequest", box=rules, property="roomRules", value=AGENT_RULES)
            run.kit_tool("migrate", str(kit), "--document", "level-one", expect=2)
            await run.card(c, card, "refused.png")
            # Folded into the kit, the edit is the kit's; the frozen copy of the rules is the kit's to replace.
            (kit / "roles/rules/roomRules.txt").write_text(AGENT_RULES)
            run.kit_tool("rehash", str(kit))
            await run.card(c, card, "folded.png")
            run.kit_tool("migrate", str(kit), "--document", "level-one")
            final = await run.tool(c, "InspectKitRequest")
            assert not final["details"]["differences"] and final["details"]["recordedRevision"] == final["details"]["kitRevision"], final
            print("kit.inspect:", final["message"], flush=True)
        finally:
            await c.close()
            run.log.close()
        check_outputs(run.outputs, update_page)


def check_outputs(outputs: list[str], update_page: bool) -> None:
    text = PAGE.read_text()
    quoted = [m for m in FENCE.finditer(text) if m.group(1) == "text"]
    if update_page:
        assert len(quoted) == len(outputs), f"the page has {len(quoted)} text fences for {len(outputs)} commands"
        for match, output in reversed(list(zip(quoted, outputs))):
            text = text[:match.start(2)] + output + text[match.end(2):]
        PAGE.write_text(text)
        print(f"wrote {len(outputs)} command outputs into {PAGE.name}")
        return
    differ = [(i, q.group(2), o) for i, (q, o) in enumerate(zip(quoted, outputs)) if q.group(2) != o]
    assert len(quoted) == len(outputs) and not differ, (
        f"the page quotes {len(quoted)} outputs for {len(outputs)} commands; differing: " +
        "\n".join(f"#{i}:\n  page: {p!r}\n  real: {o!r}" for i, p, o in differ))
    print(f"all {len(outputs)} quoted outputs match")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9884)
    parser.add_argument("--update-page", action="store_true", help="write the real command output into the page's text fences")
    a = parser.parse_args()
    asyncio.run(capture(a.port, a.update_page))
