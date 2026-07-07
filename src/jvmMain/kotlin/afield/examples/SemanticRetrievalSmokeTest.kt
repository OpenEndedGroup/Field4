package afield.examples

import afield.ai.SemanticRetrievalQueryRequest
import afield.ai.SemanticRetrievalService
import afield.stdlib.SemanticSearchManager
import java.nio.file.Files

/*
FIELD_TEST
id: examples.semantic-retrieval.lucene-baseline
runner: jvm-main
entrypoint: afield.examples.SemanticRetrievalSmokeTest
tags: [smoke, ai, semantic-retrieval, lucene]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "SemanticRetrievalSmokeTest ok"
*/
object SemanticRetrievalSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val tempDir = Files.createTempDirectory("afield-semantic-retrieval")
        System.setProperty("afield.semanticSearch.indexPath", tempDir.resolve("lucene").toString())
        val workspaceDir = tempDir.resolve("workspace")
        val documentDir = workspaceDir.resolve("wiggleDocument")
        Files.createDirectories(documentDir)
        Files.writeString(
            documentDir.resolve("metadata.edn"),
            """
            {"version" 2
             "boxes" [{"id" "box-a"
                       "properties" {"name" "Wiggly Line Box"
                                     "code_language" "kotlin"
                                     "code" "sidecar"}}
                      {"id" "box-b"
                       "properties" {"name" "Deleted Backup Box"
                                     "_tombstone" true
                                     "code" "sidecar"}}]}
            """.trimIndent()
        )
        Files.writeString(
            documentDir.resolve("box-a_code_.kt"),
            """
            fun drawRandomWigglyLine() {
                println("current workspace sidecar curve")
            }

            fun unrelatedWorkspaceHelper() {
                println("unrelated workspace helper")
            }
            """.trimIndent()
        )
        Files.writeString(
            documentDir.resolve("box-b_code_.kt"),
            """println("tombstoned backup-only sidecar")"""
        )
        Files.writeString(
            documentDir.resolve("orphan_code_.kt"),
            """println("orphan backup-only sidecar")"""
        )
        System.setProperty("afield.semanticSearch.workspaceRoot", workspaceDir.toString())
        System.setProperty("afield.semanticSearch.onnx.model", tempDir.resolve("missing-model.onnx").toString())
        System.setProperty("afield.semanticSearch.onnx.tokenizer", tempDir.resolve("missing-tokenizer.json").toString())

        val build = SemanticRetrievalService.rebuild()
        require(build.success) { build.message }
        require(build.status.indexExists) { "index was not created" }
        require(build.status.indexedCapsules > 20) { "expected source/docs capsules, got ${build.status.indexedCapsules}" }
        require(build.status.workspaceSidecarFiles == 1) {
            "expected exactly one current workspace sidecar, got ${build.status.workspaceSidecarFiles}"
        }
        require(!build.status.indexStale) { "freshly built index should not be stale" }

        val codeResult = SemanticRetrievalService.query(
            SemanticRetrievalQueryRequest(
                query = "semantic sidecar analyzes Field box Kotlin code and resolves source uses",
                limit = 8,
                includeSource = true
            )
        )
        require(codeResult.success) { codeResult.message }
        require(codeResult.results.isNotEmpty()) { "expected code search results" }
        require(codeResult.results.any { it.path.contains("CodeUseTelemetryService") || it.path.contains("code-use-semantic-sidecar") }) {
            "expected a code-use or sidecar result, got ${codeResult.results.map { it.path }}"
        }

        val docsResult = SemanticRetrievalService.query(
            SemanticRetrievalQueryRequest(
                query = "browser screenshot smoke with backdoor playwright",
                limit = 8,
                corpus = "documentation"
            )
        )
        require(docsResult.success) { docsResult.message }
        require(docsResult.results.isNotEmpty()) { "expected documentation search results" }

        val combinedResult = SemanticRetrievalService.query(
            SemanticRetrievalQueryRequest(
                query = "random wiggly line browser screenshot smoke",
                limit = 12,
                corpora = listOf("workspace", "documentation"),
                includeSource = true
            )
        )
        require(combinedResult.success) { combinedResult.message }
        require(combinedResult.results.isNotEmpty()) { "expected combined corpus search results" }
        require(combinedResult.results.all { it.corpus in setOf("workspace", "documentation") }) {
            "combined corpus filter leaked unexpected corpora: ${combinedResult.results.map { it.corpus }}"
        }
        require(combinedResult.results.any { it.corpus == "workspace" }) {
            "expected workspace in combined corpus results"
        }

        val workspaceFunctionResult = SemanticRetrievalService.query(
            SemanticRetrievalQueryRequest(
                query = "draw random wiggly line function",
                limit = 10,
                corpus = "workspace",
                documentName = "wiggleDocument",
                includeSource = true
            )
        )
        require(workspaceFunctionResult.success) { workspaceFunctionResult.message }
        require(workspaceFunctionResult.results.any {
            it.scale == "declaration" &&
                    it.kind == "fun" &&
                    it.symbol == "drawRandomWigglyLine" &&
                    it.documentName == "wiggleDocument" &&
                    it.boxName == "Wiggly Line Box" &&
                    it.property == "code" &&
                    it.line > 0
        }) {
            "expected workspace Kotlin function sub-capsule, got ${workspaceFunctionResult.results.map { "${it.scale}:${it.kind}:${it.symbol}:${it.line}" }}"
        }

        val managerHits = SemanticSearchManager().findTopK(
            query = "random wiggly line",
            k = 5,
            corpora = listOf("field-internals", "workspace"),
            includeSource = true
        )
        require(managerHits.isNotEmpty()) { "expected manager facade search results" }
        require(managerHits.first().location.isNotBlank()) { "expected manager hit location" }

        val workspaceHits = SemanticSearchManager().findWorkspaceTopK(
            query = "current workspace sidecar curve",
            k = 5,
            documentName = "wiggleDocument",
            includeSource = true
        )
        require(workspaceHits.isNotEmpty()) { "expected workspace sidecar search results" }
        require(workspaceHits.first().documentName == "wiggleDocument") { "expected document metadata on hit" }
        require(workspaceHits.first().boxName == "Wiggly Line Box") { "expected box name metadata on hit" }
        require(workspaceHits.first().boxId == "box-a") { "expected box id metadata on hit" }
        require(workspaceHits.first().property == "code") { "expected sidecar property metadata on hit" }
        require(workspaceHits.none { it.boxId == "box-b" || it.path.contains("orphan") }) {
            "tombstoned or orphan sidecars should not be indexed: $workspaceHits"
        }

        println("SemanticRetrievalSmokeTest ok capsules=${build.status.indexedCapsules} codeHits=${codeResult.results.size} docHits=${docsResult.results.size}")
    }
}
