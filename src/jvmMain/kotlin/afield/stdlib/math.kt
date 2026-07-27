@file:afield.CompletionCategory("Math")

package afield.stdlib

/**
 * `Number`-taking overloads of the trigonometric functions, so `sin(1)` and `sin(1f)` work
 * without writing `.toDouble()` at every call site.
 *
 * Covers the circular functions and their inverses ([sin], [cos], [tan], [asin], [acos], [atan],
 * [atan2]) and the hyperbolic ones ([sinh], [cosh], [tanh], [asinh], [acosh], [atanh]). Each is a
 * one-line delegate to the matching `kotlin.math` function and always returns `Double`.
 */

/** Sine of [n] **radians**, in `-1..1`. Multiply by `PI / 180` first if you are thinking in degrees. */
fun sin(n: Number): Double = kotlin.math.sin(n.toDouble())

/** Cosine of [n] **radians**, in `-1..1`. */
fun cos(n: Number): Double = kotlin.math.cos(n.toDouble())

/** Tangent of [n] **radians**; unbounded, and enormous near odd multiples of `PI/2`. */
fun tan(n: Number): Double = kotlin.math.tan(n.toDouble())

/** Arc sine of [n], in radians over `-PI/2..PI/2`. `NaN` outside the domain `-1..1`. */
fun asin(n: Number): Double = kotlin.math.asin(n.toDouble())

/** Arc cosine of [n], in radians over `0..PI`. `NaN` outside the domain `-1..1`. */
fun acos(n: Number): Double = kotlin.math.acos(n.toDouble())

/** Arc tangent of [n], in radians over `-PI/2..PI/2`. For a full-circle angle use [atan2]. */
fun atan(n: Number): Double = kotlin.math.atan(n.toDouble())

/**
 * The angle of the vector `(x, y)` in radians over `-PI..PI`, measured counter-clockwise from the
 * positive x axis.
 *
 * Note the argument order — **y first** — which is the C convention and the opposite of how you say
 * it out loud. Unlike `atan(y / x)` this keeps the quadrant, so it is the one to use for "which way
 * is this pointing".
 */
fun atan2(y: Number, x: Number): Double = kotlin.math.atan2(y.toDouble(), x.toDouble())

/** Hyperbolic sine of [n]. Unbounded, and overflows to infinity somewhere past `n = 710`. */
fun sinh(n: Number): Double = kotlin.math.sinh(n.toDouble())

/** Hyperbolic cosine of [n]; `1` at zero and rising either side of it. */
fun cosh(n: Number): Double = kotlin.math.cosh(n.toDouble())

/**
 * Hyperbolic tangent of [n], in `-1..1`.
 *
 * The cheap smooth squashing function: linear near zero, saturating by about `|n| = 3`. Handy for
 * keeping an unbounded signal inside a range without a hard clamp.
 */
fun tanh(n: Number): Double = kotlin.math.tanh(n.toDouble())

/** Inverse hyperbolic sine of [n]. Defined for every real input. */
fun asinh(n: Number): Double = kotlin.math.asinh(n.toDouble())

/** Inverse hyperbolic cosine of [n], `>= 0`. `NaN` below `n = 1`. */
fun acosh(n: Number): Double = kotlin.math.acosh(n.toDouble())

/** Inverse hyperbolic tangent of [n]. Infinite at `±1` and `NaN` outside it — the inverse of [tanh]. */
fun atanh(n: Number): Double = kotlin.math.atanh(n.toDouble())
