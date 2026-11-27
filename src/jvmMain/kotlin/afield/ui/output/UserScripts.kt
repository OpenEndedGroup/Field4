package afield.ui.output

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.runtime.execution.go
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** The registration each box and name holds, so a box run again replaces its own. */
private val userScriptJobs = ConcurrentHashMap<Pair<String, String>, kotlinx.coroutines.Job>()

/**
 * Run [source], Javascript, in every `web(...)` page whose address matches [match], from now on:
 * at the start of each matching document, and straight away in a matching page that is already
 * open. Field's TamperMonkey.
 *
 * [match] is either a bare host, `en.wikipedia.org`, for every page on it, or a pattern in the
 * form userscript managers use, where `*` matches anything: `https://github.com/` then `*` is every
 * page on GitHub over https, and `*.wikipedia.org` as the host covers every language. [runAt] is when in each document the script
 * runs: `"start"` (before the page's own scripts, with no `<body>` yet), `"end"` (when the HTML has
 * been read, the default) or `"idle"` (after the page and its images have loaded). The script runs
 * in the page itself, so it can read and change the page's own variables as well as its DOM, and the
 * page's Content-Security-Policy does not stop it. With a [profile], only pages with that profile
 * run it; by default every web() page does.
 *
 * One script per box and [name]: running the box again replaces it (and runs the new version in
 * open pages), and stopping the box removes it, so later pages no longer run it. Pages it already
 * ran in keep what it did until they reload. An error in the script goes to the page's console
 * (`devTools()`). Needs Field's Electron window.
 *
 * ```kotlin
 * userScript("en.wikipedia.org", """
 *     document.querySelectorAll("sup.reference").forEach(note => note.remove())
 * """)
 * ```
 */
fun Box.userScript(match: String, source: String, runAt: String = "end", profile: String? = null,
                   name: String = "script") =
    registerUserScript(name, "script", userScriptPattern(match), source, runAt, profile)

/** [userScript] with a regular expression for the address, for patterns a `*` cannot say. */
fun Box.userScript(match: Regex, source: String, runAt: String = "end", profile: String? = null,
                   name: String = "script") =
    registerUserScript(name, "script", match.pattern, source, runAt, profile)

/**
 * Apply the stylesheet [css] to every `web(...)` page whose address matches [match] (a pattern as
 * [userScript] takes). It applies from the start of each document, and to open pages at once;
 * running the box again with new CSS restyles open pages without reloading them, and stopping the
 * box takes the style off. One style per box and [name]. Rules may need `!important` to beat the
 * site's own.
 *
 * ```kotlin
 * userStyle("news.ycombinator.com", "body, td { font-family: 'Iosevka Web', monospace !important }")
 * ```
 */
fun Box.userStyle(match: String, css: String, profile: String? = null, name: String = "style") =
    registerUserScript(name, "style", userScriptPattern(match), css, "start", profile)

/**
 * A pattern as a regular expression: a bare host (no `*`, no `/`) is every page on that host, over
 * http or https; otherwise `*` is anything and the rest is literal.
 */
internal fun userScriptPattern(match: String): String {
    val trimmed = match.trim()
    require(trimmed.isNotEmpty()) { "userScript: the pattern cannot be empty" }
    if ('*' !in trimmed && '/' !in trimmed) return "^https?://" + jsRegexEscape(trimmed) + "(:[0-9]+)?([/?#].*)?$"
    return "^" + trimmed.split("*").joinToString(".*") { jsRegexEscape(it) } + "$"
}

/** [text] as a literal in a pattern both Javascript's RegExp and Java's Regex read the same way (not `\Q…\E`). */
private fun jsRegexEscape(text: String) = text.replace(Regex("""[.+?^${'$'}{}()|\[\]\\/-]""")) { "\\" + it.value }

private fun Box.registerUserScript(name: String, kind: String, pattern: String, source: String, runAt: String,
                                   profile: String?) {
    require(runAt in setOf("start", "end", "idle")) { "userScript: runAt must be \"start\", \"end\" or \"idle\"" }
    runCatching { Regex(pattern) }.onFailure { throw IllegalArgumentException("userScript: bad pattern: ${it.message}") }
    val document = inside
    val key = "$id:$name"
    val token = UUID.randomUUID().toString()
    val profileDirectory = profile?.let { webProfileDirectory(it).absolutePath }
    val params = JsonObject(buildMap {
        put("key", JsonPrimitive(key))
        put("token", JsonPrimitive(token))
        put("kind", JsonPrimitive(kind))
        put("match", JsonPrimitive(pattern))
        put("runAt", JsonPrimitive(runAt))
        put("source", JsonPrimitive(source))
        put("profile", profileDirectory?.let { JsonPrimitive(it) } ?: JsonNull)
    })
    // Sent now, so a page this box opens on its next line already has it.
    val added = userScriptRequest(document, "userscript-add", params)
    go {
        val job = coroutineContext.job
        userScriptJobs.put(id to name, job)?.takeIf { it !== job }?.cancel()
        try {
            added.await()
            awaitCancellation()
        } finally {
            userScriptJobs.remove(id to name, job)
            userScriptRequest(document, "userscript-remove",
                JsonObject(mapOf("key" to JsonPrimitive(key), "token" to JsonPrimitive(token))))
        }
    }
}

private fun userScriptRequest(document: SessionInformation, action: String, params: JsonObject): CompletableFuture<JsonElement> =
    LiveOutputIframeBridge.send(document, OutputIframeTarget("", ""), "profile", method = action, params = params)
        .thenApply { it.value }
