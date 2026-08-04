package afield.ai.core

import afield.Box
import afield.SavingContext
import afield.documents
import afield.inMain
import afield.isTrue
import afield.logger.lprintln
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.models.ChatModel
import com.openai.models.ResponseFormatText
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseTextConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

internal const val AI4_AUTO_NAME_PROPERTY = "ai4_auto_name"
internal const val AI4_AUTO_NAME_SIGNATURE_PROPERTY = "ai4_auto_name_signature"

internal object Ai4AutoBoxNamer {
    const val AUTO_SAVE_MESSAGE = "AI4 auto box names"

    private const val MAX_BOXES_PER_REQUEST = 16
    private const val MAX_PROPERTY_CHARS = 2400
    private const val SIGNIFICANT_LINE_DELTA = 4
    private const val SIGNIFICANT_RELATIVE_LINE_DELTA = 0.25
    private val contentProperties = listOf(
        "code",
        "vertex",
        "fragment",
        "compute",
        "task",
        "mesh",
        "raygen",
        "miss",
        "closesthit",
        "anyhit",
        "intersection",
        "callable",
        "vertex_fragment",
        "vertex_s",
        "fragment_s",
        "compute_s",
        "task_s",
        "mesh_s",
        "raygen_s",
        "miss_s",
        "closesthit_s",
        "anyhit_s",
        "intersection_s",
        "callable_s",
        "stackSL"
    )
    private val mapper = jacksonObjectMapper().registerModule(KotlinModule.Builder().build())
    private val inFlightDocuments = ConcurrentHashMap.newKeySet<String>()
    private val attemptedContentHashes = ConcurrentHashMap.newKeySet<String>()

