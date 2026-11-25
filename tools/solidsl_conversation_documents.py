#!/usr/bin/env python3
"""Write the metadata.edn of each SolidSL conversation document from its loose box files.

A SolidSL conversation document is two boxes: a root (``solidsl_window``) marked with the main
language ``solidsl`` and holding no Kotlin, which opens the window
(``afield.runtime.languages.LanguageRoots``), and the study (``solid_source``), its child, whose
``code`` is Kotlin returning the solid and whose ``aiStudy`` mark is ``solidsl``, so its AI4 chat has
the study tools (library code, ``afield.lang.solidsl.study``). Opening the document opens the window
and checks the source in. Box text lives in sidecar files beside ``metadata.edn``; this script writes
the ``metadata.edn`` that makes the folder an openable Field document, in the bytes Field's codec
writes. A recorded example also carries its AI4 chat, referenced as a ``#afield/file``.
``--check`` fails if anything differs from what this script would write.

Use: uv run python tools/solidsl_conversation_documents.py [--check]
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEMOS = ROOT / "dev_docs/agents/demos"
STARTER = "solidsl_conversation"
EXAMPLES = ("solidsl_conversation_example",)
CHAT = "solid_source_ai4_chat_history_.md"
CODE = "solid_source_code_.kt"
SHARED_GUIDES = ("AGENTS.md",)

BOXES = (
    {
        "id": "solidsl_window",
        "name": "SolidSL window",
        "frame": (70.0, 40.0, 240.0, 60.0),
        "files": {},
        "extra": {"mainLanguage": "solidsl"},
        "parents": ("root",),
        "children": ("solid_source",),
    },
    {
        "id": "solid_source",
        "name": "the solid — talk here (AI4)",
        "frame": (70.0, 150.0, 480.0, 160.0),
        "files": {"code": CODE},
        "extra": {"aiStudy": "solidsl", "default_property": "code"},
        "parents": ("solidsl_window",),
        "children": (),
    },
)


def edn_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def persistent_properties() -> str:
    # The same list Field writes, taken from the RectSL conversation starter so the two agree.
    source = (DEMOS / "rectsl_conversation/metadata.edn").read_text()
    listed = source[source.index('"persistentProperties"') :].rstrip()
    return listed if '"aiStudy"' in listed else listed.replace('"mainLanguage"', '"mainLanguage" "aiStudy"', 1)


def metadata(folder: Path) -> str:
    boxes = []
    for box in BOXES:
        def refs(ids):
            return "#{" + " ".join(f"#afield/box {edn_string(i)}" for i in ids) + "}"
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
        properties += ['"preamble" ""', '"attic" ""', '"autorun" false', '"nested" #{}']
        if box["id"] == "solid_source" and (folder / CHAT).is_file():
            properties += ['"ai4_chat_running" false', f'"ai4_chat_history" #afield/file {edn_string(CHAT)}']
        boxes.append(f'{{"id" {edn_string(box["id"])} "properties" {{{" ".join(properties)}}}}}')
    children = " ".join(f'#afield/box {edn_string(b["id"])}' for b in BOXES if "root" in b["parents"])
    boxes.append(
        '{"id" "root" "properties" {"children" #{' + children + '} "parents" #{#afield/box "metaroot"} '
        '"name" "root" "nested" #{} "fileManagerAccessHistory" []}}'
    )
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
            path.write_text(text)
            print(f"wrote {path.relative_to(ROOT)}")

    shared = {f: (DEMOS / STARTER / f).read_text() for f in SHARED_GUIDES if (DEMOS / STARTER / f).is_file()}
    for name in (STARTER, *EXAMPLES):
        folder = DEMOS / name
        if not folder.is_dir():
            if name != STARTER and not args.check:
                continue  # an example is created by recording it
            problems.append(f"{name}: missing")
            continue
        for filename, text in shared.items():
            settle(folder / filename, text, f"{name}/{filename}")
        settle(folder / "metadata.edn", metadata(folder), f"{name}/metadata.edn")
    if problems:
        raise SystemExit("\n".join(problems))


if __name__ == "__main__":
    main()
