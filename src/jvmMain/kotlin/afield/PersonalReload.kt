package afield

import afield.logger.PinnedConsole
import afield.logger.lprintln
import afield.stdlib.Action
import afield.stdlib.actions
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Reloading [PersonalLayer] at runtime, and coping with the objects the old one left behind.
 *
 * [reloadPersonal] retires the live layer so the next engine compiles and runs against a freshly
 * built `afield-personal.jar`. The hard part is not the swap, it is that every already-evaluated
 * snippet holds classes from the layer that just died: an object made by gen-0 `kiwi.Solver` and an
 * object made by gen-1 `kiwi.Solver` are unrelated types, and mixing them produces the JVM's least
 * helpful error, `class kiwi.Solver cannot be cast to class kiwi.Solver`.
 *
 * Two halves, a scan up front and a diagnosis at the point of failure:
 *
 *  * [scanForStaleClasses] is a shallow, best-effort look at what an engine is holding, so the
 *    common case — a box with a personal object in a top-level `val` — gets caught and reset up
 *    front. It is deliberately incomplete (see its own docs) and makes no attempt to be otherwise.
 *  * [staleLayerDiagnosis] catches what the scan missed, at the moment it actually breaks, and says
 *    so in English. Because recovery is a one-click reset rather than a debugging session, the scan
 *    does not have to be good — it only has to keep most boxes from visibly hiccuping.
 *
 * Neither half re-runs anything. A box whose engine was recycled is *marked* ([needsRerun]) and left
 * alone: Field does not execute user code that the user did not ask to execute, and a reload is not
 * an exception to that — re-running a box can open windows, start strands and hit the network.
 */

/**
 * Why this box needs re-running, or null if it doesn't. Set when the box's engine is discarded out
 * from under it; cleared when the box is next executed (see `executeInBox`).
 */
var Box.needsRerun: String?
    get() = getPropertyRaw(NEEDS_RERUN_PROPERTY) as? String
    // Both sides go through the raw accessors rather than touching `properties` directly: that is
    // what enqueues the resync, and without it the editor never hears about the mark (or about it
    // being cleared) and the box carries a stale outline for the rest of the session.
    set(value) {
        if (value == null) removePropertyRaw(NEEDS_RERUN_PROPERTY)
        else setPropertyRaw(NEEDS_RERUN_PROPERTY, value)
    }

const val NEEDS_RERUN_PROPERTY = "_needsRerun"

/** Summary of one [reloadPersonal]. */
data class PersonalReloadReport(
    val retiredGeneration: Int?,
    val newGeneration: Int,
    val examined: Int,
    val reset: List<Pair<Box, String>>,
) {
    val skipped: Int get() = examined - reset.size

    override fun toString(): String = when {
        retiredGeneration == null -> "personal layer: nothing to reload"
        else -> "personal layer: gen$retiredGeneration → gen$newGeneration, " +
            "${reset.size} of $examined box(es) marked for re-running"
    }
}

/**
 * Retire the live personal layer and pick up whatever `./gradlew personalJar` last built.
 *
 * Does not build the jar — that is a separate, slow, out-of-process step, and keeping it out of here
 * means a reload is instant and can be repeated while iterating. Run `./gradlew personalJar` first.
 *
 * Every box whose engine is still holding gen-N classes gets that engine disposed and is marked
 * [needsRerun]. Boxes the scan clears are left running on their existing engines — cheap, but note
 * that each one pins the retired layer in memory until it is reset too, so a long session that never
 * re-runs anything will accumulate layers.
 *
 * Call on the main runloop. It disposes engines and walks property bags, both of which race box
 * execution otherwise; [installPersonalReloadAction] goes through [inMain] for exactly this reason.
 */
