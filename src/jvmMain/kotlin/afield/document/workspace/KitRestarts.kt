package afield.document.workspace

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

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
