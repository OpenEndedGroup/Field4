# Field Document: shadertrinket

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 9 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ShaderTrinket window + renderer

<!-- field-box id="shadertrinket_renderer" index="0" -->

```edn [field-box id="shadertrinket_renderer" role="properties"]
{"id" "shadertrinket_renderer" "index" 0 "name" "ShaderTrinket window + renderer" "parents" ["root"] "children" ["shadertrinket_lead" "shadertrinket_classic_plasma" "shadertrinket_star_tunnel" "shadertrinket_mandelbrot_glow" "shadertrinket_sdf_sphere_trace" "shadertrinket_camera" "shadertrinket_live_stream" "shadertrinket_publish"] "properties" {"children" #{#afield/box "shadertrinket_lead" #afield/box "shadertrinket_classic_plasma" #afield/box "shadertrinket_star_tunnel" #afield/box "shadertrinket_mandelbrot_glow" #afield/box "shadertrinket_sdf_sphere_trace" #afield/box "shadertrinket_camera" #afield/box "shadertrinket_live_stream" #afield/box "shadertrinket_publish"} "parents" #{#afield/box "root"} "name" "ShaderTrinket window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_renderer_code_.kt" "lines" 15 "characters" 331 "sha256" "6aa61fa323b4b1ecf3374d2b23b7bab8eb123c23549923b75d130270573e5288"} {"name" "notes" "language" "markdown" "path" "shadertrinket_renderer_notes_.md" "lines" 2 "characters" 172 "sha256" "58788c81852288d75a14cd1fa34c6880f64f814403d32699b40091d6e290502e"}]}
```

```kotlin [field-property box="shadertrinket_renderer" name="code" path="shadertrinket_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = false

once.renderer {
    window = Window("ShaderTrinket", initialDimensions = Rect(80.0, 80.0, 960.0, 540.0))
    Renderer(window)
}

println("ShaderTrinket window ready")
start(renderer::run)
```

```markdown [field-property box="shadertrinket_renderer" name="notes" path="shadertrinket_renderer_notes_.md"]
The renderer and its 960 by 540 window. Every ShaderTrinket box sits beneath it, and the window
shows their shared canvas, letterboxed if you resize it. Launch this first.
```

## 2. Star Tunnel

<!-- field-box id="shadertrinket_star_tunnel" index="1" -->

### Narrative

From `code`

Star Tunnel: launch to show it in the window. mainImage is in this box's `fragment`.

```edn [field-box id="shadertrinket_star_tunnel" role="properties"]
{"id" "shadertrinket_star_tunnel" "index" 1 "name" "Star Tunnel" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "Star Tunnel" "_tombstone" false "frame" #afield/rect [380.0 360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_star_tunnel_code_.kt" "lines" 5 "characters" 163 "sha256" "780300bb197f8396544f9a8eac0169bc6313e77c030628388e96c177e8d2877e"} {"name" "fragment" "language" "glsl" "path" "shadertrinket_star_tunnel_fragment_.glsl" "lines" 33 "characters" 1107 "sha256" "853ed8e7932984ce87e569c82cc5eeee58e0a30d7902e277bfd9807aed183c08"} {"name" "notes" "language" "markdown" "path" "shadertrinket_star_tunnel_notes_.md" "lines" 2 "characters" 170 "sha256" "d682e8e1be03fe82931c6a357d8979001c7ee78983ed98eca542027995d132df"}]}
```

```kotlin [field-property box="shadertrinket_star_tunnel" name="code" path="shadertrinket_star_tunnel_code_.kt"]
// Star Tunnel: launch to show it in the window. mainImage is in this box's `fragment`.
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
}
```

