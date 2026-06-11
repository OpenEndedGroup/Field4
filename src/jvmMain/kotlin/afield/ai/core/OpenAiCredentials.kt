package afield.ai.core

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.io.File

const val OPENAI_PLATFORM_KEY_ENV = "OPENAI_PLATFORM_KEY"
const val OPENAI_API_KEY_ENV = "OPENAI_API_KEY"

class MissingOpenAiCredentialException(message: String) : IllegalStateException(message)

data class OpenAiCredentialStatus(
    val configured: Boolean,
    val source: String,
    val configPath: String
)

fun openAiCredentialFile(): File =
    File(File(System.getProperty("user.home"), ".afield"), "openai-platform-key.txt")

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

fun openAiCredentialStatus(): OpenAiCredentialStatus {
    val file = openAiCredentialFile()
    val configured = readOpenAiCredential()
    return OpenAiCredentialStatus(
        configured = configured != null,
        source = configured?.second ?: "",
        configPath = file.absolutePath
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

fun openAiClientFromFieldConfig(): OpenAIClient {
    val (key, source) = readOpenAiCredential()
        ?: throw MissingOpenAiCredentialException(
            "AI4 needs an OpenAI Platform key. Set $OPENAI_PLATFORM_KEY_ENV, set $OPENAI_API_KEY_ENV, " +
                "or save one from /welcome. Field stores the welcome setting at ${openAiCredentialFile().absolutePath}."
        )

    return try {
        OpenAIOkHttpClient.builder().apiKey(key).build()
    } catch (t: Throwable) {
        throw IllegalStateException("Could not initialize OpenAI client from $source: ${t.message}", t)
    }
}
