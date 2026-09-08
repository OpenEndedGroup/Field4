"""Real socket-worker proof, runnable without Field. Optionally retain raw arrays as NPZ."""
import argparse
import json
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import uuid

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'python'))
from worker import receive, send


def main(output=None):
    token, session_id = str(uuid.uuid4()), str(uuid.uuid4())
    with tempfile.TemporaryDirectory(prefix='kratos-proof-') as tmp, socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(15)
        with open(pathlib.Path(tmp)/'worker.log', 'w+') as log:
            process = subprocess.Popen([sys.executable, str(ROOT/'python/worker.py'), '--port', str(listener.getsockname()[1])],
                                       env={**os.environ, 'FIELD_KRATOS_TOKEN': token, 'OMP_NUM_THREADS': '2'}, stdout=log, stderr=log)
            try:
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(30)
                    count = 0
                    def call(command, revision=0, **kwargs):
                        nonlocal count
                        count += 1
                        send(connection, dict(schemaVersion=1, units='SI:m,kg,s,K', sessionId=session_id,
                                              geometryRevision=revision, simulationRevision=revision, requestId=count,
                                              cancelled=False, command=command, token=token, **kwargs))
                        response = receive(connection)
                        assert response['ok'], response
                        assert response['requestId'] == count and response['geometryRevision'] == revision
                        return response['data']
                    def arrays(payload):
                        return {d['name']: np.frombuffer(payload['binary'], dtype={'float64':'<f8','int32':'<i4'}[d['dtype']],
                                                        count=d['length']//(8 if d['dtype']=='float64' else 4), offset=d['offset']).reshape(d['shape'])
                                for d in payload['arrays']}
                    capability = call('hello'); assert capability['available'], capability
                    background = call('createBackgroundDomain', bounds=[-1,-1,-1,1,1,1], resolution=12)
                    body = {'id':'body','op':'union',
                            'a':{'id':'base','op':'box','min':[-.73,-.63,-.52],'max':[.23,.53,.62]},
                            'b':{'id':'arm','op':'box','min':[-.13,-.32,-.42],'max':[.71,.33,.42]}}
                    a = call('solve',1,geometry=body)
                    body['b']['max'][0] = .43
                    b = call('solve',2,geometry=body)
                    av, bv = arrays(a), arrays(b)
                    assert a['diagnostics']['backgroundId'] == b['diagnostics']['backgroundId'] == background['backgroundId']
                    assert b['diagnostics']['buildCount'] == 1
                    changed = int(np.count_nonzero((av['distance']>0)!=(bv['distance']>0)))
                    delta = float(np.max(np.abs(av['temperature']-bv['temperature'])))
                    assert changed > 0 and delta > .01
                    if output:
                        np.savez(output, **arrays(background), before=av['temperature'], after=bv['temperature'], distance=bv['distance'])
                    print(json.dumps(dict(capability=capability, backgroundId=background['backgroundId'], buildCount=1,
                                          changedNodes=changed, maxTemperatureDelta=delta, first=a['diagnostics'], second=b['diagnostics']), indent=2))
                    call('shutdown')
                process.wait(timeout=5)
                assert process.returncode == 0
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=2)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=pathlib.Path)
    main(parser.parse_args().output)
