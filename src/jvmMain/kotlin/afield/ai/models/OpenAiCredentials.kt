/**
 * Where Field's AI features find their provider, API key, endpoint and model names.
 *
 * Each setting is read from an environment variable first and then from a file under
 * `~/.afield/`: the provider (`AFIELD_AI_PROVIDER`, else `ai-provider.txt`, defaulting to
 * `openai`), the OpenAI key (`OPENAI_PLATFORM_KEY` or `OPENAI_API_KEY`, else
 * `openai-platform-key.txt`), the OpenRouter key and the per-provider model settings. The
 * /welcome page writes the files through [saveAiSettings] and [saveOpenAiPlatformKey], and
 * [aiProviderConfigRevision] changes on each [saveAiSettings] so cached clients know to rebuild.
 * [activeAiProviderRuntimeConfig] resolves all of it into the one [AiProviderRuntimeConfig] a
 * bridge is built from.
 */

package afield.ai.models

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

const val OPENAI_PLATFORM_KEY_ENV = "OPENAI_PLATFORM_KEY"
const val OPENAI_API_KEY_ENV = "OPENAI_API_KEY"
const val OPENROUTER_API_KEY_ENV = "OPENROUTER_API_KEY"
const val AFIELD_AI_PROVIDER_ENV = "AFIELD_AI_PROVIDER"
const val AFIELD_AI_BASE_URL_ENV = "AFIELD_AI_BASE_URL"
const val OPENROUTER_BASE_URL_ENV = "OPENROUTER_BASE_URL"
const val LOCAL_AI_BASE_URL_ENV = "AFIELD_LOCAL_AI_BASE_URL"
const val LOCAL_AI_API_KEY_ENV = "AFIELD_LOCAL_AI_API_KEY"

private const val OPENAI_BASE_URL = "https://api.openai.com/v1"
private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
const val LOCAL_AI_DEFAULT_BASE_URL = "http://127.0.0.1:8081/v1"

private val aiProviderConfigurationRevision = AtomicLong(0L)

/** Changes whenever /welcome saves provider, credential, endpoint, or model settings. */
fun aiProviderConfigRevision(): Long = aiProviderConfigurationRevision.get()

class MissingOpenAiCredentialException(message: String) : IllegalStateException(message)

data class OpenAiCredentialStatus(
    val configured: Boolean,
    val source: String,
    val configPath: String
)

data class AiModelSettings(
    val flagshipModel: String = "",
    val fastModel: String = "",
    val fallbackModel: String = "",
    val reasoningEffort: String = ""
)

data class AiProviderStatus(
    val provider: String,
    val hasOpenAiPlatformKey: Boolean,
    val hasOpenRouterApiKey: Boolean,
    val source: String,
    val configPath: String,
    val openRouterConfigPath: String,
    val baseUrl: String,
    val modelSettings: AiModelSettings
)

data class AiSettingsSaveRequest(
    val provider: String,
    val openAiPlatformKey: String?,
    val openRouterApiKey: String?,
    val openRouterBaseUrl: String?,
    val flagshipModel: String?,
    val fastModel: String?,
    val fallbackModel: String?,
    val localBaseUrl: String? = null,
    val localApiKey: String? = null
)

data class AiProviderRuntimeConfig(
    val provider: String,
    val apiKey: String,
    val source: String,
    val baseUrl: String,
    val modelSettings: AiModelSettings
) {
    val isOpenRouter: Boolean get() = provider == "openrouter"
    val usesStatelessResponses: Boolean get() = provider == "openrouter" || provider == "local"
}

private fun aiConfigDir(): File =
    System.getProperty("afield.ai.configDir")?.let(::File)
        ?: File(System.getProperty("user.home"), ".afield")

internal fun aiSettingsEnvironmentOverrides(): List<String> = listOf(
    AFIELD_AI_PROVIDER_ENV, AFIELD_AI_BASE_URL_ENV, LOCAL_AI_BASE_URL_ENV,
    "AFIELD_AI_MODEL", "AFIELD_AI_FAST_MODEL", "AFIELD_AI_FALLBACK_MODEL"
).filter { !System.getenv(it).isNullOrBlank() }

fun openAiCredentialFile(): File =
    File(aiConfigDir(), "openai-platform-key.txt")

fun openRouterCredentialFile(): File =
    File(aiConfigDir(), "openrouter-api-key.txt")

private fun localAiCredentialFile(): File =
    File(aiConfigDir(), "local-ai-api-key.txt")

