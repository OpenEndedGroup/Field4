package afield.ai.tools

import afield.ai.retrieval.SemanticRetrievalHit
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Finding tutorial code: a worked example of box code to start from.
 *
 * Field ships about 260 tutorial documents: the materialized agent transcripts under
 * `public/docs/assets/agent_transcripts/<name>/` and the graphics curriculum in
 * `public/docs/assets/canonical_graphics/`. Semantic search indexes them box by box as its `tutorials` corpus,
 * the way it indexes a workspace; this tool is a query on that corpus, grouped by document (by lesson for the
 * graphics curriculum, whose boxes are its lessons), with each document's description and docs links from its
 * transcript and the code files to read. A match names files rather than quoting them: read one with
 * [AttachmentRequest] to see the box code itself.
 */

/**
 * Ask for tutorials by what they do. The answer is a [TutorialCodeSearchResult].
 */
@JsonClassDescription("Find Field's tutorial code: about 260 worked examples of box code, by what they do, e.g. query = `draw FLines`, `GLSL compute shader on the renderer`, `slider controls`. Returns each match's name, what it shows, the docs pages it illustrates, and its box code files: read one with AttachmentRequest(file=...) to see working box code before writing your own. If nothing matches it fails with success=false and suggests plainer words; it is unavailable when semantic search is not running. Prefer this to guessing an API from Field's source.")
@AgentToolMeta("search", effects = [ToolEffect.FILESYSTEM_READ])
data class TutorialCodeSearchRequest(
    /** What to make or learn, in plain words, matched against the `tutorials` corpus rather than by keyword. */
    @param:JsonPropertyDescription("What you want to make or learn, in plain words.")
    val query: String,
    /** How many tutorials to return; anything outside 1..12 is clamped into it. */
    @param:JsonPropertyDescription("Tutorials to return, 1..12.")
    val limit: Int = 6
)

/** One tutorial that matched, with the files a reader would open next. */
data class TutorialCodeMatch(
    /** The tutorial document's name, or `canonical_graphics` and the lesson box's name for one lesson. */
    val name: String,
    /** What the document's transcript says it shows, to 400 characters, empty when it says nothing. */
    val description: String,
    /** Names of the boxes inside it that matched, up to six. */
    val boxes: List<String>,
    /** The docs pages it illustrates, each as `page: why` from the transcript, up to four. */
    val docs: List<String>,
    /** Paths of its box code and shader files, up to eight, for [AttachmentRequest] to read. */
    val codeFiles: List<String>
)

/** The tool's answer: `success` is false both for a failed search and for a search that matched nothing. */
data class TutorialCodeSearchResult(val success: Boolean, val message: String, val matches: List<TutorialCodeMatch>)

internal object TutorialCode {
    private val codeExtensions = setOf("kt", "glsl", "slang", "metal", "js", "py")

    data class About(val description: String, val docs: List<String>)

    @Volatile private var cached: Pair<Long, Map<String, About>>? = null

    private fun transcriptsDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, "dev_docs/agents/transcripts").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    /** What each tutorial's transcript says it shows, and the docs pages it illustrates. */
    fun about(): Map<String, About> {
        val dir = transcriptsDir() ?: return emptyMap()
        cached?.takeIf { it.first == dir.lastModified() }?.let { return it.second }
        val out = dir.listFiles { f -> f.extension == "yaml" }.orEmpty().associate { file ->
            val parsed = runCatching { Yaml().load<Any?>(file.readText()) as? Map<*, *> }.getOrNull().orEmpty()
            file.nameWithoutExtension to About(
                parsed["description"]?.toString()?.trim().orEmpty(),
                (parsed["docs"] as? List<*>).orEmpty().mapNotNull { d ->
                    (d as? Map<*, *>)?.let { m -> listOfNotNull(m["page"]?.toString(), m["why"]?.toString()).joinToString(": ") }
                })
        } + ("canonical_graphics" to About(
            "The Field 4 (Vulkan) graphics curriculum: lessons that run against a live renderer, from a first triangle to compute " +
                "shaders, particles, texturing, render-to-texture and GPU sorting. Each lesson runs as a child of the `window` box.",
            listOf("assets/canonical_graphics/LESSONS.md: the lesson index, with each lesson's parent chain and files",
                "afield_four_graphics.md: the walkthrough")))
        cached = dir.lastModified() to out
        return out
    }

    /** Hits grouped into tutorials, best first: by document, or by box in the graphics curriculum. */
    fun group(hits: List<SemanticRetrievalHit>, limit: Int, about: Map<String, About> = about()): List<TutorialCodeMatch> {
        val groups = LinkedHashMap<String, MutableList<SemanticRetrievalHit>>()
        hits.filter { it.documentName.isNotBlank() }.forEach { hit ->
            val key = if (hit.documentName == "canonical_graphics") "canonical_graphics: ${hit.boxName.ifBlank { hit.boxId }}" else hit.documentName
            groups.getOrPut(key) { mutableListOf() } += hit
        }
        return groups.entries.take(limit.coerceIn(1, 12)).map { (name, rows) ->
            val document = rows.first().documentName
            val info = about[document]
            // Every text file of each matched box: the Kotlin and the shaders it draws with.
            // Every text file of each matched box (its Kotlin and the shaders it draws with); a tutorial's summary
            // stands for the whole document, so its hit brings the document's code files.
            val files = rows.distinctBy { it.boxId to it.path }.flatMap { hit ->
                val wholeDocument = hit.path.endsWith("/")
                val dirPath = if (wholeDocument) hit.path.trimEnd('/') else hit.path.substringBeforeLast('/', "")
                val siblings = File(System.getProperty("user.dir"), dirPath)
                    .listFiles { f -> f.name.contains("_.") && f.extension in codeExtensions && (wholeDocument || f.name.startsWith("${hit.boxId}_")) }.orEmpty()
                siblings.map { "$dirPath/${it.name}" }.sorted().ifEmpty { if (wholeDocument) emptyList() else listOf(hit.path) }
            }.distinct()
            TutorialCodeMatch(name, info?.description.orEmpty().take(400),
                rows.mapNotNull { it.boxName.takeIf(String::isNotBlank) }.distinct().take(6), info?.docs.orEmpty().take(4), files.take(8))
        }
    }
}

internal fun registerTutorialCodeTools(tools: ToolRegistry) {
    tools.register(TutorialCodeSearchRequest::class.java, { it.semanticRetrievalSearch != null }) { ctx, req ->
        val search = ctx.semanticRetrievalSearch!!(SemanticRetrievalSearchRequest(req.query, limit = 40, corpora = listOf("tutorials")))
        if (!search.success) return@register TutorialCodeSearchResult(false, search.message, emptyList())
        val matches = TutorialCode.group(search.results, req.limit)
        TutorialCodeSearchResult(matches.isNotEmpty(),
            if (matches.isEmpty()) "No tutorial code matched '${req.query}'. Try plainer words (what you want to see), or SemanticRetrievalSearchRequest with corpora=[documentation]."
            else "${matches.size} tutorial(s). Read a codeFiles entry with AttachmentRequest to see working box code.",
            matches)
    }
}
