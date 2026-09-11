@file:afield.CompletionCategory("Audio")

package afield.four.synth

import afield.Box
import afield.graphquery.LiveGraphEdge
import afield.graphquery.LiveGraphInspectable
import afield.inMain
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import com.illposed.osc.transport.OSCPortInBuilder
import com.illposed.osc.transport.OSCPortOut
import com.illposed.osc.transport.OSCPortOutBuilder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Sonic Pi v5 driven from a Field box, in owned mode.
 *
 * [sonicPi] and [Box.sonicPi] are the box-facing entry points, both booting the one shared
 * [SonicPi] on first use. That class carries the control API — running and stopping code, the
 * mixer, recording, and the log and error feeds that come back into the box. The types around it
 * model what the daemon sends: [SonicPiLogMessage], [SonicPiError], [SonicPiFlash],
 * [SonicPiPorts] and [SonicPiVersion].
 */

/**
 * Sonic Pi v5 driven from Field, in *owned* mode: Field spawns the Sonic Pi daemon itself and
 * becomes the client the daemon expects, so there is no Sonic Pi window anywhere — a Field box is
 * the buffer, and Sonic Pi's log and error reports come back into the box.
 *
 * Owned mode is not a stylistic preference, it is the only mode in which the control API is
 * reachable on v5. Every interesting message (`/run-code`, `/stop-all-jobs`, the mixer, recording)
 * is authenticated with a random 32-bit token the daemon mints at boot, and v5 prints that token
 * exactly once, to the daemon's stdout. The pre-v5 trick of scraping it out of `daemon.log` is dead:
 * in v5 the boot-time token log is commented out and the only surviving `Util.log "Token: …"` sits
 * inside the daemon's exit handler, so it lands in the log *after* the daemon has stopped. If you
 * did not spawn the daemon, you cannot talk to it. (An already-running Sonic Pi GUI is still
 * reachable, but only over the unauthenticated cue port — see [cue].)
 *
 * Owning the daemon comes with one obligation: [SonicPiPorts.daemon] must keep receiving
 * `/daemon/keep-alive` or the daemon reaps its whole process tree. That is a feature, not a
 * nuisance — if Field dies, Sonic Pi takes itself down rather than leaving an orphaned audio server
 * behind. Do not trust the "more frequently than every 3s" in Sonic Pi's own daemon.rb header,
 * though; v5's `KillSwitch` sleeps 40s before it starts watching and then gives up only after five
 * empty 10s windows, so the real grace period is closer to 90s. We send every 1.2s regardless,
 * which is what the GUI does and leaves no room for argument. [close] skips the wait and asks the
 * daemon to exit immediately.
 *
 * Two implementation choices:
 *
 * Nothing here touches Field's main runloop. [afield.stdlib.OscServer] steps its handlers as
 * Generators on the main thread, which is right for a box reacting to a control surface and wrong
 * here — Sonic Pi's log arrives in bursts, and requiring a pumped runloop would make this unusable
 * from a headless `jvm-main` smoke test. Callbacks therefore fire on the OSC receive thread; hop to
 * the main thread yourself with [afield.inMain] if a callback touches the scene graph.
 *
 * Encoding goes through JavaOSC rather than by hand. OSC pads every string and typetag to a
 * multiple of four bytes, adding *nothing* when the length is already aligned, and getting that
 * wrong is invisible until it isn't: a stray four bytes after a `,is` typetag shifts the arguments
 * so the token decodes as `0`, and the only evidence is `Invalid token: 0` buried in
 * `~/.sonic-pi/log/spider.log` while unauthenticated messages like `/version` keep working fine.
 *
 * Verified against Sonic Pi v5.0.0 "SuperSonic" (bundle 5.0.0, Ruby 4.0.5).
 */
