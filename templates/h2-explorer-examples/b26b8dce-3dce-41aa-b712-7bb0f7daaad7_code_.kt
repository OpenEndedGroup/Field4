// A hand-written adapter, because reflecting a Box would read `Box.ki` -- a lazy delegate
// that BUILDS A K2 REPL ENGINE on read. Exploring a box would compile one.
//
// Click into `properties` for the box's whole property bag, and `edges` for its
// parents / children / nested / nestedIn as one table.

display("box", thisBox.h2)
displayOpen("box")