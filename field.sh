#!/bin/sh
# Unix shell script to run the JVM target using Gradle Wrapper
# This script works both from command line and when double-clicked in a file browser

# Get the directory where this script is located. Note INVOKED_FROM: a workspace
# path typed on the command line has to resolve against the directory the user is
# standing in, which the cd below is about to leave.
DIR="$(cd "$(dirname "$0")" && pwd)"
INVOKED_FROM="$PWD"
cd "$DIR"

# --- launcher arguments ----------------------------------------------------------
#
# The backend already parses --workspace itself (afield.arguments.option), and this
# script has always forwarded "$@" to it, so that flag reached it either way. It is
# handled here as well because three things can only be done out here: resolving a
# relative path against INVOKED_FROM rather than $DIR; rejecting a typo *before* the
# minutes of Gradle that run before the backend ever looks at its arguments; and
# printing which workspace a bare launch is about to open. That last one matters
# because the choice is remembered once per machine, in ~/.afield/last-workspace.txt
# — so any other clone or worktree on this machine may have been the one that wrote
# it, and the workspace you get is not the workspace of the checkout you launched.

WORKSPACE_PREFERENCE_FILE="$HOME/.afield/last-workspace.txt"
DEFAULT_WORKSPACE="$HOME/Documents/AFieldWorkspace"

usage() {
    cat <<EOF
Usage: ./field.sh [<workspace>] [options] [backend arguments]

Builds Field, then launches the backend and the Electron window together.

  <workspace>              the directory holding your Field documents; a bare
                           first argument is taken as the workspace
  -w, --workspace <path>   the same, stated explicitly
  --workspace=<path>       the same again
  --print-workspace        print the workspace this launch would use, then exit
  -h, --help               show this message

A single document works too — a folder holding a metadata.edn, or that file
itself. The workspace is then the folder above it, which is what makes the
document visible on the welcome page.

With no workspace given, Field reopens the one it last used, recorded once per
machine in
  $WORKSPACE_PREFERENCE_FILE
so a different clone or worktree of this repository may have been what last
wrote it. Naming a workspace here makes it the remembered one for next time.

Any other argument is passed through to the backend unchanged. See also
AFIELD_SKIP_BUILD=1 to skip the build check, and PORT to move off 9092.
EOF
}

absolute_workspace_path() {
    # Expand the way the user's shell would have, had they not quoted it.
    case "$1" in
        "~")   printf '%s' "$HOME" ;;
        "~/"*) printf '%s%s' "$HOME" "${1#\~}" ;;
        /*)    printf '%s' "$1" ;;
        *)     printf '%s/%s' "$INVOKED_FROM" "$1" ;;
    esac
}

# Sets FIELD_WORKSPACE, plus FIELD_DOCUMENT when what was named turns out to be a
# document rather than a workspace. Both spellings get typed: dev_docs hands out
# `./field.sh <folder holding a metadata.edn>` and `./field.sh <...>/metadata.edn`,
# and a document folder passed straight through as the workspace would open a
# workspace that merely looks empty — its documents are its *subdirectories*.
resolve_workspace_request() {
    RAW="$(absolute_workspace_path "$1")"
    FIELD_DOCUMENT=""
    case "$RAW" in
        */metadata.edn) FIELD_DOCUMENT="${RAW%/metadata.edn}" ;;
        *)              [ -f "$RAW/metadata.edn" ] && FIELD_DOCUMENT="$RAW" ;;
    esac
    if [ -n "$FIELD_DOCUMENT" ]; then
        RAW="$(dirname "$FIELD_DOCUMENT")"
        FIELD_DOCUMENT="$(basename "$FIELD_DOCUMENT")"
    fi
    if [ -d "$RAW" ]; then
        FIELD_WORKSPACE="$( cd "$RAW" && pwd )"
    else
        FIELD_WORKSPACE="$RAW"
    fi
}

persisted_workspace() {
    [ -f "$WORKSPACE_PREFERENCE_FILE" ] || return 1
    VALUE="$(tr -d '\r' < "$WORKSPACE_PREFERENCE_FILE" | sed -n '1s/^[[:space:]]*//;1s/[[:space:]]*$//;1p')"
    [ -n "$VALUE" ] || return 1
    printf '%s' "$VALUE"
}

