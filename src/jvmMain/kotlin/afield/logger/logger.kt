package afield.logger

import afield.ai.core.BlockBoundary
import afield.ai.core.LogDispatcher
import org.jetbrains.kotlin.utils.threadLocal
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Deprecated("Use channelled lprintln with explicit tags", ReplaceWith("lprintln(\"info,legacy\", message)"))
fun lprintln(message: Any?) = lprintln("info,legacy", message)

@Deprecated("Use channelled lprint with explicit tags", ReplaceWith("lprint(\"info,legacy\", message)"))
fun lprint(message: Any?) = lprint("info,legacy", message)

fun lprintln(channel: String, message: Any?) {
    val msg = message?.toString() ?: "null"
    LogDispatcher.log(channel = channel, message = msg, indent = indent)
    isNL = true
}

fun lprint(channel: String, message: Any?) {
    val msg = message?.toString() ?: "null"
    LogDispatcher.log(channel = channel, message = msg, indent = indent)
    isNL = false
}

var indent by threadLocal {
    ""
}

var isNL = true

@OptIn(ExperimentalContracts::class, ExperimentalUuidApi::class)
inline fun lblock(channel: String, message: Any?, block: () -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val u = Uuid.random().toString()
    LogDispatcher.log(
        channel = channel,
        message = "${message ?: ""} - '$u'",
        indent = indent,
        blockId = u,
        boundary = BlockBoundary.BEGIN
    )
    val added = "${u}\t"
    indent += added
    try {
        block()
    } finally {
        if (indent.length > added.length) {
            indent = indent.substring(added.length)
        } else {
            indent = ""
        }
        LogDispatcher.log(
            channel = channel,
            message = "- '$u'",
            indent = indent,
            blockId = u,
            boundary = BlockBoundary.END
        )
    }
}

@Deprecated("Use channelled lblock with explicit tags", ReplaceWith("lblock(\"info,legacy\", message, block)"))
inline fun lblock(message: Any?, block: () -> Unit) = lblock("info,legacy", message, block)
