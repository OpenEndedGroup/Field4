package afield.ai.tools

import afield.runtime.lifecycle.Runloop
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * The check that turns a deadlocking `aiTool` call into an error message.
 *
 * A tool body runs on the Runloop's main thread, so a caller that is already blocking that thread inside
 * `runBlocking` can never let the body run: the call would wait for a thread that is waiting for it.
 * Giving the `runBlocking` a `Dispatchers.IO` context moves the body but not the blocked caller, so that
 * case is checked as well, by looking for `runBlocking` on the owning thread's stack. Both messages name
 * the two ways of calling a tool that do work: directly from AI4, or inside `start { ... }` in box code.
 */

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
