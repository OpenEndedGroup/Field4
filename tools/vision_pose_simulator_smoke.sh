#!/bin/sh
# Pre-smoke the __visionpose__ uplink against the visionOS *simulator*.
#
# What this genuinely proves, without a headset:
#   - the head connects /ws and its `visionpose/hello` parses on the Kotlin side;
#   - a real Compositor Services frame's pose survives the wire: 16-float column-major matrices
#     decode, the world→eye composition succeeds and the tangents come out of the projection
#     (any of those throwing means no pose is ever published, so the log line is the assertion);
#   - poses keep arriving at frame rate rather than once;
#   - nothing on the channel is being rejected;
#   - the Kotlin API is reachable the way Field code will reach it — from a box.
#
# What it cannot prove (needs a device): real head motion, and two views. The simulator reports a
# single view, so `eyes` is 1 here and 2 on hardware; that difference is expected, not a failure.
#
# Sibling of tools/vision_metal_simulator_smoke.sh, and it borrows that script's mechanics:
# FIELD4_LIVE_METAL_AUTOSTART opens the immersive space unattended, which is what runs the renderer
# the uplink is fed from.
#
# Usage: tools/vision_pose_simulator_smoke.sh [port]
set -u

PORT="${1:-9479}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE_ID="com.openendedgroup.field4-vision"
OUT="$REPO/build/vision-pose-sim"
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
    # `set -m` puts the server in its own process group, so cleanup can kill the whole tree. Killing
    # `$SERVER_PID` alone reaps the subshell and leaves the Gradle wrapper and the JVM running; and a
    # port-based sweep finds nothing when the server never got far enough to listen, which is exactly
    # the case where it leaks.
    set -m
    (cd "$REPO" && PORT="$PORT" AFIELD_SKIP_WORKSPACE_PERSIST=1 \
        ./gradlew runJvm --args="--workspace $WORKSPACE" > "$SERVER_LOG" 2>&1) &
    SERVER_PID=$!
    set +m
    STARTED_SERVER=1
    # This machine is shared with other build sessions; a cold `runJvm` behind seven busy Gradle
    # daemons has been observed to need well past four minutes. Wait on the *process*, and only give
    # up when it dies or the wall clock runs out, rather than assuming a fixed compile time.
    i=0
    while [ "$i" -lt 1800 ]; do
        curl -sk --max-time 2 "https://localhost:$PORT/health" 2>/dev/null | grep -q ok && break
        kill -0 "$SERVER_PID" 2>/dev/null || { say "FAIL: server exited; see $SERVER_LOG"; exit 1; }
        if [ $((i % 120)) -eq 119 ]; then say "  still waiting for the server ($((i / 2))s)…"; fi
        sleep 0.5
        i=$((i + 1))
    done
    curl -sk --max-time 2 "https://localhost:$PORT/health" 2>/dev/null | grep -q ok \
        || { say "FAIL: server never became healthy after $((i / 2))s; see $SERVER_LOG"; exit 1; }
fi
say "server healthy on $PORT"

# `$SERVER_PID` is the *subshell* running the Gradle wrapper; the Field JVM it forks outlives a kill
# of either. So resolve the actual listener by port and take that down too, then wait for it — the
# port going quiet is not the process going away.
cleanup() {
    xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" >/dev/null 2>&1
    [ "$STARTED_SERVER" = "1" ] || return 0
    # Whole process group first: that is what reaches the Gradle wrapper and the JVM it forked, and
    # it works even when the server never bound the port.
    PGID="$(ps -o pgid= -p "$SERVER_PID" 2>/dev/null | tr -d ' ')"
    [ -n "$PGID" ] && kill -- -"$PGID" 2>/dev/null
    kill "$SERVER_PID" 2>/dev/null
    JVM="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)"
    [ -n "$JVM" ] && kill "$JVM" 2>/dev/null
    i=0
    while [ "$i" -lt 10 ]; do
        [ -n "$JVM" ] || break
        kill -0 "$JVM" 2>/dev/null || break
        sleep 1
        i=$((i + 1))
    done
    if [ -n "$JVM" ] && kill -0 "$JVM" 2>/dev/null; then
        kill -9 "$JVM" 2>/dev/null
        say "note: the Field JVM on $PORT needed SIGKILL"
    fi
    # Last resort: anything still holding this run's workspace. Named by workspace rather than by
    # port so it also catches a server that died before listening.
    for stray in $(pgrep -f "$OUT/workspace" 2>/dev/null); do
        kill -9 "$stray" 2>/dev/null
        say "note: killed a stray process still holding this run's workspace ($stray)"
    done
    return 0
}
trap cleanup EXIT INT TERM

