# Field Document: skinned_characters

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 0 - a window and a renderer, which gpu_skin needs

<!-- field-box id="skin_renderer" index="0" -->

```edn [field-box id="skin_renderer" role="properties"]
{"id" "skin_renderer" "index" 0 "name" "0 - a window and a renderer, which gpu_skin needs" "parents" ["root"] "children" ["gpu_skin" "twist_compare" "gpu_crowd" "gpu_twist"] "properties" {"children" #{#afield/box "gpu_skin" #afield/box "twist_compare" #afield/box "gpu_crowd" #afield/box "gpu_twist"} "parents" #{#afield/box "root"} "name" "0 - a window and a renderer, which gpu_skin needs" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "skin_renderer_code_.kt" "lines" 24 "characters" 1123 "sha256" "1e06ad6ba8e1133419854e02d9044a74d3b7bfd6d62fddc77a22e0e6de432cb9"}]}
```

```kotlin [field-property box="skin_renderer" name="code" path="skin_renderer_code_.kt"]
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

## 2. 6 - skinning in the vertex shader, and a crowd of 64

<!-- field-box id="gpu_crowd" index="1" -->

```edn [field-box id="gpu_crowd" role="properties"]
{"id" "gpu_crowd" "index" 1 "name" "6 - skinning in the vertex shader, and a crowd of 64" "parents" ["root" "skin_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "skin_renderer"} "name" "6 - skinning in the vertex shader, and a crowd of 64" "_tombstone" false "frame" #afield/rect [40.0 520.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gpu_crowd_code_.kt" "lines" 76 "characters" 3217 "sha256" "3bc40d743b50628f76f9f7d3d4e5ee62b6dfd4c5b0330e68092da7c203b36ebd"}]}
```

```kotlin [field-property box="gpu_crowd" name="code" path="gpu_crowd_code_.kt"]
import afield.four.modules.GltfSupport
import afield.four.modules.GpuSkinner
import afield.four.modules.GpuSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// Sixty-four characters, one mesh, one draw call, all of them animating -- and not one vertex is
// re-uploaded after setup. Box 4 above is the CPU skinner: it rewrites the packed vertex buffer
// every pose, which costs 2.8 ms for a 50,000-vertex character and cannot be done 64 times.
//
// EDIT `count` AND RE-RUN. The per-frame CPU cost is `count x bones` matrices, so 2 here.
val count = 64

// `renderer` and `window` come from box 0, which publishes them with `prop<Renderer>(renderer)`.
// This box is connected beneath it, so the bare name resolves -- RUN BOX 0 FIRST. Before box 0
// has run there is no Renderer anywhere and this box fails with "must pass in a value for
// context parameter afield.four.Renderer", which is the same footgun box 4 documents.
val model = GltfSupport.loadGltfBasic(
    RiggedFixture.locate(), GltfSupport.GltfLoadConfig(loadTextures = false)
)
val inst = model.meshes.first { it.skin != null }

// The shaders come straight from the library constants, so this box cannot drift from the code
// `GpuSkinningSmoke` grades. `skinnedFragmentShader` rather than the bundled one because the
// bundled glTF fragment shader never reads vColor -- it outputs the normal -- so the per-instance
// tint would be invisible and "the crowd is varied" would be unfalsifiable.
val module = Module(
    Parameters(
        renderer = renderer,
        vertex = GpuSkinning.skinnedVertexShader,
        fragment = GpuSkinning.skinnedFragmentShader,
        name = "gpu skinned crowd"
    )
).pointer()

val m = Mesh(renderer, module)
module += "crowd" to m
renderer += "gpu skinned crowd" to module

val camera = Camera(renderer)
camera.p.orthoness = 0.25
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(6.5, 8.5, -17)
camera.p()

val skinner = GpuSkinner(inst, instances = count, renderer = renderer, name = "crowd")
skinner.bindTo(m, camera, instanceCount = count, tint = true)

// Instance i on an 8x8 grid. The placement is multiplied into that instance's palette, so it
// costs one mul per bone and nothing at all on the GPU -- there is no instance-matrix buffer.
fun place(i: Int): Matrix4f {
    val x = (i % 8 - 3.5f) * 2.2f
    val z = (i / 8 - 3.5f) * 2.2f
    return Matrix4f().translate(vec(x, 0, z))
}

