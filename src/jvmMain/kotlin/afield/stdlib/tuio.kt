@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Data")

package afield.stdlib


import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import com.illposed.osc.transport.OSCPortInBuilder
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A minimal TUIO receiver. [TuioReceiver] opens a UDP OSC port (3333 by default) through JavaOSC
 * and listens on `/tuio/2Dcur` and `/tuio/2Dobj` only — no blobs, no 2.5D or 3D profiles. It keeps
 * the live cursor and object sets, deduces additions and removals by diffing successive ALIVE
 * messages, and on each FSEQ message hands your [TuioListener] one [TuioReceiver.Frame] holding
 * both the full state and the per-frame added / updated / removed lists.
 *
 * Call [TuioReceiver.start] to bind and [TuioReceiver.close] (or `stop()`) to release the port;
 * both are idempotent. Callbacks arrive on JavaOSC's receive thread, not yours, and all internal
 * state is guarded by one lock. Numeric OSC arguments are coerced leniently, and malformed `set`
 * messages are skipped rather than reported. [main] is a demo that prints frame deltas.
 */

fun interface TuioListener {
    fun onFrame(frame: TuioReceiver.Frame)
}

/**
 * Minimal TUIO/UDP receiver for:
 *  - /tuio/2Dcur  (cursors)
 *  - /tuio/2Dobj  (fiducials/objects)
 *
 * It deduces add/remove from successive ALIVE messages, and emits changes on FSEQ.
 *
 * TUIO message formats (spec):
 *  /tuio/2Dcur source application@address
 *  /tuio/2Dcur alive s_id0 ... s_idN
 *  /tuio/2Dcur set s_id x_pos y_pos x_vel y_vel m_accel
 *  /tuio/2Dcur fseq f_id
 *
 *  /tuio/2Dobj set s i x y a X Y A m r
 */
