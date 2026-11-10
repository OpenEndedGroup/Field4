"""A stand-in for ``DaVinciResolveScript.scriptapp("Resolve")``, for tests only.

It has the method names the real API has and returns the same shapes (marker dicts keyed by float
frame offsets, as fusionscript returns them). ``FIELDRESOLVE_FAKE=1`` makes :func:`fieldresolve.connect`
return :func:`scriptapp` instead of connecting, which is how ``tools/test_fieldresolve.py`` and the
Kotlin ``ResolveTest`` run the worker without Resolve. Nothing published is ever made from it.

The fixture: project "Fake Project" with two timelines. "Edit 1" (current) is 24 fps from
01:00:00:00, 2400 frames long, with a 24-frame Blue marker at frame 0 and a Red one at frame 48, and
one clip, A001.mov on V1 at record 86500..86600 starting 1000 frames into its source, carrying a
Green marker at source frame 1010 and a Cream one at source frame 5 (trimmed away). Its playhead is
at 01:00:01:00. "Edit DF" is 29.97 drop-frame from 01:00:00;00 with a Mint marker at frame 1800. Asked for with a
host, the project is called "Fake Project on <host>" instead.
"""

from __future__ import annotations


class Item:
    def __init__(self, name, start, end, left, markers):
        self.name, self.start, self.end, self.left, self.markers = name, start, end, left, markers

    def GetName(self): return self.name
    def GetStart(self, subframe=False): return float(self.start)
    def GetEnd(self, subframe=False): return float(self.end)
    def GetLeftOffset(self, subframe=False): return float(self.left)
    def GetMarkers(self): return self.markers


class Timeline:
    def __init__(self, name, uid, start, end, start_tc, fps="24", drop="0", markers=None, items=None, tc="01:00:00:00"):
        self.name, self.uid, self.start, self.end, self.start_tc = name, uid, start, end, start_tc
        self.settings = {"timelineFrameRate": fps, "timelineDropFrameTimecode": drop}
        self.markers = markers or {}
        self.items = items or {}
        self.tc = tc

    def GetName(self): return self.name
    def GetUniqueId(self): return self.uid
    def GetStartFrame(self): return self.start
    def GetEndFrame(self): return self.end
    def GetStartTimecode(self): return self.start_tc
    def GetSetting(self, key): return self.settings[key]
    def GetMarkers(self): return self.markers
    def GetCurrentTimecode(self): return self.tc
    def SetCurrentTimecode(self, timecode):
        self.tc = timecode
        return True
    def GetTrackCount(self, kind): return len(self.items.get(kind, []))
    def GetItemListInTrack(self, kind, i): return self.items[kind][i - 1]


class Project:
    def __init__(self, timelines, current, name="Fake Project"):
        self.timelines, self.current, self.name = timelines, current, name

    def GetName(self): return self.name
    def GetTimelineCount(self): return len(self.timelines)
    def GetTimelineByIndex(self, i): return self.timelines[i - 1] if 1 <= i <= len(self.timelines) else None
    def GetCurrentTimeline(self): return self.current


class App:
    def __init__(self, project):
        self.project = project

    def GetProductName(self): return "DaVinci Resolve Studio"
    def GetVersionString(self): return "21.1.0.0"
    def GetCurrentPage(self): return "edit"
    def GetProjectManager(self): return self
    def GetCurrentProject(self): return self.project


def _m(color, name, note="", duration=1, custom=""):
    return {"color": color, "duration": duration, "note": note, "name": name, "customData": custom}


def scriptapp(name: str = "Resolve", host: str | None = None) -> App:
    edit = Timeline("Edit 1", "a", 86400, 86400 + 2400, "01:00:00:00", tc="01:00:01:00", markers={
        48.0: _m("Red", "Marker 2", "cut here"),
        0.0: _m("Blue", "Marker 1", duration=24, custom="x"),
    }, items={"video": [[Item("A001.mov", 86500, 86600, 1000, {
        1010.0: _m("Green", "c", "clip"),
        5.0: _m("Cream", "t", "trimmed"),
    })]]})
    df = Timeline("Edit DF", "b", 107892, 107892 + 3600, "01:00:00;00", fps="29.97", drop="1", markers={1800.0: _m("Mint", "m")})
    return App(Project([edit, df], current=edit, name=f"Fake Project on {host}" if host else "Fake Project"))
