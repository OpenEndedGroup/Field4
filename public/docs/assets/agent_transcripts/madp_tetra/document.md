# Field Document: madp_tetra

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. tetra — start here

<!-- field-box id="tetra" index="0" -->

### Narrative

From `notes`

tetra: four pictures as a tetrahedron

From `code`

Four pictures, embedded by CLIP, drawn as the one tetrahedron whose six edges are
their six distances.

CLIP turns each picture into 512 numbers: a point in a 512-dimensional space where
pictures of similar things land close together. Any four points of such a space fit
exactly in three dimensions, so this tetrahedron loses nothing: its edges ARE the
distances. Drop your own pictures on the slots, or drag one up from the tray.

```edn [field-box id="tetra" role="properties"]
{"id" "tetra" "index" 0 "name" "tetra — start here" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tetra — start here" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "tetra_notes_.md" "lines" 59 "characters" 2930 "sha256" "46c6c007b1be6b5908e75482da5a9fff5cfb1d35f37501b74a19f2a96766f2e0"} {"name" "code" "language" "kotlin" "path" "tetra_code_.kt" "lines" 23 "characters" 1113 "sha256" "272f7a8ade5287ecd5b81bf2f08a24fd8342de79ac0a67aeb261bfb8a3b5b7a4"}]}
```

```markdown [field-property box="tetra" name="notes" path="tetra_notes_.md"]
# tetra: four pictures as a tetrahedron

A port of the madp demo `tetra.html` (and its unlinked sibling `tetra2.html`).

## What it shows

CLIP is a neural network that turns a picture, or a sentence, into 512 numbers: a point in a
512-dimensional space, placed so that pictures of similar things land close together. Four inputs
give four points, and four points have six distances between them.

You can't look at 512 dimensions. But **any four points of a Euclidean space fit exactly in three
dimensions**, however many dimensions the space has: three points always lie in a plane, and a
fourth adds at most one more direction. So the tetrahedron in this box's output is not an
approximation of CLIP's geometry. Its six edges *are* the six distances, and its shape is the shape
of those four embeddings.

With the cat, the dog, the tiger and the car, the three animals sit close together and the car's
three edges are the three longest.

## How to play

- **Run the box.** The first run loads CLIP (a second or two); after that a change takes well under
  a second.
- **Drop a picture** from your desktop onto a slot, or click a slot to choose a file. Drop several
  at once and they fill that slot and the ones after it.
- **Drag a picture up from the tray** under the slots. Drop a folder on the tray (or use
  **folder…**) to fill it with your own pictures.
- **Orbit** the tetrahedron by dragging; it turns slowly by itself when you let go.
- **reset** puts the slots back as the code says.

While a new picture is being worked out the old one greys out, as on the madp page.

## How it works

The box's code is the whole pipeline:

1. `mediaSlots { … }` shows the slots and hands you their contents;
2. `it.clip()` embeds each slot, picture or text, as a unit-length 512-vector;
3. `embeddingSimplex(...)` computes the six chord distances `|a − b|` between those unit vectors
   and places the four points in 3D by classical MDS (the double-centred Gram matrix and its top
   three eigenvectors);
4. `view.show(...)` draws it: spheres in the slots' colours, an edge per pair with its length, and
   the pair table.

The note in the corner says *edges are the distances* when the fit is exact.

## What differs from the madp page

- **The model.** The madp page runs jina-clip-v2 on a Python server. Field runs OpenAI's CLIP
  ViT-B/32 through ONNX, inside Field, so the numbers differ from the page's. The geometry does not
  depend on the model.
- **No server, no Process button.** The box recomputes whenever a slot changes.
- **tetra2's bug is not ported.** The page's image + text route squared the distances twice before
  MDS. Here there is one MDS, on the distances.
- What you drop survives re-running the box, but not restarting Field. Change the paths in the code
  to keep a set.

The other three boxes are variations: a sentence in place of a picture, one picture swapped, and a
fifth point that does not fit.
```

