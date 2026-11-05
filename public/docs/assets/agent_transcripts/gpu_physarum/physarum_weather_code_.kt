/*
Everything here is read every frame, so re-run this box with other numbers and the
running network responds: wind drags the whole trail (pixels per frame), a longer
half-life makes thicker, slower roads, and jitter makes agents wander off them.
*/
physarum.trail.settings.windPx = Vector2f(0.25f, 0f)
physarum.trail.settings.halfLifeFrames = vec(12f, 12f, 12f, 12f)
physarum.settings.jitter = 0.05f
println("wind ${physarum.trail.settings.windPx}, decay per frame ${physarum.trail.decayFactors()}")
