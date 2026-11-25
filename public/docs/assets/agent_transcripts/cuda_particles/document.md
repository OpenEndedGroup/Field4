# Field Document: cuda_particles

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="cuda_renderer" index="0" -->

```edn [field-box id="cuda_renderer" role="properties"]
{"id" "cuda_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["cuda_flow"] "properties" {"children" #{#afield/box "cuda_flow"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cuda_renderer_code_.kt" "lines" 10 "characters" 222 "sha256" "0be5fac9c7f639379667e24ce0233269befb5bf7b6d84cef6c16964a7c6fd132"}]}
```

```kotlin [field-property box="cuda_renderer" name="code" path="cuda_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · CUDA particles")
    Renderer(window)
}
start(renderer::run)
```

## 2. CUDA flow

<!-- field-box id="cuda_flow" index="1" -->

```edn [field-box id="cuda_flow" role="properties"]
{"id" "cuda_flow" "index" 1 "name" "CUDA flow" "parents" ["root" "cuda_renderer"] "children" ["cuda_draw"] "properties" {"children" #{#afield/box "cuda_draw"} "parents" #{#afield/box "root" #afield/box "cuda_renderer"} "name" "CUDA flow" "_tombstone" false "frame" #afield/rect [360.0 180.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cuda_flow_code_.kt" "lines" 59 "characters" 2084 "sha256" "ee226bae75d3f5fa70ed654790c2e0407687a0b8bb7a71eea0d247e7875b3185"}]}
```

```kotlin [field-property box="cuda_flow" name="code" path="cuda_flow_code_.kt"]
import afield.cuda.CudaVulkan

// One CUDA context per renderer, on the same GPU (matched by UUID).
val cuda = CudaVulkan.forRenderer(renderer)

// Each particle is a float4: x, y in world units (about -2..2), a phase, and a size.
val count = 200_000
val particles = cuda.sharedBuffer("particles", count * 16L)

// CUDA C, compiled for this GPU by NVRTC when the box runs.
val program =
    cuda.compile(
        """
    extern "C" __global__ void seed(float4* p, int n) {
        int i = blockIdx.x * blockDim.x + threadIdx.x;
        if (i >= n) return;
        unsigned h = i * 747796405u + 2891336453u;
        h = ((h >> ((h >> 28) + 4u)) ^ h) * 277803737u;
        h = (h >> 22) ^ h;
        float a = (h & 0xffffu) / 65535.0f * 6.2831853f;
        float r = 2.0f * sqrtf((h >> 16) / 65535.0f);
        p[i] = make_float4(r * cosf(a), r * sinf(a), a, 0.4f + 0.3f * r);
    }

    // Advect every particle through a flow that turns slowly with t.
    extern "C" __global__ void flow(float4* p, int n, float t, float dt) {
        int i = blockIdx.x * blockDim.x + threadIdx.x;
        if (i >= n) return;
        float4 q = p[i];
        float u = sinf(2.5f * q.y + t) - 0.35f * q.x;
        float v = cosf(2.5f * q.x - 0.7f * t + 0.3f * q.z) - 0.35f * q.y;
        q.x += dt * u;
        q.y += dt * v;
        p[i] = q;
    }
    """,
    )
val seed = program.function("seed")
val flow = program.function("flow")
val threads = 256
val blocks = (count + threads - 1) / threads

// Scatter the particles once, then move them between every two frames.
cuda.run { stream -> seed.launch(blocks, threads, stream, particles.pointer, count) }
var t = 0f
val dt = 1f / 60f
cuda.everyFrame("flow") { stream ->
    t += dt
    flow.launch(blocks, threads, stream, particles.pointer, count, t, dt)
}

// The draw box beneath this one reads the same memory.
prop<VulkanBuffer>(cudaParticles) = particles.buffer
prop<Int>(cudaParticleCount) = count

frames {
    repeat(600) { frame() }
    println("cuda particles running on ${cuda.device.name}: ${cuda.framesWithCuda} frames with CUDA")
}
```

## 3. draw particles

<!-- field-box id="cuda_draw" index="2" -->

```edn [field-box id="cuda_draw" role="properties"]
{"id" "cuda_draw" "index" 2 "name" "draw particles" "parents" ["root" "cuda_flow"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cuda_flow"} "name" "draw particles" "_tombstone" false "frame" #afield/rect [680.0 320.0 300.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "cuda_draw_vertex_.glsl" "lines" 32 "characters" 835 "sha256" "ceba45314a14bf415f4037b3d84a0222ffddfb0614509e8508d0b1a23f044fd0"} {"name" "fragment" "language" "glsl" "path" "cuda_draw_fragment_.glsl" "lines" 13 "characters" 440 "sha256" "dd101111b7bb099431ec1872abb3aa807bdbc84543ce89db7dd80a9e7ee3a5f5"} {"name" "code" "language" "kotlin" "path" "cuda_draw_code_.kt" "lines" 15 "characters" 365 "sha256" "ca96505a7039ae67422881762801868f0df484011ae698513cefea4992581ab3"}]}
```

```glsl [field-property box="cuda_draw" name="vertex" path="cuda_draw_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// The CUDA box's buffer: xy position in world units, z phase, w size.
layout(std430, buffer_reference) buffer Particles
{
    vec4 p[];
};

layout(location = 0) out vec2 tc;
layout(location = 1) out float phase;

layout(push_constant) uniform push {
    Particles particles; // shared CUDA/Vulkan float4 buffer
    float aspectRatio; // window width / height
} pc;

// The six corners of a unit square, as two triangles
const vec2[6] corner = {
    vec2(0, 0), vec2(1, 0), vec2(1, 1),
    vec2(0, 0), vec2(1, 1), vec2(0, 1)
};

void main()
{
    vec4 q = pc.particles.p[gl_VertexIndex / 6];
    vec2 c = corner[gl_VertexIndex % 6];
    vec2 xy = q.xy * 0.42 + (c - 0.5) * 0.006 * q.w;
    gl_Position = vec4(xy.x / pc.aspectRatio, xy.y, 0, 1);
    tc = c;
    phase = q.z;
}
```

```glsl [field-property box="cuda_draw" name="fragment" path="cuda_draw_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
layout(location = 1) in float phase;
void main()
{
    // A soft round dot, tinted by the particle's starting angle.
    float d = length(tc - vec2(0.5));
    float coverage = 1.0 - smoothstep(0.25, 0.5, d);
    if (coverage < 0.01) discard;
    vec3 tint = 0.6 + 0.4 * cos(phase + vec3(0.0, 2.1, 4.2));
    uFragColor = vec4(tint, coverage * 0.35);
}
```

```kotlin [field-property box="cuda_draw" name="code" path="cuda_draw_code_.kt"]
var module = moduleHere {
    primitiveType = triangles
}
var m = Mesh()
module += "particles" to m
renderer += "cuda particles" to module

// Published by the CUDA box above with `prop`.
m.particles = { cudaParticles }
m.aspectRatio = { window.width / window.height.toFloat() }

// Six vertices per particle, no index buffer.
m.set(cudaParticleCount * 6, 0) {}

m
```

