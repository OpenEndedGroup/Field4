// Native checks for field_principal_curves on synthetic point clouds with known structure.
#include "field_principal_curves.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

struct Cloud { std::vector<double> xy; int d = 2; int n() const { return static_cast<int>(xy.size()) / d; } };

static std::mt19937_64 rng(42);
static double gauss(double s) { return std::normal_distribution<double>(0, s)(rng); }
static double unif(double a, double b) { return std::uniform_real_distribution<double>(a, b)(rng); }

static void add2(Cloud& c, double x, double y) { c.xy.push_back(x); c.xy.push_back(y); }

struct Graph {
    std::vector<double> nodes;
    std::vector<int> edges;
    std::vector<int> degree;
};

static Graph graph(fpc_forest* f, int d) {
    Graph g;
    int k = fpc_node_count(f), e = fpc_edge_count(f);
    g.nodes.resize(k * d);
    std::vector<int32_t> fixed(k);
    fpc_nodes(f, g.nodes.data(), fixed.data());
    g.edges.resize(2 * e);
    fpc_edges(f, g.edges.data());
    g.degree.assign(k, 0);
    for (int v : g.edges) g.degree[v]++;
    return g;
}

static int components(const Graph& g) {
    int k = static_cast<int>(g.degree.size());
    std::vector<int> p(k);
    for (int i = 0; i < k; i++) p[i] = i;
    auto find = [&](int v) { while (p[v] != v) v = p[v] = p[p[v]]; return v; };
    for (size_t i = 0; i < g.edges.size(); i += 2) p[find(g.edges[i])] = find(g.edges[i + 1]);
    int c = 0;
    for (int i = 0; i < k; i++) c += find(i) == i;
    return c;
}

static fpc_forest* make(const Cloud& c, fpc_params* p = nullptr) {
    char err[256];
    fpc_params def;
    fpc_default_params(&def);
    fpc_forest* f = fpc_create(c.d, c.xy.data(), nullptr, c.n(), p ? p : &def, err, sizeof err);
    if (!f) std::printf("create failed: %s\n", err);
    return f;
}

