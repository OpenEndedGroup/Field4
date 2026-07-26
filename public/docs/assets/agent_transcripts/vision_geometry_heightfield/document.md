# Field Document: vision_geometry_heightfield

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. KotlinJS → visionOS

<!-- field-box id="agent_vision_geometry_heightfield_root" index="0" -->

```edn [field-box id="agent_vision_geometry_heightfield_root" role="properties"]
{"id" "agent_vision_geometry_heightfield_root" "index" 0 "name" "KotlinJS → visionOS" "parents" ["root"] "children" ["agent_vision_geometry_heightfield"] "properties" {"children" #{#afield/box "agent_vision_geometry_heightfield"} "parents" #{#afield/box "root"} "name" "KotlinJS → visionOS" "_tombstone" false "frame" #afield/rect [50.0 50.0 500.0 360.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" []}
```

## 2. Geometry — height field

<!-- field-box id="agent_vision_geometry_heightfield" index="1" -->

### Narrative

From `code`

{{ visionmetal uniforms=spin,light }}

```edn [field-box id="agent_vision_geometry_heightfield" role="properties"]
{"id" "agent_vision_geometry_heightfield" "index" 1 "name" "Geometry — height field" "parents" ["root" "agent_vision_geometry_heightfield_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_vision_geometry_heightfield_root"} "name" "Geometry — height field" "_tombstone" false "frame" #afield/rect [90.0 100.0 420.0 270.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_vision_geometry_heightfield_code_.kt" "lines" 68 "characters" 2721 "sha256" "6b9c18101e3d822d3ee40369424a0a23f53f33ee553303a708d1980398a150f4"} {"name" "msl" "language" "msl" "path" "agent_vision_geometry_heightfield_msl_.metal" "lines" 44 "characters" 1534 "sha256" "c860f820d188ff0de4d05ab1c18ba06697728514e5e8da308d16ca5ba14183dc"}]}
```

```kotlin [field-property box="agent_vision_geometry_heightfield" name="code" path="agent_vision_geometry_heightfield_code_.kt"]
// {{ visionmetal uniforms=spin,light }}
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

onStart {
    geometry {
        val n = 48                 // cells per side; (n+1)^2 vertices
        val size = 0.52            // metres across
        val centreZ = -1.2

        // The surface, as a plain function. Sampling it three times per vertex is what
        // gives us a normal without any calculus.
        fun height(x: Double, z: Double): Double {
            val r = sqrt(x * x + z * z)
            return 0.055 * cos(r * 38.0) * exp(-r * 5.5)
        }

        val step = size / n
        val eps = step * 0.5
        val base = vertexCount

        for (row in 0..n) {
            for (col in 0..n) {
                val x = (col.toDouble() / n - 0.5) * size
                val z = (row.toDouble() / n - 0.5) * size
                val h = height(x, z)

                // Central differences along x and z give the two tangents; their cross
                // product is the normal. Negating the slopes puts it on the +y side.
                val dx = (height(x + eps, z) - height(x - eps, z)) / (2.0 * eps)
                val dz = (height(x, z + eps) - height(x, z - eps)) / (2.0 * eps)
                val len = sqrt(dx * dx + dz * dz + 1.0)
                normal(-dx / len, 1.0 / len, -dz / len)

                // Colour by height, so the shader needs no extra uniforms to show relief.
                val t = (h / 0.055 + 1.0) * 0.5
                color(0.15 + 0.75 * t, 0.35 + 0.35 * t, 0.95 - 0.35 * t)

                uv(col.toDouble() / n, row.toDouble() / n)
                vertex(x, h, centreZ + z)
            }
        }

        // vertex() handed back an index for each of those; stitch them into quads. The
        // lattice is (n+1) wide, so a cell's four corners are a, a+stride, a+stride+1, a+1.
        //
        // The order matters as much as the membership. This sheet lies in XZ with its
        // normal on +y, and only this diagonal winds counter-clockwise seen from above.
        // Take the corners the other way round and the head — which culls back faces —
        // shows you the underside of your landscape, which is to say nothing at all.
        val stride = n + 1
        for (row in 0 until n) {
            for (col in 0 until n) {
                val a = base + row * stride + col
                quad(a, a + stride, a + stride + 1, a + 1)
            }
        }

        log("height field: $vertexCount vertices, ${indexCount / 3} triangles")
    }

    uniform("light", 0.35, 0.9, 0.35)
    onFrame { frame -> uniform("spin", frame.time * 0.25) }

    onStop { println("height field stopped") }
    println("height field running")
}
```

```msl [field-property box="agent_vision_geometry_heightfield" name="msl" path="agent_vision_geometry_heightfield_msl_.metal"]
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float3 centre = float3(0.0, 0.0, -1.2);
    float3 p = v.position.xyz - centre;
    float3 n = v.normal.xyz;

    // Tip it towards the viewer so the relief reads, then turn it slowly.
    float tilt = -0.55;
    float ct = cos(tilt), st = sin(tilt);
    p.yz = float2(ct * p.y - st * p.z, st * p.y + ct * p.z);
    n.yz = float2(ct * n.y - st * n.z, st * n.y + ct * n.z);

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
    float3 n = normalize(in.normal);

    // Lit from both sides, since a height field is a sheet you can see the back of.
    float diffuse = 0.22 + 0.78 * abs(dot(n, light));
    float rim = pow(1.0 - abs(n.z), 2.0) * 0.15;

    return half4(half3(in.color.rgb * diffuse + rim), 1.0h);
}
```

