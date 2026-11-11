package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.geometry.path.tcbTo
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f

enum class DiagramConnectionLength { STABILIZE, MINIMIZE }

/** Authored dynamics are shared by players; evolving node positions belong to each player separately. */
class DiagramConnection internal constructor(
    val path: DiagramPath,
    val from: DiagramAttachment,
    val to: DiagramAttachment,
    val interiorNodes: Int,
) {
    /** Approximate settling time in seconds. Small is brisk; large is a slowly following thread. */
    var responseSeconds = .5f
    /** 1 is critically damped toward the layout target; lower values allow ringing/overshoot. */
    var dampingRatio = 1f
    var layoutStrength = 1f // zero disables attraction to the endpoint-relative layout
    var lengthMode = DiagramConnectionLength.STABILIZE
    var lengthStiffness = 30f // adjacent-node distance attraction, seconds^-2
    var lengthScale = 1f // multiplies the layout-derived or explicitly chosen rest length
    var restLength: Float? = null // total metres; null follows the current bowed endpoint layout
    var curvatureStiffness = 12f // negative gradient of squared discrete curvature, seconds^-2
    val bow = Vector3f(0f, -.06f, 0f) // target displacement at midspan, metres in diagram axes
    var tension = 0f // Kochanek–Bartels: 0 gives Catmull–Rom, 1 flattens tangents
    var continuity = 0f
    var bias = 0f

    var width: Float
        get() = path.width
        set(value) { path.width = value }
    var color: Vector4f
        get() = path.color
        set(value) { path.color = value }
    var arrow: Float
        get() = path.arrow
        set(value) { path.arrow = value }
    fun dashed(period: Float = .03f, duty: Float = .55f, phase: Float = 0f) = path.dashed(period, duty, phase)

    /** Copies of current nodes, including endpoints constrained against this frame's text poses. */
    fun nodes(frame: DiagramFrame): List<Vector3f> = frame.connectionNodes(this)

    internal fun validate() {
        require(responseSeconds.isFinite() && responseSeconds in .05f..60f)
        require(dampingRatio.isFinite() && dampingRatio in 0f..4f)
        require(layoutStrength.isFinite() && layoutStrength in 0f..1f)
        require(lengthStiffness.isFinite() && lengthStiffness in 0f..1000f)
        require(lengthScale.isFinite() && lengthScale in 0f..10f)
        restLength?.let { require(it.isFinite() && it >= 0f) }
        require(curvatureStiffness.isFinite() && curvatureStiffness in 0f..1000f)
        require(bow.isFinite())
        require(listOf(tension, continuity, bias).all { it.isFinite() && it in -1f..1f })
    }
}

/** Connection nodes affect the line only; there is no unrequested reaction force on the words. */
fun ReadingDiagramBuilder.connection(
    id: String,
    from: DiagramAttachment,
    to: DiagramAttachment,
    interiorNodes: Int = 6,
    block: DiagramConnection.() -> Unit = {},
): DiagramConnection {
    require(interiorNodes in 1..64)
    lateinit var connection: DiagramConnection
    val line = path(id, shape = { connectionCurve(connection.nodes(this), connection) })
    require(from.owner === line.owner && to.owner === line.owner) { "Connection endpoint belongs to another diagram" }
    connection = DiagramConnection(line, from, to, interiorNodes).apply(block)
    connection.validate()
    line.connection = connection
    return connection
}

/** Fixed-anchor convenience: every pre-existing DiagramAnchor is usable without wrapping it. */
fun ReadingDiagramBuilder.connection(
    id: String,
    from: DiagramAnchor,
    to: DiagramAnchor,
    interiorNodes: Int = 6,
    block: DiagramConnection.() -> Unit = {},
) = connection(id, from.attachment(), to.attachment(), interiorNodes, block)

/** Reuse Field's incremental Kochanek–Bartels spline, including endpoint handling. */
internal fun connectionCurve(points: List<Vector3f>, connection: DiagramConnection): FLine {
    connection.validate()
    return FLine().apply {
        for (p in points) tcbTo(p.x, p.y, p.z, connection.tension, connection.continuity, connection.bias)
    }
}

/** Negative gradient of E = .5 Σ |p[i-1] - 2p[i] + p[i+1]|², with the endpoints held. */
internal fun connectionCurvatureForces(points: List<Vector3f>): List<Vector3f> {
    fun difference(i: Int) =
        if (i in 1 until points.lastIndex) Vector3f(points[i - 1]).fma(-2f, points[i]).add(points[i + 1])
        else Vector3f()
    return List(points.size) { i ->
        if (i == 0 || i == points.lastIndex) Vector3f()
        else difference(i).mul(2f).sub(difference(i - 1)).sub(difference(i + 1))
    }
}
