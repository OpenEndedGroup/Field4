clearAFrame()

var shader = window.newShaderForBox()

loadGLTF("/local/Desktop/2_2_2025.glb", "panda", (loaded) => {
    window.loaded = loaded
    print("loaded!")

    // move it out of sight!
    findByName("panda").position.y=10000000
})



mouseMove.f = (event) => {

    //document.querySelector("#hitter").position is a position that's
    //in the middle of the screen, but intersecting with any planes we
    //find there --- walls, floors.

    var s = getCurrentCamera().position.distanceTo(document.querySelector("#hitter").position)

    var at = event.ray.origin + event.ray.direction * s

    // copy it to the floor position of where we clicked
    findByName("panda").position.x = at.x
    findByName("panda").position.y = at.y
    findByName("panda").position.z = at.z
}