fun reloadPersonal(): PersonalReloadReport {
    val examined = documents.values.flatMap { it.boxes.values }
        .filter { !it.getPropertyRaw("_tombstone").isTrue() }

    val retiredGeneration = PersonalLayer.advance()
    if (retiredGeneration == null) {
        return PersonalReloadReport(null, PersonalLayer.generation, examined.size, emptyList())
    }

    // Every engine's snippet chain roots at this one process-wide loader, so it has to go with the
    // layer regardless of which boxes we end up resetting. Nulling it makes the next makeKI build a
    // fresh one over the new layer; engines we leave alone keep the old one and keep working.
    KotlinInterface2.sharedClassLoader = null

    // Server.main made the layer the context classloader for everything that reaches for one by
    // convention (ClassGraph, ServiceLoader, Clojure). Move this thread over; threads started before
    // now keep the retired one, which resolves fine — it is only new lookups that want the new layer.
    if (PersonalLayer.isPresent) {
        Thread.currentThread().contextClassLoader = PersonalLayer.current
    }

    val reset = mutableListOf<Pair<Box, String>>()
    for (box in examined) {
        val reason = runCatching { scanForStaleClasses(box) }.getOrElse {
            lprintln("error,personal", "scan failed for box ${box.id}: $it")
            null
        } ?: continue
        box.disposeKi()
        box.needsRerun = "personal layer gen$retiredGeneration was reloaded — $reason"
        reset += box to reason
    }

    val report = PersonalReloadReport(retiredGeneration, PersonalLayer.generation, examined.size, reset)
    lprintln("info,personal", report.toString())
    reset.forEach { (box, reason) -> lprintln("info,personal", "  re-run ${box.name.ifBlank { box.id }} — $reason") }
    if (reset.isNotEmpty()) {
        PinnedConsole.error(
            "personal-reload",
            "personal · gen$retiredGeneration → gen${PersonalLayer.generation}",
            listOf("${reset.size} box(es) need re-running:") +
                reset.take(12).map { (box, reason) -> "  ${box.name.ifBlank { box.id }} — $reason" }
        )
    }
    return report
}

@RunsAt(newRoot)
fun installPersonalReloadAction(root: Box) {
    if (!PersonalLayer.isPresent) return
    root.actions["reloadPersonal"] = Action(
        "Reload personal/ classes",
        detail = "Picks up the last ./gradlew personalJar build. Boxes holding objects from the old " +
            "build have their Kotlin environment dropped and are flagged for re-running; nothing is re-run for you."
    ) {
        val origin = thisSession.get()
        inMain {
            val report = reloadPersonal()
            _send(origin, Message("notify").payload(Notify(report.toString(), "personal")))
        }
    }
}

// --- the scan -------------------------------------------------------------------------------

/** Peeling a million-element list tells you nothing the first element didn't. */
private const val PEEL_LIMIT = 64

/**
 * A one-peel look for classes belonging to a retired layer, returning a human-readable description
 * of the first thing found, or null.
 *
 * Covers, for the box's engine and its property bag: the declared types of top-level `val`/`var`s
 * and of function signatures, the current value of each declaration, and one level inside any
 * container those land in.
 *
 * What it deliberately does not do:
 *
 *  * **Call anything.** Values come from the snapshot [KotlinContext] already holds, never from a
 *    getter — a `val x get() = …` or a `by lazy` would otherwise run user code in the middle of a
 *    reload. For the same reason a container is only peeled when the container *itself* is a JDK or
 *    Kotlin type: iterating a user-defined collection means calling a user-defined `iterator()`.
 *  * **Go deeper than one level.** A personal object hidden in a field of a core object is not found.
 *  * **See inside method bodies.** A `kiwi.Solver` built, used and dropped inside a function leaves
 *    no value to find, yet the compiled snippet still references the dead class and will fail on the
 *    next call. [staleLayerDiagnosis] is what covers that.
 *
 * So a null result means "nothing obvious", not "safe".
 */
fun scanForStaleClasses(box: Box): String? {
    if (!PersonalLayer.hasRetiredLayers) return null

    box.properties.entries.toList().forEach { (name, value) ->
        staleIn(value)?.let { return "properties[$name] holds $it" }
    }

    // Reading box.ki would build an engine for a box that never had one. Only look at live ones.
    return scanEngineForStaleClasses(box.liveKi ?: return null)
}

/** The engine half of [scanForStaleClasses] — its declarations, without a box's property bag. */
fun scanEngineForStaleClasses(ki: KotlinScriptEngine): String? {
    if (!PersonalLayer.hasRetiredLayers) return null

    ki.context.vars.entries.toList().forEach { (name, info) ->
        staleInType(runCatching { info.descriptor.returnType }.getOrNull())
            ?.let { return "`$name` is declared as $it" }
        staleIn(info.value)?.let { return "`$name` holds $it" }
    }

    ki.context.functions.entries.toList().forEach { (name, info) ->
        val signature = runCatching {
            info.function.parameters.map { it.type } + info.function.returnType
        }.getOrDefault(emptyList())
        signature.firstNotNullOfOrNull { staleInType(it) }?.let { return "`fun $name` mentions $it" }
    }

    return null
}

