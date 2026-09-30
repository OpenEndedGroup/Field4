# Add two-keystroke chord sequences to keybindings

- **Handoff:** `e04ebaeb-3917-49ab-b819-af18d57fc4f0` (project)
- **Filed from:** https://openendedgroup.com/field4/keybindings/
- **Filed by:** agent:claude, 2026-09-30 20:42
- **Close with:** `python tools/docs_notes.py handoff-close e04ebaeb "what happened"`

## The notes that led here

- `ec8712c7` (public/docs/keybindings.md:266): Can we do chords? they are cool, and we are under some pressure here
  > Field has no two-keystroke chords

## What was asked

Note `ec8712c7` on `public/docs/keybindings.md` ("## Limits", the line "Field has no two-keystroke
chords"): "Can we do chords? they are cool, and we are under some pressure here". Marc wants
VS-Code-style two-keystroke chords (`cmd+k cmd+s`) as a real feature.

## Why this is bigger than a docs edit

The page is accurate today; the limit is in code, and it is deliberately refused:

- `src/commonMain/kotlin/keybindings.kt`, `KeySpec.parseDetailed`: a spec with two keys returns
  `null` with the reason "a shortcut is one key with modifiers — Field has no two-keystroke chords".
  `KeyChord` is one key plus three modifier booleans; there is no sequence type.
- `src/jvmMain/kotlin/afield/UserKeybindingsManager.kt`: the generated file header and the
  rejection path (search "two-keystroke chord") explain and log the refusal; tests pin it in
  `src/jvmTest/kotlin/afield/KeySpecTest.kt` and `UserKeybindingsManagerTest.kt`.
- `src/jsMain/kotlin/afield/KeybindingsClient.kt`: one spec becomes both a Monaco keybinding
  (`monacoBinding` → a single `KeyMod|KeyCode` int) and the canvas test (`matchesChord` against one
  `KeyboardEvent`). Both halves assume one event.
- Multi-taps (`Esc Esc`, `Shift Shift`) are a separate hard-wired mechanism in
  `src/jsMain/kotlin/afield/doubletap.kt`, not the binding grammar.

## Proposed approach

1. Grammar: allow a space-separated sequence of specs (`cmd+k cmd+s`); note today the space is
   also accepted as a separator *within* one spec (`cmd shift d`), so the sequence separator needs
   deciding (space between two groups that each have a key; or require `+` inside a chord once
   a sequence is used). Add `KeySequence(List<KeyChord>)`; keep single chords as length-1.
2. Monaco: `monaco.KeyMod.chord(first, second)` supports exactly two-part chords natively, so
   editor bindings are straightforward for length 2.
3. Canvas: a small prefix state machine in `KeybindingsClient` — after a prefix chord, wait
   (~1.5 s or until the next non-modifier key) for the second; show a status hint ("Cmd+K was
   pressed, waiting for second key") like VS Code; a prefix must not also fire its own command.
4. Conflict checks: a sequence whose prefix is itself bound (e.g. `cmd+k` bound *and* `cmd+k cmd+s`)
   must be reported; the `?` card and palette labels (`KeySpec.label`) need a sequence rendering.
5. Update the error text, tests, and this page.

Alternative: reuse `doubletap.kt`'s timing machinery for canvas sequences — cheaper, but it keys on
`event.key` names, not chords with modifiers.

## Questions for a human

- Two-part only (Monaco's native limit) or arbitrary length?
- Which separator distinguishes "sequence" from the existing space-as-`+` spelling? Dropping the
  space-within-a-chord spelling is a breaking change to users' files.
- Should any defaults move onto chords (e.g. freeing single-letter or `cmd+` slots)?
- Timeout and whether a pending prefix is shown on screen.

## What the page needs afterwards

Rewrite "Two-keystroke chords" under "What you can rebind", the rejected-spec example in "When you
get it wrong" (it currently uses `cmd+k cmd+s` as the canonical bad spec), and the first bullet of
"Limits"; add a "Writing a chord sequence" subsection under "Writing a shortcut".
