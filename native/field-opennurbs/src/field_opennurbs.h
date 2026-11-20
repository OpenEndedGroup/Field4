// A flat C ABI over openNURBS, for java.lang.foreign: read a Rhino .3dm file into layers and
// drawable objects.
//
// `field_opennurbs_open` reads the whole file and converts it at once into plain arrays owned by an
// opaque model handle. Everything after that is an accessor returning an int, a double, or a pointer
// into the handle's storage, valid until `field_opennurbs_close`. No C++ object, struct layout or
// exception crosses the boundary.
//
// What comes out, and what deliberately does not:
//
//  - Meshes: mesh objects, and the render meshes Rhino caches on Breps and extrusions. openNURBS
//    has no Brep tessellator (that lives in Rhino itself), so a Brep saved without its render mesh
//    ("Save Small") comes out as a SKIPPED object with a reason, never as a mesh this library made.
//    SubD objects are SKIPPED too: public openNURBS declares a limit-surface mesher but ships none.
//  - Curves: a path of move / line / cubic operations. Lines, polylines and non-rational spans up
//    to degree 3 are exact; rational spans (arcs, circles) and higher degrees are approximated by
//    cubics to a tolerance relative to the curve's size, and flagged.
//  - Hatches: their boundary loops, in the same path form.
//  - Text: the plain string, its plane, its height and its alignment.
//  - Points and point clouds.
//  - Block instances: flattened, each member transformed into place and flagged.
//
// Strings are UTF-8. Colours are 0xAARRGGBB with A = 255 for opaque.

#pragma once

#include "field_opennurbs_export.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Status codes from `field_opennurbs_open`. Anything non-zero leaves `*modelOut` null. */
enum {
    FIELD_OPENNURBS_OK = 0,
    FIELD_OPENNURBS_BAD_INPUT = 1,     /**< null or unreadable path */
    FIELD_OPENNURBS_READ_FAILED = 2,   /**< not a .3dm file, or openNURBS could not read it; see the message */
    FIELD_OPENNURBS_OUT_OF_MEMORY = 3,
    FIELD_OPENNURBS_FAILED = 4,        /**< anything else; see the message */
};

/** Object kinds, `field_opennurbs_object_int(model, FIELD_OPENNURBS_OBJECT_KIND, i)`. */
enum {
    FIELD_OPENNURBS_SKIPPED = 0,  /**< not converted; OBJECT_NOTE says why */
    FIELD_OPENNURBS_MESH = 1,     /**< points: vertices; indices: triangles; normals optional */
    FIELD_OPENNURBS_CURVE = 2,    /**< points + indices: a path, see the OP_ codes */
    FIELD_OPENNURBS_HATCH = 3,    /**< as CURVE, one closed loop per move */
    FIELD_OPENNURBS_TEXT = 4,     /**< OBJECT_TEXT, and a 10-double frame */
    FIELD_OPENNURBS_POINTS = 5,   /**< points only */
};

/** Path operations in a CURVE or HATCH's indices. Each consumes the next 1, 1 or 3 points. */
enum {
    FIELD_OPENNURBS_OP_MOVE = 0,
    FIELD_OPENNURBS_OP_LINE = 1,
    FIELD_OPENNURBS_OP_CUBIC = 3,
};

/** Bits of OBJECT_FLAGS and LAYER_FLAGS. */
enum {
    FIELD_OPENNURBS_VISIBLE = 1,      /**< the object's (or layer's own) visibility; parents are not folded in */
    FIELD_OPENNURBS_IN_BLOCK = 2,     /**< came from flattening a block instance */
    FIELD_OPENNURBS_CLOSED = 4,       /**< a closed curve */
    FIELD_OPENNURBS_PARTIAL = 8,      /**< a Brep some of whose faces had no render mesh */
    FIELD_OPENNURBS_APPROXIMATED = 16,/**< a curve with at least one span approximated by cubics */
    FIELD_OPENNURBS_LOCKED = 32,      /**< layer flag: locked */
};

/** Selectors for `field_opennurbs_model_int`. */
enum {
    FIELD_OPENNURBS_MODEL_ARCHIVE_VERSION = 0, /**< e.g. 70 for a Rhino 7 file */
    FIELD_OPENNURBS_MODEL_UNIT_SYSTEM = 1,     /**< ON::LengthUnitSystem as an int: 2 mm, 3 cm, 4 m, 8 in, 9 ft */
    FIELD_OPENNURBS_MODEL_LAYER_COUNT = 2,
    FIELD_OPENNURBS_MODEL_OBJECT_COUNT = 3,
};

/** Selectors for `field_opennurbs_layer_int`. */
enum {
    FIELD_OPENNURBS_LAYER_PARENT = 0, /**< index of the parent layer, or -1 */
    FIELD_OPENNURBS_LAYER_COLOR = 1,
    FIELD_OPENNURBS_LAYER_FLAGS = 2,
};

