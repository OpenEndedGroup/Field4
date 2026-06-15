# webosc

A tiny Quart app that shows one full-screen touch slider and forwards pointer movement as OSC over UDP.

## Run

```sh
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python app.py --osc-host 127.0.0.1 --osc-port 9000 --send absolute
```

Open `http://127.0.0.1:5000`.

## Options

```text
--host HOST                 Web server bind host, default 127.0.0.1
--port PORT                 Web server bind port, default 5000
--osc-host HOST             OSC UDP destination host, default 127.0.0.1
--osc-port PORT             OSC UDP destination port, default 9000
--send absolute|delta|both  Send position, movement deltas, or both
--osc-address PATH          OSC path for absolute x/y, default /touch/position
--osc-delta-address PATH    OSC path for dx/dy, default /touch/delta
--pixels                    Send pixel values instead of normalized 0..1 values
```

Absolute messages are sent as `[x, y]`. Delta messages are sent as `[dx, dy]`.

For delta mode, the first touch after putting a finger or mouse down establishes the new origin and does not send a delta. This lets you lift and re-place your finger to keep scrolling trackpad-style. The server prints each OSC message it sends.
