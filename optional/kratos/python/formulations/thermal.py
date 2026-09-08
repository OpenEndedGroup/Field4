"""Stationary conduction on a cut background, via Kratos' `EmbeddedLaplacianElement3D4N`.

The element derives its split geometry from the signed nodal `DISTANCE` at assembly time and
imposes the skin temperature weakly, with a Nitsche penalty scaled by `gamma/h` inside the element
(`PENALTY_COEFFICIENT` is the dimensionless `gamma`). That is the whole reason a geometry edit
costs a classification and not a mesh: nothing about the element or the ModelPart changes, only the
numbers on the nodes.

Limits worth knowing before reading a picture: a small cut is ill-conditioned and this profile's
only remedy is the penalty plus an epsilon away from exact zeros; a temperature is reported at
every node including the extension values at negative nodes of cut elements, and only the positive
region is physical.
"""
import time

import numpy as np

from .base import Formulation


class StationaryThermal(Formulation):
    name = 'thermal.stationary'
    applications = ('KratosMultiphysics', 'KratosMultiphysics.ConvectionDiffusionApplication')
    element = 'EmbeddedLaplacianElement3D4N'
    summary = 'stationary conduction in an embedded solid, uniform Dirichlet skin (Nitsche)'
    max_resolution = 32

    def variables(self):
        K = self.K
        return (K.TEMPERATURE, K.CONDUCTIVITY, K.HEAT_FLUX, K.DISTANCE)

    def dofs(self):
        return (self.K.TEMPERATURE,)

    def configure(self, model_part):
        import KratosMultiphysics as K
        import KratosMultiphysics.ConvectionDiffusionApplication as C
        self.C = C
        settings = K.ConvectionDiffusionSettings()
        settings.SetUnknownVariable(K.TEMPERATURE)
        settings.SetDiffusionVariable(K.CONDUCTIVITY)
        settings.SetVolumeSourceVariable(K.HEAT_FLUX)
        model_part.ProcessInfo[K.CONVECTION_DIFFUSION_SETTINGS] = settings

    def fields(self):
        return [
            {'name': 'temperature', 'kind': 'nodal', 'components': 1, 'unit': 'K'},
            {'name': 'distance', 'kind': 'nodal', 'components': 1, 'unit': 'm'},
            {'name': 'cellState', 'kind': 'cell', 'components': 1, 'unit': ''},
        ]

    def solve(self, geometry=None, distances=None, conductivity=1., source=1.,
              boundary_value=0., penalty=10., **ignored):
        K, C = self.K, self.C
        values = np.asarray([conductivity, source, boundary_value, penalty], float)
        if not np.isfinite(values).all() or conductivity <= 0 or penalty <= 0:
            raise ValueError('finite coefficients required; conductivity and penalty must be positive')

        d, active, cut, used, scope, classification_ms = self.classify(geometry, distances)

        start = time.perf_counter()
        self.mp.ProcessInfo[K.PENALTY_COEFFICIENT] = float(penalty)
        # Reactivate as well as deactivate. Upstream Initialize only deactivates;
        # copying that loop blindly leaves holes after a primitive moves back.
        for i, node in enumerate(self.nodes):
            node.Set(K.ACTIVE, bool(used[i]))
            node.Free(K.TEMPERATURE)
            if not used[i]:
                node.Fix(K.TEMPERATURE)
            for var, value in ((K.DISTANCE, d[i]), (K.TEMPERATURE, boundary_value),
                               (K.CONDUCTIVITY, conductivity), (K.HEAT_FLUX, source)):
                node.SetSolutionStepValue(var, float(value))
        for i, element in enumerate(self.elements):
            element.Set(K.ACTIVE, bool(active[i]))
            element.SetValue(C.EMBEDDED_SCALAR, float(boundary_value))
        linear = K.SkylineLUFactorizationSolver()
        builder = K.ResidualBasedBlockBuilderAndSolver(linear)
        scheme = K.ResidualBasedIncrementalUpdateStaticScheme()
        strategy = K.ResidualBasedLinearStrategy(self.mp, scheme, builder, False, True, False, False)
        strategy.SetEchoLevel(0)
        strategy.Check()
        model_ms = (time.perf_counter() - start) * 1000

        try:
            assembly_ms, solve_ms, relative = self.direct_solve(strategy, builder, scheme)
            u = self.nodal(K.TEMPERATURE)
            if not np.isfinite(u).all():
                raise ArithmeticError(
                    'Kratos returned nonfinite temperatures; inspect cut quality/penalty/resolution')
        finally:
            strategy.Clear()

        arrays = {'temperature': u, 'distance': np.asarray(d, dtype='<f8'),
                  'cellState': self.grid.cell_state(active, cut)}
        return arrays, {
            'backgroundId': self.grid.id, 'buildCount': self.grid.build_count,
            'activeElements': int(active.sum()), 'cutElements': int(cut.sum()),
            'classificationMs': classification_ms, 'modelUpdateMs': model_ms,
            'assemblyAndSolveMs': assembly_ms + solve_ms, 'assemblyMs': assembly_ms, 'solveMs': solve_ms,
            'relativeLinearResidual': relative,
            'linearSolver': 'SkylineLUFactorizationSolver',
            'status': 'direct solve completed; relative linear residual <= 1e-7',
            'classificationScope': scope, 'matrixReused': False,
            'temperatureMin': float(u[d > 0].min()), 'temperatureMax': float(u[d > 0].max()),
        }
