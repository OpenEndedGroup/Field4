package afield.lua

import afield.Box
import afield.BoxExecution
import afield.Completion
import afield.CompletionRequest
import afield.Execute
import afield.ExecuteSymbol
import afield.ExecutionTrace
import afield.IsRunning
import afield.Message
import afield.Metadata
import afield.Output
import afield.Rect
import afield.ReleasePolicy
import afield.RunsAt
import afield.Runloop
import afield.configureOutputProxies
import afield.dispatchExecutionOutput
import afield.early
import afield.graphics.FLine
import afield.graphics.lines
import afield.inMain
import afield.payload
import afield.plugins
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.send
import afield.stdlib.Delegation
import afield.stdlib.LastRunNotes
import afield.stdlib.LineNotes
import afield.stdlib.color
import afield.stdlib.isTrue
import afield.stdlib.filled
import afield.stdlib.vec
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.thisSession
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lua as a box language, over LuaJIT: set a box's Main Language to Lua and its children hold Lua.
 *
 * Each Lua box has **one `lua_State` of its own**, made the first time it runs and kept across runs,
 * so a box accumulates the way a REPL does — run a function definition, then run a line that calls it.
 * The state closes when the box is deleted or its document closes (it is owned by the box's parent
 * [afield.RunScope] as [ReleasePolicy.KEYED], so stop leaves it alone), and when the box stops being
 * Lua. One state per box rather than per document, as a Pd box has its own Pd: two boxes cannot see
 * each other's globals by accident, and one box's runaway loop is one box's problem.
 *
 * - **Run** runs the box's text as one chunk. **A selection** runs in the same state; a single
 *   expression is tried as `return <expression>` first, so selecting `x * 2` shows its value.
 * - **Launch** runs the chunk and then, once a Runloop frame, calls the global `update(dt)` if the
 *   chunk defined one and resumes every coroutine started with `spawn(fn)` (which pause with
 *   `wait(seconds)`), until stop or until there is nothing left to run — LÖVE's and norns' shape.
 * - A Lua error is marked red on its line, painted after anything the run printed (the editor clears a
 *   box's errors when it prints). A run that goes on longer than `field.budget` seconds (5), or a frame
 *   longer than `field.frameBudget` (1), is stopped with an error on the line it was running.
 * - Every run records its line notes ([LastRunNotes]): the value each top-level line assigned, how
 *   often a line ran, where a run stopped. `AFIELD_LUA_LINE_NOTES=0` turns recording off.
 *
 * What a Lua box can reach is in the prelude (`src/jvmMain/resources/afield/lua/prelude.lua`): `print`,
 * `display(channel, html)`, `box` (its properties by name, `box.children`, `box.parents`),
 * `field.named(name)`, `line()` and `lines(...)` for drawing FLines, `spawn`, `wait` and `update`.
 */
class LuaExecution(val inside: Box) : BoxExecution {

    override val languageName: String get() = "lua"

    @Volatile
    private var state: LuaState? = null

    /** The box's Lua, opened on first use. Main thread only. */
    fun luaState(): LuaState {
        state?.takeIf { !it.isClosed }?.let { return it }
        val fresh = LuaState(luaHandlersFor(inside))
        state = fresh
        // KEYED: released when the box retires (deleted, document closed), never by stop.
        inside.runScopes.parent.own(AutoCloseable { closeState(fresh) }, ReleasePolicy.KEYED)
        return fresh
    }

    /** Whether this box has a Lua state open now; for tests and the census. */
    val hasState: Boolean get() = state?.isClosed == false

    private fun closeState(s: LuaState) {
        if (state === s) state = null
        s.close()
    }

    /** Close the box's Lua now; the next run opens a fresh one. */
    fun close() {
        stop(inside)
        state?.let { closeState(it) }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        runLua(inside, execute, execute.code, "run", reply, fragment = false, launch = false, strand = execute.strand)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) =
        runLua(inside, execute, code, "launch", reply, fragment = false, launch = true, strand = strand)

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        runLua(inside, execute, execute.code, "fragment", reply, fragment = true, launch = false, strand = execute.strand)

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        executeFragment(inside, Execute(uid = executeSymbol.uid, type = "fragment", line = executeSymbol.line,
            code = executeSymbol.symbol, singleLine = true), reply)
    }

    private fun runLua(
        inside: Box, execute: Execute, code: String, mode: String, reply: (Message) -> Unit,
        fragment: Boolean, launch: Boolean, strand: String,
    ) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, mode, reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            if (!LuaLibrary.isAvailable) {
                inside.error(LuaLibrary.describe(), line = execute.line, property = "code")
                trace.finish(false, "LuaJIT is not installed")
                return@inMain
            }
            val lua = try { luaState() } catch (t: Throwable) {
                inside.error("could not open a Lua state: ${t.message}", line = execute.line, property = "code")
                trace.finish(false, "no Lua state")
                return@inMain
            }
            // A new generation ends the previous launch's frame loop, as Pd's does.
            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            if (!fragment) lua.call("__field_halt")
            clearErrorStatus(inside, trace.reply, originSession)

            val names = if (recordingLineNotes) assignedNames(code) else null
            val outcome = lua.runGuarded(code, names, asExpression = fragment)
            val offset = execute.line
            val result = outcome.result
            if (names != null) recordNotes(inside, code, offset, outcome)

            when (result) {
                is LuaState.Result.Ok -> {
                    if (result.values.isNotEmpty() || fragment) {
                        val text = if (result.values.isEmpty()) "ok" else result.values.joinToString(", ") { showValue(it) }
                        dispatchExecutionOutput(inside, trace.reply,
                            Output(uid = inside.id, line = offset + code.split("\n").size, kind = "ok",
                                html = escape(text), frame = Runloop.runLoop.frame.toInt()),
                            originSession = originSession)
                    }
                }
                is LuaState.Result.Err -> {
                    // Painted last: anything printed after would clear it.
                    inside.error(result.message, "code", offset + (result.line ?: 1))
                    trace.finish(false, result.message)
                    return@inMain
                }
            }
            if (!launch) {
                val (tasks, hasUpdate) = pending(lua)
                if (!fragment && (tasks > 0 || hasUpdate)) {
                    runCatching { inside.println("(launch the box to run ${if (hasUpdate) "update(dt)" else "its spawned functions"} every frame)") }
                }
                trace.finish(true)
                return@inMain
            }
            val (tasks, hasUpdate) = pending(lua)
            if (tasks == 0 && !hasUpdate) {
                trace.finish(true)
                return@inMain
            }
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))
            trace.finish(true)
            inMain {
                var last = System.nanoTime()
                try {
                    while (inside.runGeneration[strand] == generation && !lua.isClosed) {
                        val now = System.nanoTime()
                        val r = lua.tick(now / 1e9, (now - last) / 1e9)
                        last = now
                        if (r is LuaState.Result.Err) {
                            inside.error(r.message, "code", offset + (r.line ?: 1))
                            break
                        }
                        if ((r as LuaState.Result.Ok).values.firstOrNull() != true) break
                        yield()
                    }
                } finally {
                    if (!lua.isClosed) lua.call("__field_halt")
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                }
            }
        }
    }

    private fun pending(lua: LuaState): Pair<Int, Boolean> {
        val v = (lua.call("__field_pending") as? LuaState.Result.Ok)?.values.orEmpty()
        return ((v.getOrNull(0) as? Double)?.toInt() ?: 0) to (v.getOrNull(1) == true)
    }

    override fun stop(inside: Box) {
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        val code = completionRequest.allCode
        val cursor = completionRequest.position.coerceIn(0, code.length)
        var start = cursor
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) start--
        val prefix = code.substring(start, cursor)
        // `a.b.` or `a:` before the word: complete members of that path.
        var p = start
        while (p > 0 && (code[p - 1].isLetterOrDigit() || code[p - 1] == '_' || code[p - 1] == '.' || code[p - 1] == ':')) p--
        val path = code.substring(p, start).trimEnd('.', ':')
        val candidates = luaCompletions(path, prefix)
        if (candidates.isEmpty()) return
        reply(Message("complete").payload(Completion(boundsStart = start, boundsEnd = cursor,
            metadata = candidates.map { (text, kind, doc) -> Metadata(icon = kind, tail = kind, documentation = doc, text = text, displayText = text) })))
    }

    /** Names to offer at [path] (`""` for the globals) that start with [prefix]: text, kind, documentation. */
    fun luaCompletions(path: String, prefix: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        if (path.isEmpty()) LUA_KEYWORDS.forEach { out += Triple(it, "keyword", "Lua keyword") }
        val lua = state?.takeIf { !it.isClosed }
        val listed = (lua?.call("__field_complete", path) as? LuaState.Result.Ok)?.values?.firstOrNull() as? List<*>
        listed.orEmpty().forEach { entry ->
            val pair = entry as? List<*> ?: return@forEach
            val name = pair.getOrNull(0)?.toString() ?: return@forEach
            if (name == "__box") {
                val box = findBox(inside, pair.getOrNull(1)?.toString() ?: return@forEach) ?: return@forEach
                (listOf("id", "name", "frame", "children", "parents") + box.properties.keys.map { it.toString() })
                    .distinct().forEach { out += Triple(it, "property", "a property of ${box.name.ifEmpty { box.id }}") }
            } else {
                out += Triple(name, pair.getOrNull(1)?.toString() ?: "value", "Lua ${pair.getOrNull(1)}")
            }
        }
        return out.filter { it.first.startsWith(prefix) && it.first != prefix }.distinctBy { it.first }.sortedBy { it.first }
    }

    private fun clearErrorStatus(inside: Box, reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?) {
        dispatchExecutionOutput(inside, reply,
            Output(uid = inside.id, line = -1, kind = "err", property = "code", html = "", frame = Runloop.runLoop.frame.toInt()),
            originSession = originSession)
    }

    companion object {
        /** Lua 5.1's reserved words. */
        val LUA_KEYWORDS = listOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "if", "in",
            "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
        )

        /** Off with `AFIELD_LUA_LINE_NOTES=0`. */
        val recordingLineNotes: Boolean get() = System.getenv("AFIELD_LUA_LINE_NOTES") != "0"
    }
}

