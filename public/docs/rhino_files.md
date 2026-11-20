# Rhino files (.3dm)

<!-- capture-sources: src/jvmMain/kotlin/afield/geometry/rhino/Rhino3dm.kt src/jvmMain/kotlin/afield/geometry/rhino/RhinoThree.kt native/field-opennurbs/src/field_opennurbs.cpp src/jvmMain/kotlin/afield/geometry/rhino/RhinoWriter.kt native/field-opennurbs/src/field_opennurbs_write.cpp -->

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

## Writing a .3dm for Rhino

Geometry made in Field goes the other way with `Rhino3dm.save`. Give it a file and a unit, and
inside the block declare layers and put meshes, `FLine`s, NURBS curves, points and text on them.
Rhino opens the file as it would one of its own, with the layer tree, colours and names intact.

This box grows a ring of twisted ribbons, each a spine curve and a strip of triangles under it,
saves them on two layers, and reads the file straight back to draw what Rhino will see:

```diffstatic_runner {data-lang=kotlin screenshot="assets/rhino_files/ribbons.png" wait="10.0" capture="box" open-outputs="true" padding="20" requires="opennurbs" name="ribbons"}
:::static
import afield.geometry.mesh.TriMesh
import afield.geometry.rhino.*
import kotlin.math.*

val dir = java.nio.file.Files.createTempDirectory("rhino-save").toFile()
val ribbons = 14
val steps = 40

// One ribbon: a point on its centre line at t in 0..1, and how far it has twisted there.
fun centre(a: Double, t: Double) = vec((30 + 25 * t) * cos(a + t), (30 + 25 * t) * sin(a + t), 40 * sin(PI * t))
fun twist(a: Double, t: Double) = a * 2 + 3 * PI * t

val file = Rhino3dm.save(java.io.File(dir, "ribbons.3dm"), units = "millimeters") {
    layer("Ribbons", vec(0.85, 0.4, 0.15, 1.0))
    layer("Ribbons::Spines", vec(0.95, 0.9, 0.8, 1.0))
    layer("Notes", vec(0.8, 0.8, 0.8, 1.0))

    for (i in 0 until ribbons) {
        val a = 2 * PI * i / ribbons

        // The spine, as cubic Bezier spans through the centre line: they arrive in Rhino as
        // degree-3 NURBS with exactly these control points.
        val spine = FLine().moveTo(centre(a, 0.0))
        for (k in 1..4) {
            val t0 = (k - 1) / 4.0
            val t1 = k / 4.0
            spine.cubicTo(centre(a, t0 + (t1 - t0) / 3), centre(a, t0 + 2 * (t1 - t0) / 3), centre(a, t1))
        }
        curve(spine, layer = "Ribbons::Spines", name = "spine $i")

        // The strip: two edges either side of the centre line, turning as it rises.
        val positions = FloatArray((steps + 1) * 6)
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val c = centre(a, t)
            val w = 4.0
            val dx = w * cos(twist(a, t)) * cos(a + t)
            val dy = w * cos(twist(a, t)) * sin(a + t)
            val dz = w * sin(twist(a, t))
            floatArrayOf(c.x + dx.toFloat(), c.y + dy.toFloat(), c.z + dz.toFloat(), c.x - dx.toFloat(), c.y - dy.toFloat(), c.z - dz.toFloat())
                .copyInto(positions, s * 6)
        }
        val triangles = IntArray(steps * 6)
        for (s in 0 until steps) intArrayOf(2 * s, 2 * s + 1, 2 * s + 2, 2 * s + 1, 2 * s + 3, 2 * s + 2).copyInto(triangles, s * 6)
        val hue = i.toDouble() / ribbons
        mesh(TriMesh(positions, triangles), layer = "Ribbons", name = "ribbon $i", color = vec(0.9, 0.3 + 0.5 * hue, 0.2, 1.0))
    }
    text("$ribbons ribbons", vec(-30.0, -70.0, 0.0), height = 6.0, layer = "Notes")
}

// What Rhino will open, read back with the same openNURBS.
val model = Rhino3dm.load(file)
println(model)
println("${file.length() / 1024} kB, layers " + model.layers.joinToString { it.path })
:::fold draw it, then clean up
three(height = 460) { rhino(model) }
dir.deleteRecursively()
:::endfold
```

![Fourteen twisted ribbons stand in a ring, seen from above at an angle, read back from the .3dm file just written. Each is a narrow strip of triangles that rises in an arch from the inner circle and comes down further out, turning about its own length as it goes, shaded from orange-red through ochre to olive across the ring; a thin pale spine curve runs along the middle of each. The words 14 ribbons lie flat on the ground in front of the ring.](assets/rhino_files/ribbons.png)

What goes in, and what Rhino gets:

<div data-code-lang="kotlin" markdown>

| call | in the file | exact? |
| --- | --- | --- |
| `layer("A::B", color, visible)` | a layer `B` inside `A`; an undeclared layer an object names is made black and on | yes |
| `mesh(triMesh)` | a mesh, with its vertex normals and vertex colours when the `TriMesh` has them | yes |
| `curve(fline)` | one curve a subpath: a degree-1 polyline when it is all lines, else a polycurve of degree-1 and degree-3 NURBS spans; `close()` is a line back to the start | yes |
| `nurbs(degree, points, knots, weights)` | a NURBS curve, rational when you give weights, knots as openNURBS counts them | yes |
| `points(list)`, `point(p)` | a point cloud, or a point | yes |
| `text(string, origin, height)` | text on a plane, `xAxis` and `yAxis` giving its orientation | the font is Rhino's |

</div>

Each call also takes `layer`, `name` and `color`; with no colour, an object is drawn in its
layer's, as Rhino does by default. `units` is the model's length unit (`"millimeters"`, `"meters"`,
`"inches"`, ...). Field does not scale anything: a point at 30 in a millimetre file is 30 mm in
Rhino.

A model you read writes back with `model.save(file)`, in its own units. Add to it on the way out
with `Rhino3dm.save(model, file) { curve(...) }`, so read, change, write is three lines. Breps and
extrusions go back as the meshes they were read as, hatches as their boundary curves, block
instances as their placed members, and `RhinoSkipped` objects not at all.

## Breps without meshes

The reader never evaluates a NURBS surface itself. openNURBS has no Brep mesher (that lives inside
Rhino), so a Brep is exactly as good as the render mesh saved with it. A file saved with *Save
Small* has none, and some faces Rhino never managed to shade are saved with an empty one. Those
Breps come back as `RhinoSkipped` with that reason and their edges, and `rhino(model)` draws them
as wireframes. If you need them shaded, re-save the file from Rhino with meshes. SubD objects are
skipped too: public openNURBS declares a SubD mesher but does not include one.

## Building the reader

The reader and the writer are one optional native library. Build it once with `./gradlew buildOpenNurbsNative`
(it needs `cmake`, and the network the first time), or pick it from the optional extras when you
first set Field up. `Rhino3dm.describe()` says whether Field found it.

## Where to go next

- [Geometry from disk](geometry_from_disk.md): the other formats Field reads, and repairing a mesh
  into a solid.
- [Meshes and 3D geometry](mesh_examples.md): what to do with a `TriMesh` once you have one.
- [Graphics reference](reference/graphics.md): `Rhino3dm`, `RhinoModel` and the object classes.
