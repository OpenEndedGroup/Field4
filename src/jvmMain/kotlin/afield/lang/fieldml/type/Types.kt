package afield.lang.fieldml.type

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

/** The type of an integer. */
data object TInt : Type

/** The type of a floating-point number. */
data object TFloat : Type

/** The type of a boolean. */
data object TBool : Type

/** The type of a string. */
data object TString : Type

/** The type with one value, which is what `()` has. */
data object TUnit : Type

/**
 * A unification variable, identified by the number inference gave it.
 *
 * The id is what a [Scheme] quantifies and restricts; the `'a` a reader sees is a name a
 * [TypePrinter] assigns for its own lifetime, not anything stored here.
 */
data class TVar(val id: Int) : Type

/**
 * A function from one argument to one result.
 *
 * Arrows take a single argument, matching the curried application the parser builds, so a function
 * of two arguments is `TFun(a, TFun(b, c))`.
 */
data class TFun(
    val from: Type,
    val to: Type,
) : Type

/** A record type: a field name for each of its fields' types. */
data class TRecord(
    val fields: Map<String, Type>,
) : Type

/** A tuple type, in the order the elements were written. */
data class TTuple(
    val elements: List<Type>,
) : Type

/** A list type, which is homogeneous: every element has the one `element` type. */
data class TList(
    val element: Type,
) : Type

/** A data type declared by a `type` declaration, applied to as many arguments as it takes. */
data class TAdt(
    val name: String,
    val arguments: List<Type>,
) : Type

/**
 * A JVM type reached over the host bridge.
 *
 * The `name` is what `FieldMLHostTypes` reflects on to answer what the type has on it, and what it
 * shortens for display.
 */
data class THost(
    val name: String,
) : Type

/**
 * A type together with the variables generalised over it, and any restriction on what they may
 * become.
 *
 * The restrictions are how the prelude gives one scheme to a function that would otherwise need one
 * per type: `abs`, `min`, `max` and `clamp` have a single numeric scheme each, and `dot`, `vlength`,
 * `normalize` and `mix` a single vector one.
 */
data class Scheme(
    /** The [TVar] ids generalised over `body`. An empty set is a type with nothing to instantiate. */
    val quantified: Set<Int>,
    /** The type being generalised. */
    val body: Type,
    /** Quantified ids that may only be instantiated to `Int` or `Float`. */
    val numeric: Set<Int> = emptySet(),
    /** Quantified ids that may only be instantiated to `Vec2`/`Vec3`/`Vec4`. */
    val vector: Set<Int> = emptySet(),
)

/**
 * Renders a [Type] back to the two forms a reader sees.
 *
 * A printer names unification variables `'a`, `'b`, … in order of first appearance and keeps those
 * names for its own lifetime, so two types printed by one printer agree about which variable is
 * which, while two printers each start again at `'a`. Print the types that should agree with each
 * other through the same instance.
 */
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

    /**
     * The fully parenthesised form, with brackets around every arrow.
     *
     * This is what diagnostics and a box's inline result use, where the shape of a type matters more
     * than how it reads. Use [printSignature] for anything shown as a signature.
     */
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
