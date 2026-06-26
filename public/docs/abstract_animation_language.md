# Abstract Animation Forth

This is an incubating rectangle-animation language in `organic.abstract_animation`. It is deliberately small: agents write sparse world-space rectangle scenes, the Kotlin interpreter builds semantic IR, the CPU samples exact geometry and events, and StackSL remains the material language for fragment shading.

The current JVM workout is:

```diffstatic {data-lang=bash}
python3 tools/field_backdoor.py --json --port 9477 \
  --command 'run-jvm-main organic.AbstractAnimationWorkout'
```

It checks tokenization, definitions, grouping, scalar tracks, time warps, mesh lowering, gradient baking, semantic events, and StackSL material shader generation.

## Language Shape

Use normal Forth definitions and quote literals:

```diffstatic {data-lang=forth}
# comments run to end of line

: white
  0xffffff solid fill
;

: voice-bar
  rect
    white
    2.0 0.16 wh
;

"voice-a" voice-bar
  -12 0.7 xy
  '( 0 -12   12 -4   24 2   32 7 ) x
;
```

The native coordinate system is physical. A `wall 30 4 meters` scene has a centered x range of `-15..15` and y range of `-2..2`, so `1 square` means one meter by one meter.

## Minimal Mirrored Motion

This is the first acceptance example. It should compile to two rectangles, sample mirrored centers, lower to eight vertices and twelve indices, and report a touch event near the overlap.

```diffstatic {data-lang=forth}
wall 30 4 meters
duration 10
fps 60

"r" rect
  -5 0 xy
  1 square
  0xff3300 solid fill
  '( 0 -5   5 0   10 5 ) x
;

dup "r2" rename
  mirror-x
  0x3333ff solid fill
;
```

Kotlin usage:

```diffstatic {data-lang=kotlin}
val result = RectAnimationOracle.compile(source)
require(result.ok) { result.diagnostics.joinToString("\n") }

val program = result.program!!
val sampled = program.sample(5.0)
val mesh = lowerSampledScene(sampled)
val report = analyzeAnimation(program)
```

## Groups And Definitions

Definitions are compositional. A word can create a rect and leave it active so the caller can keep editing it.

```diffstatic {data-lang=forth}
wall 30 4 meters
duration 12
fps 24

: white
  0xffffff solid fill
;

: soft
  0.25 alpha
;

: voice-bar
  rect
    white
    soft
    2.0 0.2 wh
;

"group-low" group
  "low-a" voice-bar
    -2 -1 xy
  ;

  "low-b" voice-bar
    2 -1 xy
  ;
end

"group-low" select
  10 0 translate
  0.1 rot
  2 scale
;
```

Sampling flattens the group transform. The emitted `SampledRect` centers, rotations, scales, and alpha are already world-space semantic state.

## Selections And Batch Edits

Selections are first-class stack values. Use them to copy, filter, and transform whole sets of rects or groups:

```diffstatic {data-lang=forth}
"source" group
  "voice-a" rect
    "voice" tag
    0 0 xy
    1 square
    '( 0 0  4 4 ) linear x
  ;

  "drone-a" rect
    "drone" tag
    2 0 xy
    1 square
  ;
end

"source" select
  clone-deep "copy" rename
;

"copy" select descendants
  "voice" tagged
  '( 1 delay-all-time  0.5 opacity* ) each
;
```

Useful collection words:

- `all`, `roots`, `selected`
- `children`, `descendants`
- `rects`, `groups`
- `"tag" tagged`
- `'( ... ) each`
- `'( ... ) filter` / `'( ... ) where`
- `n '( ... ) times`

Predicate quotes can inspect the active object with `id`, `rect?`, `group?`, and `"tag" tagged?`.

`clone` and `copy` keep group children shared. `clone-deep` and `copy-deep` duplicate a group subtree so later edits can diverge safely.

Selection predicates and numeric edits borrow the small pure-operator subset from StackSL:

```diffstatic {data-lang=forth}
all rects
  '( "voice" tagged? id "other" = or ) filter
  '( 0.5 opacity* ) each
;
```

Useful shared-style words include `+`, `-`, `*`, `/`, `mod`, `pow`, `min`, `max`, `abs`, `floor`, `ceil`, `fract`, `sqrt`, `sin`, `cos`, `clamp`, `sat`, `mix`, `lerp`, `smoothstep`, comparisons, and `and` / `or` / `not`. In RectSL these operate on `Number` and `Track<Double>` values instead of GLSL `vec4`s.

## Tracks

A quote passed directly to a scalar property creates a default TCB track:

