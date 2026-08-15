@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lowering **(c)**: the same [SolidExpr] as GLSL, so the shape that produces the fabricable mesh can
 * also be ray-marched on a GPU at sixty frames a second.
 *
 * ```kotlin
 * val part = cuboid(2, 1.2, 0.4) - cylinder(2, 0.3).at(0.5, 0, 0)
 * println(part.toGlsl().glsl)          // float sdSolid(vec3 p) { … }
 * println(part.toRaymarchFragment())   // a complete fragment shader that draws it
 * ```
 *
 * This is the third column, and it is the one that only a *symbolic* notation can have. A
 * `(Vec3) -> Float` runs on the JVM and nowhere else; an expression tree can be walked into whatever
 * language you like. RectSL made the same move in 2D on 2026-08-06 (`RectSLGlsl.emitSdf`), and its
 * memory says the port was small because the IR was symbolic. It was small here too.
 *
 * # What is emitted, and the unusual shape of it
 *
 * Straight-line, single-assignment, **entirely scalar** GLSL: no `vec2`, no `vec3` locals, no
 * swizzles, no loops, no branches except the conditional operator.
 *
 * ```glsl
 * float sdSolid(vec3 p) {
 *     float x0 = p.x;
 *     float y0 = p.y;
 *     float z0 = p.z;
 *     float q1 = abs(x0) - 1.0;
 *     …
 *     return d7;
 * }
 * ```
 *
 * That looks like a strange thing to hand a shader compiler, and it is a deliberate trade. Any driver
 * will re-vectorise this in its first pass, so nothing is lost at run time. What is *gained* is that
 * the emitted text falls inside a grammar small enough to write a reader for — which is what
 * `SolidSLGlslTest` does. It parses the shader back and evaluates it at tens of thousands of points
 * against [SolidExpr.toField3], so the claim "the GLSL computes the same field" is **measured**
 * rather than asserted from the fact that both came from the same tree walk. A dropped negation, a
 * swapped `min` and `max`, an argument in the wrong position: all of those compile perfectly and all
 * of them are caught. Compilation alone — which is where the 2D precedent stops — proves only that
 * the text is *valid*.
 *
 * # A raymarcher needs to know when the field lies
 *
 * Sphere tracing steps along the ray by the field's value, which is only safe when that value is a
 * lower bound on the true distance. Several nodes here return something larger than the true distance
 * ([SolidExpr.Intersect] and [SolidExpr.Difference] are pointwise maxima; a twisted [SolidExpr.Extrude]
 * shears space), and stepping by an over-estimate walks a ray straight through a thin wall.
 *
 * So [GlslSolid.stepScale] is part of the result rather than a magic number in the shader: the
 * emitter reads [fieldNotes] and reports the multiplier the march should use. `1.0` means the field
 * is a true distance function and sphere tracing is exact; anything less says how much this
 * particular expression has to be slowed down, and [GlslSolid.notes] says which node caused it.
 */

/** The emitted shader, plus what a marcher needs to know about the field it describes. */
data class GlslSolid(

    /** The function source: `float <functionName>(vec3 p) { … }`. */
    val glsl: String,

    val functionName: String,

    /** How many `float` assignments it took — a cheap proxy for shader cost, and a regression ratchet. */
    val statementCount: Int,

    /**
     * The safe sphere-tracing step, as a fraction of the field's value.
     *
     * `1.0` when every node in the expression returns a true distance. Lower when one does not — see
     * this file's KDoc — with [notes] naming the offenders.
     */
    val stepScale: Double,

    /** [SolidExpr.fieldNotes], carried through, because they are exactly what [stepScale] is derived from. */
    val notes: List<String>,
) {
    override fun toString(): String =
        "GlslSolid($functionName, $statementCount statements, stepScale $stepScale" +
            (if (notes.isEmpty()) ", exact distance field)" else ", ${notes.size} note(s))")
}

