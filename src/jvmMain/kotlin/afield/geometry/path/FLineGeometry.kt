package afield.geometry.path

import afield.graphics.three.three

import afield.ui.visualization.lines

import afield.math.gp.*
import afield.ai.learning.*
import afield.ai.retrieval.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.animation.motion.*
import afield.app.commands.*
import afield.app.diagnostics.*
import afield.audio.analysis.*
import afield.audio.synth.*
import afield.data.collections.*
import afield.data.formats.*
import afield.data.graph.*
import afield.data.sorting.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.geometry.mesh.*
import afield.geometry.path.*
import afield.geometry.spatial.*
import afield.graphics.color.*
import afield.graphics.interaction.*
import afield.graphics.render.*
import afield.graphics.shader.*
import afield.graphics.text.*
import afield.graphics.vulkan.buffers.*
import afield.io.osc.*
import afield.io.process.*
import afield.lang.lambda.*
import afield.lang.python.*
import afield.lang.remotejs.*
import afield.lang.stacksl.*
import afield.math.autodiff.*
import afield.math.control.*
import afield.math.estimation.*
import afield.math.functions.*
import afield.math.linalg.*
import afield.math.optimization.*
import afield.math.statistics.*
import afield.math.units.*
import afield.media.image.*
import afield.media.video.*
import afield.runtime.execution.*
import afield.runtime.languages.*
import afield.runtime.lifecycle.*
import afield.simulation.agents.*
import afield.simulation.automata.*
import afield.simulation.particles.*
import afield.study.build.*
import afield.study.evidence.*
import afield.study.search.*
import afield.study.source.*
import afield.study.ui.*
import afield.ui.controls.*
import afield.ui.output.*
import afield.ui.visualization.*
import afield.geometry.path.java2d.FLinesAndJavaShapes
import afield.geometry.path.java2d.FLinesAndJavaShapes.CubicSegment3
import afield.math.linalg.Quaternionf
import afield.math.linalg.plus
import smile.data.DataFrame
import smile.data.formula.Formula
import smile.regression.OLS
import kotlin.math.*

/**
 * Comprehensive computational geometry utilities for FLine operations implemented as extension functions.
 * Provides transformations, projections, intersections, decompositions, curvature analyses,
 * tangents, osculating planes, offsets, and a rich set of 2D and 3D geometric algorithms.
 *
 * Functions that return new FLine instances use a "by" prefix to indicate non-mutating operations:
 * - `byWaving()` instead of `wave()`
 * - `byOffsetting()` instead of `offset()`
 */

// ==================== LIVE LIST VECTOR OPS ====================

private inline fun <A, B, C> zipSame(a: List<A>, b: List<B>, op: (A, B) -> C): List<C> {
    require(a.size == b.size) { "List sizes must match: ${a.size} != ${b.size}" }
    return List(a.size) { i -> op(a[i], b[i]) }
}

private fun Vec2.mulEach(b: Vec2) = Vec2(x * b.x, y * b.y)
private fun Vec3.mulEach(b: Vec3) = Vec3(x * b.x, y * b.y, z * b.z)
private fun Vec2.divEach(b: Vec2) = Vec2(x / b.x, y / b.y)
private fun Vec3.divEach(b: Vec3) = Vec3(x / b.x, y / b.y, z / b.z)
private fun Vec2.plusScalar(s: Float) = Vec2(x + s, y + s)
private fun Vec3.plusScalar(s: Float) = Vec3(x + s, y + s, z + s)
private fun Vec2.minusScalar(s: Float) = Vec2(x - s, y - s)
private fun Vec3.minusScalar(s: Float) = Vec3(x - s, y - s, z - s)
private fun Vec2.scalarMinus(s: Float) = Vec2(s - x, s - y)
private fun Vec3.scalarMinus(s: Float) = Vec3(s - x, s - y, s - z)
private fun Vec2.scalarDiv(s: Float) = Vec2(s / x, s / y)
private fun Vec3.scalarDiv(s: Float) = Vec3(s / x, s / y, s / z)
private fun Vec3.plus(v: Vec2) = Vec3(x + v.x, y + v.y, z)
private fun Vec3.minus(v: Vec2) = Vec3(x - v.x, y - v.y, z)
private fun Vec3.mul(v: Vec2) = Vec3(x * v.x, y * v.y, z)
private fun Vec3.div(v: Vec2) = Vec3(x / v.x, y / v.y, z)
private fun Vec2.plus(v: Vec3) = Vec3(x + v.x, y + v.y, v.z)
private fun Vec2.minus(v: Vec3) = Vec3(x - v.x, y - v.y, -v.z)
private fun Vec2.mul(v: Vec3) = Vec3(x * v.x, y * v.y, 0f)
private fun Vec2.div(v: Vec3) = Vec3(x / v.x, y / v.y, 0f)

typealias Vec2Anchor = (List<Vec2>) -> Vec2
typealias Vec3Anchor = (List<Vec3>) -> Vec3

enum class ListAnchor { Start, End, Midpoint, Center }
data class FractionAlongAnchor(val t: Number)

object Anchors2 {
    val start: Vec2Anchor = { it.firstOrNull()?.let(::Vec2) ?: Vec2() }
    val end: Vec2Anchor = { it.lastOrNull()?.let(::Vec2) ?: Vec2() }
    val midpoint: Vec2Anchor = { if (it.isEmpty()) Vec2() else Vec2(it.first()).add(it.last()).mul(0.5f) }
    val center: Vec2Anchor = { it.center() }
    fun fractionAlong(t: Number): Vec2Anchor = { points ->
        if (points.isEmpty()) Vec2()
        else if (points.size == 1) Vec2(points[0])
        else {
            val f = t.toFloat().coerceIn(0f, 1f) * (points.size - 1)
            val a = floor(f).toInt()
            val b = ceil(f).toInt()
            Vec2(points[a]).lerp(points[b], f - a)
        }
    }
}

object Anchors3 {
    val start: Vec3Anchor = { it.firstOrNull()?.let(::Vec3) ?: Vec3() }
    val end: Vec3Anchor = { it.lastOrNull()?.let(::Vec3) ?: Vec3() }
    val midpoint: Vec3Anchor = { if (it.isEmpty()) Vec3() else Vec3(it.first()).add(it.last()).mul(0.5f) }
    val center: Vec3Anchor = { it.center() }
    fun fractionAlong(t: Number): Vec3Anchor = { points ->
        if (points.isEmpty()) Vec3()
        else if (points.size == 1) Vec3(points[0])
        else {
            val f = t.toFloat().coerceIn(0f, 1f) * (points.size - 1)
            val a = floor(f).toInt()
            val b = ceil(f).toInt()
            Vec3(points[a]).lerp(points[b], f - a)
        }
    }
}

val start = ListAnchor.Start
val end = ListAnchor.End
val midpoint = ListAnchor.Midpoint
val center = ListAnchor.Center
fun fractionAlong(t: Number) = FractionAlongAnchor(t)

private fun List<Vec2>.anchor(a: ListAnchor): Vec2 = when (a) {
    ListAnchor.Start -> Anchors2.start(this)
    ListAnchor.End -> Anchors2.end(this)
    ListAnchor.Midpoint -> Anchors2.midpoint(this)
    ListAnchor.Center -> Anchors2.center(this)
}

private fun List<Vec3>.anchor(a: ListAnchor): Vec3 = when (a) {
    ListAnchor.Start -> Anchors3.start(this)
    ListAnchor.End -> Anchors3.end(this)
    ListAnchor.Midpoint -> Anchors3.midpoint(this)
    ListAnchor.Center -> Anchors3.center(this)
}

private fun List<Vec2>.anchor(a: FractionAlongAnchor): Vec2 = Anchors2.fractionAlong(a.t)(this)
private fun List<Vec3>.anchor(a: FractionAlongAnchor): Vec3 = Anchors3.fractionAlong(a.t)(this)

// A list of points broadcasts arithmetic against a number and against the other vector type:
// `pts * 2` scales every point and `pts + vec(1, 2, 3)` lifts a List<Vec2> into 3D. There is
// deliberately no `+` or `-` between a List<Vec2> and a Vec2 or List<Vec2> (or a List<Vec3> and
// a Vec3 or List<Vec3>): those are stdlib's, `pts + p` appends and `a + b` concatenates, as any
// Kotlin reader expects. Shadowing them made `samples + samples.last()` move every point and
// `controlPoints + wrap` throw. To move a list, `pts.map { it + v }`.
@JvmName("plusVec2ListScalar")
operator fun List<Vec2>.plus(s: Number): List<Vec2> = map { it.plusScalar(s.toFloat()) }
@JvmName("minusVec2ListScalar")
operator fun List<Vec2>.minus(s: Number): List<Vec2> = map { it.minusScalar(s.toFloat()) }
@JvmName("timesVec2ListScalar")
operator fun List<Vec2>.times(s: Number): List<Vec2> = map { Vec2(it).mul(s.toFloat()) }
@JvmName("divVec2ListScalar")
operator fun List<Vec2>.div(s: Number): List<Vec2> = map { Vec2(it).div(s.toFloat()) }
@JvmName("timesVec2ListVec2")
operator fun List<Vec2>.times(v: Vec2): List<Vec2> = map { it.mulEach(v) }
@JvmName("divVec2ListVec2")
operator fun List<Vec2>.div(v: Vec2): List<Vec2> = map { it.divEach(v) }
@JvmName("plusVec2ListVec3")
operator fun List<Vec2>.plus(v: Vec3): List<Vec3> = map { it.plus(v) }
@JvmName("minusVec2ListVec3")
operator fun List<Vec2>.minus(v: Vec3): List<Vec3> = map { it.minus(v) }
@JvmName("timesVec2ListVec3")
operator fun List<Vec2>.times(v: Vec3): List<Vec3> = map { it.mul(v) }
@JvmName("divVec2ListVec3")
operator fun List<Vec2>.div(v: Vec3): List<Vec3> = map { it.div(v) }
@JvmName("timesVec2ListVec2List")
operator fun List<Vec2>.times(v: List<Vec2>): List<Vec2> = zipSame(this, v) { a, b -> a.mulEach(b) }
@JvmName("divVec2ListVec2List")
operator fun List<Vec2>.div(v: List<Vec2>): List<Vec2> = zipSame(this, v) { a, b -> a.divEach(b) }
@JvmName("plusVec2ListVec3List")
operator fun List<Vec2>.plus(v: List<Vec3>): List<Vec3> = zipSame(this, v) { a, b -> a.plus(b) }
@JvmName("minusVec2ListVec3List")
operator fun List<Vec2>.minus(v: List<Vec3>): List<Vec3> = zipSame(this, v) { a, b -> a.minus(b) }
@JvmName("timesVec2ListVec3List")
operator fun List<Vec2>.times(v: List<Vec3>): List<Vec3> = zipSame(this, v) { a, b -> a.mul(b) }
@JvmName("divVec2ListVec3List")
operator fun List<Vec2>.div(v: List<Vec3>): List<Vec3> = zipSame(this, v) { a, b -> a.div(b) }
@JvmName("plusVec2ListFloatList")
operator fun List<Vec2>.plus(v: List<Float>): List<Vec2> = zipSame(this, v) { a, b -> a.plusScalar(b) }
@JvmName("minusVec2ListFloatList")
operator fun List<Vec2>.minus(v: List<Float>): List<Vec2> = zipSame(this, v) { a, b -> a.minusScalar(b) }
@JvmName("timesVec2ListFloatList")
operator fun List<Vec2>.times(v: List<Float>): List<Vec2> = zipSame(this, v) { a, b -> Vec2(a).mul(b) }
@JvmName("divVec2ListFloatList")
operator fun List<Vec2>.div(v: List<Float>): List<Vec2> = zipSame(this, v) { a, b -> Vec2(a).div(b) }
@JvmName("scalarPlusVec2List")
operator fun Number.plus(v: List<Vec2>): List<Vec2> = v + this
@JvmName("scalarMinusVec2List")
operator fun Number.minus(v: List<Vec2>): List<Vec2> = v.map { it.scalarMinus(toFloat()) }
@JvmName("scalarTimesVec2List")
operator fun Number.times(v: List<Vec2>): List<Vec2> = v * this
@JvmName("scalarDivVec2List")
operator fun Number.div(v: List<Vec2>): List<Vec2> = v.map { it.scalarDiv(toFloat()) }

@JvmName("plusVec3ListScalar")
operator fun List<Vec3>.plus(s: Number): List<Vec3> = map { it.plusScalar(s.toFloat()) }
@JvmName("minusVec3ListScalar")
operator fun List<Vec3>.minus(s: Number): List<Vec3> = map { it.minusScalar(s.toFloat()) }
@JvmName("timesVec3ListScalar")
operator fun List<Vec3>.times(s: Number): List<Vec3> = map { Vec3(it).mul(s.toFloat()) }
@JvmName("divVec3ListScalar")
operator fun List<Vec3>.div(s: Number): List<Vec3> = map { Vec3(it).div(s.toFloat()) }
@JvmName("timesVec3ListVec3")
operator fun List<Vec3>.times(v: Vec3): List<Vec3> = map { it.mulEach(v) }
@JvmName("divVec3ListVec3")
operator fun List<Vec3>.div(v: Vec3): List<Vec3> = map { it.divEach(v) }
@JvmName("plusVec3ListVec2")
operator fun List<Vec3>.plus(v: Vec2): List<Vec3> = map { it.plus(v) }
@JvmName("minusVec3ListVec2")
operator fun List<Vec3>.minus(v: Vec2): List<Vec3> = map { it.minus(v) }
@JvmName("timesVec3ListVec2")
operator fun List<Vec3>.times(v: Vec2): List<Vec3> = map { it.mul(v) }
@JvmName("divVec3ListVec2")
operator fun List<Vec3>.div(v: Vec2): List<Vec3> = map { it.div(v) }
@JvmName("timesVec3ListVec3List")
operator fun List<Vec3>.times(v: List<Vec3>): List<Vec3> = zipSame(this, v) { a, b -> a.mulEach(b) }
@JvmName("divVec3ListVec3List")
operator fun List<Vec3>.div(v: List<Vec3>): List<Vec3> = zipSame(this, v) { a, b -> a.divEach(b) }
@JvmName("plusVec3ListVec2List")
operator fun List<Vec3>.plus(v: List<Vec2>): List<Vec3> = zipSame(this, v) { a, b -> a.plus(b) }
@JvmName("minusVec3ListVec2List")
operator fun List<Vec3>.minus(v: List<Vec2>): List<Vec3> = zipSame(this, v) { a, b -> a.minus(b) }
@JvmName("timesVec3ListVec2List")
operator fun List<Vec3>.times(v: List<Vec2>): List<Vec3> = zipSame(this, v) { a, b -> a.mul(b) }
@JvmName("divVec3ListVec2List")
operator fun List<Vec3>.div(v: List<Vec2>): List<Vec3> = zipSame(this, v) { a, b -> a.div(b) }
@JvmName("plusVec3ListFloatList")
operator fun List<Vec3>.plus(v: List<Float>): List<Vec3> = zipSame(this, v) { a, b -> a.plusScalar(b) }
@JvmName("minusVec3ListFloatList")
operator fun List<Vec3>.minus(v: List<Float>): List<Vec3> = zipSame(this, v) { a, b -> a.minusScalar(b) }
@JvmName("timesVec3ListFloatList")
operator fun List<Vec3>.times(v: List<Float>): List<Vec3> = zipSame(this, v) { a, b -> Vec3(a).mul(b) }
@JvmName("divVec3ListFloatList")
operator fun List<Vec3>.div(v: List<Float>): List<Vec3> = zipSame(this, v) { a, b -> Vec3(a).div(b) }
@JvmName("scalarPlusVec3List")
operator fun Number.plus(v: List<Vec3>): List<Vec3> = v + this
@JvmName("scalarMinusVec3List")
operator fun Number.minus(v: List<Vec3>): List<Vec3> = v.map { it.scalarMinus(toFloat()) }
@JvmName("scalarTimesVec3List")
operator fun Number.times(v: List<Vec3>): List<Vec3> = v * this
@JvmName("scalarDivVec3List")
operator fun Number.div(v: List<Vec3>): List<Vec3> = v.map { it.scalarDiv(toFloat()) }

@JvmName("dotVec2ListVec2")
infix fun List<Vec2>.dot(v: Vec2): List<Float> = map { it.dot(v) }
@JvmName("dotVec2ListVec2List")
infix fun List<Vec2>.dot(v: List<Vec2>): List<Float> = zipSame(this, v) { a, b -> a.dot(b) }
@JvmName("dotVec3ListVec3")
infix fun List<Vec3>.dot(v: Vec3): List<Float> = map { it.dot(v) }
@JvmName("dotVec3ListVec3List")
infix fun List<Vec3>.dot(v: List<Vec3>): List<Float> = zipSame(this, v) { a, b -> a.dot(b) }

@JvmName("crossVec2ListVec2")
infix fun List<Vec2>.cross(v: Vec2): List<Float> = map { it.x * v.y - it.y * v.x }
@JvmName("crossVec2ListVec2List")
infix fun List<Vec2>.cross(v: List<Vec2>): List<Float> = zipSame(this, v) { a, b -> a.x * b.y - a.y * b.x }
@JvmName("crossVec3ListVec3")
infix fun List<Vec3>.cross(v: Vec3): List<Vec3> = map { Vec3(it).cross(v) }
@JvmName("crossVec3ListVec3List")
infix fun List<Vec3>.cross(v: List<Vec3>): List<Vec3> = zipSame(this, v) { a, b -> Vec3(a).cross(b) }

@JvmName("perpendicularVec2List")
fun List<Vec2>.perpendicular(): List<Vec2> = map { Vec2(-it.y, it.x) }
@JvmName("perpVec2List")
fun List<Vec2>.perp(): List<Vec2> = perpendicular()
@JvmName("perpendicularVec3List")
fun List<Vec3>.perpendicular(): List<Vec3> = map { Vec3(-it.y, it.x, it.z) }
@JvmName("perpVec3List")
fun List<Vec3>.perp(): List<Vec3> = perpendicular()

@JvmName("rotateVec2ListShort")
fun List<Vec2>.rot(angle: Number, center: Vec2Anchor = Anchors2.center): List<Vec2> {
    val about = center(this)
    val c = cos(angle.toFloat())
    val s = sin(angle.toFloat())
    return map {
        val x = it.x - about.x
        val y = it.y - about.y
        Vec2(about.x + x * c - y * s, about.y + x * s + y * c)
    }
}
@JvmName("rotateVec2ListShortAboutPoint")
fun List<Vec2>.rot(angle: Number, about: Vec2): List<Vec2> = rot(angle) { Vec2(about) }
@JvmName("rotateVec2ListShortAboutAnchor")
fun List<Vec2>.rot(angle: Number, about: ListAnchor): List<Vec2> = rot(angle, anchor(about))
@JvmName("rotateVec2ListShortAboutFraction")
fun List<Vec2>.rot(angle: Number, about: FractionAlongAnchor): List<Vec2> = rot(angle, anchor(about))

@JvmName("rotateVec3ListShort")
fun List<Vec3>.rot(angle: Number, axis: Vec3 = vec(0f, 0f, 1f), center: Vec3Anchor = Anchors3.center): List<Vec3> {
    val about = center(this)
    val q = Quaternionf().setAngleAxis(angle.toFloat(), axis.x, axis.y, axis.z)
    return map { q.transform(Vec3(it).sub(about)).add(about) }
}
@JvmName("rotateVec3ListShortAnchorDefaultAxis")
fun List<Vec3>.rot(angle: Number, center: Vec3Anchor): List<Vec3> = rot(angle, vec(0f, 0f, 1f), center)
@JvmName("rotateVec3ListShortAboutPoint")
fun List<Vec3>.rot(angle: Number, axis: Vec3 = vec(0f, 0f, 1f), about: Vec3): List<Vec3> = rot(angle, axis) { Vec3(about) }
@JvmName("rotateVec3ListShortAboutAnchor")
fun List<Vec3>.rot(angle: Number, about: ListAnchor): List<Vec3> = rot(angle, anchor(about))
@JvmName("rotateVec3ListShortAboutFraction")
fun List<Vec3>.rot(angle: Number, about: FractionAlongAnchor): List<Vec3> = rot(angle, anchor(about))
@JvmName("rotateVec3ListShortAxisAboutAnchor")
fun List<Vec3>.rot(angle: Number, axis: Vec3, about: ListAnchor): List<Vec3> = rot(angle, axis, anchor(about))
@JvmName("rotateVec3ListShortAxisAboutFraction")
fun List<Vec3>.rot(angle: Number, axis: Vec3, about: FractionAlongAnchor): List<Vec3> = rot(angle, axis, anchor(about))

@JvmName("scaleVec2ListScalarShort")
fun List<Vec2>.scale(scale: Number, center: Vec2Anchor = Anchors2.center): List<Vec2> {
    val about = center(this)
    return map { Vec2(about).add(Vec2(it).sub(about).mul(scale.toFloat())) }
}
@JvmName("scaleVec2ListScalarShortAboutPoint")
fun List<Vec2>.scale(scale: Number, about: Vec2): List<Vec2> = scale(scale) { Vec2(about) }
@JvmName("scaleVec2ListScalarShortAboutAnchor")
fun List<Vec2>.scale(scale: Number, about: ListAnchor): List<Vec2> = scale(scale, anchor(about))
@JvmName("scaleVec2ListScalarShortAboutFraction")
fun List<Vec2>.scale(scale: Number, about: FractionAlongAnchor): List<Vec2> = scale(scale, anchor(about))
@JvmName("scaleVec2ListVec2Short")
fun List<Vec2>.scale(scale: Vec2, center: Vec2Anchor = Anchors2.center): List<Vec2> {
    val about = center(this)
    return map { Vec2(about.x + (it.x - about.x) * scale.x, about.y + (it.y - about.y) * scale.y) }
}
@JvmName("scaleVec2ListVec2ShortAboutPoint")
fun List<Vec2>.scale(scale: Vec2, about: Vec2): List<Vec2> = scale(scale) { Vec2(about) }
@JvmName("scaleVec2ListVec2ShortAboutAnchor")
fun List<Vec2>.scale(scale: Vec2, about: ListAnchor): List<Vec2> = scale(scale, anchor(about))
@JvmName("scaleVec2ListVec2ShortAboutFraction")
fun List<Vec2>.scale(scale: Vec2, about: FractionAlongAnchor): List<Vec2> = scale(scale, anchor(about))

@JvmName("scaleVec3ListScalarShort")
fun List<Vec3>.scale(scale: Number, center: Vec3Anchor = Anchors3.center): List<Vec3> {
    val about = center(this)
    return map { Vec3(about).add(Vec3(it).sub(about).mul(scale.toFloat())) }
}
@JvmName("scaleVec3ListScalarShortAboutPoint")
fun List<Vec3>.scale(scale: Number, about: Vec3): List<Vec3> = scale(scale) { Vec3(about) }
@JvmName("scaleVec3ListScalarShortAboutAnchor")
fun List<Vec3>.scale(scale: Number, about: ListAnchor): List<Vec3> = scale(scale, anchor(about))
@JvmName("scaleVec3ListScalarShortAboutFraction")
fun List<Vec3>.scale(scale: Number, about: FractionAlongAnchor): List<Vec3> = scale(scale, anchor(about))
@JvmName("scaleVec3ListVec3Short")
fun List<Vec3>.scale(scale: Vec3, center: Vec3Anchor = Anchors3.center): List<Vec3> {
    val about = center(this)
    return map {
        Vec3(about.x + (it.x - about.x) * scale.x, about.y + (it.y - about.y) * scale.y, about.z + (it.z - about.z) * scale.z)
    }
}
@JvmName("scaleVec3ListVec3ShortAboutPoint")
fun List<Vec3>.scale(scale: Vec3, about: Vec3): List<Vec3> = scale(scale) { Vec3(about) }
@JvmName("scaleVec3ListVec3ShortAboutAnchor")
fun List<Vec3>.scale(scale: Vec3, about: ListAnchor): List<Vec3> = scale(scale, anchor(about))
@JvmName("scaleVec3ListVec3ShortAboutFraction")
fun List<Vec3>.scale(scale: Vec3, about: FractionAlongAnchor): List<Vec3> = scale(scale, anchor(about))

// ==================== 2D TRANSFORMATIONS ====================

/**
 * Rotate an FLine around a point in 2D (preserves Z coordinate).
 * @param angle rotation angle in radians
 * @param center center of rotation (defaults to origin)
 */
fun FLine.byRotating2D(angle: Float, center: Vec2 = vec(0f, 0f)): FLine {
    val cos = cos(angle)
    val sin = sin(angle)
    return this.transform { p ->
        val dx = p.x - center.x
        val dy = p.y - center.y
        vec(
            center.x + dx * cos - dy * sin,
            center.y + dx * sin + dy * cos,
            p.z
        )
    }
}

/**
 * Scale an FLine from a center point.
 * @param scale uniform scale factor
 * @param center center of scaling (defaults to origin)
 */
fun FLine.byScaling2D(scale: Float, center: Vec2 = vec(0f, 0f)): FLine {
    return this.transform { p ->
        vec(
            center.x + (p.x - center.x) * scale,
            center.y + (p.y - center.y) * scale,
            p.z
        )
    }
}

/**
 * Scale an FLine with different factors for X and Y.
 */
fun FLine.byScaling2D(scaleX: Float, scaleY: Float, center: Vec2 = vec(0f, 0f)): FLine {
    return this.transform { p ->
        vec(
            center.x + (p.x - center.x) * scaleX,
            center.y + (p.y - center.y) * scaleY,
            p.z
        )
    }
}

/**
 * Reflect an FLine across a line defined by two points.
 */
fun FLine.byReflecting2D(linePoint1: Vec2, linePoint2: Vec2): FLine {
    val dx = linePoint2.x - linePoint1.x
    val dy = linePoint2.y - linePoint1.y
    val lenSq = dx * dx + dy * dy

    return this.transform { p ->
        val px = p.x - linePoint1.x
        val py = p.y - linePoint1.y
        val t = (px * dx + py * dy) / lenSq
        val closestX = linePoint1.x + t * dx
        val closestY = linePoint1.y + t * dy
        vec(
            2 * closestX - p.x,
            2 * closestY - p.y,
            p.z
        )
    }
}

/**
 * Shear transformation in 2D.
 * @param shearX horizontal shear factor
 * @param shearY vertical shear factor
 */
fun FLine.byShearing2D(shearX: Float, shearY: Float): FLine {
    return this.transform { p ->
        vec(
            p.x + shearX * p.y,
            p.y + shearY * p.x,
            p.z
        )
    }
}

// ==================== 3D TRANSFORMATIONS ====================

/**
 * Rotate an FLine around an arbitrary 3D axis.
 * @param angle rotation angle in radians
 * @param axis rotation axis (will be normalized)
 * @param center center of rotation (defaults to origin)
 */
fun FLine.byRotating3D(angle: Float, axis: Vec3, center: Vec3 = vec(0f, 0f, 0f)): FLine {
    val q = Quaternionf().setAngleAxis(angle, axis.x, axis.y, axis.z)
    return this.transform { p ->
        val translated = Vec3(p).sub(center)
        q.transform(translated)
        translated.add(center)
    }
}

/**
 * Project this FLine onto a plane with perspective.
 * @param cameraPos position of the camera/eye
 * @param planePoint a point on the projection plane
 * @param planeNormal normal of the projection plane
 * @return a new perspective-projected FLine
 */
fun FLine.byPerspectiveProjecting(
    cameraPos: Vec3,
    planePoint: Vec3 = vec(0f, 0f, 0f),
    planeNormal: Vec3 = vec(0f, 0f, 1f)
): FLine {
    val normal = Vec3(planeNormal).normalize()
    val d = -normal.dot(planePoint)

    return this.transform { p ->
        val ray = Vec3(p).sub(cameraPos)
        val t = -(normal.dot(cameraPos) + d) / normal.dot(ray)
        if (t > 0) {
            Vec3(cameraPos).add(ray.mul(t))
        } else {
            Vec3(p) // Point behind camera, keep original
        }
    }
}

