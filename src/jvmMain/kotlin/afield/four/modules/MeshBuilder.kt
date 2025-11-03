package afield.four.modules

import org.lwjgl.BufferUtils

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

/**
 * this just didn't work out
 */


// subclasses have actual accessors
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
