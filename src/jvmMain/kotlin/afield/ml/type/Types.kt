package afield.ml.type

sealed interface Type

data object TInt : Type
data object TFloat : Type
data object TBool : Type
data object TString : Type
data object TUnit : Type

data class TVar(val id: Int) : Type

data class TFun(
    val from: Type,
    val to: Type,
) : Type

data class TRecord(
    val fields: Map<String, Type>,
) : Type

data class TTuple(
    val elements: List<Type>,
) : Type

data class TList(
    val element: Type,
) : Type

data class TAdt(
    val name: String,
    val arguments: List<Type>,
) : Type

data class THost(
    val name: String,
) : Type

data class Scheme(
    val quantified: Set<Int>,
    val body: Type,
    val numeric: Set<Int> = emptySet(),
)

class TypePrinter {
    private val names = mutableMapOf<Int, String>()

    fun print(type: Type): String =
        when (type) {
            TInt -> "Int"
            TFloat -> "Float"
            TBool -> "Bool"
            TString -> "String"
            TUnit -> "Unit"
            is TVar -> names.getOrPut(type.id) { "'${('a'.code + names.size).toChar()}" }
            is TFun -> "(${print(type.from)} -> ${print(type.to)})"
            is TRecord -> type.fields.entries.joinToString(prefix = "{", postfix = "}") { (name, fieldType) ->
                "$name: ${print(fieldType)}"
            }
            is TTuple -> type.elements.joinToString(prefix = "(", postfix = ")") { print(it) }
            is TList -> "[${print(type.element)}]"
            is TAdt -> if (type.arguments.isEmpty()) {
                type.name
            } else {
                "${type.name} ${type.arguments.joinToString(" ") { print(it) }}"
            }
            is THost -> type.name
        }
}
