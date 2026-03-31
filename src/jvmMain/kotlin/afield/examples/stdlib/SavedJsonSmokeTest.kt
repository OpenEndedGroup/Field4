package afield.examples.stdlib

import afield.Box
import afield.SessionInformation
import afield.graphics.FLine
import afield.stdlib.Vec3
import afield.stdlib.color
import afield.stdlib.saved
import afield.stdlib.vec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/*
FIELD_TEST
id: stdlib.saved-json.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.SavedJsonSmokeTest
tags: [smoke, stdlib, persistence, json, fline]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "SavedJson smoke tests passed"
*/
object SavedJsonSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "SavedJson smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            testDefaultIsSavedAndReloaded()
            testSetWritesUpdatedJson()
            testVec3ListsRoundTrip()
            testCollectionDefaults()
            println("SavedJson smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    private fun testDefaultIsSavedAndReloaded() {
        val dir = Files.createTempDirectory("saved-json-default").toFile()
        val box = boxIn(dir, "saved_box_default")

        Box.withBoxContextNow(box) {
            var foo: List<FLine> by saved {
                listOf(sampleLine(1f, "default"))
            }

            val loaded = foo
            require(loaded.size == 1) { "Expected default list to contain one line" }
            require((loaded.first().properties["label"] as? String) == "default") { "Default value did not materialize" }

            val file = File(dir, "${box.id}/foo.json")
            require(file.exists()) { "Expected default value to be saved to ${file.absolutePath}" }
        }

        Box.withBoxContextNow(box) {
            var foo: List<FLine> by saved {
                error("Default should not run when foo.json already exists")
            }

            val reloaded = foo
            require(reloaded.size == 1) { "Expected saved list to reload" }
            require((reloaded.first().properties["label"] as? String) == "default") { "Reloaded value did not match saved default" }
        }
    }

    private fun testSetWritesUpdatedJson() {
        val dir = Files.createTempDirectory("saved-json-set").toFile()
        val box = boxIn(dir, "saved_box_set")

        Box.withBoxContextNow(box) {
            var foo: List<FLine> by saved {
                listOf(sampleLine(1f, "initial"))
            }

            foo = listOf(sampleLine(2f, "updated"), sampleLine(3f, "second"))

            val file = File(dir, "${box.id}/foo.json")
            require(file.exists()) { "Expected updated value to be saved" }
        }

        Box.withBoxContextNow(box) {
            var foo: List<FLine> by saved {
                error("Default should not run after set wrote foo.json")
            }

            val reloaded = foo
            require(reloaded.size == 2) { "Expected updated list to reload with two entries" }
            require((reloaded[0].properties["label"] as? String) == "updated") { "First updated line missing" }
            require((reloaded[1].properties["label"] as? String) == "second") { "Second updated line missing" }

            val line = reloaded[0]
            requireColor(line.color, 0.2f, 0.4f, 0.8f, 1f)
        }
    }

    private fun testVec3ListsRoundTrip() {
        val dir = Files.createTempDirectory("saved-json-vec3").toFile()
        val box = boxIn(dir, "saved_box_vec3")

        Box.withBoxContextNow(box) {
            var foo: List<Vec3> by saved {
                listOf(vec(1f, 2f, 3f))
            }

            requireVec3(foo.single(), 1f, 2f, 3f, "default vec3")
            foo = foo + vec(4f, 5f, 6f)

            val file = File(dir, "${box.id}/foo.json")
            require(file.exists()) { "Expected vec3 list to be saved" }
        }

        Box.withBoxContextNow(box) {
            var foo: List<Vec3> by saved {
                error("Default should not run after vec3 list was saved")
            }

            require(foo.size == 2) { "Expected vec3 list to reload with two entries" }
            requireVec3(foo[0], 1f, 2f, 3f, "reloaded vec3[0]")
            requireVec3(foo[1], 4f, 5f, 6f, "reloaded vec3[1]")
        }
    }

    private fun testCollectionDefaults() {
        val dir = Files.createTempDirectory("saved-json-default-collections").toFile()
        val box = boxIn(dir, "saved_box_default_collections")

        Box.withBoxContextNow(box) {
            var lines: List<Vec3> by saved()
            var names: Set<String> by saved()
            var lookup: Map<String, Int> by saved()

            require(lines.isEmpty()) { "Expected saved() list default to be empty" }
            require(names.isEmpty()) { "Expected saved() set default to be empty" }
            require(lookup.isEmpty()) { "Expected saved() map default to be empty" }

            require(File(dir, "${box.id}/lines.json").exists()) { "Expected empty list default to be saved" }
            require(File(dir, "${box.id}/names.json").exists()) { "Expected empty set default to be saved" }
            require(File(dir, "${box.id}/lookup.json").exists()) { "Expected empty map default to be saved" }
        }

        Box.withBoxContextNow(box) {
            var lines: List<Vec3> by saved()
            var names: Set<String> by saved()
            var lookup: Map<String, Int> by saved()

            require(lines.isEmpty()) { "Expected saved empty list to reload" }
            require(names.isEmpty()) { "Expected saved empty set to reload" }
            require(lookup.isEmpty()) { "Expected saved empty map to reload" }
        }
    }

    private fun boxIn(documentDir: File, id: String): Box {
        val root = Box("root")
        val session = SessionInformation(
            filename = documentDir.absolutePath,
            documentName = documentDir.name,
            boxes = ConcurrentHashMap(),
            root = root
        )
        root.inside = session
        session.boxes[root.id] = root

        val box = Box(id)
        box.inside = session
        session.boxes[box.id] = box
        return box
    }

    private fun sampleLine(x: Float, label: String): FLine {
        return FLine().apply {
            moveTo(x, x + 1f, x + 2f)
            lineTo(x + 3f, x + 4f, x + 5f)
            properties["label"] = label
            color = vec(0.2f, 0.4f, 0.8f, 1f)
        }
    }

    private fun requireColor(value: Any?, r: Float, g: Float, b: Float, a: Float) {
        val color = value ?: error("Missing color")
        val klass = color::class.java
        val x = klass.getMethod("x").invoke(color) as Float
        val y = klass.getMethod("y").invoke(color) as Float
        val z = klass.getMethod("z").invoke(color) as Float
        val w = klass.getMethod("w").invoke(color) as Float
        require(abs(x - r) < 1e-6f && abs(y - g) < 1e-6f && abs(z - b) < 1e-6f && abs(w - a) < 1e-6f) {
            "Unexpected color: ($x, $y, $z, $w)"
        }
    }

    private fun requireVec3(value: Vec3, x: Float, y: Float, z: Float, label: String) {
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f && abs(value.z - z) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y}, ${value.z})"
        }
    }
}
