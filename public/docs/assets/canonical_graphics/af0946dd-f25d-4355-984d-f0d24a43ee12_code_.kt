// First, we'll make a shader module associated with this box that draws filled triangles
var module = moduleHere { primitiveType = triangles }

    // ... and make a mesh, which runs the shader with some data
var m = Mesh()

    // Then we connect the mesh to the module under the name "someMesh"
module += m named "someMesh"

    // ... and connect the module to the renderer (under the name of this box)
renderer += module

/*
This gets us everything we need to start rendering things. In particular, we get a vertex shader:

[[property vertex]]
*/

    // and a fragment shader:

/*
[[property fragment]]
*/

// you can edit these by switching to these properties in the editor `f1` or `ctrl-P`

//<hr>

// Now we need a place to put our data
var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}.buffer

/* Two things:

- we're free to guess the size we'll need (that's the `3.vec4s`), but if we're wrong, we'll get a dynamicly resizing buffer (I'm deliberately setting the size wrong on this buffer to make sure that the dynamic resizing works correctly. The size should be `30.vec4s`)

- `perframe = true` means that we actually have a VulkanBuffer per frame-in-flight, so we can always write to a buffer (and GPU-read from it) without waiting.*/

// now we connect this buffer to the mesh
// `.vertexData` gets magically defined for us with the coorect type, because Field has read our vertex shader
m.vertexData = { vertexData }

// You'll note that we have not have written any data to any of the buffers buffer at this point, but this is ok, because we don't size the mesh until later. Or we could write in here before.

//<hr>

// Now a little demo, some triangles that move in circles
var t = 0.0
fun polar(t : Double, r : Double) = vec(Math.sin(t), Math.cos(t)) * r.f

loop {
    t += 0.01
    var num = 0

    var numVertToWrite = (Math.sin(t*0.1)*20 + 30).i     // showing that we can add and remove verts in flight

    vertexData.write {
        for(x in 0 until numVertToWrite) {
            val offset = vec(x*0.02, 0, 0)
            put(vec(polar(t, 0.5), 0) + offset)
            put(vec(polar(t+0.4, 0.4), 0) + offset)
            put(vec(polar(t+0.9, 0.3), 0) + offset)

            num += 3
        }
    }

    // it's important that we tell the mesh how many "vertices we want to draw", but, other than that turning into triangles, what that means is entirely up to our vertex shader
    m.resize(num)

    // loop forever:
    true
}

// because `loop` is the last thing in this script, that code runs (once per animation frame) when you launch it (`alt-mouse-swipe` or `alt-up`)

// ultimately, we get something that looks like this:

// [[render_clip]]
