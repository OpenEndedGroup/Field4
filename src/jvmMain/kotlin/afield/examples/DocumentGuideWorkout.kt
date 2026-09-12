package afield.examples

/** Trusted browser input checks, marshalled onto the same dispatcher as Field's captures. */
object DocumentGuideWorkout {
    fun prepare() {
        kotlinx.coroutines.runBlocking(afield.playwrightDispatcher) {
            checkNotNull(afield.globalPlaywriteContext).setViewportSize(1440, 960)
        }
    }

    fun checkGestures() {
        kotlinx.coroutines.runBlocking(afield.playwrightDispatcher) {
            val page = checkNotNull(afield.globalPlaywriteContext)
            for (corner in listOf("nw", "ne", "sw", "se")) {
                page.evaluate("""() => Object.assign(document.getElementById('document-guide').style,
                    {left:'60px',top:'60px',width:'680px',height:'780px'})""")
                val before = page.locator("#document-guide").boundingBox()
                val x = if (corner.endsWith("w")) before.x+6 else before.x+before.width-6
                val y = if (corner.startsWith("n")) before.y+6 else before.y+before.height-6
                val dx = if (corner.endsWith("w")) 55.0 else -55.0
                val dy = if (corner.startsWith("n")) 40.0 else -40.0
                page.mouse().move(x,y)
                page.mouse().down()
                try { repeat(10) { i -> page.mouse().move(x+dx*(i+1)/10,y+dy*(i+1)/10) } }
                finally { page.mouse().up() }
                val after = page.locator("#document-guide").boundingBox()
                check(after.width < before.width-35 && after.height < before.height-25) { "Guide corner $corner did not resize" }
            }
            val before = page.locator("#document-guide").boundingBox()
            page.mouse().move(before.x+160,before.y+28)
            page.mouse().down()
            try { repeat(8) { i -> page.mouse().move(before.x+160+(i+1)*5,before.y+28+(i+1)*3) } }
            finally { page.mouse().up() }
            val after = page.locator("#document-guide").boundingBox()
            check(after.x > before.x+25 && after.y > before.y+15) { "Guide header did not drag" }
        }
    }
}