WORKSPACE_REQUESTED=""
PRINT_WORKSPACE=0

# POSIX sh has no arrays, so "$@" is rebuilt in place: each of the original $argc
# arguments is shifted off the front, and the ones the backend should see are pushed
# onto the back. The loop runs exactly $argc times, so nothing pushed is re-read.
argc=$#
consumed=0
passthrough_seen=0
while [ "$consumed" -lt "$argc" ]; do
    arg="$1"
    shift
    consumed=$((consumed + 1))
    case "$arg" in
        -h|--help)
            usage
            exit 0
            ;;
        --print-workspace)
            PRINT_WORKSPACE=1
            ;;
        -w|--workspace)
            if [ "$consumed" -ge "$argc" ]; then
                echo "field: $arg needs a directory" >&2
                exit 2
            fi
            WORKSPACE_REQUESTED="$1"
            shift
            consumed=$((consumed + 1))
            ;;
        --workspace=*)
            WORKSPACE_REQUESTED="${arg#--workspace=}"
            ;;
        -*)
            set -- "$@" "$arg"
            passthrough_seen=1
            ;;
        *)
            # A bare path is the workspace only while it is unambiguous: once any
            # backend argument has gone past, a bare word is more likely to be that
            # argument's value than a workspace.
            if [ -z "$WORKSPACE_REQUESTED" ] && [ "$passthrough_seen" = 0 ]; then
                WORKSPACE_REQUESTED="$arg"
            else
                set -- "$@" "$arg"
                passthrough_seen=1
            fi
            ;;
    esac
done

FIELD_DOCUMENT=""
if [ -n "$WORKSPACE_REQUESTED" ]; then
    resolve_workspace_request "$WORKSPACE_REQUESTED"
    WORKSPACE_ORIGIN="from the command line"
    if [ ! -d "$FIELD_WORKSPACE" ]; then
        echo "field: there is no workspace directory at $FIELD_WORKSPACE" >&2
        echo "field: to start a new workspace there, create it first — mkdir -p \"$FIELD_WORKSPACE\"" >&2
        exit 2
    fi
    set -- "$@" --workspace "$FIELD_WORKSPACE"
elif FIELD_WORKSPACE="$(persisted_workspace)"; then
    WORKSPACE_ORIGIN="last used, per $WORKSPACE_PREFERENCE_FILE"
else
    FIELD_WORKSPACE="$DEFAULT_WORKSPACE"
    WORKSPACE_ORIGIN="the default; nothing recorded yet"
fi

if [ "$PRINT_WORKSPACE" = 1 ]; then
    printf '%s\n' "$FIELD_WORKSPACE"
    exit 0
fi

if [ -n "$FIELD_DOCUMENT" ]; then
    echo "field: $FIELD_DOCUMENT is a document, not a workspace — using the folder that holds it" >&2
fi
echo "field: workspace $FIELD_WORKSPACE ($WORKSPACE_ORIGIN)" >&2
if [ -n "$FIELD_DOCUMENT" ]; then
    echo "field: open $FIELD_DOCUMENT from the welcome page once the window is up" >&2
fi

# run_in_terminal re-runs this script in a fresh terminal, where the arguments
# parsed above are gone; put an explicitly requested workspace back on that inner
# command line. Left empty otherwise, so the re-run consults the same preference
# file this one just did.
WORKSPACE_RELAUNCH_ARGS=""
if [ -n "$WORKSPACE_REQUESTED" ]; then
    WORKSPACE_RELAUNCH_ARGS="--workspace '$FIELD_WORKSPACE'"
fi

python_cmd() {
    if command -v python3 >/dev/null 2>&1; then
        command -v python3
    elif command -v python >/dev/null 2>&1; then
        command -v python
    else
        printf ''
    fi
}

derive_sidecar_port() {
    if [ -n "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT" ]; then
        printf '%s' "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT"
        return
    fi
    printf '%s' "$DIR" | cksum | awk '{ print 17841 + ($1 % 1000) }'
}