/**
 * Project this FLine onto a plane orthographically.
 * @return a new orthographically-projected FLine
 */
fun FLine.byOrthographicProjecting(
    planePoint: Vec3 = vec(0f, 0f, 0f),
    planeNormal: Vec3 = vec(0f, 0f, 1f)
): FLine {
    val normal = Vec3(planeNormal).normalize()

    return this.transform { p ->
        val v = Vec3(p).sub(planePoint)
        val dist = v.dot(normal)
        Vec3(p).sub(normal.mul(dist, Vec3()))
    }
}

// ==================== CURVE OFFSETTING ====================

/**
 * Create an offset curve at a specified distance from the original.
 * Uses normal vectors to offset the curve. For 2D curves, the offset is perpendicular to the curve.
 * @param distance offset distance (positive = right/outward, negative = left/inward)
 * @param samples number of samples per cubic segment
 */
fun FLine.byOffsetting(distance: Float, samples: Int = 50): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)
    var first = true

    for (i in 0..samples * (this.nodes.size - 1)) {
        val t = i.toFloat() / (samples * (this.nodes.size - 1))
        cursor.setT(t * (this.nodes.size - 1))

        val pos = cursor.position()
        val normal = cursor.normal2D()

        if (normal != null) {
            val offsetPos = vec(
                pos.x + normal.x * distance,
                pos.y + normal.y * distance,
                pos.z
            )

            if (first) {
                result.moveTo(offsetPos)
                first = false
            } else {
                result.lineTo(offsetPos)
            }
        }
    }

    if (this.nodes.last() is FLine.Close) {
        result.close()
    }

    return result
}

/**
 * Create parallel curves on both sides of the original curve.
 * @return Pair of FLines (left offset, right offset)
 */
fun FLine.byCreatingParallelCurves(distance: Float, samples: Int = 50): Pair<FLine, FLine> {
    return Pair(
        byOffsetting(-distance, samples),
        byOffsetting(distance, samples)
    )
}

// ==================== CURVE SMOOTHING ====================

/**
 * Smooth a curve using Gaussian kernel smoothing.
 * @param sigma smoothing strength (larger = smoother)
 * @param samples number of samples to use
 */
fun FLine.byGaussianSmoothing(
    sigma: Float,
    samples: Int = 100,
    sampler: FLineSampler? = null
): FLine {
    val points = this.samplePoints(samples, sampler = sampler)
    val smoothed = mutableListOf<Vec3>()
    val radius = (3 * sigma).toInt().coerceAtLeast(1)

    for (i in points.indices) {
        var sum = Vec3()
        var weightSum = 0f

        for (j in maxOf(0, i - radius)..minOf(points.size - 1, i + radius)) {
            val dist = abs(i - j).toFloat()
            val weight = exp(-(dist * dist) / (2 * sigma * sigma))
            sum.add(Vec3(points[j]).mul(weight))
            weightSum += weight
        }

        smoothed.add(sum.div(weightSum))
    }

    val result = FLine()
    if (smoothed.isNotEmpty()) {
        result.moveTo(smoothed[0])
        for (i in 1 until smoothed.size) {
            result.lineTo(smoothed[i])
        }
    }

    return result
}

/**
 * Simplify a curve using the Ramer-Douglas-Peucker algorithm.
 * @param epsilon maximum distance threshold for point elimination
 */
fun FLine.bySimplifying(epsilon: Float): FLine {
    // endPointOf, not `.to`: a Close stores no point and `.to` throws on one. Its end point is the
    // start of the subpath it closes, which is exactly the polyline vertex the closing segment
    // arrives at, so it belongs in the list rather than being skipped.
    val points = this.nodes.indices.map { Vec3(endPointOf(it)) }

    if (points.size < 3) return this.copy()

    val keep = rdpSimplify(points, epsilon)

    val result = FLine()
    if (keep.isNotEmpty()) {
        result.moveTo(keep[0])
        for (i in 1 until keep.size) {
            result.lineTo(keep[i])
        }
    }

    return result
}

private fun rdpSimplify(points: List<Vec3>, epsilon: Float): List<Vec3> {
    if (points.size < 3) return points

    var maxDist = 0f
    var maxIndex = 0

    for (i in 1 until points.size - 1) {
        val dist = perpendicularDistance(points[i], points[0], points[points.size - 1])
        if (dist > maxDist) {
            maxDist = dist
            maxIndex = i
        }
    }

    return if (maxDist > epsilon) {
        val left = rdpSimplify(points.subList(0, maxIndex + 1), epsilon)
        val right = rdpSimplify(points.subList(maxIndex, points.size), epsilon)
        buildList(left.size + right.size - 1) {
            addAll(left.dropLast(1))
            addAll(right)
        }
    } else {
        listOf(points.first(), points.last())
    }
}

private fun perpendicularDistance(point: Vec3, lineStart: Vec3, lineEnd: Vec3): Float {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y
    val dz = lineEnd.z - lineStart.z
    val lenSq = dx * dx + dy * dy + dz * dz

    if (lenSq == 0f) return point.distance(lineStart)

    val t = ((point.x - lineStart.x) * dx +
             (point.y - lineStart.y) * dy +
             (point.z - lineStart.z) * dz) / lenSq

    val tClamped = t.coerceIn(0f, 1f)
    val projection = Vec3(
        lineStart.x + tClamped * dx,
        lineStart.y + tClamped * dy,
        lineStart.z + tClamped * dz
    )

    return point.distance(projection)
}

// ==================== CURVATURE ANALYSIS ====================

/**
 * Calculate curvature at regular intervals along the curve.
 * @param samples number of sample points
 * @return list of (position, curvature) pairs
 */
fun FLine.curvatureAnalysis(samples: Int = 100): List<Pair<Vec3, Float>> {
    val result = mutableListOf<Pair<Vec3, Float>>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val tangent = cursor.tangent()
        val smooth = cursor.curvature()

        if (tangent != null) {
            if (smooth > 0f) {
                // on a curved segment the cursor knows the curvature exactly
                result.add(Pair(pos, smooth))
            } else {
                // Discrete curvature for polylines
                if (i < samples - 1) {
                    val nextT = (i + 1).toFloat() / (samples - 1) * (this.nodes.size - 1)
                    cursor.setT(nextT)
                    val nextPos = cursor.position()
                    val nextTangent = cursor.tangent()
                    cursor.setT(t)
                    if (nextTangent != null && tangent.lengthSquared() > 1e-12 && nextTangent.lengthSquared() > 1e-12) {
                        val dir1 = Vec3(tangent).normalize()
                        val dir2 = Vec3(nextTangent).normalize()
                        val dot = dir1.dot(dir2)
                        val cosAngle = dot.coerceIn(-1f, 1f)
                        val angle = acos(cosAngle)
                        val dist = pos.distance(nextPos)
                        val curvature = if (dist > 1e-6) angle / dist else 0f
                        result.add(Pair(pos, curvature))
                    } else {
                        result.add(Pair(pos, 0f))
                    }
                } else {
                    // Last point, copy from previous
                    val curvature = if (result.isNotEmpty()) result.last().second else 0f
                    result.add(Pair(pos, curvature))
                }
            }
        }
    }

    // Fill in NaN curvatures from adjacent samples
    for (i in result.indices) {
        if (result[i].second.isNaN()) {
            var replacement = Float.NaN
            // Find next non-NaN
            for (j in i + 1 until result.size) {
                if (!result[j].second.isNaN()) {
                    replacement = result[j].second
                    break
                }
            }
            if (replacement.isNaN() && i > 0) {
                // Find previous
                for (j in i - 1 downTo 0) {
                    if (!result[j].second.isNaN()) {
                        replacement = result[j].second
                        break
                    }
                }
            }
            if (!replacement.isNaN()) {
                result[i] = Pair(result[i].first, replacement)
            }
        }
    }

    return result
}

/**
 * Sample the curve with density proportional to curvature.
 * High curvature areas get more samples.
 * @param minSamples minimum number of samples
 * @param maxSamples maximum number of samples
 */
fun FLine.byCurvatureAdaptiveSampling(minSamples: Int = 20, maxSamples: Int = 200): FLine {
    val curvatures = curvatureAnalysis( minSamples)
    val totalCurvature = curvatures.sumOf { it.second.toDouble() }.toFloat()

    if (totalCurvature == 0f) {
        return this.copy()
    }

    val result = FLine()
    result.moveTo(curvatures.first().first)

    for (i in 1 until curvatures.size) {
        val curvature = curvatures[i].second
        val localSamples = (curvature / totalCurvature * maxSamples).toInt().coerceAtLeast(1)

        for (j in 0..localSamples) {
            val t = (i - 1 + j.toFloat() / localSamples) / (curvatures.size - 1) * (this.nodes.size - 1)
            val cursor = this.cursor()
            cursor.setT(t)
            result.lineTo(cursor.position())
        }
    }

    return result
}

/**
 * Find points of maximum curvature.
 * @param threshold minimum curvature to consider (filters noise)
 * @return list of high-curvature points
 */
fun FLine.findHighCurvaturePoints(threshold: Float = 0.1f, samples: Int = 100): List<Vec3> {
    val curvatures = curvatureAnalysis( samples)
    return curvatures.filter { it.second > threshold }.map { it.first }
}

// ==================== DECOMPOSITION ====================

/**
 * Compute the 2D convex hull of the curve using Graham scan.
 * @return FLine representing the convex hull
 */
fun FLine.byConvexHull2D(): FLine {
    // endPointOf, not `.to`: `.to` throws on a Close. A Close's end point is the start of the
    // subpath it closes, already in the list, so it adds nothing to the hull — but it must not
    // throw either.
    val points = this.nodes.indices.map { val p = endPointOf(it); Vec2(p.x, p.y) }.toMutableList()
    // A cubic bulges past its end points: sample inside each one so the hull wraps the curve.
    val cubicSamples = 24
    for (i in 1 until nodes.size) {
        val n = nodes[i]
        if (n is FLine.CubicTo) {
            val a = endPointOf(i - 1)
            for (k in 1 until cubicSamples) {
                val t = k / cubicSamples.toFloat()
                val u = 1 - t
                val w0 = u * u * u; val w1 = 3 * u * u * t; val w2 = 3 * u * t * t; val w3 = t * t * t
                points.add(Vec2(
                    w0 * a.x + w1 * n.c1.x + w2 * n.c2.x + w3 * n.to.x,
                    w0 * a.y + w1 * n.c1.y + w2 * n.c2.y + w3 * n.to.y,
                ))
            }
        }
    }

    if (points.size < 3) return this.copy()

    // Find the point with lowest y-coordinate (and leftmost if tie)
    val start = points.minByOrNull { it.y + it.x * 0.0001f }!!
    points.sortBy { p ->
        val dx = p.x - start.x
        val dy = p.y - start.y
        atan2(dy, dx)
    }

    val hull = mutableListOf<Vec2>()

    for (p in points) {
        while (hull.size >= 2) {
            val cross = crossProduct(hull[hull.size - 2], hull[hull.size - 1], p)
            if (cross <= 0) {
                hull.removeAt(hull.size - 1)
            } else {
                break
            }
        }
        hull.add(p)
    }

    val result = FLine()
    if (hull.isNotEmpty()) {
        result.moveTo(hull[0].x, hull[0].y)
        for (i in 1 until hull.size) {
            result.lineTo(hull[i].x, hull[i].y)
        }
        result.lineTo(hull[0].x, hull[0].y) // Close the hull
    }

    return result
}

private fun crossProduct(o: Vec2, a: Vec2, b: Vec2): Float {
    return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}

/**
 * Split curve at points of inflection (where curvature changes sign).
 */
fun FLine.bySplittingAtInflections(): List<FLine> {
    val cursor = this.cursor()
    val inflections = cursor.inflectionPointsXY()

    if (inflections.isEmpty()) return listOf(copy())

    return cursor.splitT(inflections)
}

// ==================== TANGENT AND NORMAL OPERATIONS ====================

/**
 * Generate tangent lines at regular intervals.
 * @param samples number of tangent lines to generate
 * @param length length of each tangent line
 */
fun FLine.byCreatingTangentLines(samples: Int = 20, length: Float = 10f): List<FLine> {
    val tangents = mutableListOf<FLine>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val tangent = cursor.tangent()

        if (tangent != null) {
            val normalized = Vec3(tangent).normalize()
            val start = Vec3(pos).sub(normalized.mul(length * 0.5f, Vec3()))
            val end = Vec3(pos).add(normalized.mul(length * 0.5f, Vec3()))

            val line = FLine()
            line.moveTo(start)
            line.lineTo(end)
            tangents.add(line)
        }
    }

    return tangents
}

/**
 * Generate normal lines at regular intervals.
 */
fun FLine.byCreatingNormalLines(samples: Int = 20, length: Float = 10f): List<FLine> {
    val normals = mutableListOf<FLine>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2D()

        if (normal != null) {
            val normalized = Vec2(normal).normalize()
            val start = vec(
                pos.x - normalized.x * length * 0.5f,
                pos.y - normalized.y * length * 0.5f,
                pos.z
            )
            val end = vec(
                pos.x + normalized.x * length * 0.5f,
                pos.y + normalized.y * length * 0.5f,
                pos.z
            )

            val line = FLine()
            line.moveTo(start)
            line.lineTo(end)
            normals.add(line)
        }
    }

    return normals
}

/**
 * Generate osculating circles at points along the curve.
 * The osculating circle has the same tangent and curvature as the curve at that point.
 */
fun FLine.byCreatingOsculatingCircles(samples: Int = 20, circleSegments: Int = 32): List<FLine> {
    val circles = mutableListOf<FLine>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val radiusVec = cursor.radiusOfCurvature()

        if (radiusVec != null && radiusVec.isFinite()) {
            val pos = cursor.position()
            val radius = radiusVec.length()
            val center = Vec3(pos).add(radiusVec)
            val T = cursor.tangent()

            if (T != null) {
                T.normalize()
                val N = Vec3(radiusVec).normalize()  // unit normal
                val B = T.cross(N, Vec3()).normalize()  // unit binormal

                val circle = FLine()
                for (j in 0..circleSegments) {
                    val angle = j.toFloat() / circleSegments * 2 * PI.toFloat()
                    val offset = cos(angle) * N + sin(angle) * T
                    val point = center + radius * offset
                    if (j == 0) {
                        circle.moveTo(point)
                    } else {
                        circle.lineTo(point)
                    }
                }
                circles.add(circle)
            }
        }
    }

    return circles
}

// ==================== BOOLEAN OPERATIONS ====================


/**
 * Union of two curves (using Java2D Area).
 *
 * Both operands are read as filled regions, whatever their `thickness` or `thicken`; pass
 * `stroke = true` to read a styled operand as the outline of its stroke instead.
 *
 * With `exact = true` the four boolean ops go through [booleanExact] instead: the result keeps the
 * operands' cubic pieces, cut exactly at the crossings, and falls back to this Area path only when
 * the exact engine cannot verify its answer.
 */
fun FLine.byUnionWith(other: FLine, exact: Boolean = false, stroke: Boolean = false): FLine =
    if (exact) booleanExact(other, FLineBooleanOp.Union, stroke).line else FLineExact.awt(this, other, FLineBooleanOp.Union, stroke)

/**
 * Difference of two curves (line1 - line2).
 */
fun FLine.byDifferenceWith(other: FLine, exact: Boolean = false, stroke: Boolean = false): FLine =
    if (exact) booleanExact(other, FLineBooleanOp.Subtract, stroke).line else FLineExact.awt(this, other, FLineBooleanOp.Subtract, stroke)

/**
 * Intersection of two curves.
 */
fun FLine.byIntersectionWith(other: FLine, exact: Boolean = false, stroke: Boolean = false): FLine =
    if (exact) booleanExact(other, FLineBooleanOp.Intersect, stroke).line else FLineExact.awt(this, other, FLineBooleanOp.Intersect, stroke)

/**
 * XOR (exclusive or) of two curves.
 */
fun FLine.byExclusiveOrWith(other: FLine, exact: Boolean = false, stroke: Boolean = false): FLine =
    if (exact) booleanExact(other, FLineBooleanOp.Xor, stroke).line else FLineExact.awt(this, other, FLineBooleanOp.Xor, stroke)

// ==================== GEOMETRIC QUERIES ====================

/**
 * The chord-polygon area [centroid2D] pairs with its chord-polygon moments (the public, curve-exact
 * area is [area2D]). Positive counter-clockwise with y up.
 */
private fun FLine.chordArea2D(): Float {
    var area = 0f
    var prevPoint: Vec2? = null
    var firstPoint: Vec2? = null

    this.nodes.forEach { node ->
        if (node is FLine.Close) return@forEach
        val currentPoint = Vec2(node.to.x, node.to.y)

        if (firstPoint == null) {
            firstPoint = currentPoint
        }

        val previous = prevPoint
        if (previous != null) {
            area += (previous.x * currentPoint.y - currentPoint.x * previous.y)
        }

        prevPoint = currentPoint
    }

    // Close the curve
    val first = firstPoint
    val previous = prevPoint
    if (first != null && previous != null) {
        area += (previous.x * first.y - first.x * previous.y)
    }

    return area * 0.5f
}

/**
 * Calculate the centroid of a 2D closed curve.
 */
fun FLine.centroid2D(): Vec2 {
    val area = chordArea2D()
    if (abs(area) < 1e-6f) {
        // Degenerate case: return average of points. Skipping a Close, exactly as the main loop
        // below already does and as chordArea2D does — a Close carries no point of
        // its own, so averaging it in would double-count the subpath's start, and reading `.to` on
        // one throws outright. This branch was the one place the guard was missing.
        var sum = Vec2()
        var count = 0
        this.nodes.forEach {
            if (it is FLine.Close) return@forEach
            sum.add(Vec2(it.to.x, it.to.y))
            count++
        }
        return if (count > 0) sum.div(count.toFloat()) else Vec2()
    }

    var cx = 0f
    var cy = 0f
    var prevPoint: Vec2? = null
    var firstPoint: Vec2? = null

    this.nodes.forEach { node ->
        if (node is FLine.Close) return@forEach
        val currentPoint = Vec2(node.to.x, node.to.y)

        if (firstPoint == null) {
            firstPoint = currentPoint
        }

        val previous = prevPoint
        if (previous != null) {
            val cross = previous.x * currentPoint.y - currentPoint.x * previous.y
            cx += (previous.x + currentPoint.x) * cross
            cy += (previous.y + currentPoint.y) * cross
        }

        prevPoint = currentPoint
    }

    // Close the curve
    val first = firstPoint
    val previous = prevPoint
    if (first != null && previous != null) {
        val cross = previous.x * first.y - first.x * previous.y
        cx += (previous.x + first.x) * cross
        cy += (previous.y + first.y) * cross
    }

    val factor = 1f / (6f * area)
    return vec(cx * factor, cy * factor)
}

// ==================== PATH OPERATIONS ====================

/**
 * Reverse the direction of a curve.
 *
 * Subpath by subpath, last subpath first, each subpath's own instructions reversed. That structure
 * is what makes the result a *reversal* rather than merely a backwards walk: node count, subpath
 * count and [FLine.Close] count all come out the same as the input, and `byReversing().byReversing()`
 * gets you back where you started.
 *
 * A closed subpath `moveTo p0, S1 -> p1, ..., Sn -> pn, close` reverses to
 * `moveTo pn, Sn' -> pn-1, ..., S1' -> p0, close`. The new `close` is the old closing segment,
 * traversed the other way — which is why the reversal of a closed contour is still closed, and
 * still has as many nodes. (A single backwards walk cannot do this: it emits the `close` where the
 * *first* instruction of the reversed subpath should be, so the contour comes apart.)
 *
 * Two normalisations, both forced and both harmless to the drawing:
 *  - a `Close` that is **not** the last instruction of its subpath — `close()` then more drawing,
 *    with no `moveTo` in between, which is legal because `close()` leaves the pen at the subpath
 *    start — comes back as an explicit straight segment. There is no way to write "close,
 *    backwards" as a `Close`, because in the reversed traversal the pen is not at the subpath
 *    start when it reaches that segment.
 *  - a subpath with no leading `moveTo` acquires one, matching the renderer, which already treats
 *    a leading `LineTo` as a `moveTo` (someSkiko.kt:332-337).
 *
 * Outside those two cases `byReversing().byReversing()` is the identity on the nose.
 */
fun FLine.byReversing(): FLine {
    val result = FLine()
    if (this.nodes.isEmpty()) return result

    // subpath boundaries: every MoveTo starts one, and anything before the first MoveTo is its own.
    val starts = mutableListOf(0)
    for (i in 1 until nodes.size) if (nodes[i] is FLine.MoveTo) starts.add(i)

    for (s in starts.indices.reversed()) {
        val from = starts[s]
        val until = if (s + 1 < starts.size) starts[s + 1] else nodes.size

        val closed = nodes[until - 1] is FLine.Close
        // whatever opens the subpath places the pen and draws nothing: a MoveTo, or — with no
        // MoveTo — the first instruction, exactly as the renderer reads it.
        val firstDrawn = from + 1
        val lastDrawn = if (closed) until - 2 else until - 1

        if (lastDrawn < firstDrawn) {
            // a subpath with nothing drawn in it: a bare `moveTo`, or a `close` with nothing to
            // close. Reproduce it rather than inventing a point for it.
            if (nodes[from] !is FLine.Close) result.moveTo(endPointOf(from))
            if (closed) result.close()
            continue
        }

        // the point the reversed subpath starts from: the end of its last drawing instruction
        result.moveTo(endPointOf(lastDrawn))

        for (i in lastDrawn downTo firstDrawn) {
            // endPointOf, not `.to`: the point an instruction starts from can be a Close's, since
            // `close()` leaves the pen at the start of the subpath it closed.
            val landsOn = endPointOf(i - 1)
            when (val node = nodes[i]) {
                is FLine.LineTo -> result.lineTo(landsOn)
                is FLine.CubicTo -> result.cubicTo(node.c2, node.c1, landsOn)
                // an interior Close: a straight run back to the subpath start, so its reverse is a
                // straight run away from it
                is FLine.Close -> result.lineTo(landsOn)
                is FLine.MoveTo -> {}
            }
        }

        if (closed) result.close()
    }

    return result
}

/**
 * Concatenate multiple curves into one.
 *
 * WARNING: this implementation is currently broken and always returns an empty
 * FLine. The inner loop iterates `result.nodes` (which starts empty and is never
 * seeded from the input `line`) instead of `line.nodes`, so no nodes are ever
 * copied. Do not rely on this until the loop is fixed to iterate `line.nodes`.
 */
fun concatenate(vararg lines: FLine): FLine {
    val result = FLine()

    lines.forEach { line ->
        line.nodes.forEach { node ->
            result.nodes.add(node.copy())
        }
    }

    return result
}

/**
 * Blend between two curves.
 * @param t blend factor (0 = line1, 1 = line2)
 */
fun FLine.byBlendingWith(
    other: FLine,
    t: Float,
    sampler: FLineSampler? = null,
    otherSampler: FLineSampler? = sampler
): FLine {
    // At least 64 samples: one per node would collapse a single cubic to two points.
    val samples = maxOf(64, this.nodes.size, other.nodes.size)
    val points1 = this.samplePoints(samples, sampler = sampler)
    val points2 = other.samplePoints(samples, sampler = otherSampler)
    if (points1.isEmpty() || points2.isEmpty()) return FLine()

    val result = FLine()

    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val blended = Vec3(p1).lerp(p2, t)

        if (i == 0) {
            result.moveTo(blended)
        } else {
            result.lineTo(blended)
        }
    }

    return result
}

/**
 * Extract a sub-curve between two t parameters.
 */
fun FLine.byExtracting(tStart: Float, tEnd: Float): FLine {
    val cursor = this.cursor()
    cursor.setT(tStart)
    val split1 = cursor.split()

    if (split1.second == null) return FLine()

    val remaining = split1.second!!
    val cursor2 = remaining.cursor()
    cursor2.setT(tEnd - tStart)
    val split2 = cursor2.split()

    return split2.first ?: FLine()
}

// ==================== ADVANCED CURVE FITTING ====================

/**
 * Fit a circle to a set of points on the curve.
 * @param samples number of points to sample
 * @return Triple of (center, radius, error)
 */
fun FLine.fitCircle(samples: Int = 20, sampler: FLineSampler? = null): Triple<Vec2, Float, Float> {
    val points = this.samplePoints(samples, sampler = sampler).map { Vec2(it.x, it.y) }

    // Use least squares circle fitting
    var sumX = 0f
    var sumY = 0f
    var sumXX = 0f
    var sumYY = 0f
    var sumXY = 0f
    var sumXXX = 0f
    var sumYYY = 0f
    var sumXXY = 0f
    var sumXYY = 0f

    points.forEach { p ->
        sumX += p.x
        sumY += p.y
        sumXX += p.x * p.x
        sumYY += p.y * p.y
        sumXY += p.x * p.y
        sumXXX += p.x * p.x * p.x
        sumYYY += p.y * p.y * p.y
        sumXXY += p.x * p.x * p.y
        sumXYY += p.x * p.y * p.y
    }

    val n = points.size.toFloat()
    val a = 2 * (sumX * sumX - n * sumXX)
    val b = 2 * (sumX * sumY - n * sumXY)
    val c = 2 * (sumY * sumY - n * sumYY)
    val d = sumXX * sumX - n * sumXXX + sumX * sumYY - n * sumXYY
    val e = sumXX * sumY - n * sumYYY + sumYY * sumY - n * sumXXY

    val denominator = a * c - b * b
    val cx = if (abs(denominator) > 1e-6f) (d * c - b * e) / denominator else sumX / n
    val cy = if (abs(denominator) > 1e-6f) (a * e - b * d) / denominator else sumY / n

    // Calculate radius as average distance to center
    val radius = points.map { sqrt((it.x - cx).pow(2) + (it.y - cy).pow(2)) }.average().toFloat()

    // Calculate error
    val error = points.map { abs(sqrt((it.x - cx).pow(2) + (it.y - cy).pow(2)) - radius) }.average().toFloat()

    return Triple(vec(cx, cy), radius, error)
}

/**
 * Fit a line to points on the curve using least squares in 2D.
 * Returns the line as two points (start and end) that best fit the sampled points,
 * along with the RMS error.
 * @param samples number of points to sample from the curve
 * @return Triple of (start point, end point, RMS error)
 */
fun FLine.fitLine2D(samples: Int = 20, sampler: FLineSampler? = null): Triple<Vec2, Vec2, Float> {
    val points = this.samplePoints(samples, sampler = sampler).map { Vec2(it.x, it.y) }

    if (points.size < 2) {
        // Degenerate case
        val p = points.firstOrNull() ?: vec(0f, 0f)
        return Triple(p, p, 0f)
    }

    // Calculate centroid
    val n = points.size.toFloat()
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / n
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / n
    val centroid = vec(cx, cy)

    // Calculate covariance matrix elements
    var sxx = 0f
    var sxy = 0f
    var syy = 0f

    points.forEach { p ->
        val dx = p.x - cx
        val dy = p.y - cy
        sxx += dx * dx
        sxy += dx * dy
        syy += dy * dy
    }

    // Find principal direction using eigenvalue decomposition
    // For 2x2 symmetric matrix, we can solve analytically
    val trace = sxx + syy
    val det = sxx * syy - sxy * sxy
    val lambda1 = trace / 2 + sqrt((trace * trace / 4 - det).coerceAtLeast(0f))

    // Eigenvector for largest eigenvalue gives line direction
    val dirX: Float
    val dirY: Float
    if (abs(sxy) > 1e-6f) {
        dirX = lambda1 - syy
        dirY = sxy
    } else if (sxx > syy) {
        dirX = 1f
        dirY = 0f
    } else {
        dirX = 0f
        dirY = 1f
    }

    // Normalize direction
    val dirLen = sqrt(dirX * dirX + dirY * dirY)
    val dx = dirX / dirLen
    val dy = dirY / dirLen

    // Project all points onto the line and find extent
    var minT = Float.POSITIVE_INFINITY
    var maxT = Float.NEGATIVE_INFINITY

    points.forEach { p ->
        val t = (p.x - cx) * dx + (p.y - cy) * dy
        minT = min(minT, t)
        maxT = max(maxT, t)
    }

    // Compute line endpoints
    val start = vec(cx + minT * dx, cy + minT * dy)
    val end = vec(cx + maxT * dx, cy + maxT * dy)

    // Calculate RMS error (perpendicular distance to line)
    val error = sqrt(points.sumOf { p ->
        val t = (p.x - cx) * dx + (p.y - cy) * dy
        val projX = cx + t * dx
        val projY = cy + t * dy
        val distSq = (p.x - projX) * (p.x - projX) + (p.y - projY) * (p.y - projY)
        distSq.toDouble()
    }.toFloat() / n)

    return Triple(start, end, error)
}

