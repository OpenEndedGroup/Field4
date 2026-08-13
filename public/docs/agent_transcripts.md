# Agent transcript gallery

Field ships a small, curated corpus of **operation transcripts** — short YAML macros that build a
whole Field document (boxes, code, graph wiring) from a blank canvas. They are the worked examples
an agent copies, runs, and adapts when [automating Field](automating_field.md).

These are the same scenes offered as **Starting points** on the [welcome screen](history_overview.md):
each transcript that is a self-contained scene is materialized into an openable document, so you can
search for it there and open an editable copy in your workspace. Four are not, and say so in their
entry — a transcript that needs an external asset, a user gesture in a browser, or a renderer from a
box outside itself makes a broken starting point, so none is offered.

This page is the index of the whole corpus, and it is complete because a build check says so:
`checkAgentTranscriptIndex` fails if a transcript in `dev_docs/agents/transcripts/` has no entry
here, if an entry names a transcript that no longer exists, if an entry offers a starting point that
was never generated, or if an entry is filed under a section whose claim about CI does not match how
CI actually checks it. The entries themselves are written by hand, because what is worth saying about
a scene is which number it prints and why that number is the interesting one, and no generator over
the yaml can write that.

Where an entry carries a figure, that figure is a capture of this code running, taken by the docs
capture pipeline for the walkthrough page linked beside it. Most entries carry none: a scene that
opens a native Vulkan window, or publishes to a headset, has no still a build can honestly produce,
so those say what to run instead.

## Running one

Any transcript can be replayed into a running Field with the agent backdoor or the `field_run_transcript`
MCP tool — see [Automating Field](automating_field.md) for the full mechanism. In short:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'macro write hello_display --file dev_docs/agents/transcripts/hello_display.yaml' \
  --command 'macro run hello_display'
```

The source corpus lives in the repository at `dev_docs/agents/transcripts/`, with a companion
`README.md` covering the op vocabulary, capture commands, and the graphics-topology rule.

## What CI checks, and what it does not

<!-- transcript-index: totals -->
All 59 transcripts are checked on every build, by one harness — `DemoTranscriptExecSmokeTest` — but
not in the same way, and the difference is worth knowing before you trust one. 42 of them are
**replayed end to end**: the real macro runner builds the graph and the real compile-and-evaluate
pipeline runs every box the transcript asks it to, and the test fails if a single box emits one
error-kind output line. (Almost always that is every box in the file. A transcript may deliberately
create a box it does not execute — one that wants a device the build does not have, or that fails on
purpose to demonstrate a diagnostic — and only the ops it actually writes down are replayed.) 10
open a GLFW window and a Vulkan renderer, which is not a thing to spin up headless in a build, so
those are **built and compiled**: the graph is assembled through the real apply path and each box's
Kotlin goes through the real K2 compiler, with only the terminal evaluation — the line that would
open the window — skipped. The remaining 7 are Kotlin **compiled to JavaScript** for a browser or
visionOS head, through the real head profile that the box's `// {{ marker }}` selects.

None of the three proves a picture is *right*; that is what the docs captures and the gallery
FIELD_TESTs are for. What all three prove is that the code in these files still compiles against
today's stdlib, which is the failure that actually happens: on 2026-07-18 `fn_differentiable_fit_demo`
shipped with two compile errors in a box body and merged, because until then nothing in CI ran a box.
A second guard, `checkAgentTranscripts`, materializes every transcript into a document and compares
it byte-for-byte against the committed starting points, so the copy the welcome screen opens is the
one the yaml produces today.

## Pure-compute scenes

<!-- transcript-index: verified-by = execute -->

These build and run headless — no window, no GPU. Open one to get the code in front of you, then run
its box to see the output (a plot, an inline value thumbnail, a printed line).

**hello_display** — Minimal blank-canvas macro that creates one runnable output box.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/hello_display/metadata.edn)

**hello_display_capture** — The same single display box, paired with the backdoor capture commands
(`wait-output` / `output` / `screenshot-box`) that collect its output events and a cropped
screenshot. Capture is a transport concern, so it lives in backdoor commands, not in the macro.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/hello_display_capture/metadata.edn)

**two_box_context** — Creates a parent/child pair and verifies the child inherits graph context from
its parent.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/two_box_context/metadata.edn)

**linalg_thumbnails_demo** — Each box evaluates one `afield.stdlib.linalg` value; on execute its
output shows the terse text head plus a small inline SVG thumbnail the `ValueVisualizer` draws — an
auto-scaled arrow for vectors (isometric for `vec3`), a diverging heatmap grid for matrices.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/linalg_thumbnails_demo/metadata.edn)

**control_surface_laws** — The three affordances Field's control surface gained: a slider **law**
(logarithmic, taper, bipolar), a **momentary button**, and a read-only **meter** fed by a `source`
lambda at 30 Hz. Four sliders over the same 20 Hz–20 kHz range make the point directly — 1 kHz sits at
56.6% of a logarithmic track and 4.90% of a linear one — and a button sits beside a checkbox so the
difference between a gesture and a state is visible rather than described.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/control_surface_laws/metadata.edn)

**fn_differentiable_fit_demo** — A self-contained demo of the differentiable fitter
(`afield.stdlib.fitBest` / `fitGradient`), the gradient-descent counterpart to the genetic
`evolve()`. The target is a product of two oscillators with a deliberately-wrong seed; a single
`fitGradient` stalls in a frequency local-minimum, so the demo uses `fitBest` (jittered restarts) to
land in the global basin. On execute it plots the target and the fitted curve and returns an RMSE
before→after summary with a loss-trajectory sparkline.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/fn_differentiable_fit_demo/metadata.edn)

**pat_fn_graph_fit** — A rhythm as a graph you can fit. `patFn(...)` turns a pattern of kernel atoms
(`g:(value,sigma)`, `adsr:(…)`) into a `Functions.Fn`, and that `Fn` carries a real graph node whose
leaves are the numbers written in the pattern text — so `fitGradient` tunes a *rhythm*. Box 1 prints
the node: its op, its role-tagged knobs (`atom0.sigma`, `atom1.attack`, `cps`) and their values. Box 2
fits a two-bump target from a seed with the right structure and the wrong numbers, plots the two on
top of each other, and prints the fitted gesture back out as pattern text you can paste anywhere. Box
3 is the honest limit: the same fit with the two heights *swapped* stalls, because re-assigning values
between atoms is a permutation rather than a direction.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/pat_fn_graph_fit/metadata.edn)

**pattern_language_composition** — [The pattern language](pattern_language.md) as eight independent
boxes, six of which render their own audio offline. *Continuous signals* prints the exact rational
midpoint of `PatArc(3/7, 5/7)`, because a pattern is a function of a time *interval* and the
arithmetic is rational rather than floating point. *Whose rhythm survives* is the one that repays a
slow read: `|+|`, `|+` and `+|` over `"0 1"` and `"10 20 30"` produce 4, 2 and 3 events, so the bar
is which side's structure the result keeps. *A parameter, not a staircase* attaches
`sine().rangex(300, 6000)` as a cutoff and prints the note count at `bd*4`, `bd*8` and `bd*16` to show
it unchanged — then runs the control arm, `appBoth` over a segmented curve, which fragments the
rhythm on purpose. *Hear the difference* measures its own render rather than asserting it: it detects
the hat onsets and prints the level coefficient of variation of the flat half beside the modulated
half. *Voice leading* prints total movement as written against voice-led, and then both pitch-class
sets, so the claim "the harmony did not change and the movement did" is two printed lines rather than
a sentence. *Groove* prints swung off-beats as exact rationals, a third of a subdivision late;
*mini-notation markers* checks three shorthand/bracket pairs (`bd sd . hh hh hh` against
`[bd sd] [hh hh hh]`, and the `_` and `!` forms) arc-for-arc and value-for-value and prints the
boolean; and *playground* is the box to edit. `showPatRoll` is SVG into `display()` and `showPatAudio`
renders through a deterministic CPU voice into an inline player, so there is no audio device and no
window anywhere in this file.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/pattern_language_composition/metadata.edn)

