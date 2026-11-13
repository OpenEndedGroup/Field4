package afield.app.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Web folders Field serves at [PREFIX], beside its own build output, so a viewer tool (see
 * `ThreeSceneBuilder.tools`) or any other browser module can live in the project that owns it
 * instead of being copied into `build/`.
 *
 * Folders are looked up on every request, first match wins, in this order:
 *
 *  1. `src/personal/web` in the checkout Field runs from, when it exists;
 *  2. `web/` in each extension root — `src/personal` and every `AFIELD_EXTENSION_DIRS` (or
 *     `-PfieldExtensionDirs`) or kit extension directory, passed by `build.gradle.kts` as the
 *     [DIRS_PROPERTY] system property — and `web/` in each `AFIELD_EXTENSION_DIRS` entry the JVM
 *     itself sees, for launches that did not come through Gradle;
 *  3. each folder in the [DIRS_ENV] environment variable, served as it is (no `web/` appended);
 *  4. folders added at runtime with [register].
 *
 * Every response carries `Cache-Control: no-store`, and [versioned] appends a hash of the file's
 * bytes, so an edited module is fetched afresh after a reload and a viewer that already imported
 * the old one sees a new URL rather than its module cache.
 */
object ExtensionWeb {
    /** The URL prefix every registered folder is served under. */
    const val PREFIX = "/extension-web/"

    /** Set by `requiredRunJvmArgs` in build.gradle.kts: the extension roots' `web/` folders, `File.pathSeparator`-separated. */
    const val DIRS_PROPERTY = "afield.extension.web"

    /** Extra folders to serve, `File.pathSeparator`-separated, each served as it is. */
    const val DIRS_ENV = "AFIELD_WEB_DIRS"

    private val added = CopyOnWriteArrayList<File>()

    /** Serve [dir] too, after every configured folder. Returns a function that stops serving it. */
    fun register(dir: File): () -> Unit {
        val canonical = dir.canonicalFile
        added.addIfAbsent(canonical)
        return { added.remove(canonical) }
    }

    private fun paths(value: String?): List<String> =
        value.orEmpty().split(File.pathSeparatorChar).map(String::trim).filter(String::isNotEmpty)

    /** The folders served right now, in lookup order; only those that exist. */
    val folders: List<File>
        get() = buildList {
            add(File("src/personal/web"))
            paths(System.getProperty(DIRS_PROPERTY)).forEach { add(File(it)) }
            paths(System.getenv("AFIELD_EXTENSION_DIRS")).forEach { add(File(it, "web")) }
            paths(System.getenv(DIRS_ENV)).forEach { add(File(it)) }
            addAll(added)
        }.map { it.canonicalFile }.distinct().filter { it.isDirectory }

    /** The file [relative] names in the first folder that has it, or `null`; never a path outside a folder. */
    fun resolve(relative: String): File? {
        if (relative.isEmpty() || '\\' in relative || '\u0000' in relative) return null
        for (root in folders) {
            val candidate = File(root, relative).canonicalFile
            if (candidate.isFile && candidate.path.startsWith(root.path + File.separator)) return candidate
        }
        return null
    }

    /**
     * [path] with `?v=<hash of the file's bytes>` appended, when it is a plain [PREFIX] path to a
     * file some folder serves; any other path, or one that already has a query, comes back unchanged.
     */
    fun versioned(path: String): String {
        if (!path.startsWith(PREFIX) || '?' in path || '#' in path) return path
        val file = resolve(path.removePrefix(PREFIX)) ?: return path
        return "$path?v=${contentHash(file)}"
    }

    /** The versioned URL of [relative] under [PREFIX] — `url("my_tool.js")` is `/extension-web/my_tool.js?v=…`. */
    fun url(relative: String): String = versioned(PREFIX + relative.removePrefix("/"))

    /** The first 12 hex digits of the SHA-256 of [file]'s bytes. */
    internal fun contentHash(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).take(6).joinToString("") { "%02x".format(it) }
}

/** `GET /extension-web/{...}`: a file from one of [ExtensionWeb.folders], never cached. */
internal fun Route.installExtensionWebRoute() {
    get("/extension-web/{...}") {
        val relative = runCatching { URI(call.request.uri).path }.getOrNull()?.removePrefix(ExtensionWeb.PREFIX).orEmpty()
        val file = ExtensionWeb.resolve(relative)
            ?: return@get call.respond(HttpStatusCode.NotFound, "extension web file not found")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondFile(file)
    }
}