/**
 * Fit a line to points on the curve using power iteration in 3D.
 * Uses power iteration on the covariance matrix to find the principal eigenvector,
 * which gives the best-fit line direction.
 * Returns the line as a point (centroid) and direction vector, along with the RMS error.
 * @param samples number of points to sample from the curve
 * @return Triple of (point on line, direction vector, RMS error)
 */
fun FLine.fitLine3D(samples: Int = 20, sampler: FLineSampler? = null): Triple<Vec3, Vec3, Float> {
    val points = this.samplePoints(samples, sampler = sampler)

    if (points.size < 2) {
        val p = points.firstOrNull() ?: vec(0f, 0f, 0f)
        return Triple(p, vec(1f, 0f, 0f), 0f)
    }

    // Calculate centroid
    val n = points.size.toFloat()
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / n
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / n
    val cz = points.sumOf { it.z.toDouble() }.toFloat() / n
    val centroid = vec(cx, cy, cz)

    // Build covariance matrix
    var sxx = 0f
    var sxy = 0f
    var sxz = 0f
    var syy = 0f
    var syz = 0f
    var szz = 0f

    points.forEach { p ->
        val dx = p.x - cx
        val dy = p.y - cy
        val dz = p.z - cz
        sxx += dx * dx
        sxy += dx * dy
        sxz += dx * dz
        syy += dy * dy
        syz += dy * dz
        szz += dz * dz
    }

    // Simple power iteration to find principal eigenvector
    var vx = 1f; var vy = 1f; var vz = 1f
    repeat(20) {
        val newX = sxx * vx + sxy * vy + sxz * vz
        val newY = sxy * vx + syy * vy + syz * vz
        val newZ = sxz * vx + syz * vy + szz * vz
        val len = sqrt(newX * newX + newY * newY + newZ * newZ)
        if (len > 1e-6f) {
            vx = newX / len
            vy = newY / len
            vz = newZ / len
        }
    }

    val direction = vec(vx, vy, vz)

    // Calculate RMS error
    val error = sqrt(points.sumOf { p ->
        val v = Vec3(p).sub(centroid)
        val proj = v.dot(direction)
        val perpDistSq = Vec3(v).sub(Vec3(direction).mul(proj)).lengthSquared()
        perpDistSq.toDouble()
    }.toFloat() / n)

    return Triple(centroid, direction, error)
}

/**
 * Fit a polynomial of given degree to the curve in 2D.
 * Uses Smile's OLS (Ordinary Least Squares) for robust fitting.
 * Fits y = a0 + a1*x + a2*x^2 + ... + an*x^n
 * @param degree degree of polynomial (1=linear, 2=quadratic, 3=cubic, etc.)
 * @param samples number of points to sample
 * @return Pair of (coefficients array [a0, a1, ..., an], RMS error)
 */
fun FLine.fitPolynomial(degree: Int, samples: Int = 50, sampler: FLineSampler? = null): Pair<FloatArray, Float> {
    require(degree >= 1) { "Polynomial degree must be at least 1" }

    val points = this.samplePoints(samples, sampler = sampler).map { Vec2(it.x, it.y) }
    val n = points.size

    if (n < degree + 1) {
        // Not enough points for the degree
        return Pair(FloatArray(degree + 1) { 0f }, Float.POSITIVE_INFINITY)
    }

    return try {
        val predictorNames = Array(degree) { i -> "x${i + 1}" }
        val columnNames = predictorNames + "y"
        val data = Array(n) { DoubleArray(degree + 1) }

        points.forEachIndexed { idx, p ->
            var xPow = p.x.toDouble()
            for (j in 0 until degree) {
                data[idx][j] = xPow
                xPow *= p.x.toDouble()
            }
            data[idx][degree] = p.y.toDouble()
        }

        // Fit y = a0 + a1*x + ... + an*x^n with intercept included as a0.
        val model = OLS.fit(
            Formula.of("y", *predictorNames),
            DataFrame.of(data, *columnNames)
        )
        val coeffs = FloatArray(degree + 1)
        coeffs[0] = model.intercept().toFloat()
        val learned = model.coefficients()
        for (i in 1..degree) {
            coeffs[i] = learned.get(i - 1).toFloat()
        }

        // Calculate RMS error
        val error = sqrt(points.sumOf { p ->
            var yPred = 0f
            var xPow = 1f
            for (i in 0..degree) {
                yPred += coeffs[i] * xPow
                xPow *= p.x
            }
            val residual = p.y - yPred
            (residual * residual).toDouble()
        }.toFloat() / n)

        Pair(coeffs, error)
    } catch (e: Exception) {
        // Fallback to zero coefficients if Smile fitting fails
        // Log for debugging but don't fail the operation
        if (System.getProperty("field.debug") != null) {
            System.err.println("FLineGeometry.fitPolynomial: Smile OLS failed, using fallback: ${e.message}")
        }
        Pair(FloatArray(degree + 1) { 0f }, Float.POSITIVE_INFINITY)
    }
}

/**
 * Fit an ellipse to points on the curve using moment-based approximation.
 * Note: This uses a simplified moment-based method rather than full algebraic distance
 * minimization. It provides good approximations for well-behaved elliptical data.
 *
 * Returns ellipse parameters: center, semi-major axis length, semi-minor axis length,
 * rotation angle, and fitting error.
 *
 * @param samples number of points to sample
 * @return data class containing ellipse parameters and error (note: error is approximate)
 */
data class EllipseFit(
    val center: Vec2,
    val semiMajorAxis: Float,
    val semiMinorAxis: Float,
    val rotationAngle: Float,
    val error: Float
)

fun FLine.fitEllipse(samples: Int = 30, sampler: FLineSampler? = null): EllipseFit {
    val points = this.samplePoints(samples, sampler = sampler).map { Vec2(it.x, it.y) }

    if (points.size < 5) {
        // Need at least 5 points for an ellipse
        val center = points.firstOrNull() ?: vec(0f, 0f)
        return EllipseFit(center, 0f, 0f, 0f, Float.POSITIVE_INFINITY)
    }

    val n = points.size

    // Direct least squares fitting of ellipse
    // Fit: a*x^2 + b*x*y + c*y^2 + d*x + e*y + f = 0
    // with constraint: b^2 - 4*a*c < 0 (ellipse constraint)

    // Build design matrix for quadratic curve
    val d = Array(n) { FloatArray(6) }
    points.forEachIndexed { i, p ->
        val x = p.x
        val y = p.y
        d[i][0] = x * x
        d[i][1] = x * y
        d[i][2] = y * y
        d[i][3] = x
        d[i][4] = y
        d[i][5] = 1f
    }

    // Build scatter matrix: D^T * D
    val s = Array(6) { FloatArray(6) { 0f } }
    for (i in 0 until 6) {
        for (j in 0 until 6) {
            for (k in 0 until n) {
                s[i][j] += d[k][i] * d[k][j]
            }
        }
    }

    // Use simplified algebraic fit (assuming b^2-4ac constraint)
    // This is a simplified version - full implementation would use eigenvalue method
    // For robustness, we normalize and solve approximately

    // Normalize points
    val meanX = points.sumOf { it.x.toDouble() }.toFloat() / n
    val meanY = points.sumOf { it.y.toDouble() }.toFloat() / n
    val stdX = sqrt(points.sumOf { ((it.x - meanX) * (it.x - meanX)).toDouble() }.toFloat() / n)
    val stdY = sqrt(points.sumOf { ((it.y - meanY) * (it.y - meanY)).toDouble() }.toFloat() / n)

    // Use a simple moment-based approximation for ellipse
    var m20 = 0f; var m02 = 0f; var m11 = 0f
    points.forEach { p ->
        val dx = p.x - meanX
        val dy = p.y - meanY
        m20 += dx * dx
        m02 += dy * dy
        m11 += dx * dy
    }
    m20 /= n; m02 /= n; m11 /= n

    // Eigenvalues of moment matrix
    val trace = m20 + m02
    val det = m20 * m02 - m11 * m11
    val lambda1 = trace / 2 + sqrt((trace * trace / 4 - det).coerceAtLeast(0f))
    val lambda2 = trace / 2 - sqrt((trace * trace / 4 - det).coerceAtLeast(0f))

    val semiMajor = 2 * sqrt(lambda1.coerceAtLeast(0f))
    val semiMinor = 2 * sqrt(lambda2.coerceAtLeast(0f))

    // Rotation angle from eigenvector
    val angle = if (abs(m11) > 1e-6f) {
        atan2(lambda1 - m02, m11)
    } else if (m20 > m02) {
        0f
    } else {
        (PI / 2).toFloat()
    }

    // Calculate error as average approximate distance from ellipse
    // Note: This uses a simplified distance metric for performance
    val error = points.sumOf { p ->
        val dx = p.x - meanX
        val dy = p.y - meanY
        // Approximate distance to ellipse by comparing radial distance to average radius
        val dist = sqrt(dx * dx + dy * dy)
        abs(dist - (semiMajor + semiMinor) / 2).toDouble()
    }.toFloat() / n

    return EllipseFit(vec(meanX, meanY), semiMajor, semiMinor, angle, error)
}

/**
 * Fit a cubic Bezier curve to the sampled points.
 * Uses least squares to find control points that best approximate the curve.
 * Can fit any number of points >= 2.
 * @param samples number of points to sample
 * @return Quad of (start point, control point 1, control point 2, end point) and error
 */
fun FLine.fitBezierCubic(samples: Int = 20, sampler: FLineSampler? = null): Pair<Array<Vec3>, Float> {
    val points = this.samplePoints(samples, sampler = sampler)

    if (points.size < 2) {
        // Not enough points
        val p = points.firstOrNull() ?: vec(0f, 0f, 0f)
        return Pair(arrayOf(p, p, p, p), Float.POSITIVE_INFINITY)
    }

    // Fix endpoints to first and last sampled points
    val p0 = points.first()
    val p3 = points.last()

    // Parameterize points by chord length
    val params = FloatArray(points.size)
    params[0] = 0f
    var totalLength = 0f
    for (i in 1 until points.size) {
        val dist = Vec3(points[i]).sub(points[i - 1]).length()
        totalLength += dist
        params[i] = totalLength
    }
    if (totalLength > 1e-6f) {
        for (i in 0 until points.size) {
            params[i] /= totalLength
        }
    }

    return try {
        // For each point: points[i] - ((1-t)^3 p0 + t^3 p3) = b1(t)*p1 + b2(t)*p2
        // where b1 = 3(1-t)^2 t and b2 = 3(1-t)t^2.
        val n = points.size
        val basis1 = DoubleArray(n)
        val basis2 = DoubleArray(n)
        val targetX = DoubleArray(n)
        val targetY = DoubleArray(n)
        val targetZ = DoubleArray(n)

        for (i in points.indices) {
            val t = params[i].toDouble()
            val t2 = t * t
            val t3 = t2 * t
            val mt = 1.0 - t
            val mt2 = mt * mt
            val mt3 = mt2 * mt

            basis1[i] = 3.0 * mt2 * t
            basis2[i] = 3.0 * mt * t2

            val fixedContribution = Vec3(p0).mul(mt3.toFloat()).add(Vec3(p3).mul(t3.toFloat()))
            targetX[i] = (points[i].x - fixedContribution.x).toDouble()
            targetY[i] = (points[i].y - fixedContribution.y).toDouble()
            targetZ[i] = (points[i].z - fixedContribution.z).toDouble()
        }

        fun solveCoordinate(target: DoubleArray): DoubleArray {
            val data = Array(n) { i ->
                doubleArrayOf(basis1[i], basis2[i], target[i])
            }
            val model = OLS.fit(
                Formula.of("y", "b1", "b2", "0"),
                DataFrame.of(data, "b1", "b2", "y")
            )
            val coeffs = model.coefficients()
            return doubleArrayOf(coeffs.get(0), coeffs.get(1))
        }

        val xCoeffs = solveCoordinate(targetX)
        val yCoeffs = solveCoordinate(targetY)
        val zCoeffs = solveCoordinate(targetZ)

        val p1 = vec(
            xCoeffs[0].toFloat(),
            yCoeffs[0].toFloat(),
            zCoeffs[0].toFloat()
        )
        val p2 = vec(
            xCoeffs[1].toFloat(),
            yCoeffs[1].toFloat(),
            zCoeffs[1].toFloat()
        )

        // Calculate error
        val error = points.withIndex().sumOf { (i, p) ->
            val t = params[i]
            val t2 = t * t
            val t3 = t2 * t
            val mt = 1 - t
            val mt2 = mt * mt
            val mt3 = mt2 * mt

            val bezierPt = Vec3(p0).mul(mt3)
                .add(Vec3(p1).mul(3 * mt2 * t))
                .add(Vec3(p2).mul(3 * mt * t2))
                .add(Vec3(p3).mul(t3))

            Vec3(p).sub(bezierPt).lengthSquared().toDouble()
        }.toFloat() / points.size

        Pair(arrayOf(p0, p1, p2, p3), sqrt(error))

    } catch (e: Exception) {
        // Fallback: use simple interpolation for control points
        // Log for debugging but don't fail the operation
        if (System.getProperty("field.debug") != null) {
            System.err.println("FLineGeometry.fitBezierCubic: Smile OLS failed, using fallback: ${e.message}")
        }
        val p1 = Vec3(p0).lerp(p3, 0.33f)
        val p2 = Vec3(p0).lerp(p3, 0.67f)

        // Calculate error with fallback curve
        val error = points.withIndex().sumOf { (i, p) ->
            val t = params[i]
            val t2 = t * t
            val t3 = t2 * t
            val mt = 1 - t
            val mt2 = mt * mt
            val mt3 = mt2 * mt

            val bezierPt = Vec3(p0).mul(mt3)
                .add(Vec3(p1).mul(3 * mt2 * t))
                .add(Vec3(p2).mul(3 * mt * t2))
                .add(Vec3(p3).mul(t3))

            Vec3(p).sub(bezierPt).lengthSquared().toDouble()
        }.toFloat() / points.size

        Pair(arrayOf(p0, p1, p2, p3), sqrt(error))
    }
}

/**
 * Fit piecewise linear segments to the curve adaptively based on curvature.
 * High curvature areas get more segments. Returns a list of line segments.
 * @param maxSegments maximum number of segments
 * @param curvatureThreshold minimum curvature to force a segment break
 * @return List of FLines, each representing a line segment
 */
fun FLine.fitPiecewiseLinear(
    maxSegments: Int = 10,
    curvatureThreshold: Float = 0.05f,
    samples: Int = 100,
    sampler: FLineSampler? = null
): List<FLine> {
    val points = this.samplePoints(samples, sampler = sampler)
    if (points.size < 2) return emptyList()

    // Analyze curvature to find break points
    val curvatures = this.curvatureAnalysis(samples = samples)
    val breakIndices = mutableListOf(0)

    // Find high curvature points
    curvatures.forEachIndexed { i, (_, k) ->
        if (abs(k) > curvatureThreshold && i > breakIndices.last() + samples / maxSegments) {
            breakIndices.add(i)
        }
    }

    // Ensure we don't exceed maxSegments
    while (breakIndices.size > maxSegments) {
        // Remove breaks at lowest curvature
        val toRemove = breakIndices
            .drop(1)
            .minByOrNull { i -> abs(curvatures.getOrNull(i)?.second ?: 0f) }
        if (toRemove != null) breakIndices.remove(toRemove)
        else break
    }

    breakIndices.add(points.size - 1)

    // Create line segments
    val segments = mutableListOf<FLine>()
    for (i in 0 until breakIndices.size - 1) {
        val start = breakIndices[i]
        val end = breakIndices[i + 1]

        if (start < points.size && end < points.size) {
            val line = FLine()
            line.moveTo(points[start])
            line.lineTo(points[end])
            segments.add(line)
        }
    }

    return segments
}

/**
 * Create an FLine from a list of points with automatic curve fitting.
 * @param points list of points to fit
 * @param strategy fitting strategy: "linear", "quadratic", "cubic", "bezier", or "adaptive"
 * @return new FLine fitted to the points
 */
fun pointsToFLine(points: List<Vec3>, strategy: String = "adaptive"): FLine {
    if (points.isEmpty()) return FLine()
    if (points.size == 1) return FLine().also { it.moveTo(points[0]) }

    val line = FLine()

    when (strategy.lowercase()) {
        "linear" -> {
            // Simple linear interpolation
            line.moveTo(points[0])
            points.drop(1).forEach { line.lineTo(it) }
        }

        "quadratic", "cubic" -> {
            // Polynomial fit
            val tempLine = FLine().also { fl ->
                fl.moveTo(points[0])
                points.drop(1).forEach { fl.lineTo(it) }
            }
            val degree = if (strategy == "quadratic") 2 else 3
            val (coeffs, _) = tempLine.fitPolynomial(degree, samples = points.size)

            // Create smooth curve from polynomial
            line.moveTo(points.first())
            val xMin = points.minOf { it.x }
            val xMax = points.maxOf { it.x }
            val steps = (points.size * 2).coerceAtLeast(20)
            for (i in 1..steps) {
                val x = xMin + (xMax - xMin) * i / steps
                var y = 0f
                var xPow = 1f
                coeffs.forEach { coeff ->
                    y += coeff * xPow
                    xPow *= x
                }
                line.lineTo(x, y, points.first().z)
            }
        }

        "bezier" -> {
            // Fit cubic Bezier segments
            line.moveTo(points[0])

            // Process points in groups of 4 (or less for the last segment)
            var i = 0
            while (i < points.size - 1) {
                val segmentEnd = min(i + 4, points.size)
                val segmentPoints = points.subList(i, segmentEnd)

                val tempLine = FLine().also { fl ->
                    fl.moveTo(segmentPoints[0])
                    segmentPoints.drop(1).forEach { fl.lineTo(it) }
                }

                if (segmentPoints.size >= 4) {
                    val (bezier, _) = tempLine.fitBezierCubic(samples = segmentPoints.size)
                    line.cubicTo(bezier[1], bezier[2], bezier[3])
                } else {
                    // Not enough points, just draw lines
                    segmentPoints.drop(1).forEach { line.lineTo(it) }
                }

                i += 3 // Move forward 3 points (from 4-point group) to maintain 1-point overlap
            }
        }

        "adaptive" -> {
            // Adaptive fitting based on point spacing
            // Strategy: Use Bezier curves for areas with consistent point spacing (likely from smooth curves),
            // and straight lines for areas with irregular spacing (likely from corners or complex features)
            line.moveTo(points[0])

            // Calculate average distance between consecutive points
            val distances = points.zipWithNext().map { (a, b) -> Vec3(a).sub(b).length() }
            val avgDist = distances.average().toFloat()
            val stdDist = sqrt(distances.sumOf { (it - avgDist) * (it - avgDist).toDouble() }.toFloat() / distances.size)

            // Process points in groups
            var i = 0
            while (i < points.size - 1) {
                val segmentStart = i
                var segmentEnd = min(i + 4, points.size - 1)

                // Check if next few points have consistent spacing (suggesting a smooth curve section)
                // Note: This is about point distribution, not the curve parameterization.
                // Cubic splines/Bezier can fit any points, but consistent spacing indicates
                // the points likely came from a smooth section worth representing with a curve.
                val hasConsistentSpacing = if (segmentEnd - segmentStart >= 2) {
                    val localDists = (segmentStart until segmentEnd).map { idx ->
                        if (idx + 1 < points.size) Vec3(points[idx]).sub(points[idx + 1]).length() else 0f
                    }
                    localDists.all { abs(it - avgDist) < stdDist * 2 }
                } else false

                if (hasConsistentSpacing && segmentEnd - segmentStart >= 3) {
                    // Use Bezier curve - can fit any number of points >= 2
                    val segmentPoints = points.subList(segmentStart, segmentEnd + 1)
                    val tempLine = FLine().also { fl ->
                        fl.moveTo(segmentPoints[0])
                        segmentPoints.drop(1).forEach { fl.lineTo(it) }
                    }
                    val (bezier, _) = tempLine.fitBezierCubic(samples = segmentPoints.size)
                    line.cubicTo(bezier[1], bezier[2], bezier[3])
                    i += 3
                } else {
                    // Use straight line for irregular spacing
                    line.lineTo(points[i + 1])
                    i += 1
                }
            }
        }

        else -> {
            // Default to linear
            line.moveTo(points[0])
            points.drop(1).forEach { line.lineTo(it) }
        }
    }

    return line
}

/**
 * Create an FLine from a list of 2D points.
 * Convenience function that converts Vec2 to Vec3 with z=0.
 */
@JvmName("pointsToFLineVec2")
fun pointsToFLine(points: List<Vec2>, strategy: String = "adaptive"): FLine {
    return pointsToFLine(points.map { vec(it.x, it.y, 0f) }, strategy)
}

/**
 * Subdivide a curve using De Casteljau's algorithm.
 * This creates a smoother approximation with more control points.
 *
 * A [FLine.Close] is carried through untouched — its segment is the straight run back to the
 * `moveTo` and there is nothing in it to subdivide — so a closed contour stays closed and a glyph
 * keeps its contour count. It used to fall through every branch here and be dropped.
 */
fun FLine.bySubdividing(levels: Int = 1): FLine {
    var result = copy()

    repeat(levels) {
        val newResult = FLine()
        var first = true

        result.nodes.forEachIndexed { i, node ->
            // endPointOf, not `.to`, throughout: a Close stores no point, and `close()` leaves the
            // pen at the start of the subpath it closed, so the *next* instruction genuinely does
            // start from a Close's resolved end point.
            if (node is FLine.Close) {
                newResult.close()
            } else if (i == 0 || node is FLine.MoveTo) {
                newResult.moveTo(result.endPointOf(i))
                first = false
            } else if (node is FLine.LineTo) {
                val prev = result.endPointOf(i - 1)
                val mid = Vec3(prev).lerp(node.to, 0.5f)
                newResult.lineTo(mid)
                newResult.lineTo(node.to)
            } else if (node is FLine.CubicTo) {
                val prev = result.endPointOf(i - 1)

                // De Casteljau subdivision at t=0.5
                val q0 = Vec3(prev).lerp(node.c1, 0.5f)
                val q1 = Vec3(node.c1).lerp(node.c2, 0.5f)
                val q2 = Vec3(node.c2).lerp(node.to, 0.5f)

                val r0 = Vec3(q0).lerp(q1, 0.5f)
                val r1 = Vec3(q1).lerp(q2, 0.5f)

                val mid = Vec3(r0).lerp(r1, 0.5f)

                newResult.cubicTo(q0, r0, mid)
                newResult.cubicTo(r1, q2, node.to)
            }
        }

        result = newResult
    }

    return result
}

/**
 * Arc-length parameterization: resample the curve with equal spacing along its length.
 * @param spacing desired spacing between points
 */
fun FLine.byArcLengthParameterizing(spacing: Float): FLine {
    return this.copy {
        val samples = sampleByDistance(spacing)
        nodes.clear()

        if (samples.isNotEmpty()) {
            moveTo(samples[0])
            for (i in 1 until samples.size) {
                lineTo(samples[i])
            }
        }
    }
}

/**
 * Create a dashed version of the curve.
 *
 * Each dash follows the curve. Dashes used to be emitted as a single straight segment between
 * their two endpoints, which is only right on a straight stretch: on anything curved the dash cut
 * the corner, so a "18 unit" dash across a bend drew as a visibly shorter chord and the dashes
 * drifted off the line they were dashing.
 *
 * Each subpath is dashed independently, so a dash never bridges the gap between two subpaths.
 *
 * @param dashLength length of each dash, along the curve
 * @param gapLength length of each gap, along the curve
 * @param resolution longest straight piece used to follow the curve inside one dash
 */
fun FLine.byDashing(dashLength: Float, gapLength: Float, resolution: Float = 2f): List<FLine> {
    val result = mutableListOf<FLine>()
    if (dashLength <= 0f || gapLength <= 0f) return result

    // One subpath at a time. A single walk over a multi-subpath line has no idea where the `moveTo`
    // jumps are, so a dash straddling one drew a straight segment right across the gap between two
    // subpaths — a 25-unit dash across a 360-unit jump measured 1428% long. Each subpath gets its
    // own dash phase, which is also what you want when dashing a glyph's separate contours.
    val subpaths = pieces()
    if (subpaths.size > 1) {
        subpaths.forEach { result += it.byDashing(dashLength, gapLength, resolution) }
        return result
    }

    val cursor = this.cursor()
    val totalLength = cursor.lengthD()
    if (totalLength <= 0f) return result

    val step = resolution.coerceAtLeast(1e-4f)

    var distance = 0f
    while (distance < totalLength) {
        val end = min(distance + dashLength, totalLength)
        val pieces = max(1, ceil((end - distance) / step).toInt())

        val dash = FLine()
        for (i in 0..pieces) {
            cursor.setD(distance + (end - distance) * i / pieces)
            val p = cursor.position()
            if (i == 0) dash.moveTo(p) else dash.lineTo(p)
        }
        result.add(dash)

        distance = end + gapLength
    }

    return result
}

/**
 * Create a wave pattern along the curve.
 * @param amplitude wave amplitude
 * @param frequency waves per node interval, not per unit of length: a line of two nodes makes
 *   `frequency` waves end to end, one of eleven makes ten times as many. For waves of a fixed
 *   wavelength, divide the length by the wavelength and by `nodes.size - 1`.
 * @param samples number of samples
 */
fun FLine.byWaving(amplitude: Float, frequency: Float, samples: Int = 100): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2D()

        if (normal != null) {
            val offset = amplitude * sin(t * frequency * 2 * PI.toFloat())
            val wavePos = vec(
                pos.x + normal.x * offset,
                pos.y + normal.y * offset,
                pos.z
            )

            if (i == 0) {
                result.moveTo(wavePos)
            } else {
                result.lineTo(wavePos)
            }
        }
    }

    return result
}

/**
 * Create a zigzag pattern along the curve.
 */
fun FLine.byZigzagging(amplitude: Float, segments: Int = 20): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)

    for (i in 0..segments) {
        val t = i.toFloat() / segments * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2D()

        if (normal != null) {
            val offset = amplitude * if (i % 2 == 0) 1f else -1f
            val zigPos = vec(
                pos.x + normal.x * offset,
                pos.y + normal.y * offset,
                pos.z
            )

            if (i == 0) {
                result.moveTo(zigPos)
            } else {
                result.lineTo(zigPos)
            }
        }
    }

    return result
}

// ==================== PATH CONSTRUCTION ====================

/**
 * Set a local 2D plane used by numeric/Vec2 path builders.
 * xAxisHint is projected onto the plane to define the X axis.
 */
