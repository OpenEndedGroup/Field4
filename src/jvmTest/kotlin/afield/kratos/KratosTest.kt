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
        // The guidance has to name a route that exists on the machine reading it. "Run uv sync" is
        // the wrong answer on macOS, where Kratos publishes no wheel and that command cannot
        // succeed; there the only route is the container.
        val macOs = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
        assertTrue(report.message.contains(if (macOs) "KratosConfig.container()" else "AFIELD_KRATOS_PYTHON"))
        assertTrue(report.message.contains("optional/kratos"))
    }

    @Test fun containerGuidanceNamesTheBuildRatherThanAnInstall() {
        val config = KratosConfig(launch = KratosLaunch.Container(
            image = "field-kratos:no-such-tag", runtime = "no-such-container-runtime"))
        val message = Kratos.howToInstall(config)
        assertTrue(message.contains("no-such-container-runtime"), message)
        assertTrue(message.contains("build --platform linux/amd64"), message)
    }

    @Test fun attachGuidanceNamesThePortItWouldDial() {
        val message = Kratos.howToInstall(KratosConfig(
            launch = KratosLaunch.Attach(port = 45123, token = "t")))
        assertTrue(message.contains("45123"), message)
        assertTrue(message.contains("--listen"), message)
    }

    @Test fun attachingToNothingIsACapabilityNotACrash() {
        val report = Kratos.probe(KratosConfig(
            startupTimeoutMs = 500,
            launch = KratosLaunch.Attach(port = 1, token = "t"),
        )).get(10, TimeUnit.SECONDS)
        assertFalse(report.available)
        assertTrue(report.message.contains("--listen"), report.message)
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

    @Test fun flowAndThermalParametersAreNotInterchangeable() {
        assertEquals("thermal.stationary", ThermalParameters().formulation)
        assertEquals("flow.embedded", FlowParameters().formulation)
        // The wire keys are the formulation's own Python signature, not a shared vocabulary.
        assertEquals(setOf("conductivity", "source", "boundary_value", "penalty"),
            ThermalParameters().wire().keys)
        assertEquals(setOf("velocity", "density", "viscosity", "steps", "time_step", "penalty",
            "max_iterations"), FlowParameters().wire().keys)
        assertEquals("temperature", ThermalParameters().regions("b")["output"])
        assertEquals("velocity", FlowParameters().regions("b")["output"])
    }

    @Test fun flowParametersRefuseAnUnsolvableModel() {
        assertFails { FlowParameters(viscosity = 0.0) }
        assertFails { FlowParameters(density = -1.0) }
        assertFails { FlowParameters(timeStep = 0.0) }
        assertFails { FlowParameters(steps = 0) }
        assertFails { FlowParameters(steps = 500) }
        assertFails { FlowParameters(penalty = 0.0) }
        assertFails { FlowParameters(velocity = Double.NaN) }
        // Reynolds is on a length the caller supplies, because the obstacle's size is not
        // something these parameters know.
        assertEquals(20.0, FlowParameters(velocity = 1.0, density = 1.0, viscosity = 0.05).reynolds(1.0), 1e-12)
    }

    @Test fun primitiveExpressionsAreTheShapeTheWorkerParses() {
        assertEquals(
            mapOf("id" to "ball", "op" to "sphere", "centre" to listOf(1.0, 2.0, 3.0), "radius" to 0.5),
            SimulationSphere("ball", 1.0, 2.0, 3.0, 0.5).expression())
        assertEquals(
            mapOf("id" to "rod", "op" to "cylinder", "a" to listOf(0.0, 0.0, 0.0),
                "b" to listOf(0.0, 0.0, 2.0), "radius" to 0.25),
            SimulationCylinder("rod", 0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.25).expression())
        val group = SimulationGroup("all", SimulationBoolean.UNION, listOf(
            SimulationBox("a", SimulationBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)),
            SimulationSphere("b", 0.0, 0.0, 0.0, 1.0)))
        assertEquals("union", group.expression()["op"])
        assertEquals(listOf("a", "b"), (group.expression()["children"] as List<*>).map { (it as Map<*, *>)["id"] })
    }

    @Test fun primitivesRefuseTheDegenerateCasesRatherThanEmittingThem() {
        assertFails { SimulationSphere("s", 0.0, 0.0, 0.0, 0.0) }
        assertFails { SimulationSphere("", 0.0, 0.0, 0.0, 1.0) }
        // Coincident endpoints have no axis to build a basis from.
        assertFails { SimulationCylinder("c", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0) }
        assertFails { SimulationCylinder("c", 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0) }
        val box = SimulationBox("a", SimulationBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        assertFails { SimulationGroup("g", SimulationBoolean.UNION, listOf(box)) }
        // Two operands sharing an id would make a selection ambiguous after an edit.
        assertFails { SimulationGroup("g", SimulationBoolean.UNION, listOf(box, box)) }
    }

    @Test fun anOffAxisCylinderLandsWhereItsEndpointsSay() {
        if (!ManifoldLibrary.isAvailable) { println("SKIPPED: ${ManifoldLibrary.describe()}"); return }
        // The affine basis is the only part of the cylinder that can be silently wrong: a bad one
        // still produces a closed solid, of the right volume, in the wrong place.
        val radius = 0.2
        val cylinder = SimulationCylinder("rod", -1.0, 0.0, 0.0, 1.0, 1.0, 1.0, radius, segments = 64)
        val mesh = cylinder.surface()
        val lows = DoubleArray(3) { c -> (0 until mesh.positions.size / 3).minOf { mesh.positions[3 * it + c] }.toDouble() }
        val highs = DoubleArray(3) { c -> (0 until mesh.positions.size / 3).maxOf { mesh.positions[3 * it + c] }.toDouble() }
        val axis = doubleArrayOf(2.0, 1.0, 1.0)
        val length = kotlin.math.sqrt(axis.sumOf { it * it })
        val ends = listOf(doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0))
        for (component in 0 until 3) {
            val direction = axis[component] / length
            // A capped cylinder reaches r*sqrt(1-d^2) past its end caps on each axis.
            val overhang = radius * kotlin.math.sqrt((1.0 - direction * direction).coerceAtLeast(0.0))
            val low = ends.minOf { it[component] } - overhang
            val high = ends.maxOf { it[component] } + overhang
            val actualLow = lows[component]
            val actualHigh = highs[component]
            // Inscribed tessellation undershoots the true radius, never overshoots it.
            assertTrue(actualLow >= low - 1e-9 && actualLow <= low + 0.02,
                "axis $component low: expected ~$low, got $actualLow")
            assertTrue(actualHigh <= high + 1e-9 && actualHigh >= high - 0.02,
                "axis $component high: expected ~$high, got $actualHigh")
        }
        // And it is a real solid, not a sheared one: volume within tessellation error of pi r^2 h.
        val expected = Math.PI * radius * radius * length
        val volume = cylinder.solid().use { it.checked().volume }
        assertTrue(kotlin.math.abs(volume - expected) / expected < 0.01, "volume $volume vs $expected")
    }

    /**
     * The local-process worker, or null with a reason.
     *
     * Deliberately not [KratosConfig.candidates]: the tests below substitute a fault script for
     * `worker.py` and read the child process's argv, both of which describe
     * [KratosLaunch.LocalProcess] specifically. Pointed at a container they would pass while
     * testing something else. The container transport is covered end to end by the
     * `kratos.embedded.thermal` catalog smoke, which goes through [Kratos.probeAny].
     */
    private fun configured(): KratosConfig? {
        val config = KratosConfig()
        if (!Files.isExecutable(Path.of(config.python))) {
            println(
                "SKIPPED optional Kratos local-worker tests: no interpreter at ${config.python}. " +
                    "On Linux/Windows: uv sync --project optional/kratos --frozen. On macOS there is " +
                    "no Kratos wheel to install, so these stay skipped and the container transport is " +
                    "covered by `run_field_tests.py --id kratos.embedded.thermal`."
            )
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
