#!/usr/bin/env python3
"""Generate the lesson index for the `canonical_graphics` example corpus.

`public/docs/assets/canonical_graphics/` is a snapshot of a real Field document — the
`afield.four` graphics curriculum, ~30 worked lessons from a first triangle up to a
GPU DLA system. It is the best corpus of known-good `afield.four` code in the tree, and
for a long time nothing could find it: every file on disk is named by box uid, and the
lesson names live only inside a 300 KB `metadata.edn`, so grepping for `RenderToTexture`
or "texturing" landed on one EDN blob instead of on code.

This writes `LESSONS.md` next to the corpus: box uid to lesson name, the parent chain each
lesson needs in order to run, and a reverse index from Field API symbol to the lessons that
use it. All three are *enumerations* derived from the corpus, so they are generated rather
than hand-written — the prose curriculum lives in `public/docs/afield_four_graphics.md`.

Regenerate (the fix for a `test_canonical_graphics_index` failure):

    uv run --project . --group backdoor --frozen python tools/gen_canonical_graphics_index.py

`--check` compares against the committed file and exits non-zero on any difference, which is
what `tools/test_canonical_graphics_index.py` runs under `./gradlew checkPythonTools`.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CORPUS = REPO_ROOT / "public" / "docs" / "assets" / "canonical_graphics"
INDEX_FILE = CORPUS / "LESSONS.md"

# Shader-stage and note properties Field stores as loose sidecars beside a box.
SIDECAR_PROPERTIES = [
    ("code", "kt"),
    ("vertex", "glsl"),
    ("fragment", "glsl"),
    ("compute", "glsl"),
    ("vertex_fragment", "slang"),
    ("vertex_s", "slang"),
    ("fragment_s", "slang"),
    ("compute_s", "slang"),
    ("notes", "md"),
]

# A box earns a row when its name starts with a curriculum number ("0.", "3b.", "7b.").
# The document also holds unrelated scratch boxes; numbering is what the author used to
# mark the curriculum, so it is the honest filter.
LESSON_NAME = re.compile(r"^(\d+)([a-z]*)\.\s*(.+)$")

# A one-byte sidecar is an emptied property, not content.
MINIMUM_SIDECAR_BYTES = 8

# Kotlin/JVM names that say nothing about Field's graphics API.
SYMBOL_STOPLIST = {
    "Any", "Array", "ArrayList", "Boolean", "Byte", "Char", "Collection", "Double",
    "Exception", "File", "Float", "FloatBuffer", "HashMap", "Int", "Integer", "List",
    "Long", "Map", "Math", "MutableCollection", "Nothing", "Object", "Pair", "Random",
    "Runnable", "Set", "Short", "String", "StringBuilder", "System", "Thread", "Triple",
    "Unit", "VK10",
}

# Lowercase builders and extension functions that are part of the box-level DSL, and so
# would be missed by the capitalised-type scan below.
DSL_SYMBOLS = [
    "computeModuleHere",
    "defaultBuffer",
    "fromContext",
    "keyCamera",
    "mapAndRead",
    "mapAndWrite",
    "moduleHere",
    "registerLygiaIncludeRoot",
    "transferNow",
]

# GLSL features worth indexing: an agent writing a shader for one of these lessons wants to
# know which lesson already demonstrates buffer references or push constants.
GLSL_FEATURES = [
    ("GL_EXT_buffer_reference", "buffer_reference"),
    ("push_constant", "push_constant"),
    ("imageStore", "imageStore"),
    ("imageLoad", "imageLoad"),
    ("subpassLoad", "subpassLoad"),
    ("debugPrintfEXT", "debugPrintfEXT"),
    ("#include", "#include"),
]


class Box:
    def __init__(self, uid: str, name: str, parents: list[str], tombstoned: bool) -> None:
        self.uid = uid
        self.name = name
        self.parents = parents
        self.tombstoned = tombstoned
        self.files: dict[str, Path] = {}
        self.symbols: set[str] = set()

    @property
    def display_name(self) -> str:
        """The box name with incidental whitespace removed (one lesson is saved as
        `5. draw pingpoing `, and the trailing space is a typo, not a name)."""
        return self.name.strip()

    @property
    def lesson_key(self) -> tuple[int, str, str]:
        """Sort key placing 3 before 3b before 4, and holding insertion order otherwise."""
        match = LESSON_NAME.match(self.name)
        if not match:
            return (10**6, "", self.name)
        return (int(match.group(1)), match.group(2), match.group(3))

    @property
    def is_lesson(self) -> bool:
        return bool(LESSON_NAME.match(self.name))


def read_boxes() -> dict[str, Box]:
    """Parse the box records out of the corpus `metadata.edn`.

    This deliberately does not build a general EDN reader: the three fields needed here
    (`id`, `name`, `parents`) are flat, and a regex pass over the record head keeps the
    generator hermetic and fast enough to run inside a unit test.
    """
    edn = (CORPUS / "metadata.edn").read_text(encoding="utf-8")
    boxes: dict[str, Box] = {}
    for record in edn.split('{"id" "')[1:]:
        uid = record.split('"')[0]
        head = record[:8000]
        name_match = re.search(r'"name" "((?:[^"\\]|\\.)*)"', head)
        parents_match = re.search(r'"parents" #\{([^}]*)\}', head)
        boxes[uid] = Box(
            uid=uid,
            name=name_match.group(1) if name_match else uid,
            parents=(
                re.findall(r'"([0-9a-f-]{36}|root)"', parents_match.group(1))
                if parents_match
                else []
            ),
            tombstoned='"_tombstone" true' in head,
        )
    return boxes


def attach_files(box: Box) -> None:
    for prop, ext in SIDECAR_PROPERTIES:
        path = CORPUS / f"{box.uid}_{prop}_.{ext}"
        # Field writes a sidecar for every property a box has ever had, so an emptied
        # property leaves a one-byte newline behind. Listing that as "Kotlin" would send a
        # reader to a blank file; `5. vertex_included` is all shader and no script.
        if path.exists() and path.stat().st_size > MINIMUM_SIDECAR_BYTES:
            box.files[prop] = path


def scan_symbols(box: Box) -> None:
    kotlin = box.files.get("code")
    if kotlin is not None:
        source = kotlin.read_text(encoding="utf-8")
        # A capitalised name applied as a constructor, builder or generic argument.
        for match in re.finditer(r"\b([A-Z][A-Za-z0-9_]{2,})\s*(?:\(|\{|<)", source):
            box.symbols.add(match.group(1))
        for match in re.finditer(r"prop<([A-Za-z0-9_]+)>", source):
            box.symbols.add(match.group(1))
        for name in DSL_SYMBOLS:
            if re.search(r"\b" + re.escape(name) + r"\b", source):
                box.symbols.add(name)
        box.symbols -= SYMBOL_STOPLIST
    for prop in ("vertex", "fragment", "compute", "vertex_fragment"):
        path = box.files.get(prop)
        if path is None:
            continue
        shader = path.read_text(encoding="utf-8")
        for needle, label in GLSL_FEATURES:
            if needle in shader:
                box.symbols.add(label)


def parent_chain(box: Box, boxes: dict[str, Box]) -> list[str]:
    """The named ancestors a lesson needs above it, nearest parent last.

    Field resolves a box's `Renderer` through graph topology, so "runs under" is not
    decoration: `5. draw rtt` reads the texture that `5. RTT` renders into, and running it
    without that parent is the error the topology rule exists to explain.
    """
    chain: list[str] = []
    seen = {box.uid}
    cursor = box
    while True:
        named = [
            boxes[p]
            for p in cursor.parents
            if p != "root" and p in boxes and p not in seen and boxes[p].name != "root"
        ]
        if not named:
            break
        parent = named[0]
        seen.add(parent.uid)
        chain.append(parent.name)
        cursor = parent
    return list(reversed(chain))


def file_cell(box: Box, props: list[str]) -> str:
    names = [f"`{box.files[p].name}`" for p in props if p in box.files]
    return "<br>".join(names) if names else "—"


def render(boxes: dict[str, Box]) -> str:
    lessons = sorted(
        (b for b in boxes.values() if b.is_lesson and not b.tombstoned and b.files),
        key=lambda b: b.lesson_key,
    )
    renderers = [
        b
        for b in boxes.values()
        if not b.tombstoned and not b.is_lesson and "Renderer" in b.symbols
    ]

    out: list[str] = []
    out.append("# canonical_graphics — lesson index")
    out.append("")
    out.append(
        "<!-- Generated by tools/gen_canonical_graphics_index.py. Do not hand-edit: "
        "regenerate with that script and commit the result. Guarded by "
        "tools/test_canonical_graphics_index.py, which runs under ./gradlew checkPythonTools. -->"
    )
    out.append("")
    out.append(
        "This directory is a snapshot of a real Field document: the `afield.four` graphics "
        "curriculum, worked lesson by lesson against the live renderer. Every file is named by "
        "box uid, so this index exists to make the corpus reachable by name and by API. The "
        "prose walkthrough, with captured native-window images, is "
        "[afield.four Graphics](../../afield_four_graphics.md)."
    )
    out.append("")
    out.append(
        "Boxes here are read-only reference. To *run* one, copy its Kotlin and shader "
        "properties into a box of your own beneath a renderer parent — see the graphics "
        "topology rule in `dev_docs/agents/transcripts/README.md`."
    )
    out.append("")

    if renderers:
        out.append("## The renderer parent")
        out.append("")
        for box in sorted(renderers, key=lambda b: b.name):
            out.append(
                f"`{box.display_name}` — {file_cell(box, ['code'])}. "
                "Opens the `Window`, builds the `Renderer` and pumps the frame loop. Every lesson "
                "below runs as its child, directly or through another lesson."
            )
        out.append("")

    out.append("## Lessons")
    out.append("")
    out.append("| Lesson | Runs under | Kotlin | Shaders |")
    out.append("| --- | --- | --- | --- |")
    for box in lessons:
        chain = parent_chain(box, boxes)
        under = " → ".join(f"`{name.strip()}`" for name in chain) if chain else "—"
        shaders = file_cell(box, ["vertex", "fragment", "compute", "vertex_fragment", "vertex_s", "fragment_s", "compute_s"])
        out.append(
            f"| {box.display_name} | {under} | {file_cell(box, ['code'])} | {shaders} |"
        )
    out.append("")

    out.append("## What each lesson uses")
    out.append("")
    out.append(
        "Field API names and GLSL features scanned out of the sources above — the reverse "
        "index, for when you know the call you need but not the lesson that demonstrates it."
    )
    out.append("")
    index: dict[str, list[str]] = {}
    for box in lessons:
        for symbol in box.symbols:
            index.setdefault(symbol, []).append(box.display_name)
    for symbol in sorted(index, key=lambda s: (s.lower(), s)):
        users = ", ".join(index[symbol])
        out.append(f"- `{symbol}` — {users}")
    out.append("")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero if the committed LESSONS.md differs from freshly generated output",
    )
    args = parser.parse_args()

    boxes = read_boxes()
    for box in boxes.values():
        attach_files(box)
        scan_symbols(box)
    generated = render(boxes)

    if args.check:
        if not INDEX_FILE.exists():
            print(f"{INDEX_FILE.relative_to(REPO_ROOT)} is missing — run this script without --check")
            return 1
        committed = INDEX_FILE.read_text(encoding="utf-8")
        if committed != generated:
            print(
                f"{INDEX_FILE.relative_to(REPO_ROOT)} is stale.\n"
                "Regenerate with:\n"
                "  uv run --project . --group backdoor --frozen python "
                "tools/gen_canonical_graphics_index.py"
            )
            return 1
        print(f"{INDEX_FILE.relative_to(REPO_ROOT)} is current")
        return 0

    INDEX_FILE.write_text(generated, encoding="utf-8")
    lessons = sum(
        1 for b in boxes.values() if b.is_lesson and not b.tombstoned and b.files
    )
    print(f"wrote {INDEX_FILE.relative_to(REPO_ROOT)} ({lessons} lessons)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
