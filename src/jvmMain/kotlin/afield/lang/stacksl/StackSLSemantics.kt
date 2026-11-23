package afield.lang.stacksl

/** Compile-time meanings of StackSL's vec4 values. */
sealed interface StackSLSemanticType {
    data object RawVec4 : StackSLSemanticType
    data object RealInvariant : StackSLSemanticType

    /** The xy lanes transform by multiplication by exp(i * weight * angle). */
    data class ComplexSpin(val weight: Int) : StackSLSemanticType {
        override fun toString(): String = "ComplexSpin($weight)"
    }
}

/** A contract on the host-supplied coordinate read by [source]. */
data class StackSLInputAssumption(val source: String, val type: StackSLSemanticType)

/** SO(2) analysis of the root result, conditional on the listed input contracts. */
data class StackSLSemanticAnalysis(
    val outputType: StackSLSemanticType,
    val assumptions: List<StackSLInputAssumption>,
    val uncertifiedReasons: List<String>
) {
    val equivarianceCertified: Boolean get() = uncertifiedReasons.isEmpty()
}
