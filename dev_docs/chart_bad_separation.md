# The failure colour a reader could not find

`Chart.expect()` states a claim about a series and draws it: the accepted range becomes a band, the
bound it names is ruled, the data outside recolours to `CHART_BAD`, and the footnote counts them.
The recolouring is the part a reader acts on, and it was drawn in a colour the palette already had.

`dev_docs/chart_clip_and_note.md` found this by measurement and pinned it rather than fixing it,
at `MIN_PALETTE_AGAINST_BAD = 12.0`, with the cost estimated as "rewrites every committed capture in
the repo and touches `Table`'s column accents and Plotly's `showSignals` too". That estimate was an
overcount: the fix moves one constant, leaves `signalPalette` alone, and changes four of the
twenty-four committed images under the three `chart_*` asset directories.

## What the collision was

CIEDE2000, over the `Vec4` constants as `float32`, which is what the harness measures:

| pair | before | after |
| --- | --- | --- |
| `CHART_BAD` vs slot 5 `#ff7a9e` (pink) | **12.24** | 29.40 |
| `CHART_BAD` vs slot 1 `#ff9e4a` (orange) | **24.96** | 29.32 |
| `CHART_BAD` vs its nearest slot | 12.24 | **29.32** |
| `CHART_BAD` vs the `#12151c` background | 55.63 | 45.72 |

For scale, the palette's own weakest internal pair is 23.81 and its median is 47.48, so `CHART_BAD`
now sits further from every series colour than two series sit from each other.

The 12.24 needed six series to bite. The 24.96 did not, and it was already visible in the repo:
`chart-expect-a-claim-you-can-watch-fail.png` has one series drawn as a line plus a points mark, so
the points took slot 1 and the four failing dots were salmon among orange. The committed version
before this change is the evidence — the four deviations are hard to pick out at a glance, on the
one figure whose entire subject is that they are easy to pick out.

## Why `CHART_BAD` moved and the palette did not

Either colour could have moved. Moving `CHART_BAD` is better for three reasons that only showed up
once measured:

- **A failing datum can appear in a chart with any subset of the palette**, so the constraint is
  `CHART_BAD` against *all six* slots, not against the pink. Moving the pink leaves the 24.96 orange
  in place and buys a floor of ~24; moving `CHART_BAD` clears everything at once.
- **The palette has three consumers and `CHART_BAD` has one.** `signalPalette` colours `Chart`
  series, `Table.accent`'s columns (`tables.kt:158`) and `showSignals`' traces, and the first two
  promise to agree with each other on which colour means which series. `CHART_BAD` appears only
  where an `expect()` claim failed.
- **The palette is six light pastels, and a marker wants to sit outside that family.** The six run
  `L*` 67.8–91.5 at chroma 32.6–65.5; `#ff6b6b` was `L*` 64.1 at chroma 63.3, inside that on both
  axes, and read as a seventh series. `#e60000` is `L*` 47.9 at chroma 96.5 — half again the chroma
  of anything in the palette and darker than all of it.

A grid search over the reds — `R ≥ 0.7`, `R` clear of `G` and `B`, `L*` between 35 and 66, at least
45 from the background — put the achievable ceiling at about 29.9 and the cheapest colour clearing
26 about 14.8 from the original. `vec(0.9, 0.0, 0.0, 1.0)` takes 29.32 of the available 29.9 while
being a single-component constant, so that is the one.

`--field-danger`, the token `CellRole.BAD` already uses for the same meaning in a `Table`, was the
obvious candidate and does not work: `#ff5050` is 16.59 from the same pink. `CHART_BAD`'s KDoc says
it matches `CellRole.BAD`'s *intent*, and that is all it can match — a table cell's red is text on a
row, competing with nothing, while a chart's red is a mark competing with six series colours.

One thing the move improves that was not the point: the expectation band is filled at alpha 0.09
over the background, and a more saturated red tints more. The band is 12.18 from the background
where it was 8.53.

## The slot that an explicit colour used to eat

`nextColor()` handed out `signalPalette[markList.size % size]`, and `markList` counts every mark,
including those given a colour by the caller. So a mark that never asked for a slot consumed one.

Two ways that bit, one of them with no careless caller involved:

- `public/docs/tetgen.md` hand-coloured its first series `vec(1.0, 0.72, 0.35)` = `#ffb859` and the
  next series then took slot 1, `#ff9e4a`, ΔE\*ab 13.91 away. That page now colours all four series
  by hand — the workaround is in the file with a comment saying why — so the page reads correctly
  today and the fix does not change it.
- `DataFrameCharts.chart` with `points = true` adds two marks per column, the dots explicitly in the
  line's own colour (`DataFrameCharts.kt:125`). Column *n* therefore took slot 2*n*: a three-column
  frame charted cyan / green / purple while `Table.accent` over the same frame went
  cyan / orange / green. The two are documented to agree and did not.

