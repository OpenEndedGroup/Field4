clearAFrame()

print("hello?")

loadPointCloudPLY("/local/Desktop/IMG_6962.MOV.dir/dense/0/fused.ply", "something", (loaded) => {
    window.loaded = loaded
    print("loaded!")

    loaded.mesh.material.size = 0.01
    loaded.mesh.scale.y = -1

    // move it out of sight!
    findByName("something").position.y=10000000
})


window.loaded.mesh.scale.y = -1


mouseMove.f = (event) => {

    //document.querySelector("#hitter").position is a position that's
    //in the middle of the screen, but intersecting with any planes we
    //find there --- walls, floors.

    var s = getCurrentCamera().position.distanceTo(document.querySelector("#hitter").position)

    var at = event.ray.origin + event.ray.direction * s

    // copy it to the floor position of where we clicked
    findByName("something").position.x = at.x
    findByName("something").position.y = at.y
    findByName("something").position.z = at.z
}