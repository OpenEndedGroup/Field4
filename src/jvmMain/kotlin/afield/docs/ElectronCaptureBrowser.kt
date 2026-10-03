package afield.docs

import afield.four.browser.locateFieldProjectRoot
import afield.logger.lprintln
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/**
 * This checkout's Field Electron app, driven by the server's Playwright over CDP.
 *
 * The capture browser is normally a plain Chromium the server launches (`ensureSharedPlaywrightBrowser`
 * in `Server.kt`), which is right for everything that renders in any browser. Some output only exists
 * inside the Electron app — the RTT view (`afield.four.stream.ElectronRTTView`) draws frames that
 * Electron's `sharedTexture` delivers, and Chromium has no such API — so a capture of it has to come
 * from Electron itself. Playwright's Java API cannot launch Electron, but it can attach to any
 * Chromium over the DevTools protocol, and Electron takes `--remote-debugging-port`.
 *
 * [launch] starts Electron with `APP_URL` set to the document, so its first tab is the Field page
 * (loaded with `field-preload.js`, the preload the RTT view needs), and [fieldPage] finds that tab
 * among the window's targets (the tab strip is a separate page). Requested with
 * `playwright-open --electron` on the backdoor. macOS and Linux (native Wayland for the RTT view).
 */
class ElectronCaptureBrowser private constructor(
    private val process: Process,
    val browser: Browser,
    private val cdpPort: Int,
) : AutoCloseable {

    val alive: Boolean get() = process.isAlive && browser.isConnected

    /** The Field tab: the page on the server's origin that is not the tab strip. */
    fun fieldPage(serverPort: Int, timeoutMs: Long = 30_000): Page {
        val origin = "https://localhost:$serverPort/"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            browser.contexts().flatMap { it.pages() }
                .firstOrNull { runCatching { it.url() }.getOrDefault("").startsWith(origin) }
                ?.let { return it }
            Thread.sleep(200)
        }
        val seen = browser.contexts().flatMap { it.pages() }.map { runCatching { it.url() }.getOrDefault("?") }
        error("Electron (CDP port $cdpPort) showed no Field tab on $origin within ${timeoutMs}ms; pages: $seen")
    }

    override fun close() {
        runCatching { browser.close() }
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        fun launch(playwright: Playwright, appUrl: String, timeoutMs: Long = 60_000): ElectronCaptureBrowser {
            val electronDir = File(locateFieldProjectRoot(), "electron")
            val binary = File(electronDir, if (System.getProperty("os.name").startsWith("Linux"))
                "node_modules/electron/dist/electron"
                else "node_modules/electron/dist/Electron.app/Contents/MacOS/Electron")
            require(binary.canExecute()) {
                "No Electron at ${binary.absolutePath}; run ./gradlew electronInstall"
            }
            val cdpPort = ServerSocket(0).use { it.localPort }
            val log = File.createTempFile("field-electron-capture-", ".log")
            val process = ProcessBuilder(binary.absolutePath, "--remote-debugging-port=$cdpPort", ".")
                .directory(electronDir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .also {
                    it.environment()["APP_URL"] = appUrl
                    // electron/main.js quits once this pid is gone, so a killed JVM leaves no orphan.
                    it.environment()["FIELD_ELECTRON_OWNER_PID"] = ProcessHandle.current().pid().toString()
                    // A fresh profile: no per-host zoom or window state from the user's own Field4 app.
                    it.environment()["FIELD_ELECTRON_USER_DATA"] =
                        java.nio.file.Files.createTempDirectory("field-electron-capture-profile-").toString()
                }
                .start()
            lprintln("info,docs,electron-capture", "Electron pid ${process.pid()} with CDP on $cdpPort for $appUrl (log ${log.absolutePath})")

            val deadline = System.currentTimeMillis() + timeoutMs
            while (!cdpReady(cdpPort)) {
                check(process.isAlive) { "Electron exited (${process.exitValue()}) before CDP came up; see ${log.absolutePath}" }
                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly()
                    error("Electron's CDP endpoint on $cdpPort did not come up within ${timeoutMs}ms; see ${log.absolutePath}")
                }
                Thread.sleep(200)
            }
            val browser = playwright.chromium().connectOverCDP("http://127.0.0.1:$cdpPort")
            return ElectronCaptureBrowser(process, browser, cdpPort).also { capture ->
                Runtime.getRuntime().addShutdownHook(Thread { capture.close() })
            }
        }

        private fun cdpReady(port: Int): Boolean = runCatching {
            val connection = URI("http://127.0.0.1:$port/json/version").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.responseCode == 200
        }.getOrDefault(false)
    }
}
