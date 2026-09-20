@file:afield.CompletionCategory("Patterns")

package afield.four.synth

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

/**
 * [MarfSpace] instances for Field's generated linalg vectors, so a reader can emit a position, a
 * colour or a set of material parameters instead of one number.
 *
 * Everything in `Marf.kt` is generic over the value type already; this file supplies the arithmetic
 * for the three types most stage material actually is. The reason it is a file of its own is the
 * dependency direction: the core layer needs nothing but `PatPattern`, and keeping `afield.stdlib.linalg`
 * out of it means a reader over some other type costs one object rather than an edit to the layer.
 *
 *     val trajectory = marfBankIn(
 *         MarfVector3f,
 *         stageTo(Vector3f(0f, 0f, 0f), 1.0),
 *         stageTo(Vector3f(1f, 0.5f, 0f), 0.5, MarfCurve.Smooth),
 *         stageTo(Vector3f(0f, 1f, 1f), 2.0, MarfCurve.Exp(3.0))
 *     )
 *
 *     val path = trajectory.read(pat("<0 1 2>").nums(), fields = MarfFieldClock.STAGES)
 *
 *     path.at(3.5).value              // a Vector3f
 *     path.signal { it.x.toDouble() } // and any component as an ordinary signal
 *
 * #### Every instance copies before it writes
 *
 * This matters more here than the arithmetic does. In `afield.stdlib.linalg`, `add(other)`,
 * `mul(scalar)`, `sub(other)` and `normalize()` are **in-place members** that return the receiver,
 * and `/` resolves to the mutating member operator rather than to the allocating extension. A stage
 * target lives in a bank and is read once per stage for as long as the reader runs, so a single
 * in-place write would corrupt the material rather than produce a wrong frame:
 *
 *     a.add(b)                // WRONG: mutates a, which is the bank's stored target
 *     a.add(b, Vector3f())    // right: fresh destination, a and b untouched
 *
 * Every method below uses the out-parameter form. `MarfVectorSpaceTest` reads a bank twice and
 * requires the second pass to agree with the first, which is the property that a mutating
 * implementation breaks and a type check does not.
 */

/** 2-component vector stages. Screen positions, UV coordinates, a pan/tilt pair. */
object MarfVector2f : MarfSpace<Vector2f> {
    override val zero: Vector2f get() = Vector2f()
    override fun add(a: Vector2f, b: Vector2f): Vector2f = a.add(b, Vector2f())
    override fun sub(a: Vector2f, b: Vector2f): Vector2f = a.sub(b, Vector2f())
    override fun scale(a: Vector2f, k: Double): Vector2f = a.mul(k.toFloat(), Vector2f())
    override fun lerp(from: Vector2f, to: Vector2f, fraction: Double): Vector2f =
        from.lerp(to, fraction.toFloat(), Vector2f())
    override fun label(value: Vector2f): String = "${fmtComponent(value.x)},${fmtComponent(value.y)}"
}

/** 3-component vector stages. Positions, directions, RGB. */
object MarfVector3f : MarfSpace<Vector3f> {
    override val zero: Vector3f get() = Vector3f()
    override fun add(a: Vector3f, b: Vector3f): Vector3f = a.add(b, Vector3f())
    override fun sub(a: Vector3f, b: Vector3f): Vector3f = a.sub(b, Vector3f())
    override fun scale(a: Vector3f, k: Double): Vector3f = a.mul(k.toFloat(), Vector3f())
    override fun lerp(from: Vector3f, to: Vector3f, fraction: Double): Vector3f =
        from.lerp(to, fraction.toFloat(), Vector3f())
    override fun label(value: Vector3f): String =
        "${fmtComponent(value.x)},${fmtComponent(value.y)},${fmtComponent(value.z)}"
}

/** 4-component vector stages. RGBA, a quaternion's storage, four packed material parameters. */
object MarfVector4f : MarfSpace<Vector4f> {
    override val zero: Vector4f get() = Vector4f()
    override fun add(a: Vector4f, b: Vector4f): Vector4f = a.add(b, Vector4f())
    override fun sub(a: Vector4f, b: Vector4f): Vector4f = a.sub(b, Vector4f())
    override fun scale(a: Vector4f, k: Double): Vector4f = a.mul(k.toFloat(), Vector4f())
    override fun lerp(from: Vector4f, to: Vector4f, fraction: Double): Vector4f =
        from.lerp(to, fraction.toFloat(), Vector4f())
    override fun label(value: Vector4f): String =
        "${fmtComponent(value.x)},${fmtComponent(value.y)},${fmtComponent(value.z)},${fmtComponent(value.w)}"

    /**
     * Note on quaternions: a `Vector4f` reader over four quaternion components interpolates
     * **componentwise and then does not renormalise**, which is `nlerp` without the `n` and is not a
     * rotation path. Read the four numbers and normalise at the point of use, or write a reader
     * whose blend is `slerp` — see the note on vector spaces in [MarfSpace].
     */
    const val QUATERNIONS_ARE_NOT_A_VECTOR_SPACE: String =
        "componentwise interpolation of a quaternion needs renormalising before it is a rotation"
}

/** Short, locale-free component text, so a stage label stays readable in a figure. */
private fun fmtComponent(v: Float): String = patFormatNumber(Math.round(v * 1000.0) / 1000.0)
