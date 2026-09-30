"""The completion sidecar (tools/code-use-semantic-sidecar) as a test or docs run needs it.

Field's default completion backend is the Analysis-API sidecar; without one Field quietly serves K1.
A run that should exercise completion therefore has to *provide* a sidecar, and has to know whether
the one it has can actually complete. `/health`'s `ok` cannot say: it only means the HTTP server is
up, and from 2026-09-28 to 09-30 it stayed `ok` while every AA session build failed. The `completion`
block the sidecar now reports is what readiness reads.

`derive_sidecar_port` and `sidecar_workspace_status` match `field.sh` byte for byte, so a run shares a
sidecar with an interactive `./field.sh` for the same checkout instead of starting a second one.
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import time
import urllib.request
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

SIDECAR_URL_ENV = "AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL"
SIDECAR_PORT_ENV = "AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT"

# A completion state that means the sidecar cannot serve AA completions. `idle` and `building` are
# fine before the first request: Field's first completion is what builds a session.
BROKEN_COMPLETION_STATES = frozenset({"failed", "failing"})


def derive_sidecar_port(repo_root: Path) -> int:
    """The checkout-derived sidecar port, computed exactly as `field.sh derive_sidecar_port` does.

    Byte-for-byte agreement is the whole point: a different formula here would start a SECOND sidecar
    for the same checkout, so a docs build and an interactive `./field.sh` would each pay a cold
    Analysis-API session instead of sharing one. `cksum` is shelled out to rather than reimplemented
    because POSIX `cksum` is CRC-32/CKSUM, which is *not* `zlib.crc32` -- a hand-rolled copy would agree
    on nothing and drift silently.
    """
    override = os.environ.get(SIDECAR_PORT_ENV, "").strip()
    if override:
        return int(override)
    try:
        completed = subprocess.run(
            ["cksum"], input=str(repo_root).encode("utf-8"), capture_output=True, check=True
        )
        checksum = int(completed.stdout.split()[0])
    except (OSError, ValueError, IndexError, subprocess.CalledProcessError):
        # No `cksum` (Windows): any stable port will do, it just won't be field.sh's.
        checksum = zlib.crc32(str(repo_root).encode("utf-8"))
    return 17841 + (checksum % 1000)


def _health_base(url: str) -> str:
    base = url.rstrip("/")
    for suffix in ("/analyze", "/complete"):
        if base.endswith(suffix):
            base = base[: -len(suffix)]
    return base


def sidecar_health(url: str, timeout: float = 1.0) -> dict[str, Any] | None:
    """The sidecar's `/health` payload, or None when nothing answers."""
    try:
        with urllib.request.urlopen(_health_base(url) + "/health", timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception:
        return None
    return payload if isinstance(payload, dict) else None


def sidecar_workspace_status(url: str, repo_root: Path) -> str:
    """`ok` / `wrong` / `down` for a sidecar URL, matching `field.sh sidecar_status`.

    A sidecar analyses one workspace, so one serving a *different* checkout is not reusable -- it is
    `wrong`, and reusing it would silently complete against another tree's sources.
    """
    payload = sidecar_health(url)
    if payload is None:
        return "down"
    reported = os.path.realpath(str(payload.get("workspaceRoot", "")))
    return "ok" if reported == os.path.realpath(str(repo_root)) else "wrong"


def completion_problem(url: str) -> str | None:
    """Why the sidecar at `url` cannot serve AA completions right now, or None if nothing says so.

    A sidecar built before the `completion` block existed reports none; that is a problem too, because
    nothing can then tell a working backend from the 09-28 one.
    """
    payload = sidecar_health(url)
    if payload is None:
        return f"no sidecar answers at {url}"
    completion = payload.get("completion")
    if not isinstance(completion, dict):
        return f"sidecar at {url} reports no completion state (an old build?); cannot tell whether AA works"
    state = str(completion.get("state", ""))
    if state in BROKEN_COMPLETION_STATES:
        return f"sidecar at {url} completion state is {state!r}: {completion.get('lastError') or 'no error recorded'}"
    return None


@dataclass
class SidecarLease:
    """One sidecar for this checkout, reused if healthy and started otherwise; stopped only if started.

    `ensure()` returns the URL, or None with the reason in `unavailable`. A sidecar this lease did not
    start stays externally owned (an interactive Field may be using it) and `stop()` leaves it alone.
    """

    repo_root: Path
    log_path: Path
    start_deadline_sec: float = 300.0
    log: Callable[[str], None] = lambda message: None
    url: str | None = None
    unavailable: str | None = None
    started_by_lease: bool = False
    _process: subprocess.Popen[str] | None = field(default=None, repr=False)
    _checked: bool = False

    def ensure(self) -> str | None:
        if self._checked:
            return self.url
        self._checked = True
        if not (self.repo_root / "tools" / "code-use-semantic-sidecar").is_dir():
            return self._fail(f"no tools/code-use-semantic-sidecar in {self.repo_root}")

        # An inherited URL wins if it is serving this checkout -- someone who exported one meant it.
        configured = os.environ.get(SIDECAR_URL_ENV, "").strip()
        if configured and sidecar_workspace_status(configured, self.repo_root) == "ok":
            return self._use(configured, f"using configured completion sidecar {configured}")

        port = derive_sidecar_port(self.repo_root)
        url = f"http://127.0.0.1:{port}"
        status = sidecar_workspace_status(url, self.repo_root)
        if status == "ok":
            return self._use(url, f"reusing completion sidecar {url}")
        if status == "wrong":
            return self._fail(
                f"{url} serves a different workspace; set {SIDECAR_PORT_ENV} to a sidecar for {self.repo_root}"
            )

        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        gradle_wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        command = [
            str(self.repo_root / gradle_wrapper), "--no-daemon", "-p", "tools/code-use-semantic-sidecar", "run",
            f"--args=--port {port} --workspace {self.repo_root}",
        ]
        self.log(f"starting completion sidecar on port {port} (log: {self.log_path})")
        popen_kwargs: dict[str, Any] = {
            "cwd": str(self.repo_root),
            "stdout": self.log_path.open("w", encoding="utf-8"),
            "stderr": subprocess.STDOUT,
            "stdin": subprocess.DEVNULL,
            "text": True,
        }
        if os.name == "nt":
            popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            popen_kwargs["start_new_session"] = True
        try:
            process = subprocess.Popen(command, **popen_kwargs)
        except OSError as exc:
            return self._fail(f"could not start the completion sidecar: {exc}")

        deadline = time.monotonic() + self.start_deadline_sec
        while time.monotonic() < deadline:
            if process.poll() is not None:
                return self._fail(
                    f"completion sidecar exited with code {process.returncode} before becoming healthy; "
                    f"see {self.log_path}"
                )
            if sidecar_workspace_status(url, self.repo_root) == "ok":
                self._process = process
                self.started_by_lease = True
                return self._use(url, f"completion sidecar ready on {url}")
            time.sleep(1.0)
        self._process = process
        self.started_by_lease = True
        self.stop()
        return self._fail(
            f"completion sidecar did not become healthy in {self.start_deadline_sec:.0f}s; see {self.log_path}"
        )

    def problem(self) -> str | None:
        """Why AA completion is unavailable now: no sidecar, or one whose completion state is broken."""
        if self.url is None:
            return self.unavailable or "no completion sidecar"
        return completion_problem(self.url)

    def stop(self) -> None:
        """Stop only a sidecar this lease started."""
        process = self._process
        if not self.started_by_lease or process is None:
            return
        self.log(f"stopping completion sidecar at {self.url}")
        if process.poll() is None:
            try:
                if os.name == "nt":
                    process.terminate()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                try:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        self._process = None

    def _use(self, url: str, message: str) -> str:
        self.log(message)
        self.url = url
        self.unavailable = None
        return url

    def _fail(self, reason: str) -> None:
        self.log(reason)
        self.url = None
        self.unavailable = reason
        return None
