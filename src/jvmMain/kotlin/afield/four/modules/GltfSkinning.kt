@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.four.modules.GltfSupport.TRS
import afield.four.modules.GltfSupport.TRSSample
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import org.lwjgl.assimp.AIAnimation
import org.lwjgl.assimp.AIBone
import org.lwjgl.assimp.AIMatrix4x4
import org.lwjgl.assimp.AIMesh
import org.lwjgl.assimp.AINode
import org.lwjgl.assimp.AINodeAnim
import org.lwjgl.assimp.AIScene

/**
 * Skeletal animation: bones, joint palettes and linear blend skinning, on the CPU.
 *
 * [GltfSupport] has always loaded glTF through Assimp and has always sampled **per-node** TRS
 * animation — `MeshInstance.animationSamples`, `buildAnimationSamplesForNode`. What it has never had
 * is *bones*: a vertex that belongs partly to one joint and partly to another. Before this file the
 * only mention of a bone in `src/jvmMain` + `src/commonMain` was `PathTraceGltf.kt`'s refusal to
 * trace a skinned mesh, which names the gap in its own error message.
 *
 * ### The shape of the model, and why it is a node tree and not a bone tree
 *
 * Assimp hands over `aiBone.mName()` and `aiBone.mOffsetMatrix()`. It does **not** hand over a bone
 * parent: the hierarchy lives in the `aiNode` tree, where a bone is just a node that some mesh
 * happens to weight vertices to. Two consequences drove the design here:
 *
 *  - A bone's parent chain can run through nodes that are **not** bones (glTF armature/"Armature"
 *    wrapper nodes are the common case, and Blender emits them by default). Indexing the skeleton by
 *    bone would lose those transforms.
 *  - The skeleton root is routinely not the mesh's own node, which is exactly the case the brief
 *    called out.
 *
 * So [Skeleton] indexes **every node in the scene** ([Skeleton.nodes], pre-order, each with a parent
 * index and its rest-pose local matrix), and a bone is the pair `(nodeIndex, inverseBind)` in
 * [Skeleton.bones]. Per-vertex joint indices address `bones`. Composition down the parent chain is
 * then one left-to-right pass over `nodes`, because a pre-order listing guarantees a parent is
 * visited before its children.
 *
 * ### The formula
 *
 * For a vertex `p` in mesh space with influences `(bᵢ, wᵢ)`:
 *
 * ```
 * palette[b] = global(node(b), t) · inverseBind(b)
 * p'         = Σᵢ wᵢ · palette[bᵢ] · p
 * ```
 *
 * where `global(n, t)` is the node's world matrix with each animated node's local transform replaced
 * by the clip's sample at `t`. The result is in **scene space**, not mesh-node space: glTF 2.0
 * §3.7.3.2 says a skinned mesh node's own transform is ignored, and Assimp's offset matrices follow
 * that convention. So a caller must *not* also apply `MeshInstance.bindMatrix` — see
 * [skinnedGltfToTriMesh], which is why it exists rather than a flag on `assimpToTriMesh`.
 *
 * ### Normals
 *
 * Normals are transformed by the **inverse transpose** of the blended 3×3, computed per vertex and
 * exactly — not approximated by the blended matrix itself. That approximation is exact for a rigid
 * joint and it is exact for a *single-influence* vertex of a rigid skeleton, which covers most of a
 * typical mesh; it is wrong precisely on the blended band around a joint, which is the part of a
 * skinned character anyone looks at. The cost is one 3×3 inversion per vertex; if that ever matters,
 * [SkinningOptions.exactNormalMatrix] turns it off and says so. When the blended matrix is singular
 * (all weights zero, or a degenerate blend) the plain 3×3 is used as the fallback.
 *
 * ### Influences past four
 *
 * glTF allows more than four influences per vertex via repeated `JOINTS_n`/`WEIGHTS_n` sets, and
 * Assimp imposes no limit at all. This caps at [MAX_INFLUENCES] = 4, keeping the **largest** weights
 * and renormalising so the kept weights sum to 1. The discard is counted and measured rather than
 * silent: [VertexInfluences.discardedInfluences] and [VertexInfluences.largestDiscardedWeight] are
 * what a caller checks to find out whether the cap bit.
 */
