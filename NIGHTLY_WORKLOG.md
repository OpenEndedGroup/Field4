# nightly/2026-09-09/3-tetgen-public-page

Kind C — documentation / explainer. Base `6658f4d65`.

## Premise check

Confirmed. `grep -rln -i tetgen public/docs/` returns nothing at the base commit. TetGen landed
in `daa117503` with `native/field-tetgen`, `afield.tetgen.Tetgen` (243 lines), `TetgenTest`
(6 tests) and `dev_docs/TETGEN.md` — and no published page.

## What runs on this machine

Verified before writing a word, because the mission says re-derive rather than quote:

- `python3 tools/build_tetgen_native.py` built
  `build/field-tetgen/build/libfield_tetgen_jvm.dylib` (792 KB) from the pinned, checksummed
  TetGen 1.6.0 tarball. Exit 0, no network failure, no vendored source.
- `libmanifoldc.dylib` is installed at `/opt/homebrew/lib` (Homebrew manifold 3.5.2).
- `./gradlew --no-daemon jvmTest --tests 'afield.tetgen.TetgenTest'` → **6 tests, 0 failures,
  0 skipped**, 0.821 s. No `SKIPPED TetGen tests:` line, so every test really exercised the
  native path rather than short-circuiting on a missing library.

So every number and every figure on the page can be generated here.

## Plan

A new published page `public/docs/tetgen.md`, in the voice and fence conventions of
`public/docs/manifold.md` (its nearest neighbour — same FFM shape, same optional-native story,
same `requires=` gating).

Shape:

1. **What it is for.** A surface bounds; a tetrahedral mesh fills. Field could already make and
   boolean closed surfaces; nothing could stress one. `structural.bodyFitted` needs the interior
   because Kratos has no immersed structural element.
