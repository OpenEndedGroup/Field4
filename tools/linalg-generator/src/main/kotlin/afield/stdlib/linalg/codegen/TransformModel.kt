package afield.stdlib.linalg.codegen

data class AlgebraShape(
    val name: String,
    val readType: String,
    val mutableType: String,
    val components: List<String>,
) {
    init {
        require(components.isNotEmpty())
        require(components.distinct().size == components.size) { "duplicate components in $name" }
    }
}

sealed interface TransformParameter { val name: String }
data class ShapeParameter(override val name: String, val shape: AlgebraShape) : TransformParameter
data class TransformScalarParameter(override val name: String) : TransformParameter

sealed interface ScalarExpr {
    data class Read(val source: String, val component: String) : ScalarExpr
    data class Parameter(val name: String) : ScalarExpr
    data class Local(val name: String) : ScalarExpr
    data class Constant(val source: String) : ScalarExpr
    data class Unary(val operator: String, val value: ScalarExpr) : ScalarExpr
    data class Binary(val left: ScalarExpr, val operator: String, val right: ScalarExpr) : ScalarExpr
    data class Call(val function: String, val arguments: List<ScalarExpr>) : ScalarExpr
}

private fun number(value: Float): String = when (value) {
    0f -> "0f"
    1f -> "1f"
    2f -> "2f"
    -1f -> "-1f"
    else -> "${value}f"
}

fun scalar(value: Float): ScalarExpr = ScalarExpr.Constant(number(value))
fun read(source: String, component: String): ScalarExpr = ScalarExpr.Read(source, component)
fun parameter(name: String): ScalarExpr = ScalarExpr.Parameter(name)
fun local(name: String): ScalarExpr = ScalarExpr.Local(name)
operator fun ScalarExpr.plus(other: ScalarExpr): ScalarExpr = ScalarExpr.Binary(this, "+", other)
operator fun ScalarExpr.minus(other: ScalarExpr): ScalarExpr = ScalarExpr.Binary(this, "-", other)
operator fun ScalarExpr.times(other: ScalarExpr): ScalarExpr = ScalarExpr.Binary(this, "*", other)
operator fun ScalarExpr.div(other: ScalarExpr): ScalarExpr = ScalarExpr.Binary(this, "/", other)
operator fun ScalarExpr.unaryMinus(): ScalarExpr = ScalarExpr.Unary("-", this)
fun call(name: String, vararg arguments: ScalarExpr): ScalarExpr = ScalarExpr.Call(name, arguments.toList())

data class TransformLocal(val name: String, val expression: ScalarExpr)

sealed interface TransformResult {
    data class Destination(val shape: AlgebraShape, val components: List<ScalarExpr>) : TransformResult
    data class Scalar(val expression: ScalarExpr) : TransformResult
}

data class TransformOperation(
    val functionName: String,
    val receiver: AlgebraShape,
    val parameters: List<TransformParameter> = emptyList(),
    val locals: List<TransformLocal> = emptyList(),
    val result: TransformResult,
)

data class TransformAlgebraModel(val operations: List<TransformOperation>) {
    init {
        require(operations.map { it.functionName }.distinct().size == operations.size) {
            "transform operation names must be unique"
        }
        operations.forEach(::validate)
    }

