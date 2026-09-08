// Run the three workload boxes first, then run this box. The macro does that for you.
// Drag the overview, Zoom to selection, choose Allocated bytes, and click a lane.
// Open current box code returns you to the code behind a measured coroutine.
// Hot code adds sampled classes, methods and box lines, including background threads.
CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 3.0, stacks = true))
thisBox.inBrowser("window.showProfilingTimeline()")
println("Recording three seconds. The timeline will latch automatically.")
