"""Capture real starter and restored-chat screenshots for the function walkthrough.

Run after packaging the example chat, using uv's backdoor group. A cold frontend
build can exceed server startup limits: prebuild jsBrowserDevelopmentWebpack if
needed. Uses copies in a temporary workspace and makes no model calls.
The worked metadata is canonicalized through Field before copying it; rerun
package_function_conversation.py afterwards to refresh the downloadable zip.
"""

import argparse
import asyncio
import json
import shutil
import tempfile
from pathlib import Path
import field_backdoor as fb
from PIL import Image

ROOT = fb.REPO_ROOT
ASSETS = ROOT / "public/docs/assets/function_conversation"


async def capture(port, workspace):
    WORK = Path(workspace)
    log = fb.SessionLogger(fb.LOG_ROOT, ["function-conversation-docs-capture"])
    c = fb.FieldBackdoor(
        port=port, workspace=str(WORK), logger=log, default_timeout=120
    )

    async def command(cmd):
        r = await fb.dispatch_command(c, fb.parse_command(cmd), cmd)
        if not r["ok"]:
            raise RuntimeError(json.dumps(r)[:2000])
        return r

    async def evaluate(code):
        r = await c.playwright_input(
            action="pageEval", script=code, script_mode="expression", timeout=60
        )
        if not r.get("ok"):
            raise RuntimeError(json.dumps(r)[:2000])
        return r.get("result")

    async def snippet(code):
        b = await c.create_box(
            name="capture setup", x=-4000, y=-4000, w=100, h=50, timeout=30
        )
        uid = b["box"]["uid"]
        r, ev, t = await c.run_box(uid, property_name="code", value=code, timeout=120)
        r, ok = fb.grade_execution(c, r, ev, timed_out=t, fail_on_error=True)
        if not ok:
            raise RuntimeError(str(r)[:2000])
        await c.delete_boxes([uid], timeout=30)

    try:
        await c.ensure_server()
        assert (
            c.managed_process is not None
        ), "Use a free port; capture must own its server"
        await c.connect()
        await c.open_document(None, scratch=True, timeout=60)
        doc = ROOT / "dev_docs/agents/demos/function_conversation_example"
        await snippet(
            "val folder = "
            + json.dumps(str(doc))
            + "\nprintln(afield.SavingContext().refreshInlineFilesFromDisk(folder))"
        )
        print("Canonical metadata with embedded saved chat written", flush=True)
        for name in ["function_conversation", "function_conversation_example"]:
            shutil.copytree(
                ROOT / "dev_docs/agents/demos" / name, WORK / name, dirs_exist_ok=True
            )
            # Use the viewer's public size option in the disposable capture copy.
            # The downloadable document retains its larger default viewer.
            tool_source = WORK / name / "function_tools_code_.kt"
            tool_source.write_text(
                tool_source.read_text().replace(
                    "SignalViewerOptions(label =",
                    "SignalViewerOptions(width = 650, label =",
                )
            )
            await c.open_document(name, scratch=False, must_exist=True, timeout=60)
            reply = await c.call_for_single_reply(
                "ai-chat-history", {"uid": "function_source"}, timeout=30
            )
            expected = (
                (WORK / name / "function_source_ai4_chat_history_.md").read_text()
                if name.endswith("_example")
                else ""
            )
            assert (
                reply["payload"]["history"] == expected
            ), "Saved chat differs from the archive"
            await c.open_playwright(timeout=60)
            await c.set_frame("function_source", {"x": 60, "y": 60, "w": 670, "h": 95})
            await c.set_frame("function_tools", {"x": 820, "y": 60, "w": 690, "h": 95})
            await command("run function_tools --timeout 120")
            result = await c.ai_tool_call(
                name="InspectFunctionStudy", arguments={}, timeout=60
            )
            print(name, "revision", result["result"]["revision"], flush=True)
            await evaluate("""(() => {
      for (const group of document.querySelectorAll('.box .disclosure-group')) window.disclosureInterface(group).closeAll();
      const tools = [...document.querySelectorAll('.box')].find(b=>b.uid==='function_tools');
      window.disclosureInterface(tools.querySelector('.disclosure-group')).open('overview');
      window.openAiChatForBox('function_source','code');
      return true;
   })()""")
            await evaluate("""(async () => {
              for(let n=0;n<100;n++) {
                const panel=document.querySelector('.ai-chat-window[data-uid="function_source"]');
                if(panel) {
                  await new Promise(r=>setTimeout(r,500));
                  const content=panel.closest('.disclosure-content');
                  content.style.width='660px'; content.style.height='760px'; content.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
                  const tools=[...document.querySelectorAll('.box')].find(b=>b.uid==='function_tools');
                  for(const content of tools.querySelectorAll('.disclosure-content')) {
                    content.style.width='680px'; content.style.height='650px'; content.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
                  }
                  return true;
                }
                await new Promise(r=>setTimeout(r,100));
              }
              throw new Error('Chat did not open');
            })()""")
            if name.endswith("_example"):
                await evaluate("""(async () => {
      for(let i=0;i<150;i++) {
        const panel=document.querySelector('.ai-chat-window[data-uid="function_source"]');
        if(panel?.querySelectorAll('.ai-chat-turn.you').length===7) {
          await Promise.all([...panel.querySelectorAll('img')].map(img => img.complete ? Promise.resolve() : new Promise(r=>{img.onload=r;img.onerror=r;})));
          
          const turns=[...panel.querySelectorAll('.ai-chat-turn.you')];
          const answer=[...panel.querySelectorAll('.ai-chat-turn.ai')].find(t=>t.textContent.includes('Moved e3'));
          if(!answer) throw new Error('Recorded move reply missing');
          answer.scrollIntoView({block:'start'});
          return {turns:turns.length,images:panel.querySelectorAll('img').length,text:turns[1].textContent};
        }
        await new Promise(r=>setTimeout(r,100));
      }
      throw new Error('Saved conversation did not restore');
    })()""")
            layout = await evaluate("""(async () => {
              await new Promise(r=>setTimeout(r,800));
              for (const box of document.querySelectorAll('.box')) {
                if(!['function_source','function_tools'].includes(box.uid)) continue;
                for(const content of box.querySelectorAll('.disclosure.open > .disclosure-content')) {
                  content.style.width=box.uid==='function_source'?'660px':'680px';
                  content.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
                }
              }
              await new Promise(r=>setTimeout(r,500));
              const viewer=document.querySelector('.field-signal-viewer');
              if(viewer.getBoundingClientRect().right>1510) throw new Error('Viewer exceeds capture');
              const panel=document.querySelector('.ai-chat-window');
              const images=[...panel.querySelectorAll('img')];
              if(images.some(i=>!i.complete || i.naturalWidth===0)) throw new Error('Chat image missing');
              return {turns:panel.querySelectorAll('.ai-chat-turn.you').length, images:images.length,
                right:viewer.getBoundingClientRect().right};
            })()""")
            print("Verified restored UI", layout, flush=True)
            output = "saved-chat.png" if name.endswith("_example") else "two-boxes.png"
            await command(
                f"screenshot-window {ASSETS/output} --no-pan --preserve-selection"
            )
            # Crop unused canvas only; all visible chat and plot pixels are Field's UI.
            with Image.open(ASSETS / output) as shot:
                shot.crop((40, 60, 3120, 1760)).save(ASSETS / output)
            print("Captured", output, flush=True)
    finally:
        await c.close()
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9796)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(
        prefix="field-function-chat-capture-"
    ) as workspace:
        asyncio.run(capture(args.port, workspace))
