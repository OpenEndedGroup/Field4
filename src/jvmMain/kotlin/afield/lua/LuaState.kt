package afield.lua

import afield.lua.LuaNative.LUA_GLOBALSINDEX
import afield.lua.LuaNative.LUA_MULTRET
import afield.lua.LuaNative.LUA_REGISTRYINDEX
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One LuaJIT `lua_State`: open, evaluate, marshal values both ways, let Lua call Kotlin, close.
 *
 * Values cross as plain data. Lua `nil`, booleans, numbers and strings become `null`, [Boolean],
 * [Double] and [String]; a table whose keys are exactly `1..n` becomes a [List], any other table a
 * [Map] (keys as [String] or [Double]); a function becomes a [LuaFunction] that Kotlin can call back.
 * Going the other way, [Number]s, [CharSequence]s, [Map]s, [Iterable]s and arrays become their Lua
 * counterparts, a [afield.Rect] becomes `{x=, y=, w=, h=}`, a [Handler] becomes a Lua function, and
 * anything else its `toString()`.
 *
 * **The FFM rule this class is built around: a Lua error must never unwind through a Java frame.**
 * LuaJIT raises errors by unwinding the C stack to the nearest `lua_pcall`. If a Kotlin [Handler] — an
 * FFM upcall — called `lua_error`, or touched a table with a metamethod that raised, the unwind would
 * cross the upcall's Java frames and take the VM down. So a handler never raises: [dispatch] catches
 * everything it throws and returns `false, message` to Lua, and the Lua-side wrapper the prelude puts
 * around every handler turns that into an ordinary Lua `error` at the caller's line. Inside a handler
 * only raw table access is used (`lua_rawget`, `lua_next`), which calls no metamethods. And every
 * evaluation from Kotlin is a `lua_pcall`, so nothing Lua raises reaches the panic handler.
 *
 * Not thread-safe by itself; every public method takes this state's lock. Lua code runs on whichever
 * thread calls [run] or [call] — in a box, the main Runloop thread.
 */
class LuaState(raw: Map<String, Handler> = emptyMap()) : AutoCloseable {

    /** A Kotlin function Lua can call. [args] are marshalled Lua values; the returned list is pushed as the results. */
    fun interface Handler {
        fun call(state: LuaState, args: List<Any?>): List<Any?>
    }

    /** A Lua function held from Kotlin: a reference in the registry, released when the state closes or [release] is called. */
    class LuaFunction internal constructor(val state: LuaState, internal val ref: Int) {
        /** Call it through [LuaState.call]; the result is [Result.Ok] with its return values or [Result.Err]. */
        operator fun invoke(vararg args: Any?): Result = state.call(this, *args)

        /** Drop the registry reference now rather than when the state closes. */
        fun release() = state.releaseRef(ref)

        override fun toString() = "a Lua function"
    }

    /** What an evaluation came to. */
    sealed class Result {
        /** Everything the chunk or function returned. */
        class Ok(val values: List<Any?>) : Result()

        /**
         * The error: [message] without its `box:12:` position, [line] the 1-based line of the chunk it
         * happened on when one is known, [syntax] true when the chunk did not compile.
         */
        class Err(val message: String, val line: Int?, val syntax: Boolean = false, val traceback: String? = null) : Result() {
            override fun toString() = (line?.let { "line $it: " } ?: "") + message
        }
    }

    private val lock = ReentrantLock()
    private var l: MemorySegment? = LuaNative.newState().also {
        require(it.address() != 0L) { "luaL_newstate returned NULL" }
        LuaNative.openLibs(it)
    }
    private val handlerIds = ArrayList<Int>()
    private val refs = HashSet<Int>()

    /** True once [close] has run; every other method then refuses. */
    val isClosed: Boolean get() = l == null

