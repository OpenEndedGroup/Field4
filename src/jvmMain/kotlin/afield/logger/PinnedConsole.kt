package afield.logger

import afield.ai.core.BlockBoundary
import afield.ai.core.ConsoleMute
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.StdoutLog
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.terminal.Terminal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * A [LogSink] that pins chosen channels to a small, non-scrolling region at the *top* of the
 * terminal while every other log line scrolls normally below it.
 *
 * The heavy lifting is a single ANSI scroll-region (DECSTBM): the top N rows are fenced off so
 * ordinary output can only scroll in the region beneath them, which means a plain `println` "just
 * works" — there is no per-frame render loop over application state. The header is repainted only
 * when a pinned value actually changes (coalesced by a single daemon tick), and the terminal size
 * is re-checked about once a second.
 *
 * Selection is by channel tag: a log event whose comma-separated `channel` contains a pinned tag
 * (e.g. pinning `"audio"` matches `"info,audio"`) updates that tag's row instead of scrolling.
 * Configure via the `AFIELD_PIN_CHANNELS` env var (comma/space separated) and/or at runtime with
 * [pin]/[unpin]/[clear].
 *
 * Degrades cleanly: if stdout is not an interactive TTY, ANSI/cursor control is unavailable, or the
 * terminal is a legacy Windows console (Mordant reports [AnsiLevel.NONE]), pinning switches off and
 * every event — pinned or not — simply scrolls, with would-be-pinned lines tagged `pin` so they
 * stay visible. Set `AFIELD_PIN_DISABLE=1` to force this fallback.
 *
 * Known limitation: raw `System.out.println`/stderr writes elsewhere in the app bypass this sink's
 * lock and can, very occasionally, interleave with a header repaint. They still scroll below the
 * header; the visual glitch is transient. Routing all app output through the dispatcher avoids it.
 */

/**
 * A channel to pin, at "channel picking" time (see [PinnedConsole.install]/[PinnedConsole.pin]).
 * [heatmap] colors the channel's rolling numeric values (`foo=1.2ms`) blue-through-red by whether
 * they fell or rose since the channel's last line; it's on by default and opt-out per channel.
 */
data class PinnedChannel(val tag: String, val heatmap: Boolean = true)

object PinnedConsole {

    @Volatile
    private var activeSink: PinnedConsoleSink? = null

    private const val STARTUP_CHANNEL = "startup"
    private const val STARTUP_COLLAPSE_MS = 4_000L

    /**
     * Channels pinned by default once startup collapses — the worst per-frame terminal scrollers.
     * `fps` = the renderer's frames-per-second line; `upload` = vertex + virtual-texture GPU uploads;
     * `perf` = the remaining `info,perf` dumps (GPU scopes, CPU spans, per-module perf, …); `net` =
     * WebSocket packet/KB rates to/from the front end (see NetStats); `compile`/`evaluate` = the Kotlin
     * compile/evaluate timers. Order matters: `fps`/`upload` precede `perf`, so those lines route to
     * their own rows (matchedPin picks the first pinned tag present in the channel) and only the
     * leftover perf lines fall to the perf row.
     */
    private val DEFAULT_PINS = listOf("fps", "upload", "perf", "net", "compile", "evaluate")

    @Volatile private var startedAtNanos: Long? = null
    private val startupFinished = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Install the pinned console for boot: pins the "startup" channel as the first (top) row, plus the
     * [DEFAULT_PINS] (fps + uploads) and anything in `AFIELD_PIN_CHANNELS`, and starts the boot
     * stopwatch. Call once at the very top of `main()`. Combined with the per-phase annotations in
     * [runAll], the startup row ticks through the boot stages and then collapses, leaving the fps/upload
     * rows pinned during normal running. Honors `AFIELD_PIN_DISABLE` (falls back to plain stdout).
     */
    fun installWithStartup() {
        val env = System.getenv("AFIELD_PIN_CHANNELS")
            ?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        startedAtNanos = System.nanoTime()
        // startup's line is boot-stage prose, not a stats line, so heatmap-coloring it would be noise.
        val channels = listOf(PinnedChannel(STARTUP_CHANNEL, heatmap = false)) +
            (DEFAULT_PINS + env).map { PinnedChannel(it) }
        install(channels.distinctBy { it.tag })
        // After the sink exists, so a stack trace thrown on the way up can already be pinned. Stderr
        // does not pass through the dispatcher, so without this it lands on a row the next repaint
        // clears, in a scroll region the terminal keeps no scrollback for. See [SessionErrorLog].
        SessionErrorLog.install()
    }

