@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package afield

import afield.runtime.TopologyWatcher
import afield.stdlib.Vec2
import afield.stdlib.vec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A client's drawing, as it now stands in that window. */
@Serializable
data class NotationSceneUpdate(val scene: String)

/** What a client has selected in the drawing layer. */
@Serializable
data class NotationSelection(val ids: List<String>)

/**
 * The server side of the drawing layer: clients send their scene here (not as a raw `set-string`),
 * and it is *merged* into the authoritative one by element version, so a mark written by code and
 * a mark drawn by hand, arriving together, both survive. What a person changed then goes to the
 * reactions ([dispatchNotationChange]) and to arrow auto-anchoring.
 */
@RunsAt(plugins)
fun installNotationHandlers() {
    handlers["notation-scene"] = mutableSetOf({ message, _ ->
        val info = thisSessionInfo.get()
        val update = Json.decodeFromJsonElement<NotationSceneUpdate>(message.payload!!)
        val incoming = NotationScene.parse(update.scene)
        val (before, merged) = NotationStore.update(info) { current -> NotationScene.merge(current, incoming) }
        val human = NotationChange.between(before, merged)
        if (!human.isEmpty) {
            NotationAnchors.autoAnchor(info, human)
            dispatchNotationChange(info, human)
        }
        Handled.yes
    })

    // "Anchor Drawing to Boxes": each selected mark follows the box under it; an arrow's ends are
    // anchored separately. With nothing selected, every unanchored mark over a box is.
    handlers["notation-anchor-selection"] = mutableSetOf({ _, _ ->
        val info = thisSessionInfo.get()
        val selected = NotationStore.selection(info).toSet()
        var count = 0
        NotationStore.update(info) { scene ->
            val targets = scene.live.filter { e ->
                if (selected.isNotEmpty()) e.string("id") in selected
                else e.custom("fieldAnchor") == null && e.custom("fieldStart") == null && e.custom("fieldEnd") == null
            }
            val changed = targets.mapNotNull { e -> NotationAnchors.anchorToBoxUnder(info, e)?.also { count++ } }
            scene.withElements(changed)
        }
        NotationAnchors.ensureHooks(info)
        notify(if (count == 0) "No marks over boxes to anchor" else "Anchored $count mark${if (count == 1) "" else "s"} to boxes", "drawing")
        Handled.yes
    })

    handlers["notation-selection"] = mutableSetOf({ message, _ ->
        val info = thisSessionInfo.get()
        val sel = Json.decodeFromJsonElement<NotationSelection>(message.payload!!)
        NotationStore.setSelection(info, sel.ids)
        Handled.yes
    })

    // A document that has just opened has anchored marks in its scene but no frame hooks yet.
    TopologyWatcher("notation", intervalMs = 250) { boxes ->
        boxes.firstOrNull { it.isInsideInitialized() }?.inside?.let { NotationAnchors.ensureHooks(it) }
    }.start()
}

/**
 * Marks that follow boxes.
 *
 * Two kinds of anchor, both in `customData`, both *absolute* — recomputing from them is idempotent,
 * so it does not matter how many clients or frame updates trigger it:
 *
 *  - `fieldAnchor = {box, dx, dy}` — the mark's origin is the box's origin plus (dx, dy).
 *  - `fieldStart` / `fieldEnd = {box, u, v}` or `{box, edge: true}` — one end of an arrow or line,
 *    either at a fixed place in the box's frame (u, v in 0..1) or on the box's edge, facing the
 *    other end. The points in between follow the ends by a similarity transform.
 */
internal object NotationAnchors {
    private val hooked = ConcurrentHashMap.newKeySet<Box>()
    private val pending = ConcurrentHashMap.newKeySet<SessionInformation>()

    fun anchored(e: JsonObject, box: Box): JsonObject {
        val f = box.frame
        return e.withCustom("fieldAnchor", buildJsonObject {
            put("box", JsonPrimitive(box.id))
            put("dx", JsonPrimitive((e.double("x") ?: 0.0) - f.x))
            put("dy", JsonPrimitive((e.double("y") ?: 0.0) - f.y))
        })
    }

    /** Hooks frame changes of every box an anchor in [info]'s scene refers to. */
    fun ensureHooks(info: SessionInformation) {
        val scene = NotationStore.scene(info)
        val ids = HashSet<String>()
        for (e in scene.live) {
            for (k in listOf("fieldAnchor", "fieldStart", "fieldEnd")) {
                e.custom(k)?.asObject()?.string("box")?.let { ids += it }
            }
        }
        for (id in ids) {
            val box = info.boxes[id] ?: continue
            if (hooked.add(box)) {
                box.onFrameChange["notation"] = { _, _, incoming ->
                    schedule(info)
                    incoming
                }
            }
        }
    }

