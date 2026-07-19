# Value visualizer: ownership and verification

The current user tour and real captures are in `public/docs/reading_your_values.md`. The rendering
contract and limits belong on `ValueVisualizer` in
`src/jvmMain/kotlin/afield/visualization/ValueVisualizer.kt`; graphics-specific registrations live
in `afield/four/FourVisualizers.kt` and synth registrations in `afield/four/synth/Visualizers.kt`.

Extension points:

- implement `ToHTML` for a type-owned representation;
- call `registerValuePrinter<T> { ... }` for a process-wide external formatter;
- call `Box.viz(channel, value)` to display, or use `value.h` to obtain the HTML.

`ToHTML` and registered printers return trusted HTML. Built-in textual branches escape values. Keep
that distinction explicit when adding formatters.

Verification sources:

- `src/jvmTest/kotlin/afield/LinalgThumbnailVisualizerTest.kt`
- `dev_docs/viz_demo.kt`
- `dev_docs/agents/transcripts/linalg_thumbnails_demo.yaml`
- the docs-runner fences and captures in `public/docs/reading_your_values.md` and
  `public/docs/vector_algebra.md`

When a type, limit, palette, or thumbnail changes, update the owning KDoc/tests and regenerate the
real public captures. Do not restate private constants in this page.
