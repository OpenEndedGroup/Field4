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
 * ## Two blends, chosen per instance
 *
 * Linear blend skinning averages matrices, and under a twist about the bone's own axis that
 * average stops being a rotation: the fixture's `twist` clip at 180° pinches the joint ring to
 * nothing, the candy wrapper. [DualQuaternionSkinning] is the CPU fix; this carries it to the
 * GPU. Each instance's palette is uploaded **twice** — as `mat4`s, and as dual quaternions plus a
 * residual 3×3 (see [DUAL_VEC4S_PER_JOINT], [packDualPalette]) — and a per-instance method word
 * says which the shader blends. So one draw call can hold linear-blend and dual-quaternion
 * characters side by side, which is how the difference is best seen.
 *
 * The split `M = T·R·S` stays on the CPU, and it is the oracle's own [DualQuaternionSkinning.split]:
 * it is `O(instances × bones)`, like the rest of the palette, and reusing it means the GPU adds only
 * the blend.
 *
 * ## Push-constant budget
 *
 * The stock glTF block is 176 bytes. This adds five buffer references and three uints, for
 * **228**. Vulkan only guarantees 128, so this is a MoltenVK/desktop-class assumption, not a
 * portable one; the machine it was measured on reports 4096. If it ever has to shrink, the three
 * uints fold into one packed word, the joint and weight buffers interleave into one, and the
 * method words can ride in the dual palette's unused `w` lanes.
 */
object GpuSkinning {

    /** Influences per vertex, fixed by [GltfSkinning.MAX_INFLUENCES] and by `uvec4`/`vec4`. */
    const val MAX_INFLUENCES: Int = GltfSkinning.MAX_INFLUENCES

    /** Floats per vertex in the packed buffer the bundled glTF shaders read. */
    const val VERTEX_STRIDE_FLOATS: Int = 12

    /** Invocations per workgroup in [verifyComputeShader]. */
    const val LOCAL_SIZE: Int = 64

    /**
     * `vec4`s per joint in the dual-quaternion palette: `real.xyzw`, `dual.xyzw`, then the
     * residual 3×3 `S` as three columns in JOML order (`m00 m01 m02`, …) with `w` unused.
     *
     * 80 bytes against the matrix palette's 64. Five and not four because the residual can be a
     * full 3×3: Gram-Schmidt leaves it upper-triangular, six floats, which *would* fit beside the
     * eight of the dual quaternion — but a degenerate joint (a basis axis scaled to nothing) hands
     * the whole 3×3 over as its residual, and a packing that could not carry it would be exact only
     * until the first collapsed bone.
     */
    const val DUAL_VEC4S_PER_JOINT: Int = 5

    /** Floats per joint in the dual-quaternion palette: [DUAL_VEC4S_PER_JOINT] × 4. */
    const val DUAL_FLOATS_PER_JOINT: Int = DUAL_VEC4S_PER_JOINT * 4

    /** Method word 0: linear blend skinning from the `mat4` palette. */
    const val METHOD_LINEAR: Int = 0

    /** Method word bit 0: dual quaternion blending from the dual palette. */
    const val METHOD_DUAL_QUATERNION: Int = 1

    /**
     * Method word bit 1: this instance's palette has at least one non-rigid joint, so the
     * residual 3×3 must be blended. Only meaningful with [METHOD_DUAL_QUATERNION].
     *
     * This is `DualQuaternionSkinning.skin`'s `anyNonRigid`, carried per instance: the oracle skips
     * the residual accumulation for a palette of pure rigid motions, and so does the shader, so
     * the two take the same arithmetic path rather than one of them blending identities.
     */
    const val METHOD_RESIDUAL_BIT: Int = 2