2. **The first call**, with a cutaway figure of a real tet mesh.
3. **Quality**, measured: radius-edge ratio (what TetGen's `-q` actually bounds) and radius
   ratio (what conditioning cares about), with a histogram figure.
4. **Sizing**, with a live convergence sweep: edge length against node count, cell count, wall
   time and volume error.
5. **Sharp edges**, re-derived here rather than quoted — the cube's exact volume, and the same
   box put through a level-set march for contrast, since that is the route this replaced and it
   is reproducible on this machine without MMG.
6. **Failure modes** — the open surface, the `preserveSurface` trap, the refusals that happen
   before TetGen is called, with the real message text.
7. **Building it**, the AGPL note, and the two compile flags that are load-bearing.

Supporting code I own and will add:

- `src/jvmMain/kotlin/afield/tetgen/TetMeshViews.kt` — `qualities()`, `radiusEdgeRatios()`,
  `centroids()`, `filtered()`, `boundary()`, `toTriMesh(shrink)`, `toFLines()`. Without these
  every figure on the page is thirty lines of index arithmetic, which is the wrong thing for a
  page to teach. With them the examples are short and the page is about meshing.
- tests for that file in `src/jvmTest/kotlin/afield/tetgen/`.
- a `"tetgen"` entry in `NATIVE_PREREQUISITES` in `tools/build_zensical_docs.py`, so
  `requires="tetgen"` is a known key. This is outside my stated file partition but is a
  two-function addition to docs tooling that nobody else is touching tonight, and the page
  cannot declare its prerequisite without it.
- a nav entry appended to `public/zensical.yml`.

## Status

Plan written. Execution follows.

---

# Second session, 2026-09-09 evening

The first session was killed by a rate limit mid-sentence, at *"let me pin this as a real test
rather than a print-only probe"*. The orchestrator salvaged its staged tree as `e41cf2b02`. This
picks up from there. Everything above is the first session's; everything below is this one's.

## What changed

`3e768fd04` **merged `main`**, which had moved from `6658f4d65` to `4107a579e`. Clean — zero file
overlap. Two Kratos commits landed and both bear directly on this page.

`f832cd8cc` **`TetMeshViews.kt` reads a cell at its own stride.** `ad7e97b43` made
`TetMesh.nodesPerCell` 4 or 10; every view in this file was written before that and indexed `cells`
at a hard 4. Nothing about that fails loudly — `cellCount` is right, every index a stride-4 read
lands on is a real node, nothing is out of bounds — so a 10-node mesh would have produced a
plausible quality histogram and a plausible picture, both wrong. One private `corner(t, k)` now owns
the stride and nothing else touches `cells`. The new test compares view for view against the
unpromoted mesh rather than checking a plausible range, because a plausible range is the bug.

`9f7ffc4f2` **the sharp-edge figure's clip**, and `281f3948f` **its recapture**. See below.

`a6eb70e29` **the `## Into Kratos` rewrite.** The page said the 4-node tetrahedron locks, quoted
35.6% → 75.9% of `wL⁴/8EI`, and closed with *"the wrapper currently rejects anything but 4-node
output, which makes that a visible gap rather than a silent one"*. It does not any more. The
locking discussion stayed and got longer — what a constant-strain element is and why bending is
exactly what it cannot do — and now carries both columns from `optional/kratos/VERIFICATION.md`:
13.2 / 35.6 / 62.0% at 4 nodes against 95.4 / 98.8 / 99.7% at 10, on 30, 240 and 1,080 tetrahedra,
plus 69.5% against 99.8% end to end on one 864-element TetGen mesh. Those are the container's
numbers and the page now says so, as does the header, which previously claimed everything on the
page was produced here natively. A new `### Asking for 10-node elements` covers `order = 2`,
`TetMesh.quadratic()`, `corners()`, the `-o2` use-after-free and the cost; its numbers are this
machine's.

`0eb9a1877` **four of the six probes assert.** `TetgenPageProbeTest` was six tests with no assertion
in any of them.

## Verification

Observed, not inferred:

| what | result |
|---|---|
| `jvmTest --tests 'afield.tetgen.*'` | see per-class rows below |
| `TetMeshViewsTest` | 10 tests, 0 failures, 0 skipped, 3.3 s |
| `TetgenTest` | 9 tests, 0 failures, 0 skipped, 0.5 s |
| `TetgenPageFenceDryRunTest` | 3 tests, 0 failures, 19 s |
| `TetgenPageProbeTest` | 6 tests, 0 failures, 18.3 s |
| `TetgenPageMeasurementTest.printWhatTenNodeElementsCost` | 1 test, 0 failures |
| `checkKDocAttachment` | green, 1302 files, 17504 KDoc blocks |
| `citationGates` | green |
| `checkCodebaseReference` | green, 11929 symbols across 17 pages |
| docs capture | 3 runs, 52.2 s / 66.5 s / 56.0 s, `regenerated=1` each, `failures=0`; `cutaway.png` and `quality.png` reused from cache every time |
| `build_zensical_docs.py --page tetgen` | `No issues found`, all three runs |
| `sharp_edge.png` | **opened and read, three times** |

The three gates were forced rather than trusted:
`./gradlew --no-daemon --rerun-tasks citationGates checkKDocAttachment checkCodebaseReference` →
**BUILD SUCCESSFUL in 3m 31s, 27 actionable tasks, 27 executed**. Not one `UP-TO-DATE`. They also
ran inside every commit hook on this branch, and `checkKDocAttachment` refused two of my commits
before I got them right, both for the same thing that stopped the salvage commit: a KDoc above
`@Test fun …` sharing one line.

Zero skips on every TetGen test means the native library really ran rather than short-circuiting.
`compileTestKotlinJvm` is warning-free; the one warning the forced run turned up
(`TetgenPageFenceDryRunTest.kt`, a redundant `.toDouble()`) is gone.

**What I did not run.** No Kratos anything: not the container, not `run_field_tests.py --id
kratos.*`, not the Python worker suite. A sibling agent owns that tonight and has the podman
container. Every Kratos number on the page is quoted from `optional/kratos/VERIFICATION.md` and the
page says which numbers are the container's. I also did not run the full `jvmTest`, only the
`afield.tetgen` classes plus the three forced gates.

## The figure, and how it was actually diagnosed

The upper panel of `sharp_edge.png` was an empty olive rectangle with an illegible plot in one
corner. Three things were wrong, and each was only visible by opening the PNG — the build said
`No issues found` for all three versions.

1. **A `Chart` does not clip to its own axes.** `resolvedXDomain` sets the scale and `lineLines`
   then draws every point it was given, however far outside the plot rectangle it lands.
   `Chart.toHTML` passes the result to `linesToHTML`, and a box output panel fits the geometry it
   receives. A cross section 2 × 3 across, charted into a 0.24 × 0.24 window, put the outline some
   five thousand pixels outside a 780 × 440 frame. The fence now clips with Liang–Barsky before
   charting, with a `NaN` between disjoint runs so one outline stays one series.
2. **A `Chart`'s note is one unwrapped line.** 138 characters was wider than the figure, so it was
   clipped at the frame edge *and* still counted towards the bounds the panel fits — losing its own
   tail and shrinking the figure to about 70%. The `main` panel, which has a 59-character note, was
   the control.
3. **Four warm colours.** The amber reference and the amber `h = 0.1` were indistinguishable. The
   three marched outlines are now explicitly red, green, blue, coarsest to finest.

`TetgenPageFenceDryRunTest` rehearses the clip and asserts every clipped coordinate is inside the
window, because that failure is a layout failure and not an exception.

## Risks and unknowns

- **`TetMeshViews.kt` has never been exercised on a quadratic mesh from TetGen itself.** The new
  test promotes a hand-built two-tetrahedron mesh. The arithmetic does not care, but nothing on this
  branch has run `Tetgen.tetrahedralize(surface, order = 2)` through `boundary()` on a real body and
  looked at the picture.
- **The `Chart` non-clipping is not fixed, only worked around.** `plotRect`'s own KDoc says the plot
  area is "the rectangle the axes bound and the data is clipped to", and for `MarkKind.LINE` that is
  not true. Fixing it belongs in `afield/visualization/charts.kt`, which is outside this branch's
  partition and would change every figure in the docs. Filed here rather than done.
- The same applies to the unwrapped note. Any page that writes a long `note` gets a silently
  truncated caption and a silently shrunken figure.
- **The Kratos numbers are quoted, not re-derived.** They are labelled as such, and I have no way to
  check them without the container.
- `checkAgentTranscriptLayouts` was red on this branch's base (a first-session finding) and I did
  not re-check it; my file set does not select it.

## Recommendation

Mergeable. The blocker is fixed and confirmed by eye, the stale Kratos passage now describes what
landed, and the page's load-bearing claims have assertions behind them for the first time.

Two follow-ups worth their own branches, both outside this partition:

1. Clip `MarkKind.LINE` (and the rest) to `plotRect` in `charts.kt`, and wrap or measure `Chart.note`
   against `width`. Two defects, both silent, both capable of producing a wrong-looking figure that
   passes every gate.
2. Run `Tetgen.tetrahedralize(order = 2)` on the page's bracket through `boundary()` and look at it.
