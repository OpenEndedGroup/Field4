#!/usr/bin/env python3
"""Package the RectSL conversation starter and the three recorded sessions for the public page.

Everything published here comes from a recorded session. Nothing is re-rendered or summarised:

- each turn's transcript page is built from ``conversations/<turn>.md``, and the page's person-side
  actions come from ``conversations/<action>.human.json``;
- the images on those pages are the exact bytes the model was shown, taken out of the chat history
  Field saved (``rectsl_source_ai4_chat_history_.md`` keeps each one as a data URL); the
  ``conversations/images`` sidecars the recorder wrote are the same bytes and are not committed;
- the page's listings (starter, final scenes, the edits it discusses) are copied from the saved
  revisions (``studies/rectsl_source/``) between ``<!-- rectsl-study-source:NAME -->`` markers;
- the three screenshots of Field itself come from ``tools/capture_rectsl_conversation.py``; this
  script only requires that they exist.

The rendering, zip and checks are ``tools/conversation_demo.py``'s. Run with --check to verify the
committed page and assets without rewriting them.
Use: uv run --project . --group docs python tools/package_rectsl_conversation.py [--check]
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

import conversation_demo as demo

ROOT = demo.ROOT
DEMOS = ROOT / "dev_docs/agents/demos"
OUTPUT = ROOT / "public/docs/assets/rectsl_conversation"
PAGE = ROOT / "public/docs/rectsl_conversation.md"
STARTER = "rectsl_conversation"
CHAT_FILE = "rectsl_source_ai4_chat_history_.md"
SCENE_FILE = "rectsl_source_rectSL_.rectsl"
STUDY = "studies/rectsl_source"
STARTER_FILES = ("README.md", "AGENTS.md", "metadata.edn", SCENE_FILE, "rectsl_window_code_.kt")
TOOLS_SOURCE = ROOT / "src/jvmMain/kotlin/afield/rectsl/study/RectSLStudyTools.kt"
# Screenshots of Field itself, taken by tools/capture_rectsl_conversation.py rather than built here.
CAPTURED = ("card.png", "saved-chat.png", "window.png")
IMAGE_REF = re.compile(r"\]\((images/[0-9a-f]{20}\.(?:png|jpg))\)")

EXAMPLES = {
    "lanterns": {
        "title": "Lanterns at dusk",
        "turns": {
            "01-lanterns": "Paper lanterns",
            "02-scatter": "A less orderly sky",
            "03-moods": "Three skies, motion untouched",
            "04-loop": "A seamless loop, shown",
        },
        # Exports the session wrote into the document; the clip is shown on the page.
        "files": ("files/lanterns-seam-check-two-loops.mp4",),
    },
    "tide": {
        "title": "A tide under a rising moon",
        "turns": {
            "01-tide": "The tide",
            "02-moon": "A moon and its shimmer",
            "03-faster": "Twice as fast, the moon untouched",
        },
        "files": (),
    },
    "score": {
        "title": "A visual score",
        "turns": {
            "01-score": "A score at 120 bpm",
            "02-canon": "A canon",
            "03-accent": "Which line? A longer accent",
        },
        "files": (),
    },
}


def example_folder(name: str) -> Path:
    return DEMOS / f"rectsl_conversation_{name}"


def turn_text(name: str, turn: str) -> str:
    """The recorded turn as Field rendered it, checked against the chat Field saved."""
    folder = example_folder(name)
    text = (folder / "conversations" / (turn + ".md")).read_text()
    assert text.startswith(f"# {turn}\n\n<<you>>\n"), f"{name}/{turn}: not a recorded turn"
    body = text.split("\n\n", 1)[1]
    record = json.loads((folder / "conversations" / (turn + ".json")).read_text())
    assert record["status"] == "completed", f"{name}/{turn}: the recorded turn did not complete"
    prompt = body.split("<<you>>", 1)[1].split("<<", 1)[0].strip()
    assert prompt == record["prompt"].strip(), f"{name}/{turn}: transcript prompt differs from the record"
    assert body.strip() in demo.externalize_images((folder / CHAT_FILE).read_text(), None), \
        f"{name}/{turn}: transcript is not in the chat history Field saved"
    return text


def describe_action(action: dict) -> str:
    kind = action["action"]
    after = action["revisionAfter"]
    description = action["after"]["facts"]["description"]
    if kind == "adopt":
        return f"Adopted variant **{action['values'][0]}** in the study card → `{after}` “{description}”"
    if kind == "keep":
        knob, value = action["values"]
        return f"Moved knob {knob} to {value} with its slider in the study card and pressed Keep → `{after}` “{description}”"
    if kind == "edit":
        return (f"Edited the scene by hand: `{action['replaced']}` became `{action['with']}`. "
                "The edit went through the property the editor writes and the window followed it; it was "
                "not checked in, so the study reported the editor as dirty.")
    if kind == "checkin":
        return f"Pressed Check in on the study card → `{after}` “{description}”"
    raise ValueError(f"Unknown person-side action {kind}")


def actions_between(name: str) -> dict[str, list[dict]]:
    """Attach each person-side action to the turn it follows: 03a-adopt follows 03-moods."""
    turns = list(EXAMPLES[name]["turns"])
    placed: dict[str, list[dict]] = {t: [] for t in turns}
    for path in sorted((example_folder(name) / "conversations").glob("*.human.json")):
        action = json.loads(path.read_text())
        owner = [t for t in turns if t[:2] == action["label"][:2]]
        assert owner, f"{name}: {action['label']} follows no recorded turn"
        placed[owner[0]].append(action)
    return placed


def person_block(actions: list[dict], heading: str) -> str:
    if not actions:
        return ""
    items = "".join("<li>" + re.sub(r"^<p>|</p>$", "", demo.prose(describe_action(a)).strip()) + "</li>" for a in actions)
    return demo.section("person", heading, f"<ul>{items}</ul>")


def reader(name: str, turn: str) -> bytes:
    example = EXAMPLES[name]
    turns = list(example["turns"])
    position = turns.index(turn)
    placed = actions_between(name)
    sections = ""
    if position:
        sections += person_block(placed[turns[position - 1]], "The person, in Field, before this turn")
    sections += demo.render_turn(turn_text(name, turn).split("\n\n", 1)[1])
    if position + 1 == len(turns):
        sections += person_block(placed[turn], "The person, in Field, after this turn")
    return demo.transcript_page(
        title=f"{example['turns'][turn]} · {example['title']}", eyebrow=f"Field · AI4 / {example['title']} / turn {position + 1:02}",
        titles=example["turns"], current=turn, sections=sections,
        back_href="../../../rectsl_conversation/", back_label="Talking a scene into existence", css_href="../conversation.css")


def assets() -> dict[str, bytes]:
    folder = DEMOS / STARTER
    result = {
        "conversation.css": demo.CSS.encode(),
        f"{STARTER}.zip": demo.deterministic_zip(STARTER, {f: (folder / f).read_bytes() for f in STARTER_FILES}),
        "RectSLStudyTools.kt": TOOLS_SOURCE.read_bytes(),
    }
    for name, example in EXAMPLES.items():
        folder = example_folder(name)
        images = demo.chat_images((folder / CHAT_FILE).read_text())
        for turn in example["turns"]:
            text = turn_text(name, turn)
            result[f"{name}/{turn}.txt"] = text.encode()
            result[f"{name}/{turn}.html"] = reader(name, turn)
            for path in IMAGE_REF.findall(text):
                image = path.removeprefix("images/")
                assert image in images, f"{name}/{turn}: {image} is not in the saved chat"
                result[f"{name}/{path}"] = images[image]
        result[f"{name}/final.rectsl"] = (folder / SCENE_FILE).read_bytes()
        for relative in example["files"]:
            result[f"{name}/{Path(relative).name}"] = (folder / relative).read_bytes()
    return result


def revision(name: str, prefix: str) -> str:
    matches = sorted((example_folder(name) / STUDY).glob(prefix + "-*.rectsl"))
    assert len(matches) == 1, f"{name}: expected one revision {prefix}, found {len(matches)}"
    return matches[0].read_text()


def lines_with(source: str, *needles: str) -> str:
    """The lines of [source] containing any of [needles], in order — how an edit's reach is shown."""
    return "\n".join(line for line in source.splitlines() if any(n in line for n in needles))


