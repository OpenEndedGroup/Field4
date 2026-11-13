package afield.tools.docs

import afield.app.protocol.AgentPageFilm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The timeline file format ([FilmTimeline]) and the frame loop's pure parts (pageFilm.kt). */
class FilmTimelineTest {
    private fun timeline(tracks: String, stage: String = STAGE) =
        FilmTimeline.parse("""{"format": "field-film-timeline/1", "duration": 4, "fps": 10, "stage": $stage, "tracks": [$tracks]}""")

    private fun JsonObject.view(name: String) = this["views"]!!.jsonObject[name]!!.jsonObject
    private fun JsonObject.vec(name: String) = this[name]!!.jsonArray.map { it.jsonPrimitive.double }
    private fun close(a: List<Double>, b: List<Double>, tol: Double = 1e-9) =
        assertTrue(a.indices.all { abs(a[it] - b[it]) <= tol }, "$a is not $b")

    @Test
    fun theExampleTimelineInTheDocsParses() {
        val file = File("public/docs/assets/filming_a_timeline/hinge.film.json")
        val t = FilmTimeline.parse(file.readText())
        assertEquals(10.0, t.duration)
        assertEquals(1280, t.width)
        assertEquals(setOf("model"), t.views)
        assertEquals(setOf("Hinge"), t.boxes)
        assertEquals(listOf("model", "plan", "slider", "size"), t.stage!!.regions.map { it.id })
    }

    @Test
    fun theDocsPageShowsTheExampleTimelineAsItIs() {
        val page = File("public/docs/filming_a_timeline.md").readText()
        assertTrue(File("public/docs/assets/filming_a_timeline/hinge.film.json").readText().trim() in page,
            "filming_a_timeline.md shows a timeline other than assets/filming_a_timeline/hinge.film.json: paste the file in")
    }

    @Test
    fun aTimelineThatDoesNotSayWhatItMeansNamesThePlace() {
        fun fails(json: String, says: String) {
            val e = assertFailsWith<FilmTimelineException> { FilmTimeline.parse(json) }
            assertTrue(says in e.message!!, "\"${e.message}\" does not say \"$says\"")
        }
        fails("[]", "a JSON object")
        fails("""{"format": "something/2"}""", "format is")
        fails("""{"durration": 3}""", "unknown field \"durration\"")
        fails("""{"tracks": [{"camera": "nowhere", "keys": [{"t": 0, "pose": {"eye": [0,0,1], "target": [0,0,0]}}]}]}""", "no stage region is called \"nowhere\"")
        fails("""{"stage": $STAGE, "tracks": [{"camera": "model", "keys": [{"t": 2, "pose": {"eye": [0,0,1], "target": [0,0,0]}}, {"t": 1, "pose": {"eye": [0,0,1], "target": [0,0,0]}}]}]}""", "tracks[0].keys: keys go in time order")
        fails("""{"stage": $STAGE, "tracks": [{"camera": "model", "keys": [{"t": 0, "pose": {"eye": [0,0,1], "target": [0,0,0]}, "ease": "bouncy"}]}]}""", "ease is one of")
        fails("""{"tracks": [{"drag": {"box": "b"}, "keys": [{"t": 0, "release": true}]}]}""", "a release follows an at key")
        fails("""{"tracks": [{"drag": {"box": "b"}, "keys": [{"t": 0, "at": [1]}]}]}""", "at is [x, y]")
        fails("""{"tracks": [{"motion": {"box": "b"}, "keys": [{"t": 0, "value": 2}]}]}""", "a motion value is 0 to 1")
        fails("""{"tracks": [{"camera": {"box": "b"}, "parts": {"box": "b"}, "keys": []}]}""", "names one of")
        fails("""{"stage": {"regions": [{"id": "a", "box": "b", "rect": [0, 0, 0, 10]}]}}""", "stage.regions[0]: rect is")
    }

    @Test
    fun easesGoFromZeroToOneAndInOutIsTheViewersSmoothstep() {
        for (e in FilmEase.entries) { assertEquals(0.0, e.at(0.0)); assertEquals(1.0, e.at(1.0)) }
        assertEquals(0.5, FilmEase.IN_OUT.at(0.5))
        assertEquals(0.25, FilmEase.IN.at(0.5))
        assertEquals(0.0, FilmEase.STEP.at(0.99))
    }

