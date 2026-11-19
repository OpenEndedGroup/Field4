# Buffer paint — editing a live buffer, seeing it unscaled

`afield.media.image.BufferCanvas` (`src/jvmMain/kotlin/afield/media/image/BufferCanvas.kt`) is a 2-D
window (offset, rowStride, channels 1–4, wrapX/wrapY) onto a `FloatArray`, `FloatBuffer` or
`ByteBuffer` (`U8`/`I32`/`F32`). It copies nothing; `paint`, `stroke`, `fill`, `copy`/`paste`,
`clone`, `blur`, `motionBlur`, `smudge` and `mapInPlace` write into the storage. User docs:
`public/docs/visualizers.md` "Painting into a live buffer". Demo: transcript
`dev_docs/agents/transcripts/buffer_paint.yaml`, saved document `dev_docs/agents/demos/buffer_paint/`.
Idea credit: citrons, "Paint in the memory of other programs" (2026-04-13).

## Decisions

- **Display is 1 element : 1 pixel** (`zoom` whole numbers only). The existing `Img` visualizer
  (`ImagingVisualizer`) downsamples to a long edge and auto-ranges, which is right for photos and
  wrong for raw memory, so `BufferCanvas.toHTML` does not delegate to it.
- **No silent rescaling.** `ValueMap.Raw` (default) shows 0..1 and *marks* the rest — blue below,
  red above, magenta NaN/inf — and the caption counts each. `Linear(lo, hi)` / `Signed(m)` are
  explicit and always named in the caption. Bytes read as 0..255, ints as themselves.
- Clipboard type is `Img` (so `copy` gives 3 channels for a 2-channel canvas).
- Names `CellRect` and `BufferElement` avoid clashing with the several `Rect` types already
  reachable from box code.

## Measured (2026-11-19)

- `src/jvmFastTest/kotlin/afield/media/image/BufferCanvasTest.kt`: 10 tests (stride/offset,
  paint opacity, exact copy/paste round trip incl. out-of-range, blur mass conservation on a
  wrapping canvas, motion blur direction, ring wrap in time, smudge carry, map marking, U8/F32/vec4).
- Delay line (22050 samples, 440 Hz pluck, 61-sample motion blur = moving-average low-pass): pluck
  RMS 0.244 → 0.122. Pasting a 0.7-opacity copy of pluck 1 into silence: 0.000 → 0.171.

## Not done

- Pointer / drawing-layer driven painting (edits are box-code calls only).
- GPU storage buffers: works on a mapped staging `ByteBuffer` in principle (absolute indexing, no
  position changes), but no demo against a live `Renderer` buffer or readback was built or run.
- The `<audio>` players render, but playback was not listened to in a headless browser (duration
  showed 0:00 there); the audio effect is verified by RMS only.
