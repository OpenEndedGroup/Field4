# Field Document: aperture_playground

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1. Renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "1. Renderer" "parents" ["root"] "children" ["aperture_sprites" "aperture_source"] "properties" {"children" #{#afield/box "aperture_sprites" #afield/box "aperture_source"} "parents" #{#afield/box "root"} "name" "1. Renderer" "_tombstone" false "frame" #afield/rect [60.0 70.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 10 "characters" 279 "sha256" "59930bc0746093a513d55dc04694cfce08a47dd3851c5aad863cf726b55968b5"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field — aperture playground", initialDimensions = Rect(80.0, 80.0, 960.0, 720.0))
    Renderer(window)
}
start(renderer::run)
```

## 2. 2. Source RTT — lines and points

<!-- field-box id="aperture_source" index="1" -->

```edn [field-box id="aperture_source" role="properties"]
{"id" "aperture_source" "index" 1 "name" "2. Source RTT — lines and points" "parents" ["root" "agent_graphics_context"] "children" ["aperture_half"] "properties" {"children" #{#afield/box "aperture_half"} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "2. Source RTT — lines and points" "_tombstone" false "frame" #afield/rect [460.0 70.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_source_code_.kt" "lines" 43 "characters" 1638 "sha256" "f9f25cfe543ead7ceb7c7f250cb87f5516da0d41a6092836b5ba519d6938a65f"} {"name" "vertex" "language" "glsl" "path" "aperture_source_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_source_fragment_.glsl" "lines" 26 "characters" 927 "sha256" "52fd5840bbc29fbd596a4b00c17be6df51103f76300a2782bb35816e2a2a0c8f"}]}
```

```kotlin [field-property box="aperture_source" name="code" path="aperture_source_code_.kt"]
import org.lwjgl.vulkan.VK10.*

/*
Full-resolution RGBA16F source. RGBA hold light at radii 0, 4, 10, 24 px.
Alpha is the fourth light band, so every intermediate filter must use blend=false.
propDown: re-running this box with new dimensions re-runs the passes that read paintSource.
*/
propDown<RenderToTexture>(paintSource)
paintSource = RenderToTexture {
    width = window.width
    height = window.height
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paint-source"
}
paintSource.setDefaultSampler(anisotropyEnable = false)
renderer.renderList.preTargets["paint-source"] = paintSource
prop<afield.math.linalg.Vec4>(apertureControl)
apertureControl = vec(1.5, 0, 0, 0)
println("paint source RTT ready: ${paintSource.width}x${paintSource.height}")

var module = moduleHere {
    paintSource.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "source" to mesh
mesh.resize(3)
paintSource += "source" to module
// x = requested maximum defocus pixels (capped at 24), y = source energy; z/w are unused.
mesh.look = { vec(24.0, 1.0, 0.0, 0.0) }
mesh.size = { vec(paintSource.width, paintSource.height, 0, 0) }

// Nest the two editable reduction helpers without hiding their shaders.
frame = Rect(360.0, 70.0, 260.0, 270.0)
inside.boxes.getValue("aperture_half").nestedIn = thisBox
inside.boxes.getValue("aperture_half").frame = Rect(15.0, 70.0, 240.0, 55.0)
inside.boxes.getValue("aperture_quarter").nestedIn = thisBox
inside.boxes.getValue("aperture_quarter").frame = Rect(15.0, 150.0, 240.0, 55.0)
```

```glsl [field-property box="aperture_source" name="vertex" path="aperture_source_vertex_.glsl"]
#version 450
layout(location=0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    // Field uses a negative-height Vulkan viewport.
    uv = vec2(p.x, 1.0-p.y);
}
```

```glsl [field-property box="aperture_source" name="fragment" path="aperture_source_fragment_.glsl"]
#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(push_constant) uniform Source {
    vec4 look;
    vec4 size;
} pc;
float capsule(vec2 p, vec2 a, vec2 b) {
    vec2 d=b-a;
    return length(p-a-d*clamp(dot(p-a,d)/dot(d,d),0.0,1.0));
}
vec4 bands(float radius) {
    if(radius<4.0) return mix(vec4(1,0,0,0),vec4(0,1,0,0),radius/4.0);
    if(radius<10.0) return mix(vec4(0,1,0,0),vec4(0,0,1,0),(radius-4.0)/6.0);
    return mix(vec4(0,0,1,0),vec4(0,0,0,1),clamp((radius-10.0)/14.0,0.0,1.0));
}
void main() {
    vec2 p=uv*pc.size.xy;
    float row=floor(uv.y*4.0);
    float y=(row+.5)*pc.size.y/4.0;
    float d=capsule(p,vec2(pc.size.x*.12,y-32),vec2(pc.size.x*.70,y+32));
    float line=1.0-smoothstep(.3,1.3,d);
    float dots=1.0-smoothstep(7.0,8.0,length(p-vec2(pc.size.x*.82,y)));
    float radius=clamp(pc.look.x*row/3.0,0.0,24.0);
    result=bands(radius)*(line+dots)*pc.look.y;
}
```

## 3. 3. Reduce ½

<!-- field-box id="aperture_half" index="2" -->

```edn [field-box id="aperture_half" role="properties"]
{"id" "aperture_half" "index" 2 "name" "3. Reduce ½" "parents" ["root" "aperture_source"] "children" ["aperture_quarter" "aperture_medium"] "properties" {"children" #{#afield/box "aperture_quarter" #afield/box "aperture_medium"} "parents" #{#afield/box "root" #afield/box "aperture_source"} "name" "3. Reduce ½" "_tombstone" false "frame" #afield/rect [460.0 260.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_half_code_.kt" "lines" 25 "characters" 776 "sha256" "52d58efb26969957cb6eafe1a7bc4b1c86037c3b6cd1fa98dfdaa2bbb7b8d986"} {"name" "vertex" "language" "glsl" "path" "aperture_half_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_half_fragment_.glsl" "lines" 11 "characters" 454 "sha256" "f7dad831d940b89297b19153878272f3195716a1205a80dc454f539f74a25ad9"}]}
```

```kotlin [field-property box="aperture_half" name="code" path="aperture_half_code_.kt"]
import org.lwjgl.vulkan.VK10.*

propDown<RenderToTexture>(paintHalf) = RenderToTexture {
    width = (paintSource.width / 2).coerceAtLeast(1)
    height = (paintSource.height / 2).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paintHalf"
}
paintHalf.setDefaultSampler(anisotropyEnable = false)
var module = moduleHere {
    textures("paintSource" to paintSource)
    paintHalf.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintHalf += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintHalf"] = paintHalf
```

```glsl [field-property box="aperture_half" name="vertex" path="aperture_half_vertex_.glsl"]
#version 450
layout(location=0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    // Field uses a negative-height Vulkan viewport.
    uv = vec2(p.x, 1.0-p.y);
}
```

```glsl [field-property box="aperture_half" name="fragment" path="aperture_half_fragment_.glsl"]
#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintSource;
void main() {
    // Average a pixel footprint. RGBA remain independent energy bands.
    vec2 d = 0.25 * vec2(dFdx(uv.x), dFdy(uv.y));
    result = 0.25 * (texture(paintSource,uv+vec2(-d.x,-d.y))
    + texture(paintSource,uv+vec2(d.x,-d.y))
    + texture(paintSource,uv+vec2(-d.x,d.y)) + texture(paintSource,uv+d));
}
```

## 4. 4. Reduce ¼

<!-- field-box id="aperture_quarter" index="3" -->

```edn [field-box id="aperture_quarter" role="properties"]
{"id" "aperture_quarter" "index" 3 "name" "4. Reduce ¼" "parents" ["root" "aperture_half"] "children" ["aperture_broad"] "properties" {"children" #{#afield/box "aperture_broad"} "parents" #{#afield/box "root" #afield/box "aperture_half"} "name" "4. Reduce ¼" "_tombstone" false "frame" #afield/rect [460.0 440.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_quarter_code_.kt" "lines" 25 "characters" 793 "sha256" "051ad22d86a65f45288e02232028773c1507b4bb3cae7c53f98e69ac3635f415"} {"name" "vertex" "language" "glsl" "path" "aperture_quarter_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_quarter_fragment_.glsl" "lines" 11 "characters" 444 "sha256" "ced8adc4342a1323b853755fe2038584cf0c1fbf839ea5bb709bc6da8f6315f6"}]}
```

```kotlin [field-property box="aperture_quarter" name="code" path="aperture_quarter_code_.kt"]
import org.lwjgl.vulkan.VK10.*

propDown<RenderToTexture>(paintQuarter) = RenderToTexture {
    width = (paintSource.width / 4).coerceAtLeast(1)
    height = (paintSource.height / 4).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paintQuarter"
}
paintQuarter.setDefaultSampler(anisotropyEnable = false)
var module = moduleHere {
    textures("paintHalf" to paintHalf)
    paintQuarter.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintQuarter += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintQuarter"] = paintQuarter
```

```glsl [field-property box="aperture_quarter" name="vertex" path="aperture_quarter_vertex_.glsl"]
#version 450
layout(location=0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    // Field uses a negative-height Vulkan viewport.
    uv = vec2(p.x, 1.0-p.y);
}
```

```glsl [field-property box="aperture_quarter" name="fragment" path="aperture_quarter_fragment_.glsl"]
#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintHalf;
void main() {
    // Average a pixel footprint. RGBA remain independent energy bands.
    vec2 d = 0.25 * vec2(dFdx(uv.x), dFdy(uv.y));
    result = 0.25 * (texture(paintHalf,uv+vec2(-d.x,-d.y))
    + texture(paintHalf,uv+vec2(d.x,-d.y))
    + texture(paintHalf,uv+vec2(-d.x,d.y)) + texture(paintHalf,uv+d));
}
```

## 5. 8. Analytic point sprites

<!-- field-box id="aperture_sprites" index="4" -->

### Narrative

From `code`

Exact disc/aperture overlap: no texture or blur pass for this column.

```edn [field-box id="aperture_sprites" role="properties"]
{"id" "aperture_sprites" "index" 4 "name" "8. Analytic point sprites" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "8. Analytic point sprites" "_tombstone" false "frame" #afield/rect [1320.0 440.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_sprites_code_.kt" "lines" 15 "characters" 559 "sha256" "ae18a7f13608085e061a7b014c7992820562d1e7c0433f4f57b70f1bb5463a2f"} {"name" "vertex" "language" "glsl" "path" "aperture_sprites_vertex_.glsl" "lines" 16 "characters" 567 "sha256" "7c05a99e285e628d5a8e5bd7ecdaad19c502de74531ba4c11391f95fdeabeda2"} {"name" "fragment" "language" "glsl" "path" "aperture_sprites_fragment_.glsl" "lines" 32 "characters" 1443 "sha256" "808feb9ea02f6de9fc5e255a04ac0c202e870b8ad8fdfedd78025740aae48078"}]}
```

```kotlin [field-property box="aperture_sprites" name="code" path="aperture_sprites_code_.kt"]
// Exact disc/aperture overlap: no texture or blur pass for this column.
var module = moduleHere {
    blend = true
    srcColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    dstColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "sprites" to mesh
mesh.resize(24)
mesh.size = { vec(window.width, window.height, 0, 0) }
// x: maximum aperture pixels; y: outline; z: intensity; w: solid fill.
mesh.look = { vec(24.0, 0.0, 1.0, 1.0) }
renderer.renderList += "aperture-sprites" to module
```

```glsl [field-property box="aperture_sprites" name="vertex" path="aperture_sprites_vertex_.glsl"]
#version 450
layout(push_constant) uniform Sprite {
    vec4 size;
    vec4 look;
} pc;
layout(location=0) out vec2 local;
layout(location=1) flat out vec2 appearance;
const vec2 corners[6] = vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
void main() {
    float row=float(gl_VertexIndex/6);
    appearance=vec2(1.0,pc.look.x*row/3.0/8.0);
    local=corners[gl_VertexIndex%6]*(1.0+appearance.y);
    vec2 p=vec2(pc.size.x*.93,pc.size.y*(row+.5)/4.0)+local*8.0;
    vec2 ndc=p/pc.size.xy*2.0-1.0;
    gl_Position=vec4(ndc.x,-ndc.y,0,1);
}
```

```glsl [field-property box="aperture_sprites" name="fragment" path="aperture_sprites_fragment_.glsl"]
#version 450
layout(push_constant) uniform push {
    vec4 size;
    vec4 look;
} pc;
layout(location=0) in vec2 local;
layout(location=1) flat in vec2 appearance; // visible energy, aperture radius
layout(location=0) out vec4 color;

// A disc convolved with a uniform circular aperture: overlap area / aperture area.
// This spreads the sprite's energy rather than inflating an equally bright soft circle.
float apertureDisc(float d, float body, float aperture, float aa) {
    if (aperture < aa) return 1.0-smoothstep(body-aa, body+aa, d);
    if (d >= body+aperture) return 0.0;
    if (d <= abs(body-aperture)) return min(1.0, body*body/(aperture*aperture));
    float d2 = d*d, r2 = body*body, b2 = aperture*aperture;
    float u = clamp((d2+r2-b2)/(2.0*d*body), -1.0, 1.0);
    float v = clamp((d2+b2-r2)/(2.0*d*aperture), -1.0, 1.0);
    float h = max(0.0, (-d+body+aperture)*(d+body-aperture)*(d-body+aperture)*(d+body+aperture));
    return clamp((r2*acos(u)+b2*acos(v)-0.5*sqrt(h))/(3.14159265*b2), 0.0, 1.0);
}

void main() {
    float r = length(local);
    float aa = max(fwidth(r), 0.002);
    float aperture = appearance.y;
    float disc = apertureDisc(r, 1.0, aperture, aa);
    float ring = max(0.0, disc-apertureDisc(r, 0.66, aperture, aa));
    // Independent outline, intensity and fill controls.
    float energy = pc.look.y*ring + pc.look.z*appearance.x*mix(ring,disc,pc.look.w);
    color = vec4(vec3(energy), 0.0);
}
```

## 6. 6. Aperture 24 px

<!-- field-box id="aperture_broad" index="5" -->

```edn [field-box id="aperture_broad" role="properties"]
{"id" "aperture_broad" "index" 5 "name" "6. Aperture 24 px" "parents" ["root" "aperture_quarter"] "children" ["aperture_composite"] "properties" {"children" #{#afield/box "aperture_composite"} "parents" #{#afield/box "root" #afield/box "aperture_quarter"} "name" "6. Aperture 24 px" "_tombstone" false "frame" #afield/rect [890.0 440.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_broad_code_.kt" "lines" 28 "characters" 898 "sha256" "821cc3fbd5107d84571f217c229d2419555ff80a91c6f62b6c7a5d889b6292bf"} {"name" "vertex" "language" "glsl" "path" "aperture_broad_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_broad_fragment_.glsl" "lines" 27 "characters" 1057 "sha256" "6b5b546e1ec925580d3684b191205d36c33888920fc31f265d98955e79995c63"}]}
```

```kotlin [field-property box="aperture_broad" name="code" path="aperture_broad_code_.kt"]
import org.lwjgl.vulkan.VK10.*

propDown<RenderToTexture>(paintBroad) = RenderToTexture {
    width = (paintSource.width / 4).coerceAtLeast(1)
    height = (paintSource.height / 4).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paintBroad"
}
paintBroad.setDefaultSampler(anisotropyEnable = false)
var module = moduleHere {
    textures("paintQuarter" to paintQuarter)
    paintBroad.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintBroad += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintBroad"] = paintBroad

// x/y stretch the aperture; z changes its rim softness; w is gain.
mesh.aperture = { vec(1.0, 1.0, 1.0, 1.0) }
```

```glsl [field-property box="aperture_broad" name="vertex" path="aperture_broad_vertex_.glsl"]
#version 450
layout(location=0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    // Field uses a negative-height Vulkan viewport.
    uv = vec2(p.x, 1.0-p.y);
}
```

```glsl [field-property box="aperture_broad" name="fragment" path="aperture_broad_fragment_.glsl"]
#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintQuarter;
layout(push_constant) uniform Aperture {
    vec4 aperture;
} pc;
vec4 sampleBlack(vec2 p) {
    if (any(lessThan(p,vec2(0))) || any(greaterThan(p,vec2(1)))) return vec4(0);
    return texture(paintQuarter,p);
}
void main() {
    vec2 texel = 1.0/vec2(textureSize(paintQuarter,0));
    float rim = clamp(pc.aperture.z,0.05,1.0);
    result = vec4(0);
    vec4 weights = vec4(0);
    // A finite circular aperture with a one-texel soft rim. No random samples.
    // Half-resolution G/B radii 2/5 represent 4/10 screen pixels;
    // quarter-resolution A radius 6 represents 24 screen pixels.
    for (int y=-6;y<=6;y++) for (int x=-6;x<=6;x++) {
        float w = clamp((6.0+0.5*rim-length(vec2(x,y)))/rim,0.0,1.0);
        if (w <= 0.0) continue;
        result.a += w * sampleBlack(uv+texel*vec2(x,y)*pc.aperture.xy).a;
        weights.a += w;
    }
    result = pc.aperture.w * result / max(weights,vec4(0.000001));
}
```

## 7. 5. Aperture 4 / 10 px

<!-- field-box id="aperture_medium" index="6" -->

```edn [field-box id="aperture_medium" role="properties"]
{"id" "aperture_medium" "index" 6 "name" "5. Aperture 4 / 10 px" "parents" ["root" "aperture_half"] "children" ["aperture_composite"] "properties" {"children" #{#afield/box "aperture_composite"} "parents" #{#afield/box "root" #afield/box "aperture_half"} "name" "5. Aperture 4 / 10 px" "_tombstone" false "frame" #afield/rect [890.0 260.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_medium_code_.kt" "lines" 28 "characters" 899 "sha256" "e72062210b2dae13b31324475f8317984ac0bccff9ffc5220952b4f04f5525d3"} {"name" "vertex" "language" "glsl" "path" "aperture_medium_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_medium_fragment_.glsl" "lines" 29 "characters" 1115 "sha256" "b4e3de183e9ee1c6d70d5df07b441dda42849d8a08d1b5b6f65a6cb87ff904e1"}]}
```

```kotlin [field-property box="aperture_medium" name="code" path="aperture_medium_code_.kt"]
import org.lwjgl.vulkan.VK10.*

propDown<RenderToTexture>(paintMedium) = RenderToTexture {
    width = (paintSource.width / 2).coerceAtLeast(1)
    height = (paintSource.height / 2).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paintMedium"
}
paintMedium.setDefaultSampler(anisotropyEnable = false)
var module = moduleHere {
    textures("paintHalf" to paintHalf)
    paintMedium.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintMedium += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintMedium"] = paintMedium

// x/y stretch the aperture; z changes its rim softness; w is gain.
mesh.aperture = { vec(1.0, 1.0, 1.0, 1.0) }
```

```glsl [field-property box="aperture_medium" name="vertex" path="aperture_medium_vertex_.glsl"]
#version 450
layout(location=0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    // Field uses a negative-height Vulkan viewport.
    uv = vec2(p.x, 1.0-p.y);
}
```

```glsl [field-property box="aperture_medium" name="fragment" path="aperture_medium_fragment_.glsl"]
#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintHalf;
layout(push_constant) uniform Aperture {
    vec4 aperture;
} pc;
vec4 sampleBlack(vec2 p) {
    if (any(lessThan(p,vec2(0))) || any(greaterThan(p,vec2(1)))) return vec4(0);
    return texture(paintHalf,p);
}
void main() {
    vec2 texel = 1.0/vec2(textureSize(paintHalf,0));
    float rim = clamp(pc.aperture.z,0.05,1.0);
    result = vec4(0);
    vec4 weights = vec4(0);
    // A finite circular aperture with a one-texel soft rim. No random samples.
    // Half-resolution G/B radii 2/5 represent 4/10 screen pixels;
    // quarter-resolution A radius 6 represents 24 screen pixels.
    for (int y=-6;y<=6;y++) for (int x=-6;x<=6;x++) {
        float distance = length(vec2(x,y));
        vec2 w = clamp((vec2(2.0,5.0)+0.5*rim-distance)/rim,0.0,1.0);
        if (w.y <= 0.0) continue;
        vec4 c = sampleBlack(uv+texel*vec2(x,y)*pc.aperture.xy);
        result.gb += w*c.gb;
        weights.gb += w;
    }
    result = pc.aperture.w * result / max(weights,vec4(0.000001));
}
```

## 8. 7. Composite — sharp / blur / guide

<!-- field-box id="aperture_composite" index="7" -->

```edn [field-box id="aperture_composite" role="properties"]
{"id" "aperture_composite" "index" 7 "name" "7. Composite — sharp / blur / guide" "parents" ["root" "aperture_medium" "aperture_broad"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "aperture_medium" #afield/box "aperture_broad"} "name" "7. Composite — sharp / blur / guide" "_tombstone" false "frame" #afield/rect [1320.0 260.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_composite_code_.kt" "lines" 23 "characters" 835 "sha256" "bf9d25d78fe8004133f889edf73c34956b108b3307a8c8eff5ca05148ab2a0e2"} {"name" "vertex" "language" "glsl" "path" "aperture_composite_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_composite_fragment_.glsl" "lines" 19 "characters" 729 "sha256" "870d46d6e6d7caa9b2a9da5f87b395e668bde930fea8dcd29ac954f775b7cea1"}]}
```

```kotlin [field-property box="aperture_composite" name="code" path="aperture_composite_code_.kt"]
import org.lwjgl.vulkan.VK10.*

// Screen composite: apertureControl.x is gain; y selects the display mode.
// Edit and rerun apertureControl.set below to update the vector read by the push supplier.
var module = moduleHere {
    textures("paintSource" to paintSource, "paintMedium" to paintMedium, "paintBroad" to paintBroad)
    depthTest = false
    depthWrite = false
    blend = true
    srcColor = VK_BLEND_FACTOR_ONE
    dstColor = VK_BLEND_FACTOR_ONE
    srcAlpha = VK_BLEND_FACTOR_ZERO
    dstAlpha = VK_BLEND_FACTOR_ONE
}
var mesh = Mesh()
module += "composite" to mesh
mesh.resize(3)
mesh.displayControl = { apertureControl }
renderer.renderList += "paint-composite" to module
println("paint composite ready")

// y: 0 aperture, 1 sharp, 2 radius guide. Edit here and re-execute.
apertureControl.set(1.0f, 0.0f, 0.0f, 0.0f)
```

```glsl [field-property box="aperture_composite" name="vertex" path="aperture_composite_vertex_.glsl"]
#version 450
layout(location=0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    // Field uses a negative-height Vulkan viewport.
    uv = vec2(p.x, 1.0-p.y);
}
```

```glsl [field-property box="aperture_composite" name="fragment" path="aperture_composite_fragment_.glsl"]
#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintSource;
layout(set=0,binding=1) uniform sampler2D paintMedium;
layout(set=0,binding=2) uniform sampler2D paintBroad;
layout(push_constant) uniform Display {
    vec4 displayControl;
} pc;
void main() {
    vec4 sharp = texture(paintSource,uv);
    vec4 mid = texture(paintMedium,uv);
    float energy = dot(sharp,vec4(1));
    float c = sharp.r + mid.g + mid.b + texture(paintBroad,uv).a;
    if (pc.displayControl.y > 1.5)
    c = energy>0.000001 ? dot(sharp,vec4(0,4,10,24))/(24.0*energy) : 0.0;
    else if (pc.displayControl.y > 0.5) c = energy;
    result = vec4(vec3(c*pc.displayControl.x),0);
}
```

