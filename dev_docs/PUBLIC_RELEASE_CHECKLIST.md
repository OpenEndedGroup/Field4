# Public Release Checklist

This checklist tracks repository cleanup work needed before the first public
source release.

## Repository Hygiene

- Decide whether the remaining untracked source-like paths should be committed,
  moved to external packages, or removed from the release workspace:
  - `src/commonMain/resources/extensions/`
  - `templates/note/`
- Keep generated docs output (`public/field4/`) and local Field browser state
  out of commits.

## Legal and Distribution

- Add source/package license identifiers where appropriate.
- Confirm redistribution terms for bundled jars, native libraries, and browser
  assets.
- Decide how to distribute `libs/libken_ceres_jvm.dylib`; it is referenced by
  the Gradle run configuration and Ceres smoke tasks, but is currently local and
  macOS arm64 only.
- Document which native libraries are optional and which are required to run the
  default app.
- Decide whether generated/offline documentation archives belong in releases,
  GitHub release assets, or a separate download area.

## User-Facing Documentation

- Verify the quickstart on a fresh macOS checkout.
- Verify the quickstart on at least one Linux environment.
- Verify or clearly mark Windows support expectations.
- Add troubleshooting notes for local HTTPS certificate warnings.
- Add a concise architecture overview for contributors who do not need the full
  agent guide.

## Build and Test

- Run `./gradlew compileKotlinJvm`.
- Run `python3 tools/run_field_tests.py --refresh-catalog`.
- Run `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean`.
- Before tagging, run `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean`
  to regenerate documentation captures from source.
- Record the exact Java, Gradle, Kotlin, and Node versions used for the release
  candidate.

## Release Packaging

- Choose the first public version number.
- Remove or quarantine local crash logs, screenshots, scratch scripts, and
  generated caches from the release workspace.
- Create a short changelog entry describing the initial public source release.
- Smoke-test the release archive or GitHub checkout from a clean directory.
