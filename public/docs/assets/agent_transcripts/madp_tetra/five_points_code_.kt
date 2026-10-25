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