fun FLine.set2DPlane(
    origin: Vec3 = vec(0f, 0f, 0f),
    normal: Vec3 = vec(0f, 0f, 1f),
    xAxisHint: Vec3 = vec(1f, 0f, 0f)
): FLine {
    val n = Vec3(normal)
    if (n.lengthSquared() < 1e-12f) n.set(0f, 0f, 1f)
    n.normalize()

    var x = Vec3(xAxisHint)
    x.sub(Vec3(n).mul(n.dot(x)))
    if (x.lengthSquared() < 1e-12f) {
        x = if (abs(n.x) < 0.9f) vec(1f, 0f, 0f) else vec(0f, 1f, 0f)
        x.sub(Vec3(n).mul(n.dot(x)))
    }
    x.normalize()

    val y = Vec3(n).cross(x).normalize()
    properties[FLINE_PLANE2D_KEY] = Plane2D(Vec3(origin), x, y, n)
    return this
}

/**
 * Clear any plane set by [set2DPlane], returning to the default XY plane.
 */
fun FLine.clear2DPlane(): FLine {
    properties.remove(FLINE_PLANE2D_KEY)
    return this
}

private fun FLine.plane2DOrDefault(): Plane2D {
    return plane2D() ?: Plane2D(
        origin = vec(0f, 0f, 0f),
        xAxis = vec(1f, 0f, 0f),
        yAxis = vec(0f, 1f, 0f),
        normal = vec(0f, 0f, 1f)
    )
}

private fun polarVector(angle: Float, length: Float, plane: Plane2D): Vec3 {
    val c = cos(angle.toDouble()).toFloat()
    val s = sin(angle.toDouble()).toFloat()
    return Vec3(plane.xAxis).mul(c * length).add(Vec3(plane.yAxis).mul(s * length))
}

private fun appendArcBetweenVectors(
    line: FLine,
    center: Vec3,
    normal: Vec3,
    startVec: Vec3,
    endVec: Vec3,
    segments: Int
) {
    val segs = segments.coerceAtLeast(1)
    val r = startVec.length()
    if (r < 1e-6f) {
        line.lineTo(Vec3(center).add(endVec))
        return
    }

    val n = Vec3(normal)
    if (n.lengthSquared() < 1e-12f) {
        line.lineTo(Vec3(center).add(endVec))
        return
    }
    n.normalize()

    val u = Vec3(startVec).normalize()
    val v = Vec3(n).cross(u).normalize()
    val angle = atan2(
        Vec3(startVec).cross(endVec, Vec3()).dot(n),
        Vec3(startVec).dot(endVec)
    )
    val step = angle / segs

    for (i in 1..segs) {
        val theta = step * i
        val c = cos(theta.toDouble()).toFloat()
        val s = sin(theta.toDouble()).toFloat()
        val p = Vec3(center)
            .add(Vec3(u).mul(c * r))
            .add(Vec3(v).mul(s * r))
        line.lineTo(p)
    }
}

/**
 * Append a circular arc tangent to segments (current -> p1) and (p1 -> p2).
 */
fun FLine.arcTo(p1: Vec3, p2: Vec3, radius: Float, segments: Int = 32): FLine {
    if (breakNext) {
        breakNext = false
        return moveTo(p1)
    }
    if (nodes.isEmpty()) return moveTo(p1)

    val p0 = currentPoint ?: return moveTo(p1)
    if (radius <= 0f) return lineTo(p1)

    val v1 = Vec3(p0).sub(p1)
    val v2 = Vec3(p2).sub(p1)
    val len1 = v1.length()
    val len2 = v2.length()
    if (len1 < 1e-6f || len2 < 1e-6f) return lineTo(p1)

    val u1 = Vec3(v1).div(len1)
    val u2 = Vec3(v2).div(len2)
    val dot = u1.dot(u2).coerceIn(-1f, 1f)
    val theta = acos(dot.toDouble()).toFloat()
    if (theta < 1e-5f) return lineTo(p1)

    val tanHalf = tan((theta / 2f).toDouble()).toFloat()
    if (abs(tanHalf) < 1e-6f) return lineTo(p1)
    val t = radius / tanHalf
    if (t > len1 || t > len2) return lineTo(p1)

    val tangent1 = Vec3(p1).add(Vec3(u1).mul(t))
    val tangent2 = Vec3(p1).add(Vec3(u2).mul(t))

    val normal = Vec3(u1).cross(u2)
    if (normal.lengthSquared() < 1e-12f) return lineTo(p1)
    normal.normalize()

    val bisector = Vec3(u1).add(u2)
    if (bisector.lengthSquared() < 1e-12f) return lineTo(p1)
    bisector.normalize()

    val sinHalf = sin((theta / 2f).toDouble()).toFloat()
    if (abs(sinHalf) < 1e-6f) return lineTo(p1)
    val center = Vec3(p1).add(Vec3(bisector).mul(radius / sinHalf))

    if (p0.distance(tangent1) > 1e-5f) {
        lineTo(tangent1)
    }
    appendArcBetweenVectors(this, center, normal, Vec3(tangent1).sub(center), Vec3(tangent2).sub(center), segments)
    return this
}

/**
 * 2D overload of [arcTo] using the active 2D plane.
 */
fun FLine.arcTo(p1: Vec2, p2: Vec2, radius: Number, segments: Int = 32): FLine {
    return arcTo(
        planePoint(p1.x, p1.y, 0f),
        planePoint(p2.x, p2.y, 0f),
        radius.toFloat(),
        segments
    )
}

/**
 * Numeric overload of [arcTo] using the active 2D plane.
 */
fun FLine.arcTo(
    x1: Number,
    y1: Number,
    x2: Number,
    y2: Number,
    radius: Number,
    segments: Int = 32
): FLine {
    return arcTo(planePoint(x1, y1, 0f), planePoint(x2, y2, 0f), radius.toFloat(), segments)
}

/**
 * Append a circular arc through a midpoint to an end point.
 */
fun FLine.circleThroughTo(through: Vec3, end: Vec3, segments: Int = 32): FLine {
    if (breakNext || nodes.isEmpty()) {
        breakNext = false
        moveTo(through)
        return lineTo(end)
    }

    val start = currentPoint ?: return moveTo(through).lineTo(end)
    val arc = arcThrough3Points(start, through, end, segments)
    arc.nodes.drop(1).forEach { lineTo(it.to) }
    return this
}

/**
 * 2D overload of [circleThroughTo] using the active 2D plane.
 */
fun FLine.circleThroughTo(through: Vec2, end: Vec2, segments: Int = 32): FLine {
    return circleThroughTo(
        planePoint(through.x, through.y, 0f),
        planePoint(end.x, end.y, 0f),
        segments
    )
}

/**
 * Numeric overload of [circleThroughTo] using the active 2D plane.
 */
fun FLine.circleThroughTo(
    throughX: Number,
    throughY: Number,
    endX: Number,
    endY: Number,
    segments: Int = 32
): FLine {
    return circleThroughTo(
        planePoint(throughX, throughY, 0f),
        planePoint(endX, endY, 0f),
        segments
    )
}

/**
 * Append a cubic Bezier using polar handle angles/lengths in the active 2D plane.
 */
fun FLine.polarCubicTo(
    c1Angle: Float,
    c1Length: Float,
    c2Angle: Float,
    c2Length: Float,
    endPoint: Vec3
): FLine {
    if (breakNext || nodes.isEmpty()) {
        breakNext = false
        return moveTo(endPoint)
    }
    val plane = plane2DOrDefault()
    val start = currentPoint ?: return moveTo(endPoint)
    val c1 = Vec3(start).add(polarVector(c1Angle, c1Length, plane))
    val c2 = Vec3(endPoint).add(polarVector(c2Angle, c2Length, plane))
    return cubicTo(c1, c2, endPoint)
}

/**
 * 2D overload of [polarCubicTo] using the active 2D plane.
 */
fun FLine.polarCubicTo(
    c1Angle: Float,
    c1Length: Float,
    c2Angle: Float,
    c2Length: Float,
    endPoint: Vec2
): FLine {
    return polarCubicTo(
        c1Angle,
        c1Length,
        c2Angle,
        c2Length,
        planePoint(endPoint.x, endPoint.y, 0f)
    )
}

/**
 * Numeric overload of [polarCubicTo] using the active 2D plane.
 */
fun FLine.polarCubicTo(
    c1Angle: Float,
    c1Length: Float,
    c2Angle: Float,
    c2Length: Float,
    x: Number,
    y: Number,
    z: Number = 0
): FLine {
    return polarCubicTo(c1Angle, c1Length, c2Angle, c2Length, planePoint(x, y, z))
}

private data class TCBParams(val tension: Float, val continuity: Float, val bias: Float)
private const val TCB_KEY = "_tcb"

private fun tcbParams(node: FLine.Node?): TCBParams {
    return (node?.properties?.get(TCB_KEY) as? TCBParams) ?: TCBParams(0f, 0f, 0f)
}

private fun setTcbParams(node: FLine.Node, params: TCBParams) {
    node.properties[TCB_KEY] = params
}

private fun tcbOut(p0: Vec3, p1: Vec3, p2: Vec3, params: TCBParams): Vec3 {
    val d1 = Vec3(p1).sub(p0)
    val d2 = Vec3(p2).sub(p1)
    val t = 1f - params.tension
    val a = 0.5f * t * (1f + params.continuity) * (1f + params.bias)
    val b = 0.5f * t * (1f - params.continuity) * (1f - params.bias)
    return d1.mul(a).add(d2.mul(b))
}

private fun tcbIn(p0: Vec3, p1: Vec3, p2: Vec3, params: TCBParams): Vec3 {
    val d1 = Vec3(p1).sub(p0)
    val d2 = Vec3(p2).sub(p1)
    val t = 1f - params.tension
    val a = 0.5f * t * (1f - params.continuity) * (1f + params.bias)
    val b = 0.5f * t * (1f + params.continuity) * (1f - params.bias)
    return d1.mul(a).add(d2.mul(b))
}

/**
 * Build a polyline from a list of points.
 */
fun polylineFrom(points: List<Vec3>, closed: Boolean = false): FLine {
    val f = FLine()
    if (points.isNotEmpty()) {
        f.moveTo(points[0])
        for (i in 1 until points.size) {
            f.lineTo(points[i])
        }
        if (closed) f.close()
    }
    return f
}

/**
 * 2D overload of [polylineFrom], with an optional constant Z value.
 */
fun polylineFrom(points: List<Vec2>, z: Number = 0, closed: Boolean = false): FLine {
    val f = FLine()
    if (points.isNotEmpty()) {
        f.moveTo(points[0].x, points[0].y, z)
        for (i in 1 until points.size) {
            f.lineTo(points[i].x, points[i].y, z)
        }
        if (closed) f.close()
    }
    return f
}

/**
 * Append a polyline to this line, honoring [FLine.breakNext].
 */
fun FLine.byAppendingPolyline(points: Iterable<Vec3>, closed: Boolean = false): FLine {
    val it = points.iterator()
    if (!it.hasNext()) return this
    if (breakNext) {
        breakNext = false
        moveTo(it.next())
    } else {
        val first = it.next()
        if (nodes.isEmpty() || nodes.last() is FLine.Close) moveTo(first) else lineTo(first)
    }
    while (it.hasNext()) {
        lineTo(it.next())
    }
    if (closed) close()
    return this
}

/**
 * 2D overload of [byAppendingPolyline], with an optional constant Z value.
 */
fun FLine.byAppendingPolyline(points: Iterable<Vec2>, z: Number = 0, closed: Boolean = false): FLine {
    val it = points.iterator()
    if (!it.hasNext()) return this
    if (breakNext) {
        breakNext = false
        val first = it.next()
        moveTo(first.x, first.y, z)
    } else {
        val first = it.next()
        if (nodes.isEmpty() || nodes.last() is FLine.Close) moveTo(first.x, first.y, z) else lineTo(first.x, first.y, z)
    }
    while (it.hasNext()) {
        val p = it.next()
        lineTo(p.x, p.y, z)
    }
    if (closed) close()
    return this
}

/**
 * Catmull-Rom to cubic Bezier conversion (uniform, tension-scaled).
 */
fun catmullRomToBezier(points: List<Vec3>, closed: Boolean = false, tension: Float = 1f): FLine {
    val curve = FLine()
    if (points.isEmpty()) return curve
    if (points.size == 1) return curve.apply { moveTo(points[0]) }

    curve.moveTo(points[0])
    val n = points.size
    val k = tension / 6f

    if (closed) {
        for (i in 0 until n) {
            val p0 = points[(i - 1 + n) % n]
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            val p3 = points[(i + 2) % n]
            val c1 = Vec3(p1).add(Vec3(p2).sub(p0).mul(k))
            val c2 = Vec3(p2).sub(Vec3(p3).sub(p1).mul(k))
            curve.cubicTo(c1, c2, p2)
        }
        curve.close()
    } else {
        for (i in 0 until n - 1) {
            val p0 = if (i > 0) points[i - 1] else points[i]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = if (i + 2 < n) points[i + 2] else points[i + 1]
            val c1 = Vec3(p1).add(Vec3(p2).sub(p0).mul(k))
            val c2 = Vec3(p2).sub(Vec3(p3).sub(p1).mul(k))
            curve.cubicTo(c1, c2, p2)
        }
    }

    return curve
}

/**
 * 2D overload of [catmullRomToBezier], with an optional constant Z value.
 */
fun catmullRomToBezier(points: List<Vec2>, z: Number = 0, closed: Boolean = false, tension: Float = 1f): FLine {
    val pts3 = points.map { vec(it.x, it.y, z) }
    return catmullRomToBezier(pts3, closed, tension)
}

/**
 * Cubic spline builder (Catmull-Rom based).
 */
fun cubicSplineFrom(points: List<Vec3>, closed: Boolean = false, tension: Float = 1f): FLine {
    return catmullRomToBezier(points, closed, tension)
}

/**
 * 2D overload of [cubicSplineFrom], with an optional constant Z value.
 */
fun cubicSplineFrom(points: List<Vec2>, z: Number = 0, closed: Boolean = false, tension: Float = 1f): FLine {
    return catmullRomToBezier(points, z, closed, tension)
}

/**
 * Append a smooth segment by enforcing a shared tangent at the join.
 * If called immediately after a MoveTo, this falls back to lineTo.
 * smoothness scales the (next - prev) / 6 handle length (1f = default).
 */
fun FLine.smoothTo(endPoint: Vec3, smoothness: Float = 1f): FLine {
    if (breakNext) {
        breakNext = false
        return moveTo(endPoint)
    }
    if (nodes.isEmpty()) return moveTo(endPoint)

    val last = nodes.last()
    if (last is FLine.MoveTo) return lineTo(endPoint)
    if (last is FLine.Close) return moveTo(endPoint)

    // endPointOf, not `.to`: `?.` guards a *missing* node, not a Close's empty storage. `last` is
    // neither a MoveTo nor a Close by here, but the node before it can perfectly well be a Close —
    // `moveTo, lineTo, close, lineTo` is a legal line and the pen really is at the subpath start.
    val prev = if (nodes.size >= 2) endPointOf(nodes.size - 2) else last.to
    val current = last.to
    val handle = Vec3(endPoint).sub(prev).mul(smoothness / 6f)

    when (last) {
        is FLine.LineTo -> {
            val c1 = Vec3(prev).lerp(current, 1f / 3f)
            val c2 = Vec3(current).sub(handle)
            val replacement = FLine.CubicTo().set(Vec3(current), c1, c2)
            replacement.properties.putAll(last.properties)
            nodes[nodes.size - 1] = replacement
            rev++
        }

        is FLine.CubicTo -> {
            last.c2 = Vec3(current).sub(handle)
            rev++
        }
    }

    val c1New = Vec3(current).add(handle)
    val c2New = Vec3(current).lerp(endPoint, 2f / 3f)
    return cubicTo(c1New, c2New, endPoint)
}

/**
 * Numeric overload of [smoothTo].
 */
fun FLine.smoothTo(x: Number, y: Number, z: Number = 0, smoothness: Float = 1f): FLine {
    return smoothTo(vec(x, y, z), smoothness)
}

/**
 * 2D overload of [smoothTo].
 */
fun FLine.smoothTo(v: Vec2, smoothness: Float = 1f): FLine {
    return smoothTo(vec(v.x, v.y, 0f), smoothness)
}

/**
 * Append a Kochanek-Bartels (TCB) spline segment incrementally.
 * Tension/continuity/bias apply to the end point being added.
 */
fun FLine.tcbTo(
    endPoint: Vec3,
    tension: Float = 0f,
    continuity: Float = 0f,
    bias: Float = 0f
): FLine {
    val paramsNew = TCBParams(tension, continuity, bias)
    fun attachParams(): FLine {
        setTcbParams(here, paramsNew)
        return this
    }

    if (breakNext) {
        breakNext = false
        moveTo(endPoint)
        return attachParams()
    }
    if (nodes.isEmpty()) {
        moveTo(endPoint)
        return attachParams()
    }

    val last = nodes.last()
    if (last is FLine.MoveTo) {
        lineTo(endPoint)
        return attachParams()
    }
    if (last is FLine.Close) {
        moveTo(endPoint)
        return attachParams()
    }

    val prev = last.to
    val prevNode = nodes.getOrNull(nodes.size - 2)
    if (prevNode == null) {
        lineTo(endPoint)
        return attachParams()
    }

    // endPointOf, not `.to`: `last` has already been ruled out as a Close, but the two nodes behind
    // it have not, and `moveTo, lineTo, close, lineTo` puts a Close exactly there.
    val prevPrev = endPointOf(nodes.size - 2)
    val prevPrevPrev = if (nodes.size >= 3) endPointOf(nodes.size - 3) else prevPrev

    val paramsPrev = tcbParams(last)
    val paramsPrevPrev = tcbParams(prevNode)

    val inPrev = tcbIn(prevPrev, prev, endPoint, paramsPrev)
    val outPrev = tcbOut(prevPrev, prev, endPoint, paramsPrev)

    when (last) {
        is FLine.LineTo -> {
            val outPrevPrev = tcbOut(prevPrevPrev, prevPrev, prev, paramsPrevPrev)
            val c1 = Vec3(prevPrev).add(Vec3(outPrevPrev).mul(1f / 3f))
            val c2 = Vec3(prev).sub(Vec3(inPrev).mul(1f / 3f))
            val replacement = FLine.CubicTo().set(Vec3(prev), c1, c2)
            replacement.properties.putAll(last.properties)
            nodes[nodes.size - 1] = replacement
            rev++
        }

        is FLine.CubicTo -> {
            last.c2 = Vec3(prev).sub(Vec3(inPrev).mul(1f / 3f))
            rev++
        }
    }

    val c1New = Vec3(prev).add(Vec3(outPrev).mul(1f / 3f))
    val inEnd = tcbIn(prev, endPoint, endPoint, paramsNew)
    val c2New = Vec3(endPoint).sub(Vec3(inEnd).mul(1f / 3f))
    cubicTo(c1New, c2New, endPoint)
    return attachParams()
}

/**
 * Numeric overload of [tcbTo] using the active 2D plane.
 */
fun FLine.tcbTo(
    x: Number,
    y: Number,
    z: Number = 0,
    tension: Float = 0f,
    continuity: Float = 0f,
    bias: Float = 0f
): FLine {
    return tcbTo(planePoint(x, y, z), tension, continuity, bias)
}

/**
 * 2D overload of [tcbTo] using the active 2D plane.
 */
fun FLine.tcbTo(
    v: Vec2,
    tension: Float = 0f,
    continuity: Float = 0f,
    bias: Float = 0f
): FLine {
    return tcbTo(planePoint(v.x, v.y, 0f), tension, continuity, bias)
}

/**
 * Append a biarc (two circular arcs) with parametric control.
 * join controls the split point along the chord; joinTangentMix blends start/end tangents at the join.
 * bulge offsets the join point sideways (scaled by chord length).
 */
fun FLine.biarcTo(
    endPoint: Vec3,
    startTangent: Vec3? = null,
    endTangent: Vec3? = null,
    join: Float = 0.5f,
    joinTangentMix: Float = 0.5f,
    bulge: Float = 0f,
    segments: Int = 32,
    planeNormal: Vec3? = null,
    joinTangent: Vec3? = null
): FLine {
    if (breakNext) {
        breakNext = false
        return moveTo(endPoint)
    }
    if (nodes.isEmpty()) return moveTo(endPoint)

    val start = currentPoint ?: return moveTo(endPoint)
    val chord = Vec3(endPoint).sub(start)
    val chordLen = chord.length()
    if (chordLen < 1e-6f) return lineTo(endPoint)

    val startTan = startTangent?.let { Vec3(it) } ?: run {
        val last = nodes.last()
        when (last) {
            is FLine.LineTo -> {
                // endPointOf, not `.to`: the node behind the last one can be a Close, whose `.to`
                // throws — `moveTo, lineTo, close, lineTo` puts one exactly there.
                val prev = if (nodes.size >= 2) endPointOf(nodes.size - 2) else start
                Vec3(start).sub(prev)
            }

            is FLine.CubicTo -> Vec3(start).sub(last.c2)
            else -> Vec3(chord)
        }
    }
    val endTan = endTangent?.let { Vec3(it) } ?: Vec3(chord)

    if (startTan.lengthSquared() < 1e-12f || endTan.lengthSquared() < 1e-12f) {
        return lineTo(endPoint)
    }
    startTan.normalize()
    endTan.normalize()

    val j = join.coerceIn(0f, 1f)
    if (j < 0.01f || j > 0.99f) {
        byAppendingArcByTangent(endPoint, startTan, endTan, segments.coerceAtLeast(2))
        return this
    }
    // Default parameters: the classic biarc, which meets startTan and endTan exactly. Control
    // points q0 = start + d·startTan and q1 = end - d·endTan with |q1 - q0| = 2d; the join is their midpoint.
    if (j == 0.5f && joinTangentMix == 0.5f && bulge == 0f && joinTangent == null) {
        val tt = Vec3(startTan).add(endTan)
        val qa = 2f * (1f - startTan.dot(endTan))
        val qb = chord.dot(tt)
        val qc = -chord.lengthSquared()
        val dArm = if (abs(qa) < 1e-6f) (if (abs(qb) > 1e-9f) -qc / (2 * qb) else -1f)
        else (-qb + sqrt(max(0f, qb * qb - 4 * qa * qc))) / (2 * qa)
        if (dArm > 0f) {
            val q0 = Vec3(start).add(Vec3(startTan).mul(dArm))
            val q1 = Vec3(endPoint).sub(Vec3(endTan).mul(dArm))
            val joinPt = Vec3(q0).add(q1).mul(0.5f)
            val totalSegs = segments.coerceAtLeast(2)
            byAppendingArcByTangent(joinPt, startTan, endTan, totalSegs / 2)
            byAppendingArcByTangent(endPoint, arcEndTangent(start, startTan, joinPt), endTan, totalSegs - totalSegs / 2)
            return this
        }
    }
    var mid = Vec3(start).add(Vec3(chord).mul(j))
    if (bulge != 0f) {
        val n = planeNormal?.let { Vec3(it) }
            ?: plane2D()?.normal
            ?: Vec3(startTan).cross(endTan)
        if (n.lengthSquared() > 1e-12f) {
            n.normalize()
            val lateral = Vec3(n).cross(chord)
            if (lateral.lengthSquared() > 1e-12f) {
                lateral.normalize().mul(bulge * chordLen)
                mid.add(lateral)
            }
        }
    }

    val jt = joinTangent?.let { Vec3(it) } ?: run {
        val mix = joinTangentMix.coerceIn(0f, 1f)
        Vec3(startTan).mul(1f - mix).add(Vec3(endTan).mul(mix))
    }
    if (jt.lengthSquared() < 1e-12f) jt.set(chord).normalize() else jt.normalize()

    val totalSegs = segments.coerceAtLeast(2)
    val segA = max(1, (totalSegs * j).roundToInt())
    val segB = max(1, totalSegs - segA)

    if (Vec3(startTan).cross(jt, Vec3()).lengthSquared() < 1e-12f ||
        Vec3(jt).cross(endTan, Vec3()).lengthSquared() < 1e-12f
    ) {
        lineTo(mid)
        return lineTo(endPoint)
    }

    // Each arc is fixed by its start point, start tangent and end point; the second starts along
    // the first's end tangent so the join is smooth (the requested join tangent only shapes `mid`).
    byAppendingArcByTangent(mid, startTan, jt, segA)
    byAppendingArcByTangent(endPoint, arcEndTangent(start, startTan, mid), endTan, segB)
    return this
}

/**
 * 2D overload of [biarcTo] using heading angles in radians.
 */
fun FLine.biarcTo(
    endPoint: Vec2,
    startHeading: Float? = null,
    endHeading: Float? = null,
    join: Float = 0.5f,
    joinTangentMix: Float = 0.5f,
    bulge: Float = 0f,
    segments: Int = 32
): FLine {
    val plane = plane2DOrDefault()
    val startTan = startHeading?.let { polarVector(it, 1f, plane) }
    val endTan = endHeading?.let { polarVector(it, 1f, plane) }
    return biarcTo(
        planePoint(endPoint.x, endPoint.y, 0f),
        startTan,
        endTan,
        join,
        joinTangentMix,
        bulge,
        segments,
        plane.normal
    )
}

/**
 * Numeric overload of [biarcTo] using the active 2D plane.
 */
fun FLine.biarcTo(
    x: Number,
    y: Number,
    startHeading: Float? = null,
    endHeading: Float? = null,
    join: Float = 0.5f,
    joinTangentMix: Float = 0.5f,
    bulge: Float = 0f,
    segments: Int = 32
): FLine {
    return biarcTo(vec(x, y), startHeading, endHeading, join, joinTangentMix, bulge, segments)
}

/**
 * Build a standalone biarc between two points with specified tangents.
 */
fun biarcBetween(
    start: Vec3,
    end: Vec3,
    startTangent: Vec3,
    endTangent: Vec3,
    join: Float = 0.5f,
    joinTangentMix: Float = 0.5f,
    bulge: Float = 0f,
    segments: Int = 32,
    planeNormal: Vec3? = null,
    joinTangent: Vec3? = null
): FLine {
    val f = FLine()
    f.moveTo(start)
    f.biarcTo(end, startTangent, endTangent, join, joinTangentMix, bulge, segments, planeNormal, joinTangent)
    return f
}

// ==================== ARCS AND HIGHER-ORDER CURVES ====================

/**
 * Append a circular arc to this FLine in 3D.
 * @param center center point of the arc
 * @param radius radius of the arc
 * @param startAngle start angle in radians
 * @param endAngle end angle in radians
 * @param normal plane normal (defines the plane containing the arc)
 * @param segments number of line segments to approximate the arc
 * @return this FLine (for chaining)
 */
fun FLine.byAppendingArc3D(
    center: Vec3,
    radius: Float,
    startAngle: Float,
    endAngle: Float,
    normal: Vec3 = vec(0f, 0f, 1f),
    segments: Int = 32
): FLine {
    // Create orthonormal basis for the arc plane
    val n = Vec3(normal).normalize()

    // Find arbitrary perpendicular vector
    val arbitrary = if (abs(n.x) < 0.9f) vec(1f, 0f, 0f) else vec(0f, 1f, 0f)
    val u = Vec3(arbitrary).cross(n).normalize()
    val v = Vec3(n).cross(u).normalize()

    // Generate arc points
    val angleStep = (endAngle - startAngle) / segments
    for (i in 0..segments) {
        val angle = startAngle + i * angleStep
        val x = radius * cos(angle)
        val y = radius * sin(angle)

        // Transform to 3D using basis vectors
        val point = Vec3(center)
            .add(u.mul(x, Vec3()))
            .add(v.mul(y, Vec3()))

        if (i == 0 && this.nodes.isEmpty()) {
            this.moveTo(point)
        } else {
            this.lineTo(point)
        }
    }

    return this
}

