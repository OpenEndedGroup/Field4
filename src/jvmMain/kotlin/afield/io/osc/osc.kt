@file:afield.runtime.completion.CompletionCategory("Audio")

package afield.io.osc

import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.i
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.execution.go
import afield.runtime.lifecycle.cached
import afield.runtime.lifecycle.once
import afield.simulation.automata.slice

import afield.audio.synth.*
import afield.data.collections.get
import afield.data.collections.r
import afield.data.graph.main
import afield.graphics.shader.compute
import afield.graphics.vulkan.buffers.add
import afield.math.control.main
import afield.math.linalg.set
import afield.math.statistics.main
import afield.media.image.close
import afield.media.image.open
import afield.media.image.split
import afield.runtime.execution.send
import afield.runtime.execution.start
import afield.simulation.automata.main
import afield.document.graph.Box
import afield.runtime.lifecycle.Generator
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.inMain
import com.illposed.osc.OSCBundle
import com.illposed.osc.MessageSelector
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCPacketDispatcher
import com.illposed.osc.OSCPacketEvent
import com.illposed.osc.OSCPacketListener
import com.illposed.osc.OSCParser
import com.illposed.osc.OSCSerializerAndParserBuilder
import com.illposed.osc.argument.handler.Activator
import com.illposed.osc.argument.handler.BlobArgumentHandler
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import com.illposed.osc.transport.OSCPortOut
import com.illposed.osc.transport.OSCPortOutBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OSC in and out, wrapped around JavaOSC (`com.illposed.osc`) and driven from Field's main runloop.
 * The page that teaches it is `public/docs/osc.md`.
 *
 * The usual entry points are [osc] and [oscClient], not the constructors: they hand back cached
 * [OscServer] / [OscClient] instances (keyed by port, and by host+port) so that re-running a box
 * during live editing reuses the existing socket instead of rebinding it. [OscServer] binds a UDP
 * port on every interface (default 9000) in its constructor and starts listening immediately.
 * Handlers registered with `on` are keyed by OSC address pattern and return a `Generator`. The
 * handler lambda itself runs on JavaOSC's receive thread when a message arrives; the generator it
 * returns is started, and resumed once, on the next service pass on the main thread, then resumed
 * once per pass until it finishes — so a handler can span frames. Every `yield` hands back the
 * message that started it. [OscClient] opens its outbound socket lazily on first `send`.
 *
 * Handlers belong to the server, not to the box that registered them: stopping or re-running the
 * box leaves them in place. Registering the same pattern again replaces the handler; a pattern the
 * edited code no longer mentions keeps firing until `remove` is called for it.
 *
 * Three things to know when debugging. A port another program already holds does not make the bind
 * fail: JavaOSC sets `SO_REUSEADDR`, so both sockets bind and a program bound to `127.0.0.1` takes
 * what is sent there. A handler that throws is reported as an error on the box that registered it,
and on the console; a failed send is only printed, so a box that sends every frame keeps running.
 * And [OscServer] has `debug` on by default, printing every incoming message to Field's console
 * (not to the box).
 */

/**
 * JavaOSC 0.9's blob parser returns the blob as a slice but leaves the read position at the start
 * of the blob's bytes, so every argument *after* a blob is decoded from the blob itself (an `i`
 * after the 3-byte blob `01 02 03` arrives as 16909056, `0x01020300`). This one reads the same
 * slice and then steps past the bytes and their padding.
 */
private class AdvancingBlobArgumentHandler : BlobArgumentHandler() {
    override fun parse(input: ByteBuffer): ByteBuffer {
        val value = super.parse(input)
        input.position(input.position() + value.remaining())
        OSCParser.align(input)
        return value
    }

    override fun clone(): AdvancingBlobArgumentHandler = AdvancingBlobArgumentHandler()
}

/** Seconds between the NTP epoch (1900) that OSC timetags count from and the Unix epoch (1970). */
private const val NTP_TO_UNIX_SECONDS = 2_208_988_800L

private val bundleScheduler = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "field-osc-bundle-timetags").apply { isDaemon = true }
}

