package afield.graphics.render

import afield.graphics.three.three

import afield.geometry.path.FLine
import afield.app.diagnostics.Named
import afield.graphics.vulkan.buffers.DynamicallyWriteable
import afield.ai.learning.*
import afield.ai.retrieval.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.animation.motion.*
import afield.app.commands.*
import afield.app.diagnostics.*
import afield.audio.analysis.*
import afield.audio.synth.*
import afield.data.collections.*
import afield.data.formats.*
import afield.data.graph.*
import afield.data.sorting.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.geometry.mesh.*
import afield.geometry.path.*
import afield.geometry.spatial.*
import afield.graphics.color.*
import afield.graphics.interaction.*
import afield.graphics.render.*
import afield.graphics.shader.*
import afield.graphics.text.*
import afield.graphics.vulkan.buffers.*
import afield.io.osc.*
import afield.io.process.*
import afield.lang.lambda.*
import afield.lang.python.*
import afield.lang.remotejs.*
import afield.lang.stacksl.*
import afield.math.autodiff.*
import afield.math.control.*
import afield.math.estimation.*
import afield.math.functions.*
import afield.math.linalg.*
import afield.math.optimization.*
import afield.math.statistics.*
import afield.math.units.*
import afield.media.image.*
import afield.media.video.*
import afield.runtime.execution.*
import afield.runtime.languages.*
import afield.runtime.lifecycle.*
import afield.simulation.agents.*
import afield.simulation.automata.*
import afield.simulation.particles.*
import afield.study.build.*
import afield.study.evidence.*
import afield.study.search.*
import afield.study.source.*
import afield.study.ui.*
import afield.ui.controls.*
import afield.ui.output.*
import afield.ui.visualization.*

/**
 * Writes [FLine]s into GPU vertex streams: [pointsOut], [linesOut] and [trianglesOut] sample lines into [DynamicallyWriteable] position and colour buffers.
 *
 * [FLineMap] is a named, ordered collection of lines with a revision hash for change detection.
 */


/**
 * Ordered map of named [FLine]s with a lightweight revision hash.
 *
 * Handy for GPU uploads where stable keys and change detection matter.
 */
class FLineMap : LinkedHashMap<String, FLine>() {

    /**
     * Combined revision hash of all contained lines.
     */
    val rev: Long
        get() {
            var hash: Long = 1L
            values.forEach { hash = 31 * hash + it.rev }
            return hash
        }

    operator fun plusAssign(f: FLine) {
        plusAssign("fline_${f.uid}" to f)
    }

    operator fun plusAssign(f: Pair<String, FLine>) {
        this[f.first] = f.second
    }

    operator fun plusAssign(f: Named<FLine>) {
        this[f.name] = f.value
    }

    override fun toString(): String = prettySummary(
        "FLineMap",
        "size" to size,
        "keys" to keys.take(4),
        "rev" to rev
    )
}


/**
 * Convenience overload of [pointsOut] for an [FLineMap].
 *
 */
fun pointsOut(
    m: FLineMap, vertexData: DynamicallyWriteable,
    pointData: DynamicallyWriteable,
    colorData: DynamicallyWriteable
) = pointsOut(m.values, vertexData, colorData, pointData)

/**
 * Write point positions, colours and sizes for a collection of [FLine]s.
 *
 * Positions go to [vertexData], one colour per point to [colorData] and one float per point to
 * [pointSizeData], all in node order. `Close` instructions are skipped — they carry no coordinate — so the
 * count returned is the number of *drawable* nodes rather than `nodes.size`, and the three buffers stay in
 * step with each other. Per-node `pointSize` and `color` properties win over the line-level ones.
 *
 * @return number of points written.
 */
fun pointsOut(
    m: Collection<FLine>,
    vertexData: DynamicallyWriteable,
    colorData: DynamicallyWriteable,
    pointSizeData: DynamicallyWriteable
): Int {

    var num = 0
    colorData.write {
        val cd = this
        pointSizeData.write {
            val pointd = this
            vertexData.write {

                m.forEachIndexed { i, f ->
                    val fallback = f.pointSize
                    val fallbackColor = f.color
                    f.nodes.forEach { node ->
                        // A Close carries no coordinate at all (`Node(0)`, so `to` indexes an empty
                        // storage), and every rect, `Rect.toFLine` and arrowhead ends in one — reading it
                        // threw IndexOutOfBoundsException for any `pointed` closed line. The Skiko and SVG
                        // point writers have always skipped it; this one now does too.
                        if (node is FLine.Close) return@forEach
                        put(node.to)
                        val q = (node.properties["pointSize"] as? Number ?: fallback).f
                        pointd.put(q)
                        cd.put((node.properties["color"] as? Vec4 ?: fallbackColor))
                        num++
                    }
                }
            }
        }
    }
    return num
}

/**
 * Convenience overload of [trianglesOut] for an [FLineMap].
 */
fun trianglesOut(
    m: FLineMap, vertexData: DynamicallyWriteable,
    colorData: DynamicallyWriteable,
    samplesPerCubic: Int = 100
) = trianglesOut(m.values, vertexData, colorData, samplesPerCubic)


/**
 * Tessellate filled [FLine]s into triangles and write positions and colors.
 *
 * @return number of vertices written.
 */
fun trianglesOut(
    m: Collection<FLine>, vertexData: DynamicallyWriteable, colorData: DynamicallyWriteable, samplesPerCubic: Int = 100
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
                f.tessellate(samplesPerCubic) { verts, index ->
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

/**
 * Convenience overload of [linesOut] for a single [FLine].
 */
fun linesOut(
    m: FLine,
    vertexData: DynamicallyWriteable? = null,
    colorData: DynamicallyWriteable? = null,
    samplesPerCubic: Int = 100
) = linesOut(listOf(m), vertexData, colorData, samplesPerCubic)

/**
 * Convenience overload of [linesOut] for an [FLineMap].
 */
fun linesOut(
    m: FLineMap, vertexData: DynamicallyWriteable? = null,
    colorData: DynamicallyWriteable? = null,
    samplesPerCubic: Int = 100
) = linesOut(m.values, vertexData, colorData, samplesPerCubic)

/**
 * Linearize stroke paths into a vertex stream, plus an optional colour stream indexed *by node*.
 *
 * The two streams are not parallel and are not the same length. [vertexData] gets one entry per
 * linearized sample — `samplesPerCubic` of them for every `CubicTo` — while [colorData] gets
 * `FLine.colorsPer()` per line, which is one `Vec4` per *node*. They are joined through the 4th
 * component of each vertex: it carries `inst + base`, where `base` accumulates `nodes.size` across
 * lines, i.e. an index into the concatenated per-node colour array. Do not read it as a per-vertex
 * colour or as a segment id.
 *
 * A sentinel `(0,0,0,-1)` is written after *every* line, not between them, so N lines produce N
 * sentinels including a trailing one — a consumer that stops at "between" will read one too many. A
 * node-less `FLine` still contributes its sentinel and nothing else.
 *
 * @return number of vertices written, counting every sentinel — so samples + N, not samples.
 */
fun linesOut(
    m: Collection<FLine>,
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
            num += 1
            base += f.nodes.size
        }
    }

    return num
}
