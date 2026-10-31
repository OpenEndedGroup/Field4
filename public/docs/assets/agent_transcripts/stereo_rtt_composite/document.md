# Field Document: stereo_rtt_composite

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Window and renderer

<!-- field-box id="stereo_renderer" index="0" -->

### Narrative

From `code`

Launch 1, 2, 2b, 3, 4 in order; stop box 4 to pause.
Desktop preview first. For OpenXR: connect the headset, set true, restart Field.

```edn [field-box id="stereo_renderer" role="properties"]
{"id" "stereo_renderer" "index" 0 "name" "1 - Window and renderer" "parents" ["root"] "children" ["stereo_scene"] "properties" {"children" #{#afield/box "stereo_scene"} "parents" #{#afield/box "root"} "name" "1 - Window and renderer" "_tombstone" false "frame" #afield/rect [60.0 60.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stereo_renderer_code_.kt" "lines" 34 "characters" 1319 "sha256" "db9899b47d9c37dd2d1e8db0bf2f47bc244c4c67407137cde7985a87873ac851"}]}
```

```kotlin [field-property box="stereo_renderer" name="code" path="stereo_renderer_code_.kt"]
// Launch 1, 2, 2b, 3, 4 in order; stop box 4 to pause.
// Desktop preview first. For OpenXR: connect the headset, set true, restart Field.
import afield.graphics.vulkan.targets.OpenXRHmdTarget
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
val useHeadset = false
Options.enableVR = useHeadset
Options.enableHDR = false
once.renderer {
    window = Window("Stereo RTT composite", initialDimensions = Rect(80.0, 80.0, 1280.0, 640.0))
    val r = Renderer(window)
    if (useHeadset) r.openXRHmdTarget = OpenXRHmdTarget(r)
    r
}
// Initialize the swapchain and locate the initial eye views; box 4 owns the loop.
renderer.render()
display(
    "guide",
    """
    <div style="width:460px;font:14px/1.6 system-ui">
    <b>Scene → stereo RTT → FXAA → headset + desktop</b><br>
    Launch 1, 2, 2b, 3, 4 in order. Box 4 starts rendering.<br>
    Edit geometry in 2, shaders in 3, and toggle FXAA in 3's output.<br>
    Desktop preview: 1200 × 1200 pixels per eye.<br>
    For VR, connect your OpenXR runtime first, set useHeadset = true above,
    then restart Field and launch the boxes again. Eye size comes from the runtime.<br>
    Stop box 4 to pause the render loop. Restart Field to change mode or eye size.
    </div>
    """
        .trimIndent(),
)
displayOpen("guide")
```

## 2. 2 - Stereo cubes and floor

<!-- field-box id="stereo_scene" index="1" -->

```edn [field-box id="stereo_scene" role="properties"]
{"id" "stereo_scene" "index" 1 "name" "2 - Stereo cubes and floor" "parents" ["root" "stereo_renderer"] "children" ["stereo_chart" "stereo_fxaa"] "properties" {"children" #{#afield/box "stereo_chart" #afield/box "stereo_fxaa"} "parents" #{#afield/box "root" #afield/box "stereo_renderer"} "name" "2 - Stereo cubes and floor" "_tombstone" false "frame" #afield/rect [560.0 60.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stereo_scene_code_.kt" "lines" 68 "characters" 2840 "sha256" "08a1001e71d6d1894b7906d90cc916a6c230e1a388301a1af212665283a8990a"} {"name" "vertex" "language" "glsl" "path" "stereo_scene_vertex_.glsl" "lines" 12 "characters" 259 "sha256" "ba9c05a361b5dad4a7b25127970e926431dec660a338790f6adadfff1908afb0"} {"name" "fragment" "language" "glsl" "path" "stereo_scene_fragment_.glsl" "lines" 6 "characters" 121 "sha256" "09f2d517b9f20511becdbad44a079ab2444e197c15df7f90141a8a45c10d42de"}]}
```

