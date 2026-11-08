package afield.graphics.text.sentences

import kotlin.math.abs
import kotlin.test.*

class DependencyLengthTest {
    // "the dog chased a cat": the→dog, dog→chased, chased is the root, a→cat, cat→chased.
    private val dog = DependencyTree(
        words = listOf("the", "dog", "chased", "a", "cat"),
        head = intArrayOf(1, 2, -1, 4, 2),
        rels = listOf("det", "nsubj", "root", "det", "obj"),
    )

    private fun permutations(n: Int): List<IntArray> {
        if (n == 0) return listOf(IntArray(0))
        return permutations(n - 1).flatMap { p ->
            (0..p.size).map { at -> (p.take(at) + (n - 1) + p.drop(at)).toIntArray() }
        }
    }

    private fun randomTree(n: Int, rng: java.util.Random): DependencyTree {
        // Word 0 is the root; every later word hangs off an earlier one, then the labels are shuffled.
        val parentOf = IntArray(n) { if (it == 0) -1 else rng.nextInt(it) }
        val relabel = (0 until n).shuffled(rng)
        val head = IntArray(n)
        for (i in 0 until n) head[relabel[i]] = if (parentOf[i] < 0) -1 else relabel[parentOf[i]]
        return DependencyTree(List(n) { "w$it" }, head, List(n) { listOf("a", "b", "c")[rng.nextInt(3)] })
    }

    @Test
    fun totalLengthSumsHeadToDependentDistancesWithoutTheRoot() {
        assertEquals(1 + 1 + 1 + 2, dog.length())
        assertEquals(2, dog.arcLength(4))
        // "chased the dog a cat" moves the subject after the verb: 1 + 2 + 1 + 4.
        assertEquals(1 + 2 + 1 + 4, dog.length(intArrayOf(2, 0, 1, 3, 4)))
        assertFailsWith<IllegalArgumentException> { dog.length(intArrayOf(0, 1, 2, 3, 3)) }
    }

    @Test
    fun projectivityMeansEverySubtreeIsContiguous() {
        assertTrue(dog.isProjective())
        // "the chased dog a cat" splits the subject's subtree around its head.
        assertFalse(dog.isProjective(intArrayOf(0, 2, 1, 3, 4)))
        val projective = permutations(5).count { dog.isProjective(it) }
        // Root group {dog, chased, cat}: 3!, then each of dog and cat orders its determiner 2 ways.
        assertEquals(6 * 2 * 2, projective)
    }

    @Test
    fun freeRandomSamplesExactlyTheProjectiveOrdersUniformly() {
        val all = permutations(5).filter { dog.isProjective(it) }.map { it.toList() }.toSet()
        val rng = java.util.Random(4)
        val counts = HashMap<List<Int>, Int>()
        val draws = 24_000
        repeat(draws) {
            val order = Linearization.freeRandom(dog, rng).toList()
            counts[order] = (counts[order] ?: 0) + 1
        }
        assertEquals(all, counts.keys)
        val expected = draws.toDouble() / all.size
        for ((order, c) in counts) assertTrue(abs(c - expected) < 0.15 * expected, "$order drawn $c times, expected about $expected")
    }

    @Test
    fun freeRandomMeanMatchesTheStarTreeAnalyticValue() {
        // A root with k leaf dependents: the root lands uniformly at r, giving r(r+1)/2 + (k-r)(k-r+1)/2.
        val k = 6
        val star = DependencyTree(List(k + 1) { "w$it" }, IntArray(k + 1) { if (it == 0) -1 else 0 })
        val analytic = (0..k).sumOf { r -> r * (r + 1) / 2 + (k - r) * (k - r + 1) / 2 }.toDouble() / (k + 1)
        val rng = java.util.Random(9)
        val mean = (0 until 40_000).map { star.length(Linearization.freeRandom(star, rng)) }.average()
        assertEquals(analytic, mean, 0.05)
    }

    @Test
    fun fixedOrderPlacesEachRelationTypeConsistently() {
        // Two clauses, each a verb with an nsubj and an obj; the second clause is the first's ccomp.
        val tree = DependencyTree(
            words = listOf("she", "said", "it", "he", "saw", "them"),
            head = intArrayOf(1, -1, 1, 4, 1, 4),
            rels = listOf("nsubj", "root", "obj", "nsubj", "ccomp", "obj"),
        )
        val rng = java.util.Random(2)
        repeat(200) {
            val weights = Linearization.randomWeights(tree, rng)
            assertTrue(weights.values.all { it in -1.0..1.0 })
            val order = Linearization.fixedOrder(tree, weights, rng)
            assertTrue(tree.isProjective(order))
            val p = tree.positions(order)
            fun before(a: Int, b: Int) = p[a] < p[b]
            // The subject/verb and object/verb orders agree across both clauses, as the weights say.
            assertEquals(before(0, 1), before(3, 4))
            assertEquals(before(2, 1), before(5, 4))
            assertEquals(weights.getValue("nsubj") < 0, before(0, 1))
            assertEquals(weights.getValue("obj") < 0, before(2, 1))
        }
    }

