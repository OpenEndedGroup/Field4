# /// script
# requires-python = ">=3.12"
# dependencies = ["fonttools[woff]==4.66.1"]
# ///
"""Bake Field's zero into official Iosevka Etoile webfonts; no outline synthesis.

uv run tools/fonts/build_field_etoile.py --archive /path/to/PkgWebFont-IosevkaEtoile-34.7.0.zip
See README.md in this directory for the pinned upstream source and license.
"""
from __future__ import annotations

import argparse
import hashlib
import io
from pathlib import Path
import zipfile

from fontTools.ttLib import TTFont

SOURCE_SHA256 = "df9faa02aaefe2c5506828962df2bded7d68fec5ec5bab5bae2b594873a05b6c"
ZERO_GLYPH = "zero.cv10-5"  # cv10=5: tall-reverse-slashed in upstream 34.7.0's character-variants.md.
FACES = {"Light": "light", "LightItalic": "light-italic", "Regular": "regular", "Italic": "italic", "Bold": "bold", "BoldItalic": "bold-italic"}


def build(archive: Path, output: Path, faces: list[str] | None = None) -> None:
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Expected the pinned Iosevka Etoile 34.7.0 webfont archive (SHA256 mismatch)")
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for upstream_style, suffix in FACES.items():
            if faces and upstream_style not in faces:
                continue
            font = TTFont(io.BytesIO(source.read(f"WOFF2/IosevkaEtoile-{upstream_style}.woff2")), recalcTimestamp=False)
            if ZERO_GLYPH not in font.getGlyphOrder():
                raise ValueError(f"Missing zero variant in {upstream_style}")
            original_outline = font["glyf"][ZERO_GLYPH].compile(font["glyf"])
            for table in font["cmap"].tables:
                if table.isUnicode() and 0x30 in table.cmap:
                    table.cmap[0x30] = ZERO_GLYPH
            # Give the derivative its own family and PostScript names; retain copyright/license records.
            style = {"BoldItalic": "Bold Italic", "LightItalic": "Light Italic"}.get(upstream_style, upstream_style)
            replacements = {1: "Field Etoile", 2: style, 3: f"FieldEtoile-{upstream_style}-34.7.0",
                            4: f"Field Etoile {style}", 6: f"FieldEtoile-{upstream_style}",
                            16: "Field Etoile", 17: style, 18: f"Field Etoile {style}",
                            21: "Field Etoile", 22: style}
            for record in font["name"].names:
                if record.nameID in replacements:
                    record.string = replacements[record.nameID].encode(record.getEncoding())
            font.flavor = "woff2"
            destination = output / f"field-etoile-{suffix}.woff2"
            font.save(destination)
            # Verify the serialized file, including the actual default glyph and proportional metrics.
            with TTFont(destination) as result:
                assert result.getBestCmap()[0x30] == ZERO_GLYPH
                assert result["glyf"][ZERO_GLYPH].compile(result["glyf"]) == original_outline
                cmap = result.getBestCmap()
                assert result["hmtx"][cmap[ord("i")]][0] != result["hmtx"][cmap[ord("W")]][0]
                assert result["name"].getDebugName(1) == "Field Etoile"
            print(f"{destination.name}: {destination.stat().st_size:,} bytes; default 0={ZERO_GLYPH}; proportional metrics verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / "src/jsMain/resources/woff2")
    parser.add_argument("--face", action="append", choices=list(FACES), help="Build only a selected face (repeatable)")
    args = parser.parse_args()
    build(args.archive, args.output, args.face)
