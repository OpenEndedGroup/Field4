package afield.graphics.text.sentences

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON emitted by the `sentence` Python tool (`~/temp/sentence`), which parses a
 * sentence into the two halves of a sentence diagram: a constituency tree that hangs
 * above the words, and dependency arcs that hang below them, over one shared token
 * list so the two can be drawn around the same row of words.
 *
 *     .venv/bin/python parse.py "..." > out.json          # one-shot
 *     .venv/bin/python parse.py --serve 9500              # keeps the model warm
 *
 * Parse with [Sentences.parse] / [Sentences.parseAll].
 */
@Serializable
data class Token(
    val id: Int,
    val word: String,
    val lemma: String? = null,
    /** Penn treebank tag -- the label on this token's preterminal tree node. */
    val pos: String? = null,
    val upos: String? = null,
    val feats: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    /**
     * True when this token survives as a node in the collapsed dependency graph.
     * In the original 2011 diagrams this is exactly the set of words drawn with an
     * underline: collapsing folds prepositions and conjunctions into relation names
     * ("of" becomes part of `prep_of`), leaving those words with no arcs at all.
     */
    @SerialName("in_graph") val inGraph: Boolean = true,
    /** True when this token was the one folded into a relation name. */
    val absorbed: Boolean = false,
)

/**
 * A constituency tree node.  A preterminal (a POS tag and its word) is folded into a
 * single node, so [leaf] nodes carry both [word] and [token].
 */
@Serializable
data class Node(
    val id: Int,
    val label: String,
    val leaf: Boolean = false,
    val depth: Int = 0,
    val word: String? = null,
    /** 1-based token index this leaf sits above; null on internal nodes. */
    val token: Int? = null,
    val children: List<Node> = emptyList(),
) {
    fun leaves(): List<Node> =
        if (leaf) listOf(this) else children.flatMap { it.leaves() }

    /** Every node, parents before children. */
    fun walk(): List<Node> =
        listOf(this) + children.flatMap { it.walk() }

    /** Parent-child pairs, for drawing tree edges. */
    fun edges(): List<Pair<Node, Node>> =
        children.flatMap { listOf(this to it) + it.edges() }

    /** Token id to the depth of its preterminal. See `Placements.byTreeDepth`. */
    fun leafDepths(depth: Int = 0): Map<Int, Int> =
        if (leaf) (token?.let { mapOf(it to depth) } ?: emptyMap())
        else children.fold(emptyMap()) { acc, c -> acc + c.leafDepths(depth + 1) }
}

@Serializable
data class Edge(
    /** Governor token id; 0 for the root arc. */
    val gov: Int,
    val dep: Int,
    /** Relation code, e.g. `prep_of`. */
    val rel: String,
    /** Relation family, e.g. `prep`. */
    val base: String,
    /** Long-form name the diagrams print, e.g. "prep collapsed". */
    val label: String,
    /** The folded-in preposition or conjunction, when this is a collapsed relation. */
    val marker: String? = null,
    val direction: String = "right",
    val distance: Int = 0,
) {
    val isRoot: Boolean get() = gov == 0
    val collapsed: Boolean get() = marker != null
}

@Serializable
data class Dependencies(
    /** What the parser actually produced. */
    val ud: List<Edge> = emptyList(),
    /** Classic Stanford Dependencies. */
    @SerialName("sd_basic") val sdBasic: List<Edge> = emptyList(),
    /** Collapsed Stanford Dependencies -- what the 2011 diagrams drew. */
    @SerialName("sd_collapsed") val sdCollapsed: List<Edge> = emptyList(),
)

/** Which dependency analysis to draw. */
enum class Scheme { UD, SD_BASIC, SD_COLLAPSED }

@Serializable
data class Sentence(
    val schema: Int = 2,
    val backend: String = "",
    val text: String = "",
    val tokens: List<Token> = emptyList(),
    val constituency: Node,
    @SerialName("constituency_bracketed") val bracketed: String = "",
    @SerialName("constituency_depth") val treeDepth: Int = 0,
    val dependencies: Dependencies = Dependencies(),
) {
    private val byId: Map<Int, Token> by lazy { tokens.associateBy { it.id } }

    fun token(id: Int): Token =
        byId[id] ?: throw IllegalArgumentException("no token $id in \"$text\"")

    fun tokenOrNull(id: Int): Token? = byId[id]

    fun edges(scheme: Scheme = Scheme.SD_COLLAPSED): List<Edge> = when (scheme) {
        Scheme.UD -> dependencies.ud
        Scheme.SD_BASIC -> dependencies.sdBasic
        Scheme.SD_COLLAPSED -> dependencies.sdCollapsed
    }

    /** Words drawn with an underline: those still present in the collapsed graph. */
    fun underlined(): List<Token> = tokens.filter { it.inGraph }

    /**
     * Sanity check on a freshly parsed sentence.  Returns the problems found, empty
     * when all is well -- worth calling once when wiring this up, since a mismatch
     * between the tree's leaves and the token list would quietly break every leaf
     * reference the layout depends on.
     */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        val leaves = constituency.leaves()
        if (leaves.size != tokens.size) {
            out += "tree has ${leaves.size} leaves but there are ${tokens.size} tokens"
        }
        leaves.forEachIndexed { i, leaf ->
            val expected = i + 1
            if (leaf.token != expected) {
                out += "leaf $i references token ${leaf.token}, expected $expected"
            }
            val tok = tokenOrNull(leaf.token ?: -1)
            if (tok != null && tok.word != leaf.word) {
                out += "leaf ${leaf.token} is \"${leaf.word}\" but token is \"${tok.word}\""
            }
        }
        for (t in tokens) {
            if (!t.inGraph && !t.absorbed) {
                out += "token ${t.id} \"${t.word}\" has no arcs and was not absorbed"
            }
        }
        return out
    }
}

object Sentences {
    /** Tolerant of fields we do not model, so the Python side can grow. */
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse the whole array the tool emits -- one entry per sentence. */
    fun parseAll(text: String): List<Sentence> = json.decodeFromString(text)

    /** Parse a single sentence; the tool always emits an array, even for one. */
    fun parse(text: String): Sentence =
        parseAll(text).firstOrNull()
            ?: throw IllegalArgumentException("no sentences in that JSON")

    fun parseFile(path: String): List<Sentence> =
        parseAll(java.io.File(path).readText())

    /**
     * Ask the warm parser server directly, skipping the file entirely.
     * Start it with `.venv/bin/python parse.py --serve 9500`.
     */
    fun fromServer(text: String, url: String = "http://127.0.0.1:9500"): List<Sentence> {
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 60_000
        try {
            conn.outputStream.use { it.write(text.toByteArray()) }
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            return parseAll(body)
        } catch (e: java.io.IOException) {
            throw java.io.IOException(
                "no parser at $url -- start it with:\n" +
                    "    .venv/bin/python parse.py --serve 9500", e
            )
        } finally {
            conn.disconnect()
        }
    }
}