/**
 * A dispatcher that honours bundle timetags with the right epoch.
 *
 * JavaOSC 0.9 converts a timetag to Java time from a 1900 epoch constant that is one hour off
 * (`-2208992400000` rather than `-2208988800000`), so it dispatches every future bundle 3600 s
 * early — a bundle stamped ten seconds ahead arrives at once. Here the delay is computed from the
 * raw NTP seconds, and the bundle is handed to the ordinary dispatcher, set to dispatch at once,
 * when it falls due. Bundles nested inside a bundle go out with their parent.
 */
private class TimetagDispatcher : OSCPacketDispatcher() {
    init {
        isAlwaysDispatchingImmediately = true
    }

    override fun handlePacket(event: OSCPacketEvent) {
        val bundle = event.packet as? OSCBundle
        val stamp = bundle?.timestamp
        if (stamp == null || stamp.isImmediate) {
            super.handlePacket(event)
            return
        }
        val dueMs = (stamp.seconds - NTP_TO_UNIX_SECONDS) * 1000L + (stamp.fraction * 1000L ushr 32)
        val delayMs = dueMs - System.currentTimeMillis()
        if (delayMs <= 0) super.handlePacket(event)
        else bundleScheduler.schedule({ super.handlePacket(event) }, delayMs, TimeUnit.MILLISECONDS)
    }
}

/**
 * Expand every `{a,b}` alternative list in an OSC address pattern, so that `/x/{1,10}/{a,b}` becomes
 * the four patterns `/x/1/a`, `/x/1/b`, `/x/10/a`, `/x/10/b`. A pattern without braces comes back
 * alone; an unclosed brace is left for the matcher to reject.
 */
internal fun expandOscBraces(pattern: String): List<String> {
    val open = pattern.indexOf('{')
    if (open < 0) return listOf(pattern)
    val close = pattern.indexOf('}', open)
    if (close < 0) return listOf(pattern)
    val head = pattern.substring(0, open)
    val tail = pattern.substring(close + 1)
    return pattern.substring(open + 1, close).split(',').flatMap { expandOscBraces(head + it + tail) }
}

/**
 * JavaOSC 0.9's pattern matcher gets `{…}` wrong once an alternative has matched the start of a part
 * and the rest then fails: stepping to the next alternative skips its first character, so
 * `/fader/{1,10}` never matches `/fader/10`. Expanding the braces first and matching each expansion
 * with JavaOSC's own selector keeps every other wildcard, `//` included, exactly as JavaOSC does it.
 */
internal class BraceExpandingSelector(pattern: String) : MessageSelector {
    private val selectors = expandOscBraces(pattern).map { OSCPatternAddressMessageSelector(it) }

    override fun isInfoRequired(): Boolean = false

    override fun matches(messageEvent: OSCMessageEvent): Boolean = selectors.any { it.matches(messageEvent) }
}

/** Throw, naming [localPort], when something on this machine already has it bound for UDP. */
internal fun requireOscPortFree(localPort: Int) {
    val probe = java.net.DatagramSocket(null as java.net.SocketAddress?)
    try {
        probe.reuseAddress = false
        probe.bind(InetSocketAddress(localPort))
    } catch (e: java.net.BindException) {
        throw IllegalStateException(
            "OSC port $localPort is already in use by another program (TouchOSC Bridge, Max, another Field?). " +
                "Messages sent there would go to it, not here. Find it with `lsof -nP -iUDP:$localPort`, " +
                "or listen on another port.",
            e,
        )
    } finally {
        probe.close()
    }
}

/**
 * Bind a receiving OSC port on every interface at [localPort], with the two JavaOSC 0.9 decoding
 * defects above corrected. Listeners are added afterwards through `dispatcher.addListener`.
 *
 * JavaOSC sets `SO_REUSEADDR`, under which binding a port another program already holds succeeds
 * and the other program (bound to `127.0.0.1`, say) quietly keeps the datagrams. So the port is
 * probed first with a plain bind, and a held port is refused by name.
 */
internal fun openOscPortIn(localPort: Int): OSCPortIn {
    requireOscPortFree(localPort)
    val parsers = OSCSerializerAndParserBuilder().setUsingDefaultHandlers(false)
    for ((id, handler) in Activator.createParserTypes()) {
        parsers.registerArgumentHandler(if (id == 'b') AdvancingBlobArgumentHandler() else handler, id)
    }
    return OSCPortIn(parsers, arrayListOf<OSCPacketListener>(TimetagDispatcher()), InetSocketAddress(localPort))
}

