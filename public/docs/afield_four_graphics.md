# afield.four Graphics

`afield.four` is Field's direct Vulkan path. A graphics box usually has three parts:

- Kotlin code that creates modules, meshes, buffers, cameras, textures, and renderer connections.
- Shader properties such as `vertex`, `fragment`, `compute`, or `vertex_fragment`.
- Reflected accessors that Field generates by reading the shader interface.

That last part is the one to learn. If a vertex shader declares `layout(location = 0) in vec3 pos`, the Kotlin side can write `pos.add(...)` inside `m.set { ... }`. If a shader declares a push constant member named `vertexData`, the Kotlin side can assign `m.vertexData = { vertexData }`. A shader is both the code that runs on the GPU and the contract that shapes the Kotlin API for its box.

<iframe
  src="/offline.html?file=/field4/assets/canonical_graphics/metadata.edn"
  title="Canonical Graphics Offline Viewer"
  style="width:100%; height:640px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>

## A traditional triangle

Start with one shader module and one mesh. `moduleHere { ... }` creates a shader module from the current box's shader properties. `Mesh()` creates draw data. `module += m named "my mesh"` attaches the mesh to the module, and `renderer += module` makes the module part of the window.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/00-traditional-triangle.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-traditional-triangle" macro-file="../../dev_docs/agents/transcripts/afield_four_traditional_triangle.yaml" capture-box="agent_four_traditional_triangle"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

var module = moduleHere {
    primitiveType = triangle_strip
}

var m = Mesh()

module += m named "my mesh"
renderer += module

m.set(3, 3) {
    pos.add(0,0,0, 0.5,0,0, 0.5,0.5,0)

    3 {
        col.add(0,it/3.0,1-it/3.0,1)
    }
    tri.add(0,1,2)
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

`layout(location = 0) in vec3 pos` is why Kotlin has `pos.add(...)`. `layout(location = 1) in vec4 col` is why Kotlin has `col.add(...)`. The shader copies `col` to `fcol`, and the fragment shader receives that value at the matching location:

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

## Buffer-backed triangles

The first triangle put `pos` and `col` directly into the mesh's vertex buffers. Sometimes you want a buffer that is visible as an ordinary named resource in the shader: perhaps the geometry changes every frame, perhaps a compute shader fills it, or perhaps several draws share it. This example uses the simplest version of that pattern: write a few positions into a `defaultBuffer`, bind that buffer to a reflected push-constant member, and draw vertices by index.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/01-buffer-triangles.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-buffer-triangles" source-box="assets/canonical_graphics/af0946dd-f25d-4355-984d-f0d24a43ee12" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer {
    perFrame = true
    size = 9.vec4s
}.buffer

m.vertexData = { vertexData }

vertexData.write {
    put(vec(-0.8, -0.6, 0.0, 1.0))
    put(vec(-0.2, -0.6, 0.0, 1.0))
    put(vec(-0.5,  0.2, 0.0, 1.0))

    put(vec(-0.1, -0.4, 0.0, 1.0))
    put(vec( 0.5, -0.4, 0.0, 1.0))
    put(vec( 0.2,  0.5, 0.0, 1.0))

    put(vec( 0.2, -0.8, 0.0, 1.0))
    put(vec( 0.8, -0.8, 0.0, 1.0))
    put(vec( 0.5,  0.0, 0.0, 1.0))
}

m.resize(9)
```

There are three moving pieces here. `defaultBuffer { ... }.buffer` allocates GPU-visible storage. `vertexData.write { ... }` fills it with nine `vec4` positions, three per triangle. `m.resize(9)` tells the draw to consume nine vertices. If this were animated, the same code would move the `write` and `resize` calls into a `frames { ... }` loop.

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
    return (a % b) / (b - 1);
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/01b-ubo-camera.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-ubo-camera" source-box="assets/canonical_graphics/85e8478b-529b-406e-b835-7c00912848f2" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += module

val camera = afield.four.util.Camera()
camera.at = vec(-8,2,7)

module.camera.m = camera.m()
module.camera.p = camera.p()

for (n in 1..80) {
    m.set(3 * n, 1 * n) {
        n {
            val z = 0.0 + it
            pos.add(0,0,-1+z, 1.0,0.1,z, 1,1,z)
            3 {
                col.add(10-it*0,it/3.0,1-it/3.0,1)
            }
            tri.add(0+3*it,1+3*it,2+3*it)
        }
    }
}
```

The shader declares a UBO named `camera`:

```diffstatic {data-lang=glsl}
#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

layout (set=0, binding = 0) uniform camera {
    mat4 p;
    mat4 m;
} cam;

void main()
{
    gl_Position = cam.p * cam.m * vec4(pos, 1);
    fcol = col;
}
```

Because the uniform block is named `camera`, the Kotlin side writes `module.camera.p` and `module.camera.m`. The module owns this resource because the block is declared at descriptor set/binding scope, not as a per-mesh push constant.

