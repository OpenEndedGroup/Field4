package afield.ai

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.helpers.ResponseAccumulator
import com.openai.models.ChatModel
import com.openai.models.files.FileCreateParams
import com.openai.models.files.FilePurpose
import com.openai.models.responses.*
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.*
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/* ============================== Constants ============================== */

private const val MAX_TOOL_ROUNDS = 4

/* ============================== Annotations & Modes ============================== */

enum class ReturnImageMode { FILE_UPLOAD, DATA_URL }

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReturnsImage(
    val mime: String = "image/png",
    val caption: String = "",
    val mode: ReturnImageMode = ReturnImageMode.FILE_UPLOAD,
    val detail: ResponseInputImage.Detail.Known = ResponseInputImage.Detail.Known.AUTO
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolName(val value: String)

/* ============================== Data Types ============================== */

data class ImageSpec(
    val url: String? = null,                            // "https://…" or "data:image/png;base64,…"
    val fileId: String? = null,                         // Files API id
    val detail: ResponseInputImage.Detail = ResponseInputImage.Detail.AUTO
)

data class ToolReturn(
    val json: Any,                                      // function_call_output payload (Map/POJO/String/etc.)
    val images: List<ImageSpec> = emptyList()           // images to attach so the model “sees” pixels next pass
)


data class McpEndpoint(
    val serverLabel: String,              // a short name the model may see
    val serverUrl: String,                // full Streamable HTTP MCP URL
    val requireApproval: String? = null,  // "never" | "always" | "auto" (if supported by your SDK)
    val allowedTools: List<String>? = null, val headers: Map<String, String>? = null
)

data class ConversationState(
    val inputs: MutableList<ResponseInputItem> = mutableListOf(),
    var model: ChatModel = ChatModel.GPT_4O,
    var instructions: String? = null,
    var previousResponseId: String? = null,
    val toolRegistry: MutableMap<String, Class<*>> = linkedMapOf(),
    val mcpServers: MutableList<McpEndpoint> = mutableListOf()   // <— NEW
)

fun ConversationState.registerMcp(
    label: String,
    url: String,
    requireApproval: String? = "never",
    allowedTools: List<String>? = null,
    headers: Map<String, String>? = null
) {
    mcpServers += McpEndpoint(label, url, requireApproval, allowedTools, headers)
}

data class ConverseResult(val response: Response, val text: String)

data class ModelImageInfo(val source: String, val detail: String?)

/* ============================== Small Helpers ============================== */

private fun userRole(): ResponseInputItem.Message.Role = ResponseInputItem.Message.Role.of("user")

private fun wrapDetailKnown(k: ResponseInputImage.Detail.Known): ResponseInputImage.Detail =
    ResponseInputImage.Detail.of(k.name.lowercase())

/** Convert common return shapes to image bytes. */
private fun toImageBytes(result: Any, mime: String): ByteArray = when (result) {
    is ByteArray -> result
    is BufferedImage -> ByteArrayOutputStream().use { out ->
        val fmt = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"; else -> "png"
        }
        require(ImageIO.write(result, fmt, out)) { "ImageIO.write failed" }
        out.toByteArray()
    }

    is java.io.File -> Files.readAllBytes(result.toPath())
    is java.nio.file.Path -> Files.readAllBytes(result)
    is String -> {
        // Only accept data URLs here (no fetching of http(s) in this helper).
        require(result.startsWith("data:image/")) { "String must be a data URL for image content" }
        val comma = result.indexOf(',')
        require(comma > 0) { "Invalid data URL" }
        Base64.getDecoder().decode(result.substring(comma + 1))
    }

    else -> error("Unsupported image return type: ${result::class.java.name}")
}

private fun toDataUrl(bytes: ByteArray, mime: String): String =
    "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

