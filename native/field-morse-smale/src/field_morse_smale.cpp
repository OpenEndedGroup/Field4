// The discrete Morse–Smale complex of a 2D scalar grid. See field_morse_smale.h for the
// discretisation, cell ids, ownership rules and the error convention.
//
// Outline.
//   1. Order every vertex by (value, index): simulation of simplicity, so no two vertices tie.
//   2. Build Forman's discrete gradient with ProcessLowerStars (Robins, Wood & Sheppard 2011): each
//      vertex's lower star is paired off independently, and what stays unpaired is critical. A
//      critical vertex is a minimum, a critical edge a saddle, a critical triangle a maximum.
//   3. From each saddle, follow the two descending V-paths (vertex–edge pairs) to minima and the
//      two ascending V-paths (edge–triangle pairs, through each edge's other triangle) to maxima.
//      These separatrices are the arcs of the Morse–Smale graph.
//   4. Cancel saddle–extremum arcs in order of persistence |f(s) - f(e)|, as Gyulassy et al.
//      (2008) describe: an arc s–e may be cancelled when it is the only arc joining s and e. Every
//      other arc reaching e is rerouted through s to s's remaining extrema. The whole sequence is
//      computed once. A threshold replays a prefix of it on a fresh copy of the base graph.
//   5. Separately, compute the persistence diagram of the lower-star filtration by union-find.
//
// Rerouted arcs are stored as compositions of three earlier arcs, not as copied paths, so a
// cancellation costs O(1). A path is materialised only when a view is read.

#include "field_morse_smale.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <numeric>
#include <queue>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr double INF = std::numeric_limits<double>::infinity();

void set_error(char* error, int32_t capacity, const std::string& message) {
    if (!error || capacity <= 0) return;
    std::strncpy(error, message.c_str(), static_cast<size_t>(capacity) - 1);
    error[capacity - 1] = '\0';
}

struct Arc {
    int saddle;    // node index
    int extremum;  // node index
    int kind;      // FMS_DESCENDING / FMS_ASCENDING
    double persistence;
    // Either a base path (base >= 0) or the composition first + reverse(middle) + last.
    int base = -1;
    int first = -1, middle = -1, last = -1;
    bool alive = true;
};

struct Node {
    int cell;
    int type;
    std::vector<int> arcs;
    bool alive = true;
};

struct Graph {
    std::vector<Node> nodes;
    std::vector<Arc> arcs;
};

struct Cancellation {
    int arc;
    int saddle_cell;
    int extremum_cell;
    double persistence;
};

struct Pair {
    int birth, death, dimension;
    double persistence;
};

using Point = std::array<float, 2>;

}  // namespace

// Grid topology: vertex, edge and triangle indices, their incidences, and the gradient on them.
struct fms_complex {
    int width = 0, height = 0;
    std::vector<float> values;
    std::vector<int32_t> rank;

    // gradient pairs; -1 = unpaired
    std::vector<int32_t> vpair;  // vertex -> edge index
    std::vector<int32_t> e2v;    // edge index -> vertex
    std::vector<int32_t> e2t;    // edge index -> triangle index
    std::vector<int32_t> t2e;    // triangle index -> edge index

    std::vector<std::vector<Point>> base_paths;
    Graph base;
    std::unordered_map<int, int> node_of_cell;
    std::vector<double> cancelled_at;  // per node
    std::vector<Cancellation> cancellations;
    std::vector<Pair> pairs;
    std::vector<int32_t> basin0;  // per vertex: minimum cell id
    std::vector<int32_t> peak0;   // per triangle: maximum cell id, or -1
    double timings[4] = {0, 0, 0, 0};

    int n() const { return width * height; }
    int x_of(int v) const { return v % width; }
    int y_of(int v) const { return v / width; }

