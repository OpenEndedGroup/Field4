// Elastic principal graphs, ported from ElPiGraph. See field_principal_curves.h for the energy,
// ownership and error conventions.
//
// Deviations from ElPiGraph, each deliberate:
//   - Elasticities are uniform: every edge has lambda and every node of degree > 1 has mu, recomputed
//     when the topology changes. ElPiGraph carries per-edge values through grammar operations, which
//     with uniform inputs gives the same matrices.
//   - Convergence compares the largest node move with the data's own spread, not with each node's
//     distance from the origin, so pixel coordinates far from (0, 0) do not stop the fit early.
//   - Growth candidates are scored on a random subsample of the points (candidate_sample) and in
//     parallel; the chosen configuration is then refitted on every point.
//   - Each connected component's lambda and mu are scaled by its share of the point weight
//     (component_scale), so a sparse component is not crushed by the stiffness meant for a dense one.
//   - branch_penalty adds penalty * sum(max(0, degree - 2)) to a candidate's energy, a simpler stand-in
//     for ElPiGraph's penalised energy, so branching has to earn its place.
//   - Point-to-edge projection and per-node statistics are new.

#include "field_principal_curves.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <numeric>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

void set_error(char* error, int32_t capacity, const std::string& message) {
    if (!error || capacity <= 0) return;
    std::strncpy(error, message.c_str(), static_cast<size_t>(capacity) - 1);
    error[capacity - 1] = '\0';
}

struct Graph {
    int d = 2;
    std::vector<double> y;  // k * d
    std::vector<char> fixed;
    std::vector<std::array<int, 2>> edges;

    int k() const { return static_cast<int>(fixed.size()); }
    const double* node(int i) const { return &y[static_cast<size_t>(i) * d]; }
    double* node(int i) { return &y[static_cast<size_t>(i) * d]; }

    int add_node(const double* p) {
        y.insert(y.end(), p, p + d);
        fixed.push_back(0);
        return k() - 1;
    }

    std::vector<std::vector<int>> adjacency() const {
        std::vector<std::vector<int>> adj(k());
        for (auto& e : edges) {
            adj[e[0]].push_back(e[1]);
            adj[e[1]].push_back(e[0]);
        }
        return adj;
    }
};

// Connected-component label of every node.
std::vector<int> component_labels(const Graph& g) {
    std::vector<int> label(g.k(), -1);
    auto adj = g.adjacency();
    int next = 0;
    for (int s = 0; s < g.k(); s++) {
        if (label[s] >= 0) continue;
        std::vector<int> stack{s};
        label[s] = next;
        while (!stack.empty()) {
            int u = stack.back(); stack.pop_back();
            for (int v : adj[u]) if (label[v] < 0) { label[v] = next; stack.push_back(v); }
        }
        next++;
    }
    return label;
}

template <typename F>
void parallel_for(int n, int threads, F body) {
    if (threads <= 1 || n < 2048) {
        body(0, n);
        return;
    }
    std::vector<std::thread> pool;
    int chunk = (n + threads - 1) / threads;
    for (int t = 0; t < threads; t++) {
        int lo = t * chunk, hi = std::min(n, lo + chunk);
        if (lo >= hi) break;
        pool.emplace_back([=] { body(lo, hi); });
    }
    for (auto& th : pool) th.join();
}

// Gaussian elimination with partial pivoting; solves A X = B in place (A is k*k, B is k*d).
void solve(std::vector<double>& A, std::vector<double>& B, int k, int d) {
    for (int c = 0; c < k; c++) {
        int p = c;
        for (int r = c + 1; r < k; r++) if (std::fabs(A[r * k + c]) > std::fabs(A[p * k + c])) p = r;
        if (std::fabs(A[p * k + c]) < 1e-300) throw std::runtime_error("singular elastic system");
        if (p != c) {
            for (int j = 0; j < k; j++) std::swap(A[c * k + j], A[p * k + j]);
            for (int j = 0; j < d; j++) std::swap(B[c * d + j], B[p * d + j]);
        }
        double inv = 1.0 / A[c * k + c];
        for (int r = c + 1; r < k; r++) {
            double f = A[r * k + c] * inv;
            if (f == 0) continue;
            for (int j = c; j < k; j++) A[r * k + j] -= f * A[c * k + j];
            for (int j = 0; j < d; j++) B[r * d + j] -= f * B[c * d + j];
        }
    }
    for (int r = k - 1; r >= 0; r--) {
        for (int j = 0; j < d; j++) {
            double s = B[r * d + j];
            for (int c = r + 1; c < k; c++) s -= A[r * k + c] * B[c * d + j];
            B[r * d + j] = s / A[r * k + r];
        }
    }
}

}  // namespace

struct fpc_forest {
    int d = 2;
    int n = 0;
    std::vector<double> x, w;
    double wsum = 0;
    double spread = 1;  // total variance of the data, for convergence
    fpc_params p{};
    Graph g;

    int threads() const {
        int t = p.threads > 0 ? p.threads : static_cast<int>(std::thread::hardware_concurrency());
        return std::max(1, t);
    }
    const double* point(int i) const { return &x[static_cast<size_t>(i) * d]; }
    double r0sq() const { return p.trimming_radius > 0 ? p.trimming_radius * p.trimming_radius : std::numeric_limits<double>::infinity(); }

    double dist2(const double* a, const double* b) const {
        double s = 0;
        for (int j = 0; j < d; j++) { double t = a[j] - b[j]; s += t * t; }
        return s;
    }
};

