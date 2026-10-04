val tracker = particleTracker()
suspend fun trackMovie() {
    val run = tracker.track(
        "public/docs/assets/particle_video/source.mkv",
        ParticleTrackingOptions(particles = 24000, colorLimit = 1.0),
    )
    println("cached=${run.cacheHit}; tracks=${run.tracksFile}")
    particleVideoView(run.load(), pointRadius = .45)
}
start(::trackMovie)