    bool edge_exists(int e) const {
        int v = e / 3, k = e % 3, x = x_of(v), y = y_of(v);
        if (k == 0) return x < width - 1;
        if (k == 1) return y < height - 1;
        return x < width - 1 && y < height - 1;
    }
    std::array<int, 2> edge_vertices(int e) const {
        int v = e / 3, k = e % 3;
        return {v, k == 0 ? v + 1 : k == 1 ? v + width : v + width + 1};
    }
    // up to two triangles containing edge e; -1 where the grid ends
    std::array<int, 2> edge_cofaces(int e) const {
        int v = e / 3, k = e % 3, x = x_of(v), y = y_of(v);
        if (k == 0) return {y < height - 1 ? 2 * v : -1, y > 0 ? 2 * (v - width) + 1 : -1};
        if (k == 1) return {x < width - 1 ? 2 * v + 1 : -1, x > 0 ? 2 * (v - 1) : -1};
        return {2 * v, 2 * v + 1};
    }
    std::array<int, 3> triangle_vertices(int t) const {
        int v = t / 2;
        return t % 2 == 0 ? std::array<int, 3>{v, v + 1, v + width + 1} : std::array<int, 3>{v, v + width, v + width + 1};
    }
    std::array<int, 3> triangle_edges(int t) const {
        int v = t / 2;
        return t % 2 == 0 ? std::array<int, 3>{3 * v, 3 * (v + 1) + 1, 3 * v + 2}
                          : std::array<int, 3>{3 * v + 1, 3 * (v + width), 3 * v + 2};
    }
    int edge_top(int e) const {
        auto [a, b] = edge_vertices(e);
        return rank[a] > rank[b] ? a : b;
    }
    int triangle_top(int t) const {
        auto vs = triangle_vertices(t);
        int best = vs[0];
        for (int v : vs) if (rank[v] > rank[best]) best = v;
        return best;
    }

    int edge_id(int e) const { return n() + e; }
    int triangle_id(int t) const { return 4 * n() + t; }
    // the vertex whose value a cell carries
    int top_of_cell(int cell) const {
        if (cell < n()) return cell;
        if (cell < 4 * n()) return edge_top(cell - n());
        return triangle_top(cell - 4 * n());
    }
    // cell -1 is the outside of the grid, a virtual maximum at +inf
    double value_of_cell(int cell) const { return cell < 0 ? INF : values[top_of_cell(cell)]; }
    Point position_of_cell(int cell) const {
        if (cell < n()) return {static_cast<float>(x_of(cell)), static_cast<float>(y_of(cell))};
        if (cell < 4 * n()) {
            auto [a, b] = edge_vertices(cell - n());
            return {0.5f * (x_of(a) + x_of(b)), 0.5f * (y_of(a) + y_of(b))};
        }
        auto vs = triangle_vertices(cell - 4 * n());
        float x = 0, y = 0;
        for (int v : vs) { x += x_of(v); y += y_of(v); }
        return {x / 3, y / 3};
    }
};

struct fms_view {
    std::vector<int32_t> nodes;
    std::vector<int32_t> arc_saddle, arc_extremum, arc_kind, arc_length;
    std::vector<float> paths;
    std::vector<int32_t> basin, peak;
};

namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

// Neighbour directions around a vertex in cyclic order, for the Freudenthal split along (+1, +1).
constexpr int DX[6] = {1, 1, 0, -1, -1, 0};
constexpr int DY[6] = {0, 1, 1, 0, -1, -1};

// The edge from x towards direction d, and the triangle between directions d and d + 1.
int edge_towards(const fms_complex& c, int x, int d) {
    const int w = c.width;
    switch (d) {
        case 0: return 3 * x;
        case 1: return 3 * x + 2;
        case 2: return 3 * x + 1;
        case 3: return 3 * (x - 1);
        case 4: return 3 * (x - w - 1) + 2;
        default: return 3 * (x - w) + 1;
    }
}
int triangle_between(const fms_complex& c, int x, int d) {
    const int w = c.width;
    switch (d) {
        case 0: return 2 * x;
        case 1: return 2 * x + 1;
        case 2: return 2 * (x - 1);
        case 3: return 2 * (x - w - 1) + 1;
        case 4: return 2 * (x - w - 1);
        default: return 2 * (x - w) + 1;
    }
}

