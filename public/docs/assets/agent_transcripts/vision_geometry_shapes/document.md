# Field Document: vision_geometry_shapes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. KotlinJS → visionOS

<!-- field-box id="agent_vision_geometry_shapes_root" index="0" -->

```edn [field-box id="agent_vision_geometry_shapes_root" role="properties"]
{"id" "agent_vision_geometry_shapes_root" "index" 0 "name" "KotlinJS → visionOS" "parents" ["root"] "children" ["agent_vision_geometry_shapes"] "properties" {"children" #{#afield/box "agent_vision_geometry_shapes"} "parents" #{#afield/box "root"} "name" "KotlinJS → visionOS" "_tombstone" false "frame" #afield/rect [50.0 50.0 480.0 340.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" []}
```

## 2. Geometry — shapes

<!-- field-box id="agent_vision_geometry_shapes" index="1" -->

### Narrative

From `code`

{{ visionmetal uniforms=spin,light }}

From `msl`

Spins the whole mesh about the group centre, then shades it. The plane picks up a
checker from the uv its builder wrote; everything else has uv (0,0)..(1,1) per face.

```edn [field-box id="agent_vision_geometry_shapes" role="properties"]
{"id" "agent_vision_geometry_shapes" "index" 1 "name" "Geometry — shapes" "parents" ["root" "agent_vision_geometry_shapes_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_vision_geometry_shapes_root"} "name" "Geometry — shapes" "_tombstone" false "frame" #afield/rect [90.0 100.0 400.0 250.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_vision_geometry_shapes_code_.kt" "lines" 41 "characters" 1454 "sha256" "33573a6ddf1583b8c8dd07bbd19622ed2e43518fc866b18bd5002b5f4692f765"} {"name" "msl" "language" "msl" "path" "agent_vision_geometry_shapes_msl_.metal" "lines" 41 "characters" 1492 "sha256" "4f3d9944de2bf342811b64ddac6ecaf4e981fe7ea1a541ce03eca984d074d81d"}]}
```

```kotlin [field-property box="agent_vision_geometry_shapes" name="code" path="agent_vision_geometry_shapes_code_.kt"]
// {{ visionmetal uniforms=spin,light }}
import kotlin.math.cos
import kotlin.math.sin

// Four shapes in one mesh, laid out left to right 1.2m in front of you.
onStart {
    geometry {
        // The current colour applies to everything built after it. `tintFaces` overrides
        // it per face so the cube's six sides stay legible while it turns.
        color(1.0, 0.42, 0.18)
        cube(0.11, cx = -0.42, cz = -1.2, tintFaces = true)

        color(0.25, 0.75, 1.0)
        sphere(0.13, segments = 32, rings = 20, cx = -0.14, cz = -1.2)

        // plane() writes uv across the whole sheet, which the shader picks up as a checker.
        color(0.95, 0.85, 0.25)
        plane(0.26, 0.26, cols = 8, rows = 8, cx = 0.14, cz = -1.2)

        // tube() sweeps a polygon along a polyline — the same real-thickness treatment
        // the native FLine viewer gives a stroke.
        color(0.62, 0.42, 1.0)
        tube(
            (0..48).map { i ->
                val t = i / 48.0
                val a = t * 6.2831853
                p(0.42 + 0.055 * sin(a), -0.13 + t * 0.26, -1.2 + 0.055 * cos(a))
            },
            radius = 0.017,
            sides = 10,
        )

        log("built $vertexCount vertices, ${indexCount / 3} triangles")
    }

    uniform("light", 0.4, 0.85, 0.5)
    onFrame { frame -> uniform("spin", frame.time * 0.4) }

    onStop { println("geometry shapes stopped") }
    println("geometry shapes running")
}
```

```msl [field-property box="agent_vision_geometry_shapes" name="msl" path="agent_vision_geometry_shapes_msl_.metal"]
// Spins the whole mesh about the group centre, then shades it. The plane picks up a
// checker from the uv its builder wrote; everything else has uv (0,0)..(1,1) per face.
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float3 centre = float3(0.0, 0.0, -1.2);
    float3 p = v.position.xyz - centre;
    float3 n = v.normal.xyz;

    float a = u_spin.x;
    float c = cos(a), s = sin(a);
    p.xz = float2(c * p.x + s * p.z, -s * p.x + c * p.z);
    n.xz = float2(c * n.x + s * n.z, -s * n.x + c * n.z);

    float4 world = frame.modelMatrix * float4(p + centre, 1.0);
    FieldVarying out;
    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
    out.worldPosition = world.xyz;
    out.normal = normalize(n);
    out.color = v.color;
    out.uv = v.uv.xy;
    return out;
}

fragment half4 field_fragment(
    FieldVarying in [[stage_in]],
    constant FieldFrame &frame [[buffer(1)]])
{
    float3 light = normalize(u_light.xyz);
    float diffuse = 0.25 + 0.75 * max(dot(normalize(in.normal), light), 0.0);

    // An 8x8 checker straight off the interpolated uv.
    float2 cell = floor(in.uv * 8.0);
    float checker = fmod(cell.x + cell.y, 2.0);
    float3 rgb = in.color.rgb * mix(0.72, 1.0, checker) * diffuse;

    return half4(half3(rgb), half(in.color.a));
}
```

