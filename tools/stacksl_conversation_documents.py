#!/usr/bin/env python3
"""Write the metadata.edn of each StackSL conversation document from its loose box files.

A StackSL conversation document is two boxes: a window (``stacksl_window``) whose main language is
StackSL, and the program (``stacksl_source``), its child, whose ``stackSL`` property is the shader.
Both autorun: opening the document opens the window and draws the program in it, and the program's
AI4 chat has the study tools (they are library code, ``afield.stacksl.study``, not a box). Box text
lives in sidecar files beside ``metadata.edn`` — ``<uid>_<property>_.<ext>`` — so an edit is a normal
diff; this script writes the ``metadata.edn`` that makes the folder an openable Field document, in
the bytes Field's codec writes (``checkAgentDemos`` holds that).

The window box and ``AGENTS.md`` are the same in every document: the recorded examples copy the
starter's. A recorded example also carries its AI4 chat, referenced from ``metadata.edn`` as a
``#afield/file`` rather than inlined. The study's words guide, a library resource, is generated here
from the public cheatsheet. ``--check`` fails if anything differs from what this script would write.

Use: uv run python tools/stacksl_conversation_documents.py [--check]
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEMOS = ROOT / "dev_docs/agents/demos"
STARTER = "stacksl_conversation"
EXAMPLES = ("stacksl_conversation_ink", "stacksl_conversation_tiles", "stacksl_conversation_aurora")
CHEATSHEET = ROOT / "public/docs/stacksl_cheatsheet.md"
WORDS = ROOT / "src/jvmMain/resources/afield/stacksl/study/stacksl_words.md"
CHAT = "stacksl_source_ai4_chat_history_.md"
# Sections of the cheatsheet a study cannot use: Kotlin host helpers, and texture sampling, which a
# single-pass study refuses.
SKIPPED_SECTIONS = {"Kotlin helpers", "Feedback sampling, store, and host values"}

# Frames are [x y w h]; kept within the agent-demo layout ceiling (620 x 170). The window owns the
# renderer and is the StackSL root, so the program beneath it runs as StackSL and draws in it.
BOXES = (
    {
        "id": "stacksl_window",
        "name": "window",
        "frame": (70.0, 40.0, 240.0, 60.0),
        "files": {"code": "stacksl_window_code_.kt"},
        "extra": {"mainLanguage": "stacksl"},
        "parents": ("root",),
        "children": ("stacksl_source",),
    },
    {
        "id": "stacksl_source",
        "name": "the shader — talk here (AI4)",
        "frame": (70.0, 150.0, 420.0, 150.0),
        "files": {"stackSL": "stacksl_source_stackSL_.stacksl"},
        "extra": {"default_property": "stackSL"},
        "parents": ("stacksl_window",),
        "children": (),
    },
)
SHARED_FILES = ("stacksl_window_code_.kt",)
# Guides every document carries unchanged; they are not box properties, so they are not in metadata.edn.
SHARED_GUIDES = ("AGENTS.md",)


def words_guide() -> str:
    """The StackSL vocabulary, lifted from the public cheatsheet: each section's tables and examples."""
    import re

    out = [
        "# StackSL words",
        "",
        "Generated from `public/docs/stacksl_cheatsheet.md` by `tools/stacksl_conversation_documents.py`;",
        "do not edit here. Stack effects read `a b -- c`, top of stack on the right. Search this guide",
        "for a word rather than reading it all. Texture sampling (`sample`, `prev`, feedback) is left out:",
        "a study renders single-pass programs and refuses those words.",
    ]
    sections = re.split(r"^## ", CHEATSHEET.read_text(), flags=re.MULTILINE)[1:]
    for section in sections:
        title, _, body = section.partition("\n")
        title = title.strip()
        if title in SKIPPED_SECTIONS:
            continue
        kept = []
        in_fence = False
        fence_lang = ""
        paragraph = "none"
        for line in body.splitlines():
            if line.startswith("```"):
                if not in_fence:
                    in_fence = True
                    fence_lang = "stacksl" if "stacksl" in line else ""
                    fence = []
                else:
                    in_fence = False
                    samples = re.search(r"\b(sample|prev|gather|state|texelSize)", "\n".join(fence))
                    if fence_lang and not samples:
                        kept += ["```stacksl", *fence, "```"]
                continue
            if in_fence:
                fence.append(line)
                continue
            if line.startswith("|"):
                kept.append(re.sub(r"<br>.*?(?=\s*\|)", "", line))
                continue
            text = line.strip()
            prose = text and not text.startswith("<") and not text.startswith("*Every") and not text.startswith("Worked:")
            if prose and paragraph != "done":
                # The section's first paragraph, which the cheatsheet hard-wraps across lines.
                if paragraph == "open":
                    kept[-1] += " " + text
                else:
                    kept.append(text)
                    paragraph = "open"
            elif not text:
                if paragraph == "open":
                    paragraph = "done"
                if kept and kept[-1] != "":
                    kept.append("")
        while kept and kept[-1] == "":
            kept.pop()
        out += ["", "## " + title, ""] + kept
    return "\n".join(out).replace("\n\n\n", "\n\n") + "\n"