```kotlin [field-property box="stereo_scene" name="code" path="stereo_scene_code_.kt"]
import afield.graphics.vulkan.targets.SideBySideStereoTarget

val xr = renderer.openXRHmdTarget
check(xr == null || xr.hasEyeViews) { "Connect and wear the headset, then launch box 1 first" }
val eye = xr?.let { renderer.runOnQueueThread { it.sideBySideRTTParameters() } }
val origin = Vec3()
xr?.headPose(origin)
val stereo = SideBySideStereoTarget(
    renderer,
    eyeWidth = eye?.width?.div(2) ?: 1200,
    eyeHeight = eye?.height ?: 1200,
    openXR = xr,
)
stereo.stereoCamera.base.lookAt(Vec3(), Vec3(0f, 0f, -2f))
stereo.stereoCamera.base.setAspect(stereo.eyeWidth.toFloat() / stereo.eyeHeight)
stereo +=
    "room" to
        {
            val module = moduleHere {
                primitiveType = lines
                depthTest = true
                depthWrite = true
                blend = false
            }
            fun wire(name: String, points: List<Vec3>, color: Vec4) {
                val mesh = Mesh(renderer, module)
                module += name to mesh
                mesh.set(points.size, points.size / 2) {
                    for ((i, v) in points.withIndex()) {
                        pos.add(v.x, v.y, v.z)
                        col.add(color.x, color.y, color.z, color.w)
                        tri.add(i)
                    }
                }
                mesh.p = { stereo.currentEyeProjectionMatrix() }
                mesh.m = { stereo.currentEyeViewMatrix() }
            }
            val grid = mutableListOf<Vec3>()
            for (i in -6..6) {
                val a = i * 0.5f
                grid.add(Vec3(origin.x + a, origin.y - 1.5f, origin.z - 3f))
                grid.add(Vec3(origin.x + a, origin.y - 1.5f, origin.z + 3f))
                grid.add(Vec3(origin.x - 3f, origin.y - 1.5f, origin.z + a))
                grid.add(Vec3(origin.x + 3f, origin.y - 1.5f, origin.z + a))
            }
            wire("floor", grid, vec(0.2, 0.35, 0.45, 1))
            for (n in -1..1) {
                val corners =
                    (0..7).map { i ->
                        Vec3(
                            origin.x + n * 0.8f + (if (i and 1 == 0) -0.25f else 0.25f),
                            origin.y + (if (i and 2 == 0) -0.25f else 0.25f),
                            origin.z - 2f + (if (i and 4 == 0) -0.25f else 0.25f),
                        )
                    }
                val edges = mutableListOf<Vec3>()
                for (i in 0..7) for (bit in listOf(1, 2, 4)) if (i and bit == 0) {
                    edges.add(corners[i])
                    edges.add(corners[i or bit])
                }
                wire(
                    "cube $n",
                    edges,
                    if (n == -1) vec(1, 0.3, 0.2, 1) else if (n == 0) vec(0.3, 1, 0.5, 1) else vec(0.3, 0.5, 1, 1),
                )
            }
            module
        }
```

```glsl [field-property box="stereo_scene" name="vertex" path="stereo_scene_vertex_.glsl"]
#version 450
layout(location=0) in vec3 pos;
layout(location=1) in vec4 col;
layout(location=0) out vec4 fcol;
layout(push_constant) uniform Matrices {
    mat4 p;
    mat4 m;
} pc;
void main() {
    gl_Position = pc.p * pc.m * vec4(pos,1);
    fcol = col;
}
```

```glsl [field-property box="stereo_scene" name="fragment" path="stereo_scene_fragment_.glsl"]
#version 450
layout(location=0) in vec4 fcol;
layout(location=0) out vec4 outColor;
void main() {
    outColor = fcol;
}
```

## 3. 3 - FXAA composite RTT

<!-- field-box id="stereo_fxaa" index="2" -->

### Narrative

From `code`

Scene -> stereo RTT -> FXAA composite -> OpenXR + desktop mirror.
The fragment property reuses Fabricate's FXAA shader, adding per-eye sample clamping.
Toggle FXAA below to compare at the same render resolution. No temporal history is used.