```kotlin [field-property box="tetra" name="code" path="tetra_code_.kt"]
// Four pictures, embedded by CLIP, drawn as the one tetrahedron whose six edges are
// their six distances.
//
// CLIP turns each picture into 512 numbers: a point in a 512-dimensional space where
// pictures of similar things land close together. Any four points of such a space fit
// exactly in three dimensions, so this tetrahedron loses nothing: its edges ARE the
// distances. Drop your own pictures on the slots, or drag one up from the tray.
val inputs = mediaSlots {
    image("public/docs/assets/madp_tetra/tabby_cat.jpg")
    image("public/docs/assets/madp_tetra/labrador.jpg")
    image("public/docs/assets/madp_tetra/tiger.jpg")
    image("public/docs/assets/madp_tetra/toyota_sports_800.jpg")
    tray("public/docs/assets/madp_tetra")
}
val view = simplexView()

// Runs now, and again whenever a slot changes.
inputs.onChange { slots ->
    view.dim("processing current embeddings")
    val vectors = slots.map { it.clip() }        // one unit-length 512-vector per slot
    val simplex = embeddingSimplex(vectors, slots.map { it.label })
    view.show(simplex, colors = slots.map { it.color })
}
```

## 2. tetra2 — three pictures and a sentence

<!-- field-box id="tetra2" index="1" -->

### Narrative

From `notes`

tetra2: three pictures and a sentence

From `code`

Three pictures and a sentence, in the one space CLIP puts both in.

The sentence sits far from every picture, even the one it describes: CLIP's pictures
and its sentences occupy different regions of the space (the "modality gap"). The
diamonds rank each point's three edges: green the closest, orange, red the furthest.
Which picture is the sentence closest to? Type something else and see.

```edn [field-box id="tetra2" role="properties"]
{"id" "tetra2" "index" 1 "name" "tetra2 — three pictures and a sentence" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tetra2 — three pictures and a sentence" "_tombstone" false "frame" #afield/rect [1100.0 80.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "tetra2_notes_.md" "lines" 13 "characters" 779 "sha256" "0f3d7dd66857c914d32d09b976f8012f19cb401681197b4b89023be439704c99"} {"name" "code" "language" "kotlin" "path" "tetra2_code_.kt" "lines" 21 "characters" 993 "sha256" "86a8593cc4bb448970ce51f78373fbc270d7854d9e25e1fd0746e4f6dd1a0508"}]}
```

```markdown [field-property box="tetra2" name="notes" path="tetra2_notes_.md"]
# tetra2: three pictures and a sentence

The madp page `tetra2.html`: slot 4 holds text instead of a picture. CLIP embeds pictures and
sentences into **one** space, so a sentence is a point among the pictures and the tetrahedron is
still exact.

What to notice: the sentence's point sits far from all three pictures, even the one it describes.
CLIP's pictures and sentences occupy different regions of the space, the "modality gap". Its
*rank* still carries meaning: the diamonds mark each point's three edges, green for the closest,
orange, red for the furthest. With "a photo of a cat", the green diamond at point 4 is on the edge
to the cat. Type "a photo of a dog" and it moves to the dog.

The madp page's server squared these distances twice before MDS; this box does not.
```

```kotlin [field-property box="tetra2" name="code" path="tetra2_code_.kt"]
// Three pictures and a sentence, in the one space CLIP puts both in.
//
// The sentence sits far from every picture, even the one it describes: CLIP's pictures
// and its sentences occupy different regions of the space (the "modality gap"). The
// diamonds rank each point's three edges: green the closest, orange, red the furthest.
// Which picture is the sentence closest to? Type something else and see.
val inputs = mediaSlots {
    image("public/docs/assets/madp_tetra/tabby_cat.jpg")
    image("public/docs/assets/madp_tetra/labrador.jpg")
    image("public/docs/assets/madp_tetra/tiger.jpg")
    text("a photo of a cat")
    tray("public/docs/assets/madp_tetra")
}
val view = simplexView()

inputs.onChange { slots ->
    view.dim("processing current embeddings")
    val vectors = slots.map { it.clip() }        // pictures and text, one space
    val simplex = embeddingSimplex(vectors, slots.map { it.label })
    view.show(simplex, colors = slots.map { it.color }, flags = true)
}
```

## 3. swap one picture

<!-- field-box id="swap_one_picture" index="2" -->

### Narrative

From `notes`

swap one picture

From `code`

tetra with one picture swapped: the car is now a wolf.

Compare the shape with tetra's. There, the car's three edges were the three longest:
its point stood off on its own. Four animals make a nearly even tetrahedron, every
edge within a fifth of the others. Drag the bicycle from the tray onto slot 4 and see
where it lands.

