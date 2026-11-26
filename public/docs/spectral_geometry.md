# Spectral Geometry

<!-- capture-sources: src/jvmMain/kotlin/afield/geometry/spectral/ public/docs/assets/spectral_geometry/bun_zipper.ply -->

Open **spectral-geometry** from **Templates** on the Field welcome screen. Launch **Ellipse chain**,
**Plane-wave instrument**, **Discover plane waves**, or **Manifold harmonics** with ++alt+arrow-up++
(use Option for Alt on macOS). The boxes put the spectrum or its effect next to the geometry, so changing
a coefficient gives you something to look at.

| Box | What to try |
| --- | --- |
| Ellipse chain | Compare the 3:2 clock ratio with √2 on the same torus. Set an oscillator's rate to zero to freeze that link, or zero its gain to remove it. Select the third preset to see the trail accumulate through a volume. |
| Plane-wave instrument | Pick the Schwarz or gyroid field, then rotate one wavevector, change its wavelength, or solo it. **Animate lobes through their saddle** moves the level through a split and a merge while particles follow the surface. |
| Discover plane waves | Compare Cartesian FFT, fixed shells, and adaptive discovery with the same wave budget and held-out sample set. Edit `shape` to try a sphere or a mesh with concavities. |
| Manifold harmonics | Bring back position modes one eigenspace at a time. Increase **Heat time** to smooth the embedding. Switch the standing wave to move the zero contour and the particle flow. |

## Ellipses and clocks

An `EpicycleGeometry` maps periodic phases into 3D. Each `EllipseHarmonic` stores two
vectors and an integer lattice mode. A separate `PhaseClock` supplies angles in radians.
The endpoint is the sum of all links. `partialSums` gives their intermediate endpoints.

```diffstatic {data-lang=kotlin}
import afield.geometry.spectral.EpicycleGeometry
import afield.geometry.spectral.PhaseClock

val geometry = EpicycleGeometry.torus(major = 1.15, minor = 0.38)
val clock = PhaseClock(doubleArrayOf(1.0, kotlin.math.sqrt(2.0)))
val phase = clock.phase(2.3)
three {
    mesh(geometry.sampleSurface().withVertexNormals(), opacity = 0.23, wireframe = false)
    lines(geometry.orbits(phase) + geometry.chain(phase))
}
```

`EpicycleGeometry.fit` takes a regularly sampled periodic function. Pass power-of-two sample
counts and a lambda from phase to position. The first axis varies fastest. The fit keeps one
mode per conjugate pair and handles the DC and Nyquist terms separately. The two-phase
`sampleSurface` closes both seams in its triangle indices.

### Fit an epicycle to an FLine

For a single closed contour, use an `FLine` cursor to turn phase into distance around the
path. Equal phase steps give equal distances along the source curve. The gray line is the
original, and the white line is the fit. Try changing `maxDegree` from 5 to 20 to recover
more of the sharp corners.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spectral_geometry/fline-epicycle-fit.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" padding="10" embed=true name="fline-epicycle-fit" alt="A closed gray FLine overlaid with a white five-harmonic fit that rounds the sharp corners."}
:::static
import afield.geometry.spectral.EpicycleGeometry
import afield.geometry.spectral.PhaseClock

val outline = fline {
    moveTo(28, 140)
    cubicTo(88, 24, 195, 23, 300, 69)
    lineTo(386, 82)
    cubicTo(338, 132, 326, 189, 235, 191)
    cubicTo(167, 193, 131, 148, 114, 115)
    close()
    color = vec(0.48, 0.48, 0.48, 1.0)
    thickness = 4.0
}

val cursor = outline.cursor()
val perimeter = cursor.lengthD()
val turn = 2 * PI
val machine =
    EpicycleGeometry.fit(intArrayOf(256), maxDegree = 5.0) { phase ->
        cursor.setD((perimeter * phase[0] / turn).toFloat()).position()
    }