internal fun readLocalAiCredential(): Pair<String, String>? {
    System.getenv(LOCAL_AI_API_KEY_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return it to LOCAL_AI_API_KEY_ENV
    }
    val file = localAiCredentialFile()
    return runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }?.let { it to file.absolutePath } }.getOrNull()
}

/** Accept an API base URL on this computer or another machine owned by the user. */
internal fun normalizeLocalAiBaseUrl(value: String): String {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull()
    require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Enter an http:// or https:// API base URL, such as http://gpu-host:8081/v1."
    }
    val base = value.trim().trimEnd('/')
    return if (uri.path.isNullOrEmpty() || uri.path == "/") "$base/v1" else base
}

private fun aiProviderFile(): File =
    File(aiConfigDir(), "ai-provider.txt")

private fun aiModelSettingsFile(): File =
    File(aiConfigDir(), "ai-models.properties")

private fun normalizeProvider(provider: String?): String {
    val normalized = provider?.trim()?.lowercase(Locale.US)
    return when (normalized) {
        "openrouter", "router" -> "openrouter"
        "anthropic", "claude" -> "anthropic"
        "local" -> "local"
        else -> "openai"
    }
}

fun readOpenAiCredential(): Pair<String, String>? {
    System.getenv(OPENAI_PLATFORM_KEY_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return it to OPENAI_PLATFORM_KEY_ENV
    }
    System.getenv(OPENAI_API_KEY_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return it to OPENAI_API_KEY_ENV
    }

    val file = openAiCredentialFile()
    return runCatching {
        file.takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { it to file.absolutePath }
    }.getOrNull()
}

fun readOpenRouterCredential(): Pair<String, String>? {
    System.getenv(OPENROUTER_API_KEY_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return it to OPENROUTER_API_KEY_ENV
    }

    val file = openRouterCredentialFile()
    return runCatching {
        file.takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { it to file.absolutePath }
    }.getOrNull()
}

fun configuredAiProvider(): String {
    System.getenv(AFIELD_AI_PROVIDER_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return normalizeProvider(it)
    }
    return runCatching {
        aiProviderFile().takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeProvider(it) }
    }.getOrNull() ?: "openai"
}

private fun modelSettingKey(provider: String, name: String): String = "$provider.$name"

private fun readAiModelSettings(provider: String = configuredAiProvider()): AiModelSettings {
    val props = Properties()
    runCatching {
        aiModelSettingsFile().takeIf { it.exists() }?.inputStream()?.use(props::load)
    }
    fun value(name: String): String {
        props.getProperty(modelSettingKey(provider, name), "").trim().takeIf { it.isNotBlank() }?.let { return it }
        // Files written before provider-scoped settings belong to the provider selected in that file.
        val savedProvider = runCatching {
            aiProviderFile().takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()?.let(::normalizeProvider)
        }.getOrNull() ?: "openai"
        return if (savedProvider == provider) props.getProperty(name, "").trim() else ""
    }
    return AiModelSettings(value("flagshipModel"), value("fastModel"), value("fallbackModel"), value("reasoningEffort"))
}

fun configuredAiModelSettings(provider: String = configuredAiProvider()): AiModelSettings = readAiModelSettings(provider)

/**
 * The local inference server's context window in tokens: `AFIELD_LOCAL_AI_CONTEXT_TOKENS`, else
 * `local.contextTokens` in `ai-models.properties`, else null, in which case the bridge learns it from the
 * server's first refusal of an oversized request.
 */
fun configuredLocalAiContextTokens(): Int? {
    System.getenv("AFIELD_LOCAL_AI_CONTEXT_TOKENS")?.trim()?.toIntOrNull()?.takeIf { it >= 2048 }?.let { return it }
    val props = Properties()
    runCatching { aiModelSettingsFile().takeIf { it.isFile }?.inputStream()?.use(props::load) }
    return props.getProperty("local.contextTokens", "").trim().toIntOrNull()?.takeIf { it >= 2048 }
}

