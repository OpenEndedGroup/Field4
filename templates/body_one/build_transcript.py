#!/usr/bin/env python3
"""Build body_one's starting point and live-document operations from its editable source."""
import argparse
import json
from pathlib import Path

import yaml


def build():
    here = Path(__file__).resolve().parent
    boxes = [
        ("body1_context", "1 · Headset / preview / Field pane", "context.kt", 0),
        ("body1_drawing", "2 · Tracking markers and connections", "drawing.kt", 600),
        ("body1_tracking", "3 · Tracking / body queries", "tracking.kt", 1200),
    ]
    operations = []
    for uid, name, file, x in boxes:
        properties = {"code": (here / file).read_text()}
        if uid == "body1_drawing":
            properties.update({stage: (here / f"{stage}.glsl").read_text() for stage in ("vertex", "fragment")})
        operations.append({"create": {"box": uid, "name": name, "frame": {"x": x, "y": 0, "w": 500, "h": 100}, "properties": properties}})
    operations += [
        {"connect": {"parent": "body1_context", "child": "body1_drawing"}},
        {"connect": {"parent": "body1_drawing", "child": "body1_tracking"}},
    ]
    launch = [{"launch": {"box": uid}} for uid, *_ in boxes] + [{"await": {"wait": "pending"}}]
    return operations, launch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--transcript", type=Path)
    parser.add_argument("--docs", type=Path, help="Refresh the folded source section of this Markdown page")
    parser.add_argument("--output", type=Path, default=Path("/tmp/body-one-transcripts"))
    args = parser.parse_args()
    create, launch = build()
    args.output.mkdir(parents=True, exist_ok=True)
    for name, operations in [("create", create), ("launch", launch), ("stop", [{"stop": {"box": uid}} for uid in ("body1_tracking", "body1_drawing", "body1_context")])]:
        (args.output / f"{name}.json").write_text(json.dumps({"operations": operations}, indent=2, ensure_ascii=False) + "\n")
    if args.docs:
        here = Path(__file__).resolve().parent
        text = args.docs.read_text().split("\n## Editable source", 1)[0].rstrip() + "\n\n## Editable source\n"
        for title, file in [("Headset, browser and preview", "context.kt"), ("Marker pool and draw attachment", "drawing.kt"), ("Tracking, controls and geometric queries", "tracking.kt"), ("GPU thickening", "vertex.glsl"), ("Analytic coverage", "fragment.glsl")]:
            language = "kotlin" if file.endswith(".kt") else "glsl"
            text += f"\n```diffstatic {{data-lang={language}}}\n:::fold {title}\n" + (here / file).read_text() + ":::endfold\n```\n"
        args.docs.write_text(text)
    if args.transcript:
        class Dumper(yaml.SafeDumper):
            pass
        Dumper.add_representer(str, lambda d, s: d.represent_scalar("tag:yaml.org,2002:str", s, style="|" if "\n" in s else None))
        transcript = {
            "name": "body_one",
            "description": "Inspect WiVRn body joints, both hands and controllers in OpenXR. Validity-aware skeleton drawing, joint axes, world bounds and closest-point queries, with an optional inspection copy and a native headset preview.",
            "docs": [{"page": "body_tracking.md", "why": "Tracking availability, body geometry queries and the editable body_one starting point."}],
            "operations": create + launch,
        }
        args.transcript.parent.mkdir(parents=True, exist_ok=True)
        args.transcript.write_text(yaml.dump(transcript, Dumper=Dumper, sort_keys=False, allow_unicode=True, width=100))


if __name__ == "__main__":
    main()