object GltfSkinning {

    /** Influences kept per vertex. glTF's own baseline, and what every GPU skinning path assumes. */
    const val MAX_INFLUENCES: Int = 4

    // ------------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------------

    /**
     * One node of the scene hierarchy, in the rest pose.
     *
     * [local] is kept as a full [Matrix4f] rather than a [TRS] for the reason
     * `GltfSupport.MeshInstance.bindMatrix` exists: a TRS cannot carry a mirror or a shear, and
     * `decomposeToTRS` flattens a mirrored node to a pure translation without saying so.
     */
    class SkeletonNode(
        val name: String,
        /** Index into [Skeleton.nodes], or `-1` for the scene root. Always less than this node's own index. */
        val parent: Int,
        val local: Matrix4f,
    ) {
        override fun toString(): String = "SkeletonNode($name, parent=$parent)"
    }

    /** A node some mesh weights vertices to, with the matrix that takes mesh space into its bind space. */
    class Bone(
        val name: String,
        /** Index into [Skeleton.nodes]. */
        val node: Int,
        /** Assimp's `aiBone.mOffsetMatrix`; glTF's inverse bind matrix. */
        val inverseBind: Matrix4f,
    ) {
        override fun toString(): String = "Bone($name, node=$node)"
    }

    /** One animated node within a clip: the node's local TRS, sampled at [AnimationClip.sampleRate]. */
    class JointChannel(
        /** Index into [Skeleton.nodes]. */
        val node: Int,
        val samples: List<TRSSample>,
    )

    /**
     * A named clip: for each animated node, its local TRS sampled on a uniform grid.
     *
     * Resampled onto a grid rather than kept as glTF keyframes because that is what
     * `GltfSupport.buildAnimationSamplesForNode` already does for the rigid path, and matching it
     * means the two paths cannot disagree about what "time `t`" means.
     */
    class AnimationClip(
        val name: String,
        val durationSeconds: Float,
        val sampleRate: Float,
        val channels: List<JointChannel>,
    ) {
        /** The animated node's local matrix at [seconds], or `null` if this clip does not touch it. */
        fun localAt(node: Int, seconds: Float): Matrix4f? =
            channels.firstOrNull { it.node == node }?.let { sampleAt(it.samples, seconds).toMatrix() }

        override fun toString(): String =
            "AnimationClip($name, ${"%.3f".format(durationSeconds)}s, ${channels.size} channels)"
    }