namespace {

// Nearest node for each point of `subset` (all points if empty). part = -1 for trimmed points.
void partition(const fpc_forest& f, const Graph& g, const std::vector<int>& subset, std::vector<int>& part,
               std::vector<double>& d2, int threads) {
    const int m = subset.empty() ? f.n : static_cast<int>(subset.size());
    part.assign(m, -1);
    d2.assign(m, 0);
    const double r0 = f.r0sq();
    const int k = g.k();
    parallel_for(m, threads, [&](int lo, int hi) {
        for (int s = lo; s < hi; s++) {
            const double* xi = f.point(subset.empty() ? s : subset[s]);
            int best = -1;
            double bd = std::numeric_limits<double>::infinity();
            for (int j = 0; j < k; j++) {
                double v = f.dist2(xi, g.node(j));
                if (v < bd) { bd = v; best = j; }
            }
            if (bd > r0) { part[s] = -1; d2[s] = r0; } else { part[s] = best; d2[s] = bd; }
        }
    });
}

// ElPiGraph's spring Laplacian, with uniform lambda and mu.
// scale[j]: multiplier for the elasticities of node j's component (see component_scale).
void add_laplacian(const fpc_forest& f, const Graph& g, const std::vector<double>& scale, std::vector<double>& A) {
    const int k = g.k();
    for (auto& e : g.edges) {
        double l = f.p.lambda * scale[e[0]];
        A[e[0] * k + e[0]] += l; A[e[1] * k + e[1]] += l;
        A[e[0] * k + e[1]] -= l; A[e[1] * k + e[0]] -= l;
    }
    auto adj = g.adjacency();
    for (int c = 0; c < k; c++) {
        int K = static_cast<int>(adj[c].size());
        if (K < 2 || f.p.mu <= 0) continue;
        double mu = f.p.mu * scale[c];
        A[c * k + c] += mu;
        for (int l : adj[c]) { A[c * k + l] -= mu / K; A[l * k + c] -= mu / K; }
        for (int a : adj[c]) for (int b : adj[c]) A[a * k + b] += mu / (static_cast<double>(K) * K);
    }
}

struct Energy { double total, mse, ep, rp, trimmed; };

std::vector<double> component_scale(const fpc_forest& f, const Graph& g, const std::vector<int>& subset, const std::vector<int>& part);

Energy energy(const fpc_forest& f, const Graph& g, const std::vector<int>& subset, const std::vector<int>& part,
              const std::vector<double>& d2) {
    auto scale = component_scale(f, g, subset, part);
    double ws = 0, mse = 0, trimmed = 0;
    for (size_t s = 0; s < part.size(); s++) {
        double wi = f.w[subset.empty() ? s : subset[s]];
        ws += wi;
        mse += wi * d2[s];
        if (part[s] < 0) trimmed += wi;
    }
    mse /= std::max(ws, 1e-300);
    double ep = 0;
    for (auto& e : g.edges) ep += f.p.lambda * scale[e[0]] * f.dist2(g.node(e[0]), g.node(e[1]));
    double rp = 0;
    auto adj = g.adjacency();
    std::vector<double> mean(g.d);
    for (int c = 0; c < g.k(); c++) {
        if (adj[c].size() < 2) continue;
        std::fill(mean.begin(), mean.end(), 0.0);
        for (int l : adj[c]) for (int j = 0; j < g.d; j++) mean[j] += g.node(l)[j] / adj[c].size();
        rp += f.p.mu * scale[c] * f.dist2(g.node(c), mean.data());
    }
    return {mse + ep + rp, mse, ep, rp, trimmed / std::max(ws, 1e-300)};
}

// Each component's share of the assigned point weight, per node. The approximation term weights
// every point by its share of the total, so without this a component holding 1% of the points
// feels 1% of the pull and the same elasticity as everything else, and shrinks. Scaling each
// component's lambda and mu by its share makes it settle as it would if fitted alone. A single
// component (or a single tree) is unaffected. The share is floored so an empty component stays put.
std::vector<double> component_scale(const fpc_forest& f, const Graph& g, const std::vector<int>& subset, const std::vector<int>& part) {
    auto label = component_labels(g);
    int nc = label.empty() ? 0 : *std::max_element(label.begin(), label.end()) + 1;
    std::vector<double> share(nc, 0.0);
    double total = 0;
    for (size_t s = 0; s < part.size(); s++) {
        double wi = f.w[subset.empty() ? s : subset[s]];
        total += wi;
        if (part[s] >= 0) share[label[part[s]]] += wi;
    }
    std::vector<double> scale(g.k(), 1.0);
    if (nc <= 1 || total <= 0) return scale;
    for (int j = 0; j < g.k(); j++) scale[j] = std::max(share[label[j]] / total, 1e-6);
    return scale;
}

// Fixed-topology EM fit. Returns iterations used; leaves the final partition in part/d2.
int embed(const fpc_forest& f, Graph& g, const std::vector<int>& subset, int max_iterations, int threads,
          std::vector<int>& part, std::vector<double>& d2) {
    const int k = g.k(), d = g.d;
    partition(f, g, subset, part, d2, threads);
    if (k == 0) return 0;
    int it = 0;
    for (; it < max_iterations; it++) {
        double ws = 0;
        for (size_t s = 0; s < part.size(); s++) ws += f.w[subset.empty() ? s : subset[s]];
        std::vector<double> A(static_cast<size_t>(k) * k, 0.0), B(static_cast<size_t>(k) * d, 0.0);
        for (size_t s = 0; s < part.size(); s++) {
            int j = part[s];
            if (j < 0) continue;
            int i = subset.empty() ? static_cast<int>(s) : subset[s];
            double wi = f.w[i] / ws;
            A[j * k + j] += wi;
            for (int c = 0; c < d; c++) B[j * d + c] += wi * f.point(i)[c];
        }
        add_laplacian(f, g, component_scale(f, g, subset, part), A);
        for (int j = 0; j < k; j++) {
            if (g.fixed[j]) {
                for (int c = 0; c < k; c++) A[j * k + c] = 0;
                A[j * k + j] = 1;
                for (int c = 0; c < d; c++) B[j * d + c] = g.node(j)[c];
            } else {
                // a node with no points and no edges would make the system singular; tie it to where it is
                const double tie = 1e-9;
                A[j * k + j] += tie;
                for (int c = 0; c < d; c++) B[j * d + c] += tie * g.node(j)[c];
            }
        }
        solve(A, B, k, d);
        double move = 0;
        for (int j = 0; j < k; j++) move = std::max(move, f.dist2(&B[j * d], g.node(j)));
        g.y = B;
        partition(f, g, subset, part, d2, threads);
        if (move / f.spread < f.p.eps * f.p.eps) { it++; break; }
    }
    return it;
}

int degree_excess(const Graph& g) {
    auto adj = g.adjacency();
    int s = 0;
    for (auto& a : adj) s += std::max(0, static_cast<int>(a.size()) - 2);
    return s;
}

// Weighted k-means on a sample, k-means++ seeding. Returns k * d centres.
std::vector<double> kmeans(const fpc_forest& f, int k, std::mt19937_64& rng) {
    const int d = f.d;
    std::vector<int> sample(f.n);
    std::iota(sample.begin(), sample.end(), 0);
    if (f.n > 50000) { std::shuffle(sample.begin(), sample.end(), rng); sample.resize(50000); }
    std::vector<double> c;
    std::uniform_int_distribution<int> pick(0, static_cast<int>(sample.size()) - 1);
    const double* first = f.point(sample[pick(rng)]);
    c.insert(c.end(), first, first + d);
    std::vector<double> dmin(sample.size(), std::numeric_limits<double>::infinity());
    while (static_cast<int>(c.size() / d) < k) {
        double total = 0;
        for (size_t s = 0; s < sample.size(); s++) {
            dmin[s] = std::min(dmin[s], f.dist2(f.point(sample[s]), &c[c.size() - d]));
            total += dmin[s] * f.w[sample[s]];
        }
        std::uniform_real_distribution<double> u(0, total);
        double r = u(rng), acc = 0;
        size_t chosen = sample.size() - 1;
        for (size_t s = 0; s < sample.size(); s++) { acc += dmin[s] * f.w[sample[s]]; if (acc >= r) { chosen = s; break; } }
        const double* p = f.point(sample[chosen]);
        c.insert(c.end(), p, p + d);
    }
    std::vector<int> lab(sample.size());
    for (int it = 0; it < 25; it++) {
        for (size_t s = 0; s < sample.size(); s++) {
            int best = 0; double bd = std::numeric_limits<double>::infinity();
            for (int j = 0; j < k; j++) { double v = f.dist2(f.point(sample[s]), &c[j * d]); if (v < bd) { bd = v; best = j; } }
            lab[s] = best;
        }
        std::vector<double> sum(static_cast<size_t>(k) * d, 0.0), ws(k, 0.0);
        for (size_t s = 0; s < sample.size(); s++) {
            double wi = f.w[sample[s]];
            ws[lab[s]] += wi;
            for (int j = 0; j < d; j++) sum[lab[s] * d + j] += wi * f.point(sample[s])[j];
        }
        for (int j = 0; j < k; j++) if (ws[j] > 0) for (int q = 0; q < d; q++) c[j * d + q] = sum[j * d + q] / ws[j];
    }
    return c;
}

// Mean and principal axis (unit) and standard deviation along it, of the points with label `which`
// (all points if labels is empty).
void principal_axis(const fpc_forest& f, const std::vector<int>& labels, int which, std::vector<double>& mean,
                    std::vector<double>& axis, double& sigma) {
    const int d = f.d;
    mean.assign(d, 0.0);
    double ws = 0;
    for (int i = 0; i < f.n; i++) {
        if (!labels.empty() && labels[i] != which) continue;
        ws += f.w[i];
        for (int j = 0; j < d; j++) mean[j] += f.w[i] * f.point(i)[j];
    }
    if (ws <= 0) throw std::runtime_error("an initial component has no points");
    for (auto& m : mean) m /= ws;
    std::vector<double> C(d * d, 0.0);
    for (int i = 0; i < f.n; i++) {
        if (!labels.empty() && labels[i] != which) continue;
        for (int a = 0; a < d; a++) for (int b = 0; b < d; b++)
            C[a * d + b] += f.w[i] * (f.point(i)[a] - mean[a]) * (f.point(i)[b] - mean[b]) / ws;
    }
    axis.assign(d, 0.0);
    axis[0] = 1;
    for (int it = 0; it < 100; it++) {  // power iteration
        std::vector<double> nx(d, 0.0);
        for (int a = 0; a < d; a++) for (int b = 0; b < d; b++) nx[a] += C[a * d + b] * axis[b];
        double len = std::sqrt(std::inner_product(nx.begin(), nx.end(), nx.begin(), 0.0));
        if (len < 1e-300) break;
        for (int a = 0; a < d; a++) axis[a] = nx[a] / len;
    }
    double var = 0;
    for (int a = 0; a < d; a++) for (int b = 0; b < d; b++) var += axis[a] * C[a * d + b] * axis[b];
    sigma = std::sqrt(std::max(var, 0.0));
}

void check_ready(const fpc_forest* f) {
    if (!f) throw std::invalid_argument("forest is null");
}

void validate(const fpc_params& p) {
    if (!(p.lambda >= 0) || !(p.mu >= 0)) throw std::invalid_argument("lambda and mu must be >= 0");
    if (p.max_iterations < 1) throw std::invalid_argument("max_iterations must be >= 1");
    if (!(p.eps > 0)) throw std::invalid_argument("eps must be > 0");
    if (!(p.branch_penalty >= 0)) throw std::invalid_argument("branch_penalty must be >= 0");
}

template <typename F>
int32_t guarded(char* error, int32_t cap, F body) {
    try { return body(); }
    catch (const std::exception& e) { set_error(error, cap, e.what()); return -1; }
}

}  // namespace

