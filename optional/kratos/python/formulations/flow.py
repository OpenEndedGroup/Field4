"""Incompressible flow past an embedded obstacle, via `EmbeddedWeaklyCompressibleNavierStokes3D4N`.

The second formulation, and the one that says whether the registry was worth building. It shares
the background grid, the classification, the protocol, the revision gate and the transport with
`thermal.stationary`, and differs in every way a physics can:

| | `thermal.stationary` | `flow.embedded` |
|---|---|---|
| solved region | **inside** the authored solid | **outside** it — the solid is an obstacle |
| unknowns per node | 1 (temperature) | 4 (velocity, pressure) |
| in time | stationary | transient, BDF2 inside the element |
| algebra | one linear solve | Newton–Raphson per step |
| small cuts | our own epsilon | `DistanceModificationProcess`, in C++ |

The reversed region is the trap. Kratos wants positive `DISTANCE` on the side it solves, so
conduction and flow ask for opposite signs from the same authored geometry; `solve_region` is
where that is decided, once, in `background.BackgroundGrid.classify`.

The element is the maintained one, not the legacy `EmbeddedNavierStokes`. That one zeroes the
velocity rows of negative nodes and leans on the penalty term for a diagonal, which left this
configuration with a structurally singular matrix on the first Newton step. Its own solver already
calls the weakly-compressible element the successor.

What this is not: a validated CFD setup. It is a lid-less channel with a free-stream boundary on
four sides, no turbulence model, no wall functions, and a first-order-in-space cut boundary. It
resolves a recirculating wake behind a bluff body at modest Reynolds number and it should not be
read as a drag prediction.
"""
import time

import numpy as np

from .base import Formulation


