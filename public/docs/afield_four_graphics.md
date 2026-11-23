# Field 4 Graphics

<div data-field-transcript-gallery hidden></div>

Build a window, draw into it, and then start changing what the GPU does. This curriculum takes you
from a triangle through cameras, textures, compute shaders and particles, with each lesson in a
box you can run and edit. For a shorter first encounter, try [Graphics on the GPU](tour_gpu_graphics.md).

Open **Welcome → Starting points → canonical_graphics** to get an editable copy
of this complete graphics curriculum, including its code and shader sidecars.
Launch the **window** box first, then the lesson you want to explore. The bundled
original stays unchanged, and opening it again makes another fresh copy.


`Field 4` is Field's direct Vulkan path. Vulkan is a powerful, low-level graphics API that is notorious for how hard it is to write code for. Field turns it into something that is pleasant to hack on in real time. But [p5.js](https://p5js.org) it ain't.

A graphics box usually has three parts:

- Kotlin code that creates modules, meshes, buffers, cameras, textures, and renderer connections.
- Shader properties: properties of the box, such as `vertex`, `fragment`, `compute`, or `vertex_fragment`, that hold GLSL code. You switch the code editor to one of them and edit it like the box's Kotlin.
- Reflected accessors that Field generates by reading the shader interface. Field compiles the shaders, reads which inputs, buffers, uniforms and push constants they declare, and gives the box Kotlin properties with the same names, so you never write binding numbers or byte offsets by hand.

In any graphics engine, the low-level view that the GPU has of the data and the high-level CPU view of it have to match. In Field the low-level view is written in GLSL (or [Slang](#slang)), and the high-level view in Kotlin (or another of Field's languages). The GLSL drives that conversation, and the Kotlin side follows it. If a vertex shader declares `layout(location = 0) in vec3 pos`, the Kotlin side can write `pos.add(...)` inside `m.set { ... }`. If a shader declares a push constant member named `vertexData`, the Kotlin side can assign `m.vertexData = { vertexData }`. A shader is both the code that runs on the GPU and the contract that shapes the Kotlin API for its box.

<iframe
  src="offline.html?file=assets/canonical_graphics/metadata.edn"
  title="Canonical Graphics Offline Viewer"
  style="width:100%; height:640px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>

This page is about the low-level Vulkan pieces: what you need for the kind of shader hacking
behind a lot of new media art and data visualization. For more standard photorealistic rendering,
progressive PBR with environment lighting, glass and editable
material shaders, see [Progressive path tracing](path_tracing.md), including
[glTF and GLB import](path_tracing.md#loading-gltf-and-glb-models). It renders into
an ordinary `RenderToTexture`, so its output can be combined with the techniques
in this guide. The [firefly controls](path_tracing.md#reducing-bright-specks) help
keep interactive previews clean.

## Hello triangle, first attempt

The [triangle replay](agent_transcripts.md#afield_four_traditional_triangle) creates the draw
box. Connect it under a running renderer before you run its code.

Start with the rite of passage for any new graphics system: getting a triangle on the screen. That needs one of everything: one shader module, one mesh to draw, one renderer and one window. The code below makes each of these in turn:

- `moduleHere { ... }` creates a shader module, one draw pipeline, from the `vertex` and `fragment` shader properties of the box it runs in. If the box has no shader of its own yet, `moduleHere` first fills both properties with a small working default (a vertex shader with a single `pos` input and a flat orange fragment shader), so the code compiles and draws something from the first run. You then edit those shaders in place.
- `Mesh()` creates the draw data: vertex buffers whose names come from the vertex shader's inputs, plus an index buffer.
- `module += m named "my mesh"` attaches the mesh to the module, so the module draws it.
- `renderer += module` adds the module to the renderer, which draws it into the window every frame. `renderer` is already there because this box is connected beneath a renderer box.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/00-traditional-triangle.png" alt="a single right triangle filling most of a native render window against black, its right angle at the bottom right. The fill is interpolated from vertex colours: deep blue at the bottom left corner, teal along the bottom edge, and green at the apex top right" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-traditional-triangle" macro-file="../../dev_docs/agents/transcripts/afield_four_traditional_triangle.yaml" capture-box="agent_four_traditional_triangle"}
// A module is one draw pipeline: this box's `vertex` and `fragment` shaders plus
// the meshes it draws. `renderer` is inherited from the renderer parent box.
var module = moduleHere {
    primitiveType = triangle_strip
}

// A Mesh holds vertex buffers whose names come from the vertex shader's `in`
// attributes (`pos`, `col`), plus the index buffer `tri`.
var m = Mesh()
module += m named "my mesh"
renderer += module

// Room for 3 vertices and 3 indices; inside, `pos`, `col` and `tri` append data.
m.set(3, 3) {
    // x, y, z for each of the three corners, in clip space (-1..1)
    pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)

    // one RGBA colour per vertex, `it` running 0, 1, 2
    3 {
        col.add(0, it / 3.0, 1 - it / 3.0, 1)
    }
    tri.add(0, 1, 2)
}
```

The `m.set(3, 3)` call says: allocate three vertices and three indices. Inside that block, `pos`, `col`, and `tri` are generated writers. `tri.add(0,1,2)` gives the triangle strip its three indices.

The vertex shader declares the attributes that produce those generated writers:
 
```diffstatic {data-lang=glsl}
#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

void main()
{
    fcol = col;
    gl_Position = vec4(pos, 1);
}
```

`layout(location = 0) in vec3 pos` is why Kotlin can compile something like `pos.add(...)`. `layout(location = 1) in vec4 col` is why Kotlin can compile `col.add(...)`. The shader copies `col` to `fcol`, and the fragment shader receives that value at the matching location:

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec4 fcol;

void main()
{
    uFragColor = vec4(1.0, 0.8, 0.4, 1.0) * fcol;
}
```

The first example is as small as it can be: vertex data is written directly into the mesh, the shader reads it as vertex attributes, and the fragment shader colors the pixels.

## Hello triangle, second attempt

The first triangle put `pos` and `col` directly into the mesh's vertex buffers. That is completely supported, and it is the closest to what you were taught if you learned OpenGL (or Three.js), but it is not the most general or flexible way to feed a shader. Sometimes you want a buffer that is visible as an ordinary named resource in the shader: perhaps the geometry changes every frame, perhaps a compute shader fills it, or perhaps several draws share it. This example uses the simplest version of that pattern: write a few positions into a `defaultBuffer`, bind that buffer to a reflected push-constant member, and draw vertices by index.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/01-buffer-triangles.png" alt="three triangles against black: a bronze triangle on the left, a magenta-to-green triangle in the middle, and a blue-grey triangle on the right with a lighter overlapping region" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-buffer-triangles" source-box="assets/canonical_graphics/af0946dd-f25d-4355-984d-f0d24a43ee12" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer {
    perFrame = true
    size = 9.vec4s
}
    .buffer

m.vertexData = { vertexData }

vertexData.write {
    put(vec(-0.8, -0.6, 0.0, 1.0))
    put(vec(-0.2, -0.6, 0.0, 1.0))
    put(vec(-0.5, 0.2, 0.0, 1.0))

    put(vec(-0.1, -0.4, 0.0, 1.0))
    put(vec(0.5, -0.4, 0.0, 1.0))
    put(vec(0.2, 0.5, 0.0, 1.0))

    put(vec(0.2, -0.8, 0.0, 1.0))
    put(vec(0.8, -0.8, 0.0, 1.0))
    put(vec(0.5, 0.0, 0.0, 1.0))
}

m.resize(9)
```

There are three moving pieces here. `defaultBuffer { ... }.buffer` allocates GPU-visible storage. `vertexData.write { ... }` fills it with nine `vec4` positions, three per triangle. `m.resize(9)` tells the draw to consume nine vertices. If this were animated, the same code would move the `write` and `resize` calls into a `frames { ... }` loop (see [Frames and the animation loop](animation_time_model.md)).

The shader side uses a Vulkan buffer reference:

```diffstatic {data-lang=glsl}
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer vertexData
{
    vec4 positions[];
};

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
    vertexData vertexData;
} pc;

layout(location=0) out vec4 localColor;

float wrap(int a, int b) {
    // Divide as floats so the ramp includes intermediate channel values.
    return float(a % b) / float(b - 1);
}

void main()
{
    gl_Position = vec4(pc.vertexData.positions[gl_VertexIndex].xyz, 1);
    localColor = vec4(
    wrap(gl_VertexIndex, 3),
    wrap(gl_VertexIndex, 5),
    wrap(gl_VertexIndex, 11),
    1.0);
}
```

The important name is `vertexData`. It appears in the push constant block, so Field reflects it into `m.vertexData`. The Kotlin line `m.vertexData = { vertexData }` binds the buffer created in Kotlin to the shader member read as `pc.vertexData`.

The fragment shader just consumes the color produced by the vertex shader:

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec4 localColor;

void main()
{
    uFragColor = vec4(localColor.rgb, 0.3);
}
```

## Camera matrices in a UBO

The previous two examples draw in clip-space coordinates. To draw from a camera, the shader needs projection and view matrices. This version stores those matrices in a uniform buffer owned by the module.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/01b-ubo-camera.png" alt="a wide black window holding a receding row of ten right triangles, small at the far left and growing steadily towards the right as they approach the camera, each stepped slightly lower than the last. Every triangle is shaded from magenta at its lower left to pale cream at its upper right, the perspective coming from the projection and view matrices the module's uniform buffer supplies" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-ubo-camera" source-box="assets/canonical_graphics/85e8478b-529b-406e-b835-7c00912848f2" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += module

val camera = Camera()
camera.at = vec(-8, 2, 7)

module.view.m = camera.m()
module.view.p = camera.p()

for (n in 1..80) {
    m.set(3 * n, 1 * n) {
        n {
            val z = 0.0 + it
            pos.add(0, 0, -1 + z, 1.0, 0.1, z, 1, 1, z)
            3 {
                col.add(10 - it * 0, it / 3.0, 1 - it / 3.0, 1)
            }
            tri.add(0 + 3 * it, 1 + 3 * it, 2 + 3 * it)
        }
    }
}
```

The shader declares a UBO named `view`:

```diffstatic {data-lang=glsl}
#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

layout (set=0, binding = 0) uniform view {
    mat4 p;
    mat4 m;
} cam;

void main()
{
    gl_Position = cam.p * cam.m * vec4(pos, 1);
    fcol = col;
}
```

Because the uniform block is named `view`, the Kotlin side writes `module.view.p` and `module.view.m`. The module owns this resource because the block is declared at descriptor set/binding scope, not as a per-mesh push constant.

`moduleHere()` generates a Kotlin class per uniform block, named by capitalising the block's name. When that name is already a type the box imports, the class gets `Block` on the end instead: a block called `camera` generates `CameraBlock`, so `Camera()` is still Field's camera.

## Camera as push constants

For quick sketches, it is often simpler for the mesh to carry the camera matrices as push constants. The draw data still lives in `m`, but now `m.p` and `m.m` are assigned directly before the draw.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/02-camera-matrices.png" alt="the same receding row of ten right triangles growing from far left to near right across a black window, here in flat pale cream with no vertex-colour gradient. The camera framing is identical to the uniform-buffer version because only where the matrices are stored has changed" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-camera-matrices" source-box="assets/canonical_graphics/b2ba23e8-18e5-4f07-a709-2b89116a83b3" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
camera.at = vec(-8, 2, 7)

m.p = camera.p
m.m = camera.m

for (n in 1..80) {
    m.set(3 * n, 1 * n) {
        n {
            val z = 0.0 + it
            pos.add(0, 0, -1 + z, 1.0, 0.1, z, 1, 1, z)
            3 {
                col.add(0, it / 3.0, 1 - it / 3.0, 1)
            }
            tri.add(0 + 3 * it, 1 + 3 * it, 2 + 3 * it)
        }
    }
}
```

The GLSL is almost identical to the UBO version, but the matrices are now push constants:

```diffstatic {data-lang=glsl}
#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
} pc;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);
    fcol = col;
}
```

That is why the Kotlin changed from `module.camera.p = ...` to `m.p = ...`. `p` and `m` are reflected onto the mesh because the shader reads them from the per-draw push constant block.

## Keyboard camera

[`KeyCamera`](reference/graphics.md#keycamera-kt-keycamera) does not change the shader contract. It wraps a `Camera` and updates it from keyboard input while the renderer runs.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/02-keyboard-camera.png" alt="a small square window holding one pale cream right triangle, its vertical edge at the left and its right angle at the bottom left, seen at whatever angle the keyboard camera had reached when the frame was captured" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-keyboard-camera" source-box="assets/canonical_graphics/954d64d5-ee3f-4cb7-8ac7-1e2cce8a9073" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var module = moduleHere()
var m = Mesh()

module += m named "mainMesh"
renderer += module

val camera = Camera()
// The quad runs from (0, 0) to (1, 1): aim at its centre, close enough that it fills the window.
camera.at = vec(0.5, 0.5, -0.8)
camera.target = vec(0.5, 0.5, 0)
m.p = camera.p
m.m = camera.m

m.set(3, 1) {
    val z = 0.0
    pos.add(0, 0, 0 + z, 1.0, 0.1, z, 1, 1, z)
    tri.add(0, 1, 2)
}

var kc by keyCamera(camera = camera)
```

The shader is the same push-constant shape as the previous section, just without the color attribute. Camera control is ordinary Kotlin state around the same rendering contract.

With the render window focused, these keys move the camera. Movement glides to a stop after you let go, and steps are a fraction of the distance to the camera's target, so the same key moves less up close.

| Key | Moves | With Shift |
| --- | --- | --- |
| W / Up, S / Down | forward, back (the target moves too) | forward, back toward a fixed target |
| A, D | left, right | the same, with the target fixed |
| E, C | up, down | the same, with the target fixed |
| Left, Right | turn in place | orbit around the target |
| Page Up, Page Down | tilt in place | orbit up and down around the target |
| Z / `,`, X / `.` | roll | |
| `-`, `=` | narrow, widen the field of view | in finer steps |
| R | reset to where the camera started | |

Re-running the box replaces the controls rather than adding a second set, because `keyCamera` registers them under the property's name (`kc` here).

## Compute shader

Compute modules use the same box model, but instead of drawing vertices they dispatch workgroups: groups of GPU threads that run the same shader together. This example writes an input buffer, dispatches a `ComputeMesh`, reads the output buffer back every frame, and shows the first ten floats in the box's `trace` output channel. Nothing is drawn to the graphics window; the trace is the result:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/03-compute-trace.png" alt="the box's trace output channel holding one line, the frame counter followed by the first ten floats of the output buffer: 3.0 13.0 26.0 39.0 56.0 44.0 81.0 95.0 109.0 75.0" capture="box" graphics-context=true execution="launch" open-outputs="true" wait-output="true" wait-output-kind="display" wait="2" w="400" h="60" x="24" y="180" timeout="120" embed=true name="four-compute-trace" source-box="assets/canonical_graphics/8b8d3306-5c31-49aa-ab27-659a992ebd09" source-box-properties="compute" source-box-wait="0.1"}
var numVec4 = 16

var module = computeModuleHere {
    ssboSizer = { ubo ->
        numVec4 * 4 * 4
    }
}

var m = ComputeMesh()
var localSize = 16
// Include a partial final group; the shader guards both buffer lengths.
m.x = (numVec4 + localSize - 1) / localSize
m.y = 1
m.z = 1

module += "mainInvocation" to m
renderer += "runCompute" to module

module.InputBuffer.mapAndWrite {
    this.clear()
    for (n in 0 until numVec4) {
        this.add(n.toFloat() * 13.0)
    }
    this.clear()
}

var fr = 0
frames {
    while (true) {
        module.OutputBuffer.mapAndRead {
            var s = "${fr}:: "
            this.clear()
            for (n in 0 until 10) s += this.get(n).toString() + " "
            display("trace", s)
        }

        fr++
        frame()
    }
}
```

The compute shader declares its workgroup size and two storage buffers:

```diffstatic {data-lang=glsl}
#version 450
layout(local_size_x = 16, local_size_y = 1, local_size_z = 1) in;

layout(set=0, binding = 0) writeonly buffer OutputBuffer {
    vec4 outData[];
};

layout(set=0, binding = 1) readonly buffer InputBuffer {
    vec4 inData[];
};


void main() {
    uint idx = gl_GlobalInvocationID.x;
    // Array lengths reflect the actual SSBO allocation, including edited element counts.
    if (idx >= uint(min(inData.length(), outData.length()))) return;
    outData[idx] = inData[idx] * vec4(1,1,1,1) +
    vec4(3 + idx * 1, -(idx * 21.0), idx * 3, idx * 4);
}
```

`layout(local_size_x = 16)` matches the Kotlin `localSize`. `m.x`, `m.y`, and `m.z` set the dispatch dimensions, counted in workgroups. Here `numVec4` is 16 and `localSize` is 16, so `m.x = (16 + 16 - 1) / 16 = 1`: one workgroup of 16 × 1 × 1 threads, 16 invocations in all, one per `vec4`. With `numVec4 = 20` it would be two workgroups, 32 invocations, and the `idx >=` guard in the shader stops the 12 that have no element. The storage-buffer names become `module.InputBuffer` and `module.OutputBuffer`, which is why Kotlin can map and read/write them directly.

## Compute particles, then draw them

The compute example above dispatched a shader and read the result back on the CPU. The more useful pattern keeps the data on the GPU: one box computes into a buffer, a *second* box draws from that same buffer, and the two are wired together through the box graph rather than through a shared global.

This is the first example that needs two boxes, so it is also the first place the graph topology matters: the draw box is [connected beneath](tour_connecting_boxes.md) the compute box, which publishes its output buffer as a [property](reactive_properties.md):

```diffstatic {data-lang=kotlin}
fieldProbeHere(1000).display("probe")
displayOpen("probe")

var module = computeModuleHere {}
var m = ComputeMesh()

module += "mainMesh" to m
renderer += "module" to module

// N changes buffer allocation. Stop the draw child first; rerun compute, then draw.
val N = 100000
var inData = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer

m.inData = { inData }
m.outData = { outData }

var x = 0.0
m.something = {
    x += 0.01
    x.f
}

prop<FrameBufferGroup>(outputPositions) = outData
prop<Int>(particleCount) = N

// uint push members accept Int lambdas; the shader checks the tail group.
m.count = { N }

m.x = (N + 63) / 64
m.y = 1
m.z = 1

inData.write {
    for (x in 0 until N) {
        put(vec(Math.random(), Math.random(), Math.random(), Math.random()))
    }
}
```

The two `prop<…>` lines are the publication: `outputPositions` is the buffer and `particleCount` is how many particles it holds, and any box beneath this one inherits both. The dispatch is `(N + 63) / 64` groups of 64, rounded up so no particle is left out; the extra lanes in the last group are why `m.count = { N }` passes the count to the shader, which returns early for any index at or past it. Because `N` sets the buffer sizes, changing it is a rebuild rather than a live edit: stop the draw child first, so it is not reading a buffer that is about to be replaced, then rerun this box and then the draw box. `m.something = { ... }` is a reflected push-constant member fed by a Kotlin lambda re-evaluated every dispatch, which is how the animation clock reaches the shader.

The compute shader takes both buffers as buffer references through its push-constant block, and calls `fieldProbe` to sample a value for the probe display:

```diffstatic {data-lang=glsl}
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer inData {
    vec4 a[];
};
layout(std430, buffer_reference) buffer outData {
    vec4 a[];
};

{% property "fieldProbe" %}

layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
    uint count;
} pc;

void main() {
    uint idx = gl_GlobalInvocationID.x;
    // Ceiling dispatch includes extra lanes; none may access the particle buffers.
    if (idx >= pc.count) return;
    pc.outData.a[idx].x = pc.inData.a[idx].x*cos(pc.something*pc.inData.a[idx].z) + pc.inData.a[idx].y*sin(pc.something*pc.inData.a[idx].w);
    pc.outData.a[idx].y = -pc.inData.a[idx].x*sin(pc.something*pc.inData.a[idx].w) + pc.inData.a[idx].y*cos(pc.something*pc.inData.a[idx].z*0.03);

    fieldProbe(pc.outData.a[idx].y);

    pc.outData.a[idx].zw = pc.inData.a[idx].zw;
}
```

`{% property "fieldProbe" %}` is Field's shader templating pulling in the probe preamble; see [Field probes](field_probes.md). Templating in general is [Shader templating](#shader-templating) below.

The draw box is a **child of the compute box**, and that is the only reason `outputPositions` resolves:

```diffstatic {data-lang=kotlin}
var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()

module += "mainMesh" to m
renderer += "boo2" to module

m.inData = { outputPositions }

// Inherit the count from the same parent that owns the buffers.
val N = particleCount

m.aspectRatio = { window.width / window.height.toFloat() }
m.set(N * 2 * 3, 0) {}

m
```

`m.inData = { outputPositions }` reads the parent's published property by name and binds it to this shader's `inData` buffer reference, and `particleCount` comes from the same parent, so the draw always sizes itself to the buffer it reads. No buffer is copied and nothing round-trips through the CPU: both boxes point at the same GPU allocation. `m.set(N*2*3, 0) {}` allocates six vertices per particle with an empty writer block, because the vertex shader synthesizes each quad's corners from `gl_VertexIndex` instead of reading vertex attributes:

```diffstatic {data-lang=glsl}
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer inData {
    vec4 pos[];
};

layout(location = 0) out vec2 tc;

layout(push_constant) uniform push {
    inData inData;
    float aspectRatio;
} pc;

const vec3[6] part = {
    vec3(0,0,0), vec3(1,0,0), vec3(1,1,0),
    vec3(0,0,0), vec3(1,1,0), vec3(0,1,0)
};

void main()
{
    float scale = 1.0 * pc.inData.pos[gl_VertexIndex/6].w;
    vec3 p = (part[gl_VertexIndex%6]-vec3(0.5, 0.5, 0))*scale;

    p.xy += pc.inData.pos[gl_VertexIndex/6].xy*10.0;
    p.xy *= vec2(1/pc.aspectRatio, 1);
    gl_Position = vec4(p*0.04, 1);

    tc = part[gl_VertexIndex%6].xy;
}
```

`gl_VertexIndex / 6` picks the particle and `gl_VertexIndex % 6` picks the corner: 100,000 particles drawn as 600,000 vertices with no index buffer and no per-particle CPU work.

Here are the three boxes on the canvas, renderer above compute above draw, each joined to the one beneath it:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x720" clip="0,0,420,500" screenshot="assets/afield_four_graphics/03b-particles-boxes.png" transcript="dev_docs/agents/transcripts/threebox.yaml" capture-box="tb_renderer" padding="0" wait="2" timeout="240" embed=true hidden=true name="four-particles-boxes" alt="three connected boxes on the canvas: renderer at the top, compute particles beneath it, draw particles beneath that, joined by arrows"}
// Only the boxes and their connections: the output panels are hidden for this picture.
ui.eval(
    """
    const style = document.createElement("style")
    style.textContent = ".disclosure-group { display: none !important; }"
    document.head.appendChild(style)
    return true""".trimIndent(),
)
ui.pause(800)
```

and what the draw box puts in the window: 100,000 particles drawn straight from the compute box's buffer.

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="tb_renderer" transcript="dev_docs/agents/transcripts/threebox.yaml" screenshot="assets/afield_four_graphics/03b-particles.png" wait="6" timeout="240" native-wait-frames="4" trim-native=true embed=true hidden=true name="four-particles" alt="100,000 particles drawn as small rings in a native Vulkan window, a bright diagonal cloud dense at the centre and thinning towards its ragged edges"}
// The transcript builds renderer, compute and draw boxes, connects them and launches each in turn.
```

To see it running, open the corpus document in the viewer above, or open [threebox](offline.html?file=assets/agent_transcripts/threebox/metadata.edn), the same renderer/compute/draw shape built as a runnable three-box scene.

This compute shader reads the same input every frame. For particles whose velocity carries over from one frame to the next, see [GPU State](gpu_state.md), which keeps that state on the card.

### Pushing particles around a shape

A compute pass like the one above can keep its particles off, onto or around a shape with
`#include "particles/shape_force"`, which any Field shader can include. Two functions work in the shape's local XY, measured from its centre:

- `particleShapeBoundary2D(local, shape, halfSize, radius)` returns the outward unit normal in
  `.xy` and the signed distance to the boundary in `.z`, negative inside. `shape` is
  `PARTICLE_SHAPE_CIRCLE` (uses `radius`), `PARTICLE_SHAPE_RECTANGLE` (uses `halfSize`) or
  `PARTICLE_SHAPE_SEGMENT` (uses `halfSize.x` along local X, with an unsigned distance).
- `particleShapeForce2D(boundary, band, gain, outward)` turns that into a force that fades to zero
  `band` units either side of the boundary. A positive `gain` pulls particles onto the outline and a
  negative one pushes them off it; with `outward = true` a positive gain pushes along the normal on
  both sides, which clears a shape of particles.

Inside the compute shader's `main`, after reading the particle:

```diffstatic {data-lang=glsl}
#include "particles/shape_force"

// p is this particle's position, v its velocity; the circle sits at the origin
vec3 b = particleShapeBoundary2D(p.xy, PARTICLE_SHAPE_CIRCLE, vec2(0.0), 0.5);
v.xy += particleShapeForce2D(b, 0.1, 0.02, false);
```

For a rotated or moved shape, transform the particle into the shape's frame first and rotate the
returned force back.

## Texturing

Textures add another reflected resource. The shader declares a sampler named `bbc`; Kotlin creates a `LoadedTexture` property with the same name and transfers it before drawing.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/04-texturing.png" alt="a square quad filling a small window, textured with a checkerboard of saturated red, green, blue, orange and yellow cells. Two diagonals cross it corner to corner, one white and one black, drawn into the texture itself so the sampling is legible" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-texturing" source-box="assets/canonical_graphics/55bc1784-bc44-40eb-bc01-d02eca463445" source-box-properties="vertex,fragment" source-box-wait="0.1"}
prop<LoadedTexture>(bbc) = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
// The quad runs from (0, 0) to (1, 1): aim at its centre, close enough that it fills the window.
camera.at = vec(0.5, 0.5, -0.8)
camera.target = vec(0.5, 0.5, 0)
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
m.set(4, 2) {
    val z = 0.0

    pos.add(0, 0, 0 + z, 1.0, 0.1, z, 1, 1, z, 0, 1, z)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)

    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
```

The vertex shader now has two attributes: position and texture coordinate.

```diffstatic {data-lang=glsl}
#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec2 tc;
layout (location = 1) out vec2 ftc;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
} pc;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);
    ftc = tc;
}
```

`layout(location = 1) in vec2 tc` is why Kotlin has `tc.add(...)`. The fragment shader receives `ftc` and samples the texture:

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D bbc;

void main()
{
    vec4 c = texture(bbc, ftc.yx);
    uFragColor = vec4(c.rgb, 1.0);
}
```

