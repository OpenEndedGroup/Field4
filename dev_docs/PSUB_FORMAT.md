# The `.psub` plane-subdivision format

A depth map, segmented into a hierarchy of planes. One flat little-endian file whose
sections upload to the GPU verbatim and read on the JVM as `ByteBuffer` slices with no
per-element decoding anywhere.

Producer: `~/temp/planesubdivgame` (C++17, no dependencies). `psub_format.h` there is the
normative header and this document tracks it; `psub.py` is a working reader plus verifier and
is the best cross-check if a loader here disagrees with a file.

Not to be confused with [PLANE_GEOMETRY.md](PLANE_GEOMETRY.md), which is about the 2D
plane — Delaunay, Voronoi, contours. This is 3D plane *fitting*: planes as surfaces
extracted from a depth map. The nearest relative in the tree is
[MESH_GEOMETRY.md](MESH_GEOMETRY.md), since a group's plane plus its oriented box is a quad
away from being a `TriMesh`.

## What is in a file

Two layers over one point set.

**The tree.** A binary subdivision of the points. Each node holds a plane equation, the
oriented bounding box of the points it owns, error metrics, and a contiguous slice of a
permutation array. Children own contiguous halves of their parent's slice, so a point
belongs to exactly one leaf and to every ancestor of that leaf — **a point's full plane
membership is its root-to-leaf chain, stored implicitly.** Walking up from a leaf to a chosen
depth gives a coarser segmentation of the same scene for free, which is how a level-of-detail
view is taken.

**The groups.** A physical surface usually spans several tree cells, because a cut has to go
somewhere. The optional group layer agglomerates leaves that agree on one plane into a flat
table: 28 leaves → 6 groups on the reference synthetic scene, 47 → 16 on a real frame. Groups
do not disturb the tree. If a producer ran without merging, the group sections are empty and
nothing else changes.

## Three layout rules

1. **Little-endian throughout.** No exceptions, no byte-order mark.
2. **Every section starts on a 16-byte boundary** measured from the start of the file, and
   holds fixed-size records. No varints, no compression, no string tables.
3. **Sections are independent.** Nothing needs relocating on load, so `mmap` the file and
   hand a section straight to the GPU, or slice it as an `IntBuffer` / `FloatBuffer`.

## Header — 512 bytes at offset 0

| off | type | field | |
|---|---|---|---|
| 0 | u32 | `magic` | `0x42555350`, i.e. the bytes `PSUB` |
| 4 | u32 | `version` | currently 1 |
| 8 | u32 | `flags` | bit 0 `HAS_CAMERA`, bit 1 `ORTHO` |
| 12 | u32 | `reserved0` | 0 |
| 16 | u32 | `width` | source image width, 0 if the input was a bare point cloud |
| 20 | u32 | `height` | |
| 24 | u32 | `num_points` | valid samples, not pixels |
| 28 | u32 | `num_nodes` | |
| 32 | u32 | `num_leaves` | |
| 36 | u32 | `max_depth` | deepest node's depth |
| 40 | u32 | `num_members` | length of `MEMBER_IDS`, 0 when overlap membership is off |
| 44 | u32 | `num_groups` | 0 when merging is off |
| 48 | f32×3 | `bbox_min` | over all points |
| 60 | f32×3 | `bbox_max` | |
| 72 | f32 | `fit_tol` | the rms tolerance the producer used — bounds the *perpendicular* residual, in whatever units the z channel carries. See gotcha 1 |
| 76 | f32 | `min_fill` | the occupancy threshold it used |
| 80 | — | `sections[16]` | 16 × 24-byte section records, see below |
| 464 | f32 | `cam_fx`, `cam_fy`, `cam_cx`, `cam_cy`, `cam_sx`, `cam_sy` | valid iff `HAS_CAMERA` |
| 488 | u8×24 | `pad` | zero |

The camera block makes the file self-describing, which matters because the *meaning of the
z axis is a producer choice* (see the disparity note at the end). With `ORTHO` clear the points
were unprojected as `x = (u-cx)·z/fx, y = (v-cy)·z/fy, z = value`; with `ORTHO` set, as
`x = (u-cx)·sx, y = (v-cy)·sy, z = value`.

