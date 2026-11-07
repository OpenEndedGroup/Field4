package afield.graphics.text.sentences

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A Reed-Kellogg layout, as a sibling to the arc-and-tree one rather than a mode of it.
 *
 * The difference is which way the dependency runs. In [layout] the words are the input and the
 * structure is derived around them; here the structure is the input and the words are placed
 * *on* it -- along baselines and slants -- so their positions fall out of the grammar. Nothing
 * about "a row of words" survives, which is why this is its own pass.
 *
 * Input is the collapsed Stanford Dependencies, which is the right starting point because it has
 * already folded prepositions and conjunctions into relation names. Reed-Kellogg wants the same
 * kind of folding for auxiliaries and particles -- "will push" and "step off" are each written as
 * one thing on the baseline -- so [Role.MERGED] carries on where collapsing left off.
 *
 * Geometry is all straight segments, so unlike the arc field there is no collision solver: room
 * is reserved on the way up (see [Cluster]) and nothing can then overlap.
 */

/** What a dependency becomes in a Reed-Kellogg diagram. */
enum class Role {
    /** Left of the bisector. */
    SUBJECT,

    /** Right of the bisector -- the clause's verb. */
    PREDICATE,

    /** On the baseline past a half-bar. */
    OBJECT,

    /** Past a backslash: predicate nominative or adjective. */
    COMPLEMENT,

    /** On a slant below its host. */
    MODIFIER,

    /** A slant carrying the preposition, with its object on a shelf below. */
    PREPOSITION,

    /** Its own baseline, joined to the host by a dotted riser. */
    CLAUSE,

    /** Folded into the host's own slot: auxiliaries, particles, multi-word expressions. */
    MERGED,

    /** Not drawn. */
    DROPPED,
}

/**
 * Which role a relation takes.
 *
 * Reed-Kellogg presumes a traditional grammar -- subject, predicate, complement, modifier -- and
 * the dependency labels give that cleanly for the core and not at all for the tail. Anything
 * unrecognised becomes a [Role.MODIFIER] on a slant, which is wrong but legible, rather than
 * being silently dropped.
 */
fun roleOf(relation: String): Role = when (relation.split("_", ":")[0]) {
    "root" -> Role.PREDICATE
    "nsubj", "nsubjpass", "csubj", "csubjpass", "xsubj" -> Role.SUBJECT
    "dobj", "iobj", "obj" -> Role.OBJECT
    "acomp", "attr" -> Role.COMPLEMENT
    "prep", "prepc" -> Role.PREPOSITION
    "advcl", "ccomp", "xcomp", "rcmod", "acl", "partmod", "infmod" -> Role.CLAUSE
    // Written as part of the word they belong to, not on their own line.
    "aux", "auxpass", "prt", "mwe", "possessive", "cop", "complm", "mark", "punct" -> Role.MERGED
    "det", "amod", "nn", "num", "number", "advmod", "neg", "poss", "predet", "quantmod",
    "npadvmod", "tmod", "appos", "conj", "cc", "expl", "discourse", "parataxis",
    -> Role.MODIFIER
    else -> Role.MODIFIER
}

/**
 * A word, placed. [angle] is degrees; modifiers are written along their slant.
 *
 * [size] and [width] travel with the word so that whatever reserves space for it and whatever
 * draws it agree by construction. Getting those out of step is how a slant ends up shorter than
 * the word it carries.
 */
data class KWord(
    val token: Int,
    val text: String,
    val at: P,
    val angle: Double,
    val role: Role,
    /** Point size this word is set at. */
    val size: Double,
    /** Measured advance at [size]. */
    val width: Double,
) {
    /** The space this word actually occupies, slant and all. */
    val footprint: Rect get() = Rect.textFromStart(at, width, size, angle)
}

enum class KLineKind { BASELINE, BISECTOR, HALF_BAR, BACKSLASH, SLANT, SHELF, RISER }

data class KLine(val a: P, val b: P, val kind: KLineKind)

