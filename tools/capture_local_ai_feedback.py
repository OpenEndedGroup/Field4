"""Capture local_ai.md's live-feedback screenshot from a real local inference host.

Starts a Field this script owns with the Local provider pointed at ``--base-url``, opens AI4 on a new
box, sends one prompt and captures the chat while the model server works on it. The status line is
what ``tools/local_ai_monitor.py`` on that host reports; nothing is staged. Needs a llama.cpp server
and its monitor (one port above the API) running, as the page describes, so it is not a runner capture.
Use: uv run --project . --group backdoor python tools/capture_local_ai_feedback.py --base-url http://gpu-host:8081/v1
"""

import argparse
import asyncio
import json
import os
import tempfile

from field_python_env import ensure_repo_python

ensure_repo_python()
import field_backdoor as fb  # noqa: E402

OUTPUT = fb.REPO_ROOT / "public/docs/assets/local_ai/live-feedback.png"
PROMPT = "Write a short Kotlin box that draws a spiral of 40 circles with FLine, and explain each line."

OPEN = """(async () => {
  const ask = [...document.querySelectorAll('.box')].find(b => b.textContent.includes('Ask'));
  window.openAiChatForBox(ask.uid, 'code');
  for (let i = 0; i < 50; i++) {
    await new Promise(r => setTimeout(r, 400));
    const f = document.querySelector('.ai-chat-window .local-ai-feedback');
    if (f && !f.hidden && f.textContent.includes('GPU')) return f.textContent;
  }
  throw new Error('No live status from the monitor: ' + document.querySelector('.local-ai-feedback')?.textContent);
})()"""

# Wait until the monitor reports the model busy with this prompt and the GPU under load.
ASK = """(async () => {
  const chat = [...document.querySelectorAll('*')].find(e => e.__aiChat).__aiChat;
  chat.textarea.value = %s;
  chat.submit();
  for (let i = 0; i < 200; i++) {
    await new Promise(r => setTimeout(r, 400));
    const t = document.querySelector('.ai-chat-window .local-ai-feedback')?.textContent || '';
    if (/Processing|Generating/.test(t) && !/GPU 0: 0%% load/.test(t)) return t;
  }
  throw new Error('The model never reported work');
})()""" % json.dumps(PROMPT)


async def capture(port, workspace):
    log = fb.SessionLogger(fb.LOG_ROOT, ["local-ai-feedback-docs-capture"])
    c = fb.FieldBackdoor(port=port, workspace=workspace, logger=log, default_timeout=120)

    async def command(line):
        r = await fb.dispatch_command(c, fb.parse_command(line), line)
        if not r["ok"]:
            raise RuntimeError(json.dumps(r)[:2000])
        return r

    async def evaluate(code):
        r = await c.playwright_input(action="pageEval", script=code, script_mode="expression", timeout=120)
        if not r.get("ok"):
            raise RuntimeError(json.dumps(r)[:2000])
        return r.get("result")

    try:
        await c.ensure_server()
        assert c.managed_process is not None, "Use a free port; capture must own its server"
        await c.connect()
        await c.open_document(None, scratch=True, timeout=60)
        await c.open_playwright(timeout=90)
        await command("new-box --name Ask")
        print("idle:", await evaluate(OPEN), flush=True)
        print("busy:", await evaluate(ASK), flush=True)
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        await command(f"screenshot-box @last {OUTPUT}")
        print("captured", OUTPUT, flush=True)
    finally:
        await c.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="the model server's /v1 address")
    parser.add_argument("--port", type=int, default=9797)
    args = parser.parse_args()
    # The Field this script starts inherits these; Local is chosen without touching saved settings.
    os.environ["AFIELD_AI_PROVIDER"] = "local"
    os.environ["AFIELD_LOCAL_AI_BASE_URL"] = args.base_url
    with tempfile.TemporaryDirectory(prefix="field-local-ai-capture-", ignore_cleanup_errors=True) as workspace:
        asyncio.run(capture(args.port, workspace))
