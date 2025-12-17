package afield.stdlib

import afield.Generator
import afield.Runloop
import afield.inMain
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import com.illposed.osc.transport.OSCPortInBuilder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.Closeable
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

private data class OscEntry(val server: OscServer, val job: Job)

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

fun osc(port: Int = 9000): OscServer = OscServers.get(port)
