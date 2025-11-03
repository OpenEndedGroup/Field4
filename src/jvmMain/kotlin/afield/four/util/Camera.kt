package afield.four.util

import afield.four.Renderer
import afield.four.buffers.HasUniform
import afield.four.modules.fromContext
import afield.stdlib.set
import afield.stdlib.vec
import afield.stdlib.x
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.minus

class Camera(renderer: Renderer = fromContext()) {
    var at = vec(0, 0, -10)
    var up = vec(0, 1, 0)
    var target = vec(0, 0, 0)

    val forward: Vector3f
        get() = (target - at).normalize()

    var p = SuperProjection(renderer = renderer)
    var m = object : HasUniform<Matrix4f> {
        override fun evaluateUniform() = modelView()
    }

    fun modelView(): Matrix4f {
        var up = up.normalize()
        val side = (forward x up)
        up = (side x forward).normalize()

        val m = Matrix4f()

        m[0 * 4 + 0] = side.x
        m[1 * 4 + 0] = side.y
        m[2 * 4 + 0] = side.z

        m[0 * 4 + 1] = up.x
        m[1 * 4 + 1] = up.y
        m[2 * 4 + 1] = up.z

        m[0 * 4 + 2] = forward.x
        m[1 * 4 + 2] = forward.y
        m[2 * 4 + 2] = forward.z

        m[3 * 4 + 3] = 1f

        m.transpose()

        m.translate(at)

        return m
    }
}
