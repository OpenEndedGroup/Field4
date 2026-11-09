"""Read timelines, markers and the playhead from a running DaVinci Resolve.

Resolve's scripting API (``DaVinciResolveScript``, a CPython extension that ships inside the
Resolve app) connects to the running application over a local socket. This module finds it,
connects, and turns what it returns into plain dicts with every frame number worked out: a
timeline marker comes back as an offset from the start of its timeline, and this module adds the
timeline's record frame, timecode and time in seconds beside it.

From a Python box::

    import fieldresolve as fr

    for m in fr.markers():                 # the current timeline
        print(m["frame"], m["timecode"], m["color"], m["name"], m["note"])

    fr.markers("Edit 2")                   # a timeline by name
    fr.timelines()                         # every timeline in the current project
    fr.playhead()                          # where the current timeline's playhead is

Kotlin boxes reach the same functions through ``afield.io.resolve.Resolve``, which runs
``python -m fieldresolve`` (``__main__.py``) as a worker process and talks to it in JSON lines.

Resolve has to be running with a project open, and external scripting has to be allowed:
Preferences > System > General > External scripting using > Local. :func:`connect` raises
:class:`ResolveUnavailable` with that advice when it cannot connect.

The drawings made with the viewer's annotation tool are not part of the scripting API, so a
marker's annotation here is its text: ``name``, ``note`` and the script-only ``customData``.
"""

from __future__ import annotations

import os
import sys

__all__ = [
    "ResolveUnavailable",
    "connect",
    "status",
    "timelines",
    "timeline",
    "markers",
    "clip_markers",
    "playhead",
    "frames_to_timecode",
    "timecode_to_frames",
]


class ResolveUnavailable(RuntimeError):
    """Resolve's scripting module is missing, or Resolve did not answer."""


def _default_paths() -> tuple[str, str]:
    """``(Modules directory, fusionscript library)`` for this platform's default install."""
    if sys.platform.startswith("darwin"):
        return ("/Library/Application Support/Blackmagic Design/DaVinci Resolve/Developer/Scripting/Modules",
                "/Applications/DaVinci Resolve/DaVinci Resolve.app/Contents/Libraries/Fusion/fusionscript.so")
    if sys.platform.startswith("win") or sys.platform.startswith("cygwin"):
        program_data = os.environ.get("PROGRAMDATA", r"C:\ProgramData")
        return (program_data + r"\Blackmagic Design\DaVinci Resolve\Support\Developer\Scripting\Modules",
                r"C:\Program Files\Blackmagic Design\DaVinci Resolve\fusionscript.dll")
    return ("/opt/resolve/Developer/Scripting/Modules", "/opt/resolve/libs/Fusion/fusionscript.so")


_apps: dict = {}
"""Connections by host ("" for this machine)."""

SCRIPTING_PORT = 1144
"""The port Resolve Studio's scripting listens on for connections from other machines."""


def _script_module():
    modules, lib = _default_paths()
    if os.environ.get("RESOLVE_SCRIPT_API"):
        modules = os.path.join(os.environ["RESOLVE_SCRIPT_API"], "Modules")
    # DaVinciResolveScript.py reads RESOLVE_SCRIPT_LIB when fusionscript is not importable.
    os.environ.setdefault("RESOLVE_SCRIPT_LIB", lib)
    if modules not in sys.path:
        sys.path.append(modules)
    try:
        import DaVinciResolveScript  # type: ignore[import-not-found]
    except ImportError as e:
        raise ResolveUnavailable(
            f"could not load DaVinci Resolve's scripting module ({e}); looked in {modules} and "
            f"{os.environ['RESOLVE_SCRIPT_LIB']}. Is Resolve installed? Set RESOLVE_SCRIPT_API and "
            "RESOLVE_SCRIPT_LIB for a non-standard install.") from e
    return DaVinciResolveScript


def _check_reachable(host: str, timeout: float = 5.0, port: int = SCRIPTING_PORT) -> None:
    """Fail fast when nothing answers on the scripting port: fusionscript itself waits ~150 s."""
    import socket
    try:
        socket.create_connection((host, port), timeout=timeout).close()
    except OSError as e:
        raise ResolveUnavailable(
            f"nothing answered on {host}:{port} ({e}). Is Resolve Studio running there, with "
            "Preferences > System > General > External scripting using > Network?") from e