extern "C" {

int32_t fpc_abi_version(void) { return FPC_ABI_VERSION; }
const char* fpc_version_string(void) { return "field_principal_curves 1.0 (elastic principal graphs, ported from ElPiGraph)"; }

void fpc_default_params(fpc_params* out) {
    out->lambda = 0.01;
    out->mu = 0.1;
    out->trimming_radius = 0;
    out->branch_penalty = 0;
    out->max_iterations = 30;
    out->eps = 0.001;
    out->candidate_sample = 5000;
    out->threads = 0;
    out->seed = 1;
}

namespace {
void load_points(fpc_forest& f, const double* points, const double* weights, int32_t n) {
    if (n < 2 || !points) throw std::invalid_argument("need at least two points");
    std::vector<double> x(points, points + static_cast<size_t>(n) * f.d), w(n, 1.0);
    if (weights) w.assign(weights, weights + n);
    double wsum = 0;
    for (int i = 0; i < n; i++) {
        if (!(w[i] >= 0) || !std::isfinite(w[i])) throw std::invalid_argument("weight " + std::to_string(i) + " is negative or not finite");
        for (int j = 0; j < f.d; j++)
            if (!std::isfinite(x[static_cast<size_t>(i) * f.d + j])) throw std::invalid_argument("point " + std::to_string(i) + " is not finite");
        wsum += w[i];
    }
    if (!(wsum > 0)) throw std::invalid_argument("weights sum to zero");
    f.x = std::move(x);
    f.w = std::move(w);
    f.n = n;
    f.wsum = wsum;
    std::vector<double> mean, axis;
    double sigma;
    principal_axis(f, {}, 0, mean, axis, sigma);
    double total = 0;
    for (int i = 0; i < n; i++) total += f.w[i] * f.dist2(f.point(i), mean.data());
    f.spread = std::max(total / f.wsum, 1e-300);
}
}  // namespace

fpc_forest* fpc_create(int32_t dimension, const double* points, const double* weights, int32_t n,
                       const fpc_params* params, char* error, int32_t error_capacity) {
    try {
        if (dimension != 2 && dimension != 3) throw std::invalid_argument("dimension must be 2 or 3");
        auto f = std::make_unique<fpc_forest>();
        f->d = dimension;
        f->g.d = dimension;
        if (params) f->p = *params; else fpc_default_params(&f->p);
        validate(f->p);
        load_points(*f, points, weights, n);
        return f.release();
    } catch (const std::exception& e) {
        set_error(error, error_capacity, e.what());
        return nullptr;
    }
}

int32_t fpc_set_points(fpc_forest* f, const double* points, const double* weights, int32_t n, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] { check_ready(f); load_points(*f, points, weights, n); return 0; });
}

