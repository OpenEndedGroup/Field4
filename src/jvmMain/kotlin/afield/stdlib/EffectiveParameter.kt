@file:afield.CompletionCategory("Workspace")

package afield.stdlib

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
