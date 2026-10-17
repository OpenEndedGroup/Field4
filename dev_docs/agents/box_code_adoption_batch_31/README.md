# Clear vanished shared-memory detections and show the quad winding

Baseline `9708e07ed`. Four findings addressed in the shared-memory and webcam YAML examples:

- `062f48a4f074-01` / `9e657e3b1e5f-01`: empty segments resize the overlay mesh to zero before continuing, so old detections disappear. Both the stored and explicit launch copy in shared_memory are updated.
- `062f48a4f074-02`: the half-width comment correctly describes four pixels across a 1000-pixel window. Native measurement agrees with width * viewport height for horizontal lines.
- `9e657e3b1e5f-02`: each quad corner is one pos.add call and each triangle is one tri.add call, retaining coordinates and winding. The same readable layout is applied to the paired shared-memory example.

The actual shared buffers are published through NumpyShared.SharedLines, using its real back buffers, publish() and snapshot consumption. Each actual overlay runs under a real native renderer through initial-empty, one horizontal line, empty, and a new line elsewhere, then is relaunched. Vertex/triangle counts are 0/0 or 4/2 as appropriate. Native captures contain no green pixels at the sampled centre column in either empty phase; the two nonempty phases show the line at different positions. At 2524 image pixels high, the 0.004 overlay occupies 10 rows and the 0.003 overlay 8 rows, within two raster pixels of the expected width. Line and cleared captures are inspected. Renderers are stopped afterward.

This is a real shared-buffer/native-consumer test with controlled publications. It does not claim to exercise a webcam, OpenCV detectors, Python's NumPy writer, camera textures or inset views.

Actual import/ktfmt formatting covers three Kotlin overlay occurrences (two distinct bodies), one collection conversion, and four GLSL properties. Parsed checks cover all 13 code properties, preserving neighboring Python and all foreign code byte-for-byte. The CLI idiom corpus excludes python/fieldcv/examples; audit.py therefore calls the actual check_block engine on the three selected Kotlin bodies, which pass the idiom/collection audit. The empty CLI result is not counted as coverage.

The first rehearsal's repeated cached inspection fragment reported an uninitialized Mesh although the native capture was already empty. Phase-specific inspections and the final complete rerun pass; failed evidence is retained without treating it as a renderer regression. verification.json / idiom-verification.json record source and idiom checks; evidence.tar.gz retains actual publications, numerical pixel checks, images and rehearsal results. No fake or replacement capture is added to public documentation.
