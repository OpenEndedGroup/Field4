// A time slider: drag it sideways and it starts each box it enters and stops each box it leaves.
rootProp<Box>(execution) = thisBox

fun launchFrom(was: Double, now: Double) {
    val all = root.children - thisBox
    val a = all.filter { it.frame.intersects(was) }
    val b = all.filter { it.frame.intersects(was, now) }
    val c = all.filter { it.frame.intersects(now) }

    val incoming = c - a
    val outgoing = a - c
    val onlyOne = b - (a + c)

    incoming.forEach(Box::begin)
    outgoing.forEach(Box::end)
    onlyOne.forEach {
        inMain {
            it.begin()
            yield()
            it.end()
        }
    }
}

onViewportChange +=
    "lock_x" to
        { inside, a, b ->
            frame = frame.copy(y = -b.y - 100, h = b.h * 2 - 50, w = 40.0)
        }

onFrameChange +=
    "lock_x" to
        { inside, a, b ->
            launchFrom(a.x, b.x)
            b.copy(y = -inside.viewport.y - 100, w = 40.0)
        }

frame = frame.copy(w = 40.0)

customization =
    """
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