**petri_sound** — [Petri nets as a sequencer](petri_nets.md) as seven independent boxes, each
drawing its net with `showPetriNet` and playing it with `showDirtAudio` — a net's `emits()` is a
`PatPattern<String>`, so the audio path takes it with no adapter, which is the point. The audio is
mixed *offline* from the real Tidal Dirt-Samples library when it is installed (`DirtRender.kt`
auto-discovers the SuperCollider quark, `FIELD_DIRT_SAMPLES` overrides) and from the synthetic
`patVoice` when it is not, and each box prints which one it used — so this file sounds like actual
drums on a machine that has the samples and still runs, silently degraded but honest, on one that
does not. *The smallest loop* is one token going round two transitions and prints
`bd sd bd sd bd sd bd sd` at a period of `1/4`. *Five against three* is the one that earns the model:
nothing in its source says fifteen, and `settle()` prints that it settles after 15 steps into 2
T-invariants — one per independent ring — with four cycles of audio so you can hear the rings drift
and re-align. *Song structure* and *tokens that are rhythms* are the medium timescale: the second
carries a whole `PatPattern` as a token's colour, so `unfold` prints four bars of `bd sd` followed by
four of `bd bd bd bd hh hh…` — an arrangement enforced by the net rather than by a scheduler, because
only one place is marked. *Dining philosophers* is deliberately silent and is the contrast: the same
engine as a concurrency checker, printing `deadlocks=true` for the naive table, then
`deadlocks=false … never fires: grab.4, eat.4, done.4` for the asymmetric one — a starvation, not a
deadlock, which is why the third line adds a fair conflict policy and nothing is left over. And
*playground* is the box to edit: a read arc counting a fast `bd hh` ring, a weighted arc draining
three at once, and a snare that consequently walks backwards through the bar (`bd hh bd hh bd hh bd
sd hh` becoming `bd sd hh bd hh bd hh bd sd hh`), which is a 3-against-8 nobody wrote down. A
seventh box is the *live* path rather than the offline one — the real polyphonic Dirt player driven
by the transport — behind a `live` flag that defaults to false, because `audio { }` opens an output
device and this file is replayed end to end in CI; the flag rather than a comment is what keeps
those lines compiling.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/petri_sound/metadata.edn)

**petri_state_space** — The other half of `afield.petri`: the same engine as an exact concurrency
checker, in five boxes. Where `petri_sound` asks what a net *plays*, this asks what it can *do* —
[the classical questions](petri_nets.md) over every interleaving rather than the one the conflict
policy picks. The first box is the argument: on the symmetric five-philosopher table `willDeadlock()`
prints `true` for that one trajectory, and `stateSpace()` exhausts all **82** reachable markings, all
**265** firings between them, and hands back the shortest sequence that wedges it — `grab.0, grab.1,
grab.2, grab.3, grab.4`, five firings, each of the markings along the way re-fired through the
engine's own enabling test by `verify()` before it is believed. It is drawn, too: BFS depth left to
right, one dot per marking, the counterexample in orange across the top into a red-ringed `stuck`.
*Asymmetric* is the fix and the fault that survives it — 70 markings, deadlock-free *proved* rather
than observed, and then `deadTransitions()` naming a starved philosopher on the same net where
`neverFires()` correctly names nobody, because starvation belongs to the conflict policy and deadness
belongs to the net. *Buffer* counts the four-slot producer/consumer buffer at exactly five reachable
markings, 4-bounded, live and reversible, and asks an `EF` query for the shortest route to a full one.
*A leak* is the honest one: the exhaustive search reports `BUDGET` and returns `null` — unknown — from
every accessor that would otherwise have said "safe", while Karp–Miller puts an ω on the leaking place
and terminates in two nodes. And *what the algebra cannot see* prints two nets with byte-identical
incidence matrices, one of which enforces mutual exclusion with an inhibitor arc: no P-invariant
couples the two critical sections, and the search proves the exclusion over all three markings anyway.
Finally *the wall* measures the thing a green tick hides: the philosophers' state space grows like the
companion Pell numbers, about 2.41× per philosopher, so eleven is 16 238 markings in 230 ms and twelve
is past the budget — and a stubborn-set partial-order reduction turns that into `2n²−2n+2`, a **73×**
speed-up at eleven and an answer at twelve, while refusing every property the theorem does not cover.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/petri_state_space/metadata.edn)

**mesh_geometry_examples** — The [mesh and 3D geometry worked example](mesh_examples.md) as seven
independent boxes, one per stage of the family. *Primitives* builds a cube, an icosphere, a torus and a
cylinder and prints what each one is — welded, watertight, χ = 2. *Marching cubes* takes a sphere, a
drilled sphere and a gyroid out of scalar fields and shows the surface converging on the analytic
volume as the resolution doubles. *SDF modelling* writes a shape as an expression — primitives combined
with union, difference and a smooth minimum — then meshes it. *Subdivide, smooth, decimate* runs one
mesh through Loop subdivision, then Laplacian against Taubin (watch which one holds its volume), then
QEM decimation to a triangle budget with the deviation that costs. *Curvature, rays, closest points,
inside* queries a torus through the BVH and prints correctly-signed Gaussian curvature. *Bridges* turns
`voronoi3d` cells into meshes, meshes into `FLine`s, and writes OBJ and STL. The last box needs no view
code at all: it evaluates a `TriMesh` and lets its own `ToHTML` draw it — a shaded render beside the
counts, χ, genus, area, volume and bounds. Every box is pure CPU and runs on its own.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/mesh_geometry_examples/metadata.edn)

**geometry_from_disk** — [Geometry from disk](geometry_from_disk.md) as three boxes, all of them
starting from a file rather than a generator. The first reads the committed 5.4 MB Khronos ToyCar GLB
through `meshFromAnyFile` and renders it with `showMesh`: **77,429 vertices, 108,936 triangles** over
three mesh/material buckets, each baked through its own node transform so the car arrives sitting on
its draped cloth rather than heaped at the origin — and extent **0.073 × 0.029 × 0.074**, because the
file is in metres and it is a 7 cm toy. It reports **χ = 1909, open, 2782 boundary loops**, which is
not a defect but what an artist's shell always looks like once every UV seam is counted as the cut it
really is. The second writes one coloured sphere out as OBJ and as binary STL and reads both back,
printing the row that matters: OBJ returns **642 verts, watertight, χ = 2** with its normals and
without its colours, STL returns **3840 unshared verts, χ = 1280, open** and welds back to 642 without
its normals — while the volume stays **4.152741** down every route, which is the check that the
coordinates themselves survived. The third writes a four-corner coloured ASCII PLY and reads it with
`PlySupport.loadPly`, which needs no renderer at all, then bridges it to a `TriMesh` with the colours
intact and the loader's invented `(0, 0, 1)` normal correctly dropped. Change the path in the first box
to a mesh file of your own; boxes two and three clean up the temp files they write.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/geometry_from_disk/metadata.edn)

