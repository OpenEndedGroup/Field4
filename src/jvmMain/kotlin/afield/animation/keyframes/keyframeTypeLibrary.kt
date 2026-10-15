package afield.animation.keyframes

import afield.animation.interpolation.interpolant
import afield.app.diagnostics.named
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.output.column
import afield.ui.visualization.menu
import afield.ui.visualization.table

import afield.animation.interpolation.KeySegment
import afield.audio.synth.*
import afield.data.table.cell
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.ui.visualization.h

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The key types every keyframe editor offers beyond its five built-in ones: the easing family,
 * splines that look past the segment to the keys either side, shapes, and shaped noise. Each is a
 * [KeySegment] lambda like the ones a box defines with interpolant(...), evaluated in Kotlin and
 * drawn by the editor from samples.
 */

/** One entry in the library: the name a key stores, where it sits in the menu, and its shape. */
class KeyTypeEntry internal constructor(
    val name: String,
    val label: String,
    val group: String,
    /** For the easing table: the family (a row) and the direction (a column). */
    val family: String? = null,
    val variant: String? = null,
    val shape: KeySegment.() -> Double,
)

object KeyTypeLibrary {

    val entries: List<KeyTypeEntry> by lazy { build() }

    private val byName: Map<String, KeyTypeEntry> by lazy { entries.associateBy { it.name } }

    fun named(name: String): KeyTypeEntry? = byName[name]

