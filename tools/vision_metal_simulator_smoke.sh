#!/bin/sh
# Pre-smoke the visionOS Live Metal head against the visionOS *simulator*.
#
# What this genuinely proves, without a headset:
#   - the app decodes wire v2 and applies parts independently;
#   - Apple's Metal compiler accepts the generated preamble, including the `#define u_NAME` block;
#   - Kotlin/JS geometry crosses the JavaScriptCore typed-array bridge into a real MTLBuffer;
#   - a shader-only reload keeps the script/geometry/clock resident;
#   - a broken shader reports a diagnostic carrying the author's own line number.
#
# What it cannot prove (needs a device): per-eye stereo separation, spatial input, display timing.
#
# The assertion channel is the Field server's stdout: the head sends `visionmetal/status` back over
# /ws, which the server prints as `[vision-metal/ok|error] <uid> r<n> <phase>: <message>`. So this
# reads the server log rather than looking at pixels.
#
# Usage: tools/vision_metal_simulator_smoke.sh [port]
set -u

PORT="${1:-9478}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE_ID="com.openendedgroup.field4-vision"
OUT="$REPO/build/vision-metal-sim"
SERVER_LOG="$OUT/field-server.log"
APP_LOG="$OUT/app-console.log"
RESULT="$OUT/result.txt"

mkdir -p "$OUT"
: > "$RESULT"

say() { printf '%s\n' "$*" | tee -a "$RESULT"; }
fail() { say "FAIL: $*"; FAILED=1; }
FAILED=0

# ---- a booted visionOS simulator -------------------------------------------------------------
DEVICE="$(xcrun simctl list devices available 2>/dev/null \
    | awk '/-- visionOS/{v=1;next} /^-- /{v=0} v && /\(Booted\)/{print; exit}' \
    | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')"
if [ -z "$DEVICE" ]; then
    DEVICE="$(xcrun simctl list devices available 2>/dev/null \
        | awk '/-- visionOS 2[6-9]/{v=1;next} /^-- /{v=0} v && /\(/{print; exit}' \
        | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')"
    [ -n "$DEVICE" ] || { say "FAIL: no visionOS 26+ simulator available"; exit 1; }
    say "booting simulator $DEVICE"
    xcrun simctl boot "$DEVICE" 2>/dev/null
    xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1
fi
say "simulator: $DEVICE"

# ---- build + install --------------------------------------------------------------------------
say "building for the simulator…"
if ! (cd "$REPO/native/field-visionos" && xcodebuild -project Field4Vision.xcodeproj \
        -scheme Field4Vision -destination "id=$DEVICE" -configuration Debug build) \
        > "$OUT/xcodebuild.log" 2>&1; then
    say "FAIL: xcodebuild failed; see $OUT/xcodebuild.log"
    exit 1
fi
APP="$(find "$HOME/Library/Developer/Xcode/DerivedData/Field4Vision-"*/Build/Products/Debug-xrsimulator \
    -maxdepth 1 -name 'Field4Vision.app' 2>/dev/null | head -1)"
[ -n "$APP" ] || { say "FAIL: built app not found"; exit 1; }
xcrun simctl install "$DEVICE" "$APP" || { say "FAIL: install failed"; exit 1; }
say "installed $APP"

# ---- a Field server on $PORT ------------------------------------------------------------------
STARTED_SERVER=0
if curl -sk --max-time 3 "https://localhost:$PORT/health" 2>/dev/null | grep -q ok; then
    say "reusing the Field server already on $PORT"
else
    say "starting Field on $PORT…"
    WORKSPACE="$OUT/workspace"
    (cd "$REPO" && PORT="$PORT" AFIELD_SKIP_WORKSPACE_PERSIST=1 \
        ./gradlew runJvm --args="--workspace $WORKSPACE" > "$SERVER_LOG" 2>&1) &
    SERVER_PID=$!
    STARTED_SERVER=1
    i=0
    while [ "$i" -lt 480 ]; do
        curl -sk --max-time 2 "https://localhost:$PORT/health" 2>/dev/null | grep -q ok && break
        kill -0 "$SERVER_PID" 2>/dev/null || { say "FAIL: server exited; see $SERVER_LOG"; exit 1; }
        sleep 0.5
        i=$((i + 1))
    done
    curl -sk --max-time 2 "https://localhost:$PORT/health" 2>/dev/null | grep -q ok \
        || { say "FAIL: server never became healthy; see $SERVER_LOG"; exit 1; }
