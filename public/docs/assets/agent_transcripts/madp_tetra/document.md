# Field Document: madp_tetra

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. tetra — start here

<!-- field-box id="tetra" index="0" -->

### Narrative

From `code`

Four pictures, embedded by CLIP, drawn as the one tetrahedron whose six edges are
their six distances.

CLIP turns each picture into 512 numbers: a point in a 512-dimensional space where
pictures of similar things land close together. Any four points of such a space fit
exactly in three dimensions, so this tetrahedron loses nothing: its edges ARE the
distances. Drop your own pictures on the slots, or drag one up from the tray.

```edn [field-box id="tetra" role="properties"]
{"id" "tetra" "index" 0 "name" "tetra — start here" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tetra — start here" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tetra_code_.kt" "lines" 23 "characters" 1113 "sha256" "272f7a8ade5287ecd5b81bf2f08a24fd8342de79ac0a67aeb261bfb8a3b5b7a4"}]}
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

From `code`

Three pictures and a sentence, in the one space CLIP puts both in.

The sentence sits far from every picture, even the one it describes: CLIP's pictures
and its sentences occupy different regions of the space (the "modality gap"). The
diamonds rank each point's three edges: green the closest, orange, red the furthest.
Which picture is the sentence closest to? Type something else and see.

```edn [field-box id="tetra2" role="properties"]
{"id" "tetra2" "index" 1 "name" "tetra2 — three pictures and a sentence" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tetra2 — three pictures and a sentence" "_tombstone" false "frame" #afield/rect [1100.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tetra2_code_.kt" "lines" 21 "characters" 993 "sha256" "86a8593cc4bb448970ce51f78373fbc270d7854d9e25e1fd0746e4f6dd1a0508"}]}
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

From `code`

tetra with one picture swapped: the car is now a wolf.

Compare the shape with tetra's. There, the car's three edges were the three longest:
its point stood off on its own. Four animals make a nearly even tetrahedron, every
edge within a fifth of the others. Drag the bicycle from the tray onto slot 4 and see
where it lands.

```edn [field-box id="swap_one_picture" role="properties"]
{"id" "swap_one_picture" "index" 2 "name" "swap one picture" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "swap one picture" "_tombstone" false "frame" #afield/rect [80.0 1250.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swap_one_picture_code_.kt" "lines" 20 "characters" 877 "sha256" "e38cda16bafbfe25d9481b4e7a170b806dff1472d1ddb022652d7f421a2479ff"}]}
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

From `code`

Five points do not fit in three dimensions.

Four points always sit exactly in 3D; five in general need four. The picture is now
classical MDS's best 3D fit, its drawn edges no longer quite the distances in the
table, and the note under it gives the stress: how far the fit is off, 0 being exact.

```edn [field-box id="five_points" role="properties"]
{"id" "five_points" "index" 3 "name" "five points do not fit" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "five points do not fit" "_tombstone" false "frame" #afield/rect [1100.0 1250.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "five_points_code_.kt" "lines" 20 "characters" 910 "sha256" "a52180885ba822971d200f6b4473fb2f30c5aae1abe5871dfc80c7c4f7f665c9"}]}
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

