// The whole wrapper. Three jobs: build a tetgenio PLC from flat arrays, run TetGen without letting
// its exceptions escape, and copy the result into memory the JVM can own and free.

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>

#include "tetgen.h"

#include "field_tetgen.h"

namespace {

struct Outputs {
    int* nodeCount;
    double** nodes;
    int* cellCount;
    int** cells;
    char* message;
    int messageCapacity;
};

int fail(const Outputs& out, int status, const char* message) {
    if (out.nodeCount) *out.nodeCount = 0;
    if (out.cellCount) *out.cellCount = 0;
    if (out.nodes) *out.nodes = nullptr;
    if (out.cells) *out.cells = nullptr;
    if (out.message && out.messageCapacity > 0) {
        std::snprintf(out.message, static_cast<size_t>(out.messageCapacity), "%s", message);
    }
    return status;
}

}  // namespace

extern "C" {

int field_tetgen_tetrahedralize(
    int pointCount, const double* points,
    int triangleCount, const int* triangles,
    double qualityRatio, double maxVolume, int preserveSurface,
    int* nodeCountOut, double** nodesOut, int* cellCountOut, int** cellsOut,
    char* message, int messageCapacity) {
    const Outputs out{nodeCountOut, nodesOut, cellCountOut, cellsOut, message, messageCapacity};
    // Zero the outputs up front so every early return leaves the caller with a defined state.
    fail(out, FIELD_TETGEN_OK, "");

    if (pointCount < 4 || points == nullptr || triangleCount < 4 || triangles == nullptr) {
        return fail(out, FIELD_TETGEN_BAD_INPUT,
                    "a closed surface needs at least 4 vertices and 4 triangles");
    }
    for (int i = 0; i < 3 * triangleCount; ++i) {
        if (triangles[i] < 0 || triangles[i] >= pointCount) {
            return fail(out, FIELD_TETGEN_BAD_INPUT, "triangle index outside the vertex array");
        }
    }
    if (!(qualityRatio > 1.0)) {
        return fail(out, FIELD_TETGEN_BAD_INPUT, "qualityRatio must exceed 1.0");
    }

    tetgenio in;
    tetgenio result;

    in.firstnumber = 0;
    in.numberofpoints = pointCount;
    in.pointlist = new (std::nothrow) REAL[static_cast<size_t>(pointCount) * 3];
    if (in.pointlist == nullptr) {
        return fail(out, FIELD_TETGEN_OUT_OF_MEMORY, "could not allocate the input vertex array");
    }
    for (int i = 0; i < 3 * pointCount; ++i) in.pointlist[i] = points[i];

    // Every triangle becomes a one-polygon facet. TetGen treats a facet's edges as constraints, so
    // this is exactly what preserves the Manifold boolean's sharp edges in the output.
    in.numberoffacets = triangleCount;
    in.facetlist = new (std::nothrow) tetgenio::facet[triangleCount];
    in.facetmarkerlist = new (std::nothrow) int[triangleCount];
    if (in.facetlist == nullptr || in.facetmarkerlist == nullptr) {
        return fail(out, FIELD_TETGEN_OUT_OF_MEMORY, "could not allocate the input facet array");
    }
    for (int t = 0; t < triangleCount; ++t) {
        tetgenio::facet& f = in.facetlist[t];
        f.numberofpolygons = 1;
        f.polygonlist = new (std::nothrow) tetgenio::polygon[1];
        f.numberofholes = 0;
        f.holelist = nullptr;
        if (f.polygonlist == nullptr) {
            return fail(out, FIELD_TETGEN_OUT_OF_MEMORY, "could not allocate an input polygon");
        }
        tetgenio::polygon& p = f.polygonlist[0];
        p.numberofvertices = 3;
        p.vertexlist = new (std::nothrow) int[3];
        if (p.vertexlist == nullptr) {
            return fail(out, FIELD_TETGEN_OUT_OF_MEMORY, "could not allocate an input polygon");
        }
        p.vertexlist[0] = triangles[3 * t];
        p.vertexlist[1] = triangles[3 * t + 1];
        p.vertexlist[2] = triangles[3 * t + 2];
        in.facetmarkerlist[t] = 0;
    }

    // Built as a switch string rather than by poking tetgenbehavior's fields, because the parser is
    // the documented interface and the fields are not.
    std::string switches = "pzQ";                 // PLC, zero-based indices, quiet
    switches += "q" + std::to_string(qualityRatio);
    if (maxVolume > 0.0) {
        char volume[64];
        std::snprintf(volume, sizeof(volume), "a%.17g", maxVolume);
        switches += volume;
    }
    if (preserveSurface != 0) switches += "Y";

    tetgenbehavior behavior;
    if (!behavior.parse_commandline(const_cast<char*>(switches.c_str()))) {
        return fail(out, FIELD_TETGEN_BAD_INPUT, ("TetGen rejected the switches: " + switches).c_str());
    }

    // With TETLIBRARY defined, TetGen signals a fatal condition by throwing an int rather than
    // calling exit(). Letting that unwind into FFM would abort the VM.
    try {
        tetrahedralize(&behavior, &in, &result);
    } catch (int code) {
        char message[256];
        std::snprintf(message, sizeof(message),
                      "TetGen aborted with code %d; the surface is probably not closed, "
                      "self-intersects, or has duplicate vertices", code);
        return fail(out, FIELD_TETGEN_FAILED, message);
    } catch (...) {
        return fail(out, FIELD_TETGEN_FAILED, "TetGen aborted with an unknown exception");
    }

    if (result.numberofpoints <= 0 || result.numberoftetrahedra <= 0) {
        return fail(out, FIELD_TETGEN_FAILED, "TetGen returned an empty mesh");
    }
    if (result.numberofcorners != 4) {
        return fail(out, FIELD_TETGEN_FAILED, "expected linear tetrahedra with 4 corners");
    }

    // malloc, not new[]: the JVM frees these through field_tetgen_free, and keeping one allocator
    // on both sides of that boundary is one less thing to get wrong.
    const size_t nodeBytes = static_cast<size_t>(result.numberofpoints) * 3 * sizeof(double);
    const size_t cellBytes = static_cast<size_t>(result.numberoftetrahedra) * 4 * sizeof(int);
    double* nodes = static_cast<double*>(std::malloc(nodeBytes));
    int* cells = static_cast<int*>(std::malloc(cellBytes));
    if (nodes == nullptr || cells == nullptr) {
        std::free(nodes);
        std::free(cells);
        return fail(out, FIELD_TETGEN_OUT_OF_MEMORY, "could not allocate the result arrays");
    }
    for (int i = 0; i < 3 * result.numberofpoints; ++i) nodes[i] = result.pointlist[i];
    for (int i = 0; i < 4 * result.numberoftetrahedra; ++i) cells[i] = result.tetrahedronlist[i];

    if (nodeCountOut) *nodeCountOut = result.numberofpoints;
    if (cellCountOut) *cellCountOut = result.numberoftetrahedra;
    if (nodesOut) *nodesOut = nodes;
    if (cellsOut) *cellsOut = cells;
    return FIELD_TETGEN_OK;
}

void field_tetgen_free(double* nodes, int* cells) {
    std::free(nodes);
    std::free(cells);
}

const char* field_tetgen_version(void) {
    return "TetGen 1.6.0";
}

}  // extern "C"