## Section table

Each record is 24 bytes: `u64 offset`, `u64 size`, `u32 stride`, `u32 count`.

| id | name | stride | count | contents |
|---|---|---|---|---|
| 0 | `POINTS` | 16 | points | `float4` — xyz, w = 1 |
| 1 | `POINT_PIXEL` | 4 | points | `v*width + u`, or `0xFFFFFFFF` |
| 2 | `POINT_NORMAL` | 16 | 0 | reserved, never written |
| 3 | `NODES` | 128 | nodes | `PsubNode`; index 0 is the root |
| 4 | `PERM` | 4 | points | point ids; each node owns `[first_point, +point_count)` |
| 5 | `LEAF_OF_POINT` | 4 | points | leaf node id per point — the inverse of `PERM` |
| 6 | `LABEL_IMAGE` | 4 | pixels | leaf node id per pixel, or `0xFFFFFFFF` |
| 7 | `MEMBER_OFFSETS` | 4 | points+1 | CSR offsets, only when overlap membership is on |
| 8 | `MEMBER_IDS` | 4 | — | *extra* node ids a point also satisfies |
| 9 | `GROUPS` | 128 | groups | `PsubGroup` |
| 10 | `GROUP_LEAVES` | 4 | leaves | leaf ids; group owns `[first_leaf, +leaf_count)` |
| 11 | `GROUP_OF_NODE` | 4 | nodes | group id for a leaf, `0xFFFFFFFF` for an internal node |
| 12–15 | | | 0 | spare |

**Test `count`, not `offset` or `stride`.** An absent section may carry a plausible-looking
stride and an offset that is merely wherever the writer's cursor happened to be. `count == 0`
is the only reliable "not present".

## `PsubNode` — 128 bytes

Eight `float4` fetches. Ordered so the geometry comes first: a shader that only wants the
plane and the box reads five `vec4`s and stops.

| off | type | field | |
|---|---|---|---|
| 0 | f32×4 | `plane` | `nx, ny, nz, d`; n is unit and `n·p + d = 0` |
| 16 | f32×3 | `obb_center` | |
| 28 | f32 | `rms` | sqrt of the mean squared **perpendicular** point-plane distance — see the warning below |
| 32 | f32×3 | `obb_u` | major in-plane axis, unit |
| 44 | f32 | `max_err` | |
| 48 | f32×3 | `obb_v` | minor in-plane axis, unit |
| 60 | f32 | `mean_abs_err` | |
| 64 | f32×3 | `obb_w` | **equals `plane.xyz`** |
| 76 | f32 | `inlier_frac` | fraction within the producer's inlier band |
| 80 | f32×3 | `half_extent` | along u, v, w — `half_extent.z` is the slab half-thickness, again perpendicular |
| 92 | f32 | `fill_ratio` | occupancy of the OBB face: solid rectangle 1.0, triangle ≈0.5, annulus ≈0.4 |
| 96 | u32 | `first_point` | offset into `PERM` |
| 100 | u32 | `point_count` | |
| 104 | u32 | `child0` | `0xFFFFFFFF` when a leaf |
| 108 | u32 | `child1` | |
| 112 | u32 | `parent` | `0xFFFFFFFF` for the root |
| 116 | u32 | `depth` | root is 0 |
| 120 | u32 | `flags` | see below |
| 124 | u16 | `cc_count` | connected components of the support in image space; **0 means not computed** |
| 126 | u16 | `hole_permille` | enclosed void as a share of the footprint, in 1/1000 — high means the support wraps a hole |

Flags: `LEAF` 1, `GOOD_FIT` 2, `COMPACT` 4, `SPLIT_FAILED` 8, `DEGENERATE` 16, `HIT_DEPTH` 32,
`HIT_MINPTS` 64. On a leaf they say *why* subdivision stopped there, which is often more
useful than the fact that it did — `SPLIT_FAILED` means a cut was wanted but none earned its
keep, `HIT_MINPTS` means it ran out of points.

