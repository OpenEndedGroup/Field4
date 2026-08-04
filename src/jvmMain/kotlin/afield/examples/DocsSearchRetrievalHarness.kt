package afield.examples

import afield.ai.SemanticRetrievalQueryRequest
import afield.ai.SemanticRetrievalService
import afield.ai.core.GlslIncludeLibraryIndex

/**
 * A/B eyeball harness for the local docs-search retrieval path.
 *
 * Runs a fixed (or command-line supplied) set of queries through exactly what the
 * rewritten [afield.ai.core.DocsSearchBridge] uses for grounding — the native
 * Lucene hybrid index ([SemanticRetrievalService]) plus the local GLSL include
 * manifest ([GlslIncludeLibraryIndex]) — and prints the ranked hits. **No LLM is
 * called**, so it costs nothing and needs no API key: it lets you judge retrieval
 * quality against the old OpenAI hosted `file_search` before committing to the swap.
 *
 * Run:
 *   ./gradlew runDocsSearchRetrievalHarness
 *   ./gradlew runDocsSearchRetrievalHarness --args="how do I create a render target"
 *
 * Vector (semantic) ranking is only active when the local ONNX embedder is
 * configured (downloaded from /welcome → Semantic search, or set via
 * -Dafield.semanticSearch.onnx.model / .onnx.tokenizer). Without it you still get
 * BM25 + symbol ranking, and the header says which mode you're in.
 */
object DocsSearchRetrievalHarness {
    private val DEFAULT_QUERIES = listOf(
        "how do I create and use a render target",
        "swap chain present mode and frame pacing",
        "dot product of two vectors and what it measures",
        "how does the agent tool registry expose tools to the model",
        "native Lucene semantic retrieval index",
        "PinnedConsole logging to the editor",
        "OpenXR VR frame pacing on Linux",
        "glsl noise include function for shaders"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val queries = if (args.isNotEmpty()) listOf(args.joinToString(" ").trim()) else DEFAULT_QUERIES
        val limit = (System.getProperty("harness.limit") ?: "8").toIntOrNull()?.coerceIn(1, 40) ?: 8

        println("=== Field4 docs-search retrieval harness (local, no LLM) ===")

        val forceRebuild = System.getProperty("harness.rebuild") == "true"
        val status0 = SemanticRetrievalService.status()
        if (!status0.indexExists || forceRebuild) {
            val why = if (!status0.indexExists) "index missing" else "harness.rebuild=true"
            println("Rebuilding index ($why) — scans source + docs; computes embeddings if the ONNX embedder is enabled (can take minutes)...")
            val build = SemanticRetrievalService.rebuild()
            println("  rebuild: success=${build.success} capsules=${build.status.indexedCapsules} ${build.message}")
        }

        val status = SemanticRetrievalService.status()
        val model = SemanticRetrievalService.modelInfo()
        val vectorActive = status.embedderBackend != "disabled" && status.embeddingDimension > 0
        println("index: exists=${status.indexExists} docs=${status.indexedDocuments} capsules=${status.indexedCapsules} stale=${status.indexStale} semanticSearchEnabled=${status.enabled}")
        println("corpora: ${SemanticRetrievalService.availableCorpora.joinToString(", ")}")
        if (vectorActive) {
            println("ranking: BM25 + symbol + VECTOR (embedder=${model.backend} dim=${status.embeddingDimension})")
        } else {
            println("ranking: BM25 + symbol only — vector INACTIVE: ${status.message.ifBlank { model.message.ifBlank { "no local ONNX embedder configured" } }}")
            println("  → for a fair A/B vs OpenAI's hosted embeddings, enable the local vector leg:")
            println("    1) /welcome → Semantic search → download the granite embedder (or set -Dafield.semanticSearch.onnx.model / .onnx.tokenizer)")
            println("    2) re-run with -Dharness.rebuild=true  (rebuilds the index WITH embeddings — required; a BM25-only index stores no vectors)")
        }
        if (status.indexStale && !forceRebuild) {
            println("  note: index is stale vs current source; add -Dharness.rebuild=true for fresh results.")
        }
        println("queries: ${queries.size}, top-$limit each\n")

        for (query in queries) {
            println("──────────────────────────────────────────────────────────────")
            println("Q: $query")
            val result = runCatching {
                SemanticRetrievalService.query(
                    SemanticRetrievalQueryRequest(
                        query = query,
                        limit = limit,
                        rebuildIfEmpty = true,
                        includeCapsule = true,
                        includeSource = true
                    )
                )
            }.getOrElse {
                println("  ERROR: ${it.message}")
                continue
            }

            if (!result.success) println("  (query reported failure: ${result.message})")
            if (result.results.isEmpty()) {
                println("  no hits")
            } else {
                result.results.forEachIndexed { i, hit ->
                    val loc = hit.path + if (hit.line > 0) ":${hit.line}" else ""
                    val sym = if (hit.symbol.isNotBlank()) "  ${hit.symbol}" else ""
                    println(
                        "  ${(i + 1).toString().padStart(2)}. [${hit.corpus}] $loc$sym" +
                            "   score=${"%.3f".format(hit.score)}  ranks(bm25=${hit.bm25Rank} vec=${hit.vectorRank} sym=${hit.symbolRank})"
                    )
                    val preview = hit.sourcePreview.ifBlank { hit.capsule }
                        .replace(Regex("\\s+"), " ").trim().take(180)
                    if (preview.isNotBlank()) println("      $preview")
                }
            }

            // Mirror the GLSL-include leg DocsSearchBridge adds for shader questions.
            val glsl = runCatching { GlslIncludeLibraryIndex.contextAttachmentFor(query) }.getOrNull()
            if (glsl != null) {
                println("  + GLSL include match: ${glsl.fileName} (${glsl.code.length} chars of include context)")
            }
            println()
        }

        println("Done. Eyeball whether the top hits are the files/docs you'd want a Claude/OpenAI answer grounded in.")
        println("Tip: pass your own query with --args=\"...\"; raise depth with -Dharness.limit=15.")
    }
}
