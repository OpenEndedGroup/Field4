import pathlib
import socket
import sys
import threading
import time
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'python'))
import numpy as np
import formulations
from background import BackgroundGrid, classify
from worker import Session, capabilities, receive, send, serve


def domain(bounds=(-1, -1, -1, 1, 1, 1), resolution=12, formulation=formulations.DEFAULT):
    """A grid plus the formulation that owns its ModelPart -- what a session holds."""
    return formulations.create(formulation, BackgroundGrid(list(bounds), resolution))

BOX_A = {'id': 'base', 'op': 'box', 'min': [-.73,-.63,-.52], 'max': [.23,.53,.62]}
BOX_B = {'id': 'arm', 'op': 'box', 'min': [-.13,-.32,-.42], 'max': [.71,.33,.42]}
BODY = {'id': 'body', 'op': 'union', 'a': BOX_A, 'b': BOX_B}


def request(command, **kwargs):
    return dict(schemaVersion=1, units='SI:m,kg,s,K', sessionId='test', geometryRevision=0,
                simulationRevision=0, requestId=1, cancelled=False, command=command, **kwargs)


class PhysicsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.domain = domain()

    def test_sphere_analytic_and_coefficients(self):
        d = .75 - np.linalg.norm(self.domain.positions, axis=1)
        result, _ = self.domain.solve(distances=d)
        exact = (.75**2 - np.sum(self.domain.positions**2, axis=1))/6
        rms = np.sqrt(np.mean((result['temperature'][d>0]-exact[d>0])**2))
        self.assertLess(rms, .001)
        doubled, _ = self.domain.solve(distances=d, conductivity=2, boundary_value=3)
        np.testing.assert_allclose(doubled['temperature'][d>0], 3+result['temperature'][d>0]/2, atol=1e-10)

    def test_edit_and_restore_same_mesh(self):
        identity = self.domain.id
        coordinates = self.domain.positions.copy()
        cells = self.domain.cells.copy()
        model = self.domain.mp
        first, d1 = self.domain.solve(geometry=BODY)
        changed = {**BODY, 'b': {**BOX_B, 'max': [.43,.33,.42]}}
        second, d2 = self.domain.solve(geometry=changed)
        restored, _ = self.domain.solve(geometry=BODY)
        self.assertIs(model, self.domain.mp)
        self.assertEqual(identity, self.domain.id)
        self.assertEqual(self.domain.build_count, 1)
        np.testing.assert_array_equal(coordinates, self.domain.positions)
        np.testing.assert_array_equal(cells, self.domain.cells)
        self.assertEqual(d1["classificationScope"], "full")
        self.assertGreater(np.max(np.abs(first['temperature']-second['temperature'])), .01)
        self.assertGreater(np.count_nonzero((first['distance']>0)!=(second['distance']>0)), 0)
        np.testing.assert_allclose(first['temperature'], restored['temperature'], atol=1e-12)
        coefficients, reuse = self.domain.solve(geometry=BODY, conductivity=2)
        self.assertEqual(reuse['classificationScope'], 'reused')
        np.testing.assert_allclose(coefficients['temperature'], first['temperature']/2, atol=1e-12)
        print('NO_REMESH', identity, d1, d2, flush=True)

    def test_classification_csg_and_invalid_input(self):
        points = np.array([[0,0,0],[2,0,0]])
        self.assertGreater(classify(points, BODY)[0], 0)
        self.assertLess(classify(points, BODY)[1], 0)
        cut = {**BODY, 'op': 'difference'}
        self.assertLess(classify(points, cut)[0], 0)
        for bad in (np.zeros(3), np.full(len(self.domain.nodes), np.nan), np.ones(len(self.domain.nodes))):
            with self.assertRaises(ValueError):
                self.domain.solve(distances=bad)


