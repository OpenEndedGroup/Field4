# Euclid's Elements

`afield.geometry.euclid` is plane geometry in the classical sense: points, lines, rays, segments,
circles and arcs, with every intersection, closest point and distance between them. On top of
that sit the propositions of Euclid's *Elements*, drawn the way Oliver Byrne printed them in 1847,
in red, yellow, blue and black.

Each proposition takes geometry in and hands back a `Construction`. Its `result` is what the
proposition asked for (a triangle, a segment, a line). Its `steps` are the numbered sentences of
the proof, each holding the circles, lines and lettered points it draws. Every point in a
construction comes from intersecting circles and straight lines, so the diagram is the working,
not an illustration of it.

The constructions below run from Book I, where everything starts from a line and two circles,
through the areas of Book II, the circle in Book III and the regular polygons of Book IV, to
proportion in Book VI.

## Writing a proposition

Every proposition on this page is written out in full in its box, with `construction`. The same
constructions are in the library as `Elements.prop1(...)`, `Elements.prop46(...)` and so on, and
a proposition that cites an earlier one calls the library's copy. Inside `construction { }`:

- `step("sentence", figures...)` adds a numbered sentence of the proof and what it draws.
- `shape.drawn(Byrne.RED)` makes a figure, with a `Role` when it is construction, result or area;
  `point.named("A")` letters a point.
- `meet(a, b)` is where two figures cross. `pick(points)` chooses one, and `pickSide(line, points,
  side)` chooses by side of a line; these are the choices `Elements.solutions` explores.
- `by { Elements.prop1(...) }` cites an earlier proposition: its steps join the proof, and its
  result comes back.
- `check(claim, "why")` states what the proposition promises, so a choice that breaks it is
  dropped.
- The last line is the result.

## Book I: the first constructions

### I.1: an equilateral triangle

The first proposition builds an equilateral triangle on a segment. Draw a circle about each end
through the other, and the triangle's third corner is where the circles cross. `side = -1` puts
that corner above the segment; `toFLines()` draws the diagram and `markdown()` writes out the
proof.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop1.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop1" embed=true alt="Byrne's figure for I.1 on cream paper: a red circle about A and a blue circle about B, each passing through the other centre. They cross at C above the black segment AB, and C is joined to A in yellow and to B in red."}
import afield.geometry.euclid.*

// prop1 written out in full; the library has the same construction as Elements.prop1(...)
fun prop1(ab: Segment2, side: Int = 1) = construction("I.1", "On a given finite straight line to construct an equilateral triangle.") {
    val (a, b) = ab
    val around = Circle2(a, a.dist(b))
    val around2 = Circle2(b, a.dist(b))
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    step("With centre A and radius AB describe the circle BCD.", around.drawn(Byrne.RED, Role.CONSTRUCTION))
    step("With centre B and radius BA describe the circle ACE.", around2.drawn(Byrne.BLUE, Role.CONSTRUCTION))
    val c = pickSide(ab.line, meet(around, around2, "I.1"), side)
    step(
        "From C, where the circles cut one another, draw CA and CB. Then CA = AB = CB, and ABC is equilateral.",
        c.named("C"),
        Segment2(c, a).drawn(Byrne.YELLOW, Role.RESULT),
        Segment2(c, b).drawn(Byrne.RED, Role.RESULT),
    )
    Triangle2(a, b, c)
}

val c = prop1(Segment2(point2(0, 0), point2(160, 0)), side = -1)
lines(c.toFLines(width = 520.0, height = 400.0), w = 520.0, h = 400.0)
markdown(c.markdown())
```

`c.result` is a `Triangle2`, and its corners are ordinary `Point2`s you can keep building on.
`c.toFLines(upTo = 2)` draws only the first two steps, which is how you step through a proof.

### I.2: moving a line to a point

To place a copy of segment BC at point A, Euclid builds the equilateral triangle of I.1 on AB,
then uses two circles, one about B and one about the triangle's apex D, to carry the length across.
The triangle comes from I.1, so its two circles are not redrawn; only its sides appear.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop2.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-elements-prop2" embed=true alt="Byrne's figure for I.2: the given blue segment BC and the point A, the dotted sides of the equilateral triangle DAB, a blue circle about B through C, a larger red circle about D, dotted productions of DB and DA, and the yellow result AL starting at A."}
import afield.geometry.euclid.*

// prop2 written out in full; the library has the same construction as Elements.prop2(...)
fun prop2(a: Point2, bc: Segment2) = construction("I.2", "To place at a given point a straight line equal to a given straight line.") {
    val (b, c) = bc
    val r = b.dist(c)
    step(
        "Let A be the given point and BC the given straight line.",
        a.named("A"),
        bc.drawn(Byrne.BLUE),
        b.named("B"),
        c.named("C"),
    )
    if (a.dist(b) <= Euclid.epsilon * (1 + r)) {
        step("A is at B already, so BC is the line required.", bc.drawn(Byrne.YELLOW, Role.RESULT))
        return@construction bc
    }
    step("Join AB.", Segment2(a, b).drawn(Byrne.BLACK, Role.CONSTRUCTION))
    val d = by { Elements.prop1(Segment2(a, b)) }.c
    step(
        "On AB construct the equilateral triangle DAB.",
        Segment2(d, a).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Segment2(d, b).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        d.named("D"),
    )
    val circleB = Circle2(b, r)
    val gs = meet(Line2.through(d, b), circleB, "I.2")
    val g = pick(gs, gs.maxBy { (it - d) dot (b - d) }) // Euclid's G is beyond B; the other works too
    step(
        "With centre B and radius BC describe the circle CGH, and produce DB to meet it at G.",
        circleB.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        Segment2(d, g).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        g.named("G"),
    )
    val circleD = Circle2(d, d.dist(g))
    val ls = meet(Line2.through(d, a), circleD, "I.2")
    val l = pick(ls, ls.maxBy { (it - d) dot (a - d) })
    step(
        "With centre D and radius DG describe the circle GKL, and produce DA to meet it at L.",
        circleD.drawn(Byrne.RED, Role.CONSTRUCTION),
        Segment2(d, l).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        l.named("L"),
    )
    val al = Segment2(a, l)
    step("DL = DG and DA = DB, so the remainder AL = BG = BC.", al.drawn(Byrne.YELLOW, Role.RESULT))
    check(eq(al.length, r), "I.2: AL is not equal to BC")
    al
}

val c = prop2(point2(0, 0), Segment2(point2(90, 40), point2(150, 100)))
lines(c.toFLines(width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

### I.3: cutting off the less from the greater

Given two unequal segments, I.3 cuts from the longer a piece as long as the shorter: copy the
shorter to the longer's end with I.2, then a circle about that end marks the cut.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop3.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-elements-prop3" embed=true alt="Byrne's figure for I.3: the long black segment AB, the short blue segment C below it, its copy AD in blue at A, a red circle about A through D, and the yellow piece AE it cuts from AB."}
import afield.geometry.euclid.*

// prop3 written out in full; the library has the same construction as Elements.prop3(...)
fun prop3(greater: Segment2, less: Segment2) = construction("I.3", "From the greater of two given straight lines to cut off a part equal to the less.") {
    require(greater.length >= less.length) { "I.3: the first line must be the greater" }
    val (a, b) = greater
    step(
        "Let AB be the greater and C the less.",
        greater.drawn(Byrne.BLACK),
        a.named("A"),
        b.named("B"),
        less.drawn(Byrne.BLUE),
    )
    val ad = by { Elements.prop2(a, less) }
    step("At A place AD equal to C.", ad.drawn(Byrne.BLUE), ad.b.named("D"))
    val circle = Circle2(a, ad.length)
    val e = pick(meet(greater, circle, "I.3"))
    val ae = Segment2(a, e)
    step(
        "With centre A and radius AD describe the circle DEF, cutting AB at E. Then AE = AD = C.",
        circle.drawn(Byrne.RED, Role.CONSTRUCTION),
        e.named("E"),
        ae.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(ae.length, less.length), "I.3: AE is not equal to C")
    ae
}

val c = prop3(Segment2(point2(0, 0), point2(200, 0)), Segment2(point2(30, 60), point2(110, 80)))
lines(c.toFLines(width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

### I.9: bisecting an angle

Mark equal distances D and E along the two arms, build the equilateral triangle of I.1 on DE on
the side away from the vertex, and the line from the vertex to its apex F halves the angle.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop9.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop9" embed=true alt="Byrne's figure for I.9: the angle BAC in black, a thin blue circle about A marking D and E on its arms, a dotted line DE, the apex F beyond it, and the yellow bisector AF."}
import afield.geometry.euclid.*

// prop9 written out in full; the library has the same construction as Elements.prop9(...)
fun prop9(vertex: Point2, b: Point2, c: Point2) = construction("I.9", "To bisect a given rectilineal angle.") {
    val a = vertex
    step(
        "Let BAC be the given angle.",
        Segment2(a, b).drawn(Byrne.BLACK),
        Segment2(a, c).drawn(Byrne.BLACK),
        a.named("A"),
        b.named("B"),
        c.named("C"),
    )
    val r = minOf(a.dist(b), a.dist(c)) / 2
    val d = pick(meet(Ray2(a, b - a), Circle2(a, r), "I.9"))
    step("Take any point D on AB.", d.named("D"))
    val circle = Circle2(a, r)
    val e = pick(meet(Ray2(a, c - a), circle, "I.9"))
    step("From AC cut off AE equal to AD (I.3).", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), e.named("E"))
    val de = Segment2(d, e)
    step("Join DE.", de.drawn(Byrne.BLACK, Role.CONSTRUCTION))
    val f = by { Elements.prop1(de, -de.line.side(a).let { if (it == 0) 1 else it }) }.c
    val af = Ray2(a, f - a)
    step(
        "On DE, on the side away from A, construct the equilateral triangle DEF, and join AF. AF bisects the angle BAC.",
        f.named("F"),
        Segment2(a, f).drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(
        eq(2 * angleAt(a, b, f), angleAt(a, b, c)) && eq(angleAt(a, b, f), angleAt(a, f, c)),
        "I.9: AF does not halve the angle",
    )
    af
}

val c = prop9(point2(0, 0), point2(200, -20), point2(80, -160))
lines(c.toFLines(width = 520.0, height = 400.0), w = 520.0, h = 400.0)
markdown(c.markdown())
```