The name `bbc` is the bridge. Kotlin creates `prop<LoadedTexture>(bbc)`, the shader declares `sampler2D bbc`, and Field binds the texture resource by matching that reflected name.

## Multi-texture

A shader can declare an *array* of samplers, and the Kotlin side binds it by publishing an array of image views under the array's name. The reflected-name contract is the same as the single texture above; only the type changes.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/04b-multi-texture.png" alt="one quad in thirds, each third sampling a different entry of the texture array: a blue and gold spiral on the left, an orange-to-green gradient in the middle, and the coloured checkerboard with its crossing diagonals on the right" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-multi-texture" source-box="assets/canonical_graphics/3cbcadc0-b279-470d-9a5f-afc120518829" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var bbc = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var bbc2 = LoadedTexture("public/docs/assets/rt-uv-gradient.png")
bbc2.transferNow()

var bbc3 = LoadedTexture("public/docs/assets/afield_four_graphics/spiral-texture.png")
bbc3.transferNow()

var asArray: Array<ImageView> = arrayOf(bbc.imageView, bbc2.imageView, bbc3.imageView)

prop<Array<ImageView>>(ta) = asArray

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
// The quad runs from (0, 0) to (1, 1): aim at its centre, close enough that it fills the window.
camera.at = vec(0.5, 0.5, -0.8)
camera.target = vec(0.5, 0.5, 0)
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
m.set(4, 2) {
    val z = 0.0

    // four corners of the unit square, and the texture coordinate at each
    pos.add(0, 0, z)
    pos.add(1, 0, z)
    pos.add(1, 1, z)
    pos.add(0, 1, z)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)

    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
```

`prop<Array<ImageView>>(ta)` is the whole difference. Note that `LoadedTexture` is not itself the bindable resource; `bbc.imageView` is, which is why the array is built from image views rather than from the textures. Each entry here is a different image, the checkerboard, a colour gradient and a frame of the spiral from [Graphics on the GPU](tour_gpu_graphics.md), and the fragment shader picks one for each third of the quad.

The fragment shader declares the array and indexes it:

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D ta[3];

void main()
{
    // Each third of the quad, left to right, shows one entry of the array. Every entry is sampled and the
    // result chosen afterwards, so the array index stays a constant.
    // ftc.yx swaps the texture coordinates, which transposes the image on the quad.
    vec4 c0 = texture(ta[0], ftc.yx);
    vec4 c1 = texture(ta[1], ftc.yx);
    vec4 c2 = texture(ta[2], ftc.yx);
    vec4 c = ftc.x < 1.0 / 3.0 ? c0 : (ftc.x < 2.0 / 3.0 ? c1 : c2);
    uFragColor = vec4(c.rgb, 1.0);
}
```

The declared array length (`ta[3]`) is the descriptor count, and the count must match **exactly**: publishing two views against `ta[3]` is refused at link time with `can't link texture resource ta of length 2 but the shader expects 3`, and the box draws nothing.

## Loading a glTF model

Everything so far built its geometry in Kotlin. `gltfModelHere(path)` instead reads a glTF file and hands back a module *and* the meshes it found, with shaders written to match how that file lays out its data. Here it loads the Khronos toy car the [Fabricate renderer](fabricate_renderer.md) pages use:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/04c-gltf-toycar.png" alt="the Khronos toy car, a 1950s saloon with tail fins, filling a native window seen from the front left, shaded by its view-space normals in blue, green and magenta" wait="4" timeout="120" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-gltf-toycar"}
var (module, meshes) = gltfModelHere("public/docs/assets/fabricate_demo/ToyCar.glb")

var m = Mesh()
module += "car" to m
renderer += "boo" to module

// orthoness blends between perspective (0) and orthographic (1) projection.
val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 0.4, 0)
camera.at = vec(-2.2, 1.8, 2.9)

// Keyboard controls for the camera; R resets it.
KeyCamera(camera = camera)

/*
The file stands the car up and scales it to centimetres (a 0.0001 node scale); this model matrix
keeps the rotation and makes it about four units long. meshes[model].meshes[primitive]: copy
primitive 0 of model 0 into our mesh.
*/
val model = Matrix4f().rotateX(Math.PI.toFloat() / 2).scale(0.01f)
meshes[0].meshes[0].applyToMesh(m, camera, model)
camera.p()

loop {
    frame()
}
```

The colours are the written fragment shader's: it shades by the view-space normal, so faces turned the same way share a colour.

Three things to notice. `meshes[0].meshes[0].applyToMesh(m, camera, model)` copies one primitive of one model into your mesh; the outer index is the model, the inner one the primitive, so a multi-part file is addressed rather than flattened. The `model` matrix is there because this file stands the car up and scales it to a few centimetres with its own node transform: the box keeps the rotation and makes the car about four units long, a size the camera can frame. And `camera.p.orthoness` / `camera.p.matchDepth` are Field's blend between perspective and orthographic rather than a choice between them.

`applyToMesh` also fills the shader's `modelView` (view times model) and `proj` push constants from the camera every frame, which is why the loop has nothing to do but `frame()`. [Geometry from disk](geometry_from_disk.md) has the measurement behind the composition.

The vertex shader shows what "matched to the data" means. Rather than declaring vertex attributes, it reads a flat float array through a buffer reference and unpacks by stride:

```diffstatic {data-lang=glsl}
layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView; // view * model
    mat4 proj; // projection
    vec4 materialBaseColor;
    uint vertexOffset; // in floats
    uint indexOffset; // in indices
    uint vertexStride; // in floats
    uint useVertexColor; // bool as uint
    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