    /** Coalesces a burst of frame changes (a drag) into one recompute on the main loop. */
    private fun schedule(info: SessionInformation) {
        if (pending.add(info)) inMain {
            pending.remove(info)
            recompute(info)
        }
    }

    /** Recomputes every anchored mark in [info]'s scene from the boxes' current frames. */
    fun recompute(info: SessionInformation) {
        NotationStore.update(info) { scene ->
            val changed = scene.live.mapNotNull { e -> follow(info, e)?.takeIf { it != e } }
            scene.withElements(changed)
        }
    }

    private fun follow(info: SessionInformation, e: JsonObject): JsonObject? {
        val kind = MarkType.of(e.string("type"))
        val start = e.custom("fieldStart")?.asObject()
        val end = e.custom("fieldEnd")?.asObject()
        if (kind.isLinear && (start != null || end != null)) {
            val pts = e.worldPoints()
            if (pts.size < 2) return null
            val s0 = pts.first(); val e0 = pts.last()
            // Resolve fixed (u, v) ends first, so an edge end can face the other end's new place.
            var s1 = start?.let { fixedEnd(info, it) } ?: s0
            var e1 = end?.let { fixedEnd(info, it) } ?: e0
            if (start != null && start.bool("edge") == true) {
                val b = info.boxes[start.string("box")] ?: return null
                val toward = if (end?.bool("edge") == true) info.boxes[end.string("box")]?.frame?.center() ?: e1 else e1
                s1 = edgeToward(b.frame, toward.x.toDouble(), toward.y.toDouble())
            }
            if (end != null && end.bool("edge") == true) {
                val b = info.boxes[end.string("box")] ?: return null
                val toward = if (start?.bool("edge") == true) info.boxes[start.string("box")]?.frame?.center() ?: s1 else s1
                e1 = edgeToward(b.frame, toward.x.toDouble(), toward.y.toDouble())
            }
            if (near(s0, s1) && near(e0, e1)) return null
            return e.withWorldPoints(similarity(pts, s0, e0, s1, e1))
        }
        val anchor = e.custom("fieldAnchor")?.asObject() ?: return null
        val box = info.boxes[anchor.string("box")] ?: return null
        val nx = box.frame.x + (anchor.double("dx") ?: 0.0)
        val ny = box.frame.y + (anchor.double("dy") ?: 0.0)
        val ox = e.double("x") ?: 0.0
        val oy = e.double("y") ?: 0.0
        if (abs(nx - ox) < 0.01 && abs(ny - oy) < 0.01) return null
        return e.with("x" to JsonPrimitive(nx), "y" to JsonPrimitive(ny))
    }

    private fun fixedEnd(info: SessionInformation, a: JsonObject): Vec2? {
        if (a.bool("edge") == true) return null
        val f = info.boxes[a.string("box")]?.frame ?: return null
        return vec(f.x + (a.double("u") ?: 0.5) * f.w, f.y + (a.double("v") ?: 0.5) * f.h)
    }

    /** [e] anchored to the box under it (an arrow or line: each end to the box under that end), or null. */
    fun anchorToBoxUnder(info: SessionInformation, e: JsonObject): JsonObject? {
        val kind = MarkType.of(e.string("type"))
        if (kind == MarkType.ARROW || kind == MarkType.LINE) {
            val pts = e.worldPoints()
            if (pts.size < 2) return null
            var next = e
            for ((key, p) in listOf("fieldStart" to pts.first(), "fieldEnd" to pts.last())) {
                val b = boxAt(info, p.x.toDouble(), p.y.toDouble(), margin = 8.0) ?: continue
                val f = b.frame
                next = next.withCustom(key, buildJsonObject {
                    put("box", JsonPrimitive(b.id))
                    put("u", JsonPrimitive((p.x - f.x) / f.w.coerceAtLeast(1.0)))
                    put("v", JsonPrimitive((p.y - f.y) / f.h.coerceAtLeast(1.0)))
                })
            }
            return next.takeIf { it != e }
        }
        val c = e.worldBounds().center()
        val b = boxAt(info, c.x.toDouble(), c.y.toDouble()) ?: return null
        return anchored(e, b)
    }

