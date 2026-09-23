package afield.ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

data class RelatedContextHints(
    val guides: List<String> = emptyList(),
    val examples: List<String> = emptyList()
)

object RelatedContextDiscovery {
    private val catalog = RelatedContextCatalog(Paths.get(System.getProperty("user.dir")).normalize())

    fun forPath(rawPath: String?): RelatedContextHints = catalog.forPath(rawPath)
}

internal class RelatedContextCatalog(private val repoRoot: Path) {
    private val results = ConcurrentHashMap<String, RelatedContextHints>()
    private val connectedExample = "afield://ai4-examples/connected-compute-render"

    private val candidates: List<String> by lazy { collectFiles() }

    fun forPath(rawPath: String?): RelatedContextHints {
        val normalized = rawPath?.trim()?.replace('\\', '/')?.takeIf { it.isNotBlank() } ?: return RelatedContextHints()
        if (normalized.startsWith("afield://")) {
            return RelatedContextHints(examples = listOf(connectedExample))
        }

        val sourcePath = normalized.removePrefix("./")
        return results.computeIfAbsent(sourcePath) { discover(it) }
    }

    private fun discover(sourcePath: String): RelatedContextHints {
        val tokens = sourceTokens(sourcePath)
        if (tokens.isEmpty()) return RelatedContextHints()

        val guides = candidates.filter { it.endsWith(".md", ignoreCase = true) }
            .mapNotNull { candidate ->
                val score = scoreGuide(sourcePath, tokens, candidate)
                if (score <= 0) null else score to candidate
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
            .take(6)

        val examples = candidates.filter { "/examples/" in it && (it.endsWith(".kt") || it.endsWith(".kts")) }
            .mapNotNull { candidate ->
                val score = scoreExample(sourcePath, tokens, candidate)
                if (score <= 0) null else score to candidate
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
            .take(6)

        return RelatedContextHints(guides = guides, examples = examples)
    }

    // One bounded traversal. Never index nested checkouts, generated assets or dependency trees.
    private fun collectFiles(): List<String> {
        if (!Files.isDirectory(repoRoot)) return emptyList()
        val result = mutableListOf<String>()
        val excluded = setOf("build", "node_modules", "vendor", "target", "dist", "site", "worktrees")
        Files.walkFileTree(repoRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == repoRoot) return FileVisitResult.CONTINUE
                val name = dir.fileName.toString()
                if (name.startsWith(".") || name in excluded || Files.exists(dir.resolve(".git")))
                    return FileVisitResult.SKIP_SUBTREE
                // These are the only canonical documentation/example roots.
                if (dir.parent == repoRoot && name !in setOf("src", "dev_docs", "public", "docs"))
                    return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && (file.extension.equals("md", true) ||
                    ("/examples/" in relativize(file) && file.extension in setOf("kt", "kts"))))
                    result += relativize(file)
                return FileVisitResult.CONTINUE
            }
        })
        return result.sorted()
    }

    private fun relativize(path: Path): String =
        repoRoot.relativize(path.normalize()).invariantSeparatorsPathString

    private fun scoreGuide(sourcePath: String, tokens: Set<String>, candidate: String): Int {
        val candidateLower = candidate.lowercase()
        val fileName = candidate.substringAfterLast('/')
        var score = 0

        if (candidate == "src/VULKAN_GRAPHICS_SYSTEM.md" &&
            ("/afield/four/" in sourcePath || sourcePath.endsWith("/shaders.kt"))
        ) {
            score += 220
        }

        val sourceDir = sourcePath.substringBeforeLast('/', "")
        if (fileName.equals("README.md", ignoreCase = true)) {
            if (candidateLower == "$sourceDir/readme.md") {
                score += 180
            } else {
                val sourceParts = sourceDir.split('/').filter { it.isNotBlank() }
                val candidateDir = candidateLower.removeSuffix("/readme.md")
                val candidateParts = candidateDir.split('/').filter { it.isNotBlank() }
                if (sourceParts.startsWith(candidateParts)) {
                    val distance = sourceParts.size - candidateParts.size
                    score += (120 - distance * 18).coerceAtLeast(20)
                }
            }
        }

        if (score == 0 && tokenOverlap(tokens, sourceTokens(candidate)) == 0) return 0

        if (fileName.matches(Regex("[A-Z0-9_\\-]+\\.md"))) {
            score += 25
        }

        score += tokenOverlap(tokens, sourceTokens(candidate)) * 12

        if (candidateLower.startsWith("dev_docs/")) score += 12
        if (candidateLower.startsWith("src/")) score += 8
        if (candidateLower.contains("/agents/")) score -= 15

        if (sourcePath.contains("/texture/video/") && candidateLower.contains("video")) score += 30
        if (sourcePath.contains("/stdlib/") && candidateLower.contains("/stdlib/")) score += 28
        if (sourcePath.contains("/workspace/") && candidateLower.contains("/workspace/")) score += 28
        if (sourcePath.contains("/animation/") && candidateLower.contains("/animation/")) score += 28

        return score
    }

    private fun scoreExample(sourcePath: String, tokens: Set<String>, candidate: String): Int {
        val candidateTokens = sourceTokens(candidate)
        var score = tokenOverlap(tokens, candidateTokens) * 14
        val sourceStem = sourcePath.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val candidateStem = candidate.substringAfterLast('/').substringBeforeLast('.').lowercase()

        if (sourceStem == candidateStem) score += 200
        if (candidateStem.contains(sourceStem) || sourceStem.contains(candidateStem)) score += 90
        if (sourcePath.contains("/texture/video/") && candidate.lowercase().contains("/examples/video/")) score += 40
        if (sourcePath.contains("/four/") && candidate.lowercase().contains("/examples/four/")) score += 35
        if (candidate.lowercase().contains("readme")) score -= 20
        return score
    }

    private fun tokenOverlap(left: Set<String>, right: Set<String>): Int =
        left.intersect(right).size

    private fun sourceTokens(raw: String): Set<String> {
        val normalized = raw.replace('\\', '/')
        val split = normalized.split('/', '-', '_', '.', ' ')
        val tokens = linkedSetOf<String>()
        split.forEach { part ->
            camelCaseTokens(part).forEach { token ->
                val cleaned = token.trim().lowercase()
                if (cleaned.length >= 3 && cleaned !in ignoredTokens) {
                    tokens += cleaned
                }
            }
        }
        return tokens
    }

    private fun camelCaseTokens(part: String): List<String> =
        part.split(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"))

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        if (prefix.size > this.size) return false
        return this.subList(0, prefix.size) == prefix
    }

    private val ignoredTokens = setOf(
        "src", "main", "jvm", "common", "kotlin", "afield",
        "readme", "guide", "docs", "dev", "file", "files", "stdlib", "examples", "public", "assets"
    )
}