sidecar_status() {
    PYTHON="$(python_cmd)"
    if [ -z "$PYTHON" ]; then
        printf 'unknown'
        return 0
    fi
    "$PYTHON" - "$1" "$DIR" <<'PY'
import json
import os
import sys
import urllib.request

url = sys.argv[1].rstrip("/")
workspace = os.path.realpath(sys.argv[2])
if url.endswith("/analyze"):
    url = url[:-len("/analyze")]
try:
    with urllib.request.urlopen(url + "/health", timeout=0.8) as response:
        payload = json.loads(response.read().decode("utf-8"))
except Exception:
    print("down")
    sys.exit(0)

reported = os.path.realpath(str(payload.get("workspaceRoot", "")))
print("ok" if reported == workspace else "wrong")
PY
}

choose_sidecar_port() {
    START_PORT="$(derive_sidecar_port)"
    OFFSET=0
    while [ "$OFFSET" -lt 25 ]; do
        SIDECAR_PORT="$((START_PORT + OFFSET))"
        URL="http://127.0.0.1:$SIDECAR_PORT"
        STATUS="$(sidecar_status "$URL")"
        if [ "$STATUS" = "ok" ] || [ "$STATUS" = "down" ] || [ "$STATUS" = "unknown" ]; then
            printf '%s' "$SIDECAR_PORT"
            return
        fi
        OFFSET="$((OFFSET + 1))"
    done
    printf '%s' "$START_PORT"
}

ensure_lfs_content() {
    # Files marked `filter=lfs` only become real content when Git LFS is installed at clone time;
    # without it Git leaves a ~130-byte text pointer in place. Nothing notices until Gradle tries to
    # read one, which surfaces ~2 minutes into :compileKotlinJvm as "zip END header not found" — a
    # message that gives no hint that the clone is the problem. Check up front, before provisioning
    # hundreds of megabytes of JDKs for a build that cannot succeed.
    [ -f .gitattributes ] || return 0
    LFS_STUBS=""
    for pattern in $( awk '/filter=lfs/ { print $1 }' .gitattributes ); do
        for candidate in $pattern; do
            [ -f "$candidate" ] || continue
            if head -c 40 "$candidate" 2>/dev/null | grep -q "git-lfs.github.com"; then
                LFS_STUBS="$LFS_STUBS  $candidate
"
            fi
        done
    done
    [ -n "$LFS_STUBS" ] || return 0
    echo "field: this clone is missing its Git LFS content — the following are pointer files, not real content:" >&2
    printf '%s' "$LFS_STUBS" >&2
    echo "field: install Git LFS, then run 'git lfs install' and 'git lfs pull' (see Requirements in README.md)" >&2
    return 1
}

