#!/usr/bin/env python3
"""Record, act in, and publish Field's AI4 conversation demos ("talking a … into existence").

A conversation demo is a Field document with one study box — a function study, a StackSL program,
a RectSL scene — whose AI4 chat has the study's tools. This module is everything the demos share:

  record   send one real AI4 general-chat turn through the ``ai-assist`` RPC Field's chat panel uses,
           and keep what happened under the document's ``conversations/``: the prompt, the turn as
           Field rendered it (images moved to sidecars), the study before and after, the notices,
           and the turn's tool I/O log. No tool call is scripted in place of a model decision, and
           no key is read or recorded.
  act      do the person's half through the surfaces a person uses (StackSL and RectSL cards):
           ``adopt LABEL``, ``keep KNOB VALUE``, ``checkin`` click the study card in Field's
           server-owned browser; ``edit OLD NEW`` writes the program through the editor's sync path.
           Each action is kept as ``conversations/<label>.human.json``.

and, for the per-demo packagers, the transcript page renderer, the deterministic zip, the listing
sync between ``<!-- MARKER:NAME -->`` markers, and the asset check.

The document is opened with ``keepRunning``, so its window and running boxes outlive each
invocation, and a study tool call names the study box as its focus, exactly as its chat does.

Use:
  uv run --project . --group backdoor python tools/conversation_demo.py record --study stacksl \\
      --document dev_docs/agents/demos/stacksl_conversation_ink --label 01-bloom \\
      --prompt-file dev_docs/agents/demos/stacksl_conversation_ink/prompts/01-bloom.txt --port 9788
  uv run --project . --group backdoor python tools/conversation_demo.py act --study stacksl \\
      --document dev_docs/agents/demos/stacksl_conversation_ink --label 02a-adopt --port 9788 adopt B

The Field on ``--port`` must already be running with the document's workspace (the demo needs it to
stay up between turns); start it with ``./field.sh <workspace>`` and ``PORT=<port>``.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import io
import json
import re
import time
import zipfile
from dataclasses import dataclass
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Study:
    """What distinguishes one kind of study for recording: its box, property, tools and window."""

    box: str
    property: str
    inspect_tool: str
    edit_tool: str
    window_box: str | None = None
    card: str | None = None  # CSS selector of the study card, for person-side actions


STUDIES = {
    "function": Study("function_source", "code", "InspectFunctionStudy", "EditFunctionStudy"),
    "stacksl": Study("stacksl_source", "stackSL", "InspectStackSLStudy", "EditStackSLStudy", "stacksl_window", ".stacksl-study"),
    "rectsl": Study("rectsl_source", "rectSL", "InspectRectSLStudy", "EditRectSLStudy", "rectsl_window", ".rectsl-study"),
}

DATA_URL = re.compile(r"data:image/(png|jpeg);base64,([A-Za-z0-9+/=]+)")


def image_name(data: bytes, kind: str) -> str:
    """The sidecar name for an image: 20 hex characters of its SHA-256, and its own extension."""
    return hashlib.sha256(data).hexdigest()[:20] + (".jpg" if kind == "jpeg" else ".png")


def externalize_images(text: str, output: Path | None) -> str:
    """Replace each data-URL image with ``images/<hash>.<ext>``, writing the bytes there when [output] is given."""
    def store(match: re.Match) -> str:
        data = base64.b64decode(match.group(2))
        name = image_name(data, match.group(1))
        if output is not None:
            (output / "images").mkdir(exist_ok=True)
            (output / "images" / name).write_bytes(data)
        return "images/" + name
    return DATA_URL.sub(store, text)


def chat_images(chat: str) -> dict[str, bytes]:
    """Every image in a saved AI4 chat, named as [externalize_images] names them."""
    return {image_name(base64.b64decode(data), kind): base64.b64decode(data) for kind, data in DATA_URL.findall(chat)}


# ---- Recording -------------------------------------------------------------------------------------

def _backdoor():
    from field_python_env import ensure_repo_python

    ensure_repo_python()
    import field_backdoor as fb  # noqa: E402

    return fb


async def _open(fb, document: Path, port: int, study: Study, label: str, what: str):
    logger = fb.SessionLogger(fb.LOG_ROOT, [f"conversation_demo_{what}", label])
    client = fb.FieldBackdoor(port=port, workspace=str(document.parent), default_timeout=120, logger=logger, attach_existing=True)
    await client.ensure_server()
    await client.connect()
    await client.open_document(document.name, scratch=False, must_exist=True, keep_running=True, timeout=120)
    if study.window_box:
        # The window opens with the document (autorun) and stays up between invocations
        # (keepRunning); launch it only if it is not there.
        wait = f"wait-native-renderer {study.window_box} --frames 3 --timeout 30"
        if not (await fb.dispatch_command(client, fb.parse_command(wait), wait))["ok"]:
            for line in (f"launch {study.window_box} --timeout 120", f"wait-native-renderer {study.window_box} --frames 5 --timeout 120"):
                result = await fb.dispatch_command(client, fb.parse_command(line), line)
                if not result["ok"]:
                    raise RuntimeError(line + " failed: " + json.dumps(result)[:2000])
        # The study box draws in that window only while it runs; a window that had to be relaunched
        # took the running program with it.
        status = await fb.dispatch_command(client, fb.parse_command("status"), "status")
        if study.box not in (status.get("data") or {}).get("runningBoxes", []):
            line = f"launch {study.box} --timeout 120"
            result = await fb.dispatch_command(client, fb.parse_command(line), line)
            if not result["ok"]:
                raise RuntimeError(line + " failed: " + json.dumps(result)[:2000])
    return client, logger


async def _inspect(client, study: Study) -> dict:
    last = None
    for _ in range(30):
        last = await client.ai_tool_call(name=study.inspect_tool, arguments={}, timeout=120, focus_box=study.box)
        if last.get("ok") and last.get("result"):
            return last["result"]
        await asyncio.sleep(1)
    raise RuntimeError(f"{study.inspect_tool} failed: " + json.dumps(last)[:1000])


async def record_turn(study: Study, document: Path, label: str, prompt: str, port: int, timeout: float) -> dict:
    """Send one AI4 turn to [study]'s box in [document] and keep the evidence; see the module docs."""
    fb = _backdoor()
    output = document / "conversations"
    output.mkdir(exist_ok=True)
    target = output / label
    if target.with_suffix(".json").exists():
        raise ValueError("Choose a new label; saved conversations are never overwritten")
    client, logger = await _open(fb, document, port, study, label, "record")
    try:
        names = {x.get("name") for x in await client.ai_tools_list(timeout=30, focus_box=study.box)}
        if study.edit_tool not in names:
            raise RuntimeError(f"{study.edit_tool} is not offered for {study.box}; is it a {study.property} study box?")
        source = await client.get_property(uid=study.box, property_name=study.property, timeout=30)
        before = await _inspect(client, study)
        seq = client.event_seq
        start = time.monotonic()
        wall_start = time.time()
        await client.send_message("ai-assist", {"uid": study.box, "property": study.property,
            "prompt": source, "extraPrompt": prompt, "kind": "general", "startLine": 0, "images": []})
        print("AI4 turn started: " + label, flush=True)
        last_notice = ""
        status = "timed_out"
        notices: list[str] = []
        while time.monotonic() - start < timeout:
            await asyncio.sleep(2)
            events = client.events_since(seq)
            notices = [e.get("payload", {}).get("text", "") for e in events if e.get("name") == "notify"]
            if notices and notices[-1] != last_notice:
                last_notice = notices[-1]
                print(last_notice[:240], flush=True)
            if any("Finished AI conversation" in n or "AI general failed" in n or "AI4 setup" in n for n in notices):
                status = "completed" if any("Finished AI conversation" in n for n in notices) else "failed"
                break
        events = client.events_since(seq)
        panels = [e.get("payload", {}) for e in events if e.get("name") == "set-output" and e.get("payload", {}).get("channel") == "ai-chat"]
        panel = panels[-1] if panels else {}
        text = panel.get("message", panel.get("output", panel.get("value", "")))
        if not isinstance(text, str):
            text = json.dumps(text)
        text = externalize_images(text, output)
        after = await _inspect(client, study)
        record = {"label": label, "status": status, "transport": "Field ai-assist kind=general (AI4 UI path)",
                  "prompt": prompt, "elapsedSeconds": round(time.monotonic() - start, 3),
                  "before": before, "after": after, "panel": {**panel, "value": text}, "notices": notices}
        target.with_suffix(".json").write_text(json.dumps(record, indent=2))
        # AI4 writes one tool I/O log per turn; keep this turn's beside it, so the recorded tool calls
        # — every comparison the model cited as proof — can be replayed later.
        tool_logs = Path.home() / ".afield/logs/ai4_tool_io"
        fresh = [p for p in tool_logs.glob("*.jsonl") if p.stat().st_mtime >= wall_start] if tool_logs.is_dir() else []
        if len(fresh) == 1:
            (output / "tool-io").mkdir(exist_ok=True)
            (output / "tool-io" / (label + ".jsonl")).write_text(fresh[0].read_text())
        turn = text[text.rfind("<<you>>"):] if "<<you>>" in text else text
        target.with_suffix(".md").write_text("# " + label + "\n\n" + turn)
        print(json.dumps({"saved": str(target.with_suffix(".json")), "seconds": record["elapsedSeconds"],
                          "revisionAfter": after.get("revision"), "notices": notices[-2:]}), flush=True)
        if status != "completed":
            raise RuntimeError("AI4 turn " + status + "; partial evidence saved and the study left as it is")
        return record
    finally:
        await client.close()
        logger.close()