class SonicPi private constructor(
    private val install: SonicPiInstall,
    private val bootTimeoutMs: Long
) : AutoCloseable, LiveGraphInspectable {

    companion object {
        private const val KEEP_ALIVE_INTERVAL_MS = 1_200L

        @Volatile
        private var sharedInstance: SonicPi? = null

        /**
         * The one Sonic Pi this Field owns, booting it if necessary.
         *
         * Cached the way [afield.stdlib.OscServers] caches sockets, and for the same reason: a box
         * re-runs on every keystroke while you are live coding, and booting a second audio server
         * per edit would be a disaster rather than an inconvenience.
         */
        @Synchronized
        fun shared(
            install: SonicPiInstall = SonicPiInstall.discover(),
            bootTimeoutMs: Long = 90_000L
        ): SonicPi {
            sharedInstance?.takeUnless { it.closed }?.let { return it }
            return SonicPi(install, bootTimeoutMs).also { sharedInstance = it }
        }

        fun currentOrNull(): SonicPi? = sharedInstance?.takeUnless { it.closed }

        @Synchronized
        fun destroyShared() {
            sharedInstance?.close()
            sharedInstance = null
        }
    }

    /** Every log line, error and status message Sonic Pi has sent, oldest first, capped. */
    private val logBuffer = CopyOnWriteArrayList<SonicPiLogMessage>()
    private val errorBuffer = CopyOnWriteArrayList<SonicPiError>()
    private val seenAddresses = ConcurrentHashMap<String, Long>()
    private val pendingAcks = ConcurrentHashMap<String, CountDownLatch>()

    /** jobId -> workspace, learned from `/run/started`, so a late error can find its box. */
    private val jobWorkspaces = ConcurrentHashMap<Int, String>()
    private val workspaceJobs = ConcurrentHashMap<String, Int>()
    private val endedJobs = ConcurrentHashMap<Int, Boolean>()
    private val flashBuffer = CopyOnWriteArrayList<SonicPiFlash>()
    private val flashCounts = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()
    private val flashTimes = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()
    private val reportTargets = ConcurrentHashMap<String, ReportTarget>()

    @Volatile
    private var fallbackTarget: ReportTarget? = null

    private data class ReportTarget(val box: Box, val lineOffset: Int)

    private val readyLatch = CountDownLatch(1)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var versionReply: SonicPiVersion? = null

    /** Print Sonic Pi's log to stdout (and so into the box's output) as it arrives. */
    @Volatile
    var verbose: Boolean = false

    /** Print errors as they arrive. On by default — a silent syntax error is the worst outcome. */
    @Volatile
    var reportErrors: Boolean = true

    @Volatile
    var onLog: ((SonicPiLogMessage) -> Unit)? = null

    @Volatile
    var onError: ((SonicPiError) -> Unit)? = null

    /** Raw feed of everything the spider server sends, for messages this class does not model. */
    @Volatile
    var onMessage: ((String, List<Any?>) -> Unit)? = null

    /**
     * Called for every line Sonic Pi reports as having triggered sound.
     *
     * Fires on the OSC receive thread, at the rate the music plays — two `live_loop`s at a modest
     * tempo produce something like sixteen a second, and each iteration reports the loop's opening
     * line and its `end` as well as the `play` in between. Coalesce before drawing anything.
     */
    @Volatile
    var onFlash: ((SonicPiFlash) -> Unit)? = null

    @Volatile
    var closed: Boolean = false
        private set

    private val process: Process
    val ports: SonicPiPorts

    private val toSpider: OSCPortOut
    private val toDaemon: OSCPortOut
    private val fromSpider: OSCPortIn
    private val cueClients = ConcurrentHashMap<String, OSCPortOut>()

    private val keepAliveThread: Thread
    private val stdoutThread: Thread
    private val stderrThread: Thread
    private val shutdownHook: Thread

    init {
        require(install.ruby.canExecute()) { "Sonic Pi's bundled ruby is not executable: ${install.ruby}" }
        require(install.daemon.isFile) { "Sonic Pi's daemon.rb is missing: ${install.daemon}" }

        val processBuilder = ProcessBuilder(install.ruby.absolutePath, install.daemon.absolutePath)
            .directory(install.daemon.parentFile)
        processBuilder.environment().putAll(install.environment)
        process = processBuilder.start()

        // The daemon prints one line — six whitespace-separated integers — then keeps the pipe open
        // for its own logging. Both streams need draining forever regardless, or a full pipe buffer
        // wedges the daemon somewhere down the line.
        val portLine = CountDownLatch(1)
        val parsed = AtomicReference<SonicPiPorts?>(null)
        val bootLog = CopyOnWriteArrayList<String>()

        stdoutThread = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (parsed.get() == null) {
                        bootLog += line
                        SonicPiPorts.parse(line)?.let {
                            parsed.set(it)
                            portLine.countDown()
                        }
                    }
                    if (verbose) println("[sonic-pi daemon] $line")
                }
            }
        }, "sonic-pi-daemon-stdout").apply { isDaemon = true; start() }

        stderrThread = Thread({
            process.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    bootLog += line
                    if (verbose || parsed.get() == null) println("[sonic-pi daemon!] $line")
                }
            }
        }, "sonic-pi-daemon-stderr").apply { isDaemon = true; start() }

        // From here on the daemon is running, so every failure path has to take it down with us.
        // Anything else strands an audio server that nobody owns, holding the cue port, until the
        // daemon's own kill switch notices — which takes about a minute and a half, long enough to
        // push the *next* boot onto a different cue port and make it look like the port moved.
        var spiderOut: OSCPortOut? = null
        var daemonOut: OSCPortOut? = null
        var spiderIn: OSCPortIn? = null
        var keepAlive: Thread? = null
        var hook: Thread? = null
        try {
            if (!portLine.await(bootTimeoutMs, TimeUnit.MILLISECONDS) || parsed.get() == null) {
                error(
                    "Sonic Pi's daemon never printed its port line within ${bootTimeoutMs}ms. " +
                        "Daemon output so far:\n" + bootLog.joinToString("\n").take(4000)
                )
            }
            val p = parsed.get()!!

            spiderOut = openPortOut(p.guiSendToSpider)
            daemonOut = openPortOut(p.daemon)

            // The daemon picked gui-listen-to-spider as a free port and expects its client to bind it.
            val listening = OSCPortInBuilder().setLocalPort(p.guiListenToSpider).build()
            spiderIn = listening
            listening.dispatcher.addListener(OSCPatternAddressMessageSelector("//*"), OSCMessageListener { event ->
                runCatching { dispatch(event.message.address, event.message.arguments?.toList() ?: emptyList()) }
                    .onFailure { println("SonicPi: listener for ${event.message.address} failed: ${it.message}") }
            })
            // Deliberately no connect() here. A receive-only OSCPortIn has no remote address, so
            // connecting throws "Can't connect to port 0"; startListening() is what binds the local
            // port. OscServer in afield.stdlib does call connect(), but inside a
            // `catch (_: Throwable) {}` that hides this exact exception.
            listening.startListening()

            val daemonPort = daemonOut
            keepAlive = Thread({
                while (!stopping.get()) {
                    runCatching { daemonPort.send(OSCMessage("/daemon/keep-alive", listOf(p.token))) }
                    Thread.sleep(KEEP_ALIVE_INTERVAL_MS)
                }
            }, "sonic-pi-keep-alive").apply { isDaemon = true; start() }

            hook = Thread({ shutdown(fromHook = true) }, "sonic-pi-shutdown")
            runCatching { Runtime.getRuntime().addShutdownHook(hook) }

            ports = p
            toSpider = spiderOut
            toDaemon = daemonOut
            fromSpider = listening
            keepAliveThread = keepAlive
            shutdownHook = hook
        } catch (t: Throwable) {
            stopping.set(true)
            runCatching { parsed.get()?.let { daemonOut?.send(OSCMessage("/daemon/exit", listOf(it.token))) } }
            keepAlive?.interrupt()
            runCatching { spiderIn?.stopListening() }
            runCatching { spiderIn?.close() }
            runCatching { spiderOut?.close() }
            runCatching { daemonOut?.close() }
            hook?.let { h -> runCatching { Runtime.getRuntime().removeShutdownHook(h) } }
            process.destroyForcibly()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            throw t
        }
    }

    /** True once the spider server has announced `/spider/ready`. */
    val isReady: Boolean
        get() = readyLatch.count == 0L

    val isRunning: Boolean
        get() = !closed && process.isAlive

    /** Block until Sonic Pi is ready to accept code. Returns false on timeout. */
    fun awaitReady(timeoutMs: Long = 60_000L): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Evaluate Sonic Pi code — the whole point of this class.
     *
     * `workspace` is the buffer name Sonic Pi attributes the run to, and shows in error reports;
     * a Field box's name makes a good one. Fire-and-forget: errors surface asynchronously through
     * [onError] and [errors], not as a thrown exception, because that is how Sonic Pi reports them.
     */
    fun run(code: String, workspace: String? = null, silent: Boolean = false): SonicPi = apply {
        val args = mutableListOf<Any?>(ports.token, code)
        if (workspace != null) {
            args += workspace
            args += if (silent) 1 else 0
        }
        send("/run-code", args)
    }

    fun stopAll(): SonicPi = apply { send("/stop-all-jobs", listOf(ports.token)) }

    fun stop(jobId: Int): SonicPi = apply { send("/stop-job", listOf(ports.token, jobId)) }

    /** Round-trip a `/ping` and wait for its `/ack`. The cheapest proof the token is accepted. */
    fun ping(id: String = "field4", timeoutMs: Long = 5_000L): Boolean {
        val latch = CountDownLatch(1)
        pendingAcks[id] = latch
        try {
            send("/ping", listOf(ports.token, id))
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pendingAcks.remove(id)
        }
    }

    /** Ask Sonic Pi its version, waiting for the reply. */
    fun version(timeoutMs: Long = 5_000L): SonicPiVersion? {
        versionReply?.let { return it }
        send("/version", listOf(ports.token))
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            versionReply?.let { return it }
            Thread.sleep(50)
        }
        return versionReply
    }

    /** The fader after the limiter, `0.0`–`1.0`. v5 rescaled this: v4's `0`–`5` range is gone. */
    fun volume(value: Double, silent: Boolean = false): SonicPi =
        apply { send("/mixer-output-volume", listOf(ports.token, value.toFloat(), if (silent) 1 else 0)) }

    /** The gain *before* the limiter, `0.0`–`1.0` (`0.5` is unity). New in v5. */
    fun drive(value: Double, silent: Boolean = false): SonicPi =
        apply { send("/mixer-drive", listOf(ports.token, value.toFloat(), if (silent) 1 else 0)) }

    fun startRecording(): SonicPi = apply { send("/start-recording", listOf(ports.token)) }

    fun stopRecording(): SonicPi = apply { send("/stop-recording", listOf(ports.token)) }

    fun saveRecording(path: String): SonicPi =
        apply { send("/save-recording", listOf(ports.token, path)) }

    fun deleteRecording(): SonicPi = apply { send("/delete-recording", listOf(ports.token)) }

    /**
     * Send an OSC cue to Sonic Pi's cue port.
     *
     * Sonic Pi turns an incoming cue into a cue name of the form `/osc:<host>:<port>/your/path`,
     * which Sonic Pi code waits on with `sync` — in practice via its wildcard prefix rather than the
     * literal host and port. (That wildcard is spelled with a star, which cannot be written in a
     * KDoc block without ending it.)
     *
     * Unlike everything else here this needs no token, so the same call reaches a Sonic Pi GUI you
     * launched yourself — pass `host`/`port` to aim at one. Cues are the right channel for
     * per-event traffic; [run] is for changing what the music is.
     */
    fun cue(
        address: String,
        args: List<Any?> = emptyList(),
        host: String = "127.0.0.1",
        port: Int = ports.oscCues
    ): SonicPi = apply {
        val key = "$host:$port"
        val client = cueClients.computeIfAbsent(key) {
            OSCPortOutBuilder().setRemoteSocketAddress(InetSocketAddress(host, port)).build()
        }
        runCatching { client.send(OSCMessage(address, args)) }
            .onFailure { println("SonicPi: cue $address failed: ${it.message}") }
    }

    fun cue(address: String, vararg args: Any?): SonicPi = cue(address, args.toList())

    /**
     * Route Sonic Pi's output for `workspace` into `box`.
     *
     * Necessary because Sonic Pi's errors are *asynchronous*. [run] returns as soon as the OSC
     * message is away, and the error for a bad line arrives a second or so later on the OSC receive
     * thread — by which time the box has finished running. A bare `println` from that thread is
     * `kotlin.io.println`, not `Box.println`, so it goes to the process stdout Field was launched
     * from and never appears in the box. Errors raised inside a `live_loop` can arrive minutes
     * later, which makes this the normal case rather than an edge one.
     *
     * `lineOffset` is the number of box lines before the Ruby source. Both Sonic Pi and Field's
     * editor/output protocol use 1-based lines, so a reported line maps to `reportedLine +
     * lineOffset`. The default of 0 is right for the usual shape, where the opening `sonicPi("""`
     * and the first line of Ruby share a line.
     */
    fun reportTo(workspace: String, box: Box, lineOffset: Int = 0): SonicPi = apply {
        val target = ReportTarget(box, lineOffset)
        reportTargets[workspace] = target
        fallbackTarget = target
    }

    fun stopReportingTo(workspace: String): SonicPi = apply {
        val removed = reportTargets.remove(workspace)
        if (removed != null && fallbackTarget == removed) fallbackTarget = null
    }

    /**
     * Which lines have triggered sound, and how often, for `workspace`.
     *
     * Reset when a new run starts on that workspace, so this describes the run you are listening to
     * rather than the whole session. This is the "what is making sound and where" signal: Sonic Pi
     * emits it from `play`, `synth`, `sample`, `control`, `play_chord` and the `with_fx`/`live_loop`
     * block boundaries, tagged with the caller's line.
     *
     * It reports *triggering*, not audibility — a `play` at `amp: 0` still creates a node and still
     * flashes, so a line being hot does not prove you can hear it.
     */
    fun activity(workspace: String): Map<Int, Long> = flashCounts[workspace]?.toMap() ?: emptyMap()

    val flashes: List<SonicPiFlash>
        get() = flashBuffer.toList()

    /**
     * How long ago, in milliseconds, each line of `workspace` last triggered sound.
     *
     * The basis for showing activity in the editor: recency rather than a count, because what a
     * player wants to see is which lines are sounding *now*.
     */
    fun lineAges(workspace: String, now: Long = System.nanoTime()): Map<Int, Long> =
        flashTimes[workspace]?.mapValues { (_, at) -> (now - at) / 1_000_000 } ?: emptyMap()

    /** The lines of `workspace` that triggered sound within the last [withinMs]. */
    fun activeLines(workspace: String, withinMs: Long = 500L): List<Int> =
        lineAges(workspace).filterValues { it <= withinMs }.keys.sorted()

    /** The most recent Sonic Pi job id for `workspace`, if one has started. */
    fun jobIdFor(workspace: String): Int? = workspaceJobs[workspace]

    fun isJobAlive(jobId: Int): Boolean = !endedJobs.containsKey(jobId)

    /** Wait for `workspace`'s next job to start, returning its id. */
    fun awaitJobStart(workspace: String, timeoutMs: Long = 15_000L): Int? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            workspaceJobs[workspace]?.let { return it }
            Thread.sleep(20)
        }
        return workspaceJobs[workspace]
    }

    val log: List<SonicPiLogMessage>
        get() = logBuffer.toList()

    val errors: List<SonicPiError>
        get() = errorBuffer.toList()

    /** Addresses seen from the spider server, with how many of each — handy when something is quiet. */
    fun messageCounts(): Map<String, Long> = seenAddresses.toMap()

    fun clearLog(): SonicPi = apply {
        logBuffer.clear()
        errorBuffer.clear()
    }

    private fun send(address: String, args: List<Any?>) {
        if (closed) {
            println("SonicPi: ignoring $address — this client is closed")
            return
        }
        runCatching { toSpider.send(OSCMessage(address, args)) }
            .onFailure { println("SonicPi: send $address failed: ${it.message}") }
    }

    private fun openPortOut(port: Int): OSCPortOut =
        OSCPortOutBuilder()
            .setRemoteSocketAddress(InetSocketAddress("127.0.0.1", port))
            .build()
            .also { runCatching { it.connect() } }

    private fun dispatch(address: String, args: List<Any?>) {
        seenAddresses.merge(address, 1L, Long::plus)
        onMessage?.invoke(address, args)

        when (address) {
            "/spider/ready" -> readyLatch.countDown()

            "/ack" -> (args.firstOrNull() as? String)?.let { pendingAcks[it]?.countDown() }

            "/version" -> versionReply = SonicPiVersion(
                version = args.getOrNull(0) as? String ?: "?",
                versionNumber = (args.getOrNull(1) as? Number)?.toInt() ?: 0,
                latestVersion = args.getOrNull(2) as? String,
                platform = args.getOrNull(7) as? String
            )

            // `/run/started` is the only place the jobId <-> workspace pairing is stated, and it is
            // what lets an error that surfaces much later still find the box that caused it.
            "/run/started" -> {
                val jobId = (args.getOrNull(0) as? Number)?.toInt()
                val workspace = args.getOrNull(1) as? String
                if (jobId != null && workspace != null) {
                    if (jobWorkspaces.size > MAX_TRACKED_JOBS) jobWorkspaces.clear()
                    jobWorkspaces[jobId] = workspace
                    workspaceJobs[workspace] = jobId
                    endedJobs.remove(jobId)
                    flashCounts.remove(workspace)
                    flashTimes.remove(workspace)
                    // A fresh run means the previous run's marker is stale.
                    reportTargets[workspace]?.let { target ->
                        inMain { runCatching { target.box.error(null, "code", -1) } }
                    }
                }
            }

            "/run/ended" -> (args.getOrNull(0) as? Number)?.toInt()?.let { endedJobs[it] = true }

            "/flash" -> {
                val flash = SonicPiFlash(
                    jobId = (args.getOrNull(0) as? Number)?.toInt() ?: -1,
                    workspace = args.getOrNull(1) as? String ?: "",
                    line = (args.getOrNull(2) as? Number)?.toInt() ?: -1
                )
                if (flash.line >= 0) {
                    flashBuffer += flash
                    while (flashBuffer.size > MAX_FLASHES) flashBuffer.removeAt(0)
                    flashCounts.computeIfAbsent(flash.workspace) { ConcurrentHashMap() }
                        .merge(flash.line, 1L, Long::plus)
                    flashTimes.computeIfAbsent(flash.workspace) { ConcurrentHashMap() }[flash.line] =
                        System.nanoTime()
                    runCatching { onFlash?.invoke(flash) }
                        .onFailure { println("SonicPi: onFlash threw: ${it.message}") }
                }
            }

            "/log/multi_message" -> emit(decodeMultiMessage(args))

            "/log/info" -> emit(decodeLogInfo(args))

            "/error" -> emit(decodeError(args, syntax = false))

            "/syntax_error" -> emit(decodeError(args, syntax = true))
        }
    }

    private fun targetFor(jobId: Int): ReportTarget? =
        jobWorkspaces[jobId]?.let { reportTargets[it] } ?: fallbackTarget

    private fun emit(message: SonicPiLogMessage) {
        logBuffer += message
        trim(logBuffer)
        val target = if (message.jobId >= 0) targetFor(message.jobId) else null
        if (target != null && message.text.isNotBlank()) {
            // Only a job's own output goes to the box. Sonic Pi's boot chatter arrives on
            // /log/info with no job, and putting "Initialised SuperSonic" in someone's box would
            // be noise rather than feedback.
            inMain { runCatching { target.box.println(message.text) } }
        } else if (verbose) {
            println(message.toString())
        }
        runCatching { onLog?.invoke(message) }
            .onFailure { println("SonicPi: onLog threw: ${it.message}") }
    }

    private fun emit(error: SonicPiError) {
        errorBuffer += error
        trim(errorBuffer)
        val target = targetFor(error.jobId)
        if (target != null) {
            // One error-kind output on purpose. A following normal println would make Field's
            // normal-output handler clear this error immediately, leaving grey text and no marker.
            // toString includes the source line as well as the diagnostic, so the red output remains
            // readable without a second channel. Sonic Pi and Field both count lines from one.
            inMain {
                runCatching {
                    target.box.error(error.toString(), "code", sonicPiErrorBoxLine(error.line, target.lineOffset))
                }
            }
        } else if (reportErrors) {
            println(error.toString())
        }
        runCatching { onError?.invoke(error) }
            .onFailure { println("SonicPi: onError threw: ${it.message}") }
    }

    private fun trim(buffer: CopyOnWriteArrayList<*>) {
        while (buffer.size > MAX_BUFFERED) buffer.removeAt(0)
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("running") { isRunning },
        LiveGraphEdge("ready") { isReady },
        LiveGraphEdge("ports") { ports },
        LiveGraphEdge("logLines") { logBuffer.size },
        LiveGraphEdge("errors") { errorBuffer.size },
        LiveGraphEdge("messageCounts") { messageCounts() }
    )

    override fun toString(): String = synthPrettyObject(
        this,
        "running" to isRunning,
        "ready" to isReady,
        "ports" to ports,
        "logLines" to logBuffer.size,
        "errors" to errorBuffer.size
    )

    override fun close() = shutdown(fromHook = false)

    private fun shutdown(fromHook: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        closed = true

        // Ask nicely first: /daemon/exit is authenticated with the same token and tears down the
        // spider and the audio server with it. If Field died instead, the 3s keep-alive timeout
        // gets us the same outcome.
        runCatching { toDaemon.send(OSCMessage("/daemon/exit", listOf(ports.token))) }
        runCatching { keepAliveThread.interrupt() }

        runCatching { fromSpider.stopListening() }
        runCatching { fromSpider.close() }
        runCatching { toSpider.close() }
        runCatching { toDaemon.close() }
        cueClients.values.forEach { c -> runCatching { c.close() } }
        cueClients.clear()

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }

        if (!fromHook) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            if (sharedInstance === this) sharedInstance = null
        }
    }
}

