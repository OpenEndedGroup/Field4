package afield.graphics.render

import afield.graphics.shader.Introspected

import org.lwjgl.BufferUtils

/**
 * Two ways of writing vertices into a `Mesh`, of which one is wired up.
 *
 * [writeSuperMap] is the live one, and it is where `m.set(4, 2) { … }` in a shader box comes from. It
 * reads a vertex shader's introspected inputs and returns Kotlin source: a `MappedStructure` holding
 * one buffer field per input, and a `Mesh.set(numVert, numTriangle)` extension that resizes the mesh
 * and maps all of those buffers at once, so the lambda can assign them by the names the shader itself
 * declares. `insertForShader` calls this whenever a `moduleHere` box's vertex and fragment shaders
 * compile, and appends the text to that box's preamble.
 *
 * [MeshBuilder], [VertexData] and [writeVertexData] are the other way: stage vertex bytes on the CPU
 * through generated `write(...)` calls, then copy them into the mesh's buffers in one pass. `Mesh`
 * constructs a [MeshBuilder] for every mesh and offers it as `builder { }`, but nothing in the tree
 * calls [writeVertexData] to generate those `write` methods, and nothing calls the internal
 * `closeAndApply` that copies the staged bytes across, so vertices staged this way never reach the
 * mesh. `m.set` is the route to use.
 */

/**
 * The `MappedStructure` and `Mesh.set` source text for a vertex shader's inputs `v`, as one line.
 *
 * Returned as Kotlin to be compiled in the box's own preamble rather than as a function to call,
 * because the field names and their buffer types are the shader's, so they do not exist until it has
 * been introspected.
 */
fun writeSuperMap(v: List<Introspected.Input>): String {

    var body = "lateinit var tri : IntBuffer\n"
    v.forEach {
        body += "lateinit var ${it.name} : FloatBuffer\n"
    }

    var mapChain = "mesh.t?.mapAndWrite_ints { tri = this ; builder() }"
    v.forEach {
        mapChain = "mesh.write(\"${it.name}\") { ${it.name} = this ; ${mapChain} }"
    }

    return """
        /*supermap*/ class MappedStructure {
            ${body}
        }
        fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {
            this.resize(numVert, numTriangle)
            val mesh = this
            MappedStructure().apply {
                 ${mapChain} 
           }
        }
    """.trimIndent().replace("\n", "; ").replace(";;", ";").replace(";;", ";").trimIndent()
}

// Everything from here down is the staging path that did not work out; the file overview says why.
// Written as a line comment so that a declaration inserted below it does not adopt it as its KDoc.

// subclasses have actual accessors
/**
 * A growable byte buffer for one vertex attribute, with the number of vertices written to it.
 *
 * [VertexData.checkCapacity] reallocates when the next write would not fit. [VertexData.rewind] zeroes the vertex count and
 * leaves the buffer's own position alone, so a staged attribute is re-counted rather than cleared.
 */
class VertexData {
    var cap = 16
    var buffer = BufferUtils.createByteBuffer(cap * 4)
    var v = 0

    fun rewind() {
        v = 0
    }

    fun checkCapacity(n: Int) {
        if (buffer.position() + n > cap) {
            realloc(Math.max(Math.ceil(cap * 1.5).toInt(), buffer.position() + n * 2 + 1))
        }
    }

    private fun realloc(size: Int) {
        var a = buffer.position()
        var source2 = BufferUtils.createByteBuffer(size * 4)
        buffer.clear()
        source2.put(buffer)
        source2.clear()
        buffer = source2
        source2.position(a)
        cap = size
    }
}

/**
 * The `MeshBuilder.write(...)` source text for a vertex shader's inputs `n`: one parameter per input,
 * defaulted, writing into that input's own [VertexData].
 *
 * Throws for an input whose type is a struct rather than a scalar or vector, since there is no single
 * Kotlin parameter to stand for one. Nothing in the tree calls this, so the `write` it describes is
 * not generated anywhere.
 */
