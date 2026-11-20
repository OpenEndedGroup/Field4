// The writing half of the openNURBS C ABI: see field_opennurbs_write.h for the contract. Kept in its
// own file, apart from the reader in field_opennurbs.cpp.

#include "field_opennurbs_write.h"
#include "field_opennurbs.h"

#include "opennurbs.h"

#include <algorithm>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

namespace {

struct Writer {
    ONX_Model model;
    ON_3dmObjectAttributes next;
    int defaultLayer = -1;
    int objects = 0;
    std::vector<int> layers;  // our index -> the model's layer index
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
    const ON_ModelComponentReference ref = w.model.AddManagedModelGeometryComponent(geometry, attributes);
    if (ref.IsEmpty()) return -1;
    ++w.objects;
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
        int p = 0;
        auto next = [&]() {
            if (p >= pointCount) throw std::exception();
            ON_3dPoint point(xyz[3 * p], xyz[3 * p + 1], xyz[3 * p + 2]);
            ++p;
            return point;
        };
        int added = 0;
        std::vector<Span> spans;
        ON_3dPoint at = ON_3dPoint::Origin;
        auto flush = [&]() {
            if (!spans.empty()) {
                if (add(w, subpath(spans)) == 0) ++added;
                spans.clear();
            }
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
                    return -1;
            }
        }
        flush();
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