/**
 * Create a circular arc through three points in 3D.
 * Returns an FLine containing the arc.
 * @param p1 first point
 * @param p2 second point (arc passes through this)
 * @param p3 third point
 * @param segments number of segments to approximate the arc
 */
fun arcThrough3Points(p1: Vec3, p2: Vec3, p3: Vec3, segments: Int = 32): FLine {
    // Find the center and radius of the circle through three points
    // First, find the plane normal
    val v1 = Vec3(p2).sub(p1)
    val v2 = Vec3(p3).sub(p1)
    val normal = Vec3(v1).cross(v2).normalize()

    // Find the circumcenter in 3D
    val a = v1.lengthSquared()
    val b = v2.lengthSquared()
    val c = Vec3(v1).cross(v2).lengthSquared()

    if (c < 1e-6f) {
        // Points are collinear, return a line
        val line = FLine()
        line.moveTo(p1)
        line.lineTo(p2)
        line.lineTo(p3)
        return line
    }

    // (center - p1) = s v1 + t v2 with (center - p1)·v1 = a/2 and (center - p1)·v2 = b/2
    val d = v1.dot(v2)
    val s = b * (a - d) / (2 * c)
    val t = a * (b - d) / (2 * c)

    val center = Vec3(p1)
        .add(Vec3(v1).mul(s))
        .add(Vec3(v2).mul(t))

    val radius = Vec3(p1).sub(center).length()

    // Angles measured from p1, positive about `normal`; p2 and p3 sit at angles in (0, 2π).
    val u = Vec3(p1).sub(center).normalize()
    val v = Vec3(normal).cross(u).normalize()
    fun angleOf(p: Vec3): Float {
        val q = Vec3(p).sub(center)
        val ang = atan2(q.dot(v), q.dot(u))
        return if (ang < 0f) ang + 2 * PI.toFloat() else ang
    }
    // Sweep positively if p2 comes before p3 that way, otherwise go the other way round.
    val a2 = angleOf(p2)
    val a3 = angleOf(p3)
    val sweep = if (a2 <= a3) a3 else a3 - 2 * PI.toFloat()

    val result = FLine()
    result.moveTo(p1)
    result.appendArcPoints(center, u, v, radius, sweep, segments)
    return result
}

/** Appends `lineTo`s along the circle `center + r (cos θ u + sin θ v)` for θ in (0, sweep]. */
private fun FLine.appendArcPoints(center: Vec3, u: Vec3, v: Vec3, radius: Float, sweep: Float, segments: Int) {
    val n = segments.coerceAtLeast(1)
    for (i in 1..n) {
        val ang = sweep * i / n
        lineTo(
            Vec3(center)
                .add(Vec3(u).mul(radius * cos(ang)))
                .add(Vec3(v).mul(radius * sin(ang)))
        )
    }
}

/**
 * Append a quadratic Bezier curve to this FLine.
 * Converts to cubic Bezier internally.
 * @return this FLine (for chaining)
 */
fun FLine.byAppendingQuadratic(controlPoint: Vec3, endPoint: Vec3): FLine {
    if (this.nodes.isEmpty()) {
        this.moveTo(endPoint)
        return this
    }

    val start = currentPoint ?: return moveTo(endPoint)

    // Convert quadratic to cubic
    // C1 = start + 2/3 * (control - start)
    // C2 = end + 2/3 * (control - end)
    val c1 = Vec3(start).add(Vec3(controlPoint).sub(start).mul(2f / 3f))
    val c2 = Vec3(endPoint).add(Vec3(controlPoint).sub(endPoint).mul(2f / 3f))

    this.cubicTo(c1, c2, endPoint)
    return this
}

/**
 * Append a circular arc to this FLine by specifying tangent at start and end.
 * Uses the arc that best fits the tangent constraints.
 * @return this FLine (for chaining)
 */
fun FLine.byAppendingArcByTangent(
    endPoint: Vec3,
    startTangent: Vec3,
    endTangent: Vec3,
    segments: Int = 32
): FLine {
    if (this.nodes.isEmpty()) {
        this.moveTo(endPoint)
        return this
    }

    val start = currentPoint ?: return moveTo(endPoint)
    val chord = Vec3(endPoint).sub(start)
    // One circle leaves `start` along a tangent and passes through `endPoint`. Use the start
    // tangent; fall back to the end tangent (reflected through the chord) if that is degenerate.
    val tan = if (startTangent.lengthSquared() > 1e-12f) Vec3(startTangent).normalize() else {
        val e = Vec3(endTangent).normalize()
        val cn = Vec3(chord).normalize()
        Vec3(cn).mul(2 * e.dot(cn)).sub(e)
    }
    val normal = Vec3(tan).cross(chord)
    if (normal.lengthSquared() < 1e-12f * max(1f, chord.lengthSquared())) return lineTo(endPoint)
    normal.normalize()
    // Toward the center, in the plane of the tangent and the chord.
    val inward = Vec3(normal).cross(tan).normalize()
    val radius = chord.lengthSquared() / (2 * chord.dot(inward))
    val center = Vec3(start).add(Vec3(inward).mul(radius))

    val u = Vec3(start).sub(center).normalize()
    val q = Vec3(endPoint).sub(center)
    var sweep = atan2(q.dot(tan), q.dot(u))
    if (sweep < 0f) sweep += 2 * PI.toFloat()
    appendArcPoints(center, u, tan, radius, sweep, segments)
    return this
}

/** The unit tangent at the end of the arc [byAppendingArcByTangent] draws from `start` along `tan`. */
private fun arcEndTangent(start: Vec3, tan: Vec3, end: Vec3): Vec3 {
    // A circular arc's end tangent is its start tangent reflected through the chord.
    val cn = Vec3(end).sub(start).normalize()
    return Vec3(cn).mul(2 * tan.dot(cn)).sub(tan).normalize()
}

// ==================== NOISE UTILITIES ====================

/**
 * Apply Gaussian noise to this curve.
 * @param amplitude noise amplitude
 * @param seed random seed for reproducibility
 * @param samples number of points to sample and reconstruct
 * @return a new noised FLine
 */
fun FLine.byGaussianNoising(
    amplitude: Float,
    seed: Long = System.currentTimeMillis(),
    samples: Int = 100,
    sampler: FLineSampler? = null
): FLine {
    val random = java.util.Random(seed)
    val points = this.samplePoints(samples, sampler = sampler)

    val result = FLine()
    points.forEachIndexed { i, point ->
        // Generate Gaussian noise (Box-Muller transform)
        val u1 = random.nextFloat()
        val u2 = random.nextFloat()
        val r = sqrt(-2.0 * ln(u1.toDouble())).toFloat()
        val theta = 2.0 * PI * u2

        val noiseX = r * cos(theta).toFloat() * amplitude
        val noiseY = r * sin(theta).toFloat() * amplitude
        val noiseZ = (random.nextGaussian().toFloat()) * amplitude

        val noisedPoint = vec(
            point.x + noiseX,
            point.y + noiseY,
            point.z + noiseZ
        )

        if (i == 0) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Apply direction-sensitive (oriented) Gaussian noise to this curve.
 * Noise is applied primarily along the normal direction.
 * @param normalAmplitude noise amplitude in normal direction
 * @param tangentAmplitude noise amplitude in tangent direction
 * @param seed random seed for reproducibility
 * @param samples number of points to sample
 * @return a new noised FLine
 */
fun FLine.byOrientedGaussianNoising(
    normalAmplitude: Float,
    tangentAmplitude: Float = 0f,
    seed: Long = System.currentTimeMillis(),
    samples: Int = 100
): FLine {
    val random = java.util.Random(seed)
    val cursor = this.cursor(0.01f)
    val result = FLine()

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val tangent = cursor.tangent()
        val normal = cursor.normal2D()

        // Generate Gaussian noise
        val u1 = random.nextFloat()
        val u2 = random.nextFloat()
        val r = sqrt(-2.0 * ln(u1.toDouble())).toFloat()
        val theta = 2.0 * PI * u2

        val normalNoise = r * cos(theta).toFloat() * normalAmplitude
        val tangentNoise = r * sin(theta).toFloat() * tangentAmplitude

        val noisedPoint = if (normal != null && tangent != null) {
            val normalVec = Vec3(normal.x, normal.y, 0f).normalize()
            val tangentVec = Vec3(tangent).normalize()

            Vec3(pos)
                .add(normalVec.mul(normalNoise, Vec3()))
                .add(tangentVec.mul(tangentNoise, Vec3()))
        } else {
            Vec3(pos)
        }

        if (i == 0) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Apply Perlin noise to this curve.
 * @param amplitude noise amplitude
 * @param frequency noise frequency (higher = more variation)
 * @param octaves number of noise octaves (more = more detail)
 * @param seed random seed for reproducibility
 * @param samples number of points to sample
 * @return a new noised FLine
 */
fun FLine.byPerlinNoising(
    amplitude: Float,
    frequency: Float = 1f,
    octaves: Int = 4,
    seed: Long = System.currentTimeMillis(),
    samples: Int = 100
): FLine {
    val perlin = PerlinNoise(seed)
    val cursor = this.cursor(0.01f)
    val result = FLine()

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2D()

        // Generate Perlin noise at this position
        val noiseX = perlin.octaveNoise(pos.x * frequency, pos.y * frequency, 0.1f, octaves)
        val noiseY = perlin.octaveNoise(pos.x * frequency, pos.y * frequency, 0.2f, octaves)
        val noiseZ = perlin.octaveNoise(pos.x * frequency, pos.y * frequency, 0.3f, octaves)

        val noisedPoint = if (normal != null) {
            // Apply noise primarily in normal direction
            val normalVec = Vec3(normal.x, normal.y, 0f).normalize()
            Vec3(pos).add(normalVec.mul(noiseX * amplitude, Vec3()))
        } else {
            vec(
                pos.x + noiseX * amplitude,
                pos.y + noiseY * amplitude,
                pos.z + noiseZ * amplitude
            )
        }

        if (i == 0) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Simple Perlin noise implementation for internal use.
 */
private class PerlinNoise(seed: Long) {
    private val random = kotlin.random.Random(seed)
    private val permutation = IntArray(256) { it }.apply { shuffle(random) }
    private val p = IntArray(512) { permutation[it % 256] }

    fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val zi = floor(z).toInt() and 255

        val xf = x - floor(x)
        val yf = y - floor(y)
        val zf = z - floor(z)

        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)

        val aaa = p[p[p[xi] + yi] + zi]
        val aba = p[p[p[xi] + yi + 1] + zi]
        val aab = p[p[p[xi] + yi] + zi + 1]
        val abb = p[p[p[xi] + yi + 1] + zi + 1]
        val baa = p[p[p[xi + 1] + yi] + zi]
        val bba = p[p[p[xi + 1] + yi + 1] + zi]
        val bab = p[p[p[xi + 1] + yi] + zi + 1]
        val bbb = p[p[p[xi + 1] + yi + 1] + zi + 1]

        var x1 = lerp(grad(aaa, xf, yf, zf), grad(baa, xf - 1, yf, zf), u)
        var x2 = lerp(grad(aba, xf, yf - 1, zf), grad(bba, xf - 1, yf - 1, zf), u)
        val y1 = lerp(x1, x2, v)

        x1 = lerp(grad(aab, xf, yf, zf - 1), grad(bab, xf - 1, yf, zf - 1), u)
        x2 = lerp(grad(abb, xf, yf - 1, zf - 1), grad(bbb, xf - 1, yf - 1, zf - 1), u)
        val y2 = lerp(x1, x2, v)

        return lerp(y1, y2, w)
    }

    fun octaveNoise(x: Float, y: Float, z: Float, octaves: Int): Float {
        var total = 0f
        var frequency = 1f
        var amplitude = 1f
        var maxValue = 0f

        for (i in 0 until octaves) {
            total += noise(x * frequency, y * frequency, z * frequency) * amplitude
            maxValue += amplitude
            amplitude *= 0.5f
            frequency *= 2f
        }

        return total / maxValue
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

    private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
        return when (hash and 15) {
            0 -> x + y
            1 -> -x + y
            2 -> x - y
            3 -> -x - y
            4 -> x + z
            5 -> -x + z
            6 -> x - z
            7 -> -x - z
            8 -> y + z
            9 -> -y + z
            10 -> y - z
            11 -> -y - z
            12 -> y + x
            13 -> -y + z
            14 -> y - x
            else -> -y - z
        }
    }
}

// ==================== CURVE COMBINATION UTILITIES ====================

/**
 * Add two curves point-wise.
 * Samples both curves and adds corresponding points.
 */
fun FLine.byAddingWith(
    other: FLine,
    samples: Int = 100,
    sampler: FLineSampler? = null,
    otherSampler: FLineSampler? = sampler
): FLine {
    val points1 = this.samplePoints(samples, sampler = sampler)
    val points2 = other.samplePoints(samples, sampler = otherSampler)
    if (points1.isEmpty() || points2.isEmpty()) return FLine()

    val result = FLine()
    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val sum = Vec3(p1).add(p2)

        if (i == 0) {
            result.moveTo(sum)
        } else {
            result.lineTo(sum)
        }
    }

    return result
}

/**
 * Subtract one curve from another point-wise.
 */
fun FLine.bySubtractingWith(
    other: FLine,
    samples: Int = 100,
    sampler: FLineSampler? = null,
    otherSampler: FLineSampler? = sampler
): FLine {
    val points1 = this.samplePoints(samples, sampler = sampler)
    val points2 = other.samplePoints(samples, sampler = otherSampler)
    if (points1.isEmpty() || points2.isEmpty()) return FLine()

    val result = FLine()
    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val diff = Vec3(p1).sub(p2)

        if (i == 0) {
            result.moveTo(diff)
        } else {
            result.lineTo(diff)
        }
    }

    return result
}

/**
 * Project one curve onto another.
 * For each point on line1, finds the closest point on line2.
 */
fun FLine.byProjectingOnto(
    other: FLine,
    samples: Int = 100,
    sampler: FLineSampler? = null
): FLine {
    val points1 = this.samplePoints(samples, sampler = sampler)

    val result = FLine()
    points1.forEachIndexed { i, point ->
        val closest = other.cursor().setNearest(point).position()

        if (i == 0) {
            result.moveTo(closest)
        } else {
            result.lineTo(closest)
        }
    }

    return result
}

/**
 * Invert a curve through a point (geometric inversion).
 * Each point is inverted through a circle centered at 'center' with given 'radius'.
 */
fun FLine.byInverting(
    center: Vec2 = vec(0f, 0f),
    radius: Float = 100f,
    samples: Int = 100,
    sampler: FLineSampler? = null
): FLine {
    val points = this.samplePoints(samples, sampler = sampler)
    val radiusSq = radius * radius

    val result = FLine()
    points.forEachIndexed { i, point ->
        val dx = point.x - center.x
        val dy = point.y - center.y
        val distSq = dx * dx + dy * dy

        if (distSq < 1e-6f) {
            // Point at center, map to infinity (skip)
            return@forEachIndexed
        }

        val factor = radiusSq / distSq
        val inverted = vec(
            center.x + dx * factor,
            center.y + dy * factor,
            point.z
        )

        if (i == 0 || result.nodes.isEmpty()) {
            result.moveTo(inverted)
        } else {
            result.lineTo(inverted)
        }
    }

    return result
}

/**
 * Multiply two curves point-wise (component-wise multiplication of coordinates).
 */
fun FLine.byMultiplyingWith(
    other: FLine,
    samples: Int = 100,
    sampler: FLineSampler? = null,
    otherSampler: FLineSampler? = sampler
): FLine {
    val points1 = this.samplePoints(samples, sampler = sampler)
    val points2 = other.samplePoints(samples, sampler = otherSampler)
    if (points1.isEmpty() || points2.isEmpty()) return FLine()

    val result = FLine()
    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val product = vec(p1.x * p2.x, p1.y * p2.y, p1.z * p2.z)

        if (i == 0) {
            result.moveTo(product)
        } else {
            result.lineTo(product)
        }
    }

    return result
}

// ==================== PARAMETERIZATION UTILITIES ====================

/**
 * Interface for parameter functions that can vary along a curve.
 * This allows any numeric parameter to be a function of position along the curve.
 */
interface ParameterFunction {
    /**
     * Evaluate the parameter at normalized position [t] and curve [position].
     */
    fun evaluate(t: Float, position: Vec3): Float
}

/**
 * Constant parameter (doesn't vary).
 */
class ConstantParameter(val value: Float) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3) = value

    override fun toString(): String = prettySummary("ConstantParameter", "value" to prettyNumber(value))
}

/**
 * Linear interpolation from start to end value.
 */
class LinearParameter(val start: Float, val end: Float) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3) = start + (end - start) * t

    override fun toString(): String = prettySummary(
        "LinearParameter",
        "start" to prettyNumber(start),
        "end" to prettyNumber(end)
    )
}

/**
 * Parameter driven by another curve's Y coordinate.
 */
class CurveParameter(val curve: FLine, val scale: Float = 1f, val offset: Float = 0f) : ParameterFunction {
    private val cursor = curve.cursor()

    override fun evaluate(t: Float, position: Vec3): Float {
        cursor.setT(t * (curve.nodes.size - 1))
        return cursor.position().y * scale + offset
    }

    override fun toString(): String = prettySummary(
        "CurveParameter",
        "curve" to curve,
        "scale" to prettyNumber(scale),
        "offset" to prettyNumber(offset)
    )
}

/**
 * Parameter driven by distance from a point.
 */
class DistanceParameter(
    val point: Vec3,
    val minValue: Float,
    val maxValue: Float,
    val maxDistance: Float
) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3): Float {
        val dist = position.distance(point)
        val normalized = (dist / maxDistance).coerceIn(0f, 1f)
        return minValue + (maxValue - minValue) * normalized
    }

    override fun toString(): String = prettySummary(
        "DistanceParameter",
        "point" to point,
        "minValue" to prettyNumber(minValue),
        "maxValue" to prettyNumber(maxValue),
        "maxDistance" to prettyNumber(maxDistance)
    )
}

/**
 * Parameter driven by curvature.
 */
class CurvatureParameter(
    val line: FLine,
    val minValue: Float,
    val maxValue: Float,
    val maxCurvature: Float = 1f
) : ParameterFunction {
    private val cursor = line.cursor()

    override fun evaluate(t: Float, position: Vec3): Float {
        cursor.setT(t * (line.nodes.size - 1))
        if (cursor.tangent() != null) {
            val curvature = cursor.curvature()
            val normalized = (curvature / maxCurvature).coerceIn(0f, 1f)
            return minValue + (maxValue - minValue) * normalized
        }

        return minValue
    }

    override fun toString(): String = prettySummary(
        "CurvatureParameter",
        "line" to line,
        "minValue" to prettyNumber(minValue),
        "maxValue" to prettyNumber(maxValue),
        "maxCurvature" to prettyNumber(maxCurvature)
    )
}

/**
 * Sine wave parameter.
 */
class SineParameter(
    val amplitude: Float,
    val frequency: Float,
    val phase: Float = 0f,
    val offset: Float = 0f
) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3) =
        offset + amplitude * sin(t * frequency * 2 * PI.toFloat() + phase)

    override fun toString(): String = prettySummary(
        "SineParameter",
        "amplitude" to prettyNumber(amplitude),
        "frequency" to prettyNumber(frequency),
        "phase" to prettyNumber(phase),
        "offset" to prettyNumber(offset)
    )
}

/**
 * Apply a parameterized offset to a curve.
 * The offset distance varies along the curve according to the parameter function.
 */
fun FLine.byOffsettingParameterized(
    distanceFunc: ParameterFunction,
    samples: Int = 100
): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1)
        cursor.setT(t * (this.nodes.size - 1))

        val pos = cursor.position()
        val normal = cursor.normal2D()
        val distance = distanceFunc.evaluate(t, pos)

        // Skip samples with a degenerate normal or a non-finite distance so we never emit a
        // NaN/Inf or wildly-out-of-range point.
        if (normal != null && distance.isFinite()) {
            val offsetPos = vec(
                pos.x + normal.x * distance,
                pos.y + normal.y * distance,
                pos.z
            )

            // Decide moveTo/lineTo by what has actually been emitted: the first sample can be
            // skipped, in which case the first surviving point must still start the path.
            if (result.nodes.isEmpty()) {
                result.moveTo(offsetPos)
            } else {
                result.lineTo(offsetPos)
            }
        }
    }

    return result
}

/**
 * Apply parameterized noise to a curve.
 * The noise amplitude varies according to the parameter function.
 */
fun FLine.byNoisingParameterized(
    amplitudeFunc: ParameterFunction,
    seed: Long = System.currentTimeMillis(),
    samples: Int = 100
): FLine {
    val random = java.util.Random(seed)
    val cursor = this.cursor(0.01f)
    val result = FLine()

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1)
        cursor.setT(t * (this.nodes.size - 1))

        val pos = cursor.position()
        val normal = cursor.normal2D()
        val amplitude = amplitudeFunc.evaluate(t, pos)

        // Generate Gaussian noise (Box-Muller). random.nextFloat() can return exactly 0,
        // which makes ln(u1) = -Inf and r = Inf; clamp u1 away from 0 and cap the resulting
        // magnitude so a near-zero draw can't fling the point to infinity.
        val u1 = random.nextFloat().coerceIn(1e-7f, 1f)
        val u2 = random.nextFloat()
        val r = sqrt(-2.0 * ln(u1.toDouble())).toFloat().coerceAtMost(4.0f)
        val theta = 2.0 * PI * u2

        val noise = r * cos(theta).toFloat() * amplitude

        // Only displace along a valid unit normal with finite noise; otherwise keep the point
        // on the source curve rather than emitting a degenerate outlier.
        val noisedPoint = if (normal != null && noise.isFinite()) {
            val normalVec = Vec3(normal.x, normal.y, 0f)
            Vec3(pos).add(normalVec.mul(noise, Vec3()))
        } else {
            Vec3(pos)
        }

        if (result.nodes.isEmpty()) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Apply parameterized scaling to a curve around a center point.
 */
fun FLine.byScalingParameterized(
    scaleFunc: ParameterFunction,
    center: Vec2 = vec(0f, 0f),
    samples: Int = 100,
    sampler: FLineSampler? = null
): FLine {
    val series = this.sampleSeries(samples, sampler = sampler)
    if (series.isEmpty()) return this.copy()
    val result = FLine()

    series.forEachIndexed { i, sample ->
        val point = sample.point
        val t = sample.t
        val scale = scaleFunc.evaluate(t, point)

        val scaled = vec(
            center.x + (point.x - center.x) * scale,
            center.y + (point.y - center.y) * scale,
            point.z
        )

        if (i == 0) {
            result.moveTo(scaled)
        } else {
            result.lineTo(scaled)
        }
    }

    return result
}

/**
 * Create a parameter function from a simple lambda.
 */
fun parameterFromLambda(func: (t: Float, pos: Vec3) -> Float): ParameterFunction {
    return object : ParameterFunction {
        override fun evaluate(t: Float, position: Vec3) = func(t, position)
    }
}

// ==================== ADVANCED / EXPERIMENTAL CURVES ====================

/**
 * Blend two sampled point sets with an optional parameterized dry/wet control.
 * Values outside 0..1 are allowed for intentional overshoot.
 */
private fun mixSamples(
    base: List<Vec3>,
    processed: List<Vec3>,
    dryWet: Float,
    modulator: ParameterFunction? = null
): List<Vec3> {
    val count = min(base.size, processed.size)
    val blended = ArrayList<Vec3>(count)
    for (i in 0 until count) {
        val t = if (count <= 1) 0f else i.toFloat() / (count - 1)
        val wet = modulator?.evaluate(t, processed[i]) ?: dryWet
        val dry = 1f - wet
        blended.add(Vec3(base[i]).mul(dry).add(Vec3(processed[i]).mul(wet)))
    }
    return blended
}

private fun List<Vec3>.toFLine(): FLine {
    val f = FLine()
    if (this.isNotEmpty()) {
        f.moveTo(this[0])
        for (i in 1 until this.size) {
            f.lineTo(this[i])
        }
    }
    return f
}

private fun normalizedArcLengthParams(points: List<Vec3>): DoubleArray {
    val n = points.size
    val params = DoubleArray(n)
    if (n == 0) return params
    var total = 0.0
    for (i in 1 until n) {
        total += points[i].distance(points[i - 1]).toDouble()
        params[i] = total
    }
    if (total > 0.0) {
        for (i in params.indices) params[i] /= total
    }
    return params
}

private fun alphaFromCutoff(fcHz: Double, dt: Double): Double {
    val dtc = dt.coerceAtLeast(1e-6)
    val tau = 1.0 / (2.0 * PI * fcHz.coerceAtLeast(1e-6))
    return 1.0 / (1.0 + tau / dtc)
}

private fun normalizeAngle(angle: Float): Float {
    var a = angle
    val twoPi = (2f * PI.toFloat())
    while (a > PI) a -= twoPi
    while (a < -PI) a += twoPi
    return a
}

// ---------- Higher-order B-splines ----------

/**
 * A B-spline of [degree] through [controlPoints], evaluated with Cox-de Boor at [samples] points.
 * Open, the knots are clamped, so the curve starts at the first control point and ends at the last.
 * [closed] wraps the first [degree] points round to the end and uses uniform knots, so the curve
 * is a smooth loop whose last sample is its first; it passes through none of the control points.
 */
fun bsplineFrom(
    controlPoints: List<Vec3>,
    degree: Int = 3,
    samples: Int = 200,
    closed: Boolean = false
): FLine {
    if (controlPoints.size < degree + 1) return FLine()

    // Appended with addAll, not `controlPoints + ...`: on a List<Vec3> that `+` adds the two lists
    // point by point (and throws when their sizes differ), so closed curves used to throw.
    val cp = if (closed) controlPoints.toMutableList().apply { addAll(controlPoints.take(degree)) } else controlPoints

    val n = cp.size - 1
    val p = degree
    val knotCount = n + p + 2
    val knots = FloatArray(knotCount)

    if (closed) {
        // Uniform knots; the loop is the span where p + 1 basis functions overlap, [knots[p], knots[n + 1]].
        for (i in 0 until knotCount) knots[i] = i.toFloat() / (knotCount - 1)
    } else {
        for (i in 0..p) knots[i] = 0f
        val interior = n - p
        for (j in 1..interior) {
            knots[p + j] = j.toFloat() / (interior + 1).toFloat()
        }
        for (i in (p + interior + 1) until knotCount) knots[i] = 1f
    }
    val uStart = if (closed) knots[p] else 0f
    val uEnd = if (closed) knots[n + 1] else 1f

    fun basis(u: Float): FloatArray {
        val N = FloatArray(n + 1)
        val temp = FloatArray(n + 1)
        for (i in 0..n) {
            val inRange = (knots[i] <= u && u < knots[i + 1]) || (u == 1f && i == n)
            N[i] = if (inRange) 1f else 0f
        }
        for (k in 2..p + 1) {
            for (i in 0..n) {
                val denom1 = knots[i + k - 1] - knots[i]
                val denom2 = knots[i + k] - knots[i + 1]
                val term1 = if (denom1 > 1e-6f) (u - knots[i]) / denom1 * N[i] else 0f
                val term2 = if (denom2 > 1e-6f) (knots[i + k] - u) / denom2 * N.getOrElse(i + 1) { 0f } else 0f
                temp[i] = term1 + term2
            }
            System.arraycopy(temp, 0, N, 0, n + 1)
        }
        return N
    }

    val pts = ArrayList<Vec3>(samples)
    for (s in 0 until samples) {
        // A loop ends where it began; its end knot is outside every half-open basis span.
        if (closed && s == samples - 1 && s > 0) {
            pts.add(Vec3(pts[0]))
            continue
        }
        val u = if (samples == 1) uStart else uStart + (uEnd - uStart) * s.toFloat() / (samples - 1)
        val N = basis(u.coerceIn(uStart, uEnd))
        val pnt = Vec3()
        for (i in 0..n) {
            pnt.add(Vec3(cp[i]).mul(N[i]))
        }
        pts.add(pnt)
    }

    return pts.toFLine()
}

