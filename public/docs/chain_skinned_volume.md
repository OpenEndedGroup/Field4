# A twisted limb, weighed and traced

Twist a skinned limb about its own bone and linear blend skinning squeezes the joint into what
animators call a candy wrapper. Field already measures that as a length:
`DualQuaternionSkinning.candyWrapperRadius` reports the narrowest ring. A length does not say how
much of the limb has gone. This page asks for the volume. It poses one rigged glTF, turns the
posed mesh into a Manifold solid, weighs it, and hands the same solid to the path tracer.

Three parts of Field that do not know about each other: `afield.four.modules` skins the file on the
CPU, `afield.manifold` turns triangles into a solid and measures it, and `afield.four.fabricated`
traces the result.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_skinned_volume/twist.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="skinned volume study converged" embed=true hidden=true timeout="300" name="chain-skinned-volume-trace" alt="Two twelve-sided tubes lying side by side on a pale floor with the Venice lagoon behind. The nearer cyan tube, posed by linear blend skinning at a half turn, narrows to a point at its middle like a twisted sweet wrapper; the tan tube behind it, posed by dual quaternion skinning, keeps its full width along its whole length" macro-file="../../dev_docs/agents/transcripts/skinned_volume_path_trace.yaml" capture-box="skinned_volume_trace"}
// Built and launched from dev_docs/agents/transcripts/skinned_volume_path_trace.yaml.
```

Both tubes are the same file at the same pose: the upper bone turned 180° about its own axis. The
nearer, cyan tube was skinned by linear blending and the tan one behind it by dual quaternions. The
cyan tube's middle ring is not just thin. It is a single point.

Environment: Venice Sunset by Greg Zaal / Poly Haven (CC0), shared with the
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

## A rig that encloses something

`RiggedFixture`, the rig the skinning tests use, is an open tube with no caps, so it encloses no
volume and Manifold will not take it as a solid. `CappedTwistFixture` is the same two-bone rig with
both ends closed. Like `RiggedFixture`, it is written out by Kotlin source rather than downloaded:

* 12 sides, radius 0.5, height 4, with rings every 0.25 along the bone and a centre vertex on each
  cap. That makes 206 vertices and 408 triangles, and every edge is shared by exactly two of them.
* `boneLower` at the origin, `boneUpper` at `y = 2`. The weight to `boneUpper` is
  `clamp((y − 1) / 2, 0, 1)`, so the joint zone runs from `y = 1` to `y = 3` and the ring at `y = 2` is
  an even 50/50 blend.
* One clip, `twist`, turns `boneUpper` from 0° to 180° about `+y`. The sampler slerps, so the angle
  is linear in time and `secondsFor(degrees)` converts.

At rest the tube is a straight prism over a regular 12-gon, and the volume has a closed form: area
`½ · 12 · 0.5² · sin 30° = 0.75`, times height 4, is **3.0**.

## Volume against twist

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_skinned_volume/sweep.png" wait="8" capture="box" open-outputs="true" w="1000" h="460" padding="16" timeout="180" requires="manifold" name="chain-skinned-volume-sweep" alt="A line plot of enclosed volume against twist angle from 0 to 180 degrees. The linear-blend trace falls smoothly from 3.0 to 2.0; the dual-quaternion trace rises very slightly above the flat rest line at 3.0 and eases back" embed=true}
:::static
import afield.four.modules.CappedTwistFixture
import afield.four.modules.GltfSkinning.SkinningMethod
import afield.four.modules.GltfSkinning.SkinningOptions
import afield.four.modules.skinnedGltfToTriMesh
import afield.manifold.toSolid

val rig = CappedTwistFixture.locate()
val twists = (0..12).map { it * 15f }

// Pose the file, hand the triangles to Manifold, read back the enclosed volume.
fun volumeAt(degrees: Float, method: SkinningMethod): Double =
    skinnedGltfToTriMesh(
        rig, clip = "twist", seconds = CappedTwistFixture.secondsFor(degrees),
        options = SkinningOptions(method = method),
    ).toSolid().use { it.volume }

:::fold sweep both methods and plot them against the rest volume
val lbs = twists.map { volumeAt(it, SkinningMethod.LINEAR_BLEND) }
val dqs = twists.map { volumeAt(it, SkinningMethod.DUAL_QUATERNION) }
showSignals(
    "linear blend" to lbs,
    "dual quaternion" to dqs,
    "rest, closed form" to twists.map { CappedTwistFixture.REST_VOLUME },
    x = twists,
    channel = "main",
    title = "Enclosed volume at 180°: linear blend %.4f, dual quaternion %.4f".format(lbs.last(), dqs.last()),
    xTitle = "twist of boneUpper about its own axis (degrees)",
    yTitle = "Manifold volume",
)
:::endfold
```

Thirteen poses, fifteen degrees apart. Under linear blending the volume falls at every step. Under
dual quaternions it goes slightly *up*. Those are the two artefacts `DualQuaternionSkinningTest`
already measures as edge lengths, a pinch and a bulge, and now they show up as volume too.

## The numbers each hop owes the next

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_skinned_volume/readout.png" wait="8" capture="box" open-outputs="true" w="1060" h="330" padding="18" timeout="180" requires="manifold" name="chain-skinned-volume-readout" embed=true}
:::static
import afield.four.modules.CappedTwistFixture
import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning.SkinningMethod
import afield.four.modules.GltfSkinning.SkinningOptions
import afield.four.modules.skinnedGltfToTriMesh
import afield.manifold.toSolid
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume

val rig = CappedTwistFixture.locate()

