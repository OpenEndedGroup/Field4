package afield.four.synth

import afield.Box
import afield.SessionInformation
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/**
 * The Kotlin half of `orca()`, fed the reports `orca_field.js` sends. The page half, and the two
 * together in a real browser, are `tools/run_ui_regressions.py --id orca-io`.
 */
class OrcaTest {
    private fun box(): Box {
        val root = Box("root")
        val info = SessionInformation(Files.createTempDirectory("orca-").toString(),
            documentName = "orca", root = root, ephemeral = true)
        root.inside = info
        return Box("player").also { it.inside = info; info.boxes[it.id] = it }
    }

    private fun report(json: String) = Json.parseToJsonElement(json)

    private fun commands(reply: JsonElement) = reply.jsonObject["commands"]!!.jsonArray.map { it.jsonObject }

    @Test fun helloLoadsTheGridAndTempoBeforeAnythingQueued() {
        val orca = Orca(box(), "orca", "D4\n.:03C", 140, false)
        orca.write(0, 3, "#hi#")
        val sent = commands(orca.receive(report("""{"hello":true,"frame":0,"bpm":120,"paused":false,"t":1,"events":[],"operators":"- `A` add"}""")))
        assertEquals(listOf("load", "bpm", "guide", "write"), sent.map { it["op"]!!.jsonPrimitive.content })
        assertEquals("D4\n.:03C", sent[0]["grid"]!!.jsonPrimitive.content)
        assertEquals(140, sent[1]["value"]!!.jsonPrimitive.int)
        assertEquals("#hi#", sent[3]["text"]!!.jsonPrimitive.content)
        assertEquals("- `A` add", orca.operators)
        assertTrue(commands(orca.receive(report("""{"frame":1,"t":2,"events":[]}"""))).isEmpty(), "Commands go once")
    }

    @Test fun everyKindOfMessageReachesItsCallback() {
        val orca = Orca(box(), "orca", null, null, false)
        val notes = mutableListOf<OrcaNote>(); val ccs = mutableListOf<OrcaCC>(); val bends = mutableListOf<OrcaBend>()
        val udp = mutableListOf<String>(); val osc = mutableListOf<OrcaOsc>(); var frames = 0
        orca.onNote { notes += it }.onCC { ccs += it }.onBend { bends += it }
            .onUdp { udp += it }.onOsc { osc += it }.onFrame { frames++ }
        orca.receive(report("""{"frame":9,"bpm":120,"paused":false,"t":1000,"events":[
            {"kind":"note","frame":8,"at":990,"channel":1,"octave":3,"note":"C","midi":60,"velocity":8,"length":2},
            {"kind":"mono","frame":8,"at":990,"channel":2,"octave":4,"note":"a","midi":82,"velocity":16,"length":1},
            {"kind":"cc","frame":8,"at":990,"channel":0,"knob":65,"value":127},
            {"kind":"pb","frame":8,"at":990,"channel":3,"lsb":4,"msb":5},
            {"kind":"udp","frame":8,"at":990,"message":"hello"},
            {"kind":"osc","frame":8,"at":990,"path":"/a","message":"12","args":[1,2]}],
            "grid":"D4\n.:03C","w":5,"h":2,"variables":{"a":"3"},"cursor":{"x":2,"y":1}}"""))

        val note = notes[0]
        assertEquals(listOf(60, 82), notes.map { it.midi })
        assertEquals(listOf(false, true), notes.map { it.mono })
        assertEquals(0.5, note.velocity)
        assertEquals(2 * 60.0 / 120 / 4, note.seconds, 1e-12, "Two frames at 120 bpm")
        assertEquals(261.6256, note.hz, 1e-3)
        assertEquals(OrcaCC(0, 65, 127, 8), ccs.single())
        assertEquals(OrcaBend(3, 4, 5, 8), bends.single())
        assertEquals(listOf("hello"), udp)
        assertEquals(OrcaOsc("/a", listOf(1, 2), "12", 8), osc.single())
        assertEquals(1, frames)
        assertEquals("D4\n.:03C", orca.grid); assertEquals(5, orca.width); assertEquals(9, orca.frame)
        assertTrue(orca.playing); assertEquals(mapOf("a" to "3"), orca.variables); assertEquals(2 to 1, orca.cursor)
        assertEquals(6, orca.recent.size)
    }

