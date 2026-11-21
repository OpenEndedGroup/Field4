// The writing half of the openNURBS C ABI: see field_opennurbs_write.h for the contract. Kept in its
// own file, apart from the reader in field_opennurbs.cpp.

#include "field_opennurbs_write.h"
#include "field_opennurbs.h"

#include "opennurbs.h"

#include <algorithm>
#include <cstring>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Writer {
    ONX_Model model;
    ON_3dmObjectAttributes next;
    int defaultLayer = -1;
    int objects = 0;
    std::vector<int> layers;  // our index -> the model's layer index
    bool inBlock = false;     // between _begin_block and _end_block
    ON_wString blockName;
    ON_SimpleArray<ON_UUID> members;
    std::vector<ON_UUID> blocks;  // our block index -> its definition's id
};

void message(char* out, int capacity, const std::string& text) {
    if (out == nullptr || capacity <= 0) return;
    const size_t n = std::min(text.size(), static_cast<size_t>(capacity - 1));
    std::memcpy(out, text.data(), n);
    out[n] = 0;
}

// openNURBS's ON_wString(const char*) reads its argument as UTF-8.
ON_wString wide(const char* utf8) { return utf8 == nullptr ? ON_wString() : ON_wString(utf8); }

// The ABI's alpha is opacity; ON_Color's is transparency.
ON_Color color(int argb) {
    const unsigned u = static_cast<unsigned>(argb);
    return ON_Color(static_cast<int>((u >> 16) & 0xFF), static_cast<int>((u >> 8) & 0xFF), static_cast<int>(u & 0xFF),
                    255 - static_cast<int>((u >> 24) & 0xFF));
}

std::string narrow(const ON_wString& s) {
    std::string out;
    const wchar_t* w = static_cast<const wchar_t*>(s);
    for (size_t i = 0; w != nullptr && w[i] != 0; ++i) out += w[i] < 0x80 ? static_cast<char>(w[i]) : '?';
    return out;
}

int layerIndex(Writer& w, int layer) {
    if (layer >= 0 && layer < static_cast<int>(w.layers.size())) return w.layers[layer];
    if (w.defaultLayer < 0) w.defaultLayer = w.model.AddLayer(L"Default", ON_Color::Black);
    return w.defaultLayer;
}

// Add [geometry] with the current attributes; the model owns both from here on.
int add(Writer& w, ON_Geometry* geometry) {
    ON_3dmObjectAttributes* attributes = new ON_3dmObjectAttributes(w.next);
    attributes->m_layer_index = layerIndex(w, w.next.m_layer_index);
    if (w.inBlock) attributes->SetMode(ON::idef_object);
    const ON_ModelComponentReference ref = w.model.AddManagedModelGeometryComponent(geometry, attributes);
    if (ref.IsEmpty()) return -1;
    if (w.inBlock) w.members.Append(ref.ModelComponent()->Id()); else ++w.objects;
    return 0;
}

ON_NurbsCurve* polyline(const std::vector<ON_3dPoint>& points) {
    const int n = static_cast<int>(points.size());
    ON_NurbsCurve* curve = new ON_NurbsCurve(3, false, 2, n);
    for (int i = 0; i < n; ++i) curve->SetCV(i, points[i]);
    for (int i = 0; i < n; ++i) curve->m_knot[i] = i;
    return curve;
}

ON_NurbsCurve* bezier(const ON_3dPoint& a, const ON_3dPoint& b, const ON_3dPoint& c, const ON_3dPoint& d) {
    ON_NurbsCurve* curve = new ON_NurbsCurve(3, false, 4, 4);
    curve->SetCV(0, a);
    curve->SetCV(1, b);
    curve->SetCV(2, c);
    curve->SetCV(3, d);
    const double knots[] = {0, 0, 0, 1, 1, 1};
    for (int i = 0; i < 6; ++i) curve->m_knot[i] = knots[i];
    return curve;
}

// One subpath: a run of line points and cubic spans, starting at its move.
struct Span {
    bool cubic;
    std::vector<ON_3dPoint> points;  // a line run's vertices, or a cubic's four control points
};

