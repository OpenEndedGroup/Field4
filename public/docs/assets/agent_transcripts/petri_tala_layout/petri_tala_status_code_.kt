import afield.petri.*
import afield.layout.D2Layout

fun philosophers(count: Int = 5): PetriNet = petri {
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

val net = philosophers()

// Timing each layout once, in order, is the obvious thing and it lies: the first one pays
// for JVM warmup and the second does not. Both are warmed, then measured three times from
// a cleared cache, and the minimum kept — the reading least polluted by whatever else the
// machine is doing.
fun best(layout: PetriLayout): Long {
    net.frame(layout)
    var bestNs = Long.MAX_VALUE
    repeat(3) {
        // BOTH caches. `clearPetriLayoutCache` alone leaves D2Layout's own request
        // cache warm, and TALA then times at 0 ms because it is a map lookup.
        clearPetriLayoutCache()
        D2Layout.clearCache()
        val at = System.nanoTime()
        net.frame(layout)
        bestNs = minOf(bestNs, System.nanoTime() - at)
    }
    return bestNs / 1_000_000
}

val forceMs = best(PetriLayout.FORCE)
val talaMs = best(PetriLayout.TALA)

val force = net.frame(PetriLayout.FORCE)
val tala = net.frame(PetriLayout.TALA)
val differ = net.placeNames.any { force.position(it) != tala.position(it) }

val headline = if (D2Layout.available)
    "helper present — d2 ${D2Layout.version}"
else
    "helper absent — boxes 1 and 2 are the same drawing"

val detail = if (D2Layout.available)
    "The two drawings differ: <b>$differ</b>. False here would mean TALA fell back silently."
else
    "differ = <b>$differ</b>, which is the fallback working. Build it with " +
    "<code>(cd native/field-d2-layout &amp;&amp; go build -o build/field-d2-layout .)</code> — " +
    "Field prints that line itself, with an install hint when <code>go</code> is not on PATH."

// Rendered rather than returned as a string, so the demo is something to look at.
display("main", """
  <div style="font:13px/1.55 ui-monospace,SFMono-Regular,monospace;background:#0d0d0d;
              color:#dcdcdc;border:1px solid #2b2b2b;padding:14px 16px;max-width:720px">
    <div style="font-size:15px;color:#7fd7c4;margin-bottom:8px">$headline</div>
    <div style="margin-bottom:10px">$detail</div>
    <table style="border-collapse:collapse">
      <tr><td style="padding:2px 18px 2px 0;color:#8a8a8a">FORCE</td>
          <td style="text-align:right">${forceMs} ms</td>
          <td style="padding-left:14px;color:#8a8a8a">48 seeded restarts, scored</td></tr>
      <tr><td style="padding:2px 18px 2px 0;color:#8a8a8a">TALA</td>
          <td style="text-align:right">${talaMs} ms</td>
          <td style="padding-left:14px;color:#8a8a8a">subprocess, 3 seeds, best kept</td></tr>
    </table>
    <div style="margin-top:10px;color:#8a8a8a">best of 3, both warmed first —
      timing them once each in order flatters whichever runs second</div>
    <div style="margin-top:8px;color:#8a8a8a">TALA is never chosen by
      <code>PetriLayout.AUTO</code>, so nothing already drawn changes.</div>
  </div>
""".trimIndent())

headline
