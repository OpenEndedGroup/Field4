// Brep surfaces and trim loops behind a flat C ABI. See field_opennurbs_brep.h for the packed
// layout; this file only gathers and packs. The tessellation is on the JVM side.

#include "field_opennurbs_brep.h"
#include "field_opennurbs.h"

#include "opennurbs.h"

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

void appendLoop(const ON_Brep& brep, const ON_BrepLoop& loop, const ON_Surface& surface, std::vector<double>& out) {
    std::vector<double> points;
    for (int t = 0; t < loop.m_ti.Count(); ++t) {
        const ON_BrepTrim& trim = brep.m_T[loop.m_ti[t]];
        const int spans = trim.SpanCount();
        if (spans <= 0) continue;
        std::vector<double> knots(spans + 1);
        if (!trim.GetSpanVector(knots.data())) continue;
        const int per = samplesPerSpan(trim);
        // Every point but the trim's last, which is the next trim's first.
        for (int s = 0; s < spans; ++s) {
            for (int k = 0; k < per; ++k) {
                const double param = knots[s] + (knots[s + 1] - knots[s]) * k / per;
                const ON_3dPoint p = trim.PointAt(param);
                double u = p.x, v = p.y;
                surface.GetNurbFormParameterFromSurfaceParameter(p.x, p.y, &u, &v);
                points.push_back(u);
                points.push_back(v);
            }
        }
    }
    out.push_back(loop.m_type == ON_BrepLoop::inner ? 2.0 : 1.0);
    out.push_back(static_cast<double>(points.size() / 2));
    out.insert(out.end(), points.begin(), points.end());
}

// False when a face's surface has no NURBS form; the face is then packed with zero CVs.
void appendFace(const ON_Brep& brep, const ON_BrepFace& face, std::vector<double>& out) {
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
    for (int l = 0; l < face.m_li.Count(); ++l) appendLoop(brep, brep.m_L[face.m_li[l]], *surface, out);
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
    out.push_back(brep.m_F.Count());
    for (int f = 0; f < brep.m_F.Count(); ++f) appendFace(brep, brep.m_F[f], out);
    return out;
}

// ---------------------------------------------------------------------------------------------
// The fixture

ON_3dmObjectAttributes* brepAttributes(int layer, const wchar_t* name) {
    ON_3dmObjectAttributes* attributes = new ON_3dmObjectAttributes();
    attributes->m_layer_index = layer;
    attributes->SetName(name, true);
    return attributes;
}

ON_Brep* squareWithHole() {
    const ON_Plane plane(ON_3dPoint(30, -10, 0), ON_3dVector::ZAxis);
    ON_SimpleArray<ON_Curve*> outer;
    const ON_3dPoint c[4] = {ON_3dPoint(20, -20, 0), ON_3dPoint(40, -20, 0), ON_3dPoint(40, 0, 0), ON_3dPoint(20, 0, 0)};
    for (int i = 0; i < 4; ++i) outer.Append(new ON_LineCurve(c[i], c[(i + 1) % 4]));
    ON_Brep* brep = ON_BrepTrimmedPlane(plane, outer, false);
    if (brep == nullptr) return nullptr;
    ON_SimpleArray<ON_Curve*> hole;
    // Inner loops run clockwise seen from the face's normal.
    ON_Circle circle(ON_Plane(ON_3dPoint(30, -10, 0), -ON_3dVector::ZAxis), 5.0);
    hole.Append(new ON_ArcCurve(circle));
    brep->NewPlanarFaceLoop(0, ON_BrepLoop::inner, hole, false);
    return brep;
}