inline fun <T> withRetries(times: Int = 3, block: () -> T): T {
    var last: Throwable? = null
    repeat(times) {
        try {
            return block()
        } catch (t: Throwable) {
            last = t
            if (it < times - 1) Thread.sleep((200L shl it)) // 200, 400, 800 ms
        }
    }
    throw last ?: IllegalStateException("unknown failure")
}

private fun describeImageSpecs(images: List<ImageSpec>): List<String> = images.mapIndexed { i, img ->
    val src = when {
        img.fileId != null -> "fileId=${img.fileId}"
        img.url?.startsWith("data:") == true -> "data:url(${img.url.take(24)}…)"
        img.url != null -> "url=${img.url}"
        else -> "unknown"
    }
    "• [input #$i] $src, detail=${img.detail}"
}

/* ============================== Tool Registry ============================== */

private fun toolNameOf(clazz: Class<*>): String = clazz.getAnnotation(ToolName::class.java)?.value ?: clazz.simpleName

fun ConversationState.registerTools(vararg tools: Class<*>) {
    tools.forEach { toolRegistry[toolNameOf(it)] = it }
}

/* ============================== Building Requests ============================== */

private fun buildParams(state: ConversationState): ResponseCreateParams {
    val builder = ResponseCreateParams.builder().model(state.model).store(true)
        .input(ResponseCreateParams.Input.ofResponse(state.inputs))

    state.instructions?.let { builder.instructions(it) }
    state.previousResponseId?.let { builder.previousResponseId(it) }
    state.toolRegistry.values.forEach { builder.addTool(it) }

    state.mcpServers.forEach { m ->
        val mcp = Tool.Mcp.builder().serverLabel(m.serverLabel).serverUrl(m.serverUrl).build()
        builder.addTool(mcp)
    }

    return builder.build()
}

private fun makeUserMessage(text: String, images: List<ImageSpec>): ResponseInputItem {
    val msg = ResponseInputItem.Message.builder().role(userRole()).addInputTextContent(text).apply {
        images.forEach { img ->
            val ib = ResponseInputImage.builder().detail(img.detail)
            img.url?.let { ib.imageUrl(it) }
            img.fileId?.let { ib.fileId(it) }
            addContent(ib.build())
        }
    }.build()
    return ResponseInputItem.ofMessage(msg)
}

/* ============================== Streaming ============================== */

private fun streamOnce(
    client: OpenAIClient, state: ConversationState, params: ResponseCreateParams, onText: (String) -> Unit
): Response {
    val acc = ResponseAccumulator.create()

    client.async().responses().createStreaming(params).subscribe({ event ->
        acc.accumulate(event)

        event.created().ifPresent { created ->
            state.previousResponseId = created.response().id()
        }

        event.outputTextDelta().ifPresent { delta ->
            try {
                onText(delta.delta())
            } catch (_: Throwable) { /* keep stream alive */
            }
        }

        event.completed().ifPresent { println() }
    }).onCompleteFuture().join()

    val resp = acc.response()
    state.previousResponseId = resp.id()
    return resp
}

/* ============================== Tool Execution ============================== */

private fun resolveToolClass(state: ConversationState, name: String): Class<*>? =
    state.toolRegistry[name] ?: state.toolRegistry.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun toolReturnFromExecuteResult(client: OpenAIClient, execMethod: Method, result: Any?): ToolReturn {
    val ann = execMethod.getAnnotation(ReturnsImage::class.java)
    if (ann == null) return ToolReturn(json = result ?: emptyMap<String, Any>())

    requireNotNull(result) { "@ReturnsImage execute() returned null" }
    return when (ann.mode) {
        ReturnImageMode.FILE_UPLOAD -> {
            val bytes = toImageBytes(result, ann.mime)
            val fileId = uploadImage(client, bytes)
            ToolReturn(
                json = mapOf("file_id" to fileId, "mime" to ann.mime, "caption" to ann.caption),
                images = listOf(ImageSpec(fileId = fileId, detail = wrapDetailKnown(ann.detail)))
            )
        }

        ReturnImageMode.DATA_URL -> {
            val bytes = toImageBytes(result, ann.mime)
            val url = toDataUrl(bytes, ann.mime)
            ToolReturn(
                json = mapOf("image_url" to url, "mime" to ann.mime, "caption" to ann.caption),
                images = listOf(ImageSpec(url = url, detail = wrapDetailKnown(ann.detail)))
            )
        }
    }
}