// ProcessLowerStars for one vertex. Within a lower star every cell's highest vertex is x, so cells
// are ordered by their remaining vertex ranks, descending, compared lexicographically. An edge
// (x, w) has key (rank w), a triangle (x, a, b) has key (max, min). Packed into one integer, an
// edge sorts before a triangle whose highest other vertex is the same w.
void process_lower_star(fms_complex& c, int x, std::vector<int>& crit_vertices, std::vector<int>& crit_edges,
                        std::vector<int>& crit_triangles) {
    const int64_t scale = static_cast<int64_t>(c.n()) + 1;
    int nb[6];
    bool lower[6];
    const int px = c.x_of(x), py = c.y_of(x);
    for (int d = 0; d < 6; d++) {
        int nx = px + DX[d], ny = py + DY[d];
        nb[d] = (nx >= 0 && ny >= 0 && nx < c.width && ny < c.height) ? ny * c.width + nx : -1;
        lower[d] = nb[d] >= 0 && c.rank[nb[d]] < c.rank[x];
    }
    struct Cell { int index; bool triangle; int64_t key; int faces[2]; bool done; };
    Cell cells[12];
    int count = 0;
    int edge_slot[6];
    for (int d = 0; d < 6; d++) {
        edge_slot[d] = -1;
        if (!lower[d]) continue;
        edge_slot[d] = count;
        cells[count++] = {edge_towards(c, x, d), false, static_cast<int64_t>(c.rank[nb[d]]) * scale, {-1, -1}, false};
    }
    if (count == 0) {
        crit_vertices.push_back(x);
        return;
    }
    for (int d = 0; d < 6; d++) {
        int d2 = (d + 1) % 6;
        if (!lower[d] || !lower[d2]) continue;
        int64_t hi = std::max(c.rank[nb[d]], c.rank[nb[d2]]), lo = std::min(c.rank[nb[d]], c.rank[nb[d2]]);
        cells[count++] = {triangle_between(c, x, d), true, hi * scale + lo + 1, {edge_slot[d], edge_slot[d2]}, false};
    }
    auto unpaired_faces = [&](const Cell& t, int& which) {
        int n = 0;
        for (int f : t.faces) if (!cells[f].done) { n++; which = f; }
        return n;
    };
    // the minimal edge pairs with x
    int delta = -1;
    for (int i = 0; i < count; i++)
        if (!cells[i].triangle && (delta < 0 || cells[i].key < cells[delta].key)) delta = i;
    c.vpair[x] = cells[delta].index;
    c.e2v[cells[delta].index] = x;
    cells[delta].done = true;

    // Lower stars hold at most 12 cells, so the two priority queues are linear scans over flags.
    bool in_zero[12] = {}, in_one[12] = {};
    for (int i = 0; i < count; i++) if (!cells[i].triangle && i != delta) in_zero[i] = true;
    auto push_cofaces = [&](int edge_slot_index) {
        for (int i = 0; i < count; i++) {
            if (!cells[i].triangle || cells[i].done) continue;
            if (cells[i].faces[0] != edge_slot_index && cells[i].faces[1] != edge_slot_index) continue;
            int which;
            if (unpaired_faces(cells[i], which) == 1) in_one[i] = true;
        }
    };
    push_cofaces(delta);
    auto pop_min = [&](bool* queue) {
        int best = -1;
        for (int i = 0; i < count; i++)
            if (queue[i] && (best < 0 || cells[i].key < cells[best].key)) best = i;
        if (best >= 0) queue[best] = false;
        return best;
    };
    for (;;) {
        int alpha;
        while ((alpha = pop_min(in_one)) >= 0) {
            if (cells[alpha].done) continue;
            int which = -1;
            int n = unpaired_faces(cells[alpha], which);
            if (n == 0) { in_zero[alpha] = true; continue; }
            // pair the triangle with its single unpaired edge
            c.e2t[cells[which].index] = cells[alpha].index;
            c.t2e[cells[alpha].index] = cells[which].index;
            cells[which].done = cells[alpha].done = true;
            in_zero[which] = false;
            push_cofaces(which);
        }
        int gamma = pop_min(in_zero);
        if (gamma < 0) break;
        if (cells[gamma].done) continue;
        cells[gamma].done = true;
        if (cells[gamma].triangle) crit_triangles.push_back(cells[gamma].index);
        else {
            crit_edges.push_back(cells[gamma].index);
            push_cofaces(gamma);
        }
    }
}

