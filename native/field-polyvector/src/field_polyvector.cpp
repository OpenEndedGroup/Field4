// The reference PolyVectorization main(), split into fpv_field and fpv_trace. The algorithm is
// unchanged: the helper functions below are the reference's own (calculateGradient, calculateWeight,
// calculateIndices, repairMask, computeComponentMasks), reading the same globals, and the stage bodies
// follow main() step for step. What changed is the plumbing: input from a float array instead of a
// file, parameters from the caller, results copied into a handle, and no SVG.

#include "field_polyvector.h"

#include "stdafx.h"

#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>

#include "AlmostReebGraph.h"
#include "ChainDecomposition.h"
#include "ContractDeg2.h"
#include "ContractLoops.h"
#include "ContractLoops2.h"
#include "FindRoots.h"
#include "Optimizer.h"
#include "Params.h"
#include "RemoveShortBranches.h"
#include "Simplify.h"
#include "Smooth.h"
#include "SplitEmUp.h"
#include "TopoGraphEmbedding.h"
#include "chopFakeEnds.h"
#include "findSingularities.h"
#include "polynomial_energy.h"
#include "traceAuto.h"

// The reference's tuning constants, made settable (see POLYVECTOR_PATCHES).
double BACKGROUND_FOREGROUND_THRESHOLD = 90.0;
double FRAME_FIELD_REGULARIZER_WEIGHT = 0.1;
double FRAME_FIELD_SMOOTHNESS_WEIGHT = 50.0;
double PRUNE_SHORT_BRANCHES_RATIO = 0.75;
int MAX_NUMBER_OF_WHITE_PIXELS_IN_A_CONTRACTIBLE_LOOP = 4;

namespace {

std::mutex reference_lock;  // the reference's globals below, and the tuning constants above

// The reference reports progress on std::cout; a library must not. Held for the length of a call.
struct QuietCout {
    std::streambuf* saved;
    std::ostringstream sink;
    QuietCout() : saved(std::cout.rdbuf(sink.rdbuf())) {}
    ~QuietCout() { std::cout.rdbuf(saved); }
};

cv::Mat bwImg, origMask;
int m, n;
Eigen::MatrixXcd g, tau, tauTimesGmag;
Eigen::MatrixXd gMag, weight;

void set_error(char* error, int32_t capacity, const std::string& message) {
    if (!error || capacity <= 0) return;
    std::strncpy(error, message.c_str(), static_cast<size_t>(capacity) - 1);
    error[capacity - 1] = '\0';
}

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }

// ---- the reference's helpers, unchanged apart from formatting -------------------------------

void calculateGradient() {
    using namespace cv;
    Mat grad_x, grad_y;
    Sobel(bwImg, grad_x, CV_32F, 1, 0, 3, 1.0, 0, BORDER_DEFAULT);
    Sobel(bwImg, grad_y, CV_32F, 0, 1, 3, 1.0, 0, BORDER_DEFAULT);
    Mat planes[] = {grad_x, grad_y};
    Mat cvG;
    merge(planes, 2, cvG);
    cv2eigen(cvG, g);
    tauTimesGmag = g * std::complex<double>(0.0, 1.0);
    gMag = tauTimesGmag.cwiseAbs();
    double maxGradMag = gMag.maxCoeff();
    for (int i = 0; i < m; ++i)
        for (int j = 0; j < n; ++j)
            if (maxGradMag <= 0 || fabs(gMag(i, j) / maxGradMag) < 0.1) { gMag(i, j) = 0; tauTimesGmag(i, j) = 0; }
    Eigen::MatrixXd gMagNoZeros = gMag;
    for (int i = 0; i < m; ++i)
        for (int j = 0; j < n; ++j)
            if (fabs(gMag(i, j)) < 1e-10) gMagNoZeros(i, j) = 1;
    tau = tauTimesGmag.array() / gMagNoZeros.array();
}

