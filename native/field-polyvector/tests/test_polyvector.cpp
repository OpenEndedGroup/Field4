// Native checks for field_polyvector on synthetic drawings whose answer is known.
#include "field_polyvector.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "opencv2/imgcodecs.hpp"
#include "opencv2/imgproc.hpp"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

struct Canvas {
    int w, h;
    std::vector<float> ink;
    Canvas(int w, int h) : w(w), h(h), ink(w * h, 0.f) {}
    // a soft pencil-like stroke: Gaussian across, half-width width/2 (the reference is tuned on
    // scanned drawings with strokes of about this profile; crisp 0/1 strokes have flat interiors with
    // no gradient to orient the field, and the reference can then emit NaN points)
    void stroke(double x0, double y0, double x1, double y1, double width) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double px = x + 0.5 - x0, py = y + 0.5 - y0, dx = x1 - x0, dy = y1 - y0;
                double t = std::clamp((px * dx + py * dy) / (dx * dx + dy * dy), 0.0, 1.0);
                double d = std::hypot(px - t * dx, py - t * dy);
                float v = static_cast<float>(std::exp(-(d / (width / 2)) * (d / (width / 2))));
                ink[y * w + x] = std::max(ink[y * w + x], v);
            }
    }
};

struct Result {
    std::vector<std::vector<std::pair<float, float>>> curves;
    int singularities = 0;
};

static Result run(const Canvas& c) {
    char err[256];
    Result out;
    fpv_result* r = fpv_field(c.ink.data(), c.w, c.h, nullptr, err, sizeof err);
    if (!r) { std::printf("field failed: %s\n", err); failures++; return out; }
    if (fpv_trace(r, nullptr, err, sizeof err) < 0) { std::printf("trace failed: %s\n", err); failures++; fpv_free(r); return out; }
    int n = fpv_curve_count(r, 1);
    std::vector<int32_t> len(n);
    std::vector<float> xy(2 * fpv_point_total(r, 1));
    fpv_curves(r, 1, len.data(), xy.data());
    size_t o = 0;
    for (int i = 0; i < n; i++) {
        out.curves.emplace_back();
        for (int k = 0; k < len[i]; k++, o += 2) out.curves.back().push_back({xy[o], xy[o + 1]});
    }
    out.singularities = fpv_singularity_count(r);
    fpv_free(r);
    return out;
}

static double length(const std::vector<std::pair<float, float>>& c) {
    double s = 0;
    for (size_t i = 1; i < c.size(); i++) s += std::hypot(c[i].first - c[i - 1].first, c[i].second - c[i - 1].second);
    return s;
}

// Every curve point must lie on ink.
static bool onInk(const Canvas& c, const Result& r) {
    for (auto& curve : r.curves)
        for (auto& p : curve) {
            int x = std::clamp(static_cast<int>(p.first), 0, c.w - 1), y = std::clamp(static_cast<int>(p.second), 0, c.h - 1);
            float best = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int xx = std::clamp(x + dx, 0, c.w - 1), yy = std::clamp(y + dy, 0, c.h - 1);
                best = std::max(best, c.ink[yy * c.w + xx]);
            }
            if (best < 0.3f) return false;
        }
    return true;
}

