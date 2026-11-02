@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.browser

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/*
 * Keyboard and mouse from a paired headset app (the patched visionOS ALVR client) over the network,
 * delivered as the same evdev-shaped events WiVRn's virtual devices produce, so
 * [WiVRnBrowserInput] does the layout, modifier, cursor and release work unchanged.
 *
 * Wire format, version 1 (the Swift sender is `FieldInputBridge.swift` in the ALVR fork). Every
 * frame is a big-endian u16 length followed by that many bytes, at most [PairedInputProtocol.MAX_FRAME].
 *
 *  1. Field → app: "FVRI", 0x01, server nonce (16 bytes).
 *  2. app → Field: "FVRI", 0x01, client nonce (16), HMAC-SHA256(K, "client" ‖ sn ‖ cn).
 *  3. Field → app: 0x01 then HMAC-SHA256(K, "server" ‖ sn ‖ cn); or 0x00 then a UTF-8 reason, and close.
 *  4. app → Field, repeatedly: u64 sequence (from 1, strictly increasing), then AES-256-GCM of a batch
 *     of records under HMAC-SHA256(K, "session" ‖ sn ‖ cn), nonce = 4 zero bytes ‖ sequence, AAD "FVRI-data".
 *
 * K is SHA-256 of "field-vr-paired-input/1", a zero byte and the normalized pairing code. Records:
 * 0x01 key (u16 HID usage, u8 down), 0x02 motion (f32 dx, f32 dy; CSS-ish pixels, +y down),
 * 0x03 button (u8 index: 0 left, 1 right, 2 middle, 3+ auxiliary; u8 down), 0x04 scroll (f32 dx,
 * f32 dy; lines, +y away from the user), 0x05 reset (u8 length, UTF-8 reason), 0x06 heartbeat.
 *
 * Threat model: a LAN peer cannot inject or read input without the code, and cannot replay a
 * session's frames. Someone who records a handshake can try codes offline; the code is 50 random
 * bits, which is a deterrent rather than a guarantee. Rotate it by deleting the key file.
 */

/** Pairing secret, its key derivation and record codes. Shared constants for the tests. */
internal object PairedInputProtocol {
    const val MAGIC = "FVRI"
    const val VERSION = 1
    const val MAX_FRAME = 4096
    const val NONCE_BYTES = 16
    const val DEFAULT_PORT = 9293

    const val KEY = 1
    const val MOTION = 2
    const val BUTTON = 3
    const val SCROLL = 4
    const val RESET = 5
    const val HEARTBEAT = 6

    /** Crockford base32, so the code survives being read off a headset and typed. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun newCode(random: SecureRandom = SecureRandom()): String {
        val raw = (0 until 10).map { ALPHABET[random.nextInt(32)] }.joinToString("")
        return raw.take(5) + "-" + raw.drop(5)
    }

    fun normalize(code: String): String = code.uppercase().filter { !it.isWhitespace() && it != '-' }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")

    fun validCode(code: String): Boolean = normalize(code).let { it.length == 10 && it.all { c -> c in ALPHABET } }

    fun pairingKey(code: String): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update("field-vr-paired-input/1".toByteArray())
        update(0)
        digest(normalize(code).toByteArray())
    }

    fun hmac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        update(label.toByteArray())
        parts.forEach { update(it) }
        doFinal()
    }

    fun gcm(sessionKey: ByteArray, sequence: Long, mode: Int): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()
        init(mode, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        updateAAD("FVRI-data".toByteArray())
    }

    /** The pairing code persists so the headset app need be told once; 0600, never logged. */
    fun loadOrCreateCode(path: Path = Path.of(System.getProperty("user.home"), ".afield", "vr-paired-input-code")): String {
        if (Files.isRegularFile(path)) {
            val saved = Files.readString(path).trim()
            if (validCode(saved)) return saved
        }
        Files.createDirectories(path.parent)
        val code = newCode()
        val temp = Files.createTempFile(path.parent, ".vr-paired-input-code", ".tmp")
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(temp, code + "\n")
        Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return code
    }
}

