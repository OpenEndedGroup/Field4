package afield.app.canvas

import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.handlers
import afield.app.protocol.put
import afield.app.protocol.send
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

/**
 * The stroke layer: living strokes (the server's `eachStroke`, `afield.ui.notation.StrokeSketch`) drawn
 * on a 2D canvas in world coordinates, between Field's background and its boxes.
 *
 * The server sends a whole frame as `stroke-layer-frame` (the JSON `StrokeLayerFrames` writes) and
 * sends no other until this page answers `stroke-layer-ack`, which it does once the frame is drawn, so
 * frames arrive no faster than this page can draw them. Between frames the canvas is faded towards
 * transparent by the frame's `trail` per 1/60 s of wall time, as the madp line demo fades its canvas
 * to black once per frame. Panning or zooming clears the trails, since they are kept in screen space.
 * The layer draws through the main pane's camera.
 */
object StrokeLayer {
    private var canvas: HTMLCanvasElement? = null
    private var pending: dynamic = null
    private var scheduled = false
    private var lastDraw = 0.0
    private var lastCamera = ""

    /** Frames drawn, for tests and the backdoor (`window.strokeLayerState()`). */
    private var drawn = 0
    private var items = 0

    fun install() {
        handlers.put("stroke-layer-frame") { message, _ ->
            val text = (message.payload as? JsonPrimitive)?.content
            if (text != null) {
                pending = JSON.parse<dynamic>(text)
                schedule()
            }
            Handled.yes
        }
        window.asDynamic().strokeLayerState = {
            val state: dynamic = js("({})")
            state.framesDrawn = drawn
            state.items = items
            state.mounted = canvas != null
            state
        }
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        window.requestAnimationFrame { now ->
            scheduled = false
            val frame = pending
            pending = null
            if (frame != null) {
                draw(frame, now)
                send(Message("stroke-layer-ack"))
            }
        }
    }

    private fun ensureCanvas(): HTMLCanvasElement? {
        canvas?.let { return it }
        val viewport = document.querySelector(".field-main-viewport") as? HTMLElement ?: return null
        val c = document.createElement("canvas") as HTMLCanvasElement
        c.className = "field-stroke-layer"
        val background = viewport.querySelector(".field-webgl-background")
        if (background != null) viewport.insertBefore(c, background.nextSibling) else viewport.insertBefore(c, viewport.firstChild)
        canvas = c
        return c
    }

    private fun draw(frame: dynamic, now: Double) {
        val c = ensureCanvas() ?: return
        val dpr = window.devicePixelRatio
        val w = c.clientWidth
        val h = c.clientHeight
        val pw = (w * dpr).toInt()
        val ph = (h * dpr).toInt()
        val ctx = c.getContext("2d") as CanvasRenderingContext2D
        val pane = CanvasCamera.main
        val camera = "${pane.tx},${pane.ty},${CanvasCamera.zoom},$pw,$ph"
        var fresh = false
        if (c.width != pw || c.height != ph) {
            c.width = pw
            c.height = ph
            fresh = true
        }
        val trail = (frame.trail as? Double) ?: (frame.trail as? Number)?.toDouble() ?: 0.0
        val dt = if (lastDraw == 0.0) 1.0 / 60 else ((now - lastDraw) / 1000.0).coerceIn(0.0, 0.5)
        lastDraw = now
        ctx.asDynamic().setTransform(1, 0, 0, 1, 0, 0)
        if (fresh || camera != lastCamera || trail <= 0.0) {
            ctx.clearRect(0.0, 0.0, pw.toDouble(), ph.toDouble())
        } else {
            // Keep `trail` of the old picture per 1/60 s, fading towards the canvas underneath.
            val keep = trail.pow(dt * 60.0)
            ctx.globalCompositeOperation = "destination-out"
            ctx.fillStyle = "rgba(0,0,0,${1.0 - keep})"
            ctx.fillRect(0.0, 0.0, pw.toDouble(), ph.toDouble())
            ctx.globalCompositeOperation = "source-over"
        }
        lastCamera = camera
        val z = CanvasCamera.zoom
        ctx.asDynamic().setTransform(dpr * z, 0, 0, dpr * z, dpr * pane.tx, dpr * pane.ty)
        ctx.lineCap = org.w3c.dom.ROUND
        ctx.lineJoin = org.w3c.dom.ROUND
        val list = frame.items
        val n = (list.length as Int)
        for (i in 0 until n) drawItem(ctx, list[i])
        drawn++
        items = n
    }

