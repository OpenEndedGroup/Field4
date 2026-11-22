// Brep surfaces and trim loops behind a flat C ABI. See field_opennurbs_brep.h for the packed
// layout; this file only gathers and packs. The tessellation is on the JVM side.

#include "field_opennurbs_brep.h"
#include "field_opennurbs.h"

#include "opennurbs.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <new>
#include <string>
#include <vector>

namespace {

struct Breps {
    std::vector<std::string> ids;
    std::vector<std::vector<double>> data;
    std::vector<std::string> subdIds;
    std::vector<std::vector<double>> subdData;
};

void brepMessage(char* message, int capacity, const std::string& text) {
    if (message == nullptr || capacity <= 0) return;
    const size_t n = text.size() < static_cast<size_t>(capacity - 1) ? text.size() : static_cast<size_t>(capacity - 1);
    std::memcpy(message, text.data(), n);
    message[n] = 0;
}

std::string brepId(const ON_UUID& id) {
    char buffer[64];
    ON_UuidToString(id, buffer);
    return buffer;
}

bool faceHasMesh(const ON_BrepFace& face) {
    const ON_Mesh* mesh = face.Mesh(ON::render_mesh);
    if (mesh == nullptr) mesh = face.Mesh(ON::any_mesh);
    return mesh != nullptr && mesh->FaceCount() > 0;
}

// Samples per span of a trim curve: a line needs its end points only; anything curved gets enough
// that the chord error in parameter space is far below what the surface grid resolves.
int samplesPerSpan(const ON_Curve& curve) {
    if (curve.Degree() <= 1) return 1;
    return 64;
}

// Each Brep edge sampled once, on its 3D curve, span by span, its ends pinned to its two Brep
// vertices: every trim of the edge hands out these same points, so faces that share the edge share
// their boundary samples exactly.
struct EdgeSamples {
    std::vector<ON_3dPoint> points;  // in the edge's own direction, both ends included
};

const EdgeSamples& edgeSamples(const ON_Brep& brep, int ei, std::vector<EdgeSamples>& cache) {
    EdgeSamples& out = cache[ei];
    if (!out.points.empty()) return out;
    const ON_BrepEdge& edge = brep.m_E[ei];
    const int spans = edge.SpanCount();
    std::vector<double> knots(spans > 0 ? spans + 1 : 2);
    if (spans <= 0 || !edge.GetSpanVector(knots.data())) {
        const ON_Interval d = edge.Domain();
        knots = {d[0], d[1]};
    }
    const int per = samplesPerSpan(edge);
    const int n = static_cast<int>(knots.size()) - 1;
    for (int s = 0; s < n; ++s)
        for (int k = 0; k < per; ++k) out.points.push_back(edge.PointAt(knots[s] + (knots[s + 1] - knots[s]) * k / per));
    out.points.push_back(edge.PointAt(knots[n]));
    out.points.front() = brep.m_V[edge.m_vi[0]].point;
    out.points.back() = brep.m_V[edge.m_vi[1]].point;
    return out;
}

// The trim parameter whose image on the surface is nearest `target`, searched in [lo, hi].
double nearestOnTrim(const ON_BrepTrim& trim, const ON_Surface& surface, const ON_3dPoint& target, double lo, double hi) {
    auto distance = [&](double t) {
        const ON_3dPoint uv = trim.PointAt(t);
        return surface.PointAt(uv.x, uv.y).DistanceTo(target);
    };
    const double g = 0.5 * (std::sqrt(5.0) - 1.0);
    double a = lo, b = hi;
    double c = b - g * (b - a), d = a + g * (b - a);
    double fc = distance(c), fd = distance(d);
    for (int i = 0; i < 48; ++i) {
        if (fc < fd) { b = d; d = c; fd = fc; c = b - g * (b - a); fc = distance(c); }
        else { a = c; c = d; fc = fd; d = a + g * (b - a); fd = distance(d); }
    }
    return 0.5 * (a + b);
}

void pushPoint(const ON_Surface& surface, double u, double v, const ON_3dPoint& p, std::vector<double>& points) {
    double nu = u, nv = v;
    surface.GetNurbFormParameterFromSurfaceParameter(u, v, &nu, &nv);
    points.push_back(nu);
    points.push_back(nv);
    points.push_back(p.x);
    points.push_back(p.y);
    points.push_back(p.z);
}

// One trim's points, all but its last (the next trim's first). With an edge: the edge's samples, in
// the trim's direction, each at the trim parameter whose image is nearest it. Singular: the trim's own
// samples, all at its vertex. Neither: the trim's own samples and their images (no topology).
void appendTrim(const ON_Brep& brep, const ON_BrepTrim& trim, const ON_Surface& surface,
                std::vector<EdgeSamples>& cache, std::vector<double>& points, int& untopological) {
    const ON_Interval domain = trim.Domain();
    const bool hasEdge = trim.m_ei >= 0 && trim.m_ei < brep.m_E.Count() && trim.m_type != ON_BrepTrim::singular;
    if (hasEdge) {
        const EdgeSamples& e = edgeSamples(brep, trim.m_ei, cache);
        const int n = static_cast<int>(e.points.size()) - 1;
        // Dense images of the trim, to bracket each sample before refining it.
        const int dense = std::max(64, 8 * n);
        std::vector<ON_3dPoint> image(dense + 1);
        for (int j = 0; j <= dense; ++j) {
            const ON_3dPoint uv = trim.PointAt(domain.ParameterAt(static_cast<double>(j) / dense));
            image[j] = surface.PointAt(uv.x, uv.y);
        }
        int from = 0;
        for (int k = 0; k < n; ++k) {
            const ON_3dPoint& p = e.points[trim.m_bRev3d ? n - k : k];
            double t = domain[0];
            if (k > 0) {
                int best = from;
                double bestD = image[from].DistanceTo(p);
                for (int j = from + 1; j <= dense; ++j) {
                    const double dj = image[j].DistanceTo(p);
                    if (dj < bestD) { bestD = dj; best = j; }
                }
                from = best;
                const double lo = domain.ParameterAt(static_cast<double>(std::max(best - 1, 0)) / dense);
                const double hi = domain.ParameterAt(static_cast<double>(std::min(best + 1, dense)) / dense);
                t = nearestOnTrim(trim, surface, p, lo, hi);
            }
            const ON_3dPoint uv = trim.PointAt(t);
            pushPoint(surface, uv.x, uv.y, p, points);
        }
        return;
    }
    const int spans = trim.SpanCount();
    if (spans <= 0) return;
    std::vector<double> knots(spans + 1);
    if (!trim.GetSpanVector(knots.data())) return;
    const int per = samplesPerSpan(trim);
    const bool singular = trim.m_type == ON_BrepTrim::singular && trim.m_vi[0] >= 0;
    if (!singular) ++untopological;
    for (int s = 0; s < spans; ++s) {
        for (int k = 0; k < per; ++k) {
            const ON_3dPoint uv = trim.PointAt(knots[s] + (knots[s + 1] - knots[s]) * k / per);
            const ON_3dPoint p = singular ? brep.m_V[trim.m_vi[0]].point : surface.PointAt(uv.x, uv.y);
            pushPoint(surface, uv.x, uv.y, p, points);
        }
    }
}

void appendLoop(const ON_Brep& brep, const ON_BrepLoop& loop, const ON_Surface& surface,
                std::vector<EdgeSamples>& cache, std::vector<double>& out, int& untopological) {
    std::vector<double> points;
    for (int t = 0; t < loop.m_ti.Count(); ++t) appendTrim(brep, brep.m_T[loop.m_ti[t]], surface, cache, points, untopological);
    out.push_back(loop.m_type == ON_BrepLoop::inner ? 2.0 : 1.0);
    out.push_back(static_cast<double>(points.size() / 5));
    out.insert(out.end(), points.begin(), points.end());
}

// False when a face's surface has no NURBS form; the face is then packed with zero CVs.
void appendFace(const ON_Brep& brep, const ON_BrepFace& face, std::vector<EdgeSamples>& cache, std::vector<double>& out, int& untopological) {
    out.push_back(faceHasMesh(face) ? 1.0 : 0.0);
    out.push_back(face.m_bRev ? 1.0 : 0.0);
    const ON_Surface* surface = face.m_si >= 0 && face.m_si < brep.m_S.Count() ? brep.m_S[face.m_si] : nullptr;
    ON_NurbsSurface nurbs;
    if (surface == nullptr || surface->GetNurbForm(nurbs, 0.0) == 0) {
        for (int i = 0; i < 4; ++i) out.push_back(0.0);  // degrees and counts 0: nothing to evaluate
        out.push_back(0.0);                                // no loops
        return;
    }
    const int cu = nurbs.CVCount(0), cv = nurbs.CVCount(1);
    out.push_back(nurbs.Degree(0));
    out.push_back(nurbs.Degree(1));
    out.push_back(cu);
    out.push_back(cv);
    for (int dir = 0; dir < 2; ++dir) {
        const int n = nurbs.KnotCount(dir);
        const double* k = nurbs.Knot(dir);
        out.push_back(k[0]);  // openNURBS drops the two superfluous end knots; restore them
        out.insert(out.end(), k, k + n);
        out.push_back(k[n - 1]);
    }
    for (int i = 0; i < cu; ++i) {
        for (int j = 0; j < cv; ++j) {
            ON_4dPoint h;
            nurbs.GetCV(i, j, h);
            out.push_back(h.x);
            out.push_back(h.y);
            out.push_back(h.z);
            out.push_back(h.w);
        }
    }
    out.push_back(face.m_li.Count());
    for (int l = 0; l < face.m_li.Count(); ++l) appendLoop(brep, brep.m_L[face.m_li[l]], *surface, cache, out, untopological);
}

// A SubD as triangles: openNURBS's own Catmull-Clark subdivision, `level` times, then every vertex
// of the subdivided net moved to its limit surface point, with the limit normal for that face's
// sector (so a crease keeps one normal per side). Per triangle corner: x y z nx ny nz.
std::vector<double> packSubD(const ON_SubD& source, int level) {
    std::vector<double> out;
    ON_SubD subd(source);
    if (level > 0) subd.GlobalSubdivide(static_cast<unsigned int>(level));
    ON_SubDFaceIterator faces = subd.FaceIterator();
    std::vector<ON_3dPoint> p;
    std::vector<ON_3dVector> n;
    for (const ON_SubDFace* face = faces.FirstFace(); face != nullptr; face = faces.NextFace()) {
        const unsigned int count = face->EdgeCount();
        if (count < 3) continue;
        p.clear();
        n.clear();
        bool ok = true;
        for (unsigned int i = 0; i < count; ++i) {
            const ON_SubDVertex* v = face->Vertex(i);
            if (v == nullptr) { ok = false; break; }
            const ON_3dPoint point = v->SurfacePoint();
            if (!point.IsValid()) { ok = false; break; }
            p.push_back(point);
            n.push_back(v->SurfaceNormal(face, true));
        }
        if (!ok) continue;
        for (unsigned int i = 1; i + 1 < count; ++i) {
            const unsigned int corner[3] = {0, i, i + 1};
            for (unsigned int c : corner) {
                out.push_back(p[c].x); out.push_back(p[c].y); out.push_back(p[c].z);
                out.push_back(n[c].x); out.push_back(n[c].y); out.push_back(n[c].z);
            }
        }
    }
    return out;
}

std::vector<double> pack(const ON_Brep& brep) {
    std::vector<double> out;
    std::vector<EdgeSamples> cache(brep.m_E.Count());
    int untopological = 0;
    out.push_back(brep.m_F.Count());
    for (int f = 0; f < brep.m_F.Count(); ++f) appendFace(brep, brep.m_F[f], cache, out, untopological);
    out.push_back(untopological);
    return out;
}

}  // namespace

