package drowning

import afield.stdlib.shaderTemplate
import afield.stdlib.stackAutoShaderSources
import java.io.File

/**
 * `dust` from a terminal: dump the GLSL, measure a network, print a force program's GLSL.
 *
 * The counterpart to [PsubMain], and the same two jobs. `--shaders` is load-bearing rather than a
 * convenience: `build_starting_point.py` embeds the dumped GLSL into the Field document, and a document
 * carrying GLSL one revision behind its Kotlin is a box that throws
 * `can't find <name> in a mesh in this module` the moment it runs while looking perfectly fine on the
 * page. Dump first, build second.
 *
 * ```bash
 * ./gradlew --no-daemon personalJar
 * java -cp build/personal/afield-personal.jar:<kotlin-stdlib> drowning.DustMainKt --shaders /tmp/dust
 * java -cp ... drowning.DustMainKt --onn 40000          # build a network and describe it
 * java -cp ... drowning.DustMainKt --program edgeForce/rope
 * ```
 *
 * `--shaders` writes the *unexpanded* GLSL — with `{{ dust.stereo }}` and `{{ stack.auto.* }}` still in
 * it — because that is what belongs in a box: Field expands those, and a document holding the expanded
 * form would have the stereo mode and the force programs frozen into it. For a version `glslc` can chew
 * on, `--shaders-expanded` fills the slots with the multiview path and the default programs.
 *
 * The real check is `DustWorkout`, which compiles everything through Field's own preprocessor and
 * shaderc:
 *
 * ```bash
 * python3 tools/run_field_tests.py --refresh-catalog --id drowning.dust.workout
 * ```
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: DustMain [--shaders DIR] [--shaders-expanded DIR] [--onn N] [--extent E]")
        println("                [--program SLOT/NAME] [--programs] [--serial]")
        println()
        println("  --shaders DIR           write the box GLSL, template slots intact")
        println("  --shaders-expanded DIR  write it with the multiview slot and the default programs filled")
        println("  --onn N                 build an orthant network over N random particles and describe it")
        println("  --extent E              half-extent of the cloud for --onn (default 1)")
        println("  --program SLOT/NAME     print one force program and the GLSL it compiles to")
        println("  --stacksl 'PROGRAM'     the same, for any StackSL string given on the command line")
        println("  --programs              list every force program")
        println("  --serial                build --onn without the parallel search, for a timing comparison")
        kotlin.system.exitProcess(1)
    }

    var shaderDir: String? = null
    var expandedDir: String? = null
    var onn = 0
    var extent = 1f
    var program: String? = null
    var inlineProgram: String? = null
    var listPrograms = false
    var serial = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--shaders" -> shaderDir = args[++i]
            "--shaders-expanded" -> expandedDir = args[++i]
            "--onn" -> onn = args[++i].toInt()
            "--extent" -> extent = args[++i].toFloat()
            "--program" -> program = args[++i]
            // Any StackSL string, straight from the shell: diagnostics at every severity, the stack
            // analysis, the stack.auto report, and the GLSL. The question "does this warn?" came up
            // three times before this existed.
            "--stacksl" -> inlineProgram = args[++i]
            "--programs" -> listPrograms = true
            "--serial" -> serial = true
            else -> {
                println("unknown argument '${args[i]}'")
                kotlin.system.exitProcess(1)
            }
        }
        i++
    }

    shaderDir?.let { writeShaders(File(it), expand = false) }
    expandedDir?.let { writeShaders(File(it), expand = true) }

    if (listPrograms) {
        DustPrograms.ALL.forEach { (name, source) ->
            println("--- $name")
            source.lines().forEach { println("    $it") }
        }
    }

    program?.let { name ->
        val source = DustPrograms.ALL[name] ?: run {
            println("no such program '$name'. Known: ${DustPrograms.ALL.keys.joinToString(", ")}")
            kotlin.system.exitProcess(1)
        }
        println("--- $name")
        println(source)
        println("--- DIAGNOSTICS (every severity, and the stack analysis)")
        val result = afield.stacksl.StackSLCompiler.compile(
            source,
            afield.stacksl.StackSLCompileOptions(
                prefix = name.substringBefore('/'), includeHeader = false, declareSamplers = false
            )
        )
        if (result.diagnostics.isEmpty()) println("    (none)")
        result.diagnostics.forEach { println("    $it") }
        result.analysis.functions.forEach { f ->
            println("    fn ${f.name}: $f")
        }
        result.analysis.danglingWires.forEach { println("    dangling: $it") }
        println("--- GLSL")
        println(
            afield.stacksl.compileStackSLToGlsl(
                source = source,
                prefix = name.substringBefore('/'),
                declareSamplers = false
            )
        )
    }

    inlineProgram?.let { text ->
        println("--- program")
        println(text)
        println("--- stack.auto report")
        println(afield.stdlib.stackAutoShaderReport(text, "probe"))
        val result = afield.stacksl.StackSLCompiler.compile(
            text,
            afield.stacksl.StackSLCompileOptions(
                prefix = "probe", includeHeader = false, declareSamplers = false
            )
        )
        println("--- diagnostics (every severity)")
        if (result.diagnostics.isEmpty()) println("    (none)")
        result.diagnostics.forEach { println("    $it") }
        println("--- stack analysis")
        result.analysis.functions.forEach { println("    $it") }
    }

    if (onn > 0) describeNetwork(onn, extent, serial)
}

private fun writeShaders(dir: File, expand: Boolean) {
    dir.mkdirs()
    // Expansion needs a Box to hold the template slot and the force programs — the same route the
    // workout takes, so what lands here is what a box would compile.
    val box = if (!expand) null else afield.Box("dust-main-dump").also { b ->
        b.shaderTemplate["dust"] = mapOf("stereo" to DustStereoMode.MULTIVIEW.shaderSlot)
        DustPrograms.DEFAULTS.forEach { (slot, source) -> b.stackAutoShaderSources[slot] = source }
    }

    DustShaders.ALL.forEach { (name, entry) ->
        val (source, stage) = entry
        val text = if (box == null) source else afield.stdlib.preprocess(
            box, source,
            when (stage) {
                DustShaders.ShaderStage.VERTEX -> afield.four.modules.ShaderKind.VERTEX_SHADER
                DustShaders.ShaderStage.FRAGMENT -> afield.four.modules.ShaderKind.FRAGMENT_SHADER
                DustShaders.ShaderStage.COMPUTE -> afield.four.modules.ShaderKind.COMPUTE_SHADER
            }
        )
        File(dir, name).writeText(text)
        println("wrote ${File(dir, name)} (${text.lines().size} lines)")
    }
}

private fun describeNetwork(count: Int, extent: Float, serial: Boolean) {
    val settings = DustOrthantSettings(parallel = !serial)
    val field = DustField.random(count, extent = extent)
    println(field.describe())

    val t0 = System.nanoTime()
    val directed = DustOrthant.directedEdges(field, settings)
    val t1 = System.nanoTime()
    val links = DustOrthant.trim(field, directed, settings)
    val t2 = System.nanoTime()

    println(links.describe())
    println(
        "     search %.0f ms (%s), trim %.0f ms".format(
            (t1 - t0) / 1e6, if (serial) "serial" else "parallel", (t2 - t1) / 1e6
        )
    )
    println("     octant occupancy " + directed.octantOccupancy().toList())

    val historyCapacity = 64
    println(
        "     device %.1f MB (state x2, links, %d frames of history)".format(
            DustLayout.deviceBytes(count, historyCapacity) / 1e6, historyCapacity
        )
    )
    println("     " + DustLook().forMeanEdge(links.meanRestLength()).forExtent(extent))
}
