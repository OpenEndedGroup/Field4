package afield.app.server

import afield.io.osc.query.Mdns
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress

internal class FieldLanDiscovery {
    private val browseLock = Mutex()
    @Volatile private var advertisedName: String? = null

    fun advertise(bindHost: String, port: Int): Closeable? {
        val bound = InetAddress.getAllByName(bindHost).first()
        if (bound.isLoopbackAddress || (bound !is Inet4Address && !bound.isAnyLocalAddress)) return null
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("computer")
            .substringBefore('.').replace(Regex("[^a-zA-Z0-9-]"), "-").take(36).ifBlank { "computer" }
        val name = "Field on $host ($port)"
        val addresses = if (bound.isAnyLocalAddress) null else listOf(bound.hostAddress)
        advertisedName = name
        return Mdns.advertise(name, SERVICE_TYPE, port, addresses)
    }

    fun installRoutes(route: Route) = with(route) {
        get("/api/remote-field/lan") {
            if (call.refuseRemoteFieldDiscovery()) return@get
            if (!browseLock.tryLock()) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }
            try {
                val fields = withContext(Dispatchers.IO) { browse() }
                call.respond(fields)
            } finally {
                browseLock.unlock()
            }
        }
    }

    private fun browse(): List<FieldLanPeer> = runCatching {
        Mdns.browse(SERVICE_TYPE, timeoutMs = 1200, preferReplyAddress = true)
    }.getOrDefault(emptyList()).mapNotNull { service ->
        if (service.instance == advertisedName || service.port !in 1..65535) return@mapNotNull null
        val address = service.addresses.firstOrNull { ip ->
            val literal = runCatching { InetAddress.ofLiteral(ip) }.getOrNull()
            literal is Inet4Address && !literal.isLoopbackAddress && !literal.isAnyLocalAddress && !literal.isMulticastAddress
        } ?: return@mapNotNull null
        FieldLanPeer(service.instance, "https://$address:${service.port}")
    }

    @Serializable
    private data class FieldLanPeer(val name: String, val url: String)

    private companion object {
        const val SERVICE_TYPE = "_field4._tcp"
    }
}

private suspend fun ApplicationCall.refuseRemoteFieldDiscovery(): Boolean {
    val host = request.headers[HttpHeaders.Host]
    if (isLoopbackDocsClient(request.local.remoteAddress) && isAgentRouteHost(host) &&
        isSameSiteRequest(request.headers["Sec-Fetch-Site"], request.headers[HttpHeaders.Origin], host)
    ) return false
    respond(HttpStatusCode.Forbidden)
    return true
}
