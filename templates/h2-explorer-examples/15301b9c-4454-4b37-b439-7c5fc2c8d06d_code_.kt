// A polyline's content is its node list, which is one property among four. The adapter
// turns it into a node table: index, instruction kind, control-point count, endpoint, and how
// many per-node properties each carries.
//
// Note the `Close` row: `FLine.Node.end` THROWS on a Close (it is Node(0) and stores no point of
// its own), so the adapter reads the last stored point and a Close honestly carries blanks.

import afield.graphics.FLine

val f = FLine()
    .moveTo(0.15, 0.2)
    .lineTo(0.85, 0.2)
    .cubicTo(0.95, 0.5, 0.75, 0.8, 0.5, 0.8)
    .lineTo(0.15, 0.5)

display("fline", f.h2)
displayOpen("fline")