/** USB HID keyboard usages (page 0x07) to Linux evdev key codes, as the kernel's hid-input maps them. */
internal object HidToEvdev {
    private val table = IntArray(256).also { t ->
        // 0x04–0x1d letters a–z, in HID order.
        intArrayOf(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44)
            .forEachIndexed { i, code -> t[0x04 + i] = code }
        // 0x1e–0x27 digits 1–0, then Enter, Escape, Backspace, Tab, Space, - = [ ] \ (non-US #) ; ' ` , . / CapsLock.
        intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 28, 1, 14, 15, 57, 12, 13, 26, 27, 43, 43, 39, 40, 41, 51, 52, 53, 58)
            .forEachIndexed { i, code -> t[0x1e + i] = code }
        // 0x3a–0x45 F1–F12.
        intArrayOf(59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88).forEachIndexed { i, code -> t[0x3a + i] = code }
        // 0x46–0x52 PrintScreen ScrollLock Pause Insert Home PageUp Delete End PageDown Right Left Down Up.
        intArrayOf(99, 70, 119, 110, 102, 104, 111, 107, 109, 106, 105, 108, 103).forEachIndexed { i, code -> t[0x46 + i] = code }
        // 0x53–0x63 NumLock, keypad / * - + Enter 1–9 0 .
        intArrayOf(69, 98, 55, 74, 78, 96, 79, 80, 81, 75, 76, 77, 71, 72, 73, 82, 83).forEachIndexed { i, code -> t[0x53 + i] = code }
        // 0x64 non-US \ (ISO), 0x65 Application/Compose, 0x66 Power, 0x67 keypad =.
        t[0x64] = 86; t[0x65] = 127; t[0x66] = 116; t[0x67] = 117
        // 0x68–0x73 F13–F24.
        (0 until 12).forEach { t[0x68 + it] = 183 + it }
        t[0x7f] = 113; t[0x80] = 115; t[0x81] = 114 // Mute, VolumeUp, VolumeDown
        t[0x85] = 121 // keypad comma
        // International: Ro, Katakana/Hiragana, Yen, Henkan, Muhenkan, keypad JP comma; LANG1/2 (Hangeul, Hanja).
        t[0x87] = 89; t[0x88] = 93; t[0x89] = 124; t[0x8a] = 92; t[0x8b] = 94; t[0x8c] = 95
        t[0x90] = 122; t[0x91] = 123
        // 0xe0–0xe7 LeftControl LeftShift LeftAlt LeftMeta RightControl RightShift RightAlt RightMeta.
        intArrayOf(29, 42, 56, 125, 97, 54, 100, 126).forEachIndexed { i, code -> t[0xe0 + i] = code }
    }

    private val swapCommandControl = mapOf(29 to 125, 125 to 29, 97 to 126, 126 to 97)

    /** Null for usages with no Linux key. [commandAsControl] swaps Command and Control for Mac habits. */
    fun key(usage: Int, commandAsControl: Boolean = false): Int? {
        val code = table.getOrNull(usage)?.takeIf { it != 0 } ?: return null
        return if (commandAsControl) swapCommandControl[code] ?: code else code
    }

    val modifierCodes = setOf(29, 42, 56, 125, 97, 54, 100, 126, 58)
}

/**
 * One listening port, at most one authenticated headset session. Network I/O runs on daemon threads;
 * [poll] and [takeResets] run on the frame thread, as [WiVRnBrowserInput] expects. The listener exists
 * only while this source is open, and the session ends with it.
 */