data class KelloggConfig(
    /**
     * Degrees below horizontal. Modifiers are written *along* their slant, so this is a
     * legibility setting as much as a stylistic one -- much past 45 and the words stand too near
     * vertical to read comfortably.
     */
    val slantAngle: Double = 45.0,
    /** How far a slant runs past the end of its word. */
    val slantPad: Double = 12.0,
    /** Clear space kept between neighbouring clusters. */
    val gap: Double = 16.0,
    /** Padding either side of a word sitting on a baseline. */
    val baselinePad: Double = 10.0,
    /** How far the bisector stands above and below the baseline. */
    val bisectorRise: Double = 16.0,
    /** How far a half-bar or backslash stands above the baseline. */
    val barRise: Double = 14.0,
    /** How far a subordinate clause's baseline sits below its host's. */
    val clauseDrop: Double = 70.0,
    /**
     * How much steeper each nested level of modifier slants.
     *
     * A child slant anchored on its parent's at the *same* angle is collinear with it, so a nest
     * reads as one long line instead of a hierarchy. Steepening a little per level makes them fan
     * visibly while still touching, which is what carries the structure.
     */
    val nestAngleStep: Double = 22.0,
    /** Ceiling on that, since a near-vertical slant cannot carry readable text. */
    val maxSlantAngle: Double = 72.0,
    /** Length of the dash and the gap between dashes, for risers. */
    val dash: Double = 5.0,
    val fontSize: Double = 26.0,
    val metrics: Metrics = ApproxSerifMetrics(26.0),
    /**
     * Size for words on slants. Smaller than [fontSize] is what keeps a deep stack of modifiers
     * readable, and it has to be here rather than in the renderer so the slants are measured at
     * the size they are drawn at.
     */
    val modifierSize: Double = 16.0,
    val modifierMetrics: Metrics = ApproxSerifMetrics(16.0),
)

data class KelloggDiagram(
    val sentence: Sentence,
    val config: KelloggConfig,
    val words: List<KWord>,
    val lines: List<KLine>,
    val bounds: Rect,
) {
    /**
     * Anything wrong with the result; empty when all is well.
     *
     * The check that matters is coverage. Reed-Kellogg places words by role, and a role the
     * layout does not recognise is the easy way to lose one silently -- so every token in the
     * dependency graph has to turn up on the page, either written on a line of its own or folded
     * into a neighbour's slot.
     */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        val drawn = words.filter { it.token > 0 }.map { it.token }
        val duplicated = drawn.groupBy { it }.filterValues { it.size > 1 }.keys
        for (t in duplicated) out += "token $t \"${sentence.token(t).word}\" is drawn twice"

        val written = words.joinToString(" ") { it.text }
        for (tok in sentence.tokens) {
            if (!tok.inGraph) continue
            if (tok.id in drawn) continue
            // Not on a line of its own, so it should have been merged into someone's text.
            if (!written.split(" ", " ").contains(tok.word)) {
                out += "token ${tok.id} \"${tok.word}\" is nowhere on the page"
            }
        }
        return out
    }
}

/** A node of the dependency tree, with merged words already folded in. */
class KNode(
    val token: Int,
    /** The word, plus anything merged into it: "will push", "step off". */
    val text: String,
    val role: Role,
    /**
     * The preposition or conjunction folded into this node's relation -- the `of` in `prep_of`,
     * the `and` in `conj_and`. Reed-Kellogg writes it on the slant with this node's own word on a
     * shelf below, so it has to be carried through: the word itself never contains it.
     */
    val marker: String? = null,
    val children: MutableList<KNode> = mutableListOf(),
) {
    fun childrenWith(vararg roles: Role) = children.filter { it.role in roles }

    /**
     * True when this node has arguments of its own, and so wants its own baseline rather than a
     * slant. A verb with a subject or an object is a predication wherever the parse hung it.
     */
    val isPredication: Boolean
        get() = children.any { it.role == Role.SUBJECT || it.role == Role.OBJECT || it.role == Role.COMPLEMENT }
}

/**
 * A piece of diagram built relative to an anchor at the origin.
 *
 * Everything is built at the origin and translated into place, so the measuring and the drawing
 * are the same code -- there is no second copy of the geometry to keep in step, which is the
 * usual way a layout like this goes wrong.
 */
class Cluster(
    val words: MutableList<KWord> = mutableListOf(),
    val lines: MutableList<KLine> = mutableListOf(),
) {
    val bounds: Rect
        get() {
            val fromLines = if (lines.isEmpty()) null
            else Rect.around(lines.flatMap { listOf(it.a, it.b) })
            // The words' own footprints, not just their anchors: a slant has to reserve room for
            // the text it carries, including the part standing off either side of the line.
            val all = words.map { it.footprint } + listOfNotNull(fromLines)
            return if (all.isEmpty()) Rect.EMPTY else Rect.union(all)
        }

    fun translated(by: P): Cluster {
        val c = Cluster()
        words.mapTo(c.words) { it.copy(at = it.at + by) }
        lines.mapTo(c.lines) { KLine(it.a + by, it.b + by, it.kind) }
        return c
    }

    operator fun plusAssign(other: Cluster) {
        words += other.words
        lines += other.lines
    }
}