void fpc_free(fpc_forest* f) { delete f; }

int32_t fpc_set_params(fpc_forest* f, const fpc_params* params, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] { check_ready(f); validate(*params); f->p = *params; return 0; });
}

int32_t fpc_set_graph(fpc_forest* f, int32_t k, const double* nodes, int32_t e, const int32_t* edges,
                      char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] {
        check_ready(f);
        if (k < 1) throw std::invalid_argument("a graph needs at least one node");
        Graph g;
        g.d = f->d;
        g.y.assign(nodes, nodes + static_cast<size_t>(k) * f->d);
        g.fixed.assign(k, 0);
        for (int i = 0; i < e; i++) {
            int a = edges[2 * i], b = edges[2 * i + 1];
            if (a < 0 || b < 0 || a >= k || b >= k || a == b) throw std::invalid_argument("edge " + std::to_string(i) + " is invalid");
            g.edges.push_back({a, b});
        }
        f->g = std::move(g);
        return 0;
    });
}

int32_t fpc_init_components(fpc_forest* f, int32_t components, int32_t nodes_per_component, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] {
        check_ready(f);
        if (components < 1 || nodes_per_component < 2) throw std::invalid_argument("need >= 1 component of >= 2 nodes");
        // Over-segment with k-means, join the centres by their minimum spanning tree, and cut its
        // (components - 1) longest edges (single linkage), so elongated and curved structures stay
        // whole where plain k-means would cut across them. Each piece starts as the longest path
        // through its part of the tree, resampled to nodes_per_component nodes; a piece too small to
        // have a path starts as a segment along its principal axis.
        std::mt19937_64 rng(f->p.seed);
        const int d = f->d;
        int m = std::min(f->n, std::max(8 * components + 8, 2 * nodes_per_component * components));
        auto c = kmeans(*f, m, rng);
        std::vector<char> in(m, 0);
        std::vector<double> best(m, std::numeric_limits<double>::infinity());
        std::vector<int> from(m, -1);
        std::vector<std::array<int, 2>> tree;
        std::vector<double> length;
        best[0] = 0;
        for (int step = 0; step < m; step++) {
            int u = -1;
            for (int j = 0; j < m; j++) if (!in[j] && (u < 0 || best[j] < best[u])) u = j;
            in[u] = 1;
            if (from[u] >= 0) { tree.push_back({from[u], u}); length.push_back(best[u]); }
            for (int j = 0; j < m; j++) { double v = f->dist2(&c[u * d], &c[j * d]); if (!in[j] && v < best[j]) { best[j] = v; from[j] = u; } }
        }
        std::vector<int> order(tree.size());
        std::iota(order.begin(), order.end(), 0);
        std::sort(order.begin(), order.end(), [&](int a, int b) { return length[a] > length[b]; });
        std::vector<char> cut(tree.size(), 0);
        for (int i = 0; i < components - 1 && i < static_cast<int>(order.size()); i++) cut[order[i]] = 1;
        std::vector<std::vector<int>> adj(m);
        for (size_t e = 0; e < tree.size(); e++) if (!cut[e]) { adj[tree[e][0]].push_back(tree[e][1]); adj[tree[e][1]].push_back(tree[e][0]); }
        auto farthest = [&](int s, std::vector<int>& parent) {
            std::vector<double> dist(m, -1);
            parent.assign(m, -1);
            std::vector<int> stack{s};
            dist[s] = 0;
            int far = s;
            while (!stack.empty()) {
                int u = stack.back(); stack.pop_back();
                if (dist[u] > dist[far]) far = u;
                for (int v : adj[u]) if (dist[v] < 0) { dist[v] = dist[u] + std::sqrt(f->dist2(&c[u * d], &c[v * d])); parent[v] = u; stack.push_back(v); }
            }
            return far;
        };
        Graph g;
        g.d = d;
        std::vector<char> seen(m, 0);
        std::vector<int> label(m, -1);
        int pieces = 0;
        for (int s0 = 0; s0 < m; s0++) {
            if (seen[s0]) continue;
            std::vector<int> parent;
            int a = farthest(s0, parent);
            for (int j = 0; j < m; j++) if (parent[j] >= 0 || j == s0) { seen[j] = 1; label[j] = pieces; }
            int b = farthest(a, parent);
            std::vector<int> path;
            for (int v = b; v >= 0; v = parent[v]) path.push_back(v);
            int base = g.k();
            std::vector<double> p(d);
            if (path.size() >= 2) {
                std::vector<double> cum(path.size(), 0.0);
                for (size_t i = 1; i < path.size(); i++) cum[i] = cum[i - 1] + std::sqrt(f->dist2(&c[path[i] * d], &c[path[i - 1] * d]));
                for (int i = 0; i < nodes_per_component; i++) {
                    double target = cum.back() * i / (nodes_per_component - 1);
                    size_t j = std::upper_bound(cum.begin(), cum.end(), target) - cum.begin();
                    j = std::clamp<size_t>(j, 1, path.size() - 1);
                    double seg = cum[j] - cum[j - 1], u = seg > 0 ? (target - cum[j - 1]) / seg : 0;
                    for (int q = 0; q < d; q++) p[q] = c[path[j - 1] * d + q] * (1 - u) + c[path[j] * d + q] * u;
                    g.add_node(p.data());
                    if (i > 0) g.edges.push_back({base + i - 1, base + i});
                }
            } else {
                // one centre: a short segment through it along the principal axis of its points
                std::vector<int> labels(f->n);
                for (int i = 0; i < f->n; i++) {
                    int bj = 0; double bd = std::numeric_limits<double>::infinity();
                    for (int j = 0; j < m; j++) { double v = f->dist2(f->point(i), &c[j * d]); if (v < bd) { bd = v; bj = j; } }
                    labels[i] = bj;
                }
                std::vector<double> mean, axis;
                double sigma;
                principal_axis(*f, labels, path[0], mean, axis, sigma);
                for (int i = 0; i < nodes_per_component; i++) {
                    double t = -2 * sigma + 4 * sigma * i / (nodes_per_component - 1);
                    for (int q = 0; q < d; q++) p[q] = mean[q] + t * axis[q];
                    g.add_node(p.data());
                    if (i > 0) g.edges.push_back({base + i - 1, base + i});
                }
            }
            pieces++;
        }
        f->g = std::move(g);
        return 0;
    });
}

