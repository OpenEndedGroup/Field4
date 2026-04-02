package afield.four.modules

import afield.logger.lprintln
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private data class SlangTargetOptions(
    val stage: String,
    val glslProfile: String,
    val spirvProfile: String = "sm_6_5"
)

private data class SlangExecutableCandidate(
    val command: String,
    val description: String
)

data class SlangEntryPoint(
    val name: String,
    val stage: String
)

data class SlangStageArtifacts(
    val kind: ShaderKind,
    val entryPoint: SlangEntryPoint,
    val glslSource: String,
    val reflectionJson: String,
    val spirv: ShaderSPIRVUtils.SPIRV
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
        return withTempSource(entryFileName, source) { candidate, tempDir, inputFile ->
            val outputFile = tempDir.resolve(entryFileName.substringBeforeLast('.', entryFileName) + ".glsl")
            runSlangc(
                candidate,
                listOf(
                    "-target", "glsl",
                    "-profile", options.glslProfile,
                    "-stage", options.stage,
                    "-no-mangle",
                    "-preserve-params",
                    "-matrix-layout-column-major",
                    "-o", outputFile.toString(),
                    inputFile.toString()
                )
            )

            if (!Files.exists(outputFile)) {
                throw CompilerError("slangc completed but produced no GLSL output for $entryFileName")
            }

            outputFile.readText(StandardCharsets.UTF_8).also { generatedGlsl ->
                lprintln(
                    "debug,slang",
                    "Generated GLSL for $entryFileName (${kind.name}):\n$generatedGlsl"
                )
            }
        }
    }

    fun compileVertexFragment(source: String, entryFileName: String = "vertex_fragment.slang"): Pair<SlangStageArtifacts, SlangStageArtifacts> {
        val vertex = compileSharedStage(source, ShaderKind.VERTEX_SHADER, entryFileName)
        val fragment = compileSharedStage(source, ShaderKind.FRAGMENT_SHADER, entryFileName)
        return vertex to fragment
    }

    fun compileSharedStage(source: String, kind: ShaderKind, entryFileName: String): SlangStageArtifacts {
        return withTempSource(entryFileName, source) { candidate, tempDir, inputFile ->
            val entryPoints = inventoryEntryPoints(candidate, tempDir, inputFile)
            val entryPoint = requireSingleEntryPoint(entryPoints, targetOptionsFor(kind).stage, entryFileName)
            compileStageArtifacts(candidate, tempDir, inputFile, kind, entryPoint, entryFileName)
        }
    }

    private fun targetOptionsFor(kind: ShaderKind): SlangTargetOptions = when (kind) {
        ShaderKind.VERTEX_SHADER -> SlangTargetOptions(stage = "vertex", glslProfile = "glsl_450")
        ShaderKind.FRAGMENT_SHADER -> SlangTargetOptions(stage = "fragment", glslProfile = "glsl_450")
        ShaderKind.COMPUTE_SHADER -> SlangTargetOptions(stage = "compute", glslProfile = "glsl_450")
        ShaderKind.MESH_SHADER -> SlangTargetOptions(stage = "mesh", glslProfile = "glsl_460")
        ShaderKind.TASK_SHADER -> SlangTargetOptions(stage = "task", glslProfile = "glsl_460")
        else -> throw IllegalArgumentException("Slang is not supported for shader kind $kind")
    }

    private fun <T> withTempSource(
        entryFileName: String,
        source: String,
        block: (candidate: SlangExecutableCandidate, tempDir: Path, inputFile: Path) -> T
    ): T {
        val tempDir = Files.createTempDirectory("afield-slang-")
        val inputFile = tempDir.resolve(entryFileName)
        inputFile.writeText(source, StandardCharsets.UTF_8)

        return try {
            withResolvedExecutable { candidate ->
                block(candidate, tempDir, inputFile)
            }
        } catch (e: IOException) {
            val hint = e.message ?: "unable to launch slangc"
            throw CompilerError(hint)
        } finally {
            cleanupTempDirectory(tempDir)
        }
    }

    private fun <T> withResolvedExecutable(block: (SlangExecutableCandidate) -> T): T {
        val candidates = executableCandidates()
        var lastMissingExecutable: IOException? = null

        for (candidate in candidates) {
            try {
                return block(candidate)
            } catch (e: IOException) {
                if (isExecutableNotFound(e)) {
                    lastMissingExecutable = e
                    continue
                }
                throw CompilerError(
                    "Failed to launch slangc via ${candidate.description} (${candidate.command}): ${e.message ?: "unknown error"}"
                )
            }
        }

        throw CompilerError(buildExecutableNotFoundMessage(candidates, lastMissingExecutable))
    }

    private fun inventoryEntryPoints(
        candidate: SlangExecutableCandidate,
        tempDir: Path,
        inputFile: Path
    ): List<SlangEntryPoint> {
        val reflectionFile = tempDir.resolve("inventory-reflection.json")
        runSlangc(
            candidate,
            listOf(
                "-target", "spirv",
                "-profile", "sm_6_5",
                "-no-codegen",
                "-reflection-json", reflectionFile.toString(),
                inputFile.toString()
            )
        )

        if (!Files.exists(reflectionFile)) {
            throw CompilerError("slangc completed but produced no reflection JSON for ${inputFile.fileName}")
        }

        val root = JSONObject(reflectionFile.readText(StandardCharsets.UTF_8))
        val entryPoints = mutableListOf<SlangEntryPoint>()
        val jsonEntryPoints = root.optJSONArray("entryPoints") ?: return emptyList()
        for (i in 0 until jsonEntryPoints.length()) {
            val entry = jsonEntryPoints.getJSONObject(i)
            entryPoints += SlangEntryPoint(
                name = entry.getString("name"),
                stage = entry.getString("stage").lowercase()
            )
        }
        return entryPoints
    }

    private fun requireSingleEntryPoint(
        entryPoints: List<SlangEntryPoint>,
        stage: String,
        entryFileName: String
    ): SlangEntryPoint {
        val matches = entryPoints.filter { it.stage == stage.lowercase() }
        if (matches.size == 1) return matches.first()

        val available = if (entryPoints.isEmpty()) {
            "none"
        } else {
            entryPoints.joinToString(", ") { "${it.stage}:${it.name}" }
        }
        throw CompilerError(
            "Expected exactly one $stage entry point in $entryFileName, found ${matches.size}. Available entry points: $available"
        )
    }

    private fun compileStageArtifacts(
        candidate: SlangExecutableCandidate,
        tempDir: Path,
        inputFile: Path,
        kind: ShaderKind,
        entryPoint: SlangEntryPoint,
        entryFileName: String
    ): SlangStageArtifacts {
        val options = targetOptionsFor(kind)
        val stem = "${entryFileName.substringBeforeLast('.', entryFileName)}-${options.stage}"
        val outputFile = tempDir.resolve("$stem.glsl")
        val spirvFile = tempDir.resolve("$stem.spv")
        val reflectionFile = tempDir.resolve("$stem-reflection.json")

        runSlangc(
            candidate,
            listOf(
                "-target", "glsl",
                "-profile", options.glslProfile,
                "-stage", options.stage,
                "-entry", entryPoint.name,
                "-no-mangle",
                "-preserve-params",
                "-matrix-layout-column-major",
                "-o", outputFile.toString(),
                inputFile.toString()
            )
        )

        if (!Files.exists(outputFile)) {
            throw CompilerError("slangc completed but produced no GLSL output for ${entryPoint.name}")
        }

        val generatedGlsl = outputFile.readText(StandardCharsets.UTF_8).also {
            lprintln(
                "debug,slang",
                "Generated GLSL for ${entryFileName.substringBeforeLast('.', entryFileName)}.${entryPoint.name} (${kind.name}):\n$it"
            )
        }

        runSlangc(
            candidate,
            listOf(
                "-target", "spirv",
                "-profile", options.spirvProfile,
                "-stage", options.stage,
                "-entry", entryPoint.name,
                "-no-mangle",
                "-preserve-params",
                "-matrix-layout-column-major",
                "-reflection-json", reflectionFile.toString(),
                "-o", spirvFile.toString(),
                inputFile.toString()
            )
        )

        if (!Files.exists(spirvFile)) {
            throw CompilerError("slangc completed but produced no SPIR-V output for ${entryPoint.name}")
        }
        if (!Files.exists(reflectionFile)) {
            throw CompilerError("slangc completed but produced no reflection JSON for ${entryPoint.name}")
        }

        return SlangStageArtifacts(
            kind = kind,
            entryPoint = entryPoint,
            glslSource = generatedGlsl,
            reflectionJson = reflectionFile.readText(StandardCharsets.UTF_8),
            spirv = ShaderSPIRVUtils.wrapBytecode(Files.readAllBytes(spirvFile))
        )
    }

    private fun runSlangc(candidate: SlangExecutableCandidate, arguments: List<String>): String {
        val command = mutableListOf(candidate.command)
        command.addAll(arguments)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val message = output.trim().ifBlank { "slangc exited with code $exitCode" }
            throw CompilerError(message)
        }
        return output
    }

    private fun cleanupTempFile(path: Path) {
        runCatching { path.deleteIfExists() }
    }

    private fun cleanupTempDirectory(path: Path) {
        runCatching {
            if (!Files.exists(path)) return@runCatching
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { cleanupTempFile(it) }
            }
        }
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
