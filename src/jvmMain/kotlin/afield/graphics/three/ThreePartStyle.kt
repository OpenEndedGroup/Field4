package afield.graphics.three

import afield.math.linalg.Vec4
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How a part is drawn, set from Kotlin by part id, beside its geometry rather than baked into it.
 *
 * The viewer applies a style as a material on the part's existing geometry, so restyling a part sends
 * no geometry, and in a contact sheet each panel can style the same part differently
 * ([ThreeSceneBuilder.panel]'s `styles`): the pair a close-up is about in red, the rest muted.
 *
 * ```kotlin
 * three {
 *     for ((id, m) in pipes) mesh(m, id = id, panel = -1, wireframe = false)
 *     columns(2)
 *     panel(title = "a meets b", styles = mapOf("a" to ThreePartStyle.highlight(), "b" to ThreePartStyle.highlight(), "c" to ThreePartStyle.muted()))
 *     panel(title = "b meets c", styles = mapOf("b" to ThreePartStyle.highlight(), "c" to ThreePartStyle.highlight(), "a" to ThreePartStyle.muted()))
 * }
 * ```
 *
 * None of it changes picking, review identity or visibility. A colour from the stored part appearance
 * ([ThreeGeometryViewState.styleParts]) wins over this one, since a reader set it later.
 */
@Serializable
data class ThreePartStyle(
    /** Red, green and blue in 0..1 (linear, as vertex colours are), replacing the part's own colours; `null` keeps them. */
    val color: List<Double>? = null,
    /** The part's opacity in 0..1; `null` keeps its own, or the muted one for [ThreeEmphasis.MUTED]. */
    val opacity: Double? = null,
    val emphasis: ThreeEmphasis = ThreeEmphasis.NONE,
) {
    init {
        require(color == null || (color.size == 3 && color.all { it.isFinite() && it in 0.0..1.0 })) {
            "a part style's colour is three values in 0..1, got $color"
        }
        require(opacity == null || (opacity.isFinite() && opacity in 0.0..1.0)) { "a part style's opacity is in 0..1, got $opacity" }
    }

    companion object {
        /** The part in [color] (red by default), glowing a little, so it reads first. */
        fun highlight(color: Vec4 = Vec4(0.95f, 0.12f, 0.08f, 1f)) =
            ThreePartStyle(listOf(color.x.toDouble(), color.y.toDouble(), color.z.toDouble()), emphasis = ThreeEmphasis.HIGHLIGHT)

        /** The part greyed and see-through: still there for context, no longer the subject. */
        fun muted(opacity: Double = 0.18) = ThreePartStyle(opacity = opacity, emphasis = ThreeEmphasis.MUTED)

        /** A plain colour, and an opacity if given. */
        fun of(color: Vec4, opacity: Double? = null) =
            ThreePartStyle(listOf(color.x.toDouble(), color.y.toDouble(), color.z.toDouble()), opacity)
    }
}

/** What a [ThreePartStyle] does beyond colour and opacity. */
@Serializable
enum class ThreeEmphasis {
    /** Nothing more. */
    NONE,

    /** A glow in the part's colour, so it stands out from the parts around it. */
    HIGHLIGHT,

    /** Grey (unless a colour is given), see-through, and writing no depth, so what is behind it shows. */
    MUTED,
}

/**
 * Metadata a box hands to the viewer, as JSON: strings, numbers, booleans, `null`, lists and maps of them
 * (and [JsonElement]s, as they are). Anything else is refused, naming [where].
 */
internal fun threeMetadataJson(value: Any?, where: String): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> {
        require(value.toDouble().isFinite()) { "$where holds $value, which JSON cannot carry" }
        JsonPrimitive(value)
    }
    is Enum<*> -> JsonPrimitive(value.name)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
        require(k is String) { "$where: metadata keys are strings, got $k" }
        k to threeMetadataJson(v, "$where.$k")
    })
    is Iterable<*> -> JsonArray(value.mapIndexed { i, v -> threeMetadataJson(v, "$where[$i]") })
    is Array<*> -> JsonArray(value.mapIndexed { i, v -> threeMetadataJson(v, "$where[$i]") })
    is DoubleArray -> threeMetadataJson(value.toList(), where)
    is FloatArray -> threeMetadataJson(value.toList(), where)
    is IntArray -> threeMetadataJson(value.toList(), where)
    else -> throw IllegalArgumentException("$where holds a ${value::class.simpleName}; metadata is strings, numbers, booleans, lists and maps")
}

internal fun threeMetadataObject(values: Map<String, Any?>, where: String): JsonObject? =
    if (values.isEmpty()) null else threeMetadataJson(values, where) as JsonObject
