@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.stacksl.cpu.CpuRaster

/**
 * Where a study's frames come from: [P] — a program, a compiled scene — drawn at a size and a list of
 * times, each frame handed back as a [CpuRaster] of the program's raw output.
 *
 * Everything a study measures and lays out ([StudyEvidence]) works on those rasters, so the renderer
 * underneath is a choice the study can change at run time: the GPU of a running window when there is
 * one, a CPU reference when there is not, each saying which it is in [description]. Rows run top to
 * bottom; colour is linear light (what the shader wrote), and [StudyEvidence.presented] is what the
 * window shows of it.
 *
 * StackSL's rasterizers implement it for a program's text (square frames), RectSL's for a compiled
 * scene; a document's own study can implement it for whatever it draws.
 */
interface StudyFrames<in P> {
    /** Which renderer produced the last frames, and why — repeated in every tool result. */
    val description: String

    /** Whether a frame is cheap right now — whether defaults can afford large sizes and many frames. */
    val fast: Boolean

    /** [input] at [width] by [height], once per time in [seconds], handed to [each] in order. */
    fun render(input: P, width: Int, height: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit)
}

/** [StudyFrames.render], collected. */
fun <P> StudyFrames<P>.renderAll(input: P, width: Int, height: Int, seconds: List<Double>): List<CpuRaster> {
    val out = arrayOfNulls<CpuRaster>(seconds.size)
    render(input, width, height, seconds) { i, raster -> out[i] = raster }
    return out.map { requireNotNull(it) }
}
