package afield.logger

import afield.ai.core.BlockBoundary
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.StdoutLog
import com.github.ajalt.mordant.input.InputEvent
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawModeOrNull
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
import kotlin.time.Duration.Companion.milliseconds

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
object PinnedConsole {

    @Volatile
    private var activeSink: PinnedConsoleSink? = null

    private const val STARTUP_CHANNEL = "startup"
    private const val STARTUP_COLLAPSE_MS = 4_000L

    /**
     * Channels pinned by default once startup collapses — the worst per-frame terminal scrollers.
     * `fps` = the renderer's frames-per-second line; `upload` = vertex + virtual-texture GPU uploads;
     * `perf` = the remaining `info,perf` dumps (GPU scopes, CPU spans, per-module perf, …). Order
     * matters: `fps`/`upload` precede `perf`, so those lines route to their own rows (matchedPin picks
     * the first pinned tag present in the channel) and only the leftover perf lines fall to the perf row.
     */
    private val DEFAULT_PINS = listOf("fps", "upload", "perf")

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
        install((listOf(STARTUP_CHANNEL) + DEFAULT_PINS + env).distinct())
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
        install(tags)
    }

    /**
     * Install the pinned console, pinning [pinnedTags]. Replaces [StdoutLog] in the dispatcher
     * (preserving any other sinks, e.g. the web LogStream) and registers a shutdown hook that
     * restores the terminal. Safe to call once; a second call just re-points the runtime API.
     */
    fun install(pinnedTags: List<String>) {
        val sink = PinnedConsoleSink(pinnedTags)
        LogDispatcher.addSink(sink)
        LogDispatcher.removeSink(StdoutLog)
        activeSink = sink
        Runtime.getRuntime().addShutdownHook(Thread({ sink.shutdown() }, "pinned-console-shutdown"))
    }

    /** Start pinning [tag] at runtime (adds a row if not already pinned). */
    fun pin(tag: String) = activeSink?.addPinnedTag(tag) ?: Unit

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
}

private class PinnedConsoleSink(initialTags: List<String>) : LogSink {

    private class Pinned(val message: String, val channel: String, val timestamp: java.time.Instant)
    private class OpenBlock(val text: String, val start: java.time.Instant)

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

    /** pinned tag -> latest value; iteration order = display order. Guarded by [lock]. */
    private val pinnedTags = LinkedHashSet(initialTags)
    private val values = HashMap<String, Pinned>()

    /** Open lblock BEGINs on pinned channels, keyed by blockId, so END can show elapsed time. Guarded by [lock]. */
    private val openBlocks = HashMap<String, OpenBlock>()

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
     * Opt-in scrollback (`AFIELD_PIN_SCROLLBACK=1`): keep a ring buffer of non-pinned lines and render
     * the region ourselves as a pager (PageUp/PageDown/arrows/Home/End + mouse wheel) instead of letting
     * the terminal scroll it. Requires raw-mode input, which disables the tty's Ctrl-C signal — so we
     * catch Ctrl-C/q as keys and exit. Off by default: the log region just terminal-scrolls as before.
     */
    private val scrollback: Boolean =
        enabled && System.getenv("AFIELD_PIN_SCROLLBACK").let { it == "1" || it == "true" }
    private val history = ArrayDeque<String>()   // rendered non-pinned lines (no trailing newline). Guarded by [lock].
    private var scrollUp = 0                      // logical lines the view bottom sits above the newest; 0 = following
    private var rawScope: RawModeScope? = null

