@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.midi.rtp

import afield.document.graph.Box
import afield.runtime.lifecycle.Runloop
import afield.io.osc.query.Mdns
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sound.midi.MidiMessage
import javax.sound.midi.ShortMessage

/**
 * The box-facing side of RTP-MIDI: `rtpMidi(...)` opens or joins an AppleMIDI network session that
 * a DAW, an iPad app or macOS *MIDI Network Setup* can connect to, and `rtpMidiDiscover()` lists the
 * sessions already on the network.
 *
 * Sessions are shared per port across boxes and outlive a stopped box; each box's listeners are
 * replaced when it re-runs. The wire protocol is in `RtpMidiSession.kt` and `RtpMidiCodec.kt`.
 */

/**
 * Process-wide RTP-MIDI state behind `Box.rtpMidi`: one session per control port, its Bonjour
 * advertisement, the per-box callbacks and the latest controller and note values.
 *
 * Sessions follow the house rule for launched things: they are keyed by port and survive a box
 * being stopped, so a DAW stays connected while you edit. A box's callbacks are keyed by box and
 * key, so a re-run replaces its callback, and deleting the box removes them.
 * [closeAll] ends every session (each peer is sent BY).
 */
object RtpMidiSessions {
    internal class Entry(val session: RtpMidiSession, val advert: Closeable?) {
        val controllers = ConcurrentHashMap<Pair<Int, Int>, Int>()
        val notes = ConcurrentHashMap<Pair<Int, Int>, Int>()
        val slots = ConcurrentHashMap<String, (MidiMessage, RtpMidiSession.Peer) -> Unit>()
        @Volatile var messages = 0L
    }

    private val entries = ConcurrentHashMap<Int, Entry>()
    private val queue = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var drainInstalled = false

    internal fun entry(name: String, port: Int, advertise: Boolean): Entry {
        entries[port]?.let { if (!it.session.closed) return it }
        synchronized(this) {
            entries[port]?.let { if (!it.session.closed) return it }
            val s = RtpMidiSession(name, port)
            val advert = if (advertise) runCatching { Mdns.advertise(name, "_apple-midi._udp", s.port) }.getOrNull() else null
            val e = Entry(s, advert)
            s.onMessage { m, _, peer ->
                e.messages++
                if (m is ShortMessage) when (m.command) {
                    ShortMessage.CONTROL_CHANGE -> e.controllers[m.channel to m.data1] = m.data2
                    ShortMessage.NOTE_ON -> if (m.data2 > 0) e.notes[m.channel to m.data1] = m.data2 else e.notes.remove(m.channel to m.data1)
                    ShortMessage.NOTE_OFF -> e.notes.remove(m.channel to m.data1)
                }
                for (h in e.slots.values) dispatch { h(m, peer) }
            }
            entries[s.port] = e
            return e
        }
    }

    /** Runs [r] on the main Runloop inside Field, directly where no Runloop turns (tests). */
    internal fun dispatch(r: () -> Unit) {
        if (Runloop.mainThread == null) return r()
        queue.add(Runnable(r))
        if (!drainInstalled) synchronized(this) {
            if (!drainInstalled) {
                drainInstalled = true
                Runloop.runLoop.preRun.setUnowned("afield.rtpmidi.dispatch", sequence {
                    while (true) {
                        var n = 0
                        while (n++ < 2000) {
                            val next = queue.poll() ?: break
                            try { next.run() } catch (t: Throwable) { afield.app.diagnostics.logging.lprintln("error,rtpmidi", "callback threw: $t") }
                        }
                        yield(null)
                    }
                })
            }
        }
    }

    internal fun listen(box: Box, e: Entry, key: String, handler: (MidiMessage, RtpMidiSession.Peer) -> Unit) {
        val k = "${box.id}|$key"
        val wrapped: (MidiMessage, RtpMidiSession.Peer) -> Unit = { m, p ->
            Box.withBoxContextNow(box) { try { handler(m, p) } catch (t: Throwable) { box.println("rtpMidi '$key': $t") } }
        }
        if (e.slots.put(k, wrapped) == null) {
            box.runScopes.parent.own(afield.runtime.lifecycle.ReleasePolicy.KEYED) { e.slots.remove(k) }
        }
    }

    /** Sessions currently open, by control port. */
    val sessions: Map<Int, RtpMidiSession> get() = entries.filterValues { !it.session.closed }.mapValues { it.value.session }

    fun close(port: Int) { entries.remove(port)?.let { it.advert?.close(); it.session.close() } }

