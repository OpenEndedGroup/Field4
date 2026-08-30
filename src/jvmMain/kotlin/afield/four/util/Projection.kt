package afield.four.util

import afield.four.Renderer
import afield.four.buffers.HasUniform
import afield.four.modules.fromContext
import org.ejml.data.Complex_F64
import org.ejml.simple.SimpleMatrix
import afield.stdlib.linalg.Matrix4f
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

// ============================================================================
// Simple perspective-only projection
// ============================================================================

class Projection : HasUniform<Matrix4f> {
    @JvmField
    val projectionMatrix: Matrix4f

    /**
     * Vertical field of view in **radians**, and it must be **positive**.
     *
     * A negative fovy does not flip the camera, it mirrors the image: `h = 1 / tan(fovy / 2)`
     * changes sign, which negates `m00` and `m11` together and so rotates the picture 180° while
     * preserving triangle winding. See [Options.fov] for the degrees-in-a-radians-slot bug that
     * shipped exactly that for the whole standard-renderer path.
     */
    var fov = Options.fov
    var zNear = Options.zNear
    var zFar = Options.zFar
    var aspect = 1.0f

    var auto: () -> Unit = {}

    constructor(width: Int, height: Int, fov: Double = Options.fov) {
        this.projectionMatrix = Matrix4f()
        this.fov = fov
        this.resize(width, height)
    }

    constructor(renderer: Renderer = fromContext()) {
        this.projectionMatrix = Matrix4f()
        resize(renderer.window.width, renderer.window.height)
        auto = {
            resize(renderer.window.width, renderer.window.height)
        }
    }

    fun resize(width: Int, height: Int): Projection {
        projectionMatrix.identity()
        aspect = width.toFloat() / height.toFloat()
        projectionMatrix.setPerspective(
            fov.toFloat(),
            aspect,
            zNear.toFloat(),
            zFar.toFloat(),
            true // Vulkan-style depth [0,1]
        )
        return this
    }

    override fun evaluateUniform(): Matrix4f {
        auto()
        return projectionMatrix
    }
}



class SuperProjection : HasUniform<Matrix4f> {

    @JvmField
    val projectionMatrix: Matrix4f

    /** Vertical field of view in **radians**, and must be positive — see [Projection.fov]. */
    var fov = Options.fov
    var zNear = Options.zNear
    var zFar = Options.zFar
    var aspect = 1.0f

    // Principal-point offsets at matchDepth, in units of "half width / half height".
    // ox = 0, oy = 0   → centered
    // ox = 1          → center shifted one half-width to +X at matchDepth
    // oy = 1          → center shifted one half-height to +Y at matchDepth
    var ox = 0.0
    var oy = 0.0

    // 0 = pure perspective, 1 = pure ortho (endpoints of the Lie path)
    var orthoness = 0.0

    // Distance along -Z (in view space) where the ortho footprint should match
    // the perspective footprint. If <= 0, we fall back to the midpoint between zNear/zFar.
    var matchDepth = 5.0

    // Optional callback to automatically choose matchDepth (e.g. focus distance).
    var autoMatchDistance: () -> Double
    constructor(width: Int, height: Int, fov: Double = Options.fov) {
        this.projectionMatrix = Matrix4f()
        this.fov = fov
        this.resize(width, height)
        this.autoMatchDistance = { matchDepth }
    }

    constructor(renderer: Renderer = fromContext(), autoMatchDistance: (() -> Double)? = null) {
        this.projectionMatrix = Matrix4f()
        this.autoMatchDistance = autoMatchDistance ?: { matchDepth }
        resize(renderer.window.width, renderer.window.height)
        auto = {
            matchDepth = this.autoMatchDistance()
            resize(renderer.window.width, renderer.window.height)
        }
    }
    var auto: () -> Unit = {}



    fun resize(width: Int, height: Int): SuperProjection {
        aspect = width.toFloat() / height.toFloat()

        val m = buildLieInterpolatedProjection()
        projectionMatrix.set(m)
        return this
    }

    fun setAspect(aspect: Float): SuperProjection {
        this.aspect = aspect
        val m = buildLieInterpolatedProjection()
        projectionMatrix.set(m)
        return this
    }

    override fun evaluateUniform(): Matrix4f {
        auto()
        return projectionMatrix
    }

