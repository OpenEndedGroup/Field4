# Tetra (madp `tetra.html`, `tetra2.html`) → Field·4 starting point

The madp demo takes four pictures (or, in the unlinked `tetra2.html`, three pictures and one line of
text), embeds each with CLIP on a local Python server (`server.py` + `clipme.py`: jina-clip-v2,
truncated to 512 dimensions and L2-normalised), computes the six pairwise distances, and runs
classical MDS to three dimensions. The page centres the four points, scales the farthest to radius
3, and draws them in three.js as four coloured spheres joined by six edges, with HTML labels for the
point numbers and the edge lengths, a pair table with bars, an orbit control and a slow spin. While
the inputs are newer than the picture, the diagram is greyed out as "outdated". `tetra2` adds a
small diamond at each end of each edge: for every point, its three edges ranked closest / middle /
furthest in green, orange and red.

The teaching point: **any four points in a Euclidean space sit exactly in 3D**, so the tetrahedron
is not a projection that loses something, it *is* the 512-dimensional geometry of those four
embeddings. `tetra2` makes the image–text gap visible: the text point sits far from all three
pictures, even the one it describes, and its flags show which picture it is nonetheless closest to.

## Shape of the port

All of it runs in Field. The student's box holds the pipeline, top to bottom:

```kotlin
val inputs = mediaSlots {
    image("public/docs/assets/madp_tetra/tabby_cat.jpg")
    ...
    tray("public/docs/assets/madp_tetra")     // drag any of these onto a slot
}
inputs.onChange { slots ->
    val vectors = slots.map { it.clip() }                    // CLIP, image or text, unit length
    val simplex = embeddingSimplex(vectors, slots.map { it.label })   // chord distances + classical MDS
    showSimplex(simplex, colors = slots.map { it.color })     // three { } view
}
```

- **Embedding:** Field's ONNX CLIP (`afield.ai.inference.onnx.Clip`, ViT-B/32, 512-d, already
  unit length). Not jina-clip-v2, so the numbers differ from the madp page; the geometry lesson does
  not depend on the model, and the image–text gap is if anything wider on ViT-B/32 (`Clip.kt`
  measures image–text cosines near 0.21). An aside on the gallery entry says so.
- **Distances:** chord (Euclidean between unit vectors, `sqrt(2 - 2 cos)`), which is the distance
  the madp page draws, plus cosine distance carried along for the table.
- **MDS:** the existing `smileMdsDistances(d, dimensions, positiveCorrection = false)`
  (`afield/math/statistics/smile_mds.kt`) is exactly classical MDS (double-centred `-½ D²`, top
  eigenvectors scaled by `√λ`), with a LAPACK fallback for macOS. No new `classicalMds` is needed.
  Chord distances between unit vectors are Euclidean, so no positive correction; with four points
  and three dimensions the fit is exact up to floating point, and the simplex reports its stress so
  that a five- or six-point variation shows what *stops* being exact.
- **tetra2's double-squaring bug is not ported:** `server.py`'s image+text route calls
  `classical_mds(euclid ** 2)`, and `classical_mds` squares again, so its tetrahedron is drawn from
  fourth powers of the distances. Here there is one MDS, on the distances.

## Shared code (new)

| Piece | Where | What |
| --- | --- | --- |
| `clipEncodeImage(BufferedImage)`, `clipEncodeImage(File)` | `ai/inference/onnx/Clip.kt` | the vision tower from an image or a file, one shared `ClipImage`, synchronised. There was no file-to-vector path; every caller had to build an RGBA `ByteBuffer`. |
| `EmbeddingSimplex`, `embeddingSimplex(vectors, labels)` | `math/statistics/EmbeddingSimplex.kt` | N ≤ ~8 vectors → chord and cosine matrices, edges with per-endpoint ranks (closest … furthest), classical-MDS coordinates in 3D (padded for N ≤ 3), centred and scaled so the farthest point is at a given radius, and the stress of the fit. Model-agnostic: any `FloatArray`/`DoubleArray` embedding. |
| `Box.showSimplex(simplex, …)` | `graphics/three/SimplexView.kt` | the madp picture in `three { }`: spheres, edges, point and edge labels, rank flags, pair table, slow turntable, dimming while stale. |
| viewer labels + corner overlay + dim | `threejs_viewer.html` `[viewer-region labels]`, `ThreeSceneBuilder.label(...)`, `overlay(...)`, `dimmed(...)` | DOM labels anchored to 3D points and re-projected each frame (crisp text, CSS styled), an HTML block in a corner, and the greyed "outdated" state. General: nothing here is tetra-specific. |
| `Box.mediaSlots { }` | `ui/output/MediaSlots.kt` | the input widget: N coloured slots, image or text; click to pick, drop a file (or several onto the widget to fill in order), drop or pick a folder to fill a tray, drag from the tray onto a slot, type into a text slot. Images are decoded and downscaled in the browser (EXIF orientation honoured) and sent over the existing `string-update` control channel; the box sees `MediaSlot`s and an `onChange`. The slots' contents persist in a box property so a re-run keeps what was dropped. |

## Starting point (`madp_tetra`)

1. `tetra` — the lead: four pictures (cat, dog, tiger, car). Drop your own.
2. `tetra2 — three pictures and a sentence` — cat, dog, tiger + "a photo of a cat"; rank flags on.
3. `swap one picture` — the same three animals, the car replaced with a wolf: watch the
   tetrahedron fold as the fourth point moves in among the animals.
4. `five points do not fit` — five inputs: the simplex is drawn in its best 3D fit and the table
   shows the stress; the teaching point stated in the negative.

## Not ported

| madp | Why |
| --- | --- |
| the Python server, `api/clip-distances` | CLIP runs in the Field JVM |
| jina-clip-v2, Matryoshka truncation to 512 | Field's CLIP is ViT-B/32, natively 512-d |
| Process / Reset buttons, auto-process after 350 ms | the box re-computes on every slot change; re-running the box resets |
| per-slot "submitted / processing for N ms" | one status line under the slots (model load, then ms per encode) |

## Sample images

CC0 photographs from Wikimedia Commons, downscaled to 512 px, in
`public/docs/assets/madp_tetra/` with `ATTRIBUTION.md` naming each source page and author.
