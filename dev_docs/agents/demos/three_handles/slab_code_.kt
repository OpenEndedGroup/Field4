/*
The blue handle runs on a vertical rail (axis = y): it sets the slab's height. The orange
one slides only across the slab's top (normal = y), carrying the post with it. Field holds
each handle to its line or plane before the callback sees the position.
*/
var height = 20.0
var post = vec(20, 0, 10)

fun draw() {
    three(name = "slab", height = 480) {
        mesh(
            boxMesh(120, height, 60).translated(0.0, height / 2, 0.0),
            id = "slab",
            wireframe = false,
            material = ThreeSurfaceMaterial(color = floatArrayOf(0.85f, 0.55f, 0.3f)),
        )
        mesh(boxMesh(6, 40, 6).translated(post.x.toDouble(), height + 20, post.z.toDouble()), id = "post", wireframe = false)
        handle("height", at = vec(-60.0, height, 30.0), label = "height %.0f".format(height),
            axis = vec(0, 1, 0), color = "#4fc3f7") { drag ->
            height = maxOf(2.0, drag.position.y.toDouble())
            draw()
        }
        handle("post", at = vec(post.x.toDouble(), height, post.z.toDouble()), label = "post",
            normal = vec(0, 1, 0), color = "#ff8a65") { drag ->
            post = drag.position
            draw()
        }
    }
}
draw()
