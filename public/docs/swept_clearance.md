# Moving Parts and Clearance

A door swings, a drawer slides, a hatch lifts. How close does it come to everything around it?
Field can measure the gap throughout a motion, draw the region the part passes through, and check
whether it keeps a clearance you choose.

The tools in `afield.geometry.kinematics` work with Manifold [`Solid`s](manifold.md) or triangle
meshes, in whatever units you model in. The examples here use millimetres.

## Describing a motion

A `RigidMotion` is either a rotation about an axis through a point, between two angles in degrees, or a slide
along a direction, between two distances. Model the part where it rests (the door closed, the drawer shut); the
motion's value 0 is that rest pose.

```diffstatic {data-lang=kotlin}
import afield.geometry.kinematics.*
import afield.math.linalg.Vector3d

// Hinged on the z axis at the origin, opening from 0° to 90°.
val swing = RigidMotion.rotation(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), 0.0, 90.0)
// Pulled out along +x by up to 450.
val pull = RigidMotion.slide(Vector3d(1.0, 0.0, 0.0), 0.0, 450.0)

swing.pose(0.5) // the Matrix4d half-way through, at 45°
swing.poseAt(30.0) // the pose at 30°
swing.stepsBy(1.0) // 91 MotionSteps, one per degree
swing.toJson() // {"type":"rotation","point":[0.0,0.0,0.0],"axis":[0.0,0.0,1.0],"from":0.0,"to":90.0}
```

Poses are column-major `Matrix4d`s mapping the part's rest coordinates to where it is, with the translation in
`m30`, `m31` and `m32`; `poseF` gives the same pose as a float `Matrix4f` for drawing. The JSON is what a viewer
needs to play the motion back, and `RigidMotion.fromJson` reads it.

## How close, where and when

