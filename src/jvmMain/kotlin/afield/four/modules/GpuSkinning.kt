@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.four.Renderer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.buffer
import afield.four.util.Camera
import afield.stdlib.linalg.Matrix4f

/**
 * Skinning in the **vertex shader**, with a joint-matrix palette on the GPU, and a crowd of
 * characters from one mesh and one draw call.
 *
 * [CpuSkinner] deforms on the CPU and rewrites the packed 12-float vertex buffer that
 * `MeshInstance.applyToMesh` uploads. That is correct, it is measured — 17.7 M vertices/s, so
 * 2.8 ms for a 50,000-vertex character — and it is the **oracle** this path is checked against.
 * What it cannot do is a crowd: every character costs another full deformation and another full
 * re-upload, so a hundred of them is a hundred times 2.8 ms plus a hundred buffer writes.
 *
 * Here the bind-pose vertices are uploaded **once** and never touched again. Per frame the CPU
 * writes only the palette — `instances × bones` matrices, which for the fixtures here is 7 bones
 * — and the vertex shader does the `Σ wᵢ · palette[bᵢ] · p` for every vertex of every instance.
 *
 * ## What is on the GPU and what is not
 *
 * Worth being exact about, because "GPU skinning" is often claimed for less:
 *
 * - **On the GPU:** the per-vertex blend. `O(instances × vertices)` — the term that grows.
 * - **On the CPU:** evaluating the palette from the clips. `O(instances × bones)`, and bones are
 *   units where vertices are tens of thousands. [GltfSkinning.Skeleton.buildGlobals] is a
 *   sequential parent-before-child walk; moving it to a compute shader would buy a rounding
 *   error and cost the ability to read it.
 *
 * So per-instance clip blending is evaluated per instance *on the CPU* and handed over as that
 * instance's slice of the palette. See [GpuSkinner.uploadPalettes].
 *
 * ## The instance transform rides in the palette
 *
 * There is no per-instance model-matrix buffer, and that is not an omission. A palette entry is
 * already `global(node) · inverseBind`, and placing instance *i* in the world is one more matrix
 * on the left:
 *
 * ```
 * palette[i][b] = model(i) · global(node(b), tᵢ) · inverseBind(b)
 * ```
 *
 * The CPU is building that palette anyway, so per-instance placement costs one `mul` per bone
 * and nothing at all on the GPU. It also keeps the normal matrix honest — the model transform is
 * inside the blended 3×3 that [glsl] inverts and transposes, rather than applied after it.
 * [GpuSkinner.placedPalette] does the multiply.
 *
 * ## One GLSL, two entry points
 *
 * [glsl] is the whole of the maths, and it is used by **both** [skinnedVertexShader] (which
 * draws) and [verifyComputeShader] (which writes deformed positions to a buffer the CPU reads
 * back). That is deliberate: `GpuSkinningSmoke` comparing the compute output against
 * [GltfSkinning.skin] is then evidence about the shader that actually renders, and not about a
 * second implementation written to pass a test.
 *
 * ## Push-constant budget
 *
 * The stock glTF block is 176 bytes. This adds three buffer references and three uints, for
 * **212**. Vulkan only guarantees 128, so this is a MoltenVK/desktop-class assumption, not a
 * portable one; the machine it was measured on reports 4096. If it ever has to shrink, the three
 * uints fold into one packed word and the joint and weight buffers interleave into one.
 */
object GpuSkinning {

    /** Influences per vertex, fixed by [GltfSkinning.MAX_INFLUENCES] and by `uvec4`/`vec4`. */
    const val MAX_INFLUENCES: Int = GltfSkinning.MAX_INFLUENCES

    /** Floats per vertex in the packed buffer the bundled glTF shaders read. */
    const val VERTEX_STRIDE_FLOATS: Int = 12

    /** Invocations per workgroup in [verifyComputeShader]. */
    const val LOCAL_SIZE: Int = 64