private fun uploadImage(client: OpenAIClient, bytes: ByteArray): String {
    val tmp = Files.createTempFile("tool-image-", ".png")
    try {
        Files.write(tmp, bytes)
        val file = client.files().create(
            FileCreateParams.builder().purpose(FilePurpose.VISION)   // vision inputs for models
                .file(tmp).build()
        )
        return file.id()
    } finally {
        try {
            Files.deleteIfExists(tmp)
        } catch (_: Exception) {
        }
    }
}

private fun executeTools(
    client: OpenAIClient, state: ConversationState, calls: List<ResponseFunctionToolCall>
): List<Pair<ResponseFunctionToolCall, ToolReturn>> = calls.map { call ->
    val name = call.name()
    val clazz =
        resolveToolClass(state, name) ?: return@map call to ToolReturn(json = mapOf("error" to "Unknown tool: $name"))

    val exec: Method =
        clazz.methods.firstOrNull { it.name == "execute" && it.parameterCount == 0 } ?: return@map call to ToolReturn(
            json = mapOf("error" to "Tool $name has no zero-arg execute()")
        )

    @Suppress("UNCHECKED_CAST") val argsObj: Any = try {
        call.arguments(clazz as Class<Any>)
    } catch (t: Throwable) {
        return@map call to ToolReturn(json = mapOf("error" to "Failed to parse args for $name: ${t.message}"))
    }

    val out: Any? = try {
        exec.invoke(argsObj)
    } catch (t: Throwable) {
        return@map call to ToolReturn(json = mapOf("error" to "Execution failed for $name: ${t.message}"))
    }

    call to toolReturnFromExecuteResult(client, exec, out)
}

/* ============================== Model Image Introspection (demo) ============================== */

private fun <T> optOrNull(any: Any?): T? = (any as? java.util.Optional<*>)?.orElse(null) as? T

private fun callReflect(obj: Any, name: String): Any? =
    obj.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { m ->
        m.isAccessible = true
        runCatching { m.invoke(obj) }.getOrNull()
    }

private fun collectModelImages(resp: Response): List<ModelImageInfo> {
    val out = mutableListOf<ModelImageInfo>()
    resp.output().forEach { item ->
        item.message().ifPresent { msg ->
            msg.content().forEach { part ->
                val outputImageOpt = callReflect(part, "outputImage")
                val imageObj = optOrNull<Any>(outputImageOpt) ?: callReflect(part, "image") ?: return@forEach
                val url = optOrNull<String>(callReflect(imageObj, "imageUrl")) ?: optOrNull<String>(
                    callReflect(
                        imageObj, "url"
                    )
                )
                val fileId = optOrNull<String>(callReflect(imageObj, "fileId"))
                val detail = callReflect(imageObj, "detail")?.toString()
                val source = when {
                    url != null -> url
                    fileId != null -> "file:$fileId"
                    else -> "unknown"
                }
                out += ModelImageInfo(source, detail)
            }
        }
    }
    return out
}

private fun printModelImages(resp: Response) {
    val imgs = collectModelImages(resp)
    if (imgs.isEmpty()) {
        println("[demo] model returned no image outputs.")
    } else {
        println("[demo] model returned ${imgs.size} image output(s):")
        imgs.forEachIndexed { i, it ->
            val src = if (it.source.startsWith("data:")) "data:url(${it.source.take(24)}…)" else it.source
            println("• [output #$i] $src, detail=${it.detail}")
        }
    }
}

/* ============================== Tools (examples) ============================== */