async def act(study: Study, document: Path, label: str, action: str, values: list[str], port: int, timeout: float) -> dict:
    """Do one person-side action in [study]'s card or editor; see the module docs."""
    fb = _backdoor()
    output = document / "conversations"
    output.mkdir(exist_ok=True)
    target = output / f"{label}.human.json"
    if target.exists():
        raise ValueError("Choose a new label; recorded actions are never overwritten")
    client, logger = await _open(fb, document, port, study, label, "act")

    async def page(script: str):
        result = await client.playwright_input(action="pageEval", script=script, script_mode="expression", timeout=120)
        if not result.get("ok"):
            raise RuntimeError(json.dumps(result)[:2000])
        return result.get("result")

    try:
        before = await _inspect(client, study)
        if action == "edit":
            old, new = values
            source = await client.get_property(uid=study.box, property_name=study.property, timeout=30)
            if source.count(old) != 1:
                raise ValueError(f"'{old}' must occur exactly once in the program")
            await client.set_string(study.box, study.property, source.replace(old, new))
            detail = {"replaced": old, "with": new, "surface": f"program box {study.property} property (the editor's sync path)"}
        else:
            if not study.card:
                raise ValueError(f"a {study.box} study has no card to act in")
            await client.open_playwright(timeout=120)
            card = json.dumps(study.card)
            await page(f"""(async () => {{ for (let i = 0; i < 300; i++) {{ if (document.querySelector({card})) return true;
                await new Promise(r => setTimeout(r, 100)); }} throw new Error('study card did not appear'); }})()""")
            if action == "adopt":
                chosen = json.dumps(values[0])
                script = f"""(() => {{ const c = document.querySelector({card});
                    const b = [...c.querySelectorAll('button[data-act=adopt]')].find(x => x.dataset.label === {chosen});
                    if (!b) throw new Error('no held variant ' + {chosen}); b.click(); return true; }})()"""
            elif action == "keep":
                knob, value = values
                script = f"""(() => {{ const c = document.querySelector({card});
                    const s = c.querySelector('input[type=range][data-knob="{int(knob)}"]');
                    if (!s) throw new Error('no knob {int(knob)}');
                    s.value = {float(value)}; s.dispatchEvent(new Event('input'));
                    c.querySelector('button[data-act=keep]').click(); return s.value; }})()"""
            elif action == "checkin":
                script = f"""(() => {{ const b = document.querySelector({card} + ' button[data-act=checkin]');
                    if (!b) throw new Error('the editor holds no unchecked edit'); b.click(); return true; }})()"""
            else:
                raise ValueError(action)
            detail = {"surface": "study card in Field's browser", "clicked": await page(script), "values": values}
        after = before
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            await asyncio.sleep(1)
            after = await _inspect(client, study)
            if after.get("revision") != before.get("revision") or action == "edit":
                break
        record = {"label": label, "action": action, **detail,
                  "revisionBefore": before.get("revision"), "revisionAfter": after.get("revision"),
                  "dirtyAfter": (after.get("facts") or {}).get("dirty"), "before": before, "after": after}
        target.write_text(json.dumps(record, indent=2))
        print(json.dumps({k: record[k] for k in ("label", "action", "revisionBefore", "revisionAfter", "dirtyAfter")}), flush=True)
        if action != "edit" and after.get("revision") == before.get("revision"):
            raise RuntimeError("The card action did not produce a revision; see the card's status line")
        return record
    finally:
        await client.close()
        logger.close()


