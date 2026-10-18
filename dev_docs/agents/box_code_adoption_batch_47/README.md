# Clear the remaining CV overlays and use the module mesh builder

Three frozen findings addressed: `ed3814a2e496-01`, `2ef873951f77-01`, `2ef873951f77-02`.

Both remaining published consumers resize their mesh to zero when shared detections disappear. The docs camera quad is declared inside moduleHere with +Mesh; the overlay declares its named mesh there too, then gets the actual mesh from the module pointer's current module for per-frame updates. Shader/module names and all geometry/UV/colour arithmetic stay unchanged. The half-width comment now correctly says four pixels across a 1000-pixel window.

## Real behavioral evidence

One fresh private Field on 9718 drives the actual matching native renderer. The original and rewritten camera quad consume a real SharedImage published with a controlled 4×4 test pattern; before/after native pixels are identical.

Both actual overlay programs consume real NumpyShared.SharedLines through initial-empty, one-line, cleared and resumed publications. Eight native phases verify zero or four vertices and zero or two indices, and actual green pixel rows are absent when empty and match width × viewport height within two raster pixels when populated. Both full relaunches consume the current line. The line and cleared images are visually inspected. Renderer stopped afterward; this JVM is now CPU-only.

These are controlled buffer publications, not a webcam/OpenCV detector or Python-writer replay. No diagnostic input or image replaces the published film capture. The existing public figure is retained because it already runs the corrected shared_memory source from batch31.

Actual testing caught and repaired two mistaken mesh-access expressions (standalone Mesh(String) and meshes on ModulePointer); current code uses module.current.meshes. An initial harness set/value operation also used the wrong transcript field and was replaced with the durable file-based property edit. Failed verdicts remain in evidence. Completed phases were reused; final page verification ran against final source, and the transcript's final collection-literal source was separately replayed.

## Mechanical coverage

Actual import/ktfmt covers three Kotlin bodies, including one collection-literal conversion. Token/container and raw-string checks compare the approved edit with final code. The mixed transcript's Python programs and nonselected source properties remain byte-for-byte intact; Python was never sent to the Kotlin formatter. Existing GLSL is unchanged. No duplicate full guard is run locally; landing owns those checks.

behavior.json points to the successful count verdict for every native phase. evidence.tar.gz retains real JSON verdicts, native images and diagnostic logs, excluding the bulk managed-server log. Sweep checkpoint: 315/405 addressed, 90 open; mechanical coverage remains incomplete.