```diffstatic {data-lang=forth}
"moving" rect
  '( 0 -8   4 0   8 8 ) x
  0 y
  1 square
;
```

Explicit modes are available when the shape matters:

```diffstatic {data-lang=forth}
"tracks" rect
  '( 0 0  4 8 ) linear x
  '( 0 1  4 3 ) hold y
  '( 0 1  4 3 ) tcb  -0.4 0 0 tcb! scale
  '( 0 0  2 1  4 0 ) alpha
;
```

Time edits are track-aware:

```diffstatic {data-lang=forth}
"delayed" rect
  '( 0 0  2 2 ) tcb 1 delay-time x
  0 y
  1 square
;
```

Object-level retiming maps every scalar track on the active rect or group:

```diffstatic {data-lang=forth}
"copy" select descendants
  '( 0.25 delay-all-time ) each
;
```

Available object retiming words:

- `delay-all-time`
- `scale-all-time`
- `reverse-all-time`
- `loop-all-time`
- `pingpong-all-time`
- `stagger-time`

Property track lenses let you pull an existing scalar track, transform its keys, and assign it back:

```diffstatic {data-lang=forth}
"voice-a" select
  x-track 1 time+ 2 value* x
  alpha-track 0 1 clamp-values alpha
;

"other" select
  x-track 0 2 keys-between 10 value+ x
;
```

Available lens words:

- `x-track`, `y-track`, `w-track`, `h-track`
- `rot-track` / `rotation-track`
- `scale-x-track`, `scale-y-track`
- `alpha-track`

Available key transforms:

- `time+`, `time*`, `snap-time`
- `value+`, `value*`, `clamp-values`
- `keys-before`, `keys-after`, `keys-between`

When a transform can preserve direct keyframes, it does. For already-procedural tracks, value transforms become sample wrappers; key filtering and snapping require direct keyed tracks.

## Paint And Mesh Lowering

Solids become one vertex color for all four corners. Linear gradients are CPU-baked into per-corner colors, so the plain material can render them through normal interpolation.

```diffstatic {data-lang=forth}
"gradient" rect
  0 0 xy
  2 2 wh
  0xff0000 0x0000ff -1 0 1 0 linear fill
  soft-edge material
  0.12 2 3 4 shader
;
```

Lowering emits four vertices and six indices per rect. Each vertex carries:

```diffstatic {data-lang=kotlin}
RectVertex(
    position = Vec4f(worldX, worldY, z, 1f),
    color = Vec4f(r, g, b, a),
    material = Vec4f(materialId, paramBase, objectId, flags),
    shaderData = Vec4f(...)
)
```

The vertex shader derives the corner UV from `gl_VertexIndex & 3`, so every rectangle must start on a vertex index divisible by four.

## StackSL Materials

Animation Forth owns geometry. StackSL owns fragment behavior. A material declaration stores a StackSL quote and assigns a material id:

```diffstatic {data-lang=forth}
material slow-stripes '(
  uv 8.0 * time + sin
  0.5 * 0.5 +
)

"stripe" rect
  0 0 xy
  4 0.35 wh
  0xffcc66 solid fill
  slow-stripes material
  9 0 0.15 0 shader
;
```

The bridge compiles that quote with `StackSLCompiler` and wraps the generated function as:

```diffstatic {data-lang=glsl}
vec4 material_slow_stripes(RectFrag r) {
    vec4 stackInput = vec4(r.uv, r.material.z, r.time);
    vec4 stackColor = rectmat_slow_stripes(stackInput);
    return vec4(stackColor.rgb * r.color.rgb, stackColor.a * r.color.a);
}
```

This convention is intentionally thin. It can be generalized later with more host inputs, material parameter buffers, or StackSL default-value bindings without changing the animation language.

To generate shader text, ask for the insertion block and decide how much wrapping you want:

```diffstatic {data-lang=kotlin}
val program = RectAnimationOracle.compile(source).program!!
val shaderCode = rectMaterialShaderCode(program)

// Insert this into a fragment shader that already defines compatible varyings,
// push constants, and a main() that constructs RectFrag.
val insertion = shaderCode.insertion

// Or use this as a complete known-good wrapper while prototyping.
val wholeFragmentShader = shaderCode.exampleFragmentShader
```

The insertion contains:

- `struct RectFrag`
- built-ins `shadePlain`, `shadeSoftEdge`, and `shadeDiagnostic`
- generated StackSL material functions
- `vec4 dispatchMaterial(RectFrag r)`

The full wrapper adds the standard Field rectangle varyings, push constants, and `main()`.

## Agent Checks

