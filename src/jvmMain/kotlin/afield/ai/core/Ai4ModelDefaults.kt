package afield.ai.core

internal object Ai4ModelDefaults {
    const val FLAGSHIP_MODEL = "gpt-5.5"
    const val FRONTIER_FALLBACK_MODEL = "gpt-5.4"
    const val FAST_MODEL = "gpt-5.4-mini"

    fun flagshipModel(): String = envOrDefault("AFIELD_AI_MODEL", FLAGSHIP_MODEL)

    fun fastModel(): String = envOrDefault("AFIELD_AI_FAST_MODEL", FAST_MODEL)

    fun fallbackModel(): String = envOrDefault("AFIELD_AI_FALLBACK_MODEL", FRONTIER_FALLBACK_MODEL)

    fun envOrDefault(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: configuredModelForDefault(default)

    private fun configuredModelForDefault(default: String): String {
        val settings = configuredAiModelSettings()
        val configured = when (default) {
            FLAGSHIP_MODEL -> settings.flagshipModel
            FAST_MODEL -> settings.fastModel
            FRONTIER_FALLBACK_MODEL -> settings.fallbackModel
            else -> ""
        }
        if (configured.isNotBlank()) return configured

        return if (configuredAiProvider() == "openrouter" && "/" !in default) {
            "openai/$default"
        } else {
            default
        }
    }
}
