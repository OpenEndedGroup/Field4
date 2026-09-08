"""The background domain: a structured tetrahedral grid, and what geometry does to it.

Nothing here imports Kratos. This is the half of the bridge that answers "which nodes are inside
the solid, and which elements are cut" -- the part every formulation needs and none of them needs
differently. `formulations/` holds the half that knows about elements and solvers.

The split matters because the no-remesh contract lives on this side. A [BackgroundGrid] is built
once per session; a geometry edit calls [BackgroundGrid.classify] again and changes nothing about
the node positions or the connectivity.
"""
import copy
import itertools
import time
import uuid

import numpy as np


# ----------------------------------------------------------------------------------------------
# Constructive geometry, as signed distance
# ----------------------------------------------------------------------------------------------

def classify(points, geometry, depth=0):
    """Signed distance of every row of `points`, **positive inside** the solid.

    Positive-inside is Kratos's embedded convention, not Field's: `afield`'s SDF family and the
    `mesh_measure` BVH are both negative-inside, and the Kotlin bridge negates on the way in.

    Booleans compose by min/max, which preserves the sign and the zero surface but is not the
    exact Euclidean distance to a Boolean result. Cut integration only reads the sign and the
    linear interpolation across an edge, so that is sufficient here and would not be for, say,
    a narrow-band redistancing scheme.
    """
    if depth > 32:
        raise ValueError('geometry expression exceeds 32 levels')
    if not isinstance(geometry.get('id'), str) or not geometry['id']:
        raise ValueError('geometry needs a stable nonempty id')
    op = geometry['op']
    handler = PRIMITIVES.get(op)
    if handler is not None:
        return handler(points, geometry)
    if op not in ('union', 'difference', 'intersection'):
        raise ValueError('unsupported geometry operation: ' + str(op))
    children = geometry.get('children')
    if children is None:
        children = [geometry['a'], geometry['b']]
    if len(children) < 2:
        raise ValueError(op + ' needs at least two operands')
    values = [classify(points, child, depth + 1) for child in children]
    if op == 'union':
        return np.maximum.reduce(values)
    if op == 'intersection':
        return np.minimum.reduce(values)
    # difference: the first operand minus every other one.
    return np.minimum.reduce([values[0]] + [-v for v in values[1:]])


def _vector(geometry, key, size=3):
    value = np.asarray(geometry[key], dtype=float)
    if value.shape != (size,) or not np.isfinite(value).all():
        raise ValueError(f'{key} must be {size} finite numbers')
    return value


def _box(points, geometry):
    lo, hi = _vector(geometry, 'min'), _vector(geometry, 'max')
    if not (hi > lo).all():
        raise ValueError('box bounds must be ordered')
    q = np.abs(points - (lo + hi) / 2) - (hi - lo) / 2
    return -(np.linalg.norm(np.maximum(q, 0), axis=1) + np.minimum(np.max(q, axis=1), 0))


def _sphere(points, geometry):
    centre = _vector(geometry, 'centre') if 'centre' in geometry else _vector(geometry, 'center')
    radius = float(geometry['radius'])
    if not np.isfinite(radius) or radius <= 0:
        raise ValueError('sphere radius must be finite and positive')
    return radius - np.linalg.norm(points - centre, axis=1)


def _cylinder(points, geometry):
    """A finite capped cylinder between two points. Exact outside, conservative inside."""
    a, b = _vector(geometry, 'a'), _vector(geometry, 'b')
    radius = float(geometry['radius'])
    if not np.isfinite(radius) or radius <= 0:
        raise ValueError('cylinder radius must be finite and positive')
    axis = b - a
    length = np.linalg.norm(axis)
    if length <= 0:
        raise ValueError('cylinder endpoints must differ')
    unit = axis / length
    relative = points - a
    along = relative @ unit
    radial = np.linalg.norm(relative - np.outer(along, unit), axis=1)
    # Distance to the slab and to the tube, combined the way an SDF box is: outside contributions
    # add in quadrature, inside takes the nearest wall.
    dr = radial - radius
    dz = np.abs(along - length / 2) - length / 2
    outside = np.hypot(np.maximum(dr, 0), np.maximum(dz, 0))
    inside = np.minimum(np.maximum(dr, dz), 0)
    return -(outside + inside)


PRIMITIVES = {'box': _box, 'sphere': _sphere, 'cylinder': _cylinder}


# ----------------------------------------------------------------------------------------------
# The grid
# ----------------------------------------------------------------------------------------------

class SuppliedMesh:
    """A mesh Field built and sent, rather than one generated here.

    Body-fitted formulations get their discretisation from TetGen on Field's side, because the
    input that preserves an edge is Manifold's triangulation and that lives in the JVM. This
    exposes the same surface a `BackgroundGrid` does — positions, cells, an id — so
    `Formulation.build` cannot tell the difference.

    It has no `classify`: there is no level set, and a formulation that asks for one is confused
    about which half of the bridge it is in.
    """

    def __init__(self, positions, cells):
        self.positions = np.ascontiguousarray(positions, dtype='<f8')
        self.cells = np.ascontiguousarray(cells, dtype='<i4')
        if self.positions.ndim != 2 or self.positions.shape[1] != 3:
            raise ValueError('positions must be an (n, 3) float64 array')
        if self.cells.ndim != 2 or self.cells.shape[1] != 4:
            raise ValueError('cells must be an (m, 4) int32 array of tetrahedra')
        if len(self.positions) < 4 or len(self.cells) < 1:
            raise ValueError('a tetrahedral mesh needs at least 4 nodes and 1 element')
        if not np.isfinite(self.positions).all():
            raise ValueError('positions must all be finite')
        if self.cells.min() < 0 or self.cells.max() >= len(self.positions):
            raise ValueError('a cell references a node outside the mesh')
        self.id = str(uuid.uuid4())
        self.build_count = 1
        self.build_ms = 0.0
        lo, hi = self.positions.min(axis=0), self.positions.max(axis=0)
        self.bounds = [*lo.tolist(), *hi.tolist()]

    @property
    def node_count(self):
        return len(self.positions)

    @property
    def cell_count(self):
        return len(self.cells)

    def classify(self, geometry=None, distances=None, solve_region='inside'):
        raise ValueError('a supplied body-fitted mesh has no level set to classify')


