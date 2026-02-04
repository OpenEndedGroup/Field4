package afield.stdlib

import afield.Generator
import afield.Runloop
import afield.inMain
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.OSCMessage
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import com.illposed.osc.transport.OSCPortInBuilder
import com.illposed.osc.transport.OSCPortOut
import com.illposed.osc.transport.OSCPortOutBuilder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around JavaOSC's OSCPortIn that queues callbacks onto the main runloop.
 *
 * Register handlers via [on]; keys are OSC address patterns, values are Generators whose input is [Message].
 * Incoming messages are matched against the patterns, and matching generators are
 * started and stepped on the main thread via [run] or [tick].
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

    private val port: OSCPortIn = OSCPortInBuilder()
        .setLocalPort(localPort)
        .build()

    private val handlers = ConcurrentHashMap<String, (Message) -> Generator<Message, *>>()
    private val registered = ConcurrentHashMap<String, Boolean>()
    private val pendingStart = ConcurrentLinkedQueue<Running>()
    private val running = mutableListOf<Running>()

    private val keepRunning = AtomicBoolean(false)

    var debug = true

    init {
        try { port.connect() } catch (_: Throwable) { }
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

    fun on(pattern: String, handler: (Message) -> Generator<Message, *>) {
        handlers[pattern] = handler
        ensureRegistered(pattern)
    }

    fun remove(pattern: String) {
        handlers.remove(pattern)
    }

    private fun ensureRegistered(pattern: String) {
        if (registered.putIfAbsent(pattern, true) != null) return

        val selector = OSCPatternAddressMessageSelector(pattern)
        val listener = OSCMessageListener { event ->
            try {
                if (debug) {
                    println("OSC: ${event.message.address} = ${event.message.arguments.joinToString(", ")}")
                }
                val handler = handlers[pattern] ?: return@OSCMessageListener
                enqueue(handler, event)
            }
            catch (t: Throwable) { println("OSC handler failed: ${t.message}") }
        }
        port.dispatcher.addListener(selector, listener)
    }

    private data class Running(val generator: Generator<Message, *>, val message: Message)

    private fun enqueue(handler: (Message) -> Generator<Message, *>, event: OSCMessageEvent) {
        val message = Message(
            address = event.message.address,
            arguments = event.message.arguments?.toList() ?: emptyList(),
            event = event
        )
        try {
            pendingStart.add(Running(handler(message), message))
        } catch (t: Throwable) {
            println("OSC handler for ${message.address} failed: ${t.message}")
        }
    }

    private fun service() {
        while (true) {
            val run = pendingStart.poll() ?: break
            try {
                val gen = run.generator
                gen.start()
                if (!gen.isCompleted) running += run
            } catch (t: Throwable) {
                println("OSC generator threw on start: ${t.message}")
            }
        }

        val it = running.iterator()
        while (it.hasNext()) {
            val run = it.next()
            try {
                run.generator.next(run.message)
                if (run.generator.isCompleted) it.remove()
            } catch (t: Throwable) {
                println("OSC generator threw on step: ${t.message}")
                it.remove()
            }
        }
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

    override fun close() {
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
     * Send a pre-built OSC message.
     */
    fun send(message: OSCMessage) {
        val out = try {
            ensurePort()
        } catch (t: Throwable) {
            println("OSC send failed: ${t.message}")
            return
        }
        if (!out.isConnected) connect()
        try {
            if (debug) {
                println("OSC -> ${message.address} = ${message.arguments.joinToString(", ")}")
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

private data class OscEntry(val server: OscServer, val job: Job)
private data class OscClientKey(val host: String, val port: Int)
private data class OscClientEntry(val client: OscClient)

/**
 * Cached per-port servers that are automatically driven on the main runloop.
 */
object OscServers {
    private val cache = ConcurrentHashMap<Int, OscEntry>()

    fun get(port: Int = 9000): OscServer {
        val entry = cache.compute(port) { _, existing ->
            if (existing == null || existing.job.isCancelled || existing.job.isCompleted) {
                val server = existing?.server ?: OscServer(port)
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