void main()
{
    uint idx = pc.indexBuffer.indexData[pc.indexOffset+uint(gl_VertexIndex)];
    uint base = pc.vertexOffset + idx * pc.vertexStride;

    vec3 pos = vec3(
    pc.vertexBuffer.vertexData[base + 0],
    pc.vertexBuffer.vertexData[base + 1],
    pc.vertexBuffer.vertexData[base + 2]
    );
    // ... normal, uv and colour follow at base + 3 to base + 11
}
```

`vertexOffset`, `indexOffset` and `vertexStride` are what let every primitive in the file share one pair of buffers: the mesh selects its slice by offset instead of owning an allocation. Manual indexing (`indexData[indexOffset + gl_VertexIndex]`) rather than a bound index buffer is the same trade.

The companion lesson swaps one call for the textured variant, `gltfModelWithTextureHere(path)`, which also binds the file's images as the sampler array `uTextures` the fragment shader declares: the array-of-samplers pattern above, filled from the file rather than by hand. The array gets exactly as many images as the shader declares, so the car's ten fill its six slots with the first six:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/04d-gltf-toycar-textured.png" alt="the same toy car with its textures: red body and bumpers, a green roof and bonnet carrying orange flames, a chequered stripe and a racing number, and whitewall tyres" wait="4" timeout="120" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true hidden=true name="four-gltf-toycar-textured" source-box="assets/canonical_graphics/f1d2cbea-b20d-4794-b9f7-37b847dcdeb6" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var (module, meshes) = gltfModelWithTextureHere("public/docs/assets/fabricate_demo/ToyCar.glb")

var m = Mesh()
module += "car" to m
renderer += "boo" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 0.4, 0)
camera.at = vec(-2.2, 1.8, 2.9)
KeyCamera(camera = camera)

// The same placement as the untextured lesson: the file's rotation, scaled to about four units long.
val model = Matrix4f().rotateX(Math.PI.toFloat() / 2).scale(0.01f)
meshes[0].meshes[0].applyToMesh(m, camera, model)
camera.p()

loop {
    frame()
}
```

## Render to texture

Everything so far drew into the window. `RenderToTexture` gives a module somewhere else to draw: an offscreen image that a later box can sample. It is the building block for post-processing, feedback, and anything that needs to read what it just drew.

The producing box creates the target, registers it on the renderer's render list, and attaches its module to the *target* rather than to the renderer:

```diffstatic {data-lang=kotlin}
prop<RenderToTexture>(target) = RenderToTexture {
    clearFirst = false
    useDepth = true
}

renderer.renderList += "target" to target

var module = moduleHere {
    // Match the module's pipeline (colour format, depth) to the target it draws into.
    +target
}
var m = Mesh()

module += "mainMesh" to m
target += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m

// we can set this live and after the fact
target.parameters.clearFirst = true

var kc by keyCamera(camera = camera)
loop {
    m.set(3, 1) {
        val z = Math.random() * 0.0
        pos.add(-3, 1, -1 + z, 1.0, -20.3, z, 3, 3, z)
        tri.add(0, 1, 2)
    }
    true
}
```

Three lines carry the idea. `renderer.renderList += "target" to target` schedules the offscreen pass. `target += "boo" to module` puts the drawing inside that pass instead of on screen. And `target.parameters.clearFirst = true` shows that the target's parameters stay live: you can flip them from the editor while the loop is running, with no rebuild.

The consuming box is a **child** of the producer, and that is the whole binding mechanism:

```diffstatic {data-lang=kotlin}
prop<RenderToTexture>(bbc) = target

var module = moduleHere()
var mesh = Mesh()
module += "mainMesh" to mesh
renderer += "boo" to module

val camera = Camera()
camera.at = vec(0, 0, -1.2)
mesh.p = camera.p
mesh.m = camera.m

var kc by keyCamera(camera = camera)
mesh.set(4, 2) {
    val z = 0.0
    pos.add(-1, -1, z, 1.0, -1.0, z, 1, 1, z, -1.0, 1, z)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
```

`prop<RenderToTexture>(bbc) = target` reads `target` (the parent's property, visible by name because this box is a child) and republishes it as `bbc`, because *this* box's fragment shader declares `sampler2D bbc`. Republication can rename freely: the resource is identified by the reflected name the consuming shader asks for, not by the name it was published under upstream, so a box can bind any upstream target to whatever its own shader happens to call it.

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set=0, binding = 0) uniform sampler2D bbc;

void main()
{
    vec4 c = texture(bbc, ftc.yx);
    uFragColor = vec4(c.rgb, 1.0);
}
```

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="lesson_rtt_0" transcript="dev_docs/agents/transcripts/docs_capture/lesson_rtt.yaml" screenshot="assets/afield_four_graphics/05-rtt.png" wait="6" timeout="240" native-wait-frames="4" trim-native=true embed=true hidden=true name="four-rtt" alt="the window showing the consumer's quad, which fills it: black where the offscreen target was cleared, with the producer's one large white triangle sampled from the target as a wedge across the middle, transposed by the shader's ftc.yx"}
// The transcript builds window, 5. RTT and 5. draw rtt from the canonical_graphics sidecars.
```

Compare that with the texturing fragment shader: it is the same shader. From the consumer's side there is no difference between sampling a file loaded from disk and sampling the output of another box. Two boxes and one renamed property are the smallest complete statement of how `Field 4` composes.

[threebox](offline.html?file=assets/agent_transcripts/threebox/metadata.edn) and [spin_glass](offline.html?file=assets/agent_transcripts/spin_glass/metadata.edn), from the [agent transcripts](agent_transcripts.md), are runnable versions of the same producer/consumer shape.
[Shape-aware ASCII](ascii_shape.md#on-the-gpu-over-a-live-scene) chains two targets this way: a
scene drawn offscreen, then a pass with one pixel per character cell that picks a glyph for each.

## Ping-pong render to texture

A target you can read *and* write in the same pass is a contradiction, so feedback needs two images with the roles swapped every frame. `PingPongRenderToTexture` does that bookkeeping:

```diffstatic {data-lang=kotlin}
import org.lwjgl.vulkan.VK10.*

prop<PingPongRenderToTexture>(target) = PingPongRenderToTexture {
    clearFirst = false
    useDepth = false
}

prop<HasImageView>(otherView) = target.otherImageView

renderer.renderList += "target" to target

var module = moduleHere {
    +target
}
var m = Mesh()

module += "mainMesh" to m
target += "boo" to module
```

`target.otherImageView` is the image that is *not* the current render destination, published as `otherView` so the fragment shader can sample last frame while writing this one:

```diffstatic {data-lang=glsl}
layout(set=0, binding = 0) uniform sampler2D otherView;

void main()
{
    vec2 tc = gl_FragCoord.xy / 1024.0;
    vec2 px = vec2(1.0 / 1024.0);

    // Last frame, blurred hard and faded a little.
    vec3 c = vec3(0);
    float total = 0.0;
    for (int y = -1; y <= 1; y++)
    for (int x = -1; x <= 1; x++) {
        float w = (x == 0 && y == 0) ? 4.0 : ((x == 0 || y == 0) ? 2.0 : 1.0);
        c += w * texture(otherView, tc + 5.0 * px * vec2(x, y)).rgb;
        total += w;
    }
    c = c / total * 0.996;

    // A dot on a Lissajous path, its colour cycling, stamped in every frame.
    vec2 at = vec2(0.5) + 0.32 * vec2(cos(pc.time * 0.9), sin(pc.time * 1.3));
    vec3 ink = 0.5 + 0.5 * cos(pc.time + vec3(0, 2, 4));
    c = max(c, ink * smoothstep(0.025, 0.0, length(tc - at)));

    uFragColor = vec4(c, 1.0);
}
```

Each frame reads the last one blurred and slightly darker, then stamps the dot in at its new place, so the dot leaves a trail that spreads and fades rather than a line. `pc.time` advances a fixed 1/60 s per frame (`m.time = { time += 1f / 60; time }`), not with the clock, so a slow frame slows the animation instead of making the dot jump. The consuming box then displays `target.thisImageView`, the half that was just written:

```diffstatic {data-lang=kotlin}
prop<HasImageView>(bbc) = target.thisImageView
```

Note the property type here is `HasImageView`, not `RenderToTexture`. A ping-pong target is not one image, so the two halves are handed out individually, and `HasImageView` is the interface that any bindable image satisfies. `module { +target }` inside the module builder is what matches the module's pipeline state (format, sample count, attachment layout) to the target it draws into; without it the pipeline and the target disagree.

And the feedback running in the window: a dot moving through the target leaves a trail that blurs and fades, because each frame reads the last.

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="lesson_pingpong_0" transcript="dev_docs/agents/transcripts/docs_capture/lesson_pingpong.yaml" video="assets/afield_four_graphics/05-pingpong.mp4" duration="8" wait="1" timeout="240" embed=true hidden=true name="four-pingpong" alt="a dot moving on a looping path through the ping-pong target, its colour cycling, leaving a trail that blurs outward and fades as each frame reads and rewrites the last"}
// The transcript builds window, 5. PingPongTT and 5. draw pingpoing from their sidecars and launches each.
```

### Replacing carried history at a playback cut

For a prepared cut, `target.recordReplaceHistory(command, attachment) { image -> … }`
records a transfer into every slot of one attachment's history ring. The callback records a
buffer-to-image or image-to-image copy into the supplied Vulkan image, in
`TRANSFER_DST_OPTIMAL`, covering mip 0 and every array layer. Its source must be separate from
the ring. Create the target with `colorTransferDst = true`, `colorSampled = true` and one mip
level, and call outside a render pass on the graphics recording thread.

Replacing all slots prevents an earlier frame from reappearing in feedback after the cut.
The other attachments, ring indices, bound attachment and cadence stay as they were. This
works on both `PingPongRenderToTexture` and `FrameCadencedPingPongRenderToTexture`.

`renderer.whenCurrentGraphicsFrameComplete { … }`, called while recording that graphics
frame, reports completion after its submission fence signals. Keep the callback short: complete
a future or hand work to an IO worker. The transfer method records commands without waiting;
prepare source resources before the cut. Neither method loads a snapshot from disk or captures
the rest of an application's simulation state.

## A blur kernel across three boxes

The blur chain is the longest topology in the corpus (`window → 5. Bluring → 5. kernel → 5. draw blur`), and it reads best as a whole, because each box does exactly one thing.

`5. Bluring` is a plain `RenderToTexture` producer like the one above, drawing a checkerboard so there is something with hard edges to blur:

```diffstatic {data-lang=glsl}
void main()
{
    uFragColor = vec4(int(gl_FragCoord.x/32) % 2,int(gl_FragCoord.y/32) % 2,0,1);
}
```

`5. kernel` reads that target as `b0`, and renders into a *second* target of its own with two colour attachments and mip levels:

```diffstatic {data-lang=kotlin}
prop<RenderToTexture>(b0) = target

prop<RenderToTexture>(b1) = RenderToTexture {
    useDepth = false
    mipLevels = 10
    colorLayerCount = 2
    colorAttachments = 2
}

renderer.renderList += "b1" to b1

var module = moduleHere {
    primitiveType = triangle_strip
    depth = 0
    +b1
}

var m = Mesh()
module += "mainMesh" to m
b1 += "boo" to module

m.radius = { 10.0f }
m.sigma = { 10.0f }
m.resize(4)
```

`m.resize(4)` with no `set` block and `primitiveType = triangle_strip` is the fullscreen-quad idiom: four vertices, positions synthesized in the vertex shader from `gl_VertexIndex`, no vertex attributes at all. `m.radius` and `m.sigma` are reflected push-constant members, so the kernel's parameters are ordinary Kotlin lambdas you can edit live.

The interesting choice is in the vertex shader, which computes the Gaussian weights *per vertex* (four invocations instead of one per pixel) and passes them down as interpolants:

```diffstatic {data-lang=glsl}
float gauss(float x, float sigma) {
    float s2 = sigma * sigma;
    return exp(-x * x / (2.0 * s2));
}

void main()
{
    float r1 = blur.radius * 0.275; // near
    float r2 = blur.radius * 0.65; // mid
    float r3 = blur.radius * 0.95; // far

    float w0 = gauss(0.0, blur.sigma);
    float w1 = gauss(r1, blur.sigma);
    float w2 = gauss(r2, blur.sigma);
    float w3 = gauss(r3, blur.sigma);
    float norm = w0 + 2.0 * (w1 + w2 + w3);

    radii = vec4(r1,r2,r3,norm);
    weights = vec4(w0,w1,w2,w3);

    vec2 p = positions[gl_VertexIndex];
    gl_Position = vec4(p, 0.0, 1.0);
    ftc = p * 0.5 + 0.5;

    gl_Layer = 1;
}
```

The weights are constant across the quad, so interpolating them is free and the four `exp` calls replace one per pixel. `gl_Layer = 1` (with `GL_ARB_shader_viewport_layer_array`) picks which layer of the multi-layer target this pass writes.

The fragment shader is then a seven-tap symmetric blur that writes two attachments at once:

```diffstatic {data-lang=glsl}
void main()
{
    vec2 texel = 1.0 / vec2(textureSize(b0, 0));
    vec2 dx1 = texel * vec2(radii.x, 0.0);
    vec2 dx2 = texel * vec2(radii.y, 0.0);
    vec2 dx3 = texel * vec2(radii.z, 0.0);

    vec4 c =
    weights.x * texture(b0, ftc)
    + weights.y * (texture(b0, ftc + dx1) + texture(b0, ftc - dx1))
    + weights.z * (texture(b0, ftc + dx2) + texture(b0, ftc - dx2))
    + weights.w * (texture(b0, ftc + dx3) + texture(b0, ftc - dx3));

    uFragColor = vec4(c.rgb / radii.w, 1.0);
    uFragColor1 = vec4(c.bgr / radii.w, 1.0);
}
```

Two `layout(location = …) out` declarations correspond to the `colorAttachments = 2` on the Kotlin side. Finally `5. draw blur` picks a specific layer out of the array to display:

```diffstatic {data-lang=kotlin}
prop<RenderToTexture>(bbc) = b1
val other = b1.imageViewArray[1]
prop<HasImageView>(back) = other
```

and samples it as a `sampler2DArray`, the type a layered target reflects as:

```diffstatic {data-lang=glsl}
layout(set=0, binding = 0) uniform sampler2DArray bbc;
layout(set=0, binding = 1) uniform sampler2DArray back;

void main()
{
    vec4 c = texture(back, vec3(ftc.yx,1.0), 0.0);
    uFragColor = vec4(c.rgb, 1.0);
}
```

Here is the chain on the canvas:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x900" clip="0,0,420,700" screenshot="assets/afield_four_graphics/05-blur-boxes.png" transcript="dev_docs/agents/transcripts/docs_capture/lesson_blur.yaml" capture-box="lesson_blur_0" padding="0" wait="2" timeout="240" embed=true hidden=true name="four-blur-boxes" alt="four connected boxes on the canvas, stacked: window, 5. Bluring, 5. kernel and 5. draw blur, each joined to the one beneath it by an arrow"}
// Only the boxes and their connections: the output panels are hidden for this picture.
ui.eval(
    """
    const style = document.createElement("style")
    style.textContent = ".disclosure-group { display: none !important; }"
    document.head.appendChild(style)
    return true""".trimIndent(),
)
ui.pause(800)
```

and what the last box draws:

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="lesson_blur_0" transcript="dev_docs/agents/transcripts/docs_capture/lesson_blur.yaml" screenshot="assets/afield_four_graphics/05-blur.png" wait="6" timeout="240" native-wait-frames="4" trim-native=true embed=true hidden=true name="four-blur" alt="the checkerboard the first box draws, after the kernel box: a grid of blue, cyan and green squares whose hard edges are visibly softened"}
// The transcript builds window, 5. Bluring, 5. kernel and 5. draw blur from their sidecars and launches each.
```

[Aperture playground](aperture_playground.md) builds on this chain: its render passes spread light
into defocus through aperture-shaped filters, with every shader stage in a box of its own.

## Shader includes

Shader properties are templated before they reach the compiler, and `{% property "name" %}` pulls in another property by name. `5. included` shares its attribute and push-constant declarations with a sibling box:

```diffstatic {data-lang=glsl}
#version 450

{% property "vertex_included" %}

layout (location = 0) out vec4 fcol;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);

    fcol = col;
}
```

where the included property is a fragment of GLSL with no `main` and no `#version` of its own:

```diffstatic {data-lang=glsl}
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;
layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
} pc;
```

Reflection happens *after* the include is expanded, so `pos`, `col`, `p` and `m` still produce the generated Kotlin writers: a shared header does not cost you the reflected API. The include resolves against a box that has to exist alongside the one including it, so this pair only compiles once both boxes are in the document.

