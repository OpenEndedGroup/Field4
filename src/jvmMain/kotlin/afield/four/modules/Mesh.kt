package afield.four.modules

import afield.Box
import afield.ContextResolution
import afield.four.Renderer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.buffers.VertexBuffer
import afield.four.buffers.bindPushConstant
import afield.four.buffers.getBoundPushConstant
import afield.four.buffers.getPushConstant
import afield.four.buffers.setPushConstant
import afield.four.device.CommandBuffer
import afield.four.util.MapOfSequences
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.reflect.KProperty
import kotlin.reflect.full.isSubclassOf


class FromInput {
    operator fun getValue(inside: Mesh, property: KProperty<*>): VertexBuffer {
        return inside.input(property.name)
    }
}

/**
 * Resolve a value of type [A] from the box context: this box first, then its ancestors,
 * breadth-first.
 *
 * Search order is
 *  1. the current box itself (if it is an [A]),
 *  2. the current box's properties,
 *  3. each ancestor generation in turn - properties, then that box's top-level (`ki`) variables.
 *
 * Note what is deliberately absent: **the current box's own top-level variables**. Including them
 * makes the canonical idiom self-pinning -
 *
 *     var foo = fromContext<Foo>()
 *
 * resolves from a parent the first time, but that leaves a `Foo`-typed variable in *this* box's
 * variable table, so every later execution finds its own previous answer. `foo` can then never
 * track the parent again, and re-pointing it by hand doesn't survive either.
 *
 * Pass [includeLocalVariables] = true to opt back in when a box genuinely wants to find a value it
 * declared itself - e.g. code running after an async boundary in the same box.
 */
inline fun <reified A> fromContext(defaultValue: A? = null, includeLocalVariables: Boolean = false): A {
    val f = Box.context.get()
    val found = ContextResolution.walk(
        f,
        ContextResolution.Query.byType(A::class.java) { it is A },
        ContextResolution.Policy.fromContext(includeLocalVariables),
        ContextResolution.Mechanism.FROM_CONTEXT,
    )
    if (found != null) return found.value as A
    if (defaultValue != null) return defaultValue as A
    throw IllegalArgumentException("must pass in a value for context parameter ${A::class.java.name}")
}

/**
 * Named sibling of [fromContext]: resolve the value called [name] (and assignable to [A]) from the
 * box context.
 *
 * Same search order, and the same deliberate omission - this box's own top-level variables are not
 * searched unless [includeLocalVariables] is true. Here the trap is even easier to hit, since the
 * natural idiom gives the local the same name as the thing it is looking up:
 *
 *     val agent_context_value = fromContextWithNameOrNull<String>("agent_context_value")
 */
inline fun <reified A> fromContextWithNameOrNull(
    name: String,
    defaultValue: A? = null,
    includeLocalVariables: Boolean = false,
): A? {
    val f = Box.context.get()
    if (f == null) return defaultValue

    val accept: (Any?) -> Boolean = { it != null && it::class.isSubclassOf(A::class) }
    val found = ContextResolution.walk(
        f,
        ContextResolution.Query(name, listOf(name), accept, { accept(it.value) }),
        ContextResolution.Policy.fromContext(includeLocalVariables, selfCandidate = false),
        ContextResolution.Mechanism.FROM_CONTEXT_NAMED,
    )
    if (found != null) return found.value as A
    if (defaultValue != null) return defaultValue as A
    return null
}

val restart: Int
    get() = (0xFFFFFFFF).toInt()

// this has to agree with the vertexbufferstructure that was used for this pipeline (right now)
class Mesh(var renderer: Renderer = fromContext()) {

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

    var v = mutableMapOf<Int, VertexBuffer>()
    var vn = mutableMapOf<String, VertexBuffer>()

    var t: VertexBuffer? = null

    var numTriangle: Int = -1
    var numVert: Int = -1

    var update = MapOfSequences<String>()
    var instanceCount = 1

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

    var notInitialized = true

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
                        throw MissingRefException("risk of GPU null ref `$it`")
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
