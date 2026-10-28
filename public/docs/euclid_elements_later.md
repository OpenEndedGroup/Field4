# Euclid's Elements, Books II–VI

This page continues [Euclid's Elements, Book I](euclid_elements.md) into the later books: areas in
Book II, the circle in Book III, regular polygons in Book IV and proportion in Book VI. Each
proposition works the same way: it takes geometry in and returns a `Construction`, with a
`result`, the numbered `steps` of the proof, and `toFLines(...)` to draw it in Byrne's colours.
Every one is built from circles and straight lines, and from the propositions before it.

## Book II: areas and gnomons

Book II is about rectangles and squares. Byrne fills them with colour, so the proof can be read off
the picture: regions of the same colour have the same area.

### II.5 and II.6: the gnomons

A gnomon is the L-shaped region left when a smaller square is taken from the corner of a larger
one. II.5 cuts a line AB at its midpoint C and at another point D, and shows that the rectangle
AD·DB equals the gnomon in the square on CB. Adding the small square on CD to both gives the
proposition. The two red pieces are equal, and each side of the equation is red plus blue.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book2prop5.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book2prop5" embed=true alt="Byrne's figure for II.5: the line AB with the square CEFB on its right half. A red rectangle on AC and a red rectangle on DB are equal; a blue rectangle CDHL sits between them; a yellow square LHGE fills the corner of the large square, which is cut by the dotted diagonal BE."}
import afield.geometry.euclid.*

val c = Elements.book2prop5(Segment2(point2(0, 0), point2(200, 0)), side = -1)
lines(c.toFLines(width = 560.0, height = 380.0), w = 560.0, h = 380.0)
markdown(c.markdown())
```

II.6 is the same figure with D beyond the end of the line instead of inside it: the rectangle
AD·DB with the square on half the line equals the square on CD.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book2prop6.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book2prop6" embed=true alt="Byrne's figure for II.6: the line AB produced to D, with the square CEFD on CD. A red rectangle on AC and a red rectangle HMFG are equal; the blue rectangle CDML runs under the yellow square LHGE, and the dotted diagonal DE crosses the square."}
import afield.geometry.euclid.*

val c = Elements.book2prop6(Segment2(point2(0, 0), point2(160, 0)), side = -1)
lines(c.toFLines(width = 560.0, height = 380.0), w = 560.0, h = 380.0)
markdown(c.markdown())
```

### II.11: the golden section

II.11 cuts a line AB at H so that the square on AH equals the rectangle AB·BH. That is the golden
section: AB is to AH as AH is to HB, about 1.618 each time. The yellow square and the red rectangle
have equal areas.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book2prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="900" padding="14" timeout="90" name="euclid-later-book2prop11" embed=true alt="Byrne's figure for II.11: the square ABDC on AB, the midpoint E of AC, a thin blue circle about E through B reaching F below A, the yellow square AFGH on AF, and the red rectangle HBDK, equal to the yellow square."}
import afield.geometry.euclid.*

val c = Elements.book2prop11(Segment2(point2(0, 0), point2(160, 0)), side = -1)
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book2prop14.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book2prop14" embed=true alt="Byrne's figure for II.14: a red rectangle BEDC, its side BE produced to F, a black semicircle on BF below it, the dotted line from E down to H on the semicircle, and the yellow square on EH, equal in area to the red rectangle."}
import afield.geometry.euclid.*