```edn [field-box id="swap_one_picture" role="properties"]
{"id" "swap_one_picture" "index" 2 "name" "swap one picture" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "swap one picture" "_tombstone" false "frame" #afield/rect [80.0 1250.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "swap_one_picture_notes_.md" "lines" 5 "characters" 305 "sha256" "41bed46e20af71d4581451762450b93c270e7fe2ae27bdd6e296c7dfa4d813de"} {"name" "code" "language" "kotlin" "path" "swap_one_picture_code_.kt" "lines" 20 "characters" 877 "sha256" "e38cda16bafbfe25d9481b4e7a170b806dff1472d1ddb022652d7f421a2479ff"}]}
```

```markdown [field-property box="swap_one_picture" name="notes" path="swap_one_picture_notes_.md"]
# swap one picture

`tetra` with the car replaced by a wolf. In `tetra` the car's three edges were the three longest:
its point stood off on its own. With four animals every edge is between about 0.75 and 0.94, a
nearly even tetrahedron. Drag the bicycle from the tray onto slot 4 and see where it lands.
```

```kotlin [field-property box="swap_one_picture" name="code" path="swap_one_picture_code_.kt"]
// tetra with one picture swapped: the car is now a wolf.
//
// Compare the shape with tetra's. There, the car's three edges were the three longest:
// its point stood off on its own. Four animals make a nearly even tetrahedron, every
// edge within a fifth of the others. Drag the bicycle from the tray onto slot 4 and see
// where it lands.
val inputs = mediaSlots {
    image("public/docs/assets/madp_tetra/tabby_cat.jpg")
    image("public/docs/assets/madp_tetra/labrador.jpg")
    image("public/docs/assets/madp_tetra/tiger.jpg")
    image("public/docs/assets/madp_tetra/grey_wolf.jpg")
    tray("public/docs/assets/madp_tetra")
}
val view = simplexView()

inputs.onChange { slots ->
    view.dim("processing current embeddings")
    val simplex = embeddingSimplex(slots.map { it.clip() }, slots.map { it.label })
    view.show(simplex, colors = slots.map { it.color })
}
```

## 4. five points do not fit

<!-- field-box id="five_points" index="3" -->

### Narrative

From `notes`

five points do not fit

From `code`

Five points do not fit in three dimensions.

Four points always sit exactly in 3D; five in general need four. The picture is now
classical MDS's best 3D fit, its drawn edges no longer quite the distances in the
table, and the note under it gives the stress: how far the fit is off, 0 being exact.

```edn [field-box id="five_points" role="properties"]
{"id" "five_points" "index" 3 "name" "five points do not fit" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "five points do not fit" "_tombstone" false "frame" #afield/rect [1100.0 1250.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "five_points_notes_.md" "lines" 6 "characters" 403 "sha256" "651e8e5b9732399d6a2c6c5f7561825bb9bcb26401ed0f3339de92f21bb8cf8e"} {"name" "code" "language" "kotlin" "path" "five_points_code_.kt" "lines" 20 "characters" 910 "sha256" "a52180885ba822971d200f6b4473fb2f30c5aae1abe5871dfc80c7c4f7f665c9"}]}
```

```markdown [field-property box="five_points" name="notes" path="five_points_notes_.md"]
# five points do not fit

Four points always fit exactly in three dimensions; five generally need four. This box adds the
car as a fifth picture. The view now shows classical MDS's best 3D fit, its drawn edges are no
longer quite the distances in the table, and the note under it gives the stress: how far the fit
is from the real distances, 0 being exact. With these five photographs it is about 0.17.
```

```kotlin [field-property box="five_points" name="code" path="five_points_code_.kt"]
// Five points do not fit in three dimensions.
//
// Four points always sit exactly in 3D; five in general need four. The picture is now
// classical MDS's best 3D fit, its drawn edges no longer quite the distances in the
// table, and the note under it gives the stress: how far the fit is off, 0 being exact.
val inputs = mediaSlots {
    image("public/docs/assets/madp_tetra/tabby_cat.jpg")
    image("public/docs/assets/madp_tetra/labrador.jpg")
    image("public/docs/assets/madp_tetra/tiger.jpg")
    image("public/docs/assets/madp_tetra/grey_wolf.jpg")
    image("public/docs/assets/madp_tetra/toyota_sports_800.jpg")
    tray("public/docs/assets/madp_tetra")
}
val view = simplexView()

inputs.onChange { slots ->
    view.dim("processing current embeddings")
    val simplex = embeddingSimplex(slots.map { it.clip() }, slots.map { it.label })
    view.show(simplex, colors = slots.map { it.color })
}
```