void build_gradient_and_arcs(fms_complex& c) {
    auto t0 = Clock::now();
    const int n = c.n();
    c.vpair.assign(n, -1);
    c.e2v.assign(3 * n, -1);
    c.e2t.assign(3 * n, -1);
    c.t2e.assign(2 * n, -1);
    std::vector<int> crit_v, crit_e, crit_t;
    for (int x = 0; x < n; x++) process_lower_star(c, x, crit_v, crit_e, crit_t);
    auto add_node = [&](int cell, int type) {
        c.node_of_cell[cell] = static_cast<int>(c.base.nodes.size());
        c.base.nodes.push_back(Node{cell, type, {}, true});
    };
    for (int v : crit_v) add_node(v, FMS_MINIMUM);
    for (int e : crit_e) add_node(c.edge_id(e), FMS_SADDLE);
    for (int t : crit_t) add_node(c.triangle_id(t), FMS_MAXIMUM);
    // The outside of the grid as one maximum at +inf: ascending V-paths that leave through the
    // boundary end here. It makes the disc a sphere, so every saddle has two ascending arcs and a
    // maximum next to the boundary can be cancelled into the outside. It is never itself cancelled.
    add_node(-1, FMS_MAXIMUM);
    c.timings[0] = ms_since(t0);

    auto t1 = Clock::now();
    for (int e : crit_e) {
        const int saddle = c.node_of_cell.at(c.edge_id(e));
        const Point start = c.position_of_cell(c.edge_id(e));
        auto add_arc = [&](std::vector<Point> path, int end_cell, int kind) {
            int path_id = static_cast<int>(c.base_paths.size());
            c.base_paths.push_back(std::move(path));
            int end = c.node_of_cell.at(end_cell);
            Arc a;
            a.saddle = saddle;
            a.extremum = end;
            a.kind = kind;
            a.persistence = std::fabs(c.value_of_cell(end_cell) - c.value_of_cell(c.edge_id(e)));
            a.base = path_id;
            int id = static_cast<int>(c.base.arcs.size());
            c.base.arcs.push_back(a);
            c.base.nodes[saddle].arcs.push_back(id);
            c.base.nodes[end].arcs.push_back(id);
        };
        // descending: from each endpoint, along vertex–edge pairs to a minimum
        for (int u : c.edge_vertices(e)) {
            std::vector<Point> path{start, c.position_of_cell(u)};
            while (c.vpair[u] >= 0) {
                auto [a, b] = c.edge_vertices(c.vpair[u]);
                u = a == u ? b : a;
                path.push_back(c.position_of_cell(u));
            }
            add_arc(std::move(path), u, FMS_DESCENDING);
        }
        // ascending: into each triangle, then out through its paired edge into the next triangle
        for (int t : c.edge_cofaces(e)) {
            if (t < 0) continue;
            std::vector<Point> path{start, c.position_of_cell(c.triangle_id(t))};
            int end = -1;
            for (;;) {
                int out = c.t2e[t];
                if (out < 0) { end = c.triangle_id(t); break; }
                auto co = c.edge_cofaces(out);
                int next = co[0] == t ? co[1] : co[0];
                path.push_back(c.position_of_cell(c.edge_id(out)));
                if (next < 0) break;  // the V-path leaves the grid
                t = next;
                path.push_back(c.position_of_cell(c.triangle_id(t)));
            }
            add_arc(std::move(path), end, FMS_ASCENDING);
        }
    }

    // Unsimplified segmentation. A vertex is paired with an edge to a lower vertex, so increasing
    // rank order sees the target first. Triangles follow their V-paths, memoised.
    std::vector<int32_t> order(n);
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](int a, int b) { return c.rank[a] < c.rank[b]; });
    c.basin0.assign(n, -1);
    for (int v : order) {
        if (c.vpair[v] < 0) { c.basin0[v] = v; continue; }
        auto [a, b] = c.edge_vertices(c.vpair[v]);
        c.basin0[v] = c.basin0[a == v ? b : a];
    }
    c.peak0.assign(2 * n, -2);  // -2 = not yet known
    std::vector<int> chain;
    for (int t0 = 0; t0 < 2 * n; t0++) {
        if (c.peak0[t0] != -2 || c.x_of(t0 / 2) >= c.width - 1 || c.y_of(t0 / 2) >= c.height - 1) continue;
        chain.clear();
        int t = t0, label = -1;
        for (;;) {
            if (c.peak0[t] != -2) { label = c.peak0[t]; break; }
            chain.push_back(t);
            int out = c.t2e[t];
            if (out < 0) { label = c.triangle_id(t); break; }
            auto co = c.edge_cofaces(out);
            int next = co[0] == t ? co[1] : co[0];
            if (next < 0) { label = -1; break; }
            t = next;
        }
        for (int u : chain) c.peak0[u] = label;
    }
    c.timings[1] = ms_since(t1);
}

template <typename F>
void each_arc(const Graph& g, int node, int kind, F f) {
    for (int a : g.nodes[node].arcs)
        if (g.arcs[a].alive && g.arcs[a].kind == kind) f(a);
}

// Is arc a cancellable in g? Its endpoints are alive, it is the only arc joining them, and its
// saddle has at least one other arc of the same kind for the extremum's other arcs to reroute to.
bool cancellable(const Graph& g, int a) {
    const Arc& arc = g.arcs[a];
    if (g.nodes[arc.extremum].cell < 0) return false;
    if (!arc.alive || !g.nodes[arc.saddle].alive || !g.nodes[arc.extremum].alive) return false;
    int joining = 0, others = 0;
    each_arc(g, arc.saddle, arc.kind, [&](int b) {
        if (g.arcs[b].extremum == arc.extremum) joining++; else others++;
    });
    return joining == 1 && others >= 1;
}

