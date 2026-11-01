@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.browser

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** The two virtual devices created by WiVRn 26.6, not the PC's physical input devices. */
internal enum class WiVRnDevice(val deviceName: String, val product: Int) {
    KEYBOARD("WiVRn Keyboard", 0x0840), MOUSE("WiVRn Mouse", 0x0839);

    companion object {
        fun identify(name: String, bus: Int, vendor: Int, product: Int): WiVRnDevice? =
            entries.firstOrNull { bus == 3 && vendor == 0x4711 && product == it.product && name == it.deviceName }
    }
}

internal data class EvdevEvent(val micros: Long, val type: Int, val code: Int, val value: Int) {
    companion object {
        const val SIZE = 24 // Linux 64-bit timeval + u16 type/code + s32 value.
        fun decode(buffer: ByteBuffer): EvdevEvent {
            require(buffer.remaining() >= SIZE)
            buffer.order(ByteOrder.nativeOrder())
            val seconds = buffer.long
            val micros = buffer.long
            return EvdevEvent(seconds * 1_000_000 + micros, buffer.short.toInt() and 0xffff,
                buffer.short.toInt() and 0xffff, buffer.int)
        }
    }
}

internal data class WiVRnDeviceEvent(val device: WiVRnDevice, val event: EvdevEvent)

internal interface WiVRnEventSource : AutoCloseable {
    val captured: Set<WiVRnDevice>
    val problem: String?
    fun poll(): List<WiVRnDeviceEvent>
    fun takeResets(): Map<WiVRnDevice, Set<Int>>
}

/** Abstract only the syscalls, so selection, grabbing and disconnect paths can be tested. */
internal interface EvdevSystem {
    fun open(path: Path): Int
    fun identity(fd: Int): WiVRnDevice?
    fun grab(fd: Int): Boolean
    fun pressed(fd: Int): Set<Int>
    fun read(fd: Int): List<EvdevEvent>
    fun close(fd: Int)
}

/** Discover by sysfs, verify the opened descriptor again, then grab exclusively or fail closed. */
internal class WiVRnDeviceReader(
    private val sys: Path = Path.of("/sys/class/input"),
    private val dev: Path = Path.of("/dev/input"),
    private val io: EvdevSystem = LinuxEvdevSystem,
    private val clock: () -> Long = System::nanoTime,
) : WiVRnEventSource {
    private val devices = linkedMapOf<Path, Pair<WiVRnDevice, Int>>()
    private val resets = linkedMapOf<WiVRnDevice, Set<Int>>()
    private var nextScan = 0L
    override val captured: Set<WiVRnDevice> get() = devices.values.map { it.first }.toSet()
    override var problem: String? = null
        private set

    override fun poll(): List<WiVRnDeviceEvent> {
        if (clock() >= nextScan) {
            nextScan = clock() + 1_000_000_000
            scan()
        }
        val result = ArrayList<WiVRnDeviceEvent>()
        for ((path, pair) in devices.toMap()) {
            try {
                val events = io.read(pair.second)
                check(events.none { it.type == 0 && it.code == 3 }) { "input overflow; releasing and resynchronizing" }
                result += events.map { WiVRnDeviceEvent(pair.first, it) }
            } catch (error: Exception) {
                io.close(pair.second)
                devices.remove(path)
                resets[pair.first] = emptySet()
                problem = "${pair.first.deviceName} disconnected: ${error.message}"
                nextScan = 0
            }
        }
        // Keyboard modifiers and mouse clicks can interleave within one graphics frame.
        return result.sortedBy { it.event.micros }
    }

    private fun scan() {
        if (!Files.isDirectory(sys)) return
        var issue: String? = null
        val paths = Files.list(sys).use { stream -> stream.filter { it.fileName.toString().matches(Regex("event[0-9]+")) }.toList() }
        for (path in paths) {
            val kind = runCatching {
                val d = path.resolve("device")
                fun hex(name: String) = Files.readString(d.resolve("id/$name")).trim().toInt(16)
                WiVRnDevice.identify(Files.readString(d.resolve("name")).trim(), hex("bustype"), hex("vendor"), hex("product"))
            }.getOrNull() ?: continue
            val node = dev.resolve(path.fileName)
            if (node in devices || kind in captured) continue
            var fd = -1
            try {
                fd = io.open(node)
                check(io.identity(fd) == kind) { "device identity changed while opening $node" }
                check(io.grab(fd)) { "exclusive input grab failed for $node" }
                val initial = io.pressed(fd)
                devices[node] = kind to fd
                resets[kind] = initial
                fd = -1
            } catch (error: Exception) {
                issue = "${kind.deviceName}: ${error.message}. Run tools/setup_wivrn_input.py --install as administrator."
            } finally {
                if (fd >= 0) io.close(fd)
            }
        }
        problem = issue
    }

    override fun takeResets(): Map<WiVRnDevice, Set<Int>> = resets.toMap().also { resets.clear() }

    override fun close() {
        devices.values.forEach { io.close(it.second) }
        devices.clear()
    }
}

