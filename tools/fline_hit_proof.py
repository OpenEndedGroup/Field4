#!/usr/bin/env python3
"""Click a real interactive FLine figure in a real browser, and look at what comes back.

`FLineHitRecordProbeTest` runs the shipped `sliders.js` under node, which covers the
reconstruction but deliberately not *browser hit-testing*: which `<g>` a click lands in is
Chromium's decision, not the JavaScript's. This script closes that gap and produces the pixels.

It loads the SVG that `afield.examples.graphics.FLineHitInspectorFigure` emitted — the real
interactive output, not a mock-up — next to the verbatim `src/commonMain/resources/sliders.js`,
clicks named points with the real mouse, reads the payloads `window.sendFLineClick` posted, checks
them against what the figure's own geometry says they must be, and writes two screenshots: the
figure with a marker drawn at every point the browser reported, and the figure mid-hover.

    python3 tools/fline_hit_proof.py --svg /tmp/panel_wall.svg --out dev_docs/agents/artifacts/fline_hit_records

Generate the SVG first, against a running Field:

    python3 tools/field_backdoor.py --command \\
      "run-jvm-main afield.examples.graphics.FLineHitInspectorFigure --arg /tmp/panel_wall.svg"

Exit code 0 means every click came back naming the element the geometry says it should.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SLIDERS = REPO / "src/commonMain/resources/sliders.js"

# The figure's own projection, from FLineHitInspectorFigure.onWall — u along the wall, v up it.
WIDTH, HEIGHT = 560.0, 380.0
COLUMNS, ROWS = 5, 3


def on_wall(u: float, v: float) -> tuple[float, float, float]:
    depth = u * 220.0
    return 40.0 + u * 430.0 + v * 22.0, 330.0 - v * 250.0 - depth * 0.20, depth


def panel_centre(row: int, column: int) -> tuple[float, float]:
    return (column + 0.5) / COLUMNS, (row + 0.5) / ROWS


def cases() -> list[dict]:
    """Named clicks, each with what the figure's geometry says the answer has to be."""
    out: list[dict] = []
    for row, column in ((1, 2), (0, 4), (2, 0)):
        u, v = panel_centre(row, column)
        x, y, _ = on_wall(u, v)
        out.append({
            "name": f"centre of panel {row},{column}",
            "x": x, "y": y,
            "expect": {"kind": "face", "label": f"panel {row},{column}",
                       "lineIndex": row * COLUMNS + column, "u": 0.5, "v": 0.5},
            "tol": 0.02,
        })
    # 30% along the bottom edge of a panel: an edge hit with a local coordinate.
    row, column = 2, 3
    u0, u1 = column / COLUMNS, (column + 1) / COLUMNS
    v0 = row / ROWS
    ax, ay, _ = on_wall(u0, v0)
    bx, by, _ = on_wall(u1, v0)
    out.append({
        "name": f"30% along the lower edge of panel {row},{column}",
        "x": ax + 0.30 * (bx - ax), "y": ay + 0.30 * (by - ay),
        "expect": {"kind": "edge", "label": f"panel {row},{column}", "elementIndex": 0, "u": 0.30},
        "tol": 0.03,
    })
    # A shared corner of four panels: whichever <g> the browser gives the click to, it is a corner.
    cx, cy, _ = on_wall(2 / COLUMNS, 1 / ROWS)
    out.append({
        "name": "a corner shared by four panels",
        "x": cx, "y": cy,
        "expect": {"kind": "point"},
        "tol": 0.02,
    })
    # The middle of the ridge line — a thin stroke, so this also tests that a stroke is clickable.
    rx, ry, _ = on_wall(0.5, 1.0)
    out.append({
        "name": "the middle of the ridge",
        "x": rx, "y": ry,
        "expect": {"kind": "edge", "label": "ridge", "u": 0.5},
        "tol": 0.03,
    })
    return out


PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><style>
  html, body {{ margin: 0; background: #0d1116; }}
  #frame {{ padding: 24px; width: {width}px; }}
  svg {{ display: block; }}
</style></head>
<body>
<script>
  // sliders.js posts through the `afield` module; stand in for it so the payloads stay here.
  window.__posted = [];
  window.afield = {{ send: (type, payload) => window.__posted.push({{ type, payload }}) }};
</script>
<div id="frame">{svg}</div>
<script src="sliders.js"></script>
</body></html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--svg", required=True, help="the emitted interactive SVG")
    parser.add_argument("--out", required=True, help="directory for the screenshots")
    parser.add_argument("--scale", type=float, default=2.0, help="device scale factor")
    args = parser.parse_args()

    from playwright.sync_api import sync_playwright

    svg_text = pathlib.Path(args.svg).read_text()
    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    work = out_dir / ".work"
    work.mkdir(exist_ok=True)
    (work / "sliders.js").write_text(SLIDERS.read_text())
    page_path = work / "figure.html"
    page_path.write_text(PAGE.format(svg=svg_text, width=int(WIDTH)))

    failures: list[str] = []
    reported: list[dict] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": int(WIDTH) + 48, "height": int(HEIGHT) + 48},
                                device_scale_factor=args.scale)
        page.goto(page_path.as_uri())
        box = page.evaluate("() => { const r = document.querySelector('svg').getBoundingClientRect();"
                            " return { x: r.x, y: r.y, w: r.width, h: r.height }; }")
        if abs(box["w"] - WIDTH) > 1:
            failures.append(f"the SVG rendered {box['w']}px wide, expected {WIDTH}")

        for case in cases():
            page.evaluate("() => { window.__posted.length = 0; }")
            page.mouse.click(box["x"] + case["x"] * box["w"] / WIDTH,
                             box["y"] + case["y"] * box["h"] / HEIGHT)
            posted = page.evaluate("() => window.__posted")
            if len(posted) != 1:
                failures.append(f"{case['name']}: the browser posted {len(posted)} messages, expected 1")
                continue
            payload = posted[0]["payload"]
            reported.append({"name": case["name"], "payload": payload})
            for key, want in case["expect"].items():
                got = payload.get(key)
                if isinstance(want, float):
                    if got is None or abs(got - want) > case["tol"]:
                        failures.append(f"{case['name']}: {key} = {got}, expected {want} +- {case['tol']}")
                elif got != want:
                    failures.append(f"{case['name']}: {key} = {got!r}, expected {want!r}")
            print(f"  {case['name']}: kind={payload['kind']} label={payload['label']!r} "
                  f"line={payload['lineIndex']} element={payload['elementIndex']} "
                  f"u={fmt(payload['u'])} v={fmt(payload['v'])} "
                  f"on=({fmt(payload['ex'])}, {fmt(payload['ey'])}, {fmt(payload['ez'])})")

        # Markers where the browser said each click landed, drawn from the reported numbers only.
        page.evaluate(
            """(hits) => {
                const svg = document.querySelector('svg');
                const ns = 'http://www.w3.org/2000/svg';
                const colour = { point: '#ffd878', edge: '#78d6ff', face: '#bea0ff', '': '#ffffff' };
                for (const hit of hits) {
                    const p = hit.payload;
                    if (p.ex === null || p.ey === null) continue;
                    const ring = document.createElementNS(ns, 'circle');
                    ring.setAttribute('cx', p.ex);
                    ring.setAttribute('cy', p.ey);
                    ring.setAttribute('r', 7);
                    ring.setAttribute('fill', 'none');
                    ring.setAttribute('stroke', colour[p.kind] || '#fff');
                    ring.setAttribute('stroke-width', 2);
                    svg.appendChild(ring);
                    const dot = document.createElementNS(ns, 'circle');
                    dot.setAttribute('cx', p.ex);
                    dot.setAttribute('cy', p.ey);
                    dot.setAttribute('r', 1.6);
                    dot.setAttribute('fill', colour[p.kind] || '#fff');
                    svg.appendChild(dot);
                }
            }""",
            reported,
        )
        page.screenshot(path=str(out_dir / "panel_wall_hits.png"))

        # And one frame mid-hover, which is the other half of the interaction nobody had looked at.
        hover = panel_centre(1, 1)
        hx, hy, _ = on_wall(*hover)
        page.mouse.move(box["x"] + hx * box["w"] / WIDTH, box["y"] + hy * box["h"] / HEIGHT)
        page.wait_for_timeout(120)
        page.screenshot(path=str(out_dir / "panel_wall_hover.png"))
        browser.close()

    (out_dir / "reported_hits.json").write_text(json.dumps(reported, indent=1) + "\n")
    print(f"wrote {out_dir/'panel_wall_hits.png'}, {out_dir/'panel_wall_hover.png'}, "
          f"{out_dir/'reported_hits.json'}")

    if failures:
        print(f"FLine hit proof FAILED with {len(failures)} problem(s):")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print(f"FLine hit proof passed ({len(reported)} clicks in a real browser)")
    return 0


def fmt(value) -> str:
    return "-" if value is None else f"{value:.3f}"


if __name__ == "__main__":
    sys.exit(main())