/**
 * Thin wrapper around JavaOSC's OSCPortIn that queues callbacks onto the main runloop.
 *
 * Register handlers via [on]; keys are OSC address patterns, values are Generators whose input is
 * [Message]. A message is matched against every registered pattern, and each handler whose pattern
 * matches runs. The handler lambda runs on the receive thread; the generators it returns are started
 * and stepped on the main thread via [run] or [tick].
 */
class OscServer(
    localPort: Int = 9000,
    autoStart: Boolean = true
) : Closeable {

    data class Message(
        val address: String,
        val arguments: List<Any?>,
        val event: OSCMessageEvent
    )

    private val port: OSCPortIn = openOscPortIn(localPort)

    /**
     * A handler and the box whose code registered it ([Box.context] at the time of [on]), which is
     * where the handler's failures are reported. [lastReported] keeps a handler that fails the same
     * way on every message — a fader sends dozens a second — to one error on the box rather than a
     * stream of them; registering the pattern again starts a fresh registration.
     */
    private class Registration(
        val handler: (Message) -> Generator<Message, *>,
        val owner: Box?,
        val runOwned: Boolean = false,
    ) {
        @Volatile var active = true
        @Volatile var lastReported: String? = null
    }

    private val handlers = ConcurrentHashMap<String, Registration>()
    private val registered = ConcurrentHashMap<String, Boolean>()
    private val pendingStart = ConcurrentLinkedQueue<Running>()
    private val running = mutableListOf<Running>()

    private val keepRunning = AtomicBoolean(false)

    var debug = true

    init {
        ensureRegistered("//*")
        if (autoStart) startListening()
    }

    val isListening: Boolean
        get() = port.isListening

    fun startListening() {
        try { port.startListening() } catch (_: Throwable) { }
    }

    fun stopListening() {
        try { port.stopListening() } catch (_: Throwable) { }
    }

    /**
     * Run [handler] for every message whose address matches [pattern], replacing any handler already
     * registered for that pattern. If the handler's lambda or its generator throws, the exception is
     * shown as an error on the box that called `on` (and printed to the console); the generator is
     * dropped and the handler stays registered for the next message.
     *
     * The handler belongs to that box the way any keyed registration does: stopping or re-running
     * the box leaves it in place (a re-run that calls `on` again replaces it), and deleting the box
     * or closing its document removes it.
     */
    fun on(pattern: String, handler: (Message) -> Generator<Message, *>) =
        on(pattern, runOwned = false, handler)

    /**
     * Register a handler, optionally releasing it on this run's stop, rerun or retirement.
     * Cleanup removes only this registration, never a newer replacement or the shared socket.
     * With [runOwned] false, the ordinary persistent registration semantics apply.
     */
    fun on(pattern: String, runOwned: Boolean, handler: (Message) -> Generator<Message, *>) {
        val scope = if (runOwned) requireNotNull(afield.runtime.lifecycle.RunScope.current()) {
            "runOwned OSC handlers need an executing box run"
        } else null
        val owner = scope?.box ?: Box.context.get()
        val registration = Registration(handler, owner, runOwned)
        val previous = handlers.put(pattern, registration)
        if (scope != null) scope.own {
            registration.active = false
            handlers.remove(pattern, registration)
        }
        // One release per (box, pattern), not one per re-run: a re-run replaces the handler in place.
        if (!runOwned && owner != null && (previous?.owner !== owner || previous.runOwned)) {
            owner.runScopes.parent.own(afield.runtime.lifecycle.ReleasePolicy.KEYED) {
                handlers.computeIfPresent(pattern) { _, current -> if (current.owner === owner) null else current }
            }
        }
        ensureRegistered(pattern)
    }

    fun remove(pattern: String) {
        handlers.remove(pattern)
    }

    /** Whether a handler is registered for exactly [pattern]. */
    fun hasHandler(pattern: String): Boolean = handlers.containsKey(pattern)

    private fun ensureRegistered(pattern: String) {
        if (registered.putIfAbsent(pattern, true) != null) return

        val selector = BraceExpandingSelector(pattern)
        val listener = OSCMessageListener { event ->
            try {
                // Only the catch-all listener logs, or a message that also matches a handler's
                // pattern would be printed once per matching listener.
                if (debug && pattern == "//*") {
                    println("OSC: ${event.message.address} = ${event.message.arguments.joinToString(", ")}")
                }
                val registration = handlers[pattern] ?: return@OSCMessageListener
                enqueue(pattern, registration, event)
            }
            catch (t: Throwable) { println("OSC handler failed: ${t.message}") }
        }
        port.dispatcher.addListener(selector, listener)
    }

    private class Running(
        val generator: Generator<Message, *>,
        val message: Message,
        val pattern: String,
        val registration: Registration,
    )

    private fun enqueue(pattern: String, registration: Registration, event: OSCMessageEvent) {
        if (!registration.active) return
        val message = Message(
            address = event.message.address,
            arguments = event.message.arguments?.toList() ?: emptyList(),
            event = event
        )
        try {
            pendingStart.add(Running(registration.handler(message), message, pattern, registration))
        } catch (t: Throwable) {
            report(registration, "OSC handler for ${message.address} (on \"$pattern\") threw ${describe(t)}")
        }
    }

    private fun service() {
        while (true) {
            val run = pendingStart.poll() ?: break
            if (!run.registration.active) continue
            try {
                val gen = run.generator
                gen.start()
                if (!gen.isCompleted) running += run
            } catch (t: Throwable) {
                report(run.registration, "OSC generator for ${run.message.address} (on \"${run.pattern}\") threw on start: ${describe(t)}")
            }
        }

        val it = running.iterator()
        while (it.hasNext()) {
            val run = it.next()
            try {
                if (!run.registration.active) {
                    run.generator.cancel()
                    it.remove()
                    continue
                }
                run.generator.next(run.message)
                if (run.generator.isCompleted) it.remove()
            } catch (t: Throwable) {
                report(run.registration, "OSC generator for ${run.message.address} (on \"${run.pattern}\") threw: ${describe(t)}")
                it.remove()
            }
        }
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}"

    /**
     * Print [text] to the console, and show it as an error on the box that registered the handler —
     * from whichever thread failed, as a box's own `println` from a raw thread does.
     */
    private fun report(registration: Registration, text: String) {
        println(text)
        val owner = registration.owner ?: return
        if (registration.lastReported == text) return
        registration.lastReported = text
        owner.error(text)
    }

    suspend fun run() {
        keepRunning.set(true)
        while (keepRunning.get()) {
            val job = inMain { service() }
            job.join()
            yield()
        }
    }

    /** Manual single tick; call from your main loop if you do not use [run]. */
    fun tick() = service()

    fun stopRunLoop() {
        keepRunning.set(false)
    }

    private val closed = AtomicBoolean(false)

    /** True once [close] has run. [osc] never hands back a closed server; it opens a new one. */
    val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        closed.set(true)
        stopRunLoop()
        stopListening()
        try { port.close() } catch (_: Throwable) { }
    }
}