    init {
        lock.withLock {
            val s = l!!
            // The raw handlers go in a table the prelude takes and then removes from the globals.
            LuaNative.createTable(s, 0, 16)
            (defaultRaw + raw).forEach { (name, handler) -> pushString(s, name); pushHandler(s, handler); LuaNative.rawSet(s, -3) }
            setGlobalFromStack(s, RAW_TABLE)
            val loaded = run(PRELUDE, chunkName = "=field")
            if (loaded !is Result.Ok) {
                LuaNative.close(s)
                l = null
                throw IllegalStateException("the Lua prelude did not load: $loaded")
            }
            openStates.incrementAndGet()
        }
    }

    private fun live(): MemorySegment = l ?: throw IllegalStateException("this Lua state is closed")

    /** `jit.version` — "LuaJIT 2.1.…" — or `_VERSION` for a plain Lua. */
    val version: String
        get() = (run("return jit and jit.version or _VERSION") as? Result.Ok)?.values?.firstOrNull()?.toString() ?: "?"

    /** Kilobytes the Lua heap holds. */
    val memoryKb: Int get() = lock.withLock { LuaNative.gc(live(), LuaNative.LUA_GCCOUNT, 0) }

    /** Stack depth; zero between calls. For tests, which assert nothing is left behind. */
    internal val stackTop: Int get() = lock.withLock { LuaNative.getTop(live()) }

    /** Set global [name] to [value], marshalled. A [Handler] becomes a Lua function that raises on failure. */
    fun setGlobal(name: String, value: Any?) = lock.withLock {
        val s = live()
        if (value is Handler) {
            // Wrapped, so a failing Kotlin function raises a Lua error at the caller's line.
            val wrapped = call("__field_checked", value)
            if (wrapped is Result.Ok) {
                pushString(s, name); push(s, wrapped.values.firstOrNull(), 0); LuaNative.rawSet(s, LUA_GLOBALSINDEX)
                (wrapped.values.firstOrNull() as? LuaFunction)?.release()
            }
            return@withLock
        }
        pushString(s, name); push(s, value, 0); LuaNative.rawSet(s, LUA_GLOBALSINDEX)
    }

    /** Global [name], marshalled; raw access, so a strict-mode metatable on `_G` is not consulted. */
    fun getGlobal(name: String): Any? = lock.withLock {
        val s = live()
        pushString(s, name); LuaNative.rawGet(s, LUA_GLOBALSINDEX)
        try { toKotlin(s, -1, 0, HashSet()) } finally { LuaNative.pop(s, 1) }
    }

    /**
     * Compile and run [code] as a chunk called [chunkName] (the name Lua puts in front of an error's line
     * number: `=box` gives `box:3: …`). Returns what the chunk returned, or the error.
     */
    fun run(code: String, chunkName: String = CHUNK): Result = lock.withLock {
        val loaded = load(code, chunkName)
        if (loaded !is LuaFunction) return@withLock loaded as Result
        try { call(loaded) } finally { loaded.release() }
    }

    /** Compile [code] without running it: a [LuaFunction], or the syntax error as a [Result.Err]. */
    fun load(code: String, chunkName: String = CHUNK): Any = lock.withLock {
        val s = live()
        val top = LuaNative.getTop(s)
        Arena.ofConfined().use { arena ->
            val bytes = code.toByteArray(Charsets.UTF_8)
            val buf = arena.allocate(bytes.size.toLong().coerceAtLeast(1))
            MemorySegment.copy(bytes, 0, buf, JAVA_BYTE, 0, bytes.size)
            val status = LuaNative.loadBuffer(s, buf, bytes.size.toLong(), arena.allocateFrom(chunkName))
            if (status != LuaNative.LUA_OK) {
                val message = if (LuaNative.type(s, -1) == LuaNative.LUA_TSTRING) readString(s, -1) else "could not load the chunk (status $status)"
                LuaNative.setTop(s, top)
                return@withLock errorFrom(message, null, null, syntax = true)
            }
            LuaFunction(this, refTop(s))
        }
    }

    /**
     * What one guarded run came to: the [result], and when lines were recorded, for each 1-based line
     * of the chunk that ran, how often and (for the main chunk) the names it assigned with their values.
     */
    class Outcome(val result: Result, val lines: Map<Int, LineRecord>, val truncated: Boolean)