    /**
     * Mark startup complete: write a "✓ ready" line to the pinned startup row, then collapse that row
     * a few seconds later so the header tidies itself once Field is running. Idempotent.
     */
    fun finishStartup() {
        if (!startupFinished.compareAndSet(false, true)) return
        val started = startedAtNanos
        val ms = if (started != null) (System.nanoTime() - started) / 1_000_000 else -1
        lprintln("info,startup", if (ms >= 0) "✓ Field ready in $ms ms" else "✓ Field ready")
        val sink = activeSink ?: return
        thread(isDaemon = true, name = "pinned-startup-collapse") {
            try {
                Thread.sleep(STARTUP_COLLAPSE_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            sink.removePinnedTag(STARTUP_CHANNEL)
        }
    }

    /** Install by reading `AFIELD_PIN_CHANNELS` (comma/space separated tag names). No-op if unset/empty. */
    fun installFromEnv() {
        val raw = System.getenv("AFIELD_PIN_CHANNELS") ?: return
        val tags = raw.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) return
        installTags(tags)
    }

    /**
     * Install the pinned console, pinning [tags] with heatmap-coloring on by default (see
     * [PinnedChannel]). Convenience wrapper around [install] for callers that don't need
     * per-channel control.
     */
    fun installTags(tags: List<String>) = install(tags.map { PinnedChannel(it) })

    /**
     * Install the pinned console, picking channels (and per-channel heatmap on/off) via [channels].
     * Replaces [StdoutLog] in the dispatcher (preserving any other sinks, e.g. the web LogStream)
     * and registers a shutdown hook that restores the terminal. Safe to call once; a second call
     * just re-points the runtime API.
     */
    fun install(channels: List<PinnedChannel>) {
        val sink = PinnedConsoleSink(channels)
        LogDispatcher.addSink(sink)
        LogDispatcher.removeSink(StdoutLog)
        activeSink = sink
        Runtime.getRuntime().addShutdownHook(Thread({ sink.shutdown() }, "pinned-console-shutdown"))
    }

    /**
     * Start pinning [tag] at runtime (adds a row if not already pinned). Rolling-stats values in
     * the row (`foo=1.2ms`) are heatmap-colored by whether they rose or fell since last time
     * unless [heatmap] is set to false at this "channel picking" stage.
     */
    fun pin(tag: String, heatmap: Boolean = true) = activeSink?.addPinnedTag(tag, heatmap) ?: Unit

    /** Stop pinning [tag] at runtime (removes its row). */
    fun unpin(tag: String) = activeSink?.removePinnedTag(tag) ?: Unit

    /** Blank a pinned channel's current value while keeping its row. */
    fun clear(tag: String) = activeSink?.clearPinned(tag) ?: Unit

    /**
     * Register one beat of a heartbeat/spinner row named [channel]. Cheap enough to call every frame:
     * the row shows an animated spinner + beats/sec while beats keep arriving, and freezes to "idle"
     * when they stop. No-op if the console isn't installed or isn't rendering (non-TTY). Use this
     * instead of a per-frame `lprintln`/`print`.
     */
    fun beat(channel: String) = activeSink?.beat(channel) ?: Unit

    /**
     * Pin a compilation/execution error as a sticky, multi-line panel at the very *top* of the header,
     * keyed by [key] (use a stable id such as the box id so re-runs replace the same panel). Unlike the
     * channel rows, an error panel never expires on the silence timer — a broken build stays put until
     * [clearError] is called (typically on the next clean compile) or a fresh [error] for the same [key]
     * replaces it. [title] is a short one-line summary; [lines] are the detail (each clipped to width,
     * the panel body capped in height with a "+N more" tail). No-op when pinning is off (non-TTY); the
     * error still scrolls via whatever `error`-channel line the caller already logged.
     */
    fun error(key: String, title: String, lines: List<String>) =
        activeSink?.setError(key, title, lines) ?: Unit

    /** Remove the sticky error panel [key] (e.g. once the box compiles cleanly). No-op if none exists. */
    fun clearError(key: String) = activeSink?.clearError(key) ?: Unit
}

private class PinnedConsoleSink(initialChannels: List<PinnedChannel>) : LogSink {

    /**
     * A displayed pinned channel. Created only when the channel first emits (never speculative), and
     * removed after [EXPIRE_NANOS] of silence; dimmed after [DIM_NANOS]. Holds either a single latest
     * line, or — while an lblock is open on the channel — a [BlockState] rendered as a scrolling panel.
     */
    private class PinnedEntry {
        var lastNanos = 0L
        var at: java.time.Instant = java.time.Instant.EPOCH
        var channel = ""
        var dim = false
        var line: String? = null
        var heatmap: List<StatsHeatmap.Fragment>? = null // precomputed once per line; null = heatmap off
        var block: BlockState? = null
    }

    /** An open lblock on a pinned channel: a title plus the last few lines emitted inside its scope. */
    private class BlockState(val id: String, val title: String, val start: java.time.Instant) {
        val body = ArrayDeque<String>() // recent in-scope lines; capped to BLOCK_HEIGHT (oldest drop = scroll)
    }

    /**
     * A sticky, multi-line error panel pinned above everything else. Never swept by the dim/expire
     * lifecycle: it lives until [clearError]/[setError] replaces or removes it. [at] stamps when it
     * was last raised, shown on the right of the panel's header row like other pinned rows.
     */
    private class ErrorPanel(val title: String, val lines: List<String>, val at: java.time.Instant)

    /** A spinner row driven by cheap per-frame [beat] calls instead of one log line per frame. */
    private class Heartbeat(val label: String) {
        val count = java.util.concurrent.atomic.AtomicLong(0) // bumped lock-free from the hot path
        var seenCount = 0L        // last count the paint daemon observed (liveness)
        var lastChangeNanos = 0L
        var live = false
        var glyph = 0
        var rate = 0.0            // EMA of beats/sec
        var rateCount = 0L
        var rateNanos = 0L
    }

    private val terminal = Terminal()
    private val info = terminal.terminalInfo
    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val lock = ReentrantLock()

    /**
     * The watch list: tag -> heatmap-enabled, for tags eligible to be pinned. Display order follows
     * this order (LinkedHashMap preserves insertion order). Guarded by [lock].
     */
    private val pinnedTags = LinkedHashMap<String, Boolean>().apply {
        initialChannels.forEach { put(it.tag, it.heatmap) }
    }

    /** tag -> live entry. Present only for tags that have emitted recently (see [sweepEntries]). */
    private val entries = HashMap<String, PinnedEntry>()

    /** Open lblock ids -> owning entry, so END closes the right block and nested lines route to it. */
    private val openBlocks = HashMap<String, PinnedEntry>()

