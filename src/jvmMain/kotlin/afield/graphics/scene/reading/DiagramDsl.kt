package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.Typeface
import afield.math.linalg.*

@DslMarker annotation class ReadingDsl

/** Every visible item and physics point has a stable name for scoring and inspection. */
sealed class DiagramItem(val id: String, internal val owner: Any)

class DiagramAnchor
internal constructor(internal val owner: Any, internal val resolve: (DiagramFrame) -> Vector3f) {
    /** Offsets are metres in the diagram's gravity-up frame. */
    fun offset(x: Number = 0, y: Number = 0, z: Number = 0) =
        DiagramAnchor(owner) {
            resolve(it).add(x.toFloat(), y.toFloat(), z.toFloat())
        }
}

class DiagramParticle
internal constructor(id: String, owner: Any, val rest: Vector3f, val pinned: Boolean) :
    DiagramItem(id, owner) {
    val anchor = DiagramAnchor(owner) { it.particle(this) }
}

class DiagramText
internal constructor(id: String, owner: Any, val text: String, val at: DiagramAnchor) :
    DiagramItem(id, owner) {
    /** Font/style changes are preparation-time edits; position, rotation and scale stay live. */
    var font: Typeface? = null
    var bold = false
    var italic = false
    var weight: Double? = null
    /** Gaussian standard deviation in ems. Zero draws the original analytic Slug outline. */
    var blur = 0f
        set(value) { require(value.isFinite() && value >= 0f); field = value }
    /** Reserve a larger animated range before constructing Drawing; zero infers it from the score. */
    var blurCapacity = 0f
        set(value) { require(value.isFinite() && value >= 0f); field = value }
    internal var dropCapDefinition: DiagramDropCap? = null

    /** Reserve an ink-fitted opening initial across authored lines. Preparation-time typography. */
    fun dropCap(
        lines: Int = 3,
        gapEm: Float = .2f,
        characters: Int = 1,
        block: DiagramTypography.() -> Unit = {},
    ): DiagramTextSpan {
        require(lines in 1..12 && gapEm.isFinite() && gapEm in 0f..5f && characters in 1..16)
        require(!text.first().isWhitespace()) { "A drop-cap run must begin with its initial, without leading whitespace" }
        val breaks = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(this@DiagramText.text) }
        var end = 0
        repeat(characters) {
            end = breaks.next()
            require(end != java.text.BreakIterator.DONE) { "Drop cap exceeds the available grapheme clusters" }
        }
        require('\n' !in text.substring(0, end)) { "A drop cap cannot cross a line break" }
        dropCapDefinition = DiagramDropCap(lines, gapEm, end, DiagramTypography().apply(block))
        return this.characters(0, end)
    }

    /** The initial remains a span of the original text, usable by measurements and attachments. */
    val initial: DiagramTextSpan
        get() = characters(0, requireNotNull(dropCapDefinition) { "Call dropCap() on this run first" }.end)

    var tracking = 0.0 // font units between shaped glyphs
    internal val styles = ArrayList<Pair<DiagramTextSpan, DiagramTypography>>()
    var scale = 1f
    var rotation = 0f // radians around the run baseline midpoint, in the diagram plane
    var color = Vector4f(.82f, .88f, .98f, 1f)
    val all
        get() = characters(0, text.length)

    val left
        get() = all.left

    val right
        get() = all.right

    val center
        get() = all.center

    val above
        get() = all.above

    val below
        get() = all.below

    /**
     * A line excludes its newline; anchors and measurements keep the complete run's coordinates.
     */
    fun line(index: Int): DiagramTextSpan {
        val lines = text.split('\n')
        require(index in lines.indices && lines[index].isNotEmpty())
        val start = lines.take(index).sumOf { it.length + 1 }
        return characters(start, start + lines[index].length)
    }

    /** Unicode word tokens in reading order, each retaining its position in the full run. */
    val words: List<DiagramTextSpan>
        get() =
            Regex("[\\p{L}\\p{N}_]+(?:[’'][\\p{L}\\p{N}_]+)*")
                .findAll(text)
                .map { characters(it.range.first, it.range.last + 1) }
                .toList()

    val inkBounds
        get() = all.inkBounds

    val bodyBounds
        get() = all.bodyBounds

    val emBounds
        get() = all.emBounds

    val capBounds
        get() = all.capBounds

    val xHeightBounds
        get() = all.xHeightBounds

    val advanceBounds
        get() = all.advanceBounds

    val baseline
        get() = all.baseline

    val baselineLeft
        get() = all.baselineLeft

    val baselineRight
        get() = all.baselineRight

    fun local(x: Number = 0, y: Number = 0, z: Number = 0) = all.local(x, y, z)

    /** Caret at a shaped cluster boundary; indexing is UTF-16, as for word/span selections. */
    fun caret(index: Int): DiagramAnchor {
        require(index in 0..text.length)
        return DiagramAnchor(owner) { it.textCaret(this, index) }
    }

    fun characters(start: Int, endExclusive: Int): DiagramTextSpan {
        require(start >= 0 && endExclusive > start && endExclusive <= text.length) {
            "Invalid span $start..<$endExclusive in '$text'"
        }
        return DiagramTextSpan(this, start, endExclusive)
    }

    /** Literal spans retain the placement and kerning of the entire prepared run. */
    fun span(value: String, occurrence: Int = 0): DiagramTextSpan {
        require(value.isNotEmpty() && occurrence >= 0)
        var from = 0
        var at = -1
        repeat(occurrence + 1) {
            at = text.indexOf(value, from)
            require(at >= 0) { "No occurrence $occurrence of '$value' in '$text'" }
            from = at + value.length
        }
        return characters(at, at + value.length)
    }

    /**
     * Word boundaries avoid accidentally selecting 'he' inside 'the'. Occurrences are zero-based.
     */
    fun word(value: String, occurrence: Int = 0): DiagramTextSpan {
        require(value.isNotEmpty() && occurrence >= 0)
        val match =
            Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(value)}(?![\\p{L}\\p{N}_])")
                .findAll(text)
                .drop(occurrence)
                .firstOrNull()
        requireNotNull(match) { "No word '$value' occurrence $occurrence in '$text'" }
        return characters(match.range.first, match.range.last + 1)
    }
}