private const val MAX_BUFFERED = 5_000
private const val MAX_TRACKED_JOBS = 4_096
private const val MAX_FLASHES = 2_048

/** One line reported as having triggered sound. See [SonicPi.activity]. */
data class SonicPiFlash(val jobId: Int, val workspace: String, val line: Int)

/**
 * Work out where `code` starts inside the box, so a line Sonic Pi reports can be pointed at.
 *
 * Sonic Pi numbers its errors from line 1 of the source it was handed. That source is the inside of a
 * Kotlin triple-quoted string, so its line 1 is the box line the opening quote sits on — which is
 * line 1 only when the `sonicPi(` call is the first thing in the box. One `import` above it and every
 * marker is off by one, which is exactly the kind of wrongness nobody reports and everybody stops
 * trusting. Since the box's own text is right here, find the code in it rather than asking the caller.
 *
 * Returns 0 when the code cannot be located, which is the old behaviour and right for a box whose
 * code is built at runtime rather than written as a literal.
 */
internal fun inferSonicPiLineOffset(box: Box, code: String): Int {
    val source = runCatching { box.getPropertyRaw("code") as? String }.getOrNull() ?: return 0
    if (source.isEmpty() || code.isEmpty()) return 0
    // The literal appears verbatim; anchor on the longest prefix that is cheap to find.
    var at = source.indexOf(code)
    if (at < 0) {
        // A trimIndent()-ed or otherwise reshaped literal will not match whole. Fall back to the
        // first non-blank line, which is enough to locate the region's start.
        val anchor = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return 0
        if (anchor.isEmpty()) return 0
        val anchorAt = source.indexOf(anchor)
        if (anchorAt < 0) return 0
        // The anchor is the region's first non-blank line; step back over the blank ones.
        val blankLeading = code.lineSequence().takeWhile { it.isBlank() }.count()
        return (source.take(anchorAt).count { it == '\n' } - blankLeading).coerceAtLeast(0)
    }
    return source.take(at).count { it == '\n' }
}