    @Test
    fun aPoseIsEasedIntoFromTheKeyBeforeAndAnArcLiftsItMidway() {
        val t = timeline("""{"camera": "model", "keys": [
            {"t": 0, "pose": {"eye": [0, 0, 10], "target": [0, 0, 0], "up": [0, 1, 0]}},
            {"t": 2, "pose": {"eye": [10, 0, 10], "target": [2, 0, 0], "up": [0, 1, 0]}, "ease": "inOut", "arc": [0, 4, 0]}]}""")
        val mid = t.frame(10, 1.0, 0.9).view("model")["camera"]!!.jsonObject
        close(mid.vec("eye"), listOf(5.0, 4.0, 10.0))
        close(mid.vec("target"), listOf(1.0, 0.0, 0.0))
        close(t.frame(20, 2.0, 1.9).view("model")["camera"]!!.jsonObject.vec("eye"), listOf(10.0, 0.0, 10.0))
        close(t.frame(39, 3.9, 3.8).view("model")["camera"]!!.jsonObject.vec("eye"), listOf(10.0, 0.0, 10.0))
    }

    @Test
    fun anOrbitWithNoEyeTurnsTheViewsOwnCameraAsTheViewersFilmDoes() {
        val t = timeline("""{"camera": "model", "keys": [{"t": 0.5, "orbit": {"degreesPerSecond": 90}}]}""")
        val initial = FilmPose(listOf(10.0, 3.0, 0.0), listOf(0.0, 3.0, 0.0), listOf(0.0, 1.0, 0.0))
        assertNull(t.frame(0, 0.0, null, mapOf("model" to initial)).view("model")["camera"], "before its first key a view's camera is its own")
        // One second in: a quarter turn about +y, by minus the angle (turnCamera's sense).
        val a = -PI / 2
        val eye = t.frame(15, 1.5, 1.4, mapOf("model" to initial)).view("model")["camera"]!!.jsonObject.vec("eye")
        close(eye, listOf(10 * cos(a), 3.0, -10 * sin(a)), 1e-9)
    }

    @Test
    fun anOrbitThatFollowsAPoseStartsWhereThePoseLeftTheCamera() {
        val t = timeline("""{"camera": "model", "keys": [
            {"t": 0, "pose": {"eye": [0, 0, 5], "target": [0, 0, 0], "up": [0, 1, 0]}},
            {"t": 1, "orbit": {"degreesPerSecond": 180}, "ease": "step"}]}""")
        close(t.frame(10, 1.0, 0.9).view("model")["camera"]!!.jsonObject.vec("eye"), listOf(0.0, 0.0, 5.0))
        close(t.frame(20, 2.0, 1.9).view("model")["camera"]!!.jsonObject.vec("eye"), listOf(0.0, 0.0, -5.0), 1e-9)
    }

