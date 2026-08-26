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

## What is still not verified

The four terminal-emulator branches of `run_in_terminal` (`gnome-terminal`, `konsole`, `xterm`,
`x-terminal-emulator`). None is installed on macOS, so none has ever been executed here. They
re-exec the script with an inner command line built as a *string*, carrying only the workspace —
so a non-macOS double-click still drops every other passthrough argument. Deliberately left: the
quoting work that would fix it cannot be verified on this machine, and an unverifiable fix to a
quoting bug is worse than a named one.
