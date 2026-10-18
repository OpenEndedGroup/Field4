// field_principal_curves: elastic principal graphs (curves, trees, forests) fitted to weighted 2D or
// 3D point clouds, behind a flat C ABI that afield.geometry.curves.principal.PrincipalCurvesNative
// binds with java.lang.foreign.
//
// The fitting algorithm is a port of ElPiGraph (Albergante, Mirkes, Bac, Chen, Martin, Faure,
// Barillot, Pinello, Gorban, Zinovyev; https://github.com/j-bac/elpigraph-python, GPL-3). Field is
// AGPL-3.0-or-later, which GPL-3 section 13 allows to combine with it. Energy, for node positions y,
// edges E with elasticities lambda, and stars S (each node of degree > 1, with elasticity mu):
//
//     U = sum_i w_i min(|x_i - y_k(i)|^2, R0^2) / sum_i w_i          (approximation, trimmed at R0)
//       + sum_(a,b) in E  lambda_ab |y_a - y_b|^2                     (stretching)
//       + sum_c in S      mu_c |y_c - mean(leaves of c)|^2            (bending)
//
// Fitting a fixed graph alternates assigning each point to its nearest node with solving the
// linear system that minimises U given that assignment. Growing a graph applies, at each step, the
// graph-grammar operation (bisect an edge; add a node to a leaf; add a node to any node) whose fitted
// configuration has the lowest energy.
//
// Beyond ElPiGraph: each point is also projected onto the nearest edge, and per-node statistics of
// the projections are kept (see fpc_node_stats).
//
// Ownership. fpc_create returns a forest the caller frees with fpc_free. Every output array is
// caller-allocated and sized by the matching count call. No call keeps a caller pointer: the
// points are copied in. A forest is not thread-safe; one caller at a time.
//
// Errors. Calls that can fail return a negative status or NULL and write a message into `error`.
// No C++ exception crosses this boundary.

#ifndef FIELD_PRINCIPAL_CURVES_H
#define FIELD_PRINCIPAL_CURVES_H

#include <stdint.h>

#if defined(_WIN32)
#  define FPC_API __declspec(dllexport)
#else
#  define FPC_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define FPC_ABI_VERSION 2

typedef struct fpc_forest fpc_forest;

typedef struct fpc_params {
    double lambda;           // edge stretching elasticity (ElPiGraph default 0.01)
    double mu;               // star bending elasticity (ElPiGraph default 0.1)
    double trimming_radius;  // points farther than this from every node do not pull; <= 0 means none
    double branch_penalty;   // added to the energy per unit of (degree - 2) at branch points when choosing growth steps
    int32_t max_iterations;  // EM iterations per fit
    double eps;              // convergence: relative change of node positions
    int32_t candidate_sample;// points used to score growth candidates (0 = all)
    int32_t threads;         // 0 = hardware concurrency
    uint64_t seed;           // for sampling and k-means
} fpc_params;

// grammar flags for fpc_grow
#define FPC_BISECT 1     // split an edge at its midpoint
#define FPC_EXTEND 2     // add a node to a leaf (keeps curves as curves)
#define FPC_BRANCH 4     // add a node to any node, making or enlarging a branch point

FPC_API int32_t fpc_abi_version(void);
FPC_API const char* fpc_version_string(void);
FPC_API void fpc_default_params(fpc_params* out);

// points: n * dimension doubles; weights: n doubles or NULL for all ones. dimension is 2 or 3.
FPC_API fpc_forest* fpc_create(int32_t dimension, const double* points, const double* weights, int32_t n,
                               const fpc_params* params, char* error, int32_t error_capacity);
FPC_API void fpc_free(fpc_forest* f);
FPC_API int32_t fpc_set_params(fpc_forest* f, const fpc_params* params, char* error, int32_t error_capacity);

// Replaces the points (and weights, or NULL for all ones), keeping the graph: refit to follow data
// that moves.
FPC_API int32_t fpc_set_points(fpc_forest* f, const double* points, const double* weights, int32_t n,
                               char* error, int32_t error_capacity);

// Initialisation. Each replaces the graph.
// fpc_set_graph: k nodes (k * dimension doubles) and e edges (2e node indices).
FPC_API int32_t fpc_set_graph(fpc_forest* f, int32_t k, const double* nodes, int32_t e, const int32_t* edges,
                              char* error, int32_t error_capacity);
// `components` disjoint chains of `nodes_per_component` nodes. The points are over-segmented by
// k-means, the centres joined by their minimum spanning tree, and the tree cut at its
// (components - 1) longest edges; each piece starts as the longest path through it, so curved and
// elongated structures are followed rather than cut across.
FPC_API int32_t fpc_init_components(fpc_forest* f, int32_t components, int32_t nodes_per_component,
                                    char* error, int32_t error_capacity);
// k k-means centres joined by their minimum spanning tree.
FPC_API int32_t fpc_init_tree(fpc_forest* f, int32_t k, char* error, int32_t error_capacity);

// Fixes node `node` in place (fixed = 1) or frees it, optionally moving it first (position non-NULL).
FPC_API int32_t fpc_set_node(fpc_forest* f, int32_t node, const double* position, int32_t fixed,
                             char* error, int32_t error_capacity);
// Inserts a node on edge `edge` at parameter t in (0, 1). Returns the new node index.
FPC_API int32_t fpc_split_edge(fpc_forest* f, int32_t edge, double t, char* error, int32_t error_capacity);

// Fits the current graph without changing its topology. Returns the number of iterations used.
FPC_API int32_t fpc_fit(fpc_forest* f, int32_t max_iterations, char* error, int32_t error_capacity);
// Grows the graph by grammar operations (FPC_* flags) until it has target_nodes nodes, fitting
// after each step. Returns the number of steps applied.
FPC_API int32_t fpc_grow(fpc_forest* f, int32_t target_nodes, int32_t grammar, char* error, int32_t error_capacity);

FPC_API int32_t fpc_node_count(const fpc_forest* f);
FPC_API int32_t fpc_edge_count(const fpc_forest* f);
FPC_API void fpc_nodes(const fpc_forest* f, double* positions, int32_t* fixed);
FPC_API void fpc_edges(const fpc_forest* f, int32_t* edges);
// out: total energy, approximation (MSE), stretching (EP), bending (RP), trimmed weight fraction.
FPC_API void fpc_energy(const fpc_forest* f, double* out5);

// Per point: nearest node, nearest edge (-1 if the graph has none or the point is trimmed),
// parameter along that edge from its first node, and distance to the graph.
FPC_API int32_t fpc_point_count(const fpc_forest* f);
FPC_API void fpc_assignments(const fpc_forest* f, int32_t* node, int32_t* edge, double* t, double* distance);

// Per node, from the edge projections, each point shared between an edge's two nodes by its
// parameter: support (sum of weights), effective sample size (sum w)^2 / sum w^2, mean residual
// distance, transverse spread, and standard error of the node's position (sqrt(spread / n_eff)).
// Spread is the variance across the curve in 2D (1 value per node) and the 2x2 covariance in the
// plane normal to the curve in 3D (3 values per node: uu, uv, vv, in the basis written to `basis`,
// 6 doubles per node: u then v). The tangent is written to `tangent` (dimension doubles per node).
// Spread describes the data; standard error describes how well the node is determined. A wide,
// densely sampled ribbon has large spread and small error; a thin curve through three points the
// reverse.
FPC_API void fpc_node_stats(const fpc_forest* f, double* support, double* n_eff, double* residual,
                            double* spread, double* standard_error, double* tangent, double* basis);

#ifdef __cplusplus
}
#endif

#endif
