# Field Document: visionos_start

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. KotlinJS → visionOS

<!-- field-box id="agent_visionos_start_root" index="0" -->

```edn [field-box id="agent_visionos_start_root" role="properties"]
{"id" "agent_visionos_start_root" "index" 0 "name" "KotlinJS → visionOS" "parents" ["root"] "children" ["agent_visionos_start"] "properties" {"children" #{#afield/box "agent_visionos_start"} "parents" #{#afield/box "root"} "name" "KotlinJS → visionOS" "_tombstone" false "frame" #afield/rect [50.0 50.0 440.0 310.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" []}
```

## 2. visionOS Starter

<!-- field-box id="agent_visionos_start" index="1" -->

### Narrative

From `code`

{{ visionmetal uniforms=phase,amount,tint }}
Open Live Metal in Field4Vision. Launch again after editing to hot-reload.

```edn [field-box id="agent_visionos_start" role="properties"]
{"id" "agent_visionos_start" "index" 1 "name" "visionOS Starter" "parents" ["root" "agent_visionos_start_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_visionos_start_root"} "name" "visionOS Starter" "_tombstone" false "frame" #afield/rect [90.0 100.0 360.0 220.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_visionos_start_code_.kt" "lines" 16 "characters" 386 "sha256" "7dc622858dae2ee53749c6071c3903fd559056ccd5b327c40e90be82d4377093"} {"name" "fragment" "language" "glsl" "path" "agent_visionos_start_fragment_.glsl" "lines" 36 "characters" 1238 "sha256" "97889bbc9725a260ff4be786dffc6259f05bf5c382ec484948cfcfbcf93ce26a"}]}
```

```kotlin [field-property box="agent_visionos_start" name="code" path="agent_visionos_start_code_.kt"]
// {{ visionmetal uniforms=phase,amount,tint }}
// Open Live Metal in Field4Vision. Launch again after editing to hot-reload.
onStart {
    uniform("amount", 0.025)
    uniform("tint", 0.1, 0.8, 1.0, 0.35)

    onFrame { frame ->
        uniform("phase", frame.time * 3.0)
    }

    onStop {
        println("visionOS starter stopped")
    }

    println("visionOS starter running")
}
```

```glsl [field-property box="agent_visionos_start" name="fragment" path="agent_visionos_start_fragment_.glsl"]
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float phase = fieldUniform(frame, 0).x;
    float amount = fieldUniform(frame, 1).x;
    float3 centre = float3(0.45, 0.0, -1.2);
    float3 p = v.position.xyz
        + v.normal.xyz * sin(phase + v.position.y * 18.0) * amount
        - centre;
    float3 n = v.normal.xyz;

    float angle = phase * 0.18 + 0.55;
    float c = cos(angle), s = sin(angle);
    p.xz = float2(c * p.x + s * p.z, -s * p.x + c * p.z);
    n.xz = float2(c * n.x + s * n.z, -s * n.x + c * n.z);

    float4 world = frame.modelMatrix * float4(p + centre, 1.0);
    FieldVarying out;
    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
    out.worldPosition = world.xyz;
    out.normal = n;
    out.color = v.color;
    return out;
}

fragment half4 field_fragment(
    FieldVarying in [[stage_in]],
    constant FieldFrame &frame [[buffer(1)]])
{
    float4 tint = fieldUniform(frame, 2);
    float3 rgb = mix(in.color.rgb, tint.rgb, tint.a);
    return half4(half3(rgb), half(in.color.a));
}
```

