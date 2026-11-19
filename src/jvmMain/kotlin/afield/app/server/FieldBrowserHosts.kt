@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.app.server

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

private val registeredFieldBrowserHosts = FieldBrowserHostsFile(
    Path.of(System.getProperty("user.home"), ".afield", "browser-hosts.json"),
    System.getenv("AFIELD_BROWSER_HOSTS"),
)

/**
 * Exact additional browser authorities, configured by the local operator, never by a request.
 * An edit to `~/.afield/browser-hosts.json` takes effect on the next request; no restart.
 */
internal fun fieldBrowserHosts(): Set<String> = registeredFieldBrowserHosts.current()

/**
 * The registered hosts, re-read whenever the file's modification time or size changes, so a host
 * added while Field runs is honoured at once. Between changes a call costs one `stat`.
 */
internal class FieldBrowserHostsFile(private val path: Path, private val environment: String?) {
    private data class Stamp(val modified: Long, val size: Long)
    @Volatile private var cached: Pair<Stamp?, Set<String>>? = null

    fun current(): Set<String> {
        val stamp = runCatching {
            if (Files.isRegularFile(path)) Stamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path)) else null
        }.getOrNull()
        cached?.let { (seen, hosts) -> if (seen == stamp) return hosts }
        return readFieldBrowserHosts(path, environment).also { cached = stamp to it }
    }
}

/** Invalid or oversized files fail closed; an explicit environment list can still be used. */
internal fun readFieldBrowserHosts(path: Path, environment: String? = null): Set<String> {
    val saved = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) > 16_384) return@runCatching emptyList<String>()
        val entries = Json.parseToJsonElement(Files.readString(path)).jsonArray
        require(entries.size <= 128)
        entries.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
    }.getOrDefault(emptyList())
    return (saved + environment.orEmpty().split(',')).map(String::trim).filter(::validFieldDnsAuthority)
        .map(String::lowercase).toSet()
}

/** DNS names only; localhost and literal IPs already have their own validation. Ports are exact. */
internal fun validFieldDnsAuthority(authority: String): Boolean {
    if (authority.count { it == ':' } > 1) return false
    if (':' in authority && authority.substringAfter(':').toIntOrNull()?.let { it in 1..65535 } != true) return false
    val host = authority.substringBefore(':')
    return host.length in 1..253 && host.split('.').all {
        it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
    }
}