`5. lygia` shows the same mechanism reaching a third-party shader library. `registerLygiaIncludeRoot()` in Kotlin registers [Lygia](https://lygia.xyz)'s source tree as an include root, after which the shader uses ordinary `#include`:

```diffstatic {data-lang=glsl}
#include "lygia/generative/gerstnerWave.glsl"
#include "lygia/generative/fbm.glsl"

void main()
{
    vec2 uv = ftc.xy * 1.0;
    vec3 f = gerstnerWave(uv, vec2(1.0, 1.2), 10.3, 0.3, 0.3);

    float n = fbm(uv*2 + f.yx * 0.75);
    float n2 = fbm(uv * 2.0 -
    f.yx * 0.5 +
    vec2(-f.y, f.x)*n*9);

    vec3 col = abs(f) * (0.6 + 0.8 * n) + vec3(n2 * 0.25, n * 0.2, n * 0.35);
    uFragColor = vec4(col, 1.0);
}
```

This example needs a Lygia checkout on disk, which is why it carries no figure.

## Shader templating

Beyond includes, a shader property is a template with values supplied from Kotlin. `shaderTemplate["col"]` publishes a map of `Resolver`s, and the shader substitutes them with `{{ … }}`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/05b-templating.png" alt="one right triangle filling a black window, its right angle at the bottom right, in flat saturated yellow with no gradient. The colour is not written into the shader: it is the value Kotlin substituted into the template slot" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-templating" source-box="assets/canonical_graphics/1103f132-7484-4440-8102-5bd159a29fbf" source-box-properties="vertex,fragment" source-box-wait="0.1"}
// we can define this first
shaderTemplate["col"] = mapOf("fixedColor" to Resolver { "vec4(1,0.75,0,1)" })

// Run the declarations above first; rerun the module fragment to compile its generated shader interface.
//%-
var module = moduleHere {
    primitiveType = triangle_strip
}

var m = Mesh()

module += m named "my mesh"
renderer += module

m.set(3, 3) {
    pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)
    tri.add(0, 1, 2)
}
```

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;

layout(location = 0) in vec4 fcol;
void main()
{
    uFragColor = vec4({{ col.fixedColor }});
}
```

A `Resolver` is a lambda returning GLSL *source*, not a value: the string is spliced in before compilation, so `{{ col.fixedColor }}` becomes a compile-time constant rather than a uniform read. That is the trade: changing it recompiles the shader, and in exchange the optimiser sees a literal.

In the corpus this box also carries a `//%-` line, which is Field's execution break: everything above it runs once when the box is first evaluated, and the part below is what re-runs. Declaring the template before the break means the substitution is registered before `moduleHere` reads the shader.

## Generated push-constant blocks

Everything above runs in one direction: you write the GLSL, and Field generates the Kotlin interface to it from what the shader declares. Push-constant blocks can run the other way. You write a Kotlin class, and Field generates the GLSL. `PushConstants<T>` reflects a Kotlin class into a GLSL push-constant declaration:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/05c-push-constants.png" alt="one right triangle filling a black window in flat dark crimson, the same geometry as the templating figure with a different colour. The value arrives through a push-constant block GLSL never declared by hand, reflected out of a Kotlin class" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-push-constants" source-box="assets/canonical_graphics/42056e8d-ec8b-4bf8-b84a-1cd1d8524510" source-box-properties="vertex,fragment" source-box-wait="0.1"}
class MyPC(
    var a: Vec4,
    var b: Vec4,
    var c: Vec4,
) : PushConstants<MyPC>()

var pc = MyPC(vec(1, 0, 0, 1), vec(1, 2, 3, 4), vec(1, 2, 3, 4))

shaderTemplate["pc"] = mapOf("dec" to Resolver { pc.glsl() })

pc.a.x = 0.3f
pc.otherDeclarations = "layout(offset=${pc.nextFreeOffset(16)}) uint foo;"

// Run the declarations above first; rerun the module fragment to compile its generated shader interface.
//%-
var module = moduleHere {
    primitiveType = triangle_strip
}

var m = Mesh()

module += m named "my mesh"
renderer += module

m.push.bindAll { pc }
m.foo = { 10u }

m.set(3, 3) {
    pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)
    tri.add(0, 1, 2)
}
```

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec4 fcol;

{{ pc.dec }}
void main()
{
    loadRegs();

    uFragColor = vec4(a());
}
```

`pc.glsl()` emits the block declaration; `{{ pc.dec }}` splices it in; `m.push.bindAll { pc }` binds the whole Kotlin object to it in one call rather than member by member; and `loadRegs()` plus the accessor `a()` are part of the generated preamble, which is why the shader reads `a()` rather than `pc.a`. `nextFreeOffset(16)` computes where a hand-written extra member can sit without colliding with the generated layout, the escape hatch for the case the generator does not cover.

[GPU schemas](gpu_schema.md) take the same direction further: one annotated data class becomes a layout that can sit in push constants, a storage buffer, or any buffer a shader reaches through a pointer.

## Debugging: printf from a shader

Vulkan can print from inside a shader. Enable the extension, guard the call so it does not fire for every invocation, and the message arrives through the validation layer:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/06-printf.png" alt="one bronze-shaded right triangle against black, with its right angle at the top left; this section also demonstrates guarded vertex debug output" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-printf" source-box="assets/canonical_graphics/81f27655-7d32-4a4d-b73d-1b5d368efaa7" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var module = moduleHere { primitiveType = triangles }

var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}
    .buffer

m.vertexData = { vertexData }

vertexData.write {
    put(vec(0, 0, 0))
    put(vec(1, 0, 0))
    put(vec(0, 1, 0))
}

m.resize(3)
```

```diffstatic {data-lang=glsl}
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_debug_printf : enable

// …

void main()
{
    vec3 pos = pc.vertexData.positions[gl_VertexIndex].xyz;
    gl_Position = vec4(pos, 1);

    localColor = vec4(wrap(gl_VertexIndex,3),wrap(gl_VertexIndex,5),wrap(gl_VertexIndex,11),1.0);

    if (gl_VertexIndex == 0) {
        debugPrintfEXT("VS: index=%d, pos=(%f,%f)",gl_VertexIndex, pos.x, pos.y);
    }
}
```

Two things are required. `#extension GL_EXT_debug_printf : enable` has to be there, and printing needs the validation layer (`Options.validate`, on by default, or `Options.shaderDebugPrintf`); a shader that calls `debugPrintfEXT` with both off gets a warning in the log, since it would print nothing. And the guard matters: `debugPrintfEXT` with no condition in a shader running over a million invocations will flood the log and stall the frame. The guard here (`gl_VertexIndex == 0`) prints once per draw, for the first vertex.

## Debugging: texture probes

The other debugging tool goes the other way: instead of printing text, it samples a value out of a running shader and draws it. `fieldProbeHere()` creates the probe and `{% property "fieldProbe" %}` brings the shader side in:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/06-texture-probe.png" alt="the probe card in the box's output area: 600 calls, all finite and in range, then a vec3 table of each post-projection component. x runs from -0.4482 to 0.4482 and y from -0.7301 to 0.7301, both centred on zero, while z sits between 0.9988 and 1.0000, so every vertex lands at the far end of the depth range" capture="box" graphics-context=true open-outputs="true" wait-output="true" wait-output-kind="display" wait="2" w="400" h="60" x="24" y="180" timeout="120" embed=true name="four-texture-probe" source-box="assets/canonical_graphics/e1d6dd17-1ebd-40e7-a0ef-309779c562ff" source-box-properties="vertex,fragment" source-box-wait="0.1"}
prop<LoadedTexture>(bbc) = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

val probe = fieldProbeHere().display("probe")

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
camera.at = vec(0.5, 0.5, -0.8)
camera.target = vec(0.5, 0.5, 0)
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
val n = 100
m.set(4 * n, 2 * n) {
    n {
        val z = it + 0.0

        pos.add(0, 0, 0 + z, 1.0, 0.1, z, 1, 1, z, 0, 1, z)
        tc.add(0, 0, 1, 0, 1, 1, 0, 1)

        val b = it * 4
        tri.add(b + 0, b + 1, b + 2)
        tri.add(b + 0, b + 2, b + 3)
    }
}
```

```diffstatic {data-lang=glsl}
#version 450

{% property "fieldProbe" %}

// …

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);

    fieldProbe(gl_Position.xyz/gl_Position.w);

    ftc = tc;
}
```

`fieldProbe(…)` writes the sampled value into a buffer the probe owns, and `.display("probe")` puts the readback in the box's output area. Probing the post-projection position, as here, is how you check a transform is doing what you think without reasoning backwards from the picture. `n = 100` stacked quads make it worth probing: 400 vertices where a wrong `p` or `m` is otherwise just a blank window. See [Field probes](field_probes.md) for the readback side.

A second probe lesson (`6b. debugging / mapping - Texture probe example`) goes further and warps the sampled distribution before display (`AdaptiveQuantileWarp` and `QuantileWarpConfig`). Reach for that when the interesting variation is buried in a narrow part of the range and a linear readout flattens it.

In the Vulkan window the 100 quads stack along z, so from the camera they land on top of one another and look like one. Everything this example is for is in the box's output area.

## Multi-pass starting points

Open [Bloom across several resolutions](agent_transcripts.md#four_pass_chain) and follow the
image from the half- and quarter-resolution targets through the two aperture blurs to the
composite. The [variable-width stroke replay](agent_transcripts.md#variable_width_strokes_gpu)
uses an existing renderer to draw paths with width specified along the stroke.

[Fluid and jump-flood simulations](agent_transcripts.md#four_simulation_chain) use
the same pass-chain API for a fluid with state that survives each frame and a distance field
that is rebuilt when the shape moves.

The [raster summary pyramid](agent_transcripts.md#four_summary_pyramid) replaces colour with
mergeable moment sums. The [Morton quadtree](agent_transcripts.md#four_morton_quadtree) feeds
the same reduction from sorted points. Compare the centroid and covariance ellipse of a tile
in each document to see what carries across the two representations.

The [texture wall transcript](agent_transcripts.md#mega_texture_wall) uses feedback in the
opposite direction. The shader estimates the mip level it needs on screen, and the CPU uses
that demand to decide which tiles to load. Connect the wall to a renderer and point it at
your own image directory.

## Streaming a target over the network

`FieldRTTStreamer` turns a render target into an encoded video stream, which is how a Field sketch drives a display it is not attached to. A render can also be streamed over WebRTC to other Field instances and to browser ShaderTrinkets ([WebRTC Streams](webrtc.md)), or shown in Field's own desktop app ([graphics in output areas](four_in_output_areas.md)). The largest consumer of `FieldRTTStreamer` is the Vision Pro front end; see [Apple Vision Pro](vision.md), which covers the stereo side-by-side layout, the media wire's pose tail, and `tagPosesFrom` for stamping a frame with the head pose it was rendered from:

```diffstatic {data-lang=kotlin}
import afield.media.stream.FieldRTTStreamer
import org.lwjgl.vulkan.VK10.*

// Streamer allocates + imports the surface and hands back the RTT to render into.

prop<FieldRTTStreamer>(streamer)
once.streamer {
    FieldRTTStreamer(renderer, dest = "127.0.0.1:9001", width = 1280, height = 720, fps = 15)
}

prop<RenderToTexture>(target) = streamer.start().first()

renderer.renderList += "target" to target

var module = moduleHere { +target }
var m = Mesh()
module += "mainMesh" to m
target += "boo" to module
```

The shape is exactly the `RenderToTexture` producer from earlier (`renderList +=`, `target +=`, `+target`) with the target supplied by the streamer instead of constructed directly. `once.streamer { … }` is the live-coding guard: re-running the box re-runs everything except the block that allocated the stream, so you can edit the drawing without tearing down the connection. `prop<RenderToTexture>(target) = …` publishes the target as a typed property, so child boxes inherit it as `target` the same way the particle draw box inherits `outputPositions`.

The picture this produces arrives at a receiver on `127.0.0.1:9001` rather than in a window.

## A GPU radix sort

Not every compute job is a shader you write. `GpuRadixSort` is a prepackaged one, and this example is about how it schedules work rather than about the sort itself:

```diffstatic {data-lang=kotlin}
val sorter = GpuRadixSort()

var sourceBuffer = buffer {
    size = 200.ints
    storage()
    addressable()
    hosted()
    dest()
}
    .buffer

sourceBuffer.write {
    200 {
        putInt(2000 - it)
    }
}

renderer.renderList.others["sort"] = Target { command, index ->
    sorter.sort(command, sourceBuffer, 200)
}

sourceBuffer.read_ints {
    _print(this.get(0))
}
```

Two things to take from it. The `buffer { … }` builder spells out usage explicitly: `storage()`, `addressable()` (needed for buffer references), `hosted()` (CPU-visible), `dest()` (a transfer destination). Those flags are what decide whether a given access is legal later. And `renderList.others["sort"] = Target { command, index -> … }` is the general escape hatch: a slot on the frame where you get the command buffer and can record whatever Vulkan work you like. `sorter.sort(command, …)` records into that buffer rather than executing immediately.

This example reads back through `hosted()` memory. For a GPU→CPU read that happens every frame, use host-cached memory instead; see the readback notes in [Field probes](field_probes.md).

This box sorts and prints, and draws nothing.

## Slang

The same box model accepts [Slang](https://shader-slang.org) instead of GLSL through the `vertex_fragment` property, which holds both stages in one file. The Kotlin side does not change at all: modules, meshes, buffers and reflected accessors work the same way, because reflection reads the compiled shader interface rather than the source language.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/07-slang-triangle.png" alt="a row of about thirty overlapping triangles against black, each shaded from its vertex index by the Slang shader, drawn from vertices the Kotlin loop writes into a buffer each frame" wait="0.5" timeout="20" capture="native-window" native-wait-frames="6" trim-native=true graphics-context=true embed=true name="four-slang-triangle" execution="launch" source-box="assets/canonical_graphics/65bd0587-dbfa-451a-8f6a-e003ff3d1abf" source-box-properties="vertex_fragment" source-box-wait="0.1"}
var module = moduleHere { primitiveType = triangles }

var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}
    .buffer

m.vertexData = { vertexData }

var t = 0.0
fun polar(t: Double, r: Double) = vec(Math.sin(t), Math.cos(t)) * r.f

loop {
    t += 0.01
    var num = 0

    var numVertToWrite = (Math.sin(t * 0.1) * 20 + 30).i

    vertexData.write {
        for (x in 0 until numVertToWrite) {
            val offset = vec(x * 0.02, 0, 0)
            put(vec(polar(t, 0.5), 0) + offset)
            put(vec(polar(t + 0.4, 0.4), 0) + offset)
            put(vec(polar(t + 0.9, 0.3), 0) + offset)

            num += 3
        }
    }

    m.resize(num)
    true
}
```

The corpus box for this lesson (`7. slang — hello triangle`) is also the best-commented file in the collection, and is written as a tutorial. Open it directly: it makes two points in passing that generalise beyond Slang. First, buffer sizes are a guess that Field will correct: `size = 3.vec4s` is set too small there, and the buffer resizes dynamically rather than failing. Second, `perFrame = true` allocates one buffer per frame in flight, so a write never has to wait on the GPU to finish reading.

A second Slang lesson (`7b. slang — Texture`) is the same step again with a texture bound, and it makes the language-independence claim concrete: the Kotlin is the texturing code from earlier, unchanged, `prop<LoadedTexture>` and all, because the resource is matched by reflected name and the name survives the change of shader language.

