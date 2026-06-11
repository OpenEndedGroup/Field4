package afield.ai.core

import afield.Box
import afield.SavingContext
import afield.inMain
import afield.isTrue
import afield.logger.lprintln
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.okhttp.OpenAIOkHttpClient
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

internal object Ai4AutoBoxNamer {
    const val AUTO_SAVE_MESSAGE = "AI4 auto box names"

    private const val MAX_BOXES_PER_REQUEST = 16
    private const val MAX_CODE_CHARS = 2400
    private val mapper = jacksonObjectMapper().registerModule(KotlinModule.Builder().build())
    private val inFlightDocuments = ConcurrentHashMap.newKeySet<String>()
    private val attemptedCodeHashes = ConcurrentHashMap.newKeySet<String>()

    private data class Candidate(val id: String, val code: String, val codeHash: String)
    private data class ProposedName(val id: String = "", val name: String = "")

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleAfterSave(document: String, boxes: Set<Box>, message: String?) {
        if (message == AUTO_SAVE_MESSAGE) return
        if (!enabled()) return
        if (!inFlightDocuments.add(document)) return

        val candidates = boxes.asSequence()
            .filter { it.id != "root" }
            .filter { !it.getPropertyRaw("_tombstone").isTrue() }
            .filter { isUnnamed(it.name) }
            .mapNotNull { box ->
                val code = box.getPropertyRaw("code") as? String ?: return@mapNotNull null
                val trimmed = code.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val hash = sha256(trimmed)
                val attemptKey = "$document:${box.id}:$hash"
                if (attemptedCodeHashes.contains(attemptKey)) return@mapNotNull null
                Candidate(box.id, trimmed.take(MAX_CODE_CHARS), hash)
            }
            .take(MAX_BOXES_PER_REQUEST)
            .toList()

        if (candidates.isEmpty()) {
            inFlightDocuments.remove(document)
            return
        }

        candidates.forEach { attemptedCodeHashes += "$document:${it.id}:${it.codeHash}" }

        GlobalScope.launch(afield.Runloop.miscPool.asCoroutineDispatcher()) {
            try {
                val proposed = requestNames(candidates)
                if (proposed.isEmpty()) return@launch

                inMain {
                    val byId = proposed.associate { it.id to sanitizeName(it.name) }
                        .filterValues { it != null }
                        .mapValues { it.value!! }
                    if (byId.isEmpty()) return@inMain

                    var changed = false
                    boxes.forEach { box ->
                        val next = byId[box.id] ?: return@forEach
                        val code = box.getPropertyRaw("code") as? String ?: return@forEach
                        if (isUnnamed(box.name) && code.trim().isNotBlank()) {
                            box.name = next
                            changed = true
                        }
                    }

                    if (changed) {
                        lprintln("info,ai.boxnames", "AI4 named ${byId.size} untitled box candidates in $document")
                        SavingContext().save(document, boxes, AUTO_SAVE_MESSAGE)
                    }
                }
            } catch (t: Throwable) {
                lprintln("warning,ai.boxnames", "AI4 box naming skipped: ${t.message}")
            } finally {
                inFlightDocuments.remove(document)
            }
        }
    }

    private fun enabled(): Boolean {
        val flag = System.getenv("AFIELD_AI_AUTO_NAME_BOXES") ?: System.getProperty("afield.ai.autoNameBoxes")
        if (flag.equals("false", ignoreCase = true) || flag == "0") return false
        return !System.getenv("OPENAI_API_KEY").isNullOrBlank()
    }

    private fun requestNames(candidates: List<Candidate>): List<ProposedName> {
        val prompt = buildString {
            appendLine("You name boxes in Field4, a live-coding environment.")
            appendLine("For each box, infer a good short human-readable name from its Kotlin code.")
            appendLine("Rules:")
            appendLine("- Return only JSON: an array of objects with exactly id and name.")
            appendLine("- Names should be 1-4 words, Title Case, no quotes, no punctuation unless essential.")
            appendLine("- Do not use Untitled, Box, Kotlin, Code, Script, Example, or generic names.")
            appendLine("- Prefer the main visual/output/data purpose over implementation details.")
            appendLine()
            appendLine("Boxes:")
            candidates.forEach { candidate ->
                appendLine("ID: ${candidate.id}")
                appendLine("```kotlin")
                appendLine(candidate.code)
                appendLine("```")
            }
        }

        val model = ChatModel.of(Ai4ModelDefaults.envOrDefault("AFIELD_AI_AUTO_NAME_MODEL", Ai4ModelDefaults.FAST_MODEL))
        val params = ResponseCreateParams.builder()
            .model(model)
            .maxOutputTokens(1200L)
            .input(prompt)
            .text(ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build())
            .build()

        val response = OpenAIOkHttpClient.fromEnv().responses().create(params)
        val text = extractOutputText(response)
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

    private fun sanitizeName(raw: String): String? {
        val cleaned = raw
            .lineSequence()
            .firstOrNull()
            ?.replace(Regex("""^[\s"'`*_#-]+|[\s"'`*_#-]+$"""), "")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?: return null
        if (isUnnamed(cleaned)) return null
        if (cleaned.length < 3) return null
        val withoutBadChars = cleaned.replace(Regex("""[^\p{L}\p{N} ._/\-]+"""), "").trim()
        return withoutBadChars.take(36).trim().ifBlank { null }
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
