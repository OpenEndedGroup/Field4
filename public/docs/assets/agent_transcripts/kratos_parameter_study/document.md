# Field Document: kratos_parameter_study

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Kratos parameter study

<!-- field-box id="kratos_study" index="0" -->

```edn [field-box id="kratos_study" role="properties"]
{"id" "kratos_study" "index" 0 "name" "Kratos parameter study" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Kratos parameter study" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kratos_study_code_.kt" "lines" 194 "characters" 10251 "sha256" "2668ee1e00ab6a312bf8e1617f8106809a824e8e1f1971704625e86b3633b464"}]}
```

```kotlin [field-property box="kratos_study" name="code" path="kratos_study_code_.kt"]
import afield.frames
import afield.kratos.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshRenderer
import afield.stdlib.MeshView
import afield.stdlib.showMeshGrid
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Launch (Alt-R), then press "Sweep the grid".
// Install once: uv sync --project optional/kratos --frozen
//   (or, where Kratos has no wheels: podman build --platform linux/amd64
//    -t field-kratos:10.4.0 optional/kratos)
//
// The question: WHICH ARM LENGTH AND WHICH CONDUCTIVITY PUT THE TARGET
// TEMPERATURE AT ONE POINT INSIDE THE PART?
//
// Stationary conduction gives T = Tb + (q/k)*phi(shape), so the answer is a
// curve k* = q*phi(arm)/(T-Tb) through the design plane — the dark valley in
// the heat map. Nothing here knows that; the sweep measures it.

val settings = AtomicReference(listOf(7.0, KratosStudyDemo.TARGET))
val action = AtomicReference("")
val done = AtomicInteger(0)
val cancelling = AtomicReference(false)
fun change(index: Int, value: Double) {
    settings.updateAndGet { old -> old.mapIndexed { i, v -> if (i == index) value else v } }
}
controlGroup("controls") {
    slider("Grid points per axis", min=3.0, max=13.0, step=2.0, initialValue=7.0) { change(0,it) }
    slider("Target temperature (K)", min=293.7, max=294.6, step=0.05,
           initialValue=KratosStudyDemo.TARGET) { change(1,it) }
    button("Sweep the grid") { if (it) action.set("grid") }
    button("Search instead") { if (it) action.set("search") }
    button("Cancel") { if (it) action.set("cancel") }
}
displayOpen("controls")

fun safe(value: Any?): String =
    value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
fun status(text: String) {
    display("status", "<div id='kratos-study-status' style='padding:12px;max-width:800px'>$text</div>")
    displayOpen("status")
}
// One of the two shapes a press can return. A named holder rather than a Pair of two
// nullables, so the branch that consumes it reads as what it is.
class StudyOutcome(val landscape: StudyLandscape?,
                   val optimisation: StudyOptimisation<KratosStudyDemo.Design>?)

status("Starting the optional worker…")

frames {
    if (!ManifoldLibrary.isAvailable) {
        status("Manifold unavailable: ${safe(ManifoldLibrary.describe())}")
        return@frames
    }
    val config = KratosConfig.candidates().first()
    val opening = KratosSession.openAsync(
        KratosStudyDemo.BOUNDS, KratosStudyDemo.RESOLUTION, config)
    var session: KratosSession? = null
    try {
        while (!opening.isDone) frame()
        val worker = opening.get()
        session = worker

        // One study object for the whole session, so its cache survives every press:
        // re-running an overlapping grid re-solves only the points it has not seen.
        var target = settings.get()[1]
        var study = KratosStudyDemo.study(worker, target) {
            done.incrementAndGet()
            // Throwing from the progress callback aborts the sweep — that is what
            // Cancel does, rather than poisoning one point with a null result.
            if (cancelling.get()) error("cancelled")
        }
        var running: CompletableFuture<StudyOutcome>? = null
        var expected = 0
        var resultText = "Press <b>Sweep the grid</b>. Each point is one Kratos solve."
        var previous = ""

        while (true) {
            val values = settings.get()
            val command = action.getAndSet("")

            // A new target is a different question, so it needs a fresh study — but the
            // solves are the same solves, so nothing is thrown away that could be reused.
            if (values[1] != target && running == null) {
                target = values[1]
                study = KratosStudyDemo.study(worker, target) {
                    done.incrementAndGet()
                    if (cancelling.get()) error("cancelled")
                }
            }

            if (command == "cancel" && running != null) {
                cancelling.set(true)
                resultText = "Cancelling after the solve in flight…"
            } else if ((command == "grid" || command == "search") && running == null) {
                val n = values[0].toInt()
                cancelling.set(false)
                done.set(0)
                expected = if (command == "grid") n*n else 2*(n*n/2).coerceAtLeast(4)
                val active = study
                running = CompletableFuture.supplyAsync<StudyOutcome> {
                    if (command == "grid") StudyOutcome(active.grid(n, n), null)
                    else StudyOutcome(null, active.optimise((n*n/2).coerceAtLeast(4), seed = 7L))
                }
                resultText = "Solving $expected designs on one background grid…"
            }

            val pending = running
            if (pending != null && pending.isDone) {
                running = null
                try {
                    val outcome = pending.get()
                    val landscape = outcome.landscape
                    val optimisation = outcome.optimisation
                    val figure = if (landscape != null)
                        KratosStudyFigure.landscape(landscape, optimisation,
                            width = 860, height = 540, scale = ColourScale.STRETCHED)
                    else KratosStudyFigure.convergence(optimisation!!, null, 860, 420)
                    display("landscape", figure)
                    displayOpen("landscape")

                    val winner = landscape?.best ?: optimisation?.best
                    if (winner == null) {
                        resultText = "Every design was infeasible."
                    } else {
                        val design = study.designAt(winner.coordinates)
                        // The other half of what a study owes: not only "this design is
                        // best" but here it is, and here is what it does.
                        val field = study.resultAt(winner.coordinates)
                        showMeshGrid("Winning body · drag to orbit" to
                                         KratosDemo.body(design.armEnd).surface(),
                            channel="geometry", cellWidth=380,
                            view=MeshView(yawDegrees=-28.0, pitchDegrees=18.0, wireframe=false))
                        showMeshGrid("Its temperature, mid-plane" to field.sliceMesh(samples=110),
                            channel="slice", cellWidth=380, renderer=MeshRenderer.CPU,
                            view=MeshView(yawDegrees=0.0, pitchDegrees=0.0, wireframe=false))
                        displayOpen("geometry")
                        displayOpen("slice")
                        val predicted = KratosStudyDemo.predictedConductivity(winner, target)
                        val probe = winner.metrics["probe"] ?: Double.NaN
                        val peak = winner.metrics["peak"] ?: Double.NaN
                        resultText = "<span id='kratos-study-result' " +
                            "data-arm='${design.armEnd}' data-k='${design.conductivity}'>" +
                            "<b>Best design</b> arm ${"%.3f".format(design.armEnd)} m · " +
                            "conductivity ${"%.3f".format(design.conductivity)} W/(m K)<br>" +
                            "Probe ${"%.4f".format(probe)} K · " +
                            "asked for ${"%.2f".format(target)} K · " +
                            "miss ${"%.4f".format(winner.objective ?: Double.NaN)} K<br>" +
                            "Peak in the part ${"%.4f".format(peak)} K<br>" +
                            "Closed form says this arm wants k = " +
                            "${if (predicted == null) "?" else "%.3f".format(predicted)} " +
                            "W/(m K)<br>" +
                            "${study.solveCount} solves · ${study.cacheHits} served from cache · " +
                            "mean ${"%.0f".format(study.meanSolveMs)} ms<br>" +
                            (landscape?.let { "${it.feasible.size}/${it.samples.size} feasible<br>" } ?: "") +
                            (optimisation?.let { "${safe(it.run.summary())}<br>" +
                                (it.control?.let { c -> "${safe(c.summary())}<br>" } ?: "") } ?: "") +
                            "</span>"
                    }
                } catch (failure: Exception) {
                    val why = failure.cause?.message ?: failure.message
                    resultText = if (why != null && why.contains("cancelled"))
                        "Cancelled after ${done.get()} solves. Nothing solved was lost — " +
                            "press Sweep again and the cache serves it."
                    else "Sweep failed: ${safe(why)}"
                }
                cancelling.set(false)
            }

            val progress = if (running == null) "" else
                "<br>${done.get()} of $expected designs solved…"
            val text = "<b>${worker.state}</b> · ${worker.formulation}<br>" +
                "Background ${worker.domain.id} · builds ${worker.domain.buildCount} · " +
                "${worker.domain.nodeCount} nodes · ${worker.domain.cellCount} tetrahedra<br>" +
                "Asking for ${"%.2f".format(values[1])} K at " +
                "(${KratosStudyDemo.PROBE.x}, ${KratosStudyDemo.PROBE.y}, " +
                "${KratosStudyDemo.PROBE.z}) m<br>" +
                resultText + progress +
                "<br><i>Dark is close. Every arm length is a reclassification of the same " +
                "grid — builds stays 1 however many designs you solve.</i>"
            if (text != previous) { status(text); previous = text }
            frame()
        }
    } catch (failure: Exception) {
        status("Worker unavailable: ${safe(failure.cause?.message ?: failure.message)}<br>" +
            "Install with <code>uv sync --project optional/kratos --frozen</code>, or build " +
            "the container image where Kratos publishes no wheel, then launch again.")
    } finally {
        if (session != null) session!!.close() else opening.thenAccept { it.close() }
    }
}
```