    /**
     * Arrows a person drew or re-ended: an end that lands on a box gets anchored there, and an end
     * that has been dragged off its box loses its anchor. Code-owned arrows keep what code gave them.
     */
    fun autoAnchor(info: SessionInformation, change: NotationChange) {
        val candidates = (change.added + change.updated).filter {
            MarkType.of(it.string("type")) == MarkType.ARROW && it.custom("fieldOwner") == null
        }
        if (candidates.isEmpty()) return
        NotationStore.update(info) { scene ->
            val rewritten = candidates.mapNotNull { original ->
                val e = scene[original.string("id") ?: ""] ?: return@mapNotNull null
                val pts = e.worldPoints()
                if (pts.size < 2) return@mapNotNull null
                var next = e
                for ((key, p) in listOf("fieldStart" to pts.first(), "fieldEnd" to pts.last())) {
                    val box = boxAt(info, p.x.toDouble(), p.y.toDouble(), margin = 8.0)
                    val existing = e.custom(key)?.asObject()
                    val value = box?.let { b ->
                        if (existing?.string("box") == b.id) existing
                        else buildJsonObject {
                            val f = b.frame
                            put("box", JsonPrimitive(b.id))
                            put("u", JsonPrimitive(((p.x - f.x) / f.w.coerceAtLeast(1.0)).coerceIn(-0.1, 1.1)))
                            put("v", JsonPrimitive(((p.y - f.y) / f.h.coerceAtLeast(1.0)).coerceIn(-0.1, 1.1)))
                        }
                    }
                    // A dragged end means a new (u, v) even on the same box.
                    val moved = existing != null && box != null && existing.string("box") == box.id &&
                        existing.bool("edge") != true && fixedEnd(info, existing)?.let { !near(it, p, 1.5) } == true
                    val finalValue = if (moved) buildJsonObject {
                        val f = box.frame
                        put("box", JsonPrimitive(box.id))
                        put("u", JsonPrimitive((p.x - f.x) / f.w.coerceAtLeast(1.0)))
                        put("v", JsonPrimitive((p.y - f.y) / f.h.coerceAtLeast(1.0)))
                    } else value
                    if (finalValue != existing) next = next.withCustom(key, finalValue)
                }
                next.takeIf { it != e }
            }
            scene.withElements(rewritten)
        }
        ensureHooks(info)
    }

    /** The smallest visible box whose frame contains (x, y), grown by [margin]. */
    fun boxAt(info: SessionInformation, x: Double, y: Double, margin: Double = 0.0): Box? =
        info.boxes.values
            .filter { it.id != "root" && it.frame.w > 0 && it.frame.h > 0 && it.frame.containsPoint(x, y, margin) }
            .minByOrNull { it.frame.w * it.frame.h }

    /** The two points where the line between [a]'s and [b]'s centres leaves each rect, pulled in by a gap. */
    fun edgePoints(a: Rect, b: Rect): Pair<Vec2, Vec2> {
        val ca = a.center(); val cb = b.center()
        return edgeToward(a, cb.x.toDouble(), cb.y.toDouble()) to edgeToward(b, ca.x.toDouble(), ca.y.toDouble())
    }

    /** Where the ray from [r]'s centre toward (tx, ty) crosses [r]'s edge, plus a small gap outward. */
    fun edgeToward(r: Rect, tx: Double, ty: Double, gap: Double = 6.0): Vec2 {
        val cx = r.x + r.w / 2
        val cy = r.y + r.h / 2
        val dx = tx - cx
        val dy = ty - cy
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return vec(cx, cy)
        val sx = if (abs(dx) < 1e-9) Double.POSITIVE_INFINITY else (r.w / 2) / abs(dx)
        val sy = if (abs(dy) < 1e-9) Double.POSITIVE_INFINITY else (r.h / 2) / abs(dy)
        val s = minOf(sx, sy)
        val len = hypot(dx, dy)
        return vec(cx + dx * s + dx / len * gap, cy + dy * s + dy / len * gap)
    }

    /** Maps [pts] by the rotation+scale+translation taking (s0, e0) to (s1, e1). */
    private fun similarity(pts: List<Vec2>, s0: Vec2, e0: Vec2, s1: Vec2, e1: Vec2): List<Vec2> {
        val ax = (e0.x - s0.x).toDouble(); val ay = (e0.y - s0.y).toDouble()
        val bx = (e1.x - s1.x).toDouble(); val by = (e1.y - s1.y).toDouble()
        val la = hypot(ax, ay)
        if (la < 1e-6) return pts.mapIndexed { i, _ -> if (i == 0) s1 else e1 }.let { if (pts.size > 2) listOf(s1) + List(pts.size - 2) { s1 } + listOf(e1) else it }
        val scale = hypot(bx, by) / la
        val rot = atan2(by, bx) - atan2(ay, ax)
        val c = cos(rot) * scale
        val sn = sin(rot) * scale
        return pts.mapIndexed { i, p ->
            when (i) {
                0 -> s1
                pts.lastIndex -> e1
                else -> {
                    val px = (p.x - s0.x).toDouble(); val py = (p.y - s0.y).toDouble()
                    vec(s1.x + c * px - sn * py, s1.y + sn * px + c * py)
                }
            }
        }
    }

    private fun near(a: Vec2, b: Vec2, eps: Double = 0.01) = abs(a.x - b.x) < eps && abs(a.y - b.y) < eps
}

internal fun Rect.center(): Vec2 = vec(x + w / 2, y + h / 2)
