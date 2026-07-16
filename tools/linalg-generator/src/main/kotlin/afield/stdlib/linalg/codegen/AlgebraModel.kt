package afield.stdlib.linalg.codegen

enum class Component(val sourceName: String) {
    X("x"),
    Y("y"),
    Z("z"),
    W("w"),
}

/**
 * The scalar element type a vector is built from. The heap vector emitter lowers the same
 * operation model into 32-bit float and 64-bit double storage; only the literals, the Kotlin
 * type, the buffer accessors and the bit-fold for hashing differ.
 */
enum class ScalarKind(
    val kotlinType: String,
    val typeSuffix: String,
    val zero: String,
    val one: String,
    val minusOne: String,
    val numberConversion: String,
    val bufferType: String,
    val bufferPut: String,
    val byteSize: Int,
    val bits: Int,
) {
    FLOAT("Float", "f", "0.0f", "1.0f", "-1.0f", "toFloat", "FloatBuffer", "putFloat", 4, 32),
    DOUBLE("Double", "d", "0.0", "1.0", "-1.0", "toDouble", "DoubleBuffer", "putDouble", 8, 64);

    /** Reads a component's raw bits and folds them to an Int for `hashCode`. */
    fun hashComponent(reference: String): String =
        if (this == FLOAT) "$reference.toBits()" else "$reference.toBits().hashCode()"

    /** The read-interface name of a sibling vector of the same scalar (for cross-dimension constructors). */
    fun readName(dimension: Int): String = "Vector$dimension${typeSuffix}c"
}

data class VectorType(
    val dimension: Int,
    val heapName: String,
    val readName: String,
    val arenaName: String?,
    val scalar: ScalarKind = ScalarKind.FLOAT,
) {
    init {
        require(dimension in 2..4)
    }

    val components: List<Component> = Component.entries.take(dimension)
    val factoryName: String = "vec$dimension"
    val documentation: ApiDocumentation get() = VectorDocumentation.forDimension(dimension, scalar)
}

/** Human-facing documentation is model data shared by KDoc and generated reference pages. */
data class ApiDocumentation(
    val summary: String,
    val details: List<String> = emptyList(),
    val parameters: Map<String, String> = emptyMap(),
    val returns: String? = null,
)

sealed interface ValueSource {
    data object Self : ValueSource
    data class Parameter(val name: String) : ValueSource
}

sealed interface Expr {
    data class Read(val source: ValueSource, val component: Component) : Expr
    data class ScalarParameter(val name: String) : Expr
    data class Constant(val source: String) : Expr
    data class Binary(val left: Expr, val operator: String, val right: Expr) : Expr
    data class Call(val function: String, val arguments: List<Expr>) : Expr
}

operator fun Expr.plus(other: Expr): Expr = Expr.Binary(this, "+", other)
operator fun Expr.minus(other: Expr): Expr = Expr.Binary(this, "-", other)
operator fun Expr.times(other: Expr): Expr = Expr.Binary(this, "*", other)
operator fun Expr.div(other: Expr): Expr = Expr.Binary(this, "/", other)

sealed interface Parameter {
    val name: String
}

data class VectorParameter(override val name: String) : Parameter
data class ScalarParameter(override val name: String) : Parameter

sealed interface OperationResult {
    data object Destination : OperationResult
    data class Scalar(val expression: Expr) : OperationResult
}

data class Operation(
    val name: String,
    val vectorParameters: List<VectorParameter> = emptyList(),
    val scalarParameters: List<ScalarParameter> = emptyList(),
    val result: OperationResult,
    val componentExpressions: List<Expr> = emptyList(),
    /**
     * When true this operation is emitted only in the bulk backend. Used by the broadcast-vector
     * ops: applying one vector uniformly across a whole array is only a distinct affordance for the
     * flat kernels; in the single-element heap/arena/segment backends it would just duplicate the
     * ordinary per-element vector op it shares a name with.
     */
    val bulkOnly: Boolean = false,
) {
    val documentation: ApiDocumentation get() = OperationDocumentation.forOperation(this)

    init {
        when (result) {
            OperationResult.Destination -> require(componentExpressions.isNotEmpty())
            is OperationResult.Scalar -> require(componentExpressions.isEmpty())
        }
    }
}

data class AlgebraModel(
    /** Storage-backed vectors (heap, frame arena and segment views). Float only. */
    val vectors: List<VectorType>,
    /** Every vector that gets a heap implementation: the storage-backed set plus double variants. */
    val heapVectors: List<VectorType>,
    private val operationsByType: Map<VectorType, List<Operation>>,
) {
    fun operations(type: VectorType): List<Operation> = operationsByType.getValue(type)
}