ON_Curve* subpath(const std::vector<Span>& spans) {
    if (spans.size() == 1 && !spans[0].cubic) return polyline(spans[0].points);
    if (spans.size() == 1) {
        const auto& p = spans[0].points;
        return bezier(p[0], p[1], p[2], p[3]);
    }
    ON_PolyCurve* poly = new ON_PolyCurve(static_cast<int>(spans.size()));
    double t = 0;
    for (const Span& span : spans) {
        ON_NurbsCurve* piece = span.cubic ? bezier(span.points[0], span.points[1], span.points[2], span.points[3])
                                          : polyline(span.points);
        const ON_Interval domain = piece->Domain();
        piece->SetDomain(t, t + domain.Length());
        t += domain.Length();
        poly->Append(piece);
    }
    return poly;
}

// A path in the ABI's op form, as its subpaths; throws on a malformed one.
std::vector<std::vector<Span>> parsePath(const double* xyz, int pointCount, const int* ops, int opCount) {
    int p = 0;
    auto next = [&]() {
        if (p >= pointCount) throw std::exception();
        ON_3dPoint point(xyz[3 * p], xyz[3 * p + 1], xyz[3 * p + 2]);
        ++p;
        return point;
    };
    std::vector<std::vector<Span>> out;
    std::vector<Span> spans;
    ON_3dPoint at = ON_3dPoint::Origin;
    auto flush = [&]() {
        if (!spans.empty()) out.push_back(spans);
        spans.clear();
    };
    for (int i = 0; i < opCount; ++i) {
        switch (ops[i]) {
            case FIELD_OPENNURBS_OP_MOVE:
                flush();
                at = next();
                break;
            case FIELD_OPENNURBS_OP_LINE: {
                const ON_3dPoint to = next();
                if (spans.empty() || spans.back().cubic) spans.push_back(Span{false, {at}});
                spans.back().points.push_back(to);
                at = to;
                break;
            }
            case FIELD_OPENNURBS_OP_CUBIC: {
                const ON_3dPoint b = next(), c = next(), d = next();
                spans.push_back(Span{true, {at, b, c, d}});
                at = d;
                break;
            }
            default:
                throw std::exception();
        }
    }
    flush();
    return out;
}

// ---------------------------------------------------------------------------------------------
// Breps, surfaces, SubDs, hatches and blocks

// Reads the packed doubles of _add_brep; throws past the end.
struct Packed {
    const double* d;
    int n;
    int p = 0;
    double next() {
        if (p >= n) throw std::runtime_error("the packed Brep ends early, at double " + std::to_string(p));
        return d[p++];
    }
    int count() {
        const double v = next();
        if (!(v >= 0) || v > 1e8) throw std::runtime_error("bad count " + std::to_string(v) + " at double " + std::to_string(p - 1));
        return static_cast<int>(v);
    }
};

ON_NurbsSurface* surfaceFrom(int du, int dv, int cu, int cv, const double* ku, const double* kv, const double* cvs) {
    if (du < 1 || dv < 1 || cu < du + 1 || cv < dv + 1) throw std::runtime_error("bad surface degree or control point count");
    bool rational = false;
    for (int i = 0; i < cu * cv; ++i) rational = rational || cvs[4 * i + 3] != 1.0;
    ON_NurbsSurface* s = new ON_NurbsSurface(3, rational, du + 1, dv + 1, cu, cv);
    for (int i = 0; i < cu + du - 1; ++i) s->SetKnot(0, i, ku[i]);
    for (int i = 0; i < cv + dv - 1; ++i) s->SetKnot(1, i, kv[i]);
    for (int i = 0; i < cu; ++i) {
        for (int j = 0; j < cv; ++j) {
            const double* h = cvs + (i * cv + j) * 4;
            if (rational) s->SetCV(i, j, ON_4dPoint(h[0], h[1], h[2], h[3]));
            else s->SetCV(i, j, ON_3dPoint(h[0], h[1], h[2]));
        }
    }
    if (!s->IsValid()) {
        delete s;
        throw std::runtime_error("openNURBS finds a surface invalid (check its knots)");
    }
    return s;
}

ON_NurbsSurface* surfaceFrom(Packed& in) {
    const int du = in.count(), dv = in.count(), cu = in.count(), cv = in.count();
    std::vector<double> ku(cu + du - 1), kv(cv + dv - 1), cvs(static_cast<size_t>(cu) * cv * 4);
    for (double& k : ku) k = in.next();
    for (double& k : kv) k = in.next();
    for (double& c : cvs) c = in.next();
    return surfaceFrom(du, dv, cu, cv, ku.data(), kv.data(), cvs.data());
}

