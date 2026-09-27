@file:afield.CompletionCategory("Lighting")

package afield.dmx

import afield.Box
import afield.registerStopHook
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Box-scoped ownership of DMX sockets.
 *
 * Live coding re-runs a box many times and stops it at will; a socket must survive the first and
 * die with the second. So each `artnetOut` / `sacnOut` / `artnetIn` / `sacnIn` call is keyed by
 * box and endpoint: a re-run with the same arguments gets the same, still-sending object back (no
 * rebind, no gap on the wire), different arguments close the old one first, and stopping the box
 * closes it through `Box.registerStopHook` — the teardown path `streamProcess` uses for child
 * processes. This is deliberately *not* the per-port singleton `osc.kt` uses, whose sockets are
 * never released.
 *
 * Inputs are keyed by protocol and port only, because only one socket can own a UDP port: a second
 * box asking for the same input shares the first one, and stopping either box closes it (the next
 * call reopens it).
 */
object DmxSockets {
    private val outputs = ConcurrentHashMap<String, Pair<String, DmxOutput>>()
    private val inputs = ConcurrentHashMap<String, Pair<String, DmxInput>>()
    private val hooked = ConcurrentHashMap.newKeySet<String>()

    /** Every open output and input, for a status display. */
    val open: List<AutoCloseable> get() = outputs.values.map { it.second } + inputs.values.map { it.second }

    internal fun output(box: Box, key: String, config: String, make: () -> DmxOutput): DmxOutput {
        val k = "${box.id}|$key"
        var created: DmxOutput? = null
        val entry = outputs.compute(k) { _, old ->
            if (old != null && old.first == config && !old.second.isClosed) old
            else {
                old?.second?.close()
                val o = make(); created = o
                config to o
            }
        }!!
        created?.let { o ->
            box.registerStopHook(Runnable { outputs.remove(k, entry); o.close() })
        }
        return entry.second
    }

    internal fun input(box: Box, key: String, config: String, make: () -> DmxInput): DmxInput {
        var created: DmxInput? = null
        val entry = inputs.compute(key) { _, old ->
            if (old != null && old.first == config && !old.second.isClosed) old
            else {
                old?.second?.close()
                val i = make(); created = i
                config to i
            }
        }!!
        val i = entry.second
        // One hook per (box, input) instance: a box re-run twenty times must not queue twenty hooks.
        val hookKey = "${box.id}|$key|${System.identityHashCode(i)}"
        if (created != null || hooked.add(hookKey)) {
            hooked.add(hookKey)
            box.registerStopHook(Runnable { hooked.remove(hookKey); inputs.remove(key, entry); i.close() })
        }
        return i
    }

    /** Close everything, e.g. from a "panic" box. */
    fun closeAll() {
        outputs.values.forEach { it.second.close() }; outputs.clear()
        inputs.values.forEach { it.second.close() }; inputs.clear()
    }
}

/**
 * An Art-Net output owned by this box: sends to [host] (a node's IP, `127.0.0.1` for a local
 * receiver, or a broadcast address such as `2.255.255.255`) on [port]. Re-running the box returns
 * the same output; stopping it closes the socket. See [DmxOutput] for the send policy.
 */
fun Box.artnetOut(
    host: String = "127.0.0.1",
    port: Int = ArtNet.PORT,
    maxRateHz: Double = 44.0,
    sync: Boolean = false,
): DmxOutput = DmxSockets.output(this, "artnet>$host:$port", "$maxRateHz|$sync") {
    DmxOutput(DmxProtocol.ARTNET, host = host, port = port, maxRateHz = maxRateHz, sync = sync)
}

/**
 * An sACN (E1.31) output owned by this box. With [host] null, each universe goes to its multicast
 * group `239.255.hi.lo`; with a host, unicast. Stopping the box sends Stream_Terminated and closes
 * the socket. The CID is derived from the box id, so a re-created output is recognised by receivers
 * as the same source.
 */
fun Box.sacnOut(
    host: String? = null,
    port: Int = Sacn.PORT,
    sourceName: String = "Field4 ${name.ifBlank { id }}",
    priority: Int = Sacn.DEFAULT_PRIORITY,
    maxRateHz: Double = 44.0,
    sync: Boolean = false,
): DmxOutput = DmxSockets.output(this, "sacn>${host ?: "multicast"}:$port", "$sourceName|$priority|$maxRateHz|$sync") {
    DmxOutput(
        DmxProtocol.SACN, host = host, port = port, sourceName = sourceName, priority = priority,
        cid = UUID.nameUUIDFromBytes("afield.dmx:$id".toByteArray()), maxRateHz = maxRateHz, sync = sync,
    )
}

/**
 * An Art-Net input on [port]: Field becomes a node, merges what it receives ([merge]) and answers
 * ArtPoll. Closed when this box stops.
 */
fun Box.artnetIn(port: Int = ArtNet.PORT, merge: MergeMode = MergeMode.HTP): DmxInput =
    DmxSockets.input(this, "artnet<$port", merge.name) { DmxInput(DmxProtocol.ARTNET, port = port, merge = merge) }

/**
 * An sACN input on [port]. Pass [universes] to join their multicast groups; unicast traffic arrives
 * regardless. Closed when this box stops.
 */
fun Box.sacnIn(port: Int = Sacn.PORT, universes: List<Int> = emptyList(), merge: MergeMode = MergeMode.HTP): DmxInput =
    DmxSockets.input(this, "sacn<$port", "${merge.name}|$universes") {
        DmxInput(DmxProtocol.SACN, port = port, merge = merge, multicastUniverses = universes)
    }

/**
 * Art-Net discovery: send one ArtPoll to [host]:[port] and collect the ArtPollReplies that come back
 * within [timeoutMs]. Blocks for the whole timeout. Use a broadcast address (e.g. `255.255.255.255`
 * or your subnet's) to find every node on a network; `127.0.0.1` finds a Field [DmxInput] on this
 * machine.
 */
fun artPoll(host: String = "255.255.255.255", port: Int = ArtNet.PORT, timeoutMs: Long = 1000): List<ArtNet.PollReply> {
    val found = LinkedHashMap<String, ArtNet.PollReply>()
    DatagramSocket().use { socket ->
        socket.broadcast = true
        val poll = ByteArray(ArtNet.POLL_LENGTH)
        val n = ArtNet.encodePoll(poll)
        socket.send(DatagramPacket(poll, n, InetSocketAddress(InetAddress.getByName(host), port)))
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1500)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            socket.soTimeout = left.toInt().coerceAtLeast(1)
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (_: SocketTimeoutException) {
                break
            }
            ArtNet.decodePollReply(buf, p.length)?.let { found["${p.address.hostAddress}#${it.bindIndex}"] = it }
        }
    }
    return found.values.toList()
}
