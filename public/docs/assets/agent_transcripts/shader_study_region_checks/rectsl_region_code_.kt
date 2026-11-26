import afield.lang.rectsl.study.RectSLStudy
import kotlin.io.path.createTempDirectory

val scene =
    """
    ->wall 16 9 meters
    ->duration 4
    ->background 0x161e2e

    "sun" rect
      -4 1 ->xy
      3 ->square
      0xeab962 solid ->fill
    ;

    "moon" rect
      4 -1 ->xy
      2 ->square
      0xabbceb solid ->fill
      '( 0=4  4=5 ) ->x
    ;""".trimIndent()
val moonFill = "0xabbceb"
val sunFill = "0xeab962"
val localEdit = scene.replace(moonFill, "0x93e5bb")
val spillEdit = localEdit.replace(sunFill, "0xdb756f")
val allowed = [2.5, -2.5, 6.5, 0.5] // x0, y0, x1, y1 in metres
val times = [0.0, 2.0, 3.5]

val folder = createTempDirectory("rectsl-region-").toFile()
try {
    var source = scene
    val study =
        RectSLStudy(folder, { source }) { expected, replacement ->
            if (source != expected) false
            else {
                source = replacement
                true
            }
        }
    val baseline = study.checkIn("Original wall").revision
    for ((label, candidate) in [("Moon only" to localEdit), ("Moon and sun" to spillEdit)]) {
        val revision = study.edit(study.currentId(), SourceStudy.hash(source), candidate, label).revision
        val report = study.compare(baseline, revision, seconds = times, region = allowed, size = 192)
        val held = report.facts["preservedOutsideRegion"] == true
        val verdict = if (held) "pass" else "changed outside the allowed rectangle"
        display(label, "<p><strong>$verdict</strong></p>" + report.h)
        displayOpen(label)
        println("$label: $verdict")
    }
} finally {
    folder.deleteRecursively()
}
