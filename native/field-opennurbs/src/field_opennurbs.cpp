// openNURBS behind a flat C ABI. See field_opennurbs.h for the contract; the notes here are about
// how the conversion is done.

#include "field_opennurbs.h"

#include "opennurbs.h"

#include <cmath>
#include <cstring>
#include <exception>
#include <map>
#include <new>
#include <string>
#include <vector>

namespace {

// ---------------------------------------------------------------------------------------------
// The converted model. Everything an accessor returns points in here.

struct Layer {
    std::string name, path, id;
    int parent = -1;
    int color = 0;
    int flags = 0;
};

struct Object {
    int kind = FIELD_OPENNURBS_SKIPPED;
    int layer = -1;
    int color = 0;
    int flags = 0;
    int hAlign = 0, vAlign = 0;
    std::string name, id, type, text, note, block;
    std::vector<double> points;
    std::vector<int> indices;
    std::vector<float> normals;
    // A mesh's vertex colours as ARGB, one a vertex; empty unless every piece of the object has them.
    std::vector<int> colors;
    std::vector<double> frame;
    // A Brep's or extrusion's edges, as a path in the same form as a curve's: what Rhino draws as
    // the object's wireframe, which a render mesh's facets are not.
    std::vector<double> edgePoints;
    std::vector<int> edgeIndices;
};

struct Model {
    int archiveVersion = 0;
    int unitSystem = 0;
    double metersPerUnit = 0;
    std::string log;
    std::vector<Layer> layers;
    std::vector<Object> objects;
};

void writeMessage(char* message, int capacity, const std::string& text) {
    if (message == nullptr || capacity <= 0) return;
    const size_t n = std::min(text.size(), static_cast<size_t>(capacity - 1));
    std::memcpy(message, text.data(), n);
    message[n] = 0;
}

// wchar_t is UTF-32 on macOS and Linux and UTF-16 on Windows; openNURBS strings are wchar_t.
std::string utf8(const wchar_t* s) {
    std::string out;
    if (s == nullptr) return out;
    for (size_t i = 0; s[i] != 0; ++i) {
        uint32_t c = static_cast<uint32_t>(s[i]);
        if (sizeof(wchar_t) == 2 && c >= 0xD800 && c <= 0xDBFF && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (static_cast<uint32_t>(s[i + 1]) - 0xDC00);
            ++i;
        }
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    return out;
}

std::string utf8(const ON_wString& s) { return utf8(static_cast<const wchar_t*>(s)); }

std::string idString(const ON_UUID& id) {
    char buffer[64] = {0};
    ON_UuidToString(id, buffer);
    return buffer;
}

// ON_Color's alpha is transparency (0 opaque); the ABI's is opacity.
int argb(const ON_Color& c) {
    const unsigned a = 255u - static_cast<unsigned>(c.Alpha());
    return static_cast<int>((a << 24) | (static_cast<unsigned>(c.Red()) << 16) |
                            (static_cast<unsigned>(c.Green()) << 8) | static_cast<unsigned>(c.Blue()));
}

// ---------------------------------------------------------------------------------------------
// Meshes

void appendMesh(const ON_Mesh& mesh, const ON_Xform* xform, Object& out, bool& normalsOk, bool& colorsOk) {
    const int base = static_cast<int>(out.points.size() / 3);
    const int vertexCount = mesh.VertexCount();
    const bool hasColors = mesh.HasVertexColors() && mesh.m_C.Count() == vertexCount;
    if (!hasColors) colorsOk = false;
    for (int i = 0; i < vertexCount; ++i) out.colors.push_back(hasColors ? argb(mesh.m_C[i]) : 0);
    const bool hasNormals = mesh.HasVertexNormals() && mesh.m_N.Count() == vertexCount;
    // Normals must be all-or-nothing across the pieces of one object.
    if (!hasNormals) normalsOk = false;
    for (int i = 0; i < vertexCount; ++i) {
        ON_3dPoint p = mesh.Vertex(i);
        if (xform != nullptr) p = (*xform) * p;
        out.points.push_back(p.x);
        out.points.push_back(p.y);
        out.points.push_back(p.z);
        if (hasNormals) {
            ON_3dVector n(mesh.m_N[i]);
            if (xform != nullptr) {
                // Normals transform by the inverse transpose; for the rigid and uniformly scaled
                // transforms blocks almost always use, the linear part then unitize is enough.
                ON_Xform normalXform = *xform;
                normalXform.m_xform[0][3] = normalXform.m_xform[1][3] = normalXform.m_xform[2][3] = 0;
                n = normalXform * n;
                n.Unitize();
            }
            out.normals.push_back(static_cast<float>(n.x));
            out.normals.push_back(static_cast<float>(n.y));
            out.normals.push_back(static_cast<float>(n.z));
        } else {
            out.normals.insert(out.normals.end(), {0.f, 0.f, 0.f});
        }
    }
    const bool flip = xform != nullptr && xform->Determinant() < 0;
    for (int f = 0; f < mesh.FaceCount(); ++f) {
        const ON_MeshFace& face = mesh.m_F[f];
        if (!face.IsValid(vertexCount)) continue;
        auto tri = [&](int a, int b, int c) {
            out.indices.push_back(base + a);
            out.indices.push_back(base + (flip ? c : b));
            out.indices.push_back(base + (flip ? b : c));
        };
        tri(face.vi[0], face.vi[1], face.vi[2]);
        if (face.IsQuad()) tri(face.vi[0], face.vi[2], face.vi[3]);
    }
}

void finishMesh(Object& out, bool normalsOk, bool colorsOk) {
    out.kind = FIELD_OPENNURBS_MESH;
    if (!normalsOk) out.normals.clear();
    if (!colorsOk) out.colors.clear();
    if (out.indices.empty()) {
        out.kind = FIELD_OPENNURBS_SKIPPED;
        if (out.note.empty()) out.note = "mesh has no faces";
        out.points.clear();
        out.normals.clear();
        out.colors.clear();
    }
}

// ---------------------------------------------------------------------------------------------
// Curves, as move / line / cubic paths

struct Path {
    Object& out;
    bool started = false;
    ON_3dPoint last = ON_3dPoint::UnsetPoint;
    double gap;
    // Where the path goes: the object's own points and indices, or its edge path.
    std::vector<double>* points = nullptr;
    std::vector<int>* ops = nullptr;

    Path(Object& object, double gapTolerance, bool edges = false)
        : out(object), gap(gapTolerance),
          points(edges ? &object.edgePoints : &object.points),
          ops(edges ? &object.edgeIndices : &object.indices) {}

    void push(const ON_3dPoint& p) {
        points->push_back(p.x);
        points->push_back(p.y);
        points->push_back(p.z);
    }
    void moveIfNeeded(const ON_3dPoint& p) {
        if (!started || last.DistanceTo(p) > gap) {
            ops->push_back(FIELD_OPENNURBS_OP_MOVE);
            push(p);
            started = true;
        }
        last = p;
    }
    void move(const ON_3dPoint& p) {
        ops->push_back(FIELD_OPENNURBS_OP_MOVE);
        push(p);
        started = true;
        last = p;
    }
    void line(const ON_3dPoint& p) {
        ops->push_back(FIELD_OPENNURBS_OP_LINE);
        push(p);
        last = p;
    }
    void cubic(const ON_3dPoint& c1, const ON_3dPoint& c2, const ON_3dPoint& p) {
        ops->push_back(FIELD_OPENNURBS_OP_CUBIC);
        push(c1);
        push(c2);
        push(p);
        last = p;
    }
};

ON_3dPoint cubicAt(const ON_3dPoint& p0, const ON_3dPoint& c1, const ON_3dPoint& c2, const ON_3dPoint& p3, double t) {
    const double s = 1 - t;
    return s * s * s * p0 + 3 * s * s * t * c1 + 3 * s * t * t * c2 + t * t * t * p3;
}

// A Bezier span that is rational or above degree 3, replaced by Hermite cubics matching its end
// points and tangents, split until each piece is within `tolerance` at three interior samples.
void approximateSpan(const ON_BezierCurve& bez, double t0, double t1, double tolerance, int depth, Path& path) {
    ON_3dPoint p0, p1;
    ON_3dVector d0, d1;
    bez.Ev1Der(t0, p0, d0);
    bez.Ev1Der(t1, p1, d1);
    const double h = (t1 - t0) / 3.0;
    const ON_3dPoint c1 = p0 + h * d0;
    const ON_3dPoint c2 = p1 - h * d1;
    double error = 0;
    for (double u : {0.25, 0.5, 0.75}) {
        error = std::max(error, bez.PointAt(t0 + u * (t1 - t0)).DistanceTo(cubicAt(p0, c1, c2, p1, u)));
    }
    if (error <= tolerance || depth >= 12) {
        path.cubic(c1, c2, p1);
        return;
    }
    const double tm = 0.5 * (t0 + t1);
    approximateSpan(bez, t0, tm, tolerance, depth + 1, path);
    approximateSpan(bez, tm, t1, tolerance, depth + 1, path);
}

void appendNurbs(const ON_NurbsCurve& nurbs, double tolerance, Path& path) {
    const int spans = nurbs.m_cv_count - nurbs.m_order + 1;
    for (int span = 0; span < spans; ++span) {
        ON_BezierCurve bez;
        if (!nurbs.ConvertSpanToBezier(span, bez)) continue;  // an empty span: a repeated knot
        path.moveIfNeeded(bez.PointAt(0));
        const int degree = bez.Degree();
        if (!bez.IsRational() && degree == 1) {
            path.line(bez.PointAt(1));
        } else if (!bez.IsRational() && degree <= 3) {
            if (degree < 3) bez.IncreaseDegree(3);  // exact for a polynomial curve
            ON_3dPoint c1, c2, p3;
            bez.GetCV(1, c1);
            bez.GetCV(2, c2);
            bez.GetCV(3, p3);
            path.cubic(c1, c2, p3);
        } else {
            if (path.ops == &path.out.indices) path.out.flags |= FIELD_OPENNURBS_APPROXIMATED;
            approximateSpan(bez, 0, 1, tolerance, 0, path);
        }
    }
}

void appendCurve(const ON_Curve& curve, double tolerance, Path& path) {
    if (const ON_PolyCurve* poly = ON_PolyCurve::Cast(&curve)) {
        for (int i = 0; i < poly->Count(); ++i) {
            if (const ON_Curve* segment = poly->SegmentCurve(i)) appendCurve(*segment, tolerance, path);
        }
        return;
    }
    ON_Polyline polyline;
    if (const ON_PolylineCurve* pc = ON_PolylineCurve::Cast(&curve)) {
        polyline = pc->m_pline;
    } else if (const ON_LineCurve* lc = ON_LineCurve::Cast(&curve)) {
        polyline.Append(lc->m_line.from);
        polyline.Append(lc->m_line.to);
    }
    if (polyline.Count() >= 2) {
        path.moveIfNeeded(polyline[0]);
        for (int i = 1; i < polyline.Count(); ++i) path.line(polyline[i]);
        return;
    }
    ON_NurbsCurve nurbs;
    if (curve.GetNurbForm(nurbs) == 0) {
        path.out.note = "curve has no NURBS form";
        return;
    }
    appendNurbs(nurbs, tolerance, path);
}

double curveTolerance(const ON_Curve& curve) {
    const ON_BoundingBox box = curve.BoundingBox();
    const double diagonal = box.IsValid() ? box.Diagonal().Length() : 1.0;
    return std::max(1e-9, 1e-5 * diagonal);
}

// ---------------------------------------------------------------------------------------------
// The reader

class Reader {
public:
    Reader(const ONX_Model& model, Model& result) : model_(model), result_(result) {}

    void run() {
        readLayers();
        ONX_ModelComponentIterator it(model_, ON_ModelComponent::Type::ModelGeometry);
        for (const ON_ModelComponent* component = it.FirstComponent(); component != nullptr; component = it.NextComponent()) {
            const ON_ModelGeometryComponent* mgc = ON_ModelGeometryComponent::Cast(component);
            if (mgc == nullptr) continue;
            const ON_3dmObjectAttributes* attributes = mgc->Attributes(&ON_3dmObjectAttributes::DefaultAttributes);
            // Block members live in the geometry table too; they are drawn through their instances.
            if (attributes->IsInstanceDefinitionObject()) continue;
            Context top;
            top.id = idString(attributes->m_uuid);
            top.parentColor = layerColor(attributes->m_layer_index);
            top.visible = attributes->IsVisible() && attributes->Mode() != ON::hidden_object;
            convert(mgc->Geometry(nullptr), *attributes, top);
        }
    }

private:
    struct Context {
        const ON_Xform* xform = nullptr;
        ON_Xform xformStorage;
        std::string id;
        std::string block;
        ON_Color parentColor = ON_Color::Black;
        bool visible = true;
        int depth = 0;
    };

    const ONX_Model& model_;
    Model& result_;
    std::map<int, int> layerByModelIndex_;
    std::vector<ON_Color> layerColors_;

    void readLayers() {
        std::map<std::string, int> byId;
        std::vector<std::string> parentIds;
        ONX_ModelComponentIterator it(model_, ON_ModelComponent::Type::Layer);
        for (const ON_ModelComponent* component = it.FirstComponent(); component != nullptr; component = it.NextComponent()) {
            const ON_Layer* layer = ON_Layer::Cast(component);
            if (layer == nullptr) continue;
            Layer out;
            out.name = utf8(layer->NameAsPointer());
            out.id = idString(layer->Id());
            out.color = argb(layer->Color());
            out.flags = (layer->IsVisible() ? FIELD_OPENNURBS_VISIBLE : 0) | (layer->IsLocked() ? FIELD_OPENNURBS_LOCKED : 0);
            const int dense = static_cast<int>(result_.layers.size());
            layerByModelIndex_[layer->Index()] = dense;
            byId[out.id] = dense;
            parentIds.push_back(idString(layer->ParentLayerId()));
            layerColors_.push_back(layer->Color());
            result_.layers.push_back(out);
        }
        for (size_t i = 0; i < result_.layers.size(); ++i) {
            auto parent = byId.find(parentIds[i]);
            result_.layers[i].parent = parent == byId.end() ? -1 : parent->second;
        }
        for (size_t i = 0; i < result_.layers.size(); ++i) {
            std::string path = result_.layers[i].name;
            int p = result_.layers[i].parent;
            for (int guard = 0; p >= 0 && guard < 64; ++guard) {
                path = result_.layers[p].name + "::" + path;
                p = result_.layers[p].parent;
            }
            result_.layers[i].path = path;
        }
    }

    int denseLayer(int modelIndex) const {
        auto found = layerByModelIndex_.find(modelIndex);
        return found == layerByModelIndex_.end() ? -1 : found->second;
    }

    ON_Color layerColor(int modelIndex) const {
        const int dense = denseLayer(modelIndex);
        return dense < 0 ? ON_Color::Black : layerColors_[dense];
    }

    ON_Color resolveColor(const ON_3dmObjectAttributes& attributes, const Context& context) const {
        switch (attributes.ColorSource()) {
        case ON::color_from_object:
            return attributes.m_color;
        case ON::color_from_material: {
            const ON_ModelComponentReference ref = model_.RenderMaterialFromIndex(attributes.m_material_index);
            if (const ON_Material* material = ON_Material::Cast(ref.ModelComponent())) return material->Diffuse();
            return layerColor(attributes.m_layer_index);
        }
        case ON::color_from_parent:
            return context.depth > 0 ? context.parentColor : layerColor(attributes.m_layer_index);
        default:
            return layerColor(attributes.m_layer_index);
        }
    }

    Object& begin(const ON_Object* geometry, const ON_3dmObjectAttributes& attributes, const Context& context) {
        result_.objects.emplace_back();
        Object& out = result_.objects.back();
        out.layer = denseLayer(attributes.m_layer_index);
        out.color = argb(resolveColor(attributes, context));
        const bool visible = context.visible && attributes.IsVisible() && attributes.Mode() != ON::hidden_object;
        out.flags = (visible ? FIELD_OPENNURBS_VISIBLE : 0) | (context.depth > 0 ? FIELD_OPENNURBS_IN_BLOCK : 0);
        out.name = utf8(attributes.m_name);
        out.id = context.id;
        out.block = context.block;
        if (geometry != nullptr) {
            const ON_ClassId* classId = geometry->ClassId();
            out.type = classId != nullptr ? classId->ClassName() : "unknown";
        }
        return out;
    }

    void skip(Object& out, const std::string& why) {
        out.kind = FIELD_OPENNURBS_SKIPPED;
        out.note = why;
        out.points.clear();
        out.indices.clear();
        out.normals.clear();
        out.colors.clear();
        out.edgePoints.clear();
        out.edgeIndices.clear();
    }

    // Every edge once, each its own subpath. A tolerance from the whole Brep's size keeps a long
    // edge and a short one at the same visual accuracy.
    void appendEdges(const ON_Brep& brep, const ON_Xform* xform, Object& out) {
        const ON_BoundingBox box = brep.BoundingBox();
        const double tolerance = std::max(1e-9, 1e-4 * (box.IsValid() ? box.Diagonal().Length() : 1.0));
        for (int e = 0; e < brep.m_E.Count(); ++e) {
            const ON_BrepEdge& edge = brep.m_E[e];
            ON_Curve* curve = edge.DuplicateCurve();
            if (curve == nullptr) continue;
            if (xform != nullptr) curve->Transform(*xform);
            Path path(out, tolerance, true);
            appendCurve(*curve, tolerance, path);
            delete curve;
        }
    }

    void convert(const ON_Geometry* geometry, const ON_3dmObjectAttributes& attributes, const Context& context) {
        if (geometry == nullptr) return;
        if (const ON_InstanceRef* iref = ON_InstanceRef::Cast(geometry)) {
            expandInstance(*iref, attributes, context);
            return;
        }
        Object& out = begin(geometry, attributes, context);
        const ON_Xform* xform = context.xform;

        if (const ON_Mesh* mesh = ON_Mesh::Cast(geometry)) {
            bool normalsOk = true, colorsOk = true;
            appendMesh(*mesh, xform, out, normalsOk, colorsOk);
            finishMesh(out, normalsOk, colorsOk);
        } else if (const ON_Brep* brep = ON_Brep::Cast(geometry)) {
            bool normalsOk = true, colorsOk = true;
            int missing = 0;
            for (int f = 0; f < brep->m_F.Count(); ++f) {
                const ON_Mesh* faceMesh = brep->m_F[f].Mesh(ON::render_mesh);
                if (faceMesh == nullptr) faceMesh = brep->m_F[f].Mesh(ON::any_mesh);
                // Rhino sometimes saves an empty mesh for a face it never managed to shade
                // (slivers, faces left over from a failed fillet); that is no mesh either.
                if (faceMesh == nullptr || faceMesh->FaceCount() == 0) { ++missing; continue; }
                appendMesh(*faceMesh, xform, out, normalsOk, colorsOk);
            }
            appendEdges(*brep, xform, out);
            if (missing == brep->m_F.Count()) {
                // Its edges survive: a Brep with no mesh can still be drawn as Rhino's wireframe.
                std::vector<double> edgePoints = std::move(out.edgePoints);
                std::vector<int> edgeIndices = std::move(out.edgeIndices);
                skip(out, "Brep has no usable cached render mesh (absent after Save Small, or saved empty); "
                          "openNURBS cannot mesh a Brep, so re-save it from Rhino with meshes");
                out.edgePoints = std::move(edgePoints);
                out.edgeIndices = std::move(edgeIndices);
            } else {
                if (missing > 0) {
                    out.flags |= FIELD_OPENNURBS_PARTIAL;
                    out.note = std::to_string(missing) + " of " + std::to_string(brep->m_F.Count()) +
                               " faces have no usable cached render mesh";
                }
                finishMesh(out, normalsOk, colorsOk);
            }
        } else if (const ON_Extrusion* extrusion = ON_Extrusion::Cast(geometry)) {
            const ON_Mesh* mesh = extrusion->Mesh(ON::render_mesh);
            if (mesh == nullptr) mesh = extrusion->Mesh(ON::any_mesh);
            if (mesh == nullptr) {
                skip(out, "extrusion has no cached render mesh; re-save it from Rhino with meshes");
            } else {
                bool normalsOk = true, colorsOk = true;
                appendMesh(*mesh, xform, out, normalsOk, colorsOk);
                finishMesh(out, normalsOk, colorsOk);
                if (ON_Brep* brep = extrusion->BrepForm()) {
                    appendEdges(*brep, xform, out);
                    delete brep;
                }
            }
        } else if (ON_SubD::Cast(geometry) != nullptr) {
            // Public openNURBS declares ON_SubDMesh::ToMesh but does not define it; the limit
            // surface mesher ships only inside Rhino. A SubD is reported, never guessed at from its
            // control net, which is a different shape.
            skip(out, "SubD objects are not converted (public openNURBS has no SubD mesher); "
                      "in Rhino, ToNURBS or Mesh it first");
        } else if (const ON_Curve* curve = ON_Curve::Cast(geometry)) {
            convertCurve(*curve, xform, out, FIELD_OPENNURBS_CURVE);
        } else if (const ON_Hatch* hatch = ON_Hatch::Cast(geometry)) {
            out.kind = FIELD_OPENNURBS_HATCH;
            out.flags |= FIELD_OPENNURBS_CLOSED;
            for (int i = 0; i < hatch->LoopCount(); ++i) {
                ON_Curve* loop = hatch->LoopCurve3d(i);
                if (loop == nullptr) continue;
                if (xform != nullptr) loop->Transform(*xform);
                Path path(out, curveTolerance(*loop));
                appendCurve(*loop, path.gap, path);
                delete loop;
            }
            if (out.indices.empty()) skip(out, "hatch has no boundary loops");
        } else if (const ON_Text* text = ON_Text::Cast(geometry)) {
            convertText(*text, xform, out);
        } else if (const ON_Point* point = ON_Point::Cast(geometry)) {
            out.kind = FIELD_OPENNURBS_POINTS;
            ON_3dPoint p = point->point;
            if (xform != nullptr) p = (*xform) * p;
            out.points = {p.x, p.y, p.z};
        } else if (const ON_PointCloud* cloud = ON_PointCloud::Cast(geometry)) {
            out.kind = FIELD_OPENNURBS_POINTS;
            for (int i = 0; i < cloud->m_P.Count(); ++i) {
                ON_3dPoint p = cloud->m_P[i];
                if (xform != nullptr) p = (*xform) * p;
                out.points.insert(out.points.end(), {p.x, p.y, p.z});
            }
        } else if (ON_Annotation::Cast(geometry) != nullptr) {
            skip(out, "dimensions and leaders are not converted; plain text is");
        } else {
            skip(out, "not a kind this loader converts");
        }
    }

    void convertCurve(const ON_Curve& source, const ON_Xform* xform, Object& out, int kind) {
        const ON_Curve* curve = &source;
        ON_Curve* moved = nullptr;
        if (xform != nullptr) {
            moved = source.DuplicateCurve();
            if (moved != nullptr && moved->Transform(*xform)) curve = moved;
        }
        out.kind = kind;
        if (curve->IsClosed()) out.flags |= FIELD_OPENNURBS_CLOSED;
        const double tolerance = curveTolerance(*curve);
        Path path(out, tolerance);
        appendCurve(*curve, tolerance, path);
        delete moved;
        if (out.indices.empty()) skip(out, out.note.empty() ? "curve has no spans" : out.note);
    }

    void convertText(const ON_Text& text, const ON_Xform* xform, Object& out) {
        const ON_ModelComponentReference ref = model_.DimensionStyleFromId(text.DimensionStyleId());
        const ON_DimStyle* style = ON_DimStyle::Cast(ref.ModelComponent());
        if (style == nullptr) style = &ON_DimStyle::Default;
        out.kind = FIELD_OPENNURBS_TEXT;
        out.text = utf8(text.PlainText());
        ON_Plane plane = text.Plane();
        double height = text.TextHeight(style) * std::max(1e-12, text.DimScale(style));
        if (xform != nullptr) {
            const ON_3dPoint tip = (*xform) * (plane.origin + plane.yaxis);
            plane.Transform(*xform);
            const double scaled = plane.origin.DistanceTo(tip);
            if (scaled > 0) height *= scaled;
        }
        out.frame = {plane.origin.x, plane.origin.y, plane.origin.z,
                     plane.xaxis.x, plane.xaxis.y, plane.xaxis.z,
                     plane.yaxis.x, plane.yaxis.y, plane.yaxis.z, height};
        switch (text.TextHorizontalAlignment(style)) {
        case ON::TextHorizontalAlignment::Center: out.hAlign = 1; break;
        case ON::TextHorizontalAlignment::Right: out.hAlign = 2; break;
        default: out.hAlign = 0; break;
        }
        // The vertical attachment is passed through as openNURBS numbers it (0 top ... 6 bottom of
        // the bounding box); the header spells the values out.
        out.vAlign = static_cast<int>(text.TextVerticalAlignment(style));
    }

    void expandInstance(const ON_InstanceRef& iref, const ON_3dmObjectAttributes& attributes, const Context& context) {
        const ON_ModelComponentReference ref = model_.ComponentFromId(
            ON_ModelComponent::Type::InstanceDefinition, iref.m_instance_definition_uuid);
        const ON_InstanceDefinition* definition = ON_InstanceDefinition::Cast(ref.ModelComponent());
        if (definition == nullptr || context.depth >= 16) {
            Object& out = begin(&iref, attributes, context);
            skip(out, definition == nullptr ? "block instance refers to a missing definition"
                                            : "block instances nested deeper than 16");
            return;
        }
        Context inner;
        inner.xformStorage = context.xform != nullptr ? (*context.xform) * iref.m_xform : iref.m_xform;
        inner.xform = &inner.xformStorage;
        inner.id = context.id;
        const std::string name = utf8(definition->NameAsPointer());
        inner.block = context.block.empty() ? name : context.block + "/" + name;
        inner.parentColor = resolveColor(attributes, context);
        inner.visible = context.visible && attributes.IsVisible() && attributes.Mode() != ON::hidden_object;
        inner.depth = context.depth + 1;
        const ON_SimpleArray<ON_UUID>& members = definition->InstanceGeometryIdList();
        if (members.Count() == 0 && definition->IsLinkedType()) {
            Object& out = begin(&iref, attributes, context);
            skip(out, "linked block \"" + name + "\" keeps its geometry in another file");
            return;
        }
        for (int i = 0; i < members.Count(); ++i) {
            const ON_ModelGeometryComponent& member = model_.ModelGeometryComponentFromId(members[i]);
            const ON_Geometry* geometry = member.Geometry(nullptr);
            if (geometry == nullptr) continue;
            const ON_3dmObjectAttributes* memberAttributes = member.Attributes(&ON_3dmObjectAttributes::DefaultAttributes);
            Context step = inner;
            step.xform = &step.xformStorage;
            convert(geometry, *memberAttributes, step);
        }
    }
};

}  // namespace

// ---------------------------------------------------------------------------------------------
// The C ABI

extern "C" {

int field_opennurbs_open(const char* utf8Path, void** modelOut, char* message, int messageCapacity) {
    if (modelOut != nullptr) *modelOut = nullptr;
    if (utf8Path == nullptr || modelOut == nullptr) {
        writeMessage(message, messageCapacity, "null path or result pointer");
        return FIELD_OPENNURBS_BAD_INPUT;
    }
    try {
        FILE* fp = ON::OpenFile(utf8Path, "rb");
        if (fp == nullptr) {
            writeMessage(message, messageCapacity, std::string("cannot open ") + utf8Path);
            return FIELD_OPENNURBS_BAD_INPUT;
        }
        ON_BinaryFile archive(ON::archive_mode::read3dm, fp);
        ON_wString log;
        ON_TextLog textLog(log);
        ONX_Model onx;
        const bool ok = onx.Read(archive, &textLog);
        const int version = archive.Archive3dmVersion();
        ON::CloseFile(fp);
        if (!ok && version == 0) {
            writeMessage(message, messageCapacity,
                std::string(utf8Path) + " is not a .3dm file openNURBS can read: " + utf8(log));
            return FIELD_OPENNURBS_READ_FAILED;
        }
        Model* model = new Model();
        model->archiveVersion = version;
        const ON_UnitSystem& units = onx.m_settings.m_ModelUnitsAndTolerances.m_unit_system;
        model->unitSystem = static_cast<int>(units.UnitSystem());
        const double meters = units.MetersPerUnit(ON_DBL_QNAN);
        model->metersPerUnit = std::isfinite(meters) ? meters : 0.0;
        model->log = utf8(log);
        if (!ok) model->log = "openNURBS reported a partial read: " + model->log;
        Reader(onx, *model).run();
        *modelOut = model;
        return FIELD_OPENNURBS_OK;
    } catch (const std::bad_alloc&) {
        writeMessage(message, messageCapacity, "out of memory reading the file");
        return FIELD_OPENNURBS_OUT_OF_MEMORY;
    } catch (const std::exception& e) {
        writeMessage(message, messageCapacity, e.what());
        return FIELD_OPENNURBS_FAILED;
    } catch (...) {
        writeMessage(message, messageCapacity, "unknown failure reading the file");
        return FIELD_OPENNURBS_FAILED;
    }
}

void field_opennurbs_close(void* model) { delete static_cast<Model*>(model); }

const char* field_opennurbs_version(void) {
    // The release tag the build script pinned, when it says; ON::Version() alone is only major.minor.
#ifdef FIELD_OPENNURBS_VERSION
    static std::string version = "openNURBS " FIELD_OPENNURBS_VERSION;
#else
    static std::string version = "openNURBS " + std::to_string(ON::VersionMajor()) + "." + std::to_string(ON::VersionMinor());
#endif
    return version.c_str();
}

int field_opennurbs_model_int(void* handle, int what) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr) return 0;
    switch (what) {
    case FIELD_OPENNURBS_MODEL_ARCHIVE_VERSION: return model->archiveVersion;
    case FIELD_OPENNURBS_MODEL_UNIT_SYSTEM: return model->unitSystem;
    case FIELD_OPENNURBS_MODEL_LAYER_COUNT: return static_cast<int>(model->layers.size());
    case FIELD_OPENNURBS_MODEL_OBJECT_COUNT: return static_cast<int>(model->objects.size());
    default: return 0;
    }
}