void calculateWeight() {
    using namespace cv;
    Eigen::MatrixXcd eigTauTimesGmag2 = tauTimesGmag.array().pow(2);
    Mat re, im;
    Eigen::MatrixXd real = eigTauTimesGmag2.real(), imag = eigTauTimesGmag2.imag();
    eigen2cv(real, re);
    eigen2cv(imag, im);
    Mat Lx, Ly;
    Mat kernel = Mat::ones(3, 3, CV_64F);
    kernel.at<double>(1, 1) = 0;
    filter2D(re, Lx, -1, kernel);
    filter2D(im, Ly, -1, kernel);
    Eigen::MatrixXd Lx_eig, Ly_eig;
    cv2eigen(Lx, Lx_eig);
    cv2eigen(Ly, Ly_eig);
    Eigen::MatrixXcd mse = Lx_eig + std::complex<double>(0, 1) * Ly_eig;
    Eigen::MatrixXd mseNorm = mse.cwiseAbs();
    for (int i = 0; i < m; ++i)
        for (int j = 0; j < n; ++j)
            if (mseNorm(i, j) < 1e-10) mseNorm(i, j) = 1;
    mse = mse.array() / mseNorm.array();
    Eigen::MatrixXcd tau2 = tau.array().pow(2);
    mse = mse - tau2;
    for (int i = 0; i < m; ++i)
        for (int j = 0; j < n; ++j)
            if (fabs(gMag(i, j)) < 1e-10) mse(i, j) = 0;
    weight = mse.cwiseAbs();
    double maxW = weight.maxCoeff();
    weight = Eigen::MatrixXd::Ones(m, n) - (maxW > 0 ? Eigen::MatrixXd(weight / maxW) : weight);
    for (int i = 0; i < m; ++i)
        for (int j = 0; j < n; ++j)
            if (fabs(gMag(i, j)) < 1e-10) weight(i, j) = 0;
}

Eigen::MatrixXi calculateIndices(const cv::Mat& mask, int& nnz) {
    Eigen::MatrixXi indices = Eigen::MatrixXi(m, n);
    indices.setConstant(-1);
    int idx = 0;
    for (int j = 0; j < n; ++j)
        for (int i = 0; i < m; ++i)
            if (mask.at<uchar>(i, j) != 0) indices(i, j) = idx++;
    nnz = idx;
    return indices;
}

void repairMask(cv::Mat& mask) {
    std::vector<std::pair<int, int>> newGuys;
    for (int i = 0; i < m; ++i)
        for (int j = 0; j < n; ++j) {
            if (mask.at<uchar>(i, j) != 0) continue;
            int nn = 0;
            for (int i1 = -1; i1 < 2; ++i1)
                for (int j1 = -1; j1 < 2; ++j1) {
                    if ((i1 == j1) || (i1 + i < 0) || (i1 + i >= m) || (j1 + j < 0) || (j1 + j >= n)) continue;
                    if (mask.at<uchar>(i1 + i, j1 + j) != 0) nn++;
                }
            if (nn >= 5) newGuys.push_back({i, j});
        }
    for (auto p : newGuys) mask.at<uchar>(p.first, p.second) = 255;
}

std::vector<cv::Mat> computeComponentMasks(const cv::Mat& binMask) {
    cv::Mat labels;
    int numLabels = cv::connectedComponents(binMask, labels, 8, CV_32S);
    std::vector<cv::Mat> masks;
    for (int lbl = 1; lbl < numLabels; ++lbl) masks.push_back(labels == lbl);
    return masks;
}

struct Component {
    cv::Mat mask;
    Eigen::MatrixXi indices;
    Eigen::VectorXcd X;
    std::array<Eigen::MatrixXcd, 2> roots;
    std::set<std::array<int, 2>> singularities;
};

}  // namespace

struct fpv_result {
    int width = 0, height = 0;
    cv::Mat bw;
    std::vector<uint8_t> mask;
    std::vector<float> weight, directions;
    std::vector<Component> components;
    std::vector<std::array<float, 2>> singularities;
    std::vector<MyPolyline> traced, curves;
    int dropped = 0;
    double timings[2] = {0, 0};
};