# ---- Publishing -------------------------------------------------------------------------------------

CSS = """@import url("../../stylesheets/fonts.css");
:root{color-scheme:dark;font:16px/1.65 "Iosevka Web",Iosevka,monospace;background:#080808;color:#ddd}
*{box-sizing:border-box}body{margin:0;overflow-wrap:anywhere}main{max-width:1040px;margin:auto;padding:32px 28px 80px}
a{color:inherit;text-underline-offset:4px}a:hover{color:#fff}a:focus-visible,summary:focus-visible{outline:1px solid #ddd;outline-offset:5px}
.top{display:flex;justify-content:space-between;gap:20px;color:#aaa;font-size:.85rem}
header{padding:44px 0 28px;border-bottom:1px solid #333}.eyebrow,.role{color:#999;font-size:.8rem;letter-spacing:.07em;text-transform:uppercase}
h1{font:400 clamp(1.7rem,4vw,2.5rem)/1.2 "Iosevka Web",Iosevka,monospace;color:#f2f2f2;margin:12px 0 18px}
header p{color:#aaa;max-width:76ch}.chapters{display:flex;flex-wrap:wrap;gap:8px;margin-top:24px}
.chapters a{padding:4px 12px;border:1px solid #333;text-decoration:none}.chapters a[aria-current]{background:#ddd;color:#080808;border-color:#ddd}
.turn{margin:32px 0}.turn.you{padding:20px 24px;background:#191919;border-left:2px solid #aaa}.role{margin-bottom:14px}
.turn.person{padding:16px 24px;border:1px dashed #555;color:#bbb}.turn.person ul{margin:0;padding-left:20px}
p{margin:12px 0}strong{color:#eee}pre,code{font:inherit}code{font-size:.9em;background:#222;padding:1px 4px;overflow-wrap:anywhere}
pre{overflow:auto;padding:18px;background:#151515;border:1px solid #333}pre code{background:none;padding:0;white-space:pre-wrap}
img{display:block;max-width:100%;height:auto;margin:18px auto;border:1px solid #333}a:has(img){display:block}
figure{margin:28px 0}figcaption{color:#999;font-size:.85rem;text-align:center}
figure.window img{max-width:min(100%,560px)}
table{border-collapse:collapse;display:block;overflow:auto}td,th{padding:8px 12px;border:1px solid #333;text-align:left}
.tool-run{margin:24px 0;border:1px solid #333;background:#111}.tool-run>summary{padding:12px 18px;color:#aaa}
summary{cursor:pointer}.tool-run ol{padding:0 20px 8px 44px}.tool-run li{padding:8px 0;color:#aaa}.tool-run li summary{color:#ccc}
.tool-run .body{font-size:.85rem;overflow-wrap:anywhere}.tool-run .body p{white-space:pre-wrap}
footer{margin-top:48px;padding-top:24px;border-top:1px solid #333;display:flex;justify-content:space-between;gap:24px;font-size:.9rem}
@media(max-width:600px){main{padding:20px 16px 48px}.turn.you,.turn.person{padding:14px}.top{flex-wrap:wrap}header{padding-top:28px}}
"""


