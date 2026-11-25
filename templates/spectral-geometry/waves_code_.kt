import afield.geometry.spectral.PlaneWave
import afield.geometry.spectral.PlaneWaveField

val schwarz = PlaneWaveField(
    0.0,
    listOf(vec(1, 0, 0), vec(0, 1, 0), vec(0, 0, 1)).map {
        PlaneWave(it, 1.0, 0.0)
    },
)
val gyroid = PlaneWaveField(
    0.0,
    listOf(
        vec(1, 1, 0),
        vec(1, -1, 0),
        vec(0, 1, 1),
        vec(0, 1, -1),
        vec(1, 0, 1),
        vec(-1, 0, 1),
    ).map { PlaneWave(it, 0.0, 0.5) },
)
val lobes = PlaneWaveField(
    0.0,
    listOf(
        PlaneWave(vec(2, 0, 0), -1.0, 0.0),
        PlaneWave(vec(0, 1, 0), 1.0, 0.0),
        PlaneWave(vec(0, 0, 1), 1.0, 0.0),
    ),
)
val singleShell = PlaneWaveField.random(24, seed = 17, radialFrequency = { 2.4 })
val broad = PlaneWaveField.random(24, seed = 17, radialFrequency = { it.nextDouble(0.8, 5.0) })

var preset = 0
var selected = 0
var iso = 0.0
var phase = 0.0
var wavelength = 1.0
var gain = 1.0
var azimuth = 0.0
var elevation = 0.0
var rotation = 0.0
var band = 6.0
var animate = false
var showSingle = false
val bounds = boundsAround(radius = PI)
val names = listOf("Schwarz P", "Gyroid", "Lobes", "Single shell", "Broadband")
controlGroup(columns = 2) {
    slider(
        "Field preset",
        0.0,
        4.0,
        1.0,
        0.0,
        hoverText = "0 Schwarz P · 1 Gyroid · 2 Lobes · 3 Single shell · 4 Broadband",
    ) {
        preset = it.toInt()
    }
    slider("Isovalue", -2.0, 2.0, 0.01, 0.0) { iso = it }
    slider("Wave", 1.0, 24.0, 1.0, 1.0) { selected = it.toInt() - 1 }
    slider("Amplitude factor", 0.0, 2.0, 0.01, 1.0) { gain = it }
    slider("Wavelength factor", 0.5, 2.0, 0.01, 1.0) { wavelength = it }
    slider("Phase (radians)", -PI, PI, 0.01, 0.0) { phase = it }
    slider("Wave azimuth", -PI, PI, 0.01, 0.0) { azimuth = it }
    slider("Wave elevation", -PI, PI, 0.01, 0.0) { elevation = it }
    slider("Rotate spectrum", -PI, PI, 0.01, 0.0) { rotation = it }
    slider("Low-pass cutoff", 0.0, 9.0, 0.01, 6.0) { band = it }
    checkbox("Animate lobes through their saddle") { animate = it }
    checkbox("Solo the selected wave") { showSingle = it }
}
displayOpen("controls")

var tick = 0.0
var previous: List<Any> = emptyList()
var particles = emptyList<Vec3>()
frames {
    while (true) {
        val setting = listOf(
            preset,
            selected,
            iso,
            phase,
            wavelength,
            gain,
            azimuth,
            elevation,
            rotation,
            band,
            animate,
            showSingle,
        )
        if (animate || setting != previous) {
            if (setting != previous) particles = emptyList()
            previous = setting
            val chosen = if (animate) 2 else preset
            val source = listOf(schwarz, gyroid, lobes, singleShell, broad)[chosen]
            val waveIndex = selected.coerceAtMost(source.waves.lastIndex)
            val turn = quat(rotation, vec(0, 1, 0))
            val waves =
                source.waves.mapIndexed { i, wave ->
                    if (i == waveIndex) {
                        val k = wave.wavevector.div(wavelength.toFloat())
                        quat(azimuth, vec(0, 1, 0)).transform(k)
                        quat(elevation, vec(1, 0, 0)).transform(k)
                        PlaneWave.polar(k, gain * wave.amplitude, wave.phase + phase)
                    } else wave
                }
            val active = if (showSingle) listOf(waves[waveIndex]) else waves
            val field = PlaneWaveField(source.offset, active).lowPass(band).rotate(turn)
            val level = if (animate) 1.0 + 0.32 * kotlin.math.sin(tick) else iso
            val body = field.extractSurface(bounds, resolution = 31, isoValue = level, positiveOutside = false)
            if (animate && body.vertexCount > 0) {
                if (particles.isEmpty()) {
                    particles = List(75) { body.position(it * body.vertexCount / 75) }
                } else {
                    particles =
                        particles
                            .mapNotNull { point ->
                                val tangent = field.gradient(point).cross(vec(0.08, 0.01, -0.04))
                                field.projectToSurface(Vec3(point).add(tangent), level, maxStep = 0.20)
                            }
                            .filter {
                                it.x in bounds.min.x..bounds.max.x &&
                                    it.y in bounds.min.y..bounds.max.y &&
                                    it.z in bounds.min.z..bounds.max.z
                            }
                }
                tick += 0.10
            }
            val dots = FLine().apply {
                particles.forEach { moveTo(it) }
                pointed = true
                stroked = false
                pointSize = 2.5
                color = vec(1, 1, 1, 1)
            }
            val vectors = waves.mapIndexed { i, wave ->
                FLine().apply {
                    moveTo(vec(0, 0, 0))
                    lineTo(turn.transform(wave.wavevector))
                    color = if (i == waveIndex) vec(1, 1, 1, 1) else vec(0.75, 0.75, 0.75, 0.85)
                    thickness = 2.0
                    pointed = true
                    pointSize = (1.8 + wave.amplitude * 3.4).coerceAtMost(9.0)
                }
            }
            three(name = "plane-waves", width = 810, height = 430) {
                background("#080808")
                helpers(false)
                columns(2)
                panel(title = names[chosen], caption = "Isosurface · f = ${level.f2}")
                fitTo(bounds.min, bounds.max, panel = 0)
                if (!body.isEmpty) mesh(body, wireframe = false, cullBackFaces = false, id = "surface")
                lines(listOf(dots), id = "surface-particles")
                panel(
                    title = "Wavevectors",
                    caption = "Wave ${waveIndex + 1} / ${waves.size} · dot size is amplitude",
                    camera = ThreeCameraSpec(yawDegrees = 39.0, pitchDegrees = 29.0),
                )
                val span = waves.maxOf { it.frequency }.coerceAtLeast(2.0) * 1.2
                fitTo(vec(-span, -span, -span), vec(span, span, span), panel = 1)
                lines(vectors, id = "wavevectors")
            }
            displayOpen("main")
        }
        delay(125)
    }
}
