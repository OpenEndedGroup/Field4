// Run this box while the rest of the document is busy: it records three seconds of every
// running box and opens the profiling timeline. Drag the overview to zoom; click a lane.
CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 3.0, stacks = true))
thisBox.inBrowser("window.showProfilingTimeline()")
println("recording three seconds; the timeline opens when it is done")