fi
say "server healthy on $PORT"

cleanup() {
    xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" >/dev/null 2>&1
    [ "$STARTED_SERVER" = "1" ] && kill "$SERVER_PID" 2>/dev/null
    return 0
}
trap cleanup EXIT INT TERM

# ---- launch the head, pointed at that server --------------------------------------------------
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" >/dev/null 2>&1
: > "$APP_LOG"
say "launching the head with Live Metal autostart…"
SIMCTL_CHILD_FIELD4_SERVER="https://localhost:$PORT" \
SIMCTL_CHILD_FIELD4_LIVE_METAL_AUTOSTART=1 \
    xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" > "$APP_LOG" 2>&1 &

# The head opens the immersive space, connects /ws and sends subscribe-all. Wait for the socket
# rather than guessing: the server logs a vision-metal line as soon as it has something to say.
i=0
while [ "$i" -lt 60 ]; do
    grep -q "subscribe\|vision-metal" "$SERVER_LOG" 2>/dev/null && break
    grep -q "LiveMetal\|Live Metal" "$APP_LOG" 2>/dev/null && break
    sleep 1
    i=$((i + 1))
done
say "head launched (waited ${i}s)"

# ---- publish a program: the geometry-shapes starting point -------------------------------------
say "publishing vision_geometry_shapes…"
python3 "$REPO/tools/field_backdoor.py" --json --port "$PORT" --timeout 180 \
    --command "macro write vision_geometry_shapes --file $REPO/dev_docs/agents/transcripts/vision_geometry_shapes.yaml" \
    --command 'macro run vision_geometry_shapes' > "$OUT/publish.json" 2>&1 \
    || say "note: backdoor reported a non-zero exit; continuing to read status"

# Give the head a few frames to compile the shader and take the geometry upload.
i=0
while [ "$i" -lt 45 ]; do
    grep -q "vision-metal.*MSL installed" "$SERVER_LOG" 2>/dev/null && break
    sleep 1
    i=$((i + 1))
done

say ""
say "---- vision-metal status reported by the head ----"
grep "vision-metal" "$SERVER_LOG" 2>/dev/null | tail -30 | tee -a "$RESULT"
say "-------------------------------------------------"
say ""

# ---- assertions --------------------------------------------------------------------------------
expect() {
    if grep -qE "$1" "$SERVER_LOG" 2>/dev/null; then
        say "ok   $2"
    else
        fail "$2  (no server-log line matching /$1/)"
    fi
}

expect 'vision-metal/ok.*uniforms: Declared 2: spin, light' 'uniforms part applied — 2 names declared, in order'
expect 'vision-metal/ok.*shader: MSL installed'             'Metal compiled the generated preamble + u_NAME defines'
expect 'vision-metal/ok.*geometry: 1337 vertices / 2380'    'Kotlin/JS geometry reached a MTLBuffer with the expected mesh'
expect 'vision-metal/ok.*lifecycle: RUN-START'              'the JavaScriptCore lifecycle is live'
if grep -qE 'vision-metal/error' "$SERVER_LOG" 2>/dev/null; then
    fail 'the head reported an error while publishing a known-good program'
    grep -E 'vision-metal/error' "$SERVER_LOG" | tail -5 | tee -a "$RESULT"
else
    say 'ok   no errors reported for the known-good program'
fi

UID_LINE="$(grep -oE 'agent_vision_geometry_shapes_kotlinjs' "$SERVER_LOG" 2>/dev/null | head -1)"
[ -n "$UID_LINE" ] || { say ""; say "cannot continue without a resident program uid"; say "vision-metal-simulator-smoke FAILED"; exit 1; }

# ---- a shader-ONLY reload must not disturb script, geometry or clock ---------------------------
say ""
say "reloading MSL only…"
MARK_RELOAD="$(wc -l < "$SERVER_LOG" | tr -d ' ')"
cat > "$OUT/reload.yaml" <<YAML
name: vision_metal_sim_reload
description: Republish only the shader part of a resident program, from a JVM Kotlin driver box.
operations:
  - create:
      box: agent_vision_sim_reload
      name: Vision reload driver
      frame: { x: 600.0, y: 80.0, w: 280.0, h: 120.0 }
      properties:
        code: |
          import afield.graphics.visionMetalProgramByUID
          val s = visionMetalProgramByUID("$UID_LINE")!!
          s.updateShader(s.shaderSource!!)
          println("driver: shader-only reload -> r" + s.currentRevision)
  - execute:
      box: agent_vision_sim_reload
      property: code
  - await:
      wait: pending