private fun escape(text: String) = "<pre style=\"margin:0; white-space:pre-wrap\">" +
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"

/** A value as a fragment's result shows it: integral numbers without a decimal point. */
internal fun showValue(v: Any?): String = when (v) {
    null -> "nil"
    is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    is String -> "\"$v\""
    is List<*> -> v.joinToString(", ", "{", "}") { showValue(it) }
    is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${it.key} = ${showValue(it.value)}" }
    is LuaState.BoxRef -> "box ${v.id}"
    else -> v.toString()
}

private val LOCAL_FUNCTION = Regex("""^\s*local\s+function\s+([A-Za-z_]\w*)""")
private val FUNCTION = Regex("""^\s*function\s+([A-Za-z_]\w*)\s*\(""")
private val LOCALS = Regex("""^\s*local\s+([A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*)\s*(?:=|$)""")
private val ASSIGN = Regex("""^\s*([A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*)\s*=(?!=)""")

/**
 * The plain names each line of [code] assigns (1-based line → names): `local a, b = …`, `x = …`,
 * `function f(`, `local function g(`. Field assignments (`t.x = …`) are left out, so recording reads
 * nothing but names — it never indexes a table, which could run a metamethod.
 */
internal fun assignedNames(code: String): Map<Int, List<String>> {
    val out = LinkedHashMap<Int, List<String>>()
    code.split("\n").forEachIndexed { i, raw ->
        val line = raw.substringBefore("--")
        val names = LOCAL_FUNCTION.find(line)?.groupValues?.get(1)?.let { listOf(it) }
            ?: FUNCTION.find(line)?.groupValues?.get(1)?.let { listOf(it) }
            ?: LOCALS.find(line)?.groupValues?.get(1)?.split(",")?.map { it.trim() }
            ?: ASSIGN.find(line)?.groupValues?.get(1)?.split(",")?.map { it.trim() }
        if (names != null && names.none { it in LuaExecution.LUA_KEYWORDS }) out[i + 1] = names
    }
    return out
}