fun writeVertexData(n: List<Introspected.Input>): String {
    var args = mutableListOf<String>()
    n.forEach { input ->
        args += input.type.fold(
            { input.name + " : " + it.kotlinTypeName + " = " + it.kotlinDefault },
            { throw IllegalArgumentException("cant vertex write complex structures") })
    }

    var body = ""
    n.forEach {
        var offset = 0L
        body += it.type.fold({ ty ->
            val s =
                "getDataAndCheckCapacity(${it.location}, ${ty.sizeInBytes}).let { vd -> vd.v++ ; vd.buffer.let { buffer -> ${it.name}.let { x -> ${ty.writeXToBuffer} }}}"
            offset += it.sizeInBytes
            s
        }, {
            // done above
        })

    }
    return """
        fun MeshBuilder.write(${args.joinToString(",")}) {
            ${body}
        }
    """.trim().trimIndent()
}


/**
 * Stages one mesh's vertices and triangles in host memory, with a slot for each of sixteen shader
 * input locations.
 *
 * [MeshBuilder.tri] and [MeshBuilder.quad] index backwards from the vertex written most recently, so a face can be emitted
 * straight after the vertices it uses and a caller never tracks an index itself. Attribute slots are
 * created on demand by [MeshBuilder.getDataAndCheckCapacity], which the generated `write` methods call, and the
 * count held by location 0 is what every other slot is measured against.
 *
 * The staged bytes reach its `target` mesh only through the internal `closeAndApply`, which nothing calls; the
 * file overview has the rest.
 */
class MeshBuilder(val target: Mesh) {

    val data = arrayOfNulls<VertexData>(16)

    val triangle = VertexData()

    var isOpen = false
    internal fun open() {
        if (!isOpen) {
            data.forEach { it?.rewind() }
        }
        isOpen = true
    }

    fun tri(a: Int, b: Int, c: Int) {
        triangle.checkCapacity(3)
        triangle.buffer.putInt(data[0]!!.v - 1 - c).putInt(data[0]!!.v - 1 - b).putInt(data[0]!!.v - 1 - a)
        triangle.v++
    }

    fun tri() {
        triangle.checkCapacity(3)
        triangle.buffer.putInt(data[0]!!.v - 1 - 2).putInt(data[0]!!.v - 1 - 1).putInt(data[0]!!.v - 1 - 0)
        triangle.v++
    }

    fun quad() {
        triangle.checkCapacity(6)
        triangle.buffer.putInt(data[0]!!.v - 1 - 2).putInt(data[0]!!.v - 1 - 1).putInt(data[0]!!.v - 1 - 0)
        triangle.buffer.putInt(data[0]!!.v - 1 - 3).putInt(data[0]!!.v - 1 - 2).putInt(data[0]!!.v - 1 - 1)
        triangle.v += 2
    }

    fun getDataAndCheckCapacity(layout: Int, sizeInBytes: Int): VertexData {
        var a = data[layout]
        if (a == null) {
            a = VertexData()
            data[layout] = a
            a.checkCapacity(sizeInBytes)
            return a
        }
        a.checkCapacity(sizeInBytes)
        return a
    }

    internal fun closeAndApply() {
        try {
            if (!isOpen) {
            } else {
                if (data[0] == null) return
                if (data[0]!!.v != target.numVert || triangle.v != target.numTriangle || (target.notInitialized && data[0]!!.v > 0 && triangle.v > 0)) {
                    target.resize(data[0]!!.v, triangle.v)

                    data.forEachIndexed { index, d ->
                        if (d != null) {
                            target.v[index]?.mapAndWrite_bytes {
                                d.buffer.limit(d.buffer.position())
                                d.buffer.rewind()
                                this.put(d.buffer)
                                d.buffer.clear()
                            }
                        }
                    }

                    target.t?.mapAndWrite_bytes {
                        triangle.buffer.limit(triangle.buffer.position())
                        triangle.buffer.rewind()
                        this.put(triangle.buffer)
                        triangle.buffer.clear()
                    }


                }
            }
        } finally {
            isOpen = false
        }
    }
}
