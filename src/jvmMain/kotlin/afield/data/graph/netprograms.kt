package afield.data.graph

import afield.math.linalg.vec
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First-order programs with numbers, compiled to interaction nets and run by the ordinary [Reducer].
 *
 * The vocabulary is interaction combinators plus native numbers, close to what HVM and Vine's Ivy use:
 * a constructor `CON` that is a λ when its principal faces outward and an application when it faces a
 * function, a fan `DUP` that copies values, an eraser `ERA`, number literals `NUM`, binary operators `OP2`
 * (and their half-applied form `OP1`), a zero test `SWI`, and a reference `REF` that expands into a copy
 * of a named definition only when something meets it.
 *
 * That last agent is what makes recursion possible: a definition's body is a template that is stamped
 * into the net once per call, so the net never holds more of the program than is being run. Each branch
 * of a [NetExpr.Switch] is lifted into a definition of its own, taking the branch's free variables as
 * parameters, so the branch not taken is a lone `REF` meeting an eraser and costs one reaction.
 *
 * Everything is strict about types only by convention: a program that applies a number, or adds a
 * function, leaves a pair with no rule, and [NetPrograms.Run.stuck] reports it.
 */
sealed interface NetExpr {
    /** A use of a parameter or a `let` name. */
    data class Var(val name: String) : NetExpr

    /** A whole-number literal. Numbers are carried as `Long`. */
    data class Num(val value: Long) : NetExpr

    /** [op] applied to [left] and [right]. Both operands reduce at once. */
    data class Op(val op: NetOp, val left: NetExpr, val right: NetExpr) : NetExpr

    /** A call of the definition [name] with [args], one per parameter. */
    data class Call(val name: String, val args: List<NetExpr>) : NetExpr

    /** [zero] when [scrutinee] is 0, else [succ] with [pred] bound to `scrutinee - 1`. */
    data class Switch(val scrutinee: NetExpr, val zero: NetExpr, val pred: String, val succ: NetExpr) : NetExpr

    /** [body] with [name] bound to [value]. A name used more than once is copied by fans. */
    data class Let(val name: String, val value: NetExpr, val body: NetExpr) : NetExpr
}

/** The binary operators an `OP2` agent can carry. Comparisons give 1 or 0. */
enum class NetOp(val symbol: String, val apply: (Long, Long) -> Long) {
    ADD("+", { a, b -> a + b }),
    SUB("-", { a, b -> a - b }),
    MUL("*", { a, b -> a * b }),
    AND("&", { a, b -> a and b }),
    OR("|", { a, b -> a or b }),
    XOR("^", { a, b -> a xor b }),
    /** `a & ~b`. */
    ANDN("&~", { a, b -> a and b.inv() }),
    SHL("<<", { a, b -> a shl b.toInt() }),
    SHR(">>", { a, b -> a ushr b.toInt() }),
    EQ("==", { a, b -> if (a == b) 1 else 0 }),
    LT("<", { a, b -> if (a < b) 1 else 0 });
}

/** One named definition: its parameters, in call order, and its body. */
data class NetDef(val name: String, val params: List<String>, val body: NetExpr)

/**
 * Builds a [NetProgram]. Declare each function with `val name by fn()`, give it a body with `name.def { … }`,
 * and call it as `name(…)`. Parameters, the predecessor a `switch` hands its nonzero branch, and the
 * value a `let` binds are all lambda parameters, so the program spells no name as a string. Operators
 * take [NetExpr]s and plain `Int`s or `Long`s in either position, and a body may return a bare number.
 *
 * ```kotlin
 * val p = netProgram {
 *     val fib by fn()
 *     fib.def { k ->
 *         switch(k, zero = 0) { j ->
 *             switch(j, zero = 1) { i -> fib(i + 1) + fib(i) }
 *         }
 *     }
 *     main { fib(10) }
 * }
 * NetPrograms.run(p).value // 55
 * ```
 *
 * Declaring every function before giving any a body is what lets one call another, or itself: Kotlin will
 * not let a local `val` be used inside its own initializer.
 */
fun netProgram(block: NetProgramBuilder.() -> Unit): NetProgram = NetProgramBuilder().apply(block).build()

/** The receiver of [netProgram]: declares functions and builds the expressions inside them. */
class NetProgramBuilder {
    private val fns = mutableListOf<NetFn>()
    private var main: NetExpr? = null
    private var fresh = 0

    /** A variable no Kotlin name can collide with. */
    private fun fresh(): NetExpr.Var = NetExpr.Var("%${fresh++}")

