// The StudyBuild "builds" panel, reduced: a failed card whose open failure details hold a
// 320px-capped <pre> with far more text than that, and a second card below it.
val diagnostic = (1..400).joinToString("\n") { "  \"line-$it\" : \"saved diagnostic value $it\"," }
display("builds", """
<div class="shrink-fixture" style="background:#000;color:#ddd;padding:16px;font:14px/1.45 monospace;max-width:640px;box-sizing:border-box">
<section><div>Build checked panels · Build failed</div>
<details open style="margin-top:12px;background:#171717;padding:12px;border-radius:8px">
<summary>Inspect failure details</summary>
<pre style="font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow:auto">{
$diagnostic
}</pre></details></section>
<section style="margin-top:24px"><div>Check wall layouts · Ready to build</div><p>Not built yet</p></section>
</div>
""".trimIndent())
displayOpen("builds")
println("OUTPUT_PANEL_SHRINK_READY")