    /**
     * The skinning maths, as GLSL: three buffer-reference types and three functions.
     *
     * Place immediately after `#version` — it carries its own `#extension` directive. Written to
     * mirror [GltfSkinning.skin] statement for statement, including the parts that look like
     * fussiness and are not:
     *
     * - It accumulates the weighted **matrix**, not the weighted transformed position. Identical
     *   for positions; it is the only way to get the blended 3×3 the normal needs.
     * - A vertex with no influence at all gets the **identity**, not the zero matrix, so an
     *   unweighted vertex stays where it is instead of collapsing to the origin.
     * - Weights are renormalised, matching `SkinningOptions.renormalize`, so a file whose weights
     *   sum to 0.9 does not shrink.
     * - The normal uses the exact inverse transpose when the blended 3×3 is invertible and the
     *   plain 3×3 when it is not, matching `SkinningOptions.exactNormalMatrix`. Falling back
     *   rather than producing a NaN is what keeps a degenerate pose renderable.
     *
     * `mat4 * float` and `mat4 + mat4` are component-wise in GLSL, so `afSkinBlend` is the
     * literal transcription of `GltfSkinning.addScaled`.
     */
    fun glsl(): String = """
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) readonly buffer AfSkinJoints  { uvec4 v[]; };
layout(std430, buffer_reference) readonly buffer AfSkinWeights { vec4  v[]; };
layout(std430, buffer_reference) readonly buffer AfSkinPalette { mat4  v[]; };

// The four influences of `vertex`, blended into one matrix. `paletteBase` is where this
// instance's slice of the palette starts -- instanceIndex * boneCount.
mat4 afSkinBlend(AfSkinJoints joints, AfSkinWeights weights, AfSkinPalette palette,
                 uint vertex, uint paletteBase) {
    uvec4 j = joints.v[vertex];
    vec4  w = weights.v[vertex];

    float sum = w.x + w.y + w.z + w.w;
    float norm = sum > 0.0 ? 1.0 / sum : 1.0;

    mat4 blended = mat4(0.0);
    bool contributed = false;
    if (w.x != 0.0) { blended += palette.v[paletteBase + j.x] * (w.x * norm); contributed = true; }
    if (w.y != 0.0) { blended += palette.v[paletteBase + j.y] * (w.y * norm); contributed = true; }
    if (w.z != 0.0) { blended += palette.v[paletteBase + j.z] * (w.z * norm); contributed = true; }
    if (w.w != 0.0) { blended += palette.v[paletteBase + j.w] * (w.w * norm); contributed = true; }

    // An unweighted vertex keeps its bind position rather than collapsing to the origin.
    return contributed ? blended : mat4(1.0);
}

vec3 afSkinPosition(mat4 blended, vec3 p) {
    return (blended * vec4(p, 1.0)).xyz;
}

vec3 afSkinNormal(mat4 blended, vec3 n) {
    mat3 nm = mat3(blended);
    float d = determinant(nm);
    // Singular, infinite or NaN determinant: use the plain 3x3. A wrong-ish normal beats a NaN,
    // which would take the whole triangle out of the rasteriser.
    if (abs(d) > 1e-20 && !isinf(d) && !isnan(d)) nm = transpose(inverse(nm));
    vec3 t = nm * n;
    float len = length(t);
    return len > 1e-12 ? t / len : t;
}
""".trimIndent()

    /**
     * [GltfSupport.gltVertexShader] with the skin in it: same packed 12-float vertex, same buffer
     * references, same push constants — plus a joint stream, a palette and `gl_InstanceIndex`.
     *
     * Everything the stock shader does, it still does. The additions are:
     *
     * - `jointBuffer` / `weightBuffer`, indexed by the **same** `idx` the vertex data is, so the
     *   12-float layout every other Field glTF path depends on is unchanged. Two extra buffers
     *   beat a fourteen-float vertex that no other shader could read.
     * - `paletteBuffer`, sliced by `gl_InstanceIndex * boneCount`.
     * - `skinEnabled`, so one module can draw a rigged and a rigid mesh. 0 is the stock path,
     *   bit for bit.
     * - `instanceTint`, which multiplies in a hash of the instance index. A crowd where every
     *   character is the same colour is a crowd you cannot tell is a crowd.
     *
     * `modelView` should carry **view only** when skinning: a skinned mesh is already in scene
     * space (glTF 2.0 §3.7.3.2), and each instance's placement is inside its palette. Pushing a
     * model matrix as well applies it twice.
     */
    val skinnedVertexShader: String = """
#version 450
${glsl()}
#extension GL_EXT_scalar_block_layout : require

layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;        // view * model; view alone when skinning
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats
    uint useVertexColor;   // bool as uint

    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;

    AfSkinJoints  jointBuffer;
    AfSkinWeights weightBuffer;
    AfSkinPalette paletteBuffer;

    uint boneCount;        // palette entries per instance
    uint skinEnabled;      // 0 = draw the bind pose, exactly as the stock shader does
    uint instanceTint;     // 1 = tint by gl_InstanceIndex so a crowd reads as a crowd
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec3 vNormalVS;
layout(location = 2) out vec2 vUV;
layout(location = 3) out vec4 vColor;

// A cheap, stable, well-spread hue per instance. Not a random number generator -- it only has to
// make neighbours differ.
vec3 afInstanceTint(uint i) {
    float h = fract(float(i) * 0.6180339887);
    vec3 k = fract(vec3(h) + vec3(0.0, 0.3333333, 0.6666667));
    return 0.45 + 0.55 * abs(k * 6.0 - 3.0) / 3.0;
}

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

    if (pc.skinEnabled != 0u) {
        uint paletteBase = uint(gl_InstanceIndex) * pc.boneCount;
        mat4 blended = afSkinBlend(pc.jointBuffer, pc.weightBuffer, pc.paletteBuffer, idx, paletteBase);
        pos = afSkinPosition(blended, pos);
        normal = afSkinNormal(blended, normal);
    }

    if (pc.instanceTint != 0u) {
        color.rgb *= afInstanceTint(uint(gl_InstanceIndex));
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
""".trimIndent()

