# Public Release Checklist

This is a release-readiness checklist, not a description of current support. Items remain
open until a named release candidate records evidence for them.

## Repository Hygiene

- `src/commonMain/resources/extensions/` is retained in the repository: its tracked
  `remote_ar` files provide the RemoteJS/WebXR browser head used by `remoteAr()`.
  Its vendored A-Frame and Tone.js packages include their upstream licenses and
  are maintained by `tools/vendor_remote_ar_runtime.py`. A local ignored `.vscode/`
  directory is editor configuration and is not part of the release.
  On 2026-11-04, the `remote-ar-head` and `remote-ar-embed` browser regressions
  passed against checkout `df169880f`; this did not test a physical XR device.
- Decide whether `templates/note/`, if present in the release workspace, should be
  committed, moved to an external package, or removed.
- Keep generated docs output (`public/field4/`) and local Field browser state
  out of commits.

## Legal and Distribution

- Add source/package license identifiers where appropriate.
- Confirm redistribution terms for bundled jars, native libraries, and browser
  assets.
- The Ceres solver ships as source, not as a binary: `libs/libken_ceres_jvm.dylib` is
  gone and `./gradlew buildCeresRedistributable` builds a self-contained ABI v3 dylib
  (8.0 MB, `libc++`/`libSystem` only, sparse Cholesky) from pinned Ceres 2.2.0 /
  Eigen 3.4.0 source, asserting its own load commands. It is an optional extra, so a
  release needs to *say* that solver features require that one build step, and that it
  is macOS-arm64 only. See `dev_docs/CERES.md`.
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
- Run `uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog`.
- Run `FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean`.
- Before tagging, run `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --clean`
  to regenerate documentation captures from source.
- Record the exact Java, Gradle, Kotlin, and Node versions used for the release
  candidate.

## Release Packaging

- Choose the release version number.
- Remove or quarantine local crash logs, screenshots, scratch scripts, and
  generated caches from the release workspace.
- Create a short changelog entry describing the initial public source release.
- Smoke-test the release archive or GitHub checkout from a clean directory.
