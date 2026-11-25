@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.lang.solidsl.study

import afield.lang.solidsl.SolidExpr
import afield.lang.solidsl.bounds
import afield.lang.solidsl.toGlsl
import afield.lang.solidsl.toLevelSetField
import afield.lang.stacksl.cpu.CpuRaster
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a SolidSL study draws: a [SolidExpr], optionally shaded by GLSL of the study's own, seen from
 * an orbiting camera.
 *
 * A study box's Kotlin returns a [SolidExpr] or a [SolidScene] ([shaded] makes one). [SolidStudyShader]
 * turns it into the fragment shader the window and the GPU frames draw, and [SolidCpuTracer] draws
 * the same solid with the default shading when there is no window. [SolidView] is where the camera is.
 */

/**
 * A solid and how it is shaded.
 *
 * [shade] is GLSL that defines `vec3 shade(vec3 p, vec3 n, vec3 rd, float t)`: the linear colour
 * of the surface point `p` with unit normal `n`, seen along the ray `rd`, at time `t` in seconds.
 * Blank uses [SolidStudyShader.DEFAULT_SHADE]. It may call `sdScene(p)`, `softShadow(p, l)`,
 * `occlusion(p, n)`, `CENTRE` and `RADIUS`. [background] is the linear colour behind the solid, as
 * `0xRRGGBB` in sRGB.
 */
data class SolidScene(val solid: SolidExpr, val shade: String = "", val background: Int = 0x14161c)

/** This solid, shaded by the GLSL [shade] function: see [SolidScene]. */
fun SolidExpr.shaded(shade: String, background: Int = 0x14161c): SolidScene = SolidScene(this, shade, background)

/**
 * Where the camera is: it orbits the solid's centre, z up.
 *
 * [yawDegrees] turns it about the vertical axis (0 looks from −y towards +y, the front; 90 from +x),
 * [pitchDegrees] raises it above the horizon (90 looks straight down), and [distance] is in multiples
 * of the solid's bounding radius. [section] cuts the solid open: `x`, `y` or `z` removes everything
 * beyond the plane through the centre (moved by [sectionOffset]) normal to that axis, on the camera's
 * side, so the inside shows. [seconds] is the time the shading sees.
 */
data class SolidView(
    val yawDegrees: Double = 35.0,
    val pitchDegrees: Double = 24.0,
    val distance: Double = 1.6,
    val section: String = "",
    val sectionOffset: Double = 0.0,
    val seconds: Double = 0.0,
) {
    init {
        require(section in setOf("", "x", "y", "z")) { "section is x, y, z or empty" }
        require(distance.isFinite() && distance >= 1.05) { "distance is in multiples of the bounding radius, at least 1.05" }
    }

    companion object {
        /** The turntable a study's time runs: 30 degrees a second from 35 degrees, at a 24-degree pitch. */
        fun turntable(seconds: Double) = SolidView(yawDegrees = 35.0 + 30.0 * seconds, seconds = seconds)
    }
}

/** The fragment shader that draws a [SolidScene] from a [SolidView] given in push constants. */
object SolidStudyShader {
    /**
     * The shading a scene gets when it has none of its own: a warm key light with a soft shadow, a cool
     * fill, a rim, and occlusion in the creases, over a pale grey-blue.
     */
    val DEFAULT_SHADE = """
        vec3 shade(vec3 p, vec3 n, vec3 rd, float t) {
            vec3 key = normalize(vec3(0.5, -0.6, 0.8));
            float diffuse = max(dot(n, key), 0.0) * softShadow(p + n * RADIUS * 0.002, key);
            float fill = max(dot(n, normalize(vec3(-0.7, 0.4, 0.3))), 0.0);
            float rim = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
            float ao = occlusion(p, n);
            vec3 base = vec3(0.60, 0.64, 0.70);
            return base * (vec3(1.0, 0.95, 0.86) * 0.95 * diffuse + vec3(0.16, 0.20, 0.30) * fill + 0.06) * ao + vec3(0.20, 0.24, 0.32) * rim * ao;
        }
    """.trimIndent()