bool writeBrepFixture(const char* path, std::string& error) {
    ONX_Model model;
    model.m_properties.m_RevisionHistory.NewRevision();
    model.m_settings.m_ModelUnitsAndTolerances.m_unit_system = ON_UnitSystem::Millimeters;
    model.AddDefaultLayer(nullptr, ON_Color::UnsetColor);
    const int nurbs = model.AddLayer(L"NURBS", ON_Color(70, 130, 200));
    const int boxes = model.AddLayer(L"Boxes", ON_Color(200, 120, 40));

    ON_Brep* sphere = ON_BrepSphere(ON_Sphere(ON_3dPoint(0, 0, 0), 10.0));
    ON_Brep* cylinder = ON_BrepCylinder(ON_Cylinder(ON_Circle(ON_Plane(ON_3dPoint(30, 20, -10), ON_3dVector::ZAxis), 5.0), 20.0), true, true);
    ON_Brep* plate = squareWithHole();
    if (sphere == nullptr || cylinder == nullptr || plate == nullptr) {
        error = "openNURBS could not build the fixture Breps";
        return false;
    }
    model.AddManagedModelGeometryComponent(sphere, brepAttributes(nurbs, L"sphere"));
    model.AddManagedModelGeometryComponent(cylinder, brepAttributes(nurbs, L"capped cylinder"));
    model.AddManagedModelGeometryComponent(plate, brepAttributes(nurbs, L"plate with hole"));

    // A box with a saved render mesh per face (one quad), and the same box without, side by side.
    for (int saved = 0; saved < 2; ++saved) {
        const double x = saved ? -30.0 : -15.0;
        ON_3dPoint corners[8] = {
            ON_3dPoint(x, 20, -5), ON_3dPoint(x + 10, 20, -5), ON_3dPoint(x + 10, 30, -5), ON_3dPoint(x, 30, -5),
            ON_3dPoint(x, 20, 5), ON_3dPoint(x + 10, 20, 5), ON_3dPoint(x + 10, 30, 5), ON_3dPoint(x, 30, 5)};
        ON_Brep* box = ON_BrepBox(corners);
        if (saved) {
            for (int f = 0; f < box->m_F.Count(); ++f) {
                ON_BrepFace& face = box->m_F[f];
                const ON_BrepLoop* loop = face.OuterLoop();
                if (loop == nullptr || loop->m_ti.Count() != 4) continue;
                ON_Mesh* quad = new ON_Mesh(1, 4, false, false);
                for (int i = 0; i < 4; ++i) quad->SetVertex(i, box->m_V[box->m_T[loop->m_ti[i]].m_vi[0]].point);
                if (face.m_bRev) quad->SetQuad(0, 0, 3, 2, 1); else quad->SetQuad(0, 0, 1, 2, 3);
                quad->ComputeVertexNormals();
                face.SetMesh(ON::render_mesh, quad);
            }
        }
        model.AddManagedModelGeometryComponent(box, brepAttributes(boxes, saved ? L"box, saved mesh" : L"box, no mesh"));
    }

    // SubDs: a smooth one (its limit surface is a rounded blob inside the cage) and a creased one
    // (its limit surface is the box itself), each a 2 x 2 x 2 cage 10 across.
    const int subds = model.AddLayer(L"SubD", ON_Color(90, 170, 90));
    for (int creased = 0; creased < 2; ++creased) {
        const double x = creased ? 15.0 : -5.0;
        const ON_3dPoint corners[8] = {
            ON_3dPoint(x, -45, -5), ON_3dPoint(x + 10, -45, -5), ON_3dPoint(x + 10, -35, -5), ON_3dPoint(x, -35, -5),
            ON_3dPoint(x, -45, 5), ON_3dPoint(x + 10, -45, 5), ON_3dPoint(x + 10, -35, 5), ON_3dPoint(x, -35, 5)};
        ON_SubD* subd = ON_SubD::CreateSubDBox(corners, creased ? ON_SubDEdgeTag::Crease : ON_SubDEdgeTag::Smooth, 2, 2, 2, nullptr);
        if (subd == nullptr) {
            error = "openNURBS could not build the fixture SubD";
            return false;
        }
        model.AddManagedModelGeometryComponent(subd, brepAttributes(subds, creased ? L"creased SubD" : L"smooth SubD"));
    }

    FILE* fp = ON::OpenFile(path, "wb");
    if (fp == nullptr) {
        error = std::string("cannot open ") + path + " for writing";
        return false;
    }
    ON_BinaryFile archive(ON::archive_mode::write3dm, fp);
    ON_wString log;
    ON_TextLog textLog(log);
    const bool ok = model.Write(archive, 7, &textLog);
    ON::CloseFile(fp);
    if (!ok) error = "openNURBS failed to write the Brep fixture";
    return ok;
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

int field_opennurbs_write_brep_fixture(const char* utf8Path, char* message, int messageCapacity) {
    if (utf8Path == nullptr) {
        brepMessage(message, messageCapacity, "null path");
        return FIELD_OPENNURBS_BAD_INPUT;
    }
    try {
        std::string error;
        if (!writeBrepFixture(utf8Path, error)) {
            brepMessage(message, messageCapacity, error);
            return FIELD_OPENNURBS_FAILED;
        }
        return FIELD_OPENNURBS_OK;
    } catch (const std::exception& e) {
        brepMessage(message, messageCapacity, e.what());
        return FIELD_OPENNURBS_FAILED;
    } catch (...) {
        brepMessage(message, messageCapacity, "unknown failure writing the Brep fixture");
        return FIELD_OPENNURBS_FAILED;
    }
}

}  // extern "C"