    /**
     * The hierarchy, the bones and the clips — everything needed to build a joint palette at a time.
     *
     * Immutable, and deliberately shareable: several `MeshInstance`s of one file point at the same
     * instance rather than each carrying a copy of the scene's node tree.
     */
    class Skeleton(
        val nodes: List<SkeletonNode>,
        val bones: List<Bone>,
        val clips: List<AnimationClip>,
    ) {
        /** Every node's rest-pose world matrix, composed down the parent chain. */
        val restGlobals: List<Matrix4f> = buildGlobals(null, 0f)

        /**
         * Every node's world matrix at [seconds] under [clip], animated nodes taking their sampled
         * local transform and every other node keeping its rest local transform.
         *
         * One left-to-right pass: [nodes] is pre-order, so a parent's global is already computed.
         */
        fun buildGlobals(clip: AnimationClip?, seconds: Float): List<Matrix4f> {
            val out = ArrayList<Matrix4f>(nodes.size)
            for ((i, n) in nodes.withIndex()) {
                val local = clip?.localAt(i, seconds) ?: Matrix4f(n.local)
                val parent = if (n.parent >= 0) out[n.parent] else null
                out += if (parent == null) local else Matrix4f(parent).mul(local)
            }
            return out
        }

        /**
         * The joint matrix palette at [seconds]: one matrix per entry of [bones], in that order, each
         * taking a mesh-space position to its skinned scene-space position.
         *
         * At the bind pose this is the identity for every bone, **exactly** when the file's numbers
         * allow it — which is the point of `RiggedFixture`'s choice of constants and the reason
         * `GltfSkinningTest` can assert bit-identity rather than a tolerance.
         */
        fun palette(clip: AnimationClip?, seconds: Float): List<Matrix4f> {
            val globals = buildGlobals(clip, seconds)
            return bones.map { Matrix4f(globals[it.node]).mul(it.inverseBind) }
        }

        /** The palette for the clip named [clipName]; the rest pose if there is no such clip. */
        fun palette(clipName: String, seconds: Float): List<Matrix4f> =
            palette(clips.firstOrNull { it.name == clipName }, seconds)

        /** A blend of two clips' palettes — `mix = 0` is all [a], `mix = 1` is all [b]. */
        fun blendedPalette(a: AnimationClip?, aSeconds: Float, b: AnimationClip?, bSeconds: Float, mix: Float): List<Matrix4f> {
            val pa = palette(a, aSeconds)
            val pb = palette(b, bSeconds)
            val m = mix.coerceIn(0f, 1f)
            // Blended componentwise, which is the same linear blend LBS already commits to below --
            // not a quaternion blend. It shortens a limb at 90 degrees exactly as LBS does.
            return pa.indices.map { i -> lerpMatrix(pa[i], pb[i], m) }
        }

        fun clip(name: String): AnimationClip? = clips.firstOrNull { it.name == name }

        override fun toString(): String =
            "Skeleton(${nodes.size} nodes, ${bones.size} bones, clips ${clips.map { it.name }})"
    }

    /**
     * Per-vertex joint indices and weights, four-wide and flat.
     *
     * `jointIndex[v * 4 + k]` addresses [Skeleton.bones]; `weight[v * 4 + k]` is its weight. Unused
     * slots carry index `0` and weight `0`, so a consumer never needs a count.
     */
    class VertexInfluences(
        val vertexCount: Int,
        val jointIndex: IntArray,
        val weight: FloatArray,
        /** How many influences the four-wide cap threw away, over the whole mesh. */
        val discardedInfluences: Int,
        /** The largest single weight the cap threw away. `0` when it never bit. */
        val largestDiscardedWeight: Float,
    ) {
        /** The sum of this vertex's weights. `1` to within float rounding when the mesh is sane. */
        fun weightSum(vertex: Int): Float {
            var s = 0f
            for (k in 0 until MAX_INFLUENCES) s += weight[vertex * MAX_INFLUENCES + k]
            return s
        }

        /** The worst `|Σw - 1|` over every vertex — one number a test can floor. */
        fun worstWeightSumError(): Float {
            var worst = 0f
            for (v in 0 until vertexCount) {
                val e = kotlin.math.abs(weightSum(v) - 1f)
                if (e > worst) worst = e
            }
            return worst
        }

        /** Vertices with no influence at all — they will sit at the origin if skinned naively. */
        fun unweightedVertices(): Int = (0 until vertexCount).count { weightSum(it) <= 0f }
    }

    /** A mesh's skin: which skeleton, and how its vertices attach to it. */
    class MeshSkin(
        val skeleton: Skeleton,
        val influences: VertexInfluences,
    ) {
        override fun toString(): String =
            "MeshSkin(${influences.vertexCount} vertices, $skeleton)"
    }

    /**
     * How [skin] averages the influences of a vertex that several joints pull on.
     *
     * The default is [LINEAR_BLEND] because it is what every GPU skinning path and every other
     * engine defaults to, and changing that silently would make this codebase disagree with the
     * files it loads. [DUAL_QUATERNION] is strictly better under twist and slightly worse under a
     * stretched joint; `DualQuaternionSkinningTest` measures both directions rather than asserting
     * one is better.
     */
    enum class SkinningMethod {
        /** Average the joint matrices. Fast, universal, and pinches a half-twisted joint ring to a point. */
        LINEAR_BLEND,

