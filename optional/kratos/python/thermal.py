"""Fixed-grid embedded stationary conduction, using Kratos' real cut elements.

Positive distance is the physical interior. No mesher or replacement ModelPart
is called when geometry changes. Small-cut stabilization is the element's
Nitsche penalty only; this is a correctness/interaction prototype.
"""
import itertools
import copy
import time
import uuid

import numpy as np


def classify(points, geometry, depth=0):
    if depth > 32:
        raise ValueError('geometry expression exceeds 32 levels')
    if not isinstance(geometry.get('id'), str) or not geometry['id']:
        raise ValueError('geometry needs a stable nonempty id')
    op = geometry['op']
    if op == 'box':
        lo, hi = np.asarray(geometry['min'], dtype=float), np.asarray(geometry['max'], dtype=float)
        if lo.shape != (3,) or hi.shape != (3,) or not np.isfinite([lo, hi]).all() or not (hi > lo).all():
            raise ValueError('box bounds must be finite, ordered 3-vectors')
        q = np.abs(points - (lo + hi) / 2) - (hi - lo) / 2
        return -(np.linalg.norm(np.maximum(q, 0), axis=1) + np.minimum(np.max(q, axis=1), 0))
    if op not in ('union', 'difference', 'intersection'):
        raise ValueError('unsupported geometry operation: ' + str(op))
    a, b = (classify(points, geometry[key], depth + 1) for key in ('a', 'b'))
    return np.maximum(a, b) if op == 'union' else np.minimum(a, -b if op == 'difference' else b)