    /**
     * Build a projection by Lie-interpolating between:
     *  - an off-center perspective matrix (with FOV, aspect, zNear, zFar, ox, oy)
     *  - an off-center orthographic matrix whose footprint (l/r/b/t) at `matchDepth`
     *    matches that perspective footprint at the same depth.
     *
     * Then apply:
     *  - depth renormalization to [0,1]
     *  - isotropic FOV renormalization so the vertical scale at `matchDepth`
     *    matches the perspective's vertical scale there.
     */
    private fun buildLieInterpolatedProjection(): Matrix4f {
        val fovF = fov.toFloat()
        val nearF = zNear.toFloat()
        val farF = zFar.toFloat()

        // Choose the effective match depth (fallback to midpoint if needed).
        val matchDepthD = if (matchDepth > 0.0) {
            matchDepth
        } else {
            0.5 * (zNear + zFar)
        }
        val matchDepthF = matchDepthD.toFloat()

        // At depth d along -Z, the symmetric perspective half-height is:
        //   h(d) = d * tan(fov/2)
        // and half-width = h(d) * aspect.
        val halfHeightD = matchDepthD * tan(0.5 * fov)
        val halfWidthD = halfHeightD * aspect

        // Base symmetric extents at match depth (Double for accuracy)
        val lBaseD = -halfWidthD
        val rBaseD = +halfWidthD
        val bBaseD = -halfHeightD
        val tBaseD = +halfHeightD

        // Apply principal-point offsets at matchDepth.
        // ox/oy are in "half-width/half-height units" at matchDepth.
        val dxD = ox * halfWidthD
        val dyD = oy * halfHeightD

        val lMatch = (lBaseD + dxD).toFloat()
        val rMatch = (rBaseD + dxD).toFloat()
        val bMatch = (bBaseD + dyD).toFloat()
        val tMatch = (tBaseD + dyD).toFloat()

        // Perspective: we need extents at the *near plane* for a frustum.
        // Given extents at depth d, near-plane extents are scaled by (zNear / d).
        val scaleToNear = nearF / matchDepthF
        val lNear = lMatch * scaleToNear
        val rNear = rMatch * scaleToNear
        val bNear = bMatch * scaleToNear
        val tNear = tMatch * scaleToNear

        // Off-center perspective with Vulkan-style depth [0,1]
        val persp = Matrix4f()
            .identity()
            .frustum(lNear, rNear, bNear, tNear, nearF, farF, true)

        // Off-center ortho with the same footprint (l/r/b/t) at matchDepth.
        // Since ortho is a prism, these extents hold for all depths; objects at
        // matchDepth see identical x/y mapping in perspective vs. ortho.
        val ortho = Matrix4f()
            .identity()
            .ortho(lMatch, rMatch, bMatch, tMatch, nearF, farF)

        val tBlend = orthoness.toFloat().coerceIn(0f, 1f)

        // Lie interpolation in GL(4): P(t) = P0 * exp(t * log(P0^{-1} * P1))
        // Here P0 = perspective, P1 = ortho.
        val raw = lieInterpolateProjectionEJML(persp, ortho, tBlend)

        // Renormalize depth and FOV.
        val perspRef = Matrix4f().set(persp)
        val zRefF = matchDepthF

        raw
            .renormDepth01(nearF, farF)
            .renormVerticalFovAtDepth(perspRef, zRefF, weight = 1f)

        return raw
    }
}

// ============================================================================
// Field linalg <-> EJML conversion
// ============================================================================

// Field Matrix4f -> EJML SimpleMatrix (4x4, row-major)
fun matrix4fToSimple(m: Matrix4f): SimpleMatrix {
    return SimpleMatrix(
        4, 4, true,
        // Row 0 (x' row)
        m.m00().toDouble(), m.m10().toDouble(), m.m20().toDouble(), m.m30().toDouble(),
        // Row 1 (y' row)
        m.m01().toDouble(), m.m11().toDouble(), m.m21().toDouble(), m.m31().toDouble(),
        // Row 2 (z' row)
        m.m02().toDouble(), m.m12().toDouble(), m.m22().toDouble(), m.m32().toDouble(),
        // Row 3 (w' row)
        m.m03().toDouble(), m.m13().toDouble(), m.m23().toDouble(), m.m33().toDouble()
    )
}

