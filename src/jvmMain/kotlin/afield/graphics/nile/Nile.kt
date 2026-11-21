package afield.graphics.nile

/**
 * Nile-style stream kernels: small stages that consume one typed stream of tokens and emit another.
 *
 * After Dan Amelang's Nile language and its Gezira graphics library, as described in
 * *Pixels on a screen* (whitebeard.blog). A [Stage] maps a `List<A>` to a `List<B>`; stages compose
 * with [then]. A [kernel] handles one token at a time and may [KernelScope.emit] any number of
 * outputs, or [KernelScope.push] tokens back onto the front of its own input queue, which is how a
 * Nile kernel recurses (DecomposeBeziers pushes the two halves of a curve that is too big for one
 * pixel). [sortBy] and [groupBy] are the whole-stream stages Gezira's rasteriser needs.
 *
 * [Stage.trace] runs a pipeline and keeps every intermediate stream by stage name, so a box can draw
 * each one.
 */
abstract class Stage<A, B>(val name: String) {

    /** Run this stage over [input], recording every named intermediate stream into [trace]. */
    abstract fun run(input: List<A>, trace: Trace?): List<B>

    /** Run this stage over [input]. */
    operator fun invoke(input: List<A>): List<B> = run(input, null)

    /** Run this stage over [input] and keep each stage's output stream. */
    fun trace(input: List<A>): Trace {
        val t = Trace()
        t.record("input", input)
        t.output = run(input, t)
        return t
    }

    override fun toString(): String = name
}

/** The streams one run of a pipeline produced, keyed by stage name, in pipeline order. */
class Trace {
    /** Each stage's output stream, in the order the stages ran. */
    val streams = LinkedHashMap<String, List<Any?>>()

    /** Tokens each kernel took off its queue, counting pushed-back ones, by stage name. */
    val steps = LinkedHashMap<String, Int>()

    /** The final stream. */
    var output: List<Any?> = emptyList()

    internal fun record(name: String, stream: List<Any?>) {
        streams[name] = stream
    }

    /** The output stream of the stage called [name], cast to its token type. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(name: String): List<T> =
        streams[name] as List<T>? ?: error("no stage called '$name'; stages: ${streams.keys}")

    override fun toString(): String =
        streams.entries.joinToString("\n") { (k, v) -> "$k: ${v.size} tokens" + (steps[k]?.let { ", $it steps" } ?: "") }
}

/** What a kernel's per-token body can do: emit downstream, or push back onto its own input. */
class KernelScope<A, B> internal constructor(
    private val queue: ArrayDeque<A>,
    private val out: MutableList<B>,
) {
    /** Send [b] downstream. */
    fun emit(b: B) {
        out.add(b)
    }

    /** Put [a] back on the front of this kernel's own input, to be handled next (Nile's `<<`). */
    fun push(a: A) {
        queue.addFirst(a)
    }
}

/** Built once per run of a [kernel]: `var`s declared in the builder block are per-run state. */
class KernelBuilder<A, B> internal constructor() {
    internal var body: (KernelScope<A, B>.(A) -> Unit)? = null
    internal var finish: (KernelScope<A, B>.() -> Unit)? = null

    /** Handle one token. */
    fun each(f: KernelScope<A, B>.(A) -> Unit) {
        body = f
    }

    /** Called once after the input is exhausted, to flush state. */
    fun end(f: KernelScope<A, B>.() -> Unit) {
        finish = f
    }
}

/** A token-at-a-time stage. [maxSteps] stops a kernel whose push-back never terminates. */
class Kernel<A, B>(
    name: String,
    private val maxSteps: Int,
    private val build: KernelBuilder<A, B>.() -> Unit,
) : Stage<A, B>(name) {
    override fun run(input: List<A>, trace: Trace?): List<B> {
        val b = KernelBuilder<A, B>().apply(build)
        val body = b.body ?: error("kernel '$name' has no each { } block")
        val queue = ArrayDeque(input)
        val out = ArrayList<B>()
        val scope = KernelScope(queue, out)
        var steps = 0
        while (queue.isNotEmpty()) {
            if (++steps > maxSteps) error("kernel '$name' took more than $maxSteps tokens; does its push-back terminate?")
            scope.body(queue.removeFirst())
        }
        b.finish?.invoke(scope)
        trace?.let {
            it.record(name, out)
            it.steps[name] = steps
        }
        return out
    }
}

/**
 * A kernel. The [build] block runs afresh for every run, so state is ordinary `var`s in it:
 *
 * ```
 * val runningSum = kernel<Int, Int>("RunningSum") {
 *     var s = 0
 *     each { s += it; emit(s) }
 * }
 * ```
 */
fun <A, B> kernel(name: String, maxSteps: Int = 50_000_000, build: KernelBuilder<A, B>.() -> Unit): Stage<A, B> =
    Kernel(name, maxSteps, build)

/** A kernel that emits exactly one output per input. */
fun <A, B> mapKernel(name: String, f: (A) -> B): Stage<A, B> = kernel(name) { each { emit(f(it)) } }

/** Stable sort of the whole stream by [key] (Gezira's `SortBy`). */
fun <A, K : Comparable<K>> sortBy(name: String, key: (A) -> K): Stage<A, A> = object : Stage<A, A>(name) {
    override fun run(input: List<A>, trace: Trace?): List<A> = input.sortedBy(key).also { trace?.record(name, it) }
}

/** Runs of consecutive tokens with the same [key], as lists. */
fun <A, K> groupBy(name: String, key: (A) -> K): Stage<A, List<A>> = object : Stage<A, List<A>>(name) {
    override fun run(input: List<A>, trace: Trace?): List<List<A>> {
        val out = ArrayList<List<A>>()
        var cur = ArrayList<A>()
        var curKey: Any? = null
        for (a in input) {
            val k = key(a)
            if (cur.isNotEmpty() && k != curKey) {
                out.add(cur)
                cur = ArrayList()
            }
            curKey = k
            cur.add(a)
        }
        if (cur.isNotEmpty()) out.add(cur)
        trace?.record(name, out)
        return out
    }
}

/** Feed this stage's output into [next]. */
infix fun <A, B, C> Stage<A, B>.then(next: Stage<B, C>): Stage<A, C> {
    val first = this
    return object : Stage<A, C>("$first → $next") {
        override fun run(input: List<A>, trace: Trace?): List<C> = next.run(first.run(input, trace), trace)
    }
}
