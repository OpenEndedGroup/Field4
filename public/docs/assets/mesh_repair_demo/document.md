# Field Document: mesh_repair_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["mesh-repair-diagnose" "mesh-repair-fix"] "properties" {"children" #{#afield/box "mesh-repair-diagnose" #afield/box "mesh-repair-fix"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. Mesh repair — fill the holes, then cut it

<!-- field-box id="mesh-repair-fix" index="1" -->

### Narrative

From `code`

Repair it, cut it, and be told what could not be done.

```edn [field-box id="mesh-repair-fix" role="properties"]
{"id" "mesh-repair-fix" "index" 1 "name" "Mesh repair — fill the holes, then cut it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Mesh repair — fill the holes, then cut it" "frame" #afield/rect [620.0 60.0 520.0 430.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh-repair-fix_code_.kt" "lines" 61 "characters" 2396 "sha256" "37b59f031e1db6d7576de653b661e3f5eb9d8e7c6bd2276687a58b2c61d143ab"}]}
```

```kotlin [field-property box="mesh-repair-fix" name="code" path="mesh-repair-fix_code_.kt"]
// Repair it, cut it, and be told what could not be done.

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.manifold.toSolid
import afield.stdlib.TriMesh
import afield.stdlib.defectFLines
import afield.stdlib.icosphereMesh
import afield.stdlib.repairToSolid
import afield.stdlib.signedVolume

run {

val holeFaces = 4
val base = icosphereMesh(3, 1.0)
var broken = base.filterTriangles { it >= holeFaces }
val pos = broken.positions.copyOf(broken.positions.size + 9)
val idx = broken.indices.copyOf()
for (k in 0..2) {
    val v = broken.indices[k]
    val d = (broken.vertexCount + k) * 3
    pos[d] = broken.x(v); pos[d + 1] = broken.y(v); pos[d + 2] = broken.z(v)
    idx[k] = broken.vertexCount + k
}
broken = TriMesh(pos, idx)

// repairToSolid returns a RESULT, not a mesh. The mesh is one field of it. The rest is the
// story: what each step changed, one line per hole with how it was filled, and `unfixed` —
// the blocking defects still present, each with the reason nothing was done about it.
// A repair that hands back only a mesh cannot be audited, which is why this one does not.
val result = broken.repairToSolid()
println(result.report())

// The defects as drawable lines, on their own channel.
display("defects", broken.defectFLines())

if (!ManifoldLibrary.isAvailable) { println(ManifoldLibrary.describe()); return@run }
if (!result.isSolid) { println("still not a solid — see the unfixed list above"); return@run }

// It is a solid now, so it can be cut. That was the whole point.
val solid = result.mesh.toSolid()
val knife = Solid.cube(4.0, 4.0, 4.0, center = true).translated(0.0, 0.0, 2.0)
val lower = solid - knife
println()
println("whole sphere     ${"%.5f".format(base.signedVolume())}")
println("repaired volume  ${"%.5f".format(solid.volume)}   (the flat cap loses a sliver)")
println("after the cut    ${"%.5f".format(lower.volume)}   (half, cut at the equator)")

showMeshGrid(
    "broken — an open hole" to broken,
    "repaired — capped" to result.mesh,
    // The PLAIN mesh, not toTriMesh(normals = true): asking for normals splits a vertex per
    // face at every hard edge, which is right for shading and makes the caption's
    // isWatertight() read false on a solid. See Solid.toTriMesh's KDoc.
    "and cut" to lower.toTriMesh(),
    columns = 3,
    cellWidth = 300,
)
lower.close(); knife.close(); solid.close()

}
```

## 3. Mesh repair — why is this not a solid?

<!-- field-box id="mesh-repair-diagnose" index="2" -->

### Narrative

From `code`

Why is this mesh not a solid?  —  afield.stdlib/mesh_repair.kt

Everything here is generated in this box: no files, no network. The imports are half the
point — diagnose() and showMeshDiagnosis exist only where mesh_repair.kt does, so if this
box compiles at all, the Field running it has that code.

```edn [field-box id="mesh-repair-diagnose" role="properties"]
{"id" "mesh-repair-diagnose" "index" 2 "name" "Mesh repair — why is this not a solid?" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Mesh repair — why is this not a solid?" "frame" #afield/rect [60.0 60.0 520.0 430.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh-repair-diagnose_code_.kt" "lines" 57 "characters" 2546 "sha256" "6d3086d124e59245de42c0d1884303e1e2e7af2d397f11b59aae21a314a8cc49"}]}
```

```kotlin [field-property box="mesh-repair-diagnose" name="code" path="mesh-repair-diagnose_code_.kt"]
// Why is this mesh not a solid?  —  afield.stdlib/mesh_repair.kt
//
// Everything here is generated in this box: no files, no network. The imports are half the
// point — diagnose() and showMeshDiagnosis exist only where mesh_repair.kt does, so if this
// box compiles at all, the Field running it has that code.

import afield.manifold.ManifoldLibrary
import afield.manifold.isValidSolid
import afield.stdlib.TriMesh
import afield.stdlib.diagnose
import afield.stdlib.icosphereMesh
import afield.stdlib.showMeshDiagnosis

run {

// ---- the damage. change these two and re-run ----------------------------------------
val holeFaces = 4        // faces to delete: an open hole. 1 gives a triangular one; 8 gives
                         // a figure-eight loop, which the diagnosis names as such.
val crackAFace = true    // give one triangle its own three vertices, as an STL export does
// --------------------------------------------------------------------------------------

// A sphere with a patch missing. An open boundary is the commonest reason a mesh off disk
// is refused, and until mesh_repair.kt there was nothing in Field that could fix one —
// weld does not fill a hole, and neither does fixWinding.
val base = icosphereMesh(3, 1.0)
var broken = base.filterTriangles { it >= holeFaces }

if (crackAFace) {
    val pos = broken.positions.copyOf(broken.positions.size + 9)
    val idx = broken.indices.copyOf()
    for (k in 0..2) {
        val v = broken.indices[k]
        val d = (broken.vertexCount + k) * 3
        pos[d] = broken.x(v); pos[d + 1] = broken.y(v); pos[d + 2] = broken.z(v)
        idx[k] = broken.vertexCount + k
    }
    broken = TriMesh(pos, idx)
}

if (ManifoldLibrary.isAvailable) println("valid solid? ${broken.isValidSolid()}")
println()

// Not a boolean: the ordered list of what is wrong, worst first, each with the call that
// addresses it. Note what is NOT blocking — Manifold accepts a pinch vertex and accepts
// disjoint components, so those are reported at a lower severity rather than as failures.
println(broken.diagnose().report())

// The same facts as a picture. Red is an open boundary, orange a non-manifold edge,
// yellow a pinch vertex — so the red patch is where to look.
// No `view` argument: the camera aims itself at the worst defect, because a card captioned
// "4 faces removed" over a picture of a flawless sphere argues that nothing is wrong.
showMeshDiagnosis(
    broken,
    title = "$holeFaces faces removed" + (if (crackAFace) ", plus one unwelded face" else ""),
)

}
```