    /** `val name by fn()` declares a function called `name`; see [NetFn]. */
    fun fn(): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, NetFn>> =
        PropertyDelegateProvider { _, property ->
            require(fns.none { it.name == property.name }) { "netProgram: two functions called ${property.name}" }
            val f = NetFn(property.name, this)
            fns += f
            ReadOnlyProperty { _, _ -> f }
        }

    /** A number literal, for where a bare `Int` cannot go. */
    fun n(value: Long): NetExpr = NetExpr.Num(value)

    /** A number literal, for where a bare `Int` cannot go. */
    fun n(value: Int): NetExpr = NetExpr.Num(value.toLong())

    /** [zero] when [scrutinee] is 0, otherwise [nonzero] of `scrutinee - 1`. Both may be numbers. */
    fun switch(scrutinee: Any, zero: Any, nonzero: (NetExpr) -> Any): NetExpr {
        val pred = fresh()
        return NetExpr.Switch(toExpr(scrutinee), toExpr(zero), pred.name, toExpr(nonzero(pred)))
    }

    /** [body] of [value]; the value is reduced once and copied by fans to every use. */
    fun let(value: Any, body: (NetExpr) -> Any): NetExpr {
        val x = fresh()
        return NetExpr.Let(x.name, toExpr(value), toExpr(body(x)))
    }

    /** The closed expression the program evaluates. */
    fun main(body: () -> Any) {
        main = toExpr(body())
    }

    internal fun params(count: Int): List<NetExpr.Var> = List(count) { fresh() }

    internal fun build(): NetProgram {
        val missing = fns.filter { it.def == null }.map { it.name }
        require(missing.isEmpty()) { "netProgram: ${missing.joinToString()} declared with fn() but never given a def { }" }
        return NetProgram(fns.map { it.def!! }, main ?: error("netProgram needs a main { }"))
    }
}

/**
 * A function of a [netProgram], declared by `val name by fn()`. Give it a body once with [def] and call it
 * with `name(…)`; the call's argument count is checked against the body's when the program compiles.
 */
class NetFn internal constructor(val name: String, private val builder: NetProgramBuilder) {
    internal var def: NetDef? = null

    internal fun define(arity: Int, body: (List<NetExpr.Var>) -> Any) {
        require(def == null) { "netProgram: $name has two def { } bodies" }
        val params = builder.params(arity)
        def = NetDef(name, params.map { it.name }, toExpr(body(params)))
    }

    /** Gives this function its body, a lambda of 0 parameters. */
    @JvmName("def0")
    fun def(body: () -> Any) = define(0) { v -> body() }

    /** Gives this function its body, a lambda of 1 parameter. */
    @JvmName("def1")
    fun def(body: (NetExpr) -> Any) = define(1) { v -> body(v[0]) }

    /** Gives this function its body, a lambda of 2 parameters. */
    @JvmName("def2")
    fun def(body: (NetExpr, NetExpr) -> Any) = define(2) { v -> body(v[0], v[1]) }

    /** Gives this function its body, a lambda of 3 parameters. */
    @JvmName("def3")
    fun def(body: (NetExpr, NetExpr, NetExpr) -> Any) = define(3) { v -> body(v[0], v[1], v[2]) }

    /** Gives this function its body, a lambda of 4 parameters. */
    @JvmName("def4")
    fun def(body: (NetExpr, NetExpr, NetExpr, NetExpr) -> Any) = define(4) { v -> body(v[0], v[1], v[2], v[3]) }

    /** Gives this function its body, a lambda of 5 parameters. */
    @JvmName("def5")
    fun def(body: (NetExpr, NetExpr, NetExpr, NetExpr, NetExpr) -> Any) = define(5) { v -> body(v[0], v[1], v[2], v[3], v[4]) }

    /** Gives this function its body, a lambda of 6 parameters. */
    @JvmName("def6")
    fun def(body: (NetExpr, NetExpr, NetExpr, NetExpr, NetExpr, NetExpr) -> Any) = define(6) { v -> body(v[0], v[1], v[2], v[3], v[4], v[5]) }

    /** A call of this function. Each argument is a [NetExpr], an `Int` or a `Long`. */
    operator fun invoke(vararg args: Any): NetExpr = NetExpr.Call(name, args.map { toExpr(it) })
}

/** An `Int` or `Long` as a [NetExpr.Num], or the [NetExpr] itself. */
internal fun toExpr(value: Any): NetExpr = when (value) {
    is NetExpr -> value
    is Int -> NetExpr.Num(value.toLong())
    is Long -> NetExpr.Num(value)
    else -> error("netProgram: expected an expression or a whole number, got $value (${value::class.simpleName})")
}

