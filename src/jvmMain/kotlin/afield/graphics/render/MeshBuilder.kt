package afield.graphics.render

import afield.graphics.shader.Introspected

import org.lwjgl.BufferUtils

/**
 * Filling a [Mesh]'s vertex and index buffers from Kotlin, by two routes.
 *
 * [MeshBuilder] is the imperative one: append vertices and triangles into growable [VertexData]
 * buffers in any order, and they are uploaded in one pass the next time the mesh is drawn.
 * `Mesh.builder { }` opens one.
 *
 * [writeSuperMap] is the generated one. It reads a vertex shader's declared inputs and returns
 * Kotlin source for a `mesh.set(numVert, numTriangle) { }` that maps every input buffer at once and
 * names each one. [afield.graphics.shader.insertForShader] puts that source in a shader box's
 * preamble, so `set` knows the names a particular shader uses. [writeVertexData] generates a
 * per-vertex `write(...)` for the same inputs; nothing in the tree calls it.
 */

/**
 * Kotlin source for a `Mesh.set(numVert, numTriangle) { }` that maps every one of the shader's
 * input buffers at once and binds each to a `FloatBuffer` named after its input, with the index
 * buffer as `tri`.
 *
 * Returned as one line of semicolon-separated declarations, since it is spliced into a box's
 * preamble rather than compiled on its own.
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

// this just didn't work out

// subclasses have actual accessors
/**
 * A growable byte buffer and the count of items written into it.
 *
 * [MeshBuilder] keeps one per shader input location plus one for triangle indices, which is how a
 * recipe can append in any order without knowing the final vertex count first. [checkCapacity]
 * grows the buffer when the next write would not fit.
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
 * Kotlin source for a `MeshBuilder.write(...)` taking one argument per shader input and appending a
 * vertex to each buffer.
 *
 * Nothing in the tree calls this; [writeSuperMap] is the route a shader box's preamble takes.
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
 * Accumulates a mesh's vertices and triangles on the CPU and writes them into [target] in one pass.
 *
 * [getDataAndCheckCapacity] hands out a [VertexData] per shader input location, which is what the
 * generated `write(...)` in a shader box's preamble appends through. [tri] and [quad] index
 * backwards from the vertices just written, so a recipe never tracks indices itself.
 *
 * The write happens the next time [target] is drawn, and only if a count has moved: either the
 * vertices or the triangles differ from the mesh's current size, or the mesh has no buffers yet.
 * Rewriting the same number of vertices with different positions therefore uploads nothing.
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
