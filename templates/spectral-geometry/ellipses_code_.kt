import afield.geometry.spectral.EllipseHarmonic
import afield.geometry.spectral.EpicycleGeometry
import afield.geometry.spectral.PhaseClock

val torus = EpicycleGeometry.torus(1.15, 0.38)
val torusMesh = torus.sampleSurface(48, 24).withVertexNormals()
val generative = EpicycleGeometry.random(
    2,
    listOf(
        intArrayOf(1, 0),
        intArrayOf(0, 1),
        intArrayOf(1, 1),
        intArrayOf(2, -1),
        intArrayOf(3, 2),
    ),
    seed = 19,
    decay = 0.85,
)
val volume = EpicycleGeometry(
    3,
    vec(0, 0, 0),
    listOf(
        EllipseHarmonic(intArrayOf(1, 0, 0), vec(0.9, 0, 0), vec(0, 0.15, 0)),
        EllipseHarmonic(intArrayOf(0, 1, 0), vec(0, 0.75, 0), vec(0, 0, 0.15)),
        EllipseHarmonic(intArrayOf(0, 0, 1), vec(0, 0, 0.70), vec(0.15, 0, 0)),
    ),
)
var rational = false
var preset = 0
var speed = 1.0
var link = 0
var gain = 1.0
var linkRate = 1.0
controlGroup(columns = 2) {
    slider("Torus / random / volume", 0.0, 2.0, 1.0, 0.0) { preset = it.toInt() }
    checkbox("Rational clocks (3:2 and 2:1)") { rational = it }
    slider("Clock speed", 0.0, 4.0, 0.01, 1.0) { speed = it }
    slider("Oscillator", 1.0, 5.0, 1.0, 1.0) { link = it.toInt() - 1 }
    slider("Oscillator gain", 0.0, 2.0, 0.01, 1.0) { gain = it }
    slider("Oscillator rate", -2.0, 2.0, 0.01, 1.0) { linkRate = it }
}
displayOpen("controls")

var time = 0.0
var previousSetting: List<Any> = emptyList()
val trail = ArrayDeque<Vec3>()
frames {
    while (true) {
        val original = listOf(torus, generative, volume)[preset]
        val geometry = original.withSpectrum(
            original.spectrum.reweight { mode, _ ->
                if (mode == link) gain else 1.0
            },
        )
        val setting = listOf(preset, rational, link, gain, linkRate)
        if (setting != previousSetting) {
            trail.clear()
            time = 0.0
            previousSetting = setting
        }
        val rate = if (rational) 1.5 else kotlin.math.sqrt(2.0)
        val frequencies =
            if (preset == 2) doubleArrayOf(1.0, rate, if (rational) 2.0 else kotlin.math.sqrt(3.0))
            else doubleArrayOf(1.0, rate)
        val clock = PhaseClock(frequencies)
        fun links(t: Double) =
            geometry.partialSums(clock.phase(t)) { mode, angle ->
                if (mode == link && linkRate != 1.0) clock.frequency(geometry.harmonics[mode].mode) * t * linkRate
                else angle
            }
        val elapsed = speed * 0.10
        repeat(14) { i ->
            trail.addLast(links(time + elapsed * (i + 1) / 14).last())
            if (trail.size > 3600) trail.removeFirst()
        }
        time += elapsed
        val phase = clock.phase(time)
        val points = links(time)
        val chain = polylineFrom(points).apply {
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 2.0
        }
        val orbits =
            geometry.orbits(phase).mapIndexed { i, orbit ->
                val nominalCenter = geometry.partialSums(phase)[i]
                val shift = Vec3(points[i]).sub(nominalCenter)
                orbit.nodes.forEach { it.to.add(shift) }
                orbit.color = if (i == link) vec(1.0, 1.0, 1.0, 0.85) else vec(0.65, 0.65, 0.65, 0.55)
                orbit
            }
        val cloud = FLine().apply {
            trail.forEach { moveTo(it) }
            color = vec(0.72, 0.72, 0.72, 0.62)
            pointed = true
            stroked = false
            pointSize = 1.4
        }
        three(name = "oriented-ellipses", width = 670, height = 455) {
            background("#080808")
            helpers(false)
            fitTo(vec(-1.65, -1.65, -1.25), vec(1.65, 1.65, 1.25))
            if (preset == 0) mesh(torusMesh, wireframe = false, opacity = 0.19, id = "torus")
            lines(orbits + chain + cloud)
        }
        displayOpen("main")
        delay(110)
    }
}
