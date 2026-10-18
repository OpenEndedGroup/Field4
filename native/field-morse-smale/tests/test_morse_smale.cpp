// Native checks for field_morse_smale on fields whose topology is known in closed form, and on
// noise, where the invariants of discrete Morse theory must hold exactly.
#include "field_morse_smale.h"

#include <cmath>
#include <cstdio>
#include <set>
#include <vector>

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

struct Critical {
    std::vector<int32_t> cell, type;
    std::vector<float> value, xy;
    std::vector<double> at;
};

static Critical critical(fms_complex* c) {
    int n = fms_critical_count(c);
    Critical k{std::vector<int32_t>(n), std::vector<int32_t>(n), std::vector<float>(n), std::vector<float>(2 * n), std::vector<double>(n)};
    fms_critical_points(c, k.cell.data(), k.type.data(), k.value.data(), k.at.data(), k.xy.data());
    return k;
}

struct Counts { int minima = 0, saddles = 0, maxima = 0, arcs = 0; };

static Counts counts(fms_complex* c, double threshold) {
    char err[256];
    fms_view* v = fms_simplify(c, threshold, err, sizeof err);
    Critical k = critical(c);
    std::vector<int32_t> alive(fms_view_node_count(v));
    fms_view_nodes(v, alive.data());
    std::set<int> live(alive.begin(), alive.end());
    Counts out;
    for (size_t i = 0; i < k.cell.size(); i++) {
        if (!live.count(k.cell[i])) continue;
        if (k.type[i] == FMS_MINIMUM) out.minima++; else if (k.type[i] == FMS_SADDLE) out.saddles++; else out.maxima++;
    }
    out.arcs = fms_view_arc_count(v);
    fms_free_view(v);
    return out;
}

static std::vector<float> field(int w, int h, double (*f)(double, double)) {
    std::vector<float> out(w * h);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) out[y * w + x] = static_cast<float>(f(2.0 * x / (w - 1) - 1, 2.0 * y / (h - 1) - 1));
    return out;
}

static std::vector<float> noise(int w, int h, unsigned seed) {
    std::vector<float> vals(w * h);
    for (auto& v : vals) { seed = seed * 1103515245u + 12345u; v = static_cast<float>((seed >> 8) & 0xffff) / 65535.f; }
    return vals;
}

// The invariants every complex must satisfy, whatever the field.
static void invariants(const char* name, fms_complex* c) {
    Critical k = critical(c);
    int m = 0, s = 0, M = 0;
    for (int t : k.type) { m += t == FMS_MINIMUM; s += t == FMS_SADDLE; M += t == FMS_MAXIMUM; }
    // The grid is a disc: Euler characteristic 1, exactly, for any discrete gradient (the virtual
    // outside maximum is not reported, so this is the disc count).
    CHECK(m - s + M == 1, "%s: euler %d - %d + %d != 1", name, m, s, M);
    // Simplification never adds a critical point, and keeps the Euler characteristic.
    int previous = 1 << 30;
    for (double t : {0.0, 0.001, 0.01, 0.05, 0.1, 0.3, 1.0, static_cast<double>(INFINITY)}) {
        Counts n = counts(c, t);
        int total = n.minima + n.saddles + n.maxima;
        CHECK(total <= previous, "%s: simplification grew at %g", name, t);
        CHECK(n.minima - n.saddles + n.maxima == 1, "%s: euler broken at threshold %g", name, t);
        previous = total;
    }
    // Every critical cell appears in exactly one persistence pair; every pair joins critical cells.
    int pairs = fms_pair_count(c);
    std::vector<int32_t> birth(pairs), death(pairs), dim(pairs);
    std::vector<double> pers(pairs);
    fms_pairs(c, birth.data(), death.data(), dim.data(), pers.data());
    std::set<int> crit(k.cell.begin(), k.cell.end()), seen;
    bool ok = true;
    for (int i = 0; i < pairs; i++) {
        for (int cell : {birth[i], death[i]}) {
            if (cell < 0) continue;
            if (!crit.count(cell) || !seen.insert(cell).second) ok = false;
        }
        if (pers[i] < 0) ok = false;
    }
    CHECK(ok && seen.size() == crit.size(), "%s: pairing does not match critical cells (%zu of %zu seen)", name, seen.size(), crit.size());
}

