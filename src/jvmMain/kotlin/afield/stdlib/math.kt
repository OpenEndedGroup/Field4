@file:afield.CompletionCategory("Math")

package afield.stdlib

fun sin(n: Number): Double = kotlin.math.sin(n.toDouble())
fun cos(n: Number): Double = kotlin.math.cos(n.toDouble())
fun tan(n: Number): Double = kotlin.math.tan(n.toDouble())

fun asin(n: Number): Double = kotlin.math.asin(n.toDouble())
fun acos(n: Number): Double = kotlin.math.acos(n.toDouble())
fun atan(n: Number): Double = kotlin.math.atan(n.toDouble())
fun atan2(y: Number, x: Number): Double = kotlin.math.atan2(y.toDouble(), x.toDouble())

fun sinh(n: Number): Double = kotlin.math.sinh(n.toDouble())
fun cosh(n: Number): Double = kotlin.math.cosh(n.toDouble())
fun tanh(n: Number): Double = kotlin.math.tanh(n.toDouble())

fun asinh(n: Number): Double = kotlin.math.asinh(n.toDouble())
fun acosh(n: Number): Double = kotlin.math.acosh(n.toDouble())
fun atanh(n: Number): Double = kotlin.math.atanh(n.toDouble())
