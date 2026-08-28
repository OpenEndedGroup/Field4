package afield.docs

import afield.stdlib.SmileMdsDistance
import afield.stdlib.StatsDependence
import afield.stdlib.smileMds
import afield.stdlib.smileMdsDistances
import afield.stdlib.smileTsne
import afield.stdlib.vec
import afield.stdlib.vec2KnnGraph
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every number `public/docs/projection_examples.md` publishes, recomputed and checked against the
 * page text.
 *
 * ### Why this reads the Markdown
 *
 * A figure quoted in a document that no task declares as an input is decoration. The failure mode is
 * specific and has happened here: a page published `177` where the truth was `183`, `jvmTest` went
 * UP-TO-DATE over the Markdown-only edit, and a stale result passed. So this asserts in **both**
 * directions —
 *
 * - the recomputed value must format to exactly the string on the page, so a code change that moves
 *   a number turns the page red;
 * - the string must actually *be* on the page, so deleting the sentence is not a way to a green
 *   build. That is [CitationContractTest]'s `everyDerivationIsStillUsedByItsPage` argument in
 *   miniature.
 *
 * The page is declared as a `jvmTest` input in `build.gradle.kts` alongside the other pages whose
 * prose is under test, or an edit to it would leave this task UP-TO-DATE and prove nothing.
 *
 * ### What is deliberately not here
 *
 * Wall-clock timings, and the memory table. The memory figures on the page are exact arithmetic on
 * `n^2 * 8` bytes and are checked below because they cost nothing; a timing would be a measurement of
 * this machine rather than of the code.
 */
class ProjectionExamplesPageTest {

    private companion object {
        val PAGE: File = File("public/docs/projection_examples.md")

        /** The four word families the first and fourth fences use. */
        val FAMILIES = listOf(
            listOf("compute", "computes", "computed", "computer", "computing"),
            listOf("render", "renders", "rendered", "renderer", "rendering"),
            listOf("listen", "listens", "listened", "listener", "listening"),
            listOf("sample", "samples", "sampled", "sampler", "sampling"),
        )
    }

    /**
     * The page, with whitespace runs collapsed and typographic minus signs turned into hyphens.
     *
     * Both normalisations exist because of real near-misses rather than for tidiness: the page wraps
     * at 100 columns, so an eight-value spectrum quoted in prose has a newline inside it; and the
     * prose spells a negative eigenvalue with U+2212 MINUS SIGN, which no `"%.2f".format` will ever
     * produce.
     */
    private val page: String by lazy {
        assertTrue(PAGE.isFile, "the page under test is missing: ${PAGE.absolutePath}")
        PAGE.readText().replace('\u2212', '-').replace(Regex("\\s+"), " ")
    }

    /** Collects `page says X / code says X` mismatches so one run reports all of them. */
    private class Ledger {
        val problems = ArrayList<String>()
        val checked = ArrayList<String>()

        /**
         * Claims this platform cannot decide. Kept separate from [problems] so a
         * platform-conditional claim is visible rather than silently absent, and separate from
         * [checked] so it cannot inflate the vacuity floor.
         */
        val skipped = ArrayList<String>()

        fun claim(page: String, what: String, format: String, computed: Double) {
            val rendered = format.format(computed)
            if (!page.contains(rendered)) {
                problems += "$what: the page does not contain \"$rendered\" (computed $computed)"
            } else {
                checked += "$what = $rendered"
            }
        }

        fun claim(page: String, what: String, literal: String) {
            if (!page.contains(literal)) {
                problems += "$what: the page does not contain \"$literal\""
            } else {
                checked += "$what = $literal"
            }
        }
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun planeDistances(coordinates: Array<DoubleArray>): Array<DoubleArray> =
        Array(coordinates.size) { a ->
            DoubleArray(coordinates.size) { b ->
                sqrt(coordinates[a].indices.sumOf { c -> (coordinates[a][c] - coordinates[b][c]).let { it * it } })
            }
        }