/**
 * Decode `/log/multi_message`: `jobId, thread, runtime, count`, then `count` flattened
 * `(style, text)` pairs.
 *
 * Tolerant — the count and the payload come from separate parts of the spider server, so
 * a message that claims more pairs than it carries decodes to the pairs that are actually there.
 * Losing a log line is a cosmetic failure; throwing on the OSC receive thread would not be.
 */
internal fun decodeMultiMessage(args: List<Any?>): SonicPiLogMessage {
    val count = (args.getOrNull(3) as? Number)?.toInt() ?: 0
    val lines = (0 until count).mapNotNull { i ->
        val style = (args.getOrNull(4 + i * 2) as? Number)?.toInt() ?: return@mapNotNull null
        val text = args.getOrNull(5 + i * 2) as? String ?: return@mapNotNull null
        SonicPiLogLine(style, text)
    }
    return SonicPiLogMessage(
        jobId = (args.getOrNull(0) as? Number)?.toInt() ?: -1,
        // Ruby sends the thread name through `.inspect`, so it arrives already quoted.
        thread = (args.getOrNull(1) as? String)?.trim('"') ?: "",
        runtime = (args.getOrNull(2) as? String)?.toDoubleOrNull() ?: 0.0,
        lines = lines
    )
}

/** Decode `/log/info`: `style, text`. Sonic Pi's own chatter rather than a job's output. */
internal fun decodeLogInfo(args: List<Any?>): SonicPiLogMessage = SonicPiLogMessage(
    jobId = -1,
    thread = "info",
    runtime = 0.0,
    lines = listOf(
        SonicPiLogLine(
            (args.getOrNull(0) as? Number)?.toInt() ?: 0,
            args.getOrNull(1) as? String ?: ""
        )
    )
)

