// Launch it (Alt-R). 4000 points flow from a square into a heart and back. On the left
// each point heads for the partner matchPoints gave it; on the right, for the heart
// point that shares its list index.
val rnd = java.util.Random(1)
val n = 4000
val square = List(n) { vec(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1) }
val heart = mutableListOf<Vec2>()
while (heart.size < n) {
    val x = rnd.nextDouble() * 2.6 - 1.3; val y = rnd.nextDouble() * 2.6 - 1.3
    val a = x * x + y * y - 1
    if (a * a * a - x * x * y * y * y <= 0) heart += vec(x, 0.1 - y) // image y runs down
}
val partner = matchPoints(square, heart).reorder(heart)

// Each point keeps the colour of where it started in the square.
val colour = IntArray(n) {
    val c = okhsl(atan2(square[it].y, square[it].x) * 180 / PI + 360, 0.9, 0.5 + 0.25 * square[it].length() / 1.42)
    (255 shl 24) or ((c.x * 255).toInt() shl 16) or ((c.y * 255).toInt() shl 8) or (c.z * 255).toInt()
}

val imageWidth = 900; val imageHeight = 440
fun splat(px: IntArray, centre: Int, a: Vec2, b: Vec2, t: Double, c: Int) {
    val x = centre + ((a.x + (b.x - a.x) * t) * 160).toInt()
    val y = imageHeight / 2 + ((a.y + (b.y - a.y) * t) * 160).toInt()
    for (dy in 0..1) for (dx in 0..1)
        if (x + dx in 0 until imageWidth && y + dy in 0 until imageHeight) px[(y + dy) * imageWidth + x + dx] = c
}

displayOpen("morph")
loop {
    if (frameNumber % 2 == 0) {
        val t = 0.5 - 0.5 * cos(frameNumber / 70.0)
        val px = IntArray(imageWidth * imageHeight) { 0xff12161c.toInt() }
        for (i in 0 until n) {
            splat(px, imageWidth / 4, square[i], partner[i], t, colour[i])
            splat(px, 3 * imageWidth / 4, square[i], heart[i], t, colour[i])
        }
        display("morph", afield.util.PngEncoder.encode(px, imageWidth, imageHeight))
    }
}