    /** Sticky error panels, keyed (e.g. by box id). Rendered above all other rows. Guarded by [lock]. */
    private val errorPanels = LinkedHashMap<String, ErrorPanel>()

    /** Heartbeat/spinner rows, keyed by channel. Counts are bumped lock-free; structure changes under [lock]. */
    private val heartbeats = java.util.concurrent.ConcurrentHashMap<String, Heartbeat>()
    private val heartbeatOrder = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Volatile private var dirty = false      // header needs a repaint (pins / spinner)
    @Volatile private var logDirty = false   // log viewport needs a repaint (scrollback mode only)
    private var reservedRows = -1   // header rows currently fenced off; -1 = never established
    private var cachedW = 80
    private var cachedH = 24

    private val enabled: Boolean = run {
        if (System.getenv("AFIELD_PIN_DISABLE") != null) return@run false
        // ansiLevel==NONE is the reliable "no ANSI" signal (e.g. legacy Windows conhost, piped output);
        // supportsAnsiCursor is a stricter heuristic that false-negatives on some real terminals, so we
        // don't require it — cursor/scroll-region codes track ANSI support.
        if (!info.outputInteractive || info.ansiLevel == AnsiLevel.NONE) {
            return@run false
        }
        val (w, h) = currentSize()
        if (w <= 0 || h <= 0) return@run false
        cachedW = w
        cachedH = h
        true
    }

    @Volatile private var running = enabled

    /**
     * Scrollback pager — on by default; set `AFIELD_PIN_SCROLLBACK=0` (or `false`) to disable. Keeps a
     * ring buffer of non-pinned lines and renders the log region ourselves as a pager (PageUp/PageDown/
     * arrows/Home/End + mouse wheel) instead of letting the terminal scroll it. Input is read from
     * /dev/tty via `stty` with ISIG kept, so Ctrl-C still signals normally. When disabled (or on
     * Windows, where the tty control is POSIX-only) the log region just terminal-scrolls as before.
     */
    private val scrollback: Boolean =
        enabled && System.getenv("AFIELD_PIN_SCROLLBACK").let { it != "0" && it != "false" }
    private val history = ArrayDeque<String>()   // rendered non-pinned lines (no trailing newline). Guarded by [lock].
    private var scrollUp = 0                      // logical lines the view bottom sits above the newest; 0 = following
    @Volatile private var inputActive = false     // stty tweaked + mouse tracking on (needs restoring on shutdown)
    private var savedStty: String? = null         // original `stty -g` settings, to restore verbatim

