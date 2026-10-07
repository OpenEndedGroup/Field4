package afield.ai.core

import afield.Runloop
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/** Fail before dispatch when a synchronous caller is preventing the owner from running. */
internal suspend fun checkToolRunloopDispatch() {
    check(!Runloop.isMainThread || coroutineContext[ContinuationInterceptor] === Runloop.runLoop.main) {
        "Calling an aiTool from runBlocking on Field's UI thread would deadlock. " +
            "Call the registered tool directly from AI4, or invoke it inside start { ... } in box code."
    }
    // Choosing Dispatchers.IO for runBlocking moves its body, not the blocked caller.
    // The owner is already inside runBlocking before that body's first dispatch.
    val owner = Runloop.mainThread
    check(owner == null || owner === Thread.currentThread() || owner.stackTrace.none {
        it.className.startsWith("kotlinx.coroutines.BuildersKt") && it.methodName.startsWith("runBlocking")
    }) {
        "Calling an aiTool while Field's UI thread is in runBlocking would deadlock, even with Dispatchers.IO. " +
            "Call the registered tool directly from AI4, or invoke it inside start { ... } in box code."
    }
}
