package afield.stdlib.polytope

/** Minimal polygonal OFF export for diagnostics and independent test inspection. */
fun PolyhedralObject.toOff(): String {
    val realization = realization ?: throw IllegalStateException("OFF export requires a 3D realization")
    topology.validate().throwIfInvalid()
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
