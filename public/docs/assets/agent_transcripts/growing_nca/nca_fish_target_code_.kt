import afield.media.image.util.PngEncoder
import java.io.File

/*
Draw the picture for a new rule with FLines, in the 40 x 40 square the trainer uses (pixels, y down),
and save it as a training target.
*/
val body = fline {
    moveTo(33, 20)
    cubicTo(28, 8, 14, 11, 11, 20)
    cubicTo(14, 29, 28, 32, 33, 20)
    filled = true
    color = vec(0.95, 0.5, 0.12, 1.0)
}
val tail = fline {
    moveTo(13, 20)
    lineTo(4, 12)
    lineTo(7, 20)
    lineTo(4, 28)
    close()
    filled = true
    color = vec(0.2, 0.45, 0.85, 1.0)
}
val fin = fline {
    moveTo(17, 14)
    lineTo(21, 7)
    lineTo(26, 13)
    close()
    filled = true
    color = vec(0.2, 0.45, 0.85, 1.0)
}
val eye = fline {
    circle(27.5, 18, 2.0)
    filled = true
    color = vec(0.1, 0.1, 0.15, 1.0)
}

val target = GrowingNca.targetFromFLines([tail, fin, body, eye], size = 40)
val file = File(System.getProperty("java.io.tmpdir"), "nca/fish_target.json")
GrowingNca.writeTarget(file, target, size = 40, name = "fish")
println("wrote $file")
val picture = PngEncoder.encode(GrowingNca.targetArgb(target, 40, scale = 8), 320, 320)
File(file.parentFile, "fish_target.png").writeBytes(picture)
display("main", picture)
