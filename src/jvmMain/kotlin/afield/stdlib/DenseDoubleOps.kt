@file:afield.CompletionCategory("Math")

package afield.stdlib

import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.VectorOperators

/** Allocation-free SIMD operations on equal-length dense double arrays, with scalar tails.
 * Reductions use lane-wise accumulation, so last-bit rounding can differ from a scalar sum.
 * Destination arrays may alias either input for elementwise operations.
 */
object DenseDoubleOps {
    private val species = DoubleVector.SPECIES_PREFERRED

    /** Sum of elementwise products; neither input is modified. */
    fun dot(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        var acc = DoubleVector.zero(species)
        val end = species.loopBound(a.size)
        var i = 0
        while (i < end) {
            acc = acc.add(DoubleVector.fromArray(species, a, i).mul(DoubleVector.fromArray(species, b, i)))
            i += species.length()
        }
        var sum = acc.reduceLanes(VectorOperators.ADD)
        while (i < a.size) { sum += a[i] * b[i]; i++ }
        return sum
    }

    /** Overwrites [out] with `a + factor * b`, including when [out] is one of the inputs. */
    fun combine(out: DoubleArray, a: DoubleArray, factor: Double, b: DoubleArray) {
        require(out.size == a.size && a.size == b.size)
        val end = species.loopBound(a.size)
        var i = 0
        while (i < end) {
            DoubleVector.fromArray(species, a, i).add(DoubleVector.fromArray(species, b, i).mul(factor)).intoArray(out, i)
            i += species.length()
        }
        while (i < a.size) { out[i] = a[i] + factor * b[i]; i++ }
    }

    /** Multiplies every element of [a] by [factor] in place. */
    fun scale(a: DoubleArray, factor: Double) {
        val end = species.loopBound(a.size)
        var i = 0
        while (i < end) {
            DoubleVector.fromArray(species, a, i).mul(factor).intoArray(a, i)
            i += species.length()
        }
        while (i < a.size) { a[i] *= factor; i++ }
    }
}
