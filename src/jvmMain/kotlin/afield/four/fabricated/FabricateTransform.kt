package afield.four.fabricated

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

/** Local transform used by the fabricated scene graph. */
data class FabricateTransform(
    val translation: Vector3f = Vector3f(),
    val rotation: Quaternionf = Quaternionf(),
    val scale: Vector3f = Vector3f(1f, 1f, 1f)
) {
    fun toMatrix(): Matrix4f = Matrix4f()
        .translate(translation)
        .rotate(rotation)
        .scale(scale)

    fun set(other: FabricateTransform): FabricateTransform {
        translation.set(other.translation)
        rotation.set(other.rotation)
        scale.set(other.scale)
        return this
    }

    fun copyOf(): FabricateTransform = FabricateTransform(
        translation = Vector3f(translation),
        rotation = Quaternionf(rotation),
        scale = Vector3f(scale)
    )

    companion object {
        fun identity(): FabricateTransform = FabricateTransform()
    }
}