def connect(host: str | None = None, fresh: bool = False):
    """The ``Resolve`` scripting object, connecting on first use (or again with ``fresh=True``).

    ``host`` names another machine (a host name or address) whose Resolve Studio allows network
    scripting; None is this machine.
    """
    key = host or ""
    app = _apps.get(key)
    if app is not None and not fresh:
        try:
            app.GetProductName()
            return app
        except Exception:
            _apps.pop(key, None)
    if os.environ.get("FIELDRESOLVE_FAKE") == "1":
        from . import _fake
        app = _fake.scriptapp("Resolve", host)
    else:
        if host:
            _check_reachable(host)
        module = _script_module()
        app = module.scriptapp("Resolve", host) if host else module.scriptapp("Resolve")
    if app is None:
        if host:
            raise ResolveUnavailable(
                f"DaVinci Resolve on {host} did not answer. Open a project there and allow network "
                "scripting: Preferences > System > General > External scripting using > Network.")
        raise ResolveUnavailable(
            "DaVinci Resolve did not answer. Start Resolve, open a project, and allow external "
            "scripting: Preferences > System > General > External scripting using > Local.")
    _apps[key] = app
    return app


def _project(resolve):
    project = resolve.GetProjectManager().GetCurrentProject()
    if project is None:
        raise ResolveUnavailable("Resolve is running but has no project open")
    return project


# ---------------------------------------------------------------------------------------------
# Timecode. Resolve counts real frames (GetStartFrame is 107892 for a 29.97 drop-frame timeline
# that starts at 01:00:00;00), so labels and frame counts convert with the SMPTE drop-frame rule.

def _nominal(fps: float) -> int:
    return int(round(fps))


def _drop(fps: float, drop_frame: bool) -> int:
    """Labels skipped at each minute (except every tenth): 2 at 29.97, 4 at 59.94, 0 otherwise."""
    if not drop_frame:
        return 0
    nominal = _nominal(fps)
    if nominal % 30 != 0 or abs(fps - nominal) < 1e-3:
        return 0
    return 2 * nominal // 30


