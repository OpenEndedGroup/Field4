import pathlib
import socket
import sys
import threading
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'python'))
import numpy as np
from thermal import ThermalDomain, classify
from worker import Session, capabilities, receive, send, serve

BOX_A = {'id': 'base', 'op': 'box', 'min': [-.73,-.63,-.52], 'max': [.23,.53,.62]}
BOX_B = {'id': 'arm', 'op': 'box', 'min': [-.13,-.32,-.42], 'max': [.71,.33,.42]}
BODY = {'id': 'body', 'op': 'union', 'a': BOX_A, 'b': BOX_B}


def request(command, **kwargs):
    return dict(schemaVersion=1, units='SI:m,kg,s,K', sessionId='test', geometryRevision=0,
                simulationRevision=0, requestId=1, cancelled=False, command=command, **kwargs)


class PhysicsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.domain = ThermalDomain([-1,-1,-1,1,1,1], 12)

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


if __name__ == '__main__':
    unittest.main()
