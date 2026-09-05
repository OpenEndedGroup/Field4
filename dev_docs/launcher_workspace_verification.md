# Verifying that `field.sh` opens the workspace it says it will

`tools/test_field_sh_launcher.py` proves what `field.sh` *parses* and what argv it hands to the
backend, under all five shells, against a fake `java` that records its arguments. What it cannot
prove is the sentence a user actually cares about: **Field started, and the workspace it opened is
the one the launcher announced.** That needs a real backend, and it is the check bafa9ee5f's
`--print-workspace` exists to make answerable but that nobody had run.

This page is that check, as performed on 2026-08-26, and the recipe for repeating it in about five
minutes.

## Why the launcher's promise can be a lie

`Server.kt:734` is the whole mechanism:

```kotlin
val home = option("workspace") { readPersistedWorkspacePath() ?: defaultWorkspacePath() }
```

The `--workspace` argument wins; failing that the backend reads `~/.afield/last-workspace.txt`, which
is **machine-wide** — any clone or worktree may have been the last to write it. So if the launcher
prints a workspace on stderr and then fails to pass `--workspace`, the user is told one thing and
shown another, silently, with no error anywhere.

That is exactly what `run_in_terminal`'s fallback did: it called `run_field` with no `"$@"`, and none
of the four X11 terminal emulators it tries first exists on macOS, so that fallback *is* the macOS
no-tty path — an agent's shell tool, a launchd job, `< /dev/null`.

## The measurement

Two launches, same arguments, same identity-verified server, differing only in the launcher script.
`FIXED` is `field.sh` at this branch; `PREFIX` is `git show bafa9ee5f:field.sh` byte for byte.

| | announced on stderr | `currentWorkspace` the backend reports | documents listed |
| --- | --- | --- | --- |
| **FIXED** | `…/live/wsA` | `…/live/wsA` ✅ | 1 (`NIGHTLY_LIVE_PROOF_A`) |
| **PREFIX** | `…/live/wsA` | `/Users/marc/Documents/AFieldWorkspace` ❌ | **493** |

The pre-fix launcher announced a temp directory and opened the developer's real 493-document
workspace. Not a hypothetical: that is the failure, live, on the default path of the platform this is
developed on.

## The recipe

The discriminator has to come from the **server**, not from what you typed. `/api/welcome-docs`
carries `currentWorkspace`, which is `home` above; `defaultDocument` is *not* a sound substitute
because it falls back to the last-opened document and can name the right thing for the wrong reason.

```sh
# 1. two workspaces with uniquely-named documents, so the answer is unmistakable
mkdir -p /tmp/wsA/PROOF_A && echo '{:version 2 :boxes []}' > /tmp/wsA/PROOF_A/metadata.edn

# 2. what the launcher promises, without launching anything
./field.sh /tmp/wsA --print-workspace

# 3. launch on the no-tty path: no AFIELD_FIELD_SH_NO_TERMINAL, stdin closed, agent env cleared
env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT -u CODEX_SANDBOX \
    PORT=9712 AFIELD_SKIP_WORKSPACE_PERSIST=1 AFIELD_CODE_USE_SEMANTIC_SIDECAR=off \
    ./field.sh /tmp/wsA < /dev/null &

# 4. VERIFY THE SERVER IS YOURS before believing a word it says
P=$(lsof -nP -iTCP:9712 -sTCP:LISTEN -t); lsof -a -p "$P" -d cwd -Fn | sed -n 's/^n//p'
curl -sk https://localhost:9712/api/instance-info      # repoRoot + pid must match

# 5. the answer
curl -sk https://localhost:9712/api/welcome-docs | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["currentWorkspace"])'
```

Three rules learned doing it:

- **`AFIELD_SKIP_WORKSPACE_PERSIST=1` is not optional.** Without it a real launch rewrites
  `~/.afield/last-workspace.txt` and re-points every other clone's next bare `./field.sh`. Back the
  file up and check its hash afterwards regardless; `tools/verification_runtime.py` sets this for
  every automated server for the same reason.
- **A sandbox `HOME` does not isolate the backend.** It redirects `field.sh`'s own `$HOME` reads, but
  the JVM's `user.home` came from the launch environment and still resolved to the real home — the
  `PREFIX` run above read the *real* preference file despite `HOME` pointing at a temp directory.
  Plan for the backend to see the real home and protect it with the env var, not with `HOME`.
- **`lsof` on the port is the identity check, and it is not enough on its own.**
  `field_backdoor.py --port` will start its *own* managed server on a free port and tear it down when
  the invocation ends, so "nothing is listening afterwards" does not mean your launch was ever the
  thing being measured. Resolve the listener's `cwd` *and* ask `/api/instance-info` for `repoRoot`,
  and check the reported `pid` equals the listener.
- **`SIGINT` was ignored.** Stopping the backend needed `SIGTERM` after a bounded wait, both times,
  cwd-verified before each signal.

# Verifying that `--document` opens that document

`-d, --document` (2026-08-28) has the same shape of promise and the same shape of hole: the
launcher prints `field: document docA — opening it directly, not the welcome page`, and whether
that sentence is true is decided by the *backend*, not by the argv the launcher hands over.
`tools/test_field_sh_launcher.py` proves the argv and stops there, so the seam is checked here.

The lever is `/app-url`. `launch_electron_when_ready` curls exactly that route and hands the answer
to the Electron shell as `APP_URL`, so whatever it returns is the window the user gets:

