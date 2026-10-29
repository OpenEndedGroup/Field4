package afield.document.workspace

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The compatibility route for a project that already has its own launcher, workspace and document
 * conventions and wants to appear in Welcome's kit library without adopting Field's. Such a kit
 * declares a `host` block in its `field-kit.json` naming a launcher script, a loopback HTTP endpoint
 * and the workspace that launcher owns, and Field then treats it as something to start rather than
 * something to load: none of its runtime declarations join the calling host's classpath, and its
 * documents keep whatever versioning scheme the project already uses.
 *
 * [KitDedicatedHosts.open] is the whole action, and it runs only from an explicit **Open kit host**
 * or **Create in kit host** in Welcome, never from adding a kit. It probes `/api/instance-info` at
 * the declared endpoint first and reuses a host that is already up, refusing outright when a
 * different workspace answers there; otherwise it runs the launcher from the kit's source directory
 * with Field's own port and path variables stripped from the environment, appends its output to
 * `logs/<id>.log` under the kits directory, and polls for up to three minutes. Creating a document
 * is then a POST to that host's own starting-point API, so the running project copies the seed by
 * its own rules and hands back the URL to open.
 *
 * Two checks hold the arrangement together. [KitPackages.read] calls [KitDedicatedHosts.validate] on
 * every manifest, so a non-loopback endpoint, a query string, user info in the URL, or a launcher
 * path escaping the package is refused when the kit is added rather than when it is run. And
 * [KitDedicatedHosts.open] compares the launcher's hash in the source directory against the one in
 * the installed snapshot, so the script that executes is the script that was there when the kit was
 * added.
 */

/** Compatibility for an existing project launcher. Its original workspace and runtime remain authoritative. */
@Serializable
data class KitDedicatedHost(val launcher: String, val endpoint: String, val workspace: String,
    val environment: Map<String, String> = emptyMap())

/** Starts an existing local project without rewriting its launcher, manifests or existing documents. */
object KitDedicatedHosts {
    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun validate(root: File, host: KitDedicatedHost) {
        require(KitPackages.child(root, host.launcher).isFile) { "Missing dedicated-host launcher" }
        val uri = URI(host.endpoint)
        require(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1") && uri.port > 0 && uri.rawQuery == null && uri.userInfo == null) {
            "Dedicated kit hosts need an explicit loopback HTTP endpoint"
        }
        KitPackages.child(root, host.workspace)
        require(host.environment.keys.all { Regex("[A-Z][A-Z0-9_]*").matches(it) }) { "Invalid host environment key" }
    }

    /** The launcher is invoked only by an explicit Run/Create action, never by installation. */
    @Synchronized
    fun open(kit: InstalledKit, startingPoint: String? = null): String {
        val manifest = KitPackages.read(File(kit.directory))
        val host = manifest.host ?: error("This kit runs in the current Field host")
        val source = File(kit.source)
        validate(source, host)
        require(KitPackages.hash(KitPackages.child(source, host.launcher)) == KitPackages.hash(KitPackages.child(File(kit.directory), host.launcher))) {
            "The dedicated launcher changed. Check the kit source for updates first."
        }
        val base = host.endpoint.trimEnd('/')
        val workspace = KitPackages.child(source, host.workspace)
        fun ready(): Boolean = runCatching {
            val response = client.send(HttpRequest.newBuilder(URI("$base/api/instance-info")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching false
            val actual = mapper.readTree(response.body()).path("workspace").asText()
            require(File(actual).canonicalFile == workspace.canonicalFile) { "A different Field workspace owns ${host.endpoint}" }
            true
        }.getOrElse { if (it is IllegalArgumentException) throw it else false }
        if (!ready()) {
            val launcher = KitPackages.child(source, host.launcher)
            val command = when (launcher.extension.lowercase()) { "sh" -> listOf("bash", launcher.path); "ps1" -> listOf("powershell", "-File", launcher.path); else -> listOf(launcher.path) }
            val builder = ProcessBuilder(command).directory(source)
            listOf("PORT", "AFIELD_HTTP_PORT", "AFIELD_EXTENSION_DIRS", "FIELD_HOME", "APP_URL", "FIELD_ELECTRON_USER_DATA",
                "AFIELD_SKIP_BUILD", "FIELD_ELECTRON_CDP_PORT", "AFIELD_KITS_DIR", "AFIELD_WORKSPACE_PREFERENCE_FILE").forEach { builder.environment().remove(it) }
            host.environment.forEach { (key, value) -> builder.environment()[key] = value.replace("\${source}", source.canonicalPath) }
            val log = File(KitPackages.directory, "logs/${kit.id}.log").also { it.parentFile.mkdirs() }
            val process = builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            val deadline = System.currentTimeMillis() + 180000
            while (!ready() && System.currentTimeMillis() < deadline) {
                require(process.isAlive || ready()) { "Kit launcher exited; see ${log.path}" }
                Thread.sleep(300)
            }
            require(ready()) { "Kit host is not ready; see ${log.path}" }
        }
        if (startingPoint == null) return "$base/welcome"
        val point = manifest.startingPoints[startingPoint] ?: error("Unknown starting point")
        val name = File(point.path).name
        val response = client.send(HttpRequest.newBuilder(URI("$base/api/starting-points/open"))
            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("name" to name)))).build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == 200) { "Dedicated host could not create '$name': ${response.body().take(300)}" }
        val document = mapper.readTree(response.body()).path("filename").asText()
        require(document.isNotBlank() && !document.contains('/') && !document.contains('\\')) { "Dedicated host returned an invalid document" }
        return "$base/index.html?file=" + java.net.URLEncoder.encode(document, Charsets.UTF_8)
    }
}
