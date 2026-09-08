@file:afield.CompletionCategory("Simulation")
package afield.kratos

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Optional environment; neither construction nor Field startup imports any native Kratos code.
 *
 * [python] and [worker] describe the default [KratosLaunch.LocalProcess] launch. Set [launch]
 * instead to run the worker in a container or attach to one that is already running — see
 * [KratosLaunch], and note that a machine with no Kratos wheels for its platform (every macOS one)
 * can only use those.
 */
data class KratosConfig(
    val python: String = System.getenv("AFIELD_KRATOS_PYTHON") ?: "optional/kratos/.venv/bin/python",
    val worker: Path = Path.of(System.getenv("AFIELD_KRATOS_WORKER") ?: "optional/kratos/python/worker.py").toAbsolutePath(),
    val startupTimeoutMs: Int = 15_000,
    val requestTimeoutMs: Int = 60_000,
    val threads: Int = 2,
    val setupScript: Path? = null,
    val launch: KratosLaunch? = null,
) {
    init { require(startupTimeoutMs > 0 && requestTimeoutMs > 0 && threads > 0) }

    /**
     * [launch], or the local child process described by [python] and [worker].
     *
     * Public because "which launch am I actually on?" is the first question when a solve behaves
     * differently between two machines, and a box should be able to print the answer.
     */
    fun resolvedLaunch(): KratosLaunch = launch ?: KratosLaunch.LocalProcess(python, worker)

    companion object {
        /**
         * The launches worth trying on this machine, best first, for [Kratos.probeAny].
         *
         * Worth being clear about what this is not: a check that any of them works. An installed
         * interpreter is not an installed environment — a `uv sync` that fails during resolution
         * still leaves `optional/kratos/.venv/bin/python` behind, and a check for that file says
         * yes to a venv containing nothing at all. Only starting the worker settles it, so this
         * lists candidates and [Kratos.probeAny] starts them.
         *
         * `AFIELD_KRATOS_LAUNCH` pins the list to one entry: `local`, or `container`.
         */
        fun candidates(): List<KratosConfig> {
            val local = KratosConfig()
            val container = container()
            val launch = container.launch as KratosLaunch.Container
            val containerPlausible = containerRuntimeAvailable(launch.runtime) &&
                containerImagePresent(launch.runtime, launch.image)
            return when (System.getenv("AFIELD_KRATOS_LAUNCH")?.lowercase()) {
                "local" -> listOf(local)
                "container" -> listOf(container)
                else -> buildList {
                    if (Files.isExecutable(Path.of(local.python))) add(local)
                    if (containerPlausible) add(container)
                    if (isEmpty()) add(local)
                }
            }
        }

        /**
         * Run the worker in the container built from `optional/kratos/Containerfile`.
         *
         * The only profile that works on macOS, where Kratos has no wheels at all. Field dials the
         * published loopback port rather than being dialled back; [KratosLaunch] says why.
         */
        fun container(
            image: String = System.getenv("AFIELD_KRATOS_IMAGE") ?: "field-kratos:10.4.0",
            runtime: String = System.getenv("AFIELD_KRATOS_RUNTIME") ?: "podman",
            threads: Int = 2,
            requestTimeoutMs: Int = 300_000,
        ): KratosConfig = KratosConfig(
            threads = threads,
            // Emulated x86-64 runs the same solve an order of magnitude slower than the native
            // reference, so the request deadline that suits a Linux checkout times out here.
            requestTimeoutMs = requestTimeoutMs,
            launch = KratosLaunch.Container(image = image, runtime = runtime),
        )
    }
}

data class KratosCapability(val available: Boolean, val details: Map<String, Any?>, val message: String)

/** A [KratosConfig] and what actually happened when [Kratos.probeAny] started it. */
data class KratosAvailability(val config: KratosConfig, val capability: KratosCapability) {
    val available: Boolean get() = capability.available
}

/** Cold-start probe is asynchronous, bounded, and closes its worker even when imports fail. */
object Kratos {
    fun probe(config: KratosConfig = KratosConfig()): CompletableFuture<KratosCapability> = CompletableFuture.supplyAsync {
        try {
            KratosConnection(config).use { connection ->
                val detail = connection.call("hello", 0, 0)
                KratosCapability(detail["available"] == true, detail, detail["installation"].toString())
            }
        } catch (e: Exception) {
            KratosCapability(false, emptyMap(), "Kratos unavailable: ${e.message}. ${howToInstall(config)}")
        }
    }

