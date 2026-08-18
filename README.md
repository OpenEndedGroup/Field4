# Field 4

Field 4 is a Kotlin-based live-coding environment for graphics, exploratory
data visualization, and new media art.

Project site and user documentation: https://openendedgroup.com/field4

## Status

Field 4 is being prepared for its first public source release. Expect some
sharp edges while the repository is being cleaned up, especially around native
graphics/audio dependencies and developer tooling.

## Requirements

- macOS, Linux, or Windows
- A shell that can run the Gradle wrapper
- **Git LFS, installed before you clone.** `libs/kotlin-compiler-embeddable-*.jar`
  (~180MB) is stored with LFS. Clone without it and Git silently leaves a 134-byte
  text pointer in the jar's place; the build then fails partway through
  `:compileKotlinJvm` with `zip END header not found`, which does not obviously
  point back here. Install it (`apt install git-lfs`, `brew install git-lfs`, or
  the Windows Git installer's LFS option) and run `git lfs install` once per
  machine. If you have already cloned without it, you do not need to re-clone —
  install Git LFS and run `git lfs pull`.
- Internet access on the first build so Gradle can download toolchains and
  dependencies
- A browser that can accept the local development HTTPS certificate

## Quickstart

On macOS and Linux:

```bash
./field.sh
```

On Windows:

```powershell
.\field.ps1
```

This is the one command you need: it builds Field (compiling the Kotlin/JVM
backend and the JavaScript front end via the Gradle wrapper), then launches the
backend and the Electron window together.

The **first run compiles the whole project and downloads large native
dependencies** (LWJGL graphics/audio, Lucene, ONNX Runtime, and others), so
expect it to take several minutes and a good chunk of bandwidth. Subsequent
launches are incremental and start in a few seconds. If the build fails, the
script stops and prints the Gradle errors instead of launching a stale build —
fix them and re-run.

The backend runs in the terminal you launched from, with a live status console
pinned at the top (startup progress, frame rate, GPU uploads, compile/evaluate
timings, and front-end network traffic); everything else scrolls below it. You
can scroll back through the log with PageUp/PageDown or the mouse wheel, and
press End to return to following the latest output.

Once you know your build is current, you can skip the build check for a faster
relaunch:

```bash
AFIELD_SKIP_BUILD=1 ./field.sh
```

```powershell
$env:AFIELD_SKIP_BUILD = "1"; .\field.ps1
```

## OpenAI Features

Some experimental AI-assisted editing, documentation search, and autocomplete
features use the OpenAI API. Field reads the standard `OPENAI_API_KEY`
environment variable through the OpenAI client libraries.

Create an API key in the OpenAI Platform dashboard, then export it before
starting Field. On macOS and Linux:

```bash
export OPENAI_API_KEY="sk-..."
./field.sh
```

On Windows PowerShell:

```powershell
$env:OPENAI_API_KEY = "sk-..."
.\field.ps1
```

For a persistent local setup, add the export line to your shell profile, such
as `~/.zshrc`, or to your PowerShell profile, and restart your terminal. Do
not commit API keys to the repository.

Optional model override environment variables:

- `AFIELD_AI_MACRO_MODEL`
- `AFIELD_KOTLIN_AUTOCOMPLETE_MODEL`
- `AFIELD_SHADER_AUTOCOMPLETE_MODEL`
- `AFIELD_GLSL_AUTOCOMPLETE_MODEL`
- `AFIELD_DOCS_SEARCH_MODEL`
- `AFIELD_DOCS_SEARCH_FALLBACK_MODEL`
- `AFIELD_COMPLETION_DOC_MODEL`

## Workspace

Field documents are stored on disk in a workspace directory. To run with a
specific workspace:

```bash
./gradlew runDesktop --args="--workspace /path/to/AFieldWorkspace"
```

The default development server port is `9092`. Override it with `PORT`:

```bash
PORT=9477 ./gradlew runDesktop
```

## Developer Checks

Compile the JVM sources:

```bash
./gradlew compileKotlinJvm
```

Run the Field smoke-test catalog:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

Run browser UI regressions:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

Browser screenshots and UI regressions use Field's JVM/server-owned Playwright
through the backdoor. The Python `playwright` package is not required in the
repo `uv` environment for these checks.

Build the documentation site without regenerating screenshots:

```bash
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

That one command is the supported Zensical path: `uv` resolves the docs
dependencies from `pyproject.toml`/`uv.lock`, `tools/build_zensical_docs.py`
preprocesses Field's runnable docs fences, and the wrapper then invokes
Zensical. Do not create a separate virtualenv or install Zensical
packages by hand.

Regenerate every screenshot and video through a fresh Field-backed docs run:

```bash
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

That run starts (or reuses) this checkout's completion sidecar on the same port
`./field.sh` uses, because completion figures otherwise capture the K1 fallback
rather than the default backend — a `capture="ui"` completion fence now fails if
Field's log says its completion fell back. `FIELD_DOCS_RUNNER_SIDECAR=off` skips
the sidecar, at the cost of failing those fences.

The consolidated testing guide is `dev_docs/testing/TESTING_STRATEGY.md`.

## License

Field 4 is licensed under the GNU Affero General Public License, version 3.0 or later.
See `LICENSE`.

## Repository Map

- `src/commonMain`: shared RPC payloads and message dispatch
- `src/jvmMain`: Ktor server, live-code execution, persistence, rendering, and
  runtime support
- `src/jsMain`: browser UI, canvas/editor integration, output areas, and
  commands
- `public/docs`: Zensical source for the user documentation
- `metadata_store` and `templates`: example documents and document templates
- `tools`: automation utilities for Agents, including the Field backdoor test runner
- `dev_docs`: internal design notes and agent-facing development notes

See `AGENTS.md` for a deeper repo guide and automation workflow.