    @Test fun aNoteIsPlacedBackWhereOrcaPlayedItPlusTheLatency() {
        val orca = Orca(box(), "orca", null, null, false)
        val times = mutableListOf<Long>()
        orca.onNote { times += it.time }
        val before = System.nanoTime()
        // Played 100 ms before this report was sent: the note lands 100 ms before "now + latency".
        orca.receive(report("""{"frame":1,"bpm":120,"t":5000,"events":[{"kind":"note","at":4900,"channel":0,"octave":3,"note":"C","midi":60,"velocity":15,"length":1}]}"""))
        val after = System.nanoTime()
        val expected = (orca.latency * 1e9).toLong() - 100_000_000L
        assertTrue(times.single() - after <= expected && times.single() - before >= expected,
            "note time ${times.single() - before} ns from the report should be ${expected} ns")
    }

    @Test fun anEditIsSavedWithTheDocumentAndReopenedWithoutAGrid() {
        val player = box()
        val orca = Orca(player, "orca", null, null, false)
        orca.receive(report("""{"frame":1,"t":1,"events":[],"grid":"D8\n.;hi","edited":true}"""))
        assertEquals("D8\n.;hi", Orca.savedGrid(player, "orca"))
        assertNull(Orca.savedGrid(player, "other"))
        val reopened = Orca(player, "orca", Orca.savedGrid(player, "orca"), null, false)
        assertEquals("D8\n.;hi", commands(reopened.receive(report("""{"hello":true,"t":1,"events":[]}""")))[0]["grid"]!!.jsonPrimitive.content)
    }

    @Test fun aGridSetFromKotlinLosesItsIndentation() {
        val orca = Orca(box(), "orca", null, null, false)
        orca.grid = """
            D4
            .:03C
        """
        val load = commands(orca.receive(report("""{"t":1,"events":[]}"""))).single()
        assertEquals("D4\n.:03C", load["grid"]!!.jsonPrimitive.content)
    }

    @Test fun syncWaitsForTheReportAfterTheOneThatCarriedTheCommands() = runBlocking {
        val orca = Orca(box(), "orca", null, null, false)
        orca.command("bpm:150")
        val synced = async { orca.sync(5_000) }
        yield()
        orca.receive(report("""{"t":1,"events":[]}"""))
        yield()
        assertFalse(synced.isCompleted, "The first report only carries the command out")
        orca.receive(report("""{"t":2,"bpm":150,"events":[]}"""))
        assertTrue(synced.await())
        assertEquals(150, orca.bpm)
    }

    @Test fun aCallbackThatThrowsDoesNotStopTheOthers() {
        val orca = Orca(box(), "orca", null, null, false)
        var udp = 0
        orca.onUdp { kotlin.error("broken") }.onUdp { udp++ }
        orca.receive(report("""{"t":1,"events":[{"kind":"udp","message":"x"}]}"""))
        assertEquals(1, udp)
    }

    @Test fun aNoteSoundsOnAPatSynthBank() {
        val bank = PatSynthBank()
        bank.play(OrcaNote(0, 3, "C", 69, 1.0, 4, 0.1, false, 0, System.nanoTime()))
        val left = FloatArray(4096); val right = FloatArray(4096)
        bank.render(left, right, 4096, SynthContext(48_000f, 4096))
        assertTrue(left.any { it != 0f } && right.any { it != 0f }, "The note was rendered")
    }

    @Test fun cellsAreReadAndSetFromKotlinAndChangesAreReported() {
        val orca = Orca(box(), "orca", null, null, false)
        val seen = mutableListOf<OrcaCell>(); val watched = mutableListOf<Char>()
        orca.onChange { seen += it }.onCell(1, 1) { watched += it }
        orca.receive(report("""{"t":1,"events":[],"grid":"D4...\n....."}"""))
        assertTrue(seen.isEmpty(), "The first grid is the baseline, not a change")
        assertEquals('D', orca[0, 0]); assertEquals('.', orca[1, 1]); assertEquals('.', orca[40, 40])

        orca[1, 1] = 'C'
        assertEquals('C', orca[1, 1], "A set cell reads back at once")
        val sent = commands(orca.receive(report("""{"t":2,"events":[]}""")))
        assertEquals(listOf("set"), sent.map { it["op"]!!.jsonPrimitive.content })
        assertEquals("C", sent[0]["glyph"]!!.jsonPrimitive.content)
        assertEquals('C', orca[1, 1], "Still the set value while the report that carried it out is answered")

        orca.receive(report("""{"t":3,"events":[],"grid":"D4...\n.C..."}"""))
        assertEquals(listOf(OrcaCell(1, 1, '.', 'C')), seen)
        assertEquals(listOf('C'), watched)
        orca.receive(report("""{"t":4,"events":[],"grid":"D4...\n.d..."}"""))
        assertEquals('d', orca[1, 1], "Once reported, the cell is whatever ORCA says")
        assertEquals(listOf('C', 'd'), watched)
    }

