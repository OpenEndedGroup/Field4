// solver_kernel.h
// Public header for the 3D constraint solver (Ceres + Emscripten)

#pragma once
#include <cstdint>

// ------------------------------------------------------------
// Opcodes (must match solver_kernel.cpp and your JS helpers)
// ------------------------------------------------------------
namespace op {
  enum : int {
    PLANAR = 0,            // [n, i0, i1, ..., in-1, w]
    ON_LINE = 1,           // [m, a, b, p2, ..., pm-1, w]  // line = AB, others on it
    DISTANCE = 2,          // [D, i, j, w]

    SAME_X  = 3,           // [n, i0, i1, ..., w]
    SAME_Y  = 4,           // [n, ...]
    SAME_Z  = 5,           // [n, ...]
    SAME_XY = 6,           // [n, ...]
    SAME_XZ = 7,           // [n, ...]
    SAME_YZ = 8,           // [n, ...]

    PERP_AT_ORIGIN = 9,    // [o, a, b, w] → (a-o) ⟂ (b-o)

    // Locks (to build-time pose)
    LOCK_X  = 20,          // [n, i0, ..., w]
    LOCK_Y  = 21,
    LOCK_Z  = 22,
    LOCK_XY = 23,
    LOCK_XZ = 24,
    LOCK_YZ = 25,

    // Priors (targets = incoming pose each solve; residuals installed on build)
    PRIOR_3 = 30,          // [n, i0, ..., w]  (accepts -1 meaning “all points”)
    PRIOR_X = 31,          // [n, i0, ..., w]
    PRIOR_Y = 32,          // [n, i0, ..., w]
    PRIOR_Z = 33,          // [n, i0, ..., w]

    // Distance variants
    MIN_DISTANCE   = 34,   // [Dmin, i, j, w]  residual = max(0, Dmin - d)
    EQUAL_DISTANCE = 35,   // [a, b, c, d, w]  residual = |ab| - |cd|
    MAX_DISTANCE   = 36,   // [Dmax, i, j, w]  residual = max(0, d - Dmax)

    // Inequality
    NO_Z_NEG = 37,         // [n, i0, ..., w]  (accepts -1 meaning “all points”)

    // JVM/native extensions
    DOT_AT_ORIGIN = 40,       // [o, a, b, target, normalized, w]
    VECTOR_DOT = 41,          // [a0, a1, b0, b1, target, normalized, w]
    POINT_ON_PLANE = 42,      // [p, a, b, c, offset, w]
    LINE_PARALLEL = 43,       // [a0, a1, b0, b1, w]
    LINE_ANGLE = 44,          // [a0, a1, b0, b1, target_cos, w]
    DIHEDRAL_ANGLE = 45,      // [a, b, c, d, target_cos, w]
    EQUAL_EDGE_VECTOR = 46,   // [a, b, c, d, w]
    MIRROR_ACROSS_PLANE = 47, // [p, q, a, b, c, w]
    PANEL_AREA = 48,          // [a, b, c, d, target_area, w]
    POINT_IN_HALFSPACE = 49,  // [p, a, b, c, min_signed_distance, w]
    LINE_DIRECTION = 50,      // [a, b, dx, dy, dz, w]
    PLANE_NORMAL_DIRECTION = 51, // [a, b, c, nx, ny, nz, target_cos, w]
    LINE_TOWARD_POINT = 52,   // [a, b, vp, w]

    // Solver-directed pseudo-ops and global (all-point) constraints
    SET_LOSS = 60,              // [kind, scale]  0=none(L2) 1=Huber 2=SoftLOne 3=Cauchy
    EDGE_TANGENCY = 61,         // [a, b, radius, w]
    TANGENCY_CENTROID = 62,     // [nEdges, a0,b0, ..., w]
    CENTROID = 63,              // [n, i0..., tx,ty,tz, w]   (n<0 means "all points")
    RADIUS_ABOUT_CENTROID = 64  // [n, i0..., radius, w]     (n<0 means "all points")
  };
}

