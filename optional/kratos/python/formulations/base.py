"""What every formulation has to supply, and the Kratos ModelPart construction they share.

A formulation is the physics half of the bridge: which Kratos applications it needs, which element
it puts on the background tetrahedra, which nodal variables exist, and what one solve does. The
geometry half is `background.BackgroundGrid` and is the same for all of them.

Adding one means a subclass and a line in `formulations/__init__.py`. It does not mean touching
the worker, the protocol envelope, the binary array encoding, the revision gate, or the Kotlin
transport -- all of which are formulation-blind by construction.
"""
import time

import numpy as np


class FormulationError(ValueError):
    """A model the caller asked for that this formulation cannot build. Structured, not fatal."""


class Formulation:
    """One physics on one [background.BackgroundGrid].

    Subclasses set the class attributes and implement [solve]. [build] is shared: it creates one
    Kratos node per grid node and one element per grid tetrahedron, in grid order, so that node
    index `i` in every array on the wire is `self.nodes[i]` here and needs no lookup table.
    """

    #: Wire name, e.g. `thermal.stationary`. Stable; it appears in `createBackgroundDomain`.
    name = None
    #: Modules that must import for this formulation to be offered.
    applications = ()
    #: Registered Kratos element for a 3D linear tetrahedron.
    element = None
    #: One-line description, reported by `hello`.
    summary = ''
    #: Largest resolution this profile's linear solver is honest about.
    max_resolution = 32
    #: `inside` solves within the authored solid (conduction); `outside` treats it as an obstacle
    #: in the solved medium (flow). See `background.BackgroundGrid.classify`.
    solve_region = 'inside'
    #: Solution-step history depth. 1 for a stationary solve; BDF2 needs 3.
    buffer_size = 1
    #: True when this formulation is built from a mesh Field sends rather than from a grid spec.
    supplied_mesh = False

    def __init__(self, grid, options=None):
        self.grid = grid
        self.options = dict(options or {})
        self.build_ms = 0.0
        self.nodes = []
        self.elements = []
        self.build()

    # ------------------------------------------------------------------------------------------
    # Construction
    # ------------------------------------------------------------------------------------------

    def variables(self):
        """Nodal solution-step variables to register before any node exists. Kratos requires that
        order: a variable added after the nodes is not on them."""
        raise NotImplementedError

    def dofs(self):
        """Nodal degrees of freedom to add to every node.

        Either a variable, or a `(variable, reaction)` pair when the formulation wants reactions
        computed — a monolithic fluid does, a Laplacian does not.
        """
        raise NotImplementedError

    def configure(self, model_part):
        """Anything the ModelPart needs before elements are created — ProcessInfo settings, a
        `ConvectionDiffusionSettings`, a constitutive law on the Properties."""

    def finish(self, model_part):
        """Anything the ModelPart needs *after* its nodes and elements exist — conditions, mostly."""

    def build(self):
        import KratosMultiphysics as K
        self.K = K
        K.Logger.GetDefaultOutput().SetSeverity(K.Logger.Severity.WARNING)
        start = time.perf_counter()
        self.model = K.Model()
        self.mp = model_part = self.model.CreateModelPart(self.name.replace('.', '_'))
        model_part.ProcessInfo[K.DOMAIN_SIZE] = 3
        for variable in self.variables():
            model_part.AddNodalSolutionStepVariable(variable)
        # Buffer depth has to be set before the nodes exist, like the variables.
        model_part.SetBufferSize(self.buffer_size)
        self.configure(model_part)
        dofs = list(self.dofs())
        for i, p in enumerate(self.grid.positions, 1):
            node = model_part.CreateNewNode(i, *p)
            for dof in dofs:
                if isinstance(dof, tuple):
                    node.AddDof(*dof)
                else:
                    node.AddDof(dof)
        properties = model_part.CreateNewProperties(1)
        self.properties = properties
        for i, cell in enumerate(self.grid.cells, 1):
            # Grid connectivity is zero-based on the wire; Kratos ids are one-based.
            model_part.CreateNewElement(self.element, i, [int(c) + 1 for c in cell], properties)
        self.nodes, self.elements = list(model_part.Nodes), list(model_part.Elements)
        self.finish(model_part)
        self.build_ms = (time.perf_counter() - start) * 1000

    # Read-through to the grid, so a caller holding a formulation does not have to hold both.
    @property
    def positions(self):
        return self.grid.positions

    @property
    def cells(self):
        return self.grid.cells

    @property
    def id(self):
        return self.grid.id

    @property
    def build_count(self):
        return self.grid.build_count

    # ------------------------------------------------------------------------------------------
    # Solving
    # ------------------------------------------------------------------------------------------

    def fields(self):
        """Describe the arrays [solve] returns, so a client can render them without knowing the
        formulation. `kind` is `nodal` or `cell`; `components` is 1 for a scalar."""
        raise NotImplementedError

    def solve(self, geometry=None, distances=None, **parameters):
        """One atomic snapshot: classify, update the model, assemble, solve, pack.

        Returns `(arrays, diagnostics)`. `arrays` maps the names in [fields] to contiguous
        little-endian numpy arrays; `diagnostics` is free-form and is merged into the reply.
        """
        raise NotImplementedError

    # ------------------------------------------------------------------------------------------
    # Shared machinery
    # ------------------------------------------------------------------------------------------

    def classify(self, geometry, distances):
        """Time [background.BackgroundGrid.classify] and keep its masks."""
        start = time.perf_counter()
        d, active, cut, used, scope = self.grid.classify(geometry, distances, self.solve_region)
        return d, active, cut, used, scope, (time.perf_counter() - start) * 1000

    def direct_solve(self, strategy, builder, scheme, tolerance=1e-7):
        """Assemble and solve with the strategy's own system, timing the two separately.

        Kratos will happily run `strategy.Solve()`, but then assembly and the linear solve are one
        number and there is no way to tell a classification cost from a factorization cost. The
        residual check is on the algebra only: it says the linear system was solved, and nothing
        about whether the discretisation approximates the continuum problem.
        """
        K = self.K
        start = time.perf_counter()
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
        relative = float(np.linalg.norm(residual) / max(np.linalg.norm(np.asarray(rhs)), 1e-30))
        if not np.isfinite(relative) or relative > tolerance:
            raise ArithmeticError(f'linear residual exceeds {tolerance}: {relative}')
        scheme.Update(self.mp, builder.GetDofSet(), matrix, increment, rhs)
        strategy.FinalizeSolutionStep()
        return assembly_ms, solve_ms, relative

    def nodal(self, variable):
        """One float64 per background node, in grid order."""
        return np.array([n.GetSolutionStepValue(variable) for n in self.nodes], dtype='<f8')

    def nodal_vector(self, variable):
        """One xyz triple per background node, in grid order."""
        return np.array([list(n.GetSolutionStepValue(variable))[:3] for n in self.nodes], dtype='<f8')
