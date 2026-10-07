# Evolving a Shader Toward a Picture

Give Field a small picture and it will search for a StackSL program that draws something like it. The search is genetic programming over the program's words: it mutates, recombines and keeps the programs whose renders look closest to the target, rendering every candidate on the CPU, with no window and no GPU. What you get back is a short StackSL program you can paste into a shader box.

It finds a program that looks like the target more often than random programs do. It does not find *the* program, and on some targets it finds nothing that looks much like the target at all. The [last section](#what-beating-the-control-means) says exactly what the numbers mean.

## Evolve toward a target

`evolveStackSLTowards` runs the search and, beside it, a random control: the same number of programs drawn at random from the same vocabulary, scored the same way. `StackSLTargets` has five built-in 32×32 targets. This box asks for four flat quadrants:

```diffstatic_runner {data-lang=kotlin screenshot="assets/stacksl_evolve/quadrants.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1100" timeout="600" name="stacksl-evolve-quadrants"}
:::static
val run = evolveStackSLTowards(StackSLTargets.quadrants(32), budget = 400, selection = Selection.LEXICASE)
print(run.summary())
print(run.best!!.text)
showImg(run.contactSheet(), caption = "the target, the evolved program, the random control's best")
```

It takes about a minute. `budget` is the number of programs each side gets to render; doubling it roughly doubles the time. The printed line gives both sides' scores as *skill*: 0 is what a flat fill of the target's average colour would score, 1 is an exact match. The contact sheet draws both programs at 160 pixels, so you see the program itself rather than an enlarged thumbnail of what the search scored.

`run.best!!.text` is the program. It is ordinary StackSL and runs anywhere StackSL runs, including `StackSLCpu.renderStill(text)` and a GPU shader box.

## Your own picture

`StackSLTarget.of` turns any `Img` into a target, scaling it down to 32×32:

```diffstatic {data-lang=kotlin}
val target = StackSLTarget.of("logo", imgFromFile("/path/to/logo.png"))
val run = evolveStackSLTowards(target, budget = 800, selection = Selection.LEXICASE)
showImg(run.contactSheet())
```

Simple, high-contrast pictures work best: flat shapes, a few colours, structure larger than a few pixels. A photograph is a target the vocabulary has almost no way to draw.

## How "close" is measured

The default score is LPIPS, a learned perceptual distance (`Lpips.distance(a, b)` in [`afield.onnx`](reference/machine-learning.md)): both pictures go through the convolution stages of an image-recognition network, and the differences between their features are weighted by a layer trained on people's judgements of which images look alike. It compares edges, blobs and textures, not pixel values. The first time it runs on a machine Field builds the model, which takes about a minute.

`metric = StackSLMetric.PIXEL` scores by average pixel error instead. Run on the same target with the same budget, the two find very different programs:

```diffstatic_runner {data-lang=kotlin screenshot="assets/stacksl_evolve/bands-metrics.png" wait="1.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1100" timeout="900" name="stacksl-evolve-bands-metrics"}
:::static
val target = StackSLTargets.bands(32)
val pixel = evolveStackSLTowards(target, budget = 400, metric = StackSLMetric.PIXEL)
val lpips = evolveStackSLTowards(target, budget = 400, selection = Selection.LEXICASE)
print("pixel: " + pixel.best!!.text)
print("lpips: " + lpips.best!!.text)

:::fold draw the target and both winners side by side
showImg(
    montage(
        listOf(
            "target" to target.raster.toImg().upscaleNearest(5),
            "pixel error" to pixel.objective.render(pixel.best!!, 160).toImg(),
            "LPIPS" to lpips.objective.render(lpips.best!!, 160).toImg()
        ),
        columns = 3, cellSide = 170
    ),
    caption = "six yellow and blue bands, and the best program found under each score"
)
:::endfold
```

Average pixel error is satisfied first by getting the target's overall colour balance right, and a search finds that long before it finds the layout. A program that draws mostly the background colour, with any texture at all, scores well against a target that is mostly background. LPIPS gives no credit for that, so the search has to find edges and shapes where the target has them.

`selection = Selection.LEXICASE` matters with LPIPS. LPIPS compares features wherever they are in the picture, so on its own it accepts the right texture in the wrong place. Under lexicase each quadrant of the picture is scored separately, and a program is kept when it is the best on some quadrant, so the search has to get each part right. `StackSLMetric.LPIPS_PIXEL` averages the two scores, for when exact colours matter more than shapes.

## Options

| parameter | default | what it does |
| --- | --- | --- |
| `budget` | 600 | programs rendered by each side (search and control) |
| `seed` | 1 | the same seed gives the same program |
| `metric` | `LPIPS` | `PIXEL`, `LPIPS` or `LPIPS_PIXEL` |
| `selection` | `TOURNAMENT` | `LEXICASE` scores each quadrant separately (LPIPS metrics) |
| `genome` | `StackSLGenome()` | the vocabulary and program length, 3 to 24 words |
| `supersample` | 1 | render each candidate at 2× and average down, at four times the cost |

## What beating the control means

Every result is reported against the random control at the same budget, because a vocabulary this expressive produces plausible pictures by chance. A search that only matched what random drawing finds would look identical from outside.

!!! note "Beating the control is not resembling the target"
    "Evolution beat the control" means the evolved program **scores** higher under the metric the search optimised. It does not mean the picture looks like the target. Under average pixel error, evolution beat the control on four of five targets while drawing dark diagonal streaks for a yellow disc and a vertical gradient for four quadrants. Look at the contact sheet before believing a score.

`StackSLEvolveGallery` (the `stacksl.evolve.gallery` smoke test) runs the comparison across several targets and checks resemblance separately, with a measure neither search optimises: each pixel of a render is labelled with the target colour it is nearest, and the labels are compared with the target's own (`StackSLLayout.layoutAgreement`, a Cohen's kappa: 1 is exact, 0 is chance). RESULTS_TABLE

??? aside "How the score came to be LPIPS"
    The first version of this search (2026-09-06) scored by average pixel error and produced the dark streaks and gradients described above. A home-made structural score (multi-scale SSIM plus colour error) was tried next, on 2026-10-07: it produced a white disc for the disc target and bands for the bands target, but its winner for the rings target was fine stripes that only looked like rings at 32 pixels. It was replaced with LPIPS rather than tuned. LPIPS is a published, learned metric with a reference implementation that Field's export is checked against (`tools/export_lpips_onnx.py`; agreement 1.2×10⁻⁷).

    Two other things came out of that work. The blank-picture guard used to reject any render with fewer than four distinct colours, which also rejected every exact two-colour picture, including four of the five targets themselves. It now requires two. And LPIPS barely tells a picture from its negative (0.98 similarity for the rings target against its own inverse), which is why the layout check compares colours and LPIPS does not.

??? aside "Why the targets are made in Kotlin, not taken from the StackSL gallery"
    A gallery still is a picture some StackSL program already makes, so searching for it would be a program-recovery task, and a good score could mean either "the search works" or "it rediscovered a short program from its own vocabulary". The built-in targets are drawn with arithmetic StackSL has no single word for. Each one can still be drawn by a hand-written program of 7 to 19 words, which the smoke test checks, so a target the search fails on is one it could have reached.