    /** Pearson r and optimally-scaled Kruskal stress-1, exactly as the first fence computes them. */
    private fun agreement(input: Array<DoubleArray>, embedded: Array<DoubleArray>): Pair<Double, Double> {
        val a = ArrayList<Double>()
        val b = ArrayList<Double>()
        for (i in input.indices) for (j in 0 until i) { a += input[i][j]; b += embedded[i][j] }
        val r = StatsDependence.pearson(a.toDoubleArray(), b.toDoubleArray())
        val scale = a.indices.sumOf { a[it] * b[it] } / a.indices.sumOf { b[it] * b[it] }
        val stress = sqrt(
            a.indices.sumOf { (a[it] - scale * b[it]).let { d -> d * d } } / a.indices.sumOf { a[it] * a[it] },
        )
        return r to stress
    }

    private fun wordDistances(): Array<DoubleArray> {
        val words = FAMILIES.flatten()
        return Array(words.size) { a -> DoubleArray(words.size) { b -> editDistance(words[a], words[b]).toDouble() } }
    }

    @Test
    fun theEditDistanceFiguresOnThePageAreTheOnesTheCodeProduces() {
        val words = FAMILIES.flatten()
        val distances = wordDistances()
        val ledger = Ledger()

        val within = ArrayList<Double>()
        val between = ArrayList<Double>()
        for (a in words.indices) for (b in 0 until a) {
            if (a / 5 == b / 5) within += distances[a][b] else between += distances[a][b]
        }
        ledger.claim(page, "within-family mean edit distance", "%.3f", within.average())
        ledger.claim(page, "between-family mean edit distance", "%.3f", between.average())

        // The 2-D fit, both ways round, and the two higher-dimensional rows the page quotes.
        listOf(true to "corrected", false to "uncorrected").forEach { (correction, label) ->
            val fit = smileMdsDistances(distances, dimensions = 2, positiveCorrection = correction)
            val (r, stress) = agreement(distances, planeDistances(fit.coordinates))
            ledger.claim(page, "$label 2-D r", "%.4f", r)
            ledger.claim(page, "$label 2-D stress-1", "%.4f", stress)
            // `proportion` sums to 1 whatever is retained — the trap the page names.
            ledger.claim(page, "$label proportion sum", "%.4f", fit.proportion.sum())
        }
        listOf(3, 4).forEach { dimensions ->
            val fit = smileMdsDistances(distances, dimensions = dimensions)
            val (r, stress) = agreement(distances, planeDistances(fit.coordinates))
            ledger.claim(page, "${dimensions}-D r", "%.4f", r)
            ledger.claim(page, "${dimensions}-D stress-1", "%.4f", stress)
        }

        // The claim the figure is actually making, as a count.
        val plane = planeDistances(
            smileMdsDistances(distances, dimensions = 2, label = "levenshtein").coordinates,
        )
        fun sameFamilyNearest(matrix: Array<DoubleArray>) = words.indices.count { row ->
            val nearest = words.indices.filter { it != row }.minByOrNull { matrix[row][it] }!!
            nearest / 5 == row / 5
        }
        ledger.claim(page, "same-family nearest by edit distance", "**${sameFamilyNearest(distances)} of 20**")
        ledger.claim(page, "same-family nearest in the plane", "**${sameFamilyNearest(plane)} of 20**")
        val lostPairs = words.indices.filter { row ->
            val nearest = words.indices.filter { it != row }.minByOrNull { plane[row][it] }!!
            nearest / 5 != row / 5
        }.associate { row ->
            words[row] to words[words.indices.filter { it != row }.minByOrNull { plane[row][it] }!!]
        }
        // Printed, not only asserted: the first draft of the page named the wrong two words from
        // reading the figure, and this line is what a future edit should quote from.
        println("words whose plane-nearest neighbour left their family: " +
            lostPairs.entries.joinToString { "${it.key} -> ${it.value}" })
        val lost = lostPairs.keys.sorted()
        ledger.claim(page, "the words that lose their family", "`${lost[0]}` and `${lost[1]}`")

        report(ledger)
    }

