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

/**
 * Format a nanosecond duration as a compact, human-readable value **with its unit** for
 * pinned-console stat lines: more significant digits for sub-millisecond values, plain integers
 * once past 100ms, and a rollover to decimal seconds past 1s (so a slow `compile=1.63s` reads
 * cleanly rather than `compile=1629ms`). The trailing unit (`ms`/`s`) is part of the returned
 * string and satisfies the [afield.logger] heatmap token grammar (`key=value unit`), so callers
 * emit `"compile=${formatMillis(ns)}"` with no unit of their own.
 */
fun formatMillis(nanos: Long): String {
    val ms = nanos / 1_000_000.0
    return when {
        ms >= 10_000.0 -> "%.1fs".format(ms / 1000.0)
        ms >= 1_000.0 -> "%.2fs".format(ms / 1000.0)
        ms >= 100.0 -> "%.0fms".format(ms)
        ms >= 10.0 -> "%.1fms".format(ms)
        ms >= 1.0 -> "%.2fms".format(ms)
        else -> "%.3fms".format(ms)
    }
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
