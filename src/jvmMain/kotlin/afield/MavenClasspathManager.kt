package afield

import afield.logger.lprintln
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver

@Serializable
private data class StoredMavenDependency(
    val coordinate: String,
    val resolved: Boolean = false,
    val jarCount: Int = 0,
    val latestVersion: String = "",
    val status: String = "",
    val resolvedAtEpochMs: Long = 0
)

@Serializable
private data class StoredMavenClasspathState(
    val repositories: List<String> = listOf(MavenClasspathManager.MAVEN_CENTRAL_REPOSITORY),
    val dependencies: List<StoredMavenDependency> = emptyList(),
    val resolvedJars: List<String> = emptyList()
)

object MavenClasspathManager {
    const val MAVEN_CENTRAL_REPOSITORY = "https://repo.maven.apache.org/maven2"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val stateFile = File(File(System.getProperty("user.home"), ".afield"), "maven-classpath.json")
    private var resolver = MavenDependenciesResolver()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val lock = Any()
    private var state: StoredMavenClasspathState = loadState()
    private val addedRepositories = linkedSetOf<String>()
    private val attachedUrls = linkedSetOf<String>()

    init {
        synchronized(lock) {
            state.repositories.forEach { addRepositoryToResolverLocked(it) }
        }
    }

    fun status(message: String = ""): MavenClasspathStatus = synchronized(lock) {
        statusLocked(message)
    }

    fun addRepository(url: String): MavenClasspathStatus = synchronized(lock) {
        val normalized = normalizeRepository(url)
        addRepositoryToResolverLocked(normalized).throwOnFailure("repository")
        if (normalized !in state.repositories) {
            state = state.copy(repositories = (state.repositories + normalized).distinct())
            persistLocked()
        }
        statusLocked("Repository added.")
    }

    fun removeRepository(url: String): MavenClasspathStatus = synchronized(lock) {
        val normalized = normalizeRepository(url)
        if (normalized == MAVEN_CENTRAL_REPOSITORY) {
            throw IllegalArgumentException("Maven Central is kept as the default repository.")
        }
        state = state.copy(repositories = state.repositories.filterNot { it == normalized })
        rebuildResolverLocked()
        persistLocked()
        statusLocked("Repository removed.")
    }

    fun addDependency(coordinate: String): MavenClasspathStatus {
        val normalized = normalizeCoordinate(coordinate)
        return resolve(listOf(normalized), addToList = true)
    }

    fun removeDependency(coordinate: String): MavenClasspathStatus = synchronized(lock) {
        val normalized = normalizeCoordinate(coordinate)
        state = state.copy(
            dependencies = state.dependencies.filterNot { it.coordinate == normalized },
            resolvedJars = state.resolvedJars.filterNot { jarPathLooksLikeCoordinate(it, normalized) }
        )
        persistLocked()
        statusLocked("Dependency removed. Restart Field to unload already attached jars from this JVM.")
    }