    private fun build(): List<KeyTypeEntry> = buildList {
        // ── Ease: Penner's family, as a fraction of the way from v0 to v1 ──────────────────
        fun ease(family: String, fIn: (Double) -> Double, fOut: (Double) -> Double, fInOut: (Double) -> Double) {
            val key = family.lowercase()
            add(KeyTypeEntry("ease.$key.in", "$key in", "Ease", family, "in") { lerp(fIn(u)) })
            add(KeyTypeEntry("ease.$key.out", "$key out", "Ease", family, "out") { lerp(fOut(u)) })
            add(KeyTypeEntry("ease.$key.inout", "$key in-out", "Ease", family, "in-out") { lerp(fInOut(u)) })
        }
        fun power(n: Double) = Triple<(Double) -> Double, (Double) -> Double, (Double) -> Double>(
            { x -> x.pow(n) },
            { x -> 1 - (1 - x).pow(n) },
            { x -> if (x < 0.5) 2.0.pow(n - 1) * x.pow(n) else 1 - (-2 * x + 2).pow(n) / 2 },
        )
        ease("Sine", { 1 - cos(it * PI / 2) }, { sin(it * PI / 2) }, { -(cos(PI * it) - 1) / 2 })
        listOf("Quad" to 2.0, "Cubic" to 3.0, "Quart" to 4.0, "Quint" to 5.0).forEach { (name, n) ->
            val (i, o, io) = power(n)
            ease(name, i, o, io)
        }
        ease(
            "Expo",
            { if (it <= 0) 0.0 else 2.0.pow(10 * it - 10) },
            { if (it >= 1) 1.0 else 1 - 2.0.pow(-10 * it) },
            { x -> when { x <= 0 -> 0.0; x >= 1 -> 1.0; x < 0.5 -> 2.0.pow(20 * x - 10) / 2; else -> (2 - 2.0.pow(-20 * x + 10)) / 2 } },
        )
        ease(
            "Circ",
            { 1 - sqrt(max(0.0, 1 - it * it)) },
            { sqrt(max(0.0, 1 - (it - 1) * (it - 1))) },
            { x -> if (x < 0.5) (1 - sqrt(max(0.0, 1 - (2 * x) * (2 * x)))) / 2 else (sqrt(max(0.0, 1 - (-2 * x + 2) * (-2 * x + 2))) + 1) / 2 },
        )
        val c1 = 1.70158
        val c2 = c1 * 1.525
        val c3 = c1 + 1
        ease(
            "Back",
            { c3 * it * it * it - c1 * it * it },
            { 1 + c3 * (it - 1).pow(3) + c1 * (it - 1).pow(2) },
            { x -> if (x < 0.5) ((2 * x).pow(2) * ((c2 + 1) * 2 * x - c2)) / 2 else ((2 * x - 2).pow(2) * ((c2 + 1) * (2 * x - 2) + c2) + 2) / 2 },
        )
        val c4 = 2 * PI / 3
        val c5 = 2 * PI / 4.5
        ease(
            "Elastic",
            { x -> when { x <= 0 -> 0.0; x >= 1 -> 1.0; else -> -(2.0.pow(10 * x - 10)) * sin((10 * x - 10.75) * c4) } },
            { x -> when { x <= 0 -> 0.0; x >= 1 -> 1.0; else -> 2.0.pow(-10 * x) * sin((10 * x - 0.75) * c4) + 1 } },
            { x ->
                when {
                    x <= 0 -> 0.0
                    x >= 1 -> 1.0
                    x < 0.5 -> -(2.0.pow(20 * x - 10) * sin((20 * x - 11.125) * c5)) / 2
                    else -> (2.0.pow(-20 * x + 10) * sin((20 * x - 11.125) * c5)) / 2 + 1
                }
            },
        )
        ease("Bounce", { 1 - bounceOut(1 - it) }, ::bounceOut, { x -> if (x < 0.5) (1 - bounceOut(1 - 2 * x)) / 2 else (1 + bounceOut(2 * x - 1)) / 2 })

        // ── Spline: tangents from the keys either side, a cubic Hermite between ─────────────
        add(KeyTypeEntry("spline.catmull", "Catmull-Rom", "Spline") { tcb(0.0, 0.0, 0.0) })
        add(KeyTypeEntry("spline.tcb.tense", "TCB tense", "Spline") { tcb(0.5, 0.0, 0.0) })
        add(KeyTypeEntry("spline.tcb.loose", "TCB loose", "Spline") { tcb(-0.5, 0.0, 0.0) })
        add(KeyTypeEntry("spline.tcb.overshoot", "TCB overshoot", "Spline") { tcb(0.0, 0.6, 0.0) })
        add(KeyTypeEntry("spline.tcb.corner", "TCB corner", "Spline") { tcb(0.0, 0.0, -0.6) })
        add(KeyTypeEntry("spline.monotone", "monotone", "Spline") { hermite(fritschCarlson(true), fritschCarlson(false)) })
        add(KeyTypeEntry("spline.steffen", "Steffen", "Spline") { hermite(steffen(true), steffen(false)) })

        // ── Shape ─────────────────────────────────────────────────────────────────────────
        add(KeyTypeEntry("shape.smoothstep", "smoothstep", "Shape") { lerp(smoothstep(u)) })
        add(KeyTypeEntry("shape.smootherstep", "smootherstep", "Shape") { lerp(u * u * u * (u * (u * 6 - 15) + 10)) })
        add(KeyTypeEntry("shape.cosine", "cosine", "Shape") { lerp((1 - cos(PI * u)) / 2) })
        add(KeyTypeEntry("shape.spring", "spring", "Shape") {
            // A damped spring released towards v1, nudged so it lands there exactly.
            val f = { x: Double -> 1 - exp(-5 * x) * cos(3.5 * PI * x) }
            lerp(f(u) + u * (1 - f(1.0)))
        })
        add(KeyTypeEntry("shape.sigmoid", "sigmoid", "Shape") {
            // A steep logistic S, rescaled so it starts and ends exactly on the keys.
            val f = { x: Double -> 1 / (1 + exp(-12 * (x - 0.5))) }
            lerp((f(u) - f(0.0)) / (f(1.0) - f(0.0)))
        })
        add(KeyTypeEntry("shape.approach", "approach", "Shape") { lerp((1 - exp(-6 * u)) / (1 - exp(-6.0))) })
        add(KeyTypeEntry("shape.slowmiddle", "slow middle", "Shape") { lerp(u - 0.14 * sin(2 * PI * u)) })
        add(KeyTypeEntry("shape.steps4", "4 steps", "Shape") { lerp(min(1.0, floor(u * 4) / 4)) })
        add(KeyTypeEntry("shape.steps8", "8 steps", "Shape") { lerp(min(1.0, floor(u * 8) / 8)) })

        // ── Noise: gradient noise around the straight line, pinned to both keys ─────────────
        add(KeyTypeEntry("noise.noise", "noise", "Noise") { lerp(u) + noiseAmplitude() * sin(PI * u) * perlin(u * 4, seed()) })
        add(KeyTypeEntry("noise.busy", "busy", "Noise") {
            val n = perlin(u * 8, seed()) * 0.6 + perlin(u * 16, seed() + 1) * 0.3 + perlin(u * 32, seed() + 2) * 0.15
            lerp(u) + noiseAmplitude() * sin(PI * u) * n
        })
        add(KeyTypeEntry("noise.drift", "drift", "Noise") { lerp(u) + 1.6 * noiseAmplitude() * sin(PI * u) * perlin(u * 1.5, seed()) })
        add(KeyTypeEntry("noise.jitter", "jitter", "Noise") {
            val cell = floor(u * 16).toInt()
            lerp(u) + noiseAmplitude() * sin(PI * u) * (hash(cell, seed()) * 2 - 1)
        })
    }

