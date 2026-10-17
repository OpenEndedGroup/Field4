import afield.io.syphon.*

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

/*
Match a server name, app name or UUID; "" takes the first announced server.
This named client is shared across reruns; a closed client is recreated on lookup.
Stop ends this receive loop while leaving the shared connection available.
*/
val inp = syphonClient("Field pattern")

loop {
    inp.png()?.let { display("main", it) }
    delay(250)
}
