@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package afield.ai

import com.openai.client.OpenAIClientAsync
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/* ──────────────────────────────────────────────────────────────────────────────
 * Public helpers (same shape you already use)
 * ──────────────────────────────────────────────────────────────────────────── */

data class ToolEntry<T : Any>(
    /** The annotated schema class used to derive the JSON schema (→ tool name). */
    val schema: Class<T>,
    /** Optional display name (e.g. "plans.add_room"). */
    val name: String = schema.simpleName,
    /** Your handler. Takes parsed args (schema instance). */
    val handler: (Any) -> Any
)

@Suppress("FunctionName")
fun <T : Any> tool(schema: Class<T>, name: String = schema.simpleName, handler: (T) -> Any) =
    ToolEntry(schema = schema, name = name) { args -> @Suppress("UNCHECKED_CAST") handler(args as T) }

/* ──────────────────────────────────────────────────────────────────────────────
 * Minimal transcript (local, optional)
 * ──────────────────────────────────────────────────────────────────────────── */

data class ToolCallRecord(
    val name: String,
    val callId: String,
    val outputSummary: String
)

data class Turn(
    val role: String,           // "user" | "assistant"
    val content: String,
    val toolCalls: List<ToolCallRecord> = emptyList()
)



private fun normalizeToolName(n: String) = n.trim().lowercase().replace(' ', '_')

/* ---------------------------------- AI2 ----------------------------------- */