### I.10: bisecting a segment

I.10 is built from the two propositions before it: the equilateral triangle on AB (I.1), then the
bisector of the angle at its apex (I.9), which meets AB at its midpoint D.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop10.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-elements-prop10" embed=true alt="Byrne's figure for I.10: segment AB, the dotted sides of the equilateral triangle ABC below it, the red bisector from C down to D, and the two equal halves AD in yellow and DB in blue."}
import afield.geometry.euclid.*

// prop10 written out in full; the library has the same construction as Elements.prop10(...)
fun prop10(ab: Segment2) = construction("I.10", "To bisect a given finite straight line.") {
    val (a, b) = ab
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val c = by { Elements.prop1(ab) }.c
    step(
        "On AB construct the equilateral triangle ABC.",
        Segment2(c, a).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Segment2(c, b).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        c.named("C"),
    )
    val bisector = by { Elements.prop9(c, a, b) }
    val d = pick(meet(bisector, ab, "I.10"))
    step(
        "Bisect the angle ACB by CD, meeting AB at D. Then AD = DB.",
        Segment2(c, d).drawn(Byrne.RED),
        d.named("D"),
        Segment2(a, d).drawn(Byrne.YELLOW, Role.RESULT),
        Segment2(d, b).drawn(Byrne.BLUE, Role.RESULT),
    )
    check(eq(a.dist(d), d.dist(b)), "I.10: D is not the midpoint")
    d
}

