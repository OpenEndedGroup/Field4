package kotlinx.coroutines.internal.intellij

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object IntellijCoroutines {
    fun currentThreadCoroutineContext(): CoroutineContext = EmptyCoroutineContext
}