// Applies the cancellation of arc a; new arcs are appended to g.arcs and reported to on_new.
// Returns the extremum node the cancelled extremum's region merges into.
template <typename F>
int cancel(const fms_complex& c, Graph& g, int a, F on_new) {
    const Arc arc = g.arcs[a];
    std::vector<int> saddle_others, extremum_others;
    each_arc(g, arc.saddle, arc.kind, [&](int b) { if (b != a) saddle_others.push_back(b); });
    each_arc(g, arc.extremum, arc.kind, [&](int b) { if (b != a) extremum_others.push_back(b); });
    int merge_into = g.arcs[saddle_others.front()].extremum;
    for (int x : extremum_others) {
        for (int y : saddle_others) {
            Arc na;
            na.saddle = g.arcs[x].saddle;
            na.extremum = g.arcs[y].extremum;
            na.kind = arc.kind;
            na.persistence = std::fabs(c.value_of_cell(g.nodes[na.extremum].cell) - c.value_of_cell(g.nodes[na.saddle].cell));
            na.first = x;
            na.middle = a;
            na.last = y;
            int id = static_cast<int>(g.arcs.size());
            g.arcs.push_back(na);
            g.nodes[na.saddle].arcs.push_back(id);
            g.nodes[na.extremum].arcs.push_back(id);
            on_new(id);
        }
    }
    for (int node : {arc.saddle, arc.extremum}) {
        g.nodes[node].alive = false;
        for (int b : g.nodes[node].arcs) g.arcs[b].alive = false;
    }
    return merge_into;
}

void build_hierarchy(fms_complex& c) {
    auto t0 = Clock::now();
    Graph g = c.base;
    // ties broken by arc index, so the sequence is deterministic
    using Item = std::pair<double, int>;
    std::priority_queue<Item, std::vector<Item>, std::greater<Item>> queue;
    for (size_t a = 0; a < g.arcs.size(); a++) queue.push({g.arcs[a].persistence, static_cast<int>(a)});
    c.cancelled_at.assign(c.base.nodes.size(), INF);
    double running = 0;
    while (!queue.empty()) {
        auto [p, a] = queue.top();
        queue.pop();
        if (!cancellable(g, a)) continue;
        running = std::max(running, p);
        const Arc arc = g.arcs[a];
        c.cancellations.push_back({a, g.nodes[arc.saddle].cell, g.nodes[arc.extremum].cell, running});
        c.cancelled_at[arc.saddle] = running;
        c.cancelled_at[arc.extremum] = running;
        cancel(c, g, a, [&](int id) { queue.push({g.arcs[id].persistence, id}); });
    }
    c.timings[2] = ms_since(t0);
}

struct UnionFind {
    std::vector<int32_t> parent;
    explicit UnionFind(int n) : parent(n) { std::iota(parent.begin(), parent.end(), 0); }
    int find(int v) {
        int r = v;
        while (parent[r] != r) r = parent[r];
        while (parent[v] != r) { int next = parent[v]; parent[v] = r; v = next; }
        return r;
    }
};

