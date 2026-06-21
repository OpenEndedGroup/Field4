package afield.ai.core

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.io.File
import java.util.Locale
import java.util.Properties

const val OPENAI_PLATFORM_KEY_ENV = "OPENAI_PLATFORM_KEY"
const val OPENAI_API_KEY_ENV = "OPENAI_API_KEY"
const val OPENROUTER_API_KEY_ENV = "OPENROUTER_API_KEY"
const val AFIELD_AI_PROVIDER_ENV = "AFIELD_AI_PROVIDER"
const val AFIELD_AI_BASE_URL_ENV = "AFIELD_AI_BASE_URL"
const val OPENROUTER_BASE_URL_ENV = "OPENROUTER_BASE_URL"

private const val OPENAI_BASE_URL = "https://api.openai.com/v1"
private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

class MissingOpenAiCredentialException(message: String) : IllegalStateException(message)

data class OpenAiCredentialStatus(
    val configured: Boolean,
    val source: String,
    val configPath: String
)

data class AiModelSettings(
    val flagshipModel: String = "",
    val fastModel: String = "",
    val fallbackModel: String = ""
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
    val fallbackModel: String?
)

data class AiProviderRuntimeConfig(
    val provider: String,
    val apiKey: String,
    val source: String,
    val baseUrl: String,
    val modelSettings: AiModelSettings
) {
    val isOpenRouter: Boolean get() = provider == "openrouter"
}

fun openAiCredentialFile(): File =
    File(File(System.getProperty("user.home"), ".afield"), "openai-platform-key.txt")

fun openRouterCredentialFile(): File =
    File(File(System.getProperty("user.home"), ".afield"), "openrouter-api-key.txt")

private fun aiProviderFile(): File =
    File(File(System.getProperty("user.home"), ".afield"), "ai-provider.txt")

private fun aiModelSettingsFile(): File =
    File(File(System.getProperty("user.home"), ".afield"), "ai-models.properties")

private fun normalizeProvider(provider: String?): String {
    val normalized = provider?.trim()?.lowercase(Locale.US)
    return when (normalized) {
        "openrouter", "router" -> "openrouter"
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

private fun readAiModelSettings(): AiModelSettings {
    val props = Properties()
    runCatching {
        aiModelSettingsFile().takeIf { it.exists() }?.inputStream()?.use(props::load)
    }
    return AiModelSettings(
        flagshipModel = props.getProperty("flagshipModel", "").trim(),
        fastModel = props.getProperty("fastModel", "").trim(),
        fallbackModel = props.getProperty("fallbackModel", "").trim()
    )
}

fun configuredAiModelSettings(): AiModelSettings = readAiModelSettings()

fun configuredAiBaseUrl(provider: String = configuredAiProvider()): String {
    System.getenv(AFIELD_AI_BASE_URL_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
    if (provider == "openrouter") {
        System.getenv(OPENROUTER_BASE_URL_ENV)?.trim()?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        runCatching {
            val props = Properties()
            aiModelSettingsFile().takeIf { it.exists() }?.inputStream()?.use(props::load)
            props.getProperty("openRouterBaseUrl", "").trim().takeIf { it.isNotBlank() }?.trimEnd('/')
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
    val source = if (provider == "openrouter") openRouter?.second else openAi?.second
    return AiProviderStatus(
        provider = provider,
        hasOpenAiPlatformKey = openAi != null,
        hasOpenRouterApiKey = openRouter != null,
        source = source ?: "",
        configPath = openAiCredentialFile().absolutePath,
        openRouterConfigPath = openRouterCredentialFile().absolutePath,
        baseUrl = configuredAiBaseUrl(provider),
        modelSettings = configuredAiModelSettings()
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
    aiProviderFile().parentFile?.mkdirs()
    aiProviderFile().writeText("$provider\n", Charsets.UTF_8)

    request.openAiPlatformKey?.trim()?.takeIf { it.isNotBlank() }?.let { saveOpenAiPlatformKey(it) }

    request.openRouterApiKey?.trim()?.takeIf { it.isNotBlank() }?.let { key ->
        val file = openRouterCredentialFile()
        file.parentFile?.mkdirs()
        file.writeText("$key\n", Charsets.UTF_8)
    }

    val props = Properties()
    fun putIfPresent(name: String, value: String?) {
        value?.trim()?.takeIf { it.isNotBlank() }?.let { props.setProperty(name, it) }
    }
    putIfPresent("flagshipModel", request.flagshipModel)
    putIfPresent("fastModel", request.fastModel)
    putIfPresent("fallbackModel", request.fallbackModel)
    putIfPresent("openRouterBaseUrl", request.openRouterBaseUrl?.trimEnd('/'))
    val existing = Properties()
    runCatching {
        aiModelSettingsFile().takeIf { it.exists() }?.inputStream()?.use(existing::load)
    }
    existing.forEach { key, value ->
        if (!props.containsKey(key)) props[key] = value
    }
    aiModelSettingsFile().parentFile?.mkdirs()
    aiModelSettingsFile().outputStream().use { props.store(it, "Field AI settings") }

    if (provider == "openai" && readOpenAiCredential() == null) {
        throw IllegalArgumentException("OpenAI selected, but no OpenAI Platform key is configured.")
    }
    if (provider == "openrouter" && readOpenRouterCredential() == null) {
        throw IllegalArgumentException("OpenRouter selected, but no OpenRouter API key is configured.")
    }

    return aiProviderStatus()
}

fun activeAiProviderRuntimeConfig(): AiProviderRuntimeConfig {
    val provider = configuredAiProvider()
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
        modelSettings = configuredAiModelSettings()
    )
}

fun openAiClientFromFieldConfig(): OpenAIClient {
    val config = activeAiProviderRuntimeConfig()
    return try {
        OpenAIOkHttpClient.builder()
            .apiKey(config.apiKey)
            .baseUrl(config.baseUrl)
            .apply {
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
