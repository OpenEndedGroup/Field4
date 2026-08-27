#!/usr/bin/env python3
"""Render the series from `tools/loc_history.py` as a self-contained HTML chart.

A stacked area of hand-written Kotlin / JavaScript / HTML / Swift / Python over time, with a
crosshair tooltip, a table view, and light and dark themes. No build step and no CDN — the
output is one file you can open or drop into a page.

Two decisions worth keeping:

* **The legend carries today's values, and only the largest band is direct-labelled.** With
  five series the thin ones cluster at the top of the stack and end-labels collide into
  illegibility. Putting each value in its legend row means no number depends on measuring a
  band's thickness — which also discharges the light-mode contrast relief the palette
  requires (three of the five hues sit below 3:1 on the light surface).
* **Kotlin is ~83% of the total, so the small bands are only a few pixels tall.** That is the
  honest shape of the data, not a rendering bug; the surface gap is 1px rather than 2px so a
  3px Swift band survives it. Read the small numbers off the legend, tooltip or table.

The palette is the validated categorical set, slots 1-5 assigned in stack order so chart
adjacency matches the order the validator cleared on the adjacent pairlist.

Usage:
    python3 tools/loc_history.py && python3 tools/loc_chart.py
    python3 tools/loc_chart.py --series build/loc/series.json --out /tmp/loc.html
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent

# Plot box inside the 960x430 viewBox.
LEFT, RIGHT, TOP, BOTTOM = 56, 838, 18, 372

# Stack order, bottom band first. Slot n must match the validated palette order.
SERIES = (
    ("kt", "Kotlin", "--s1"),
    ("js", "JavaScript", "--s2"),
    ("html", "HTML", "--s3"),
    ("swift", "Swift", "--s4"),
    ("py", "Python", "--s5"),
)

MONTHS = ("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
          "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

SHELL = r"""<div class="viz-root">
<style>
.viz-root{
  color-scheme: light;
  --surface-1:#fcfcfb;
  --text-primary:#0b0b0b; --text-secondary:#52514e; --text-muted:#898781;
  --grid:#e1e0d9; --axis:#c3c2b7; --hairline:rgba(11,11,11,0.10);
  --s1:#2a78d6; --s2:#eb6834; --s3:#1baf7a; --s4:#eda100; --s5:#e87ba4;
  font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
  color: var(--text-primary); background: var(--surface-1);
  border: 1px solid var(--hairline); border-radius: 10px;
  padding: 20px 22px 16px; max-width: 980px;
}
@media (prefers-color-scheme: dark){
  :root:where(:not([data-theme="light"])) .viz-root{ __DARK__ }
}
:root[data-theme="dark"] .viz-root{ __DARK__ }
.viz-root h2{ font-size:16px; font-weight:600; margin:0 0 2px; letter-spacing:-0.01em; }
.viz-root .sub{ font-size:12.5px; color:var(--text-secondary); margin:0 0 14px; }
.viz-root .hero{ font-size:50px; font-weight:600; line-height:1.02; letter-spacing:-0.02em; }
.viz-root .hero-cap{ font-size:12px; color:var(--text-secondary); margin-top:2px; }
.viz-root .head{ display:flex; align-items:flex-end; justify-content:space-between;
  gap:24px; flex-wrap:wrap; }
/* The left block absorbs the wrapping; the hero keeps its own line and stays right-aligned
   even if the row does wrap, so it can never end up beside the legend. */
.viz-root .head > .titles{ flex:1 1 380px; min-width:0; }
.viz-root .head > .figure{ flex:0 0 auto; margin-left:auto; text-align:right;
  white-space:nowrap; }
.viz-root .legend{ display:flex; gap:18px; align-items:center; flex-wrap:wrap; margin:2px 0 8px; }
.viz-root .lg{ display:flex; align-items:baseline; gap:6px; font-size:12.5px;
  color:var(--text-secondary); }
.viz-root .lg b{ color:var(--text-primary); font-weight:600;
  font-variant-numeric:tabular-nums; }
.viz-root .sw{ width:11px; height:11px; border-radius:2px; display:inline-block;
  position:relative; top:1px; flex:none; }
