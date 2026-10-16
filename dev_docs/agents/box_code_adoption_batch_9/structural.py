"""Prepare immutable queued solve requests and publication-based redraws; no writes without --apply."""
from pathlib import Path
import yaml,sys,difflib
ROOT=Path.cwd();path=ROOT/'dev_docs/agents/transcripts/kratos_structural_playground.yaml'
original=path.read_text();doc=yaml.safe_load(original);code=doc['operations'][0]['create']['properties']['code']
def replace(old,new):
 global code
 assert old in code,old
 code=code.replace(old,new)
replace('// arm length, element size, element order, tip load (kN), deflection exaggeration', '''// A queued request keeps the physical inputs from the button press, including its load.
data class SolveRequest(
    val arm: Double, val elementSize: Double, val order: Int, val loadKN: Double, val epoch: Long,
) {
    val meshKey get() = Triple(arm, elementSize, order)
    val comparisonKey get() = Triple(arm, elementSize, loadKN)
}
data class PendingSolve(
    val request: SolveRequest,
    val future: java.util.concurrent.CompletableFuture<SimulationResult?>,
)

// arm length, element size, element order, tip load (kN), deflection exaggeration''')
replace('var pending: java.util.concurrent.CompletableFuture<SimulationResult?>? = null\n    // Element orders still to solve. "Solve" queues one, "Compare orders" queues both.\n    val queue = ArrayDeque<Int>()','''var opening: java.util.concurrent.CompletableFuture<KratosSession>? = null
    var pending: PendingSolve? = null
    // Compare orders copies one snapshot; later slider edits cannot mix its two loads or shapes.
    val queue = ArrayDeque<SolveRequest>()''')
replace('var shape = Pair(-1.0, -1.0)\n    var drawnFor = Triple(-1.0, -1, 0)', '''var comparison: Triple<Double, Double, Double>? = null
    var requestEpoch = 0L
    var publications = 0L
    var latestRequest: SolveRequest? = null
    var drawnFor: Pair<Double, Long>? = null
    fun request(values: List<Double>, order: Int) =
        SolveRequest(values[0], values[1], order, values[3], requestEpoch)
    fun isCurrent(request: SolveRequest): Boolean {
        val now = settings.get()
        return action.get() != "cancel" && request.epoch == requestEpoch &&
            request.comparisonKey == Triple(now[0], now[1], now[3])
    }''')
replace('''// A different part is a different answer, so old results stop being comparable.
            if (Pair(values[0], values[1]) != shape) {
                shape = Pair(values[0], values[1]); results.clear()
            }''','''// Arm, mesh size and load define a comparison. Clear the picture when any changes.
            val currentComparison = Triple(values[0], values[1], values[3])
            if (currentComparison != comparison) {
                comparison = currentComparison
                requestEpoch++
                queue.clear()
                if (pending != null) session?.cancel()
                results.clear()
                latestRequest = null
                publications++
                drawnFor = null
                displayClear("stress")
                resultText = "Settings changed. Press <b>Solve</b> or <b>Compare orders</b>."
            }''')
replace('session?.cancel(); pending = null; queue.clear()', '''requestEpoch++
                    session?.cancel()
                    queue.clear()
                    // Keep the future until it settles, so a new solve cannot overlap this worker.''')
replace('"solve" -> queue.addLast(values[2].toInt())\n                "compare" -> { queue.clear(); queue.addLast(1); queue.addLast(2) }','''"solve" -> queue.addLast(request(values, values[2].toInt()))
                "compare" -> {
                    queue.clear()
                    val first = request(values, 1)
                    queue.addLast(first)
                    queue.addLast(first.copy(order = 2))
                }''')
replace('val order = queue.removeFirst()\n                val key = Triple(values[0], values[1], order)','''val submitted = queue.removeFirst()
                if (!isCurrent(submitted)) continue
                val order = submitted.order
                val key = submitted.meshKey''')
replace('session?.close(); session = null','session?.close(); session = null; meshed = null')
replace('status("Meshing at h = ${num(values[1], 3)} m, order $order…")','status("Meshing at h = ${num(submitted.elementSize, 3)} m, order $order…")')
replace('Tetgen.tetrahedralize(bracket(values[0]).surface(),\n                                                      targetEdgeLength = values[1], order = order)', 'Tetgen.tetrahedralize(bracket(submitted.arm).surface(),\n                                                      targetEdgeLength = submitted.elementSize, order = order)')
replace('''val opening = KratosSession.openMeshAsync(built, config)
                    while (!opening.isDone) frame()
                    session = opening.get()
                    meshed = key''','''val starting = KratosSession.openMeshAsync(built, config)
                    opening = starting
                    while (!starting.isDone) frame()
                    session = starting.get()
                    opening = null
                    meshed = key''')
replace('pending = session!!.solveAsync(loads(values[0], values[3]))','''if (!isCurrent(submitted)) continue
                pending = PendingSolve(submitted, session!!.solveAsync(loads(submitted.arm, submitted.loadKN)))''')
replace('''if (ready != null && ready.isDone) {
                pending = null
                try {
                    ready.get()?.let { results[it.domain.nodesPerCell] = it }
                } catch (failure: Exception) {
                    queue.clear()
                    resultText = "Solve failed: ${safe(failure.cause?.message ?: failure.message)}"
                }
            }''','''if (ready != null && ready.future.isDone) {
                pending = null
                try {
                    val result = ready.future.get()
                    if (isCurrent(ready.request)) {
                        if (result == null) {
                            resultText = "Solve completed without a result."
                        } else {
                            results[result.domain.nodesPerCell] = result
                            latestRequest = ready.request
                            // A replacement under the same order is still a new publication.
                            publications++
                        }
                    }
                } catch (failure: Exception) {
                    if (isCurrent(ready.request)) {
                        queue.clear()
                        resultText = "Solve failed: ${safe(failure.cause?.message ?: failure.message)}"
                    }
                }
            }''')
replace('''// Redraw when the set of results changes or the exaggeration slider moves, so
            // the scale is a display control and never costs a solve.
            val stamp = Triple(values[4], results.size, results.keys.sum())''','''// Every accepted result redraws, even when it replaces the same element order.
            // Exaggeration changes the view without another solve.
            val stamp = values[4] to publications''')
replace('drawnFor = stamp\n                val scale = values[4]', 'drawnFor = stamp\n                val displayed = checkNotNull(latestRequest)\n                val scale = values[4]')
replace('"data-arm=\'${values[0]}\' " +','"data-arm=\'${displayed.arm}\' " +\n                    "data-load-kn=\'${displayed.loadKN}\' " +\n                    "data-element-size=\'${displayed.elementSize}\' " +')
replace('''} finally {
        session?.close()
    }''','''} finally {
        // Stop can arrive while a worker is opening; close it when that future completes.
        opening?.whenComplete { late, _ -> late?.close() }
        session?.close()
    }''')
# Replace only this literal body; preserve descriptions, frames, graph and authored fragment markers.
start=original.index('        code: |\n')+len('        code: |\n')
updated=original[:start]+''.join('          '+line+'\n' for line in code.splitlines())
yaml.safe_load(updated)
assert updated.count('//%-')==original.count('//%-')
(ROOT/'build/box-code-revision/critical/structural.diff').write_text(''.join(difflib.unified_diff(original.splitlines(keepends=True),updated.splitlines(keepends=True),fromfile=str(path),tofile=str(path))))
if '--apply' in sys.argv:path.write_text(updated)
print('structural request/publication patch prepared')
