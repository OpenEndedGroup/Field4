# Field4 Quick Reference for AI Agents

For the consolidated testing strategy and command matrix, start with
`dev_docs/testing/TESTING_STRATEGY.md`.

## TL;DR: How to Work with Field4

### Preferred for Running Field Code

If you need to actually run Field code, capture stdout, inspect compile/runtime errors, or verify behavior after changing the codebase, prefer `tools/field_backdoor.py` over editing sidecar files or driving the browser manually.

Minimum hello world:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Hello' \
  --command 'run @last --value "println(\"hello world\")"'
```

Preferred no-escaping variant:

```bash
cat <<'EOF' | uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'new-box --name Hello' \
  --command 'run @last --file -'
println("hello world")
EOF
```

Use this workflow for:
- backend/frontend/runtime verification
- example code smoke tests
- shader or code-property mutation over websocket
- deterministic output/error capture
- temporary `renderer` parent setup for `afield.four` snippets via `--graphics-context`
- opening the real browser DOM for the current scratch session with `--with-playwright` or `playwright-open`

Phase-1 smoke suite:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog
```

That regenerates `build/field-tests/catalog.json` and runs each catalog entry through `uv run --project . --group backdoor python tools/field_backdoor.py --json`.

If a public docs page seems to need a visible "verification" or backdoor command section, check the `FIELD_TEST` catalog first. For example, RectSL user docs are covered by source annotations in `src/jvmMain/kotlin/afield/examples/rectsl/`:
`rectsl.examples.smoke`, `rectsl.language.workout`, and `rectsl.editor-extension.workout`.
Keep those commands in the smoke suite/dev docs, not in rendered user-facing pages.

Graph connection command:

```text
connect <parent-uid> <child-uid> [more-child-uids...]
```

This matches the frontend `g` behavior: first uid is the parent, remaining uid(s) become children.

Playwright command:

```text
playwright-open [--timeout seconds]
editor-open <box> [--property code] [--timeout seconds]
input-key press <key> [--selector <css>] [--wait-ms milliseconds]
```

Prefer the global flag when screenshots or output-area DOM are part of the same run:

```text
--with-playwright
```

Prefer this flag when child test boxes need inherited graphics context from a launched `Renderer`:

```text
--graphics-context
```

That makes the backdoor create and launch a parent box called `renderer`, wait briefly, and auto-connect later `new-box` boxes beneath it so `renderer` is valid in your snippet code. Prefer scratch when you want that setup to stay disposable.

For transcript-driven graphics setup, run `dev_docs/agents/transcripts/graphics_renderer_launch.yaml`, then use:

```text
wait-native-renderer agent_graphics_context --frames 3
```

This checks the native renderer/window path directly. A `println(...)` marker is not enough to prove that a render loop is alive.

That opens or reuses Playwright immediately after the backdoor attaches to its current session, and again after any later `open ...` command. Use `playwright-open` when you intentionally want to defer browser startup.
That Playwright browser is headless by default so it should not steal focus during tests. Set `AFIELD_PLAYWRIGHT_VISIBLE=true` only when you want to watch it.
Screenshot commands wait for page reconnect/render now, but they still cannot capture output DOM that only existed before the browser attached, so prefer `--with-playwright` when the run creates the visual state.
The first run can take longer while Playwright downloads browser binaries.

Screenshot commands:

```text
screenshot-window <path> [--uid <box>] [--with-editor] [--open-outputs]
screenshot-editor <box> <path> [--padding 10]
screenshot-box <box> <path> [--padding 16] [--open-outputs]
native-screenshot <path> [--wait-frames 4]
wait-native-renderer [box] [--frames 3]
```

Use:
- `screenshot-window /tmp/with-editor.png --uid @last --with-editor --open-outputs` for a whole-window capture with Monaco visible.
- `screenshot-window /tmp/without-editor.png --uid @last --open-outputs` for a whole-window capture without Monaco.
- `editor-open @last --property code`, then `screenshot-editor @last /tmp/editor.png --padding 10`, for inline histogram / piano-roll decorations.
- `screenshot-box @last /tmp/box.png --padding 16` for a clipped box image with its outputs opened. `--open-outputs` is accepted, but box screenshots already open outputs internally.