// Operators. Each takes an expression, an Int or a Long on either side.
operator fun NetExpr.plus(o: NetExpr): NetExpr = NetExpr.Op(NetOp.ADD, this, o)
operator fun NetExpr.plus(o: Int): NetExpr = NetExpr.Op(NetOp.ADD, this, NetExpr.Num(o.toLong()))
operator fun NetExpr.plus(o: Long): NetExpr = NetExpr.Op(NetOp.ADD, this, NetExpr.Num(o))
operator fun Int.plus(o: NetExpr): NetExpr = NetExpr.Op(NetOp.ADD, NetExpr.Num(toLong()), o)
operator fun Long.plus(o: NetExpr): NetExpr = NetExpr.Op(NetOp.ADD, NetExpr.Num(this), o)
operator fun NetExpr.minus(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SUB, this, o)
operator fun NetExpr.minus(o: Int): NetExpr = NetExpr.Op(NetOp.SUB, this, NetExpr.Num(o.toLong()))
operator fun NetExpr.minus(o: Long): NetExpr = NetExpr.Op(NetOp.SUB, this, NetExpr.Num(o))
operator fun Int.minus(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SUB, NetExpr.Num(toLong()), o)
operator fun Long.minus(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SUB, NetExpr.Num(this), o)
operator fun NetExpr.times(o: NetExpr): NetExpr = NetExpr.Op(NetOp.MUL, this, o)
operator fun NetExpr.times(o: Int): NetExpr = NetExpr.Op(NetOp.MUL, this, NetExpr.Num(o.toLong()))
operator fun NetExpr.times(o: Long): NetExpr = NetExpr.Op(NetOp.MUL, this, NetExpr.Num(o))
operator fun Int.times(o: NetExpr): NetExpr = NetExpr.Op(NetOp.MUL, NetExpr.Num(toLong()), o)
operator fun Long.times(o: NetExpr): NetExpr = NetExpr.Op(NetOp.MUL, NetExpr.Num(this), o)
infix fun NetExpr.and(o: NetExpr): NetExpr = NetExpr.Op(NetOp.AND, this, o)
infix fun NetExpr.and(o: Int): NetExpr = NetExpr.Op(NetOp.AND, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.and(o: Long): NetExpr = NetExpr.Op(NetOp.AND, this, NetExpr.Num(o))
infix fun Int.and(o: NetExpr): NetExpr = NetExpr.Op(NetOp.AND, NetExpr.Num(toLong()), o)
infix fun Long.and(o: NetExpr): NetExpr = NetExpr.Op(NetOp.AND, NetExpr.Num(this), o)
infix fun NetExpr.or(o: NetExpr): NetExpr = NetExpr.Op(NetOp.OR, this, o)
infix fun NetExpr.or(o: Int): NetExpr = NetExpr.Op(NetOp.OR, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.or(o: Long): NetExpr = NetExpr.Op(NetOp.OR, this, NetExpr.Num(o))
infix fun Int.or(o: NetExpr): NetExpr = NetExpr.Op(NetOp.OR, NetExpr.Num(toLong()), o)
infix fun Long.or(o: NetExpr): NetExpr = NetExpr.Op(NetOp.OR, NetExpr.Num(this), o)
infix fun NetExpr.xor(o: NetExpr): NetExpr = NetExpr.Op(NetOp.XOR, this, o)
infix fun NetExpr.xor(o: Int): NetExpr = NetExpr.Op(NetOp.XOR, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.xor(o: Long): NetExpr = NetExpr.Op(NetOp.XOR, this, NetExpr.Num(o))
infix fun Int.xor(o: NetExpr): NetExpr = NetExpr.Op(NetOp.XOR, NetExpr.Num(toLong()), o)
infix fun Long.xor(o: NetExpr): NetExpr = NetExpr.Op(NetOp.XOR, NetExpr.Num(this), o)
infix fun NetExpr.andNot(o: NetExpr): NetExpr = NetExpr.Op(NetOp.ANDN, this, o)
infix fun NetExpr.andNot(o: Int): NetExpr = NetExpr.Op(NetOp.ANDN, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.andNot(o: Long): NetExpr = NetExpr.Op(NetOp.ANDN, this, NetExpr.Num(o))
infix fun Int.andNot(o: NetExpr): NetExpr = NetExpr.Op(NetOp.ANDN, NetExpr.Num(toLong()), o)
infix fun Long.andNot(o: NetExpr): NetExpr = NetExpr.Op(NetOp.ANDN, NetExpr.Num(this), o)
infix fun NetExpr.shl(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SHL, this, o)
infix fun NetExpr.shl(o: Int): NetExpr = NetExpr.Op(NetOp.SHL, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.shl(o: Long): NetExpr = NetExpr.Op(NetOp.SHL, this, NetExpr.Num(o))
infix fun Int.shl(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SHL, NetExpr.Num(toLong()), o)
infix fun Long.shl(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SHL, NetExpr.Num(this), o)
infix fun NetExpr.shr(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SHR, this, o)
infix fun NetExpr.shr(o: Int): NetExpr = NetExpr.Op(NetOp.SHR, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.shr(o: Long): NetExpr = NetExpr.Op(NetOp.SHR, this, NetExpr.Num(o))
infix fun Int.shr(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SHR, NetExpr.Num(toLong()), o)
infix fun Long.shr(o: NetExpr): NetExpr = NetExpr.Op(NetOp.SHR, NetExpr.Num(this), o)
infix fun NetExpr.eq(o: NetExpr): NetExpr = NetExpr.Op(NetOp.EQ, this, o)
infix fun NetExpr.eq(o: Int): NetExpr = NetExpr.Op(NetOp.EQ, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.eq(o: Long): NetExpr = NetExpr.Op(NetOp.EQ, this, NetExpr.Num(o))
infix fun Int.eq(o: NetExpr): NetExpr = NetExpr.Op(NetOp.EQ, NetExpr.Num(toLong()), o)
infix fun Long.eq(o: NetExpr): NetExpr = NetExpr.Op(NetOp.EQ, NetExpr.Num(this), o)
infix fun NetExpr.lt(o: NetExpr): NetExpr = NetExpr.Op(NetOp.LT, this, o)
infix fun NetExpr.lt(o: Int): NetExpr = NetExpr.Op(NetOp.LT, this, NetExpr.Num(o.toLong()))
infix fun NetExpr.lt(o: Long): NetExpr = NetExpr.Op(NetOp.LT, this, NetExpr.Num(o))
infix fun Int.lt(o: NetExpr): NetExpr = NetExpr.Op(NetOp.LT, NetExpr.Num(toLong()), o)
infix fun Long.lt(o: NetExpr): NetExpr = NetExpr.Op(NetOp.LT, NetExpr.Num(this), o)

/** A set of definitions and the expression to evaluate. Compile and run it with [NetPrograms]. */
class NetProgram(val defs: List<NetDef>, val main: NetExpr)

/**
 * The agents, the compiler and the rule book for [NetProgram]s, plus [run] and [queens].
 */
object NetPrograms {
    val CON = Types.agent("CON", 2)
    val DUP = Types.agent("DUP", 2)
    val ERA = Types.agent("ERA", 0)
    val NUM = Types.agent("NUM", 0)
    val OP2 = Types.agent("OP2", 2)
    val OP1 = Types.agent("OP1", 1)
    val SWI = Types.agent("SWI", 2)
    val REF = Types.agent("REF", 0)
    /** Holds the answer on its auxiliary port, so a finished net has no pair left facing it. */
    val ROOT = Types.agent("ROOT", 1)

    /** Scalar keys in [NodeState.scalars]. */
    const val VALUE = "value"
    const val OP = "op"
    const val LEFT = "left"
    const val DEF = "def"

    /** The high and low 32-bit halves of a value, kept beside [VALUE] so a `Long` survives the `Double` scalars exactly. */
    private const val VALUE_HI = "value.hi"
    private const val VALUE_LO = "value.lo"
    private const val LEFT_HI = "left.hi"
    private const val LEFT_LO = "left.lo"

    /** Scalars that hold [v] exactly: [VALUE] (or [LEFT]) for display, plus its two 32-bit halves. */
    private fun exact(key: String, hi: String, lo: String, v: Long): Map<String, Double> =
        mapOf(key to v.toDouble(), hi to (v shr 32).toDouble(), lo to (v and 0xffffffffL).toDouble())

    private fun exactOf(s: Map<String, Double>, key: String, hi: String, lo: String): Long {
        val h = s[hi]
        val l = s[lo]
        return if (h != null && l != null) (h.toLong() shl 32) or l.toLong() else (s[key] ?: 0.0).toLong()
    }

    private fun numScalars(v: Long) = exact(VALUE, VALUE_HI, VALUE_LO, v)

    /** The value a `NUM` node holds, exactly, across the whole `Long` range. */
    fun valueOf(node: Node): Long = exactOf(node.state.scalars, VALUE, VALUE_HI, VALUE_LO)

    /** A port in a template: node index and raw port index (0 is principal). */
    internal data class TPort(val node: Int, val port: Int)

    /** A compiled definition: nodes, internal wires, and the port that faces whoever meets its `REF`. */
    class Template internal constructor(
        val name: String,
        internal val types: List<AgentType>,
        internal val scalars: List<Map<String, Double>>,
        internal val wires: List<Pair<TPort, TPort>>,
        internal val root: TPort,
    ) {
        /** How many agents one expansion stamps into the net. */
        val size: Int get() = types.size
    }

    /** Every definition compiled, including the switch branches lifted out of them, plus `main`. */
    class Compiled internal constructor(val templates: List<Template>, val index: Map<String, Int>) {
        val main: Template get() = templates[index.getValue(MAIN)]
        fun template(name: String): Template = templates[index.getValue(name)]
    }

    private const val MAIN = "main"

    // ---------------------------------------------------------------- compiler

    /**
     * Lowers every definition to a [Template]. Throws on an unknown variable or definition, or a call
     * with the wrong number of arguments.
     */
    fun compile(program: NetProgram): Compiled {
        val pending = ArrayDeque<NetDef>()
        pending += program.defs
        pending += NetDef(MAIN, emptyList(), program.main)
        val arity = HashMap<String, Int>()
        program.defs.forEach { arity[it.name] = it.params.size }
        val out = mutableListOf<NetDef>()
        // Lift switches first, so every definition the templates refer to has an index.
        while (pending.isNotEmpty()) {
            val d = pending.removeFirst()
            var counter = 0
            fun lift(e: NetExpr, bound: List<String>): NetExpr = when (e) {
                is NetExpr.Var, is NetExpr.Num -> e
                is NetExpr.Op -> NetExpr.Op(e.op, lift(e.left, bound), lift(e.right, bound))
                is NetExpr.Call -> NetExpr.Call(e.name, e.args.map { lift(it, bound) })
                is NetExpr.Let -> NetExpr.Let(e.name, lift(e.value, bound), lift(e.body, bound + e.name))
                is NetExpr.Switch -> {
                    val k = counter++
                    val zeroName = "${d.name}\$z$k"
                    val succName = "${d.name}\$s$k"
                    val free = (freeVars(e.zero) + (freeVars(e.succ) - e.pred))
                        .filter { it in bound }.distinct()
                    pending += NetDef(zeroName, free, e.zero)
                    pending += NetDef(succName, listOf(e.pred) + free, e.succ)
                    arity[zeroName] = free.size
                    arity[succName] = free.size + 1
                    LiftedSwitch(lift(e.scrutinee, bound), zeroName, succName, free)
                }
                is LiftedSwitch -> e
            }
            out += NetDef(d.name, d.params, lift(d.body, d.params))
        }
        val index = out.withIndex().associate { (i, d) -> d.name to i }
        val templates = out.map { TemplateCompiler(it, index, arity).compile() }
        return Compiled(templates, index)
    }

    /** A switch after lifting: the branches are now definitions that take [free] after the predecessor. */
    private data class LiftedSwitch(
        val scrutinee: NetExpr, val zero: String, val succ: String, val free: List<String>,
    ) : NetExpr

    private fun freeVars(e: NetExpr): List<String> = when (e) {
        is NetExpr.Var -> listOf(e.name)
        is NetExpr.Num -> emptyList()
        is NetExpr.Op -> freeVars(e.left) + freeVars(e.right)
        is NetExpr.Call -> e.args.flatMap { freeVars(it) }
        is NetExpr.Let -> freeVars(e.value) + (freeVars(e.body) - e.name)
        is NetExpr.Switch -> freeVars(e.scrutinee) + freeVars(e.zero) + (freeVars(e.succ) - e.pred)
        is LiftedSwitch -> freeVars(e.scrutinee) + e.free
    }

    private class TemplateCompiler(val def: NetDef, val index: Map<String, Int>, val arity: Map<String, Int>) {
        val types = mutableListOf<AgentType>()
        val scalars = mutableListOf<Map<String, Double>>()
        val wires = mutableListOf<Pair<TPort, TPort>>()
        val scopes = ArrayDeque<Pair<String, MutableList<TPort>>>()
        val outside = TPort(-1, 0)

        fun add(type: AgentType, s: Map<String, Double> = emptyMap()): Int {
            types += type; scalars += s; return types.size - 1
        }

        fun p(node: Int) = TPort(node, 0)
        fun a(node: Int, i: Int) = TPort(node, i + 1)
        fun wire(x: TPort, y: TPort) { wires += x to y }

        var root: TPort? = null
        fun sinkTo(port: TPort, sink: TPort) {
            if (sink == outside) root = port else wire(port, sink)
        }

        fun compile(): Template {
            if (def.params.isEmpty()) {
                expr(def.body, outside)
            } else {
                val lams = def.params.map { add(CON) }
                root = p(lams.first())
                for (i in 0 until lams.size - 1) wire(a(lams[i], 1), p(lams[i + 1]))
                val uses = def.params.map { name -> mutableListOf<TPort>().also { scopes.addLast(name to it) } }
                expr(def.body, a(lams.last(), 1))
                repeat(def.params.size) { scopes.removeLast() }
                def.params.indices.forEach { i -> wire(a(lams[i], 0), shareSink(uses[i])) }
            }
            return Template(def.name, types.toList(), scalars.toList(), wires.toList(), root!!)
        }

        /** The port a value should be wired to so that it reaches every one of [uses]. */
        fun shareSink(uses: List<TPort>): TPort = when (uses.size) {
            0 -> p(add(ERA))
            1 -> uses[0]
            else -> {
                val first = add(DUP)
                var dup = first
                for (i in 0 until uses.size - 1) {
                    wire(a(dup, 0), uses[i])
                    if (i == uses.size - 2) {
                        wire(a(dup, 1), uses[i + 1])
                    } else {
                        val next = add(DUP)
                        wire(a(dup, 1), p(next))
                        dup = next
                    }
                }
                p(first)
            }
        }

        fun expr(e: NetExpr, sink: TPort) {
            when (e) {
                is NetExpr.Var -> {
                    val scope = scopes.lastOrNull { it.first == e.name }
                        ?: error("${def.name}: unknown variable ${e.name}")
                    require(sink != outside) { "${def.name}: a definition cannot be a bare variable" }
                    scope.second += sink
                }
                is NetExpr.Num -> sinkTo(p(add(NUM, numScalars(e.value))), sink)
                is NetExpr.Op -> {
                    val op = add(OP2, mapOf(OP to e.op.ordinal.toDouble()))
                    sinkTo(a(op, 1), sink)
                    expr(e.left, p(op))
                    expr(e.right, a(op, 0))
                }
                is NetExpr.Call -> {
                    val target = index[e.name] ?: error("${def.name}: unknown definition ${e.name}")
                    require(arity[e.name] == e.args.size) {
                        "${def.name}: ${e.name} takes ${arity[e.name]} arguments, called with ${e.args.size}"
                    }
                    applyTo(p(add(REF, mapOf(DEF to target.toDouble()))), e.args, sink)
                }
                is NetExpr.Let -> {
                    val uses = mutableListOf<TPort>()
                    scopes.addLast(e.name to uses)
                    expr(e.body, sink)
                    scopes.removeLast()
                    expr(e.value, shareSink(uses))
                }
                is LiftedSwitch -> {
                    val swi = add(SWI)
                    val pair = add(CON)
                    wire(a(swi, 0), p(pair))
                    wire(a(pair, 0), p(add(REF, mapOf(DEF to index.getValue(e.zero).toDouble()))))
                    wire(a(pair, 1), p(add(REF, mapOf(DEF to index.getValue(e.succ).toDouble()))))
                    applyTo(a(swi, 1), e.free.map { NetExpr.Var(it) }, sink)
                    expr(e.scrutinee, p(swi))
                }
                is NetExpr.Switch -> error("switch should have been lifted")
            }
        }

        /** Applies the function arriving at [fn] to [args] through a chain of `CON`s, result to [sink]. */
        fun applyTo(fn: TPort, args: List<NetExpr>, sink: TPort) {
            var f = fn
            for (arg in args) {
                val app = add(CON)
                wire(f, p(app))
                expr(arg, a(app, 0))
                f = a(app, 1)
            }
            sinkTo(f, sink)
        }
    }

    // ---------------------------------------------------------------- rules

    private fun state(scalars: Map<String, Double> = emptyMap()) =
        NodeState(energy = 1.0, scalars = LinkedHashMap(scalars))

    /** Offsets that spread a stamped template around the collision point instead of on top of it. */
    private fun spread(i: Int, scale: Double = 9.0) =
        vec(cos(i * 2.39996) * scale * sqrt(i + 1.0), sin(i * 2.39996) * scale * sqrt(i + 1.0))

    /**
     * The rule book for [compiled]: annihilation, erasure, duplication, arithmetic, switching, and `REF`
     * expansion. Rules are matched by agent pair, so the book holds one rule per pair that can meet.
     */
    fun rules(compiled: Compiled): RuleBook = rules {
        val withAux = listOf(CON, DUP, OP2, OP1, SWI)

        rule("annihilate", CON, CON) { ctx ->
            passthrough(ctx.auxPeer(0, 0), ctx.auxPeer(1, 0))
            passthrough(ctx.auxPeer(0, 1), ctx.auxPeer(1, 1))
        }
        rule("fan-fan", DUP, DUP) { ctx ->
            passthrough(ctx.auxPeer(0, 0), ctx.auxPeer(1, 0))
            passthrough(ctx.auxPeer(0, 1), ctx.auxPeer(1, 1))
        }
        for (other in listOf(CON, OP2, OP1, SWI)) {
            rule("commute", DUP, other) { ctx -> commute(ctx) }
        }
        for (other in withAux + listOf(NUM, REF, ERA)) {
            rule("erase", ERA, other) { ctx ->
                for (i in 0 until ctx.second.type.auxPorts) {
                    reconnect(principal(node("e$i", ERA, spread(i), state())), 1, i)
                }
            }
        }
        rule("copy-num", DUP, NUM) { ctx ->
            for (i in 0..1) {
                val n = node("n$i", NUM, ctx.branchImpulse(i), ctx.stateCopy(1))
                reconnect(principal(n), 0, i)
            }
        }
        rule("copy-ref", DUP, REF) { ctx ->
            for (i in 0..1) {
                val r = node("r$i", REF, ctx.branchImpulse(i), ctx.stateCopy(1))
                reconnect(principal(r), 0, i)
            }
        }
        rule("op-left", OP2, NUM) { ctx ->
            val s = ctx.stateCopy(0)
            s.scalars.putAll(exact(LEFT, LEFT_HI, LEFT_LO, valueOf(ctx.second)))
            val half = node("op", OP1, vec(0.0, 0.0), s)
            reconnect(principal(half), 0, 0)
            reconnect(aux(half, 0), 0, 1)
        }
        rule("op-right", OP1, NUM) { ctx ->
            val op = NetOp.entries[(ctx.first.state.scalars[OP] ?: 0.0).toInt()]
            val l = exactOf(ctx.first.state.scalars, LEFT, LEFT_HI, LEFT_LO)
            val r = valueOf(ctx.second)
            val n = node("n", NUM, vec(0.0, 0.0), state(numScalars(op.apply(l, r))))
            reconnect(principal(n), 0, 0)
        }
        rule("switch", SWI, NUM) { ctx ->
            val k = valueOf(ctx.second)
            // A selector that meets the (zero, succ) pair: its aux 0 takes the zero branch, aux 1 the succ.
            val sel = node("sel", CON, vec(0.0, 0.0), state())
            reconnect(principal(sel), 0, 0)
            val era = node("era", ERA, spread(1), state())
            if (k == 0L) {
                reconnect(aux(sel, 0), 0, 1)
                aux(sel, 1) to principal(era)
            } else {
                aux(sel, 0) to principal(era)
                val app = node("app", CON, spread(2), state())
                aux(sel, 1) to principal(app)
                val pred = node("pred", NUM, spread(3), state(numScalars(k - 1)))
                aux(app, 0) to principal(pred)
                reconnect(aux(app, 1), 0, 1)
            }
        }
        for (other in listOf(CON, OP2, OP1, SWI, NUM)) {
            rule("expand", REF, other) { ctx ->
                val t = compiled.templates[(ctx.first.state.scalars[DEF] ?: 0.0).toInt()]
                val refs = t.types.indices.map { i -> node("t$i", t.types[i], spread(i), state(t.scalars[i])) }
                fun port(tp: TPort): PortRef = if (tp.port == 0) principal(refs[tp.node]) else aux(refs[tp.node], tp.port - 1)
                for ((x, y) in t.wires) port(x) to port(y)
                // The pair is consumed, so the agent the REF met is stamped again, facing the new body.
                val again = node("x", ctx.second.type, vec(0.0, 0.0), ctx.stateCopy(1))
                port(t.root) to principal(again)
                for (i in 0 until ctx.second.type.auxPorts) reconnect(aux(again, i), 1, i)
                emit("expand", data = mapOf(DEF to (ctx.first.state.scalars[DEF] ?: 0.0)))
            }
        }
    }

    /** The commutation of a fan (ordinal 0) through any agent with auxiliaries (ordinal 1). */
    private fun RewriteBuilder.commute(ctx: PairContext) {
        val m = ctx.first.type.auxPorts
        val n = ctx.second.type.auxPorts
        val as_ = (0 until n).map { j -> node("a$j", ctx.first.type, ctx.branchImpulse(j, n), ctx.stateCopy(0)) }
        val bs = (0 until m).map { i -> node("b$i", ctx.second.type, ctx.branchImpulse(i, m), ctx.stateCopy(1)) }
        as_.forEachIndexed { j, a -> reconnect(principal(a), 1, j) }
        bs.forEachIndexed { i, b -> reconnect(principal(b), 0, i) }
        for (j in 0 until n) for (i in 0 until m) aux(as_[j], i) to aux(bs[i], j)
    }

    // ---------------------------------------------------------------- running

    /** The program's `main`, stamped into a fresh net under a `ROOT`. */
    fun instantiate(compiled: Compiled): Net {
        val t = compiled.main
        val net = net { }
        val nodes = t.types.indices.map { i ->
            net.create(t.types[i], state = state(t.scalars[i])).also { net.setPosition(it, spread(i, 14.0)) }
        }
        fun port(tp: TPort): Port = nodes[tp.node].ports[tp.port]
        for ((x, y) in t.wires) net.connect(port(x), port(y))
        val root = net.create(ROOT, id = "root", state = state())
        net.setPosition(root, vec(0.0, -60.0))
        require(!(t.root.port == 0 && t.types[t.root.node] == REF)) {
            "main cannot be a call of a function with no parameters: nothing would meet its REF"
        }
        net.connect(port(t.root), root.aux(0))
        return net
    }

    /**
     * What to write across [node] when drawing: a number's value, an operator's symbol (with its left operand
     * once it has one), and nothing for the other agents. Pass it to [Net.toPngBytes] as `label`.
     */
    fun label(node: Node): String? = when (node.type) {
        NUM -> valueOf(node).toString()
        OP2 -> NetOp.entries[(node.state.scalars[OP] ?: 0.0).toInt()].symbol
        OP1 -> "${exactOf(node.state.scalars, LEFT, LEFT_HI, LEFT_LO)}${NetOp.entries[(node.state.scalars[OP] ?: 0.0).toInt()].symbol}"
        else -> null
    }

    /** The number `ROOT` is holding, or null while it holds anything else. */
    fun resultOf(net: Net): Long? {
        val root = net.nodes.firstOrNull { it.type == ROOT } ?: return null
        val peer = net.connected(root.aux(0))?.node ?: return null
        return if (peer.type == NUM) valueOf(peer) else null
    }

    /**
     * What a run did. [interactions] counts every rule that fired; [rounds] is the number of
     * [Reducer.stepFrontier] rounds, which is the length of the longest chain of reactions that had to
     * wait for each other; [widest] is the most reactions any one round fired.
     */
    data class Run(
        val value: Long?,
        val interactions: Int,
        val rounds: Int,
        val widest: Int,
        val peakAgents: Int,
        val stuck: List<String>,
        val perRound: List<Int>,
        val ruleCounts: Map<String, Int>,
    )

    /** Reduces [program] to normal form, firing every ready pair each round, without layout. */
    fun run(program: NetProgram, maxRounds: Int = 100_000): Run {
        val compiled = compile(program)
        val net = instantiate(compiled)
        val reducer = Reducer(rules(compiled), relaxIterations = 0)
        val perRound = mutableListOf<Int>()
        val counts = LinkedHashMap<String, Int>()
        var peak = net.nodes.size
        while (perRound.size < maxRounds) {
            val fired = reducer.stepFrontier(net, relaxIterations = 0)
            if (fired.isEmpty()) break
            perRound += fired.size
            fired.forEach { counts.merge(it.ruleName, 1, Int::plus) }
            peak = maxOf(peak, net.nodes.size)
        }
        val stuck = net.activePairs().map { "${it.left.type.name}-${it.right.type.name}" }
        return Run(resultOf(net), perRound.sum(), perRound.size, perRound.maxOrNull() ?: 0, peak, stuck, perRound, counts)
    }

    /**
     * N-queens as a [NetProgram]: the number of ways to place [n] queens on an n×n board. Each row tries
     * every free column, and the two halves of each choice, this column and every later column, are
     * added with an `OP2` so they reduce side by side. Columns and both diagonals are bit masks.
     */
    fun queens(size: Int): NetProgram {
        require(size in 1..16) { "n must be in 1..16" }
        val full = (1L shl size) - 1
        return netProgram {
            val solve by fn()
            val place by fn()
            solve.def { row, cols, d1, d2 ->
                switch(row eq size, zero = place(row, cols, d1, d2, full andNot (cols or d1 or d2))) { _ -> 1 }
            }
            // avail is the set of free columns; p = avail - 1, so avail & ~p is its lowest bit.
            place.def { row, cols, d1, d2, avail ->
                switch(avail, zero = 0) { p ->
                    let(p + 1) { a ->
                        let(a andNot p) { bit ->
                            solve(row + 1, cols or bit, (d1 or bit) shl 1, (d2 or bit) shr 1) +
                                place(row, cols, d1, d2, a and p)
                        }
                    }
                }
            }
            main { solve(0, 0, 0, 0) }
        }
    }

    /** The same count by ordinary backtracking, to check [queens] against. */
    fun queensReference(n: Int): Long {
        val full = (1L shl n) - 1
        fun go(cols: Long, d1: Long, d2: Long): Long {
            if (cols == full) return 1
            var avail = full and (cols or d1 or d2).inv()
            var total = 0L
            while (avail != 0L) {
                val bit = avail and -avail
                avail = avail and (avail - 1)
                total += go(cols or bit, ((d1 or bit) shl 1) and full, (d2 or bit) shr 1)
            }
            return total
        }
        return go(0, 0, 0)
    }
}
