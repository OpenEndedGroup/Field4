# Field Document: text_one_vr

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 27 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · Reading space

<!-- field-box id="text1_context" index="0" -->

```edn [field-box id="text1_context" role="properties"]
{"id" "text1_context" "index" 0 "name" "1 · Reading space" "parents" ["root"] "children" ["text1_poem" "text1_example2" "text1_example3" "text1_example4" "text1_typography" "text1_word_blur" "text1_elastic" "text1_drop_caps" "text1_two_readings" "text1_overstroke_rectangle" "text1_overstroke_union" "text1_frustum_readings" "text1_frustum_two_faces" "text1_stroke" "text1_point" "text1_text" "text1_blur" "text1_blur_medium_v" "text1_blur_broad_h" "text1_blur_broad_v" "text1_composite"] "properties" {"children" #{#afield/box "text1_poem" #afield/box "text1_example2" #afield/box "text1_example3" #afield/box "text1_example4" #afield/box "text1_typography" #afield/box "text1_word_blur" #afield/box "text1_elastic" #afield/box "text1_drop_caps" #afield/box "text1_two_readings" #afield/box "text1_overstroke_rectangle" #afield/box "text1_overstroke_union" #afield/box "text1_frustum_readings" #afield/box "text1_frustum_two_faces" #afield/box "text1_stroke" #afield/box "text1_point" #afield/box "text1_text" #afield/box "text1_blur" #afield/box "text1_blur_medium_v" #afield/box "text1_blur_broad_h" #afield/box "text1_blur_broad_v" #afield/box "text1_composite"} "parents" #{#afield/box "root"} "name" "1 · Reading space" "_tombstone" false "frame" #afield/rect [0.0 150.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_context_code_.kt" "lines" 64 "characters" 3068 "sha256" "208388fd3cada3d24733c90f1e3449f1f58e8196ac77f59dc84d65574b78e6db"}]}
```

```kotlin [field-property box="text1_context" name="code" path="text1_context_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.vulkan.targets.*
import afield.graphics.vulkan.util.Options
import afield.media.browser.*

/*
Set this code switch BEFORE the first Vulkan renderer is created.
Moving between desktop-only and OpenXR mode requires a fresh Field JVM.
The browser texture size below is independent of the headset render resolution.
*/
val headsetMode = false // true for WiVRn / OpenXR; false for desktop preview
Options.enableVR = headsetMode
// The core OpenXR validator does not register spaces created by WiVRn's vendor extension.
// Disable that layer for body tracking; Vulkan validation remains independently available.
if (headsetMode) Options.validateOpenXR = false
Options.enableHDR = false
prop<Window>(window) = Window("text_one · a reading space", initialDimensions = Rect(60.0, 60.0, 1440.0, 1000.0))
prop<Renderer>(renderer) = Renderer(window)
val ownedRenderer = renderer
var workspace: FieldVrWorkspace? = null
if (headsetMode) {
    renderer.openXRHmdTarget = OpenXRHmdTarget(renderer)
    repeat(8) { if (renderer.openXRHmdTarget?.hasEyeViews == false) renderer.render() }
    check(renderer.openXRHmdTarget?.hasEyeViews == true) { "Connect WiVRn before launching the headset mode" }
    workspace = FieldVrWorkspace(
        renderer,
        FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
        width = 2560,
        height = 1600,
        readyBoxUid = thisBox.id,
        withDepth = false,
        widthMeters = 1.8f,
        distanceMeters = 1.7f,
    )
    workspace?.controllerScrollHands = setOf(OpenXRHmdTarget.Hand.RIGHT)
}
// FieldVrWorkspace takes eye dimensions from OpenXR, exactly as dust_six does.
// Only the disconnected desktop preview uses this fixed fallback.
prop<MultiviewStereoTarget>(stereo) =
    workspace?.stereo ?: MultiviewStereoTarget(renderer, 1600, 1200, withDepth = false)
val ownedStereo = stereo
if (!headsetMode) {
    stereo.stereoCamera.base.at = vec(0f, 0f, 2.35f)
    stereo.stereoCamera.base.target = vec(0f, 0f, 0f)
}
stereo.layered.parameters.clearColor = vec(.003f, .005f, .010f, 1f)
display(
    "resolution",
    "<div style='padding:12px'>${if (headsetMode) "OpenXR runtime" else "Desktop fallback"}: ${stereo.eyeWidth} × ${stereo.eyeHeight} per eye. Browser: 2560 × 1600. Restart Field after changing WiVRn resolution.</div>",
)
prop<Boolean>(showStereoPair) = false
val mirror = stereo.mirrorToScreen(name = "reading-preview", stereoPair = { showStereoPair })

display(
    "mode",
    "<div style='padding:12px;width:440px'>${if(headsetMode)"OpenXR: current predicted pose in both eyes + sharp Field browser" else "Desktop stereo preview: the same two-eye scene, without a headset"}. No motion history or temporal antialiasing. Launch the example boxes, Scores, the shaders, then Drawing.</div>",
)
loop { frame() }
    .end {
        workspace?.close()
        ownedRenderer.renderList.preTargets.remove("stereo-multiview", ownedStereo)
        ownedRenderer.openXRHmdTarget?.clearEyeSource(ownedStereo.layered)
        ownedRenderer.cleanup()
    }
```

## 2. 14 · Words becoming weather

<!-- field-box id="text1_word_blur" index="1" -->

```edn [field-box id="text1_word_blur" role="properties"]
{"id" "text1_word_blur" "index" 1 "name" "14 · Words becoming weather" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "14 · Words becoming weather" "_tombstone" false "frame" #afield/rect [650.0 550.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_word_blur_code_.kt" "lines" 30 "characters" 1310 "sha256" "bc546f972c78770ced983b3773c84e4fd80756b7ab151b0de2f6f8f5d287faa6"}]}
```

```kotlin [field-property box="text1_word_blur" name="code" path="text1_word_blur_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Separate words can breathe on separate clocks. The blur radius is measured in ems;
// opacity is independent, so a word can stay bright while its outline dissolves.
val serif = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    for ((i, word) in listOf("a land", "of vague", "promise.").withIndex()) {
        val p = point("word-$i", (i - 1) * .65f, .04f, pinned = true)
        text("blur-word-$i", word, p) {
            font = serif
            scale = .48f
            // Reserve once, before Drawing. Scrubbing blur then only samples cached masks.
            blurCapacity = 2.2f
        }
    }
}
val progress = progressReporter("Prepare words becoming weather", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, serif, "field-slug".file, diagramScore(graph, 32.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleSix) = ReadingExample(
    "6 · Words becoming weather",
    "Slow word-local Gaussian blur: emerge from a soft cloud, hold in focus, then dissolve again. Structural lines and other words stay sharp.",
    player,
)
```

## 3. 11 · Green / light compositor

<!-- field-box id="text1_composite" index="2" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_composite" role="properties"]
{"id" "text1_composite" "index" 2 "name" "11 · Green / light compositor" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "11 · Green / light compositor" "_tombstone" false "frame" #afield/rect [1800.0 770.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_composite_code_.kt" "lines" 4 "characters" 215 "sha256" "379695a35d73e35ac5a246e0a588aaef6a2618ec57951381b11cfa84ee409128"} {"name" "vertex" "language" "glsl" "path" "text1_composite_vertex_.glsl" "lines" 10 "characters" 310 "sha256" "bfeef78cb283bfcb51d42e49e10e99971c2d418460670cf8a23279f7fd45a6cf"} {"name" "fragment" "language" "glsl" "path" "text1_composite_fragment_.glsl" "lines" 213 "characters" 11278 "sha256" "95964628b7ce3cb8fbf11db1a7314286bc4c4eb099b74550774a95ee2ae5769e"}]}
```

```kotlin [field-property box="text1_composite" name="code" path="text1_composite_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(compositeModuleFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_composite" name="vertex" path="text1_composite_vertex_.glsl"]
#version 450
#extension GL_EXT_multiview : require
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1.0 - p.y);
    eye = gl_ViewIndex;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="text1_composite" name="fragment" path="text1_composite_fragment_.glsl"]
#version 450
layout(location=0) in vec2 uv;
layout(location=1) flat in int eye;
layout(location=0) out vec4 outColor;
layout(set=0,binding=0) uniform sampler2DArray sharpImage;
layout(set=0,binding=1) uniform sampler2DArray mediumImage;
layout(set=0,binding=2) uniform sampler2DArray broadImage;
layout(push_constant) uniform CompositePush {
    vec4 selection; // selector gain, offset, effect mix, source-green scale
    vec4 reconstruction; // red/blue green reconstruction weights, exposure, reserved
} pc;
// Live grading controls use a UBO: the compositor still needs only 32 push-constant bytes.
layout(std140,set=0,binding=3) uniform CompositeGrade {
    vec4 selectorRange; // selector minimum/maximum, cubic fold strength, reserved
    vec4 selectorSource; // sharp/medium/broad green weights, reserved
    vec4 paint; // reserved legacy layout; accumulated light is never normalized
    vec4 colorFold; // red/green/blue fold gains, overall fold mix
    vec4 foldLimits; // red/green/blue fold caps, fold scale
    vec4 alphaFold; // fold gain, cap, strength, reserved
    vec4 saturation; // base saturation, shadow desaturation, shadow knee, shadow slope
    vec4 gain; // per-channel linear gain, reserved
    vec4 gamma; // per-channel artistic gamma, master artistic gamma
    vec4 shadowTone; // shadow hue RGB, luminance-preserving strength
    vec4 highlightTone; // highlight hue RGB, luminance-preserving strength
    vec4 toneRange; // shadow end, highlight start, luminance balance, reserved
    vec4 background; // linear background RGB, strength
    vec4 fxaa; // overall mix, relative edge threshold, absolute threshold, span pixels
    vec4 subpixel; // subpixel smoothing, output-eye width/height, linear-light FXAA flag
    vec4 supersampling; // per-pixel rotation mix, footprint radius, stable integer seed, display dither in 8-bit code steps
} grade;

// Editable effective LUT. Outside 0..1 these sum-to-one weights deliberately go
// negative, so preserve signed RGB until background and artistic gamma are applied.
vec3 blurWeights(float green) {
    float lo=min(grade.selectorRange.x,grade.selectorRange.y);
    float hi=max(grade.selectorRange.x,grade.selectorRange.y);
    float t=clamp(green*pc.selection.x+pc.selection.y,lo,hi);
    return vec3(max(1.0-2.0*t,0.0),1.0-abs(2.0*t-1.0),max(2.0*t-1.0,0.0));
}
float luminance(vec3 c) {
    return dot(c,vec3(0.2126,0.7152,0.0722));
}
vec3 signedGamma(vec3 c,vec3 gamma_) {
    return sign(c)*pow(abs(c),1.0/max(gamma_,vec3(0.001)));
}

// Hue transfer at constant Rec.709 linear luminance. Reduce chroma, rather than
// clipping RGB, when the requested tint would leave the display gamut.
vec3 lumaPreservingTint(vec3 color,vec4 tint,float region) {
    float strength=clamp(tint.a*region,0.0,1.0);
    vec3 hue=max(tint.rgb,vec3(0));
    float hueY=luminance(hue);
    if(strength<=0.0||hueY<=1e-6)return color;
    vec3 target=hue*(luminance(color)/hueY);
    vec3 delta=(target-color)*strength;
    float chromaScale=1.0;
    for(int channel=0;channel<3;channel++) {
        if(delta[channel]>1e-6)chromaScale=min(chromaScale,(1.0-color[channel])/delta[channel]);
        else if(delta[channel]<-1e-6)chromaScale=min(chromaScale,-color[channel]/delta[channel]);
    }
    return color+delta*clamp(chromaScale,0.0,1.0);
}

// Resolve supersampled RGBA in linear scene space, before nonlinear grading.
// Four bilinear taps cover the output pixel; at 100% the original single fetch is used.
vec2 outputSize() {
    return grade.subpixel.y>0.0&&grade.subpixel.z>0.0?grade.subpixel.yz:vec2(textureSize(sharpImage,0).xy);
}
// Integer hash has no frame or eye dependence: a stationary view has stationary
// grain, and both eyes use the same screen-space pattern rather than independent noise.
uint sampleMaskHash(uvec2 pixel,uint seed) {
    uint h=pixel.x*1664525u+pixel.y*1013904223u+seed*747796405u;
    h=(h^(h>>16u))*2246822519u;
    h=(h^(h>>13u))*3266489917u;
    return h^(h>>16u);
}
vec4 sharpSample(vec3 p) {
    vec2 sourceSize=vec2(textureSize(sharpImage,0).xy);
    vec2 size=outputSize();
    if(all(lessThanEqual(sourceSize,size+vec2(0.5))))return textureLod(sharpImage,p,0.0);
    uvec2 pixel=uvec2(clamp(floor(p.xy*size),vec2(0),size-vec2(1)));
    uint bits=sampleMaskHash(pixel,uint(max(grade.supersampling.z,0.0)));
    float angle=float(bits>>8u)*(6.28318530718/16777216.0)*clamp(grade.supersampling.x,0.0,1.0);
    float c=cos(angle),s=sin(angle);
    mat2 rotation=mat2(c,s,-s,c);
    // Opposite pairs keep the centroid exactly zero; equal weights preserve constant RGBA.
    // Rotation is in output-pixel coordinates, before dividing by the non-square eye size.
    float radius=0.25*max(grade.supersampling.y,0.0);
    vec2 a=rotation*vec2(radius,radius)/size;
    vec2 b=rotation*vec2(radius,-radius)/size;
    return 0.25*(textureLod(sharpImage,p+vec3(a,0),0.0)
    +textureLod(sharpImage,p-vec3(a,0),0.0)
    +textureLod(sharpImage,p+vec3(b,0),0.0)
    +textureLod(sharpImage,p-vec3(b,0),0.0));
}

// One world-frame image evaluation. Alpha is summed opacity; it is not display alpha.
vec3 composeScene(vec2 at) {
    vec3 p=vec3(clamp(at,vec2(0),vec2(1)),float(eye));
    vec4 sharp=sharpSample(p);
    vec4 medium=textureLod(mediumImage,p,0.0);
    vec4 broad=textureLod(broadImage,p,0.0);

    //sharp -= broad*0.2;

    vec3 source=max(grade.selectorSource.xyz,vec3(0));
    float sourceSum=dot(source,vec3(1));
    float g=(sourceSum>1e-6?dot(vec3(sharp.g,medium.g,broad.g),source)/sourceSum:broad.g)*pc.selection.w;
    float foldedGreen=g-g*g*g*grade.selectorRange.z;
    vec3 weights=blurWeights(foldedGreen);
    vec4 c=mix(sharp,sharp*weights.x+medium*weights.y+broad*weights.z,pc.selection.z);
    c.g=dot(c.rb,pc.reconstruction.xy)/max(pc.reconstruction.x+pc.reconstruction.y,1e-6);

    // Optional channel foldovers retain the original recipe's signed response.
    vec3 folded=c.rgb;
    folded.r+=min(grade.foldLimits.x,broad.g*broad.g*g*grade.colorFold.x)*grade.foldLimits.w;
    folded.g-=min(grade.foldLimits.y,g*g*grade.colorFold.y)*grade.foldLimits.w;
    folded.b-=min(grade.foldLimits.z,g*broad.g*grade.colorFold.z)*c.b*grade.foldLimits.w;
    c.rgb=mix(c.rgb,folded,clamp(grade.colorFold.w,0.0,1.0));
    c.a*=1.0-clamp(grade.alphaFold.z,0.0,1.0)*clamp(g*broad.g*grade.alphaFold.x,0.0,max(grade.alphaFold.y,0.0));

    // Additive light: opacity weights deposition, never divides accumulated RGB.
    float energy=dot(abs(c.rgb),vec3(1.0/3.0));
    c.rgb=mix(vec3(luminance(c.rgb)),c.rgb,max(grade.saturation.x,0.0));
    float desaturate=clamp(grade.saturation.y*max(grade.saturation.z-energy,0.0)*grade.saturation.w,0.0,1.0);
    c.rgb=mix(c.rgb,vec3(energy),desaturate);

    // Background is another light contribution, without coverage-based darkening.
    vec3 color=c.rgb+grade.background.rgb*grade.background.a;
    color*=grade.gain.rgb*pc.reconstruction.z;
    // Toning operates on final display-linear colour, after artistic gamma.
    // This makes its luminance invariant survive per-channel gamma and gamut limits.
    color=clamp(signedGamma(color,grade.gamma.rgb*grade.gamma.w),vec3(0),vec3(1));
    float tone=clamp(luminance(color)+grade.toneRange.z,0.0,1.0);
    float shadow=1.0-smoothstep(0.0,max(grade.toneRange.x,0.0001),tone);
    float highlight=smoothstep(min(grade.toneRange.y,0.9999),1.0,tone);
    color=lumaPreservingTint(color,grade.shadowTone,shadow);
    color=lumaPreservingTint(color,grade.highlightTone,highlight);
    return color;
}

// Keep perceptual edge detection, with selectable linear-light colour averaging.
// The target performs the one final sRGB encoding; artistic gamma belongs to grading.
vec3 linearToSrgb(vec3 c) {
    return mix(12.92*c,1.055*pow(max(c,vec3(0)),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));
}
vec3 srgbToLinear(vec3 c) {
    return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));
}
bool linearAA() {
    return grade.subpixel.w>=0.5;
}
vec3 displaySample(vec2 at) {
    vec3 color=clamp(composeScene(at),vec3(0),vec3(1));
    return linearAA()?color:linearToSrgb(color);
}
float aaLuma(vec3 color) {
    return luminance(linearAA()?linearToSrgb(color):color);
}

// Compact FXAA-style directional filter plus a bounded subpixel low-pass.
// It evaluates this same compositor at neighbours: no extra RTT, history, or pose delay.
vec3 spatialAA(vec2 at,vec3 center) {
    vec2 texel=1.0/outputSize();
    vec3 n=displaySample(at+texel*vec2(0,-1)),s=displaySample(at+texel*vec2(0,1));
    vec3 e=displaySample(at+texel*vec2(1,0)),w=displaySample(at+texel*vec2(-1,0));
    float mY=aaLuma(center),nY=aaLuma(n),sY=aaLuma(s),eY=aaLuma(e),wY=aaLuma(w);
    float low=min(mY,min(min(nY,sY),min(eY,wY)));
    float high=max(mY,max(max(nY,sY),max(eY,wY)));
    float contrast=high-low;
    if(contrast<max(grade.fxaa.z,high*grade.fxaa.y))return center;
    vec3 nw=displaySample(at+texel*vec2(-1,-1)),ne=displaySample(at+texel*vec2(1,-1));
    vec3 sw=displaySample(at+texel*vec2(-1,1)),se=displaySample(at+texel*vec2(1,1));
    float nwY=aaLuma(nw),neY=aaLuma(ne),swY=aaLuma(sw),seY=aaLuma(se);
    low=min(low,min(min(nwY,neY),min(swY,seY)));
    high=max(high,max(max(nwY,neY),max(swY,seY)));
    vec2 direction=vec2(-((nwY+neY)-(swY+seY)),(nwY+swY)-(neY+seY));
    float reduce=max((nwY+neY+swY+seY)*(0.25/8.0),1.0/128.0);
    direction=clamp(direction/(min(abs(direction.x),abs(direction.y))+reduce),vec2(-grade.fxaa.w),vec2(grade.fxaa.w))*texel;
    vec3 a=0.5*(displaySample(at-direction/6.0)+displaySample(at+direction/6.0));
    vec3 b=a*0.5+0.25*(displaySample(at-direction*0.5)+displaySample(at+direction*0.5));
    float bY=aaLuma(b);
    vec3 edge=(bY<low||bY>high)?a:b;
    float averageY=(nY+sY+eY+wY)*0.25;
    float subpixel=clamp((abs(averageY-mY)/max(contrast,1e-6)-0.25)/0.75,0.0,1.0);
    subpixel=subpixel*subpixel*(3.0-2.0*subpixel);
    vec3 neighbourhood=(center+n+s+e+w+nw+ne+sw+se)/9.0;
    return mix(edge,neighbourhood,subpixel*clamp(grade.subpixel.x,0.0,1.0));
}
void main() {
    vec3 color=composeScene(uv);
    if(grade.fxaa.x>0.0) {
        vec3 center=clamp(color,vec3(0),vec3(1));
        if(!linearAA())center=linearToSrgb(center);
        vec3 filtered=mix(center,spatialAA(uv,center),clamp(grade.fxaa.x,0.0,1.0));
        color=linearAA()?filtered:srgbToLinear(filtered);
    }
    // Dither after grading and AA, in perceptual code space, then return linear RGB.
    // One shared RGB offset avoids coloured speckles. Static per-eye-pixel noise avoids flicker.
    // A triangular distribution spans +/- one code step at strength 1; zero disables it.
    float strength=max(grade.supersampling.w,0.0);
    if(strength>0.0) {
        uvec2 pixel=uvec2(gl_FragCoord.xy);
        float a=float(sampleMaskHash(pixel,19391u)>>8u)/16777216.0;
        float b=float(sampleMaskHash(pixel,73471u)>>8u)/16777216.0;
        vec3 encoded=linearToSrgb(clamp(color,vec3(0),vec3(1)));
        // Keep an exactly black background black; fully apply noise above two code steps.
        float gate=smoothstep(0.0,2.0/255.0,max(encoded.r,max(encoded.g,encoded.b)));
        encoded=clamp(encoded+vec3((a-b)*strength*gate/255.0),vec3(0),vec3(1));
        color=srgbToLinear(encoded);
    }
    outColor=vec4(max(color,vec3(0)),1.0);
}
```

## 4. 16 · Broad blur / horizontal

<!-- field-box id="text1_blur_broad_h" index="3" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_blur_broad_h" role="properties"]
{"id" "text1_blur_broad_h" "index" 3 "name" "16 · Broad blur / horizontal" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "16 · Broad blur / horizontal" "_tombstone" false "frame" #afield/rect [1800.0 550.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_blur_broad_h_code_.kt" "lines" 4 "characters" 215 "sha256" "502d696a5e93fee970c1cdde3d6f507177ae2ba8985100e33fb1f28b3055bac8"} {"name" "vertex" "language" "glsl" "path" "text1_blur_broad_h_vertex_.glsl" "lines" 10 "characters" 310 "sha256" "bfeef78cb283bfcb51d42e49e10e99971c2d418460670cf8a23279f7fd45a6cf"} {"name" "fragment" "language" "glsl" "path" "text1_blur_broad_h_fragment_.glsl" "lines" 20 "characters" 861 "sha256" "4631f2b27487bb0cc7e210c7578866150db400df9abd6bd045ba7c65518aba71"}]}
```

