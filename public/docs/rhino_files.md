# Rhino files (.3dm)

<!-- capture-sources: src/jvmMain/kotlin/afield/geometry/rhino/Rhino3dm.kt src/jvmMain/kotlin/afield/geometry/rhino/RhinoThree.kt native/field-opennurbs/src/field_opennurbs.cpp -->

Have a Rhino model you want to explore? Load it in a box and draw it in a viewer you can orbit:

```diffstatic {data-lang=kotlin}
import afield.geometry.rhino.*

val model = Rhino3dm.load("~/Downloads/model.3dm")
println(model)            // layers, objects by kind, and anything skipped with the reason
three { rhino(model) }    // every object as a part, folded under its layer path
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

## What comes back

Object by object:

<div data-code-lang="kotlin" markdown>

| in Rhino | in the `RhinoModel` | exact? |
| --- | --- | --- |
| mesh | `RhinoMesh`, its quads split into triangles | yes |
| Brep, extrusion | `RhinoMesh` from the render mesh Rhino saved, plus its edges as an `FLine` | the mesh is Rhino's own |
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

The reader never evaluates a NURBS surface itself. openNURBS has no Brep mesher (that lives inside
Rhino), so a Brep is exactly as good as the render mesh saved with it. A file saved with *Save
Small* has none, and some faces Rhino never managed to shade are saved with an empty one. Those
Breps come back as `RhinoSkipped` with that reason and their edges, and `rhino(model)` draws them
as wireframes. If you need them shaded, re-save the file from Rhino with meshes. SubD objects are
skipped too: public openNURBS declares a SubD mesher but does not include one.

## Building the reader

The reader is an optional native library. Build it once with `./gradlew buildOpenNurbsNative`
(it needs `cmake`, and the network the first time), or pick it from the optional extras when you
first set Field up. `Rhino3dm.describe()` says whether Field found it.

## Where to go next

- [Geometry from disk](geometry_from_disk.md): the other formats Field reads, and repairing a mesh
  into a solid.
- [Meshes and 3D geometry](mesh_examples.md): what to do with a `TriMesh` once you have one.
- [Graphics reference](reference/graphics.md): `Rhino3dm`, `RhinoModel` and the object classes.