    init {
        if (enabled) {
            lock.withLock {
                // Clear the screen and fence off the top rows as a fixed header. Unlike vim/htop/less we do
                // NOT switch to the alternate screen buffer: staying in the primary buffer means that on exit
                // the final frame — pinned header + the tail of the scrolling log — stays in the terminal's
                // scrollback instead of being wiped when the app quits. shutdown() only drops the region.
                write(CLEAR_HOME)
                ensureRegion()
                dirty = true
                logDirty = true
            }
            thread(isDaemon = true, name = "pinned-console") {
                var tick = 0
                while (running) {
                    try {
                        Thread.sleep(COALESCE_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    tick++
                    frame(checkSize = tick % SIZE_CHECK_EVERY == 0)
                }
            }
            if (scrollback) startInputLoop()
        }
    }

    override fun log(event: LogEvent) {
        if (ConsoleMute.isMuted(event.channel)) return // muted channels: keep them off the console (still on LogStream)
        if (!enabled) {
            // Degraded: nothing is pinned; keep the current stdout look, but mark would-be pins.
            val tag = matchedPin(event.channel)
            if (tag != null) StdoutLog.log(event.copy(channel = "pin,${event.channel}"))
            else StdoutLog.log(event)
            return
        }
        lock.withLock {
            if (routePinned(event)) {
                dirty = true
                if (reservedRows <= 0) emitScrolling(event) // no region to draw the pin into: still show it
            } else {
                emitScrolling(event)
            }
        }
    }

    /** Route a non-pinned line either into the scrollback buffer (pager mode) or straight to the tty. */
    private fun emitScrolling(event: LogEvent) {
        val content = formatScrolling(event)
        if (scrollback && reservedRows > 0) {
            for (line in content.split('\n')) history.addLast(line)
            while (history.size > HISTORY_MAX) history.removeFirst()
            // While paused (scrolled up), keep the same lines in view as new ones arrive below.
            if (scrollUp > 0) {
                scrollUp = (scrollUp + content.count { it == '\n' } + 1).coerceAtMost(maxOf(0, history.size - 1))
            }
            logDirty = true
        } else {
            write("$content\n")
        }
    }

    /**
     * Route [event] to a pinned channel if it belongs to one — as the channel's latest line, or, while
     * an lblock is open, into that block's scrolling body. Entries are created lazily here so a channel
     * is never shown before it emits. Returns true if the event was consumed by a pinned entry.
     */
    private fun routePinned(event: LogEvent): Boolean {
        // Close an open block on its END → "✓ title (N ms)". Swallow orphan END markers either way.
        if (event.boundary == BlockBoundary.END) {
            val e = event.blockId?.let { openBlocks.remove(it) }
            if (e != null) {
                val b = e.block
                val ms = if (b != null) java.time.Duration.between(b.start, event.timestamp).toMillis() else 0
                e.line = "✓ ${b?.title ?: cleanMessage(event.message)} ($ms ms)"
                e.heatmap = null
                e.block = null
                touch(e, event)
            }
            return true
        }
        // A line emitted inside an open pinned block's scope (its indent carries the block id).
        if (event.indent.isNotEmpty()) {
            val e = openBlockForIndent(event.indent)
            if (e != null) {
                appendBlockLine(e, cleanMessage(event.message))
                touch(e, event)
                return true
            }
        }
        val tag = matchedPin(event.channel) ?: return false
        val e = entries.getOrPut(tag) { PinnedEntry() }
        touch(e, event)
        if (event.boundary == BlockBoundary.BEGIN) {
            e.block = BlockState(event.blockId ?: "", cleanMessage(event.message), event.timestamp)
            e.line = null
            e.heatmap = null
            event.blockId?.let { openBlocks[it] = e }
        } else if (e.block != null) {
            appendBlockLine(e, cleanMessage(event.message)) // a line on the block's own channel
        } else {
            val cleaned = cleanMessage(event.message)
            e.line = cleaned
            // Computed once per real update (not per repaint) so re-renders don't diff a value
            // against itself and flatten every color to grey.
            e.heatmap = if (pinnedTags[tag] == true) StatsHeatmap.fragments(tag, cleaned) else null
        }
        return true
    }

    private fun touch(e: PinnedEntry, event: LogEvent) {
        e.lastNanos = System.nanoTime()
        e.at = event.timestamp
        e.channel = event.channel
        e.dim = false
    }

    private fun appendBlockLine(e: PinnedEntry, line: String) {
        val b = e.block ?: return
        b.body.addLast(line)
        while (b.body.size > BLOCK_HEIGHT) b.body.removeFirst() // keep the last BLOCK_HEIGHT (scroll)
    }

    /** Find an open block whose id appears as a token in [indent] (lblock pushes "<id>\t" per level). */
    private fun openBlockForIndent(indent: String): PinnedEntry? {
        if (openBlocks.isEmpty()) return null
        val tokens = indent.split('\t')
        for ((id, e) in openBlocks) if (id.isNotEmpty() && id in tokens) return e
        return null
    }

    /** Sweep entries for the dim/expire lifecycle. Must hold [lock]; sets [dirty] on any visible change. */
    private fun sweepEntries() {
        if (entries.isEmpty()) return
        val now = System.nanoTime()
        val it = entries.entries.iterator()
        var changed = false
        while (it.hasNext()) {
            val (tag, e) = it.next()
            val age = now - e.lastNanos
            if (age > EXPIRE_NANOS) {
                e.block?.id?.let { openBlocks.remove(it) }
                StatsHeatmap.reset(tag) // don't diff a future reuse of this channel against a stale value
                it.remove()
                changed = true
            } else {
                val d = age > DIM_NANOS
                if (d != e.dim) { e.dim = d; changed = true }
            }
        }
        if (changed) dirty = true
    }

    /** Strip lblock's trailing " - '&lt;uuid&gt;'" block-id marker; a no-op for ordinary messages. */
    private fun cleanMessage(message: String): String = message.replace(BLOCK_ID_SUFFIX, "").trim()

    // --- runtime API (called via PinnedConsole) ---

    fun addPinnedTag(tag: String, heatmap: Boolean = true) = lock.withLock {
        pinnedTags[tag] = heatmap // shows once it emits
    }

    fun removePinnedTag(tag: String) = lock.withLock {
        pinnedTags.remove(tag)
        entries.remove(tag)?.block?.id?.let { openBlocks.remove(it) }
        StatsHeatmap.reset(tag)
        dirty = true
    }

    fun clearPinned(tag: String) = lock.withLock { if (entries.remove(tag) != null) dirty = true }

    /**
     * Pin/replace the sticky error panel [key] with [title] + [lines]. Shown above every other header
     * row and never swept by the dim/expire lifecycle — it persists until [clearError] or the next
     * [setError] for the same key. Degraded (non-TTY) console keeps nothing pinned; the caller's
     * scrolling error line is the record there.
     */
    fun setError(key: String, title: String, lines: List<String>) {
        if (!enabled) return
        lock.withLock {
            errorPanels[key] = ErrorPanel(title, lines.ifEmpty { listOf("(no detail)") }, java.time.Instant.now())
            dirty = true // frame() grows the region to fit before repainting (headerHeight != reservedRows)
        }
    }

    /** Remove the sticky error panel [key]. The freed rows fall back into the scrolling region. */
    fun clearError(key: String) {
        if (!enabled) return
        lock.withLock { if (errorPanels.remove(key) != null) dirty = true }
    }

    /** Cheap per-frame pulse: bumps [channel]'s spinner row (created on first call). Lock-free steady state. */
    fun beat(channel: String) {
        if (!enabled) return
        val hb = heartbeats[channel] ?: lock.withLock {
            heartbeats.getOrPut(channel) {
                heartbeatOrder.add(channel)
                dirty = true
                Heartbeat(channel)
            }
        }
        hb.count.incrementAndGet()
    }

    fun shutdown() {
        running = false
        if (!enabled) return
        if (inputActive) {
            runCatching { savedStty?.let { setStty(it) } ?: setStty("sane") } // restore cooked tty mode
        }
        lock.withLock {
            // Disable mouse tracking and drop the scroll region. We never entered the alternate screen, so
            // there is nothing to "leave" and nothing gets wiped: the final frame stays on screen and in
            // scrollback. Park the cursor on a fresh line below it so the returning shell prompt doesn't
            // land on top of the last log line.
            write((if (inputActive) MOUSE_OFF else "") + "${CSI}r" + "$CSI${cachedH};1H\n")
        }
    }

    // --- scrollback pager input (only active when AFIELD_PIN_SCROLLBACK=1) ---
    //
    // Mordant's raw-mode/input is dead in this JVM (same native-integration gap that breaks its size
    // detection), so we drive the tty ourselves: `stty -echo -icanon min 1 time 0` for immediate,
    // unechoed keys — crucially keeping ISIG, so Ctrl-C stays a real signal and the normal shutdown
    // path still works — plus SGR mouse reporting for the wheel. POSIX only; a no-op on Windows.
    //
    // Read keystrokes from /dev/tty, NOT System.in: field.sh backgrounds the backend (`java … &`) and
    // POSIX shells redirect an async command's stdin to /dev/null, so System.in is EOF. /dev/tty is the
    // controlling terminal regardless of how fd 0 was wired (same reason we size via `stty < /dev/tty`).

    private fun startInputLoop() {
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        val input = try { java.io.FileInputStream("/dev/tty") } catch (_: Throwable) { null } ?: return
        savedStty = captureStty()
        if (!setStty("-echo", "-icanon", "min", "1", "time", "0")) {
            runCatching { input.close() }
            return
        }
        write(MOUSE_ON)
        inputActive = true
        thread(isDaemon = true, name = "pinned-console-input") {
            input.use { inp ->
                val buf = ByteArray(64)
                while (running) {
                    val n = try { inp.read(buf) } catch (_: Throwable) { -1 }
                    if (n < 0) break
                    val chunk = String(buf, 0, n, Charsets.ISO_8859_1) // 1 byte -> 1 char, escapes intact
                    lock.withLock { parseInput(chunk) }
                }
            }
        }
    }

    /** Scan a chunk of raw terminal input for scroll keys and SGR wheel events. */
    private fun parseInput(s: String) {
        val page = (cachedH - reservedRows - 1).coerceAtLeast(1)
        val esc = '\u001B'
        var i = 0
        while (i < s.length) {
            if (s[i] == esc) {
                val rest = s.substring(i)
                when {
                    rest.startsWith("$esc[5~") -> { scrollBy(page); i += 4 }            // PageUp
                    rest.startsWith("$esc[6~") -> { scrollBy(-page); i += 4 }           // PageDown
                    rest.startsWith("$esc[A") -> { scrollBy(1); i += 3 }                // Up
                    rest.startsWith("$esc[B") -> { scrollBy(-1); i += 3 }               // Down
                    rest.startsWith("$esc[1~") -> { scrollTop(); i += 4 }               // Home
                    rest.startsWith("$esc[H") || rest.startsWith("${esc}OH") -> { scrollTop(); i += 3 }
                    rest.startsWith("$esc[4~") -> { scrollBottom(); i += 4 }            // End
                    rest.startsWith("$esc[F") || rest.startsWith("${esc}OF") -> { scrollBottom(); i += 3 }
                    rest.startsWith("$esc[<") -> i += parseMouse(rest)                   // SGR mouse
                    else -> i += 1                                                       // unknown escape: skip ESC
                }
            } else {
                when (s[i]) {
                    'q', 'Q' -> quit()
                    'k' -> scrollBy(1)
                    'j' -> scrollBy(-1)
                    'b' -> scrollBy(page)
                    ' ' -> scrollBy(-page)
                    'g' -> scrollTop()
                    'G' -> scrollBottom()
                }
                i += 1
            }
        }
    }

    /** Parse an SGR mouse report `ESC[<b;x;y(M|m)` starting at [rest]; returns bytes consumed. */
    private fun parseMouse(rest: String): Int {
        val term = rest.indexOfFirst { it == 'M' || it == 'm' }
        if (term < 0) return rest.length // incomplete; drop it
        val b = rest.substring(3, term).substringBefore(';').toIntOrNull() // after "ESC[<"
        when (b) {
            64 -> scrollBy(WHEEL_LINES)   // wheel up
            65 -> scrollBy(-WHEEL_LINES)  // wheel down
        }
        return term + 1
    }

    private fun scrollBy(delta: Int) {
        val maxUp = maxOf(0, history.size - 1)
        val next = (scrollUp + delta).coerceIn(0, maxUp)
        if (next != scrollUp) { scrollUp = next; logDirty = true; dirty = true } // dirty: refresh the rule's indicator
    }

    private fun scrollTop() = scrollBy(history.size)
    private fun scrollBottom() { if (scrollUp != 0) { scrollUp = 0; logDirty = true; dirty = true } }

    /** ISIG is kept on, so Ctrl-C already signals; 'q' quits via a normal exit (runs the shutdown hook). */
    private fun quit() {
        thread(isDaemon = true, name = "pinned-console-quit") { exitProcess(130) }
    }

    private fun captureStty(): String? = try {
        val p = ProcessBuilder("stty", "-g")
            .redirectInput(java.io.File("/dev/tty"))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readLine() }?.trim()
        p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        out?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private fun setStty(vararg args: String): Boolean = try {
        val p = ProcessBuilder(listOf("stty", *args))
            .redirectInput(java.io.File("/dev/tty"))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Throwable) {
        false
    }

    // --- painting ---

    /** One coalesced daemon step: refresh size occasionally, repaint the header if anything changed. */
    private fun frame(checkSize: Boolean) {
        if (!running) return
        lock.withLock {
            if (checkSize) {
                val (w, h) = currentSize()
                if (w > 0 && h > 0 && (w != cachedW || h != cachedH)) {
                    cachedW = w
                    cachedH = h
                    ensureRegion()
                    dirty = true
                    logDirty = true
                }
            }
            sweepEntries()
            animateHeartbeats()
            if (headerHeight() != reservedRows) {
                ensureRegion()
                dirty = true
                logDirty = true
            }
            if (scrollback) {
                // We own the whole screen: repaint header rows on `dirty`, the log viewport on `logDirty`,
                // in one write, then park the cursor at the bottom so stray output lands harmlessly there.
                if (dirty || logDirty) {
                    val sb = StringBuilder()
                    if (dirty && reservedRows > 0) appendHeaderRows(sb)
                    if (logDirty && reservedRows > 0) appendLogRows(sb)
                    sb.append(CSI).append(cachedH).append(";1H")
                    write(sb)
                    dirty = false
                    logDirty = false
                }
            } else if (dirty) {
                dirty = false
                if (reservedRows > 0) paintHeader()
            }
        }
    }

    /** Advance spinners, refresh rates, and mark dirty while any heartbeat is live (must hold [lock]). */
    private fun animateHeartbeats() {
        if (heartbeats.isEmpty()) return
        val now = System.nanoTime()
        var anyLive = false
        for (hb in heartbeats.values) {
            val c = hb.count.get()
            if (c != hb.seenCount) { hb.seenCount = c; hb.lastChangeNanos = now }
            if (hb.rateNanos != 0L && now > hb.rateNanos) {
                val inst = (c - hb.rateCount) * 1e9 / (now - hb.rateNanos)
                hb.rate = if (hb.rate <= 0.0) inst else hb.rate * 0.7 + inst * 0.3
            }
            hb.rateCount = c
            hb.rateNanos = now
            val live = now - hb.lastChangeNanos < HEARTBEAT_IDLE_NANOS
            if (live) { anyLive = true; hb.glyph = (hb.glyph + 1) % SPINNER.length }
            if (live != hb.live) { hb.live = live; dirty = true } // repaint on the live<->idle transition
        }
        if (anyLive) dirty = true // keep repainting to animate the spinner
    }

    /** Rows the header wants: error panels + each live entry (blocks are multi-line) + heartbeats + rule. */
    private fun headerHeight(): Int {
        var rows = 0
        for (panel in errorPanels.values) rows += panelHeight(panel)
        for (tag in pinnedTags.keys) rows += entries[tag]?.let { entryHeight(it) } ?: 0
        rows += heartbeatOrder.size
        if (rows == 0) return 0
        val want = rows + 1
        if (errorPanels.isEmpty()) {
            // Unchanged behavior: if the header can't fit while leaving MIN_SCROLL_ROWS free, pin nothing.
            return if (cachedH >= want + MIN_SCROLL_ROWS) want else 0
        }
        // An error is pinned: keep it visible even on a short terminal by clamping the header height
        // (appendHeaderRows renders error panels first and drops the least-important tail rows) while
        // still preserving MIN_SCROLL_ROWS of scrolling output.
        val maxHeader = cachedH - MIN_SCROLL_ROWS
        if (maxHeader < 2) return 0 // no room for even one content row plus the rule
        return want.coerceAtMost(maxHeader)
    }

    /** A single-line channel is 1 row; an open block is a header + up to BLOCK_HEIGHT body rows. */
    private fun entryHeight(e: PinnedEntry): Int {
        val b = e.block ?: return 1
        return 1 + minOf(BLOCK_HEIGHT, b.body.size)
    }

    /** An error panel is a title row + its detail lines, capped at [ERROR_BODY_MAX] body rows. */
    private fun panelHeight(panel: ErrorPanel): Int = 1 + minOf(panel.lines.size, ERROR_BODY_MAX)

    /** (Re)establish the DECSTBM scroll region so the top [headerHeight] rows never scroll. */
    private fun ensureRegion() {
        val needed = headerHeight()
        val sb = StringBuilder()
        // Make space for freshly-reserved rows so we don't paint over live output.
        val grow = when {
            reservedRows < 0 -> needed
            needed > reservedRows -> needed - reservedRows
            else -> 0
        }
        repeat(grow) { sb.append('\n') }
        if (needed > 0) {
            sb.append(CSI).append(needed + 1).append(';').append(cachedH).append('r') // region below header
            sb.append(CSI).append(needed + 1).append(";1H")                            // cursor into region
        } else {
            sb.append(CSI).append('r') // whole screen scrolls
        }
        write(sb)
        reservedRows = needed
    }

    /** Non-scrollback repaint: save/restore the cursor around the header so terminal scrolling is undisturbed. */
    private fun paintHeader() {
        val sb = StringBuilder()
        sb.append(SAVE)
        appendHeaderRows(sb)
        sb.append(RESTORE)
        write(sb)
    }

    /** Emit the header (error panels, then pinned entries, then heartbeats, then the rule) into rows 1.. */
    private fun appendHeaderRows(sb: StringBuilder) {
        val w = cachedW
        // Build the ordered header content as plain row strings — error panels first so a broken build
        // is never the row that gets dropped when space is tight — then pinned stat rows, then spinners.
        val content = ArrayList<String>()
        for (panel in errorPanels.values) content += renderErrorPanelRows(panel, w)
        for (tag in pinnedTags.keys) {
            val e = entries[tag] ?: continue
            content += renderEntryRows(tag, e, w)
        }
        for (key in heartbeatOrder) {
            val hb = heartbeats[key] ?: continue
            content += renderHeartbeatRow(hb, w)
        }
        // reservedRows is content + 1 (the rule), clamped in headerHeight(); paint what fits, drop the
        // tail, and blank any body row left over if content shrank since the region was last sized.
        val bodyRows = (reservedRows - 1).coerceAtLeast(0)
        var row = 1
        while (row <= bodyRows) {
            sb.append(CSI).append(row).append(";1H").append(CLEAR_LINE)
            if (row - 1 < content.size) sb.append(content[row - 1])
            row++
        }
        sb.append(CSI).append(row).append(";1H").append(CLEAR_LINE)
        sb.append(rule(w))
    }

    /** Render one pinned entry as row string(s): a single line, or a block header + indented body. */
    private fun renderEntryRows(tag: String, e: PinnedEntry, w: Int): List<String> {
        val head = if (e.dim) DIM else colorFor(levelOf(e.channel))
        val label = tag.take(LABEL_WIDTH).padEnd(LABEL_WIDTH)
        val time = timeFmt.format(e.at)
        val block = e.block
        val out = ArrayList<String>()
        if (block != null) {
            out += styledRow(head, "▸ $label ${block.title}", time, w)
            val bodyStyle = if (e.dim) DIM else TextColors.gray
            for (line in block.body) out += styledRow(bodyStyle, "    $line", "", w) // 4-space indent
        } else {
            val prefix = StatsHeatmap.Fragment("$BULLET $label ", null)
            val lineText = (e.line ?: "").replace('\n', ' ')
            // Suppress heatmap coloring once dimmed (idle) so the whole row reads uniformly stale.
            val body = if (!e.dim) e.heatmap else null
            out += styledFragments(head, listOf(prefix) + (body ?: listOf(StatsHeatmap.Fragment(lineText, null))), time, w)
        }
        return out
    }

    /** Render a sticky error panel as row strings: a red title row + up to [ERROR_BODY_MAX] detail rows. */
    private fun renderErrorPanelRows(panel: ErrorPanel, w: Int): List<String> {
        val out = ArrayList<String>()
        out += styledRow(TextColors.brightRed, "✗ ${panel.title}", timeFmt.format(panel.at), w)
        val truncated = panel.lines.size > ERROR_BODY_MAX
        val shown = if (truncated) panel.lines.take(ERROR_BODY_MAX - 1) else panel.lines
        for (line in shown) out += styledRow(TextColors.red, "    ${line.replace('\n', ' ')}", "", w)
        if (truncated) out += styledRow(DIM, "    … (+${panel.lines.size - shown.size} more)", "", w)
        return out
    }

    /** Clip [leftPlain] to fit beside [rightPlain] within [w], style the left with [style], dim the right. */
    private fun styledRow(style: TextStyle, leftPlain: String, rightPlain: String, w: Int): String {
        val budget = (w - rightPlain.length).coerceAtLeast(1)
        val left = if (leftPlain.length > budget) leftPlain.take((budget - 1).coerceAtLeast(0)) + "…"
        else leftPlain.padEnd(budget)
        return style(left) + DIM(rightPlain)
    }

    /**
     * Like [styledRow], but the left side is a sequence of [fragments] that may each carry their own
     * style (e.g. a heatmap-colored value) instead of one uniform [style]. Clipping/padding happens on
     * the plain text *before* any styling is applied, so fragments are always independently, fully
     * styled (open+reset) and simply concatenated — no reliance on nested-reset behavior in terminals.
     */
    private fun styledFragments(
        style: TextStyle,
        fragments: List<StatsHeatmap.Fragment>,
        rightPlain: String,
        w: Int
    ): String {
        val budget = (w - rightPlain.length).coerceAtLeast(1)
        val totalLen = fragments.sumOf { it.text.length }
        val clipped = if (totalLen > budget) {
            var remaining = (budget - 1).coerceAtLeast(0)
            val truncated = mutableListOf<StatsHeatmap.Fragment>()
            for (f in fragments) {
                if (remaining <= 0) break
                if (f.text.length <= remaining) {
                    truncated += f
                    remaining -= f.text.length
                } else {
                    truncated += StatsHeatmap.Fragment(f.text.take(remaining), f.style)
                    remaining = 0
                }
            }
            truncated += StatsHeatmap.Fragment("…", null)
            truncated
        } else {
            fragments + StatsHeatmap.Fragment(" ".repeat(budget - totalLen), null)
        }
        val left = clipped.joinToString("") { (it.style ?: style)(it.text) }
        return left + DIM(rightPlain)
    }

    /** Scrollback pager: render the visible window of [history] into the log region (rows below the header). */
    private fun appendLogRows(sb: StringBuilder) {
        val logTop = reservedRows + 1
        val logRows = cachedH - reservedRows
        if (logRows <= 0) return
        val size = history.size
        val maxUp = maxOf(0, size - 1)
        if (scrollUp > maxUp) scrollUp = maxUp
        var idx = size - 1 - scrollUp          // index of the bottom visible line
        var row = logTop + logRows - 1
        while (row >= logTop) {                // paint bottom-up; blank rows above the oldest line
            sb.append(CSI).append(row).append(";1H").append(CLEAR_LINE)
            if (idx in 0 until size) sb.append(clipVisible(history[idx], cachedW))
            row--
            idx--
        }
    }

    /** Clip a (possibly ANSI-styled) line to [width] visible columns, passing escape sequences through. */
    private fun clipVisible(s: String, width: Int): String {
        if (width <= 0) return ""
        val sb = StringBuilder()
        var visible = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\u001B') {
                val start = i
                i++
                if (i < s.length && s[i] == '[') {
                    i++
                    while (i < s.length && s[i] !in '@'..'~') i++
                    if (i < s.length) i++
                } else if (i < s.length) {
                    i++
                }
                sb.append(s, start, i)
            } else {
                if (visible >= width) break
                sb.append(c)
                visible++
                i++
            }
        }
        sb.append(RESET) // don't let a truncated style bleed past the row
        return sb.toString()
    }