/**
 * This shape as a GLSL distance function.
 *
 * @param functionName the emitted function's name.
 */
fun SolidExpr.toGlsl(functionName: String = "sdSolid"): GlslSolid {
    val w = GlslWriter()
    w.line("float $functionName(vec3 p) {")
    val p0 = GlslPoint(w.assign("x", "p.x"), w.assign("y", "p.y"), w.assign("z", "p.z"))
    val d = emitSolid(this, p0, w)
    w.line("    return $d;")
    w.line("}")
    val notes = fieldNotes()
    return GlslSolid(
        glsl = w.text(),
        functionName = functionName,
        statementCount = w.count,
        // 0.7 is the usual "the field over-estimates somewhere" safety factor, and 0.4 for a shear,
        // which over-estimates by the shear rate rather than by a bounded amount. Both are stated
        // here, once, rather than left as a constant in whoever's shader.
        stepScale = when {
            notes.isEmpty() -> 1.0
            notes.any { it.contains("shears space") || it.contains("not a distance function") } -> 0.4
            else -> 0.7
        },
        notes = notes,
    )
}

/**
 * A complete fragment shader that draws this shape: sphere tracing, a central-difference normal, two
 * lights and a soft floor. Drop it into `afield.four`, or paste it into Shadertoy.
 *
 * The uniforms are deliberately few and conventional — `uResolution`, `uTime`, `uCameraDistance` —
 * so that a preview is a copy and paste rather than a plumbing exercise.
 *
 * @param maxSteps sphere-tracing iterations before the ray gives up.
 */
fun SolidExpr.toRaymarchFragment(maxSteps: Int = 128, functionName: String = "sdSolid"): String {
    val f = toGlsl(functionName)
    val radius = bounds()?.let {
        val s = it.size
        0.5 * kotlin.math.sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
    } ?: 2.0
    val centre = bounds()?.center ?: afield.stdlib.Vec3(0f, 0f, 0f)
    return """
        #version 450

        layout(location = 0) in vec2 vUv;
        layout(location = 0) out vec4 fragColor;

        layout(binding = 0) uniform Params {
            vec2 uResolution;
            float uTime;
            float uCameraDistance;
        };

        ${f.glsl.trimEnd()}

        // The march step is scaled by ${f.stepScale}: ${if (f.notes.isEmpty()) "this expression is a true distance field" else f.notes.first()}
        const float STEP_SCALE = ${f.stepScale};
        const vec3 CENTRE = vec3(${glslFloat(centre.x.toDouble())}, ${glslFloat(centre.y.toDouble())}, ${glslFloat(centre.z.toDouble())});
        const float RADIUS = ${glslFloat(radius)};

        vec3 sdNormal(vec3 p) {
            float h = RADIUS * 1e-4;
            vec2 k = vec2(1.0, -1.0);
            return normalize(
                k.xyy * $functionName(p + k.xyy * h) +
                k.yyx * $functionName(p + k.yyx * h) +
                k.yxy * $functionName(p + k.yxy * h) +
                k.xxx * $functionName(p + k.xxx * h));
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy * 2.0 - uResolution) / uResolution.y;

            float a = uTime * 0.35;
            float dist = RADIUS * max(uCameraDistance, 1.5);
            vec3 eye = CENTRE + vec3(sin(a) * dist, cos(a) * dist, dist * 0.55);
            vec3 forward = normalize(CENTRE - eye);
            vec3 right = normalize(cross(forward, vec3(0.0, 0.0, 1.0)));
            vec3 up = cross(right, forward);
            vec3 ray = normalize(forward * 1.7 + right * uv.x + up * uv.y);

            float t = 0.0;
            float hit = -1.0;
            for (int i = 0; i < $maxSteps; ++i) {
                vec3 q = eye + ray * t;
                float d = $functionName(q);
                if (d < RADIUS * 1e-4) { hit = t; break; }
                t += max(d * STEP_SCALE, RADIUS * 1e-5);
                if (t > dist * 3.0) break;
            }

            vec3 colour = vec3(0.055, 0.063, 0.078);
            if (hit >= 0.0) {
                vec3 q = eye + ray * hit;
                vec3 n = sdNormal(q);
                float key = max(dot(n, normalize(vec3(0.4, -0.7, 0.9))), 0.0);
                float fill = max(dot(n, normalize(vec3(-0.6, 0.3, 0.2))), 0.0);
                colour = vec3(0.62, 0.68, 0.78) * (0.18 + 0.85 * key) + vec3(0.16, 0.20, 0.30) * fill;
            }
            fragColor = vec4(colour, 1.0);
        }
    """.trimIndent()
}

