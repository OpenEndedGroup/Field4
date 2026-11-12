package afield.graphics.scene.reading

import afield.math.linalg.*

/** Sigma is measured in internal scene pixels, before the final headset resolve. */
class ReadingFrostSettings {
    var enabled = false
    var opacity = 1f
    var blurPixels = 5f
    var brightness = 1.1f
    var blackLevel = 0f // minimum red/blue light; reconstructed display green follows these channels
    var clipEpsilon = .002f // metres behind the surface; excludes its own ink
}

/** Immutable geometry/pose for one frame; settings remain live on the owning text frame. */
class ReadingFrostSurface
internal constructor(
    val frame: DiagramTextFrame,
    snapshot: DiagramFrame,
    val opacity: Float,
) {
    internal val geometry = frame.geometry(snapshot)
    internal val localToDiagram =
        Matrix4f()
            .translation(snapshot.textPoint(frame.span.run, 0f, 0f).add(snapshot.pose(frame.path).offset))
            .rotate(snapshot.textOrientation(frame.span.run))
            .rotateZ(frame.span.run.rotation)
}

/** Positive means behind the panel. The sign is chosen independently of the run's winding. */
internal fun readingFrostClipPlane(world: Matrix4f, eye: Vector3f, epsilon: Float): Vector4f {
    val origin = world.transformPosition(Vector3f())
    val normal = world.transformDirection(Vector3f(0f, 0f, 1f)).normalize()
    if (Vector3f(eye).sub(origin).dot(normal) < 0f) normal.negate()
    return Vector4f(-normal.x, -normal.y, -normal.z, normal.dot(origin) - epsilon)
}
