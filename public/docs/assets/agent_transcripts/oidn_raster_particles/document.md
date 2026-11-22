# Field Document: oidn_raster_particles

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="oidn_raster_renderer" index="0" -->

```edn [field-box id="oidn_raster_renderer" role="properties"]
{"id" "oidn_raster_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["oidn_raster_particles"] "properties" {"children" #{#afield/box "oidn_raster_particles"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oidn_raster_renderer_code_.kt" "lines" 10 "characters" 233 "sha256" "954b939420738d833e0236eeb0770cdb908b6d56604f2936b5772bdb925250f5"}]}
```

```kotlin [field-property box="oidn_raster_renderer" name="code" path="oidn_raster_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Denoising a raster render")
    Renderer(window)
}
start(renderer::run)
```

## 2. particles, stochastic transparency

<!-- field-box id="oidn_raster_particles" index="1" -->

```edn [field-box id="oidn_raster_particles" role="properties"]
{"id" "oidn_raster_particles" "index" 1 "name" "particles, stochastic transparency" "parents" ["root" "oidn_raster_renderer"] "children" ["oidn_raster_denoise"] "properties" {"children" #{#afield/box "oidn_raster_denoise"} "parents" #{#afield/box "root" #afield/box "oidn_raster_renderer"} "name" "particles, stochastic transparency" "_tombstone" false "frame" #afield/rect [320.0 180.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oidn_raster_particles_code_.kt" "lines" 66 "characters" 2666 "sha256" "480a3f76a89ba613ef650d15669c38758258c23edb6fcf0582238050f61b7c21"}]}
```

```kotlin [field-property box="oidn_raster_particles" name="code" path="oidn_raster_particles_code_.kt"]
import afield.graphics.vulkan.targets.RenderToTexture
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT

// A 640 x 360 float target, drawn every frame.
prop<RenderToTexture>(particleImage) = RenderToTexture {
    width = 640
    height = 360
    useDepth = false
    format = VK_FORMAT_R32G32B32A32_SFLOAT
}
renderer.renderList += "oidn particles" to particleImage

// 3000 soft discs on a three-armed spiral. Each fragment is kept with probability equal to
// the disc's opacity there, and drawn opaque: stochastic transparency, one sample a pixel.
val module = Module(
    parameters(renderer) {
        name = "oidn-particles"
        +particleImage
        depthTest = false
        depthWrite = false
        blend = false
        vertex =
            """#version 450
        layout(location=0) out vec2 tc;
        layout(location=1) flat out int id;
        layout(location=2) flat out vec3 col;
        const vec2 corner[6] = vec2[](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
        float h(float n) { return fract(sin(n) * 43758.5453); }
        void main() {
            int i = gl_VertexIndex / 6;
            float f = float(i) + 1.0;
            id = i;
            tc = corner[gl_VertexIndex % 6];
            float r = 0.08 + 0.8 * h(f * 5.3);
            float a = r * 5.0 + floor(h(f * 3.1) * 3.0) * 2.0944 + (h(f * 7.7) - 0.5) * 0.7;
            float s = 0.03 + 0.09 * h(f * 9.1);
            col = mix(vec3(1.0, 0.5, 0.15), vec3(0.25, 0.55, 1.0), r) * (0.5 + h(f * 11.3));
            vec2 p = vec2(cos(a), sin(a)) * r + (tc - 0.5) * s;
            gl_Position = vec4(p.x * 360.0 / 640.0, p.y, 0.5, 1.0);
        }"""
        fragment =
            """#version 450
        layout(location=0) in vec2 tc;
        layout(location=1) flat in int id;
        layout(location=2) flat in vec3 col;
        layout(location=0) out vec4 color;
        float hash(uvec3 v) {
            v = v * 1664525u + 1013904223u;
            v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
            v ^= v >> 16u;
            v.x += v.y * v.z;
            return float(v.x) / 4294967295.0;
        }
        void main() {
            float d = length(tc * 2.0 - 1.0);
            float opacity = d < 1.0 ? 0.6 * exp(-4.0 * d * d) : 0.0;
            if (hash(uvec3(gl_FragCoord.xy, id)) >= opacity) discard;
            color = vec4(col, 1.0);
        }"""
    },
)
val discs = Mesh(renderer, module.pointer())
discs.resize(3000 * 6, 0, module.pointer())
module.meshes["discs"] = discs
particleImage.modules["discs"] = module.pointer()
println("drawing 3000 particles into ${particleImage.width} x ${particleImage.height}")
```

## 3. denoise the raster image

<!-- field-box id="oidn_raster_denoise" index="2" -->

```edn [field-box id="oidn_raster_denoise" role="properties"]
{"id" "oidn_raster_denoise" "index" 2 "name" "denoise the raster image" "parents" ["root" "oidn_raster_particles"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "oidn_raster_particles"} "name" "denoise the raster image" "_tombstone" false "frame" #afield/rect [320.0 340.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oidn_raster_denoise_code_.kt" "lines" 25 "characters" 1112 "sha256" "ff999718819f4e4cd489a49bda7e46fb6cd5c7bc0d1ef1494dafecfb07c67d28"}]}
```

```kotlin [field-property box="oidn_raster_denoise" name="code" path="oidn_raster_denoise_code_.kt"]
import afield.media.image.util.PngEncoder
import afield.media.reconstruction.OidnComputeDenoiser
import afield.media.reconstruction.OidnImageSource

// Copy the particle render into an image source and denoise it once, with flat guides.
val image = OidnImageSource(renderer, 640, 360).upload(particleImage)
val denoiser = OidnComputeDenoiser(image)
val denoised = denoiser.denoiseImage()
val noisy = denoiser.read(image.radiance)

// The middle 400 columns of each, noisy on the left and denoised on the right, as sRGB.
fun srgb(x: Float) =
    (255 * (if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1 / 2.4f) - 0.055f)).toInt().coerceIn(0, 255)
val pixels =
    IntArray(800 * 360) { k ->
        val x = k % 800
        val y = k / 800
        val src = if (x < 400) noisy else denoised
        val i = (y * 640 + 120 + x % 400) * 4
        (255 shl 24) or (srgb(src[i]) shl 16) or (srgb(src[i + 1]) shl 8) or srgb(src[i + 2])
    }
display("main", PngEncoder.encode(pixels, 800, 360))
println("denoised ${image.width} x ${image.height} in ${denoiser.lastDenoiseMs.f0} ms")
denoiser.close()
image.close()
```