extern "C" {

int32_t fpv_abi_version(void) { return FPV_ABI_VERSION; }
const char* fpv_version_string(void) {
    return "field_polyvector 1.0 (Bessmeltsev & Solomon 2019, PolyVectorization d2964393, MIT)";
}

void fpv_default_field_params(fpv_field_params* out) {
    out->ink_threshold = 90.0 / 255.0;
    out->regularizer = 0.1;
    out->smoothness = 50.0;
    out->repair_passes = 3;
}

void fpv_default_trace_params(fpv_trace_params* out) {
    out->prune_ratio = 0.75;
    out->max_loop_pixels = 4;
    out->simplify_tolerance = 1e-2;
    out->smooth = 1;
}

fpv_result* fpv_field(const float* evidence, int32_t width, int32_t height, const fpv_field_params* params,
                      char* error, int32_t error_capacity) {
    std::lock_guard<std::mutex> guard(reference_lock);
    QuietCout quiet;
    try {
        if (!evidence) throw std::invalid_argument("evidence is null");
        if (width < 8 || height < 8) throw std::invalid_argument("image must be at least 8x8");
        fpv_field_params p;
        if (params) p = *params; else fpv_default_field_params(&p);
        if (!(p.ink_threshold > 0 && p.ink_threshold < 1)) throw std::invalid_argument("ink_threshold must be in (0, 1)");
        if (!(p.regularizer >= 0) || !(p.smoothness > 0)) throw std::invalid_argument("regularizer must be >= 0 and smoothness > 0");
        // On the reference's 0..255 scale. Rounded, so the default 90/255 is exactly 90 again: cv::threshold
        // keeps pixels strictly above it, and 89.99999 would turn every 90 into ink.
        BACKGROUND_FOREGROUND_THRESHOLD = std::round(p.ink_threshold * 255.0 * 1000.0) / 1000.0;
        FRAME_FIELD_REGULARIZER_WEIGHT = p.regularizer;
        FRAME_FIELD_SMOOTHNESS_WEIGHT = p.smoothness;

        auto t0 = Clock::now();
        auto r = std::make_unique<fpv_result>();
        r->width = width;
        r->height = height;
        m = height;
        n = width;
        bwImg = cv::Mat(m, n, CV_8U);
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) {
                float v = evidence[static_cast<size_t>(i) * n + j];
                if (std::isnan(v)) throw std::invalid_argument("evidence has NaN at (" + std::to_string(j) + ", " + std::to_string(i) + ")");
                bwImg.at<uchar>(i, j) = static_cast<uchar>(std::lround(std::clamp(v, 0.0f, 1.0f) * 255.0f));
            }
        cv::threshold(bwImg, origMask, BACKGROUND_FOREGROUND_THRESHOLD, 255, cv::THRESH_BINARY);
        for (int i = 0; i < p.repair_passes; ++i) repairMask(origMask);
        if (cv::countNonZero(origMask) == 0) throw std::invalid_argument("no ink: nothing in the evidence is above ink_threshold");

        calculateGradient();
        calculateWeight();

        r->directions.assign(static_cast<size_t>(m) * n * 4, 0.0f);
        for (cv::Mat& compMask : computeComponentMasks(origMask)) {
            Component c;
            c.mask = compMask;
            int nnz = 0;
            c.indices = calculateIndices(compMask, nnz);
            c.X = optimizeByLinearSolve(bwImg, weight, tau, FRAME_FIELD_SMOOTHNESS_WEIGHT, compMask, c.indices);
            if (c.X.size() == 0) c.X = optimize(bwImg, weight, tau, FRAME_FIELD_SMOOTHNESS_WEIGHT, compMask, c.indices);
            c.roots = findRoots(c.X, compMask);
            c.singularities = findSingularities(c.roots, c.X, c.indices, compMask);
            // the reference's singularity pass: down-weight singular pixels and solve again
            for (;;) {
                size_t before = c.singularities.size();
                bool somethingNew = false;
                for (auto s : c.singularities)
                    if (weight(s[0], s[1]) > 1e-5) { somethingNew = true; weight(s[0], s[1]) = 0; }
                if (!somethingNew) break;
                c.X = optimizeByLinearSolve(bwImg, weight, tau, FRAME_FIELD_SMOOTHNESS_WEIGHT, compMask, c.indices);
                if (c.X.size() == 0) c.X = optimize(bwImg, weight, tau, FRAME_FIELD_SMOOTHNESS_WEIGHT, compMask, c.indices);
                c.roots = findRoots(c.X, compMask);
                c.singularities = findSingularities(c.roots, c.X, c.indices, compMask);
                // The reference computes `int - size_t` here, which is unsigned: it repeats whenever the count
                // changed, up or down, not only when it fell. Kept, so results match the reference.
                if (before == c.singularities.size()) break;
            }
            for (int i = 0; i < m; i++)
                for (int j = 0; j < n; j++) {
                    if (compMask.at<uchar>(i, j) == 0) continue;
                    for (int k = 0; k < 2; k++) {
                        std::complex<double> z = c.roots[k](i, j);
                        double len = std::abs(z);
                        if (len > 0) {
                            r->directions[(static_cast<size_t>(i) * n + j) * 4 + 2 * k] = static_cast<float>(z.real() / len);
                            r->directions[(static_cast<size_t>(i) * n + j) * 4 + 2 * k + 1] = static_cast<float>(z.imag() / len);
                        }
                    }
                }
            for (auto s : c.singularities) r->singularities.push_back({s[1] + 0.5f, s[0] + 0.5f});
            r->components.push_back(std::move(c));
        }
        r->bw = bwImg.clone();
        r->mask.resize(static_cast<size_t>(m) * n);
        r->weight.resize(static_cast<size_t>(m) * n);
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) {
                r->mask[static_cast<size_t>(i) * n + j] = origMask.at<uchar>(i, j) ? 1 : 0;
                r->weight[static_cast<size_t>(i) * n + j] = static_cast<float>(weight(i, j));
            }
        r->timings[0] = ms_since(t0);
        return r.release();
    } catch (const std::exception& e) {
        set_error(error, error_capacity, e.what());
        return nullptr;
    }
}