    /**
     * A fragment shader that actually uses `vColor`.
     *
     * [GltfSupport.gltfFragmentShader] declares `vColor` and then never reads it — it outputs
     * `vNormalVS.rgb * d`, so every mesh is shaded by its own normal direction and the
     * `materialBaseColor` push constant has no effect on the picture. That is fine for one
     * object and useless for a crowd: with it, a hundred instances tinted a hundred ways render
     * a hundred identical characters, and "the crowd is varied" becomes unfalsifiable.
     *
     * So this keeps the same one-directional-light look and multiplies the interpolated colour
     * in. Same `location` bindings, so it drops into the same module.
     */
    val skinnedFragmentShader: String = """
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec3 vNormalVS;
layout(location = 3) in vec4 vColor;

void main()
{
    vec3 l = normalize(vec3(1, 0.4, -0.2));
    float d = abs(dot(normalize(vNormalVS), l));
    // A floor under the diffuse term, so a character facing away is dark rather than absent.
    uFragColor = vec4(vColor.rgb * (0.25 + 0.75 * d), 1.0);
}
""".trimIndent()

    /**
     * The same [glsl], run over every vertex of every instance, writing deformed positions and
     * normals to a buffer the CPU can read back and compare with [GltfSkinning.skin].
     *
     * This exists because a screenshot cannot tell you that a vertex is in the right place to six
     * decimals, and because the alternative — transform feedback — is not in core Vulkan. One
     * invocation per `(instance, vertex)`; output is two `vec4` per invocation, position then
     * normal, so the readback is `std430`-simple rather than a packing puzzle.
     *
     * It reads the bind pose from its own `bindBuffer` (two `vec4` per vertex) rather than from
     * the packed 12-float vertex buffer, so the comparison does not depend on the draw path's
     * vertex layout staying put.
     */
    val verifyComputeShader: String = """
#version 450
${glsl()}
layout(local_size_x = $LOCAL_SIZE) in;

layout(std430, buffer_reference) readonly buffer AfSkinBind { vec4 v[]; };
layout(std430, buffer_reference) buffer AfSkinOut { vec4 v[]; };

layout(push_constant) uniform Push {
    AfSkinJoints  jointBuffer;
    AfSkinWeights weightBuffer;
    AfSkinPalette paletteBuffer;
    AfSkinBind    bindBuffer;
    AfSkinOut     outBuffer;
    uint boneCount;
    uint vertexCount;
    uint instanceCount;
} pc;

void main() {
    uint gid = gl_GlobalInvocationID.x;
    if (gid >= pc.vertexCount * pc.instanceCount) return;

    uint inst = gid / pc.vertexCount;
    uint v    = gid - inst * pc.vertexCount;

    mat4 blended = afSkinBlend(pc.jointBuffer, pc.weightBuffer, pc.paletteBuffer,
                               v, inst * pc.boneCount);

    vec3 p = afSkinPosition(blended, pc.bindBuffer.v[v * 2u].xyz);
    vec3 n = afSkinNormal(blended, pc.bindBuffer.v[v * 2u + 1u].xyz);

    pc.outBuffer.v[gid * 2u]      = vec4(p, 1.0);
    pc.outBuffer.v[gid * 2u + 1u] = vec4(n, 0.0);
}
""".trimIndent()
}