Slang requires `slangc` to be available. Field checks an explicit `AFIELD_SLANGC` or `-Dafield.slangc` override, then `PATH`, then the optional compiler prepared by `./gradlew buildLearnedShaders`. That task uses a pinned shared cache; live compilation itself does not download anything. See the [optional toolchain](learned_shader.md#optional-toolchain).

Slang can also differentiate a shader. [Learn from a shader on the GPU](gpu_shader_learning.md) uses that to train a small network on what a `moduleHere()` shader renders, while you edit it.

## Diffusion-limited aggregation

The last lesson is the shortest, because the work is packaged:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/08-dla.png" alt="a diffusion-limited aggregation cluster in a native render window: a wide ring of fine magenta branches, darker red near its inner edge, around a dark hollow centre, with the green haze of still-walking particles filling the space outside it" wait="20" timeout="90" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-dla" source-box="assets/canonical_graphics/e39270ec-8faf-4d26-8f4e-ce4d23ec4af1" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var dla = ShaderDlaSystem() {
    width = 1024
    height = 1024
    particleCount = 300_000
}

// this installs editable shaders
dla.installInto()
```

`installInto()` writes the system's shaders into *this box's* shader properties, so a packaged effect arrives as source you can edit rather than as a closed component. The vertex shader it installs is a million-particle random walk that reads the accumulated image as a texture, steps each walker, sticks it when it touches something solid, and writes the particle state back through a buffer reference: simulation and drawing in a single vertex shader, with no compute pass at all:

```diffstatic {data-lang=glsl}
void main() {
    ivec2 dims = textureSize(sourceTex, 0);
    vec2 maxPos = vec2(max(dims.x - 1, 0), max(dims.y - 1, 0));

    Particle particle = push.particleBuffer.particles[gl_VertexIndex];
    uint rng = particle.rngState ^ (uint(gl_VertexIndex) * 2246822519u + 3266489917u);
    vec2 positionPx = clamp(particle.positionPx, vec2(0.0), maxPos);
    uint mode = particle.mode;
    bool becameSolid = false;

    if (mode != 0u) {
        positionPx = randomPosition(rng, dims);
        mode = 0u;
    }
    else {
        ivec2 center = clamp(ivec2(round(positionPx)), ivec2(0), dims - 1);
        int probe = max(int(round(push.sampleStepPx)), 1);
        bool solidNeighbor =
        isSolidAt(center + ivec2(probe, 0), dims, push.solidThreshold) ||
        isSolidAt(center + ivec2(-probe, 0), dims, push.solidThreshold) ||
        isSolidAt(center + ivec2(0, probe), dims, push.solidThreshold) ||
        isSolidAt(center + ivec2(0, -probe), dims, push.solidThreshold);

        if (solidNeighbor) {
            mode = 1u;
            becameSolid = true;
        }
        else {
            positionPx = clamp(positionPx + randomWalkStep(rng, push.walkStepPx), vec2(0.0), maxPos);
        }
    }

    push.particleBuffer.particles[gl_VertexIndex].positionPx = positionPx;
    // …
}
```

`push.walkStepPx`, `push.solidThreshold`, `push.stuckRed` and the rest are reflected push-constant members, so every constant in the simulation is a Kotlin lambda away from being animated. The reflected-interface contract this page opened with still holds at a million particles: the shader is the thing you edit.

Whether the result branches depends on how densely the walkers cover the texture, and the useful range is narrow. The settings above, 300 000 walkers on 1024×1024 (about 0.3 per pixel), grow visible branches within 1500 frames and a cluster about 200 pixels across by 6000. A million walkers over a 4112×2524 window are too sparse: after 3000 frames the cluster is about a dozen pixels across. A million over 900×560, about two per pixel, are too dense to branch: every point of the cluster's edge has a walker beside it, so it grows as a solid diamond rather than a dendrite. At the density above `solidDecayPerFrame` makes little visible difference.

## Traffic: the Biham–Middleton–Levine model { #bml-traffic }

In the Biham–Middleton–Levine model every cell of a torus is empty or holds one car. Red cars only move right, blue cars only move down, and they take turns: on even steps every red car with an empty cell ahead moves into it, on odd steps the blue cars do the same. The only parameter is how many cars there are. Run this in a box beneath the renderer box:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/09-bml-traffic.png" alt="Biham–Middleton–Levine traffic on a 512 by 512 torus: red and blue cars organised into diagonal bands running from lower left to upper right, with empty black road between them" wait="15" timeout="90" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-bml-traffic" source-box="assets/canonical_graphics/1b0f6eb5-b860-401d-913a-6469b4285774" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var bml = ShaderBmlSystem() {
    width = 512
    height = 512
    density = 0.32f
}
bml.installInto()
```

The grid appears in the renderer's window. This box, and the probed one below, are the `9. bml traffic` and `9b. bml probe` lessons of `canonical_graphics`. The cars start as noise and organise into diagonal bands within a few hundred steps. At 0.32 the bands then slowly lose: the fraction of cars moving falls from 0.92 to 0.44 over 2000 steps, because that density is close to the transition. At 0.20 the bands win and the moving fraction climbs to 0.998; at 0.45 a jam spanning the whole grid forms, and by step 1750 nothing moves. Change `density` and re-run, or call `bml.reset()` to scatter a new grid with the current settings. `bml.settings.running = false` pauses it.

The state is the texture, red at 1 for a red car and blue at 1 for a blue one, and each frame is one half-step: a full-screen pass that reads the previous frame and writes this one. Every cell decides from three texels. It empties if it holds a car of the moving colour and the cell ahead is empty, fills if it is empty and the cell behind holds one, and otherwise stays as it was. Because every cell gathers rather than any car pushing, no two cars can claim the same cell and the car count is conserved exactly. That rule is what the box's `fragment` tab holds, and editing it re-runs the model:

```diffstatic {data-lang=glsl}
int mover = push.mode == 0 ? 1 : 2;
ivec2 ahead = push.mode == 0 ? ivec2(1, 0) : ivec2(0, 1);

int next = here;
if (here == mover && cellAt(p + ahead, dims) == 0) {
    next = 0;
    // a car moves out of this cell this half-step ...
}
else if (here == 0 && cellAt(p - ahead, dims) == mover) {
    next = mover;
}

outColor = colourOf(next);
```

### Probing the cars that move

The window shows the pattern but not how much of the traffic is moving. A [field probe](field_probes.md) can tell you. Set `probe` to a GLSL expression and the system calls `fieldProbe(...)` with it at the commented line above, which runs exactly once for each car that moves. Here it records `x + y` of the cell the car is leaving:

```diffstatic_runner {data-lang=kotlin video="assets/afield_four_graphics/09b-bml-probe.mp4" alt="the probe card on the BML box's output, updating as the traffic runs: a calls count of the cars that moved this half-step, summary statistics of their x + y, a histogram and a quantile curve" capture="box" graphics-context=true open-outputs="true" wait-output="true" wait-output-kind="display" duration="6" fps="10" wait="3" w="760" h="60" x="24" y="180" timeout="300" embed=true name="four-bml-probe" source-box="assets/canonical_graphics/176b09bd-6e1c-4cb4-9c8a-b046d54c6dde" source-box-properties="vertex,fragment" source-box-wait="0.1"}
var bml = ShaderBmlSystem() {
    width = 512
    height = 512
    density = 0.32f
    probe = "float(p.x + p.y)"
}
bml.installInto()

fieldProbeHere(samples = 4096).display("probe")
displayOpen("probe")
```

The card on the box's `probe` channel shows two things. Its `calls` count is exactly how many cars moved this half-step. Divide it by the number of cars of one colour (`density × width × height / 2` at the default even split) and you have the model's mean velocity, the usual measure of the jamming transition: near 1 in free flow, 0 in a jam. The histogram is the distribution of `x + y` over the moving cars. Cars scattered uniformly would give a triangle peaking in the middle of the range, and the diagonal bands show up as departures from that triangle while they form.

`probe` writes the instrumented rule into the `fragment` tab, replacing what was there, so to probe something else you can edit the call in place. The tab then reads:

```diffstatic {data-lang=glsl}
#version 450

layout(set = 0, binding = 0) uniform sampler2D sourceTex;

layout(push_constant) uniform Push {
    int mode;
    float density;
    float eastboundFraction;
    int seed;
} push;

{% property "fieldProbe" %}

layout(location = 0) out vec4 outColor;

const vec4 EMPTY = vec4(0.0, 0.0, 0.0, 1.0);
const vec4 EAST = vec4(1.0, 0.0, 0.0, 1.0);
const vec4 SOUTH = vec4(0.0, 0.0, 1.0, 1.0);

uint hash(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}

float random01(uvec2 p, uint salt) {
    return float(hash(p.x ^ hash(p.y ^ hash(uint(push.seed) ^ salt)))) / 4294967295.0;
}

// 0 empty, 1 eastbound, 2 southbound
int cellAt(ivec2 p, ivec2 dims) {
    vec4 c = texelFetch(sourceTex, (p + dims) % dims, 0);
    if (c.r > 0.5) return 1;
    if (c.b > 0.5) return 2;
    return 0;
}

vec4 colourOf(int cell) {
    return cell == 1 ? EAST : (cell == 2 ? SOUTH : EMPTY);
}

void main() {
    ivec2 dims = textureSize(sourceTex, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);

    if (push.mode == 3) {
        uvec2 u = uvec2(p);
        if (random01(u, 1u) < push.density) {
            outColor = random01(u, 2u) < push.eastboundFraction ? EAST : SOUTH;
        }
        else {
            outColor = EMPTY;
        }
        return;
    }

    int here = cellAt(p, dims);
    if (push.mode == 2) {
        outColor = colourOf(here);
        return;
    }

    // even half-steps move eastbound cars +x, odd ones move southbound cars +y
    int mover = push.mode == 0 ? 1 : 2;
    ivec2 ahead = push.mode == 0 ? ivec2(1, 0) : ivec2(0, 1);

    int next = here;
    if (here == mover && cellAt(p + ahead, dims) == 0) {
        next = 0;
        fieldProbe(float(p.x + p.y));
        // a car moves out of this cell this half-step: one invocation per moving car,
        // which is where to tap it (fieldProbe, fieldStats) without counting it twice
    }
    else if (here == 0 && cellAt(p - ahead, dims) == mover) {
        next = mover;
    }

    outColor = colourOf(next);
}
```

## A fluid: lattice Boltzmann { #lattice-boltzmann }

[`ShaderLatticeBoltzmannSystem`](reference/alife.md#alife-lbm-kt-shaderlatticeboltzmannsystem) is a two-dimensional fluid. Every texel is a lattice site holding nine numbers, how much fluid there is moving in each of nine directions: still, the four neighbours, the four diagonals. A step moves each of those one site along its direction, then relaxes each site toward the equilibrium for its own density and velocity, and a population that runs into a solid site bounces back where it came from. Density and velocity are never stored; they are sums over the nine. This box builds one as a wind tunnel, wind blowing left to right across a lattice that wraps:

```diffstatic {data-lang=kotlin}
/*
A lattice Boltzmann fluid in a wind tunnel: a wind blows left to right and the
lattice wraps, so what leaves on the right comes back on the left.
On its own it is uniform wind and shows nothing; the box beneath this one puts
something in its way.
*/
prop<ShaderLatticeBoltzmannSystem>(lbm) = ShaderLatticeBoltzmannSystem() {
    width = 800
    height = 480
    baseVelocity = Vector2f(0.08f, 0f)
    // a thin fluid (viscosity about 0.014) so the wake sheds vortices
    relaxation = 1.85f
    // nothing pulls the fluid back toward the wind, so the wake travels;
    // the inlet that 10b draws at the left edge keeps the wind blowing
    driveBlend = 0f
    stepsPerFrame = 8
    dyeDecayPerFrame = 0.001f
    // vorticity: orange turns one way, blue the other. Try COMPOSITE, DYE or SPEED
    previewMode = FluidVisualizationMode.CURL
}

// this installs the editable collision-and-streaming rule in this box's fragment tab
lbm.installInto()
```

A wind through an empty tunnel is uniform and shows nothing, so a second box beneath this one puts something in its way. It draws into the fluid's control texture, where red is solid, green and blue a push, and alpha a dye source that also holds the wind steady. `controlsParameters` gives the module additive blending, and the controls are carried from frame to frame, so what the shader writes is a drawing that stays until `clearControls()` erases it:

```diffstatic {data-lang=kotlin}
/*
Draws into the fluid's control texture: a solid disc, walls top and bottom, and
stripes of dye released at the left edge. The controls persist from frame to
frame, so clear them first or a re-run leaves the old drawing behind.
*/
lbm.clearControls()

var module = moduleHere(lbm.controlsParameters("cylinder"))
lbm.addControls("cylinder", module)

val quad = fullscreenTriangle(module)
module += "quad" to quad

quad.center = { vec(160f, 236f) }
quad.radius = { 24f }
quad.height = { 480f }
```

```diffstatic {data-lang=glsl}
void main() {
    vec2 p = gl_FragCoord.xy;
    outColor = vec4(0.0);

    if (distance(p, push.center) < push.radius) outColor.r = 1.0;
    if (p.y < 3.0 || p.y > push.height - 3.0) outColor.r = 1.0;

    if (p.x < 4.0 && mod(floor(p.y / 12.0), 2.0) == 0.0) outColor.a = 1.0;
}
```

These are the `10. lattice boltzmann` and `10b. lbm cylinder` lessons of `canonical_graphics`, and the [`lattice_boltzmann_cylinder`](agent_transcripts.md#lattice_boltzmann_cylinder) starting point builds both with their renderer. The flow divides round the cylinder, and within a minute or so the two layers of shear behind it start to roll up alternately, one turning each way, and leave a von Kármán vortex street:

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="lbm_renderer" transcript="dev_docs/agents/transcripts/lattice_boltzmann_cylinder.yaml" screenshot="assets/afield_four_graphics/10-lattice-boltzmann.png" wait="80" timeout="240" native-wait-frames="4" embed=true hidden=true name="four-lattice-boltzmann" alt="vorticity behind a white cylinder in a dark wind tunnel: the flow from the left divides round it, and behind it the shear layers have rolled up into a street of alternating orange and blue vortices drifting to the right"}
// The transcript built the renderer, the fluid and the cylinder and launched them;
// this waits for the wake to start shedding and captures the window.
```

What decides it is the Reynolds number, speed times cylinder width over viscosity. The viscosity is `(1/relaxation − 0.5) / 3`, so `relaxation = 1.85f` gives about 0.014 and, with a wind of 0.08 sites per step past a 48-site cylinder, a Reynolds number near 280. Two settings in this box are there to let the street form. `driveBlend = 0f` turns off the pull back toward the wind that every site otherwise feels, which would damp a growing wake faster than it grows; the inlet stripes keep the wind blowing instead. And `initialNoise`, on by default, starts the fluid with a velocity too small to see, because a symmetric setup computed exactly stays symmetric and the wake would never start to swing.

`stepsPerFrame = 8` runs eight lattice steps a frame. The method is only accurate while the fluid moves well under a site per step, so at one step a frame a gust takes thousands of frames to cross the window. `previewMode` chooses what you see: `CURL` is vorticity, `DYE` the dye alone, `SPEED` and `VELOCITY` the flow, `COMPOSITE` the dye lit by the flow. It is read every frame, so assigning `lbm.settings.previewMode` from another box changes the view without restarting anything, and so does every other setting except the sizes.

The box's `fragment` tab holds one step of the method, all nine populations written to three attachments of one ping-pong target in a single pass, and editing it re-runs the fluid.

## A forest fire { #forest-fire }

[`ShaderForestFireSystem`](reference/alife.md#alife-fire-kt-shaderforestfiresystem) spreads fire through a forest. Each texel is a tree or bare ground, decided once from a hash of its position, and each tree is ready to burn, burning, or recovering. A ready tree catches from a burning neighbour, by chance: each burning neighbour gets one try per frame with probability `spreadProbability`, half by default. A tree burns for a few frames, then cannot burn again until it has recovered. Because trees recover, the fire never runs out of fuel, and a front can be followed by another one through the same trees. There are four kinds of fire, one per colour channel, and they compete: a tree that catches one kind is not available to the others until it has recovered. This box builds a forest the shape of the window:

```diffstatic {data-lang=kotlin}
/*
A forest fire: a fixed scatter of trees, and four kinds of fire (red, green, blue,
magenta) that compete for them. Burnt trees recover, so a fire front can be
followed by another. Nothing burns until something is lit; the box beneath this
one lights it.
The forest is 768 trees high and as wide as the window's shape needs, so it fills
the window without being stretched.
*/
val treesHigh = 768
prop<ShaderForestFireSystem>(fire) = ShaderForestFireSystem() {
    height = treesHigh
    width = treesHigh * window.width / window.height
    treeDensity = 0.6f
}

// this installs the editable spreading rule in this box's fragment tab
fire.installInto()
```

Nothing burns until something is lit. The box beneath it draws into the fire's ignition texture, one kind of fire per channel; `fireInputParameters` makes the drawing additive, so writing 1 into a channel lights that kind there. This one lights two points, red and green, and keeps them lit:

```diffstatic {data-lang=kotlin}
/*
Draws into the fire's ignition texture: two points that burn for ever, one red
and one green. Each relights its neighbourhood as soon as the trees recover, so
each sends out a train of rings, and where the trains meet one kind takes over.
*/
fire.clearFireInput()
fire.clearState()

var module = moduleHere(fire.fireInputParameters("starter"))
fire.addFireInput("starter", module)

val quad = fullscreenTriangle(module)
module += "quad" to quad

// positions are in trees, so place them as fractions of the forest's size
val w = fire.settings.width.toFloat()
val h = fire.settings.height.toFloat()
quad.red = { vec(w * 0.33f, h * 0.5f) }
quad.green = { vec(w * 0.68f, h * 0.5f) }
quad.radius = { 4f }
```

```diffstatic {data-lang=glsl}
void main() {
    vec2 p = gl_FragCoord.xy;
    outColor = vec4(0.0);
    if (distance(p, push.red) < push.radius) outColor.r = 1.0;
    if (distance(p, push.green) < push.radius) outColor.g = 1.0;
}
```

These are the `11. forest fire` and `11b. fire starter` lessons of `canonical_graphics`, and the [`forest_fire`](agent_transcripts.md#forest_fire) starting point builds both with a square window of their own. A point that never goes out relights the trees round it as soon as they recover, so each sends out a train of rings, one ring per recovery time. Red recovers faster than green by default, so its rings come more often. The two trains meet, and in the trees between the sources they interleave, each kind taking the trees the other has just left:

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="fire_renderer" transcript="dev_docs/agents/transcripts/forest_fire.yaml" screenshot="assets/afield_four_graphics/11-forest-fire.png" wait="30" timeout="240" native-wait-frames="4" embed=true hidden=true name="four-forest-fire" alt="a square forest crowded with ragged concentric rings of red and green fire, the trains from the two ignition points interleaved where they have met and passed through the recovering trees"}
// The transcript built the renderer, the forest and the fire starter and launched them;
// this waits for the two trains of rings to fill the forest and captures the window.
```

`spreadProbability` is what makes the rings round. At 1 every burning tree lights all eight of its neighbours at once, the fire moves one texel a frame along the diagonals as fast as along the axes, and the rings come out square. At one half each front advances by chance, slower on average, and roughens into a ragged circle. `treeDensity` is the other setting to try: it is the fraction of texels that hold a tree, and the default of 0.72 makes a denser, more uniform forest than the 0.6 here.

The box's `fragment` tab holds the spreading rule, and editing it re-runs the fire.

## An atmospheric sky { #atmospheric-sky }

Make a sky from sunlight scattered by molecules and haze through a spherical atmosphere. The same
shader gives you blue overhead at noon, orange at sunset, colour after the sun has gone and a blue
rim around the planet seen from space. The scattering lives in `sky/atmosphere.glsl`: include it
with `#include "sky/atmosphere.glsl"` in any shader. Its Kotlin twin,
`afield.graphics.sky.Atmosphere`, computes the same numbers on the CPU.

The box draws one fullscreen triangle beneath the renderer, as the forest fire above does. It keeps
the sun and camera in a [`SkyView`](reference/graphics.md#atmosphere-kt-skyview), hands them to the shader as push constants every frame, and puts
sliders for them in its output:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/atmospheric-sky-noon.png" alt="A noon sky seen from 3.5 km: deep blue overhead fading to pale blue and white at the horizon, the small white sun about 30 degrees up, and pale hazy ground below" timeout="120" capture="native-window" native-wait-frames="30" max-width="1600" embed=true name="atmospheric-sky-noon" macro-file="../../dev_docs/agents/transcripts/atmospheric_sky.yaml" capture-box="atmospheric_sky"}
/*
A sky you can live-code: single scattering through Earth's atmosphere (Nishita 1993, with Hillaire 2020's
coefficients), one fullscreen triangle beneath the renderer. The physics is `#include "sky/atmosphere.glsl"`;
the camera and the tone mapping are in this box's fragment property. Drag the sliders, or run one of the
box's preset properties (`sunset`, `twilight`, `space`) to jump there.
*/
import afield.graphics.sky.SkyView

// Published as this box's `skyView` property, so the preset properties can reach it.
prop<SkyView>(skyView)
val view = SkyView(sunElevation = 30.0, altitude = 3.5, pitch = 10.0, exposure = 2.0)
skyView = view

val sky = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(sky)
sky += "quad" to quad
renderer += "atmospheric-sky" to sky

quad.viewport = {
    vec(window.width.toFloat(), window.height.toFloat(), view.altitude, Math.toRadians(view.sunElevation))
}
quad.view = {
    vec(Math.toRadians(view.sunAzimuth), Math.toRadians(view.pitch), view.exposure, Math.toRadians(view.fieldOfView))
}

controlGroup("Sky") {
    slider("Sun elevation (degrees)", min = -12.0, max = 90.0, initialValue = view.sunElevation) {
        view.sunElevation = it
    }
    slider("Sun azimuth (degrees)", min = -180.0, max = 180.0, initialValue = view.sunAzimuth) { view.sunAzimuth = it }
    slider("Altitude (log10 km)", min = -3.0, max = 4.5, initialValue = Math.log10(view.altitude)) {
        view.altitude = Math.pow(10.0, it)
    }
    slider("Camera pitch (degrees)", min = -90.0, max = 90.0, initialValue = view.pitch) { view.pitch = it }
    slider("Exposure", min = 0.1, max = 40.0, initialValue = view.exposure) { view.exposure = it }
}
displayOpen("Sky")
println("atmospheric sky: sun ${view.sunElevation}°, altitude ${view.altitude} km")
```

The fragment shader turns each pixel into a view ray from a camera `altitude` km above the ground,
and asks the include what light arrives along it:

```diffstatic {data-lang=glsl}
#version 450
#include "sky/atmosphere.glsl"

layout(location = 0) out vec4 color;

layout(push_constant) uniform pcs {
    vec4 viewport; // width, height, altitude (km), sun elevation (radians)
    vec4 view; // sun azimuth, camera pitch, exposure, vertical field of view (radians)
} pc;

void main() {
    Atmosphere a = earthAtmosphere();

    // A pinhole camera at the given altitude, looking along -z and pitched up by view.y.
    vec2 uv = (gl_FragCoord.xy - 0.5 * pc.viewport.xy) / pc.viewport.y;
    uv.y = -uv.y;
    float pitch = pc.view.y;
    vec3 forward = vec3(0.0, sin(pitch), -cos(pitch));
    vec3 right = vec3(1.0, 0.0, 0.0);
    vec3 up = cross(right, forward);
    vec3 rd = normalize(forward + (uv.x * right + uv.y * up) * 2.0 * tan(0.5 * pc.view.w));
    vec3 ro = vec3(0.0, a.planetRadius + pc.viewport.z, 0.0);

    float e = pc.viewport.w, az = pc.view.x;
    vec3 sun = vec3(cos(e) * sin(az), sin(e), -cos(e) * cos(az));

    vec3 L = skyRadiance(a, ro, rd, sun, vec3(20.0), 0.00465, 32, 8);

    // Exposure, then a soft shoulder so the sun's disc saturates instead of clipping hue.
    color = vec4(1.0 - exp(-L * pc.view.z), 1.0);
}
```

### Sunset, twilight and the planet from space

The box also has three small properties, `sunset`, `twilight` and `space`, that move its `SkyView`.
Pick one in the property switcher and run it; the sky changes on the next frame. The sliders stay
where they were. The box publishes its `SkyView` as its `skyView` property, so `fromContext` in
another property of the same box can find it.

Lower the sun and the light reaching you has crossed far more air. Blue is scattered out of it
first, so what is left is yellow and red, and the haze near the ground glows orange:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/atmospheric-sky-sunset.png" alt="A sunset from 3.5 km: the sun is a bright yellow glow on the horizon, the sky fades from grey-blue overhead to yellow, and the ground below is lit orange-brown" timeout="120" capture="native-window" native-wait-frames="30" max-width="1600" embed=true name="atmospheric-sky-sunset" macro-file="../../dev_docs/agents/transcripts/atmospheric_sky.yaml" capture-box="atmospheric_sky" run-box="atmospheric_sky" property="sunset"}
// Run this property to move the sky box (its `code`) to sunset.
import afield.graphics.sky.SkyView

skyView.set(SkyView(sunElevation = 2.0, altitude = 3.5, pitch = 5.0, exposure = 4.0))
println("sky preset: sunset")
```

With the sun four degrees below the horizon the ground is in the planet's shadow, but the upper air
is still lit: a band of orange along the horizon under a violet sky, at ten times the exposure.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/atmospheric-sky-twilight.png" alt="Twilight: a black ground below a curved horizon, a thin band of orange and yellow along it, then pink and violet sky above" timeout="120" capture="native-window" native-wait-frames="30" max-width="1600" embed=true name="atmospheric-sky-twilight" macro-file="../../dev_docs/agents/transcripts/atmospheric_sky.yaml" capture-box="atmospheric_sky" run-box="atmospheric_sky" property="twilight"}
// Run this property to move the sky box (its `code`) to twilight.
import afield.graphics.sky.SkyView

skyView.set(SkyView(sunElevation = -4.0, altitude = 3.5, pitch = 5.0, exposure = 40.0))
println("sky preset: twilight")
```

From 20,000 km, looking straight down, rays that miss the shell see black space, rays that graze it
pick up a thin blue rim, and rays that meet the ground return its sunlit colour through the air. The
planet has no continents or clouds: what you see is a smooth sphere of ocean-dark ground under a
blue haze, fading into night where the sunlight has crossed a long path of air.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/atmospheric-sky-space.png" alt="The planet from 20,000 km against black space: a smooth, featureless blue-grey sphere lit from the top, with a brighter blue rim at its edge and the lower part fading through purple into night" timeout="120" capture="native-window" native-wait-frames="30" max-width="1600" embed=true name="atmospheric-sky-space" macro-file="../../dev_docs/agents/transcripts/atmospheric_sky.yaml" capture-box="atmospheric_sky" run-box="atmospheric_sky" property="space"}
// Run this property to move the sky box (its `code`) to space.
import afield.graphics.sky.SkyView

skyView.set(SkyView(sunElevation = 20.0, altitude = 20000.0, pitch = -90.0, exposure = 0.4, fieldOfView = 40.0))
println("sky preset: space")
```

### What the include computes

Distances are in kilometres and the planet's centre is the origin. `earthAtmosphere()` returns an
`Atmosphere` struct with Earth's values; change any field before you call the functions below, for
an alien sky or a thicker haze:

| Field | Earth | Meaning |
| --- | --- | --- |
| `planetRadius`, `atmosphereRadius` | 6360, 6460 km | the ground and the top of the air |
| `rayleighScattering`, `rayleighHeight` | (5.8, 13.6, 33.1)×10⁻³ /km, 8 km | molecules: scatter blue most, thin out with height |
| `mieScattering`, `mieAbsorption`, `mieHeight`, `mieG` | 4.0×10⁻³, 4.4×10⁻³ /km, 1.2 km, 0.8 | haze: grey, low, scatters forward |
| `ozoneAbsorption`, `ozoneCenter`, `ozoneHalfWidth` | (0.65, 1.88, 0.085)×10⁻³ /km, 25 km, 15 km | absorbs orange, which keeps twilight blue-violet |
| `groundAlbedo` | (0.10, 0.12, 0.15) | how much light the ground reflects; dark and bluish, like ocean |

- `skyRadiance(a, ro, rd, sunDir, sunColor, sunAngularRadius, steps, lightSteps)`: everything a camera
  at `ro` sees along `rd`, meaning scattered light, the lit ground, the sun's disc and black space.
- `skyInScatter(...)`: the scattered light along one segment of a ray, and the transmittance across it.
- `skyTransmittanceToTop(a, p, dir, steps)`: the fraction of light that gets from `p` out of the
  atmosphere along `dir`; zero in the planet's shadow.
- `skyRayleighPhase(mu)`, `skyMiePhase(mu, g)`: how much light turns through an angle whose cosine
  is `mu`.

`steps` and `lightSteps` (32 and 8 in the box) trade speed for smoothness: the view ray is cut into
`steps` pieces, and from each a second ray toward the sun is cut into `lightSteps`.

On the CPU, `Atmosphere.radiance(Atmosphere.Params(), ro, rd, sunDir)` returns the value
`skyRadiance` would, which is useful for colouring a light by the sun's elevation.

### How it works

The method is single scattering, after Nishita and colleagues. At each sample along the view ray, air
scatters sunlight toward the camera in proportion to its density and the phase function, and that
light is dimmed twice: by the air between the sample and the sun (the second march), and by the air
between the sample and the camera. Rayleigh scattering by molecules goes as the inverse fourth power
of wavelength, which is why the sky is blue and the low sun red. Mie scattering by haze uses the
Cornette-Shanks phase function, which brightens the sky around the sun. Ozone only absorbs.

Light that scatters more than once is left out, so deep twilight comes out darker than a real one
and the sky close to the horizon slightly too dark.

The sky follows Maxime Heckel's walkthrough
["On rendering the sky, sunsets and planets"](https://blog.maximeheckel.com/posts/on-rendering-the-sky-sunsets-and-planets)
and these papers:

- Tomoyuki Nishita, Takao Sirai, Katsumi Tadamura, Eihachiro Nakamae, "Display of the Earth Taking
  into Account Atmospheric Scattering", SIGGRAPH 1993.
  [doi:10.1145/166117.166140](https://doi.org/10.1145/166117.166140)
- Sébastien Hillaire, "A Scalable and Production Ready Sky and Atmosphere Rendering Technique",
  Computer Graphics Forum (EGSR) 2020, for the Earth coefficients and the ozone profile.
  [Paper](https://sebh.github.io/publications/egsr2020.pdf)

Open the [starting point](agent_transcripts.md#atmospheric_sky) to try the sky and its presets.

## Exact triangle coverage { #exact-triangle-coverage }

A rasterizer that takes one sample per pixel asks "is the pixel centre inside the triangle?" and
nothing else. Edges come out as stairs, and a triangle smaller than a pixel that falls between
centres disappears. The alternative is to compute how much of each pixel the triangle actually
covers. For a triangle and a square pixel that has an exact closed form, and Field has it on the
CPU (`afield.graphics.render.TriangleCoverage`) and as a shader include,
`#include "coverage/triangle.glsl"`. The CPU version clips the triangle to the pixel with
`clipConvexToBox`, the same clipper RectSL uses for its exact rect coverage
(`afield.lang.rectsl.RectSLCoverage`), and sums the clipped polygon's area.

The CPU box draws eight triangles at 32 × 24 pixels, once point-sampled and once with exact
coverage, next to the same scene at 512 × 384:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/exact-coverage-cpu.png" capture="box" open-outputs="true" padding="20" wait="4" timeout="180" embed=true name="exact-coverage-cpu" macro-file="../../dev_docs/agents/transcripts/exact_triangle_coverage.yaml" capture-box="coverage_cpu" alt="Three panels. Point-sampled at 32 by 24: stair-stepped orange and green triangles, the thin blue sliver broken into two blocks, the four tiny triangles and the white needle missing. Exact coverage at 32 by 24: the same triangles with graded edge pixels, the sliver and needle as faint continuous bands and four grey dots for the tiny triangles. The 512 by 384 reference shows what they are."}
// dev_docs/agents/transcripts/exact_triangle_coverage.yaml, box "coverage on the CPU"
val point = TriangleSplat.render(scene, 32, 24, CoverageSampling.POINT)
val exact = TriangleSplat.render(scene, 32, 24, CoverageSampling.EXACT)
println("point-sampled: ${point.visible} of ${scene.size} triangles reach a pixel")
println("exact:         ${exact.visible} of ${scene.size} triangles reach a pixel")
```

Point sampling reaches 3 of the 8 triangles; exact coverage reaches all 8. Each of the four tiny
triangles covers 0.08 of a pixel and shows as a pixel at 8 % brightness, which is the right answer
at that resolution.

### A soft window, and culling

`windowCoverage(..., sigma, ...)` integrates a window rather than a hard triangle: 1 at the
triangle's incentre, falling to 0 at its edges as `(d / r)^sigma`, where `d` is the distance to the
nearest edge and `r` the inradius. `sigma = 0` is the hard triangle. The angle bisectors split the
triangle into three parts, each nearest one edge, so in each part the window depends on one
distance only. Green's theorem then turns the pixel integral into one closed-form term per side of
the part clipped to the pixel.

The integral over the whole triangle is `2A / ((sigma + 1)(sigma + 2))`, and no pixel can receive
more than that. `TriangleSplat.render` culls a triangle whose total, times its opacity, is under
half an 8-bit step (`TriangleSplat.cullThreshold`), before it touches any pixel. In the demo,
5,306 of 20,000 sub-pixel triangles at 128 × 128 and `sigma = 1` are culled this way.

### The same thing in a shader

Beneath a renderer, the GPU box is a fullscreen pass whose fragment shader cuts the window into
fat pixels. The left half calls `tcWindow` at each fat pixel's centre and the right half calls
`tcWindowCoverage` over the whole fat pixel. Fat pixel size and `sigma` are sliders:

```diffstatic {data-lang=glsl}
float cover = exact
    ? tcWindowCoverage(a, b, d, sigma, cell, cell + 1.0)
    : tcWindow(a, b, d, sigma, cell + 0.5);
c = mix(c, C[i].rgb, clamp(cover * C[i].a, 0.0, 1.0));
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/exact-coverage-gpu.png" capture="native-window" native-wait-frames="30" timeout="180" max-width="1600" embed=true name="exact-coverage-gpu" hidden=true macro-file="../../dev_docs/agents/transcripts/exact_triangle_coverage.yaml" capture-box="coverage_gpu" alt="The native window split down the middle over a faint grid of fat pixels. Left: the eight triangles point-sampled, with stepped edges, two blue blocks for the sliver and nothing for the tiny triangles or the needle. Right: the same triangles with exact coverage, graded edges, a continuous blue sliver, four grey dots and a grey needle."}
// dev_docs/agents/transcripts/exact_triangle_coverage.yaml, box "coverage on the GPU"
```

`TriangleCoverageTest` checks the Kotlin against 256 × 256 supersampling of a unit pixel, over
about 250 partly covered pixels per `sigma`: the worst error is 3.4e-4 at `sigma = 0` (which is
the supersampler's own edge resolution) and at most 1.5e-5 for `sigma` 1, 2 and 4. Adding up every
pixel's integral recovers the closed-form total to 4e-13. Open the
[starting point](agent_transcripts.md#exact_triangle_coverage) to try it.

The window and its bisector split follow *ATS: Anti-Aliased Triangle Splatting with Exact
Per-Primitive Integrals* ([arXiv 2611.22512](https://arxiv.org/abs/2611.22512)). Field's code is
written from the method; none of the paper's code or figures is used. Gradients with respect to
the vertices, which the paper also derives, are not implemented here.

### Which one to use { #which-coverage-to-use }

Field computes pixel coverage in several places, and they answer different questions:

- **A triangle mesh, drawn deterministically on the CPU:** the [CPU rasterizer](three_output.md#what-the-cpu-rasterizer-is-for)
  behind `MeshRenderer.CPU` and the inline `TriMesh` card. It is a z-buffered scanline rasterizer
  that smooths edges by supersampling (2 × 2 samples per pixel by default), not by exact area.
- **The exact area of each pixel a triangle covers, or a soft triangle window:** this section,
  `TriangleCoverage` on the CPU and `coverage/triangle.glsl` in a shader.
- **The exact area of RectSL rects:** `RectSLCoverage`, the reference RectSL's soft raster is
  measured against. It shares `clipConvexToBox` with `TriangleCoverage`.
- **Gaussian splats:** `SplatRaster` (depth sort, then back to front over the whole image) or
  [`SplatTiles`](geometry_from_disk.md#splats-by-tile-and-what-a-tile-costs) (16 × 16 tiles,
  front to back, on the CPU or as a compute shader). Both project splats with `SplatRaster.project`.
- **A filled curved `FLine` on the GPU:** [Slug filled paths](slug_paths.md), analytic curve
  coverage evaluated in the fragment shader.
- **A filled or stroked `FLine` in a GPU buffer, as a mask or field a shader reads:**
  `BufferCanvas.fill(line, value, rule)` and `BufferCanvas.stroke(line, width, value)` write exact
  area coverage into the buffer, under non-zero or even-odd, however the outline overlaps itself
  ([An outline filled into a buffer](visualizers.md#an-outline-filled-into-a-buffer)).
- **The same coverage as numbers or spans on the CPU, with every stage editable:**
  `Gezira.rasterize(beziers, w, h, rule)` and the `Gezira.rasterizer` kernels in
  [Rasterising with stream kernels](#stream-kernels) (`afield.graphics.nile`), which
  `BufferCanvas.fill` and `stroke` run underneath. Gezira's original edge-sample pipeline is
  `Gezira.edgeSampleRasterizer`; it is exact only where one outline crosses a pixel
  ([Overlaps and fill rules](#overlaps-and-fill-rules)).

## Rasterising with stream kernels { #stream-kernels }

Dan Amelang's Nile language writes graphics as small kernels that each read one stream of typed
tokens and write another, and his Gezira library builds a whole 2D renderer out of them. The
rasteriser is five stages. DecomposeBeziers cuts each curve in two, and pushes both parts back
onto its own input, until every piece fits inside one pixel. Field's version cuts where the curve
crosses a pixel edge when it can, and in half otherwise, so a circle of radius 15 pixels becomes
124 pieces. Each piece becomes an edge sample: the
pixel it is in, how far it descends, and how much of that pixel lies to its right. The samples are
sorted by x and then by y, and CombineEdgeSamples walks each row, adding up each pixel's samples and
carrying the descent to the right, so the result is a stream of coverage spans.

`afield.graphics.nile` is a small Kotlin version of this. A `kernel` has an `each { }` block that
can `emit` downstream or `push` back onto its own queue, stages join with `then`, and `trace` runs a
pipeline and keeps every stream by stage name. `Gezira.edgeSampleRasterizer` is the five stages, so
a box can draw any of the streams over the pixel grid:

```diffstatic {data-lang=kotlin}
val t = Gezira.edgeSampleRasterizer.trace(Gezira.beziers(shape))
val pieces = t.get<Bezier>("DecomposeBeziers")
val samples = t.get<EdgeSample>("EdgeSamples")
val spans = t.get<Span>("CombineEdgeSamples")
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-streams.png" capture="box" open-outputs="true" padding="20" wait="4" timeout="180" embed=true name="gezira-streams" macro-file="../../dev_docs/agents/transcripts/nile_gezira_kernels.yaml" capture-box="gezira_streams" alt="Three panels over a 16 by 12 pixel grid. Pieces: a curved shape cut into short orange and blue pieces, each inside one pixel, with a cross at every cut. Edge samples: the outline with one short vertical bar per piece, orange where the edge descends and blue where it rises. Spans: the shape filled in white with graded edge pixels, yellow bars on the edge pixels and green bars across the interior runs."}
// dev_docs/agents/transcripts/nile_gezira_kernels.yaml, box "gezira streams"
```

Inside a pixel the piece is not replaced by its chord: the area to its right is integrated along
the curve itself, with three-point Gauss–Legendre, which is exact for a cubic. So the pipeline
gives exact coverage for curves as well as polygons. `GeziraTest` measures it against
`TriangleCoverage`, fanning each polygon into signed triangles: over 9,720 edge and interior pixels of 40 stars and triangles, the worst difference is
2e-13 and the mean 8e-15. Against the
same curves flattened to 2,048 chords each, the worst difference is 3.6e-6, which is what
the chords leave out.

Beside Skia's anti-aliased raster the two agree to within Skia's own approximation and its
8-bit storage:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-vs-skia.png" capture="box" open-outputs="true" padding="20" wait="4" timeout="180" embed=true name="gezira-vs-skia" macro-file="../../dev_docs/agents/transcripts/nile_gezira_kernels.yaml" capture-box="gezira_vs_skia" alt="Three panels at 48 by 40 pixels: a five-pointed star overlapping a circle, rasterised by the kernels, the same by Skia, and their difference magnified ten times, which is a red outline along the edges, brightest on the edges facing down and to the right."}
// dev_docs/agents/transcripts/nile_gezira_kernels.yaml, box "beside skia"
```

On a five-pointed star the kernels and Skia differ by at most 0.10 and on average 0.009, and
Skia differs from the exact `TriangleCoverage` answer by the same 0.10: the difference is Skia's.
On a circle the largest difference is 0.19 at one edge pixel, the mean 0.008.

### Overlaps and fill rules

Gezira's five stages are exact only while one outline crosses each pixel. CombineEdgeSamples adds
up signed edge areas, so in a pixel that two outlines cross (two shapes, or two parts of one outline
that crosses itself) it adds both partial coverages and counts their overlap twice. Against a
64 × 64 non-zero supersample of a star overlapping a circle, the edge samples are up to 0.31 too
high, in the few pixels both outlines cross; the mean over all 560 covered pixels is 0.003. A sum of areas also
has no way to say which overlaps are holes, so there is no even-odd rule.

`Gezira.rasterizer` is a different pipeline of four stages that does both. FlattenBeziers cuts each curve
in half, pushing both halves back, until it lies within 1/10,000 of a pixel of its chord.
RowEdges cuts the lines at every pixel row, the pieces are sorted by row, and WindingSpans resolves
one row at a time: it cuts the row into bands at every line end and every place two lines cross, so
no two lines cross inside a band, then walks each band left to right counting the winding number.
Wherever the fill rule says the region between two neighbouring lines is inside, it adds that
trapezoid's exact area to each pixel column it covers:

```diffstatic {data-lang=kotlin}
val nonZero = Gezira.rasterize(shape, 56, 40, FillRule.NON_ZERO)
val evenOdd = Gezira.rasterize(shape, 56, 40, FillRule.EVEN_ODD)
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-fill-rules.png" capture="box" open-outputs="true" padding="20" wait="4" timeout="180" embed=true name="gezira-fill-rules" macro-file="../../dev_docs/agents/transcripts/nile_gezira_kernels.yaml" capture-box="gezira_fill_rules" alt="Three panels at 56 by 40 pixels. Non-zero: a pentagram filled solid and two overlapping circles filled as one shape. Even-odd: the same with the pentagram's centre pentagon and the lens where the circles overlap left empty. Edge samples, error times ten: red dots only at the pixels where two edges cross, at the star's inner corners and where the circles meet."}
// dev_docs/agents/transcripts/nile_gezira_kernels.yaml, box "fill rules"
```

For polygons this is exact whatever the overlap. `GeziraWindingTest` builds exact answers from
`TriangleCoverage` for twelve pairs of overlapping convex polygons (the union is `A + B − A∩B`, the
even-odd answer `A + B − 2·A∩B`, with the intersection clipped exactly) and for a square with a
square hole wound each way. Under both rules the largest difference over 5,727 pixels is
2e-13; the edge samples are up to 0.36 off the same unions. Curves are off by at most the flattening:
against the edge samples on ten circles, where those are exact, the largest difference is 6e-5 over 4,349 pixels. On a
self-crossing pentagram and on overlapping curves, against a 64 × 64 supersample under each rule,
the largest difference is 0.0025, which is the supersample's own resolution, and the mean 1e-4.
Skia is up to 0.16 off the same supersample on the pentagram.

The edge samples keep their place as Gezira's design, and the streams above are theirs.
`BufferCanvas.fill` and `stroke`, and `Canvas.draw` below, use the winding rasteriser.

A new stage is one more kernel. Gezira's TransformBeziers, a rotation here, goes in front of the
rasteriser with `then`:

```diffstatic {data-lang=kotlin}
val t = (rotate(20.0, 8.0, 8.0) then Gezira.rasterizer).trace(Gezira.beziers(square))
```

### Stroking

`GeziraStroke.strokeBezierPath(width)` is Gezira's StrokeBezierPath: it offsets each segment to
both sides, and a segment whose offset strays more than 0.01 pixels from the true offset curve is
cut in half and both halves pushed back, the same recursion DecomposeBeziers uses. Its output is an
outline that overlaps itself on the inside of every bend, which the non-zero rule covers once:

```diffstatic {data-lang=kotlin}
val t = (GeziraStroke.strokeBezierPath(2.0) then Gezira.rasterizer).trace(wave)
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-stroke.png" capture="box" open-outputs="true" padding="20" wait="4" timeout="180" embed=true name="gezira-stroke" macro-file="../../dev_docs/agents/transcripts/nile_gezira_kernels.yaml" capture-box="gezira_stroke" alt="A wave two pixels wide over a 48 by 24 pixel grid, its pixels white in the middle and grey at the edges. The stroke outline is drawn over it in alternating orange and blue pieces, short where the wave bends and long where it is straight, with a straight cap across each end."}
// dev_docs/agents/transcripts/nile_gezira_kernels.yaml, box "stroke kernel"
```

Joins are bevels and caps are butt. A stroked straight line is an exact rectangle (to 5e-14). A
circle stroked 2.5 pixels wide matches the exact ring to 0.0043, and the open wave matches a
32 × 32 supersample of the true stroke to 0.0031, mean 0.0005. Skia's stroke of the same circle is
up to 0.20 off the ring, and of the wave 0.039 on average.

### Texture and compositing

The paint end of the pipeline is two more kernels. `GeziraComposite.texture { x, y -> colour }`
turns each span into fragments, the colour at the pixel centre scaled by the span's coverage, and
`compositeOver(canvas, blend)` blends each fragment into a `Canvas` and emits the result, so the
composited pixels are a stream too. `Canvas.draw` runs all of it:

```diffstatic {data-lang=kotlin}
canvas.draw(Gezira.beziers(star), GeziraComposite.texture(gradient))
canvas.draw(Gezira.circle(40.0, 28.0, 13.0), GeziraComposite.color(Rgba.of(0.1, 0.5, 1.0, 0.55)))
canvas.draw(ring, GeziraComposite.color(Rgba.of(0.3, 0.9, 0.5, 0.9)), GeziraComposite.MULTIPLY)
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-composite.png" capture="box" open-outputs="true" padding="20" wait="4" timeout="180" embed=true name="gezira-composite" macro-file="../../dev_docs/agents/transcripts/nile_gezira_kernels.yaml" capture-box="gezira_composite" alt="A 64 by 48 pixel canvas: a star shading from orange at the top left to pink at the bottom, a translucent blue disc over its right side turning the star purple where they overlap, and a green ring over its left side that darkens the star to olive and teal where they meet."}
// dev_docs/agents/transcripts/nile_gezira_kernels.yaml, box "texture and composite"
```

Two translucent shapes composited with `OVER` agree with Skia's source-over to 0.0053 per channel
on average (largest 0.076, at edge pixels, where the coverage differs as above).

### WindingSpans on the GPU { #gezira-gpu }

`GeziraGpu` runs the winding rasteriser's last stage as a compute shader. FlattenBeziers, RowEdges
and the sort stay on the CPU as the same kernels; `GeziraGpu.pack` lays out their row edges, each
relative to its row and to the row's leftmost pixel, so float32 keeps its digits for the shape and
not for the image. On the GPU each pixel row is one workgroup of 32 invocations. They find the row's
band boundaries (every edge end and every place two edges cross) and sort them; then each takes a
run of bands, walks each band's edges left to right counting the winding number, and adds the area
of every inside trapezoid to the columns it covers, exactly as WindingSpans does.

A box beneath a renderer gets the buffers and the shader from a `GpuProbe`, which also says when
the image has been written:

```diffstatic {data-lang=kotlin}
val probe = GpuProbe(renderer, "gezira-gpu-demo")
val stage = GeziraGpu.stage(probe)
val jobs = FillRule.entries.map { GeziraGpu.dispatch(probe, stage, shape, W, H, it, it.name) }
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-gpu.png" capture="box" open-outputs="true" padding="20" wait="6" timeout="240" embed=true name="gezira-gpu" macro-file="../../dev_docs/agents/transcripts/gezira_gpu_winding.yaml" capture-box="ggw_raster" alt="Six panels in two rows and three columns, each 120 by 48 pixels: a pentagram, two overlapping circles and a square with a square hole. The top row is the non-zero rule, all three shapes solid; the bottom row is even-odd, with the pentagram's centre, the circles' lens and the square's hole empty. The columns are the GPU, the CPU, and their difference magnified 100,000 times, a faint red outline brightest on the square's vertical edges."}
// dev_docs/agents/transcripts/gezira_gpu_winding.yaml, box "gezira on the gpu"
```

The rows are the two fill rules; the columns are the GPU, the CPU and their difference magnified
100,000 times. The difference is float32 against the CPU's doubles. The probe `four.gezira-gpu.probe` runs both
on eighteen images (stars, a pentagram, circles, overlapping curves, a square with a hole wound
each way, a word of glyphs, under each rule): the largest difference over 47,232 pixels is 1.5e-6
and the mean 1.4e-8. On fields of 64 stars and circles it is 5e-6 at 128 × 128, rising with the
image to 3.9e-5 at 1024 × 1024, where x is stored to fewer digits.

This is a study of one stage checked against its CPU kernel, not a renderer to draw with: for
filled paths on the GPU use [Slug](slug_paths.md), which shades coverage per fragment.

It is not yet faster. On an Apple GPU the shader takes 29 to 55 ms on those fields, from 128 × 128 to
1024 × 1024, where the whole CPU rasteriser takes 31 to 65 ms, and the CPU's share of the GPU path
(flattening, cutting and laying out) adds 14 to 30 ms. The flattening, to 1/10,000 of a pixel, puts
a few hundred edges in each row of a small circle, and each band still visits every edge in its row.
[The tiger on the GPU](#gezira-gpu-tiger) below puts the whole pipeline there.

### A line of text

Glyph outlines are quadratic Béziers. (The SVG tiger below is cubics and straight lines; the kernels
take both.) `Gezira.glyphs` sets a string with Java's fonts and keeps the quadratics:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-text.png" capture="box" open-outputs="true" padding="20" wait="4" timeout="180" embed=true name="gezira-text" macro-file="../../dev_docs/agents/transcripts/nile_gezira_kernels.yaml" capture-box="gezira_text" alt="Two panels each showing the words Pixels on a screen in a serif face, white on black, the first rasterised by the kernels and the second by Skia; at this size they look the same."}
// dev_docs/agents/transcripts/nile_gezira_kernels.yaml, box "text beside skia"
```

"Pixels on a screen" at 40 pixels is 426 segments, 309 of them quadratic. DecomposeBeziers cuts it
into 2,220 pieces and the whole pipeline takes about 7 ms on the CPU. Against a 32 × 32 supersample
the kernels' coverage is off by 0.0016 on average (largest 0.017); Skia's is off by 0.032 on average
and it draws 0.7 % less ink in total.

### The SVG tiger

`GeziraFLine.render` draws Field's own FLines with the kernels above, painted by the attributes
every Field renderer reads: `color` (its alpha is the opacity), `filled`, `stroked` and
`thickness`. FLine has no fill rule, so `GeziraFLine` adds one, `fillRule`, non-zero unless set.
Each line is filled with the winding rasteriser under its rule, then stroked with
`StrokeBezierPath`, then composited. Only `GeziraFLine` reads `fillRule`, so Field's own line
renderer, under it here, fills the frame's hole:

```diffstatic {data-lang=kotlin}
frame.filled = true
frame.fillRule = FillRule.EVEN_ODD
ring.thickness = 6.0
val r = GeziraFLine.render([disc, ring, frame, wave], 220, 130)
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-fline-paint.png" capture="box" open-outputs="true" padding="20" trim="true" wait="4" timeout="180" embed=true name="gezira-fline-paint" macro-file="../../dev_docs/agents/transcripts/nile_gezira_svg.yaml" capture-box="gezira_fline_paint" alt="Four FLines drawn twice. Above, by the Gezira kernels on white: an orange disc with a dark brown ring, a translucent blue square frame with a square hole, and a crimson wave across the bottom. Below, larger, by Field's line renderer on black: the same, except the blue square is solid, since that renderer does not read fillRule."}
// dev_docs/agents/transcripts/nile_gezira_svg.yaml, box "flines through gezira"
```

`GeziraSvg.parse` reads an SVG document into such FLines, in the document's units. It reads path
data with every command (arcs become one cubic per quarter turn), rectangles, circles, ellipses,
lines and polygons, and nested groups, whose `transform`s compose and whose `fill`, `stroke`,
`stroke-width`, `fill-rule` and opacities are inherited, from attributes or `style`. An element
with a fill and a stroke becomes two FLines, the fill first. `GeziraSvg.render` fits the document
to a canvas and hands its FLines to `GeziraFLine.render`; `GeziraSvg.skia` hands the same text to
Skia's own SVG renderer, for comparison:

```diffstatic {data-lang=kotlin}
val doc = GeziraSvg.parse(GeziraSvg.tiger())
val r = GeziraSvg.render(doc, 300, 300)
val skia = GeziraSvg.skia(GeziraSvg.tiger(), 300, 300)
val diff = GeziraSvg.difference(r.canvas.rgba, skia, 10.0)
```

The Ghostscript tiger by the kernels, by Skia, and the difference between them times ten, white
where they agree. The readout over it counts the elements and segments read, the Béziers into the
rasteriser, the lines they flatten to and the spans out, and times each stage:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-svg-tiger.png" capture="box" open-outputs="true" padding="20" trim="true" wait="6" timeout="240" embed=true name="gezira-svg-tiger" macro-file="../../dev_docs/agents/transcripts/nile_gezira_svg.yaml" capture-box="gezira_svg_tiger" alt="Three 300 pixel tiger heads side by side, orange and black stripes, green eyes and an open pink mouth: the first rasterised by the Gezira kernels, the second by Skia, which look the same; the third is almost all white, with faint coloured outlines along edges and darker lines along the whiskers, where the two renderers differ most."}
// dev_docs/agents/transcripts/nile_gezira_svg.yaml, box "tiger beside skia"
```

The two agree away from edges and differ most on the whiskers, strokes thinner than a pixel.
`StrokeBezierPath` joins corners with a bevel, where SVG's default is a mitre.
Gradients, `use`, clipping, masks and text are not read (`SvgDocument.skipped` counts what was
passed over), and a group's `opacity` multiplies into each shape rather than compositing the group
as one layer. The tiger is Ghostscript's `tiger.eps`, AGPL, as converted on
[Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Ghostscript_Tiger.svg); the
[SVG starting point](agent_transcripts.md#nile_gezira_svg) replays these boxes.

### The tiger on the GPU { #gezira-gpu-tiger }

`GeziraGpuScene` draws a whole document on the GPU, every frame, from the same FLines
`GeziraFLine.render` takes. The FLines go up once, with each stroke outlined by `StrokeBezierPath`
on the CPU at load, and each frame five compute stages run with nothing done on the CPU:

1. *count*: each curve, through the frame's transform, gets the number of pieces Wang's bound says
   keep it within 0.02 pixels of its chords;
2. *scan*: a prefix sum turns the counts into where each curve's segments go;
3. *emit*: each curve writes its segments, so they stay in paint order;
4. *bin*: each band of 16 rows keeps the segments that reach it, in order, and marks where each
   path's run of them starts;
5. *resolve*: each pixel row takes the runs that reach it in paint order, resolves each one exactly
   as WindingSpans does under the path's fill rule, and composites the path's colour over the row.

The transform is six numbers, so turning or zooming the tiger costs the CPU nothing:

```diffstatic {data-lang=kotlin}
val scene = GeziraGpuScene.of(probe, doc.lines, N, N, fit)
loop {
    scene.transform = about * fit
    frame()
}
```

One frame read back at 300 × 300, beside the CPU's `GeziraSvg.render` and the difference times ten,
with the readout of what was measured:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/gezira-gpu-tiger.png" capture="box" open-outputs="true" padding="20" trim="true" wait="10" timeout="300" embed=true name="gezira-gpu-tiger" macro-file="../../dev_docs/agents/transcripts/gezira_gpu_tiger.yaml" capture-box="ggt_compare" alt="Three 300 pixel tiger heads side by side: the first drawn on the GPU, the second by the CPU kernels, which look the same; the third almost all white, with faint outlines along the whiskers and edges. Below, a readout of the paths and curves, the largest and mean difference per channel, the frame time with both GPU scenes drawing, and the CPU's time."}
// dev_docs/agents/transcripts/gezira_gpu_tiger.yaml, box "gpu beside cpu"
```

The GPU and the CPU differ by the flattening: the CPU halves curves to 1/10,000 of a pixel, the GPU
cuts each into Wang's count of equal pieces at 0.02. `four.gezira-gpu-tiger.probe` measures both
against each other and against Skia, and times them:

| size | largest / mean difference from the CPU | frame, wall | pipeline on the GPU | resolve stage | CPU `GeziraSvg.render` |
| --- | --- | --- | --- | --- | --- |
| 450 × 450 | 0.021 / 1.5e-4 | 15.8 ms | 10.2 ms | 9.2 ms | 1,029 ms |
| 900 × 900 | 0.025 / 8.2e-5 | 16.0 ms | 11.2 ms | 9.9 ms | 769 ms |
| 1800 × 1800 | 0.032 / 5.0e-5 | 18.4 ms | 17.8 ms | 16.0 ms | 1,211 ms |

An empty frame in the same window takes 8.3 ms. The pipeline's own time is the difference between
frames that run it five times and frames that run it once, and each stage's the same way with only
that stage repeated; the renderer's own GPU timestamps come back unusable on this driver. Against
Skia the GPU and the CPU differ by the same 2.1e-3 on average at 900 × 900. Almost all of the time
is the resolve stage; bin is under 2 ms and the other three under 0.3 ms between them.

What sets the resolve stage's speed is its shared memory, because that decides how many rows a GPU
core works on at once. `rowEdges`, the most edges one path may put in one pixel row, sizes it:

```diffstatic {data-lang=kotlin}
val scene = GeziraGpuScene(probe, GeziraGpuScene.paths(doc.lines), N, N, fit, rowEdges = 256)
```

At 900 × 900 the pipeline takes 16.8 ms at 512, 13.1 ms at 320 and 11.3 ms at 256, the default,
with the same picture. A row that one path crowds past `rowEdges` is not lost: the fast pass leaves it,
and a second pass at 512 redraws just that row, so a small or busy drawing costs the old speed only
in its crowded rows (`wideRows()` counts them; the tiger has none at 150 × 150, where its busiest
row has 242 edges).
Under MoltenVK (`AFIELD_VULKAN_DRIVER=moltenvk`) the same frame takes 10.8 ms wall.

??? aside "Agents: how these were measured"
    Every number in this section is printed by `four.gezira-gpu-tiger.probe`
    (`GeziraGpuTigerProbe`); the runs, the per-stage tables before and after, and what was tried
    and did not help are in `dev_docs/GEZIRA_GPU_TIMING.md`.

The [GPU tiger starting point](agent_transcripts.md#gezira_gpu_tiger) opens the live version: a box
turns and zooms the tiger in the native window while another draws the frame above.

Open the [starting point](agent_transcripts.md#nile_gezira_kernels) to edit the kernels. The
method is from *[Pixels on a screen](https://whitebeard.blog/posts/pixels-on-a-screen/)*, a post
on umgeni, a compiler for Nile that renders the SVG tiger from Gezira kernels; Field's kernels are
written from that description. Round joins and caps, Gezira's other blend modes and image
textures are not implemented here; on the GPU, strokes are outlined on the CPU once at load.

## Stopping the renderer

Stopping the box that owns `renderer` also stops every running box connected beneath it, then
frees the GPU device. The same happens when you close the Field window. Field waits up to two
seconds for those boxes to finish; a `loop {}` stops within a frame. So a child box that writes
buffers every frame does not outlive the memory it writes into.
To start them again, select the renderer box and run **Relaunch the N box(es) stopping this
renderer stopped** from the command palette. It launches the renderer box first if its renderer
is gone, then the boxes it stopped, parents before children.

Anything that is not a box job, such as a raw `thread {}` or a renderer stashed in a global, is
not stopped. If it writes a buffer after the device is gone, it gets an `IllegalStateException`
that says so. A queue submission after the renderer has gone, or a descriptor that still names a
freed buffer, is refused the same way. Stop that work yourself, from a box you can stop.

??? aside "Agents: teardown order"
    The shutdown order is written up in `dev_docs/renderer_teardown_order.md`.

## Where these examples come from

Every example on this page is a box in a real Field document, `canonical_graphics`, which is what
the viewer at the top of this page is showing. To read the code rather than the prose, click a box
there for its Kotlin, its shaders and the picture its window drew, or
[open the document full-page](offline.html?file=assets/canonical_graphics/metadata.edn). To run and
edit the lessons, open **Welcome → Starting points → canonical_graphics** in Field, which makes you
an editable copy; launch its **window** box first, then a lesson.

??? aside "Agents: the lesson index"
    Before writing Field 4 graphics code, adapt a lesson from
    [`LESSONS.md`](assets/canonical_graphics/LESSONS.md). It lists every lesson with its Kotlin and
    shader files, records the parent chain each one needs in order to run, and carries a reverse
    index from Field API name (`RenderToTexture`, `LoadedTexture`, `computeModuleHere`,
    `GpuRadixSort`, …) to the lessons that use it.

Each image is the lesson's own output, captured from a live Vulkan window. Sections without an
image say why under the code: the lesson needs two boxes wired together, needs a file or tool the
repository does not ship, or produces something other than a picture. For the two-box shape, the
[threebox](offline.html?file=assets/agent_transcripts/threebox/metadata.edn) and [spin_glass](offline.html?file=assets/agent_transcripts/spin_glass/metadata.edn) [agent transcripts](agent_transcripts.md) are runnable versions.

## Where to go next

For experimental area lights, Vulkan ray-query shadows and optional RTXDI resampling, see [Direct lighting](direct_lighting.md). Other pages that build on the pieces here:

- [Ray Tracing Examples](ray_tracing_examples.md): a ray-traced image built up from a ray-generation shader.
- [Compute your ray-traced geometry](computed_ray_geometry.md): a compute shader writes the triangles the ray tracer sees.
- [Ray Reconstruction](ray_reconstruction.md): denoising RTXDI samples into a stable image.
- [Field Probes](field_probes.md): instrumenting shaders that have no `println`.
- [CUDA Beside the Renderer](cuda.md): particles moved by a CUDA kernel in the renderer's own buffer, on Linux with an NVIDIA GPU.
- [WebRTC Streams](webrtc.md): publishing a render as a live video stream, and sampling other streams as textures.
- [Stereo RTT composite](stereo_rtt_composite.md): a small scene you can change while wearing a headset.

These examples cover the low-level pieces: modules, meshes, buffers, cameras, compute dispatch,
textures, offscreen targets, shader includes and templating, and the debugging tools. Once that
contract is clear, continue with [Fabricate Renderer](fabricate_renderer.md) for the higher-level
scene harness, material parameters, shadows, and ambient occlusion captures, or
[Path tracing](path_tracing.md) for the progressive tracer written over the same device. The
[Renderers](reference/renderers.md) reference page lists all three and the scene, pass and material
machinery they share, each declaration with the KDoc beside it in the source.

For pixel coverage computed on the CPU rather than by the GPU's rasterizer, start from
[Which one to use](#which-coverage-to-use). The [CPU rasterizer](three_output.md#what-the-cpu-rasterizer-is-for)
draws meshes with no GPU at all, [Splats by tile](geometry_from_disk.md#splats-by-tile-and-what-a-tile-costs)
does the same for Gaussian splats, and [how wrong soft coverage is](rectsl_inverse_animation.md#fitting-against-an-image)
measures RectSL's differentiable raster against exact area.

### Related rendering projects

- [Chromium](puppet_browser.md) and [video particles](particle_video.md) use browser and video frames as textures.
  [Syphon](syphon.md) shares frames with other macOS applications.
- [Streaming diffusion](streaming_diffusion.md) puts live image generation in the render graph.
  [OpenDLSS-NR](open_dlss_nr.md) applies a neural filter to rendered images.
- [Particles, cloth and constraints](physics.md) covers particle and constraint simulation.
  [Pressure membranes](pressure_membranes.md) builds inflated surfaces from pressure and tension.
- In a headset, put [stereo video in a room](stereo_video.md), use
  [body and hand tracking](body_tracking.md), or place an [eye-tracked gaze marker](openxr_eye_intersection.md).

## API reference

- Installing the diffusion-limited aggregation system:
  [`ShaderDlaSystem`](reference/alife.md#alife-dla-kt-shaderdlasystem) —
  [`installInto`](reference/alife.md#alife-dla-kt--shaderdlasystem--installinto).
- Installing and feeding the forest-fire simulation:
  [`ShaderForestFireSystem`](reference/alife.md#alife-fire-kt-shaderforestfiresystem) —
  [`addFireInput`](reference/alife.md#alife-fire-kt--shaderforestfiresystem--addfireinput),
  [`fireInputParameters`](reference/alife.md#alife-fire-kt--shaderforestfiresystem--fireinputparameters),
  [`installInto`](reference/alife.md#alife-fire-kt--shaderforestfiresystem--installinto).
- Installing and controlling the fluid simulation:
  [`ShaderLatticeBoltzmannSystem`](reference/alife.md#alife-lbm-kt-shaderlatticeboltzmannsystem) —
  [`addControls`](reference/alife.md#alife-lbm-kt--shaderlatticeboltzmannsystem--addcontrols),
  [`controlsParameters`](reference/alife.md#alife-lbm-kt--shaderlatticeboltzmannsystem--controlsparameters),
  [`installInto`](reference/alife.md#alife-lbm-kt--shaderlatticeboltzmannsystem--installinto).
