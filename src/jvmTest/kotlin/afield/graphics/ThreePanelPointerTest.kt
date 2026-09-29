package afield.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreePanelPointerTest {
    @Test fun eachViewportHasItsOwnRayCoordinatesAndChromeDoesNotPick() {
        ViewerJavaScript.Session.open("panel-pointer",listOf("panelPointerAt")).use { js->
            val views=js.fromJson("""[{"x":0,"y":0,"w":300,"h":358},{"x":300,"y":0,"w":300,"h":358},{"x":600,"y":0,"w":300,"h":358}]""")
            for(i in 0..2) {
                val hit=js.call("panelPointerAt",150+300*i,179,views)
                assertEquals(i,hit.getMember("panel").asInt())
                assertEquals(0.0,hit.getMember("x").asDouble(),1e-12)
                assertEquals(0.0,hit.getMember("y").asDouble(),1e-12)
            }
            assertTrue(js.call("panelPointerAt",450,380,views).isNull)
            assertTrue(js.call("panelPointerAt",900,100,views).isNull)
            assertTrue(js.call("panelPointerAt",-1,100,views).isNull)
            assertEquals(1,js.call("panelPointerAt",300,100,views).getMember("panel").asInt())
        }
    }
    @Test fun bottomOriginRowsAndAnEmptyLastCellAreHandled() {
        ViewerJavaScript.Session.open("panel-pointer",listOf("panelPointerAt")).use { js->
            val views=js.fromJson("""[{"x":0,"y":400,"w":300,"h":358},{"x":300,"y":400,"w":300,"h":358},{"x":0,"y":0,"w":300,"h":358}]""")
            assertEquals(0,js.call("panelPointerAt",150,579,views).getMember("panel").asInt())
            assertEquals(2,js.call("panelPointerAt",150,179,views).getMember("panel").asInt())
            assertTrue(js.call("panelPointerAt",450,179,views).isNull)
        }
    }
}