ON_NurbsCurve* trimFrom(Packed& in) {
    const int degree = in.count(), count = in.count();
    if (degree < 1 || count < degree + 1) throw std::runtime_error("bad trim degree or control point count");
    std::vector<double> knots(count + degree - 1), cvs(static_cast<size_t>(count) * 3);
    for (double& k : knots) k = in.next();
    for (double& c : cvs) c = in.next();
    bool rational = false;
    for (int i = 0; i < count; ++i) rational = rational || cvs[3 * i + 2] != 1.0;
    ON_NurbsCurve* c = new ON_NurbsCurve(2, rational, degree + 1, count);
    for (int i = 0; i < count + degree - 1; ++i) c->m_knot[i] = knots[i];
    for (int i = 0; i < count; ++i) {
        if (rational) c->SetCV(i, ON::homogeneous_rational, &cvs[3 * i]);  // (u w, v w, w)
        else c->SetCV(i, ON_3dPoint(cvs[3 * i], cvs[3 * i + 1], 0.0));
    }
    if (!c->IsValid()) {
        delete c;
        throw std::runtime_error("openNURBS finds a trim curve invalid (check its knots)");
    }
    return c;
}

ON_3dPoint image(const ON_Surface& s, const ON_Curve& c2, double t) {
    const ON_3dPoint uv = c2.PointAt(t);
    return s.PointAt(uv.x, uv.y);
}

// The 3D curve a trim traces on its surface, and how far it may stray from the exact image.
ON_Curve* edgeCurve(const ON_NurbsSurface& s, const ON_NurbsCurve& c2, double& tolerance) {
    tolerance = 0;
    const ON_3dPoint a = c2.PointAtStart(), b = c2.PointAtEnd();
    // A straight trim along an iso line: the surface's own iso-curve, exactly.
    if (c2.IsLinear(1e-12) && (a.x == b.x || a.y == b.y)) {
        const int dir = a.y == b.y ? 0 : 1;  // dir 0: u varies at constant v
        const double c = dir == 0 ? a.y : a.x;
        const double t0 = dir == 0 ? a.x : a.y, t1 = dir == 0 ? b.x : b.y;
        ON_Curve* iso = s.IsoCurve(dir, c);
        if (iso != nullptr) {
            const bool reversed = t1 < t0;
            const ON_Interval range(reversed ? t1 : t0, reversed ? t0 : t1);
            if (range.Length() > 0 && (range == iso->Domain() || iso->Trim(range))) {
                if (reversed) iso->Reverse();
                return iso;
            }
            delete iso;
        }
    }
    // A flat parallelogram (degree 1 by 1, two by two points, polynomial) maps the uv plane affinely:
    // the image of each control point, weights kept, is exact.
    if (s.Degree(0) == 1 && s.Degree(1) == 1 && s.CVCount(0) == 2 && s.CVCount(1) == 2 && !s.IsRational()) {
        ON_3dPoint p00, p10, p01, p11;
        s.GetCV(0, 0, p00); s.GetCV(1, 0, p10); s.GetCV(0, 1, p01); s.GetCV(1, 1, p11);
        if (((p00 + p11) - (p10 + p01)).Length() <= 1e-12 * (1 + p00.DistanceTo(p11))) {
            ON_NurbsCurve* c3 = new ON_NurbsCurve(3, c2.IsRational(), c2.Order(), c2.CVCount());
            for (int i = 0; i < c2.KnotCount(); ++i) c3->m_knot[i] = c2.m_knot[i];
            for (int i = 0; i < c2.CVCount(); ++i) {
                ON_4dPoint h;
                c2.GetCV(i, h);  // dimension 2: (u w, v w, -, w)
                const double w = c2.IsRational() ? h.w : 1.0;
                const ON_3dPoint p = s.PointAt(h.x / w, h.y / w);
                if (c2.IsRational()) c3->SetCV(i, ON_4dPoint(p.x * w, p.y * w, p.z * w, w));
                else c3->SetCV(i, p);
            }
            return c3;
        }
    }
    // Anything else: a dense polyline through the exact image, its chord error as the tolerance.
    const ON_Interval d = c2.Domain();
    const int n = std::max(32, 16 * c2.SpanCount() * c2.Degree());
    ON_3dPointArray points(n + 1);
    for (int i = 0; i <= n; ++i) points.Append(image(s, c2, d.ParameterAt(static_cast<double>(i) / n)));
    for (int i = 0; i < n; ++i) {
        const ON_3dPoint mid = image(s, c2, d.ParameterAt((i + 0.5) / n));
        tolerance = std::max(tolerance, mid.DistanceTo(0.5 * (points[i] + points[i + 1])));
    }
    return new ON_PolylineCurve(points);
}

