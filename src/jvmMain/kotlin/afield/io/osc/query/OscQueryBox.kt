@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.osc.query

import afield.document.graph.Box
import afield.ui.output.ControlGroupBuilder
import afield.ui.output.ControlValueHandle
import afield.runtime.lifecycle.Runloop
import afield.ui.output.controlGroup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The box-facing OSCQuery entry points: `Box.oscQuery` to publish parameters, `Box.oscQueryRemote`
 * and `Box.oscQueryDiscover` to reach other services, and `Box.oscQueryMirror` to show a remote's
 * namespace as Field controls.
 *
 * [OscQueryServices] keeps one server per HTTP port across box runs and brings callbacks onto the
 * main Runloop; [BoxOscQuery] is the per-box view that owns the parameters a box declares.
 */

/**
 * Process-wide OSCQuery state behind `Box.oscQuery`: one [OscQueryServer] per HTTP port, the
 * remotes a box has opened, and the queue that brings callbacks onto the main Runloop.
 *
 * Lifecycle follows `afield.io.mqtt`: servers survive a box being stopped (TouchDesigner should not
 * lose its connection because you stopped the box that owns a parameter) and re-running a box
 * replaces its parameters in place, keeping their values. Each parameter is owned by the box that
 * declared it as `ReleasePolicy.KEYED`, so deleting the box removes it from the namespace (and
 * remotes see `PATH_REMOVED`). [closeAll] shuts everything down.
 */
object OscQueryServices {
    /** HTTP port `Box.oscQuery` uses when none is given; OSC UDP is the next port up. */
    const val DEFAULT_HTTP_PORT = 9020

    private val servers = ConcurrentHashMap<Int, OscQueryServer>()
    private val remotes = ConcurrentHashMap<String, OscQueryRemote>()
    private val owned = ConcurrentHashMap.newKeySet<String>()

    private val queue = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var drainInstalled = false

    /** Runs [r] on the main Runloop inside Field, directly where no Runloop turns (tests, jvm-main). */
    fun dispatch(r: Runnable) {
        if (Runloop.mainThread == null) return r.run()
        queue.add(r)
        if (!drainInstalled) synchronized(this) {
            if (!drainInstalled) {
                drainInstalled = true
                Runloop.runLoop.preRun.setUnowned("afield.oscquery.dispatch", sequence {
                    while (true) {
                        var n = 0
                        while (n++ < 2000) {
                            val next = queue.poll() ?: break
                            try { next.run() } catch (t: Throwable) { afield.app.diagnostics.logging.lprintln("error,oscquery", "callback threw: $t") }
                        }
                        yield(null)
                    }
                })
            }
        }
    }

    fun server(name: String, httpPort: Int, oscPort: Int, advertise: Boolean): OscQueryServer =
        servers.compute(httpPort) { _, existing ->
            if (existing != null && !existing.isClosed) existing
            else OscQueryServer(name, httpPort, oscPort, advertise = advertise, dispatch = ::dispatch)
        }!!

    fun remote(host: String, port: Int): OscQueryRemote =
        remotes.compute("$host:$port") { _, existing -> existing?.refresh() ?: OscQueryRemote(host, port, ::dispatch) }!!

    internal fun own(box: Box, server: OscQueryServer, path: String) {
        val key = "${box.id}|${server.httpPort}|$path"
        if (!owned.add(key)) return
        box.runScopes.parent.own(afield.runtime.lifecycle.ReleasePolicy.KEYED) {
            if (owned.remove(key)) server.remove(path)
        }
    }

    /** Close every server and remote. Their parameters are gone; boxes re-create them when re-run. */
    fun closeAll() {
        servers.values.forEach { it.close() }; servers.clear()
        remotes.values.forEach { it.close() }; remotes.clear()
        owned.clear()
    }
}

/**
 * A box's view of an OSCQuery service: declare parameters, and their callbacks run in this box's
 * context on the main Runloop when TouchDesigner, Max, Chataigne or VDMX set them.
 *
 * ```
 * val q = oscQuery("My piece")
 * q.float("/synth/cutoff", 20.0..20000.0, 1000.0) { cutoff = it }
 * q.bool("/synth/on", true) { on = it }
 * ```
 *
 * Re-running the box replaces each parameter's callback and keeps its current value. Values set from
 * Field with [OscQueryParam.set] are pushed to every remote that `LISTEN`s.
 */
