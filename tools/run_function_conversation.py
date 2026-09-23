#!/usr/bin/env python3
"""Send a real AI4 general-chat turn and preserve its actual UI conversation.

Requires the saved function_conversation document and its tools box running on
the supplied Field port. Uses the same ai-assist RPC as Field's chat UI. Does not
substitute scripted tool calls for model decisions. No keys are read or recorded.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import base64
import hashlib
import re
import time
from pathlib import Path

from field_python_env import ensure_repo_python
ensure_repo_python()
import field_backdoor as fb


def externalize_images(text: str, output: Path) -> str:
    """Preserve exact image bytes in sidecars instead of duplicating base64 in transcripts."""
    def store(match):
        data=base64.b64decode(match.group(1))
        images=output / "images"
        images.mkdir(exist_ok=True)
        name=hashlib.sha256(data).hexdigest()[:20]+".png"
        (images/name).write_bytes(data)
        return "images/"+name
    return re.sub(r"data:image/png;base64,([A-Za-z0-9+/=]+)",store,text)


async def main(args):
    root = Path(args.document).resolve()
    output = root / "conversations"
    output.mkdir(exist_ok=True)
    target = output / args.label
    if target.with_suffix(".json").exists():
        raise ValueError("Choose a new label; saved conversations are never overwritten")
    logger = fb.SessionLogger(fb.LOG_ROOT, ["run_function_conversation", args.label])
    client = fb.FieldBackdoor(port=args.port, workspace=str(root.parent), default_timeout=120,
                              logger=logger, attach_existing=True)
    prompt = Path(args.prompt_file).read_text()
    try:
        await client.ensure_server()
        await client.connect()
        await client.open_document(root.name, scratch=False, must_exist=True, timeout=120)
        # Explicit schema discovery verifies this session is actually offering the tools.
        tools = await client.ai_tools_list(timeout=30)
        names = {x.get("name") for x in tools}
        if "EditFunctionStudy" not in names:
            raise RuntimeError("Run the document's function_tools box first")
        source = await client.get_property(uid="function_source", property_name="code", timeout=30)
        before = await client.ai_tool_call(name="InspectFunctionStudy", arguments={}, timeout=30)
        seq = client.event_seq
        start = time.monotonic()
        await client.send_message("ai-assist", {"uid":"function_source", "property":"code",
            "prompt":source, "extraPrompt":prompt, "kind":"general", "startLine":0, "images":[]})
        print("AI4 turn started: " + args.label, flush=True)
        last_notice = ""
        status = "timed_out"
        while time.monotonic() - start < args.timeout:
            await asyncio.sleep(2)
            events = client.events_since(seq)
            notices = [e.get("payload",{}).get("text","") for e in events if e.get("name")=="notify"]
            if notices and notices[-1] != last_notice:
                last_notice = notices[-1]
                print(last_notice[:240], flush=True)
            if any("Finished AI conversation" in n or "AI general failed" in n or "AI4 setup" in n for n in notices):
                status = "completed" if any("Finished AI conversation" in n for n in notices) else "failed"
                break
        events = client.events_since(seq)
        panels = [e.get("payload",{}) for e in events if e.get("name")=="set-output"
                  and e.get("payload",{}).get("channel")=="ai-chat"]
        panel = panels[-1] if panels else {}
        # Set_output calls its text field 'message' in this version; retain the raw payload too.
        text = panel.get("message", panel.get("output", panel.get("value", "")))
        if not isinstance(text,str): text=json.dumps(text)
        text = externalize_images(text, output)
        panel = {**panel, "value": text}
        after = await client.ai_tool_call(name="InspectFunctionStudy", arguments={}, timeout=30)
        record = {"label":args.label, "status":status, "transport":"Field ai-assist kind=general (AI4 UI path)",
                  "prompt":prompt, "elapsedSeconds":round(time.monotonic()-start,3),
                  "before":before, "after":after, "panel":panel, "notices":notices,
                  "tokenUsage":"Not surfaced by AI4 SendResult; tool-io logs record model/rounds where available"}
        target.with_suffix(".json").write_text(json.dumps(record,indent=2))
        # The JSON preserves the whole UI conversation; the Markdown shows just this turn.
        turn = text[text.rfind("<<you>>"):] if "<<you>>" in text else text
        target.with_suffix(".md").write_text("# " + args.label + "\n\n" + turn)
        guides = root / "guides" / "conversations"
        guides.mkdir(exist_ok=True)
        (guides / (args.label + ".md")).write_text("# " + args.label + "\n\n" +
            turn.replace("](images/", "](../../conversations/images/"))
        print(json.dumps({"saved":str(target.with_suffix('.json')), "seconds":record['elapsedSeconds'],
                          "panelKeys":list(panel), "notices":notices[-2:]}), flush=True)
        if status != "completed":
            raise RuntimeError("AI4 turn " + status + "; partial evidence saved and working revision/server left in place")
    finally:
        await client.close()
        logger.close()


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--document", default="dev_docs/agents/demos/function_conversation",
                        help="Path to the document receiving the turn and its saved evidence")
    parser.add_argument("--port",type=int,default=9788)
    parser.add_argument("--label",required=True)
    parser.add_argument("--prompt-file",required=True)
    parser.add_argument("--timeout",type=float,default=600)
    asyncio.run(main(parser.parse_args()))