class ProtocolTest(unittest.TestCase):
    def test_capabilities(self):
        self.assertTrue(capabilities()['available'])
        self.assertEqual(capabilities()['versions']['KratosMultiphysics'], '10.4.0')

    def test_errors_do_not_kill_worker(self):
        a, b = socket.socketpair()
        thread = threading.Thread(target=lambda: serve(b, 'secret'))
        thread.start()
        try:
            send(a, request('bad', token='secret'))
            self.assertFalse(receive(a)['ok'])
            wrong = request('hello'); wrong['schemaVersion'] = 2
            send(a, wrong); self.assertIn('mismatch', receive(a)['error']['message'])
            send(a, request('hello')); self.assertTrue(receive(a)['data']['available'])
            send(a, request('createBackgroundDomain', bounds=[-1,-1,-1,1,1,1], resolution=4))
            self.assertTrue(receive(a)['ok'])
            send(a, request('solve', geometry=BODY, thermal={'conductivity': 0}))
            self.assertIn('positive', receive(a)['error']['message'])
            send(a, request('solve', geometry=BODY))
            self.assertTrue(receive(a)['ok'])
            send(a, request('shutdown')); self.assertTrue(receive(a)['ok'])
        finally:
            a.close(); thread.join(5); b.close()
        self.assertFalse(thread.is_alive())

    def test_revision_and_background_lifetime(self):
        s = Session()
        created = s.dispatch(request('createBackgroundDomain', bounds=[-1,-1,-1,1,1,1], resolution=4))
        self.assertEqual(created['arrays'][0]['shape'], [125,3])
        with self.assertRaisesRegex(ValueError, 'already exists'):
            s.dispatch(request('createBackgroundDomain', bounds=[-1,-1,-1,1,1,1], resolution=4))
        r = request('solve', geometry=BODY);r['geometryRevision']=2
        s.dispatch(r)
        r['geometryRevision']=1
        with self.assertRaisesRegex(ValueError, 'obsolete'):
            s.dispatch(r)
        s.dispatch(request('shutdown'))
        self.assertIsNone(s.domain)


class GeometryTest(unittest.TestCase):
    """The constructive evaluator is formulation-blind, so it is tested without building a model."""

    def test_sphere_and_cylinder_signs(self):
        sphere = {'id': 'ball', 'op': 'sphere', 'centre': [0, 0, 0], 'radius': 1.0}
        d = classify(np.array([[0, 0, 0], [0.5, 0, 0], [2, 0, 0]]), sphere)
        np.testing.assert_allclose(d, [1.0, 0.5, -1.0])
        rod = {'id': 'rod', 'op': 'cylinder', 'a': [0, 0, 0], 'b': [0, 0, 2], 'radius': 0.5}
        d = classify(np.array([[0, 0, 1], [0.4, 0, 1], [0.9, 0, 1], [0, 0, 3]]), rod)
        self.assertGreater(d[0], 0)
        self.assertGreater(d[1], 0)
        self.assertLess(d[2], 0)
        self.assertLess(d[3], 0)
        # A capped cylinder's interior distance is the nearer of the wall and the cap.
        np.testing.assert_allclose(classify(np.array([[0, 0, 0.1]]), rod), [0.1])

    def test_nary_boolean_matches_the_pairwise_spelling(self):
        boxes = [{'id': f'b{i}', 'op': 'box', 'min': [i, -1, -1], 'max': [i + 1.5, 1, 1]} for i in range(3)]
        points = np.array([[0.5, 0, 0], [1.6, 0, 0], [2.6, 0, 0], [9, 0, 0]])
        nary = classify(points, {'id': 'u', 'op': 'union', 'children': boxes})
        pairwise = classify(points, {'id': 'u', 'op': 'union', 'a': boxes[0],
                                     'b': {'id': 'u2', 'op': 'union', 'a': boxes[1], 'b': boxes[2]}})
        np.testing.assert_allclose(nary, pairwise)
        self.assertLess(nary[-1], 0)

    def test_bad_primitives_are_rejected_by_name(self):
        for bad in ({'id': 'x', 'op': 'sphere', 'centre': [0, 0, 0], 'radius': -1},
                    {'id': 'x', 'op': 'cylinder', 'a': [0, 0, 0], 'b': [0, 0, 0], 'radius': 1},
                    {'id': 'x', 'op': 'box', 'min': [1, 1, 1], 'max': [0, 0, 0]},
                    {'id': '', 'op': 'box', 'min': [0, 0, 0], 'max': [1, 1, 1]},
                    {'id': 'x', 'op': 'toroid'}):
            with self.assertRaises(ValueError):
                classify(np.zeros((1, 3)), bad)