/**
 * Reinterpret an existing curve through a higher-order B-spline fit.
 * @param degree spline degree (3=cubic, 4=quartic, 5=quintic)
 * @param controlSampleCount how many samples from the original to treat as control points
 * @param dryWet mix factor against the original samples (can exceed 1 for exaggeration)
 */
fun FLine.byBSplineSmoothed(
    degree: Int = 4,
    controlSampleCount: Int = 24,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    closed: Boolean = false,
    controlSampler: FLineSampler? = null,
    outputSampler: FLineSampler? = null
): FLine {
    val cps = this.samplePoints(controlSampleCount, sampler = controlSampler)
    if (cps.isEmpty()) return this.copy()
    val spline = bsplineFrom(cps, degree, samples, closed)
    val base = this.samplePoints(samples, sampler = outputSampler)
    val processed = spline.samplePoints(samples, sampler = outputSampler)
    val blended = mixSamples(base, processed, dryWet, wetModulator)
    return blended.toFLine()
}

// ---------- Euler spiral (3D) ----------

private data class SpiralFrame(val origin: Vec3, val u: Vec3, val v: Vec3, val n: Vec3)

private fun projectOntoPlane(v: Vec3, normal: Vec3): Vec3 {
    val n = Vec3(normal)
    return Vec3(v).sub(n.mul(v.dot(n), Vec3()))
}

private fun orthonormalAround(normal: Vec3): Vec3 {
    val n = Vec3(normal)
    val ref = if (abs(n.x) < 0.6f) vec(1f, 0f, 0f) else vec(0f, 1f, 0f)
    val u = Vec3(n).cross(ref)
    return if (u.lengthSquared() < 1e-12f) vec(0f, 0f, 1f) else u.normalize()
}

private fun buildSpiralFrame(start: Vec3, end: Vec3, startDir: Vec3, endDir: Vec3): SpiralFrame {
    val chord = Vec3(end).sub(start)
    val startN = Vec3(startDir)
    val endN = Vec3(endDir)

    var n = Vec3(startN).cross(endN)
    if (n.lengthSquared() < 1e-6f) n = Vec3(startN).cross(chord)
    if (n.lengthSquared() < 1e-6f) n = Vec3(chord).cross(startN)
    if (n.lengthSquared() < 1e-6f) {
        val fallback = if (abs(chord.z) < 0.9f) vec(0f, 0f, 1f) else vec(0f, 1f, 0f)
        n = Vec3(chord).cross(fallback)
    }
    if (n.lengthSquared() < 1e-12f) n = vec(0f, 0f, 1f)
    n.normalize()

    var u = Vec3(chord).sub(Vec3(n).mul(chord.dot(n)))
    if (u.lengthSquared() < 1e-10f) u = projectOntoPlane(startN, n)
    if (u.lengthSquared() < 1e-10f) u = projectOntoPlane(endN, n)
    if (u.lengthSquared() < 1e-12f) u = orthonormalAround(n)
    u.normalize()
    val v = Vec3(n).cross(u).normalize()

    return SpiralFrame(start, u, v, n)
}

private fun signedAngleOnPlane(from: Vec3, to: Vec3, normal: Vec3): Float {
    val f = Vec3(from)
    val t = Vec3(to)
    val cross = Vec3(f).cross(t)
    val dot = f.dot(t).coerceIn(-1f, 1f)
    return atan2(cross.dot(normal), dot)
}

/**
 * Generalised Fresnel integral `∫₀¹ exp(i(a/2·t² + b·t + c)) dt`, returned as (real, imaginary).
 * Composite Simpson in doubles; the fit below keeps |a| small enough that 1024 panels are exact
 * to well under a float ulp.
 */
private fun generalizedFresnel(a: Double, b: Double, c: Double): Pair<Double, Double> {
    val n = 1024
    val h = 1.0 / n
    var re = 0.0
    var im = 0.0
    for (k in 0..n) {
        val t = k * h
        val w = if (k == 0 || k == n) 1.0 else if (k % 2 == 1) 4.0 else 2.0
        val phase = 0.5 * a * t * t + b * t + c
        re += w * cos(phase)
        im += w * sin(phase)
    }
    return Pair(re * h / 3.0, im * h / 3.0)
}

private fun wrapToPi(a: Double): Double {
    var x = a
    while (x > PI) x -= 2 * PI
    while (x <= -PI) x += 2 * PI
    return x
}

/**
 * Planar G1 Hermite clothoid (Bertolazzi & Frego, "G1 fitting with clothoids", 2015).
 *
 * Finds the curve with heading `θ(s) = θ0 + κ·s + ½κ'·s²` that leaves the origin at [theta0]
 * and reaches ([dx], [dy]) heading [theta0] + `(φ1 − φ0)`. Returns (κ, κ', L), or null when the
 * Newton solve for the single unknown `A` does not converge.
 */
private fun fitClothoidG1(dx: Double, dy: Double, phi0: Double, phi1: Double, maxIterations: Int): Triple<Double, Double, Double>? {
    val r = hypot(dx, dy)
    if (r < 1e-12) return null
    val delta = phi1 - phi0
    fun g(A: Double) = generalizedFresnel(2 * A, delta - A, phi0).second

    var A = 3.0 * (phi0 + phi1)
    var converged = false
    for (i in 0 until maxIterations) {
        val gA = g(A)
        if (abs(gA) < 1e-12) { converged = true; break }
        val h = 1e-6 * max(1.0, abs(A))
        val dg = (g(A + h) - g(A - h)) / (2 * h)
        if (abs(dg) < 1e-14) break
        A -= gA / dg
    }
    if (!converged && abs(g(A)) > 1e-9) return null

    val x = generalizedFresnel(2 * A, delta - A, phi0).first
    if (x <= 1e-12) return null
    val length = r / x
    return Triple((delta - A) / length, 2 * A / (length * length), length)
}

/**
 * Euler spiral (clothoid) between two points, solved exactly in a plane.
 *
 * The plane is the one spanned by the two directions (or the chord, when they are parallel).
 * Within it the curve meets both endpoints and both projected directions exactly; there is
 * no residual correction. Any part of `end − start` that leaves that plane is added as a
 * linear lift along the curve, so in 3D the curve is "planar clothoid plus a ramp" and its
 * tangents tilt out of the plane by `atan(lift / L)`.
 */
private fun eulerSpiralCore3D(
    start: Vec3,
    startDir: Vec3,
    end: Vec3,
    endDir: Vec3,
    samples: Int,
    iterations: Int,
    headingScale: Float,
    turn: Float
): List<Vec3> {
    val frame = buildSpiralFrame(start, end, startDir, endDir)
    val targetVec = Vec3(end).sub(start)
    val normalOffset = targetVec.dot(frame.n).toDouble()
    val dx = targetVec.dot(frame.u).toDouble()
    val dy = targetVec.dot(frame.v).toDouble()
    val sampleCount = max(samples, 2)

    fun headingOf(d: Vec3, fallback: Double): Double {
        val a = d.dot(frame.u).toDouble()
        val b = d.dot(frame.v).toDouble()
        return if (a * a + b * b > 1e-20) atan2(b, a) else fallback
    }

    val chordAngle = atan2(dy, dx)
    val theta0 = headingOf(startDir, chordAngle)
    val theta1 = headingOf(endDir, chordAngle)
    val phi0 = wrapToPi(theta0 - chordAngle)
    val phi1 = wrapToPi(phi0 + wrapToPi(theta1 - theta0) * headingScale) + turn

    val fit = fitClothoidG1(dx, dy, phi0, phi1, max(iterations, 40))
        ?: return List(sampleCount) { i ->
            Vec3(start).add(Vec3(targetVec).mul(i.toFloat() / (sampleCount - 1)))
        }
    val (kappa, kappaPrime, length) = fit

    fun heading(s: Double) = theta0 + kappa * s + 0.5 * kappaPrime * s * s

    val pts = ArrayList<Vec3>(sampleCount)
    pts.add(Vec3(start))
    val ds = length / (sampleCount - 1)
    var x = 0.0
    var y = 0.0
    for (i in 1 until sampleCount) {
        // Simpson over [s0, s0 + ds] with 8 panels.
        val s0 = (i - 1) * ds
        val panels = 8
        val hh = ds / panels
        var cx = 0.0
        var cy = 0.0
        for (k in 0..panels) {
            val w = if (k == 0 || k == panels) 1.0 else if (k % 2 == 1) 4.0 else 2.0
            val th = heading(s0 + k * hh)
            cx += w * cos(th)
            cy += w * sin(th)
        }
        x += cx * hh / 3.0
        y += cy * hh / 3.0
        val t = i.toDouble() / (sampleCount - 1)
        val p = Vec3(start)
            .add(Vec3(frame.u).mul(x.toFloat()))
            .add(Vec3(frame.v).mul(y.toFloat()))
            .add(Vec3(frame.n).mul((normalOffset * t).toFloat()))
        pts.add(p)
    }
    // Float rounding only: the integration is exact to ~1e-9 of the chord.
    pts[pts.lastIndex] = Vec3(end)
    return pts
}

/**
 * Build an Euler spiral (clothoid) from [start] to [end] that leaves along [startDirection]
 * and arrives along [endDirection].
 *
 * The curve is solved exactly in the plane of the two directions: both endpoints and both
 * directions (projected into that plane) are met, and curvature changes linearly with arc
 * length. If [end] is out of that plane, the difference is added as a linear lift, so the
 * 3D tangents tilt by the lift's slope.
 *
 * @param samples number of points in the returned polyline
 * @param iterations maximum Newton iterations for the fit (at least 40 are always used)
 * @param lengthScale ignored: a clothoid meeting both ends is unique; kept for source compatibility
 * @param headingScale scales the turn from start to end direction (1 meets [endDirection])
 * @param turn extra heading, in radians, added at the end (2π adds a loop)
 */
fun eulerSpiralBetween3D(
    start: Vec3,
    startDirection: Vec3,
    end: Vec3,
    endDirection: Vec3,
    samples: Int = 200,
    iterations: Int = 6,
    lengthScale: Float = 1f,
    headingScale: Float = 1f,
    turn: Float = 0f
): FLine {
    val pts = eulerSpiralCore3D(
        start = start,
        startDir = startDirection,
        end = end,
        endDir = endDirection,
        samples = samples,
        iterations = iterations,
        headingScale = headingScale,
        turn = turn
    )
    return pts.toFLine()
}

private fun FLine.outgoingTangent3D(): Vec3? {
    if (nodes.isEmpty()) return null
    val last = nodes.last()
    // endPointOf, not `.to`: `?.` guards a missing node, not a Close's empty storage.
    val prev = if (nodes.size >= 2) endPointOf(nodes.size - 2) else null
    return when (last) {
        is FLine.CubicTo -> Vec3(last.to).sub(last.c2)
        is FLine.LineTo -> if (prev != null) Vec3(last.to).sub(prev) else null
        else -> null
    }
}

/**
 * Return a new line with an Euler spiral appended from the current end to [endPoint].
 */
fun FLine.byEulerSpiralTo(
    endPoint: Vec3,
    startDirection: Vec3? = null,
    endDirection: Vec3? = null,
    samples: Int = 200,
    iterations: Int = 6,
    lengthScale: Float = 1f,
    headingScale: Float = 1f,
    turn: Float = 0f
): FLine {
    // `?.` guards a missing node, not a Close's empty storage — currentPoint guards both.
    val start = currentPoint ?: vec(0f, 0f, 0f)
    val startDirResolved = startDirection ?: outgoingTangent3D() ?: Vec3(endPoint).sub(start)
    val endDirResolved = endDirection ?: Vec3(endPoint).sub(start)

    val spiral = eulerSpiralBetween3D(
        start = start,
        startDirection = startDirResolved,
        end = endPoint,
        endDirection = endDirResolved,
        samples = samples,
        iterations = iterations,
        lengthScale = lengthScale,
        headingScale = headingScale,
        turn = turn
    )

    val result = this.copy()
    if (result.nodes.isEmpty()) result.moveTo(start)
    spiral.nodes.drop(1).forEach { result.lineTo(it.to) }
    return result
}

/**
 * Append an Euler spiral from the current end to [endPoint].
 */
fun FLine.eulerSpiralTo(
    endPoint: Vec3,
    startDirection: Vec3? = null,
    endDirection: Vec3? = null,
    samples: Int = 200,
    iterations: Int = 6,
    lengthScale: Float = 1f,
    headingScale: Float = 1f,
    turn: Float = 0f
): FLine {
    if (breakNext) {
        breakNext = false
        return moveTo(endPoint)
    }
    if (nodes.isEmpty()) return moveTo(endPoint)

    val start = currentPoint ?: return moveTo(endPoint)
    val startDirResolved = startDirection ?: outgoingTangent3D() ?: Vec3(endPoint).sub(start)
    val endDirResolved = endDirection ?: Vec3(endPoint).sub(start)

    val spiral = eulerSpiralBetween3D(
        start = start,
        startDirection = startDirResolved,
        end = endPoint,
        endDirection = endDirResolved,
        samples = samples,
        iterations = iterations,
        lengthScale = lengthScale,
        headingScale = headingScale,
        turn = turn
    )

    val pts = spiral.nodes
    if (pts.size <= 1) return lineTo(endPoint)
    pts.drop(1).forEach { lineTo(it.to) }
    return this
}

/**
 * Numeric overload of [eulerSpiralTo] using the active 2D plane.
 */
fun FLine.eulerSpiralTo(
    x: Number,
    y: Number,
    z: Number = 0,
    startDirection: Vec3? = null,
    endDirection: Vec3? = null,
    samples: Int = 200,
    iterations: Int = 6,
    lengthScale: Float = 1f,
    headingScale: Float = 1f,
    turn: Float = 0f
): FLine = eulerSpiralTo(
    planePoint(x, y, z),
    startDirection,
    endDirection,
    samples,
    iterations,
    lengthScale,
    headingScale,
    turn
)

/**
 * 2D overload of [eulerSpiralTo] using the active 2D plane.
 */
fun FLine.eulerSpiralTo(
    v: Vec2,
    startDirection: Vec3? = null,
    endDirection: Vec3? = null,
    samples: Int = 200,
    iterations: Int = 6,
    lengthScale: Float = 1f,
    headingScale: Float = 1f,
    turn: Float = 0f
): FLine = eulerSpiralTo(
    planePoint(v.x, v.y, 0f),
    startDirection,
    endDirection,
    samples,
    iterations,
    lengthScale,
    headingScale,
    turn
)

// ---------- Curvature-driven evolution ----------

/**
 * Iterate mean-curvature flow on a sampled polyline.
 * @param step how far to move toward the discrete Laplacian each iteration
 * @param iterations number of passes to apply
 * @param anchorEnds keep the first/last points fixed while evolving
 */
fun FLine.byCurvatureFlow(
    step: Float = 0.25f,
    iterations: Int = 5,
    samples: Int = 200,
    anchorEnds: Boolean = true,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    sampler: FLineSampler? = null
): FLine {
    val base = this.samplePoints(samples, sampler = sampler).toMutableList()
    if (base.size < 3) return this.copy()

    var pts = base
    repeat(iterations) {
        val updated = MutableList(pts.size) { Vec3() }
        for (i in pts.indices) {
            updated[i] = if (anchorEnds && (i == 0 || i == pts.lastIndex)) {
                pts[i]
            } else {
                val prev = pts[i - 1]
                val next = pts[i + 1]
                val lap = Vec3(prev).add(next).mul(0.5f).sub(pts[i])
                Vec3(pts[i]).add(Vec3(lap).mul(step))
            }
        }
        pts = updated
    }

    val blended = mixSamples(base, pts, dryWet, wetModulator)
    return blended.toFLine()
}

// ---------- Gaussian Process interpolation ----------

/**
 * RBF-kernel Gaussian Process interpolation along arc length, applied independently to XYZ.
 * @param trainingSamples how many points to use from the source curve as observations
 * @param samples how many points to output
 * @param lengthScale kernel length scale (in normalized arc-length units)
 * @param variance kernel variance
 * @param noise observation standard deviation; its square is added to the training diagonal
 * @param drawSamples if true, draw a single sample from the posterior instead of using the mean
 */
fun FLine.byGaussianProcessInterpolating(
    trainingSamples: Int = 48,
    samples: Int = 200,
    lengthScale: Float = 0.15f,
    variance: Float = 1f,
    noise: Float = 1e-3f,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    drawSamples: Boolean = false,
    seed: Long? = null,
    trainingSampler: FLineSampler? = null,
    outputSampler: FLineSampler? = null
): FLine {
    require(trainingSamples >= 2 && samples >= 1)
    require(lengthScale.isFinite() && lengthScale > 0 && variance.isFinite() && variance > 0)
    require(noise.isFinite() && noise >= 0)
    val obs = this.samplePoints(trainingSamples, sampler = trainingSampler)
    if (obs.size < 2) return this.copy()
    val model = gpCurve(normalizedArcLengthParams(obs), obs,
        kernel = GpKernels.rbf(lengthScale.toDouble(), variance.toDouble()),
        noiseStd = DoubleArray(obs.size) { noise.toDouble() }, mean = vec(0, 0, 0))
    val base = this.samplePoints(samples, sampler = outputSampler)
    if (base.isEmpty()) return this.copy()
    val joint = model.posterior(normalizedArcLengthParams(base))
    val query = if (drawSamples) joint.samplePoints(seed ?: System.nanoTime()) else joint.meanPoints
    return mixSamples(base, query, dryWet, wetModulator).toFLine()
}

// ---------- Filtered geometry (EMA, One Euro, alpha-beta / 1$) ----------

/**
 * How a driver-style smoother is allowed to retarget the sampled source line.
 */
enum class FLineDriverTargetUpdateMode {
    /**
     * Never revisit earlier samples; target search only moves forward.
     */
    FORWARD_ONLY,

    /**
     * Always pick the closest sample inside a local search window, even if
     * that means stepping backwards on the source line.
     */
    WINDOWED_NEAREST,

    /**
     * Search a local window, but only step backwards when the earlier sample
     * is meaningfully closer than the current anchor.
     */
    STICKY_WINDOWED_NEAREST
}

/**
 * Configuration for [FLine.byDriverSmoothing].
 *
 * Angular quantities are expressed per output sample step because this
 * smoother operates on a resampled polyline rather than real time.
 */
data class FLineDriverSmoothingConfig(
    val maxAngularVelocity: Float = 0.3f,
    val maxAngularAcceleration: Float = 0.06f,
    val lookaheadSamples: Int = 4,
    val forwardSearchSamples: Int = 18,
    val backwardSearchSamples: Int = 6,
    val backwardRetargetHysteresis: Float = 0.15f,
    val linearStepScale: Float = 1f,
    val targetUpdateMode: FLineDriverTargetUpdateMode = FLineDriverTargetUpdateMode.STICKY_WINDOWED_NEAREST
) {
    init {
        require(maxAngularVelocity >= 0f) { "maxAngularVelocity must be >= 0" }
        require(maxAngularAcceleration >= 0f) { "maxAngularAcceleration must be >= 0" }
        require(lookaheadSamples >= 0) { "lookaheadSamples must be >= 0" }
        require(forwardSearchSamples >= 0) { "forwardSearchSamples must be >= 0" }
        require(backwardSearchSamples >= 0) { "backwardSearchSamples must be >= 0" }
        require(backwardRetargetHysteresis in 0f..0.999f) {
            "backwardRetargetHysteresis must be in [0, 1)"
        }
        require(linearStepScale >= 0f) { "linearStepScale must be >= 0" }
    }
}

private val driverForwardAxis = vec(1f, 0f, 0f)

private fun initialForwardDirection(points: List<Vec3>): Vec3 {
    for (i in 1 until points.size) {
        val delta = Vec3(points[i]).sub(points[i - 1])
        if (delta.lengthSquared() > 1e-8f) return delta.normalize()
    }
    return Vec3(driverForwardAxis)
}

private fun clampVectorMagnitude(v: Vec3, maxMagnitude: Float): Vec3 {
    if (maxMagnitude <= 0f) return vec(0f, 0f, 0f)
    val length = v.length()
    if (length <= maxMagnitude || length <= 1e-8f) return Vec3(v)
    return Vec3(v).mul(maxMagnitude / length)
}

private fun orthogonalUnit(v: Vec3): Vec3 {
    val base = if (abs(v.x) < 0.9f) vec(1f, 0f, 0f) else vec(0f, 1f, 0f)
    var ortho = Vec3(v).cross(base)
    if (ortho.lengthSquared() <= 1e-8f) ortho = Vec3(v).cross(vec(0f, 0f, 1f))
    return if (ortho.lengthSquared() <= 1e-8f) {
        vec(0f, 1f, 0f)
    } else {
        ortho.normalize()
    }
}

private fun shortestRotationAxisAngle(from: Vec3, to: Vec3): Pair<Vec3, Float> {
    val fromN = if (from.lengthSquared() <= 1e-8f) Vec3(driverForwardAxis) else Vec3(from).normalize()
    val toN = if (to.lengthSquared() <= 1e-8f) Vec3(driverForwardAxis) else Vec3(to).normalize()
    val cross = Vec3(fromN).cross(toN)
    val sinAngle = cross.length()
    val cosAngle = fromN.dot(toN).coerceIn(-1f, 1f)
    return if (sinAngle > 1e-6f) {
        Pair(cross.div(sinAngle), atan2(sinAngle, cosAngle))
    } else if (cosAngle < 0f) {
        Pair(orthogonalUnit(fromN), PI.toFloat())
    } else {
        Pair(vec(0f, 0f, 1f), 0f)
    }
}

private fun orientationFromForward(forward: Vec3): Quaternionf {
    val target = if (forward.lengthSquared() <= 1e-8f) Vec3(driverForwardAxis) else Vec3(forward).normalize()
    return Quaternionf().rotationToField(driverForwardAxis, target)
}

private fun nearestWindowedDriverAnchor(
    points: List<Vec3>,
    driverPosition: Vec3,
    currentAnchor: Int,
    config: FLineDriverSmoothingConfig
): Int {
    val last = points.lastIndex
    val clampedCurrent = currentAnchor.coerceIn(0, last)
    val currentDistance = driverPosition.distance(points[clampedCurrent])

    val searchStart = when (config.targetUpdateMode) {
        FLineDriverTargetUpdateMode.FORWARD_ONLY -> clampedCurrent
        FLineDriverTargetUpdateMode.WINDOWED_NEAREST,
        FLineDriverTargetUpdateMode.STICKY_WINDOWED_NEAREST -> (clampedCurrent - config.backwardSearchSamples).coerceAtLeast(0)
    }
    val searchEnd = (clampedCurrent + config.forwardSearchSamples).coerceAtMost(last)

    var bestIndex = clampedCurrent
    var bestDistance = currentDistance
    for (i in searchStart..searchEnd) {
        val distance = driverPosition.distance(points[i])
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = i
        }
    }

    return when (config.targetUpdateMode) {
        FLineDriverTargetUpdateMode.FORWARD_ONLY,
        FLineDriverTargetUpdateMode.WINDOWED_NEAREST -> bestIndex

        FLineDriverTargetUpdateMode.STICKY_WINDOWED_NEAREST -> {
            if (bestIndex >= clampedCurrent) {
                bestIndex
            } else {
                val requiredDistance = currentDistance * (1f - config.backwardRetargetHysteresis)
                if (bestDistance <= requiredDistance) bestIndex else clampedCurrent
            }
        }
    }
}

/**
 * Simple exponential moving average over sampled points.
 */