    private fun renderHeartbeatRow(hb: Heartbeat, w: Int): String {
        val spin = if (hb.live) SPINNER[hb.glyph] else IDLE_GLYPH
        val label = hb.label.take(LABEL_WIDTH).padEnd(LABEL_WIDTH)
        val right = if (hb.live) " ${"%.0f".format(hb.rate)}/s" else " idle"
        val leftBudget = (w - right.length).coerceAtLeast(1)
        var left = "$spin $label ${"%,d".format(hb.count.get())} frames"
        left = if (left.length > leftBudget) {
            left.take((leftBudget - 1).coerceAtLeast(0)) + "…"
        } else {
            left.padEnd(leftBudget)
        }
        val color = if (hb.live) TextColors.brightGreen else TextColors.gray
        return color(left) + DIM(right)
    }

    private fun rule(w: Int): String {
        if (w <= 0) return ""
        if (!scrollback) return DIM("─".repeat(w))
        // Show the pager state on the rule: "following" the tail, or how many newer lines are below.
        val label = if (scrollUp <= 0) " following " else " ▼ $scrollUp newer · End to follow "
        val dashes = (w - label.length).coerceAtLeast(0)
        val plain = ("─".repeat(dashes) + label).take(w) // clip plain text, then style, so width is exact
        return DIM(plain)
    }