/** No helper process, global input group or native build: libc through the JDK's FFM API. */
internal object LinuxEvdevSystem : EvdevSystem {
    private val linker by lazy { Linker.nativeLinker() }
    private fun call(name: String, result: java.lang.foreign.MemoryLayout, vararg args: java.lang.foreign.MemoryLayout) =
        linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), FunctionDescriptor.of(result, *args))
    private val open by lazy { call("open", JAVA_INT, ADDRESS, JAVA_INT) }
    private val close by lazy { call("close", JAVA_INT, JAVA_INT) }
    private val read by lazy { call("read", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG) }
    private val errno by lazy { call("__errno_location", ADDRESS) }
    private val ioctlPointer by lazy {
        linker.downcallHandle(linker.defaultLookup().find("ioctl").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2))
    }
    private val ioctlLong by lazy {
        linker.downcallHandle(linker.defaultLookup().find("ioctl").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG), Linker.Option.firstVariadicArg(2))
    }
    private fun errno(): Int = (errno.invokeExact() as MemorySegment).reinterpret(4).get(JAVA_INT, 0)

    override fun open(path: Path): Int = Arena.ofConfined().use { arena ->
        check(System.getProperty("os.name").startsWith("Linux") && ADDRESS.byteSize() == 8L)
        val fd = open.invokeExact(arena.allocateFrom(path.toString()), 0x800 or 0x80000) as Int // NONBLOCK | CLOEXEC, read-only
        check(fd >= 0) { "cannot read $path (errno ${errno()})" }
        fd
    }

    override fun identity(fd: Int): WiVRnDevice? = Arena.ofConfined().use { arena ->
        val id = arena.allocate(8, 2)
        val name = arena.allocate(256)
        check((ioctlPointer.invokeExact(fd, 0x80084502L, id) as Int) >= 0)
        check((ioctlPointer.invokeExact(fd, 0x81004506L, name) as Int) >= 0)
        WiVRnDevice.identify(name.getString(0), id.get(JAVA_SHORT, 0).toInt() and 0xffff,
            id.get(JAVA_SHORT, 2).toInt() and 0xffff, id.get(JAVA_SHORT, 4).toInt() and 0xffff)
    }

    override fun grab(fd: Int): Boolean = (ioctlLong.invokeExact(fd, 0x40044590L, 1L) as Int) == 0

    override fun pressed(fd: Int): Set<Int> = Arena.ofConfined().use { arena ->
        val bits = arena.allocate(96)
        check((ioctlPointer.invokeExact(fd, 0x80604518L, bits) as Int) >= 0)
        (0 until 768).filter { (bits.get(JAVA_BYTE, (it / 8).toLong()).toInt() and (1 shl (it % 8))) != 0 }.toSet()
    }

    override fun read(fd: Int): List<EvdevEvent> = Arena.ofConfined().use { arena ->
        val data = arena.allocate((EvdevEvent.SIZE * 256).toLong(), 8)
        val count = read.invokeExact(fd, data, data.byteSize()) as Long
        if (count < 0) {
            val error = errno()
            if (error == 11) return emptyList() // EAGAIN
            throw IllegalStateException("evdev read failed (errno $error)")
        }
        check(count > 0 && count % EvdevEvent.SIZE == 0L) { "evdev stream ended or returned a partial event" }
        val bytes = data.asSlice(0, count).asByteBuffer()
        buildList { while (bytes.hasRemaining()) add(EvdevEvent.decode(bytes)) }
    }

    override fun close(fd: Int) { close.invokeExact(fd) as Int }
}