@JsonClassDescription("Gets the current time. Defaults to UTC.")
class GetTime {
    @JsonPropertyDescription("IANA zone like 'Europe/London'.")
    var zone: String? = null
    fun execute(): Map<String, Any> {
        val zid = try {
            java.time.ZoneId.of(zone ?: "UTC")
        } catch (_: Exception) {
            java.time.ZoneId.of("UTC")
        }
        val now = java.time.OffsetDateTime.now(zid)
        return mapOf("iso" to now.toString(), "zone" to zid.id)
    }
}

@JsonClassDescription("Adds two numbers.")
class Add {
    var a: Double = 0.0
    var b: Double = 0.0
    fun execute(): Map<String, Double> = mapOf("sum" to (a + b))
}

@JsonClassDescription("Draws attention to a file_id (an image, the result of a tool and so on)")
class NoteFileID {
    @JsonPropertyDescription("The file_id you want the user to pay attention to")
    var file_id: String? = null
    fun execute(): Map<String, String> {
        println("file id: $file_id")
        return mapOf("noted" to (file_id ?: ""))
    }
}

@JsonClassDescription("Generate a simple swirl PNG.")
class MakeSwirl {
    @JsonPropertyDescription("Canvas width in pixels")
    var width: Int = 256

    @JsonPropertyDescription("Canvas height in pixels")
    var height: Int = 256

    @ReturnsImage(mime = "image/png", mode = ReturnImageMode.FILE_UPLOAD, caption = "Swirl")
    fun execute(): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.background = java.awt.Color(0, 0, 0, 0)
        g.clearRect(0, 0, width, height)
        val cx = width / 2.0
        val cy = height / 2.0
        val radius = minOf(width, height) / 2.2
        for (i in 0 until 2000) {
            val t = i / 2000.0 * 12.0
            val r = radius * i / 2000.0
            val x = (cx + r * cos(t)).toInt()
            val y = (cy + r * sin(t)).toInt()
            val c = java.awt.Color.getHSBColor((i / 2000.0f), 0.9f, 0.95f).rgb
            if (x in 0 until width && y in 0 until height) img.setRGB(x, y, c)
        }
        g.dispose()
        return img
    }
}

/* ============================== Conversation Orchestration ============================== */

private fun findToolCalls(resp: Response): List<ResponseFunctionToolCall> =
    resp.output().mapNotNull { it.functionCall().orElse(null) }

/**
 * Streams partial text via `onText`, handles tool calls (possibly multiple rounds),
 * and returns the final Response + concatenated text. Subsequent calls continue the conversation.
 */
fun converse(
    client: OpenAIClient, state: ConversationState, userText: String, onText: (String) -> Unit = {}
): ConverseResult {
    // fresh inputs for this turn: user message
    state.inputs.clear()
    state.inputs += makeUserMessage(userText, emptyList())

    // first pass (may request tools)
    var response = withRetries { streamOnce(client, state, buildParams(state), onText) }
    val seenCallIds = mutableSetOf<String>()
    var rounds = 0

    // tool loop
    while (true) {
        if (++rounds > MAX_TOOL_ROUNDS) {
            println("warning: too many tool calls")
            break
        }

        val calls = findToolCalls(response).filter { seenCallIds.add(it.callId()) }
        if (calls.isEmpty()) break

        val results = executeTools(client, state, calls)

        // rebuild inputs: tool outputs (+ optional attached images)
        state.inputs.clear()
        results.forEach { (fnCall, toolReturn) ->
            state.inputs += ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder().callId(fnCall.callId()).outputAsJson(toolReturn.json)
                    .build()
            )
        }

        val imagesToAttach = results.flatMap { it.second.images }
        if (imagesToAttach.isNotEmpty()) {
            describeImageSpecs(imagesToAttach).forEach { println("[tool attachment] $it") }
            state.inputs += makeUserMessage(
                text = "Tool-provided image(s) for the previous step.", images = imagesToAttach
            )
        }

        response = streamOnce(client, state, buildParams(state), onText)
    }

    // collect final assistant text
    val fullText = response.output().mapNotNull { it.message().orElse(null) }.flatMap { it.content() }
        .mapNotNull { it.outputText().orElse(null) }.joinToString("") { it.text() }

    return ConverseResult(response, fullText)
}