## `PsubGroup` — 128 bytes

**The first 96 bytes are byte-identical to `PsubNode`**, deliberately: the same accessor reads
the geometry of either.

| off | type | field | |
|---|---|---|---|
| 0–95 | | | exactly as `PsubNode`: `plane`, `obb_*`, `half_extent`, error metrics |
| 96 | u32 | `first_leaf` | offset into `GROUP_LEAVES` |
| 100 | u32 | `leaf_count` | |
| 104 | u32 | `point_count` | |
| 108 | u32 | `flags` | reserved, always 0 in version 1 |
| 112 | u32×4 | `pad` | zero |

`fill_ratio` is **not** computed for groups and is always 0. Everything else in the shared
prefix is real, recomputed over the union.

## Invariants a loader may rely on

- `PERM` is a permutation of `[0, num_points)`.
- Children partition the parent's range with `child0` first: `child0.first_point ==
  parent.first_point`, and the two counts sum to the parent's.
- **Children are always stored after their parents**, so a single forward pass over `NODES`
  can process the tree with no queue and no recursion.
- Plane normals are unit to within 1e-3, and `obb_w == plane.xyz` exactly.
- Every point lies inside its own node's OBB; overshoot measured at ~1e-7 on real files.
- Groups partition the leaves, and their `point_count` sums to `num_points`.
- `GROUP_OF_NODE` is `0xFFFFFFFF` at every internal node.

## Reading it here

Sketch; adapt to wherever this lands.

```kotlin
private const val PSUB_MAGIC = 0x42555350
const val PSUB_INVALID = -1                 // 0xFFFFFFFF read as a signed Int

