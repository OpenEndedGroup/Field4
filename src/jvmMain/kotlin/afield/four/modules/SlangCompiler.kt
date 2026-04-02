package afield.four.modules

import afield.logger.lprintln
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private data class SlangTargetOptions(
    val stage: String,
    val profile: String
)

private data class SlangExecutableCandidate(
    val command: String,
    val description: String
)

object SlangCompiler {
    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    private fun executableCandidates(): List<SlangExecutableCandidate> {
        System.getenv("AFIELD_SLANGC")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return listOf(SlangExecutableCandidate(it, "AFIELD_SLANGC"))
            }

        System.getProperty("afield.slangc")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return listOf(SlangExecutableCandidate(it, "-Dafield.slangc"))
            }

        return buildList {
            if (isWindows) add(SlangExecutableCandidate("slangc.exe", "PATH"))
            add(SlangExecutableCandidate("slangc", "PATH"))
        }
    }

    fun compileToGlsl(source: String, kind: ShaderKind, entryFileName: String): String {
        val options = targetOptionsFor(kind)
        val tempDir = Files.createTempDirectory("afield-slang-")
        val inputFile = tempDir.resolve(entryFileName)
        val outputFile = tempDir.resolve(entryFileName.substringBeforeLast('.', entryFileName) + ".glsl")

        return try {
            inputFile.writeText(source, StandardCharsets.UTF_8)

            val candidates = executableCandidates()
            var lastMissingExecutable: IOException? = null

            for (candidate in candidates) {
                val command = mutableListOf(
                    candidate.command,
                    "-target",
                    "glsl",
                    "-profile",
                    options.profile,
                    "-stage",
                    options.stage,
                    "-no-mangle",
                    "-preserve-params",
                    "-matrix-layout-column-major",
                    "-o",
                    outputFile.toString(),
                    inputFile.toString()
                )

                val process = try {
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                } catch (e: IOException) {
                    if (isExecutableNotFound(e)) {
                        lastMissingExecutable = e
                        continue
                    }
                    throw CompilerError(
                        "Failed to launch slangc via ${candidate.description} (${candidate.command}): ${e.message ?: "unknown error"}"
                    )
                }

                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val message = if (output.isNotBlank()) output else "slangc exited with code $exitCode"
                    throw CompilerError(message)
                }

                if (!Files.exists(outputFile)) {
                    throw CompilerError("slangc completed but produced no GLSL output for $entryFileName")
                }

                return outputFile.readText(StandardCharsets.UTF_8).also { generatedGlsl ->
                    lprintln(
                        "debug,slang",
                        "Generated GLSL for $entryFileName (${kind.name}):\n$generatedGlsl"
                    )
                }
            }

            throw CompilerError(buildExecutableNotFoundMessage(candidates, lastMissingExecutable))
        } catch (e: IOException) {
            val hint = e.message ?: "unable to launch slangc"
            throw CompilerError(hint)
        } finally {
            cleanupTempFile(outputFile)
            cleanupTempFile(inputFile)
            cleanupTempDirectory(tempDir)
        }
    }

    private fun targetOptionsFor(kind: ShaderKind): SlangTargetOptions = when (kind) {
        ShaderKind.VERTEX_SHADER -> SlangTargetOptions(stage = "vertex", profile = "glsl_450")
        ShaderKind.FRAGMENT_SHADER -> SlangTargetOptions(stage = "fragment", profile = "glsl_450")
        ShaderKind.COMPUTE_SHADER -> SlangTargetOptions(stage = "compute", profile = "glsl_450")
        ShaderKind.MESH_SHADER -> SlangTargetOptions(stage = "mesh", profile = "glsl_460")
        ShaderKind.TASK_SHADER -> SlangTargetOptions(stage = "task", profile = "glsl_460")
        else -> throw IllegalArgumentException("Slang is not supported for shader kind $kind")
    }

    private fun cleanupTempFile(path: Path) {
        runCatching { path.deleteIfExists() }
    }

    private fun cleanupTempDirectory(path: Path) {
        runCatching { path.deleteIfExists() }
    }

    private fun isExecutableNotFound(e: IOException): Boolean {
        val message = e.message ?: return false
        return message.contains("No such file", ignoreCase = true) ||
            message.contains("CreateProcess error=2", ignoreCase = true) ||
            message.contains("error=2", ignoreCase = true)
    }

    private fun buildExecutableNotFoundMessage(
        candidates: List<SlangExecutableCandidate>,
        lastMissingExecutable: IOException?
    ): String {
        val searched = candidates.joinToString("\n") { "  - ${it.description}: ${it.command}" }
        val detail = lastMissingExecutable?.message?.let { "\nLast launch error: $it" } ?: ""
        return """
            Unable to find `slangc`.
            Searched:
            $searched$detail

            Install the Vulkan SDK (it includes `slangc`) and ensure `slangc` is on PATH,
            or set an explicit override with `AFIELD_SLANGC` or `-Dafield.slangc=/full/path/to/slangc`.
        """.trimIndent()
    }
}
