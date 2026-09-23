"""Real save/restart/browser regression; no model calls. Run with uv's backdoor group.

Uses a temporary document and an unused dedicated port, never an existing Field.
Screenshot: build/ui-regressions/artifacts/ai4-chat-persistence/restored.png.
"""
import argparse
import asyncio
import json
import tempfile
from pathlib import Path

import field_backdoor as fb


async def verify(port: int, workspace: str):
    uid = None
    other_uid = None
    transcript = ('<<you>>\nMake three drifting sine waves.\n<<ai>>\n'
                  '<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>\n'
                  'Three channels, one hour. Keep channel z delayed by 1.25 seconds.\n'
                  '```kotlin\nval duration = 3600.0\n```\n'
                  '<<progress>>\nCompiled and checked\nAll three channels stay between zero and one.\n')
    for phase in range(3):
        logger = fb.SessionLogger(fb.LOG_ROOT, ["ai4-chat-persistence", str(phase)])
        client = fb.FieldBackdoor(port=port, workspace=workspace, logger=logger, default_timeout=90)
        try:
            await client.ensure_server()
            assert client.managed_process is not None, "Use a free port; this regression must own its server"
            await client.connect()
            await client.open_document("conversation", scratch=False, must_exist=phase > 0, timeout=60)
            print(f"phase {phase}: fresh Field, document {client.session_key}", flush=True)
            if phase == 0:
                box = await client.create_box(name="Saved AI4 conversation", x=40, y=40, w=650, h=70, timeout=30)
                uid = box["box"]["uid"]
                other = await client.create_box(name="Independent conversation", x=850, y=40, w=300, h=70, timeout=30)
                other_uid = other["box"]["uid"]
                code = ('val owner = afield.Box.context.get()!!\n'
                        'afield.ai.core.AiBoxConversation.write(owner, ' + json.dumps(transcript) + ')\n')
                result, _, timed_out = await client.run_box(uid, property_name="code", value=code, timeout=90)
                assert not timed_out and not result.get("errors"), result
                await client.set_string(other_uid, "ai4_chat_history", "<<you>>\nOther box only.")
                expected = transcript
            else:
                reply = await client.call_for_single_reply("ai-chat-history", {"uid": uid}, timeout=15)
                expected = transcript if phase == 1 else ""
                assert reply["payload"]["history"] == expected, reply
                other = await client.call_for_single_reply("ai-chat-history", {"uid": other_uid}, timeout=15)
                assert other["payload"]["history"] == "<<you>>\nOther box only.", other

            if phase == 1:
                await client.open_playwright(timeout=60)
                script = """(async () => {
                    const uid = UID;
                    window.openAiChatForBox(uid, 'code');
                    const find = () => document.querySelector('.ai-chat-window[data-uid="' + uid + '"]');
                    const until = async predicate => {
                        for (let n=0; n<100; n++) {
                            if (predicate()) return;
                            await new Promise(resolve => setTimeout(resolve, 50));
                        }
                        throw new Error('Chat UI did not reach the expected state');
                    };
                    await until(() => find()?.textContent.includes('delayed by 1.25 seconds'));
                    if (!find().textContent.includes('gpt-6-astra · OpenAI')) throw new Error('Model label lost');
                    if (!find().querySelector('pre code')) throw new Error('Code block lost');
                    return {restored: true, model: find().querySelector('.ai-chat-model').textContent};
                })()""".replace("UID", json.dumps(uid))
                result = await client.playwright_input(action="pageEval", script=script, script_mode="expression", timeout=30)
                assert result.get("ok"), result
                print(json.dumps(result), flush=True)
                artifact = fb.REPO_ROOT / "build/ui-regressions/artifacts/ai4-chat-persistence/restored.png"
                artifact.parent.mkdir(parents=True, exist_ok=True)
                command = f"screenshot-box {uid} {artifact} --padding 12"
                result = await fb.dispatch_command(client, fb.parse_command(command), command)
                assert result["ok"], result
                clear = """(async () => {
                    const input = document.querySelector('.ai-chat-window[data-uid="' + UID + '"] textarea');
                    input.value = '/clear';
                    input.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true}));
                    for (let n=0; n<100; n++) {
                        if (!input.closest('.ai-chat-window').querySelector('.ai-chat-messages').textContent.trim() && !input.disabled) return {cleared:true};
                        await new Promise(resolve => setTimeout(resolve, 50));
                    }
                    throw new Error('/clear did not clear the saved chat');
                })()""".replace("UID", json.dumps(uid))
                result = await client.playwright_input(action="pageEval", script=clear, script_mode="expression", timeout=30)
                assert result.get("ok"), result
                expected = ""
                print("/clear completed in the browser", flush=True)

            if phase < 2:
                sidecar = Path(workspace) / "conversation" / f"{uid}_ai4_chat_history_.md"
                for _ in range(150):
                    if sidecar.exists() and sidecar.read_text() == expected:
                        break
                    await asyncio.sleep(0.1)
                else:
                    raise AssertionError("Autosave did not write the expected transcript")
                print("autosave verified on disk; restarting owned Field", flush=True)
        finally:
            await client.close()
            logger.close()
    print("PASS: saved chat restored after restart; UI clear survived another restart; other box unchanged", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9796)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="field-ai4-chat-") as workspace:
        asyncio.run(verify(args.port, workspace))
