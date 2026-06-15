package afield.debug

import afield.logger.lprintln
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class LiveProbeClient(
    private val helperMainClass: String,
    private val debugHost: String = "127.0.0.1",
    private val debugPort: Int = 5005,
    private val javaBin: String = "java",
    private val classPath: String = System.getProperty("java.class.path")
) : AutoCloseable {

    private val process: Process
    private val writer: PrintWriter
    private val reader: BufferedReader
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-probe-client-reader").apply { isDaemon = true }
    }

    private val listeners = ConcurrentHashMap<String, (ProbeResponse) -> Unit>()

    init {
        val pb = ProcessBuilder(
            javaBin,
            "--add-modules=jdk.jdi",
            "-cp",
            classPath,
            helperMainClass,
            debugHost,
            debugPort.toString()
        )
        pb.redirectErrorStream(true)
        process = pb.start()
        writer = PrintWriter(process.outputStream, true)
        reader = BufferedReader(InputStreamReader(process.inputStream))
        startReader()
    }

    fun probe(request: ProbeVariableRequest, onEvent: (ProbeResponse) -> Unit): String {
        listeners[request.requestId] = onEvent

        synchronized(writer) {
            writer.println(ProbeJson.encodeToString<ProbeRequest>(request))
            writer.flush()
        }

        return request.requestId
    }

    fun isAlive(): Boolean = process.isAlive

    override fun close() {
        runCatching { writer.close() }
        runCatching { reader.close() }
        runCatching { process.destroy() }
        executor.shutdownNow()
    }

    private fun startReader() {
        executor.submit {
            while (true) {
                val line = reader.readLine() ?: break

                val response = try {
                    ProbeJson.decodeFromString<ProbeResponse>(line)
                } catch (_: Throwable) {
                    if (line.isNotBlank()) {
                        lprintln("warning,debug-probe", "ignoring helper output: $line")
                    }
                    continue
                }

                val listener = listeners[response.requestId]
                listener?.invoke(response)

                if (response is CompleteResponse) {
                    listeners.remove(response.requestId)
                }
            }
        }
    }
}
