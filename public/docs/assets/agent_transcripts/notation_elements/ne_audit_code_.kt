// Calls every part of the API once and prints what came back. Run `elements` and `score` first.
val elements = inside.boxes.values.first { it.name == "elements" }
val score = inside.boxes.values.first { it.name == "score" }
fun check(what: String, ok: Boolean, detail: Any? = "") =
    println((if (ok) "works  " else "BROKEN ") + what + "  " + detail)

check("marks", notation.marks.isNotEmpty(), notation.marks.size)
check("strokes", notation.strokes.any { it.name == "wave" }, notation.strokes.size)
check("arrows", notation.arrows.isNotEmpty(), notation.arrows.size)
check("lines", notation.lines.size >= 2, notation.lines.size)
check("texts", notation.texts.isNotEmpty(), notation.texts.size)
check("shapes", notation.shapes.size >= 6, notation.shapes.size)
check("frames", notation.frames.any { it.label == "score" }, notation.frames.map { it.label })
val wave = notation.named("wave")
check("named", wave != null)
check("get by id", wave != null && notation[wave.id] == wave)
check("arrowsFrom/To", notation.arrowsFrom(elements).isNotEmpty() && notation.arrowsTo(score).isNotEmpty())
val a = notation.arrowsFrom(elements).first()
check("arrow.fromBox/toBox", a.fromBox == elements && a.toBox == score, "${a.fromBox?.name} -> ${a.toBox?.name}")
check("selected", notation.selected.isEmpty(), "(nothing selected in a fresh page)")
check(
    "mark props",
    wave!!.points.size == 61 && wave.pressures.isNotEmpty() && wave.owner == elements,
    "${wave.kind} ${wave.bounds} stroke=${wave.stroke}",
)
check("toFLine", wave.toFLine().nodes.size == 61)
check("yAt / xAt", wave.yAt(200.0).size == 1 && notation.named("C5")!!.xAt(235.0).size == 2)
check("vertical (world)", notation.vertical(140.0).isNotEmpty(), notation.vertical(140.0).size)
check("horizontal (world)", notation.horizontal(245.0).isNotEmpty())
check("intersect segment", notation.intersect(vec(80, 350), vec(440, 350)).isNotEmpty())
check("inside(frame)", notation.inside(notation.named("score")!!).size == 3)
val grouped = notation.marks.first { it.groups.isNotEmpty() }
check("groups / grouped", notation.grouped(grouped).size == 3, grouped.groups)
val sp = notation.space("score", x = 0.0..8.0, y = 72.0..60.0)
check(
    "space(name) excludes its frame by id",
    sp.marks.none { it.kind == MarkType.FRAME } && sp.marks.any { it.label == "C5" },
)
check(
    "space events/between/sounding",
    sp.events.size == 3 && sp.sounding(1.5).isNotEmpty() && sp.between(0.0, 8.0).size == 3,
)
check("space(box)", notation.space(score, 0.0..1.0, 0.0..1.0).bounds == score.frame)

// Writing, on marks this box owns.
val mine = notation("scratch") {
    rect(100, 650, 60, 40, name = "scratch") // over this box
    text("t", 160, 760, name = "t")
}
check("over(box)", notation.over(thisBox).any { it.name == "scratch" }, notation.over(thisBox).map { it.label })
val e = notation.edit(mine[0]) {
    fill = "#ffd8a8"
    roughness = 2.0
    strokeStyle = "dotted"
    fillStyle = "zigzag"
    moveBy(10.0, 0.0)
}
check("edit", e != null && e.bounds.x == 110.0 && e.json["strokeStyle"].toString() == "\"dotted\"")
notation.named("scratch")!!.anchorTo(thisBox)
check("anchor / anchorTo", notation.named("scratch")!!.anchoredTo == thisBox)
notation.unanchor(mine[0])
check("unanchor", notation.named("scratch")!!.anchoredTo == null)
notation.delete(mine[1])
check("delete", notation.named("t") == null)
notation.clear("scratch")
check("clear(key)", notation.named("scratch") == null)
