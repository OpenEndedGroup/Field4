/*
Change the study: check the box's text in as the first revision if there is none yet,
then flip warmth (knob 2) between warm and cool. Each run publishes one revision and shows
it beside its parent with their difference. Run "rings" first.
*/

var now = ringsStudy.inspect(StudyCall.Inspect())
if (now.revision == "empty") {
    ringsStudy.edit(
        StudyCall.Edit(
            "empty",
            now.facts["liveSourceHash"] as String,
            ringsStudy.revisions.liveSource(),
            "the starting rings",
        ),
    )
    now = ringsStudy.inspect(StudyCall.Inspect())
}
val warmth = StudyKnobs.plainNumbers(now.facts["source"] as String)[2].value
val changed = ringsStudy.setNumbers(
    StudyCall.SetNumbers(
        now.revision,
        now.facts["liveSourceHash"] as String,
        [StudyCall.Number(2, if (warmth > 0.5) 0.2 else 0.8)],
        if (warmth > 0.5) "cooler" else "warmer",
    ),
)

println(changed.revision + ": " + ringsStudy.revisions.liveSource())
display("main", "<img src='${changed.toolImages.single().dataUrl}' style='width:340px'/>")