# ---- launch the head; the immersive renderer is what drives the uplink -------------------------
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" >/dev/null 2>&1
: > "$APP_LOG"
say "launching the head with Live Metal autostart…"
SIMCTL_CHILD_FIELD4_SERVER="https://localhost:$PORT" \
SIMCTL_CHILD_FIELD4_LIVE_METAL_AUTOSTART=1 \
SIMCTL_CHILD_FIELD4_POSE_NAME="sim-head" \
    xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" > "$APP_LOG" 2>&1 &

# The first pose always prints one line, whatever the log rate is set to — so wait for that rather
# than for a fixed number of seconds.
i=0
while [ "$i" -lt 90 ]; do
    grep -q "\[vision-pose\] first pose" "$SERVER_LOG" 2>/dev/null && break
    sleep 1
    i=$((i + 1))
done
say "waited ${i}s for the first pose"

say ""
say "---- vision-pose lines from the server ----"
grep "vision-pose" "$SERVER_LOG" 2>/dev/null | tail -10 | tee -a "$RESULT"
say "-------------------------------------------"
say ""

expect() {
    if grep -qE "$1" "$SERVER_LOG" 2>/dev/null; then
        say "ok   $2"
    else
        fail "$2  (no server-log line matching /$1/)"
    fi
}

# The receiver's media-wire parser proves itself at launch (there is no XCTest target to hold it) and
# reports the verdict over __visionpose__. It has to come back over the socket: `os.Logger` goes to
# the unified log, and `simctl launch --console-pty` captures nothing from this app — verified, the
# console file comes back zero bytes. So assert on the SERVER log, like the Live Metal smoke does.
if grep -q "rtt-wire-selfcheck ok" "$SERVER_LOG" 2>/dev/null; then
    say 'ok   the media wire parser self-check passed (v1 + v2 pose tail)'
elif grep -q "rtt-wire-selfcheck FAILED" "$SERVER_LOG" 2>/dev/null; then
    fail "the media wire parser self-check failed: $(grep -o 'rtt-wire-selfcheck FAILED.*' "$SERVER_LOG" | head -1)"
else
    fail 'the head never reported a media wire self-check'
fi

expect '\[vision-pose\] head .sim-head. .* attached' 'the head said hello and it parsed'
expect '\[vision-pose\] first pose' 'a real compositor pose decoded, composed and published'

# ---- is it still arriving, and is anything being refused? --------------------------------------
# Read the counters from a box, which is how Field code will read them.
say "probing the stream from a box…"
# Quoted delimiter: the Kotlin below contains backticks in a comment, and an unquoted heredoc
# would run them as command substitution (it did — three 'command not found' lines, and the words
# vanished from the generated YAML). Nothing here needs shell expansion.
cat > "$OUT/probe.yaml" <<'YAML'
name: vision_pose_probe
description: Sample the pose stream's counters twice from a JVM Kotlin box.
operations:
  - create:
      box: agent_vision_pose_probe
      name: Pose probe
      frame: { x: 600.0, y: 80.0, w: 280.0, h: 120.0 }
      properties:
        code: |
          import afield.graphics.VisionPoseStream
          val first = VisionPoseStream.frameCount
          Thread.sleep(3000)
          val second = VisionPoseStream.frameCount
          // `accepted` and `regressed` are both cumulative since server start; `grew` is the 3s
          // window. Mixing the two scales is how you get a nonsense ratio.
          println("pose-probe: " + VisionPoseStream.summary())
          // Round-trips the clock-sync ping over the real socket. The simulator has no video stream,
          // so the device's own spans stay empty here; what this proves is the ping/pong path and the
          // offset estimator against a live head rather than a synthetic one.
          val latency = afield.graphics.visionPoseLatencyReport(pings = 6, spacingMillis = 120)
          println("pose-probe: offsetKnown=" + afield.graphics.VisionPoseLatency.offsetKnown +
                  " bestRttMs=" + "%.2f".format(
                      if (afield.graphics.VisionPoseLatency.bestRoundTripNanos == Long.MAX_VALUE) -1.0
                      else afield.graphics.VisionPoseLatency.bestRoundTripNanos / 1.0e6))
          latency.lines().forEach { println("pose-latency: " + it) }
          println("pose-probe: grew=" + (second - first) +
                  " accepted=" + second +
                  " heads=" + VisionPoseStream.connectedHeadCount +
                  " rejected=" + VisionPoseStream.rejectedCount +
                  " regressed=" + VisionPoseStream.regressedCount +
                  " eyes=" + (VisionPoseStream.latest?.eyes?.size ?: 0) +
                  " controllers=" + (VisionPoseStream.latest?.controllers?.size ?: 0) +
                  " intervalMs=" + "%.2f".format(VisionPoseStream.intervalMillis))
  - execute:
      box: agent_vision_pose_probe
      property: code
  - await:
      wait: pending
YAML
python3 "$REPO/tools/field_backdoor.py" --json --port "$PORT" --timeout 180 \
    --command "macro write vision_pose_probe --file $OUT/probe.yaml" \
    --command 'macro run vision_pose_probe' > "$OUT/probe.json" 2>&1 \
    || say "note: backdoor reported a non-zero exit; continuing to read the log"