        /**
         * Average the joint *motions* as unit dual quaternions — see [DualQuaternionSkinning]. Keeps
         * a twisted joint round; bulges slightly where linear blending pinches.
         */
        DUAL_QUATERNION,
    }

    /** Knobs on [skin]. */
    data class SkinningOptions(
        /** Which blend [skin] performs. See [SkinningMethod]. */
        val method: SkinningMethod = SkinningMethod.LINEAR_BLEND,
        /**
         * Transform normals by the exact inverse transpose of the blended 3×3 rather than by the
         * blended 3×3 itself. On by default; see the class comment for what turning it off costs.
         */
        val exactNormalMatrix: Boolean = true,
        /**
         * Renormalise each vertex's weights to sum to 1 before blending. On by default. Turn it off to
         * see what the file actually says — a mesh whose weights sum to 0.9 will shrink toward the
         * origin, which is a real bug worth being able to look at.
         */
        val renormalize: Boolean = true,
    )

    /** What [skin] produced: deformed positions and normals, plus what it had to notice on the way. */
    class SkinnedVertices(
        val vertexCount: Int,
        /** `3 * vertexCount`, scene space. */
        val position: FloatArray,
        /** `3 * vertexCount`, unit length, or `null` when the input had no normals. */
        val normal: FloatArray?,
        /** Vertices whose blended 3×3 could not be inverted, so the plain 3×3 was used for the normal. */
        val singularNormalMatrices: Int,
        /**
         * Palette entries that carried scale, shear or a reflection. Always 0 for
         * [SkinningMethod.LINEAR_BLEND], which needs no such split; for
         * [SkinningMethod.DUAL_QUATERNION] it is how many joints needed the residual path, and a
         * caller that expected a rigid rig can assert it is 0.
         */
        val nonRigidJoints: Int = 0,
        /**
         * Influences whose sign was flipped to match their vertex's pivot. Always 0 for
         * [SkinningMethod.LINEAR_BLEND]. A non-zero count here is antipodality handling doing its
         * job, not a warning.
         */
        val antipodalFlips: Int = 0,
    )

    // ------------------------------------------------------------------------
    // Linear blend skinning
    // ------------------------------------------------------------------------

    /**
     * Linear blend skinning: [positions] and [normals] deformed by [palette] under [influences].
     *
     * @param positions `3 * vertexCount` mesh-space floats.
     * @param normals `3 * vertexCount` mesh-space floats, or `null`.
     * @param palette one matrix per bone, as from [Skeleton.palette].
     */
    fun skin(
        positions: FloatArray,
        normals: FloatArray?,
        influences: VertexInfluences,
        palette: List<Matrix4f>,
        options: SkinningOptions = SkinningOptions(),
    ): SkinnedVertices {
        if (options.method == SkinningMethod.DUAL_QUATERNION) {
            return DualQuaternionSkinning.skin(positions, normals, influences, palette, options)
        }

        val n = influences.vertexCount
        require(positions.size >= n * 3) { "positions has ${positions.size} floats, need ${n * 3}" }
        require(normals == null || normals.size >= n * 3) { "normals has ${normals?.size} floats, need ${n * 3}" }

        val outP = FloatArray(n * 3)
        val outN = if (normals != null) FloatArray(n * 3) else null
        val blended = Matrix4f()
        val nm = Matrix3f()
        var singular = 0

        for (v in 0 until n) {
            var sum = 0f
            for (k in 0 until MAX_INFLUENCES) sum += influences.weight[v * MAX_INFLUENCES + k]
            val norm = if (options.renormalize && sum > 0f) 1f / sum else 1f

            // Accumulate the weighted matrix rather than the weighted transformed position: identical
            // for positions, and it is the only way to get the blended matrix the normal needs.
            blended.zero()
            var contributed = false
            for (k in 0 until MAX_INFLUENCES) {
                val w = influences.weight[v * MAX_INFLUENCES + k]
                if (w == 0f) continue
                val m = palette[influences.jointIndex[v * MAX_INFLUENCES + k]]
                addScaled(blended, m, w * norm)
                contributed = true
            }
            if (!contributed) blended.identity()

            val px = positions[v * 3]; val py = positions[v * 3 + 1]; val pz = positions[v * 3 + 2]
            outP[v * 3] = blended.m00 * px + blended.m10 * py + blended.m20 * pz + blended.m30
            outP[v * 3 + 1] = blended.m01 * px + blended.m11 * py + blended.m21 * pz + blended.m31
            outP[v * 3 + 2] = blended.m02 * px + blended.m12 * py + blended.m22 * pz + blended.m32

            if (normals != null && outN != null) {
                blended.get3x3(nm)
                if (options.exactNormalMatrix) {
                    val d = nm.determinant()
                    if (kotlin.math.abs(d) > 1e-20f && d.isFinite()) {
                        nm.invert().transpose()
                    } else {
                        singular++
                    }
                }
                val nx = normals[v * 3]; val ny = normals[v * 3 + 1]; val nz = normals[v * 3 + 2]
                var tx = nm.m00 * nx + nm.m10 * ny + nm.m20 * nz
                var ty = nm.m01 * nx + nm.m11 * ny + nm.m21 * nz
                var tz = nm.m02 * nx + nm.m12 * ny + nm.m22 * nz
                val len = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
                if (len > 1e-12f) { tx /= len; ty /= len; tz /= len }
                outN[v * 3] = tx; outN[v * 3 + 1] = ty; outN[v * 3 + 2] = tz
            }
        }
        return SkinnedVertices(n, outP, outN, singular)
    }

