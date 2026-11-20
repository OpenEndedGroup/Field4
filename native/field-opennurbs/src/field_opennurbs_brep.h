// The NURBS half of the openNURBS wrapper: the exact surfaces and trim loops of every Brep in a
// .3dm file, for a tessellator on the JVM side (`afield.geometry.rhino.BrepTessellator`).
//
// openNURBS reads Breps but cannot mesh them, so `field_opennurbs.h` hands out only the render
// meshes Rhino cached. This file hands out what those meshes were made from. It opens the file on
// its own handle (independent of `field_opennurbs_open`), and for each top-level Brep, and each
// extrusion through its Brep form, packs one flat array of doubles:
//
//     faceCount
//     per face:
//       hasRenderMesh            1 when Rhino cached a usable render mesh for this face, else 0
//       reversed                 1 when the face's normal is opposite the surface's (ON_BrepFace::m_bRev)
//       degreeU degreeV cvCountU cvCountV
//       knotsU[degreeU + cvCountU + 1]   full clamped knot vectors (openNURBS's end knots restored)
//       knotsV[degreeV + cvCountV + 1]
//       cvs[cvCountU * cvCountV * 4]     homogeneous (x*w, y*w, z*w, w), u-major: cv(i, j) at (i * cvCountV + j) * 4
//       loopCount
//       per loop:
//         loopType                1 outer, 2 inner (ON_BrepLoop::TYPE)
//         pointCount
//         points[pointCount * 2]  the loop's trims, sampled, in the NURBS surface's own parameters;
//                                 the loop closes from the last point back to the first
//
// Block members are not included. SubDs: see `field_opennurbs_subds_count`.

#pragma once

#include "field_opennurbs_export.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Read the Breps and SubDs of a .3dm file. Status codes as `field_opennurbs_open`; null-safe close.
 *
 * @param subdLevel how many times to Catmull-Clark subdivide each SubD before taking its limit
 *   points (0 to 6); negative skips SubDs.
 */
FIELD_OPENNURBS_API int field_opennurbs_breps_open(const char* utf8Path, int subdLevel, void** handleOut,
    char* message, int messageCapacity);
FIELD_OPENNURBS_API void field_opennurbs_breps_close(void* handle);

/** How many Breps were packed. */
FIELD_OPENNURBS_API int field_opennurbs_breps_count(void* handle);

/** The top-level object id of Brep `index` — the same string as `FIELD_OPENNURBS_OBJECT_ID`. */
FIELD_OPENNURBS_API const char* field_opennurbs_breps_id(void* handle, int index);

/** The packed array's length in doubles, and the array itself (owned by the handle). */
FIELD_OPENNURBS_API int field_opennurbs_breps_length(void* handle, int index);
FIELD_OPENNURBS_API const double* field_opennurbs_breps_data(void* handle, int index);

/**
 * The SubDs, by the same pattern: id, and a packed array of triangle corners, `x y z nx ny nz` each,
 * three per triangle. Made with openNURBS's own subdivision (`ON_SubD::GlobalSubdivide`) and limit
 * points (`ON_SubDVertex::SurfacePoint`, `SurfaceNormal`): a mesh inscribed in the limit surface.
 */
FIELD_OPENNURBS_API int field_opennurbs_subds_count(void* handle);
FIELD_OPENNURBS_API const char* field_opennurbs_subds_id(void* handle, int index);
FIELD_OPENNURBS_API int field_opennurbs_subds_length(void* handle, int index);
FIELD_OPENNURBS_API const double* field_opennurbs_subds_data(void* handle, int index);

/**
 * Write a "Save Small" fixture: Breps with exact surfaces and trims and no render meshes — a sphere
 * of radius 10, a capped cylinder (radius 5, height 20), and a 20 x 20 planar square with a
 * circular hole of radius 5 — plus a box with and one without cached meshes, for comparison, and
 * a smooth and a creased SubD box (2 x 2 x 2 faces, 10 across).
 */
FIELD_OPENNURBS_API int field_opennurbs_write_brep_fixture(const char* utf8Path, char* message, int messageCapacity);

#ifdef __cplusplus
}
#endif
