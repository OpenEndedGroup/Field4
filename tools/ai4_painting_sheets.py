#!/usr/bin/env python3
"""Lay out a painting's stages from an AI4 Studio document as a contact sheet on black.

The `ai4_paint_studio` starting point keeps every `paint_look` the agent makes as
`<document>/progress/NNN.png` and every painting it hangs as `<document>/gallery/<title>.png`. This
script puts a run of them side by side, each with its caption, for the docs:

    uv run --project . python tools/ai4_painting_sheets.py \\
        --document <workspace>/harbour --out public/docs/assets/ai4_painting/dusk.jpg \\
        --frame progress/001.png=bare\\ ground --frame "gallery/Harbour at dusk.png=hung"

Nothing is drawn on the frames themselves: they are the canvas exactly as the agent saw it.
"""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONTS = [
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "C:/Windows/Fonts/arial.ttf",
]


def font(size: int) -> ImageFont.ImageFont:
    for path in FONTS:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def sheet(frames: list[tuple[Path, str]], columns: int, width: int, text_size: int) -> Image.Image:
    images = []
    for path, caption in frames:
        image = Image.open(path).convert("RGB")
        images.append((image.resize((width, round(image.height * width / image.width)), Image.LANCZOS), caption))
    gap, caption_height = 14, text_size * 2
    # Frames can differ in shape (a fresh canvas of another aspect mid-painting): every cell is as tall as
    # the tallest, each frame centred in its cell.
    height = max(image.height for image, _ in images)
    rows = (len(images) + columns - 1) // columns
    out = Image.new("RGB", (columns * (width + gap) + gap, rows * (height + caption_height + gap) + gap), "black")
    draw = ImageDraw.Draw(out)
    text = font(text_size)
    for i, (image, caption) in enumerate(images):
        x = gap + (i % columns) * (width + gap)
        y = gap + (i // columns) * (height + caption_height + gap)
        out.paste(image, (x, y + (height - image.height) // 2))
        draw.text((x, y + height + text_size // 2), caption, fill=(200, 200, 200), font=text)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--document", type=Path, required=True, help="the Studio document folder")
    parser.add_argument("--frame", action="append", required=True,
                        help="path inside the document, optionally =caption (repeatable, in order)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--columns", type=int, default=4)
    parser.add_argument("--width", type=int, default=360, help="width of each frame in pixels")
    parser.add_argument("--text-size", type=int, default=30,
                        help="caption size in pixels; a sheet is shown at about half size in the docs column")
    args = parser.parse_args()
    frames = []
    for spec in args.frame:
        name, _, caption = spec.partition("=")
        frames.append((args.document / name, caption or Path(name).stem))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    sheet(frames, args.columns, args.width, args.text_size).save(args.out, quality=90)
    print(args.out)


if __name__ == "__main__":
    main()
