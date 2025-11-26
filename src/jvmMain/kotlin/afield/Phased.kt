package afield

import kotlinx.coroutines.*
import java.util.concurrent.LinkedTransferQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class Frames {
    var job: Job? = null
    private var stillborn = false

    var atEnd = suspend {}
    var body = suspend {}
    var shouldend = { false }

    var frameNumber = 0

    override fun toString(): String {
        return "(frames)"
    }

    suspend fun wait() {
        while (!coroutineContext[Job]!!.isCancelled) frame()
    }

    suspend fun end() {
        if (job != null) job?.cancel()
        else {
            stillborn = true
        }
    }

    suspend fun delay(ms: Int) {
        kotlinx.coroutines.delay(ms.toLong())
        frame()
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun frame(): Boolean {
        job = coroutineContext[Job]
        var r = true
        if (stillborn && job != null) {
            job?.cancel()
            stillborn = false
            r = false
        }

        if (shouldend() && job != null) {
            job?.cancel()
            r = false
        }

        try {
            yield()
            frameNumber++
            return r
        } catch (t: Throwable) {
            // this seems a little wild
            coroutineScope {
                launch(coroutineContext[CoroutineDispatcher]!! + Job()) {
                    atEnd()
                }
            }
            throw t
        }
    }
}

class EndedException : Throwable()

fun frames(block: suspend Frames.() -> Unit): Frames {
    val v = Frames()

    v.body = suspend {
        block(v)
    }

    return v
}

fun forever(block: suspend Frames.() -> Boolean): Frames {
    val v = Frames()

    v.body = suspend {
        var f = v.frameNumber
        while(block(v)) {
            if (v.frameNumber==f) {
                v.frame()
            }
            f = v.frameNumber
        }
    }

    return v
}

fun loop(block: suspend Frames.() -> Unit): Frames {
    val v = Frames()

    v.body = suspend {
        var f = v.frameNumber
        while(true) {
            block(v)
            if (v.frameNumber==f) {
                v.frame()
            }
            f = v.frameNumber
        }
    }

    return v
}


fun eachFrame(block: suspend Frames.() -> Unit): Frames {
    return frames {
        while(frame()) {
            this.block()
        }
    }
}

@OptIn(InternalCoroutinesApi::class)
class Framer : CoroutineDispatcher() {

    var nextList = LinkedTransferQueue<Pair<CoroutineContext, Runnable>>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        nextList.add(context to block)
//        println(" framer will dispatch $context $block $nextList ${nextList.size} ${System.identityHashCode(this)}")
    }

    fun frame(): Int {
        var n = nextList.poll()
        var c = 0
        while (n != null) {
            c++
            try {
                n.second.run()
            } catch (ex: Throwable) {
                ex.printStackTrace()
            }
            n = nextList.poll()
        }
        if (c == 0) print(".") else print(":")
        return c
    }

}

fun (suspend () -> Unit).then(next: suspend () -> Unit): (suspend () -> Unit) {
    val first = this
    return suspend {
        first()
        next()
    }
}

//context(CoroutineScope)
//fun (suspend () -> Unit).and(next: suspend () -> Unit): (suspend () -> Unit) {
//    val first = this
//    return suspend {
//        var a = launch {
//            first()
//        }
//        var b = launch {
//            next()
//        }
//        a.join()
//        b.join()
//    }
//}

