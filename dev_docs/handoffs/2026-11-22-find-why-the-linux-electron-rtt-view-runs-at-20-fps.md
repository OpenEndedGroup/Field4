# Find why the Linux Electron RTT view runs at ~20 fps

- **Handoff:** `a6224d9d-dacf-46a6-96f4-dfabb234ac03` (project)
- **Filed from:** https://openendedgroup.com/field4/four_in_output_areas/
- **Filed by:** agent:claude, 2026-11-22 10:14
- **Close with:** `python tools/docs_notes.py handoff-close a6224d9d "what happened"`

## The notes that led here

- `4ef77964` (public/docs/four_in_output_areas.md:80): we should figure out why linux only gets 20fps here
  > The Linux view also passes frames through a shared GPU image ring, with no CPU readback or video encoding. On an NVIDIA RTX 4090 (driver 610.43.02, Electron 44.5.1) the 640×360 view showed about 20 frames a second, with roughly 0.8 ms from the frame's GPU fence to the canvas draw. The same moving square, in the Linux app:

## What was asked

The note on `public/docs/four_in_output_areas.md` ("On Linux") asks why the Electron RTT view on
Linux shows about 20 frames a second while the same 640×360 view runs at the renderer's rate on
Apple silicon. The page reports ~0.8 ms from GPU fence to canvas draw on an RTX 4090 (driver
610.43.02, Electron 44.5.1). So the GPU-to-panel handoff is fast, and the time is going somewhere
else: frame pacing, the producer side, or the browser's draw/present path.

## Why it is bigger than a docs edit

Answering it means profiling on a Linux/NVIDIA Wayland machine and probably changing code. That is
engineering work, and it cannot be done from this macOS docs pass.

## Where to look

- `src/jvmMain/kotlin/afield/media/stream/ElectronRTTView.kt`: the view, its frame ring and how
  it paces frames.
- `src/jvmMain/kotlin/afield/graphics/texture/LinuxDmaBufExport.kt`: the dma-buf export path.
- `src/jvmMain/kotlin/afield/media/stream/LinuxElectronImportProbe.kt`,
  `src/jvmMain/kotlin/afield/media/browser/LinuxExternalBrowserSurface.kt`: import on the
  Electron side.
- `src/jvmTest/kotlin/afield/media/browser/ExternalBrowserFrameMailboxTest.kt`,
  `src/jvmTest/kotlin/afield/app/diagnostics/FrameProfileTest.kt`: existing instrumentation.

## Proposed approach

1. On the Linux machine, record per-stage timestamps over a few seconds: renderer frame produced,
   export/fence signalled, notification reaching Electron, import, canvas draw, compositor present.
2. Check for a fixed wait: a timer or poll interval near 50 ms, a mailbox that drops frames while
   an import is still in flight, or a synchronous fence wait on the renderer or main thread.
3. Check whether the cost is per-frame import (making a new EGLImage/texture for every frame rather
   than caching one per ring slot), or Chromium's Wayland vsync/throttling for a canvas that is
   not focused.

Alternatives: compare Electron flags (GPU rasterization, no frame-rate limit). Or measure a bare
Electron dma-buf canvas outside Field, to separate Field's cost from Chromium's.

## Questions for a human

- Which Linux machine/session should be the reference, and is 20 fps reproducible today?
- What is the target: the renderer's own rate, or 60 fps?

## What the page needs once it is done

Replace the 20 fps figure in "On Linux" with the new measurement. If a limit remains, give its
cause in one sentence.
