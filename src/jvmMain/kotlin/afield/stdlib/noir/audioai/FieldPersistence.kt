@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import afield.Box
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * How a Field document remembers audio work: ids and judgements in a box property, and not one
 * sample of audio.
 *
 * The rule this file enforces is that Python is a worker and Field is the authority. The service
 * owns immutable, content-addressed assets; the document owns which of them it refers to and what
 * you thought of them. That is why saved state is a [FieldAudioProjectState] of ids rather than
 * audio, and why reopening a document and re-fetching those ids gets the same bytes back.
 */

/** Persist only protocol data and asset IDs; Python remains a worker, never project authority. */
object FieldAudioPersistence {
    /** The box property the project state lives in unless you name another one. */
    const val DEFAULT_PROPERTY = "audioAIProject"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    /**
     * Register [property] as persistent and JSON-sidecar-separated on this document.
     *
     * Idempotent, and called for you by [load] and [save], so you rarely need it directly. The
     * sidecar registration is what keeps a large project state out of the document file itself and
     * makes its diffs readable.
     */
    fun install(property: String = DEFAULT_PROPERTY) {
        Box.ioPersistentProperties.add(property)
        Box.ioSeparateProperties.putIfAbsent(property, "json")
    }

    /**
     * Read the saved state out of [box], or an empty [FieldAudioProjectState] if there is none yet.
     *
     * Decodes with `ignoreUnknownKeys`, so a state written by a newer Field still loads.
     */
    fun load(box: Box, property: String = DEFAULT_PROPERTY): FieldAudioProjectState {
        install(property)
        val encoded = box.properties[property] as? String ?: return FieldAudioProjectState()
        return json.decodeFromString(encoded)
    }

    /**
     * Write [state] into [box] as pretty-printed JSON, replacing whatever was there.
     *
     * Only ids and judgements are written, never audio; see [SavedAudioOperation].
     */
    fun save(box: Box, state: FieldAudioProjectState, property: String = DEFAULT_PROPERTY) {
        install(property)
        box.properties[property] = json.encodeToString(state)
    }
}
