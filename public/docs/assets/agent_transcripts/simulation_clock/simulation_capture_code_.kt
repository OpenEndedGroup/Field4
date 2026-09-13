CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start",3.0,stacks=true))
thisBox.inBrowser("window.showProfilingTimeline(); document.getElementById('profile-tab-frames').click()")
println("Recording frames, scopes and named simulation coroutines")
