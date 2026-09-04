"""Explicit interpreter bootstrap for Field's agent-facing Python entrypoints.

This module is deliberately side-effect free when imported. Entrypoints call
``ensure_repo_python`` before importing their optional dependencies; libraries do not get to
restart the process merely because another program imported them.
"""

from __future__ import annotations

import importlib.util
import os
import shutil
import sys
from pathlib import Path
from typing import Iterable


def _is_running_interpreter(candidate: Path) -> bool:
    try:
        return os.path.samefile(str(candidate), sys.executable)
    except OSError:
        return False


def ensure_repo_python(required_modules: Iterable[str] = ("websockets",)) -> None:
    """Re-exec this entrypoint in the repo environment when dependencies are absent."""
    missing = [name for name in required_modules if importlib.util.find_spec(name) is None]
    if not missing:
        return
    if os.environ.get("FIELD_BACKDOOR_REEXEC") == "1":
        raise SystemExit(
            f"Field Python environment still lacks {', '.join(missing)} after re-exec.\n"
            "Run: uv sync --group backdoor"
        )

    repo_root = Path(__file__).resolve().parent.parent
    entry = os.path.abspath(sys.argv[0]) if sys.argv and sys.argv[0] else str(Path(__file__).resolve())
    env = {**os.environ, "FIELD_BACKDOOR_REEXEC": "1"}
    venv_python = repo_root / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python3")
    if venv_python.exists() and not _is_running_interpreter(venv_python):
        os.execve(str(venv_python), [str(venv_python), entry, *sys.argv[1:]], env)

    uv = shutil.which("uv")
    if uv:
        os.execvpe(
            uv,
            [uv, "run", "--project", str(repo_root), "--group", "backdoor", "python", entry, *sys.argv[1:]],
            env,
        )
    raise SystemExit(
        f"Field Python entrypoint requires {', '.join(missing)}, but no usable repo .venv or uv was found.\n"
        "Run via: uv run --project . --group backdoor python <entrypoint> ..."
    )
