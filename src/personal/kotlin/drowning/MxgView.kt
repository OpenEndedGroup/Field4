package drowning

import afield.stdlib.linalg.Matrix4f

/** Letterboxed pixel-space projection, with maxnet's y-down image coordinates kept upright. */
fun MxgFrame.projection(viewportWidth: Int, viewportHeight: Int): Matrix4f {
    val viewAspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1).toFloat()
    val sourceAspect = width.toFloat() / height.toFloat()
    var left = 0f
    var right = width.toFloat()
    var top = 0f
    var bottom = height.toFloat()
    if (viewAspect > sourceAspect) {
        val fittedWidth = height * viewAspect
        left = (width - fittedWidth) * 0.5f
        right = left + fittedWidth
    } else {
        val fittedHeight = width / viewAspect
        top = (height - fittedHeight) * 0.5f
        bottom = top + fittedHeight
    }
    return Matrix4f().ortho(left, right, bottom, top, -1f, 1f, true)
}

/**
 * The live Field half of [MxgSource]. The package reads/decodes/paces and the box owns Vulkan.
 *
 * Use one renderer parent (the same `Window`/`Renderer` box as `light_one`) and one child box with
 * [MxgShaders.VERTEX]/[MxgShaders.FRAGMENT] in its editable shader properties:
 *
 * ```kotlin
 * import drowning.*
 *
 * var renderer = fromContext<Renderer>()
 * var window = fromContext<Window>()
 * var module = moduleHere {
 *     primitiveType = triangles
 *     depthTest = false
 *     depthWrite = false
 *     blend = true
 * }
 * var contours = Mesh(); var edges = Mesh(); var nodes = Mesh()
 * module += "contours" to contours; module += "edges" to edges; module += "nodes" to nodes
 * renderer += module
 *
 * var path = System.getProperty("user.home") + "/temp/maxnet/output.mxg"
 * @exported var mxg = MxgSource.open(path).start()
 * @exported var look = MxgLook()
 *
 * // Profiled once at open: exact fixed capacities for every complete frame in this file.
 * var nodeBuffer = defaultBuffer {
 *     perFrame = true; size = mxg.capacity.nodeVec4s.vec4s; streamWrites()
 * }.buffer
 * var segmentBuffer = defaultBuffer {
 *     perFrame = true; size = mxg.capacity.segmentVec4s.vec4s; streamWrites()
 * }.buffer
 *
 * for (mesh in listOf(contours, edges, nodes)) {
 *     mesh.nodes = { nodeBuffer }
 *     mesh.segments = { segmentBuffer }
 * }
 * contours.drawMode = { 0u }; edges.drawMode = { 1u }; nodes.drawMode = { 2u }
 *
 * var seen = -1L
 * loop {
 *     var snapshot = mxg.latest
 *     if (snapshot != null && snapshot.serial != seen) {
 *         seen = snapshot.serial
 *         var frame = snapshot.frame
 *         frame.writeInto(nodes = nodeBuffer, segments = segmentBuffer)
 *     }
 *     var frame = snapshot?.frame
 *     if (frame != null) {
 *         contours.resize(if (look.drawContours) frame.segmentCount * 6 else 0)
 *         edges.resize(if (look.drawEdges) (frame.nodeCount - 1).coerceAtLeast(0) * 6 else 0)
 *         nodes.resize(if (look.drawNodes) frame.nodeCount * 6 else 0)
 *         var projection = frame.projection(window.width, window.height)
 *         for (mesh in listOf(contours, edges, nodes)) {
 *             mesh.p = { projection }; mesh.nodeCount = { frame.nodeCount.toUInt() }
 *             mesh.viewport = { vec(window.width.toFloat(), window.height.toFloat()) }
 *             mesh.contourWidthPixels = { look.contourWidthPixels }
 *             mesh.edgeWidthPixels = { look.edgeWidthPixels }
 *             mesh.nodeRadiusPixels = { look.nodeRadiusPixels }
 *             mesh.maximumRadiusPixels = { look.maximumRadiusPixels }
 *             mesh.contourAlpha = { look.contourAlpha }; mesh.edgeAlpha = { look.edgeAlpha }
 *             mesh.nodeAlpha = { look.nodeAlpha }; mesh.holesAlpha = { look.holesAlpha }
 *             mesh.dynamicsGain = { look.dynamicsGain }; mesh.levelFloor = { look.levelFloor }
 *             mesh.levelCeiling = { look.levelCeiling }
 *             mesh.quantizationLevels = { frame.quantizationLevels.toFloat() }
 *         }
 *     }
 *     mxg.failure?.let { throw it }
 *     true
 * }
 * ```
 *
 * The source is deliberately not a `VulkanBuffer` owner. That keeps personal-layer reloads and file
 * decoding off the renderer, and makes the two uploads visible in the only place they are safe: the
 * frame loop. Call `mxg.close()` when permanently stopping the box.
 */
object MxgView