/**
 * Decode `/error` and `/syntax_error`, which do not agree on argument order.
 *
 * `/error` is `jobId, message, backtrace, line, firstCol, lastCol, sourceLine`, while
 * `/syntax_error` is `jobId, message, sourceLine, line, lineAsString, firstCol, lastCol` — the
 * offending source moves from last to third, there is no backtrace, and the line number arrives
 * twice, once as an int and once as a string.
 */
internal fun decodeError(args: List<Any?>, syntax: Boolean): SonicPiError = SonicPiError(
    jobId = (args.getOrNull(0) as? Number)?.toInt() ?: -1,
    message = args.getOrNull(1) as? String ?: "",
    backtrace = if (syntax) "" else args.getOrNull(2) as? String ?: "",
    line = (args.getOrNull(3) as? Number)?.toInt() ?: -1,
    firstColumn = (args.getOrNull(if (syntax) 5 else 4) as? Number)?.toInt() ?: -1,
    lastColumn = (args.getOrNull(if (syntax) 6 else 5) as? Number)?.toInt() ?: -1,
    sourceLine = (if (syntax) args.getOrNull(2) else args.getOrNull(6)) as? String ?: "",
    syntax = syntax
)

/**
 * The ports and token the Sonic Pi daemon allocates at boot and prints, once, to stdout.
 *
 * The order is the daemon's, not ours: `daemon gui-listen-to-spider gui-send-to-spider scsynth
 * osc-cues token`. `scsynth` keeps its historical name in v5 even though SuperSonic has replaced
 * scsynth as the audio engine.
 */