class DiagramTextSpan
internal constructor(val run: DiagramText, val start: Int, val endExclusive: Int) {
    /** Apply a preparation-time font/style override; later overlapping styles take precedence. */
    fun style(block: DiagramTypography.() -> Unit): DiagramTextSpan = apply {
        run.styles.add(this to DiagramTypography().apply(block))
    }

    val inkBounds
        get() = DiagramTextBox(this, DiagramBoundsKind.INK)

    val bodyBounds
        get() = DiagramTextBox(this, DiagramBoundsKind.NO_DESCENDERS)

    val emBounds
        get() = DiagramTextBox(this, DiagramBoundsKind.EM)

    val capBounds
        get() = DiagramTextBox(this, DiagramBoundsKind.CAPS)

    val xHeightBounds
        get() = DiagramTextBox(this, DiagramBoundsKind.X_HEIGHT)

    val advanceBounds
        get() = DiagramTextBox(this, DiagramBoundsKind.ADVANCE)

    /** Coordinates in this span's font units: x from its baseline start, y upward. */
    fun local(x: Number = 0, y: Number = 0, z: Number = 0) =
        DiagramAnchor(run.owner) {
            val m = it.measure(this)
            it.textPoint(run, m.advance.left + x.toFloat(), m.baseline + y.toFloat(), z.toFloat())
        }

    private fun anchor(x: Float, y: Float) = DiagramAnchor(run.owner) { it.textAnchor(this, x, y) }

    val baselineLeft
        get() = anchor(0f, 0f)

    val baselineRight
        get() = anchor(1f, 0f)

    val baseline
        get() = anchor(.5f, 0f)

    val left
        get() = anchor(0f, .35f)

    val right
        get() = anchor(1f, .35f)

    val center
        get() = anchor(.5f, .35f)

    val above
        get() = anchor(.5f, .8f)

    val below
        get() =
            DiagramAnchor(run.owner) {
                it.textAnchor(this, .5f, 0f).add(it.textDirection(run, 0f, -.02f, 0f))
            }
}