    fun resolve(coordinates: List<String> = emptyList(), addToList: Boolean = false): MavenClasspathStatus {
        val targets = if (coordinates.isEmpty()) {
            synchronized(lock) { state.dependencies.map { it.coordinate } }
        } else {
            coordinates.map(::normalizeCoordinate)
        }
        if (targets.isEmpty()) return status("No dependencies to resolve.")

        val resolvedEntries = mutableListOf<StoredMavenDependency>()
        val resolvedFiles = linkedSetOf<String>()
        val messages = mutableListOf<String>()

        synchronized(lock) {
            rebuildResolverLocked()
        }

        for (coordinate in targets) {
            val result = runBlocking {
                resolver.resolve(coordinate, ExternalDependenciesResolver.Options.Empty, null)
            }
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    val files = result.value.map { it.absoluteFile }.filter { it.exists() }
                    val paths = files.map { it.absolutePath }
                    resolvedFiles += paths
                    val latest = latestVersionForCoordinate(coordinate).getOrDefault("")
                    resolvedEntries += StoredMavenDependency(
                        coordinate = coordinate,
                        resolved = true,
                        jarCount = paths.size,
                        latestVersion = latest,
                        status = "resolved ${paths.size} jars",
                        resolvedAtEpochMs = System.currentTimeMillis()
                    )
                }

                is ResultWithDiagnostics.Failure -> {
                    val diagnostics = result.reports.mapNotNull { it.message }.joinToString("; ")
                    val status = diagnostics.ifBlank { "resolution failed" }
                    resolvedEntries += StoredMavenDependency(
                        coordinate = coordinate,
                        resolved = false,
                        status = status,
                        resolvedAtEpochMs = System.currentTimeMillis()
                    )
                    messages += "$coordinate: $status"
                }
            }
        }

        synchronized(lock) {
            val mergedDependencies = linkedMapOf<String, StoredMavenDependency>()
            state.dependencies.forEach { mergedDependencies[it.coordinate] = it }
            resolvedEntries.forEach { entry ->
                if (addToList || entry.coordinate in mergedDependencies) {
                    mergedDependencies[entry.coordinate] = entry
                }
            }
            if (addToList) {
                targets.forEach { coordinate ->
                    mergedDependencies.putIfAbsent(coordinate, StoredMavenDependency(coordinate = coordinate))
                }
            }
            state = state.copy(
                dependencies = mergedDependencies.values.toList(),
                resolvedJars = (state.resolvedJars + resolvedFiles).distinct()
            )
            persistLocked()
            attachResolvedToSharedLoadersLocked()
            val message = if (messages.isEmpty()) {
                "Resolved ${targets.size} ${if (targets.size == 1) "dependency" else "dependencies"}."
            } else {
                messages.joinToString(" | ")
            }
            return statusLocked(message)
        }
    }

    fun upgradeDependencies(): MavenUpgradeResponse {
        val current = synchronized(lock) { state.dependencies.map { it.coordinate } }
        val upgradedCoordinates = current.mapNotNull { coordinate ->
            latestCoordinate(coordinate).getOrNull()?.takeIf { it != coordinate }
        }
        if (upgradedCoordinates.isEmpty()) {
            return MavenUpgradeResponse(emptyList(), status("Already on latest known versions."))
        }
        resolve(upgradedCoordinates, addToList = true)
        synchronized(lock) {
            val upgradedByKey = upgradedCoordinates.associateBy { coordinateKey(it) }
            state = state.copy(
                dependencies = state.dependencies
                    .filterNot { coordinateKey(it.coordinate) in upgradedByKey.keys }
                    .plus(upgradedCoordinates.map { coordinate ->
                        state.dependencies.firstOrNull { it.coordinate == coordinate }
                            ?: StoredMavenDependency(coordinate = coordinate, status = "upgraded")
                    })
                    .distinctBy { it.coordinate },
                resolvedJars = state.resolvedJars
                    .filterNot { jar -> current.any { old -> coordinateKey(old) in upgradedByKey.keys && jarPathLooksLikeCoordinate(jar, old) } }
            )
            persistLocked()
            val status = statusLocked("Upgraded ${upgradedCoordinates.size} dependencies. Restart Field to unload older already attached jars from this JVM.")
            return MavenUpgradeResponse(upgradedCoordinates, status)
        }
    }

    fun search(query: String, rows: Int = 12): MavenSearchResponse {
        val clean = query.trim()
        if (clean.length < 2) return MavenSearchResponse(clean, emptyList(), "Enter at least two characters.")
        val centralQuery = centralSearchQuery(clean)
        val encodedQuery = URLEncoder.encode(centralQuery, StandardCharsets.UTF_8)
        val uri = URI.create("https://search.maven.org/solrsearch/select?q=$encodedQuery&rows=${rows.coerceIn(1, 40)}&wt=json")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Maven Central search returned HTTP ${response.statusCode()}.")
        }
        val docs = json.parseToJsonElement(response.body())
            .jsonObject["response"]?.jsonObject
            ?.get("docs")?.jsonArray
            .orEmpty()
        val results = docs.mapNotNull { element ->
            val obj = element.jsonObject
            val group = obj["g"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artifact = obj["a"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val latest = obj["latestVersion"]?.jsonPrimitive?.contentOrNull
                ?: obj["v"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            MavenSearchResult(
                groupId = group,
                artifactId = artifact,
                latestVersion = latest,
                packaging = obj["p"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                versionCount = obj["versionCount"]?.jsonPrimitive?.intOrNull ?: 0,
                timestampEpochMs = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                coordinate = "$group:$artifact:$latest"
            )
        }
        return MavenSearchResponse(clean, results)
    }

    internal fun attachResolvedTo(loader: ClassLoader?) = synchronized(lock) {
        attachResolvedToLocked(loader)
    }

    private fun statusLocked(message: String): MavenClasspathStatus {
        val resolvedJars = state.resolvedJars.filter { File(it).exists() }.distinct()
        return MavenClasspathStatus(
            repositories = state.repositories,
            dependencies = state.dependencies.map {
                MavenDependencyEntry(
                    coordinate = it.coordinate,
                    resolved = it.resolved,
                    jarCount = it.jarCount,
                    latestVersion = it.latestVersion,
                    status = it.status,
                    resolvedAtEpochMs = it.resolvedAtEpochMs
                )
            },
            resolvedJars = resolvedJars,
            attachedJarCount = attachedUrls.size,
            annotationSnippet = annotationSnippetLocked(),
            message = message
        )
    }

    private fun annotationSnippetLocked(): String {
        val lines = mutableListOf<String>()
        state.repositories
            .filterNot { it == MAVEN_CENTRAL_REPOSITORY }
            .forEach { lines += "@file:Repository(\"$it\")" }
        state.dependencies.forEach { lines += "@file:DependsOn(\"${it.coordinate}\")" }
        return lines.joinToString("\n")
    }

    private fun addRepositoryToResolverLocked(url: String): ResultWithDiagnostics<Boolean> {
        if (url in addedRepositories) return ResultWithDiagnostics.Success(true)
        val result = resolver.addRepository(
            RepositoryCoordinates(url),
            ExternalDependenciesResolver.Options.Empty,
            null
        )
        if (result is ResultWithDiagnostics.Success) {
            addedRepositories += url
        }
        return result
    }

    private fun rebuildResolverLocked() {
        resolver = MavenDependenciesResolver()
        addedRepositories.clear()
        state.repositories.forEach { addRepositoryToResolverLocked(it) }
    }

    private fun ResultWithDiagnostics<Boolean>.throwOnFailure(kind: String) {
        if (this is ResultWithDiagnostics.Failure) {
            val diagnostics = reports.mapNotNull { it.message }.joinToString("; ")
            throw IllegalArgumentException(diagnostics.ifBlank { "invalid $kind" })
        }
    }

    private fun attachResolvedToSharedLoadersLocked() {
        attachResolvedToLocked(KotlinInterface.sharedClassLoader)
        attachResolvedToLocked(KotlinInterface2.sharedClassLoader)
    }

    private fun attachResolvedToLocked(loader: ClassLoader?) {
        val target = loader as? URLClassLoader ?: return
        val addUrl = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
        addUrl.isAccessible = true
        val currentUrls = target.urLs.mapTo(linkedSetOf()) { it.toExternalForm() }
        state.resolvedJars
            .asSequence()
            .map(::File)
            .filter { it.exists() }
            .map { it.toURI().toURL() }
            .forEach { url ->
                val external = url.toExternalForm()
                if (external !in currentUrls) {
                    runCatching {
                        addUrl.invoke(target, url)
                        attachedUrls += external
                    }.onFailure {
                        lprintln("error,kotlin", "failed to add Maven dependency URL $url: ${it.message}")
                    }
                }
            }
    }

    private fun persistLocked() {
        stateFile.parentFile.mkdirs()
        stateFile.writeText(json.encodeToString(state), Charsets.UTF_8)
    }

    private fun loadState(): StoredMavenClasspathState =
        runCatching {
            if (!stateFile.exists()) return@runCatching StoredMavenClasspathState()
            val decoded = json.decodeFromString<StoredMavenClasspathState>(stateFile.readText(Charsets.UTF_8))
            decoded.copy(
                repositories = (listOf(MAVEN_CENTRAL_REPOSITORY) + decoded.repositories.map(::normalizeRepository)).distinct(),
                dependencies = decoded.dependencies.map { it.copy(coordinate = normalizeCoordinate(it.coordinate)) }.distinctBy { it.coordinate },
                resolvedJars = decoded.resolvedJars.distinct()
            )
        }.getOrElse {
            lprintln("warning,kotlin", "could not read Maven classpath state: ${it.message}")
            StoredMavenClasspathState()
        }

    private fun normalizeRepository(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("Repository must be a valid URL.")
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Repository URL must start with http:// or https://.")
        }
        if (uri.host.isNullOrBlank()) throw IllegalArgumentException("Repository URL needs a host.")
        return trimmed
    }

    private fun normalizeCoordinate(coordinate: String): String {
        val clean = coordinate.trim()
        val parts = clean.split(":")
        if (parts.size < 3 || parts.any { it.isBlank() }) {
            throw IllegalArgumentException("Use Maven coordinates like group:artifact:version.")
        }
        return clean
    }

    private fun coordinateKey(coordinate: String): String =
        coordinate.split(":").take(2).joinToString(":")

    private fun jarPathLooksLikeCoordinate(path: String, coordinate: String): Boolean {
        val parts = coordinate.split(":")
        if (parts.size < 3) return false
        val groupPath = parts[0].replace('.', File.separatorChar)
        val artifact = parts[1]
        val version = parts[2]
        val normalized = File(path).path
        return normalized.contains(File.separator + groupPath + File.separator + artifact + File.separator + version + File.separator)
    }

    private fun centralSearchQuery(query: String): String {
        val parts = query.split(":").map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size >= 2) {
            "g:\"${parts[0]}\" AND a:\"${parts[1]}\""
        } else {
            query
        }
    }

    private fun latestCoordinate(coordinate: String): Result<String> = runCatching {
        val latest = latestVersionForCoordinate(coordinate).getOrThrow()
        val parts = coordinate.split(":").toMutableList()
        parts[parts.lastIndex] = latest
        parts.joinToString(":")
    }

    private fun latestVersionForCoordinate(coordinate: String): Result<String> = runCatching {
        val parts = coordinate.split(":")
        if (parts.size < 2) return@runCatching ""
        val response = search("${parts[0]}:${parts[1]}", rows = 1)
        response.results.firstOrNull()?.latestVersion.orEmpty()
    }
}
