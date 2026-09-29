package afield

import afield.graphics.FLine
import afield.stdlib.Delegation
import afield.stdlib.Vec2
import afield.stdlib.vec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The document's drawing, as seen from this box — read it, write to it, and react to it.
 *
 * ```
 * notation.strokes.forEach { println(it.bounds) }        // what has been drawn
 * notation { arrow(from = thisBox, to = other) }          // draw from code; re-running replaces it
 * notation.onStroke { s -> println("drawn: ${s.bounds}") } // react to a hand-drawn stroke
 * ```
 *
 * The box is the implicit receiver, so this is just `notation`. See `dev_docs/NOTATION.md`.
 */
val Box.notation: Notation get() = Notation(this)

/** Something a mark can be referred to by: a [Mark] read from the scene, or a [MarkRef] just built. */
interface MarkHandle {
    val id: String
}

/** A mark created in a `notation { }` block, before it has been read back from the scene. */
data class MarkRef(override val id: String) : MarkHandle

/**
 * One element of the drawing — a stroke, a shape, a text, an arrow — read from the scene.
 *
 * A Mark is a snapshot: it does not change when the drawing does. Ask [Notation] again for the
 * current state, and use [Notation.edit] to change one.
 */
class Mark internal constructor(
    /** The element exactly as Excalidraw stores it. */
    val json: JsonObject,
    private val view: Notation,
) : MarkHandle {
    override val id: String get() = json.string("id") ?: ""
    val kind: MarkType get() = MarkType.of(json.string("type"))

    /** World-space bounds (unrotated), in the same units as a box's `frame`. */
    val bounds: Rect get() = json.worldBounds()
    val center: Vec2 get() = bounds.let { vec(it.x + it.w / 2, it.y + it.h / 2) }
    val angle: Double get() = json.double("angle") ?: 0.0

    /** World-space points of a stroke, line or arrow; empty for other kinds. */
    val points: List<Vec2> get() = if (kind.isLinear) json.worldPoints() else emptyList()
    val start: Vec2? get() = points.firstOrNull()
    val end: Vec2? get() = points.lastOrNull()

    val stroke: String get() = json.string("strokeColor") ?: "#1e1e1e"
    val fill: String get() = json.string("backgroundColor") ?: "transparent"
    val strokeWidth: Double get() = json.double("strokeWidth") ?: 1.0
    val opacity: Double get() = json.double("opacity") ?: 100.0

    /** The text of a text mark. */
    val text: String? get() = if (kind == MarkType.TEXT) json.string("text") else null

    /** The text bound inside a shape or on an arrow, if there is one. */
    val boundText: Mark? get() = (json["boundElements"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.takeIf { e -> e.string("type") == "text" }?.string("id") }
        ?.firstNotNullOfOrNull { view[it] }

    /**
     * A name given from code (`name =`), else the mark's own text, else the text bound to it, else
     * — for a frame — the title Excalidraw keeps in the frame's own `name` field, which is where a
     * frame named by hand has its name.
     */
    val label: String? get() = name ?: text ?: boundText?.text ?: (if (kind == MarkType.FRAME) json.string("name") else null)

    /** The name given from code with `name =`, or through [Notation.edit]. */
    val name: String? get() = json.custom("fieldName")?.stringOrNull()

    /** The box whose `notation { }` block wrote this mark; null for a mark drawn by hand. */
    val owner: Box? get() = ownerId?.let { view.boxById(it) }
    val ownerId: String? get() = json.custom("fieldOwner")?.stringOrNull()
    val handDrawn: Boolean get() = ownerId == null

    /** The box under this mark's centre, if any (the smallest, when boxes overlap). */
    val box: Box? get() = view.boxAt(center)

    /** For an arrow or line: the box its first point is on. */
    val fromBox: Box? get() = start?.let { view.boxAt(it, margin = 8.0) }

    /** For an arrow or line: the box its last point is on. */
    val toBox: Box? get() = end?.let { view.boxAt(it, margin = 8.0) }

    /** The box this mark follows when boxes move, if it is anchored. */
    val anchoredTo: Box? get() = json.custom("fieldAnchor")?.asObject()?.string("box")?.let { view.boxById(it) }

    /** The Excalidraw frame this mark is in, if any. */
    val frame: Mark? get() = json.string("frameId")?.let { view[it] }

    fun contains(p: Vec2): Boolean = bounds.let { p.x >= it.x && p.x <= it.x + it.w && p.y >= it.y && p.y <= it.y + it.h }

    /** The mark's geometry in world coordinates, rotation included: a stroke's polyline, or a shape's outline. */
    fun toFLine(): FLine = FLine().also { f ->
        MarkGeometry.of(json).paths.firstOrNull()?.forEachIndexed { i, p -> if (i == 0) f.moveTo(p.x, p.y) else f.lineTo(p.x, p.y) }
    }

    /** Where this mark crosses the vertical line at world [x]: its y values there (a shape gives its edges). */
    fun yAt(x: Double): List<Double> = Probe.crossings(MarkGeometry.of(json), ProbeAxis.VERTICAL, x).map { it.y }.sorted()

    /** Where this mark crosses the horizontal line at world [y]: its x values there. */
    fun xAt(y: Double): List<Double> = Probe.crossings(MarkGeometry.of(json), ProbeAxis.HORIZONTAL, y).map { it.x }.sorted()

    /** The length of the mark's path (a shape's perimeter), in world units. */
    val length: Double get() = MarkGeometry.of(json).length

    override fun toString(): String =
        "Mark(${kind.name.lowercase()} $id${label?.let { " \"$it\"" } ?: ""} at ${bounds.x.toInt()},${bounds.y.toInt()} ${bounds.w.toInt()}×${bounds.h.toInt()})"

    override fun equals(other: Any?): Boolean = other is Mark && other.id == id && other.json == json
    override fun hashCode(): Int = id.hashCode()
}

