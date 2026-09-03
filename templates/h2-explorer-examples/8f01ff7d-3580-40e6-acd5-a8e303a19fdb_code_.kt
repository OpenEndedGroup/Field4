// The reflective default. No adapter, no registration -- `.h2` reads the properties of
// anything it is handed, in constructor order, and pages them.
//
// `.h` renders this as one line: `Trail { id -> 7, x -> 0.25, ... }`.

data class Trail(val id: Int, val x: Double, val y: Double, val label: String, val closed: Boolean)

display("trail", Trail(7, 0.25, 0.75, "trail-7", true).h2)
displayOpen("trail")