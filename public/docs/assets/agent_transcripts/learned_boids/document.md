# Field Document: learned_boids

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="lb_renderer" index="0" -->

```edn [field-box id="lb_renderer" role="properties"]
{"id" "lb_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["lb_train"] "properties" {"children" #{#afield/box "lb_train"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_renderer_code_.kt" "lines" 13 "characters" 333 "sha256" "e9aa3201b3d8526ac381dc9ce75bd9d8fe68e6ed1e50ee8632f5fd6f0453e731"}]}
```

```kotlin [field-property box="lb_renderer" name="code" path="lb_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// One window and renderer, published to every child as `renderer`.
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
{"id" "lb_train" "index" 1 "name" "train the rule" "parents" ["root" "lb_renderer"] "children" ["lb_written" "lb_learned"] "properties" {"children" #{#afield/box "lb_written" #afield/box "lb_learned"} "parents" #{#afield/box "root" #afield/box "lb_renderer"} "name" "train the rule" "_tombstone" false "frame" #afield/rect [380.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_train_code_.kt" "lines" 99 "characters" 3494 "sha256" "7f96047269d7789f37ae4d40aec1e58e78b08f2fa768cd79fe530cefe1c72ef8"}]}
```

```kotlin [field-property box="lb_train" name="code" path="lb_train_code_.kt"]
import afield.ai.learning.DeepSetsOptions
import afield.ai.learning.InteractionExample
import afield.ai.learning.trainEquivariant

prop<afield.ai.learning.EquivariantWeights>(learnedRule)

val radius = 2f / 64f
val minSeparation = radius * 0.05f
val velocityScale = 0.4f
val random = java.util.Random(5)

/*
The rule being imitated, evaluated on random neighbourhoods. No simulation is needed
to generate training data when you already have the law -- and having the law is what
makes the result gradeable at all.
*/
fun sample(): InteractionExample {
    val v = floatArrayOf(
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
    )
    /*
    Up to 70 neighbours: the gathered flock runs at about 57 per boid, so train on the
    density you will run at. Cohesion weights the *mean* offset by 200, so a small error
    in the meaned branch becomes a large force.
    */
    val neighbours = (0..random.nextInt(70)).map {
        val r = radius * Math.sqrt(random.nextDouble()).toFloat()
        val a = random.nextDouble() * 2 * Math.PI
        floatArrayOf(
            (r * Math.cos(a)).toFloat(),
            (r * Math.sin(a)).toFloat(),
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        )
    }
    var sx = 0f
    var sy = 0f
    var dvx = 0f
    var dvy = 0f
    var dx = 0f
    var dy = 0f
    for (n in neighbours) {
        val d = Math.hypot(n[0].toDouble(), n[1].toDouble()).toFloat()
        // Separation is short range; cohesion and alignment see the whole radius. One
        // radius for all three cannot clump -- see BoidSettings.separationRadiusFraction.
        if (d <= radius * 0.25f) {
            val soft = maxOf(d, minSeparation)
            sx -= n[0] / (soft * soft)
            sy -= n[1] / (soft * soft)
        }
        dvx += n[2]
        dvy += n[3]
        dx += n[0]
        dy += n[1]
    }
    // Same weights as the written box: separation summed, alignment and cohesion meaned.
    val c = neighbours.size.toFloat()
    return InteractionExample(
        v,
        neighbours,
        floatArrayOf(
            0.02f * sx + 3.0f * dvx / c + 200f * dx / c,
            0.02f * sy + 3.0f * dvy / c + 200f * dy / c,
        ),
    )
}

// Published to the children as `learnedRule`; rerun this box to retrain.
learnedRule = trainEquivariant(
    radius = radius,
    velocityScale = velocityScale,
    accelScale = 20f,
    sumScale = 8f,
    minSeparation = minSeparation,
    /*
    The *equivariant* form: psi emits scalars that multiply the neighbour's own
    direction and velocity difference, so rotating the flock rotates the force
    exactly and a symmetric neighbourhood gives identically zero. Without that, a
    fixed-direction residual grows with crowding and drifts the flock out of its
    containment.

    psi runs once per neighbour per boid, so it is nearly all of this scene's GPU cost.
    8x8 with a 4-wide message (library default 16x16 and 6) is about three times
    cheaper for a slightly worse fit. Keep two layers: one layer of 16 costs more and
    fits worse.
    */
    options = DeepSetsOptions(
        steps = 4000,
        batch = 16,
        seed = 5,
        psiHidden = intArrayOf(8, 8),
        messageWidth = 4,
    ),
) {
        List(16) { sample() }
    }

println("trained ${learnedRule.parameterCount} parameters: ${learnedRule}")
```

## 3. written rule

<!-- field-box id="lb_written" index="2" -->

