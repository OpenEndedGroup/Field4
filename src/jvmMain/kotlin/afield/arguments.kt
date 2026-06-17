package afield

import afield.Server
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly


inline fun <reified T> option(name: String, def: () -> T): T {
    val i = Server.args.indexOfFirst { it.equals("--" + name, true) }
    if (i == -1) {
        return def()
    }
    if (i == Server.args.size - 1) throw IllegalArgumentException("expected argument after $name")
    return convertTo(T::class.java, Server.args[i + 1])
}

@Suppress("UNCHECKED_CAST")
fun <T> convertTo(target: Class<T>, value: String): T {
    if (target == String::class.java) return value as T
    if (target == Int::class.java || target == Int::class.javaObjectType) return value.toInt() as T
    if (target == Double::class.java || target == Double::class.javaObjectType) return value.toDouble() as T
    if (target == Float::class.java || target == Float::class.javaObjectType) return value.toFloat() as T
    throw IllegalArgumentException("can't convert String to $target")
}
