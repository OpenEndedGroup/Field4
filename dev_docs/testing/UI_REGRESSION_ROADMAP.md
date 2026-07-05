# UI Regression Roadmap

Canonical testing map: `dev_docs/testing/TESTING_STRATEGY.md`. This document is
the long-term checklist and detailed reference for browser-level UI regressions.

This is the long-term checklist for browser-level Field UI regressions run by
`tools/run_ui_regressions.py`. The goal is to protect workflows that are hard to
cover with snippet-only smoke tests, and to keep representative screenshots for
human and agent inspection.

Run the suite from the repository root with the repo uv project:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py --port 9490 --timeout 45
```

Use the `backdoor` dependency group. Do not run this runner with a random system
Python; it depends on the same websocket/Playwright-facing Python environment as
`tools/field_backdoor.py`.

Run one test while iterating:

```bash
uv run --project . --group backdoor python tools/run_ui_regressions.py \
  --id macro-transcript-panel --port 9680 --timeout 45
```

## Runtime Telemetry

- Non-JSON mode prints `RUN [i/n] <test> attempt <n> port=<port>` before each backdoor invocation.
- While a test is still running, the runner prints heartbeat lines every 15 seconds by default.
- Tune the heartbeat with `--heartbeat-interval <seconds>`.
- `PASS` / `FAIL` lines print immediately after each test completes, including retained screenshot paths.
- `--json` suppresses progress chatter so stdout remains machine-readable.

## Screenshot Artifact Policy

- Store artifacts under `build/ui-regressions/artifacts/<test-id>/`.
- Keep the most recent 5 passing screenshots per test in `passes/`.
- Keep `latest-pass.png` for quick inspection of the newest passing state.
- Keep `latest-failure.png` when a test fails and a screenshot was captured.
- Write structured result data to `build/ui-regressions/results.json`.
- Supplemental screenshots, such as the history panel whole-window capture, are retained as `latest-<label>.png`.

## Troubleshooting Notes

- If a test reports `backdoor returned no JSON`, inspect the newest `.field-backdoor/logs/*.managed-server.log`; this usually means Field failed before the UI test started.
- A JVM compile failure blocks the whole browser suite because the backdoor starts Field through Gradle.
- The suite increments ports from the base `--port`, one per selected test, to avoid stale sessions.
- The history and macro/transcript tests use named temporary documents because those panels are document-path backed.
- Screenshot failures are treated as real test failures even if the DOM assertion passed.

## Sections

1. [implemented] Command Palette Smoke Sweep
   - Run representative command categories through the Field command palette.
   - Cover document commands, selected-box/property commands, and Monaco editor actions.
   - Assert the palette closes, focus lands in a sensible place, and console errors stay empty.

2. [implemented] Editor Open/Edit/Sync
   - Create a box, open Monaco, edit a property, blur/run/reopen.
   - Assert editor contents and box properties stay in sync.

3. [implemented] Output Area Basics
   - Exercise `display("channel", html)`, channel replacement, embedded scripts, and nonblank screenshots.

4. [implemented] Controllers Matrix
   - Cover static lines, editable lines, and native `controlGroup` widgets.
   - Assert `/controllers?page=outputs` and `/controllers?page=controls` both render useful content.

5. [implemented] Selection + Screenshot Backdoor
   - Create multiple boxes, select one, open editor, and capture box/window/editor screenshots.
   - Assert selection state and screenshot files are non-empty.

6. [implemented] History Panel
   - Open the history view after creating document changes.
   - Assert entries render, selection/version interactions do not throw, and closing returns to normal Field UI.

7. [implemented] Macro / Transcript Panel
   - Open the macro recorder/transcript panel after simple actions.
   - Assert transcript rows, selection controls, and feedback states render.

8. [implemented] Inline Visualizers
   - Exercise editor-mounted visualizers such as `histogram`, `history`, `stats`, and `pianoRoll`.
   - Assert widgets exist and screenshots are nonblank.

9. [implemented] Focus / Keyboard Contract
   - Assert Escape closes transient panels, Enter accepts palette rows, arrows move the active row, and editor focus returns.

## Current Implemented Coverage

- `command-palette-action-this-binding`
  - Protects Monaco action invocation from losing its original `this` binding.
- `command-palette-smoke-sweep`
  - Runs document, selected-property, and editor action categories through the Field command palette.
- `editor-open-edit-sync`
  - Verifies Monaco edits sync to box properties and survive property switches.
- `insert-stack-marker-command`
  - Verifies the "Insert Stack Mark" palette command mutates Monaco and returns focus.
- `output-area-basics`
  - Exercises `display(...)` replacement, embedded script execution, visible output DOM, and screenshot capture.
- `controllers-editable-lines-output`
  - Covers `lines(foo, editable = true)` in `/controllers?page=outputs`.
- `controllers-static-lines-output`
  - Covers static `lines(...)` rendering in `/controllers?page=outputs`.
- `controllers-control-group`
  - Covers native `controlGroup` widgets in `/controllers?page=controls` and `/controllers?page=outputs`.
- `selection-screenshot-backdoor`
  - Verifies selected box/editor/window screenshot agreement and retained supplemental captures.
- `history-panel`
  - Opens the document history iframe, waits for version data, interacts with controls, and closes it.
- `macro-transcript-panel`
  - Seeds transcript operations and a document macro, then verifies rows, selection, save-selected, browser lists, preview, close/reopen, and screenshot capture.
- `inline-visualizers-editor`
  - Launches documented inline histogram/history/stats/piano-roll code, asserts Monaco SVG view zones render, and retains an editor screenshot.
- `focus-keyboard-contract`
  - Exercises palette ArrowUp/ArrowDown, Escape focus restoration, and Enter acceptance for an editor action.
