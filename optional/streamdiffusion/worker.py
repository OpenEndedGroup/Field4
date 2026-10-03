"""Owned local worker: small JSON control messages and SCM_RIGHTS GPU allocations only."""
import argparse
import array
from collections import deque
import json
import logging
import os
from pathlib import Path
import queue
import socket
import sys
import threading
import time

protocol_stdout = sys.stdout
sys.stdout = sys.stderr  # third-party prints belong in the worker log
output_lock = threading.Lock()


def emit(event, **fields):
    with output_lock:
        protocol_stdout.write(json.dumps(dict(event=event, **fields)) + "\n")
        protocol_stdout.flush()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--socket", required=True)
    args = p.parse_args()
    root = args.root.resolve()
    sys.path.insert(0, str(root))
    os.environ["STREAMDIFFUSIONV2_ROOT"] = str(root)
    os.environ.setdefault("OMP_NUM_THREADS", "8")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    # Keep sibling modules ahead of the optional external checkout's module names.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import torch
    from gpu_interop import SharedSlot, select_device, completed, release_unconsumed
    torch.set_grad_enabled(False)
    logging.basicConfig(level=logging.INFO)
    slots, remaining = [], set()
    commands = queue.Queue(maxsize=32)
    free_outputs = deque()
    condition = threading.Condition()
    stopped = threading.Event()
    pending_reset = None

    def receive_commands():
        nonlocal pending_reset
        try:
            for line in sys.stdin:
                if len(line) > 65536:
                    raise ValueError("Oversized control message")
                message = json.loads(line)
                if message["op"] == "output_free":
                    with condition:
                        free_outputs.append((message["slot"], message["value"]))
                        condition.notify_all()
                elif message["op"] == "reset":
                    # Keep the latest settings while a prompt is encoding.
                    # Accepted frame jobs remain chronological.
                    with condition:
                        pending_reset = message
                else:
                    commands.put(message)
        finally:
            stopped.set()
            with condition:
                condition.notify_all()

    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(args.socket)
            os.chmod(args.socket, 0o600)
            listener.listen(1)
            listener.settimeout(30)
            emit("listening")
            request = json.loads(sys.stdin.readline())
            if request["op"] != "open" or request["schema"] != 1:
                raise ValueError("Unsupported protocol handshake")
            width, height = request["width"], request["height"]
            if not (0 < width <= 4096 and 0 < height <= 4096):
                raise ValueError("Invalid frame dimensions")
            if request["format"] not in (37, 43, 44, 50):
                raise ValueError("Unsupported RGBA8/BGRA8 format")
            descriptors = request["slots"]
            count = len(descriptors) * 2
            if not (1 <= request["inputs"] < len(descriptors) <= 32):
                raise ValueError("Invalid GPU ring sizes")
            with listener.accept()[0] as peer:
                peer.settimeout(10)
                _, ancillary, flags, _ = peer.recvmsg(1, socket.CMSG_SPACE(count * array.array("i").itemsize))
                fds = array.array("i")
                for level, kind, data in ancillary:
                    if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                        fds.frombytes(data[:len(data) - len(data) % fds.itemsize])
                remaining.update(fds)
                if flags & socket.MSG_CTRUNC or len(fds) != count:
                    raise ValueError("Incomplete GPU descriptor handoff")
            os.unlink(args.socket)
        device = select_device(request["device_uuid"])
        for i, descriptor in enumerate(descriptors):
            slots.append(SharedSlot(descriptor, fds[i * 2], fds[i * 2 + 1], width, height, device, remaining))
        input_count = request["inputs"]
        inputs, outputs = slots[:input_count], slots[input_count:]
        free_outputs.extend((i, 0) for i in range(len(outputs)))
        settings = request["settings"]
        generation = request["generation"]
        mode = settings["backend"]
        backend = None
        if mode == "diffusion":
            from backend import DiffusionBackend
            emit("status", message="Loading Wan, T5 and TAEHV models")
            backend = DiffusionBackend(root, width, height, settings)
        elif mode not in ("identity", "invert"):
            raise ValueError("Unknown backend")
        def caps():
            return backend.capabilities() if backend else dict(backend=mode, taehv=False, tensorrt_active=False)
        emit("ready", device_uuid=request["device_uuid"], gpu_only_pixels=True, **caps())
        threading.Thread(target=receive_commands, daemon=True, name="diffusion-control").start()
        frames, ids = [], []
        first = True
        bgra = request["format"] in (44, 50)
        order = [2, 1, 0] if bgra else [0, 1, 2]

        def publish(rgb, tag):
            with condition:
                while not free_outputs and not stopped.is_set():
                    condition.wait(0.25)
                if stopped.is_set():
                    return
                output_index, released = free_outputs.popleft()
            if not 0 <= output_index < len(outputs):
                raise ValueError("Bad output release slot")
            output = outputs[output_index]
            if released != output.value + (1 if output.value else 0):
                raise ValueError("Bad output release timeline value")
            if released:
                output.wait(released)
            packed = (rgb.clamp(0, 1) * 255).round().to(torch.uint8)
            output.tensor[:, :, :3].copy_(packed[:, :, order])
            output.tensor[:, :, 3].fill_(255)
            ready_value = released + 1
            output.signal(ready_value)
            completed()  # ready notice guarantees Field cannot wait on unfinished inference
            emit("output", slot=output_index, value=ready_value, frame_id=tag[0], time_nanos=tag[1],
                generation=generation, **caps())

        while not stopped.is_set():
            with condition:
                reset = pending_reset
                pending_reset = None
            if reset is not None:
                generation = reset["generation"]
                settings = reset["settings"]
                if backend:
                    backend.reset(settings)
                frames, ids, first = [], [], True
                emit("reset", generation=generation, **caps())
            try:
                message = commands.get(timeout=0.25)
            except queue.Empty:
                continue
            if message["op"] != "frame":
                raise ValueError("Unknown worker operation")
            index, value = message["slot"], message["value"]
            if not 0 <= index < len(inputs):
                raise ValueError("Bad input slot")
            slot = inputs[index]
            if value != slot.value + 1:
                raise ValueError("Bad input timeline value")
            slot.wait(value)
            # Torch kernels copy into model-owned storage before returning the shared slot.
            rgb = slot.tensor[:, :, order].permute(2, 0, 1).float().div_(127.5).sub_(1)
            if backend is not None:
                rgb = rgb.to(torch.bfloat16)
            slot.signal(value + 1)
            completed()
            emit("input_free", slot=index, value=value + 1)
            if message["generation"] != generation:
                continue
            tag = (message["frame_id"], message["time_nanos"])
            if backend is None:
                image = rgb.permute(1, 2, 0).float() * 0.5 + 0.5
                publish(1 - image if mode == "invert" else image, tag)
                continue
            frames.append(rgb); ids.append(tag)
            if len(frames) == (5 if first else 4):
                started = time.perf_counter()
                results = backend.process(frames, ids)
                frames, ids, first = [], [], False
                for image, result_tag in results:
                    publish(image, result_tag)
                emit("stats", generation=generation, chunk_seconds=time.perf_counter() - started,
                    allocated_bytes=torch.cuda.memory_allocated(), reserved_bytes=torch.cuda.memory_reserved(), **caps())
    finally:
        stopped.set()
        if slots:
            torch.cuda.synchronize()
        for slot in reversed(slots):
            slot.close()
        release_unconsumed(remaining)
        if os.path.exists(args.socket):
            os.unlink(args.socket)


if __name__ == "__main__":
    try:
        main()
    except BaseException as error:
        emit("error", message=str(error))
        raise