class Psub(path: java.nio.file.Path) {
    private val buf: java.nio.ByteBuffer =
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { ch ->
            ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size())
        }.order(java.nio.ByteOrder.LITTLE_ENDIAN)

    init { require(buf.getInt(0) == PSUB_MAGIC) { "not a .psub file" } }

    val version    get() = buf.getInt(4)
    val flags      get() = buf.getInt(8)
    val width      get() = buf.getInt(16)
    val height     get() = buf.getInt(20)
    val numPoints  get() = buf.getInt(24)
    val numNodes   get() = buf.getInt(28)
    val numLeaves  get() = buf.getInt(32)
    val maxDepth   get() = buf.getInt(36)
    val numGroups  get() = buf.getInt(44)
    val fitTol     get() = buf.getFloat(72)
    val hasCamera  get() = (flags and 1) != 0
    val ortho      get() = (flags and 2) != 0
    val fx get() = buf.getFloat(464); val fy get() = buf.getFloat(468)
    val cx get() = buf.getFloat(472); val cy get() = buf.getFloat(476)
    val sx get() = buf.getFloat(480); val sy get() = buf.getFloat(484)

    // section table: 16 records of 24 bytes at offset 80
    // .toInt() caps usable files at 2 GB; fine for single frames, revisit for archives
    fun secOffset(id: Int) = buf.getLong(80 + id * 24).toInt()
    fun secCount(id: Int)  = buf.getInt(80 + id * 24 + 20)
    fun has(id: Int)       = secCount(id) > 0

    /** A read-only view of one section, for GPU upload or bulk copy.
     *  Written without chaining position()/limit() so it compiles on any JDK:
     *  before Java 9 those return Buffer, not ByteBuffer. */
    fun section(id: Int): java.nio.ByteBuffer {
        val off = secOffset(id)
        val size = buf.getLong(80 + id * 24 + 8).toInt()
        val d = buf.duplicate()
        d.position(off)
        d.limit(off + size)
        return d.slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
    }
    // Cache the views you index in a loop; asIntBuffer() allocates.
    val label   by lazy { section(SEC_LABEL_IMAGE).asIntBuffer() }
    val leafOf  by lazy { section(SEC_LEAF_OF_POINT).asIntBuffer() }
    val perm    by lazy { section(SEC_PERM).asIntBuffer() }
    val groupOf by lazy { section(SEC_GROUP_OF_NODE).asIntBuffer() }
    val points  by lazy { section(SEC_POINTS).asFloatBuffer() }

    // ---- nodes, addressed by absolute offset; no per-node object needed
    private val nodes = secOffset(SEC_NODES)
    fun planeX(n: Int) = buf.getFloat(nodes + n * 128)
    fun planeY(n: Int) = buf.getFloat(nodes + n * 128 + 4)
    fun planeZ(n: Int) = buf.getFloat(nodes + n * 128 + 8)
    fun planeD(n: Int) = buf.getFloat(nodes + n * 128 + 12)
    fun rms(n: Int)    = buf.getFloat(nodes + n * 128 + 28)
    fun halfExtent(n: Int, k: Int) = buf.getFloat(nodes + n * 128 + 80 + 4 * k)
    fun fillRatio(n: Int)  = buf.getFloat(nodes + n * 128 + 92)
    fun firstPoint(n: Int) = buf.getInt(nodes + n * 128 + 96)
    fun pointCount(n: Int) = buf.getInt(nodes + n * 128 + 100)
    fun child0(n: Int)     = buf.getInt(nodes + n * 128 + 104)
    fun parent(n: Int)     = buf.getInt(nodes + n * 128 + 112)
    fun depth(n: Int)      = buf.getInt(nodes + n * 128 + 116)
    fun nodeFlags(n: Int)  = buf.getInt(nodes + n * 128 + 120)
    fun isLeaf(n: Int)     = (nodeFlags(n) and 1) != 0

    companion object {
        const val SEC_POINTS = 0; const val SEC_POINT_PIXEL = 1; const val SEC_NODES = 3
        const val SEC_PERM = 4; const val SEC_LEAF_OF_POINT = 5; const val SEC_LABEL_IMAGE = 6
        const val SEC_MEMBER_OFFSETS = 7; const val SEC_MEMBER_IDS = 8
        const val SEC_GROUPS = 9; const val SEC_GROUP_LEAVES = 10; const val SEC_GROUP_OF_NODE = 11
    }
}
```

`section(id)` is the one to reach for when uploading: `NODES` and `GROUPS` are already in
std430-compatible 128-byte stride, and `LABEL_IMAGE` is a plain `width*height` `u32` texture.

### The four operations worth having

```kotlin
/** Every plane a point belongs to: its leaf, then every ancestor, root last. */
fun Psub.ownership(point: Int): List<Int> {
    val out = ArrayList<Int>(8)
    var n = leafOf.get(point)
    while (n != PSUB_INVALID) { out.add(n); n = parent(n) }
    return out
}

/** Coarsen: the ancestor of this node at tree depth <= k. Level-of-detail for free. */
fun Psub.atDepth(node: Int, k: Int): Int {
    var n = node
    while (n != PSUB_INVALID && depth(n) > k) n = parent(n)
    return n
}

/** pixel -> leaf -> group. Two indirections, both plain u32 arrays. */
fun Psub.groupAtPixel(pixel: Int): Int {
    val leaf = label.get(pixel)
    if (leaf == PSUB_INVALID || !has(SEC_GROUP_OF_NODE)) return PSUB_INVALID
    return groupOf.get(leaf)
}