class AI3 {

    val instructions = """
            You are a code writing assistant writing code in Kotlin, Javascript or Python, you only answer with complete Kotlin, Javascript or Python function bodies or fragments. Please help the user write some Kotlin or Javascript code to complete the request. There is already code in this function body before <cursor> and code after <cursor>, keep this code as much as you can. Return only complete edited code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin or Javascript function body ready to run and nothing else. Try to pull repeated numerical constants out into appropriately named variables or parameters.
            For example, if asked to complete:
            
            import foo
            /*preamble*/ fun SCRIPT() {
            print("hello <cursor>
            /*postamble*/}
            
            you might return
            
            import foo
            /*preamble*/ fun SCRIPT() {
            print("hello world")
            /*postamble*/}
            
            if given a range demarcated by <rewrite> and </rewrite> you can rewrite that code given the comment above it. For example, given:
            
            import foo
            /*preamble*/ fun SCRIPT() {
            // make this more glum
            <rewrite>print("hello world")</rewrite>
            /*postamble*/}
            
            you might return
            
            import foo
            /*preamble*/ fun SCRIPT() {
            // make this more glum
            print("goodbye world")
            /*postamble*/}
            
            Similar formatting will be provided for code in Python or Javascript. Comments inside code are welcome, but only if they significantly add to the understanding of the code; don't repeat yourself. Match braces, return only working code.
            """.trimIndent()

    lateinit var conversation: ConversationState
    lateinit var client: OpenAIClient

    fun init() {
        System.setProperty("java.awt.headless", "true")
        client = OpenAIOkHttpClient.builder().apiKey(System.getenv("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY"))
            .build()

        conversation = ConversationState(
            model = ChatModel.GPT_4O, instructions = instructions
        ).apply {
            registerTools(
                // todo
            )
        }
    }

    var markerStart = "/*preamble*/fun SCRIPT() {\n"
    var shortMarkerStart = "fun SCRIPT() {\n"
    var markerEnd = "/*postamble*/}\n"
    var shortMarkerEnd = "}"
    var cursor = "<cursor>\n"

    var rewriteBegin = "<rewrite>\n"
    var rewriteEnd = "</rewrite>\n"


    fun cursorify(contents: String, endOfLine: Int = -1): String {
        val lines = contents.split("\n")

        val preamble = lines.indexOfLast {
            it.lowercase().trim().startsWith("import ") || it.lowercase().trim().startsWith("@file")
        }


        if (endOfLine==-1) {
            val pre = lines.subList(0, preamble + 1)
            val before = lines.subList(preamble + 1, lines.size)
            return pre.joinToString(separator = "\n") + markerStart + before.joinToString(separator = "\n") + markerEnd
        }
        else {
            val pre = lines.subList(0, preamble + 1)
            val before = lines.subList(preamble + 1, Math.min(lines.size, endOfLine + 1))
            val after = lines.subList(Math.min(lines.size, endOfLine + 1), lines.size)

            return pre.joinToString(separator = "\n") + markerStart + before.joinToString(separator = "\n") + cursor + "\n" + after.joinToString(
                separator = "\n"
            ) + markerEnd
        }
    }

    fun uncursorify(contents: String): String {
        if (contents.indexOf(cursor) != -1) throw MalformedAI("output shouldn't still have a <cursor> tag in it")
        // assumption -- this doesn't have <cursor> in it

        val a = contents.indexOf(shortMarkerStart)
        val b = contents.lastIndexOf(shortMarkerEnd)

        if (a == -1) throw MalformedAI(" couldn't find marker for $shortMarkerStart in $contents")
        if (b == -1) throw MalformedAI(" couldn't find marker for $shortMarkerEnd and $contents")

        val preamble = contents.substring(0, a).replace("/*preamble*/", "")
        val meat = contents.substring(a + shortMarkerStart.length, b).replace("/*postamble*/", "")

        return (preamble + "\n" + meat).trimIndent().trim()
    }

