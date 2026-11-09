#!/usr/bin/env python3
"""Prepare the audio asset and a Field operation transcript; never edit document metadata."""
import argparse
import json
import shutil
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("document", type=Path, help="Existing text_one document folder")
    parser.add_argument("--output", type=Path, default=Path("/tmp/text-one-spatial-audio.json"))
    args = parser.parse_args()
    document = args.document.resolve()
    if not (document / "metadata.edn").is_file():
        parser.error(f"Not an existing Field document: {document}")
    here = Path(__file__).resolve().parent
    repo = here.parents[2]
    files = document / "files"
    files.mkdir(exist_ok=True)
    destination = files / "spatial-demo.wav"
    source = repo / "public/docs/assets/eigendrum/square-centre.wav"
    if destination.exists() and destination.read_bytes() != source.read_bytes():
        parser.error(f"Refusing to overwrite a different audio asset: {destination}")
    shutil.copyfile(source, destination)
    guides = document / "guides"
    guides.mkdir(exist_ok=True)
    guide = guides / "08-spatial-audio.md"
    if not guide.exists():
        shutil.copyfile(here.parent / "guides/08-spatial-audio.md", guide)
    operations = [
        {"create": {"box": "text1_spatial_wav", "name": "18 · Head-tracked WAV",
                    "frame": {"x": 2350, "y": 1000, "w": 480, "h": 100},
                    "properties": {"code": (here / "spatial_wav.kt").read_text()}}},
        {"connect": {"parent": "text1_context", "child": "text1_spatial_wav"}},
    ]
    args.output.write_text(json.dumps({"operations": operations}, ensure_ascii=False, indent=2) + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