/**
 * The GPU half of [CpuSkinner]: joints and weights uploaded once, a palette written per frame,
 * and a crowd drawn from one mesh.
 *
 * ```kotlin
 * val skinner = GpuSkinner(models[0].meshes[0], instances = 64)
 * skinner.uploadBindPose(mesh, camera)                 // once
 * loop {
 *     skinner.uploadPalettes((0 until 64).map { i ->
 *         skinner.placedPalette(model(i), "bend", t + i * 0.05f)
 *     })                                               // 64 x 7 matrices, not 64 x 50,000 vertices
 *     frame()
 * }
 * ```
 *
 * The three things easy to get wrong, all of which this handles and none of which announce
 * themselves if you do them by hand:
 *
 * - **The model matrix must not be pushed.** `applyToMesh` defaults `model` to the node's own
 *   transform, and glTF 2.0 §3.7.3.2 says a skinned mesh node's transform is ignored. This
 *   passes `Matrix4f()`; per-instance placement goes through [placedPalette].
 * - **The bind pose must be uploaded, not a posed one.** Sharing a `MeshInstance` with a
 *   [CpuSkinner] is therefore a trap: `CpuSkinner.pose` rewrites `instance.buffers` in place and
 *   the GPU would then skin already-skinned vertices. [uploadBindPose] snapshots at construction
 *   and writes the snapshot, so the two can coexist — see `GpuSkinningSmoke`, which runs both.
 * - **`reuseBuffers` must be on.** Without it every call allocates a fresh `FrameBufferGroup`;
 *   here it is called once, but the palette upload is per frame and is a plain overwrite.
 */
