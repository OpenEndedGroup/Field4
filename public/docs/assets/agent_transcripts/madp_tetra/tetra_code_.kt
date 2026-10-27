/*
Four pictures, embedded by CLIP, drawn as the one tetrahedron whose six edges are
their six distances.

CLIP turns each picture into 512 numbers: a point in a 512-dimensional space where
pictures of similar things land close together. Any four points of such a space fit
exactly in three dimensions, so this tetrahedron loses nothing: its edges ARE the
distances. Drop your own pictures on the slots, or drag one up from the tray.
*/
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
    val vectors = slots.map { it.clip() } // one unit-length 512-vector per slot
    val simplex = embeddingSimplex(vectors, slots.map { it.label })
    view.show(simplex, colors = slots.map { it.color })
}
