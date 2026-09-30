#!/usr/bin/env python3
"""Package the stack.auto conversation starter and its recorded session for the public page.

Everything published here comes from the recorded session. Nothing is re-rendered or summarised:

- each turn's transcript page is built from ``conversations/<turn>.md``, and the person's action
  from ``conversations/<action>.human.json``;
- the images on those pages, and the ones the page shows, are the exact bytes the model was shown,
  taken out of the chat history Field saved (``polytope_source_ai4_chat_history_.md`` keeps each one
  as a data URL); the ``conversations/images`` sidecars the recorder wrote are the same bytes and
  are not committed;
- the page's listings are copied from the saved revisions of each slot
  (``studies/polytope_source/stack.auto/<slot>/revisions/``), the starter, and the recorded box's
  shaders and code, between ``<!-- stackauto-study-source:NAME -->`` markers;
- the pictures of Field itself come from ``tools/capture_stackauto_conversation.py``; this script
  only requires that they exist.

The rendering, zip and checks are ``tools/conversation_demo.py``'s. Run with --check to verify the
committed page and assets without rewriting them.
Use: uv run --project . --group docs python tools/package_stackauto_conversation.py [--check]
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import conversation_demo as demo

ROOT = demo.ROOT
DEMOS = ROOT / "dev_docs/agents/demos"
OUTPUT = ROOT / "public/docs/assets/stackauto_conversation"
PAGE = ROOT / "public/docs/stackauto_conversation.md"
STARTER = "stackauto_conversation"
EXAMPLE = "stackauto_conversation_polytope"
CHAT_FILE = "polytope_source_ai4_chat_history_.md"
CODE_FILE = "polytope_source_code_.kt"
STARTER_FILES = ("README.md", "AGENTS.md", "metadata.edn", "polytope_renderer_code_.kt", CODE_FILE,
                 "polytope_source_vertex_.glsl", "polytope_source_fragment_.glsl")
TOOLS_SOURCE = ROOT / "src/jvmMain/kotlin/afield/stacksl/study/StackAutoStudyTools.kt"
CAPTURED = ("starter.png", "burst.mp4", "card.png", "in-shader.png", "saved-chat.png")
IMAGE_REF = re.compile(r"\]\((images/[0-9a-f]{20}\.(?:png|jpg))\)")
TITLE = "A polytope that bursts and burns"
TURNS = {
    "explode-01-burst": "A burst, the top first",
    "explode-02-shrink": "Open it up: out fast, back slowly",
    "decay-01-embers": "Hot metal, cooling",
    "decay-02-burn": "Burning through at the peak",
    "decay-03-ragged": "Ragged frames, not dots",
    "decay-04-look": "Is it what I asked for?",
}


def folder() -> Path:
    return DEMOS / EXAMPLE


def turn_text(turn: str) -> str:
    """The recorded turn as Field rendered it, checked against the chat Field saved."""
    text = (folder() / "conversations" / (turn + ".md")).read_text()
    assert text.startswith(f"# {turn}\n\n<<you>>\n"), f"{turn}: not a recorded turn"
    body = text.split("\n\n", 1)[1]
    record = json.loads((folder() / "conversations" / (turn + ".json")).read_text())
    assert record["status"] == "completed", f"{turn}: the recorded turn did not complete"
    prompt = body.split("<<you>>", 1)[1].split("<<", 1)[0].strip()
    assert prompt == record["prompt"].strip(), f"{turn}: transcript prompt differs from the record"
    assert body.strip() in demo.externalize_images((folder() / CHAT_FILE).read_text(), None), \
        f"{turn}: transcript is not in the chat history Field saved"
    return text


def describe_action(action: dict) -> str:
    if action["action"] == "run":
        return ("Ran the polytope box. Its Kotlin ran again, which built the mesh the model had written, "
                "and the window drew the burn-through as frames; the slots stayed at "
                f"`{action['revisionAfter']}`.")
    raise ValueError(f"Unknown person-side action {action['action']}")


def actions_after() -> dict[str, list[dict]]:
    """Attach each person-side action to the turn it follows: decay-03a-run follows decay-03-ragged."""
    placed: dict[str, list[dict]] = {t: [] for t in TURNS}
    for path in sorted((folder() / "conversations").glob("*.human.json")):
        action = json.loads(path.read_text())
        prefix = re.match(r"^[a-z]+-\d+", action["label"]).group(0)
        owner = [t for t in TURNS if t.startswith(prefix + "-")]
        assert len(owner) == 1, f"{action['label']} follows no recorded turn"
        placed[owner[0]].append(action)
    return placed


def person_block(actions: list[dict], heading: str) -> str:
    if not actions:
        return ""
    items = "".join("<li>" + re.sub(r"^<p>|</p>$", "", demo.prose(describe_action(a)).strip()) + "</li>" for a in actions)
    return demo.section("person", heading, f"<ul>{items}</ul>")


def reader(turn: str) -> bytes:
    turns = list(TURNS)
    position = turns.index(turn)
    placed = actions_after()
    sections = ""
    if position:
        sections += person_block(placed[turns[position - 1]], "The person, in Field, before this turn")
    sections += demo.render_turn(turn_text(turn).split("\n\n", 1)[1])
    return demo.transcript_page(
        title=f"{TURNS[turn]} · {TITLE}", eyebrow=f"Field · AI4 / {TITLE} / turn {position + 1:02}",
        titles=TURNS, current=turn, sections=sections,
        back_href="../../../stackauto_conversation/", back_label="Talking to the StackSL in your shader",
        css_href="../conversation.css")


def assets() -> dict[str, bytes]:
    starter = DEMOS / STARTER
    result = {
        "conversation.css": demo.CSS.encode(),
        f"{STARTER}.zip": demo.deterministic_zip(STARTER, {f: (starter / f).read_bytes() for f in STARTER_FILES}),
        "StackAutoStudyTools.kt": TOOLS_SOURCE.read_bytes(),
    }
    images = demo.chat_images((folder() / CHAT_FILE).read_text())
    for turn in TURNS:
        text = turn_text(turn)
        result[f"polytope/{turn}.txt"] = text.encode()
        result[f"polytope/{turn}.html"] = reader(turn)
        for path in IMAGE_REF.findall(text):
            image = path.removeprefix("images/")
            assert image in images, f"{turn}: {image} is not in the saved chat"
            result[f"polytope/{path}"] = images[image]
    result["polytope/final-code.kt"] = (folder() / CODE_FILE).read_bytes()
    return result


def revision(slot: str, prefix: str) -> str:
    matches = sorted((folder() / f"studies/polytope_source/stack.auto/{slot}/revisions").glob(prefix + "-*.stacksl"))
    assert len(matches) == 1, f"{slot}: expected one revision {prefix}, found {len(matches)}"
    return matches[0].read_text()


def head(slot: str) -> str:
    revisions = folder() / f"studies/polytope_source/stack.auto/{slot}/revisions"
    return (revisions / (revisions / "HEAD").read_text().strip()).with_suffix(".stacksl").read_text()


def excerpt(source: str, start: str, end: str) -> str:
    begin = source.index(start)
    return source[begin:source.index(end, begin) + len(end)].rstrip()


def fence(source: str, lang: str = "stacksl") -> str:
    return f"```diffstatic {{data-lang={lang}}}\n" + source.strip("\n") + "\n```"


def edit(before: str, after: str, lang: str) -> str:
    return f"```diff {{data-lang={lang}}}\n:::before\n" + before.strip("\n") + "\n:::after\n" + after.strip("\n") + "\n```"


def folded(title: str, source: str, lang: str = "stacksl") -> str:
    body = "\n".join(("    " + line) if line else "" for line in fence(source, lang).splitlines())
    return f'??? example "{title}"\n\n{body}'


def source_blocks() -> dict[str, str]:
    """Listings on the page, each copied from the starter, a saved revision or the recorded box."""
    starter = DEMOS / STARTER
    code = (folder() / CODE_FILE).read_text()
    for slot in ("explode", "face_decay"):
        assert f'module.{slot} = """{head(slot)}"""' in code, f"the box's code does not hold stack.auto.{slot}'s HEAD"
    starter_fragment = (starter / "polytope_source_fragment_.glsl").read_text()
    final_fragment = (folder() / "polytope_source_fragment_.glsl").read_text()
    return {
        "starter-slots": fence(excerpt((starter / CODE_FILE).read_text(), "// The vertex shader's slot", '[0.75,0.72,0.68,1]\n"""'), "kotlin"),
        "starter-vertex": fence(excerpt((starter / "polytope_source_vertex_.glsl").read_text(), "// explode(vec4", "* e.x;"), "glsl"),
        "starter-fragment": fence(excerpt(starter_fragment, "// face_decay(vec4", "discard;"), "glsl"),
        "explode-burst": fence(revision("explode", "r2")),
        "explode-shrink": fence(revision("explode", "r3")),
        "decay-embers": fence(revision("face_decay", "r2")),
        "decay-burn": folded("face_decay after decay-02 · small round holes", revision("face_decay", "r3")),
        "decay-host": edit(excerpt(starter_fragment, "// face_decay(vec4", "discard;"),
                           excerpt(final_fragment, "// face_decay(vec4", "discard;"), "glsl"),
        "decay-mesh": folded("The mesh the model rebuilt, in the box's Kotlin — since shortened with faces, vertexMap and faceCentroids",
                             excerpt(code, "val solid = canonicalConway", "\n// Time,").removesuffix("\n// Time,"), "kotlin"),
        "decay-final": fence(head("face_decay")),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="Verify without writing")
    args = parser.parse_args()
    demo.sync_blocks(PAGE, "stackauto-study-source", source_blocks(), args.check)
    demo.settle_assets(OUTPUT, assets(), args.check, "stack.auto conversation", CAPTURED,
                       "tools/capture_stackauto_conversation.py", gitignore=f"!{STARTER}.zip\n")


if __name__ == "__main__":
    main()
