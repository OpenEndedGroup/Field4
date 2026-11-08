# Field Document: debruijn_tilings_3d_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. cubic against icosahedral directions

<!-- field-box id="debruijn3d_stars" index="0" -->

```edn [field-box id="debruijn3d_stars" role="properties"]
{"id" "debruijn3d_stars" "index" 0 "name" "cubic against icosahedral directions" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "cubic against icosahedral directions" "_tombstone" false "frame" #afield/rect [80.0 1440.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn3d_stars_code_.kt" "lines" 18 "characters" 627 "sha256" "97b03a3752a058504b85b6356f9527929da122fbffb2f2436d9adf208c8afb69"}]}
```

```kotlin [field-property box="debruijn3d_stars" name="code" path="debruijn3d_stars_code_.kt"]
val cut = Vector3d(0.31, 0.52, 0.80)

val panels =
    DeBruijnStar.entries.map { star ->
        val t = deBruijnTiling3D(star, radius = if (star.directions.size > 6) 3.0 else 4.0, scale = 10.0)
        val shapes = t.shapeCounts().values.joinToString(" : ")
        val name = star.name.lowercase().replace('_', ' ')
        "$name, ${t.directions.size} families\ncell shapes ${t.shapeVolumes.size}, counts $shapes" to
            t.slice(cut, offset = 0.5).lines()
    }

showPlaneGrid(
    *panels.toTypedArray(),
    columns = 3,
    cellWidth = 240,
    cellHeight = 240,
    window = Rect(-55.0, -55.0, 110.0, 110.0),
)
```

## 2. twenty rhombohedra make a triacontahedron

<!-- field-box id="debruijn3d_triacontahedron" index="1" -->

```edn [field-box id="debruijn3d_triacontahedron" role="properties"]
{"id" "debruijn3d_triacontahedron" "index" 1 "name" "twenty rhombohedra make a triacontahedron" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "twenty rhombohedra make a triacontahedron" "_tombstone" false "frame" #afield/rect [80.0 80.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn3d_triacontahedron_code_.kt" "lines" 18 "characters" 736 "sha256" "4ffc5064b1e75333b060db5f97fe3c471c9b558bdb94c350345de0c53668b7af"}]}
```

```kotlin [field-property box="debruijn3d_triacontahedron" name="code" path="debruijn3d_triacontahedron_code_.kt"]
import afield.geometry.solid.manifold.*

// One plane per family: six planes, twenty triple crossings, twenty rhombohedra.
val t = deBruijnTiling3D(planes = 0..0, scale = 10.0)
val counts = t.shapeCounts()

val (union, hull) = Solid.scope {
    // The control: the convex hull of every corner, which is the triacontahedron.
    val corners = Solid.hullOfPointsD(t.cells.flatMap { it.cornerArray().toList() }.toDoubleArray())
    t.toSolid().toTriMesh() to corners.volume
}

showMeshGrid(
    "${counts[1]} prolate + ${counts[2]} oblate, pulled apart" to t.toTriMesh(shrink = 0.8),
    "their Manifold union · cells %.1f · hull %.1f".format(t.volume(), hull) to union.withColor(0.75, 0.78, 0.84),
    columns = 2,
    cellWidth = 370,
)
```

## 3. two layers welded into a printable slab

<!-- field-box id="debruijn3d_slab" index="2" -->

```edn [field-box id="debruijn3d_slab" role="properties"]
{"id" "debruijn3d_slab" "index" 2 "name" "two layers welded into a printable slab" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "two layers welded into a printable slab" "_tombstone" false "frame" #afield/rect [700.0 1440.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn3d_slab_code_.kt" "lines" 25 "characters" 1118 "sha256" "3ae5603ed4973e5e6b72eb82a5bc884330e772cb55d72052d3a149aa06ab6026"}]}
```

```kotlin [field-property box="debruijn3d_slab" name="code" path="debruijn3d_slab_code_.kt"]
import afield.geometry.solid.manifold.*

// Stand a 5-fold axis upright and keep two layers: the cells between planes 0 and 2 of family 0.
val t = deBruijnTiling3D(radius = 4.0, scale = 10.0).oriented(DeBruijnStar.ICOSAHEDRAL.directions[0])
val slab = t.between(family = 0, from = 0, to = 2).filter { hypot(it.center.x, it.center.y) < 60.0 }
val floor = slab.sumOf { it.center.z } / slab.size

val (welded, relief) = Solid.scope {
    val union = t.toSolid(slab)
    // A disk 90 across, trimmed flat at the slab's middle height.
    val disk = Solid.cylinder(400.0, 45.0, segments = 96, center = true)
    union.volume to (union and disk).trimByPlane(0.0, 0.0, 1.0, floor)
}

showMesh(
    relief.toTriMesh().withColor(0.85, 0.80, 0.68),
    title = "two layers of the 3D Penrose tiling, 5-fold axis up · genus ${relief.genus}",
    caption =
        "${slab.size} cells · sum of cell volumes %.1f · welded by Manifold %.1f"
            .format(
                slab.sumOf { it.volume },
                welded,
            ),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 50.0, width = 760, height = 520),
)
```

## 4. slices across 5-, 3- and 2-fold axes