**typography_worked_example** — [Typography as geometry](typography_examples.md) as eight boxes, and
the interesting thing about it is that there is no AWT rasteriser anywhere in the file: AWT is touched
only to read a font's glyph outlines, and every picture after that is Field's own scanline filler or
Skia SVG. *A string becomes contours* prints, per glyph, the contour and hole counts, the advance, and
each ring's depth and *signed* area — which is where the winding convention becomes visible. *The
winding convention, and what happens without it* is the control arm: the same word rasterised with
`honourHoles = true`, with it switched off (the counters close up solid), and under `EVEN_ODD` over
the correct rings — then it prints the worst per-pixel difference between the last two, where 0.0
means the two fill rules agree exactly. *Advances, kerning, tracking, alignment* prints two identities
instead of claiming them: the laid-out advance width against the sum of the glyph advances, and the
line widened by tracking against `(gaps × 0.16 em)`. Its kerning probe is written to survive a face
with no kern table at all, and prints which happened. *Text on a curve* lays a ring by arc length and
prints how many glyphs turned with the curve, plus that the original layout is untouched. *The
handles, animated* must be **launched**, not executed, and pre-computes its window from the wave's
extremes so the frame does not jitter. *Extrusion* checks a 2D hole count against a 3D Euler
characteristic and prints ink area × depth against the measured mesh volume. *SDF* and *point clouds*
close it out, the second running Delaunay and k-medoids over sampled letters.

![The word "open" three times: counters open, counters filled solid, and counters open again under the even-odd rule](assets/typography_examples/winding.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/typography_worked_example/metadata.edn)

**wfc_worked_example** — The [Wave Function Collapse worked
example](wfc_examples.md) as eight independent boxes: adjacency authored by hand with `wfcRules { }`
and both phases pinned with `forceTile`; the simple-tiled model learned from an image, printing the
band histogram that shows its learned weights losing the water bands entirely; an eight-tile
edge-socket set whose traces always connect; the overlapping model with and without the classic
ground constraint; von Neumann vs Moore on a source built so the choice provably matters (473
diagonal breaks against 0); and the collapse itself sampled through `step()` into wave and
possibility-count frames; and both `FloatArray` paths, whose synthesised heightfield ends up as
iso-contour `FLine`s. The last box needs no view code at all: it evaluates a half-collapsed and a
contradicted solver and lets `WfcSolver`'s own `ToHTML` draw them — possibilities left, entropy, and
the tiles decided so far, with the contradicted cell marked in both heatmaps. Every box is pure CPU,
runs on its own, and prints the numbers the docs page quotes.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/wfc_worked_example/metadata.edn)

**spatial_geometry_examples** — The [Voronoi, contours and clusters worked
example](spatial_geometry_examples.md) as five independent boxes, each drawing itself with
`showPlaneGrid` (which is `drawToSVG` → `display`, so no AWT and no GPU). *Delaunay* builds the
triangulation, its Voronoi dual and the convex hull, checks the triangle and edge counts against
Euler's `2n-2-h` and `3n-3-h`, and prints how far past the sites JTS's clip envelope for an unbounded
cell actually reaches. *Apollonius* runs the same five sites at equal weights (every bisector a
straight line), at mixed weights (every bisector a circular arc), and with one site heavy enough to
swallow two neighbours into **complete discs** — which makes its own cell a region with holes rather
than a polygon — then verifies every boundary sample really is a score tie. *Contours* recovers the
Voronoi diagram from a completely different algorithm by contouring the gap between the nearest and
second-nearest site, and shows why asking for iso `0.0` finds *nothing*: that field is non-negative,
it grazes zero on the seams, and marching squares locates sign changes. *Marching* puts the two
decisions inside marching squares on inputs whose exact answer you know — a level set running through
a whole column of grid samples, a circle passing exactly through grid corners, and the ambiguous
saddle cell — and draws the sampling grid so you can see what the marcher had to work with.
*Clustering* cuts a merge tree at two depths, shows the finer cut nested inside the coarser, and runs
k-medoids and k-medians on the same planted clusters. Every box is pure CPU and runs on its own; move
a site, change a weight, raise an iso level or ask for a different `k` and re-run it.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/spatial_geometry_examples/metadata.edn)

**alife_worked_example** — [Spatial artificial life](alife_examples.md) as twenty boxes: ten that
measure, and a band below them of ten that **move**. The measuring ten are independent and pure CPU —
Conway's Life checked against the glider's exact `(1, 1)` displacement and the R-pentomino's
textbook peak of 319 at generation 821; Wolfram's rule 90 checked cell-for-cell against Pascal's
triangle mod 2 computed from Kummer's theorem; a cyclic CA whose soup advance fraction matches the
closed form `1 - (13/14)^8` and then pins at 1.0; Gray–Scott's regimes told apart by isoperimetric
ratio, with two controls that produce nothing at all; Lenia's discrete limit shown bit-identical to the
integer Life stepper; boids and Physarum each against a twin run with one interaction switched off;
DLA's mass dimension against an Eden cluster and a uniform sprinkle; the Koch snowflake's exact
closure, length and area identities; and the bridges out to iso-contour `FLine`s, a colonization tree
and a watertight marched mesh. Every one of them prints the numbers the docs page quotes.

The moving ten are the same systems as time evolutions, through `Box.animateEvolution` — each one a
schedule (how many steps, over how many seconds, on what ramp) plus a way to draw the state, with the step
counter burned into every frame so a clip cannot lie about where it got to. They must be **launched**
rather than run: a `Frames` that is only built never runs. None of the twenty opens a `Window` or a
`Renderer`.

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/alife_worked_example/metadata.edn)

**statistics_examples** — [Which of these signals actually depend on each other?](statistics_examples.md)
as five independent boxes. *Summary* pins the three quantities `buildSummaryStats` makes easy to
misread — MAD recomputed from scratch to show it is the unscaled median absolute deviation, the
`sampleStdDev/populationStdDev` ratio against `sqrt(n/(n-1))` to show which of the two conventions the
card's `stddev` follows while its `skew` follows the other, and `cv` blowing up to 10¹⁴ once the mean is
re-centred to zero. *Histogram* re-derives the bin-width rule and predicts the bin count before asking:
28 bins where Freedman–Diaconis wins, and 10 where FD and Scott are both inflated by the distance
*between* two modes so the crudest rule, Sturges, decides. *Dependence* is the centrepiece — six shapes
at n=400 with all six coefficients on each, `xi` shown asymmetric (0.985 forwards, 0.246 backwards on a
parabola), `dCor` shown to be exactly 1 for *any* straight line but only 0.491 for a perfect parabola,
and then the permutation test the family does not provide: the growing-spread case's dCor of 0.193 beats
all 400 random re-pairings while its xi of 0.026 is beaten by 76 of them. *Rolling* runs both rolling
classes across a regime change against rolling `|pearson|` and against the measured null threshold, then
cross-checks the incremental sums against the O(w²) batch call — two different algorithms agreeing to
5×10⁻¹⁵. *Warp* fits an 8-knot quantile warp on one log-normal draw and measures a disjoint second one
(skew 11.7 → 0.007), compares it against the exact `Φ⁻¹(F̂(v))` built in the box, and prints both sharp
edges: `evaluate(-1.0)` returning −21.1 outside the fitted knots, and the 0.20 smoothing tracking
`10·0.8ᵏ`. Every box is pure CPU and self-checking; change a shape, a window size, a target distribution
or an n and re-run it.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/statistics_examples/metadata.edn)

**spectral_examples** — [Spectral examples](spectral_examples.md) as five independent boxes over the
same chirp. *One spectrum* runs `fft` with a Hann window and plots the one-sided magnitude against
real Hz, with the centroid and the 85 % rolloff drawn in as dashed marks rather than left as numbers.
*Spectrogram* takes the same chirp through `stft`, converts to dB relative to peak with an explicit
−80 dB floor, and overlays the centroid trace on the heatmap. *Mel* runs two chirps crossing in
opposite directions through a 48-band filter bank, so the picture is an X and the bands are the only
thing you have to trust. *Resonator bank* is the control arm and does no transform at all: 96
log-spaced resonators from 120 Hz to 3900 Hz recover the same structure by filtering, which is worth
seeing beside the FFT. *Streaming features* pushes samples one at a time through
`EMAFilter then SpectralCentroidFilter`, plus flux and RMS, into two injected onsets planted a third
and two thirds of the way through — so spectral flux has something it should spike at.