class BoxOscQuery internal constructor(private val box: Box, val server: OscQueryServer) {

    /** `http://<host>:<port>/`, the URL a remote browses. */
    val url: String get() = "http://${java.net.InetAddress.getLocalHost().hostName}:${server.httpPort}/"
    val httpPort: Int get() = server.httpPort
    val oscPort: Int get() = server.oscPort

    /**
     * A parameter of any OSC [type] (`"f"`, `"ii"`, `"s"`, `"T"`, ...). [onSet] gets every value a
     * remote sends, coerced to [type] and clipped to [range].
     */
    fun param(
        path: String,
        type: String,
        initial: List<Any?>,
        range: List<Pair<Double?, Double?>> = emptyList(),
        choices: List<Any?> = emptyList(),
        access: OscAccess = OscAccess.READ_WRITE,
        description: String? = null,
        onSet: ((List<Any?>) -> Unit)? = null,
    ): OscQueryParam {
        val p = server.add(path, type, initial, range, choices, access, description)
        if (onSet != null) p.onSet { v -> Box.withBoxContextNow(box) { try { onSet(v) } catch (t: Throwable) { box.println("oscQuery '${p.path}': $t") } } }
        OscQueryServices.own(box, server, p.path)
        return p
    }

    /** A float over [range]. */
    fun float(path: String, range: ClosedFloatingPointRange<Double> = 0.0..1.0, initial: Double = range.start,
              description: String? = null, access: OscAccess = OscAccess.READ_WRITE, onSet: ((Double) -> Unit)? = null): OscQueryParam =
        param(path, "f", listOf(initial), listOf(range.start to range.endInclusive), access = access, description = description,
            onSet = onSet?.let { f -> { v: List<Any?> -> f((v[0] as Number).toDouble()) } })

    /** An int over [range]. */
    fun int(path: String, range: IntRange? = null, initial: Int = range?.first ?: 0,
            description: String? = null, onSet: ((Int) -> Unit)? = null): OscQueryParam =
        param(path, "i", listOf(initial), range?.let { listOf(it.first.toDouble() to it.last.toDouble()) } ?: emptyList(),
            description = description, onSet = onSet?.let { f -> { v: List<Any?> -> f((v[0] as Number).toInt()) } })

    /** A boolean (OSC `T`/`F`): a toggle in TouchDesigner and Chataigne. */
    fun bool(path: String, initial: Boolean = false, description: String? = null, onSet: ((Boolean) -> Unit)? = null): OscQueryParam =
        param(path, "T", listOf(initial), description = description,
            onSet = onSet?.let { f -> { v: List<Any?> -> f(v[0] as Boolean) } })

    /** A string. */
    fun string(path: String, initial: String = "", description: String? = null, onSet: ((String) -> Unit)? = null): OscQueryParam =
        param(path, "s", listOf(initial), description = description, onSet = onSet?.let { f -> { v: List<Any?> -> f(v[0].toString()) } })

    /** A string limited to [choices] (`RANGE: [{"VALS": ...}]`), which remotes show as a menu. */
    fun menu(path: String, choices: List<String>, initial: String = choices.first(), description: String? = null,
             onSet: ((String) -> Unit)? = null): OscQueryParam =
        param(path, "s", listOf(initial), choices = choices, description = description,
            onSet = onSet?.let { f -> { v: List<Any?> -> f(v[0].toString()) } })

    /** An n-float vector, each over [range]: `"ff"` for an XY pad, `"fff"` for a colour. */
    fun vector(path: String, initial: List<Double>, range: ClosedFloatingPointRange<Double> = 0.0..1.0,
               description: String? = null, onSet: ((List<Double>) -> Unit)? = null): OscQueryParam =
        param(path, "f".repeat(initial.size), initial, List(initial.size) { range.start to range.endInclusive }, description = description,
            onSet = onSet?.let { f -> { v: List<Any?> -> f(v.map { (it as Number).toDouble() }) } })

    /** Describe a container (`/synth`), shown by remote browsers. */
    fun describe(path: String, description: String) = server.describe(path, description)

    operator fun get(path: String): OscQueryParam? = server[path]

    /**
     * Field controls that are also OSCQuery parameters: each slider or checkbox appears in this
     * box's output *and* in the namespace under [prefix]. Dragging it in Field runs the callback and
     * tells every listening remote; a remote setting it moves the Field control and runs the
     * callback.
     */
    fun controls(prefix: String = "/" + box.name.ifBlank { box.id }, channel: String = "controls", block: OscControls.() -> Unit) {
        val base = "/" + prefix.trim('/')
        box.controlGroup(channel) { OscControls(this@BoxOscQuery, this, base).block() }
    }