```glsl [field-property box="shadertrinket_star_tunnel" name="fragment" path="shadertrinket_star_tunnel_fragment_.glsl"]
{{ shadertoy.image }}

float hash(vec2 p) {
  p = fract(p * vec2(234.34, 435.35));
  p += dot(p, p + 34.23);
  return fract(p.x * p.y);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float t = iTime * 0.55;
  float a = atan(uv.y, uv.x);
  float r = length(uv);
  vec3 col = vec3(0.002, 0.004, 0.009);

  for (int i = 0; i < 48; i++) {
    float fi = float(i);
    float lane = floor(fi / 6.0);
    float spoke = mod(fi, 6.0);
    float z = fract(t + fi * 0.071);
    float rr = mix(1.55, 0.05, z);
    float aa = spoke * 1.047 + lane * 0.38 + t * 1.7 + sin(lane) * 0.5;
    vec2 pos = vec2(cos(aa), sin(aa)) * rr;
    float star = pow(max(0.0, 1.0 - length(uv - pos) / (0.015 + z * 0.035)), 8.0);
    vec3 tint = 0.55 + 0.45 * cos(vec3(0.2, 1.8, 4.1) + fi * 0.7);
    col += star * tint * (0.25 + z * 2.2);
  }

  float warp = sin(a * 9.0 - r * 7.0 + t * 8.0);
  col += vec3(0.03, 0.08, 0.15) * smoothstep(0.95, 0.0, abs(warp)) * smoothstep(1.5, 0.2, r);
  col *= 1.0 - smoothstep(1.15, 1.75, r);
  fragColor = vec4(col, 1.0);
}
```

```markdown [field-property box="shadertrinket_star_tunnel" name="notes" path="shadertrinket_star_tunnel_notes_.md"]
Star Tunnel: 48 stars in a loop, each placed by its index and the time, so a for loop draws many
things at once. Try changing 48, or the 0.071 that spaces them in depth.
```

## 3. Mandelbrot Glow

<!-- field-box id="shadertrinket_mandelbrot_glow" index="2" -->

### Narrative

From `code`

Mandelbrot Glow: launch to show it in the window. mainImage is in this box's `fragment`.

```edn [field-box id="shadertrinket_mandelbrot_glow" role="properties"]
{"id" "shadertrinket_mandelbrot_glow" "index" 2 "name" "Mandelbrot Glow" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "Mandelbrot Glow" "_tombstone" false "frame" #afield/rect [720.0 360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_mandelbrot_glow_code_.kt" "lines" 5 "characters" 167 "sha256" "36bddb093c204ab8fa5bf96a3cc63949a00986cd5fa30f4029eb56ae9d565c4e"} {"name" "fragment" "language" "glsl" "path" "shadertrinket_mandelbrot_glow_fragment_.glsl" "lines" 26 "characters" 785 "sha256" "eb67657d01d59c56a5c16249dfc7f890f4a99fc65e574ccf51f4a15ea50eb860"} {"name" "notes" "language" "markdown" "path" "shadertrinket_mandelbrot_glow_notes_.md" "lines" 2 "characters" 185 "sha256" "1cceaf8eab26b61ee40bccbd53a3cab8a68b4324bf344eaaa8e20087ae7e4b26"}]}
```

```kotlin [field-property box="shadertrinket_mandelbrot_glow" name="code" path="shadertrinket_mandelbrot_glow_code_.kt"]
// Mandelbrot Glow: launch to show it in the window. mainImage is in this box's `fragment`.
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
}
```

```glsl [field-property box="shadertrinket_mandelbrot_glow" name="fragment" path="shadertrinket_mandelbrot_glow_fragment_.glsl"]
{{ shadertoy.image }}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float zoom = 0.72 + 0.18 * sin(iTime * 0.18);
  vec2 c = uv * zoom + vec2(-0.68, 0.0);
  c += vec2(0.08 * sin(iTime * 0.21), 0.05 * cos(iTime * 0.17));
  vec2 z = vec2(0.0);
  float iter = 0.0;
  float trap = 10.0;

  for (int i = 0; i < 96; i++) {
    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
    trap = min(trap, abs(z.x * z.y));
    if (dot(z, z) > 16.0) {
      iter = float(i);
      break;
    }
  }

  float m = iter / 96.0;
  vec3 col = 0.5 + 0.5 * cos(vec3(0.4, 2.2, 4.2) + m * 8.0 + iTime * 0.25);
  col *= smoothstep(0.0, 0.04, m);
  col += exp(-trap * 45.0) * vec3(0.25, 0.9, 1.15);
  fragColor = vec4(col, 1.0);
}
```

```markdown [field-property box="shadertrinket_mandelbrot_glow" name="notes" path="shadertrinket_mandelbrot_glow_notes_.md"]
Mandelbrot Glow: z = z*z + c iterated 96 times, coloured by how fast it escapes, plus an orbit
trap (the closest z came to the axes) for the glow. The zoom and centre drift with iTime.
```

