# Field Document: rr_plumbing

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["rr_plumbing"] "properties" {"children" #{#afield/box "rr_plumbing"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 300 "sha256" "ed9c90e9909e15814bcf04d30e27c778e23b8bdb27e200771a092e766fb4a29b"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableVR = false
Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. rr plumbing

<!-- field-box id="rr_plumbing" index="1" -->

```edn [field-box id="rr_plumbing" role="properties"]
{"id" "rr_plumbing" "index" 1 "name" "rr plumbing" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "rr plumbing" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rr_plumbing_code_.kt" "lines" 155 "characters" 5913 "sha256" "5d92848563fc7101e1370f739094dfe5183fc9178778ca27548eb55eb395c27d"} {"name" "vertex" "language" "glsl" "path" "rr_plumbing_vertex_.glsl" "lines" 5 "characters" 119 "sha256" "f5f928935456f7b96462d2257491aa9db6746aea0bb37e84df323e8399a26dc9"} {"name" "fragment" "language" "glsl" "path" "rr_plumbing_fragment_.glsl" "lines" 35 "characters" 1459 "sha256" "fc8dfabf4148c7534aac7bf9bbb2a95c879201c90c3c4a316fe90352106611aa"}]}
```

```kotlin [field-property box="rr_plumbing" name="code" path="rr_plumbing_code_.kt"]
import afield.graphics.render.lighting.*
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.media.reconstruction.*
import org.lwjgl.vulkan.VK13.*

// This guide conversion describes the existing opaque Lambertian RTXDI bridge.
// It is not a template for inventing missing glossy or transmission guides.
NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow()
prop<AutoCloseable>(plumbing)
fromContextWithNameOrNull<AutoCloseable>("plumbing")?.close()
val size = ReconstructionSize(1920, 1080)
prop<DirectLightingDemo>(scene)
scene = DirectLightingDemo(renderer, size.width, size.height)
val settings =
    RtxdiSettings({ scene.bundle.scene.camera.modelView() }, { scene.bundle.scene.projection.evaluateUniform() })
        .apply {
            temporal = true
            spatialSamples = 4
            denoisePasses = 0
        }
scene.lighting.enableRtxdi(RtxdiProgram.load(), settings).getOrThrow()
scene.lighting.mode = DirectLighting.Mode.RTXDI
scene.lighting.samples = 8
val rr = DlssReconstruction(renderer, size, size, ReconstructionMode.DLAA, rayReconstruction = true)
prop<RenderToTexture>(guides)
guides = RenderToTexture(renderer) {
    width = size.width
    height = size.height
    useDepth = false
    colorAttachments = 5
    colorFormats = [
        VK_FORMAT_R32_SFLOAT,
        VK_FORMAT_R32G32_SFLOAT,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R16G16B16A16_SFLOAT,
    ]
}
val sampler = TextureSampler(renderer, 1, false, filter = VK_FILTER_NEAREST)
val surfaces = scene.lighting.surfaces
val inputs = mapOf(
    "positions" to surfaces.position,
    "normals" to surfaces.normal,
    "albedo" to surfaces.albedo,
    "velocity" to surfaces.motion,
)
val guideModule = moduleHere {
    +guides
    depthTest = false
    depthWrite = false
    blend = false
    textureResolver = { texture -> inputs[texture.name]?.let { fromViews([it], texture.name, sampler) } }
}
val quad = Mesh(renderer, guideModule)
quad.resize(3, 0, guideModule)
guideModule += "convert" to quad
guides += "guides" to guideModule

// Remove independent scheduling: this pass now owns their order.
scene.bundle.frameGraph().passNames().forEach { renderer.renderList.preTargets.remove(it) }
renderer.renderList.preTargets.remove(scene.lighting.name)
renderer.renderList.modules.remove("direct-light-demo")
var bypass = false
var animate = true
var cut = false
var time = 0.0
var frameIndex = 0L
var revision = 0L
var shaderVersion = guideModule.current.fi
val pass = object : Target {
    override fun render(command: CommandBuffer, index: Int) {
        if (shaderVersion !== guideModule.current.fi) {
            shaderVersion = guideModule.current.fi
            revision++
            rr.reset("guide edit")
        }
        val jitter =
            if (bypass) ReconstructionJitter(0f, 0f) else ReconstructionJitter.halton(frameIndex, size, size)
        scene.bundle.scene.rasterJitterNdc.set(2 * jitter.x / size.width, -2 * jitter.y / size.height)
        scene.animate(time)
        if (animate) time += 1.0 / 60
        settings.commitLights([IdentifiedAreaLight(1, scene.lighting.emitter)])

        scene.bundle.gBuffer.render(command, index) // geometry, unjittered motion
        scene.lighting.render(command, index) // raw RTXDI RGB
        guides.render(command, index) // moduleHere format / convention conversion
        if (!bypass)
            rr.evaluate(
                command,
                ReconstructionInputs(scene.lighting.output, guides.imageViewArray[0], guides.imageViewArray[1]),
                ReconstructionFrame(frameIndex, jitter, revision = revision),
                RayReconstructionGuides(
                    guides.imageViewArray[2],
                    guides.imageViewArray[3],
                    guides.imageViewArray[4],
                    scene.bundle.scene.camera.modelView(),
                    scene.bundle.scene.projection.evaluateUniform(),
                ),
            )
        frameIndex++
    }
}
renderer.renderList.preTargets["RR plumbing"] = pass
rr.output.blitToScreen(name = "RR plumbing")
// Capture this allocation, not the mutable box prop that a rerun replaces.
val ownedTarget = guides
val owner = renderer // Cleanup must not depend on a still-running parent box.
var closed = false
plumbing = AutoCloseable {
    if (!closed) {
        closed = true
        owner.renderList.preTargets.remove("RR plumbing", pass)
        owner.renderList.modules.remove("RR plumbing")
        rr.close()
        scene.close()
        owner.cleanup += Runnable {
            guideModule.current.cleanup()
            sampler.cleanup()
            ownedTarget.imageViewArray.forEach { it.imageView.cleanup() }
            ownedTarget.colorImageRefs.forEach { it?.cleanup() }
            ownedTarget.defaultSampler?.cleanup()
        }
    }
}
controlGroup("RTXDI to RR") {
    checkbox("Bypass to raw lighting", initialValue = false) { bypass = it }
    checkbox("Move light", initialValue = true) { animate = it }
    button("Cut / reset history") { if (it) cut = true }
}
displayOpen("RTXDI to RR")
println(
    "Read render(): G-buffer → raw RTXDI → five guide attachments → RR → screen. Edit fragment to inspect the guide mapping.",
)
frames {
    var wasBypass = bypass
    while (true) {
        if (wasBypass != bypass) {
            rr.reset("bypass changed")
            settings.resetHistory("bypass changed")
            (if (bypass) scene.lighting.output else rr.output).blitToScreen(name = "RR plumbing")
            wasBypass = bypass
        }
        if (cut) {
            time += 4
            revision++
            rr.reset("live cut")
            settings.resetHistory("live cut")
            scene.bundle.invalidateFrameHistory()
            cut = false
        }
        frame()
    }
}
    .end { plumbing.close() }
```

```glsl [field-property box="rr_plumbing" name="vertex" path="rr_plumbing_vertex_.glsl"]
#version 460
void main() {
    vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    gl_Position=vec4(p*2-1,0,1);
}
```

```glsl [field-property box="rr_plumbing" name="fragment" path="rr_plumbing_fragment_.glsl"]
#version 460

layout(set = 0, binding = 0) uniform sampler2D positions;
layout(set = 0, binding = 1) uniform sampler2D normals;
layout(set = 0, binding = 2) uniform sampler2D albedo;
layout(set = 0, binding = 3) uniform sampler2D velocity;

layout(location = 0) out float depth;
layout(location = 1) out vec2 motion;
layout(location = 2) out vec4 diffuse;
layout(location = 3) out vec4 specular;
layout(location = 4) out vec4 normalRoughness;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 positionAndDepth = texelFetch(positions, pixel, 0);

    // G-buffer alpha stores Vulkan depth in [0, 1]; 1 is the cleared background.
    depth = positionAndDepth.w;

    // Raster motion is currentUV - previousUV, unjittered and Y down.
    // Reconstruction wants current-to-previous motion in input pixels.
    vec2 uvMotion = texelFetch(velocity, pixel, 0).xy;
    vec2 inputSize = vec2(textureSize(velocity, 0));
    motion = -uvMotion * inputSize;

    vec3 worldNormal = texelFetch(normals, pixel, 0).xyz;
    bool valid = positionAndDepth.w < 1 && dot(worldNormal, worldNormal) > .01;
    diffuse = vec4(valid ? texelFetch(albedo, pixel, 0).rgb : vec3(0), 1);

    // Opaque Lambertian material: zero specular RGB and roughness 1 in normalRoughness.w.
    // Background uses a unit fallback normal instead of normalizing zero.
    specular = vec4(0, 0, 0, 1);
    normalRoughness = vec4(valid ? normalize(worldNormal) : vec3(0, 0, 1), 1);
}
```

