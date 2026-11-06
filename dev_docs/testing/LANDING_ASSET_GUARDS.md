# Landing guards for new transcripts and docs asset sizes

Two file-only guards added on 2026-11-06. Each runs in about a second without a JVM, is a task in
the lander's `GUARDS` (`tools/check_staged_generated_artifacts.py`), is a `CHECKS` entry in
`tools/land_precheck.py`, and is a dependency of `check` and `docsGates`.

| guard | Gradle | precheck key | selected by |
| --- | --- | --- | --- |
| transcript registration | `checkTranscriptRegistration` | `transcript-registration` | a top-level `dev_docs/agents/transcripts/*.yaml`, `public/docs/agent_transcripts.md`, `DemoTranscriptExecSmokeTest.kt`, `AgentTranscriptMaterializer.kt` |
| docs asset size budget | `checkDocAssetBudget` | `doc-asset-budget` | a `.png`/`.mp4`/`.webm`/`.gif` under `public/docs/assets/`, the exceptions register, `tools/check_doc_assets.py` |

## Transcript registration

`tools/check_transcript_registration.py`. On 2026-11-05 `gpu_physarum.yaml` landed in three
commits because each register it belongs in was found by a different guard at a different time:
the `DemoTranscriptExecSmokeTest` classification (1269001df, a smoke test no landing runs), then
the index entry, starting-point bundle and `GUARD_CORPORA.md` counts (444938ec9).
`AgentTranscriptIndexGuard` (`checkAgentTranscriptIndex`) does check both the entry and the
classification, but it checks the classification only for transcripts that already have an
entry, so a bare new transcript showed one gap, and the second appeared only after the first was
fixed. This checker reports every gap at once and prints the line to add:

- **fails** with no `### <name>` heading on `public/docs/agent_transcripts.md`;
- **fails** with no `"<name>"` in `EXECUTE`, `COMPILE_ONLY` or `KOTLINJS`, and also on a classification
  naming no transcript;
- **reminds** about a missing `public/docs/assets/agent_transcripts/<name>/` bundle (unless the name is
  in `AgentTranscriptMaterializer.EXCLUDE`) and stale `dev_docs/GUARD_CORPORA.md` counts. The lander
  regenerates both, so they only fail with `--strict-bundles` / `--strict-corpora`.

It reads the Kotlin sets textually, skipping comments. If a set is renamed, the checker fails and
says so; it does not treat the renamed set as empty.

## Docs asset size budget

`tools/check_doc_assets.py --budget-only`, which also runs as part of the full
`check_doc_assets.py` pass that every docs build makes. Measured on 2026-11-06 over the 1716 tracked
PNGs under `public/docs/assets/`: median 0.30 MB at 1920x1080, p90 1.15 MB, p95 1.9 MB, max 47.9 MB
at 6528x3672. Over the 217 videos: median 0.46 MB, p90 4.7 MB. The budget sits just above p95:

- PNG: at most 2 MiB **and** at most 3840x2160 pixels (8.3 Mpx);
- video (`.mp4`, `.webm`, `.gif`): at most 4 MiB.

The 188 assets over budget that day (164 PNG, 24 video) are listed by exact path in
`dev_docs/testing/doc_asset_budget_exceptions.json`, each with its size and dimensions. The register
is enforced in both directions, like `expected_test_failures.json`: an unlisted asset over budget
fails; an entry whose asset now fits, or is no longer tracked, fails until the entry is deleted; an
entry with a wildcard fails; and a registered asset may not grow past its recorded bytes or
dimensions.

### Making a capture fit

Do not resize a committed capture by hand, and do not register a new asset. Recapture it:

- `max-width="1600"` on the `diffstatic_runner` fence downscales the finished still after
  `clip=`/`trim=` (Lanczos, optimised PNG), so the result is still the real capture. This is the
  path for `capture="native-window"`, whose size is the renderer's window and not the capture page.
  It is refused on `capture="box"` stills (their pixels are `scale=`) and on `video=`. Setting it
  changes the fence's options digest, so the next incremental build recaptures that figure.
- A coarser render target (6291b6ff9 took physarum-gpu from 2.6 MB to 0.9 MB with a 512-high trail),
  a tighter `clip=`, `trim=true`, or a shorter, lower-fps video.

## Tests

`tools/test_check_transcript_registration.py`, `tools/test_check_doc_assets.py` (`SizeBudgetTest`),
`tools/test_docs_runner_max_width.py`, and the selection tests in `tools/test_land_precheck.py` and
`tools/test_check_staged_generated_artifacts.py`. Each guard has a positive control (a constructed
violation: an unregistered `gpu_physarum`, a 2.6 MB still, a 6528x3672 still, a 4 MiB+ video, a
stale/wildcard/grown register entry) and a negative control (the committed tree is clean).