double field_opennurbs_meters_per_unit(void* handle) {
    const Model* model = static_cast<const Model*>(handle);
    return model == nullptr ? 0.0 : model->metersPerUnit;
}

int field_opennurbs_layer_int(void* handle, int what, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->layers.size())) return 0;
    const Layer& layer = model->layers[index];
    switch (what) {
    case FIELD_OPENNURBS_LAYER_PARENT: return layer.parent;
    case FIELD_OPENNURBS_LAYER_COLOR: return layer.color;
    case FIELD_OPENNURBS_LAYER_FLAGS: return layer.flags;
    default: return 0;
    }
}

int field_opennurbs_object_int(void* handle, int what, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return 0;
    const Object& object = model->objects[index];
    switch (what) {
    case FIELD_OPENNURBS_OBJECT_KIND: return object.kind;
    case FIELD_OPENNURBS_OBJECT_LAYER: return object.layer;
    case FIELD_OPENNURBS_OBJECT_COLOR: return object.color;
    case FIELD_OPENNURBS_OBJECT_FLAGS: return object.flags;
    case FIELD_OPENNURBS_OBJECT_POINT_COUNT: return static_cast<int>(object.points.size() / 3);
    case FIELD_OPENNURBS_OBJECT_INDEX_COUNT: return static_cast<int>(object.indices.size());
    case FIELD_OPENNURBS_OBJECT_H_ALIGN: return object.hAlign;
    case FIELD_OPENNURBS_OBJECT_V_ALIGN: return object.vAlign;
    case FIELD_OPENNURBS_OBJECT_EDGE_POINT_COUNT: return static_cast<int>(object.edgePoints.size() / 3);
    case FIELD_OPENNURBS_OBJECT_EDGE_INDEX_COUNT: return static_cast<int>(object.edgeIndices.size());
    default: return 0;
    }
}

