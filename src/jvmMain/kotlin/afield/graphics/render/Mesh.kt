package afield.graphics.render

import afield.document.context.fromContext
import afield.graphics.shader.Introspected

import afield.document.graph.Box
import afield.document.context.ContextResolution
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.MissingRefException
import afield.graphics.vulkan.buffers.Structure
import afield.graphics.vulkan.buffers.VertexBuffer
import afield.graphics.vulkan.buffers.bindPushConstant
import afield.graphics.vulkan.buffers.getBoundPushConstant
import afield.graphics.vulkan.buffers.getPushConstant
import afield.graphics.vulkan.buffers.setPushConstant
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.util.MapOfSequences
import afield.app.diagnostics.logging.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.reflect.KProperty
import kotlin.reflect.full.isSubclassOf


/**
 * [Mesh] is the vertex-buffer draw call of a graphics module: it holds the vertex inputs and push
 * constants the module's shaders declare, and draws them on the [Renderer] found from context.
 *
 * Its layout has to agree with the vertex-buffer structure the module's pipeline was built with.
 * [fullscreenTriangle] makes the one-triangle mesh used for full-screen passes.
 */

class FromInput {
    operator fun getValue(inside: Mesh, property: KProperty<*>): VertexBuffer {
        return inside.input(property.name)
    }
}

/** The primitive-restart index: write it into a strip or fan index buffer to end the current run and begin another. Meshes index with `VK_INDEX_TYPE_UINT32`, so the value is all thirty-two bits set, and a module's pipeline turns restart on for exactly the strip and fan topologies. */
val restart: Int
    get() = (0xFFFFFFFF).toInt()

