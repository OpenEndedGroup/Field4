@file:afield.CompletionCategory("Simulation")
package afield.kratos

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Where the worker process comes from, and which end of the socket Field owns.
 *
 * The protocol above this is identical in all three cases — one authenticated frame stream, one
 * session, the envelope in `protocol.md`. What differs is the ten seconds before the first frame.
 *
 * | | who runs Python | who listens | who dials |
 * |---|---|---|---|
 * | [LocalProcess] | Field, as a child process | Field, on an ephemeral 127.0.0.1 port | the worker |
 * | [Container] | Field, as a container | the worker, inside its namespace | Field, on the published port |
 * | [Attach] | nobody — it is already running | the worker | Field |
 *
 * # Why the container case has to reverse the direction
 *
 * [LocalProcess] binds `127.0.0.1` in the JVM and hands the port to the child, which connects back.
 * A container cannot do that. Its network namespace is not the host's, so the host's loopback
 * interface is not reachable from inside it, and on macOS there is a virtual machine in between as
 * well. Publishing a port is the direction that *does* work through both, so in [Container] the
 * worker binds and Field dials.
 *
 * That does mean the worker binds `0.0.0.0` — inside the container. Only the port the runtime
 * publishes leaves that namespace, and it is published to `127.0.0.1` on the host, so the host's
 * exposure is the same loopback-only socket either way. The token is checked on the first frame in
 * both directions.
 *
 * # Why [Container] exists at all
 *
 * Kratos publishes `manylinux_2_28_x86_64` and `win_amd64` wheels. There is no macOS wheel for
 * either architecture, so on an Apple Silicon checkout `uv sync --project optional/kratos` fails
 * during resolution and [LocalProcess] has nothing to run. The container carries the same pinned
 * environment, resolved from the same `uv.lock`. Under emulation it is slow enough that the
 * measured component times mean nothing as absolute numbers; the ratios between them still do.
 */
sealed interface KratosLaunch {

    /** The token the worker will demand on its first frame. Generated per launch, except when attaching. */
    fun token(): String = UUID.randomUUID().toString()

    /**
     * Bring up a worker and return a connected socket.
     *
     * @param log the file both the worker's stdout and stderr are redirected to; quoted back in
     *   startup and transport failures, so a native abort with no Java stack still says something.
     */
    fun start(config: KratosConfig, token: String, log: Path): KratosStarted