class AI2(
    apiKey: String = System.getenv("OPENAI_API_KEY") ?: "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",
    private val model: ChatModel = ChatModel.GPT_5_1_CODEX
) {
    private val client: OpenAIClientAsync = OpenAIOkHttpClientAsync.builder()
        .apiKey(apiKey)
        .build()

    /** Simple rolling memory that we include as plain text every user turn. */
    private val transcript = StringBuilder()

    /** Start one user turn. */
    fun converse(
        userInput: String,
        tools: List<ToolEntry<out Any>> = emptyList(),
        systemPreamble: String =
            "Assistant is helpful and concise. If tools are available, call them.",
        maxOutputTokens: Int = 1000,
        onDelta: (String) -> Unit = { },
        onDone: (String) -> Unit = { },
        onToolCallStarted: (String) -> Unit = { _ -> },
        onToolCallFinished: (String, Any?) -> Unit = { _, _ -> }
    ) {
        val packedInput = buildString {
            appendLine(systemPreamble)
            if (transcript.isNotBlank()) {
                appendLine()
                appendLine("Conversation so far:")
                append(transcript)
                appendLine()
            }
            appendLine("User: $userInput")
            append("Assistant: ")
        }

        // Build the initial request for THIS turn (no previousResponseId here).
        val start = ResponseCreateParams.builder()
            .model(model)
            .input(packedInput)
            .maxOutputTokens(maxOutputTokens.toLong())
            .apply {
                // publish tools on every request
                tools.forEach { entry ->
                    println("AI2: register tool -> ${entry.name}")
                    @Suppress("UNCHECKED_CAST")
                    addTool(entry.schema as Class<Any>)
                }
            }
            .build()

        // Run the turn (streams + possible tool chaining).
        runTurn(
            firstParams = start,
            tools = tools,
            onDelta = onDelta,
            onDone = { final ->
                if (final.isNotBlank()) {
                    transcript.appendLine(final)
                    transcript.appendLine()
                }
                onDone(final)
            },
            onToolCallStarted = onToolCallStarted,
            onToolCallFinished = onToolCallFinished
        )
    }

    /* ------------------------- One streamed model turn ------------------------- */
    private fun runTurn(
        firstParams: ResponseCreateParams,
        tools: List<ToolEntry<out Any>>,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onToolCallStarted: (String) -> Unit,
        onToolCallFinished: (String, Any?) -> Unit
    ) {
        val sb = StringBuilder()
        val finished = AtomicBoolean(false)
        val chained = AtomicBoolean(false)
        var currentResponseId: String? = null

        val stream = client.responses().createStreaming(firstParams)

        fun safeFinalize() {
            if (!finished.compareAndSet(false, true)) return
            onDone(sb.toString())
            try { stream.close() } catch (_: Throwable) {}
        }

        stream.subscribe { ev ->
            println("              event ${ev}")
            // response created -> capture response id for follow-ups
            ev.created().ifPresent { created ->
                currentResponseId = created.response().id()
            }

            // text streaming deltas
            ev.outputTextDelta().ifPresent { d ->
                val chunk = d.delta()
                if (chunk.isNotEmpty()) {
                    sb.append(chunk)
                    onDelta(chunk)
                }
            }

            // tool/function call finished for an item
            ev.outputItemDone().ifPresent { done ->
                val fc = done.item().asFunctionCall()
                if (fc is ResponseFunctionToolCall) {
                    val callId = fc.callId()
                    val toolName = fc.name()

                    val entry = tools.firstOrNull {
                        normalizeToolName(it.name) == normalizeToolName(toolName)
                    }

                    onToolCallStarted(toolName)

                    val result: Any? = try {
                        if (entry != null) {
                            val parsed = fc.arguments(entry.schema)
                            @Suppress("UNCHECKED_CAST")
                            (entry.handler as (Any) -> Any).invoke(parsed)
                        } else {
                            mapOf("error" to "No handler registered for '$toolName'")
                        }
                    } catch (t: Throwable) {
                        mapOf("error" to ("tool handler error: " + (t.message ?: t.toString())))
                    }

                    onToolCallFinished(toolName, result)

                    // Chain follow-up for THIS SAME TURN using previousResponseId.
                    // We must close this stream before starting the follow-up.
                    chained.set(true)
                    try { stream.close() } catch (_: Throwable) {}

                    val fnOut = ResponseInputItem.FunctionCallOutput.builder()
                        .callId(callId)
                        .outputAsJson(result!!)
                        .build()

                    val follow = ResponseCreateParams.builder()
                        .model(model)
                        .previousResponseId(currentResponseId) // same turn
                        .input(
                            ResponseCreateParams.Input.ofResponse(
                                listOf(ResponseInputItem.ofFunctionCallOutput(fnOut))
                            )
                        )
                        .maxOutputTokens(1024L)
                        .apply {
                            tools.forEach { entry ->
                                @Suppress("UNCHECKED_CAST")
                                addTool(entry.schema as Class<Any>)
                            }
                        }
                        .build()

                    print(" >> forward ")
                    runTurn(
                        firstParams = follow,
                        tools = tools,
                        onDelta = onDelta,
                        onDone = onDone,
                        onToolCallStarted = onToolCallStarted,
                        onToolCallFinished = onToolCallFinished
                    )
                }
            }

            // terminal signals
            ev.completed().ifPresent {
                if (!chained.get()) safeFinalize()
            }
            ev.failed().ifPresent {
                System.err.println("AI2: stream failed: $it")
                if (!chained.get()) safeFinalize()
            }
            ev.error().ifPresent {
                System.err.println("AI2: stream error: $it")
                if (!chained.get()) safeFinalize()
            }
        }

        // safety net: always finalize when the stream ends
        stream.onCompleteFuture().thenRun {
            if (!chained.get()) {
                safeFinalize()
            }
        }
    }
}

/* ------------------------------ Tiny CLI loop ------------------------------ */

fun runCli(ai: AI2, tools: List<ToolEntry<out Any>>) {
    val reader = System.`in`.bufferedReader()
    println("Chat REPL. Type ':q' to quit.")
    while (true) {
        print("\nYou> "); System.out.flush()
        val line = reader.readLine() ?: break
        if (line.trim() == ":q") break
        if (line.isBlank()) continue

        val latch = CountDownLatch(1)
        ai.converse(
            userInput = line,
            tools = tools,
            onDelta = { print(it); System.out.flush() },
            onDone = { println(); latch.countDown() },
            onToolCallStarted = { n -> println("\n[tool $n ->]") },
            onToolCallFinished = { n, _ -> println("[/tool $n]") }
        )
        latch.await()
    }
}