var n = 0
loop {
    val t = n / 60f
    // The whole per-frame CPU cost: `count` palettes of 2 matrices each. Every instance is at its
    // own phase of its own blend of two clips, which is the per-instance clip blending -- it is
    // evaluated here, on the CPU, because it is O(instances x bones) and bones are units.
    skinner.uploadPalettes((0 until count).map { i ->
        skinner.blendedPalette(
            place(i),
            "bend", (t + i * 0.09f) % 2f,
            "curl", (t * 0.7f + i * 0.05f) % 2f,
            mix = i / (count - 1f)
        )
    })
    skinner.updateCamera(m, camera)
    if (n % 120 == 0) println("frame $n: $count instances, ${count * skinner.boneCount} matrices uploaded, one draw call")
    n++
    frame()
}
```

## 3. 1 - a rigged mesh, and what the loader reads out of it

<!-- field-box id="rig_facts" index="2" -->

```edn [field-box id="rig_facts" role="properties"]
{"id" "rig_facts" "index" 2 "name" "1 - a rigged mesh, and what the loader reads out of it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - a rigged mesh, and what the loader reads out of it" "_tombstone" false "frame" #afield/rect [400.0 40.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rig_facts_code_.kt" "lines" 60 "characters" 3222 "sha256" "affb52c5c7afe2c5f689ba7aee9e36a64207454874118302d0a817561c805d78"}]}
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

## 4. 7 - the candy wrapper on the GPU, linear and dual quaternion in one draw

<!-- field-box id="gpu_twist" index="3" -->

```edn [field-box id="gpu_twist" role="properties"]
{"id" "gpu_twist" "index" 3 "name" "7 - the candy wrapper on the GPU, linear and dual quaternion in one draw" "parents" ["root" "skin_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "skin_renderer"} "name" "7 - the candy wrapper on the GPU, linear and dual quaternion in one draw" "_tombstone" false "frame" #afield/rect [680.0 520.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gpu_twist_code_.kt" "lines" 80 "characters" 3330 "sha256" "d645bb396aa5ff9a503512ccbd8bb668dbf7279af6e4db8763c6c92c1538f2c6"}]}
```

```kotlin [field-property box="gpu_twist" name="code" path="gpu_twist_code_.kt"]
import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning.SkinningMethod
import afield.four.modules.GltfSkinning.SkinningOptions
import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.GpuSkinner
import afield.four.modules.GpuSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// Box 5 is the candy wrapper on the CPU. This is the same twist on the GPU, both methods in ONE
// draw call: the left cylinder is linear blend skinning and pinches to a point at 180 degrees, the
// right one is dual quaternion skinning and keeps its radius. Same mesh, same palette, same shader;
// the only difference between them is one word per instance saying which blend to run.
//
// RUN BOX 0 FIRST, and stop box 6 if it is running -- both draw into box 0's window.
//
// EDIT `methods` AND RE-RUN: make both LINEAR_BLEND and both pinch; make both DUAL_QUATERNION and
// neither does.
val methods = listOf(SkinningMethod.LINEAR_BLEND, SkinningMethod.DUAL_QUATERNION)

val model = GltfSupport.loadGltfBasic(
    RiggedFixture.locate(), GltfSupport.GltfLoadConfig(loadTextures = false)
)
val inst = model.meshes.first { it.skin != null }

val module = Module(
    Parameters(
        renderer = renderer,
        vertex = GpuSkinning.skinnedVertexShader,
        fragment = GpuSkinning.skinnedFragmentShader,
        name = "gpu twist, lbs vs dqs"
    )
).pointer()

val m = Mesh(renderer, module)
module += "twist" to m
renderer += "gpu twist, lbs vs dqs" to module

val camera = Camera(renderer)
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(0, 2.4, -5.2)
camera.p()

val skinner = GpuSkinner(inst, instances = 2, renderer = renderer, name = "twist")
skinner.bindTo(m, camera, instanceCount = 2, tint = true)

// Seen from this camera +x is on the LEFT, so instance 0 goes to +1.3.
val places = listOf(Matrix4f().translate(vec(1.3, 0, 0)), Matrix4f().translate(vec(-1.3, 0, 0)))
val sk = skinner.skin.skeleton
val twist = sk.clip("twist")!!

// The waist as a number, from the CPU oracles on the same palette: the smallest distance from the
// bone axis of any vertex on the y = 2 ring. Printed so the picture is not the only evidence.
val restRadius = RiggedFixture.RADIUS
fun waist(method: SkinningMethod, palette: List<Matrix4f>): Float {
    val bind = FloatArray(skinner.vertexCount * 3) { skinner.bindPositions[it] }
    val out = GltfSkinning.skin(bind, null, inst.skin!!.influences, palette, SkinningOptions(method = method))
    return DualQuaternionSkinning.candyWrapperRadius(out.position, RiggedFixture.UPPER_JOINT_Y)
}

var n = 0
loop {
    // 0 -> 180 -> 0 degrees of twist, three seconds a cycle at 60 fps, on a loop.
    val seconds = (n / 90f) % twist.durationSeconds
    skinner.uploadPalettes(places.map { skinner.placedPalette(it, "twist", seconds) }, methods)
    skinner.updateCamera(m, camera)
    if (n % 45 == 0) {
        val rest = sk.palette(twist, seconds)
        println("t = %.2f s   waist, %% of rest radius:   %s %.1f%%   |   %s %.1f%%".format(
            seconds,
            methods[0], 100 * waist(methods[0], rest) / restRadius,
            methods[1], 100 * waist(methods[1], rest) / restRadius,
        ))
    }
    n++
    frame()
}
```

## 5. 2 - drag `time` and watch it bend

<!-- field-box id="drag_time" index="4" -->

```edn [field-box id="drag_time" role="properties"]
{"id" "drag_time" "index" 4 "name" "2 - drag `time` and watch it bend" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - drag `time` and watch it bend" "_tombstone" false "frame" #afield/rect [40.0 200.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "drag_time_code_.kt" "lines" 46 "characters" 2129 "sha256" "5939fd7fe3408b3abea772c5b2e032b83f17468ddaf1529dd002a1ec339e2cae"}]}
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

## 6. 4 - the same deformation on the GPU, no shader change

<!-- field-box id="gpu_skin" index="5" -->

```edn [field-box id="gpu_skin" role="properties"]
{"id" "gpu_skin" "index" 5 "name" "4 - the same deformation on the GPU, no shader change" "parents" ["root" "skin_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "skin_renderer"} "name" "4 - the same deformation on the GPU, no shader change" "_tombstone" false "frame" #afield/rect [40.0 360.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gpu_skin_code_.kt" "lines" 71 "characters" 3462 "sha256" "c9a99cc156372dbb50677daaff60bed6f82ad220cdfe1136e00bd0a310f5b19b"}]}
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