    private fun addScaled(into: Matrix4f, m: Matrix4f, w: Float) {
        into.m00 += m.m00 * w; into.m01 += m.m01 * w; into.m02 += m.m02 * w; into.m03 += m.m03 * w
        into.m10 += m.m10 * w; into.m11 += m.m11 * w; into.m12 += m.m12 * w; into.m13 += m.m13 * w
        into.m20 += m.m20 * w; into.m21 += m.m21 * w; into.m22 += m.m22 * w; into.m23 += m.m23 * w
        into.m30 += m.m30 * w; into.m31 += m.m31 * w; into.m32 += m.m32 * w; into.m33 += m.m33 * w
    }

    private fun lerpMatrix(a: Matrix4f, b: Matrix4f, t: Float): Matrix4f {
        val out = Matrix4f().zero()
        addScaled(out, a, 1f - t)
        addScaled(out, b, t)
        return out
    }

    // ------------------------------------------------------------------------
    // Assimp extraction
    // ------------------------------------------------------------------------

    /**
     * Walk the whole scene's node tree into a flat pre-order list with parent indices.
     *
     * Pre-order matters: [Skeleton.buildGlobals] relies on a parent always appearing before its
     * children so it can compose in one pass.
     */
    fun readNodes(scene: AIScene): List<SkeletonNode> {
        val out = ArrayList<SkeletonNode>()
        fun walk(node: AINode, parent: Int) {
            val index = out.size
            out += SkeletonNode(node.mName().dataString(), parent, toMatrix4f(node.mTransformation()))
            val children = node.mChildren() ?: return
            for (i in 0 until node.mNumChildren()) walk(AINode.create(children[i]), index)
        }
        scene.mRootNode()?.let { walk(it, -1) }
        return out
    }