## 4. SDF Sphere Trace

<!-- field-box id="shadertrinket_sdf_sphere_trace" index="3" -->

### Narrative

From `code`

SDF Sphere Trace: launch to show it in the window. mainImage is in this box's `fragment`.

```edn [field-box id="shadertrinket_sdf_sphere_trace" role="properties"]
{"id" "shadertrinket_sdf_sphere_trace" "index" 3 "name" "SDF Sphere Trace" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "SDF Sphere Trace" "_tombstone" false "frame" #afield/rect [1060.0 360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_sdf_sphere_trace_code_.kt" "lines" 5 "characters" 168 "sha256" "f06b142cb8f827a442d07eb2c89a2412d20faa8600944cd487a2749fb3e02533"} {"name" "fragment" "language" "glsl" "path" "shadertrinket_sdf_sphere_trace_fragment_.glsl" "lines" 53 "characters" 1467 "sha256" "1f64765df6608ae5171b6221d244a64c19e70b39ec53205ca52c918f48969d4d"} {"name" "notes" "language" "markdown" "path" "shadertrinket_sdf_sphere_trace_notes_.md" "lines" 3 "characters" 221 "sha256" "50c8febd207c3a404c834af83765e91ac972c58f7e16c304ef4f99db8dda7b19"}]}
```

```kotlin [field-property box="shadertrinket_sdf_sphere_trace" name="code" path="shadertrinket_sdf_sphere_trace_code_.kt"]
// SDF Sphere Trace: launch to show it in the window. mainImage is in this box's `fragment`.
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
}
```

```glsl [field-property box="shadertrinket_sdf_sphere_trace" name="fragment" path="shadertrinket_sdf_sphere_trace_fragment_.glsl"]
{{ shadertoy.image }}

float sdSphere(vec3 p, float r) {
  return length(p) - r;
}

float mapScene(vec3 p) {
  float ca = cos(iTime * 0.7);
  float sa = sin(iTime * 0.7);
  p.xz = mat2(ca, -sa, sa, ca) * p.xz;
  float s = sdSphere(p, 0.82);
  float rings = abs(length(p.xz) - 0.98) - 0.025;
  float band = abs(p.y + 0.08 * sin(atan(p.z, p.x) * 5.0 + iTime * 2.0)) - 0.035;
  return min(s, max(rings, band));
}

vec3 calcNormal(vec3 p) {
  vec2 e = vec2(0.001, 0.0);
  return normalize(vec3(
    mapScene(p + e.xyy) - mapScene(p - e.xyy),
    mapScene(p + e.yxy) - mapScene(p - e.yxy),
    mapScene(p + e.yyx) - mapScene(p - e.yyx)
  ));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  vec3 ro = vec3(0.0, 0.0, 3.1);
  vec3 rd = normalize(vec3(uv, -1.75));
  vec3 col = vec3(0.005, 0.007, 0.01);
  float t = 0.0;
  float glow = 0.0;

  for (int i = 0; i < 96; i++) {
    vec3 p = ro + rd * t;
    float d = mapScene(p);
    glow += 0.018 / (0.03 + abs(d));
    if (d < 0.001 || t > 8.0) break;
    t += d * 0.78;
  }

  if (t < 8.0) {
    vec3 p = ro + rd * t;
    vec3 n = calcNormal(p);
    vec3 l = normalize(vec3(-0.5, 0.7, 0.9));
    float diff = max(0.0, dot(n, l));
    float fres = pow(1.0 + dot(rd, n), 4.0);
    col = vec3(0.08, 0.16, 0.20) + diff * vec3(0.34, 0.88, 0.76) + fres * vec3(0.9, 0.35, 1.0);
  }

  col += glow * vec3(0.03, 0.22, 0.45);
  fragColor = vec4(col, 1.0);
}
```

```markdown [field-property box="shadertrinket_sdf_sphere_trace" name="notes" path="shadertrinket_sdf_sphere_trace_notes_.md"]
SDF Sphere Trace: ray marching. mapScene returns the distance to the nearest surface, the loop
steps along each ray by that distance, and calcNormal reads the surface's slope from it. Change
mapScene to change the shape.
```

## 5. Classic Plasma