    fun closeAll() { for (p in entries.keys.toList()) close(p) }

    /** AppleMIDI sessions on the local network (`_apple-midi._udp`), collected for [timeoutMs]. */
    fun discover(timeoutMs: Long = 1500): List<Mdns.Service> = Mdns.browse("_apple-midi._udp", timeoutMs)
}

/**
 * A box's view of an RTP-MIDI session: send to every connected peer, and react to what they send.
 * Callbacks run on Field's main loop with this box as context.
 */
class BoxRtpMidi internal constructor(private val box: Box, private val entry: RtpMidiSessions.Entry) {
    val session: RtpMidiSession get() = entry.session
    val port: Int get() = session.port
    val peers: List<RtpMidiSession.Peer> get() = session.peers
    val connected: Boolean get() = session.connectedPeers.isNotEmpty()

    /** Invite another session; [port] is its control port (5004 for macOS's default session). */
    fun invite(host: String, port: Int = RtpMidiSession.DEFAULT_PORT) = session.invite(host, port)

    fun noteOn(pitch: Int, velocity: Int = 100, channel: Int = 0) = session.noteOn(pitch, velocity, channel)
    fun noteOff(pitch: Int, channel: Int = 0) = session.noteOff(pitch, 0, channel)
    fun controlChange(controller: Int, value: Int, channel: Int = 0) = session.controlChange(controller, value, channel)
    fun programChange(program: Int, channel: Int = 0) = session.programChange(program, channel)
    fun pitchBend(value: Int, channel: Int = 0) = session.pitchBend(value, channel)
    fun sysex(bytes: ByteArray) = session.sysex(bytes)
    fun send(message: MidiMessage) = session.send(message)
    fun allNotesOff() = session.allNotesOff()

    /** Every message received, with the peer that sent it. Re-running the box replaces it. */
    fun onMessage(key: String = "message", block: (MidiMessage, RtpMidiSession.Peer) -> Unit) =
        RtpMidiSessions.listen(box, entry, key, block)

    /** Note on (velocity > 0) and note off (velocity 0, including note-on with velocity 0). */
    fun onNote(key: String = "note", block: (pitch: Int, velocity: Int, channel: Int) -> Unit) = onMessage(key) { m, _ ->
        if (m is ShortMessage) when (m.command) {
            ShortMessage.NOTE_ON -> block(m.data1, m.data2, m.channel)
            ShortMessage.NOTE_OFF -> block(m.data1, 0, m.channel)
        }
    }

    /** Control changes as (controller, value 0..127, channel). */
    fun onControl(key: String = "control", block: (controller: Int, value: Int, channel: Int) -> Unit) = onMessage(key) { m, _ ->
        if (m is ShortMessage && m.command == ShortMessage.CONTROL_CHANGE) block(m.data1, m.data2, m.channel)
    }

    /** The last value of [controller] on [channel] as 0..1, or [default] until it has moved. */
    fun knob(controller: Int, channel: Int = 0, default: Double = 0.0): Double =
        entry.controllers[channel to controller]?.let { it / 127.0 } ?: default

    /** Raw 0..127 value of [controller], or null if untouched. */
    fun raw(controller: Int, channel: Int = 0): Int? = entry.controllers[channel to controller]

    /** Notes held now, as (channel, pitch) to velocity. */
    val held: Map<Pair<Int, Int>, Int> get() = entry.notes.toMap()

    /** Messages received since the session opened. */
    val messageCount: Long get() = entry.messages

    fun stopListening(key: String? = null) {
        entry.slots.keys.removeIf { it.startsWith("${box.id}|") && (key == null || it == "${box.id}|$key") }
    }

    fun close() = RtpMidiSessions.close(port)

    override fun toString() = session.toString()
}

/**
 * An RTP-MIDI (AppleMIDI) network session named [name] on UDP [port] and [port] + 1, advertised
 * over Bonjour so macOS *MIDI Network Setup*, iPad apps and rtpMIDI list it. The first call for a
 * port opens it; later calls, from any box, share it.
 */
fun Box.rtpMidi(name: String = "Field", port: Int = RtpMidiSession.DEFAULT_PORT, advertise: Boolean = true): BoxRtpMidi =
    BoxRtpMidi(this, RtpMidiSessions.entry(name, port, advertise))

/** AppleMIDI sessions on the local network, found over Bonjour for [timeoutMs]. */
fun Box.rtpMidiDiscover(timeoutMs: Long = 1500): List<Mdns.Service> = RtpMidiSessions.discover(timeoutMs)
