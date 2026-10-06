package afield.graphics

import kotlinx.serialization.Serializable

/** Presentation overrides for a named part. Colours are RGB values in 0..1.
 * A null xrayOpacity follows the viewer slider; 1 keeps an important part opaque.
 * These settings do not change geometry, picking, assembly groups or review identity.
 */
@Serializable
data class ThreePartAppearance(val color: List<Double>, val unlit: Boolean = false, val xrayOpacity: Double? = null) {
    internal fun validate() {
        require(color.size == 3 && color.all { it.isFinite() && it in 0.0..1.0 }) { "Colour must contain three finite RGB values in 0..1" }
        require(xrayOpacity == null || xrayOpacity.isFinite() && xrayOpacity in 0.0..1.0) { "X-ray opacity must be in 0..1" }
    }
}

internal fun applyPartAppearance(payload: ThreeGeometryPayload, styles: Map<String, ThreePartAppearance>): ThreeGeometryPayload =
    payload.copy(drawables = payload.drawables.map { drawable ->
        val style = styles[drawable.id] ?: return@map drawable
        style.validate()
        val vertices = drawable.geometry.attributes["position"]?.array?.size?.div(3) ?: return@map drawable
        drawable.copy(geometry = drawable.geometry.copy(attributes = drawable.geometry.attributes +
            ("color" to ThreeAttribute(itemSize = 3, array = FloatArray(vertices * 3) { style.color[it % 3].toFloat() }))),
            style = drawable.style.copy(unlit = style.unlit, xrayOpacity = style.xrayOpacity?.toFloat()))
    })
