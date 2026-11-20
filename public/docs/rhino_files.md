# Rhino files (.3dm)

<!-- capture-sources: src/jvmMain/kotlin/afield/geometry/rhino/Rhino3dm.kt src/jvmMain/kotlin/afield/geometry/rhino/RhinoThree.kt src/jvmMain/kotlin/afield/geometry/rhino/BrepTessellator.kt native/field-opennurbs/src/field_opennurbs.cpp native/field-opennurbs/src/field_opennurbs_brep.cpp -->

Have a Rhino model you want to explore? Load it in a box and draw it in a viewer you can orbit:

```diffstatic {data-lang=kotlin}
import afield.geometry.rhino.*

val model = Rhino3dm.load("~/Downloads/model.3dm")
println(model) // layers, objects by kind, and anything skipped with the reason
three { rhino(model) } // every object as a part, folded under its layer path
```

A Rhino model is more than its surfaces. It has a layer tree, curves that are not the edges of
anything, polygons, notes, and blocks placed many times. `Rhino3dm.load` reads all of that
through McNeel's own openNURBS library and hands it back in Field's terms.

`rhino(model)` draws what Rhino shows and lists what it hides: an object on a switched-off layer is
in the viewer's Parts list, unticked, under the same layer path it has in Rhino. Meshes are shaded
in the colour Rhino gives them (by layer, by object, by material, or from the block they sit in) and
outlined with their Brep edges rather than their facets. Text is set as outlines lying in its own
plane at its own height, so a label written on a wall stays on the wall as you orbit.

## A sample with one of everything

The example below has no file to read, so it asks the reader to write a sample first: nested
layers, a mesh, two Breps, five kinds of curve, text, a point, a hatch and a block placed twice.
With a file of your own, the `Rhino3dm.load` line is all you need.

```diffstatic_runner {data-lang=kotlin screenshot="assets/rhino_files/sample.png" wait="10.0" capture="box" open-outputs="true" padding="20" requires="opennurbs" name="sample"}
:::static
import afield.geometry.rhino.*

val dir = java.nio.file.Files.createTempDirectory("rhino-demo").toFile()
val model = Rhino3dm.load(Rhino3dm.writeSample(java.io.File(dir, "sample.3dm")))
println(model)

// The tree and its objects are plain data.
for (layer in model.layers) {
    val count = model.on(layer.path, sublayers = false).size
    println("${layer.path.padEnd(14)} ${if (layer.isVisible) "on " else "off"}  $count objects")
}
:::fold draw it, then clean up
three(height = 460) { rhino(model) }
dir.deleteRecursively()
:::endfold
```

![The sample model in an orbiting viewer, seen from above at an angle with Z up. A dark red shaded box with darker edges and, beside it, a flat red plate made of a quad and a triangle sit on the Hull layer; a green dot marks the point. Thin green curves lie flat around them: a long straight line, a wavy cubic, a circle, an open slot of two lines joined by a half-round end, and the words Hello Field set as outlines lying in the same plane. Two grey triangular fins, the two placements of one block, stand off to the right. The Brep on the switched-off Glass layer is not drawn.](assets/rhino_files/sample.png)

## What comes back

Object by object:

<div data-code-lang="kotlin" markdown>

| in Rhino | in the `RhinoModel` | exact? |
| --- | --- | --- |
| mesh | `RhinoMesh`, its quads split into triangles | yes |
| Brep, extrusion | `RhinoMesh` from the render mesh Rhino saved, or tessellated from its NURBS surfaces where there is none, plus its edges as an `FLine` | Rhino's mesh, or vertices on the exact surface |
| SubD | `RhinoMesh` on its limit surface, subdivided `subdLevel` times | vertices on the limit surface |
| line, polyline, polycurve, NURBS curve | `RhinoCurve` with an `FLine` | lines and polynomial spans up to cubic, yes; arcs and other rational spans within 10⁻⁵ of the curve's size, flagged `approximated` |
| hatch | `RhinoCurve` with `isHatch`, its boundary loops filled | as curves |
| text | `RhinoText`: string, plane, capital height, alignment; `toFLines()` sets it | the font is Field's, not the file's |
| point, point cloud | `RhinoPoints` | yes |
| block instance | its members, moved into place, `block` naming the definition | yes |
| anything else | `RhinoSkipped`, with the reason | — |

</div>

The model is also plain data for anything else in Field: `model.mesh()` is every visible mesh
merged into one `TriMesh` for the [mesh tools](mesh_examples.md), `model.lines()` every curve and
edge as `FLine`s, and `model.on("Hull")` the objects on a layer and the layers inside it.