val clock = PhaseClock(doubleArrayOf(1.0))
val points = machine.trace(clock, start = 0.0, step = turn / 384, count = 384)
val fitted = polylineFrom(points, closed = true).apply {
    color = vec(1, 1, 1, 1)
    thickness = 1.7
}
lines(outline, fitted, w = 640.0, h = 274.0, fitToSize = true)
```

### A torus drawing machine

Follow the links from the fixed hub to the bright tip. The first arm describes a circle. The
second slides along a straight guide, the degenerate ellipse in the chain. Two smaller circular
arms finish the chain. Each guide is centred on the joint that carries it, and the four joint
positions are the successive partial sums. The right-hand view keeps the tip's path as the two
clocks carry it around the torus.

```diffstatic_runner {data-lang=kotlin video="assets/spectral_geometry/torus-drawing-machine.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" restart-target="false" wait="0.8" duration="16" fps="24" timeout="300" padding="10" embed=true name="torus-drawing-machine" alt="A four-link drawing machine with luminous joints and circular guides on the left. Its bright tip travels around a torus. The right view builds up the actual tip path until it wraps around the torus many times."}
:::static
import afield.geometry.spectral.EpicycleGeometry
import afield.geometry.spectral.PhaseClock

val machine = EpicycleGeometry.torus(major = 1.12, minor = 0.44)
val clock = PhaseClock(doubleArrayOf(1.0, sqrt(2.0)), initialPhase = doubleArrayOf(0.62, 1.05))
val speed = 4.8
val sweepSeconds = 16.0
val sampleStep = 0.024
val track = machine.trace(
    clock,
    start = 0.0,
    step = sampleStep,
    count = (sweepSeconds * speed / sampleStep).toInt() + 1,
)

:::fold view, joints, and clock
val camera = ThreeCameraSpec(
    yawDegrees = 32.0,
    pitchDegrees = 35.0,
    padding = 1.0,
    upAxis = ThreeUpAxis.Z,
)
val min = vec(-1.58, -1.58, -0.51)
val max = vec(1.58, 1.58, 0.51)
val bearing = icosphereMesh(subdivisions = 1, radius = 0.048)
val tip = icosphereMesh(subdivisions = 1, radius = 0.077)
val steel = ThreeSurfaceMaterial(color = floatArrayOf(0.68f, 0.68f, 0.68f))
val bearingMaterial = ThreeSurfaceMaterial(color = floatArrayOf(0.88f, 0.88f, 0.88f))
val started = AnimationClock.nowSeconds()
var opened = false

:::endfold