    private fun validate(operation: TransformOperation) {
        val shapeSources = buildMap {
            put("self", operation.receiver)
            operation.parameters.filterIsInstance<ShapeParameter>().forEach { put(it.name, it.shape) }
        }
        val scalarParameters = operation.parameters.filterIsInstance<TransformScalarParameter>().mapTo(mutableSetOf()) { it.name }
        val locals = mutableSetOf<String>()
        fun validateExpression(expression: ScalarExpr) {
            when (expression) {
                is ScalarExpr.Read -> {
                    val shape = requireNotNull(shapeSources[expression.source]) {
                        "${operation.functionName}: unknown shape source ${expression.source}"
                    }
                    require(expression.component in shape.components) {
                        "${operation.functionName}: ${expression.component} is not a component of ${shape.name}"
                    }
                }
                is ScalarExpr.Parameter -> require(expression.name in scalarParameters) {
                    "${operation.functionName}: unknown scalar parameter ${expression.name}"
                }
                is ScalarExpr.Local -> require(expression.name in locals) {
                    "${operation.functionName}: local ${expression.name} used before definition"
                }
                is ScalarExpr.Constant -> require(expression.source.isNotBlank())
                is ScalarExpr.Unary -> validateExpression(expression.value)
                is ScalarExpr.Binary -> { validateExpression(expression.left); validateExpression(expression.right) }
                is ScalarExpr.Call -> expression.arguments.forEach(::validateExpression)
            }
        }
        operation.locals.forEach { value ->
            require(value.name !in locals && value.name !in scalarParameters && value.name !in shapeSources)
            validateExpression(value.expression)
            locals += value.name
        }
        when (val result = operation.result) {
            is TransformResult.Destination -> {
                require(result.components.size == result.shape.components.size) {
                    "${operation.functionName}: expected ${result.shape.components.size} destination components"
                }
                result.components.forEach(::validateExpression)
            }
            is TransformResult.Scalar -> validateExpression(result.expression)
        }
    }
}

object FieldTransformAlgebraModel {
    val vec3 = AlgebraShape("vec3", "Vector3fc", "Vector3f", listOf("x", "y", "z"))
    val vec4 = AlgebraShape("vec4", "Vector4fc", "Vector4f", listOf("x", "y", "z", "w"))
    val quaternion = AlgebraShape("quaternion", "Quaternionfc", "Quaternionf", listOf("x", "y", "z", "w"))
    val matrix3 = AlgebraShape("matrix3", "Matrix3fc", "Matrix3f", matrixComponents(3))
    val matrix4 = AlgebraShape("matrix4", "Matrix4fc", "Matrix4f", matrixComponents(4))

    fun build(): TransformAlgebraModel = TransformAlgebraModel(buildList {
        add(quaternionTransform())
        add(dot("dotQuaternion", quaternion))
        add(normalize("normalizeQuaternion", quaternion))
        add(destination("conjugateQuaternion", quaternion, outputs = listOf(-q("x"), -q("y"), -q("z"), q("w"))))
        add(invertQuaternion())
        add(multiplyQuaternion())

        add(transformMatrix3Vector())
        add(determinantMatrix3())
        add(multiplyMatrix(3, matrix3))
        add(invertMatrix3())
        add(transposeMatrix(3, matrix3))

        add(transformMatrix4Position())
        add(transformMatrix4Direction())
        add(transformMatrix4Vector())
        add(determinantMatrix4())
        add(invertMatrix4())
        add(transposeMatrix(4, matrix4))
        add(multiplyMatrix(4, matrix4))
        add(translateMatrix4())
        add(scaleMatrix4())
    })

    private fun matrixComponents(size: Int): List<String> = buildList {
        for (column in 0 until size) for (row in 0 until size) add("m$column$row")
    }

    private fun q(component: String) = read("self", component)
    private fun m3(column: Int, row: Int) = read("self", "m$column$row")
    private fun m4(column: Int, row: Int) = read("self", "m$column$row")

    private fun destination(
        name: String,
        receiver: AlgebraShape,
        parameters: List<TransformParameter> = emptyList(),
        locals: List<TransformLocal> = emptyList(),
        shape: AlgebraShape = receiver,
        outputs: List<ScalarExpr>,
    ) = TransformOperation(name, receiver, parameters, locals, TransformResult.Destination(shape, outputs))

    private fun dot(name: String, shape: AlgebraShape): TransformOperation {
        val other = ShapeParameter("other", shape)
        val terms = shape.components.map { read("self", it) * read("other", it) }
        return TransformOperation(name, shape, listOf(other), result = TransformResult.Scalar(sum(terms)))
    }