/**
 * Thin wrapper around JavaOSC's OSCPortOut for sending messages.
 *
 * Connects lazily on first send, and can be cached via [OscClients] to avoid
 * recreating sockets across live edits.
 */
class OscClient(
    remoteHost: String = "127.0.0.1",
    remotePort: Int = 9000,
    autoConnect: Boolean = false
) : Closeable {

    private val remoteAddress = InetSocketAddress(remoteHost, remotePort)
    private var port: OSCPortOut? = null

    var debug = false

    init {
        if (autoConnect) connect()
    }

    val isConnected: Boolean
        get() = port?.isConnected ?: false

    private fun ensurePort(): OSCPortOut {
        val existing = port
        if (existing != null) return existing
        val created = OSCPortOutBuilder()
            .setRemoteSocketAddress(remoteAddress)
            .build()
        port = created
        return created
    }

    fun connect() {
        try { ensurePort().connect() } catch (_: Throwable) { }
    }

    fun disconnect() {
        try { port?.disconnect() } catch (_: Throwable) { }
    }

    fun send(address: String, arguments: List<Any?> = emptyList()) {
        send(OSCMessage(address, arguments))
    }

    /**
     * Convenience vararg sender; converts arguments to a list and delegates to [send].
     */
    fun send(address: String, vararg arguments: Any?) {
        send(address, arguments.toList())
    }

    /**
     * Send [messages] as one bundle, for the receiver to act on at [atMillis] (wall-clock
     * `System.currentTimeMillis()` time), or at once when it is null.
     *
     * ```kotlin
     * oscClient("127.0.0.1", 57120).sendBundle(listOf(OSCMessage("/note", [60])), atMillis = System.currentTimeMillis() + 500)
     * ```
     */
    fun sendBundle(messages: List<OSCMessage>, atMillis: Long? = null) {
        send(com.illposed.osc.OSCBundle(messages, atMillis?.let(::oscTimeTag) ?: com.illposed.osc.argument.OSCTimeTag64.IMMEDIATE))
    }

    /** One message, in a bundle timetagged [millis] from now — [sendBundle] for the common case. */
    fun sendIn(millis: Long, address: String, vararg arguments: Any?) {
        sendBundle(listOf(OSCMessage(address, arguments.toList())), System.currentTimeMillis() + millis)
    }

    /**
     * Send a pre-built OSC message.
     */
    fun send(message: OSCMessage) = send(message as com.illposed.osc.OSCPacket)

    /** Send a pre-built OSC message or bundle. */
    fun send(message: com.illposed.osc.OSCPacket) {
        val out = try {
            ensurePort()
        } catch (t: Throwable) {
            println("OSC send failed: ${t.message}")
            return
        }
        if (!out.isConnected) connect()
        try {
            if (debug) {
                println("OSC -> " + if (message is OSCMessage) "${message.address} = ${message.arguments.joinToString(", ")}" else "$message")
            }
            out.send(message)
        } catch (t: Throwable) {
            println("OSC send failed: ${t.message}")
        }
    }

    override fun close() {
        try { port?.close() } catch (_: Throwable) { }
        port = null
    }
}

