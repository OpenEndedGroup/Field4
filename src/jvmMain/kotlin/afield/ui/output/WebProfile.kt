package afield.ui.output

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.runtime.execution.go
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * A web profile: where `web(url, profile = ...)` pages keep their cookies, logins and storage.
 * Get one with [webProfile], or [sharedWebProfile] for the pages that have no profile (and Field's
 * own pages). [cookies] reads and writes its cookies while Field's Electron window is open.
 *
 * ```kotlin
 * val github = webProfile("github")
 * github.cookies.get("https://github.com").thenAccept { all ->
 *     all.forEach { println("${it.name} = ${it.value.take(12)}") }
 * }
 * ```
 */
class WebProfile internal constructor(
    internal val document: SessionInformation,
    /** The profile's directory; null for the shared profile. */
    val directory: File?,
) {
    /** This profile's cookies. */
    val cookies: WebCookies = WebCookies(this)

    internal fun send(action: String, params: JsonObject = JsonObject(emptyMap())): CompletableFuture<JsonElement> =
        LiveOutputIframeBridge.send(document, OutputIframeTarget("", ""), "profile",
            url = directory?.absolutePath ?: "", method = action, params = params).thenApply { it.value }

    internal val key: String get() = directory?.absolutePath ?: ""

    override fun toString() = "WebProfile(${directory ?: "shared"})"
}

/**
 * The web profile called [profile]: the same name or file spec that `web(url, profile = ...)`
 * takes, so `webProfile("github")` is the profile `web("github.com", profile = "github")` logs in
 * with. Its directory is made if it is missing.
 */
fun Box.webProfile(profile: String): WebProfile = WebProfile(inside, webProfileDirectory(profile))

/** The profile `web(url)` pages without a profile share with each other and with Field itself. */
val Box.sharedWebProfile: WebProfile get() = WebProfile(inside, null)

/**
 * One cookie, as Chromium stores it. [expires] is null for a session cookie, which lasts until
 * Field quits. [sameSite] is `unspecified`, `no_restriction`, `lax` or `strict`.
 */
data class WebCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val expires: Instant? = null,
    val sameSite: String = "unspecified",
    val hostOnly: Boolean = false,
) {
    internal companion object {
        fun from(element: JsonElement): WebCookie? {
            val o = element as? JsonObject ?: return null
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            fun bool(key: String) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: false
            val expiry = (o["expirationDate"] as? JsonPrimitive)?.doubleOrNull
            return WebCookie(
                name = str("name") ?: return null,
                value = str("value") ?: "",
                domain = str("domain") ?: "",
                path = str("path") ?: "/",
                secure = bool("secure"),
                httpOnly = bool("httpOnly"),
                expires = if (bool("session") || expiry == null) null else Instant.ofEpochMilli((expiry * 1000).toLong()),
                sameSite = str("sameSite") ?: "unspecified",
                hostOnly = bool("hostOnly"),
            )
        }
    }
}

/**
 * A change to a cookie in a watched profile, from [onCookieChange]. [removed] is true when the
 * cookie went away; [cause] is Chromium's reason: `inserted` (a new or changed cookie), `explicit` (a
 * page or this API removed it), `overwrite` (the old value of a cookie being replaced, which is
 * followed by the `inserted` new one), `expired`, `evicted` or `expired-overwrite`.
 */
data class WebCookieChange(val cookie: WebCookie, val removed: Boolean, val cause: String)

/**
 * The cookies of one [WebProfile]. Every call returns a future and needs Field's Electron window
 * showing this document.
 */
class WebCookies internal constructor(private val profile: WebProfile) {
    /**
     * The cookies a request to [url] would carry, or every cookie in the profile when [url] is null.
     * [name] and [domain] narrow the answer further.
     */
    fun get(url: String? = null, name: String? = null, domain: String? = null): CompletableFuture<List<WebCookie>> =
        profile.send("cookies-get", params("url" to url?.let(::webAddress), "name" to name, "domain" to domain))
            .thenApply { answer -> (answer as? JsonArray).orEmpty().mapNotNull(WebCookie::from) }