int32_t fpc_init_tree(fpc_forest* f, int32_t k, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] {
        check_ready(f);
        if (k < 2) throw std::invalid_argument("a tree needs at least two nodes");
        std::mt19937_64 rng(f->p.seed);
        auto c = kmeans(*f, k, rng);
        Graph g;
        g.d = f->d;
        g.y = c;
        g.fixed.assign(k, 0);
        // Prim's minimum spanning tree on the centres
        std::vector<char> in(k, 0);
        std::vector<double> best(k, std::numeric_limits<double>::infinity());
        std::vector<int> from(k, -1);
        best[0] = 0;
        for (int step = 0; step < k; step++) {
            int u = -1;
            for (int j = 0; j < k; j++) if (!in[j] && (u < 0 || best[j] < best[u])) u = j;
            in[u] = 1;
            if (from[u] >= 0) g.edges.push_back({from[u], u});
            for (int j = 0; j < k; j++) {
                double v = f->dist2(&c[u * f->d], &c[j * f->d]);
                if (!in[j] && v < best[j]) { best[j] = v; from[j] = u; }
            }
        }
        f->g = std::move(g);
        return 0;
    });
}

int32_t fpc_set_node(fpc_forest* f, int32_t node, const double* position, int32_t fixed, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] {
        check_ready(f);
        if (node < 0 || node >= f->g.k()) throw std::invalid_argument("no node " + std::to_string(node));
        if (position) std::copy(position, position + f->d, f->g.node(node));
        f->g.fixed[node] = fixed ? 1 : 0;
        return 0;
    });
}