    private fun normalize(name: String, shape: AlgebraShape): TransformOperation {
        val squared = sum(shape.components.map { read("self", it) * read("self", it) })
        val inv = TransformLocal("inverseLength", scalar(1f) / call("sqrt", squared))
        return destination(name, shape, locals = listOf(inv), outputs = shape.components.map { read("self", it) * local("inverseLength") })
    }

    private fun quaternionTransform(): TransformOperation {
        val value = ShapeParameter("value", vec3)
        val vx = read("value", "x"); val vy = read("value", "y"); val vz = read("value", "z")
        val qx = q("x"); val qy = q("y"); val qz = q("z"); val qw = q("w")
        val locals = listOf(
            TransformLocal("tx", scalar(2f) * (qy * vz - qz * vy)),
            TransformLocal("ty", scalar(2f) * (qz * vx - qx * vz)),
            TransformLocal("tz", scalar(2f) * (qx * vy - qy * vx)),
        )
        return destination(
            "transformQuaternionVector3", quaternion, listOf(value), locals, vec3,
            listOf(
                vx + qw * local("tx") + qy * local("tz") - qz * local("ty"),
                vy + qw * local("ty") + qz * local("tx") - qx * local("tz"),
                vz + qw * local("tz") + qx * local("ty") - qy * local("tx"),
            )
        )
    }

    private fun invertQuaternion(): TransformOperation {
        val inverse = TransformLocal("inverseNorm", scalar(1f) / sum(quaternion.components.map { q(it) * q(it) }))
        return destination(
            "invertQuaternion", quaternion, locals = listOf(inverse),
            outputs = listOf(-q("x") * local("inverseNorm"), -q("y") * local("inverseNorm"), -q("z") * local("inverseNorm"), q("w") * local("inverseNorm")),
        )
    }

    private fun multiplyQuaternion(): TransformOperation {
        val right = ShapeParameter("right", quaternion)
        val lx = q("x"); val ly = q("y"); val lz = q("z"); val lw = q("w")
        val rx = read("right", "x"); val ry = read("right", "y"); val rz = read("right", "z"); val rw = read("right", "w")
        return destination(
            "multiplyQuaternion", quaternion, listOf(right), outputs = listOf(
                lw * rx + lx * rw + ly * rz - lz * ry,
                lw * ry - lx * rz + ly * rw + lz * rx,
                lw * rz + lx * ry - ly * rx + lz * rw,
                lw * rw - lx * rx - ly * ry - lz * rz,
            )
        )
    }

    private fun transformMatrix3Vector(): TransformOperation {
        val value = ShapeParameter("value", vec3)
        return destination(
            "transformMatrix3Vector3", matrix3, listOf(value), shape = vec3,
            outputs = (0..2).map { row -> sum((0..2).map { column -> m3(column, row) * read("value", vec3.components[column]) }) },
        )
    }

    private fun determinantMatrix3(): TransformOperation {
        val determinant = m3(0, 0) * (m3(1, 1) * m3(2, 2) - m3(2, 1) * m3(1, 2)) -
            m3(1, 0) * (m3(0, 1) * m3(2, 2) - m3(2, 1) * m3(0, 2)) +
            m3(2, 0) * (m3(0, 1) * m3(1, 2) - m3(1, 1) * m3(0, 2))
        return TransformOperation("determinantMatrix3", matrix3, result = TransformResult.Scalar(determinant))
    }

    private fun multiplyMatrix(size: Int, shape: AlgebraShape): TransformOperation {
        val right = ShapeParameter("right", shape)
        val outputs = buildList {
            for (column in 0 until size) for (row in 0 until size) {
                add(sum((0 until size).map { k -> read("self", "m$k$row") * read("right", "m$column$k") }))
            }
        }
        return destination("multiplyMatrix$size", shape, listOf(right), outputs = outputs)
    }

