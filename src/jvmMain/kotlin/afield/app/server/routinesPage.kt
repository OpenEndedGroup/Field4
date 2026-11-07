package afield.app.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The server side of `/routines.html`: the scheduled routines in `routines/`, how each one last went,
 * and the text that adopts one into a scheduler.
 *
 * Both routes are reads that run `tools/routines.py` (stdlib-only, so any Python will do, found the
 * way the docs pass finds one) and pass its JSON through. Nothing here schedules, installs or starts
 * a routine: adopting one is the user pasting a prompt into their agent or running the printed
 * command, so the page can never change what runs at night. They are loopback + same-site all the
 * same, because the answers carry the checkout's absolute path and the machine's report text.
 */

/** The routine names `routines.py` accepts; anything else is refused before a process starts. */
private val routineName = Regex("[a-z][a-z0-9-]{1,40}")

/** The schedulers `routines.py install` renders for. */
internal val routineHarnesses = setOf("prompt", "launchd", "cron")

/**
 * The `routines.py` arguments for `GET /api/routines/adopt`, or `null` for a name or harness the
 * page would never send. An argv, never a shell, so this refuses nonsense rather than quoting it.
 */
internal fun routineAdoptArguments(name: String, harness: String): List<String>? =
    if (routineName.matches(name) && harness in routineHarnesses) listOf("install", name, "--harness", harness, "--json") else null

private fun runRoutinesTool(checkout: Path, arguments: List<String>, timeoutSeconds: Long = 60): Pair<Int, String> {
    val process = ProcessBuilder(docsNotesPython() + listOf("tools/routines.py") + arguments)
        .directory(checkout.toFile()).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return 124 to "tools/routines.py timed out after ${timeoutSeconds}s"
    }
    reader.join(2000)
    return process.exitValue() to output.toString()
}

internal fun Route.installRoutinesRoutes(policy: DocsAccessPolicy) {
    get("/api/routines") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
        val checkout = docsImprovementCheckout()?.takeIf { Files.isRegularFile(it.resolve("tools/routines.py")) }
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "This Field is not running from a checkout with tools/routines.py.")
        val (code, output) = withContext(Dispatchers.IO) { runRoutinesTool(checkout, listOf("status", "--json")) }
        if (code != 0) return@get call.respond(HttpStatusCode.InternalServerError, output.ifBlank { "tools/routines.py status exited $code" })
        call.respondText(output, ContentType.Application.Json)
    }
    get("/api/routines/adopt") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(policy))) return@get
        val checkout = docsImprovementCheckout()?.takeIf { Files.isRegularFile(it.resolve("tools/routines.py")) }
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "This Field is not running from a checkout with tools/routines.py.")
        val arguments = routineAdoptArguments(call.request.queryParameters["name"].orEmpty(), call.request.queryParameters["harness"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.BadRequest, "not a routine name and harness")
        val (code, output) = withContext(Dispatchers.IO) { runRoutinesTool(checkout, arguments) }
        if (code != 0) return@get call.respond(HttpStatusCode.NotFound, output.ifBlank { "tools/routines.py install exited $code" })
        call.respondText(output, ContentType.Application.Json)
    }
}