/** What changed in the drawing, handed to [Notation.onChange]. */
class NotationEdit internal constructor(
    val added: List<Mark>,
    val updated: List<Mark>,
    val removed: List<Mark>,
    /** The drawing as it now stands. */
    val notation: Notation,
)

/**
 * The drawing, as seen from one box. `notation` in box code gives you one; it reads the scene when
 * you ask it something, so a Notation kept in a variable still answers with the current drawing.
 */
class Notation internal constructor(private val box: Box) {

    private val info: SessionInformation? get() = if (box.isInsideInitialized()) box.inside else null
    internal val scene: NotationScene get() = info?.let { NotationStore.scene(it) } ?: NotationScene.EMPTY

    // ── reading ─────────────────────────────────────────────────────────────────────────────────

    /** Every mark in the drawing, back to front. */
    val marks: List<Mark> get() = scene.live.map { Mark(it, this) }

    val strokes: List<Mark> get() = marks.filter { it.kind == MarkType.STROKE }
    val arrows: List<Mark> get() = marks.filter { it.kind == MarkType.ARROW }
    val lines: List<Mark> get() = marks.filter { it.kind == MarkType.LINE }
    val texts: List<Mark> get() = marks.filter { it.kind == MarkType.TEXT }
    val shapes: List<Mark> get() = marks.filter { it.kind == MarkType.RECTANGLE || it.kind == MarkType.ELLIPSE || it.kind == MarkType.DIAMOND }
    val frames: List<Mark> get() = marks.filter { it.kind == MarkType.FRAME }

    /** The marks this box wrote, from every `notation { }` block. */
    val mine: List<Mark> get() = marks.filter { it.ownerId == box.id }

    /** What is selected in the drawing layer right now, in any window showing this document. */
    val selected: List<Mark> get() = info?.let { i -> NotationStore.selection(i).mapNotNull { get(it) } } ?: emptyList()

    operator fun get(id: String): Mark? = scene[id]?.takeIf { !it.isDeletedElement() }?.let { Mark(it, this) }

    /** The first mark labelled [name] — a code-given name, its own text, or the text bound to it. */
    fun named(name: String): Mark? = marks.firstOrNull { it.label?.trim().equals(name.trim(), ignoreCase = true) }

    fun allNamed(name: String): List<Mark> = marks.filter { it.label?.trim().equals(name.trim(), ignoreCase = true) }

    /** Marks whose centre lies on [box]. */
    fun over(box: Box): List<Mark> = marks.filter { m -> m.center.let { box.frame.containsPoint(it.x.toDouble(), it.y.toDouble()) } }

    /** Marks entirely inside [rect]. */
    fun inside(rect: Rect): List<Mark> = marks.filter { m ->
        val b = m.bounds
        b.x >= rect.x && b.y >= rect.y && b.x + b.w <= rect.x + rect.w && b.y + b.h <= rect.y + rect.h
    }

    /** The marks in an Excalidraw frame. */
    fun inside(frame: Mark): List<Mark> = marks.filter { it.json.string("frameId") == frame.id }

    /** Marks whose bounds come within [radius] of [p], nearest first. */
    fun near(p: Vec2, radius: Double): List<Mark> = marks
        .map { it to distanceToRect(p, it.bounds) }
        .filter { it.second <= radius }
        .sortedBy { it.second }
        .map { it.first }

    /** Arrows (and lines) whose first point is on [box]. */
    fun arrowsFrom(box: Box): List<Mark> = marks.filter { it.kind.let { k -> k == MarkType.ARROW || k == MarkType.LINE } && it.fromBox == box }

