package afield.examples

import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler
import java.io.File

/*
FIELD_TEST
id: docs.kotlin-in-the-head.emitted-js
runner: jvm-main
entrypoint: afield.examples.KotlinInTheHeadEvidence
tags: [docs, remotejs, kotlinjs]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinInTheHeadEvidence ok"
*/

/**
 * Regenerates the emitted-JavaScript evidence embedded in `public/docs/kotlin_in_the_head.md`.
 *
 * The whole honesty point of that page: the WebXR/audio payoff of a Kotlin/JS head is NOT headlessly
 * capturable (it needs a real browser / Vision head + a user gesture), but the claim "your Kotlin
 * really became JavaScript" IS reproducible — it is exactly what [KotlinToJsCompiler] emits.
 *
 * This drives the REAL compiler on the exact worked-example source the doc shows (the `V` vector
 * data class with `operator fun plus`/`times`, through the real `remoteAR` profile via
 * [KotlinJsHeadProfiles.remoteAr.prepareSource]) and dumps the emitted module so the page can quote a
 * faithful excerpt. Output goes only under `build/` (never a committed asset); the page author copies
 * the relevant slice by hand. Also compiles a Tone example so the doc's second profile is proven too.
 */
object KotlinInTheHeadEvidence {

    // The `vectors` box from the doc, verbatim. `data class` + operator overloads + string templates +
    // the RemoteAR `log(...)` helper — all real Kotlin, all bound by RemoteArKotlinJs.
    private val vectorsSource = """
        // {{ remoteAR }}
        data class V(val x: Double, val y: Double) {
            operator fun plus(o: V) = V(x + o.x, y + o.y)
            operator fun times(k: Double) = V(x * k, y * k)
        }

        val a = V(1.0, 2.0)
        val b = V(10.0, 20.0)
        log("sum = ${'$'}{a + b}")
        log("scaled = ${'$'}{a * 3.0}")
    """.trimIndent()

    // The `ring` box: builds real THREE geometry and animates it on the head's frame clock. Uses the
    // bound THREE.Vector3 `+`/`*` operators, addBox/scene/onFrame — none of this is faked in raw JS.
    private val ringSource = """
        // {{ remoteAR }}
        clearAll()
        val n = 12
        val ring = (0 until n).map { i ->
            val theta = i.toDouble() / n * 2.0 * kotlin.math.PI
            val here = vec(kotlin.math.cos(theta) * 2.0, kotlin.math.sin(theta) * 2.0, 0.0)
            val box = addBox("ring-${'$'}i", 0.25).mesh
            box.position.set(here.x, here.y, here.z)
            box
        }
        onFrame { frame ->
            ring.forEachIndexed { i, box ->
                box.rotation.y = frame.time + i.toDouble() * 0.2
            }
        }
        log("ring installed: ${'$'}{ring.size} boxes")
    """.trimIndent()

    // The `bells` box (Tone.js head): a scheduled arpeggio built from real Tone nodes.
    private val bellsSource = """
        // {{ tone }}
        onStart {
            val synth = Tone.PolySynth()
            val reverb = Tone.Reverb(3.0)
            reverb.toDestination()
            synth.connect(reverb)
            val notes = listOf("C4", "E4", "G4", "B4")
            val transport = Tone.getTransport()
            transport.bpm.value = 96.0
            var step = 0
            val loop = transport.scheduleRepeat({ time ->
                synth.triggerAttackRelease(notes[step % notes.size], "8n", time)
                step += 1
            }, "8n")
            Tone.start()
            transport.start()
            onStop {
                transport.clear(loop)
                transport.stop()
                synth.dispose()
                reverb.dispose()
            }
        }
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File("build/field-tests/kotlin_in_the_head").apply { mkdirs() }

        val vectors = compileThroughProfile(vectorsSource, KotlinJsHeadProfiles.remoteAr, "vectors")
        emit(outDir, "vectors", vectorsSource, vectors)

        val ring = compileThroughProfile(ringSource, KotlinJsHeadProfiles.remoteAr, "ring")
        emit(outDir, "ring", ringSource, ring)

        val bells = compileThroughProfile(bellsSource, KotlinJsHeadProfiles.tone, "bells")
        emit(outDir, "bells", bellsSource, bells)

        // Sanity: the `V` data class and its operators really made it into the emitted JS (not
        // tree-shaken away, not faked) — this is the exact evidence the doc quotes. The plain module
        // lowers the class to `function main$V(...)` with prototype methods carrying Kotlin's mangled
        // JS names (`plus_…_k$` / `times_…_k$`), and DCE keeps only the members `main` actually uses.
        val v = vectors.js!!
        require(v.contains("function main\$V")) { "expected `function main\$V` in the emitted JS" }
        require(v.contains("protoOf(main\$V).plus_")) { "expected the plus operator method on main\$V" }
        require(v.contains("protoOf(main\$V).times_")) { "expected the times operator method on main\$V" }

        println("KotlinInTheHeadEvidence ok")
    }

    private fun compileThroughProfile(
        source: String,
        profile: afield.remotejs.KotlinJsHeadProfile,
        module: String,
    ): KotlinToJsCompiler.Result {
        val selection = KotlinJsHeadProfiles.resolve(source)
        require(selection.profile === profile) {
            "$module resolved to ${selection.profile.id}, expected ${profile.id}"
        }
        // prepareSource() does implicit-main wrapping + appends the profile's generated declarations —
        // the exact real path a live box takes before K2→JS.
        val prepared = profile.prepareSource(selection.source)
        val result = KotlinToJsCompiler.compile(prepared, moduleName = module, wrapImplicitMain = false)
        require(result.success && result.js != null) {
            "$module did not compile through the real K2→JS pipeline:\n${result.rawLog.takeLast(2000)}"
        }
        return result
    }

    private fun emit(dir: File, name: String, kotlin: String, result: KotlinToJsCompiler.Result) {
        File(dir, "$name.kt").writeText(kotlin)
        File(dir, "$name.js").writeText(result.js!!)
        println("---- $name : ${kotlin.length} chars Kotlin -> ${result.js.length} chars JS (mode=${result.mode}) ----")
        println(result.js)
        println("---- end $name ----")
    }
}