def prose(text: str) -> str:
    """Transcript Markdown as HTML: the text stays text (escaped before Markdown), code spans are not
    escaped twice, and each image links to its full-size file."""
    import markdown

    rendered = markdown.markdown(escape(text, quote=False), extensions=["fenced_code", "tables", "sane_lists"])
    rendered = re.sub(r"<code>(.*?)</code>",
                      lambda m: "<code>" + re.sub(r"&amp;(lt|gt|amp|quot);", r"&\1;", m[1]) + "</code>", rendered, flags=re.DOTALL)
    return re.sub(r'(<img [^>]*src="(images/[^"]+)"[^>]*>)', r'<a href="\2" title="Open full size">\1</a>', rendered)


def section(role: str, label: str, html: str) -> str:
    return f'<section class="turn {role}" aria-label="{escape(label)}"><div class="role">{escape(label)}</div>{html}</section>'


def render_turn(body: str, default_label: str = "AI4") -> str:
    """One recorded turn (``<<you>>``, ``<<progress>>``, ``<<ai>>`` blocks) as HTML sections: the
    request, the tool activity folded away, each tool image as a figure, and the replies."""
    parts = re.split(r"^<<(you|ai|progress)>>\s*\n", body, flags=re.MULTILINE)
    sections: list[str] = [prose(parts[0])] if parts[0].strip() else []
    traces: list[str] = []

    def flush():
        if traces:
            sections.append(f'<details class="tool-run"><summary>Tool activity · {len(traces)} steps</summary><ol>{"".join(traces)}</ol></details>')
            traces.clear()

    for role, text in zip(parts[1::2], parts[2::2]):
        text = text.strip()
        label = default_label
        model = re.match(r"<<model:(\{.*?\})>>\n?", text)
        if model:
            identity = json.loads(model[1])
            label = f"AI4 · {identity.get('responseModel') or identity.get('requestedModel')} / {identity.get('provider')}"
            text = text[model.end():].strip()
        if role == "progress":
            title, _, detail = text.partition("\n")
            try:
                detail_html = "<pre><code>" + escape(json.dumps(json.loads(detail), indent=2)) + "</code></pre>"
            except (ValueError, TypeError):
                detail_html = prose(detail)
            traces.append(f'<li><details><summary>{escape(title)}</summary><div class="body">{detail_html}</div></details></li>')
            continue
        flush()
        image = re.fullmatch(r"_(.+?)_\s*\n\n!\[tool image\]\((images/[^)]+)\)", text, flags=re.DOTALL)
        if image:
            caption, path = image[1], image[2]
            window = caption.startswith("afield.four render window")
            shown = "The live window, captured for the model" if window else caption
            sections.append(f'<figure class="{"window" if window else "evidence"}"><a href="{path}" title="Open full size">'
                            f'<img src="{path}" alt="{escape(shown)}" loading="lazy"></a><figcaption>{escape(shown)}</figcaption></figure>')
        else:
            sections.append(section(role, "You" if role == "you" else label, prose(text)))
    flush()
    return "".join(sections)


