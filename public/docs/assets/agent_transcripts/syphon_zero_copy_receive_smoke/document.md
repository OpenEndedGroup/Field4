# Field Document: syphon_zero_copy_receive_smoke

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. syphon zero-copy renderer

<!-- field-box id="syphon_zero_copy_renderer" index="0" -->

```edn [field-box id="syphon_zero_copy_renderer" role="properties"]
{"id" "syphon_zero_copy_renderer" "index" 0 "name" "syphon zero-copy renderer" "parents" ["root"] "children" ["syphon_zero_copy_smoke"] "properties" {"children" #{#afield/box "syphon_zero_copy_smoke"} "parents" #{#afield/box "root"} "name" "syphon zero-copy renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_zero_copy_renderer_code_.kt" "lines" 15 "characters" 302 "sha256" "885aba3bfb38dbfc42fc1838d74eef4e22fb42038c2cb66dd78fff3b80e7334a"}]}
```

```kotlin [field-property box="syphon_zero_copy_renderer" name="code" path="syphon_zero_copy_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Syphon zero-copy receive smoke")
    Renderer(window)
}

println("syphon zero-copy renderer ready")
start(renderer::run)
```

## 2. Syphon receive, zero-copy, exact pixels

<!-- field-box id="syphon_zero_copy_smoke" index="1" -->

```edn [field-box id="syphon_zero_copy_smoke" role="properties"]
{"id" "syphon_zero_copy_smoke" "index" 1 "name" "Syphon receive, zero-copy, exact pixels" "parents" ["root" "syphon_zero_copy_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "syphon_zero_copy_renderer"} "name" "Syphon receive, zero-copy, exact pixels" "_tombstone" false "frame" #afield/rect [320.0 180.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_zero_copy_smoke_code_.kt" "lines" 115 "characters" 5535 "sha256" "a9a13280fef066068d2dd9b02ebcf142adf85f54e9dcfba00cc03394cd110a3e"} {"name" "vertex" "language" "glsl" "path" "syphon_zero_copy_smoke_vertex_.glsl" "lines" 6 "characters" 138 "sha256" "907a218100d7c3399bd0131de3d4aae514b9fd90ea58b289f8f8b333fa6d650b"} {"name" "fragment" "language" "glsl" "path" "syphon_zero_copy_smoke_fragment_.glsl" "lines" 7 "characters" 270 "sha256" "85655a25a50c56788ef5a3ee94286463b233bd4d029823941e0e051055f9ccbe"}]}
```

```kotlin [field-property box="syphon_zero_copy_smoke" name="code" path="syphon_zero_copy_smoke_code_.kt"]
import afield.io.syphon.*
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import org.lwjgl.vulkan.VK10.*

// Capture the renderer before the frame coroutine starts.
val owner = renderer
frames {
    check(Syphon.isAvailable) { Syphon.describe() }
    val probePath =
        SyphonLibrary.probePath
            ?: throw IllegalStateException("field_syphon_probe is missing: ./gradlew buildSyphonNative")
    val device = owner.device
    println(
        "syphon-zero-copy driver=${device.physicalDevice.driverName} metalObjects=${device.supportsMetalObjects} externalMemoryHost=${device.supportsExternalMemoryHost}",
    )

    // 64x48 for the first half of 14 s, then 96x64
    val name = "FieldZeroCopySmoke-${ProcessHandle.current().pid()}"
    val proc =
        ProcessBuilder(probePath.toString(), "serve", name, "14", "64", "48", "96", "64")
            .redirectErrorStream(true)
            .start()
    try {
        syphonClient(name, 8000).use { inp ->
            inp.texture().use { tex ->
                val probeTarget = RenderToTexture(owner) {
                    width = 96
                    height = 64
                }
                try {
                    prop<SyphonTexture>(syphonIn) = tex
                    val copiesBefore = Syphon.cpuCopies
                    println(
                        "syphon-zero-copy path=${tex.path} size=${tex.width}x${tex.height} fallback='${tex.fallbackReason}'",
                    )
                    require(tex.path != SyphonTexture.Path.CPU_COPY) { "fell back to copying: ${tex.fallbackReason}" }

                    prop<RenderToTexture>(syphonProbeTarget) = probeTarget
                    owner.renderList += "probeTarget" to probeTarget
                    val module = moduleHere()
                    val quad = Mesh(owner)
                    module += "quad" to quad
                    probeTarget += "sample" to module
                    quad.set(4, 2) {
                        pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
                        tc.add(0, 0, 1, 0, 1, 1, 0, 1)
                        tri.add(0, 1, 2)
                        tri.add(0, 2, 3)
                    }

                    fun check(w: Int, h: Int) {
                        val html: String = TexturePreviewCache.capture(
                            owner,
                            probeTarget.colorImageHandles[0],
                            probeTarget.colorFormats[0],
                            96,
                            64,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            96,
                        ) ?: throw IllegalStateException("readback failed")
                        val png =
                            Regex("base64,([^']+)").find(html)?.groupValues?.get(1)
                                ?: throw IllegalStateException("PNG payload missing")
                        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(png)))
                        val points =
                            [1 to 1, w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4]
                        val want = [[17, 34, 51], [255, 0, 0], [0, 255, 0], [0, 0, 255], [255, 255, 255]]
                        val got = points.map { (x, y) ->
                            image.getRGB(x, y).let { [(it ushr 16) and 255, (it ushr 8) and 255, it and 255] }
                        }
                        println(
                            "syphon-zero-copy ${w}x$h path=${tex.path} imports=${tex.imports} frames=${tex.frames} pixels=$got",
                        )
                        require(got == want) { "${w}x$h: expected $want, sampled $got" }
                    }

                    var ticks = 0
                    var phase = 0
                    while (true) {
                        ticks++
                        require(ticks < 1500) { "timed out in phase $phase: $tex" }
                        if (phase == 0 && ticks > 60 && tex.width == 64) {
                            check(64, 48)
                            phase = 1
                        } else if (phase == 1 && tex.width == 96) {
                            phase = 2
                            ticks = 1000
                        } else if (phase == 2 && ticks > 1060) {
                            check(96, 64)
                            val copies = Syphon.cpuCopies - copiesBefore
                            println(
                                "syphon-zero-copy frames=${tex.frames} imports=${tex.imports} cpuCopies(texture)=${tex.cpuCopies} cpuCopies(jvm)=$copies",
                            )
                            require(tex.imports == 2L) { "expected one import per surface size, got ${tex.imports}" }
                            require(tex.cpuCopies == 0L && copies == 0L) {
                                "frames went through memory: ${tex.cpuCopies} / $copies"
                            }
                            require(tex.frames > 100) { "only ${tex.frames} frames arrived" }
                            println("syphon-zero-copy PASS")
                            break
                        }
                        frame()
                        frame()
                    }
                } finally {
                    owner.renderList.others.remove("syphonProbeTarget", probeTarget)
                }
            }
        }
    } finally {
        proc.destroy()
    }
}
```

```glsl [field-property box="syphon_zero_copy_smoke" name="vertex" path="syphon_zero_copy_smoke_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
void main() {
    gl_Position = vec4(pos.xy, 0.5, 1.0);
}
```

```glsl [field-property box="syphon_zero_copy_smoke" name="fragment" path="syphon_zero_copy_smoke_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 color;
layout(set = 0, binding = 0) uniform sampler2D syphonIn;
void main() {
    // texel (x, y) of the received frame, y down from its top row, into pixel (x, y)
    color = texelFetch(syphonIn, ivec2(gl_FragCoord.xy), 0);
}
```