/** Builds a Reed-Kellogg diagram from a sentence's collapsed dependencies. */
class Kellogg(private val config: KelloggConfig = KelloggConfig()) {

    private fun width(text: String) = config.metrics.width(text)
    private fun slantWidth(text: String) = config.modifierMetrics.width(text)

    private fun dirOf(angle: Double) =
        P(cos(Math.toRadians(angle)), sin(Math.toRadians(angle)))

    /** The angle a child of a slant at [angle] should take. */
    private fun nested(angle: Double) = minOf(angle + config.nestAngleStep, config.maxSlantAngle)

    /** Assemble the dependency tree, folding [Role.MERGED] words into their hosts. */
    fun tree(sentence: Sentence, scheme: Scheme = Scheme.SD_COLLAPSED): KNode? {
        val edges = sentence.edges(scheme)
        val roles = edges.associateWith { roleOf(it.rel) }
        val byGov = edges.groupBy { it.gov }

        val rootEdge = edges.firstOrNull { it.isRoot } ?: return null

        fun build(token: Int, role: Role, marker: String?): KNode {
            val kids = byGov[token].orEmpty()
            // Merged words join their host's text, in reading order.
            val merged = kids
                .filter { roles[it] == Role.MERGED }
                .map { it.dep }
                .filter { sentence.tokenOrNull(it) != null }
            val parts = (merged + token).sorted().map { sentence.token(it).word }
            val node = KNode(token, parts.joinToString(" "), role, marker)
            for (e in kids) {
                val r = roles[e]!!
                if (r == Role.MERGED || r == Role.DROPPED) continue
                node.children += build(e.dep, r, e.marker)
            }
            return node
        }
        return build(rootEdge.dep, Role.PREDICATE, null)
    }

    fun layout(sentence: Sentence, scheme: Scheme = Scheme.SD_COLLAPSED): KelloggDiagram {
        val root = tree(sentence, scheme)
            ?: return KelloggDiagram(sentence, config, emptyList(), emptyList(), Rect.EMPTY)
        val c = clause(root)
        val b = c.bounds
        return KelloggDiagram(sentence, config, c.words, c.lines, b)
    }

    // -- one clause: subject | predicate [half-bar object] [backslash complement] -----------

    private fun clause(head: KNode): Cluster {
        val c = Cluster()
        var x = 0.0

        val subject = head.children.firstOrNull { it.role == Role.SUBJECT }
        val obj = head.children.firstOrNull { it.role == Role.OBJECT }
        val complement = head.children.firstOrNull { it.role == Role.COMPLEMENT }

        if (subject != null) {
            val s = slot(subject)
            c += s.translated(P(x, 0.0))
            x += s.bounds.let { max(it.x1, width(subject.text)) } + config.baselinePad
        } else {
            // An imperative has no subject; leave a stub so the bisector has something to sit on.
            x += config.baselinePad * 2
        }

        // The bisector crosses the baseline; everything else only touches it.
        c.lines += KLine(P(x, -config.bisectorRise), P(x, config.bisectorRise), KLineKind.BISECTOR)
        x += config.baselinePad

        val p = slot(head, placed = setOfNotNull(subject, obj, complement))
        c += p.translated(P(x, 0.0))
        x += max(p.bounds.x1, width(head.text)) + config.baselinePad

        if (obj != null) {
            c.lines += KLine(P(x, -config.barRise), P(x, 0.0), KLineKind.HALF_BAR)
            x += config.baselinePad
            val o = slot(obj)
            c += o.translated(P(x, 0.0))
            x += max(o.bounds.x1, width(obj.text)) + config.baselinePad
        } else if (complement != null) {
            // A backslash rather than a half-bar: the complement renames the subject.
            c.lines += KLine(P(x, 0.0), P(x + config.barRise, -config.barRise), KLineKind.BACKSLASH)
            x += config.barRise + config.baselinePad
            val k = slot(complement)
            c += k.translated(P(x, 0.0))
            x += max(k.bounds.x1, width(complement.text)) + config.baselinePad
        }

        c.lines += KLine(P(0.0, 0.0), P(x, 0.0), KLineKind.BASELINE)
        return c
    }