    @Test fun aSetFromACallbackGoesOutInTheSameReply() {
        val orca = Orca(box(), "orca", null, null, false)
        orca.onNote { orca.bang(it.frame, 0) }
        val sent = commands(orca.receive(report("""{"t":1,"events":[{"kind":"note","frame":3,"midi":60}]}""")))
        assertEquals("*", sent.single()["glyph"]!!.jsonPrimitive.content)
        assertEquals(3, sent.single()["x"]!!.jsonPrimitive.int)
    }

    @Test fun variablesAreHeldAndReleased() {
        val orca = Orca(box(), "orca", null, null, false)
        orca.variable('a', '5'); orca.variable('a', null)
        val sent = commands(orca.receive(report("""{"t":1,"events":[]}""")))
        assertEquals(listOf("5", null), sent.map { it["value"]!!.jsonPrimitive.contentOrNull })
        assertTrue(sent.all { it["op"]!!.jsonPrimitive.content == "variable" && it["name"]!!.jsonPrimitive.content == "a" })
    }

    @Test fun evalSendsJavaScriptAndReturnsWhatThePageAnswers() = runBlocking {
        val orca = Orca(box(), "orca", null, null, false)
        val answer = async { orca.eval("client.orca.w") }
        yield()
        val sent = commands(orca.receive(report("""{"t":1,"events":[]}"""))).single()
        assertEquals("eval", sent["op"]!!.jsonPrimitive.content)
        assertEquals("client.orca.w", sent["js"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.content
        orca.receive(report("""{"t":2,"events":[],"results":[{"id":"$id","value":{"w":64}}]}"""))
        assertEquals(64, answer.await().jsonObject["w"]!!.jsonPrimitive.int)

        val failing = async { runCatching { orca.eval("nope()") } }
        yield()
        val failId = commands(orca.receive(report("""{"t":3,"events":[]}"""))).single()["id"]!!.jsonPrimitive.content
        orca.receive(report("""{"t":4,"events":[],"results":[{"id":"$failId","error":"ReferenceError: nope is not defined"}]}"""))
        assertTrue(failing.await().exceptionOrNull()!!.message!!.contains("nope is not defined"))

        orca.js("client.clock.stop()")
        val fire = commands(orca.receive(report("""{"t":5,"events":[]}"""))).single()
        assertNull(fire["id"], "js() does not ask for an answer")
    }

    @Test fun aSecondPageIsAStoppedMirrorUntilThePlayerGoesQuiet() {
        val orca = Orca(box(), "orca", "D4\n.:03C", null, false)
        val notes = mutableListOf<String>()
        orca.onNote { notes += "${it.frame}" }
        orca.receive(report("""{"page":"tab","hello":true,"t":1,"events":[],"grid":"D4\n.:03C","paused":false}"""))
        orca.write(0, 3, "#x#")

        // AI4's capture browser opens the same output: it is stopped and shown the player's grid,
        // its own notes are ignored, and the box's write still goes to the tab.
        val mirror = commands(orca.receive(report("""{"page":"capture","hello":true,"t":1,"events":[{"kind":"note","frame":0,"midi":60}],"grid":"."}""")))
        assertEquals(listOf("guide", "stop", "load"), mirror.map { it["op"]!!.jsonPrimitive.content })
        assertEquals("D4\n.:03C", mirror[2]["grid"]!!.jsonPrimitive.content)
        assertTrue(notes.isEmpty())
        assertEquals(listOf("write"), commands(orca.receive(report("""{"page":"tab","t":2,"events":[]}"""))).map { it["op"]!!.jsonPrimitive.content })
        assertTrue(commands(orca.receive(report("""{"page":"capture","t":2,"events":[]}"""))).isEmpty(), "an up-to-date mirror is left alone")

        // The tab closes; the mirror takes over, is given the grid again and set playing.
        orca.playerTimeoutNanos = 0
        val takeover = commands(orca.receive(report("""{"page":"capture","t":3,"events":[{"kind":"note","frame":5,"midi":60}]}""")))
        assertEquals(listOf("load", "play"), takeover.map { it["op"]!!.jsonPrimitive.content })
        assertEquals(listOf("5"), notes)
    }

    @Test fun exposeToolsRegistersTheOrcaTools() {
        val orca = Orca(box(), "orca", null, null, false)
        assertEquals(listOf("orca_read", "orca_write", "orca_load", "orca_command", "orca_js"),
            orca.exposeTools(iframeTools = false).map { it.name })
    }
}