data class SonicPiPorts(
    val daemon: Int,
    val guiListenToSpider: Int,
    val guiSendToSpider: Int,
    val scsynth: Int,
    val oscCues: Int,
    val token: Int
) {
    companion object {
        // Six integers alone on a line. The token is a random *signed* 32-bit int, so it is very
        // often negative — a `\d+`-only pattern silently never matches roughly half the time.
        private val LINE = Regex("""^\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*$""")

        fun parse(line: String): SonicPiPorts? {
            val m = LINE.matchEntire(line) ?: return null
            val v = m.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return SonicPiPorts(v[0], v[1], v[2], v[3], v[4], v[5])
        }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "daemon" to daemon,
        "guiListenToSpider" to guiListenToSpider,
        "guiSendToSpider" to guiSendToSpider,
        "scsynth" to scsynth,
        "oscCues" to oscCues,
        // deliberately not the token — this ends up in logs and screenshots
        "token" to "<redacted>"
    )
}

/** Where Sonic Pi is installed, and the files/environment that owned mode needs. */
data class SonicPiInstall(
    val home: File,
    val ruby: File,
    val daemon: File,
    /** AppImages need the same library and executable search paths that their `AppRun` sets. */
    val environment: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * Find a Sonic Pi install.
         *
         * `-Dsonicpi.home=` or `SONIC_PI_HOME` wins, and may point at the `Contents/Resources`
         * directory on macOS, an extracted Linux AppDir, or the AppImage itself. A Linux AppImage
         * in `~/Downloads` is also found automatically and extracted once into Field's cache.
         */
        fun discover(): SonicPiInstall {
            val explicit = System.getProperty("sonicpi.home") ?: System.getenv("SONIC_PI_HOME")
            val explicitAppImage =
                System.getProperty("sonicpi.appimage") ?: System.getenv("FIELD_SONIC_PI_APPIMAGE")

            sequenceOf(explicitAppImage, explicit)
                .filterNotNull()
                .map(::File)
                .filter { it.isFile && it.name.endsWith(".AppImage", ignoreCase = true) }
                .forEach { return fromAppImage(it) }

            val roots = buildList {
                explicit?.let { File(it).takeIf(File::isDirectory)?.let(::add) }
                add(File("/Applications/Sonic Pi.app/Contents/Resources"))
                add(File(System.getProperty("user.home"), "Applications/Sonic Pi.app/Contents/Resources"))
                add(File("/opt/sonic-pi"))
                add(File("/usr/lib/sonic-pi"))
            }
            for (root in roots) {
                of(root)?.let { return it }
            }

            appImageCandidates().forEach { return fromAppImage(it) }

            error(
                "Could not find a Sonic Pi install. Looked in:\n" +
                    roots.joinToString("\n") { "  $it" } +
                    "\nAlso looked for Sonic Pi AppImages in ~/Downloads. " +
                    "Set -Dsonicpi.home=…, SONIC_PI_HOME, or FIELD_SONIC_PI_APPIMAGE."
            )
        }

        /** Build an install from a candidate app root/AppDir, or null if the pieces are not there. */
        fun of(root: File): SonicPiInstall? {
            val homes = listOf(
                root,
                File(root, "Contents/Resources"),
                File(root, "usr/share/sonic-pi"),
                File(root, "share/sonic-pi")
            ).map { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }.distinct()
            return homes.firstNotNullOfOrNull(::ofHome)
        }

        private fun ofHome(root: File): SonicPiInstall? {
            val daemon = File(root, "app/server/ruby/bin/daemon.rb").canonicalFile
            if (!daemon.isFile) return null

            // Linux AppImages put Ruby beside share/, not below Sonic Pi's app/server/native/.
            val appImageRuby = File(root, "../../ruby/bin/ruby").canonicalFile
            val ruby = sequenceOf(
                File(root, "app/server/native/ruby/bin/ruby"),
                File(root, "server/native/ruby/bin/ruby"),
                appImageRuby,
                File("/usr/bin/ruby")
            ).map { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }
                .firstOrNull { it.canExecute() } ?: return null

            val environment = if (ruby == appImageRuby) appImageEnvironment(root) else emptyMap()
            return SonicPiInstall(root.canonicalFile, ruby, daemon, environment)
        }

        private fun appImageEnvironment(home: File): Map<String, String> {
            val usr = File(home, "../..").canonicalFile
            val rubyBin = File(usr, "ruby/bin").absolutePath
            val libraryPath = listOf(File(usr, "lib"), File(usr, "ruby/lib"))
                .joinToString(File.pathSeparator) { it.absolutePath }
            val currentPath = System.getenv("PATH").orEmpty()
            val currentLibraries = System.getenv("LD_LIBRARY_PATH").orEmpty()
            return mapOf(
                "PATH" to listOf(rubyBin, currentPath).filter(String::isNotBlank).joinToString(File.pathSeparator),
                "LD_LIBRARY_PATH" to listOf(libraryPath, currentLibraries)
                    .filter(String::isNotBlank)
                    .joinToString(File.pathSeparator)
            )
        }

        private fun appImageCandidates(): List<File> {
            val home = File(System.getProperty("user.home"))
            return listOf(File(home, "Downloads"), File(home, "downloads"))
                .flatMap { directory ->
                    directory.listFiles { file ->
                        file.isFile && file.name.contains("sonic", ignoreCase = true) &&
                            file.name.contains("pi", ignoreCase = true) &&
                            file.name.endsWith(".AppImage", ignoreCase = true)
                    }?.toList().orEmpty()
                }
                .distinctBy { it.absolutePath }
                .sortedByDescending { it.lastModified() }
        }

        @Synchronized
        private fun fromAppImage(appImage: File): SonicPiInstall {
            require(appImage.canExecute()) { "Sonic Pi AppImage is not executable: $appImage" }

            val cacheBase = System.getenv("XDG_CACHE_HOME")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: File(System.getProperty("user.home"), ".cache")
            val cacheKey = buildString {
                append(appImage.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                append('-').append(appImage.length())
                append('-').append(appImage.lastModified())
            }
            val cache = File(cacheBase, "field4/sonic-pi/appimages/$cacheKey")
            val extracted = File(cache, "squashfs-root")
            val complete = File(cache, ".field4-extraction-complete")

            if (complete.isFile) {
                of(extracted)?.let { return it }
            }

            check(cache.mkdirs() || cache.isDirectory) { "Could not create Sonic Pi cache: $cache" }
            val extractionLog = File(cache, "extract.log")
            val process = ProcessBuilder(appImage.absolutePath, "--appimage-extract", "usr")
                .directory(cache)
                .redirectOutput(extractionLog)
                .redirectErrorStream(true)
                .start()
            val exit = process.waitFor()
            check(exit == 0) {
                "Could not extract Sonic Pi AppImage (exit $exit). See $extractionLog"
            }
            val install = of(extracted)
                ?: error("Sonic Pi AppImage extracted, but its server/Ruby layout was not recognised: $extracted")
            complete.writeText("${appImage.absolutePath}\n${appImage.length()}\n${appImage.lastModified()}\n")
            return install
        }

        fun isInstalled(): Boolean = runCatching { discover() }.isSuccess
    }
}