class FlowTest(unittest.TestCase):
    """The second formulation, on the same grid and the same protocol as the first."""

    SPHERE = {'id': 'obstacle', 'op': 'sphere', 'centre': [0., 0., 0.], 'radius': .30}

    @classmethod
    def setUpClass(cls):
        cls.grid = BackgroundGrid([-1., -.6, -.6, 2., .6, .6], 6)
        cls.flow = formulations.create('flow.embedded', cls.grid)

    def test_the_obstacle_is_the_negative_region(self):
        """Conduction solves inside the authored solid; flow solves around it. Same expression."""
        inside, *_ = self.grid.classify(self.SPHERE, None, 'inside')
        outside, *_ = self.grid.classify(self.SPHERE, None, 'outside')
        np.testing.assert_allclose(inside, -outside)
        centre = np.argmin(np.linalg.norm(self.grid.positions, axis=1))
        self.assertGreater(inside[centre], 0)
        self.assertLess(outside[centre], 0)

    def test_flow_past_a_sphere_looks_like_flow_past_a_sphere(self):
        arrays, diag = self.flow.solve(geometry=self.SPHERE, velocity=1., density=1.,
                                       viscosity=.05, steps=2, time_step=.08)
        self.assertTrue(diag['allStepsConverged'], diag['status'])
        self.assertEqual(self.grid.id, diag['backgroundId'])
        self.assertEqual(1, diag['buildCount'])
        speed = np.linalg.norm(arrays['velocity'], axis=1)
        fluid = arrays['distance'] > 0
        self.assertTrue(np.isfinite(speed).all() and np.isfinite(arrays['pressure']).all())
        # Potential flow accelerates to 1.5x the free stream at a sphere's equator, and this is a
        # coarse viscous cut version of that: the maximum must exceed the free stream and stay in
        # the same neighbourhood rather than blowing up.
        self.assertGreater(speed[fluid].max(), 1.05)
        self.assertLess(speed[fluid].max(), 2.5)
        # A wake: somewhere in the fluid the flow is measurably slower than the free stream. How
        # much slower is a resolution question and not an assertion — this grid is 6 cells across
        # the whole domain and the sphere spans about two of them, which gives an 11% deficit;
        # resolution 8 in the same configuration gives 82%. The claim being pinned is that the
        # obstacle slows the flow at all, not by how much.
        self.assertLess(speed[fluid].min(), .95)
        # Pressure is high in front and low behind.
        x = self.grid.positions[:, 0]
        front = fluid & (x < -.35) & (np.abs(self.grid.positions[:, 1]) < .25)
        self.assertGreater(arrays['pressure'][front].max(), arrays['pressure'][fluid].mean())

    def test_geometry_edit_reuses_the_background(self):
        identity, model = self.grid.id, self.flow.mp
        positions = self.grid.positions.copy()
        first, _ = self.flow.solve(geometry=self.SPHERE, steps=1, time_step=.08, viscosity=.05)
        bigger = {**self.SPHERE, 'radius': .42}
        second, d2 = self.flow.solve(geometry=bigger, steps=1, time_step=.08, viscosity=.05)
        self.assertIs(model, self.flow.mp)
        self.assertEqual(identity, self.grid.id)
        self.assertEqual(1, self.grid.build_count)
        np.testing.assert_array_equal(positions, self.grid.positions)
        self.assertGreater(np.count_nonzero((first['distance'] > 0) != (second['distance'] > 0)), 0)
        self.assertGreater(np.max(np.abs(first['velocity'] - second['velocity'])), 1e-6)

    def test_invalid_flow_parameters_are_refused(self):
        for bad in ({'viscosity': 0.}, {'density': -1.}, {'time_step': 0.}, {'steps': 0},
                    {'steps': 2.5}, {'penalty': 0.}):
            with self.assertRaises(ValueError):
                self.flow.solve(geometry=self.SPHERE, **bad)


def beam_mesh(nx, ny, nz, length=1.0, breadth=0.1, depth=0.1):
    """A structured tetrahedral beam, so the structural tests need no mesher."""
    from background import SuppliedMesh
    xs = np.linspace(0, length, nx + 1)
    ys = np.linspace(-breadth / 2, breadth / 2, ny + 1)
    zs = np.linspace(-depth / 2, depth / 2, nz + 1)
    pts = np.array([[x, y, z] for x in xs for y in ys for z in zs])
    index = lambda i, j, k: (i * (ny + 1) + j) * (nz + 1) + k
    cells = []
    for i in range(nx):
        for j in range(ny):
            for k in range(nz):
                c = [index(i,j,k), index(i+1,j,k), index(i+1,j+1,k), index(i,j+1,k),
                     index(i,j,k+1), index(i+1,j,k+1), index(i+1,j+1,k+1), index(i,j+1,k+1)]
                for tet in ((0,1,2,6),(0,2,3,6),(0,3,7,6),(0,7,4,6),(0,4,5,6),(0,5,1,6)):
                    ids = [c[t] for t in tet]
                    if np.linalg.det(pts[ids[1:]] - pts[ids[0]]) < 0:
                        ids[1], ids[2] = ids[2], ids[1]
                    cells.append(ids)
    return SuppliedMesh(pts, np.array(cells, dtype='<i4'))