class GpuSkinner(
    val instance: GltfSupport.MeshInstance,
    /** How many characters this palette is sized for. One draw call covers all of them. */
    val instances: Int = 1,
    val renderer: Renderer = fromContext(),
    /** Distinguishes this skinner's buffers from another's. */
    val name: String = "gpuSkin",
) {
    /** The mesh's skin; non-null by construction, as for [CpuSkinner]. */
    val skin: GltfSkinning.MeshSkin = requireNotNull(instance.skin) {
        "MeshInstance '${instance.name}' has no skin; GpuSkinner has nothing to deform"
    }

    val vertexCount: Int = instance.buffers.vertexCount
    val boneCount: Int = skin.skeleton.bones.size

    /** Clip names available on this mesh's skeleton, in file order. */
    val clips: List<String> get() = skin.skeleton.clips.map { it.name }

    init {
        require(instances > 0) { "instances must be positive, got $instances" }
        require(boneCount > 0) { "MeshInstance '${instance.name}' has a skin with no bones" }
    }

    /**
     * The bind-pose positions and normals, snapshotted at construction.
     *
     * `3 * vertexCount` each. Taken here rather than read on demand because a [CpuSkinner] on the
     * same `MeshInstance` overwrites `instance.buffers` in place, and a GPU skinner that read the
     * buffer later would skin an already-skinned mesh.
     */
    val bindPositions: FloatArray = FloatArray(vertexCount * 3)
    val bindNormals: FloatArray = FloatArray(vertexCount * 3)

    /** The whole packed 12-float vertex buffer as it was at construction — the bind pose. */
    private val bindVertexData: FloatArray = FloatArray(vertexCount * GpuSkinning.VERTEX_STRIDE_FLOATS)

    init {
        val f = instance.buffers.vertexData.asFloatBuffer()
        for (n in 0 until vertexCount * GpuSkinning.VERTEX_STRIDE_FLOATS) bindVertexData[n] = f.get(n)
        for (v in 0 until vertexCount) for (k in 0..2) {
            bindPositions[v * 3 + k] = bindVertexData[v * 12 + k]
            bindNormals[v * 3 + k] = bindVertexData[v * 12 + 3 + k]
        }
    }

    /** `uvec4` per vertex. Static: written once in `init`, never again. */
    val jointBuffer: FrameBufferGroup = buffer(name = "${name}Joints") {
        size = vertexCount * 4L * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    /** `vec4` per vertex. Static. */
    val weightBuffer: FrameBufferGroup = buffer(name = "${name}Weights") {
        size = vertexCount * 4L * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    /**
     * `instances × boneCount` `mat4`. The only thing written per frame, and the reason a crowd is
     * nearly free: 64 characters of a 7-bone rig is 448 matrices, against 64 × `vertexCount`
     * vertices the GPU would otherwise have to be handed.
     */
    val paletteBuffer: FrameBufferGroup = buffer(name = "${name}Palette") {
        size = instances.toLong() * boneCount * 16L * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    init {
        val infl = skin.influences
        jointBuffer.get(write = true).mapAndWrite_ints {
            for (v in 0 until vertexCount) for (k in 0 until GpuSkinning.MAX_INFLUENCES) {
                put(infl.jointIndex[v * GpuSkinning.MAX_INFLUENCES + k])
            }
        }
        weightBuffer.get(write = true).mapAndWrite {
            for (v in 0 until vertexCount) for (k in 0 until GpuSkinning.MAX_INFLUENCES) {
                put(infl.weight[v * GpuSkinning.MAX_INFLUENCES + k])
            }
        }
    }

    /**
     * This instance's palette at [seconds] of [clip], with [model] multiplied in on the left, so
     * one entry is `model · global(node, t) · inverseBind`.
     *
     * @param clip a clip name, or `null` for the rest pose.
     */
    fun placedPalette(model: Matrix4f, clip: String?, seconds: Float): List<Matrix4f> =
        place(model, skin.skeleton.palette(clip?.let { skin.skeleton.clip(it) }, seconds))

    /**
     * As [placedPalette], but blending two clips — the per-instance clip blend. [mix] is 0 for all
     * [clipA], 1 for all [clipB].
     *
     * The blend is a component-wise matrix lerp, which is what
     * [GltfSkinning.Skeleton.blendedPalette] does and therefore what the CPU oracle does. It is
     * not a slerp: a matrix lerp of two very different rotations shrinks the joint, and the fix
     * for that is `SkinningMethod.DUAL_QUATERNION`, not a different blend here.
     */
    fun blendedPalette(
        model: Matrix4f, clipA: String?, aSeconds: Float, clipB: String?, bSeconds: Float, mix: Float,
    ): List<Matrix4f> = place(
        model,
        skin.skeleton.blendedPalette(
            clipA?.let { skin.skeleton.clip(it) }, aSeconds,
            clipB?.let { skin.skeleton.clip(it) }, bSeconds, mix,
        )
    )

    private fun place(model: Matrix4f, palette: List<Matrix4f>): List<Matrix4f> =
        palette.map { Matrix4f(model).mul(it) }

    /**
     * Write one palette per instance. `palettes.size` must be at most [instances], and every
     * entry must have [boneCount] matrices.
     *
     * The whole per-frame CPU cost of an animating crowd.
     */
    fun uploadPalettes(palettes: List<List<Matrix4f>>) {
        require(palettes.size <= instances) {
            "${palettes.size} palettes for a skinner sized for $instances instances"
        }
        for ((i, p) in palettes.withIndex()) require(p.size == boneCount) {
            "palette $i has ${p.size} matrices, this skeleton has $boneCount bones"
        }
        paletteBuffer.get(write = true).mapAndWrite {
            for (p in palettes) for (m in p) {
                for (n in 0 until 16) put(m[n])
            }
        }
    }

    /** One palette, for the common single-character case. */
    fun uploadPalette(palette: List<Matrix4f>) = uploadPalettes(listOf(palette))

    /**
     * Upload the **bind-pose** vertex buffer and set every push constant
     * [GpuSkinning.skinnedVertexShader] needs. Call once; after this only [uploadPalettes] and, if
     * the camera moves, `mesh.push["modelView"] = camera.m()` are per-frame.
     *
     * @param instanceCount how many characters to draw. Sets `mesh.instanceCount`, which
     *   `Mesh.kt` already passes to `vkCmdDraw` — so a crowd is one draw call and needs no change
     *   to `standardRenderer.kt` or to any renderer code.
     * @param tint multiply in a per-instance hue, so a crowd reads as a crowd.
     */
    fun uploadBindPose(
        mesh: Mesh,
        camera: Camera = fromContext(),
        instanceCount: Int = instances,
        tint: Boolean = instances > 1,
    ) {
        require(instanceCount in 1..instances) { "instanceCount $instanceCount is outside 1..$instances" }

        // Put the bind pose back before uploading: a CpuSkinner sharing this MeshInstance may have
        // posed it in place, and uploading a posed mesh would make the GPU skin it a second time.
        val f = instance.buffers.vertexData.asFloatBuffer()
        for (n in bindVertexData.indices) f.put(n, bindVertexData[n])

        // Model = identity. The mesh node's transform is ignored for a skinned mesh, and each
        // instance's placement lives in its palette.
        instance.applyToMesh(mesh, camera, Matrix4f(), reuseBuffers = true)

        mesh.push.bind("jointBuffer") { jointBuffer }
        mesh.push.bind("weightBuffer") { weightBuffer }
        mesh.push.bind("paletteBuffer") { paletteBuffer }
        mesh.push["boneCount"] = boneCount
        mesh.push["skinEnabled"] = 1
        mesh.push["instanceTint"] = if (tint) 1 else 0

        mesh.instanceCount = instanceCount
    }

    /** Draw the bind pose through the stock path — the before picture, for a comparison shot. */
    fun disableSkinning(mesh: Mesh) {
        mesh.push["skinEnabled"] = 0
    }
}
