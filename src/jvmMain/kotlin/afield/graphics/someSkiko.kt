package afield.graphics

import afield.Box
import afield.stdlib.*
import org.jetbrains.skia.*
import org.jetbrains.skia.svg.SVGCanvas
import java.io.ByteArrayOutputStream

fun Box.lines(vararg m: FLine, channel: String = "lines", w: Double = 1000.0, h: Double = 1000.0) {
    display(channel, drawToSVG(m.toList(), w, h))
}


fun drawToSVG(n: List<FLine>, w: Double = -1.0, h: Double = -1.0): String {
    val ba = ByteArrayOutputStream()
    val o = OutputWStream(ba)

    var we = w;
    var he = h;
    if (we == -1.0) {
        val r = n.map { it.fast2DBounds() }.reduce { a, b -> a.union(b) }
        we = r.w+r.x
        he = r.h+r.y
    }

    val s = SVGCanvas.make(Rect.makeWH(we.toFloat(), he.toFloat()), o, true, true)

    n.forEach {
        outputTo(it, s)
    }
    s.close()
    o.close()

    val hack = "xmlns=\"http://www.w3.org/2000/svg\""
    return ba.toString().replace(hack, " style='overflow:visible' " + hack)

}

fun outputTo(f: FLine, s: Canvas) {
    var p = Path()

    var first = true
    f.nodes.forEach {
        when (it) {
            is FLine.MoveTo -> p.moveTo(it.to.x.toFloat(), it.to.y.toFloat())
            is FLine.LineTo -> {
                if (first) {
                    p.moveTo(it.to.x.toFloat(), it.to.y.toFloat())
                } else
                    p.lineTo(it.to.x.toFloat(), it.to.y.toFloat())
            }

            is FLine.CubicTo -> {
                if (first) {
                    p.moveTo(it.to.x.toFloat(), it.to.y.toFloat())
                } else

                    p.cubicTo(
                        it.c1.x.toFloat(),
                        it.c1.y.toFloat(),
                        it.c2.x.toFloat(),
                        it.c2.y.toFloat(),
                        it.to.x.toFloat(),
                        it.to.y.toFloat()
                    )
            }

            is FLine.Close -> p.closePath()
        }
        first = false
    }

    val paint = Paint()
    paint.strokeWidth = f.thickness?.toFloat() ?: 1.0f
    val c = f.color
    paint.color4f = Color4f(c.x.toFloat(), c.y.toFloat(), c.z.toFloat(), c.w.toFloat())
    val mode =
        if (f.stroked && f.filled)
            PaintMode.STROKE_AND_FILL
        else
            if (f.stroked)
                PaintMode.STROKE
            else if (f.filled) PaintMode.FILL
            else null

    if (mode != null) {
        paint.mode = mode
        s.drawPath(p, paint)
    }

    fun radiusFor(f: FLine, it: FLine.Node) = it.pointSize ?: f.pointSize

    if (f.pointed) {
        var circ = Path()

        f.nodes.forEach {
            when (it) {
                is FLine.MoveTo -> circ.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                is FLine.LineTo -> circ.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                is FLine.CubicTo -> circ.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                is FLine.Close -> {}
            }
        }

        paint.color4f = Color4f(c.x.toFloat(), c.y.toFloat(), c.z.toFloat(), c.w.toFloat())
        paint.mode = PaintMode.FILL
        s.drawPath(circ, paint)
    }

    p.close()

}
