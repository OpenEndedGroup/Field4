package afield.document.workspace

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the classpath a kit needs before stopping the Field that asked for it, so a build that
 * fails leaves Welcome running and able to say why. Adding a kit package registers a snapshot but
 * loads nothing: its Kotlin extensions, pinned dependencies and native libraries reach a host only
 * through the launch profile [KitPackages] writes, which is why **Restart Field** in Welcome's kit
 * area has to compile and resolve before the restart rather than after it.
 *
 * [KitRestarts.prepare] checks that this installation has both a launcher (`field.sh`, or
 * `field.ps1` on Windows) and a Gradle wrapper, then runs `gradlew jvmJar dumpFieldLaunch
 * -PfieldServerOnly=true` on a daemon thread, adding `--offline` when `AFIELD_OFFLINE` is set and
 * appending to `logs/restart.log` under the kits directory. The current host keeps serving
 * throughout. A non-zero exit, or a build still going after twenty minutes, leaves
 * [KitRestarts.status] at phase `failed` with the message and the log path and no restart happens;
 * only a clean build invokes the `ready` callback, which flushes pending document saves, stops the
 * server and relaunches the launcher with `--server-only`. Welcome polls
 * `GET /api/kits/restart-status` while the build runs, and the Electron or browser window it polled
 * from reconnects to the new host once the restart completes.
 */

/** The phase Welcome is showing: `idle`, `building`, `restarting` or `failed`, with its log path. */
@Serializable
data class KitRestartStatus(val running: Boolean = false, val phase: String = "idle", val message: String = "", val log: String = "")

/** Prepare the next classpath before stopping Field, so a failed kit build leaves Welcome usable. */
object KitRestarts {
    @Volatile var status = KitRestartStatus()
        private set

    @Synchronized
    fun prepare(checkout: File, ready: () -> Unit) {
        require(!status.running) { "A kit runtime build is already running" }
        val windows = System.getProperty("os.name").startsWith("Windows")
        val launcher = File(checkout, if (windows) "field.ps1" else "field.sh")
        require(launcher.isFile) { "This Field installation has no launcher for rebuilding kits" }
        val wrapper = File(checkout, if (windows) "gradlew.bat" else "gradlew")
        require(wrapper.isFile) { "This Field installation has no build wrapper" }
        val log = File(KitPackages.directory, "logs/restart.log").also { it.parentFile.mkdirs() }
        status = KitRestartStatus(true, "building", "Preparing kit classes and dependencies; the current Field remains running.", log.path)
        Thread({
            try {
                val args = mutableListOf(wrapper.path, "jvmJar", "dumpFieldLaunch", "-PfieldServerOnly=true")
                if (System.getenv("AFIELD_OFFLINE") in listOf("1", "true")) args.add("--offline")
                val command = if (windows) listOf("cmd", "/c") + args else args
                val process = ProcessBuilder(command).directory(checkout).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(log)).start()
                if (!process.waitFor(20, TimeUnit.MINUTES)) { process.destroyForcibly(); error("Kit runtime build exceeded 20 minutes") }
                require(process.exitValue() == 0) { "Kit runtime build failed. Field is still running. See ${log.path}\n" + log.readText().takeLast(1800) }
                status = KitRestartStatus(true, "restarting", "Kit runtime prepared; restarting Field.", log.path)
                ready()
            } catch (error: Exception) { status = KitRestartStatus(false, "failed", error.message ?: "Kit runtime preparation failed", log.path) }
        }, "field-kit-build").apply { isDaemon = true }.start()
    }
}
