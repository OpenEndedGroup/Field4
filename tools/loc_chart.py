#!/usr/bin/env python3
"""Render the series from `tools/loc_history.py` as a self-contained HTML chart.

A stacked area of hand-written code by language over time, topped by the box code in starting
points and lessons, and beneath it a second chart of the docs site's hand-written pages. Both
have a crosshair tooltip; there is a table view and light and dark themes. No build step and no
CDN — the output is one file you can open or drop into a page.

Decisions worth keeping:

* **The legend carries today's values, and only the largest band is direct-labelled.** With
  nine bands the thin ones cluster at the top of the stack and end-labels collide into
  illegibility. Putting each value in its legend row means no number depends on measuring a
  band's thickness — which also discharges the contrast relief the palette requires (three
  light hues and both neutrals sit below 3:1 on their surface).
* **Kotlin is ~80% of the total, so the small bands are only a few pixels tall.** That is the
  honest shape of the data, not a rendering bug; the surface gap is 1px rather than 2px so a
  3px band survives it. Read the small numbers off the legend, tooltip or table.
* **Eight hues, then fold.** The validated categorical set has eight slots, assigned in stack
  order so chart adjacency matches the order the validator cleared on the adjacent pairlist.
  Go and shaders share slot 8 as "Other" rather than inventing a ninth hue.
* **Starting points and lessons are a neutral band, not a ninth hue.** They are a different
  kind of thing from a language, so the band takes no categorical identity. Its greys were
  stepped against slot 8's red with the palette validator (worst CVD delta E 11.3 light,
  10.7 dark).
* **Docs are their own chart.** Prose lines do not stack with code, and a second y-scale on
  the code chart would be a dual axis; the docs chart shares only the time axis. It is one
  line in secondary ink, since it is a single series and claims none of the code hues.

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

# Plot boxes: the code chart inside a 960x430 viewBox, the docs chart inside 960x190.
LEFT, RIGHT, TOP, BOTTOM = 56, 838, 18, 372
DOCS_HEIGHT, DOCS_TOP, DOCS_BOTTOM = 190, 14, 152

# Stack order, bottom band first: (name, CSS var, languages from `ours` it sums, or None for
# the starting points and lessons). Slot n must match the validated palette order.
SERIES = (
    ("Kotlin", "--s1", ("kt",)),
    ("JavaScript", "--s2", ("js",)),
    ("HTML", "--s3", ("html",)),
    ("Swift", "--s4", ("swift",)),
    ("Python", "--s5", ("py",)),
    ("Rust", "--s6", ("rs",)),
    ("C/C++/ObjC", "--s7", ("cpp",)),
    ("Other (Go, shaders)", "--s8", ("go", "shader")),
    ("Starting points & lessons", "--examples", None),
)


def band_values(row: dict) -> list[int]:
    """One value per `SERIES` band. Series written before a band existed read it as 0."""
    ours, examples = row["ours"], row.get("examples", {})
    return [sum(examples.values()) if langs is None else sum(ours.get(k, 0) for k in langs)
            for _, _, langs in SERIES]

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
  --s6:#008300; --s7:#4a3aa7; --s8:#e34948; --examples:#a3a29b;
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
.viz-root h3{ font-size:14px; font-weight:600; margin:22px 0 2px; }
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
    <p class="sub">Every language across
       <span style="white-space:nowrap">OpenEndedGroup/Field4</span> and its
       <span style="white-space:nowrap">field-audio-ai</span> submodule, with the box code
       in its starting points and lessons on top &middot; __RANGE__</p>
  </div>
  <div class="figure">
    <div class="hero">__TOTAL__</div>
    <div class="hero-cap">lines today, from zero in __FIRSTMONTH__</div>
  </div>
</div>

<div class="legend">__LEGEND__</div>

<div class="wrap" data-chart="code">
  <svg viewBox="0 0 960 430" role="img" aria-label="__ALT__">
    __SVG__
  </svg>
  <div class="tip"></div>
</div>

<div class="head">
  <div class="titles">
    <h3>Documentation &mdash; hand-written pages</h3>
    <p class="sub">The <code>.md</code> pages the docs site's contents links to, less the
       reference pages generated from KDoc and other generators</p>
  </div>
  <div class="figure">
    <div class="hero" style="font-size:30px">__DOCSTOTAL__</div>
    <div class="hero-cap">lines across __DOCSPAGES__ pages</div>
  </div>
</div>

<div class="wrap" data-chart="docs">
  <svg viewBox="0 0 960 __DOCSH__" role="img" aria-label="__DOCSALT__">
    __DOCSSVG__
  </svg>
  <div class="tip"></div>
</div>

<details>
  <summary>Table view &mdash; month ends</summary>
  __TABLE__
</details>

<p class="foot">__FOOT__</p>
</div>

<script>
(function(){
const root = document.currentScript.previousElementSibling;
const L=__L__, R=__R__;
const fmt = v => v.toLocaleString('en-US');
const css = k => getComputedStyle(root).getPropertyValue(k).trim();

// rows are [date, v1..vn]; bands are {name, v} drawn stacked when `stacked`, else as lines.
// `extra(row)` returns [label, value] rows shown under the bands, `total` adds a Total row.
function wire(name, rows, bands, opts){
  const wrap = root.querySelector('[data-chart="' + name + '"]');
  const svg = wrap.querySelector('svg'), tip = wrap.querySelector('.tip');
  const hit = svg.querySelector('.hit'), cross = svg.querySelector('.cross');
  const dots = [...svg.querySelectorAll('.dot')];
  const n = rows.length, H = opts.height;
  const px = i => L + (R-L)*i/(n-1);
  const py = v => opts.bottom - (opts.bottom-opts.top)*v/opts.ymax;

  function row(label, value, colour){
    const r = document.createElement('div'); r.className = 'r';
    if (colour){
      const key = document.createElement('i'); key.className = 'k';
      key.style.background = colour; r.appendChild(key);
    }
    // Series names are data — textContent, never innerHTML.
    const nm = document.createElement('span'); nm.className = 'n';
    nm.textContent = label; r.appendChild(nm);
    const val = document.createElement('span'); val.className = 'v';
    val.textContent = fmt(value); r.appendChild(val);
    return r;
  }
  function show(e){
    const r = svg.getBoundingClientRect();
    const x = (e.clientX - r.left) / r.width * 960;
    const i = Math.max(0, Math.min(n-1, Math.round((x - L)/(R-L)*(n-1))));
    const d = rows[i], vx = px(i);
    cross.setAttribute('x1', vx); cross.setAttribute('x2', vx);
    cross.style.opacity = 1;
    let acc = 0, top = 0;
    bands.forEach((b,k)=>{
      acc = opts.stacked ? acc + d[k+1] : d[k+1];
      top = Math.max(top, acc);
      dots[k].setAttribute('cx', vx); dots[k].setAttribute('cy', py(acc));
      dots[k].style.opacity = 1;
    });
    tip.textContent = '';
    const head = document.createElement('div');
    head.className = 'd';
    head.textContent = new Date(d[0]+'T00:00:00').toLocaleDateString('en-US',
        {year:'numeric', month:'short', day:'numeric'});
    tip.appendChild(head);
    bands.forEach((b,k)=> tip.appendChild(row(b.name, d[k+1], css(b.v))));
    (opts.extra ? opts.extra(d) : []).forEach(([l, v]) => tip.appendChild(row(l, v)));
    if (opts.stacked){
      const foot = document.createElement('div'); foot.className = 'tot';
      const lab = document.createElement('span'); lab.textContent = 'Total';
      const num = document.createElement('span');
      num.style.marginLeft = 'auto'; num.style.fontVariantNumeric = 'tabular-nums';
      num.textContent = fmt(acc);
      foot.appendChild(lab); foot.appendChild(num); tip.appendChild(foot);
    }
    tip.style.opacity = 1;
    const relX = vx / 960 * r.width;
    tip.style.left = Math.min(r.width - tip.offsetWidth - 4, Math.max(0, relX + 14)) + 'px';
    tip.style.top  = Math.max(0, (py(top)/H*r.height) - tip.offsetHeight - 10) + 'px';
  }
  function hide(){
    tip.style.opacity = 0; cross.style.opacity = 0;
    dots.forEach(d => d.style.opacity = 0);
  }
  hit.addEventListener('pointermove', show);
  hit.addEventListener('pointerleave', hide);
}

wire('code', __DATA__, __SERIESJS__,
     {stacked: true, height: 430, top: __T__, bottom: __B__, ymax: __YMAX__});
wire('docs', __DOCSDATA__, [{name: 'Lines', v: '--text-secondary'}],
     {stacked: false, height: __DOCSH__, top: __DT__, bottom: __DB__, ymax: __DOCSYMAX__,
      extra: d => [['Pages', d[2]]]});
})();
</script>
"""

