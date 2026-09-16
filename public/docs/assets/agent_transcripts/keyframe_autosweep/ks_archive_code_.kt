// What a night looks like from the other end. Nothing here touches the renderer: an
// archive describes itself, which is the whole point of the manifests.
import afield.stdlib.path
import afield.stdlib.readSweepArchive
import afield.stdlib.sweepArchiveSummary
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readBytes

val archive = "%document/sweep".path
val rows = readSweepArchive(archive)

println(sweepArchiveSummary(archive))

// A contact sheet, straight from the stills the sweep wrote. Base64 rather than a file
// URL so the panel needs no static mount.
val sheet = rows.joinToString("") { m ->
    val still = archive.resolve("renders/${m.id}/still.png")
    val img = if (!still.exists()) "{dim:no still}" else
        "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(still.readBytes())}' " +
            "width='150' style='border:1px solid #333;display:block'/>"
    val pushed = m.extrapolationGain?.let { " ✦" } ?: ""
    "<div style='display:inline-block;margin:2px;font:10px Iosevka,monospace;color:#999'>" +
        "$img${m.index}$pushed ${m.anchor.take(12)}</div>"
}
display("sheet", "<div style='width:500px'>$sheet</div>")

markdown("what went in", """
    #### ${rows.size} renders

    | # | parents and weights | pushed past the hull | discrete lane from |
    |---|---------------------|----------------------|--------------------|
    ${rows.joinToString("\n") { m ->
        "| ${m.index} | ${m.parents.joinToString("<br>") { "${it.key} ${"%.2f".format(it.weight)}" }} " +
            "| ${m.extrapolationGain?.let { g -> "x${"%.2f".format(g)} away from ${m.extrapolationPole}" } ?: "—"} " +
            "| ${m.discreteLanes.values.distinct().joinToString(", ")} |"
    }}
""", width = 500)

// One lane's trajectory through the night, read out of the keys themselves.
val intensities = rows.map { m ->
    val key = archive.resolve("renders/${m.id}/key.json")
    if (key.exists()) (afield.stdlib.KeyframeScope().loadKey(key)["light.intensity"] as? Number ?: 0).toFloat() else 0f
}
showSignals("light.intensity" to intensities, title = "one lane across the night", xTitle = "render")