:::fold measure rest and a half turn, both methods, both volume implementations
fun row(label: String, degrees: Float, method: SkinningMethod): String {
    val mesh = skinnedGltfToTriMesh(
        rig, clip = "twist", seconds = CappedTwistFixture.secondsFor(degrees),
        options = SkinningOptions(method = method),
    )
    val waist = DualQuaternionSkinning.candyWrapperRadius(
        mesh.positions, CappedTwistFixture.UPPER_JOINT_Y, tolerance = 0.01f,
    )
    return mesh.toSolid().use { solid ->
        val field = mesh.signedVolume()
        "| $label | ${mesh.isWatertight()} | ${solid.genus} | %.6f | %.6f | %.1e | %.4f | %.4f |".format(
            solid.volume, field, kotlin.math.abs(solid.volume - field),
            solid.volume / CappedTwistFixture.REST_VOLUME, waist,
        )
    }
}

markdown("chain", """
| pose | watertight | genus | Manifold volume | `signedVolume` | gap | of rest | waist radius |
|---|---|---|---|---|---|---|---|
${row("rest", 0f, SkinningMethod.LINEAR_BLEND)}
${row("linear blend, 179.9°", 179.9f, SkinningMethod.LINEAR_BLEND)}
${row("linear blend, 180°", 180f, SkinningMethod.LINEAR_BLEND)}
${row("dual quaternion, 180°", 180f, SkinningMethod.DUAL_QUATERNION)}

Closed forms: rest ${CappedTwistFixture.REST_VOLUME}; linear blend at 180°, 0.75 × (2 + 2/3) = ${"%.6f".format(0.75 * (2.0 + 2.0 / 3.0))}.
""", width = 1000)
Unit
:::endfold
```

Manifold's `volume` and Field's `signedVolume` share no code. One integrates Manifold's own
half-edge solid in double precision, the other sums signed tetrahedra over the float triangles.
Where they agree, the skinned mesh reached Manifold intact.

**Linear blending at 180° has a closed form, and it is two thirds.** At the 50/50 ring the blend
averages the identity with its own half turn, `½·diag(1,1,1) + ½·diag(−1,1,−1) = diag(0,1,0)`, so
every vertex of that ring lands on the axis. In general, a ring whose weight is `w` gets
`diag(1 − 2w, 1, 1 − 2w)`: a pure scale with no rotation left in it. Its scale `1 − 2w = 2 − y`
is linear in `y`, so each band of the joint zone is a frustum of two similar 12-gons. The zone then
integrates exactly to `0.75 · ∫₁³ (2 − y)² dy = 0.75 · ⅔`, and the two unblended units keep their
0.75 each. The total is 2.0, so a third of the limb is gone, and none of that loss comes from how
the tube happens to be triangulated.

!!! note "At an exact pinch, Manifold weighs its own triangulation"

    At 180° exactly, the 50/50 ring does not quite shrink to a point. Float rounding in the
    half-turn quaternion leaves it about 2·10⁻⁷ across, which is under the tolerance Manifold
    gives a mesh of this size (about 5·10⁻⁷). Manifold keeps every one of the 206 vertices and all
    408 triangles, but it swaps edges in that collar, so the volume it reports is the volume of *its*
    triangulation. That is the gap in the 180° row. At 179.9° the ring is well above tolerance and
    the gap disappears. If a measured volume matters at a degenerate pose, compare it with
    `signedVolume` on the mesh you handed over.

## What crosses each hop

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| file to posed triangles | `skinnedGltfToTriMesh` with `SkinningOptions(method = …)` | positions and normals, scene space, one vertex per file vertex | the skeleton, the weights and the clip. What comes out is a plain `TriMesh` |
| triangles to a solid | [`toSolid`](reference/graphics.md) | positions and indices | the normals. Manifold gets three floats per vertex and nothing else |
| a solid back to triangles | [`toTriMesh(normals = true)`](reference/graphics.md) | positions, indices, normals split at a crease angle | shared vertices along the creases. Measure the solid, not this mesh |
| triangles to a scene | [`importTriMesh`](reference/renderers.md#pathtracetrimesh-kt-functions) | positions, normals, one material, a transform | zero-area triangles, which are counted and skipped |

Linear blending and dual quaternions are the same call with a different `SkinningOptions`, so the
comparison above runs both from one palette on one file. The only difference between the two
tubes in the render is that `method` argument.

## Where each piece is documented

* [Skinned Characters](agent_transcripts.md#skinned_characters) is the transcript that introduced
  skinning, the bending cylinder, and the candy wrapper measured as a radius.
* [Solids & Booleans (Manifold)](manifold.md) covers `toSolid`, what it refuses and why, and the
  status model.
* [Progressive Path Tracing](path_tracing.md) covers the tracer, its materials and its two traversal
  backends.
* [A polytope, cut open, and traced](chain_polytope_render.md) does the same last two hops with a
  Conway polyhedron at the front.

??? aside "Why this fixture is not the bending cylinder with lids"

    The first plan was to cap `RiggedFixture`'s tube as it is: five rings at `y = 0 … 4`, of which
    only the middle one is blended. The trouble is dual quaternions. At a half turn they rotate that one ring 90°
    against each neighbour, and a straight triangulated band between two 12-gons 90° apart
    passes within `r/√2` of the axis. The triangulation alone would then remove about a third of those
    bands' volume, and the page would blame dual quaternions for it. Seventeen rings spread the
    joint's rotation over eight bands, 22.5° each at the worst pose. The capped fixture is a new
    object rather than an edit because `RiggedFixtureDriftTest` pins the bending cylinder's
    bytes. `SkinnedVolumeTest` pins every structural claim on this page: the open tube is not a
    solid; the rest volume is 3.0; the tip angle matches the requested twist; linear blending falls at
    every step to 2.0; dual quaternions stay above rest and within 1%; the two volume
    implementations agree everywhere except at the exact pinch.
