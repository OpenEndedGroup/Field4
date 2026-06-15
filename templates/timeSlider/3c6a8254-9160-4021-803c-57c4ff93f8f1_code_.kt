// export this box so that any box can now write 'execute' to talk about this box
rootProp<Box>(execution) = thisBox

fun launchFrom(was : Double, now : Double) {

    val all = root.children - thisBox
    val a = all.filter { it.frame.intersects(was) }
    val b = all.filter { it.frame.intersects(was, now) }
    val c = all.filter { it.frame.intersects(now) }

    val incomming = c-a // starting
    val outgoing = a-c // stopping
    val onlyOne = b-(a+c) // starting and stopping

    incomming.forEach(Box::begin)
    outgoing.forEach(Box::end)

    onlyOne.forEach {
        inMain {
            it.begin()
            yield()
            it.end()
        }
    }
}

onViewportChange += "lock_x" to { inside, a, b ->
    frame = frame.copy(y = -b.y-100, h = b.h*2-50, w = 40.0)
}

onFrameChange += "lock_x" to { inside, a, b ->
    launchFrom(a.x, b.x)
    b.copy(y = -inside.viewport.y-100, w = 40.0)
}

frame = frame.copy(w=40.0)

// set a custom border, and lock the height
customization = """
    this.style.border = "0px solid transparent"
    this.style.borderLeft = "3px solid red"
    this.style.background = "transparent"
    this.style.marginTop = "0px"
    this.style.paddingTop = "0px"
    this.style.setProperty("--box-border", "0")
    this.style.fontStyle = "italic"
    this.style.color = "white"
    this.lock_w = true
    this.lock_h = true
"""

name = "t"