    // -- a word on a line, with its modifiers hanging below ---------------------------------

    /** A word sitting on a baseline, plus everything that hangs off it. */
    private fun slot(n: KNode, placed: Set<KNode> = emptySet()): Cluster {
        val c = Cluster()
        c.words += KWord(
            n.token, n.text, P(0.0, -config.baselinePad * 0.35), 0.0, n.role,
            config.fontSize, width(n.text),
        )
        hang(
            c, n, from = P(0.0, 0.0), along = P(1.0, 0.0), reach = width(n.text),
            hostSize = config.fontSize, angle = config.slantAngle, hostAngle = 0.0,
            placed = placed,
        )
        return c
    }

    /**
     * How a child attaches to its host. Total by construction: every role ends up somewhere, so
     * no word can quietly vanish from the page.
     */
    private fun attach(
        child: KNode,
        hostSize: Double,
        angle: Double,
        hostAngle: Double,
    ): Cluster = when {
        // Its own arguments mean its own baseline, wherever the parse hung it. Same for an
        // argument that surfaced somewhere `clause` was not looking.
        child.role == Role.CLAUSE || child.isPredication -> clauseBelow(child)
        // A folded-in preposition or conjunction goes on the slant, its own word on a shelf.
        child.marker != null -> onShelf(child, child.marker, hostSize, angle, hostAngle)
        child.role == Role.PREPOSITION -> onShelf(child, child.text, hostSize, angle, hostAngle)
        else -> modifier(child, hostSize, angle, hostAngle)
    }

    /** A subordinate baseline, hanging from a dotted riser. */
    private fun clauseBelow(n: KNode): Cluster {
        val c = Cluster()
        val drop = config.clauseDrop
        c.lines += KLine(P(0.0, 0.0), P(0.0, drop), KLineKind.RISER)
        c += clause(n).translated(P(0.0, drop))
        return c
    }

    /**
     * Place [n]'s modifiers below it, marching along [along] from [from].
     *
     * Each is pushed far enough along to clear the one before it. That is the whole of the
     * collision handling: a slant carrying a subtree projects `cos(angle) * length` horizontally
     * *and* the width of its own subtree, so how much room a child needs depends on its depth,
     * not just its text. Reserving it here means nothing has to be moved later.
     */
    private fun hang(
        c: Cluster,
        n: KNode,
        from: P,
        along: P,
        reach: Double,
        /** Point size of the word this is hanging under, so a slant can clear its descender. */
        hostSize: Double,
        /** Angle for slants hanging here. */
        angle: Double,
        /** Angle of the line they hang from, so they can be started clear of it. */
        hostAngle: Double,
        /**
         * How far along the host line to begin. Children of a *slant* start past the parent's own
         * word: they run parallel to it, so beginning at the slant's origin puts them straight
         * through it.
         */
        startAt: Double = 0.0,
        /**
         * Children already drawn by the caller -- the arguments `clause` put on the baseline.
         * Passed explicitly rather than skipped by role: a subject can itself be a predication
         * with a subject of its own, and skipping those by role loses them off the page.
         */
        placed: Set<KNode> = emptySet(),
    ): Double {
        var offset = startAt
        var clearTo = Double.NEGATIVE_INFINITY
        // The furthest point on the host line anything was attached at. The host has to be drawn
        // at least this long or its children hang off the end of it, unattached.
        var reached = 0.0

        for (child in n.children) {
            if (child in placed) continue

            val sub = attach(child, hostSize, angle, hostAngle)
            // Slide along the host line until this cluster clears the previous one.
            var anchor = from + along * offset
            var placed = sub.translated(anchor)
            if (placed.bounds.x0 < clearTo) {
                val need = clearTo - placed.bounds.x0
                val step = if (abs(along.x) < 1e-9) need else need / along.x
                anchor = anchor + along * step
                placed = sub.translated(anchor)
            }
            c += placed
            reached = max(reached, (anchor - from).length)
            clearTo = placed.bounds.x1 + config.gap
            offset = (anchor - from).length + config.gap
            // Keep successive slants under the host rather than trailing off past it.
            if (offset > reach + startAt) offset = reach + startAt
        }
        return reached
    }