    // --- helpers ---

    /**
     * Terminal size (cols, rows). Mordant's own detection returns a bogus 79x24 fallback in this JVM,
     * so on POSIX we ask the controlling terminal directly with `stty size < /dev/tty` — which works
     * regardless of how stdio was wired and reflects live resizes. Falls back to Mordant otherwise.
     */
    private fun currentSize(): Pair<Int, Int> {
        queryTtySize()?.let { return it }
        val s = terminal.updateSize()
        return s.width to s.height
    }

    private fun queryTtySize(): Pair<Int, Int>? {
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) return null
        return try {
            val proc = ProcessBuilder("stty", "size")
                .redirectInput(java.io.File("/dev/tty"))            // read the controlling terminal
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val line = proc.inputStream.bufferedReader().use { it.readLine() }?.trim()
            proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            val parts = line?.split(Regex("\\s+"))
            if (parts != null && parts.size >= 2) {
                val rows = parts[0].toIntOrNull()
                val cols = parts[1].toIntOrNull()
                if (rows != null && cols != null && rows > 0 && cols > 0) cols to rows else null
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** First pinned tag (in display order) that appears as a token in [channel], or null. */
    private fun matchedPin(channel: String): String? {
        val tokens = channel.split(',').mapTo(HashSet(), String::trim)
        return lock.withLock { pinnedTags.keys.firstOrNull { it in tokens } }
    }

    private fun levelOf(channel: String): String? {
        val tokens = channel.split(',').map { it.trim() }
        return LEVELS.firstOrNull { it in tokens }
    }

    private fun colorFor(level: String?): TextStyle = when (level) {
        "error" -> TextColors.brightRed
        "warning", "warn" -> TextColors.brightYellow
        "info" -> TextColors.brightCyan
        "debug" -> TextColors.gray
        "trace" -> TextColors.gray
        else -> TextColors.white
    }

    /** Reproduce [StdoutLog]'s line format (so scrolling output is unchanged) and terminate it. */
    private fun formatScrolling(event: LogEvent): String {
        val channelPart = if (event.channel.isNotBlank()) "$DARK_GRAY$ITALIC[${event.channel}]$RESET " else ""
        val marker = when (event.boundary) {
            BlockBoundary.BEGIN -> ">|> "
            BlockBoundary.END -> "<|< "
            null -> ""
        }
        return channelPart + event.indent + marker + event.message
    }

    /** All output goes through Mordant's terminal so Windows VT processing is enabled. */
    private fun write(text: CharSequence) {
        terminal.rawPrint(text.toString())
    }

    private companion object {
        const val CSI = "\u001B["
        const val SAVE = "\u001B7"          // DECSC – save cursor
        const val RESTORE = "\u001B8"       // DECRC – restore cursor
        const val CLEAR_LINE = "\u001B[2K"
        const val DARK_GRAY = "\u001B[90m"  // matches StdoutLog
        const val ITALIC = "\u001B[3m"
        const val RESET = "\u001B[0m"
        const val BULLET = "●"
        const val CLEAR_HOME = "\u001B[2J\u001B[H"

        const val MOUSE_ON = "$CSI?1000h$CSI?1006h"   // SGR mouse reporting (for the scroll wheel)
        const val MOUSE_OFF = "$CSI?1000l$CSI?1006l"

        const val LABEL_WIDTH = 12
        const val MIN_SCROLL_ROWS = 3       // keep at least this many scrolling rows, else no region
        const val HISTORY_MAX = 10_000      // scrollback ring-buffer capacity (logical lines)
        const val WHEEL_LINES = 3           // lines scrolled per mouse-wheel notch
        const val BLOCK_HEIGHT = 5          // max body rows shown for an open pinned lblock (scrolls beyond)
        const val ERROR_BODY_MAX = 8        // max detail rows shown for a pinned error panel (rest -> "+N more")
        const val DIM_NANOS = 10_000_000_000L    // no output for 10s -> dim the entry
        const val EXPIRE_NANOS = 20_000_000_000L // no output for 20s -> remove the entry's row
        const val SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"    // braille spinner frames
        const val IDLE_GLYPH = '·'
        const val HEARTBEAT_IDLE_NANOS = 1_500_000_000L  // no beat for 1.5s ⇒ idle (spinner stops)
        const val COALESCE_MS = 50L
        const val SIZE_CHECK_EVERY = 20     // ~ once per second at COALESCE_MS

        val DIM = TextColors.gray
        val LEVELS = listOf("error", "warning", "warn", "info", "debug", "trace")
        val BLOCK_ID_SUFFIX = Regex("""\s*-?\s*'[0-9a-fA-F-]{36}'\s*$""")
    }
}
