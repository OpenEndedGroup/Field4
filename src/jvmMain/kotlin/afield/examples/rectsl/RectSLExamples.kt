package afield.examples.rectsl

import afield.KotlinInterface2
import afield.KotlinScriptEngine
import afield.rectsl.RectAnimationOracle
import afield.rectsl.compileRectSL
import afield.rectsl.lowerSampledScene
import afield.rectsl.rectMaterialShaderCode
import afield.stdlib.Functions
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
        ->wall 30 4 meters
        ->duration 10
        ->fps 60

        "left" rect
          -5 0 ->xy
          1 ->square
          0xff3300 solid ->fill
          '( 0=-5   5=0   10=5 ) ->x
        ;

        dup "right" ->rename
          ->mirror-x
          0x3366ff solid ->fill
        ;
    """.trimIndent()

    val layeredTracks: String = """
        #rectsl
        ->wall 16 9 meters
        ->duration 6
        ->fps 30

        linear-track "rise" '( 0=-3   3=0   6=3 )
        hold-track "pulse" '( 0=0.2   2=1.0   4=0.45 )

        "bar-template" rect
          0 -2 ->xy
          0.5 1.4 ->wh
          "rise" track-ref ->y
          "pulse" track-ref smooth ->alpha
          0xeeeeee solid ->fill
        ;

        8 '(
          "bar-template" ->select
            clone
            i ->layer
            i 0.1 * ->z
            i 3.5 - 0.8 * -2 ->xy
        ) repeat
    """.trimIndent()

    val groupsAndStackSLMaterial: String = """
        #rectsl
        ->wall 10 10 meters
        ->duration 8
        ->fps 30

        material petals-tint '(
          uv 4.0 * sin abs
        )

        "orbit" group
          "cell" rect
            0 0 ->xy
            0.75 ->square
            "petal" ->tag
            '( 0 0   4 3.14159   8 6.28318 ) ->rot
            0xffffff solid ->fill
          ;

          0 6 '(
            "cell" ->select
              clone
              i 2 * 5 - 0 ->xy
          ) range
        end-group

        "orbit" ->select descendants
          "petal" tagged
          "petals" ->group-selected
          selected descendants rects '(
            0.85 ->alpha
            petals-tint ->material
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

        checkFaceTravelHeading()
        checkLifeWindows()
        checkSplit()
        checkRecursiveSplit()
        checkArrayGenerators()
        checkGalleryWorkspace()
        checkPublicDocsExamples()
        checkCheatsheetCoverage()
        println("rectsl examples passed")
    }

    /**
     * `face-travel` must set rotation to the heading of the object's own x/y tracks.
     * A dart travelling the +x/+y diagonal has a constant heading of atan2(1,1)=pi/4,
     * an unambiguous oracle for the finite-difference [HeadingTrack]. `face-travel-at`
     * adds a fixed offset, checked here as pi/4 + pi/2.
     */
    private fun checkFaceTravelHeading() {
        fun rotationAtMid(word: String): Double {
            val src = """
                #rectsl
                ->wall 10 10 abstract
                ->duration 4
                ->fps 30
                "dart" rect
                  1 0.3 ->wh
                  0xffffff solid ->fill
                  '( 0=-5  4=5 ) ->x
                  '( 0=-5  4=5 ) ->y
                  $word
                ;
            """.trimIndent()
            val r = RectAnimationOracle.compile(src)
            check(r.ok && r.programOrNull != null) { "$word failed:\n" + r.diagnostics.joinToString("\n") }
            return r.program.sample(2.0).rects.first().rotation
        }
        val quarter = Math.PI / 4.0
        val plain = rotationAtMid("->face-travel")
        check(Math.abs(plain - quarter) < 1e-3) { "face-travel heading wrong: got $plain expected $quarter" }
        val offset = rotationAtMid("1.5707963 ->face-travel-at")
        check(Math.abs(offset - (quarter + Math.PI / 2.0)) < 1e-3) {
            "face-travel-at heading wrong: got $offset expected ${quarter + Math.PI / 2.0}"
        }
        println("rectsl face-travel heading check: face-travel=$plain, face-travel-at=$offset")
    }

    /**
     * `appear`/`vanish` hard-gate alpha at fixed times; `live birth life` additionally
     * shifts the object's tracks so local time 0 is its birth. Checked with exact 0/1
     * alpha at window edges and the trajectory's start value landing at the birth time.
     */
    private fun checkLifeWindows() {
        val src = """
            #rectsl
            ->wall 10 10 abstract
            ->duration 6
            ->fps 30
            "beacon" rect
              0 0 ->xy
              1 ->square
              0xffffff solid ->fill
              1 ->appear
              4 ->vanish
            ;
            "mover" rect
              1 ->square
              0xffffff solid ->fill
              '( 0=-4  2=4 ) ->x
              2 2 ->live
            ;
        """.trimIndent()
        val r = RectAnimationOracle.compile(src)
        check(r.ok && r.programOrNull != null) { "life windows failed:\n" + r.diagnostics.joinToString("\n") }
        val p = r.program
        fun rect(t: Double, id: String) = p.sample(t).rects.first { it.id == id }
        check(rect(0.5, "beacon").alpha == 0.0) { "beacon should be invisible before appear" }
        check(rect(2.0, "beacon").alpha == 1.0) { "beacon should be visible inside window" }
        check(rect(4.5, "beacon").alpha == 0.0) { "beacon should be invisible after vanish" }
        check(rect(1.0, "mover").alpha == 0.0) { "mover should be invisible before birth" }
        check(rect(2.0, "mover").alpha == 1.0) { "mover should be visible at birth" }
        val bornX = rect(2.0, "mover").center.x
        check(Math.abs(bornX - (-4.0)) < 1e-9) { "mover trajectory should start (-4) at its birth t=2, got $bornX" }
        check(rect(4.5, "mover").alpha == 0.0) { "mover should be invisible after death" }
        println("rectsl life-window check: beacon+mover gating OK (born x=$bornX)")
    }

    /**
     * `split` must (a) vanish the template at the split time, (b) birth `count` shards
     * exactly at the template's split-point position, and (c) let each shard's authored
     * flight carry it outward from there. The shell rises to (0,0) by t=2, so all shards
     * start at the origin and later fly out to a nonzero radius.
     */
    private fun checkSplit() {
        val src = """
            #rectsl
            ->wall 10 10 abstract
            ->duration 6
            ->fps 30
            "shell" rect
              0.5 ->square
              0xffffff solid ->fill
              '( 0=-4  2=0 ) ->y
              4 2 '(
                '( 0=0  2=5 )  f 6.28318 * cos  value*
                '( 0=0  2=5 )  f 6.28318 * sin  value*
                ->translate
              ) ->split
            ;
        """.trimIndent()
        val r = RectAnimationOracle.compile(src)
        check(r.ok && r.programOrNull != null) { "split failed:\n" + r.diagnostics.joinToString("\n") }
        val p = r.program
        fun shards(t: Double) = p.sample(t).rects.filter { it.id != "shell" }
        fun shell(t: Double) = p.sample(t).rects.first { it.id == "shell" }
        check(shell(1.0).alpha == 1.0) { "shell should be alive before the split" }
        val before = shards(1.0)
        check(before.size == 4) { "expected 4 shards, got ${before.size}" }
        check(before.all { it.alpha == 0.0 }) { "shards must be invisible before the split" }
        check(shell(2.0).alpha == 0.0) { "shell must vanish at the split" }
        val atSplit = shards(2.0)
        check(atSplit.all { it.alpha == 1.0 }) { "shards must be visible at the split" }
        check(atSplit.all { Math.hypot(it.center.x, it.center.y) < 1e-9 }) { "shards must start at the split point" }
        check(shards(4.0).all { Math.hypot(it.center.x, it.center.y) > 1.0 }) { "shards should fly outward after the split" }
        println("rectsl split check: shell -> 4 shards born at split point, flying outward")
    }

    /**
     * A nested `split` must compose: a grand-shard is born at `t1 + t2` (its split time
     * adds onto its parent's) and starts exactly on its parent shard's split-point. Here
     * the seed splits at t=1 into a shard flying +x; that shard splits again at local t=1
     * (global t=2) into a grand-shard flying +y. At t=1 only the shard is alive at (0,0);
     * at t=2 the shard has died and the grand-shard is born at (1,0) — where the shard was.
     */
    private fun checkRecursiveSplit() {
        val src = """
            #rectsl
            ->wall 10 10 abstract
            ->duration 8
            ->fps 30
            "seed" rect
              0.4 ->square
              0xffffff solid ->fill
              1 1 '(
                '( 0=0  4=4 ) linear ->x
                1 1 '(
                  '( 0=0  4=4 ) linear ->y
                ) ->split
              ) ->split
            ;
        """.trimIndent()
        val r = RectAnimationOracle.compile(src)
        check(r.ok && r.programOrNull != null) { "recursive split failed:\n" + r.diagnostics.joinToString("\n") }
        val p = r.program
        fun near(a: Double, b: Double) = Math.abs(a - b) < 1e-9
        fun visibleNonSeed(t: Double) = p.sample(t).rects.filter { it.id != "seed" && it.alpha > 0.5 }
        check(p.sample(1.0).rects.size == 3) { "recursive split should make seed + shard + grand = 3 rects" }
        val atSplit1 = visibleNonSeed(1.0)
        check(atSplit1.size == 1 && near(atSplit1[0].center.x, 0.0) && near(atSplit1[0].center.y, 0.0)) {
            "at t=1 exactly the shard should be alive at (0,0), got ${atSplit1.map { it.center }}"
        }
        val atSplit2 = visibleNonSeed(2.0)
        check(atSplit2.size == 1 && near(atSplit2[0].center.x, 1.0) && near(atSplit2[0].center.y, 0.0)) {
            "at t=2 exactly the grand-shard should be born at (1,0), got ${atSplit2.map { it.center }}"
        }
        println("rectsl recursive-split check: grand-shard born at t1+t2 on the parent split point")
    }

    /**
     * `arc`, `spiral`, and `phyllotaxis` place copies at known polar positions. Checked
     * against closed-form expectations: an arc across [0, pi] hits (2,0)/(0,2)/(-2,0);
     * a one-turn spiral lands its endpoints on +x at radii 1 and 3; the sunflower's
     * radii follow scale·√i = 0, 1, √2.
     */
    private fun checkArrayGenerators() {
        fun header(body: String) = """
            #rectsl
            ->wall 12 12 abstract
            ->duration 4
            ->fps 30
            "t" rect
              0.5 ->square
              0xffffff solid ->fill
              $body
            ;
        """.trimIndent()
        fun rectsOf(src: String) = run {
            val r = RectAnimationOracle.compile(src)
            check(r.ok && r.programOrNull != null) { "generator failed:\n" + r.diagnostics.joinToString("\n") }
            r.program.sample(0.0).rects
        }
        fun near(a: Double, b: Double) = Math.abs(a - b) < 1e-6

        val arc = rectsOf(header("3 2 0 3.141592653589793 '( ) arc"))
        check(arc.size == 3) { "arc expected 3 copies, got ${arc.size}" }
        check(arc.any { near(it.center.x, 2.0) && near(it.center.y, 0.0) }) { "arc missing (2,0)" }
        check(arc.any { near(it.center.x, 0.0) && near(it.center.y, 2.0) }) { "arc missing (0,2)" }
        check(arc.any { near(it.center.x, -2.0) && near(it.center.y, 0.0) }) { "arc missing (-2,0)" }

        val spiral = rectsOf(header("2 1 3 1 '( ) spiral")).map { Math.hypot(it.center.x, it.center.y) }.sorted()
        check(near(spiral[0], 1.0) && near(spiral[1], 3.0)) { "spiral radii wrong: $spiral" }

        val phyllo = rectsOf(header("3 1 '( ) sunflower")).map { Math.hypot(it.center.x, it.center.y) }.sorted()
        check(near(phyllo[0], 0.0) && near(phyllo[1], 1.0) && near(phyllo[2], Math.sqrt(2.0))) {
            "phyllotaxis radii wrong: $phyllo"
        }
        println("rectsl array-generator check: arc/spiral/phyllotaxis placement OK")
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

    /** Compile every complete RectSL listing shown on the public examples page. */
    private fun checkPublicDocsExamples() {
        val file = File(System.getProperty("user.dir"), "public/docs/rectsl_examples.md")
        if (!file.exists()) return
        val blocks = rectslCodeBlocks(file.readText())
        check(blocks.isNotEmpty()) { "public RectSL examples page has no RectSL code blocks" }

        val placeholderCurves = with(Functions) {
            val curve = gaussian(mean = 0.5, sigma = 0.16, amplitude = 1.0)
            mapOf("target" to curve, "seed" to curve, "fit" to curve)
        }
        var escapeEngine: KotlinScriptEngine? = null
        try {
            blocks.forEachIndexed { index, source ->
                val result = if ("{{" in source) {
                    val engine = escapeEngine
                        ?: KotlinInterface2(filePrefix = "RECTSL_DOCS_ESCAPE_").also { escapeEngine = it }
                    compileRectSL(source, curves = placeholderCurves, engine = engine)
                } else {
                    compileRectSL(source, curves = placeholderCurves)
                }
                check(result.ok && result.programOrNull != null) {
                    "public RectSL example ${index + 1} failed to compile:\n" +
                        result.diagnostics.joinToString("\n")
                }
            }
        } finally {
            escapeEngine?.dispose()
        }
        println("rectsl public docs compiled ${blocks.size} worked example(s)")
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
            "->wall meters" to listOf("->wall", "meters"), "->wall pixels" to listOf("->wall", "pixels"),
            "->wall abstract" to listOf("->wall", "abstract"), "->fit contain" to listOf("->fit", "contain"),
            "->fit cover" to listOf("->fit", "cover"), "->fit stretch" to listOf("->fit", "stretch"),
            "material name '( ... )" to listOf("material"), "name ->material" to listOf("->material"),
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
        listOf("->rot", "->rotation"), listOf("rot-track", "rotation-track"),
        listOf("neg", "negate"), listOf("sat", "saturate"), listOf("mix", "lerp"),
        listOf("copy", "clone"), listOf("copy-deep", "clone-deep"), listOf("end", "end-group"),
        listOf("filter", "where"), listOf("loop-count", "n"),
        listOf("<", "lt"), listOf("<=", "lte"), listOf(">", "gt"), listOf(">=", "gte"),
        listOf("=", "eq"), listOf("!=", "<>", "neq"), listOf("ease-smoothstep", "smooth"),
        listOf("colormap", "cmap"), listOf("heat", "heatmap"),
        listOf("line", "plot"), listOf("phyllotaxis", "sunflower"),
    )
    private val CANONICAL: Map<String, String> =
        ALIAS_GROUPS.flatMap { g -> g.map { it to g.first() } }.toMap()

    private fun canonicalOperator(word: String): String = CANONICAL[word] ?: word
}
