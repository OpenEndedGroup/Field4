package afield.four.util

import afield.four.Renderer
import afield.four.buffers.HasUniform
import afield.four.modules.fromContext
import org.joml.Matrix4f

class Projection : HasUniform<Matrix4f> {
    @JvmField
    val projectionMatrix: Matrix4f

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
        projectionMatrix.perspective(
            fov.toFloat(), aspect,
            zNear.toFloat(), zFar.toFloat(), true
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

    var fov = Options.fov
    var zNear = Options.zNear
    var zFar = Options.zFar
    var aspect = 1.0f
    var ox = 0.0
    var oy = 0.0
    var orthoness = 1.0


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


    fun resize(width: Int, height: Int): SuperProjection {
        projectionMatrix.identity()
        aspect = width.toFloat() / height.toFloat()
        projectionMatrix.set(projectionVulkanMorphing(fov, aspect.toDouble(), zNear, zFar, ox, oy, orthoness, zRef = (0.5*(zNear+zFar))))
        return this
    }


    override fun evaluateUniform(): Matrix4f {
        auto()
        return projectionMatrix
    }

    fun projectionVulkanMorphing(
        fovYRadians: Double,
        aspect: Double,
        near: Double,
        far: Double,
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        rho: Double = 1.0,
        zRef: Double = 0.5 * (near + far),
        flipY: Boolean = true
    ): FloatArray {
        require(near > 0.0 && far > near) { "Require 0 < near < far" }
        require(aspect > 0.0) { "Require aspect > 0" }
        val rclamp = rho.coerceIn(0.0, 1.0)

        // Near-plane half-extent
        val t = near * kotlin.math.tan(0.5 * fovYRadians) // half-height
        val b = -t
        val r = t * aspect                                // half-width
        val l = -r

        // Principal-point offsets in near-plane units
        val dx = offsetX * r
        val dy = offsetY * t
        val lP = l + dx
        val rP = r + dx
        val bP = b + dy
        val tP = t + dy

        // Core scalars for off-center form
        val ax = 2.0 * near / (rP - lP)
        val ay = 2.0 * near / (tP - bP)
        val bx = (rP + lP) / (rP - lP)
        val by = (tP + bP) / (tP - bP)

        // We will enforce: w_clip = D(z) = rho*z + (1-rho)*zRef
        val wZ = rclamp
        val w1 = (1.0 - rclamp) * zRef

        // To keep x_ndc = (ax * x) / D + bx (and same for y), set:
        // x_clip = ax * x + bx * D  => x_row: [ax, 0, bx*wZ, bx*w1]
        // y_clip = ay * y + by * D  => y_row: [0, ay*(flip?), by*wZ*(flip?), by*w1*(flip?)]
        val sY = if (flipY) -1.0 else 1.0

        // Depth (z_clip): build something that is exact at the endpoints:
        //  - rho=1: standard perspective z_clip = Ap*z + Bp, with w=z
        //  - rho=0: scaled-ortho z_ndc = (z - near)/(far - near)
        //           With w=zRef and z_clip_ortho = ((z - near)/(far - near)) * w
        //
        // Choose a simple blend in numerators:
        val Ap = far / (far - near)         // perspective m33
        val Bp = -near * far / (far - near) // perspective m43
        val Ao = 1.0 / (far - near)         // orthographic slope (pre-multiplied by w later)
        val Bo = -near / (far - near)

        // z_clip = rho * (Ap*z + Bp) + (1-rho) * ((Ao*z + Bo) * D)
        // Expand: z_row: [ 0, 0,  rho*Ap + (1-rho)*Ao*D_z,  rho*Bp + (1-rho)*Ao*D_1 + (1-rho)*Bo*D_? ]
        // Where D = wZ*z + w1*1, so:
        val z_col2 = rclamp * Ap + (1.0 - rclamp) * Ao * wZ       // multiplies 'z'
        val z_col3 = rclamp * Bp + (1.0 - rclamp) * (Ao * w1 + Bo * (wZ * 0.0 + w1)) // constant part
        // Note: we omit a z^2 term (can’t be expressed by a 4×4); this keeps things linear and monotone.

        // Assemble column-major mat4
        val m = FloatArray(16) { 0f }
        fun set(c: Int, r0: Int, v: Double) {
            m[c * 4 + r0] = v.toFloat()
        }

        // Column 0 (multiplies x)
        set(0, 0, ax)       // x -> x_clip
        set(0, 1, 0.0)      // x -> y_clip
        set(0, 2, 0.0)      // x -> z_clip
        set(0, 3, 0.0)      // x -> w_clip

        // Column 1 (multiplies y)
        set(1, 0, 0.0)
        set(1, 1, -ay * sY)
        set(1, 2, 0.0)
        set(1, 3, 0.0)

        // Column 2 (multiplies z)
        set(2, 0, bx * wZ)          // contributes bx * (wZ*z) to x_clip
        set(2, 1, by * wZ * sY)     // contributes by * (wZ*z) to y_clip
        set(2, 2, -z_col2)           // to z_clip
        set(2, 3, -wZ)               // to w_clip

        // Column 3 (multiplies 1)
        set(3, 0, bx * w1)          // contributes bx * (w1*1) to x_clip
        set(3, 1, by * w1 * sY)     // contributes by * (w1*1) to y_clip
        set(3, 2, z_col3)           // to z_clip
        set(3, 3, w1)               // to w_clip

        return m
    }
}
