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
.\gradlew.bat runDesktop
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
.\gradlew.bat runDesktop
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

Build the documentation site without regenerating screenshots:

```bash
cd public
FIELD_DOCS_RUNNER_MODE=never uv run --project .. --group docs mkdocs build
```

The consolidated testing guide is `dev_docs/testing/TESTING_STRATEGY.md`.

## License

Field 4 is licensed under the GNU Affero General Public License, version 3.0.
See `LICENSE`.

## Repository Map

- `src/commonMain`: shared RPC payloads and message dispatch
- `src/jvmMain`: Ktor server, live-code execution, persistence, rendering, and
  runtime support
- `src/jsMain`: browser UI, canvas/editor integration, output areas, and
  commands
- `public/docs`: MkDocs source for the user documentation
- `metadata_store` and `templates`: example documents and document templates
- `tools`: automation utilities for Agents, including the Field backdoor test runner
- `dev_docs`: internal design notes and agent-facing development notes

See `AGENTS.md` for a deeper repo guide and automation workflow.