void fpv_free(fpv_result* r) { delete r; }

int32_t fpv_trace(fpv_result* r, const fpv_trace_params* params, char* error, int32_t error_capacity) {
    std::lock_guard<std::mutex> guard(reference_lock);
    QuietCout quiet;
    try {
        if (!r) throw std::invalid_argument("result is null");
        fpv_trace_params p;
        if (params) p = *params; else fpv_default_trace_params(&p);
        if (!(p.prune_ratio >= 0 && p.prune_ratio <= 1)) throw std::invalid_argument("prune_ratio must be in [0, 1]");
        if (p.max_loop_pixels < 0) throw std::invalid_argument("max_loop_pixels must be >= 0");
        PRUNE_SHORT_BRANCHES_RATIO = p.prune_ratio;
        MAX_NUMBER_OF_WHITE_PIXELS_IN_A_CONTRACTIBLE_LOOP = p.max_loop_pixels;
        auto t0 = Clock::now();
        m = r->height;
        n = r->width;
        bwImg = r->bw;
        r->traced.clear();
        r->curves.clear();

        for (Component& c : r->components) {
            std::map<std::array<int, 2>, std::vector<PixelInfo>> pixelInfo;
            std::vector<std::array<bool, 2>> endedWithASingularity;
            auto compPolys = traceAll(bwImg, c.mask, c.mask, c.roots, c.X, c.indices, pixelInfo, endedWithASingularity);
            r->traced.insert(r->traced.end(), compPolys.begin(), compPolys.end());

            auto reebGraph = computeAlmostReebGraph(c.mask, c.roots, compPolys, pixelInfo, c.singularities, c.indices, c.X, endedWithASingularity);
            contractSingularityBranches(reebGraph);
            simpleThresholds(reebGraph);
            connectStuffAroundSingularities(reebGraph, c.mask, compPolys, c.singularities, c.roots, endedWithASingularity);
            edge_iter eit, eend;
            for (std::tie(eit, eend) = boost::edges(reebGraph); eit != eend; ++eit) reebGraph[*eit].weight = 1;
            contractLoops(reebGraph, c.mask, compPolys);
            std::map<edge_descriptor, size_t> ignore;
            removeBranchesFilter1(reebGraph, false, ignore);
            splitEmUpCorrectly(reebGraph);

            for (size_t v = 0; v < boost::num_vertices(reebGraph); ++v) {
                if ((boost::degree(v, reebGraph) == 2) && (reebGraph[v].nextToSingularity || reebGraph[v].clusterCurveHitSingularity)) {
                    std::vector<size_t> verts;
                    oedge_iter oit, oend;
                    for (std::tie(oit, oend) = boost::out_edges(v, reebGraph); oit != oend; ++oit) verts.push_back(oit->m_target);
                    boost::clear_vertex(v, reebGraph);
                    auto e = boost::add_edge(verts[0], verts[1], reebGraph);
                    reebGraph[e.first].edgeCurve = -1;
                }
            }
            for (bool chopped = true; chopped;) {
                chopped = false;
                for (std::tie(eit, eend) = boost::edges(reebGraph); eit != eend; ++eit)
                    if (boost::degree(eit->m_source, reebGraph) > 2 && boost::degree(eit->m_target, reebGraph) > 2) {
                        boost::remove_edge(*eit, reebGraph);
                        chopped = true;
                        break;
                    }
            }

            std::vector<MyPolyline> compVectorization;
            std::vector<std::vector<double>> radii;
            std::vector<std::array<bool, 2>> protectedEnds, isItASpecialDeg2Vertex;
            std::vector<std::pair<PointOnCurve, PointOnCurve>> yJunctions;
            std::tie(compVectorization, radii, protectedEnds, isItASpecialDeg2Vertex, yJunctions) = topoGraphEmbedding(reebGraph, compPolys, bwImg);
            G wG;
            std::tie(compVectorization, wG) = chopFakeEnds(compVectorization, radii, protectedEnds, isItASpecialDeg2Vertex, yJunctions);
            for (std::tie(eit, eend) = boost::edges(wG); eit != eend; ++eit) wG[*eit].weight = 1.0;
            std::vector<edge_descriptor> removedEdges =
                boost::num_edges(wG) < 350 ? contractLoops2(wG, c.mask, compVectorization) : contractLoops(wG, c.mask, compVectorization);
            std::vector<std::vector<std::pair<double, double>>> cutThosePieces(compVectorization.size());
            for (auto e : removedEdges) {
                int curve = wG[e.m_source].clusterPoints[0].curve;
                if (curve == wG[e.m_target].clusterPoints[0].curve) {
                    double s1 = wG[e.m_source].clusterPoints[0].segmentIdx, s2 = wG[e.m_target].clusterPoints[0].segmentIdx;
                    cutThosePieces[curve].push_back(std::minmax(s1, s2));
                }
            }
            std::vector<MyPolyline> compNew;
            for (size_t i = 0; i < compVectorization.size(); ++i) {
                if (compVectorization[i].empty()) continue;
                std::vector<std::pair<double, double>> segments;
                std::sort(cutThosePieces[i].begin(), cutThosePieces[i].end(), [](auto& a, auto& b) { return a.first < b.first; });
                segments.push_back({0.0, 0.0});
                for (auto& cut : cutThosePieces[i]) { segments.back().second = cut.first; segments.push_back({cut.second, 0.0}); }
                segments.back().second = compVectorization[i].size() - 1;
                for (auto& seg : segments) {
                    MyPolyline poly;
                    if (fabs(seg.second - seg.first) > 1e-5)
                        for (int k = static_cast<int>(seg.first); k <= seg.second; ++k)
                            if (poly.empty() || (poly.back() - compVectorization[i][k]).norm() > 1e-6) poly.push_back(compVectorization[i][k]);
                    if (!poly.empty()) compNew.push_back(poly);
                }
            }
            for (auto& poly : compNew) poly = simplify(poly, p.simplify_tolerance);
            if (p.smooth) smooth(compNew);
            r->curves.insert(r->curves.end(), compNew.begin(), compNew.end());
        }
        // split at non-finite points, which the reference can produce
        r->dropped = 0;
        std::vector<MyPolyline> clean;
        for (auto& poly : r->curves) {
            MyPolyline part;
            for (auto& q : poly) {
                if (std::isfinite(q.x()) && std::isfinite(q.y())) { part.push_back(q); continue; }
                r->dropped++;
                if (part.size() >= 2) clean.push_back(part);
                part.clear();
            }
            if (part.size() >= 2) clean.push_back(part);
        }
        r->curves.swap(clean);
        r->timings[1] = ms_since(t0);
        return static_cast<int32_t>(r->curves.size());
    } catch (const std::exception& e) {
        set_error(error, error_capacity, e.what());
        return -1;
    }
}

