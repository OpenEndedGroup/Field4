package afield.logger

import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Everything written to `System.err` this run: appended to a session file, and — when it looks like
 * a failure — pinned on screen where a repaint cannot take it away.
 *
 * Ktor logs through SLF4J, slf4j-simple writes to `System.err`, and none of that path passes through
 * [LogDispatcher]. So an unhandled-request stack trace never entered [PinnedConsole]'s scrollback
 * ring, which meant three separate things were true at once and each one alone was fatal:
 *
 *  1. It was not in the ring, so the pager's PageUp could not find it.
 *  2. The pager repaints the whole log viewport from that ring, clearing each row as it goes, and it
 *     parks the cursor on the bottom row precisely so that stray writes land somewhere harmless — so
 *     the trace was overwritten by the next repaint, tens of milliseconds later.
 *  3. The pinned header installs a DECSTBM scroll region, and lines that scroll out of a scroll
 *     region are discarded instead of being kept in the terminal's own scrollback.
 *
 * An exception was therefore visible for an instant and then gone, with nothing left to copy. Hence
 * privileging stderr rather than merely tolerating it:
 *
 *  - every byte still reaches the real stderr, so anything capturing this process sees exactly what
 *    it saw before;
 *  - every line is appended to [path], which is the durable, copyable, greppable record;
 *  - a stack trace (or an slf4j `ERROR` line) is raised as a sticky [PinnedConsole.error] panel,
 *    which is the one region of the screen a repaint will not reclaim.
 */
object SessionErrorLog {

    /** Cap on one session file. This machine runs close to a full disk; a log loop must not fill it. */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /** Session files kept in the directory, newest first. */
    private const val KEEP_SESSIONS = 20

    /** Lines gathered into one pinned panel before the rest is left to the file. */
    private const val MAX_RECORD_LINES = 400

    /** A record with no new line for this long is finished. */
    private const val IDLE_FLUSH_MS = 400L

    /** One panel key: the newest failure replaces the previous one, and the file keeps the history. */
    private const val PIN_KEY = "stderr"

    /** `java.lang.NoClassDefFoundError: afield/SourceFileResponse`, `kotlin.KotlinNullPointerException`. */
    private val throwableHeader =
        Regex("""^(?:[\w$]+\.)*[\w$]*(?:Exception|Error|Throwable)\b(?::.*)?$""")

    /** A stack frame, a `Caused by:` chain link, a `Suppressed:` branch, or an `... 66 more` tail. */
    private val throwableContinuation =
        Regex("""^(?:\s+(?:at\s+\S|\.{3}\s+\d+\s+more\b)|(?:Caused by|Suppressed):)""")

    /** slf4j-simple's default layout, which is how Ktor's unhandled-request errors arrive. */
    private val slf4jError = Regex("""^\[[^]]*]\s+ERROR\s+\S+\s+-\s""")

    /** This line opens a failure record: an slf4j `ERROR`, or a bare throwable header. */
    internal fun startsRecord(line: String): Boolean =
        slf4jError.containsMatchIn(line) || throwableHeader.matches(line.trim())

    /**
     * This line belongs to the record already open. A throwable header counts: the slf4j line and
     * the `java.lang.…` line beneath it are one event, and splitting them would pin the half without
     * the stack trace.
     */
    internal fun continuesRecord(line: String): Boolean =
        throwableContinuation.containsMatchIn(line) || throwableHeader.matches(line.trim())

    private val installed = AtomicBoolean(false)
    private val lock = Any()
    private val record = ArrayList<String>()
    private var recordAtNanos = 0L

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var written = 0L

    /** This run's session file, once [install] has run. Null when the file could not be opened. */
    @Volatile
    var path: Path? = null
        private set