    private fun path(ctx: CanvasRenderingContext2D, d: dynamic) {
        ctx.beginPath()
        var i = 0
        val n = d.length as Int
        while (i < n) {
            when (d[i] as Int) {
                0 -> { ctx.moveTo(d[i + 1], d[i + 2]); i += 3 }
                1 -> { ctx.lineTo(d[i + 1], d[i + 2]); i += 3 }
                2 -> { ctx.bezierCurveTo(d[i + 1], d[i + 2], d[i + 3], d[i + 4], d[i + 5], d[i + 6]); i += 7 }
                else -> { ctx.closePath(); i += 1 }
            }
        }
    }

    private fun drawItem(ctx: CanvasRenderingContext2D, item: dynamic) {
        val color = item.c as String
        val d = item.d
        ctx.lineWidth = item.w as Double
        if ((item.f as Int) == 1) {
            path(ctx, d)
            ctx.fillStyle = color
            ctx.fill()
        }
        val k = item.k
        if ((item.s as Int) == 1) {
            if (k == null || k == undefined) {
                path(ctx, d)
                ctx.strokeStyle = color
                ctx.stroke()
            } else {
                strokeByNode(ctx, d, k, color)
            }
        }
        val o = item.o
        if (o != null && o != undefined) {
            val ok = item.ok
            var j = 0
            val n = o.length as Int
            while (j + 3 < n) {
                ctx.beginPath()
                ctx.moveTo(o[j], o[j + 1])
                ctx.lineTo(o[j + 2], o[j + 3])
                ctx.strokeStyle = if (ok != null && ok != undefined) ok[j / 4] as String else color
                ctx.stroke()
                j += 4
            }
        }
        if ((item.p as Int) == 1) dots(ctx, d, k, item.r, (item.pr as? Number)?.toDouble() ?: 3.0, color)
    }

    /** Each segment on its own, in the colour of the node that ends it (the line's colour for a node with none). */
    private fun strokeByNode(ctx: CanvasRenderingContext2D, d: dynamic, k: dynamic, color: String) {
        var i = 0
        var node = 0
        var x = 0.0
        var y = 0.0
        var sx = 0.0
        var sy = 0.0
        val n = d.length as Int
        while (i < n) {
            val code = d[i] as Int
            val c = k[node]
            val segColor = if (c == 0 || c == null) color else c as String
            when (code) {
                0 -> { x = d[i + 1]; y = d[i + 2]; sx = x; sy = y; i += 3 }
                1 -> {
                    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(d[i + 1], d[i + 2])
                    ctx.strokeStyle = segColor; ctx.stroke()
                    x = d[i + 1]; y = d[i + 2]; i += 3
                }
                2 -> {
                    ctx.beginPath(); ctx.moveTo(x, y)
                    ctx.bezierCurveTo(d[i + 1], d[i + 2], d[i + 3], d[i + 4], d[i + 5], d[i + 6])
                    ctx.strokeStyle = segColor; ctx.stroke()
                    x = d[i + 5]; y = d[i + 6]; i += 7
                }
                else -> {
                    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(sx, sy)
                    ctx.strokeStyle = segColor; ctx.stroke()
                    x = sx; y = sy; i += 1
                }
            }
            node++
        }
    }

    private fun dots(ctx: CanvasRenderingContext2D, d: dynamic, k: dynamic, r: dynamic, radius: Double, color: String) {
        var i = 0
        var node = 0
        val n = d.length as Int
        while (i < n) {
            val code = d[i] as Int
            val size = when (code) { 2 -> 7; 3 -> 1; else -> 3 }
            if (code != 3) {
                val x: Double = d[i + size - 2]
                val y: Double = d[i + size - 1]
                val c = if (k != null && k != undefined) k[node] else null
                val rad = if (r != null && r != undefined) r[node] as Double else radius
                ctx.beginPath()
                ctx.arc(x, y, max(0.0, rad), 0.0, 2 * PI)
                ctx.fillStyle = if (c == null || c == 0) color else c as String
                ctx.fill()
            }
            i += size
            node++
        }
    }
}