class StructuralTest(unittest.TestCase):
    """Body-fitted linear elasticity, against closed-form beam theory."""

    L, B, D = 1.0, 0.1, 0.1
    E, NU, RHO, G = 2.0e9, 0.30, 1000.0, 9.81
    CLAMP = [-1e-9, -1, -1, 1e-9, 1, 1]

    def test_axial_extension_is_exact(self):
        """A constant-strain tetrahedron represents constant strain with no error, so this pins the
        material, the clamp, the point loads and the solver — everything except bending."""
        force = 1.0e4
        exact = force * self.L / (self.E * self.B * self.D)
        mesh = beam_mesh(10, 2, 2)
        f = formulations.create('structural.bodyFitted', mesh)
        arrays, diag = f.solve(young_modulus=self.E, poisson_ratio=self.NU, density=self.RHO,
                               gravity=(0., 0., 0.), clamp=self.CLAMP,
                               load_region=[self.L - 1e-9, -1, -1, self.L + 1e-9, 1, 1],
                               load=(force, 0., 0.))
        tip = mesh.positions[:, 0] > self.L - 1e-9
        coarse = arrays['displacement'][tip, 0].mean() / exact
        self.assertLess(diag['relativeLinearResidual'], 1e-7)
        self.assertEqual(len(arrays['vonMises']), mesh.cell_count)

        # Not exact to machine precision, and the reason is the load rather than the element: the
        # total force is split equally between the end-face nodes regardless of their tributary
        # area, so the stress near the end is not quite uniform. That is a Saint-Venant end effect
        # and it shrinks with refinement, where a genuine element error in constant strain would
        # not.
        finer = beam_mesh(20, 3, 3)
        g = formulations.create('structural.bodyFitted', finer)
        refined, _ = g.solve(young_modulus=self.E, poisson_ratio=self.NU, density=self.RHO,
                             gravity=(0., 0., 0.), clamp=self.CLAMP,
                             load_region=[self.L - 1e-9, -1, -1, self.L + 1e-9, 1, 1],
                             load=(force, 0., 0.))
        fine_tip = finer.positions[:, 0] > self.L - 1e-9
        fine = refined['displacement'][fine_tip, 0].mean() / exact
        self.assertLess(abs(coarse - 1.0), 0.01, 'coarse axial within 1%%: %f' % coarse)
        self.assertLess(abs(fine - 1.0), 0.002, 'refined axial within 0.2%%: %f' % fine)

    def test_bending_converges_upward_and_locks(self):
        """The characterisation, not an accuracy claim: linear tets lock in bending, so the
        deflection approaches Euler-Bernoulli from below and slowly. If a future element fixes
        this the ordering assertions still hold and the ceiling should be raised."""
        inertia = self.B * self.D ** 3 / 12.0
        weight = self.RHO * self.G * self.B * self.D
        analytic = weight * self.L ** 4 / (8 * self.E * inertia)
        fractions = []
        for res in ((10, 2, 2), (20, 3, 3)):
            mesh = beam_mesh(*res)
            f = formulations.create('structural.bodyFitted', mesh)
            arrays, _ = f.solve(young_modulus=self.E, poisson_ratio=self.NU, density=self.RHO,
                                gravity=(0., 0., -self.G), clamp=self.CLAMP)
            tip = mesh.positions[:, 0] > self.L - 1e-9
            fractions.append(abs(arrays['displacement'][tip, 2]).mean() / analytic)
        self.assertLess(fractions[0], fractions[1], 'refining must reduce the locking')
        self.assertLess(fractions[1], 1.05, 'a stiff element cannot exceed the theory')
        self.assertGreater(fractions[1], 0.5)

    def test_a_body_fitted_solve_refuses_a_level_set(self):
        mesh = beam_mesh(4, 1, 1)
        f = formulations.create('structural.bodyFitted', mesh)
        with self.assertRaisesRegex(ValueError, 'no level set'):
            mesh.classify(BODY)
        with self.assertRaisesRegex(ValueError, 'takes no level set'):
            f.solve(geometry=BODY, clamp=self.CLAMP)

    def test_invalid_structural_parameters_are_refused(self):
        mesh = beam_mesh(4, 1, 1)
        f = formulations.create('structural.bodyFitted', mesh)
        for bad in ({'young_modulus': 0.}, {'density': -1.}, {'poisson_ratio': 0.5},
                    {'poisson_ratio': -2.}, {'gravity': (0., 0., float('nan'))}):
            with self.assertRaises(ValueError):
                f.solve(clamp=self.CLAMP, **bad)
        # A clamp that catches nothing leaves the body free, which is a singular system.
        with self.assertRaisesRegex(ValueError, 'unrestrained'):
            f.solve(clamp=[10., 10., 10., 11., 11., 11.])