    /** Arrows (and lines) whose last point is on [box]. */
    fun arrowsTo(box: Box): List<Mark> = marks.filter { it.kind.let { k -> k == MarkType.ARROW || k == MarkType.LINE } && it.toBox == box }

    // ── cutting ─────────────────────────────────────────────────────────────────────────────────

    /** Cut the whole drawing with the vertical line at world [x]; hit values are world y. */
    fun vertical(x: Double): List<Hit> = cut(marks, ProbeAxis.VERTICAL, x) { it }

    /** Cut the whole drawing with the horizontal line at world [y]; hit values are world x. */
    fun horizontal(y: Double): List<Hit> = cut(marks, ProbeAxis.HORIZONTAL, y) { it }

    /** Every place the segment [from]–[to] crosses a mark, nearest [from] first; hit values are 0..1 along the segment. */
    fun intersect(from: Vec2, to: Vec2): List<Hit> = marks.flatMap { m ->
        Probe.crossings(MarkGeometry.of(m.json), from, to).map { (t, c) ->
            Hit(m, vec(c.x, c.y), t, null, c.along, 0.0, c.pressure)
        }
    }.sortedBy { it.value }

    /** Every place an [FLine] (a probe of any shape) crosses a mark, in order along the FLine. */
    fun intersect(probe: FLine): List<Hit> {
        val pts = probe.nodes.mapNotNull { it.toOrNull }.map { vec(it.x, it.y) }
        return pts.zipWithNext().flatMapIndexed { i, (a, b) ->
            intersect(a, b).map { h -> Hit(h.mark, h.point, i + h.value, null, h.along, 0.0, h.pressure) }
        }
    }

    /**
     * A region of the drawing read with its own axes — see [NotationSpace]. [region] is the
     * rectangle; [x] and [y] are what its left→right and top→bottom mean.
     */
    fun space(region: Rect, x: ClosedFloatingPointRange<Double>, y: ClosedFloatingPointRange<Double>): NotationSpace =
        NotationSpace(this, { region }, x, y)

    /** A region that is a box's frame: move the box and the space moves with it. */
    fun space(region: Box, x: ClosedFloatingPointRange<Double>, y: ClosedFloatingPointRange<Double>): NotationSpace =
        NotationSpace(this, { region.frame }, x, y)

    /** A region that is the frame (or any mark) labelled [name], wherever it is now. */
    fun space(name: String, x: ClosedFloatingPointRange<Double>, y: ClosedFloatingPointRange<Double>): NotationSpace =
        NotationSpace(this, { named(name)?.bounds }, x, y)

    internal fun cut(among: List<Mark>, axis: ProbeAxis, at: Double, map: (Double) -> Double): List<Hit> {
        val out = ArrayList<Hit>()
        for (m in among) {
            val g = MarkGeometry.of(m.json)
            val cs = Probe.crossings(g, axis, at)
            if (cs.isEmpty()) continue
            val across = { c: Crossing -> if (axis == ProbeAxis.VERTICAL) c.y else c.x }
            if (g.closed) {
                val sorted = cs.sortedBy(across)
                var i = 0
                while (i + 1 < sorted.size) {
                    val a = map(across(sorted[i])); val b = map(across(sorted[i + 1]))
                    val lo = min(a, b); val hi = max(a, b)
                    val mid = (across(sorted[i]) + across(sorted[i + 1])) / 2
                    val point = if (axis == ProbeAxis.VERTICAL) vec(at, mid) else vec(mid, at)
                    out += Hit(m, point, (lo + hi) / 2, lo..hi, sorted[i].along, 0.0, null)
                    i += 2
                }
            } else {
                for (c in cs) out += Hit(m, vec(c.x, c.y), map(across(c)), null, c.along, c.dAcrossDProbe, c.pressure)
            }
        }
        return out.sortedBy { it.value }
    }

    // ── writing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Draws from code. The marks made here belong to this box under [key]: running the block
     * again replaces them (updating in place, so the drawing does not flicker), and marks drawn
     * by hand are never touched.
     */
    operator fun invoke(key: String = "default", build: NotationBuilder.() -> Unit): List<MarkRef> {
        val i = info ?: return emptyList()
        val builder = NotationBuilder(box, key, this)
        builder.build()
        NotationStore.replaceOwned(i, box.id, key, builder.made, builder.pendingBindings)
        return builder.made.mapNotNull { it.string("id")?.let(::MarkRef) }
    }

    /** Removes the marks this box drew — under [key], or under every key when [key] is null. */
    fun clear(key: String? = null) {
        val i = info ?: return
        NotationStore.update(i) { scene ->
            scene.withElements(scene.live.filter { e ->
                e.custom("fieldOwner")?.stringOrNull() == box.id &&
                    (key == null || e.custom("fieldKey")?.stringOrNull() == key)
            }.map { it.with("isDeleted" to JsonPrimitive(true)) })
        }
    }