```edn [field-box id="stereo_fxaa" role="properties"]
{"id" "stereo_fxaa" "index" 2 "name" "3 - FXAA composite RTT" "parents" ["root" "stereo_scene"] "children" ["stereo_present"] "properties" {"children" #{#afield/box "stereo_present"} "parents" #{#afield/box "root" #afield/box "stereo_scene"} "name" "3 - FXAA composite RTT" "_tombstone" false "frame" #afield/rect [980.0 60.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stereo_fxaa_code_.kt" "lines" 56 "characters" 2131 "sha256" "e88fc89efe55ce417310f920bdcf3e1ac3b5934be197da5dc1001d985a963e55"} {"name" "vertex" "language" "glsl" "path" "stereo_fxaa_vertex_.glsl" "lines" 13 "characters" 364 "sha256" "24741aa3db01e37be8be45d79d7d8199e0471957112a7b532725b21e22b136c8"} {"name" "fragment" "language" "glsl" "path" "stereo_fxaa_fragment_.glsl" "lines" 79 "characters" 2624 "sha256" "2717aa9a00ad06789cd8958e8cf9b8997c1a7f1b870de16068dbddb3835dfa22"}]}
```

```kotlin [field-property box="stereo_fxaa" name="code" path="stereo_fxaa_code_.kt"]
/*
Scene -> stereo RTT -> FXAA composite -> OpenXR + desktop mirror.
The fragment property reuses Fabricate's FXAA shader, adding per-eye sample clamping.
Toggle FXAA below to compare at the same render resolution. No temporal history is used.
*/
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.targets.SideBySideStereoTarget
import java.util.concurrent.atomic.AtomicBoolean

val sourceStereo = fromContext<SideBySideStereoTarget>()
val rawStereo = sourceStereo.packed
val aaEnabled = AtomicBoolean(true)
val composite = RenderToTexture(renderer) {
    width = sourceStereo.eyeWidth * 2
    height = sourceStereo.eyeHeight
    format = rawStereo.parameters.format
    colorSampled = true
    colorTransferSrc = true
    useDepth = false
}
val sourceSampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
val pass = moduleHere {
    name = "stereo-fxaa-composite"
    color = 1
    colorFormats = listOf(composite.parameters.format)
    imageFormat = composite.parameters.format
    depth = 0
    depthTest = false
    depthWrite = false
    blend = false
    textureResolver = { tex ->
        if (tex.name == "sourceTex") fromViews(listOf(rawStereo), tex.name, sourceSampler) else null
    }
}
composite += "fxaa" to pass
val quad = StandardRenderer.run { pass.ensureFullscreenTriangle("fxaa-quad") }
quad.push["edgeThreshold"] = 0.125f
quad.push["edgeThresholdMin"] = 0.0312f
quad.push["spanMax"] = 8f
quad.update["aa-toggle"] = updateForever {
    quad.push["aaBlend"] = if (aaEnabled.get()) 1f else 0f
    true
}

// preTargets retain insertion order: the scene packs its two eyes before this composite reads it.
renderer.renderList.preTargets["stereo-fxaa"] = composite
controlGroup("antialias") {
    checkbox("FXAA", initialValue = true) { aaEnabled.set(it) }
}
println(
    "FXAA composite: ${composite.parameters.width} x ${composite.parameters.height}; current-frame input, isolated eye taps",
)
displayOpen("antialias")
```

```glsl [field-property box="stereo_fxaa" name="vertex" path="stereo_fxaa_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 vUv;
const vec2 positions[3] = vec2[](
vec2(-1.0, -1.0),
vec2(3.0, -1.0),
vec2(-1.0, 3.0)
);
void main() {
    vec2 pos = positions[gl_VertexIndex];
    // Texel-aligned: v = 0 at clip y = +1, which the Y-flipped viewport puts at row 0.
    vUv = vec2(pos.x, -pos.y) * 0.5 + 0.5;
    gl_Position = vec4(pos, 0.0, 1.0);
}
```