<!-- field-box id="debruijn3d_slices" index="3" -->

```edn [field-box id="debruijn3d_slices" role="properties"]
{"id" "debruijn3d_slices" "index" 3 "name" "slices across 5-, 3- and 2-fold axes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "slices across 5-, 3- and 2-fold axes" "_tombstone" false "frame" #afield/rect [80.0 760.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn3d_slices_code_.kt" "lines" 19 "characters" 628 "sha256" "972e02ee5ae3d8080d262b7d05fad219916d751cba44d6f493a0b001be5089cd"}]}
```

```kotlin [field-property box="debruijn3d_slices" name="code" path="debruijn3d_slices_code_.kt"]
val t = deBruijnTiling3D(radius = 5.0, scale = 10.0)
val phi = (1 + sqrt(5.0)) / 2
val window = Rect(-70.0, -70.0, 140.0, 140.0)

val panel = { title: String, normal: Vector3d ->
    val s = t.slice(normal, offset = 0.5)
    val sides = s.sideCounts().entries.joinToString(" ") { (n, count) -> "$n-gons $count" }
    "$title\n$sides" to s.lines()
}

showPlaneGrid(
    panel("across a 5-fold axis", Vector3d(0.0, 1.0, phi)),
    panel("across a 3-fold axis", Vector3d(1.0, 1.0, 1.0)),
    panel("across a 2-fold axis", Vector3d(0.0, 0.0, 1.0)),
    columns = 3,
    cellWidth = 250,
    cellHeight = 250,
    window = window,
)
```

## 5. a patch of the 3D Penrose tiling

<!-- field-box id="debruijn3d_patch" index="4" -->

### Narrative

From `code`

prolate / oblate against the radius of the patch, beside the golden ratio.

```edn [field-box id="debruijn3d_patch" role="properties"]
{"id" "debruijn3d_patch" "index" 4 "name" "a patch of the 3D Penrose tiling" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a patch of the 3D Penrose tiling" "_tombstone" false "frame" #afield/rect [700.0 80.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn3d_patch_code_.kt" "lines" 17 "characters" 850 "sha256" "a4296b2fd37a6c7cd17d1968ac43947cb098babb04856bcdb3103d11f104defb"}]}
```

```kotlin [field-property box="debruijn3d_patch" name="code" path="debruijn3d_patch_code_.kt"]
// prolate / oblate against the radius of the patch, beside the golden ratio.
val phi = (1 + sqrt(5.0)) / 2
val ratios =
    [2, 4, 8, 12].joinToString(" · ") { r ->
        val counts = deBruijnTiling3D(radius = r.toDouble()).shapeCounts()
        "radius $r: %.4f".format(counts.getValue(1).toDouble() / counts.getValue(2))
    }

val penrose3 = deBruijnTiling3D(radius = 2.0, scale = 10.0)
// The cells whose crossing is nearest the middle of the grid.
val middle = penrose3.cells.filter { it.crossing.length() < 1.3 }
showMesh(
    penrose3.toTriMesh(only = middle, shrink = 0.88),
    title = "${middle.size} cells of the 3D Penrose tiling, each shrunk to 88%",
    caption = "yellow prolate, blue oblate · prolate/oblate $ratios · φ = %.4f".format(phi),
    view = MeshView(yawDegrees = 25.0, pitchDegrees = 22.0, width = 720, height = 520),
)
```

## 6. slide family 0 and count unchanged cells

<!-- field-box id="debruijn3d_offset_sweep" index="5" -->

```edn [field-box id="debruijn3d_offset_sweep" role="properties"]
{"id" "debruijn3d_offset_sweep" "index" 5 "name" "slide family 0 and count unchanged cells" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "slide family 0 and count unchanged cells" "_tombstone" false "frame" #afield/rect [700.0 760.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn3d_offset_sweep_code_.kt" "lines" 25 "characters" 1000 "sha256" "7c35672c4ef95acf8acba4399ba8761a36b33fd38c96c4e5bd6d96c42c186e00"}]}
```

```kotlin [field-property box="debruijn3d_offset_sweep" name="code" path="debruijn3d_offset_sweep_code_.kt"]
val base = defaultDeBruijnOffsets(6)
val build = { shift: Double ->
    val offsets = base.toMutableList().also { it[0] += shift }
    deBruijnTiling3D(offsets = offsets, radius = 4.0, scale = 10.0, recenter = false)
}
// A cell is the same cell if it has the same corner lift and the same three families.
val identity = { c: DeBruijnCell -> c.lift(0).toList() + c.families }
val central = { t: DeBruijnTiling3D -> t.cells.filter { it.center.length() < 35.0 } }
val before = central(build(0.0)).map(identity).toSet()

val panels =
    [0.0, 0.1, 0.3, 0.6].map { shift ->
        val t = build(shift)
        val kept = central(t).count { identity(it) in before }
        val title = "family 0 offset +%.1f".format(shift)
        "$title\n$kept of ${central(t).size} central cells unchanged" to t.slice(t.directions[0], offset = 0.5).lines()
    }

showPlaneGrid(
    *panels.toTypedArray(),
    columns = 2,
    cellWidth = 360,
    cellHeight = 360,
    window = Rect(-60.0, -60.0, 120.0, 120.0),
)
```

