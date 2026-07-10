from __future__ import annotations

import atexit
import hashlib
import json
import logging
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any
import urllib.error
import urllib.request
import ssl

from mkdocs.structure.files import File

# Shared with tools/discover_field_tests.py; see public/fabricate_docs_scenes.py.
# public/ is already on sys.path when mkdocs loads this hook, so this stays a
# clean leaf import with no sys.path manipulation.
from fabricate_docs_scenes import FABRICATE_DOCS_SCENES, validate_scenes_against_harness


LOG = logging.getLogger("mkdocs.field_runner")

RUNNER_TARGETS = {
    "diffstatic_runner": "diffstatic",
    "diff_runner": "diff",
    "diffstatic": "diffstatic",
    "diff": "diff",
}
RUNNER_FENCES = {"diffstatic_runner", "diff_runner"}
DIFF_FENCES = {"diff", "diff_runner"}
STATIC_FENCES = {"diffstatic", "diffstatic_runner"}
FENCE_NAMES = sorted(RUNNER_TARGETS, key=len, reverse=True)
SCRIPT_VERSION = 17
RUNNER_RETRIES = 3
RUNNER_SUBPROCESS_GRACE_SEC = 5.0
# Kept consistent with tools/field_backdoor.py: the first Playwright attach on a
# machine may download browser binaries. While that marker is absent, give the
# capture subprocess enough headroom to finish the download instead of killing it
# at ~50s and retrying with full JVM restarts.
PLAYWRIGHT_READY_MARKER = Path(__file__).resolve().parent.parent / ".field-backdoor" / "playwright-ready"
PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC = float(os.environ.get("AFIELD_PLAYWRIGHT_ATTACH_TIMEOUT", "180"))
RUNNER_MODE_ENV = "FIELD_DOCS_RUNNER_MODE"
RUNNER_CONTINUE_ON_ERROR_ENV = "FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR"
RUNNER_PORT_ENV = "FIELD_DOCS_RUNNER_PORT"
RUNNER_WORKSPACE_ENV = "FIELD_DOCS_RUNNER_WORKSPACE"
RUNNER_MODE_ALIASES = {
    "incremental": "incremental",
    "cache": "incremental",
    "cached": "incremental",
    "strict": "strict",
    "fresh": "strict",
    "rebuild": "strict",
    "always": "always",
    "never": "never",
    "skip": "never",
    "off": "never",
}
FENCE_OPEN_RE = re.compile(
    rf"^(?P<indent>[ ]{{0,3}})(?P<fence>`{{3,}})(?P<runner>{'|'.join(re.escape(name) for name in FENCE_NAMES)})"
    r"(?P<rest>.*?)(?P<newline>\r?\n)?$"
)
GENERIC_FENCE_OPEN_RE = re.compile(r"^(?P<indent>[ ]{0,3})(?P<fence>`{3,}|~{3,}).*(?:\r?\n)?$")
MARKER_PREFIXES = (":::static", ":::before", ":::after", ":::")
TRUE_VALUES = {"1", "true", "yes", "on"}
FALSE_VALUES = {"0", "false", "no", "off"}
RUNNER_DIGEST_SOURCE_FILES = (
    "public/docs_hooks.py",
    "src/jsMain/kotlin/afield/Main.kt",
    "src/jvmMain/kotlin/afield/docs/screenshot.kt",
    "src/jvmMain/kotlin/afield/graphics/FLine.kt",
    "src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt",
    "src/jvmMain/kotlin/afield/four/fabricated/StandardRendererDocsHarness.kt",
    "src/jvmMain/kotlin/afield/nano/NanoVisualization.kt",
    "src/jvmMain/kotlin/afield/graphics/someSkiko.kt",
    "src/commonMain/resources/outputareas2.js",
    "tools/field_backdoor.py",
    "tools/run_field_tests.py",
)

@dataclass(frozen=True)
class RunnerOptions:
    screenshot: str
    source_image: str | None
    capture: str
    execution: str
    wait: float
    padding: float
    timeout: float
    property_name: str
    embed: bool
    alt: str | None
    document: str | None
    source_box: str | None
    source_box_properties: tuple[str, ...]
    source_box_wait: float
    run_box: str | None
    capture_box: str | None
    setup_boxes: tuple[str, ...]
    macro_file: str | None
    macro_name: str | None
    macro_selection: tuple[str, ...]
    fabricate_scene: str | None
    native_wait_frames: int
    trim_native: bool
    box_name: str
    box_x: float
    box_y: float
    box_w: float
    box_h: float
    open_outputs: bool
    wait_output: bool
    wait_output_kind: str
    wait_output_channel: str | None
    wait_output_contains: str | None
    with_editor: bool
    graphics_context: bool
    force: bool
    video: str | None  # path to .mp4 output; when set, records video instead of screenshot
    duration: float  # video duration in seconds (default 5.0)
    fps: int  # video frame rate (default 30)
    hidden: bool
    language_key: str


@dataclass(frozen=True)
class CacheEntry:
    digest: str
    metadata: dict[str, Any] | None = None