// ==============================================================================================
// The emitter
// ==============================================================================================

/** The current point in the walk, as three scalar variable names. */
private data class GlslPoint(val x: String, val y: String, val z: String)

/**
 * Accumulates the straight-line body.
 *
 * Every value gets a name — no nested expression is ever reused by being written twice — which is
 * what keeps the emitted code linear in the size of the tree and the grammar small enough to parse.
 */
private class GlslWriter {
    private val sb = StringBuilder()
    private var next = 0
    var count = 0
        private set

    fun line(s: String) {
        sb.append(s).append('\n')
    }

    /** `float <prefix><n> = <expr>;`, returning the name. */
    fun assign(prefix: String, expr: String): String {
        val name = "$prefix${next++}"
        sb.append("    float ").append(name).append(" = ").append(expr).append(";\n")
        count++
        return name
    }

    fun comment(s: String) {
        sb.append("    // ").append(s).append('\n')
    }

    fun text(): String = sb.toString()
}

/** A `Double` as a GLSL float literal. Kotlin's own rendering always carries a `.` or an `E`. */
internal fun glslFloat(v: Double): String = when {
    v.isNaN() -> "0.0"
    v == Double.POSITIVE_INFINITY -> "1.0E18"
    v == Double.NEGATIVE_INFINITY -> "-1.0E18"
    else -> v.toString()
}

private const val GLSL_FAR = "1.0E18"

