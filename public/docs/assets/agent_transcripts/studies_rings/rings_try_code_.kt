// Speculate without publishing: sweep the ring count, and try two candidates beside the
// current revision. Nothing here changes the study; adopt a candidate from AI4 chat
// (AdoptRingsVariant) or with rings.adoptVariant(...). Run "rings" first.
val rings = fromContextWithNameOrNull<SourceFramesStudy>("rings")!!
if (rings.inspect(StudyCall.Inspect()).revision == "empty") println("no revision yet: run \"change it\" first") else {
    val source = rings.revisions.liveSource()
    val swept = rings.sweep(StudyCall.Sweep(knob = 0, values = listOf(3.0, 5.0, 7.0, 11.0, 15.0)))
    val tried = rings.tryVariants(StudyCall.TryVariants(listOf(
        StudyCall.Variant("tight", source = StudyKnobs.withPlainNumbers(source, mapOf(0 to 15.0)), note = "15 rings"),
        StudyCall.Variant("bright", source = StudyKnobs.withPlainNumbers(source, mapOf(1 to 0.4)), note = "lighter dark bands"))))
    println("held: " + tried.facts["held"])
    display("main", "<img src='${swept.toolImages.single().dataUrl}' style='width:100%'/>" +
        "<img src='${tried.toolImages.single().dataUrl}' style='width:340px'/>")
}
