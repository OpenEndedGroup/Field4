# afield.four Graphics

`afield.four` is Field's direct Vulkan path. A graphics box usually has three parts:

- Kotlin code that creates modules, meshes, buffers, cameras, textures, and renderer connections.
- Shader properties such as `vertex`, `fragment`, `compute`, or `vertex_fragment`.
- Reflected accessors that Field generates by reading the shader interface.

That last part is the trick worth learning. If a vertex shader declares `layout(location = 0) in vec3 pos`, the Kotlin side can write `pos.add(...)` inside `m.set { ... }`. If a shader declares a push constant member named `vertexData`, the Kotlin side can assign `m.vertexData = { vertexData }`. The shader is not just code that runs on the GPU; it is also the contract that shapes the Kotlin API for that box.

<iframe
  src="/offline.html?file=/field4/assets/canonical_graphics/metadata.edn"
  title="Canonical Graphics Offline Viewer"
  style="width:100%; height:640px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>

## 0. Traditional Triangle

Start with one shader module and one mesh. `moduleHere { ... }` creates a shader module from the current box's shader properties. `Mesh()` creates draw data. `module += m named "my mesh"` attaches the mesh to the module, and `renderer += module` makes the module part of the window.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/00-traditional-triangle.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" graphics-context=true embed=true force=true name="four-traditional-triangle" macro-file="../../dev_docs/agents/transcripts/afield_four_traditional_triangle.yaml" capture-box="agent_four_traditional_triangle"}
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

The first example is deliberately small: vertex data is written directly into the mesh, the shader reads it as vertex attributes, and the fragment shader colors the pixels.

## 1. Buffer-Backed Triangles

The first triangle put `pos` and `col` directly into the mesh's vertex buffers. Sometimes you want a buffer that is visible as an ordinary named resource in the shader: perhaps the geometry changes every frame, perhaps a compute shader fills it, or perhaps several draws share it. This example uses the simplest version of that pattern: write a few positions into a `defaultBuffer`, bind that buffer to a reflected push-constant member, and draw vertices by index.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/01-buffer-triangles.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" graphics-context=true embed=true name="four-buffer-triangles" source-box="assets/canonical_graphics/af0946dd-f25d-4355-984d-f0d24a43ee12" source-box-properties="vertex,fragment" source-box-wait="0.1"}
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

## 1b. Camera Matrices In A UBO

The previous two examples draw in clip-space coordinates. To draw from a camera, the shader needs projection and view matrices. This version stores those matrices in a uniform buffer owned by the module.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/01b-ubo-camera.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" graphics-context=true embed=true name="four-ubo-camera" source-box="assets/canonical_graphics/85e8478b-529b-406e-b835-7c00912848f2" source-box-properties="vertex,fragment" source-box-wait="0.1"}
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

## 2. Camera As Push Constants

For quick sketches, it is often simpler for the mesh to carry the camera matrices as push constants. The draw data still lives in `m`, but now `m.p` and `m.m` are assigned directly before the draw.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/02-camera-matrices.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" graphics-context=true embed=true name="four-camera-matrices" source-box="assets/canonical_graphics/b2ba23e8-18e5-4f07-a709-2b89116a83b3" source-box-properties="vertex,fragment" source-box-wait="0.1"}
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

## 2b. Keyboard Camera

`KeyCamera` does not change the shader contract. It wraps a `Camera` and updates it from keyboard input while the renderer runs.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/02-keyboard-camera.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" graphics-context=true embed=true name="four-keyboard-camera" source-box="assets/canonical_graphics/954d64d5-ee3f-4cb7-8ac7-1e2cce8a9073" source-box-properties="vertex,fragment" source-box-wait="0.1"}
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

var kc = KeyCamera(camera=camera)
```

The shader is the same push-constant shape as the previous section, just without the color attribute. The lesson is that camera control is ordinary Kotlin state around the same rendering contract.

## 3. Compute Shader

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

## 4. Texturing

Textures add another reflected resource. The shader declares a sampler named `bbc`; Kotlin creates a `LoadedTexture` property with the same name and transfers it before drawing.

```diffstatic_runner {data-lang=kotlin screenshot="assets/afield_four_graphics/04-texturing.png" wait="0.25" timeout="10" capture="native-window" native-wait-frames="4" graphics-context=true embed=true name="four-texturing" source-box="assets/canonical_graphics/55bc1784-bc44-40eb-bc01-d02eca463445" source-box-properties="vertex,fragment" source-box-wait="0.1"}
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

var kc = KeyCamera(camera=camera)
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

## Shader Language Note

The examples above use GLSL because it is the shortest way to see the reflected interface. The same box model also supports Slang through the `vertex_fragment` shader property when `slangc` is available: the Kotlin still creates modules, meshes, buffers, and textures, while the shader source changes language.

## Fabricate Renderer

These examples cover the low-level pieces: modules, meshes, buffers, cameras, compute dispatch, and textures. Once that contract is clear, continue with [Fabricate Renderer](fabricate_renderer.md) for the higher-level scene harness, material parameters, shadows, and ambient occlusion captures.