    @Test
    fun theSpiralFiguresOnThePageAreTheOnesTheCodeProduces() {
        val n = 160
        val t = DoubleArray(n) { it / (n - 1.0) }
        val xy = Array(n) { i ->
            val theta = 2.0 * PI * 2.0 * t[i]
            val radius = 0.18 + 0.62 * t[i]
            doubleArrayOf(radius * cos(theta), radius * sin(theta))
        }
        val graph = vec2KnnGraph((0 until n).map { vec(xy[it][0], xy[it][1]) }, k = 4)
        val adjacency = Array(n) { graph.neighbors(it).toIntArray() }
        val hops = Array(n) { source ->
            val depth = IntArray(n) { -1 }
            depth[source] = 0
            val queue = ArrayDeque<Int>()
            queue += source
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (next in adjacency[at]) if (depth[next] < 0) {
                    depth[next] = depth[at] + 1
                    queue += next
                }
            }
            DoubleArray(n) { depth[it].toDouble() }
        }
        val ledger = Ledger()

        // The guard that makes the example valid at all: no k-NN edge jumps between arms.
        ledger.claim(page, "worst |i - j| over a graph edge", "over an edge = ${graph.edges.maxOf { abs(it.i - it.j) }}")
        ledger.claim(page, "graph edge count", "${graph.edges.size} edges")

        val geodesic = smileMdsDistances(hops, dimensions = 2, label = "graph hops").coordinates
        val straight = smileMds(
            xy, dimensions = 2, distance = SmileMdsDistance.EUCLIDEAN, positiveCorrection = false,
        ).coordinates
        fun oriented(coordinates: Array<DoubleArray>): DoubleArray {
            val raw = DoubleArray(n) { coordinates[it][0] }
            val sign = if (StatsDependence.spearman(t, raw) < 0.0) -1.0 else 1.0
            return DoubleArray(n) { sign * raw[it] }
        }
        val arc = DoubleArray(n)
        for (i in 1 until n) {
            arc[i] = arc[i - 1] + sqrt(
                (xy[i][0] - xy[i - 1][0]).let { it * it } + (xy[i][1] - xy[i - 1][1]).let { it * it },
            )
        }
        ledger.claim(page, "hop-count rho", "**${"%.4f".format(StatsDependence.spearman(t, oriented(geodesic)))}**")
        ledger.claim(page, "straight-line rho", "%.4f", StatsDependence.spearman(t, oriented(straight)))
        ledger.claim(page, "hop-count pearson vs arc length", "**${"%.4f".format(abs(StatsDependence.pearson(arc, oriented(geodesic))))}**")
        ledger.claim(page, "straight-line pearson vs arc length", "**${"%.4f".format(abs(StatsDependence.pearson(arc, oriented(straight))))}**")

        report(ledger)
    }