ensure_gradle_jvm() {
    # gradlew's JVM-wrapper preamble provisions Corretto into ~/.field4/gradle-jvm on first contact,
    # and it is not safe to run two copies of it at once: it `rm -rf`s the target directory and
    # downloads to a single fixed temp path, so concurrent gradlew processes delete each other's
    # half-extracted JDK (tar then fails with "Cannot mkdir: No such file or directory" on every
    # entry). This script starts exactly such a pair — the backgrounded sidecar gradlew below and the
    # foreground build gradlew — so on a fresh clone the two race and the build dies. Provision once,
    # serially, before either of them runs. Nothing to do once a previous run has left a .flag behind;
    # that is the same up-to-date marker gradlew itself checks, so warm runs pay nothing.
    for flag in "$HOME"/.field4/gradle-jvm/*/.flag; do
        [ -e "$flag" ] && return 0
    done
    echo "field: provisioning the build JVM (first run downloads a JDK; this happens once)…" >&2
    if ! ./gradlew --version; then
        echo "field: could not provision the build JVM — see the errors above" >&2
        return 1
    fi
}

# Gradle only picks up the provisioned toolchain when it is named on the command line (see the note in
# ensure_graalvm_toolchain), so record the flag here and have every gradlew invocation pass it. Stays
# empty when provisioning is skipped, in which case the calls below fall back to plain gradlew.
FIELD_TOOLCHAIN_ARG=""

note_toolchain() {
    # $1 is a .../bin/java; Gradle wants the JDK home, which is that path minus the /bin/java tail
    # (on macOS this correctly keeps the .../Contents/Home prefix).
    FIELD_TOOLCHAIN_ARG="-Dorg.gradle.java.installations.paths=${1%/bin/java}"
}

ensure_graalvm_toolchain() {
    # build.gradle.kts compiles against a GraalVM Community toolchain (vendor = GRAAL_VM) because the
    # polyglot/Truffle JS+WASM runtime and the JVMCI-related JVM args depend on it. Gradle can in
    # principle auto-provision that via the foojay resolver, but it asks only for "languageVersion 25"
    # and foojay hands back the *newest* matching build — currently graalvm-community-jdk-25i1-25.0.3
    # (java_version 25.1.3), whose download link is broken upstream: /redirect 301s to the GitHub
    # releases *listing page*, so Gradle saves 500KB of HTML and dies with "Cannot expand TAR"...
    # and caches that HTML, so every subsequent run fails identically. Revving Gradle does not help;
    # the bad data is in foojay. Fetch the exact pinned build straight from the official release
    # instead. That also keeps the toolchain matching the org.graalvm.polyglot:*:$graalvmVersion
    # artifacts, which the floating foojay pick silently did not. Note the JDK cannot simply be
    # dropped into ~/.gradle/jdks: that is Gradle's *provisioning cache*, and it only trusts JDKs it
    # unpacked there itself, so a hand-placed tree is ignored. It has to be pointed at explicitly with
    # org.gradle.java.installations.paths, which every gradlew call below therefore passes (GRADLE_OPTS
    # does *not* carry this property through — only the command line does). That one flag serves the
    # root build and the sidecar's jvmToolchain(25) alike, with neither consulting foojay at all.
    if [ "${AFIELD_SKIP_TOOLCHAIN_PROVISION:-0}" = "1" ]; then
        return 0
    fi
    GRAAL_VERSION="$(sed -n 's/^val graalvmVersion = "\([^"]*\)".*/\1/p' build.gradle.kts | head -1)"
    if [ -z "$GRAAL_VERSION" ]; then
        echo "field: could not read graalvmVersion from build.gradle.kts" >&2
        return 1
    fi

    JDKS_DIR="$HOME/.field4/toolchains"
    for probe in "$JDKS_DIR"/graalvm-community-*"$GRAAL_VERSION"*/bin/java \
                 "$JDKS_DIR"/graalvm-community-*"$GRAAL_VERSION"*/Contents/Home/bin/java; do
        [ -x "$probe" ] && { note_toolchain "$probe"; return 0; }
    done

    case "$( uname -s )" in
        Darwin) GRAAL_OS=macos ;;
        Linux)  GRAAL_OS=linux ;;
        *)      echo "field: no GraalVM build known for $( uname -s ); install JDK $GRAAL_VERSION manually" >&2
                return 1 ;;
    esac
    case "$( uname -m )" in
        x86_64|amd64)  GRAAL_ARCH=x64 ;;
        arm64|aarch64) GRAAL_ARCH=aarch64 ;;
        *)             echo "field: no GraalVM build known for $( uname -m ); install JDK $GRAAL_VERSION manually" >&2
                       return 1 ;;
    esac
    GRAAL_URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAAL_VERSION}/graalvm-community-jdk-${GRAAL_VERSION}_${GRAAL_OS}-${GRAAL_ARCH}_bin.tar.gz"

    echo "field: provisioning the GraalVM $GRAAL_VERSION toolchain (~320MB; this happens once)…" >&2
    mkdir -p "$JDKS_DIR"
    # Stage into a private directory and move the finished JDK into place, so an interrupted or
    # corrupt download never leaves a half-extracted tree for the probe above to accept next run.
    GRAAL_TMP="$JDKS_DIR/.field-graalvm-staging-$$"
    rm -rf "$GRAAL_TMP"
    mkdir -p "$GRAAL_TMP"
    if ! curl -fL --progress-bar --output "$GRAAL_TMP/graalvm.tar.gz" "$GRAAL_URL"; then
        rm -rf "$GRAAL_TMP"
        echo "field: could not download $GRAAL_URL" >&2
        return 1
    fi
    # The failure that motivates this whole function is an HTML page saved under a .tar.gz name, so
    # check that what arrived is actually an intact gzip stream before trusting it.
    if ! gzip -t "$GRAAL_TMP/graalvm.tar.gz" 2>/dev/null; then
        rm -rf "$GRAAL_TMP"
        echo "field: $GRAAL_URL did not return a valid archive (an error page?) — install JDK $GRAAL_VERSION manually" >&2
        return 1
    fi
    if ! tar -xzf "$GRAAL_TMP/graalvm.tar.gz" -C "$GRAAL_TMP"; then
        rm -rf "$GRAAL_TMP"
        echo "field: could not unpack the GraalVM toolchain" >&2
        return 1
    fi
    rm -f "$GRAAL_TMP/graalvm.tar.gz"
    GRAAL_UNPACKED=""
    for entry in "$GRAAL_TMP"/*/; do
        [ -d "$entry" ] && GRAAL_UNPACKED="${entry%/}"
    done
    if [ -z "$GRAAL_UNPACKED" ]; then
        rm -rf "$GRAAL_TMP"
        echo "field: the GraalVM archive did not contain a JDK directory" >&2
        return 1
    fi
    mv "$GRAAL_UNPACKED" "$JDKS_DIR/$( basename "$GRAAL_UNPACKED" )"
    rm -rf "$GRAAL_TMP"

    for probe in "$JDKS_DIR"/graalvm-community-*"$GRAAL_VERSION"*/bin/java \
                 "$JDKS_DIR"/graalvm-community-*"$GRAAL_VERSION"*/Contents/Home/bin/java; do
        [ -x "$probe" ] && { note_toolchain "$probe"; return 0; }
    done
    echo "field: provisioned GraalVM $GRAAL_VERSION but found no bin/java under $JDKS_DIR" >&2
    return 1
}

