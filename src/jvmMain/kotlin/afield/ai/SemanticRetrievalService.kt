package afield.ai

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import afield.Box
import afield.SavingContext
import afield.home
import afield.logger.lprintln
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import java.nio.charset.StandardCharsets
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.math.sqrt

@Serializable
data class SemanticRetrievalStatus(
    val enabled: Boolean,
    val indexPath: String,
    val indexExists: Boolean,
    val indexedDocuments: Int = 0,
    val indexedCapsules: Int = 0,
    val capsuleBackend: String = "source-index-capsules",
    val embedderBackend: String = "disabled",
    val embeddingDimension: Int = 0,
    val lastBuildEpochMs: Long = 0,
    val workspaceRoot: String = "",
    val workspaceDocuments: Int = 0,
    val workspaceSidecarFiles: Int = 0,
    val corpusFingerprint: String = "",
    val currentCorpusFingerprint: String = "",
    val indexStale: Boolean = false,
    val message: String = ""
)

@Serializable
data class SemanticRetrievalConfig(
    val enabled: Boolean = true,
    val presetId: String = "granite-97m-r2-quantized",
    val modelPath: String = "",
    val tokenizerPath: String = "",
    val dimension: Int = 384,
    val maxTokens: Int = 512,
    val queryPrefix: String = "",
    val documentPrefix: String = ""
)

@Serializable
data class SemanticRetrievalModelPreset(
    val id: String,
    val label: String,
    val modelUrl: String,
    val tokenizerUrl: String,
    val modelFileName: String,
    val tokenizerFileName: String,
    val dimension: Int,
    val maxTokens: Int,
    val queryPrefix: String = "",
    val documentPrefix: String = "",
    val note: String = ""
)

@Serializable
data class SemanticRetrievalFileState(
    val path: String = "",
    val exists: Boolean = false,
    val sizeBytes: Long = 0,
    val lastModifiedEpochMs: Long = 0
)

@Serializable
data class SemanticRetrievalSettingsStatus(
    val config: SemanticRetrievalConfig,
    val status: SemanticRetrievalStatus,
    val model: SemanticRetrievalModelInfo,
    val presets: List<SemanticRetrievalModelPreset>,
    val modelFile: SemanticRetrievalFileState,
    val tokenizerFile: SemanticRetrievalFileState,
    val indexSizeBytes: Long,
    val modelCacheSizeBytes: Long,
    val configPath: String,
    val cacheRoot: String,
    val downloadRunning: Boolean = false,
    val message: String = ""
)

@Serializable
data class SemanticRetrievalBuildResult(
    val success: Boolean,
    val message: String,
    val status: SemanticRetrievalStatus
)

@Serializable
data class SemanticRetrievalQueryRequest(
    val query: String,
    val limit: Int = 10,
    val corpus: String = "",
    val pathPrefix: String = "",
    val kinds: List<String> = emptyList(),
    val documentName: String = "",
    val boxId: String = "",
    val property: String = "",
    val rebuildIfEmpty: Boolean = true,
    val includeCapsule: Boolean = false,
    val includeSource: Boolean = false
)

@Serializable
data class SemanticRetrievalQueryResult(
    val success: Boolean,
    val message: String,
    val status: SemanticRetrievalStatus,
    val results: List<SemanticRetrievalHit> = emptyList()
)

@Serializable
data class SemanticRetrievalHit(
    val id: String,
    val scale: String,
    val corpus: String,
    val kind: String,
    val symbol: String,
    val title: String,
    val path: String,
    val line: Int,
    val endLine: Int = 0,
    val parentId: String = "",
    val documentName: String = "",
    val boxName: String = "",
    val boxId: String = "",
    val property: String = "",
    val language: String = "",
    val score: Double,
    val vectorRank: Int = 0,
    val bm25Rank: Int = 0,
    val symbolRank: Int = 0,
    val vectorScore: Float = 0f,
    val bm25Score: Float = 0f,
    val symbolScore: Float = 0f,
    val capsule: String = "",
    val sourcePreview: String = ""
)

@Serializable
data class SemanticRetrievalModelInfo(
    val backend: String,
    val dimension: Int,
    val modelPath: String = "",
    val tokenizerPath: String = "",
    val queryPrefix: String = "",
    val documentPrefix: String = "",
    val message: String = ""
)

@Serializable
data class SemanticRetrievalDownloadRequest(
    val presetId: String = "granite-97m-r2-quantized"
)

@Serializable
data class SemanticRetrievalConfigSaveRequest(
    val enabled: Boolean? = null,
    val presetId: String? = null,
    val modelPath: String? = null,
    val tokenizerPath: String? = null,
    val dimension: Int? = null,
    val maxTokens: Int? = null,
    val queryPrefix: String? = null,
    val documentPrefix: String? = null
)

@Serializable
data class SemanticRetrievalDeleteCacheRequest(
    val target: String = "index"
)