    /** Changes any mark — including one drawn by hand. */
    fun edit(mark: MarkHandle, block: MarkEditor.() -> Unit): Mark? {
        val i = info ?: return null
        var result: JsonObject? = null
        NotationStore.update(i) { scene ->
            val current = scene[mark.id]?.takeIf { !it.isDeletedElement() } ?: return@update scene
            val editor = MarkEditor(current)
            editor.block()
            val next = editor.build()
            result = next
            scene.withElement(next)
        }
        return result?.let { NotationStore.scene(i)[it.string("id") ?: ""] }?.let { Mark(it, this) }
    }

    fun delete(mark: MarkHandle) {
        val i = info ?: return
        NotationStore.update(i) { scene ->
            val current = scene[mark.id] ?: return@update scene
            scene.withElement(current.with("isDeleted" to JsonPrimitive(true)))
        }
    }

    /** Makes [mark] follow [box] when the box moves. */
    fun anchor(mark: MarkHandle, to: Box) {
        val i = info ?: return
        NotationStore.update(i) { scene ->
            val e = scene[mark.id] ?: return@update scene
            scene.withElement(NotationAnchors.anchored(e, to))
        }
        NotationAnchors.ensureHooks(i)
    }

    fun unanchor(mark: MarkHandle) {
        val i = info ?: return
        NotationStore.update(i) { scene ->
            val e = scene[mark.id] ?: return@update scene
            scene.withElement(e.withCustom("fieldAnchor", null).withCustom("fieldStart", null).withCustom("fieldEnd", null))
        }
    }

    // ── reacting ────────────────────────────────────────────────────────────────────────────────

    /**
     * Calls [handler] whenever someone changes the drawing by hand. Running the box again replaces
     * the handler registered under the same [key]; stopping the box removes it. Changes this code
     * makes itself do not call it back.
     */
    fun onChange(key: String = "change", handler: (NotationEdit) -> Unit) =
        register(key, NotationHandler { ev -> handler(ev) })

    /** Calls [handler] for each stroke drawn by hand, once it is finished. */
    fun onStroke(key: String = "stroke", handler: (Mark) -> Unit) =
        register(key, NotationHandler { ev -> ev.added.filter { it.kind == MarkType.STROKE }.forEach(handler) })

    /** Calls [handler] for each arrow drawn by hand, and again when its ends are moved. */
    fun onArrow(key: String = "arrow", handler: (Mark) -> Unit) =
        register(key, NotationHandler { ev -> (ev.added + ev.updated).filter { it.kind == MarkType.ARROW }.forEach(handler) })

    /** Calls [handler] for each text typed by hand, and again when it is edited. */
    fun onText(key: String = "text", handler: (Mark) -> Unit) =
        register(key, NotationHandler { ev -> (ev.added + ev.updated).filter { it.kind == MarkType.TEXT }.forEach(handler) })

    private fun register(key: String, handler: NotationHandler) {
        box.notationHandlers[key] = handler
        if (box.notationStopHookInstalled != true) {
            box.notationStopHookInstalled = true
            box.registerStopHook {
                box.notationHandlers.clear()
                box.notationStopHookInstalled = false
            }
        }
    }

    // ── helpers used by Mark ───────────────────────────────────────────────────────────────────

    internal fun boxById(id: String): Box? = info?.boxes?.get(id)

    internal fun boxAt(p: Vec2, margin: Double = 0.0): Box? = info?.let { NotationAnchors.boxAt(it, p.x.toDouble(), p.y.toDouble(), margin) }

    private fun distanceToRect(p: Vec2, r: Rect): Double {
        val dx = max(max(r.x - p.x, 0.0), p.x - (r.x + r.w))
        val dy = max(max(r.y - p.y, 0.0), p.y - (r.y + r.h))
        return hypot(dx, dy)
    }
}

internal fun Rect.containsPoint(px: Double, py: Double, margin: Double = 0.0): Boolean =
    px >= x - margin && px <= x + w + margin && py >= y - margin && py <= y + h + margin

internal fun interface NotationHandler {
    fun handle(event: NotationEdit)
}

internal var Box.notationHandlers by Delegation<MutableMap<String, NotationHandler>>(defaultValue = { ConcurrentHashMap() })
internal var Box.notationStopHookInstalled by Delegation<Boolean?>()