<!-- field-box id="shadertrinket_classic_plasma" index="4" -->

### Narrative

From `code`

Classic Plasma: launch to show it in the window. mainImage is in this box's `fragment`.

```edn [field-box id="shadertrinket_classic_plasma" role="properties"]
{"id" "shadertrinket_classic_plasma" "index" 4 "name" "Classic Plasma" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "Classic Plasma" "_tombstone" false "frame" #afield/rect [40.0 360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_classic_plasma_code_.kt" "lines" 5 "characters" 166 "sha256" "2b8a6feba7e8b877d05c669152d16eedaf66063524370e459034a8516e6d42ad"} {"name" "fragment" "language" "glsl" "path" "shadertrinket_classic_plasma_fragment_.glsl" "lines" 17 "characters" 624 "sha256" "1aac0fbe32904b28ac252d04f3522b8c1dc8bafd15d7824b25ae907b3f78de0e"} {"name" "notes" "language" "markdown" "path" "shadertrinket_classic_plasma_notes_.md" "lines" 2 "characters" 165 "sha256" "788e9737fd1a9d00782f70cb7c76115f42e9915f756d71a0b858ad85f0c4df74"}]}
```

```kotlin [field-property box="shadertrinket_classic_plasma" name="code" path="shadertrinket_classic_plasma_code_.kt"]
// Classic Plasma: launch to show it in the window. mainImage is in this box's `fragment`.
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
}
```

```glsl [field-property box="shadertrinket_classic_plasma" name="fragment" path="shadertrinket_classic_plasma_fragment_.glsl"]
{{ shadertoy.image }}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = fragCoord / iResolution.xy;
  vec2 p = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float t = iTime * 0.75;

  float v = sin(p.x * 7.0 + t);
  v += sin((p.y + sin(t * 0.7)) * 8.0);
  v += sin((p.x + p.y) * 5.0 + t * 1.7);
  v += sin(length(p + vec2(sin(t), cos(t * 0.6))) * 11.0 - t * 2.0);

  vec3 col = 0.5 + 0.5 * cos(vec3(0.0, 1.7, 3.4) + v + t);
  col *= 0.65 + 0.35 * smoothstep(1.2, 0.0, length(p));
  col += pow(max(0.0, 1.0 - length(uv - 0.5) * 1.35), 4.0) * vec3(0.35, 0.48, 0.65);
  fragColor = vec4(col, 1.0);
}
```

```markdown [field-property box="shadertrinket_classic_plasma" name="notes" path="shadertrinket_classic_plasma_notes_.md"]
Classic Plasma: four sine waves summed and pushed through a cosine palette. The palette line,
0.5 + 0.5 * cos(vec3(0.0, 1.7, 3.4) + v + t), is the one to play with.
```

## 6. Publish (share the canvas)

<!-- field-box id="shadertrinket_publish" index="5" -->

### Narrative

From `code`

Publish: the canvas, whichever shader is showing, streamed to the class as @you/field-shader
(your aaaframe login, or this computer's user name on Field's own relay). A browser ShaderTrinket
receives it by that name, and so does the Live Stream box. Needs the Field Electron app.

```edn [field-box id="shadertrinket_publish" role="properties"]
{"id" "shadertrinket_publish" "index" 5 "name" "Publish (share the canvas)" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "Publish (share the canvas)" "_tombstone" false "frame" #afield/rect [1060.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_publish_code_.kt" "lines" 4 "characters" 339 "sha256" "a218f9de74cbfd54a5be131ecf91fabcfd5055222dd9fb3f59aedb349694d220"} {"name" "notes" "language" "markdown" "path" "shadertrinket_publish_notes_.md" "lines" 4 "characters" 366 "sha256" "756b5cb50697e97da8ec4bbac6295a18e34d9bcf5ff3a0463abe379f811f706d"}]}
```

```kotlin [field-property box="shadertrinket_publish" name="code" path="shadertrinket_publish_code_.kt"]
// Publish: the canvas, whichever shader is showing, streamed to the class as @you/field-shader
// (your aaaframe login, or this computer's user name on Field's own relay). A browser ShaderTrinket
// receives it by that name, and so does the Live Stream box. Needs the Field Electron app.
val sink = publishShadertoyCanvas("field-shader")
```