// EJML SimpleMatrix (4x4) -> Field Matrix4f
fun simpleToMatrix4f(s: SimpleMatrix): Matrix4f {
    require(s.numRows == 4 && s.numCols == 4) { "Expected 4x4 matrix" }

    val m = Matrix4f()

    // Row 0
    m.m00(s.get(0, 0).toFloat())
    m.m10(s.get(0, 1).toFloat())
    m.m20(s.get(0, 2).toFloat())
    m.m30(s.get(0, 3).toFloat())

    // Row 1
    m.m01(s.get(1, 0).toFloat())
    m.m11(s.get(1, 1).toFloat())
    m.m21(s.get(1, 2).toFloat())
    m.m31(s.get(1, 3).toFloat())

    // Row 2
    m.m02(s.get(2, 0).toFloat())
    m.m12(s.get(2, 1).toFloat())
    m.m22(s.get(2, 2).toFloat())
    m.m32(s.get(2, 3).toFloat())

    // Row 3
    m.m03(s.get(3, 0).toFloat())
    m.m13(s.get(3, 1).toFloat())
    m.m23(s.get(3, 2).toFloat())
    m.m33(s.get(3, 3).toFloat())

    return m
}

// ============================================================================
// Matrix exp/log via eigen decomposition (EJML)
// ============================================================================

private const val IMAG_TOL = 1e-8
private const val POS_TOL = 1e-12

/**
 * Matrix exponential via eigen decomposition:
 *   A = V * diag(λ) * V^{-1}
 *   exp(A) = V * diag(exp(λ)) * V^{-1}
 *
 * Assumptions:
 *  - A has a full set of real eigenvectors (V invertible)
 *  - eigenvalues are numerically real
 */
fun expmEigen(A: SimpleMatrix): SimpleMatrix {
    require(A.numRows == A.numCols) { "expmEigen requires a square matrix" }
    val n = A.numRows

    val evd = A.eig()
    val V = SimpleMatrix(n, n)
    val Dexp = SimpleMatrix(n, n)

    for (i in 0 until evd.numberOfEigenvalues) {
        val lambda: Complex_F64 = evd.getEigenvalue(i)
        if (abs(lambda.imaginary) > IMAG_TOL) {
            throw IllegalArgumentException(
                "expmEigen: complex eigenvalue λ=$lambda — not supported in this real implementation"
            )
        }
        val lam = lambda.real

        val v: SimpleMatrix? = evd.getEigenVector(i)
        require(v != null) { "expmEigen: missing eigenvector for eigenvalue index $i" }

        // Place v as column i in V
        for (r in 0 until n) {
            V.set(r, i, v.get(r, 0))
        }

        Dexp.set(i, i, exp(lam))
    }

    val Vinv = V.invert()
    return V.mult(Dexp).mult(Vinv)
}

/**
 * Matrix logarithm via eigen decomposition (real principal log):
 *   A = V * diag(λ) * V^{-1}
 *   log(A) = V * diag(log(λ)) * V^{-1}
 *
 * Requires:
 *  - all eigenvalues have |Im(λ)| < IMAG_TOL
 *  - all eigenvalues have Re(λ) > POS_TOL (no nonpositive real eigenvalues)
 *  - A has a full set of real eigenvectors (V invertible)
 */
fun logmEigen(A: SimpleMatrix): SimpleMatrix {
    require(A.numRows == A.numCols) { "logmEigen requires a square matrix" }
    val n = A.numRows

    val evd = A.eig()
    val V = SimpleMatrix(n, n)
    val Dlog = SimpleMatrix(n, n)

    for (i in 0 until evd.numberOfEigenvalues) {
        val lambda: Complex_F64 = evd.getEigenvalue(i)
        if (abs(lambda.imaginary) > IMAG_TOL) {
            throw IllegalArgumentException(
                "logmEigen: complex eigenvalue λ=$lambda — real principal log not implemented here"
            )
        }
        val lam = lambda.real
        if (lam <= POS_TOL) {
            throw IllegalArgumentException(
                "logmEigen: non-positive eigenvalue λ=$lam — real log undefined"
            )
        }

        val v: SimpleMatrix? = evd.getEigenVector(i)
        require(v != null) { "logmEigen: missing eigenvector for eigenvalue index $i" }

        for (r in 0 until n) {
            V.set(r, i, v.get(r, 0))
        }

        Dlog.set(i, i, ln(lam))
    }

    val Vinv = V.invert()
    return V.mult(Dlog).mult(Vinv)
}

/**
 * Lie-group interpolation in GL(4, R) between P0 and P1:
 *
 *   M = P0^{-1} * P1
 *   L = log(M)
 *   P(t) = P0 * exp(t * L)
 *
 * t in [0,1], so P(0) = P0, P(1) = P1.
 */
fun lieInterpolateProjectionEJML(
    P0: Matrix4f,
    P1: Matrix4f,
    t: Float
): Matrix4f {
    require(t in 0f..1f) { "t must be in [0,1], got $t" }

    val A0 = matrix4fToSimple(P0)
    val A1 = matrix4fToSimple(P1)

    val M = A0.invert().mult(A1) // relative transform P0^{-1} P1

    val L = logmEigen(M)
    val Lt = L.scale(t.toDouble())
    val E = expmEigen(Lt)

    val At = A0.mult(E)
    return simpleToMatrix4f(At)
}

