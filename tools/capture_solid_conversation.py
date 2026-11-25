#!/usr/bin/env python3
"""Capture the SolidSL conversation page's pictures of Field itself.

Both come from the recorded nautilus document, reopened on a Field this script starts and owns, in
a temporary workspace: ``window.png`` is the study's own window turning the finished lamp, and
``study-box.png`` the study box with the turntable strip in its output. Nothing here makes a model
call. The script checks that the strip was drawn on the GPU before capturing.

The window must be able to open (an awake display).
Use: uv run --project . --group backdoor python tools/capture_solid_conversation.py [--port 9798]
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
ASSETS = ROOT / "public/docs/assets/solid_conversation"
EXAMPLE = "solidsl_conversation_example"


async def capture(port: int, workspace: Path) -> None:
    log = fb.SessionLogger(fb.LOG_ROOT, ["solid-conversation-docs-capture"])
    c = fb.FieldBackdoor(port=port, workspace=str(workspace), logger=log, default_timeout=120)

    async def command(line: str) -> dict:
        result = await fb.dispatch_command(c, fb.parse_command(line), line)
        if not result["ok"]:
            raise RuntimeError(line + ": " + json.dumps(result)[:2000])
        return result

    try:
        await c.ensure_server()
        assert c.managed_process is not None, "Use a free port; the capture must own its server"
        await c.connect()
        shutil.copytree(DEMOS / EXAMPLE, workspace / EXAMPLE)
        await c.open_document(EXAMPLE, scratch=False, must_exist=True, keep_running=True, timeout=60)
        await c.open_playwright(timeout=90)
        await command("wait-native-renderer solidsl_window --frames 5 --timeout 120")
        # Inspect checks the study in and draws the strip; renderedBy says what drew it.
        facts = {}
        for _ in range(30):
            r = await c.ai_tool_call(name="ObserveSolidStudy", arguments={"seconds": [0]}, timeout=240, focus_box="solid_source")
            facts = (r.get("result") or {}).get("facts", {})
            if r.get("ok") and "GPU" in facts.get("renderedBy", ""):
                break
            await asyncio.sleep(2)
        assert "GPU" in facts.get("renderedBy", ""), "The study did not draw on the GPU: " + json.dumps(facts)[:500]
        await asyncio.sleep(3)
        await command(f"native-screenshot {ASSETS / 'window.png'} --uid solidsl_window")
        await command(f"screenshot-box solid_source {ASSETS / 'study-box.png'}")
        for name in ("window.png", "study-box.png"):
            with Image.open(ASSETS / name) as shot:
                if shot.width > 1600:
                    shot.resize((shot.width // 2, shot.height // 2), Image.LANCZOS).save(ASSETS / name)
            print("captured", name, flush=True)
    finally:
        await c.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9798)
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="field-solid-chat-capture-", ignore_cleanup_errors=True) as workspace:
        asyncio.run(capture(args.port, Path(workspace)))
