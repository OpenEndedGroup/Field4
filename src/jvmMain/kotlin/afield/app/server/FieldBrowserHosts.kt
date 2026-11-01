@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.app.server

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

private val registeredFieldBrowserHosts by lazy {
    readFieldBrowserHosts(Path.of(System.getProperty("user.home"), ".afield", "browser-hosts.json"),
        System.getenv("AFIELD_BROWSER_HOSTS"))
}

/** Exact additional browser authorities, configured by the local operator, never by a request. */
internal fun fieldBrowserHosts(): Set<String> = registeredFieldBrowserHosts

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