class TuioReceiver(
    private val localPort: Int = 3333,
    private val listener: TuioListener = TuioListener {}
) : Closeable {

    data class TuioCursor(
        val sessionId: Long,
        val x: Float,
        val y: Float,
        val xVel: Float,
        val yVel: Float,
        val motionAccel: Float
    )

    data class TuioObject(
        val sessionId: Long,
        val classId: Long,
        val x: Float,
        val y: Float,
        val angle: Float,
        val xVel: Float,
        val yVel: Float,
        val aVel: Float,
        val motionAccel: Float,
        val rotationAccel: Float
    )


    data class Frame(
        val fseq: Int,
        val source: String?,
        val cursors: Collection<TuioCursor>,
        val objects: Collection<TuioObject>,
        val cursorAdded: List<TuioCursor>,
        val cursorUpdated: List<TuioCursor>,
        val cursorRemoved: List<TuioCursor>,
        val objectAdded: List<TuioObject>,
        val objectUpdated: List<TuioObject>,
        val objectRemoved: List<TuioObject>
    )

    private val lock = ReentrantLock()

    // Current state
    private val cursors = LinkedHashMap<Long, TuioCursor>()
    private val objects = LinkedHashMap<Long, TuioObject>()

    // “Alive” tracking (used to deduce add/remove)
    private var aliveCursors: Set<Long> = emptySet()
    private var aliveObjects: Set<Long> = emptySet()

    // Per-frame deltas (emitted on fseq)
    private val cursorAdded = LinkedHashSet<Long>()
    private val cursorUpdated = LinkedHashSet<Long>()
    private val cursorRemoved = LinkedHashMap<Long, TuioCursor>()

    private val objectAdded = LinkedHashSet<Long>()
    private val objectUpdated = LinkedHashSet<Long>()
    private val objectRemoved = LinkedHashMap<Long, TuioObject>()

    private var currentSource: String? = null

    private var portIn: OSCPortIn? = null

    fun start() {
        lock.withLock {
            if (portIn != null) return
            val builder = OSCPortInBuilder()
                .setLocalPort(localPort)
                .addMessageListener(
                    OSCPatternAddressMessageSelector("/tuio/2Dcur"),
                    OSCMessageListener { msg -> handleTuioMessage(msg.message) }
                )
                .addMessageListener(
                    OSCPatternAddressMessageSelector("/tuio/2Dobj"),
                    OSCMessageListener { msg -> handleTuioMessage(msg.message) }
                )

            portIn = builder.build().also { p ->
                // Some JavaOSC versions want connect(); if yours doesn’t have it, just delete this call.
                try { p.connect() } catch (_: Throwable) {}
                p.startListening()
            }
        }
    }

    fun stop() {
        close()
    }

    override fun close() {
        lock.withLock {
            portIn?.let { p ->
                try { p.stopListening() } catch (_: Throwable) {}
                try { p.close() } catch (_: Throwable) {}
            }
            portIn = null
        }
    }

    private fun handleTuioMessage(message: OSCMessage) {
        // All TUIO commands are string in arg0: "source" / "alive" / "set" / "fseq"
        val addr = message.address
        val args = message.arguments ?: emptyList<Any>()

        if (args.isEmpty() || args[0] !is String) return
        val cmd = args[0] as String

        lock.withLock {
            when (addr) {
                "/tuio/2Dcur" -> handle2DCur(cmd, args)
                "/tuio/2Dobj" -> handle2DObj(cmd, args)
                else -> Unit
            }
        }
    }

    private fun handle2DCur(cmd: String, args: List<Any>) {
        when (cmd) {
            "source" -> {
                currentSource = args.getOrNull(1)?.toString()
            }
            "alive" -> {
                val newAlive = args.drop(1).mapNotNull { it.toLongOrNullSafe() }.toSet()
                val removed = aliveCursors - newAlive
                val added = newAlive - aliveCursors

                removed.forEach { id ->
                    cursors.remove(id)?.let { cursorRemoved[id] = it }
                    cursorAdded.remove(id)
                    cursorUpdated.remove(id)
                }
                added.forEach { id -> cursorAdded.add(id) }

                aliveCursors = newAlive
            }
            "set" -> {
                // set s_id x y X Y m
                if (args.size < 7) return
                val sId = args[1].toLongOrNullSafe() ?: return
                val cursor = TuioCursor(
                    sessionId = sId,
                    x = args[2].toFloatSafe(),
                    y = args[3].toFloatSafe(),
                    xVel = args[4].toFloatSafe(),
                    yVel = args[5].toFloatSafe(),
                    motionAccel = args[6].toFloatSafe()
                )

                val existed = cursors.containsKey(sId)
                cursors[sId] = cursor
                if (!existed && !cursorAdded.contains(sId)) cursorAdded.add(sId) else cursorUpdated.add(sId)
            }
            "fseq" -> {
                val fseq = args.getOrNull(1)?.toIntOrNullSafe() ?: return
                emitFrame(fseq)
            }
        }
    }

    private fun handle2DObj(cmd: String, args: List<Any>) {
        when (cmd) {
            "source" -> {
                currentSource = args.getOrNull(1)?.toString()
            }
            "alive" -> {
                val newAlive = args.drop(1).mapNotNull { it.toLongOrNullSafe() }.toSet()
                val removed = aliveObjects - newAlive
                val added = newAlive - aliveObjects

                removed.forEach { id ->
                    objects.remove(id)?.let { objectRemoved[id] = it }
                    objectAdded.remove(id)
                    objectUpdated.remove(id)
                }
                added.forEach { id -> objectAdded.add(id) }

                aliveObjects = newAlive
            }
            "set" -> {
                // set s i x y a X Y A m r
                if (args.size < 11) return
                val sId = args[1].toLongOrNullSafe() ?: return
                val cId = args[2].toLongOrNullSafe() ?: 0L
                val obj = TuioObject(
                    sessionId = sId,
                    classId = cId,
                    x = args[3].toFloatSafe(),
                    y = args[4].toFloatSafe(),
                    angle = args[5].toFloatSafe(),
                    xVel = args[6].toFloatSafe(),
                    yVel = args[7].toFloatSafe(),
                    aVel = args[8].toFloatSafe(),
                    motionAccel = args[9].toFloatSafe(),
                    rotationAccel = args[10].toFloatSafe()
                )

                val existed = objects.containsKey(sId)
                objects[sId] = obj
                if (!existed && !objectAdded.contains(sId)) objectAdded.add(sId) else objectUpdated.add(sId)
            }
            "fseq" -> {
                val fseq = args.getOrNull(1)?.toIntOrNullSafe() ?: return
                emitFrame(fseq)
            }
        }
    }

    private fun emitFrame(fseq: Int) {
        val addedC = cursorAdded.mapNotNull { cursors[it] }
        val updatedC = cursorUpdated.mapNotNull { cursors[it] }
        val removedC = cursorRemoved.values.toList()

        val addedO = objectAdded.mapNotNull { objects[it] }
        val updatedO = objectUpdated.mapNotNull { objects[it] }
        val removedO = objectRemoved.values.toList()

        val frame = Frame(
            fseq = fseq,
            source = currentSource,
            cursors = cursors.values.toList(),
            objects = objects.values.toList(),
            cursorAdded = addedC,
            cursorUpdated = updatedC,
            cursorRemoved = removedC,
            objectAdded = addedO,
            objectUpdated = updatedO,
            objectRemoved = removedO
        )

        // clear per-frame deltas
        cursorAdded.clear()
        cursorUpdated.clear()
        cursorRemoved.clear()

        objectAdded.clear()
        objectUpdated.clear()
        objectRemoved.clear()

        listener.onFrame(frame)
    }

    private fun Any.toLongOrNullSafe(): Long? = when (this) {
        is Long -> this
        is Int -> this.toLong()
        is Short -> this.toLong()
        is Byte -> this.toLong()
        is Number -> this.toLong()
        is String -> this.toLongOrNull()
        else -> null
    }

    private fun Any.toIntOrNullSafe(): Int? = when (this) {
        is Int -> this
        is Long -> this.toInt()
        is Short -> this.toInt()
        is Byte -> this.toInt()
        is Number -> this.toInt()
        is String -> this.toIntOrNull()
        else -> null
    }

    private fun Any.toFloatSafe(): Float = when (this) {
        is Float -> this
        is Double -> this.toFloat()
        is Int -> this.toFloat()
        is Long -> this.toFloat()
        is Number -> this.toFloat()
        is String -> this.toFloatOrNull() ?: 0f
        else -> 0f
    }
}

/** Tiny demo: prints frame deltas. */
fun main() {
    val r = TuioReceiver(localPort = 3333, listener = TuioListener { frame ->
        println("fseq=${frame.fseq} source=${frame.source}")
        frame.cursorAdded.forEach { println("  +cur $it") }
        frame.cursorUpdated.forEach { println("  ~cur $it") }
        frame.cursorRemoved.forEach { println("  -cur $it") }
        frame.objectAdded.forEach { println("  +obj $it") }
        frame.objectUpdated.forEach { println("  ~obj $it") }
        frame.objectRemoved.forEach { println("  -obj $it") }
    })
    r.start()

    println("Listening for TUIO/UDP on port 3333. Ctrl+C to exit.")
}