    /**
     * Every animation in the file, resampled onto a uniform grid at [sampleRate] Hz.
     *
     * Deliberately *all* of them: `GltfSupport.buildAnimationSamplesForNode` takes only
     * `mAnimations()[0]`, which is fine for a file with one clip and silently drops the rest.
     */
    fun readClips(scene: AIScene, nodes: List<SkeletonNode>, sampleRate: Float): List<AnimationClip> {
        val count = scene.mNumAnimations()
        if (count == 0) return emptyList()
        val animations = scene.mAnimations() ?: return emptyList()
        val byName = HashMap<String, Int>()
        for ((i, n) in nodes.withIndex()) byName.putIfAbsent(n.name, i)

        return (0 until count).mapNotNull { a ->
            val anim = AIAnimation.create(animations[a])
            val ticksPerSecond = if (anim.mTicksPerSecond() != 0.0) anim.mTicksPerSecond() else 25.0
            val durationTicks = anim.mDuration()
            if (durationTicks <= 0.0 || ticksPerSecond <= 0.0) return@mapNotNull null
            val durationSeconds = (durationTicks / ticksPerSecond).toFloat()
            val channels = anim.mChannels() ?: return@mapNotNull null
            val out = ArrayList<JointChannel>()
            for (c in 0 until anim.mNumChannels()) {
                val ch = AINodeAnim.create(channels[c])
                val node = byName[ch.mNodeName().dataString()] ?: continue
                out += JointChannel(node, sampleChannel(ch, ticksPerSecond, durationSeconds, sampleRate))
            }
            AnimationClip(
                name = anim.mName().dataString().ifBlank { "animation-$a" },
                durationSeconds = durationSeconds,
                sampleRate = sampleRate,
                channels = out,
            )
        }
    }

    /**
     * The bones of one Assimp mesh, plus its per-vertex influences.
     *
     * `null` when the mesh has no bones at all, which is the ordinary rigid case and is how a caller
     * tells "not skinned" from "skinned with everything at weight zero".
     */
    fun readMeshSkin(mesh: AIMesh, nodes: List<SkeletonNode>, clips: List<AnimationClip>): MeshSkin? {
        val boneCount = mesh.mNumBones()
        if (boneCount == 0) return null
        val boneBuffer = mesh.mBones() ?: return null
        val byName = HashMap<String, Int>()
        for ((i, n) in nodes.withIndex()) byName.putIfAbsent(n.name, i)

        val bones = ArrayList<Bone>(boneCount)
        // Gathered per vertex first, then sorted and capped, because Assimp stores weights bone-major
        // and the cap has to see all of a vertex's influences to keep the largest.
        val gathered = Array(mesh.mNumVertices()) { ArrayList<Pair<Int, Float>>(MAX_INFLUENCES) }

        for (b in 0 until boneCount) {
            val bone = AIBone.create(boneBuffer[b])
            val name = bone.mName().dataString()
            val node = byName[name]
            require(node != null) { "glTF bone '$name' names no node in the scene hierarchy" }
            bones += Bone(name, node, toMatrix4f(bone.mOffsetMatrix()))
            val weights = bone.mWeights()
            for (w in 0 until bone.mNumWeights()) {
                val vw = weights[w]
                val v = vw.mVertexId()
                if (v < gathered.size && vw.mWeight() != 0f) gathered[v] += b to vw.mWeight()
            }
        }

        val jointIndex = IntArray(gathered.size * MAX_INFLUENCES)
        val weight = FloatArray(gathered.size * MAX_INFLUENCES)
        var discarded = 0
        var largestDiscarded = 0f
        for (v in gathered.indices) {
            val list = gathered[v]
            if (list.size > MAX_INFLUENCES) {
                list.sortByDescending { it.second }
                for (k in MAX_INFLUENCES until list.size) {
                    discarded++
                    if (list[k].second > largestDiscarded) largestDiscarded = list[k].second
                }
            }
            var kept = 0f
            val n = minOf(list.size, MAX_INFLUENCES)
            for (k in 0 until n) kept += list[k].second
            for (k in 0 until n) {
                jointIndex[v * MAX_INFLUENCES + k] = list[k].first
                // Renormalise only when the cap actually threw something away. Otherwise leave the
                // file's own numbers alone, so a test can measure what the file says rather than what
                // this function wishes it said.
                weight[v * MAX_INFLUENCES + k] =
                    if (list.size > MAX_INFLUENCES && kept > 0f) list[k].second / kept else list[k].second
            }
        }

        return MeshSkin(
            Skeleton(nodes, bones, clips),
            VertexInfluences(gathered.size, jointIndex, weight, discarded, largestDiscarded),
        )
    }

    // ------------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------------

