@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.ui.controls

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.audio.synth.*
/** A named finite scalar with explicit requested/effective values and units. */
class EffectiveParameter(
    val name: String,
    initial: Double,
    val minimum: Double,
    val maximum: Double,
    val units: String = "",
    val description: String = ""
) {
    init {
        require(name.isNotBlank())
        require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum)
        require(initial.isFinite())
    }
    @Volatile var requested: Double = initial
        set(value) { require(value.isFinite()); field = value }
    val effective: Double get() = requested.coerceIn(minimum, maximum)
    val clamped: Boolean get() = requested !in minimum..maximum
    fun snapshot(): ParameterValue {
        val value = requested
        return ParameterValue(name, value, value.coerceIn(minimum, maximum), minimum, maximum, units, description)
    }
}

data class ParameterValue(
    val name: String, val requested: Double, val effective: Double,
    val minimum: Double, val maximum: Double, val units: String, val description: String
)