int32_t fpc_split_edge(fpc_forest* f, int32_t edge, double t, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] {
        check_ready(f);
        if (edge < 0 || edge >= static_cast<int>(f->g.edges.size())) throw std::invalid_argument("no edge " + std::to_string(edge));
        if (!(t > 0 && t < 1)) throw std::invalid_argument("t must be in (0, 1)");
        auto e = f->g.edges[edge];
        std::vector<double> p(f->d);
        for (int j = 0; j < f->d; j++) p[j] = f->g.node(e[0])[j] * (1 - t) + f->g.node(e[1])[j] * t;
        int m = f->g.add_node(p.data());
        f->g.edges[edge] = {e[0], m};
        f->g.edges.push_back({m, e[1]});
        return m;
    });
}

int32_t fpc_fit(fpc_forest* f, int32_t max_iterations, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] {
        check_ready(f);
        if (f->g.k() == 0) throw std::invalid_argument("the forest has no graph yet; initialise it first");
        std::vector<int> part;
        std::vector<double> d2;
        return embed(*f, f->g, {}, max_iterations > 0 ? max_iterations : f->p.max_iterations, f->threads(), part, d2);
    });
}

int32_t fpc_grow(fpc_forest* f, int32_t target_nodes, int32_t grammar, char* error, int32_t error_capacity) {
    return guarded(error, error_capacity, [&] {
        check_ready(f);
        if (f->g.k() == 0) throw std::invalid_argument("the forest has no graph yet; initialise it first");
        if ((grammar & (FPC_BISECT | FPC_EXTEND | FPC_BRANCH)) == 0) throw std::invalid_argument("no grammar operation enabled");
        std::mt19937_64 rng(f->p.seed + 7919);
        std::vector<int> all(f->n);
        std::iota(all.begin(), all.end(), 0);
        int steps = 0;
        std::vector<int> part;
        std::vector<double> d2;
        embed(*f, f->g, {}, f->p.max_iterations, f->threads(), part, d2);
        while (f->g.k() < target_nodes) {
            std::vector<int> subset;
            if (f->p.candidate_sample > 0 && f->p.candidate_sample < f->n) {
                std::shuffle(all.begin(), all.end(), rng);
                subset.assign(all.begin(), all.begin() + f->p.candidate_sample);
            }
            // candidates
            std::vector<Graph> candidates;
            auto adj = f->g.adjacency();
            const int d = f->d;
            if (grammar & FPC_BISECT) {
                for (size_t e = 0; e < f->g.edges.size(); e++) {
                    Graph c = f->g;
                    auto ed = c.edges[e];
                    std::vector<double> p(d);
                    for (int j = 0; j < d; j++) p[j] = 0.5 * (c.node(ed[0])[j] + c.node(ed[1])[j]);
                    int m = c.add_node(p.data());
                    c.edges[e] = {ed[0], m};
                    c.edges.push_back({m, ed[1]});
                    candidates.push_back(std::move(c));
                }
            }
            for (int i = 0; i < f->g.k(); i++) {
                int deg = static_cast<int>(adj[i].size());
                bool leaf = deg <= 1;
                if (leaf ? !(grammar & (FPC_EXTEND | FPC_BRANCH)) : !(grammar & FPC_BRANCH)) continue;
                Graph c = f->g;
                std::vector<double> p(d);
                if (deg == 1) {
                    for (int j = 0; j < d; j++) p[j] = 2 * c.node(i)[j] - c.node(adj[i][0])[j];
                } else if (deg == 0) {
                    // a lone node: step towards its farthest assigned point
                    double bd = -1;
                    std::copy(c.node(i), c.node(i) + d, p.begin());
                    for (int s = 0; s < f->n; s++) if (part[s] == i) {
                        double v = f->dist2(f->point(s), c.node(i));
                        if (v > bd) { bd = v; for (int j = 0; j < d; j++) p[j] = 0.5 * (c.node(i)[j] + f->point(s)[j]); }
                    }
                } else {
                    // ElPiGraph: the mean of the points on the star's centre, or of its leaves if none
                    double ws = 0;
                    std::fill(p.begin(), p.end(), 0.0);
                    for (int s = 0; s < f->n; s++) if (part[s] == i) { ws += f->w[s]; for (int j = 0; j < d; j++) p[j] += f->w[s] * f->point(s)[j]; }
                    if (ws > 0) for (auto& v : p) v /= ws;
                    else for (int l : adj[i]) for (int j = 0; j < d; j++) p[j] += c.node(l)[j] / deg;
                }
                int m = c.add_node(p.data());
                c.edges.push_back({i, m});
                candidates.push_back(std::move(c));
            }
            if (candidates.empty()) break;
            // score in parallel, each candidate fitted single-threaded on the subset
            std::vector<double> score(candidates.size(), std::numeric_limits<double>::infinity());
            std::atomic<size_t> next{0};
            int workers = std::min<int>(f->threads(), static_cast<int>(candidates.size()));
            std::vector<std::thread> pool;
            const int cand_iterations = std::max(3, f->p.max_iterations / 3);
            for (int t = 0; t < workers; t++) pool.emplace_back([&] {
                std::vector<int> cp;
                std::vector<double> cd;
                for (size_t c; (c = next.fetch_add(1)) < candidates.size();) {
                    try {
                        embed(*f, candidates[c], subset, cand_iterations, 1, cp, cd);
                        score[c] = energy(*f, candidates[c], subset, cp, cd).total + f->p.branch_penalty * degree_excess(candidates[c]);
                    } catch (...) { score[c] = std::numeric_limits<double>::infinity(); }
                }
            });
            for (auto& th : pool) th.join();
            size_t best = std::min_element(score.begin(), score.end()) - score.begin();
            if (!std::isfinite(score[best])) throw std::runtime_error("no growth candidate could be fitted");
            f->g = std::move(candidates[best]);
            embed(*f, f->g, {}, f->p.max_iterations, f->threads(), part, d2);
            steps++;
        }
        return steps;
    });
}

