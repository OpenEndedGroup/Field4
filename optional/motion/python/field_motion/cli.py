"""Inspect a portable recording without importing any network or simulator."""
import argparse
import json
import time
from .recording import Recording, encode_frame


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording")
    parser.add_argument("--benchmark", action="store_true", help="Measure JSON vs binary encoding, not inference or transport latency")
    args = parser.parse_args()
    recording = Recording(args.recording)
    count = 0
    episodes = set()
    first = last = None
    json_bytes = binary_bytes = 0
    json_seconds = binary_seconds = 0.
    for frame in recording.frames():
        count += 1
        episodes.add(frame["episode"])
        if first is None:
            first = frame["time"]
        last = frame["time"]
        if args.benchmark:
            start = time.perf_counter()
            encoded = json.dumps(frame, separators=(",", ":"), allow_nan=False).encode()
            json_seconds += time.perf_counter() - start
            json_bytes += len(encoded)
            start = time.perf_counter()
            encoded = encode_frame(frame)
            binary_seconds += time.perf_counter() - start
            binary_bytes += len(encoded)
    result = dict(backend=recording.metadata.get("backend"), skeleton=recording.skeleton["id"],
                  joints=len(recording.skeleton["names"]), hash=recording.skeleton["hash"], frames=count,
                  episodes=sorted(episodes), start=first, end=last, events=sum(1 for _ in recording.events()))
    if args.benchmark:
        result["encodingOnly"] = dict(jsonBytes=json_bytes, binaryBytes=binary_bytes,
                                      jsonTotalMs=json_seconds * 1000, binaryTotalMs=binary_seconds * 1000,
                                      frames=count, transportMeasured=False, inferenceMeasured=False)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