    private fun invertMatrix3(): TransformOperation {
        val d = TransformLocal("inverseDeterminant", scalar(1f) / local("determinant"))
        val determinant = TransformLocal("determinant", determinantMatrix3().let { (it.result as TransformResult.Scalar).expression })
        val inv = local("inverseDeterminant")
        return destination(
            "invertMatrix3", matrix3, locals = listOf(determinant, d), outputs = listOf(
                (m3(1,1)*m3(2,2)-m3(2,1)*m3(1,2))*inv,
                (m3(2,1)*m3(0,2)-m3(0,1)*m3(2,2))*inv,
                (m3(0,1)*m3(1,2)-m3(1,1)*m3(0,2))*inv,
                (m3(2,0)*m3(1,2)-m3(1,0)*m3(2,2))*inv,
                (m3(0,0)*m3(2,2)-m3(2,0)*m3(0,2))*inv,
                (m3(1,0)*m3(0,2)-m3(0,0)*m3(1,2))*inv,
                (m3(1,0)*m3(2,1)-m3(2,0)*m3(1,1))*inv,
                (m3(2,0)*m3(0,1)-m3(0,0)*m3(2,1))*inv,
                (m3(0,0)*m3(1,1)-m3(1,0)*m3(0,1))*inv,
            ),
        )
    }

    private fun transposeMatrix(size: Int, shape: AlgebraShape): TransformOperation = destination(
        "transposeMatrix$size", shape,
        outputs = buildList { for (column in 0 until size) for (row in 0 until size) add(read("self", "m$row$column")) },
    )

    private fun transformMatrix4Position(): TransformOperation {
        val value = ShapeParameter("value", vec3)
        return destination(
            "transformMatrix4Position", matrix4, listOf(value), shape = vec3,
            outputs = (0..2).map { row -> sum((0..2).map { column -> m4(column, row) * read("value", vec3.components[column]) }) + m4(3, row) },
        )
    }

    private fun transformMatrix4Direction(): TransformOperation {
        val value = ShapeParameter("value", vec3)
        return destination(
            "transformMatrix4Direction", matrix4, listOf(value), shape = vec3,
            outputs = (0..2).map { row -> sum((0..2).map { column -> m4(column, row) * read("value", vec3.components[column]) }) },
        )
    }

    private fun transformMatrix4Vector(): TransformOperation {
        val value = ShapeParameter("value", vec4)
        return destination(
            "transformMatrix4Vector4", matrix4, listOf(value), shape = vec4,
            outputs = (0..3).map { row -> sum((0..3).map { column -> m4(column, row) * read("value", vec4.components[column]) }) },
        )
    }

    private fun determinantMatrix4(): TransformOperation {
        val b = matrix4CofactorLocals()
        return TransformOperation("determinantMatrix4", matrix4, locals = b, result = TransformResult.Scalar(matrix4DeterminantExpr()))
    }

    private fun matrix4CofactorLocals(): List<TransformLocal> = listOf(
            TransformLocal("b00", m4(0,0)*m4(1,1)-m4(0,1)*m4(1,0)),
            TransformLocal("b01", m4(0,0)*m4(1,2)-m4(0,2)*m4(1,0)),
            TransformLocal("b02", m4(0,0)*m4(1,3)-m4(0,3)*m4(1,0)),
            TransformLocal("b03", m4(0,1)*m4(1,2)-m4(0,2)*m4(1,1)),
            TransformLocal("b04", m4(0,1)*m4(1,3)-m4(0,3)*m4(1,1)),
            TransformLocal("b05", m4(0,2)*m4(1,3)-m4(0,3)*m4(1,2)),
            TransformLocal("b06", m4(2,0)*m4(3,1)-m4(2,1)*m4(3,0)),
            TransformLocal("b07", m4(2,0)*m4(3,2)-m4(2,2)*m4(3,0)),
            TransformLocal("b08", m4(2,0)*m4(3,3)-m4(2,3)*m4(3,0)),
            TransformLocal("b09", m4(2,1)*m4(3,2)-m4(2,2)*m4(3,1)),
            TransformLocal("b10", m4(2,1)*m4(3,3)-m4(2,3)*m4(3,1)),
            TransformLocal("b11", m4(2,2)*m4(3,3)-m4(2,3)*m4(3,2)),
        )

