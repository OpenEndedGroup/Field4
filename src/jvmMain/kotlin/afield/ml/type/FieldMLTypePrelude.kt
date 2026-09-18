package afield.ml.type

/**
 * The type of every name a FieldML program has before it declares anything: the prelude, as the
 * type checker sees it.
 *
 * [schemes] is the root environment `Inferencer` starts from, so a name here is in scope in every
 * FieldML box. Each entry is a [Scheme] rather than a bare type, because most of the prelude is
 * polymorphic and a bare type cannot say *how* polymorphic: `map` works over any element type,
 * `abs` over `Int` or `Float` but not over a string, `dot` over two vectors as long as they are
 * the same width. A scheme carries those restrictions on its quantified variables, which is what
 * lets one entry stand in for what would otherwise be a pile of overloads.
 *
 * `FieldMLRuntimePrelude` holds the other half, name for name: the values these types describe.
 * The two lists are matched by hand and nothing in the build compares them. What the build does
 * guard is the page a reader looks the prelude up in, through
 * `FieldMLCheatsheetCoverageTest.everyPreludeNameIsListedWithItsType`, which fails when a name is
 * added, removed or retyped without `public/docs/fieldml_cheatsheet.md` following.
 *
 * The [THost] values at the top are the handful of host types FieldML names. Everything else the
 * JVM offers arrives as the opaque `Host`, and the `host…` family of intrinsics is how a program
 * says what a value of that type actually is.
 */

/** The prelude as types: the schemes `Inferencer` starts from, and the five host types FieldML knows by name. */
object FieldMLTypePrelude {
    /** `Host`: what a value the type checker cannot say anything more specific about has. */
    val host = THost(FieldMLHostTypes.OPAQUE)

    /** A two-component vector. Arithmetic, `.x`/`.y`, and every `overVector` scheme accept it. */
    val vec2 = THost("Vec2")

    /** A three-component vector, and the only width `cross` is defined for. */
    val vec3 = THost("Vec3")

    /** A four-component vector, which is also how a colour is written. */
    val vec4 = THost("Vec4")

    /** An `afield.graphics.FLine`, threaded through the drawing prelude so `moveTo`/`lineTo`/`draw` chain in a pipe. */
    val fline = THost("FLine")

    /**
     * `thisBox` is the receiver most host calls start from, and it really is an `afield.Box` — so
     * typing it as one is what turns the whole `hostGet`/`hostCall` family from opaque into checked
     * in the case a live document hits first.
     */
    val box = THost("Box")

    private val vectorNames = setOf("Vec2", "Vec3", "Vec4")

    /** True for `Vec2`/`Vec3`/`Vec4`, the host types that support arithmetic and `.x`/`.y`/`.z`/`.w`. */
    fun isVectorType(type: Type): Boolean = type is THost && type.name in vectorNames

    /** Component names readable off a vector type, in order. */
    fun vectorComponents(type: Type): List<String> =
        when ((type as? THost)?.name) {
            "Vec2" -> listOf("x", "y")
            "Vec3" -> listOf("x", "y", "z")
            "Vec4" -> listOf("x", "y", "z", "w")
            else -> emptyList()
        }

    private val any = TVar(-1)
    private val hostAny = THost("Host")
    private fun number(id: Int): Type = TVar(id)

    // Scheme builders for the polymorphic part of the prelude. Quantified ids are handed out from a
    // single descending counter so that no two schemes accidentally share one.
    private var nextSchemeId = -100

    private fun freshSchemeId(): Int = nextSchemeId--

    private fun poly(build: (Type) -> Type): Scheme {
        val id = freshSchemeId()
        return Scheme(setOf(id), build(TVar(id)))
    }

    private fun poly2(build: (Type, Type) -> Type): Scheme {
        val first = freshSchemeId()
        val second = freshSchemeId()
        return Scheme(setOf(first, second), build(TVar(first), TVar(second)))
    }

    /** A scheme over one variable that must resolve to `Int` or `Float`. */
    private fun overNumber(build: (Type) -> Type): Scheme {
        val id = freshSchemeId()
        return Scheme(setOf(id), build(TVar(id)), numeric = setOf(id))
    }

    /** A scheme over one variable that must resolve to `Vec2`, `Vec3` or `Vec4`. */
    private fun overVector(build: (Type) -> Type): Scheme {
        val id = freshSchemeId()
        return Scheme(setOf(id), build(TVar(id)), vector = setOf(id))
    }

