#!/usr/bin/env python3
"""Read-only GPU/model feedback for Field's Local provider; Python standard library only."""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import subprocess
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def gpu_stats() -> list[dict]:
    """Report host-wide NVIDIA usage, including other applications."""
    result = subprocess.run(
        ["nvidia-smi", "--query-gpu=index,name,temperature.gpu,utilization.gpu,memory.used,memory.total",
         "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=2, check=True)
    def number(value: str):
        try:
            return float(value.strip())
        except ValueError:
            return None
    return [dict(index=row[0].strip(), name=row[1].strip(), temperatureC=number(row[2]),
                 utilizationPercent=number(row[3]), memoryUsedMiB=number(row[4]), memoryTotalMiB=number(row[5]))
            for row in csv.reader(io.StringIO(result.stdout), skipinitialspace=True) if len(row) == 6]


def parse_metrics(text: str) -> dict[str, float]:
    values = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) == 2 and parts[0].startswith("llamacpp:") and "{" not in parts[0]:
            try:
                values[parts[0].removeprefix("llamacpp:")] = float(parts[1])
            except ValueError:
                pass
    return values


class Sampler:
    def __init__(self, backend: str, api_key: str = ""):
        self.backend = backend.rstrip("/")
        self.api_key = api_key
        self.snapshot = {"schemaVersion": 1, "sampledAtMs": 0, "gpus": [], "state": "unavailable"}
        self.previous = None

    def read(self, path: str) -> str:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        request = urllib.request.Request(self.backend + path, headers=headers)
        # These endpoints do not wake llama.cpp or reset its idle timer. Never query /slots here.
        with urllib.request.urlopen(request, timeout=2) as response:
            return response.read(262144).decode()

    def sample(self):
        snapshot = {"schemaVersion": 1, "sampledAtMs": int(time.time() * 1000), "gpus": [], "state": "unavailable"}
        try:
            snapshot["gpus"] = gpu_stats()
        except (OSError, subprocess.SubprocessError):
            snapshot["gpuUnavailable"] = True
        try:
            props = json.loads(self.read("/props"))
            snapshot["contextCapacity"] = props.get("default_generation_settings", {}).get("n_ctx")
            snapshot["state"] = "sleeping" if props.get("is_sleeping") else "ready"
        except urllib.error.HTTPError as error:
            if error.code == 503 and b"Loading model" in error.read(4096):
                snapshot["state"] = "loading"
        except (OSError, ValueError, TypeError, AttributeError):
            pass
        try:
            metrics = parse_metrics(self.read("/metrics"))
            now = time.monotonic()
            busy = metrics.get("requests_processing", 0)
            snapshot["requestsProcessing"] = busy
            snapshot["requestsQueued"] = metrics.get("requests_deferred", 0)
            if busy:
                snapshot["state"] = "working"
                if self.previous:
                    then, previous = self.previous
                    elapsed = now - then
                    generated = metrics.get("tokens_predicted_total", 0) - previous.get("tokens_predicted_total", 0)
                    if elapsed > 0 and generated > 0:
                        snapshot["state"] = "generating"
                        snapshot["generationTokensPerSecond"] = generated / elapsed
            self.previous = (now, metrics)
        except (OSError, ValueError):
            self.previous = None
        self.snapshot = snapshot


def serve(host: str, port: int, backend: str, api_key: str):
    sampler = Sampler(backend, api_key)

    def sample_loop():
        while True:
            sampler.sample()
            time.sleep(2)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/status":
                self.send_error(404)
                return
            body = json.dumps(sampler.snapshot, allow_nan=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    threading.Thread(target=sample_loop, daemon=True).start()
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"Local AI monitor: http://{host}:{port}/status; model server {backend}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8082)
    parser.add_argument("--backend", default="http://127.0.0.1:8081")
    args = parser.parse_args()
    serve(args.host, args.port, args.backend, os.environ.get("LLAMA_API_KEY", ""))