.viz-root svg{ display:block; width:100%; height:auto; overflow:visible; }
.viz-root .tick{ font-size:11px; fill:var(--text-muted); }
.viz-root .endlab{ font-size:11.5px; fill:var(--text-secondary); }
.viz-root .endval{ font-size:11.5px; font-weight:600; fill:var(--text-primary); }
.viz-root .foot{ font-size:11.5px; color:var(--text-muted); line-height:1.55; margin-top:12px;
  border-top:1px solid var(--hairline); padding-top:10px; }
.viz-root .foot b{ color:var(--text-secondary); font-weight:600; }
.viz-root .wrap{ position:relative; }
.viz-root .tip{ position:absolute; pointer-events:none; opacity:0; transition:opacity .08s;
  background:var(--surface-1); border:1px solid var(--hairline); border-radius:8px;
  padding:8px 10px; font-size:12px; box-shadow:0 4px 14px rgba(0,0,0,.13);
  min-width:168px; z-index:5; }
.viz-root .tip .d{ color:var(--text-secondary); font-size:11px; margin-bottom:5px; }
.viz-root .tip .r{ display:flex; align-items:center; gap:7px; margin:2px 0; }
.viz-root .tip .k{ width:12px; height:2px; border-radius:1px; flex:none; }
.viz-root .tip .v{ font-weight:600; font-variant-numeric:tabular-nums; margin-left:auto; }
.viz-root .tip .n{ color:var(--text-secondary); }
.viz-root .tip .tot{ border-top:1px solid var(--hairline); margin-top:5px; padding-top:5px;
  display:flex; gap:10px; font-weight:600; }
.viz-root details{ margin-top:10px; font-size:12px; color:var(--text-secondary); }
.viz-root summary{ cursor:pointer; }
.viz-root table{ border-collapse:collapse; margin-top:8px; font-size:11.5px;
  font-variant-numeric:tabular-nums; }
.viz-root th,.viz-root td{ text-align:right; padding:3px 12px 3px 0;
  border-bottom:1px solid var(--hairline); }
.viz-root th:first-child,.viz-root td:first-child{ text-align:left; }
.viz-root th{ color:var(--text-muted); font-weight:500; }
</style>

<div class="head">
  <div class="titles">
    <h2>Field4 &mdash; hand-written lines of code</h2>
    <p class="sub">Kotlin, JavaScript, HTML, Swift and Python across
       <span style="white-space:nowrap">OpenEndedGroup/Field4</span> and its
       <span style="white-space:nowrap">field-audio-ai</span> submodule
       &middot; __RANGE__</p>
  </div>
  <div class="figure">
    <div class="hero">__TOTAL__</div>
    <div class="hero-cap">lines today, from zero in __FIRSTMONTH__</div>
  </div>
</div>

<div class="legend">__LEGEND__</div>

<div class="wrap">
  <svg viewBox="0 0 960 430" role="img" aria-label="__ALT__">
    __SVG__
  </svg>
  <div class="tip" id="tip"></div>
</div>

<details>
  <summary>Table view &mdash; month ends</summary>
  __TABLE__
</details>

<p class="foot">__FOOT__</p>
</div>