    private data class TextProperty(val name: String, val content: String, val fullContent: String, val truncated: Boolean)
    private data class ContentSignature(val hash: String, val lines: Int, val chars: Int)
    private data class Candidate(
        val id: String,
        val currentName: String,
        val properties: List<TextProperty>,
        val signature: ContentSignature,
        val reason: String
    )
    private data class ProposedName(val id: String = "", val name: String? = null)
    private data class EnabledCheck(val enabled: Boolean, val reason: String)

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleAfterSave(document: String, boxes: Set<Box>, message: String?) {
        lprintln(
            "info,ai.boxnames",
            "AI4 auto box naming considered for $document; saveMessage=${message ?: ""}; boxCount=${boxes.size}"
        )
        if (message == AUTO_SAVE_MESSAGE) {
            lprintln("info,ai.boxnames", "AI4 auto box naming skipped for $document: ignoring its own autosave")
            return
        }
        // Never rename boxes in agent-backdoor-loaded documents — the agent's box names are
        // intentional (e.g. window/sender) and the auto-namer would rewrite them (HDR Window, …).
        if (documents.values.any { it.agentManaged && (it.filename == document || it.documentName == document) }) {
            lprintln("info,ai.boxnames", "AI4 auto box naming skipped for $document: agent-managed (backdoor) session")
            return
        }
        val enabled = enabledCheck()
        if (!enabled.enabled) {
            lprintln("info,ai.boxnames", "AI4 auto box naming skipped for $document: ${enabled.reason}")
            return
        }
        lprintln("info,ai.boxnames", "AI4 auto box naming enabled for $document: ${enabled.reason}")
        if (!inFlightDocuments.add(document)) {
            lprintln("info,ai.boxnames", "AI4 auto box naming skipped for $document: request already in flight")
            return
        }

        var rootSkipped = 0
        var tombstoneSkipped = 0
        var manuallyNamedSkipped = 0
        var notSignificantSkipped = 0
        var noTextSkipped = 0
        var alreadyAttemptedSkipped = 0
        val allCandidates = boxes.mapNotNull { box ->
            if (box.id == "root") {
                rootSkipped++
                return@mapNotNull null
            }
            if (box.getPropertyRaw("_tombstone").isTrue()) {
                tombstoneSkipped++
                return@mapNotNull null
            }
            val properties = gatherTextProperties(box)
            if (properties.isEmpty()) {
                noTextSkipped++
                return@mapNotNull null
            }
            val signature = signatureFor(properties)
            val previousSignature = parseSignature(box.getPropertyRaw(AI4_AUTO_NAME_SIGNATURE_PROPERTY) as? String)
            val aiNamed = box.getPropertyRaw(AI4_AUTO_NAME_PROPERTY).isTrue()
            val unnamed = isUnnamed(box.name)
            val significant = previousSignature == null || significantChange(previousSignature, signature)
            val reason = when {
                unnamed && previousSignature == null -> "unnamed"
                unnamed && significant -> "unnamed; content changed significantly"
                aiNamed && previousSignature == null -> "AI-generated name without prior signature"
                aiNamed && significant -> "AI-generated name; content changed significantly"
                unnamed || aiNamed -> {
                    notSignificantSkipped++
                    return@mapNotNull null
                }
                else -> {
                    manuallyNamedSkipped++
                    return@mapNotNull null
                }
            }
            val attemptKey = "$document:${box.id}:${signature.hash}"
            if (attemptedContentHashes.contains(attemptKey)) {
                alreadyAttemptedSkipped++
                return@mapNotNull null
            }
            Candidate(box.id, box.name, properties, signature, reason)
        }
        val candidates = allCandidates.take(MAX_BOXES_PER_REQUEST)
        lprintln(
            "info,ai.boxnames",
            "AI4 auto box naming eligibility for $document: candidates=${allCandidates.size}, " +
                "using=${candidates.size}, root=$rootSkipped, tombstone=$tombstoneSkipped, " +
                "manuallyNamed=$manuallyNamedSkipped, notSignificant=$notSignificantSkipped, noText=$noTextSkipped, " +
                "alreadyAttempted=$alreadyAttemptedSkipped"
        )

        if (candidates.isEmpty()) {
            lprintln("info,ai.boxnames", "AI4 auto box naming found no boxes that need naming in $document")
            inFlightDocuments.remove(document)
            return
        }

        candidates.forEach { attemptedContentHashes += "$document:${it.id}:${it.signature.hash}" }
        lprintln(
            "info,ai.boxnames",
            "AI4 auto box naming attempting ${candidates.size} candidate(s) in $document: " +
                candidates.joinToString(", ") {
                    "${it.id.take(8)}:${it.signature.lines} lines:${it.signature.chars} chars:" +
                        "${it.signature.hash.take(10)}:${it.reason}"
                }
        )

        GlobalScope.launch(afield.Runloop.miscPool.asCoroutineDispatcher()) {
            try {
                val proposed = requestNames(candidates)
                if (proposed.isEmpty()) {
                    lprintln("info,ai.boxnames", "AI4 auto box naming got no proposed names for $document")
                    return@launch
                }

                inMain {
                    val proposedById = proposed.associateBy { it.id }
                    val candidatesById = candidates.associateBy { it.id }
                    var nameChanged = false
                    var signatureChanged = false
                    val accepted = mutableMapOf<String, String>()
                    val abstained = mutableListOf<String>()
                    boxes.forEach { box ->
                        val candidate = candidatesById[box.id] ?: return@forEach
                        val proposedName = sanitizeName(proposedById[box.id]?.name)
                        val canApply = isUnnamed(box.name) || box.getPropertyRaw(AI4_AUTO_NAME_PROPERTY).isTrue()

                        if (proposedName != null && canApply) {
                            if (box.name != proposedName) {
                                box.name = proposedName
                                nameChanged = true
                            }
                            box.setPropertyRaw(AI4_AUTO_NAME_PROPERTY, true)
                            accepted[box.id] = proposedName
                        } else {
                            abstained += box.id
                        }

                        val serializedSignature = serializeSignature(candidate.signature)
                        if (box.getPropertyRaw(AI4_AUTO_NAME_SIGNATURE_PROPERTY) != serializedSignature) {
                            box.setPropertyRaw(AI4_AUTO_NAME_SIGNATURE_PROPERTY, serializedSignature, doResync = false)
                            signatureChanged = true
                        }
                    }

                    if (nameChanged || signatureChanged) {
                        lprintln(
                            "info,ai.boxnames",
                            "AI4 auto box naming completed for $document: accepted=$accepted abstained=$abstained " +
                                "nameChanged=$nameChanged signatureChanged=$signatureChanged"
                        )
                        SavingContext().save(document, boxes, AUTO_SAVE_MESSAGE)
                    } else {
                        lprintln(
                            "info,ai.boxnames",
                            "AI4 auto box naming changed no boxes in $document: accepted=$accepted abstained=$abstained"
                        )
                    }
                }
            } catch (t: Throwable) {
                lprintln("warning,ai.boxnames", "AI4 box naming skipped: ${t.message}")
            } finally {
                inFlightDocuments.remove(document)
            }
        }
    }