```kotlin
val startupDocument = commandLineStartupDocumentName()
    ?: lastOpenedDocumentName()?.takeIf { preferences.autoOpenLastDocumentInDesktop }
```

## The measurement

Three launches of the real backend, on port 9711, against a two-document scratch workspace,
differing only in the `--document` argument. No Electron and no rebuild needed:
`build/field-launch/args` puts `build/classes/kotlin/jvm/main` on the classpath, so
`./gradlew compileKotlinJvm` is enough to be running current code, and `preferSystemBrowser`
defaults to `true`, whose branch opens no window at all.

| launched with | `/app-url` returned |
| --- | --- |
| *(nothing)* | `…/index.html?file=dust_two` |
| `--document docA` | `…/index.html?file=docA` ✅ |
| `--document docB` | `…/index.html?file=docB` ✅ |

The control row is the point of the flag. This machine's Field has
`autoOpenLastDocumentInDesktop` on and `lastFn` at `dust_two`, so a bare launch reopens *that* —
which is the document you would have had to close before going through the welcome page to reach
the one you wanted. Naming one skips both.

```sh
WS=/tmp/verify-ws; mkdir -p "$WS/docA" "$WS/docB"
printf '{:version 2 :boxes []}\n' | tee "$WS/docA/metadata.edn" > "$WS/docB/metadata.edn"

AFIELD_SKIP_WORKSPACE_PERSIST=1 AFIELD_CODE_USE_SEMANTIC_SIDECAR=off AFIELD_HTTP_PORT=off \
    PORT=9711 "$(cat build/field-launch/java)" @build/field-launch/args \
    --workspace "$WS" --document docA &

until curl -sk --max-time 2 https://localhost:9711/health | grep -q ok; do sleep 0.25; done
curl -sk https://localhost:9711/app-url
```

Two things this run reconfirmed, both already rules above: `AFIELD_SKIP_WORKSPACE_PERSIST=1` is not
optional, and identify the process before signalling it — `pgrep -f field-launch/args` matched a
`java` that was **not** any of these three. It was the developer's own Field, on 9092, started
twenty minutes earlier. A free port did not mean an empty process table.

## What is still not verified

`field.ps1`. The `--workspace`/`--document` parsing layer landed there on 2026-08-28 as the
Windows equivalent of `field.sh`'s, and **it has never been executed**: no PowerShell is installed
on this machine, `brew install --cask powershell` wants an interactive `sudo`, and the disk is too
tight for it anyway. What holds it up is therefore reading plus two static gates in
`tools/test_field_sh_launcher.py` — `test_the_powershell_launcher_declares_the_same_options`
(the same flags as `field.sh`, so a flag added to one launcher fails until it reaches the other)
and `test_the_powershell_launcher_stays_readable_by_windows_powershell` (ASCII outside comments and
CRLF throughout, because a BOM-less `.ps1` is read as ANSI by Windows PowerShell 5.1). Nothing
checks that it *runs*. Anyone with a Windows box: `.\field.ps1 --print-workspace`,
`.\field.ps1 -d <a document>`, `.\field.ps1 -d nosuchthing` (expect exit 2), and
`.\field.ps1 <a document> --read-only` (expect the read-only note, and nothing written into the
document's folder) settle it in a minute, and this section should then say so.

The first of those two gates only became what that sentence claims on 2026-09-05, and
`--read-only` is what showed the difference. It reached `field.sh` in 8cabbf40e and `field.ps1`
56 commits later. This gate stayed green across all of them, because it checked a hand-written
list of spellings kept beside the ledger rather than the ledger itself, and so could not fail for
a flag nobody had told it about. What did go red was the *other* gate,
`test_every_launcher_option_is_covered_by_the_ledger` — `field.sh` had grown a `case` arm the
ledger did not name — so `checkPythonTools` was failing that whole time, on the sibling assertion,
which is how this got found. The spellings are now derived from `COVERED_OPTIONS`, so the pair is
chained end to end: `field.sh`'s arms must equal the ledger, and `field.ps1` must name every
spelling in it. A flag cannot land on one launcher alone.

`field.ps1`'s `--read-only` arm is itself unrun, like the rest of that file. Two things about it
are only argued, not observed: that the banner reaches the user (`Write-FieldNote` writes it, as
every other launcher note does), and that `--read-only` still leaves a following bare path
readable as the workspace. The second is a behaviour the old passthrough got *wrong* —
`StartsWith("-")` set `$passthroughSeen`, so `.\field.ps1 --read-only <path>` sent the path to the
backend as an argument instead of resolving it as a workspace, where `field.sh` resolves it. The
explicit arm does not set that flag, matching `field.sh`.

One detail worth knowing while it is unverified: PowerShell keeps the workspace record somewhere
else. `Server.kt`'s `fieldUserConfigDir` puts it under `%APPDATA%\AField` on Windows and
`~/.afield` everywhere else, so `field.ps1` branches on the platform to read the same file the
backend will. Reading the Unix path on Windows would have made the launcher announce a workspace
the backend was not about to open — the exact failure this whole page exists to catch.

The four terminal-emulator branches of `run_in_terminal` (`gnome-terminal`, `konsole`, `xterm`,
`x-terminal-emulator`). None is installed on macOS, so none has ever been executed here. They
re-exec the script with an inner command line built as a *string*, carrying only the workspace and
now the document — so a non-macOS double-click still drops every other passthrough argument.
Deliberately left: the quoting work that would fix it cannot be verified on this machine, and an
unverifiable fix to a quoting bug is worse than a named one.