```kotlin [field-property box="text1_blur_broad_h" name="code" path="text1_blur_broad_h_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(broadHorizontalFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_blur_broad_h" name="vertex" path="text1_blur_broad_h_vertex_.glsl"]
#version 450
#extension GL_EXT_multiview : require
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1.0 - p.y);
    eye = gl_ViewIndex;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="text1_blur_broad_h" name="fragment" path="text1_blur_broad_h_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 uv;
layout(location = 1) flat in int eye;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2DArray source;
layout(push_constant) uniform BlurPush {
    vec4 tapStep; // XY step in output pixels, kernel radius and gain
} pc;

// Nine-tap Gaussian approximation, using bilinear pairing for five texture fetches.
// The output texel size makes H/V isotropic even while the first pass downsamples.
vec4 sampleOffset(float offset) {
    return texture(source, vec3(uv + pc.tapStep.xy * pc.tapStep.z * offset, float(eye)));
}
void main() {
    vec4 c = sampleOffset(0.0) * 0.2270270270;
    c += (sampleOffset(1.3846153846) + sampleOffset(-1.3846153846)) * 0.3162162162;
    c += (sampleOffset(3.2307692308) + sampleOffset(-3.2307692308)) * 0.0702702703;
    outColor = c * pc.tapStep.w;
}
```

## 5. 2 · Marked breath

<!-- field-box id="text1_poem" index="4" -->

```edn [field-box id="text1_poem" role="properties"]
{"id" "text1_poem" "index" 4 "name" "2 · Marked breath" "parents" ["root" "text1_context"] "children" ["text1_score" "text1_drawing"] "properties" {"children" #{#afield/box "text1_score" #afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "2 · Marked breath" "_tombstone" false "frame" #afield/rect [650.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_poem_code_.kt" "lines" 58 "characters" 2441 "sha256" "88a009cf2b4b0931f8717e4b807d11df6211feab2f202ddb2ad8f61e78d6a84f"}]}
```

```kotlin [field-property box="text1_poem" name="code" path="text1_poem_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// 1 · Marked breath. Whole runs, two selected words and an arclength bead.
// Verbatim fragments of the supplied passage; font preparation happens before playback.
val graph = readingDiagram {
    val peg = point("peg", -.78, .52, pinned = true)
    dot("peg-marker", peg.anchor) {
        shape = DiagramPointShape.RING
        radius = .009f
        color = vec(.8f, .43f, .13f, 1f)
    }
    val first = point("first", 0, .24, .025)
    val second = point("second", 0, -.08, -.025)
    link(peg, first)
    link(first, second)
    val opening = text("opening", "Then let go,", first)
    val sentence = text("sentence", "hands lifting—feeling around\nin front of me.", second)
    endpointDots(arc("hanging", peg.anchor, opening.above, rise = .09f) { dashed(.025f) })
    val birds = underline("birdsong", sentence.word("hands"), gap = .018f) {
        width = .002f
        color = vec(.8f, .43f, .13f, 1f)
    }
    underline("walls", sentence.word("me"), gap = .018f) { color = vec(.8f, .43f, .13f, 1f) }
    dot("beside-word", opening.word("Then").left.offset(x = -.028f)) {
        shape = DiagramPointShape.RING
        radius = .005f
    }
    dot("on-underline", birds.at(.65f)) { radius = .0045f }
    val relation = arc(
        "reflection",
        sentence.word("hands").below.offset(y = -.055f),
        sentence.word("me").below.offset(y = -.055f),
        rise = -.23f,
    ) {
        width = .0012f
        arrow = .013f
    }
    endpointDots(relation)
    dot("bead", relation.at(.50f)) { radius = .003f }
}
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 1 / 4: marked breath", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleOne) = ReadingExample(
    "1 · Marked breath",
    "Whole-run spans, a dot beside a word, and dots on an underline and an arc. Two baselines sit 5 cm apart in depth.",
    player,
)
```

## 6. 3 · Constituency canopy

<!-- field-box id="text1_example2" index="5" -->

```edn [field-box id="text1_example2" role="properties"]
{"id" "text1_example2" "index" 5 "name" "3 · Constituency canopy" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "3 · Constituency canopy" "_tombstone" false "frame" #afield/rect [650.0 110.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_example2_code_.kt" "lines" 146 "characters" 6074 "sha256" "3f1276ceaf1f6999d3eb75bc09b91db79294b77c77d9a4e5212b21ac9fc5afec"}]}
```

```kotlin [field-property box="text1_example2" name="code" path="text1_example2_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*
import afield.graphics.text.sentences.*
import kotlin.math.*

// 2 · Complete canopy. Preserve the parser's geometry, while the visible words come from the passage.
val parsed = ReadingPassage.sentence(2)
check(parsed.problems().isEmpty()) { parsed.problems().joinToString() }
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
val layout =
    SentenceDiagram()
        .use(parsed)
        .apply {
            fontSize = 32.0
            wordFont = face.awtFont.deriveFont(32f)
            heights = Heights.Flat()
            layerGap = 55.0
            wordGap = 50.0
            tagFontSize = 13.0
            labelFontSize = 11.0
        }
        .diagram()
// Include the lower arcs and their labels when fitting the whole diagram into reading space.
val bounds = layout.bounds
val cx = bounds.centre.x
val cy = bounds.centre.y
val metres = min(2.15 / bounds.width, 1.7 / bounds.height)
fun world(p: P, z: Double = 0.0) = vec(((p.x - cx) * metres).toFloat(), (-(p.y - cy) * metres).toFloat(), z.toFloat())
fun direction(p: P) = vec((p.x * metres).toFloat(), (-p.y * metres).toFloat(), 0f)
val graph = readingDiagram {
    val nodes =
        layout.nodes.associate { n ->
            val p = world(n.at, .018 * sin(n.id * 1.71) + n.depth * .009)
            n.id to point("node-${n.id}", p.x, p.y, p.z, pinned = n.id == parsed.constituency.id)
        }
    val degrees = mutableMapOf<Int, Int>()
    for (edge in layout.treeEdges) {
        // Physics has four reciprocal slots; the visible topology always includes every branch.
        if (degrees.getOrDefault(edge.parent, 0) < 4 && degrees.getOrDefault(edge.child, 0) < 4) {
            link(nodes.getValue(edge.parent), nodes.getValue(edge.child), .65f)
            degrees[edge.parent] = degrees.getOrDefault(edge.parent, 0) + 1
            degrees[edge.child] = degrees.getOrDefault(edge.child, 0) + 1
        }
        val a = nodes.getValue(edge.parent).anchor
        val b = nodes.getValue(edge.child).anchor
        val c1 = direction(edge.curve.c1 - edge.curve.a)
        val c2 = direction(edge.curve.c2 - edge.curve.b)
        val branch = path(
            "branch-${edge.parent}-${edge.child}",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine().moveTo(from).cubicTo(from + c1, to + c2, to)
            },
        ) {
            width = .0013f
            color = vec(1f, 1f, 1f, .7f)
        }
        endpointDots(branch)
    }
    for (n in layout.nodes) {
        val anchor = nodes.getValue(n.id).anchor
        dot("joint-${n.id}", anchor) {
            radius = if (n.leaf) .0025f else .004f
            shape = DiagramPointShape.RING
        }
    }
    val particles = mutableMapOf<Int, DiagramParticle>()
    val words =
        layout.words.associate { w ->
            val p = world(P(w.centre, w.baseline), .025 * sin(w.token * 1.3))
            val node = point("word-point-${w.token}", p.x, p.y, p.z)
            particles[w.token] = node
            w.token to text("word-${w.token}", w.word, node) { scale = (32.0 * metres / .09).toFloat() }
        }
    val wordLayouts = layout.words.associateBy { it.token }
    // A font-metric baseline anchor follows its word; the offset preserves the original layout.
    fun wordAnchor(token: Int, at: P): DiagramAnchor {
        val w = wordLayouts.getValue(token)
        return words
            .getValue(token)
            .all
            .baseline
            .offset(x = (at.x - w.centre) * metres, y = -(at.y - w.baseline) * metres)
    }
    for (edge in layout.wordEdges) {
        if (degrees.getOrDefault(edge.node, 0) < 4) link(nodes.getValue(edge.node), particles.getValue(edge.token), .6f)
        val a = nodes.getValue(edge.node).anchor
        val b = wordAnchor(edge.token, edge.curve.b)
        val c1 = direction(edge.curve.c1 - edge.curve.a)
        val c2 = direction(edge.curve.c2 - edge.curve.b)
        val stem = path(
            "stem-${edge.token}",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine().moveTo(from).cubicTo(from + c1, to + c2, to)
            },
        ) {
            width = .0010f
            color = vec(1f, 1f, 1f, .6f)
        }
        endpointDots(stem)
    }
    for (w in layout.words) if (w.underlined)
        underline("mark-${w.token}", words.getValue(w.token).all, gap = .010f) {
            color = vec(1f, 1f, 1f, .75f)
            width = .0012f
        }
    for ((i, dep) in layout.arcs.withIndex()) {
        if (dep.edge.isRoot) continue
        val a = wordAnchor(dep.edge.gov, dep.curve.a)
        val b = wordAnchor(dep.edge.dep, dep.curve.b)
        val c1 = direction(dep.curve.c1 - dep.curve.a)
        val c2 = direction(dep.curve.c2 - dep.curve.b)
        val arc = path(
            "dependency-$i",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine().moveTo(from).cubicTo(from + c1, to + c2, to)
            },
        ) {
            width = .0012f
            color = vec(1f, 1f, 1f, .45f)
        }
        endpointDots(arc)
    }
}
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 2 / 4: complete canopy", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.wind = .012f
prop<ReadingExample>(exampleTwo) = ReadingExample(
    "2 · Constituency canopy",
    "The complete constituent tree and word stems, with collapsed dependencies beneath the passage’s words. Curves follow their moving anchors.",
    player,
)
```

## 7. 4 · Dependency undertow

<!-- field-box id="text1_example3" index="6" -->

```edn [field-box id="text1_example3" role="properties"]
{"id" "text1_example3" "index" 6 "name" "4 · Dependency undertow" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "4 · Dependency undertow" "_tombstone" false "frame" #afield/rect [650.0 220.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_example3_code_.kt" "lines" 86 "characters" 3807 "sha256" "9049912afb434c0e7225f4947bae04e07b721bb0566cd964a818d90932110097"}]}
```

```kotlin [field-property box="text1_example3" name="code" path="text1_example3_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*
import afield.graphics.text.sentences.*
import kotlin.math.*

// 3 · Undertow. Nested dependency arcs, with independently scored labels and beads.
val parsed = ReadingPassage.sentence(3)
check(parsed.problems().isEmpty()) { parsed.problems().joinToString() }
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
val layout =
    SentenceDiagram()
        .use(parsed)
        .apply {
            fontSize = 32.0
            wordFont = face.awtFont.deriveFont(32f)
            wordGap = 42.0
            heights = Heights.Wandering(seed = 17, amplitude = 20.0)
            arcLabels = false
            arcSpanRatio = .43
        }
        .diagram()
val cx = (layout.words.first().left + layout.words.last().right) * .5
val top = layout.words.minOf { it.baseline } - 38.0
val bottom = max(layout.arcs.maxOf { it.curve.lowestPoint().y }, layout.words.maxOf { it.baseline }) + 30.0
val cy = (top + bottom) * .5
val metres = min(2.05 / (layout.words.last().right - layout.words.first().left), 1.05 / (bottom - top))
fun world(p: P, z: Float = 0f) = vec(((p.x - cx) * metres).toFloat(), (-(p.y - cy) * metres).toFloat(), z)
val graph = readingDiagram {
    val particles = mutableMapOf<Int, DiagramParticle>()
    val words =
        layout.words.associate { w ->
            val p = world(P(w.centre, w.baseline), (.025 * sin(w.token * 1.5)).toFloat())
            val particle = point("particle-${w.token}", p.x, p.y, p.z, pinned = w.token == 4)
            particles[w.token] = particle
            w.token to text("word-${w.token}", w.word, particle) { scale = (32.0 * metres / .09).toFloat() }
        }
    val physics = words.keys.sorted()
    for ((a, b) in physics.zipWithNext()) link(particles.getValue(a), particles.getValue(b))
    for (w in layout.words) if (w.underlined)
        underline("mark-${w.token}", words.getValue(w.token).all, gap = .012f) { color = vec(.5f, .33f, .14f, 1f) }
    for ((i, dep) in layout.arcs.withIndex()) {
        if (dep.edge.isRoot) continue
        val a = words.getValue(dep.edge.gov).below.offset(y = -.02f)
        val b = words.getValue(dep.edge.dep).below.offset(y = -.02f)
        val originalA = world(dep.curve.a)
        val originalB = world(dep.curve.b)
        val c1 = world(dep.curve.c1) - originalA
        val c2 = world(dep.curve.c2) - originalB
        val path = path(
            "arc-$i",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine()
                    .moveTo(from)
                    .cubicTo(from + c1 + vec(0f, 0f, -dep.lane * .012f), to + c2 + vec(0f, 0f, -dep.lane * .012f), to)
            },
        ) {
            width = .0015f
            arrow = .012f
            if (i % 2 == 0) dashed(.026f)
        }
        dot("bead-$i", path.at(.50f)) {
            radius = .004f
            color = vec(.85f, .48f, .15f, 1f)
        }
        endpointDots(path)
    }
}
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 3 / 4: dependency undertow", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.wind = .010f
prop<ReadingExample>(exampleThree) = ReadingExample(
    "3 · Dependency undertow",
    "The real collapsed dependencies form nested undercurrents. Beads and small endpoint dots follow the moving arcs.",
    player,
)
```

## 8. 5 · Sentence furniture

<!-- field-box id="text1_example4" index="7" -->

```edn [field-box id="text1_example4" role="properties"]
{"id" "text1_example4" "index" 7 "name" "5 · Sentence furniture" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "5 · Sentence furniture" "_tombstone" false "frame" #afield/rect [650.0 330.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_example4_code_.kt" "lines" 88 "characters" 4454 "sha256" "72472c8e6de45e3daa8ab86e073b6cf7e704879a915247695e30966f6fe45c41"}]}
```

```kotlin [field-property box="text1_example4" name="code" path="text1_example4_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*
import afield.graphics.text.sentences.*
import kotlin.math.*

// 4 · Sentence furniture. Reed-Kellogg shelves become a shallow spatial mobile, then a reading line.
val parsed = ReadingPassage.sentence(4)
check(parsed.problems().isEmpty()) { parsed.problems().joinToString() }
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
val config = KelloggConfig(
    slantAngle = 30.0,
    nestAngleStep = 10.0,
    maxSlantAngle = 48.0,
    fontSize = 32.0,
    metrics = Java2DMetrics(face.awtFont.deriveFont(32f)),
    modifierSize = 23.0,
    modifierMetrics = Java2DMetrics(face.awtFont.deriveFont(23f)),
)
val layout = Kellogg(config).layout(parsed)
check(layout.problems().isEmpty()) { layout.problems().joinToString() }
val metres = min(2.15 / layout.bounds.width, 1.1 / layout.bounds.height)
val center = layout.bounds.centre
fun world(p: P, z: Float = 0f) = vec(((p.x - center.x) * metres).toFloat(), (-(p.y - center.y) * metres).toFloat(), z)
val graph = readingDiagram {
    group("furniture") {
        val points = ArrayList<Pair<KWord, DiagramParticle>>()
        for ((i, w) in layout.words.withIndex()) {
            val angle = Math.toRadians(w.angle)
            val midpoint = P(w.at.x + cos(angle) * w.width * .5, w.at.y + sin(angle) * w.width * .5)
            val p = world(midpoint, if (w.role == Role.MODIFIER) -.035f else .02f)
            val particle = point("word-point-$i", p.x, p.y, p.z, pinned = w.role == Role.PREDICATE)
            points.add(w to particle)
            // Kellogg can merge detached clause punctuation into a predicate ("could smell .").
            // Keep a verbatim phrase here; the complete reading run retains the original punctuation.
            val quote =
                w.text.trim().let { label ->
                    if (ReadingPassage.text.contains(label)) label
                    else label.trimEnd { it.isWhitespace() || it in ".,;:!?…" }
                }
            check(ReadingPassage.text.contains(quote)) { "Layout changed the source phrase: $quote" }
            text("word-$i", quote, particle) {
                scale = (w.size * metres / .09).toFloat()
                rotation = -angle.toFloat()
            }
        }
        for ((a, b) in points.zipWithNext()) link(a.second, b.second, .35f)
        fun attached(p: P): DiagramAnchor {
            val position = world(p)
            val nearest = points.minBy { it.second.rest.distanceSquared(position) }.second
            val offset = position - nearest.rest
            return nearest.anchor.offset(offset.x, offset.y, offset.z)
        }
        for ((i, l) in layout.lines.withIndex()) line("shelf-$i", attached(l.a), attached(l.b)) {
            width = if (l.kind == KLineKind.BASELINE) .002f else .0013f
            color = vec(.3f, .49f, .65f, 1f)
            if (l.kind == KLineKind.RISER) dashed(.022f)
        }
        for ((i, entry) in points.withIndex()) if (i % 2 == 0)
            dot("joint-$i", entry.second.anchor.offset(y = -.015f)) {
                radius = .0035f
                shape = DiagramPointShape.RING
            }
    }
    group("reading") {
        val p = point("reading-point", 0, 0, .07, pinned = true)
        val sentence = text("reading-run", parsed.text, p) { scale = .66f }
        val wind = underline("reading-wind", sentence.word("toe"), gap = .018f) { color = vec(.8f, .42f, .12f, 1f) }
        underline("reading-weather", sentence.word("earth"), gap = .018f) { color = vec(.8f, .42f, .12f, 1f) }
        dot("reading-bead", wind.at(.5f)) { radius = .004f }
    }
}
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 4 / 4: sentence furniture", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 24.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.wind = .012f
prop<ReadingExample>(exampleFour) = ReadingExample(
    "4 · Sentence furniture",
    "A Reed-Kellogg structure with gently separated shelves, a softened slant angle, then a scored crossfade into one complete reading run.",
    player,
)
```

## 9. 13 · Typography playground

<!-- field-box id="text1_typography" index="8" -->

```edn [field-box id="text1_typography" role="properties"]
{"id" "text1_typography" "index" 8 "name" "13 · Typography playground" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "13 · Typography playground" "_tombstone" false "frame" #afield/rect [650.0 440.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_typography_code_.kt" "lines" 91 "characters" 3475 "sha256" "75c8c0b9d47ad091e0b9a93ba53c903e05f473aa7d30148e8d5cd0545132578c"}]}
```