/** The stale class named by a declared type — the classifier itself, or one level of type argument. */
private fun staleInType(type: KType?): String? {
    if (type == null) return null
    val direct = (type.classifier as? KClass<*>)?.javaObjectType
    describeIfStale(direct)?.let { return it }
    return type.arguments.asSequence()
        .mapNotNull { it.type }
        .firstNotNullOfOrNull { arg -> describeIfStale((arg.classifier as? KClass<*>)?.javaObjectType) }
}

/** The stale class held by a value, looking one level inside JDK/Kotlin containers. */
private fun staleIn(value: Any?): String? {
    if (value == null) return null
    describeIfStale(value.javaClass)?.let { return it }
    if (!isSafeToPeel(value)) return null
    return peel(value).firstNotNullOfOrNull { element ->
        describeIfStale(element.javaClass)?.let { "$it (inside a ${value.javaClass.simpleName})" }
    }
}

private fun describeIfStale(type: Class<*>?): String? =
    PersonalLayer.retiredGenerationOf(type)?.let { "${type!!.name} from gen$it" }

/**
 * Is this container safe to iterate — i.e. is iterating it guaranteed not to run user code? Only
 * JDK and Kotlin implementations qualify. A container from the personal layer itself needs no peel:
 * its own class already answered the question.
 */
private fun isSafeToPeel(value: Any): Boolean {
    if (value is Array<*>) return true
    if (value !is Collection<*> && value !is Map<*, *> && value !is Pair<*, *> && value !is Triple<*, *, *>) return false
    val loader = value.javaClass.classLoader ?: return true
    val name = value.javaClass.name
    return loader === Any::class.java.classLoader ||
        name.startsWith("java.") || name.startsWith("kotlin.")
}

private fun peel(value: Any): Sequence<Any> = when (value) {
    is Array<*> -> value.asSequence().take(PEEL_LIMIT)
    is Collection<*> -> value.asSequence().take(PEEL_LIMIT)
    is Map<*, *> -> value.entries.asSequence().take(PEEL_LIMIT).flatMap { sequenceOf(it.key, it.value) }
    is Pair<*, *> -> sequenceOf(value.first, value.second)
    is Triple<*, *, *> -> sequenceOf(value.first, value.second, value.third)
    else -> emptySequence()
}.filterNotNull()

// --- diagnosing what the scan missed --------------------------------------------------------

/**
 * If [throwable] looks like a box tripping over classes from a retired layer, an explanation fit to
 * show in place of the JVM's — and the box is flagged [needsRerun] on the way past, since that is
 * exactly the state it is in.
 *
 * The gate is cheap: nothing has ever been retired in a session that never reloaded, so this returns
 * null immediately and costs a boolean.
 */
fun staleLayerDiagnosis(box: Box, throwable: Throwable): String? {
    if (!PersonalLayer.hasRetiredLayers) return null
    if (!throwable.looksLikeStaleLinkage()) return null

    val found = runCatching { scanForStaleClasses(box) }.getOrNull()
    val detail = found ?: throwable.staleLoaderMentioned() ?: return null

    val message = "this box is holding objects from a previous personal/ build ($detail) — " +
        "re-run it to rebuild them against the current one"
    box.needsRerun = message
    return message
}

/**
 * `ClassCastException` and the `LinkageError` family are how a split identity surfaces: the same
 * source class loaded by two loaders, one of them dead. Walks the cause chain because the box's
 * failure is usually wrapped.
 */
private fun Throwable.looksLikeStaleLinkage(): Boolean =
    causeChain().any { it is ClassCastException || it is LinkageError }

/**
 * The JVM spells out both loaders in a cast failure — "…is in unnamed module of loader
 * 'afield-personal-gen0' @1b2c3d…". When the scan finds nothing (the offending object was never in a
 * declaration we can see) that text is the remaining evidence.
 */
private fun Throwable.staleLoaderMentioned(): String? = causeChain()
    .mapNotNull { it.message }
    .firstOrNull { STALE_LOADER_MENTION.containsMatchIn(it) }
    ?.let { STALE_LOADER_MENTION.find(it)!!.value }

private val STALE_LOADER_MENTION = Regex("""afield-personal-gen\d+""")

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { if (it.cause === it) null else it.cause }.take(8)