/** Turn one run's recording into [LastRunNotes] entries for [box]'s `code`. */
internal fun recordNotes(box: Box, code: String, offset: Int, outcome: LuaState.Outcome) {
    val lines = code.split("\n")
    val produced = ArrayList<LastRunNotes.Produced>()
    for ((line, r) in outcome.lines) {
        if (line < 1 || line > lines.size) continue
        val times = if (r.count > 1) " · ran ${r.count}×" else ""
        val (caption, value) = when {
            r.values.isNotEmpty() && r.values.all { it.second == "a function" } -> {
                val names = r.values.joinToString(", ") { it.first }
                "defined $names$times" to listOf(LineNotes.plain("defined "), LineNotes.strong(names))
            }
            r.values.isNotEmpty() -> {
                val segs = ArrayList<LineNotes.Segment>()
                r.values.forEachIndexed { k, (n, v) ->
                    if (k > 0) segs += LineNotes.plain(", ")
                    segs += LineNotes.plain("$n = "); segs += LineNotes.strong(v)
                }
                if (times.isNotEmpty()) segs += LineNotes.plain(times)
                (r.values.joinToString(", ") { "${it.first} = ${it.second}" } + times) to segs
            }
            !r.main -> {
                val s = "ran ${r.count}× inside a function"
                s to listOf(LineNotes.plain("ran "), LineNotes.strong("${r.count}×"), LineNotes.plain(" inside a function"))
            }
            r.count > 1 -> "ran ${r.count}×" to listOf(LineNotes.plain("ran "), LineNotes.strong("${r.count}×"))
            else -> "ran" to listOf(LineNotes.plain("ran"))
        }
        produced += LastRunNotes.Produced(line, line, caption, value = value)
    }
    val err = outcome.result as? LuaState.Result.Err
    val stoppedAt = err?.line
    val notReached = if (stoppedAt != null) {
        (stoppedAt + 1..lines.size).filter { l ->
            val t = lines[l - 1].trim()
            t.isNotEmpty() && !t.startsWith("--") && l !in outcome.lines
        }
    } else emptyList()
    if (outcome.truncated) {
        // Say so on the first line that has no record: the recorder stopped watching partway through.
        val quiet = lines.indices.map { it + 1 }.firstOrNull { it !in outcome.lines && lines[it - 1].isNotBlank() }
        if (quiet != null) produced += LastRunNotes.Produced(quiet, quiet, "not recorded: the run went on past the recorder's limit",
            value = listOf(LineNotes.plain("not recorded: the run went on past the recorder's limit")))
    }
    LastRunNotes.record(box, "code", code, offset, produced, stoppedAt = stoppedAt, stopReason = err?.message, notReached = notReached)
}

