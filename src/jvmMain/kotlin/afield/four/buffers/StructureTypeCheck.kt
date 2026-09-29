package afield.four.buffers

import afield.four.modules.Introspected
import afield.four.util.Vec2h
import afield.four.util.Vec3h
import afield.four.util.Vec4h
import afield.stdlib.linalg.Matrix2f
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

/**
 * Why [value] cannot go into this member's slot, or null when it can — the check [Structure.set] and
 * [Structure.writeToBuffer] make against the *reflected* type before writing.
 *
 * `writeToBuffer` writes by the value's Kotlin runtime type, so a `Vector3f` set on a member the shader
 * now declares `float` wrote twelve bytes into a four-byte slot and overwrote the next member. The
 * comparison is by shape, component count and whether the scalar is 64-bit, which is what decides how
 * many bytes land: a `Float` fits `float`, `int`, `uint` or a half, a `Vector3f` fits `vec3`, a `Long`
 * fits `uint64_t` and a device pointer. Values whose width this cannot know — a [WritesToBuffer], a raw
 * `ByteBuffer`, a [HasUniform] before it is evaluated, a buffer reference — and array members are not
 * judged here.
 */
internal fun Introspected.Member.refusalFor(value: Any): String? {
    if (arrayStride > 0 || physicalPointer) return null
    val (components, wide) = kotlinValueShape(value) ?: return null
    val primitive = type.fold({ it }, { null })
        ?: return "`$name` is a struct in the shader, and ${describeValue(value)} is not"
    val primitiveWide = primitive.componentsSizeInBytes == 8
    if (components != primitive.components || wide != primitiveWide)
        return "`$name` is ${primitive.name} in the shader, and ${describeValue(value)} is not"
    return null
}

private fun kotlinValueShape(value: Any): Pair<Int, Boolean>? = when (value) {
    is Float, is Int, is UInt -> 1 to false
    is Long -> 1 to true
    is Vector2f, is Vec2h -> 2 to false
    is Vector3f, is Vec3h -> 3 to false
    is Vector4f, is Quaternionf, is Vec4h -> 4 to false
    is Matrix2f -> 4 to false
    is Matrix3f -> 9 to false
    is Matrix4f -> 16 to false
    else -> null
}

private fun describeValue(value: Any): String = "a ${value::class.simpleName ?: value.javaClass.name}"

/** The member at [path] (`"light.color"` for a nested member) among [members], or null. */
internal fun memberAtPath(members: List<Introspected.Member>, path: String): Introspected.Member? {
    var level = members
    var found: Introspected.Member? = null
    for (part in path.split('.')) {
        found = level.firstOrNull { it.name == part } ?: return null
        level = found.type.fold({ emptyList() }, { it })
    }
    return found
}