```kotlin [field-property box="text1_typography" name="code" path="text1_typography_code_.kt"]
import afield.geometry.path.FLine
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// 5 · A room for words. One Slug run can contain multiple real faces and inline styles.
val serif = Typefaces.named("Noto Serif", 100, strict = true)
val sans = Typefaces.named("Noto Sans", 100, strict = true)
val graph = readingDiagram {
    val peg = point("peg", 0, .25, pinned = true)
    val mobile = point("mobile", 0, .12)
    link(peg, mobile, .35f)
    val sentence = text("sentence", "Each toe could smell the earth it touched.", mobile) {
        font = serif
        scale = .7f
        word("could").style { italic = true }
        word("earth").style { bold = true }
        word("touched").style {
            font = sans
            weight = 300.0
        }
    }
    // Each selection stays in the full shaped run, including kerning, inline fonts and tracking.
    rectangle("whole-ink", sentence.inkBounds, padding = .012f) {
        width = .0008f
        color = vec(1f, 1f, 1f, .20f)
    }
    for ((i, word) in sentence.words.withIndex()) {
        rectangle("word-box-$i", word.bodyBounds, padding = .003f) {
            width = .0008f
            color = vec(1f, 1f, 1f, .35f)
        }
    }
    underline("water", sentence.word("earth"), gap = .018f) {
        width = .0013f
        color = vec(1f, 1f, 1f, .45f)
    }
    dot("caret", sentence.caret(4).alongText(sentence, y = -.025f)) {
        shape = DiagramPointShape.DIAMOND
        radius = .0035f
        color = vec(1f, 1f, 1f, .55f)
    }
    // Author arbitrary curves in font units. The frame supplies the glyphs' exact live transform.
    textPath(
        "cap-ruler",
        sentence,
        shape = {
            FLine().moveTo(advance.left, capHeight + 12f).lineTo(advance.right, capHeight + 12f)
        },
    ) {
        dashed(.015f, .35f)
        width = .0007f
        color = vec(1f, 1f, 1f, .30f)
    }
    // Equal strings with different faces must own distinct prepared outlines and GPU data.
    for (i in 0..2) {
        val p = point("face-$i", -.62 + i * .62, -.18, pinned = true)
        val word = text("face-word-$i", "earth", p) {
            font = if (i == 2) sans else serif
            italic = i == 1
            bold = i == 2
            scale = .62f
        }
        rectangle("face-box-$i", word.inkBounds, padding = .008f) {
            width = .0007f
            color = vec(1f, 1f, 1f, .25f)
        }
    }
    // Numeric weight requests are prepared as real outlines. AWT chooses or synthesizes the face;
    // they are not a fragment-shader dilation and do not claim arbitrary variable-font axes.
    for (i in 0..4) {
        val p = point("weight-$i", -.8 + i * .4, -.46, pinned = true)
        text("weight-word-$i", "rain", p) {
            font = serif
            weight = 300.0 + 100 * i
            scale = .48f
        }
    }
}
val progress = progressReporter("Prepare typography playground", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, serif, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleFive) = ReadingExample(
    "5 · A room for words",
    "Inline italic, bold and alternate faces; repeated words in different fonts; numeric weights; live ink and body boxes, baseline attachments and a cap-height ruler.",
    player,
)
```

## 10. 18 · Elastic sentences

<!-- field-box id="text1_elastic" index="9" -->

```edn [field-box id="text1_elastic" role="properties"]
{"id" "text1_elastic" "index" 9 "name" "18 · Elastic sentences" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "18 · Elastic sentences" "_tombstone" false "frame" #afield/rect [650.0 660.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_elastic_code_.kt" "lines" 177 "characters" 7074 "sha256" "5afcea8b919a6ab83777c5d780aaa8a38eb076e9aa3cbb5e99e17f6d2aa07df5"}]}
```

```kotlin [field-property box="text1_elastic" name="code" path="text1_elastic_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

/*
Verbatim excerpts from texts/source-passage.txt; line breaks are the only layout edits.
Fixed landmarks and sliding constraints share one small connection helper. Only the
interior nodes have inertia: the two visible endpoints always stay on the measured text.
*/
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val readingFrames = mutableListOf<DiagramTextFrame>()
val graph = readingDiagram {
    val door = text("door", "When the door closed\nbehind me", point("door-point", -.62, .22)) {
        font = face
        scale = .55f
    }
    val eyes = text("eyes", "my eyes were going\nto be useless in the dark", point("eyes-point", .65, .28, .35)) {
        font = face
        scale = .55f
    }
    val hands = text("hands", "hands lifting—feeling around\nin front of me.", point("hands-point", .15, -.45, -.3)) {
        font = face
        scale = .55f
    }
    val nonexistence = text("nonexistence", "nonexistence.", point("nonexistence-point", -.8, -.38, .65)) {
        font = Typefaces.firstAvailable("Noto Serif Thin", "Noto Serif Light", "Noto Serif", size = 100)
        scale = .62f
    }
    val sipping = text("sipping", "sipping.", point("sipping-point", 1.4, .05, -.7)) {
        font = face
        italic = true
        scale = .58f
    }
    val taut = text("taut", "this sentence,\npulled taut, goes nowhere:", point("taut-point", -.15, .65, -.5)) {
        font = face
        scale = .52f
    }
    val frames =
        listOf(door, eyes, hands, nonexistence, sipping, taut).associateWith { run ->
            textFrame("${run.id}-frame", run, standoff = .025f, mode = DiagramTextFrameMode.LINE_UNION) {
                    width = .0008f
                    color = vec(1f, 1f, 1f, .22f)
                }
                .also { readingFrames.add(it) }
        }
    val thread = connection(
        "door-thread",
        frames.getValue(door).attach(),
        frames.getValue(eyes).attach(),
        interiorNodes = 8,
    ) {
        responseSeconds = 1.0f
        dampingRatio = .8f
        lengthStiffness = 35f
        curvatureStiffness = 20f
        bow.set(0f, -.14f, .04f)
        tension = .15f
        width = .0015f
        color = vec(1f, 1f, 1f, .3f)
    }
    val marginThread = connection(
        "margin-thread",
        frames.getValue(door).attach(DiagramTextSite.RIGHT_MARGIN),
        frames.getValue(hands).attach(),
        interiorNodes = 6,
    ) {
        responseSeconds = 1.7f
        dampingRatio = 1f
        bow.set(.05f, -.1f, 0f)
        width = .001f
        color = vec(1f, 1f, 1f, .2f)
        dashed(.025f)
    }
    val silenceThread = connection(
        "nonexistence-thread",
        frames.getValue(hands).attach(),
        frames.getValue(nonexistence).attach(),
    ) {
        responseSeconds = 1.4f
        bow.set(0f, -.08f, .06f)
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    val elsewhereThread = connection(
        "sipping-thread",
        frames.getValue(eyes).attach(DiagramTextSite.RIGHT_MARGIN),
        frames.getValue(sipping).attach(),
    ) {
        responseSeconds = 1.1f
        bow.set(.05f, 0f, .04f)
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    val tideThread = connection(
        "taut-thread",
        frames.getValue(door).attach(),
        frames.getValue(taut).attach(),
    ) {
        responseSeconds = 1.8f
        bow.set(0f, .1f, -.06f)
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    // Endpoints use the same live path anchors, including sliding attachments.
    for (line in listOf(thread, marginThread, silenceThread, elsewhereThread, tideThread)) endpointDots(line.path)
}
// Each text plane turns about its own physics point. The stage itself stays fixed in the room.
graph.texts.forEach { it.faceReader = true }
val preparation = progressReporter("Prepare elastic sentences", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 36.0) {}) { n, total, text ->
        preparation.update(n.toDouble() / total, text)
    }
} finally {
        preparation.close()
    }
prop<ReadingExample>(exampleSeven) = ReadingExample(
    "7 · Elastic sentences",
    "Six independently facing text planes in a shallow volume, endpoint dots and fading elastic links. The passage’s nonexistence is lighter; sipping is italic.",
    player,
)
controlGroup("elastic threads") {
    slider("Response", unit = "s", initialValue = 1.0, min = .1, max = 6.0) {
        graph.connections.forEach { c -> c.responseSeconds = it.toFloat() }
    }
    slider("Length stiffness", initialValue = 35.0, min = 0.0, max = 300.0) {
        graph.connections.forEach { c -> c.lengthStiffness = it.toFloat() }
    }
    checkbox("Minimize length", initialValue = false) {
        graph.connections.forEach { c ->
            c.lengthMode = if (it) DiagramConnectionLength.MINIMIZE else DiagramConnectionLength.STABILIZE
        }
    }
    slider("Rest length multiplier", initialValue = 1.0, min = 0.0, max = 3.0) {
        graph.connections.forEach { c -> c.lengthScale = it.toFloat() }
    }
    slider("Curvature minimization", initialValue = 20.0, min = 0.0, max = 300.0) {
        graph.connections.forEach { c -> c.curvatureStiffness = it.toFloat() }
    }
    slider("TCB tension", initialValue = .15, min = -1.0, max = 1.0) {
        graph.connections.forEach { c -> c.tension = it.toFloat() }
    }
    slider("TCB continuity", initialValue = 0.0, min = -1.0, max = 1.0) {
        graph.connections.forEach { c -> c.continuity = it.toFloat() }
    }
    slider("TCB bias", initialValue = 0.0, min = -1.0, max = 1.0) {
        graph.connections.forEach { c -> c.bias = it.toFloat() }
    }
}

controlGroup("individual text planes") {
    checkbox("Each run faces the reader", initialValue = true) {
        graph.texts.forEach { run -> run.faceReader = it }
    }
    slider("Facing half-life", unit = "s", initialValue = .7, min = 0.0, max = 3.0) {
        graph.texts.forEach { run -> run.facingHalfLife = it.toFloat() }
    }
}

controlGroup("text frames") {
    checkbox("Per-line rectangle union", initialValue = true) { enabled ->
        readingFrames.forEach { frame ->
            frame.mode = if (enabled) DiagramTextFrameMode.LINE_UNION else DiagramTextFrameMode.BOUNDS
        }
    }
    checkbox("Merge small steps", initialValue = true) { enabled ->
        readingFrames.forEach { frame -> frame.mergeSmallSteps = enabled }
    }
    slider("Frame standoff", unit = "m", initialValue = .025, min = 0.0, max = .15) {
        readingFrames.forEach { frame -> frame.standoff = it.toFloat() }
    }
    slider("Frame opacity", initialValue = .22, min = 0.0, max = 1.0) {
        readingFrames.forEach { frame -> frame.path.color.w = it.toFloat() }
    }
    slider("Frame width", unit = "mm", initialValue = .8, min = .1, max = 3.0) {
        readingFrames.forEach { frame -> frame.path.width = it.toFloat() * .001f }
    }
}
```

## 11. 19 · A dropped initial

<!-- field-box id="text1_drop_caps" index="10" -->

```edn [field-box id="text1_drop_caps" role="properties"]
{"id" "text1_drop_caps" "index" 10 "name" "19 · A dropped initial" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "19 · A dropped initial" "_tombstone" false "frame" #afield/rect [650.0 770.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_drop_caps_code_.kt" "lines" 82 "characters" 3147 "sha256" "8aac84d791addd13c7d9e8f96fd6a25ff53f09b08ad18ec7ee94378a0a41f3df"}]}
```

```kotlin [field-property box="text1_drop_caps" name="code" path="text1_drop_caps_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

/*
Authored line breaks are part of the poem. The first three lines sit beside the initial;
the fourth returns to the paragraph's left margin. Everything is one measured run, with bounded Slug atlases for its
render parts.
*/
val body = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val readingFrames = mutableListOf<DiagramTextFrame>()
val graph = readingDiagram {
    val paragraph = text(
        "paragraph",
        "When the door closed behind me\nand I saw my eyes were going\nto be useless in the dark,\nI took myself in hand.",
        point("paragraph-point", -.15, .25),
    ) {
        font = body
        scale = .65f
        blurCapacity = .8f // reserve body-em Gaussian range before Drawing builds its caches
        dropCap(lines = 3, gapEm = .2f) {
            font = body
            weight = 600.0
        }
    }
    val aside = text("aside", "Then let go,", point("aside-point", .5, -.4, .03)) {
        font = body
        italic = true
        scale = .45f
    }
    val paragraphFrame = textFrame("paragraph-frame", paragraph, standoff = .025f, mode = DiagramTextFrameMode.LINE_UNION) {
        color = vec(1f, 1f, 1f, .22f)
    }
    val asideFrame = textFrame("aside-frame", aside, standoff = .025f, mode = DiagramTextFrameMode.LINE_UNION) {
        color = vec(1f, 1f, 1f, .22f)
    }
    readingFrames.addAll(listOf(paragraphFrame, asideFrame))
    val thread = connection(
        "initial-thread",
        paragraphFrame.attach(),
        asideFrame.attach(DiagramTextSite.LEFT_MARGIN),
    ) {
        responseSeconds = 1.2f
        bow.set(0f, -.07f, .025f)
        width = .001f
        color = vec(1f, 1f, 1f, .2f)
    }
    endpointDots(thread.path)
}
graph.texts.forEach { it.faceReader = true }
val progress = progressReporter("Prepare a dropped initial", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, body, "field-slug".file, diagramScore(graph, 30.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, text)
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleEight) = ReadingExample(
    "8 · A dropped initial",
    "An ink-fitted three-line initial, body text returning to the left margin, and an elastic connection anchored to the initial itself.",
    player,
)

controlGroup("drop-cap paragraph") {
    slider("Paragraph blur", unit = "body em", initialValue = 0.0, min = 0.0, max = .8) {
        graph.text("paragraph").blur = it.toFloat()
    }
}

controlGroup("text frames") {
    checkbox("Per-line rectangle union", initialValue = true) { enabled ->
        readingFrames.forEach { frame ->
            frame.mode = if (enabled) DiagramTextFrameMode.LINE_UNION else DiagramTextFrameMode.BOUNDS
        }
    }
    checkbox("Merge small steps", initialValue = true) { enabled ->
        readingFrames.forEach { frame -> frame.mergeSmallSteps = enabled }
    }
    slider("Frame standoff", unit = "m", initialValue = .025, min = 0.0, max = .15) {
        readingFrames.forEach { frame -> frame.standoff = it.toFloat() }
    }
}
```

## 12. 22 · Two readings / composed sequence

<!-- field-box id="text1_two_readings" index="11" -->

```edn [field-box id="text1_two_readings" role="properties"]
{"id" "text1_two_readings" "index" 11 "name" "22 · Two readings / composed sequence" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "22 · Two readings / composed sequence" "_tombstone" false "frame" #afield/rect [650.0 880.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_two_readings_code_.kt" "lines" 108 "characters" 4415 "sha256" "3406ee2d96570c6a9ce0dc9deb3abb7584fc9f42d5b20a656813700e7b41f29f"}]}
```

```kotlin [field-property box="text1_two_readings" name="code" path="text1_two_readings_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Two verbatim excerpts, with authored line breaks. These are independent physical runs,
// not a single billboard: the near surround can frost part of the later, more distant run.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val near = text(
        "near-reading",
        "My words found a few\nlast handholds of their own\nonce my body collapsed.",
        point("near-point", -.14, -.04, .28),
    ) {
        font = face
        scale = .58f
        orientation.rotateY(-.10f)
        blurCapacity = 1.5f // both entrance and exit tracks reserve their Gaussian range before Drawing
    }
    val far = text(
        "far-reading",
        "A whole school of ideas\nswam before me,\nbut I could hook only one:\ndon’t believe what you see.",
        point("far-point", .14, .11, -.24),
    ) {
        font = face
        scale = .56f
        orientation.rotateY(.12f)
        blurCapacity = 1.5f
    }
    val nearFrame = textFrame("near-frame", near, .025f, DiagramTextFrameMode.LINE_UNION) {
        width = .0007f
        color = vec(1f, 1f, 1f, .10f)
    }
    val farFrame = textFrame("far-frame", far, .025f, DiagramTextFrameMode.LINE_UNION) {
        width = .0007f
        color = vec(1f, 1f, 1f, .10f)
    }
    val thread = connection("reading-thread", nearFrame.attach(), farFrame.attach(), interiorNodes = 8) {
        responseSeconds = 1.6f
        dampingRatio = .9f
        lengthStiffness = 25f
        curvatureStiffness = 18f
        bow.set(0f, -.19f, .10f)
        tension = .15f
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    endpointDots(thread.path, radius = .002f, opacity = .4f)
}

// This example owns its score. Box 6 deliberately leaves it alone when re-scoring other examples.
// All visible paths fade; none draw themselves in. Dots lead the connecting thread by .7 seconds.
val score = diagramScore(graph, 36.0) {
    val near = graph.text("near-reading")
    val far = graph.text("far-reading")
    fadeIn(near, at = 0.0, seconds = 5.0)
    blurIn(near, at = 0.0, seconds = 8.0, radius = .75f)
    fadeIn(graph.path("near-frame"), at = .7, seconds = 5.0)
    fadeIn(far, at = 5.0, seconds = 5.5)
    blurIn(far, at = 5.0, seconds = 9.0, radius = .75f)
    fadeIn(graph.path("far-frame"), at = 6.0, seconds = 5.0)
    for (dot in graph.dots) fadeIn(dot, at = 6.4, seconds = 1.0)
    fadeIn(graph.path("reading-thread"), at = 7.1, seconds = 2.9)

    // Let the room stay readable, then release the two runs on different clocks.
    blurOut(near, at = 22.0, seconds = 7.0, radius = .75f)
    fadeOut(near, at = 22.0, seconds = 6.0)
    fadeOut(graph.path("near-frame"), at = 24.0, seconds = 5.0)
    blurOut(far, at = 26.0, seconds = 8.0, radius = .75f)
    fadeOut(far, at = 27.0, seconds = 6.0)
    fadeOut(graph.path("far-frame"), at = 28.0, seconds = 6.0)
    fadeOut(graph.path("reading-thread"), at = 25.0, seconds = 6.0)
    for (dot in graph.dots) fadeOut(dot, at = 26.0, seconds = 5.0)
}
val progress = progressReporter("Prepare two readings", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, score) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.spring = 3f
player.dampingRate = 3f
player.wind = 0f
player.gravity = 0f
prop<ReadingExample>(exampleNine) = ReadingExample(
    "9 · Two readings",
    "Staggered blur/fade entrances, overlapping frosted rectlet surrounds, a dotted elastic thread and body-responsive text. Both runs sharpen by 14 seconds; they dissolve separately from 22 seconds.",
    player,
)

controlGroup("sequence") {
    button("Replay this sequence") { down ->
        if (down) {
            player.restart()
            player.playback.playing = true
        }
    }
    button("Hold both readings") { down ->
        if (down) {
            player.playback.seek(16.0)
            player.playback.playing = false
        }
    }
}
display(
    "timing",
    "<div style='padding:12px;width:440px'>Near reading: enter 0–8s, dissolve 22–29s.<br>Far reading: enter 5–14s, dissolve 26–34s.<br>Dots lead the thread; the thread fades at 7.1s. Both text anchors are unpinned, so the body can waft them and their surrounds away.</div>",
)
```

## 13. 24 · Extended rectangle

<!-- field-box id="text1_overstroke_rectangle" index="12" -->

```edn [field-box id="text1_overstroke_rectangle" role="properties"]
{"id" "text1_overstroke_rectangle" "index" 12 "name" "24 · Extended rectangle" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "24 · Extended rectangle" "_tombstone" false "frame" #afield/rect [-1300.0 150.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_overstroke_rectangle_code_.kt" "lines" 55 "characters" 1990 "sha256" "8a91cadcdcc3df20c7d61d02e0e95dcefd16ae8583f3b5e81baaf50f16036227"}]}
```

