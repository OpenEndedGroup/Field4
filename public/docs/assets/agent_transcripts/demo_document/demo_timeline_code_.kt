// A time slider: drag it sideways and it starts each box it enters and stops each box it leaves.
rootProp<Box>(execution) = thisBox

onViewportChange +=
    "lock_x" to
        { inside, a, b ->
            frame = frame.copy(y = -b.y - 100, h = b.h * 2 - 50, w = 40.0)
        }

onFrameChange +=
    "lock_x" to
        { inside, a, b ->
            // The boxes under the line before the move, swept by it, and under it after.
            val all = root.children - thisBox
            val before = all.filter { it.frame.intersects(a.x) }
            val swept = all.filter { it.frame.intersects(a.x, b.x) }
            val after = all.filter { it.frame.intersects(b.x) }
            (after - before).forEach(Box::begin)
            (before - after).forEach(Box::end)
            // Crossed in one move: start it and stop it again.
            (swept - (before + after)).forEach {
                inMain {
                    it.begin()
                    yield()
                    it.end()
                }
            }
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
