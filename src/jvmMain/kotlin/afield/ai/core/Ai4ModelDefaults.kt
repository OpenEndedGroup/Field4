package afield.ai.core

internal object Ai4ModelDefaults {
    const val FLAGSHIP_MODEL = "gpt-5.5"
    const val FRONTIER_FALLBACK_MODEL = "gpt-5.4"
    const val FAST_MODEL = "gpt-5.4-mini"

    fun envOrDefault(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
}
