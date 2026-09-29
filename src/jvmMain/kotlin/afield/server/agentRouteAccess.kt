package afield.server

/**
 * The guard for `POST /api/agent-task` and `POST /api/agent-eval`, which run code in Field's JVM and
 * may spend the machine's model tokens.
 *
 * Deliberately **not** [refuseUnlessSameSiteDocsClient]: that guard reads the documentation
 * annotation policy, whose opt-ins (`allowRemoteAnnotations`, `allowCrossSiteAnnotations`) exist so a
 * tablet can annotate the docs. Turning those on must not also open code execution to the LAN or to
 * other websites (review, 2026-09-29). This policy has no opt-in at all. Three conditions, all required:
 *
 *  - the peer is loopback ([isLoopbackDocsClient]; no name lookup);
 *  - the `Host` header names `localhost`, `127.0.0.1` or `[::1]` (any port) — not merely "a literal IP",
 *    which [isAllowedFieldHost] accepts. A DNS-rebinding page reaches loopback under its *own* name,
 *    and that name is in `Host`;
 *  - a browser-initiated request is same-origin ([isSameSiteRequest]). curl and the backdoor send no
 *    `Origin`/`Sec-Fetch-Site` and pass.
 *
 * @return a refusal message, or null to allow.
 */
internal fun agentRouteRefusal(remoteAddress: String?, hostHeader: String?, secFetchSite: String?, origin: String?): String? {
    if (!isLoopbackDocsClient(remoteAddress)) return "Field's agent routes are loopback-only."
    if (!isAgentRouteHost(hostHeader)) return "Field's agent routes answer only to Host localhost, 127.0.0.1 or [::1]."
    if (!isSameSiteRequest(secFetchSite, origin, hostHeader)) return "Field's agent routes refuse cross-site browser requests."
    return null
}

internal fun isAgentRouteHost(hostHeader: String?): Boolean {
    val authority = hostHeader?.trim()?.lowercase().orEmpty()
    if (authority.isEmpty()) return false
    val host = if (authority.startsWith("[")) authority.substringBefore("]") + "]" else authority.substringBefore(':')
    val port = authority.removePrefix(host).removePrefix(":")
    if (port.isNotEmpty() && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) return false
    return host in setOf("localhost", "127.0.0.1", "[::1]")
}
