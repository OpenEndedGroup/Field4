"""Small bounded loopback JSON frames; network/simulator work stays in its Python process."""
import json
import struct
MAX_BYTES = 4 * 1024 * 1024


def exact(stream, count):
    result = bytearray()
    while len(result) < count:
        block = stream.recv(count - len(result))
        if not block:
            raise EOFError('Motion connection closed')
        result.extend(block)
    return bytes(result)


def receive(stream):
    size, = struct.unpack('>I', exact(stream, 4))
    if not 2 <= size <= MAX_BYTES:
        raise ValueError('Invalid motion control frame size')
    return json.loads(exact(stream, size))


def send(stream, message):
    data = json.dumps(message, allow_nan=False, separators=(',', ':')).encode()
    if len(data) > MAX_BYTES:
        raise ValueError('Motion control frame too large')
    stream.sendall(struct.pack('>I', len(data)) + data)
