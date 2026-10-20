# Graphics on the GPU

The "4" in Field·4 is its renderer: Vulkan, driven from boxes. Boxes on the GPU work the
way the ones on the previous pages do. You write a few lines of Kotlin, run the box, and the
picture changes while it runs. The difference is that some of a box's properties are now shaders,
and the box draws into a native window instead of its output panel.

## A renderer, and a box beneath it

One box opens the window and runs the frame loop. Its code publishes the window and the renderer
as properties, builds them once, and starts the renderer:

```diffstatic {data-lang=kotlin}
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = true

once.renderer {
    window = Window("Field")
    Renderer(window)
}

start(renderer::run)
```

`once.renderer { }` keeps the renderer across re-runs, so running this box again does not open a
second window. Every box you connect beneath it (select the renderer box, then the new box, and
press `g`) can use `renderer` directly, the same way a child box sees any property its parent
publishes. The canonical_graphics starting point (**Welcome → Starting points →
canonical_graphics**) already has this box, named **window**; launch it first.

## A shader-driven result

This box draws one square that covers the window, and lets its fragment shader decide every pixel.
The box has three properties: `code` (the Kotlin below), and `vertex` and `fragment`, the two
shaders.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_gpu_graphics/rings.png" alt="a native render window filled edge to edge with concentric rings around its centre, alternating mid blue and pale gold with soft gradients between them, stretched into ellipses by the wide window" wait="1" timeout="90" capture="native-window" native-wait-frames="8" trim-native=true graphics-context=true embed=true name="tour-gpu-rings" source-box="assets/tour_gpu_graphics/rings" source-box-properties="vertex,fragment" source-box-wait="0.1"}
val started = AnimationClock.nowSeconds()

renderer += moduleHere {
    +Mesh("square") {
        // four corners in clip space, where the window runs from -1 to 1
        set(4, 2) {
            pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
            tc.add(0, 0, 1, 0, 1, 1, 0, 1)
            tri.add(0, 1, 2)
            tri.add(0, 2, 3)
        }
        // read once per frame, and handed to the shader as `pc.time`
        time = { (AnimationClock.nowSeconds() - started).toFloat() }
    }
}
```

`moduleHere { }` makes a draw pipeline from this box's `vertex` and `fragment` properties, and
`renderer += ...` puts it on screen. The names inside `set(4, 2) { }` are not part of a library:
`pos` and `tc` exist because the vertex shader declares those two inputs, and `tri` is the index
buffer every mesh has.

```diffstatic {data-lang=glsl}
#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec2 tc;

layout (location = 1) out vec2 ftc;

void main()
{
    gl_Position = vec4(pos, 1);
    ftc = tc;
}
```

`time` comes from the fragment shader the same way. It is a member of the shader's push constants,
so the mesh gets a `time` property, and the function assigned to it is called every frame. The
shader declares a `float`, so the function returns a `Float`: seconds since the box ran. The
clock is the `AnimationClock` from the [previous page](animation_time_model.md), so the rings drift
outward at the same rate live and in a recording.

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(push_constant) uniform constants {
    float time;
} pc;

void main()
{
    vec2 p = ftc * 2.0 - 1.0;
    float r = length(p);
    float rings = 0.5 + 0.5 * sin(24.0 * r - 3.0 * pc.time);
    vec3 col = mix(vec3(0.05, 0.10, 0.30), vec3(1.0, 0.65, 0.25), rings);
    uFragColor = vec4(col, 1.0);
}
```

## A live edit

Shaders are properties like any other, and editing one is a live edit. Open the box's `fragment`
property and add the angle around the centre to the line that makes the rings:

```diffstatic {data-lang=glsl}
    float rings = 0.5 + 0.5 * sin(24.0 * r - 3.0 * pc.time + 5.0 * atan(p.y, p.x));
```

Run the box again and the rings become a five-armed spiral, still turning. The window stays open
and the renderer keeps its frame loop. Field recompiles the shader, reads its interface again, and
the mesh named `"square"` replaces the old one, so nothing piles up.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_gpu_graphics/spiral.png" alt="the same full-window blue and gold pattern, now a five-armed spiral winding out from the centre instead of concentric rings" wait="1" timeout="90" capture="native-window" native-wait-frames="8" trim-native=true graphics-context=true embed=true name="tour-gpu-spiral" source-box="assets/tour_gpu_graphics/spiral" source-box-properties="vertex,fragment" source-box-wait="0.1"}
val started = AnimationClock.nowSeconds()

renderer += moduleHere {
    +Mesh("square") {
        set(4, 2) {
            pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
            tc.add(0, 0, 1, 0, 1, 1, 0, 1)
            tri.add(0, 1, 2)
            tri.add(0, 2, 3)
        }
        time = { (AnimationClock.nowSeconds() - started).toFloat() }
    }
}
```

## Where to go from here

- [Field 4 Graphics](afield_four_graphics.md) is the full walkthrough: buffers, cameras, compute
  shaders and particles, textures, glTF, render-to-texture and blur, each with its picture.
- The [lesson index](https://github.com/OpenEndedGroup/Field4/blob/main/public/docs/assets/canonical_graphics/LESSONS.md)
  lists every lesson in the canonical_graphics starting point, with the parent each one runs
  under, and which lesson uses which call (`RenderToTexture`, `computeModuleHere`,
  `LoadedTexture`, ...).
- [Graphics in an Output Area](four_in_output_areas.md) draws into a box's output panel instead of
  a window, in Field's desktop app.
