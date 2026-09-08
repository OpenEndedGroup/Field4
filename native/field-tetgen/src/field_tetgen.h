// A flat C ABI over TetGen, for java.lang.foreign.
//
// One function does the whole job: hand it a closed triangle surface — the thing
// `afield.manifold.Solid.toTriMesh()` already produces — and get back a conforming tetrahedral mesh
// whose boundary *is* that surface, with the input's sharp edges preserved exactly.
//
// Why a wrapper rather than binding TetGen directly: `tetrahedralize` takes C++ objects with
// constructors, owns its output through `tetgenio`'s destructor, and signals failure by throwing.
// None of those cross FFM. This turns all three into plain arrays and an int.

#pragma once

#include "field_tetgen_export.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Status codes. Anything non-zero leaves the result arrays null. */
enum {
    FIELD_TETGEN_OK = 0,
    FIELD_TETGEN_BAD_INPUT = 1,      /**< empty or malformed surface, or a nonsensical option */
    FIELD_TETGEN_FAILED = 2,         /**< TetGen refused the input; see the message */
    FIELD_TETGEN_OUT_OF_MEMORY = 3,
};

/*
 * The result travels through out-parameters rather than a struct on purpose. A struct means the
 * Kotlin side has to reproduce this compiler's field offsets and tail padding exactly, and getting
 * that wrong reads plausible-looking garbage rather than failing. Plain pointers have no layout to
 * agree about.
 *
 * `nodes` is `nodeCount` xyz triples; `cells` is `cellCount` zero-based node index quadruples,
 * which is the convention the Kratos worker already uses on the wire. Both are owned by the
 * library until `field_tetgen_free`.
 */

/**
 * Tetrahedralize a closed triangle surface.
 *
 * @param pointCount    vertices in the surface.
 * @param points        `3 * pointCount` doubles, xyz.
 * @param triangleCount triangles in the surface.
 * @param triangles     `3 * triangleCount` zero-based vertex indices.
 * @param qualityRatio  TetGen's radius-edge bound (`-q`). 1.414 is its default; 1.2 is what the
 *                      measurement in dev_docs/TETGEN.md settled on, because it removes the last
 *                      poor element at no measurable cost. Values below ~1.1 can fail to terminate.
 * @param maxVolume     upper bound on element volume (`-a`), or <= 0 for none. A regular tetrahedron
 *                      of edge h has volume h^3/(6*sqrt(2)), which is how a target edge length is
 *                      spelled here.
 * @param preserveSurface non-zero adds `-Y`, forbidding Steiner points on the boundary. It helps a
 *                      finely tessellated convex surface and hurts a boolean result badly; the
 *                      measurement has the numbers. Default off.
 * @param nodeCountOut  receives the vertex count; 0 on failure.
 * @param nodesOut      receives a malloc'd `3 * nodeCount` array; null on failure.
 * @param cellCountOut  receives the tetrahedron count; 0 on failure.
 * @param cellsOut      receives a malloc'd `4 * cellCount` array; null on failure.
 * @param message       receives a NUL-terminated explanation on failure; may be null.
 * @param messageCapacity bytes available at `message`.
 * @return one of the FIELD_TETGEN_* codes.
 */
FIELD_TETGEN_API int field_tetgen_tetrahedralize(
    int pointCount, const double* points,
    int triangleCount, const int* triangles,
    double qualityRatio, double maxVolume, int preserveSurface,
    int* nodeCountOut, double** nodesOut, int* cellCountOut, int** cellsOut,
    char* message, int messageCapacity);

/** Release arrays handed out by `field_tetgen_tetrahedralize`. Null-safe. */
FIELD_TETGEN_API void field_tetgen_free(double* nodes, int* cells);

/** TetGen's version string, so a mismatch is reportable rather than mysterious. */
FIELD_TETGEN_API const char* field_tetgen_version(void);

#ifdef __cplusplus
}
#endif
