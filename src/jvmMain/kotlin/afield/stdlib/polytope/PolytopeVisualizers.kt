package afield.stdlib.polytope

import afield.RunsAt
import afield.h
import afield.newRoot
import afield.registerValuePrinter

/**
 * Field-facing value-printer registration for the polytope algebra.
 *
 * The mathematical types deliberately contain no HTML. Each printer reduces its value to one of
 * the public data-class summaries in [Summaries.kt], then lets Field's ordinary reflective value
 * visualizer format that value. Thus the useful last expressions in a live box are informative,
 * while topology remains independent of FLine, meshes, and presentation.
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