class DiagramPath
internal constructor(id: String, owner: Any, internal val make: DiagramFrame.() -> FLine) :
    DiagramItem(id, owner) {
    internal var connection: DiagramConnection? = null
    var ink = DiagramInk()
    var width: Float
        get() = ink.width
        set(value) {
            require(value.isFinite() && value >= 0)
            ink = ink.copy(width = value)
        }

    var color: Vector4f
        get() = ink.color
        set(value) {
            ink = ink.copy(color = Vector4f(value))
        }

    var arrow: Float
        get() = ink.arrow
        set(value) {
            require(value.isFinite() && value >= 0)
            ink = ink.copy(arrow = value)
        }

    fun dashed(period: Float = .03f, duty: Float = .55f, phase: Float = 0f) {
        require(period.isFinite() && period > 0 && duty in 0f..1f && phase.isFinite())
        ink = ink.copy(dashPeriod = period, dashDuty = duty, dashPhase = phase)
    }

    /** Fraction of full world arclength; a dot here follows the line while it moves or bends. */
    fun at(fraction: Float): DiagramAnchor {
        require(fraction.isFinite() && fraction in 0f..1f)
        return DiagramAnchor(owner) { it.pathPoint(this, fraction) }
    }
}

class DiagramDot internal constructor(id: String, owner: Any, val at: DiagramAnchor) :
    DiagramItem(id, owner) {
    var radius = .004f
    var color = Vector4f(.9f, .52f, .18f, 1f)
    var shape = DiagramPointShape.DISC
}

/** A named selection for entrances and crossfades; grouping adds no geometry or physics. */
class DiagramGroup
internal constructor(val id: String, internal val owner: Any, val items: List<DiagramItem>)

data class DiagramLink(val a: DiagramParticle, val b: DiagramParticle, val strength: Float)

class ReadingDiagram
internal constructor(
    internal val owner: Any,
    val particles: List<DiagramParticle>,
    val texts: List<DiagramText>,
    val paths: List<DiagramPath>,
    val dots: List<DiagramDot>,
    val links: List<DiagramLink>,
    val groups: List<DiagramGroup>,
) {
    val connections: List<DiagramConnection> = paths.mapNotNull { it.connection }

    fun connection(id: String) = requireNotNull(path(id).connection) { "Path '$id' is not a physics connection" }

    val items: List<DiagramItem> = particles + texts + paths + dots
    val byId = items.associateBy { it.id }

    fun text(id: String) = byId.getValue(id) as DiagramText

    fun path(id: String) = byId.getValue(id) as DiagramPath

    fun dot(id: String) = byId.getValue(id) as DiagramDot

    fun point(id: String) = byId.getValue(id) as DiagramParticle

    fun group(id: String) = groups.single { it.id == id }
}

@ReadingDsl
class ReadingDiagramBuilder internal constructor() {
    private val owner = Any()
    private val names = HashSet<String>()
    private val particles = ArrayList<DiagramParticle>()
    private val texts = ArrayList<DiagramText>()
    private val paths = ArrayList<DiagramPath>()
    private val dots = ArrayList<DiagramDot>()
    private val links = ArrayList<DiagramLink>()
    private val groups = ArrayList<DiagramGroup>()

    private fun name(id: String) {
        require(id.isNotBlank() && names.add(id)) { "Duplicate or blank diagram name '$id'" }
    }

    private fun check(anchor: DiagramAnchor) {
        require(anchor.owner === owner) { "Anchor belongs to another diagram" }
    }

