package afield.examples.petri

import afield.layout.D2Layout
import afield.petri.PetriLayout
import afield.petri.PetriDrawing
import afield.petri.arcCrossings
import afield.petri.PetriNet
import afield.petri.clearPetriLayoutCache
import afield.petri.frame
import afield.petri.petri
import afield.petri.toSvg
import java.io.File

/*
FIELD_TEST
id: petri.tala.comparison
runner: jvm-main
entrypoint: afield.examples.petri.PetriTalaComparison
tags: [petri, layout, tala, d2, demo]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "petri tala comparison ok"
*/

/**
 * Draws one net twice — [PetriLayout.FORCE] and [PetriLayout.TALA] — side by side, and writes the
 * result to `build/petri-tala/comparison.html`.
 *
 * This is the demo for `native/field-d2-layout`, and it is also the honest test of what that
 * helper is *for*: dining philosophers is the net that made the spring model produce a hairball,
 * so putting the two drawings next to each other is the only way to judge whether the second
 * engine earns its keep.
 *
 * It passes either way. With the helper built you get two different drawings; without it you get
 * the same drawing twice, because TALA falls through to FORCE — and that fallback is the property
 * worth pinning, since Field must run on a machine with no Go toolchain. What it will not tolerate
 * is the helper being present and silently producing FORCE's answer anyway.
 */
object PetriTalaComparison {

    private fun philosophers(count: Int = 5): PetriNet = petri {
        for (index in 0 until count) place("fork.$index", tokens = 1)
        for (index in 0 until count) {
            val right = (index + 1) % count
            place("think.$index", tokens = 1)
            place("eat.$index")
            t("take.$index") {
                from("think.$index"); from("fork.$index"); from("fork.$right"); to("eat.$index")
            }
            t("drop.$index") {
                from("eat.$index"); to("think.$index"); to("fork.$index"); to("fork.$right")
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val net = philosophers()
        val available = D2Layout.available
        println("helper: ${if (available) "present, d2 ${D2Layout.version}" else "absent — TALA will fall back"}")

        val w = 560.0
        val h = 460.0

        // Timing the two layouts once each, in order, is the obvious thing and it lies: the first
        // one pays for JVM warmup and the second does not, which on this net flattered TALA by
        // roughly a factor of ten. So both are warmed, then measured three times from a cleared
        // cache, and the minimum is reported -- the reading least polluted by whatever else the
        // machine is doing.
        fun best(layout: PetriLayout): Pair<PetriDrawing, Long> {
            var drawing = net.frame(layout)
            var bestNs = Long.MAX_VALUE
            repeat(3) {
                // BOTH caches: clearing only the Petri one leaves D2Layout's request cache warm
                // and TALA times at 0 ms, because it never reaches the subprocess.
                clearPetriLayoutCache()
                D2Layout.clearCache()
                val at = System.nanoTime()
                drawing = net.frame(layout)
                bestNs = minOf(bestNs, System.nanoTime() - at)
            }
            return drawing to bestNs / 1_000_000
        }

        val (force, forceMs) = best(PetriLayout.FORCE)
        val (tala, talaMs) = best(PetriLayout.TALA)

        val differ = net.placeNames.any { force.position(it) != tala.position(it) }

        val forceCrossings = force.arcCrossings()
        val talaCrossings = tala.arcCrossings()
        println("best of 3, warmed: FORCE ${forceMs}ms, TALA ${talaMs}ms, drawings differ: $differ")
        println("drawn arc crossings: FORCE $forceCrossings, TALA $talaCrossings")

        check(!available || differ) {
            "the helper is present but TALA reproduced FORCE exactly — it fell back silently"
        }
        check(available || !differ) {
            "the helper is absent but TALA did not fall through to FORCE"
        }

        // build/ is gitignored, so this leaves nothing behind in the tree.
        val out = File("build/petri-tala")
        out.mkdirs()
        val page = File(out, "comparison.html")
        page.writeText(
            """
            <!doctype html><meta charset="utf-8">
            <body style="margin:0;background:#0b0b0b;color:#ddd;font:13px ui-monospace,monospace">
            <div style="display:flex;gap:18px;padding:16px">
              <figure style="margin:0">
                <figcaption>FORCE — spring model, ${forceMs}ms</figcaption>
                ${force.toSvg(w, h)}
              </figure>
              <figure style="margin:0">
                <figcaption>TALA — ${if (available) "d2 ${D2Layout.version}, ${talaMs}ms" else "helper absent, fell back to FORCE"}</figcaption>
                ${tala.toSvg(w, h)}
              </figure>
            </div>
            </body>
            """.trimIndent()
        )
        println("wrote ${page.absolutePath}")
        println("petri tala comparison ok")
    }
}
