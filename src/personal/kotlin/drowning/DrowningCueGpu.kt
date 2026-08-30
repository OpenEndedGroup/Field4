package drowning

import afield.four.buffers.BufferWriteable
import afield.four.device.CommandBuffer
import afield.stdlib.linalg.Matrix4f
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * std430 palette shared by splats, particles and MXG draws.
 *
 * Per cell (15 vec4s): modelFromCamera[4], cameraFromModel[4], source/depth/flow atlas rects,
 * disparity+flow decode, inverse-depth calibration, source geometry, and scored appearance.
 * Atlas rects are `(u,v,w,h)`. Score is `(opacity*flicker, particleInfluence,
 * projectedLight*flicker, flicker)`.
 */
object DrowningPaletteLayout {
    const val CELL_VEC4S = 15
    const val CELL_BYTES = CELL_VEC4S * 16
    const val CELL_COUNT = 15
    const val TOTAL_VEC4S = CELL_VEC4S * CELL_COUNT
}

/**
 * Write this look's atlas/decode calibration while taking placement and scored presence from
 * [spatialFrom]. This lets a look change atomically without resetting the live 15-cell score.
 */
fun DrowningCue.writePalette(
    into: BufferWriteable,
    command: CommandBuffer? = null,
    spatialFrom: DrowningCue = this
) {
    require(spatialFrom.cells.size == cells.size) {
        "palette spatial cue has ${spatialFrom.cells.size} cells, expected ${cells.size}"
    }
    into.mapAndWrite_bytes {
        order(ByteOrder.LITTLE_ENDIAN)
        cells.forEachIndexed { index, cell ->
            val spatial = spatialFrom.cells[index]
            require(spatial.index == cell.index) { "palette cell mismatch at $index" }
            putMatrix(spatial.modelFromCamera)
            putMatrix(spatial.cameraFromModel)
            putFloats(cell.sourceRect.normalized(atlasWidth, atlasHeight))
            putFloats(cell.disparityRect.normalized(atlasWidth, atlasHeight))
            putFloats(cell.flowRect.normalized(atlasWidth, atlasHeight))
            putFloat(cell.disparityMultiplier)
            putFloat(cell.flowZero)
            putFloat(cell.flowMultiplierX)
            putFloat(cell.flowMultiplierY)
            putFloat(cell.calibration.a)
            putFloat(cell.calibration.b)
            putFloat(cell.calibration.epsilon)
            putFloat(cell.calibration.focalLengthPixels)
            putFloat(cell.calibration.centreX)
            putFloat(cell.calibration.centreY)
            putFloat(cell.calibration.width.toFloat())
            putFloat(cell.calibration.height.toFloat())
            putFloat(spatial.opacity.coerceAtLeast(0f) * spatial.flicker.coerceIn(0f, 1f))
            putFloat(spatial.particleInfluence.coerceAtLeast(0f))
            putFloat(spatial.projectedLight.coerceAtLeast(0f) * spatial.flicker.coerceIn(0f, 1f))
            putFloat(spatial.flicker.coerceIn(0f, 1f))
        }
    }
    into.clean(false, command)
}

private fun ByteBuffer.putFloats(values: FloatArray) {
    values.forEach(::putFloat)
}

private fun ByteBuffer.putMatrix(m: Matrix4f) {
    putFloat(m.m00); putFloat(m.m01); putFloat(m.m02); putFloat(m.m03)
    putFloat(m.m10); putFloat(m.m11); putFloat(m.m12); putFloat(m.m13)
    putFloat(m.m20); putFloat(m.m21); putFloat(m.m22); putFloat(m.m23)
    putFloat(m.m30); putFloat(m.m31); putFloat(m.m32); putFloat(m.m33)
}
