package afield.logger

import org.jetbrains.kotlin.utils.threadLocal
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


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

@OptIn(ExperimentalContracts::class)
inline fun lblock(message : Any?, block :  () -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    lprintln(">|> ${message}")
    indent += "\t"
    try {
        block()
    }
    finally {
        if (indent.length>0) indent = indent.substring(1)
        lprintln("<|<")
    }
}