extern "C" {

int field_opennurbs_breps_open(const char* utf8Path, int subdLevel, void** handleOut, char* message, int messageCapacity) {
    if (handleOut != nullptr) *handleOut = nullptr;
    if (utf8Path == nullptr || handleOut == nullptr) {
        brepMessage(message, messageCapacity, "null path or handle");
        return FIELD_OPENNURBS_BAD_INPUT;
    }
    try {
        FILE* fp = ON::OpenFile(utf8Path, "rb");
        if (fp == nullptr) {
            brepMessage(message, messageCapacity, std::string("cannot open ") + utf8Path);
            return FIELD_OPENNURBS_BAD_INPUT;
        }
        ON_BinaryFile archive(ON::archive_mode::read3dm, fp);
        ONX_Model onx;
        const bool ok = onx.Read(archive, nullptr);
        const int version = archive.Archive3dmVersion();
        ON::CloseFile(fp);
        if (!ok && version == 0) {
            brepMessage(message, messageCapacity, std::string(utf8Path) + " is not a .3dm file openNURBS can read");
            return FIELD_OPENNURBS_READ_FAILED;
        }
        Breps* breps = new Breps();
        ONX_ModelComponentIterator it(onx, ON_ModelComponent::Type::ModelGeometry);
        for (const ON_ModelComponent* component = it.FirstComponent(); component != nullptr; component = it.NextComponent()) {
            const ON_ModelGeometryComponent* mgc = ON_ModelGeometryComponent::Cast(component);
            if (mgc == nullptr) continue;
            const ON_3dmObjectAttributes* attributes = mgc->Attributes(&ON_3dmObjectAttributes::DefaultAttributes);
            if (attributes->IsInstanceDefinitionObject()) continue;
            const ON_Geometry* geometry = mgc->Geometry(nullptr);
            if (const ON_Brep* brep = ON_Brep::Cast(geometry)) {
                breps->ids.push_back(brepId(attributes->m_uuid));
                breps->data.push_back(pack(*brep));
            } else if (const ON_Extrusion* extrusion = ON_Extrusion::Cast(geometry)) {
                if (ON_Brep* form = extrusion->BrepForm()) {
                    breps->ids.push_back(brepId(attributes->m_uuid));
                    breps->data.push_back(pack(*form));
                    delete form;
                }
            } else if (const ON_SubD* subd = ON_SubD::Cast(geometry)) {
                if (subdLevel >= 0) {
                    breps->subdIds.push_back(brepId(attributes->m_uuid));
                    breps->subdData.push_back(packSubD(*subd, subdLevel > 6 ? 6 : subdLevel));
                }
            }
        }
        *handleOut = breps;
        return FIELD_OPENNURBS_OK;
    } catch (const std::bad_alloc&) {
        brepMessage(message, messageCapacity, "out of memory reading Breps");
        return FIELD_OPENNURBS_OUT_OF_MEMORY;
    } catch (const std::exception& e) {
        brepMessage(message, messageCapacity, e.what());
        return FIELD_OPENNURBS_FAILED;
    } catch (...) {
        brepMessage(message, messageCapacity, "unknown failure reading Breps");
        return FIELD_OPENNURBS_FAILED;
    }
}

void field_opennurbs_breps_close(void* handle) { delete static_cast<Breps*>(handle); }

int field_opennurbs_breps_count(void* handle) {
    return handle == nullptr ? 0 : static_cast<int>(static_cast<Breps*>(handle)->ids.size());
}

const char* field_opennurbs_breps_id(void* handle, int index) {
    if (handle == nullptr || index < 0 || index >= field_opennurbs_breps_count(handle)) return "";
    return static_cast<Breps*>(handle)->ids[index].c_str();
}

int field_opennurbs_breps_length(void* handle, int index) {
    if (handle == nullptr || index < 0 || index >= field_opennurbs_breps_count(handle)) return 0;
    return static_cast<int>(static_cast<Breps*>(handle)->data[index].size());
}

const double* field_opennurbs_breps_data(void* handle, int index) {
    if (handle == nullptr || index < 0 || index >= field_opennurbs_breps_count(handle)) return nullptr;
    return static_cast<Breps*>(handle)->data[index].data();
}

int field_opennurbs_subds_count(void* handle) {
    return handle == nullptr ? 0 : static_cast<int>(static_cast<Breps*>(handle)->subdIds.size());
}

const char* field_opennurbs_subds_id(void* handle, int index) {
    if (handle == nullptr || index < 0 || index >= field_opennurbs_subds_count(handle)) return "";
    return static_cast<Breps*>(handle)->subdIds[index].c_str();
}

int field_opennurbs_subds_length(void* handle, int index) {
    if (handle == nullptr || index < 0 || index >= field_opennurbs_subds_count(handle)) return 0;
    return static_cast<int>(static_cast<Breps*>(handle)->subdData[index].size());
}

const double* field_opennurbs_subds_data(void* handle, int index) {
    if (handle == nullptr || index < 0 || index >= field_opennurbs_subds_count(handle)) return nullptr;
    return static_cast<Breps*>(handle)->subdData[index].data();
}


}  // extern "C"
