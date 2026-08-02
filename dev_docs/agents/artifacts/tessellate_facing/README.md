# `FLine.tessellate` facing — before and after

Two renders of the same scene, produced by the same code, differing only in
`src/jvmMain/kotlin/afield/graphics/FLine.kt`.

The scene is a cube built as **six filled FLines**, one per face, each with a square hole, each
wound counter-clockwise seen from *outside* the cube. It is drawn through a perspective camera with
**back-face culling** and flat diffuse shading. Culling is the whole instrument: it is the thing
that notices which way a triangle faces, and it is the thing Field's own viewers switch off
(`four/modules/MeshModule.kt:466` sets `VK_CULL_MODE_NONE`; `threejs_viewer.html:900` uses
`THREE.DoubleSide`), which is why this defect shipped without anyone seeing it.

| | |
|---|---|
| `before_590e1f781.png` | The `+y` face (green, the lid) is **gone**. You are looking straight into the box, at the *insides* of the far `-x` and `-z` walls, which are drawn because their winding is inverted too. |
| `after_fixed.png` | Three lit faces — `+x` red, `+y` green, `+z` blue — each with its hole, and nothing behind them. |

Three of the six faces were emitted back-to-front:

* `+y`, because the y-dominant projection kept `(x, z)`, which is the *mirror* of the right-handed
  cycle `y -> (z, x)`;
* `-x` and `-z`, because the negative hemisphere of the dominant axis was not handled — a face whose
  normal points down the axis projects mirror-reversed even on the correct pair.

`-y` came out right in the before image only by the accident of two mirrors cancelling.

## Regenerating

Both images come from `src/jvmTest/kotlin/afield/graphics/TessellationFacingRenderTest.kt`, which
also asserts the same fact numerically (so it fails in CI without anyone looking at a picture).

After:

```
AFIELD_TESSELLATION_RENDER_OUT=dev_docs/agents/artifacts/tessellate_facing \
  ./gradlew jvmTest --tests 'afield.graphics.TessellationFacingRenderTest'
mv dev_docs/agents/artifacts/tessellate_facing/cube_faces.png \
   dev_docs/agents/artifacts/tessellate_facing/after_fixed.png
```

Before — revert only the tessellator, keep the test:

```
git checkout 590e1f781 -- src/jvmMain/kotlin/afield/graphics/FLine.kt
AFIELD_TESSELLATION_RENDER_OUT=dev_docs/agents/artifacts/tessellate_facing \
  ./gradlew jvmTest --tests 'afield.graphics.TessellationFacingRenderTest'   # fails, as it should
mv dev_docs/agents/artifacts/tessellate_facing/cube_faces.png \
   dev_docs/agents/artifacts/tessellate_facing/before_590e1f781.png
git checkout HEAD -- src/jvmMain/kotlin/afield/graphics/FLine.kt
```

Without the environment variable the test renders to a temp directory and asserts there, so an
ordinary test run never writes into the repository.
