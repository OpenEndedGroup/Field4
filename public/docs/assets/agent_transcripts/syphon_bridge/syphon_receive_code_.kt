import afield.io.syphon.*

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

// a server's name, app name or uuid; "" takes the first one announced
val inp = syphonClient("Field pattern")

loop {
    inp.png()?.let { display("main", it) }
    delay(250)
}