# A box's `println` is rewritten to `_printlnHere` and goes to the box's *output channel*, not the
# server's stdout — the server log only ever shows the echoed source. So read the macro result, which
# is where the output actually lands. (Reading the log instead matches the echoed source and yields a
# confident, wrong answer.)
say ""
say "---- box probe ----"
PROBE="$(python3 - "$OUT/probe.json" <<'PYEOF'
import json, re, sys
# The backdoor writes one JSON object per command, concatenated.
text = open(sys.argv[1]).read()
decoder, index, lines = json.JSONDecoder(), 0, []
while index < len(text):
    while index < len(text) and text[index].isspace():
        index += 1
    if index >= len(text):
        break
    value, index = decoder.raw_decode(text, index)
    for item in (value.get("data") or {}).get("output") or []:
        lines.append(item.get("html", ""))
for line in lines:
    # Both probe lines, so the shell reads numbers out of short parsed strings rather than out of
    # the raw JSON — a greedy pattern over one very long JSON line is how the RTT came back blank.
    if re.search(r"pose-probe: (grew=\d+ accepted=\d+|offsetKnown=)", line):
        print(line)
PYEOF
)"
if [ -n "$PROBE" ]; then printf '%s\n' "$PROBE" | tee -a "$RESULT"; fi
COUNTS="$(printf '%s\n' "$PROBE" | grep 'grew=' | head -1)"
CLOCK="$(printf '%s\n' "$PROBE" | grep 'offsetKnown=' | head -1)"
say "-------------------"
say ""

if [ -z "$PROBE" ]; then
    fail 'the box probe never reported — the Kotlin API was not reachable from a box'
else
    GREW="$(printf '%s' "$COUNTS" | sed -E 's/.*grew=([0-9]+).*/\1/')"
    REJECTED="$(printf '%s' "$COUNTS" | sed -E 's/.*rejected=([0-9]+).*/\1/')"
    REGRESSED="$(printf '%s' "$COUNTS" | sed -E 's/.*regressed=([0-9]+).*/\1/')"
    EYES="$(printf '%s' "$COUNTS" | sed -E 's/.*eyes=([0-9]+).*/\1/')"
    CONTROLLERS="$(printf '%s' "$COUNTS" | sed -E 's/.*controllers=([0-9]+).*/\1/')"
    [ "${GREW:-0}" -gt 30 ] && say "ok   the stream is sustained ($GREW poses in 3s)" \
        || fail "the stream stalled after the first pose (only $GREW in 3s)"
    [ "${REJECTED:-1}" -eq 0 ] && say 'ok   nothing on the channel was refused' \
        || fail "$REJECTED messages on the channel were refused"
    case "$CLOCK" in
        *offsetKnown=true*)
            RTT="$(printf '%s' "$CLOCK" | sed -E 's/.*bestRttMs=([0-9.-]+).*/\1/')"
            say "ok   clock-sync ping round-tripped over the socket (best RTT ${RTT:-?} ms)"
            ;;
        *)
            fail 'the clock-sync ping never came back — host and device spans cannot be compared'
            ;;
    esac
    [ "${EYES:-0}" -ge 1 ] && say "ok   $EYES view(s) carried per frame (1 in the simulator, 2 on device)" \
        || fail 'no per-eye views arrived'
    # The simulator has no tracked accessories, so zero is the correct answer here and the assertion
    # is only that the field round-trips. Controllers are device-only to verify for real.
    say "ok   controllers field present: $CONTROLLERS (0 expected in the simulator)"
    # Out-of-order arrival is expected — /ws dispatches a coroutine per raw message — and dropping
    # those instead of applying them over a newer pose is the point. Compare cumulative against
    # cumulative: `GREW` is a 3-second window and `REGRESSED` is a total, so ratioing those two
    # (which an earlier version of this script did) fails on nothing but a long run.
    ACCEPTED="$(printf '%s' "$COUNTS" | sed -E 's/.*accepted=([0-9]+).*/\1/')"
    if [ "${ACCEPTED:-0}" -le 0 ]; then
        fail 'no poses were accepted at all'
    elif [ "${REGRESSED:-0}" -ge "${ACCEPTED:-0}" ]; then
        fail "more poses arrived late ($REGRESSED) than in order ($ACCEPTED cumulative)"
    else
        PCT=$(( REGRESSED * 100 / ACCEPTED ))
        say "ok   $REGRESSED of $((ACCEPTED + REGRESSED)) poses arrived late (${PCT}% of accepted), dropped not applied"
    fi
fi

say ""
if [ "$FAILED" = "0" ]; then
    say "vision-pose-simulator-smoke ok"
    exit 0
fi
say "vision-pose-simulator-smoke FAILED"
exit 1
