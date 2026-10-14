# SolidCraft: edit the design, then see the part

[SolidCraft](https://github.com/bherbruck/solidcraft) is a parametric CAD engine. Field's optional
`afield.solidcraft` binding opens a saved design, changes a named parameter, evaluates its feature
tree, and returns B-rep measurements or a triangle mesh for display. It can also export STEP, binary
STL, and the edited SolidCraft design. This is a small first binding across SolidCraft's geometry,
kernel, document, and I/O crates (L0–L3), alongside Field's [Manifold](manifold.md) mesh-solid API.

Open [solidcraft_intro](agent_transcripts.md#solidcraft_intro) from the welcome screen. Its one box
uses a design bundled with Field: a 40 × 30 mm sketch, an extrusion whose depth is `width / 2`, a
fillet, and a circular cut that stays 20 mm deep. At `width = 40 mm` the cut goes through. At
`width = 50 mm` the part becomes 25 mm deep, so the same cut is blind. Change `width` in the box
and run it again.

```diffstatic_runner {data-lang=kotlin screenshot="assets/solidcraft/compare.png" capture="box" capture-box="solidcraft_compare" macro-file="../../dev_docs/agents/transcripts/solidcraft_intro.yaml" requires="solidcraft" open-outputs="true" padding="16" wait="1" timeout="180" embed=true hidden=true name="solidcraft-compare" alt="Two views of the same grey CAD part in Field on a dark background. At left, width 40 mm gives a through hole in a 20 mm deep block. At right, width 50 mm makes the block 25 mm deep and the fixed 20 mm cut becomes blind. Both panels report watertight triangle meshes below the picture."}
// The transcript opens the bundled design and runs the real Rust evaluator.
```

The image is captured from that starting point's code. The display meshes have coincident vertices
welded so Field can measure their topology; SolidCraft's own B-rep volume is printed separately.
The mesh is a view of the evaluated design, while the design remains editable in Rust.

## Install the optional bridge

Select **SolidCraft parametric CAD** in Field's first-start setup, or reopen setup with
`./field.sh --setup`. It checks for Git, Python, a C/C++ compiler, and Rust/Cargo 1.95 or newer;
**BUILD** runs the pinned source build. You can run the same build directly:

```diffstatic {data-lang=bash}
./gradlew buildSolidCraftNative
./gradlew smokeSolidCraft
```

The first build downloads SolidCraft source at a pinned commit and compiles Field's small C ABI
adapter inside that workspace. Ordinary Field compilation and startup do not fetch or load it.
`SolidCraftLibrary.describe()` reports where Field looked. Set `AFIELD_SOLIDCRAFT_LIBRARY` or
`-Dafield.solidcraft.library` to use a library at another path. Restart Field after building it,
then run the starting point. The native library currently needs to be built separately for each
operating system and architecture you use.

## Use it in a box

```diffstatic {data-lang=kotlin}
import afield.solidcraft.SolidCraftDesign
import afield.solidcraft.SolidCraftLibrary
import afield.stdlib.showMesh
import afield.stdlib.weld

if (SolidCraftLibrary.isAvailable) {
    SolidCraftDesign.openBundledExample().use { part ->
        part.setParameter("width", "50 mm")
        val body = part.summary().getJSONArray("bodies").getJSONObject(0)
        println("B-rep volume: ${body.getDouble("volume")} mm³")

        val mesh = part.mesh(bodyIndex = 0, tolerance = 0.1)
        showMesh(mesh.toTriMesh().weld())
        println("STEP: ${part.exportStep().size} bytes")
    }
}
```

`SolidCraftDesign.open(path)` opens your own `.solidcraft` file. `summary()` reports each body's
volume, area, bounds and topology counts. `mesh()` supplies double-precision positions, triangle
indices and the originating B-rep face ID for each triangle. `toTriMesh()` copies it into Field's
float mesh format; call `.weld()` when you want Field's index-based watertightness and genus checks.
Close a design with `use { }` when done. An invalid parameter edit leaves the preceding design and
evaluation intact.

This first slice reads and edits existing feature trees. It does not yet create sketches or features
from Kotlin, and its calls run in the calling thread. The [starting point](offline.html?file=assets/agent_transcripts/solidcraft_intro/metadata.edn)
is a small place to test which parts of that API are worth extending.
