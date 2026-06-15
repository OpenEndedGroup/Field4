/**
 * ComplexTest.kt
 * Demonstrates various Kotlin features for compiler testing.
 */

import kotlin.math.*
import kotlin.random.Random

// Type alias
public typealias StringList = List<String>

// Inline class
@JvmInline
value class Meter(val value: Double) {
    operator fun plus(other: Meter) = Meter(this.value + other.value)
    operator fun compareTo(other: Meter): Int = this.value.compareTo(other.value)
    override fun toString() = "${'$'}value m"
}

// Sealed class hierarchy
sealed class Expr {
    data class Const(val number: Double): Expr()
    data class Sum(val e1: Expr, val e2: Expr): Expr()
    object NotANumber: Expr()
}

// Extension function
fun Expr.evaluate(): Double = when (this) {
    is Expr.Const    -> number
    is Expr.Sum      -> e1.evaluate() + e2.evaluate()
    Expr.NotANumber  -> Double.NaN
}

// Generic class with delegation
class CountingSet<T>(private val inner: MutableCollection<T> = mutableListOf()) : MutableCollection<T> by inner {
    var objectsAdded = 0
        private set

    override fun add(element: T): Boolean {
        objectsAdded++
        return inner.add(element)
    }

    override fun addAll(elements: Collection<T>): Boolean {
        objectsAdded += elements.size
        return inner.addAll(elements)
    }
}

// Higher-order inline function
inline fun <T> measureTimeMillis(block: () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    val result = block()
    val end = System.currentTimeMillis()
    return result to (end - start)
}

// Data class with default parameters and named args
data class Person(
    val name: String,
    val age: Int = 0,
    val nicknames: StringList = listOf()
) {
    fun greet(): String = "Hello, my name is $name" +
        if (nicknames.isNotEmpty()) " (also known as ${nicknames.joinToString()})" else ""
}

// Object declaration
object Utils {
    fun randomExpr(depth: Int): Expr = if (depth <= 0) {
        Expr.Const(Random.nextDouble())
    } else {
        Expr.Sum(randomExpr(depth - 1), randomExpr(depth - 1))
    }

    tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}

// Main function demonstrating features
fun main(args: Array<String>) {
    // Inline class and operator
    val d1 = Meter(1.0)
    val d2 = Meter(2.5)
    println("Sum: ${'$'}{d1 + d2}, comparison d1 < d2: ${'$'}{d1 < d2}")

    // Sealed class evaluation
    val expr = Expr.Sum(Expr.Const(1.0), Expr.Sum(Expr.Const(2.0), Expr.Const(3.0)))
    println("Expr: $expr = ${'$'}{expr.evaluate()}")

    // CountingSet
    val cs = CountingSet<String>()
    cs.addAll(listOf("a", "b", "c"))
    println("Added ${'$'}{cs.objectsAdded} items to CountingSet: $cs")

    // measureTimeMillis
    val (person, time) = measureTimeMillis {
        Person(name = "Alice", nicknames = listOf("Al", "Lee"))
    }
    println("Created $person in $time ms")

    // Utils functions
    println("Random Expr evaluation: ${'$'}{Utils.randomExpr(3).evaluate()}")
    println("GCD of 42 and 56: ${'$'}{Utils.gcd(42, 56)}")

    // Collection operations
    val ints = (1..20).toList()
    val evens = ints.filter { it % 2 == 0 }.map { it * it }
    println("Squared evens: $evens")

    // Regex and destructuring
    val regex = "(\\w+)-(\\d+)".toRegex()
    val input = "item-123"
    regex.matchEntire(input)?.destructured?.let { (word, number) ->
        println("Destructured: word=$word, number=$number")
    }

    // Sequence example
    val fibs = generateSequence(Pair(0, 1)) { (a, b) -> Pair(b, a + b) }
        .map { it.first }
        .take(10)
    println("Fibonacci: ${'$'}{fibs.toList()}")
}