    private fun bounceOut(x0: Double): Double {
        val n1 = 7.5625
        val d1 = 2.75
        var x = x0
        return when {
            x < 1 / d1 -> n1 * x * x
            x < 2 / d1 -> { x -= 1.5 / d1; n1 * x * x + 0.75 }
            x < 2.5 / d1 -> { x -= 2.25 / d1; n1 * x * x + 0.9375 }
            else -> { x -= 2.625 / d1; n1 * x * x + 0.984375 }
        }
    }

    // Slopes, in value per unit of time, of the segment and of the ones either side of it.
    private fun KeySegment.slopeBefore(): Double? = before?.let { b -> if (t0 - b.time > 1e-9) (v0 - b.value) / (t0 - b.time) else null }
    private fun KeySegment.slope(): Double = if (span > 1e-9) (v1 - v0) / span else 0.0
    private fun KeySegment.slopeAfter(): Double? = after?.let { a -> if (a.time - t1 > 1e-9) (a.value - v1) / (a.time - t1) else null }

    private fun KeySegment.hermite(m0: Double, m1: Double): Double {
        val s = u
        val s2 = s * s
        val s3 = s2 * s
        return (2 * s3 - 3 * s2 + 1) * v0 + (s3 - 2 * s2 + s) * span * m0 + (-2 * s3 + 3 * s2) * v1 + (s3 - s2) * span * m1
    }

    /** Kochanek–Bartels: tension, bias, continuity. (0, 0, 0) is Catmull-Rom. */
    private fun KeySegment.tcb(tension: Double, bias: Double, continuity: Double): Double {
        val d = slope()
        val dl = slopeBefore() ?: d
        val dn = slopeAfter() ?: d
        val k = (1 - tension) / 2
        val m0 = k * ((1 + bias) * (1 + continuity) * dl + (1 - bias) * (1 - continuity) * d)
        val m1 = k * ((1 + bias) * (1 - continuity) * d + (1 - bias) * (1 + continuity) * dn)
        return hermite(m0, m1)
    }

    /** Fritsch–Carlson tangents: no overshoot between keys, by a harmonic mean of the slopes. */
    private fun KeySegment.fritschCarlson(atStart: Boolean): Double {
        val d = slope()
        val other = (if (atStart) slopeBefore() else slopeAfter()) ?: return d
        if (other * d <= 0) return 0.0
        return 2 / (1 / other + 1 / d)
    }

    /** Steffen (1990): monotone and local, the smallest of the neighbouring slopes and their blend. */
    private fun KeySegment.steffen(atStart: Boolean): Double {
        val d = slope()
        val (dl, dr, hl, hr) = if (atStart) {
            val b = before ?: return d
            listOf(slopeBefore() ?: return d, d, t0 - b.time, span)
        } else {
            val a = after ?: return d
            listOf(d, slopeAfter() ?: return d, span, a.time - t1)
        }
        val p = (dl * hr + dr * hl) / (hl + hr)
        return (sign(dl) + sign(dr)) * min(min(abs(dl), abs(dr)), 0.5 * abs(p))
    }

    // Noise is measured against the segment's own rise, with a floor, so a flat segment still moves.
    private fun KeySegment.noiseAmplitude(): Double = 0.18 * max(abs(v1 - v0), 0.3)

    private fun KeySegment.seed(): Int = (t0 * 1000.0).toLong().toInt() * 31 + (v0 * 1000.0).toLong().toInt()

    private fun hash(i: Int, seed: Int): Double {
        var h = i * 374761393 + seed * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647.0
    }

    /** One-dimensional gradient noise, about -1 to 1, zero at whole numbers. */
    private fun perlin(x: Double, seed: Int): Double {
        val i = floor(x).toInt()
        val f = x - i
        val g0 = hash(i, seed) * 2 - 1
        val g1 = hash(i + 1, seed) * 2 - 1
        val fade = f * f * f * (f * (f * 6 - 15) + 10)
        val n0 = g0 * f
        val n1 = g1 * (f - 1)
        return 2 * (n0 + fade * (n1 - n0))
    }
}
