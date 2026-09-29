package afield

import kotlinx.serialization.json.Json
import kotlin.test.*

class OutputAreaReplayTest {
    @Test fun replacementVisibilityAndClearSurviveLateConnection() {
        val state=OutputAreaReplay()
        state.control("open","form")
        state.put("form","old");state.put("status","ready");state.put("form","new")
        val messages=state.snapshot("a")
        assertEquals(listOf("set-output","output-control","set-output"),messages.map { it.name })
        assertEquals("new",Json.decodeFromJsonElement(Set_output.serializer(),messages[0].payload!!).value)
        state.control("close","form")
        assertEquals("close",Json.decodeFromJsonElement(OutputControl.serializer(),state.snapshot("a")[1].payload!!).action)
        state.control("clear","form")
        assertEquals(1,state.snapshot("a").size)
        state.control("clear-all",null);assertTrue(state.snapshot("a").isEmpty())
    }
    @Test fun ownershipAndSizeAreBounded() {
        val a=OutputAreaReplay(6); val b=OutputAreaReplay()
        a.put("one","1234");a.put("two","5678")
        assertEquals(1,a.snapshot("a").size);assertTrue(b.snapshot("b").isEmpty())
        a.put("large","1234567");assertTrue(a.snapshot("a").isEmpty())
    }
}
