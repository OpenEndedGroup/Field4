# Mesh repair: the 27-mesh table, and what "faithful" means in it

Displaced from `public/docs/geometry_from_disk.md`, which is a page about getting geometry off
disk and into a Boolean. This is the measurement behind the one claim that page now makes —
that `repairToSolid()` fixes open boundaries and non-manifold edges, and refuses rather than
guesses — plus the residuals that claim hides.

Owner: `src/jvmTest/kotlin/afield/stdlib/MeshFromDiskTest.kt`. Every number below is asserted
there as an exact value, not printed.

## How the table is built

27 meshes, each broken in a named, deterministic way, each a damaged copy of a known-good mesh
so the correct answer is known in advance.

**21 of the 27 are rejected by Manifold as-is.** The other six are damaged inputs Manifold
accepts unchanged; they are excluded from every count below, because a repair that "fixes" a mesh
Manifold never objected to has measured nothing.

"Repaired" is judged against **the undamaged original** — volume, area and genus — rather than
against `isValidSolid()`. This is the distinction the whole table exists to make: `isValidSolid()`
passing means Manifold will accept the mesh, not that it is the mesh you started with.

`repaired()` (weld → degenerate → unused → winding, in `mesh_topology.kt`) already covers a real
share of the ground, so the comparison has a working baseline rather than a null one.

## The counts, of the 21 Manifold rejects

| | count |
|---|---|
| the pre-existing `repaired()` makes valid | **6** — every unwelded-seam and every inconsistent-winding case, all of them faithful |
| `repairToSolid()` makes valid | **19** |
| …of which **faithful** — volume, area *and* genus match the original | **15** |
| …of which **valid but the wrong shape** | **4** |
| cannot be fixed, and say so | **2** |

The two that cannot be fixed assert that the failure is *reported*: a `NaN` position, refused
outright because repairing around it would silently delete the affected triangles and cap the
gap, and two closed tetrahedra sharing a face.

## Why the 15/4 split is the interesting number, and not the 19

A fan fill is trivially correct on a planar rim. So a table of flat holes measures only the easy
case, and lets "fixes" mean no more than "Manifold accepts it". The four merely-valid rows are
the curved and non-planar holes that separate valid from faithful:

| the four merely-valid rows | volume error |
|---|---|
| a hole across a torus's tube | 1.5% |
| a hole that severs the tube (genus 1 → 0) | 4.2% |
| a wide hole over a sphere | 1.9% |
| a crowned rim | **35%** |

The crowned rim at 35% is the honest ceiling on what a fan fill can do, and the reason the page
tells a reader to compare against the shape they meant rather than to trust `isSuspect`.

## Scope

Beyond what `repaired()` already does, `repairToSolid()` is responsible for exactly two families:
open boundaries, and non-manifold edges from duplicated faces or dangling fins. Anything else in
the 27 is either already handled by `repaired()` or in the two-row refusal set.

## Open

- **Nothing pins the six excluded meshes as excluded.** The count "21 of 27" is asserted, so a
  mesh moving between the accepted and rejected groups would fail — but the *reason* the six are
  out (Manifold accepts them unchanged) is prose here and in the test's comments, not an
  assertion about Manifold's behaviour on them.