ensure_semantic_sidecar() {
    if [ "$AFIELD_CODE_USE_SEMANTIC_SIDECAR" = "off" ] || [ "$AFIELD_CODE_USE_SEMANTIC_SIDECAR" = "false" ]; then
        return
    fi
    if [ ! -d "$DIR/tools/code-use-semantic-sidecar" ]; then
        return
    fi

    if [ -n "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL" ]; then
        STATUS="$(sidecar_status "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")"
        if [ "$STATUS" = "ok" ]; then
            return
        fi
        echo "Configured code-use semantic sidecar is $STATUS for this checkout; starting a checkout-local sidecar."
    fi

    SIDECAR_PORT="$(choose_sidecar_port)"
    URL="http://127.0.0.1:$SIDECAR_PORT"
    STATUS="$(sidecar_status "$URL")"
    export AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL="$URL"

    if [ "$STATUS" = "ok" ]; then
        echo "Using code-use semantic sidecar at $URL"
        return
    fi

    LOG_DIR="$DIR/build/code-use-semantic-sidecar"
    LOG_FILE="$LOG_DIR/sidecar-launcher.log"
    mkdir -p "$LOG_DIR"
    echo "Starting code-use semantic sidecar at $URL for $DIR"
    echo "Sidecar log: $LOG_FILE"
    if [ -n "$FIELD_TOOLCHAIN_ARG" ]; then
        nohup ./gradlew "$FIELD_TOOLCHAIN_ARG" -p tools/code-use-semantic-sidecar run --args="--port $SIDECAR_PORT --workspace $DIR" >> "$LOG_FILE" 2>&1 < /dev/null &
    else
        nohup ./gradlew -p tools/code-use-semantic-sidecar run --args="--port $SIDECAR_PORT --workspace $DIR" >> "$LOG_FILE" 2>&1 < /dev/null &
    fi
}

build_field() {
    # Always build before launching so a fresh clone (and every edit-and-relaunch) actually runs
    # current code. `jvmJar` is the one target that does it all: it compiles Kotlin/JVM, runs the JS
    # webpack (jsBrowserDevelopmentWebpack), and bundles afield.mjs into the served static/ resources.
    # `dumpFieldLaunch` then writes the java+classpath+electron launch spec. Both are incremental, so
    # this is a quick up-to-date check once built. Set AFIELD_SKIP_BUILD=1 to skip when you know the
    # build is current. Gradle output is shown (not -q): the first build compiles the whole project.
    if [ "${AFIELD_SKIP_BUILD:-0}" = "1" ] && [ -f build/field-launch/args ]; then
        echo "field: AFIELD_SKIP_BUILD=1 — skipping build" >&2
        return 0
    fi
    echo "field: building (JVM + JS; the first run compiles everything and can take a few minutes)…" >&2
    BUILD_OK=0
    if [ -n "$FIELD_TOOLCHAIN_ARG" ]; then
        ./gradlew "$FIELD_TOOLCHAIN_ARG" jvmJar dumpFieldLaunch || BUILD_OK=1
    else
        ./gradlew jvmJar dumpFieldLaunch || BUILD_OK=1
    fi
    if [ "$BUILD_OK" != "0" ]; then
        echo "field: BUILD FAILED — fix the errors above, then re-run ./field.sh" >&2
        return 1
    fi
}