    @Test
    fun partsKeysAccumulateInOrderByIdOrLayer() {
        val t = timeline("""{"parts": "model", "keys": [{"t": 1, "hide": ["lid"]}, {"t": 2, "show": {"layer": ["Lid"]}}]}""")
        assertNull(t.frame(5, 0.5, 0.4).view("model")["parts"])
        assertEquals(1, t.frame(15, 1.5, 1.4).view("model")["parts"]!!.jsonArray.size)
        val ops = t.frame(25, 2.5, 2.4).view("model")["parts"]!!.jsonArray
        assertEquals(listOf(true, false), ops.map { it.jsonObject["hidden"]!!.jsonPrimitive.content.toBoolean() })
        assertEquals("Lid", ops[1].jsonObject["layers"]!!.jsonArray[0].jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun aMotionTakesValuesEasedBetweenKeysPlaysTheViewersSwingAndRests() {
        val t = timeline("""{"motion": "model", "keys": [
            {"t": 0, "value": 0}, {"t": 1, "value": 1, "ease": "linear"},
            {"t": 1.5, "play": {"period": 2, "shape": "swing"}}, {"t": 3.5, "rest": true, "tracks": true}]}""")
        assertEquals(0.25, t.frame(2, 0.25, 0.15).view("model")["motion"]!!.jsonObject["value"]!!.jsonPrimitive.double, 1e-12)
        val swing = t.frame(20, 2.0, 1.9).view("model")["motion"]!!.jsonObject["value"]!!.jsonPrimitive.double
        assertEquals(timelineAt("swing", 0.25), swing, 1e-12)
        val rest = t.frame(36, 3.6, 3.5).view("model")
        assertEquals("true", rest["motion"]!!.jsonObject["rest"]!!.jsonPrimitive.content)
        assertEquals("true", rest["tracks"]!!.jsonPrimitive.content)
    }

    @Test
    fun theViewersTimelineShapesAreMirrored() {
        assertEquals(0.0, timelineAt("swing", 0.0)); assertEquals(1.0, timelineAt("swing", 0.45))
        assertEquals(0.5, timelineAt("swing", 0.2), 1e-12); assertEquals(0.0, timelineAt("swing", 0.95))
        assertEquals(0.25, timelineAt("loop", 1.25), 1e-12); assertEquals(1.0, timelineAt("once", 3.0))
    }

    private val drag = """{"drag": {"box": "b", "channel": "plan"}, "until": {"view": "model", "metadata": "edge", "of": "x"}, "keys": [
        {"t": 1.0, "at": [10, 0]}, {"t": 2.0, "at": [20, 0]}, {"t": 2.5, "release": true},
        {"t": 3.0, "at": [5, 5]}, {"t": 3.2, "at": [6, 5]}]}"""

    private fun actions(t: FilmTimeline, frames: List<Double>, from: Double? = null): List<String> {
        var before: Double? = from
        return frames.flatMap { now -> t.serverActions(before, now).map { "${it.phase}@${it.x},${it.y}" }.also { before = now } }
    }

    @Test
    fun aDragIsAPressMovesAndAReleaseInTheDrawingsOwnCoordinates() {
        val t = timeline(drag)
        val frames = (0..40).map { it / 10.0 }
        val made = actions(t, frames)
        assertEquals("start@10.0,0.0", made.first())
        // Pressed through its last key, let go at its release: nothing moves after 2.0 until the release.
        assertEquals(listOf("move@20.0,0.0", "end@20.0,0.0", "start@5.0,5.0"), made.dropWhile { it != "move@20.0,0.0" }.take(3))
        // The second gesture has no release key: it lets go just after its last key.
        assertEquals("end@6.0,5.0", made.last())
        assertEquals(2, made.count { it.startsWith("start") }); assertEquals(2, made.count { it.startsWith("end") })
        assertEquals("b", t.serverActions(null, 1.0).single().box)
    }

    @Test
    fun aGestureBetweenTwoFramesIsStillMadeAndAJumpAcrossFramesSkipsNoRelease() {
        val t = timeline(drag)
        assertEquals(listOf("start@10.0,0.0", "move@20.0,0.0", "end@20.0,0.0", "start@5.0,5.0"), actions(t, listOf(0.0, 3.0)))
        // A film that starts after a whole gesture still makes it first, so the box is where that gesture left it.
        assertEquals(listOf("start@10.0,0.0", "move@20.0,0.0", "end@20.0,0.0", "start@5.0,5.0", "move@6.0,5.0", "end@6.0,5.0"),
            actions(t, listOf(2.9, 4.0)))
    }

    @Test
    fun replayingFramesInOrderMakesTheSameDragsAsFilmingThem() {
        val t = timeline(drag)
        val frames = (0 until 40).map { canvasFlightFrameTime(it, 40, 10, 4.0) }
        // A resume from frame 25 replays 0..24 and then films 25 on: the same actions, in the same order.
        assertEquals(actions(t, frames), actions(t, frames.take(25)) + actions(t, frames.drop(25), from = frames[24]))
    }

    @Test
    fun aFrameWaitsForTheViewToShowADragOrControlOnlyWhenItMoved() {
        val t = timeline(drag + """, {"control": {"box": "b", "label": "depth"}, "until": {"view": "model", "metadata": "depth"},
            "keys": [{"t": 1, "value": 10}, {"t": 2, "value": 20, "ease": "inOut"}]}""")
        val pressed = t.frame(15, 1.5, 1.4)["waits"]!!.jsonArray
        assertEquals(2, pressed.size)
        assertEquals(15.0, pressed[0].jsonObject["value"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("depth", pressed[1].jsonObject["control"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertNull(t.frame(24, 2.4, 2.3)["waits"], "a held drag and a still control wait for nothing")
        assertEquals(20.0, (t.frame(24, 2.4, 2.3)["controls"] as JsonArray)[0].jsonObject["value"]!!.jsonPrimitive.double)
    }

    @Test
    fun aCaptionFadesInAndOutOverItsFade() {
        val t = timeline("""{"captions": [{"t": 1, "text": "one", "fade": 0.5}, {"t": 3, "text": "two"}]}""")
        assertNull(t.frame(5, 0.5, 0.4)["caption"])
        assertEquals(0.5, t.frame(12, 1.25, 1.2)["caption"]!!.jsonObject["opacity"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(1.0, t.frame(20, 2.0, 1.9)["caption"]!!.jsonObject["opacity"]!!.jsonPrimitive.double)
        assertEquals(0.5, t.frame(27, 2.75, 2.7)["caption"]!!.jsonObject["opacity"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("two", t.frame(30, 3.0, 2.9)["caption"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun boxesAreNamedByAliasNameOrUidAndAnUnknownOneIsAnError() {
        val boxes = listOf("u-1" to "Hinge", "u-2" to "Other", "u-3" to "Other")
        val text = """{"stage": {"regions": [{"id": "a", "box": "Hinge", "rect": [0,0,1,1]}, {"id": "b", "box": "@h", "rect": [0,0,1,1]}, {"id": "c", "box": "u-2", "rect": [0,0,1,1]}]}}"""
        val resolved = FilmTimeline.parse(resolveTimelineBoxes(text, boxes, mapOf("h" to "u-1")))
        assertEquals(listOf("u-1", "u-1", "u-2"), resolved.stage!!.regions.map { it.box })
        assertTrue("its boxes: Hinge, Other, Other" in assertFailsWith<FilmTimelineException> {
            resolveTimelineBoxes("""{"tracks": [{"drag": {"box": "Nope"}, "keys": [{"t": 0, "at": [0, 0]}]}]}""", boxes)
        }.message!!)
        assertTrue("2 boxes" in assertFailsWith<FilmTimelineException> { resolveTimelineBoxes("""{"x": {"box": "Other"}}""", boxes) }.message!!)
        assertEquals(text, resolveTimelineBoxes(text, null))
    }

    @Test
    fun theSessionUrlIsTheDocumentsCapturePageAsAFilmClient() {
        val doc = "https://localhost:9600/index.html?file=scratch&user=playwright&scratch=1&sessionKey=%2Fk"
        assertEquals("https://localhost:9600/index.html?file=scratch&user=film&scratch=1&sessionKey=%2Fk", sessionFilmUrl(doc, ""))
        assertEquals("https://localhost:9600/index.html?file=scratch&user=film&scratch=1&sessionKey=%2Fk&bg=1", sessionFilmUrl(doc, "?bg=1"))
        assertEquals("https://example.com/a", resolveFilmUrl("https://example.com/a", null))
        assertFailsWith<IllegalArgumentException> { resolveFilmUrl("@session", null) }
    }

    @Test
    fun aRequestTakesTheTimelinesDurationRateAndSizeUnlessItSetsThem() {
        val t = timeline("")
        val unset = AgentPageFilm(url = "@session", path = "/tmp/x.mp4", durationSec = 0.0, fps = 0, width = 0, height = 0, pixelRatio = 0.0)
        val filled = unset.withTimelineDefaults(t)
        assertEquals(listOf(4.0, 10.0, 1920.0, 1080.0, 1.0), listOf(filled.durationSec, filled.fps.toDouble(), filled.width.toDouble(), filled.height.toDouble(), filled.pixelRatio))
        assertEquals(12, unset.copy(fps = 12).withTimelineDefaults(t).fps)
    }

    @Test
    fun aResumeStartsAtTheFirstMissingFrameAndRefusesAnotherFilmsFolder() {
        val dir = Files.createTempDirectory("film-frames")
        try {
            for (i in listOf(0, 1, 2, 4)) Files.write(filmFrameFile(dir, i), byteArrayOf(1))
            Files.write(dir.resolve("frame_000003.jpg.part"), byteArrayOf(1))
            assertEquals(3, firstMissingFrame(dir, 10), "a half-written frame is made again")
            assertEquals(0, firstMissingFrame(Files.createTempDirectory("film-empty"), 10))
            val a = AgentPageFilm(url = "u", path = "p", durationSec = 1.0, timeline = "{}")
            assertEquals(emptyList(), manifestDifferences(filmManifest(a, 30), filmManifest(a.copy(url = "other", path = "q"), 30)))
            assertEquals(listOf("width", "timeline"), manifestDifferences(filmManifest(a, 30), filmManifest(a.copy(width = 640, timeline = "{ }"), 30)))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun progressRatesComeFromTheLastFramesOnly() {
        var now = 0L
        val rate = FilmRate(window = 2) { now }
        assertNull(rate.perSecond)
        rate.tick(); now += 1_000_000_000; rate.tick()
        assertEquals(1.0, rate.perSecond)
        now += 100_000_000; rate.tick(); now += 100_000_000; rate.tick()
        assertEquals(10.0, rate.perSecond!!, 1e-9)
        assertEquals(1.2, rate.elapsed, 1e-9)
    }

    private companion object {
        const val STAGE = """{"regions": [{"id": "model", "box": "b", "channel": "model", "rect": [0, 0, 640, 480]}]}"""
    }
}