// ============================================================================
// Renormalization helpers (depth + FOV) on Matrix4f
// ============================================================================

// Field transform (column-major):
// x' = m00*x + m10*y + m20*z + m30*w
// y' = m01*x + m11*y + m21*z + m31*w
// z' = m02*x + m12*y + m22*z + m32*w
// w' = m03*x + m13*y + m23*z + m33*w

private fun Matrix4f.ndcDepthAt(zView: Float): Float {
    val x = 0f
    val y = 0f
    val z = zView
    val w = 1f

    val cz = m02() * x + m12() * y + m22() * z + m32() * w
    val cw = m03() * x + m13() * y + m23() * z + m33() * w

    return cz / cw
}

private fun Matrix4f.ndcY(x: Float, y: Float, z: Float): Float {
    val w = 1f
    val cy = m01() * x + m11() * y + m21() * z + m31() * w
    val cw = m03() * x + m13() * y + m23() * z + m33() * w
    return cy / cw
}

/**
 * Approximate vertical scale in NDC at a given view-space depth:
 *   scale ≈ d(y_ndc) / d(y_view)
 */
private fun Matrix4f.verticalScaleAtDepth(zView: Float, sampleY: Float = 1f): Float {
    val x = 0f
    val y0 = 0f
    val y1 = sampleY

    val yNdc0 = ndcY(x, y0, zView)
    val yNdc1 = ndcY(x, y1, zView)

    return (yNdc1 - yNdc0) / sampleY
}

/**
 * Remap depth so that:
 *   z = -near → z_ndc = 0
 *   z = -far  → z_ndc = 1
 *
 * Assumes right-handed view space (camera at origin, looking down -Z).
 * Only adjusts the Z row; W row is preserved.
 */
fun Matrix4f.renormDepth01(near: Float, far: Float): Matrix4f {
    val zn = ndcDepthAt(-near)
    val zf = ndcDepthAt(-far)

    val eps = 1e-6f
    val denom = zf - zn
    if (abs(denom) < eps) {
        // Degenerate mapping; leave it alone.
        return this
    }

    // z_ndc' = (z_ndc - zn) / (zf - zn) = a*z_ndc + b
    val a = 1f / denom
    val b = -zn / denom

    // Old Z row and W row
    val z0 = m02()
    val w0 = m03()
    val z1 = m12()
    val w1 = m13()
    val z2 = m22()
    val w2 = m23()
    val z3 = m32()
    val w3 = m33()

    // New Z row = a * oldZ + b * oldW
    m02(a * z0 + b * w0)
    m12(a * z1 + b * w1)
    m22(a * z2 + b * w2)
    m32(a * z3 + b * w3)

    return this
}

/**
 * Isotropic FOV renormalization at a given depth `zRef`.
 *
 * Adjusts both X and Y rows so that the vertical NDC scale at zRef matches
 * that of the reference perspective matrix. The `weight` parameter blends
 * between no change (0) and full correction (1).
 */
fun Matrix4f.renormVerticalFovAtDepth(
    referencePersp: Matrix4f,
    zRef: Float,
    sampleY: Float = 1f,
    weight: Float = 1f
): Matrix4f {
    val w = weight.coerceIn(0f, 1f)
    if (w <= 0f) {
        // No FOV correction requested
        return this
    }

    val zView = -zRef

    val targetScale = referencePersp.verticalScaleAtDepth(zView, sampleY)
    val currentScale = this.verticalScaleAtDepth(zView, sampleY)

    val eps = 1e-6f
    if (kotlin.math.abs(currentScale) < eps) {
        // Avoid dividing by ~0; skip in pathological cases
        return this
    }

    // Full correction factor (what we'd apply with weight = 1)
    val full = targetScale / currentScale

    // Blend between 1 (no change) and full correction
    // weight = 0 -> s = 1
    // weight = 1 -> s = full
    val s = 1f + w * (full - 1f)

    // Isotropic: scale BOTH X and Y rows by the same factor s.

    // X row: (m00, m10, m20, m30)
    m00(m00() * s)
    m10(m10() * s)
    m20(m20() * s)
    m30(m30() * s)

    // Y row: (m01, m11, m21, m31)
    m01(m01() * s)
    m11(m11() * s)
    m21(m21() * s)
    m31(m31() * s)

    return this
}
