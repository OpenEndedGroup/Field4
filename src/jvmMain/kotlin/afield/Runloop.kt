package afield


import afield.logger.lprintln
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.max

@OptIn(DelicateCoroutinesApi::class)
fun inMain(block: suspend CoroutineScope.() -> Unit) = GlobalScope.launch(Runloop.runLoop.main, block = block)

class Runloop {

    private constructor() {
        lprintln("info,runloop", "Runloop is being constructed")
        lprintln("info,runloop", "main = ${System.identityHashCode(main)}")
    }

    companion object {
        val runLoop = Runloop()
        val miscPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 2)
        @Volatile var mainThread: Thread? = null
    }

    var frame = 0L

    val main = Framer()
    var shouldSleep = true

    var limiter = RateLimiter(10.0, 0.2)

    fun start() {
        if (mainThread == null) {
            mainThread = Thread.currentThread()
        }
        while (true) {
            lprintln("debug,runloop", "run loop anybody? ${Thread.currentThread()}/${Thread.currentThread().name}")
            main.frame()
            frame++

            if (shouldSleep) {
                lprintln("debug,runloop", "framer has run out of work")
                Thread.sleep(1)
            }
            shouldSleep = true
        }
    }
}

class RateLimiter(
    rate: Double, private val maxLatency: Double
) {
    private val intervalMillis = (1000.0 / rate).toLong()
    private var lastExecution = 0L             // timestamp of last execution
    private var pending: (() -> Unit)? = null  // the most recent block waiting
    private var job: Job? = null               // scheduled coroutine
    private val lock = Any()

    fun submit(block: () -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // compute next allowed execution time
            val nextTime = max(lastExecution + intervalMillis, now)
            val latency = nextTime - now

            if (latency > maxLatency * 1000) {
                return
            }

            if (job == null) {
                schedule(block, nextTime)
            } else {
                pending = block
            }
        }
    }

    operator fun invoke(block: () -> Unit) = submit(block)

    private fun schedule(block: () -> Unit, executeAt: Long) {
        val delayMs = max(0, executeAt - System.currentTimeMillis())
        job = GlobalScope.launch {
            delay(delayMs)
            // dispatch on your main runloop
            synchronized(lock) {
                // if there’s a newer pending block, schedule it now
                pending?.also { next ->
                    pending = null
                    val now = System.currentTimeMillis()
                    val nextExec = max(lastExecution + intervalMillis, now)
                    if (nextExec - now <= maxLatency * 1000) {
                        schedule(next, nextExec)
                    }
                } ?: inMain {
                    lastExecution = System.currentTimeMillis()
                    job = null
                    block()
                }

            }
        }
    }
}