    /**
     * Start each of [configs] in turn and return the first that answers `hello` with
     * `available=true`, or the last failure.
     *
     * This is what an example or a smoke test should call: one source then runs on a Linux
     * checkout with the venv installed and on a macOS checkout with the container image built,
     * and reports which. Code that is measuring something should name its launch instead —
     * discovery that silently changes what you measured is worse than a clear failure.
     */
    fun probeAny(configs: List<KratosConfig> = KratosConfig.candidates()): CompletableFuture<KratosAvailability> =
        CompletableFuture.supplyAsync {
            var last = KratosAvailability(
                KratosConfig(),
                KratosCapability(false, emptyMap(), "No Kratos launch to try."),
            )
            for (config in configs) {
                val capability = probe(config).join()
                last = KratosAvailability(config, capability)
                if (capability.available) return@supplyAsync last
            }
            last
        }

    /**
     * What to tell someone who has not got it, in the launch they actually asked for — and, when
     * the platform has no wheels, the only launch that can work.
     *
     * The unhelpful version of this message is "run uv sync", which on macOS is advice to run a
     * command that cannot succeed: `uv` reports that Kratos publishes `manylinux_2_28_x86_64` and
     * `win_amd64` only, and there is nothing to install.
     */
    fun howToInstall(config: KratosConfig = KratosConfig()): String = when (val launch = config.resolvedLaunch()) {
        is KratosLaunch.Attach ->
            "Start a worker with `--listen ${launch.port}` and FIELD_KRATOS_TOKEN set to the token " +
                "this config carries, reachable at ${launch.host}:${launch.port}."
        is KratosLaunch.Container -> when {
            !containerRuntimeAvailable(launch.runtime) ->
                "No `${launch.runtime}` on PATH. Install it (`brew install podman` and " +
                    "`podman machine init && podman machine start` on macOS), then build the image: " +
                    "`${launch.runtime} build --platform linux/amd64 -t ${launch.image} optional/kratos`."
            !containerImagePresent(launch.runtime, launch.image) ->
                "Image ${launch.image} is not built. Run " +
                    "`${launch.runtime} build --platform linux/amd64 -t ${launch.image} optional/kratos`."
            else ->
                "Image ${launch.image} exists and ${launch.runtime} is on PATH, so this is a runtime " +
                    "failure rather than a missing install; the worker log is quoted above."
        }
        is KratosLaunch.LocalProcess -> if (macOs()) {
            "Kratos publishes no macOS wheel for any architecture, so `uv sync --project " +
                "optional/kratos` cannot succeed here. Use KratosConfig.container() instead, after " +
                "`podman build --platform linux/amd64 -t field-kratos:10.4.0 optional/kratos`."
        } else {
            "Run `uv sync --project optional/kratos --frozen` and set AFIELD_KRATOS_PYTHON to that " +
                ".venv/bin/python, or use KratosConfig.container()."
        }
    }

    private fun macOs(): Boolean =
        System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
}

internal class KratosConnection(private val config: KratosConfig) : AutoCloseable {
    val sessionId: String = UUID.randomUUID().toString()
    private val launch = config.resolvedLaunch()
    private val token = launch.token()
    private val requests = AtomicLong()
    var lastWriteMs: Double = 0.0; private set
    var lastReadMs: Double = 0.0; private set
    private val lock = ReentrantLock()
    private val directory = Files.createTempDirectory("field-kratos-")
    private val log = directory.resolve("worker.log")
    private var process: Process? = null
    private var socket: Socket? = null
    private var release: () -> Unit = {}
    @Volatile private var closed = false
    private val hook = Thread({ close() }, "Kratos shutdown")

