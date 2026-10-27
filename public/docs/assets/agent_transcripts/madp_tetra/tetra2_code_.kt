/*
Three pictures and a sentence, in the one space CLIP puts both in.

The sentence sits far from every picture, even the one it describes: CLIP's pictures
and its sentences occupy different regions of the space (the "modality gap"). The
diamonds rank each point's three edges: green the closest, orange, red the furthest.
Which picture is the sentence closest to? Type something else and see.
*/
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
    val vectors = slots.map { it.clip() } // pictures and text, one space
    val simplex = embeddingSimplex(vectors, slots.map { it.label })
    view.show(simplex, colors = slots.map { it.color }, flags = true)
}
