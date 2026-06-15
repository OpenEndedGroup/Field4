package afield

import afield.logger.lprintln
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

object UserThemeManager {
    private val themeDir = File(System.getProperty("user.home"), ".afield")
    private val themeFile = File(themeDir, "theme.css")

    private const val seedCss = """/* Field user theme tokens.
 * Edit this file while Field is running; changes are applied live.
 * Field applies the field-user-theme class to the document root.
 */
.field-user-theme {
    --field-font: "Iosevka Web", monospace;
    --field-font-system: "Iosevka Web", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    --field-font-code: "Iosevka Web", Monaco, Menlo, Consolas, "Courier New", monospace;
    --field-font-code-fallback: "Iosevka", "JetBrains Mono", Menlo, Consolas, monospace;

    /* Palette: the small set to override for a new theme. */
    --field-black-rgb: 0, 0, 0;
    --field-white-rgb: 255, 255, 255;
    --field-base-rgb: 20, 20, 20;
    --field-editor-rgb: 30, 30, 30;
    --field-accent-rgb: 72, 201, 255;
    --field-danger-rgb: 255, 80, 80;
    --field-warning-rgb: 235, 150, 70;
    --field-search-rgb: 255, 209, 102;
    --field-info-rgb: 100, 150, 255;
    --field-success-rgb: 80, 180, 100;
}
"""

    @Volatile
    private var started = false

    @Volatile
    private var lastSignature: String = ""

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        ensureThemeFile()
        lastSignature = signature()

        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    ensureThemeFile()
                    val next = signature()
                    if (next != lastSignature) {
                        lastSignature = next
                        broadcast()
                    }
                } catch (t: Throwable) {
                    lprintln("error,user-theme", "Error watching ${themeFile.absolutePath}: ${t.message}")
                }
                delay(750.milliseconds)
            }
        }
    }

    fun sendTo(session: WebSocketSession?) {
        ensureThemeFile()
        _send(session, Message("set-user-theme").payload(readTheme()))
    }

    private fun broadcast() {
        val theme = readTheme()
        lprintln("info,user-theme", "Reloaded user theme from ${theme.path}")
        _send(Message("set-user-theme").payload(theme))
    }

    private fun ensureThemeFile() {
        if (!themeDir.exists()) themeDir.mkdirs()
        if (!themeFile.exists()) {
            themeFile.writeText(seedCss)
            lprintln("info,user-theme", "Created user theme file at ${themeFile.absolutePath}")
        } else {
            val css = themeFile.readText()
            if (shouldWrapBareThemeCss(css)) {
                themeFile.writeText(wrapBareThemeCss(css))
                lprintln("info,user-theme", "Wrapped user theme file as valid CSS at ${themeFile.absolutePath}")
            }
        }
    }

    private fun shouldWrapBareThemeCss(css: String): Boolean {
        val trimmed = css.trim()
        return trimmed.isNotEmpty() && !trimmed.contains("{")
    }

    private fun wrapBareThemeCss(css: String): String =
        ".field-user-theme {\n${css.trim().prependIndent("    ")}\n}\n"

    private fun readTheme(): UserTheme =
        UserTheme(
            css = themeFile.readText(),
            path = themeFile.absolutePath,
            lastModified = themeFile.lastModified()
        )

    private fun signature(): String {
        if (!themeFile.exists()) return "missing"
        return "${themeFile.lastModified()}:${themeFile.length()}"
    }
}