class ThermalDomain:
    def __init__(self, bounds, resolution):
        import KratosMultiphysics as K
        import KratosMultiphysics.ConvectionDiffusionApplication as C
        self.K, self.C = K, C
        K.Logger.GetDefaultOutput().SetSeverity(K.Logger.Severity.WARNING)
        start = time.perf_counter()
        lo, hi = np.asarray(bounds[:3], float), np.asarray(bounds[3:], float)
        if lo.shape != (3,) or hi.shape != (3,) or not np.isfinite([lo, hi]).all() or not (hi > lo).all():
            raise ValueError('domain bounds must be six finite ordered values')
        if type(resolution) is not int or not 4 <= resolution <= 32:
            raise ValueError('reference Skyline solver supports resolution 4..32; larger grids need a sparse solver profile')
        n = self.resolution = resolution
        self.bounds = list(bounds)
        self.id = str(uuid.uuid4())
        self.build_count = 1
        self.previous_geometry = None
        self.previous_distances = None
        self.model = K.Model()
        self.mp = m = self.model.CreateModelPart('thermal')
        m.ProcessInfo[K.DOMAIN_SIZE] = 3
        settings = K.ConvectionDiffusionSettings()
        settings.SetUnknownVariable(K.TEMPERATURE)
        settings.SetDiffusionVariable(K.CONDUCTIVITY)
        settings.SetVolumeSourceVariable(K.HEAT_FLUX)
        m.ProcessInfo[K.CONVECTION_DIFFUSION_SETTINGS] = settings
        for var in (K.TEMPERATURE, K.CONDUCTIVITY, K.HEAT_FLUX, K.DISTANCE):
            m.AddNodalSolutionStepVariable(var)
        self.positions = np.array(list(itertools.product(*(np.linspace(lo[i], hi[i], n+1) for i in range(3)))), dtype='<f8')
        for i, p in enumerate(self.positions, 1):
            m.CreateNewNode(i, *p).AddDof(K.TEMPERATURE)
        def index(p):
            return 1 + (p[0]*(n+1)+p[1])*(n+1)+p[2]
        prop = m.CreateNewProperties(1)
        cells = []
        for origin in itertools.product(range(n), repeat=3):
            for perm in itertools.permutations(range(3)):
                p, ids = list(origin), [index(origin)]
                for axis in perm:
                    p[axis] += 1
                    ids.append(index(p))
                if np.linalg.det(self.positions[np.array(ids[1:])-1] - self.positions[ids[0]-1]) < 0:
                    ids[1], ids[2] = ids[2], ids[1]
                m.CreateNewElement('EmbeddedLaplacianElement3D4N', len(cells)+1, ids, prop)
                cells.append(ids)
        self.cells = np.asarray(cells, dtype='<i4') - 1
        self.nodes, self.elements = list(m.Nodes), list(m.Elements)
        self.boundary = np.any((self.positions == lo) | (self.positions == hi), axis=1)
        self.build_ms = (time.perf_counter() - start) * 1000

    def solve(self, geometry=None, distances=None, conductivity=1., source=1., boundary_value=0., penalty=10.):
        K, C = self.K, self.C
        values = np.asarray([conductivity, source, boundary_value, penalty], float)
        if not np.isfinite(values).all() or conductivity <= 0 or penalty <= 0:
            raise ValueError('finite coefficients required; conductivity and penalty must be positive')
        start = time.perf_counter()
        reused = distances is None and geometry == self.previous_geometry and self.previous_distances is not None
        d = self.previous_distances.copy() if reused else (classify(self.positions, geometry) if distances is None else np.asarray(distances, dtype='<f8').copy())
        if d.shape != (len(self.nodes),) or not np.isfinite(d).all():
            raise ValueError('distance must contain one finite float64 per background node')
        if np.any(d[self.boundary] >= 0):
            raise ValueError('embedded body must be strictly inside the background domain; expand domain bounds')
        if not np.any(d > 0):
            raise ValueError('no positive nodes: geometry is empty or under-resolved; increase resolution')
        self.previous_geometry = copy.deepcopy(geometry) if distances is None else None
        self.previous_distances = d.copy() if distances is None else None
        # Avoid exact level-set zeros, matching upstream embedded solver semantics.
        eps = 1e-12 * max(np.subtract(self.bounds[3:], self.bounds[:3]))
        d = np.where(np.abs(d) < eps, np.where(d > 0, eps, -eps), d)
        active = np.any(d[self.cells] > 0, axis=1)
        cut = active & np.any(d[self.cells] < 0, axis=1)
        used = np.zeros(len(d), bool)
        used[self.cells[active].ravel()] = True
        classification_ms = (time.perf_counter() - start) * 1000
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
        for i, elem in enumerate(self.elements):
            elem.Set(K.ACTIVE, bool(active[i]))
            elem.SetValue(C.EMBEDDED_SCALAR, float(boundary_value))
        linear = K.SkylineLUFactorizationSolver()
        builder = K.ResidualBasedBlockBuilderAndSolver(linear)
        scheme = K.ResidualBasedIncrementalUpdateStaticScheme()
        strategy = K.ResidualBasedLinearStrategy(self.mp, scheme,
                                                builder, False, True, False, False)
        strategy.SetEchoLevel(0)
        strategy.Check()
        model_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        try:
            # Use the strategy's own allocated system, with explicit Kratos assembly
            # and solve calls so timings identify the numerical work independently.
            strategy.Initialize()
            strategy.InitializeSolutionStep()
            strategy.Predict()
            matrix = strategy.GetSystemMatrix()
            increment = strategy.GetSolutionVector()
            rhs = strategy.GetSystemVector()
            builder.Build(scheme, self.mp, matrix, rhs)
            builder.ApplyDirichletConditions(scheme, self.mp, matrix, increment, rhs)
            assembly_ms = (time.perf_counter() - start) * 1000
            start = time.perf_counter()
            builder.SystemSolve(matrix, increment, rhs)
            solve_ms = (time.perf_counter() - start) * 1000
            product = K.Vector(len(rhs))
            K.UblasSparseSpace().Mult(matrix, increment, product)
            residual = np.asarray(product) - np.asarray(rhs)
            relative_residual = float(np.linalg.norm(residual) / max(np.linalg.norm(np.asarray(rhs)), 1e-30))
            if not np.isfinite(relative_residual) or relative_residual > 1e-7:
                raise ArithmeticError('linear residual exceeds 1e-7: ' + str(relative_residual))
            scheme.Update(self.mp, builder.GetDofSet(), matrix, increment, rhs)
            strategy.FinalizeSolutionStep()
            u = np.array([v.GetSolutionStepValue(K.TEMPERATURE) for v in self.nodes], dtype='<f8')
            if not np.isfinite(u).all():
                raise ArithmeticError('Kratos returned nonfinite temperatures; inspect cut quality/penalty/resolution')
        finally:
            strategy.Clear()
        return {'temperature': u, 'distance': np.asarray(d, dtype='<f8'), 'cellState': np.where(cut, 2, active).astype('<i4')}, {
            'backgroundId': self.id, 'buildCount': self.build_count,
            'activeElements': int(active.sum()), 'cutElements': int(cut.sum()),
            'classificationMs': classification_ms, 'modelUpdateMs': model_ms,
            'assemblyAndSolveMs': assembly_ms + solve_ms, 'assemblyMs': assembly_ms, 'solveMs': solve_ms,
            'relativeLinearResidual': relative_residual,
            'linearSolver': 'SkylineLUFactorizationSolver', 'status': 'direct solve completed; relative linear residual <= 1e-7',
            'classificationScope': 'reused' if reused else 'full', 'matrixReused': False,
            'temperatureMin': float(u[d > 0].min()), 'temperatureMax': float(u[d > 0].max()),
        }