private fun emitSolid(e: SolidExpr, p: GlslPoint, w: GlslWriter): String = when (e) {

    is SolidExpr.Sphere -> {
        val r2 = w.assign("t", "${p.x} * ${p.x} + ${p.y} * ${p.y} + ${p.z} * ${p.z}")
        w.assign("d", "sqrt($r2) - ${glslFloat(e.radius)}")
    }

    is SolidExpr.Cuboid -> emitBox3(p, e.x / 2, e.y / 2, e.z / 2, w)

    is SolidExpr.Cylinder -> emitCappedConeZ(p, abs(e.height) / 2, e.radiusLow, e.radiusHigh, w)

    is SolidExpr.Extrude -> {
        w.comment("extrude ${e.profile::class.simpleName} to z = ${e.height}")
        val h = e.height
        val twist = Math.toRadians(e.twistDegrees)
        val straight = twist == 0.0 && e.scaleTop == 1.0
        // `s` is computed once and used twice — for the inverse transform of the point and again to
        // put the profile's distance back into the unscaled frame. Two `assign`s of the same
        // expression would be correct and would also be the kind of thing that drifts.
        val s = if (straight) null else {
            val u = w.assign("t", "clamp(${p.z} / ${glslFloat(h)}, 0.0, 1.0)")
            w.assign("t", "1.0 + ${glslFloat(e.scaleTop - 1.0)} * $u") to u
        }
        val (qx, qy) = if (s == null) {
            p.x to p.y
        } else if (twist == 0.0) {
            w.assign("t", "${p.x} / ${s.first}") to w.assign("t", "${p.y} / ${s.first}")
        } else {
            val a = w.assign("t", "${glslFloat(-twist)} * ${s.second}")
            val ca = w.assign("t", "cos($a)")
            val sa = w.assign("t", "sin($a)")
            w.assign("t", "($ca * ${p.x} - $sa * ${p.y}) / ${s.first}") to
                w.assign("t", "($sa * ${p.x} + $ca * ${p.y}) / ${s.first}")
        }
        val raw = emitProfile(e.profile, qx, qy, w)
        val d2 = if (s == null) raw else w.assign("t", "$raw * ${s.first}")
        val dz = w.assign("t", "max(-${p.z}, ${p.z} - ${glslFloat(h)})")
        val ox = w.assign("t", "max($d2, 0.0)")
        val oz = w.assign("t", "max($dz, 0.0)")
        w.assign("d", "min(max($d2, $dz), 0.0) + sqrt($ox * $ox + $oz * $oz)")
    }

    is SolidExpr.Revolve -> {
        w.comment("revolve ${e.profile::class.simpleName} about Z by ${e.degrees} degrees")
        val r2 = w.assign("t", "${p.x} * ${p.x} + ${p.y} * ${p.y}")
        val r = w.assign("t", "sqrt($r2)")
        val body = emitProfile(e.profile, r, p.z, w)
        if (e.degrees >= 360.0) body else {
            val a = Math.toRadians(e.degrees)
            val d0 = w.assign("t", "-${p.y}")
            val d1 = w.assign("t", "-(${glslFloat(sin(a))} * ${p.x} - ${glslFloat(cos(a))} * ${p.y})")
            val wedge = if (e.degrees > 180.0) w.assign("t", "min($d0, $d1)") else w.assign("t", "max($d0, $d1)")
            w.assign("d", "max($body, $wedge)")
        }
    }

    is SolidExpr.Gyroid -> {
        val s = glslFloat(e.scale)
        val sx = w.assign("t", "sin(${p.x} * $s)"); val cx = w.assign("t", "cos(${p.x} * $s)")
        val sy = w.assign("t", "sin(${p.y} * $s)"); val cy = w.assign("t", "cos(${p.y} * $s)")
        val sz = w.assign("t", "sin(${p.z} * $s)"); val cz = w.assign("t", "cos(${p.z} * $s)")
        w.assign("d", "abs($sx * $cy + $sy * $cz + $sz * $cx) - ${glslFloat(e.thickness)}")
    }

    SolidExpr.Empty -> w.assign("d", GLSL_FAR)

    is SolidExpr.Translate -> emitSolid(
        e.child,
        GlslPoint(
            w.assign("x", "${p.x} - ${glslFloat(e.x)}"),
            w.assign("y", "${p.y} - ${glslFloat(e.y)}"),
            w.assign("z", "${p.z} - ${glslFloat(e.z)}"),
        ),
        w,
    )

    is SolidExpr.Rotate -> {
        // The transpose of the X-then-Y-then-Z Euler matrix, folded to nine literals — the same nine
        // `eulerMatrix` gives both other lowerings, so the three cannot drift apart on a convention.
        val m = transpose3(eulerMatrix(e.x, e.y, e.z))
        emitSolid(
            e.child,
            GlslPoint(
                w.assign("x", "${glslFloat(m[0])} * ${p.x} + ${glslFloat(m[1])} * ${p.y} + ${glslFloat(m[2])} * ${p.z}"),
                w.assign("y", "${glslFloat(m[3])} * ${p.x} + ${glslFloat(m[4])} * ${p.y} + ${glslFloat(m[5])} * ${p.z}"),
                w.assign("z", "${glslFloat(m[6])} * ${p.x} + ${glslFloat(m[7])} * ${p.y} + ${glslFloat(m[8])} * ${p.z}"),
            ),
            w,
        )
    }

    is SolidExpr.Scale -> {
        val inner = emitSolid(
            e.child,
            GlslPoint(
                w.assign("x", "${p.x} / ${glslFloat(e.x)}"),
                w.assign("y", "${p.y} / ${glslFloat(e.y)}"),
                w.assign("z", "${p.z} / ${glslFloat(e.z)}"),
            ),
            w,
        )
        w.assign("d", "$inner * ${glslFloat(min(abs(e.x), min(abs(e.y), abs(e.z))))}")
    }

    is SolidExpr.Mirror -> {
        val (nx, ny, nz) = normalize3(e.x, e.y, e.z)
        val dot = w.assign(
            "t",
            "2.0 * (${p.x} * ${glslFloat(nx)} + ${p.y} * ${glslFloat(ny)} + ${p.z} * ${glslFloat(nz)})",
        )
        emitSolid(
            e.child,
            GlslPoint(
                w.assign("x", "${p.x} - $dot * ${glslFloat(nx)}"),
                w.assign("y", "${p.y} - $dot * ${glslFloat(ny)}"),
                w.assign("z", "${p.z} - $dot * ${glslFloat(nz)}"),
            ),
            w,
        )
    }

    is SolidExpr.Round -> w.assign("d", "${emitSolid(e.child, p, w)} - ${glslFloat(e.radius)}")

    is SolidExpr.Shell -> w.assign("d", "abs(${emitSolid(e.child, p, w)}) - ${glslFloat(e.thickness)}")

    is SolidExpr.Union -> foldSolid(e.children, p, w, "min", GLSL_FAR)

    is SolidExpr.Intersect -> foldSolid(e.children, p, w, "max", "-$GLSL_FAR")

    is SolidExpr.Difference -> {
        if (e.children.isEmpty()) w.assign("d", GLSL_FAR) else {
            var acc = emitSolid(e.children[0], p, w)
            for (i in 1 until e.children.size) {
                val b = emitSolid(e.children[i], p, w)
                acc = w.assign("d", "max($acc, -$b)")
            }
            acc
        }
    }

    is SolidExpr.SmoothUnion -> {
        val da = emitSolid(e.a, p, w)
        val db = emitSolid(e.b, p, w)
        emitSmoothMin(da, db, max(e.k, 1e-9), w)
    }

    is SolidExpr.SmoothDifference -> {
        val da = emitSolid(e.a, p, w)
        val db = emitSolid(e.b, p, w)
        val nb = w.assign("t", "-$db")
        emitSmoothMax(da, nb, max(e.k, 1e-9), w)
    }
}

