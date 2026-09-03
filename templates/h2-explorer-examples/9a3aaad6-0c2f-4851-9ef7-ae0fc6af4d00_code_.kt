// The collection half of the gate used to be narrower than its own machinery: it admitted
// `IntArray` but not `ByteArray`, `List` but not `Set`, while both the tree and the explorer
// already knew how to draw all of them. Swap the two lines below to compare `.h` and `.h2`.

val names = (1..400).map { "node-" + it }.toSet()

display("set", names.h2)
// display("set", names.h)
displayOpen("set")