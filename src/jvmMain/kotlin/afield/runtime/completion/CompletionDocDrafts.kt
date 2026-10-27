package afield.runtime.completion

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.notes
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.output.md

import afield.audio.synth.*
import afield.document.workspace.CompletionDocDraftSummary
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import afield.ai.completion.Ai4CompletionDoc
import afield.ai.completion.generatedCompletionDocsPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.absolutePathString

/**
 * How many KDoc drafts completion has cached, for the welcome page.
 *
 * Nothing here works the drafts. `tools/docs_notes.py import-drafts` makes each one under src/ a
 * `kdoc-draft` note on its declaration, and the docs revision pass (/docs-pass.html) handles them
 * like the human's own notes. See dev_docs/agents/COMPLETION_DOC_POLISH_DESIGN.md.
 */

/** Counts the cached KDoc drafts that belong to declarations under `src/`, for the welcome page. */
object CompletionDocDrafts {
    private val json = Json { ignoreUnknownKeys = true }

    fun summary(): CompletionDocDraftSummary {
        val cachePath = generatedCompletionDocsPath()
        if (!Files.exists(cachePath)) {
            return CompletionDocDraftSummary(cachePath.absolutePathString(), message = "No KDoc drafts yet.")
        }
        return runCatching {
            val records = json.parseToJsonElement(Files.readString(cachePath, StandardCharsets.UTF_8))
                .jsonObject["records"]?.jsonObject.orEmpty().values
                .map { it.jsonObject }
                .filter { it["documentation"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
            val paths = records.map { it["sourcePath"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            val ours = paths.filter(Ai4CompletionDoc::isInOurTree)
            CompletionDocDraftSummary(
                cachePath = cachePath.absolutePathString(),
                exists = true,
                draftCount = ours.size,
                fileCount = ours.toSet().size,
                outsideCount = paths.size - ours.size,
                message = if (ours.isEmpty()) "No KDoc drafts for declarations under src/." else
                    "${ours.size} KDoc drafts across ${ours.toSet().size} files; import them on the docs pass page."
            )
        }.getOrElse { error ->
            CompletionDocDraftSummary(cachePath.absolutePathString(), exists = true,
                message = "Could not read the KDoc drafts: ${error.message.orEmpty()}")
        }
    }
}
