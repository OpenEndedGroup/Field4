"""Linear-elastic small-displacement mechanics on a body-fitted tetrahedral mesh.

The third formulation and the first that is **not** embedded, because Kratos has no immersed
structural element -- 312 public names in `StructuralMechanicsApplication` and not one of them
matches `embedded`, `shifted` or `sbm`. So this one takes a mesh whose boundary really is the
part's surface, produced by TetGen from Manifold's triangulation on Field's side.

That is not a fallback. It is the reason: stress concentrates at re-entrant corners and fillets,
and a level-set route rounds every sharp edge by ~0.4 of a background cell at *every* resolution,
which is exactly where the answer matters. `dev_docs/TETGEN.md` has the measurement.

What changes for the bridge, and what does not:

  * the mesh arrives over the wire (`createBodyMesh`) instead of being generated here from bounds
    and a resolution, so `self.grid` is a `SuppliedMesh` rather than a `BackgroundGrid`;
  * there is no level set, so no `distance` field and no cut cells;
  * a geometry edit means a new mesh, and therefore a new session. Nothing here pretends the
    no-remesh invariant survives -- it belongs to the embedded formulations.

The mesh also chooses the element. A 4-node tetrahedron has constant strain and locks badly in
bending; a 10-node one has linear strain and does not. Field decides which by what it asks TetGen
for, and this formulation reads the width of what arrives -- there is no parameter, because a
parameter could disagree with the mesh.

Everything else -- protocol, framing, revisions, binary arrays, transport -- is untouched.
"""
import time

import numpy as np

from .base import Formulation


