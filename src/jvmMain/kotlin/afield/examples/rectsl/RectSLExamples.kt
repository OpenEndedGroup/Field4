package afield.examples.rectsl

import afield.KotlinInterface2
import afield.KotlinScriptEngine
import afield.rectsl.RectAnimationOracle
import afield.rectsl.compileRectSL
import afield.rectsl.lowerSampledScene
import afield.rectsl.rectMaterialShaderCode
import java.io.File

/*
FIELD_TEST
id: rectsl.examples.smoke
runner: jvm-main
entrypoint: afield.examples.rectsl.RectSLExamples
tags: [smoke, examples, rectsl]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "rectsl examples passed"
*/
object RectSLExamples {
    val minimalMirror: String = """
        #rectsl
        wall 30 4 meters
        duration 10
        fps 60

        "left" rect
          -5 0 xy
          1 square
          0xff3300 solid fill
          '( 0=-5   5=0   10=5 ) x
        ;

        dup "right" rename
          mirror-x
          0x3366ff solid fill
        ;
    """.trimIndent()

    val layeredTracks: String = """
        #rectsl
        wall 16 9 meters
        duration 6
        fps 30

        linear-track "rise" '( 0=-3   3=0   6=3 )
        hold-track "pulse" '( 0=0.2   2=1.0   4=0.45 )

        "bar-template" rect
          0 -2 xy
          0.5 1.4 wh
          "rise" track-ref y
          "pulse" track-ref smooth alpha
          0xeeeeee solid fill
        ;

        8 '(
          "bar-template" select
            clone
            i layer
            i 0.1 * z
            i 3.5 - 0.8 * -2 xy
        ) repeat
    """.trimIndent()