    /**
     * Tee `System.err` into the session file and the pinned console. Idempotent; call once, after the
     * [PinnedConsole] sink exists so that a stack trace thrown on the way up can already be pinned.
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val dir = Paths.get(System.getProperty("user.dir")).resolve("build").resolve("logs")
        val file = runCatching {
            Files.createDirectories(dir)
            pruneOldSessions(dir)
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            dir.resolve("session-$stamp-pid${ProcessHandle.current().pid()}.log")
        }.getOrNull() ?: return
        val stream = runCatching { FileOutputStream(file.toFile(), true) }.getOrNull() ?: return

        path = file
        out = stream
        System.setErr(PrintStream(TeeStderr(System.err), true, StandardCharsets.UTF_8))

        thread(isDaemon = true, name = "session-error-log") {
            while (true) {
                try {
                    Thread.sleep(IDLE_FLUSH_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                synchronized(lock) {
                    val idleNanos = System.nanoTime() - recordAtNanos
                    if (record.isNotEmpty() && idleNanos > IDLE_FLUSH_MS * 1_000_000) flushRecord()
                }
            }
        }
        lprintln("info,startup", "stderr → $file")
    }

    /** Keep only the newest [KEEP_SESSIONS] session files, counting the one about to be created. */
    private fun pruneOldSessions(dir: Path) {
        runCatching {
            Files.newDirectoryStream(dir, "session-*.log").use { it.toList() }
                .sortedByDescending { it.fileName.toString() }
                .drop(KEEP_SESSIONS - 1)
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * Pass every byte through to the real stderr, and split the copy into lines. A stack trace
     * arrives as many small writes, so grouping by line is what makes the record legible; the
     * pass-through is unconditional so no existing reader of this process's stderr changes.
     */
    private class TeeStderr(private val real: PrintStream) : OutputStream() {
        private val pending = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            real.write(b)
            accumulate(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            real.write(b, off, len)
            for (i in off until off + len) accumulate(b[i].toInt())
        }

        override fun flush() = real.flush()

        private fun accumulate(byte: Int) {
            if (byte == '\n'.code) {
                val text = pending.toString(StandardCharsets.UTF_8)
                pending.reset()
                onLine(text.trimEnd('\r'))
            } else if (pending.size() < 64 * 1024) {
                pending.write(byte)
            }
        }
    }

    private fun onLine(text: String) {
        synchronized(lock) {
            appendToFile(text)
            consume(text)
        }
    }

    /** Must hold [lock]. Never writes to stderr on failure — that would recurse straight back here. */
    private fun appendToFile(text: String) {
        val stream = out ?: return
        if (written >= MAX_BYTES) return
        runCatching {
            val bytes = (text + "\n").toByteArray(StandardCharsets.UTF_8)
            stream.write(bytes)
            written += bytes.size
            if (written >= MAX_BYTES) {
                stream.write("[session log capped at $MAX_BYTES bytes]\n".toByteArray(StandardCharsets.UTF_8))
            }
            // Flushed per line: the whole point is to still have the last thing stderr said after a crash.
            stream.flush()
        }
    }

    /**
     * Must hold [lock]. Gathers consecutive lines into one failure record. A throwable header while a
     * record is already open *continues* it rather than starting a new one — the slf4j line and the
     * `java.lang.…` line beneath it are one event, and splitting them would pin the half without the
     * stack trace.
     */
    private fun consume(line: String) {
        recordAtNanos = System.nanoTime()
        val open = record.isNotEmpty()
        val continues = open && continuesRecord(line)
        val starts = startsRecord(line)
        when {
            continues -> if (record.size < MAX_RECORD_LINES) record += line
            starts -> {
                flushRecord()
                record += line
            }
            open -> flushRecord()
        }
    }

    /** Must hold [lock]. */
    private fun flushRecord() {
        if (record.isEmpty()) return
        val lines = record.toList()
        record.clear()
        val first = lines.first().trim()
        val title = if (first.length > 160) first.take(159) + "…" else first
        val body = ArrayList<String>()
        // First, so it survives the panel's height cap: the point of the panel is to say where to copy from.
        path?.let { body += "copy from $it" }
        lines.drop(1).forEach { body += it.trimEnd() }
        runCatching { PinnedConsole.error(PIN_KEY, title, body) }
    }
}