```edn [field-box id="lb_written" role="properties"]
{"id" "lb_written" "index" 2 "name" "written rule" "parents" ["root" "lb_train"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lb_train"} "name" "written rule" "_tombstone" false "frame" #afield/rect [740.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_written_code_.kt" "lines" 79 "characters" 2883 "sha256" "de04b2ceea3417faa7cf5b3a0606131e702e0a2b952c67c9d9d43a9fbd041855"}]}
```

```kotlin [field-property box="lb_written" name="code" path="lb_written_code_.kt"]
import afield.simulation.physics.ShaderBoids
import afield.graphics.vulkan.util.GpuNeighbourGrid
import afield.math.linalg.Vector2f

prop<afield.simulation.physics.ShaderBoids>(written)

// Built once: GPU buffers, neighbour grid and draw pass. Everything below is live.
once.written {
    val grid = GpuNeighbourGrid(
        capacity = 12000,
        cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f),
        domainSize = 2f,
        renderer = renderer,
        name = "writtenGrid",
    )
    ShaderBoids(grid, 12000, renderer) {
            name = "written"
            radius = 2f / 64f
        }
        .also {
            it.reset(Vector2f(-0.95f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
            it.install(renderer.renderList.others)
            it.installDrawInto()
        }
}

/*
The rule's weights. Live: the push buffer re-reads these every frame, so edit and
rerun. They are far from the textbook 0.02/1.2/0.8 because separation *sums* over
~57 neighbours while alignment and cohesion take *means*; these bring the three
terms to the same order at this density and radius. The training box hard-codes the
same three numbers -- rerun it after changing them.
*/
written.settings.separation = 0.02f
written.settings.separationRadiusFraction = 0.25f
written.settings.alignment = 3.0f
written.settings.cohesion = 200f
written.settings.maxSpeed = 0.25f
/*
Weak and wide on purpose. A stiff containment squeezes everything into one disc;
give the flock room and cohesion only reaches one interaction radius, so groups that
drift apart can never find each other again and you get several clumps instead of one.
*/
written.settings.containment = 1.5f
written.settings.containmentRadius = 0.44f
written.settings.containmentCenter = Vector2f(-0.45f, 0f)
written.settings.dt = 1f / 60f
written.settings.pointSize = 3f
written.settings.intensity = 1.5f

/*
A wandering point the flock flees (negative driverStrength). Left alone the flock
settles into a near-static disc, so the motion comes from outside the rule. The
learned box runs the identical walk about its own centre, so the A/B stays fair.

Called every frame. It is stateless: a seeded random target every 70 frames,
smoothstepped between, so calling it twice in a frame gives the same point.
*/
written.driver = {
    val t = renderer.frameCounter / 70.0
    val i = Math.floor(t).toLong()
    fun at(k: Long): Vector2f {
        val r = java.util.Random(k * 0x9E3779B9L)
        return Vector2f(
            -0.45f + (r.nextFloat() * 2 - 1) * 0.30f,
            0f + (r.nextFloat() * 2 - 1) * 0.30f,
        )
    }
    val a = at(i)
    val b = at(i + 1)
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
{"id" "lb_learned" "index" 3 "name" "learned rule" "parents" ["root" "lb_train"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lb_train"} "name" "learned rule" "_tombstone" false "frame" #afield/rect [1080.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lb_learned_code_.kt" "lines" 61 "characters" 2231 "sha256" "b8788c9f3224fd26af134393886df97ad3919e3094c0b7fa0fdb712f63c883ff"}]}
```

```kotlin [field-property box="lb_learned" name="code" path="lb_learned_code_.kt"]
import afield.simulation.physics.ShaderBoids
import afield.graphics.vulkan.util.GpuNeighbourGrid
import afield.math.linalg.Vector2f

// `learnedRule` is the trained network from the parent box. The only difference from
// the written flock is this argument.
//
// A flock compiles its rule into its shaders, so it is cached on the rule: retrain the
// parent and the next run builds a new flock, releasing the old one.
val learned = cached<ShaderBoids>(learnedRule, renderer) {
    val grid = GpuNeighbourGrid(
        capacity = 12000,
        cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f),
        domainSize = 2f,
        renderer = renderer,
        name = "learnedGrid",
    )
    ShaderBoids(grid, 12000, renderer, equivariant = learnedRule) {
            name = "learned"
            radius = 2f / 64f
        }
        .also {
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
        return Vector2f(
            0.45f + (r.nextFloat() * 2 - 1) * 0.30f,
            0f + (r.nextFloat() * 2 - 1) * 0.30f,
        )
    }
    val a = at(i)
    val b = at(i + 1)
    val f = (t - i).toFloat()
    val e = f * f * (3 - 2 * f)
    Vector2f(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
}
learned.settings.driverStrength = -0.9f
learned.settings.driverRadius = 0.22f

// Everything except the force rule matches the written box; only the colours differ.
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
println("learned rule: ${learned.count} boids")
```

