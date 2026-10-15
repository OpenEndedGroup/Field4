import afield.io.syphon.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

// other applications list this as "java — Field pattern" (or Field's app name)
val out = syphonServer("Field pattern")
val img = BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB)
var t = 0.0

loop {
    t += 1.0 / 60
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(20, 24, 40)
    g.fillRect(0, 0, 640, 360)
    g.stroke = BasicStroke(6f)
    for (i in 0 until 12) {
        val a = t + i * Math.PI / 6
        g.color = Color.getHSBColor((i / 12.0 + t * 0.05).toFloat() % 1f, 0.7f, 1f)
        val x = 320 + (cos(a) * 140).toInt()
        val y = 180 + (sin(a * 2) * 110).toInt()
        g.drawOval(x - 30, y - 30, 60, 60)
    }
    g.color = Color.WHITE
    g.drawString("Field over Syphon  frame ${out.framesPublished}", 16, 340)
    g.dispose()
    out.publish(img)
}
