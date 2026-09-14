# Keyframer coverage ledger

What `KeyframerCoverageTest` measures, and what it cannot see. `public/docs/keyframer_examples.md`
carries the one-sentence claim; the census is here.

## The contract

`KeyframerCoverageTest` reflects over the members of a **hand-listed set of `keyframer.kt`'s
classes** (`SURFACE`) and requires every public one to be classified as either exercised by
`KeyframerTest` or unreachable with a stated reason. Adding a method or a property to one of those
classes fails the build until it is accounted for.

The three figures are asserted, not printed
(`KeyframerCoverageTest.kt:358`–`360`, `assertEquals`):

| | |
| --- | --- |
| total public behavioural declarations | 120 |
| covered by `KeyframerTest` | 112 (93.3%) |
| unreachable in a headless unit test | 8 |

Data-class members the Kotlin compiler generates are excluded, on the same grounds the codebase
reference excludes them. A further assertion guards the *category*: "unreachable" must mean a live
`Renderer` or the AWT clipboard, and nothing else (`KeyframerCoverageTest.kt:370`).

## The eight

| uncovered | why |
| --- | --- |
| `target(ModulePointer/ComputeModulePointer/MeshModulePointer, name)` | resolves `module.current.liveUniformStructures`; needs a live `Renderer` |
| `stackAuto(…)`, all three overloads | writes shader source through `stackAutoShaderSource`; needs a live `Renderer` |
| `camera(Camera, keyName)` | `Camera(val renderer: Renderer = fromContext())` (`Camera.kt:16#class Camera(val renderer: Renderer = fromContext()) {`) |
| `Key.copyUpdateCodeToClipboard(…)` | the AWT system clipboard throws on a headless JVM |

The docs page's own sections on module targets and on the camera describe the first and third of
these from the caller's side.

## What the ledger cannot see

The class list itself, and the total, are maintained by hand. Only the enumeration *within* the
listed classes is measured, so a new **top-level** declaration is invisible to it:
`enum class KeyDiffKind` (`stdlib/keyframer.kt:1458#enum class KeyDiffKind { ADDED, REMOVED, CHANGED }`)
is public, absent from `SURFACE`, and the suite is green regardless. `KeyframerTest` exercises it;
it simply is not counted, which pushes 93.3% down rather than up.

## Open

Closing the remaining eight needs a renderer in the loop, not more unit tests. Deriving `SURFACE`
from the package rather than from a hand-kept list would make a new top-level declaration visible;
the cost is that it would need its own exclusion list for the readout members, which is the trade
`DerivedCountGuard` made the other way for `vision_geometry.md` (select by return type, no hand-kept
exclusions).
