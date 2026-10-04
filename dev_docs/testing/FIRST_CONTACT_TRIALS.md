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
| Ubuntu 24.04 x86-64 | Pending participant | Passed server-only source launch in [run 37200109317](https://github.com/OpenEndedGroup/Field4/actions/runs/37200109317) |
| Windows x64 | Pending participant | Initial run failed at `Get-FileHash`; SHA-256 repair is on this branch. Recheck [37201214051](https://github.com/OpenEndedGroup/Field4/actions/runs/37201214051) pending. |
| Apple-silicon macOS | Pending participant | Passed server-only source launch in [run 37200109317](https://github.com/OpenEndedGroup/Field4/actions/runs/37200109317) |

Do not change "Pending participant" to pass on the strength of CI. A developer-led session after
help is useful diagnostic evidence, but does not retroactively become an unaided success.

The second Windows attempt (37200741034) failed earlier, with an HTTP 500 downloading the Gradle
distribution. The wrapper now allows two retries. Its Linux/macOS jobs were superseded by the
third attempt, not counted as failures or passes. The first run's Linux and Mac successes are
server-only automated evidence; they do not establish desktop behavior or a human's elapsed time.