private object VectorDocumentation {
    fun forDimension(dimension: Int, scalar: ScalarKind) = ApiDocumentation(
        summary = "A mutable ${dimension}D vector of ${scalar.bits}-bit floating-point components.",
        details = listOf(
            "Treat a vector as an arrow: its components describe a displacement, its length describes magnitude, and normalization keeps direction while making length one.",
            "Use the heap type for ordinary Field code and persistent state. Destination overloads avoid result allocation when a loop needs it.",
        ),
    )
}

private object OperationDocumentation {
    fun forOperation(operation: Operation): ApiDocumentation {
        val vectorArgument = operation.vectorParameters.isNotEmpty()
        return when (operation.name) {
            "add" -> doc(
                if (vectorArgument) "Adds another vector component by component." else "Adds a scalar to each component.",
                params = common("other" to "the vector to add", "scalar" to "the value to add"),
            )
            "sub" -> doc(
                if (vectorArgument) "Subtracts another vector component by component." else "Subtracts a scalar from each component.",
                params = common("other" to "the vector to subtract", "scalar" to "the value to subtract"),
            )
            "mul" -> doc(
                if (vectorArgument) "Multiplies corresponding components." else "Scales every component by a scalar.",
                details = if (vectorArgument) emptyList() else listOf("Scalar multiplication changes the arrow's length; a negative scalar also reverses its direction."),
                params = common("other" to "the component-wise multiplier", "scalar" to "the scale factor"),
            )
            "div" -> doc(
                if (vectorArgument) "Divides corresponding components." else "Divides every component by a scalar.",
                params = common("other" to "the component-wise divisor", "scalar" to "the divisor"),
            )
            "negate" -> doc("Reverses the vector by negating every component.")
            "absolute" -> doc("Replaces every component with its absolute value.")
            "min" -> doc(
                if (vectorArgument) "Selects the smaller value independently in each component." else "Caps every component at a scalar upper bound.",
                params = common("other" to "the other component values", "scalar" to "the upper bound applied to every component"),
            )
            "max" -> doc(
                if (vectorArgument) "Selects the larger value independently in each component." else "Raises every component to a scalar lower bound.",
                params = common("other" to "the other component values", "scalar" to "the lower bound applied to every component"),
            )
            "length" -> ApiDocumentation("Returns this vector's Euclidean length (magnitude).", returns = "the square root of the sum of the squared components")
            "distance" -> ApiDocumentation(
                summary = "Returns the straight-line distance to another vector.",
                parameters = mapOf("other" to "the point to measure to"),
                returns = "the Euclidean distance",
            )
            "addBroadcast" -> doc("Adds one vector to every element, broadcasting it across the whole range.")
            "subBroadcast" -> doc("Subtracts one vector from every element, broadcasting it across the whole range.")
            "mulBroadcast" -> doc("Scales every element per-axis by one vector, broadcasting it across the whole range.")
            "divBroadcast" -> doc("Divides every element per-axis by one vector, broadcasting it across the whole range.")
            "minBroadcast" -> doc("Caps every element against one vector's components (component-wise clamp-to-box ceiling).")
            "maxBroadcast" -> doc("Raises every element against one vector's components (component-wise clamp-to-box floor).")
            "lerpBroadcast" -> doc(
                "Interpolates every element toward one target point by t.",
                listOf("At t = 0 the elements are unchanged; at t = 1 they all reach the single target point."),
                common("t" to "the interpolation amount"),
            )
            "lerp" -> doc(
                "Linearly interpolates from this vector toward another.",
                listOf("At t = 0 the result is this vector; at t = 1 it is other. Values between zero and one trace the straight line between them."),
                common("other" to "the endpoint", "t" to "the interpolation amount"),
            )
            "fma" -> doc(
                "Computes this + other * scalar component by component.",
                listOf("This is the common position-plus-scaled-direction pattern used for rays, velocities, and Euler integration."),
                common("other" to "the vector to scale and add", "scalar" to "the scale factor"),
            )
            "dot" -> ApiDocumentation(
                "Measures how strongly two vectors point in the same direction.",
                listOf("Positive means broadly aligned, zero means perpendicular, and negative means opposed. For unit vectors the result is the cosine of the angle between them."),
                mapOf("other" to "the vector to compare"),
                "the sum of the products of corresponding components",
            )
            "lengthSquared" -> ApiDocumentation(
                "Returns the squared length of this vector.",
                listOf("Use this for comparisons when you do not need the actual distance; it avoids a square root."),
                returns = "the sum of the squared components",
            )
            "distanceSquared" -> ApiDocumentation(
                "Returns the squared straight-line distance to another vector.",
                listOf("Use this for nearest/farthest comparisons when you do not need the actual distance."),
                mapOf("other" to "the point to measure to"),
                "the squared Euclidean distance",
            )
            "normalize" -> doc(
                "Keeps the vector's direction while scaling its length to one.",
                listOf("A zero-length vector has no direction; normalizing it follows floating-point arithmetic and produces non-finite components. Check lengthSquared() first when zero is possible."),
            )
            "cross" -> doc(
                "Returns a vector perpendicular to this vector and another 3D vector.",
                listOf("The direction follows the right-hand rule. Its length is the area of the parallelogram spanned by the inputs, so parallel vectors produce zero."),
                common("other" to "the second 3D direction"),
            )
            else -> error("No documentation for vector operation ${operation.name}")
        }
    }

