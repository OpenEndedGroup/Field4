package afield.media.browser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WiVRnEvdevTest {
    @Test fun `native syscall bindings open and close without touching an input device`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val fd = LinuxEvdevSystem.open(Path.of("/dev/null"))
        try {
            assertFalse(LinuxEvdevSystem.grab(fd))
            assertFailsWith<IllegalStateException> { LinuxEvdevSystem.identity(fd) }
            assertFailsWith<IllegalStateException> { LinuxEvdevSystem.read(fd) }
        } finally { LinuxEvdevSystem.close(fd) }
    }
    @Test fun `kernel packet decoding preserves timestamp type code and signed value`() {
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
            .putLong(123).putLong(456).putShort(2).putShort(0).putInt(-50).flip()
        assertEquals(EvdevEvent(123000456,2,0,-50), EvdevEvent.decode(bytes))
    }

    private class Devices : EvdevSystem {
        val opened = mutableListOf<Path>()
        val closed = mutableListOf<Int>()
        val events = mutableListOf<EvdevEvent>()
        var identity: WiVRnDevice? = WiVRnDevice.KEYBOARD
        var grabbed = true
        var grabCount = 0
        override fun open(path: Path): Int { opened += listOf(path); return 7 }
        override fun identity(fd: Int) = identity
        override fun grab(fd: Int): Boolean { grabCount++; return grabbed }
        override fun pressed(fd: Int) = setOf(30)
        override fun read(fd: Int) = events.toList().also { events.clear() }
        override fun close(fd: Int) { closed += fd }
    }

    private fun device(root: Path, number: Int, name: String, vendor: String = "4711", product: String = "0840") {
        val d = root.resolve("event$number/device")
        Files.createDirectories(d.resolve("id")); Files.writeString(d.resolve("name"), name)
        for ((key,value) in listOf("vendor" to vendor,"product" to product,"bustype" to "0003"))
            Files.writeString(d.resolve("id/$key"), value)
    }

    @Test fun `discovery never opens a physical keyboard or a mismatched vendor`() {
        val root = Files.createTempDirectory("wivrn-sysfs")
        try {
            device(root,1,"PC keyboard");device(root,2,"WiVRn Keyboard",vendor="1234");device(root,3,"WiVRn Keyboard")
            val io = Devices()
            WiVRnDeviceReader(root, root.resolve("dev"), io).use { reader ->
                reader.poll()
                assertEquals(listOf(root.resolve("dev/event3")),io.opened)
                assertEquals(setOf(WiVRnDevice.KEYBOARD),reader.captured)
                assertEquals(setOf(30),reader.takeResets()[WiVRnDevice.KEYBOARD])
            }
            assertEquals(listOf(7),io.closed)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `descriptor identity is checked before grab and a failed grab closes without reading`() {
        val root = Files.createTempDirectory("wivrn-sysfs")
        try {
            device(root,1,"WiVRn Keyboard")
            val io = Devices().also { it.identity = WiVRnDevice.MOUSE }
            WiVRnDeviceReader(root,root,io).use { reader -> reader.poll();assertTrue(reader.captured.isEmpty()) }
            assertEquals(0,io.grabCount);assertEquals(listOf(7),io.closed)
            io.identity = WiVRnDevice.KEYBOARD;io.grabbed=false
            WiVRnDeviceReader(root,root,io).use { reader ->
                reader.poll();assertTrue(reader.captured.isEmpty());assertContains(reader.problem!!,"exclusive input grab failed")
            }
            assertEquals(listOf(7,7),io.closed)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `overflow discards the batch closes capture and schedules state resynchronization`() {
        val root = Files.createTempDirectory("wivrn-sysfs")
        try {
            device(root,1,"WiVRn Keyboard")
            val io = Devices()
            WiVRnDeviceReader(root,root,io).use { reader ->
                reader.poll();reader.takeResets()
                io.events += EvdevEvent(1,1,30,1);io.events += EvdevEvent(2,0,3,0)
                assertTrue(reader.poll().isEmpty());assertTrue(reader.captured.isEmpty())
                assertEquals(emptySet(),reader.takeResets()[WiVRnDevice.KEYBOARD])
                assertContains(reader.problem!!,"overflow")
                reader.poll()
                assertEquals(setOf(30),reader.takeResets()[WiVRnDevice.KEYBOARD])
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
