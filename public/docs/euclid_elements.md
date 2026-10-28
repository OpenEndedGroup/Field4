# Euclid's Elements, Book I

`afield.geometry.euclid` is plane geometry in the classical sense: points, lines, rays, segments,
circles and arcs, with every intersection, closest point and distance between them. On top of
that sit the propositions of Euclid's *Elements*, drawn the way Oliver Byrne printed them in 1847,
in red, yellow, blue and black.

Each proposition takes geometry in and hands back a `Construction`. Its `result` is what the
proposition asked for (a triangle, a segment, a line). Its `steps` are the numbered sentences of
the proof, each holding the circles, lines and lettered points it draws. Every point in a
construction comes from intersecting circles and straight lines, so the diagram is the working,
not an illustration of it.

## I.1: an equilateral triangle

The first proposition builds an equilateral triangle on a segment. Draw a circle about each end
through the other, and the triangle's third corner is where the circles cross. `side = -1` puts
that corner above the segment; `toFLines()` draws the diagram and `markdown()` writes out the
proof.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop1.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop1" embed=true alt="Byrne's figure for I.1 on cream paper: a red circle about A and a blue circle about B, each passing through the other centre. They cross at C above the black segment AB, and C is joined to A in yellow and to B in red."}
import afield.geometry.euclid.*

val c = Elements.prop1(Segment2(point2(0, 0), point2(160, 0)), side = -1)
lines(c.toFLines(width = 520.0, height = 400.0), w = 520.0, h = 400.0)
markdown(c.markdown())
```

`c.result` is a `Triangle2`, and its corners are ordinary `Point2`s you can keep building on.
`c.toFLines(upTo = 2)` draws only the first two steps, which is how you step through a proof.

## I.2: moving a line to a point

To place a copy of segment BC at point A, Euclid builds the equilateral triangle of I.1 on AB,
then uses two circles, one about B and one about the triangle's apex D, to carry the length across.
The triangle comes from I.1, so its two circles are not redrawn; only its sides appear.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop2.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-elements-prop2" embed=true alt="Byrne's figure for I.2: the given blue segment BC and the point A, the dotted sides of the equilateral triangle DAB, a blue circle about B through C, a larger red circle about D, dotted productions of DB and DA, and the yellow result AL starting at A."}
import afield.geometry.euclid.*

val c = Elements.prop2(point2(0, 0), Segment2(point2(90, 40), point2(150, 100)))
lines(c.toFLines(width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

## I.3: cutting off the less from the greater

Given two unequal segments, I.3 cuts from the longer a piece as long as the shorter: copy the
shorter to the longer's end with I.2, then a circle about that end marks the cut.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop3.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-elements-prop3" embed=true alt="Byrne's figure for I.3: the long black segment AB, the short blue segment C below it, its copy AD in blue at A, a red circle about A through D, and the yellow piece AE it cuts from AB."}
import afield.geometry.euclid.*

val c = Elements.prop3(Segment2(point2(0, 0), point2(200, 0)), Segment2(point2(30, 60), point2(110, 80)))
lines(c.toFLines(width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

## I.9: bisecting an angle

Mark equal distances D and E along the two arms, build the equilateral triangle of I.1 on DE on
the side away from the vertex, and the line from the vertex to its apex F halves the angle.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop9.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop9" embed=true alt="Byrne's figure for I.9: the angle BAC in black, a thin blue circle about A marking D and E on its arms, a dotted line DE, the apex F beyond it, and the yellow bisector AF."}
import afield.geometry.euclid.*

val c = Elements.prop9(point2(0, 0), point2(200, -20), point2(80, -160))
lines(c.toFLines(width = 520.0, height = 400.0), w = 520.0, h = 400.0)
markdown(c.markdown())
```

## I.10: bisecting a segment

I.10 is built from the two propositions before it: the equilateral triangle on AB (I.1), then the
bisector of the angle at its apex (I.9), which meets AB at its midpoint D.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop10.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-elements-prop10" embed=true alt="Byrne's figure for I.10: segment AB, the dotted sides of the equilateral triangle ABC below it, the red bisector from C down to D, and the two equal halves AD in yellow and DB in blue."}
import afield.geometry.euclid.*