/** What value this node's plane predicts at a pixel — the piecewise-planar approximation. */
fun Psub.predict(node: Int, u: Int, v: Int): Float {
    val nx = planeX(node); val ny = planeY(node); val nz = planeZ(node); val d = planeD(node)
    return if (ortho) {
        // n.(X, Y, value) + d = 0  ->  value = -(d + nx X + ny Y) / nz
        val X = (u - cx) * sx; val Y = (v - cy) * sy
        if (kotlin.math.abs(nz) < 1e-6f) Float.NaN else -(d + nx * X + ny * Y) / nz
    } else {
        // p = z * ray  ->  z = -d / (n . ray)
        val den = nx * ((u - cx) / fx) + ny * ((v - cy) / fy) + nz
        if (kotlin.math.abs(den) < 1e-6f) Float.NaN else -d / den
    }
}
```

Rendering `predict` at successive `atDepth` levels is the level-of-detail view: depth ≤1 is a
couple of planes, the leaves are a low-poly approximation of the surface.

## Which sections you actually need

On a 640×480 frame with 295 k points the whole file is 9.5 MB, and **the four per-point arrays
are 8.3 MB of that.** If you only want the planes and a per-pixel lookup:

| | bytes |
|---|---|
| `NODES` + `GROUPS` + `GROUP_LEAVES` + `GROUP_OF_NODE` | 8 KB |
| `LABEL_IMAGE` | 1.2 MB |
| everything else (`POINTS`, `PERM`, `LEAF_OF_POINT`, `POINT_PIXEL`) | 8.3 MB |

So the geometry is trivially small; the bulk is only there if you need per-point access. Skip
those four sections and a frame costs about a megabyte.

## Seven things that will bite

1. **`rms`, `max_err`, `mean_abs_err` and `half_extent.z` are all *perpendicular*
   point-to-plane distances, not errors in the value channel.** The two differ by
   `1/|plane.nz|`, which is not a small factor: on a real disparity frame the leaf normals ran
   down to `|nz| = 0.0104`, so a plane whose stored rms is 0.0075 can be **96× worse** than
   that at predicting disparity. `fit_tol` bounds the perpendicular residual, so a steeply
   sloped plane is held to a much looser standard in the value channel than a fronto-parallel
   one. If what you care about is "predicted disparity within ε", compute
   `|predict(node,u,v) - observed|` yourself; do not read `rms` as that quantity.

   The relationship is exact and worth writing down, since it is also how to verify a loader:
   `predict(n,u,v) - observed = -(n·p + d) / nz`, where `n·p + d` is the signed perpendicular
   distance the metrics are built from.

2. **The z axis means whatever the producer fed in.** These files are often built from
   *disparity* (inverse depth) rather than metric depth, in orthographic mode with x and y
   scaled to roughly [-0.5, 0.5] and disparity normalised to [0, 1]. That is the right way to
   fit neural depth — a world plane is an exactly affine ramp in `(u, v, disparity)`, so no
   intrinsics are needed — but it means `plane.d`, `rms` and `half_extent` are in *those*
   units, not metres. Check `ORTHO` and the scales before attaching physical meaning.
3. **Normals point at the viewer**, oriented by `n.z < 0`. Stable frame to frame; do not
   re-orient.
4. **`rms` is also outlier-sensitive when the producer's absorb phase ran.** A group can report
   rms 0.0143 whose p99 residual is 0.0066, the difference being 33 points out of 99 228.
   Threshold on `inlier_frac`, or on your own percentile.
5. **`cc_count == 0` means "not computed", not "no components."** It is only filled in when
   the producer needed it.
6. **`hole_permille` is per mille**, not a fraction. 180 means 18 % of the footprint is
   enclosed void.
7. **A group's OBB can be a thick slab** whose centre sits well off its own plane, when the
   group absorbed boundary points. If you need a point on the plane, project the centre onto
   it rather than assuming it is already there.

## Verifying a loader

`psub.py` in the producer tree reads and re-derives everything independently; run it on the
same file and compare. Three checks are worth porting here as a test, because each one fails
loudly if an offset is wrong rather than quietly returning plausible numbers:

- `PERM` sorted equals `0 until numPoints`.
- For every leaf, the rms of `nx·X + ny·Y + nz·value + d` over that leaf's pixels equals the
  stored `rms`. Measured agreement on a real file is **2.5e-9** — this single check exercises
  the section table, the node stride, the plane convention, the camera block and the
  orthographic/perspective branch all at once.
- Group `point_count` sums to `numPoints`, and `GROUP_OF_NODE` is `0xFFFFFFFF` at every node
  whose `child0` is not `0xFFFFFFFF`.

## Versioning

`version` is 1. Section ids 12–15 are spare and unused sections carry `count == 0`, so adding
a section is backward compatible — a loader that checks `count` ignores what it does not know.
Growing `PsubNode` or `PsubGroup` past 128 bytes, or changing a field's meaning, is not; that
takes a version bump.