// this has to agree with the vertexbufferstructure that was used for this pipeline (right now)
class Mesh(
    /** The renderer the vertex and index buffers are allocated on, and whose frame counter decides when [prepareForDraw] has already run for this frame. */
    var renderer: Renderer = fromContext()
) {

    constructor(renderer: Renderer = fromContext(), pointer: ModulePointer = fromContext()) : this(renderer) {
        this.inside = pointer
    }

    /**
     * Module pointer supplying the mesh's shader inputs and push-constant layout.
     *
     * The module-aware constructor initializes it. For a mesh built with the primary constructor,
     * pass an explicit pointer to the first [resize] call; that method's default reads this
     * `lateinit` property and therefore requires prior initialization.
     */
    lateinit var inside: ModulePointer

    /** The vertex buffers by the shader input location they feed. [resize] creates one per input the module declares, grows or trims it to the vertex count, and frees any whose input the current shaders do not declare. */
    var v = mutableMapOf<Int, VertexBuffer>()

    /** The same buffers again, by input name — the map [input] reads, and the one to reach for from box code, where the shader's `position` is easier to name than its location. */
    var vn = mutableMapOf<String, VertexBuffer>()

    /** The index buffer, or null when the mesh is not indexed. [resize] allocates it only for a non-zero triangle count, and drops it again when that count goes back to zero. */
    var t: VertexBuffer? = null

    /** Primitives the last [resize] made room for, or -1 before the first one. Above zero it is an indexed draw of this many primitives; at zero the mesh draws [numVert] vertices straight through. */
    var numTriangle: Int = -1

    /** Vertices the last [resize] made room for, or -1 before the first one. */
    var numVert: Int = -1

    /** Named sequences advanced once per frame, from [prepareForDraw], before the buffers are flushed — which is where a mesh that rewrites its own vertices each frame puts that work. */
    var update = MapOfSequences<String>()

    /** How many copies of the mesh each draw call asks for; reaches `vkCmdDraw` and `vkCmdDrawIndexed` directly, so the shader distinguishes them by `gl_InstanceIndex`. */
    var instanceCount = 1

    /** The push constants for this draw, laid out to match the vertex and fragment members the current module declares. The `FromStructure` delegates read and write it by member name. */
    var push = Structure()

    class BoundFromStructure<T> {
        operator fun setValue(inside: Mesh, property: KProperty<*>, any: () -> T) {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            inside.push.bindPushConstant(property.name, any) {
                throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: Mesh, property: KProperty<*>): () -> T {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            return inside.push.getBoundPushConstant(property.name) {
                throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
            }
        }
    }


    class FromStructure<T> {
        operator fun setValue(inside: Mesh, property: KProperty<*>, any: T) {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            inside.push.setPushConstant(property.name, any) {
                throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: Mesh, property: KProperty<*>): T /* really Union[T, () -> T] */ {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            return inside.push.getPushConstant(property.name) {
//            else if (property.name in inside.inside.current.liveUniformStructures) {
//                return inside.inside.current.liveUniformStructures[property.name] as T
//            }
                throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
            }
        }
    }


    /**
     * Returns the vertex buffer for the named mesh input.
     *
     * The [name] must match an input declared by the current module. If the mesh has not yet been
     * resized, the exception points callers at [resize] so buffers are created before lookup.
     */
    fun input(name: String): VertexBuffer {
        if (name !in vn && notInitialized) throw IllegalArgumentException("Input '$name' is not in mesh. Call .resize(numVert, numTriangle) with a valid size first inside the context of a module.")
        if (name !in vn) throw IllegalArgumentException("Input '$name' is not in mesh. Valid names are $vn")
        return vn[name]!!
    }

    operator fun get(name: String) = input(name)

    /** Whether [resize] has yet run. Only [input] reads it, to tell "there is no such input in this module" from "there are no inputs yet because nothing has been sized". */
    var notInitialized = true

    /** The open-ended writer for this mesh's vertices and indices. [prepareForDraw] closes and applies it if the `builder { }` block left it open, so the contents land in the buffers before the draw that needs them. */
    var builder = MeshBuilder(this)

    fun builder(b: MeshBuilder.() -> Unit): MeshBuilder {
        if (!builder.isOpen) {
            builder.open()
        }
        builder.b()
        return builder
    }

    fun primitiveSize(): Int {
        return when (inside.current.parameters.primitiveType) {
            VK_PRIMITIVE_TOPOLOGY_POINT_LIST -> 0
            VK_PRIMITIVE_TOPOLOGY_LINE_LIST -> 2
            VK_PRIMITIVE_TOPOLOGY_LINE_STRIP -> 1
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST -> 3
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP -> 1
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN -> 1
            VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY -> 4
            VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY -> 1
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY -> 6
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY -> 1
            VK_PRIMITIVE_TOPOLOGY_PATCH_LIST -> 1
            else -> throw IllegalArgumentException("primitiveSize ? ${inside.current.parameters.primitiveType}")
        }
    }


    /**
     * here numTriangle = 0 means 'we're not indexed and we don't need an index buffer'
     */
    fun resize(numVert: Int, numTriangle: Int = 0, inside: ModulePointer = this.inside): Mesh {
        this.inside = inside
        initializePushConstantsFromModule(inside)

        val orphaned = v.keys.toMutableSet()
        inside.current.vi.inputs.forEach {
            var existing = v[it.location]
            orphaned.remove(it.location)
            val neededSize = numVert * it.sizeInBytes
            if (existing != null && existing.size < neededSize) {
                existing.cleanup()
                v.remove(it.location)
                vn.remove(it.name)
                existing = null
            }
            if (existing == null && neededSize > 0) {
                existing =
                    VertexBuffer(
                        inside.current.parameters.renderer,
                        neededSize,
                        usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                    )
                v[it.location] = existing
                vn[it.name] = existing
            } else {
                if (existing == null) {

                } else {
                    existing.trimTo(neededSize)
                    v[it.location] = existing
                    vn[it.name] = existing
                }
            }
        }
        val neededTriangleSize = numTriangle * primitiveSize() * 4L
        val tt = t
        if (neededTriangleSize > 0) {
            if (tt == null) {
                t = VertexBuffer(renderer, neededTriangleSize, usage = VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
            } else if (tt.size >= neededTriangleSize) {
                tt.trimTo(neededTriangleSize)
            } else {
                tt.cleanup()
                t = VertexBuffer(renderer, neededTriangleSize, usage = VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
            }
        } else {
            if (tt != null) {
                tt.cleanup()
                t = null
            }
        }

        orphaned.forEach {
            vn.values.remove(v[it])
            v[it]?.cleanup()
            v.remove(it)
        }

        this.numTriangle = numTriangle
        this.numVert = numVert
        this.notInitialized = false

        return this
    }

    internal fun initializePushConstantsFromModule(inside: ModulePointer) {
        push.setMergedPushMembers(inside)
    }

    /**
     * Lay [push] out as the current module's push-constant members, vertex and fragment merged.
     *
     * An empty merge is applied too. It used to be skipped, so a full reload that removed *every* push
     * constant left the old offsets in place, and setting a member that no longer exists succeeded
     * silently instead of failing with "can't find … as a push_constant".
     */
    private fun Structure.setMergedPushMembers(inside: ModulePointer) {
        val mergedMembers = linkedMapOf<String, Introspected.Member>()
        for (pc in inside.current.fi.pushConstants + inside.current.vi.pushConstants) {
            for (member in pc.members) {
                mergedMembers[member.name] = member
            }
        }
        push.setMembers(mergedMembers.values.sortedBy { it.offset })
    }

    fun write(index: Int, numElements: Int, builder: FloatBuffer.() -> Unit) {
        val v = v.computeIfAbsent(index) {
            VertexBuffer(renderer, numVert * numElements * 4L)
        }

        v.mapAndWrite(builder)
    }

    fun write(name: String, inside: Module = this.inside.current, builder: FloatBuffer.() -> Unit) {
        var input = inside.namedInputs[name]!!
        val v = v.computeIfAbsent(input.location) {
            VertexBuffer(renderer, numVert * input.sizeInBytes)
        }

        v.mapAndWrite(builder)
    }


    fun prepareForDraw(cmd: CommandBuffer) {
        val frameId = renderer.frameCounter
        val imageIndex = renderer.currentImage
        if (lastPreparedFrameId == frameId && lastPreparedImageIndex == imageIndex) return

        if (builder.isOpen) {
            builder.closeAndApply()
        }

        update.update()

//        if (notInitialized)
//            lprintln("warning,graphics", "mesh has not been initialized yet. Call .resize(v,t) to build its buffers")

        v.entries.forEach { (_, buffer) ->
            buffer.clean(false, cmd)
        }

        t?.clean(false, cmd)
        lastPreparedFrameId = frameId
        lastPreparedImageIndex = imageIndex
    }

    private var tick = 0
    private var lastPreparedFrameId = Long.MIN_VALUE
    private var lastPreparedImageIndex = -1
    fun draw(inside: Module, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) {
        if (inside != this.inside.current)
            lprintln("warning,graphics", "mesh was built in a different module ${inside}!=${this.inside.current}")

        // NOTE: This may be called inside dynamic rendering / a render pass instance.
        // VertexBuffer.clean() will fall back to a separate transfer if needed.
        prepareForDraw(cmd)

        MemoryStack.stackPush().use { stack ->

            val profiler = renderer.getProfiler()
            val frameIndex = renderer.currentImage
            // reuse renderer’s ModuleMarks to keep plumbing minimal
            var marks: Renderer.ModuleMarks? = null
            if (profiler != null) {
                marks = Renderer.ModuleMarks(
                    name = "${inside.parameters.name}:mesh"
                )
                marks.tsBegin = profiler.writeTimestamp(
                    cmd.vkCommandBuffer, frameIndex, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                )
            }

            val vertexBuffer = stack.mallocLong(inside.vi.inputs.size)
            inside.vi.inputs.forEachIndexed { index, it ->
                val buffer = v.get(it.location)
                if (buffer == null) {
                    lprintln("warning,graphics", "missing vertex buffer for $it at slot $index")
                    vertexBuffer.put(index, 0L)
                } else {
                    vertexBuffer.put(index, buffer.dest.buffer)
                }
            }

            val offsets = stack.mallocLong(v.size)
            for (i in 0 until v.size) {
                offsets.put(i, 0)
            }

            if (v.size > 0)
                vkCmdBindVertexBuffers(cmd.vkCommandBuffer, 0, vertexBuffer, offsets)

            fun pushConstantsForCurrentDispatch(): Boolean {
                if (pushConstantBuffer.limit() <= 0) {
                    // No push constants now; drop a layout left behind by a module that had some.
                    if (push.offsets.isNotEmpty()) push.setMergedPushMembers(this.inside)
                    return true
                }
                push.setMergedPushMembers(this.inside)
                try {
                    pushConstantBuffer.clear()
                    push.writeToBuffer(pushConstantBuffer, cmd) {
                        throw MissingRefException(afield.graphics.vulkan.buffers.unassignedMemberMessage(it))
                    }
                } catch (mre: Exception) {
                    inside.parameters.errorHandler(mre.message ?: "push constant error", mre)
                    lprintln("error,graphics", "push constant error, not rendering mesh : ${mre.message}")
                    return false
                }
                pushConstantBuffer.clear()
                vkCmdPushConstants(
                    cmd.vkCommandBuffer,
                    inside.pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT,
                    0,
                    pushConstantBuffer
                )
                return true
            }

            fun drawForCurrentDispatch(vertexCountOverride: Int?, indexCountOverride: Int?) {
                val tt = t
                if (tt != null) {
                    val indexCount = indexCountOverride ?: (if (numTriangle > 0) numTriangle * primitiveSize() else 0)
                    if (indexCount > 0) {
                        vkCmdBindIndexBuffer(cmd.vkCommandBuffer, tt.dest.buffer, 0, VK_INDEX_TYPE_UINT32)
                        vkCmdDrawIndexed(
                            cmd.vkCommandBuffer,
                            indexCount,
                            instanceCount,
                            0,
                            0,
                            0
                        )
                    } else {
                        if (tick++ % 100 == 0) {
                            lprintln(
                                "warning,graphics",
                                "mesh has no triangles in it, but still has a triangle buffer? skipping drawIndexed call"
                            )
                        }
                    }
                } else {
                    val vertexCount = vertexCountOverride ?: numVert
                    if (vertexCount > 0) {
                        vkCmdDraw(cmd.vkCommandBuffer, vertexCount, instanceCount, 0, 0)
                    } else {
                        if (tick++ % 100 == 0) {
                            lprintln("warning,graphics", "mesh has no verts in it, skipping draw call")
                        }
                    }
                }
            }

            try {
                forEachFanOutDispatch(
                    renderer = renderer,
                    push = push,
                    onNoFanOut = {
                        if (pushConstantsForCurrentDispatch()) {
                            drawForCurrentDispatch(null, null)
                        }
                    },
                    onWriter = { writer ->
                        if (pushConstantsForCurrentDispatch()) {
                            drawForCurrentDispatch(
                                vertexCountOverride = writer.publication.vertexCount,
                                indexCountOverride = writer.publication.indexCount
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                inside.parameters.errorHandler(e.message ?: "fan-out dispatch error", e)
                lprintln("error,graphics", "fan-out dispatch error, not rendering mesh : ${e.message}")
            }

            if (profiler != null && marks != null) {
                marks.tsEnd = profiler.writeTimestamp(
                    cmd.vkCommandBuffer, frameIndex, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
                )
                renderer.registerModuleMarks(frameIndex, marks)
            }
        }
    }


    override fun toString(): String {
        return "Mesh(${numVert}v, ${numTriangle}t)"
    }
}

/**
 * Three non-indexed vertices for a fullscreen shader that derives positions from `gl_VertexIndex`.
 * [module] defaults to the current box's graphics module. No vertex attributes are allocated and
 * nothing is attached or presented: add the returned mesh to the module under your chosen name.
 */
fun fullscreenTriangle(module: ModulePointer = fromContext()): Mesh {
    require(module.current.vi.inputs.isEmpty()) { "fullscreenTriangle needs a vertex shader without attributes" }
    require(module.current.parameters.primitiveType == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST) {
        "fullscreenTriangle needs triangle-list topology"
    }
    return Mesh(module.current.parameters.renderer, module).resize(3)
}
