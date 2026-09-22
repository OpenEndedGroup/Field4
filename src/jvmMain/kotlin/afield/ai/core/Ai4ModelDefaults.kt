package afield.ai.core

internal object Ai4ModelDefaults {
    const val FLAGSHIP_MODEL = "gpt-6-astra"
    const val FRONTIER_FALLBACK_MODEL = "gpt-5.4"
    const val FAST_MODEL = "gpt-6-luna"

    // Claude defaults, used when the Anthropic provider is selected and no model
    // has been configured explicitly (env or ai-models.properties).
    const val ANTHROPIC_FLAGSHIP_MODEL = "claude-opus-4-8"
    const val ANTHROPIC_FALLBACK_MODEL = "claude-sonnet-5"
    const val ANTHROPIC_FAST_MODEL = "claude-haiku-4-5"

    fun flagshipModel(): String = envOrDefault("AFIELD_AI_MODEL", FLAGSHIP_MODEL)

    fun fastModel(): String = envOrDefault("AFIELD_AI_FAST_MODEL", FAST_MODEL)

    fun fallbackModel(): String = envOrDefault("AFIELD_AI_FALLBACK_MODEL", FRONTIER_FALLBACK_MODEL)

    fun envOrDefault(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: configuredModelForDefault(default)

    internal fun configuredModelForDefault(
        default: String,
        settings: AiModelSettings = configuredAiModelSettings(),
        provider: String = configuredAiProvider()
    ): String {
        val configured = when (default) {
            FLAGSHIP_MODEL -> settings.flagshipModel
            FAST_MODEL -> settings.fastModel
            FRONTIER_FALLBACK_MODEL -> settings.fallbackModel
            else -> ""
        }
        if (configured.isNotBlank()) return configured

        return when (provider) {
            "anthropic" -> when (default) {
                FLAGSHIP_MODEL -> ANTHROPIC_FLAGSHIP_MODEL
                FAST_MODEL -> ANTHROPIC_FAST_MODEL
                FRONTIER_FALLBACK_MODEL -> ANTHROPIC_FALLBACK_MODEL
                else -> default
            }
            // The Astra migration is for the direct OpenAI path. Router access is
            // independently configured; retain its established default until evaluated.
            "openrouter" -> if (default == FLAGSHIP_MODEL) "openai/gpt-5.6-sol"
                else if ("/" !in default) "openai/$default" else default
            else -> default
        }
    }
}