    /** One recorded line: [count] times it ran, [main] whether in the chunk's top level, [values] name → value as shown. */
    class LineRecord(val count: Int, val main: Boolean, val values: List<Pair<String, String>>)

    /**
     * Run [code] under the prelude's guard — stopped with an error after `field.budget` seconds — and,
     * when [lineNames] is given (1-based chunk line → names assigned on it), record its lines as it runs.
     * With [asExpression], `return <code>` is tried first, the way the Lua REPL treats a line.
     */
    fun runGuarded(code: String, lineNames: Map<Int, List<String>>?, asExpression: Boolean = false): Outcome = lock.withLock {
        val compiled = (if (asExpression) load("return $code") as? LuaFunction else null) ?: load(code)
        if (compiled !is LuaFunction) return@withLock Outcome(compiled as Result, emptyMap(), false)
        try {
            guarded(compiled, lineNames?.mapValues { it.value } ?: false, perFrame = false) { call(compiled) }
        } finally {
            compiled.release()
        }
    }

    /** One frame of a launched box: `update(dt)` and the due tasks, guarded by `field.frameBudget`. */
    fun tick(now: Double, dt: Double): Result = lock.withLock {
        guarded(null, false, perFrame = true) { call("__field_tick", now, dt) }.result
    }

    /** Call [fn] under the frame budget: a callback from Field (a slider moving) into the box's Lua. */
    fun callGuarded(fn: LuaFunction, vararg args: Any?): Result = lock.withLock {
        guarded(null, false, perFrame = true) { call(fn, *args) }.result
    }

    private fun guarded(fn: LuaFunction?, names: Any, perFrame: Boolean, body: () -> Result): Outcome {
        val begun = call("__field_begin", fn, names.takeIf { it != false }, perFrame)
        if (begun is Result.Err) return Outcome(begun, emptyMap(), false)
        val result = body()
        val ended = call("__field_end")
        val values = (ended as? Result.Ok)?.values.orEmpty()
        // The record is keyed by line; when the lines that ran are exactly 1..n it arrives as a List.
        val entries: List<Pair<Int, Any?>> = when (val rec = values.getOrNull(0)) {
            is Map<*, *> -> rec.entries.mapNotNull { (k, v) -> (k as? Double)?.toInt()?.let { it to v } }
            is List<*> -> rec.mapIndexed { i, v -> (i + 1) to v }
            else -> emptyList()
        }
        val lines = LinkedHashMap<Int, LineRecord>()
        for ((line, v) in entries) {
            val r = v as? Map<*, *> ?: continue
            val vals = (r["values"] as? List<*>).orEmpty().mapNotNull { pair ->
                val p = pair as? List<*> ?: return@mapNotNull null
                (p.getOrNull(0)?.toString() ?: return@mapNotNull null) to (p.getOrNull(1)?.toString() ?: "nil")
            }
            lines[line] = LineRecord((r["count"] as? Double)?.toInt() ?: 0, r["main"] == true, vals)
        }
        return Outcome(result, lines, values.getOrNull(1) == true)
    }

    /** Call the global function [name] with [args]. */
    fun call(name: String, vararg args: Any?): Result = lock.withLock {
        val s = live()
        pushString(s, name); LuaNative.rawGet(s, LUA_GLOBALSINDEX)
        callTop(s, args)
    }

    /** Call [fn] with [args]. */
    fun call(fn: LuaFunction, vararg args: Any?): Result = lock.withLock {
        require(fn.state === this) { "that function belongs to another Lua state" }
        val s = live()
        LuaNative.rawGetI(s, LUA_REGISTRYINDEX, fn.ref)
        callTop(s, args)
    }