int main() {
    char err[256];
    CHECK(fpc_abi_version() == FPC_ABI_VERSION, "abi");

    // One noisy line: grown as a curve, its nodes lie on y = 0.5 x and the width matches the noise.
    {
        Cloud c;
        for (int i = 0; i < 4000; i++) { double t = unif(0, 100); add2(c, t + gauss(0.3), 0.5 * t + gauss(2.0) * 0.8944 ); }
        fpc_forest* f = make(c);
        CHECK(fpc_init_components(f, 1, 3, err, sizeof err) == 0, "%s", err);
        CHECK(fpc_grow(f, 16, FPC_BISECT | FPC_EXTEND, err, sizeof err) >= 0, "%s", err);
        Graph g = graph(f, 2);
        double worst = 0;
        for (size_t i = 0; i < g.nodes.size(); i += 2) {
            // distance to the line y = 0.5 x
            worst = std::max(worst, std::fabs(g.nodes[i + 1] - 0.5 * g.nodes[i]) / std::sqrt(1.25));
        }
        CHECK(worst < 0.6, "line: node off the line by %.3f", worst);
        CHECK(*std::max_element(g.degree.begin(), g.degree.end()) <= 2, "line grew a branch");
        int k = fpc_node_count(f);
        std::vector<double> sup(k), ne(k), res(k), spr(k), se(k), tan(2 * k), basis(1);
        fpc_node_stats(f, sup.data(), ne.data(), res.data(), spr.data(), se.data(), tan.data(), basis.data());
        // perpendicular noise: the y noise 1.789 projected onto the normal (0.894) plus x noise; sigma_perp^2 ~ 2.6
        double mid = spr[k / 2];
        std::vector<double> sorted(spr);
        std::sort(sorted.begin(), sorted.end());
        double median = sorted[k / 2];
        double expected = std::pow(1.789 * 0.8944, 2) + std::pow(0.3 * 0.4472, 2);
        CHECK(std::fabs(median - expected) / expected < 0.25, "line: median transverse variance %.3f, expected %.3f", median, expected);
        CHECK(se[k / 2] < std::sqrt(mid), "line: standard error %.3f not below spread %.3f", se[k / 2], std::sqrt(mid));
        fpc_free(f);
    }

    // Uniform band of half-width a: transverse variance a^2 / 3.
    {
        Cloud c;
        for (int i = 0; i < 6000; i++) add2(c, unif(0, 200), unif(-6, 6));
        fpc_forest* f = make(c);
        fpc_init_components(f, 1, 12, err, sizeof err);
        fpc_fit(f, 0, err, sizeof err);
        int k = fpc_node_count(f);
        std::vector<double> sup(k), ne(k), res(k), spr(k), se(k), tan(2 * k), basis(1);
        fpc_node_stats(f, sup.data(), ne.data(), res.data(), spr.data(), se.data(), tan.data(), basis.data());
        double v = spr[k / 2];
        CHECK(std::fabs(v - 12.0) / 12.0 < 0.15, "band: transverse variance %.2f, expected 12", v);
        fpc_free(f);
    }

    // Two crossing lines, fitted as two components: neither borrows a node from the other.
    {
        Cloud c;
        for (int i = 0; i < 3000; i++) { double t = unif(-50, 50); add2(c, t + gauss(0.5), t + gauss(0.5)); }
        for (int i = 0; i < 3000; i++) { double t = unif(-50, 50); add2(c, t + gauss(0.5), -t + gauss(0.5)); }
        // initialise from the two strokes an artist would draw, then grow only as curves
        double nodes[] = {-45, -45, 0, 0, 45, 45, -45, 45, 0.5, -0.5, 45, -45};
        int edges[] = {0, 1, 1, 2, 3, 4, 4, 5};
        fpc_forest* f = make(c);
        fpc_set_graph(f, 6, nodes, 4, edges, err, sizeof err);
        CHECK(fpc_grow(f, 24, FPC_BISECT | FPC_EXTEND, err, sizeof err) >= 0, "%s", err);
        Graph g = graph(f, 2);
        CHECK(components(g) == 2, "crossing: %d components, expected 2", components(g));
        CHECK(*std::max_element(g.degree.begin(), g.degree.end()) <= 2, "crossing became a branch");
        fpc_free(f);
    }

    // A Y: with branching allowed, a tree with exactly one node of degree 3; curves-only, no branch.
    {
        Cloud c;
        for (int i = 0; i < 6000; i++) {
            double t = unif(0, 60);
            int arm = i % 3;
            double ang = arm == 0 ? -M_PI / 2 : (arm == 1 ? M_PI / 2 - 0.6 : M_PI / 2 + 0.6);
            add2(c, t * std::cos(ang) + gauss(1.0), t * std::sin(ang) + gauss(1.0));
        }
        fpc_params p;
        fpc_default_params(&p);
        fpc_forest* f = make(c, &p);
        fpc_init_components(f, 1, 3, err, sizeof err);
        CHECK(fpc_grow(f, 22, FPC_BISECT | FPC_EXTEND | FPC_BRANCH, err, sizeof err) >= 0, "%s", err);
        Graph g = graph(f, 2);
        int deg3 = 0, deg4 = 0, leaves = 0;
        for (int dgr : g.degree) { deg3 += dgr == 3; deg4 += dgr > 3; leaves += dgr == 1; }
        CHECK(deg3 == 1 && deg4 == 0 && leaves == 3, "Y tree: %d degree-3, %d higher, %d leaves", deg3, deg4, leaves);
        fpc_free(f);
        f = make(c, &p);
        fpc_init_components(f, 1, 3, err, sizeof err);
        fpc_grow(f, 22, FPC_BISECT | FPC_EXTEND, err, sizeof err);
        g = graph(f, 2);
        CHECK(*std::max_element(g.degree.begin(), g.degree.end()) <= 2, "Y as curves grew a branch");
        fpc_free(f);
    }

    // Two disconnected families stay two components.
    {
        Cloud c;
        for (int i = 0; i < 2000; i++) { double t = unif(0, 40); add2(c, t + gauss(0.5), gauss(0.5)); }
        for (int i = 0; i < 2000; i++) { double t = unif(0, 40); add2(c, 200 + gauss(0.5), 100 + t + gauss(0.5)); }
        fpc_forest* f = make(c);
        fpc_init_components(f, 2, 3, err, sizeof err);
        fpc_grow(f, 16, FPC_BISECT | FPC_EXTEND, err, sizeof err);
        CHECK(components(graph(f, 2)) == 2, "families merged");
        fpc_free(f);
    }

    // Three strands of different widths, close together, over twelve samplings: no fitted edge may
    // join nodes that lie on different strands. With three-node starting chains, four of these
    // twelve once grew a hook from one strand into the next.
    for (int seed = 1; seed <= 12; seed++) {
        std::mt19937_64 r(seed);
        std::normal_distribution<double> gn;
        std::uniform_real_distribution<double> un;
        Cloud c;
        for (int i = 0; i < 3000; i++) {
            double t = un(r), x, y, s;
            if (i % 3 == 0) { x = 30 + 360 * t; y = 50 + 25 * std::sin(7 * t); s = 2; }
            else if (i % 3 == 1) { x = 40 + 300 * t; y = 130 + 40 * t * t; s = 7; }
            else { x = 330 + 50 * std::cos(3 * t); y = 200 + 50 * std::sin(3 * t); s = 3.5; }
            add2(c, x + s * gn(r), y + s * gn(r));
        }
        fpc_forest* f = make(c);
        fpc_init_components(f, 3, 7, err, sizeof err);
        fpc_grow(f, 45, FPC_BISECT | FPC_EXTEND, err, sizeof err);
        Graph g = graph(f, 2);
        int k = static_cast<int>(g.degree.size());
        std::vector<int> strand(k);
        for (int i = 0; i < k; i++) {
            double bd = 1e300;
            for (int j = 0; j < c.n(); j++) {
                double dx = c.xy[2 * j] - g.nodes[2 * i], dy = c.xy[2 * j + 1] - g.nodes[2 * i + 1];
                if (dx * dx + dy * dy < bd) { bd = dx * dx + dy * dy; strand[i] = j % 3; }
            }
        }
        int jumps = 0;
        for (size_t e = 0; e < g.edges.size(); e += 2) jumps += strand[g.edges[e]] != strand[g.edges[e + 1]];
        CHECK(jumps == 0 && components(g) == 3, "strands seed %d: %d jumping edges, %d components", seed, jumps, components(g));
        fpc_free(f);
    }

    // A dense strand and a sparse one, fitted as two curves of ten nodes each: the sparse one must
    // keep its length instead of collapsing under stiffness scaled for the dense one.
    {
        Cloud c;
        for (int i = 0; i < 4000; i++) add2(c, unif(20, 380), 60 + gauss(8));
        for (int i = 0; i < 40; i++) add2(c, unif(20, 380), 150 + gauss(1.5));
        std::vector<double> nodes;
        std::vector<int> edges;
        for (int s = 0; s < 2; s++) for (int i = 0; i < 10; i++) {
            nodes.push_back(30 + 340.0 * i / 9); nodes.push_back(s ? 150 : 60);
            if (i) { edges.push_back(s * 10 + i - 1); edges.push_back(s * 10 + i); }
        }
        fpc_forest* f = make(c);
        fpc_set_graph(f, 20, nodes.data(), 18, edges.data(), err, sizeof err);
        fpc_fit(f, 0, err, sizeof err);
        Graph g = graph(f, 2);
        double lo = 1e9, hi = -1e9;
        for (int i = 10; i < 20; i++) { lo = std::min(lo, g.nodes[2 * i]); hi = std::max(hi, g.nodes[2 * i]); }
        CHECK(hi - lo > 250, "sparse strand collapsed to %.0f of ~340", hi - lo);
        fpc_free(f);
    }

    // A loop from a closed initial graph.
    {
        Cloud c;
        for (int i = 0; i < 4000; i++) { double a = unif(0, 2 * M_PI); double r = 50 + gauss(1.0); add2(c, r * std::cos(a), r * std::sin(a)); }
        std::vector<double> nodes;
        std::vector<int> edges;
        for (int i = 0; i < 6; i++) { nodes.push_back(30 * std::cos(i * M_PI / 3)); nodes.push_back(30 * std::sin(i * M_PI / 3)); edges.push_back(i); edges.push_back((i + 1) % 6); }
        fpc_forest* f = make(c);
        fpc_set_graph(f, 6, nodes.data(), 6, edges.data(), err, sizeof err);
        fpc_grow(f, 30, FPC_BISECT, err, sizeof err);
        Graph g = graph(f, 2);
        double rmin = 1e9, rmax = 0;
        for (size_t i = 0; i < g.nodes.size(); i += 2) { double r = std::hypot(g.nodes[i], g.nodes[i + 1]); rmin = std::min(rmin, r); rmax = std::max(rmax, r); }
        CHECK(rmin > 46 && rmax < 52, "loop radius %.1f..%.1f", rmin, rmax);
        CHECK(components(g) == 1 && static_cast<int>(g.edges.size()) / 2 == fpc_node_count(f), "loop is not one cycle");
        fpc_free(f);
    }

    // Strong outliers: with trimming, the line is not dragged; without, it is.
    {
        Cloud c;
        for (int i = 0; i < 3000; i++) add2(c, unif(0, 100), gauss(0.5));
        for (int i = 0; i < 600; i++) add2(c, unif(0, 100), unif(40, 60));
        fpc_params p;
        fpc_default_params(&p);
        double drift[2];
        for (int trim = 0; trim < 2; trim++) {
            p.trimming_radius = trim ? 8 : 0;
            fpc_forest* f = make(c, &p);
            double nodes[] = {0, 0, 50, 0, 100, 0};
            int edges[] = {0, 1, 1, 2};
            fpc_set_graph(f, 3, nodes, 2, edges, err, sizeof err);
            fpc_grow(f, 12, FPC_BISECT | FPC_EXTEND, err, sizeof err);
            Graph g = graph(f, 2);
            double m = 0;
            for (size_t i = 1; i < g.nodes.size(); i += 2) m = std::max(m, std::fabs(g.nodes[i]));
            drift[trim] = m;
            fpc_free(f);
        }
        CHECK(drift[1] < 2.0 && drift[0] > drift[1] * 2, "outliers: drift untrimmed %.2f trimmed %.2f", drift[0], drift[1]);
    }

    // An anisotropic 3D tube: sigma 3 along one normal, 1 along the other.
    {
        Cloud c;
        c.d = 3;
        for (int i = 0; i < 8000; i++) { c.xy.push_back(unif(0, 200)); c.xy.push_back(gauss(3.0)); c.xy.push_back(gauss(1.0)); }
        fpc_forest* f = make(c);
        fpc_init_components(f, 1, 10, err, sizeof err);
        fpc_fit(f, 0, err, sizeof err);
        int k = fpc_node_count(f);
        std::vector<double> sup(k), ne(k), res(k), spr(3 * k), se(k), tan(3 * k), basis(6 * k);
        fpc_node_stats(f, sup.data(), ne.data(), res.data(), spr.data(), se.data(), tan.data(), basis.data());
        int j = k / 2;
        double a = spr[3 * j], b = spr[3 * j + 1], cc = spr[3 * j + 2];
        double tr = a + cc, det = a * cc - b * b;
        double l1 = tr / 2 + std::sqrt(tr * tr / 4 - det), l2 = tr / 2 - std::sqrt(tr * tr / 4 - det);
        CHECK(std::fabs(l1 - 9) < 1.2 && std::fabs(l2 - 1) < 0.25, "tube covariance eigenvalues %.2f %.2f, expected 9 and 1", l1, l2);
        fpc_free(f);
    }

    // Errors and edits.
    {
        Cloud c;
        for (int i = 0; i < 100; i++) add2(c, i, 0);
        fpc_forest* f = make(c);
        CHECK(fpc_fit(f, 0, err, sizeof err) < 0, "fit without a graph accepted");
        double nodes[] = {0, 0, 99, 0};
        int bad[] = {0, 5};
        CHECK(fpc_set_graph(f, 2, nodes, 1, bad, err, sizeof err) < 0, "bad edge accepted");
        int good[] = {0, 1};
        fpc_set_graph(f, 2, nodes, 1, good, err, sizeof err);
        int m = fpc_split_edge(f, 0, 0.25, err, sizeof err);
        CHECK(m == 2 && fpc_edge_count(f) == 2, "split edge");
        double pin[] = {10, 20};
        fpc_set_node(f, 0, pin, 1, err, sizeof err);
        fpc_fit(f, 0, err, sizeof err);
        Graph g = graph(f, 2);
        CHECK(g.nodes[0] == 10 && g.nodes[1] == 20, "fixed node moved");
        double nan[] = {0, NAN, 1, 1};
        CHECK(fpc_create(2, nan, nullptr, 2, nullptr, err, sizeof err) == nullptr, "NaN accepted");
        fpc_free(f);
    }

    if (failures) { std::printf("%d failure(s)\n", failures); return 1; }
    std::printf("field_principal_curves_test: ok\n");
    return 0;
}