class RegistryTest(unittest.TestCase):
    """Formulation is a plugin: the worker reports what it has and refuses what it does not."""

    def test_available_separates_installed_from_registered(self):
        catalogue = formulations.available()
        self.assertIn(formulations.DEFAULT, catalogue)
        thermal = catalogue[formulations.DEFAULT]
        self.assertTrue(thermal['available'], thermal['missing'])
        self.assertEqual({}, thermal['missing'])
        self.assertEqual('EmbeddedLaplacianElement3D4N', thermal['element'])
        for name, detail in catalogue.items():
            # A formulation is listed whether or not it can be built; a missing application is a
            # reason, not an absence.
            self.assertEqual(detail['available'], not detail['missing'], name)

    def test_capabilities_reports_usable_formulations(self):
        report = capabilities()
        self.assertIn(formulations.DEFAULT, report['usableFormulations'])
        self.assertEqual(formulations.DEFAULT, report['defaultFormulation'])
        self.assertTrue(report['available'])

    def test_unknown_formulation_names_the_alternatives(self):
        s = Session()
        with self.assertRaisesRegex(ValueError, 'thermal.stationary'):
            s.dispatch(request('createBackgroundDomain', bounds=[-1,-1,-1,1,1,1],
                               resolution=4, formulation='no.such.physics'))

    def test_solve_accepts_parameters_and_the_schema_one_thermal_spelling(self):
        s = Session()
        s.dispatch(request('createBackgroundDomain', bounds=[-1,-1,-1,1,1,1], resolution=6))
        a = s.dispatch(request('solve', geometry=BODY, parameters={'conductivity': 2.0}))
        b = s.dispatch(request('solve', geometry=BODY, thermal={'conductivity': 2.0}))
        self.assertEqual(a['diagnostics']['formulation'], formulations.DEFAULT)
        self.assertEqual([f['name'] for f in a['diagnostics']['fields']],
                         ['temperature', 'distance', 'cellState'])
        # Equal to rounding, not bit-identical: Kratos assembles with OpenMP atomics, so the
        # summation order varies between runs and two solves of the same model can differ in the
        # last ulp. Asserting equal bytes here failed exactly that way.
        def temperature(payload):
            d = next(x for x in payload['arrays'] if x['name'] == 'temperature')
            return np.frombuffer(payload['binary'], dtype='<f8',
                                 count=d['length'] // 8, offset=d['offset'])
        np.testing.assert_allclose(temperature(a), temperature(b), rtol=1e-12, atol=1e-15)


class TransportTest(unittest.TestCase):
    """`--listen` is the direction a container worker has to use; `--port` cannot reach the host."""

    @staticmethod
    def _serve_until_raise(sock, token):
        try:
            serve(sock, token)
            return None
        except Exception as exc:
            return str(exc)

    def test_listen_accepts_one_client_and_checks_the_token(self):
        import worker
        held = {}
        # Take a free port and release it, the same way Field picks the published port. The gap is
        # the reason the Kotlin side waits for the worker's readiness line rather than for connect.
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        thread = threading.Thread(target=lambda: held.update(sock=worker.listen(port, timeout=10)))
        thread.start()
        try:
            deadline = time.time() + 10
            while True:
                try:
                    client = socket.create_connection(('127.0.0.1', port), timeout=5)
                    break
                except OSError:
                    if time.time() > deadline:
                        raise
                    time.sleep(0.05)
            thread.join(10)
            self.assertIn('sock', held)
            # Rejecting the token raises out of serve(); catch it here so the expected failure is
            # an assertion rather than a traceback printed over the suite's output.
            refused = []
            served = threading.Thread(
                target=lambda: refused.append(self._serve_until_raise(held['sock'], 'right-token')))
            served.start()
            try:
                # The first frame is the only place the token is checked, and a wrong one is fatal
                # to the connection rather than a recoverable structured error.
                send(client, request('hello', token='wrong-token'))
                with self.assertRaises(Exception):
                    receive(client)
            finally:
                client.close(); served.join(5); held['sock'].close()
            self.assertFalse(served.is_alive())
            self.assertEqual(['invalid session token'], refused)
        finally:
            thread.join(1)

    def test_listen_rejects_a_port_already_taken(self):
        import worker
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as taken:
            taken.bind(('127.0.0.1', 0))
            taken.listen(1)
            port = taken.getsockname()[1]
            with self.assertRaises(OSError):
                worker.listen(port, timeout=1)


if __name__ == '__main__':
    unittest.main()
