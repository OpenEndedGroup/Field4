@file:afield.CompletionCategory("Workspace")

package afield

/**
 * One answer from [ContextResolution.explain], with the fields a person reads first pulled out.
 *
 * [json] is the whole answer, unchanged — the same object the backdoor's `why`, MCP `field_why` and the
 * AI tool `WhyDoesThisResolve` return. `.h` shows [markdown] as a card; `.h2` explores [fields];
 * `toString()` is the summary sentence, so `print(ContextResolution.explain(thisBox, "speed"))` reads as prose.
 */
class WhyAnswer(val json: kotlinx.serialization.json.JsonObject) : ToHTML {
    private val plain = ContextWhy.plain(json) as Map<*, *>

    private fun name(o: Any?): String? = (o as? Map<*, *>)?.let { m ->
        val box = (m["supplier"] ?: m) as? Map<*, *>
        (box?.get("name") as? String)?.ifBlank { null } ?: box?.get("uid") as? String
    }

    private fun names(key: String): List<String> = (plain[key] as? List<*>).orEmpty().mapNotNull(::name)

    /** What was asked: a name or a type. */
    val query: String get() = plain["query"] as String
    /** Whether anything up the graph supplies it. */
    val found: Boolean get() = plain["found"] == true
    /** The box whose value is used, or null when nothing supplies it. */
    val supplier: String? get() = name(plain["supplier"])
    /** Where in the supplier it was found: `PROPERTY`, `VARIABLE` or `SELF`. */
    val matchKind: String? get() = plain["matchKind"] as? String
    /** The value, as text. */
    val value: String? get() = plain["value"] as? String
    /** The boxes from this one to the supplier. */
    val path: List<String> get() = names("path")
    /** Boxes as near as the supplier holding a different value — an ambiguity settled by parents order. */
    val competing: List<String> get() = names("competing")
    /** Every other box that also answers, farther away or later in order. */
    val shadowed: List<String> get() = names("shadowed")
    /** The one-sentence explanation. */
    val summary: String get() = plain["summary"] as String

    /** The answer as a short Markdown table and the summary sentence. */
    val markdown: String
        get() {
            fun cell(xs: List<String>) = if (xs.isEmpty()) "—" else xs.joinToString(", ") { it.md }
            val rows = if (!found) "| supplier | nothing supplies `${query.md}` |\n" else """
                | supplier | ${supplier.md} (${matchKind?.lowercase().md}) = ${value.md} |
                | path | ${path.joinToString(" → ") { it.md }} |
                | competing | ${cell(competing)} |
                | shadowed | ${cell(shadowed)} |
            """.trimIndent() + "\n"
            return "| why `${query.md}` | |\n| --- | --- |\n" + rows + "\n" + summary.md
        }

    /** The fields [markdown] shows, for `.h2` to page through; the whole answer is under `json`. */
    val fields: Map<String, Any?>
        get() = linkedMapOf("query" to query, "supplier" to supplier, "matchKind" to matchKind, "value" to value,
            "path" to path, "competing" to competing, "shadowed" to shadowed, "summary" to summary, "json" to plain)

    override fun toHTML(): String = FieldMarkdown.card(markdown, width = 560)

    override fun toString(): String = summary
}

/** The `.h2` rendering of a [WhyAnswer]: its fields to explore, its summary sentence as the headline. */
@RunsAt(newRoot)
fun registerWhyH2() {
    registerH2Value<WhyAnswer> { H2ValueSnapshot(value = it.fields, summary = it.summary, title = "why ${it.query}") }
}