int main() {
    char err[256];
    CHECK(fms_abi_version() == FMS_ABI_VERSION, "abi");

    // One Gaussian hill: one maximum, and fully simplified, one minimum and one maximum.
    {
        auto vals = field(65, 65, [](double x, double y) { return std::exp(-4 * (x * x + y * y)); });
        fms_complex* c = fms_build(vals.data(), 65, 65, 1, 65, err, sizeof err);
        CHECK(c, "build: %s", err);
        Counts raw = counts(c, 0);
        CHECK(raw.maxima == 1, "one Gaussian has one maximum, got %d", raw.maxima);
        invariants("gaussian", c);
        fms_free(c);
    }

    // sin(pi x) sin(pi y) on [-1,1]^2: two interior maxima, two interior minima and a centre saddle
    // whose four separatrices reach them.
    {
        auto vals = field(81, 81, [](double x, double y) { return std::sin(M_PI * x) * std::sin(M_PI * y) + 1e-3 * x; });
        fms_complex* c = fms_build(vals.data(), 81, 81, 1, 81, err, sizeof err);
        CHECK(c, "build: %s", err);
        Critical k = critical(c);
        int interiorMax = 0, interiorMin = 0, centre = -1;
        for (size_t i = 0; i < k.cell.size(); i++) {
            float x = k.xy[2 * i], y = k.xy[2 * i + 1];
            if (x < 1 || y < 1 || x > 79 || y > 79) continue;
            if (k.type[i] == FMS_MAXIMUM) interiorMax++;
            if (k.type[i] == FMS_MINIMUM) interiorMin++;
            if (k.type[i] == FMS_SADDLE && std::fabs(x - 40) <= 1 && std::fabs(y - 40) <= 1) centre = k.cell[i];
        }
        CHECK(interiorMax == 2 && interiorMin == 2, "egg crate: %d interior max, %d interior min", interiorMax, interiorMin);
        CHECK(centre >= 0, "egg crate: no centre saddle");
        fms_view* v = fms_simplify(c, 0, err, sizeof err);
        int arcs = fms_view_arc_count(v);
        std::vector<int32_t> sa(arcs), ex(arcs), kind(arcs), len(arcs);
        fms_view_arcs(v, sa.data(), ex.data(), kind.data(), len.data());
        int centreArcs = 0;
        for (int i = 0; i < arcs; i++) if (sa[i] == centre && ex[i] >= 0) centreArcs++;
        CHECK(centreArcs == 4, "centre saddle has %d separatrices to extrema", centreArcs);
        fms_free_view(v);
        invariants("egg crate", c);
        fms_free(c);
    }

    // A constant field: simulation of simplicity makes it a ramp in index order, so one minimum
    // (vertex 0) and nothing else.
    {
        std::vector<float> vals(16 * 16, 0.25f);
        fms_complex* c = fms_build(vals.data(), 16, 16, 1, 16, err, sizeof err);
        CHECK(c, "build: %s", err);
        Counts raw = counts(c, 0);
        CHECK(raw.minima == 1 && raw.saddles == 0 && raw.maxima == 0, "constant: %d/%d/%d", raw.minima, raw.saddles, raw.maxima);
        invariants("constant", c);
        fms_free(c);
    }

    // Strides: the second channel of an interleaved two-channel image gives the same complex.
    {
        auto one = field(32, 32, [](double x, double y) { return std::cos(3 * x) * std::cos(2 * y); });
        std::vector<float> two(32 * 32 * 2, 99.f);
        for (int i = 0; i < 32 * 32; i++) two[2 * i + 1] = one[i];
        fms_complex* a = fms_build(one.data(), 32, 32, 1, 32, err, sizeof err);
        fms_complex* b = fms_build(two.data() + 1, 32, 32, 2, 64, err, sizeof err);
        CHECK(critical(a).cell == critical(b).cell, "strided build differs");
        fms_free(a);
        fms_free(b);
    }

    // Errors are reported, not thrown.
    {
        std::vector<float> vals(9, 0.f);
        vals[4] = NAN;
        CHECK(fms_build(vals.data(), 3, 3, 1, 3, err, sizeof err) == nullptr, "NaN accepted");
        CHECK(fms_build(vals.data(), 1, 3, 1, 3, err, sizeof err) == nullptr, "1-wide accepted");
    }

    // Noise, at several sizes and seeds, including a quantised field full of plateaus.
    for (unsigned seed : {1u, 12345u, 777u}) {
        auto vals = noise(64, 48, seed);
        fms_complex* c = fms_build(vals.data(), 64, 48, 1, 64, err, sizeof err);
        CHECK(c, "build: %s", err);
        invariants("noise", c);
        Counts all = counts(c, INFINITY);
        std::printf("noise seed %u: %d critical, fully simplified %d/%d/%d\n", seed, fms_critical_count(c), all.minima, all.saddles, all.maxima);
        fms_free(c);
    }
    {
        auto vals = noise(40, 40, 99u);
        for (auto& v : vals) v = std::floor(v * 4) / 4;
        fms_complex* c = fms_build(vals.data(), 40, 40, 1, 40, err, sizeof err);
        CHECK(c, "build: %s", err);
        invariants("plateaus", c);
        fms_free(c);
    }

    if (failures) { std::printf("%d failure(s)\n", failures); return 1; }
    std::printf("field_morse_smale_test: ok\n");
    return 0;
}
