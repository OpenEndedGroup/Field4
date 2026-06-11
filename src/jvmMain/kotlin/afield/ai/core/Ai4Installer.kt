package afield.ai.core

import afield.*
import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.RelatedContextDiscovery
import afield.ai.searchWorkspaceSymbols
import afield.four.Renderer
import afield.four.util.Window
import afield.four.util.lastWindowToOpen
import afield.four.util.openWindowsSnapshot
import afield.logger.lprintln
import afield.macros.checkAtticAndExecute
import afield.macros.checkIntroductionAndExecute
import afield.macros.checkPreambleAndExecute
import afield.macros.gatherPreambleUpwards
import afield.stdlib.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.api.ScriptDiagnostic

private val ai4Scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val AI4_CONNECTED_RENDER_EXAMPLE_REF = "afield://ai4-examples/connected-compute-render"

private val AI4_CONNECTED_RENDER_EXAMPLE = """
# AI4 example: connected compute box and render box

When a user asks for both particle motion and visual appearance changes, inspect the graph instead of assuming one file owns everything.

Typical layout:
- Current box: Kotlin `code` creates `computeModuleHere`, `ComputeMesh`, ping-pong buffers, and `prop<FrameBufferGroup>(outputPositions) = outData`.
- Current box: `compute` shader updates particle positions or velocity. Speed changes usually belong here, or in Kotlin params such as `m.params`.
- Connected render box: Kotlin `code` creates a graphics `moduleHere`, `Mesh`, and reads the exported positions with `m.inData = { outputPositions }`.
- Connected render box: `fragment` shader sets `uFragColor`. Color, alpha, contrast, blue/orange palette, and particle edge treatment usually belong here.

Preamble clue:
- `Box.outputPositions by Inheritance...` means `outputPositions` is a graph-relevant Field property; inspect parents/children that mention `outputPositions`.
- Common inherited setup properties can be useful for understanding how the scene is wired, but are weaker graph-discovery clues than domain-specific names like `outputPositions`.
- `Mesh.inData by BoundFromStructure...`, `ComputeMesh.params by BoundFromStructure...`, `ModulePointer.someUniform by FromUniform...`, etc. are shader binding helpers. They explain how Kotlin talks to shader push constants/uniforms, but they are not by themselves evidence that the current box owns the value.

Good workflow:
1. Call `ListContents` early and look at both the current box and graph-connected boxes.
2. Edit the compute box for motion/speed.
3. Edit the connected render box's fragment shader for color/contrast.
4. After Kotlin edits, call `CompileKotlin` on the edited `code` ref and fix errors before finalizing.
5. After shader edits, call `ReloadShaders` for each affected shader or target box. Use `visualCheck=true` when screenshot context exists, inspect the post-reload screenshot, and keep editing/reloading if the visual result is not achieved.
""".trimIndent()