launch_electron_when_ready() {
    # Background helper: wait for the backend to serve, then open the Electron shell against it.
    ELECTRON_BIN="$(cat build/field-launch/electron 2>/dev/null || true)"
    { [ -n "$ELECTRON_BIN" ] && [ -x "$ELECTRON_BIN" ]; } || return 0
    i=0
    while [ "$i" -lt 480 ]; do   # ~120s
        if command -v curl >/dev/null 2>&1; then
            case "$(curl -sk --max-time 2 "https://localhost:$PORT/health" 2>/dev/null)" in *ok*) break ;; esac
        else
            [ "$i" -ge 20 ] && break
        fi
        sleep 0.25
        i=$((i + 1))
    done
    APP_URL=""
    command -v curl >/dev/null 2>&1 && APP_URL="$(curl -sk --max-time 2 "https://localhost:$PORT/app-url" 2>/dev/null || true)"
    [ -n "$APP_URL" ] || APP_URL="https://localhost:$PORT/index.html"
    set --   # collect per-OS electron args (may be empty)
    if [ -f build/field-launch/electron-args ]; then
        while IFS= read -r earg; do [ -n "$earg" ] && set -- "$@" "$earg"; done < build/field-launch/electron-args
    fi
    cd "$(cat build/field-launch/electron-dir)" || return 0
    APP_URL="$APP_URL" WITH_DEVTOOLS="${WITH_DEVTOOLS:-0}" exec "$ELECTRON_BIN" "$@" .
}

run_field() {
    ensure_lfs_content || exit 1
    ensure_gradle_jvm || exit 1
    ensure_graalvm_toolchain || exit 1
    ensure_semantic_sidecar
    build_field || exit 1

    PORT="${PORT:-9092}"; export PORT
    export AFIELD_PORT_FALLBACK_COUNT="${AFIELD_PORT_FALLBACK_COUNT:-1}"

    # Snapshot the terminal's exact settings BEFORE the JVM (Mordant) flips it into raw/alt mode, so on
    # exit we can put back precisely what the user had — not a forced canonical default. `stty -g` emits
    # a machine-readable dump that `stty <dump>` restores verbatim; that's strictly better than `stty
    # sane`, which resets erase/kill/flow-control/etc. to hard-coded values the user may not use.
    # The `2>/dev/null` has to be on a group rather than on `stty` itself: with no controlling
    # terminal (an agent's shell tool, a launchd job, `< /dev/null`) it is the *redirection* that
    # fails, and the shell reports that in its own voice — "field.sh: line 492: /dev/tty: Device not
    # configured" on bash, "cannot open /dev/tty" on dash, "run_field:14: device not configured" on
    # zsh — so a redirection on stty catches nothing. Suppressing it inside the group covers all five
    # shells this script has to satisfy (see tools/test_field_sh_launcher.py).
    SAVED_STTY="$( { stty -g </dev/tty; } 2>/dev/null || true )"

    JAVA_BIN="$(cat build/field-launch/java)"
    # Backend in the background but still attached to THIS terminal — including stdin, so terminal-size
    # detection (Mordant runs `stty`, which reads stdin) sees the real rows/cols and the scroll region
    # uses the whole window. This script runs without job control, so the backgrounded backend stays in
    # the foreground process group and can hold the tty without SIGTTIN. fd 1 stays the tty -> the pinned
    # console renders. The Electron shell (a GUI, no tty needed) runs alongside; that's how they coexist.
    "$JAVA_BIN" "@build/field-launch/args" "$@" &
    BACKEND_PID=$!

    launch_electron_when_ready &
    ELECTRON_PID=$!

    # Ctrl-C / TERM stop both; and if either exits on its own, stop the other. On EXIT, guarantee the
    # terminal is usable again (restore stty, mouse off, drop the scroll region) even if the backend was
    # hard-killed and its JVM shutdown hook never ran. We deliberately do NOT touch the alternate screen
    # buffer: the console runs in the primary buffer, so the final frame — pinned header + the tail of the
    # scrolling log — stays in scrollback on exit instead of being wiped. Emitting these outside a scroll
    # region / with mouse tracking already off is a harmless no-op.
    restore_terminal() {
        [ -t 1 ] || return 0
        if [ -n "$SAVED_STTY" ]; then
            stty "$SAVED_STTY" </dev/tty 2>/dev/null            # put back the exact pre-launch settings
        else
            stty sane </dev/tty 2>/dev/null                     # fallback: no snapshot -> canonical reset
        fi
        printf '\033[?1000l\033[?1006l\033[r' 2>/dev/null       # mouse off, drop scroll region
    }
    trap 'restore_terminal' EXIT
    trap 'kill "$BACKEND_PID" "$ELECTRON_PID" 2>/dev/null' INT TERM
    while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$ELECTRON_PID" 2>/dev/null; do
        sleep 0.5
    done
    kill "$BACKEND_PID" "$ELECTRON_PID" 2>/dev/null
    wait "$BACKEND_PID" 2>/dev/null
}

