#!/usr/bin/env python3
"""Lint: every asset a *published* docs page references must be committed to git.

A docs page can point at an image/video that exists in a warm working tree but was
never ``git add``-ed — a ``diffstatic_runner`` capture that the docs build regenerated,
or a hand-placed hero/still. It renders fine locally and 404s on a clean checkout or in
CI. This script catches that class before it merges.

Scope
-----
By default only pages listed in ``public/zensical.yml`` ``nav:`` are checked — that is
what actually ships. Unpublished POC pages (e.g. ``diff-player.md``) and ``dev_docs``
are skipped. Pass ``--all`` to scan every markdown file under the docs root.

Resolution
----------
References are resolved the way Zensical serves the site, checked against the *source*
tree:

* markdown-processed refs (``screenshot=``, ``poster=``, ``![](...)``, ``<img src>``)
  are relative to the source page's directory;
* raw-HTML refs in a pretty-URL site can carry a leading ``../`` to climb out of the
  page's own URL directory (page served at ``/foo/`` → ``../assets`` = ``/assets``).

So an asset is satisfied if it is git-tracked at *either* resolution. Only assets that
are untracked at both are reported (whether they are absent or merely unstaged — both
break a clean checkout).

Placeholder captures
--------------------
Being committed is necessary but not sufficient: a capture harness that cannot run its
capture may write a *stand-in* image, and a stand-in is a perfectly valid PNG that git
tracks and the page renders. Four figures on ``ray_tracing_examples.md`` shipped that way
for seven weeks -- identical "Ray Tracing / Not Available" cards under a page claiming
every image on it was rendered by Field. So this script also looks at pixels, and rejects
a shipped figure that matches a known placeholder card.

(A capture can also fail to a *black* frame, a different failure with different tells,
not detected here; see ``dev_docs/DOCS_RUNNER_GPU_CAPTURES.md``.)

One placeholder is *known* and grandfathered -- see ``KNOWN_PLACEHOLDERS`` -- because the
renders can only be produced on hardware this project does not universally have. Those are
reported on every run but do not gate. Any other placeholder fails immediately.

Exit status is non-zero when any published page references an untracked asset, or ships an
unexpected placeholder in place of a real capture, so this can gate a merge or run at the
end of a docs build.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zlib
from pathlib import Path, PurePosixPath

ASSET_EXTS = (".png", ".svg", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".mp4", ".webm", ".wav")

# Attribute-style refs (runner-fence headers + raw HTML). Covers runner capture
# outputs (`screenshot=` PNGs, `video=` MP4s, `audio=` WAVs) and HTML `src=`/`poster=`.
# Note this does NOT match `source-image=` (a local /tmp input to the runner, not a
# shipped asset): none of these tokens appears as `…=` inside "source-image".
ATTR_REF = re.compile(r'(?:screenshot|video|audio|poster|src)\s*=\s*"([^"]+)"')
MD_IMG = re.compile(r'!\[[^\]]*\]\(\s*([^)\s]+)')
EXTERNAL = ("http://", "https://", "data:", "mailto:", "#")


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True).stdout


def repo_root() -> Path:
    top = git(Path.cwd(), "rev-parse", "--show-toplevel").strip()
    return Path(top) if top else Path.cwd()


def nav_pages(config_yml: Path) -> set[str]:
    """Collect every `*.md` path listed under the config `nav:` block (relative to docs_dir)."""
    pages: set[str] = set()
    if not config_yml.exists():
        return pages
    in_nav = False
    for line in config_yml.read_text(encoding="utf-8").splitlines():
        if re.match(r"^nav:\s*$", line):
            in_nav = True
            continue
        if in_nav:
            # A new top-level key (non-indented, non-comment) ends the nav block.
            if line and not line[0].isspace() and not line.startswith("#"):
                break
            pages.update(re.findall(r"([\w./-]+\.md)", line))
    return pages


def extract_refs(text: str) -> list[str]:
    return ATTR_REF.findall(text) + MD_IMG.findall(text)


def resolve_candidates(page: Path, ref: str) -> list[Path]:
    """Filesystem candidates for `ref` on `page`, covering both resolution conventions."""
    clean = ref.split("#")[0].split("?")[0].strip()
    if clean.startswith("/"):
        # site-absolute — resolved against the docs root by the caller via `../` walk-up;
        # treated here as relative to the page dir after stripping the leading slash.
        clean = clean.lstrip("/")
    # A: markdown semantics — relative to the source page's directory.
    # B: pretty-URL semantics — page served one level deep (/<stem>/), so a leading
    #    `../` climbs back to the page dir; model that by nesting under the stem.
    return [
        (page.parent / clean).resolve(),
        (page.parent / page.stem / clean).resolve(),
    ]



# ── Placeholder-capture detection ───────────────────────────────────────────────────────
#
# `RayTracingDocsHarness.writeNotSupportedPlaceholder` (removed 2026-08-30) drew a 512x512
# card whenever the device had no VK_KHR_ray_tracing_pipeline: background #1a1a22, a 32px
# grid in #444455, and three centred lines of monospace text in #8888aa. Those three
# colours account for 99.2% of the pixels in each of the four cards it committed; glyph
# antialiasing is the remainder.
#
# The harness no longer writes cards, but the detector stays: it is what makes the failure
# mode *unrepeatable* by any future harness that reaches for the same convenience, and it
# is what keeps the four cards still in the tree visible on every single build.
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PLACEHOLDER_PALETTE = ((0x1A, 0x1A, 0x22), (0x44, 0x44, 0x55), (0x88, 0x88, 0xAA))
PLACEHOLDER_TOLERANCE = 6          # per channel, to absorb antialiased glyph edges
PLACEHOLDER_MIN_COVERAGE = 0.97    # measured: 0.992 on all four committed cards
PLACEHOLDER_GRID_PITCH = 32
PLACEHOLDER_MIN_GRID_LINE = 0.80   # a grid column/row is mostly grid-coloured
# Decoding is pure Python, so it is spent only on card-sized images. The card is exactly
# 512x512; a hero image or a wide screenshot is not a candidate and is not decoded.
PLACEHOLDER_MAX_PIXELS = 512 * 512


def png_header(data: bytes) -> tuple[int, int, int, int, int] | None:
    """`(width, height, bit_depth, colour_type, interlace)` from IHDR, or None if not a PNG."""
    if not data.startswith(PNG_SIGNATURE) or len(data) < 33:
        return None
    if data[12:16] != b"IHDR":
        return None
    width, height = int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
    return width, height, data[24], data[25], data[28]


def decode_png_rgb(data: bytes) -> tuple[int, int, list[bytes]] | None:
    """Decode 8-bit non-interlaced truecolour PNG to a flat list of RGB triples.

    Returns None for anything else -- 16-bit, palette, greyscale, interlaced, corrupt. That
    is the safe direction for a guard: an image this cannot read is reported as "not a
    placeholder" rather than guessed at, and the card is always 8-bit RGBA from ImageIO.
    """
    header = png_header(data)
    if header is None:
        return None
    width, height, depth, colour, interlace = header
    if depth != 8 or colour not in (2, 6) or interlace != 0:
        return None
    if width * height > PLACEHOLDER_MAX_PIXELS or width == 0 or height == 0:
        return None

    idat = bytearray()
    pos = 8
    while pos + 8 <= len(data):
        length = int.from_bytes(data[pos:pos + 4], "big")
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
        pos += 12 + length
    if not idat:
        return None
    try:
        raw = zlib.decompress(bytes(idat))
    except zlib.error:
        return None

    bpp = 3 if colour == 2 else 4
    stride = width * bpp
    if len(raw) < height * (stride + 1):
        return None

    pixels: list[bytes] = []
    prev = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if filter_type == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                predictor = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + predictor) & 0xFF
        elif filter_type != 0:
            return None
        pixels.extend(bytes(line[i:i + 3]) for i in range(0, stride, bpp))
        prev = line
    return width, height, pixels


def _palette_neighbourhood() -> set[bytes]:
    """Every colour within `PLACEHOLDER_TOLERANCE` per channel of a palette entry."""
    span = range(-PLACEHOLDER_TOLERANCE, PLACEHOLDER_TOLERANCE + 1)
    near: set[bytes] = set()
    for r, g, b in PLACEHOLDER_PALETTE:
        for dr in span:
            for dg in span:
                for db in span:
                    rr, gg, bb = r + dr, g + dg, b + db
                    if 0 <= rr <= 255 and 0 <= gg <= 255 and 0 <= bb <= 255:
                        near.add(bytes((rr, gg, bb)))
    return near


_PALETTE_NEAR = _palette_neighbourhood()


def placeholder_report(path: Path) -> tuple[float, float] | None:
    """`(palette_coverage, grid_line_score)` for a decodable candidate, else None."""
    try:
        data = path.read_bytes()
    except OSError:
        return None
    decoded = decode_png_rgb(data)
    if decoded is None:
        return None
    width, height, pixels = decoded

    coverage = sum(1 for px in pixels if px in _PALETTE_NEAR) / len(pixels)

    # The grid is the structural half of the fingerprint: a flat dark image can drift near
    # this palette by accident, but not with a #444455 rule every 32px in both directions.
    grid = bytes(PLACEHOLDER_PALETTE[1])
    lines: list[float] = []
    for x in range(0, width, PLACEHOLDER_GRID_PITCH):
        column = [pixels[y * width + x] for y in range(height)]
        lines.append(sum(1 for px in column if px == grid) / len(column))
    for y in range(0, height, PLACEHOLDER_GRID_PITCH):
        row = pixels[y * width:(y + 1) * width]
        lines.append(sum(1 for px in row if px == grid) / len(row))
    grid_score = min(lines) if lines else 0.0
    return coverage, grid_score


def looks_like_placeholder_card(path: Path) -> bool:
    """True when `path` is a "capture unavailable" card rather than a real capture."""
    report = placeholder_report(path)
    if report is None:
        return False
    coverage, grid_score = report
    return coverage >= PLACEHOLDER_MIN_COVERAGE and grid_score >= PLACEHOLDER_MIN_GRID_LINE


# Recorded 2026-08-30. These four figures on `ray_tracing_examples.md` are the cards
# described above, committed 2026-07-09 in f91613648 and shipped ever since. They are
# reported on every run but do not gate, because the real renders can only be produced on
# a machine with VK_KHR_ray_tracing_pipeline -- Linux CI has one, MoltenVK on macOS does
# not -- and no one should be unable to build docs in the meantime.
#
# This is a debt, not a policy. Each entry is a promise that a real capture is owed, and
# `tools/test_check_doc_assets.py` fails if an entry outlives the placeholder it names, so
# the list cannot quietly become permanent. Any placeholder *not* listed here fails now.
KNOWN_PLACEHOLDERS = {
    "public/docs/assets/rt-uv-gradient.png",
    "public/docs/assets/rt-triangle-scene.png",
    "public/docs/assets/rt-shader-tiles.png",
    "public/docs/assets/rt-mirror-balls.png",
}


def stale_placeholder_exceptions(root: Path) -> list[str]:
    """Entries in `KNOWN_PLACEHOLDERS` whose figure is no longer a placeholder."""
    stale = []
    for entry in sorted(KNOWN_PLACEHOLDERS):
        path = root / entry
        if not path.is_file() or not looks_like_placeholder_card(path):
            stale.append(entry)
    return stale


DIFFSTATIC_FENCE = re.compile(r"```diffstatic_runner \{([^}]*)\}")
FENCE_ASSET = re.compile(r'(?:screenshot|video|audio)="([^"]+)"')


def orphaned_capture_fences(text: str) -> list[str]:
    """Assets a fence generates that the page then shows nowhere.

    `embed` defaults to FALSE in `public/docs_hooks.py`, and the `<img>`/`<video>` is only emitted
    when it is true. So a fence declaring `screenshot="..."` without `embed=true` runs its code,
    spends the capture budget, writes the PNG -- and the reader sees nothing. That is invisible from
    either end: the asset exists on disk so the tracked-asset check above is happy, and the page
    renders without error. It shipped on two whole pages (`rectsl_inverse_animation`, `manifold`)
    plus three stragglers before anyone opened them and noticed the code had no pictures.

    Referencing the asset by hand instead -- an ordinary `![](...)` line -- is a legitimate second
    convention and is not reported.
    """
    shown = set(re.findall(r"!\[[^\]]*\]\(([^)]+)\)", text)) | set(re.findall(r'src="([^"]+)"', text))
    shown_names = {PurePosixPath(r.strip()).name for r in shown}
    orphans: list[str] = []
    for match in DIFFSTATIC_FENCE.finditer(text):
        attrs = match.group(1)
        asset = FENCE_ASSET.search(attrs)
        if asset is None or "embed=" in attrs:
            continue
        path = asset.group(1).strip()
        if path in shown or PurePosixPath(path).name in shown_names:
            continue
        orphans.append(path)
    return orphans


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--docs-root", default="public/docs", help="Docs directory (default: public/docs).")
    parser.add_argument("--config", default="public/zensical.yml", help="Zensical config for the nav (default: public/zensical.yml).")
    parser.add_argument("--all", action="store_true", help="Scan every *.md under the docs root, not just nav pages.")
    parser.add_argument("--ignore", action="append", default=[], metavar="PAGE",
                        help="Docs-root-relative page path to skip (repeatable).")
    args = parser.parse_args()

    root = repo_root()
    docs_root = (root / args.docs_root).resolve()
    if not docs_root.is_dir():
        print(f"error: docs root {docs_root} not found", file=sys.stderr)
        return 2

    tracked = {line for line in git(root, "ls-files").splitlines()}
    ignore = set(args.ignore)

    if args.all:
        pages = sorted(docs_root.rglob("*.md"))
    else:
        nav = nav_pages(root / args.config)
        pages = sorted(docs_root / p for p in nav if (docs_root / p).exists())

    def rel(p: Path) -> str | None:
        try:
            return p.relative_to(root).as_posix()
        except ValueError:
            return None

    problems: list[tuple[str, str, str]] = []
    orphans: list[tuple[str, str]] = []
    fakes: list[tuple[str, str, str]] = []
    known_fakes: list[tuple[str, str, str]] = []
    checked_pages = 0
    checked_refs = 0
    for page in pages:
        page_rel = rel(page)
        if page_rel is None or page.relative_to(docs_root).as_posix() in ignore:
            continue
        checked_pages += 1
        page_text = page.read_text(encoding="utf-8", errors="ignore")
        for asset in orphaned_capture_fences(page_text):
            orphans.append((page_rel, asset))
        for ref in extract_refs(page.read_text(encoding="utf-8", errors="ignore")):
            r = ref.strip()
            if not r or r.startswith(EXTERNAL) or not r.lower().endswith(ASSET_EXTS):
                continue
            checked_refs += 1
            candidates = resolve_candidates(page, r)
            cand_rels = [c for c in (rel(c) for c in candidates) if c is not None]
            if any(cr in tracked for cr in cand_rels):
                shipped = next((c for c in candidates if c.is_file()), None)
                if shipped is not None and looks_like_placeholder_card(shipped):
                    asset_rel = rel(shipped) or r
                    bucket = known_fakes if asset_rel in KNOWN_PLACEHOLDERS else fakes
                    bucket.append((page_rel, r, asset_rel))
                continue
            on_disk = any(c.exists() for c in candidates)
            state = "unstaged (present, not git-added)" if on_disk else "absent (never generated)"
            problems.append((page_rel, r, state))

    print(f"checked {checked_pages} page(s), {checked_refs} asset ref(s) "
          f"[{'all md' if args.all else 'nav-published only'}]")
    if known_fakes:
        print(f"\nKNOWN GAP — {len(known_fakes)} published figure(s) are still placeholder cards:\n")
        for page_rel, ref, asset_rel in known_fakes:
            print(f"  {asset_rel}  (in {page_rel})")
        print("\nThese are grandfathered in KNOWN_PLACEHOLDERS and do not fail the build. They "
              "still owe a real capture — re-run them on hardware that can produce it, then "
              "delete their entries.")

    for entry in stale_placeholder_exceptions(root):
        print(f"\nNOTE — KNOWN_PLACEHOLDERS lists {entry}, which is no longer a placeholder "
              "(or no longer exists). Delete the entry.")

    if fakes:
        print(f"\nFAIL — {len(fakes)} published figure(s) are placeholder cards, not captures:\n")
        for page_rel, ref, asset_rel in fakes:
            print(f"  {asset_rel}")
            print(f"      in:    {page_rel} (as {ref})")
            print("      state: pixels match a capture harness's \"not available\" card")
        print("\nFix: re-run the capture on hardware that can actually produce it, or drop the "
              "figure from the page. Do not commit the card — an honest gap beats a fake capture.")

    if orphans:
        print(f"\nFAIL — {len(orphans)} capture fence(s) generate an asset the page never shows:\n")
        for page_rel, asset in orphans:
            print(f"  {asset}")
            print(f"      in:    {page_rel}")
            print("      cause: the fence declares it but has no `embed=true`, and no markdown "
                  "reference shows it")
        print("\nFix: add `embed=true` to the fence, or reference the asset with an ordinary "
              "![](...) line.")

    if not problems:
        if fakes or orphans:
            return 1
        if known_fakes:
            print(f"\nOK — every referenced asset is committed and shown "
                  f"({len(known_fakes)} known placeholder gap(s) above, not gating).")
        else:
            print("OK — every referenced asset is committed and shown, and none is a placeholder.")
        return 0

    print(f"\nFAIL — {len(problems)} referenced asset(s) not tracked in git:\n")
    for page_rel, ref, state in problems:
        print(f"  {ref}")
        print(f"      in:    {page_rel}")
        print(f"      state: {state}")
    print("\nFix: `git add` the asset (if present) or regenerate it, then commit.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
