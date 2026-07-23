# Code-use semantic sidecar

Standalone K2 Analysis API worker for Field's code-use telemetry.

Normally, run Field through the repository launcher:

```bash
./field.sh
```

On Windows, use the matching PowerShell launcher:

```powershell
.\field.ps1
```

`field.sh` and `field.ps1` check whether a sidecar is already serving this
checkout, start one if needed, export `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL`,
and then start Field. They derive a stable local port from the checkout path so
generated worktrees get their own sidecar process.

To run the sidecar manually:

```bash
./gradlew -p tools/code-use-semantic-sidecar run --args="--port 17841 --workspace $PWD"
```

Then start Field with:

```bash
AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL=http://127.0.0.1:17841 ./field.sh
```

Disable launcher-managed sidecar startup with:

```bash
AFIELD_CODE_USE_SEMANTIC_SIDECAR=off ./field.sh
```

On Windows:

```powershell
$env:AFIELD_CODE_USE_SEMANTIC_SIDECAR = "off"
.\field.ps1
```

The sidecar serves:

- `GET /health`
- `POST /analyze`
- `POST /complete`

`POST /analyze` uses the same JSON request/response contract documented in
`dev_docs/agents/CODE_USE_TELEMETRY.md`. It is intentionally outside the main
Field Gradle project because the standalone Analysis API pulls an IntelliJ-style
classpath that should not be mixed into the live-coding runtime.

`POST /complete` is documented in `dev_docs/completion_aa_sidecar.md`. In addition to ordinary
inherited preamble context, language runtimes may send JVM-analysis-safe file-scope `declarations`
for synthetic APIs such as Kotlin/JS remote-head externals.

Implementation notes:

- The first worker is source-first. Field sends `src/jvmMain/kotlin`,
  `src/commonMain/kotlin`, and `src/personal/kotlin` by default, but leaves
  `classpathRoots` empty unless the caller explicitly opts in.
- The small compatibility classes under `src/main/java/com/intellij/...` and
  `src/main/kotlin/kotlinx/coroutines/internal/intellij/...` bridge ABI
  differences between the standalone Analysis API artifacts and the embedded
  compiler/IntelliJ classes used by the sidecar. Keep them local to this
  out-of-process tool.
- Field-style snippets are wrapped in a synthetic function before analysis so
  statement-oriented box code can be resolved by the regular Kotlin source
  analysis APIs.
