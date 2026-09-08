package afield.examples

import afield.globalPlaywriteContext
import afield.playwrightDispatcher

/** Browser regression helper; uses the same dedicated dispatcher as Field screenshot capture. */
object ProfilingPanelWorkout {
    fun checkCorners() {
        // Trusted browser pointer events through Field's own Playwright dispatcher.
        kotlinx.coroutines.runBlocking(playwrightDispatcher) {
            val page = checkNotNull(globalPlaywriteContext)
            for (corner in listOf("nw", "ne", "sw", "se")) {
                page.evaluate("""() => Object.assign(document.getElementById('profile-timeline').style,
                    {left:'80px',top:'80px',right:'auto',bottom:'auto',width:'1100px',height:'600px'})""")
                val before = page.locator("#profile-timeline").boundingBox()
                val x = if (corner.endsWith("w")) before.x+6 else before.x+before.width-6
                val y = if (corner.startsWith("n")) before.y+6 else before.y+before.height-6
                val dx = if (corner.endsWith("w")) 70.0 else -70.0
                val dy = if (corner.startsWith("n")) 40.0 else -40.0
                page.mouse().move(x,y)
                page.mouse().down()
                try {
                    repeat(10) { i -> page.mouse().move(x+dx*(i+1)/10, y+dy*(i+1)/10) }
                } finally { page.mouse().up() }
                val after = page.locator("#profile-timeline").boundingBox()
                check(after.width < before.width-50 && after.height < before.height-25) { "Corner $corner did not resize: ${after.width} x ${after.height}" }
            }
        }
    }
}