/** One styled line of Sonic Pi's log. Style `0` is Sonic Pi's own chatter, `1` is your `puts`. */
data class SonicPiLogLine(val style: Int, val text: String)

data class SonicPiLogMessage(
    val jobId: Int,
    val thread: String,
    val runtime: Double,
    val lines: List<SonicPiLogLine>
) {
    val text: String get() = lines.joinToString("\n") { it.text }

    override fun toString(): String {
        val where = if (jobId >= 0) "run $jobId" else thread
        return "[sonic-pi $where] $text"
    }
}

data class SonicPiError(
    val jobId: Int,
    val message: String,
    val backtrace: String,
    val line: Int,
    val firstColumn: Int,
    val lastColumn: Int,
    val sourceLine: String,
    val syntax: Boolean
) {
    override fun toString(): String = buildString {
        append("[sonic-pi ")
        append(if (syntax) "syntax error" else "error")
        if (jobId >= 0) append(" in run $jobId")
        if (line >= 0) append(" at line $line")
        append("] ")
        append(message.trim())
        if (sourceLine.isNotBlank()) {
            append("\n  ")
            append(sourceLine.trim())
        }
    }
}

/** Translate a 1-based Sonic Pi source line into Field's 1-based output protocol. */
internal fun sonicPiErrorBoxLine(sonicPiLine: Int, lineOffset: Int): Int =
    if (sonicPiLine < 1) -1 else sonicPiLine + lineOffset