    /** The builder [controls] hands its block: [ControlGroupBuilder]'s controls, published. */
    class OscControls internal constructor(private val q: BoxOscQuery, private val group: ControlGroupBuilder, private val base: String) {
        private fun pathOf(label: String) = "$base/" + label.trim().replace(Regex("[\\s#*,?\\[\\]{}]+"), "_")

        fun slider(label: String, min: Double = 0.0, max: Double = 1.0, step: Double = 0.01, initialValue: Double = (min + max) / 2,
                   description: String? = null, onUpdate: (Double) -> Unit): ControlValueHandle<Double> {
            lateinit var h: ControlValueHandle<Double>
            // The param first: a cached control runs its callback while it is being built.
            val param = q.float(pathOf(label), min..max, initialValue, description) { v -> h.set(v); onUpdate(v) }
            h = group.slider(label, min, max, step, initialValue) { v -> param.set(v); onUpdate(v) }
            return h
        }

        fun checkbox(label: String, initialValue: Boolean = false, description: String? = null, onUpdate: (Boolean) -> Unit): ControlValueHandle<Boolean> {
            lateinit var h: ControlValueHandle<Boolean>
            val param = q.bool(pathOf(label), initialValue, description) { v -> h.set(v); onUpdate(v) }
            h = group.checkbox(label, initialValue) { v -> param.set(v); onUpdate(v) }
            return h
        }
    }
}

/**
 * This box's OSCQuery service on [httpPort] (OSC UDP on [oscPort]), advertised over DNS-SD as
 * [name]. The first call for a port starts it; later calls, from any box, share it.
 */
fun Box.oscQuery(
    name: String = "Field",
    httpPort: Int = OscQueryServices.DEFAULT_HTTP_PORT,
    oscPort: Int = if (httpPort == 0) 0 else httpPort + 1,
    advertise: Boolean = true,
): BoxOscQuery = BoxOscQuery(this, OscQueryServices.server(name, httpPort, oscPort, advertise))

/**
 * A remote OSCQuery service at [host]:[httpPort], cached so re-running a box reuses its
 * connection. A cached remote re-reads the namespace when it is asked for again, so `params` lists
 * what the service has now, not what it had when the first box connected. Callbacks (`listen`)
 * run on the main Runloop.
 */
fun Box.oscQueryRemote(host: String, httpPort: Int): OscQueryRemote = OscQueryServices.remote(host, httpPort)

/** OSCQuery services on the local network, found over DNS-SD (`_oscjson._tcp`) for [timeoutMs]. */
fun Box.oscQueryDiscover(timeoutMs: Long = 1500): List<Mdns.Service> = OscQueryRemote.discover(timeoutMs)

/**
 * Field controls mirroring every numeric and boolean parameter of [remote]: a slider for each float
 * or int with a range, a checkbox for each boolean. Moving one sets the remote; when the remote
 * supports `LISTEN`, its own changes move the Field control.
 */
fun Box.oscQueryMirror(remote: OscQueryRemote, channel: String = "controls", filter: (OscQueryRemote.Node) -> Boolean = { true }) {
    val nodes = remote.params.filter(filter)
    controlGroup(channel) {
        for (n in nodes) {
            val t = n.type
            when {
                t == "T" || t == "F" -> {
                    val h = checkbox(n.path, n.value.firstOrNull() == true) { v -> n.set(v) }
                    if (remote.supportsListen) n.listen { v -> (v.firstOrNull() as? Boolean)?.let { h.set(it) } }
                }
                (t == "f" || t == "i" || t == "d" || t == "h") -> {
                    val (lo, hi) = n.range.firstOrNull() ?: (null to null)
                    val min = lo ?: 0.0
                    val max = hi ?: maxOf(1.0, n.number)
                    val step = if (t == "i" || t == "h") 1.0 else (max - min) / 1000.0
                    val h = slider(n.path, min, max, step, n.number.coerceIn(min, max)) { v -> n.set(v) }
                    if (remote.supportsListen) n.listen { v -> (v.firstOrNull() as? Number)?.let { h.set(it.toDouble()) } }
                }
            }
        }
    }
}