private fun foldSolid(
    children: List<SolidExpr>,
    p: GlslPoint,
    w: GlslWriter,
    op: String,
    empty: String,
): String {
    if (children.isEmpty()) return w.assign("d", empty)
    var acc = emitSolid(children[0], p, w)
    for (i in 1 until children.size) {
        val b = emitSolid(children[i], p, w)
        acc = w.assign("d", "$op($acc, $b)")
    }
    return acc
}

private fun emitBox3(p: GlslPoint, hx: Double, hy: Double, hz: Double, w: GlslWriter): String {
    val qx = w.assign("t", "abs(${p.x}) - ${glslFloat(hx)}")
    val qy = w.assign("t", "abs(${p.y}) - ${glslFloat(hy)}")
    val qz = w.assign("t", "abs(${p.z}) - ${glslFloat(hz)}")
    val ox = w.assign("t", "max($qx, 0.0)")
    val oy = w.assign("t", "max($qy, 0.0)")
    val oz = w.assign("t", "max($qz, 0.0)")
    return w.assign("d", "sqrt($ox * $ox + $oy * $oy + $oz * $oz) + min(max($qx, max($qy, $qz)), 0.0)")
}

private fun emitCappedConeZ(p: GlslPoint, h: Double, r1: Double, r2: Double, w: GlslWriter): String {
    w.comment("capped cone along Z, half-height $h, radii $r1 -> $r2")
    val qx = w.assign("t", "sqrt(${p.x} * ${p.x} + ${p.y} * ${p.y})")
    val qy = p.z
    val k2x = r2 - r1
    val k2y = 2.0 * h
    val k2dot = k2x * k2x + k2y * k2y
    val cax = w.assign("t", "$qx - min($qx, ($qy < 0.0) ? ${glslFloat(r1)} : ${glslFloat(r2)})")
    val cay = w.assign("t", "abs($qy) - ${glslFloat(h)}")
    val t = if (k2dot < 1e-30) w.assign("t", "0.0") else w.assign(
        "t",
        "clamp(((${glslFloat(r2)} - $qx) * ${glslFloat(k2x)} + (${glslFloat(h)} - $qy) * ${glslFloat(k2y)})" +
            " / ${glslFloat(k2dot)}, 0.0, 1.0)",
    )
    val cbx = w.assign("t", "$qx - ${glslFloat(r2)} + ${glslFloat(k2x)} * $t")
    val cby = w.assign("t", "$qy - ${glslFloat(h)} + ${glslFloat(k2y)} * $t")
    val s = w.assign("t", "(($cbx < 0.0) ? 1.0 : 0.0) * (($cay < 0.0) ? 1.0 : 0.0)")
    return w.assign(
        "d",
        "(1.0 - 2.0 * $s) * sqrt(min($cax * $cax + $cay * $cay, $cbx * $cbx + $cby * $cby))",
    )
}