/**
 * The OSC timetag for wall-clock time [atMillis]: NTP seconds since 1900 in the high 32 bits, the
 * fraction of a second in the low 32. Built from the numbers directly because JavaOSC's own
 * conversion from a `Date` counts from an epoch an hour away from the true one.
 */
fun oscTimeTag(atMillis: Long): com.illposed.osc.argument.OSCTimeTag64 {
    val seconds = Math.floorDiv(atMillis, 1000L) + 2_208_988_800L
    val fraction = (Math.floorMod(atMillis, 1000L) shl 32) / 1000L
    return com.illposed.osc.argument.OSCTimeTag64.valueOf((seconds shl 32) or fraction)
}

private data class OscEntry(val server: OscServer, val job: Job)
private data class OscClientKey(val host: String, val port: Int)
private data class OscClientEntry(val client: OscClient)

/**
 * Cached per-port servers that are automatically driven on the main runloop.
 */
object OscServers {
    private val cache = ConcurrentHashMap<Int, OscEntry>()

    @OptIn(DelicateCoroutinesApi::class)
    fun get(port: Int = 9000): OscServer {
        val entry = cache.compute(port) { _, existing ->
            if (existing == null || existing.server.isClosed) {
                val server = OscServer(port)
                val job = GlobalScope.launch(Runloop.runLoop.main) { server.run() }
                OscEntry(server, job)
            } else if (existing.job.isCancelled || existing.job.isCompleted) {
                val server = existing.server
                val job = GlobalScope.launch(Runloop.runLoop.main) { server.run() }
                OscEntry(server, job)
            } else existing
        }!!
        return entry.server
    }
}

/**
 * Cached per-host+port clients that open on first use and remain available across edits.
 */
object OscClients {
    private val cache = ConcurrentHashMap<OscClientKey, OscClientEntry>()

    fun get(host: String = "127.0.0.1", port: Int = 9000): OscClient {
        val key = OscClientKey(host, port)
        val entry = cache.compute(key) { _, existing ->
            if (existing == null) OscClientEntry(OscClient(host, port)) else existing
        }!!
        return entry.client
    }
}

/**
 * Get (and cache) an OSC server listening on [port].
 *
 * The server is started and driven on the main runloop the first time it is requested.
 */
fun osc(port: Int = 9000): OscServer = OscServers.get(port)

/**
 * Get (and cache) an OSC client targeting [host]:[port].
 *
 * The underlying socket is opened on first send and kept around for reuse across live edits.
 */
fun oscClient(host: String = "127.0.0.1", port: Int = 9000): OscClient = OscClients.get(host, port)
