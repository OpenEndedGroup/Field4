"""field.motion.v1: JSON manifest/events, length-framed binary float32 pose chunks.

Integers framing each record/header are big endian; pose floats are little endian.
The skeleton hash uses null-terminated UTF-8 strings and IEEE float64 LE numbers.
All stored poses are already in common RH Y-up metres; source metadata documents conversion.
"""
import hashlib
import json
import math
import os
from pathlib import Path
import struct

MAX_BYTES = 16 * 1024 * 1024


def finite(values):
    if not all(isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x) for x in values):
        raise ValueError("Non-finite or non-numeric motion value")


def skeleton_hash(s):
    h = hashlib.sha256()

    def string(value):
        h.update(value.encode("utf-8") + b"\0")

    def number(value):
        h.update(struct.pack("<d", value))

    string(s["id"])
    string(s["version"])
    for name in s["names"]:
        string(name)
    for key in ("parents", "restTranslations", "restRotations"):
        for value in s[key]:
            number(value)
    c = s["coordinates"]
    for value in c["sourceToField"]:
        number(value)
    number(c["metresPerUnit"])
    for key in ("sourceUnits", "sourceHandedness", "sourceUp", "sourceForward"):
        string(c[key])
    for body in s["bodies"]:
        string(body["name"])
        string(body["joint"])
        number(body.get("massKg") if body.get("massKg") is not None else -1)
    return h.hexdigest()


def validate_skeleton(s):
    strings = [s["id"], s["version"]] + s["names"] + [s["coordinates"][k] for k in ("sourceUnits", "sourceHandedness", "sourceUp", "sourceForward")]
    strings += [value for body in s["bodies"] for value in (body["name"], body["joint"])]
    if any(not isinstance(value, str) or not value or chr(0) in value for value in strings):
        raise ValueError("Invalid skeleton string")
    n = len(s["names"])
    if not (1 <= n <= 1024) or len(set(s["names"])) != n or not all(s["names"]):
        raise ValueError("Invalid joint names")
    if len(s["parents"]) != n or s["parents"].count(-1) != 1:
        raise ValueError("Skeleton needs one root")
    for i, p in enumerate(s["parents"]):
        if type(p) is not int or not (-1 <= p < i):
            raise ValueError("Parents must precede children")
    if len(s["restTranslations"]) != n * 3:
        raise ValueError("Rest translation count")
    finite(s["restTranslations"])
    validate_quaternions(s["restRotations"], n)
    c = s["coordinates"]
    b = c["sourceToField"]
    finite(b + [c["metresPerUnit"]])
    if len(b) != 9 or c["metresPerUnit"] <= 0 or c["common"] != "RH_Y_UP_METRES_XYZW":
        raise ValueError("Invalid coordinate contract")
    for i in range(3):
        for j in range(3):
            if abs(sum(b[k * 3 + i] * b[k * 3 + j] for k in range(3)) - (i == j)) > 1e-5:
                raise ValueError("Coordinate basis must be orthogonal")
    body_names = [b["name"] for b in s["bodies"]]
    if len(set(body_names)) != len(body_names) or any(b["joint"] not in s["names"] for b in s["bodies"]):
        raise ValueError("Invalid body mapping")
    for b in s["bodies"]:
        if b.get("massKg") is not None and (not math.isfinite(b["massKg"]) or b["massKg"] <= 0):
            raise ValueError("Invalid body mass")
    if skeleton_hash(s) != s["hash"]:
        raise ValueError("Skeleton hash mismatch")


def validate_quaternions(values, n):
    finite(values)
    if len(values) != n * 4 or any(abs(sum(v * v for v in values[i:i + 4]) - 1) >= .002 for i in range(0, len(values), 4)):
        raise ValueError("Quaternions must be normalized xyzw")


def validate_frame(f, n):
    finite([f["time"]])
    if f["time"] < 0 or any(type(f[k]) is not int or f[k] < 0 for k in ("index", "episode", "revision")):
        raise ValueError("Invalid frame clock")
    for p in (f["executed"], f.get("intended")):
        if p is None:
            continue
        if len(p["root"]) != 3 or len(p["worldPositions"]) != 3 * n:
            raise ValueError("Pose shape mismatch")
        finite(p["root"] + p["worldPositions"])
        if p["localRotations"]:
            validate_quaternions(p["localRotations"], n)
        elif p is f["executed"]:
            raise ValueError("Executed pose requires orientations")
    finite(f["contacts"])
    if len(f["contacts"]) > 1024 or any(not 0 <= c <= 1 for c in f["contacts"]):
        raise ValueError("Invalid contacts")
    if f["contactProvenance"] not in ("unavailable", "ardy.predicted", "simulator.measured", "fixture"):
        raise ValueError("Invalid contact provenance")
    finite(f["diagnostics"].values())


def check_order(previous, frame):
    if previous and (frame["time"] < previous["time"] or frame["index"] <= previous["index"] or frame["episode"] < previous["episode"]):
        raise ValueError("Out-of-order motion frame")