    /** The TRS at [seconds] in a uniformly-sampled channel; clamped at both ends, lerp/slerp between. */
    fun sampleAt(samples: List<TRSSample>, seconds: Float): TRS {
        if (samples.isEmpty()) return TRS(Vector3f(), Quaternionf(), Vector3f(1f, 1f, 1f))
        if (samples.size == 1 || seconds <= samples.first().time) return samples.first().trs
        if (seconds >= samples.last().time) return samples.last().trs
        var i = 0
        while (i < samples.size - 1 && samples[i + 1].time <= seconds) i++
        val a = samples[i]
        val b = samples[i + 1]
        val span = (b.time - a.time).coerceAtLeast(1e-8f)
        val f = ((seconds - a.time) / span).coerceIn(0f, 1f)
        return TRS(
            Vector3f(a.trs.t).lerp(b.trs.t, f),
            Quaternionf(a.trs.r).slerp(b.trs.r, f).normalize(),
            Vector3f(a.trs.s).lerp(b.trs.s, f),
        )
    }

    private fun sampleChannel(
        ch: AINodeAnim, ticksPerSecond: Double, durationSeconds: Float, sampleRate: Float,
    ): List<TRSSample> {
        val dt = 1f / sampleRate
        val out = ArrayList<TRSSample>()
        var t = 0f
        while (t <= durationSeconds + 1e-4f) {
            val tick = t * ticksPerSecond
            out += TRSSample(t, TRS(keyVec3(ch, tick, true), keyQuat(ch, tick), keyVec3(ch, tick, false)))
            t += dt
        }
        return out
    }

    private fun keyVec3(ch: AINodeAnim, tick: Double, position: Boolean): Vector3f {
        val keys = if (position) ch.mPositionKeys() else ch.mScalingKeys()
        val count = if (position) ch.mNumPositionKeys() else ch.mNumScalingKeys()
        val fallback = if (position) 0f else 1f
        if (keys == null || count == 0) return Vector3f(fallback, fallback, fallback)
        var i = 0
        while (i < count - 1 && tick >= keys[i + 1].mTime()) i++
        val a = keys[i].mValue()
        if (i == count - 1) return Vector3f(a.x(), a.y(), a.z())
        val bk = keys[i + 1]
        val bv = bk.mValue()
        val span = (bk.mTime() - keys[i].mTime()).coerceAtLeast(1e-8)
        val f = ((tick - keys[i].mTime()) / span).coerceIn(0.0, 1.0).toFloat()
        return Vector3f(a.x() + (bv.x() - a.x()) * f, a.y() + (bv.y() - a.y()) * f, a.z() + (bv.z() - a.z()) * f)
    }

    private fun keyQuat(ch: AINodeAnim, tick: Double): Quaternionf {
        val keys = ch.mRotationKeys()
        val count = ch.mNumRotationKeys()
        if (keys == null || count == 0) return Quaternionf()
        var i = 0
        while (i < count - 1 && tick >= keys[i + 1].mTime()) i++
        val a = keys[i].mValue()
        // At an exact key the result must be the key itself, bit for bit -- a slerp with f = 0 would
        // renormalise and drift, and the bind-pose identity assertion is measured at exactly t = 0.
        if (i == count - 1 || tick <= keys[i].mTime()) return Quaternionf(a.x(), a.y(), a.z(), a.w())
        val bk = keys[i + 1]
        val bv = bk.mValue()
        val span = (bk.mTime() - keys[i].mTime()).coerceAtLeast(1e-8)
        val f = ((tick - keys[i].mTime()) / span).coerceIn(0.0, 1.0).toFloat()
        return Quaternionf(a.x(), a.y(), a.z(), a.w()).slerp(Quaternionf(bv.x(), bv.y(), bv.z(), bv.w()), f)
    }

    internal fun toMatrix4f(m: AIMatrix4x4): Matrix4f = Matrix4f(
        m.a1(), m.b1(), m.c1(), m.d1(),
        m.a2(), m.b2(), m.c2(), m.d2(),
        m.a3(), m.b3(), m.c3(), m.d3(),
        m.a4(), m.b4(), m.c4(), m.d4(),
    )
}