    val groupsAndStackSLMaterial: String = """
        #rectsl
        wall 10 10 meters
        duration 8
        fps 30

        material petals-tint '(
          uv 4.0 * sin abs
        )

        "orbit" group
          "cell" rect
            0 0 xy
            0.75 square
            "petal" tag
            '( 0 0   4 3.14159   8 6.28318 ) rot
            0xffffff solid fill
          ;

          0 6 '(
            "cell" select
              clone
              i 2 * 5 - 0 xy
          ) range
        end-group

        "orbit" select descendants
          "petal" tagged
          "petals" group-selected
          selected descendants rects '(
            0.85 alpha
            petals-tint material
          ) each
        ;
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        listOf(
            "minimalMirror" to minimalMirror,
            "layeredTracks" to layeredTracks,
            "groupsAndStackSLMaterial" to groupsAndStackSLMaterial,
        ).forEach { (name, source) ->
            val result = RectAnimationOracle.compile(source)
            check(result.ok && result.programOrNull != null) {
                "$name failed:\n" + result.diagnostics.joinToString("\n")
            }
        }

        checkGalleryWorkspace()
        checkCheatsheetCoverage()
        println("rectsl examples passed")
    }

    /**
     * Every RectSL docs-gallery program (loose `*_rectsl_.rectsl` files under
     * `public/docs/assets/rectsl_gallery/`, rendered by `RectSLGalleryHarness`) must
     * compile, sample, lower to a non-empty mesh, and have its StackSL materials
     * compile — all CPU-only, so this guards the gallery without a GPU. Silently
     * skips if the workspace is absent (partial checkout).
     *
     * Files using a `{{ … }}` Kotlin escape are compiled with a real Kotlin engine
     * (lazily created, shared, disposed at the end). jvm-main is not a render loop, so
     * the lazy synchronous compile path is safe here — no pre-warm needed.
     */
    private fun checkGalleryWorkspace() {
        val dir = File(System.getProperty("user.dir"), "public/docs/assets/rectsl_gallery")
        val files = dir.listFiles { f -> f.name.endsWith("_rectsl_.rectsl") } ?: return
        check(files.isNotEmpty()) { "rectsl gallery workspace has no programs: ${dir.absolutePath}" }
        var escapeEngine: KotlinScriptEngine? = null
        try {
            val names = mutableSetOf<String>()
            files.sortedBy { it.name }.forEach { f ->
                val name = f.name.removeSuffix("_rectsl_.rectsl")
                names += name
                val source = f.readText()
                val result = if ("{{" in source) {
                    val engine = escapeEngine ?: KotlinInterface2(filePrefix = "RECTSL_SMOKE_ESCAPE_").also { escapeEngine = it }
                    compileRectSL(source, engine = engine)
                } else {
                    RectAnimationOracle.compile(source)
                }
                val program = result.programOrNull
                check(result.ok && program != null) {
                    "gallery '$name' failed to compile:\n" + result.diagnostics.joinToString("\n")
                }
                val mesh = lowerSampledScene(program.sample(program.duration * 0.5))
                check(mesh.vertices.isNotEmpty()) { "gallery '$name' lowered to an empty mesh (nothing to draw)" }
                val shader = rectMaterialShaderCode(program)
                check(shader.ok) {
                    "gallery '$name' materials failed to compile:\n" + shader.diagnostics.joinToString("\n")
                }
            }
            checkGalleryEdn(dir, names)
        } finally {
            escapeEngine?.dispose()
        }
    }

    private fun checkGalleryEdn(dir: File, names: Set<String>) {
        // The workspace is also a real openable Field document (offline viewer +
        // download/inspect). Parse metadata.edn through Field's own EDN reader so a
        // malformed document fails here rather than only in the viewer, and confirm
        // it carries a box per example.
        val edn = File(dir, "metadata.edn")
        check(edn.exists()) { "gallery workspace metadata.edn missing: ${edn.absolutePath}" }
        val transfers = afield.SavingContext().readEdnTransfersFromString(edn.readText())
        val boxIds = transfers.filter { it.id != "root" }.map { it.id }.toSet()
        check(boxIds.containsAll(names)) {
            "metadata.edn is missing boxes for workspace examples: ${names - boxIds}"
        }
        println("rectsl gallery workspace compiled ${names.size} program(s); metadata.edn boxes=${boxIds.size}")
    }

    // ── cheatsheet coverage guard ────────────────────────────────────────────────

    /**
     * Every operator documented in `rectsl_cheatsheet.md` must appear in at least one
     * *worked, animated* example — a gallery `*_rectsl_.rectsl` program or a
     * `data-lang=rectsl`/`data-lang=text` code block in `rectsl_examples.md`. This
     * makes "every operator is demonstrated" an enforced invariant, not a fact that
     * silently rots the next time a word is added.
     *
     * Aliases and alternate spellings collapse to one representative (see [ALIAS_GROUPS]):
     * demonstrating `+` covers `add`, `<=` covers `lte`, `clone` covers `copy`, etc.
     * The `Model` (literals/syntax) and `Kotlin entry points` cheatsheet sections are
     * API surface, not stack operators, so they are excluded. Silently skips if the
     * docs are absent (partial checkout).
     */
    private fun checkCheatsheetCoverage() {
        val root = File(System.getProperty("user.dir"))
        val cheatsheet = File(root, "public/docs/rectsl_cheatsheet.md").takeIf { it.exists() } ?: return
        val examples = File(root, "public/docs/rectsl_examples.md")
        val galleryDir = File(root, "public/docs/assets/rectsl_gallery")

        val operators = cheatsheetOperators(cheatsheet.readText())
        check(operators.size > 100) { "cheatsheet operator scrape looks wrong: only ${operators.size} found" }

        val corpus = buildString {
            galleryDir.listFiles { f -> f.name.endsWith("_rectsl_.rectsl") }?.forEach { append(it.readText()).append('\n') }
            if (examples.exists()) rectslCodeBlocks(examples.readText()).forEach { append(it).append('\n') }
        }
        val used = corpusTokens(corpus).map(::canonicalOperator).toSet()

        val missing = operators.filter { canonicalOperator(it) !in used }.map(::canonicalOperator).distinct()
        check(missing.isEmpty()) {
            "these cheatsheet operators have no worked/animated example (add one to the RectSL gallery or " +
                "rectsl_examples.md, or note the alias): ${missing.joinToString(", ")}"
        }
        println("rectsl cheatsheet coverage: all ${operators.size} operators demonstrated")
    }

    /** First-column backticked tokens of every operator table, phrase words expanded. */
    private fun cheatsheetOperators(text: String): List<String> {
        val skip = setOf("Model", "Kotlin entry points")
        val phrase = mapOf(
            "wall meters" to listOf("wall", "meters"), "wall pixels" to listOf("wall", "pixels"),
            "wall abstract" to listOf("wall", "abstract"), "fit contain" to listOf("fit", "contain"),
            "fit cover" to listOf("fit", "cover"), "fit stretch" to listOf("fit", "stretch"),
            "material name '( ... )" to listOf("material"), "name material" to listOf("material"),
        )
        val backtick = Regex("`([^`]+)`")
        val out = LinkedHashSet<String>()
        var section: String? = null
        text.lineSequence().forEach { line ->
            val header = Regex("^##+\\s+(.*)").find(line)
            if (header != null) { section = header.groupValues[1].trim(); return@forEach }
            if (section in skip) return@forEach
            val cell = Regex("^\\|\\s*(.+?)\\s*\\|").find(line)?.groupValues?.get(1) ?: return@forEach
            if (cell in setOf("Word", "Syntax", "API") || cell.all { it == '-' || it == '|' || it == ' ' }) return@forEach
            backtick.findAll(cell).forEach { m -> out.addAll(phrase[m.groupValues[1]] ?: listOf(m.groupValues[1])) }
        }
        return out.toList()
    }

    /** Bodies of ```… data-lang=rectsl/text …``` fenced blocks. */
    private fun rectslCodeBlocks(text: String): List<String> =
        Regex("```([^\\n]*)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .filter { "data-lang=rectsl" in it.groupValues[1] || "data-lang=text" in it.groupValues[1] }
            .map { it.groupValues[2] }
            .toList()

    /** Whitespace-split tokens with `#`-to-end-of-line comments stripped. */
    private fun corpusTokens(text: String): Set<String> =
        text.lineSequence()
            .map { it.substringBefore('#') }
            .flatMap { it.trim().split(Regex("\\s+")) }
            .filter { it.isNotEmpty() }
            .toSet()

    private val ALIAS_GROUPS: List<List<String>> = listOf(
        listOf("+", "add"), listOf("-", "sub"), listOf("*", "mul"), listOf("/", "div"),
        listOf("rot", "rotation"), listOf("rot-track", "rotation-track"),
        listOf("neg", "negate"), listOf("sat", "saturate"), listOf("mix", "lerp"),
        listOf("copy", "clone"), listOf("copy-deep", "clone-deep"), listOf("end", "end-group"),
        listOf("filter", "where"), listOf("loop-count", "n"),
        listOf("<", "lt"), listOf("<=", "lte"), listOf(">", "gt"), listOf(">=", "gte"),
        listOf("=", "eq"), listOf("!=", "<>", "neq"), listOf("ease-smoothstep", "smooth"),
    )
    private val CANONICAL: Map<String, String> =
        ALIAS_GROUPS.flatMap { g -> g.map { it to g.first() } }.toMap()

    private fun canonicalOperator(word: String): String = CANONICAL[word] ?: word
}