    companion object {
        val defaultInstructions =
            "Please help the user write some Kotlin code to complete the request. Make sure you balance your braces. There is already code in this file before <cursor> and code after <cursor>. Return only complete edited code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin file ready to run and nothing else."
        val rewriteInstructions =
            "Please help the user write some Kotlin code to complete the request. Make sure you balance your braces. There is already code in this file, you should rewrite the section between <rewrite> and </rewrite>. Return only complete edited code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin file ready to run and nothing else."
        val errorInstructions =
            "Please help the user fix some Kotlin code to complete the request. Make sure you balance your braces. There is already code in this file but there is an error at the point in the file indicated by <error> and </error>. Return only complete fixed code, do not use backticks or any other kind of markup. The returned code must be a complete Kotlin file ready to run and nothing else."
        val chatInstructions =
            "Please help the user fix and extend some Kotlin code to complete the request. Make sure you balance your braces. Return only complete, edited code, do not use backticks or any other kind of markup, do not supply a diff. The returned code must be a complete Kotlin file ready to run and nothing else. The user has asked for specific help starting at a particular line. That help is the message `<<HELP>>`, and this position is marked inside the code with `<help>`. Take care to focus your edits to the text to that point and after, as much as that makes sense given the language."
    }

    fun wrap(lang : String = "Kotlin", instruct: String = defaultInstructions.replace("Kotlin", lang), code: String): String {
        return instruct+"\n```\n"+cursorify(code, -1)+"\n```"
    }

    fun unwrap(contents: String): String {
        return uncursorify(contents)
    }

    fun runPrompt(prompt: String): ConverseResult {
        val r = converse(client, conversation, prompt) { print(it); System.out.flush() }
        return r
    }
}

/* ============================== Demos ============================== */

fun main() {
    System.setProperty("java.awt.headless", "true")
    val client: OpenAIClient =
        OpenAIOkHttpClient.builder().apiKey(System.getenv("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY")).build()

    val convo = ConversationState(
        model = ChatModel.GPT_4O,
        instructions = "You are a concise Kotlin-savvy assistant that uses tools when helpful. " + "When returning images, do not inline base64. If you want the user to see a file, " + "automatically call the appropriate tool to display it to the user before finishing the conversation. " + "Do not refer to images or files that this tool hasn't been given."
    ).apply {
        registerTools(
            GetTime::class.java, Add::class.java, NoteFileID::class.java, MakeSwirl::class.java
        )
    }

    val reader = BufferedReader(System.`in`.reader())
    while (true) {
        print("|> "); System.out.flush()
        val line = reader.readLine() ?: break
        val r = converse(client, convo, line) { print(it); System.out.flush() }
        printModelImages(r.response)
    }
}

fun main_smokeTest() {
    val client: OpenAIClient =
        OpenAIOkHttpClient.builder().apiKey(System.getenv("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY")).build()

    val convo = ConversationState(
        model = ChatModel.GPT_4O,
        instructions = "You are a concise Kotlin-savvy assistant that uses tools when helpful."
    ).apply {
        registerTools(GetTime::class.java, Add::class.java, NoteFileID::class.java, MakeSwirl::class.java)
    }

    val r1 = converse(
        client, convo, "What's the exact time in Europe/London right now? Use the GetTime tool."
    ) { print("$it--") }
    println("\n\n[final #1]\n${r1.text}")

    val r2 = converse(client, convo, "Take the number of minutes of that time and add 100 to it. Use the Add tool.") {
        print("$it..")
    }
    println("\n\n[final #2]\n${r2.text}")

    val r3 =
        converse(client, convo, "Generate a colorful swirl image and then tell me what you drew.") { print("$it..") }
    println("\n\n[final #3]\n${r3.text}")
}