    /** A modifier on a slant, written along it, with its own modifiers hanging off that slant. */
    private fun modifier(
        n: KNode,
        hostSize: Double,
        angle: Double,
        hostAngle: Double,
    ): Cluster {
        val c = Cluster()
        val d = dirOf(angle)
        val w = slantWidth(n.text)
        val start = textStart(hostSize, hostAngle, angle)
        val own = start + w + config.slantPad
        // The word sits along the slant, standing just off the line.
        c.words += KWord(n.token, n.text, d * start, angle, n.role, config.modifierSize, w)
        // This slant's own modifiers begin past its word, and fan a little steeper.
        val reached = hang(
            c, n, from = P(0.0, 0.0), along = d, reach = own,
            hostSize = config.modifierSize, angle = nested(angle), hostAngle = angle,
            startAt = start + w + config.gap,
        )
        // Drawn last, and long enough to reach the furthest thing hanging off it: a child anchors
        // at a point on this line, so the line has to actually get there.
        c.lines += KLine(P(0.0, 0.0), d * max(own, reached), KLineKind.SLANT)
        return c
    }

    /**
     * How far down a slant its word should start.
     *
     * Far enough that the word clears the descenders of whatever it hangs beneath: a host set at
     * `hostSize` reaches about `0.22 * hostSize` below its own line, and a slant leaves that line
     * at [KelloggConfig.slantAngle], so the distance needed along the slant is that over sin.
     */
    private fun textStart(hostSize: Double, hostAngle: Double, angle: Double): Double {
        // Clear the descenders of the word this hangs beneath.
        val descent = hostSize * 0.22 + config.gap * 0.35
        val s = sin(Math.toRadians(angle))
        val fromWord = if (s < 1e-6) descent else descent / s

        // And clear the host's *line*, which carries on past its word. This slant diverges from it
        // by only `between` degrees, so at distance t along it the perpendicular gap is
        // `t * sin(between)` -- and the text needs its own half-height of that before it starts,
        // or it runs alongside the parent line and through it.
        val between = abs(angle - hostAngle)
        val sb = sin(Math.toRadians(between))
        val need = config.modifierSize * 0.78 + config.gap * 0.35
        val fromLine = if (sb < 1e-3) need * 8 else need / sb

        return max(config.slantPad * 0.4, max(fromWord, fromLine))
    }

    /**
     * A preposition on a slant with its object on a shelf below.
     *
     * The object is the arc-side collapse read backwards: `prep_of(walls, plant)` names the
     * preposition in the relation and hangs the noun off it, which is exactly the shape
     * Reed-Kellogg draws.
     */
    private fun onShelf(
        n: KNode,
        slantText: String,
        hostSize: Double,
        angle: Double,
        hostAngle: Double,
    ): Cluster {
        val c = Cluster()
        val d = dirOf(angle)
        val sw = slantWidth(slantText)
        val start = textStart(hostSize, hostAngle, angle)
        val len = start + sw + config.slantPad
        val end = d * len
        c.lines += KLine(P(0.0, 0.0), end, KLineKind.SLANT)
        // (the shelf below carries the object; nothing hangs off this slant itself)
        // Token -1: the marker is not a token of its own -- collapsing folded it into the
        // relation, which is exactly why it has to be written from `marker` and not from a word.
        c.words += KWord(
            -1, slantText, d * start, angle, n.role, config.modifierSize, sw,
        )

        // The shelf carries the object; its own modifiers hang below the shelf.
        val obj = Cluster()
        obj.words += KWord(
            n.token, n.text, P(config.baselinePad, -config.baselinePad * 0.35), 0.0, n.role,
            config.fontSize, width(n.text),
        )
        val shelfReach = hang(
            obj, n, from = P(0.0, 0.0), along = P(1.0, 0.0), reach = width(n.text),
            hostSize = config.fontSize, angle = config.slantAngle, hostAngle = 0.0,
        )
        val shelfLen = max(width(n.text) + config.baselinePad * 2, shelfReach)
        obj.lines += KLine(P(0.0, 0.0), P(shelfLen, 0.0), KLineKind.SHELF)

        c += obj.translated(end)
        return c
    }
}

/** The relation names each role covers, for a quick sanity check on a new sentence. */
fun kelloggRoles(sentence: Sentence, scheme: Scheme = Scheme.SD_COLLAPSED): Map<Role, List<String>> =
    sentence.edges(scheme).groupBy { roleOf(it.rel) }
        .mapValues { (_, v) -> v.map { it.rel }.distinct().sorted() }