    private fun matrix4DeterminantExpr(): ScalarExpr =
        local("b00")*local("b11") - local("b01")*local("b10") + local("b02")*local("b09") +
            local("b03")*local("b08") - local("b04")*local("b07") + local("b05")*local("b06")

    private fun invertMatrix4(): TransformOperation {
        val locals = matrix4CofactorLocals() + TransformLocal("inverseDeterminant", scalar(1f) / matrix4DeterminantExpr())
        val d = local("inverseDeterminant")
        return destination(
            "invertMatrix4", matrix4, locals = locals, outputs = listOf(
                (m4(1,1)*local("b11") - m4(1,2)*local("b10") + m4(1,3)*local("b09"))*d,
                (m4(0,2)*local("b10") - m4(0,1)*local("b11") - m4(0,3)*local("b09"))*d,
                (m4(3,1)*local("b05") - m4(3,2)*local("b04") + m4(3,3)*local("b03"))*d,
                (m4(2,2)*local("b04") - m4(2,1)*local("b05") - m4(2,3)*local("b03"))*d,
                (m4(1,2)*local("b08") - m4(1,0)*local("b11") - m4(1,3)*local("b07"))*d,
                (m4(0,0)*local("b11") - m4(0,2)*local("b08") + m4(0,3)*local("b07"))*d,
                (m4(3,2)*local("b02") - m4(3,0)*local("b05") - m4(3,3)*local("b01"))*d,
                (m4(2,0)*local("b05") - m4(2,2)*local("b02") + m4(2,3)*local("b01"))*d,
                (m4(1,0)*local("b10") - m4(1,1)*local("b08") + m4(1,3)*local("b06"))*d,
                (m4(0,1)*local("b08") - m4(0,0)*local("b10") - m4(0,3)*local("b06"))*d,
                (m4(3,0)*local("b04") - m4(3,1)*local("b02") + m4(3,3)*local("b00"))*d,
                (m4(2,1)*local("b02") - m4(2,0)*local("b04") - m4(2,3)*local("b00"))*d,
                (m4(1,1)*local("b07") - m4(1,0)*local("b09") - m4(1,2)*local("b06"))*d,
                (m4(0,0)*local("b09") - m4(0,1)*local("b07") + m4(0,2)*local("b06"))*d,
                (m4(3,1)*local("b01") - m4(3,0)*local("b03") - m4(3,2)*local("b00"))*d,
                (m4(2,0)*local("b03") - m4(2,1)*local("b01") + m4(2,2)*local("b00"))*d,
            )
        )
    }

    private fun translateMatrix4(): TransformOperation {
        val parameters = listOf(TransformScalarParameter("x"), TransformScalarParameter("y"), TransformScalarParameter("z"))
        val x = parameter("x"); val y = parameter("y"); val z = parameter("z")
        val outputs = buildList {
            for (column in 0..2) for (row in 0..3) add(m4(column, row))
            for (row in 0..3) add(m4(0,row)*x + m4(1,row)*y + m4(2,row)*z + m4(3,row))
        }
        return destination("translateMatrix4", matrix4, parameters, outputs = outputs)
    }

    private fun scaleMatrix4(): TransformOperation {
        val parameters = listOf(TransformScalarParameter("x"), TransformScalarParameter("y"), TransformScalarParameter("z"))
        val scales = listOf(parameter("x"), parameter("y"), parameter("z"), scalar(1f))
        return destination(
            "scaleMatrix4", matrix4, parameters,
            outputs = buildList { for (column in 0..3) for (row in 0..3) add(m4(column,row) * scales[column]) },
        )
    }

    private fun sum(expressions: List<ScalarExpr>): ScalarExpr = expressions.reduce(ScalarExpr::plus)
}