fun FLine.byEmaFiltering(
    alpha: Float = 0.18f,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    sampler: FLineSampler? = null
): FLine {
    val points = this.samplePoints(samples, sampler = sampler)
    if (points.isEmpty()) return this.copy()
    val filtered = MutableList(points.size) { Vec3() }
    var ema = Vec3(points.first())
    filtered[0] = Vec3(ema)
    for (i in 1 until points.size) {
        val raw = points[i]
        ema = vec(
            ema.x + (raw.x - ema.x) * alpha,
            ema.y + (raw.y - ema.y) * alpha,
            ema.z + (raw.z - ema.z) * alpha
        )
        filtered[i] = ema
    }
    val blended = mixSamples(points, filtered, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * One-Euro filter on 3D positions with a fixed dt inferred from an FPS hint.
 */
fun FLine.byOneEuroFiltering(
    minCutoffHz: Float = 1.5f,
    beta: Float = 0.03f,
    dCutoffHz: Float = 1f,
    fpsHint: Float = 60f,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    sampler: FLineSampler? = null
): FLine {
    val points = this.samplePoints(samples, sampler = sampler)
    if (points.isEmpty()) return this.copy()
    val dt = (1f / fpsHint.coerceAtLeast(1e-3f)).toDouble()
    var dxHat = vec(0f, 0f, 0f)
    var xHat = Vec3(points.first())
    val filtered = mutableListOf<Vec3>()
    filtered.add(Vec3(xHat))

    for (i in 1 until points.size) {
        val raw = points[i]
        val dx = Vec3(raw).sub(points[i - 1]).mul((1f / dt.toFloat()))
        val ad = alphaFromCutoff(dCutoffHz.toDouble(), dt).toFloat()
        dxHat = vec(
            dxHat.x + ad * (dx.x - dxHat.x),
            dxHat.y + ad * (dx.y - dxHat.y),
            dxHat.z + ad * (dx.z - dxHat.z)
        )
        val speed = dxHat.length()
        val fc = (minCutoffHz + beta * speed).toDouble()
        val a = alphaFromCutoff(fc, dt).toFloat()
        xHat = vec(
            xHat.x + a * (raw.x - xHat.x),
            xHat.y + a * (raw.y - xHat.y),
            xHat.z + a * (raw.z - xHat.z)
        )
        filtered.add(Vec3(xHat))
    }

    val blended = mixSamples(points, filtered, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * Lightweight constant-velocity alpha-beta filter applied to a curve.
 */
fun FLine.byAlphaBetaFiltering(
    alpha: Float = 0.25f,
    beta: Float = 0.08f,
    fpsHint: Float = 90f,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    sampler: FLineSampler? = null
): FLine {
    val points = this.samplePoints(samples, sampler = sampler)
    if (points.isEmpty()) return this.copy()
    val dt = (1f / fpsHint.coerceAtLeast(1e-3f))
    var x = Vec3(points.first())
    var v = vec(0f, 0f, 0f)
    val filtered = mutableListOf<Vec3>()
    filtered.add(Vec3(x))
    for (i in 1 until points.size) {
        val pred = x().add(Vec3(v).mul(dt))
        val residual = Vec3(points[i]).sub(pred)
        x = Vec3(pred).add(Vec3(residual).mul(alpha))
        v = Vec3(v).add(Vec3(residual).mul(beta / dt))
        filtered.add(Vec3(x))
    }
    val blended = mixSamples(points, filtered, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * Driver-style pursuit smoother over a sampled polyline.
 *
 * The output starts aligned with the first non-degenerate segment of the
 * sampled source, then steers toward a target sample with bounded angular
 * acceleration and bounded angular velocity. Targeting supports forward-only
 * motion, unrestricted local retargeting, or a sticky local mode that only
 * jumps backwards when an earlier point is clearly closer.
 */
fun FLine.byDriverSmoothing(
    config: FLineDriverSmoothingConfig = FLineDriverSmoothingConfig(),
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    sampler: FLineSampler? = null
): FLine {
    val points = this.samplePoints(samples, sampler = sampler)
    if (points.size < 2) return this.copy()

    val filtered = ArrayList<Vec3>(points.size)
    var driverPosition = Vec3(points.first())
    var orientation = orientationFromForward(initialForwardDirection(points))
    var angularVelocity = vec(0f, 0f, 0f)
    var anchorIndex = 0
    filtered += Vec3(driverPosition)

    for (i in 1 until points.size) {
        anchorIndex = nearestWindowedDriverAnchor(points, driverPosition, anchorIndex, config)
        val targetIndex = (anchorIndex + config.lookaheadSamples).coerceAtMost(points.lastIndex)
        val target = points[targetIndex]
        val toTarget = Vec3(target).sub(driverPosition)

        if (toTarget.lengthSquared() > 1e-8f) {
            val currentForward = driverForwardAxis * orientation
            val (rotationAxis, headingError) = shortestRotationAxisAngle(currentForward, toTarget)
            val desiredAngularVelocity = Vec3(rotationAxis).mul(min(headingError, config.maxAngularVelocity))
            val velocityDelta = clampVectorMagnitude(
                Vec3(desiredAngularVelocity).sub(angularVelocity),
                config.maxAngularAcceleration
            )
            angularVelocity = clampVectorMagnitude(
                Vec3(angularVelocity).add(velocityDelta),
                config.maxAngularVelocity
            )
        } else {
            angularVelocity = clampVectorMagnitude(
                Vec3(angularVelocity),
                config.maxAngularVelocity
            )
        }

        val angularSpeed = angularVelocity.length()
        if (angularSpeed > 1e-8f) {
            val rotationAxis = Vec3(angularVelocity).div(angularSpeed)
            val deltaQuat = Quaternionf().setAngleAxis(angularSpeed, rotationAxis.x, rotationAxis.y, rotationAxis.z)
            orientation = Quaternionf(deltaQuat).mul(orientation).normalize()
        }

        val stepLength = points[i].distance(points[i - 1]) * config.linearStepScale
        val currentForward = driverForwardAxis * orientation
        driverPosition = Vec3(driverPosition).add(Vec3(currentForward).mul(stepLength))
        filtered += Vec3(driverPosition)
    }

    val blended = mixSamples(points, filtered, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * One-Dollar style normalization/resampling (rotation + scale + re-sample).
 * Useful as a "filter" to regularize noisy gesture paths.
 */
fun FLine.byOneDollarFiltering(
    targetSamples: Int = 64,
    targetSize: Float = 200f,
    rotateToZero: Boolean = true,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    sampler: FLineSampler? = null
): FLine {
    val points = this.samplePoints(targetSamples, sampler = sampler)
    if (points.isEmpty()) return this.copy()

    val centroid = points.fold(Vec3()) { acc, p -> acc.add(p) }.div(points.size.toFloat())
    var normed = points.map { Vec3(it).sub(centroid) }

    if (rotateToZero && normed.size > 1) {
        val first = normed.first()
        val angle = atan2(first.y, first.x)
        val c = cos(-angle)
        val s = sin(-angle)
        normed = normed.map {
            vec(
                it.x * c - it.y * s,
                it.x * s + it.y * c,
                it.z
            )
        }
    }

    val bboxX = normed.minOf { it.x } to normed.maxOf { it.x }
    val bboxY = normed.minOf { it.y } to normed.maxOf { it.y }
    val scaleDenom = max(bboxX.second - bboxX.first, bboxY.second - bboxY.first).coerceAtLeast(1e-4f)
    val scale = targetSize / scaleDenom
    normed = normed.map { vec(it.x * scale, it.y * scale, it.z) }

    val blended = mixSamples(points, normed, dryWet, wetModulator)
    return blended.toFLine()
}


// ==================== SKETCH VERBS: CENTRIPETAL SPLINES, WIGGLE, ATOMIZE, TRANSFORM-TO ====================

/**
 * A smooth curve through every point of [points]: a centripetal Catmull-Rom spline, as cubic segments.
 *
 * [catmullRomToBezier] is the *uniform* spline, which overshoots and can loop where neighbouring
 * points are unevenly spaced, as they are in anything drawn by hand. The centripetal parameterisation
 * ([alpha] = 0.5, the default; 0 is uniform and 1 chordal) weights each tangent by the distance to the
 * neighbours, so the curve stays close to the hand's path and never forms a cusp or a self-loop within
 * a segment. The ends are clamped: the first and last points stand in for their missing neighbours.
 *
 * ```
 * val curve = catmullRomCentripetal(listOf(vec(0, 0), vec(40, 10), vec(60, 60), vec(140, 70)))
 * ```
 *
 * Fewer than three points give a polyline (one point gives a single `moveTo`).
 */
fun catmullRomCentripetal(points: List<Vec2>, alpha: Double = 0.5): FLine {
    val out = FLine()
    if (points.isEmpty()) return out
    out.moveTo(points[0].x, points[0].y)
    if (points.size < 3) {
        for (i in 1 until points.size) out.lineTo(points[i].x, points[i].y)
        return out
    }
    fun d(a: Vec2, b: Vec2): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val v = (dx * dx + dy * dy).pow(alpha * 0.5)
        return if (v < 1e-6) 1.0 else v
    }
    val n = points.size
    for (j in 0 until n - 1) {
        val p0 = points[max(0, j - 1)]
        val p1 = points[j]
        val p2 = points[min(n - 1, j + 1)]
        val p3 = points[min(n - 1, j + 2)]
        val d1 = d(p0, p1)
        val d2 = d(p1, p2)
        val d3 = d(p2, p3)
        val b1x = (d1 * d1 * p2.x - d2 * d2 * p0.x + (2 * d1 * d1 + 3 * d1 * d2 + d2 * d2) * p1.x) / (3 * d1 * (d1 + d2))
        val b1y = (d1 * d1 * p2.y - d2 * d2 * p0.y + (2 * d1 * d1 + 3 * d1 * d2 + d2 * d2) * p1.y) / (3 * d1 * (d1 + d2))
        val b2x = (d3 * d3 * p1.x - d2 * d2 * p3.x + (2 * d3 * d3 + 3 * d3 * d2 + d2 * d2) * p2.x) / (3 * d3 * (d3 + d2))
        val b2y = (d3 * d3 * p1.y - d2 * d2 * p3.y + (2 * d3 * d3 + 3 * d3 * d2 + d2 * d2) * p2.y) / (3 * d3 * (d3 + d2))
        out.cubicTo(b1x, b1y, 0, b2x, b2y, 0, p2.x, p2.y, 0)
    }
    return out
}

/**
 * Classic 2D Perlin noise with a fixed permutation table, in about -1..1, zero at integer lattice
 * points.
 *
 * The table is the one the madp sketch demos shuffle (`(i * 16807 + 37) % (i + 1)`), so a noise
 * field ported from them, and [byWiggling], moves exactly as it did there.
 */
object ClassicPerlin2D {
    private val perm: IntArray = run {
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = (i * 16807 + 37) % (i + 1)
            val tmp = p[i]; p[i] = p[j]; p[j] = tmp
        }
        IntArray(512) { p[it and 255] }
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(a: Double, b: Double, t: Double) = a + t * (b - a)
    private fun grad(hash: Int, x: Double, y: Double): Double {
        val h = hash and 3
        val u = if (h < 2) x else y
        val v = if (h < 2) y else x
        return (if (h and 1 != 0) -u else u) + (if (h and 2 != 0) -v else v)
    }

    /** The noise at ([x], [y]). */
    fun noise(x: Double, y: Double): Double {
        val fx = floor(x)
        val fy = floor(y)
        val xi = fx.toInt() and 255
        val yi = fy.toInt() and 255
        val xf = x - fx
        val yf = y - fy
        val u = fade(xf)
        val v = fade(yf)
        val aa = perm[perm[xi] + yi]
        val ab = perm[perm[xi] + yi + 1]
        val ba = perm[perm[xi + 1] + yi]
        val bb = perm[perm[xi + 1] + yi + 1]
        return lerp(
            lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u),
            lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u),
            v,
        )
    }
}

/**
 * Every node of this line (and every cubic's control points) pushed about by 2D Perlin noise
 * ([ClassicPerlin2D]): [amplitude] in the line's own units, [frequency] in noise cycles per unit, and
 * [phase] sliding the noise field, so `phase = t` makes the wiggle move.
 *
 * The x displacement is read first and the y displacement is read at the already-moved x, as the madp
 * line demo's `wiggle` does. Node properties (colour, point size, overshoot) are kept.
 */
fun FLine.byWiggling(amplitude: Double = 10.0, frequency: Double = 0.05, phase: Double = 0.0): FLine {
    val a = amplitude
    val f = frequency
    return transform { p ->
        val x = p.x + ClassicPerlin2D.noise(p.x * f + phase, p.y * f + phase + 100) * a
        val y = p.y + ClassicPerlin2D.noise(x * f + phase + 200, p.y * f + phase + 300) * a
        vec(x, y, p.z)
    }
}

/**
 * This line broken into separate segments: every drawn piece of it becomes its own `moveTo`/`lineTo`
 * subpath, between the same end points (a cubic is replaced by its chord). Each segment carries the
 * properties of the node that ended it.
 *
 * A line of separate pieces draws the same until something treats subpaths one by one: per-subpath
 * overshoot, [byWiggling] (which then moves the two ends of a join apart), or a dot at each end.
 */
fun FLine.byAtomizing(): FLine {
    val out = FLine()
    out.properties.putAll(properties)
    var at: Vec3? = null
    for (node in nodes) {
        when (node) {
            is FLine.MoveTo -> at = Vec3(node.to)
            is FLine.Close -> at = null
            else -> {
                val from = at
                val to = Vec3(node.to)
                if (from != null) {
                    out.moveTo(from)
                    out.lineTo(to)
                    out.here.properties.putAll(node.properties)
                }
                at = to
            }
        }
    }
    return out
}

/**
 * This line rotated, scaled and moved as one rigid shape so that its first node lands on [start] and
 * its last on [end]. A line whose two ends coincide is returned unchanged, since no rotation would be
 * defined.
 */
fun FLine.byTransformingTo(start: Vec2, end: Vec2): FLine {
    if (nodes.isEmpty()) return copy()
    val a = endPointOf(0)
    val b = endPointOf(nodes.size - 1)
    val sdx = (b.x - a.x).toDouble()
    val sdy = (b.y - a.y).toDouble()
    val sLen = hypot(sdx, sdy)
    if (sLen < 1e-6) return copy()
    val tdx = (end.x - start.x).toDouble()
    val tdy = (end.y - start.y).toDouble()
    val scale = hypot(tdx, tdy) / sLen
    val rot = atan2(tdy, tdx) - atan2(sdy, sdx)
    val c = cos(rot)
    val s = sin(rot)
    return transform { p ->
        val dx = (p.x - a.x).toDouble()
        val dy = (p.y - a.y).toDouble()
        vec(start.x + (dx * c - dy * s) * scale, start.y + (dx * s + dy * c) * scale, p.z)
    }
}

// ==================== G2 CLOTHOID SPLINE ====================

/**
 * One fitted segment of [clothoidSplineFrom]: start heading, start curvature, curvature slope, length.
 *
 * With [weight] 1 (the Euler spiral) curvature is `kappa + kappaPrime·s`. Any other weight is an S9
 * segment ([fitWeightedPiece]): with `t = s / length` and `u = t − ½`, curvature is
 * `(a + b·g(u)) / length` where `g(u) = ½·sign(u)·|2u|^(1/weight)`; [kappa] and [endKappa] are its
 * end values, and [bendA] / [bendB] the dimensionless `a`, `b`.
 */
private class ClothoidPiece(
    val theta: Double, val kappa: Double, val kappaPrime: Double, val length: Double,
    val weight: Double = 1.0, val bendA: Double = 0.0, val bendB: Double = 0.0,
) {
    val endKappa get() = if (weight == 1.0) kappa + kappaPrime * length else (bendA + 0.5 * bendB) / length

    /** Change of curvature across the piece times its length: zero for a circular arc. */
    val bendSpread get() = if (weight == 1.0) kappaPrime * length * length else bendB

    /** Heading at arc length [s]; the weight-1 expression is the one the drawing always used. */
    fun heading(s: Double): Double {
        if (weight == 1.0) return theta + kappa * s + 0.5 * kappaPrime * s * s
        val t = s / length
        return theta + bendA * t + bendB * s9Integral(t - 0.5, 1 / weight)
    }

    fun curvature(s: Double): Double {
        if (weight == 1.0) return kappa + kappaPrime * s
        return (bendA + bendB * s9Shape(s / length - 0.5, 1 / weight)) / length
    }
}

/** The S9 curvature shape `g(u) = ½·sign(u)·|2u|^e` on `u ∈ [−½, ½]`; `e = 1` is linear (Euler). */
private fun s9Shape(u: Double, e: Double): Double = 0.5 * sign(u) * abs(2 * u).pow(e)

/** `∫ from −½ to u of g`, which is `(|2u|^(e+1) − 1) / (4(e+1))`; zero at both ends of the segment. */
private fun s9Integral(u: Double, e: Double): Double = (abs(2 * u).pow(e + 1) - 1) / (4 * (e + 1))

/**
 * The S9 segment of [weight] from (ax, ay) heading [thetaA] to (bx, by) heading [thetaB].
 *
 * In the chord's frame, scaled to unit chord, the heading is `ψ(t) = φ0 + a·t + b·G(t)` with
 * `G = ∫g`. `G(1) = 0` because `g` is odd about the middle, so the turn fixes `a = φ1 − φ0`, and
 * `b` is the root of `∫₀¹ sin ψ dt = 0` (the end lands on the chord). Then `length = chord / ∫ cos ψ`.
 * The small-angle guess is `b = (φ0 + a/2) / ∫₀¹ (t − ½)·g dt`, which is `12(φ0 + a/2)` at weight 1;
 * Newton runs from there, falling back to bisection inside a bracket when a step leaves it.
 */
private fun fitWeightedPiece(ax: Double, ay: Double, bx: Double, by: Double, thetaA: Double, thetaB: Double, weight: Double): ClothoidPiece? {
    val dx = bx - ax
    val dy = by - ay
    val chordLength = hypot(dx, dy)
    if (chordLength < 1e-12) return null
    val chord = atan2(dy, dx)
    val phi0 = wrapToPi(thetaA - chord)
    val phi1 = wrapToPi(thetaB - chord)
    val a = phi1 - phi0
    val e = 1 / weight
    val panels = 64
    fun integrals(b: Double): Pair<Double, Double> {
        var c = 0.0
        var sn = 0.0
        for (q in 0..panels) {
            val w = if (q == 0 || q == panels) 1.0 else if (q % 2 == 1) 4.0 else 2.0
            val t = q.toDouble() / panels
            val psi = phi0 + a * t + b * s9Integral(t - 0.5, e)
            c += w * cos(psi); sn += w * sin(psi)
        }
        return c / (3.0 * panels) to sn / (3.0 * panels)
    }
    // ∫ (t − ½)·g dt over the segment is 1 / (4(e + 2)) in closed form
    var b = (phi0 + 0.5 * a) * 4 * (e + 2)
    var lo = Double.NaN
    var hi = Double.NaN
    var converged = false
    for (iter in 0 until 80) {
        val f = integrals(b).second
        if (abs(f) < 1e-13) { converged = true; break }
        // f falls as b grows near the root; keep a bracket for bisection
        if (f > 0) lo = b else hi = b
        val h = 1e-6 * max(1.0, abs(b))
        val df = (integrals(b + h).second - integrals(b - h).second) / (2 * h)
        var next = if (abs(df) > 1e-14) b - f / df else Double.NaN
        val bracketed = !lo.isNaN() && !hi.isNaN()
        if (bracketed && (next.isNaN() || next <= min(lo, hi) || next >= max(lo, hi))) next = 0.5 * (lo + hi)
        if (next.isNaN()) return null
        if (abs(next - b) < 1e-14 * max(1.0, abs(b))) { b = next; converged = true; break }
        b = next
    }
    if (!converged && abs(integrals(b).second) > 1e-9) return null
    val x = integrals(b).first
    if (x <= 1e-12) return null
    val length = chordLength / x
    return ClothoidPiece(thetaA, (a - 0.5 * b) / length, 0.0, length, weight, a, b)
}

private fun fitClothoidPiece(ax: Double, ay: Double, bx: Double, by: Double, thetaA: Double, thetaB: Double): ClothoidPiece? {
    val dx = bx - ax
    val dy = by - ay
    val chord = atan2(dy, dx)
    val phi0 = wrapToPi(thetaA - chord)
    val phi1 = wrapToPi(thetaB - chord)
    val fit = fitClothoidG1(dx, dy, phi0, phi1, 60) ?: return null
    return ClothoidPiece(thetaA, fit.first, fit.second, fit.third)
}

/**
 * Tangent angles at the knots such that neighbouring clothoid segments meet with equal curvature.
 *
 * Open curves need one condition at each end: a fixed heading ([startTheta], [endTheta]) when
 * given, otherwise "the end segment is a circular arc" (κ' = 0). A two-knot open curve needs at
 * least one fixed heading. Returns the fitted pieces, or null when Newton fails (the caller then
 * falls back to a cubic).
 *
 * Residual `i` depends only on the headings at knots `i − 1`, `i` and `i + 1`, so the Jacobian is
 * tridiagonal (cyclic for a closed curve): each Newton step refits two segments per knot and solves
 * in O(n), and a curve through hundreds of knots costs about what the drawing does.
 */
private fun solveClothoidSpline(
    xs: DoubleArray, ys: DoubleArray, closed: Boolean, iterations: Int,
    startTheta: Double? = null, endTheta: Double? = null, weight: Double = 1.0,
): List<ClothoidPiece>? {
    val n = xs.size
    val segs = if (closed) n else n - 1
    if (segs < 1 || (closed && n < 3)) return null
    fun nxt(i: Int) = (i + 1) % n

    // Initial headings: Catmull-Rom directions.
    val theta = DoubleArray(n) { i ->
        val p = if (closed) (i - 1 + n) % n else max(i - 1, 0)
        val q = if (closed) nxt(i) else min(i + 1, n - 1)
        atan2(ys[q] - ys[p], xs[q] - xs[p])
    }
    if (!closed && n >= 3) {
        // reflect the neighbouring direction about the end chord, the circle-through-three-points guess
        val c0 = atan2(ys[1] - ys[0], xs[1] - xs[0])
        theta[0] = 2 * c0 - theta[1]
        val c1 = atan2(ys[n - 1] - ys[n - 2], xs[n - 1] - xs[n - 2])
        theta[n - 1] = 2 * c1 - theta[n - 2]
    }
    // A fixed end heading is taken within π of its chord, so the end piece turns the short way.
    val fixedStart = if (closed || startTheta == null) null else {
        val c = atan2(ys[1] - ys[0], xs[1] - xs[0]); c + wrapToPi(startTheta - c)
    }
    val fixedEnd = if (closed || endTheta == null) null else {
        val c = atan2(ys[n - 1] - ys[n - 2], xs[n - 1] - xs[n - 2]); c + wrapToPi(endTheta - c)
    }
    if (fixedStart != null) theta[0] = fixedStart
    if (fixedEnd != null) theta[n - 1] = fixedEnd
    if (!closed && n == 2 && fixedStart == null && fixedEnd == null) return null
    if (!closed && n == 2) {
        // with one free end its first guess is the reflection of the fixed one about the chord
        val c = atan2(ys[1] - ys[0], xs[1] - xs[0])
        if (fixedStart == null) theta[0] = 2 * c - theta[1]
        if (fixedEnd == null) theta[1] = 2 * c - theta[0]
    }

    fun fitSeg(j: Int, th: DoubleArray) =
        if (weight == 1.0) fitClothoidPiece(xs[j], ys[j], xs[nxt(j)], ys[nxt(j)], th[j], th[nxt(j)])
        else fitWeightedPiece(xs[j], ys[j], xs[nxt(j)], ys[nxt(j)], th[j], th[nxt(j)], weight)

    /** Residual at knot [i], from the pieces either side of it (and its heading, at a fixed end). */
    fun residualAt(i: Int, pieces: Array<ClothoidPiece?>, th: DoubleArray): Double {
        if (!closed && i == 0) {
            if (fixedStart != null) return th[0] - fixedStart
            return pieces[0]!!.bendSpread
        }
        if (!closed && i == n - 1) {
            if (fixedEnd != null) return th[n - 1] - fixedEnd
            return pieces[segs - 1]!!.bendSpread
        }
        val a = pieces[(i - 1 + segs) % segs]!!
        val b = pieces[i % segs]!!
        return (a.endKappa - b.kappa) * 0.5 * (a.length + b.length)
    }
    fun residual(pieces: Array<ClothoidPiece?>, th: DoubleArray): DoubleArray? {
        if (pieces.any { it == null }) return null
        return DoubleArray(n) { residualAt(it, pieces, th) }
    }
    fun norm(r: DoubleArray) = sqrt(r.sumOf { it * it })

    var pieces = Array(segs) { fitSeg(it, theta) }
    var r = residual(pieces, theta) ?: return null
    // Jacobian bands: sub[i] = ∂r_i/∂θ_{i−1}, diag[i] = ∂r_i/∂θ_i, sup[i] = ∂r_i/∂θ_{i+1};
    // for a closed curve sub[0] is ∂r_0/∂θ_{n−1} and sup[n−1] is ∂r_{n−1}/∂θ_0.
    val sub = DoubleArray(n)
    val diag = DoubleArray(n)
    val sup = DoubleArray(n)
    for (iter in 0 until iterations) {
        if (norm(r) < 1e-9) break
        sub.fill(0.0); diag.fill(0.0); sup.fill(0.0)
        val h = 1e-6
        for (j in 0 until n) {
            val th = theta.copyOf()
            th[j] += h
            val trial = pieces.copyOf()
            val before = if (closed) (j - 1 + segs) % segs else j - 1
            if (before >= 0) trial[before] = fitSeg(before, th) ?: return null
            if (closed || j < segs) trial[j % segs] = fitSeg(j % segs, th) ?: return null
            // only the knots either side of j and j itself see the change
            for (offset in -1..1) {
                val ii = if (closed) (j + offset + n) % n else j + offset
                if (ii < 0 || ii >= n) continue
                val d = (residualAt(ii, trial, th) - r[ii]) / h
                when (offset) {
                    0 -> diag[ii] = d
                    1 -> sub[ii] = d    // r_{j+1} against θ_j
                    else -> sup[ii] = d // r_{j−1} against θ_j
                }
            }
        }
        val rhs = DoubleArray(n) { -r[it] }
        val step = (if (closed) solveCyclicTridiagonal(sub, diag, sup, rhs) ?: denseCyclicRescue(sub, diag, sup, rhs)
        else solveTridiagonal(sub, diag, sup, rhs)) ?: return null
        var lambda = 1.0
        var accepted = false
        while (lambda > 1e-4) {
            val th = DoubleArray(n) { theta[it] + lambda * step[it] }
            val trial = Array(segs) { fitSeg(it, th) }
            val rt = residual(trial, th)
            if (rt != null && norm(rt) < norm(r)) {
                th.copyInto(theta); pieces = trial; r = rt; accepted = true
                break
            }
            lambda *= 0.5
        }
        if (!accepted) break
    }
    // Tolerance against the shape's size: 1e-6 of a curvature-times-length is invisible.
    if (norm(r) > 1e-6 * n) return null
    return pieces.map { it!! }
}

/**
 * Tridiagonal solve with partial pivoting (LAPACK `dgtsv`): `sub[i]` = A[i][i−1], `diag[i]` = A[i][i],
 * `sup[i]` = A[i][i+1]; `sub[0]` and `sup[n−1]` are ignored. Null when singular.
 */
private fun solveTridiagonal(sub: DoubleArray, diag: DoubleArray, sup: DoubleArray, b: DoubleArray): DoubleArray? {
    val n = b.size
    if (n == 1) return if (abs(diag[0]) < 1e-14) null else doubleArrayOf(b[0] / diag[0])
    val dl = DoubleArray(n - 1) { sub[it + 1] }
    val d = diag.copyOf()
    val du = DoubleArray(n - 1) { sup[it] }
    val du2 = DoubleArray(n)
    val x = b.copyOf()
    for (i in 0 until n - 1) {
        if (abs(d[i]) >= abs(dl[i])) {
            if (abs(d[i]) < 1e-14) return null
            val f = dl[i] / d[i]
            d[i + 1] -= f * du[i]
            x[i + 1] -= f * x[i]
        } else {
            // swap rows i and i+1; row i gains a second superdiagonal entry
            val f = d[i] / dl[i]
            d[i] = dl[i]
            val t = d[i + 1]
            d[i + 1] = du[i] - f * t
            if (i < n - 2) {
                du2[i] = du[i + 1]
                du[i + 1] = -f * du2[i]
            }
            du[i] = t
            val tb = x[i]
            x[i] = x[i + 1]
            x[i + 1] = tb - f * x[i + 1]
        }
    }
    if (abs(d[n - 1]) < 1e-14) return null
    x[n - 1] /= d[n - 1]
    x[n - 2] = (x[n - 2] - du[n - 2] * x[n - 1]) / d[n - 2]
    for (i in n - 3 downTo 0) x[i] = (x[i] - du[i] * x[i + 1] - du2[i] * x[i + 2]) / d[i]
    return x
}

/**
 * Cyclic tridiagonal solve: as [solveTridiagonal], plus the corners A[0][n−1] = `sub[0]` and
 * A[n−1][0] = `sup[n−1]`, removed by Sherman–Morrison. The answer is checked against the full
 * system and refused (null) when it does not satisfy it.
 */
private fun solveCyclicTridiagonal(sub: DoubleArray, diag: DoubleArray, sup: DoubleArray, b: DoubleArray): DoubleArray? {
    val n = b.size
    if (n < 3) return null
    val beta = sub[0]      // A[0][n−1]
    val alpha = sup[n - 1] // A[n−1][0]
    val gamma = if (abs(diag[0]) > 1e-12) -diag[0] else -1.0
    val d = diag.copyOf()
    d[0] -= gamma
    d[n - 1] -= alpha * beta / gamma
    val y = solveTridiagonal(sub, d, sup, b) ?: return null
    val u = DoubleArray(n).also { it[0] = gamma; it[n - 1] = alpha }
    val z = solveTridiagonal(sub, d, sup, u) ?: return null
    val vy = y[0] + beta / gamma * y[n - 1]
    val vz = z[0] + beta / gamma * z[n - 1]
    if (abs(1 + vz) < 1e-14) return null
    val x = DoubleArray(n) { y[it] - z[it] * vy / (1 + vz) }
    // Sherman–Morrison can lose accuracy when the reduced system is near singular; check the answer
    var err = 0.0
    var size = 0.0
    for (i in 0 until n) {
        val left = x[(i - 1 + n) % n]
        val right = x[(i + 1) % n]
        val ax = sub[i] * left + diag[i] * x[i] + sup[i] * right
        err = max(err, abs(ax - b[i]))
        size = max(size, abs(b[i]))
    }
    if (!x.all { it.isFinite() } || err > 1e-8 * max(size, 1e-12)) return null
    return x
}

/**
 * The cyclic system of [solveCyclicTridiagonal] solved densely, for the rare closed curve where
 * Sherman–Morrison loses accuracy. Small systems only: the matrix is n × n.
 */
private fun denseCyclicRescue(sub: DoubleArray, diag: DoubleArray, sup: DoubleArray, b: DoubleArray): DoubleArray? {
    val n = b.size
    if (n > 400) return null
    val a = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        a[i][i] += diag[i]
        a[i][(i - 1 + n) % n] += sub[i]
        a[i][(i + 1) % n] += sup[i]
    }
    return solveDense(a, b)
}

/** Gaussian elimination with partial pivoting; null when singular. */
private fun solveDense(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    val m = Array(n) { a[it].copyOf() }
    val v = b.copyOf()
    for (c in 0 until n) {
        var p = c
        for (rr in c + 1 until n) if (abs(m[rr][c]) > abs(m[p][c])) p = rr
        if (abs(m[p][c]) < 1e-14) return null
        val tmp = m[p]; m[p] = m[c]; m[c] = tmp
        val tv = v[p]; v[p] = v[c]; v[c] = tv
        for (rr in c + 1 until n) {
            val f = m[rr][c] / m[c][c]
            if (f == 0.0) continue
            for (k in c until n) m[rr][k] -= f * m[c][k]
            v[rr] -= f * v[c]
        }
    }
    val x = DoubleArray(n)
    for (rr in n - 1 downTo 0) {
        var s = v[rr]
        for (k in rr + 1 until n) s -= m[rr][k] * x[k]
        x[rr] = s / m[rr][rr]
    }
    return x
}

/**
 * The plane [clothoidSplineFrom] solves in: in-plane axes [u] and [v], height axis [n], through
 * [origin]. The default XY plane maps (x, y, height) straight to (x, y, z), with no rounding.
 */
private class ClothoidFrame(val origin: Vec3, val u: Vec3, val v: Vec3, val n: Vec3, val isXY: Boolean) {
    fun x(p: Vec3) = if (isXY) p.x.toDouble() else Vec3(p).sub(origin).dot(u).toDouble()
    fun y(p: Vec3) = if (isXY) p.y.toDouble() else Vec3(p).sub(origin).dot(v).toDouble()
    fun h(p: Vec3) = if (isXY) p.z.toDouble() else Vec3(p).sub(origin).dot(n).toDouble()
    fun place(x: Double, y: Double, h: Double): Vec3 =
        if (isXY) vec(x.toFloat(), y.toFloat(), h.toFloat())
        else Vec3(origin).add(Vec3(u).mul(x.toFloat())).add(Vec3(v).mul(y.toFloat())).add(Vec3(n).mul(h.toFloat()))

    companion object {
        val XY = ClothoidFrame(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f), true)

        /** The frame whose height axis is [normal], or the knots' best-fit plane when [normal] is null. */
        fun of(normal: Vec3?, pts: List<Vec3>): ClothoidFrame {
            val nn = (normal ?: bestFitNormal(pts) ?: return XY).let { Vec3(it) }
            if (nn.length() < 1e-9f) return XY
            nn.normalize()
            if (abs(nn.x) < 1e-7f && abs(nn.y) < 1e-7f && nn.z > 0) return XY
            // u: world X flattened into the plane, or world Y when the plane faces X
            val seed = if (abs(nn.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
            val u = Vec3(seed).sub(Vec3(nn).mul(seed.dot(nn))).normalize()
            val v = Vec3(nn).cross(u).normalize()
            val c = Vec3(0f, 0f, 0f)
            for (p in pts) c.add(p)
            c.mul(1f / pts.size)
            return ClothoidFrame(c, u, v, nn, false)
        }

        /**
         * Normal of the plane through [pts] that leaves the least squared distance (smallest
         * eigenvector of their covariance, by Jacobi rotations), oriented toward +Z (then +Y, +X).
         * Null when the points are on a line or a point, which do not fix a plane.
         */
        private fun bestFitNormal(pts: List<Vec3>): Vec3? {
            if (pts.size < 3) return null
            val cx = pts.sumOf { it.x.toDouble() } / pts.size
            val cy = pts.sumOf { it.y.toDouble() } / pts.size
            val cz = pts.sumOf { it.z.toDouble() } / pts.size
            val a = Array(3) { DoubleArray(3) }
            for (p in pts) {
                val d = doubleArrayOf(p.x - cx, p.y - cy, p.z - cz)
                for (i in 0..2) for (j in 0..2) a[i][j] += d[i] * d[j]
            }
            val e = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
            repeat(50) {
                var p = 0; var q = 1
                for ((i, j) in listOf(0 to 2, 1 to 2)) if (abs(a[i][j]) > abs(a[p][q])) { p = i; q = j }
                if (abs(a[p][q]) < 1e-15 * (abs(a[0][0]) + abs(a[1][1]) + abs(a[2][2]) + 1e-300)) return@repeat
                val phi = 0.5 * atan2(2 * a[p][q], a[q][q] - a[p][p])
                val c = cos(phi); val s = sin(phi)
                for (k in 0..2) {
                    val akp = a[k][p]; val akq = a[k][q]
                    a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq
                }
                for (k in 0..2) {
                    val apk = a[p][k]; val aqk = a[q][k]
                    a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk
                }
                for (k in 0..2) {
                    val ekp = e[k][p]; val ekq = e[k][q]
                    e[k][p] = c * ekp - s * ekq; e[k][q] = s * ekp + c * ekq
                }
            }
            val ev = (0..2).sortedBy { a[it][it] }
            // a line (second eigenvalue ≈ 0) has no plane of its own
            if (a[ev[1]][ev[1]] < 1e-12 * max(a[ev[2]][ev[2]], 1e-300)) return null
            val k = ev[0]
            var nx = e[0][k]; var ny = e[1][k]; var nz = e[2][k]
            val flip = if (abs(nz) > 1e-9) nz < 0 else if (abs(ny) > 1e-9) ny < 0 else nx < 0
            if (flip) { nx = -nx; ny = -ny; nz = -nz }
            return vec(nx, ny, nz)
        }
    }
}

/**
 * The height of [clothoidSplineFrom] along its plane's normal, as a cubic spline in arc length
 * through the knots' heights: twice differentiable, so a G2 curve in the plane stays G2 in space.
 * Open ends are natural (no bending), or take a given slope; a closed curve is periodic.
 */
private class HeightSpline(val s: DoubleArray, val y: DoubleArray, val m: DoubleArray) {
    /** Height at arc length [at], which lies in segment [k] (from knot k to knot k + 1). */
    fun at(k: Int, at: Double): Double {
        val h = s[k + 1] - s[k]
        if (h < 1e-12) return y[k]
        val a = s[k + 1] - at
        val b = at - s[k]
        return m[k] * a * a * a / (6 * h) + m[k + 1] * b * b * b / (6 * h) +
            (y[k] / h - m[k] * h / 6) * a + (y[k + 1] / h - m[k + 1] * h / 6) * b
    }

    companion object {
        /**
         * [s] and [y] run over the knots in drawing order; for a closed curve the last entry is the
         * first knot again. [startSlope]/[endSlope] are dh/ds at the ends, null for natural ends.
         */
        fun of(s: DoubleArray, y: DoubleArray, closed: Boolean, startSlope: Double?, endSlope: Double?): HeightSpline? {
            val n = s.size
            val h = DoubleArray(n - 1) { s[it + 1] - s[it] }
            if (h.any { it < 1e-12 }) return null
            fun d(i: Int) = (y[i + 1] - y[i]) / h[i]
            if (closed) {
                // unknowns M_0..M_{m-1} with M_m = M_0
                val mm = n - 1
                if (mm < 3) return null
                val sub = DoubleArray(mm); val dia = DoubleArray(mm); val sup = DoubleArray(mm); val rhs = DoubleArray(mm)
                for (i in 0 until mm) {
                    val hp = h[(i - 1 + mm) % mm]; val hn = h[i]
                    sub[i] = hp; dia[i] = 2 * (hp + hn); sup[i] = hn
                    rhs[i] = 6 * (d(i) - d((i - 1 + mm) % mm))
                }
                val m = solveCyclicTridiagonal(sub, dia, sup, rhs) ?: return null
                return HeightSpline(s, y, DoubleArray(n) { m[it % mm] })
            }
            val sub = DoubleArray(n); val dia = DoubleArray(n); val sup = DoubleArray(n); val rhs = DoubleArray(n)
            for (i in 1 until n - 1) {
                sub[i] = h[i - 1]; dia[i] = 2 * (h[i - 1] + h[i]); sup[i] = h[i]
                rhs[i] = 6 * (d(i) - d(i - 1))
            }
            if (startSlope == null) dia[0] = 1.0 else {
                dia[0] = 2 * h[0]; sup[0] = h[0]; rhs[0] = 6 * (d(0) - startSlope)
            }
            if (endSlope == null) dia[n - 1] = 1.0 else {
                sub[n - 1] = h[n - 2]; dia[n - 1] = 2 * h[n - 2]; rhs[n - 1] = 6 * (endSlope - d(n - 2))
            }
            val m = solveTridiagonal(sub, dia, sup, rhs) ?: return null
            return HeightSpline(s, y, m)
        }
    }
}

/**
 * One stretch of [clothoidSplineFrom] in drawing order: the knots it runs through ([knots], indices
 * into the deduplicated points) and how it is drawn: fitted [pieces], a straight line, or (both
 * null and more than two knots) the cubic fallback.
 */
private class ClothoidRun(val knots: List<Int>, val pieces: List<ClothoidPiece>?) {
    val straight get() = pieces == null && knots.size == 2
}

/**
 * Curvature-continuous (G2) interpolating spline through [points], built from Euler-spiral
 * (clothoid) segments.
 *
 * Each segment is the clothoid fixed by its two end tangent angles; a global Newton solve turns
 * the angle at every knot until the curvature arriving equals the curvature leaving. Curvature
 * therefore changes linearly with arc length inside each segment and has no jumps at the knots,
 * so a round shape reads as round: points on a circle give that circle exactly. Open curves end
 * in circular arcs (the first and last segments have constant curvature) unless [startDirection]
 * or [endDirection] fixes the heading there instead.
 *
 * Knots listed in [corners] break the curve: the direction may change there, and each stretch
 * between corners is solved as its own open spline whose ends are circular arcs. Two corners next
 * to each other are joined by a straight line, so a glyph outline such as a "D" is one call. A
 * closed shape with corners starts at its first corner.
 *
 * In space the curve is solved in a plane, by default the XY plane, and each knot's height above
 * that plane (its Z, by default) is carried along by a cubic spline in arc length, so the curve
 * still passes through every knot and stays curvature-continuous in 3D: knots on a helix give a
 * helix. [planeNormal] picks another plane; null fits the plane to the knots, which suits a
 * nearly flat curve drawn in space. A 3D [startDirection] or [endDirection] sets the climb at that
 * end as well as the heading.
 *
 * This is the weight-1 member of Aurimas Gasiulis's S9 family (https://gasiulis.name/new-spline);
 * it is also Levien's Euler-spiral spline ("From Spiral to Spline", 2009). The solve is linear in
 * the number of knots, so hundreds of knots are fine. If it fails (for example a path that doubles
 * back between neighbouring knots) that stretch falls back to [cubicSplineFrom].
 *
 * @param points the knots, in order
 * @param closed join the last knot back to the first, with G2 continuity there too
 * @param samplesPerSegment polyline points drawn per segment
 * @param corners indices into [points] where the curve may turn a corner instead of staying smooth
 * @param startDirection direction at the first knot of an open curve; null leaves it free, ending
 *   the curve in a circular arc
 * @param endDirection direction at the last knot of an open curve; null leaves it free
 * [weight] picks the member of the S9 family. At 1 each segment is an Euler spiral. Below 1 the
 * curvature inside a segment follows `a + b·g(u)` with `g(u) = ½·sign(u)·|2u|^(1/weight)` (u runs
 * from −½ to ½ along the segment): nearly constant through the middle, changing quickly near the
 * knots, so a segment is close to a circular arc that blends into its neighbours at the ends. The
 * shape `g` rises monotonically, so every segment still has at most one inflection and its
 * curvature extremes sit at its knots; arcs of one circle still come out as that circle.
 *
 * @param planeNormal normal of the plane the curve is solved in (default +Z, the XY plane); null
 *   fits a plane to the knots
 * @param weight S9 weight in `[0.1, 1]`: 1 is the Euler-spiral spline, smaller values keep each
 *   segment's curvature flatter in the middle and move its change toward the knots
 */
fun clothoidSplineFrom(
    points: List<Vec3>,
    closed: Boolean = false,
    samplesPerSegment: Int = 48,
    corners: Collection<Int> = emptyList(),
    startDirection: Vec3? = null,
    endDirection: Vec3? = null,
    planeNormal: Vec3? = Vec3(0f, 0f, 1f),
    weight: Double = 1.0,
): FLine {
    require(weight in 0.1..1.0) { "clothoidSplineFrom: weight must be in 0.1..1.0, was $weight" }
    require(!closed || (startDirection == null && endDirection == null)) {
        "clothoidSplineFrom: startDirection and endDirection apply to open curves; a closed curve has no ends"
    }
    // Repeated points are dropped; `keep` remembers where each kept point came from, so corner
    // indices still name the knots the caller meant.
    val keep = ArrayList<Int>()
    for (i in points.indices) if (keep.isEmpty() || Vec3(points[i]).sub(points[keep.last()]).length() > 1e-6f) keep.add(i)
    val droppedClosing = closed && keep.size > 1 && Vec3(points[keep.first()]).sub(points[keep.last()]).length() < 1e-6f
    if (droppedClosing) keep.removeAt(keep.lastIndex)
    val pts = keep.map { points[it] }
    fun keptPosition(c: Int): Int? {
        if (c !in points.indices) return null
        if (droppedClosing && c >= points.size - 1) return 0
        val at = keep.binarySearch(c)
        return if (at >= 0) at else -at - 2
    }

    val line = FLine()
    if (pts.isEmpty()) return line
    val n = pts.size
    val frame = ClothoidFrame.of(planeNormal, pts)
    val xs = DoubleArray(n) { frame.x(pts[it]) }
    val ys = DoubleArray(n) { frame.y(pts[it]) }
    val hs = DoubleArray(n) { frame.h(pts[it]) }
    // A direction's in-plane part is the heading; its height part over its in-plane length the slope.
    fun heading(d: Vec3?): Double? {
        d ?: return null
        val a = d.dot(frame.u).toDouble(); val b = d.dot(frame.v).toDouble()
        return if (hypot(a, b) > 1e-12) atan2(b, a) else null
    }
    fun slope(d: Vec3?): Double? {
        d ?: return null
        val planar = hypot(d.dot(frame.u).toDouble(), d.dot(frame.v).toDouble())
        return if (planar > 1e-12) d.dot(frame.n) / planar else null
    }
    val startTheta = heading(startDirection)
    val endTheta = heading(endDirection)

    if (n == 1) { line.moveTo(pts[0]); return line }
    if (n == 2 && (closed || (startTheta == null && endTheta == null))) {
        line.moveTo(pts[0]); line.lineTo(pts[1]); return line
    }
    val steps = max(samplesPerSegment, 2)
    val cornerAt = corners.mapNotNull(::keptPosition).filter { closed || it in 1 until n - 1 }.toSortedSet()

    // Solve each stretch. A closed shape with corners is walked from its first corner round to the
    // same corner again; `order` holds knot indices, closed shapes repeating the first at the end.
    val runs = ArrayList<ClothoidRun>()
    val order: List<Int>
    if (closed && cornerAt.isEmpty()) {
        order = (0..n).map { it % n }
        val pieces = solveClothoidSpline(xs, ys, true, 40, weight = weight) ?: return cubicSplineFrom(pts, true)
        runs.add(ClothoidRun(order, pieces))
    } else {
        val first = if (closed) cornerAt.first() else 0
        order = if (closed) (0..n).map { (first + it) % n } else (0 until n).toList()
        val breaks = (listOf(0) + cornerAt.map { if (closed) (it - first + n) % n else it } + listOf(order.lastIndex)).distinct().sorted()
        for ((k, a) in breaks.dropLast(1).withIndex()) {
            val b = breaks[k + 1]
            val knots = order.subList(a, b + 1)
            val st = if (!closed && a == 0) startTheta else null
            val et = if (!closed && b == order.lastIndex) endTheta else null
            val rx = DoubleArray(knots.size) { xs[knots[it]] }
            val ry = DoubleArray(knots.size) { ys[knots[it]] }
            val pieces = if (knots.size == 2 && st == null && et == null) null else solveClothoidSpline(rx, ry, false, 40, st, et, weight)
            runs.add(ClothoidRun(knots, pieces))
        }
    }

    // Arc length at each knot of `order` (the cubic fallback's knots by chord), then the height spline.
    val segLength = ArrayList<Double>()
    for (run in runs) {
        if (run.pieces != null) run.pieces.forEach { segLength.add(it.length) }
        else for (i in 0 until run.knots.size - 1) {
            val a = run.knots[i]; val b = run.knots[i + 1]
            segLength.add(hypot(xs[b] - xs[a], ys[b] - ys[a]))
        }
    }
    val sAt = DoubleArray(order.size)
    for (i in 1 until order.size) sAt[i] = sAt[i - 1] + segLength[i - 1]
    val flat = hs.all { it == hs[0] }
    val height = if (flat) null else HeightSpline.of(
        sAt, DoubleArray(order.size) { hs[order[it]] }, closed,
        if (closed) null else slope(startDirection), if (closed) null else slope(endDirection),
    )
    // A G2 curve in the XY plane keeps its plan-view curvature on its nodes for the comb and plot.
    val record = frame.isXY
    fun place(x: Double, y: Double, seg: Int, s: Double): Vec3 =
        frame.place(x, y, if (height != null) height.at(seg, sAt[seg] + s) else if (flat) hs[0] else hs[order[seg]])

    line.moveTo(pts[order[0]])
    var seg = 0
    for ((k, run) in runs.withIndex()) {
        if (record) {
            if (k == 0) line.nodes.last().properties[CURVATURE_PROPERTY] = run.pieces?.first()?.kappa ?: 0.0
            else line.nodes.last().properties[CORNER_PROPERTY] = true
        }
        when {
            run.pieces != null -> for ((j, piece) in run.pieces.withIndex()) {
                val a = run.knots[j]; val b = run.knots[j + 1]
                val ds = piece.length / steps
                var x = xs[a]
                var y = ys[a]
                for (i in 1..steps) {
                    // Simpson over one sample's arc of θ(s) = θ0 + κs + ½κ's², 8 panels
                    val s0 = (i - 1) * ds
                    var cx = 0.0
                    var cy = 0.0
                    val panels = 8
                    val hh = ds / panels
                    for (q in 0..panels) {
                        val w = if (q == 0 || q == panels) 1.0 else if (q % 2 == 1) 4.0 else 2.0
                        val s = s0 + q * hh
                        val th = piece.heading(s)
                        cx += w * cos(th); cy += w * sin(th)
                    }
                    x += cx * hh / 3.0
                    y += cy * hh / 3.0
                    // the last sample lands on the knot exactly
                    line.lineTo(if (i == steps) place(xs[b], ys[b], seg, piece.length) else place(x, y, seg, i * ds))
                    if (record) line.nodes.last().properties[CURVATURE_PROPERTY] = piece.curvature(i * ds)
                }
                seg++
            }
            run.straight -> {
                val a = run.knots[0]; val b = run.knots[1]
                // flat: one straight segment; with heights, sampled so the climb follows the spline
                val count = if (height == null) 1 else steps
                for (i in 1..count) {
                    val t = i.toDouble() / count
                    line.lineTo(place(xs[a] + (xs[b] - xs[a]) * t, ys[a] + (ys[b] - ys[a]) * t, seg, t * segLength[seg]))
                    if (record) line.nodes.last().properties[CURVATURE_PROPERTY] = 0.0
                }
                seg++
            }
            else -> {
                for (node in catmullRomToBezier(run.knots.map { pts[it] }, false).nodes.drop(1)) line.copyTo(node)
                seg += run.knots.size - 1
            }
        }
    }
    if (closed) line.close()
    return line
}

/**
 * 2D overload of [clothoidSplineFrom], with an optional constant Z value.
 */
fun clothoidSplineFrom(
    points: List<Vec2>,
    z: Number = 0,
    closed: Boolean = false,
    samplesPerSegment: Int = 48,
    corners: Collection<Int> = emptyList(),
    startDirection: Vec2? = null,
    endDirection: Vec2? = null,
    weight: Double = 1.0,
): FLine = clothoidSplineFrom(
    points.map { vec(it.x, it.y, z) }, closed, samplesPerSegment, corners,
    startDirection?.let { vec(it.x, it.y, 0) }, endDirection?.let { vec(it.x, it.y, 0) },
    weight = weight,
)

/** Node property where [clothoidSplineFrom] records the exact signed curvature at each drawn point. */
private const val CURVATURE_PROPERTY = "curvature"

/** Node property marking a corner of [clothoidSplineFrom]: the direction (and curvature) jump there. */
private const val CORNER_PROPERTY = "clothoidCorner"
/**
 * The factor relating the curvature recorded on [drawn] to the curvature of the line as it now
 * stands, or null when the line no longer has the shape it was drawn with.
 *
 * Scaling a line by `s` after [clothoidSplineFrom] built it divides every curvature by `s`, and a
 * mirror flips the sign, while the recorded values stay as built. The turn at each node, measured
 * from its neighbours, is compared with recorded curvature × local spacing; a least-squares fit
 * gives the factor, and a poor fit (a non-uniform scale, a bend, an edit) refuses.
 */
private fun recordedCurvatureFactor(drawn: List<FLine.Node>, all: List<Vec3>, ks: List<Double>): Double? {
    var num = 0.0
    var den = 0.0
    var turns = 0.0
    val pairs = ArrayList<Pair<Double, Double>>()
    for (i in 1 until all.lastIndex) {
        if (drawn[i].properties[CORNER_PROPERTY] == true) continue
        val ux = (all[i].x - all[i - 1].x).toDouble(); val uy = (all[i].y - all[i - 1].y).toDouble()
        val wx = (all[i + 1].x - all[i].x).toDouble(); val wy = (all[i + 1].y - all[i].y).toDouble()
        val lu = hypot(ux, uy); val lw = hypot(wx, wy)
        if (lu < 1e-9 || lw < 1e-9) continue
        val turn = atan2(ux * wy - uy * wx, ux * wx + uy * wy)
        val predicted = ks[i] * 0.5 * (lu + lw)
        pairs.add(turn to predicted)
        num += turn * predicted; den += predicted * predicted; turns += turn * turn
    }
    if (den < 1e-18) return if (turns < 1e-12) 1.0 else null
    val c = num / den
    val misfit = pairs.sumOf { (t, p) -> (t - c * p) * (t - c * p) }
    if (misfit > 1e-3 * turns + 1e-12) return null
    return if (abs(c - 1) < 1e-3) 1.0 else c
}

/**
 * Points along [line], their unit normals (tangent turned left), arc length, and signed curvature.
 *
 * A line from [clothoidSplineFrom] carries exact curvature on its nodes and is read from them,
 * corrected for any uniform scale or mirror applied since ([recordedCurvatureFactor]).
 * Any other line is sampled evenly by arc length, and curvature is the circle through points a
 * fixed distance either side, so the answer does not depend on how the line was flattened.
 */
private class CurvatureSamples(val points: List<Vec3>, val nx: DoubleArray, val ny: DoubleArray, val along: DoubleArray, val kappa: DoubleArray)

private fun signedCurvatureSamples(line: FLine, samples: Int): CurvatureSamples {
    val drawn = line.nodes.filter { it !is FLine.Close }
    if (drawn.size >= 3 && drawn.all { it.properties[CURVATURE_PROPERTY] is Double }) {
        val all = drawn.map { Vec3(it.to) }
        val recorded = drawn.map { it.properties[CURVATURE_PROPERTY] as Double }
        val factor = recordedCurvatureFactor(drawn, all, recorded)
        if (factor != null) {
            val ks = recorded.map { it * factor }
            val along = DoubleArray(all.size)
            for (i in 1 until all.size) along[i] = along[i - 1] + all[i].distance(all[i - 1])
            val pick = (0 until max(samples, 3)).map { (it.toDouble() * all.lastIndex / (max(samples, 3) - 1)).roundToInt() }.distinct()
            val nx = DoubleArray(pick.size)
            val ny = DoubleArray(pick.size)
            for ((j, i) in pick.withIndex()) {
                val o = all[max(i - 1, 0)]; val q = all[min(i + 1, all.lastIndex)]
                val tx = (q.x - o.x).toDouble(); val ty = (q.y - o.y).toDouble()
                val tl = max(hypot(tx, ty), 1e-12)
                nx[j] = -ty / tl; ny[j] = tx / tl
            }
            return CurvatureSamples(pick.map { all[it] }, nx, ny, DoubleArray(pick.size) { along[pick[it]] }, DoubleArray(pick.size) { ks[pick[it]] })
        }
    }
    val n = max(samples, 3)
    val c = line.cursor()
    val total = c.lengthD().toDouble()
    val h = total / (n - 1) * 1.5
    val pts = ArrayList<Vec3>(n)
    val nx = DoubleArray(n); val ny = DoubleArray(n); val along = DoubleArray(n); val k = DoubleArray(n)
    fun at(d: Double): Vec3 { c.setD(d.coerceIn(0.0, total).toFloat()); return c.position() }
    for (i in 0 until n) {
        val d = total * i / (n - 1)
        val b = at(d)
        val a = at(d - h); val e = at(d + h)
        pts.add(b); along[i] = d
        val tx = (e.x - a.x).toDouble(); val ty = (e.y - a.y).toDouble()
        val tl = max(hypot(tx, ty), 1e-12)
        nx[i] = -ty / tl; ny[i] = tx / tl
        val abx = (b.x - a.x).toDouble(); val aby = (b.y - a.y).toDouble()
        val bcx = (e.x - b.x).toDouble(); val bcy = (e.y - b.y).toDouble()
        val cross = abx * bcy - aby * bcx
        val denom = hypot(abx, aby) * hypot(bcx, bcy) * hypot(tx, ty)
        k[i] = if (denom > 1e-18) 2 * cross / denom else 0.0
    }
    // the ends see a one-sided neighbourhood; copy their inside neighbour
    k[0] = k[1]; k[n - 1] = k[n - 2]
    return CurvatureSamples(pts, nx, ny, along, k)
}

/**
 * A curvature comb for this line: a tooth along the normal at each sample, of length
 * curvature × [scale], plus the line joining the tooth tips.
 *
 * Teeth point toward the centre of curvature, so they flip sides at an inflection; a
 * smooth envelope means smoothly varying curvature, a step in it is a curvature jump.
 * A line made by [clothoidSplineFrom] carries its exact curvature and is read from that, still
 * exactly after a uniform scale, rotation, move or mirror. Any other line (a cubic spline, a
 * hand-drawn stroke, a spline stretched unevenly after it was built) is measured from its shape.
 *
 * @param scale tooth length per unit curvature (world units squared)
 * @param samples teeth along the whole line
 */
fun FLine.curvatureComb(scale: Double = 2000.0, samples: Int = 120): FLine {
    val cs = signedCurvatureSamples(this, samples)
    val comb = FLine()
    val tips = cs.points.indices.map { i ->
        val p = cs.points[i]
        val len = cs.kappa[i] * scale
        vec((p.x + cs.nx[i] * len).toFloat(), (p.y + cs.ny[i] * len).toFloat(), p.z)
    }
    for (i in tips.indices) {
        comb.moveTo(cs.points[i]); comb.lineTo(tips[i])
    }
    if (tips.size > 1) {
        comb.moveTo(tips[0])
        for (t in tips.drop(1)) comb.lineTo(t)
    }
    return comb
}

/**
 * Curvature of this line plotted against arc length, as a new line in a [width] × [height] box
 * whose top-left is the origin (y grows downward, zero curvature on the middle row).
 *
 * The vertical scale is set by the largest |curvature| unless [maxCurvature] is given; pass the
 * same value to plots you want to compare.
 *
 * @param samples points along the line
 */
fun FLine.curvaturePlot(width: Double = 400.0, height: Double = 120.0, samples: Int = 300, maxCurvature: Double? = null): FLine {
    val cs = signedCurvatureSamples(this, samples)
    val k = cs.kappa
    val total = max(cs.along.last(), 1e-12)
    val top = maxCurvature ?: max(k.maxOf { abs(it) }, 1e-9)
    val plot = FLine()
    for (i in k.indices) {
        val x = width * cs.along[i] / total
        val y = height / 2 - (k[i] / top).coerceIn(-1.0, 1.0) * height / 2
        if (i == 0) plot.moveTo(vec(x, y, 0.0)) else plot.lineTo(vec(x, y, 0.0))
    }
    return plot
}