/** Runs [box]'s reaction handlers for a change a person made. */
@OptIn(DelicateCoroutinesApi::class)
internal fun dispatchNotationChange(info: SessionInformation, change: NotationChange) {
    if (change.isEmpty) return
    for (box in info.boxes.values) {
        val handlers = box.notationHandlers
        if (handlers.isEmpty()) continue
        val view = Notation(box)
        val event = NotationEdit(
            added = change.added.map { Mark(it, view) },
            updated = change.updated.map { Mark(it, view) },
            removed = change.removed.map { Mark(it, view) },
            notation = view,
        )
        for ((key, handler) in handlers) {
            GlobalScope.launch(Box.boxContextElement(box), start = CoroutineStart.UNDISPATCHED) {
                try {
                    handler.handle(event)
                } catch (t: Throwable) {
                    box.output.errorProxy("notation handler '$key': ${t.message ?: t.javaClass.simpleName}", "code", 0)
                }
            }
        }
    }
}

/** Sets the fields of one mark; see [Notation.edit]. Unset fields keep their values. */
class MarkEditor internal constructor(private val original: JsonObject) {
    private val changes = LinkedHashMap<String, JsonElement>()
    private var custom: JsonObject = original.obj("customData") ?: JsonObject(emptyMap())

    var x: Double
        get() = (changes["x"] as? JsonPrimitive)?.doubleOrNull ?: original.double("x") ?: 0.0
        set(v) { changes["x"] = JsonPrimitive(v) }
    var y: Double
        get() = (changes["y"] as? JsonPrimitive)?.doubleOrNull ?: original.double("y") ?: 0.0
        set(v) { changes["y"] = JsonPrimitive(v) }
    var width: Double
        get() = (changes["width"] as? JsonPrimitive)?.doubleOrNull ?: original.double("width") ?: 0.0
        set(v) { changes["width"] = JsonPrimitive(v) }
    var height: Double
        get() = (changes["height"] as? JsonPrimitive)?.doubleOrNull ?: original.double("height") ?: 0.0
        set(v) { changes["height"] = JsonPrimitive(v) }
    var angle: Double
        get() = (changes["angle"] as? JsonPrimitive)?.doubleOrNull ?: original.double("angle") ?: 0.0
        set(v) { changes["angle"] = JsonPrimitive(v) }
    var stroke: String
        get() = (changes["strokeColor"] as? JsonPrimitive)?.content ?: original.string("strokeColor") ?: "#1e1e1e"
        set(v) { changes["strokeColor"] = JsonPrimitive(v) }
    var fill: String
        get() = (changes["backgroundColor"] as? JsonPrimitive)?.content ?: original.string("backgroundColor") ?: "transparent"
        set(v) { changes["backgroundColor"] = JsonPrimitive(v); if (v != "transparent") changes.putIfAbsent("fillStyle", JsonPrimitive("solid")) }
    var strokeWidth: Double
        get() = (changes["strokeWidth"] as? JsonPrimitive)?.doubleOrNull ?: original.double("strokeWidth") ?: 1.0
        set(v) { changes["strokeWidth"] = JsonPrimitive(v) }
    var opacity: Double
        get() = (changes["opacity"] as? JsonPrimitive)?.doubleOrNull ?: original.double("opacity") ?: 100.0
        set(v) { changes["opacity"] = JsonPrimitive(v) }
    var text: String?
        get() = (changes["text"] as? JsonPrimitive)?.content ?: original.string("text")
        set(v) { if (v != null) { changes["text"] = JsonPrimitive(v); changes["originalText"] = JsonPrimitive(v) } }
    var name: String?
        get() = custom["fieldName"]?.stringOrNull()
        set(v) { custom = if (v == null) JsonObject(custom - "fieldName") else JsonObject(custom + ("fieldName" to JsonPrimitive(v))) }

    fun moveBy(dx: Double, dy: Double) { x += dx; y += dy }

    internal fun build(): JsonObject = JsonObject(original + changes + ("customData" to custom))
}

/**
 * The drawing vocabulary of a `notation { }` block. Every call makes one mark owned by the box and
 * returns a [MarkRef] to it. The `var`s set defaults for the calls that follow them.
 */
class NotationBuilder internal constructor(private val owner: Box, private val key: String, private val view: Notation) {
    internal val made = ArrayList<JsonObject>()
    private var counter = 0

    var stroke: String = "#1e1e1e"
    var fill: String = "transparent"
    var strokeWidth: Double = 2.0
    var roughness: Double = 1.0
    var opacity: Double = 100.0
    var dashed: Boolean = false
    var fontSize: Double = 20.0

    /** When set, the marks made after it follow this box when it moves. */
    var anchorTo: Box? = null

    fun rect(x: Number, y: Number, w: Number, h: Number, name: String? = null, label: String? = null,
             stroke: String = this.stroke, fill: String = this.fill): MarkRef =
        shape("rectangle", x, y, w, h, name, label, stroke, fill)

    fun ellipse(x: Number, y: Number, w: Number, h: Number, name: String? = null, label: String? = null,
                stroke: String = this.stroke, fill: String = this.fill): MarkRef =
        shape("ellipse", x, y, w, h, name, label, stroke, fill)

