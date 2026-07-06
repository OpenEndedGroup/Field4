package afield.ml.type

object FieldMLTypePrelude {
    val host = THost("Host")
    val vec2 = THost("Vec2")
    val vec3 = THost("Vec3")
    val vec4 = THost("Vec4")
    val fline = THost("FLine")

    private val any = TVar(-1)
    private val hostAny = THost("Host")
    private fun number(id: Int): Type = TVar(id)

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
