# Unaided first-contact trials

Run one human trial each on Ubuntu 24.04 x86-64, Windows x64 and an Apple-silicon Mac.
These are separate from automated CI and the developer's warm-cache Linux run.

## Participant instructions

Use a machine or account that has never run Field. Do not erase another user's caches to simulate
this. Start a stopwatch, read only the public README and installation guide, and install Field.
You may follow their prerequisite/package instructions. Do not ask a developer for help until you
have recorded the point where you got stuck. There is no need to configure AI or Python.

Find and open the first drawing. Launch it, change five petals to eight, inspect the value, stop,
then close and reopen your document to verify the edit was saved. Try the native triangle only if
normal GPU prerequisites are available. Stop Field when finished.

Record downloads using your OS's network usage view/router counter if available. If you cannot
measure traffic, write "not measured"; checkout size and cache growth are disk usage, not network
bytes. Screenshots/recordings are welcome after reviewing them for private information.

## Results form

- Participant alias (no personal contact information):
- OS/version, architecture, RAM, GPU and desktop session:
- Field commit and docs URL/date:
- Fresh machine or new account? Existing Java/Node/Git/LFS/tools:
- Internet connection; measured download bytes, method, or "not measured":
- Stopwatch: prerequisite work / clone / first launch to welcome / first moving drawing:
- Could you identify the next action without assistance?
- Exact first hesitation or failure, including command and first concrete error:
- Did you complete launch → edit → inspect → stop → reopen? Describe what happened:
- Native triangle (pass/fail/not attempted and reason):
- Help received (none, or what changed after asking):
- One thing you expected to be easier:

## Evidence ledger

| Platform | Unaided human trial | Automated source launch |
| --- | --- | --- |
| Ubuntu 24.04 x86-64 | Pending participant | Passed server-only source launch in [run 37284801723](https://github.com/OpenEndedGroup/Field4/actions/runs/37284801723): 14.7 minutes with the Linux CI build profile below. |
| Windows x64 | Pending participant | Passed server-only source launch in [run 37281461833](https://github.com/OpenEndedGroup/Field4/actions/runs/37281461833): 24.2 minutes on Windows Server 2025 x64. |
| Apple-silicon macOS | Pending participant | Passed server-only source launch in [run 37281461833](https://github.com/OpenEndedGroup/Field4/actions/runs/37281461833): 19.2 minutes on macOS 14.8.9 arm64. |

Do not change "Pending participant" to pass on the strength of CI. A developer-led session after
help is useful diagnostic evidence, but does not retroactively become an unaided success.

The second Windows attempt (37200741034) failed earlier, with an HTTP 500 downloading the Gradle
distribution. The wrapper now allows two retries. Its Linux/macOS jobs were superseded by the
third attempt, not counted as failures or passes. The first run's Linux and Mac successes are
server-only automated evidence; they do not establish desktop behavior or a human's elapsed time.

The Windows launcher now passes after repairs to SHA-256 hashing, illegal compiler-output
filenames, PowerShell build-result handling and versioned ARPACK DLL extraction. Both successful
Windows and macOS jobs checked the real launcher, health/API protection, browser assets,
and starter-document copies. They did not exercise the Electron desktop or native rendering.

The recorded launch times include application dependency downloads, compilation and startup;
they exclude repository checkout and the workflow’s prerequisite/helper steps. Download bytes
remain unmeasured. These timings describe hosted CI, not an unaided participant’s experience.

Two later Linux attempts lost their hosted runner before a result or artifact was written
(`Runner received shutdown signal`); they provide no application verdict. The successful Linux
rerun capped the Kotlin compiler heap at 4 GB, used one Gradle worker and disabled parallel
build execution. This is the workflow’s Linux CI profile, not the launcher’s default configuration.
Its live log records disk space and available memory each minute. Resource pressure motivated
the adjustment, but the earlier shutdown logs do not establish its cause.