struct BrepBuilder {
    ON_Brep& brep;
    double tol;
    explicit BrepBuilder(ON_Brep& b, double t) : brep(b), tol(t) {}

    int vertex(const ON_3dPoint& p) {
        for (int i = 0; i < brep.m_V.Count(); ++i) if (brep.m_V[i].point.DistanceTo(p) <= tol) return i;
        return brep.NewVertex(p, 0.0).m_vertex_index;
    }

    // An unshared edge this trim traces too: the same two end vertices, and the edge's points at a
    // quarter, half and three quarters of its domain all lying on the trim's image. Matching points to
    // the image rather than to the trim's own fractions joins trims of one edge whatever their pacing.
    // `reversed` when the trim runs against the edge (found from where those points fall along it).
    int sharedEdge(const ON_NurbsSurface& surface, const ON_NurbsCurve& c2, const ON_3dPoint& a, const ON_3dPoint& b,
                   bool& reversed) {
        const ON_Interval d = c2.Domain();
        const int dense = 256;
        std::vector<ON_3dPoint> trace(dense + 1);
        for (int j = 0; j <= dense; ++j) trace[j] = image(surface, c2, d.ParameterAt(static_cast<double>(j) / dense));
        // Distance from p to the trace's polyline, and the fraction along the trim where it is nearest.
        auto nearest = [&](const ON_3dPoint& p, double& at) {
            double best = 1e300;
            for (int j = 0; j < dense; ++j) {
                const ON_3dVector e = trace[j + 1] - trace[j];
                const double len2 = e * e;
                double f = len2 > 0 ? ((p - trace[j]) * e) / len2 : 0.0;
                f = f < 0 ? 0 : (f > 1 ? 1 : f);
                const double dist = p.DistanceTo(trace[j] + f * e);
                if (dist < best) { best = dist; at = (j + f) / dense; }
            }
            return best;
        };
        for (int e = 0; e < brep.m_E.Count(); ++e) {
            const ON_BrepEdge& edge = brep.m_E[e];
            if (edge.m_ti.Count() >= 2) continue;
            const ON_3dPoint s = brep.m_V[edge.m_vi[0]].point, t = brep.m_V[edge.m_vi[1]].point;
            const bool forward = s.DistanceTo(a) <= tol && t.DistanceTo(b) <= tol;
            const bool backward = s.DistanceTo(b) <= tol && t.DistanceTo(a) <= tol;
            if (!forward && !backward) continue;
            // The trace is a polyline of the image; allow its chord sag on top of the tolerances.
            const double slack = tol * 10 + edge.m_tolerance + 1e-3 * a.DistanceTo(b) + 1e-3 * trace[0].DistanceTo(trace[dense / 2]);
            const ON_Interval ed = edge.Domain();
            double f1 = 0, f2 = 0, f3 = 0;
            if (nearest(edge.PointAt(ed.ParameterAt(0.25)), f1) > slack) continue;
            if (nearest(edge.PointAt(ed.ParameterAt(0.5)), f2) > slack) continue;
            if (nearest(edge.PointAt(ed.ParameterAt(0.75)), f3) > slack) continue;
            const bool along = f1 < f2 && f2 < f3;
            const bool against = f1 > f2 && f2 > f3;
            if (forward && along) { reversed = false; return e; }
            if (backward && against) { reversed = true; return e; }
        }
        return -1;
    }

