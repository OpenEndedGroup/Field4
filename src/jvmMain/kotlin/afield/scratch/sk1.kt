package afield.scratch

import afield.inMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async


fun inandout() {
    inMain {
        println("stage 1 ${Thread.currentThread().name}")
        async(Dispatchers.Default) {
            println("stage 2... ${Thread.currentThread().name}")
            Thread.sleep(2000)
            println("...stage 2, complete ${Thread.currentThread().name}")
        }.await()
        println("stage 3 ${Thread.currentThread().name}")
    }
}
