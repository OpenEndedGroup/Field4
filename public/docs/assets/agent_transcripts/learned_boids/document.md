# Field Document: learned_boids

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="lb_renderer" index="0" -->

```edn [field-box id="lb_renderer" role="properties"]
{"id" "lb_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["lb_train"] "properties" {"children" #{#afield/box "lb_train"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_renderer_code_.kt" "lines" 11 "characters" 253 "sha256" "8f2698a4ec3dd76cc69a76aaa0748f783cdfc73d29760f3b6401692b6856922f"}]}
```

```kotlin [field-property box="lb_renderer" name="code" path="lb_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Learned boids")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. train the rule

<!-- field-box id="lb_train" index="1" -->

```edn [field-box id="lb_train" role="properties"]
{"id" "lb_train" "index" 1 "name" "train the rule" "parents" ["root" "lb_renderer"] "children" ["lb_written" "lb_learned"] "properties" {"children" #{#afield/box "lb_written" #afield/box "lb_learned"} "parents" #{#afield/box "root" #afield/box "lb_renderer"} "name" "train the rule" "_tombstone" false "frame" #afield/rect [380.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_train_code_.kt" "lines" 72 "characters" 3821 "sha256" "3b3c3423edd1791c620bee67ce4ceb94d986a9363270d83d2e951957f9fa528d"}]}
```

```kotlin [field-property box="lb_train" name="code" path="lb_train_code_.kt"]
import afield.stdlib.DeepSetsOptions
import afield.stdlib.InteractionExample
import afield.stdlib.trainEquivariant

prop<afield.stdlib.EquivariantWeights>(learnedRule)

val radius = 2f / 64f
val minSeparation = radius * 0.05f
val velocityScale = 0.4f
val random = java.util.Random(5)

// The rule being imitated, evaluated on random neighbourhoods. No simulation is needed
// to generate training data when you already have the law -- and having the law is what
// makes the result gradeable at all.
fun sample(): InteractionExample {
    val v = floatArrayOf(
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale)
    // Up to 70, not 14. The flock actually runs at about 57 neighbours per boid once
    // cohesion has gathered it, and a model trained only on sparse neighbourhoods is
    // extrapolating in deployment -- which matters enormously here because cohesion is
    // weighted 200x the *mean* offset, so a small error in the meaned branch becomes a
    // large force. Train on the density you will run at.
    val neighbours = (0..random.nextInt(70)).map {
        val r = radius * Math.sqrt(random.nextDouble()).toFloat()
        val a = random.nextDouble() * 2 * Math.PI
        floatArrayOf((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(),
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale)
    }
    var sx = 0f; var sy = 0f; var dvx = 0f; var dvy = 0f; var dx = 0f; var dy = 0f
    for (n in neighbours) {
        val d = Math.hypot(n[0].toDouble(), n[1].toDouble()).toFloat()
        // Separation is short range; cohesion and alignment see the whole radius. One
        // radius for all three cannot clump -- see BoidSettings.separationRadiusFraction.
        if (d <= radius * 0.25f) {
            val soft = maxOf(d, minSeparation)
            sx -= n[0] / (soft * soft); sy -= n[1] / (soft * soft)
        }
        dvx += n[2]; dvy += n[3]; dx += n[0]; dy += n[1]
    }
    val c = neighbours.size.toFloat()
    return InteractionExample(v, neighbours, floatArrayOf(
        0.02f * sx + 3.0f * dvx / c + 200f * dx / c,
        0.02f * sy + 3.0f * dvy / c + 200f * dy / c))
}

learnedRule = trainEquivariant(
    radius = radius, velocityScale = velocityScale,
    accelScale = 20f, sumScale = 8f, minSeparation = minSeparation,
    // The *equivariant* form: psi emits scalars that multiply the neighbour's own
    // direction and velocity difference, so rotating the flock rotates the force
    // exactly and a symmetric neighbourhood gives identically zero. The plain form has
    // neither property — it scores 0.50 on a forty-eight-neighbour ring where the true
    // force is zero, a fixed-direction residual that grows with crowding — and that is
    // what used to drift this flock out of its containment while the written one, under
    // the same containment, stayed put. It also fits better: 0.262 against 0.302.
    //
    // 8x8 with a 4-wide message rather than the library default of 16x16 and 6. Swept
    // against this exact rule: 160 multiply-adds a neighbour instead of 480, for a
    // relative error of 0.302 against 0.287 — three times cheaper for five percent
    // worse. The cost of this scene is *entirely* psi (the written flock runs at 116 fps
    // on the identical grid, the learned one at 17.6), so this is the only dial that
    // matters. Depth is what to keep: one hidden layer of 16 costs more than two of 8
    // and scores 0.350.
    options = DeepSetsOptions(
        steps = 4000, batch = 16, seed = 5,
        psiHidden = intArrayOf(8, 8), messageWidth = 4,
    )
) { List(16) { sample() } }

println("trained ${learnedRule.parameterCount} parameters: ${learnedRule}")
```

## 3. written rule

<!-- field-box id="lb_written" index="2" -->

```edn [field-box id="lb_written" role="properties"]
{"id" "lb_written" "index" 2 "name" "written rule" "parents" ["root" "lb_train"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lb_train"} "name" "written rule" "_tombstone" false "frame" #afield/rect [740.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_written_code_.kt" "lines" 71 "characters" 3201 "sha256" "3eee413f10c85c6011e4ea9180ab507113a7556cba2b25025d1bc688a2d57598"}]}
```

```kotlin [field-property box="lb_written" name="code" path="lb_written_code_.kt"]
import afield.four.physics.ShaderBoids
import afield.four.util.GpuNeighbourGrid
import afield.stdlib.linalg.Vector2f

prop<afield.four.physics.ShaderBoids>(written)

once.written {
    val grid = GpuNeighbourGrid(
        capacity = 12000, cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f), domainSize = 2f,
        renderer = renderer, name = "writtenGrid")
    ShaderBoids(grid, 12000, renderer) {
        name = "written"
        radius = 2f / 64f
    }.also {
        it.reset(Vector2f(-0.95f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
        it.install(renderer.renderList.others)
        it.installDrawInto()
    }
}

// Live: the push buffer re-reads these every frame.
// Balanced against the measured magnitudes at this density and radius, not against
// the numbers a textbook quotes. With 57 neighbours inside a radius of 0.031,
// separation *sums* 57 terms of about 137 while cohesion *means* an offset of about
// 0.003 -- so the textbook 0.02/1.2/0.8 puts separation 27x above alignment and 4600x
// above cohesion, and the flock spaces itself evenly instead of gathering. These three
// bring the terms to the same order.
written.settings.separation = 0.02f
written.settings.separationRadiusFraction = 0.25f
written.settings.alignment = 3.0f
written.settings.cohesion = 200f
written.settings.maxSpeed = 0.25f
// Weak and wide on purpose. A stiff containment squeezes everything into one disc;
// give the flock room and cohesion only reaches one interaction radius, so groups that
// drift apart can never find each other again and you get several clumps instead of one.
written.settings.containment = 1.5f
written.settings.containmentRadius = 0.44f
written.settings.containmentCenter = Vector2f(-0.45f, 0f)
written.settings.dt = 1f / 60f
written.settings.pointSize = 3f
written.settings.intensity = 1.5f

// A wandering point the flock flees. Left alone at a settled density this scene reads as
// static — near-cancelling forces, an evenly spaced disc that barely changes — so the
// visible energy has to come from outside the rule. Whether "settled" is genuinely
// *still* is not established and a still frame cannot tell: measured on a smaller CPU
// flock at these weights the residual oscillates without trending, which is churn rather
// than rest. The learned box drives its flock with the identical walk about its own
// centre, so the A/B stays fair.
//
// The lambda is re-read every frame when the push buffer is written, which is the whole
// animation. It holds no state and is derived from the frame counter, so calling it
// twice in a frame cannot make it drift.
written.driver = {
    val t = renderer.frameCounter / 70.0
    val i = Math.floor(t).toLong()
    fun at(k: Long): Vector2f {
        val r = java.util.Random(k * 0x9E3779B9L)
        return Vector2f(-0.45f + (r.nextFloat() * 2 - 1) * 0.30f,
                        0f + (r.nextFloat() * 2 - 1) * 0.30f)
    }
    val a = at(i); val b = at(i + 1)
    val f = (t - i).toFloat()
    val e = f * f * (3 - 2 * f)
    Vector2f(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
}
written.settings.driverStrength = -0.9f
written.settings.driverRadius = 0.22f

println("written rule: ${written.count} boids")
```

## 4. learned rule

<!-- field-box id="lb_learned" index="3" -->

```edn [field-box id="lb_learned" role="properties"]
{"id" "lb_learned" "index" 3 "name" "learned rule" "parents" ["root" "lb_train"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lb_train"} "name" "learned rule" "_tombstone" false "frame" #afield/rect [1080.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_learned_code_.kt" "lines" 51 "characters" 1914 "sha256" "e6db184bd5494318de2bf41899fec9155f0ba3e18b68d6c21cf8d1207131edce"}]}
```

```kotlin [field-property box="lb_learned" name="code" path="lb_learned_code_.kt"]
import afield.four.physics.ShaderBoids
import afield.four.util.GpuNeighbourGrid
import afield.stdlib.linalg.Vector2f

prop<afield.four.physics.ShaderBoids>(learned)

// `learnedRule` is the trained network from the parent box. The only difference from
// the written flock is this argument.
once.learned {
    val grid = GpuNeighbourGrid(
        capacity = 12000, cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f), domainSize = 2f,
        renderer = renderer, name = "learnedGrid")
    ShaderBoids(grid, 12000, renderer, equivariant = learnedRule) {
        name = "learned"
        radius = 2f / 64f
    }.also {
        it.reset(Vector2f(-0.05f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
        it.install(renderer.renderList.others)
        it.installDrawInto()
    }
}

// The identical walk about this flock's own centre, so the two see the same forcing.
learned.driver = {
    val t = renderer.frameCounter / 70.0
    val i = Math.floor(t).toLong()
    fun at(k: Long): Vector2f {
        val r = java.util.Random(k * 0x9E3779B9L)
        return Vector2f(0.45f + (r.nextFloat() * 2 - 1) * 0.30f,
                        0f + (r.nextFloat() * 2 - 1) * 0.30f)
    }
    val a = at(i); val b = at(i + 1)
    val f = (t - i).toFloat()
    val e = f * f * (3 - 2 * f)
    Vector2f(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
}
learned.settings.driverStrength = -0.9f
learned.settings.driverRadius = 0.22f

learned.settings.separationRadiusFraction = 0.25f
learned.settings.maxSpeed = 0.25f
learned.settings.containment = 1.5f
learned.settings.containmentRadius = 0.44f
learned.settings.containmentCenter = Vector2f(0.45f, 0f)
learned.settings.dt = 1f / 60f
learned.settings.pointSize = 3f
learned.settings.intensity = 1.5f
learned.settings.slowColor = vec(0.95, 0.25, 0.45, 1.0)
learned.settings.fastColor = vec(1.0, 0.85, 0.45, 1.0)
println("learned rule: ${learned.count} boids on ${learned.learned}")
```

