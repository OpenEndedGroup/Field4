# Geometry (madp `3d.html`) → Field·4 starting point

The madp demo is a stateful, immediate-mode triangle DSL: `build()` runs on every edit, emits
triangles through a transform stack with a current colour and a current material, and three.js
draws the result on a dark studio ground with soft shadows, SSAO and ACES tone mapping. "Animate"
re-runs `build()` per frame with `t`; "Export GLB" writes the meshes, optionally as morph-target
animation.

## Shape of the port

The student's program is a Kotlin box. Its body is the `build()` of the original, as a block:

```kotlin
geometry {
    view(background = "#05080a", ground = true, grid = true)
    mesh("ripple terrain")
    mat(roughness = 0.86, metalness = 0.03, flat = true)
    surface(78, 78) { u, v ->
        val x = (u - 0.5) * 7.2
        val z = (v - 0.5) * 7.2
        val r = sqrt(x * x + z * z)
        val y = sin(r * 3.6) * 0.22 + noise(x * 0.75, z * 0.75) * 0.38
        vertex(x, y, z, hsl(188 + y * 56 + r * 5, 0.74, 0.42 + y * 0.08))
    }
}
```

`Box.geometry { }` builds a `GeometryScene` and shows it in a `three { }` view with the studio look.
`Box.animateGeometry { }` returns a `Frames` that rebuilds the scene at 30 fps with `t` advancing
and pushes each frame into the same viewer (fixed topology is not required to *show* it, only to
export it as animation).

## API mapping

| madp | Field | Where |
| --- | --- | --- |
| `mesh(name, mat)` | `mesh(name)` | `GeometryBuilder` |
| `mat({color, roughness, metalness, emissive, emissiveIntensity, opacity, side, wireframe, flat})` | `mat(color=, roughness=, metalness=, emissive=, emissiveIntensity=, opacity=, side=, wireframe=, flat=)` (named args, merged into the current material) | `GeometryBuilder` |
| `color(c)` | `color(c)` | `GeometryBuilder` |
| `push/pop/identity/translate/rotateX/Y/Z/scale` | same names | `GeometryBuilder` (a `Mat4` stack) |
| `tri`, `quad` | `tri(a, b, c)`, `quad(a, b, c, d)` over `Vertex` or `Vec3` | `GeometryBuilder` |
| `surface(cols, rows, (u,v)=>{pos,color})` | `surface(cols, rows) { u, v -> vertex(...) }` | `GeometryBuilder` |
| `revolve(profile, segs)` | `revolve(profile: List<ProfilePoint>, segments)`; `profile(r, y, color)` | `GeometryBuilder` |
| `tube(segs, sides, t => {pos, radius, color})` | `tube(segments, sides, closed) { t -> section(pos, radius, color) }` | `GeometryBuilder` |
| `box(center, size)` | `box(center, size)` | `GeometryBuilder` |
| `v/mix/polar/spherical` | `vec(x,y,z)`, `mix`, `polar`, `spherical` | `GeometryBuilder` |
| `hsl/rgb` | Field's `hsl`, `rgb`, or a CSS hex string | see below |
| `seed/rand/noise` | `seed`, `rand`, `noise` — the same LCG and the same permutation table, so `seed(18)` grows the same crystal bloom | `GeometryBuilder` |
| `view({background, ground, grid})` | `view(background=, ground=, grid=)` | `GeometryBuilder`, carried to the viewer as a studio spec |
| `t, time, frame, dt` | `t`, `frame`, `dt` | `GeometryBuilder` |
| Export GLB | the viewer's own Export GLB button; `GeometryScene.writeGlb(file)`; animated: `writeGlbAnimation(file, frames, fps) { … }` writes morph targets | shared |
| gltfpack | dropped: the GLB is written uncompressed (gltfpack is an external binary) | gap |

### Colour

three.js `Color.setHSL` reads its arguments in the *linear* working space (r152+, the vendored
`three.core.js` says `colorSpace = ColorManagement.workingColorSpace`), and Field's top-level
`hsl()` reads the HSL cube as linear light and encodes it to sRGB. So the two agree, and the
presets use Field's own `hsl` unchanged. Strings and `rgb()` are sRGB in both. The builder decodes
every colour to **linear** for the `TriMesh` vertex colours, which is what three.js and glTF's
`COLOR_0` both expect.

## Shared code (with KDoc and tests)

1. `afield/geometry/mesh/GeometryBuilder.kt` — the DSL above, producing `GeometryScene`
   (a list of `GeometryPart(name, material, TriMesh)` plus the view state). Each material change
   after triangles were emitted starts a new part, as in the original.
2. `three` viewer **studio look** (`threejs_viewer.html`, `ThreeSceneBuilder.studio(...)`):
   ACES filmic tone mapping, PCF soft shadows from a key light, hemisphere + rim fill, a dark
   receiving ground plane and an optional faint grid at a given height. Off by default, so every
   existing figure keeps its look.
3. Per-drawable **PBR material** fields on `ThreeDrawStyle` (`roughness`, `metalness`, `emissive`,
   `emissiveIntensity`, `color`), omitted from the wire when unset.
4. `ThreeSceneBuilder.geometry(scene)` and `Box.geometry { }` / `Box.animateGeometry { }`
   (`afield/graphics/three/GeometryView.kt`).
5. `afield/geometry/mesh/GeometryGlb.kt`: GLB writer for a `GeometryScene` (static, and morph-target animation over a fixed topology).

## Dropped or changed

- **SSAO**: the vendored three carries no postprocessing addons and the viewer renders several
  panels by scissor; an `EffectComposer` there is a larger change than this port. Shadows + tone
  mapping carry most of the look. Gap, recorded.
- gltfpack compression; Monaco AI completion; agent chat; local saves; "Copy Guide" (the guide is
  KDoc on `GeometryBuilder`).
- `side` defaults to double as in the original.

## Starting point

`madp_geometry` transcript: a lead box (ripple terrain) and presets crystal bloom, lathe vessel,
knot tube, breathing sheet (animated), fungal tree, menger lesson — in a column, spaced by their
output panels.