class BackgroundGrid:
    """Node positions, tetrahedral connectivity, and the classification of geometry against them.

    Built once. `resolution` is cells per axis, so a resolution of n has (n+1)**3 nodes and
    6*n**3 tetrahedra: each cube is split by the six permutations of the x/y/z traversal order
    (Freudenthal), which conforms across cell faces without any bookkeeping.
    """

    def __init__(self, bounds, resolution, max_resolution=32):
        start = time.perf_counter()
        lo, hi = np.asarray(bounds[:3], float), np.asarray(bounds[3:], float)
        if lo.shape != (3,) or hi.shape != (3,) or not np.isfinite([lo, hi]).all() or not (hi > lo).all():
            raise ValueError('domain bounds must be six finite ordered values')
        if type(resolution) is not int or not 4 <= resolution <= max_resolution:
            raise ValueError(
                f'this solver profile supports resolution 4..{max_resolution}; '
                'larger grids need a sparse solver profile')
        n = self.resolution = resolution
        self.bounds = list(bounds)
        self.id = str(uuid.uuid4())
        self.build_count = 1
        self.previous_geometry = None
        self.previous_distances = None
        self.positions = np.array(
            list(itertools.product(*(np.linspace(lo[i], hi[i], n + 1) for i in range(3)))), dtype='<f8')

        def index(p):
            return (p[0] * (n + 1) + p[1]) * (n + 1) + p[2]

        cells = []
        for origin in itertools.product(range(n), repeat=3):
            for permutation in itertools.permutations(range(3)):
                p, ids = list(origin), [index(origin)]
                for axis in permutation:
                    p[axis] += 1
                    ids.append(index(p))
                if np.linalg.det(self.positions[ids[1:]] - self.positions[ids[0]]) < 0:
                    ids[1], ids[2] = ids[2], ids[1]
                cells.append(ids)
        self.cells = np.asarray(cells, dtype='<i4')
        self.boundary = np.any((self.positions == lo) | (self.positions == hi), axis=1)
        self.build_ms = (time.perf_counter() - start) * 1000

    @property
    def node_count(self):
        return len(self.positions)

    @property
    def cell_count(self):
        return len(self.cells)

    def classify(self, geometry=None, distances=None, solve_region='inside'):
        """Nodal distance and the active/cut masks a cut formulation needs.

        Returns `(distance, active, cut, used, scope)`: `active` is per cell and true where any
        node is in the solved region, `cut` is per cell and true where the interface passes
        through, `used` is per node and true where the node belongs to at least one active cell,
        and `scope` is `reused` when the geometry expression was identical to the last one and the
        distances did not have to be recomputed.

        `solve_region` is where the physics lives relative to the authored solid, and the two cases
        are genuinely opposite. Conduction is solved **inside** the part. Flow is solved **outside**
        it: the part is an obstacle in the fluid. Kratos wants positive distance on the solved side
        in both cases, so `outside` negates the field after validating it — the validation itself
        does not change, because "the solid is strictly inside the domain and not empty" is the
        requirement either way.
        """
        if solve_region not in ('inside', 'outside'):
            raise ValueError("solve_region must be 'inside' or 'outside'")
        if (distances is None) == (geometry is None):
            raise ValueError('provide exactly one geometry expression or distance array')
        reused = distances is None and geometry == self.previous_geometry and self.previous_distances is not None
        if reused:
            d = self.previous_distances.copy()
        elif distances is None:
            d = classify(self.positions, geometry)
        else:
            d = np.asarray(distances, dtype='<f8').copy()
        if d.shape != (self.node_count,) or not np.isfinite(d).all():
            raise ValueError('distance must contain one finite float64 per background node')
        if np.any(d[self.boundary] >= 0):
            raise ValueError('embedded body must be strictly inside the background domain; expand domain bounds')
        if not np.any(d > 0):
            raise ValueError('no positive nodes: geometry is empty or under-resolved; increase resolution')
        self.previous_geometry = copy.deepcopy(geometry) if distances is None else None
        self.previous_distances = d.copy() if distances is None else None
        if solve_region == 'outside':
            d = -d
        # Avoid exact level-set zeros, matching upstream embedded solver semantics.
        eps = 1e-12 * max(np.subtract(self.bounds[3:], self.bounds[:3]))
        d = np.where(np.abs(d) < eps, np.where(d > 0, eps, -eps), d)
        active = np.any(d[self.cells] > 0, axis=1)
        cut = active & np.any(d[self.cells] < 0, axis=1)
        used = np.zeros(len(d), bool)
        used[self.cells[active].ravel()] = True
        return d, active, cut, used, ('reused' if reused else 'full')

    def cell_state(self, active, cut):
        """0 inactive, 1 positive, 2 cut — the wire encoding."""
        return np.where(cut, 2, active).astype('<i4')