Use `RectAnimationOracle` for small tools:

```diffstatic {data-lang=kotlin}
val parse = RectAnimationOracle.parse(source)
val compile = RectAnimationOracle.compile(source)
val sampled = RectAnimationOracle.sample(program, time)
val mesh = RectAnimationOracle.lower(sampled)
val report = RectAnimationOracle.analyze(program)
```

Good first-pass checks:

- sample important musical times and inspect `SampledRect.center`, `alpha`, and `rotation`
- verify `RectMesh.vertices.size == rectCount * 4`
- verify `RectMesh.indices.size == rectCount * 6`
- read `AnimationReport.events` for touches, leaves, starts, stops, and opacity crossings
- read `AnimationReport.frameMetrics` for density and motion-weighted activity
- compile StackSL materials with `compileStackSLMaterials(program)` before generating the fragment shader

## Writing A Field Mesh

When the shader module uses the standard vertex attribute names from `RECT_ANIMATION_VERTEX_SHADER`, sampled geometry can be written straight into a Field `Mesh`:

```diffstatic {data-lang=kotlin}
val program = RectAnimationOracle.compile(source).program!!
val shaderCode = rectMaterialShaderCode(program)

val module = moduleHere {
    name = "rect-animation"
    primitiveType = triangles
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shaderCode.exampleFragmentShader
}

val mesh = Mesh()
module += mesh named "rects"
program.writeFrameToMesh(time = 1.25, mesh = mesh)
```

The writer expects a triangle-list module with four `vec4` inputs:

```diffstatic {data-lang=glsl}
layout(location = 0) in vec4 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec4 a_material;
layout(location = 3) in vec4 a_shader;
```

If your shader uses different names, pass an attribute map:

```diffstatic {data-lang=kotlin}
program.writeFrameToMesh(
    time = 1.25,
    mesh = mesh,
    attributes = RectMeshAttributeNames(
        position = "position",
        color = "color",
        material = "material",
        shaderData = "shaderData"
    )
)
```

Under the hood this resizes the mesh to `rectCount * 4` vertices and `rectCount * 2` triangles, writes each vec4 attribute buffer, and fills the index buffer with the two triangles per rectangle.

## Example Coverage Map

The companion workout is the executable truth for this page:

```diffstatic {data-lang=bash}
python3 tools/field_backdoor.py --json --port 9477 \
  --command 'run-jvm-main organic.AbstractAnimationWorkout'
```

It currently covers:

| Workout case | What it proves |
| --- | --- |
| `tokenizer` | comments, definitions, colors, quotes, string diagnostics |
| `minimal mirror mesh` | rect creation, default TCB tracks, `dup`, `rename`, `mirror-x`, sampling, mesh counts |
| `definitions and groups` | user words, nested rects, group transforms, alpha/scale propagation |
| `selections and deep timing transforms` | `clone-deep`, `descendants`, `"tag" tagged`, `each`, object-level retiming |
| `operators and key lenses` | StackSL-style scalar words, predicate filtering, `x-track`, key time/value edits |
| `track modes and time warps` | explicit `linear`, `hold`, `tcb`, `tcb!`, track-local time warps |
| `gradient and material lowering` | solid/linear fills, CPU-baked gradient vertices, built-in/custom material ids |
| `semantic analysis` | contact, leave-screen, opacity crossing, motion/frame metrics |
| `StackSL material shader` | StackSL quote compilation, material insertion block, complete wrapper shader |

For agent work, add a workout case whenever a language feature gets a new surface word. Add a docs snippet when the feature changes how a human should write RectSL. The pair is intentional: the workout keeps examples honest, while this page keeps the language learnable.

Because the implementation lives under `src/personal`, the normal Field test catalog does not discover it unless asked directly:

```diffstatic {data-lang=bash}
python3 tools/run_field_tests.py --refresh-catalog \
  --root src/personal/kotlin/organic/abstract_animation_workout.kt \
  --id organic.abstract-animation.workout \
  --port 9478 --json
```

## Current Boundaries

This is still an incubation module. It does not yet do layout words, true vector tracks, full material parameter tables, texture bindings, GPU-side track evaluation, or raster analysis. The intended next move is to lift it from `organic` into the same neighborhood as StackSL and wire it into `afield.four` as a geometry creation path.

The larger Field4 experiment is whether a non-integrated language like RectSL can supply syntax highlighting, diagnostics, and a flow-lens equivalent through an extension API instead of hard-coding every language into Monaco. RectSL is useful for that experiment precisely because it is out of tree: if the extension surface can make this language pleasant without special Monaco plumbing, it is probably a good API.