val rectangle = Quadrilateral2(point2(0, 0), point2(180, 0), point2(180, -80), point2(0, -80))
val c = Elements.book2prop14(rectangle)
lines(c.toFLines(width = 520.0, height = 420.0), w = 520.0, h = 420.0)
markdown(c.markdown())
```

## Book III: a tangent from a point

III.17 draws the tangent to a circle from a point A outside it. A second circle about the centre E
reaches out to A; a perpendicular where AE crosses the first circle meets the second at F; and the
line from E to F crosses the first circle at the point of contact B. The triangles EAB and EFD are
equal, so the angle at B is a right angle.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book3prop17.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book3prop17" embed=true alt="Byrne's figure for III.17: a black circle about E, the outside point A, a larger thin blue circle about E through A, the red perpendicular DF, the dotted lines AE and EF, and the yellow tangent AB touching the circle at B."}
import afield.geometry.euclid.*

val c = Elements.book3prop17(Circle2(point2(0, 0), 60.0), point2(170, 40))
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book4prop10.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book4prop10" embed=true alt="Byrne's figure for IV.10: the black line AB with its golden section at C marked in red, a thin blue circle about A through B, a thin red circle about B fitting the chord BD, the dotted line DC, and the golden triangle ABD with AD in yellow and BD in red."}
import afield.geometry.euclid.*

val c = Elements.book4prop10(Segment2(point2(0, -150), point2(0, 0)))
lines(c.toFLines(width = 520.0, height = 480.0), w = 520.0, h = 480.0)
markdown(c.markdown())
```

### IV.11: the pentagon

To put a regular pentagon in a circle, inscribe a triangle with the golden triangle's angles, then
bisect its two base angles. The bisectors cut the circle at the two remaining corners. The triangle
goes in by drawing the tangent at A and copying the golden triangle's base angles against it
(I.23); the angle between a tangent and a chord equals the angle on the far side of the circle
(III.32), which makes the triangle's angles come out right.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book4prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="780" padding="14" timeout="90" name="euclid-later-book4prop11" embed=true alt="Byrne's figure for IV.11: on the left the golden triangle FGH; on the right a black circle with the dotted tangent KAL at the top, the red sides AC and AD and blue base CD of the inscribed triangle, the dotted bisectors CE and DB, and the yellow regular pentagon ABCDE."}
import afield.geometry.euclid.*

val c = Elements.book4prop11(Circle2(point2(0, 0), 100.0))
lines(c.toFLines(width = 620.0, height = 400.0), w = 620.0, h = 400.0)
markdown(c.markdown())
```

### IV.15: the hexagon

The hexagon needs only one circle beyond the given one: drawn about one end of a diameter, through
the centre, it cuts off two corners, and lines from them through the centre give two more.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book4prop15.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book4prop15" embed=true alt="Byrne's figure for IV.15: a black circle with centre G, the dotted diameter AD, a thin red circle about D through G cutting the circle at C and E, the dotted lines CF and EB through G, and the yellow regular hexagon ABCDEF."}
import afield.geometry.euclid.*

val c = Elements.book4prop15(Circle2(point2(0, 0), 100.0))
lines(c.toFLines(width = 480.0, height = 480.0), w = 480.0, h = 480.0)
markdown(c.markdown())
```

### IV.16: the fifteen-sided figure

Put a triangle and a pentagon in the same circle, sharing the corner A. Measured in fifteenths of
the circle, the triangle's side AC spans five and the pentagon's side AB three, so the arc BC
between them spans two. Halving that arc gives one fifteenth, and stepping it round the circle
gives the figure.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book4prop16.png" wait="3" capture="box" open-outputs="true" w="1000" h="860" padding="14" timeout="90" name="euclid-later-book4prop16" embed=true alt="Byrne's figure for IV.16: a black circle holding a blue equilateral triangle and a red regular pentagon that share the top corner A. The arc between the pentagon's corner B and the triangle's corner C is halved at E, and a ring of thin yellow circles steps a yellow fifteen-sided figure round the circle."}
import afield.geometry.euclid.*

val c = Elements.book4prop16(Circle2(point2(0, 0), 100.0))
lines(c.toFLines(width = 480.0, height = 480.0), w = 480.0, h = 480.0)
markdown(c.markdown())
```

## Book VI: proportion

Book VI does with lengths what Book II did with areas. Almost everything rests on VI.2: a line
parallel to one side of a triangle cuts the other two sides in the same ratio.

### VI.9 and VI.10: dividing a line

VI.9 cuts off a third (or any part) of a line AB. Mark equal steps along a second line from A, join
the last to B, and a parallel through the first step cuts off the part.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book6prop9.png" wait="3" capture="box" open-outputs="true" w="1000" h="700" padding="14" timeout="90" name="euclid-later-book6prop9" embed=true alt="Byrne's figure for VI.9: the black line AB and a second line AC rising from A, marked off by three thin blue circles into equal steps AD, DE, EC. The red line BC and the dotted red parallel DF through D, with the yellow third AF along AB."}
import afield.geometry.euclid.*

val c = Elements.book6prop9(Segment2(point2(0, 0), point2(240, 0)), parts = 3)
lines(c.toFLines(width = 560.0, height = 320.0), w = 560.0, h = 320.0)
markdown(c.markdown())
```