class EmbeddedFlow(Formulation):
    name = 'flow.embedded'
    applications = ('KratosMultiphysics', 'KratosMultiphysics.FluidDynamicsApplication',
                    'KratosMultiphysics.LinearSolversApplication')
    element = 'EmbeddedWeaklyCompressibleNavierStokes3D4N'
    summary = 'transient incompressible flow past an embedded obstacle, no-slip skin (Nitsche)'
    # Four unknowns a node and a Newton iteration a step: the same grid costs far more here than
    # it does for a scalar Laplacian, so the profile is honest about a lower ceiling.
    max_resolution = 20
    solve_region = 'outside'
    # BDF2 reads two previous steps.
    buffer_size = 3

    def variables(self):
        K = self.K
        import KratosMultiphysics.FluidDynamicsApplication as CFD
        # DENSITY and SOLID_FRACTION_VELOCITY are historical because this element reads its
        # material data from the nodes rather than from Properties
        # (`element_has_nodal_properties` in upstream's embedded solver).
        return (K.VELOCITY, K.PRESSURE, K.ACCELERATION, K.MESH_VELOCITY, K.BODY_FORCE,
                K.NODAL_AREA, K.REACTION, K.REACTION_WATER_PRESSURE, K.NORMAL,
                K.EXTERNAL_PRESSURE, K.DISTANCE, K.DISTANCE_GRADIENT, K.DENSITY,
                CFD.SOLID_FRACTION_VELOCITY, CFD.EMBEDDED_WET_PRESSURE, CFD.EMBEDDED_WET_VELOCITY)

    def dofs(self):
        K = self.K
        return ((K.VELOCITY_X, K.REACTION_X), (K.VELOCITY_Y, K.REACTION_Y),
                (K.VELOCITY_Z, K.REACTION_Z), (K.PRESSURE, K.REACTION_WATER_PRESSURE))

    def configure(self, model_part):
        import KratosMultiphysics.FluidDynamicsApplication as CFD
        import KratosMultiphysics.LinearSolversApplication  # noqa: F401  (registers SparseLUSolver)
        self.CFD = CFD
        # DYNAMIC_TAU scales the ASGS stabilization; the penalty is the Nitsche no-slip on the cut
        # boundary. Both are upstream's embedded defaults.
        model_part.ProcessInfo[self.K.DYNAMIC_TAU] = 1.0
        model_part.ProcessInfo[self.K.PENALTY_COEFFICIENT] = 10.0
        model_part.ProcessInfo[self.K.OSS_SWITCH] = 0

    def fields(self):
        return [
            {'name': 'velocity', 'kind': 'nodal', 'components': 3, 'unit': 'm/s'},
            {'name': 'pressure', 'kind': 'nodal', 'components': 1, 'unit': 'Pa'},
            {'name': 'distance', 'kind': 'nodal', 'components': 1, 'unit': 'm'},
            {'name': 'cellState', 'kind': 'cell', 'components': 1, 'unit': ''},
        ]

    # ----------------------------------------------------------------------------------------
    # Boundary conditions
    # ----------------------------------------------------------------------------------------

    def _apply_channel(self, inlet_speed):
        """A free-stream tunnel: velocity fixed on the inlet and the four lateral faces, pressure
        fixed on the outlet, and nothing fixed on the obstacle — the element imposes no-slip there.

        Fixing velocity on the laterals rather than adding slip conditions keeps this to nodal
        Dirichlet data, so the structured grid needs no surface conditions at all. It also makes
        the domain a wind tunnel with a rigid free stream, which is the boundary condition the
        picture should be read against.
        """
        K = self.K
        lo = np.asarray(self.grid.bounds[:3], float)
        hi = np.asarray(self.grid.bounds[3:], float)
        p = self.grid.positions
        outlet = p[:, 0] >= hi[0] - 1e-12
        lateral = (
            (p[:, 0] <= lo[0] + 1e-12) |
            (p[:, 1] <= lo[1] + 1e-12) | (p[:, 1] >= hi[1] - 1e-12) |
            (p[:, 2] <= lo[2] + 1e-12) | (p[:, 2] >= hi[2] - 1e-12)
        ) & ~outlet
        for i, node in enumerate(self.nodes):
            node.Free(K.VELOCITY_X); node.Free(K.VELOCITY_Y); node.Free(K.VELOCITY_Z)
            node.Free(K.PRESSURE)
            if lateral[i]:
                node.Fix(K.VELOCITY_X); node.Fix(K.VELOCITY_Y); node.Fix(K.VELOCITY_Z)
                node.SetSolutionStepValue(K.VELOCITY, [inlet_speed, 0.0, 0.0])
            if outlet[i]:
                node.Fix(K.PRESSURE)
                node.SetSolutionStepValue(K.PRESSURE, 0.0)
        return int(lateral.sum()), int(outlet.sum())

    def _seed_from_rest(self, inlet_speed):
        """Start from a uniform free stream rather than from rest, in every buffer level.

        BDF2 reads two previous steps, and starting them at zero means the first two steps solve a
        transient that has nothing to do with the question. A uniform stream is a consistent
        divergence-free initial state away from the obstacle, so the only transient left is the one
        the obstacle causes.
        """
        K = self.K
        stream = [inlet_speed, 0.0, 0.0]
        for node in self.nodes:
            for step in range(self.buffer_size):
                node.SetSolutionStepValue(K.VELOCITY, step, stream)
                node.SetSolutionStepValue(K.PRESSURE, step, 0.0)

    # ----------------------------------------------------------------------------------------

    def solve(self, geometry=None, distances=None, velocity=1.0, density=1.0, viscosity=0.01,
              steps=8, time_step=0.05, penalty=10.0, max_iterations=8,
              velocity_tolerance=1e-3, pressure_tolerance=1e-3, **ignored):
        K, CFD = self.K, self.CFD
        import KratosMultiphysics.LinearSolversApplication as LS
        coefficients = np.asarray([velocity, density, viscosity, time_step, penalty], float)
        if not np.isfinite(coefficients).all() or min(density, viscosity, time_step, penalty) <= 0:
            raise ValueError('density, viscosity, time step and penalty must be finite and positive')
        if type(steps) is not int or not 1 <= steps <= 200:
            raise ValueError('steps must be an integer in 1..200')

        d, active, cut, used, scope, classification_ms = self.classify(geometry, distances)

        start = time.perf_counter()
        self.properties.SetValue(K.DENSITY, float(density))
        self.properties.SetValue(K.DYNAMIC_VISCOSITY, float(viscosity))
        # Enormous on purpose: the weakly-compressible element divides by it, and upstream's own
        # material file uses 1e12 to mean "treat this as incompressible".
        self.properties.SetValue(K.SOUND_VELOCITY, 1.0e12)
        self.properties.SetValue(K.CONSTITUTIVE_LAW, CFD.Newtonian3DLaw())
        for node in self.nodes:
            node.SetSolutionStepValue(K.DENSITY, float(density))
            node.SetSolutionStepValue(CFD.SOLID_FRACTION_VELOCITY, [0.0, 0.0, 0.0])
            node.SetValue(K.SOUND_VELOCITY, 1.0e12)
        self.mp.ProcessInfo[K.PENALTY_COEFFICIENT] = float(penalty)
        self.mp.ProcessInfo[K.DELTA_TIME] = float(time_step)
        for i, node in enumerate(self.nodes):
            node.SetSolutionStepValue(K.DISTANCE, float(d[i]))
        self._seed_from_rest(velocity)
        fixed_velocity, fixed_pressure = self._apply_channel(velocity)

        # The element integrates itself in time, so the "scheme" only has to write the solution
        # back onto the nodes; BDF2's coefficients are recomputed per step from DELTA_TIME.
        scheme = K.ResidualBasedIncrementalUpdateStaticSchemeSlip(3, 4)
        bdf = K.TimeDiscretization.BDF(2)
        linear = LS.SparseLUSolver()
        builder = K.ResidualBasedBlockBuilderAndSolver(linear)
        criterion = K.MixedGenericCriteria([
            (K.VELOCITY, velocity_tolerance, velocity_tolerance * 1e-3),
            (K.PRESSURE, pressure_tolerance, pressure_tolerance * 1e-3)])
        criterion.SetEchoLevel(0)
        strategy = K.ResidualBasedNewtonRaphsonStrategy(
            self.mp, scheme, criterion, builder, int(max_iterations), False, True, False)
        strategy.SetEchoLevel(0)

        # Upstream's own small-cut guard, in C++: it perturbs near-zero distances, deactivates the
        # elements wholly inside the obstacle, and — the part a hand-rolled loop keeps getting
        # wrong — restores the node fixity it changed at the end of every step.
        modification = CFD.DistanceModificationProcess(self.model, K.Parameters(f"""{{
            "model_part_name": "{self.mp.Name}",
            "distance_threshold": 1e-3,
            "continuous_distance": true,
            "check_at_each_time_step": true,
            "avoid_almost_empty_elements": true,
            "deactivate_full_negative_elements": true
        }}"""))
        model_ms = (time.perf_counter() - start) * 1000

        start = time.perf_counter()
        converged, iterations = [], []
        try:
            strategy.Initialize()
            modification.ExecuteInitialize()
            for _ in range(steps):
                self.mp.CloneTimeStep(self.mp.ProcessInfo[K.TIME] + float(time_step))
                self.mp.ProcessInfo[K.STEP] += 1
                bdf.ComputeAndSaveBDFCoefficients(self.mp.ProcessInfo)
                modification.ExecuteInitializeSolutionStep()
                strategy.InitializeSolutionStep()
                strategy.Predict()
                converged.append(bool(strategy.SolveSolutionStep()))
                # The strategy has no Python getter for this; it writes it to the ProcessInfo.
                iterations.append(int(self.mp.ProcessInfo[K.NL_ITERATION_NUMBER]))
                strategy.FinalizeSolutionStep()
                modification.ExecuteFinalizeSolutionStep()
            solve_ms = (time.perf_counter() - start) * 1000
            u = self.nodal_vector(K.VELOCITY)
            pressure = self.nodal(K.PRESSURE)
            if not (np.isfinite(u).all() and np.isfinite(pressure).all()):
                raise ArithmeticError(
                    'Kratos returned a nonfinite flow field; reduce the time step or the Reynolds '
                    'number, or raise the resolution')
        finally:
            strategy.Clear()

        speed = np.linalg.norm(u, axis=1)
        fluid = d > 0
        arrays = {'velocity': u, 'pressure': pressure, 'distance': np.asarray(d, dtype='<f8'),
                  'cellState': self.grid.cell_state(active, cut)}
        span = float(min(np.subtract(self.grid.bounds[3:], self.grid.bounds[:3])))
        return arrays, {
            'backgroundId': self.grid.id, 'buildCount': self.grid.build_count,
            'activeElements': int(active.sum()), 'cutElements': int(cut.sum()),
            'classificationMs': classification_ms, 'modelUpdateMs': model_ms,
            'assemblyAndSolveMs': solve_ms, 'newtonLoopMs': solve_ms,
            'newtonIterations': iterations, 'newtonIterationsTotal': int(sum(iterations)),
            'steps': steps, 'timeStep': float(time_step),
            'simulatedTime': float(self.mp.ProcessInfo[K.TIME]),
            'convergedSteps': int(sum(converged)), 'allStepsConverged': all(converged),
            'linearSolver': 'SparseLUSolver',
            'status': ('all Newton steps converged' if all(converged) else
                       f'{sum(converged)}/{steps} Newton steps converged within {max_iterations} iterations'),
            'classificationScope': scope, 'matrixReused': False,
            'fixedVelocityNodes': fixed_velocity, 'fixedPressureNodes': fixed_pressure,
            # Reynolds number on the obstacle's own scale is what tells you whether to believe the
            # picture; the domain span is a stand-in for a characteristic length we do not measure.
            'reynoldsOnDomainSpan': float(density * velocity * span / viscosity),
            'speedMax': float(speed[fluid].max()), 'speedMean': float(speed[fluid].mean()),
            'pressureMin': float(pressure[fluid].min()), 'pressureMax': float(pressure[fluid].max()),
        }
