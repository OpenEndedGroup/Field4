/*
A wall of panels, seen plan-oblique, that you can ASK questions of.

Run this box, then click a panel. The "what you clicked" output names the
element under the pointer -- which panel, whether you hit its face, one of its
edges or a corner, and WHERE on it (u across the panel, v up it) -- in the
wall's own coordinates, with z the real distance along the wall. Hovering
highlights the panel the pointer is over. Clicking the amber ridge line names
that instead, with u along its length.

This is `Box.inspect`, which is `lines(...)` plus a hit record wide enough to
build on: see FLineIntersection and dev_docs/fline_hit_records.md.
*/

val columns = 5
val rows = 3

// u runs along the wall (0 near, 1 far), v up it. The wall recedes as u grows,
// so the panels are trapezoids on screen and z carries a real depth.
fun onWall(u: Double, v: Double): Vec3 {
    val depth = u * 220.0
    return vec(40.0 + u * 430.0 + v * 22.0, 330.0 - v * 250.0 - depth * 0.20, depth)
}

val figure = mutableListOf<FLine>()
for (row in 0 until rows) for (column in 0 until columns) {
    val u0 = column / columns.toDouble()
    val u1 = (column + 1) / columns.toDouble()
    val v0 = row / rows.toDouble()
    val v1 = (row + 1) / rows.toDouble()
    figure += fline {
        moveTo(onWall(u0, v0))
        lineTo(onWall(u1, v0))
        lineTo(onWall(u1, v1))
        lineTo(onWall(u0, v1))
        close()
        label = "panel $row,$column"
        filled = true
        stroked = true
        thickness = 1.5
        color = vec(0.13 + 0.055 * column, 0.30 + 0.03 * row, 0.44 - 0.05 * row, 0.85)
    }
}
figure += fline {
    moveTo(onWall(0.0, 1.0))
    lineTo(onWall(1.0, 1.0))
    label = "ridge"
    thickness = 4.0
    color = vec(1.0, 0.76, 0.36, 1.0)
}

/*
`inspect` sets hoverColor and onClick on every line and writes a card naming
whatever you click. The lambda is the extra thing to do with each hit -- here,
print it, so the box's text output becomes a log of what you asked the figure.
*/
inspect(figure, w = 620.0, h = 420.0, onHit = { hit -> println(hit.describe()) })