    void face(Packed& in, Writer& w) {
        const bool reversed = in.next() != 0;
        ON_NurbsSurface* surface = surfaceFrom(in);
        const int si = brep.AddSurface(surface);
        const int fi = brep.NewFace(si).m_face_index;
        brep.m_F[fi].m_bRev = reversed;
        const int loops = in.count();
        for (int l = 0; l < loops; ++l) {
            const int type = in.count();
            const int li = brep.NewLoop(type == 2 ? ON_BrepLoop::inner : ON_BrepLoop::outer, brep.m_F[fi]).m_loop_index;
            const int trims = in.count();
            for (int t = 0; t < trims; ++t) trim(in, *surface, li);
        }
        const int meshVertices = in.count();
        if (meshVertices > 0) {
            std::vector<ON_3dPoint> xyz(meshVertices);
            for (auto& p : xyz) { p.x = in.next(); p.y = in.next(); p.z = in.next(); }
            const int triangles = in.count();
            ON_Mesh* mesh = new ON_Mesh(triangles, meshVertices, false, false);
            for (int i = 0; i < meshVertices; ++i) mesh->SetVertex(i, xyz[i]);
            for (int f = 0; f < triangles; ++f) {
                const int a = in.count(), b = in.count(), c = in.count();
                if (a >= meshVertices || b >= meshVertices || c >= meshVertices) {
                    delete mesh;
                    throw std::runtime_error("a face's render mesh indexes past its vertices");
                }
                mesh->SetTriangle(f, a, b, c);
            }
            mesh->ComputeFaceNormals();
            mesh->ComputeVertexNormals();
            brep.m_F[fi].SetMesh(ON::render_mesh, mesh);
        }
        (void)w;
    }

    void trim(Packed& in, const ON_NurbsSurface& surface, int li) {
        ON_NurbsCurve* c2 = trimFrom(in);
        const int c2i = brep.AddTrimCurve(c2);
        const ON_Interval d = c2->Domain();
        const ON_3dPoint a = image(surface, *c2, d[0]), b = image(surface, *c2, d[1]);
        double extent = 0;
        for (int k = 1; k <= 8; ++k) extent = std::max(extent, image(surface, *c2, d.ParameterAt(k / 8.0)).DistanceTo(a));
        if (extent <= tol) {
            // The trim's image is a point: a singular trim along a collapsed side of the surface.
            ON_Surface::ISO iso = surface.IsIsoparametric(*c2);
            ON_BrepTrim& trim = brep.NewSingularTrim(brep.m_V[vertex(a)], brep.m_L[li], iso, c2i);
            trim.m_tolerance[0] = trim.m_tolerance[1] = 0;
            return;
        }
        bool reversed = false;
        int ei = sharedEdge(surface, *c2, a, b, reversed);
        if (ei < 0) {
            double edgeTolerance = 0;
            ON_Curve* c3 = edgeCurve(surface, *c2, edgeTolerance);
            const int c3i = brep.AddEdgeCurve(c3);
            const int v0 = vertex(a), v1 = vertex(b);
            ON_BrepEdge& edge = brep.NewEdge(brep.m_V[v0], brep.m_V[v1], c3i, nullptr, edgeTolerance);
            ei = edge.m_edge_index;
            reversed = false;
        }
        ON_BrepTrim& trim = brep.NewTrim(brep.m_E[ei], reversed, brep.m_L[li], c2i);
        trim.m_tolerance[0] = trim.m_tolerance[1] = 0;
    }
};

std::string validity(const ON_Brep& brep) {
    ON_wString log;
    ON_TextLog textLog(log);
    if (brep.IsValid(&textLog)) return std::string();
    std::string text = narrow(log);
    if (text.size() > 1500) text = text.substr(0, 1500) + "...";
    return text.empty() ? "openNURBS finds the Brep invalid" : text;
}

}  // namespace