    /**
     * Set a cookie for [url]. Leave [domain] null for a cookie only [url]'s host gets; give one
     * (`.example.com`) to share it with subdomains. [expires] null makes a session cookie.
     */
    fun set(url: String, name: String, value: String, domain: String? = null, path: String? = null,
            secure: Boolean? = null, httpOnly: Boolean? = null, expires: Instant? = null,
            sameSite: String? = null): CompletableFuture<Unit> {
        require(name.isNotEmpty()) { "A cookie needs a name" }
        return profile.send("cookies-set", params(
            "url" to webAddress(url), "name" to name, "value" to value, "domain" to domain, "path" to path,
            "secure" to secure, "httpOnly" to httpOnly, "expirationDate" to expires?.let { it.toEpochMilli() / 1000.0 },
            "sameSite" to sameSite,
        )).thenApply { }
    }

    /** Remove the cookie called [name] that a request to [url] would carry. */
    fun remove(url: String, name: String): CompletableFuture<Unit> =
        profile.send("cookies-remove", params("url" to webAddress(url), "name" to name)).thenApply { }

    /** Remove every cookie [url] would carry, or every cookie in the profile when [url] is null; completes with how many. */
    fun clear(url: String? = null): CompletableFuture<Int> = get(url).thenCompose { all ->
        val removals = all.map { cookie ->
            val host = cookie.domain.removePrefix(".")
            remove("${if (cookie.secure) "https" else "http"}://$host${cookie.path}", cookie.name)
        }
        CompletableFuture.allOf(*removals.toTypedArray()).thenApply { all.size }
    }

    /** Write cookies to the profile's directory now rather than when Chromium next gets round to it. */
    fun flush(): CompletableFuture<Unit> = profile.send("cookies-flush").thenApply { }

    private fun params(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.mapNotNull { (key, value) ->
        when (value) {
            null -> null
            is String -> key to JsonPrimitive(value)
            is Boolean -> key to JsonPrimitive(value)
            is Number -> key to JsonPrimitive(value)
            else -> key to JsonPrimitive(value.toString())
        }
    }.toMap())
}

/** The cookie watches running for each box and name, so a box run again replaces its watch. */
private class CookieWatch(val document: SessionInformation, val profileKey: String, val changes: Channel<WebCookieChange>)

private val cookieWatches = ConcurrentHashMap<Pair<String, String>, CookieWatch>()

/**
 * Call [onChange] for every cookie that is set, changed or removed in [profile] from now on, by a
 * page or by code.
 *
 * One watch per box and [name]: running the box again replaces it and stopping the box ends it.
 * [onChange] runs on Field's runloop. The changes come from Field's Electron window, so a watch
 * hears nothing while no Electron window shows this document.
 *
 * ```kotlin
 * onCookieChange(webProfile("github")) { change ->
 *     println("${if (change.removed) "-" else "+"} ${change.cookie.name} (${change.cause})")
 * }
 * ```
 */
fun Box.onCookieChange(profile: WebProfile, name: String = "cookies", onChange: suspend (WebCookieChange) -> Unit) {
    val key = id to name
    val watch = CookieWatch(profile.document, profile.key, Channel(Channel.UNLIMITED))
    // Registered and subscribed now, not when the coroutine below first runs: a cookie this same
    // box sets on its next line must already be heard. Electron takes requests in the order they
    // are sent, so the subscription is in place before that cookie is set.
    cookieWatches.put(key, watch)?.takeIf { it !== watch }?.changes?.close()
    val subscribed = profile.send("cookies-watch")
    go {
        val job = coroutineContext.job
        job.invokeOnCompletion {
            cookieWatches.remove(key, watch)
            watch.changes.close()
        }
        subscribed.await()
        for (change in watch.changes) onChange(change)
    }
}

/** Where Electron's `web-profile-event` messages (one per cookie change) arrive from the Field page. */
internal object WebProfileEvents {
    fun dispatch(document: SessionInformation, message: JsonElement) {
        val o = message as? JsonObject ?: return
        when ((o["kind"] as? JsonPrimitive)?.contentOrNull) {
            "visit", "title" -> return reportWebNavigation(document, o)
            "cookie" -> {}
            else -> return
        }
        val directory = (o["directory"] as? JsonPrimitive)?.contentOrNull ?: ""
        val cookie = o["cookie"]?.let(WebCookie::from) ?: return
        val change = WebCookieChange(cookie,
            (o["removed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["cause"] as? JsonPrimitive)?.contentOrNull ?: "")
        for (watch in cookieWatches.values) {
            if (watch.document === document && watch.profileKey == directory) watch.changes.trySend(change)
        }
    }
}
