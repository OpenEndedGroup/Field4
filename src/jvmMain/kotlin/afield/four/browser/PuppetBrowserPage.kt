package afield.four.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture

// A page read as data rather than watched as a picture.
//
// These are the queries you would otherwise write by hand every time: a selector's text, an
// attribute, how many of something there is, a table as rows, and a wait for a condition to become
// true. They are thin — each is one `PuppetBrowserControl.evaluate` — and they are here rather than
// in a box because the escaping and the null handling are the parts that are easy to get subtly
// wrong, and because a shared spelling is what makes two boxes agree about what `text` means.
//
// They run in an isolated world by default. An isolated world sees the same DOM and none of the
// page's globals, so a page that has redefined `Array.prototype.map`, shadowed
// `document.querySelectorAll` or installed its own `Element.prototype.getAttribute` cannot change
// what Field reads out of it. Less paranoia about hostile pages than about ordinary ones:
// dashboards ship polyfills.
//
// Every one of them works against a `HeadlessPuppetBrowser` with no GPU, and equally against a
// `FieldUiBrowserTexture` you are already rendering — the receiver is the control plane, not the
// texture.

/** The single element matching [selector], as trimmed text, or null if there is no such element. */
fun PuppetBrowserControl.text(
    selector: String,
    world: BrowserJavascriptWorld = BrowserJavascriptWorld.ISOLATED,
    timeoutMillis: Long = 10_000,
): CompletableFuture<String?> = evaluate(
    "(() => { const e = document.querySelector(${selector.asJsString()}); " +
        "return e === null ? null : e.textContent.trim() })()",
    world = world,
    timeoutMillis = timeoutMillis,
).thenApply { it.value.stringOrNull() }

/** Every element matching [selector], as trimmed text, in document order. */
fun PuppetBrowserControl.texts(
    selector: String,
    world: BrowserJavascriptWorld = BrowserJavascriptWorld.ISOLATED,
    timeoutMillis: Long = 10_000,
): CompletableFuture<List<String>> = evaluate(
    "Array.from(document.querySelectorAll(${selector.asJsString()}), (e) => e.textContent.trim())",
    world = world,
    timeoutMillis = timeoutMillis,
).thenApply { result -> result.value.asArray().map { it.stringOrNull().orEmpty() } }

/**
 * One attribute of the single element matching [selector]; null when either the element or the
 * attribute is absent. Deliberately the *attribute* and not the property: `href` here is what the
 * markup says, not the absolute URL Chromium resolved it to.
 */
fun PuppetBrowserControl.attribute(
    selector: String,
    name: String,
    world: BrowserJavascriptWorld = BrowserJavascriptWorld.ISOLATED,
    timeoutMillis: Long = 10_000,
): CompletableFuture<String?> = evaluate(
    "(() => { const e = document.querySelector(${selector.asJsString()}); " +
        "return e === null ? null : e.getAttribute(${name.asJsString()}) })()",
    world = world,
    timeoutMillis = timeoutMillis,
).thenApply { it.value.stringOrNull() }

/** How many elements match [selector]. */
fun PuppetBrowserControl.count(
    selector: String,
    world: BrowserJavascriptWorld = BrowserJavascriptWorld.ISOLATED,
    timeoutMillis: Long = 10_000,
): CompletableFuture<Int> = evaluate(
    "document.querySelectorAll(${selector.asJsString()}).length",
    world = world,
    timeoutMillis = timeoutMillis,
).thenApply { it.value.jsonPrimitive.content.toInt() }

/** A table's header cells and body rows, exactly as they were laid out. */
data class PuppetPageTable(val header: List<String>, val rows: List<List<String>>) {
    /**
     * Column [index] of every row as a number, with a cell that is not one becoming null rather
     * than zero — a missing reading and a reading of zero are different facts, and a plot that
     * cannot tell them apart is the reason this does not default.
     */
    fun numbers(index: Int): List<Double?> = rows.map { row ->
        row.getOrNull(index)?.replace(",", "")?.trim()?.toDoubleOrNull()
    }