```markdown [field-property box="shadertrinket_publish" name="notes" path="shadertrinket_publish_notes_.md"]
Publish, the sending half of the jam: whatever the canvas shows is streamed as @you/field-shader,
your aaaframe login or this computer's user name on Field's own relay. Classmates receive it by
that name in a browser ShaderTrinket or in their own Live Stream box. Run
WebRTCNetwork.join("https://<your aaaframe>") once to join a class. Needs the Field Electron app.
```

## 7. Flat Rings (start here)

<!-- field-box id="shadertrinket_lead" index="6" -->

### Narrative

From `code`

ShaderTrinket in Field. This box's `fragment` property is a ShaderToy Image pass: mainImage
gets fragCoord in pixels (y up) and the ShaderToy uniforms (iResolution, iTime, iMouse, iDate,
iChannel0..3). Edit it and press alt-R to recompile; a mistake is marked on its own line.

Every shader box below draws into the same canvas, so launching one replaces the picture, the
way choosing a preset does. Launch this box again to come back to Flat Rings.

```edn [field-box id="shadertrinket_lead" role="properties"]
{"id" "shadertrinket_lead" "index" 6 "name" "Flat Rings (start here)" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "Flat Rings (start here)" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_lead_code_.kt" "lines" 8 "characters" 538 "sha256" "f1924b6a2e49076f2c26fd80ab3ea900c274253dcd87b4eea2cd65345b7d7499"} {"name" "fragment" "language" "glsl" "path" "shadertrinket_lead_fragment_.glsl" "lines" 27 "characters" 922 "sha256" "10c84486b43d83fe18324b653bf1b06524125d61e478b2af74430dab247d46d8"} {"name" "notes" "language" "markdown" "path" "shadertrinket_lead_notes_.md" "lines" 20 "characters" 1409 "sha256" "b783dc259d96f0c44d6a6da70da0c0102cb06c7932c49d30e44ef06fd20ee3f3"}]}
```

```kotlin [field-property box="shadertrinket_lead" name="code" path="shadertrinket_lead_code_.kt"]
// ShaderTrinket in Field. This box's `fragment` property is a ShaderToy Image pass: mainImage
// gets fragCoord in pixels (y up) and the ShaderToy uniforms (iResolution, iTime, iMouse, iDate,
// iChannel0..3). Edit it and press alt-R to recompile; a mistake is marked on its own line.
//
// Every shader box below draws into the same canvas, so launching one replaces the picture, the
// way choosing a preset does. Launch this box again to come back to Flat Rings.
val canvas = shadertoyCanvas()
canvas += shadertoyHere { +canvas.rtt }
```

```glsl [field-property box="shadertrinket_lead" name="fragment" path="shadertrinket_lead_fragment_.glsl"]
{{ shadertoy.image }}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float t = iTime * 0.7;
  vec3 col = vec3(0.015, 0.018, 0.018);

  float grid = mod(floor((uv.x + 2.0) * 9.0) + floor((uv.y + 2.0) * 9.0), 2.0);
  if (grid < 0.5) col = vec3(0.025, 0.03, 0.03);

  for (int i = 0; i < 8; i++) {
    float fi = float(i);
    vec2 c = vec2(cos(t + fi * 1.35), sin(t * 0.8 + fi * 1.1)) * 0.42;
    float r = 0.17 + 0.035 * step(0.5, mod(fi, 2.0));
    float ring = 1.0 - step(0.018, abs(length(uv - c) - r));
    float dotMask = 1.0 - step(0.035, length(uv - c));

    if (ring > 0.5) {
      if (mod(fi, 3.0) < 1.0) col = vec3(0.16, 0.72, 0.62);
      else if (mod(fi, 3.0) < 2.0) col = vec3(0.95, 0.74, 0.25);
      else col = vec3(0.35, 0.50, 0.95);
    }
    if (dotMask > 0.5) col = vec3(0.92, 0.96, 0.90);
  }

  fragColor = vec4(col, 1.0);
}
```