    /** The fullscreen-triangle vertex shader the fragment is drawn with. */
    val VERTEX = """
        #version 450
        void main() {
            vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    /** The centre and bounding radius the camera orbits, from the solid's bounds (an unbounded one gets the origin and 2). */
    fun frame(solid: SolidExpr): Pair<DoubleArray, Double> {
        val b = solid.bounds() ?: return doubleArrayOf(0.0, 0.0, 0.0) to 2.0
        val s = b.size
        val c = b.center
        val radius = 0.5 * sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
        return doubleArrayOf(c.x.toDouble(), c.y.toDouble(), c.z.toDouble()) to max(radius, 1e-3)
    }

    /** The push-constant values for [view] at [width] by [height]: `view` and `frame`, four floats each. */
    fun push(view: SolidView, width: Int, height: Int): Pair<FloatArray, FloatArray> = floatArrayOf(
        Math.toRadians(view.yawDegrees).toFloat(), Math.toRadians(view.pitchDegrees).toFloat(), view.distance.toFloat(), view.seconds.toFloat(),
    ) to floatArrayOf(width.toFloat(), height.toFloat(), when (view.section) { "x" -> 1f; "y" -> 2f; "z" -> 3f; else -> 0f }, view.sectionOffset.toFloat())

    private fun f(v: Double) = afield.lang.solidsl.glslFloat(v)

    /**
     * The fragment shader for [scene]. It writes linear colour with alpha 1; the window encodes it for
     * display, and a study's frames are encoded the same way ([afield.study.evidence.StudyEvidence.presented]).
     */
    fun fragment(scene: SolidScene, maxSteps: Int = 160): String {
        val g = scene.solid.toGlsl("sdSolid")
        val (centre, radius) = frame(scene.solid)
        fun channel(shift: Int) = srgbToLinear(((scene.background shr shift) and 0xff) / 255.0)
        return """
            |#version 450
            |layout(location = 0) out vec4 outColor;
            |layout(push_constant) uniform Push { vec4 view; vec4 frame; } pc;
            |
            |${g.glsl.trimEnd()}
            |
            |const vec3 CENTRE = vec3(${f(centre[0])}, ${f(centre[1])}, ${f(centre[2])});
            |const float RADIUS = ${f(radius)};
            |const float STEP_SCALE = ${f(g.stepScale)};
            |
            |vec3 eyeOf() {
            |    float yaw = pc.view.x, pitch = pc.view.y;
            |    return CENTRE + RADIUS * pc.view.z * vec3(sin(yaw) * cos(pitch), -cos(yaw) * cos(pitch), sin(pitch));
            |}
            |
            |float sectionSide() {
            |    vec3 e = eyeOf() - CENTRE;
            |    int axis = int(pc.frame.z + 0.5);
            |    float s = axis == 1 ? e.x : (axis == 2 ? e.y : e.z);
            |    return s >= 0.0 ? 1.0 : -1.0;
            |}
            |
            |// The solid, cut open when a section is asked for: everything beyond the plane, on the camera's side, goes.
            |float sdScene(vec3 p) {
            |    float d = sdSolid(p);
            |    int axis = int(pc.frame.z + 0.5);
            |    if (axis > 0) {
            |        vec3 q = p - CENTRE;
            |        float s = axis == 1 ? q.x : (axis == 2 ? q.y : q.z);
            |        d = max(d, (s - pc.frame.w) * sectionSide());
            |    }
            |    return d;
            |}
            |
            |vec3 sdNormal(vec3 p) {
            |    float h = RADIUS * 2e-4;
            |    vec2 k = vec2(1.0, -1.0);
            |    return normalize(k.xyy * sdScene(p + k.xyy * h) + k.yyx * sdScene(p + k.yyx * h) +
            |                     k.yxy * sdScene(p + k.yxy * h) + k.xxx * sdScene(p + k.xxx * h));
            |}
            |
            |float softShadow(vec3 ro, vec3 l) {
            |    float res = 1.0;
            |    float t = RADIUS * 0.01;
            |    for (int i = 0; i < 48; ++i) {
            |        float h = sdScene(ro + l * t) * STEP_SCALE;
            |        res = min(res, 10.0 * h / t);
            |        if (res < 0.002 || t > RADIUS * 3.0) break;
            |        t += clamp(h, RADIUS * 0.004, RADIUS * 0.2);
            |    }
            |    return clamp(res, 0.0, 1.0);
            |}
            |
            |float occlusion(vec3 p, vec3 n) {
            |    float o = 0.0;
            |    float w = 1.0;
            |    for (int i = 1; i <= 5; ++i) {
            |        float h = RADIUS * 0.03 * float(i);
            |        o += w * (h - sdScene(p + n * h));
            |        w *= 0.6;
            |    }
            |    return clamp(1.0 - 4.0 * o / RADIUS, 0.0, 1.0);
            |}
            |
            |${scene.shade.ifBlank { DEFAULT_SHADE }.trimEnd()}
            |
            |void main() {
            |    vec2 res = pc.frame.xy;
            |    vec2 uv = (gl_FragCoord.xy * 2.0 - res) / res.y;
            |    uv.y = -uv.y; // rows count from the top
            |    vec3 eye = eyeOf();
            |    vec3 forward = normalize(CENTRE - eye);
            |    vec3 right = normalize(cross(forward, vec3(0.0, 0.0, 1.0)));
            |    if (abs(forward.z) > 0.999) right = vec3(1.0, 0.0, 0.0);
            |    vec3 up = cross(right, forward);
            |    vec3 rd = normalize(forward * 1.7 + right * uv.x + up * uv.y);
            |    float far = RADIUS * (pc.view.z + 1.5);
            |    float t = max(0.0, length(eye - CENTRE) - RADIUS * 1.05);
            |    bool hit = false;
            |    for (int i = 0; i < $maxSteps; ++i) {
            |        float d = sdScene(eye + rd * t);
            |        if (abs(d) < RADIUS * 2e-4 * (1.0 + t / RADIUS)) { hit = true; break; }
            |        t += max(d * STEP_SCALE, RADIUS * 1e-4);
            |        if (t > far) break;
            |    }
            |    vec3 bg = vec3(${f(channel(16))}, ${f(channel(8))}, ${f(channel(0))});
            |    vec3 colour = bg * (1.0 - 0.25 * length(uv) * 0.5);
            |    if (hit) {
            |        vec3 p = eye + rd * t;
            |        colour = shade(p, sdNormal(p), rd, pc.view.w);
            |    }
            |    outColor = vec4(colour, 1.0);
            |}
        """.trimMargin()
    }

    /** The sRGB decoding function, for an encoded value in `0..1`. */
    fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
}

/**
 * The CPU reference: [SolidScene.solid] sphere-traced in Kotlin through its field lowering, with the
 * default shading's key, fill, rim and occlusion but no shadow, whatever [SolidScene.shade] says.
 * Used when there is no window to draw on; a study says so with every frame.
 */
object SolidCpuTracer {
    /** [scene] at [width] by [height] from [view], as linear colour, rows top to bottom. */
    fun render(scene: SolidScene, width: Int, height: Int, view: SolidView): CpuRaster {
        val field = scene.solid.toLevelSetField()
        val step = scene.solid.toGlsl().stepScale
        val (c, radius) = SolidStudyShader.frame(scene.solid)
        val axis = when (view.section) { "x" -> 0; "y" -> 1; "z" -> 2; else -> -1 }
        val yaw = Math.toRadians(view.yawDegrees); val pitch = Math.toRadians(view.pitchDegrees)
        val eye = doubleArrayOf(c[0] + radius * view.distance * sin(yaw) * cos(pitch),
            c[1] - radius * view.distance * cos(yaw) * cos(pitch), c[2] + radius * view.distance * sin(pitch))
        val side = if (axis < 0 || eye[axis] - c[axis] >= 0) 1.0 else -1.0
        fun sd(x: Double, y: Double, z: Double): Double {
            var d = -field.at(x, y, z)
            if (axis >= 0) {
                val s = (if (axis == 0) x else if (axis == 1) y else z) - c[axis]
                d = max(d, (s - view.sectionOffset) * side)
            }
            return d
        }
        val fw = norm(doubleArrayOf(c[0] - eye[0], c[1] - eye[1], c[2] - eye[2]))
        var right = norm(cross(fw, doubleArrayOf(0.0, 0.0, 1.0)))
        if (kotlin.math.abs(fw[2]) > 0.999) right = doubleArrayOf(1.0, 0.0, 0.0)
        val up = cross(right, fw)
        fun lin(shift: Int) = SolidStudyShader.srgbToLinear(((scene.background shr shift) and 0xff) / 255.0)
        val bg = doubleArrayOf(lin(16), lin(8), lin(0))
        val key = norm(doubleArrayOf(0.5, -0.6, 0.8)); val fillDir = norm(doubleArrayOf(-0.7, 0.4, 0.3))
        val rgba = FloatArray(width * height * 4)
        IntStream.range(0, height).parallel().forEach { py ->
            for (px in 0 until width) {
                val u = ((px + 0.5) * 2.0 - width) / height
                val v = -((py + 0.5) * 2.0 - height) / height
                val rd = norm(doubleArrayOf(fw[0] * 1.7 + right[0] * u + up[0] * v, fw[1] * 1.7 + right[1] * u + up[1] * v, fw[2] * 1.7 + right[2] * u + up[2] * v))
                val far = radius * (view.distance + 1.5)
                var t = max(0.0, radius * view.distance - radius * 1.05)
                var hit = false
                for (i in 0 until 160) {
                    val d = sd(eye[0] + rd[0] * t, eye[1] + rd[1] * t, eye[2] + rd[2] * t)
                    if (kotlin.math.abs(d) < radius * 5e-4 * (1 + t / radius)) { hit = true; break }
                    t += max(d * step, radius * 1e-4)
                    if (t > far) break
                }
                val base = (py * width + px) * 4
                val shade = 1.0 - 0.125 * sqrt(u * u + v * v)
                var col = doubleArrayOf(bg[0] * shade, bg[1] * shade, bg[2] * shade)
                if (hit) {
                    val p = doubleArrayOf(eye[0] + rd[0] * t, eye[1] + rd[1] * t, eye[2] + rd[2] * t)
                    val h = radius * 2e-4
                    val n = norm(doubleArrayOf(
                        sd(p[0] + h, p[1], p[2]) - sd(p[0] - h, p[1], p[2]),
                        sd(p[0], p[1] + h, p[2]) - sd(p[0], p[1] - h, p[2]),
                        sd(p[0], p[1], p[2] + h) - sd(p[0], p[1], p[2] - h)))
                    var o = 0.0; var w = 1.0
                    for (i in 1..5) { val hh = radius * 0.03 * i; o += w * (hh - sd(p[0] + n[0] * hh, p[1] + n[1] * hh, p[2] + n[2] * hh)); w *= 0.6 }
                    val ao = (1.0 - 4.0 * o / radius).coerceIn(0.0, 1.0)
                    val diffuse = max(dot(n, key), 0.0)
                    val fill = max(dot(n, fillDir), 0.0)
                    val rim = Math.pow(1.0 - max(-dot(n, rd), 0.0), 3.0)
                    val base3 = doubleArrayOf(0.60, 0.64, 0.70)
                    val keyCol = doubleArrayOf(1.0, 0.95, 0.86); val fillCol = doubleArrayOf(0.16, 0.20, 0.30); val rimCol = doubleArrayOf(0.20, 0.24, 0.32)
                    col = DoubleArray(3) { k -> base3[k] * (keyCol[k] * 0.95 * diffuse + fillCol[k] * fill + 0.06) * ao + rimCol[k] * rim * ao }
                }
                rgba[base] = col[0].toFloat(); rgba[base + 1] = col[1].toFloat(); rgba[base + 2] = col[2].toFloat(); rgba[base + 3] = 1f
            }
        }
        return CpuRaster(width, height, rgba)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: DoubleArray): DoubleArray { val l = sqrt(dot(a, a)).takeIf { it > 0 } ?: 1.0; return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l) }
}