extern "C" {

void* field_opennurbs_writer_new(int unitSystem) {
    try {
        Writer* w = new Writer();
        w->model.m_properties.m_RevisionHistory.NewRevision();
        w->model.m_settings.m_ModelUnitsAndTolerances.m_unit_system =
            ON_UnitSystem(ON::LengthUnitSystemFromUnsigned(static_cast<unsigned>(unitSystem)));
        w->next.m_layer_index = -1;
        return w;
    } catch (...) {
        return nullptr;
    }
}

void field_opennurbs_writer_free(void* writer) { delete static_cast<Writer*>(writer); }

int field_opennurbs_writer_add_layer(void* writer, const char* utf8Name, int argb, int parent, int visible) {
    if (writer == nullptr) return -1;
    try {
        Writer& w = *static_cast<Writer*>(writer);
        ON_Layer layer;
        layer.SetName(wide(utf8Name));
        layer.SetColor(color(argb));
        layer.SetVisible(visible != 0);
        if (parent >= 0 && parent < static_cast<int>(w.layers.size())) {
            layer.SetParentLayerId(w.model.LayerFromIndex(w.layers[parent]).ModelComponent()->Id());
        }
        const ON_ModelComponentReference ref = w.model.AddModelComponent(layer);
        if (ref.IsEmpty()) return -1;
        w.layers.push_back(ref.ModelComponent()->Index());
        return static_cast<int>(w.layers.size()) - 1;
    } catch (...) {
        return -1;
    }
}

void field_opennurbs_writer_attributes(void* writer, int layer, int hasColor, int argb, const char* utf8Name,
                                       int visible) {
    if (writer == nullptr) return;
    Writer& w = *static_cast<Writer*>(writer);
    w.next = ON_3dmObjectAttributes();
    w.next.m_layer_index = layer;
    if (hasColor != 0) {
        w.next.SetColorSource(ON::color_from_object);
        w.next.m_color = color(argb);
    }
    if (utf8Name != nullptr && *utf8Name != 0) w.next.SetName(wide(utf8Name), true);
    w.next.SetVisible(visible != 0);
}

int field_opennurbs_writer_add_mesh(void* writer, const double* xyz, int vertexCount, const int* triangles,
                                    int triangleCount, const float* normals, const int* vertexColors) {
    if (writer == nullptr || xyz == nullptr || triangles == nullptr || vertexCount <= 0 || triangleCount <= 0) return -1;
    try {
        ON_Mesh* mesh = new ON_Mesh(triangleCount, vertexCount, normals != nullptr, false);
        for (int i = 0; i < vertexCount; ++i) mesh->SetVertex(i, ON_3dPoint(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]));
        for (int f = 0; f < triangleCount; ++f) {
            const int a = triangles[3 * f], b = triangles[3 * f + 1], c = triangles[3 * f + 2];
            if (a < 0 || b < 0 || c < 0 || a >= vertexCount || b >= vertexCount || c >= vertexCount) {
                delete mesh;
                return -1;
            }
            mesh->SetTriangle(f, a, b, c);
        }
        if (normals != nullptr) {
            for (int i = 0; i < vertexCount; ++i)
                mesh->SetVertexNormal(i, ON_3dVector(normals[3 * i], normals[3 * i + 1], normals[3 * i + 2]));
        }
        if (vertexColors != nullptr) {
            mesh->m_C.Reserve(vertexCount);
            for (int i = 0; i < vertexCount; ++i) mesh->m_C.Append(color(vertexColors[i]));
        }
        mesh->ComputeFaceNormals();
        if (normals == nullptr) mesh->ComputeVertexNormals();
        mesh->BoundingBox();
        return add(*static_cast<Writer*>(writer), mesh);
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_add_nurbs_curve(void* writer, int degree, int cvCount, const double* xyz,
                                           const double* weights, const double* knots) {
    if (writer == nullptr || xyz == nullptr || knots == nullptr || degree < 1 || cvCount < degree + 1) return -1;
    try {
        ON_NurbsCurve* curve = new ON_NurbsCurve(3, weights != nullptr, degree + 1, cvCount);
        for (int i = 0; i < cvCount; ++i) {
            const double wt = weights == nullptr ? 1.0 : weights[i];
            if (weights == nullptr) {
                curve->SetCV(i, ON_3dPoint(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]));
            } else {
                curve->SetCV(i, ON_4dPoint(xyz[3 * i] * wt, xyz[3 * i + 1] * wt, xyz[3 * i + 2] * wt, wt));
            }
        }
        for (int i = 0; i < cvCount + degree - 1; ++i) curve->m_knot[i] = knots[i];
        if (!curve->IsValid()) {
            delete curve;
            return -1;
        }
        return add(*static_cast<Writer*>(writer), curve);
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_add_path(void* writer, const double* xyz, int pointCount, const int* ops, int opCount) {
    if (writer == nullptr || xyz == nullptr || ops == nullptr) return -1;
    try {
        Writer& w = *static_cast<Writer*>(writer);
        int added = 0;
        for (const auto& spans : parsePath(xyz, pointCount, ops, opCount)) {
            if (add(w, subpath(spans)) == 0) ++added;
        }
        return added;
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_add_points(void* writer, const double* xyz, int count) {
    if (writer == nullptr || xyz == nullptr || count <= 0) return -1;
    try {
        Writer& w = *static_cast<Writer*>(writer);
        if (count == 1) return add(w, new ON_Point(ON_3dPoint(xyz[0], xyz[1], xyz[2])));
        ON_PointCloud* cloud = new ON_PointCloud(count);
        for (int i = 0; i < count; ++i) cloud->m_P.Append(ON_3dPoint(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]));
        return add(w, cloud);
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_add_text(void* writer, const char* utf8Text, const double* origin, const double* xAxis,
                                    const double* yAxis, double height) {
    if (writer == nullptr || utf8Text == nullptr || origin == nullptr || xAxis == nullptr || yAxis == nullptr) return -1;
    try {
        ON_Plane plane(ON_3dPoint(origin[0], origin[1], origin[2]), ON_3dVector(xAxis[0], xAxis[1], xAxis[2]),
                       ON_3dVector(yAxis[0], yAxis[1], yAxis[2]));
        if (!plane.IsValid()) return -1;
        ON_Text* text = new ON_Text();
        if (!text->Create(static_cast<const wchar_t*>(wide(utf8Text)), &ON_DimStyle::Default, plane)) {
            delete text;
            return -1;
        }
        if (height > 0) text->SetTextHeight(&ON_DimStyle::Default, height);
        return add(*static_cast<Writer*>(writer), text);
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_add_brep(void* writer, const double* packed, int length, char* out, int capacity) {
    if (writer == nullptr || packed == nullptr || length <= 0) {
        message(out, capacity, "null writer or empty Brep");
        return -1;
    }
    ON_Brep* brep = new ON_Brep();
    try {
        Writer& w = *static_cast<Writer*>(writer);
        Packed in{packed, length};
        BrepBuilder builder(*brep, 1e-6 * (1 + 0));
        const int faces = in.count();
        if (faces == 0) throw std::runtime_error("a Brep needs at least one face");
        for (int f = 0; f < faces; ++f) builder.face(in, w);
        if (in.p != in.n) throw std::runtime_error("the packed Brep has " + std::to_string(in.n - in.p) + " doubles left over");
        brep->SetTrimIsoFlags();
        brep->SetTolerancesBoxesAndFlags(false, false, false, false, true, true, true, true);
        const std::string problem = validity(*brep);
        if (!problem.empty()) throw std::runtime_error(problem);
        ON_Brep* added = brep;
        brep = nullptr;
        if (add(w, added) != 0) throw std::runtime_error("openNURBS would not add the Brep to the model");
        return 0;
    } catch (const std::exception& e) {
        delete brep;
        message(out, capacity, e.what());
        return -1;
    } catch (...) {
        delete brep;
        message(out, capacity, "unknown failure building the Brep");
        return -1;
    }
}

int field_opennurbs_writer_add_surface(void* writer, int degreeU, int degreeV, int cvCountU, int cvCountV,
                                       const double* knotsU, const double* knotsV, const double* cvs) {
    if (writer == nullptr || knotsU == nullptr || knotsV == nullptr || cvs == nullptr) return -1;
    try {
        ON_NurbsSurface* surface = surfaceFrom(degreeU, degreeV, cvCountU, cvCountV, knotsU, knotsV, cvs);
        ON_Brep* brep = new ON_Brep();
        if (!brep->Create(surface)) {
            delete surface;
            delete brep;
            return -1;
        }
        return add(*static_cast<Writer*>(writer), brep);
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_add_subd(void* writer, const double* xyz, int vertexCount, const int* faceSizes,
                                    int faceCount, const int* faceVertices, const int* creases, int creaseCount) {
    if (writer == nullptr || xyz == nullptr || faceSizes == nullptr || faceVertices == nullptr || vertexCount <= 0 ||
        faceCount <= 0 || (creaseCount > 0 && creases == nullptr)) return -1;
    ON_SubD* subd = new ON_SubD();
    try {
        std::vector<ON_SubDVertex*> vertices(vertexCount);
        for (int i = 0; i < vertexCount; ++i) vertices[i] = subd->AddVertex(ON_SubDVertexTag::Unset, xyz + 3 * i);
        auto creased = [&](int a, int b) {
            for (int c = 0; c < creaseCount; ++c) {
                if ((creases[2 * c] == a && creases[2 * c + 1] == b) || (creases[2 * c] == b && creases[2 * c + 1] == a)) return true;
            }
            return false;
        };
        int at = 0;
        for (int f = 0; f < faceCount; ++f) {
            const int n = faceSizes[f];
            if (n < 3) throw std::exception();
            ON_SimpleArray<ON_SubDEdgePtr> edges(n);
            for (int k = 0; k < n; ++k) {
                const int a = faceVertices[at + k], b = faceVertices[at + (k + 1) % n];
                if (a < 0 || b < 0 || a >= vertexCount || b >= vertexCount) throw std::exception();
                ON_SubDEdgePtr e = subd->FindOrAddEdge(vertices[a], vertices[b]);
                if (e.IsNull()) throw std::exception();
                if (creased(a, b)) const_cast<ON_SubDEdge*>(e.Edge())->m_edge_tag = ON_SubDEdgeTag::Crease;
                edges.Append(e);
            }
            if (subd->AddFace(edges) == nullptr) throw std::exception();
            at += n;
        }
        subd->UpdateAllTagsAndSectorCoefficients(true);
        if (!subd->IsValid()) throw std::exception();
        ON_SubD* added = subd;
        subd = nullptr;
        return add(*static_cast<Writer*>(writer), added);
    } catch (...) {
        delete subd;
        return -1;
    }
}

int field_opennurbs_writer_add_hatch(void* writer, const double* xyz, int pointCount, const int* ops, int opCount) {
    if (writer == nullptr || xyz == nullptr || ops == nullptr || pointCount <= 0) return -1;
    try {
        std::vector<ON_Curve*> owned;
        ON_SimpleArray<const ON_Curve*> loops;
        for (const auto& spans : parsePath(xyz, pointCount, ops, opCount)) {
            ON_Curve* c = subpath(spans);
            owned.push_back(c);
            loops.Append(c);
        }
        ON_Plane plane(ON_3dPoint(0, 0, xyz[2]), ON_3dVector::ZAxis);
        ON_Hatch* hatch = new ON_Hatch();
        const bool ok = loops.Count() > 0 && hatch->Create(plane, loops, 0, 0, 1);
        for (ON_Curve* c : owned) delete c;
        if (!ok) {
            delete hatch;
            return -1;
        }
        return add(*static_cast<Writer*>(writer), hatch);
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_begin_block(void* writer, const char* utf8Name) {
    if (writer == nullptr || utf8Name == nullptr || *utf8Name == 0) return -1;
    Writer& w = *static_cast<Writer*>(writer);
    if (w.inBlock) return -1;
    w.inBlock = true;
    w.blockName = wide(utf8Name);
    w.members.Empty();
    return 0;
}

int field_opennurbs_writer_end_block(void* writer) {
    if (writer == nullptr) return -1;
    Writer& w = *static_cast<Writer*>(writer);
    if (!w.inBlock) return -1;
    w.inBlock = false;
    try {
        ON_InstanceDefinition definition;
        definition.SetName(w.blockName);
        definition.SetInstanceGeometryIdList(w.members);
        const ON_ModelComponentReference ref = w.model.AddModelComponent(definition);
        if (ref.IsEmpty()) return -1;
        w.blocks.push_back(ref.ModelComponent()->Id());
        return static_cast<int>(w.blocks.size()) - 1;
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_add_instance(void* writer, int block, const double* xform16) {
    if (writer == nullptr || xform16 == nullptr) return -1;
    Writer& w = *static_cast<Writer*>(writer);
    if (block < 0 || block >= static_cast<int>(w.blocks.size()) || w.inBlock) return -1;
    try {
        ON_InstanceRef* instance = new ON_InstanceRef();
        instance->m_instance_definition_uuid = w.blocks[block];
        for (int r = 0; r < 4; ++r) for (int c = 0; c < 4; ++c) instance->m_xform.m_xform[r][c] = xform16[4 * r + c];
        return add(w, instance);
    } catch (...) {
        return -1;
    }
}

int field_opennurbs_writer_object_count(void* writer) {
    return writer == nullptr ? 0 : static_cast<Writer*>(writer)->objects;
}

int field_opennurbs_writer_write(void* writer, const char* utf8Path, int version, char* out, int capacity) {
    if (writer == nullptr || utf8Path == nullptr) {
        message(out, capacity, "null writer or path");
        return FIELD_OPENNURBS_BAD_INPUT;
    }
    try {
        Writer& w = *static_cast<Writer*>(writer);
        ON_wString log;
        ON_TextLog textLog(log);
        if (!w.model.Write(utf8Path, version, &textLog)) {
            message(out, capacity, "openNURBS could not write " + std::string(utf8Path) + ": " + narrow(log));
            return FIELD_OPENNURBS_FAILED;
        }
        return FIELD_OPENNURBS_OK;
    } catch (const std::exception& e) {
        message(out, capacity, e.what());
        return FIELD_OPENNURBS_FAILED;
    } catch (...) {
        message(out, capacity, "unknown failure writing the model");
        return FIELD_OPENNURBS_FAILED;
    }
}

}  // extern "C"
