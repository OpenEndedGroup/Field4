"""Run a source through detectors, on the calling thread or on a worker of its own.

In Field, use :meth:`Pipeline.start`. The worker thread keeps running after the cell that started it
has returned, so the box stays responsive while frames are processed, and the cell can be re-run to
read ``pipeline.latest`` or change parameters. Stopping the box (or ``pipeline.stop()``) ends it.

The result for one frame is a dict merging every detector's output with ``frame`` (the
:class:`~fieldcv.sources.Frame`), ``index``, ``width``, ``height``, and ``timings`` (milliseconds per
detector).
"""

from __future__ import annotations

import threading
import time
import traceback
from typing import Callable, Iterable

from .detectors import Detector
from .sources import Source, open_source


class Pipeline:
    def __init__(self, source, *detectors: Detector, on_result: Callable[[dict], None] | None = None):
        self.source: Source = open_source(source)
        self.detectors: list[Detector] = list(detectors)
        self.on_result = on_result
        self.latest: dict | None = None
        self.error: str | None = None
        self.frames = 0
        self.fps = 0.0
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def process(self, frame) -> dict:
        out: dict = {}
        timings = {}
        for d in self.detectors:
            t0 = time.perf_counter()
            out.update(d(frame))
            timings[d.name] = (time.perf_counter() - t0) * 1000
        out.update(frame=frame, index=frame.index, width=frame.width, height=frame.height, timings=timings)
        return out

    def step(self) -> dict | None:
        """Process the next frame on this thread; ``None`` when the source is exhausted."""
        try:
            frame = next(self.source)
        except StopIteration:
            return None
        result = self.process(frame)
        self.latest = result
        self.frames += 1
        if self.on_result is not None:
            self.on_result(result)
        return result

    def __iter__(self) -> Iterable[dict]:
        while not self._stop.is_set():
            result = self.step()
            if result is None:
                return
            yield result

    # -- background running -----------------------------------------------------------------

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> "Pipeline":
        if self.running:
            return self
        self._stop.clear()
        self.error = None
        self._thread = threading.Thread(target=self._run, name="fieldcv-pipeline", daemon=True)
        self._thread.start()
        _register(self)
        return self

    def _run(self) -> None:
        t_last, n_last = time.perf_counter(), self.frames
        try:
            for _ in self:
                now = time.perf_counter()
                if now - t_last >= 1.0:
                    self.fps = (self.frames - n_last) / (now - t_last)
                    t_last, n_last = now, self.frames
        except Exception:
            self.error = traceback.format_exc()

    def stop(self, timeout: float = 2.0) -> None:
        self._stop.set()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(timeout)
        self._thread = None

    def close(self) -> None:
        self.stop()
        for d in self.detectors:
            d.close()
        self.source.close()

    def __repr__(self) -> str:
        state = "running" if self.running else ("failed" if self.error else "stopped")
        names = ", ".join(d.name for d in self.detectors)
        return f"<Pipeline [{names}] {state}, {self.frames} frames, {self.fps:.1f} fps>"


# Pipelines started from a Field box are closed when that box is stopped (see fieldcv.field).
_live: dict[str, list[Pipeline]] = {}


def _register(p: Pipeline) -> None:
    try:
        from .field import current_box_id
        key = current_box_id()
    except Exception:
        return
    if key is None:
        return
    previous = _live.setdefault(key, [])
    # Re-running a cell that starts a pipeline replaces the one it started last time.
    for old in previous:
        if old is not p:
            old.close()
    previous[:] = [p]


def close_for_box(box_id: str) -> int:
    pipelines = _live.pop(box_id, [])
    for p in pipelines:
        p.close()
    return len(pipelines)
