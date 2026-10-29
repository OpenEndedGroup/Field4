#!/usr/bin/env python3
"""Inspect prerequisites or fetch pinned sources. No model inference is implied by a successful probe.

No Torch installation, license acceptance, gated-weight download or user process termination.
Each backend has its own interpreter. The portable recording tools need only the standard library.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
PINS = json.loads((ROOT / "upstreams.json").read_text())


def command(argv, timeout=15, cwd=None):
    try:
        p = subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        return dict(exitCode=p.returncode, stdout=p.stdout[-4096:], stderr=p.stderr[-4096:])
    except (OSError, subprocess.TimeoutExpired) as e:
        return dict(exitCode=None, error=type(e).__name__ + ": " + str(e))


def hf_access(repo):
    token_file = Path(os.environ.get("HF_TOKEN_PATH", str(Path(os.environ.get("HF_HOME", str(Path.home() / ".cache/huggingface")))) + "/token"))
    token = os.environ.get("HF_TOKEN") or (token_file.read_text().strip() if token_file.is_file() else None)
    headers = {"Authorization": "Bearer " + token} if token else {}
    request = urllib.request.Request("https://huggingface.co/" + repo + "/resolve/main/config.json", method="HEAD", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return dict(repository=repo, status=response.status, credentialPresent=bool(token))
    except urllib.error.HTTPError as e:
        # Never include request/redirect headers or token values in the evidence file.
        return dict(repository=repo, status=e.code, code=e.headers.get("X-Error-Code"), credentialPresent=bool(token))
    except (OSError, urllib.error.URLError) as e:
        return dict(repository=repo, error=type(e).__name__)


def probe(backend, development, network=False):
    pins = PINS[backend]
    source = development / "upstream" / ("CLoSD" if backend == "closd" else "ardy")
    interpreter = development / "environments" / backend / "bin/python"
    actual = command(["git", "rev-parse", "HEAD"], cwd=source) if source.is_dir() else None
    result = dict(backend=backend, inferenceValidated=False, timestamp=datetime.now(timezone.utc).isoformat(),
                  platform=platform.platform(), pins=pins, source=str(source), sourceCommit=actual,
                  interpreter=str(interpreter), interpreterExists=interpreter.is_file(),
                  freeBytes=shutil.disk_usage(development).free,
                  gpu=command(["nvidia-smi", "--query-gpu=name,driver_version,memory.total,memory.used", "--format=csv"]))
    blockers = []
    if actual is None or actual.get("stdout", "").strip() != pins["commit"]:
        blockers.append("Pinned upstream source missing or different revision")
    if not interpreter.is_file():
        blockers.append("Separate Python " + pins["python"] + " environment absent")
    if backend == "ardy":
        if network:
            result["textEncoderAccess"] = hf_access(pins["gatedModel"])
            if result["textEncoderAccess"].get("status") != 200:
                blockers.append("Llama text-encoder authorization unavailable; resolve access before live integration")
        else:
            blockers.append("Gated text-encoder access not checked (use --network)")
    else:
        sdk = os.environ.get("ISAAC_GYM_ROOT")
        result["isaacGymRootConfigured"] = bool(sdk)
        result["isaacGymPackagePresent"] = bool(sdk and (Path(sdk) / "python/isaacgym/__init__.py").is_file())
        if not result["isaacGymPackagePresent"]:
            blockers.append("Set ISAAC_GYM_ROOT to an extracted, licensed Isaac Gym SDK")
    if interpreter.is_file():
        # Isaac Gym must be imported before Torch in the legacy environment.
        script = "import isaacgym; import torch; print(torch.__version__, torch.cuda.is_available())" if backend == "closd" else "import torch, ardy; print(torch.__version__, torch.cuda.is_available())"
        result["imports"] = command([str(interpreter), "-c", script], cwd=source if source.is_dir() else None, timeout=30)
        if result["imports"].get("exitCode") != 0:
            blockers.append("Backend imports failed; inspect imports diagnostics")
    result["blockers"] = blockers
    result["prerequisitesReady"] = not blockers
    return result


def fetch(backend, development):
    pins = PINS[backend]
    target = development / "upstream" / ("CLoSD" if backend == "closd" else "ardy")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=target, text=True).strip()
        if actual != pins["commit"]:
            raise ValueError("Existing source has a different revision; preserve it and choose another --directory")
        return target
    subprocess.run(["git", "clone", "--no-checkout", pins["repository"], str(target)], check=True)
    subprocess.run(["git", "checkout", "--detach", pins["commit"]], cwd=target, check=True)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["probe", "fetch"])
    parser.add_argument("backend", choices=["ardy", "closd"])
    parser.add_argument("--directory", type=Path, required=True, help="Development directory, holding upstream/ and environments/")
    parser.add_argument("--network", action="store_true", help="Check gated Hugging Face access without downloading weights")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    if args.action == "fetch":
        print(fetch(args.backend, args.directory.resolve()))
        return 0
    result = probe(args.backend, args.directory.resolve(), args.network)
    encoded = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(encoded + "\n")
    print(encoded)
    return 0 if result["prerequisitesReady"] else 2


if __name__ == "__main__":
    sys.exit(main())