```glsl [field-property box="stereo_fxaa" name="fragment" path="stereo_fxaa_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 vUv;
layout(set = 0, binding = 0) uniform sampler2D sourceTex;

layout(push_constant) uniform FxaaPush {
    float aaBlend;
    float edgeThreshold;
    float edgeThresholdMin;
    float spanMax;
} push;

layout(location = 0) out vec4 outColor;

float luma(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

// Keep every FXAA tap in the fragment's own eye; no sampling across the stereo seam.
vec4 sampleEye(vec2 uv) {
    vec2 halfTexel = 0.5 / vec2(textureSize(sourceTex, 0));
    float eyeX = vUv.x < 0.5 ? 0.0 : 0.5;
    vec2 lower = vec2(eyeX, 0.0) + halfTexel;
    vec2 upper = vec2(eyeX + 0.5, 1.0) - halfTexel;
    return texture(sourceTex, clamp(uv, lower, upper));
}

void main() {
    vec2 inverseResolution = 1.0 / vec2(textureSize(sourceTex, 0));
    vec4 center = sampleEye(vUv);
    if (push.aaBlend <= 1e-5) {
        outColor = center;
        return;
    }

    vec3 rgbNW = sampleEye(vUv + vec2(-1.0, -1.0) * inverseResolution).rgb;
    vec3 rgbNE = sampleEye(vUv + vec2(1.0, -1.0) * inverseResolution).rgb;
    vec3 rgbSW = sampleEye(vUv + vec2(-1.0, 1.0) * inverseResolution).rgb;
    vec3 rgbSE = sampleEye(vUv + vec2(1.0, 1.0) * inverseResolution).rgb;
    vec3 rgbM = center.rgb;

    float lumaNW = luma(rgbNW);
    float lumaNE = luma(rgbNE);
    float lumaSW = luma(rgbSW);
    float lumaSE = luma(rgbSE);
    float lumaM = luma(rgbM);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
    float lumaRange = lumaMax - lumaMin;
    float threshold = max(push.edgeThresholdMin, lumaMax * push.edgeThreshold);
    if (lumaRange < threshold) {
        outColor = center;
        return;
    }

    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y = ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float dirReduce = max(
    (lumaNW + lumaNE + lumaSW + lumaSE) * 0.03125,
    push.edgeThresholdMin
    );
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * rcpDirMin, vec2(-push.spanMax), vec2(push.spanMax)) * inverseResolution;

    vec3 rgbA = 0.5 * (
    sampleEye(vUv + dir * (1.0 / 3.0 - 0.5)).rgb +
    sampleEye(vUv + dir * (2.0 / 3.0 - 0.5)).rgb
    );
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
    sampleEye(vUv + dir * -0.5).rgb +
    sampleEye(vUv + dir * 0.5).rgb
    );

    float lumaB = luma(rgbB);
    vec3 fxaaColor = ((lumaB < lumaMin) || (lumaB > lumaMax)) ? rgbA : rgbB;
    outColor = vec4(mix(rgbM, fxaaColor, clamp(push.aaBlend, 0.0, 1.0)), center.a);
}
```

## 4. 2b - Resolution chart

<!-- field-box id="stereo_chart" index="3" -->

```edn [field-box id="stereo_chart" role="properties"]
{"id" "stereo_chart" "index" 3 "name" "2b - Resolution chart" "parents" ["root" "stereo_scene"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stereo_scene"} "name" "2b - Resolution chart" "_tombstone" false "frame" #afield/rect [560.0 260.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stereo_chart_code_.kt" "lines" 38 "characters" 1562 "sha256" "c6366ed2322eedf53b7cc7d07cfc44becf72fa037f8b146ae85501d4357cd063"} {"name" "vertex" "language" "glsl" "path" "stereo_chart_vertex_.glsl" "lines" 12 "characters" 259 "sha256" "ba9c05a361b5dad4a7b25127970e926431dec660a338790f6adadfff1908afb0"} {"name" "fragment" "language" "glsl" "path" "stereo_chart_fragment_.glsl" "lines" 22 "characters" 651 "sha256" "00179f6869728703e73dfac232de86dd2754008d13ec462e7d1555c2a1489f91"}]}
```