const char* field_opennurbs_string(void* handle, int what, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr) return "";
    if (what == FIELD_OPENNURBS_MODEL_LOG) return model->log.c_str();
    if (what <= FIELD_OPENNURBS_LAYER_ID) {
        if (index < 0 || index >= static_cast<int>(model->layers.size())) return "";
        const Layer& layer = model->layers[index];
        switch (what) {
        case FIELD_OPENNURBS_LAYER_NAME: return layer.name.c_str();
        case FIELD_OPENNURBS_LAYER_PATH: return layer.path.c_str();
        default: return layer.id.c_str();
        }
    }
    if (index < 0 || index >= static_cast<int>(model->objects.size())) return "";
    const Object& object = model->objects[index];
    switch (what) {
    case FIELD_OPENNURBS_OBJECT_NAME: return object.name.c_str();
    case FIELD_OPENNURBS_OBJECT_ID: return object.id.c_str();
    case FIELD_OPENNURBS_OBJECT_TYPE: return object.type.c_str();
    case FIELD_OPENNURBS_OBJECT_TEXT: return object.text.c_str();
    case FIELD_OPENNURBS_OBJECT_NOTE: return object.note.c_str();
    case FIELD_OPENNURBS_OBJECT_BLOCK: return object.block.c_str();
    default: return "";
    }
}