    /** Call the value on top of the stack under the prelude's message handler; leaves the stack as it found it. */
    private fun callTop(s: MemorySegment, args: Array<out Any?>): Result {
        val base = LuaNative.getTop(s) - 1 // the function's slot
        // The message handler goes below the function: it records where the error happened while the
        // failing frame is still on the stack, which is the only moment that is knowable.
        pushString(s, "__field_msgh"); LuaNative.rawGet(s, LUA_GLOBALSINDEX)
        LuaNative.insert(s, base + 1)
        val handlerIndex = base + 1
        args.forEach { push(s, it, 0) }
        val status = LuaNative.pcall(s, args.size, LUA_MULTRET, handlerIndex)
        try {
            if (status != LuaNative.LUA_OK) {
                return when (LuaNative.type(s, -1)) {
                    LuaNative.LUA_TTABLE -> {
                        @Suppress("UNCHECKED_CAST")
                        val e = toKotlin(s, -1, 0, HashSet()) as? Map<Any?, Any?> ?: emptyMap()
                        errorFrom(e["msg"]?.toString() ?: "error", (e["line"] as? Double)?.toInt(), e["trace"]?.toString())
                    }
                    LuaNative.LUA_TSTRING -> errorFrom(readString(s, -1), null, null)
                    else -> Result.Err(if (status == LuaNative.LUA_ERRMEM) "Lua ran out of memory" else "Lua error (status $status)", null)
                }
            }
            val results = (handlerIndex + 1..LuaNative.getTop(s)).map { toKotlin(s, it, 0, HashSet()) }
            return Result.Ok(results)
        } finally {
            LuaNative.setTop(s, base)
        }
    }

    private fun errorFrom(raw: String, line: Int?, trace: String?, syntax: Boolean = false): Result.Err {
        val m = POSITION.find(raw)
        val at = m?.groupValues?.get(2)?.toIntOrNull()
        val stripped = if (m != null && m.groupValues[1] == CHUNK.removePrefix("=")) raw.substring(m.range.last + 1) else raw
        return Result.Err(stripped.trim(), line ?: if (m?.groupValues?.get(1) == CHUNK.removePrefix("=")) at else null, syntax, trace)
    }

    internal fun releaseRef(ref: Int) = lock.withLock {
        val s = l ?: return@withLock
        if (refs.remove(ref)) LuaNative.unref(s, LUA_REGISTRYINDEX, ref)
    }

    /** Close the state: every [LuaFunction] and [Handler] it handed out goes with it. Idempotent. */
    override fun close() = lock.withLock {
        val s = l ?: return@withLock
        l = null
        handlerIds.forEach { handlers.remove(it) }
        handlerIds.clear()
        refs.clear()
        LuaNative.close(s)
        openStates.decrementAndGet()
        Unit
    }

    // ── marshalling ─────────────────────────────────────────────────────────────────────────────

    private fun absIndex(s: MemorySegment, idx: Int) = if (idx < 0 && idx > LUA_REGISTRYINDEX) LuaNative.getTop(s) + idx + 1 else idx

    private fun refTop(s: MemorySegment): Int = LuaNative.ref(s, LUA_REGISTRYINDEX).also { refs += it }

    private fun readString(s: MemorySegment, idx: Int): String = Arena.ofConfined().use { arena ->
        val len = arena.allocate(JAVA_LONG)
        val p = LuaNative.toLString(s, idx, len)
        if (p.address() == 0L) return "" else {
            val n = len.get(JAVA_LONG, 0)
            String(p.reinterpret(n).toArray(JAVA_BYTE), Charsets.UTF_8)
        }
    }

    private fun pushString(s: MemorySegment, text: String) = Arena.ofConfined().use { arena ->
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buf = arena.allocate(bytes.size.toLong().coerceAtLeast(1))
        MemorySegment.copy(bytes, 0, buf, JAVA_BYTE, 0, bytes.size)
        LuaNative.pushLString(s, buf, bytes.size.toLong())
    }

    private fun setGlobalFromStack(s: MemorySegment, name: String) {
        pushString(s, name); LuaNative.insert(s, -2); LuaNative.rawSet(s, LUA_GLOBALSINDEX)
    }

    private fun pushHandler(s: MemorySegment, handler: Handler) {
        val id = nextHandlerId.getAndIncrement()
        handlers[id] = Registered(this, handler)
        handlerIds += id
        LuaNative.pushNumber(s, id.toDouble())
        LuaNative.pushCClosure(s, LuaNative.dispatchStub, 1)
    }

