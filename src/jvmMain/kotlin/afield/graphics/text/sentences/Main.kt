package afield.graphics.text.sentences

/**
 * Command line preview: JSON in, SVG out.
 *
 * A thin wrapper over [SentenceDiagram] -- for driving this from Field, use that class
 * directly and ignore all of the below.
 *
 *     kotlin -cp ... afield.graphics.text.sentences.MainKt --in 3b.json --depth --out 3b.svg
 *     optional/sentence-parser/.venv/bin/python python/fieldsentences "..." | kotlin ... afield.graphics.text.sentences.MainKt --out out.svg
 */
fun main(args: Array<String>) {
    fun flag(name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    fun has(name: String) = args.contains(name)

    if (has("--help") || has("-h")) {
        println(
            """
            sentences -- lay out a sentence diagram and dump it as SVG

              --in FILE        diagram JSON (default: stdin)
              --sentence TEXT  ask the parser server instead of reading JSON
              --url URL        server URL (default http://127.0.0.1:9500)
              --out FILE       write SVG here (default: stdout)
              --index N        which sentence, if the JSON holds several (default 0)

              --depth          hang each word at its own tree depth (as the originals do)
              --flat           all words on one baseline
              --wander SEED    pseudo-random word heights (the default, seed 1)
              --amplitude N    how far the heights wander (default 90)
              --heights CSV    explicit baselines, one per token
              --jitter N       loosen --depth by up to N either way

              --scheme NAME    sd_collapsed (default) | sd_basic | ud
              --hug            tree hugs the words instead of using depth layers
              --check          report any inconsistencies in the JSON and exit

              --layerGap N     tree layer spacing (default 50)
              --noLabels       omit relation labels (arcs pull in much closer)
              --labelSize N    relation label size (default 11)
              --arcRatio N     arc depth as a fraction of its span (default 0.5)
              --sheared        arcs drape parallel to their two words (differing heights)
              --parabola       arcs as true parabolas; leaves words at an angle
              --arcLanes       arcs in lanes below a common baseline (the pendulous way)
              --laneGap N      vertical spacing between arc lanes, with --arcLanes (default 50)
              --unevenLanes    do not compensate lane depth for word height
              --margin N       clear space above the first arc lane (default 50)
              --perArc         lanes start below each arc's own words, not below all of them
            """.trimIndent()
        )
        return
    }

    val d = SentenceDiagram()
    flag("--url")?.let { d.url = it }
    when {
        flag("--sentence") != null -> d.text = flag("--sentence")
        flag("--in") != null -> d.loadFile(flag("--in")!!)
        else -> d.load(System.`in`.readBytes().decodeToString())
    }
    flag("--index")?.let { d.index = it.toInt() }

    val problems = d.problems()
    if (has("--check")) {
        if (problems.isEmpty()) println("ok -- ${d.sentence.tokens.size} tokens, no problems")
        else problems.forEach { println("problem: $it") }
        return
    }
    // Worth saying out loud rather than drawing something quietly wrong.
    problems.forEach { System.err.println("warning: $it") }

    d.scheme = when (flag("--scheme")) {
        null, "sd_collapsed" -> Scheme.SD_COLLAPSED
        "sd_basic" -> Scheme.SD_BASIC
        "ud" -> Scheme.UD
        else -> throw IllegalArgumentException("unknown scheme ${flag("--scheme")}")
    }

    if (has("--hug")) d.nodeY = NodeY.HUG_CHILDREN
    if (has("--noLabels")) d.arcLabels = false
    flag("--labelSize")?.let { d.labelFontSize = it.toDouble() }
    if (has("--sheared")) d.arcShape = ArcShape.SHEARED
    if (has("--parabola")) d.arcShape = ArcShape.PARABOLA
    if (has("--arcLanes")) d.arcDepth = ArcDepth.LANES
    flag("--arcRatio")?.let { d.arcSpanRatio = it.toDouble() }
    if (has("--perArc")) d.laneOrigin = LaneOrigin.PER_ARC
    if (has("--unevenLanes")) d.evenLanes = false
    flag("--layerGap")?.let { d.layerGap = it.toDouble() }
    flag("--laneGap")?.let { d.laneGap = it.toDouble() }
    flag("--margin")?.let { d.baselineMargin = it.toDouble() }

    val explicit = flag("--heights")?.split(",")?.map { it.trim().toDouble() }
    d.heights = when {
        explicit != null -> Heights.Explicit(explicit)
        has("--flat") -> Heights.Flat()
        has("--depth") -> Heights.ByTreeDepth(jitter = flag("--jitter")?.toDouble() ?: 0.0)
        else -> Heights.Wandering(
            seed = flag("--wander")?.toLong() ?: 1L,
            amplitude = flag("--amplitude")?.toDouble() ?: 90.0,
        )
    }

    val out = flag("--out")
    if (out != null) {
        // Rendered through Field's own FLine -> SVG path; there is no second renderer.
        d.writeSvg(out)
        val diagram = d.diagram()
        System.err.println(
            "wrote $out -- ${diagram.words.size} words, ${diagram.nodes.size} tree nodes, " +
                "${diagram.arcs.size} arcs in ${diagram.deepestLane} lanes, " +
                "${diagram.bounds.width.toInt()}x${diagram.bounds.height.toInt()}"
        )
    } else {
        print(d.svg())
    }
}