const double* field_opennurbs_object_points(void* handle, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return nullptr;
    const Object& object = model->objects[index];
    return object.points.empty() ? nullptr : object.points.data();
}

const int* field_opennurbs_object_indices(void* handle, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return nullptr;
    const Object& object = model->objects[index];
    return object.indices.empty() ? nullptr : object.indices.data();
}

const float* field_opennurbs_object_normals(void* handle, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return nullptr;
    const Object& object = model->objects[index];
    return object.normals.empty() ? nullptr : object.normals.data();
}

const int* field_opennurbs_object_colors(void* handle, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return nullptr;
    const Object& object = model->objects[index];
    return object.colors.empty() ? nullptr : object.colors.data();
}

const double* field_opennurbs_object_edge_points(void* handle, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return nullptr;
    const Object& object = model->objects[index];
    return object.edgePoints.empty() ? nullptr : object.edgePoints.data();
}

const int* field_opennurbs_object_edge_indices(void* handle, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return nullptr;
    const Object& object = model->objects[index];
    return object.edgeIndices.empty() ? nullptr : object.edgeIndices.data();
}

const double* field_opennurbs_object_frame(void* handle, int index) {
    const Model* model = static_cast<const Model*>(handle);
    if (model == nullptr || index < 0 || index >= static_cast<int>(model->objects.size())) return nullptr;
    const Object& object = model->objects[index];
    return object.frame.size() == 10 ? object.frame.data() : nullptr;
}


}  // extern "C"