int32_t fpc_node_count(const fpc_forest* f) { return f->g.k(); }
int32_t fpc_edge_count(const fpc_forest* f) { return static_cast<int32_t>(f->g.edges.size()); }

void fpc_nodes(const fpc_forest* f, double* positions, int32_t* fixed) {
    std::copy(f->g.y.begin(), f->g.y.end(), positions);
    for (int i = 0; i < f->g.k(); i++) fixed[i] = f->g.fixed[i];
}

void fpc_edges(const fpc_forest* f, int32_t* edges) {
    for (size_t i = 0; i < f->g.edges.size(); i++) { edges[2 * i] = f->g.edges[i][0]; edges[2 * i + 1] = f->g.edges[i][1]; }
}

void fpc_energy(const fpc_forest* f, double* out) {
    std::vector<int> part;
    std::vector<double> d2;
    partition(*f, f->g, {}, part, d2, f->threads());
    Energy e = energy(*f, f->g, {}, part, d2);
    out[0] = e.total; out[1] = e.mse; out[2] = e.ep; out[3] = e.rp; out[4] = e.trimmed;
}

int32_t fpc_point_count(const fpc_forest* f) { return f->n; }

namespace {

// Projects every point onto the nearest edge among those at its nearest node.
void project(const fpc_forest& f, std::vector<int>& node, std::vector<int>& edge, std::vector<double>& t,
             std::vector<double>& dist, std::vector<char>& trimmed) {
    std::vector<double> d2;
    std::vector<int> part;
    partition(f, f.g, {}, part, d2, f.threads());
    std::vector<std::vector<int>> incident(f.g.k());
    for (size_t e = 0; e < f.g.edges.size(); e++) { incident[f.g.edges[e][0]].push_back(static_cast<int>(e)); incident[f.g.edges[e][1]].push_back(static_cast<int>(e)); }
    node.assign(f.n, -1); edge.assign(f.n, -1); t.assign(f.n, 0); dist.assign(f.n, 0); trimmed.assign(f.n, 0);
    const double r0 = f.r0sq();
    const int d = f.d;
    parallel_for(f.n, f.threads(), [&](int lo, int hi) {
        for (int i = lo; i < hi; i++) {
            const double* xi = f.point(i);
            int j = 0; double bd = std::numeric_limits<double>::infinity();
            for (int q = 0; q < f.g.k(); q++) { double v = f.dist2(xi, f.g.node(q)); if (v < bd) { bd = v; j = q; } }
            node[i] = j;
            dist[i] = std::sqrt(bd);
            trimmed[i] = bd > r0;
            double best = bd;
            for (int e : incident[j]) {
                const double* a = f.g.node(f.g.edges[e][0]);
                const double* b = f.g.node(f.g.edges[e][1]);
                double ab2 = f.dist2(a, b), dot = 0;
                for (int c = 0; c < d; c++) dot += (xi[c] - a[c]) * (b[c] - a[c]);
                double s = ab2 > 0 ? std::clamp(dot / ab2, 0.0, 1.0) : 0.0;
                double q2 = 0;
                for (int c = 0; c < d; c++) { double v = xi[c] - (a[c] + s * (b[c] - a[c])); q2 += v * v; }
                if (edge[i] < 0 || q2 < best) { best = q2; edge[i] = e; t[i] = s; }
            }
            dist[i] = std::sqrt(best);
            if (trimmed[i]) edge[i] = -1;
        }
    });
}

}  // namespace

void fpc_assignments(const fpc_forest* f, int32_t* node, int32_t* edge, double* t, double* distance) {
    std::vector<int> nd, ed;
    std::vector<double> tt, dd;
    std::vector<char> tr;
    project(*f, nd, ed, tt, dd, tr);
    std::copy(nd.begin(), nd.end(), node);
    std::copy(ed.begin(), ed.end(), edge);
    std::copy(tt.begin(), tt.end(), t);
    std::copy(dd.begin(), dd.end(), distance);
}