    private fun float1(): Scheme = Scheme(emptySet(), fn(TFloat, TFloat))
    private fun float2(): Scheme = Scheme(emptySet(), fn(TFloat, TFloat, TFloat))

    /**
     * The shape of a macro expansion: `afield.macros.Insert`, minus the fields an interpreter has
     * nothing to do with (`standaloneHere`, `afterImports`, `target`).
     *
     * `call` is the leading identifier of the rewritten call site — the `Box` extension the macro
     * rewrote to, or `""` when the rewrite is not a call. `declarations` is the generated Kotlin a
     * Kotlin box would have compiled; for a FieldML box it is text to read.
     */
    private val macroExpansion = TRecord(
        linkedMapOf(
            "call" to TString,
            "arguments" to TString,
            "here" to TString,
            "preamble" to TString,
            "declarations" to TString,
            "start" to TString,
            "end" to TString,
        ),
    )

    /**
     * Every prelude name and its scheme, grouped in declaration order by what it is for: output,
     * vectors and FLines, numbers, lists, drawing, the `host…` intrinsics, and the `prop` family.
     *
     * `Inferencer` wraps this as the outermost type environment, and it also compares against it by
     * *identity* in two places: the `host…` intrinsics are only given their refined types when the
     * name at the head of the call is still this entry. Rebind `hostCall` to something of your own
     * and it becomes an ordinary function of the type your binding has, with none of the
     * special-case narrowing. `FieldMLCompletionEngine` reads the same map for the completion list
     * and for the hover card's signature.
     */
    val schemes: Map<String, Scheme> = linkedMapOf(
        "Unit" to Scheme(emptySet(), TUnit),
        "thisBox" to Scheme(emptySet(), box),
        "print" to Scheme(setOf(-1), fn(any, any)),
        "println" to Scheme(setOf(-1), fn(any, any)),
        "display" to Scheme(setOf(-1), fn(TString, fn(any, any))),
        "show" to Scheme(setOf(-1), fn(any, TString)),
        "tap" to Scheme(setOf(-1, -2), fn(fn(TVar(-1), TVar(-2)), fn(TVar(-1), TVar(-1)))),

        "vec2" to numericScheme(-10, -11, body = fn(number(-10), number(-11), vec2)),
        "vec3" to numericScheme(-12, -13, -14, body = fn(number(-12), number(-13), number(-14), vec3)),
        "vec4" to numericScheme(-15, -16, -17, -18, body = fn(number(-15), number(-16), number(-17), number(-18), vec4)),

        "fline" to Scheme(emptySet(), fline),
        "moveTo" to numericScheme(-20, -21, body = fn(number(-20), number(-21), fn(fline, fline))),
        "moveTo3" to numericScheme(-22, -23, -24, body = fn(number(-22), number(-23), number(-24), fn(fline, fline))),
        "lineTo" to numericScheme(-25, -26, body = fn(number(-25), number(-26), fn(fline, fline))),
        "lineTo3" to numericScheme(-27, -28, -29, body = fn(number(-27), number(-28), number(-29), fn(fline, fline))),
        "moveToV" to Scheme(emptySet(), fn(vec2, fn(fline, fline))),
        "lineToV" to Scheme(emptySet(), fn(vec2, fn(fline, fline))),
        "cubicTo" to numericScheme(
            -30,
            -31,
            -32,
            -33,
            -34,
            -35,
            body = fn(number(-30), number(-31), number(-32), number(-33), number(-34), number(-35), fn(fline, fline)),
        ),
        "close" to Scheme(emptySet(), fn(fline, fline)),
        "thickness" to numericScheme(-36, body = fn(number(-36), fn(fline, fline))),
        "color" to Scheme(emptySet(), fn(vec4, fn(fline, fline))),
        "filled" to Scheme(emptySet(), fn(TBool, fn(fline, fline))),
        "stroked" to Scheme(emptySet(), fn(TBool, fn(fline, fline))),
        "pointed" to Scheme(emptySet(), fn(TBool, fn(fline, fline))),
        "pointSize" to Scheme(emptySet(), fn(TFloat, fn(fline, fline))),

        // Numbers. Every helper that transforms a single subject takes it last, so it reads well at
        // the end of a pipe: `x |> pow 2.0` is x squared, `x |> mod 4` is x modulo 4.
        "pi" to Scheme(emptySet(), TFloat),
        "tau" to Scheme(emptySet(), TFloat),
        "sin" to float1(),
        "cos" to float1(),
        "tan" to float1(),
        "asin" to float1(),
        "acos" to float1(),
        "atan" to float1(),
        "atan2" to float2(),
        "sqrt" to float1(),
        "exp" to float1(),
        "log" to float1(),
        "floor" to float1(),
        "ceil" to float1(),
        "round" to float1(),
        "pow" to float2(),
        "fmod" to float2(),
        "lerp" to Scheme(emptySet(), fn(TFloat, TFloat, TFloat, TFloat)),
        "abs" to overNumber { a -> fn(a, a) },
        "sign" to overNumber { a -> fn(a, a) },
        "min" to overNumber { a -> fn(a, a, a) },
        "max" to overNumber { a -> fn(a, a, a) },
        "clamp" to overNumber { a -> fn(a, a, a, a) },
        "mod" to Scheme(emptySet(), fn(TInt, TInt, TInt)),
        "idiv" to Scheme(emptySet(), fn(TInt, TInt, TInt)),
        "toFloat" to Scheme(emptySet(), fn(TInt, TFloat)),
        "toInt" to Scheme(emptySet(), fn(TFloat, TInt)),
        "not" to Scheme(emptySet(), fn(TBool, TBool)),

        // Lists.
        "range" to Scheme(emptySet(), fn(TInt, TInt, TList(TInt))),
        "linspace" to Scheme(emptySet(), fn(TFloat, TFloat, TInt, TList(TFloat))),
        "tabulate" to poly { a -> fn(TFun(TInt, a), TInt, TList(a)) },
        "map" to poly2 { a, b -> fn(TFun(a, b), TList(a), TList(b)) },
        "mapIndexed" to poly2 { a, b -> fn(TFun(TInt, TFun(a, b)), TList(a), TList(b)) },
        "filter" to poly { a -> fn(TFun(a, TBool), TList(a), TList(a)) },
        "fold" to poly2 { a, b -> fn(TFun(b, TFun(a, b)), b, TList(a), b) },
        "length" to poly { a -> fn(TList(a), TInt) },
        "isEmpty" to poly { a -> fn(TList(a), TBool) },
        "head" to poly { a -> fn(TList(a), a) },
        "tail" to poly { a -> fn(TList(a), TList(a)) },
        "nth" to poly { a -> fn(TInt, TList(a), a) },
        "take" to poly { a -> fn(TInt, TList(a), TList(a)) },
        "drop" to poly { a -> fn(TInt, TList(a), TList(a)) },
        "reverse" to poly { a -> fn(TList(a), TList(a)) },
        "append" to poly { a -> fn(TList(a), TList(a), TList(a)) },
        "concat" to poly { a -> fn(TList(TList(a)), TList(a)) },
        "zip" to poly2 { a, b -> fn(TList(b), TList(a), TList(TTuple(listOf(a, b)))) },
        "sortBy" to poly { a -> fn(TFun(a, TFloat), TList(a), TList(a)) },
        "sum" to Scheme(emptySet(), fn(TList(TFloat), TFloat)),

        // Vectors. These work on any of Vec2/Vec3/Vec4, as long as both sides agree.
        "dot" to overVector { v -> fn(v, v, TFloat) },
        "vlength" to overVector { v -> fn(v, TFloat) },
        "distance" to overVector { v -> fn(v, v, TFloat) },
        "normalize" to overVector { v -> fn(v, v) },
        "mix" to overVector { v -> fn(v, v, TFloat, v) },
        "cross" to Scheme(emptySet(), fn(vec3, vec3, vec3)),
        "polar" to Scheme(emptySet(), fn(TFloat, TFloat, vec2)),

        // Drawing and animation.
        "draw" to Scheme(emptySet(), fn(TString, fline, fline)),
        "drawSized" to Scheme(emptySet(), fn(TString, TFloat, TFloat, fline, fline)),
        "animate" to poly { a -> fn(TFun(TFloat, a), hostAny) },
        "animateFor" to poly { a -> fn(TFloat, TFun(TFloat, a), hostAny) },

        "hostInput" to Scheme(emptySet(), hostAny),
        "hostNull" to Scheme(emptySet(), hostAny),
        "hostClass" to Scheme(emptySet(), fn(TString, hostAny)),
        "hostNew" to Scheme(setOf(-43), fn(TVar(-43), fn(hostAny, hostAny))),
        "hostNew0" to Scheme(emptySet(), fn(hostAny, hostAny)),
        "hostCall" to Scheme(setOf(-44, -45), fn(TString, fn(TVar(-44), fn(TVar(-45), hostAny)))),
        "hostCall0" to Scheme(setOf(-2), fn(TString, fn(TVar(-2), hostAny))),
        "hostCall1" to Scheme(setOf(-3, -4), fn(TString, fn(TVar(-3), fn(TVar(-4), hostAny)))),
        "hostCall2" to Scheme(setOf(-5, -6, -7), fn(TString, fn(TVar(-5), fn(TVar(-6), fn(TVar(-7), hostAny))))),
        "hostStaticCall" to Scheme(setOf(-38), fn(TString, fn(TVar(-38), fn(hostAny, hostAny)))),
        "hostExt" to Scheme(setOf(-50, -51), fn(hostAny, fn(TString, fn(TVar(-50), fn(TVar(-51), hostAny))))),
        "hostStaticTap" to Scheme(setOf(-39, -40), fn(TString, fn(TVar(-39), fn(hostAny, fn(TVar(-40), TVar(-40)))))),
        "hostTap" to Scheme(setOf(-41, -42), fn(TString, fn(TVar(-41), fn(TVar(-42), TVar(-42))))),
        "hostGet" to Scheme(setOf(-8), fn(TString, fn(TVar(-8), hostAny))),
        "hostSet" to Scheme(setOf(-9, -37), fn(TString, fn(TVar(-9), fn(TVar(-37), hostAny)))),

        // Erasure escapes: the author asserting what a `Host` reflection could only see as `Object`
        // actually is. Deliberately not inferred — a guess here would be the one dishonest type.
        "hostAs" to Scheme(setOf(-52), fn(TString, fn(TVar(-52), hostAny))),
        "hostAsInt" to Scheme(setOf(-46), fn(TVar(-46), TInt)),
        "hostAsFloat" to Scheme(setOf(-47), fn(TVar(-47), TFloat)),
        "hostAsBool" to Scheme(setOf(-48), fn(TVar(-48), TBool)),
        "hostAsString" to Scheme(setOf(-49), fn(TVar(-49), TString)),
        "hostAsList" to Scheme(setOf(-53), fn(TVar(-53), TList(hostAny))),

        // Source macros — mechanism A. `hostMacroExpand` is the one prelude name whose result is a
        // *record* type rather than `Host`: the shape is fixed by `afield.macros.Insert`, so unlike a
        // reflective call there is something honest to say, and `expansion.call` is checked where it is
        // written. `hostMacro`'s result cannot be — a macro rewrites to whichever function it likes.
        "hostMacroExpand" to Scheme(setOf(-54), fn(TString, fn(TVar(-54), macroExpansion))),
        "hostMacro" to Scheme(setOf(-55, -56), fn(TString, fn(TVar(-55), fn(TVar(-56), hostAny)))),

        // The `prop` family. A slot holds `Any`, so reading one is `Host` and the author says what it
        // is — the same rule as `hostGet`, and for the same reason: a guess here would be the one
        // dishonest type. Writing is different, and typed: `setProp` hands back exactly what it stored.
        "prop" to Scheme(emptySet(), fn(TString, hostAny)),
        "propOr" to Scheme(setOf(-57), fn(TString, fn(TVar(-57), hostAny))),
        "setProp" to Scheme(setOf(-58), fn(TString, fn(TVar(-58), TVar(-58)))),
        "rootProp" to Scheme(emptySet(), fn(TString, hostAny)),
        "setRootProp" to Scheme(setOf(-59), fn(TString, fn(TVar(-59), TVar(-59)))),
        "once" to Scheme(setOf(-60, -61), fn(TString, fn(TFun(TVar(-60), TVar(-61)), hostAny))),
    )

    private fun fn(vararg parts: Type): Type {
        require(parts.size >= 2)
        return parts.dropLast(1).asReversed().fold(parts.last()) { acc, part -> TFun(part, acc) }
    }

    private fun numericScheme(vararg ids: Int, body: Type): Scheme =
        Scheme(ids.toSet(), body, ids.toSet())
}
