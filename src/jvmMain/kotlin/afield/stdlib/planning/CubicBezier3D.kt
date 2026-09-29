@file:afield.CompletionCategory("Math")

package afield.stdlib.planning

import kotlin.math.*

/**
 * An immutable double-precision cubic in caller-chosen units. [start] and [end] are
 * endpoints; [control1] and [control2] are the inner controls. The caller supplies
 * all approximation tolerances and curvature thresholds.
 */
data class CubicBezier3D(val start: Point3D, val control1: Point3D, val control2: Point3D, val end: Point3D) {
    private val controls get() = listOf(start, control1, control2, end)

    /** Position at a finite parameter [t] in the closed unit interval. */
    fun valueAt(t: Double): Point3D {
        require(t.isFinite() && t in 0.0..1.0)
        val u = 1-t
        return start*(u*u*u)+control1*(3*u*u*t)+control2*(3*u*t*t)+end*(t*t*t)
    }

    /** Unnormalised derivative with respect to the unit parameter; it may be zero. */
    fun tangentAt(t: Double): Point3D {
        require(t.isFinite() && t in 0.0..1.0)
        val u = 1-t
        return (control1-start)*(3*u*u)+(control2-control1)*(6*u*t)+(end-control2)*(3*t*t)
    }

    private fun halves(): Pair<CubicBezier3D, CubicBezier3D> {
        val a=(start+control1)*.5; val b=(control1+control2)*.5; val c=(control2+end)*.5
        val d=(a+b)*.5; val e=(b+c)*.5; val f=(d+e)*.5
        return CubicBezier3D(start,a,d,f) to CubicBezier3D(f,e,c,end)
    }

    /**
     * Conservative lower bound on radius across the continuous cubic, at least [minimum].
     * Subdivides Bernstein control hulls, bounding speed below and |r' × r''| above.
     * Throws if the bound cannot be established within [maximumDepth]; failure is not
     * proof that no valid curve exists. Zero chords/cusps and arithmetic overflow fail
     * closed. This is a numerical bound, not interval-arithmetic certification.
     */
    fun minimumRadiusBound(minimum: Double, maximumDepth: Int = 18): Double {
        require(minimum.isFinite() && minimum > 0 && maximumDepth in 0..24)
        fun bound(curve: CubicBezier3D, depth: Int): Double {
            val chord=curve.end-curve.start
            require(chord.length>0 && chord.length.isFinite()) { "Degenerate curve" }
            val axis=chord.normalized()
            val d=curve.controls.zipWithNext().map { (a,b)->(b-a)*3.0 }
            val e=d.zipWithNext().map { (a,b)->(b-a)*2.0 }
            val cross=listOf(d[0] cross e[0],(d[0] cross e[1])*(1.0/3)+(d[1] cross e[0])*(2.0/3),
                (d[1] cross e[1])*(2.0/3)+(d[2] cross e[0])*(1.0/3),d[2] cross e[1]).maxOf { it.length }
            val speed=d.minOf { it dot axis }
            val radius=if(speed>0) speed.pow(3)/max(cross,1e-20) else 0.0
            require(cross.isFinite() && speed.isFinite() && radius.isFinite()) { "Curve bound overflow" }
            if(radius>=minimum) return radius
            require(depth<maximumDepth) { "Curve does not certify the $minimum minimum radius" }
            val (a,b)=curve.halves()
            return min(bound(a,depth+1),bound(b,depth+1))
        }
        return bound(this,0)
    }

    /**
     * Polyline including both endpoints. Every subcurve's control hull is within
     * [maximumDeviation] of its finite chord. This bounds curve-to-polyline distance;
     * it does not prove containment in another shape. Throws
     * on degenerate chords or failure to converge within [maximumDepth].
     */
    fun flatten(maximumDeviation: Double, maximumDepth: Int = 24): List<Point3D> {
        require(maximumDeviation.isFinite() && maximumDeviation>0 && maximumDepth in 0..24)
        val out=mutableListOf(start)
        fun append(curve: CubicBezier3D, depth: Int) {
            val chord=curve.end-curve.start; val length2=chord dot chord
            require(length2.isFinite() && length2>0) { "Degenerate curve" }
            val deviation=listOf(curve.control1,curve.control2).maxOf { q ->
                q.distanceTo(curve.start+chord*(((q-curve.start) dot chord)/length2).coerceIn(0.0,1.0))
            }
            if(deviation<=maximumDeviation) { out+=curve.end; return }
            require(depth<maximumDepth) { "Curve tessellation did not converge" }
            val (a,b)=curve.halves(); append(a,depth+1); append(b,depth+1)
        }
        append(this,0)
        return out
    }
}