void fpc_node_stats(const fpc_forest* f, double* support, double* n_eff, double* residual, double* spread,
                    double* standard_error, double* tangent, double* basis) {
    const int k = f->g.k(), d = f->d;
    auto adj = f->g.adjacency();
    // tangents: through a node from its first neighbour's side to its second's; at a leaf, along its edge
    std::vector<double> tan(static_cast<size_t>(k) * d, 0.0);
    for (int i = 0; i < k; i++) {
        std::vector<double> v(d, 0.0);
        if (adj[i].size() >= 2) for (int c = 0; c < d; c++) v[c] = f->g.node(adj[i][1])[c] - f->g.node(adj[i][0])[c];
        else if (adj[i].size() == 1) for (int c = 0; c < d; c++) v[c] = f->g.node(adj[i][0])[c] - f->g.node(i)[c];
        else v[0] = 1;
        double len = std::sqrt(std::inner_product(v.begin(), v.end(), v.begin(), 0.0));
        if (len < 1e-300) { std::fill(v.begin(), v.end(), 0.0); v[0] = 1; len = 1; }
        for (int c = 0; c < d; c++) tan[i * d + c] = v[c] / len;
    }
    // normal-plane basis in 3D
    std::vector<double> u(static_cast<size_t>(k) * 3, 0.0), w(static_cast<size_t>(k) * 3, 0.0);
    if (d == 3) for (int i = 0; i < k; i++) {
        const double* tt = &tan[i * 3];
        double ref[3] = {1, 0, 0};
        if (std::fabs(tt[0]) > 0.9) { ref[0] = 0; ref[1] = 1; }
        double a[3] = {tt[1] * ref[2] - tt[2] * ref[1], tt[2] * ref[0] - tt[0] * ref[2], tt[0] * ref[1] - tt[1] * ref[0]};
        double la = std::sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        for (int c = 0; c < 3; c++) a[c] /= la;
        double b[3] = {tt[1] * a[2] - tt[2] * a[1], tt[2] * a[0] - tt[0] * a[2], tt[0] * a[1] - tt[1] * a[0]};
        for (int c = 0; c < 3; c++) { u[i * 3 + c] = a[c]; w[i * 3 + c] = b[c]; }
    }
    std::vector<int> nd, ed;
    std::vector<double> tt, dd;
    std::vector<char> tr;
    project(*f, nd, ed, tt, dd, tr);
    std::vector<double> sw(k, 0), sw2(k, 0), sres(k, 0), s11(k, 0), s12(k, 0), s22(k, 0);
    for (int i = 0; i < f->n; i++) {
        if (tr[i]) continue;
        const double* xi = f->point(i);
        std::vector<std::pair<int, double>> shares;
        std::vector<double> r(d);
        if (ed[i] < 0) {
            shares.push_back({nd[i], 1.0});
            for (int c = 0; c < d; c++) r[c] = xi[c] - f->g.node(nd[i])[c];
        } else {
            auto e = f->g.edges[ed[i]];
            shares.push_back({e[0], 1 - tt[i]});
            shares.push_back({e[1], tt[i]});
            for (int c = 0; c < d; c++) r[c] = xi[c] - (f->g.node(e[0])[c] + tt[i] * (f->g.node(e[1])[c] - f->g.node(e[0])[c]));
        }
        for (auto [j, share] : shares) {
            double wi = f->w[i] * share;
            if (wi <= 0) continue;
            sw[j] += wi;
            sw2[j] += wi * wi;
            sres[j] += wi * dd[i];
            if (d == 2) {
                double nx = -tan[j * 2 + 1], ny = tan[j * 2];
                double s = r[0] * nx + r[1] * ny;
                s11[j] += wi * s * s;
            } else {
                double a = 0, b = 0;
                for (int c = 0; c < 3; c++) { a += r[c] * u[j * 3 + c]; b += r[c] * w[j * 3 + c]; }
                s11[j] += wi * a * a; s12[j] += wi * a * b; s22[j] += wi * b * b;
            }
        }
    }
    for (int j = 0; j < k; j++) {
        support[j] = sw[j];
        n_eff[j] = sw2[j] > 0 ? sw[j] * sw[j] / sw2[j] : 0;
        residual[j] = sw[j] > 0 ? sres[j] / sw[j] : 0;
        double total;
        if (d == 2) {
            spread[j] = sw[j] > 0 ? s11[j] / sw[j] : 0;
            total = spread[j];
        } else {
            spread[j * 3] = sw[j] > 0 ? s11[j] / sw[j] : 0;
            spread[j * 3 + 1] = sw[j] > 0 ? s12[j] / sw[j] : 0;
            spread[j * 3 + 2] = sw[j] > 0 ? s22[j] / sw[j] : 0;
            total = spread[j * 3] + spread[j * 3 + 2];
            for (int c = 0; c < 3; c++) { basis[j * 6 + c] = u[j * 3 + c]; basis[j * 6 + 3 + c] = w[j * 3 + c]; }
        }
        standard_error[j] = n_eff[j] > 0 ? std::sqrt(total / n_eff[j]) : std::numeric_limits<double>::infinity();
        for (int c = 0; c < d; c++) tangent[j * d + c] = tan[j * d + c];
    }
}

}  // extern "C"