    fun point(
        id: String,
        x: Number,
        y: Number,
        z: Number = 0,
        pinned: Boolean = false,
    ): DiagramParticle {
        name(id)
        val p = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
        require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
        return DiagramParticle(id, owner, p, pinned).also { particles.add(it) }
    }

    fun text(id: String, value: String, at: DiagramParticle, block: DiagramText.() -> Unit = {}) =
        text(id, value, at.anchor, block)

    /** Labels can hang on a particle, a word span or a moving path without another physics node. */
    fun text(
        id: String,
        value: String,
        at: DiagramAnchor,
        block: DiagramText.() -> Unit = {},
    ): DiagramText {
        name(id)
        check(at)
        require(value.isNotBlank()) { "A text run must contain visible text" }
        return DiagramText(id, owner, value, at).apply(block).also { texts.add(it) }
    }

    /** A reciprocal distance constraint; drawing is separate, so invisible links are easy. */
    fun link(a: DiagramParticle, b: DiagramParticle, strength: Float = 1f) {
        check(a.anchor)
        check(b.anchor)
        require(a !== b && strength.isFinite() && strength >= 0)
        require(links.none { (it.a === a && it.b === b) || (it.a === b && it.b === a) }) {
            "Duplicate constraint ${a.id} / ${b.id}"
        }
        links.add(DiagramLink(a, b, strength))
    }

    fun path(
        id: String,
        shape: DiagramFrame.() -> FLine,
        block: DiagramPath.() -> Unit = {},
    ): DiagramPath {
        name(id)
        return DiagramPath(id, owner, shape).apply(block).also { paths.add(it) }
    }

    fun line(
        id: String,
        from: DiagramAnchor,
        to: DiagramAnchor,
        block: DiagramPath.() -> Unit = {},
    ): DiagramPath {
        check(from)
        check(to)
        return path(id, { FLine().moveTo(position(from)).lineTo(position(to)) }, block)
    }

    /** Gravity-up arc; the authored centreline is thickened independently in each eye. */
    fun arc(
        id: String,
        from: DiagramAnchor,
        to: DiagramAnchor,
        rise: Float = .12f,
        block: DiagramPath.() -> Unit = {},
    ): DiagramPath {
        check(from)
        check(to)
        return path(
            id,
            {
                val a = position(from)
                val b = position(to)
                FLine()
                    .moveTo(a)
                    .cubicTo(Vector3f(a).add(0f, rise, 0f), Vector3f(b).add(0f, rise, 0f), b)
            },
            block,
        )
    }

    fun underline(
        id: String,
        span: DiagramTextSpan,
        gap: Float = .014f,
        block: DiagramPath.() -> Unit = {},
    ) =
        line(
            id,
            span.baselineLeft.alongText(span.run, y = -gap),
            span.baselineRight.alongText(span.run, y = -gap),
            block,
        )

    fun dot(id: String, at: DiagramAnchor, block: DiagramDot.() -> Unit = {}): DiagramDot {
        name(id)
        check(at)
        return DiagramDot(id, owner, at).apply(block).also { dots.add(it) }
    }

    fun group(id: String, block: ReadingDiagramBuilder.() -> Unit): DiagramGroup {
        name(id)
        val before = (particles + texts + paths + dots).map { it.id }.toSet()
        block()
        return DiagramGroup(
                id,
                owner,
                (particles + texts + paths + dots).filter { it.id !in before },
            )
            .also { groups.add(it) }
    }

    internal fun build(): ReadingDiagram {
        require(particles.isNotEmpty()) { "A reading diagram needs at least one physics point" }
        for (p in particles) require(links.count { it.a === p || it.b === p } <= 4) {
            "${p.id} has more than four SIMDVerlet constraints"
        }
        return ReadingDiagram(
            owner,
            particles.toList(),
            texts.toList(),
            paths.toList(),
            dots.toList(),
            links.toList(),
            groups.toList(),
        )
    }
}

fun readingDiagram(block: ReadingDiagramBuilder.() -> Unit) =
    ReadingDiagramBuilder().apply(block).build()
