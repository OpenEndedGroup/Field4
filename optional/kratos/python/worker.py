"""One Field session per persistent worker; framed MessagePack over loopback TCP.

Large numeric fields are little-endian contiguous binary blocks, never JSON or
boxed MessagePack arrays. No mapped files or shared-storage lifetime is needed.
"""
import argparse
import importlib
import importlib.metadata
import os
import platform
import runpy
import socket
import struct
import sys
import time
import traceback

import msgpack

import formulations

SCHEMA = 1
UNITS = 'SI:m,kg,s,K'
MAX_FRAME = 64 * 1024 * 1024
ENVELOPE = ('schemaVersion', 'units', 'sessionId', 'geometryRevision', 'simulationRevision', 'requestId', 'cancelled')


def capabilities():
    """What this worker can do, formulation by formulation.

    `available` is true when at least one formulation can be built, not when every registered
    application imports: a worker with ConvectionDiffusion but no FluidDynamics is useful, and
    reporting it as unavailable would be wrong. `formulations` carries the per-physics detail,
    including the import error for each one that is missing.
    """
    catalogue = formulations.available()
    applications, errors = [], {}
    for name in ('KratosMultiphysics', 'numpy'):
        try:
            importlib.import_module(name)
            applications.append(name)
        except Exception as exc:
            errors[name] = str(exc)
    for name, detail in catalogue.items():
        if detail['available']:
            applications.extend(m for m in detail['applications'] if m not in applications)
        else:
            errors.update(detail['missing'])
    versions = {}
    for name in ('KratosMultiphysics', 'KratosConvectionDiffusionApplication',
                 'KratosFluidDynamicsApplication', 'KratosStructuralMechanicsApplication',
                 'KratosLinearSolversApplication', 'numpy', 'msgpack'):
        try:
            versions[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            versions[name] = None
    usable = [name for name, detail in catalogue.items() if detail['available']]
    return {'available': bool(usable) and 'numpy' in applications,
            'python': sys.version, 'executable': sys.executable,
            'platform': platform.platform(), 'applications': applications, 'versions': versions,
            'errors': errors,
            'formulations': catalogue, 'usableFormulations': usable,
            'defaultFormulation': formulations.DEFAULT,
            'formulation': formulations.REGISTRY[formulations.DEFAULT].element,
            'installation': 'uv sync --project optional/kratos; set AFIELD_KRATOS_PYTHON to that .venv/bin/python'}


def read_exact(sock, n):
    data = bytearray()
    while len(data) < n:
        part = sock.recv(n-len(data))
        if not part:
            raise EOFError('Field socket closed')
        data.extend(part)
    return data


def receive(sock):
    n, = struct.unpack('!I', read_exact(sock, 4))
    if not 0 < n <= MAX_FRAME:
        raise ValueError('frame length exceeds protocol limit')
    return msgpack.unpackb(read_exact(sock, n), raw=False, strict_map_key=True)


def send(sock, value):
    data = msgpack.packb(value, use_bin_type=True)
    if len(data) > MAX_FRAME:
        raise ValueError('response exceeds protocol frame limit')
    sock.sendall(struct.pack('!I', len(data)) + data)


def arrays_payload(arrays):
    descriptors, blocks, offset = [], [], 0
    for name, array in arrays.items():
        block = array.tobytes(order='C')
        dtype = {'d': 'float64', 'i': 'int32'}[array.dtype.char]
        descriptors.append({'name': name, 'dtype': dtype, 'shape': list(array.shape),
                            'byteOrder': 'little', 'storage': 'inline', 'offset': offset, 'length': len(block)})
        blocks.append(block)
        offset += len(block)
    return {'arrays': descriptors, 'binary': b''.join(blocks)}


class Session:
    def __init__(self, setup=None):
        self.setup = setup or {}
        self.session_id = None
        self.domain = None
        self.grid = None
        self.formulation = None
        self.revision = (-1, -1)

    def dispatch(self, request):
        for key in ENVELOPE:
            if key not in request:
                raise ValueError('missing envelope field: ' + key)
        if type(request['cancelled']) is not bool:
            raise ValueError('cancelled must be boolean')
        if type(request['schemaVersion']) is not int or request['schemaVersion'] != SCHEMA or request['units'] != UNITS:
            raise ValueError('protocol/units mismatch: requires schema 1 and ' + UNITS)
        if any(type(request[k]) is not int or request[k] < 0 for k in ('geometryRevision', 'simulationRevision', 'requestId')):
            raise ValueError('revisions and requestId must be nonnegative integers')
        if not isinstance(request['sessionId'], str) or not request['sessionId']:
            raise ValueError('sessionId must be a nonempty string')
        if self.session_id is None:
            self.session_id = request['sessionId']
        if request['sessionId'] != self.session_id:
            raise ValueError('worker belongs to another session')
        command = request['command']
        if command == 'hello':
            return capabilities()
        if command in ('shutdown', 'closeSession'):
            self.domain = self.grid = self.formulation = None
            return {'closed': True}
        if command == 'cancel' or request['cancelled']:
            return {'cancelled': True, 'policy': 'serialized worker; Field suppresses in-flight obsolete results'}
        rev = (request['geometryRevision'], request['simulationRevision'])
        if any(a < b for a, b in zip(rev, self.revision)):
            raise ValueError('obsolete revision')
        if command == 'createBackgroundDomain':
            if self.domain is not None:
                raise ValueError('background already exists; create a new session to change domain bounds/resolution')
            from background import BackgroundGrid
            name = request.get('formulation') or formulations.DEFAULT
            chosen = formulations.REGISTRY.get(name)
            if chosen is None:
                raise formulations.FormulationError(
                    f'unknown formulation {name!r}; this worker offers ' + ', '.join(sorted(formulations.REGISTRY)))
            grid = BackgroundGrid(request['bounds'], request['resolution'],
                                  max_resolution=chosen.max_resolution)
            self.formulation = formulations.create(name, grid, request.get('formulationOptions'))
            self.grid = grid
            # `domain` stays the name the rest of this file and the setup hook use, so an existing
            # before_solve hook keeps working: it is now the formulation, which owns the ModelPart.
            self.domain = self.formulation
            self.revision = rev
            return {**arrays_payload({'positions': grid.positions, 'cells': grid.cells}),
                    'backgroundId': grid.id, 'buildCount': grid.build_count,
                    'domainBuildMs': grid.build_ms + self.formulation.build_ms,
                    'formulation': name, 'element': self.formulation.element,
                    'fields': self.formulation.fields()}
        if command == 'createBodyMesh':
            # The body-fitted counterpart of createBackgroundDomain: Field meshed the Manifold
            # surface with TetGen and sends the result, because the input that preserves a sharp
            # edge is that triangulation and it lives in the JVM.
            if self.domain is not None:
                raise ValueError('a mesh already exists; create a new session to replace it')
            import numpy as np
            from background import SuppliedMesh
            name = request.get('formulation')
            chosen = formulations.REGISTRY.get(name)
            if chosen is None:
                raise formulations.FormulationError(
                    f'unknown formulation {name!r}; this worker offers ' + ', '.join(sorted(formulations.REGISTRY)))
            if not chosen.supplied_mesh:
                raise formulations.FormulationError(
                    f'{name} builds its own background grid; use createBackgroundDomain')
            positions, cells = request.get('positions'), request.get('cells')
            if not isinstance(positions, bytes) or len(positions) % 24 != 0:
                raise ValueError('positions must be a float64[nodeCount, 3] binary block')
            if not isinstance(cells, bytes) or len(cells) % 16 != 0:
                raise ValueError('cells must be an int32[cellCount, 4] binary block')
            mesh = SuppliedMesh(np.frombuffer(positions, dtype='<f8').reshape(-1, 3),
                                np.frombuffer(cells, dtype='<i4').reshape(-1, 4))
            self.formulation = formulations.create(name, mesh, request.get('formulationOptions'))
            self.grid = mesh
            self.domain = self.formulation
            self.revision = rev
            return {'backgroundId': mesh.id, 'buildCount': mesh.build_count,
                    'domainBuildMs': self.formulation.build_ms,
                    'nodeCount': mesh.node_count, 'cellCount': mesh.cell_count,
                    'bounds': mesh.bounds,
                    'formulation': name, 'element': self.formulation.element,
                    'fields': self.formulation.fields()}
        if command == 'solve':
            if self.domain is None:
                raise ValueError('createBackgroundDomain first')
            import numpy as np
            distance = request.get('distance')
            if distance is not None:
                if not isinstance(distance, bytes) or len(distance) != self.grid.node_count * 8:
                    raise ValueError('distance binary must be float64[nodeCount], little endian')
                distance = np.frombuffer(distance, dtype='<f8')
            # A body-fitted formulation solves the mesh it was given, so it takes neither.
            if not self.formulation.supplied_mesh:
                if (distance is None) == (request.get('geometry') is None):
                    raise ValueError('provide exactly one geometry expression or distance binary')
            self.revision = rev
            if 'before_solve' in self.setup:
                self.setup['before_solve'](self.domain, request)
            # `parameters` is the formulation-blind spelling; `thermal` is what schema 1 shipped
            # with and still means the thermal formulation's parameters.
            parameters = request.get('parameters')
            if parameters is None:
                parameters = request.get('thermal', {})
            arrays, diagnostic = self.formulation.solve(
                geometry=request.get('geometry'), distances=distance, **parameters)
            diagnostic['formulation'] = self.formulation.name
            diagnostic['fields'] = self.formulation.fields()
            start = time.perf_counter()
            payload = arrays_payload(arrays)
            diagnostic['resultPackingMs'] = (time.perf_counter()-start)*1000
            diagnostic['resultBytes'] = len(payload['binary'])
            diagnostic['regions'] = request.get('regions', {})
            return {**payload, 'diagnostics': diagnostic}
        raise ValueError('unknown command: ' + str(command))


def serve(sock, token):
    setup_path = os.environ.get("FIELD_KRATOS_SETUP")
    session = Session(runpy.run_path(setup_path) if setup_path else None)
    authenticated = False
    while True:
        try:
            request = receive(sock)
        except EOFError:
            return
        if not authenticated:
            if request.get('token') != token:
                raise ValueError('invalid session token')
            authenticated = True
        envelope = {key: request.get(key) for key in ENVELOPE}
        try:
            result = session.dispatch(request)
            response = {**envelope, 'ok': True, 'obsolete': False, 'data': result}
        except Exception as exc:
            traceback.print_exc(file=sys.stderr)
            response = {**envelope, 'ok': False, 'obsolete': False,
                        'error': {'type': type(exc).__name__, 'message': str(exc)}}
        send(sock, response)
        if response['ok'] and request['command'] in ('shutdown', 'closeSession'):
            return


def dial(port, timeout=15):
    """Field owns the listener and this process connects back to it. The default."""
    return socket.create_connection(('127.0.0.1', port), timeout=timeout)


def listen(port, timeout=60):
    """This process owns the listener and Field connects in. Used for a container worker.

    A container has its own network namespace, so a Field listening on the *host's* 127.0.0.1 is
    unreachable from inside it and the dial-back direction cannot work. Binding 0.0.0.0 here is
    confined to that namespace: only the port the container runtime publishes escapes, and Field
    publishes it to the host's loopback interface alone. The token is still the first thing
    checked, so an unauthenticated peer that does reach the port gets one frame and an exception.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(('0.0.0.0', port))
        server.listen(1)
        server.settimeout(timeout)
        # Field waits for this line before it dials, so the port is bound by the time it tries.
        print('field-kratos listening on %d' % port, flush=True)
        connection, _ = server.accept()
        return connection


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--port', type=int, help='connect back to this Field-owned loopback port')
    group.add_argument('--listen', type=int, help='bind this port and wait for Field to connect in')
    args = parser.parse_args()
    with (dial(args.port) if args.port is not None else listen(args.listen)) as connection:
        connection.settimeout(None)
        serve(connection, os.environ.pop('FIELD_KRATOS_TOKEN'))