```markdown [field-property box="shadertrinket_lead" name="notes" path="shadertrinket_lead_notes_.md"]
ShaderTrinket, in Field. Launch the renderer above, then this box: the window shows Flat Rings,
ShaderTrinket's default program.

The program is this box's fragment property, a ShaderToy Image pass: you write
void mainImage(out vec4 fragColor, in vec2 fragCoord), where fragCoord is the pixel (y up, as in
ShaderToy) and fragColor the colour you want seen there. The ShaderToy uniforms are all bound:
iResolution (the canvas size), iTime, iTimeDelta, iFrame, iFrameRate, iDate, iMouse (the window
cursor in canvas pixels, zw the click while the button is down) and iChannel0..3 with
iChannelResolution. The {{ shadertoy.image }} line at the top brings them in; leave it there.

Edit the fragment and press alt-R to recompile. A mistake is marked on its own line and the last
good program keeps running. ShaderToy code pasted from the web works as is.

Every shader box here draws into one canvas, shadertoyCanvas(), so launching a box replaces the
picture, the way ShaderTrinket's preset menu does; launch this one again to come back. To read a
texture, add textures("iChannel0" to t) inside the shadertoyHere block: the camera (see the
Camera Input Demo), another canvas (shadertoyCanvas(name = "...", inWindow = false).rtt), or any
Field texture. Channels come out the right way up and in ShaderToy's colours.

Ported from aaaframe's madp/shadertrinket.html; dev_docs/madp/shadertrinket.md has the mapping.
```

## 8. Live Stream Input Demo (receive)

<!-- field-box id="shadertrinket_live_stream" index="7" -->

```edn [field-box id="shadertrinket_live_stream" role="properties"]
{"id" "shadertrinket_live_stream" "index" 7 "name" "Live Stream Input Demo (receive)" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "Live Stream Input Demo (receive)" "_tombstone" false "frame" #afield/rect [380.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_live_stream_code_.kt" "lines" 19 "characters" 804 "sha256" "e11807448027ce96144d9a56ea80543dc3bcbff8a9e2a0f7903bd30d217f7a1c"} {"name" "fragment" "language" "glsl" "path" "shadertrinket_live_stream_fragment_.glsl" "lines" 30 "characters" 1198 "sha256" "ef41c15d1e2be57910d97fe6d0808b8a2d3c362c77a59786e5a9eab56d143d72"} {"name" "notes" "language" "markdown" "path" "shadertrinket_live_stream_notes_.md" "lines" 4 "characters" 321 "sha256" "3ea0ebbcfde15952de59b7d5285d3fc7fe62329cb87d39cc3391e6db549090b1"}]}
```

```kotlin [field-property box="shadertrinket_live_stream" name="code" path="shadertrinket_live_stream_code_.kt"]
import afield.media.webrtc.webrtcPublishers
import afield.media.webrtc.webrtcSource

// Live Stream Input Demo: iChannel2 is a classmate's stream (ShaderTrinket's Receive into iChannel2).
// Click a name in the list under this box. Until you do, it shows the first publisher on the
// network; on Field's own relay that is this document's Publish box.
val publishers = webrtcPublishers()
val stream = webrtcSource(publishers.names.firstOrNull() ?: "nobody", 960, 540)
publishers.onChange { names ->
    if (stream.target == "nobody" && names.isNotEmpty()) stream.watch(names.first())
}
publishers.onPick { name -> stream.watch(name) }
display("publishers", publishers.html { stream.target })

val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
    textures("iChannel2" to stream)
}
```

```glsl [field-property box="shadertrinket_live_stream" name="fragment" path="shadertrinket_live_stream_fragment_.glsl"]
{{ shadertoy.image }}

// Live Stream Input Demo: iChannel2 is the stream picked in this box's list (ShaderTrinket's
// Receive). The Publish box shares this canvas as @you/field-shader for the class to pick.

float hash(vec2 p) {
  p = fract(p * vec2(234.34, 435.35));
  p += dot(p, p + 34.23);
  return fract(p.x * p.y);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = fragCoord / iResolution.xy;
  vec2 p = (2.0 * fragCoord - iResolution.xy) / iResolution.y;

  vec3 remote = texture(iChannel2, uv).rgb;
  float signal = smoothstep(0.01, 0.16, dot(remote, vec3(0.333)));

  float lane = floor(uv.y * 12.0);
  float bars = step(0.82, hash(vec2(lane, floor(iTime * 5.0))));
  float sweep = smoothstep(0.02, 0.0, abs(fract(uv.x * 5.0 - iTime * 0.35) - 0.5));
  vec3 fallback = mix(vec3(0.015, 0.018, 0.028), vec3(0.08, 0.36, 0.40), bars);
  fallback += sweep * vec3(0.9, 0.62, 0.18) * smoothstep(1.35, 0.0, length(p));

  // The procedural field remains visible until a remote stream arrives.
  vec3 color = mix(fallback, remote, signal);
  color += signal * vec3(0.18, 0.75, 0.64) * sweep;
  color *= 0.75 + 0.25 * sin(iTime + uv.x * 9.0);
  fragColor = vec4(color, 1.0);
}
```

