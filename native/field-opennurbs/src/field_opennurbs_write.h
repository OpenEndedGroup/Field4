// Writing Rhino .3dm files: a flat model-builder C ABI over openNURBS's ONX_Model, for
// java.lang.foreign, the writing half of field_opennurbs.h.
//
// `field_opennurbs_writer_new` makes an empty model; `_add_layer` and the `_add_*` geometry calls
// fill it, each object taking the attributes last given to `_attributes`; `_write` saves it, and
// `_writer_free` releases it. Every argument is an int, a double, a UTF-8 string or a pointer to a
// plain array the caller owns and may free as soon as the call returns. No C++ object or exception
// crosses the boundary.
//
// Colours are 0xAARRGGBB with A = 255 for opaque, as in field_opennurbs.h.

#pragma once

#include "field_opennurbs_export.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * An empty model in [unitSystem] — ON::LengthUnitSystem as an int, the numbers
 * `FIELD_OPENNURBS_MODEL_UNIT_SYSTEM` reports (2 mm, 3 cm, 4 m, 8 in, 9 ft; 0 none). Null on failure.
 */
FIELD_OPENNURBS_API void* field_opennurbs_writer_new(int unitSystem);

/** Release a writer. Null-safe. */
FIELD_OPENNURBS_API void field_opennurbs_writer_free(void* writer);

/**
 * Add a layer named [utf8Name], coloured [argb], inside layer [parent] (an index this writer
 * returned, or -1 for the top level), switched on or off. Returns its index, or -1 on failure.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_layer(void* writer, const char* utf8Name, int argb, int parent,
    int visible);

/**
 * The attributes of every object added after this call: its [layer] (-1 for a "Default" layer made
 * when first needed), its colour ([hasColor] 0 means "by layer"), its name ([utf8Name], may be null)
 * and whether it is shown.
 */
FIELD_OPENNURBS_API void field_opennurbs_writer_attributes(void* writer, int layer, int hasColor, int argb,
    const char* utf8Name, int visible);

/**
 * A mesh of [vertexCount] xyz vertices and [triangleCount] triangles (3 ints each). [normals]
 * (3 floats a vertex) and [vertexColors] (one 0xAARRGGBB a vertex) may be null. Returns 0 or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_mesh(void* writer, const double* xyz, int vertexCount,
    const int* triangles, int triangleCount, const float* normals, const int* vertexColors);

/**
 * A NURBS curve of [degree] with [cvCount] control points (xyz), [weights] (null for a
 * non-rational curve; otherwise one a point, the points given unweighted) and cvCount + degree - 1
 * knots in openNURBS's convention (no superfluous end knots). Returns 0 or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_nurbs_curve(void* writer, int degree, int cvCount,
    const double* xyz, const double* weights, const double* knots);

/**
 * A path in the reader's form — FIELD_OPENNURBS_OP_MOVE / _LINE / _CUBIC, each consuming 1, 1 or 3
 * xyz points. Every subpath becomes one curve: a degree-1 NURBS polyline when it is all lines,
 * otherwise a polycurve of degree-1 and degree-3 Bézier NURBS spans, with the points exactly as
 * given. Returns how many curves it added, or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_path(void* writer, const double* xyz, int pointCount,
    const int* ops, int opCount);

/** One point ([count] 1) or a point cloud. Returns 0 or -1. */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_points(void* writer, const double* xyz, int count);

/**
 * Text: [utf8Text] on the plane at [origin] spanned by [xAxis] and [yAxis] (xyz each), with capital
 * height [height] in model units. Returns 0 or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_text(void* writer, const char* utf8Text, const double* origin,
    const double* xAxis, const double* yAxis, double height);

/**
 * A Brep from [length] packed doubles (see below). openNURBS builds the topology: each trim's 3D edge
 * (an exact iso-curve or image under a planar parallelogram surface where it can, otherwise a dense
 * polyline within the trim's surface image), shared vertices, one shared edge wherever two trims
 * trace the same 3D curve (seams included), and a singular trim wherever a trim's image collapses to
 * a point (a sphere's poles). Knots are in openNURBS's convention (no superfluous end knots):
 *
 *     faceCount
 *     per face:
 *       reversed                      1 when the face's normal is opposite the surface's
 *       degreeU degreeV cvCountU cvCountV
 *       knotsU[cvCountU + degreeU - 1] knotsV[cvCountV + degreeV - 1]
 *       cvs[cvCountU * cvCountV * 4]  homogeneous (x*w, y*w, z*w, w), u-major
 *       loopCount
 *       per loop:
 *         loopType                    1 outer, 2 inner
 *         trimCount
 *         per trim (a 2D NURBS curve in the surface's parameters, in loop order):
 *           degree cvCount
 *           knots[cvCount + degree - 1]
 *           cvs[cvCount * 3]          homogeneous (u*w, v*w, w)
 *       meshVertexCount               0 for none; otherwise a render mesh cached on the face:
 *       xyz[meshVertexCount * 3] triangleCount indices[triangleCount * 3]
 *
 * Returns 0, or -1 with the reason (openNURBS's validity log) in [message].
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_brep(void* writer, const double* packed, int length,
    char* message, int messageCapacity);

/**
 * An untrimmed NURBS surface, written as Rhino writes one: a one-face Brep. Same surface layout as a
 * face of `_add_brep` without `reversed`, loops or mesh. Returns 0 or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_surface(void* writer, int degreeU, int degreeV, int cvCountU,
    int cvCountV, const double* knotsU, const double* knotsV, const double* cvs);

/**
 * A SubD from its control net: [vertexCount] xyz points, [faceCount] faces whose sizes are in
 * [faceSizes] and vertex indices, in order, in [faceVertices]; [creaseCount] edges (vertex index
 * pairs in [creases]) tagged as creases. Tags are completed by openNURBS (a vertex where three
 * creases meet becomes a corner). Returns 0 or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_subd(void* writer, const double* xyz, int vertexCount,
    const int* faceSizes, int faceCount, const int* faceVertices, const int* creases, int creaseCount);

/**
 * A solid hatch whose boundary is a path in the `_add_path` form: its first subpath is the outer
 * loop, the rest are holes, all on the plane z = (the first point's z). Returns 0 or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_hatch(void* writer, const double* xyz, int pointCount,
    const int* ops, int opCount);

/**
 * Start a block definition named [utf8Name]: objects added until `_end_block` become its members
 * rather than top-level objects. Returns 0, or -1 when a block is already open.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_begin_block(void* writer, const char* utf8Name);

/** Close the open block definition. Returns its index for `_add_instance`, or -1. */
FIELD_OPENNURBS_API int field_opennurbs_writer_end_block(void* writer);

/**
 * Place block [block] with the row-major 4 x 4 transform [xform16] (with the current attributes).
 * Returns 0 or -1.
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_add_instance(void* writer, int block, const double* xform16);

/** How many objects the writer holds. */
FIELD_OPENNURBS_API int field_opennurbs_writer_object_count(void* writer);

/**
 * Save to [utf8Path] as a Rhino [version] file (2 to 8; 0 for the newest openNURBS writes). Returns a
 * FIELD_OPENNURBS_* status from field_opennurbs.h, with openNURBS's complaint in [message].
 */
FIELD_OPENNURBS_API int field_opennurbs_writer_write(void* writer, const char* utf8Path, int version,
    char* message, int messageCapacity);

#ifdef __cplusplus
}
#endif
