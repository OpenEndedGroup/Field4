// An `Fn`'s only non-derived field is a private `(Double) -> Double`, so a property tree
// would show you nothing. The adapter samples it instead: 512 rows of x and f(x) over the
// declared domain, which is what you actually wanted to see.
//
// Scroll it. The explorer streams windows over the socket rather than sending 512 rows at once.

import afield.stdlib.Functions

val wobble = Functions.fn(Functions.Domain(0.0, 6.283185), "sin+ripple") {
    kotlin.math.sin(it) + 0.15 * kotlin.math.sin(it * 7)
}

display("fn", wobble.h2)
displayOpen("fn")