    private fun enabledCheck(): EnabledCheck {
        val flag = System.getenv("AFIELD_AI_AUTO_NAME_BOXES") ?: System.getProperty("afield.ai.autoNameBoxes")
        if (flag.equals("false", ignoreCase = true) || flag == "0") {
            return EnabledCheck(false, "disabled by AFIELD_AI_AUTO_NAME_BOXES/afield.ai.autoNameBoxes=$flag")
        }
        val config = runCatching { activeAiProviderRuntimeConfig() }.getOrNull()
        if (config == null) return EnabledCheck(false, "no AI credential found")
        return EnabledCheck(true, "${config.provider} credential source=${config.source}")
    }

    private fun requestNames(candidates: List<Candidate>): List<ProposedName> {
        val prompt = buildString {
            appendLine("You name boxes in Field4, a live-coding environment.")
            appendLine("For each box, infer a good short human-readable name from its Kotlin code and shader/text properties.")
            appendLine("Rules:")
            appendLine("- Return only JSON: an array of objects with exactly id and name.")
            appendLine("- Return one object for every ID below.")
            appendLine("- Names should be 1-4 words, Title Case, no quotes, no punctuation unless essential.")
            appendLine("- Do not use Untitled, Box, Kotlin, Code, Script, Example, or generic names.")
            appendLine("- Prefer the main visual/output/data purpose over implementation details.")
            appendLine("- If you cannot infer a useful specific name, set name to null.")
            appendLine("- If the current AI-generated name is still good, return that same name.")
            appendLine()
            appendLine("Boxes:")
            candidates.forEach { candidate ->
                appendLine("ID: ${candidate.id}")
                appendLine("Current name: ${candidate.currentName.ifBlank { "(unnamed)" }}")
                appendLine("Why this box is being considered: ${candidate.reason}")
                appendLine("Text signature: ${candidate.signature.lines} lines, ${candidate.signature.chars} chars")
                candidate.properties.forEach { property ->
                    appendLine()
                    appendLine("Property: ${property.name}${if (property.truncated) " (truncated)" else ""}")
                    appendLine("```")
                    appendLine(property.content)
                    appendLine("```")
                }
                appendLine()
            }
        }
        lprintln(
            "info,ai.boxnames.prompt",
            "AI4 auto box naming prompt for ${candidates.size} candidate(s), ${prompt.length} chars:\n$prompt"
        )

        val modelName = Ai4ModelDefaults.envOrDefault("AFIELD_AI_AUTO_NAME_MODEL", Ai4ModelDefaults.fastModel())
        lprintln("info,ai.boxnames", "AI4 auto box naming sending request to model $modelName")
        val text = if (configuredAiProvider() == "anthropic") {
            val msg = anthropicClientFromFieldConfig().messages().create(
                com.anthropic.models.messages.MessageCreateParams.builder()
                    .model(modelName)
                    .maxTokens(1200L)
                    .addUserMessage(prompt)
                    .build()
            )
            msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
        } else {
            val params = ResponseCreateParams.builder()
                .model(ChatModel.of(modelName))
                .maxOutputTokens(1200L)
                .input(prompt)
                .text(ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build())
                .build()
            extractOutputText(openAiClientFromFieldConfig().responses().create(params))
        }
        lprintln("info,ai.boxnames", "AI4 auto box naming raw response text (${text.length} chars):\n$text")
        if (text.isBlank()) return emptyList()
        return mapper.readValue(jsonArrayText(text))
    }