    fun diamond(x: Number, y: Number, w: Number, h: Number, name: String? = null, label: String? = null,
                stroke: String = this.stroke, fill: String = this.fill): MarkRef =
        shape("diamond", x, y, w, h, name, label, stroke, fill)

    fun text(text: String, x: Number, y: Number, size: Number = fontSize, name: String? = null,
             color: String = stroke): MarkRef {
        val id = nextId(name)
        val e = textElement(id, text, x.toDouble(), y.toDouble(), size.toDouble(), color, containerId = null, name = name)
        return add(e)
    }

    /** A straight or bent line through [points]. */
    fun line(points: List<Vec2>, name: String? = null, stroke: String = this.stroke): MarkRef =
        add(linear("line", nextId(name), points, name, stroke))

    /** [line] through the points of an [FLine] (curves are sampled). */
    fun line(fline: FLine, name: String? = null, stroke: String = this.stroke): MarkRef =
        line(sample(fline), name, stroke)

    /** A freehand stroke — the same kind of mark as one drawn with the pen tool. */
    fun stroke(fline: FLine, name: String? = null, stroke: String = this.stroke): MarkRef =
        stroke(sample(fline), name, stroke)

    fun stroke(points: List<Vec2>, name: String? = null, stroke: String = this.stroke): MarkRef {
        val e = linear("freedraw", nextId(name), points, name, stroke)
            .with("pressures" to JsonArray(emptyList()), "simulatePressure" to JsonPrimitive(true))
        return add(e)
    }

    /** An arrow between two points. */
    fun arrow(from: Vec2, to: Vec2, label: String? = null, name: String? = null): MarkRef =
        arrowElement(from, to, label, name) { it }

    /**
     * An arrow from one box to another. Its ends sit on the boxes' edges and follow the boxes as
     * they move.
     */
    fun arrow(from: Box, to: Box, label: String? = null, name: String? = null): MarkRef {
        val (s, e) = NotationAnchors.edgePoints(from.frame, to.frame)
        return arrowElement(s, e, label, name) {
            it.withCustom("fieldStart", edgeAnchor(from)).withCustom("fieldEnd", edgeAnchor(to))
        }
    }

    fun arrow(from: Box, to: Vec2, label: String? = null, name: String? = null): MarkRef {
        val s = NotationAnchors.edgeToward(from.frame, to.x.toDouble(), to.y.toDouble())
        return arrowElement(s, to, label, name) { it.withCustom("fieldStart", edgeAnchor(from)) }
    }

    fun arrow(from: Vec2, to: Box, label: String? = null, name: String? = null): MarkRef {
        val e = NotationAnchors.edgeToward(to.frame, from.x.toDouble(), from.y.toDouble())
        return arrowElement(from, e, label, name) { it.withCustom("fieldEnd", edgeAnchor(to)) }
    }

    /**
     * An arrow bound, the way Excalidraw binds them, to two marks: dragging either mark by hand
     * keeps the arrow attached.
     */
    fun arrow(from: MarkHandle, to: MarkHandle, label: String? = null, name: String? = null): MarkRef {
        val a = boundsOf(from.id) ?: error("no mark ${from.id}")
        val b = boundsOf(to.id) ?: error("no mark ${to.id}")
        val (s, e) = NotationAnchors.edgePoints(a, b)
        val ref = arrowElement(s, e, label, name) {
            it.with(
                "startBinding" to binding(from.id),
                "endBinding" to binding(to.id),
            )
        }
        bindTo(from.id, ref.id)
        bindTo(to.id, ref.id)
        return ref
    }