val c = Elements.prop10(Segment2(point2(0, 0), point2(180, 0)))
lines(c.toFLines(width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

## I.11 and I.12: perpendiculars

I.11 raises a perpendicular from a point C on a line: mark D and E equally far from C on either
side, and the apex of the equilateral triangle on DE stands straight above C.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="720" padding="14" timeout="90" name="euclid-elements-prop11" embed=true alt="Byrne's figure for I.11: the black line AB with C on it, a thin blue circle about C marking D and E, the apex F above, and the yellow perpendicular CF."}
import afield.geometry.euclid.*

val c = Elements.prop11(Segment2(point2(0, 0), point2(200, 0)), point2(80, 0), side = -1)
lines(c.toFLines(width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

I.12 drops a perpendicular from a point C off the line: a circle about C, large enough to reach
past the line, cuts it at G and E, and the midpoint of GE (by I.10) is the foot H.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop12.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop12" embed=true alt="Byrne's figure for I.12: a black line, the point C above it, a point D below, a thin blue circle about C through D cutting the line at G and E, and the yellow perpendicular CH to the midpoint H."}
import afield.geometry.euclid.*

val c = Elements.prop12(Line2(point2(0, 0), point2(1, 0)), point2(60, -120))
lines(c.toFLines(extent = 220.0, width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

## I.23: copying an angle

I.23 puts a copy of the angle DCE at the start A of a line. Mark D and E equally far from C,
mark AF that far along the line, and a circle about F with radius DE finds G. The triangles AFG and
CDE have equal sides, so their angles at A and C are equal. Euclid builds that triangle with I.22;
here two circles do the same job.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop23.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop23" embed=true alt="Byrne's figure for I.23: on the left the given angle DCE in black with its red chord DE; on the right the black ray from A, a thin blue circle about A marking F, a thin red circle about F, and the yellow arm AG making the same angle with the ray."}
import afield.geometry.euclid.*

val c = Elements.prop23(
    along = Ray2(point2(0, 0), point2(1, 0)),
    vertex = point2(-200, 40), d = point2(-100, 40), e = point2(-150, -40),
    side = -1,
)
lines(c.toFLines(extent = 140.0, width = 560.0, height = 400.0), w = 560.0, h = 400.0)
markdown(c.markdown())
```

## I.31: a parallel through a point

Join A to any point D of the line, then copy the angle the join makes with the line (I.23) to the
other side of the join at A. Equal alternate angles make the new line parallel.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop31.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-elements-prop31" embed=true alt="Byrne's figure for I.31: the black segment BC, the point A above it, a dotted join from A to D on BC, and the yellow line through A parallel to BC."}
import afield.geometry.euclid.*

val c = Elements.prop31(point2(60, -90), Segment2(point2(0, 0), point2(200, 0)))
lines(c.toFLines(extent = 160.0, width = 520.0, height = 360.0), w = 520.0, h = 360.0)
markdown(c.markdown())
```

## I.46: a square on a segment

The last construction of Book I gathers the others: a perpendicular at A (I.11), cut to the
length of AB, then a parallel to each side (I.31) through the far corners, closing the square.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop46.png" wait="3" capture="box" open-outputs="true" w="1000" h="760" padding="14" timeout="90" name="euclid-elements-prop46" embed=true alt="Byrne's figure for I.46: segment AB, a thin dotted perpendicular rising from A, a thin blue circle about A through B, the red side AD, and the square closed by the blue side DE and the yellow side BE."}
import afield.geometry.euclid.*

val c = Elements.prop46(Segment2(point2(0, 0), point2(120, 0)), side = -1)
lines(c.toFLines(extent = 160.0, width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

## I.47: the squares on a right triangle

Book I ends with Pythagoras. Squares go on all three sides of a right triangle (I.46), and a
parallel through the right angle (I.31) splits the square on the long side into two rectangles.
Each rectangle equals one of the other squares, which Byrne shows by colour: red equals red, blue
equals blue.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements/prop47.png" wait="3" capture="box" open-outputs="true" w="1000" h="900" padding="14" timeout="90" name="euclid-elements-prop47" embed=true alt="Byrne's figure for I.47: a right triangle ABC with squares on its three sides. The square on BA is filled red and the square on AC blue. The large square on BC, outlined in yellow, is split by the line AL into a red rectangle under B and a blue rectangle under C, with the dotted joins AD and FC crossing it."}
import afield.geometry.euclid.*

val c = Elements.prop47(Triangle2(point2(0, 0), point2(120, 0), point2(0, -90)))
lines(c.toFLines(width = 560.0, height = 560.0), w = 560.0, h = 560.0)
markdown(c.markdown())
```

## How a construction is styled

A proposition says what each element is for, not how to draw it. Every figure has one of three
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

- The shapes and their intersections:
  [Euclid.kt](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/euclid/Euclid.kt).
- The propositions:
  [Elements.kt](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/euclid/Elements.kt).
- [Euclid's Elements, Books II–VI](euclid_elements_later.md): the gnomons, the golden section,
  the pentagon and the fifteen-sided figure, and proportion.
- [Typography as Geometry](typography_examples.md), where the letters come from.
