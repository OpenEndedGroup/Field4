package afield

import afield.logger.lprintln
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Time-windowed WebSocket traffic stats for the front-end `/ws` channel, surfaced on the pinned "net"
 * console row: `↑` = backend → front end, `↓` = front end → backend. Counters are bumped at the send
 * choke point ([sendWithOutputBackPressure]) and the inbound frame loop; a 1s reporter emits per-second
 * packet + KB rates — and only when there's traffic, so the pinned row dims then expires when the front
 * end goes idle (see PinnedConsole's lifecycle).
 */
object NetStats {
    private val outFrames = AtomicLong()
    private val outBytes = AtomicLong()
    private val inFrames = AtomicLong()
    private val inBytes = AtomicLong()

    /** backend → front end: one WebSocket text frame of [bytes]. */
    fun sent(bytes: Int) {
        outFrames.incrementAndGet()
        outBytes.addAndGet(bytes.toLong())
    }

    /** front end → backend: one WebSocket text frame of [bytes]. */
    fun received(bytes: Int) {
        inFrames.incrementAndGet()
        inBytes.addAndGet(bytes.toLong())
    }

    @Volatile private var started = false

    /** Start the 1s reporter (idempotent). */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        thread(isDaemon = true, name = "afield-net-stats") {
            var pOf = 0L
            var pOb = 0L
            var pIf = 0L
            var pIb = 0L
            var last = System.nanoTime()
            while (true) {
                try {
                    Thread.sleep(WINDOW_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val now = System.nanoTime()
                val dt = (now - last) / 1e9
                last = now
                if (dt <= 0.0) continue

                val of = outFrames.get()
                val ob = outBytes.get()
                val nf = inFrames.get()
                val nb = inBytes.get()
                val dOf = of - pOf
                val dOb = ob - pOb
                val dIf = nf - pIf
                val dIb = nb - pIb
                pOf = of; pOb = ob; pIf = nf; pIb = nb

                if (dOf == 0L && dIf == 0L) continue // idle: let the pinned "net" row dim then expire

                lprintln(
                    "info,net",
                    String.format(
                        Locale.US,
                        "↑ %.0f pkt/s %.1f KB/s   ↓ %.0f pkt/s %.1f KB/s",
                        dOf / dt, dOb / dt / 1024.0, dIf / dt, dIb / dt / 1024.0,
                    ),
                )
            }
        }
    }

    private const val WINDOW_MS = 1000L
}