int main(int argc, char** argv) {
    char err[256];
    CHECK(fpv_abi_version() == FPV_ABI_VERSION, "abi");

    // One straight stroke: the curves lie on the ink and cover it. Exact curve counts on clean
    // synthetic strokes are not asserted: the reference finds nothing in a 3 px stroke and can trace
    // a 4-5 px one as two parallel curves, depending on a grey level here and there. The equivalence
    // check at the end pins exact behaviour on a real drawing instead.
    {
        Canvas c(120, 80);
        c.stroke(15, 40, 105, 40, 5);
        Result r = run(c);
        double total = 0;
        for (auto& cv : r.curves) total += length(cv);
        CHECK(!r.curves.empty() && total > 80, "single stroke: %zu curves, %.0f px", r.curves.size(), total);
        CHECK(onInk(c, r), "single stroke off the ink");
    }

    // An X: the two strokes pass straight through the crossing. The polyvector field holds both
    // directions there, so tracing continues along each; total length is about both strokes.
    {
        Canvas c(140, 140);
        c.stroke(20, 20, 120, 120, 3);
        c.stroke(20, 120, 120, 20, 3);
        Result r = run(c);
        double total = 0;
        for (auto& cv : r.curves) total += length(cv);
        CHECK(total > 250 && total < 320, "X: total length %.0f, expected ~283", total);
        CHECK(r.curves.size() <= 4, "X: %zu curves", r.curves.size());
        CHECK(onInk(c, r), "X off the ink");
        std::printf("X: %zu curves, total length %.0f\n", r.curves.size(), total);
    }

    // A T in 3 px strokes: the bar and the stem both survive. (In strokes of 5 px or more the
    // reference drops the stem; the docs say so. This guards the case that works.)
    {
        Canvas c(140, 120);
        c.stroke(20, 30, 120, 30, 3);
        c.stroke(70, 30, 70, 100, 3);
        Result r = run(c);
        double total = 0;
        for (auto& cv : r.curves) total += length(cv);
        CHECK(total > 150 && total < 210, "T: total length %.0f, expected ~170", total);
        CHECK(onInk(c, r), "T off the ink");
        std::printf("T: %zu curves, total length %.0f\n", r.curves.size(), total);
    }

    // Two parallel strokes 15 px apart: curves on each, none bridging the gap.
    {
        Canvas c(140, 80);
        c.stroke(20, 30, 120, 30, 5);
        c.stroke(20, 45, 120, 45, 5);
        Result r = run(c);
        bool upper = false, lower = false;
        for (auto& cv : r.curves) {
            double ymin = 1e9, ymax = -1e9;
            for (auto& p : cv) { ymin = std::min<double>(ymin, p.second); ymax = std::max<double>(ymax, p.second); }
            CHECK(ymax - ymin < 8, "parallel: a curve spans y %.1f..%.1f, across both strokes", ymin, ymax);
            if (ymax < 38) upper = true; else if (ymin > 38) lower = true;
        }
        CHECK(upper && lower, "parallel: curves on both strokes (%d, %d)", upper, lower);
        CHECK(onInk(c, r), "parallel off the ink");
    }

    // Separate components are vectorized independently.
    {
        Canvas c(160, 100);
        c.stroke(15, 20, 60, 80, 3);
        c.stroke(100, 20, 145, 80, 3);
        char e2[256];
        fpv_result* r = fpv_field(c.ink.data(), c.w, c.h, nullptr, e2, sizeof e2);
        CHECK(r && fpv_component_count(r) == 2, "components: %d", r ? fpv_component_count(r) : -1);
        if (r) fpv_free(r);
    }

    // Errors are reported.
    {
        std::vector<float> blank(64 * 64, 0.f);
        CHECK(fpv_field(blank.data(), 64, 64, nullptr, err, sizeof err) == nullptr, "blank page accepted");
        CHECK(fpv_field(blank.data(), 4, 4, nullptr, err, sizeof err) == nullptr, "tiny image accepted");
        fpv_field_params bad;
        fpv_default_field_params(&bad);
        bad.ink_threshold = 2;
        CHECK(fpv_field(blank.data(), 64, 64, &bad, err, sizeof err) == nullptr, "bad threshold accepted");
    }

    // Equivalence with the reference implementation on one of its own sample drawings. The numbers are
    // from the reference CLI built from the same pinned commit (tools/validate_polyvector.py checks
    // all six samples point for point); this library drops the reference's single-point "curves".
    if (argc > 1) {
        std::string path = std::string(argv[1]) + "/sample_inputs/banana_tree.png";
        cv::Mat img = cv::imread(path, cv::IMREAD_COLOR), gray;
        CHECK(!img.empty(), "could not read %s", path.c_str());
        if (!img.empty()) {
            cv::cvtColor(img, gray, cv::COLOR_BGR2GRAY);
            Canvas c(gray.cols, gray.rows);
            for (int y = 0; y < gray.rows; y++)
                for (int x = 0; x < gray.cols; x++) c.ink[y * c.w + x] = (255 - gray.at<uchar>(y, x)) / 255.0f;
            Result r = run(c);
            double total = 0;
            for (auto& cv : r.curves) total += length(cv);
            CHECK(r.curves.size() == 99 && std::fabs(total - 7178) < 1, "banana_tree: %zu curves, %.0f px (reference: 99, 7178)", r.curves.size(), total);
            std::printf("banana_tree: %zu curves, %.0f px\n", r.curves.size(), total);
        }
    }

    if (failures) { std::printf("%d failure(s)\n", failures); return 1; }
    std::printf("field_polyvector_test: ok\n");
    return 0;
}