![A spectrogram of a chirp: a rising diagonal ridge with two horizontal tone lines, the spectral centroid traced over it](assets/spectral_examples/spectrogram.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/spectral_examples/metadata.edn)

**imaging_cv_examples** — [Imaging and computer vision](imaging_examples.md) as six independent
boxes, all pure CPU: `showImgGrid` is `Img.toPngBytes` → `PngEncoder` → `display`, so there is no AWT
rasteriser and no GPU. The images are deliberately large — 2400×1800, 4.3 megapixels, 52 MB of float
RGB — because that is the size at which "is this fast enough to actually use?" has a meaningful
answer. *Basics* prints the Otsu threshold it computed rather than one guessed, and resamples down and
back up so you can see the detail is really gone. *Filters* corrupts a clean synthetic image with 4 %
salt-and-pepper noise and prints the mean absolute residual of gaussian, median and bilateral against
the clean original — the median is the right tool for impulse noise and the numbers say so, not the
picture. *Edges* runs Canny beside `cannyAuto`, whose thresholds come from that image's own gradients,
and ends with the closed outlines as `FLine`s. *Features* plants a square with known corners and a
decoy ring Hough must not fit, then prints how far the worst Harris corner landed from the true
corner. *Morphology* plants three discs of known radius and prints the distance-transform peak against
the largest planted radius. *Motion* warps a frame by a *known* affine transform, tracks it with
pyramidal Lucas–Kanade, fits a homography with RANSAC, and prints how far the recovered model
disagrees with the planted one over a 16-point probe grid — the honest number for this whole family.

![Four panels: frame A, frame B rotated and translated, their absolute difference as an inferno interference pattern, and frame B warped back by the recovered homography — which reads as frame A again](assets/imaging_examples/motion.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/imaging_cv_examples/metadata.edn)

**imaging_vector_kernels** — Three boxes that each time one imaging primitive against a *second,
independent definition of the same thing*, written out longhand in the box. *pyrDown* times the fused
decimation against blur-then-throw-away, and prints the max elementwise difference so you can see the
only disagreement is float summation order. *boxBlur* times the running-sum implementation against
`convolveSeparable(boxKernel1D)`, which shares no code with it — and then prints the two blocks
together, at radius 2 and at radius 24, because read together they say something the O(1)-beats-O(k)
story gets wrong: at radius 2 the *kernel* is faster, and only past a crossover of a few pixels does
the running sum win. A running sum is serial by nature; five taps of separable convolution vectorize
freely. *bilateral* has no cheap reference, so it probes 3 px either side of a disc boundary at
`y = 210` — about one `sigmaSpace`, chosen because probing 30 px out would show a preserved step and
prove nothing — and prints the noise left in a flat patch. The absolute milliseconds here are not the
published benchmark (that is `:tools:imaging-bench` against OpenCV); what these boxes show is the
ratio to the slower definition, which is what the change actually did. See the *Is it fast enough?*
section of [Imaging and computer vision](imaging_examples.md) for the measured comparison.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/imaging_vector_kernels/metadata.edn)

**imaging_visualizers_demo** — Ten boxes with no `showImg`, no `display()` and no `println` in any of
them. Every box's last expression is an imaging value, and what you see is the inline
`ValueVisualizer` deciding how to draw it — which makes this the demo for
[visualizers](visualizers.md) rather than for imaging. `Img` gets an aspect-correct thumbnail with a
channel strip and per-channel histogram behind a disclosure; a signed `sobel().dx` gets a diverging
map with zero pinned rather than at an arbitrary grey; an out-of-range magnitude image gets rescaled
with its *true* range printed instead of appearing as a flat white rectangle. The fourth box is the
regression: one `NaN` block and one lone `+∞` used to poison `min`/`max`, make `normalized()` produce
an all-NaN image, and leave the thumbnail silently black; non-finites are now counted, excluded from
statistics and painted magenta. `List<Corner>` is a scatter sized by strength — check its disclosure
against the corners of the rectangle the box planted at `x 30..90, y 26..76`. `List<HoughLine>` is
drawn twice, once clipped into a canvas the visualizer had to *invent* (the caption admits it) and
once as accumulator peaks. `FlowField` draws failed tracks as grey crosses rather than hiding them,
and `ComponentLabelling` rotates hue by the golden ratio so adjacent label ids get unrelated colours.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/imaging_visualizers_demo/metadata.edn)

**viz_showheatmap** — Four small boxes for the one-line helpers, and the shortest thing on this page.
A Gaussian bump in viridis; a diverging field with `normalize = false` and explicit `vmin`/`vmax`,
which is what anchors the white midpoint at zero rather than at the data's mean; a synthetic
spectrogram with `transpose`, `flipY` and `logScale`, which is exactly the shape the spectral docs
used to hand-roll; and a hand-built ARGB `IntArray` through `showImage`. `showHeatmap` and `showImage`
encode with `PngEncoder` and `display`, so there is no manual bilinear resampling, no flipY
bookkeeping and no AWT.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/viz_showheatmap/metadata.edn)

**audio_ai_codec** — One box for the [EnCodec reconstruction
microscope](audio_ai_codec.md): two waveform players with a shared playhead, two log-frequency STFT
images, and a token map you can hover to read a codebook entry. Be clear about what it is and is not:
the box builds a deterministic fixture in-process — a 4-second chirp at 8 kHz, a "reconstruction" that
is the source at 0.91 gain plus two tones of codec texture, and 4 × 200 token frames — and the metrics
in the header are literals typed into `ReconstructionMetrics`, not measured. That is the point. The
real path reaches an isolated GPU service, and this transcript exercises the real
`CodecVisualization` renderer *without pretending to run EnCodec when the service is absent*, which
is why it can run in CI at all.

![A codec reconstruction card: two waveform players, two log-frequency spectrograms, and a hoverable token map](assets/audio_ai_codec/reconstruction_microscope.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/audio_ai_codec/metadata.edn)

**arclength_spacing_demo** — Two boxes on the difference between spacing a curve by its *parameter*
and spacing it by its *arc length*. A cubic can be perfectly straight and still be parameterised so
unevenly that `t = 0.5` is four fifths of the way along it, and until 2026-07-31 Field's arc-length
table was blind to exactly that case. *Dashes* dashes the same two curves twice — marching `t` in
equal steps down the left column, `byDashing` down the right — and captions each panel with the
shortest and longest dash it actually drew (18.8 .. 25.0 against a requested 22.0 for a mere S-curve;
far worse for the collinear one). *Ticks* puts 24 equal steps of parameter beside 24 equal steps of
arc length with the max/median gap on each (2.821 against 1.000 on the collinear cubic), and returns
the mechanism as numbers: the old subdivision metric reading 0.000 on a curve the new one scores 161.
Both boxes compute both spacings live, so neither picture is stored. Move a control point towards the
middle of the chord and re-run to watch the two columns converge.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/arclength_spacing_demo/metadata.edn)

**close_node_demo** — Two boxes about the `Close` node in the middle of an `FLine`, which is a shape
Field's own stdlib produces and almost nothing handled. *Reversing a closed contour* writes out the
**old** reversal walk inline, beside today's, on a square whose fourth side is the close and on a
two-contour glyph with a counter — and captions every panel with one number, the fraction of the
original length the reversal actually retraced, because a reversal that drops a segment is shorter
than what it reversed and that is the whole story. It also round-trips each reversal twice and prints
whether the node-class sequence came back identical. *The shipped primitive* is the provenance
argument: `arrow(a, b, startHead = true, endHead = true)` from the stdlib is ten nodes with two
`Close` nodes in it, at indices 5 and 9 — so exactly one of them is interior, and no user cleverness
was needed to build one. It runs ten public operations over that arrow
under `runCatching` — reverse, subdivide, simplify, convex hull, filter, winding number, contains,
centroid, bounds, dash — and prints `ok` or `THREW` for each. Every one of them threw
`IndexOutOfBoundsException` on that exact line before 2026-08-01. Both boxes draw with
`showPlaneGrid`, so no AWT and no GPU.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/close_node_demo/metadata.edn)