    @Test
    fun theMdsVersusTsneComparisonOnThePageIsTheOneTheCodeProduces() {
        val dimensions = 12
        val perCluster = 60
        val rng = java.util.Random(4)
        val centres = listOf(
            DoubleArray(dimensions),
            DoubleArray(dimensions).also { it[0] = 10.0 },
            DoubleArray(dimensions).also { it[1] = 40.0 },
        )
        val rows = ArrayList<DoubleArray>()
        val label = ArrayList<Int>()
        centres.forEachIndexed { cluster, centre ->
            repeat(perCluster) {
                rows += DoubleArray(dimensions) { centre[it] + rng.nextGaussian() }
                label += cluster
            }
        }
        val vectors = rows.toTypedArray()
        val n = vectors.size
        val ledger = Ledger()

        fun centroid(coordinates: Array<DoubleArray>, cluster: Int): DoubleArray {
            val members = (0 until n).filter { label[it] == cluster }
            return DoubleArray(coordinates[0].size) { c -> members.sumOf { coordinates[it][c] } / members.size }
        }
        fun ratio(coordinates: Array<DoubleArray>): Double {
            fun gap(a: Int, b: Int): Double {
                val ca = centroid(coordinates, a)
                val cb = centroid(coordinates, b)
                return sqrt(ca.indices.sumOf { (ca[it] - cb[it]).let { d -> d * d } })
            }
            return gap(0, 2) / gap(0, 1)
        }
        fun nearest(source: Array<DoubleArray>, row: Int, k: Int): Set<Int> =
            (0 until n).filter { it != row }
                .sortedBy { other -> source[row].indices.sumOf { (source[row][it] - source[other][it]).let { d -> d * d } } }
                .take(k).toSet()
        fun recall(coordinates: Array<DoubleArray>, k: Int): Double =
            (0 until n).sumOf { row -> nearest(vectors, row, k).intersect(nearest(coordinates, row, k)).size / k.toDouble() } / n

        val mds = smileMds(vectors, dimensions = 2, distance = SmileMdsDistance.EUCLIDEAN, positiveCorrection = false)
        val tsne = smileTsne(vectors, dimensions = 2, distance = SmileMdsDistance.EUCLIDEAN, perplexity = 30.0)

        ledger.claim(page, "true between-centre ratio", "**${"%.4f".format(ratio(vectors))}**")
        ledger.claim(page, "MDS between-centre ratio", "**${"%.4f".format(ratio(mds.coordinates))}**")
        ledger.claim(page, "t-SNE between-centre ratio", "**${"%.4f".format(ratio(tsne.coordinates))}**")
        ledger.claim(page, "MDS 10-NN recall", "%.4f", recall(mds.coordinates, 10))
        ledger.claim(page, "t-SNE 10-NN recall", "**${"%.4f".format(recall(tsne.coordinates, 10))}**")

        // The perplexity sweep table.
        listOf(5.0, 30.0, 100.0).forEach { perplexity ->
            val fit = smileTsne(vectors, dimensions = 2, distance = SmileMdsDistance.EUCLIDEAN, perplexity = perplexity)
            ledger.claim(page, "perplexity $perplexity ratio", "%.4f", ratio(fit.coordinates))
            ledger.claim(page, "perplexity $perplexity recall", "%.4f", recall(fit.coordinates, 10))
        }

        // The seed claim, in both directions: identical at the same seed, and how far apart at another.
        val again = smileTsne(vectors, dimensions = 2, distance = SmileMdsDistance.EUCLIDEAN, perplexity = 30.0)
        val other = smileTsne(vectors, dimensions = 2, distance = SmileMdsDistance.EUCLIDEAN, perplexity = 30.0, seed = 7L)
        fun drift(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            (0 until n).maxOf { row -> (0..1).maxOf { c -> abs(a[row][c] - b[row][c]) } }
        val same = drift(tsne.coordinates, again.coordinates)
        if (same != 0.0) {
            ledger.problems += "the page says two fits at one seed are bit-identical; the worst difference was $same"
        } else {
            ledger.checked += "same-seed refit is bit-identical"
        }
        ledger.claim(page, "different-seed drift", "**${"%.2f".format(drift(tsne.coordinates, other.coordinates))}**")
        ledger.claim(page, "default-seed cost", "%.4f", tsne.cost)
        ledger.claim(page, "seed-7 cost", "%.4f", other.cost)

        report(ledger)
    }

    @Test
    fun thePositiveCorrectionAndCostFiguresOnThePageAreTheOnesTheCodeProduces() {
        val distances = wordDistances()
        val ledger = Ledger()

        // How many dimensions each setting supports before the spectrum runs out.
        val usable = listOf(false, true).associateWith { correction ->
            var accepted = 0
            var scores = DoubleArray(0)
            for (dimension in 2..18) {
                try {
                    val fit = smileMdsDistances(distances, dimensions = dimension, positiveCorrection = correction)
                    accepted = dimension
                    scores = fit.scores
                } catch (refused: IllegalArgumentException) {
                    break
                }
            }
            accepted to scores
        }
        ledger.claim(page, "uncorrected usable dimensions", "**${usable.getValue(false).first}** dimensions")
        ledger.claim(page, "corrected usable dimensions", "**${usable.getValue(true).first}** requestable dimensions")
        ledger.claim(page, "smallest corrected eigenvalue", "%.2f", usable.getValue(true).second.last())

        // The tenth eigenvalue of the uncorrected spectrum, which is why the refusal happens. Read
        // from the refusal message itself rather than from a second implementation of the spectrum.
        val refusal = runCatching {
            smileMdsDistances(distances, dimensions = usable.getValue(false).first + 1, positiveCorrection = false)
        }.exceptionOrNull()
        if (refusal !is IllegalArgumentException) {
            ledger.problems += "expected the uncorrected fit to refuse one dimension past ${usable.getValue(false).first}"
        } else {
            // Two different refusals reach here depending on which eigensolver is live, and only
            // one of them names the eigenvalue. This tree's LAPACK fallback (smile_mds.kt, taken
            // when bytedeco's ARPACK cannot dlopen -- as on Apple Silicon) reports "smallest
            // retained <value>". Where ARPACK does load, Smile refuses first with its own
            // "Some of the first N eigenvalues are < 0." (MDS.java), which carries no number.
            // Requiring the first spelling would fail this test on every machine where the
            // library works, which is the opposite of what it is for.
            val message = refusal.message.orEmpty()
            val smallest = Regex("smallest retained (-?[0-9.eE-]+)").find(message)?.groupValues?.get(1)
            when {
                smallest != null ->
                    ledger.claim(page, "the first materially negative eigenvalue", "%.2f", smallest.toDouble())
                message.contains("eigenvalues are < 0") ->
                    ledger.skipped += "the first materially negative eigenvalue: the ARPACK-backed " +
                        "refusal names no eigenvalue (\"$message\")"
                else ->
                    ledger.problems += "the fit refused, but with neither known message: $message"
            }
        }
        // The first eight eigenvalues, quoted on the page as a list.
        val eight = usable.getValue(false).second.take(8).joinToString(", ") { "%.2f".format(it) }
        ledger.claim(page, "the uncorrected spectrum", eight)

        // The memory table: exact arithmetic, so an exact check.
        mapOf(1_000 to "%.1f", 5_000 to "%.0f", 20_000 to "%.1f", 50_000 to "%.0f").forEach { (rows, format) ->
            val mib = rows.toLong() * rows.toLong() * 8L / (1024.0 * 1024.0)
            val rendered = if (mib >= 1024.0) "${format.format(mib / 1024.0)} GiB" else "${format.format(mib)} MiB"
            ledger.claim(page, "proximity matrix at n = $rows", rendered)
        }

        report(ledger)
    }

    private fun report(ledger: Ledger) {
        assertTrue(ledger.checked.size >= 5, "this check went green over ${ledger.checked.size} claims")
        if (ledger.problems.isNotEmpty()) {
            fail(
                "public/docs/projection_examples.md disagrees with the code in " +
                    "${ledger.problems.size} place(s):\n  " + ledger.problems.joinToString("\n  ") +
                    "\n\n${ledger.checked.size} claim(s) did agree:\n  " + ledger.checked.joinToString("\n  "),
            )
        }
        println("projection_examples.md: ${ledger.checked.size} published figure(s) recomputed and matched")
        if (ledger.skipped.isNotEmpty()) {
            println("projection_examples.md: ${ledger.skipped.size} claim(s) not checkable on this platform:\n  " +
                ledger.skipped.joinToString("\n  "))
        }
    }
}