DARK_VARS = (
    "color-scheme: dark; --surface-1:#1a1a19; --text-primary:#ffffff; "
    "--text-secondary:#c3c2b7; --text-muted:#898781; --grid:#2c2c2a; --axis:#383835; "
    "--hairline:rgba(255,255,255,0.10); --s1:#3987e5; --s2:#d95926; --s3:#199e70; "
    "--s4:#c98500; --s5:#d55181; --s6:#008300; --s7:#9085e9; --s8:#e66767; "
    "--examples:#62615c;"
)

FOOT = (
    "<b>Counted:</b> Kotlin, JavaScript, HTML, Swift, Python, Rust, C/C++/Objective-C, Go "
    "and shader source (GLSL, Slang, Metal, HLSL), all lines including blanks and comments. "
    "Swift is the visionOS head and its encoder; Rust is the paint engine's Field-side code "
    "(<code>native/field-paint/src</code> and the <code>src/field/</code> it adds to the "
    "forked crate); C/C++ is the native helpers under <code>native/</code>. Python is "
    "Field4's <code>tools/</code> plus the <code>field-audio-ai</code> service. "
    "<b>Starting points &amp; lessons</b> is the box code saved in documents &mdash; the "
    "welcome page's starting points, the graphics lessons, the agent and docs demo "
    "documents &mdash; with identical copies counted once. "
    "<b>Excluded as vendored</b> ({vendored:,} lines today): the Monaco editor, the three.js "
    "editor and its <code>examples/</code> trees, A-Frame, super-three, CodeMirror and its "
    "modes, acorn/tern, tess2, every <code>.min.js</code>, the forked claude-paint crate, "
    "FFmpeg's NVENC headers and Steam Audio's C API. "
    "<b>Excluded as generated</b> ({generated:,} lines): <code>src/generatedLinalg/</code>, "
    "the RectSL/StackSL gallery wrappers, and saved <code>templates/</code> sheets. "
    "<b>Docs</b> are the contents pages' Markdown, skipping any page whose first line says a "
    "generator wrote it. "
    "<b>Method:</b> one point per day, taken from the largest tree committed that day, so the "
    "line follows the leading edge where branches ran in parallel. The submodule is counted "
    "through the gitlink Field4 pins, so it appears the day Field4 adopted it. The visionOS "
    "app's original commit root is left out (its Swift is counted from the main line). "
    "Kotlin is ~80% of the total, so the small bands are only a few pixels tall &mdash; read "
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


def _frame(n: int, ymax: int, step: int, top: int, bottom: int, dates: list[str]) -> list[str]:
    """Gridlines, y labels, baseline and month ticks for one plot box."""
    px = lambda i: LEFT + (RIGHT - LEFT) * i / (n - 1)
    py = lambda v: bottom - (bottom - top) * v / ymax
    out: list[str] = []
    for v in range(0, ymax + 1, step):
        y = py(v)
        out.append(f'<line x1="{LEFT}" x2="{RIGHT}" y1="{y:.1f}" y2="{y:.1f}" '
                   f'stroke="var(--grid)" stroke-width="1"/>')
        label = f"{v // 1000:,}k" if v else "0"
        out.append(f'<text class="tick" x="{LEFT - 9}" y="{y + 3.8:.1f}" '
                   f'text-anchor="end">{label}</text>')
    out.append(f'<line x1="{LEFT}" x2="{RIGHT}" y1="{bottom}" y2="{bottom}" '
               f'stroke="var(--axis)" stroke-width="1"/>')

    seen = set()
    for i, day in enumerate(dates):
        month = day[:7]
        if month in seen:
            continue
        seen.add(month)
        x = px(i)
        label = MONTHS[int(month[5:7])]
        if month[5:7] == "01":
            label = f"Jan {month[2:4]}"
        out.append(f'<line x1="{x:.1f}" x2="{x:.1f}" y1="{bottom}" y2="{bottom + 4}" '
                   f'stroke="var(--axis)" stroke-width="1"/>')
        out.append(f'<text class="tick" x="{x:.1f}" y="{bottom + 17}" '
                   f'text-anchor="middle">{label}</text>')
    return out


def _hover(vars_: list[str], top: int, bottom: int) -> list[str]:
    """Crosshair, one dot per series, and the hit rect the script listens on."""
    out = [f'<line class="cross" x1="0" x2="0" y1="{top}" y2="{bottom}" '
           f'stroke="var(--axis)" stroke-width="1" style="opacity:0" pointer-events="none"/>']
    for var in vars_:
        out.append(f'<circle class="dot" r="4.5" fill="var({var})" stroke="var(--surface-1)" '
                   f'stroke-width="2" style="opacity:0" pointer-events="none"/>')
    out.append(f'<rect class="hit" x="{LEFT}" y="{top}" width="{RIGHT - LEFT}" '
               f'height="{bottom - top}" fill="transparent" style="cursor:crosshair"/>')
    return out


def render(series: list[dict]) -> str:
    n = len(series)
    values = [band_values(r) for r in series]
    peak = max(sum(v) for v in values)
    ymax, step = _axis_scale(peak)
    dates = [r["date"] for r in series]

    px = lambda i: LEFT + (RIGHT - LEFT) * i / (n - 1)
    py = lambda v: BOTTOM - (BOTTOM - TOP) * v / ymax

    # Cumulative band tops, in stacking order.
    cum = []
    for vals in values:
        running, tops = 0, []
        for v in vals:
            running += v
            tops.append(running)
        cum.append(tops)

    out = _frame(n, ymax, step, TOP, BOTTOM, dates)

    # Paint the top band first and lower bands over it, so each polygon can run to the
    # baseline. Fills are opaque: at any alpha the bands beneath would tint the ones above.
    for band in reversed(range(len(SERIES))):
        pts = " ".join(f"{px(i):.2f},{py(cum[i][band]):.2f}" for i in range(n))
        out.append(f'<polygon points="{px(0):.2f},{BOTTOM} {pts} {px(n-1):.2f},{BOTTOM}" '
                   f'fill="var({SERIES[band][1]})"/>')
    # Surface gap on each internal boundary — white does the separating, not a stroke. 1px
    # rather than the usual 2px: the thinnest band here is ~3px tall and 2px would erase it.
    for band in range(len(SERIES) - 1):
        pts = " ".join(f"{px(i):.2f},{py(cum[i][band]):.2f}" for i in range(n))
        out.append(f'<polyline points="{pts}" fill="none" stroke="var(--surface-1)" '
                   f'stroke-width="1" stroke-linejoin="round"/>')

    # Direct-label the largest band only; every other value rides the legend. With nine
    # bands, end-labels for all of them collide into a stack of leader lines.
    last, now = series[-1], values[-1]
    biggest = max(range(len(SERIES)), key=lambda k: now[k])
    low = cum[-1][biggest - 1] if biggest else 0
    mid, name = (low + cum[-1][biggest]) / 2, SERIES[biggest][0]
    y = py(mid)
    out.append(f'<line x1="{RIGHT}" x2="{RIGHT + 8}" y1="{y:.1f}" y2="{y:.1f}" '
               f'stroke="var(--axis)" stroke-width="1"/>')
    out.append(f'<text class="endval" x="{RIGHT + 12}" y="{y - 1:.1f}">{now[biggest]:,}</text>')
    out.append(f'<text class="endlab" x="{RIGHT + 12}" y="{y + 11:.1f}">{name}</text>')
    out += _hover([var for _, var, _ in SERIES], TOP, BOTTOM)

    # The docs chart: one line in secondary ink, its own y-scale, the same time axis.
    docs = [r.get("docs", {"lines": 0, "pages": 0}) for r in series]
    docs_ymax, docs_step = _axis_scale(max(max(d["lines"] for d in docs), 1))
    dpy = lambda v: DOCS_BOTTOM - (DOCS_BOTTOM - DOCS_TOP) * v / docs_ymax
    docs_out = _frame(n, docs_ymax, docs_step, DOCS_TOP, DOCS_BOTTOM, dates)
    pts = " ".join(f"{px(i):.2f},{dpy(d['lines']):.2f}" for i, d in enumerate(docs))
    docs_out.append(f'<polyline points="{pts}" fill="none" stroke="var(--text-secondary)" '
                    f'stroke-width="2" stroke-linejoin="round"/>')
    docs_out += _hover(["--text-secondary"], DOCS_TOP, DOCS_BOTTOM)

    # The legend carries today's value for every series — see the module docstring.
    legend = "".join(
        f'<span class="lg"><i class="sw" style="background:var({var})"></i>'
        f'{name} <b>{now[k]:,}</b></span>'
        for k, (name, var, _) in enumerate(SERIES)
    )

    month_end = {}
    for r, vals, d in zip(series, values, docs):
        month_end[r["date"][:7]] = (r["date"], vals, d)
    head_cells = "".join(f"<th>{name}</th>" for name, _, _ in SERIES)
    rows = "".join(
        f'<tr><td>{day}</td>' + "".join(f'<td>{v:,}</td>' for v in vals)
        + f'<td>{sum(vals):,}</td><td>{d["lines"]:,}</td><td>{d["pages"]:,}</td></tr>'
        for day, vals, d in month_end.values()
    )
    table = (f"<table><thead><tr><th>As of</th>{head_cells}<th>Total</th>"
             f"<th>Docs lines</th><th>Docs pages</th></tr></thead>"
             f"<tbody>{rows}</tbody></table>")

    total = sum(now)
    first_month = f"{MONTHS[int(series[0]['date'][5:7])]} {series[0]['date'][:4]}"
    last_month = f"{MONTHS[int(last['date'][5:7])]} {last['date'][:4]}"

    data = json.dumps([[r["date"], *vals] for r, vals in zip(series, values)],
                      separators=(",", ":"))
    docs_data = json.dumps([[r["date"], d["lines"], d["pages"]] for r, d in zip(series, docs)],
                           separators=(",", ":"))
    series_js = json.dumps([{"name": name, "v": var} for name, var, _ in SERIES],
                           separators=(",", ":"))

    return (SHELL
            .replace("__DARK__", DARK_VARS)
            .replace("__SVG__", "\n    ".join(out))
            .replace("__DOCSSVG__", "\n    ".join(docs_out))
            .replace("__LEGEND__", legend)
            .replace("__TABLE__", table)
            .replace("__TOTAL__", f"{total:,}")
            .replace("__DOCSTOTAL__", f"{docs[-1]['lines']:,}")
            .replace("__DOCSPAGES__", f"{docs[-1]['pages']:,}")
            .replace("__RANGE__", f"{first_month} &ndash; {last_month}")
            .replace("__FIRSTMONTH__", first_month.split()[0])
            .replace("__ALT__", f"Stacked area chart of hand-written lines of code in Field4, "
                                f"growing from zero in {first_month} to {total:,} in {last_month}")
            .replace("__DOCSALT__", f"Line chart of hand-written documentation lines, "
                                    f"{docs[-1]['lines']:,} across {docs[-1]['pages']} pages "
                                    f"in {last_month}")
            .replace("__FOOT__", FOOT.format(vendored=last["vendored"],
                                             generated=last["generated"]))
            .replace("__DATA__", data)
            .replace("__DOCSDATA__", docs_data)
            .replace("__SERIESJS__", series_js)
            .replace("__L__", str(LEFT)).replace("__R__", str(RIGHT))
            .replace("__T__", str(TOP)).replace("__B__", str(BOTTOM))
            .replace("__DT__", str(DOCS_TOP)).replace("__DB__", str(DOCS_BOTTOM))
            .replace("__DOCSH__", str(DOCS_HEIGHT))
            .replace("__YMAX__", str(ymax))
            .replace("__DOCSYMAX__", str(docs_ymax)))


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