```markdown [field-property box="shadertrinket_live_stream" name="notes" path="shadertrinket_live_stream_notes_.md"]
Live Stream Input Demo, the receiving half of the classroom jam: a list of everyone publishing on
the network appears under this box, and the one you click arrives as iChannel2, from a browser
ShaderTrinket or another Field. Until a stream arrives the shader shows its own procedural field.
Needs the Field Electron app.
```

## 9. Camera Input Demo

<!-- field-box id="shadertrinket_camera" index="8" -->

### Narrative

From `code`

Camera Input Demo: launch to show it in the window. mainImage is in this box's `fragment`.

```edn [field-box id="shadertrinket_camera" role="properties"]
{"id" "shadertrinket_camera" "index" 8 "name" "Camera Input Demo" "parents" ["root" "shadertrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "shadertrinket_renderer"} "name" "Camera Input Demo" "_tombstone" false "frame" #afield/rect [40.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shadertrinket_camera_code_.kt" "lines" 6 "characters" 207 "sha256" "051b81079a5fcec105de568de0f6d103979aa4c7bc515be1b9326acacf4fe293"} {"name" "fragment" "language" "glsl" "path" "shadertrinket_camera_fragment_.glsl" "lines" 25 "characters" 1108 "sha256" "ecc1c2414e9dcfadae522eb91175800ec3d64158cf54102e84f686e21cefda09"} {"name" "notes" "language" "markdown" "path" "shadertrinket_camera_notes_.md" "lines" 3 "characters" 280 "sha256" "e5a40fcb8c486ff0a1b45871e294a488d923ecef0d72634f0ba0e925c39a2f0b"}]}
```

```kotlin [field-property box="shadertrinket_camera" name="code" path="shadertrinket_camera_code_.kt"]
// Camera Input Demo: launch to show it in the window. mainImage is in this box's `fragment`.
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
    textures("iChannel0" to webcam())
}
```

```glsl [field-property box="shadertrinket_camera" name="fragment" path="shadertrinket_camera_fragment_.glsl"]
{{ shadertoy.image }}

// Camera Input Demo: iChannel0 is the camera (ShaderTrinket's Camera button is the
// textures("iChannel0" to webcam()) line in this box's code). With no camera it samples black and
// you get a quiet prompt-like field.

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = fragCoord / iResolution.xy;

  // Mirror the camera so it behaves like a familiar preview.
  vec2 cameraUv = vec2(1.0 - uv.x, uv.y);
  vec3 camera = texture(iChannel0, cameraUv).rgb;
  float luma = dot(camera, vec3(0.299, 0.587, 0.114));

  vec2 p = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float rings = 0.5 + 0.5 * sin(length(p) * 28.0 - iTime * 4.0 + luma * 5.0);
  float scan = smoothstep(0.92, 1.0, sin(uv.y * iResolution.y * 0.75 + iTime * 12.0));
  vec3 tint = mix(vec3(0.02, 0.16, 0.18), vec3(0.94, 0.72, 0.22), rings);

  // If the camera is off, iChannel0 samples black and you get a quiet prompt-like field.
  vec3 color = mix(camera, tint, 0.34);
  color += scan * vec3(0.16, 0.55, 0.52) * (0.25 + luma);
  color *= 0.78 + 0.55 * luma;
  fragColor = vec4(color, 1.0);
}
```

```markdown [field-property box="shadertrinket_camera" name="notes" path="shadertrinket_camera_notes_.md"]
Camera Input Demo: iChannel0 is the camera, bound by textures("iChannel0" to webcam()) in this
box's code. webcam() opens the camera once and keeps it open across runs. The first time, macOS asks
for camera permission; with no camera, the channel is black and the box prints why.
```

