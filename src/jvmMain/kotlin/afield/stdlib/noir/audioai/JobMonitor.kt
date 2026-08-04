package afield.stdlib.noir.audioai

/** An embeddable, same-origin view of all durable Field Audio jobs. */
fun fieldAudioJobMonitor(
    serviceBaseUrl: String = "/field-audio",
    heightPixels: Int = 420,
): String {
    require(heightPixels >= 120) { "job monitor height must be at least 120 pixels" }
    val base = serviceBaseUrl.trimEnd('/').replace("&", "&amp;").replace("\"", "&quot;")
    return """
<iframe
    title="Field Audio jobs"
    src="$base/monitor"
    data-afield-intrinsic-width="760"
    data-afield-intrinsic-height="$heightPixels"
    style="border:none;padding:0;margin:0;display:block;width:100%;height:${heightPixels}px;background:#111417;"
></iframe>
""".trimIndent()
}