**dynamic_graph_systems_examples** — [Graphs that change while you
watch](dynamic_graph_systems_examples.md) as six independent boxes: point clouds that move while the
graph joining them is continually reinforced and forgotten. *Six systems* runs all six
`DynamicGraphSystem`s over one 220-point disc for the same 20 frames with the same reinforcement and
ceiling, and tells them apart by the one exact structural fact that separates them — the fraction of
edges that close a triangle, which is 0.0 % for flowline advection (a functional map, one arc per point)
and 100 % for both face-builders, the local tangent fan and alpha-event collapse. It also prints the peak
weight each system reached against the shared ceiling, which is how you find out that the alpha-event
system's area-scaled increment tops out at 0.3 % of it. *Decay* pins both forgetting laws against their
closed forms at 1, 4 and 120 steps (`e^-0.7 = 0.496585304` and `0.300000000`), halves a weight with
`ln 2 / λ`, and expires an edge by `ttl` while it is still at its ceiling — the two independent ways an
edge can die. *Hysteresis* walks two points apart from 0.05 to 0.13, past `rEnter` and inside `rExit`,
and shows the edge surviving where the `rExit == rEnter` control arm loses it; then 150 frames of a
wobbling 200-point disc measure what that buys — 553 edges created and 81 lost against 996 and 637, so
the memoryless arm creates 1.80× as many and loses 7.9× as many and its edge set never accumulates
enough weight to be a memory of anything. *Threshold* is the centrepiece: a static 240-point background
mesh with three rigid 22-point clusters crossing it, where a `minWeight` cut of 1.2 leaves **zero** edges
crossing a group boundary and components of exactly `[240, 22, 22, 22]` — the co-moving groups recovered
out of a graph that has only ever seen positions and a clock. The last two boxes must be **launched**,
not executed: *breathing* rewires a k-nearest-neighbour graph over a cloud that never moves, beside the
identical system with its clock pinned at zero (which holds exactly 590 edges for all 420 steps), and
*morphogenesis* grows a 64-point ring into a branching filament beside the same nine-stage pipeline with
tip growth removed. Every box is pure CPU; no `Window`, no `Renderer`.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/dynamic_graph_systems_examples/metadata.edn)

**autodiff_worked_example** — Field's **automatic-differentiation layer** as five independent
boxes, one per layer of the stack. *Basics* differentiates one expression three ways that have to agree
— a reverse-mode tape sweep, a second-order forward-mode `Dual2` number, and a central-difference
oracle — and plots `f`, `f'` and `f''` from a **single** sweep, because the second derivative comes
free with the forward pass. It also prints the exact 2×2 Rosenbrock Hessian, which is a polarised
`Dual2` result rather than a finite difference of a gradient.

![f, f prime and f double-prime of exp(-x^2) sin(6x), plotted from one forward sweep](assets/autodiff/autodiff_basics.png)

*Optimizers* walks the same Rosenbrock valley three times on the same exact gradients. The picture is
the argument for a line search: L-BFGS plunges past 10⁻²⁴ in about 42 iterations and stops, while
SGD+momentum is still zig-zagging near 10⁻³ at iteration 300 and Adam has barely left 10⁰.

![log10 Rosenbrock loss per iteration for SGD, Adam and L-BFGS](assets/autodiff/autodiff_optimizers.png)

*Fn fit* is the Field-specific bridge, and the reason this is not a generic AD library. `Functions.Fn`
is a closure over `Double` and cannot be differentiated by overloading its arithmetic — but every `Fn`
the stdlib builds carries an inspectable graph, and that graph is re-interpreted in tracked arithmetic.
The box makes the *same* `fitGradient` call twice, once on the historic central-difference gradient and
once with `gradient = FnGradientMode.EXACT`: same optimiser, same step size, same landscape. Both
start at RMSE 0.3605; finite differences ends at 0.0142 after 6801 loss evaluations, the exact
gradient at 0.0096 after 801 — better, for an eighth of the work. It then asks `exactFitRefusal` why a
`saw`-based seed is turned away, because the exact path re-checks its own interpreter against `Fn.eval`
before it will trust itself.

![the target signal, the exactly-fitted signal, and both RMSE trajectories](assets/autodiff/autodiff_fit.png)

*IK* solves inverse kinematics on a four-link `FLine` chain. Forward kinematics is a cumulative angle
sum, so it differentiates cleanly and 18 L-BFGS iterations put the tip dead centre in the target
reticle. The third panel is the smoothness knob, shown as the honest trade it is: the pose straightens
and it *costs* 0.006 units of reach.

![a four-link chain at rest, solved, and solved with a smoothness penalty](assets/autodiff/autodiff_ik.png)

*Pack* packs twelve discs by descending `relu(overlap)²` pairwise plus `relu(penetration)²` against the
walls — worst overlap 48.3 units → 0.35. This is where the library's even-split kink convention earns
itself: at the instant two discs touch, the hinge's derivative is half a one-sided rule's, so the
packing settles instead of chattering across the contact.

![twelve discs before and after a differentiable packing](assets/autodiff/autodiff_pack.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/autodiff_worked_example/metadata.edn)

**morpho_circuit_search** — Three boxes that search for circuits in
[somebody else's language](morpho_examples.md), where one unit of budget is one invocation of
morpho's own compiler on GraalJS. *Does selection beat sampling?* gives random search, hill climbing
and evolution the same generator, the same seed and the same budget on a one-bit full adder — whose
truth table is small enough that correctness is exhaustive rather than sampled — and marks
first-exactly-correct with a dropped tick, because a curve that reaches zero and one that reaches
0.004 look identical and that is the entire question. Two of the three find it; the control does not.
*A recursion, found* evolves an or-reduce, then re-compiles the same source text at 7, 9, 16, 32 and
48 bits — the widths are deliberately odd, because morpho's `SPLIT` gives equal halves only at even
widths and a family of powers of two never shows the search a cell whose halves differ, so it will
happily return a design that is silently wrong at 5. *The recursive adder is not found* is the
negative result, and it is here because the alternative is to quietly not mention it: four drivers,
including one *given* the SPLITs, all flatten well short of correct — and then the box hand-writes the
ripple skeleton in the generator's own grammar and scores it through the same evaluator, so the target
is provably inside the search space. The empty half of that cost plane is the finding.

![Four search drivers on a recursive eight-bit adder, every curve flattening short of zero, beside a cost plane holding only the two reference designs and nothing the search found](assets/morpho_examples/search-adder.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/morpho_circuit_search/metadata.edn)

**keyframer_worked_example** — `KeyframeScope`, the thing that stops a look you spent twenty minutes
arriving at from existing only as the contents of a running process. Four pure-CPU boxes: *targets*
registers two uniform `Structure`s, captures them into a `Key`, edits it, invokes it to write the
state back, and reads the live structures to prove it landed — including the trap that `capture()`
sees a structure's *bindings* and never its *set values*, so a property written by value snapshots as
null in silence. *Persist* shows that every `saveKey` also appends to a `.versions` history, that a
`Boolean` or a list is dropped from a saved key with no warning at all, and how `missingKey` lets a
scope that has grown a parameter still load an older file. *Updatecode* turns a captured look back
into pasteable Kotlin whose literals keep their *types*.

The fourth box is the argument for storing a camera as a `CameraPose` rather than a position: a
dual-quaternion `sclerp` is a screw motion, so it holds the subject at a constant 8.2462 units and
dead centre of frame for the whole move, while lerping the two positions cuts the corner and swings
27.2% closer at the midpoint.

![Left, a plan view: a blue quarter-circle sclerp path with equal-length sight lines to the subject, and an orange straight chord cutting across it. Right, distance to the subject over the move: flat blue at 8.2462, orange dipping to 6.0](assets/keyframer_examples/camera_screw.png)

Walkthrough: [Capturing & replaying live state](keyframer_examples.md).
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/keyframer_worked_example/metadata.edn)

