"""Lifecycle test faults; successful physics still runs the real Kratos worker."""
import os
import pathlib
import socket
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / 'python'))
import worker

port = int(sys.argv[sys.argv.index('--port')+1])
# Filename of a copied fixture selects the fault, keeping the production API clean.
mode = pathlib.Path(__file__).stem
with socket.create_connection(('127.0.0.1', port)) as connection:
    if mode == 'crash':
        worker.receive(connection)
        os._exit(7)
    elif mode == 'timeout':
        worker.receive(connection)
        time.sleep(60)
    elif mode == 'mismatch':
        request = worker.receive(connection)
        response = {key: request[key] for key in worker.ENVELOPE}
        worker.send(connection, {**response, 'schemaVersion': 99, 'ok': True, 'obsolete': False, 'data': {}})
    elif mode == 'delay':
        import thermal
        solve = thermal.ThermalDomain.solve
        def delayed(self, *args, **kwargs):
            pathlib.Path(__file__).with_suffix('.started').touch()
            time.sleep(.3)
            return solve(self, *args, **kwargs)
        thermal.ThermalDomain.solve = delayed
        worker.serve(connection, os.environ['FIELD_KRATOS_TOKEN'])
    elif mode == 'startup':
        pathlib.Path(__file__).with_suffix('.started').touch()
        time.sleep(.3)
        worker.serve(connection, os.environ['FIELD_KRATOS_TOKEN'])
    else:
        raise ValueError(mode)
