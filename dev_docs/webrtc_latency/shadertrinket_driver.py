"""Drive a real browser ShaderTrinket on an aaaframe server, for verifying Field's WebRTC sources
and sinks from the other end (dev_docs/WEBRTC_SOURCES_SINKS.md).

    python shadertrinket_driver.py BASE OUTDIR SUFFIX RECEIVE CHANNEL SECONDS

Loads BASE/shadertrinket, sets an animated shader that shows iChannel<CHANNEL>, publishes as
login/SUFFIX (or not, with "-"), asks to receive RECEIVE (or not, with "-"), waits SECONDS, then
writes OUTDIR/trinket-canvas.png and prints the badge, the RTP counters and the signalling frames
as JSON. Runs with aaaframe's own venv, which has Playwright; HEADED=1 shows the window.

The shader must animate: a canvas that never changes gives canvas.captureStream no frames, so a
static ShaderTrinket (its default shader) publishes an empty stream.
"""
import asyncio
import json
import os
import sys

from playwright.async_api import async_playwright

base, outdir, suffix, receive, channel, seconds = sys.argv[1:7]
channel = int(channel)
seconds = float(seconds)

SHADER = """
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = fragCoord / iResolution.xy;
  vec3 remote = texture(iChannel%d, uv).rgb;
  vec3 stripes = 0.5 + 0.5 * cos(iTime + uv.xyx * 6.0 + vec3(0, 2, 4));
  fragColor = vec4(mix(stripes * 0.35, remote, step(0.02, dot(remote, vec3(1.0)))), 1.0);
}
""" % channel

# Logs every peer connection's state and, once connected, its video RTP counters.
INSTRUMENT = """
const Orig = window.RTCPeerConnection;
window.RTCPeerConnection = function (cfg) {
  const pc = new Orig(cfg);
  const t0 = performance.now();
  const log = (w) => console.log('PC ' + ((performance.now() - t0) / 1000).toFixed(1) + ' ' + w);
  pc.addEventListener('connectionstatechange', () => log('conn ' + pc.connectionState));
  pc.addEventListener('track', (e) => log('track ' + e.track.kind));
  setInterval(async () => {
    if (pc.connectionState !== 'connected') return;
    (await pc.getStats()).forEach((r) => {
      if (r.type === 'inbound-rtp' && r.kind === 'video') log('in video bytes=' + r.bytesReceived + ' decoded=' + r.framesDecoded + ' ' + (r.frameWidth || 0) + 'x' + (r.frameHeight || 0) + ' fps=' + r.framesPerSecond);
      if (r.type === 'outbound-rtp' && r.kind === 'video') log('out video bytes=' + r.bytesSent + ' encoded=' + r.framesEncoded + ' ' + (r.frameWidth || 0) + 'x' + (r.frameHeight || 0));
    });
  }, 3000);
  return pc;
};
window.RTCPeerConnection.prototype = Orig.prototype;
"""


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=not os.environ.get("HEADED"),
            args=["--autoplay-policy=no-user-gesture-required"],
        )
        page = await browser.new_page(viewport={"width": 1280, "height": 800})
        logs, frames = [], []
        page.on("console", lambda m: logs.append(m.text))

        def on_ws(ws):
            ws.on("framereceived", lambda f: frames.append("< " + str(f)[:160]))
            ws.on("framesent", lambda f: frames.append("> " + str(f)[:160]))

        page.on("websocket", on_ws)
        await page.add_init_script(INSTRUMENT)
        await page.goto(base + "/shadertrinket")
        await page.wait_for_function("window.shadertrinketEmbed !== undefined", timeout=60000)
        await page.wait_for_timeout(1500)
        await page.evaluate("(c) => window.shadertrinketEmbed.setCode(c)", SHADER)
        await page.wait_for_timeout(800)
        if suffix != "-":
            await page.evaluate("(s) => window.shadertrinketEmbed.publish(s)", suffix)
        if receive != "-":
            await page.evaluate("([n, c]) => window.shadertrinketEmbed.receive(n, c)", [receive, channel])
        await page.wait_for_timeout(int(seconds * 1000))
        canvas = await page.query_selector("#shader-canvas")
        if canvas:
            await canvas.screenshot(path=f"{outdir}/trinket-canvas.png")
        badge = await page.evaluate("document.getElementById('canvas-badge') && document.getElementById('canvas-badge').textContent")
        print(json.dumps({
            "badge": badge,
            "rtp": [l for l in logs if l.startswith("PC ")][-12:],
            "frames": frames[-20:],
        }, indent=1))
        await browser.close()


asyncio.run(main())