// ── the handlers a box's Lua calls ──────────────────────────────────────────────────────────────────

private fun findBox(from: Box, id: String): Box? = boxesNear(from).firstOrNull { it.id == id }

/**
 * Every box [from] can reach: its document's (the root's children) when it is in one, and in any case
 * the boxes connected to it — which is all a box made outside a document, in a test, has.
 */
private fun boxesNear(from: Box): Sequence<Box> = sequence {
    yield(from)
    runCatching { from.root }.getOrNull()?.let { root -> yield(root); yieldAll(root.children) }
    val seen = HashSet<Box>()
    val queue = ArrayDeque(listOf(from))
    while (queue.isNotEmpty()) {
        val b = queue.removeFirst()
        if (!seen.add(b)) continue
        yield(b)
        queue.addAll(b.children); queue.addAll(b.parents)
    }
}

/** [name] on [box], or the nearest parent's: how Field's inherited properties read. */
private fun inheritedProperty(box: Box, name: String): Any? {
    val seen = HashSet<Box>()
    val queue = ArrayDeque(listOf(box))
    while (queue.isNotEmpty()) {
        val b = queue.removeFirst()
        if (!seen.add(b)) continue
        b.getPropertyRaw(name)?.let { return it }
        queue.addAll(b.parents)
    }
    return null
}

private fun tagged(value: Any?): List<Any?> = when {
    value is Box -> listOf("box", value.id)
    value is Collection<*> && value.isNotEmpty() && value.all { it is Box } -> listOf("boxes", value.map { (it as Box).id })
    else -> listOf("value", value)
}

private fun fromLua(value: Any?, key: String): Any? = when {
    value is LuaState.BoxRef -> value
    key == "frame" && value is Map<*, *> -> Rect(
        (value["x"] as? Number)?.toDouble() ?: 0.0, (value["y"] as? Number)?.toDouble() ?: 0.0,
        (value["w"] as? Number)?.toDouble() ?: 100.0, (value["h"] as? Number)?.toDouble() ?: 100.0)
    else -> value
}

private fun num(v: Any?, what: String): Double = (v as? Number)?.toDouble() ?: throw IllegalArgumentException("$what should be a number, not ${showValue(v)}")

/** Build an FLine from a Lua `line()` table: `ops` of `{"m", x, y}`, `{"l", …}`, `{"c", …}`, `{"z"}`, `{"o", x, y, r}`. */
internal fun luaLineToFLine(t: Map<*, *>): FLine {
    val f = FLine()
    val ops = t["ops"] as? List<*> ?: emptyList<Any?>()
    for (o in ops) {
        val op = o as? List<*> ?: continue
        when (op.getOrNull(0)) {
            "m" -> f.moveTo(num(op[1], "x"), num(op[2], "y"))
            "l" -> f.lineTo(num(op[1], "x"), num(op[2], "y"))
            "c" -> f.cubicTo(num(op[1], "x"), num(op[2], "y"), 0.0, num(op[3], "x"), num(op[4], "y"), 0.0, num(op[5], "x"), num(op[6], "y"), 0.0)
            "z" -> f.close()
            "o" -> {
                val cx = num(op[1], "x"); val cy = num(op[2], "y"); val r = num(op[3], "radius")
                val n = 48
                for (i in 0..n) {
                    val a = 2 * PI * i / n
                    if (i == 0) f.moveTo(cx + r * cos(a), cy + r * sin(a)) else f.lineTo(cx + r * cos(a), cy + r * sin(a))
                }
                f.close()
            }
        }
    }
    (t["rgba"] as? List<*>)?.let { c ->
        f.color = vec(num(c.getOrNull(0) ?: 1.0, "red"), num(c.getOrNull(1) ?: 1.0, "green"), num(c.getOrNull(2) ?: 1.0, "blue"), num(c.getOrNull(3) ?: 1.0, "alpha"))
    }
    (t["thickness"] as? Number)?.let { f.thickness = it.toDouble() }
    (t["filled"] as? Boolean)?.let { f.filled = it }
    (t["stroked"] as? Boolean)?.let { f.stroked = it }
    return f
}