    private fun doc(
        summary: String,
        details: List<String> = emptyList(),
        params: Map<String, String> = common(),
    ) = ApiDocumentation(summary, details, params, "the destination vector")

    private fun common(vararg entries: Pair<String, String>): Map<String, String> = buildMap {
        putAll(entries)
        put("dest", "the vector that receives the result")
    }
}

object FieldAlgebraModel {
    fun build(): AlgebraModel {
        val vectors = listOf(
            VectorType(2, "Vector2f", "Vector2fc", "ArenaVec2f", ScalarKind.FLOAT),
            VectorType(3, "Vector3f", "Vector3fc", "ArenaVec3f", ScalarKind.FLOAT),
            VectorType(4, "Vector4f", "Vector4fc", "ArenaVec4f", ScalarKind.FLOAT),
        )
        val doubleVectors = listOf(
            VectorType(2, "Vector2d", "Vector2dc", arenaName = null, ScalarKind.DOUBLE),
            VectorType(3, "Vector3d", "Vector3dc", arenaName = null, ScalarKind.DOUBLE),
            VectorType(4, "Vector4d", "Vector4dc", arenaName = null, ScalarKind.DOUBLE),
        )
        val heapVectors = vectors + doubleVectors
        return AlgebraModel(vectors, heapVectors, heapVectors.associateWith { operations(it) })
    }

