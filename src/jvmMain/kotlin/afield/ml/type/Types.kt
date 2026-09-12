package afield.ml.type

/**
 * The types FieldML infers, and the two ways one is printed back to a reader.
 *
 * [Type] is the vocabulary inference works in: five ground types, a unification variable [TVar], an
 * arrow [TFun], the structural cases [TRecord], [TTuple] and [TList], [TAdt] for a declared data
 * type applied to its arguments, and [THost] for a JVM type reached over the host bridge, whose
 * `name` is what `FieldMLHostTypes` reflects on. [TFun] takes one argument at a time, matching the
 * curried application the parser builds, so a function of two arguments is `TFun(a, TFun(b, c))`.
 *
 * A [Scheme] is a type together with the variable ids generalised over it, and it can restrict what
 * those ids are allowed to become. An id listed in `numeric` only unifies with `Int` or `Float`, and
 * one listed in `vector` only with `Vec2`, `Vec3` or `Vec4`. That is how the prelude gives `abs`,
 * `min`, `max` and `clamp` one scheme each rather than one per numeric type, and `dot`, `vlength`,
 * `normalize` and `mix` one each rather than one per vector width.
 *
 * [TypePrinter] renders a type two ways. `print` parenthesises every arrow, and is what diagnostics
 * and a box's inline result use; `printSignature` drops the parentheses that right associativity
 * makes redundant, and is what completion shows and what the cheatsheet's coverage guard compares
 * against. A printer names unification variables `'a`, `'b`, … in order of first appearance and
 * keeps those names for its own lifetime, so two types printed by one printer agree about which
 * variable is which, while two printers each start again at `'a`.
 */

/** An inferred FieldML type. */
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
    /** Quantified ids that may only be instantiated to `Vec2`/`Vec3`/`Vec4`. */
    val vector: Set<Int> = emptySet(),
)

class TypePrinter {
    private val names = mutableMapOf<Int, String>()

    /**
     * Like [print], but without the parentheses that [print] puts around every arrow. Since `->` is
     * right associative, a curried type only needs them when a function is an *argument*:
     * `Float -> Float -> Vec2` rather than `(Float -> (Float -> Vec2))`, but still
     * `(Int -> 'a) -> Int -> ['a]`. This is the form to show a reader — signatures in the cheatsheet
     * and in completion — while [print] stays as it is for inline results and diagnostics.
     */
    fun printSignature(type: Type): String =
        when (type) {
            is TFun -> {
                val from = printSignature(type.from)
                val wrapped = if (type.from is TFun) "($from)" else from
                "$wrapped -> ${printSignature(type.to)}"
            }
            is TRecord -> type.fields.entries.joinToString(prefix = "{", postfix = "}") { (name, fieldType) ->
                "$name: ${printSignature(fieldType)}"
            }
            is TTuple -> type.elements.joinToString(prefix = "(", postfix = ")") { printSignature(it) }
            is TList -> "[${printSignature(type.element)}]"
            is TAdt -> if (type.arguments.isEmpty()) {
                type.name
            } else {
                "${type.name} ${type.arguments.joinToString(" ") { printSignature(it) }}"
            }
            else -> print(type)
        }

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
            is THost -> FieldMLHostTypes.display(type.name)
        }
}