def transcript_page(*, title: str, eyebrow: str, titles: dict[str, str], current: str, sections: str,
                    back_href: str, back_label: str, css_href: str = "conversation.css",
                    intro: str = "The recorded exchange: the request, the tool activity, every image the tools returned to the model, and its reply. Expand tool activity to read the steps; open an image to see it full size.") -> bytes:
    """A standalone page for one turn of a series, with chapter links, a raw-transcript link and prev/next."""
    names = list(titles)
    position = names.index(current)
    chapters = "".join(
        f'<a href="{key}.html" title="{escape(t)}" aria-label="{i + 1}: {escape(t)}"' + (' aria-current="page"' if key == current else "") + f">{i + 1:02}</a>"
        for i, (key, t) in enumerate(titles.items()))
    previous = f'<a rel="prev" href="{names[position - 1]}.html">← {escape(titles[names[position - 1]])}</a>' if position else "<span></span>"
    following = (f'<a rel="next" href="{names[position + 1]}.html">{escape(titles[names[position + 1]])} →</a>'
                 if position + 1 < len(names) else f'<a href="{back_href}">Back to the walkthrough →</a>')
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{escape(title)} · Field AI4</title><link rel="stylesheet" href="{css_href}"></head>
<body><main><nav class="top" aria-label="Document"><a href="{back_href}">← {escape(back_label)}</a><a href="{current}.txt">Raw transcript</a></nav>
<header><div class="eyebrow">{escape(eyebrow)}</div><h1>{escape(titles[current])}</h1>
<p>{escape(intro)}</p>
<nav class="chapters" aria-label="Recorded turns">{chapters}</nav></header>
{sections}<footer>{previous}{following}</footer></main></body></html>
""".encode()


def deterministic_zip(prefix: str, files: dict[str, bytes], date=(2026, 9, 26, 0, 0, 0)) -> bytes:
    """A zip whose bytes depend only on [files]: fixed dates and modes, sorted entries under [prefix]/."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for relative in sorted(files):
            entry = zipfile.ZipInfo(f"{prefix}/{relative}", date_time=date)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            z.writestr(entry, files[relative])
    return buffer.getvalue()