VI.10 copies any division: given a line AC already cut at D and E, it cuts AB in the same ratios
with parallels to BC. The pieces of AB take the colours of the pieces of AC they match.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book6prop10.png" wait="3" capture="box" open-outputs="true" w="1000" h="720" padding="14" timeout="90" name="euclid-later-book6prop10" embed=true alt="Byrne's figure for VI.10: the line AC rising from A, cut at D and E into red, yellow and blue pieces; the dotted line BC and dotted parallels through D and E; and AB below cut at F and L into red, yellow and blue pieces in the same proportions."}
import afield.geometry.euclid.*

val ac = Segment2(point2(0, 0), point2(150, -130))
val c = Elements.book6prop10(Segment2(point2(0, 0), point2(240, 0)), ac, listOf(ac.at(0.3), ac.at(0.75)))
lines(c.toFLines(width = 560.0, height = 360.0), w = 560.0, h = 360.0)
markdown(c.markdown())
```

### VI.11 and VI.12: third and fourth proportionals

VI.11 finds a third proportional to two lines: the length x with AB : AC = AC : x. Extend AB by
AC, join BC, and a parallel through the end cuts off x along AC produced.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book6prop11.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book6prop11" embed=true alt="Byrne's figure for VI.11: the red line AB and blue line AC at an angle from A, AB produced by the blue BD equal to AC, the black join BC, the dotted parallel DE, and the yellow third proportional CE along AC produced."}
import afield.geometry.euclid.*

val c = Elements.book6prop11(Segment2(point2(0, 0), point2(120, 0)), Segment2(point2(0, 0), point2(60, -100)))
lines(c.toFLines(width = 520.0, height = 460.0), w = 520.0, h = 460.0)
markdown(c.markdown())
```

VI.12 finds a fourth proportional to three lines A, B and C: the length x with A : B = C : x. Lay A
then B along one line from D and C along another, join the ends of A and C, and a parallel through
the end of B cuts off x.

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book6prop12.png" wait="3" capture="box" open-outputs="true" w="1000" h="820" padding="14" timeout="90" name="euclid-later-book6prop12" embed=true alt="Byrne's figure for VI.12: three given lines on the left in red, blue and black; from D a line carrying DG equal to the red and GE equal to the blue, a line rising at an angle carrying DH equal to the black, thin circles marking each, the dotted joins GH and EL, and the yellow fourth proportional HL."}
import afield.geometry.euclid.*

val c = Elements.book6prop12(
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/euclid_elements_later/book6prop30.png" wait="3" capture="box" open-outputs="true" w="1000" h="900" padding="14" timeout="90" name="euclid-later-book6prop30" embed=true alt="Byrne's figure for VI.30: the square ABFG above AB, the midpoint C, a thin blue circle about C through G cutting AB produced at D, a yellow rectangle ABNK in the bottom of the square, a red strip KNFG above it and an equal red square BDMN beside it, and the yellow cut AE."}
import afield.geometry.euclid.*

val c = Elements.book6prop30(Segment2(point2(0, 0), point2(160, 0)), side = -1)
lines(c.toFLines(width = 520.0, height = 460.0), w = 520.0, h = 460.0)
markdown(c.markdown())
```

II.11 and VI.30 land on the same point. `book2prop11(ab).result` and `book6prop30(ab).result` agree
to rounding, one reached through areas and the other through ratio.

## See also

- [Euclid's Elements, Book I](euclid_elements.md): the first constructions and how a diagram is
  styled.
- The propositions:
  [Elements.kt](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/geometry/euclid/Elements.kt).
  It also has III.1 (the centre of a circle), IV.4 and IV.5 (a triangle's incircle and
  circumcircle) and VI.13 (a mean proportional), which this page does not show.