fun installAi4() {
    var bridge: OpenAiIdeCodexBridge? = null
    var helpers: IdeChatHelpers? = null
    var chatBridge: OpenAiIdeCodexBridge? = null
    var chatHelpers: IdeChatHelpers? = null
    val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val chatThreadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val chatHistoryByKey = ConcurrentHashMap<String, String>()
    val agentMemoryByKey = ConcurrentHashMap<String, AgentMemoryState>()

    fun langSpecForName(name: String): LanguageSpec = when {
        name.equals("Kotlin", ignoreCase = true) -> KOTLIN_SCRIPT
        name.equals(GLSL_VERTEX.name, ignoreCase = true) -> GLSL_VERTEX
        name.equals(GLSL_FRAGMENT.name, ignoreCase = true) -> GLSL_FRAGMENT
        name.equals(GLSL_MESH.name, ignoreCase = true) -> GLSL_MESH
        name.equals(GLSL_TASK.name, ignoreCase = true) -> GLSL_TASK
        name.equals(GLSL_COMPUTE.name, ignoreCase = true) -> GLSL_COMPUTE
        name.equals(SLANG_VERTEX.name, ignoreCase = true) -> SLANG_VERTEX
        name.equals(SLANG_FRAGMENT.name, ignoreCase = true) -> SLANG_FRAGMENT
        name.equals(SLANG_MESH.name, ignoreCase = true) -> SLANG_MESH
        name.equals(SLANG_TASK.name, ignoreCase = true) -> SLANG_TASK
        name.equals(SLANG_COMPUTE.name, ignoreCase = true) -> SLANG_COMPUTE
        name.startsWith("GLSL (ray", ignoreCase = true) -> GLSL_RAY_TRACING
        name.equals(GLSL_MISS.name, ignoreCase = true) -> GLSL_MISS
        name.equals(GLSL_CLOSEST_HIT.name, ignoreCase = true) -> GLSL_CLOSEST_HIT
        name.equals(GLSL_ANY_HIT.name, ignoreCase = true) -> GLSL_ANY_HIT
        name.equals(GLSL_INTERSECTION.name, ignoreCase = true) -> GLSL_INTERSECTION
        name.equals(GLSL_CALLABLE.name, ignoreCase = true) -> GLSL_CALLABLE
        name.startsWith("Slang (ray", ignoreCase = true) -> SLANG_RAY_TRACING
        name.equals(SLANG_MISS.name, ignoreCase = true) -> SLANG_MISS
        name.equals(SLANG_CLOSEST_HIT.name, ignoreCase = true) -> SLANG_CLOSEST_HIT
        name.equals(SLANG_ANY_HIT.name, ignoreCase = true) -> SLANG_ANY_HIT
        name.equals(SLANG_INTERSECTION.name, ignoreCase = true) -> SLANG_INTERSECTION
        name.equals(SLANG_CALLABLE.name, ignoreCase = true) -> SLANG_CALLABLE
        name.equals("nano", ignoreCase = true) -> NANO_ASM
        else -> LanguageSpec(
            name = name,
            wrap = { it.trimEnd() },
            unwrap = { it.trimEnd() }
        )
    }

    fun threadFor(fileKey: String, fileText: String): OpenAiIdeCodexBridge.ThreadState =
        threadByKey[fileKey] ?: bridge!!.startThread(fileKey, fileText).also {
            threadByKey[fileKey] = it
        }

    fun chatThreadFor(fileKey: String, fileText: String): OpenAiIdeCodexBridge.ThreadState =
        chatThreadByKey[fileKey] ?: chatBridge!!.startThread(
            fileKey,
            fileText,
            instructions = OpenAiIdeCodexBridge.defaultChatThreadInstructions
        ).also {
            chatThreadByKey[fileKey] = it
        }

    fun aiSetupMessage(t: Throwable): String {
        if (t is MissingOpenAiCredentialException) return t.message ?: "AI4 needs an OpenAI Platform key."
        return "AI4 could not initialize OpenAI support: ${t.message ?: t::class.java.simpleName}"
    }

    data class FileRef(val document: String, val uid: String, val property: String)

    fun documentName(): String = File(thisSessionInfo.get().filename).name

    fun fileRefFor(docName: String, uid: String, property: String): String =
        "afield://$docName/$uid/$property"

    fun parseFileRef(raw: String, defaultProperty: String? = null): FileRef? {
        if (!raw.startsWith("afield://")) return null
        val parts = raw.removePrefix("afield://").split("/", limit = 3)
        if (parts.size == 2 && defaultProperty != null) return FileRef(parts[0], parts[1], defaultProperty)
        if (parts.size != 3) return null
        return FileRef(parts[0], parts[1], parts[2])
    }

    fun addWindowsFromValue(value: Any?, windows: MutableList<Window>) {
        when (value) {
            is Window -> windows.add(value)
            is Renderer -> windows.addAll(value.windows)
        }
    }

    fun Box.hierarchyForChatScreenshot(): List<Box> {
        val seen = mutableSetOf<Box>()
        val queue = ArrayDeque<Box>()
        val ordered = mutableListOf<Box>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            ordered.add(next)
            next.parents.forEach { queue.add(it) }
        }
        return ordered
    }

    fun windowsVisibleFrom(box: Box?): List<Window> {
        val windows = mutableListOf<Window>()
        if (box != null) {
            box.hierarchyForChatScreenshot().forEach { candidate ->
                candidate.properties.values.forEach { addWindowsFromValue(it, windows) }
                runCatching {
                    candidate.ki.context.vars.values.forEach { addWindowsFromValue(it.value, windows) }
                }
            }
        }
        openWindowsSnapshot().forEach { windows.add(it) }
        lastWindowToOpen?.let { windows.add(it) }
        return windows.distinct()
    }

    fun chooseChatScreenshotWindow(box: Box?, target: String): Window? {
        val windows = windowsVisibleFrom(box).filter { window ->
            val renderer = window.associatedRenderer
            renderer != null && renderer.supportsDirectWindowCapture(window)
        }
        if (windows.isEmpty()) return null
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return windows.first()

        trimmed.toIntOrNull()?.let { index ->
            windows.getOrNull(index - 1)?.let { return it }
        }

        return windows.firstOrNull { it.title.contains(trimmed, ignoreCase = true) }
            ?: windows.first()
    }

    fun resolveChatScreenshotBox(uid: String, sessionInfo: SessionInformation?): Box? {
        val requestUid = uid.trim()
        if (requestUid.isBlank()) return sessionInfo?.root

        if (sessionInfo != null) {
            if (requestUid == "root" || requestUid == sessionInfo.root.id) return sessionInfo.root
            sessionInfo.boxes[requestUid]?.let { return it }
            return sessionInfo.root
        }

        return runCatching { box(requestUid, createIfMissing = false) }.getOrNull()
    }

    suspend fun captureChatScreenshot(
        request: AiChatScreenshotRequest,
        sessionInfo: SessionInformation? = thisSessionInfo.get()
    ): AiChatScreenshotResponse {
        val requestUid = request.uid.trim()
        val box = resolveChatScreenshotBox(requestUid, sessionInfo)
        if (box == null && requestUid.isNotBlank()) {
            lprintln("warning,ai4", "AI chat screenshot uid '$requestUid' was not found; trying open windows only")
        }
        if (box == null && requestUid.isBlank() && openWindowsSnapshot().isEmpty() && lastWindowToOpen == null) {
            return AiChatScreenshotResponse(
                success = false,
                message = "No active Field document or capturable afield.four Window for screenshot."
            )
        }

        val window = chooseChatScreenshotWindow(box, request.target) ?: return AiChatScreenshotResponse(
            success = false,
            message = if (requestUid.isNotBlank() && box == null) {
                "No capturable afield.four Window is visible; box '$requestUid' was not found in the active session."
            } else {
                "No capturable afield.four Window is visible from this box."
            }
        )
        val renderer = window.associatedRenderer ?: return AiChatScreenshotResponse(
            success = false,
            message = "The selected afield.four Window is not attached to a Renderer."
        )

        val out = Files.createTempFile("field4_ai_chat_screenshot_", ".jpg")
        try {
            renderer.captureWindowToFile(window, out.toString())
            val bytes = Files.readAllBytes(out)
            val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
            return AiChatScreenshotResponse(
                success = true,
                dataUrl = dataUrl,
                message = "Captured ${window.title} (${window.width} x ${window.height}; JPEG ${bytes.size / 1024} KiB)",
                title = window.title,
                width = window.width,
                height = window.height
            )
        } finally {
            runCatching { Files.deleteIfExists(out) }
        }
    }

    handlers["ai-chat-screenshot"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AiChatScreenshotRequest>(message.payload!!)
        val sessionInfo = thisSessionInfo.get()
        CoroutineScope(Runloop.miscPool.asCoroutineDispatcher()).launch {
            val response = runCatching {
                captureChatScreenshot(request, sessionInfo)
            }.getOrElse { throwable ->
                lprintln("warning,ai4", "AI chat screenshot failed: ${throwable.message}")
                AiChatScreenshotResponse(
                    success = false,
                    message = throwable.message ?: throwable::class.java.simpleName
                )
            }
            reply(Message("ai-chat-screenshot").payload(response))
        }
        Handled.yes
    })

    handlers["ai-assist"] = mutableSetOf({ message, _ ->
        val exec = Json.decodeFromJsonElement<AiAssist>(message.payload!!)
        val box = box(exec.uid)

        if (bridge == null) {
            val uiLog = NotifyLog()
            try {
                bridge = OpenAiIdeCodexBridge(log = uiLog)
                helpers = IdeChatHelpers(bridge!!, log = uiLog)
                notify("Initializing AI support v4", "AI4")
            } catch (t: Throwable) {
                val messageText = aiSetupMessage(t)
                lprintln("warning,ai4", messageText)
                notify(messageText, "AI4 setup")
                return@mutableSetOf Handled.yes
            }
        }

        if (chatBridge == null) {
            val uiLog = NotifyLog(label = "")
            try {
                chatBridge = OpenAiIdeCodexBridge(model = Ai4ModelDefaults.FLAGSHIP_MODEL, log = uiLog)
                chatHelpers = IdeChatHelpers(chatBridge!!, log = uiLog)
            } catch (t: Throwable) {
                val messageText = aiSetupMessage(t)
                lprintln("warning,ai4", messageText)
                notify(messageText, "AI4 setup")
                return@mutableSetOf Handled.yes
            }
        }

        val sessionInfo = thisSessionInfo.get()
        if (sessionInfo == null) {
            notify("AI4 unavailable: no active session context.", "AI4")
            return@mutableSetOf Handled.yes
        }

        val langName = languageNameForProperty(box, exec.property)
        val lang = langSpecForName(langName)
        val docName = File(sessionInfo.filename).name
        val docPath = sessionInfo.filename
        val escapedDocPath = Regex.escape(docPath)
        val fileKey = fileRefFor(docName, exec.uid, exec.property)
        val textProperties = linkedSetOf(
            "code",
            "vertex",
            "fragment",
            "vertex_fragment",
            "mesh",
            "task",
            "compute",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "vertex_s",
            "fragment_s",
            "mesh_s",
            "task_s",
            "compute_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s"
        ).apply {
            if (exec.property.isNotBlank()) add(exec.property)
        }

        fun languageNameForBoxProperty(target: Box, property: String): String = when (property) {
            "code" -> target.ke.languageName
            "vertex" -> "GLSL (vertex)"
            "fragment" -> "GLSL (fragment)"
            "vertex_fragment" -> "Slang (vertex+fragment)"
            "compute" -> "GLSL (compute)"
            "mesh" -> "GLSL (mesh)"
            "task" -> "GLSL (task)"
            "raygen" -> "GLSL (ray generation)"
            "miss" -> "GLSL (miss)"
            "closesthit" -> "GLSL (closest hit)"
            "anyhit" -> "GLSL (any hit)"
            "intersection" -> "GLSL (intersection)"
            "callable" -> "GLSL (callable)"
            "vertex_s" -> "Slang (vertex)"
            "fragment_s" -> "Slang (fragment)"
            "compute_s" -> "Slang (compute)"
            "mesh_s" -> "Slang (mesh)"
            "task_s" -> "Slang (task)"
            "raygen_s" -> "Slang (ray generation)"
            "miss_s" -> "Slang (miss)"
            "closesthit_s" -> "Slang (closest hit)"
            "anyhit_s" -> "Slang (any hit)"
            "intersection_s" -> "Slang (intersection)"
            "callable_s" -> "Slang (callable)"
            "customization", "remote_js" -> "JavaScript"
            "metadata" -> "YAML"
            else -> languageNameForProperty(target, property)
        }

        fun boxByIdOrNull(uid: String): Box? =
            if (uid == sessionInfo.root.id) sessionInfo.root else sessionInfo.boxes[uid]

        fun textPropertyContents(target: Box, property: String): String? =
            (target.getPropertyRaw(property) as? String)?.takeIf { it.isNotBlank() }

        fun boxLabel(target: Box): String =
            target.name.trim().ifBlank { target.id }

        fun resolveBoxByChatName(rawName: String): Box? {
            val requested = rawName.trim()
            if (requested.isBlank()) return null
            val boxes = (listOf(sessionInfo.root) + sessionInfo.boxes.values)
                .distinctBy { it.id }
            boxes.firstOrNull { it.id == requested }?.let { return it }
            boxes.firstOrNull { boxLabel(it) == requested }?.let { return it }
            boxes.firstOrNull { boxLabel(it).equals(requested, ignoreCase = true) }?.let { return it }
            val partial = boxes.filter { boxLabel(it).contains(requested, ignoreCase = true) }
            return partial.singleOrNull()
        }

        fun boxChatRef(rawName: String, rawProperty: String?): String? {
            val target = resolveBoxByChatName(rawName) ?: return null
            val property = rawProperty?.trim()?.takeIf { it.isNotBlank() } ?: "code"
            return fileRefFor(docName, target.id, property)
        }

        fun expandBoxSlashRefs(text: String): String {
            if (!text.contains("/box")) return text
            var expanded = text
            val quoted = Regex("""/box\s+"([^"]+)"(?:/([A-Za-z0-9_-]+))?""")
            expanded = quoted.replace(expanded) { match ->
                boxChatRef(match.groupValues[1], match.groupValues.getOrNull(2)) ?: match.value
            }
            val unquotedWithProperty = Regex("""/box\s+([^/\n]+?)/([A-Za-z0-9_-]+)""")
            expanded = unquotedWithProperty.replace(expanded) { match ->
                boxChatRef(match.groupValues[1], match.groupValues[2]) ?: match.value
            }
            val unquotedAtEnd = Regex("""/box\s+([^/\n]+?)\s*$""")
            expanded = unquotedAtEnd.replace(expanded) { match ->
                boxChatRef(match.groupValues[1], "code") ?: match.value
            }
            return expanded
        }

        val expandedExtraPrompt = expandBoxSlashRefs(exec.extraPrompt)
        val agentMemory = agentMemoryByKey.computeIfAbsent(fileKey) { AgentMemoryState() }

        val preamblePropertyRegex =
            Regex("""(?:var|val)\s+([A-Za-z0-9_.$]+)\.([A-Za-z_][A-Za-z0-9_]*)\s+by\s+([A-Za-z0-9_.$]+)(?:\s*<\s*([^>\n]+)\s*>)?""")

        fun preambleText(target: Box): String =
            (listOf("preamble", "_preamble")
                .mapNotNull { property -> target.getPropertyRaw(property) as? String }
                + listOfNotNull(runCatching { gatherPreambleUpwards(target) }.getOrNull()))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")

        fun preambleHintsForBox(target: Box, relation: String): List<PreamblePropertyHint> =
            preamblePropertyRegex.findAll(preambleText(target)).map { match ->
                val receiver = match.groupValues[1]
                val property = match.groupValues[2]
                val delegate = match.groupValues[3].substringAfterLast('.')
                PreamblePropertyHint(
                    property = property,
                    receiver = receiver,
                    delegate = delegate,
                    type = match.groupValues.getOrNull(4).orEmpty(),
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    relation = relation,
                    graphRelevant = receiver == "Box" && delegate.startsWith("Inheritance")
                )
            }.distinctBy { "${it.receiver}.${it.property}:${it.delegate}:${it.boxUid}" }.toList()

        fun inheritedBoxPropertyNames(target: Box): Set<String> =
            preambleHintsForBox(target, "current")
                .filter { it.graphRelevant }
                .map { it.property }
                .toSet()

        fun textForGraphCueSearch(target: Box): String =
            textProperties.mapNotNull { textPropertyContents(target, it) }.joinToString("\n") + "\n" + preambleText(target)

        data class RelatedBox(val box: Box, val relation: String)

        fun graphNeighborBoxes(target: Box): List<RelatedBox> {
            val seen = linkedMapOf<String, RelatedBox>()
            fun add(candidate: Box?, relation: String) {
                if (candidate == null) return
                if (candidate.id == target.id || candidate.id == "root") return
                val existing = seen[candidate.id]
                if (existing == null) {
                    seen[candidate.id] = RelatedBox(candidate, relation)
                } else if (!existing.relation.contains(relation)) {
                    seen[candidate.id] = RelatedBox(candidate, "${existing.relation}; $relation")
                }
            }
            target.parents.forEach { add(it, "connected parent of ${boxLabel(target)}") }
            target.children.forEach { add(it, "connected child of ${boxLabel(target)}") }
            sessionInfo.boxes.values.forEach { candidate ->
                if (candidate.parents.any { it.id == target.id }) add(candidate, "connected child of ${boxLabel(target)}")
                if (candidate.children.any { it.id == target.id }) add(candidate, "connected parent of ${boxLabel(target)}")
            }

            val inheritedNames = inheritedBoxPropertyNames(target)
            if (inheritedNames.isNotEmpty()) {
                sessionInfo.boxes.values.forEach { candidate ->
                    if (candidate.id == target.id || candidate.id == "root") return@forEach
                    val candidateText = textForGraphCueSearch(candidate)
                    val matches = inheritedNames
                        .filter { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(candidateText) }
                        .take(4)
                    if (matches.isNotEmpty()) {
                        add(candidate, "shares inherited Box propert${if (matches.size == 1) "y" else "ies"} ${matches.joinToString(", ")} with ${boxLabel(target)}")
                    }
                }
            }

            return seen.values.sortedBy { it.box.name.ifBlank { it.box.id } }
        }

        fun contentEntriesForBox(target: Box, relation: String): List<ListContentEntry> =
            textProperties.mapNotNull { property ->
                val content = textPropertyContents(target, property) ?: return@mapNotNull null
                ListContentEntry(
                    fileRef = fileRefFor(docName, target.id, property),
                    property = property,
                    language = languageNameForBoxProperty(target, property),
                    content = content,
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    relation = relation
                )
            }

        fun preambleHintDescription(target: Box): String {
            val hints = preambleHintsForBox(target, "current")
            val inherited = hints.filter { it.graphRelevant }.map { it.property }.distinct().take(8)
            val shaderDriven = hints
                .filter { !it.graphRelevant && (it.receiver.contains("Mesh") || it.receiver.contains("Module") || it.receiver == "Structure") }
                .map { "${it.receiver}.${it.property}" }
                .distinct()
                .take(8)
            return buildString {
                if (inherited.isNotEmpty()) {
                    append(" inherited Box properties: ")
                    append(inherited.joinToString(", "))
                    append(".")
                }
                if (shaderDriven.isNotEmpty()) {
                    append(" shader binding helpers: ")
                    append(shaderDriven.joinToString(", "))
                    append(".")
                }
            }.trim()
        }

        fun languageForFilePath(path: Path): String {
            val lower = path.fileName?.toString()?.lowercase().orEmpty()
            return when {
                lower.endsWith(".kt") || lower.endsWith(".kts") -> "Kotlin"
                lower.endsWith(".glsl") || lower.endsWith(".vert") || lower.endsWith(".frag")
                    || lower.endsWith(".comp") || lower.endsWith(".mesh") || lower.endsWith(".task")
                    || lower.endsWith(".rgen") || lower.endsWith(".rmiss") || lower.endsWith(".rchit")
                    || lower.endsWith(".rahit") || lower.endsWith(".rint") || lower.endsWith(".rcall") -> "GLSL"
                lower.endsWith(".slang") -> "Slang"
                lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") -> "JavaScript"
                lower.endsWith(".ts") || lower.endsWith(".tsx") -> "TypeScript"
                lower.endsWith(".java") -> "Java"
                lower.endsWith(".py") -> "Python"
                lower.endsWith(".md") -> "Markdown"
                lower.endsWith(".yaml") || lower.endsWith(".yml") -> "YAML"
                lower.endsWith(".json") -> "JSON"
                lower.endsWith(".html") -> "HTML"
                lower.endsWith(".css") -> "CSS"
                lower.endsWith(".xml") -> "XML"
                else -> "text"
            }
        }

        val listDefinitions = {
            fun definitionsForBox(target: Box): List<DefinitionInfo> {
                val langForBox = languageNameForProperty(target, "code")
                val fileRef = fileRefFor(docName, target.id, "code")
                val vars = target.ki.context.vars.values.map { v ->
                    DefinitionInfo(
                        name = v.name,
                        type = "variable",
                        fileRef = fileRef,
                        language = langForBox
                    )
                }
                val funcs = target.ki.context.functions.values.map { f ->
                    DefinitionInfo(
                        name = f.toString(true),
                        type = "function",
                        fileRef = fileRef,
                        language = langForBox
                    )
                }
                return vars + funcs
            }

            val currentDefs = definitionsForBox(box)
            val otherDefs = sessionInfo.boxes.values
                .filter { it.id != box.id }
                .sortedBy { it.name.ifBlank { it.id } }
                .flatMap { definitionsForBox(it) }

            ListDefinitionsResult(definitions = currentDefs + otherDefs)
        }

        val listFileContents = {
            val relatedBoxes = graphNeighborBoxes(box)
            val entries = buildList {
                addAll(contentEntriesForBox(box, "current box (${boxLabel(box)})"))
                relatedBoxes.forEach { related ->
                    addAll(contentEntriesForBox(related.box, related.relation))
                }
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("No text content available for box '${box.id}'.")
            }
            val hints = buildList {
                addAll(preambleHintsForBox(box, "current box (${boxLabel(box)})"))
                relatedBoxes.forEach { related ->
                    addAll(preambleHintsForBox(related.box, related.relation))
                }
            }
            val primary = entries.firstOrNull { it.boxUid == box.id && it.property == exec.property }
                ?: entries.firstOrNull { it.boxUid == box.id }
                ?: entries.first()
            notify("Listing ${entries.size} current/connected content property/properties for ${box.id}", "AI4")
            ListContentsResult(
                fileRef = primary.fileRef,
                content = primary.content,
                language = primary.language,
                contents = entries,
                preambleHints = hints
            )
        }

        val contextAttachments: List<ContextAttachment> = buildList {
            fun addTextPropertyAttachment(target: Box, property: String, description: String) {
                val content = textPropertyContents(target, property) ?: return
                val hintDescription = preambleHintDescription(target)
                val combinedDescription = listOf(description, hintDescription)
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
                add(
                    ContextAttachment(
                        fileName = fileRefFor(docName, target.id, property),
                        language = languageNameForBoxProperty(target, property),
                        code = content,
                        description = combinedDescription
                    )
                )
            }

            textProperties.forEach { property ->
                addTextPropertyAttachment(box, property, "current box ${boxLabel(box)} / $property")
            }

            val relatedBoxes = graphNeighborBoxes(box)
            relatedBoxes.forEach { related ->
                textProperties.forEach { property ->
                    addTextPropertyAttachment(
                        related.box,
                        property,
                        "${related.relation}; box ${boxLabel(related.box)} / $property"
                    )
                }
            }

            if (relatedBoxes.isNotEmpty() || exec.prompt.contains("outputPositions") || expandedExtraPrompt.contains("particles", ignoreCase = true)) {
                add(
                    ContextAttachment(
                        fileName = AI4_CONNECTED_RENDER_EXAMPLE_REF,
                        language = "Markdown",
                        code = AI4_CONNECTED_RENDER_EXAMPLE,
                        description = "example workflow for particle speed in a compute box plus color/contrast in a connected render box"
                    )
                )
            }

            val hasGraphicsProperties = listOf(
                "vertex",
                "fragment",
                "mesh",
                "task",
                "compute",
                "raygen",
                "miss",
                "closesthit",
                "anyhit",
                "intersection",
                "callable",
                "vertex_s",
                "fragment_s",
                "vertex_fragment",
                "mesh_s",
                "task_s",
                "compute_s",
                "raygen_s",
                "miss_s",
                "closesthit_s",
                "anyhit_s",
                "intersection_s",
                "callable_s"
            )
                .any { property -> textPropertyContents(box, property) != null }
            if (hasGraphicsProperties || exec.prompt.contains("moduleHere") || exec.prompt.contains("renderer")) {
                val guidePath = Paths.get(System.getProperty("user.dir")).resolve("src/VULKAN_GRAPHICS_SYSTEM.md")
                if (Files.exists(guidePath) && Files.isRegularFile(guidePath)) {
                    val guide = runCatching { Files.readString(guidePath, StandardCharsets.UTF_8) }.getOrNull()
                    if (!guide.isNullOrBlank()) {
                        add(
                            ContextAttachment(
                                fileName = guidePath.toString(),
                                language = "Markdown",
                                code = guide
                            )
                        )
                    }
                }
            }

            if (hasGraphicsProperties || glslSpecForProperty(exec.property) != null || expandedExtraPrompt.contains("include", ignoreCase = true)) {
                GlslIncludeLibraryIndex.contextAttachmentFor(expandedExtraPrompt.ifBlank { exec.prompt })?.let { add(it) }
            }
        }

        val resolveAttachment: (String) -> AttachmentResponse? = attachmentResolver@{ raw ->
            val requested = raw.trim()
            if (requested.isEmpty()) return@attachmentResolver null

            if (requested == AI4_CONNECTED_RENDER_EXAMPLE_REF) {
                return@attachmentResolver AttachmentResponse(
                    file = AI4_CONNECTED_RENDER_EXAMPLE_REF,
                    language = "Markdown",
                    content = AI4_CONNECTED_RENDER_EXAMPLE
                )
            }

            parseFileRef(requested)?.let { ref ->
                if (ref.document != docName) return@let
                val target = boxByIdOrNull(ref.uid) ?: return@let
                val content = textPropertyContents(target, ref.property) ?: return@let
                return@attachmentResolver AttachmentResponse(
                    file = fileRefFor(docName, target.id, ref.property),
                    language = languageNameForBoxProperty(target, ref.property),
                    content = content,
                    relatedExamples = listOf(AI4_CONNECTED_RENDER_EXAMPLE_REF)
                )
            }

            val requestedProperty = requested.removePrefix("/")
            if (requestedProperty in textProperties) {
                val content = textPropertyContents(box, requestedProperty) ?: return@attachmentResolver null
                return@attachmentResolver AttachmentResponse(
                    file = fileRefFor(docName, box.id, requestedProperty),
                    language = languageNameForBoxProperty(box, requestedProperty),
                    content = content
                )
            }

            GlslIncludeLibraryIndex.resolveAttachment(requested)?.let { return@attachmentResolver it }

            val candidatePaths = linkedSetOf<Path>()
            val requestedPath = runCatching { Paths.get(requested) }.getOrNull()
            if (requestedPath != null) {
                if (requestedPath.isAbsolute) {
                    candidatePaths.add(requestedPath.normalize())
                } else {
                    candidatePaths.add(Paths.get(System.getProperty("user.dir")).resolve(requestedPath).normalize())
                    val docDir = Paths.get(docPath).parent
                    if (docDir != null) {
                        candidatePaths.add(docDir.resolve(requestedPath).normalize())
                    }
                }
            }

            for (candidate in candidatePaths) {
                if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) continue
                val content = runCatching { Files.readString(candidate, StandardCharsets.UTF_8) }.getOrNull() ?: continue
                val related = RelatedContextDiscovery.forPath(candidate.toString())
                return@attachmentResolver AttachmentResponse(
                    file = candidate.toString(),
                    language = languageForFilePath(candidate),
                    content = content,
                    relatedGuides = related.guides,
                    relatedExamples = related.examples
                )
            }

            null
        }

        val editOtherFile: (EditFileRequest) -> EditFileResponse = editOtherFile@{ req ->
            notify("Editing ${req.file}", "AI4")
            val ref = parseFileRef(req.file)
                ?: return@editOtherFile EditFileResponse(false, "Unsupported file ref '${req.file}'")
            if (ref.document != docName) {
                return@editOtherFile EditFileResponse(false, "Unknown document '${ref.document}'")
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@editOtherFile EditFileResponse(false, "Unknown box '${ref.uid}'")
            setProvisional(docPath, target.id, ref.property, req.newContent)
            EditFileResponse(true, "Updated ${req.file}")
        }

        val workspaceSymbolSearch: (WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult = workspaceSymbolSearch@{ req ->
            val query = req.query.trim()
            if (query.isEmpty()) return@workspaceSymbolSearch WorkspaceSymbolSearchResult(emptyList())
            searchWorkspaceSymbols(
                index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex(),
                rawQuery = query,
                limit = req.limit
            )
        }

        fun diagnosticToCompileDiagnostic(diagnostic: ScriptDiagnostic): CompileDiagnostic {
            val location = diagnostic.location
            return CompileDiagnostic(
                severity = diagnostic.severity.name,
                message = diagnostic.message,
                line = location?.start?.line ?: -1,
                column = location?.start?.col ?: -1,
                endLine = location?.end?.line ?: -1,
                endColumn = location?.end?.col ?: -1,
                code = diagnostic.code.toString()
            )
        }

        data class SetupSnapshot(
            val preamble: String,
            val preambleCache: String,
            val intro: String,
            val introCache: String,
            val attic: String,
            val atticCache: String
        )

        fun setupSnapshot(target: Box): SetupSnapshot = SetupSnapshot(
            preamble = target.getPropertyRaw("preamble", "") as? String ?: "",
            preambleCache = target.getPropertyRaw("_preamble", "") as? String ?: "",
            intro = target.getPropertyRaw("introduction", "") as? String ?: "",
            introCache = target.getPropertyRaw("_introduction", "") as? String ?: "",
            attic = target.getPropertyRaw("attic", "") as? String ?: "",
            atticCache = target.getPropertyRaw("_attic", "") as? String ?: ""
        )

        fun setupChanges(before: SetupSnapshot, after: SetupSnapshot): List<SetupPropertyChange> = listOf(
            SetupPropertyChange(
                property = "preamble",
                changed = before.preamble != after.preamble,
                cacheChanged = before.preambleCache != after.preambleCache,
                beforeChars = before.preamble.length,
                afterChars = after.preamble.length
            ),
            SetupPropertyChange(
                property = "intro",
                changed = before.intro != after.intro,
                cacheChanged = before.introCache != after.introCache,
                beforeChars = before.intro.length,
                afterChars = after.intro.length
            ),
            SetupPropertyChange(
                property = "attic",
                changed = before.attic != after.attic,
                cacheChanged = before.atticCache != after.atticCache,
                beforeChars = before.attic.length,
                afterChars = after.attic.length
            )
        )

        fun requestedRefOrCurrent(raw: String, defaultProperty: String = exec.property.ifBlank { "code" }): FileRef {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return FileRef(docName, box.id, defaultProperty)
            parseFileRef(trimmed, defaultProperty = defaultProperty)?.let { return it }
            if (boxByIdOrNull(trimmed) != null) return FileRef(docName, trimmed, defaultProperty)
            return FileRef(docName, box.id, trimmed.removePrefix("/").ifBlank { defaultProperty })
        }

        fun stagedOrCurrentSource(target: Box, property: String, explicitSource: String?): String? {
            if (explicitSource != null) return explicitSource
            provisionalEntries(docPath)
                .firstOrNull { it.uid == target.id && it.property == property }
                ?.let { return it.contents }
            return textPropertyContents(target, property)
        }

        fun visualVerificationAvailable(): Boolean =
            exec.images.isNotEmpty() || chatHistoryByKey[fileKey].orEmpty().contains("data:image/")

        val compileKotlin: (CompileKotlinRequest) -> CompileKotlinResult = compileKotlin@{ req ->
            val ref = requestedRefOrCurrent(req.file, defaultProperty = "code")
            if (ref.document != docName) {
                return@compileKotlin CompileKotlinResult(
                    success = false,
                    file = req.file,
                    message = "Unknown document '${ref.document}'."
                )
            }
            if (ref.property != "code") {
                return@compileKotlin CompileKotlinResult(
                    success = false,
                    file = fileRefFor(docName, ref.uid, ref.property),
                    message = "CompileKotlin only supports the code property; got '${ref.property}'."
                )
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@compileKotlin CompileKotlinResult(
                    success = false,
                    file = fileRefFor(docName, ref.uid, ref.property),
                    message = "Unknown box '${ref.uid}'."
                )
            val source = stagedOrCurrentSource(target, ref.property, req.source)
                ?: return@compileKotlin CompileKotlinResult(
                    success = false,
                    file = fileRefFor(docName, target.id, ref.property),
                    message = "No Kotlin source is available for ${target.id}/${ref.property}."
                )

            notify("Compiling Kotlin for ${target.id}", "AI4")

            val diagnostics = mutableListOf<CompileDiagnostic>()
            val before = setupSnapshot(target)
            var snippetsCompiled = 0
            var failed = false

            runCatching {
                runBlocking {
                    Box.withBoxContext(target) {
                        val lines = source.trimEnd().lines()
                        val breaks = lines.mapIndexedNotNull { index, line ->
                            if (line.trim().startsWith("//%-") || line.trim().startsWith("/*%-*/")) index else null
                        } + lines.size

                        var at = 0
                        var lineCounter = 0
                        for (breakIndex in breaks) {
                            val snippetSource = lines.subList(at, breakIndex).joinToString("\n")
                            val expanded = if (KotlinEngineConfig.offloadCompilation) {
                                withContext(target.ki.compilationDispatcher) { target.ki.applyMacros(target, snippetSource) }
                            } else {
                                target.ki.applyMacros(target, snippetSource)
                            }
                            val compiledSource = expanded.split("\n").joinToString("\n") { line ->
                                lineCounter++
                                line.replace("__line__", "${lineCounter - 1}")
                            }

                            if (req.runSetupBlocks) {
                                val setupCompileError: (List<ScriptDiagnostic>) -> Unit = { list ->
                                    diagnostics += list.map(::diagnosticToCompileDiagnostic)
                                }
                                val setupEvalError: (List<ScriptDiagnostic>) -> Unit = { list ->
                                    diagnostics += list.map(::diagnosticToCompileDiagnostic)
                                }
                                val setupEvalException: (Throwable) -> Unit = { throwable ->
                                    diagnostics += CompileDiagnostic(
                                        severity = "ERROR",
                                        message = "Setup block exception: ${throwable.message ?: throwable::class.java.name}"
                                    )
                                }
                                withContext(Runloop.runLoop.main) {
                                    checkPreambleAndExecute(target, setupCompileError, setupEvalError, setupEvalException)
                                    checkIntroductionAndExecute(target, setupCompileError, setupEvalError, setupEvalException)
                                    checkAtticAndExecute(target, setupCompileError, setupEvalError, setupEvalException)
                                }
                            }

                            val prepared = target.ki.compileSnippet(
                                compiledSource,
                                implicits = listOf(target),
                                onCompileError = { list ->
                                    diagnostics += list.map(::diagnosticToCompileDiagnostic)
                                }
                            )
                            if (prepared == null) {
                                failed = true
                                break
                            }
                            snippetsCompiled++
                            at = breakIndex
                        }
                    }
                }
            }.onFailure { throwable ->
                failed = true
                diagnostics += CompileDiagnostic(
                    severity = "ERROR",
                    message = throwable.message ?: throwable::class.java.name
                )
            }

            val after = setupSnapshot(target)
            val hasHardDiagnostic = diagnostics.any { severity ->
                val normalized = severity.severity.uppercase()
                normalized != "DEBUG" && normalized != "INFO" && normalized != "WARNING"
            }
            val success = !failed && !hasHardDiagnostic
            val changed = setupChanges(before, after)
            val changedNames = changed.filter { it.changed || it.cacheChanged }.joinToString(", ") { it.property }
            val message = buildString {
                append(if (success) "Kotlin compiled successfully" else "Kotlin compile failed")
                append(" for ${target.id}; snippets=$snippetsCompiled")
                if (changedNames.isNotBlank()) append("; setup changed: $changedNames")
            }
            notify(message, "AI4")

            CompileKotlinResult(
                success = success,
                file = fileRefFor(docName, target.id, "code"),
                message = message,
                diagnostics = diagnostics,
                setupChanges = changed
            )
        }

        val shaderProperties = setOf(
            "vertex",
            "fragment",
            "vertex_fragment",
            "mesh",
            "task",
            "compute",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "vertex_s",
            "fragment_s",
            "mesh_s",
            "task_s",
            "compute_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s"
        )

        fun modeForShaderProperty(property: String): String? = when (property) {
            "vertex", "fragment", "vertex_fragment", "vertex_s", "fragment_s" -> "graphics"
            "compute", "compute_s" -> "compute"
            "task", "mesh", "task_s", "mesh_s" -> "mesh"
            "raygen", "miss", "closesthit", "anyhit", "intersection", "callable",
            "raygen_s", "miss_s", "closesthit_s", "anyhit_s", "intersection_s", "callable_s" -> "ray"
            else -> null
        }

        class LocalShaderSourceSnapshot(
            val property: String,
            val hadLocalValue: Boolean,
            val value: Any?
        )

        fun applyStagedShaderSourcesForReload(target: Box): () -> Unit {
            val stagedSources = provisionalEntries(docPath)
                .filter { it.uid == target.id && it.property in shaderProperties }
            if (stagedSources.isEmpty()) return {}

            val reloadSideEffectProperties = listOf("preamble", "_preamble", "introduction", "_introduction", "attic", "_attic")
            val snapshotProperties = (stagedSources.map { it.property } + reloadSideEffectProperties).distinct()
            val snapshots = snapshotProperties.map { property ->
                LocalShaderSourceSnapshot(
                    property = property,
                    hadLocalValue = target.properties.containsKey(property),
                    value = target.properties[property]
                )
            }
            stagedSources.forEach { staged ->
                target.setPropertyRaw(staged.property, staged.contents, doResync = false)
            }
            return {
                snapshots.forEach { snapshot ->
                    if (snapshot.hadLocalValue) {
                        target.properties[snapshot.property] = snapshot.value
                    } else {
                        target.properties.remove(snapshot.property)
                    }
                }
            }
        }

        fun modesForShaderReload(target: Box, requestedMode: String, requestedProperty: String): List<String> {
            val normalized = requestedMode.trim().lowercase()
            if (normalized !in setOf("", "auto")) return listOf(
                when (normalized) {
                    "graphics", "graphic", "module", "vertex", "fragment" -> "graphics"
                    "compute" -> "compute"
                    "mesh", "task" -> "mesh"
                    "ray", "rays", "raytracing", "ray-tracing", "ray_tracing" -> "ray"
                    else -> normalized
                }
            )

            modeForShaderProperty(requestedProperty)?.let { return listOf(it) }

            return buildList {
                if (runCatching { target._shader != null }.getOrDefault(false)) add("graphics")
                if (runCatching { target._computeShader != null }.getOrDefault(false)) add("compute")
                if (runCatching { target._meshShader != null }.getOrDefault(false)) add("mesh")
                if (runCatching { target._rayTracingShader != null }.getOrDefault(false)) add("ray")
            }
        }

        val reloadShaders: (ReloadShadersRequest) -> ReloadShadersResult = reloadShaders@{ req ->
            val ref = requestedRefOrCurrent(req.target, defaultProperty = "code")
            if (ref.document != docName) {
                return@reloadShaders ReloadShadersResult(
                    success = false,
                    target = req.target,
                    mode = req.mode,
                    message = "Unknown document '${ref.document}'."
                )
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@reloadShaders ReloadShadersResult(
                    success = false,
                    target = req.target,
                    mode = req.mode,
                    message = "Unknown box '${ref.uid}'."
                )

            val modes = modesForShaderReload(target, req.mode, ref.property).distinct()
            if (modes.isEmpty()) {
                return@reloadShaders ReloadShadersResult(
                    success = false,
                    target = fileRefFor(docName, target.id, ref.property),
                    mode = req.mode,
                    message = "No shader module pointer is attached to '${target.id}'."
                )
            }

            notify("Reloading shader(s): ${modes.joinToString(", ")}", "AI4")
            val errors = mutableListOf<ShaderReloadError>()
            val reloaded = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val oldErrorProxy = target.output.errorProxy
            val restoreShaderSources = applyStagedShaderSourcesForReload(target)

            try {
                runCatching {
                    runBlocking {
                        withContext(Runloop.runLoop.main) {
                            target.output.errorProxy = { content, property, line ->
                                if (content != null) {
                                    errors += ShaderReloadError(
                                        property = property.ifBlank { "shader" },
                                        line = line,
                                        message = content.toString()
                                    )
                                }
                                oldErrorProxy(content, property, line)
                            }
                            try {
                                modes.forEach { mode ->
                                    val ok = when (mode) {
                                        "graphics" -> if (runCatching { target._shader != null }.getOrDefault(false)) {
                                            reloadShader(target)
                                        } else {
                                            false
                                        }
                                        "compute" -> if (runCatching { target._computeShader != null }.getOrDefault(false)) {
                                            reloadComputeShader(target)
                                        } else {
                                            false
                                        }
                                        "mesh" -> if (runCatching { target._meshShader != null }.getOrDefault(false)) {
                                            reloadMeshShader(target)
                                        } else {
                                            false
                                        }
                                        "ray" -> if (runCatching { target._rayTracingShader != null }.getOrDefault(false)) {
                                            reloadRayTracingShader(target)
                                        } else {
                                            false
                                        }
                                        else -> {
                                            errors += ShaderReloadError(mode, message = "Unknown shader reload mode '$mode'.")
                                            false
                                        }
                                    }
                                    if (ok) reloaded += mode else failed += mode
                                }
                            } finally {
                                target.output.errorProxy = oldErrorProxy
                            }
                        }
                    }
                }.onFailure { throwable ->
                    failed += modes.filter { it !in reloaded }
                    errors += ShaderReloadError(
                        property = "shader",
                        message = throwable.message ?: throwable::class.java.name
                    )
                    target.output.errorProxy = oldErrorProxy
                }
            } finally {
                restoreShaderSources()
            }

            val success = failed.isEmpty() && errors.isEmpty()
            var visualAttached = false
            var visualMessage = ""
            var visualDataUrl = ""
            if (success && req.visualCheck && visualVerificationAvailable()) {
                runCatching {
                    runBlocking {
                        delay(2_000)
                        val screenshot = captureChatScreenshot(
                            AiChatScreenshotRequest(uid = target.id, target = ""),
                            sessionInfo
                        )
                        if (screenshot.success && screenshot.dataUrl.isNotBlank()) {
                            visualAttached = true
                            visualMessage = screenshot.message
                            visualDataUrl = screenshot.dataUrl
                        } else {
                            visualMessage = screenshot.message
                        }
                    }
                }.onFailure { throwable ->
                    visualMessage = throwable.message ?: throwable::class.java.simpleName
                }
            }

            val message = buildString {
                append(if (success) "Shader reload succeeded" else "Shader reload failed")
                append(" for ${target.id}")
                if (reloaded.isNotEmpty()) append("; reloaded=${reloaded.joinToString(",")}")
                if (failed.isNotEmpty()) append("; failed=${failed.joinToString(",")}")
                if (visualMessage.isNotBlank()) append("; visual=$visualMessage")
            }
            notify(message, "AI4")

            ReloadShadersResult(
                success = success,
                target = fileRefFor(docName, target.id, ref.property),
                mode = req.mode,
                message = message,
                reloaded = reloaded,
                errors = errors,
                visualCheckAttached = visualAttached,
                visualCheckMessage = visualMessage,
                visualDataUrl = visualDataUrl
            )
        }

        val vectorStoreIds = buildList {
            KotlinSourceIndex.currentVectorStoreId()?.let { add(it) }
            addAll(GlslIncludeLibraryIndex.currentVectorStoreIdsFor(exec.property, expandedExtraPrompt + "\n" + exec.prompt))
        }.distinct()

        fun parsedScreenshotCommand(): String? {
            val text = when (exec.kind) {
                "general", "chat" -> exec.extraPrompt.trim()
                else -> ""
            }
            val match = Regex("^/(screenshot|shot)(?:\\s+([\\s\\S]*))?$", RegexOption.IGNORE_CASE).matchEntire(text)
                ?: return null
            return match.groupValues.getOrNull(2)?.trim().orEmpty()
        }

        parsedScreenshotCommand()?.let { target ->
            ai4Scope.launch {
                val response = runCatching {
                    captureChatScreenshot(AiChatScreenshotRequest(uid = exec.uid, target = target), sessionInfo)
                }.getOrElse { throwable ->
                    AiChatScreenshotResponse(
                        success = false,
                        message = throwable.message ?: throwable::class.java.simpleName
                    )
                }
                val previous = chatHistoryByKey[fileKey].orEmpty()
                val commandText = buildString {
                    append("/screenshot")
                    if (target.isNotBlank()) {
                        append(" ")
                        append(target)
                    }
                }
                val next = buildString {
                    if (previous.isNotBlank()) {
                        append(previous)
                        append("\n")
                    }
                    append("<<you>>\n")
                    append(commandText)
                    if (response.success && response.dataUrl.isNotBlank()) {
                        val title = if (response.title.isNotBlank()) " from ${response.title}" else ""
                        append("\n\n_")
                        append(response.message.ifBlank { "Captured screenshot$title" })
                        append("_\n\n![screenshot")
                        append(title)
                        append("](")
                        append(response.dataUrl)
                        append(")")
                    } else {
                        append("\n<<ai>>\n_")
                        append(response.message.ifBlank { "Screenshot failed" })
                        append("_")
                    }
                }
                chatHistoryByKey[fileKey] = next
                _send(
                    Message("set-output").payload(Set_output(box.id, "outputarea", "ai-chat", next)),
                    document = escapedDocPath
                )
                _send(
                    Message("notify").payload(
                        Notify(
                            response.message.ifBlank { if (response.success) "Captured screenshot" else "Screenshot failed" },
                            "AI4"
                        )
                    ),
                    document = escapedDocPath
                )
            }
            return@mutableSetOf Handled.yes
        }

        if (exec.kind == "chat" || exec.kind == "cursor" || exec.kind == "rewrite" || exec.kind == "error") {
            clearProvisional(docPath)
        }

        val state = if (exec.kind == "general") {
            chatThreadFor(fileKey, exec.prompt)
        } else {
            threadFor(fileKey, exec.prompt)
        }

        when (exec.kind) {
            "chat" -> notify("Initiating AI Chat", "AI4")
            "general" -> notify("Initiating AI Conversation", "AI4")
            "cursor" -> notify("Initiating Completion", "AI4")
            "rewrite" -> notify("Initiating AI Rewrite", "AI4")
            "error" -> notify("Initiating AI Error Fix", "AI4")
            else -> {
                notify("Unknown AI request kind '${exec.kind}'", "AI4")
                return@mutableSetOf Handled.yes
            }
        }

        ai4Scope.launch {
            val chatResult: OpenAiIdeCodexBridge.SendResult?
            val editResult: IdeChatHelpers.HighLevelResult?
            val streamActive = AtomicBoolean(false)
            var streamPrefix: String? = null
            val liveLock = Any()
            val streamBuffer = StringBuilder()
            val progressEntries = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
            var livePrefix: String? = null
            var lastPush = 0L

            fun baseWithoutPendingAi(): String {
                val prefix = livePrefix ?: streamPrefix ?: ""
                return prefix
                    .removeSuffix("<<ai>>\n")
                    .removeSuffix("<<ai>>")
            }

            fun renderLiveConversation(
                aiText: String = "",
                latestProgressOpen: Boolean = true,
                additions: String = ""
            ): String {
                val entries = synchronized(liveLock) { progressEntries.toList() }
                val base = baseWithoutPendingAi()
                return buildString {
                    append(base)
                    if (length > 0 && this[length - 1] != '\n') append("\n")
                    entries.forEachIndexed { index, progress ->
                        val open = latestProgressOpen && aiText.isBlank() && index == entries.lastIndex
                        append(if (open) "<<progress-open>>\n" else "<<progress>>\n")
                        append(progress.title.trim().ifBlank { "AI activity" })
                        val body = progress.body.trim()
                        if (body.isNotBlank()) {
                            append("\n")
                            append(body)
                        }
                        append("\n")
                    }
                    if (additions.isNotBlank()) {
                        append(additions.trimEnd())
                        append("\n")
                    }
                    if (aiText.isNotEmpty() || (entries.isEmpty() && additions.isBlank())) {
                        append("<<ai>>\n")
                        append(aiText)
                    }
                }
            }

            fun pushLiveConversation(latestProgressOpen: Boolean = true) {
                if (!streamActive.get()) return
                val partial = synchronized(liveLock) { streamBuffer.toString() }
                val payload = Set_output(
                    box.id,
                    "outputarea",
                    "ai-chat",
                    renderLiveConversation(partial, latestProgressOpen)
                )
                _send(Message("set-output").payload(payload), document = escapedDocPath)
            }

            fun visibleUserBlockForKind(): String = when (exec.kind) {
                "chat" -> expandedExtraPrompt.trim().ifBlank { "Inline chat requested." }
                "cursor" -> "Complete code at the cursor."
                "rewrite" -> "Rewrite the selected code."
                "error" -> "Fix the current error."
                else -> "AI assistance requested."
            }

            fun beginVisibleAiPanel(title: String, userBlock: String) {
                val previous = chatHistoryByKey[fileKey].orEmpty()
                livePrefix = buildString {
                    if (previous.isNotBlank()) {
                        append(previous)
                        append("\n")
                    }
                    if (userBlock.isNotBlank()) {
                        append("<<you>>\n")
                        append(userBlock)
                        append("\n")
                    }
                    append("<<ai>>\n")
                }
                streamPrefix = livePrefix
                synchronized(liveLock) {
                    progressEntries += OpenAiIdeCodexBridge.ProgressUpdate(
                        title = title,
                        body = "Preparing context and tools."
                    )
                }
                streamActive.set(true)
                pushLiveConversation(latestProgressOpen = true)
            }

            val progressCallback: (OpenAiIdeCodexBridge.ProgressUpdate) -> Unit = { progress ->
                if (streamActive.get()) {
                    synchronized(liveLock) {
                        progressEntries += progress
                    }
                    pushLiveConversation(latestProgressOpen = true)
                }
            }

            fun sendFinalVisibleAiPanel(markdown: String, additions: String = "") {
                val next = renderLiveConversation(
                    aiText = markdown,
                    latestProgressOpen = false,
                    additions = additions
                )
                chatHistoryByKey[fileKey] = next
                val payload = Set_output(box.id, "outputarea", "ai-chat", next)
                _send(Message("set-output").payload(payload), document = escapedDocPath)
            }

            if (exec.kind != "general") {
                beginVisibleAiPanel(
                    title = when (exec.kind) {
                        "chat" -> "Starting inline chat"
                        "cursor" -> "Starting completion"
                        "rewrite" -> "Starting rewrite"
                        "error" -> "Starting error fix"
                        else -> "Starting AI work"
                    },
                    userBlock = visibleUserBlockForKind()
                )
            }

            try {
                when (exec.kind) {
                    "chat" -> {
                        chatResult = null
                        editResult = helpers!!.chatInline(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            lineNumber = exec.startLine,
                            userPrompt = expandedExtraPrompt,
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            visualVerificationEnabled = visualVerificationAvailable(),
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    "general" -> {
                        val trimmedPrompt = expandedExtraPrompt.trim()
                        val attachmentMarkdown = exec.images.joinToString("\n") { url ->
                            "![attachment]($url)"
                        }.trim()
                        val userBlock = buildString {
                            if (trimmedPrompt.isNotEmpty()) append(trimmedPrompt)
                            if (attachmentMarkdown.isNotEmpty()) {
                                if (trimmedPrompt.isNotEmpty()) append("\n\n")
                                append(attachmentMarkdown)
                            }
                        }.trim()
                        val previous = chatHistoryByKey[fileKey].orEmpty()
                        val prefix = buildString {
                            if (previous.isNotBlank()) {
                                append(previous)
                                append("\n")
                            }
                            if (userBlock.isNotEmpty()) {
                                append("<<you>>\n")
                                append(userBlock)
                                append("\n")
                            }
                            append("<<ai>>\n")
                        }
                        streamPrefix = prefix
                        livePrefix = prefix
                        streamActive.set(true)
                        synchronized(liveLock) {
                            progressEntries += OpenAiIdeCodexBridge.ProgressUpdate(
                                title = "Starting AI conversation",
                                body = "Preparing context and tools."
                            )
                        }
                        pushLiveConversation(latestProgressOpen = true)
                        chatResult = chatHelpers!!.chatGeneral(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = expandedExtraPrompt,
                            imageUrls = exec.images,
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            visualVerificationEnabled = visualVerificationAvailable(),
                            vectorStoreIds = vectorStoreIds,
                            onTextDelta = { chunk ->
                                if (!streamActive.get() || chunk.isEmpty()) return@chatGeneral
                                synchronized(liveLock) {
                                    streamBuffer.append(chunk)
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastPush >= 80L) {
                                    lastPush = now
                                    if (!streamActive.get()) return@chatGeneral
                                    pushLiveConversation(latestProgressOpen = false)
                                }
                            },
                            onProgress = { progress ->
                                progressCallback(progress)
                            }
                        )
                        streamActive.set(false)
                        editResult = null
                    }

                    "cursor" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Complete the code at <cursor>.",
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            visualVerificationEnabled = visualVerificationAvailable(),
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    "rewrite" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Rewrite only the code between <rewrite> and </rewrite>.",
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            visualVerificationEnabled = visualVerificationAvailable(),
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    "error" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = "Fix the error described between <error> and </error>.",
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            visualVerificationEnabled = visualVerificationAvailable(),
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    else -> {
                        chatResult = null
                        editResult = null
                    }
                }
            } catch (e: Exception) {
                if (livePrefix != null) {
                    sendFinalVisibleAiPanel(
                        "_AI ${exec.kind} failed: ${e.message ?: e::class.java.simpleName}_"
                    )
                }
                streamActive.set(false)
                inMain {
                    notify("AI ${exec.kind} failed: ${e.message ?: e::class.java.simpleName}", "AI4")
                }
                return@launch
            }

            inMain {
                if (chatResult != null) {
                    chatThreadByKey[fileKey] = chatResult.threadState
                    val trimmedPrompt = expandedExtraPrompt.trim()
                    val attachmentMarkdown = exec.images.joinToString("\n") { url ->
                        "![attachment]($url)"
                    }.trim()
                    val userBlock = buildString {
                        if (trimmedPrompt.isNotEmpty()) append(trimmedPrompt)
                        if (attachmentMarkdown.isNotEmpty()) {
                            if (trimmedPrompt.isNotEmpty()) append("\n\n")
                            append(attachmentMarkdown)
                        }
                    }.trim()
                    val trimmedResponse = chatResult.text.trim()
                    if (livePrefix == null) {
                        livePrefix = streamPrefix ?: buildString {
                            val previous = chatHistoryByKey[fileKey].orEmpty()
                            if (previous.isNotBlank()) {
                                append(previous)
                                append("\n")
                            }
                            if (userBlock.isNotEmpty()) {
                                append("<<you>>\n")
                                append(userBlock)
                                append("\n")
                            }
                            append("<<ai>>\n")
                        }
                    }
                    val additions = chatResult.conversationAdditions
                        .filter { it.isNotBlank() }
                        .joinToString("\n") { it.trimEnd() }
                    val checklistMarkdown = agentMemory.checklistMarkdown()
                    val responseWithChecklist = buildString {
                        append(trimmedResponse.ifEmpty { "_(no response)_" })
                        if (checklistMarkdown.isNotBlank()) {
                            append("\n\n")
                            append(checklistMarkdown)
                        }
                    }
                    val next = renderLiveConversation(
                        aiText = responseWithChecklist,
                        latestProgressOpen = false,
                        additions = additions
                    )
                    chatHistoryByKey[fileKey] = next
                    val payload = Set_output(box.id, "outputarea", "ai-chat", next)
                    _send(Message("set-output").payload(payload), document = escapedDocPath)
                    notify("Finished AI conversation", "AI4")
                } else if (editResult != null) {
                    threadByKey[fileKey] = editResult.threadState
                    markProvisional(box, exec.property, editResult.editedCode)
                    val commentary = editResult.commentary
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    val finalMarkdown = buildString {
                        append("_Prepared a provisional ${exec.kind} edit for `${exec.property}`._")
                        if (commentary.isNotBlank()) {
                            append("\n\n")
                            append(commentary)
                        }
                        val checklistMarkdown = agentMemory.checklistMarkdown()
                        if (checklistMarkdown.isNotBlank()) {
                            append("\n\n")
                            append(checklistMarkdown)
                        }
                    }
                    sendFinalVisibleAiPanel(finalMarkdown)
                    streamActive.set(false)
                    notify("Finished AI ${exec.kind}", "AI4")
                }
            }
        }
        Handled.yes
    })
    
    // Chat interface handlers
    handlers["hello_chat"] = mutableSetOf({ message, reply ->
        val hello = Json.decodeFromJsonElement<Hello>(message.payload!!)
        lprintln("info,chat", "Chat connection from ${hello.username} for file ${hello.filename}")
        // Initialize chat state if needed
        Handled.yes
    })
    
    handlers["chat_input"] = mutableSetOf({ message, reply ->
        val input = Json.decodeFromJsonElement<Chat_input>(message.payload!!)
        
        if (chatBridge == null) {
            val uiLog = NotifyLog(label = "")
            chatBridge = OpenAiIdeCodexBridge(model = Ai4ModelDefaults.FLAGSHIP_MODEL, log = uiLog)
            chatHelpers = IdeChatHelpers(chatBridge!!, log = uiLog)
        }
        
        val fileKey = "chat:${documentName()}"
        val state = chatThreadFor(fileKey, "")
        val agentMemory = agentMemoryByKey.computeIfAbsent(fileKey) { AgentMemoryState() }
        
        ai4Scope.launch {
            try {
                // Format the user message with images if present
                val userMessage = buildString {
                    append(input.value)
                    if (input.images.isNotEmpty()) {
                        append("\n\n[User attached ${input.images.size} image(s)]")
                    }
                }
                
                // Send intermediate response
                reply(Message("chat_input").payload(Chat_input(
                    value = "<div style='opacity: 0.5;'>Thinking...</div>",
                    phase = 1
                )))
                
                val lang = LanguageSpec(
                    name = "text",
                    wrap = { it.trimEnd() },
                    unwrap = { it.trimEnd() }
                )
                val result = chatHelpers!!.chatGeneral(
                    state = state,
                    code = "",
                    lang = lang,
                    userPrompt = userMessage,
                    imageUrls = input.images,
                    agentMemory = agentMemory
                )
                
                chatThreadByKey[fileKey] = result.threadState
                
                // Format response with markdown
                val responseWithChecklist = buildString {
                    append(result.text)
                    val checklistMarkdown = agentMemory.checklistMarkdown()
                    if (checklistMarkdown.isNotBlank()) {
                        append("\n\n")
                        append(checklistMarkdown)
                    }
                }
                val formattedResponse = formatMarkdown(responseWithChecklist)
                
                // Send final response
                inMain {
                    reply(Message("chat_input").payload(Chat_input(
                        value = formattedResponse,
                        phase = 20
                    )))
                }
            } catch (e: Exception) {
                inMain {
                    reply(Message("chat_input").payload(Chat_input(
                        value = "<div style='color: red;'>Error: ${e.message}</div>",
                        phase = 20
                    )))
                }
            }
        }
        
        Handled.yes
    })
}

private fun formatMarkdown(text: String): String {
    // Simple markdown formatting for chat output
    var result = text
        .replace(Regex("```([\\s\\S]*?)```"), "<pre><code>$1</code></pre>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        .replace("\n", "<br/>")
    return result
}