fun configuredAiBaseUrl(provider: String = configuredAiProvider()): String {
    System.getenv(AFIELD_AI_BASE_URL_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
    if (provider == "local") {
        System.getenv(LOCAL_AI_BASE_URL_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { return normalizeLocalAiBaseUrl(it) }
        val props = Properties()
        runCatching { aiModelSettingsFile().takeIf { it.isFile }?.inputStream()?.use(props::load) }
        return props.getProperty("local.baseUrl", "").trim().takeIf { it.isNotBlank() }
            ?.let(::normalizeLocalAiBaseUrl) ?: LOCAL_AI_DEFAULT_BASE_URL
    }
    if (provider == "openrouter") {
        System.getenv(OPENROUTER_BASE_URL_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        runCatching {
            val props = Properties()
            aiModelSettingsFile().takeIf { it.exists() }?.inputStream()?.use(props::load)
            (props.getProperty(modelSettingKey("openrouter", "baseUrl"), "").ifBlank {
                props.getProperty("openRouterBaseUrl", "")
            }).trim().takeIf { it.isNotBlank() }?.trimEnd('/')
        }.getOrNull()?.let { return it }
        return OPENROUTER_BASE_URL
    }
    return OPENAI_BASE_URL
}

fun openAiCredentialStatus(): OpenAiCredentialStatus {
    val file = openAiCredentialFile()
    val configured = readOpenAiCredential()
    return OpenAiCredentialStatus(
        configured = configured != null,
        source = configured?.second ?: "",
        configPath = file.absolutePath
    )
}

fun aiProviderStatus(): AiProviderStatus {
    val provider = configuredAiProvider()
    val openAi = readOpenAiCredential()
    val openRouter = readOpenRouterCredential()
    val source = when (provider) {
        "local" -> readLocalAiCredential()?.second ?: "Local endpoint (no API key)"
        "openrouter" -> openRouter?.second
        else -> openAi?.second
    }
    return AiProviderStatus(
        provider = provider,
        hasOpenAiPlatformKey = openAi != null,
        hasOpenRouterApiKey = openRouter != null,
        source = source ?: "",
        configPath = openAiCredentialFile().absolutePath,
        openRouterConfigPath = openRouterCredentialFile().absolutePath,
        baseUrl = configuredAiBaseUrl(provider),
        modelSettings = configuredAiModelSettings(provider)
    )
}

fun saveOpenAiPlatformKey(key: String): OpenAiCredentialStatus {
    val trimmed = key.trim()
    require(trimmed.isNotBlank()) { "OpenAI Platform key is blank." }
    val file = openAiCredentialFile()
    file.parentFile?.mkdirs()
    file.writeText("$trimmed\n", Charsets.UTF_8)
    return openAiCredentialStatus()
}

fun saveAiSettings(request: AiSettingsSaveRequest): AiProviderStatus {
    val provider = normalizeProvider(request.provider)
    val suppliedOpenAiKey = request.openAiPlatformKey?.trim().orEmpty()
    val suppliedOpenRouterKey = request.openRouterApiKey?.trim().orEmpty()
    // Validate before writing any setting, so a failed save keeps the working provider.
    val localBaseUrl = if (provider == "local") normalizeLocalAiBaseUrl(request.localBaseUrl.orEmpty()) else null
    if (provider == "local") require(!request.flagshipModel.isNullOrBlank()) { "Enter the model ID served by your local endpoint." }
    val previousLocalBaseUrl = configuredAiBaseUrl("local")
    val previousSavedProvider = runCatching { normalizeProvider(aiProviderFile().readText()) }.getOrDefault("openai")
    if (provider == "openai" && suppliedOpenAiKey.isBlank() && readOpenAiCredential() == null) {
        throw IllegalArgumentException("OpenAI selected, but no OpenAI Platform key is configured.")
    }
    if (provider == "openrouter" && suppliedOpenRouterKey.isBlank() && readOpenRouterCredential() == null) {
        throw IllegalArgumentException("OpenRouter selected, but no OpenRouter API key is configured.")
    }
    aiProviderFile().parentFile?.mkdirs()
    aiProviderFile().writeText("$provider\n", Charsets.UTF_8)

    suppliedOpenAiKey.takeIf { it.isNotBlank() }?.let { saveOpenAiPlatformKey(it) }

    suppliedOpenRouterKey.takeIf { it.isNotBlank() }?.let { key ->
        val file = openRouterCredentialFile()
        file.parentFile?.mkdirs()
        file.writeText("$key\n", Charsets.UTF_8)
    }
    if (provider == "local") {
        val key = request.localApiKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            localAiCredentialFile().apply { parentFile.mkdirs(); writeText("$key\n") }
        } else if (localBaseUrl != previousLocalBaseUrl) {
            // A credential saved for one endpoint must not follow a URL change.
            localAiCredentialFile().delete()
        }
    }

    val props = Properties()
    runCatching {
        aiModelSettingsFile().takeIf { it.exists() }?.inputStream()?.use(props::load)
    }
    // Older settings were global; move them to their original provider before switching.
    listOf("flagshipModel", "fastModel", "fallbackModel", "reasoningEffort").forEach { name ->
        val scoped = modelSettingKey(previousSavedProvider, name)
        val legacy = props.getProperty(name, "").trim()
        if (props.getProperty(scoped, "").isBlank() && legacy.isNotBlank()) props.setProperty(scoped, legacy)
    }
    props.getProperty("openRouterBaseUrl", "").trim().takeIf { it.isNotBlank() }?.let {
        if (props.getProperty("openrouter.baseUrl", "").isBlank()) props.setProperty("openrouter.baseUrl", it)
    }
    fun replace(name: String, value: String?) {
        val key = modelSettingKey(provider, name)
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) props.remove(key) else props.setProperty(key, trimmed)
    }
    replace("flagshipModel", request.flagshipModel)
    replace("fastModel", request.fastModel)
    replace("fallbackModel", request.fallbackModel)
    if (provider == "openrouter") replace("baseUrl", request.openRouterBaseUrl?.trimEnd('/'))
    if (provider == "local") replace("baseUrl", localBaseUrl)
    // Do not let legacy global overrides leak into another provider after this save.
    listOf("flagshipModel", "fastModel", "fallbackModel", "openRouterBaseUrl").forEach(props::remove)
    aiModelSettingsFile().parentFile?.mkdirs()
    aiModelSettingsFile().outputStream().use { props.store(it, "Field AI settings") }

    aiProviderConfigurationRevision.incrementAndGet()
    return aiProviderStatus()
}

fun activeAiProviderRuntimeConfig(): AiProviderRuntimeConfig {
    val provider = configuredAiProvider()
    if (provider == "local") {
        val model = System.getenv("AFIELD_AI_MODEL")?.trim()?.takeIf { it.isNotBlank() }
            ?: configuredAiModelSettings("local").flagshipModel
        require(model.isNotBlank()) { "Choose a local model in Welcome → AI → Local." }
        val credential = readLocalAiCredential()
        return AiProviderRuntimeConfig("local", credential?.first ?: "local-no-key-required",
            credential?.second ?: "Local endpoint (no API key)", normalizeLocalAiBaseUrl(configuredAiBaseUrl("local")),
            configuredAiModelSettings("local"))
    }
    val credential = if (provider == "openrouter") readOpenRouterCredential() else readOpenAiCredential()
    val (key, source) = credential
        ?: throw MissingOpenAiCredentialException(
            if (provider == "openrouter") {
                "AI4 needs an OpenRouter API key. Set $OPENROUTER_API_KEY_ENV or save one from /welcome. " +
                    "Field stores the welcome setting at ${openRouterCredentialFile().absolutePath}."
            } else {
                "AI4 needs an OpenAI Platform key. Set $OPENAI_PLATFORM_KEY_ENV, set $OPENAI_API_KEY_ENV, " +
                    "or save one from /welcome. Field stores the welcome setting at ${openAiCredentialFile().absolutePath}."
            }
        )

    return AiProviderRuntimeConfig(
        provider = provider,
        apiKey = key,
        source = source,
        baseUrl = configuredAiBaseUrl(provider),
        modelSettings = configuredAiModelSettings(provider)
    )
}

internal val localRequestTimeout: com.openai.core.Timeout = com.openai.core.Timeout.builder()
    .request(java.time.Duration.ofMinutes(45))
    .read(java.time.Duration.ofMinutes(5))
    .build()

fun openAiClientFromFieldConfig(): OpenAIClient {
    val config = activeAiProviderRuntimeConfig()
    return try {
        OpenAIOkHttpClient.builder()
            .apiKey(config.apiKey)
            .baseUrl(config.baseUrl)
            .apply {
                // The SDK's default caps a whole request at 10 minutes. A local model at ~15 tokens/s cannot
                // write a large tool call in that time: on 2026-10-28 a round was cut at 602 s, mid-answer.
                // Allow a long generation, but fail one that goes silent between chunks.
                if (config.provider == "local") timeout(localRequestTimeout)
                if (config.isOpenRouter) {
                    putHeader("HTTP-Referer", "https://localhost:9092")
                    putHeader("X-OpenRouter-Title", "Field4")
                }
            }
            .build()
    } catch (t: Throwable) {
        throw IllegalStateException("Could not initialize ${config.provider} client from ${config.source}: ${t.message}", t)
    }
}
