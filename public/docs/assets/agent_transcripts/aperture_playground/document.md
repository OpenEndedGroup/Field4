# Field Document: aperture_playground

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 1. Renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "1. Renderer" "parents" ["root"] "children" ["aperture_sprites" "aperture_source"] "properties" {"children" #{#afield/box "aperture_sprites" #afield/box "aperture_source"} "parents" #{#afield/box "root"} "name" "1. Renderer" "_tombstone" false "frame" #afield/rect [60.0 70.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 9 "characters" 264 "sha256" "ea11f6823f90b4c8b44bac2ed96c69337852e784428b110d19fb0a2d4adb3b0d"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field — aperture playground", initialDimensions = Rect(80.0,80.0,960.0,720.0))
    Renderer(window)
}
start(renderer::run)
```

## 2. 2. Source RTT — lines and points

<!-- field-box id="aperture_source" index="1" -->

```edn [field-box id="aperture_source" role="properties"]
{"id" "aperture_source" "index" 1 "name" "2. Source RTT — lines and points" "parents" ["root" "agent_graphics_context"] "children" ["aperture_half"] "properties" {"children" #{#afield/box "aperture_half"} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "2. Source RTT — lines and points" "_tombstone" false "frame" #afield/rect [460.0 70.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_source_code_.kt" "lines" 43 "characters" 1762 "sha256" "fcc368ffb48e46caeccacb9dd1fef3191062a5df43ffdb692b06b13ecdbc88a2"} {"name" "vertex" "language" "glsl" "path" "aperture_source_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_source_fragment_.glsl" "lines" 23 "characters" 919 "sha256" "6b57e0d80a57e72bd70425960fa676c688f03844d0204ffd68116390bc5b180d"}]}
```

```kotlin [field-property box="aperture_source" name="code" path="aperture_source_code_.kt"]
import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

// Full-resolution RGBA16F source. RGBA hold light at radii 0, 4, 10, 24 px.
// Alpha is the fourth light band, so every intermediate filter must use blend=false.
// Re-run the downstream boxes after changing the dimensions.
prop<RenderToTexture>(paintSource)
thisBox.paintSource = RenderToTexture {
    width = window.width
    height = window.height
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paint-source"
}
thisBox.paintSource.setDefaultSampler(anisotropyEnable = false)
renderer.renderList.preTargets["paint-source"] = thisBox.paintSource
prop<afield.stdlib.Vec4>(apertureControl)
thisBox.apertureControl = vec(1.5,0,0,0)
println("paint source RTT ready: ${thisBox.paintSource.width}x${thisBox.paintSource.height}")

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
// x = defocus radius in framebuffer pixels, y = energy, z = sprite radius, w = guide mix.
mesh.look = { vec(24.0,1.0,0.0,0.0) }
mesh.size = { vec(paintSource.width,paintSource.height,0,0) }

// Nest the two editable reduction helpers without hiding their shaders.
thisBox.frame = Rect(360.0,70.0,260.0,270.0)
thisBox.inside.boxes.getValue("aperture_half").nestedIn = thisBox
thisBox.inside.boxes.getValue("aperture_half").frame = Rect(15.0,70.0,240.0,55.0)
thisBox.inside.boxes.getValue("aperture_quarter").nestedIn = thisBox
thisBox.inside.boxes.getValue("aperture_quarter").frame = Rect(15.0,150.0,240.0,55.0)
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
layout(push_constant) uniform Source { vec4 look; vec4 size; } pc;
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
{"id" "aperture_half" "index" 2 "name" "3. Reduce ½" "parents" ["root" "aperture_source"] "children" ["aperture_quarter" "aperture_medium"] "properties" {"children" #{#afield/box "aperture_quarter" #afield/box "aperture_medium"} "parents" #{#afield/box "root" #afield/box "aperture_source"} "name" "3. Reduce ½" "_tombstone" false "frame" #afield/rect [460.0 260.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_half_code_.kt" "lines" 29 "characters" 990 "sha256" "36b9a7fa7ce9e65294c2c12cb13142aca98144600156938d1ecd0e232078ac26"} {"name" "vertex" "language" "glsl" "path" "aperture_half_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_half_fragment_.glsl" "lines" 11 "characters" 462 "sha256" "19cb748c5dc55457a71e36933a6ab62bd0892e455180917b2b060f61f2a884ef"}]}
```

```kotlin [field-property box="aperture_half" name="code" path="aperture_half_code_.kt"]
import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
prop<RenderToTexture>(paintHalf) = RenderToTexture {
    width = (source.width/2).coerceAtLeast(1)
    height = (source.height/2).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paintHalf"
}
paintHalf.setDefaultSampler(anisotropyEnable = false)
val input = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
var module = moduleHere {
    textures("paintSource" to input)
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
{"id" "aperture_quarter" "index" 3 "name" "4. Reduce ¼" "parents" ["root" "aperture_half"] "children" ["aperture_broad"] "properties" {"children" #{#afield/box "aperture_broad"} "parents" #{#afield/box "root" #afield/box "aperture_half"} "name" "4. Reduce ¼" "_tombstone" false "frame" #afield/rect [460.0 440.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_quarter_code_.kt" "lines" 29 "characters" 1007 "sha256" "c31b9dfd89670e35005fb15b258c92901e495e32cba9363d9e876fd7b1ab532b"} {"name" "vertex" "language" "glsl" "path" "aperture_quarter_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_quarter_fragment_.glsl" "lines" 11 "characters" 452 "sha256" "d0b7081138e7f149297dd0dd434b08e573a0002a4d934b3e6eb6e7ff68661d7e"}]}
```

```kotlin [field-property box="aperture_quarter" name="code" path="aperture_quarter_code_.kt"]
import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
prop<RenderToTexture>(paintQuarter) = RenderToTexture {
    width = (source.width/4).coerceAtLeast(1)
    height = (source.height/4).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paintQuarter"
}
paintQuarter.setDefaultSampler(anisotropyEnable = false)
val input = fromContextWithNameOrNull<RenderToTexture>("paintHalf")!!
var module = moduleHere {
    textures("paintHalf" to input)
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
{"id" "aperture_sprites" "index" 4 "name" "8. Analytic point sprites" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "8. Analytic point sprites" "_tombstone" false "frame" #afield/rect [1320.0 440.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_sprites_code_.kt" "lines" 15 "characters" 553 "sha256" "809ab182e52f70b0009219e94de6e0bd9bbfa888dd695ec5f672de5f48349e0f"} {"name" "vertex" "language" "glsl" "path" "aperture_sprites_vertex_.glsl" "lines" 13 "characters" 559 "sha256" "5969dbe19a5f47692e6b507bfbafe9eca155a0be5d1f1ecc094e298bbe947004"} {"name" "fragment" "language" "glsl" "path" "aperture_sprites_fragment_.glsl" "lines" 29 "characters" 1435 "sha256" "fe7c255899267b868be3adcc431b6f23dece617dd51799b0c1fd80896277386b"}]}
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
mesh.size = { vec(window.width,window.height,0,0) }
// x: maximum aperture pixels; y: outline; z: intensity; w: solid fill.
mesh.look = { vec(24.0,0.0,1.0,1.0) }
renderer.renderList += "aperture-sprites" to module
```

```glsl [field-property box="aperture_sprites" name="vertex" path="aperture_sprites_vertex_.glsl"]
#version 450
layout(push_constant) uniform Sprite { vec4 size; vec4 look; } pc;
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
layout(push_constant) uniform push { vec4 size; vec4 look; } pc;
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
{"id" "aperture_broad" "index" 5 "name" "6. Aperture 24 px" "parents" ["root" "aperture_quarter"] "children" ["aperture_composite"] "properties" {"children" #{#afield/box "aperture_composite"} "parents" #{#afield/box "root" #afield/box "aperture_quarter"} "name" "6. Aperture 24 px" "_tombstone" false "frame" #afield/rect [890.0 440.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_broad_code_.kt" "lines" 32 "characters" 1109 "sha256" "08551c59785279956dc40ddc1c52c6ddbb85d0a791161e3817ea950ca2e4ac70"} {"name" "vertex" "language" "glsl" "path" "aperture_broad_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_broad_fragment_.glsl" "lines" 25 "characters" 1053 "sha256" "06de5cf20ca560005d7c729736f7b487543de1ed3b746062519a1c681aee4a60"}]}
```

```kotlin [field-property box="aperture_broad" name="code" path="aperture_broad_code_.kt"]
import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
prop<RenderToTexture>(paintBroad) = RenderToTexture {
    width = (source.width/4).coerceAtLeast(1)
    height = (source.height/4).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paintBroad"
}
paintBroad.setDefaultSampler(anisotropyEnable = false)
val input = fromContextWithNameOrNull<RenderToTexture>("paintQuarter")!!
var module = moduleHere {
    textures("paintQuarter" to input)
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
mesh.aperture = { vec(1.0,1.0,1.0,1.0) }
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
layout(push_constant) uniform Aperture { vec4 aperture; } pc;
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
{"id" "aperture_medium" "index" 6 "name" "5. Aperture 4 / 10 px" "parents" ["root" "aperture_half"] "children" ["aperture_composite"] "properties" {"children" #{#afield/box "aperture_composite"} "parents" #{#afield/box "root" #afield/box "aperture_half"} "name" "5. Aperture 4 / 10 px" "_tombstone" false "frame" #afield/rect [890.0 260.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_medium_code_.kt" "lines" 32 "characters" 1110 "sha256" "a38b6abedc0e9b1a9fa533c07c19bcba09a3b44608637d9015b39adcadd160fc"} {"name" "vertex" "language" "glsl" "path" "aperture_medium_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_medium_fragment_.glsl" "lines" 27 "characters" 1111 "sha256" "194af25ee478556c05b6762e8f7b78f59a754d60dc71310969661fec9379629b"}]}
```

```kotlin [field-property box="aperture_medium" name="code" path="aperture_medium_code_.kt"]
import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
prop<RenderToTexture>(paintMedium) = RenderToTexture {
    width = (source.width/2).coerceAtLeast(1)
    height = (source.height/2).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paintMedium"
}
paintMedium.setDefaultSampler(anisotropyEnable = false)
val input = fromContextWithNameOrNull<RenderToTexture>("paintHalf")!!
var module = moduleHere {
    textures("paintHalf" to input)
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
mesh.aperture = { vec(1.0,1.0,1.0,1.0) }
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
layout(push_constant) uniform Aperture { vec4 aperture; } pc;
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
{"id" "aperture_composite" "index" 7 "name" "7. Composite — sharp / blur / guide" "parents" ["root" "aperture_medium" "aperture_broad"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "aperture_medium" #afield/box "aperture_broad"} "name" "7. Composite — sharp / blur / guide" "_tombstone" false "frame" #afield/rect [1320.0 260.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "aperture_composite_code_.kt" "lines" 28 "characters" 1104 "sha256" "3ec3971e9698fb2dcca070d651617c7a918965547c6bf4d564d8a6e96f721c13"} {"name" "vertex" "language" "glsl" "path" "aperture_composite_vertex_.glsl" "lines" 8 "characters" 258 "sha256" "d8133a5a77fbe0d45c15e12b0ebaa5e1d811746872616def9c8b2ffc4181125a"} {"name" "fragment" "language" "glsl" "path" "aperture_composite_fragment_.glsl" "lines" 17 "characters" 729 "sha256" "af5b9b160b30ffcebf34f4724487234ba15e9dd4c0a0aeba220323e937bef9cc"}]}
```

```kotlin [field-property box="aperture_composite" name="code" path="aperture_composite_code_.kt"]
import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

// Screen composite. B and G in the paint box control this shared vector.
val controls = fromContextWithNameOrNull<afield.stdlib.Vec4>("apertureControl")!!
val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
val medium = fromContextWithNameOrNull<RenderToTexture>("paintMedium")!!
val broad = fromContextWithNameOrNull<RenderToTexture>("paintBroad")!!
var module = moduleHere {
    textures("paintSource" to source, "paintMedium" to medium, "paintBroad" to broad)
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
mesh.displayControl = { controls }
renderer.renderList += "paint-composite" to module
println("paint composite ready")

// y: 0 aperture, 1 sharp, 2 radius guide. Edit here and re-execute.
controls.set(1.0f,0.0f,0.0f,0.0f)
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
layout(push_constant) uniform Display { vec4 displayControl; } pc;
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

