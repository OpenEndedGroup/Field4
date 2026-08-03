@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.RunsAt
import afield.h
import afield.newRoot
import afield.registerValuePrinter

/**
 * The single place this package knows that Field has a screen — and it is one function long.
 *
 * The mathematical types contain no HTML and no presentation logic. Instead each printer registered
 * here reduces its value to one of the plain public summary data classes (`PolytopeTopologySummary`,
 * `Polytope4Summary`, and so on) and hands *that* to Field's ordinary reflective value visualizer. So
 * leaving a topology, a transform result or a canonicalization plan as the last expression in a box
 * prints something useful, while nothing in the topology depends on `FLine`, meshes or a renderer.
 *
 * The consequence worth knowing: `println(x.summary())` and the visualizer show the same facts,
 * because they are the same value. Nothing is computed only for display.
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