// ------------------------------------------------------------
// C ABI (exported to Wasm)
// Notes:
//  - points_* are length 3*N (xyzxyz…).
//  - program is a packed stream of doubles (opcodes/counts/indices are
//    encoded as doubles; kernel casts to int where needed).
//  - constraints_hash is your stable u32 for caching the graph.
//  - max_iters >= 1.
//  - *_ex and *_metrics allow dynamic per-axis prior weights each frame.
//    If you don’t need that, pass nullptr.
//  - *_metrics can optionally fill total and per-group costs.
// ------------------------------------------------------------
extern "C" {

// JVM/FFM-oriented handle API. A handle owns one compiled Ceres problem. Create
// it with the initial pose so build-time locks can capture their targets; then
// solve repeatedly with updated poses and optional dynamic prior weights.
const char* ken_ceres_jvm_abi_version();

void* ken_ceres_solver_create(const double* points_in, int num_points,
                              const double* program, int program_len,
                              uint32_t constraints_hash);

void ken_ceres_solver_destroy(void* handle);

// "" when the program parsed completely; otherwise a description of where parsing stopped. A
// non-empty error means residual blocks are missing and a solve answers a different question.
const char* ken_ceres_solver_last_error(void* handle);

int ken_ceres_solver_solve(void* handle,
                           const double* points_in, int num_points,
                           int max_iters,
                           const double* prior_w_xyz, // nullable (len = 3*N)
                           double* points_out,
                           double* total_cost_out,    // nullable
                           double* group_costs_out,   // nullable
                           int group_costs_cap,
                           int* out_group_count);     // nullable

// As ken_ceres_solver_solve, plus solver options in and a solver summary out.
//
// options (flat; a shorter array leaves later settings at their defaults):
//   [0] max_iterations
//   [1] linear solver: 0 DENSE_QR, 1 DENSE_NORMAL_CHOLESKY, 2 SPARSE_NORMAL_CHOLESKY,
//                      3 CGNR, 4 DENSE_SCHUR, 5 ITERATIVE_SCHUR
//   [2] num_threads
//   [3] function_tolerance
//   [4] gradient_tolerance
//   [5] parameter_tolerance
//   [6] use_nonmonotonic_steps (0/1)
//   [7] minimizer_progress_to_stdout (0/1)
//
// summary_out (flat, up to 12 slots):
//   [0] initial_cost           [1] final_cost
//   [2] successful steps       [3] unsuccessful steps
//   [4] iterations             [5] ceres::TerminationType
//   [6] total seconds          [7] num_residuals
//   [8] num_effective_parameters  [9] num_parameter_blocks
//  [10] num_residual_blocks   [11] ceres::LinearSolverType actually used
int ken_ceres_solver_solve2(void* handle,
                            const double* points_in, int num_points,
                            const double* options, int options_len,
                            const double* prior_w_xyz, // nullable (len = 3*N)
                            double* points_out,
                            double* total_cost_out,    // nullable
                            double* group_costs_out,   // nullable
                            int group_costs_cap,
                            int* out_group_count,      // nullable
                            double* summary_out,       // nullable
                            int summary_cap);

// Simple solve (no dynamic prior weights, no metrics)
int solve_constraints(const double* points_in, int num_points,
                      const double* program, int program_len,
                      uint32_t constraints_hash, int max_iters,
                      double* points_out);

// Extended solve with dynamic per-axis prior weights.
// prior_w_xyz is length 3*num_points (xyz per point) or nullptr to keep
// the weights that were installed at build-time by PRIOR_* opcodes.
int solve_constraints_ex(const double* points_in, int num_points,
                         const double* program, int program_len,
                         uint32_t constraints_hash, int max_iters,
                         const double* prior_w_xyz,   // nullable (len = 3*N)
                         double* points_out);

// Solve + metrics (total cost + per-constraint-group costs).
// If group_costs_out != nullptr, provide group_costs_cap capacity.
// The function writes up to that many entries and sets out_group_count
// (if provided). Costs are 0.5 * sum(residual_i^2) with loss applied,
// matching Ceres' Evaluate semantics.
int solve_constraints_metrics(const double* points_in, int num_points,
                              const double* program, int program_len,
                              uint32_t constraints_hash, int max_iters,
                              const double* prior_w_xyz,   // nullable (len = 3*N)
                              double* points_out,
                              double* total_cost_out,      // nullable
                              double* group_costs_out,     // nullable
                              int      group_costs_cap,
                              int*     out_group_count);   // nullable
} // extern "C"
