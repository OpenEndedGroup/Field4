@file:afield.runtime.completion.CompletionCategory("Data")

package afield.ai.retrieval

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.audio.synth.*
import afield.document.workspace.path
import afield.ai.retrieval.SemanticRetrievalBuildResult
import afield.ai.retrieval.SemanticRetrievalConfigSaveRequest
import afield.ai.retrieval.SemanticRetrievalDeleteCacheRequest
import afield.ai.retrieval.SemanticRetrievalDownloadRequest
import afield.ai.retrieval.SemanticRetrievalQueryRequest
import afield.ai.retrieval.SemanticRetrievalService
import afield.ai.retrieval.SemanticRetrievalSettingsStatus
import afield.ai.retrieval.SemanticRetrievalStatus
import java.util.Locale

/**
 * Box-facing wrapper around `afield.ai.retrieval.SemanticRetrievalService`, Field's embedding-based search
 * over source, docs and workspace boxes. Make a [SemanticSearchManager] and call
 * [SemanticSearchManager.find] for the full [SemanticSearchResult] (message, status, hits) or
 * [SemanticSearchManager.findTopK] / [SemanticSearchManager.findWorkspaceTopK] when you just want
 * a `List<`[SemanticSearchHit]`>`; each hit carries score, kind, corpus, path and line range, and
 * prints as one readable line. Queries can be narrowed by corpus, path prefix, kind, document,
 * box or property, and note that `find` passes `rebuildIfEmpty = true` by default.
 *
 * The remaining methods administer the service rather than query it:
 * [SemanticSearchManager.status], [SemanticSearchManager.settings], [SemanticSearchManager.enable],
 * [SemanticSearchManager.saveSettings], [SemanticSearchManager.downloadPreset],
 * [SemanticSearchManager.rebuild], [SemanticSearchManager.deleteIndex] and
 * [SemanticSearchManager.deleteModels]. Every call delegates straight through and blocks; the only
 * state kept here is [SemanticSearchManager.lastResult].
 */

data class SemanticSearchResult(
    val success: Boolean,
    val message: String,
    val hits: List<SemanticSearchHit>,
    val status: SemanticRetrievalStatus
)

data class SemanticSearchHit(
    val title: String,
    val symbol: String,
    val kind: String,
    val corpus: String,
    val path: String,
    val line: Int,
    val endLine: Int,
    val score: Double,
    val id: String,
    val scale: String,
    val documentName: String = "",
    val boxName: String = "",
    val boxId: String = "",
    val property: String = "",
    val language: String = "",
    val capsule: String = "",
    val sourcePreview: String = ""
) {
    val location: String
        get() = if (line > 0) "$path:$line" else path

    override fun toString(): String {
        val name = symbol.ifBlank { title.ifBlank { path } }
        val owner = when {
            documentName.isNotBlank() && boxName.isNotBlank() -> "$documentName / $boxName"
            documentName.isNotBlank() -> documentName
            else -> ""
        }
        val ownerText = if (owner.isBlank()) "" else " [$owner]"
        return "${String.format(Locale.ROOT, "%.3f", score)} $name ($kind)$ownerText $location"
    }
}

class SemanticSearchManager {
    var lastResult: SemanticSearchResult? = null
        private set

    fun status(): SemanticRetrievalStatus =
        SemanticRetrievalService.status()

    fun settings(): SemanticRetrievalSettingsStatus =
        SemanticRetrievalService.settingsStatus()

    fun enable(enabled: Boolean = true): SemanticRetrievalSettingsStatus =
        SemanticRetrievalService.saveConfig(SemanticRetrievalConfigSaveRequest(enabled = enabled))

    fun saveSettings(
        enabled: Boolean? = null,
        presetId: String? = null,
        modelPath: String? = null,
        tokenizerPath: String? = null,
        dimension: Int? = null,
        maxTokens: Int? = null,
        queryPrefix: String? = null,
        documentPrefix: String? = null
    ): SemanticRetrievalSettingsStatus =
        SemanticRetrievalService.saveConfig(
            SemanticRetrievalConfigSaveRequest(
                enabled = enabled,
                presetId = presetId,
                modelPath = modelPath,
                tokenizerPath = tokenizerPath,
                dimension = dimension,
                maxTokens = maxTokens,
                queryPrefix = queryPrefix,
                documentPrefix = documentPrefix
            )
        )

    fun downloadPreset(presetId: String = settings().config.presetId): SemanticRetrievalSettingsStatus =
        SemanticRetrievalService.downloadPreset(SemanticRetrievalDownloadRequest(presetId = presetId))

    fun rebuild(): SemanticRetrievalBuildResult =
        SemanticRetrievalService.rebuild()

    fun deleteIndex(): SemanticRetrievalSettingsStatus =
        SemanticRetrievalService.deleteCache(SemanticRetrievalDeleteCacheRequest("index"))

    fun deleteModels(): SemanticRetrievalSettingsStatus =
        SemanticRetrievalService.deleteCache(SemanticRetrievalDeleteCacheRequest("models"))

    fun find(
        query: String,
        k: Int = 10,
        corpus: String = "",
        corpora: List<String> = emptyList(),
        pathPrefix: String = "",
        kinds: List<String> = emptyList(),
        documentName: String = "",
        boxId: String = "",
        property: String = "",
        rebuildIfEmpty: Boolean = true,
        includeCapsule: Boolean = false,
        includeSource: Boolean = false
    ): SemanticSearchResult {
        val result = SemanticRetrievalService.query(
            SemanticRetrievalQueryRequest(
                query = query,
                limit = k,
                corpus = corpus,
                corpora = corpora,
                pathPrefix = pathPrefix,
                kinds = kinds,
                documentName = documentName,
                boxId = boxId,
                property = property,
                rebuildIfEmpty = rebuildIfEmpty,
                includeCapsule = includeCapsule,
                includeSource = includeSource
            )
        )
        return SemanticSearchResult(
            success = result.success,
            message = result.message,
            hits = result.results.map {
                SemanticSearchHit(
                    title = it.title,
                    symbol = it.symbol,
                    kind = it.kind,
                    corpus = it.corpus,
                    path = it.path,
                    line = it.line,
                    endLine = it.endLine,
                    score = it.score,
                    id = it.id,
                    scale = it.scale,
                    documentName = it.documentName,
                    boxName = it.boxName,
                    boxId = it.boxId,
                    property = it.property,
                    language = it.language,
                    capsule = it.capsule,
                    sourcePreview = it.sourcePreview
                )
            },
            status = result.status
        ).also {
            lastResult = it
        }
    }

    fun findTopK(
        query: String,
        k: Int = 10,
        corpus: String = "",
        corpora: List<String> = emptyList(),
        pathPrefix: String = "",
        kinds: List<String> = emptyList(),
        documentName: String = "",
        boxId: String = "",
        property: String = "",
        includeCapsule: Boolean = false,
        includeSource: Boolean = false
    ): List<SemanticSearchHit> =
        find(
            query = query,
            k = k,
            corpus = corpus,
            corpora = corpora,
            pathPrefix = pathPrefix,
            kinds = kinds,
            documentName = documentName,
            boxId = boxId,
            property = property,
            includeCapsule = includeCapsule,
            includeSource = includeSource
        ).hits

    fun findWorkspaceTopK(
        query: String,
        k: Int = 10,
        documentName: String = "",
        boxId: String = "",
        property: String = "",
        includeSource: Boolean = false
    ): List<SemanticSearchHit> =
        findTopK(
            query = query,
            k = k,
            corpus = "workspace",
            documentName = documentName,
            boxId = boxId,
            property = property,
            includeSource = includeSource
        )
}
