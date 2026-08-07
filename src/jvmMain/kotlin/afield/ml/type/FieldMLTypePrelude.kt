package afield.ml.type

object FieldMLTypePrelude {
    val host = THost("Host")
    val vec2 = THost("Vec2")
    val vec3 = THost("Vec3")
    val vec4 = THost("Vec4")
    val fline = THost("FLine")

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

    val schemes: Map<String, Scheme> = linkedMapOf(
        "Unit" to Scheme(emptySet(), TUnit),
        "thisBox" to Scheme(emptySet(), hostAny),
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
        "hostClass" to Scheme(emptySet(), fn(TString, hostAny)),
        "hostNew0" to Scheme(emptySet(), fn(hostAny, hostAny)),
        "hostCall0" to Scheme(setOf(-2), fn(TString, fn(TVar(-2), hostAny))),
        "hostCall1" to Scheme(setOf(-3, -4), fn(TString, fn(TVar(-3), fn(TVar(-4), hostAny)))),
        "hostCall2" to Scheme(setOf(-5, -6, -7), fn(TString, fn(TVar(-5), fn(TVar(-6), fn(TVar(-7), hostAny))))),
        "hostStaticCall" to Scheme(setOf(-38), fn(TString, fn(TVar(-38), fn(hostAny, hostAny)))),
        "hostStaticTap" to Scheme(setOf(-39, -40), fn(TString, fn(TVar(-39), fn(hostAny, fn(TVar(-40), TVar(-40)))))),
        "hostTap" to Scheme(setOf(-41, -42), fn(TString, fn(TVar(-41), fn(TVar(-42), TVar(-42))))),
        "hostGet" to Scheme(setOf(-8), fn(TString, fn(TVar(-8), hostAny))),
        "hostSet" to Scheme(setOf(-9, -37), fn(TString, fn(TVar(-9), fn(TVar(-37), hostAny)))),
    )

    private fun fn(vararg parts: Type): Type {
        require(parts.size >= 2)
        return parts.dropLast(1).asReversed().fold(parts.last()) { acc, part -> TFun(part, acc) }
    }

    private fun numericScheme(vararg ids: Int, body: Type): Scheme =
        Scheme(ids.toSet(), body, ids.toSet())
}