private fun emitSmoothMin(a: String, b: String, k: Double, w: GlslWriter): String {
    val h = w.assign("t", "clamp(0.5 + 0.5 * ($b - $a) / ${glslFloat(k)}, 0.0, 1.0)")
    return w.assign("d", "$b + ($a - $b) * $h - ${glslFloat(k)} * $h * (1.0 - $h)")
}

private fun emitSmoothMax(a: String, b: String, k: Double, w: GlslWriter): String {
    val h = w.assign("t", "clamp(0.5 - 0.5 * ($b - $a) / ${glslFloat(k)}, 0.0, 1.0)")
    return w.assign("d", "$b + ($a - $b) * $h + ${glslFloat(k)} * $h * (1.0 - $h)")
}

// ---- 2D ----

private fun emitProfile(p: Profile, x: String, y: String, w: GlslWriter): String = when (p) {

    is Profile.Circle -> w.assign("d", "sqrt($x * $x + $y * $y) - ${glslFloat(p.radius)}")

    is Profile.Rect -> {
        val qx = w.assign("t", "abs($x) - ${glslFloat(p.x / 2)}")
        val qy = w.assign("t", "abs($y) - ${glslFloat(p.y / 2)}")
        val ox = w.assign("t", "max($qx, 0.0)")
        val oy = w.assign("t", "max($qy, 0.0)")
        w.assign("d", "sqrt($ox * $ox + $oy * $oy) + min(max($qx, $qy), 0.0)")
    }

    is Profile.Polygon -> emitPolygon(p.points, x, y, w)

    is Profile.Offset -> w.assign("d", "${emitProfile(p.child, x, y, w)} - ${glslFloat(p.delta)}")

    is Profile.Translate -> emitProfile(
        p.child,
        w.assign("u", "$x - ${glslFloat(p.x)}"),
        w.assign("v", "$y - ${glslFloat(p.y)}"),
        w,
    )

    is Profile.Rotate -> {
        val a = Math.toRadians(-p.degrees)
        emitProfile(
            p.child,
            w.assign("u", "${glslFloat(cos(a))} * $x - ${glslFloat(sin(a))} * $y"),
            w.assign("v", "${glslFloat(sin(a))} * $x + ${glslFloat(cos(a))} * $y"),
            w,
        )
    }

    is Profile.Scale -> {
        val inner = emitProfile(
            p.child,
            w.assign("u", "$x / ${glslFloat(p.x)}"),
            w.assign("v", "$y / ${glslFloat(p.y)}"),
            w,
        )
        w.assign("d", "$inner * ${glslFloat(min(abs(p.x), abs(p.y)))}")
    }

    is Profile.Union -> foldProfile(p.children, x, y, w, "min", GLSL_FAR)

    is Profile.Intersect -> foldProfile(p.children, x, y, w, "max", "-$GLSL_FAR")

    is Profile.Difference -> {
        if (p.children.isEmpty()) w.assign("d", GLSL_FAR) else {
            var acc = emitProfile(p.children[0], x, y, w)
            for (i in 1 until p.children.size) {
                val b = emitProfile(p.children[i], x, y, w)
                acc = w.assign("d", "max($acc, -$b)")
            }
            acc
        }
    }

    Profile.Empty -> w.assign("d", GLSL_FAR)
}

