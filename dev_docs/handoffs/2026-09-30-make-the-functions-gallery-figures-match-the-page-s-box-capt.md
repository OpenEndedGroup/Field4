# Make the Functions gallery figures match the page's box captures

- **Handoff:** `84a9cacf-a2bd-43ed-ab09-d16db89c575a` (project)
- **Filed from:** https://openendedgroup.com/field4/functions_examples/
- **Filed by:** agent:claude, 2026-09-30 20:41
- **Close with:** `python tools/docs_notes.py handoff-close 84a9cacf "what happened"`

## The notes that led here

- `33bfd2c3` (public/docs/functions_examples.md:192): why are some images on this page on a blue background?
  > The capture above is the endpoint. The plainer case (a single fitGradient with no restarts, on a target whose amplitude and offset are wrong but whose frequency is right) shows all three curves at once: the target, the seed it started from, and where it ended up.

## What was asked

On `public/docs/functions_examples.md` ("Watching the descent"), the human asked: *why are some
images on this page on a blue background?*

## Why the images look different

Five figures on the page are not Field captures. They come from a separate headless AWT
renderer that uses its own palette:

- `public/docs/assets/functions_gallery/functions-fit-before-after.png` (page, "Watching the descent")
- `.../functions-fit-convergence.mp4` (same section)
- `.../functions-waveshaper-transfer.png`, `.../functions-waveshaper-on-sine.png`,
  `.../functions-composed-pipeline.png` (waveshaper and pipeline sections)

They are drawn by `afield.examples.functions.FunctionsPlotGallery`
(`src/jvmMain/kotlin/afield/examples/functions/FunctionsPlotGallery.kt`, colour constants near the
top: `BG = Color(20, 22, 28)`, `PANEL = Color(28, 30, 38)`, `GRID = Color(48, 52, 62)`). That
blue-grey slate, the gridlines and the legend box are the harness's own chart style. The
FIELD_TEST `functions.plot-gallery` produces them, `dev_docs/FUNCTIONS_PLOT_GALLERY.md` describes
them, and `dev_docs/docs_capture_owners.json` records who owns them (regenerate with
`FUNCTIONS_PLOT_DIR=... run_field_tests.py --id functions.plot-gallery`). All the other figures on
the page are `diffstatic_runner` box captures, black canvas, of `lines(...)` / `Fn.plot(...)`
output. The two styles sit next to each other on the page, and the reader sees the difference
without knowing why it is there.

This needs a change to the capture harness or new captures, and a docs-only edit cannot do
either. The one prose alternative, an aside explaining the provenance, would explain the mismatch
and leave it in place.

## Proposed approaches

1. **Re-capture as box figures (preferred).** Move each of the five into a `diffstatic_runner`
   fence on the page that computes the same thing (`fitGradient` before/after; the waveshaper
   curves; the composed pipeline) and draws it with `Fn.plot` / `Box.plot` or `lines(...)`, as the
   `fit_to_target_box.png` figure already does. Those figures would then match the rest of the page
   and be recaptured with it. The convergence video is the hard one: it needs a frame-per-iteration
   capture that the docs runner can drive, or it stays as a harness asset.
2. **Restyle the harness.** Change `FunctionsPlotGallery`'s palette to match Field's box plot
   (black background, the same axis and text colours as `Fn.plot`'s SVG) and regenerate. This is
   less work, but the page still has two renderers that can drift apart.
3. **Keep as is and explain.** Add a `??? aside` saying these are harness captures. This is the
   cheapest option and changes nothing the reader sees.

## Questions for the human

- Should Functions figures on the docs site all come from box captures, or is the AWT harness an
  acceptable second source if it is restyled?
- Is the convergence video worth keeping if it cannot be produced by the docs runner?

## What the page needs afterwards

Swap the five image/video references on `functions_examples.md` for the new fences or
regenerated assets. Check that the prose colour cues still match ("thick orange target", "cyan
fitted", "dashed grey seed" in the alt text and in the paragraph after the video). Update
`dev_docs/docs_capture_owners.json` if ownership moves to the docs runner.
