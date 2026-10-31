"""Local visual experiments at http://127.0.0.1:8765 (no web dependencies)."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import time
import torch
from .model import load_model
from .server import Service, MAX_LINE


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--checkpoint', default=None, help='Checkpoint (.pt, .json); default: the bundled Distill weights')
    p.add_argument('--device', default='cpu')
    p.add_argument('--port', type=int, default=8765)
    a = p.parse_args()
    torch.set_num_threads(1)
    svc = Service(load_model(a.checkpoint, a.device))
    page = Path(__file__).with_name('lab.html').read_bytes()
    origin = f'http://127.0.0.1:{a.port}'

    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, data, kind):
            self.send_response(code)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path != '/':
                self.send_error(404); return
            self.reply(200, page, 'text/html; charset=utf-8')

        def do_POST(self):
            if (self.path != '/api' or self.headers.get('Content-Type') != 'application/json'
                or self.headers.get('Origin') not in (None, origin)
                or self.headers.get('Host') != f'127.0.0.1:{a.port}'):
                self.send_error(403); return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= MAX_LINE:
                    raise ValueError('Invalid request size')
                q = json.loads(self.rfile.read(size))
                t = time.perf_counter()
                result = svc.dispatch(q)
                data = json.dumps(dict(ok=True, result=result, elapsed_ms=(time.perf_counter()-t)*1000), allow_nan=False).encode()
                self.reply(200, data, 'application/json')
            except Exception as e:
                self.reply(400, json.dumps(dict(ok=False,error=str(e))).encode(), 'application/json')

        def log_message(self, *_):
            pass

    print(f'Open {origin} — Ctrl+C to stop', flush=True)
    with HTTPServer(('127.0.0.1', a.port), Handler) as server:
        server.serve_forever()


if __name__ == '__main__':
    main()
