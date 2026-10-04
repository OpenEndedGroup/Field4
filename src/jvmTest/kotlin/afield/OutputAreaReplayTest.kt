package afield

import kotlinx.serialization.json.Json
import kotlin.test.*

class OutputAreaReplayTest {
    @Test fun sameBoxIdInTwoDocumentsHasIndependentOutputAndDeletion() {
        val uid="shared-preview"
        val a="project-one"; val b="project-two"
        val marker=BoxOutputLog.mark()
        fun display(document:String,html:String)=BoxOutputLog.observeBroadcast(
            Message("set-output").payload(Set_output(uid,"outputarea","preview",html)),document)
        try {
            display(a,"First design"); display(b,"Second design"); display(a,"Edited first design")
            BoxOutputLog.observeOutput(Output(uid=uid,line=1,html="Second error",kind="err",frame=0),b)
            assertEquals(listOf("Edited first design"),BoxOutputLog.since(uid,marker,document=a).entries.map{it.text})
            assertEquals(listOf("Second design","Second error"),BoxOutputLog.since(uid,marker,document=b).entries.map{it.text})
            assertTrue(BoxOutputLog.since(uid,marker).entries.isEmpty())
            BoxOutputLog.clear(uid,a)
            assertEquals(2,BoxOutputLog.since(uid,marker,document=b).entries.size)
        } finally { BoxOutputLog.clear(uid,a);BoxOutputLog.clear(uid,b) }
    }
    @Test fun libraryCardsAreVisibleToCaptureAndLateOutputReplayWithoutRunningTheBox() {
        val box=Box("library-card-output-test")
        afield.stdlib.BoxStudies.show(box,"builds","<p>Working</p>")
        afield.stdlib.BoxStudies.show(box,"builds","<p>Build held: keep your design</p>")
        assertEquals(listOf("builds"),box.output.retainedAreas.channels())
        val messages=box.output.retainedAreas.snapshot(box.id)
        assertEquals(listOf("set-output","output-control"),messages.map { it.name })
        assertEquals("<p>Build held: keep your design</p>",Json.decodeFromJsonElement(Set_output.serializer(),messages[0].payload!!).value)
        assertEquals("open",Json.decodeFromJsonElement(OutputControl.serializer(),messages[1].payload!!).action)
        box.displayClear("builds")
        assertTrue(box.output.retainedAreas.channels().isEmpty())
        assertTrue(box.output.retainedAreas.snapshot(box.id).isEmpty())
    }
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
