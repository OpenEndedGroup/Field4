// field_polyvector: vectorization of line drawings via polyvector fields (Bessmeltsev & Solomon,
// ACM TOG 2019), headless, behind a flat C ABI that afield.geometry.vectorize.polyvector.PolyvectorNative
// binds with java.lang.foreign.
//
// The algorithm is the authors' MIT-licensed implementation (github.com/bmpix/PolyVectorization),
// built from a pinned commit with the patches listed in tools/build_native_geometry.py. This file and
// field_polyvector.cpp split its single main() into two stages so each can be inspected and re-run:
//
//   fpv_field   evidence image -> ink mask, per-pixel line weight, and a polyvector (frame) field:
//               two unsigned directions at every ink pixel, found by a linear solve that aligns them
//               with the image gradient's tangent and keeps them smooth. Singular points of the field
//               are found and the solve repeated with them down-weighted, as the reference does.
//   fpv_trace   field -> curves: streamlines traced along the field, grouped into an "almost Reeb
//               graph", loops contracted, short branches pruned, junctions resolved, and the result
//               embedded and smoothed as polylines.
//
// A traced result can be recomputed with different tracing parameters without recomputing the field.
//
// Coordinates are pixels, x to the right and y down, at pixel centres (+0.5 already applied).
//
// Threading. The reference keeps state in globals, so every call into this library is serialised
// by one process-wide lock. Calls from several threads are safe but do not run in parallel.
//
// Ownership. fpv_field returns a handle the caller frees with fpv_free. Output arrays are
// caller-allocated and sized by the matching count call. Errors: NULL or a negative status, with a
// message in `error`; no C++ exception crosses this boundary.

#ifndef FIELD_POLYVECTOR_H
#define FIELD_POLYVECTOR_H

#include <stdint.h>

#if defined(_WIN32)
#  define FPV_API __declspec(dllexport)
#else
#  define FPV_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define FPV_ABI_VERSION 1

typedef struct fpv_result fpv_result;

typedef struct fpv_field_params {
    double ink_threshold;    // 0..1: evidence above this is ink (reference: 90/255)
    double regularizer;      // frame-field orthogonality weight; larger gives sharper junctions (0.1)
    double smoothness;       // frame-field smoothness weight; raise only for very noisy input (50)
    int32_t repair_passes;   // passes filling single-pixel holes in the ink mask (3)
} fpv_field_params;

typedef struct fpv_trace_params {
    double prune_ratio;          // how readily short side branches are removed, 0..1 (0.75)
    int32_t max_loop_pixels;     // holes with at most this many background pixels are closed (4)
    double simplify_tolerance;   // final polyline simplification, pixels (0.01)
    int32_t smooth;              // 1: smooth the final curves as the reference does
} fpv_trace_params;

FPV_API int32_t fpv_abi_version(void);
FPV_API const char* fpv_version_string(void);
FPV_API void fpv_default_field_params(fpv_field_params* out);
FPV_API void fpv_default_trace_params(fpv_trace_params* out);

// evidence: width * height floats, row-major, 1 = ink, 0 = paper (a dark-on-light drawing is
// 1 - luminance). Builds the mask, weights and frame field.
FPV_API fpv_result* fpv_field(const float* evidence, int32_t width, int32_t height, const fpv_field_params* params,
                              char* error, int32_t error_capacity);
FPV_API void fpv_free(fpv_result* r);

// Traces curves on the field. Replaces any earlier trace of this result.
FPV_API int32_t fpv_trace(fpv_result* r, const fpv_trace_params* params, char* error, int32_t error_capacity);

// Field outputs (available after fpv_field). mask: width*height bytes (0/1). weight: width*height
// floats, the reference's per-pixel confidence that one direction dominates. directions: 4 floats
// per pixel (x0, y0, x1, y1), the two field directions, unit length, zero off the ink.
FPV_API void fpv_field_maps(const fpv_result* r, uint8_t* mask, float* weight, float* directions);
FPV_API int32_t fpv_component_count(const fpv_result* r);
FPV_API int32_t fpv_singularity_count(const fpv_result* r);
FPV_API void fpv_singularities(const fpv_result* r, float* xy);

// Polylines: kind 0 = streamlines traced on the field (before topology processing), kind 1 = the
// final curves. Counts, per-curve point counts, then all points as x, y floats back to back.
FPV_API int32_t fpv_curve_count(const fpv_result* r, int32_t kind);
FPV_API int64_t fpv_point_total(const fpv_result* r, int32_t kind);
FPV_API void fpv_curves(const fpv_result* r, int32_t kind, int32_t* lengths, float* xy);

// Milliseconds: field (gradient, weights, solves, singularity passes), trace (tracing, graph,
// topology, embedding, smoothing).
FPV_API void fpv_timings(const fpv_result* r, double* out2);

// The reference can emit non-finite points on some inputs (crisp synthetic strokes, mostly). The
// final curves are split at any such point and the point dropped; this says how many were dropped
// by the last fpv_trace, so a caller can tell a clean result from a repaired one.
FPV_API int32_t fpv_dropped_points(const fpv_result* r);

#ifdef __cplusplus
}
#endif

#endif