    /** An Excalidraw frame: a named region that groups the marks drawn inside it. */
    fun frame(name: String, x: Number, y: Number, w: Number, h: Number): MarkRef {
        val id = nextId(name)
        return add(base(id, "frame", x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), name, "#bbb", "transparent")
            .with("name" to JsonPrimitive(name)))
    }

    // ── element construction ───────────────────────────────────────────────────────────────────

    private fun shape(type: String, x: Number, y: Number, w: Number, h: Number, name: String?, label: String?,
                      stroke: String, fill: String): MarkRef {
        val id = nextId(name)
        var e = base(id, type, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), name, stroke, fill)
        if (type == "rectangle") e = e.with("roundness" to buildJsonObject { put("type", JsonPrimitive(3)) })
        val ref = add(e)
        if (label != null) addLabel(ref.id, label, e.worldBounds())
        return ref
    }

    private fun addLabel(containerId: String, label: String, b: Rect) {
        val tid = "$containerId-label"
        val size = fontSize
        val (tw, th) = measure(label, size)
        val t = textElement(tid, label, b.x + (b.w - tw) / 2, b.y + (b.h - th) / 2, size, stroke, containerId, null)
            .with("textAlign" to JsonPrimitive("center"), "verticalAlign" to JsonPrimitive("middle"))
        add(t)
        bindTo(containerId, tid, type = "text")
    }

    private fun arrowElement(from: Vec2, to: Vec2, label: String?, name: String?, decorate: (JsonObject) -> JsonObject): MarkRef {
        val id = nextId(name)
        val e = decorate(linear("arrow", id, listOf(from, to), name, stroke)
            .with("startArrowhead" to JsonNull, "endArrowhead" to JsonPrimitive("arrow")))
        val ref = add(e)
        if (label != null) addLabel(ref.id, label, e.worldBounds())
        return ref
    }

    private fun linear(type: String, id: String, points: List<Vec2>, name: String?, stroke: String): JsonObject =
        base(id, type, 0.0, 0.0, 0.0, 0.0, name, stroke, "transparent")
            .with("lastCommittedPoint" to JsonNull, "startBinding" to JsonNull, "endBinding" to JsonNull)
            .withWorldPoints(points.ifEmpty { listOf(vec(0, 0)) })

    private fun textElement(id: String, text: String, x: Double, y: Double, size: Double, color: String,
                            containerId: String?, name: String?): JsonObject {
        val (w, h) = measure(text, size)
        return base(id, "text", x, y, w, h, name, color, "transparent").with(
            "text" to JsonPrimitive(text),
            "originalText" to JsonPrimitive(text),
            "fontSize" to JsonPrimitive(size),
            "fontFamily" to JsonPrimitive(5),
            "textAlign" to JsonPrimitive("left"),
            "verticalAlign" to JsonPrimitive("top"),
            "containerId" to jsonString(containerId),
            "autoResize" to JsonPrimitive(true),
            "lineHeight" to JsonPrimitive(1.25),
        )
    }

    private fun base(id: String, type: String, x: Double, y: Double, w: Double, h: Double, name: String?,
                     stroke: String, fill: String): JsonObject {
        var custom = buildJsonObject {
            put("fieldOwner", JsonPrimitive(owner.id))
            put("fieldKey", JsonPrimitive(key))
            if (name != null) put("fieldName", JsonPrimitive(name))
        }
        val e = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
            put("width", JsonPrimitive(w)); put("height", JsonPrimitive(h))
            put("angle", JsonPrimitive(0))
            put("strokeColor", JsonPrimitive(stroke))
            put("backgroundColor", JsonPrimitive(fill))
            put("fillStyle", JsonPrimitive(if (fill == "transparent") "hachure" else "solid"))
            put("strokeWidth", JsonPrimitive(strokeWidth))
            put("strokeStyle", JsonPrimitive(if (dashed) "dashed" else "solid"))
            put("roughness", JsonPrimitive(roughness))
            put("opacity", JsonPrimitive(opacity))
            put("groupIds", JsonArray(emptyList()))
            put("frameId", JsonNull)
            put("roundness", JsonNull)
            put("seed", JsonPrimitive(abs(id.hashCode()) + 1))
            put("isDeleted", JsonPrimitive(false))
            put("boundElements", JsonArray(emptyList()))
            put("link", JsonNull)
            put("locked", JsonPrimitive(false))
            put("customData", custom)
        }
        val a = anchorTo
        return if (a != null && type != "arrow") NotationAnchors.anchored(e, a) else e
    }

    private fun add(e: JsonObject): MarkRef {
        made += e
        return MarkRef(e.string("id")!!)
    }

    private fun nextId(name: String?): String =
        if (name != null) "${owner.id}~$key~$name" else "${owner.id}~$key~${counter++}"

    private fun edgeAnchor(b: Box): JsonObject = buildJsonObject {
        put("box", JsonPrimitive(b.id)); put("edge", JsonPrimitive(true))
    }

    private fun binding(id: String): JsonObject = buildJsonObject {
        put("elementId", JsonPrimitive(id)); put("focus", JsonPrimitive(0)); put("gap", JsonPrimitive(6))
    }

    /** Records [childId] in [containerId]'s `boundElements`, whether the container is new or already in the scene. */
    private fun bindTo(containerId: String, childId: String, type: String = "arrow") {
        val entry = buildJsonObject { put("id", JsonPrimitive(childId)); put("type", JsonPrimitive(type)) }
        val idx = made.indexOfFirst { it.string("id") == containerId }
        if (idx >= 0) {
            val cur = made[idx]
            val list = (cur["boundElements"] as? JsonArray)?.toMutableList() ?: mutableListOf()
            if (list.none { (it as? JsonObject)?.string("id") == childId }) list += entry
            made[idx] = cur.with("boundElements" to JsonArray(list))
        } else {
            pendingBindings.getOrPut(containerId) { mutableListOf() } += entry
        }
    }

    /** Bindings onto marks this block did not create; applied by [NotationStore.replaceOwned]. */
    internal val pendingBindings = LinkedHashMap<String, MutableList<JsonObject>>()

    private fun boundsOf(id: String): Rect? =
        made.firstOrNull { it.string("id") == id }?.worldBounds() ?: view.scene[id]?.worldBounds()

    private fun measure(text: String, size: Double): Pair<Double, Double> {
        val lines = text.split('\n')
        return (lines.maxOf { it.length } * size * 0.55).coerceAtLeast(size) to lines.size * size * 1.25
    }

    private fun sample(fline: FLine): List<Vec2> {
        val out = ArrayList<Vec2>()
        var last: afield.stdlib.Vec3? = null
        for (node in fline.nodes) {
            val to = node.toOrNull ?: continue
            if (node.storage.size >= 3 && last != null) {
                val p0 = last; val c1 = node.storage[0]; val c2 = node.storage[1]; val p3 = node.storage[2]
                for (i in 1..12) {
                    val t = i / 12.0; val u = 1 - t
                    val px = u * u * u * p0.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * p3.x
                    val py = u * u * u * p0.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * p3.y
                    out += vec(px, py)
                }
            } else {
                out += vec(to.x, to.y)
            }
            last = to
        }
        return out
    }
}

