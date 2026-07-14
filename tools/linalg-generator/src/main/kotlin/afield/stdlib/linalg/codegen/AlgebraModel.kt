package afield.stdlib.linalg.codegen

enum class Component(val sourceName: String) {
    X("x"),
    Y("y"),
    Z("z"),
    W("w"),
}

data class VectorType(
    val dimension: Int,
    val heapName: String,
    val readName: String,
    val arenaName: String,
) {
    init {
        require(dimension in 2..4)
    }

    val components: List<Component> = Component.entries.take(dimension)
    val factoryName: String = "vec$dimension"
}

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
) {
    init {
        when (result) {
            OperationResult.Destination -> require(componentExpressions.isNotEmpty())
            is OperationResult.Scalar -> require(componentExpressions.isEmpty())
        }
    }
}

data class AlgebraModel(
    val vectors: List<VectorType>,
    val operationsByDimension: Map<Int, List<Operation>>,
) {
    fun operations(type: VectorType): List<Operation> = operationsByDimension.getValue(type.dimension)
}

object FieldAlgebraModel {
    fun build(): AlgebraModel {
        val vectors = listOf(
            VectorType(2, "Vector2f", "Vector2fc", "ArenaVec2f"),
            VectorType(3, "Vector3f", "Vector3fc", "ArenaVec3f"),
            VectorType(4, "Vector4f", "Vector4fc", "ArenaVec4f"),
        )
        return AlgebraModel(vectors, vectors.associate { it.dimension to operations(it) })
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

        fun sum(expressions: List<Expr>): Expr = expressions.reduce { a, b -> a + b }

        val dot = sum(self.zip(other()).map { (a, b) -> a * b })
        val distanceSquared = sum(self.zip(other()).map { (a, b) -> (a - b) * (a - b) })
        val lengthSquared = sum(self.map { it * it })
        val inverseLength = Expr.Constant("1.0f") / Expr.Call("sqrt", listOf(lengthSquared))

        return buildList {
            add(vectorBinary("add", "+"))
            add(vectorBinary("sub", "-"))
            add(vectorBinary("mul", "*"))
            add(vectorBinary("div", "/"))
            add(scalarBinary("mul", "*"))
            add(scalarBinary("div", "/"))
            add(
                Operation(
                    name = "negate",
                    result = OperationResult.Destination,
                    componentExpressions = self.map { Expr.Constant("-1.0f") * it },
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
        }
    }
}