data class SemanticCapsule(
    val id: String,
    val scale: String,
    val corpus: String,
    val kind: String,
    val symbol: String,
    val title: String,
    val path: String,
    val line: Int,
    val endLine: Int = 0,
    val parentId: String = "",
    val documentName: String = "",
    val boxName: String = "",
    val boxId: String = "",
    val property: String = "",
    val language: String = "",
    val signature: String = "",
    val documentation: String = "",
    val metadata: String = "",
    val source: String = ""
) {
    fun intentText(): String = buildString {
        appendLine("kind: $kind")
        appendLine("symbol: $symbol")
        appendLine("title: $title")
        appendLine("corpus: $corpus")
        appendLine("file: $path")
        if (documentName.isNotBlank()) appendLine("document: $documentName")
        if (boxName.isNotBlank()) appendLine("box: $boxName")
        if (boxId.isNotBlank()) appendLine("box_uid: $boxId")
        if (property.isNotBlank()) appendLine("property: $property")
        if (language.isNotBlank()) appendLine("language: $language")
        if (signature.isNotBlank()) {
            appendLine()
            appendLine("signature:")
            appendLine(signature)
        }
        if (documentation.isNotBlank()) {
            appendLine()
            appendLine("documentation:")
            appendLine(documentation)
        }
        if (metadata.isNotBlank()) {
            appendLine()
            appendLine("metadata:")
            appendLine(metadata)
        }
    }.trim()

    fun implementationText(): String = buildString {
        if (signature.isNotBlank()) {
            appendLine("signature:")
            appendLine(signature)
            appendLine()
        }
        if (source.isNotBlank()) {
            appendLine("source:")
            appendLine(source)
        } else {
            append(intentText())
        }
    }.trim()

    fun lexicalText(): String = listOf(
        symbol,
        title,
        kind,
        path,
        documentName,
        boxName,
        boxId,
        property,
        language,
        signature,
        documentation,
        metadata,
        source
    )
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

interface SemanticEmbedder : AutoCloseable {
    val dimension: Int
    val backend: String
    fun encodeQueries(texts: List<String>): List<FloatArray>
    fun encodeDocuments(texts: List<String>): List<FloatArray>
}

object SemanticRetrievalService {
    private const val INTENT_VECTOR = "intentVector"
    private const val IMPLEMENTATION_VECTOR = "implementationVector"
    private const val META_FILE = "field-semantic-search.meta"

    private val cacheRoot: Path = Paths.get(
        System.getProperty("afield.semanticSearch.cacheRoot")
            ?: System.getenv("AFIELD_SEMANTIC_SEARCH_CACHE_ROOT")
            ?: Paths.get(System.getProperty("user.home"), ".afield", "semantic-search").toString()
    ).toAbsolutePath().normalize()
    private val modelCacheRoot: Path = cacheRoot.resolve("models").toAbsolutePath().normalize()
    private val configPath: Path = cacheRoot.resolve("config.json").toAbsolutePath().normalize()
    private val explicitIndexPath: Path? = (
        System.getProperty("afield.semanticSearch.indexPath")
            ?: System.getenv("AFIELD_SEMANTIC_SEARCH_INDEX_PATH")
        )?.trim()?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath().normalize() }

    private val analyzer: Analyzer = StandardAnalyzer()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val downloadClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val lock = Any()

    @Volatile
    private var lastStatus: SemanticRetrievalStatus? = null

    @Volatile
    private var embedder: SemanticEmbedder? = null

    @Volatile
    private var embedderMessage: String = ""

    @Volatile
    private var downloadRunning: Boolean = false

    val presets: List<SemanticRetrievalModelPreset> = listOf(
        SemanticRetrievalModelPreset(
            id = "granite-97m-r2-quantized",
            label = "Granite 97M R2 int8",
            modelUrl = "https://huggingface.co/onnx-community/granite-embedding-97m-multilingual-r2-ONNX/resolve/main/onnx/model_quantized.onnx",
            tokenizerUrl = "https://huggingface.co/onnx-community/granite-embedding-97m-multilingual-r2-ONNX/resolve/main/tokenizer.json",
            modelFileName = "granite-embedding-97m-multilingual-r2/model_quantized.onnx",
            tokenizerFileName = "granite-embedding-97m-multilingual-r2/tokenizer.json",
            dimension = 384,
            maxTokens = 512,
            note = "Small JVM-friendly baseline; quantized ONNX."
        ),
        SemanticRetrievalModelPreset(
            id = "granite-97m-r2-fp32",
            label = "Granite 97M R2 fp32",
            modelUrl = "https://huggingface.co/onnx-community/granite-embedding-97m-multilingual-r2-ONNX/resolve/main/onnx/model.onnx",
            tokenizerUrl = "https://huggingface.co/onnx-community/granite-embedding-97m-multilingual-r2-ONNX/resolve/main/tokenizer.json",
            modelFileName = "granite-embedding-97m-multilingual-r2/model.onnx",
            tokenizerFileName = "granite-embedding-97m-multilingual-r2/tokenizer.json",
            dimension = 384,
            maxTokens = 512,
            note = "Larger baseline with unquantized ONNX weights."
        )
    )

    fun modelInfo(): SemanticRetrievalModelInfo {
        val settings = currentConfig()
        val configured = OnnxSemanticEmbedder.Config.fromSettings(settings)
        val modelPath = configured.modelPath
        val tokenizerPath = configured.tokenizerPath
        if (settings.enabled && modelPath?.exists() == true && tokenizerPath?.exists() == true) {
            return SemanticRetrievalModelInfo(
                backend = "onnx-runtime-java",
                dimension = configured.dimension,
                modelPath = modelPath.absolutePathString(),
                tokenizerPath = tokenizerPath.absolutePathString(),
                queryPrefix = configured.queryPrefix,
                documentPrefix = configured.documentPrefix,
                message = "ONNX embedder files are configured; runtime session loads on rebuild/query."
            )
        }
        return SemanticRetrievalModelInfo(
            backend = if (settings.enabled) "disabled" else "off",
            dimension = configured.dimension,
            modelPath = configured.modelPath?.absolutePathString().orEmpty(),
            tokenizerPath = configured.tokenizerPath?.absolutePathString().orEmpty(),
            queryPrefix = configured.queryPrefix,
            documentPrefix = configured.documentPrefix,
            message = OnnxSemanticEmbedder.Config.disabledReason(configured)
        )
    }

    fun status(): SemanticRetrievalStatus {
        val cached = lastStatus
        if (cached != null) return cached.copy(indexExists = indexExists())
        return computeStatus("Semantic retrieval index has not been built in this process.")
    }

    fun settingsStatus(): SemanticRetrievalSettingsStatus {
        val config = currentConfig()
        val model = OnnxSemanticEmbedder.Config.fromSettings(config)
        val effectiveModel = model.modelPath?.absolutePathString().orEmpty()
        val effectiveTokenizer = model.tokenizerPath?.absolutePathString().orEmpty()
        return SemanticRetrievalSettingsStatus(
            config = config,
            status = computeStatus(""),
            model = modelInfo(),
            presets = presets,
            modelFile = fileState(effectiveModel),
            tokenizerFile = fileState(effectiveTokenizer),
            indexSizeBytes = directorySize(indexPath()),
            modelCacheSizeBytes = directorySize(modelCacheRoot),
            configPath = configPath.absolutePathString(),
            cacheRoot = cacheRoot.absolutePathString(),
            downloadRunning = downloadRunning,
            message = ""
        )
    }

    fun saveConfig(request: SemanticRetrievalConfigSaveRequest): SemanticRetrievalSettingsStatus = synchronized(lock) {
        val current = currentConfig()
        val next = current.copy(
            enabled = request.enabled ?: current.enabled,
            presetId = request.presetId?.ifBlank { current.presetId } ?: current.presetId,
            modelPath = request.modelPath?.trim() ?: current.modelPath,
            tokenizerPath = request.tokenizerPath?.trim() ?: current.tokenizerPath,
            dimension = request.dimension?.coerceIn(1, 1024) ?: current.dimension,
            maxTokens = request.maxTokens?.coerceIn(16, 8192) ?: current.maxTokens,
            queryPrefix = request.queryPrefix ?: current.queryPrefix,
            documentPrefix = request.documentPrefix ?: current.documentPrefix
        )
        persistConfig(next)
        resetEmbedder()
        settingsStatus().copy(message = "Semantic search settings saved.")
    }

    fun downloadPreset(request: SemanticRetrievalDownloadRequest): SemanticRetrievalSettingsStatus = synchronized(lock) {
        val preset = presets.firstOrNull { it.id == request.presetId }
            ?: error("Unknown semantic search model preset '${request.presetId}'.")
        downloadRunning = true
        try {
            val modelPath = modelCacheRoot.resolve(preset.modelFileName).toAbsolutePath().normalize()
            val tokenizerPath = modelCacheRoot.resolve(preset.tokenizerFileName).toAbsolutePath().normalize()
            downloadFile(preset.modelUrl, modelPath)
            downloadFile(preset.tokenizerUrl, tokenizerPath)
            persistConfig(
                currentConfig().copy(
                    enabled = true,
                    presetId = preset.id,
                    modelPath = modelPath.absolutePathString(),
                    tokenizerPath = tokenizerPath.absolutePathString(),
                    dimension = preset.dimension,
                    maxTokens = preset.maxTokens,
                    queryPrefix = preset.queryPrefix,
                    documentPrefix = preset.documentPrefix
                )
            )
            resetEmbedder()
            downloadRunning = false
            settingsStatus().copy(message = "Downloaded ${preset.label}.")
        } catch (t: Throwable) {
            downloadRunning = false
            throw t
        }
    }

    fun deleteCache(request: SemanticRetrievalDeleteCacheRequest): SemanticRetrievalSettingsStatus = synchronized(lock) {
        when (request.target.trim().lowercase(Locale.ROOT)) {
            "index", "lucene" -> deleteRecursively(indexPath())
            "models", "model" -> {
                resetEmbedder()
                deleteRecursively(modelCacheRoot)
            }
            "all" -> {
                resetEmbedder()
                deleteRecursively(indexPath())
                deleteRecursively(modelCacheRoot)
            }
            else -> error("Unknown semantic search cache target '${request.target}'.")
        }
        settingsStatus().copy(message = "Deleted semantic search ${request.target} cache.")
    }

    fun rebuild(): SemanticRetrievalBuildResult = synchronized(lock) {
        if (!currentConfig().enabled) {
            val status = computeStatus("Semantic search is off.")
            return@synchronized SemanticRetrievalBuildResult(false, status.message, status)
        }
        val started = System.currentTimeMillis()
        val build = buildCapsules()
        val capsules = build.capsules
        val currentEmbedder = loadEmbedder()
        val path = indexPath()
        path.createDirectories()
        FSDirectory.open(path).use { directory ->
            val config = IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
            IndexWriter(directory, config).use { writer ->
                val intentVectors = currentEmbedder?.encodeDocuments(capsules.map { it.intentText() }).orEmpty()
                val implementationVectors = currentEmbedder?.encodeDocuments(capsules.map { it.implementationText() }).orEmpty()
                capsules.forEachIndexed { index, capsule ->
                    val doc = capsule.toLuceneDocument(
                        intentVector = intentVectors.getOrNull(index),
                        implementationVector = implementationVectors.getOrNull(index)
                    )
                    writer.updateDocument(Term("id", capsule.id), doc)
                }
                writer.commit()
            }
        }
        val meta = listOf(
            "builtAtEpochMs=$started",
            "capsules=${capsules.size}",
            "embedder=${currentEmbedder?.backend ?: "disabled"}",
            "dimension=${currentEmbedder?.dimension ?: 0}",
            "capsuleBackend=source-index+workspace-sidecars",
            "workspaceDocuments=${build.workspaceDocuments}",
            "workspaceSidecarFiles=${build.workspaceSidecarFiles}",
            "corpusFingerprint=${build.corpusFingerprint}"
        ).joinToString("\n")
        Files.writeString(path.resolve(META_FILE), meta, StandardCharsets.UTF_8)
        val status = computeStatus(
            "Built semantic retrieval index with ${capsules.size} capsule(s) in ${System.currentTimeMillis() - started}ms."
        )
        lastStatus = status
        SemanticRetrievalBuildResult(true, status.message, status)
    }

    fun query(request: SemanticRetrievalQueryRequest): SemanticRetrievalQueryResult {
        if (!currentConfig().enabled) {
            return SemanticRetrievalQueryResult(false, "Semantic search is off.", status())
        }
        val safeQuery = request.query.trim()
        if (safeQuery.isBlank()) {
            return SemanticRetrievalQueryResult(false, "Query is blank.", status())
        }
        if (!indexExists() && request.rebuildIfEmpty) {
            rebuild()
        }
        if (!indexExists()) {
            return SemanticRetrievalQueryResult(false, "Semantic retrieval index does not exist yet.", status())
        }

        val limit = request.limit.coerceIn(1, 50)
        val currentEmbedder = loadEmbedder()
        val requestedKinds = request.kinds.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        val filter: (Document) -> Boolean = { doc ->
            val corpusOk = request.corpus.isBlank() || doc.get("corpus").equals(request.corpus, ignoreCase = true)
            val pathOk = request.pathPrefix.isBlank() || doc.get("path").orEmpty().startsWith(request.pathPrefix)
            val kindOk = requestedKinds.isEmpty() || doc.get("kind").orEmpty().lowercase(Locale.ROOT) in requestedKinds
            val documentOk = request.documentName.isBlank() ||
                    doc.get("documentName").orEmpty().equals(request.documentName, ignoreCase = true)
            val boxOk = request.boxId.isBlank() || doc.get("boxId").orEmpty() == request.boxId
            val propertyOk = request.property.isBlank() ||
                    doc.get("property").orEmpty().equals(request.property, ignoreCase = true)
            corpusOk && pathOk && kindOk && documentOk && boxOk && propertyOk
        }

        return runCatching {
            FSDirectory.open(indexPath()).use { directory ->
                DirectoryReader.open(directory).use { reader ->
                    val searcher = IndexSearcher(reader)
                    val candidateCount = (limit * 8).coerceAtLeast(80)
                    val bm25 = searcher.search(buildBm25Query(safeQuery), candidateCount)
                        .scoreDocs
                        .toRanked(searcher, filter)
                    val symbol = searcher.search(buildSymbolQuery(safeQuery), candidateCount)
                        .scoreDocs
                        .toRanked(searcher, filter)
                    val vector = currentEmbedder?.encodeQueries(listOf(safeQuery))?.firstOrNull()?.let { queryVector ->
                        val intentHits = searcher.search(KnnFloatVectorQuery(INTENT_VECTOR, queryVector, candidateCount), candidateCount)
                            .scoreDocs
                            .toRanked(searcher, filter)
                        val implementationHits = searcher.search(
                            KnnFloatVectorQuery(IMPLEMENTATION_VECTOR, queryVector, candidateCount),
                            candidateCount
                        ).scoreDocs.toRanked(searcher, filter, scoreScale = 0.85f)
                        mergeVectorRanks(intentHits, implementationHits)
                    }.orEmpty()

                    val fused = fuseRanks(searcher, vector, bm25, symbol, limit, request)
                    SemanticRetrievalQueryResult(
                        success = true,
                        message = "Returned ${fused.size} semantic retrieval hit(s). Vector backend=${currentEmbedder?.backend ?: "disabled"}; capsule backend=source-index-capsules.",
                        status = status(),
                        results = fused
                    )
                }
            }
        }.getOrElse {
            SemanticRetrievalQueryResult(false, it.message ?: it.javaClass.name, status())
        }
    }

    private fun loadEmbedder(): SemanticEmbedder? {
        if (!currentConfig().enabled) return null
        val current = embedder
        if (current != null) return current
        val config = OnnxSemanticEmbedder.Config.fromSettings(currentConfig())
        val created = config.createOrNull()
        embedder = created
        embedderMessage = created?.backend ?: OnnxSemanticEmbedder.Config.disabledReason(config)
        return created
    }

    private fun computeStatus(message: String): SemanticRetrievalStatus {
        val config = currentConfig()
        val meta = readMeta()
        val exists = indexExists()
        val currentFingerprint = currentCorpusFingerprint()
        val indexedFingerprint = meta["corpusFingerprint"].orEmpty()
        val documents = if (exists) {
            runCatching {
                FSDirectory.open(indexPath()).use { directory ->
                    DirectoryReader.open(directory).use { it.numDocs() }
                }
            }.getOrDefault(0)
        } else {
            0
        }
        val status = SemanticRetrievalStatus(
            enabled = config.enabled,
            indexPath = indexPath().absolutePathString(),
            indexExists = exists,
            indexedDocuments = documents,
            indexedCapsules = meta["capsules"]?.toIntOrNull() ?: documents,
            capsuleBackend = meta["capsuleBackend"] ?: "source-index+workspace-sidecars",
            embedderBackend = if (!config.enabled) "disabled" else embedder?.backend ?: meta["embedder"] ?: "disabled",
            embeddingDimension = embedder?.dimension ?: meta["dimension"]?.toIntOrNull() ?: 0,
            lastBuildEpochMs = meta["builtAtEpochMs"]?.toLongOrNull() ?: 0L,
            workspaceRoot = workspaceRootPath()?.toString().orEmpty(),
            workspaceDocuments = meta["workspaceDocuments"]?.toIntOrNull() ?: 0,
            workspaceSidecarFiles = meta["workspaceSidecarFiles"]?.toIntOrNull() ?: 0,
            corpusFingerprint = indexedFingerprint,
            currentCorpusFingerprint = currentFingerprint,
            indexStale = exists && currentFingerprint.isNotBlank() &&
                    (indexedFingerprint.isBlank() || indexedFingerprint != currentFingerprint),
            message = message.ifBlank { if (config.enabled) embedderMessage else "Semantic search is off." }
        )
        lastStatus = status
        return status
    }

    private fun indexExists(): Boolean =
        Files.exists(indexPath()) && runCatching {
            FSDirectory.open(indexPath()).use { DirectoryReader.indexExists(it) }
        }.getOrDefault(false)

    private fun readMeta(): Map<String, String> {
        val file = indexPath().resolve(META_FILE)
        if (!Files.exists(file)) return emptyMap()
        return runCatching {
            Files.readAllLines(file, StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun indexPath(): Path = explicitIndexPath ?: cacheRoot.resolve("lucene").toAbsolutePath().normalize()

    private fun currentConfig(): SemanticRetrievalConfig {
        val fromFile = if (Files.exists(configPath)) {
            runCatching { json.decodeFromString<SemanticRetrievalConfig>(Files.readString(configPath, StandardCharsets.UTF_8)) }
                .getOrNull()
        } else {
            null
        } ?: SemanticRetrievalConfig()

        val envModel = firstNonBlank(System.getProperty("afield.semanticSearch.onnx.model"), System.getenv("AFIELD_SEMANTIC_SEARCH_ONNX_MODEL"))
        val envTokenizer = firstNonBlank(System.getProperty("afield.semanticSearch.onnx.tokenizer"), System.getenv("AFIELD_SEMANTIC_SEARCH_ONNX_TOKENIZER"))
        val envDimension = firstNonBlank(System.getProperty("afield.semanticSearch.dimension"), System.getenv("AFIELD_SEMANTIC_SEARCH_DIMENSION"))
            ?.toIntOrNull()
        val envMaxTokens = firstNonBlank(System.getProperty("afield.semanticSearch.maxTokens"), System.getenv("AFIELD_SEMANTIC_SEARCH_MAX_TOKENS"))
            ?.toIntOrNull()
        val envQueryPrefix = firstNonBlank(System.getProperty("afield.semanticSearch.queryPrefix"), System.getenv("AFIELD_SEMANTIC_SEARCH_QUERY_PREFIX"))
        val envDocumentPrefix = firstNonBlank(System.getProperty("afield.semanticSearch.documentPrefix"), System.getenv("AFIELD_SEMANTIC_SEARCH_DOCUMENT_PREFIX"))

        return fromFile.copy(
            modelPath = envModel ?: fromFile.modelPath,
            tokenizerPath = envTokenizer ?: fromFile.tokenizerPath,
            dimension = envDimension?.coerceIn(1, 1024) ?: fromFile.dimension,
            maxTokens = envMaxTokens?.coerceIn(16, 8192) ?: fromFile.maxTokens,
            queryPrefix = envQueryPrefix ?: fromFile.queryPrefix,
            documentPrefix = envDocumentPrefix ?: fromFile.documentPrefix
        )
    }

    private fun persistConfig(config: SemanticRetrievalConfig) {
        configPath.parent.createDirectories()
        Files.writeString(configPath, json.encodeToString(config), StandardCharsets.UTF_8)
    }

    private fun resetEmbedder() {
        embedder?.close()
        embedder = null
        embedderMessage = ""
        lastStatus = null
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun downloadFile(url: String, target: Path) {
        target.parent?.createDirectories()
        val tmp = target.resolveSibling("${target.fileName}.part")
        val request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("User-Agent", "Field4 semantic-search")
            .build()
        val response = downloadClient.send(request, HttpResponse.BodyHandlers.ofFile(tmp))
        if (response.statusCode() !in 200..299) {
            runCatching { Files.deleteIfExists(tmp) }
            error("Download failed for $url: HTTP ${response.statusCode()}")
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun fileState(rawPath: String): SemanticRetrievalFileState {
        if (rawPath.isBlank()) return SemanticRetrievalFileState()
        val path = Paths.get(rawPath).toAbsolutePath().normalize()
        val exists = Files.exists(path)
        return SemanticRetrievalFileState(
            path = path.absolutePathString(),
            exists = exists,
            sizeBytes = if (exists && Files.isRegularFile(path)) Files.size(path) else 0L,
            lastModifiedEpochMs = if (exists) runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L) else 0L
        )
    }

    private fun directorySize(path: Path): Long {
        if (!Files.exists(path)) return 0L
        return runCatching {
            Files.walk(path).use { stream ->
                stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
            }
        }.getOrDefault(0L)
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private data class CapsuleBuild(
        val capsules: List<SemanticCapsule>,
        val workspaceDocuments: Int,
        val workspaceSidecarFiles: Int,
        val corpusFingerprint: String
    )

    private data class WorkspaceSidecarRecord(
        val documentName: String,
        val documentDir: Path,
        val metadataMtime: Long,
        val boxId: String,
        val boxName: String,
        val property: String,
        val language: String,
        val file: Path,
        val relativePath: String,
        val sizeBytes: Long,
        val modifiedEpochMs: Long
    )

    private fun buildCapsules(): CapsuleBuild {
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val kotlinCapsules = sourceCapsules(root, index)
        val docsCapsules = docsCapsules(root)
        val workspaceRecords = workspaceSidecarRecords()
        val workspaceCapsules = workspaceSidecarCapsules(workspaceRecords)
        val allLeafCapsules = kotlinCapsules + docsCapsules + workspaceCapsules
        val subsystemCapsules = subsystemCapsules(allLeafCapsules)
        val repositoryCapsule = SemanticCapsule(
            id = "repo:field4",
            scale = "repository",
            corpus = "field-internals",
            kind = "repository",
            symbol = "Field4",
            title = "Field4 repository",
            path = ".",
            line = 1,
            metadata = "Indexed at ${Instant.now()}; declarations=${kotlinCapsules.size}; documentation_sections=${docsCapsules.size}; workspace_sidecars=${workspaceCapsules.size}."
        )
        return CapsuleBuild(
            capsules = allLeafCapsules + subsystemCapsules + repositoryCapsule,
            workspaceDocuments = workspaceRecords.map { it.documentName }.toSet().size,
            workspaceSidecarFiles = workspaceRecords.size,
            corpusFingerprint = corpusFingerprint(workspaceRecords)
        )
    }

    private fun workspaceSidecarCapsules(records: List<WorkspaceSidecarRecord>): List<SemanticCapsule> {
        return records.mapNotNull { record ->
            val source = runCatching { Files.readString(record.file, StandardCharsets.UTF_8) }.getOrNull()
                ?.take(80_000)
                ?: return@mapNotNull null
            if (source.isBlank()) return@mapNotNull null
            val firstLine = source.lineSequence().firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()
            val hash = source.sha256Id()
            val lineCount = source.count { it == '\n' } + 1
            val symbol = listOf(record.documentName, record.boxName.ifBlank { record.boxId }, record.property)
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            SemanticCapsule(
                id = "workspace:${record.documentName}:${record.boxId}:${record.property}:$hash".sha256Id(),
                scale = "declaration",
                corpus = "workspace-sidecars",
                kind = "sidecar-${record.property}",
                symbol = symbol,
                title = "${record.boxName.ifBlank { record.boxId }}.${record.property}",
                path = record.relativePath,
                line = 1,
                endLine = lineCount,
                parentId = "workspace-box:${record.documentName}:${record.boxId}",
                documentName = record.documentName,
                boxName = record.boxName,
                boxId = record.boxId,
                property = record.property,
                language = record.language,
                signature = firstLine,
                metadata = buildString {
                    appendLine("document: ${record.documentName}")
                    appendLine("box: ${record.boxName}")
                    appendLine("box_uid: ${record.boxId}")
                    appendLine("property: ${record.property}")
                    appendLine("language: ${record.language}")
                    appendLine("file_hash: $hash")
                    appendLine("byte_size: ${record.sizeBytes}")
                    appendLine("modified_epoch_ms: ${record.modifiedEpochMs}")
                }.trim(),
                source = source
            )
        }
    }

    private fun workspaceSidecarRecords(): List<WorkspaceSidecarRecord> {
        val root = workspaceRootPath() ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        val context = SavingContext()
        val out = mutableListOf<WorkspaceSidecarRecord>()

        workspaceDocumentDirs(root).forEach { documentDir ->
            val metadataFile = documentDir.resolve(SavingContext.METADATA_EDN)
            val documentName = documentDir.fileName?.toString().orEmpty()
            val metadataMtime = runCatching { Files.getLastModifiedTime(metadataFile).toMillis() }.getOrDefault(0L)
            val transfers = runCatching { context.readEdnTransfers(documentDir.toString()) }.getOrDefault(emptyList())
            transfers.forEach { transfer ->
                if (transfer.id == "root") return@forEach
                if (transfer.properties["_tombstone"].isTrueLike()) return@forEach
                val boxName = transfer.properties["name"]?.toString().orEmpty()
                for ((property, extension) in Box.ioSeparateProperties) {
                    if (!transfer.properties.containsKey(property)) continue
                    val sidecar = documentDir.resolve("${transfer.id}_${property}_.$extension").normalize()
                    if (!Files.isRegularFile(sidecar)) continue
                    val relative = root.relativize(sidecar).toString().replace("\\", "/")
                    out += WorkspaceSidecarRecord(
                        documentName = documentName,
                        documentDir = documentDir,
                        metadataMtime = metadataMtime,
                        boxId = transfer.id,
                        boxName = boxName,
                        property = property,
                        language = sidecarLanguage(property, extension, transfer.properties),
                        file = sidecar,
                        relativePath = relative,
                        sizeBytes = runCatching { Files.size(sidecar) }.getOrDefault(0L),
                        modifiedEpochMs = runCatching { Files.getLastModifiedTime(sidecar).toMillis() }.getOrDefault(0L)
                    )
                }
            }
        }
        return out.sortedWith(compareBy<WorkspaceSidecarRecord> { it.documentName }.thenBy { it.boxId }.thenBy { it.property })
    }

    private fun workspaceDocumentDirs(root: Path): List<Path> {
        return runCatching {
            Files.list(root).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .filter { path ->
                        val name = path.fileName?.toString().orEmpty()
                        name.isNotBlank() &&
                                !name.startsWith(".") &&
                                name != "_projects" &&
                                !name.equals("backups", ignoreCase = true) &&
                                !name.equals("_backups", ignoreCase = true) &&
                                Files.isRegularFile(path.resolve(SavingContext.METADATA_EDN))
                    }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun workspaceRootPath(): Path? {
        val configured = firstNonBlank(
            System.getProperty("afield.semanticSearch.workspaceRoot"),
            System.getenv("AFIELD_SEMANTIC_SEARCH_WORKSPACE_ROOT")
        )
        return runCatching {
            Paths.get(configured ?: home).toAbsolutePath().normalize()
        }.getOrNull()
    }

    private fun sidecarLanguage(property: String, extension: String, properties: Map<String, Any?>): String {
        if (property == "code") {
            properties["code_language"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                return it.lowercase(Locale.ROOT)
            }
            properties["mainLanguage"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                return it.lowercase(Locale.ROOT)
            }
        }
        return when (extension.lowercase(Locale.ROOT)) {
            "kt", "kts" -> "kotlin"
            "glsl" -> "glsl"
            "slang" -> "slang"
            "stacksl" -> "stacksl"
            "js" -> "javascript"
            "py" -> "python"
            "md" -> "markdown"
            "yaml", "yml" -> "yaml"
            else -> extension.lowercase(Locale.ROOT)
        }
    }

    private fun corpusFingerprint(records: List<WorkspaceSidecarRecord>): String {
        val payload = buildString {
            records.forEach { record ->
                append(record.relativePath).append('\t')
                append(record.documentName).append('\t')
                append(record.boxId).append('\t')
                append(record.boxName).append('\t')
                append(record.property).append('\t')
                append(record.language).append('\t')
                append(record.sizeBytes).append('\t')
                append(record.modifiedEpochMs).append('\t')
                append(record.metadataMtime).append('\n')
            }
        }
        return payload.sha256Id()
    }

    private fun currentCorpusFingerprint(): String = corpusFingerprint(workspaceSidecarRecords())

    private fun Any?.isTrueLike(): Boolean = when (this) {
        is Boolean -> this
        is Number -> this.toInt() != 0
        is String -> this.equals("true", ignoreCase = true) || this == "1"
        else -> false
    }

    private fun sourceCapsules(root: Path, index: KotlinSourceIndexDb?): List<SemanticCapsule> {
        val definitions = index?.definitions.orEmpty().ifEmpty { scanDefinitions(root) }
        val linesCache = HashMap<String, List<String>>()
        fun lines(path: String): List<String> {
            return linesCache.getOrPut(path) {
                val file = root.resolve(path).normalize()
                runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
            }
        }

        return definitions.mapNotNull { def ->
            val fileLines = lines(def.filePath)
            val start = (def.line - 1).coerceAtLeast(0)
            if (start >= fileLines.size) return@mapNotNull null
            val source = declarationSource(fileLines, start)
            val signature = source.lineSequence().firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()
            val calls = extractCallNames(source).take(32)
            val parentId = "container:${def.filePath}"
            val metadata = buildString {
                appendLine("file_hash: ${def.contentHash}")
                appendLine("package: ${packageName(fileLines)}")
                if (!def.completionCategory.isNullOrBlank()) appendLine("completion_category: ${def.completionCategory}")
                if (calls.isNotEmpty()) {
                    appendLine("calls:")
                    calls.forEach { appendLine("- $it") }
                }
            }.trim()
            SemanticCapsule(
                id = "decl:${def.filePath}:${def.line}:${def.kind}:${def.name}".sha256Id(),
                scale = "declaration",
                corpus = "field-internals",
                kind = def.kind,
                symbol = def.name,
                title = def.name.substringAfterLast('.'),
                path = def.filePath,
                line = def.line,
                endLine = start + source.lines().size,
                parentId = parentId,
                signature = signature,
                documentation = def.kdoc.orEmpty(),
                metadata = metadata,
                source = source
            )
        } + containerCapsules(root, definitions)
    }

    private fun containerCapsules(root: Path, definitions: List<KotlinDefinitionRecord>): List<SemanticCapsule> {
        return definitions.groupBy { it.filePath }.map { (path, defs) ->
            val file = root.resolve(path).normalize()
            val packageName = runCatching { packageName(Files.readAllLines(file, StandardCharsets.UTF_8)) }.getOrDefault("")
            SemanticCapsule(
                id = "container:$path",
                scale = "container",
                corpus = "field-internals",
                kind = "file",
                symbol = path,
                title = path.substringAfterLast('/'),
                path = path,
                line = 1,
                metadata = buildString {
                    appendLine("package: $packageName")
                    appendLine("declarations:")
                    defs.take(80).forEach { appendLine("- ${it.kind} ${it.name}:${it.line}") }
                }.trim()
            )
        }
    }

    private fun docsCapsules(root: Path): List<SemanticCapsule> {
        val docsRoot = root.resolve("public/docs")
        if (!Files.exists(docsRoot)) return emptyList()
        val out = mutableListOf<SemanticCapsule>()
        Files.walk(docsRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".md") }
                .forEach { file ->
                    val rel = root.relativize(file).toString().replace("\\", "/")
                    val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
                    val sections = markdownSections(lines)
                    sections.forEachIndexed { idx, section ->
                        val title = section.title.ifBlank { rel.substringAfterLast('/') }
                        out += SemanticCapsule(
                            id = "docs:$rel:${section.line}:$idx".sha256Id(),
                            scale = "container",
                            corpus = "documentation",
                            kind = "docs-section",
                            symbol = title,
                            title = title,
                            path = rel,
                            line = section.line,
                            endLine = section.endLine,
                            parentId = "subsystem:docs:${rel.substringBeforeLast('/', "")}",
                            documentation = section.body.take(10_000),
                            metadata = "markdown_heading_level: ${section.level}"
                        )
                    }
                }
        }
        return out
    }

    private fun subsystemCapsules(capsules: List<SemanticCapsule>): List<SemanticCapsule> {
        val bySubsystem = capsules
            .filter { it.path.isNotBlank() && it.path != "." }
            .groupBy { capsule ->
                when {
                    capsule.path.startsWith("public/docs/") -> "docs:${capsule.path.removePrefix("public/docs/").substringBefore('/')}"
                    capsule.path.startsWith("src/") -> capsule.path.split('/').take(5).joinToString("/")
                    else -> capsule.path.substringBefore('/')
                }
            }
        return bySubsystem.map { (subsystem, rows) ->
            SemanticCapsule(
                id = "subsystem:$subsystem",
                scale = "subsystem",
                corpus = rows.groupingBy { it.corpus }.eachCount().maxByOrNull { it.value }?.key ?: "mixed",
                kind = "subsystem",
                symbol = subsystem,
                title = subsystem,
                path = rows.minByOrNull { it.path }?.path.orEmpty(),
                line = 1,
                metadata = buildString {
                    appendLine("items: ${rows.size}")
                    rows.take(100).forEach { appendLine("- ${it.kind} ${it.symbol} ${it.path}:${it.line}") }
                }.trim()
            )
        }
    }

    private fun scanDefinitions(root: Path): List<KotlinDefinitionRecord> {
        val out = mutableListOf<KotlinDefinitionRecord>()
        val sourceRoots = listOf("src/jvmMain/kotlin", "src/commonMain/kotlin", "src/personal/kotlin")
            .map { root.resolve(it) }
            .filter { Files.exists(it) }
        sourceRoots.forEach { sourceRoot ->
            Files.walk(sourceRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                    val rel = root.relativize(file).toString().replace("\\", "/")
                    val text = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrDefault("")
                    val hash = text.sha256Id()
                    Regex("""(?m)^(\s*)(?:[\w\s@().<>"]+\s+)?(fun|val|var|class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)""")
                        .findAll(text)
                        .forEach { match ->
                            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                            out += KotlinDefinitionRecord(
                                name = match.groupValues[3],
                                kind = match.groupValues[2],
                                filePath = rel,
                                line = line,
                                fileId = null,
                                contentHash = hash
                            )
                        }
                }
            }
        }
        return out
    }

    private fun SemanticCapsule.toLuceneDocument(intentVector: FloatArray?, implementationVector: FloatArray?): Document =
        Document().also { doc ->
            doc.add(StringField("id", id, Field.Store.YES))
            doc.add(StringField("scale", scale, Field.Store.YES))
            doc.add(StringField("corpus", corpus, Field.Store.YES))
            doc.add(StringField("kind", kind, Field.Store.YES))
            doc.add(StringField("path", path, Field.Store.YES))
            doc.add(StringField("parentId", parentId, Field.Store.YES))
            doc.add(StringField("documentName", documentName, Field.Store.YES))
            doc.add(StringField("boxId", boxId, Field.Store.YES))
            doc.add(StringField("property", property, Field.Store.YES))
            doc.add(StringField("language", language, Field.Store.YES))
            doc.add(StoredField("boxName", boxName))
            doc.add(StoredField("symbol", symbol))
            doc.add(StoredField("title", title))
            doc.add(TextField("symbolText", splitIdentifier(symbol), Field.Store.NO))
            doc.add(TextField("titleText", splitIdentifier(title), Field.Store.NO))
            doc.add(TextField("boxNameText", splitIdentifier(boxName), Field.Store.NO))
            doc.add(TextField("signature", signature, Field.Store.YES))
            doc.add(TextField("documentation", documentation, Field.Store.YES))
            doc.add(TextField("metadata", metadata, Field.Store.YES))
            doc.add(TextField("source", source.take(30_000), Field.Store.YES))
            doc.add(TextField("all", lexicalText().take(80_000), Field.Store.NO))
            doc.add(StoredField("line", line))
            doc.add(StoredField("endLine", endLine))
            doc.add(StoredField("capsule", intentText().take(30_000)))
            if (intentVector != null) doc.add(KnnFloatVectorField(INTENT_VECTOR, intentVector, VectorSimilarityFunction.COSINE))
            if (implementationVector != null) doc.add(KnnFloatVectorField(IMPLEMENTATION_VECTOR, implementationVector, VectorSimilarityFunction.COSINE))
        }

    private fun buildBm25Query(raw: String): Query {
        val parser = MultiFieldQueryParser(
            arrayOf("all", "documentation", "metadata", "source", "signature", "title"),
            analyzer,
            mapOf("title" to 2.5f, "signature" to 2.0f, "documentation" to 1.6f, "metadata" to 1.3f)
        )
        return runCatching { parser.parse(MultiFieldQueryParser.escape(raw)) }
            .getOrElse { TermQuery(Term("all", raw.lowercase(Locale.ROOT))) }
    }

    private fun buildSymbolQuery(raw: String): Query {
        val builder = BooleanQuery.Builder()
        queryTokens(raw).forEach { token ->
            builder.add(TermQuery(Term("symbolText", token)), BooleanClause.Occur.SHOULD)
            builder.add(TermQuery(Term("titleText", token)), BooleanClause.Occur.SHOULD)
            builder.add(TermQuery(Term("boxNameText", token)), BooleanClause.Occur.SHOULD)
            if (token.length > 2) builder.add(TermQuery(Term("signature", token)), BooleanClause.Occur.SHOULD)
        }
        return builder.build().takeIf { it.clauses().isNotEmpty() } ?: buildBm25Query(raw)
    }

    private fun Array<ScoreDoc>.toRanked(
        searcher: IndexSearcher,
        filter: (Document) -> Boolean,
        scoreScale: Float = 1f
    ): List<RankedDoc> =
        mapIndexedNotNull { idx, sd ->
            val doc = searcher.storedFields().document(sd.doc)
            if (!filter(doc)) null else RankedDoc(sd.doc, idx + 1, sd.score * scoreScale)
        }

    private fun mergeVectorRanks(a: List<RankedDoc>, b: List<RankedDoc>): List<RankedDoc> {
        val merged = LinkedHashMap<Int, RankedDoc>()
        (a + b).forEach { row ->
            val existing = merged[row.docId]
            if (existing == null || row.score > existing.score) {
                merged[row.docId] = row
            }
        }
        return merged.values.sortedByDescending { it.score }.mapIndexed { idx, row -> row.copy(rank = idx + 1) }
    }

    private fun fuseRanks(
        searcher: IndexSearcher,
        vector: List<RankedDoc>,
        bm25: List<RankedDoc>,
        symbol: List<RankedDoc>,
        limit: Int,
        request: SemanticRetrievalQueryRequest
    ): List<SemanticRetrievalHit> {
        val ids = LinkedHashSet<Int>()
        ids += vector.map { it.docId }
        ids += bm25.map { it.docId }
        ids += symbol.map { it.docId }
        val vectorByDoc = vector.associateBy { it.docId }
        val bm25ByDoc = bm25.associateBy { it.docId }
        val symbolByDoc = symbol.associateBy { it.docId }
        return ids.map { docId ->
            val vectorHit = vectorByDoc[docId]
            val bm25Hit = bm25ByDoc[docId]
            val symbolHit = symbolByDoc[docId]
            val score = rrf(vectorHit?.rank) + rrf(bm25Hit?.rank) + 0.5 * rrf(symbolHit?.rank)
            val doc = searcher.storedFields().document(docId)
            hitFromDoc(
                doc = doc,
                score = score,
                vector = vectorHit,
                bm25 = bm25Hit,
                symbol = symbolHit,
                includeCapsule = request.includeCapsule,
                includeSource = request.includeSource
            )
        }.sortedByDescending { it.score }
            .take(limit)
    }

    private fun hitFromDoc(
        doc: Document,
        score: Double,
        vector: RankedDoc?,
        bm25: RankedDoc?,
        symbol: RankedDoc?,
        includeCapsule: Boolean,
        includeSource: Boolean
    ): SemanticRetrievalHit =
        SemanticRetrievalHit(
            id = doc.get("id").orEmpty(),
            scale = doc.get("scale").orEmpty(),
            corpus = doc.get("corpus").orEmpty(),
            kind = doc.get("kind").orEmpty(),
            symbol = doc.get("symbol").orEmpty(),
            title = doc.get("title").orEmpty(),
            path = doc.get("path").orEmpty(),
            line = doc.getField("line")?.numericValue()?.toInt() ?: 0,
            endLine = doc.getField("endLine")?.numericValue()?.toInt() ?: 0,
            parentId = doc.get("parentId").orEmpty(),
            documentName = doc.get("documentName").orEmpty(),
            boxName = doc.get("boxName").orEmpty(),
            boxId = doc.get("boxId").orEmpty(),
            property = doc.get("property").orEmpty(),
            language = doc.get("language").orEmpty(),
            score = score,
            vectorRank = vector?.rank ?: 0,
            bm25Rank = bm25?.rank ?: 0,
            symbolRank = symbol?.rank ?: 0,
            vectorScore = vector?.score ?: 0f,
            bm25Score = bm25?.score ?: 0f,
            symbolScore = symbol?.score ?: 0f,
            capsule = if (includeCapsule) doc.get("capsule").orEmpty() else "",
            sourcePreview = if (includeSource) doc.get("source").orEmpty().take(2000) else doc.get("signature").orEmpty()
        )

    private fun rrf(rank: Int?, k: Int = 60): Double = if (rank == null || rank <= 0) 0.0 else 1.0 / (k + rank)

    private data class RankedDoc(val docId: Int, val rank: Int, val score: Float)

    private data class MarkdownSection(val title: String, val level: Int, val line: Int, val endLine: Int, val body: String)

    private fun markdownSections(lines: List<String>): List<MarkdownSection> {
        if (lines.isEmpty()) return emptyList()
        val headings = lines.mapIndexedNotNull { idx, line ->
            val match = Regex("""^(#{1,4})\s+(.+?)\s*$""").matchEntire(line) ?: return@mapIndexedNotNull null
            Triple(idx, match.groupValues[1].length, match.groupValues[2].trim())
        }
        if (headings.isEmpty()) {
            return listOf(MarkdownSection("", 1, 1, lines.size, lines.joinToString("\n").take(12_000)))
        }
        return headings.mapIndexed { index, heading ->
            val start = heading.first
            val endExclusive = headings.getOrNull(index + 1)?.first ?: lines.size
            MarkdownSection(
                title = heading.third,
                level = heading.second,
                line = start + 1,
                endLine = endExclusive,
                body = lines.subList(start, endExclusive).joinToString("\n").take(12_000)
            )
        }
    }

    private fun declarationSource(lines: List<String>, start: Int, maxLines: Int = 220): String {
        var depth = 0
        var seenBrace = false
        val out = mutableListOf<String>()
        for (idx in start until (start + maxLines).coerceAtMost(lines.size)) {
            val line = lines[idx]
            out += line
            line.forEach { ch ->
                if (ch == '{') {
                    depth++
                    seenBrace = true
                } else if (ch == '}') {
                    depth--
                }
            }
            if (seenBrace && depth <= 0) break
            if (!seenBrace && idx > start && line.isBlank()) break
        }
        return out.joinToString("\n").trimEnd()
    }

    private fun packageName(lines: List<String>): String =
        lines.firstNotNullOfOrNull { line ->
            Regex("""^\s*package\s+([A-Za-z0-9_.]+)""").find(line)?.groupValues?.get(1)
        }.orEmpty()

    private fun extractCallNames(source: String): List<String> =
        Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filterNot { it in kotlinKeywords }
            .distinct()
            .toList()

    private fun splitIdentifier(raw: String): String {
        val spaced = raw
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("""([a-z0-9])([A-Z])"""), "$1 $2")
        return "$raw $spaced"
    }

    private fun queryTokens(raw: String): List<String> =
        splitIdentifier(raw).lowercase(Locale.ROOT)
            .split(Regex("""[^a-z0-9_]+"""))
            .filter { it.length >= 2 }
            .distinct()

    private val kotlinKeywords = setOf(
        "if", "for", "while", "when", "return", "throw", "catch", "fun", "class", "object", "interface",
        "val", "var", "try", "else", "do", "is", "as", "in", "super", "this"
    )
}

class OnnxSemanticEmbedder private constructor(
    private val config: Config,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: HuggingFaceTokenizer
) : SemanticEmbedder {
    override val dimension: Int = config.dimension
    override val backend: String = "onnx-runtime-java:${config.modelPath?.fileName ?: "model.onnx"}"

    override fun encodeQueries(texts: List<String>): List<FloatArray> =
        encode(texts.map { config.queryPrefix + it })

    override fun encodeDocuments(texts: List<String>): List<FloatArray> =
        encode(texts.map { config.documentPrefix + it })

    private fun encode(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val encodings = tokenizer.batchEncode(texts.toTypedArray())
        val maxLength = encodings.maxOfOrNull { it.ids.size }?.coerceAtMost(config.maxTokens) ?: 0
        if (maxLength <= 0) return texts.map { FloatArray(dimension) }
        val ids = Array(encodings.size) { LongArray(maxLength) }
        val mask = Array(encodings.size) { LongArray(maxLength) }
        val typeIds = Array(encodings.size) { LongArray(maxLength) }
        encodings.forEachIndexed { batch, encoding ->
            copyPadded(encoding.ids, ids[batch], maxLength)
            copyPadded(encoding.attentionMask, mask[batch], maxLength)
            copyPadded(encoding.typeIdsOrEmpty(), typeIds[batch], maxLength)
        }
        val inputs = LinkedHashMap<String, OnnxTensor>()
        fun addIfExpected(name: String, value: Array<LongArray>) {
            if (session.inputNames.contains(name)) {
                inputs[name] = OnnxTensor.createTensor(env, value)
            }
        }
        addIfExpected("input_ids", ids)
        addIfExpected("attention_mask", mask)
        addIfExpected("token_type_ids", typeIds)
        session.run(inputs).use { result ->
            inputs.values.forEach { it.close() }
            val raw = result.firstOrNull()?.value
            return pooledVectors(raw, mask).map { it.normalized() }
        }
    }

    override fun close() {
        runCatching { tokenizer.close() }
        runCatching { session.close() }
    }

    private fun pooledVectors(raw: Any?, mask: Array<LongArray>): List<FloatArray> {
        return when (raw) {
            is Array<*> -> {
                val first = raw.firstOrNull()
                when (first) {
                    is FloatArray -> raw.filterIsInstance<FloatArray>().map { it.copyOf(dimension) }
                    is Array<*> -> poolTokenEmbeddings(raw, mask)
                    else -> mask.map { FloatArray(dimension) }
                }
            }
            is FloatArray -> listOf(raw.copyOf(dimension))
            else -> mask.map { FloatArray(dimension) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun poolTokenEmbeddings(raw: Array<*>, mask: Array<LongArray>): List<FloatArray> {
        return raw.mapIndexed { batch, item ->
            val tokens = item as? Array<FloatArray> ?: return@mapIndexed FloatArray(dimension)
            val out = FloatArray(dimension)
            var count = 0
            tokens.forEachIndexed { tokenIndex, vector ->
                if ((mask.getOrNull(batch)?.getOrNull(tokenIndex) ?: 0L) == 0L) return@forEachIndexed
                val upto = minOf(dimension, vector.size)
                for (i in 0 until upto) out[i] += vector[i]
                count++
            }
            if (count > 0) for (i in out.indices) out[i] /= count.toFloat()
            out
        }
    }

    data class Config(
        val modelPath: Path?,
        val tokenizerPath: Path?,
        val dimension: Int,
        val maxTokens: Int,
        val queryPrefix: String,
        val documentPrefix: String
    ) {
        fun createOrNull(): OnnxSemanticEmbedder? {
            val model = modelPath?.takeIf { it.exists() } ?: return null
            val tokenizerFile = tokenizerPath?.takeIf { it.exists() } ?: return null
            return runCatching {
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(model.absolutePathString(), OrtSession.SessionOptions())
                val tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optTruncation(true)
                    .optPadding(true)
                    .optMaxLength(maxTokens)
                    .build()
                OnnxSemanticEmbedder(this, env, session, tokenizer)
            }.onFailure {
                lprintln("warning,semantic-search", "failed to load ONNX embedder: ${it.message ?: it.javaClass.name}")
            }.getOrNull()
        }

        companion object {
            fun fromSettings(settings: SemanticRetrievalConfig): Config {
                return Config(
                    modelPath = settings.modelPath.trim().takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath().normalize() },
                    tokenizerPath = settings.tokenizerPath.trim().takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath().normalize() },
                    dimension = settings.dimension.coerceIn(1, 1024),
                    maxTokens = settings.maxTokens.coerceIn(16, 8192),
                    queryPrefix = settings.queryPrefix,
                    documentPrefix = settings.documentPrefix
                )
            }

            fun disabledReason(config: Config): String =
                when {
                    config.modelPath == null -> "Set afield.semanticSearch.onnx.model / AFIELD_SEMANTIC_SEARCH_ONNX_MODEL to enable dense vectors."
                    !config.modelPath.exists() -> "Configured ONNX model does not exist: ${config.modelPath.absolutePathString()}"
                    config.tokenizerPath == null -> "Set afield.semanticSearch.onnx.tokenizer / AFIELD_SEMANTIC_SEARCH_ONNX_TOKENIZER to enable dense vectors."
                    !config.tokenizerPath.exists() -> "Configured tokenizer does not exist: ${config.tokenizerPath.absolutePathString()}"
                    else -> "ONNX embedder is configured but failed to load."
                }
        }
    }
}

private fun copyPadded(source: LongArray, target: LongArray, maxLength: Int) {
    val upto = minOf(source.size, maxLength)
    for (i in 0 until upto) target[i] = source[i]
}

private fun Encoding.typeIdsOrEmpty(): LongArray =
    runCatching { typeIds }.getOrDefault(LongArray(ids.size))

private fun FloatArray.normalized(): FloatArray {
    var sum = 0.0
    forEach { sum += (it * it).toDouble() }
    val norm = sqrt(sum).toFloat()
    if (norm <= 0f) return this
    for (i in indices) this[i] /= norm
    return this
}

private fun String.sha256Id(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