    @Test
    fun fixedHeadPositionKeepsEveryDependentOnItsObservedSide() {
        val rng = java.util.Random(3)
        repeat(50) {
            val tree = randomTree(9, rng)
            val order = Linearization.fixedHeadPosition(tree, rng)
            assertTrue(tree.isProjective(order))
            val p = tree.positions(order)
            for ((h, d) in tree.arcs) assertEquals(d < h, p[d] < p[h], "word $d left its side of $h")
        }
    }

    @Test
    fun optimalIsTheShortestProjectiveOrder() {
        val rng = java.util.Random(11)
        val perms = (1..8).associateWith { permutations(it) }
        repeat(150) {
            val tree = randomTree(2 + rng.nextInt(7), rng)
            val best = perms.getValue(tree.size).filter { tree.isProjective(it) }.minOf { tree.length(it) }
            val order = Linearization.optimal(tree)
            assertTrue(tree.isProjective(order))
            assertEquals(best, tree.length(order), "tree heads ${tree.head.toList()}")
        }
    }

    @Test
    fun lengthsAreReproducibleAndBracketedByTheOptimum() {
        val a = dog.lengths(samples = 50, seed = 5)
        val b = dog.lengths(samples = 50, seed = 5)
        assertContentEquals(a.free, b.free)
        assertContentEquals(a.fixedOrder, b.fixedOrder)
        assertEquals(5, a.observed)
        // "the dog chased cat a": a determiner on the far side of its noun saves a word.
        assertEquals(4, a.optimal)
        assertTrue((a.free + a.fixedOrder + a.fixedHeadPosition).all { it >= a.optimal })
        assertEquals(a.observed / a.free.average(), a.ratio)
    }

    @Test
    fun punctuationIsRemovedAndItsDependentsReattached() {
        val parsed = ParsedSentence(
            text = "Stop , he said .",
            words = listOf("Stop", ",", "he", "said", "."),
            upos = listOf("VERB", "PUNCT", "PRON", "VERB", "PUNCT"),
            // An odd parse that hangs "he" off the comma, to exercise reattachment.
            heads = listOf(4, 4, 2, 0, 4),
            rels = listOf("ccomp", "punct", "nsubj", "root", "punct"),
        )
        val tree = assertNotNull(parsed.tree())
        assertEquals(listOf("Stop", "he", "said"), tree.words)
        assertEquals(listOf(2, 2, -1), tree.head.toList())
        assertEquals(2 + 1, tree.length())
    }

    @Test
    fun reorderedSentencesKeepTheTreeForDrawing() {
        val order = intArrayOf(2, 0, 1, 3, 4)
        val s = dog.sentence(order)
        assertTrue(s.problems().isEmpty(), s.problems().joinToString())
        assertEquals(listOf("chased", "the", "dog", "a", "cat"), s.tokens.map { it.word })
        assertEquals(dog.length(order), s.dependencies.ud.filter { !it.isRoot }.sumOf { abs(it.gov - it.dep) })
        val layout = SentenceDiagram().apply { scheme = Scheme.UD }.use(s).diagram()
        assertEquals(4, layout.arcs.size)
    }

    @Test
    fun retainedTextsLoadAndMeasure() {
        for (text in SentenceTexts.all()) {
            assertTrue(text.sentences.isNotEmpty(), text.key)
            for (s in text.sentences) {
                assertEquals(s.words.size, s.heads.size)
                assertEquals(1, s.heads.count { it == 0 }, s.text)
            }
            val measured = text.measure(samples = 10)
            assertTrue(measured.size >= text.sentences.size * 0.9, "${text.key}: ${measured.size} of ${text.sentences.size}")
            assertTrue(measured.all { it.lengths.optimal <= it.lengths.observed || !it.tree.isProjective() })
        }
    }

    @Test
    fun figureSvgScalesToItsPanelKeepingItsProportions() {
        val svg = figureSvg(dog.arcLines(spacing = 20.0), maxWidth = 500)
        val open = Regex("<svg\\b[^>]*>").find(svg)!!.value
        val viewBox = Regex("viewBox=\"0 0 ([0-9.]+) ([0-9.]+)\"").find(open)!!.groupValues
        assertTrue(viewBox[1].toDouble() > viewBox[2].toDouble(), open)
        assertTrue("max-width:500px" in open, open)
        assertFalse(Regex("\\swidth=").containsMatchIn(open), open)
    }
}