class BodyFittedStructural(Formulation):
    name = 'structural.bodyFitted'
    applications = ('KratosMultiphysics',
                    'KratosMultiphysics.StructuralMechanicsApplication',
                    'KratosMultiphysics.LinearSolversApplication')
    element = 'SmallDisplacementElement3D4N'
    #: Both tetrahedron orders, picked from the mesh Field sends rather than from a parameter --
    #: the mesh already decided, and disagreeing with it is not a thing anyone wants to express.
    #:
    #: The 4-node element has constant strain, which makes it far too stiff in bending: a cantilever
    #: under self-weight reaches 13%, 36% and 62% of Euler-Bernoulli over three refinements, still
    #: far short and climbing slowly. The 10-node element has linear strain, does not lock that way,
    #: and reaches 95% on the coarsest of those three. `dev_docs/TETGEN.md` has both tables.
    elements = {4: 'SmallDisplacementElement3D4N', 10: 'SmallDisplacementElement3D10N'}
    summary = 'linear-elastic small displacement on a body-fitted tetrahedral mesh (TetGen)'
    supplied_mesh = True

    def variables(self):
        K = self.K
        return (K.DISPLACEMENT, K.REACTION, K.VOLUME_ACCELERATION)

    def dofs(self):
        K = self.K
        return ((K.DISPLACEMENT_X, K.REACTION_X),
                (K.DISPLACEMENT_Y, K.REACTION_Y),
                (K.DISPLACEMENT_Z, K.REACTION_Z))

    def configure(self, model_part):
        import KratosMultiphysics.StructuralMechanicsApplication as SMA
        import KratosMultiphysics.LinearSolversApplication  # noqa: F401  (registers SparseLUSolver)
        self.SMA = SMA

    def finish(self, model_part):
        """One point-load condition per node, created once and mostly carrying zero.

        A nodal force needs a condition in Kratos, and creating conditions per solve would change
        the DOF set every time. Creating them all up front costs one condition per node and makes
        a load change nothing but a value change -- the same trick that keeps the embedded
        formulations' element set fixed.
        """
        properties = self.properties
        for i, node in enumerate(self.nodes, 1):
            model_part.CreateNewCondition('PointLoadCondition3D1N', i, [node.Id], properties)
        self.conditions = list(model_part.Conditions)

    def fields(self):
        return [
            {'name': 'displacement', 'kind': 'nodal', 'components': 3, 'unit': 'm'},
            {'name': 'vonMises', 'kind': 'cell', 'components': 1, 'unit': 'Pa'},
        ]

    # ------------------------------------------------------------------------------------------

    def _region_mask(self, region):
        """Nodes inside an axis-aligned box, given as [minx,miny,minz,maxx,maxy,maxz]."""
        if region is None:
            return np.zeros(self.grid.node_count, bool)
        box = np.asarray(region, float)
        if box.shape != (6,) or not np.isfinite(box).all() or not (box[3:] >= box[:3]).all():
            raise ValueError('a region must be six finite ordered values')
        p = self.grid.positions
        return np.all((p >= box[:3] - 1e-12) & (p <= box[3:] + 1e-12), axis=1)

    def solve(self, geometry=None, distances=None, young_modulus=2.1e11, poisson_ratio=0.3,
              density=7850.0, gravity=(0.0, 0.0, -9.81), clamp=None, load_region=None,
              load=(0.0, 0.0, 0.0), **ignored):
        K, SMA = self.K, self.SMA
        if geometry is not None or distances is not None:
            raise ValueError(
                'structural.bodyFitted solves the mesh it was given; it takes no level set. '
                'Send a new mesh with createBodyMesh to change the geometry.')
        numbers = np.asarray([young_modulus, poisson_ratio, density, *gravity, *load], float)
        if not np.isfinite(numbers).all():
            raise ValueError('material, gravity and load values must be finite')
        if young_modulus <= 0 or density <= 0:
            raise ValueError('young_modulus and density must be positive')
        if not -1.0 < poisson_ratio < 0.5:
            raise ValueError('poisson_ratio must lie in (-1, 0.5); 0.5 is incompressible and '
                             'this element cannot represent it')

        start = time.perf_counter()
        self.properties.SetValue(K.YOUNG_MODULUS, float(young_modulus))
        self.properties.SetValue(K.POISSON_RATIO, float(poisson_ratio))
        self.properties.SetValue(K.DENSITY, float(density))
        self.properties.SetValue(K.CONSTITUTIVE_LAW, SMA.LinearElastic3DLaw())

        clamped = self._region_mask(clamp)
        if not clamped.any():
            raise ValueError('no node lies in the clamp region; the body would be unrestrained '
                             'and the stiffness matrix singular')
        loaded = self._region_mask(load_region)
        # The requested force is the total over the region, so the answer does not change when the
        # mesh is refined and the region picks up more nodes.
        #
        # Equally, not by tributary area, which makes the traction slightly non-uniform near the
        # loaded face -- a Saint-Venant end effect that decays within a diameter and shrinks under
        # refinement. On a 10-node mesh it is a little further from consistent still (the consistent
        # nodal loads for a quadratic face are negative at the corners), but the resultant is exact
        # either way, so equilibrium and everything away from the end are unaffected.
        share = (np.asarray(load, float) / loaded.sum()) if loaded.any() else np.zeros(3)

        acceleration = [float(g) for g in gravity]
        for i, node in enumerate(self.nodes):
            node.SetSolutionStepValue(K.VOLUME_ACCELERATION, acceleration)
            for dof in (K.DISPLACEMENT_X, K.DISPLACEMENT_Y, K.DISPLACEMENT_Z):
                node.Free(dof)
            if clamped[i]:
                node.Fix(K.DISPLACEMENT_X); node.Fix(K.DISPLACEMENT_Y); node.Fix(K.DISPLACEMENT_Z)
                node.SetSolutionStepValue(K.DISPLACEMENT, [0.0, 0.0, 0.0])
        for i, condition in enumerate(self.conditions):
            condition.SetValue(SMA.POINT_LOAD, list(share) if loaded[i] else [0.0, 0.0, 0.0])
        model_ms = (time.perf_counter() - start) * 1000

        import KratosMultiphysics.LinearSolversApplication as LS
        linear = LS.SparseLUSolver()
        builder = K.ResidualBasedBlockBuilderAndSolver(linear)
        scheme = K.ResidualBasedIncrementalUpdateStaticScheme()
        strategy = K.ResidualBasedLinearStrategy(self.mp, scheme, builder, True, True, False, False)
        strategy.SetEchoLevel(0)
        strategy.Check()
        try:
            assembly_ms, solve_ms, relative = self.direct_solve(strategy, builder, scheme)
            u = self.nodal_vector(K.DISPLACEMENT)
            if not np.isfinite(u).all():
                raise ArithmeticError('Kratos returned nonfinite displacements; check the clamp '
                                      'region actually restrains all six rigid-body modes')
            start = time.perf_counter()
            # The peak over the element's integration points, not the first one. A 4-node element
            # has exactly one, so this is unchanged there; a 10-node element has four and its stress
            # varies across them, and reporting a corner-ish sample would understate exactly the
            # concentrations these elements were introduced to resolve.
            stress = np.array([max(element.CalculateOnIntegrationPoints(
                SMA.VON_MISES_STRESS, self.mp.ProcessInfo)) for element in self.elements],
                dtype='<f8')
            stress_ms = (time.perf_counter() - start) * 1000
        finally:
            strategy.Clear()

        magnitude = np.linalg.norm(u, axis=1)
        arrays = {'displacement': u, 'vonMises': stress}
        return arrays, {
            'backgroundId': self.grid.id, 'buildCount': self.grid.build_count,
            'nodeCount': self.grid.node_count, 'cellCount': self.grid.cell_count,
            'nodesPerCell': self.nodes_per_cell, 'element': self.element,
            'clampedNodes': int(clamped.sum()), 'loadedNodes': int(loaded.sum()),
            'modelUpdateMs': model_ms, 'assemblyMs': assembly_ms, 'solveMs': solve_ms,
            'assemblyAndSolveMs': assembly_ms + solve_ms, 'stressRecoveryMs': stress_ms,
            'relativeLinearResidual': relative,
            'linearSolver': 'SparseLUSolver',
            'status': 'direct solve completed; relative linear residual <= 1e-7',
            'classificationScope': 'none (body-fitted)', 'matrixReused': False,
            'displacementMax': float(magnitude.max()),
            'vonMisesMax': float(stress.max()), 'vonMisesMean': float(stress.mean()),
        }