/** Selectors for `field_opennurbs_object_int`. */
enum {
    FIELD_OPENNURBS_OBJECT_KIND = 0,
    FIELD_OPENNURBS_OBJECT_LAYER = 1,       /**< index into the layer list, or -1 */
    FIELD_OPENNURBS_OBJECT_COLOR = 2,       /**< resolved display colour (by layer, object, material or parent) */
    FIELD_OPENNURBS_OBJECT_FLAGS = 3,
    FIELD_OPENNURBS_OBJECT_POINT_COUNT = 4, /**< xyz triples at OBJECT points */
    FIELD_OPENNURBS_OBJECT_INDEX_COUNT = 5, /**< ints at OBJECT indices */
    FIELD_OPENNURBS_OBJECT_H_ALIGN = 6,     /**< text: 0 left, 1 centre, 2 right */
    /** text: where the plane's origin attaches vertically, as ON::TextVerticalAlignment numbers it —
        0 top of the first line, 1 middle of the first line, 2 baseline of the first line, 3 middle,
        4 middle of the last line, 5 baseline of the last line, 6 bottom of the bounding box */
    FIELD_OPENNURBS_OBJECT_V_ALIGN = 7,
    FIELD_OPENNURBS_OBJECT_EDGE_POINT_COUNT = 8, /**< xyz triples at OBJECT edge points */
    FIELD_OPENNURBS_OBJECT_EDGE_INDEX_COUNT = 9, /**< path operations at OBJECT edge indices */
};

/** Selectors for `field_opennurbs_string`. */
enum {
    FIELD_OPENNURBS_LAYER_NAME = 0,  /**< the layer's own name */
    FIELD_OPENNURBS_LAYER_PATH = 1,  /**< "Parent::Child" */
    FIELD_OPENNURBS_LAYER_ID = 2,
    FIELD_OPENNURBS_OBJECT_NAME = 3,
    FIELD_OPENNURBS_OBJECT_ID = 4,   /**< the top-level object's id; block members share their instance's */
    FIELD_OPENNURBS_OBJECT_TYPE = 5, /**< the openNURBS class, e.g. "ON_Brep" */
    FIELD_OPENNURBS_OBJECT_TEXT = 6, /**< text: the plain string */
    FIELD_OPENNURBS_OBJECT_NOTE = 7, /**< why an object was skipped or is partial; "" otherwise */
    FIELD_OPENNURBS_OBJECT_BLOCK = 8,/**< "Outer/Inner" block definition names for a block member; "" otherwise */
    FIELD_OPENNURBS_MODEL_LOG = 9,   /**< what openNURBS said while reading (index ignored); usually "" */
};

/**
 * Read a .3dm file.
 *
 * @param utf8Path      path to the file.
 * @param modelOut      receives the model handle on success, null otherwise.
 * @param message       receives a NUL-terminated explanation on failure; may be null.
 * @param messageCapacity bytes available at `message`.
 * @return one of the FIELD_OPENNURBS_* status codes.
 */
FIELD_OPENNURBS_API int field_opennurbs_open(const char* utf8Path, void** modelOut,
    char* message, int messageCapacity);

/** Release a model and everything its accessors handed out. Null-safe. */
FIELD_OPENNURBS_API void field_opennurbs_close(void* model);

/** The openNURBS version this was built against, so a mismatch is reportable. */
FIELD_OPENNURBS_API const char* field_opennurbs_version(void);

FIELD_OPENNURBS_API int field_opennurbs_model_int(void* model, int what);

/** Metres per model unit (0.001 for millimetres); 0 when the file's units are unset or custom-unknown. */
FIELD_OPENNURBS_API double field_opennurbs_meters_per_unit(void* model);

FIELD_OPENNURBS_API int field_opennurbs_layer_int(void* model, int what, int layer);

FIELD_OPENNURBS_API int field_opennurbs_object_int(void* model, int what, int object);

/** A string owned by the model; "" (never null) for an out-of-range index or an empty value. */
FIELD_OPENNURBS_API const char* field_opennurbs_string(void* model, int what, int index);

/** 3 * POINT_COUNT doubles, xyz, in model units; null when there are none. */
FIELD_OPENNURBS_API const double* field_opennurbs_object_points(void* model, int object);

/** INDEX_COUNT ints: triangle vertex indices for a MESH, path operations for a CURVE or HATCH. */
FIELD_OPENNURBS_API const int* field_opennurbs_object_indices(void* model, int object);

/** 3 * POINT_COUNT floats of vertex normals for a MESH that has them; null otherwise. */
FIELD_OPENNURBS_API const float* field_opennurbs_object_normals(void* model, int object);

/**
 * A MESH that came from a Brep or an extrusion also carries that object's edges — what Rhino draws
 * as its wireframe — as a path in the CURVE form, one subpath per edge. So does a Brep SKIPPED for
 * want of a render mesh, which can then still be drawn as a wireframe. Null for other objects.
 */
FIELD_OPENNURBS_API const double* field_opennurbs_object_edge_points(void* model, int object);
FIELD_OPENNURBS_API const int* field_opennurbs_object_edge_indices(void* model, int object);

/**
 * A TEXT object's frame: origin xyz, x axis xyz, y axis xyz, height — 10 doubles. The axes are unit
 * vectors spanning the text's plane; height is the capital-letter height in model units with the
 * dimension style's scale applied. Null for anything but TEXT.
 */
FIELD_OPENNURBS_API const double* field_opennurbs_object_frame(void* model, int object);

/**
 * Write a small .3dm file exercising every kind above — nested layers, a mesh, a Brep with and one
 * without render meshes, line / polyline / cubic / circle / polycurve, text, a point, a hatch, a
 * block with two instances. It is how the JVM tests get a fixture without committing one.
 */
FIELD_OPENNURBS_API int field_opennurbs_write_fixture(const char* utf8Path, char* message, int messageCapacity);

#ifdef __cplusplus
}
#endif