    private fun operations(type: VectorType): List<Operation> {
        val self = type.components.map { Expr.Read(ValueSource.Self, it) }
        fun other(name: String = "other") = type.components.map { Expr.Read(ValueSource.Parameter(name), it) }
        val scalar = Expr.ScalarParameter("scalar")
        val t = Expr.ScalarParameter("t")

        fun vectorBinary(name: String, operator: String) = Operation(
            name = name,
            vectorParameters = listOf(VectorParameter("other")),
            result = OperationResult.Destination,
            componentExpressions = self.zip(other()).map { (a, b) -> Expr.Binary(a, operator, b) },
        )

        fun scalarBinary(name: String, operator: String) = Operation(
            name = name,
            scalarParameters = listOf(ScalarParameter("scalar")),
            result = OperationResult.Destination,
            componentExpressions = self.map { Expr.Binary(it, operator, scalar) },
        )

        fun scalarCall(name: String, function: String) = Operation(
            name = name,
            scalarParameters = listOf(ScalarParameter("scalar")),
            result = OperationResult.Destination,
            componentExpressions = self.map { Expr.Call(function, listOf(it, scalar)) },
        )

        // One vector, broadcast uniformly across every element of a bulk array. Bulk-only: the
        // per-axis operand is passed as `dimension` scalars (rendered as a lane broadcast in the
        // SIMD body), so these read exactly like the ordinary vector op in every element.
        val broadcastComponents = type.components.map { Expr.ScalarParameter("other${it.sourceName.uppercase()}") }
        val broadcastParameters = type.components.map { ScalarParameter("other${it.sourceName.uppercase()}") }
        fun broadcastBinary(name: String, operator: String) = Operation(
            name = name,
            scalarParameters = broadcastParameters,
            result = OperationResult.Destination,
            componentExpressions = self.zip(broadcastComponents).map { (a, b) -> Expr.Binary(a, operator, b) },
            bulkOnly = true,
        )
        fun broadcastCall(name: String, function: String) = Operation(
            name = name,
            scalarParameters = broadcastParameters,
            result = OperationResult.Destination,
            componentExpressions = self.zip(broadcastComponents).map { (a, b) -> Expr.Call(function, listOf(a, b)) },
            bulkOnly = true,
        )

        fun sum(expressions: List<Expr>): Expr = expressions.reduce { a, b -> a + b }

        val dot = sum(self.zip(other()).map { (a, b) -> a * b })
        val distanceSquared = sum(self.zip(other()).map { (a, b) -> (a - b) * (a - b) })
        val lengthSquared = sum(self.map { it * it })
        val length = Expr.Call("sqrt", listOf(lengthSquared))
        val distance = Expr.Call("sqrt", listOf(distanceSquared))
        val inverseLength = Expr.Constant(type.scalar.one) / Expr.Call("sqrt", listOf(lengthSquared))

        return buildList {
            add(vectorBinary("add", "+"))
            add(vectorBinary("sub", "-"))
            add(vectorBinary("mul", "*"))
            add(vectorBinary("div", "/"))
            add(scalarBinary("add", "+"))
            add(scalarBinary("sub", "-"))
            add(scalarBinary("mul", "*"))
            add(scalarBinary("div", "/"))
            add(
                Operation(
                    name = "negate",
                    result = OperationResult.Destination,
                    componentExpressions = self.map { Expr.Constant(type.scalar.minusOne) * it },
                )
            )
            add(
                Operation(
                    name = "absolute",
                    result = OperationResult.Destination,
                    componentExpressions = self.map { Expr.Call("abs", listOf(it)) },
                )
            )
            add(
                Operation(
                    name = "min",
                    vectorParameters = listOf(VectorParameter("other")),
                    result = OperationResult.Destination,
                    componentExpressions = self.zip(other()).map { (a, b) -> Expr.Call("min", listOf(a, b)) },
                )
            )
            add(
                Operation(
                    name = "max",
                    vectorParameters = listOf(VectorParameter("other")),
                    result = OperationResult.Destination,
                    componentExpressions = self.zip(other()).map { (a, b) -> Expr.Call("max", listOf(a, b)) },
                )
            )
            add(scalarCall("min", "min"))
            add(scalarCall("max", "max"))
            add(
                Operation(
                    name = "lerp",
                    vectorParameters = listOf(VectorParameter("other")),
                    scalarParameters = listOf(ScalarParameter("t")),
                    result = OperationResult.Destination,
                    componentExpressions = self.zip(other()).map { (a, b) -> a + (b - a) * t },
                )
            )
            add(
                Operation(
                    name = "fma",
                    vectorParameters = listOf(VectorParameter("other")),
                    scalarParameters = listOf(ScalarParameter("scalar")),
                    result = OperationResult.Destination,
                    componentExpressions = self.zip(other()).map { (a, b) -> a + b * scalar },
                )
            )
            add(Operation("dot", listOf(VectorParameter("other")), result = OperationResult.Scalar(dot)))
            add(Operation("lengthSquared", result = OperationResult.Scalar(lengthSquared)))
            add(
                Operation(
                    "distanceSquared",
                    listOf(VectorParameter("other")),
                    result = OperationResult.Scalar(distanceSquared),
                )
            )
            add(Operation("length", result = OperationResult.Scalar(length)))
            add(Operation("distance", listOf(VectorParameter("other")), result = OperationResult.Scalar(distance)))
            add(
                Operation(
                    name = "normalize",
                    result = OperationResult.Destination,
                    componentExpressions = self.map { it * inverseLength },
                )
            )
            if (type.dimension == 3) {
                val b = other()
                add(
                    Operation(
                        name = "cross",
                        vectorParameters = listOf(VectorParameter("other")),
                        result = OperationResult.Destination,
                        componentExpressions = listOf(
                            self[1] * b[2] - self[2] * b[1],
                            self[2] * b[0] - self[0] * b[2],
                            self[0] * b[1] - self[1] * b[0],
                        ),
                    )
                )
            }

            // Bulk-only broadcast family: one uniform vector applied across a whole array
            // (uniform force fields / translate, per-axis scale, clamp-to-box, ease-to-point).
            add(broadcastBinary("addBroadcast", "+"))
            add(broadcastBinary("subBroadcast", "-"))
            add(broadcastBinary("mulBroadcast", "*"))
            add(broadcastBinary("divBroadcast", "/"))
            add(broadcastCall("minBroadcast", "min"))
            add(broadcastCall("maxBroadcast", "max"))
            add(
                Operation(
                    name = "lerpBroadcast",
                    scalarParameters = broadcastParameters + ScalarParameter("t"),
                    result = OperationResult.Destination,
                    componentExpressions = self.zip(broadcastComponents).map { (a, b) -> a + (b - a) * t },
                    bulkOnly = true,
                )
            )
        }
    }
}