// --- Minimal HTML → ANSI mapper (fallback) ---
// If you’d prefer plain text, set USE_ANSI = false and it will strip tags.
const val USE_ANSI = true
const val RESET = "\u001B[0m"

fun decodeBasicEntities(s: String): String =
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

fun stripTags(html: String): String =
    html.replace(Regex("<[^>]+>"), "")

fun htmlToAnsi(html: String): String {
    if (!USE_ANSI) return decodeBasicEntities(stripTags(html))

    var s = html

    // Line breaks & blocks
    s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?i)</p>"), "\n\n")
    s = s.replace(Regex("(?i)<p[^>]*>"), "")

    // Lists (very minimal)
    s = s.replace(Regex("(?i)<li[^>]*>"), " • ")
    s = s.replace(Regex("(?i)</li>"), "\n")
    s = s.replace(Regex("(?i)</(ul|ol)>"), "\n")
    s = s.replace(Regex("(?i)<(ul|ol)[^>]*>"), "")

    // Headers: bold + extra spacing
    s = s.replace(Regex("(?i)<h[1-6][^>]*>"), "\u001B[1m")
    s = s.replace(Regex("(?i)</h[1-6]>"), "$RESET\n")

    // Emphasis
    s = s.replace(Regex("(?i)<(strong|b)[^>]*>"), "\u001B[1m")
    s = s.replace(Regex("(?i)</(strong|b)>"), "\u001B[22m") // reset bold only

    s = s.replace(Regex("(?i)<(em|i)[^>]*>"), "\u001B[3m")
    s = s.replace(Regex("(?i)</(em|i)>"), "\u001B[23m") // reset italics only

    s = s.replace(Regex("(?i)<u[^>]*>"), "\u001B[4m")
    s = s.replace(Regex("(?i)</u>"), "\u001B[24m") // reset underline only

    // Code spans/blocks (inverse)
    s = s.replace(Regex("(?i)<code[^>]*>"), "\u001B[7m")
    s = s.replace(Regex("(?i)</code>"), "\u001B[27m")

    s = s.replace(Regex("(?i)<pre[^>]*>"), "\u001B[7m")
    s = s.replace(Regex("(?i)</pre>"), "\u001B[27m\n")

    // Links: just underline the text
    s = s.replace(Regex("(?i)<a\\b[^>]*>"), "\u001B[4m")
    s = s.replace(Regex("(?i)</a>"), "\u001B[24m")

    // Drop any other tags
    s = s.replace(Regex("<[^>]+>"), "")

    s = decodeBasicEntities(s)
    return s
}

// --- Example tool schema (you can remove if you already have one) ---
@com.fasterxml.jackson.annotation.JsonClassDescription("Get current weather for a city.")
class GetWeather {
    @com.fasterxml.jackson.annotation.JsonPropertyDescription("City name, e.g. 'Paris'")
    var city: String? = null

    @com.fasterxml.jackson.annotation.JsonPropertyDescription("Units: 'c' for Celsius or 'f' for Fahrenheit")
    var unit: String? = "c"

    fun execute(): Map<String, Any> {
        val u = (unit ?: "c").lowercase()
        val tempC = 21.5
        val temp = if (u == "f") tempC * 9 / 5 + 32 else tempC
        return mapOf(
            "city" to (city ?: "Unknown"),
            "unit" to u,
            "temperature" to String.format("%.1f%s", temp, if (u == "f") "°F" else "°C"),
            "source" to "demo-stub"
        )
    }
}

// Convenience printer that only writes the newly-added suffix
fun printDelta(prev: String, curr: String) {
    if (curr.startsWith(prev)) {
        val extra = curr.substring(prev.length)
        if (extra.isNotEmpty()) {
            print(extra)
            System.out.flush()
        }
    } else {
//        println("-- not prefix `$prev` `$curr`")
        // fallback if something rewrote earlier text
        print("\n$curr")
        System.out.flush()
    }
}


