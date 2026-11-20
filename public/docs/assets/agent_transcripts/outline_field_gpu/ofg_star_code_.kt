// A five-pointed star in cell coordinates, closed back to its first point. Running this
// box rewrites the wall buffer while the simulation runs; the pattern grows into it.
val star = FLine()
for (k in 0..10) {
    val a = Math.PI / 2 + k * Math.PI / 5
    val r = if (k % 2 == 0) 50.0 else 22.0
    val x = 70.0 + r * Math.cos(a)
    val y = 205.0 - r * Math.sin(a)
    if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
}
addOutline(star)
println("added a star")