    init {
        if (enabled) {
            lock.withLock {
                // Switch to the alternate screen buffer (saving the user's current terminal) and clear it,
                // like vim/htop/less. shutdown() switches back, restoring the terminal to its prior state.
                write("$ENTER_ALT$CLEAR_HOME")
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
        val tag = matchedPin(event.channel)
        if (!enabled) {
            // Degraded: nothing is pinned; keep the current stdout look, but mark would-be pins.
            if (tag != null) StdoutLog.log(event.copy(channel = "pin,${event.channel}"))
            else StdoutLog.log(event)
            return
        }
        lock.withLock {
            if (tag != null) {
                updatePinned(tag, event)
                dirty = true
                if (reservedRows <= 0) emitScrolling(event) // no room for a region: still show it
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
     * Fold one event into a pinned channel's single row. [lblock] emits a BEGIN then an END event
     * (each carrying a [LogEvent.blockId]): we show "▸ msg" while the block runs and "✓ msg (N ms)"
     * when it ends, stripping the internal block-id suffix. A plain lprintln line just shows its
     * (cleaned) message. Nested lblocks/lines simply overwrite the row with the most recent activity.
     */
    private fun updatePinned(tag: String, event: LogEvent) {
        when (event.boundary) {
            BlockBoundary.BEGIN -> {
                val text = cleanMessage(event.message)
                event.blockId?.let { openBlocks[it] = OpenBlock(text, event.timestamp) }
                values[tag] = Pinned("▸ $text", event.channel, event.timestamp)
            }
            BlockBoundary.END -> {
                val begun = event.blockId?.let { openBlocks.remove(it) }
                if (begun != null) {
                    val ms = java.time.Duration.between(begun.start, event.timestamp).toMillis()
                    values[tag] = Pinned("✓ ${begun.text} ($ms ms)", event.channel, event.timestamp)
                }
                // If we never saw the BEGIN (pinned mid-block), ignore the bare "- 'uuid'" end marker.
            }
            null -> values[tag] = Pinned(cleanMessage(event.message), event.channel, event.timestamp)
        }
    }

    /** Strip lblock's trailing " - '&lt;uuid&gt;'" block-id marker; a no-op for ordinary messages. */
    private fun cleanMessage(message: String): String = message.replace(BLOCK_ID_SUFFIX, "").trim()

    // --- runtime API (called via PinnedConsole) ---

    fun addPinnedTag(tag: String) = lock.withLock { if (pinnedTags.add(tag)) dirty = true }

    fun removePinnedTag(tag: String) = lock.withLock {
        if (pinnedTags.remove(tag)) { values.remove(tag); dirty = true }
    }

    fun clearPinned(tag: String) = lock.withLock { if (values.remove(tag) != null) dirty = true }

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
        runCatching { rawScope?.close() } // restore cooked tty mode + disable mouse tracking (scrollback)
        lock.withLock {
            // Drop the scroll region, then leave the alternate screen — restoring the terminal to
            // exactly what it showed before Field started.
            write("${CSI}r$LEAVE_ALT")
        }
    }

    // --- scrollback pager input (only active when AFIELD_PIN_SCROLLBACK=1) ---

    private fun startInputLoop() {
        thread(isDaemon = true, name = "pinned-console-input") {
            val scope = try {
                terminal.enterRawModeOrNull(MouseTracking.Normal)
            } catch (_: Throwable) {
                null
            } ?: return@thread // raw mode unavailable → no scrolling, but the follow view still works
            rawScope = scope
            try {
                while (running) {
                    val ev = try {
                        scope.readEventOrNull(200.milliseconds)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    lock.withLock { handleInput(ev) }
                }
            } finally {
                runCatching { scope.close() }
            }
        }
    }

    private fun handleInput(ev: InputEvent) {
        val logRows = (cachedH - reservedRows).coerceAtLeast(1)
        val maxUp = maxOf(0, history.size - 1)
        val before = scrollUp
        when (ev) {
            is KeyboardEvent -> {
                if (ev.ctrl && ev.key.equals("c", ignoreCase = true)) return quit()
                when (ev.key) {
                    "q", "Q" -> return quit()
                    "PageUp" -> scrollUp += logRows - 1
                    "PageDown" -> scrollUp -= logRows - 1
                    "ArrowUp", "k" -> scrollUp += 1
                    "ArrowDown", "j" -> scrollUp -= 1
                    "Home", "g" -> scrollUp = maxUp
                    "End", "G" -> scrollUp = 0
                    else -> return
                }
            }
            is MouseEvent -> when {
                ev.wheelUp -> scrollUp += WHEEL_LINES
                ev.wheelDown -> scrollUp -= WHEEL_LINES
                else -> return
            }
            else -> return
        }
        scrollUp = scrollUp.coerceIn(0, maxUp)
        if (scrollUp != before) logDirty = true
    }

    /** Raw mode disables the tty's Ctrl-C signal, so mimic quit ourselves; exitProcess runs the shutdown hook. */
    private fun quit() {
        thread(isDaemon = true, name = "pinned-console-quit") { exitProcess(130) }
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

    /** Rows the header wants: one per pinned tag + one per heartbeat + a separator rule, if tall enough. */
    private fun headerHeight(): Int {
        val rows = pinnedTags.size + heartbeatOrder.size
        if (rows == 0) return 0
        val want = rows + 1
        return if (cachedH >= want + MIN_SCROLL_ROWS) want else 0
    }

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

    /** Emit the pinned rows + heartbeat rows + rule into rows 1..reservedRows (absolute positioning). */
    private fun appendHeaderRows(sb: StringBuilder) {
        val w = cachedW
        var row = 1
        for (tag in pinnedTags) {
            sb.append(CSI).append(row).append(";1H").append(CLEAR_LINE)
            sb.append(renderPinRow(tag, w))
            row++
        }
        for (key in heartbeatOrder) {
            val hb = heartbeats[key] ?: continue
            sb.append(CSI).append(row).append(";1H").append(CLEAR_LINE)
            sb.append(renderHeartbeatRow(hb, w))
            row++
        }
        sb.append(CSI).append(row).append(";1H").append(CLEAR_LINE)
        sb.append(rule(w))
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

    private fun renderPinRow(tag: String, w: Int): String {
        val v = values[tag]
        val right = if (v == null) "" else " ${timeFmt.format(v.timestamp)}"
        val leftBudget = (w - right.length).coerceAtLeast(1)
        val label = tag.take(LABEL_WIDTH).padEnd(LABEL_WIDTH)
        val msg = (v?.message ?: "—").replace('\n', ' ').replace('\r', ' ')
        var left = "$BULLET $label $msg"
        left = if (left.length > leftBudget) {
            left.take((leftBudget - 1).coerceAtLeast(0)) + "…"
        } else {
            left.padEnd(leftBudget)
        }
        val color = colorFor(v?.channel?.let(::levelOf))
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
        return lock.withLock { pinnedTags.firstOrNull { it in tokens } }
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
        const val ENTER_ALT = "\u001B[?1049h"   // save screen + switch to the alternate buffer
        const val LEAVE_ALT = "\u001B[?1049l"   // switch back, restoring the saved screen
        const val CLEAR_HOME = "\u001B[2J\u001B[H"

        const val LABEL_WIDTH = 12
        const val MIN_SCROLL_ROWS = 3       // keep at least this many scrolling rows, else no region
        const val HISTORY_MAX = 10_000      // scrollback ring-buffer capacity (logical lines)
        const val WHEEL_LINES = 3           // lines scrolled per mouse-wheel notch
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