def sync_blocks(page: Path, marker: str, blocks: dict[str, str], check: bool) -> None:
    """Replace the text between ``<!-- MARKER:NAME -->`` and ``<!-- /MARKER:NAME -->`` with each block."""
    original = text = page.read_text()
    for name, source in blocks.items():
        opening, closing = f"<!-- {marker}:{name} -->", f"<!-- /{marker}:{name} -->"
        text, count = re.subn(re.escape(opening) + r".*?" + re.escape(closing),
                              lambda _: opening + "\n" + source + "\n" + closing, text, flags=re.DOTALL)
        assert count == 1, f"Expected one {name} block on {page.name}"
    if text != original:
        if check:
            raise SystemExit(f"{page.relative_to(ROOT)} listings differ from their saved sources; rerun its packager")
        page.write_text(text)


def settle_assets(output: Path, expected: dict[str, bytes], check: bool, label: str,
                  captured: tuple[str, ...] = (), capture_script: str = "", gitignore: str | None = None) -> None:
    """Write [expected] under [output] and delete anything else, or with [check] refuse any
    difference. [captured] files are screenshots another script makes: required, never compared."""
    written = {p.relative_to(output).as_posix() for p in output.rglob("*") if p.is_file() and p.name != ".gitignore"} if output.is_dir() else set()
    if check:
        stale = sorted(n for n, data in expected.items() if not (output / n).is_file() or (output / n).read_bytes() != data)
        stale += [f"{n} (run {capture_script})" for n in captured if not (output / n).is_file()]
        extra = sorted(written - set(expected) - set(captured))
        if stale or extra:
            raise SystemExit(f"Stale {label} assets: " + ", ".join(stale + [f"{n} (not produced)" for n in extra]))
        print(f"{label}: {len(expected)} assets match their recorded sources.")
        return
    for name, data in expected.items():
        (output / name).parent.mkdir(parents=True, exist_ok=True)
        (output / name).write_bytes(data)
    for name in written - set(expected) - set(captured):
        (output / name).unlink()
    if gitignore is not None:
        (output / ".gitignore").write_text(gitignore)
    print(f"Packaged {len(expected)} {label} assets.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("record", "act"):
        sub = commands.add_parser(name)
        sub.add_argument("--study", choices=sorted(STUDIES), required=True)
        sub.add_argument("--document", required=True, help="Path to the document folder")
        sub.add_argument("--label", required=True)
        sub.add_argument("--port", type=int, default=9788)
        sub.add_argument("--timeout", type=float, default=900 if name == "record" else 120)
        if name == "record":
            sub.add_argument("--prompt-file", required=True)
        else:
            sub.add_argument("action", choices=["adopt", "keep", "edit", "checkin"])
            sub.add_argument("values", nargs="*")
    args = parser.parse_args()
    study = STUDIES[args.study]
    document = Path(args.document).resolve()
    if args.command == "record":
        asyncio.run(record_turn(study, document, args.label, Path(args.prompt_file).read_text(), args.port, args.timeout))
    else:
        asyncio.run(act(study, document, args.label, args.action, args.values, args.port, args.timeout))


if __name__ == "__main__":
    main()