/**
 * The authoritative scene for each document, and every read-modify-write of it. The scene lives in
 * the root box's `drawing` property; this caches its parse and serialises writers per document.
 */
internal object NotationStore {
    private class Cached(val text: String?, val scene: NotationScene)
    private val cache = ConcurrentHashMap<SessionInformation, Cached>()
    private val selections = ConcurrentHashMap<SessionInformation, List<String>>()

    fun text(info: SessionInformation): String? = info.root.getPropertyRaw("drawing") as? String

    fun scene(info: SessionInformation): NotationScene {
        val t = text(info)
        val c = cache[info]
        if (c != null && c.text === t) return c.scene
        val parsed = NotationScene.parse(t)
        cache[info] = Cached(t, parsed)
        return parsed
    }

    /** Applies [f] to the current scene and stores the result if anything changed. Returns (before, after). */
    fun update(info: SessionInformation, f: (NotationScene) -> NotationScene): Pair<NotationScene, NotationScene> =
        synchronized(info.root) {
            // A drawing that is present but unreadable reads as empty, and is never written over:
            // replacing it would turn a parse problem into lost work.
            if (NotationScene.parseOrNull(text(info)) == null) {
                notify("The document's drawing could not be read, so it was not changed", "drawing")
                return@synchronized scene(info) to scene(info)
            }
            val before = scene(info)
            val after = f(before)
            if (after !== before) {
                val text = after.serialize()
                if (text != text(info)) {
                    info.root.setPropertyRaw("drawing", text)
                    cache[info] = Cached(text, after)
                }
            }
            before to after
        }

    /** Replaces [owner]'s marks under [key] with [made]; unchanged marks keep their version. */
    fun replaceOwned(
        info: SessionInformation, owner: String, key: String, made: List<JsonObject>,
        bindings: Map<String, List<JsonObject>> = emptyMap(),
    ) {
        update(info) { scene ->
            val newIds = made.mapNotNull { it.string("id") }.toSet()
            val stale = scene.live.filter { e ->
                e.custom("fieldOwner")?.stringOrNull() == owner &&
                    e.custom("fieldKey")?.stringOrNull() == key &&
                    e.string("id") !in newIds
            }.map { it.with("isDeleted" to JsonPrimitive(true)) }
            val changed = made.filter { e ->
                val old = scene[e.string("id") ?: ""]
                old == null || old.isDeletedElement() || !sameContent(old, e)
            }
            // Arrows this block bound to marks it did not make: record them on those marks too, or
            // Excalidraw will not drag the arrow along when the mark is moved by hand.
            val rebound = bindings.mapNotNull { (containerId, entries) ->
                val container = scene[containerId] ?: return@mapNotNull null
                val list = (container["boundElements"] as? JsonArray)?.toMutableList() ?: mutableListOf()
                val have = list.mapNotNull { (it as? JsonObject)?.string("id") }.toSet()
                val add = entries.filter { it.string("id") !in have }
                if (add.isEmpty()) null else container.with("boundElements" to JsonArray(list + add))
            }
            scene.withElements(changed + stale + rebound)
        }
        NotationAnchors.ensureHooks(info)
        NotationAnchors.recompute(info)
    }

    private val volatileKeys = setOf("version", "versionNonce", "updated", "index", "seed")
    private fun sameContent(a: JsonObject, b: JsonObject): Boolean =
        JsonObject(a - volatileKeys) == JsonObject(b - volatileKeys)

    fun setSelection(info: SessionInformation, ids: List<String>) { selections[info] = ids }
    fun selection(info: SessionInformation): List<String> = selections[info] ?: emptyList()
}