def encode_frame(frame):
    validate_frame(frame, len(frame["executed"]["worldPositions"]) // 3)
    header = {k: v for k, v in frame.items() if k not in ("executed", "intended", "contacts")}
    header.update(hasIntended=frame.get("intended") is not None, contactCount=len(frame["contacts"]),
                  intendedHasRotations=bool(frame.get("intended") and frame["intended"]["localRotations"]))
    data = json.dumps(header, allow_nan=False, separators=(",", ":")).encode()
    values = []
    for pose in (frame["executed"], frame.get("intended")):
        if pose is not None:
            for key in ("root", "localRotations", "worldPositions"):
                values.extend(pose[key])
    values.extend(frame["contacts"])
    if any(abs(v) > 3.4028234663852886e38 for v in values):
        raise ValueError("Pose value exceeds float32 range")
    result = struct.pack(">I", len(data)) + data + struct.pack("<%df" % len(values), *values)
    if len(result) > MAX_BYTES:
        raise ValueError("Frame too large")
    return result


def decode_frame(data, n):
    if len(data) < 4 or len(data) > MAX_BYTES:
        raise ValueError("Invalid record size")
    length, = struct.unpack_from(">I", data)
    if not 2 <= length <= len(data) - 4:
        raise ValueError("Invalid header size")
    header = json.loads(data[4:4 + length])
    count = header.pop("contactCount")
    intended = header.pop("hasIntended")
    reference_rotations = header.pop("intendedHasRotations", True)
    if type(count) is not int or not 0 <= count <= 1024 or type(intended) is not bool:
        raise ValueError("Invalid frame layout")
    expected = 3 + n * 7 + (3 + n * (7 if reference_rotations else 3) if intended else 0) + count
    if len(data) - 4 - length != expected * 4:
        raise ValueError("Pose payload length mismatch")
    values = list(struct.unpack_from("<%df" % expected, data, 4 + length))
    offset = 0

    def take(size):
        nonlocal offset
        out = values[offset:offset + size]
        offset += size
        return out

    def pose(rotations=True):
        return dict(root=take(3), localRotations=take(n * 4 if rotations else 0), worldPositions=take(n * 3))

    header.update(executed=pose(), intended=pose(reference_rotations) if intended else None, contacts=take(count))
    validate_frame(header, n)
    return header


class RecordingWriter:
    def __init__(self, directory, skeleton, configuration, chunk_frames=256):
        validate_skeleton(skeleton)
        if not 1 <= chunk_frames <= 100000:
            raise ValueError("Invalid chunk size")
        self.directory = Path(directory)
        self.directory.mkdir()  # never replace an existing recording
        self.metadata = json.loads(json.dumps(configuration, allow_nan=False))
        self.metadata.update(schema=1, format="field.motion.v1", skeleton=skeleton)
        (self.directory / "incomplete.json").write_text(json.dumps(self.metadata, indent=2))
        self.events = (self.directory / "events.jsonl").open("w")
        self.chunk_frames = chunk_frames
        self.chunk = None
        self.count = 0
        self.chunks = []
        self.previous = None
        self.closed = False

    def event(self, event):
        if self.closed:
            raise ValueError("Recording closed")
        self.events.write(json.dumps(event, allow_nan=False, separators=(",", ":")) + "\n")
        self.events.flush()

    def append(self, frame):
        if self.closed:
            raise ValueError("Recording closed")
        validate_frame(frame, len(self.metadata["skeleton"]["names"]))
        check_order(self.previous, frame)
        data = encode_frame(frame)
        if self.count % self.chunk_frames == 0:
            if self.chunk:
                self.chunk.close()
            name = "chunk-%06d.bin" % len(self.chunks)
            self.chunks.append(name)
            self.chunk = (self.directory / name).open("wb")
        self.chunk.write(struct.pack(">I", len(data)))
        self.chunk.write(data)
        self.count += 1
        self.previous = dict(frame)

    def close(self, complete=True):
        if self.closed:
            return
        self.closed = True
        if self.chunk:
            self.chunk.close()
        self.events.close()
        if complete:
            self.metadata.update(frameCount=self.count, chunks=self.chunks)
            temp = self.directory / "manifest.tmp"
            temp.write_text(json.dumps(self.metadata, indent=2, allow_nan=False))
            os.replace(temp, self.directory / "manifest.json")
            (self.directory / "incomplete.json").unlink()

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        self.close(complete=kind is None)


class Recording:
    def __init__(self, directory):
        self.directory = Path(directory)
        path = self.directory / "manifest.json"
        if path.stat().st_size > 4 * 1024 * 1024:
            raise ValueError("Manifest too large")
        self.metadata = json.loads(path.read_text())
        if self.metadata["schema"] != 1 or self.metadata["format"] != "field.motion.v1":
            raise ValueError("Unsupported recording schema")
        self.skeleton = self.metadata["skeleton"]
        validate_skeleton(self.skeleton)
        chunks = self.metadata["chunks"]
        if len(chunks) > 100000 or chunks != ["chunk-%06d.bin" % i for i in range(len(chunks))]:
            raise ValueError("Invalid chunk names")

    def frames(self):
        previous = None
        count = 0
        for name in self.metadata["chunks"]:
            path = self.directory / name
            if path.is_symlink():
                raise ValueError("Chunk must not be a symlink")
            with path.open("rb") as stream:
                while True:
                    prefix = stream.read(4)
                    if not prefix:
                        break
                    if len(prefix) != 4:
                        raise ValueError("Truncated record prefix")
                    length, = struct.unpack(">I", prefix)
                    if not 4 <= length <= MAX_BYTES:
                        raise ValueError("Invalid frame size")
                    data = stream.read(length)
                    if len(data) != length:
                        raise ValueError("Truncated record")
                    frame = decode_frame(data, len(self.skeleton["names"]))
                    check_order(previous, frame)
                    previous = frame
                    count += 1
                    yield frame
        if count != self.metadata["frameCount"]:
            raise ValueError("Recording frame count mismatch")

    def events(self):
        with (self.directory / "events.jsonl").open() as stream:
            for line in stream:
                if len(line) > MAX_BYTES:
                    raise ValueError("Event too large")
                yield json.loads(line)