That same name is also why the Kotlin above says `afield.four.util.Camera()` in full rather than the usual bare `Camera()`. `moduleHere()` generates a Kotlin class per uniform block, capitalising the block's name, so this box's own preamble declares a `Camera` — and a declaration in the box beats any import, including the `afield.four.util.*` that every box already gets. Writing `import afield.four.util.Camera` does *not* help: the generated `Camera` still wins, `camera.at` then fails to resolve, and the example stops compiling. Rename the uniform block and the collision goes away.

## Camera as push constants

For quick sketches, it is often simpler for the mesh to carry the camera matrices as push constants. The draw data still lives in `m`, but now `m.p` and `m.m` are assigned directly before the draw.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/02-camera-matrices.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-camera-matrices" source-box="assets/canonical_graphics/b2ba23e8-18e5-4f07-a709-2b89116a83b3" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
camera.at = vec(-8,2,7)

m.p = camera.p
m.m = camera.m

for (n in 1..80) {
    m.set(3 * n, 1 * n) {
        n {
            val z = 0.0 + it
            pos.add(0,0,-1+z, 1.0,0.1,z, 1,1,z)
            3 {
                col.add(0,it/3.0,1-it/3.0,1)
            }
            tri.add(0+3*it,1+3*it,2+3*it)
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

`KeyCamera` does not change the shader contract. It wraps a `Camera` and updates it from keyboard input while the renderer runs.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/02-keyboard-camera.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-keyboard-camera" source-box="assets/canonical_graphics/954d64d5-ee3f-4cb7-8ac7-1e2cce8a9073" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

var module = moduleHere()
var m = Mesh()

module += m named "mainMesh"
renderer += module

val camera = Camera()
m.p = camera.p
m.m = camera.m

m.set(3, 1) {
    val z = 0.0
    pos.add(0,0,0+z, 1.0,0.1,z, 1,1,z)
    tri.add(0,1,2)
}

var kc by keyCamera(camera=camera)
```

The shader is the same push-constant shape as the previous section, just without the color attribute. Camera control is ordinary Kotlin state around the same rendering contract.

## Compute shader

Compute modules use the same box model, but they dispatch workgroups instead of drawing vertices. This example writes an input buffer, dispatches a `ComputeMesh`, reads the output buffer, and prints a trace. It does not draw to the graphics window, so there is no screenshot for this section.

```diffstatic {data-lang=kotlin}
var numVec4 = 16

var module = computeModuleHere {
    ssboSizer = { ubo ->
        numVec4 * 4 * 4
    }
}

var m = ComputeMesh()
var localSize = 16
m.x = numVec4 / localSize
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
    while(true) {
        module.OutputBuffer.mapAndRead {
            var s = "${fr}:: "
            this.clear()
            for(n in 0 until 10)
                s += this.get(n).toString() + " "
            print(s)
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
    if (idx < 256)
        outData[idx] = inData[idx] * vec4(1,1,1,1) +
            vec4(3 + idx * 1, -(idx * 21.0), idx * 3, idx * 4);
}
```

`layout(local_size_x = 16)` matches the Kotlin `localSize`. `m.x`, `m.y`, and `m.z` set the dispatch dimensions. The storage-buffer names become `module.InputBuffer` and `module.OutputBuffer`, which is why Kotlin can map and read/write them directly.

## Compute particles, then draw them

The compute example above dispatched a shader and read the result back on the CPU. The more useful pattern keeps the data on the GPU: one box computes into a buffer, a *second* box draws from that same buffer, and the two are wired together through the box graph rather than through a shared global.

This is the first example that needs two boxes, so it is also the first place the graph topology matters. The compute box publishes its output buffer as a property:

```diffstatic {data-lang=kotlin}
fieldProbeHere(1000).display("probe")
displayOpen("probe")

var module = computeModuleHere {}
var m = ComputeMesh()

module += "mainMesh" to m
renderer += "module" to module

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

m.x = N / 64
m.y = 1
m.z = 1

inData.write {
    for (x in 0 until N) {
        put(vec(Math.random(), Math.random(), Math.random(), Math.random()))
    }
}
```

`prop<FrameBufferGroup>(outputPositions) = outData` is the publication. `m.something = { ... }` is a reflected push-constant member fed by a Kotlin lambda re-evaluated every dispatch, which is how the animation clock reaches the shader.

The compute shader takes both buffers as buffer references through its push-constant block, and calls `fieldProbe` to sample a value for the probe display:

```diffstatic {data-lang=glsl}
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer inData { vec4 a[]; };
layout(std430, buffer_reference) buffer outData { vec4 a[]; };

{% include "fieldProbe" %}

layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
} pc;

void main() {
    uint idx = gl_GlobalInvocationID.x;
    pc.outData.a[idx].x = pc.inData.a[idx].x*cos(pc.something*pc.inData.a[idx].z) + pc.inData.a[idx].y*sin(pc.something*pc.inData.a[idx].w);
    pc.outData.a[idx].y = -pc.inData.a[idx].x*sin(pc.something*pc.inData.a[idx].w) + pc.inData.a[idx].y*cos(pc.something*pc.inData.a[idx].z*0.03);

    fieldProbe(pc.outData.a[idx].y);

    pc.outData.a[idx].zw = pc.inData.a[idx].zw;
}
```

`{% include "fieldProbe" %}` is Field's shader templating pulling in the probe preamble — see [Field probes](field_probes.md). Templating in general is [Shader templating](#shader-templating) below.

The draw box is a **child of the compute box**, and that is the only reason `outputPositions` resolves:

```diffstatic {data-lang=kotlin}
var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()

module += "mainMesh" to m
renderer += "boo2" to module

m.inData = { outputPositions }

val N = 100000

m.aspectRatio = { window.width/window.height.toFloat() }
m.set(N*2*3, 0) {}

m
```

`m.inData = { outputPositions }` reads the parent's published property by name and binds it to this shader's `inData` buffer reference. No buffer is copied and nothing round-trips through the CPU: both boxes point at the same GPU allocation. `m.set(N*2*3, 0) {}` allocates six vertices per particle with an empty writer block, because the vertex shader synthesizes each quad's corners from `gl_VertexIndex` instead of reading vertex attributes:

```diffstatic {data-lang=glsl}
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer inData { vec4 pos[]; };

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

`gl_VertexIndex/6` picks the particle and `gl_VertexIndex%6` picks the corner — 100,000 particles drawn as 600,000 vertices with no index buffer and no per-particle CPU work.

There is no captured image for this section: it is a two-box scene, and the capture harness on this page drives one box per figure. To see it, open the corpus document in the viewer above, or replay it as a transcript — `dev_docs/agents/transcripts/threebox.yaml` is the same renderer/compute/draw shape built as a runnable three-box scene.

## Texturing

Textures add another reflected resource. The shader declares a sampler named `bbc`; Kotlin creates a `LoadedTexture` property with the same name and transfers it before drawing.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/04-texturing.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-texturing" source-box="assets/canonical_graphics/55bc1784-bc44-40eb-bc01-d02eca463445" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

prop<LoadedTexture>(bbc) = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera=camera)
m.set(4, 2) {
    val z = 0.0

    pos.add(0,0,0+z,
            1.0,0.1,z,
            1,1,z,
            0,1,z)

    tc.add(0,0,
           1,0,
           1,1,
           0,1)

    tri.add(0,1,2)
    tri.add(0,2,3)
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/04b-multi-texture.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-multi-texture" source-box="assets/canonical_graphics/3cbcadc0-b279-470d-9a5f-afc120518829" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.texture.*
val renderer: Renderer = fromContext()

var bbc = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var bbc2 = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc2.transferNow()

var bbc3 = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc3.transferNow()

var asArray : Array<ImageView> = arrayOf(bbc.imageView, bbc2.imageView, bbc3.imageView)

prop<Array<ImageView>>(ta) = asArray

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera=camera)
m.set(4, 2) {
    val z = 0.0

    pos.add(0,0,0+z,
            1.0,0.1,z,
            1,1,z,
            0,1,z)

    tc.add(0,0,
           1,0,
           1,1,
           0,1)

    tri.add(0,1,2)
    tri.add(0,2,3)
}
```

`prop<Array<ImageView>>(ta)` is the whole difference. Note that `LoadedTexture` is not itself the bindable resource — `bbc.imageView` is, which is why the array is built from image views rather than from the textures. All three entries here point at the same image file, so the array binding is what the example demonstrates, not the three pictures. The figure above is therefore pixel-identical to the texturing one by construction — same quad, same texture. What it proves is that the array *links and draws*, which is exactly what fails when the count is wrong.

The fragment shader declares the array and indexes it:

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D ta[3];

void main()
{
    vec4 c = texture(ta[0], ftc.yx);
    uFragColor = vec4(c.rgb/1.0, 1.0);
}
```

The declared array length (`ta[3]`) is the descriptor count, and the count must match **exactly**: publishing two views against `ta[3]` is refused at link time with `can't link texture resource ta of length 2 but the shader expects 3`, and the box draws nothing. That matters here because it is the state the saved lesson is in — the box in the corpus binds two views against a three-slot declaration, so running it as saved reproduces that error. Fixing it is a one-line choice: supply a third view, as above, or narrow the declaration to `ta[2]`.

## Loading a glTF model

Everything so far built its geometry in Kotlin. `gltfModelHere(path)` instead reads a glTF file and hands back a module *and* the meshes it found, with the shaders already matched to how that data is laid out:

```diffstatic {data-lang=kotlin}
var (module, meshes) =
    gltfModelHere("glTF-Sample-Models/2.0/Box/glTF/Box.gltf")

var m = Mesh()

module += "let's go" to m
renderer += "boo" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0

KeyCamera(camera=camera)
meshes[0].meshes[0].applyToMesh(m, camera)

camera.at = vec(0,0,-10)
camera.p()

loop {
    module.VertexData.modelView = camera.m()
    module.VertexData.proj = camera.p()
    frame()
}
```

Three things are new. `meshes[0].meshes[0].applyToMesh(m, camera)` copies one primitive of one model into your mesh — the outer index is the model, the inner one the primitive, so a multi-part file is addressed rather than flattened. `module.VertexData` is the reflected *uniform block*, assigned whole (`module.VertexData.proj = camera.p()`) instead of through per-member lambdas. And `camera.p.orthoness` / `camera.p.matchDepth` are Field's blend between perspective and orthographic rather than a choice between them.

The vertex shader shows what "already matched to the data" means. Rather than declaring vertex attributes, it reads a flat float array through a buffer reference and unpacks by stride:

```diffstatic {data-lang=glsl}
layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout (binding = 0) uniform VertexData {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats
    uint useVertexColor;   // bool as uint
} data;

void main()
{
    uint idx = pc.indexBuffer.indexData[data.indexOffset+uint(gl_VertexIndex)];
    uint base = data.vertexOffset + idx * data.vertexStride;

    vec3 pos = vec3(
        pc.vertexBuffer.vertexData[base + 0],
        pc.vertexBuffer.vertexData[base + 1],
        pc.vertexBuffer.vertexData[base + 2]
    );
    // … normal at +3, uv at +6, colour at +8 …
}
```

`vertexOffset`, `indexOffset` and `vertexStride` are what let every primitive in the file share one pair of buffers: the mesh selects its slice by offset instead of owning an allocation. Manual indexing (`indexData[indexOffset + gl_VertexIndex]`) rather than a bound index buffer is the same trade.

The companion lesson swaps one call for the textured variant, `gltfModelWithTextureHere(path)`, which additionally binds the file's images as a sampler array — the array-of-samplers pattern above, populated from the asset rather than by hand:

```diffstatic {data-lang=glsl}
layout(location = 2) in vec2 vUV;
layout(set = 0, binding = 1) uniform sampler2D uTextures[6];

void main()
{
   uFragColor = texture(uTextures[0], vUV);
}
```

It also shows how to place a primitive using the file's own transform: `camera.m() * meshes[0].meshes[2].bindPose.m` composes the view matrix with the primitive's bind pose, which is what you want as soon as a model has more than one part.

Neither carries a figure: both need a glTF file on disk, and the repository ships none. The paths above point at the [glTF Sample Models](https://github.com/KhronosGroup/glTF-Sample-Models) collection, which is what the saved lessons were written against — in the corpus they are absolute paths into the author's checkout, so expect to repoint them. If you want geometry from disk with assets that *are* in the tree, see [Geometry from disk](geometry_from_disk.md).

## Render to texture

Everything so far drew into the window. `RenderToTexture` gives a module somewhere else to draw: an offscreen image that a later box can sample. It is the building block for post-processing, feedback, and anything that needs to read what it just drew.

The producing box creates the target, registers it on the renderer's render list, and attaches its module to the *target* rather than to the renderer:

```diffstatic {data-lang=kotlin}
import org.lwjgl.vulkan.VK10.*

prop<RenderToTexture>(target) = RenderToTexture {
    clearFirst = false
    useDepth = true
}

renderer.renderList += "target" to target

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
target += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m
m.finalColor = { vec(Math.random(),Math.random(),Math.random(),1.0) }

// we can set this live and after the fact
target.parameters.clearFirst = true

var kc by keyCamera(camera=camera)
loop {
    m.set(3, 1) {
        val z = Math.random()*0.0
        pos.add(-3,1,-1+z, 1.0,-20.3,z, 3,3,z)
        tri.add(0,1,2)
    }
    true
}
```

Three lines carry the idea. `renderer.renderList += "target" to target` schedules the offscreen pass. `target += "boo" to module` puts the drawing inside that pass instead of on screen. And `target.parameters.clearFirst = true` shows that the target's parameters stay live — you can flip them from the editor while the loop is running, which is the point of doing this in Field rather than in a build.

The consuming box is a **child** of the producer, and that is the whole binding mechanism:

```diffstatic {data-lang=kotlin}
prop<RenderToTexture>(bbc) = target

var module = moduleHere()
var mesh = Mesh()
module += "mainMesh" to mesh
renderer += "boo" to module

val camera = Camera()
mesh.p = camera.p
mesh.m = camera.m

var kc by keyCamera(camera=camera)
mesh.set(4, 2) {
    val z = 0.0
    pos.add(-1,-1, z,
            1.0,-1.0,z,
            1,1,z,
            -1.0, 1, z)
    tc.add(0,0, 1,0, 1,1, 0,1)
    tri.add(0,1,2)
    tri.add(0,2,3)
}
```

`prop<RenderToTexture>(bbc) = target` reads `target` — the parent's property, visible by name because this box is a child — and republishes it as `bbc`, because *this* box's fragment shader declares `sampler2D bbc`. Renaming on republication is what makes it general: the resource is identified by the reflected name the consuming shader uses, so a box can bind any upstream target to whatever its own shader happens to call it.

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

Compare that with the texturing fragment shader: it is the same shader. From the consumer's side there is no difference between sampling a file loaded from disk and sampling the output of another box — which is why the two boxes in this pair are the smallest complete statement of how `afield.four` composes.

Both boxes together are one scene, so there is no single-box figure for it here; the graphics-scene transcripts `threebox` and `spin_glass` in [agent transcripts](agent_transcripts.md) are runnable versions of the same producer/consumer shape.

## Ping-pong render to texture

A target you can read *and* write in the same pass is a contradiction, so feedback needs two images with the roles swapped every frame. `PingPongRenderToTexture` does that bookkeeping:

```diffstatic {data-lang=kotlin}
import org.lwjgl.vulkan.VK10.*
import afield.four.targets.*
import afield.four.texture.*

prop<PingPongRenderToTexture>(target) = PingPongRenderToTexture {
    clearFirst = false
    useDepth = false
}

prop<HasImageView>(otherView) = target.otherImageView

renderer.renderList += "target" to target

var module = moduleHere {
    target.apply()
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
    vec4 c = texture(otherView, tc.xy + vec2(0.001));

    uFragColor = vec4(c.gr+vec2(0.001),1,1);
}
```

Reading `otherView` at a slightly offset coordinate and writing the channels back swapped (`c.gr`) is what makes the image drift and recolour over time rather than settle. The consuming box then displays `target.thisImageView`, the half that was just written:

```diffstatic {data-lang=kotlin}
import afield.four.texture.*

prop<HasImageView>(bbc) = target.thisImageView
```

Note the property type here is `HasImageView`, not `RenderToTexture`. A ping-pong target is not one image, so the two halves are handed out individually, and `HasImageView` is the interface that any bindable image satisfies. `module { target.apply() }` inside the module builder is what matches the module's pipeline state — format, sample count, attachment layout — to the target it draws into; without it the pipeline and the target disagree.

## A blur kernel across three boxes

The blur chain is the longest topology in the corpus — `window → 5. Bluring → 5. kernel → 5. draw blur` — and it reads best as a whole, because each box does exactly one thing.

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
    clearFirst = true
    useDepth = false
    mipLevels = 10
    colorLayerCount = 2
    colorAttachments = 2
}

renderer.renderList += "b1" to b1

var module = moduleHere {
    primitiveType = triangle_strip
    depth = 0
    b1.apply()
}

var m = Mesh()
module += "mainMesh" to m
b1 += "boo" to module

m.radius = { 10.0f }
m.sigma = { 10.0f }
m.resize(4)
```

`m.resize(4)` with no `set` block and `primitiveType = triangle_strip` is the fullscreen-quad idiom: four vertices, positions synthesized in the vertex shader from `gl_VertexIndex`, no vertex attributes at all. `m.radius` and `m.sigma` are reflected push-constant members, so the kernel's parameters are ordinary Kotlin lambdas you can edit live.

The interesting choice is in the vertex shader, which computes the Gaussian weights *per vertex* — four invocations instead of one per pixel — and passes them down as interpolants:

```diffstatic {data-lang=glsl}
float gauss(float x, float sigma) {
    float s2 = sigma * sigma;
    return exp(-x * x / (2.0 * s2));
}

void main()
{
    float r1 = blur.radius * 0.275;   // near
    float r2 = blur.radius * 0.65;    // mid
    float r3 = blur.radius * 0.95;    // far

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

and samples it as a `sampler2DArray`, which is what a layered target reflects as:

```diffstatic {data-lang=glsl}
layout(set=0, binding = 0) uniform sampler2DArray bbc;
layout(set=0, binding = 1) uniform sampler2DArray back;

void main()
{
    vec4 c = texture(back, vec3(ftc.yx,1.0), 0.0);
    uFragColor = vec4(c.rgb, 1.0);
}
```

## Shader includes

Shader properties are templated before they reach the compiler, and `{% include "name" %}` pulls in another property by name. `5. included` shares its attribute and push-constant declarations with a sibling box:

```diffstatic {data-lang=glsl}
#version 450

{% include "vertex_included" %}

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

Reflection happens *after* the include is expanded, so `pos`, `col`, `p` and `m` still produce the generated Kotlin writers — a shared header does not cost you the reflected API. There is no figure for this pair either: the include resolves against a box that has to exist alongside it, so the fence on this page has nothing to include.

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

Note that `#include` here is the C-style directive handled by the shader compiler's include resolver, while `{% include %}` above is Field's own templating over box properties — two different mechanisms that happen to share a name. This example needs a Lygia checkout on disk, which is why it carries no figure.

## Shader templating

Beyond includes, a shader property is a template with values supplied from Kotlin. `shaderTemplate["col"]` publishes a map of `Resolver`s, and the shader substitutes them with `{{ … }}`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/05b-templating.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-templating" source-box="assets/canonical_graphics/1103f132-7484-4440-8102-5bd159a29fbf" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

// we can define this first
shaderTemplate["col"] = mapOf("fixedColor" to Resolver { "vec4(1,0.75,0,1)" } )

// then there's an execution break here
//%-
var module = moduleHere {
    primitiveType = triangle_strip
}

var m = Mesh()

module += m named "my mesh"
renderer += module

m.set(3, 3) {
    pos.add(0,0,0, 0.5,0,0, 0.5,0.5,0)
    tri.add(0,1,2)
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

A `Resolver` is a lambda returning GLSL *source*, not a value: the string is spliced in before compilation, so `{{ col.fixedColor }}` becomes a compile-time constant rather than a uniform read. That is the trade — changing it recompiles the shader, and in exchange the optimiser sees a literal.

In the corpus this box also carries a `//%-` line, which is Field's execution break: everything above it runs once when the box is first evaluated, and the part below is what re-runs. Declaring the template before the break means the substitution is registered before `moduleHere` reads the shader.

## Generated push-constant blocks

Templating gets more interesting when the substituted source is itself generated from a Kotlin type. `PushConstants<T>` reflects a Kotlin class into a GLSL push-constant declaration:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/05c-push-constants.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-push-constants" source-box="assets/canonical_graphics/42056e8d-ec8b-4bf8-b84a-1cd1d8524510" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.util.*
val renderer: Renderer = fromContext()

class MyPC(
    var a : Vec4,
    var b : Vec4,
    var c : Vec4
) : PushConstants<MyPC>()

var pc = MyPC(vec(1,0,0,1),vec(1,2,3,4),vec(1,2,3,4))

shaderTemplate["pc"] = mapOf("dec" to Resolver { pc.glsl() })

pc.a.x=0.3f
pc.otherDeclarations = "layout(offset=${pc.nextFreeOffset(16)}) uint foo;"

// then there's an execution break here
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
    pos.add(0,0,0, 0.5,0,0, 0.5,0.5,0)
    tri.add(0,1,2)
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

`pc.glsl()` emits the block declaration; `{{ pc.dec }}` splices it in; `m.push.bindAll { pc }` binds the whole Kotlin object to it in one call rather than member by member; and `loadRegs()` plus the accessor `a()` are part of the generated preamble, which is why the shader reads `a()` rather than `pc.a`. `nextFreeOffset(16)` computes where a hand-written extra member can sit without colliding with the generated layout — the escape hatch for the case the generator does not cover.

## Debugging: printf from a shader

Vulkan can print from inside a shader. Enable the extension, guard the call so it does not fire for every invocation, and the message arrives through the validation layer:

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/06-printf.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true embed=true name="four-printf" source-box="assets/canonical_graphics/81f27655-7d32-4a4d-b73d-1b5d368efaa7" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
val renderer: Renderer = fromContext()

var module = moduleHere { primitiveType = triangles }

var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}.buffer

m.vertexData = { vertexData }

vertexData.write {
    put(vec(0,0,0))
    put(vec(1,0,0))
    put(vec(0,1,0))
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

    if (gl_VertexIndex == 10) {
        debugPrintfEXT("VS: index=%d, pos=(%f,%f)",gl_VertexIndex, pos.x, pos.y);
    }
}
```

Two things are required. `#extension GL_EXT_debug_printf : enable` has to be there, and printing needs validation layers switched on (`Options.validation`) — without them the call compiles and silently does nothing. And the guard matters: `debugPrintfEXT` with no condition in a shader running over a million invocations will flood the log and stall the frame. Note the guard in this example (`gl_VertexIndex == 10`) never fires for a three-vertex draw, which is the state the lesson is saved in: the plumbing is there to be switched on by changing the condition.

## Debugging: texture probes

The other debugging tool goes the other way — instead of printing text, it samples a value out of a running shader and draws it. `fieldProbeHere()` creates the probe and `{% include "fieldProbe" %}` brings the shader side in:

```diffstatic {data-lang=kotlin}
prop<LoadedTexture>(bbc) = LoadedTexture("bbc.jpg")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

val probe = fieldProbeHere().display("probe")

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera=camera)
val n = 100
m.set(4*n, 2*n) {
    n {
        val z = it+0.0

        pos.add(0,0,0+z,
                1.0,0.1,z,
                1,1,z,
            0,1,z)

        tc.add(0,0,
            1,0,
            1,1,
            0,1)

        val b = it*4
        tri.add(b+0,b+1,b+2)
        tri.add(b+0,b+2,b+3)
    }
}
```

```diffstatic {data-lang=glsl}
#version 450

{% include "fieldProbe" %}

// …

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);

    fieldProbe(gl_Position.xyz/gl_Position.w);

    ftc = tc;
}
```

`fieldProbe(…)` writes the sampled value into a buffer the probe owns, and `.display("probe")` puts the readback in the box's output area. Probing the post-projection position, as here, is how you check a transform is doing what you think without reasoning backwards from the picture. `n = 100` stacked quads make it worth probing: 400 vertices where a wrong `p` or `m` is otherwise just a blank window. See [Field probes](field_probes.md) for the readback side.

A second probe lesson (`6b. debugging / mapping - Texture probe example`) goes further and warps the sampled distribution before display — `AdaptiveQuantileWarp` and `QuantileWarpConfig` — which is what you reach for when the interesting variation is buried in a narrow part of the range.

There is no figure here, and there should not be. The probe's value is the readback, and `.display("probe")` puts that in the *box's* output area, not in the Vulkan window — so a native-window capture of this example shows only the textured quads, pixel-identical to the texturing figure, and would suggest the probe produced nothing. The quads also stack along z, so from the camera they land on top of one another and 100 of them look like one.

## Streaming a target over the network

`FieldRTTStreamer` turns a render target into an encoded video stream, which is how a Field sketch drives a display it is not attached to. Its largest consumer is the Vision Pro front end — see **[Apple Vision Pro](vision.md)**, which covers the stereo side-by-side layout, the media wire's pose tail, and `tagPosesFrom` for stamping a frame with the head pose it was rendered from:

```diffstatic {data-lang=kotlin}
import org.lwjgl.vulkan.VK10.*
import afield.four.stream.FieldRTTStreamer

// Streamer allocates + imports the surface and hands back the RTT to render into.

prop<FieldRTTStreamer>(streamer)
once.streamer {
     = FieldRTTStreamer(renderer, dest = "127.0.0.1:9001", width = 1280, height = 720, fps = 15)
}

@exported
val target = streamer.start().first()

renderer.renderList += "target" to target

var module = moduleHere { target.apply() }
var m = Mesh()
module += "mainMesh" to m
target += "boo" to module
```

The shape is exactly the `RenderToTexture` producer from earlier — `renderList +=`, `target +=`, `target.apply()` — with the target supplied by the streamer instead of constructed directly. `once.streamer { … }` is the live-coding guard: re-running the box re-runs everything except the block that allocated the stream, so you can edit the drawing without tearing down the connection. `@exported` publishes `target` for child boxes.

There is no figure: the picture this produces arrives at a receiver on `127.0.0.1:9001`, not in a window.

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
}.buffer

sourceBuffer.write {
    200 {
        putInt(2000-it)
    }
}

renderer.renderList.others["sort"] = Target { command, index ->
    sorter.sort(command, sourceBuffer, 200)
}

sourceBuffer.read_ints {
    _print(this.get(0))
}
```

Two things to take from it. The `buffer { … }` builder spells out usage explicitly — `storage()`, `addressable()` (needed for buffer references), `hosted()` (CPU-visible), `dest()` (a transfer destination) — and those flags are what decide whether a given access is legal later. And `renderList.others["sort"] = Target { command, index -> … }` is the general escape hatch: a slot on the frame where you get the command buffer and can record whatever Vulkan work you like. `sorter.sort(command, …)` records into that buffer rather than executing immediately.

Note `hosted()` for readback. For a GPU→CPU read that happens every frame, host-cached memory is the right choice instead — see the readback notes in [Field probes](field_probes.md).

There is no figure: this box sorts and prints, and draws nothing.

## Slang

The same box model accepts [Slang](https://shader-slang.org) instead of GLSL through the `vertex_fragment` property, which holds both stages in one file. The Kotlin side does not change at all — modules, meshes, buffers and reflected accessors work the same way, because reflection reads the compiled shader interface rather than the source language.

```diffstatic {data-lang=kotlin}
var module = moduleHere { primitiveType = triangles }

var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}.buffer

m.vertexData = { vertexData }

var t = 0.0
fun polar(t : Double, r : Double) = vec(Math.sin(t), Math.cos(t)) * r.f

loop {
    t += 0.01
    var num = 0

    var numVertToWrite = (Math.sin(t*0.1)*20 + 30).i

    vertexData.write {
        for(x in 0 until numVertToWrite) {
            val offset = vec(x*0.02, 0, 0)
            put(vec(polar(t, 0.5), 0) + offset)
            put(vec(polar(t+0.4, 0.4), 0) + offset)
            put(vec(polar(t+0.9, 0.3), 0) + offset)

            num += 3
        }
    }

    m.resize(num)
    true
}
```

The corpus box for this lesson (`7. slang — hello triangle`) is also the best-commented file in the collection, and is written as a tutorial — open it directly and it makes two points in passing that generalise beyond Slang. First, buffer sizes are a guess that Field will correct: `size = 3.vec4s` is set too small there, and the buffer resizes dynamically rather than failing. Second, `perFrame = true` allocates one buffer per frame in flight, so a write never has to wait on the GPU to finish reading.

A second Slang lesson (`7b. slang — Texture`) is the same step again with a texture bound, and it makes the language-independence claim concrete: the Kotlin is the texturing code from earlier, unchanged, `prop<LoadedTexture>` and all, because the resource is matched by reflected name and the name survives the change of shader language.

Slang requires `slangc` to be available; without it the `vertex_fragment` property cannot be compiled, which is why neither carries a figure.

## Diffusion-limited aggregation

The last lesson is the shortest, because the work is packaged:

```diffstatic {data-lang=kotlin}
import afield.stdlib.*

var dla = ShaderDlaSystem() {
    width = window.width
    height = window.height
    particleCount = 1_000_000
}

// this installs editable shaders
dla.installInto()
```

`installInto()` is where the page's argument lands. It writes the system's shaders into *this box's* shader properties, so a packaged effect arrives as source you can edit rather than as a closed component. The vertex shader it installs is a million-particle random walk that reads the accumulated image as a texture, steps each walker, sticks it when it touches something solid, and writes the particle state back through a buffer reference — simulation and drawing in a single vertex shader, with no compute pass at all:

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
    } else {
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
        } else {
            positionPx = clamp(positionPx + randomWalkStep(rng, push.walkStepPx), vec2(0.0), maxPos);
        }
    }

    push.particleBuffer.particles[gl_VertexIndex].positionPx = positionPx;
    // …
}
```

`push.walkStepPx`, `push.solidThreshold`, `push.stuckRed` and the rest are reflected push-constant members, so every constant in the simulation is a Kotlin lambda away from being animated. This is the payoff of the reflected-interface contract this page opened with: at a million particles the shader is still the thing you edit.

No figure, and the reason is a property of DLA rather than of Field. Aggregation is a race between walker density and canvas area, and the legible window between the two failure modes is narrow. Captured as written — a million walkers over a 4112×2524 window — eight seconds and 600 frames grow a cluster a few pixels across on a field of near-black walkers: correct, and invisible at page scale. Shrink the canvas to 900×560 to raise density and every walker is adjacent to something solid on the first step, so the whole field sticks at once and the picture is uniform magenta noise. A good still needs a density between those and more frames than a docs build should spend, so it is left to run live.

## Where these examples come from

Every example on this page is a box in a real Field document, `canonical_graphics`, which is
committed under `public/docs/assets/canonical_graphics/` and is what the viewer at the top of this
page is showing. If you want to read the code rather than the prose — or you are an agent about to
write `afield.four` code and want a known-good pattern to adapt — start from
[the lesson index](assets/canonical_graphics/LESSONS.md). It lists every lesson with its Kotlin and
shader files, records the parent chain each one needs in order to run, and carries a reverse index
from Field API name (`RenderToTexture`, `LoadedTexture`, `computeModuleHere`, `GpuRadixSort`, …) to
the lessons that use it.

These are working notes rather than a polished sample pack: the boxes are a working document, and a
few of them are mid-thought. Where running a lesson as saved does not do what its name suggests,
this page says so — the sampler-array count in the multi-texture lesson and the never-firing printf
guard are both noted above rather than silently corrected.

## Which sections have figures

The images on this page are captures: the docs build runs the box against a live Vulkan renderer
and screenshots the native window. Sections without a figure are the ones a single box
cannot produce on its own, and the reason is stated in each:

- **Two-box scenes** — render-to-texture, ping-pong, the blur chain, compute-then-draw particles.
  The capture harness drives one box per figure; these need a producer and a consumer wired
  together. The `threebox` and `spin_glass` [agent transcripts](agent_transcripts.md) are runnable
  versions of that shape.
- **Environment prerequisites** — Slang needs `slangc`, Lygia needs its shader tree on disk, and
  the two glTF lessons need a model file the repository does not ship.
- **Nothing to look at** — the radix sort prints, and the RTT streamer sends its picture to a
  network receiver.
- **Not in the Vulkan window** — the texture probe's answer is a readback shown in the box's output
  area, and the shader `#include` example needs a sibling box to include from.
- **Wrong timescale** — the DLA aggregate needs longer to become legible than a docs build should
  spend, in a density band narrower than either default; the DLA section explains both failure modes.

## Fabricate Renderer

These examples cover the low-level pieces: modules, meshes, buffers, cameras, compute dispatch,
textures, offscreen targets, shader includes and templating, and the debugging tools. Once that
contract is clear, continue with [Fabricate Renderer](fabricate_renderer.md) for the higher-level
scene harness, material parameters, shadows, and ambient occlusion captures.