    /**
     * Write [palette] into [into] at [offset] in the dual-quaternion layout — [DUAL_FLOATS_PER_JOINT]
     * floats per joint — and return the method word the shader needs for it.
     *
     * The split is [DualQuaternionSkinning.split], the **oracle's own** factorisation, called
     * here rather than re-derived. So the only arithmetic the GPU adds is the blend, and a
     * disagreement between the shader and [DualQuaternionSkinning.skin] can only be a
     * disagreement about blending. Rigid joints get an identity residual, which is what the
     * oracle's `addScaled3` adds for them when some *other* joint needs the residual path.
     *
     * Both [GpuSkinner.uploadPalettes] and `GpuSkinningSmoke` pack through this, so the packing
     * the smoke grades is the packing the renderer draws.
     *
     * @return [METHOD_DUAL_QUATERNION], or'd with [METHOD_RESIDUAL_BIT] when any joint is non-rigid.
     */
    fun packDualPalette(palette: List<Matrix4f>, into: FloatArray, offset: Int = 0): Int {
        var nonRigid = false
        var o = offset
        for (m in palette) {
            val s = DualQuaternionSkinning.split(m)
            val r = s.dq.real
            val d = s.dq.dual
            into[o++] = r.x; into[o++] = r.y; into[o++] = r.z; into[o++] = r.w
            into[o++] = d.x; into[o++] = d.y; into[o++] = d.z; into[o++] = d.w
            val res = s.residual
            if (res == null) {
                into[o++] = 1f; into[o++] = 0f; into[o++] = 0f; into[o++] = 0f
                into[o++] = 0f; into[o++] = 1f; into[o++] = 0f; into[o++] = 0f
                into[o++] = 0f; into[o++] = 0f; into[o++] = 1f; into[o++] = 0f
            } else {
                nonRigid = true
                into[o++] = res.m00; into[o++] = res.m01; into[o++] = res.m02; into[o++] = 0f
                into[o++] = res.m10; into[o++] = res.m11; into[o++] = res.m12; into[o++] = 0f
                into[o++] = res.m20; into[o++] = res.m21; into[o++] = res.m22; into[o++] = 0f
            }
        }
        return METHOD_DUAL_QUATERNION or (if (nonRigid) METHOD_RESIDUAL_BIT else 0)
    }