`Chart` now counts slots handed out rather than marks added. The second case is the one that changes
a committed capture, and it is the one worth having: the agreement between a table and a plot of the
same numbers is a promise the library makes in `tables.kt`'s own KDoc.

## What it cost

| what | result |
| --- | --- |
| `jvmTest --tests afield.{Chart,Table,Heatmap,PairDisplay}Test` | 74 tests, 0 failures, 0 errors (ChartTest 24 → 27) |
| `run_field_tests.py --id visualization.chart-gallery` | PASS, 4560 ms; palette line reads 29.32 against a floor of 25.0 |
| `checkCodebaseReference` | ok — 11939 symbols across 17 pages, after `generateCodebaseReference` |
| `checkKDocAttachment` | ok — 1311 files, 17686 blocks (17685 + the one added here) |
| `checkAgentDemos` | ok — 10 documents, 38 inline files |
| committed harness figures re-rendered | 1 of the 6 the gallery renders, so **2 of the 16** harness-written files across the three asset directories; a second run reproduces all 16 byte-for-byte |
| committed box captures replaced | 2 of 4 |

The figure that changed is `chart-expect-a-claim-you-can-watch-fail.png`, which is committed twice —
under `public/docs/assets/chart_gallery/` and `dev_docs/assets/chart_primitive/` — and is the only
gallery figure with a failing datum in it. The four clip figures in
`dev_docs/assets/chart_clip_and_note/` and the two counterfactual figures re-render byte-identical,
which is the evidence that neither change touched a figure that did not need it.

The box captures are `box4-expect.png`, for the colour, and `box2-dataframe.png`, for the slot
accounting: its second series moves from green to orange because `step` now takes slot 1 rather than
slot 2, which is also what makes it agree with a `Table` of the same frame. `box1-one-call.png` and
`box3-pickable-bars.png` are left alone: neither has an explicitly coloured mark or a failing datum,
so neither can move.

Re-capturing a box wants one box per Field. `macro run` launches all four, and with four output
panels open on one canvas `screenshot-box` returns the target's panel with its neighbours' panels
showing through it — a plausible-looking figure that is wrong. Opening the committed document and
running only the box being captured avoids it:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9482 \
  --workspace dev_docs/agents/demos --document chart_primitive --read-only \
  --command 'run chart_expect' \
  --command 'editor-open chart_expect --open-outputs' \
  --command 'sleep 2' \
  --command 'screenshot-box chart_expect box4-expect.png --padding 10 --preserve-selection'
```

`--read-only` because the document is committed, and `status` should report
`sessionKey dev_docs/agents/demos/chart_primitive` with `boxCount 4` before anything is believed.
A neighbouring box's *title* still shows faintly through the panel; that is on the committed
captures too, since it is on the canvas rather than in the figure.

## The gate

`ChartGallery.checkPaletteSeparation` was already measuring all of this; what changed is that its
`CHART_BAD` assertion is now a constraint rather than a record of what was tolerated.

- `MIN_PALETTE_AGAINST_BAD` 12.0 → **25.0**, above `MIN_PALETTE_SEPARATION`'s 20.0 because a failing
  datum has to survive a reader who is not looking for it, and above the old 24.96 so that the
  configuration the `expect` figure was actually drawn in is red rather than borderline.
- A new assertion that `CHART_BAD` clears `MIN_PALETTE_AGAINST_BACKGROUND` too. Getting away from
  the palette meant `L*` 64.1 → 47.9 and chroma 63.3 → 96.5, which leaves it 45.72 off the
  background against a floor of 40, and the next move in that direction is the one that makes a
  failing datum invisible. Nothing was checking that, because `CHART_BAD` used to be among the
  brightest things on the figure.

`ChartTest` carries the slot accounting as arithmetic — three tests: that a hand-coloured mark leaves
slot 0 for the next one, that a frame's series land on the slots `Table.accent` would use, and that
the palette wraps and `CHART_BAD` is not in it.

## On the 0.01

The clip note says its Kotlin CIEDE2000 "agrees with an independent Python reference to 0.01", which
is true and was worth knowing, and the 0.01 is 8-bit quantisation rather than arithmetic drift. The
constants are `float32`: `vec(1.0, 0.42, 0.42)` measures 12.24 against the pink, and the `#ff6b6b`
it renders to measures 12.25. It is wider for the new colour, because `0.9` is further from
`230/255` than `0.42` is from `107/255` — 29.32 for the constant, 29.25 for the `#e60000` on screen.

Both numbers are correct, of different things. The assertions are on the constants, because a
constant is what a future edit moves. Re-checked for this change against a third implementation,
`skimage.color.deltaE_ciede2000`: fed the same `float32` Lab triples, all three agree to 0.000000 on
every pair in this note.
