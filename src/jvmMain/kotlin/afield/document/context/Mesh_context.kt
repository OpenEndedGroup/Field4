package afield.document.context

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
    if (f == null) {
        if (defaultValue != null) return defaultValue
        // A `main`, a test or a background thread: there is no box to search, so say that, rather
        // than letting the walk fail on a null box.
        throw IllegalStateException(
            "fromContext<${A::class.java.simpleName}>() needs a running box, and this code is not running " +
                "inside one (a main, a test, or another thread); pass the ${A::class.java.simpleName} explicitly"
        )
    }
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

