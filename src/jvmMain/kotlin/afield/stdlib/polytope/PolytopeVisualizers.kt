@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.RunsAt
import afield.h
import afield.newRoot
import afield.registerValuePrinter

/**
 * The single place this package knows that Field has a screen — and it is one function long.
 *
 * Each printer registered here reduces its value to one of the plain summary data classes in
 * `Summaries.kt` — `PolytopeTopologySummary`, `Polytope4Summary`, and so on — and hands *that* to
 * Field's ordinary reflective value visualizer. So a topology, a transform result or a
 * canonicalization plan left as a box's last expression prints something useful, while nothing in
 * the topology depends on `FLine`, meshes or a renderer, and `println(x.summary())` shows the same
 * facts because it is the same value.
 */

/**
 * Register a value printer for each polytope type that has a summary, so live boxes print something
 * useful without importing a visualizer.
 *
 * Called by Field for you — `@RunsAt(newRoot)` runs it once when a document root is created — and
 * harmless to call again, since registration is keyed by class in a process-wide map and simply
 * replaces. Each printer runs its summary on demand, which for the topology-bearing types means
 * running [PolytopeTopology.validate], so a printed value is a fresh verdict rather than a cached one.
 */
@RunsAt(newRoot)
fun registerPolytopeVisualizers() {
    registerValuePrinter<PolytopeTopology> { it.summary().h }
    registerValuePrinter<Realization3> { it.summary().h }
    registerValuePrinter<PolyhedralObject> { it.summary().h }
    registerValuePrinter<ValidationResult> { it.summary().h }
    registerValuePrinter<TransformDeployment> { it.summary().h }
    registerValuePrinter<PolytopeTransformResult> { it.summary().h }
    registerValuePrinter<TriangulatedGeometry> { it.summary().h }
    registerValuePrinter<CombinatorialOrbits> { it.summary().h }
    registerValuePrinter<RelaxationPlan> { it.summary().h }
    registerValuePrinter<Polytope4> { it.summary().h }
}