    init {
        try {
            Files.createFile(log)
            val started = launch.start(config, token, log)
            socket = started.socket
            process = started.process
            release = started.release
            socket!!.soTimeout = config.requestTimeoutMs
            socket!!.tcpNoDelay = true
            Runtime.getRuntime().addShutdownHook(hook)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun logTail(): String = KratosLaunch.logTail(log)

    fun call(command: String, geometryRevision: Long, simulationRevision: Long, data: Map<String, Any?> = emptyMap()): Map<String, Any?> = lock.withLock {
        check(!closed) { "Kratos worker is closed; open a new session to restart" }
        val request = linkedMapOf<String, Any?>("schemaVersion" to KRATOS_SCHEMA, "units" to KRATOS_UNITS,
            "sessionId" to sessionId, "geometryRevision" to geometryRevision, "simulationRevision" to simulationRevision,
            "requestId" to requests.incrementAndGet(), "cancelled" to false, "command" to command, "token" to token)
        require(data.keys.none { it in request }) { "Payload cannot override protocol envelope" }
        request.putAll(data)
        val response = try {
            val writing = System.nanoTime()
            writeKratosFrame(DataOutputStream(socket!!.getOutputStream()), request)
            lastWriteMs = (System.nanoTime() - writing) / 1e6
            readKratosFrame(DataInputStream(socket!!.getInputStream())) { lastReadMs = it }
        } catch (e: Exception) {
            val detail = logTail()
            close()
            throw IllegalStateException("Kratos transport failed ($command): ${e.message}. $detail. Open a new session to restart.", e)
        }
        for (key in listOf("schemaVersion", "units", "sessionId", "geometryRevision", "simulationRevision", "requestId", "cancelled")) {
            val expected = request[key]
            val actual = response[key]
            val agrees = if (expected is Number) {
                (actual is Long || actual is Int) && expected.toLong() == actual.fieldInteger()
            } else expected == actual
            if (!agrees) { close(); error("Kratos protocol mismatch in $key: expected $expected, got $actual") }
        }
        if (response["ok"] != true) {
            val error = response["error"].fieldMap()
            throw IllegalArgumentException("Kratos ${error["type"]}: ${error["message"]}")
        }
        check(response["obsolete"] == false) { "Kratos marked result obsolete" }
        response["data"].fieldMap()
    }

    override fun close() {
        if (closed) return
        closed = true
        // On an idle connection, ask the worker to release its ModelPart and exit.
        // During a solve, close the socket and terminate only this owned process.
        if (lock.tryLock()) {
            try {
                runCatching {
                    socket?.let { s ->
                        s.soTimeout = 300
                        writeKratosFrame(DataOutputStream(s.getOutputStream()), mapOf(
                            "schemaVersion" to KRATOS_SCHEMA, "units" to KRATOS_UNITS, "sessionId" to sessionId,
                            "geometryRevision" to 0L, "simulationRevision" to 0L, "requestId" to requests.incrementAndGet(),
                            "cancelled" to false, "command" to "shutdown", "token" to token))
                        readKratosFrame(DataInputStream(s.getInputStream()))
                    }
                }
            } finally { lock.unlock() }
        }
        runCatching { socket?.close() }
        process?.let {
            it.destroy()
            if (!it.waitFor(500, TimeUnit.MILLISECONDS)) { it.destroyForcibly(); it.waitFor(500, TimeUnit.MILLISECONDS) }
        }
        // A container runtime's foreground client is not the container. Killing `podman run` should
        // stop it and `--rm` should remove it, but a client killed mid-start can leave one behind,
        // so the launch gets to clean up whatever it created besides the process it handed back.
        runCatching { release() }
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        runCatching { Files.deleteIfExists(log); Files.deleteIfExists(directory) }
    }
}

/** User-visible orchestration states. Preview geometry itself remains ordinary Manifold/TriMesh data. */
enum class KratosState { GEOMETRY_PREVIEW, COARSE_PREVIEW, COMMITTED_SOLVE, READY, FAILED, CLOSED }
enum class SimulationEdit { GEOMETRY, MATERIAL, BOUNDARY, LOAD }

/**
 * The physics half of a request: which formulation the worker should run, and its coefficients.
 *
 * The worker's formulation registry is the authority on what these names mean — `hello` reports
 * every registered one, whether its Kratos application imported, and why not when it did not.
 * Everything else in this file (revisions, framing, binary arrays, the launch, cancellation) is
 * formulation-blind, so a second physics is a second implementation of this and a second entry in
 * `optional/kratos/python/formulations/`.
 */
interface SimulationParameters {
    /** Registry name, e.g. `thermal.stationary`. Must match the session's formulation. */
    val formulation: String
    /** Coefficients, in the keys the formulation's `solve` accepts. */
    fun wire(): Map<String, Any?>
    /** Semantic identities that travel with the request and come back in the diagnostics. */
    fun regions(geometryId: String): Map<String, Any?>
}

data class ThermalParameters(val conductivity: Double = 1.0, val source: Double = 1.0, val boundaryTemperature: Double = 0.0, val penalty: Double = 10.0,
                             val materialId: String = "body-material", val boundaryConditionId: String = "skin-temperature") : SimulationParameters {
    init { require(materialId.isNotBlank() && boundaryConditionId.isNotBlank()); require(listOf(conductivity, source, boundaryTemperature, penalty).all(Double::isFinite) && conductivity > 0 && penalty > 0) }
    override val formulation: String get() = FORMULATION
    override fun wire(): Map<String, Any?> = mapOf("conductivity" to conductivity, "source" to source, "boundary_value" to boundaryTemperature, "penalty" to penalty)
    override fun regions(geometryId: String): Map<String, Any?> = mapOf(
        "solid" to geometryId, "volume" to "$geometryId:volume", "surface" to "$geometryId:skin",
        "material" to materialId, "boundaryCondition" to boundaryConditionId, "output" to "temperature")

    companion object { const val FORMULATION = "thermal.stationary" }
}

/**
 * Coefficients for `flow.embedded`: incompressible flow past the authored solid.
 *
 * The authored solid is the **obstacle** here, not the medium. Conduction is solved inside the
 * part and flow is solved around it, so the same `SimulationGeometry` means opposite things to the
 * two formulations; the worker's `solve_region` is where that is decided and no caller has to
 * negate anything.
 *
 * Unlike [ThermalParameters] this is transient and nonlinear. One request marches [steps] steps of
 * [timeStep] from a uniform free stream and returns the last one, so it costs roughly `steps ×
 * Newton iterations` linear solves rather than one — seconds rather than milliseconds even on a
 * native worker. Treat it as a committed solve; it is not a slider-drag preview.
 *
 * @param velocity free-stream speed along +x, in m/s.
 * @param density fluid density, kg/m³.
 * @param viscosity dynamic viscosity, Pa·s. With [density] and [velocity] this sets the Reynolds
 *   number, and a value too small for the resolution makes the answer noise rather than a wake.
 * @param steps how many time steps to march. More is closer to steady and linearly more expensive.
 * @param penalty dimensionless Nitsche coefficient for the no-slip skin.
 */
data class FlowParameters(
    val velocity: Double = 1.0,
    val density: Double = 1.0,
    val viscosity: Double = 0.05,
    val steps: Int = 3,
    val timeStep: Double = 0.08,
    val penalty: Double = 10.0,
    val maxIterations: Int = 8,
    val materialId: String = "fluid",
    val boundaryConditionId: String = "no-slip-skin",
) : SimulationParameters {
    init {
        require(materialId.isNotBlank() && boundaryConditionId.isNotBlank())
        require(listOf(velocity, density, viscosity, timeStep, penalty).all(Double::isFinite))
        require(density > 0 && viscosity > 0 && timeStep > 0 && penalty > 0) {
            "density, viscosity, time step and penalty must be positive"
        }
        require(steps in 1..200) { "steps must be in 1..200, got $steps" }
        require(maxIterations in 1..50) { "maxIterations must be in 1..50, got $maxIterations" }
    }

    /** Reynolds number on a length you supply — the obstacle's size, not the domain's. */
    fun reynolds(length: Double): Double = density * velocity * length / viscosity

    override val formulation: String get() = FORMULATION
    override fun wire(): Map<String, Any?> = mapOf(
        "velocity" to velocity, "density" to density, "viscosity" to viscosity,
        "steps" to steps, "time_step" to timeStep, "penalty" to penalty,
        "max_iterations" to maxIterations)
    override fun regions(geometryId: String): Map<String, Any?> = mapOf(
        "solid" to geometryId, "volume" to "$geometryId:wake", "surface" to "$geometryId:skin",
        "material" to materialId, "boundaryCondition" to boundaryConditionId, "output" to "velocity")

    companion object { const val FORMULATION = "flow.embedded" }
}

/**
 * Coefficients for `structural.bodyFitted`: linear-elastic small displacement on a supplied mesh.
 *
 * The odd one out, and deliberately so. Kratos has no immersed structural element, so this
 * formulation does not take a [SimulationGeometry] at all — it solves the tetrahedral mesh the
 * session was opened with, which Field produced from the Manifold surface with
 * [afield.tetgen.Tetgen]. A geometry change means a new mesh and therefore a new session; the
 * no-remesh invariant belongs to the embedded formulations and this one does not claim it.
 *
 * Read the limitation before reading a number: `SmallDisplacementElement3D4N` is a constant-strain
 * tetrahedron, which is **exact in axial extension and locks in bending**. Measured against a
 * slender cantilever under self-weight it reaches 36%, 62% and 76% of the Euler–Bernoulli
 * deflection at three refinements, converging but slowly, while the same model reproduces axial
 * extension to 0.04%. Trust it for stiffness-dominated and axial problems; treat bending
 * deflections as a lower bound until this grows quadratic elements.
 *
 * @param clamp an axis-aligned box; every node inside it is fully fixed. Must catch at least one
 *   node, or the stiffness matrix is singular and the solve says so.
 * @param loadRegion an axis-aligned box over whose nodes [load] is spread.
 * @param load the **total** force over [loadRegion], in newtons, not a per-node force — so
 *   refining the mesh does not change the loading.
 */
data class StructuralParameters(
    val youngModulus: Double = 2.1e11,
    val poissonRatio: Double = 0.3,
    val density: Double = 7850.0,
    val gravity: Triple<Double, Double, Double> = Triple(0.0, 0.0, -9.81),
    val clamp: SimulationBounds,
    val loadRegion: SimulationBounds? = null,
    val load: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    val materialId: String = "body-material",
    val boundaryConditionId: String = "clamp",
) : SimulationParameters {
    init {
        require(materialId.isNotBlank() && boundaryConditionId.isNotBlank())
        require(youngModulus > 0 && youngModulus.isFinite()) { "youngModulus must be positive" }
        require(density > 0 && density.isFinite()) { "density must be positive" }
        require(poissonRatio > -1.0 && poissonRatio < 0.5) {
            "poissonRatio must lie in (-1, 0.5); 0.5 is incompressible and this element cannot represent it"
        }
        require(gravity.toList().all(Double::isFinite) && load.toList().all(Double::isFinite))
        require(loadRegion != null || load.toList().all { it == 0.0 }) {
            "a load needs a loadRegion to apply it over"
        }
    }

    override val formulation: String get() = FORMULATION
    override fun wire(): Map<String, Any?> = buildMap {
        put("young_modulus", youngModulus)
        put("poisson_ratio", poissonRatio)
        put("density", density)
        put("gravity", gravity.toList())
        put("clamp", clamp.wire())
        put("load", load.toList())
        loadRegion?.let { put("load_region", it.wire()) }
    }
    override fun regions(geometryId: String): Map<String, Any?> = mapOf(
        "solid" to geometryId, "volume" to "$geometryId:volume", "surface" to "$geometryId:skin",
        "material" to materialId, "boundaryCondition" to boundaryConditionId, "output" to "displacement")

    companion object { const val FORMULATION = "structural.bodyFitted" }
}

data class SimulationRevision(val geometry: Long, val simulation: Long)

/**
 * One persistent Python process, one stable background ModelPart. All work runs off Field's runloop.
 * New requests immediately obsolete old ones; in-flight Kratos solves finish without publishing.
 * Read [latest] on the Field runloop. Never use an unguarded future callback to update a scene.
 */
class KratosSession private constructor(private val connection: KratosConnection, val domain: BackgroundDomain,
                                        val capabilities: Map<String, Any?>,
                                        /** Registry name of the physics this background was built for. */
                                        val formulation: String = ThermalParameters.FORMULATION) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Field Kratos").apply { isDaemon = true } }
    private val gate = Any()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<SimulationResult?>>()
    @Volatile var revision = SimulationRevision(0, 0); private set
    @Volatile var latest: SimulationResult? = null; private set
    @Volatile var state = KratosState.GEOMETRY_PREVIEW; private set
    @Volatile var error: String? = null; private set

    /** True when this session solves a mesh Field sent, rather than classifying a background grid. */
    val isBodyFitted: Boolean by lazy {
        ((capabilities["formulations"] as? Map<*, *>)?.get(formulation) as? Map<*, *>)
            ?.get("suppliedMesh") == true
    }

    /**
     * Solve the mesh this session was opened with. For a body-fitted formulation only.
     *
     * There is no geometry argument because there is nothing to classify: the mesh *is* the
     * geometry, and changing it means [openMeshAsync] again.
     */
    fun solveAsync(parameters: SimulationParameters,
                   edit: SimulationEdit = SimulationEdit.MATERIAL,
                   preview: Boolean = false): CompletableFuture<SimulationResult?> {
        require(isBodyFitted) {
            "$formulation classifies geometry against a background grid; pass the geometry to solve"
        }
        return submit(null, parameters, edit, preview, emptyList())
    }

    fun solveAsync(geometry: SimulationGeometry, parameters: SimulationParameters = ThermalParameters(),
                   edit: SimulationEdit = SimulationEdit.GEOMETRY, preview: Boolean = false,
                   dirtyBounds: List<SimulationBounds> = emptyList()): CompletableFuture<SimulationResult?> {
        require(!isBodyFitted) {
            "$formulation solves the mesh it was given; call solveAsync(parameters) and reopen the " +
                "session with a new mesh to change the geometry"
        }
        return submit(geometry, parameters, edit, preview, dirtyBounds)
    }

    private fun submit(geometry: SimulationGeometry?, parameters: SimulationParameters,
                       edit: SimulationEdit, preview: Boolean,
                       dirtyBounds: List<SimulationBounds>): CompletableFuture<SimulationResult?> {
        require(parameters.formulation == formulation) {
            "session runs $formulation; these parameters are for ${parameters.formulation}. " +
                "Open a separate session per formulation."
        }
        val future = CompletableFuture<SimulationResult?>()
        val ticket: SimulationRevision
        synchronized(gate) {
            check(state != KratosState.CLOSED) { "Session is closed" }
            revision = SimulationRevision(revision.geometry + if (edit == SimulationEdit.GEOMETRY) 1 else 0, revision.simulation + 1)
            ticket = revision
            latest = null
            error = null
            state = if (preview) KratosState.COARSE_PREVIEW else KratosState.COMMITTED_SOLVE
            pending.add(future)
            executor.execute {
                try {
                    // Preview debounce coalesces rapid edits without occupying Field's runloop.
                    if (ticket != revision || future.isCancelled) { future.complete(null); return@execute }
                    if (preview) Thread.sleep(120)
                    if (ticket != revision || future.isCancelled) { future.complete(null); return@execute }
                    val start = System.nanoTime()
                    val geometryData = geometry?.workerData(domain) ?: emptyMap()
                    val geometryMs = (System.nanoTime() - start) / 1e6
                    val transferStart = System.nanoTime()
                    val payload = connection.call("solve", ticket.geometry, ticket.simulation,
                        geometryData + mapOf(
                            "parameters" to parameters.wire(),
                            "regions" to parameters.regions(geometry?.id ?: domain.id),
                            "dirtyBounds" to dirtyBounds.map { it.wire() },
                            "editClass" to edit.name,
                        ))
                    val elapsed = (System.nanoTime() - transferStart) / 1e6
                    val result = SimulationResult.decode(domain, ticket, payload, geometryMs, elapsed, connection.lastWriteMs, connection.lastReadMs)
                    synchronized(gate) {
                        if (ticket == revision && state != KratosState.CLOSED && !future.isCancelled) {
                            latest = result
                            state = KratosState.READY
                            future.complete(result)
                        } else future.complete(null)
                    }
                } catch (e: Exception) {
                    synchronized(gate) {
                        if (ticket == revision && state != KratosState.CLOSED) { error = e.message; state = KratosState.FAILED }
                    }
                    future.completeExceptionally(e)
                } finally { pending.remove(future) }
            }
        }
        return future
    }

    /** Invalidates in-flight and queued work immediately, without interrupting native solver code. */
    fun cancel() = synchronized(gate) {
        if (state != KratosState.CLOSED) {
            revision = revision.copy(simulation = revision.simulation + 1)
            latest = null
            state = KratosState.GEOMETRY_PREVIEW
        }
    }

    /** Guard publication at the moment Field draws, including after a future has already completed. */
    fun withLatest(draw: (SimulationResult) -> Unit) = synchronized(gate) { latest?.takeIf { it.revision == revision }?.let(draw) }

    override fun close() {
        synchronized(gate) {
            if (state == KratosState.CLOSED) return
            cancel()
            state = KratosState.CLOSED
            pending.forEach { it.completeExceptionally(IllegalStateException("Session closed")) }
            pending.clear()
        }
        connection.close()
        executor.shutdownNow()
    }

    companion object {
        /**
         * Open a session on a tetrahedral mesh Field already made, for a body-fitted formulation.
         *
         * The counterpart of [openAsync]. There the worker generates a grid from bounds and a
         * resolution; here Field meshed the Manifold surface with [afield.tetgen.Tetgen] and sends
         * the result, because the input that preserves a sharp edge is that triangulation and it
         * lives on this side.
         *
         * A geometry change means a new mesh and so a new session — there is no reclassification
         * to do instead. Meshing a realistic body takes about 0.2 s, so that is affordable; see
         * `dev_docs/TETGEN.md`.
         */
        fun openMeshAsync(mesh: afield.tetgen.TetMesh, config: KratosConfig = KratosConfig(),
                          formulation: String = StructuralParameters.FORMULATION): CompletableFuture<KratosSession> {
            val opening = CompletableFuture<KratosSession>()
            CompletableFuture.runAsync {
                if (opening.isCancelled) return@runAsync
                var connection: KratosConnection? = null
                try {
                    val worker = KratosConnection(config)
                    connection = worker
                    val capability = worker.call("hello", 0, 0)
                    check(capability["available"] == true) { "Kratos dependencies unavailable: $capability" }
                    val usable = (capability["usableFormulations"] as? List<*>)?.map { it.toString() }
                    check(usable == null || formulation in usable) {
                        "worker cannot run $formulation; it offers ${usable?.joinToString()}"
                    }
                    if (opening.isCancelled) { worker.close(); return@runAsync }
                    val positions = java.nio.ByteBuffer.allocate(mesh.positions.size * 8)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    mesh.positions.forEach { positions.putDouble(it) }
                    val cells = java.nio.ByteBuffer.allocate(mesh.cells.size * 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    mesh.cells.forEach { cells.putInt(it) }
                    val payload = worker.call("createBodyMesh", 0, 0, mapOf(
                        "formulation" to formulation,
                        "positions" to positions.array(), "cells" to cells.array()))
                    check(payload.number("nodeCount") == mesh.nodeCount.toLong() &&
                        payload.number("cellCount") == mesh.cellCount.toLong()) {
                        "worker received a different mesh than was sent: $payload"
                    }
                    val domain = BackgroundDomain.ofSuppliedMesh(
                        payload["backgroundId"] as String, mesh,
                        (payload["domainBuildMs"] as Number).toDouble())
                    val session = KratosSession(worker, domain, capability, formulation)
                    if (!opening.complete(session)) session.close()
                } catch (e: Exception) {
                    connection?.close()
                    opening.completeExceptionally(e)
                }
            }
            return opening
        }

        fun openAsync(bounds: SimulationBounds, resolution: Int = 12, config: KratosConfig = KratosConfig(),
                      formulation: String = ThermalParameters.FORMULATION): CompletableFuture<KratosSession> {
            val opening = CompletableFuture<KratosSession>()
            CompletableFuture.runAsync {
                if (opening.isCancelled) return@runAsync
                var connection: KratosConnection? = null
                try {
                    val worker = KratosConnection(config)
                    connection = worker
                    val capability = worker.call("hello", 0, 0)
                    check(capability["available"] == true) { "Kratos dependencies unavailable: $capability" }
                    if (opening.isCancelled) { worker.close(); return@runAsync }
                    val usable = (capability["usableFormulations"] as? List<*>)?.map { it.toString() }
                    check(usable == null || formulation in usable) {
                        "worker cannot run $formulation; it offers ${usable?.joinToString()}. " +
                            "See the per-formulation import errors in ${capability["formulations"]}"
                    }
                    val payload = worker.call("createBackgroundDomain", 0, 0,
                        mapOf("bounds" to bounds.wire(), "resolution" to resolution, "formulation" to formulation))
                    val session = KratosSession(worker, BackgroundDomain.decode(bounds, resolution, payload), capability, formulation)
                    // Cancellation during startup must not orphan the newly created worker.
                    if (!opening.complete(session)) session.close()
                } catch (e: Exception) {
                    connection?.close()
                    opening.completeExceptionally(e)
                }
            }
            return opening
        }
    }
}