/** The raw Kotlin functions the prelude wraps into a box's `print`, `display`, `box`, `lines`, …. */
internal fun luaHandlersFor(box: Box): Map<String, LuaState.Handler> = mapOf(
    "print" to LuaState.Handler { _, args -> box.println(args.firstOrNull()?.toString() ?: ""); emptyList() },
    "display" to LuaState.Handler { _, args ->
        when (args.size) {
            0 -> throw IllegalArgumentException("display(html) or display(channel, html)")
            1 -> box.display(args[0]?.toString() ?: "")
            else -> box.display(args[0]?.toString() ?: "main", args[1]?.toString() ?: "")
        }
        emptyList()
    },
    "self" to LuaState.Handler { _, _ -> listOf(box.id) },
    "get" to LuaState.Handler { _, args ->
        val target = findBox(box, args.getOrNull(0)?.toString() ?: "") ?: throw IllegalStateException("that box is gone")
        when (val key = args.getOrNull(1)?.toString() ?: "") {
            "children" -> listOf("boxes", target.children.map { it.id })
            "parents" -> listOf("boxes", target.parents.map { it.id })
            "name" -> listOf("value", target.name)
            else -> tagged(inheritedProperty(target, key))
        }
    },
    "set" to LuaState.Handler { _, args ->
        val target = findBox(box, args.getOrNull(0)?.toString() ?: "") ?: throw IllegalStateException("that box is gone")
        val key = args.getOrNull(1)?.toString() ?: throw IllegalArgumentException("a property name")
        if (key in READ_ONLY) throw IllegalArgumentException("'$key' can't be set from Lua")
        val value = fromLua(args.getOrNull(2), key)
        when (value) {
            null -> target.removePropertyRaw(key, true)
            is LuaState.BoxRef -> target.setPropertyRaw(key, findBox(box, value.id) ?: value.id, true)
            else -> target.setPropertyRaw(key, value, true)
        }
        emptyList()
    },
    "named" to LuaState.Handler { _, args ->
        val name = args.getOrNull(0)?.toString() ?: ""
        val found = boxesNear(box).firstOrNull { it.name == name && !it.getPropertyRaw("_tombstone").isTrue() }
        if (found == null) listOf("value", null) else listOf("box", found.id)
    },
    "lines" to LuaState.Handler { _, args ->
        val list = (args.getOrNull(0) as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::luaLineToFLine) }
        val options = args.getOrNull(1) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        box.lines(list,
            channel = options["channel"]?.toString() ?: "lines",
            w = (options["w"] as? Number)?.toDouble() ?: -1.0,
            h = (options["h"] as? Number)?.toDouble() ?: -1.0,
            fitToSize = options["fit"] == true)
        listOf(list.size)
    },
    "frame" to LuaState.Handler { _, _ -> listOf(Runloop.runLoop.frame.toDouble()) },
)

private val READ_ONLY = setOf("children", "parents", "id", "code_language", "_tombstone")

var Box.isLuaRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installLuaLanguage() {
    Box.ioPersistentProperties.add("isLuaRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "lua",
            label = "Lua",
            runtimeClass = LuaExecution::class,
            setLegacyRoot = { box, enabled -> box.isLuaRoot = enabled },
            applyTopology = ::luaTopologyNeedsUpdating,
        ),
    )
}

@RunsAt(plugins)
fun installLuaLanguagePlugin() {
    TopologyWatcher("lua") { luaTopologyNeedsUpdating(it) }.start()
}

fun luaTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isLuaRoot == true || it.isMainLanguage("lua") },
        runtimeClass = LuaExecution::class,
        newExecution = { child -> LuaExecution(child) },
        language = "Lua",
        customization = "",
        notifyCategory = "lua",
        onDetach = { child -> (child.ke as? LuaExecution)?.close() },
    )
}