    private fun extractOutputText(response: Response): String {
        val parts = mutableListOf<String>()
        response.output().forEach { item ->
            if (item.isMessage()) {
                item.message().orElse(null)?.content()?.forEach { content ->
                    content.outputText().orElse(null)?.let { parts += it.text() }
                }
            }
        }
        return parts.joinToString("\n").trim()
    }

    private fun jsonArrayText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) return trimmed
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return "[]"
    }

    private fun gatherTextProperties(box: Box): List<TextProperty> =
        contentProperties.mapNotNull { property ->
            val text = (box.getPropertyRaw(property) as? String)?.trim() ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            TextProperty(
                name = property,
                content = text.take(MAX_PROPERTY_CHARS),
                fullContent = text,
                truncated = text.length > MAX_PROPERTY_CHARS
            )
        }

    private fun signatureFor(properties: List<TextProperty>): ContentSignature {
        val fullText = buildString {
            properties.forEach { property ->
                appendLine("<<<${property.name}>>>")
                appendLine(property.fullContent)
                appendLine("<<<END ${property.name}>>>")
            }
        }
        val lines = properties.sumOf { lineCount(it.fullContent) }
        val chars = properties.sumOf { it.fullContent.length }
        return ContentSignature(sha256(fullText), lines, chars)
    }

    private fun significantChange(previous: ContentSignature, current: ContentSignature): Boolean {
        if (previous.hash == current.hash) return false
        val lineDelta = abs(current.lines - previous.lines)
        if (lineDelta >= SIGNIFICANT_LINE_DELTA) return true
        val relativeLineDelta = lineDelta.toDouble() / max(1, previous.lines).toDouble()
        return lineDelta >= 2 && relativeLineDelta >= SIGNIFICANT_RELATIVE_LINE_DELTA
    }

    private fun serializeSignature(signature: ContentSignature): String =
        "${signature.hash}:${signature.lines}:${signature.chars}"

    private fun parseSignature(raw: String?): ContentSignature? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val parts = trimmed.split(":")
        if (parts.size != 3) return null
        val lines = parts[1].toIntOrNull() ?: return null
        val chars = parts[2].toIntOrNull() ?: return null
        return ContentSignature(parts[0], lines, chars)
    }

    private fun lineCount(text: String): Int =
        if (text.isBlank()) 0 else text.lineSequence().count()

    private fun sanitizeName(raw: String?): String? {
        val cleaned = raw
            ?.takeUnless { isAbstainName(it) }
            ?.lineSequence()
            ?.firstOrNull()
            ?.replace(Regex("""^[\s"'`*_#-]+|[\s"'`*_#-]+$"""), "")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?: return null
        if (isAbstainName(cleaned)) return null
        if (isUnnamed(cleaned)) return null
        if (cleaned.length < 3) return null
        val withoutBadChars = cleaned.replace(Regex("""[^\p{L}\p{N} ._/\-]+"""), "").trim()
        return withoutBadChars.take(36).trim().ifBlank { null }
    }

    private fun isAbstainName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase().orEmpty()
            .replace(Regex("""[\s._/\-]+"""), " ")
        return normalized.isBlank() ||
            normalized == "null" ||
            normalized == "unknown" ||
            normalized == "unsure" ||
            normalized == "unclear" ||
            normalized == "no name" ||
            normalized == "no useful name" ||
            normalized == "not sure" ||
            normalized == "do not know" ||
            normalized == "don't know" ||
            normalized == "dont know" ||
            normalized == "i don't know" ||
            normalized == "i dont know" ||
            normalized == "not enough information" ||
            normalized.contains("cannot infer") ||
            normalized.contains("can't infer") ||
            normalized.contains("cannot determine")
    }

    private fun isUnnamed(name: String?): Boolean {
        val normalized = name?.trim().orEmpty()
        return normalized.isBlank() || normalized.equals("untitled", ignoreCase = true) ||
            normalized.equals("unititled", ignoreCase = true)
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
