// field_morse_smale: the discrete Morse–Smale complex of a scalar field on a regular 2D grid, behind
// a flat C ABI that afield.geometry.topology.morsesmale.MorseSmaleNative binds with java.lang.foreign.
//
// Discretisation. The grid is triangulated with the Freudenthal split: each cell is cut along its
// (x+1, y+1) diagonal. On that simplicial complex we build Forman's discrete gradient of the
// lower-star filtration, using ProcessLowerStars (Robins, Wood & Sheppard 2011), which is TTK's
// construction too. Critical cells are minima (vertices), saddles (edges) and maxima (triangles).
// Separatrices are V-paths. Ties are broken by simulation of simplicity: vertex a is below vertex b
// iff (f(a), index(a)) < (f(b), index(b)). That makes every result deterministic on plateaus. It
// also means a plateau's critical cells depend on the index order, which is a fact about this
// discretisation and not about the field.
//
// Cell identities. With N = width * height: vertex v = y * width + x has id v. Edge k of vertex v
// (k = 0 for (v, v+1), 1 for (v, v+width), 2 for (v, v+width+1)) has id N + 3v + k. Triangle t of
// the cell whose lower-left corner is v (t = 0 for (v, v+1, v+width+1), 1 for (v, v+width,
// v+width+1)) has id 4N + 2v + t. Ids are stable for a given grid size, so a critical point keeps
// its id across recomputation for as long as it stays on the same cell. A cell's value is f at its
// highest vertex.
//
// Ownership. fms_build returns a complex the caller owns and frees with fms_free. fms_simplify
// returns a view the caller owns and frees with fms_free_view. A view does not keep its complex
// alive, so free every view before freeing the complex. All output arrays are caller-allocated,
// sized by the matching *_count call. No call retains a caller pointer.
//
// Errors. A call that can fail takes `char* error, int error_capacity` and returns NULL or -1,
// leaving a message in `error`. No C++ exception crosses this boundary.

#ifndef FIELD_MORSE_SMALE_H
#define FIELD_MORSE_SMALE_H

#include <stdint.h>

#if defined(_WIN32)
#  define FMS_API __declspec(dllexport)
#else
#  define FMS_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Bumped whenever a signature or array layout changes. The Kotlin side refuses a mismatch.
#define FMS_ABI_VERSION 2

enum fms_critical_type { FMS_MINIMUM = 0, FMS_SADDLE = 1, FMS_MAXIMUM = 2 };
// A descending arc runs from a saddle down to a minimum; an ascending arc runs up to a maximum.
enum fms_arc_kind { FMS_DESCENDING = 0, FMS_ASCENDING = 1 };

typedef struct fms_complex fms_complex;
typedef struct fms_view fms_view;

FMS_API int32_t fms_abi_version(void);
FMS_API const char* fms_version_string(void);

// Builds the unsimplified complex and its whole cancellation hierarchy. Sample (x, y) is read from
// values[y * row_stride + x * pixel_stride], so one channel of an interleaved image can be passed
// directly. NaN samples are rejected.
FMS_API fms_complex* fms_build(const float* values, int32_t width, int32_t height,
                               int32_t pixel_stride, int32_t row_stride,
                               char* error, int32_t error_capacity);
FMS_API void fms_free(fms_complex* c);

// Wall-clock milliseconds for: critical points, separatrices, cancellation hierarchy, persistence
// pairs. out must hold 4 doubles.
FMS_API void fms_timings(const fms_complex* c, double* out);

// Every critical cell of the unsimplified complex: its cell id, type, value, position (vertex,
// edge midpoint or triangle centroid, in grid units) and cancelled_at, the persistence at which
// simplification removes it (+inf if none does). xy holds 2 floats per point.
FMS_API int32_t fms_critical_count(const fms_complex* c);
FMS_API void fms_critical_points(const fms_complex* c, int32_t* cell, int32_t* type,
                                 float* value, double* cancelled_at, float* xy);

// The cancellation hierarchy, in the order cancellations are applied: saddle cell, extremum
// cell, and persistence (made non-decreasing, so a threshold selects a prefix).
FMS_API int32_t fms_cancellation_count(const fms_complex* c);
FMS_API void fms_cancellations(const fms_complex* c, int32_t* saddle, int32_t* extremum, double* persistence);

// The persistence diagram of the lower-star filtration, computed by union-find independently of
// the gradient. Dimension 0 pairs a minimum (vertex) with the edge that merges it away. Dimension 1
// pairs an edge with the triangle that fills its cycle, found by union-find on the dual graph with
// one node for the outside of the grid. The global minimum has death = -1. Ids are cell ids.
FMS_API int32_t fms_pair_count(const fms_complex* c);
FMS_API void fms_pairs(const fms_complex* c, int32_t* birth, int32_t* death, int32_t* dimension, double* persistence);

// Applies every cancellation with persistence < threshold (0 gives the unsimplified complex).
// Replaying the stored hierarchy does not rebuild anything.
FMS_API fms_view* fms_simplify(const fms_complex* c, double threshold, char* error, int32_t error_capacity);
FMS_API void fms_free_view(fms_view* v);

FMS_API int32_t fms_view_node_count(const fms_view* v);
FMS_API void fms_view_nodes(const fms_view* v, int32_t* cell);

// Arcs: per arc its saddle cell, extremum cell, kind, and number of points on its path (saddle
// first, extremum last). fms_view_arc_paths writes every path's points back to back, as x, y floats
// in grid units.
//
// The outside. An ascending V-path can leave the grid through a boundary edge. The complex treats
// the outside as one extra maximum at +inf, with cell id -1, which makes the disc a sphere. Arcs
// that leave the grid have extremum -1, and maxima next to the boundary can be cancelled into the
// outside. It is never cancelled itself, and it is not listed among the critical points or nodes.
FMS_API int32_t fms_view_arc_count(const fms_view* v);
FMS_API int64_t fms_view_arc_path_total(const fms_view* v);
FMS_API void fms_view_arcs(const fms_view* v, int32_t* saddle, int32_t* extremum, int32_t* kind, int32_t* length);
FMS_API void fms_view_arc_paths(const fms_view* v, float* xy);

// Per grid vertex, the cell id of the surviving minimum whose basin (descending manifold) holds
// it, and of the surviving maximum whose peak (ascending manifold) holds the triangle above and to
// its right, which is clamped at the top and right edges. A peak is -1 where the gradient flows out
// of the grid (into the outside). Each array holds width*height.
FMS_API void fms_view_segmentation(const fms_view* v, int32_t* basin, int32_t* peak);

#ifdef __cplusplus
}
#endif

#endif