class RunnerState:
    def __init__(self, config: Any) -> None:
        self.config = config
        config_path = Path(config.config_file_path).resolve()
        self.repo_root = config_path.parent.parent
        self.docs_dir = Path(config["docs_dir"]).resolve()
        self.cache_path = config_path.parent / ".field-docs-runner-cache.json"
        self.shared_log_path = config_path.parent / ".field-docs-runner-field.log"
        self.runner_mode = resolve_runner_mode(os.environ.get(RUNNER_MODE_ENV))
        self.backdoor_port = int(os.environ.get(RUNNER_PORT_ENV, "9092"))
        self.continue_on_error = resolve_continue_on_error(
            os.environ.get(RUNNER_CONTINUE_ON_ERROR_ENV),
            runner_mode=self.runner_mode,
        )
        self._shared_field_process: subprocess.Popen[str] | None = None
        self._shared_field_started_by_runner = False
        self._closed = False
        self.failures: list[str] = []
        validate_scenes_against_harness(self.repo_root)
        self.runner_source_digest = compute_runner_source_digest(self.repo_root)
        self.cache, self.cache_metadata = self._load_cache()
        self.dirty = False
        self.started_at = time.monotonic()
        self.summary_logged = False
        self.screenshots_run = 0
        self.videos_run = 0
        self.cache_hits = 0
        self.assets_reused = 0
        self.missing_assets = 0
        self.server_starts = 0
        self.server_restarts = 0
        LOG.info("Field docs runner mode: %s", self.runner_mode)
        LOG.info("Field docs runner continue on error: %s", self.continue_on_error)
        if self.runner_mode != "never":
            self._ensure_shared_field_server()
        atexit.register(self.close)

    def handle_capture_error(self, *, page_path: Path, output_path: Path, error: Exception) -> None:
        message = f"{page_path.name} -> {output_path}: {error}"
        self.failures.append(message)
        LOG.warning("Field docs runner skipping failed asset: %s", message)

    def _http_health_ok(self, port: int, timeout: float = 1.5) -> bool:
        url = f"https://localhost:{port}/health"
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        try:
            with urllib.request.urlopen(url, timeout=timeout, context=ctx) as response:
                body = response.read().decode("utf-8", errors="replace").strip().lower()
                return body == "ok"
        except (OSError, urllib.error.URLError, urllib.error.HTTPError):
            return False

    def _request_shared_field_shutdown(self) -> None:
        url = f"https://localhost:{self.backdoor_port}/api/shutdown-instance"
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        request = urllib.request.Request(url, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=3.0, context=ctx) as response:
                response.read()
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
            LOG.debug("Field docs runner shutdown request failed: %s", exc)

    def _ensure_shared_field_server(self) -> None:
        if self._http_health_ok(self.backdoor_port):
            LOG.info("Field docs runner using existing Field server on port %s", self.backdoor_port)
            return

        gradle_wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        cmd = [str(self.repo_root / gradle_wrapper), "runJvm"]
        workspace = os.environ.get(RUNNER_WORKSPACE_ENV)
        if workspace:
            cmd.append(f"--args=--workspace {workspace}")

        self.shared_log_path.parent.mkdir(parents=True, exist_ok=True)
        log_handle = self.shared_log_path.open("w", encoding="utf-8")

        popen_kwargs: dict[str, Any] = {
            "cwd": str(self.repo_root),
            "stdout": log_handle,
            "stderr": subprocess.STDOUT,
            "stdin": subprocess.DEVNULL,
            "text": True,
            "env": {**os.environ, "PORT": str(self.backdoor_port)},
        }
        if os.name == "nt":
            popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            popen_kwargs["start_new_session"] = True

        LOG.info("Field docs runner starting shared Field server on port %s", self.backdoor_port)
        self._shared_field_process = subprocess.Popen(cmd, **popen_kwargs)
        self._shared_field_started_by_runner = True
        self.server_starts += 1

        deadline = time.monotonic() + 120.0
        while time.monotonic() < deadline:
            process = self._shared_field_process
            if process is None:
                break
            if process.poll() is not None:
                raise RuntimeError(
                    "Field docs runner could not start shared Field server; "
                    f"process exited with code {process.returncode}. See {self.shared_log_path}"
                )
            if self._http_health_ok(self.backdoor_port):
                LOG.info("Field docs runner shared Field server ready on port %s", self.backdoor_port)
                return
            time.sleep(0.5)

        raise RuntimeError(
            f"Field docs runner timed out waiting for shared Field server on port {self.backdoor_port}. "
            f"See {self.shared_log_path}"
        )

    def _ensure_runner_owned_field_server(self, *, reason: str) -> None:
        if self._shared_field_started_by_runner and self._http_health_ok(self.backdoor_port):
            return

        if self._shared_field_started_by_runner:
            self._stop_shared_field_server()
            self._ensure_shared_field_server()
            return

        if self._http_health_ok(self.backdoor_port):
            LOG.warning(
                "Field docs runner needs a fresh runner-owned Field server for %s; "
                "requesting shutdown of existing server on port %s",
                reason,
                self.backdoor_port,
            )
            self._request_shared_field_shutdown()
            deadline = time.monotonic() + 12.0
            while time.monotonic() < deadline:
                if not self._http_health_ok(self.backdoor_port, timeout=0.5):
                    break
                time.sleep(0.25)
            if self._http_health_ok(self.backdoor_port, timeout=0.5):
                raise RuntimeError(
                    "Field docs runner could not take ownership of the Field server on "
                    f"port {self.backdoor_port} for {reason}. Stop the stale Field process "
                    "or choose a fresh FIELD_DOCS_RUNNER_PORT."
                )

        self._ensure_shared_field_server()

    def _stop_shared_field_server(self) -> None:
        if not self._shared_field_started_by_runner:
            return
        process = self._shared_field_process
        if process is None:
            return
        LOG.info("Field docs runner stopping shared Field server")
        self._request_shared_field_shutdown()
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            if process.poll() is not None and not self._http_health_ok(self.backdoor_port, timeout=0.5):
                self._shared_field_process = None
                return
            if not self._http_health_ok(self.backdoor_port, timeout=0.5):
                self._shared_field_process = None
                return
            time.sleep(0.25)
        if process.poll() is not None:
            self._shared_field_process = None
            return
        try:
            if os.name == "nt":
                process.terminate()
            else:
                os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            self._shared_field_process = None
            return
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                if os.name == "nt":
                    process.kill()
                else:
                    os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                self._shared_field_process = None
                return
            process.wait(timeout=5)
        self._shared_field_process = None

    def _restart_shared_field_server(self, *, reason: str) -> None:
        if not self._shared_field_started_by_runner:
            return
        LOG.warning("Field docs runner restarting shared Field server: %s", reason)
        self.server_restarts += 1
        self._stop_shared_field_server()
        self._ensure_shared_field_server()

    def _load_cache(self) -> tuple[dict[str, str], dict[str, dict[str, Any]]]:
        try:
            payload = json.loads(self.cache_path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {}, {}
        except Exception as exc:
            LOG.warning("Field docs runner ignored unreadable cache %s: %s", self.cache_path, exc)
            return {}, {}
        if not isinstance(payload, dict):
            return {}, {}
        entries = payload.get("entries")
        if not isinstance(entries, dict):
            return {}, {}

        cache: dict[str, str] = {}
        metadata: dict[str, dict[str, Any]] = {}
        for key, value in entries.items():
            cache_key = str(key)
            if isinstance(value, str):
                cache[cache_key] = value
                continue
            if not isinstance(value, dict):
                continue
            digest = value.get("digest")
            if not isinstance(digest, str):
                continue
            cache[cache_key] = digest
            entry_metadata = value.get("metadata")
            if isinstance(entry_metadata, dict):
                metadata[cache_key] = entry_metadata
        return cache, metadata

    def save(self) -> None:
        if not self.dirty:
            return
        entries: dict[str, Any] = {}
        for cache_key, digest in self.cache.items():
            metadata = self.cache_metadata.get(cache_key)
            if metadata is None:
                entries[cache_key] = digest
            else:
                entries[cache_key] = {
                    "digest": digest,
                    "metadata": metadata,
                }
        payload = {
            "version": SCRIPT_VERSION,
            "mode": self.runner_mode,
            "entries": entries,
        }
        self.cache_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        self.dirty = False

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self.log_summary()
        self.save()
        self._stop_shared_field_server()

    def log_summary(self) -> None:
        if self.summary_logged:
            return
        self.summary_logged = True
        elapsed = time.monotonic() - self.started_at
        regenerated = self.screenshots_run + self.videos_run
        skipped = self.cache_hits + self.assets_reused + self.missing_assets + len(self.failures)
        LOG.info(
            "Field docs runner summary: regenerated=%s (screenshots=%s videos=%s), "
            "skipped=%s (cache_hits=%s reused_existing=%s missing=%s failures=%s), "
            "server_starts=%s server_restarts=%s elapsed=%.1fs",
            regenerated,
            self.screenshots_run,
            self.videos_run,
            skipped,
            self.cache_hits,
            self.assets_reused,
            self.missing_assets,
            len(self.failures),
            self.server_starts,
            self.server_restarts,
            elapsed,
        )

    def ensure_video(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        """Record a short video clip instead of taking a screenshot."""
        assert options.video is not None
        # Always record docs videos from the editor pane.
        # Keep screenshot capture mode independent when a fence emits both.
        video_options = replace(options, capture="editor")
        video_path = resolve_output_path(page_path, options.video)
        video_path.parent.mkdir(parents=True, exist_ok=True)
        cache_allowed = self.runner_mode != "always"
        cache_metadata = compute_cache_metadata(
            code=code,
            options=video_options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
        )
        digest = compute_digest(
            code=code,
            options=video_options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
        )
        cache_key = str(video_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and video_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit (video): %s", video_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=video_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing video asset after runner-only cache change: %s", video_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and video_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in video asset without cache entry: %s", video_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s (video): %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=video_path,
                output_exists=video_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )
        if video_options.graphics_context:
            self._ensure_runner_owned_field_server(
                reason=f"graphics-context video for {page_path.name} -> {video_path}"
            )
        command = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=video_path,
            options=video_options,
            is_video=True,
        )
        LOG.info("Field docs runner recording video %s for %s", page_path.name, video_path)
        completed: subprocess.CompletedProcess[str] | None = None
        for attempt in range(1, RUNNER_RETRIES + 1):
            try:
                completed = subprocess.run(
                    command,
                    cwd=self.repo_root,
                    input=code,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    capture_output=True,
                    check=False,
                    timeout=runner_subprocess_timeout(options=video_options, is_video=True),
                )
            except subprocess.TimeoutExpired as exc:
                if attempt < RUNNER_RETRIES:
                    LOG.warning(
                        "Field docs runner video attempt %s/%s timed out for %s -> %s; retrying\n%s",
                        attempt,
                        RUNNER_RETRIES,
                        page_path.name,
                        video_path,
                        format_timeout_failure_details(
                            exc,
                            command=command,
                            field_log_path=self.shared_log_path,
                        ),
                    )
                    self._restart_shared_field_server(
                        reason=f"timeout while recording {page_path.name} -> {video_path}"
                    )
                    continue
                self._restart_shared_field_server(
                    reason=f"final timeout while recording {page_path.name} -> {video_path}"
                )
                raise RuntimeError(
                    f"Field docs runner video timed out for {page_path.name} -> {video_path}.\n"
                    f"Command: {shlex.join(command)}\n"
                    f"Timed out after {exc.timeout:.1f}s"
                ) from exc
            if completed.returncode == 0:
                break
            if attempt < RUNNER_RETRIES:
                LOG.warning(
                    "Field docs runner video attempt %s/%s failed for %s -> %s; retrying\n%s",
                    attempt,
                    RUNNER_RETRIES,
                    page_path.name,
                    video_path,
                    format_completed_failure_details(
                        completed,
                        command=command,
                        field_log_path=self.shared_log_path,
                    ),
                )
                self._restart_shared_field_server(
                    reason=f"failed video capture attempt {attempt}/{RUNNER_RETRIES} for {page_path.name} -> {video_path}"
                )
            else:
                self._restart_shared_field_server(
                    reason=f"final failed video capture attempt for {page_path.name} -> {video_path}"
                )
        assert completed is not None
        if completed.returncode != 0:
            details = format_completed_failure_details(
                completed,
                command=command,
                field_log_path=self.shared_log_path,
            )
            raise RuntimeError(
                f"Field docs runner video failed for {page_path.name} -> {video_path}.\n"
                f"{details}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {video_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{execution_error}"
            )
        output_gap = extract_backdoor_output_gap(stdout=completed.stdout, code=code, options=video_options)
        if output_gap is not None:
            raise RuntimeError(
                f"Field docs runner output validation failed for {page_path.name} -> {video_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{output_gap}"
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.videos_run += 1
        self.save()

    def ensure_screenshot(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        screenshot_path = resolve_output_path(page_path, options.screenshot)
        screenshot_path.parent.mkdir(parents=True, exist_ok=True)
        cache_allowed = self.runner_mode != "always"
        cache_metadata = compute_cache_metadata(
            code=code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
        )
        digest = compute_digest(
            code=code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
        )
        cache_key = str(screenshot_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and screenshot_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit: %s", screenshot_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=screenshot_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing screenshot asset after runner-only cache change: %s", screenshot_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and screenshot_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in screenshot asset without cache entry: %s", screenshot_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s: %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=screenshot_path,
                output_exists=screenshot_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )
        source_image_path = (
            resolve_auxiliary_output_path(page_path, options.source_image)
            if options.source_image
            else None
        )
        source_image_min_mtime: float | None = None
        if source_image_path is not None:
            if path_is_relative_to(source_image_path, self.docs_dir):
                source_image_min_mtime = time.time()
            else:
                try:
                    source_image_path.unlink()
                except FileNotFoundError:
                    pass
        if options.graphics_context:
            self._ensure_runner_owned_field_server(
                reason=f"graphics-context capture for {page_path.name} -> {screenshot_path}"
            )
        command = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=screenshot_path,
            options=options,
            source_image_path=source_image_path,
            source_image_min_mtime=source_image_min_mtime,
        )
        LOG.info("Field docs runner executing %s for %s", page_path.name, screenshot_path)
        completed: subprocess.CompletedProcess[str] | None = None
        for attempt in range(1, RUNNER_RETRIES + 1):
            try:
                completed = subprocess.run(
                    command,
                    cwd=self.repo_root,
                    input=code,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    capture_output=True,
                    check=False,
                    timeout=runner_subprocess_timeout(options=options, is_video=False),
                )
            except subprocess.TimeoutExpired as exc:
                if attempt < RUNNER_RETRIES:
                    LOG.warning(
                        "Field docs runner attempt %s/%s timed out for %s -> %s; retrying\n%s",
                        attempt,
                        RUNNER_RETRIES,
                        page_path.name,
                        screenshot_path,
                        format_timeout_failure_details(
                            exc,
                            command=command,
                            field_log_path=self.shared_log_path,
                        ),
                    )
                    self._restart_shared_field_server(
                        reason=f"timeout while capturing {page_path.name} -> {screenshot_path}"
                    )
                    continue
                self._restart_shared_field_server(
                    reason=f"final timeout while capturing {page_path.name} -> {screenshot_path}"
                )
                raise RuntimeError(
                    f"Field docs runner timed out for {page_path.name} -> {screenshot_path}.\n"
                    f"Command: {shlex.join(command)}\n"
                    f"Timed out after {exc.timeout:.1f}s"
                ) from exc
            if completed.returncode == 0:
                break
            if attempt < RUNNER_RETRIES:
                LOG.warning(
                    "Field docs runner attempt %s/%s failed for %s -> %s; retrying\n%s",
                    attempt,
                    RUNNER_RETRIES,
                    page_path.name,
                    screenshot_path,
                    format_completed_failure_details(
                        completed,
                        command=command,
                        field_log_path=self.shared_log_path,
                    ),
                )
                self._restart_shared_field_server(
                    reason=f"failed capture attempt {attempt}/{RUNNER_RETRIES} for {page_path.name} -> {screenshot_path}"
                )
            else:
                self._restart_shared_field_server(
                    reason=f"final failed capture attempt for {page_path.name} -> {screenshot_path}"
                )
        assert completed is not None
        if completed.returncode != 0:
            details = format_completed_failure_details(
                completed,
                command=command,
                field_log_path=self.shared_log_path,
            )
            raise RuntimeError(
                f"Field docs runner failed for {page_path.name} -> {screenshot_path}.\n"
                f"{details}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{execution_error}"
            )
        output_gap = extract_backdoor_output_gap(stdout=completed.stdout, code=code, options=options)
        if output_gap is not None:
            field_log = tail_text(self.shared_log_path, max_lines=80)
            if field_log:
                output_gap = f"{output_gap}\n\nField server log tail:\n{field_log}"
            raise RuntimeError(
                f"Field docs runner output validation failed for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{output_gap}"
            )
        if options.source_image:
            copy_source_image(
                source_path=resolve_auxiliary_output_path(page_path, options.source_image),
                destination_path=screenshot_path,
                timeout_sec=options.timeout,
                min_mtime=source_image_min_mtime,
            )
        trim_native_graphics_screenshot(screenshot_path=screenshot_path, options=options)
        visual_gap = validate_screenshot_pixels(screenshot_path=screenshot_path, options=options)
        if visual_gap is not None:
            raise RuntimeError(
                f"Field docs runner visual validation failed for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{visual_gap}"
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.screenshots_run += 1
        self.save()
        if options.graphics_context:
            self._restart_shared_field_server(
                reason=f"completed graphics-context capture for {page_path.name} -> {screenshot_path}"
            )


STATE: RunnerState | None = None


def on_page_markdown(markdown: str, page: Any, config: Any, files: Any) -> str:
    state = ensure_state(config)
    abs_src_path = getattr(page.file, "abs_src_path", None)
    if abs_src_path:
        page_path = Path(abs_src_path).resolve()
    else:
        page_path = (Path(config["docs_dir"]) / page.file.src_path).resolve()
    return process_markdown(markdown=markdown, page_path=page_path, state=state, files=files)


def on_post_build(config: Any) -> None:
    del config
    if STATE is not None:
        if STATE.failures:
            LOG.warning("Field docs runner skipped %s asset(s) due to capture failures", len(STATE.failures))
            for failure in STATE.failures:
                LOG.warning("Field docs runner skipped asset detail: %s", failure)
        STATE.save()
        STATE.close()


def ensure_state(config: Any) -> RunnerState:
    global STATE
    if STATE is None:
        STATE = RunnerState(config)
    return STATE


def process_markdown(*, markdown: str, page_path: Path, state: RunnerState, files: Any) -> str:
    lines = markdown.splitlines(keepends=True)
    out: list[str] = []
    block_index = 0
    passthrough_fence: str | None = None
    previous_code_by_language: dict[str, str] = {}
    i = 0
    while i < len(lines):
        line = lines[i]
        if passthrough_fence is not None:
            out.append(line)
            if re.match(rf"^[ ]{{0,3}}{re.escape(passthrough_fence)}\s*(?:\r?\n)?$", line):
                passthrough_fence = None
            i += 1
            continue

        open_match = FENCE_OPEN_RE.match(line)
        if open_match is None:
            out.append(line)
            generic_match = GENERIC_FENCE_OPEN_RE.match(line)
            if generic_match is not None:
                passthrough_fence = generic_match.group("fence")
            i += 1
            continue

        start_line = i + 1
        block_index += 1
        open_line = line
        fence_name = open_match.group("runner")
        i += 1
        body: list[str] = []
        closing_line: str | None = None
        closing_re = re.compile(rf"^[ ]{{0,3}}{re.escape(open_match.group('fence'))}\s*(?:\r?\n)?$")
        while i < len(lines):
            candidate = lines[i]
            if closing_re.match(candidate):
                closing_line = candidate
                i += 1
                break
            body.append(candidate)
            i += 1
        if closing_line is None:
            raise RuntimeError(f"Unclosed {fence_name} fence in {page_path}:{start_line}")

        language_key = fence_language_key(open_line=open_line, fence_name=fence_name)
        previous_code = previous_code_by_language.get(language_key)
        rewritten_body = maybe_inherit_before(
            lines=body,
            fence_name=fence_name,
            previous_code=previous_code,
        )

        if fence_name in RUNNER_FENCES:
            options = parse_runner_options(
                runner_name=fence_name,
                open_line=open_line,
                page_path=page_path,
                line_number=start_line,
                block_index=block_index,
            )
            code = extract_runnable_code(
                lines=rewritten_body,
                fence_name=fence_name,
                page_path=page_path,
                line_number=start_line,
                options=options,
            )
            code = wrap_runnable_code_for_language(code=code, language_key=language_key)
            options = maybe_enable_output_wait(options=options, code=code)
            runner_code = code_with_macro_digest(
                code=code,
                options=options,
                repo_root=state.repo_root,
                page_path=page_path,
            )
            screenshot_succeeded = False
            video_succeeded = False
            if options.screenshot:
                screenshot_path = resolve_output_path(page_path, options.screenshot)
                if state.runner_mode == "never":
                    screenshot_succeeded = screenshot_path.exists()
                    if screenshot_succeeded:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner mode=never: %s screenshot asset %s",
                        "using existing" if screenshot_succeeded else "skipping missing",
                        screenshot_path,
                    )
                else:
                    try:
                        state.ensure_screenshot(code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        screenshot_succeeded = True
                    except RuntimeError as exc:
                        if not state.continue_on_error:
                            raise
                        state.handle_capture_error(page_path=page_path, output_path=screenshot_path, error=exc)
                if screenshot_succeeded:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=screenshot_path)
            if options.video:
                video_path = resolve_output_path(page_path, options.video)
                if state.runner_mode == "never":
                    video_succeeded = video_path.exists()
                    if video_succeeded:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner mode=never: %s video asset %s",
                        "using existing" if video_succeeded else "skipping missing",
                        video_path,
                    )
                else:
                    try:
                        state.ensure_video(code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        video_succeeded = True
                    except RuntimeError as exc:
                        if not state.continue_on_error:
                            raise
                        state.handle_capture_error(page_path=page_path, output_path=video_path, error=exc)
                if video_succeeded:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=video_path)

        hide_source = fence_name in RUNNER_FENCES and options.hidden
        if not hide_source:
            out.append(open_line.replace(fence_name, RUNNER_TARGETS[fence_name], 1))
            out.extend(rewritten_body)
            out.append(closing_line)
        if fence_name in RUNNER_FENCES and options.embed:
            if options.screenshot and screenshot_succeeded:
                out.append("\n")
                out.append(f"![{options.alt or options.box_name}]({markdown_path_for(page_path, options.screenshot)})\n")
            if options.video and video_succeeded:
                video_rel = embedded_asset_path_for(
                    page_path=page_path,
                    asset=options.video,
                    docs_dir=state.docs_dir,
                    use_directory_urls=bool(state.config["use_directory_urls"]),
                )
                out.append("\n")
                out.append(f'<video controls autoplay loop muted playsinline style="max-width:100%">\n')
                out.append(f'  <source src="{video_rel}" type="video/mp4">\n')
                out.append(f'</video>\n')

        if not hide_source:
            previous_code_by_language[language_key] = extract_effective_code(
                lines=rewritten_body,
                fence_name=fence_name,
                page_path=page_path,
                line_number=start_line,
            )
        continue

    return "".join(out)


def parse_runner_options(*, runner_name: str, open_line: str, page_path: Path, line_number: int, block_index: int) -> RunnerOptions:
    raw_attrs = open_line.split(runner_name, 1)[1].strip()
    attrs = parse_attrs(raw_attrs)
    screenshot = first_attr(attrs, "screenshot", "path")
    source_image = first_attr(attrs, "source-image", "source_image", "native-image", "native_image")
    video = first_attr(attrs, "video")
    if not screenshot and not video:
        raise RuntimeError(f"{page_path}:{line_number} {runner_name} requires screenshot=\"...\" and/or video=\"...\"")

    capture = (first_attr(attrs, "capture", "mode") or "box").strip().lower()
    if capture not in {"box", "window", "window-editor", "editor", "native-window"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported capture={capture!r}")

    execution = (first_attr(attrs, "execution", "execute", "run-mode") or "run").strip().lower()
    if execution not in {"run", "launch"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported execution={execution!r}")

    with_editor = capture == "window-editor" or parse_bool(first_attr(attrs, "with-editor", "with_editor"), default=False)
    open_outputs = parse_bool(first_attr(attrs, "open-outputs", "open_outputs"), default=True)
    wait_output = parse_bool(first_attr(attrs, "wait-output", "wait_output", "waitForOutput", "wait-for-output"), default=False)
    wait_output_kind = (first_attr(attrs, "wait-output-kind", "wait_output_kind") or "display").strip().lower()
    if wait_output_kind not in {"inline", "display", "all"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported wait-output-kind={wait_output_kind!r}")

    if capture == "window-editor":
        default_box_w = 1200.0
        default_box_h = 720.0
    elif capture == "window":
        if open_outputs:
            default_box_w = 960.0
            default_box_h = 180.0
        else:
            default_box_w = 420.0
            default_box_h = 120.0
    elif capture == "editor":
        default_box_w = 820.0
        default_box_h = 720.0
    else:
        # Keep box captures compact by default; output-aware clipping handles content sizing.
        if open_outputs:
            default_box_w = 960.0
            default_box_h = 180.0
        else:
            default_box_w = 420.0
            default_box_h = 120.0

    return RunnerOptions(
        screenshot=screenshot,
        source_image=source_image,
        capture=capture,
        execution=execution,
        wait=parse_float(first_attr(attrs, "wait"), default=0.75),
        padding=parse_float(first_attr(attrs, "padding"), default=16.0),
        timeout=parse_float(first_attr(attrs, "timeout"), default=45.0),
        property_name=first_attr(attrs, "property") or "code",
        embed=parse_bool(first_attr(attrs, "embed"), default=False),
        alt=first_attr(attrs, "alt"),
        document=first_attr(attrs, "document"),
        source_box=first_attr(attrs, "source-box", "source_box"),
        source_box_properties=parse_box_list(first_attr(attrs, "source-box-properties", "source_box_properties", "source-properties", "source_properties")),
        source_box_wait=parse_float(first_attr(attrs, "source-box-wait", "source_box_wait"), default=0.0),
        run_box=first_attr(attrs, "run-box", "run_box", "box-uid", "box_uid", "uid"),
        capture_box=first_attr(attrs, "capture-box", "capture_box", "target-box", "target_box", "target-uid", "target_uid"),
        setup_boxes=parse_box_list(first_attr(attrs, "setup-boxes", "setup_boxes", "setup")),
        macro_file=first_attr(attrs, "macro-file", "macro_file", "transcript-file", "transcript_file", "transcript"),
        macro_name=first_attr(attrs, "macro-name", "macro_name", "transcript-name", "transcript_name"),
        macro_selection=parse_box_list(first_attr(attrs, "macro-selection", "macro_selection", "selection")),
        fabricate_scene=parse_fabricate_scene(
            first_attr(attrs, "fabricate-scene", "fabricate_scene", "fabricateScene"),
            source_image=source_image,
            page_path=page_path,
            line_number=line_number,
        ),
        native_wait_frames=int(parse_float(first_attr(attrs, "native-wait-frames", "native_wait_frames"), default=30.0)),
        trim_native=parse_bool(first_attr(attrs, "trim-native", "trim_native", "native-trim", "native_trim", "auto-trim", "auto_trim"), default=False),
        box_name=first_attr(attrs, "name") or f"{page_path.stem}-{block_index}",
        box_x=parse_float(first_attr(attrs, "x"), default=24.0),
        box_y=parse_float(first_attr(attrs, "y"), default=24.0),
        box_w=parse_float(first_attr(attrs, "w"), default=default_box_w),
        box_h=parse_float(first_attr(attrs, "h"), default=default_box_h),
        open_outputs=open_outputs,
        wait_output=wait_output,
        wait_output_kind=wait_output_kind,
        wait_output_channel=first_attr(attrs, "wait-output-channel", "wait_output_channel"),
        wait_output_contains=first_attr(attrs, "wait-output-contains", "wait_output_contains", "output-contains", "contains"),
        with_editor=with_editor,
        graphics_context=parse_bool(
            first_attr(attrs, "graphics-context", "graphics_context", "graphicsContext"),
            default=False,
        ),
        force=parse_bool(first_attr(attrs, "force"), default=False),
        video=video,
        duration=parse_float(first_attr(attrs, "duration"), default=5.0),
        fps=int(parse_float(first_attr(attrs, "fps"), default=30.0)),
        hidden=parse_bool(first_attr(attrs, "hidden", "hide-source", "hide_source"), default=False),
        language_key=fence_language_key(open_line=open_line, fence_name=runner_name),
    )


def parse_attrs(raw: str) -> dict[str, str]:
    flattened = raw.replace("{", " ").replace("}", " ")
    attrs: dict[str, str] = {}
    for token in shlex.split(flattened):
        if token.startswith(".") or token.startswith("#"):
            continue
        if "=" in token:
            key, value = token.split("=", 1)
            attrs[key.strip()] = value.strip()
        elif token:
            attrs[token.strip()] = "true"
    return attrs


def first_attr(attrs: dict[str, str], *names: str) -> str | None:
    for name in names:
        if name in attrs:
            return attrs[name]
    return None


def parse_bool(raw: str | None, *, default: bool) -> bool:
    if raw is None:
        return default
    lowered = raw.strip().lower()
    if lowered in TRUE_VALUES:
        return True
    if lowered in FALSE_VALUES:
        return False
    raise RuntimeError(f"Unsupported boolean value {raw!r}")


def parse_float(raw: str | None, *, default: float) -> float:
    if raw is None:
        return default
    return float(raw)


def parse_box_list(raw: str | None) -> tuple[str, ...]:
    if raw is None:
        return ()
    return tuple(part for part in re.split(r"[\s,]+", raw.strip()) if part)


def resolve_runner_mode(raw: str | None) -> str:
    if raw is None or not raw.strip():
        return "incremental"
    lowered = raw.strip().lower()
    resolved = RUNNER_MODE_ALIASES.get(lowered)
    if resolved is None:
        supported = ", ".join(sorted(RUNNER_MODE_ALIASES))
        raise RuntimeError(f"Unsupported {RUNNER_MODE_ENV}={raw!r}; expected one of: {supported}")
    return resolved


def resolve_continue_on_error(raw: str | None, *, runner_mode: str) -> bool:
    if raw is None or not raw.strip():
        return runner_mode == "incremental"
    return parse_bool(raw, default=runner_mode == "incremental")


def fence_language_key(*, open_line: str, fence_name: str) -> str:
    attrs = parse_attrs(open_line.split(fence_name, 1)[1].strip())
    return first_attr(attrs, "data-lang", "data-language", "lang", "language") or "__default__"


def wrap_runnable_code_for_language(*, code: str, language_key: str) -> str:
    language = language_key.strip().lower()
    if language == "nano":
        source_literal = json.dumps(code)
        wrapped = f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
// FIELD_DOCS_EXPECT_SVG: false
import afield.nano.nanoRunReportHtml

val __nanoSource = {source_literal}

display(
    "nano",
    nanoRunReportHtml(
        source = __nanoSource,
        title = "Nano state",
        includeSource = false,
        includeDisassembly = true,
        includeProgramBytes = true,
        rngSeed = 1
    )
)
""".strip()
        return wrapped + "\n"

    if language == "forth":
        source_literal = json.dumps(code)
        expects_svg = forth_source_likely_produces_svg(code)
        wrapped = f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
// FIELD_DOCS_EXPECT_SVG: {"true" if expects_svg else "false"}
import afield.docs.renderForthDocsSnippet

val __forthSource = {source_literal}
renderForthDocsSnippet(__forthSource)
""".strip()
        return wrapped + "\n"

    return code


def forth_source_likely_produces_svg(source: str) -> bool:
    lowered = source.lower()
    direct_tokens = (
        " fline",
        "\nfline",
        " moveto",
        " moveto3",
        " lineto",
        " lineto3",
        " cubicto",
        " cubicto3",
        "fline-",
        's"fline"',
        " moveto ",
        " lineto ",
        " cubicto ",
    )
    return any(token in lowered for token in direct_tokens)


def parse_fabricate_scene(
    raw_scene: str | None,
    *,
    source_image: str | None,
    page_path: Path,
    line_number: int,
) -> str | None:
    if raw_scene is None or not raw_scene.strip():
        return None
    scene = raw_scene.strip()
    if scene not in FABRICATE_DOCS_SCENES:
        supported = ", ".join(sorted(FABRICATE_DOCS_SCENES))
        raise RuntimeError(f"{page_path}:{line_number} unsupported fabricate-scene={scene!r}; expected one of: {supported}")
    if not source_image:
        raise RuntimeError(f"{page_path}:{line_number} fabricate-scene requires source-image=\"...\"")
    return scene


def fabricate_docs_harness_code(*, scene: str, output_path: Path) -> str:
    method_name, ready_message = FABRICATE_DOCS_SCENES[scene]
    output_literal = json.dumps(str(output_path))
    ready_literal = json.dumps(ready_message)
    return f"""
import afield.four.Renderer
import afield.four.fabricated.StandardRendererDocsHarness
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()

val captureScene: suspend () -> Unit = {{
    StandardRendererDocsHarness.{method_name}({output_literal}, renderer = renderer)
    println({ready_literal})
}}
start(captureScene)
""".strip() + "\n"


def extract_runnable_code(*, lines: list[str], fence_name: str, page_path: Path, line_number: int, options: RunnerOptions) -> str:
    if options.fabricate_scene:
        assert options.source_image is not None
        return fabricate_docs_harness_code(
            scene=options.fabricate_scene,
            output_path=resolve_auxiliary_output_path(page_path, options.source_image),
        )
    if fence_name == "diff_runner":
        code = extract_diff_after_code(lines=lines, page_path=page_path, line_number=line_number)
    else:
        code = extract_effective_code(lines=lines, fence_name=fence_name, page_path=page_path, line_number=line_number)
    if not code.strip():
        raise RuntimeError(f"{page_path}:{line_number} {fence_name} fence did not contain runnable code")
    return code + "\n"


def extract_effective_code(*, lines: list[str], fence_name: str, page_path: Path, line_number: int) -> str:
    if fence_name in DIFF_FENCES:
        return extract_diff_result_code(lines=lines)
    return extract_static_code(lines=lines)


def extract_static_code(*, lines: list[str]) -> str:
    code_lines = [line.rstrip("\r\n") for line in lines]
    filtered = [line for line in code_lines if line.strip() not in MARKER_PREFIXES]
    return normalize_code_lines(filtered)


def extract_diff_result_code(*, lines: list[str]) -> str:
    before_lines, after_lines, _has_before, has_after = split_diff_sections(lines)
    selected = after_lines if has_after else before_lines
    return normalize_code_lines([line.rstrip("\r\n") for line in selected])


def extract_diff_after_code(*, lines: list[str], page_path: Path, line_number: int) -> str:
    code_lines = [line.rstrip("\r\n") for line in lines]
    mode = "before"
    saw_after = False
    after_lines: list[str] = []
    for line in code_lines:
        stripped = line.strip()
        if stripped == ":::before":
            mode = "before"
            continue
        if stripped == ":::after":
            mode = "after"
            saw_after = True
            continue
        if stripped in {":::static", ":::"}:
            continue
        if mode == "after":
            after_lines.append(line)

    while after_lines and not after_lines[0].strip():
        after_lines.pop(0)
    while after_lines and not after_lines[-1].strip():
        after_lines.pop()

    code = "\n".join(after_lines).rstrip()
    if not saw_after or not code.strip():
        raise RuntimeError(f"{page_path}:{line_number} diff-style fences require a non-empty :::after section")
    return code


def maybe_inherit_before(*, lines: list[str], fence_name: str, previous_code: str | None) -> list[str]:
    if fence_name not in DIFF_FENCES or previous_code is None:
        return lines

    before_lines, after_lines, has_before, has_after = split_diff_sections(lines)
    if has_before or not has_after:
        return lines
    if any(line.strip() for line in before_lines):
        return lines

    return [":::before\n", *code_to_lines(previous_code), ":::after\n", *after_lines]


def split_diff_sections(lines: list[str]) -> tuple[list[str], list[str], bool, bool]:
    before_lines: list[str] = []
    after_lines: list[str] = []
    has_before = False
    has_after = False
    mode = "before"
    for line in lines:
        stripped = line.strip()
        if stripped == ":::before":
            has_before = True
            mode = "before"
            continue
        if stripped == ":::after":
            has_after = True
            mode = "after"
            continue
        if stripped in {":::static", ":::"}:
            continue
        if mode == "after":
            after_lines.append(line)
        else:
            before_lines.append(line)
    return before_lines, after_lines, has_before, has_after


def code_to_lines(code: str) -> list[str]:
    if not code:
        return []
    return [f"{line}\n" for line in code.split("\n")]


def normalize_code_lines(lines: list[str]) -> str:
    trimmed = list(lines)
    while trimmed and not trimmed[0].strip():
        trimmed.pop(0)
    while trimmed and not trimmed[-1].strip():
        trimmed.pop()
    return "\n".join(trimmed).rstrip()


def resolve_output_path(page_path: Path, screenshot: str) -> Path:
    path = Path(screenshot)
    if path.is_absolute():
        return path
    return (page_path.parent / path).resolve()


def resolve_auxiliary_output_path(page_path: Path, path_value: str) -> Path:
    path = Path(path_value)
    if path.is_absolute():
        return path
    return (page_path.parent / path).resolve()


def markdown_path_for(page_path: Path, screenshot: str) -> str:
    absolute = resolve_output_path(page_path, screenshot)
    return Path(os.path.relpath(absolute, start=page_path.parent)).as_posix()


def embedded_asset_path_for(*, page_path: Path, asset: str, docs_dir: Path, use_directory_urls: bool) -> str:
    absolute = resolve_output_path(page_path, asset)
    try:
        page_relative = page_path.relative_to(docs_dir)
        asset_relative = absolute.relative_to(docs_dir)
    except ValueError:
        return markdown_path_for(page_path, asset)

    if use_directory_urls:
        if page_relative.name == "index.md":
            rendered_dir = page_relative.parent
        else:
            rendered_dir = page_relative.with_suffix("")
    else:
        rendered_dir = page_relative.with_suffix(".html").parent

    return Path(os.path.relpath(asset_relative, start=rendered_dir)).as_posix()


def register_generated_file(*, files: Any, docs_dir: Path, site_dir: Path, use_directory_urls: bool, output_path: Path) -> None:
    try:
        relative = output_path.relative_to(docs_dir).as_posix()
    except ValueError:
        return
    if files.get_file_from_path(relative) is not None:
        return
    files.append(File(relative, str(docs_dir), str(site_dir), use_directory_urls))


def path_is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def compute_runner_source_digest(repo_root: Path) -> str:
    hasher = hashlib.sha256()
    for relative in RUNNER_DIGEST_SOURCE_FILES:
        path = (repo_root / relative).resolve()
        hasher.update(relative.encode("utf-8"))
        hasher.update(b"\0")
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            hasher.update(b"<missing>")
            continue
        hasher.update(hashlib.sha256(data).digest())
    return hasher.hexdigest()


def options_digest_payload(options: RunnerOptions) -> dict[str, Any]:
    payload = {
        "sourceImage": options.source_image,
        "capture": options.capture,
        "effectiveCapture": effective_capture_mode(options),
        "execution": options.execution,
        "wait": options.wait,
        "padding": options.padding,
        "timeout": options.timeout,
        "property": options.property_name,
        "document": options.document,
        "sourceBox": options.source_box,
        "sourceBoxProperties": list(options.source_box_properties),
        "sourceBoxWait": options.source_box_wait,
        "runBox": options.run_box,
        "captureBox": options.capture_box,
        "setupBoxes": list(options.setup_boxes),
        "macroFile": options.macro_file,
        "macroName": options.macro_name,
        "macroSelection": list(options.macro_selection),
        "fabricateScene": options.fabricate_scene,
        "nativeWaitFrames": options.native_wait_frames,
        "trimNative": options.trim_native,
        "boxName": options.box_name,
        "boxX": options.box_x,
        "boxY": options.box_y,
        "boxW": options.box_w,
        "boxH": options.box_h,
        "openOutputs": options.open_outputs,
        "withEditor": options.with_editor,
        "graphicsContext": options.graphics_context,
        "video": options.video,
        "duration": options.duration,
        "fps": options.fps,
    }
    if options.wait_output:
        payload.update({
            "waitOutput": True,
            "waitOutputKind": options.wait_output_kind,
            "waitOutputChannel": options.wait_output_channel,
            "waitOutputContains": options.wait_output_contains,
        })
    return payload


def digest_string(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def digest_json(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode("utf-8")).hexdigest()


def compute_cache_metadata(
    *,
    code: str,
    options: RunnerOptions,
    page_path: Path,
    block_index: int,
    runner_source_digest: str,
) -> dict[str, Any]:
    options_payload = options_digest_payload(options)
    return {
        "page": str(page_path),
        "block": block_index,
        "scriptVersion": SCRIPT_VERSION,
        "runnerSourceDigest": runner_source_digest,
        "codeDigest": digest_string(code),
        "optionsDigest": digest_json(options_payload),
        "options": options_payload,
    }


def compute_digest(
    *,
    code: str,
    options: RunnerOptions,
    page_path: Path,
    block_index: int,
    runner_mode: str,
    runner_source_digest: str,
) -> str:
    payload = {
        "page": str(page_path),
        "block": block_index,
        "scriptVersion": SCRIPT_VERSION,
        "runnerSourceDigest": runner_source_digest,
        "code": code,
        "options": options_digest_payload(options),
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()


def short_digest(value: str | None) -> str:
    if not value:
        return "missing"
    return value[:12]


def copy_source_image(
    *,
    source_path: Path,
    destination_path: Path,
    timeout_sec: float,
    min_mtime: float | None = None,
) -> None:
    deadline = time.monotonic() + max(timeout_sec, 1.0)
    last_size = -1
    stable_reads = 0
    while time.monotonic() < deadline:
        if source_path.exists():
            stat = source_path.stat()
            size = stat.st_size
            if size > 0:
                if min_mtime is not None and stat.st_mtime < min_mtime:
                    time.sleep(0.1)
                    continue
                if size == last_size:
                    stable_reads += 1
                else:
                    stable_reads = 0
                last_size = size
                if stable_reads >= 1:
                    destination_path.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source_path, destination_path)
                    return
        time.sleep(0.1)
    raise RuntimeError(f"Timed out waiting for source image {source_path}")


def tail_text(path: Path, *, max_lines: int) -> str:
    try:
        text = path.read_text(errors="replace")
    except FileNotFoundError:
        return ""
    lines = text.splitlines()
    if not lines:
        return ""
    return "\n".join(lines[-max_lines:])


def tail_inline_text(text: str, *, max_lines: int) -> str:
    lines = text.strip().splitlines()
    if not lines:
        return ""
    return "\n".join(lines[-max_lines:])


def process_output_text(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def format_completed_failure_details(
    completed: subprocess.CompletedProcess[str],
    *,
    command: list[str],
    field_log_path: Path | None,
    max_lines: int = 80,
) -> str:
    parts = [
        f"Return code: {completed.returncode}",
        f"Command: {shlex.join(command)}",
    ]
    stdout = tail_inline_text(process_output_text(completed.stdout), max_lines=max_lines)
    stderr = tail_inline_text(process_output_text(completed.stderr), max_lines=max_lines)
    if stdout:
        parts.append(f"Backdoor stdout tail:\n{stdout}")
    if stderr:
        parts.append(f"Backdoor stderr tail:\n{stderr}")
    field_log = tail_text(field_log_path, max_lines=max_lines) if field_log_path is not None else ""
    if field_log:
        parts.append(f"Field server log tail:\n{field_log}")
    return "\n\n".join(parts)


def format_timeout_failure_details(
    exc: subprocess.TimeoutExpired,
    *,
    command: list[str],
    field_log_path: Path | None,
    max_lines: int = 80,
) -> str:
    parts = [
        f"Timed out after {exc.timeout:.1f}s",
        f"Command: {shlex.join(command)}",
    ]
    stdout = tail_inline_text(process_output_text(exc.stdout), max_lines=max_lines)
    stderr = tail_inline_text(process_output_text(exc.stderr), max_lines=max_lines)
    if stdout:
        parts.append(f"Backdoor stdout tail before timeout:\n{stdout}")
    if stderr:
        parts.append(f"Backdoor stderr tail before timeout:\n{stderr}")
    field_log = tail_text(field_log_path, max_lines=max_lines) if field_log_path is not None else ""
    if field_log:
        parts.append(f"Field server log tail:\n{field_log}")
    return "\n\n".join(parts)


def cache_status_label(*, cache_allowed: bool, force: bool) -> str:
    if not cache_allowed:
        return "bypassed"
    if force:
        return "forced"
    return "miss"


def format_cache_reason(
    *,
    output_path: Path,
    output_exists: bool,
    cached_digest: str | None,
    computed_digest: str,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
    cache_allowed: bool,
    force: bool,
    runner_mode: str,
) -> str:
    reasons: list[str] = []
    if not cache_allowed:
        reasons.append(f"runner mode {runner_mode}")
    if force:
        reasons.append("force=true")
    if not output_exists:
        reasons.append("output file missing")
    if cached_digest is None:
        reasons.append("no cache entry")
    elif cached_digest != computed_digest:
        reasons.extend(compare_cache_metadata(cached_metadata=cached_metadata, current_metadata=current_metadata))
        reasons.append(f"digest {short_digest(cached_digest)} -> {short_digest(computed_digest)}")
    if not reasons:
        reasons.append("cache digest changed")
    return f"{output_path} ({', '.join(dict.fromkeys(reasons))})"


def compare_cache_metadata(
    *,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
) -> list[str]:
    if not isinstance(cached_metadata, dict):
        return ["digest changed", "previous cache metadata unavailable"]

    reasons: list[str] = []
    if cached_metadata.get("scriptVersion") != current_metadata.get("scriptVersion"):
        reasons.append("script version changed")
    if cached_metadata.get("runnerSourceDigest") != current_metadata.get("runnerSourceDigest"):
        reasons.append("runner source changed")
    if cached_metadata.get("codeDigest") != current_metadata.get("codeDigest"):
        reasons.append("runnable code changed")
    if cached_metadata.get("optionsDigest") != current_metadata.get("optionsDigest"):
        reasons.append("capture options changed")
    if cached_metadata.get("page") != current_metadata.get("page"):
        reasons.append("page path changed")
    if cached_metadata.get("block") != current_metadata.get("block"):
        reasons.append("block index changed")
    if not reasons:
        reasons.append("digest changed")
    return reasons


def reuse_existing_asset_for_incremental(
    *,
    runner_mode: str,
    output_path: Path,
    options: RunnerOptions,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
) -> bool:
    if runner_mode != "incremental" or options.force or not output_path.exists():
        return False
    if not isinstance(cached_metadata, dict):
        return False
    stable_keys = ("page", "block", "scriptVersion", "codeDigest", "optionsDigest")
    if any(cached_metadata.get(key) != current_metadata.get(key) for key in stable_keys):
        return False
    return cached_metadata.get("runnerSourceDigest") != current_metadata.get("runnerSourceDigest")


def parse_backdoor_results(stdout: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    idx = 0
    size = len(stdout)
    results: list[dict[str, Any]] = []
    while idx < size:
        while idx < size and stdout[idx].isspace():
            idx += 1
        if idx >= size:
            break
        try:
            payload, next_idx = decoder.raw_decode(stdout, idx)
        except json.JSONDecodeError:
            next_newline = stdout.find("\n", idx)
            if next_newline < 0:
                break
            idx = next_newline + 1
            continue
        if isinstance(payload, dict) and "command" in payload and "ok" in payload:
            results.append(payload)
        idx = next_idx
    return results


def extract_backdoor_execution_error(stdout: str) -> str | None:
    results = parse_backdoor_results(stdout)
    if not results:
        return None

    shader_clear_properties = {
        "vertex",
        "fragment",
        "compute",
        "task",
        "mesh",
        "raygen",
        "miss",
        "closesthit",
        "anyhit",
        "intersection",
        "callable",
        "vertex_fragment",
        "vertex_s",
        "fragment_s",
        "compute_s",
        "task_s",
        "mesh_s",
        "raygen_s",
        "miss_s",
        "closesthit_s",
        "anyhit_s",
        "intersection_s",
        "callable_s",
    }
    problems: list[str] = []
    for result in results:
        command = str(result.get("command", "<unknown command>"))
        data = result.get("data")
        best_effort_timeout = (
            isinstance(data, dict)
            and command.startswith("stop ")
            and "--best-effort" in command
            and bool(data.get("bestEffort"))
        )
        if result.get("timedOut") and not best_effort_timeout:
            problems.append(f"{command}: timed out")
        if not bool(result.get("ok", False)):
            error_text = None
            if isinstance(data, dict):
                raw_error = data.get("error")
                if raw_error is not None:
                    error_text = str(raw_error)
            problems.append(f"{command}: {error_text or 'command failed'}")

        events = result.get("events")
        if not isinstance(events, list):
            continue
        for event in events:
            if not isinstance(event, dict):
                continue
            name = event.get("name") or event.get("messageName")
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            if name in {"output", "peer-output"}:
                if payload.get("kind") not in {"error", "err"}:
                    continue
                line = payload.get("line")
                line_suffix = f" line {line}" if line is not None else ""
                html = str(payload.get("html", "")).replace("\n", " ").strip()
                property_name = str(payload.get("property", ""))
                if not html and (property_name in shader_clear_properties or not property_name):
                    continue
                problems.append(f"{command}{line_suffix}: {html or 'execution error'}")
                continue
            if name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
                text = str(payload.get("text", "")).replace("\n", " ").strip()
                if text:
                    problems.append(f"{command}: {text}")

    if not problems:
        return None

    deduped = list(dict.fromkeys(problems))
    return "\n".join(deduped)


def extract_backdoor_output_gap(*, stdout: str, code: str, options: RunnerOptions) -> str | None:
    if options.graphics_context:
        return None

    results = parse_backdoor_results(stdout)
    if not results:
        return None

    events: list[dict[str, Any]] = []
    for result in results:
        result_events = result.get("events")
        if isinstance(result_events, list):
            events.extend(e for e in result_events if isinstance(e, dict))

    if not events:
        return None

    set_output_events: list[dict[str, Any]] = []
    for event in events:
        name = event.get("name") or event.get("messageName")
        if name != "set-output":
            continue
        payload = event.get("payload")
        if isinstance(payload, dict):
            set_output_events.append(payload)

    has_output_area = bool(set_output_events)
    has_svg_output = any("<svg" in str(payload.get("value", "")).lower() for payload in set_output_events)

    expects_svg = runner_code_expects_svg(code)
    expects_output_area = runner_code_expects_output_area(code)

    if expects_output_area and not has_output_area:
        return (
            "No output-area events were emitted (missing set-output messages). "
            "Likely causes: code did not call display/lines, output proxy failed, or execution stopped before rendering."
        )

    if expects_svg and not has_svg_output:
        channels = sorted({str(payload.get("channel", "")) for payload in set_output_events if payload.get("channel") is not None})
        channel_text = f" Channels seen: {', '.join(channels)}." if channels else ""
        return "Code appears to call lines(...) but no SVG output was observed in set-output payloads." + channel_text

    return None


def maybe_enable_output_wait(*, options: RunnerOptions, code: str) -> RunnerOptions:
    if options.graphics_context or options.wait_output:
        return options
    if not runner_code_expects_output_area(code):
        return options
    return replace(options, wait_output=True, wait_output_kind="display")


def runner_code_expects_svg(code: str) -> bool:
    expects_svg_override = parse_codegen_expectation(code=code, key="FIELD_DOCS_EXPECT_SVG")
    if expects_svg_override is not None:
        return expects_svg_override
    return re.search(r"\blines\s*\([\s\S]*?\bchannel\s*=", code) is not None


def runner_code_expects_output_area(code: str) -> bool:
    expects_output_area_override = parse_codegen_expectation(code=code, key="FIELD_DOCS_EXPECT_OUTPUT_AREA")
    if expects_output_area_override is not None:
        return expects_output_area_override
    expects_display = re.search(r"\bdisplay\s*\(", code) is not None
    return runner_code_expects_svg(code) or expects_display


def validate_screenshot_pixels(*, screenshot_path: Path, options: RunnerOptions) -> str | None:
    if not options.graphics_context or options.capture != "native-window" or options.source_image:
        return None
    try:
        from PIL import Image
    except Exception:
        LOG.warning("Field docs runner skipped native screenshot pixel validation; Pillow is unavailable")
        return None
    try:
        with Image.open(screenshot_path) as image:
            extrema = image.convert("RGB").getextrema()
    except Exception as exc:
        return f"Could not inspect screenshot pixels: {exc}"
    if all(high <= 1 for _low, high in extrema):
        return (
            "Native graphics screenshot is all black. This usually means the capture ran before "
            "the shader reflection/code execution finished, or the graphics example did not draw."
        )
    return None


def trim_native_graphics_screenshot(*, screenshot_path: Path, options: RunnerOptions) -> None:
    if not options.trim_native or not options.graphics_context or effective_capture_mode(options) != "native-window" or options.source_image:
        return
    try:
        from PIL import Image, ImageChops, ImageStat
    except Exception:
        LOG.warning("Field docs runner skipped native screenshot trim; Pillow is unavailable")
        return

    try:
        with Image.open(screenshot_path) as source:
            image = source.copy()
    except Exception as exc:
        LOG.warning("Field docs runner could not open native screenshot for trimming %s: %s", screenshot_path, exc)
        return

    if image.width <= 4 or image.height <= 4:
        return

    rgb = image.convert("RGB")
    sample = max(1, min(32, image.width // 12, image.height // 12))
    corner_boxes = (
        (0, 0, sample, sample),
        (image.width - sample, 0, image.width, sample),
        (0, image.height - sample, sample, image.height),
        (image.width - sample, image.height - sample, image.width, image.height),
    )
    corner_medians: list[tuple[int, int, int]] = []
    for box in corner_boxes:
        stat = ImageStat.Stat(rgb.crop(box))
        median = stat.median
        corner_medians.append((int(median[0]), int(median[1]), int(median[2])))

    def median_channel(index: int) -> int:
        values = sorted(sample[index] for sample in corner_medians)
        return values[len(values) // 2]

    background = (median_channel(0), median_channel(1), median_channel(2))
    diff = ImageChops.difference(rgb, Image.new("RGB", rgb.size, background))
    red, green, blue = diff.split()
    max_diff = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    tolerance = 6
    mask = max_diff.point(lambda value: 255 if value > tolerance else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return

    left, top, right, bottom = bbox
    margin = max(16, min(96, int(round(max(image.width, image.height) * 0.015))))
    left = max(0, left - margin)
    top = max(0, top - margin)
    right = min(image.width, right + margin)
    bottom = min(image.height, bottom + margin)
    crop_width = right - left
    crop_height = bottom - top
    if crop_width <= 4 or crop_height <= 4:
        return

    original_area = image.width * image.height
    crop_area = crop_width * crop_height
    if crop_area >= original_area * 0.96:
        return

    try:
        image.crop((left, top, right, bottom)).save(screenshot_path)
    except Exception as exc:
        LOG.warning("Field docs runner failed to trim native screenshot %s: %s", screenshot_path, exc)
        return
    LOG.info(
        "Field docs runner trimmed native screenshot: %s (%dx%d -> %dx%d)",
        screenshot_path,
        image.width,
        image.height,
        crop_width,
        crop_height,
    )

    LOG.info(
        "Field docs runner trimmed native graphics screenshot %s: %sx%s -> %sx%s",
        screenshot_path,
        image.width,
        image.height,
        crop_width,
        crop_height,
    )


def parse_codegen_expectation(*, code: str, key: str) -> bool | None:
    match = re.search(rf"{re.escape(key)}:\s*(true|false)", code, re.IGNORECASE)
    if match is None:
        return None
    return match.group(1).lower() == "true"


def effective_capture_mode(options: RunnerOptions) -> str:
    if options.capture == "window" and options.open_outputs and not options.with_editor:
        return "box"
    return options.capture


SOURCE_BOX_PROPERTIES: tuple[tuple[str, str], ...] = (
    ("preamble", "kt"),
    ("code", "kt"),
    ("vertex", "glsl"),
    ("fragment", "glsl"),
    ("compute", "glsl"),
    ("vertex_fragment", "slang"),
    ("vertex_s", "slang"),
    ("fragment_s", "slang"),
    ("compute_s", "slang"),
)


def source_box_set_commands(
    *,
    repo_root: Path,
    source_box: str,
    target_uid: str,
    source_box_properties: tuple[str, ...] = (),
) -> list[str]:
    source_ref = Path(source_box)
    if not source_ref.is_absolute():
        source_ref = repo_root / "public" / "docs" / source_ref
    source_dir = source_ref.parent
    source_uid = source_ref.name
    allowed = set(source_box_properties) if source_box_properties else None
    commands: list[str] = []
    for property_name, extension in SOURCE_BOX_PROPERTIES:
        if allowed is not None and property_name not in allowed:
            continue
        path = source_dir / f"{source_uid}_{property_name}_.{extension}"
        if path.exists():
            commands.append(shlex.join([
                "set",
                target_uid,
                property_name,
                "--file", str(path),
            ]))
    return commands


def resolve_macro_file_path(*, repo_root: Path, page_path: Path, macro_file: str) -> Path:
    raw = Path(macro_file)
    if raw.is_absolute():
        return raw
    page_relative = (page_path.parent / raw).resolve()
    if page_relative.exists():
        return page_relative
    return (repo_root / raw).resolve()


def macro_name_for_options(*, options: RunnerOptions, page_path: Path, block_index: int) -> str:
    if options.macro_name:
        return options.macro_name
    if options.macro_file:
        return Path(options.macro_file).stem
    return f"{page_path.stem}-{block_index}"


def safe_transcript_id(text: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]+", "_", text.strip())
    cleaned = re.sub(r"_+", "_", cleaned).strip("_")
    if not cleaned:
        cleaned = "box"
    if cleaned[0].isdigit():
        cleaned = f"box_{cleaned}"
    return cleaned[:80]


def docs_runner_target_uid(*, page_path: Path, block_index: int, options: RunnerOptions) -> str:
    if options.run_box:
        return options.run_box
    return safe_transcript_id(f"docs_{page_path.stem}_{block_index}_{options.box_name}")


def docs_runner_fieldml_root_alias(*, target_uid: str) -> str:
    return safe_transcript_id(f"{target_uid}_fieldml_root")


def graphics_renderer_transcript_path(repo_root: Path) -> Path:
    return repo_root / "dev_docs" / "agents" / "transcripts" / "graphics_renderer_launch.yaml"


def generated_graphics_child_transcript(*, target_uid: str, options: RunnerOptions) -> str:
    transcript: dict[str, Any] = {
        "name": f"{target_uid}_setup",
        "description": "Generated by public/docs_hooks.py for a native graphics docs capture.",
        "operations": [
            {
                "create": {
                    "box": target_uid,
                    "name": options.box_name,
                    "frame": {
                        "x": options.box_x,
                        "y": options.box_y,
                        "w": options.box_w,
                        "h": options.box_h,
                    },
                }
            },
            {
                "connect": {
                    "parent": "agent_graphics_context",
                    "child": target_uid,
                }
            },
        ],
    }
    return json.dumps(transcript, separators=(",", ":"))


def code_with_macro_digest(*, code: str, options: RunnerOptions, repo_root: Path, page_path: Path) -> str:
    digest_lines: list[str] = []
    if options.graphics_context:
        renderer_path = graphics_renderer_transcript_path(repo_root)
        try:
            renderer_digest = hashlib.sha256(renderer_path.read_bytes()).hexdigest()
        except FileNotFoundError:
            renderer_digest = "<missing>"
        digest_lines.extend([
            f"// FIELD_DOCS_RENDERER_MACRO_FILE: {renderer_path.relative_to(repo_root)}",
            f"// FIELD_DOCS_RENDERER_MACRO_DIGEST: {renderer_digest}",
        ])
    if options.macro_file:
        macro_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
        try:
            macro_bytes = macro_path.read_bytes()
        except FileNotFoundError:
            macro_digest = "<missing>"
        else:
            macro_digest = hashlib.sha256(macro_bytes).hexdigest()
        digest_lines.extend([
            f"// FIELD_DOCS_MACRO_FILE: {options.macro_file}",
            f"// FIELD_DOCS_MACRO_DIGEST: {macro_digest}",
        ])
    if not digest_lines:
        return code
    return "\n".join([code, *digest_lines])


def runner_subprocess_timeout(*, options: RunnerOptions, is_video: bool) -> float:
    base = max(options.timeout, options.duration + 30.0) if is_video else options.timeout
    budget = base + RUNNER_SUBPROCESS_GRACE_SEC
    if not PLAYWRIGHT_READY_MARKER.exists():
        budget = max(budget, PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC + RUNNER_SUBPROCESS_GRACE_SEC)
    return budget


def build_backdoor_command(
    *,
    repo_root: Path,
    page_path: Path,
    block_index: int,
    screenshot_path: Path,
    options: RunnerOptions,
    is_video: bool = False,
    source_image_path: Path | None = None,
    source_image_min_mtime: float | None = None,
) -> list[str]:
    port = int(os.environ.get(RUNNER_PORT_ENV, "9092"))
    capture_mode = effective_capture_mode(options)
    command = [
        sys.executable,
        str((repo_root / "tools" / "field_backdoor.py").resolve()),
        "--json",
        "--with-playwright",
        "--port",
        str(port),
        "--timeout",
        str(options.timeout),
    ]
    if options.document:
        command.extend(["--document", options.document])
    backdoor_commands: list[str] = []
    generated_target_uid = docs_runner_target_uid(page_path=page_path, block_index=block_index, options=options)
    default_target_uid = options.capture_box or options.run_box or (generated_target_uid if options.graphics_context else "@last")
    language = options.language_key.strip().lower()
    if options.graphics_context:
        renderer_macro_path = graphics_renderer_transcript_path(repo_root)
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            "graphics_renderer_launch",
            "--file", str(renderer_macro_path),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro",
            "run",
            "graphics_renderer_launch",
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "wait-native-renderer",
            "agent_graphics_context",
            "--frames", "3",
            "--timeout", str(options.timeout),
        ]))
    if options.macro_file:
        macro_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
        macro_name = macro_name_for_options(options=options, page_path=page_path, block_index=block_index)
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            macro_name,
            "--file", str(macro_path),
            "--timeout", str(options.timeout),
        ]))
        macro_run_parts = [
            "macro",
            "run",
            macro_name,
            "--timeout", str(options.timeout),
        ]
        for selection in options.macro_selection:
            macro_run_parts.extend(["--selection", selection])
        backdoor_commands.append(shlex.join(macro_run_parts))
    elif options.run_box:
        if options.graphics_context:
            connect_transcript = {
                "name": f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "description": "Generated by public/docs_hooks.py to connect an existing box to the docs renderer.",
                "operations": [
                    {"connect": {"parent": "agent_graphics_context", "child": options.run_box}},
                ],
            }
            backdoor_commands.append(shlex.join([
                "macro",
                "write",
                f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "--content", json.dumps(connect_transcript, separators=(",", ":")),
                "--timeout", str(options.timeout),
            ]))
            backdoor_commands.append(shlex.join([
                "macro",
                "run",
                f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "--timeout", str(options.timeout),
            ]))
        for setup_box in options.setup_boxes:
            backdoor_commands.append(shlex.join([
                "run",
                setup_box,
                "--timeout", str(options.timeout),
            ]))
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            options.run_box,
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    elif options.graphics_context:
        setup_macro_name = f"{generated_target_uid}_setup"
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            setup_macro_name,
            "--content", generated_graphics_child_transcript(target_uid=generated_target_uid, options=options),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro",
            "run",
            setup_macro_name,
            "--timeout", str(options.timeout),
        ]))
    elif language == "fieldml":
        root_alias = docs_runner_fieldml_root_alias(target_uid=generated_target_uid)
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", f"{options.box_name}-root",
            "--alias", root_alias,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", "120",
            "--h", "80",
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "set",
            root_alias,
            "mainLanguage",
            "fieldml",
        ]))
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y + 120.0),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "connect",
            root_alias,
            "@last",
            "--timeout", str(options.timeout),
        ]))
    else:
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]))
    if options.source_box and not options.macro_file:
        backdoor_commands.extend(source_box_set_commands(
            repo_root=repo_root,
            source_box=options.source_box,
            target_uid=options.run_box or (generated_target_uid if options.graphics_context else "@last"),
            source_box_properties=options.source_box_properties,
        ))
        if options.source_box_wait > 0:
            backdoor_commands.append(shlex.join(["sleep", str(options.source_box_wait)]))
    if options.macro_file:
        pass
    elif options.run_box is None and capture_mode == "editor":
        backdoor_commands.append(shlex.join([
            "set",
            "@last",
            options.property_name,
            "--file", "-",
        ]))
        backdoor_commands.append(shlex.join([
            "editor-open",
            "@last",
            "--property", options.property_name,
            "--padding", str(options.padding),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            "@last",
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    elif options.run_box is None and (
        options.source_box is None
        or (options.source_box_properties and "code" not in options.source_box_properties)
    ):
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            generated_target_uid if options.graphics_context else "@last",
            "--property", options.property_name,
            "--file", "-",
            "--timeout", str(options.timeout),
        ]))
    elif options.run_box is None:
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            generated_target_uid if options.graphics_context else "@last",
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    if options.wait > 0 and source_image_path is None:
        backdoor_commands.append(shlex.join(["sleep", str(options.wait)]))
    if options.wait_output:
        wait_output_parts = [
            "wait-output",
            default_target_uid,
            "--kind", options.wait_output_kind,
            "--timeout", str(options.timeout),
        ]
        if options.wait_output_channel:
            wait_output_parts.extend(["--channel", options.wait_output_channel])
        if options.wait_output_contains:
            wait_output_parts.extend(["--contains", options.wait_output_contains])
        backdoor_commands.append(shlex.join(wait_output_parts))
    if source_image_path is not None:
        wait_file_parts = [
            "wait-file",
            str(source_image_path),
            "--timeout",
            str(options.timeout),
            "--fail-on-error-uid",
            default_target_uid,
        ]
        if source_image_min_mtime is not None:
            wait_file_parts.extend(["--newer-than", str(source_image_min_mtime)])
        backdoor_commands.append(shlex.join(wait_file_parts))

    if is_video:
        # Use record-* commands instead of screenshot-*
        if capture_mode == "box":
            target_uid = default_target_uid
            parts = [
                "record-box",
                target_uid,
                str(screenshot_path),
                "--padding", str(options.padding),
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--timeout", str(options.timeout),
            ]
            backdoor_commands.append(shlex.join(parts))
        elif capture_mode == "editor":
            target_uid = default_target_uid
            parts = [
                "record-editor",
                target_uid,
                str(screenshot_path),
                "--property", options.property_name,
                "--padding", str(options.padding),
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            backdoor_commands.append(shlex.join(parts))
        else:
            parts = [
                "record-window",
                str(screenshot_path),
                "--uid", default_target_uid,
                "--property", options.property_name,
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            if options.with_editor:
                parts.append("--with-editor")
            backdoor_commands.append(shlex.join(parts))
    else:
        if options.source_image:
            pass
        elif capture_mode == "native-window":
            backdoor_commands.append(shlex.join([
                "native-screenshot",
                str(screenshot_path),
                "--wait-frames", str(options.native_wait_frames),
                "--timeout", str(options.timeout),
            ]))
        elif capture_mode == "box":
            target_uid = default_target_uid
            parts = [
                "screenshot-box",
                target_uid,
                str(screenshot_path),
                "--padding", str(options.padding),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            backdoor_commands.append(shlex.join(parts))
        elif capture_mode == "editor":
            target_uid = default_target_uid
            parts = [
                "screenshot-editor",
                target_uid,
                str(screenshot_path),
                "--property", options.property_name,
                "--padding", str(options.padding),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            backdoor_commands.append(shlex.join(parts))
        else:
            target_uid = default_target_uid
            parts = [
                "screenshot-window",
                str(screenshot_path),
                "--uid", target_uid,
                "--property", options.property_name,
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            if options.with_editor:
                parts.append("--with-editor")
            backdoor_commands.append(shlex.join(parts))

    if options.execution == "launch" and not options.macro_file:
        backdoor_commands.append(shlex.join([
            "stop",
            default_target_uid,
            "--timeout", str(options.timeout),
        ]))
    for command_line in backdoor_commands:
        command.extend(["--command", command_line])
    return command
