package afield.graphics.three

import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

import afield.geometry.path.div
import kotlinx.serialization.Serializable

/**
 * Presentation overrides for a named part of a three-dimensional view.
 *
 * Colours are RGB in 0..1, and a part with no x-ray opacity of its own follows the viewer's slider.
 * None of it changes geometry, picking, assembly groups or review identity: the styles travel beside
 * the geometry rather than baked into it, so restyling a part leaves every note on it attached.
 */

/** One part's overrides, as the viewer receives them. */
@Serializable
data class ThreePartAppearance(
    /** Red, green and blue, each a finite value in 0..1; anything else is refused. */
    val color: List<Double>,
    /** Draw the part at its own colour, with neither shading nor tone mapping. */
    val unlit: Boolean = false,
    /** This part's x-ray opacity in 0..1, or null to follow the viewer's slider. */
    val xrayOpacity: Double? = null,
) {
    internal fun validate() {
        require(color.size == 3 && color.all { it.isFinite() && it in 0.0..1.0 }) { "Colour must contain three finite RGB values in 0..1" }
        require(xrayOpacity == null || xrayOpacity.isFinite() && xrayOpacity in 0.0..1.0) { "X-ray opacity must be in 0..1" }
    }
}

/**
 * What [styles] do to a payload's drawables: a solid vertex colour, and the style's `unlit` and `xrayOpacity`.
 *
 * The viewer does this itself (`partAppearancePayload` in `threejs_viewer.html`), since the server sends
 * the styles beside the geometry rather than baked into it. This is the definition it is held to, by
 * `ThreeGeometryWireTest`.
 */
internal fun applyPartAppearance(payload: ThreeGeometryPayload, styles: Map<String, ThreePartAppearance>): ThreeGeometryPayload =
    payload.copy(drawables = payload.drawables.map { drawable ->
        val style = styles[drawable.id] ?: return@map drawable
        style.validate()
        val vertices = drawable.geometry.attributes["position"]?.array?.size?.div(3) ?: return@map drawable
        drawable.copy(geometry = drawable.geometry.copy(attributes = drawable.geometry.attributes +
            ("color" to ThreeAttribute(itemSize = 3, array = FloatArray(vertices * 3) { style.color[it % 3].toFloat() }))),
            style = drawable.style.copy(unlit = style.unlit, xrayOpacity = style.xrayOpacity?.toFloat()))
    })