    /**
     * Field owns the listener, the worker is an ordinary child process, and it connects back. The
     * default, and the profile everything in `VERIFICATION.md` was measured against.
     */
    data class LocalProcess(
        val python: String,
        val worker: Path,
    ) : KratosLaunch {
        override fun start(config: KratosConfig, token: String, log: Path): KratosStarted {
            require(Files.isRegularFile(worker)) { "Worker script missing: $worker" }
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 100
            val process = ProcessBuilder(python, "-u", worker.toString(), "--port", server.localPort.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile())
                .also { environment(it.environment(), config, token, null) }
                .start()
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs.toLong())
                while (true) {
                    check(process.isAlive) { "Worker exited during startup: ${logTail(log)}" }
                    if (System.nanoTime() > deadline) throw SocketTimeoutException("Kratos worker startup timed out")
                    try {
                        return KratosStarted(server.accept(), process) {}
                    } catch (_: SocketTimeoutException) {
                        // Keep checking that the child is still alive, and the deadline.
                    }
                }
            } catch (e: Exception) {
                process.destroyForcibly()
                throw e
            } finally {
                server.close()
            }
        }
    }

    /**
     * The worker runs in a container and Field dials the published port.
     *
     * The image carries the Python environment only. `optional/kratos/python` is bind-mounted
     * read-only at `/opt/field-kratos`, so editing the worker does not mean rebuilding, and the
     * worker source Field runs is always the one in the checkout.
     *
     * Build it with:
     * ```
     * podman build --platform linux/amd64 -t field-kratos:10.4.0 optional/kratos
     * ```
     *
     * @param image the tag built from `optional/kratos/Containerfile`.
     * @param runtime `podman` or `docker`; resolved on `PATH`.
     * @param platform passed to `--platform`. Needed on arm64 hosts, where it selects emulation;
     *   `null` omits the flag and takes the runtime's default.
     * @param workerDirectory the host directory holding `worker.py` and `thermal.py`.
     * @param extraArgs inserted before the image name — `--cpus`, `--memory`, an extra `-v`.
     */
    data class Container(
        val image: String = System.getenv("AFIELD_KRATOS_IMAGE") ?: "field-kratos:10.4.0",
        val runtime: String = System.getenv("AFIELD_KRATOS_RUNTIME") ?: "podman",
        val platform: String? = System.getenv("AFIELD_KRATOS_PLATFORM") ?: "linux/amd64",
        val workerDirectory: Path = Path.of("optional/kratos/python").toAbsolutePath(),
        val extraArgs: List<String> = emptyList(),
        val startupTimeoutMs: Int = 120_000,
    ) : KratosLaunch {
        init { require(startupTimeoutMs > 0) { "startupTimeoutMs must be positive" } }

        override fun start(config: KratosConfig, token: String, log: Path): KratosStarted {
            require(Files.isDirectory(workerDirectory)) { "Worker directory missing: $workerDirectory" }
            require(Files.isRegularFile(workerDirectory.resolve("worker.py"))) {
                "No worker.py under $workerDirectory"
            }
            val name = "field-kratos-" + UUID.randomUUID().toString().take(12)
            val port = freePort()
            val setup = config.setupScript?.toAbsolutePath()
            val command = buildList {
                add(runtime); add("run"); add("--rm"); add("--name"); add(name)
                platform?.let { add("--platform"); add(it) }
                add("-p"); add("127.0.0.1:$port:$port")
                // Valueless -e inherits from this ProcessBuilder's environment, so the token never
                // appears in an argv that `ps` will show to every user on the machine.
                add("-e"); add("FIELD_KRATOS_TOKEN")
                add("-e"); add("OMP_NUM_THREADS")
                add("-v"); add("$workerDirectory:/opt/field-kratos:ro")
                if (setup != null) {
                    add("-v"); add("${setup.parent}:/opt/field-kratos-setup:ro")
                    add("-e"); add("FIELD_KRATOS_SETUP")
                }
                addAll(extraArgs)
                add(image)
                add("/opt/field-kratos-env/.venv/bin/python"); add("-u")
                add("/opt/field-kratos/worker.py"); add("--listen"); add(port.toString())
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(log.toFile())
                .also {
                    environment(it.environment(), config, token,
                        setup?.let { path -> "/opt/field-kratos-setup/${path.fileName}" })
                }
                .start()
            val remove = {
                runCatching {
                    ProcessBuilder(runtime, "rm", "-f", name)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start().waitFor(5, TimeUnit.SECONDS)
                }
                Unit
            }
            try {
                // A successful connect is NOT evidence the worker is up. Podman publishes the port
                // by running a proxy on the host, and that proxy accepts from the moment `run`
                // starts -- before the image is unpacked, before Python is loaded, before anything
                // in the container has called bind(). Dialling on connect-success gets a socket
                // that reads EOF on the first frame, which then looks like a protocol fault rather
                // than a race. So wait for the worker's own line instead: it prints READY to stdout
                // immediately before accept(), and stdout is this log.
                val ready = "field-kratos listening on $port"
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs.toLong())
                while (!logTail(log).contains(ready)) {
                    check(process.isAlive) {
                        "Container worker exited during startup (${command.first()} run $image): ${logTail(log)}"
                    }
                    if (System.nanoTime() > deadline) {
                        throw SocketTimeoutException(
                            "Kratos container worker did not bind $port inside the container within " +
                                "$startupTimeoutMs ms. Emulated x86-64 startup is slow; raise " +
                                "Container.startupTimeoutMs. ${logTail(log)}"
                        )
                    }
                    Thread.sleep(50)
                }
                return KratosStarted(Socket(InetAddress.getByName("127.0.0.1"), port), process, remove)
            } catch (e: Exception) {
                process.destroyForcibly()
                remove()
                throw e
            }
        }
    }

    /**
     * A worker somebody else started — on a Linux box over an SSH tunnel, in a container run by
     * hand, under a debugger.
     *
     * Field owns no process here, so [KratosSession.close] closes the socket and leaves the worker
     * running. The worker must have been started with `--listen [port]` and the same
     * [token] in `FIELD_KRATOS_TOKEN`, and its first frame check will reject anything else.
     */
    data class Attach(
        val host: String = "127.0.0.1",
        val port: Int,
        val token: String,
    ) : KratosLaunch {
        init { require(port in 1..65535) { "port out of range: $port" } }

        override fun token(): String = token

        override fun start(config: KratosConfig, token: String, log: Path): KratosStarted {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), config.startupTimeoutMs)
            return KratosStarted(socket, null) {}
        }
    }

    companion object {
        /**
         * Bind an ephemeral port, note it, and hand it back free.
         *
         * There is a window between closing this socket and the container runtime binding the same
         * port. Nothing in the protocol can close it — the runtime is a separate process and takes
         * the port on its own schedule — so the failure mode is a startup error naming the port
         * rather than silent misbehaviour.
         */
        private fun freePort(): Int =
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        private fun environment(env: MutableMap<String, String>, config: KratosConfig, token: String, setup: String?) {
            env["FIELD_KRATOS_TOKEN"] = token
            env["OMP_NUM_THREADS"] = config.threads.toString()
            if (setup != null) env["FIELD_KRATOS_SETUP"] = setup
            else config.setupScript?.let { env["FIELD_KRATOS_SETUP"] = it.toAbsolutePath().toString() }
        }

        /** The last 4 KiB of the worker log, or empty. Quoted into startup and transport failures. */
        internal fun logTail(log: Path): String = if (Files.exists(log)) {
            Files.newByteChannel(log).use { channel ->
                channel.position((channel.size() - 4096).coerceAtLeast(0))
                val bytes = java.nio.ByteBuffer.allocate(4096)
                channel.read(bytes)
                String(bytes.array(), 0, bytes.position())
            }
        } else ""
    }
}

/**
 * What a [KratosLaunch] hands back: the connected socket, the process Field owns (if any), and a
 * best-effort release for whatever the launch created besides that process.
 */
class KratosStarted(
    val socket: Socket,
    val process: Process?,
    val release: () -> Unit,
)

/** True when [runtime] resolves on `PATH` and reports a version — the cheap half of a container probe. */
internal fun containerRuntimeAvailable(runtime: String): Boolean = runCatching {
    val process = ProcessBuilder(runtime, "--version")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
}.getOrDefault(false)

/** True when [image] is already built locally, so a missing image is reported rather than pulled. */
internal fun containerImagePresent(runtime: String, image: String): Boolean = runCatching {
    val process = ProcessBuilder(runtime, "image", "exists", image)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
}.getOrDefault(false)
