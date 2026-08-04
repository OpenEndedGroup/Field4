package afield.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Credential + client construction for the Anthropic (Claude) provider.
 *
 * Field's enterprise setup can't mint a raw `ANTHROPIC_API_KEY`, so the default
 * path here authenticates the way the official clients do: a short-lived OAuth
 * bearer token minted from an `ant auth login` profile via
 * `ant auth print-credentials --access-token`, sent as `Authorization: Bearer`
 * plus the `anthropic-beta: oauth-2025-04-20` header. A real API key
 * (`ANTHROPIC_API_KEY`) or a pre-supplied bearer token (`ANTHROPIC_AUTH_TOKEN`)
 * are honored first when present, so nothing here is `ant`-only.
 *
 * This mirrors [openAiClientFromFieldConfig] / [activeAiProviderRuntimeConfig]
 * for the OpenAI/OpenRouter providers; the client it returns exposes the same
 * `client.messages()` surface the [AnthropicBridge] drives.
 */

const val ANTHROPIC_API_KEY_ENV = "ANTHROPIC_API_KEY"
const val ANTHROPIC_AUTH_TOKEN_ENV = "ANTHROPIC_AUTH_TOKEN"
const val ANTHROPIC_BASE_URL_ENV = "ANTHROPIC_BASE_URL"
const val ANTHROPIC_PROFILE_ENV = "ANTHROPIC_PROFILE"
const val AFIELD_ANT_BIN_ENV = "AFIELD_ANT_BIN"

/** The OAuth beta header required whenever we authenticate with a bearer token rather than an API key. */
private const val ANTHROPIC_OAUTH_BETA = "oauth-2025-04-20"

/** Access tokens from `ant` are short-lived; re-mint proactively well inside their lifetime. */
private const val OAUTH_TOKEN_TTL_MS = 5 * 60 * 1000L

class MissingAnthropicCredentialException(message: String) : IllegalStateException(message)

/**
 * Resolved Anthropic auth for one request.
 * [isOAuth] selects the header shape: bearer token + oauth beta header vs. `x-api-key`.
 */
data class AnthropicAuth(
    val token: String,
    val source: String,
    val isOAuth: Boolean
)

data class AnthropicStatus(
    val available: Boolean,
    val source: String,
    val detail: String,
    val baseUrl: String,
    val profile: String
)

private fun anthropicProfileFile(): File =
    File(File(System.getProperty("user.home"), ".afield"), "anthropic-profile.txt")

private fun aiModelSettingsPropsFile(): File =
    File(File(System.getProperty("user.home"), ".afield"), "ai-models.properties")