Capture and DOM-probe sharp edges:
- Attach Playwright with `--with-playwright` before the `run` or `launch` that creates output DOM.
- Use `input-key press Escape --selector .afield-palette-input` for focused keyboard contracts. Selector-targeted keys preserve the intended focused control better than page-level key presses.
- Use a fresh scratch session or fresh custom port for each final screenshot/video asset unless you intentionally want several boxes in one canvas.
- Put nontrivial `page-eval` JavaScript in a file and call `page-eval --file <path>`. Use `--body` only when the script contains statements and an explicit `return`.
- Inspect the generated PNG/MP4, not only the JSON `clip`; screenshot clips are CSS pixels and output files may be device-scale.

Use the sidecar file workflow below when you want persistent workspace files on disk.

### Create a New Box
```bash
echo 'println("Hello!")' > my-box_code_.kt
```
✅ Field auto-creates box, assigns position, saves to metadata

### Edit Existing Box
```bash
echo 'println("Updated!")' > existing-box_code_.kt
```
✅ Field reloads, updates UI, persists changes

### Supported File Types

| File Pattern | Purpose |
|--------------|---------|
| `id_code_.kt` | Kotlin code |
| `id_fragment_.glsl` | Fragment shader |
| `id_vertex_.glsl` | Vertex shader |
| `id_metadata_.yaml` | Box metadata/tags |
| `id_customization_.js` | JavaScript |

### File Naming Rules

- Format: `<boxId>_<property>_.<ext>`
- Box ID: lowercase with hyphens (`my-box`, `shader-1`)
- Property: one of the types above
- **Important**: Underscore before dot (`_.`) is required!

### What Happens Automatically

1. **Orphan Detection** (every 5 seconds)
   - Finds files without boxes
   - Creates box with sensible name
   - Assigns non-overlapping position
   - Saves to metadata.edn

2. **Change Detection** (immediate)
   - Detects file modifications
   - Reloads content
   - Syncs to UI
   - Persists changes

3. **Metadata Reconciliation** (on edit)
   - Detects metadata.edn changes
   - Adds new boxes from metadata
   - Removes deleted boxes

### Working with metadata.edn

**Auto-managed** - you usually don't need to touch it!

But if you must:
```clojure
{:version 2
 :boxes [{:id "box-id"
          :properties {:name "Display Name"
                       :frame [x y width height]}}]}
```

### Frame Positioning

Auto-generated frames use grid layout:
- Start: (50, 50)
- Size: 350×250
- Padding: 20px
- Columns: 4

To customize, edit `metadata.edn`:
```clojure
:frame [100.0 100.0 400.0 300.0]  ; [x y width height]
```

### Best Practices

✅ **DO:**
- Use descriptive box IDs: `particle-system`, `physics`
- Create sidecar files, let Field manage metadata
- Wait 5-10 seconds after creating files
- Check Field's logs for confirmation

❌ **DON'T:**
- Manually edit metadata.edn unless necessary
- Use spaces or special chars in box IDs
- Create temp/test files (they become boxes!)
- Expect instant updates (5-15 sec latency)

### Workflow Example

```bash
# 1. Create a shader system
echo 'println("Setting up shader")' > shader-system_code_.kt
echo 'void main() { gl_FragColor = vec4(1.0); }' > shader-system_fragment_.glsl

# 2. Add metadata
cat > shader-system_metadata_.yaml << 'EOF'
tags: [graphics, shader, demo]
description: Basic shader system
EOF

# 3. Wait for Field to detect (5-10 seconds)
# 4. Check Field's UI - boxes appear automatically!
# 5. Edit and iterate as needed
```

### Debugging

**Check logs for:**
```
info,file-watch: Auto-created box: Shader System (shader-system)
info,file-watch: Reloaded external change: code in Shader System
```

**Common issues:**
- Wrong filename format → Won't be detected
- Missing underscore-dot (`_.`) → Won't be parsed correctly
- Unsupported extension → Ignored
- Permission errors → Check file permissions

### Multi-Agent Coordination

- Use **unique box IDs** to avoid conflicts
- **Wait 10 seconds** between bulk operations
- Field has **15-second grace period** for saves
- **Logs show** all file operations

---

## Full Documentation

See [../../AGENTS.md](../../AGENTS.md) for the repo-wide agent guide.
See [FIELD_BACKDOOR.md](./FIELD_BACKDOOR.md) for the websocket backdoor workflow.

## Questions?

Check Field's console output or review `src/jvmMain/kotlin/afield/FileWatchManager.kt`
