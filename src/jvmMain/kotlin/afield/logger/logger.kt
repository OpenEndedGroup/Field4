package afield.logger

import org.jetbrains.kotlin.utils.threadLocal
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


fun lprintln(message : Any?) {
    println(indent+message)
    isNL = true
}

fun lprintln() {
    println(indent)
    isNL = true
}

fun lprint(message : Any?) {
    if (isNL) print(indent)
    print(message)
    isNL = false
}

var indent by threadLocal {
    ""
}

var isNL = true

@OptIn(ExperimentalContracts::class, ExperimentalUuidApi::class)
inline fun lblock(message : Any?, block :  () -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val u = Uuid.random().toString()
    lprintln(">|> ${message} - '${u}'")
    val added = "${u}\t"
    indent += added
    try {
        block()
    }
    finally {
        if (indent.length>0) indent = indent.substring(added.length)
        lprintln("<|< - '${u}'")
    }
}