## 7. 5 - the candy wrapper, side by side

<!-- field-box id="twist_compare" index="6" -->

```edn [field-box id="twist_compare" role="properties"]
{"id" "twist_compare" "index" 6 "name" "5 - the candy wrapper, side by side" "parents" ["root" "skin_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "skin_renderer"} "name" "5 - the candy wrapper, side by side" "_tombstone" false "frame" #afield/rect [680.0 360.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "twist_compare_code_.kt" "lines" 62 "characters" 3230 "sha256" "15bb056e5b5fb0b1c4c3916999d3247e820ec57ee5a979b3593f99be952b2df0"}]}
```

```kotlin [field-property box="twist_compare" name="code" path="twist_compare_code_.kt"]
import afield.four.modules.CpuSkinner
import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// The candy wrapper: one rig, one pose, two ways of averaging the joints.
//
// `useDualQuaternions = false` is linear blend skinning -- average the joint MATRICES. `true` is
// dual quaternion skinning -- average the rigid MOTIONS. Everything else is identical: same file,
// same palette, same clip, same frame. The only difference is `method`. Flip it and re-run.
//
// Why it happens, exactly: at the joint ring the weights are 0.5/0.5 (the rig has only two distinct
// weight tuples, [1,0,0,0] and [0.5,0.5,0,0]). Linear blending averages the joint MATRICES, so at
// 180 degrees about y it forms 0.5*I + 0.5*Ry(180) = diag(0, 1, 0), and every x and z on that ring
// is multiplied by zero. Dual quaternions average the joint MOTIONS, and the average of two
// rotations is a rotation.
val useDualQuaternions = false
val twistTime = 1.0f

// ONE mesh, deliberately. Drawing LBS and DQS side by side
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

// These four lines were left over from the two-mesh version this box replaced, and still named
// its `rL` / `rR` / `outR` / `rRest` -- so the box failed to compile with "Unresolved reference
// 'rRest'" from the moment the rewrite landed. Nothing caught it: box code is compiled by the K2
// REPL, not by Gradle, so `check` is green on a demo that cannot run. Found 2026-09-20 by running
// every box of this document one at a time, which is the only thing that does catch it.
val radius = DualQuaternionSkinning.candyWrapperRadius(out.position, jointY)
val label = if (useDualQuaternions) "dual quaternion" else "linear blend"
println("twist t = %.2f, min radius at the y = %.1f ring (rest %.4f)".format(twistTime, jointY, rest))
println("  %-16s %.6f   (%5.1f%% of rest)".format(label, radius, 100f * radius / rest))
println("  non-rigid joints ${out.nonRigidJoints}, antipodal flips ${out.antipodalFlips}")

loop { frame() }
```

## 8. 3 - the refusal PathTraceGltf used to make

<!-- field-box id="path_trace" index="7" -->

```edn [field-box id="path_trace" role="properties"]
{"id" "path_trace" "index" 7 "name" "3 - the refusal PathTraceGltf used to make" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - the refusal PathTraceGltf used to make" "_tombstone" false "frame" #afield/rect [680.0 200.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_code_.kt" "lines" 45 "characters" 2332 "sha256" "e6692a43dbb12aa44bf1f0729580fc719ca5d49b72cd42dd9828eb83ea546358"}]}
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

