package afield.stdlib.noir.audioai

import afield.Box
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persist only protocol data and asset IDs; Python remains a worker, never project authority. */
object FieldAudioPersistence {
    const val DEFAULT_PROPERTY = "audioAIProject"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun install(property: String = DEFAULT_PROPERTY) {
        Box.ioPersistentProperties.add(property)
        Box.ioSeparateProperties.putIfAbsent(property, "json")
    }

    fun load(box: Box, property: String = DEFAULT_PROPERTY): FieldAudioProjectState {
        install(property)
        val encoded = box.properties[property] as? String ?: return FieldAudioProjectState()
        return json.decodeFromString(encoded)
    }

    fun save(box: Box, state: FieldAudioProjectState, property: String = DEFAULT_PROPERTY) {
        install(property)
        box.properties[property] = json.encodeToString(state)
    }
}
