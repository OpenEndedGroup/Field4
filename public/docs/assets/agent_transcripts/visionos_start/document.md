# Field Document: visionos_start

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. KotlinJS → visionOS

<!-- field-box id="agent_visionos_start_root" index="0" -->

```edn [field-box id="agent_visionos_start_root" role="properties"]
{"id" "agent_visionos_start_root" "index" 0 "name" "KotlinJS → visionOS" "parents" ["root"] "children" ["agent_visionos_start"] "properties" {"children" #{#afield/box "agent_visionos_start"} "parents" #{#afield/box "root"} "name" "KotlinJS → visionOS" "_tombstone" false "frame" #afield/rect [50.0 50.0 300.0 100.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" []}
```

## 2. visionOS Starter

<!-- field-box id="agent_visionos_start" index="1" -->

### Narrative

From `code`

{{ visionmetal uniforms=phase,amount,tint }}
Open Live Metal in Field4Vision. Launch again after editing to hot-reload.
The shader lives in this box's `msl` property — edit it and press alt-r to
recompile on the headset without restarting any of this.

From `msl` tags: `#define`

Uniforms are read by name: the head generates `#define u_phase frame.user[0]`
and so on from the `uniforms=` list on the marker in the code property.

```edn [field-box id="agent_visionos_start" role="properties"]
{"id" "agent_visionos_start" "index" 1 "name" "visionOS Starter" "parents" ["root" "agent_visionos_start_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_visionos_start_root"} "name" "visionOS Starter" "_tombstone" false "frame" #afield/rect [90.0 150.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_visionos_start_code_.kt" "lines" 24 "characters" 716 "sha256" "a5cf1e01a68741218dcd077df16bf1b11f381c75238440479c240c703f95dd1e"} {"name" "msl" "language" "msl" "path" "agent_visionos_start_msl_.metal" "lines" 38 "characters" 1349 "sha256" "88d0efd7d55751118876a2806e3bd79ac51c05cc4ba67a2326b5b60fecb58747"}]}
```

```kotlin [field-property box="agent_visionos_start" name="code" path="agent_visionos_start_code_.kt"]
// {{ visionmetal uniforms=phase,amount,tint }}
// Open Live Metal in Field4Vision. Launch again after editing to hot-reload.
// The shader lives in this box's `msl` property — edit it and press alt-r to
// recompile on the headset without restarting any of this.
onStart {
    // Geometry is built here, in the headset, and handed to Metal in one call.
    geometry {
        color(1.0, 0.35, 0.15)
        cube(0.18, cx = 0.45, cz = -1.2, tintFaces = true)
    }

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

```msl [field-property box="agent_visionos_start" name="msl" path="agent_visionos_start_msl_.metal"]
// Uniforms are read by name: the head generates `#define u_phase frame.user[0]`
// and so on from the `uniforms=` list on the marker in the code property.
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float phase = u_phase.x;
    float amount = u_amount.x;
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
    out.uv = v.uv.xy;
    return out;
}

fragment half4 field_fragment(
    FieldVarying in [[stage_in]],
    constant FieldFrame &frame [[buffer(1)]])
{
    float3 rgb = mix(in.color.rgb, u_tint.rgb, u_tint.a);
    return half4(half3(rgb), half(in.color.a));
}
```

