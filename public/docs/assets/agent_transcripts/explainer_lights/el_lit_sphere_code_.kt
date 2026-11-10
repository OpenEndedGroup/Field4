import afield.graphics.lighting.*

// A ball under one far-away light. Its shading is the cosine factor and nothing else.
val ball = icosphereMesh(4, 0.5)

fun draw(toward: Vec2) {
    // The control picks where the light is, seen from the camera; it sits 100 m away in that direction.
    val z = sqrt(max(0.0, 1.0 - toward.x * toward.x - toward.y * toward.y))
    val far = 100.0
    // Φ = 4π·100² W makes the irradiance one watt per square metre where the light hits head on.
    val sun = PointLight(vec(toward.x * far, toward.y * far, z * far), power = 4 * PI * far * far)
    three(channel = "scene", width = 640, height = 420) {
        mesh(ball.lit(listOf(sun), occluders = null, exposure = 2.0), unlit = true, wireframe = false)
        camera(yaw = 0.0, pitch = 0.0, distance = 2.4)
        background("#000000")
        hud(false)
    }
}

draw(vec(0.7, 0.4))
controlGroup {
    vec2Control("light", initialValue = vec(0.7, 0.4)) { draw(it) }
}