// Persistence of the lower-star filtration. Pairs inside one lower star have zero persistence
// and are the gradient's own pairs, so only pairs whose cells have different top vertices are kept.
void persistence_pairs(fms_complex& c) {
    const int n = c.n();
    std::vector<int32_t> order(n);
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](int a, int b) { return c.rank[a] < c.rank[b]; });

    // dimension 0: components of the sublevel set; each root remembers its oldest vertex
    {
        UnionFind uf(n);
        std::vector<int32_t> oldest(n);
        std::iota(oldest.begin(), oldest.end(), 0);
        int nb[6];
        std::vector<std::pair<int, int>> lower;  // (rank w, edge)
        for (int x : order) {
            lower.clear();
            for (int d = 0; d < 6; d++) {
                int nx = c.x_of(x) + DX[d], ny = c.y_of(x) + DY[d];
                nb[d] = (nx >= 0 && ny >= 0 && nx < c.width && ny < c.height) ? ny * c.width + nx : -1;
                if (nb[d] >= 0 && c.rank[nb[d]] < c.rank[x]) lower.push_back({c.rank[nb[d]], d});
            }
            std::sort(lower.begin(), lower.end());
            for (auto [r, d] : lower) {
                int a = uf.find(x), b = uf.find(nb[d]);
                if (a == b) continue;
                int young = c.rank[oldest[a]] > c.rank[oldest[b]] ? a : b;
                int old = young == a ? b : a;
                if (oldest[young] != x) {
                    int e = edge_towards(c, x, d);
                    c.pairs.push_back({oldest[young], c.edge_id(e), 0,
                                       static_cast<double>(c.values[x]) - c.values[oldest[young]]});
                }
                uf.parent[young] = old;
            }
        }
        c.pairs.push_back({order.front(), -1, 0, INF});
    }

    // dimension 1: components of the dual graph (triangles, plus node 2n for the outside),
    // processed from the top of the filtration down. An edge joining two dual components kills
    // the younger, which is the triangle born later. The filtration order is lexicographic with the
    // top vertex first, so walking vertices in decreasing rank and sorting each lower star (at most
    // twelve cells) visits cells in exactly that order without a global sort.
    {
        const int outside = 2 * n;
        UnionFind uf(2 * n + 1);
        std::vector<int32_t> eldest(2 * n + 1);
        std::iota(eldest.begin(), eldest.end(), 0);
        std::vector<int32_t> born(2 * n + 1, -1);  // processing order of each triangle; outside is oldest
        int32_t clock = 0;
        born[outside] = clock++;
        struct Cell { int64_t mid, lo; int index; bool triangle; };
        int nb[6];
        for (auto it = order.rbegin(); it != order.rend(); ++it) {
            const int x = *it;
            Cell cells[12];
            int count = 0;
            bool lower[6];
            for (int d = 0; d < 6; d++) {
                int nx = c.x_of(x) + DX[d], ny = c.y_of(x) + DY[d];
                nb[d] = (nx >= 0 && ny >= 0 && nx < c.width && ny < c.height) ? ny * c.width + nx : -1;
                lower[d] = nb[d] >= 0 && c.rank[nb[d]] < c.rank[x];
                if (lower[d]) cells[count++] = {c.rank[nb[d]], 0, edge_towards(c, x, d), false};
            }
            for (int d = 0; d < 6; d++) {
                int d2 = (d + 1) % 6;
                if (!lower[d] || !lower[d2]) continue;
                int64_t hi = std::max(c.rank[nb[d]], c.rank[nb[d2]]), lo = std::min(c.rank[nb[d]], c.rank[nb[d2]]);
                cells[count++] = {hi, lo + 1, triangle_between(c, x, d), true};
            }
            std::sort(cells, cells + count, [](const Cell& p, const Cell& q) {
                return p.mid != q.mid ? p.mid > q.mid : p.lo > q.lo;
            });
            for (int i = 0; i < count; i++) {
                const Cell& cell = cells[i];
                if (cell.triangle) { born[cell.index] = clock++; continue; }
                auto co = c.edge_cofaces(cell.index);
                int p = co[0] < 0 ? outside : co[0], q = co[1] < 0 ? outside : co[1];
                int a = uf.find(p), b = uf.find(q);
                if (a == b) continue;
                int young = born[eldest[a]] < born[eldest[b]] ? b : a;
                int old = young == a ? b : a;
                int dying = eldest[young];
                if (c.triangle_top(dying) != x) {
                    c.pairs.push_back({c.edge_id(cell.index), c.triangle_id(dying), 1,
                                       static_cast<double>(c.values[c.triangle_top(dying)]) - c.values[x]});
                }
                uf.parent[young] = old;
            }
        }
    }
}

