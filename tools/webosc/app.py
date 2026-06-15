import argparse
import asyncio
import json
import time
from dataclasses import dataclass
from typing import Any

from quart import Quart, render_template, websocket
from pythonosc.udp_client import SimpleUDPClient


@dataclass(frozen=True)
class OscConfig:
    host: str
    port: int
    send_mode: str
    absolute_address: str
    delta_address: str
    normalized: bool


app = Quart(__name__)
osc_config = OscConfig(
    host="127.0.0.1",
    port=9000,
    send_mode="absolute",
    absolute_address="/touch/position",
    delta_address="/touch/delta",
    normalized=True,
)
osc_client = SimpleUDPClient(osc_config.host, osc_config.port)


@app.get("/")
async def index() -> str:
    return await render_template("index.html", config=osc_config)


@app.websocket("/ws")
async def touch_socket() -> None:
    while True:
        raw_message = await websocket.receive()
        try:
            message = json.loads(raw_message)
        except json.JSONDecodeError:
            continue

        if message.get("type") != "touch":
            continue

        await asyncio.to_thread(send_osc, message)


def send_osc(message: dict[str, Any]) -> None:
    x = coerce_float(message.get("x"))
    y = coerce_float(message.get("y"))
    dx = coerce_float(message.get("dx"))
    dy = coerce_float(message.get("dy"))
    phase = message.get("phase")

    if osc_config.send_mode in {"absolute", "both"}:
        send_osc_message(osc_config.absolute_address, [x, y])

    if phase != "start" and osc_config.send_mode in {"delta", "both"}:
        send_osc_message(osc_config.delta_address, [dx, dy])


def send_osc_message(address: str, values: list[float]) -> None:
    osc_client.send_message(address, values)
    formatted_values = ", ".join(f"{value:.6f}" for value in values)
    print(
        f"[{time.strftime('%H:%M:%S')}] OSC -> "
        f"{osc_config.host}:{osc_config.port} {address} [{formatted_values}]",
        flush=True,
    )


def coerce_float(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Full-screen touch slider that forwards WebSocket pointer motion as OSC."
    )
    parser.add_argument("--host", default="127.0.0.1", help="Web server bind host.")
    parser.add_argument("--port", type=int, default=5000, help="Web server bind port.")
    parser.add_argument("--osc-host", default="127.0.0.1", help="OSC UDP destination host.")
    parser.add_argument("--osc-port", type=int, default=9000, help="OSC UDP destination port.")
    parser.add_argument(
        "--send",
        choices=("absolute", "delta", "both"),
        default="absolute",
        help="Which pointer values to send over OSC.",
    )
    parser.add_argument(
        "--osc-address",
        default="/touch/position",
        help="OSC address for absolute x/y messages.",
    )
    parser.add_argument(
        "--osc-delta-address",
        default="/touch/delta",
        help="OSC address for delta dx/dy messages.",
    )
    parser.add_argument(
        "--pixels",
        action="store_true",
        help="Send pixel coordinates instead of normalized 0..1 values.",
    )
    return parser.parse_args()


def main() -> None:
    global osc_config, osc_client

    args = parse_args()
    osc_config = OscConfig(
        host=args.osc_host,
        port=args.osc_port,
        send_mode=args.send,
        absolute_address=args.osc_address,
        delta_address=args.osc_delta_address,
        normalized=not args.pixels,
    )
    osc_client = SimpleUDPClient(osc_config.host, osc_config.port)

    print(
        f"[{time.strftime('%H:%M:%S')}] web: http://{args.host}:{args.port} | "
        f"osc: {osc_config.host}:{osc_config.port} | send: {osc_config.send_mode}"
    )
    app.run(host=args.host, port=args.port)


if __name__ == "__main__":
    main()