```kotlin [field-property box="stereo_chart" name="code" path="stereo_chart_code_.kt"]
import afield.graphics.vulkan.targets.SideBySideStereoTarget

val qualityStereo = fromContext<SideBySideStereoTarget>()
val qualityXr = renderer.openXRHmdTarget
val qualityOrigin = Vec3()
qualityXr?.headPose(qualityOrigin)
println(
    "Eye texture ${qualityStereo.eyeWidth} x ${qualityStereo.eyeHeight}; packed ${qualityStereo.eyeWidth*2} x ${qualityStereo.eyeHeight}",
)
qualityStereo +=
    "quality-chart" to
        {
            val module = moduleHere {
                primitiveType = triangles
                depthTest = true
                depthWrite = true
                blend = false
            }
            val mesh = Mesh(renderer, module)
            module += "chart" to mesh
            mesh.set(4, 2) {
                for ((x, y, u, v) in
                    listOf(
                        floatArrayOf(-0.8f, -0.85f, 0f, 0f),
                        floatArrayOf(0.8f, -0.85f, 1f, 0f),
                        floatArrayOf(0.8f, -0.15f, 1f, 1f),
                        floatArrayOf(-0.8f, -0.15f, 0f, 1f),
                    )) {
                    pos.add(qualityOrigin.x + x, qualityOrigin.y + y, qualityOrigin.z - 1.6f)
                    col.add(u, v, 0f, 0f)
                }
                for (i in listOf(0, 1, 2, 0, 2, 3)) tri.add(i)
            }
            mesh.p = { qualityStereo.currentEyeProjectionMatrix() }
            mesh.m = { qualityStereo.currentEyeViewMatrix() }
            module
        }
println("Resolution chart added below the cubes: solid colour patches and progressively finer antialiased bars")
```

```glsl [field-property box="stereo_chart" name="vertex" path="stereo_chart_vertex_.glsl"]
#version 450
layout(location=0) in vec3 pos;
layout(location=1) in vec4 col;
layout(location=0) out vec4 fcol;
layout(push_constant) uniform Matrices {
    mat4 p;
    mat4 m;
} pc;
void main() {
    gl_Position = pc.p * pc.m * vec4(pos,1);
    fcol = col;
}
```

```glsl [field-property box="stereo_chart" name="fragment" path="stereo_chart_fragment_.glsl"]
#version 450
layout(location=0) in vec4 fcol;
layout(location=0) out vec4 outColor;
void main() {
    if (fcol.a > 0.5) {
        outColor = fcol;
        return;
    }
    vec2 uv = fcol.xy;
    vec3 color;
    if (uv.y > 0.8) {
        color = uv.x < 0.333 ? vec3(0.95,0.2,0.12) : uv.x < 0.667 ? vec3(0.15,0.9,0.35) : vec3(0.15,0.4,1);
    }
    else {
        float frequency = exp2(floor(uv.x * 6.0) + 3.0);
        float phase = uv.y * frequency * 6.283185;
        float aa = max(fwidth(phase), 0.01);
        float bars = smoothstep(-aa, aa, sin(phase));
        color = mix(vec3(0.05), vec3(0.95), bars);
    }
    outColor = vec4(color,1);
}
```

## 5. 4 - Headset and desktop

<!-- field-box id="stereo_present" index="4" -->

### Narrative

From `code`

The parent graph supplies both the renderer and the composite RTT.

```edn [field-box id="stereo_present" role="properties"]
{"id" "stereo_present" "index" 4 "name" "4 - Headset and desktop" "parents" ["root" "stereo_fxaa"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stereo_fxaa"} "name" "4 - Headset and desktop" "_tombstone" false "frame" #afield/rect [980.0 340.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stereo_present_code_.kt" "lines" 13 "characters" 793 "sha256" "5da3d7d429cf0f67257642dad70980f32a8ec7650faafa3fbc4acb012cce3a08"}]}
```

```kotlin [field-property box="stereo_present" name="code" path="stereo_present_code_.kt"]
// The parent graph supplies both the renderer and the composite RTT.
import afield.document.context.fromContextWithNameOrNull
import afield.graphics.vulkan.targets.RenderToTexture

val presentedTexture: RenderToTexture =
    fromContextWithNameOrNull<RenderToTexture>("composite")
        ?: throw IllegalStateException("Launch the FXAA composite parent (3) first")
renderer.openXRHmdTarget?.copySideBySideToEyes(presentedTexture)
presentedTexture.blitToScreen(renderer = renderer, target = renderer.renderList, name = "stereo-mirror")
println("Eye texture: ${presentedTexture.parameters.width / 2} × ${presentedTexture.parameters.height}; FXAA in box 3")
// Keep start last: Field tracks the returned Frames as this box's running job.
val activeRenderer = renderer
start(activeRenderer::run)
```