# Function to run Field4 in a new terminal
run_in_terminal() {
    # No `local` here: this script is #!/bin/sh, and `local` is not in POSIX. ksh93 has no such
    # builtin and prints "field.sh: line NNN: local: not found" on every double-click launch.

    # Try different terminal emulators in order of preference
    if command -v gnome-terminal >/dev/null 2>&1; then
        gnome-terminal -- bash -c "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh $WORKSPACE_RELAUNCH_ARGS; echo ''; echo 'Press Enter to close...'; read"
    elif command -v konsole >/dev/null 2>&1; then
        konsole --hold -e bash -c "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh $WORKSPACE_RELAUNCH_ARGS"
    elif command -v xterm >/dev/null 2>&1; then
        xterm -hold -e "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh $WORKSPACE_RELAUNCH_ARGS"
    elif command -v x-terminal-emulator >/dev/null 2>&1; then
        x-terminal-emulator -e "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh $WORKSPACE_RELAUNCH_ARGS; read -p 'Press Enter to close...'"
    else
        # No terminal found, try to run directly (may not show output). "$@" matters here and used
        # not to be passed: none of the four emulators above exists on macOS, so this *is* the
        # macOS no-tty path, and without the arguments the script printed "field: workspace X (from
        # the command line)" and then launched a backend with no --workspace at all — which opened
        # whatever ~/.afield/last-workspace.txt happened to say. Announcing one workspace and
        # opening another is the exact confusion --print-workspace was added to end.
        run_field "$@"
    fi
}

# A coding agent's shell tool has no tty on stdin either, so it looks just like a Finder/Explorer
# double-click to the check below — without this, run_in_terminal pops a detached GUI terminal
# window that the agent can neither read nor close. Sense known agent CLIs (env vars they set on
# their own subprocesses) and treat that the same as AFIELD_FIELD_SH_NO_TERMINAL=1. Best-effort:
# CLAUDECODE/CLAUDE_CODE_ENTRYPOINT are documented Claude Code markers; the CODEX_SANDBOX* vars are
# undocumented internals Codex sets only when it happens to sandbox the command, so this won't catch
# every Codex invocation — see AGENTS.md.
running_under_coding_agent() {
    [ -n "$CLAUDECODE" ] && return 0
    [ -n "$CLAUDE_CODE_ENTRYPOINT" ] && return 0
    [ -n "$CODEX_SANDBOX" ] && return 0
    [ -n "$CODEX_SANDBOX_NETWORK_DISABLED" ] && return 0
    return 1
}

# Check if we're running in a terminal
if [ -t 0 ] || [ "$AFIELD_FIELD_SH_NO_TERMINAL" = "1" ] || running_under_coding_agent; then
    # Running in a terminal (or a coding agent's shell tool), just execute
    run_field "$@"
else
    # Not in a terminal (likely double-clicked), open one
    run_in_terminal "$@"
fi
