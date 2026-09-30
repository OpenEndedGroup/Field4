package afield.server

import afield.installFieldApiCors
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store is shared with `tools/docs_notes.py`, which writes `source` and `thread` into notes
 * the panel later saves again. Before the upsert merged, a panel save replaced the note wholesale
 * and every agent's association, question and memory on it was gone. These tests write the file
 * the way the tool does — as raw JSON, not through the Kotlin data classes — and then drive the
 * real routes.
 */
class DocsAnnotationThreadTest {

    @Test
    fun aPanelSaveKeepsWhatTheToolWroteAndARepliedQuestionRejoinsTheQueue() = docsApp { store ->
        val id = created(post("/api/docs-annotations") { fromThePanel(); jsonBody(UPSERT) }.bodyAsText())
        writeAsTheTool(store, id) { note ->
            JsonObject(note + mapOf(
                "source" to buildJsonObject { put("path", "public/docs/index.md"); put("startLine", 9); put("status", "anchored") },
                "thread" to JsonArray(listOf(buildJsonObject {
                    put("id", "q1"); put("kind", "question"); put("author", "agent:claude"); put("text", "which?"); put("atEpochMs", 1)
                })),
            ))
        }
        assertFalse(read(store, id).isQueued(), "an unanswered agent question takes the note out of the queue")

        val reply = """{"annotation":{"id":"$id","pageUrl":"$PAGE","note":"a note @docs"},"reply":"the second one"}"""
        val response = post("/api/docs-annotations") { fromThePanel(); jsonBody(reply) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val saved = read(store, id)
        assertEquals(listOf("question", "reply"), saved.thread.map { it.kind })
        assertEquals("human", saved.thread.last().author)
        assertEquals("the second one", saved.thread.last().text)
        assertNotNull(saved.source, "the source association must survive a panel save that did not move the anchor")
        assertEquals("q", saved.anchor.exact.take(1), "an upsert with no anchor keeps the note's anchor")
        assertTrue(saved.isQueued(), "a human reply after the question puts the note back in the queue")
    }

    @Test
    fun movingTheAnchorDropsTheAssociationAndResolvingRecordsWhoDidIt() = docsApp { store ->
        val id = created(post("/api/docs-annotations") { fromThePanel(); jsonBody(UPSERT) }.bodyAsText())
        writeAsTheTool(store, id) { note -> JsonObject(note + ("source" to buildJsonObject { put("status", "anchored") })) }

        val moved = """{"annotation":{"id":"$id","pageUrl":"$PAGE","note":"a note","anchor":{"exact":"elsewhere"}}}"""
        post("/api/docs-annotations") { fromThePanel(); jsonBody(moved) }
        assertNull(read(store, id).source, "a note re-anchored by a human describes a different sentence")

        put("/api/docs-annotations/$id/resolve") { fromThePanel(); jsonBody("""{"resolved":true,"resolution":"done"}""") }
        val resolved = read(store, id)
        assertNotNull(resolved.resolvedAtEpochMs)
        assertEquals("resolution", resolved.thread.last().kind)
        assertEquals("human", resolved.thread.last().author)
        assertFalse(resolved.isQueued())
    }

    @Test
    fun memoriesAreNeverQueued() {
        assertFalse(DocsAnnotation(pageUrl = PAGE, note = "m", kind = "memory").isQueued())
        assertTrue(DocsAnnotation(pageUrl = PAGE, note = "n").isQueued())
    }

    private fun created(body: String): String =
        json.parseToJsonElement(body).jsonObject["id"].let { (it as JsonPrimitive).content }

    private fun read(store: Path, id: String): DocsAnnotation =
        json.parseToJsonElement(Files.readString(store)).jsonObject["annotations"]!!.jsonArray
            .map { json.decodeFromJsonElement(DocsAnnotation.serializer(), it) }.single { it.id == id }

    /** Edits one note as raw JSON, the way `tools/docs_notes.py` does, keeping every other key. */
    private fun writeAsTheTool(store: Path, id: String, change: (JsonObject) -> JsonObject) {
        val root = json.parseToJsonElement(Files.readString(store)).jsonObject
        val notes = root["annotations"]!!.jsonArray.map { note ->
            val obj = note.jsonObject
            if ((obj["id"] as JsonPrimitive).content == id) change(obj) else obj
        }
        Files.writeString(store, JsonObject(root + ("annotations" to JsonArray(notes))).toString())
    }

    private fun docsApp(block: suspend HttpClient.(Path) -> Unit) {
        val dir = createTempDirectory("docs-annotations-thread-test-")
        val store = dir.resolve("docs-annotations.json")
        try {
            withDocsAnnotationStoreAt(store) {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        installFieldApiCors()
                        routing {
                            installDocsAnnotationRoutes(
                                DocsAccessPolicy(allowRemoteAnnotations = true),
                                DocsMirror(DocsMirrorSource.LOCAL, Path.of("/no-such-docs-build")),
                            )
                        }
                    }
                    client.block(store)
                }
            }
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun HttpRequestBuilder.fromThePanel() {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "http://localhost")
        header(SEC_FETCH_SITE_HEADER, "same-origin")
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val PAGE = "https://openendedgroup.com/field4/index.html"
        const val UPSERT = """{"annotation":{"pageUrl":"$PAGE","note":"a note @docs","anchor":{"exact":"quote"}}}"""
    }
}