<script>
(function(){
const DATA = __DATA__;
const S = __SERIESJS__;
const L=__L__, R=__R__, T=__T__, B=__B__, YMAX=__YMAX__;
const root = document.currentScript.previousElementSibling;
const svg = root.querySelector('svg');
const tip = root.querySelector('#tip');
const hit = root.querySelector('#hit');
const cross = root.querySelector('#cross');
const dots = S.map((_,i)=>root.querySelector('#dot'+i));
const n = DATA.length;
const fmt = v => v.toLocaleString('en-US');
const css = k => getComputedStyle(root).getPropertyValue(k).trim();
const px = i => L + (R-L)*i/(n-1);
const py = v => B - (B-T)*v/YMAX;

function show(e){
  const r = svg.getBoundingClientRect();
  const x = (e.clientX - r.left) / r.width * 960;
  const i = Math.max(0, Math.min(n-1, Math.round((x - L)/(R-L)*(n-1))));
  const d = DATA[i], vx = px(i);
  cross.setAttribute('x1', vx); cross.setAttribute('x2', vx);
  cross.style.opacity = 1;
  let acc = 0;
  for (let k=0;k<S.length;k++){
    acc += d[k+1];
    dots[k].setAttribute('cx', vx); dots[k].setAttribute('cy', py(acc));
    dots[k].style.opacity = 1;
  }
  const total = acc;
  tip.textContent = '';
  const head = document.createElement('div');
  head.className = 'd';
  head.textContent = new Date(d[0]+'T00:00:00').toLocaleDateString('en-US',
      {year:'numeric', month:'short', day:'numeric'});
  tip.appendChild(head);
  S.forEach((s,k)=>{
    const row = document.createElement('div'); row.className = 'r';
    const key = document.createElement('i'); key.className = 'k';
    key.style.background = css(s.v); row.appendChild(key);
    // Series names are data — textContent, never innerHTML.
    const name = document.createElement('span'); name.className = 'n';
    name.textContent = s.name; row.appendChild(name);
    const val = document.createElement('span'); val.className = 'v';
    val.textContent = fmt(d[k+1]); row.appendChild(val);
    tip.appendChild(row);
  });
  const foot = document.createElement('div'); foot.className = 'tot';
  const lab = document.createElement('span'); lab.textContent = 'Total';
  const num = document.createElement('span');
  num.style.marginLeft = 'auto'; num.style.fontVariantNumeric = 'tabular-nums';
  num.textContent = fmt(total);
  foot.appendChild(lab); foot.appendChild(num); tip.appendChild(foot);

  tip.style.opacity = 1;
  const relX = vx / 960 * r.width;
  tip.style.left = Math.min(r.width - tip.offsetWidth - 4, Math.max(0, relX + 14)) + 'px';
  tip.style.top  = Math.max(0, (py(total)/430*r.height) - tip.offsetHeight - 10) + 'px';
}
function hide(){
  tip.style.opacity = 0; cross.style.opacity = 0;
  dots.forEach(d => d.style.opacity = 0);
}
hit.addEventListener('pointermove', show);
hit.addEventListener('pointerleave', hide);
})();
</script>
"""

DARK_VARS = (
    "color-scheme: dark; --surface-1:#1a1a19; --text-primary:#ffffff; "
    "--text-secondary:#c3c2b7; --text-muted:#898781; --grid:#2c2c2a; --axis:#383835; "
    "--hairline:rgba(255,255,255,0.10); --s1:#3987e5; --s2:#d95926; --s3:#199e70; "
    "--s4:#c98500; --s5:#d55181;"
)

FOOT = (
    "<b>Counted:</b> every <code>.kt</code>, <code>.js</code>, <code>.html</code>, "
    "<code>.swift</code> and <code>.py</code> file, all lines including blanks and comments. "
    "Swift is the visionOS head and its encoder (<code>native/field-visionos</code>, "
    "<code>native/field-encode</code>); Python is Field4's <code>tools/</code> plus the "
    "<code>field-audio-ai</code> service. "
    "<b>Excluded as vendored</b> ({vendored:,} lines today): the Monaco editor, the three.js "
    "editor and its <code>examples/</code> trees, A-Frame, super-three, CodeMirror and its "
    "modes, acorn/tern, tess2, and every <code>.min.js</code>. "
    "<b>Excluded as generated</b> ({generated:,} lines): <code>src/generatedLinalg/</code> and "
    "the docs gallery / agent-transcript snippet dumps under <code>public/docs/assets/</code>, "
    "plus saved <code>templates/</code> sheets. "
    "<b>Method:</b> one point per day, taken from the largest tree committed that day, so the "
    "line follows the leading edge where branches ran in parallel. The submodule is counted "
    "through the gitlink Field4 pins, so it appears the day Field4 adopted it. The visionOS "
    "app's original commit root is left out (its Swift is counted from the main line). "
    "Kotlin is ~83% of the total, so the small bands are only a few pixels tall &mdash; read "
    "their values from the legend, the tooltip or the table. "
    "Generated by <code>tools/loc_chart.py</code>."
)


def _axis_scale(peak: int) -> tuple[int, int]:
    """(top, step) for the y-axis: a 1/2/5-series step, at most six gridlines.

    Deriving the step as `top // 5` instead gives ticks like 160k once the data outgrows
    500k — round numbers are the whole point of a gridline.
    """
    # decade outer, mult inner, so candidate steps ascend and the smallest wins.
    for decade in (10_000, 100_000, 1_000_000):
        for mult in (1, 2, 2.5, 5):
            step = int(mult * decade)
            if -(-peak // step) <= 6:
                return -(-peak // step) * step, step
    raise ValueError(f"no sensible axis for peak {peak}")


def render(series: list[dict]) -> str:
    n = len(series)
    peak = max(sum(r["ours"].values()) for r in series)
    ymax, step = _axis_scale(peak)

    px = lambda i: LEFT + (RIGHT - LEFT) * i / (n - 1)
    py = lambda v: BOTTOM - (BOTTOM - TOP) * v / ymax

    # Cumulative band tops, in stacking order.
    cum = []
    for row in series:
        running, tops = 0, []
        for key, _, _ in SERIES:
            running += row["ours"][key]
            tops.append(running)
        cum.append(tops)

    out: list[str] = []

    for v in range(0, ymax + 1, step):
        y = py(v)
        out.append(f'<line x1="{LEFT}" x2="{RIGHT}" y1="{y:.1f}" y2="{y:.1f}" '
                   f'stroke="var(--grid)" stroke-width="1"/>')
        label = f"{v // 1000:,}k" if v else "0"
        out.append(f'<text class="tick" x="{LEFT - 9}" y="{y + 3.8:.1f}" '
                   f'text-anchor="end">{label}</text>')
    out.append(f'<line x1="{LEFT}" x2="{RIGHT}" y1="{BOTTOM}" y2="{BOTTOM}" '
               f'stroke="var(--axis)" stroke-width="1"/>')

    seen = set()
    for i, row in enumerate(series):
        month = row["date"][:7]
        if month in seen:
            continue
        seen.add(month)
        x = px(i)
        label = MONTHS[int(month[5:7])]
        if month[5:7] == "01":
            label = f"Jan {month[2:4]}"
        out.append(f'<line x1="{x:.1f}" x2="{x:.1f}" y1="{BOTTOM}" y2="{BOTTOM + 4}" '
                   f'stroke="var(--axis)" stroke-width="1"/>')
        out.append(f'<text class="tick" x="{x:.1f}" y="{BOTTOM + 17}" '
                   f'text-anchor="middle">{label}</text>')

    # Paint the top band first and lower bands over it, so each polygon can run to the
    # baseline. Fills are opaque: at any alpha the bands beneath would tint the ones above.
    for band in reversed(range(len(SERIES))):
        pts = " ".join(f"{px(i):.2f},{py(cum[i][band]):.2f}" for i in range(n))
        out.append(f'<polygon points="{px(0):.2f},{BOTTOM} {pts} {px(n-1):.2f},{BOTTOM}" '
                   f'fill="var({SERIES[band][2]})"/>')
    # Surface gap on each internal boundary — white does the separating, not a stroke. 1px
    # rather than the usual 2px: the thinnest band here is ~3px tall and 2px would erase it.
    for band in range(len(SERIES) - 1):
        pts = " ".join(f"{px(i):.2f},{py(cum[i][band]):.2f}" for i in range(n))
        out.append(f'<polyline points="{pts}" fill="none" stroke="var(--surface-1)" '
                   f'stroke-width="1" stroke-linejoin="round"/>')

    # Direct-label the largest band only; every other value rides the legend. With five
    # series, end-labels for all of them collide into a stack of leader lines.
    last = series[-1]
    biggest = max(range(len(SERIES)), key=lambda k: last["ours"][SERIES[k][0]])
    low = cum[-1][biggest - 1] if biggest else 0
    mid, name = (low + cum[-1][biggest]) / 2, SERIES[biggest][1]
    y = py(mid)
    out.append(f'<line x1="{RIGHT}" x2="{RIGHT + 8}" y1="{y:.1f}" y2="{y:.1f}" '
               f'stroke="var(--axis)" stroke-width="1"/>')
    out.append(f'<text class="endval" x="{RIGHT + 12}" y="{y - 1:.1f}">'
               f'{last["ours"][SERIES[biggest][0]]:,}</text>')
    out.append(f'<text class="endlab" x="{RIGHT + 12}" y="{y + 11:.1f}">{name}</text>')

    out.append(f'<line id="cross" x1="0" x2="0" y1="{TOP}" y2="{BOTTOM}" '
               f'stroke="var(--axis)" stroke-width="1" style="opacity:0" pointer-events="none"/>')
    for k, (_, _, var) in enumerate(SERIES):
        out.append(f'<circle id="dot{k}" r="4.5" fill="var({var})" stroke="var(--surface-1)" '
                   f'stroke-width="2" style="opacity:0" pointer-events="none"/>')
    out.append(f'<rect id="hit" x="{LEFT}" y="{TOP}" width="{RIGHT - LEFT}" '
               f'height="{BOTTOM - TOP}" fill="transparent" style="cursor:crosshair"/>')

    # The legend carries today's value for every series — see the module docstring.
    legend = "".join(
        f'<span class="lg"><i class="sw" style="background:var({var})"></i>'
        f'{name} <b>{last["ours"][key]:,}</b></span>'
        for key, name, var in SERIES
    )

    month_end = {row["date"][:7]: row for row in series}
    head_cells = "".join(f"<th>{name}</th>" for _, name, _ in SERIES)
    rows = "".join(
        f'<tr><td>{r["date"]}</td>'
        + "".join(f'<td>{r["ours"][key]:,}</td>' for key, _, _ in SERIES)
        + f'<td>{sum(r["ours"].values()):,}</td></tr>'
        for r in month_end.values()
    )
    table = (f"<table><thead><tr><th>As of</th>{head_cells}<th>Total</th></tr></thead>"
             f"<tbody>{rows}</tbody></table>")

    total = sum(last["ours"].values())
    first_month = f"{MONTHS[int(series[0]['date'][5:7])]} {series[0]['date'][:4]}"
    last_month = f"{MONTHS[int(last['date'][5:7])]} {last['date'][:4]}"

    data = json.dumps([[r["date"], *(r["ours"][key] for key, _, _ in SERIES)]
                       for r in series], separators=(",", ":"))
    series_js = json.dumps([{"name": name, "v": var} for _, name, var in SERIES],
                           separators=(",", ":"))

    return (SHELL
            .replace("__DARK__", DARK_VARS)
            .replace("__SVG__", "\n    ".join(out))
            .replace("__LEGEND__", legend)
            .replace("__TABLE__", table)
            .replace("__TOTAL__", f"{total:,}")
            .replace("__RANGE__", f"{first_month} &ndash; {last_month}")
            .replace("__FIRSTMONTH__", first_month.split()[0])
            .replace("__ALT__", f"Stacked area chart of hand-written lines of code in Field4, "
                                f"growing from zero in {first_month} to {total:,} in {last_month}")
            .replace("__FOOT__", FOOT.format(vendored=last["vendored"],
                                             generated=last["generated"]))
            .replace("__DATA__", data)
            .replace("__SERIESJS__", series_js)
            .replace("__L__", str(LEFT)).replace("__R__", str(RIGHT))
            .replace("__T__", str(TOP)).replace("__B__", str(BOTTOM))
            .replace("__YMAX__", str(ymax)))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--series", type=Path, default=REPO_ROOT / "build/loc/series.json")
    ap.add_argument("--out", type=Path, default=REPO_ROOT / "build/loc/loc.html")
    ap.add_argument("--fragment", action="store_true",
                    help="emit just the widget, without the surrounding page")
    args = ap.parse_args()

    if not args.series.exists():
        print(f"{args.series} not found — run tools/loc_history.py first", file=sys.stderr)
        return 1

    fragment = render(json.loads(args.series.read_text()))
    page = fragment if args.fragment else (
        "<!doctype html><meta charset=utf-8><title>Field4 lines of code</title>"
        "<style>body{margin:0;padding:28px;background:#f9f9f7}"
        "@media(prefers-color-scheme:dark){body{background:#0d0d0d}}</style>"
        f"<body>{fragment}</body>"
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(page)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