val c = prop10(Segment2(point2(0, 0), point2(180, 0)))
lines(c.toFLines(width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

### I.11 and I.12: perpendiculars

I.11 raises a perpendicular from a point C on a line: mark D and E equally far from C on either
side, and the apex of the equilateral triangle on DE stands straight above C.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="720" padding="14" timeout="90" name="euclid-elements-prop11" embed=true alt="Byrne's figure for I.11: the black line AB with C on it, a thin blue circle about C marking D and E, the apex F above, and the yellow perpendicular CF."}
import afield.geometry.euclid.*

// prop11 written out in full; the library has the same construction as Elements.prop11(...)
fun prop11(ab: Segment2, c: Point2, side: Int = 1) = construction("I.11", "To draw a straight line at right angles to a given straight line from a given point on it.") {
    val line = ab.line
    step(
        "Let AB be the given straight line and C the given point on it.",
        ab.drawn(Byrne.BLACK),
        ab.a.named("A"),
        ab.b.named("B"),
        c.named("C"),
    )
    val circle = Circle2(c, ab.length / 4)
    val (d, e) = meet(line, circle, "I.11").sortedBy { line.direction dot it }
    step(
        "Take any point D on AC, and make CE equal to CD (I.3).",
        circle.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        d.named("D"),
        e.named("E"),
    )
    val f = by { Elements.prop1(Segment2(d, e), side) }.c
    val cf = Ray2(c, f - c)
    step(
        "On DE construct the equilateral triangle FDE, and join FC. FC is at right angles to AB.",
        f.named("F"),
        Segment2(c, f).drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(cf.direction.normalized() dot line.direction.normalized(), 0.0), "I.11: CF is not at right angles")
    cf
}

val c = prop11(Segment2(point2(0, 0), point2(200, 0)), point2(80, 0), side = -1)
lines(c.toFLines(width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

I.12 drops a perpendicular from a point C off the line: a circle about C, large enough to reach
past the line, cuts it at G and E, and the midpoint of GE (by I.10) is the foot H.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop12.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop12" embed=true alt="Byrne's figure for I.12: a black line, the point C above it, a point D below, a thin blue circle about C through D cutting the line at G and E, and the yellow perpendicular CH to the midpoint H."}
import afield.geometry.euclid.*

// prop12 written out in full; the library has the same construction as Elements.prop12(...)
fun prop12(ab: Line2, c: Point2) = construction(
    "I.12",
    "To draw a straight line perpendicular to a given infinite straight line from a given point not on it.",
) {
    require(ab.side(c) != 0) { "I.12: the point is on the line" }
    step("Let AB be the given straight line and C the given point not on it.", ab.drawn(Byrne.BLACK), c.named("C"))
    val d = ab.point + (ab.point - c) * 0.3
    step("Take any point D on the other side of AB.", d.named("D"))
    val circle = Circle2(c, c.dist(d))
    val (g, e) = meet(ab, circle, "I.12").sortedBy { ab.direction dot it }
    step(
        "With centre C and radius CD describe the circle EFG, cutting AB at G and E.",
        circle.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        g.named("G"),
        e.named("E"),
    )
    val h = by { Elements.prop10(Segment2(g, e)) }
    val ch = Segment2(c, h)
    step(
        "Bisect GE at H, and join CH. CH is perpendicular to AB.",
        h.named("H"),
        ch.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq((ch.b - ch.a).normalized() dot ab.direction.normalized(), 0.0), "I.12: CH is not perpendicular")
    ch
}

val c = prop12(Line2(point2(0, 0), point2(1, 0)), point2(60, -120))
lines(c.toFLines(extent = 220.0, width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

### I.23: copying an angle

I.23 puts a copy of the angle DCE at the start A of a line. Mark D and E equally far from C,
mark AF that far along the line, and a circle about F with radius DE finds G. The triangles AFG and
CDE have equal sides, so their angles at A and C are equal. Euclid builds that triangle with I.22;
here two circles do the same job.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop23.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop23" embed=true alt="Byrne's figure for I.23: on the left the given angle DCE in black with its red chord DE; on the right the black ray from A, a thin blue circle about A marking F, a thin red circle about F, and the yellow arm AG making the same angle with the ray."}
import afield.geometry.euclid.*

// prop23 written out in full; the library has the same construction as Elements.prop23(...)
fun prop23(along: Ray2, vertex: Point2, d: Point2, e: Point2, side: Int = 1) = construction(
    "I.23",
    "On a given straight line and at a point on it to construct a rectilineal angle equal to a given rectilineal angle.",
) {
    val a = along.origin
    step(
        "Let AB be the given line, A the point on it, and DCE the given angle.",
        along.drawn(Byrne.BLACK),
        a.named("A"),
        Segment2(vertex, d).drawn(Byrne.BLACK),
        Segment2(vertex, e).drawn(Byrne.BLACK),
        vertex.named("C"),
    )
    val r = minOf(vertex.dist(d), vertex.dist(e))
    val gauge = Circle2(vertex, r)
    val d1 = pick(meet(Ray2(vertex, d - vertex), gauge, "I.23"))
    val e1 = pick(meet(Ray2(vertex, e - vertex), gauge, "I.23"))
    step(
        "Cut CD and CE at D and E equally far from C, and join DE.",
        gauge.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        d1.named("D"),
        e1.named("E"),
        Segment2(d1, e1).drawn(Byrne.RED),
    )
    val copy = Circle2(a, r)
    val f = pick(meet(along, copy, "I.23"))
    step("On AB make AF equal to CD.", copy.drawn(Byrne.BLUE, Role.CONSTRUCTION), f.named("F"))
    val chord = Circle2(f, d1.dist(e1))
    val g = pickSide(along.line, meet(copy, chord, "I.23"), side)
    val ag = Ray2(a, g - a)
    step(
        "With centre F and radius DE cut the circle at G, and join AG. The triangles AFG and CDE have their sides equal, so the angle FAG equals DCE.",
        chord.drawn(Byrne.RED, Role.CONSTRUCTION),
        g.named("G"),
        Segment2(f, g).drawn(Byrne.RED),
        Segment2(a, g).drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(angleAt(a, a + along.direction, g), angleAt(vertex, d, e)), "I.23: the angles differ")
    ag
}

val c = prop23(
    along = Ray2(point2(0, 0), point2(1, 0)),
    vertex = point2(-200, 40),
    d = point2(-100, 40),
    e = point2(-150, -40),
    side = -1,
)
lines(c.toFLines(extent = 140.0, width = 560.0, height = 400.0), w = 560.0, h = 400.0)
markdown(c.markdown())
```

### I.31: a parallel through a point

Join A to any point D of the line, then copy the angle the join makes with the line (I.23) to the
other side of the join at A. Equal alternate angles make the new line parallel.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop31.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-elements-prop31" embed=true alt="Byrne's figure for I.31: the black segment BC, the point A above it, a dotted join from A to D on BC, and the yellow line through A parallel to BC."}
import afield.geometry.euclid.*

// prop31 written out in full; the library has the same construction as Elements.prop31(...)
fun prop31(a: Point2, bc: Segment2) = construction("I.31", "Through a given point to draw a straight line parallel to a given straight line.") {
    require(bc.line.side(a) != 0) { "I.31: the point is on the line" }
    step(
        "Let A be the given point and BC the given straight line.",
        a.named("A"),
        bc.drawn(Byrne.BLACK),
        bc.a.named("B"),
        bc.b.named("C"),
    )
    val d = bc.midpoint
    step("Take any point D on BC, and join AD.", d.named("D"), Segment2(a, d).drawn(Byrne.BLACK, Role.CONSTRUCTION))
    val ad = Line2.through(a, d)
    val arm = by { Elements.prop23(Ray2(a, d - a), d, a, bc.b, -ad.side(bc.b)) }
    val e = arm.at(1.0)
    val parallel = Line2(a, arm.direction)
    step(
        "At A on DA make the angle DAE equal to the alternate angle ADC, and produce EA to F. EF is parallel to BC.",
        e.named("E"),
        parallel.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(parallel.direction.normalized() cross bc.direction.normalized(), 0.0), "I.31: EF is not parallel")
    parallel
}

val c = prop31(point2(60, -90), Segment2(point2(0, 0), point2(200, 0)))
lines(c.toFLines(extent = 160.0, width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

### I.46: a square on a segment

The last construction of Book I gathers the others: a perpendicular at A (I.11), cut to the
length of AB, then a parallel to each side (I.31) through the far corners, closing the square.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop46.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop46" embed=true alt="Byrne's figure for I.46: segment AB, a thin dotted perpendicular rising from A, a thin blue circle about A through B, the red side AD, and the square closed by the blue side DE and the yellow side BE."}
import afield.geometry.euclid.*

// prop46 written out in full; the library has the same construction as Elements.prop46(...)
fun prop46(ab: Segment2, side: Int = 1) = construction("I.46", "On a given straight line to describe a square.") {
    val (a, b) = ab
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val up = by { Elements.prop11(ab, a, side) }
    val circle = Circle2(a, ab.length)
    val d = pick(meet(up, circle, "I.46"))
    step(
        "Draw AC at right angles to AB from A, and cut off AD equal to AB.",
        up.drawn(Byrne.BLACK, Role.CONSTRUCTION),
        circle.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        d.named("D"),
        Segment2(a, d).drawn(Byrne.RED),
    )
    val de = by { Elements.prop31(d, ab) }
    val be = by { Elements.prop31(b, Segment2(a, d)) }
    val e = pick(meet(de, be, "I.46"))
    val square = Quadrilateral2(a, b, e, d)
    step(
        "Through D draw DE parallel to AB, and through B draw BE parallel to AD. ADEB is a square.",
        e.named("E"),
        Segment2(d, e).drawn(Byrne.BLUE, Role.RESULT),
        Segment2(b, e).drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(isSquare(square), "I.46: ADEB is not a square")
    square
}

val c = prop46(Segment2(point2(0, 0), point2(120, 0)), side = -1)
lines(c.toFLines(extent = 160.0, width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

### I.47: the squares on a right triangle

Book I ends with Pythagoras. Squares go on all three sides of a right triangle (I.46), and a
parallel through the right angle (I.31) splits the square on the long side into two rectangles.
Each rectangle equals one of the other squares, which Byrne shows by colour: red equals red, blue
equals blue.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop47.png" wait="3" capture="box" open-outputs="true" w="1000" h="900" padding="14" timeout="90" name="euclid-elements-prop47" embed=true alt="Byrne's figure for I.47: a right triangle ABC with squares on its three sides. The square on BA is filled red and the square on AC blue. The large square on BC, outlined in yellow, is split by the line AL into a red rectangle under B and a blue rectangle under C, with the dotted joins AD and FC crossing it."}
import afield.geometry.euclid.*

// prop47 written out in full; the library has the same construction as Elements.prop47(...)
fun prop47(t: Triangle2) = construction(
    "I.47",
    "In right-angled triangles the square on the side opposite the right angle equals the squares on the sides containing the right angle.",
) {
    val (a, b, c) = t
    step(
        "Let ABC be a right-angled triangle with the right angle at A.",
        *t.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        a.named("A"),
        b.named("B"),
        c.named("C"),
    )
    val onBC = by { Elements.prop46(Segment2(b, c), -Line2.through(b, c).side(a)) }
    val onBA = by { Elements.prop46(Segment2(b, a), -Line2.through(b, a).side(c)) }
    val onAC = by { Elements.prop46(Segment2(a, c), -Line2.through(a, c).side(b)) }
    val d = onBC.d
    val e = onBC.c
    val f = onBA.d
    val g = onBA.c
    val h = onAC.d
    val k = onAC.c
    step(
        "On BC, BA and AC describe the squares BDEC, BAGF and ACKH (I.46).",
        onBA.polygon.drawn(Byrne.RED, Role.AREA),
        onAC.polygon.drawn(Byrne.BLUE, Role.AREA),
        *(onBC.sides + onBA.sides + onAC.sides).map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        d.named("D"),
        e.named("E"),
        f.named("F"),
        g.named("G"),
        h.named("H"),
        k.named("K"),
    )
    val al = by { Elements.prop31(a, Segment2(b, d)) }
    val l = pick(meet(al, Segment2(d, e), "I.47"))
    val m = pick(meet(al, Segment2(b, c), "I.47"))
    step(
        "Through A draw AL parallel to BD, cutting BC at M and DE at L.",
        Segment2(a, l).drawn(Byrne.BLACK),
        l.named("L"),
        m.named("M"),
    )
    step(
        "Join AD and FC. The triangles ABD and FBC are equal, and each is half of the rectangle BL and of the square on BA, so these are equal; in the same way CL equals the square on AC.",
        Segment2(a, d).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Segment2(f, c).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Polygon2(b, d, l, m).drawn(Byrne.RED, Role.AREA),
        Polygon2(m, l, e, c).drawn(Byrne.BLUE, Role.AREA),
    )
    step(
        "So the whole square on BC equals the squares on BA and AC.",
        *onBC.sides.map { it.drawn(Byrne.YELLOW, Role.RESULT) }.toTypedArray(),
    )
    check([onBC, onBA, onAC].none { centroid(a, b, c) in it.polygon }, "I.47: a square lies over the triangle")
    Triple(onBC, onBA, onAC)
}

val c = prop47(Triangle2(point2(0, 0), point2(120, 0), point2(0, -90)))
lines(c.toFLines(width = 560.0, height = 560.0), w = 560.0, h = 560.0)
markdown(c.markdown())
```

## Book II: areas and gnomons

Book II is about rectangles and squares. Byrne fills them with colour, so the proof can be read off
the picture: regions of the same colour have the same area.

### II.5 and II.6: the gnomons

A gnomon is the L-shaped region left when a smaller square is taken from the corner of a larger
one. II.5 cuts a line AB at its midpoint C and at another point D, and shows that the rectangle
AD·DB equals the gnomon in the square on CB. Adding the small square on CD to both gives the
proposition. The two red pieces are equal, and each side of the equation is red plus blue.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book2prop5.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book2prop5" embed=true alt="Byrne's figure for II.5: the line AB with the square CEFB on its right half. A red rectangle on AC and a red rectangle on DB are equal; a blue rectangle CDHL sits between them; a yellow square LHGE fills the corner of the large square, which is cut by the dotted diagonal BE."}
import afield.geometry.euclid.*

// book2prop5 written out in full; the library has the same construction as Elements.book2prop5(...)
fun book2prop5(ab: Segment2, side: Int = 1) = construction(
    "II.5",
    "If a straight line be cut into equal and unequal segments, the rectangle contained by the unequal segments, together with the square on the straight line between the points of section, equals the square on the half.",
) {
    val (a, b) = ab
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val c = by { Elements.prop10(ab) }
    val d = c.lerp(b, 0.4)
    step("Bisect AB at C, and take any point D between C and B.", c.named("C"), d.named("D"))
    val sq = by { Elements.prop46(Segment2(c, b), side) }
    val f = sq.c
    val e = sq.d
    step(
        "On CB describe the square CEFB, and join BE.",
        *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        Segment2(b, e).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        e.named("E"),
        f.named("F"),
    )
    val dg = by { Elements.prop31(d, Segment2(c, e)) }
    val g = pick(meet(dg, Segment2(e, f), "II.5"))
    val h = pick(meet(dg, Segment2(b, e), "II.5"))
    step(
        "Through D draw DG parallel to CE, cutting BE at H.",
        Segment2(d, g).drawn(Byrne.BLACK),
        g.named("G"),
        h.named("H"),
    )
    val km = by { Elements.prop31(h, ab) }
    val l = pick(meet(km, Line2.through(c, e), "II.5"))
    val m = pick(meet(km, Line2.through(b, f), "II.5"))
    val k = pick(meet(by { Elements.prop31(a, Segment2(c, l)) }, km, "II.5"))
    step(
        "Through H draw KLM parallel to AB, and through A draw AK parallel to CL.",
        Segment2(k, m).drawn(Byrne.BLACK),
        Segment2(a, k).drawn(Byrne.BLACK),
        k.named("K"),
        l.named("L"),
        m.named("M"),
    )
    val gnomon = Polygon2(c, b, f, g, h, l)
    step(
        "AL equals DF, so the rectangle AH, which is AD·DB, equals the gnomon CBFGHL. Add to each the square LG on CD: the rectangle AD·DB with the square on CD equals the square on CB.",
        Polygon2(a, c, l, k).drawn(Byrne.RED, Role.AREA),
        Polygon2(d, b, f, g).drawn(Byrne.RED, Role.AREA),
        Polygon2(c, d, h, l).drawn(Byrne.BLUE, Role.AREA),
        Polygon2(l, h, g, e).drawn(Byrne.YELLOW, Role.AREA),
    )
    check(eq(gnomon.area, a.dist(d) * d.dist(b)), "II.5: the gnomon is not AD·DB")
    gnomon
}

val c = book2prop5(Segment2(point2(0, 0), point2(200, 0)), side = -1)
lines(c.toFLines(width = 560.0, height = 380.0), w = 560.0, h = 380.0)
markdown(c.markdown())
```

II.6 is the same figure with D beyond the end of the line instead of inside it: the rectangle
AD·DB with the square on half the line equals the square on CD.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book2prop6.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book2prop6" embed=true alt="Byrne's figure for II.6: the line AB produced to D, with the square CEFD on CD. A red rectangle on AC and a red rectangle HMFG are equal; the blue rectangle CDML runs under the yellow square LHGE, and the dotted diagonal DE crosses the square."}
import afield.geometry.euclid.*

// book2prop6 written out in full; the library has the same construction as Elements.book2prop6(...)
fun book2prop6(ab: Segment2, side: Int = 1) = construction(
    "II.6",
    "If a straight line be bisected and a straight line be added to it, the rectangle contained by the whole with the added straight line and the added straight line, together with the square on the half, equals the square on the straight line made up of the half and the added straight line.",
) {
    val (a, b) = ab
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val c = by { Elements.prop10(ab) }
    val d = pick(meet(Ray2(b, b - a), Circle2(b, ab.length * 0.35), "II.6"))
    step(
        "Bisect AB at C, and produce it to any point D.",
        Segment2(b, d).drawn(Byrne.BLACK),
        c.named("C"),
        d.named("D"),
    )
    val sq = by { Elements.prop46(Segment2(c, d), side) }
    val f = sq.c
    val e = sq.d
    step(
        "On CD describe the square CEFD, and join DE.",
        *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        Segment2(d, e).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        e.named("E"),
        f.named("F"),
    )
    val bg = by { Elements.prop31(b, Segment2(c, e)) }
    val g = pick(meet(bg, Segment2(e, f), "II.6"))
    val h = pick(meet(bg, Segment2(d, e), "II.6"))
    step(
        "Through B draw BG parallel to CE, cutting DE at H.",
        Segment2(b, g).drawn(Byrne.BLACK),
        g.named("G"),
        h.named("H"),
    )
    val km = by { Elements.prop31(h, ab) }
    val l = pick(meet(km, Line2.through(c, e), "II.6"))
    val m = pick(meet(km, Line2.through(d, f), "II.6"))
    val k = pick(meet(by { Elements.prop31(a, Segment2(c, l)) }, km, "II.6"))
    step(
        "Through H draw KLM parallel to AB, and through A draw AK parallel to CL.",
        Segment2(k, m).drawn(Byrne.BLACK),
        Segment2(a, k).drawn(Byrne.BLACK),
        k.named("K"),
        l.named("L"),
        m.named("M"),
    )
    val gnomon = Polygon2(c, d, f, g, h, l)
    step(
        "AL equals HF, so the rectangle AM, which is AD·DB, equals the gnomon CDFGHL. Add to each the square LG on CB: the rectangle AD·DB with the square on CB equals the square on CD.",
        Polygon2(a, c, l, k).drawn(Byrne.RED, Role.AREA),
        Polygon2(h, m, f, g).drawn(Byrne.RED, Role.AREA),
        Polygon2(c, d, m, l).drawn(Byrne.BLUE, Role.AREA),
        Polygon2(l, h, g, e).drawn(Byrne.YELLOW, Role.AREA),
    )
    check(eq(gnomon.area, a.dist(d) * d.dist(b)), "II.6: the gnomon is not AD·DB")
    gnomon
}

val c = book2prop6(Segment2(point2(0, 0), point2(160, 0)), side = -1)
lines(c.toFLines(width = 560.0, height = 380.0), w = 560.0, h = 380.0)
markdown(c.markdown())
```

### II.11: the golden section

II.11 cuts a line AB at H so that the square on AH equals the rectangle AB·BH. That is the golden
section: AB is to AH as AH is to HB, about 1.618 each time. The yellow square and the red rectangle
have equal areas.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book2prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="900" padding="14" timeout="90" name="euclid-later-book2prop11" embed=true alt="Byrne's figure for II.11: the square ABDC on AB, the midpoint E of AC, a thin blue circle about E through B reaching F below A, the yellow square AFGH on AF, and the red rectangle HBDK, equal to the yellow square."}
import afield.geometry.euclid.*

// book2prop11 written out in full; the library has the same construction as Elements.book2prop11(...)
fun book2prop11(ab: Segment2, side: Int = 1) = construction(
    "II.11",
    "To cut a given straight line so that the rectangle contained by the whole and one of the segments equals the square on the remaining segment.",
) {
    val (a, b) = ab
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val sq = by { Elements.prop46(ab, side) }
    val c = sq.d
    val d = sq.c
    step(
        "On AB describe the square ABDC.",
        *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        c.named("C"),
        d.named("D"),
    )
    val e = by { Elements.prop10(Segment2(a, c)) }
    step("Bisect AC at E, and join BE.", e.named("E"), Segment2(b, e).drawn(Byrne.BLACK, Role.CONSTRUCTION))
    val circle = Circle2(e, e.dist(b))
    val f = pick(meet(Ray2(a, a - c), circle, "II.11"))
    step(
        "Produce CA to F, making EF equal to EB.",
        circle.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        Segment2(a, f).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        f.named("F"),
    )
    val sqF = by { Elements.prop46(Segment2(a, f), Line2.through(a, f).side(b)) }
    val g = sqF.c
    val h = sqF.d
    val k = pick(meet(Line2.through(g, h), Line2.through(c, d), "II.11"))
    step(
        "On AF describe the square FGHA, and produce GH to K. AB is cut at H so that the rectangle AB·BH, which is HD, equals the square AG on AH.",
        sqF.polygon.drawn(Byrne.YELLOW, Role.AREA),
        Polygon2(h, b, d, k).drawn(Byrne.RED, Role.AREA),
        Segment2(g, k).drawn(Byrne.BLACK),
        *sqF.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        g.named("G"),
        h.named("H"),
        k.named("K"),
        Segment2(a, h).drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(
        eq(a.dist(h) * a.dist(h), ab.length * h.dist(b)) && eq(a.dist(h) + h.dist(b), ab.length),
        "II.11: H is not the section",
    )
    h
}

val c = book2prop11(Segment2(point2(0, 0), point2(160, 0)), side = -1)
lines(c.toFLines(width = 520.0, height = 520.0), w = 520.0, h = 520.0)
markdown(c.markdown())
```

`c.result` is the point H. For AB of length 160 it is 98.9 from A, which is 160 divided by the
golden ratio.

### II.14: squaring a rectangle

II.14 makes a square with the same area as any straight-sided figure. Euclid first turns the
figure into a rectangle (I.45); `book2prop14` starts from that rectangle. Extend one side by the
other, draw a semicircle on the whole, and the perpendicular where the sides meet is the side of
the square.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book2prop14.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book2prop14" embed=true alt="Byrne's figure for II.14: a red rectangle BEDC, its side BE produced to F, a black semicircle on BF below it, the dotted line from E down to H on the semicircle, and the yellow square on EH, equal in area to the red rectangle."}
import afield.geometry.euclid.*
import kotlin.math.PI

// book2prop14 written out in full; the library has the same construction as Elements.book2prop14(...)
fun book2prop14(rect: Quadrilateral2) = construction("II.14", "To construct a square equal to a given rectilineal figure.") {
    val b = rect.a
    val e = rect.b
    val d = rect.c
    val c = rect.d
    step(
        "Let BEDC be the rectangle equal to the given rectilineal figure (I.45).",
        rect.polygon.drawn(Byrne.RED, Role.AREA),
        *rect.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        b.named("B"),
        e.named("E"),
        d.named("D"),
        c.named("C"),
    )
    val circle = Circle2(e, e.dist(d))
    val f = pick(meet(Ray2(e, e - b), circle, "II.14"))
    step(
        "Produce BE to F, making EF equal to ED.",
        circle.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        Segment2(e, f).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        f.named("F"),
    )
    val g = by { Elements.prop10(Segment2(b, f)) }
    step("Bisect BF at G.", g.named("G"))
    val bf = Line2.through(b, f)
    val up = Arc2(g, g.dist(f), (f - g).angle, PI)
    val semi = if (bf.side(up.midPoint) == bf.side(d)) Arc2(g, g.dist(f), (f - g).angle, -PI) else up
    step("With centre G and radius GF describe the semicircle BHF.", semi.drawn(Byrne.BLACK))
    val h = pick(meet(Ray2(e, e - d), semi, "II.14"))
    step(
        "Produce DE to meet the semicircle at H.",
        Segment2(e, h).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        h.named("H"),
    )
    val sq = by { Elements.prop46(Segment2(e, h), -Line2.through(e, h).side(b)) }
    step(
        "The square on EH equals the rectangle BD, and so the given figure.",
        sq.polygon.drawn(Byrne.YELLOW, Role.AREA),
        *sq.sides.map { it.drawn(Byrne.YELLOW, Role.RESULT) }.toTypedArray(),
    )
    check(isSquare(sq) && eq(sq.polygon.area, rect.polygon.area), "II.14: the square is not equal to the rectangle")
    sq
}

val rectangle = Quadrilateral2(point2(0, 0), point2(180, 0), point2(180, -80), point2(0, -80))
val c = book2prop14(rectangle)
lines(c.toFLines(width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

## Book III: a tangent from a point

III.17 draws the tangent to a circle from a point A outside it. A second circle about the centre E
reaches out to A; a perpendicular where AE crosses the first circle meets the second at F; and the
line from E to F crosses the first circle at the point of contact B. The triangles EAB and EFD are
equal, so the angle at B is a right angle.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book3prop17.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book3prop17" embed=true alt="Byrne's figure for III.17: a black circle about E, the outside point A, a larger thin blue circle about E through A, the red perpendicular DF, the dotted lines AE and EF, and the yellow tangent AB touching the circle at B."}
import afield.geometry.euclid.*

// book3prop17 written out in full; the library has the same construction as Elements.book3prop17(...)
fun book3prop17(circle: Circle2, a: Point2) = construction("III.17", "From a given point to draw a straight line touching a given circle.") {
    val e = circle.center
    require(a !in circle) { "III.17: the point is inside the circle" }
    step(
        "Let BCD be the given circle, E its centre, and A the given point.",
        circle.drawn(Byrne.BLACK),
        e.named("E"),
        a.named("A"),
    )
    val d = pick(meet(Segment2(a, e), circle, "III.17"))
    step("Join AE, cutting the circle at D.", Segment2(a, e).drawn(Byrne.BLACK, Role.CONSTRUCTION), d.named("D"))
    val big = Circle2(e, e.dist(a))
    step("With centre E and radius EA describe the circle AFG.", big.drawn(Byrne.BLUE, Role.CONSTRUCTION))
    val up = by { Elements.prop11(Segment2(e, a), d) }
    val f = pick(meet(up, big, "III.17"))
    step(
        "From D draw DF at right angles to EA, meeting that circle at F.",
        Segment2(d, f).drawn(Byrne.RED),
        f.named("F"),
    )
    val b = pick(meet(Segment2(e, f), circle, "III.17"))
    val ab = Segment2(a, b)
    step(
        "Join EF, cutting the first circle at B, and join AB. The triangles EAB and EFD are equal, so the angle at B is right, and AB touches the circle.",
        Segment2(e, f).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        b.named("B"),
        ab.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq((b - e).normalized() dot (a - b).normalized(), 0.0), "III.17: AB does not touch the circle")
    ab
}

val c = book3prop17(Circle2(point2(0, 0), 60.0), point2(170, 40))
lines(c.toFLines(width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

## Book IV: regular polygons

Book IV puts figures inside and around circles: the triangle's incircle and circumcircle (IV.4 and
IV.5), then the square, the pentagon, the hexagon and the fifteen-sided figure.

### IV.10: the golden triangle

The pentagon needs a triangle whose base angles are each twice its apex angle (72°, 72° and 36°).
IV.10 makes one from the golden section of II.11: a circle about A through B, and a chord BD in it
equal to the larger piece AC.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book4prop10.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book4prop10" embed=true alt="Byrne's figure for IV.10: the black line AB with its golden section at C marked in red, a thin blue circle about A through B, a thin red circle about B fitting the chord BD, the dotted line DC, and the golden triangle ABD with AD in yellow and BD in red."}
import afield.geometry.euclid.*

// book4prop10 written out in full; the library has the same construction as Elements.book4prop10(...)
fun book4prop10(ab: Segment2) = construction(
    "IV.10",
    "To construct an isosceles triangle having each of the angles at the base double the remaining one.",
) {
    val (a, b) = ab
    step("Let AB be any straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val c = by { Elements.book2prop11(ab) }
    step(
        "Cut AB at C so that the rectangle AB·BC equals the square on AC (II.11).",
        c.named("C"),
        Segment2(a, c).drawn(Byrne.RED),
    )
    val circle = Circle2(a, ab.length)
    step("With centre A and radius AB describe the circle BDE.", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION))
    val chord = Circle2(b, a.dist(c))
    val d = pick(meet(circle, chord, "IV.10"))
    step(
        "In it fit BD equal to AC (IV.1), and join AD and DC. The angles at B and D are each double the angle at A.",
        chord.drawn(Byrne.RED, Role.CONSTRUCTION),
        d.named("D"),
        Segment2(d, c).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Segment2(a, d).drawn(Byrne.YELLOW, Role.RESULT),
        Segment2(b, d).drawn(Byrne.RED, Role.RESULT),
    )
    check(eq(angleAt(b, a, d), 2 * angleAt(a, b, d)), "IV.10: the base angles are not double")
    Triangle2(a, b, d)
}

val c = book4prop10(Segment2(point2(0, -150), point2(0, 0)))
lines(c.toFLines(width = 520.0, height = 480.0), w = 520.0, h = 480.0)
markdown(c.markdown())
```

### IV.11: the pentagon

To put a regular pentagon in a circle, inscribe a triangle with the golden triangle's angles, then
bisect its two base angles. The bisectors cut the circle at the two remaining corners. The triangle
goes in by drawing the tangent at A and copying the golden triangle's base angles against it
(I.23); the angle between a tangent and a chord equals the angle on the far side of the circle
(III.32), which makes the triangle's angles come out right.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book4prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="780" padding="14" timeout="90" name="euclid-later-book4prop11" embed=true alt="Byrne's figure for IV.11: on the left the golden triangle FGH; on the right a black circle with the dotted tangent KAL at the top, the red sides AC and AD and blue base CD of the inscribed triangle, the dotted bisectors CE and DB, and the yellow regular pentagon ABCDE."}
import afield.geometry.euclid.*
import kotlin.math.PI

// book4prop11 written out in full; the library has the same construction as Elements.book4prop11(...)
fun book4prop11(circle: Circle2, at: Double = -PI / 2) = construction("IV.11", "In a given circle to inscribe an equilateral and equiangular pentagon.") {
    val o = circle.center
    val r = circle.radius
    val a = circle.at(at)
    step("Let ABCDE be the given circle.", circle.drawn(Byrne.BLACK), a.named("A"))
    val gt = by { Elements.book4prop10(Segment2(o + Point2(-2.5 * r, -0.95 * r), o + Point2(-2.5 * r, 0.95 * r))) }
    val (f, g, h) = gt
    step(
        "Set out the triangle FGH, with each angle at G and H double the angle at F (IV.10).",
        *gt.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        f.named("F"),
        g.named("G"),
        h.named("H"),
    )
    val touch = by { Elements.prop11(Segment2(o, a), a) }
    val tangent = Line2(a, touch.direction)
    val k = a + touch.direction.normalized() * r
    val l = a - touch.direction.normalized() * r
    step(
        "Draw KAL touching the circle at A.",
        Segment2(k, l).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        k.named("K"),
        l.named("L"),
    )
    val toC = by { Elements.prop23(Ray2(a, l - a), g, f, h, Line2(a, l - a).side(o)) }
    val toD = by { Elements.prop23(Ray2(a, k - a), h, f, g, Line2(a, k - a).side(o)) }
    val c = far(meet(toC, circle, "IV.11"), a)
    val d = far(meet(toD, circle, "IV.11"), a)
    step(
        "Make the angle LAC equal to the angle at G, and KAD equal to the angle at H (I.23), and join CD. The triangle ACD has the angles of FGH (III.32).",
        Segment2(a, c).drawn(Byrne.RED),
        Segment2(a, d).drawn(Byrne.RED),
        Segment2(c, d).drawn(Byrne.BLUE),
        c.named("C"),
        d.named("D"),
    )
    val e = far(meet(by { Elements.prop9(c, a, d) }, circle, "IV.11"), c)
    val b = far(meet(by { Elements.prop9(d, a, c) }, circle, "IV.11"), d)
    val pentagon = aroundCentre(o, at, [a, b, c, d, e])
    step(
        "Bisect the angles ACD and CDA by CE and DB (I.9), and join AB, BC, DE, EA. The five arcs are equal, and ABCDE is the regular pentagon.",
        Segment2(c, e).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Segment2(d, b).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        b.named("B"),
        e.named("E"),
        pentagon.drawn(Byrne.YELLOW, Role.RESULT),
    )
    require(tangent.side(o) != 0)
    check(isRegular(pentagon, 5), "IV.11: ABCDE is not regular")
    pentagon
}

val c = book4prop11(Circle2(point2(0, 0), 100.0))
lines(c.toFLines(width = 620.0, height = 400.0), w = 620.0, h = 400.0)
markdown(c.markdown())
```

### IV.15: the hexagon

The hexagon needs only one circle beyond the given one: drawn about one end of a diameter, through
the centre, it cuts off two corners, and lines from them through the centre give two more.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book4prop15.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book4prop15" embed=true alt="Byrne's figure for IV.15: a black circle with centre G, the dotted diameter AD, a thin red circle about D through G cutting the circle at C and E, the dotted lines CF and EB through G, and the yellow regular hexagon ABCDEF."}
import afield.geometry.euclid.*
import kotlin.math.PI

// book4prop15 written out in full; the library has the same construction as Elements.book4prop15(...)
fun book4prop15(circle: Circle2, at: Double = -PI / 2) = construction("IV.15", "In a given circle to inscribe an equilateral and equiangular hexagon.") {
    val g = circle.center
    val a = circle.at(at)
    step("Let ABCDEF be the given circle, and G its centre.", circle.drawn(Byrne.BLACK), g.named("G"), a.named("A"))
    val d = far(meet(Line2.through(a, g), circle, "IV.15"), a)
    step("Draw the diameter AGD.", Segment2(a, d).drawn(Byrne.BLACK, Role.CONSTRUCTION), d.named("D"))
    val around = Circle2(d, circle.radius)
    val (c, e) = meet(around, circle, "IV.15")
    step(
        "With centre D and radius DG describe the circle EGCH, cutting the given circle at C and E.",
        around.drawn(Byrne.RED, Role.CONSTRUCTION),
        c.named("C"),
        e.named("E"),
    )
    val f = far(meet(Line2.through(c, g), circle, "IV.15"), c)
    val b = far(meet(Line2.through(e, g), circle, "IV.15"), e)
    val hexagon = aroundCentre(g, at, [a, b, c, d, e, f])
    step(
        "Join CG and EG and produce them to F and B. The six arcs are equal, and ABCDEF is the regular hexagon.",
        Segment2(c, f).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Segment2(e, b).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        f.named("F"),
        b.named("B"),
        hexagon.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(isRegular(hexagon, 6), "IV.15: ABCDEF is not regular")
    hexagon
}

val c = book4prop15(Circle2(point2(0, 0), 100.0))
lines(c.toFLines(width = 480.0, height = 480.0), w = 480.0, h = 480.0)
markdown(c.markdown())
```

### IV.16: the fifteen-sided figure

Put a triangle and a pentagon in the same circle, sharing the corner A. Measured in fifteenths of
the circle, the triangle's side AC spans five and the pentagon's side AB three, so the arc BC
between them spans two. Halving that arc gives one fifteenth, and stepping it round the circle
gives the figure.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book4prop16.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book4prop16" embed=true alt="Byrne's figure for IV.16: a black circle holding a blue equilateral triangle and a red regular pentagon that share the top corner A. The arc between the pentagon's corner B and the triangle's corner C is halved at E, and a ring of thin yellow circles steps a yellow fifteen-sided figure round the circle."}
import afield.geometry.euclid.*
import kotlin.math.PI

// book4prop16 written out in full; the library has the same construction as Elements.book4prop16(...)
fun book4prop16(circle: Circle2, at: Double = -PI / 2) = construction(
    "IV.16",
    "In a given circle to inscribe a fifteen-angled figure which shall be both equilateral and equiangular.",
) {
    val o = circle.center
    val a = circle.at(at)
    step("Let ABCD be the given circle.", circle.drawn(Byrne.BLACK), a.named("A"))
    val hexagon = by { Elements.book4prop15(circle, at) }.points
    val c = hexagon[2]
    step(
        "Inscribe an equilateral triangle with a corner at A (IV.2, here taking every other corner of the hexagon of IV.15); let AC be its side.",
        *Triangle2(hexagon[0], hexagon[2], hexagon[4]).sides.map { it.drawn(Byrne.BLUE) }.toTypedArray(),
        c.named("C"),
    )
    val pentagon = by { Elements.book4prop11(circle, at) }.points
    val b = pentagon[1]
    step(
        "Inscribe a regular pentagon with a corner at A (IV.11); let AB be its side. Of the fifteen equal parts of the circle, the arc AC holds five and AB three, so BC holds two.",
        pentagon.let { Polygon2(it) }.drawn(Byrne.RED),
        b.named("B"),
    )
    val mid = by { Elements.prop10(Segment2(b, c)) }
    val up = by { Elements.prop11(Segment2(b, c), mid) }
    val e = meet(Line2(mid, up.direction), circle, "IV.16").minBy { it.dist(b) }
    step(
        "Bisect the arc BC at E (III.30). BE and EC are each a fifteenth of the circle.",
        Segment2(mid, e).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        e.named("E"),
    )
    val side = b.dist(e)
    val corners = mutableListOf(a)
    val steps = mutableListOf<Figure>()
    while (corners.size < 15) {
        val p = corners.last()
        val chord = Circle2(p, side)
        steps += chord.drawn(Byrne.YELLOW, Role.CONSTRUCTION)
        corners += meet(chord, circle, "IV.16").minBy { offset(o, p, it) }
    }
    val figure = Polygon2(corners)
    step(
        "Fit straight lines equal to BE round the whole circle (IV.1): the figure is equilateral and equiangular.",
        *steps.toTypedArray(),
        figure.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(isRegular(figure, 15), "IV.16: the figure is not regular")
    figure
}

val c = book4prop16(Circle2(point2(0, 0), 100.0))
lines(c.toFLines(width = 480.0, height = 480.0), w = 480.0, h = 480.0)
markdown(c.markdown())
```

## Book VI: proportion

Book VI does with lengths what Book II did with areas. Almost everything rests on VI.2: a line
parallel to one side of a triangle cuts the other two sides in the same ratio.

### VI.9 and VI.10: dividing a line

VI.9 cuts off a third (or any part) of a line AB. Mark equal steps along a second line from A, join
the last to B, and a parallel through the first step cuts off the part.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book6prop9.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-later-book6prop9" embed=true alt="Byrne's figure for VI.9: the black line AB and a second line AC rising from A, marked off by three thin blue circles into equal steps AD, DE, EC. The red line BC and the dotted red parallel DF through D, with the yellow third AF along AB."}
import afield.geometry.euclid.*
import kotlin.math.PI

// book6prop9 written out in full; the library has the same construction as Elements.book6prop9(...)
fun book6prop9(ab: Segment2, parts: Int = 3) = construction("VI.9", "From a given straight line to cut off a prescribed part.") {
    require(parts >= 2) { "VI.9: cut off at least a half" }
    val (a, b) = ab
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val dir = ab.direction.rotated(-PI / 6).normalized()
    val unit = ab.length * 0.9 / parts
    val marks = mutableListOf<Point2>()
    val circles = mutableListOf<Figure>()
    var p = a
    repeat(parts) {
        val circle = Circle2(p, unit)
        circles += circle.drawn(Byrne.BLUE, Role.CONSTRUCTION)
        p = pick(meet(Ray2(p, dir), circle, "VI.9"))
        marks += p
    }
    val c = marks.last()
    val d = marks.first()
    val letters = "DEGHIKLMNOPQRSTUVWXYZ"
    step(
        "Draw AC at any angle to AB, and cut off equal parts AD, DE, ... along it, as many as the part has (I.3).",
        Segment2(a, c).drawn(Byrne.BLACK),
        *circles.toTypedArray(),
        *marks.dropLast(1).mapIndexed { i, m -> m.named(letters[i].toString()) }.toTypedArray(),
        c.named("C"),
    )
    val df = by { Elements.prop31(d, Segment2(b, c)) }
    val f = pick(meet(df, ab, "VI.9"))
    val af = Segment2(a, f)
    step(
        "Join BC, and through D draw DF parallel to it (I.31). AF is the part required (VI.2).",
        Segment2(b, c).drawn(Byrne.RED),
        Segment2(d, f).drawn(Byrne.RED, Role.CONSTRUCTION),
        f.named("F"),
        af.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(af.length * parts, ab.length), "VI.9: AF is not the part")
    af
}

val c = book6prop9(Segment2(point2(0, 0), point2(240, 0)), parts = 3)
lines(c.toFLines(width = 560.0, height = 320.0), w = 560.0, h = 320.0)
markdown(c.markdown())
```

VI.10 copies any division: given a line AC already cut at D and E, it cuts AB in the same ratios
with parallels to BC. The pieces of AB take the colours of the pieces of AC they match.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book6prop10.png" wait="3" capture="box" open-outputs="true" w="1000" h="720" padding="14" timeout="90" name="euclid-later-book6prop10" embed=true alt="Byrne's figure for VI.10: the line AC rising from A, cut at D and E into red, yellow and blue pieces; the dotted line BC and dotted parallels through D and E; and AB below cut at F and L into red, yellow and blue pieces in the same proportions."}
import afield.geometry.euclid.*

// book6prop10 written out in full; the library has the same construction as Elements.book6prop10(...)
fun book6prop10(ab: Segment2, ac: Segment2, cuts: List<Point2>) = construction("VI.10", "To cut a given uncut straight line similarly to a given cut straight line.") {
    val a = ab.a
    val b = ab.b
    val c = ac.b
    val colors = [Byrne.RED, Byrne.YELLOW, Byrne.BLUE, Byrne.BLACK]
    val along = ([a] + cuts + c)
    step(
        "Let AB be the uncut line and AC the cut one, set at an angle, cut at D, E, ....",
        ab.drawn(Byrne.BLACK),
        *along
            .zipWithNext()
            .mapIndexed { i, (p, q) -> Segment2(p, q).drawn(colors[i % colors.size]) }
            .toTypedArray(),
        a.named("A"),
        b.named("B"),
        c.named("C"),
        *cuts.mapIndexed { i, p -> p.named("DEGHK"[i % 5].toString()) }.toTypedArray(),
    )
    step("Join BC.", Segment2(b, c).drawn(Byrne.BLACK, Role.CONSTRUCTION))
    val feet = cuts.map { p -> pick(meet(by { Elements.prop31(p, Segment2(b, c)) }, ab, "VI.10")) }
    val onAB = [a] + feet + b
    step(
        "Through D, E, ... draw parallels to BC (I.31), meeting AB at F, G, .... AB is cut as AC is (VI.2).",
        *cuts.zip(feet).map { (p, q) -> Segment2(p, q).drawn(Byrne.BLACK, Role.CONSTRUCTION) }.toTypedArray(),
        *onAB
            .zipWithNext()
            .mapIndexed { i, (p, q) -> Segment2(p, q).drawn(colors[i % colors.size], Role.RESULT) }
            .toTypedArray(),
        *feet.mapIndexed { i, p -> p.named("FLMNO"[i % 5].toString()) }.toTypedArray(),
    )
    check(
        feet.indices.all { eq(a.dist(feet[it]) / ab.length, a.dist(cuts[it]) / ac.length) },
        "VI.10: AB is not cut as AC is",
    )
    feet
}

val ac = Segment2(point2(0, 0), point2(150, -130))
val c = book6prop10(Segment2(point2(0, 0), point2(240, 0)), ac, [ac.at(0.3), ac.at(0.75)])
lines(c.toFLines(width = 560.0, height = 360.0), w = 560.0, h = 360.0)
markdown(c.markdown())
```

### VI.11 and VI.12: third and fourth proportionals

VI.11 finds a third proportional to two lines: the length x with AB : AC = AC : x. Extend AB by
AC, join BC, and a parallel through the end cuts off x along AC produced.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book6prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book6prop11" embed=true alt="Byrne's figure for VI.11: the red line AB and blue line AC at an angle from A, AB produced by the blue BD equal to AC, the black join BC, the dotted parallel DE, and the yellow third proportional CE along AC produced."}
import afield.geometry.euclid.*

// book6prop11 written out in full; the library has the same construction as Elements.book6prop11(...)
fun book6prop11(ab: Segment2, ac: Segment2) = construction("VI.11", "To two given straight lines to find a third proportional.") {
    val a = ab.a
    val b = ab.b
    val c = ac.b
    step(
        "Let AB and AC be the two given straight lines, set at any angle.",
        ab.drawn(Byrne.RED),
        ac.drawn(Byrne.BLUE),
        a.named("A"),
        b.named("B"),
        c.named("C"),
    )
    val circle = Circle2(b, ac.length)
    val d = pick(meet(Ray2(b, b - a), circle, "VI.11"))
    step(
        "Produce AB to D, making BD equal to AC.",
        circle.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        Segment2(b, d).drawn(Byrne.BLUE),
        d.named("D"),
    )
    step("Join BC.", Segment2(b, c).drawn(Byrne.BLACK))
    val de = by { Elements.prop31(d, Segment2(b, c)) }
    val e = pick(meet(de, Ray2(a, c - a), "VI.11"))
    val ce = Segment2(c, e)
    step(
        "Through D draw DE parallel to BC, meeting AC produced at E. As AB is to BD, so AC is to CE (VI.2), and BD is AC.",
        Segment2(d, e).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        e.named("E"),
        ce.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(ab.length * ce.length, ac.length * ac.length), "VI.11: CE is not the third proportional")
    ce
}

val c = book6prop11(Segment2(point2(0, 0), point2(120, 0)), Segment2(point2(0, 0), point2(60, -100)))
lines(c.toFLines(width = 520.0, height = 460.0), w = 520.0, h = 460.0)
markdown(c.markdown())
```

VI.12 finds a fourth proportional to three lines A, B and C: the length x with A : B = C : x. Lay A
then B along one line from D and C along another, join the ends of A and C, and a parallel through
the end of B cuts off x.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book6prop12.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book6prop12" embed=true alt="Byrne's figure for VI.12: three given lines on the left in red, blue and black; from D a line carrying DG equal to the red and GE equal to the blue, a line rising at an angle carrying DH equal to the black, thin circles marking each, the dotted joins GH and EL, and the yellow fourth proportional HL."}
import afield.geometry.euclid.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// book6prop12 written out in full; the library has the same construction as Elements.book6prop12(...)
fun book6prop12(first: Segment2, second: Segment2, third: Segment2, at: Point2 = Point2.ORIGIN) = construction("VI.12", "To three given straight lines to find a fourth proportional.") {
    val d = at
    step(
        "Let A, B, C be the three given straight lines, and DE, DF two straight lines from D at any angle.",
        first.drawn(Byrne.RED),
        second.drawn(Byrne.BLUE),
        third.drawn(Byrne.BLACK),
        d.named("D"),
    )
    val along = Point2(1.0, 0.0)
    val across = Point2(cos(-PI / 4), sin(-PI / 4))
    val c1 = Circle2(d, first.length)
    val g = pick(meet(Ray2(d, along), c1, "VI.12"))
    val c2 = Circle2(g, second.length)
    val e = pick(meet(Ray2(g, along), c2, "VI.12"))
    val c3 = Circle2(d, third.length)
    val h = pick(meet(Ray2(d, across), c3, "VI.12"))
    step(
        "Cut off DG equal to A, GE equal to B, and DH equal to C (I.3).",
        c1.drawn(Byrne.RED, Role.CONSTRUCTION),
        c2.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        c3.drawn(Byrne.BLACK, Role.CONSTRUCTION),
        Segment2(d, g).drawn(Byrne.RED),
        Segment2(g, e).drawn(Byrne.BLUE),
        Segment2(d, h).drawn(Byrne.BLACK),
        g.named("G"),
        e.named("E"),
        h.named("H"),
    )
    step("Join GH.", Segment2(g, h).drawn(Byrne.BLACK, Role.CONSTRUCTION))
    val el = by { Elements.prop31(e, Segment2(g, h)) }
    val l = pick(meet(el, Ray2(d, across), "VI.12"))
    val hl = Segment2(h, l)
    step(
        "Through E draw EL parallel to GH. As DG is to GE, so DH is to HL (VI.2): A is to B as C is to HL.",
        Segment2(e, l).drawn(Byrne.BLACK, Role.CONSTRUCTION),
        l.named("L"),
        hl.drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(first.length * hl.length, second.length * third.length), "VI.12: HL is not the fourth proportional")
    hl
}

val c = book6prop12(
    first = Segment2(point2(-220, -200), point2(-160, -200)),
    second = Segment2(point2(-220, -170), point2(-120, -170)),
    third = Segment2(point2(-220, -140), point2(-140, -140)),
)
lines(c.toFLines(width = 560.0, height = 420.0), w = 560.0, h = 420.0)
markdown(c.markdown())
```

### VI.30: the golden section again

VI.30 asks for the same cut as II.11, in the language of ratio: AB : AE = AE : EB. Euclid gets it
by fitting to AB a rectangle equal to the square on AB that overflows it by a square (VI.29). For
a square that comes down to one circle about the midpoint of AB. The two red pieces are equal; the
yellow rectangle is shared.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/book6prop30.png" wait="3" capture="box" open-outputs="true" w="1000" h="900" padding="14" timeout="90" name="euclid-later-book6prop30" embed=true alt="Byrne's figure for VI.30: the square ABFG above AB, the midpoint C, a thin blue circle about C through G cutting AB produced at D, a yellow rectangle ABNK in the bottom of the square, a red strip KNFG above it and an equal red square BDMN beside it, and the yellow cut AE."}
import afield.geometry.euclid.*

// book6prop30 written out in full; the library has the same construction as Elements.book6prop30(...)
fun book6prop30(ab: Segment2, side: Int = 1) = construction("VI.30", "To cut a given finite straight line in extreme and mean ratio.") {
    val (a, b) = ab
    step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
    val sq = by { Elements.prop46(ab, side) }
    val f = sq.c
    val g = sq.d
    step(
        "On AB describe the square ABFG.",
        *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
        f.named("F"),
        g.named("G"),
    )
    val c = by { Elements.prop10(ab) }
    step("Bisect AB at C, and join CG.", c.named("C"), Segment2(c, g).drawn(Byrne.BLACK, Role.CONSTRUCTION))
    val circle = Circle2(c, c.dist(g))
    val d = pick(meet(Ray2(b, b - a), circle, "VI.30"))
    step(
        "With centre C and radius CG cut AB produced at D. Then the rectangle on AD with height BD equals the square on AB, and exceeds AB by the square on BD (VI.29, II.6).",
        circle.drawn(Byrne.BLUE, Role.CONSTRUCTION),
        Segment2(b, d).drawn(Byrne.BLACK),
        d.named("D"),
    )
    val up = (g - a).normalized() * b.dist(d)
    val m = d + up
    val n = b + up
    val k = a + up
    val e = pick(meet(ab, Circle2(a, b.dist(d)), "VI.30"))
    step(
        "Complete the rectangle ADMK and the square BDMN. Taking away the common ABNK, the square BDMN equals KNFG. Cut off AE equal to BD: AB is cut at E in extreme and mean ratio.",
        Polygon2(a, b, n, k).drawn(Byrne.YELLOW, Role.AREA),
        Polygon2(k, n, f, g).drawn(Byrne.RED, Role.AREA),
        Polygon2(b, d, m, n).drawn(Byrne.RED, Role.AREA),
        Segment2(d, m).drawn(Byrne.BLACK),
        Segment2(m, k).drawn(Byrne.BLACK),
        Segment2(b, n).drawn(Byrne.BLACK),
        m.named("M"),
        n.named("N"),
        k.named("K"),
        e.named("E"),
        Segment2(a, e).drawn(Byrne.YELLOW, Role.RESULT),
    )
    check(eq(ab.length * e.dist(b), a.dist(e) * a.dist(e)), "VI.30: E is not the section")
    e
}

val c = book6prop30(Segment2(point2(0, 0), point2(160, 0)), side = -1)
lines(c.toFLines(width = 520.0, height = 460.0), w = 520.0, h = 460.0)
markdown(c.markdown())
```

II.11 and VI.30 land on the same point. `book2prop11(ab).result` and `book6prop30(ab).result` agree
to rounding, one reached through areas and the other through ratio.

## Every solution

A construction makes choices as it goes. Two circles cross at two points, and I.1 takes one of
them; take the other and the triangle lands on the opposite side of the line. A proposition that
cites I.1 inherits that choice, along with every choice of its own. Called plainly, a proposition
takes Euclid's choice, or the one its `side` argument asks for. `Elements.solutions { ... }` tries
every combination instead. It drops the ones whose geometry breaks the proposition's claim (in
I.31, one of the two copied angles gives a line that is not parallel), and keeps once each result
that lands in a different place.

I.2 has four solutions, the four cases Proclus described: each is a line from A as long as BC,
pointing a different way.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop2_solutions.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop2-solutions" embed=true alt="Four of Byrne's figures for I.2 in a two-by-two grid, one for each solution. In every one the blue line BC and the point A are the same, but the triangle, the two circles and the yellow result AL differ, and AL points a different way from A in each."}
import afield.geometry.euclid.*

val bc = Segment2(point2(90, 40), point2(150, 100))
val all = Elements.solutions { prop2(point2(0, 0), bc) }
print("${all.size} ways to place BC at A")
lines(
    all.flatMapIndexed { i, c ->
        c.toFLines(style = ByrneStyle.PLATE.copy(thickness = 1.8), width = 240.0, height = 240.0).map { line ->
            line.transform { v -> Vec3(v.x + 250f * (i % 2), v.y + 250f * (i / 2), v.z) }
        }
    },
    w = 490.0,
    h = 490.0,
)
```

The solutions come back with the usual one first, so `solutions { ... }.first()` is the plain
call. To choose by position, every `Construction` has a `location`, the middle of its result, and
the list has `nearest(point)` and `farthest(point)`:

```diffstatic {data-lang=kotlin}
val square = Elements.solutions { prop46(ab) }.nearest(mouse)
```

A cited proposition counts as one choice among its own distinct solutions, so the search grows
with the number of different answers, not with every crossing inside them. The deepest
constructions on these pages, the pentagon and the fifteen-sided figure, take about a tenth of a
second.

## How a construction is styled

A proposition says what each element is for, not how to draw it. Every figure has one of four
roles:

- **given**: what the proposition is handed, and lines it draws and keeps. Solid.
- **construction**: circles and lines drawn only to find a point. Circles are thin; straight
  construction lines (joins and productions) are dotted, as Byrne printed them.
- **result**: what the proposition was asked to make. Heavier.
- **area**: a region filled with colour, drawn under every line. Equal areas share a colour, as
  in I.47.

`ByrneStyle` turns roles into pens, so every diagram is styled in one place. Steps that come from
a cited proposition draw only what they produce, which keeps I.46 a square and not a tangle of
circles. To see that working as well, pass `ByrneStyle.WORKING`, which draws the cited steps faded
behind the main figure:

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop46_working.png" wait="3" capture="box" open-outputs="true" w="1000" h="560" padding="14" timeout="90" name="euclid-elements-prop46-working" embed=true alt="The figure for I.46 again, now with every circle from the cited propositions I.11, I.31 and I.23 drawn faintly behind the square, crowding around A, D and B."}
import afield.geometry.euclid.*

val c = Elements.prop46(Segment2(point2(0, 0), point2(120, 0)), side = -1)
lines(c.toFLines(style = ByrneStyle.WORKING, extent = 160.0, width = 520.0, height = 420.0), w = 520.0, h = 420.0)
```

Passing `width` and `height` to `toFLines` fits the figure to that many pixels and sizes the
strokes and letters for it, so every plate on this page looks alike. `ByrneStyle` is a data class,
so `ByrneStyle.PLATE.copy(...)` changes one thing and keeps the rest: `thickness` sets the base
stroke width every pen is a multiple of, `paper = null` drops the cream sheet, `labels = false` drops the
letters, and `labelSize` and `labelDistance` (both in multiples of `thickness`) set the letters.

## See also

- [Byrne's Euclid](https://www.c82.net/euclid/), Nicholas Rougeux's online edition of Byrne's
  1847 *Elements*, with every plate redrawn: the book these figures are drawn after.
- The shapes and their intersections:
  [Euclid.kt](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/euclid/Euclid.kt).
- The propositions:
  [Elements.kt](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/euclid/Elements.kt).
  It also has III.1 (the centre of a circle), IV.4 and IV.5 (a triangle's incircle and
  circumcircle) and VI.13 (a mean proportional), which this page does not show.
- [Typography as Geometry](typography_examples.md), where the letters come from.
