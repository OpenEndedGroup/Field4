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

SCHEMA = 1
UNITS = 'SI:m,kg,s,K'
MAX_FRAME = 64 * 1024 * 1024
ENVELOPE = ('schemaVersion', 'units', 'sessionId', 'geometryRevision', 'simulationRevision', 'requestId', 'cancelled')


def capabilities():
    applications, errors = [], {}
    for name in ('KratosMultiphysics', 'KratosMultiphysics.ConvectionDiffusionApplication', 'numpy'):
        try:
            importlib.import_module(name)
            applications.append(name)
        except Exception as exc:
            errors[name] = str(exc)
    versions = {}
    for name in ('KratosMultiphysics', 'KratosConvectionDiffusionApplication', 'numpy', 'msgpack'):
        try:
            versions[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            versions[name] = None
    return {'available': not errors, 'python': sys.version, 'executable': sys.executable,
            'platform': platform.platform(), 'applications': applications, 'versions': versions,
            'errors': errors, 'formulation': 'EmbeddedLaplacianElement3D4N',
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
            self.domain = None
            return {'closed': True}
        if command == 'cancel' or request['cancelled']:
            return {'cancelled': True, 'policy': 'serialized worker; Field suppresses in-flight obsolete results'}
        rev = (request['geometryRevision'], request['simulationRevision'])
        if any(a < b for a, b in zip(rev, self.revision)):
            raise ValueError('obsolete revision')
        if command == 'createBackgroundDomain':
            if self.domain is not None:
                raise ValueError('background already exists; create a new session to change domain bounds/resolution')
            from thermal import ThermalDomain
            self.domain = ThermalDomain(request['bounds'], request['resolution'])
            self.revision = rev
            return {**arrays_payload({'positions': self.domain.positions, 'cells': self.domain.cells}),
                    'backgroundId': self.domain.id, 'buildCount': self.domain.build_count,
                    'domainBuildMs': self.domain.build_ms}
        if command == 'solve':
            if self.domain is None:
                raise ValueError('createBackgroundDomain first')
            import numpy as np
            distance = request.get('distance')
            if distance is not None:
                if not isinstance(distance, bytes) or len(distance) != len(self.domain.nodes) * 8:
                    raise ValueError('distance binary must be float64[nodeCount], little endian')
                distance = np.frombuffer(distance, dtype='<f8')
            if (distance is None) == (request.get('geometry') is None):
                raise ValueError('provide exactly one geometry expression or distance binary')
            self.revision = rev
            if 'before_solve' in self.setup:
                self.setup['before_solve'](self.domain, request)
            arrays, diagnostic = self.domain.solve(geometry=request.get('geometry'), distances=distance,
                                                   **request.get('thermal', {}))
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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, required=True)
    args = parser.parse_args()
    with socket.create_connection(('127.0.0.1', args.port), timeout=15) as connection:
        connection.settimeout(None)
        serve(connection, os.environ.pop('FIELD_KRATOS_TOKEN'))