    /**
     * The skinning maths, as GLSL: five buffer-reference types and six functions.
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
     *
     * `afSkinBlendDual` is the same for [DualQuaternionSkinning.skin], and the parts of *that*
     * which look like fussiness are not either:
     *
     * - The antipodal pivot is the **highest-weight** influence, the first one on a tie, compared
     *   with a strict `>` from zero — so at the fixture's 0.5/0.5 ring both sides pick the same
     *   pivot, and a shader that picked the last one on a tie would flip different signs.
     * - The residual `S` is blended **without** the sign flip. A shear has no antipode; flipping it
     *   with the quaternion would negate the mesh at every flipped vertex.
     * - Normalisation is `DualQuaternionf.normalize` to the letter, including the
     *   re-orthogonalisation `dual -= dot(real, dual) · real`, which is what keeps a blended dual
     *   part from carrying a spurious scale into the translation.
     * - It returns a **matrix**, `[R·S | t]`, so `afSkinPosition` and `afSkinNormal` are shared
     *   by both methods. That changes nothing about the normal: `R` is orthonormal, so
     *   `(R·S)⁻ᵀ = R·S⁻ᵀ`, which is the oracle's residual-inverse-transpose-then-rotate.
     *
     * `afSkinTransform` picks between the two per instance, from a method word
     * ([METHOD_LINEAR], [METHOD_DUAL_QUATERNION], [METHOD_RESIDUAL_BIT]). The branch is uniform
     * within an instance, so it only diverges in a subgroup that straddles two instances of
     * different methods — which a mixed crowd has, and pays for by running both blends there.
     */
    fun glsl(): String = """
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) readonly buffer AfSkinJoints  { uvec4 v[]; };
layout(std430, buffer_reference) readonly buffer AfSkinWeights { vec4  v[]; };
layout(std430, buffer_reference) readonly buffer AfSkinPalette { mat4  v[]; };
// $DUAL_VEC4S_PER_JOINT vec4 per (instance, bone): real, dual, then the residual 3x3 by columns.
layout(std430, buffer_reference) readonly buffer AfSkinDual    { vec4  v[]; };
// One word per instance: which blend it gets. See afSkinTransform.
layout(std430, buffer_reference) readonly buffer AfSkinMethods { uint  v[]; };

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

// Rotate v by the unit quaternion q (xyzw), as q v q*. The same arithmetic as the generated
// `transformQuaternionVector3` the CPU oracle calls, term for term.
vec3 afQuatRotate(vec4 q, vec3 v) {
    vec3 t = 2.0 * cross(q.xyz, v);
    return v + q.w * t + cross(q.xyz, t);
}

// Dual quaternion linear blending, transcribed from DualQuaternionSkinning.skin, and returned as
// the equivalent affine matrix [R * S | t] so afSkinPosition and afSkinNormal serve both methods.
// That is exact rather than convenient: R is orthonormal, so (R S)^-T = R S^-T, which is what the
// oracle does to a normal -- inverse transpose of the residual, then the rotation.
//
// `residual` is the oracle's `anyNonRigid`: false means every joint of this instance's palette is a
// pure rigid motion, and S is skipped entirely rather than blended from identities.
mat4 afSkinBlendDual(AfSkinJoints joints, AfSkinWeights weights, AfSkinDual dual,
                     uint vertex, uint paletteBase, bool residual) {
    uvec4 j = joints.v[vertex];
    vec4  w = weights.v[vertex];

    float sum = w.x + w.y + w.z + w.w;
    float norm = sum > 0.0 ? 1.0 / sum : 1.0;

    // The pivot is the HIGHEST-weight influence, first one on a tie -- strict '>' from 0, exactly as
    // the oracle -- so the sign convention does not depend on the order the bones arrived in.
    int pivot = -1;
    float best = 0.0;
    for (int i = 0; i < 4; i++) {
        if (w[i] > best) { best = w[i]; pivot = i; }
    }
    if (pivot < 0) return mat4(1.0);
    vec4 pivotReal = dual.v[(paletteBase + j[pivot]) * ${DUAL_VEC4S_PER_JOINT}u];

    vec4 real = vec4(0.0);
    vec4 du   = vec4(0.0);
    mat3 s    = mat3(0.0);
    for (int i = 0; i < 4; i++) {
        if (w[i] == 0.0) continue;
        float wi = w[i] * norm;
        uint e = (paletteBase + j[i]) * ${DUAL_VEC4S_PER_JOINT}u;
        vec4 r = dual.v[e];
        vec4 d = dual.v[e + 1u];
        // Antipodality: q and -q are one rotation and opposite dual quaternions. Flip any influence
        // on the far hemisphere from the pivot, or the joint ring blends toward zero.
        float sgn = dot(pivotReal, r) < 0.0 ? -1.0 : 1.0;
        real += r * (wi * sgn);
        du   += d * (wi * sgn);
        // S is blended linearly and WITHOUT the sign: a shear has no antipode.
        if (residual) s += mat3(dual.v[e + 2u].xyz, dual.v[e + 3u].xyz, dual.v[e + 4u].xyz) * wi;
    }

    // DualQuaternionf.normalize: identity below 1e-6 length, otherwise scale both parts by 1/|real|
    // and re-orthogonalise the dual part against the real one.
    float lenSq = dot(real, real);
    if (lenSq < 1e-12) {
        real = vec4(0.0, 0.0, 0.0, 1.0);
        du = vec4(0.0);
    } else {
        float inv = 1.0 / sqrt(lenSq);
        real *= inv;
        du *= inv;
        du -= dot(real, du) * real;
    }
    if (!residual) s = mat3(1.0);

    // Translation = 2 * (dual * conj(real)).xyz, Hamilton product, as DualQuaternionf.getTranslation.
    vec3 t = 2.0 * (real.w * du.xyz - du.w * real.xyz + cross(real.xyz, du.xyz));

    // Columns of R are the rotated basis, so R * v == afQuatRotate(real, v) by linearity.
    mat3 R = mat3(afQuatRotate(real, vec3(1.0, 0.0, 0.0)),
                  afQuatRotate(real, vec3(0.0, 1.0, 0.0)),
                  afQuatRotate(real, vec3(0.0, 0.0, 1.0)));
    mat3 rs = R * s;
    return mat4(vec4(rs[0], 0.0), vec4(rs[1], 0.0), vec4(rs[2], 0.0), vec4(t, 1.0));
}

// Whichever blend this instance asked for. `methods.v[instance]`: bit 0 = dual quaternion,
// bit 1 = this instance's palette has a non-rigid joint (blend the residual 3x3).
mat4 afSkinTransform(AfSkinJoints joints, AfSkinWeights weights, AfSkinPalette palette,
                     AfSkinDual dual, AfSkinMethods methods,
                     uint vertex, uint instance, uint boneCount) {
    uint m = methods.v[instance];
    uint base = instance * boneCount;
    if ((m & ${METHOD_DUAL_QUATERNION}u) != 0u) {
        return afSkinBlendDual(joints, weights, dual, vertex, base, (m & ${METHOD_RESIDUAL_BIT}u) != 0u);
    }
    return afSkinBlend(joints, weights, palette, vertex, base);
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
     * - `dualPaletteBuffer` and `methodBuffer`: the same palette as dual quaternions plus residual,
     *   and one word per instance choosing which of the two it is skinned by.
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
    AfSkinDual    dualPaletteBuffer;
    AfSkinMethods methodBuffer;

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
        mat4 blended = afSkinTransform(pc.jointBuffer, pc.weightBuffer, pc.paletteBuffer,
                                       pc.dualPaletteBuffer, pc.methodBuffer,
                                       idx, uint(gl_InstanceIndex), pc.boneCount);
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
    AfSkinDual    dualPaletteBuffer;
    AfSkinMethods methodBuffer;
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

    mat4 blended = afSkinTransform(pc.jointBuffer, pc.weightBuffer, pc.paletteBuffer,
                                   pc.dualPaletteBuffer, pc.methodBuffer,
                                   v, inst, pc.boneCount);

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
 * skinner.bindTo(mesh, camera)                         // once
 * loop {
 *     skinner.uploadPalettes((0 until 64).map { i ->
 *         skinner.placedPalette(model(i), "bend", t + i * 0.05f)
 *     })                                               // 64 x 7 matrices, not 64 x 50,000 vertices
 *     frame()
 * }
 * ```
 *
 * Linear blend by default. For the twisted joint linear blending cannot do, set
 * `skinner.method = GltfSkinning.SkinningMethod.DUAL_QUATERNION`, or hand [uploadPalettes] one
 * method per instance to put both in one draw:
 *
 * ```kotlin
 * skinner.uploadPalettes(palettes, methods = List(64) { i -> if (i % 2 == 0) LINEAR_BLEND else DUAL_QUATERNION })
 * ```
 *
 * The three things easy to get wrong, all of which this handles and none of which announce
 * themselves if you do them by hand:
 *
 * - **The model matrix must not be pushed.** `MeshInstance.applyToMesh` defaults `model` to the
 *   node's own transform, and glTF 2.0 §3.7.3.2 says a skinned mesh node's transform is
 *   ignored. [updateCamera] pushes the view alone; per-instance placement goes through
 *   [placedPalette].
 * - **The bind pose must be uploaded, not a posed one.** Sharing a `MeshInstance` with a
 *   [CpuSkinner] is therefore a trap: `CpuSkinner.pose` rewrites `instance.buffers` in place and
 *   the GPU would then skin already-skinned vertices. This snapshots the packed vertex buffer at
 *   construction and uploads the snapshot, so the two can coexist — `GpuSkinningEvidence` runs
 *   both against the same instance and photographs each.
 * - **The vertex upload happens once.** The GPU buffers belong to this object, not to the
 *   `MeshInstance`, so nothing re-uploads geometry per frame. The only per-frame write is the
 *   palette.
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

    /**
     * The same `instances × boneCount` palette as dual quaternions plus residual —
     * [GpuSkinning.DUAL_FLOATS_PER_JOINT] floats a joint. Written by [uploadPalettes] only for
     * the instances that are dual-quaternion skinned; the rest of it is never read.
     */
    val dualPaletteBuffer: FrameBufferGroup = buffer(name = "${name}DualPalette") {
        size = instances.toLong() * boneCount * GpuSkinning.DUAL_FLOATS_PER_JOINT * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    /** One `uint` per instance: which blend the shader uses for it. Written by [uploadPalettes]. */
    val methodBuffer: FrameBufferGroup = buffer(name = "${name}Methods") {
        size = instances.toLong() * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    /**
     * The blend every instance gets unless [uploadPalettes] is handed a per-instance list.
     * Linear blend by default, matching [GltfSkinning.SkinningOptions] and every other engine.
     *
     * Takes effect at the next [uploadPalettes], because the method word also carries whether that
     * palette has a non-rigid joint, and only an upload knows.
     */
    var method: GltfSkinning.SkinningMethod = GltfSkinning.SkinningMethod.LINEAR_BLEND

    /** Scratch for [uploadPalettes]; sized once. */
    private val dualScratch = FloatArray(instances * boneCount * GpuSkinning.DUAL_FLOATS_PER_JOINT)
    private val methodScratch = IntArray(instances)

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
        // Every instance linear until told otherwise, rather than whatever the allocation held.
        methodBuffer.get(write = true).mapAndWrite_ints {
            for (i in 0 until instances) put(GpuSkinning.METHOD_LINEAR)
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
     * not a slerp, and it is worth being exact about which problem [method] does and does not
     * fix. Dual-quaternion *skinning* fixes the blend **between a vertex's influences** — the
     * candy wrapper at a twisted joint. It does nothing for this blend, which is **between two
     * clips' matrices for one joint**, and happens before skinning sees the palette: a lerp of
     * two very different rotations is a matrix that has already shrunk, and
     * [DualQuaternionSkinning.split] then faithfully carries that shrink as a residual scale. For
     * clips that disagree by a large rotation, blend the clips' rotations (a slerp per joint)
     * rather than their matrices; that is not done here.
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
     *
     * @param methods the blend for each instance, `methods[i]` for `palettes[i]`. Defaults to
     *   [method] for all of them. A dual-quaternion instance additionally costs one
     *   [DualQuaternionSkinning.split] per bone here — a Gram-Schmidt on a 3×3 — which is still
     *   `O(instances × bones)` and still nothing next to the vertices.
     */
    fun uploadPalettes(
        palettes: List<List<Matrix4f>>,
        methods: List<GltfSkinning.SkinningMethod> = List(palettes.size) { method },
    ) {
        require(palettes.size <= instances) {
            "${palettes.size} palettes for a skinner sized for $instances instances"
        }
        require(methods.size == palettes.size) {
            "${methods.size} methods for ${palettes.size} palettes; give one per instance"
        }
        for ((i, p) in palettes.withIndex()) require(p.size == boneCount) {
            "palette $i has ${p.size} matrices, this skeleton has $boneCount bones"
        }
        paletteBuffer.get(write = true).mapAndWrite {
            for (p in palettes) for (m in p) {
                for (n in 0 until 16) put(m[n])
            }
        }
        var anyDual = false
        for ((i, p) in palettes.withIndex()) {
            methodScratch[i] = if (methods[i] == GltfSkinning.SkinningMethod.DUAL_QUATERNION) {
                anyDual = true
                GpuSkinning.packDualPalette(p, dualScratch, i * boneCount * GpuSkinning.DUAL_FLOATS_PER_JOINT)
            } else {
                GpuSkinning.METHOD_LINEAR
            }
        }
        // An all-linear crowd skips the second palette write entirely.
        if (anyDual) {
            val floats = palettes.size * boneCount * GpuSkinning.DUAL_FLOATS_PER_JOINT
            dualPaletteBuffer.get(write = true).mapAndWrite { for (n in 0 until floats) put(dualScratch[n]) }
        }
        methodBuffer.get(write = true).mapAndWrite_ints {
            for (i in palettes.indices) put(methodScratch[i])
        }
    }

    /**
     * The method word the shader will see for each of the first `count` instances after the last
     * [uploadPalettes] — [GpuSkinning.METHOD_LINEAR], or [GpuSkinning.METHOD_DUAL_QUATERNION]
     * with [GpuSkinning.METHOD_RESIDUAL_BIT] when that palette had a non-rigid joint.
     */
    fun methodWords(count: Int = instances): IntArray = methodScratch.copyOf(count)

    /** One palette, for the common single-character case. */
    fun uploadPalette(palette: List<Matrix4f>) = uploadPalettes(listOf(palette))

    /**
     * The bind-pose vertex data and the indices, held here rather than on the [GltfSupport.MeshInstance].
     *
     * `MeshInstance.applyToMesh` would do this, and deliberately is not used. Two reasons, and
     * the second is the one that matters:
     *
     * - Its buffers come from `defaultBuffer { … }.buffer`, whose renderer defaults to
     *   `fromContext()`. Inside a box that resolves; from a `main` — which is where the evidence
     *   renderer and every smoke live — it throws `Cannot invoke "afield.Box.getProperties()"`.
     *   Holding them here with an explicit renderer makes one class work in both.
     * - `applyToMesh` re-uploads the whole vertex buffer on every call, because the CPU path has
     *   to. This path uploads it **once**, which is the entire point, so the upload belongs to
     *   the object whose lifetime matches it.
     */
    private val vertexBuffer: FrameBufferGroup = buffer(name = "${name}Vertices") {
        size = vertexCount.toLong() * GpuSkinning.VERTEX_STRIDE_FLOATS * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    private val indexBuffer: FrameBufferGroup = buffer(name = "${name}Indices") {
        size = instance.buffers.indexCount * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    init {
        // The bind pose, from the snapshot rather than from `instance.buffers`: a CpuSkinner
        // sharing this MeshInstance may already have posed it in place, and uploading a posed
        // mesh would make the GPU skin it a second time.
        vertexBuffer.get(write = true).mapAndWrite { put(bindVertexData) }
        indexBuffer.get(write = true).mapAndWrite_ints {
            for (n in 0 until instance.buffers.indexCount) put(instance.buffers.indexData.get(n))
        }
    }

    /**
     * Point [mesh] at this skinner and set every push constant
     * [GpuSkinning.skinnedVertexShader] needs. Call once; after this only [uploadPalettes] and, if
     * the camera moves, [updateCamera] are per-frame.
     *
     * @param instanceCount how many characters to draw. Sets `mesh.instanceCount`, which
     *   `Mesh.kt` already passes to `vkCmdDraw` — so a crowd is one draw call and needs no change
     *   to `standardRenderer.kt` or to any renderer code.
     * @param tint multiply in a per-instance hue, so a crowd reads as a crowd.
     */
    fun bindTo(
        mesh: Mesh,
        camera: Camera = fromContext(),
        instanceCount: Int = instances,
        tint: Boolean = instances > 1,
    ) {
        require(instanceCount in 1..instances) { "instanceCount $instanceCount is outside 1..$instances" }

        mesh.initializePushConstantsFromModule(mesh.inside)
        mesh.push["vertexOffset"] = 0
        mesh.push["indexOffset"] = 0
        mesh.push["vertexStride"] = GpuSkinning.VERTEX_STRIDE_FLOATS
        mesh.push["materialBaseColor"] = instance.material.baseColor
        mesh.push["useVertexColor"] = 1
        mesh.push.bind("vertexBuffer") { vertexBuffer }
        mesh.push.bind("indexBuffer") { indexBuffer }

        mesh.push.bind("jointBuffer") { jointBuffer }
        mesh.push.bind("weightBuffer") { weightBuffer }
        mesh.push.bind("paletteBuffer") { paletteBuffer }
        mesh.push.bind("dualPaletteBuffer") { dualPaletteBuffer }
        mesh.push.bind("methodBuffer") { methodBuffer }
        mesh.push["boneCount"] = boneCount
        mesh.push["skinEnabled"] = 1
        mesh.push["instanceTint"] = if (tint) 1 else 0

        updateCamera(mesh, camera)
        mesh.resize(instance.buffers.indexCount)
        mesh.instanceCount = instanceCount
    }

    /**
     * Push the camera. `modelView` is **view alone**, with no model matrix: glTF 2.0 §3.7.3.2
     * says a skinned mesh node's own transform is ignored, and each instance's placement is
     * already inside its palette. Composing a model matrix in here would apply it twice.
     */
    fun updateCamera(mesh: Mesh, camera: Camera = fromContext()) {
        mesh.push["modelView"] = camera.m()
        mesh.push["proj"] = camera.p()
    }

    /**
     * Hand [mesh] back to the stock, un-skinned path: the bind pose as `instance.buffers` holds
     * it *now*, drawn by the `skinEnabled == 0` branch.
     *
     * This is how the evidence renderer photographs the CPU path through the identical pipeline:
     * pose with a [CpuSkinner], call this, and the shader passes the already-deformed vertices
     * straight through.
     */
    fun uploadPosedVertices(mesh: Mesh) {
        val f = instance.buffers.vertexData.asFloatBuffer()
        vertexBuffer.get(write = true).mapAndWrite {
            for (n in 0 until vertexCount * GpuSkinning.VERTEX_STRIDE_FLOATS) put(f.get(n))
        }
        mesh.push["skinEnabled"] = 0
    }

    /** Put the bind pose back in the GPU buffer and re-enable GPU skinning. */
    fun restoreBindPose(mesh: Mesh) {
        vertexBuffer.get(write = true).mapAndWrite { put(bindVertexData) }
        mesh.push["skinEnabled"] = 1
    }

}
