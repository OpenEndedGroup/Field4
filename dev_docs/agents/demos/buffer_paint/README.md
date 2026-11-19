# buffer paint

Two boxes for `BufferCanvas` (`src/jvmMain/kotlin/afield/media/image/BufferCanvas.kt`; see
`public/docs/visualizers.md`, "Painting into a live buffer"). Launch box 1: a Gray-Scott simulation
runs for 2200 steps and edits its own state as it goes, with a snapshot after each edit in the
`edits` channel. Run box 2: a delay line pasted and motion-blurred in place, with *before* and
*after* players. Built by replaying `dev_docs/agents/transcripts/buffer_paint.yaml`. After citrons,
"Paint in the memory of other programs" (https://citrons.xyz/journal/2026-04-13).