**pattern_rewrite_worked_example** — Six runnable boxes that each hold one
[user-authored rewrite rule](pattern_rewrite.md) and one `println` that could not compile without it.
`neg(x) → -x` is the shape of the whole mechanism. `twice(x) → x * 2` is the precedence guard, and the
reason it is called with `twice(3 + 4)`: a non-atomic capture has to be parenthesised, so the answer
is 14 and not 11. `f(x) → f(wrap(x))` with `where { !isCallTo(x, "wrap") }` is the termination idiom —
wrap once and stop, rather than run into the fixpoint cap. Then a variadic hole, and `unquote`, which
computes the replacement *at compile time* from the matched identifier's text. The seventh box is the
one that is never executed: it holds a rule and nothing else, and a child box wired beneath it
compiles against that rule by inheritance — a control for ambient scope, which only means anything
because the parent stayed un-run.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/pattern_rewrite_worked_example/metadata.edn)

**rewrite_strategies_demo** — Nine boxes on *where and when* a rewrite rule fires, and the only
transcript in the corpus whose boxes `check` their own answers rather than printing them. The
arithmetic is chosen so a number identifies a strategy: helpers multiply by 10, 100 and 1000 or add
1, 2 and 100, so `once` landing on 12 and the baseline landing on 30 are two different rewrite
decisions and not two different sums. Box 1 is the control arm — no `strategy` block, so the historic
global fixpoint rewrites both sites — and every other box's number is a difference from it. `once`
and `subset` both reach 12 by different mechanisms; `topdown` and `bottomup` are exact mirrors on the
same input, 102 against 202; `choice("toY", "toX")` deliberately inverts declaration order to show
the strategy picks, not the source; `repeat(topdown, 2)` leaves the innermost `f` alone; and the last
box's `from { f(f(x)) }` eats two levels per step. The honest bound: each assertion proves this
strategy fired differently from the baseline, not that the rewriter is sound in general.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rewrite_strategies_demo/metadata.edn)

**polytope_conway_demo** — One deliberately compact box for the
[Conway polytope algebra](polytope_examples.md). It marks two opposite cube faces with different
height attributes, selects exactly those faces, applies `kis` only there with the height deployed per
face, and sends the resulting polygonal faces to the on-canvas `threeLines(...)` viewer. Orbit it,
hover a face, and click one: a provenance card reports the output face id, the transform role, the
deployed height, the source elements it descends from, and the 3D hit position. The inspection output
below the view exercises the registered polytope value printers.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/polytope_conway_demo/metadata.edn)

**polytope_conway_gallery** — The same algebra as four small boxes, one question each.
*Expressions* is a Conway-notation contact sheet you retype: change one list and the whole grid
rebuilds, captions and all, read off the topology rather than the pictures. *Abstract dual* takes the
cube's coordinates away and shows the dual's `realization` as `null` and its `toFaceFLines()` and
`triangulate()` refusing with their real exception messages — before a realization policy is applied
and the same object gains eight FLines. *Parameter field* writes a scalar onto each of a
dodecahedron's faces, deploys a selective **and** non-uniform `kis`, and paints the deployed value
onto the pickable output faces. *Realization* jitters a Conway output, relaxes it, and leaves the
solver's own before/after diagnostics — edge-length deviation, planarity error, convergence and stop
reason — as its final value. Every box is pure CPU.
Walkthrough: [Polytopes: topology first, coordinates later](polytope_examples.md).
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/polytope_conway_gallery/metadata.edn)

**polytope_canonical_gallery** — One box with one parameter: a Conway prefix string, `"gD"` as
shipped. It evaluates the expression, canonicalizes it, and draws the two forms side by side — both
gauge-normalized first, so the only difference on screen is shape and not scale or orientation — then
leaves the solver's own diagnostics as text: tangency-radius spread and planarity before and after,
the stop reason and iteration count, and the combinatorial face orbits. Click a face and it reports
its degree, which orbit it belongs to of how many, and its plane's distance from the centre. Change
the string to `"kkC"` to watch the same box report an honest failure: `canonicalize()` does not reach
that one, and the box prints the reason rather than a picture that looks fine.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/polytope_canonical_gallery/metadata.edn)

**polytope_holes_and_chains** — Three boxes for the two things the polytope layer gained in August
2026. *A face with a hole, end to end* builds a cube with a window cut in one face and prints the
consequences: Euler characteristic counting elements against counting cells (a disk with one hole is
`1 - 1`, so a sphere with a window is 1), the manifold check failing with exactly the count of
one-sided window edges, and eight triangles cut from the windowed face where a solid square needs
two. It then runs four operations a holed face refuses — `dual`, `bevel`, `toOff`, and fan
triangulation — under `runCatching` and prints each real exception message, with the literal fallback
`DID NOT THROW` in case one of them ever stops. *The same plate, with the ring and without it* is the
counterfactual, rendered CPU-side by `TriMesh.renderPng` with back-face culling on, which makes the
picture a winding test as well as a hole test: a mis-wound rim triangle would be *missing*. *Every
composite chains now* runs eight composite operators off one ambo — join, needle, zip, ortho, expand,
snub, meta, bevel — prints what it asked for beside what actually ran, and closes with a composite
still refusing a single realization policy. Until 2026-08-05 none of these compiled; needle, zip and
ortho had no named function at all.

![A table listing the windowed face's rings, its two Euler characteristics, the failed manifold check with four one-sided window edges, and four refused calls each with its exception message](assets/polytope_examples/face_with_a_hole.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/polytope_holes_and_chains/metadata.edn)

**ceres_free_variables** — Two boxes on unknowns that are not vertex positions. *A plane per face*
canonicalizes a Conway solid by solving for a unit normal and an offset per face, and solves the same
problem twice — once with the residual written in Kotlin and differentiated on `ceres::Jet` through
the expression VM, once with the hand-written C++ opcode — then prints the largest disagreement
between the two answers and, as a self-check, that every solved normal is still unit, which no
residual ever asked for: the sphere manifold's own doing. *Unknowns that are not points* fits a centre
and a radius to four fixed points from a deliberately terrible start, and checks itself against the
tetrahedron circumsphere computed by Cramer's rule in the same box. Both need the native Ceres
wrapper, which is **not** committed — so as checked out, both boxes take the no-wrapper branch: they
print that it is missing, show a card naming `./gradlew buildCeresRedistributable`, and draw the
*unsolved* seed rather than failing. Worth knowing while reading that branch: an ABI-too-old wrapper
is swallowed by the same `runCatching`, so it is indistinguishable from an absent one at the box's
output.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/ceres_free_variables/metadata.edn)

**rectsl_glsl_sdf** — Two boxes that print. [RectSL](rectsl_cheatsheet.md) scenes lower to a single
`float scene(vec2, float)` GLSL function, and `emitSdf` is a string builder over the compiled
program — no shaderc, no window, no renderer — so these are the cheapest possible check that the
emitter still compiles against the language. The first box lowers a two-track scene and prints the
whole emitted shader, with the counts that make the staging visible: rects unioned, curve functions
emitted, and any fidelity caveats. The second is the constant-folding demonstration: nine copies made
with `array` whose per-copy offsets are constant in time fold to literals rather than nine more
functions, because the stack VM ran once at compile time and `array`, `grid`, selections and the
`{{ }}` escapes are all spent before the GLSL exists. To see the same thing drawn and moving, replay
`rectsl_sdf_live` below.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_glsl_sdf/metadata.edn)

