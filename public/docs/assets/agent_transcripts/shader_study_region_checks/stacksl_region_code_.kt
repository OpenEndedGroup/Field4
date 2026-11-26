import afield.lang.stacksl.study.StackSLStudy
import kotlin.io.path.createTempDirectory

val program = "uv saturate"
val localEdit = "$program\nwhite 0.5 uv x step mix"
val spillEdit = "$program\nwhite 0.35 uv x step mix"
val allowed = [0.5, 0.0, 1.0, 1.0] // x0, y0, x1, y1 in UV space
val times = [0.0]

val folder = createTempDirectory("stacksl-region-").toFile()
try {
    var source = program
    val study =
        StackSLStudy(folder, { source }) { expected, replacement ->
            if (source != expected) false
            else {
                source = replacement
                true
            }
        }
    val baseline = study.checkIn("Original shader").revision
    for ((label, candidate) in [("Right half" to localEdit), ("Mask crosses the midpoint" to spillEdit)]) {
        val revision = study.edit(study.currentId(), SourceStudy.hash(source), candidate, label).revision
        val report = study.compare(baseline, revision, seconds = times, region = allowed, size = 128)
        val held = report.facts["preservedOutsideRegion"] == true
        val verdict = if (held) "pass" else "changed outside the allowed rectangle"
        display(label, "<p><strong>$verdict</strong></p>" + report.h)
        displayOpen(label)
        println("$label: $verdict")
    }
} finally {
    folder.deleteRecursively()
}
