/*
tetra with one picture swapped: the car is now a wolf.

Compare the shape with tetra's. There, the car's three edges were the three longest:
its point stood off on its own. Four animals make a nearly even tetrahedron, every
edge within a fifth of the others. Drag the bicycle from the tray onto slot 4 and see
where it lands.
*/
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