**rectsl_inverse_animation** — Two boxes on the *third* track backend. A RectSL track already knew how
to evaluate itself and how to spell itself as GLSL; `toFn` lowers it to an `Fn` instead, which makes it
differentiable, so a track can be solved rather than authored. The first box prints the symbolic IR with
its unknowns still in it — `ADD(MUL(linear[0.0=0.0 4.0=4.0], ?gain=1.0), ?bias=0.0)` — and is the
clearest look at what `free-param` actually puts in the tree. The second states three waypoints a rect
should pass through, fits the parameters against them on the exact gradient, and rasterises the answer as
three stacked bands: the target, a seed missing every waypoint by 7.72, and the solved arc threading all
three at 4.7e-15. Move a waypoint and re-run to watch it re-solve; move one somewhere
`amp·trig(rate·t + phase) + centre` cannot reach and the caption reports the honest miss instead.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_inverse_animation/metadata.edn)

**faust_voice** — Five boxes around [Faust](faust.md), whose compiler Field ships as WebAssembly, so
nothing is installed to run any of them. *Faust voice* is the one to click: a `#faust` region returned as
the box's last expression, which keeps the box lit while it plays and takes the sound down when you stop
it. The three sliders that appear are not written anywhere in the box — they are the program's own
`hslider` declarations, and because they arrive as an ordinary Field control group they are also what a
tablet gets. *Block diagram* is Faust's own SVG of the signal flow, out of the embedded compiler rather
than `faust -svg`. *Waveform* renders the identical program offline and plots it, which is both a cache
hit and the reason a Faust figure can be honest in a build with no sound card. *The interface it declares*
prints what makes the first box's controls possible: ranges, steps, `[unit:Hz]`, `[scale:log]`, and the
byte `index` in WebAssembly memory that makes a slider a float store rather than a message — listed
alphabetically, because that is how Faust orders them and not how they were written. *An error, on the
line* is broken on purpose: `#` is not a comment in Faust, so the `#faust` marker is stripped before
compiling and its lines are added back before the marker is placed — get that arithmetic wrong and every
diagnostic lands a row or two off, looking like a compiler bug. Only the middle three boxes are replayed
here; the voice wants an audio device and the last one fails by design.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/faust_voice/metadata.edn)

**faust_polyphony** — Six boxes in which [Faust](faust.md) stops being a drone with knobs and becomes an
instrument that a [pattern](sound.md) plays: eight instances of one compiled program, allocated to notes.
*Faust instrument* is the one to click — `faustPoly(…, voices = 8)` played by `inst.play(pat("[c4,ef4,g4] …"),
automate = mapOf("cutoff" to pat("900 2600 1400 5200").nums()))`, so the chords are one pattern and the filter
is another and both are scheduled in frames. Only `cutoff` appears as a slider; `freq`, `gate` and `gain` are
absent because the notes write them, and they are found by *label* because Faust lists controls
alphabetically and index 0 in that program is `cutoff`. *Offline, with a plot* is the same path with no audio
device: 16 notes over 2.25s, 8 cutoff steps, 0 steals, peak 0.317. *Three notes at once* is the box worth
reading twice, because counting attacks cannot tell polyphony from a voice stealing itself — so it counts
spectral peaks in 100–230 Hz, a band that can hold nothing but fundamentals, and gets **three** at four
voices (130.0, 166.2, 196.5 Hz against a notated 130.81, 164.81, 196.00) against **one** at a single voice,
which also reports two steals. *Onsets, in samples* puts every notated event's frame beside the frame the
audio actually attacks on — but it leads with the measurement that has no threshold in it at all: the render
is exactly `0f` between notes, so the first non-zero sample of a note is an exact fact, and it **is** the
notated frame for 13 of 13 checkable notes, worst 0. The scheduler is sample-exact; the detector's own numbers
(14 of 14 found, median 1 frame, jitter ±6, worst 7, against up to 110 if the writes were quantised to the
128-frame block that twelve of the fourteen onsets fall inside) are printed underneath and labelled as the
detector's. It checks `faustSilentGaps` first, because an onset detector pointed at a continuous tone always
finds something. *What it refuses* is a program with no `gate`, and the message it gets instead of silence.
*When it runs out of voices* is the one knob here you can hear: four notes on two voices, rendered at
`stealReleaseFrames = 1` and at `1440`, printing **4.23×** and **0.11×** for the normalised waveform step
across the steal, with both WAVs playable and the line to edit marked. Five of the six are replayed here; the
instrument wants an audio device.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/faust_polyphony/metadata.edn)

## Service-backed composition scenes

<!-- transcript-index: verified-by = execute -->

These are complete, editable starting points whose first view works without model weights. Their
generation boxes deliberately remain explicit about the companion service or hardware they need.

**audio_ai_gesture_board** — Draw timed prompt regions, salience events, and activity, density,
register and brightness curves over one shared musical timeline. The first box renders and explains
the typed sketch immediately. The second resolves a source recording by filename, requests eight
MusicGen-Style candidates, then presents both gesture-fit rankings and the full waveform/splice
audition board. The current worker treats prompt wording as compiled conditioning and the numeric
lanes as waveform-evaluated targets; the UI never disguises either as native timed control.
Walkthrough: [Gesture board: drawing toward a sound](audio_ai_gesture_board.md).
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/audio_ai_gesture_board/metadata.edn)

## visionOS device scenes

<!-- transcript-index: verified-by = kotlinjs, execute -->

These publish to Field4Vision's autonomous Live Metal renderer. Open **Live Metal** in the visionOS
app, then run the box; the program is retained and replayed if the native client connects later. A
visionOS device or simulator is required for the image. Four of the six are Kotlin compiled to
JavaScript for the head, and CI compiles them through the real head profile; `vision_metal_cube` and
`vision_geometry_desk_preview` are JVM Kotlin and CI runs them to completion. See
[Building geometry in the headset](vision_geometry.md) for the walkthrough these are drawn from.

**visionos_start** — The smallest complete Live Metal program, and the place to start: a cube built in
the headset by `geometry { }`, a Metal shader in the box's `msl` property, and one uniform animated on
the compositor clock.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/visionos_start/metadata.edn)

**vision_geometry_shapes** — A tour of the geometry builder's shape primitives: cube, sphere, plane
and tube side by side in one mesh, with a shader that reads the uv the shapes write as a checker.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_shapes/metadata.edn)

**vision_geometry_heightfield** — The builder at its raw level: a ripple height field assembled vertex
by vertex, with normals from finite differences of the same height function and per-vertex colour
carrying relief into the shader. The shape most procedural meshes take.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_heightfield/metadata.edn)

**vision_geometry_animated** — Rebuilding the mesh every frame, which is what separates building
geometry in the headset from shipping it over the wire: a swept tube whose spine and radius both move.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_animated/metadata.edn)

**vision_metal_cube** — The non-Kotlin/JS route, for comparison: JVM Kotlin publishes the shader, the
script and an explicit request for the head's built-in cube in one `visionMetal(...)` call.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_metal_cube/metadata.edn)

**vision_geometry_desk_preview** — The one on this list that needs **no headset at all**: both boxes
build a Live Metal `geometry { … }` for real — compiled to JavaScript and executed — and draw the
resulting mesh into their own output. The second is the same mesh wound backwards, so you can see what
an inside-out mesh looks like and how the preview tells you.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_desk_preview/metadata.edn)

## Browser-head scenes

<!-- transcript-index: verified-by = kotlinjs -->

These are Kotlin compiled to JavaScript and shipped to a **browser head** — the WebXR/Three.js page
at `/extensions/remote_ar/index.html`, which supplies THREE, Tone.js and the Field globals. The
result appears in the head, not in Field, so nothing on this page can show it. CI compiles each box
body through the real head profile its `// {{ marker }}` names; an unknown marker is a hard error
rather than a silent fallback to the wrong head. See [Kotlin in the head](kotlin_in_the_head.md) for
the profiles and [RemoteJS and WebXR](ar_reference.md) for the bound names.

