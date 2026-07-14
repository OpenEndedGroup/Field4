package afield.stdlib.linalg.codegen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private data class Arguments(val output: Path, val check: Boolean)

fun main(rawArguments: Array<String>) {
    val arguments = parseArguments(rawArguments)
    val emitter = KotlinEmitter(FieldAlgebraModel.build())
    val files = linkedMapOf(
        Path.of("afield/stdlib/linalg/Vectors.kt") to emitter.emitHeapVectors(),
        Path.of("afield/stdlib/linalg/arena/ArenaVectors.kt") to emitter.emitArenaVectors(),
        Path.of("org/joml/FieldVectorOperators.kt") to emitter.emitJomlTransitionOperators(),
    )

    val stale = files.mapNotNull { (relative, content) ->
        val target = arguments.output.resolve(relative)
        if (arguments.check) {
            if (!target.exists() || target.readText() != content) relative.toString() else null
        } else {
            target.parent.createDirectories()
            if (!target.exists() || target.readText() != content) {
                target.writeText(content)
                println("generated $relative")
            }
            null
        }
    }

    if (arguments.check && stale.isNotEmpty()) {
        error(
            buildString {
                appendLine("Generated linear-algebra sources are stale:")
                stale.forEach { appendLine("  $it") }
                append("Run ./gradlew generateLinalg")
            }
        )
    }
    if (arguments.check) println("generated linear-algebra sources are current")
}

private fun parseArguments(arguments: Array<String>): Arguments {
    var output: Path? = null
    var check = false
    var index = 0
    while (index < arguments.size) {
        when (val argument = arguments[index]) {
            "--output" -> {
                require(index + 1 < arguments.size) { "--output requires a path" }
                output = Path.of(arguments[++index]).toAbsolutePath().normalize()
            }
            "--check" -> check = true
            else -> error("Unknown argument: $argument")
        }
        index++
    }
    return Arguments(requireNotNull(output) { "--output is required" }, check)
}
