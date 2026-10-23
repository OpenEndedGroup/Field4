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
 * Registering a project that already has its own launcher and workspace, without changing either.
 *
 * Some of what a kit package would describe already exists as a checkout with a `field.sh`, its own
 * documents and its own runtime. A manifest with a [KitDedicatedHost] block says so: it names the
 * launcher to run, the loopback HTTP endpoint that project serves on, and the workspace that
 * endpoint has to report. Registration validates and hashes those and nothing more, and the current
 * host leaves such a kit's runtime out of its own classpath profile, so the project's files are not
 * rewritten and its code is not loaded here.
 *
 * [KitDedicatedHosts.open] is the only thing that starts it, from an explicit action in Welcome. It
 * asks the endpoint's `/api/instance-info` whether a Field is already serving the expected
 * workspace and refuses outright if a different workspace answers there. If none is, it checks that
 * the launcher where the project lives still hashes to the copy installed under the registry, runs
 * it from there with the environment variables that would otherwise point it at this Field's ports,
 * workspace, extensions and Electron profile removed, and waits up to three minutes for the
 * endpoint to come up, reporting the launcher's log if it exits first. What comes back is a URL
 * into that host: its Welcome page, or a document the host itself created from the named starting
 * point.
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
