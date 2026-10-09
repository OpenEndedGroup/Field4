val tracker = particleTracker()
suspend fun process() {
    val run = tracker.track(
        "public/docs/assets/particle_video/source.mkv",
        ParticleTrackingOptions(particles = 24000, colorLimit = 1.0),
    )
    println("cached=${run.cacheHit}; tracks=${run.tracksFile}")
    particleVideoView(run.load(), stride = 16)
}
start(::process)