def excerpt(source: str, start: str, end: str) -> str:
    begin = source.index(start)
    return source[begin:source.index(end, begin) + len(end)].rstrip()


def fence(source: str) -> str:
    return "```diffstatic {data-lang=rectsl}\n" + source.rstrip() + "\n```"


def edit(before: str, after: str) -> str:
    return "```diff {data-lang=rectsl}\n:::before\n" + before.rstrip() + "\n:::after\n" + after.rstrip() + "\n```"


def folded(title: str, source: str) -> str:
    body = "\n".join(("    " + line) if line else "" for line in fence(source).splitlines())
    return f'??? example "{title}"\n\n{body}'


def source_blocks() -> dict[str, str]:
    """Listings on the page, each copied from a saved revision."""
    heads = {name: (example_folder(name) / STUDY / "HEAD").read_text().strip() for name in EXAMPLES}
    finals = {name: (example_folder(name) / SCENE_FILE).read_text() for name in EXAMPLES}
    for name in EXAMPLES:
        assert finals[name] == revision(name, heads[name].split("-")[0]), f"{name}: the box does not hold HEAD"
    sky = ("# Blue", '"night" rect')
    return {
        "starter": fence((DEMOS / STARTER / SCENE_FILE).read_text()),
        "lanterns-dusk": edit(lines_with(revision("lanterns", "r3"), *sky), lines_with(revision("lanterns", "r4"), *sky)),
        "tide-faster": edit(excerpt(revision("tide", "r4"), '"tide" rect', ") array ;"), excerpt(revision("tide", "r5"), '"tide" rect', ") array ;")),
        "score-accent": edit(lines_with(revision("score", "r4"), 'linear-track "four-beats"'),
                             lines_with(revision("score", "r5"), 'linear-track "four-beats"')),
        "lanterns-final": folded(f"The complete scene · {heads['lanterns']}", finals["lanterns"]),
        "tide-final": folded(f"The complete scene · {heads['tide']}", finals["tide"]),
        "score-final": folded(f"The complete scene · {heads['score']}", finals["score"]),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="Verify without writing")
    args = parser.parse_args()
    demo.sync_blocks(PAGE, "rectsl-study-source", source_blocks(), args.check)
    demo.settle_assets(OUTPUT, assets(), args.check, "RectSL conversation", CAPTURED,
                       "tools/capture_rectsl_conversation.py", gitignore=f"!{STARTER}.zip\n")


if __name__ == "__main__":
    main()