/** The `ant` profile to bind to, if any: env → `~/.afield/anthropic-profile.txt` → active/default. */
fun configuredAnthropicProfile(): String? {
    System.getenv(ANTHROPIC_PROFILE_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching {
        anthropicProfileFile().takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** Base URL override: env → `ai-models.properties[anthropicBaseUrl]` → null (SDK default, api.anthropic.com). */
fun configuredAnthropicBaseUrl(): String? {
    System.getenv(ANTHROPIC_BASE_URL_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
    return runCatching {
        val props = Properties()
        aiModelSettingsPropsFile().takeIf { it.exists() }?.inputStream()?.use(props::load)
        props.getProperty("anthropicBaseUrl", "").trim().takeIf { it.isNotBlank() }?.trimEnd('/')
    }.getOrNull()
}

// --- OAuth token minting via the `ant` CLI ---------------------------------

private data class CachedToken(val token: String, val mintedAtMs: Long)

@Volatile
private var cachedOAuthToken: CachedToken? = null
private val tokenLock = Any()

/** Candidate `ant` binaries; the JVM's PATH often omits ~/go/bin, ~/.local/bin, etc. */
private fun antBinaryCandidates(): List<String> {
    val home = System.getProperty("user.home")
    return buildList {
        System.getenv(AFIELD_ANT_BIN_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        add("ant") // rely on PATH
        add("$home/go/bin/ant")
        add("$home/.local/bin/ant")
        add("/usr/local/bin/ant")
        add("/opt/homebrew/bin/ant")
    }
}

/**
 * Run `ant [--profile P] <args>` against the first candidate binary that exists,
 * returning (exitCode, stdout, stderr). Returns null only if no `ant` binary is runnable at all.
 */
private fun runAnt(profile: String?, vararg args: String): Triple<Int, String, String>? {
    for (bin in antBinaryCandidates()) {
        val cmd = buildList {
            add(bin)
            if (!profile.isNullOrBlank()) { add("--profile"); add(profile) }
            addAll(args)
        }
        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(false).start()
        } catch (_: Exception) {
            continue // binary not found at this path; try the next candidate
        }
        val out = proc.inputStream.bufferedReader().readText().trim()
        val err = proc.errorStream.bufferedReader().readText().trim()
        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return Triple(-1, out, "`ant` timed out after 20s")
        }
        return Triple(proc.exitValue(), out, err)
    }
    return null
}

private fun mintOAuthToken(profile: String?): String {
    val result = runAnt(profile, "auth", "print-credentials", "--access-token")
        ?: throw MissingAnthropicCredentialException(
            "Could not find the `ant` CLI. Install Anthropic's CLI and run `ant auth login`, " +
                "or set $AFIELD_ANT_BIN_ENV to its path (this is Anthropic's CLI, not Apache Ant)."
        )
    val (code, out, err) = result
    if (code != 0 || out.isBlank()) {
        throw MissingAnthropicCredentialException(
            "`ant auth print-credentials` failed (exit $code): ${err.take(240).ifBlank { "no output" }}. " +
                "Run `ant auth login` (or `ant --profile <name> auth login`) to authenticate."
        )
    }
    return out
}

/** Force a fresh token on the next resolve — call this after a 401 from the API. */
fun invalidateAnthropicOAuthToken() {
    synchronized(tokenLock) { cachedOAuthToken = null }
}

private fun anthropicOAuthToken(forceRefresh: Boolean): String {
    synchronized(tokenLock) {
        val now = System.currentTimeMillis()
        val cur = cachedOAuthToken
        if (!forceRefresh && cur != null && now - cur.mintedAtMs < OAUTH_TOKEN_TTL_MS) return cur.token
        val fresh = mintOAuthToken(configuredAnthropicProfile())
        cachedOAuthToken = CachedToken(fresh, now)
        return fresh
    }
}

/**
 * Resolve auth for a request. Precedence: explicit API key → explicit bearer token → `ant` OAuth profile.
 * Only the OAuth/bearer paths set [AnthropicAuth.isOAuth].
 */
fun resolveAnthropicAuth(forceRefresh: Boolean = false): AnthropicAuth {
    System.getenv(ANTHROPIC_API_KEY_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return AnthropicAuth(it, ANTHROPIC_API_KEY_ENV, isOAuth = false)
    }
    System.getenv(ANTHROPIC_AUTH_TOKEN_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return AnthropicAuth(it, ANTHROPIC_AUTH_TOKEN_ENV, isOAuth = true)
    }
    val profile = configuredAnthropicProfile()
    val source = if (profile.isNullOrBlank()) "ant auth (active profile)" else "ant auth (profile: $profile)"
    return AnthropicAuth(anthropicOAuthToken(forceRefresh), source, isOAuth = true)
}

fun anthropicClientFromFieldConfig(forceRefresh: Boolean = false): AnthropicClient {
    val auth = resolveAnthropicAuth(forceRefresh)
    val base = configuredAnthropicBaseUrl()
    return try {
        AnthropicOkHttpClient.builder()
            .apply {
                if (auth.isOAuth) {
                    authToken(auth.token)
                    putHeader("anthropic-beta", ANTHROPIC_OAUTH_BETA)
                } else {
                    apiKey(auth.token)
                }
                base?.let { baseUrl(it) }
            }
            .build()
    } catch (t: Throwable) {
        throw IllegalStateException(
            "Could not initialize Anthropic client from ${auth.source}: ${t.message}", t
        )
    }
}

/**
 * Non-throwing status for the /welcome screen. Reports whether we can currently
 * obtain a credential; the token mint is cached so repeat calls are cheap.
 */
fun anthropicStatus(): AnthropicStatus {
    val base = configuredAnthropicBaseUrl() ?: "https://api.anthropic.com"
    val profile = configuredAnthropicProfile() ?: ""
    System.getenv(ANTHROPIC_API_KEY_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return AnthropicStatus(true, ANTHROPIC_API_KEY_ENV, "API key from environment.", base, profile)
    }
    System.getenv(ANTHROPIC_AUTH_TOKEN_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return AnthropicStatus(true, ANTHROPIC_AUTH_TOKEN_ENV, "Bearer token from environment.", base, profile)
    }
    return runCatching {
        anthropicOAuthToken(forceRefresh = false)
        val src = if (profile.isBlank()) "ant auth (active profile)" else "ant auth (profile: $profile)"
        AnthropicStatus(true, src, "OAuth token minted from the ant CLI profile.", base, profile)
    }.getOrElse { e ->
        AnthropicStatus(false, "", e.message ?: "No Anthropic credential available.", base, profile)
    }
}