// Writes the path of arc as x, y pairs. A rerouted arc runs s' -> e -> s -> m, and the two
// V-paths into e share their last stretch (V-paths into an extremum form a tree), so the raw
// concatenation goes out to e and comes back over the same cells. A separatrix never revisits a
// point, so every loop is cut as the path is written: the drawing loses the out-and-back spur and
// the arc keeps its endpoints and its place in the graph.
void materialise(const fms_complex& c, const Graph& g, int arc, std::vector<float>& out) {
    std::vector<std::pair<int, bool>> stack{{arc, false}};
    std::unordered_map<uint64_t, size_t> seen;  // point -> its index in out, in points
    auto key = [](const Point& p) {
        uint32_t a, b;
        std::memcpy(&a, &p[0], 4);
        std::memcpy(&b, &p[1], 4);
        return (static_cast<uint64_t>(a) << 32) | b;
    };
    auto emit = [&](const Point& p) {
        uint64_t k = key(p);
        auto it = seen.find(k);
        if (it != seen.end()) {
            size_t keep = it->second + 1;
            for (size_t i = keep; i < out.size() / 2; i++) seen.erase(key({out[2 * i], out[2 * i + 1]}));
            out.resize(2 * keep);
            return;
        }
        seen.emplace(k, out.size() / 2);
        out.push_back(p[0]);
        out.push_back(p[1]);
    };
    while (!stack.empty()) {
        auto [a, rev] = stack.back();
        stack.pop_back();
        const Arc& x = g.arcs[a];
        if (x.base >= 0) {
            const auto& p = c.base_paths[x.base];
            if (!rev) for (const Point& q : p) emit(q);
            else for (auto it = p.rbegin(); it != p.rend(); ++it) emit(*it);
            continue;
        }
        if (!rev) {
            stack.push_back({x.last, false});
            stack.push_back({x.middle, true});
            stack.push_back({x.first, false});
        } else {
            stack.push_back({x.first, true});
            stack.push_back({x.middle, false});
            stack.push_back({x.last, true});
        }
    }
}

int resolve(std::unordered_map<int, int>& merged, int v) {
    if (v < 0) return v;
    auto it = merged.find(v);
    if (it == merged.end()) return v;
    int r = resolve(merged, it->second);
    it->second = r;
    return r;
}

}  // namespace

extern "C" {

int32_t fms_abi_version(void) { return FMS_ABI_VERSION; }
const char* fms_version_string(void) {
    return "field_morse_smale 2.0 (Forman gradient by ProcessLowerStars on the Freudenthal grid, Gyulassy cancellation)";
}

fms_complex* fms_build(const float* values, int32_t width, int32_t height, int32_t pixel_stride, int32_t row_stride,
                       char* error, int32_t error_capacity) {
    try {
        if (!values) throw std::invalid_argument("values is null");
        if (width < 2 || height < 2)
            throw std::invalid_argument("grid must be at least 2x2, got " + std::to_string(width) + "x" + std::to_string(height));
        if (pixel_stride < 1 || row_stride < (width - 1) * pixel_stride + 1)
            throw std::invalid_argument("bad strides: pixel_stride=" + std::to_string(pixel_stride) +
                                        " row_stride=" + std::to_string(row_stride));
        if (static_cast<int64_t>(width) * height > std::numeric_limits<int32_t>::max() / 5)
            throw std::invalid_argument("grid too large for 32-bit cell ids");
        auto c = std::make_unique<fms_complex>();
        c->width = width;
        c->height = height;
        const int n = width * height;
        c->values.resize(n);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                float v = values[static_cast<int64_t>(y) * row_stride + static_cast<int64_t>(x) * pixel_stride];
                if (std::isnan(v)) throw std::invalid_argument("NaN at (" + std::to_string(x) + ", " + std::to_string(y) + ")");
                c->values[y * width + x] = v;
            }
        std::vector<int32_t> order(n);
        std::iota(order.begin(), order.end(), 0);
        std::sort(order.begin(), order.end(), [&](int a, int b) {
            return c->values[a] < c->values[b] || (c->values[a] == c->values[b] && a < b);
        });
        c->rank.resize(n);
        for (int i = 0; i < n; i++) c->rank[order[i]] = i;

        build_gradient_and_arcs(*c);
        build_hierarchy(*c);
        auto t = Clock::now();
        persistence_pairs(*c);
        c->timings[3] = ms_since(t);
        return c.release();
    } catch (const std::exception& e) {
        set_error(error, error_capacity, e.what());
        return nullptr;
    }
}

void fms_free(fms_complex* c) { delete c; }

void fms_timings(const fms_complex* c, double* out) { std::memcpy(out, c->timings, sizeof(c->timings)); }

// The virtual outside node is last, and not reported.
int32_t fms_critical_count(const fms_complex* c) { return static_cast<int32_t>(c->base.nodes.size()) - 1; }

void fms_critical_points(const fms_complex* c, int32_t* cell, int32_t* type, float* value, double* cancelled_at, float* xy) {
    for (size_t i = 0; i + 1 < c->base.nodes.size(); i++) {
        const Node& n = c->base.nodes[i];
        cell[i] = n.cell;
        type[i] = n.type;
        value[i] = static_cast<float>(c->value_of_cell(n.cell));
        cancelled_at[i] = c->cancelled_at[i];
        Point p = c->position_of_cell(n.cell);
        xy[2 * i] = p[0];
        xy[2 * i + 1] = p[1];
    }
}