def frames_to_timecode(frame: int, fps: float, drop_frame: bool = False) -> str:
    """The ``HH:MM:SS:FF`` label (``;`` before the frames when drop-frame) of a frame count."""
    nominal = _nominal(fps)
    drop = _drop(fps, drop_frame)
    sign = "-" if frame < 0 else ""
    frame = abs(int(frame))
    if drop:
        per_10_min = nominal * 600 - drop * 9
        per_min = nominal * 60 - drop
        tens, rem = divmod(frame, per_10_min)
        frame += drop * 9 * tens
        if rem > drop:
            frame += drop * ((rem - drop) // per_min)
    ff = frame % nominal
    ss = frame // nominal % 60
    mm = frame // (nominal * 60) % 60
    hh = frame // (nominal * 3600)
    return f"{sign}{hh:02d}:{mm:02d}:{ss:02d}{';' if drop else ':'}{ff:02d}"


def timecode_to_frames(timecode: str, fps: float, drop_frame: bool | None = None) -> int:
    """The frame count of a ``HH:MM:SS:FF`` label. A ``;`` or ``.`` separator implies drop-frame."""
    text = timecode.strip()
    if drop_frame is None:
        drop_frame = ";" in text or "." in text
    parts = [int(p) for p in text.replace(";", ":").replace(".", ":").split(":")]
    if len(parts) != 4:
        raise ValueError(f"not a timecode: {timecode!r}")
    hh, mm, ss, ff = parts
    nominal = _nominal(fps)
    drop = _drop(fps, drop_frame)
    total_minutes = hh * 60 + mm
    return ((hh * 3600 + mm * 60 + ss) * nominal + ff) - drop * (total_minutes - total_minutes // 10)


# ---------------------------------------------------------------------------------------------

def _rate(timeline) -> tuple[float, bool]:
    raw = str(timeline.GetSetting("timelineFrameRate")).strip()
    fps = float(raw.split()[0])
    drop = "DF" in raw.upper() or str(timeline.GetSetting("timelineDropFrameTimecode")).strip() in ("1", "true", "True")
    return fps, drop


def _marker(offset, info: dict, start: int, fps: float, drop: bool) -> dict:
    offset = int(offset)
    record = start + offset
    return {
        "frame": offset,
        "recordFrame": record,
        "timecode": frames_to_timecode(record, fps, drop),
        "seconds": offset / fps,
        "duration": int(info.get("duration", 1)),
        "color": info.get("color", ""),
        "name": info.get("name", ""),
        "note": info.get("note", ""),
        "customData": info.get("customData", ""),
    }


def _timeline_dict(timeline, index: int, current_name: str | None, with_markers: bool = True) -> dict:
    fps, drop = _rate(timeline)
    start = int(timeline.GetStartFrame())
    name = timeline.GetName()
    out = {
        "name": name,
        "index": index,
        "uniqueId": timeline.GetUniqueId(),
        "current": name == current_name,
        "startFrame": start,
        "endFrame": int(timeline.GetEndFrame()),
        "startTimecode": timeline.GetStartTimecode(),
        "fps": fps,
        "dropFrame": drop,
    }
    if with_markers:
        raw = timeline.GetMarkers() or {}
        out["markers"] = [_marker(k, raw[k], start, fps, drop) for k in sorted(raw, key=float)]
    return out


def _all_timelines(project):
    for i in range(1, int(project.GetTimelineCount()) + 1):
        t = project.GetTimelineByIndex(i)
        if t is not None:
            yield i, t


def _find(project, name: str | None):
    current = project.GetCurrentTimeline()
    if name is None:
        if current is None:
            raise ResolveUnavailable("the current project has no timeline open")
        for i, t in _all_timelines(project):
            if t.GetUniqueId() == current.GetUniqueId():
                return i, t, current.GetName()
        return 0, current, current.GetName()
    names = []
    for i, t in _all_timelines(project):
        names.append(t.GetName())
        if t.GetName() == name:
            return i, t, current.GetName() if current else None
    raise KeyError(f"no timeline named {name!r} in project {project.GetName()!r}; timelines: {names}")


def status(host: str | None = None) -> dict:
    """Product, version, page, project and current timeline of the running Resolve."""
    r = connect(host)
    project = r.GetProjectManager().GetCurrentProject()
    current = project.GetCurrentTimeline() if project else None
    return {
        "product": r.GetProductName(),
        "version": r.GetVersionString(),
        "page": r.GetCurrentPage(),
        "project": project.GetName() if project else None,
        "timelineCount": int(project.GetTimelineCount()) if project else 0,
        "timeline": current.GetName() if current else None,
        "host": host,
    }


def timelines(with_markers: bool = True, host: str | None = None) -> list[dict]:
    """Every timeline in the current project, in the Media Pool's order, each with its markers."""
    project = _project(connect(host))
    current = project.GetCurrentTimeline()
    current_name = current.GetName() if current else None
    return [_timeline_dict(t, i, current_name, with_markers) for i, t in _all_timelines(project)]


def timeline(name: str | None = None, host: str | None = None) -> dict:
    """One timeline (the current one when ``name`` is None) with its markers."""
    i, t, current_name = _find(_project(connect(host)), name)
    return _timeline_dict(t, i, current_name)


def markers(name: str | None = None, host: str | None = None) -> list[dict]:
    """The markers on a timeline (the current one when ``name`` is None), earliest first.

    ``frame`` counts from the first frame of the timeline; ``recordFrame`` and ``timecode`` are on
    the timeline's own clock (so a timeline that starts at 01:00:00:00 puts frame 0 at that
    timecode); ``seconds`` is ``frame / fps``.
    """
    return timeline(name, host)["markers"]


def clip_markers(name: str | None = None, host: str | None = None) -> list[dict]:
    """Markers on the clips of a timeline's video and audio tracks, placed on the timeline.

    A clip marker's own frame counts from the start of the clip's source media. Its place on the
    timeline is ``clip start + (source frame - left offset)``, where the left offset is how far into
    the source the clip's visible part begins; markers on trimmed-off frames are left out.
    """
    i, t, _ = _find(_project(connect(host)), name)
    fps, drop = _rate(t)
    start = int(t.GetStartFrame())
    out = []
    for kind in ("video", "audio"):
        for track in range(1, int(t.GetTrackCount(kind)) + 1):
            for item in t.GetItemListInTrack(kind, track) or []:
                raw = item.GetMarkers() or {}
                if not raw:
                    continue
                item_start, item_end = int(item.GetStart()), int(item.GetEnd())
                left = int(item.GetLeftOffset())
                for k in sorted(raw, key=float):
                    record = item_start + int(k) - left
                    if not item_start <= record < item_end:
                        continue
                    m = _marker(record - start, raw[k], start, fps, drop)
                    m.update({"clip": item.GetName(), "trackType": kind, "track": track, "clipFrame": int(k)})
                    out.append(m)
    out.sort(key=lambda m: (m["frame"], m["trackType"], m["track"]))
    return out


def playhead(name: str | None = None, host: str | None = None) -> dict:
    """The current timeline's playhead: timecode, record frame and frame from the timeline start.

    Resolve reports a playhead only for the current timeline, so ``name`` must be None or name it.
    """
    i, t, current_name = _find(_project(connect(host)), name)
    if t.GetName() != current_name:
        raise ValueError(f"Resolve only reports the playhead of the current timeline ({current_name!r}), not {name!r}")
    fps, drop = _rate(t)
    tc = t.GetCurrentTimecode()
    record = timecode_to_frames(tc, fps, drop)
    start = int(t.GetStartFrame())
    return {"timeline": t.GetName(), "timecode": tc, "recordFrame": record, "frame": record - start, "seconds": (record - start) / fps, "fps": fps}