YAML
python3 "$REPO/tools/field_backdoor.py" --json --port "$PORT" --timeout 180 \
    --command "macro write vision_metal_sim_reload --file $OUT/reload.yaml" \
    --command 'macro run vision_metal_sim_reload' > "$OUT/reload.json" 2>&1 || true
i=0
while [ "$i" -lt 30 ]; do
    tail -n "+$((MARK_RELOAD + 1))" "$SERVER_LOG" 2>/dev/null | grep -q "shader: MSL installed" && break
    sleep 1
    i=$((i + 1))
done
tail -n "+$((MARK_RELOAD + 1))" "$SERVER_LOG" 2>/dev/null | grep "vision-metal" | tee -a "$RESULT"

RELOADED="$(tail -n "+$((MARK_RELOAD + 1))" "$SERVER_LOG" 2>/dev/null | grep -c 'vision-metal/ok.*shader: MSL installed')"
[ "${RELOADED:-0}" -ge 1 ] && say 'ok   the shader recompiled on its own' \
                           || fail 'a shader-only reload did not recompile'
for part in 'javascript: JavaScript installed' 'lifecycle: RUN-START' 'geometry: [0-9]+ vertices'; do
    if tail -n "+$((MARK_RELOAD + 1))" "$SERVER_LOG" 2>/dev/null | grep -qE "vision-metal.*$part"; then
        fail "a shader-only reload disturbed another part ($part reappeared)"
    fi
done
say 'ok   script, lifecycle and geometry stayed resident across the reload'

# ---- a broken shader must report the author's own line, and keep the last good pipeline --------
say ""
say "reloading a deliberately broken shader…"
MARK_BROKEN="$(wc -l < "$SERVER_LOG" | tr -d ' ')"
cat > "$OUT/broken.yaml" <<YAML
name: vision_metal_sim_broken
description: Republish a shader with an error on a known line, to check the reported line number.
operations:
  - create:
      box: agent_vision_sim_broken
      name: Vision broken-shader driver
      frame: { x: 600.0, y: 240.0, w: 280.0, h: 120.0 }
      properties:
        code: |
          import afield.graphics.visionMetalProgramByUID
          // Line 4 of the shader below is the bad one — an undeclared identifier.
          val broken = """
          vertex FieldVarying field_vertex(uint vertexID [[vertex_id]])
          {
              FieldVarying out;
              out.position = float4(wibble, 0.0, 0.0, 1.0);
              return out;
          }
          """.trimIndent()
          visionMetalProgramByUID("$UID_LINE")!!.updateShader(broken)
          println("driver: published a broken shader")
  - execute:
      box: agent_vision_sim_broken
      property: code
  - await:
      wait: pending
YAML
python3 "$REPO/tools/field_backdoor.py" --json --port "$PORT" --timeout 180 \
    --command "macro write vision_metal_sim_broken --file $OUT/broken.yaml" \
    --command 'macro run vision_metal_sim_broken' > "$OUT/broken.json" 2>&1 || true
i=0
while [ "$i" -lt 30 ]; do
    tail -n "+$((MARK_BROKEN + 1))" "$SERVER_LOG" 2>/dev/null | grep -q "vision-metal/error" && break
    sleep 1
    i=$((i + 1))
done
tail -n "+$((MARK_BROKEN + 1))" "$SERVER_LOG" 2>/dev/null | grep "vision-metal" | head -6 | tee -a "$RESULT"

if tail -n "+$((MARK_BROKEN + 1))" "$SERVER_LOG" 2>/dev/null | grep -qE 'vision-metal/error.*shader:[0-9]+:'; then
    say 'ok   the Metal error came back with a line number in the msl property'
    LINE="$(tail -n "+$((MARK_BROKEN + 1))" "$SERVER_LOG" | grep -oE 'shader:[0-9]+:' | head -1 | tr -cd '0-9')"
    [ "${LINE:-0}" = "4" ] && say "ok   and it is the right line ($LINE)" \
                           || say "note: reported line $LINE; expected 4 — check the #line offset"
else
    fail 'a broken shader did not report a line number (errors would land at line 0)'
fi

say ""
if [ "$FAILED" = "0" ]; then
    say "vision-metal-simulator-smoke ok"
else
    say "vision-metal-simulator-smoke FAILED"
fi
say "logs: $SERVER_LOG, $APP_LOG"
exit "$FAILED"
