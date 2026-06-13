package afield.stdlib

import afield.Box
import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompileResult
import afield.stacksl.StackSLCompiler
import afield.stacksl.compileStackSLToGlsl

var Box.stackSL by Inheritance<String?>()

fun Box.stackSLPrefix(): String {
    val base = id.take(12).replace('-', '_').ifBlank { "box" }
    return "stacksl_$base"
}

fun Box.compileStackSL(
    source: String = stackSL ?: "",
    prefix: String = stackSLPrefix(),
    entrypoint: String = "main",
    sampleTextures: List<String> = listOf("source"),
    tolerant: Boolean = false,
    declareSamplers: Boolean = true,
    externalizeConstants: Boolean = false
): StackSLCompileResult =
    StackSLCompiler.compile(
        source,
        StackSLCompileOptions(
            prefix = prefix,
            entrypoint = entrypoint,
            sampleTextures = sampleTextures,
            tolerant = tolerant,
            declareSamplers = declareSamplers,
            externalizeConstants = externalizeConstants
        )
    )

fun Box.stackSLToGlsl(
    source: String = stackSL ?: "",
    prefix: String = stackSLPrefix(),
    entrypoint: String = "main",
    sampleTextures: List<String> = listOf("source"),
    tolerant: Boolean = false,
    declareSamplers: Boolean = true,
    externalizeConstants: Boolean = false
): String =
    compileStackSLToGlsl(
        source = source,
        prefix = prefix,
        entrypoint = entrypoint,
        sampleTextures = sampleTextures,
        tolerant = tolerant,
        declareSamplers = declareSamplers,
        externalizeConstants = externalizeConstants
    )
