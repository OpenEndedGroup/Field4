package afield.kratos

import afield.manifold.ManifoldLibrary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KratosTest {
    @Test fun missingWorkerIsCapabilityNotStartupFailure() {
        val report = Kratos.probe(KratosConfig(python = "/no-such-field-kratos-python")).get(5, TimeUnit.SECONDS)
        assertFalse(report.available)
        assertTrue(report.message.contains("AFIELD_KRATOS_PYTHON"))
    }

    @Test fun binaryShapeDtypeAndRangeAreChecked() {
        val descriptor = mapOf("name" to "temperature", "dtype" to "float64", "shape" to listOf(2),
            "byteOrder" to "little", "storage" to "inline", "offset" to 0, "length" to 16)
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(1.5).putDouble(2.5).array()
        fun arrays(d: Map<String, Any?>) = KratosArrays(mapOf("arrays" to listOf(d), "binary" to bytes))
        assertEquals(listOf(1.5,2.5), arrays(descriptor).doubles("temperature",2).toList())
        for (change in listOf(mapOf("shape" to listOf(3)), mapOf("dtype" to "int32"), mapOf("offset" to -1),
            mapOf("offset" to Long.MAX_VALUE), mapOf("length" to 8), mapOf("byteOrder" to "big"),
            mapOf("shape" to listOf(2.5)), mapOf("offset" to 0.5))) {
            assertFails { arrays(descriptor+change).doubles("temperature",2) }
        }
    }

    @Test fun framedBinaryRoundTripAndOversizedFrame() {
        val bytes = ByteArrayOutputStream()
        writeKratosFrame(DataOutputStream(bytes), mapOf("data" to byteArrayOf(0,-1,3), "shape" to listOf(3), "nil" to null))
        val decoded = readKratosFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertTrue((decoded["data"] as ByteArray).contentEquals(byteArrayOf(0,-1,3)))
        val invalid = ByteBuffer.allocate(4).putInt(MAX_KRATOS_FRAME+1).array()
        assertFails { readKratosFrame(DataInputStream(ByteArrayInputStream(invalid))) }
    }

    private fun configured(): KratosConfig? {
        val config = KratosConfig()
        if (!Files.isExecutable(Path.of(config.python))) {
            println("SKIPPED optional Kratos runtime tests: uv sync --project optional/kratos")
            return null
        }
        return config
    }

    @Test fun handshakeCrashTimeoutRestartAndCleanup() {
        val config = configured() ?: return
        val directory = Path.of("optional/kratos/tests/fixtures").toAbsolutePath()
        val original = directory.resolve("fault_worker.py")
        fun tempDirectories(): Set<String> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { stream ->
            stream.filter { it.fileName.toString().startsWith("field-kratos-") }.map { it.toString() }.toList().toSet()
        }
        val before = tempDirectories()
        for (mode in listOf("mismatch", "crash", "timeout")) {
            val script = directory.resolve("$mode.py")
            Files.copy(original, script)
            try {
                val report = Kratos.probe(config.copy(worker=script, requestTimeoutMs=300)).get(5,TimeUnit.SECONDS)
                assertFalse(report.available, mode)
                assertTrue(report.message.contains(if (mode == "mismatch") "mismatch" else "transport failed"), report.message)
            } finally { Files.deleteIfExists(script) }
        }
        assertTrue(Kratos.probe(config).get(10,TimeUnit.SECONDS).available)
        assertEquals(before, tempDirectories(), "All worker socket/process/log resources must be released")
    }

    @Test fun manifoldAndPrimitiveEvaluatorsAgree() {
        val config = configured() ?: return
        if (!ManifoldLibrary.isAvailable) { println("SKIPPED: ${ManifoldLibrary.describe()}"); return }
        KratosSession.openAsync(SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0),8,config).get(15,TimeUnit.SECONDS).use { session ->
            val body = KratosDemo.body()
            val analytic = session.solveAsync(body).get(10,TimeUnit.SECONDS)!!
            val skin = body.solid().use { ManifoldGeometry("body",it) }
            val triangle = session.solveAsync(skin).get(10,TimeUnit.SECONDS)!!
            assertEquals(analytic.distance.map { it>0 },triangle.distance.map { it>0 })
            // CSG min/max and true skin distance differ near reentrant corners;
            // they agree in sign but need not give identical cut interpolation.
            assertEquals(analytic.domain.id,triangle.domain.id)
            assertTrue(triangle.surfaceMesh(skin.surface()).colors != null)
        }
    }

    @Test fun cancelledStartupReleasesWorker() {
        val config = configured() ?: return
        val directory = Path.of("optional/kratos/tests/fixtures").toAbsolutePath()
        val script = directory.resolve("startup.py")
        val started = directory.resolve("startup.started")
        fun workers(): Set<Long> = ProcessHandle.current().children().use { stream ->
            stream.filter { it.info().arguments().orElse(emptyArray()).contains(script.toString()) }
                .map { it.pid() }.toList().toSet()
        }
        Files.copy(directory.resolve("fault_worker.py"), script)
        try {
            val opening = KratosSession.openAsync(SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0),8,config.copy(worker=script))
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(started) && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(Files.exists(started))
            assertTrue(workers().isNotEmpty(), "Cancellation must happen after the child starts")
            assertTrue(opening.cancel(false))
            while (workers().isNotEmpty() && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(workers().isEmpty(), "Cancelled startup must release its child")
        } finally { Files.deleteIfExists(script); Files.deleteIfExists(started) }
    }

    @Test fun inFlightResultCannotOverwriteNewerEdit() {
        val config = configured() ?: return
        val directory = Path.of("optional/kratos/tests/fixtures").toAbsolutePath()
        val script = directory.resolve("delay.py")
        val started = directory.resolve("delay.started")
        Files.copy(directory.resolve("fault_worker.py"), script)
        try {
            KratosSession.openAsync(SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0),8,config.copy(worker=script))
                .get(15,TimeUnit.SECONDS).use { session ->
                    val old = session.solveAsync(KratosDemo.body())
                    val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                    while (!Files.exists(started) && System.nanoTime()<deadline) Thread.sleep(10)
                    assertTrue(Files.exists(started), "The old solve must actually enter the worker")
                    val current = session.solveAsync(KratosDemo.body(.43))
                    assertNull(old.get(10,TimeUnit.SECONDS))
                    val result = current.get(10,TimeUnit.SECONDS)!!
                    assertEquals(result,session.latest)
                    assertEquals(2L,result.revision.geometry)
                }
        } finally { Files.deleteIfExists(script); Files.deleteIfExists(started) }
    }

    @Test fun realWorkerRevisionsCancellationAndNoRemesh() {
        val config = configured() ?: return
        val bounds = SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0)
        KratosSession.openAsync(bounds,12,config).get(15,TimeUnit.SECONDS).use { session ->
            val first = KratosDemo.body(.71)
            val second = KratosDemo.body(.43)
            val a = session.solveAsync(first).get(10,TimeUnit.SECONDS)!!
            val obsolete = session.solveAsync(first,preview=true)
            val current = session.solveAsync(second,preview=true)
            assertNull(obsolete.get(10,TimeUnit.SECONDS))
            val b = current.get(10,TimeUnit.SECONDS)!!
            assertEquals(a.domain.id,b.domain.id)
            assertTrue(a.domain === b.domain)
            assertEquals(1,b.domain.buildCount)
            assertTrue(a.temperature.indices.maxOf { kotlin.math.abs(a.temperature[it]-b.temperature[it]) } > .01)
            assertEquals(b,session.latest)
            val probe = SimulationProbe("center",0.0,0.0,0.0)
            assertEquals(a.sample(probe).probeId,b.sample(probe).probeId)
            assertEquals("body-material",b.diagnostics["regions"].fieldMap()["material"])
            val cancelled = session.solveAsync(first,preview=true)
            session.cancel()
            assertNull(cancelled.get(10,TimeUnit.SECONDS))
            assertNull(session.latest)
            // A burst must not accumulate one debounce delay per obsolete request.
            val burst = (0 until 40).map { session.solveAsync(first,preview=true) }
            assertTrue(burst.last().get(3,TimeUnit.SECONDS) != null)
            assertTrue(burst.dropLast(1).all { it.get(1,TimeUnit.SECONDS) == null })
            val constant = session.solveAsync(second,ThermalParameters(source=0.0,boundaryTemperature=7.0)).get(10,TimeUnit.SECONDS)!!
            assertEquals(7.0,constant.sample(0.0,0.0,0.0)!!,1e-10)
        }
    }
}
