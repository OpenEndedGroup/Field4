# Field Document: skinned_characters

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["rig_facts" "drag_time" "path_trace" "gpu_skin" "f5e60137-f514-4c01-865c-5af02f1d8eee" "c82c404d-a15d-4f51-a06f-66dfc2a00364"] "properties" {"children" #{#afield/box "rig_facts" #afield/box "drag_time" #afield/box "path_trace" #afield/box "gpu_skin" #afield/box "f5e60137-f514-4c01-865c-5af02f1d8eee" #afield/box "c82c404d-a15d-4f51-a06f-66dfc2a00364"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{} "preamble" ""} "blockProperties" []}
```

## 2. 0 - A window and a renderer, which box 4 needs

<!-- field-box id="f5e60137-f514-4c01-865c-5af02f1d8eee" index="1" -->

```edn [field-box id="f5e60137-f514-4c01-865c-5af02f1d8eee" role="properties"]
{"id" "f5e60137-f514-4c01-865c-5af02f1d8eee" "index" 1 "name" "0 - A window and a renderer, which box 4 needs" "parents" ["root"] "children" ["gpu_skin" "c82c404d-a15d-4f51-a06f-66dfc2a00364"] "properties" {"children" #{#afield/box "gpu_skin" #afield/box "c82c404d-a15d-4f51-a06f-66dfc2a00364"} "parents" #{#afield/box "root"} "name" "0 - A window and a renderer, which box 4 needs" "frame" #afield/rect [0.0 0.0 100.0 100.0] "nested" #{} "autorun" false} "blockProperties" [{"name" "preamble" "language" "text" "lines" 6 "characters" 240 "sha256" "89f6a77619abdeabad9f89f3e7257ad06dd1da86a31cca48e470573a8d07d803"} {"name" "code" "language" "kotlin" "path" "f5e60137-f514-4c01-865c-5af02f1d8eee_code_.kt" "lines" 24 "characters" 1123 "sha256" "1e06ad6ba8e1133419854e02d9044a74d3b7bfd6d62fddc77a22e0e6de432cb9"}]}
```

```text [field-property box="f5e60137-f514-4c01-865c-5af02f1d8eee" name="preamble"]
import afield.four.util.Window
import afield.four.Renderer
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
```

```kotlin [field-property box="f5e60137-f514-4c01-865c-5af02f1d8eee" name="code" path="f5e60137-f514-4c01-865c-5af02f1d8eee_code_.kt"]
import afield.four.util.Options
import afield.Rect

// The window and renderer that box 4 needs.
//
// Box 4 draws, and a drawing box needs a Renderer in scope. Every other GPU demo in this folder
// ships one of these -- frame_history_reprojection, path_tracer_instrument, path_tracing_emitters
// and path_tracing_medium each have exactly one box declaring `prop<Renderer>(renderer)`. This
// demo did not, so box 4 only ran under `field_backdoor.py --graphics-context`, where the agent's
// own context box supplied a Renderer by type. Opened the ordinary way -- `./field.sh` on this
// document -- box 4 failed with "must pass in a value for context parameter afield.four.Renderer".
//
// `prop<Renderer>(renderer)` publishes it to this box's children, which is why box 4 is connected
// beneath this one and can now say `renderer` by name.
prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
once.renderer {
    window = Window("Field · skinned characters", initialDimensions = Rect(80.0, 80.0, 1100.0, 700.0))
    Renderer(window)
}
println("renderer ready — now run box 4")
start(renderer::run)
```

## 3. 5 - The candy wrapper, side by side

<!-- field-box id="c82c404d-a15d-4f51-a06f-66dfc2a00364" index="2" -->

```edn [field-box id="c82c404d-a15d-4f51-a06f-66dfc2a00364" role="properties"]
{"id" "c82c404d-a15d-4f51-a06f-66dfc2a00364" "index" 2 "name" "5 - The candy wrapper, side by side" "parents" ["root" "f5e60137-f514-4c01-865c-5af02f1d8eee"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "f5e60137-f514-4c01-865c-5af02f1d8eee"} "name" "5 - The candy wrapper, side by side" "frame" #afield/rect [697.33203125 375.296875 393.60546875 143.71875] "nested" #{} "attic" ""} "blockProperties" [{"name" "preamble" "language" "text" "lines" 81 "characters" 5450 "sha256" "c271e4c4e9836e4f77281b753c7a65126463289a6d57b23e4bc664576b7024e7"} {"name" "code" "language" "kotlin" "path" "c82c404d-a15d-4f51-a06f-66dfc2a00364_code_.kt" "lines" 56 "characters" 2695 "sha256" "f1aef7d2396b1ed9620addcbe6ad0496616ddcdcb63f67c4486486203760bfd8"} {"name" "fragment" "language" "glsl" "path" "c82c404d-a15d-4f51-a06f-66dfc2a00364_fragment_.glsl" "lines" 10 "characters" 260 "sha256" "f337a9d388363610b81ec88dbcd02debc876320eb9daf0a17366a0778ee0fd5b"} {"name" "vertex" "language" "glsl" "path" "c82c404d-a15d-4f51-a06f-66dfc2a00364_vertex_.glsl" "lines" 77 "characters" 2005 "sha256" "b60636fbbef4538818bb7caddee9f3d5ccc0a3be1b248282d52c72cebc731a7c"}]}
```

```text [field-property box="c82c404d-a15d-4f51-a06f-66dfc2a00364" name="preamble"]
import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
/*start*/
/*graphics-structure-type:VertexBuffer:VertexBuffer*/
class VertexBuffer(
    members: List<Introspected.Member> = VertexBufferLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var vertexData by Structure.FromStructure<Float>()
}
fun VertexBufferByteSize(): Long = 4L
fun VertexBufferLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("vertexData", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asVertexBuffer(): VertexBuffer =
    asStructure(VertexBuffer(), VertexBufferLayoutMembers())
fun BufferParameters.asVertexBuffer(): VertexBuffer =
    buffer.asVertexBuffer()
/*end*/
/*start*/
/*graphics-structure-type:IndexBuffer:IndexBuffer*/
class IndexBuffer(
    members: List<Introspected.Member> = IndexBufferLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var indexData by Structure.FromStructure<UInt>()
}
fun IndexBufferByteSize(): Long = 4L
fun IndexBufferLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("indexData", 0, PrimitiveType.uint.left(), 4, false),
    )
fun HasBuffer.asIndexBuffer(): IndexBuffer =
    asStructure(IndexBuffer(), IndexBufferLayoutMembers())
fun BufferParameters.asIndexBuffer(): IndexBuffer =
    buffer.asIndexBuffer()
/*end*/
/*graphics-pc-structure:modelView*/ var Structure.modelView by Structure.FromStructure<Any>() // view * model
/*graphics-pc-binding:modelView*/ var Mesh.modelView by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // view * model
/*graphics-pc-binding-group:modelView*/ var MeshGroup.modelView by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // view * model
/*graphics-pc-structure:proj*/ var Structure.proj by Structure.FromStructure<Any>() // projection
/*graphics-pc-binding:proj*/ var Mesh.proj by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // projection
/*graphics-pc-binding-group:proj*/ var MeshGroup.proj by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // projection
/*graphics-pc-structure:materialBaseColor*/ var Structure.materialBaseColor by Structure.FromStructure<Any>()
/*graphics-pc-binding:materialBaseColor*/ var Mesh.materialBaseColor by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:materialBaseColor*/ var MeshGroup.materialBaseColor by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-structure:vertexOffset*/ var Structure.vertexOffset by Structure.FromStructure<Any>() // in floats
/*graphics-pc-binding:vertexOffset*/ var Mesh.vertexOffset by Mesh.BoundFromStructure<UInt>() // in floats
/*graphics-pc-binding-group:vertexOffset*/ var MeshGroup.vertexOffset by MeshGroup.BoundFromStructure<UInt>() // in floats
/*graphics-pc-structure:indexOffset*/ var Structure.indexOffset by Structure.FromStructure<Any>() // in indices
/*graphics-pc-binding:indexOffset*/ var Mesh.indexOffset by Mesh.BoundFromStructure<UInt>() // in indices
/*graphics-pc-binding-group:indexOffset*/ var MeshGroup.indexOffset by MeshGroup.BoundFromStructure<UInt>() // in indices
/*graphics-pc-structure:vertexStride*/ var Structure.vertexStride by Structure.FromStructure<Any>() // in floats
/*graphics-pc-binding:vertexStride*/ var Mesh.vertexStride by Mesh.BoundFromStructure<UInt>() // in floats
/*graphics-pc-binding-group:vertexStride*/ var MeshGroup.vertexStride by MeshGroup.BoundFromStructure<UInt>() // in floats
/*graphics-pc-structure:useVertexColor*/ var Structure.useVertexColor by Structure.FromStructure<Any>() // bool as uint
/*graphics-pc-binding:useVertexColor*/ var Mesh.useVertexColor by Mesh.BoundFromStructure<UInt>() // bool as uint
/*graphics-pc-binding-group:useVertexColor*/ var MeshGroup.useVertexColor by MeshGroup.BoundFromStructure<UInt>() // bool as uint
/*graphics-pc-structure:vertexBuffer*/ var Structure.vertexBuffer by Structure.FromStructure<Any>()
/*graphics-pc-binding:vertexBuffer*/ var Mesh.vertexBuffer by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:vertexBuffer*/ var MeshGroup.vertexBuffer by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:indexBuffer*/ var Structure.indexBuffer by Structure.FromStructure<Any>()
/*graphics-pc-binding:indexBuffer*/ var Mesh.indexBuffer by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:indexBuffer*/ var MeshGroup.indexBuffer by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
```

```kotlin [field-property box="c82c404d-a15d-4f51-a06f-66dfc2a00364" name="code" path="c82c404d-a15d-4f51-a06f-66dfc2a00364_code_.kt"]
import afield.four.modules.CpuSkinner
import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// The candy wrapper, side by side, on one rig at one pose.
//
// LEFT is linear blend skinning: average the joint MATRICES. RIGHT is dual quaternion skinning:
// average the rigid MOTIONS. Everything else is identical -- same file, same palette, same clip,
// same frame. The only difference is `method`.
//
// Why it happens, exactly: at the joint ring the weights are 0.5/0.5 (the rig has only two distinct
// weight tuples, [1,0,0,0] and [0.5,0.5,0,0]). Linear blending averages the joint MATRICES, so at
// 180 degrees about y it forms 0.5*I + 0.5*Ry(180) = diag(0, 1, 0), and every x and z on that ring
// is multiplied by zero. Dual quaternions average the joint MOTIONS, and the average of two
// rotations is a rotation.
val useDualQuaternions = false
val twistTime = 1.0f

// ONE mesh, deliberately, and this is a trap worth knowing about. Drawing LBS and DQS side by side
// as two Mesh objects DOES NOT WORK: both read `inst`'s vertex buffer at draw time rather than at
// applyToMesh time, so both tubes show whichever pose was written LAST. Measured 2026-09-19 -- with
// lbs-then-dqs both rendered round, and with lbs-then-lbs both rendered pinched, while the printed
// numbers stayed correct. A two-mesh comparison here would be a figure that lies.
val renderer: Renderer = fromContext()

var (module, models) = gltfModelHere(RiggedFixture.locate())
var m = Mesh()
module += "tube" to m
renderer += "candy wrapper" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(2, 3, -8)
camera.p()

val inst = models[0].meshes[0]
val method =
    if (useDualQuaternions) GltfSkinning.SkinningMethod.DUAL_QUATERNION
    else GltfSkinning.SkinningMethod.LINEAR_BLEND
val skinner = CpuSkinner(inst, GltfSkinning.SkinningOptions(method = method))

val jointY = RiggedFixture.UPPER_JOINT_Y
val rest = DualQuaternionSkinning.candyWrapperRadius(skinner.pose("twist", 0f).position, jointY)
val out = skinner.pose("twist", twistTime)
inst.applyToMesh(m, camera, Matrix4f(), reuseBuffers = true)

println("twist t = %.2f, min radius at the y = %.1f ring (rest %.4f)".format(twistTime, jointY, rRest))
println("  LEFT   linear blend      %.6f   (%5.1f%% of rest)".format(rL, 100f * rL / rRest))
println("  RIGHT  dual quaternion   %.6f   (%5.1f%% of rest)".format(rR, 100f * rR / rRest))
println("  dual quaternion non-rigid joints ${outR.nonRigidJoints}, antipodal flips ${outR.antipodalFlips}")

loop { frame() }
```

```glsl [field-property box="c82c404d-a15d-4f51-a06f-66dfc2a00364" name="fragment" path="c82c404d-a15d-4f51-a06f-66dfc2a00364_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 1) in vec3 vNormalVS;

void main()
{
    float d = abs(dot(vNormalVS.xyz, normalize(vec3(1, 0.4, -0.2))));
    uFragColor = vec4(vNormalVS.rgb*d,1);
}
```

```glsl [field-property box="c82c404d-a15d-4f51-a06f-66dfc2a00364" name="vertex" path="c82c404d-a15d-4f51-a06f-66dfc2a00364_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats
    uint useVertexColor;   // bool as uint

    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec3 vNormalVS;
layout(location = 2) out vec2 vUV;
layout(location = 3) out vec4 vColor;

void main()
{
    uint idx = pc.indexBuffer.indexData[pc.indexOffset+uint(gl_VertexIndex)];
    uint base = pc.vertexOffset + idx * pc.vertexStride;

    vec3 pos = vec3(
        pc.vertexBuffer.vertexData[base + 0],
        pc.vertexBuffer.vertexData[base + 1],
        pc.vertexBuffer.vertexData[base + 2]
    );

    vec3 normal = vec3(
        pc.vertexBuffer.vertexData[base + 3],
        pc.vertexBuffer.vertexData[base + 4],
        pc.vertexBuffer.vertexData[base + 5]
    );

    vec2 uv = vec2(
        pc.vertexBuffer.vertexData[base + 6],
        pc.vertexBuffer.vertexData[base + 7]
    );

    vec4 color = vec4(
        pc.vertexBuffer.vertexData[base + 8],
        pc.vertexBuffer.vertexData[base + 9],
        pc.vertexBuffer.vertexData[base + 10],
        pc.vertexBuffer.vertexData[base + 11]
    );

    if (pc.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    vec4 viewPos =  pc.proj * pc.modelView*vec4(pos, 1.0);

    mat3 mv3 = mat3(pc.modelView);
    vec3 normalVS = normalize(mv3 * normal);

    vViewPos = viewPos.xyz;
    vNormalVS = normalVS;
    vUV = uv;
    vColor = color * pc.materialBaseColor;

    gl_Position = viewPos;
}
```

## 4. 1 - A rigged mesh, and what the loader now reads out of it

<!-- field-box id="rig_facts" index="3" -->

```edn [field-box id="rig_facts" role="properties"]
{"id" "rig_facts" "index" 3 "name" "1 - A rigged mesh, and what the loader now reads out of it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A rigged mesh, and what the loader now reads out of it" "_tombstone" false "frame" #afield/rect [60.0 60.0 600.0 136.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rig_facts_code_.kt" "lines" 60 "characters" 3222 "sha256" "affb52c5c7afe2c5f689ba7aee9e36a64207454874118302d0a817561c805d78"}]}
```

```kotlin [field-property box="rig_facts" name="code" path="rig_facts_code_.kt"]
import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.RiggedFixture
import afield.four.modules.assimpToTriMesh
import afield.four.modules.skinnedGltfToTriMesh
import afield.stdlib.MeshView
import afield.stdlib.showMeshGrid

// Field could load a glTF and it could sample per-NODE animation. It could not skin.
//
// One line in the whole of src/jvmMain + src/commonMain mentioned a bone, and it was
// PathTraceGltf.kt refusing to render one: "Skinned glTF meshes need skin deformation before path
// tracing". This box loads a rigged mesh, prints what the loader now reads out of it, and draws
// four poses of it. Box 2 is the one you drag.

val rig = RiggedFixture.locate()
val model = GltfSupport.loadGltfBasic(rig, GltfSupport.GltfLoadConfig(loadTextures = false))
val skin = model.meshes.firstNotNullOf { it.skin }
val sk = skin.skeleton

println("rig  $rig")
println("mesh ${model.meshes.first().buffers.vertexCount} vertices, " +
    "${model.meshes.first().buffers.indexCount / 3} triangles")
println()
println("nodes   ${sk.nodes.map { it.name }}")
println("parents ${sk.nodes.map { it.parent }}   (-1 is the scene root)")
println("bones   ${sk.bones.map { "${it.name} -> node ${it.node}" }}")
println("clips   ${sk.clips.map { "${it.name} (${it.durationSeconds}s, ${it.channels.size} channels)" }}")
println()
println("The skeleton indexes NODES, not bones. Assimp hands over a bone's name and its inverse")
println("bind matrix and no parent -- the hierarchy is in the node tree, a bone's parent chain")
println("routinely runs through nodes that are NOT bones (here: 'armature'), and the skeleton root")
println("is not the mesh's node. Indexing nodes makes all three fall out instead of needing cases.")
println()

// Weights, over a stated population. A skinning bug that shows up as a mesh collapsing toward the
// origin is almost always weights that do not sum to 1, so it is worth being able to see the number.
val infl = skin.influences
println("weights over all ${infl.vertexCount} vertices: worst |sum(w) - 1| = ${infl.worstWeightSumError()}, " +
    "${infl.unweightedVertices()} unweighted, ${infl.discardedInfluences} influences past the " +
    "${GltfSkinning.MAX_INFLUENCES}-wide cap")

// The assertion worth making about a bind pose is EXACTNESS, not closeness: an inverse-bind mistake
// lands a fraction of a percent off, which any tolerance you would reach for waves through.
val rest = assimpToTriMesh(rig)
val atZero = skinnedGltfToTriMesh(rig, clip = "bend", seconds = 0f)
val identical = (0 until rest.vertexCount).count { v ->
    val a = rest.position(v); val b = atZero.position(v)
    a.x == b.x && a.y == b.y && a.z == b.z
}
println("bind pose: $identical of ${rest.vertexCount} vertices bit-identical to the unskinned mesh")

val view = MeshView(yawDegrees = 0.0, pitchDegrees = 8.0, distance = 2.6)
showMeshGrid(
    "bind, t = 0" to atZero,
    "bend, t = 0.5" to skinnedGltfToTriMesh(rig, clip = "bend", seconds = 0.5f),
    "bend, t = 1" to skinnedGltfToTriMesh(rig, clip = "bend", seconds = 1f),
    "curl, t = 1" to skinnedGltfToTriMesh(rig, clip = "curl", seconds = 1f),
    columns = 4, cellWidth = 300, view = view,
)
```

## 5. 2 - Drag `time` and watch it bend

<!-- field-box id="drag_time" index="4" -->

```edn [field-box id="drag_time" role="properties"]
{"id" "drag_time" "index" 4 "name" "2 - Drag `time` and watch it bend" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Drag `time` and watch it bend" "_tombstone" false "frame" #afield/rect [700.0 60.0 600.0 136.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "drag_time_code_.kt" "lines" 46 "characters" 2129 "sha256" "5939fd7fe3408b3abea772c5b2e032b83f17468ddaf1529dd002a1ec339e2cae"}]}
```

```kotlin [field-property box="drag_time" name="code" path="drag_time_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.modules.RiggedFixture
import afield.four.modules.posedGltfToTriMesh
import afield.stdlib.MeshView
import afield.stdlib.showMesh

// Drag `time`. The cylinder bends.
//
// `time` runs 0 -> 2 seconds through a clip that rotates the upper bone 0 -> 60 -> 0 degrees about
// +z. At 0 the tube is straight and the skinned vertices are bit-identical to the unskinned ones;
// at 1 the tip has swung -sqrt(3) in x, which is -2 sin 60 exactly. Drag `bend to curl` as well and
// the LOWER bone joins in, which is the parent chain composing.

val rig = RiggedFixture.locate()
val view = MeshView(yawDegrees = 0.0, pitchDegrees = 8.0, distance = 2.6)

var seconds = 0.6
var mix = 0.0

fun draw() {
    // blendedPalette mixes the two clips' joint matrices componentwise -- the same linear blend that
    // linear blend skinning itself commits to, so a limb shortens at 90 degrees here exactly as it
    // does there. Not a quaternion blend, deliberately, so the two agree.
    val mesh = posedGltfToTriMesh(rig) { s ->
        s.blendedPalette(s.clip("bend"), seconds.toFloat(), s.clip("curl"), seconds.toFloat(), mix.toFloat())
    }
    val b = mesh.bounds()
    // Printed as well as drawn, so the pose is a NUMBER and not only a picture -- at time 1, mix 0,
    // tip x is -1.982, which is -2 sin 60 minus the tube's own radius projected onto the bend.
    println("time %.2f s, bend->curl %.2f -> tip x %.4f, top y %.4f"
        .format(seconds, mix, b.min.x, b.max.y))
    showMesh(
        mesh, channel = "pose", view = view,
        caption = "time %.2f s, bend->curl %.2f — tip reaches x = %.3f".format(seconds, mix, b.min.x),
    )
}

controlGroup("controls") {
    slider("time", min = 0.0, max = 2.0, step = 0.01, initialValue = seconds) { seconds = it; draw() }
    slider("bend to curl", min = 0.0, max = 1.0, step = 0.01, initialValue = mix) { mix = it; draw() }
}
displayOpen("controls")
draw()
println("Drag `time` (0 to 2 s) in the controls above, or on /controllers.")
println("At time 0 and 2 the tube is straight; the extreme is at time 1.")
```

## 6. 4 - The same deformation on the GPU, no shader change

<!-- field-box id="gpu_skin" index="5" -->

```edn [field-box id="gpu_skin" role="properties"]
{"id" "gpu_skin" "index" 5 "name" "4 - The same deformation on the GPU, no shader change" "parents" ["root" "f5e60137-f514-4c01-865c-5af02f1d8eee"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "f5e60137-f514-4c01-865c-5af02f1d8eee"} "name" "4 - The same deformation on the GPU, no shader change" "_tombstone" false "frame" #afield/rect [700.0 226.0 600.0 136.0] "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gpu_skin_code_.kt" "lines" 71 "characters" 3462 "sha256" "c9a99cc156372dbb50677daaff60bed6f82ad220cdfe1136e00bd0a310f5b19b"} {"name" "preamble" "language" "text" "lines" 81 "characters" 5450 "sha256" "f06f1a765abc44dfe220c15003c7e0867955b0f2736e2ccbbfb5d9309ffaf8f5"} {"name" "fragment" "language" "glsl" "path" "gpu_skin_fragment_.glsl" "lines" 10 "characters" 260 "sha256" "f337a9d388363610b81ec88dbcd02debc876320eb9daf0a17366a0778ee0fd5b"} {"name" "vertex" "language" "glsl" "path" "gpu_skin_vertex_.glsl" "lines" 77 "characters" 2005 "sha256" "b60636fbbef4538818bb7caddee9f3d5ccc0a3be1b248282d52c72cebc731a7c"}]}
```

```kotlin [field-property box="gpu_skin" name="code" path="gpu_skin_code_.kt"]
import afield.four.modules.CpuSkinner
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// The same deformation, on the GPU, through Field's OWN glTF module and its OWN bundled shaders.
//
// No shader change and no joint-matrix buffer: CpuSkinner rewrites the position and normal fields
// of the packed 12-float vertex buffer that applyToMesh already uploads. At the measured 17.7 M
// vertices/s that is 2.8 ms for a 50,000-vertex character.
//
// EDIT `poseTime` AND RE-RUN. 0 is a straight tube; 1 is a 60-degree bend with the tip at
// x = -1.982. The line it prints is the tip's x and the mesh's top y, so the pose is a number as
// well as a picture.
val poseTime = 1.0f

// This box needs a renderer in scope. Under `field_backdoor.py --graphics-context` that is the
// agent's context box, which sets `renderer` as a raw property but does not declare it with the
// `prop<Renderer>(renderer)` macro a hand-built renderer box uses -- so the bare name does not
// resolve and fromContext() finds it by type instead. In a document with your own renderer box as
// the parent, `renderer` resolves directly and this line is redundant but harmless.
val renderer: Renderer = fromContext()

var (module, models) = gltfModelHere(RiggedFixture.locate())

var m = Mesh()
module += "skinned" to m
renderer += "skinned characters" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(2, 3, -8)
camera.p()

val inst = models[0].meshes[0]
val skinner = CpuSkinner(inst)
val out = skinner.pose("bend", poseTime)

// model = identity, NOT the default: a skinned mesh is already in scene space, because glTF 2.0
// 3.7.3.2 says a skinned mesh node's own transform is ignored. The default would apply it twice.
inst.applyToMesh(m, camera, Matrix4f(), reuseBuffers = true)

val minX = (0 until out.vertexCount).minOf { out.position[it * 3] }
val maxY = (0 until out.vertexCount).maxOf { out.position[it * 3 + 1] }
println("gpu skinned at t = %.2f -> tip x %.4f, top y %.4f".format(poseTime, minX, maxY))

loop { frame() }

// ----------------------------------------------------------------------------------------------
// Why this poses ONCE rather than animating, and what was measured
//
// An animating version is three lines:
//
//     var n = 0
//     loop { skinner.pose("bend", (n % 120) / 60f)
//            inst.applyToMesh(m, camera, Matrix4f(), reuseBuffers = true); n++; frame() }
//
// and it runs. What it does NOT survive is the agent screenshot path: `native-screenshot` times
// out after 15 s against it, so no agent can photograph the result, and a demo nobody has looked
// at is a claim. Bisected on 2026-09-19, one variable at a time, against the same scene:
//
//   nothing in the loop                                        capture OK
//   skinner.pose() every frame, 118 fps, no upload             capture OK
//   getPropertyRaw() every frame                               capture TIMES OUT
//   inst.applyToMesh() every frame, reuseBuffers = true        capture TIMES OUT
//
// So the deformation is innocent -- posing 60 vertices every frame costs nothing and photographs
// fine. It is the per-frame re-upload (and, separately, a per-frame box-property read) that the
// direct window capture cannot get past. Left as a named finding rather than a fix, because
// diagnosing the renderer's capture path is a different job from skinning.
```

```text [field-property box="gpu_skin" name="preamble"]
import afield.four.util.Window
import afield.four.Renderer
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:VertexBuffer:VertexBuffer*/
class VertexBuffer(
    members: List<Introspected.Member> = VertexBufferLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var vertexData by Structure.FromStructure<Float>()
}
fun VertexBufferByteSize(): Long = 4L
fun VertexBufferLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("vertexData", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asVertexBuffer(): VertexBuffer =
    asStructure(VertexBuffer(), VertexBufferLayoutMembers())
fun BufferParameters.asVertexBuffer(): VertexBuffer =
    buffer.asVertexBuffer()
/*end*/
/*start*/
/*graphics-structure-type:IndexBuffer:IndexBuffer*/
class IndexBuffer(
    members: List<Introspected.Member> = IndexBufferLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var indexData by Structure.FromStructure<UInt>()
}
fun IndexBufferByteSize(): Long = 4L
fun IndexBufferLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("indexData", 0, PrimitiveType.uint.left(), 4, false),
    )
fun HasBuffer.asIndexBuffer(): IndexBuffer =
    asStructure(IndexBuffer(), IndexBufferLayoutMembers())
fun BufferParameters.asIndexBuffer(): IndexBuffer =
    buffer.asIndexBuffer()
/*end*/
/*graphics-pc-structure:modelView*/ var Structure.modelView by Structure.FromStructure<Any>() // view * model
/*graphics-pc-binding:modelView*/ var Mesh.modelView by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // view * model
/*graphics-pc-binding-group:modelView*/ var MeshGroup.modelView by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // view * model
/*graphics-pc-structure:proj*/ var Structure.proj by Structure.FromStructure<Any>() // projection
/*graphics-pc-binding:proj*/ var Mesh.proj by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // projection
/*graphics-pc-binding-group:proj*/ var MeshGroup.proj by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // projection
/*graphics-pc-structure:materialBaseColor*/ var Structure.materialBaseColor by Structure.FromStructure<Any>()
/*graphics-pc-binding:materialBaseColor*/ var Mesh.materialBaseColor by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:materialBaseColor*/ var MeshGroup.materialBaseColor by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-structure:vertexOffset*/ var Structure.vertexOffset by Structure.FromStructure<Any>() // in floats
/*graphics-pc-binding:vertexOffset*/ var Mesh.vertexOffset by Mesh.BoundFromStructure<UInt>() // in floats
/*graphics-pc-binding-group:vertexOffset*/ var MeshGroup.vertexOffset by MeshGroup.BoundFromStructure<UInt>() // in floats
/*graphics-pc-structure:indexOffset*/ var Structure.indexOffset by Structure.FromStructure<Any>() // in indices
/*graphics-pc-binding:indexOffset*/ var Mesh.indexOffset by Mesh.BoundFromStructure<UInt>() // in indices
/*graphics-pc-binding-group:indexOffset*/ var MeshGroup.indexOffset by MeshGroup.BoundFromStructure<UInt>() // in indices
/*graphics-pc-structure:vertexStride*/ var Structure.vertexStride by Structure.FromStructure<Any>() // in floats
/*graphics-pc-binding:vertexStride*/ var Mesh.vertexStride by Mesh.BoundFromStructure<UInt>() // in floats
/*graphics-pc-binding-group:vertexStride*/ var MeshGroup.vertexStride by MeshGroup.BoundFromStructure<UInt>() // in floats
/*graphics-pc-structure:useVertexColor*/ var Structure.useVertexColor by Structure.FromStructure<Any>() // bool as uint
/*graphics-pc-binding:useVertexColor*/ var Mesh.useVertexColor by Mesh.BoundFromStructure<UInt>() // bool as uint
/*graphics-pc-binding-group:useVertexColor*/ var MeshGroup.useVertexColor by MeshGroup.BoundFromStructure<UInt>() // bool as uint
/*graphics-pc-structure:vertexBuffer*/ var Structure.vertexBuffer by Structure.FromStructure<Any>()
/*graphics-pc-binding:vertexBuffer*/ var Mesh.vertexBuffer by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:vertexBuffer*/ var MeshGroup.vertexBuffer by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:indexBuffer*/ var Structure.indexBuffer by Structure.FromStructure<Any>()
/*graphics-pc-binding:indexBuffer*/ var Mesh.indexBuffer by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:indexBuffer*/ var MeshGroup.indexBuffer by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
```

```glsl [field-property box="gpu_skin" name="fragment" path="gpu_skin_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 1) in vec3 vNormalVS;

void main()
{
    float d = abs(dot(vNormalVS.xyz, normalize(vec3(1, 0.4, -0.2))));
    uFragColor = vec4(vNormalVS.rgb*d,1);
}
```

```glsl [field-property box="gpu_skin" name="vertex" path="gpu_skin_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats
    uint useVertexColor;   // bool as uint

    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec3 vNormalVS;
layout(location = 2) out vec2 vUV;
layout(location = 3) out vec4 vColor;

void main()
{
    uint idx = pc.indexBuffer.indexData[pc.indexOffset+uint(gl_VertexIndex)];
    uint base = pc.vertexOffset + idx * pc.vertexStride;

    vec3 pos = vec3(
        pc.vertexBuffer.vertexData[base + 0],
        pc.vertexBuffer.vertexData[base + 1],
        pc.vertexBuffer.vertexData[base + 2]
    );

    vec3 normal = vec3(
        pc.vertexBuffer.vertexData[base + 3],
        pc.vertexBuffer.vertexData[base + 4],
        pc.vertexBuffer.vertexData[base + 5]
    );

    vec2 uv = vec2(
        pc.vertexBuffer.vertexData[base + 6],
        pc.vertexBuffer.vertexData[base + 7]
    );

    vec4 color = vec4(
        pc.vertexBuffer.vertexData[base + 8],
        pc.vertexBuffer.vertexData[base + 9],
        pc.vertexBuffer.vertexData[base + 10],
        pc.vertexBuffer.vertexData[base + 11]
    );

    if (pc.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    vec4 viewPos =  pc.proj * pc.modelView*vec4(pos, 1.0);

    mat3 mv3 = mat3(pc.modelView);
    vec3 normalVS = normalize(mv3 * normal);

    vViewPos = viewPos.xyz;
    vNormalVS = normalVS;
    vUV = uv;
    vColor = color * pc.materialBaseColor;

    gl_Position = viewPos;
}
```

## 7. 3 - The refusal PathTraceGltf used to make

<!-- field-box id="path_trace" index="6" -->

```edn [field-box id="path_trace" role="properties"]
{"id" "path_trace" "index" 6 "name" "3 - The refusal PathTraceGltf used to make" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - The refusal PathTraceGltf used to make" "_tombstone" false "frame" #afield/rect [60.0 226.0 600.0 136.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_code_.kt" "lines" 45 "characters" 2332 "sha256" "e6692a43dbb12aa44bf1f0729580fc719ca5d49b72cd42dd9828eb83ea546358"}]}
```

```kotlin [field-property box="path_trace" name="code" path="path_trace_code_.kt"]
import afield.four.fabricated.GltfSkinPose
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.importGltf
import afield.four.modules.RiggedFixture
import afield.four.modules.skinnedGltfToTriMesh

// The line this whole branch was aimed at.
//
// PathTraceGltf.kt used to contain, verbatim:
//     require(mesh.mNumBones()==0) { "Skinned glTF meshes need skin deformation before path tracing" }
// It was the only mention of a bone in the entire JVM source tree -- the codebase naming a missing
// capability in its own refusal message. It now traces the DEFORMED mesh at a pose you choose.

val rig = RiggedFixture.locate()

val rest = PathTraceScene()
val restImport = rest.importGltf(rig)
val bent = PathTraceScene()
val bentImport = bent.importGltf(rig, pose = GltfSkinPose("bend", 1f))

println("rest pose   ${restImport.triangleCount} triangles, " +
    "x in [%.4f, %.4f]".format(restImport.boundsMin.x, restImport.boundsMax.x))
println("bend t=1    ${bentImport.triangleCount} triangles, " +
    "x in [%.4f, %.4f]".format(bentImport.boundsMin.x, bentImport.boundsMax.x))
println()
bentImport.warnings.forEach { println("warning: $it") }
println()

// The cross-check worth printing: two completely separate readers of the same deformation. The
// tracer walks Assimp and stages world-space triangles; the CPU bridge packs a 12-float vertex
// buffer and builds a TriMesh. They are two implementations of one formula, so agreeing is
// evidence and disagreeing would localise the bug.
val cpu = skinnedGltfToTriMesh(rig, clip = "bend", seconds = 1f).bounds()
println("CPU bridge  x in [%.4f, %.4f], y in [%.4f, %.4f]".format(cpu.min.x, cpu.max.x, cpu.min.y, cpu.max.y))
println("tracer      x in [%.4f, %.4f], y in [%.4f, %.4f]"
    .format(bentImport.boundsMin.x, bentImport.boundsMax.x, bentImport.boundsMin.y, bentImport.boundsMax.y))
println("max |difference| = %.7f".format(
    maxOf(
        kotlin.math.abs(cpu.min.x - bentImport.boundsMin.x), kotlin.math.abs(cpu.max.x - bentImport.boundsMax.x),
        kotlin.math.abs(cpu.min.y - bentImport.boundsMin.y), kotlin.math.abs(cpu.max.y - bentImport.boundsMax.y),
    )
))
println()
println("An unknown clip name fails the import rather than quietly tracing the rest pose -- try")
println("GltfSkinPose(\"walk\", 1f) above and read the message.")
```