internal class PairedInputSource(
    private val code: String,
    port: Int = PairedInputProtocol.DEFAULT_PORT,
    private val commandAsControl: Boolean = false,
    private val mouseScale: Double = 1.0,
    private val wheelScale: Double = 1.0,
    private val heartbeatTimeoutNanos: Long = 2_500_000_000,
    private val maxQueued: Int = 8192,
    private val repeatDelayNanos: Long = 500_000_000,
    private val repeatIntervalNanos: Long = 33_000_000,
    private val clock: () -> Long = System::nanoTime,
    bindAddress: String? = null,
) : WiVRnEventSource {
    companion object {
        /**
         * `FIELD_VR_PAIRED_INPUT_PORT` (default 9293), `FIELD_VR_PAIRED_COMMAND_AS_CONTROL=1` to type
         * Mac shortcuts with Command, `FIELD_VR_PAIRED_MOUSE_SCALE` and `FIELD_VR_PAIRED_WHEEL_SCALE`
         * (negative flips). The code comes from `~/.afield/vr-paired-input-code`, created on first use.
         */
        fun fromEnvironment(env: (String) -> String? = System::getenv): PairedInputSource = PairedInputSource(
            code = PairedInputProtocol.loadOrCreateCode(),
            port = env("FIELD_VR_PAIRED_INPUT_PORT")?.trim()?.toIntOrNull() ?: PairedInputProtocol.DEFAULT_PORT,
            commandAsControl = env("FIELD_VR_PAIRED_COMMAND_AS_CONTROL")?.trim()?.lowercase() in setOf("1", "true", "yes"),
            mouseScale = env("FIELD_VR_PAIRED_MOUSE_SCALE")?.trim()?.toDoubleOrNull() ?: 1.0,
            wheelScale = env("FIELD_VR_PAIRED_WHEEL_SCALE")?.trim()?.toDoubleOrNull() ?: 1.0,
        )
    }

    private val key = PairedInputProtocol.pairingKey(code)
    private val random = SecureRandom()
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(if (bindAddress == null) InetSocketAddress(port) else InetSocketAddress(bindAddress, port))
        soTimeout = 250
    }
    val port: Int get() = server.localPort

    private val lock = Any()
    private val queue = ArrayDeque<WiVRnDeviceEvent>()
    /** Raised by the network thread; moved to [deliverableResets] by [poll], in step with the events. */
    private val pendingResets = linkedMapOf<WiVRnDevice, Set<Int>>()
    private val deliverableResets = linkedMapOf<WiVRnDevice, Set<Int>>()
    private var session: Socket? = null
    private var sessionPeer: String? = null
    private var lastFrame = 0L
    private var lastProblem: String? = null
    @Volatile private var closed = false

    // Autorepeat: the kernel repeats a held key for evdev readers; a remote key-down does not.
    private var repeatKey: Int? = null
    private var nextRepeat = 0L

    private val thread = Thread(::serve, "field-vr-paired-input").apply { isDaemon = true; start() }

    override val captured: Set<WiVRnDevice>
        get() = synchronized(lock) { if (session != null) WiVRnDevice.entries.toSet() else emptySet() }

    override val problem: String? get() = synchronized(lock) { lastProblem }

    override val status: String
        get() = synchronized(lock) {
            val peer = sessionPeer
            when {
                closed -> "Paired input closed"
                peer != null -> "Paired keyboard + mouse from $peer · desktop focus independent"
                // The code stays visible until a headset pairs: it is how the wearer pairs.
                lastProblem != null -> "Paired input: $lastProblem · port $port · code $code"
                else -> "Paired input: waiting on port $port · code $code"
            }
        }

    /**
     * [WiVRnBrowserInput] applies [takeResets] before the events this returned, so a reset raised after
     * this call waits for the next frame; otherwise a pre-reset key-down could land after its release.
     */
    override fun poll(): List<WiVRnDeviceEvent> = synchronized(lock) {
        val now = clock()
        if (session != null && now - lastFrame > heartbeatTimeoutNanos) dropSession("headset input timed out")
        deliverableResets.putAll(pendingResets)
        pendingResets.clear()
        val result = ArrayList<WiVRnDeviceEvent>(queue.size + 2)
        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            if (event.device == WiVRnDevice.KEYBOARD) trackHeld(event.event, now)
            result += event
        }
        val repeat = repeatKey
        if (repeat != null && now >= nextRepeat) {
            result += WiVRnDeviceEvent(WiVRnDevice.KEYBOARD, EvdevEvent(now / 1000, 1, repeat, 2))
            nextRepeat = now + repeatIntervalNanos
        }
        result
    }

    override fun takeResets(): Map<WiVRnDevice, Set<Int>> = synchronized(lock) {
        deliverableResets.toMap().also { deliverableResets.clear() }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { dropSession(null) }
        runCatching { server.close() }
        thread.join(1000)
    }

    /** Under [lock]. */
    private fun trackHeld(event: EvdevEvent, now: Long) {
        when (event.value) {
            1 -> if (event.code !in HidToEvdev.modifierCodes) { repeatKey = event.code; nextRepeat = now + repeatDelayNanos }
            0 -> if (repeatKey == event.code) repeatKey = null
        }
    }

    /** Under [lock]. Releases everything; nothing held before is replayed by a later session. */
    private fun resetDevices() {
        queue.clear()
        repeatKey = null
        pendingResets[WiVRnDevice.KEYBOARD] = emptySet()
        pendingResets[WiVRnDevice.MOUSE] = emptySet()
    }

    /** Under [lock]. */
    private fun dropSession(problem: String?) {
        val socket = session ?: return
        session = null
        sessionPeer = null
        if (problem != null) lastProblem = problem
        resetDevices()
        runCatching { socket.close() }
    }

    /** Connections being paired or streaming; a few at most, so a flood cannot exhaust threads. */
    private val connections = java.util.concurrent.Semaphore(4)

    private fun serve() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
            if (!connections.tryAcquire()) { runCatching { socket.close() }; continue }
            // Its own thread: a new headset must be able to pair while a dead link is still timing out.
            Thread({ try { handle(socket) } finally { connections.release() } }, "field-vr-paired-input-session")
                .apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 3000
            // One buffered stream per socket: a second one could lose bytes the first read ahead.
            val input = DataInputStream(socket.getInputStream().buffered())
            val sessionKey = handshake(socket, input)
            if (sessionKey == null) {
                Thread.sleep(500) // slows guessing at the code
                return
            }
            val peer = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "headset"
            synchronized(lock) {
                if (closed) return
                // A new authenticated session supersedes the old one, which may be a dead Wi-Fi link.
                dropSession(null)
                resetDevices()
                session = socket
                sessionPeer = peer
                lastProblem = null
                lastFrame = clock()
            }
            receive(socket, input, sessionKey)
        } catch (error: Exception) {
            synchronized(lock) {
                val message = error.message ?: error.javaClass.simpleName
                if (session === socket) dropSession(message) else if (!closed && !socket.isClosed) lastProblem = "connection failed: $message"
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        require(length in 1..PairedInputProtocol.MAX_FRAME) { "bad frame length $length" }
        return ByteArray(length).also { input.readFully(it) }
    }

    private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }

    /** Returns the session key, or null after telling the peer why not. */
    private fun handshake(socket: Socket, input: DataInputStream): ByteArray? {
        val output = DataOutputStream(socket.getOutputStream())
        val serverNonce = ByteArray(PairedInputProtocol.NONCE_BYTES).also(random::nextBytes)
        writeFrame(output, PairedInputProtocol.MAGIC.toByteArray() + byteArrayOf(PairedInputProtocol.VERSION.toByte()) + serverNonce)
        val hello = readFrame(input)
        val header = PairedInputProtocol.MAGIC.toByteArray() + byteArrayOf(PairedInputProtocol.VERSION.toByte())
        if (hello.size != header.size + PairedInputProtocol.NONCE_BYTES + 32 || !hello.copyOf(header.size).contentEquals(header)) {
            writeFrame(output, byteArrayOf(0) + "unsupported protocol".toByteArray())
            synchronized(lock) { lastProblem = "refused a connection speaking another protocol" }
            return null
        }
        val clientNonce = hello.copyOfRange(header.size, header.size + PairedInputProtocol.NONCE_BYTES)
        val proof = hello.copyOfRange(header.size + PairedInputProtocol.NONCE_BYTES, hello.size)
        val expected = PairedInputProtocol.hmac(key, "client", serverNonce, clientNonce)
        if (!MessageDigest.isEqual(proof, expected)) {
            writeFrame(output, byteArrayOf(0) + "wrong pairing code".toByteArray())
            synchronized(lock) { lastProblem = "refused a headset with the wrong pairing code" }
            return null
        }
        writeFrame(output, byteArrayOf(1) + PairedInputProtocol.hmac(key, "server", serverNonce, clientNonce))
        return PairedInputProtocol.hmac(key, "session", serverNonce, clientNonce)
    }

    private fun receive(socket: Socket, input: DataInputStream, sessionKey: ByteArray) {
        val remainders = Remainders()
        var lastSequence = 0L
        while (!closed) {
            val frame = try { readFrame(input) } catch (_: EOFException) {
                synchronized(lock) { if (session === socket) dropSession("headset disconnected") }
                return
            }
            require(frame.size > 8 + 16) { "short data frame" }
            val sequence = ByteBuffer.wrap(frame, 0, 8).long
            require(sequence > lastSequence) { "stale or replayed frame" }
            val plain = try {
                PairedInputProtocol.gcm(sessionKey, sequence, Cipher.DECRYPT_MODE).doFinal(frame, 8, frame.size - 8)
            } catch (_: AEADBadTagException) { throw IllegalStateException("frame failed authentication") }
            lastSequence = sequence
            val items = decode(plain, remainders)
            synchronized(lock) {
                if (session !== socket) return
                lastFrame = clock()
                if (queue.size + items.size > maxQueued) {
                    dropSession("input queue overflow; releasing everything")
                    return
                }
                for (item in items) if (item is WiVRnDeviceEvent) queue.addLast(item) else resetDevices()
            }
        }
    }

    /** Reset requested by the headset, in order with the events around it. */
    private object Reset

    /** Sub-unit motion and wheel carried between records of one session. */
    private class Remainders {
        var x = 0.0; var y = 0.0; var wheelX = 0.0; var wheelY = 0.0
        fun clear() { x = 0.0; y = 0.0; wheelX = 0.0; wheelY = 0.0 }
    }

    /** Evdev events and [Reset]s, in arrival order. Motion and wheel end in a SYN, as evdev reports do. */
    private fun decode(plain: ByteArray, r: Remainders): List<Any> {
        val buffer = ByteBuffer.wrap(plain)
        val now = clock() / 1000
        val out = ArrayList<Any>()
        fun keyboard(code: Int, value: Int) { out += WiVRnDeviceEvent(WiVRnDevice.KEYBOARD, EvdevEvent(now, 1, code, value)) }
        fun mouse(type: Int, code: Int, value: Int) { out += WiVRnDeviceEvent(WiVRnDevice.MOUSE, EvdevEvent(now, type, code, value)) }
        while (buffer.hasRemaining()) {
            when (val record = buffer.get().toInt() and 0xff) {
                PairedInputProtocol.KEY -> {
                    val usage = buffer.short.toInt() and 0xffff
                    val down = buffer.get().toInt() != 0
                    HidToEvdev.key(usage, commandAsControl)?.let { keyboard(it, if (down) 1 else 0) }
                }
                PairedInputProtocol.MOTION -> {
                    r.x += buffer.float * mouseScale
                    r.y += buffer.float * mouseScale
                    val dx = r.x.roundToInt(); val dy = r.y.roundToInt()
                    r.x -= dx; r.y -= dy
                    if (dx != 0) mouse(2, 0, dx)
                    if (dy != 0) mouse(2, 1, dy)
                    if (dx != 0 || dy != 0) mouse(0, 0, 0)
                }
                PairedInputProtocol.BUTTON -> {
                    val index = buffer.get().toInt() and 0xff
                    val down = buffer.get().toInt() != 0
                    val code = when (index) { 0 -> 272; 1 -> 273; 2 -> 274; 3 -> 275; 4 -> 276; else -> null }
                    if (code != null) { mouse(1, code, if (down) 1 else 0); mouse(0, 0, 0) }
                }
                PairedInputProtocol.SCROLL -> {
                    r.wheelX += buffer.float * wheelScale * 120
                    r.wheelY += buffer.float * wheelScale * 120
                    val x = r.wheelX.roundToInt(); val y = r.wheelY.roundToInt()
                    r.wheelX -= x; r.wheelY -= y
                    if (x != 0) mouse(2, 12, x)
                    if (y != 0) mouse(2, 11, y)
                    if (x != 0 || y != 0) mouse(0, 0, 0)
                }
                PairedInputProtocol.RESET -> {
                    val length = buffer.get().toInt() and 0xff
                    buffer.position(buffer.position() + length)
                    r.clear()
                    out += Reset
                }
                PairedInputProtocol.HEARTBEAT -> {}
                else -> throw IllegalArgumentException("unknown record $record")
            }
        }
        return out
    }
}