animate {
    // The live view holds the first pose while the viewer opens. Recording uses the frame clock.
    val now = AnimationClock.nowSeconds()
    val elapsed = if (AnimationClock.isFrameIndexed()) now else now - started - 2.0
    val seconds = elapsed.coerceIn(0.0, sweepSeconds)
    val time = seconds * speed
    val phase = clock.phase(time)
    val joints = machine.partialSums(phase)
    val last = (time / sampleStep).toInt().coerceIn(0, track.lastIndex)
    val recent = track.subList((last - 155).coerceAtLeast(0), last + 1) + joints.last()
    val guides = machine.orbits(phase, segments = 64).onEach {
        it.color = vec(0.76, 0.76, 0.76, 0.66)
    }
    val tail = polylineFrom(recent).apply { color = vec(1, 1, 1, 0.9) }
    val history = polylineFrom(track.subList(0, last + 1) + joints.last()).apply {
        color = vec(0.88, 0.88, 0.88, 0.76)
    }

    three(name = "drawing-machine", width = 940, height = 486) {
        background("#080808")
        helpers(false)
        columns(2)
        panel(title = "Four linked oscillators", camera = camera)
        fitTo(min, max, panel = 0)
        lines(guides, id = "moving-orbits")
        for (i in 0 until joints.lastIndex) {
            val start = joints[i]
            val end = joints[i + 1]
            if (start.distance(end) > 1e-6) {
                val rod = tubeMesh([start, end], radius = 0.021, sides = 10)
                mesh(rod, wireframe = false, material = steel, id = "rod-$i")
            }
        }
        for ((i, joint) in joints.withIndex()) {
            val isTip = i == joints.lastIndex
            mesh(
                if (isTip) tip else bearing,
                wireframe = false,
                material = bearingMaterial,
                unlit = isTip,
                id = "joint-$i",
                transform = translation(joint.x.toDouble(), joint.y.toDouble(), joint.z.toDouble()),
            )
        }
        lines([tail], id = "trailing-tip")
        panel(title = "Accumulated tip path", camera = camera)
        fitTo(min, max, panel = 1)
        lines([history], id = "accumulation")
        val pen = joints.last()
        mesh(
            tip,
            wireframe = false,
            unlit = true,
            material = bearingMaterial,
            id = "pen",
            transform = translation(pen.x.toDouble(), pen.y.toDouble(), pen.z.toDouble()),
        )
    }
    if (!opened) {
        displayOpen("main")
        opened = true
    }
}
```

## Bounded fields from plane waves

A `PlaneWaveField` stores a DC value and sine and cosine coefficients on freely oriented
wavevectors. The zero level is one surface you can extract. The field has analytical `gradient`,
`hessian`, and `laplacian` methods. Use `projectToSurface` for local, damped projection,
or `extractSurface` for a bounded marching-cubes mesh. The former can fail at critical points.
The field value is generally unsuitable as a sphere-tracing distance. `lipschitzBound` gives a
conservative bound on the gradient magnitude.

`fitCartesian` takes a regular grid and keeps the strongest conjugate pairs. Pad a
closed mesh inside the FFT cell and truncate the signed distance so the cell faces agree.
`fitDirectional` uses weighted QR ridge regression on a fixed wavevector dictionary.
`discoverPlaneWaves` adds waves by residual correlation and refits all the coefficients.
With `refinementRadius` set, it also moves the selected wavevectors using variable projection.
Every nonlinear evaluation solves the linear coefficient problem again. The callback receives
the fitted field after each addition. Use `runInterruptible(Dispatchers.Default)` in a
background box job for larger fits.

A fitted representation rotates by transforming its wavevectors. A finite dictionary still has
directional sampling error. `PlaneWaveField.random` draws directions uniformly on the sphere and
normalizes the sum of the phase-averaged wave energies to the requested variance. Supply a constant
radial frequency to get a single shell, or a radial sampler and energy weights for a broadband field.
The resulting distribution is isotropic. A particular draw has finitely many directions.

## Manifold harmonics

`CotangentLaplacian(mesh).basis(count)` computes the lowest modes of a welded triangle
mesh. Open boundaries have natural Neumann conditions. Disconnected components each
contribute a constant mode. Degenerate faces, isolated vertices, and non-manifold inputs
raise an error. For large meshes request a partial basis, since both its size and the cost
of reconstruction scale with vertices times modes.

Analyze a vertex-major interleaved signal with `basis.analyze(signal, channels)`, then
`basis.reconstruct(spectrum)`. The position spectrum is `basis.positions()`. When a cutoff
falls inside a repeated eigenvalue, `basis.truncate` keeps the entire cluster. Individual
eigenfunctions in such a cluster can vary with the eigensolver or the triangulation. The
whole eigenspace has a consistent meaning. See [Vallet and Lévy, *Spectral Geometry Processing
with Manifold Harmonics*](https://onlinelibrary.wiley.com/doi/10.1111/j.1467-8659.2008.01122.x).

### Stanford Bunny in manifold harmonics

Start from the scanned mesh, drop unused vertices, and decimate before solving for the lowest
modes. Each panel keeps the same triangles. As more modes come in, the ears separate and the
creases around the head and paws return. The captions report the area-weighted RMS position error
against the decimated source.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spectral_geometry/stanford-bunny-harmonics.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" timeout="180" padding="10" embed=true name="stanford-bunny-harmonics" alt="Four aligned views of the Stanford Bunny. From left to right, the input and reconstructions with 12, 36, and 96 manifold harmonics show the ears, face, and paws returning as more modes are added."}
:::static
import afield.geometry.spectral.CotangentLaplacian
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

val scan = "public/docs/assets/spectral_geometry/bun_zipper.ply"
val modeCounts = [12, 36, 96]
go {
    val (source, reconstructions) = runInterruptible(Dispatchers.Default) {
        val source = meshFromAnyFile(scan).repaired().normalized(2.0).decimate(2400).repaired().withVertexNormals()
        val basis = CotangentLaplacian(source).basis(modeCounts.last())
        val positions = basis.positions()
        val reference = DoubleArray(source.positions.size) { source.positions[it].toDouble() }
        val reconstructions = modeCounts.map { count ->
            val projected = basis.truncate(positions, count)
            basis.reconstructMesh(projected) to basis.reconstructionError(reference, projected)
        }
        source to reconstructions
    }

    three(name = "bunny-harmonics", width = 960, height = 365) {
        background("#080808")
        helpers(false)
        columns(4)
        val camera = ThreeCameraSpec(yawDegrees = -34.0, pitchDegrees = 10.0, padding = 1.13)
        val bounds = source.bounds()
        panel(title = "Stanford Bunny", caption = "Decimated source", camera = camera)
        fitTo(bounds.min, bounds.max, panel = 0)
        mesh(source, wireframe = false, id = "source")
        for ((i, result) in reconstructions.withIndex()) {
            val (reconstruction, error) = result
            panel(title = "${modeCounts[i]} modes", caption = "RMS ${error.f3}", camera = camera)
            fitTo(bounds.min, bounds.max, panel = i + 1)
            mesh(reconstruction, wireframe = false, id = "reconstruction-$i")
        }
    }
    displayOpen("main")
}
```