void fpv_field_maps(const fpv_result* r, uint8_t* mask, float* weight_out, float* directions) {
    if (mask) std::copy(r->mask.begin(), r->mask.end(), mask);
    if (weight_out) std::copy(r->weight.begin(), r->weight.end(), weight_out);
    if (directions) std::copy(r->directions.begin(), r->directions.end(), directions);
}

int32_t fpv_component_count(const fpv_result* r) { return static_cast<int32_t>(r->components.size()); }
int32_t fpv_singularity_count(const fpv_result* r) { return static_cast<int32_t>(r->singularities.size()); }
void fpv_singularities(const fpv_result* r, float* xy) {
    for (size_t i = 0; i < r->singularities.size(); i++) { xy[2 * i] = r->singularities[i][0]; xy[2 * i + 1] = r->singularities[i][1]; }
}

static const std::vector<MyPolyline>& pick(const fpv_result* r, int32_t kind) { return kind == 0 ? r->traced : r->curves; }

int32_t fpv_curve_count(const fpv_result* r, int32_t kind) { return static_cast<int32_t>(pick(r, kind).size()); }
int64_t fpv_point_total(const fpv_result* r, int32_t kind) {
    int64_t t = 0;
    for (auto& p : pick(r, kind)) t += static_cast<int64_t>(p.size());
    return t;
}
void fpv_curves(const fpv_result* r, int32_t kind, int32_t* lengths, float* xy) {
    size_t o = 0;
    auto& curves = pick(r, kind);
    for (size_t i = 0; i < curves.size(); i++) {
        lengths[i] = static_cast<int32_t>(curves[i].size());
        for (auto& q : curves[i]) { xy[o++] = static_cast<float>(q.x() + 0.5); xy[o++] = static_cast<float>(q.y() + 0.5); }
    }
}

void fpv_timings(const fpv_result* r, double* out) { out[0] = r->timings[0]; out[1] = r->timings[1]; }
int32_t fpv_dropped_points(const fpv_result* r) { return r->dropped; }

}  // extern "C"
