@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

/**
 * The one-way bridge to OFF, the plain-text polygon interchange format that Geomview and most
 * polyhedron tooling read.
 *
 * One function and no reader. Export exists so that a solid can be opened in something
 * that was not written here — an independent second opinion when a test disagrees with a picture — and
 * so that a bug report can carry the actual object. It is a diagnostic, not an architectural
 * dependency: nothing else in this package goes through it, and no part of the topology is shaped by
 * what OFF can express.
 *
 * The format itself is lossy for this library's purposes. Element identity, rank structure above 2,
 * attributes and provenance all disappear; what survives is coordinates and polygons.
 */

/**
 * Minimal polygonal OFF export for diagnostics and independent test inspection.
 *
 * Validates first and throws on any issue, so a malformed topology cannot be laundered into a
 * plausible-looking file; also throws without a realization, there being nothing to write. Vertices
 * are emitted in `topology.elements(0)` order and faces refer to *positions* in that sequence rather
 * than to [ElementId] values — the two coincide for the seeds and generally do not.
 *
 * Written with `Float.toString`, so the output does not depend on the ambient locale, and at
 * `Realization3`'s single precision rather than any wider format.
 *
 * A face with holes throws. OFF has no way to say "and this ring is subtracted", so the alternatives
 * were a file whose face is solid where the object's is not, or a refusal; a diagnostic that
 * disagrees with the object it is diagnosing, and does not say so, is worse than no diagnostic. Triangulate first and write
 * the mesh if you need the geometry out.
 */
fun PolyhedralObject.toOff(): String {
    val realization = realization ?: throw IllegalStateException("OFF export requires a 3D realization")
    topology.validate().throwIfInvalid()
    val holed = topology.elements(2).firstOrNull { topology.faceHasHoles(it.id) }
    if (holed != null) throw IllegalArgumentException(
        "face ${holed.id.value} has ${topology.faceHoles(holed.id).size} hole(s), which OFF cannot express",
    )
    val vertices = topology.elements(0)
    val vertexIndices = vertices.mapIndexed { index, vertex -> vertex.id to index }.toMap()
    val faces = topology.elements(2)
    return buildString {
        append("OFF\n")
        append(vertices.size).append(' ').append(faces.size).append(' ').append(topology.elements(1).size).append('\n')
        for (i in vertices.indices) {
            append(java.lang.Float.toString(realization.x(i))).append(' ')
                .append(java.lang.Float.toString(realization.y(i))).append(' ')
                .append(java.lang.Float.toString(realization.z(i))).append('\n')
        }
        for (face in faces) {
            val cycle = topology.faceCycle(face.id)
            append(cycle.size)
            for (vertex in cycle) append(' ').append(vertexIndices.getValue(ElementId(vertex)))
            append('\n')
        }
    }
}