    /** A column by header name rather than by index. */
    fun column(name: String): List<String> {
        val index = header.indexOf(name)
        require(index >= 0) { "no column '$name' in $header" }
        return rows.map { it.getOrNull(index).orEmpty() }
    }

    /** A column by header name, as numbers. */
    fun numbers(name: String): List<Double?> {
        val index = header.indexOf(name)
        require(index >= 0) { "no column '$name' in $header" }
        return numbers(index)
    }
}

/**
 * The table matching [selector], as a header and rows of trimmed cell text.
 *
 * The header is `thead`'s first row when there is one, otherwise the table's first row if every
 * cell in it is a `th`; each remaining `tr` becomes a row. No attempt is made at `colspan`, and
 * that is deliberate — a spanning cell has no single honest position in a rectangle, and quietly
 * guessing one is how a chart ends up plotting the wrong column.
 */
fun PuppetBrowserControl.table(
    selector: String,
    world: BrowserJavascriptWorld = BrowserJavascriptWorld.ISOLATED,
    timeoutMillis: Long = 10_000,
): CompletableFuture<PuppetPageTable> = evaluate(
    """
    (() => {
        const table = document.querySelector(${selector.asJsString()})
        if (table === null) return null
        const cells = (row) => Array.from(row.cells, (c) => c.textContent.trim())
        const rows = Array.from(table.rows)
        const headRows = rows.filter((r) => r.parentElement.tagName === "THEAD")
        let header = []
        let body = rows
        if (headRows.length > 0) {
            header = cells(headRows[0])
            body = rows.filter((r) => r.parentElement.tagName !== "THEAD")
        } else if (rows.length > 0 && rows[0].cells.length > 0 &&
                   Array.from(rows[0].cells).every((c) => c.tagName === "TH")) {
            header = cells(rows[0])
            body = rows.slice(1)
        }
        return { header: header, rows: body.map(cells) }
    })()
    """.trimIndent(),
    world = world,
    timeoutMillis = timeoutMillis,
).thenApply { result ->
    val fields = (result.value as? JsonObject)
        ?: if (result.value is JsonNull) error("no table matched '$selector'")
        else error("table('$selector') came back as ${result.type}")
    PuppetPageTable(
        header = fields["header"].asArray().map { it.stringOrNull().orEmpty() },
        rows = fields["rows"].asArray().map { row -> row.asArray().map { it.stringOrNull().orEmpty() } },
    )
}

/**
 * Waits, **in the page**, until [javascriptCondition] evaluates truthy, and fails if it has not
 * within [timeoutMillis].
 *
 * In the page rather than in Kotlin, so the whole wait costs one round trip instead of one per
 * poll. A condition that *throws* fails immediately rather than being retried: an exception is a
 * broken condition, and retrying a broken condition until the deadline turns a typo into a timeout,
 * which is the least informative failure a wait can produce.
 */
fun PuppetBrowserControl.waitFor(
    javascriptCondition: String,
    timeoutMillis: Long = 10_000,
    pollMillis: Long = 25,
    world: BrowserJavascriptWorld = BrowserJavascriptWorld.ISOLATED,
): CompletableFuture<Unit> = evaluate(
    """
    new Promise((resolve, reject) => {
        const deadline = Date.now() + $timeoutMillis
        const poll = () => {
            let satisfied
            try {
                satisfied = ($javascriptCondition)
            } catch (error) {
                return reject(error)
            }
            if (satisfied) return resolve(true)
            if (Date.now() >= deadline) {
                return reject(new Error("waitFor timed out after ${timeoutMillis}ms: " +
                    ${javascriptCondition.asJsString()}))
            }
            setTimeout(poll, $pollMillis)
        }
        poll()
    })
    """.trimIndent(),
    world = world,
    // The page owns the deadline; this one only exists so a wedged renderer cannot hang forever.
    timeoutMillis = timeoutMillis + 5_000,
).thenApply { }

/**
 * A JSON string literal, which is also a JavaScript string literal for every string Field can hold.
 * It is what stops a selector containing a quote, a backslash or a newline from ending the literal
 * it is interpolated into.
 */
private fun String.asJsString(): String = JsonPrimitive(this).toString()

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