```kotlin [field-property box="text1_overstroke_rectangle" name="code" path="text1_overstroke_rectangle_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val words = text("words", "And now this sentence,\npulled taut, goes nowhere:", point("p", 0, 0)) {
        font = face
        scale = .7f
        blurCapacity = .8f
    }
    textFrame(
        "holder",
        words,
        standoff = .028f,
        overstrokeFraction = .08f,
        overstrokeDistance = .012f,
    ) {
        width = .0007f
        color = vec(1f, 1f, 1f, .2f)
    }.frost
        .apply {
            enabled = true
            blurPixels = 7f
        }
}
val score = diagramScore(graph, 28.0) {
    val holder = graph.textFrame("holder")
    fadeIn(graph.text("words"), at = 0.0, seconds = 4.0)
    blurIn(graph.text("words"), at = 0.0, seconds = 6.0, radius = .6f)
    fadeIn(holder, at = .7, seconds = 4.0)
    // Eight outward corner extensions change length gently; the rectangle itself stays complete.
    overstroke(holder, .08f, .2f, at = 7.0, seconds = 7.0, fromDistance = .012f, toDistance = .025f)
    blurOut(graph.text("words"), at = 19.0, seconds = 7.0, radius = .6f)
    fadeOut(graph.text("words"), at = 23.0, seconds = 4.0)
    fadeOut(holder, at = 22.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 10 · Extended rectangle", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, score) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.spring = 8f
player.dampingRate = 4f
player.gravity = 0f
player.wind = 0f
prop<ReadingExample>(exampleTen) = ReadingExample(
    "10 · Extended rectangle",
    "Eight outward corner extensions of a rectangular frame; a seekable score lengthens these mechanical marks.",
    player,
)
```

## 14. 25 · Union with corner extensions

<!-- field-box id="text1_overstroke_union" index="13" -->

```edn [field-box id="text1_overstroke_union" role="properties"]
{"id" "text1_overstroke_union" "index" 13 "name" "25 · Union with corner extensions" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "25 · Union with corner extensions" "_tombstone" false "frame" #afield/rect [-1300.0 260.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_overstroke_union_code_.kt" "lines" 59 "characters" 2156 "sha256" "d4c33bdfe873f992b753fd4d8041194aaf21aa4a07e486356740320334b40936"}]}
```

```kotlin [field-property box="text1_overstroke_union" name="code" path="text1_overstroke_union_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val words = text("words", "Each toe\ncould smell the earth\nit touched.", point("p", 0, 0)) {
        font = face
        italic = true
        scale = .78f
        orientation.rotateY(.13f)
        blurCapacity = .8f
    }
    textFrame(
        "holder",
        words,
        standoff = .024f,
        mode = DiagramTextFrameMode.LINE_UNION,
        overstrokeFraction = .12f,
        overstrokeDistance = .015f,
    ) {
        width = .0007f
        color = vec(1f, 1f, 1f, .18f)
    }.frost
        .apply {
            enabled = true
            blurPixels = 8f
        }
}
val score = diagramScore(graph, 28.0) {
    val holder = graph.textFrame("holder")
    fadeIn(graph.text("words"), at = 0.0, seconds = 4.0)
    blurIn(graph.text("words"), at = 0.0, seconds = 6.0, radius = .6f)
    fadeIn(holder, at = 1.0, seconds = 3.0)
    // Eight extensions belong to the overall bounds; no rectlet seam is prolonged.
    overstroke(holder, .12f, .25f, at = 7.0, seconds = 8.0)
    move(graph.text("words"), vec(0f, 0f, 0f), vec(.045f, .025f, .035f), at = 8.0, seconds = 8.0)
    blurOut(graph.text("words"), at = 19.0, seconds = 7.0, radius = .6f)
    fadeOut(graph.text("words"), at = 23.0, seconds = 4.0)
    fadeOut(holder, at = 22.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 11 · Union with corner extensions", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, score) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.spring = 8f
player.dampingRate = 4f
player.gravity = 0f
player.wind = 0f
prop<ReadingExample>(exampleEleven) = ReadingExample(
    "11 · Union with corner extensions",
    "Eight outer extensions surround a frosted rectlet union; the union and sliding boundary remain unchanged.",
    player,
)
```

## 15. 26 · Transparent text frustum

<!-- field-box id="text1_frustum_readings" index="14" -->

```edn [field-box id="text1_frustum_readings" role="properties"]
{"id" "text1_frustum_readings" "index" 14 "name" "26 · Transparent text frustum" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "26 · Transparent text frustum" "_tombstone" false "frame" #afield/rect [-1300.0 370.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_frustum_readings_code_.kt" "lines" 57 "characters" 2041 "sha256" "b7d931356c06ac651a6e676e51772458353aa395a90fc726fa18dff2be417044"}]}
```

```kotlin [field-property box="text1_frustum_readings" name="code" path="text1_frustum_readings_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val near = point("near", -.04, -.02, .18)
    val far = point("far", .22, .15, -.25)
    link(near, far)
    val front = text("front", "Marble.\nGoing back to antiquity.", near) {
        font = face
        scale = .72f
        blurCapacity = .8f
    }
    val back = text("back", "Faintly hinted,\nthe heady scent of a rose.", far) {
        font = face
        scale = .44f
        blurCapacity = .8f
    }
    textFrustum("holder", front, back, standoff = .025f) {
        width = .0007f
        color = vec(1f, 1f, 1f, .17f)
        aspectRatio = 3.2f
        frost {
            enabled = true
            blurPixels = 7f
            brightness = 1.1f
        }
    }
}
val score = diagramScore(graph, 30.0) {
    val holder = graph.textFrustum("holder")
    fadeIn(holder, at = 0.0, seconds = 4.0)
    blurIn(holder, at = 0.0, seconds = 7.0, radius = .6f)
    // Move the end texts once; both fitted frames, frosting and rails follow those poses.
    move(holder, vec(0f, 0f, 0f), vec(.06f, .03f, .02f), at = 8.0, seconds = 8.0)
    blurOut(holder, at = 21.0, seconds = 7.0, radius = .6f)
    fadeOut(holder, at = 24.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 12 · A transparent text frustum", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, score) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.spring = 8f
player.dampingRate = 4f
player.gravity = 0f
player.wind = 0f
prop<ReadingExample>(exampleTwelve) = ReadingExample(
    "12 · A transparent text frustum",
    "Two frosted, text-fitted rectangular ends share an aspect and four rails; a whole-holder cue moves both ends.",
    player,
)
```

## 16. 27 · A holder with two faces

<!-- field-box id="text1_frustum_two_faces" index="15" -->

```edn [field-box id="text1_frustum_two_faces" role="properties"]
{"id" "text1_frustum_two_faces" "index" 15 "name" "27 · A holder with two faces" "parents" ["root" "text1_context"] "children" ["text1_score"] "properties" {"children" #{#afield/box "text1_score"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "27 · A holder with two faces" "_tombstone" false "frame" #afield/rect [-1300.0 480.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_frustum_two_faces_code_.kt" "lines" 67 "characters" 2650 "sha256" "677385001e9760c88ef4b8ff34e5619859fad22052ec900185f14606102a6af9"}]}
```

```kotlin [field-property box="text1_frustum_two_faces" name="code" path="text1_frustum_two_faces_code_.kt"]
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val near = point("near", -.04, -.02, .22)
    val far = point("far", .12, .08, -.28)
    link(near, far)
    val front = text("front", "How hard it is for a butterfly\nflitting around in the air\nto pronounce itself…", near) {
        font = face
        scale = .56f
        orientation.rotateY(-.12f)
        blurCapacity = .9f
    }
    val back = text("back", "until it alights\non a flower—", far) {
        font = face
        italic = true
        scale = .4f
        // The second end reads from outside the back. Corner matching keeps the cage untwisted.
        orientation.rotateY(-.12f + kotlin.math.PI.toFloat())
        blurCapacity = .9f
    }
    textFrustum("holder", front, back, standoff = .025f) {
        width = .0007f
        color = vec(1f, 1f, 1f, .15f)
        frontFrame.overstrokeFraction = .08f
        frontFrame.overstrokeDistance = .01f
        backFrame.overstrokeFraction = .08f
        backFrame.overstrokeDistance = .01f
        frost {
            enabled = true
            blurPixels = 8f
            brightness = 1.1f
        }
    }
}
val score = diagramScore(graph, 34.0) {
    val holder = graph.textFrustum("holder")
    fadeIn(holder, at = 0.0, seconds = 5.0)
    blurIn(holder.front, at = 0.0, seconds = 8.0, radius = .65f)
    blurIn(holder.back, at = 2.0, seconds = 8.0, radius = .65f)
    // Ordinary text cues change depth and taper, while the frame fit and rail endpoints stay exact.
    move(holder.back, vec(0f, 0f, 0f), vec(.04f, .03f, -.14f), at = 10.0, seconds = 8.0)
    resize(holder.back, 1f, .72f, at = 10.0, seconds = 8.0)
    overstroke(holder.frontFrame, .08f, .18f, at = 10.0, seconds = 8.0)
    blurOut(holder, at = 24.0, seconds = 8.0, radius = .65f)
    fadeOut(holder, at = 28.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 13 · A holder with two faces", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, score) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.spring = 8f
player.dampingRate = 4f
player.gravity = 0f
player.wind = 0f
prop<ReadingExample>(exampleThirteen) = ReadingExample(
    "13 · A holder with two faces",
    "Walk around to read the outward-facing back; its scored movement and scale open and taper the device.",
    player,
)
```

## 17. 6 · Scores / example selector

<!-- field-box id="text1_score" index="16" -->

```edn [field-box id="text1_score" role="properties"]
{"id" "text1_score" "index" 16 "name" "6 · Scores / example selector" "parents" ["root" "text1_poem" "text1_example2" "text1_example3" "text1_example4" "text1_typography" "text1_word_blur" "text1_elastic" "text1_drop_caps" "text1_two_readings" "text1_overstroke_rectangle" "text1_overstroke_union" "text1_frustum_readings" "text1_frustum_two_faces"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_poem" #afield/box "text1_example2" #afield/box "text1_example3" #afield/box "text1_example4" #afield/box "text1_typography" #afield/box "text1_word_blur" #afield/box "text1_elastic" #afield/box "text1_drop_caps" #afield/box "text1_two_readings" #afield/box "text1_overstroke_rectangle" #afield/box "text1_overstroke_union" #afield/box "text1_frustum_readings" #afield/box "text1_frustum_two_faces"} "name" "6 · Scores / example selector" "_tombstone" false "frame" #afield/rect [1200.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_score_code_.kt" "lines" 116 "characters" 5399 "sha256" "e0c7b456c6b4e39d31177e20aa21b155f1b40859fca8cd4b430d168e6d4d7d03"}]}
```

```kotlin [field-property box="text1_score" name="code" path="text1_score_code_.kt"]
import afield.graphics.scene.reading.*

/*
The gallery scores are separate from graph construction and native typography preparation.
Endpoint dots lead their curves by about .7 seconds; curves only change opacity.
Re-run this box to rescore the existing players; their physics and Slug buffers are retained.
*/
val allExamples = listOf(
    exampleOne,
    exampleTwo,
    exampleThree,
    exampleFour,
    exampleFive,
    exampleSix,
    exampleSeven,
    exampleEight,
    exampleNine,
    exampleTen,
    exampleEleven,
    exampleTwelve,
    exampleThirteen,
)
for ((index, example) in allExamples.withIndex()) {
    if (index >= 8) continue // Two readings and the mechanical holders own their complete scores.
    val graph = example.player.diagram
    example.player.playback.score = diagramScore(
        graph,
        duration =
            if (index == 7) 30.0
            else if (index == 6) 36.0 else if (index == 5) 32.0 else if (index == 3) 24.0 else 20.0,
    ) {
            if (index == 7) {
                for (text in graph.texts) fadeIn(text, at = 0.0, seconds = 4.0)
                for (path in graph.paths) fadeIn(path, at = 1.0, seconds = 5.0)
                for (dot in graph.dots) fadeIn(dot, at = .3, seconds = 1.0)
                move(graph.text("aside"), vec(0f, 0f, 0f), vec(-.1f, .08f, 0f), at = 6.0, seconds = 14.0)
                exitAll(at = 26.0, seconds = 4.0, erasePaths = false)
            } else if (index == 6) {
                for (text in graph.texts) fadeIn(text, at = 0.0, seconds = 2.0)
                for (path in graph.paths) fadeIn(path, at = .7, seconds = 3.0)
                for (dot in graph.dots) fadeIn(dot, at = 0.0, seconds = .7)
                move(graph.text("eyes"), vec(0f, 0f, 0f), vec(.12f, -.3f, .08f), at = 4.0, seconds = 12.0)
                move(graph.text("eyes"), vec(0f, 0f, 0f), vec(-.12f, .3f, -.08f), at = 18.0, seconds = 12.0)
            } else if (index == 5) {
                for ((i, word) in graph.texts.withIndex()) {
                    blurIn(word, at = i * .7, seconds = 7.0, radius = .9f)
                    fadeIn(word, at = i * .7, seconds = 3.5)
                    blurOut(word, at = 18.0 + i * .7, seconds = 8.0, radius = 1.2f)
                    fadeOut(word, at = 24.0 + i * .7, seconds = 5.0)
                }
            } else if (index == 3) {
                val furniture = graph.group("furniture")
                val reading = graph.group("reading")
                for (text in furniture.items.filterIsInstance<DiagramText>()) fadeIn(text, at = 0.0, seconds = 1.0)
                for (dot in furniture.items.filterIsInstance<DiagramDot>()) fadeIn(dot, at = 0.0, seconds = .7)
                for ((i, path) in furniture.items.filterIsInstance<DiagramPath>().withIndex()) fadeIn(
                    path,
                    at = .7 + i * .06,
                    seconds = 1.2,
                )
                fadeOut(furniture, at = 9.0, seconds = 2.4)
                for (text in reading.items.filterIsInstance<DiagramText>()) fadeIn(text, at = 9.0, seconds = 2.4)
                for (dot in reading.items.filterIsInstance<DiagramDot>()) fadeIn(dot, at = 8.3, seconds = .7)
                for (path in reading.items.filterIsInstance<DiagramPath>()) fadeIn(path, at = 9.0, seconds = 2.4)
                exitAll(at = 20.0, seconds = 2.5, erasePaths = false)
            } else {
                for ((i, text) in graph.texts.withIndex()) fadeIn(text, at = i * .16, seconds = 1.0)
                for (path in graph.paths) fadeIn(path, at = .7, seconds = 1.2)
                for (dot in graph.dots) fadeIn(dot, at = 0.0, seconds = .7)
                exitAll(at = 17.0, seconds = 2.5, erasePaths = false)
            }
        }
    example.player.playback.seek(6.0)
}
// Keep the gallery through a re-score, replacing it only when graph construction
// produced different players. once/prop are the normal persistent property path.
prop<ReadingGallery>(poem)
once.poem { ReadingGallery(allExamples) }
if (poem.examples.map { it.player } != allExamples.map { it.player }) poem = ReadingGallery(allExamples)

fun describe() {
    val e = poem.examples[poem.selected]
    display("selected example", "<div style='padding:12px;width:440px'><b>${e.title}</b><p>${e.description}</p></div>")
}
controlGroup("reading spaces") {
    for ((i, e) in allExamples.withIndex()) button(e.title) { down ->
        if (down) {
            poem.select(i)
            poem.current.playback.seek(if (i >= 8) 0.0 else 6.0)
            if (i >= 8) poem.current.playback.playing = true
            describe()
        }
    }
}
controlGroup("score") {
    checkbox("Play score", initialValue = true) { poem.current.playback.playing = it }
    checkbox("Repeat score", initialValue = true) { poem.current.playback.repeat = it }
    slider("Score time", unit = "s", initialValue = 6.0, min = 0.0, max = 36.0) {
        poem.current.playback.playing = false
        poem.current.playback.seek(it.toDouble())
    }
    button("Restart entrances") { down ->
        if (down) {
            poem.current.restart()
            poem.current.playback.playing = true
        }
    }
    button("Show complete diagram") { down ->
        if (down) {
            poem.current.playback.playing = false
            poem.current.playback.seek(if (poem.selected >= 8) 16.0 else 6.0)
        }
    }
}

describe()
```

## 18. 7 · FLine strokes

