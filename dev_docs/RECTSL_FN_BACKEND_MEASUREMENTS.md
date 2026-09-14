# RectSL differentiable backend: agreement with the sampler

Displaced from `public/docs/rectsl_inverse_animation.md`, which is a page about declaring an
unknown and solving for it. These are the residuals behind its one remaining sentence about
agreement.

`RectSLFnBackendTest` is the owner and its KDoc is the detailed account — in particular of why the
characterization corpus's two halves are not equally good evidence. This note is the numbers.

## The oracle

`src/jvmTest/resources/rectsl/track_characterization.txt` is a double-precision capture of every
sampled rect of the gallery's 38 programs at 41 times each, and it shares no code with the
differentiable backend. The backend recomputes each rect's nine numeric channels and is held to
that capture.

| measurement | value |
| --- | --- |
| gallery tracks that lower | 4993 of 5020, with all 27 refusals pattern tracks |
| channel values compared | 578,059 of 580,068, across all 37 compilable programs |
| worst relative disagreement | 7.7·10⁻¹⁴ (a `cascade-split` rotation) |
| worst absolute disagreement | 8.3·10⁻¹⁴ |
| easings lowered | 35 of 38, worst disagreement 8.9·10⁻¹⁶ over a 1001-point grid |
| exact gradient vs central difference | worst 7.1·10⁻¹¹ over twenty-one parameterised shapes; 6.8·10⁻⁹ for a heading |
| the exact step, at its own boundary | exact, including at `-0.0` |
| a looped clock across 4.5 periods | bit-identical (0.0); ping-pong 8.9·10⁻¹⁶; a hold track 5.6·10⁻¹⁷ |
| a heading round a winding circle | bit-identical at 4001 of 4001 samples, across two branch-cut crossings |
| a heading whose velocity vanishes | bit-identical at 801 of 801, with 400 needing a wider probe than the first |
| the guarded `/` and `smoothstep` | bit-identical at 2001 of 2001 each, including the sample where the guard fires |

**The first row is asserted as a classification, not as a count.** A pattern track is the only
thing that may refuse; refused and pattern-built have to be the same set, checked in both
directions from the track's own structure; and that set has to be non-empty, since a predicate
matching nothing would pass the other two vacuously. A refusal for any other reason fails the
build, and so does a `pat` the backend claims to have lowered. The 4993 and the 27 are *printed*,
so treat them as a snapshot of the gallery on the day, not as a guarded figure — `RectSLFnBackendTest`
says the same at its own line 266.

## Why the gradient needs its own oracle

Agreeing on values proves nothing about a derivative: a second interpreter can reproduce `eval`
everywhere and still differentiate the wrong function. The gradient is therefore checked against a
central finite difference, which is its only oracle that does not involve trusting the gradient.
7.1·10⁻¹¹ is the finite difference's own truncation error at a 1e-6 relative step, not the
gradient's.

## The characterization corpus, and the transcription inside it

The golden is 64,490 lines and `RectSLFnBackendTest` reads it as data. Its first 37,057 lines were
generated from pre-refactor code, so for those rows the oracle came out of a generator nobody can
run today. The remaining rows cover the four musicality examples and come from the current sampler,
which shares no code path with the symbolic IR either but is not independent of a sampler bug.
**Read which half a disagreement lands in before deciding what it proves.**

One transcription sits in the arrangement: the differential test has to compose group and rect
transforms somewhere, and the `Fn` backend is scalar-only, so that composition is copied from
`SceneProgram.sample`. A companion test runs the same copy with the *original* tracks and requires
bit-equality with the golden across all 580,068 channel values, which is what stops the copy from
rotting.

## Open

- The gallery figure on the published page is a CPU raster, not a GPU capture, and nothing compares
  the two. `dev_docs/RECTSL_SOFT_RASTER.md` owns the raster; an agreement check between the raster
  and the shader would close the last gap between what the page shows and what a GPU run produces.