[`sweptClearance`](reference/graphics.md#sweptclearance-kt--sweptclearance) poses the part at every step, finds the gap to the nearest obstacle and the two points that
realise it, and keeps them all. Here a slab 1000 long, 20 thick and 100 high swings about its end past a
100 × 100 box whose nearest corner is 1100 from the hinge, at 45°:

```diffstatic {data-lang=kotlin}
import afield.geometry.kinematics.*
import afield.geometry.solid.manifold.Solid
import afield.math.linalg.Vector3d
import kotlin.math.sqrt

val slab = Solid.cube(1000.0, 20.0, 100.0)
val corner = 1100.0 / sqrt(2.0) // 1100 from the hinge, at 45°
val post = Solid.cube(100.0, 100.0, 100.0).translated(corner, corner, 0.0)
val swing = RigidMotion.rotation(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), 0.0, 90.0)

val c = sweptClearance(slab, swing, [post], maxStep = 1.0)
c.minimum // ClearanceStep(value=44.0000, gap=99.8325 to obstacle 0)
c.lowerBound // 91.1878
```

The slab's far corner is 1000.2 from the hinge, so the true nearest approach is 1100 − 1000.2 = 99.80, at 43.85°.
Sampling once a degree found 99.83 at 44°. `lowerBound` is the other half of the answer: between two samples the
gap can fall no faster than the part's farthest point moves, so no pose, sampled or not, comes closer than 91.19.

Give a `tolerance` and the sweep refines the steps where that bound leaves room for something closer:

```diffstatic {data-lang=kotlin}
val fine = sweptClearance(slab, swing, [post], maxStep = 1.0, tolerance = 0.01)
fine.minimum // ClearanceStep(value=43.8545, gap=99.8000 to obstacle 0)
fine.lowerBound // 99.7901: within 0.01 of the minimum, guaranteed
fine.minimum.contact // the points: on the slab (707.25, 707.25, 100.0), on the box (777.82, 777.82, 100.0)
fine.steps.size // 459, refined only near the minimum
```

For "when", pass a `threshold` too. `firstBelow(threshold)` is then the first step closer than it, and no
earlier pose comes closer than `threshold − tolerance`:

```diffstatic {data-lang=kotlin}
val r = sweptClearance(slab, swing, [post], maxStep = 1.0, tolerance = 0.01, threshold = 101.0)
r.firstBelow(101.0)?.value // the first angle under 101
r.keeps(99.0) // true: the certified bound is over 99
r.gaps() // one gap per step, for a timeline
r.toJson() // the motion and every step's value, gap and obstacle, for a viewer
```

To measure at values of your own choosing, such as an angle set by a slider, make a
`ClearanceProbe(part, motion, obstacles)` once and call `at(value)` for each one. This is the same
probe `sweptClearance` uses for its samples.

Obstacles are measured against the part's surface. When both are closed and one is wholly inside the other, the
gap is 0, as it is when they cross; how deep they cross is not measured.

!!! tip "Build obstacles once"
    `ClearanceBody.of(solid, name)` builds the box tree a distance query needs. Build each obstacle once and pass
    the same list to every part you check, and name them, so the report says which obstacle was nearest. A
    `ClearanceBody` also comes from a `TriMesh`, from raw triangles, as a `box` or as a vertical `prism` over a
    plan outline.

## The swept volume

[`sweptVolume`](reference/graphics.md#sweptvolume-kt--sweptvolume) builds the region the part passes through, as a union of convex hulls of successive poses. Over one
step a point on a rotating part follows an arc, which bulges outside the chord between where it starts and ends;
each hull also takes a third copy of the part, turned to the middle of the step and pushed out from the axis to
where the arc's end tangents meet. So the result always contains the true sweep:

```diffstatic {data-lang=kotlin}
val swept = sweptVolume(slab, swing, maxStep = 1.0)
swept.solid.volume // 8.05732e7, against 8.05712e7 for the exact sweep
swept.maxOvershoot // 8.73: a bound on how far it can reach beyond the true sweep
display(swept.solid.toTriMesh())
```

`maxOvershoot` is a first-order bound (the farthest radius times the step); the measured excess is far smaller,
0.0025 % of the volume at 1° steps. A slide of a convex part is swept exactly.

A part that is not convex is swept triangle by triangle (`SweepShape.AUTO`, the default), so a slot in it stays a
slot; `SweepShape.HULLS` hulls each connected piece whole, which is faster and fills recesses in.

## Keeping a clearance through the whole motion

[`minkowskiClearance`](reference/graphics.md#sweptvolume-kt--minkowskiclearance) asks the question as a rule: does the part keep at least this much from every obstacle,
everywhere along the motion? It offsets each convex piece of the swept volume by the clearance (a Minkowski sum
with a ball, sized so the ball's faces lie outside the true sphere) and intersects the result with the obstacles.

```diffstatic {data-lang=kotlin}
minkowskiClearance(slab, swing, [post], clearance = 85.0, names = ["post"])

// MinkowskiClearance(keeps 85.0, resolution 12.80)

val verdict = minkowskiClearance(slab, swing, [post], clearance = 110.0, names = ["post"])
verdict.passes // false
verdict
    .worst // ClearanceHit(post from 40.00 to 48.00, worst 44.00..45.00, 2.360e+04 in overlap at (788.5, 788.7, 50.0))
```

A pass is certain: every pose keeps the clearance. A fail says which obstacle, over which part of the motion and
where the overlap is worst; it means some pose comes within the clearance plus the `resolution`, the most the
offset sweep can reach beyond the true one. Take a smaller `maxStep` to tighten it.

## Which to use

| | `sweptClearance` | `minkowskiClearance` |
|---|---|---|
| answers | how close, at which value, to which obstacle, at which points | passes or fails a clearance, and where it fails |
| between samples | a certified lower bound; refined to a `tolerance` on request | covered: the hulls contain every pose |
| exactness | exact distances at the samples | conservative to within its `resolution` |
| cost on the box above | about 20 ms for 91 steps, triangle distances only | about 20 ms, one hull and a few Booleans per step |
| needs | nothing native | the Manifold library |
| use it for | the numbers: a gap per step to tint a timeline, the first angle under a rule | a yes or no for a rule, with no sampling question left open |

The two agree on the example: the minimum gap is 99.80, `minkowskiClearance` keeps 85 and fails 110, and the
110 failure centres on 44°, where `sweptClearance` finds the minimum. For a part with thousands of triangles the
distance sweep stays fast, since its box trees prune most triangle pairs, while every Minkowski step hulls the
part's points with the ball's.

## See also

* [Solids & Booleans (Manifold)](manifold.md) for `Solid`, hulls and `minGap`, the clearance between two solids
  standing still.