int32_t fms_cancellation_count(const fms_complex* c) { return static_cast<int32_t>(c->cancellations.size()); }

void fms_cancellations(const fms_complex* c, int32_t* saddle, int32_t* extremum, double* persistence) {
    for (size_t i = 0; i < c->cancellations.size(); i++) {
        saddle[i] = c->cancellations[i].saddle_cell;
        extremum[i] = c->cancellations[i].extremum_cell;
        persistence[i] = c->cancellations[i].persistence;
    }
}

int32_t fms_pair_count(const fms_complex* c) { return static_cast<int32_t>(c->pairs.size()); }

void fms_pairs(const fms_complex* c, int32_t* birth, int32_t* death, int32_t* dimension, double* persistence) {
    for (size_t i = 0; i < c->pairs.size(); i++) {
        birth[i] = c->pairs[i].birth;
        death[i] = c->pairs[i].death;
        dimension[i] = c->pairs[i].dimension;
        persistence[i] = c->pairs[i].persistence;
    }
}

fms_view* fms_simplify(const fms_complex* c, double threshold, char* error, int32_t error_capacity) {
    try {
        if (std::isnan(threshold)) throw std::invalid_argument("threshold is NaN");
        Graph g = c->base;
        std::unordered_map<int, int> merged;  // cancelled extremum cell -> extremum cell it merged into
        for (const auto& k : c->cancellations) {
            if (!(k.persistence < threshold)) break;  // also stops at +inf
            const Arc arc = g.arcs[k.arc];
            int into = cancel(*c, g, k.arc, [](int) {});
            merged[g.nodes[arc.extremum].cell] = g.nodes[into].cell;
        }
        auto v = std::make_unique<fms_view>();
        for (const Node& n : g.nodes) if (n.alive && n.cell >= 0) v->nodes.push_back(n.cell);
        std::vector<float> path;
        auto add = [&](int saddle_cell, int extremum_cell, int kind) {
            v->arc_saddle.push_back(saddle_cell);
            v->arc_extremum.push_back(extremum_cell);
            v->arc_kind.push_back(kind);
            v->arc_length.push_back(static_cast<int32_t>(path.size() / 2));
            v->paths.insert(v->paths.end(), path.begin(), path.end());
        };
        for (size_t a = 0; a < g.arcs.size(); a++) {
            const Arc& arc = g.arcs[a];
            if (!arc.alive) continue;
            path.clear();
            materialise(*c, g, static_cast<int>(a), path);
            add(g.nodes[arc.saddle].cell, g.nodes[arc.extremum].cell, arc.kind);
        }
        const int n = c->n();
        v->basin.resize(n);
        v->peak.resize(n);
        for (int i = 0; i < n; i++) {
            v->basin[i] = resolve(merged, c->basin0[i]);
            int x = std::min(c->x_of(i), c->width - 2), y = std::min(c->y_of(i), c->height - 2);
            v->peak[i] = resolve(merged, c->peak0[2 * (y * c->width + x)]);
        }
        return v.release();
    } catch (const std::exception& e) {
        set_error(error, error_capacity, e.what());
        return nullptr;
    }
}

void fms_free_view(fms_view* v) { delete v; }
int32_t fms_view_node_count(const fms_view* v) { return static_cast<int32_t>(v->nodes.size()); }
void fms_view_nodes(const fms_view* v, int32_t* cell) { std::copy(v->nodes.begin(), v->nodes.end(), cell); }
int32_t fms_view_arc_count(const fms_view* v) { return static_cast<int32_t>(v->arc_saddle.size()); }
int64_t fms_view_arc_path_total(const fms_view* v) { return static_cast<int64_t>(v->paths.size() / 2); }

void fms_view_arcs(const fms_view* v, int32_t* saddle, int32_t* extremum, int32_t* kind, int32_t* length) {
    std::copy(v->arc_saddle.begin(), v->arc_saddle.end(), saddle);
    std::copy(v->arc_extremum.begin(), v->arc_extremum.end(), extremum);
    std::copy(v->arc_kind.begin(), v->arc_kind.end(), kind);
    std::copy(v->arc_length.begin(), v->arc_length.end(), length);
}

void fms_view_arc_paths(const fms_view* v, float* xy) { std::copy(v->paths.begin(), v->paths.end(), xy); }

void fms_view_segmentation(const fms_view* v, int32_t* basin, int32_t* peak) {
    std::copy(v->basin.begin(), v->basin.end(), basin);
    std::copy(v->peak.begin(), v->peak.end(), peak);
}

}  // extern "C"
