# Field Document: vision_geometry_animated

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. KotlinJS → visionOS

<!-- field-box id="agent_vision_geometry_animated_root" index="0" -->

```edn [field-box id="agent_vision_geometry_animated_root" role="properties"]
{"id" "agent_vision_geometry_animated_root" "index" 0 "name" "KotlinJS → visionOS" "parents" ["root"] "children" ["agent_vision_geometry_animated"] "properties" {"children" #{#afield/box "agent_vision_geometry_animated"} "parents" #{#afield/box "root"} "name" "KotlinJS → visionOS" "_tombstone" false "frame" #afield/rect [50.0 50.0 200.0 50.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" []}
```

## 2. Geometry — rebuilt per frame

<!-- field-box id="agent_vision_geometry_animated" index="1" -->

### Narrative

From `code`

{{ visionmetal uniforms=light,pulse }}

From `msl`

Note how little this shader does: all the motion is in the geometry, rebuilt in Kotlin.

```edn [field-box id="agent_vision_geometry_animated" role="properties"]
{"id" "agent_vision_geometry_animated" "index" 1 "name" "Geometry — rebuilt per frame" "parents" ["root" "agent_vision_geometry_animated_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_vision_geometry_animated_root"} "name" "Geometry — rebuilt per frame" "_tombstone" false "frame" #afield/rect [90.0 100.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_vision_geometry_animated_code_.kt" "lines" 41 "characters" 1562 "sha256" "6c871bbd8e29a52d9592186299f22b91ccd9982f9e4e38b1507da42d350bd96f"} {"name" "msl" "language" "msl" "path" "agent_vision_geometry_animated_msl_.metal" "lines" 30 "characters" 1141 "sha256" "9f85a62b10b0e213f2924cb73bfce954af9b19175ec6de47781de091036c1a2d"}]}
```

```kotlin [field-property box="agent_vision_geometry_animated" name="code" path="agent_vision_geometry_animated_code_.kt"]
// {{ visionmetal uniforms=light,pulse }}
import kotlin.math.cos
import kotlin.math.sin

// Two ways to animate a mesh, and this box is the second one:
//
//   1. Keep the geometry still and move it in the shader from a uniform. Cheapest; use it
//      whenever the motion is a transform (see visionos_start).
//   2. Rebuild the mesh. Necessary when the *topology* or the spine changes — which is the
//      case here, because the curve itself is being redrawn.
//
// At this size (~600 vertices) rebuilding every frame is comfortable. For a mesh in the
// tens of thousands, rebuild on demand instead and let the shader do per-frame motion.
onStart {
    uniform("light", 0.4, 0.85, 0.5)

    onFrame { frame ->
        val t = frame.time

        geometry {
            color(0.2, 0.9, 0.8)
            tube(
                (0..72).map { i ->
                    val u = i / 72.0
                    // A helix whose winding count drifts, so the spine really does change
                    // shape rather than just rotating.
                    val a = u * (9.0 + 3.0 * sin(t * 0.3)) + t
                    val radius = 0.13 + 0.05 * sin(u * 3.1416 + t * 0.7)
                    p(radius * sin(a), -0.22 + u * 0.44, -1.2 + radius * cos(a))
                },
                radius = 0.011 + 0.007 * sin(t * 2.0),
                sides = 8,
            )
        }

        uniform("pulse", 0.5 + 0.5 * sin(t * 2.0))
    }

    onStop { println("animated geometry stopped") }
    println("animated geometry running — rebuilding the mesh each frame")
}
```

```msl [field-property box="agent_vision_geometry_animated" name="msl" path="agent_vision_geometry_animated_msl_.metal"]
// Note how little this shader does: all the motion is in the geometry, rebuilt in Kotlin.
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float4 world = frame.modelMatrix * v.position;
    FieldVarying out;
    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
    out.worldPosition = world.xyz;
    out.normal = normalize(v.normal.xyz);
    out.color = v.color;
    out.uv = v.uv.xy;
    return out;
}

fragment half4 field_fragment(
    FieldVarying in [[stage_in]],
    constant FieldFrame &frame [[buffer(1)]])
{
    float3 light = normalize(u_light.xyz);
    float diffuse = 0.2 + 0.8 * max(dot(normalize(in.normal), light), 0.0);

    // tube() writes uv.y along the spine, so this band travels with the curve.
    float band = 0.75 + 0.25 * sin(in.uv.y * 40.0 - fieldTime(frame) * 6.0);
    float3 rgb = in.color.rgb * diffuse * band * mix(0.8, 1.15, u_pulse.x);

    return half4(half3(rgb), half(in.color.a));
}
```