data class SonicPiVersion(
    val version: String,
    val versionNumber: Int,
    val latestVersion: String?,
    val platform: String?
)

/**
 * The Sonic Pi this Field owns, booting it on first use.
 *
 * Booting takes a couple of seconds; this returns as soon as the daemon has handed over its ports,
 * which is before the audio engine is up. Call [SonicPi.awaitReady] first if the very next thing
 * you do is make a sound.
 */
fun sonicPi(): SonicPi = SonicPi.shared()

/**
 * Boot Sonic Pi if necessary and evaluate `code` in it.
 *
 * The one-liner form, for a box that is a piece of music:
 * ```
 * sonicPi("live_loop :field do; sample :bd_haus; sleep 0.5; end")
 * ```
 * Re-running the box redefines the `live_loop` in place rather than starting a second one — that is
 * Sonic Pi's own semantics for a named `live_loop`, and the reason this pairs so well with a box.
 */
fun sonicPi(code: String, workspace: String? = null): SonicPiLaunch {
    val sp = SonicPi.shared()
    sp.awaitReady()
    val where = workspace ?: "field4"
    val prior = sp.jobIdFor(where)
    sp.run(code, where)
    return SonicPiLaunch(sp, where, prior)
}

/**
 * Run Sonic Pi code, get its errors back *in this box*, and tie the music to the box's lifecycle.
 *
 * Returning the result as the box's last expression is what connects the two: the box stays lit for
 * as long as the music plays, and stopping the box stops it (see [SonicPiLaunch]).
 *
 * Prefer this over the bare [sonicPi] from inside a box. Sonic Pi reports errors asynchronously, so
 * by the time one arrives the box has finished running and there is nothing left to attribute it to
 * — this wires the box up as the destination first (see [SonicPi.reportTo]). It matters most for the
 * failure that looks like success: a `live_loop` whose body raises on its own thread, minutes into a
 * performance, with the run that started it long since finished.
 *
 * ```
 * sonicPi("""
 * live_loop :bikes do
 *   ...
 * end
 * """)
 * ```
 *
 * `workspace` defaults to the box's name, falling back to its id, and is the name Sonic Pi uses when
 * it describes where an error happened.
 */
fun Box.sonicPi(code: String, workspace: String? = null, lineOffset: Int = -1): SonicPiLaunch {
    val where = workspace ?: name.ifBlank { id }
    val offset = if (lineOffset >= 0) lineOffset else inferSonicPiLineOffset(this, code)
    val sp = SonicPi.shared()
    sp.reportTo(where, this, offset)
    sp.awaitReady()
    val prior = sp.jobIdFor(where)
    sp.run(code, where)
    return SonicPiLaunch(sp, where, prior, offset)
}