<!-- field-box id="text1_stroke" index="17" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_stroke" role="properties"]
{"id" "text1_stroke" "index" 17 "name" "7 · FLine strokes" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "7 · FLine strokes" "_tombstone" false "frame" #afield/rect [1800.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_stroke_code_.kt" "lines" 4 "characters" 212 "sha256" "c8359ab0081c678a5886f5803d7aa38ba11de15fc3891484051754f5ab273d0f"} {"name" "vertex" "language" "glsl" "path" "text1_stroke_vertex_.glsl" "lines" 121 "characters" 5151 "sha256" "d2310aaf9fbc8bfbf276debe12a15b81a75073748d40bef2049f6a41b90a1756"} {"name" "fragment" "language" "glsl" "path" "text1_stroke_fragment_.glsl" "lines" 46 "characters" 2293 "sha256" "2b99df22a8e0c0bbfe075e1fed7a782de28bfe46f764ed88dcf5c20e546ac075"}]}
```

```kotlin [field-property box="text1_stroke" name="code" path="text1_stroke_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(strokeModuleFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_stroke" name="vertex" path="text1_stroke_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
struct Segment {
    vec4 previous; // previous centreline XYZ, half-width at A in metres
    vec4 a; // segment start XYZ, cumulative path distance
    vec4 b; // segment end XYZ, cumulative path distance
    vec4 next; // next centreline XYZ, half-width at B in metres
    vec4 colorA; // linear RGBA at segment start
    vec4 colorB; // linear RGBA at segment end
    vec4 ink; // dash period, duty, phase and first/last flags
    vec4 trim; // visible path-distance interval, two reserved values
};
layout(std430,buffer_reference) readonly buffer DiagramSegments {
    Segment item[]; // CPU-uploaded 128-byte records, shared by both eyes
};
layout(push_constant) uniform DiagramPush {
    mat4 model; // diagram-local to world, shared by both eyes
    DiagramSegments diagramData; // CPU-authored centreline samples, never eye-dependent triangles
    vec4 options; // viewport width/height, minimum stroke/marker radius in pixels
    vec4 clipPlane; // world-space backdrop clipping, zero for the main scene
} pc;
#extension GL_EXT_multiview : require
layout(std140, set=0, binding=7) uniform StereoViews {
    mat4 stereoVpL; // left-eye projection/view
    mat4 stereoVpR; // right-eye projection/view
    vec4 stereoRightL; // left-eye camera right
    vec4 stereoRightR; // right-eye camera right
    vec4 stereoUpL; // left-eye camera up
    vec4 stereoUpR; // right-eye camera up
    vec4 stereoProjectionL; // projection XY and viewport size
    vec4 stereoProjectionR; // projection XY and viewport size
};

mat4 stereoViewProjection() {
    return gl_ViewIndex==0 ? stereoVpL : stereoVpR;
}
vec3 stereoCameraRight() {
    return (gl_ViewIndex==0 ? stereoRightL : stereoRightR).xyz;
}
vec3 stereoCameraUp() {
    return (gl_ViewIndex==0 ? stereoUpL : stereoUpR).xyz;
}
vec4 stereoProjection() {
    return gl_ViewIndex==0 ? stereoProjectionL : stereoProjectionR;
}
layout(location=0) flat out vec4 endpoints;
layout(location=1) flat out vec4 widthsArc;
layout(location=2) flat out vec4 colorA;
layout(location=3) flat out vec4 colorB;
layout(location=4) flat out vec4 ink;
layout(location=5) flat out vec4 limits;
vec2 pixel(vec4 q) {
    return vec2(q.x/q.w*.5+.5,.5-q.y/q.w*.5)*pc.options.xy;
}
vec2 unit(vec2 p,vec2 fallback) {
    return dot(p,p)>1e-10?normalize(p):fallback;
}
const vec2 corners[6]=vec2[6](vec2(0,-1),vec2(1,-1),vec2(1,1),vec2(0,-1),vec2(1,1),vec2(0,1));
layout(location=6) out float backdropDistance;
void main() {
    backdropDistance=0;
    Segment s=pc.diagramData.item[gl_VertexIndex/6];
    // Do not rasterize unrevealed tessellation segments. Clamped arclength derivatives
    // at their joins can otherwise turn distant AA tails into a dotted preview.
    float capPadding=max(s.previous.w,s.next.w)+.0001;
    if(s.trim.y<=s.trim.x||s.a.w>s.trim.y+capPadding||s.b.w<s.trim.x-capPadding) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    mat4 vp=gl_ViewIndex==0?stereoVpL:stereoVpR;
    vec4 projection=gl_ViewIndex==0?stereoProjectionL:stereoProjectionR;
    mat4 m=vp*pc.model;
    vec4 a=m*vec4(s.a.xyz,1),b=m*vec4(s.b.xyz,1);
    // Clip the centreline before division; never turn a behind-eye endpoint into a giant ribbon.
    float enter=0,leave=1;
    float da=a.z,db=b.z;
    if(da<0&&db<0) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    if(da<0)enter=max(enter,-da/(db-da));
    if(db<0)leave=min(leave,da/(da-db));
    if(enter>=leave||max(a.w,b.w)<1e-6) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    vec4 ca=mix(a,b,enter),cb=mix(a,b,leave);
    ca.w=max(ca.w,1e-6);
    cb.w=max(cb.w,1e-6);
    vec2 pa=pixel(ca),pb=pixel(cb),axis=unit(pb-pa,vec2(1,0));
    vec4 prev=m*vec4(s.previous.xyz,1),next=m*vec4(s.next.xyz,1);
    vec2 before=prev.w>1e-6&&enter==0?unit(pa-pixel(prev),axis):axis;
    vec2 after=next.w>1e-6&&leave==1?unit(pixel(next)-pb,axis):axis;
    vec2 normal=vec2(-axis.y,axis.x);
    vec2 ma=unit(vec2(-before.y,before.x)+normal,normal);
    vec2 mb=unit(vec2(-after.y,after.x)+normal,normal);
    ma/=max(dot(ma,normal),.25);
    mb/=max(dot(mb,normal),.25);
    float focal=abs(projection.y)*pc.options.y*.5;
    float modelScale=length(pc.model[0].xyz);
    float ra=max(mix(s.previous.w,s.next.w,enter)*modelScale*focal/ca.w,pc.options.z);
    float rb=max(mix(s.previous.w,s.next.w,leave)*modelScale*focal/cb.w,pc.options.z);
    vec2 corner=corners[gl_VertexIndex%6];
    bool atEnd=corner.x>.5;
    vec4 clip=atEnd?cb:ca;
    vec2 p=atEnd?pb:pa;
    p+=(atEnd?mb:ma)*corner.y*((atEnd?rb:ra)+1.5);
    uint flags=uint(s.ink.w);
    if(!atEnd&&((flags&1u)!=0u||enter>0))p-=axis*(ra+1.5);
    if(atEnd&&((flags&2u)!=0u||leave<1))p+=axis*(rb+1.5);
    clip.xy=vec2(p.x/pc.options.x*2-1,1-p.y/pc.options.y*2)*clip.w;
    gl_Position=clip;
    backdropDistance=dot(pc.clipPlane,inverse(vp)*clip);
    endpoints=vec4(pa,pb);
    widthsArc=vec4(ra,rb,mix(s.a.w,s.b.w,enter),mix(s.a.w,s.b.w,leave));
    colorA=mix(s.colorA,s.colorB,enter);
    colorB=mix(s.colorA,s.colorB,leave);
    ink=s.ink;
    limits=vec4(s.trim.xy,ca.w,cb.w);
}
```

```glsl [field-property box="text1_stroke" name="fragment" path="text1_stroke_fragment_.glsl"]
#version 450
layout(location=0) flat in vec4 endpoints;
layout(location=1) flat in vec4 widthsArc;
layout(location=2) flat in vec4 colorA;
layout(location=3) flat in vec4 colorB;
layout(location=4) flat in vec4 ink;
layout(location=5) flat in vec4 limits;
layout(location=0) out vec4 outColor;
layout(location=6) in float backdropDistance;
void main() {
    if(backdropDistance<0)discard;
    // Empty reveal intervals must never leave a rounded cap behind.
    if(limits.y<=limits.x)discard;
    vec2 delta=endpoints.zw-endpoints.xy;
    float len=length(delta);
    if(len<1e-6)discard;
    vec2 axis=delta/len,q=gl_FragCoord.xy-endpoints.xy;
    float along=dot(q,axis),side=dot(q,vec2(-axis.y,axis.x));
    float t=clamp(along/len,0,1);
    // Perspective-correct world arclength; pixel-space coverage stays per-eye and spatial only.
    float worldT=(t/limits.w)/max((1-t)/limits.z+t/limits.w,1e-8);
    float arc=mix(widthsArc.z,widthsArc.w,worldT);
    float radius=mix(widthsArc.x,widthsArc.y,t);
    float sdf=abs(side)-radius;
    uint flags=uint(ink.w);
    float outside=max((flags&1u)!=0u?-along:0,(flags&2u)!=0u?along-len:0);
    if(outside>0)sdf=length(vec2(outside,side))-radius;
    float unitsPerPixel=max(fwidth(arc),1e-7);
    // Rounded reveal and dash ends use the same SDF as the outer edge.
    float trimOutside=max(limits.x-arc,arc-limits.y)/unitsPerPixel;
    // Reject distant unrevealed fragments before differentiating the cap SDF.
    // At tessellation joins its derivative can otherwise inflate AA into isolated dots.
    if(trimOutside>radius+1.5)discard;
    if(trimOutside>0)sdf=max(sdf,length(vec2(trimOutside,side))-radius);
    if(ink.x>1e-6) {
        float local=mod(arc+ink.z,ink.x)-ink.x*.5;
        float dashOutside=(abs(local)-ink.x*clamp(ink.y,.01,1)*.5)/unitsPerPixel;
        if(dashOutside>0)sdf=max(sdf,length(vec2(dashOutside,side))-radius);
    }
    float coverage=clamp(.5-sdf/max(fwidth(sdf),.75),0,1);
    // Grow sub-diameter reveals into a stroke instead of popping in a full cap-sized dot.
    coverage*=clamp((limits.y-limits.x)/unitsPerPixel/max(2*radius,1),0,1);
    vec4 c=mix(colorA,colorB,worldT);
    // White ink: structure comes from coverage, authored opacity and stroke width.
    outColor=vec4(vec3(1.0),c.a*coverage);
}
```

## 19. 8 · SDF markers

<!-- field-box id="text1_point" index="18" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_point" role="properties"]
{"id" "text1_point" "index" 18 "name" "8 · SDF markers" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "8 · SDF markers" "_tombstone" false "frame" #afield/rect [1800.0 110.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_point_code_.kt" "lines" 4 "characters" 211 "sha256" "c693e017aa8a8eba8680a71ce5c219a4878b8f96ad3593ec1681bb847f99c3bf"} {"name" "vertex" "language" "glsl" "path" "text1_point_vertex_.glsl" "lines" 64 "characters" 2618 "sha256" "bf39f13c5d3472bd8f511bd5a9e76640abfe557ed1f36ceeab22557460b001a1"} {"name" "fragment" "language" "glsl" "path" "text1_point_fragment_.glsl" "lines" 23 "characters" 798 "sha256" "aec1f1d5ad29382bd4bb1822b40de85c196b93c29e09e08b6618b4261b6ddcbe"}]}
```

```kotlin [field-property box="text1_point" name="code" path="text1_point_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(pointModuleFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_point" name="vertex" path="text1_point_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
struct Marker {
    vec4 position; // diagram-local XYZ and radius in metres
    vec4 color; // linear RGBA before score-driven opacity
    vec4 shape; // SDF kind, ring width, rotation and aspect
};
layout(std430,buffer_reference) readonly buffer DiagramMarkers {
    Marker item[]; // CPU-uploaded 48-byte records, shared by both eyes
};
layout(push_constant) uniform DiagramPush {
    mat4 model; // diagram-local to world, shared by both eyes
    DiagramMarkers diagramData; // position/radius, linear RGBA and SDF parameters
    vec4 options; // viewport width/height, minimum stroke/marker radius in pixels
    vec4 clipPlane; // world-space backdrop clipping, zero for the main scene
} pc;
#extension GL_EXT_multiview : require
layout(std140, set=0, binding=7) uniform StereoViews {
    mat4 stereoVpL; // left-eye projection/view
    mat4 stereoVpR; // right-eye projection/view
    vec4 stereoRightL; // left-eye camera right
    vec4 stereoRightR; // right-eye camera right
    vec4 stereoUpL; // left-eye camera up
    vec4 stereoUpR; // right-eye camera up
    vec4 stereoProjectionL; // projection XY and viewport size
    vec4 stereoProjectionR; // projection XY and viewport size
};

mat4 stereoViewProjection() {
    return gl_ViewIndex==0 ? stereoVpL : stereoVpR;
}
vec3 stereoCameraRight() {
    return (gl_ViewIndex==0 ? stereoRightL : stereoRightR).xyz;
}
vec3 stereoCameraUp() {
    return (gl_ViewIndex==0 ? stereoUpL : stereoUpR).xyz;
}
vec4 stereoProjection() {
    return gl_ViewIndex==0 ? stereoProjectionL : stereoProjectionR;
}
layout(location=0) noperspective out vec2 uv;
layout(location=1) flat out vec4 tint;
layout(location=2) flat out vec4 shape;
const vec2 corners[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
layout(location=3) out float backdropDistance;
void main() {
    backdropDistance=0;
    Marker p=pc.diagramData.item[gl_VertexIndex/6];
    mat4 vp=gl_ViewIndex==0?stereoVpL:stereoVpR;
    vec4 projection=gl_ViewIndex==0?stereoProjectionL:stereoProjectionR;
    vec4 clip=vp*pc.model*vec4(p.position.xyz,1);
    if(clip.z<0||clip.w<1e-6) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    float radius=max(p.position.w*length(pc.model[0].xyz)*abs(projection.y)*pc.options.y*.5/clip.w,pc.options.w);
    uv=corners[gl_VertexIndex%6]*(1+2/max(radius,1e-5));
    clip.xy+=uv*radius*vec2(2,-2)/pc.options.xy*clip.w;
    gl_Position=clip;
    backdropDistance=dot(pc.clipPlane,inverse(vp)*clip);
    tint=p.color;
    shape=p.shape;
}
```

```glsl [field-property box="text1_point" name="fragment" path="text1_point_fragment_.glsl"]
#version 450
layout(location=0) noperspective in vec2 uv;
layout(location=1) flat in vec4 tint;
layout(location=2) flat in vec4 shape;
layout(location=0) out vec4 outColor;
layout(location=3) in float backdropDistance;
void main() {
    if(backdropDistance<0)discard;
    float c=cos(shape.z),s=sin(shape.z);
    vec2 p=mat2(c,-s,s,c)*uv;
    p.x/=max(shape.w,.1);
    float d=length(p)-1;
    int form=int(shape.x+.5);
    if(form==1)d=abs(length(p)-(1-shape.y*.5))-shape.y*.5;
    if(form==2)d=(abs(p.x)+abs(p.y)-1)*.70710678;
    if(form==3) {
        float a=atan(p.y,p.x);
        d=length(p)-(.7+.3*cos(2*a));
    }
    float coverage=clamp(.5-d/max(fwidth(d),1e-5),0,1);
    // White ink; retain the marker's opacity and antialiased boundary.
    outColor=vec4(vec3(1.0),tint.a*coverage);
}
```

## 20. 9 · Slug words

<!-- field-box id="text1_text" index="19" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_text" role="properties"]
{"id" "text1_text" "index" 19 "name" "9 · Slug words" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "9 · Slug words" "_tombstone" false "frame" #afield/rect [1800.0 220.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_text_code_.kt" "lines" 4 "characters" 210 "sha256" "0189d94da9a729d4c5ac99475520342c87bbbc506408196bf09f38ed28d42fe9"} {"name" "vertex" "language" "glsl" "path" "text1_text_vertex_.glsl" "lines" 82 "characters" 3577 "sha256" "37c0f8f64c82736754c0ba76cbdb3bd07e9926743677f8c98d46289e1b2ceb97"} {"name" "fragment" "language" "glsl" "path" "text1_text_fragment_.glsl" "lines" 190 "characters" 7559 "sha256" "009c3b3978744a15fa63b7b1b2fb282f20dbda6d7f74e823ad19fa1f2d2daa7a"}]}
```

```kotlin [field-property box="text1_text" name="code" path="text1_text_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(textModuleFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_text" name="vertex" path="text1_text_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
#extension GL_EXT_multiview : require
layout(std140, set=0, binding=7) uniform StereoViews {
    mat4 stereoVpL; // left-eye projection/view
    mat4 stereoVpR; // right-eye projection/view
    vec4 stereoRightL; // left-eye camera right
    vec4 stereoRightR; // right-eye camera right
    vec4 stereoUpL; // left-eye camera up
    vec4 stereoUpR; // right-eye camera up
    vec4 stereoProjectionL; // projection XY and viewport size
    vec4 stereoProjectionR; // projection XY and viewport size
};

mat4 stereoViewProjection() {
    return gl_ViewIndex==0 ? stereoVpL : stereoVpR;
}
vec3 stereoCameraRight() {
    return (gl_ViewIndex==0 ? stereoRightL : stereoRightR).xyz;
}
vec3 stereoCameraUp() {
    return (gl_ViewIndex==0 ? stereoUpL : stereoUpR).xyz;
}
vec4 stereoProjection() {
    return gl_ViewIndex==0 ? stereoProjectionL : stereoProjectionR;
}
// Adapted Slug core: Copyright (c) 2026 AlphaPixel LLC. See LICENSE-slughorn.
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer SlugData {
    uint words[]; // immutable Slug header, curve and band atlas in 32-bit words
};
layout(push_constant) uniform SlugPC {
    mat4 model; // local glyph plane to world; eye VP is applied in this vertex shader
    SlugData slugData; // immutable word-outline atlas
    vec2 viewport; // dimensions of this eye, for perspective-dependent quad dilation
    vec4 tint; // linear RGBA, with score-driven word opacity
    vec4 clipPlane; // world-space backdrop clipping, zero for the main scene
} pc;
vec4 data4(uint at) {
    return uintBitsToFloat(uvec4(pc.slugData.words[at], pc.slugData.words[at+1],
    pc.slugData.words[at+2], pc.slugData.words[at+3]));
}
layout(location=0) out vec2 emCoord;
const vec2 corners[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
layout(location=1) out float backdropDistance;
void main() {
    backdropDistance=0;
    vec4 bounds = data4(8u);
    // A closed SlugBuffer's empty atlas: nothing to cover, so put every corner outside the clip volume.
    if (!(bounds.z > bounds.x && bounds.w > bounds.y)) {
        emCoord = vec2(0.0);
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
        return;
    }
    // Entire words crossing the eye/near plane are hidden rather than generating unstable quads.
    for (int i=0;i<4;i++) {
        vec2 p=mix(bounds.xy,bounds.zw,vec2(i&1,(i>>1)&1));
        vec4 checkClip=(gl_ViewIndex==0?stereoVpL:stereoVpR)*pc.model*vec4(p,0,1);
        if(checkClip.w<=1e-5||checkClip.z<=0) {
            gl_Position=vec4(0,0,2,1);
            emCoord=vec2(0);
            return;
        }
    }
    vec2 corner = corners[gl_VertexIndex % 6];
    vec2 q = mix(bounds.xy, bounds.zw, corner);
    vec2 normal = 2.0 * corner - 1.0;
    mat4 matrix = (gl_ViewIndex == 0 ? stereoVpL : stereoVpR) * pc.model;
    vec4 position = matrix * vec4(q, 0.0, 1.0);
    // Lengyel's perspective-dependent dilation, expressed in homogeneous vectors.
    // normal is the corner bisector scaled so both incident sides expand equally.
    vec4 direction = matrix * vec4(normalize(normal), 0.0, 0.0);
    float s = position.w;
    float t = direction.w;
    vec2 uv = pc.viewport * (s*direction.xy - t*position.xy);
    float denominator = length(uv) - s*t;
    float distance = denominator > 1e-12 ? s*s / denominator : 0.0;
    q += distance * normal;
    emCoord = q;
    gl_Position = matrix * vec4(q, 0.0, 1.0);
    backdropDistance=dot(pc.clipPlane,pc.model*vec4(q,0,1));
}
```

```glsl [field-property box="text1_text" name="fragment" path="text1_text_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
// Adapted Slug core: Copyright (c) 2026 AlphaPixel LLC. See LICENSE-slughorn.
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer SlugData {
    uint words[]; // immutable Slug header, curve and band atlas in 32-bit words
};
layout(push_constant) uniform SlugPC {
    mat4 model; // word plane to world (the vertex stage applies the current eye VP)
    SlugData slugData; // immutable word-outline atlas
    vec2 viewport; // dimensions of this eye
    vec4 tint; // linear RGBA and score-driven opacity
    vec4 clipPlane; // world-space backdrop plane, shared with the vertex stage
} pc;
vec4 data4(uint at) {
    return uintBitsToFloat(uvec4(pc.slugData.words[at], pc.slugData.words[at+1],
    pc.slugData.words[at+2], pc.slugData.words[at+3]));
}
layout(location=0) in vec2 emCoord;
layout(location=0) out vec4 fragColor;
#define TEX_WIDTH 9
#define SLUG_INDIRECTION_SIZE 32
uvec4 bandFetch(ivec2 xy) {
    uint i = pc.slugData.words[7] + uint(xy.y * 512 + xy.x);
    uint pair = pc.slugData.words[i];
    return uvec4(pair & 65535u, pair >> 16u, 0u, 0u);
}
vec4 curveFetch(ivec2 xy) {
    uint i = pc.slugData.words[6] + 4u * uint(xy.y * 512 + xy.x);
    return data4(i);
}
uint slug_CalcRootCode(float y1, float y2, float y3) {
    uint i1 = floatBitsToUint(y1) >> 31u;
    uint i2 = floatBitsToUint(y2) >> 30u;
    uint i3 = floatBitsToUint(y3) >> 29u;

    uint shift = (i2 & 2u) | (i1 & ~2u);
    shift = (i3 & 4u) | (shift & ~4u);

    return ((0x2E74u >> shift) & 0x0101u);
}

vec2 slug_SolveHorizPoly(vec4 p12, vec2 p3) {
    vec2 a = p12.xy - p12.zw * 2.0 + p3;
    vec2 b = p12.xy - p12.zw;
    float ra = 1.0 / a.y;
    float rb = 0.5 / b.y;
    float d = sqrt(max(b.y * b.y - a.y * p12.y, 0.0));
    float t1 = (b.y - d) * ra;
    float t2 = (b.y + d) * ra;
    if(abs(a.y) < 1.0 / 65536.0) {
        t1 = p12.y * rb;
        t2 = t1;
    }
    return vec2(
    (a.x * t1 - b.x * 2.0) * t1 + p12.x,
    (a.x * t2 - b.x * 2.0) * t2 + p12.x
    );
}

vec2 slug_SolveVertPoly(vec4 p12, vec2 p3) {
    vec2 a = p12.xy - p12.zw * 2.0 + p3;
    vec2 b = p12.xy - p12.zw;
    float ra = 1.0 / a.x;
    float rb = 0.5 / b.x;
    float d = sqrt(max(b.x * b.x - a.x * p12.x, 0.0));
    float t1 = (b.x - d) * ra;
    float t2 = (b.x + d) * ra;
    if(abs(a.x) < 1.0 / 65536.0) {
        t1 = p12.x * rb;
        t2 = t1;
    }
    return vec2(
    (a.y * t1 - b.y * 2.0) * t1 + p12.y,
    (a.y * t2 - b.y * 2.0) * t2 + p12.y
    );
}

ivec2 slug_CalcBandLoc(ivec2 glyphLoc, uint offset) {
    ivec2 bandLoc = ivec2(glyphLoc.x + int(offset), glyphLoc.y);
    bandLoc.y += bandLoc.x >> TEX_WIDTH;
    bandLoc.x &= (1 << TEX_WIDTH) - 1;
    return bandLoc;
}

// Location of a curve's later texel, wrapping to the next row of the 512-wide data layout the same
// way slug_CalcBandLoc() does, since a curve's texels may straddle a row boundary.
ivec2 slug_CalcCurveLoc(ivec2 curveLoc, int offset) {
    ivec2 loc = ivec2(curveLoc.x + offset, curveLoc.y);
    loc.y += loc.x >> TEX_WIDTH;
    loc.x &= (1 << TEX_WIDTH) - 1;
    return loc;
}

float slug_CalcCoverage(float xcov, float ycov, float xwgt, float ywgt) {
    float coverage = max(
    abs(xcov * xwgt + ycov * ywgt) / max(xwgt + ywgt, 1.0 / 65536.0),
    min(abs(xcov), abs(ycov))
    );
    return clamp(coverage, 0.0, 1.0);
}

float slug_Render(vec2 renderCoord, vec4 bandTransform, ivec2 glyphLoc, ivec2 bandMax) {
    vec2 emsPerPixel = fwidth(renderCoord);
    vec2 pixelsPerEm = 1.0 / emsPerPixel;

    // O(1) band index via indirection tables (2 fetches per axis).
    int qY = clamp(int(renderCoord.y * bandTransform.y + bandTransform.w), 0, SLUG_INDIRECTION_SIZE - 1);
    int qX = clamp(int(renderCoord.x * bandTransform.x + bandTransform.z), 0, SLUG_INDIRECTION_SIZE - 1);
    int bandY = int(bandFetch(ivec2(glyphLoc.x + qY, glyphLoc.y)).r);
    int bandX = int(bandFetch(ivec2(glyphLoc.x + SLUG_INDIRECTION_SIZE + qX, glyphLoc.y)).r);

    // Horizontal bands -- headers at glyphLoc + 2*IS + bandY
    float xcov = 0.0, xwgt = 0.0;
    uvec2 hbandData = bandFetch(ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandY, glyphLoc.y)).xy;

    for(int ci = 0; ci < int(hbandData.x); ci++) {
        // Compute each curve index's location separately, so a band's curve list may span
        // more than one row of the data layout.
        ivec2 hbandLoc = slug_CalcBandLoc(glyphLoc, hbandData.y + uint(ci));
        ivec2 curveLoc = ivec2(bandFetch(hbandLoc).xy);
        vec4 p12 = curveFetch(curveLoc) - vec4(renderCoord, renderCoord);

        // A curve's third control point is the next texel. Connected curves share endpoint
        // texels, so that texel can sit at the start of the next row.
        vec2 p3 = curveFetch(slug_CalcCurveLoc(curveLoc, 1)).xy - renderCoord;

        if(max(max(p12.x, p12.z), p3.x) * pixelsPerEm.x < -0.5) break;

        uint code = slug_CalcRootCode(p12.y, p12.w, p3.y);
        if(code != 0u) {
            vec2 r = slug_SolveHorizPoly(p12, p3) * pixelsPerEm.x;
            if((code & 1u) != 0u) {
                xcov += clamp(r.x + 0.5, 0.0, 1.0);
                xwgt = max(xwgt, clamp(1.0 - abs(r.x) * 2.0, 0.0, 1.0));
            }
            if(code > 1u) {
                xcov -= clamp(r.y + 0.5, 0.0, 1.0);
                xwgt = max(xwgt, clamp(1.0 - abs(r.y) * 2.0, 0.0, 1.0));
            }
        }
    }

    // Vertical bands -- headers at glyphLoc + 2*IS + numHBands + bandX
    float ycov = 0.0, ywgt = 0.0;
    uvec2 vbandData = bandFetch(ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandMax.y + 1 + bandX, glyphLoc.y)).xy;

    for(int ci = 0; ci < int(vbandData.x); ci++) {
        // See the matching comment in the horizontal-band loop above.
        ivec2 vbandLoc = slug_CalcBandLoc(glyphLoc, vbandData.y + uint(ci));
        ivec2 curveLoc = ivec2(bandFetch(vbandLoc).xy);
        vec4 p12 = curveFetch(curveLoc) - vec4(renderCoord, renderCoord);

        // See the matching comment in the horizontal-band loop above.
        vec2 p3 = curveFetch(slug_CalcCurveLoc(curveLoc, 1)).xy - renderCoord;

        if(max(max(p12.y, p12.w), p3.y) * pixelsPerEm.y < -0.5) break;

        uint code = slug_CalcRootCode(p12.x, p12.z, p3.x);
        if(code != 0u) {
            vec2 r = slug_SolveVertPoly(p12, p3) * pixelsPerEm.y;
            if((code & 1u) != 0u) {
                ycov -= clamp(r.x + 0.5, 0.0, 1.0);
                ywgt = max(ywgt, clamp(1.0 - abs(r.x) * 2.0, 0.0, 1.0));
            }
            if(code > 1u) {
                ycov += clamp(r.y + 0.5, 0.0, 1.0);
                ywgt = max(ywgt, clamp(1.0 - abs(r.y) * 2.0, 0.0, 1.0));
            }
        }
    }

    return slug_CalcCoverage(xcov, ycov, xwgt, ywgt);
}

// The fill colour. Replace it with any procedural fill; coverage is computed independently of it.
vec3 shade(vec2 q) {
    // White ink; glyph coverage and score-driven opacity remain independent.
    return vec3(1.0);
}
layout(location=1) in float backdropDistance;
void main() {
    if(backdropDistance<0)discard;
    ivec2 loc = ivec2(pc.slugData.words[16], pc.slugData.words[17]);
    ivec2 bandMax = ivec2(pc.slugData.words[18], pc.slugData.words[19]);
    float coverage = slug_Render(emCoord, data4(12u), loc, bandMax);
    if (coverage <= 0.0) discard;
    // Straight alpha; use a source-alpha blending pipeline. Convert explicitly for
    // premultiplied-alpha targets. Neither atlas data nor coverage are sRGB colors.
    fragColor = vec4(shade(emCoord), coverage * pc.tint.a);
}
```

## 21. 10 · Medium blur / horizontal

<!-- field-box id="text1_blur" index="20" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_blur" role="properties"]
{"id" "text1_blur" "index" 20 "name" "10 · Medium blur / horizontal" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "10 · Medium blur / horizontal" "_tombstone" false "frame" #afield/rect [1800.0 330.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_blur_code_.kt" "lines" 4 "characters" 216 "sha256" "9f3b7e99d9b6993cdf3ced60ac66cc8d2651e56f8a9a79724a6a52b960a3e2cc"} {"name" "vertex" "language" "glsl" "path" "text1_blur_vertex_.glsl" "lines" 10 "characters" 310 "sha256" "bfeef78cb283bfcb51d42e49e10e99971c2d418460670cf8a23279f7fd45a6cf"} {"name" "fragment" "language" "glsl" "path" "text1_blur_fragment_.glsl" "lines" 20 "characters" 861 "sha256" "4631f2b27487bb0cc7e210c7578866150db400df9abd6bd045ba7c65518aba71"}]}
```

```kotlin [field-property box="text1_blur" name="code" path="text1_blur_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(mediumHorizontalFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_blur" name="vertex" path="text1_blur_vertex_.glsl"]
#version 450
#extension GL_EXT_multiview : require
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1.0 - p.y);
    eye = gl_ViewIndex;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="text1_blur" name="fragment" path="text1_blur_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 uv;
layout(location = 1) flat in int eye;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2DArray source;
layout(push_constant) uniform BlurPush {
    vec4 tapStep; // XY step in output pixels, kernel radius and gain
} pc;

// Nine-tap Gaussian approximation, using bilinear pairing for five texture fetches.
// The output texel size makes H/V isotropic even while the first pass downsamples.
vec4 sampleOffset(float offset) {
    return texture(source, vec3(uv + pc.tapStep.xy * pc.tapStep.z * offset, float(eye)));
}
void main() {
    vec4 c = sampleOffset(0.0) * 0.2270270270;
    c += (sampleOffset(1.3846153846) + sampleOffset(-1.3846153846)) * 0.3162162162;
    c += (sampleOffset(3.2307692308) + sampleOffset(-3.2307692308)) * 0.0702702703;
    outColor = c * pc.tapStep.w;
}
```

## 22. 15 · Medium blur / vertical

<!-- field-box id="text1_blur_medium_v" index="21" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_blur_medium_v" role="properties"]
{"id" "text1_blur_medium_v" "index" 21 "name" "15 · Medium blur / vertical" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "15 · Medium blur / vertical" "_tombstone" false "frame" #afield/rect [1800.0 440.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_blur_medium_v_code_.kt" "lines" 4 "characters" 214 "sha256" "42613517a509edbfcdb2150d299898030f087353de41327c6ecc1353f130e9ca"} {"name" "vertex" "language" "glsl" "path" "text1_blur_medium_v_vertex_.glsl" "lines" 10 "characters" 310 "sha256" "bfeef78cb283bfcb51d42e49e10e99971c2d418460670cf8a23279f7fd45a6cf"} {"name" "fragment" "language" "glsl" "path" "text1_blur_medium_v_fragment_.glsl" "lines" 20 "characters" 861 "sha256" "4631f2b27487bb0cc7e210c7578866150db400df9abd6bd045ba7c65518aba71"}]}
```

```kotlin [field-property box="text1_blur_medium_v" name="code" path="text1_blur_medium_v_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(mediumVerticalFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_blur_medium_v" name="vertex" path="text1_blur_medium_v_vertex_.glsl"]
#version 450
#extension GL_EXT_multiview : require
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1.0 - p.y);
    eye = gl_ViewIndex;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="text1_blur_medium_v" name="fragment" path="text1_blur_medium_v_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 uv;
layout(location = 1) flat in int eye;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2DArray source;
layout(push_constant) uniform BlurPush {
    vec4 tapStep; // XY step in output pixels, kernel radius and gain
} pc;

// Nine-tap Gaussian approximation, using bilinear pairing for five texture fetches.
// The output texel size makes H/V isotropic even while the first pass downsamples.
vec4 sampleOffset(float offset) {
    return texture(source, vec3(uv + pc.tapStep.xy * pc.tapStep.z * offset, float(eye)));
}
void main() {
    vec4 c = sampleOffset(0.0) * 0.2270270270;
    c += (sampleOffset(1.3846153846) + sampleOffset(-1.3846153846)) * 0.3162162162;
    c += (sampleOffset(3.2307692308) + sampleOffset(-3.2307692308)) * 0.0702702703;
    outColor = c * pc.tapStep.w;
}
```

## 23. 17 · Broad blur / vertical

<!-- field-box id="text1_blur_broad_v" index="22" -->

### Narrative

From `code`

Drawing supplies the target and textures; this box owns its shader and Alt+R.

```edn [field-box id="text1_blur_broad_v" role="properties"]
{"id" "text1_blur_broad_v" "index" 22 "name" "17 · Broad blur / vertical" "parents" ["root" "text1_context"] "children" ["text1_drawing"] "properties" {"children" #{#afield/box "text1_drawing"} "parents" #{#afield/box "root" #afield/box "text1_context"} "name" "17 · Broad blur / vertical" "_tombstone" false "frame" #afield/rect [1800.0 660.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_blur_broad_v_code_.kt" "lines" 4 "characters" 213 "sha256" "6b30c78dc48684b72bc9906a1e6e8e61a8363459b80d5d2a80078633aac2b5ad"} {"name" "vertex" "language" "glsl" "path" "text1_blur_broad_v_vertex_.glsl" "lines" 10 "characters" 310 "sha256" "bfeef78cb283bfcb51d42e49e10e99971c2d418460670cf8a23279f7fd45a6cf"} {"name" "fragment" "language" "glsl" "path" "text1_blur_broad_v_fragment_.glsl" "lines" 20 "characters" 861 "sha256" "4631f2b27487bb0cc7e210c7578866150db400df9abd6bd045ba7c65518aba71"}]}
```

```kotlin [field-property box="text1_blur_broad_v" name="code" path="text1_blur_broad_v_code_.kt"]
// Drawing supplies the target and textures; this box owns its shader and Alt+R.
prop<afield.graphics.scene.reading.DiagramModuleFactory>(broadVerticalFactory) = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="text1_blur_broad_v" name="vertex" path="text1_blur_broad_v_vertex_.glsl"]
#version 450
#extension GL_EXT_multiview : require
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1.0 - p.y);
    eye = gl_ViewIndex;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
```

```glsl [field-property box="text1_blur_broad_v" name="fragment" path="text1_blur_broad_v_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 uv;
layout(location = 1) flat in int eye;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2DArray source;
layout(push_constant) uniform BlurPush {
    vec4 tapStep; // XY step in output pixels, kernel radius and gain
} pc;

// Nine-tap Gaussian approximation, using bilinear pairing for five texture fetches.
// The output texel size makes H/V isotropic even while the first pass downsamples.
vec4 sampleOffset(float offset) {
    return texture(source, vec3(uv + pc.tapStep.xy * pc.tapStep.z * offset, float(eye)));
}
void main() {
    vec4 c = sampleOffset(0.0) * 0.2270270270;
    c += (sampleOffset(1.3846153846) + sampleOffset(-1.3846153846)) * 0.3162162162;
    c += (sampleOffset(3.2307692308) + sampleOffset(-3.2307692308)) * 0.0702702703;
    outColor = c * pc.tapStep.w;
}
```

## 24. 12 · Drawing / score playback

<!-- field-box id="text1_drawing" index="23" -->

```edn [field-box id="text1_drawing" role="properties"]
{"id" "text1_drawing" "index" 23 "name" "12 · Drawing / score playback" "parents" ["root" "text1_poem" "text1_score" "text1_stroke" "text1_point" "text1_text" "text1_blur" "text1_blur_medium_v" "text1_blur_broad_h" "text1_blur_broad_v" "text1_composite"] "children" ["text1_body" "text1_navigation"] "properties" {"children" #{#afield/box "text1_body" #afield/box "text1_navigation"} "parents" #{#afield/box "root" #afield/box "text1_poem" #afield/box "text1_score" #afield/box "text1_stroke" #afield/box "text1_point" #afield/box "text1_text" #afield/box "text1_blur" #afield/box "text1_blur_medium_v" #afield/box "text1_blur_broad_h" #afield/box "text1_blur_broad_v" #afield/box "text1_composite"} "name" "12 · Drawing / score playback" "_tombstone" false "frame" #afield/rect [2350.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_drawing_code_.kt" "lines" 89 "characters" 4300 "sha256" "a03550507a9c90cc2edfe9a6c95c4eb0e211aa994d61cdb57d549931a9a94b1e"}]}
```

```kotlin [field-property box="text1_drawing" name="code" path="text1_drawing_code_.kt"]
import afield.graphics.scene.reading.*

// Staged cache quality. Apply changes at an acquired frame, without replacing the renderer or XR session.
rootProp<Int>(textCacheWidth, 2048)
rootProp<Int>(textCacheHeight, 1024)
rootProp<Int>(textCacheMinimum, 512)
rootProp<Int>(textCacheHalving, 2)
rootProp<Int>(textCacheTaps, 513)
rootProp<Float>(textCacheCutoff, 4f)
fun pendingTextCacheQuality() = ReadingTextBlurConfig(
    width = root.textCacheWidth,
    height = root.textCacheHeight,
    minimumSize = root.textCacheMinimum,
    levelsPerHalving = root.textCacheHalving,
    maximumRadius = (root.textCacheTaps - 1) / 2,
    sigmaCutoff = root.textCacheCutoff,
)

// Each shader box owns its moduleHere pipeline and its standard Alt+R action.
// The renderer supplies pass-specific targets/resources without copying shader properties.
val passes = mapOf(
    "reading-strokes" to strokeModuleFactory,
    "reading-markers" to pointModuleFactory,
    "reading-slug" to textModuleFactory,
    "reading-blur-1" to mediumHorizontalFactory,
    "reading-blur-2" to mediumVerticalFactory,
    "reading-blur-3" to broadHorizontalFactory,
    "reading-blur-4" to broadVerticalFactory,
    "reading-composite" to compositeModuleFactory,
)

prop<StereoDiagramRenderer>(drawing) =
    StereoDiagramRenderer(renderer, stereo, poem, textBlurConfig = pendingTextCacheQuality(), moduleFactories = passes)
val ownedDrawing = drawing

rootProp<Boolean>(showSkeleton, false)
rootProp<Boolean>(showPhysicsNodes, true)
rootProp<Boolean>(showBodyForces, true)

// The uniform has its own document-wide floor; panel-local black levels remain available in the DSL.
rootProp<Float>(frostBlackLevel, .012f)
rootProp<Float>(frostBlurGamma, 1f)
val frostScenePreparation = drawing.compositor.beforeScene
val frostFloorValue: () -> Float = { root.frostBlackLevel }
drawing.compositor.beforeScene =
    afield.graphics.vulkan.Target { command, index ->
        drawing.frost.blurGamma = root.frostBlurGamma
        frostScenePreparation?.render(command, index)
        drawing.compositor.sharp.modules
            .filterKeys { it.startsWith("reading-frost-") }
            .values
            .forEach { pass ->
                pass.current.liveUniformStructures.getValue("FrostTone").bind("globalBlackLevel", frostFloorValue)
            }
    }

// These controls shape already-filtered text coverage; neither changes its cache nor its timing.
rootProp<Float>(textBlurGain, 1f)
rootProp<Float>(textBlurContrast, 1f)
val textBlurToneValue: () -> Vector4f = { vec(root.textBlurGain, root.textBlurContrast, 0f, 0f) }
val prepareTextAndFrost = drawing.compositor.beforeScene
drawing.compositor.beforeScene =
    afield.graphics.vulkan.Target { command, index ->
        drawing.compositor.sharp.modules
            .filterKeys { it.startsWith("reading-local-blur-") }
            .values
            .forEach { pass ->
                pass.current.liveUniformStructures.getValue("TextBlurTone").bind("appearance", textBlurToneValue)
            }
        prepareTextAndFrost?.render(command, index)
    }

// Optional perceptual dither for downstream display quantization; zero keeps it disabled.

var lastStats = 0L
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    val now = System.nanoTime()
    if (now - lastStats > 1_000_000_000L) {
        lastStats = now
        display(
            "frame",
            "<div style='padding:12px;width:440px'>${if (drawing.textBlurNeedsRebuild) "Blur capacity changed: rebuild Drawing. " else ""}Frost: ${drawing.frost.activeSurfaces} active / ${drawing.frost.allocatedSurfaces} cached · ${drawing.frost.allocatedBytes / (1024 * 1024)} MiB · ${drawing.frost.omittedSurfaces} over budget. Text caches: ${drawing.textBlurConfig.width}×${drawing.textBlurConfig.height}, minimum ${drawing.textBlurConfig.minimumSize}px, max ${2*drawing.textBlurConfig.maximumRadius+1} taps, ${drawing.textBlurCacheBytes/(1024*1024)} MiB. Frame ${drawing.frameCount} · ${poem.labels.size} text runs · ${poem.geometry.segments.size} stroke segments · ${poem.geometry.points.size} markers · ${poem.current.physicsSteps} fixed physics steps. One centreline upload and one text-pose update serve both eyes.</div>",
        )
    }
    frame()
}
    .end { ownedDrawing.close() }
```

## 25. 21 · Body interaction / diagnostics

<!-- field-box id="text1_body" index="24" -->

```edn [field-box id="text1_body" role="properties"]
{"id" "text1_body" "index" 24 "name" "21 · Body interaction / diagnostics" "parents" ["root" "text1_drawing"] "children" ["text1_global_keys"] "properties" {"children" #{#afield/box "text1_global_keys"} "parents" #{#afield/box "root" #afield/box "text1_drawing"} "name" "21 · Body interaction / diagnostics" "_tombstone" false "frame" #afield/rect [2350.0 220.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_body_code_.kt" "lines" 229 "characters" 10555 "sha256" "01dc814811b017552a524ce2eb7a8dd222835363befb51afe8d28fcb7a680e23"} {"name" "vertex" "language" "glsl" "path" "text1_body_vertex_.glsl" "lines" 52 "characters" 1925 "sha256" "da4fb84e08efc0ffbe43960894b7c2e2abb080cabd49536892f2a049e116f035"} {"name" "fragment" "language" "glsl" "path" "text1_body_fragment_.glsl" "lines" 13 "characters" 504 "sha256" "7eb68433c9497fd4c424e92c222ca427c8ee61e30584bcecaf428f3afa664639"}]}
```

```kotlin [field-property box="text1_body" name="code" path="text1_body_code_.kt"]
import afield.graphics.scene.body.*
import afield.graphics.vulkan.Target
import kotlin.math.*

// These are persistent, inherited Field properties. Re-running this box keeps the controls' values.
prop<Boolean>(readingBodyEnabled, true)
prop<Boolean>(readingBodyHands, true)
prop<Boolean>(readingBodyHead, true)
prop<Boolean>(readingBodyWaft, true)
prop<Float>(readingBodyWaftStrength, 4f) // outward acceleration at the capsule surface, m/s²
prop<Float>(readingBodyPull, 0f) // gathering gain in the optional classic standoff mode
prop<Float>(readingBodyPush, 14f) // additional acceleration per metre of penetration
prop<Float>(readingBodyReach, .4f) // outside influence band, metres
prop<Float>(readingBodyInsideReach, 0f) // classic mode only; zero covers the whole interior
prop<Float>(readingBodyGap, .018f) // extra clearance beyond each capsule radius, metres
prop<Float>(readingBodyFalloff, 1f)
prop<Float>(readingBodyMaximum, 4.5f) // acceleration cap, m/s²; zero disables the cap
prop<Float>(readingBodyRadiusScale, 1f)
rootProp<Boolean>(showSkeleton, false)
rootProp<Boolean>(showPhysicsNodes, true)
rootProp<Boolean>(showBodyForces, true)
prop<Map<String, Float>>(bodyInteractionStats) = emptyMap()

val readingBodyDrawing = drawing
val readingBodyExamples = poem.examples.toList()
val previousReadingUpdate = drawing.beforeUpdate
val readingBodyRadii = mutableMapOf<String, Float>() // optional bone name -> radius in world metres
var readingBodySnapshot = BodySegments.from(null)
val readingBodyInverse = Matrix4f()
var readingBodyModelFrame = -1L
val readingBodyWorld = Vector3f()
val nodeCapacity = readingBodyExamples.maxOf { it.player.state.size }
val contactNodes = Array(nodeCapacity) { Vector3f() } // exact world point tested by the solver
val contactBones = Array(nodeCapacity) { Vector3f() } // closest point on the selected bone's centreline
val contactForces = FloatArray(nodeCapacity) // actual applied world acceleration magnitude
val contactFrames = LongArray(nodeCapacity) { -100L }
var contactPlayer = poem.current

// Input: signed capsule-surface distance and outward normal, in world metres.
// Output: world acceleration. Wafting never attracts: its smooth outside band fades to zero.
fun readingBodyForce(hit: BodySegmentHit): Vector3f {
    if (!readingBodyWaft)
        return hit.force(
            readingBodyPull,
            readingBodyPush,
            readingBodyReach,
            readingBodyFalloff,
            readingBodyMaximum,
            readingBodyInsideReach,
        )
    val outside = max(hit.distance, 0f)
    val t =
        if (outside == 0f) 1f else if (readingBodyReach > 0f) (1f - outside / readingBodyReach).coerceIn(0f, 1f) else 0f
    val smooth = t * t * (3f - 2f * t)
    val envelope = if (smooth == 0f) 0f else smooth.pow(readingBodyFalloff)
    var strength = readingBodyWaftStrength * envelope + readingBodyPush * max(-hit.distance, 0f)
    if (readingBodyMaximum > 0f) strength = min(strength, readingBodyMaximum)
    return Vector3f(hit.normal).mul(strength)
}

// Also available to small CPU probes without substituting a different force implementation.
prop<(Float) -> Float>(bodyForceAtDistance) = { distance ->
    readingBodyForce(BodySegmentHit(0, Vector3f(), Vector3f(1f, 0f, 0f), distance)).x
}

// These records come from the REAL force callback, rather than a second diagnostic distance test.
val readingBodyAcceleration: (Int, Vector3f, Vector3f) -> Unit = { index, local, output ->
    contactForces[index] = 0f
    contactFrames[index] = renderer.frameCounter
    if (readingBodyEnabled) {
        if (readingBodyModelFrame != renderer.frameCounter) {
            readingBodyInverse.set(drawing.model).invert()
            readingBodyModelFrame = renderer.frameCounter
        }
        drawing.model.transformPosition(local, readingBodyWorld)
        val hit = readingBodySnapshot.closest(readingBodyWorld, readingBodyGap)
        if (hit != null) {
            output.set(readingBodyForce(hit))
            contactForces[index] = output.length()
            contactNodes[index].set(readingBodyWorld)
            val radius = readingBodySnapshot[hit.segment]!!.radius + readingBodyGap
            contactBones[index].set(hit.point).fma(-max(radius, 0f), hit.normal)
            readingBodyInverse.transformDirection(output)
        }
    }
}

// One small debug module for skeleton lines, SIMD points and active force connections.
// Expansion uses the current stereo eyes; green stays zero so the debug ink remains sharp.
val readingBodyTarget = drawing.compositor.sharp
val readingBodyModule = moduleHere {
    stereo.configure(this)
    +readingBodyTarget
    depthTest = false
    depthWrite = false
    blend = true
    srcColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA
    dstColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    srcAlpha = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    dstAlpha = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
}
val readingBodyMarks = List(BodySegments.CAPACITY + nodeCapacity * 2) {
    arrayOf(Vector4f(), Vector4f(), Vector4f(1f, 0f, 1f, 0f))
}
for ((id, mark) in readingBodyMarks.withIndex()) {
    val geometry = Mesh(renderer, readingBodyModule)
    geometry.resize(6, 0)
    geometry.push.bind("a") { mark[0] } // world start/centre, radius or half-width in metres
    geometry.push.bind("b") { mark[1] } // world end, 1 = circular marker / 0 = line
    geometry.push.bind("tint") { mark[2] } // red/blue ink; alpha controls diagnostic opacity
    readingBodyModule += "body diagnostic $id" to geometry
}
readingBodyTarget += "tracked-body" to readingBodyModule

val readingBodyUpdate: () -> Unit = {
    previousReadingUpdate()
    readingBodyModelFrame = -1L
    if (contactPlayer !== poem.current) {
        contactPlayer = poem.current
        contactForces.fill(0f)
        contactFrames.fill(-100L)
    }
    readingBodySnapshot =
        if (readingBodyEnabled || root.showSkeleton)
            renderer.openXRHmdTarget?.bodySegments(readingBodyHands, readingBodyHead) { name, radius ->
                (readingBodyRadii[name] ?: radius) * readingBodyRadiusScale
            } ?: BodySegments.from(null)
        else BodySegments.from(null)
    for (example in readingBodyExamples) {
        if (readingBodyEnabled && readingBodySnapshot.active.isNotEmpty())
            example.player.externalAccelerations["tracked-body"] = readingBodyAcceleration
        else example.player.externalAccelerations.remove("tracked-body", readingBodyAcceleration)
    }
    readingBodyModule.current.enabled = root.showSkeleton || root.showPhysicsNodes || root.showBodyForces
}
drawing.beforeUpdate = readingBodyUpdate

// This runs AFTER the reading renderer has updated model and advanced the fixed-step solver.
// Node dots use current SIMD state; force lines retain the exact point sampled by that step.
readingBodyModule.current.before["body-debug"] = Target { _, _ ->
    readingBodyMarks.forEach { it[2].w = 0f }
    if (root.showSkeleton)
        for (segment in readingBodySnapshot.active) {
            val mark = readingBodyMarks[segment.id]
            mark[0].set(segment.start.x, segment.start.y, segment.start.z, if (segment.id == 8) .015f else .002f)
            mark[1].set(segment.end.x, segment.end.y, segment.end.z, if (segment.id == 8) 1f else 0f)
            mark[2].set(.5f, 0f, .5f, .35f)
        }
    val player = poem.current
    var active = 0
    var maximum = 0f
    for ((index, node) in player.diagram.particles.withIndex()) {
        val pushing =
            readingBodyEnabled &&
                readingBodySnapshot.active.isNotEmpty() &&
                renderer.frameCounter - contactFrames[index] <= 3L &&
                contactForces[index] > .01f
        if (pushing) {
            active++
            maximum = max(maximum, contactForces[index])
        }
        if (root.showPhysicsNodes) {
            val p = drawing.model.transformPosition(
                vec(player.state.x[index], player.state.y[index], player.state.z[index]),
            )
            val mark = readingBodyMarks[BodySegments.CAPACITY + index]
            mark[0].set(p.x, p.y, p.z, if (pushing) .010f else .006f)
            mark[1].w = 1f
            mark[2].set(1f, 0f, 1f, if (node.pinned) .18f else if (pushing) 1f else .55f)
        }
        if (root.showBodyForces && pushing) {
            val mark = readingBodyMarks[BodySegments.CAPACITY + nodeCapacity + index]
            val from = contactBones[index]
            val to = contactNodes[index]
            mark[0].set(from.x, from.y, from.z, .0015f)
            mark[1].set(to.x, to.y, to.z, 0f)
            mark[2].set(1f, 0f, 1f, .25f + .6f * min(contactForces[index] / 4f, 1f))
        }
    }
    bodyInteractionStats = mapOf(
        "nodes" to player.state.size.toFloat(),
        "pushing" to active.toFloat(),
        "maxAcceleration" to maximum,
        "bones" to readingBodySnapshot.active.size.toFloat(),
    )
}

// Apply this preset without moving the scene, resetting particles or changing text orientations.
prop<() -> Unit>(bodyWaftPreset) = {
    readingBodyEnabled = true
    readingBodyWaft = true
    readingBodyWaftStrength = 4f
    readingBodyReach = .4f
    readingBodyMaximum = 4.5f
    poem.current.spring = 3f
    poem.current.dampingRate = 3f
    poem.current.gravity = 0f
    poem.current.wind = 0f
}
prop<() -> Unit>(bodyInteractionDemo) = {
    poem.select(6, restart = true)
    bodyWaftPreset()
    poem.current.playback.seek(6.0)
    poem.current.playback.playing = false
    drawing.recenter()
}

var lastBodyStats = 0L
loop {
    val now = System.nanoTime()
    if (now - lastBodyStats > 1_000_000_000L) {
        lastBodyStats = now
        val stats = bodyInteractionStats
        display(
            "body diagnostics",
            "<div style='padding:12px;width:440px'>${stats["bones"]?.toInt() ?: 0} tracked bones · ${stats["nodes"]?.toInt() ?: 0} SIMD nodes · ${stats["pushing"]?.toInt() ?: 0} pushing. Bright dots and bone-to-node lines mark applied force. Dim dots are pinned. Contact uses these nodes; thread interior nodes and glyph outlines are not tested.</div>",
        )
    }
    frame()
}
    .end {
        if (drawing.beforeUpdate === readingBodyUpdate) drawing.beforeUpdate = previousReadingUpdate
        for (example in readingBodyExamples) example.player.externalAccelerations.remove(
            "tracked-body",
            readingBodyAcceleration,
        )
        readingBodyModule.disable()
        readingBodyTarget.modules.remove("tracked-body", readingBodyModule)
    }
```

```glsl [field-property box="text1_body" name="vertex" path="text1_body_vertex_.glsl"]
#version 460
#extension GL_EXT_multiview : require

// The shared eye buffer is supplied by MultiviewStereoTarget.configure.
layout(std140, set = 0, binding = 7) uniform StereoViews {
    mat4 stereoVpL; // left-eye view/projection
    mat4 stereoVpR; // right-eye view/projection
    vec4 stereoRightL; // left-eye world right
    vec4 stereoRightR; // right-eye world right
    vec4 stereoUpL; // left-eye world up
    vec4 stereoUpR; // right-eye world up
    vec4 stereoProjectionL; // left projection scale and raster size
    vec4 stereoProjectionR; // right projection scale and raster size
};

layout(push_constant) uniform Mark {
    vec4 a; // world start/centre, radius or half-width in metres
    vec4 b; // world end, 1 = circular joint / 0 = segment
    vec4 tint; // linear RGBA; zero alpha hides unavailable observations
} pc;

layout(location = 0) out vec2 uv; // local square coordinates for analytic coverage
layout(location = 1) flat out vec4 ink; // linear RGBA
layout(location = 2) flat out float roundMark; // 1 = circle / 0 = strip

const vec2 corners[6] = vec2[](
vec2(-1,-1), vec2(1,-1), vec2(1,1),
vec2(-1,-1), vec2(1,1), vec2(-1,1)
);

void main() {
    uv = corners[gl_VertexIndex];
    ink = pc.tint;
    roundMark = pc.b.w;
    vec3 right = (gl_ViewIndex == 0 ? stereoRightL : stereoRightR).xyz;
    vec3 up = (gl_ViewIndex == 0 ? stereoUpL : stereoUpR).xyz;
    vec3 p;

    // Expand on the GPU, independently for the current eye.
    if (pc.b.w > 0.5) {
        p = pc.a.xyz + (right * uv.x + up * uv.y) * pc.a.w;
    }
    else {
        vec3 d = pc.b.xyz - pc.a.xyz;
        vec3 side = cross(d, cross(right, up));
        side = dot(side, side) > 1e-12 ? normalize(side) : right;
        p = mix(pc.a.xyz, pc.b.xyz, uv.x * 0.5 + 0.5) + side * uv.y * pc.a.w;
    }
    gl_Position = pc.tint.a > 0.0
    ? (gl_ViewIndex == 0 ? stereoVpL : stereoVpR) * vec4(p, 1)
    : vec4(0, 0, -1, 1);
}
```

```glsl [field-property box="text1_body" name="fragment" path="text1_body_fragment_.glsl"]
#version 460

layout(location = 0) in vec2 uv; // local square coordinates
layout(location = 1) flat in vec4 ink; // linear RGBA
layout(location = 2) flat in float roundMark; // circle or strip coverage
layout(location = 0) out vec4 outColor; // straight alpha for the module's blend mode

void main() {
    float d = roundMark > 0.5 ? length(uv) : abs(uv.y);
    float aa = max(fwidth(d), 1e-5);
    float coverage = 1.0 - smoothstep(1.0 - aa, 1.0, d);
    outColor = vec4(ink.rgb, ink.a * coverage);
}
```

## 26. 20 · Move through the reading space

<!-- field-box id="text1_navigation" index="25" -->

```edn [field-box id="text1_navigation" role="properties"]
{"id" "text1_navigation" "index" 25 "name" "20 · Move through the reading space" "parents" ["root" "text1_drawing"] "children" ["text1_global_keys"] "properties" {"children" #{#afield/box "text1_global_keys"} "parents" #{#afield/box "root" #afield/box "text1_drawing"} "name" "20 · Move through the reading space" "_tombstone" false "frame" #afield/rect [2350.0 110.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_navigation_code_.kt" "lines" 44 "characters" 1784 "sha256" "93214b1d4f3fda5631923b195fdec3a7b85f8a48555d16d7f0417b0297c75d9e"}]}
```

```kotlin [field-property box="text1_navigation" name="code" path="text1_navigation_code_.kt"]
import afield.graphics.scene.reading.readingWalk
import afield.graphics.vulkan.targets.OpenXRHmdTarget.Hand

// Translate the reading world opposite the virtual reader; the room and browser remain tracked.
val navigationDrawing = drawing
val previousNavigationUpdate = navigationDrawing.beforeUpdate
prop<Boolean>(readingWalkEnabled, true)
prop<Float>(readingWalkSpeed, .65f) // metres per second at full deflection
var navigationLastTime = System.nanoTime()
var navigationPlaced = renderer.openXRHmdTarget?.headPose() == true
val navigationUpdate: () -> Unit = {
    previousNavigationUpdate()
    val now = System.nanoTime()
    val dt = ((now - navigationLastTime) / 1e9).coerceIn(0.0, .05).toFloat()
    navigationLastTime = now
    val xr = renderer.openXRHmdTarget
    // A synchronized OpenXR session can precede its first visible, located head pose.
    // Place the poem on that first real pose, rather than around an identity eye matrix.
    if (!navigationPlaced && xr?.headPose() == true) {
        navigationDrawing.recenter()
        navigationPlaced = true
    }
    if (readingWalkEnabled && xr != null) {
        val stick = xr.controllerState(Hand.LEFT)
        if (stick.active) {
            val eye = stereo.eyeViewMatrix(0).invert()
            val movement = readingWalk(
                stick.thumbstickX,
                stick.thumbstickY,
                vec(-eye.m20, -eye.m21, -eye.m22),
                dt.toDouble(),
                readingWalkSpeed,
            )
            navigationDrawing.origin.sub(movement)
        }
    }
}
navigationDrawing.beforeUpdate = navigationUpdate

loop { frame() }
    .end {
        if (navigationDrawing.beforeUpdate === navigationUpdate)
            navigationDrawing.beforeUpdate = previousNavigationUpdate
    }
```

## 27. 23 · Global looks / keyframes

<!-- field-box id="text1_global_keys" index="26" -->

```edn [field-box id="text1_global_keys" role="properties"]
{"id" "text1_global_keys" "index" 26 "name" "23 · Global looks / keyframes" "parents" ["root" "text1_body" "text1_navigation"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "text1_body" #afield/box "text1_navigation"} "name" "23 · Global looks / keyframes" "_tombstone" false "frame" #afield/rect [-650.0 150.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "text1_global_keys_code_.kt" "lines" 804 "characters" 25900 "sha256" "67c81f7df43fe674089e4fb08e55d526c72b31eccf44237091b7529528918615"}]}
```

```kotlin [field-property box="text1_global_keys" name="code" path="text1_global_keys_code_.kt"]
import afield.animation.keyframes.*
import afield.graphics.scene.reading.*
import afield.ui.output.*
import java.nio.file.Files

/*
One document-wide scope. Re-running this box replaces registrations and reads the LIVE models;
restoreValues=false prevents stale slider caches from overwriting the look while building the UI.
Stable target names and native key JSON keep saved looks usable as controls are added.
*/
rootProp<KeyframeScope>(readingKeyframes) = KeyframeScope(schemaVersion = 1)
val keys = root.readingKeyframes
val drawingBox = thisBox.inside.boxes.getValue("text1_drawing")
val bodyBox = thisBox.inside.boxes.getValue("text1_body")
val scoreBox = thisBox.inside.boxes.getValue("text1_score")
val spaceBox = thisBox.inside.boxes.getValue("text1_context")
val navigationBox = thisBox.inside.boxes.getValue("text1_navigation")
fun frames() = poem.examples.flatMap { it.player.diagram.textFrames }
fun grade() = drawing.compositor.settings
fun pendingTextCacheQuality() = ReadingTextBlurConfig(
    root.textCacheWidth,
    root.textCacheHeight,
    root.textCacheMinimum,
    root.textCacheHalving,
    (root.textCacheTaps - 1) / 2,
    root.textCacheCutoff,
)

drawingBox.controlGroup("text blur quality", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.cache-width-pixels",
        "Cache width",
        unit = "px",
        get = { root.textCacheWidth.toDouble() },
        min = 512.0,
        max = 8192.0,
        step = 512.0,
    ) {
        root.textCacheWidth = it.toInt()
    }
    keyframeSlider(
        keys,
        "drawing.cache-height-pixels",
        "Cache height",
        unit = "px",
        get = { root.textCacheHeight.toDouble() },
        min = 256.0,
        max = 8192.0,
        step = 256.0,
    ) {
        root.textCacheHeight = it.toInt()
    }
    keyframeSlider(
        keys,
        "drawing.broad-level-minimum-pixels",
        "Broad level minimum",
        unit = "px",
        get = { root.textCacheMinimum.toDouble() },
        min = 64.0,
        max = 2048.0,
        step = 64.0,
    ) {
        root.textCacheMinimum = it.toInt()
    }
    keyframeSlider(
        keys,
        "drawing.levels-per-size-halving",
        "Levels per size halving",
        get = { root.textCacheHalving.toDouble() },
        min = 1.0,
        max = 8.0,
        step = 1.0,
    ) {
        root.textCacheHalving = it.toInt()
    }
    keyframeSlider(
        keys,
        "drawing.maximum-taps-per-axis",
        "Maximum taps per axis",
        get = { root.textCacheTaps.toDouble() },
        min = 33.0,
        max = 1025.0,
        step = 16.0,
    ) {
        root.textCacheTaps = it.toInt()
    }
    keyframeSlider(
        keys,
        "drawing.gaussian-support-sigma",
        "Gaussian support",
        unit = "σ",
        get = { root.textCacheCutoff.toDouble() },
        min = 2.0,
        max = 6.0,
        step = .25,
    ) {
        root.textCacheCutoff = it.toFloat()
    }
    button(
        "Apply text blur quality",
        hoverText =
            "Rebuild just the cached masks. Pose, physics, score and the headset session continue. Higher resolutions use more VRAM and take longer to prepare.",
    ) { down ->
        if (down) {
            runCatching {
                require(root.textCacheMinimum <= minOf(root.textCacheWidth, root.textCacheHeight)) {
                    "Broad minimum must fit within both base dimensions"
                }
                val quality = pendingTextCacheQuality()
                drawing.requestTextBlurRebuild(quality)
                display(
                    "text blur quality status",
                    "<div style='padding:12px'>Queued ${quality.estimatedBytes(poem.blurRequests.size) / (1024 * 1024)} MiB of text masks. Current settings appear in the frame output after preparation.</div>",
                )
            }
                .onFailure {
                    display("text blur quality status", "<div style='padding:12px'>${it.message}</div>")
                }
        }
    }
}

drawingBox.controlGroup("drawing", restoreValues = false) {
    button("Recenter poem") { down -> if (down) drawing.recenter() }
    keyframeCheckbox(keys, "drawing.gently-face-the-reader", "Gently face the reader", get = { drawing.followReader }) {
        drawing.followReader = it
    }
    keyframeSlider(
        keys,
        "drawing.turning-half-life-seconds",
        "Turning half-life",
        unit = "s",
        get = { drawing.turnHalfLife.toDouble() },
        min = .1,
        max = 10.0,
    ) {
        drawing.turnHalfLife = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.stroke-width-multiplier",
        "Stroke width multiplier",
        get = { poem.current.strokeWidthScale.toDouble() },
        min = .2,
        max = 4.0,
    ) {
        poem.examples.forEach { e -> e.player.strokeWidthScale = it.toFloat() }
    }
    keyframeSlider(
        keys,
        "drawing.point-radius-multiplier",
        "Point radius multiplier",
        get = { poem.current.dotRadiusScale.toDouble() },
        min = .2,
        max = 4.0,
    ) {
        poem.examples.forEach { e -> e.player.dotRadiusScale = it.toFloat() }
    }
    keyframeCheckbox(keys, "drawing.show-diagram-dots", "Show diagram dots", get = { poem.current.showDots }) {
        poem.examples.forEach { e -> e.player.showDots = it }
    }
}

drawingBox.controlGroup("skeleton", restoreValues = false) {
    keyframeCheckbox(keys, "drawing.show-skeleton", "Show skeleton", get = { root.showSkeleton }) {
        root.showSkeleton = it
    }
    keyframeCheckbox(
        keys,
        "drawing.show-simd-nodes",
        "Show SIMD nodes",
        get = { root.showPhysicsNodes },
        hoverText = "Exact SIMD positions tested against the body. Bright = receiving force; dim = pinned.",
    ) {
        root.showPhysicsNodes = it
    }
    keyframeCheckbox(
        keys,
        "drawing.show-body-force-lines",
        "Show body force lines",
        get = { root.showBodyForces },
        hoverText = "Connect the selected bone centreline to the node when the solver applies a body force.",
    ) {
        root.showBodyForces = it
    }
}

drawingBox.controlGroup("frosted frames", restoreValues = false) {
    keyframeCheckbox(
        keys,
        "drawing.frost-behind-text-frames",
        "Frost behind text frames",
        get = { frames().firstOrNull()?.frost?.enabled ?: true },
    ) { enabled ->
        frames().forEach { it.frost.enabled = enabled }
    }
    keyframeSlider(
        keys,
        "drawing.frost-opacity",
        "Frost opacity",
        get = { (frames().firstOrNull()?.frost?.opacity ?: 1f).toDouble() },
        min = 0.0,
        max = 1.0,
    ) { value ->
        frames().forEach { it.frost.opacity = value.toFloat() }
    }
    keyframeSlider(
        keys,
        "drawing.frost-blur-scene-pixels",
        "Frost blur",
        unit = "scene px",
        get = { (frames().firstOrNull()?.frost?.blurPixels ?: 5f).toDouble() },
        min = 0.0,
        max = 32.0,
    ) { value ->
        frames().forEach { it.frost.blurPixels = value.toFloat() }
    }
    keyframeSlider(
        keys,
        "drawing.frost-brightness",
        "Frost brightness",
        get = { (frames().firstOrNull()?.frost?.brightness ?: 1.1f).toDouble() },
        min = 0.0,
        max = 2.0,
    ) { value ->
        frames().forEach { it.frost.brightness = value.toFloat() }
    }
}

drawingBox.controlGroup("frost gamma", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.frost-blur-gamma",
        "Frost blur gamma",
        get = { root.frostBlurGamma.toDouble() },
        min = .25,
        max = 4.0,
        step = .01,
    ) {
        root.frostBlurGamma = it.toFloat()
    }
}

drawingBox.controlGroup("frost black level", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.frost-black-level",
        "Frost black level",
        get = { root.frostBlackLevel.toDouble() },
        min = 0.0,
        max = .1,
        step = .001,
    ) {
        root.frostBlackLevel = it.toFloat()
    }
}

drawingBox.controlGroup("text blur appearance", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.final-contrast-power",
        "Final contrast",
        unit = "power",
        get = { (1f / grade().gamma.w).toDouble() },
        min = .25,
        max = 4.0,
        step = .01,
    ) {
        grade().gamma.w = 1f / it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.text-blur-contrast-power",
        "Text blur contrast",
        unit = "power",
        get = { root.textBlurContrast.toDouble() },
        min = .25,
        max = 4.0,
        step = .01,
    ) {
        root.textBlurContrast = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.text-blur-gain",
        "Text blur gain",
        get = { root.textBlurGain.toDouble() },
        min = 0.0,
        max = 4.0,
        step = .01,
    ) {
        root.textBlurGain = it.toFloat()
    }
}

drawingBox.controlGroup("blur cascade", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.medium-blur-radius",
        "Medium blur radius",
        get = { grade().mediumRadius.toDouble() },
        min = 0.0,
        max = 8.0,
    ) {
        grade().mediumRadius = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.broad-blur-radius",
        "Broad blur radius",
        get = { grade().broadRadius.toDouble() },
        min = 0.0,
        max = 8.0,
    ) {
        grade().broadRadius = it.toFloat()
    }
}

drawingBox.controlGroup("green compositor", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.blur-mix",
        "Blur mix",
        get = { grade().selection.z.toDouble() },
        min = 0.0,
        max = 1.0,
    ) {
        grade().selection.z = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.green-selector-gain",
        "Green selector gain",
        get = { grade().selection.x.toDouble() },
        min = 0.0,
        max = 8.0,
    ) {
        grade().selection.x = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.green-selector-offset",
        "Green selector offset",
        get = { grade().selection.y.toDouble() },
        min = -1.0,
        max = 1.0,
    ) {
        grade().selection.y = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.source-green-scale",
        "Source green scale",
        get = { grade().selection.w.toDouble() },
        min = 0.0,
        max = 4.0,
    ) {
        grade().selection.w = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.selector-sharp-weight",
        "Selector sharp weight",
        get = { grade().selectorSource.x.toDouble() },
        min = 0.0,
        max = 1.0,
    ) {
        grade().selectorSource.x = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.selector-medium-weight",
        "Selector medium weight",
        get = { grade().selectorSource.y.toDouble() },
        min = 0.0,
        max = 1.0,
    ) {
        grade().selectorSource.y = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.selector-broad-weight",
        "Selector broad weight",
        get = { grade().selectorSource.z.toDouble() },
        min = 0.0,
        max = 1.0,
    ) {
        grade().selectorSource.z = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.green-from-red-blue",
        "Green from red / blue",
        get = { grade().reconstruction.x.toDouble() },
        min = 0.0,
        max = 1.0,
    ) {
        grade().reconstruction.x = it.toFloat()
        grade().reconstruction.y = 1f - it.toFloat()
    }
}

drawingBox.controlGroup("light and colour", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.exposure",
        "Exposure",
        get = { grade().reconstruction.z.toDouble() },
        min = 0.0,
        max = 4.0,
    ) {
        grade().reconstruction.z = it.toFloat()
    }
    keyframeSlider(
        keys,
        "drawing.saturation",
        "Saturation",
        get = { grade().saturation.x.toDouble() },
        min = 0.0,
        max = 3.0,
    ) {
        grade().saturation.x = it.toFloat()
    }
    keyframeSlider(keys, "drawing.fxaa-mix", "FXAA mix", get = { grade().fxaa.x.toDouble() }, min = 0.0, max = 1.0) {
        grade().fxaa.x = it.toFloat()
    }
    keyframeColorPicker(keys, "drawing.background", "Background", get = { grade().background }) {
        grade().background.set(it)
    }
    keyframeColorPicker(
        keys,
        "drawing.shadow-tone-alpha-strength",
        "Shadow tone (alpha = strength)",
        get = { grade().shadowTone },
    ) {
        grade().shadowTone.set(it)
    }
    keyframeColorPicker(
        keys,
        "drawing.highlight-tone-alpha-strength",
        "Highlight tone (alpha = strength)",
        get = { grade().highlightTone },
    ) {
        grade().highlightTone.set(it)
    }
}

drawingBox.controlGroup("display dither", restoreValues = false) {
    keyframeSlider(
        keys,
        "drawing.dither-8-bit-code-steps",
        "Dither",
        unit = "8-bit steps",
        get = { grade().supersampling.w.toDouble() },
        min = 0.0,
        max = 2.0,
    ) {
        grade().supersampling.w = it.toFloat()
    }
}

bodyBox.controlGroup("body interaction", restoreValues = false) {
    keyframeCheckbox(keys, "body.body-interaction", "Body interaction", get = { bodyBox.readingBodyEnabled }) {
        bodyBox.readingBodyEnabled = it
    }
    keyframeCheckbox(
        keys,
        "body.waft-away-outward-band",
        "Waft away (outward band)",
        get = { bodyBox.readingBodyWaft },
    ) {
        bodyBox.readingBodyWaft = it
    }
    button("Waft away and settle") { down -> if (down) bodyWaftPreset() }
    keyframeSlider(
        keys,
        "body.waft-acceleration-m-s",
        "Waft acceleration",
        unit = "m/s²",
        get = { bodyBox.readingBodyWaftStrength.toDouble() },
        min = 0.0,
        max = 15.0,
    ) {
        bodyBox.readingBodyWaftStrength = it.toFloat()
    }
    keyframeSlider(
        keys,
        "body.outside-reach-m",
        "Outside reach",
        unit = "m",
        get = { bodyBox.readingBodyReach.toDouble() },
        min = 0.0,
        max = 1.5,
    ) {
        bodyBox.readingBodyReach = it.toFloat()
    }
    keyframeSlider(
        keys,
        "body.push-inside-capsule",
        "Push inside capsule",
        get = { bodyBox.readingBodyPush.toDouble() },
        min = 0.0,
        max = 80.0,
    ) {
        bodyBox.readingBodyPush = it.toFloat()
    }
    keyframeSlider(
        keys,
        "body.standoff-gap-m",
        "Standoff gap",
        unit = "m",
        get = { bodyBox.readingBodyGap.toDouble() },
        min = 0.0,
        max = .5,
    ) {
        bodyBox.readingBodyGap = it.toFloat()
    }
    keyframeSlider(
        keys,
        "body.range-falloff",
        "Range falloff",
        get = { bodyBox.readingBodyFalloff.toDouble() },
        min = 0.0,
        max = 8.0,
    ) {
        bodyBox.readingBodyFalloff = it.toFloat()
    }
    keyframeSlider(
        keys,
        "body.body-acceleration-cap-0-off",
        "Body acceleration cap (0 off)",
        unit = "m/s²",
        get = { bodyBox.readingBodyMaximum.toDouble() },
        min = 0.0,
        max = 20.0,
    ) {
        bodyBox.readingBodyMaximum = it.toFloat()
    }
    keyframeSlider(
        keys,
        "body.body-radius-scale",
        "Body radius scale",
        get = { bodyBox.readingBodyRadiusScale.toDouble() },
        min = .1,
        max = 3.0,
    ) {
        bodyBox.readingBodyRadiusScale = it.toFloat()
    }
    keyframeCheckbox(
        keys,
        "body.include-finger-segments",
        "Include finger segments",
        get = { bodyBox.readingBodyHands },
    ) {
        bodyBox.readingBodyHands = it
    }
    keyframeCheckbox(keys, "body.include-head", "Include head", get = { bodyBox.readingBodyHead }) {
        bodyBox.readingBodyHead = it
    }
    keyframeSlider(
        keys,
        "body.classic-gathering-gain",
        "Classic gathering gain",
        get = { bodyBox.readingBodyPull.toDouble() },
        min = 0.0,
        max = 40.0,
        hoverText = "Used only when Waft away is off. Positive values gather anchors toward the standoff surface.",
    ) {
        bodyBox.readingBodyPull = it.toFloat()
    }
    keyframeSlider(
        keys,
        "body.classic-inside-reach-0-all",
        "Classic inside reach (0 = all)",
        unit = "m",
        get = { bodyBox.readingBodyInsideReach.toDouble() },
        min = 0.0,
        max = 1.0,
    ) {
        bodyBox.readingBodyInsideReach = it.toFloat()
    }
}

scoreBox.controlGroup("gentle physics", restoreValues = false) {
    keyframeSlider(
        keys,
        "score.layout-spring",
        "Layout spring",
        get = { poem.current.spring.toDouble() },
        min = 0.0,
        max = 100.0,
    ) {
        poem.examples.forEach { e -> e.player.spring = it.toFloat() }
    }
    keyframeSlider(
        keys,
        "score.link-stiffness",
        "Link stiffness",
        get = { poem.current.constraintStrength.toDouble() },
        min = 0.0,
        max = 80.0,
    ) {
        poem.examples.forEach { e -> e.player.constraintStrength = it.toFloat() }
    }
    keyframeSlider(keys, "score.gravity", "Gravity", get = { poem.current.gravity.toDouble() }, min = -.3, max = .1) {
        poem.examples.forEach { e -> e.player.gravity = it.toFloat() }
    }
    keyframeSlider(
        keys,
        "score.air-movement",
        "Air movement",
        get = { poem.current.wind.toDouble() },
        min = 0.0,
        max = .3,
    ) {
        poem.examples.forEach { e -> e.player.wind = it.toFloat() }
    }
    button("Still proof / reset") { down ->
        if (down) {
            poem.current.gravity = 0f
            poem.current.wind = 0f
            poem.current.resetPositions()
            poem.current.playback.playing = false
            poem.current.playback.seek(6.0)
        }
    }
}

spaceBox.controlGroup("reading space", restoreValues = false) {
    keyframeCheckbox(
        keys,
        "context.show-both-eyes-side-by-side",
        "Show both eyes side by side",
        get = { spaceBox.showStereoPair },
    ) {
        spaceBox.showStereoPair = it
    }
    keyframeCheckbox(
        keys,
        "context.desktop-preview",
        "Desktop preview",
        get = { renderer.desktopPresentationEnabled },
    ) {
        renderer.desktopPresentationEnabled = it
    }
}

navigationBox.controlGroup("move through the reading space", restoreValues = false) {
    keyframeCheckbox(
        keys,
        "navigation.left-stick-movement",
        "Left stick movement",
        get = { navigationBox.readingWalkEnabled },
    ) {
        navigationBox.readingWalkEnabled = it
    }
    keyframeSlider(
        keys,
        "navigation.walking-speed-m-s",
        "Walking speed",
        unit = "m/s",
        get = { navigationBox.readingWalkSpeed.toDouble() },
        min = .05,
        max = 2.0,
    ) {
        navigationBox.readingWalkSpeed = it.toFloat()
    }
}

// Capture individual values after the aggregate controls: a recall preserves intentional differences
// between examples and frosted panels. Getters resolve inherited props on every use, including reloads.
for (i in poem.examples.indices) {
    fun player() = poem.examples[i].player
    val prefix = "example.$i"
    keys.floatTarget("$prefix.spring", { player().spring }) { player().spring = it }
    keys.floatTarget("$prefix.links", { player().constraintStrength }) { player().constraintStrength = it }
    keys.floatTarget("$prefix.gravity", { player().gravity }) { player().gravity = it }
    keys.floatTarget("$prefix.wind", { player().wind }) { player().wind = it }
    keys.floatTarget("$prefix.damping", { player().dampingRate }) { player().dampingRate = it }
    keys.floatTarget("$prefix.textScale", { player().textScale }) { player().textScale = it }
    keys.floatTarget("$prefix.strokes", { player().strokeWidthScale }) { player().strokeWidthScale = it }
    keys.floatTarget("$prefix.dots", { player().dotRadiusScale }) { player().dotRadiusScale = it }
    keys.booleanTarget("$prefix.showDots", { player().showDots }) { player().showDots = it }
    for (j in player().diagram.textFrames.indices) {
        fun frost() = player().diagram.textFrames[j].frost
        keys.booleanTarget("$prefix.frame.$j.enabled", { frost().enabled }) { frost().enabled = it }
        keys.floatTarget("$prefix.frame.$j.opacity", { frost().opacity }) { frost().opacity = it }
        keys.floatTarget("$prefix.frame.$j.blur", { frost().blurPixels }) { frost().blurPixels = it }
        keys.floatTarget("$prefix.frame.$j.brightness", { frost().brightness }) { frost().brightness = it }
        keys.floatTarget("$prefix.frame.$j.black", { frost().blackLevel }) { frost().blackLevel = it }
        fun frame() = player().diagram.textFrames[j]
        keys.floatTarget("$prefix.frame.$j.standoff", { frame().standoff }) { frame().standoff = it }
        keys.floatTarget("$prefix.frame.$j.overstrokeFraction", { frame().overstrokeFraction }) {
            frame().overstrokeFraction = it
        }
        keys.floatTarget("$prefix.frame.$j.overstrokeDistance", { frame().overstrokeDistance }) {
            frame().overstrokeDistance = it
        }
    }
    for (j in player().diagram.textFrusta.indices) {
        fun holder() = player().diagram.textFrusta[j]
        val lane = "$prefix.frustum.$j.aspect"
        keys.floatTarget(lane, { holder().aspectRatio ?: 0f }) {
            holder().aspectRatio = it.takeIf { value -> value > 0f }
        }
        // Zero means automatic fit, not a very narrow rectangle: step when either end is automatic.
        keys.interpolators[lane] = { a, b, t ->
            val x = (a as Number).toFloat()
            val y = (b as Number).toFloat()
            if (x == 0f || y == 0f) {
                if (t < 1.0) x else y
            } else x + (y - x) * t.toFloat()
        }
    }
}

// Include shader-only colour controls too. Visible controls above own their lanes and UI mirrors;
// only unrepresented vector components are registered here, avoiding two setters for one field.
val vectors = linkedMapOf<String, () -> Vector4f>(
    "selection" to { grade().selection },
    "reconstruction" to { grade().reconstruction },
    "selectorRange" to { grade().selectorRange },
    "selectorSource" to { grade().selectorSource },
    "paint" to { grade().paint },
    "colorFold" to { grade().colorFold },
    "foldLimits" to { grade().foldLimits },
    "alphaFold" to { grade().alphaFold },
    "saturation" to { grade().saturation },
    "gain" to { grade().gain },
    "gamma" to { grade().gamma },
    "toneRange" to { grade().toneRange },
    "fxaa" to { grade().fxaa },
    "subpixel" to { grade().subpixel },
    "supersampling" to { grade().supersampling },
)
val visibleComponents = setOf(
    "selection.x",
    "selection.y",
    "selection.z",
    "selection.w",
    "reconstruction.x",
    "reconstruction.z",
    "selectorSource.x",
    "selectorSource.y",
    "selectorSource.z",
    "saturation.x",
    "gamma.w",
    "fxaa.x",
    "supersampling.w",
)
for ((name, vector) in vectors) for ((axis, suffix) in listOf("x", "y", "z", "w").withIndex()) {
    if ("$name.$suffix" !in visibleComponents)
        keys.floatTarget(
            "compositor.$name.$suffix",
            { vector().get(axis) },
            { value ->
                when (axis) {
                    0 -> vector().x = value
                    1 -> vector().y = value
                    2 -> vector().z = value
                    3 -> vector().w = value
                }
            },
        )
}
keys.floatTarget("drawing.distance", { drawing.distance }) { drawing.distance = it }
keys.floatTarget("drawing.minimumStrokePixels", { drawing.minimumStrokePixels }) { drawing.minimumStrokePixels = it }
keys.floatTarget("drawing.minimumPointPixels", { drawing.minimumPointPixels }) { drawing.minimumPointPixels = it }

// Cache dimensions are staged, discrete choices. Morphing never reallocates a GPU image;
// recall restores these controls, and Apply text blur quality performs the rebuild explicitly.
keys.interpolators["drawing.cache-width-pixels"] = { a, b, t ->
    if (t < 1.0) a else b
}
keys.interpolators["drawing.cache-height-pixels"] = { a, b, t ->
    if (t < 1.0) a else b
}
keys.interpolators["drawing.broad-level-minimum-pixels"] = { a, b, t ->
    if (t < 1.0) a else b
}
keys.interpolators["drawing.levels-per-size-halving"] = { a, b, t ->
    if (t < 1.0) a else b
}
keys.interpolators["drawing.maximum-taps-per-axis"] = { a, b, t ->
    if (t < 1.0) a else b
}
keys.interpolators["drawing.gaussian-support-sigma"] = { a, b, t ->
    if (t < 1.0) a else b
}

val keyDirectory = "global-keys".path
Files.createDirectories(keyDirectory)
val startingLook = keyDirectory.resolve("starting-look")
if (!Files.exists(startingLook))
    keys.saveKey(startingLook, keys.capture(), "Live look before enabling global keyframes")
controlGroup("global looks", restoreValues = false) {
    presetBar(keys, directory = keyDirectory)
}
keyframeBrowser(keyDirectory, channel = "saved global looks", scope = keys, width = 620, height = 420)
display(
    "keyframe guide",
    "<div style='padding:12px;width:560px'>Store saves every global control, including colour, blur, frosting, physics, body interaction and navigation. Recall restores a named look and its sliders. Preset morph blends saved looks in name order. Cache quality is staged; use Apply text blur quality to rebuild. Score time, node positions and headset pose are independent. See guide 16.</div>",
)
```

