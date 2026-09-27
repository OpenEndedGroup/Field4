#!/usr/bin/env python3
"""Package the blank starter and recorded example for the function conversation walkthrough.

Only document inputs and recorded evidence are included, never live backups or caches. The
rendering, zips and checks are ``tools/conversation_demo.py``'s. Run with --check to verify the
committed downloads and page listings without rewriting them.
Use: uv run --project . --group docs python tools/package_function_conversation.py [--check]
"""

from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path
import re
import textwrap

import conversation_demo as demo

ROOT = demo.ROOT
DEMOS = ROOT / "dev_docs/agents/demos"
OUTPUT = ROOT / "public/docs/assets/function_conversation"
PAGE = ROOT / "public/docs/function_conversation.md"
EXAMPLE = DEMOS / "function_conversation_example"
STUDY = EXAMPLE / "studies/function_source"
STARTER_FILES = ("README.md", "AGENTS.md", "metadata.edn", "function_source_code_.kt")
CHAT_FILE = "function_source_ai4_chat_history_.md"
TOOLS_SOURCE = ROOT / "src/jvmMain/kotlin/afield/stdlib/FunctionStudyTools.kt"
# Screenshots of Field itself, made by tools/capture_function_conversation.py.
CAPTURED = ("starter.png", "saved-chat.png")
RECORDED_TURNS = ("01-create", "02-move", "03-stretch", "04-lag", "05-delete", "06-warp", "07-restore-background")
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


def revision(name: str) -> str:
    return (STUDY / (name + ".kt")).read_text()


def source_blocks() -> dict[str, str]:
    """Listings and edit views come from the saved Kotlin, not parallel examples."""
    def excerpt(source: str, start: str, end: str) -> str:
        return textwrap.dedent(source[source.index(start) : source.index(end)]).strip()

    def listing(source: str) -> str:
        return "```diffstatic {data-lang=kotlin}\n" + source.rstrip() + "\n```"

    created = revision("r3-66fd67f6fe7c")
    stretched = revision("r5-2aa6023ddfca")
    lagged = revision("r6-deb1b1738cb0")
    final = (EXAMPLE / "function_source_code_.kt").read_text()
    assert final == revision("r9-82456e1e48ac")
    timing = lambda source: excerpt(source, "    val firstStartSeconds", "    val lowPulseHz")
    return {
        "starter": listing((DEMOS / "function_conversation/function_source_code_.kt").read_text()),
        "recorded-start": listing(revision("r2-8f83d79e35c9")),
        "background": listing(excerpt(created, "    fun background", "    // age runs")),
        "channels": listing(excerpt(created, "    val channels = listOf", "    StudyComposition(")),
        "timing": "```diff {data-lang=kotlin}\n:::before\n" + timing(created) + "\n:::after\n" + timing(stretched) + "\n```",
        "lag": listing(excerpt(lagged, "                    val lag = if", "\n                }\n                seconds <= event.plateauEnd")),
        "final": '???+ example "Complete final source · r9-82456e1e48ac"\n\n' + textwrap.indent(listing(final), "    "),
    }


def recorded_chat() -> bytes:
    """Restore original UI turns, not a newly written summary or simulated conversation."""
    folder = EXAMPLE / "conversations"
    turns = []
    for label in RECORDED_TURNS:
        record = json.loads((folder / (label + ".json")).read_text())
        text = (folder / (label + ".md")).read_text().split("\n\n", 1)[1]
        assert text.startswith("<<you>>\n")
        assert text.split("<<you>>", 1)[1].split("<<", 1)[0].strip() == record["prompt"].strip()
        panel = record["panel"]["value"]
        assert text.strip() == panel[panel.rfind("<<you>>"):].strip()

        def image(match: re.Match) -> str:
            path = (folder / match[1]).resolve()
            assert path.is_relative_to((folder / "images").resolve())
            return "](data:image/png;base64," + base64.b64encode(path.read_bytes()).decode() + ")"

        # Data URLs make the imported chat portable to another workspace or a copied box.
        turns.append(re.sub(r"\]\((images/[^)]+\.png)\)", image, text).strip())
    return ("\n\n".join(turns) + "\n").encode()


def reader(name: str, source: str) -> bytes:
    body = source.split("\n\n", 1)[1]
    label = "AI4"
    # The compact replay was recorded as labelled Markdown rather than UI markers; keep its
    # introductory measurements and its recorded provider label.
    if name == "08-loop-improvements":
        body = body.replace("**User**\n", "<<you>>\n").replace("*Progress*\n", "<<progress>>\n").replace("**AI4 — gpt-6-astra / OpenAI**\n", "<<ai>>\n")
        label = "AI4 · gpt-6-astra / OpenAI"
    position = list(TITLES).index(name)
    return demo.transcript_page(
        title=TITLES[name], eyebrow=f"Field · AI4 / saved conversation {position + 1:02}", titles=TITLES, current=name,
        sections=demo.render_turn(body, label), back_href="../../function_conversation/", back_label="Talking a function into existence",
        intro="The original exchange, including tool results and plot images. Expand tool activity to inspect the steps; open a plot to see it full size.")


def archive(name: str) -> bytes:
    folder = DEMOS / name
    files = [folder / p for p in STARTER_FILES]
    if name != "function_conversation":
        files.append(folder / CHAT_FILE)
        for directory in ("studies", "conversations", "prompts", "checks"):
            files.extend(p for p in (folder / directory).rglob("*") if p.is_file())
        files.extend((folder / "guides/conversations").glob("*.md"))
    return demo.deterministic_zip(name, {p.relative_to(folder).as_posix(): p.read_bytes() for p in sorted(set(files))}, date=(2026, 9, 22, 0, 0, 0))


def assets() -> dict[str, bytes]:
    result = {name + ".zip": archive(name) for name in ("function_conversation", "function_conversation_example")}
    for name, source in {
        "created.png": "studies/function_source/r3-66fd67f6fe7c-0.0-3600.0.png",
        "lag.png": "studies/function_source/r6-deb1b1738cb0-1505.0-1530.0-vs-r5-2aa6023ddfca.png",
        "final.png": "studies/function_source/r9-82456e1e48ac-0.0-3600.0.png",
        "final.kt": "function_source_code_.kt",
        "independent-verification.json": "conversations/independent-verification.json",
    }.items():
        result[name] = (EXAMPLE / source).read_bytes()
    result["FunctionStudyTools.kt"] = TOOLS_SOURCE.read_bytes()
    result["conversation.css"] = demo.CSS.encode()
    for name in TITLES:
        transcript = (EXAMPLE / "conversations" / (name + ".md")).read_bytes()
        result[name + ".txt"] = transcript
        result[name + ".html"] = reader(name, transcript.decode())
        for image in re.findall(r"\]\((images/[^)]+\.png)\)", transcript.decode()):
            path = (EXAMPLE / "conversations" / image).resolve()
            assert path.is_relative_to((EXAMPLE / "conversations/images").resolve())
            result[image] = path.read_bytes()
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    demo.sync_blocks(PAGE, "function-study-source", source_blocks(), args.check)
    chat = EXAMPLE / CHAT_FILE
    if args.check:
        if not chat.is_file() or chat.read_bytes() != recorded_chat():
            raise SystemExit("Worked example chat differs from its recorded AI4 turns")
    else:
        chat.write_bytes(recorded_chat())
    demo.settle_assets(OUTPUT, assets(), args.check, "Function conversation", CAPTURED, "tools/capture_function_conversation.py",
                       gitignore="!function_conversation.zip\n!function_conversation_example.zip\n")


if __name__ == "__main__":
    main()
