@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

/**
 * Unrolls a small spectrum into StackSL. `input.xyz` is a world-space point, and the scalar field
 * splats across the four result lanes. Coefficients are baked into the program as FP32 constants.
 * Use the Vulkan buffer evaluator for large or frequently edited spectra.
 */
fun PlaneWaveField.toStackSL(): String = buildString {
    appendLine(offset.stackLiteral())
    for (wave in waves) {
        append("input ")
        append(wave.kx.stackLiteral()).append(' ')
        append(wave.ky.stackLiteral()).append(' ')
        append(wave.kz.stackLiteral()).append(" 0 vec4 dot dup cos ")
        append(wave.a.stackLiteral()).append(" * swap sin ")
        append(wave.b.stackLiteral()).appendLine(" * + +")
    }
}

private fun Double.stackLiteral(): String = toFloat().also {
    require(it.isFinite()) { "StackSL spectral constants must fit FP32" }
}.toString()