    /** Push [value] as Lua; see the class comment for the mapping. */
    internal fun push(s: MemorySegment, value: Any?, depth: Int) {
        if (depth > MAX_DEPTH) { LuaNative.pushNil(s); return }
        when (value) {
            null, Unit -> LuaNative.pushNil(s)
            is Boolean -> LuaNative.pushBoolean(s, value)
            is Number -> LuaNative.pushNumber(s, value.toDouble())
            is CharSequence -> pushString(s, value.toString())
            is Char -> pushString(s, value.toString())
            is LuaFunction -> if (value.state === this && value.ref in refs) LuaNative.rawGetI(s, LUA_REGISTRYINDEX, value.ref) else LuaNative.pushNil(s)
            is Handler -> pushHandler(s, value)
            is Map<*, *> -> {
                LuaNative.createTable(s, 0, value.size)
                value.forEach { (k, v) ->
                    if (k == null) return@forEach
                    if (k is Number) LuaNative.pushNumber(s, k.toDouble()) else pushString(s, k.toString())
                    push(s, v, depth + 1)
                    LuaNative.rawSet(s, -3)
                }
            }
            is afield.Rect -> push(s, linkedMapOf("x" to value.x, "y" to value.y, "w" to value.w, "h" to value.h), depth)
            is DoubleArray -> pushList(s, value.asList(), depth)
            is FloatArray -> pushList(s, value.asList(), depth)
            is IntArray -> pushList(s, value.asList(), depth)
            is Array<*> -> pushList(s, value.asList(), depth)
            is Iterable<*> -> pushList(s, value.toList(), depth)
            else -> pushString(s, value.toString())
        }
    }

    private fun pushList(s: MemorySegment, list: List<Any?>, depth: Int) {
        LuaNative.createTable(s, list.size, 0)
        list.forEachIndexed { i, v -> push(s, v, depth + 1); LuaNative.rawSetI(s, -2, i + 1) }
    }

    /**
     * The Lua value at [idx] as Kotlin; see the class comment. Raw access only, so no metamethod runs.
     * A table seen twice on the way down (a cycle) or deeper than [MAX_DEPTH] becomes the string "…".
     */
    internal fun toKotlin(s: MemorySegment, idx: Int, depth: Int, seen: MutableSet<Long>): Any? {
        val i = absIndex(s, idx)
        return when (LuaNative.type(s, i)) {
            LuaNative.LUA_TNIL, LuaNative.LUA_TNONE -> null
            LuaNative.LUA_TBOOLEAN -> LuaNative.toBoolean(s, i)
            LuaNative.LUA_TNUMBER -> LuaNative.toNumber(s, i)
            LuaNative.LUA_TSTRING -> readString(s, i)
            LuaNative.LUA_TFUNCTION -> { LuaNative.pushValue(s, i); LuaFunction(this, refTop(s)) }
            LuaNative.LUA_TTABLE -> tableToKotlin(s, i, depth, seen)
            LuaNative.LUA_TUSERDATA -> "a userdata"
            LuaNative.LUA_TLIGHTUSERDATA -> "a light userdata"
            LuaNative.LUA_TTHREAD -> "a coroutine"
            else -> null
        }
    }

    private fun tableToKotlin(s: MemorySegment, i: Int, depth: Int, seen: MutableSet<Long>): Any? {
        // A Field box proxy carries its box's id in its metatable's `__box`; it crosses as a BoxRef.
        if (LuaNative.getMetatable(s, i)) {
            pushString(s, "__box"); LuaNative.rawGet(s, -2)
            val id = if (LuaNative.type(s, -1) == LuaNative.LUA_TSTRING) readString(s, -1) else null
            LuaNative.pop(s, 2)
            if (id != null) return BoxRef(id)
        }
        val pointer = LuaNative.toPointer(s, i)
        if (depth > MAX_DEPTH || !seen.add(pointer)) return "…"
        val entries = LinkedHashMap<Any, Any?>()
        LuaNative.pushNil(s)
        while (LuaNative.next(s, i) != 0) {
            val key: Any? = when (LuaNative.type(s, -2)) {
                LuaNative.LUA_TNUMBER -> LuaNative.toNumber(s, -2)
                LuaNative.LUA_TSTRING -> readString(s, -2)
                LuaNative.LUA_TBOOLEAN -> LuaNative.toBoolean(s, -2).toString()
                else -> null
            }
            if (key != null) entries[key] = toKotlin(s, -1, depth + 1, seen)
            LuaNative.pop(s, 1)
        }
        seen.remove(pointer)
        val n = entries.size
        val isList = entries.keys.all { it is Double && it == Math.floor(it) && it >= 1.0 && it <= n }
        return if (isList) List(n) { entries[(it + 1).toDouble()] } else entries
    }