Scan and zippered reconstruction courtesy of Greg Turk and Marc Levoy at the Stanford University
Computer Graphics Laboratory. [Stanford 3D Scanning Repository](https://graphics.stanford.edu/data/3Dscanrep/).

## Two abstractions of the Bunny

The two forms share a scan. On the left, one unbroken stroke links targets spread over the mesh.
Each leg follows triangle edges and costs more if it reuses an edge, so the stroke explores the
ears, paws, and back instead of taking the same routes. A single-clock Fourier machine learns the
closed walk. Its smoothed tip path is lofted as a thin tube, with open space where the scan had skin.

On the right, quieten the broadest position modes and amplify the finer ones. Give each coordinate
a different phase delay, then pull thickened patches apart. Change `phaseDrift` and run the box
again to find another frame of the scattering. Fit two continuous plane-wave fields to the
high-band displacement samples on the scattered shells. At each ray hit, StackSL evaluates
the fields in world space. The signed displacement winds a blue-green patina through polished
copper. Its magnitude controls roughness, and a second, finer wave curls through the polished areas.

Both forms are rendered together with Field's [progressive path tracer](path_tracing.md).

```diffstatic_runner {data-lang=kotlin screenshot="assets/spectral_geometry/bunny-abstractions.png" capture="native-window" graphics-context=true execution="launch" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="Bunny abstractions converged" trim-native=true max-width="1540" embed=true timeout="240" name="bunny-abstractions" alt="Two Stanford Bunny abstractions on a dark stage. The left is a single long metallic tube winding over the original surface. The right is split into floating shells with copper and blue-green bands with dark gaps."}
:::static
import afield.geometry.spectral.CotangentLaplacian
import afield.geometry.spectral.FieldSample
import afield.geometry.spectral.PhaseClock
import afield.geometry.spectral.PlaneWaveField
import afield.geometry.spectral.fitSurfaceDrawing
import afield.geometry.spectral.toStackSL
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.scene.fabricated.importTriMesh

val source =
    meshFromAnyFile("public/docs/assets/spectral_geometry/bun_zipper.ply")
        .repaired()
        .normalized(2.0)
        .decimate(2400)
        .repaired()
        .withVertexNormals()
val points = List(source.vertexCount, source::position)

val surfaceDrawing = source.fitSurfaceDrawing(targetCount = 72, seed = 11, maxDegree = 172.0)
val anchors = surfaceDrawing.targets
val machine = surfaceDrawing.machine
val drawing = machine.trace(PhaseClock(doubleArrayOf(1.0)), 0.0, 2 * PI / 2304, 2304)
val wire = tubeMesh(drawing, radius = 0.012, sides = 8, closed = true)

val basis = CotangentLaplacian(source).basis(112)
val spectrum = basis.positions()
val phaseDrift = 0.151
val frequencies = spectrum.modes.map { it.frequency }
val maxFrequency = frequencies.last()
val edited = spectrum.edit { mode, axis, a, b ->
    val frequency = frequencies[mode]
    val high = frequency / maxFrequency
    val weight = if (mode == 0) 1.0 else 0.74 + 2.9 * high * high
    val drift = if (mode == 0) 1.0 else cos(phaseDrift * frequency * (0.32 + axis * 0.18))
    a * weight * drift to b
}
val resonant = basis.reconstructMesh(edited)
val highSignal = basis.reconstruct(spectrum.bandPass(maxFrequency * 0.47, maxFrequency))
:::fold Separate the phase-shifted surface into capped fragments
val pieceCount = 21
val seeds = anchors.take(pieceCount).map(points::get)
val faceGroups = List(pieceCount) { mutableListOf<Int>() }
for (face in 0 until source.triangleCount) {
    val center = Vec3(points[source.ia(face)]).add(points[source.ib(face)]).add(points[source.ic(face)]).div(3f)
    val nearest = seeds.indices.minBy { seeds[it].distanceSquared(center) }
    faceGroups[nearest] += face
}

data class Shell(val mesh: TriMesh, val sourceVertices: List<Int>)

fun shellPiece(piece: Int, faces: List<Int>): Shell {
    val oldIds = faces.flatMap { [source.ia(it), source.ib(it), source.ic(it)] }.distinct()
    val local = oldIds.withIndex().associate { it.value to it.index }
    val front = faces.flatMap {
        [local.getValue(source.ia(it)), local.getValue(source.ib(it)), local.getValue(source.ic(it))]
    }
    val count = oldIds.size
    val center = Vec3(seeds[piece])
    val direction = Vec3(center).normalize()
    val spin = quat(0.44 * sin(piece * 1.81 + phaseDrift * 10), vec(0.27, 1.0, 0.41).normalize())
    val shift = direction.mul((0.085 + 0.23 * abs(sin(piece * 1.11 + phaseDrift * 20))).toFloat())
    val vertices = FloatArray(count * 6)
    for ((i, id) in oldIds.withIndex()) {
        val normal = resonant.normal(id)!!
        val point = resonant.position(id)
        for (layer in 0..1) {
            val j = 3 * (i + layer * count)
            val p = Vec3(point).fma(-0.026f * layer, normal)
            spin.transform(p.sub(center)).add(center).add(shift)
            vertices[j] = p.x
            vertices[j + 1] = p.y
            vertices[j + 2] = p.z
        }
    }
    val indices = ArrayList(front)
    // The back face is reversed. Close all the boundary edges between the two skins.
    val seams = LinkedHashMap<Pair<Int, Int>, Pair<Int, Int>>()
    for (face in front.chunked(3)) {
        indices.addAll([face[2] + count, face[1] + count, face[0] + count])
        for (i in 0..2) {
            val a = face[i]
            val b = face[(i + 1) % 3]
            val key = minOf(a, b) to maxOf(a, b)
            if (seams.remove(key) == null) seams[key] = a to b
        }
    }
    for ((a, b) in seams.values) indices.addAll([b, a, a + count, b, a + count, b + count])
    return Shell(TriMesh(vertices, indices.toIntArray()).withVertexNormals(), oldIds)
}

:::endfold

val pieces = faceGroups.mapIndexedNotNull { i, group -> if (group.isEmpty()) null else shellPiece(i, group) }

val bottom = pieces.minOf { it.mesh.bounds().min.y }
val rightOrigin = vec(1.22f, -bottom + 0.11f, 0f)

:::fold Fit the high-band signal in world space
val signedRms = sqrt(
    points.indices
        .map { vertex ->
            val value = highSignal[3 * vertex + 2]
            value * value
        }
        .average(),
).coerceAtLeast(1e-8)

val phaseSamples = mutableListOf<FieldSample>()
val reliefSamples = mutableListOf<FieldSample>()
for (piece in pieces) {
    for ((local, original) in piece.sourceVertices.withIndex()) {
        val point = piece.mesh.position(local).add(rightOrigin)
        val x = 13.0 * highSignal[3 * original]
        val y = 8.0 * highSignal[3 * original + 1]
        phaseSamples += FieldSample(point, highSignal[3 * original + 2] / signedRms)
        reliefSamples += FieldSample(point, sqrt(x * x + y * y))
    }
}
val wavevectors = PlaneWaveField.frequencyShells(doubleArrayOf(4.0, 8.0, 13.0, 20.0), 12)
val phaseField = PlaneWaveField.fitDirectional(phaseSamples, wavevectors, ridge = 0.18) {
    1.0 + 0.08 * it
}
val reliefField = PlaneWaveField.fitDirectional(reliefSamples, wavevectors.take(36), ridge = 0.18) {
    1.0 + 0.08 * it
}
:::endfold

:::fold Set up the two forms, lights, and camera
val scene = PathTraceScene()
val wireMaterial = scene.material(PathTraceMaterial.metal(0.79f, 0.82f, 0.85f, 0.26f))
val fragmentMaterial = scene.material(PathTraceMaterial.plastic(0.78f, 0.78f, 0.78f, 0.30f))
val floor = scene.material(PathTraceMaterial.plastic(0.027f, 0.027f, 0.027f, 0.73f))
val left = Matrix4f().translate(-1.30f, -wire.bounds().min.y, 0f)
val right = Matrix4f().translate(rightOrigin)
scene.importTriMesh(wire, wireMaterial, transform = left)
for (piece in pieces) scene.importTriMesh(piece.mesh, fragmentMaterial, transform = right)
scene.quad(vec(-12, -0.025, -12), vec(-12, -0.025, 12), vec(12, -0.025, 12), vec(12, -0.025, -12), floor)

val environment = PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr")
prop<Renderer>(renderer)
val camera = Camera(renderer).also {
    it.at.set(-0.75f, 2.35f, 6.7f)
    it.target.set(0f, 0.91f, 0f)
    it.p.fov = 0.57
}
val tracer = PathTracingRenderer(renderer, scene, environment, 960, 540, camera).install()
tracer.showEnvironment = false
tracer.background = vec(0.01, 0.01, 0.01, 1)
tracer.environmentRotation = 2.1f
tracer.environmentIntensity = 1.35f
tracer.exposure = 1.16f
tracer.indirectClamp = 8f
tracer.maxBounces = 6
tracer.samplesPerFrame = 4

:::endfold

// StackSL gets the hit position. Both fields and the glaze run after intersection.
val stack = compileStackSL(
    """
    ${phaseField.toStackSL()}
    >bands
    ${reliefField.toStackSL()}
    >relief
    <bands 5.4 * cos 0.23 * 0.57 +
    <relief 0.20 + 5 sigmoid.2 0.48 * 0.22 +
    0 1 vec4
    """.trimIndent(),
    StackSLCompileOptions(prefix = "bunny_glaze"),
)
check(stack.ok) { stack.diagnostics.joinToString() }
val integrator = tracer.integratorSource()
val material =
    """
    ${stack.glsl}
    Material shadeMaterial(Material material, PathTraceSurface surface) {
        if (surface.materialId == $fragmentMaterial) {
            vec4 glaze = bunny_glaze(vec4(surface.position, 1.0));
            float oxide = smoothstep(0.39, 0.72, glaze.x);
            float grain = 0.5 + 0.5 * cos(63.0 * surface.position.y + 18.0 * glaze.x);
            vec3 copper = vec3(0.82, 0.44, 0.22);
            vec3 patina = vec3(0.12, 0.38, 0.43);
            material.base.rgb = mix(copper, patina, oxide) * (0.88 + 0.12 * grain);
            material.base.w = mix(0.90, 0.25, oxide);
            material.emission.w = clamp(glaze.y + 0.14 * oxide - 0.10 * grain, 0.18, 0.78);
        }
        return material;
    }
""".trimIndent()
val replacement =
    integrator.substringBefore("// MATERIAL_BEGIN") +
        material +
        System.lineSeparator() +
        integrator.substringAfter("// MATERIAL_END")
val reloaded = tracer.reloadIntegrator(replacement)
check(reloaded.ok) { reloaded.errors.joinToString() }
tracer.blitToScreen()

frames {
    while (tracer.samples < 256) frame()
    tracer.paused = true
    println("Bunny abstractions converged at ${tracer.samples} samples")
}
```

### Drawing the Bunny in one stroke

The bright tip follows the same closed route as the silver tube in the still. The camera
orbits as the tip lays down a warm filament that cools to steel. Each film frame starts a
fresh path trace, takes 32 samples per pixel, and runs Field's GPU OIDN denoiser with
albedo and normal guides. Stroke time controls visibility for camera, reflection, and
shadow rays alike.

<video controls muted playsinline preload="metadata" width="720" aria-label="A bright tip lays down a metallic Stanford Bunny stroke while the camera orbits" src="assets/spectral_geometry/bunny-drawing-orbit.mp4"></video>

### When the Bunny repeats

Fit a plane-wave field to the sealed scan's signed distance over a padded Fourier cell.
The fitted wavevectors sit on the cell's reciprocal lattice, so every term repeats across
each face. Crossfade their sum into a gyroid field with the same period. The ears and body
open into passages that join across cell faces. By the end, a little of the fitted scan
still perturbs every copy.

<video controls muted playsinline preload="metadata" width="720" aria-label="The Stanford Bunny opens into a gyroid and the camera pulls back to reveal its repeating cells" src="assets/spectral_geometry/bunny-periodic-lattice.mp4"></video>

The lattice film renders a finite view of the infinite periodic field. The Vulkan
renderer sphere-traces a trilinear grid holding one period. A Fourier fit changes the
distance function, so the marcher divides the absolute field value by a bound on its
rate of change along each ray. That bound comes from grid edge slopes, including the
edges across the wrap. Normals come from the same scalar field. The film's lighting
uses direct shading.

??? aside "Re-render the films"
    Run from the repository root with a Vulkan display and FFmpeg on the path. The
    arguments give the output file, frame count, width, and height. Use
    `afield.examples.four.SpectralBunnyLattice` as the entry point for the periodic film.

    ```sh
    uv run --project . --group backdoor python tools/field_backdoor.py --scratch --json \
      --command 'run-jvm-main afield.examples.four.SpectralBunnyFilm --timeout 420 --arg /tmp/bunny.mp4 --arg 144 --arg 640 --arg 360'
    ```

## Shared coefficients and Vulkan evaluation

All three domains use `Spectrum` for channel coefficients, mode frequencies, energy,
band filtering, coefficient edits, and seeded synthesis. Plane-wave energies are
phase-averaged for each component. In a bounded spatial domain, waves can interfere,
so their individual energies need not sum to the field's mean-square value.

`SpectralGpuEvaluator` takes a Field Vulkan renderer. Use `phase` for a batch of phase angles,
`spatial` for points, `spatialValueGradient` for gradient xyz plus value w, or `manifold`
for a fixed eigenbasis. `update` uploads coefficients while keeping the basis and query buffers
on the GPU. Its `output` is one vec4 per query for the first four channels, then the next group
of vec4s for additional channels. To extract a mesh from the GPU result, pass
`PlaneWaveGrid.points` to the scalar evaluator, then pass the completed readback to
`grid.extract`. Close the evaluator when its owner stops. For a box beneath
a renderer, pass its inherited `renderer`. The GPU parity smoke is
`tools/run_field_tests.py --id four.spectral-geometry.gpu`.

For a small spectrum, `field.toStackSL()` emits a program that takes the world position
in `input.xyz` and returns the scalar field in all four lanes. That form works with
[StackSL differentiation](stacksl_cheatsheet.md) and bakes in the coefficients. Use the
buffer evaluator for larger spectra or live coefficient animation.

## API reference

- Spectral geometry implementations: [`EpicycleGeometry`](reference/graphics.md#epicycles-kt-epicyclegeometry), [`EpicycleGeometry.torus`](reference/graphics.md#epicycles-kt--epicyclegeometry-companion--torus), [`EpicycleGeometry.fit`](reference/graphics.md#epicycles-kt--epicyclegeometry-companion--fit), [`PhaseClock`](reference/graphics.md#epicycles-kt-phaseclock), [`PhaseClock.phase`](reference/graphics.md#epicycles-kt--phaseclock--phase), [`CotangentLaplacian`](reference/graphics.md#manifoldharmonics-kt-cotangentlaplacian), [`CotangentLaplacian.basis`](reference/graphics.md#manifoldharmonics-kt--cotangentlaplacian--basis), [`PlaneWaveField`](reference/graphics.md#planewaves-kt-planewavefield), [`PlaneWaveField.fitDirectional`](reference/graphics.md#planewaves-kt--planewavefield-companion--fitdirectional).