## Leaving layers out

`rhino(model)` takes an `include` filter. Construction geometry that Rhino shows but you do not
want in the picture goes with one line:

```diffstatic {data-lang=kotlin}
three { rhino(model, include = { it.layer?.path != "Construction" }) }
```

The camera frames whatever is visible and included, so leaving out a far-flung helper layer also
brings the model up to fill the view. `hiddenLayers = false` drops what Rhino hides altogether,
`edges = false` drops the Brep outlines, and `text = false` the lettering.

## Breps without meshes

Rhino saves a render mesh with each Brep face unless you choose *Save Small*. Where a face has
none, `Rhino3dm.load` makes one from the face's exact NURBS surface and its trim curves, so a file
saved small still comes back shaded. SubD objects come back the same way, as their limit surface.

The example below writes such a file — a sphere, a capped cylinder, a plate with a hole, a box
saved with its mesh and one without, and a smooth and a creased SubD box, none of them carrying a
render mesh but the first box — and draws it twice. First, read with
`meshing = RhinoBrepMeshing.SAVED`, the way Rhino's saved meshes alone would give it: only the
box that has one is shaded, the rest are their edges. Then read with the default.

```diffstatic_runner {data-lang=kotlin screenshot="assets/rhino_files/save_small.png" wait="12.0" capture="box" open-outputs="true" padding="20" requires="opennurbs" name="save_small"}
:::static
import afield.geometry.rhino.*

val dir = java.nio.file.Files.createTempDirectory("rhino-nurbs").toFile()
val file = Rhino3dm.writeNurbsSample(java.io.File(dir, "save_small.3dm"))

val saved = Rhino3dm.load(file, meshing = RhinoBrepMeshing.SAVED)
val evaluated = Rhino3dm.load(file)
println(evaluated)
:::fold draw both, then clean up
three(channel = "saved", name = "saved", width = 380, height = 360) { rhino(saved) }
three(channel = "evaluated", name = "evaluated", width = 380, height = 360) { rhino(evaluated) }
dir.deleteRecursively()
:::endfold
```

![Two orbiting viewers of the same file. In the first only one small orange box is shaded; the sphere, cylinder, plate, second box and both SubD boxes are thin outlines or missing. In the second everything is shaded: a blue sphere, a blue capped cylinder, a flat blue plate with a round hole, two orange boxes, a green rounded cube made from the smooth SubD and a green sharp-edged cube from the creased one.](assets/rhino_files/save_small.png)

`meshing` chooses where the triangles come from:

<div data-code-lang="kotlin" markdown>

| `meshing` | a face with a saved mesh | a face without one |
| --- | --- | --- |
| `SAVED_ELSE_EVALUATE` (the default) | Rhino's mesh | tessellated from its surface |
| `SAVED` | Rhino's mesh | none: the Brep is a `RhinoSkipped`, drawn as its edges |
| `EVALUATE` | tessellated from its surface | tessellated from its surface |

</div>

A tessellated face is a grid over the face's parameters, a line at every knot, with each span
halved until the surface strays from the triangles by less than `tolerance` (a thousandth of the
Brep's size unless you pass one, in model units). Cells the trim curves cross are cut along them,
so holes and trimmed edges follow the trim. Every vertex lies on the exact surface. Faces are
joined where they meet smoothly and kept apart at creases, so the shading stays sharp at a box's
edges and smooth across a sphere's seam. `RhinoMesh.evaluatedFaces` says how many of an object's
faces were tessellated.

A SubD is subdivided `subdLevel` times (3 unless you say otherwise; each level makes four times
the triangles) by openNURBS's own Catmull-Clark rules, and each vertex is moved onto the limit
surface. Creased edges stay creased. `RhinoMesh.subdLevel` is set on those.

Rhino's own meshes and these are not the same triangles: Rhino's render mesh settings decide its
density, and a tessellation here follows `tolerance`. For measuring, `EVALUATE` gives every Brep the
same treatment.

## Building the reader

The reader is an optional native library. Build it once with `./gradlew buildOpenNurbsNative`
(it needs `cmake`, and the network the first time), or pick it from the optional extras when you
first set Field up. `Rhino3dm.describe()` says whether Field found it.

## Where to go next

- [Geometry from disk](geometry_from_disk.md): the other formats Field reads, and repairing a mesh
  into a solid.
- [Meshes and 3D geometry](mesh_examples.md): what to do with a `TriMesh` once you have one.
- [Graphics reference](reference/graphics.md): `Rhino3dm`, `RhinoModel` and the object classes.