private fun foldProfile(
    children: List<Profile>,
    x: String,
    y: String,
    w: GlslWriter,
    op: String,
    empty: String,
): String {
    if (children.isEmpty()) return w.assign("d", empty)
    var acc = emitProfile(children[0], x, y, w)
    for (i in 1 until children.size) {
        val b = emitProfile(children[i], x, y, w)
        acc = w.assign("d", "$op($acc, $b)")
    }
    return acc
}

/**
 * Inigo Quilez's `sdPolygon`, unrolled — one block per edge, with the edge vector and its squared
 * length folded to literals because the vertices are compile-time constants.
 *
 * The winding test is arithmetic rather than boolean. `s *= -1` guarded by `all(cond) || all(!cond)`
 * needs `||` and `!`, and both are avoidable: the two products `a·b·c` and `(1−a)(1−b)(1−c)` are
 * mutually exclusive, so their sum is 0 or 1 and `1 − 2·(sum)` is the sign flip. Fewer operators in
 * the emitted grammar means a smaller reader on the other side, and the reader is what makes the
 * fidelity of this backend measurable at all.
 */
private fun emitPolygon(points: List<P2>, x: String, y: String, w: GlslWriter): String {
    if (points.size < 3) return w.assign("d", GLSL_FAR)
    w.comment("polygon, ${points.size} vertices")
    val n = points.size
    var d2 = w.assign(
        "t",
        "($x - ${glslFloat(points[0].x)}) * ($x - ${glslFloat(points[0].x)})" +
            " + ($y - ${glslFloat(points[0].y)}) * ($y - ${glslFloat(points[0].y)})",
    )
    var sign = w.assign("t", "1.0")
    var j = n - 1
    for (i in 0 until n) {
        val ex = points[j].x - points[i].x
        val ey = points[j].y - points[i].y
        val e2 = ex * ex + ey * ey
        val wx = w.assign("t", "$x - ${glslFloat(points[i].x)}")
        val wy = w.assign("t", "$y - ${glslFloat(points[i].y)}")
        val t = if (e2 < 1e-30) w.assign("t", "0.0") else
            w.assign("t", "clamp(($wx * ${glslFloat(ex)} + $wy * ${glslFloat(ey)}) / ${glslFloat(e2)}, 0.0, 1.0)")
        val bx = w.assign("t", "$wx - ${glslFloat(ex)} * $t")
        val by = w.assign("t", "$wy - ${glslFloat(ey)} * $t")
        d2 = w.assign("t", "min($d2, $bx * $bx + $by * $by)")
        val c1 = w.assign("t", "($y >= ${glslFloat(points[i].y)}) ? 1.0 : 0.0")
        val c2 = w.assign("t", "($y < ${glslFloat(points[j].y)}) ? 1.0 : 0.0")
        val c3 = w.assign("t", "(${glslFloat(ex)} * $wy > ${glslFloat(ey)} * $wx) ? 1.0 : 0.0")
        val flip = w.assign("t", "$c1 * $c2 * $c3 + (1.0 - $c1) * (1.0 - $c2) * (1.0 - $c3)")
        sign = w.assign("t", "$sign * (1.0 - 2.0 * $flip)")
        j = i
    }
    return w.assign("d", "$sign * sqrt($d2)")
}