**remotear_start** — The starter: a `// {{ remoteAR }}` box beneath a KotlinJS language root that
adds one cube 1.2 m up and 1.5 m out and spins it on the frame clock. Open the head page yourself
first — this transcript does not open one. `onStop` runs exactly once when you relaunch, which is
what makes editing the box and launching it again a clean hot-replace rather than two cubes.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/remotear_start/metadata.edn)

**remotear_scene_motion** — The larger of the two RemoteAR scenes, and the one that is *not* offered
as a starting point: a metallic torus knot under a directional and an ambient light, an `FLine` ring
built with the transform operators (`+ Matrix4`, `+ Vector3`, `* scalar`) and published to
`stage.orbit`, and a glTF loaded through `mapDirectory` and played on the XR animation mixer. It
deliberately refers to an external `/abs/assets/robot.glb`, which no checkout contains — so it stays
agent-only rather than shipping as a starting point that half-loads. Replay it with your own model
and both paths edited; everything except the glTF works as written.

**tone_synth_sequence** — Computer music in the head, and self-contained in a way the other two are
not: box 1 embeds the head page in the Field document itself as an iframe, so there is no separate
browser window to arrange. Box 3 is `// {{ tone }}` Kotlin — a `PolySynth` into a reverb, an
arpeggio scheduled on the transport at 96 bpm — and it is not offered as a starting point for one
reason: a browser starts its `AudioContext` suspended and only resumes it after a real user gesture,
so `Tone.start()` has to follow a click *inside* the head. The box name says so. There is nothing to
hear in a headless build, which is why CI compiles this one and stops there.

## Graphics scenes

<!-- transcript-index: verified-by = compile -->

These are self-contained scenes that build their own `Window` + `Renderer` and, when run, open a
**native Vulkan window** — so the live image is a desktop window, not an embedded frame. This page
ships no synthesized still of any of them: open the scene and run it yourself, and capture its window
with a follow-on `native-screenshot` backdoor command (with `--with-playwright`). Where a walkthrough
page already carries a real capture of the same scene, the entry links or shows it.

**threebox** — A self-contained renderer + compute + draw particle system: a renderer box that owns
the `Window`/`Renderer` and pumps the loop, a compute box that simulates 100k particles into a
`buffer_reference` buffer it publishes as a prop, and a draw box that reads that buffer and renders.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/threebox/metadata.edn)

**spin_glass** — A 2D Edwards–Anderson spin glass, Vulkan-compute accelerated. A renderer box, a
"metropolis update" compute box (checkerboard / red-black Monte Carlo over Ising spins with
cosine simulated annealing, updating in-place buffers), a "measure" compute box (probes
`vec2(spin, energy)` per site so the stats card's x-mean is the magnetization and y-mean the energy
per spin), and a "lattice view" draw box whose fragment shader colours each cell. Demonstrates a
`renderer → update → measure → view` publish/read chain and a two-dispatch red-black update with a
`computeMemoryBarrier` between passes.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/spin_glass/metadata.edn)

**recstl_playground** — A one-stop [RectSL](rectsl_examples.md) playground: a Window + Renderer, a
hackable `#rectsl` snippet, and transport controls (play / loop / speed / time-scrub) published in
the box's output area. Edit the `rectsl` string and re-run to change it live.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/recstl_playground/metadata.edn)

**rectsl_dataviz** — A loadable demo of the RectSL data-visualization family: a viridis colour-bar
legend built with `array` + `colormap`, and a bar chart whose heights and colours are both bound to a
numeric list via `data` / `normalize` / `bars`, animated in with a staggered eased `->alpha`.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_dataviz/metadata.edn)

**rectsl_plot_axes** — A loadable demo of the RectSL plotting layer: a shared coordinate `->domain` +
`->frame` with `axes` (rules, nice-number ticks, numeric labels drawn as rect numerals, gridlines) and
three series overlaid in the one domain — an `area`, a `line`, and `points` — plus a `colorbar` and a
discrete swatch `legend`.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_plot_axes/metadata.edn)

**rectsl_musicality** — A RectSL scene on a musical clock, and nothing in it is written in seconds:
the wall declares `->tempo 120` and `->beats-per-cycle 4`, and the tracks are written in
mini-notation. The identity it demonstrates is worth stating plainly — a RectSL track is `sample(t)`
and a pattern is `events(arc)`, both pure functions of time, so a pattern *is* a track, and a pattern
event carries an onset and a real duration, so an event *is* a life window. `pat` is `array` indexed
by rhythm instead of by ordinal: it spends its subdivision on *when*, where `strip` spends it on
*where*. A kick on `"bd*4"` drops in on an ease-out and fades; hats are a Euclidean 5-in-16 ring;
`->every` keeps both versions of a track and chooses per sample. See the *Musicality* section of
[RectSL examples](rectsl_examples.md) for the same program with figures.
[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_musicality/metadata.edn)

**rectsl_sdf_live** — The drawn counterpart to `rectsl_glsl_sdf`: the same lowering, but the emitted
`scene()` goes into a generated fullscreen-triangle pipeline and is drawn per pixel, so the trade
becomes visible. Distance buys outlines and glows at any width, `smin` blending *between* rectangles,
boolean ops, per-pixel normals and sub-frame motion blur — in exchange for fill, material and z
order, because `min` is union and a union has no layers. The look is one of eight presets (`fill`,
`outline`, `glow`, `contours`, `relief`, `blend`, `carve`, `motion-blur`); this one ships on `relief`.
There is a documented footgun in the box, left in on purpose: a top-level `val fragment` in a box is a
script *property*, so naming the shader source `fragment` self-assigns the receiver's empty
`fragment` and hands shaderc nothing, which reports itself as "Desktop shaders for Vulkan SPIR-V
require version 140 or higher" — a message about nothing. Hence `fragmentSource`.

![A RectSL scene drawn as a distance field under the relief look: six rotating squares lit by per-pixel normals over a plain union](assets/rectsl/rectsl-sdf-relief.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/rectsl_sdf_live/metadata.edn)

**imgui_vr_panel** — [Dear ImGui as an in-VR panel](imgui_vr_panel.md), in four working lines on top
of the renderer box: a side-by-side stereo target, the demo panel, and a mouse-driven mock controller.
The ImGui frame goes into a 2048×2048 render-to-texture, onto a world-space quad 1.5 m out, rendered
once per eye — so the panel has real stereo depth rather than being pasted flat — and the packed
side-by-side image is blitted to the desktop window. **No headset required**: the mouse aims the ray
and clicking presses. On Windows and Linux the same image feeds real eye swapchains, which this
transcript does not exercise.

![The full packed side-by-side stereo frame: the ImGui panel rendered twice, one per eye, against black](assets/imgui_vr_panel/imgui-vr-panel.png)

[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/imgui_vr_panel/metadata.edn)

## Graphics building blocks

<!-- transcript-index: verified-by = compile -->

Neither of these two is a scene, and neither is offered as a starting point: one is a renderer with
nothing to draw, the other is a child box that gets its renderer from a parent it does not contain.
They are in the corpus because they are the two halves of the graphics-topology rule, and an agent
composing a new scene needs both — see `dev_docs/agents/transcripts/README.md`.

**graphics_renderer_launch** — The bare parent: open a `Window`, build a `Renderer`, pump the loop.
Nothing is drawn. Replay it when you want a renderer for boxes you are about to write, then wire
those boxes beneath it so they can reach it through graph context.

**afield_four_traditional_triangle** — The other half: a single draw box with no renderer of its own,
which calls `fromContext()` and expects an `agent_graphics_context` parent to have supplied one.
Replayed alone it has nothing to draw into — that is the rule it exists to demonstrate.
