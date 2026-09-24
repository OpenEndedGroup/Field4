"""Static readers for the original FunctionStudy transcripts (no model calls)."""

from html import escape
import json
import re

import markdown

TITLES = {
    "01-create": "Create the composition",
    "02-move": "Move the third event",
    "03-stretch": "Give the event a longer rise",
    "04-lag": "Let one channel fall behind",
    "05-delete": "Delete an event",
    "06-warp": "Warp a background passage",
    "07-restore-background": "Restore the background",
    "08-loop-improvements": "A shorter edit-and-export loop",
}

CSS = """@import url("https://pvinis.github.io/iosevka-webfont/7.0.2/iosevka.css");
:root{color-scheme:dark;font:16px/1.65 "Iosevka Web",Iosevka,monospace;background:#080808;color:#ddd}
*{box-sizing:border-box}body{margin:0;overflow-wrap:anywhere}main{max-width:1040px;margin:auto;padding:32px 28px 80px}
a{color:inherit;text-underline-offset:4px}a:hover{color:#fff}a:focus-visible,summary:focus-visible{outline:1px solid #ddd;outline-offset:5px}
.top{display:flex;justify-content:space-between;gap:20px;color:#aaa;font-size:.85rem}
header{padding:44px 0 28px;border-bottom:1px solid #333}.eyebrow,.role{color:#999;font-size:.8rem;letter-spacing:.07em;text-transform:uppercase}
h1{font:400 clamp(1.7rem,4vw,2.5rem)/1.2 "Iosevka Web",Iosevka,monospace;color:#f2f2f2;margin:12px 0 18px}
header p{color:#aaa;max-width:76ch}.chapters{display:flex;flex-wrap:wrap;gap:8px;margin-top:24px}
.chapters a{padding:4px 12px;border:1px solid #333;text-decoration:none}.chapters a[aria-current]{background:#ddd;color:#080808;border-color:#ddd}
.turn{margin:32px 0}.turn.you{padding:20px 24px;background:#191919;border-left:2px solid #aaa}.role{margin-bottom:14px}
p{margin:12px 0}strong{color:#eee}pre,code{font:inherit}code{font-size:.9em;background:#222;padding:1px 4px;overflow-wrap:anywhere}
pre{overflow:auto;padding:18px;background:#151515;border:1px solid #333}pre code{background:none;padding:0;white-space:pre-wrap}
img{display:block;max-width:100%;height:auto;margin:18px auto;border:1px solid #333}a:has(img){display:block}
table{border-collapse:collapse;display:block;overflow:auto}td,th{padding:8px 12px;border:1px solid #333;text-align:left}
.tool-run{margin:24px 0;border:1px solid #333;background:#111}.tool-run>summary{padding:12px 18px;color:#aaa}
summary{cursor:pointer}.tool-run ol{padding:0 20px 8px 44px}.tool-run li{padding:8px 0;color:#aaa}.tool-run li summary{color:#ccc}
.tool-run .body{font-size:.85rem;overflow-wrap:anywhere}.tool-run .body p{white-space:pre-wrap}
footer{margin-top:48px;padding-top:24px;border-top:1px solid #333;display:flex;justify-content:space-between;gap:24px;font-size:.9rem}
@media(max-width:600px){main{padding:20px 16px 48px}.turn.you{padding:14px}.top{flex-wrap:wrap}header{padding-top:28px}}
"""


def prose(text: str) -> str:
    # Transcript HTML stays text; Markdown supplies the presentation.
    rendered = markdown.markdown(
        escape(text, quote=False), extensions=["fenced_code", "tables", "sane_lists"]
    )
    return re.sub(
        r'(<img [^>]*src="(images/[^"]+)"[^>]*>)',
        r'<a href="\2" title="Open full-size plot">\1</a>',
        rendered,
    )


def reader(name: str, source: str) -> bytes:
    body = source.split("\n\n", 1)[1]
    # The compact replay was recorded as labelled Markdown rather than UI markers.
    # Preserve its introductory measurements and recorded provider label.
    if name == "08-loop-improvements":
        body = body.replace("**User**\n", "<<you>>\n").replace(
            "*Progress*\n", "<<progress>>\n"
        )
        body = body.replace("**AI4 — gpt-6-astra / OpenAI**\n", "<<ai>>\n")
    parts = re.split(r"^<<(you|ai|progress)>>\s*\n", body, flags=re.MULTILINE)
    sections = [prose(parts[0])] if parts[0].strip() else []
    traces = []

    def flush_traces():
        if traces:
            sections.append(
                f'<details class="tool-run"><summary>Tool activity · {len(traces)} steps</summary><ol>{"".join(traces)}</ol></details>'
            )
            traces.clear()

    for role, text in zip(parts[1::2], parts[2::2]):
        text = text.strip()
        if role == "progress":
            title, _, detail = text.partition("\n")
            try:
                detail_html = (
                    "<pre><code>"
                    + escape(json.dumps(json.loads(detail), indent=2))
                    + "</code></pre>"
                )
            except (ValueError, TypeError):
                detail_html = prose(detail)
            traces.append(
                f'<li><details><summary>{escape(title)}</summary><div class="body">{detail_html}</div></details></li>'
            )
        else:
            flush_traces()
            label = (
                "You"
                if role == "you"
                else (
                    "AI4 · gpt-6-astra / OpenAI"
                    if name == "08-loop-improvements"
                    else "AI4"
                )
            )
            sections.append(
                f'<section class="turn {role}" aria-label="{label}"><div class="role">{label}</div>{prose(text)}</section>'
            )
    flush_traces()
    names = list(TITLES)
    position = names.index(name)
    chapters = "".join(
        f'<a href="{key}.html" title="{escape(title)}" aria-label="{i+1}: {escape(title)}"'
        + (' aria-current="page"' if key == name else "")
        + f">{i+1:02}</a>"
        for i, (key, title) in enumerate(TITLES.items())
    )
    previous = (
        f'<a rel="prev" href="{names[position-1]}.html">← {TITLES[names[position-1]]}</a>'
        if position
        else "<span></span>"
    )
    following = (
        f'<a rel="next" href="{names[position+1]}.html">{TITLES[names[position+1]]} →</a>'
        if position + 1 < len(names)
        else '<a href="../../function_conversation/">Back to the walkthrough →</a>'
    )
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{escape(TITLES[name])} · Field AI4</title><link rel="stylesheet" href="conversation.css"></head>
<body><main><nav class="top" aria-label="Document"><a href="../../function_conversation/">← Talking a function into existence</a><a href="{name}.txt">Raw transcript</a></nav>
<header><div class="eyebrow">Field · AI4 / saved conversation {position+1:02}</div><h1>{escape(TITLES[name])}</h1>
<p>The original exchange, including tool results and plot images. Expand tool activity to inspect the steps; open a plot to see it full size.</p>
<nav class="chapters" aria-label="Saved conversations">{chapters}</nav></header>
{"".join(sections)}<footer>{previous}{following}</footer></main></body></html>""".encode()
