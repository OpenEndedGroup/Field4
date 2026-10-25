package afield.runtime.kotlin

import afield.app.diagnostics.named
import afield.app.server.app
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.makeKI
import afield.document.graph.preamble
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.string
import afield.ui.notation.with
import afield.ui.output.history

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.KVisibility

/**
 * Renders the source-level type name that `@exported` writes into a consuming box's preamble.
 *
 * `@exported` shares a value between boxes by two channels: the value itself crosses at runtime,
 * while its type crosses as text, a generated `val Box._x by InheritanceFromContext<T>()` line
 * spliced into the other box's preamble and recompiled by that box's own engine. This object
 * computes the `T`.
 *
 * Each box has its own [KotlinInterface2] with its own REPL history and snippet classloader chain,
 * so a type is usable in the consuming box only if two things hold:
 *
 * - **It resolves at compile time.** Every engine's compile classpath is derived from the shared
 *   base classloader, so a type is nameable when that loader can reach it. Library types, `afield.`
 *   types and `@DependsOn` jars qualify; a class declared in another box's snippet does not, since
 *   it exists only in that box's snippet loader.
 * - **It is the same class at run time.** Snippet loaders are children of the shared loader, so a
 *   name that resolves there resolves to one `Class` for every box. The check is on class identity,
 *   so a shadowed name cannot produce a declaration that compiles and then throws
 *   `ClassCastException` where it is used.
 *
 * A type that fails either test renders as [ExportedTypeNames.OPAQUE] (`Any?`). A preamble line that
 * does not compile breaks every later compile in the consuming box, so a lost type costs less than
 * a wrong one.
 *
 * Type arguments are walked recursively and each unnameable argument is erased on its own to `*`.
 * `List<Double>` stays `List<Double>`, and a list of a box-local class becomes
 * `kotlin.collections.List<*>`. [ExportedTypeNamesSmokeTest] covers these cases.
 *
 * User types declared in one box cannot pass these tests; sharing them needs a compilation unit on
 * the base classloader, which [SharedTypes] is about.
 */

/** Type-name rendering for `@exported`; [render] is the entry point. */
object ExportedTypeNames {

    /** The fallback when a type cannot be named in another box. Always valid, always useless. */
    const val OPAQUE: String = "Any?"

    /** Guards against pathological or cyclic generic nesting; real exported types are shallow. */
    private const val MAX_DEPTH = 8

    /**
     * The source text to use for [type] in another box's preamble, or [OPAQUE] if it cannot be
     * named there.
     *
     * @param loader the shared base classloader that every box's engine derives its classpath
     *   from ([KotlinScriptEngine.classLoader]). Falls back to this class's own loader, which is
     *   the app classpath — correct, just blind to anything added by `@DependsOn`.
     */
    fun render(type: KType, loader: ClassLoader?): String {
        val effective = loader ?: ExportedTypeNames::class.java.classLoader
        return try {
            renderType(type, effective, depth = 0)
        } catch (t: Throwable) {
            // Reflection over snippet-defined classes can throw rather than return null.
            OPAQUE
        }
    }

    private fun renderType(type: KType, loader: ClassLoader, depth: Int): String {
        if (depth > MAX_DEPTH) return OPAQUE
        val classifier = type.classifier as? KClass<*> ?: return OPAQUE // type parameters, etc.
        val name = nameableQualifiedName(classifier, loader) ?: return OPAQUE
        val arguments = type.arguments
        // Reflection does not always hand back one argument per declared type parameter, and writing
        // a mismatched arity is a compile error in the consuming box. `suspend (Int) -> String` is
        // the case that bites: its continuation is erased before we see it, so it arrives as
        // kotlin.Function2 carrying only two arguments where three are declared.
        if (arguments.size != classifier.typeParameters.size) return OPAQUE
        val rendered = if (arguments.isEmpty()) {
            name
        } else {
            arguments.joinToString(separator = ", ", prefix = "$name<", postfix = ">") {
                renderProjection(it, loader, depth + 1)
            }
        }
        return if (type.isMarkedNullable) nullable(rendered) else rendered
    }

    private fun renderProjection(projection: KTypeProjection, loader: ClassLoader, depth: Int): String {
        val argument = projection.type ?: return "*" // already a star projection
        val inner = renderType(argument, loader, depth)
        // An argument we cannot name becomes a star projection, so the outer type survives.
        if (inner == OPAQUE) return "*"
        return when (projection.variance) {
            KVariance.IN -> "in $inner"
            KVariance.OUT -> "out $inner"
            KVariance.INVARIANT, null -> inner
        }
    }

    /**
     * The qualified name to write for [kClass], or null when another box could not name it.
     */
    private fun nameableQualifiedName(kClass: KClass<*>, loader: ClassLoader): String? = try {
        // Local and anonymous classes have no qualified name at all — the common case for a type
        // declared inside a snippet.
        val qualified = kClass.qualifiedName ?: return null

        // `suspend (A) -> B` erases to kotlin.coroutines.SuspendFunctionN, which is a compiler
        // fiction with no source spelling.
        if (qualified.startsWith("kotlin.coroutines.SuspendFunction")) return null

        val java = kClass.java
        when {
            // Primitives are resolvable everywhere and have no loader to interrogate.
            java.isPrimitive -> qualified
            java.isAnonymousClass || java.isLocalClass || java.isSynthetic -> null
            // Non-public types compile fine here and fail in the consuming box.
            kClass.visibility != KVisibility.PUBLIC -> null
            !resolvesIdentically(java, loader) -> null
            else -> qualified
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Whether [loader] reaches exactly this class — not merely *a* class of the same name. Array
     * types work out for free: their binary name embeds the component type, so an array of a
     * box-local class fails the check for the same reason the component would.
     */
    private fun resolvesIdentically(java: Class<*>, loader: ClassLoader): Boolean = try {
        Class.forName(java.name, false, loader) === java
    } catch (t: Throwable) {
        false
    }

    private fun nullable(rendered: String): String = if (rendered.endsWith("?")) rendered else "$rendered?"
}