def edn_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def persistent_properties() -> str:
    # The same list Field writes; taken from the function conversation starter so the two agree.
    source = (DEMOS / "function_conversation/metadata.edn").read_text()
    return source[source.index('"persistentProperties"') :].rstrip()


def metadata(folder: Path) -> str:
    boxes = []
    for box in BOXES:
        def refs(ids):
            return "#{" + " ".join(f'#afield/box {edn_string(i)}' for i in ids) + "}"
        properties = [
            '"children" ' + refs(box["children"]),
            '"parents" ' + refs(box["parents"]),
            f'"name" {edn_string(box["name"])}',
            '"frame" #afield/rect [' + " ".join(repr(v) for v in box["frame"]) + "]",
        ]
        for prop, filename in box["files"].items():
            text = (folder / filename).read_text()
            properties.append(
                f'{edn_string(prop)} #afield/file-inline {{"path" {edn_string(filename)} "inline" {edn_string(text)}}}'
            )
        for key, value in box["extra"].items():
            properties.append(f"{edn_string(key)} {edn_string(value)}")
        properties += ['"preamble" ""', '"attic" ""', '"autorun" true', '"nested" #{}']
        if box["id"] == "stacksl_source" and (folder / CHAT).is_file():
            # The chat is megabytes of inline images; Field reads a #afield/file reference from the
            # sidecar on load, so metadata.edn need not carry a second copy of it.
            properties += ['"ai4_chat_running" false', f'"ai4_chat_history" #afield/file {edn_string(CHAT)}']
        boxes.append(f'{{"id" {edn_string(box["id"])} "properties" {{{" ".join(properties)}}}}}')
    children = " ".join(f'#afield/box {edn_string(b["id"])}' for b in BOXES if "root" in b["parents"])
    boxes.append(
        '{"id" "root" "properties" {"children" #{' + children + '} "parents" #{#afield/box "metaroot"} '
        '"name" "root" "nested" #{} "fileManagerAccessHistory" []}}'
    )
    # No trailing newline: the bytes must be what Field's EDN codec writes (checkAgentDemos).
    return '{"version" 2 "boxes" [' + " ".join(boxes) + "] " + persistent_properties()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    problems = []

    def settle(path: Path, text: str, what: str) -> None:
        present = path.read_text() if path.is_file() else None
        if present == text:
            return
        if args.check:
            problems.append(f"{what} differs from what this script writes; rerun without --check")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            print(f"wrote {path.relative_to(ROOT)}")

    settle(WORDS, words_guide(), str(WORDS.relative_to(ROOT)))
    shared = {f: (DEMOS / STARTER / f).read_text() for f in SHARED_FILES + SHARED_GUIDES}
    for name in (STARTER, *EXAMPLES):
        folder = DEMOS / name
        if not folder.is_dir():
            problems.append(f"{name}: missing")
            continue
        for filename, text in shared.items():
            settle(folder / filename, text, f"{name}/{filename}")
        settle(folder / "metadata.edn", metadata(folder), f"{name}/metadata.edn")
    if problems:
        raise SystemExit("\n".join(problems))
    if args.check:
        print("StackSL conversation documents: metadata, shared boxes and the words guide agree with their sources.")


if __name__ == "__main__":
    main()