    /** A Lua proxy for a Field box, as it arrives in Kotlin: the box's [id]. */
    data class BoxRef(val id: String)

    private class Registered(val state: LuaState, val handler: Handler)

    companion object {
        /** The chunk name box code runs under, so its errors read `box:12: …`. */
        const val CHUNK = "=box"

        /** Where the prelude finds the raw Kotlin handlers before it hides them. */
        internal const val RAW_TABLE = "__field_raw"

        private const val MAX_DEPTH = 24

        private val POSITION = Regex("""^([^:\n]{1,40}):(\d+):""")

        private val handlers = ConcurrentHashMap<Int, Registered>()

        /** Handlers every state has: a wall clock in seconds, which the guard reads. */
        private val defaultRaw: Map<String, Handler> = mapOf(
            "clock" to Handler { _, _ -> listOf(System.nanoTime() / 1e9) },
        )
        private val nextHandlerId = AtomicInteger(1)

        /** States opened and not yet closed, process-wide; for the leak tests. */
        internal val openStates = AtomicInteger(0)

        /** Handlers registered and not yet released with their state; for the leak tests. */
        internal val liveHandlers: Int get() = handlers.size

        /**
         * The upcall behind every Kotlin function Lua calls ([LuaNative.dispatchStub]). Returns
         * `true, results…` or `false, message`: it never raises into Lua and never lets a Kotlin
         * exception reach the FFM boundary, which would abort the VM.
         */
        @JvmStatic
        fun dispatch(s: MemorySegment): Int {
            return try {
                val id = LuaNative.toNumber(s, LuaNative.upvalueIndex(1)).toInt()
                val registered = handlers[id]
                if (registered == null) {
                    LuaNative.setTop(s, 0)
                    LuaNative.pushBoolean(s, false)
                    registered(s, "this Field function belongs to a Lua state that has been closed")
                    return 2
                }
                val state = registered.state
                val n = LuaNative.getTop(s)
                val args = (1..n).map { state.toKotlin(s, it, 0, HashSet()) }
                val results = try {
                    registered.handler.call(state, args)
                } catch (t: Throwable) {
                    LuaNative.setTop(s, 0)
                    LuaNative.pushBoolean(s, false)
                    state.pushString(s, t.message ?: t.javaClass.simpleName)
                    return 2
                }
                LuaNative.setTop(s, 0)
                LuaNative.pushBoolean(s, true)
                results.forEach { state.push(s, it, 0) }
                1 + results.size
            } catch (t: Throwable) {
                // Marshalling itself failed. Nothing sensible can be pushed if the stack is the problem.
                runCatching { LuaNative.setTop(s, 0); LuaNative.pushBoolean(s, false) }.getOrNull() ?: return 0
                1
            }
        }

        private fun registered(s: MemorySegment, message: String) = Arena.ofConfined().use { arena ->
            val bytes = message.toByteArray(Charsets.UTF_8)
            val buf = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, buf, JAVA_BYTE, 0, bytes.size)
            LuaNative.pushLString(s, buf, bytes.size.toLong())
        }

        /**
         * The Lua half of Field's API, run once in every state. Everything a box sees is defined here
         * in Lua over the raw Kotlin handlers, which it takes from `__field_raw` and then hides.
         */
        val PRELUDE: String by lazy {
            LuaState::class.java.getResource("/afield/lua/prelude.lua")?.readText()
                ?: error("afield/lua/prelude.lua is missing from the classpath")
        }
    }
}
