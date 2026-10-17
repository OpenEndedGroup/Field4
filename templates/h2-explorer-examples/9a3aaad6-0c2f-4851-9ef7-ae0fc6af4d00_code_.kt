// Sets are browsable collections. Swap the two display lines to compare the compact
// `.h` view with the paged `.h2` explorer.

val names = (1..400).map { "node-" + it }.toSet()

display("set", names.h2)
// display("set", names.h)
displayOpen("set")
