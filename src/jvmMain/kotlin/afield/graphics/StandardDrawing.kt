package afield.graphics

import afield.four.buffers.DynamicallyWriteable
import afield.stdlib.f
import afield.stdlib.pointSize
import afield.stdlib.vec


fun pointsOut(
    m: List<FLine>, vertexData: DynamicallyWriteable, pointSizeData: DynamicallyWriteable
): Int {

    var num = 0
    pointSizeData.write {
        val cd = this
        vertexData.write {

            m.forEachIndexed { i, f ->
                f.nodes.forEach { node ->
                    put(node.to)
                    cd.put((node.properties["pointSize"] as? Number ?: f.pointSize).f)
                    num++
                }
            }
        }
    }
    return num
}

fun trianglesOut(
    m: List<FLine>, vertexData: DynamicallyWriteable, colorData: DynamicallyWriteable, samplesPerCubic: Int = 100
): Int {

    var a = 0
    var splits = m.map { f ->
        val colors = f.colorsPer()
        var b = a
        a += colors.size / 4
        b to colors
    }

    var num = 0
    colorData.write {
        val cd = this
        vertexData.write {

            m.forEachIndexed { i, f ->
                f.tessellate(10) { verts, index ->
                    index.forEach {
                        val at = vec(verts[3 * it + 0], verts[3 * it + 1], verts[3 * it + 2])
                        put(at)

                        // this can't possibly work with cubic interpolations, we need to map index back to drawing instruction
                        cd.put(splits[i].second[4 * it + 0])
                        cd.put(splits[i].second[4 * it + 1])
                        cd.put(splits[i].second[4 * it + 2])
                        cd.put(splits[i].second[4 * it + 3])
                        num++
                    }
                }
            }
        }
    }
    return num
}

fun linesOut(
    m: FLine,
    vertexData: DynamicallyWriteable? = null,
    colorData: DynamicallyWriteable? = null,
    samplesPerCubic: Int = 100
) = linesOut(listOf(m), vertexData, colorData, samplesPerCubic)

fun linesOut(
    m: List<FLine>,
    vertexData: DynamicallyWriteable? = null,
    colorData: DynamicallyWriteable? = null,
    samplesPerCubic: Int = 100
): Int {

    var num = 0

    colorData?.write {
        m.map { f ->
            put(f.colorsPer())
        }
    }

    vertexData?.write {
        var base = 0
        m.forEach { f ->
            f.uniform_linearize(samplesPerCubic = samplesPerCubic) { at, inst ->
                put(at, inst + (if (inst < 0) -base else base))
                num += 1
            }
            put(0f, 0f, 0f, -1f) // this is actually important
            base += f.nodes.size
        }
    }

    return num
}
