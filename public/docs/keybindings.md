# Keyboard shortcuts

Every keyboard shortcut in Field is one line in one file that belongs to you:

```diffstatic {data-lang=text}
~/.afield/keybindings.yaml
```

Field writes that file itself, from its own list of commands, so every command Field has is
in there, including the ones that ship with no shortcut at all. Change a line, save, and the
shortcut moves immediately: the file is watched, like `~/.afield/theme.css`, and nothing
needs restarting.

Press `?` on the canvas and Field shows you the card below. It is not a picture of the
defaults: each legend is read from the shortcut that is *actually bound right now*, so once
you have edited the file the card describes your Field, not ours.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#cheatsheet .centered-container-div" screenshot="assets/keybindings/cheatsheet.png" padding="8" wait="0" timeout="120" embed=true hidden=true name="keybindings-cheatsheet" alt="Field's ? cheatsheet card, with every key legend filled in from the live keybindings"}
// The card's legends come from the live bindings, so make sure this capture is of the
// defaults rather than of whatever an earlier capture on this page left in force.
afield.UserKeybindingsManager.watchFile(null)
ui.pause(1200)

// The card is taller than it is allowed to be and scrolls; lift that ceiling so the still shows
// all of it instead of stopping mid-list. Every legend in it is still drawn by the real card.
ui.eval("var s=document.createElement('style');s.textContent='#cheatsheet .centered-container-div{max-height:none !important;overflow:visible !important}';document.head.appendChild(s);return 'unrolled'")

// `?` is ignored while a text field has focus — there it is just a question mark — so let go
// of whatever the page focused as it loaded.
ui.eval("if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur(); return document.activeElement.tagName")
ui.press("Shift+Slash")
ui.pause(1500)

// Fail the capture rather than photograph an invisible card.
val opacity = ui.eval("return getComputedStyle(document.getElementById('cheatsheet')).opacity")
    ?.toString()?.trim('"') ?: "missing"
if (opacity == "0" || opacity == "missing") error("the ? shortcut did not open the cheatsheet (opacity=$opacity)")
```

## What you can rebind

Everything Field itself does: the canvas commands (`new-box`, `copy-selection`, …), the
editor's actions (`execute-fragment`, `ai-assist`, …), the four global ones that work
wherever you are (`open-command-palette`, `open-anything`, `toggle-docs`, `show-cheatsheet`)
and all eighteen bookmark slots. Commands that appear only when something is selected
(`connect`, `-copy`, the box actions a running box declares) are in the file too, and stay
rebindable even at moments when they are not on offer.

A command that ships without a shortcut appears in the file as `""`, and giving it a chord
is the same edit as changing one:

```diffstatic {data-lang=yaml}
bindings:
  macro-transcript-panel: "cmd+shift+r"   # was: no shortcut at all
```

Three things are *not* in the file:

- **Gestures.** Alt-click to launch a box, Alt-drag to "strum" several, Opt+drag, and the
  multi-taps — `Esc Esc` to clear the newest output, `Esc Esc Esc` to clear them all,
  `Shift Shift` to open anything from the canvas, `Cmd Cmd`-and-hold for
  [spatial navigation](spatial_navigation.md). These are not single chords, so there is
  nothing in the grammar below to write them with; they are hard-wired.
- **Monaco's own editor bindings.** Autocomplete (`Ctrl+Space`), undo, find, multi-cursor
  and the rest belong to the text editor Field embeds, not to Field's command list.
- **Two-keystroke chords.** There is no `cmd+k cmd+s`. See "Limits" at the end.

## Getting a file to edit

You already have one; Field writes it on first run. Two ways to get to it:

- Run **Open Keybindings** from the command palette (`Cmd+Shift+P`, or `F1`). Field opens
  the file as a box on the canvas, so you can edit your shortcuts in Field, with Field.
- Or open `~/.afield/keybindings.yaml` in any editor you like.

Saving is all it takes. Field notices within a second, re-resolves every binding and
re-registers them in the editor and on the canvas at once, then tells you it has done so. If
you would rather not wait, or you changed the file while Field was not running, there is a
**Reload Keybindings** command that re-reads it on the spot.

Deleted the file? Field writes a fresh one from its own defaults the next time it takes
stock of its commands, which is every run. That is also the way to get a clean copy back if
you have edited yourself into a corner: move yours aside and let Field regenerate.

## Writing a shortcut

The value is a *spec*: some modifiers and one key, in any order, joined by `+`.

```diffstatic {data-lang=yaml}
bindings:
  new-box: "cmd+alt+b"            # one shortcut
  execute-all: ["alt+0", "f5"]    # several, all of which work
  search-docs-and-code: ""        # no shortcut at all
  # (delete a line)               # back to Field's default
```

**Modifiers** are `cmd`, `alt` and `shift`. `cmd` means Command on macOS and Control
everywhere else, so one spec covers both platforms; `ctrl`, `control`, `command`, `meta`,
`mod`, `ctrlcmd` and `super` are all accepted spellings of it. `alt` is Option on macOS;
`option` and `opt` also work. Case does not matter, and `+`, `-`, a space or a comma all
separate tokens, so `cmd-shift-d`, `CMD + SHIFT + D` and `cmd shift d` are the same
shortcut as `cmd+shift+d`.

**Keys** are `a`–`z`, `0`–`9`, `f1`–`f24`, one of

```diffstatic {data-lang=text}
enter escape backspace delete space tab up down left right home end pageup pagedown insert
```

or a punctuation character written as itself:

```diffstatic {data-lang=text}
[ ] ' ; , . / \ - = `
```

Those last two rows have friendly spellings too, if you would rather not put a bare
backslash in a YAML file: `return`, `esc`, `del`, `spacebar`, `arrowup`, `bracketleft`,
`quote`, `semicolon`, `comma`, `period`, `slash`, `backslash`, `minus`, `dash`, `equal`,
`backquote`, `backtick` and `plus` all resolve to the key they name.

A **shifted character stands for Shift plus its key**, which is much nicer to read than
spelling the modifier out. `?` is exactly `shift+/`, and that is how the cheatsheet's own
shortcut is written. The same goes for `~ ! @ # $ % ^ & * ( ) _ { } : " < > |`.

Two characters that are also separators need a word about them: a lone `,`, `-` or `+` is
read as a key only when it is the last thing in the spec. So `cmd+,` binds the comma key
(that really is one of Field's defaults, "Previous Property"), `cmd+-` binds the minus key,
and `cmd + k` is just `cmd+k` with room to breathe.

### Where a shortcut is live

Some shortcuts should not fire while you are typing, and the file records that as a comment
Field wrote for you:

```diffstatic {data-lang=yaml}
  new-box: "n"                    # New Box  #scope canvas
  execute-fragment: "alt+enter"   # Run Line / Selection  #scope editor
  edit-hashtag: "cmd+h"           # Edit hashtag
```

`#scope canvas` means the shortcut only fires when the canvas has focus. That is why
`new-box` can be a bare `n`: pressing `n` in an editor types an `n`. `#scope editor` means
the opposite; no comment at all means both. The scope belongs to the command, not to your
spec, so it is not something you edit, but it does tell you whether a bare letter is a
sensible thing to bind a command to.

One line of your file does both jobs. The same spec becomes the keybinding Monaco uses when
an editor has focus *and* the test the canvas runs when it does not, so a rebind cannot leave
the two halves of Field disagreeing about a key. Matching is on the physical key
(`KeyboardEvent.code`), not the character produced, so an Alt shortcut survives macOS turning
`Alt+]` into `’`, falling back to the character itself for shifted punctuation on layouts
where it is not physically Shift plus that key.

## Finding a command's id

The file is the index: every entry carries the command's own label as a comment, so
searching it for "Bookmark" or "Format" is usually the fastest route from *what you want* to
*what to write*.

The command palette works the other way round: search for the command by name, and its
current shortcut is shown on the right of the row. That makes it the real test of a rebind,
since those labels are drawn from the same binding your keypress would use:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-palette-shell" screenshot="assets/keybindings/rebound-palette.png" padding="6" wait="0" timeout="150" embed=true hidden=true name="keybindings-rebound-palette" alt="Field's command palette, opened with a custom Alt+K shortcut, listing New Box with its rebound Cmd+Opt+B"}
// A rebind, taken all the way through the real machinery: write a keybindings.yaml,
// let Field read it, then press a chord Field has never shipped and photograph what
// happens. The file is a scratch one — nobody's own shortcuts are touched.
val scratch = java.io.File(System.getProperty("java.io.tmpdir"), "field-docs-keybindings/keybindings.yaml")
scratch.parentFile.mkdirs()
scratch.writeText(
    "bindings:\n" +
    "  open-command-palette: alt+k\n" +
    "  new-box: cmd+alt+b\n"
)
afield.UserKeybindingsManager.watchFile(scratch)
ui.pause(1500)

// Alt+K is not a shortcut Field has: if the palette opens, it opened because of the file.
ui.press("Alt+K")
ui.pause(1600)
ui.type("New Box")
ui.pause(1400)

// The still is taken immediately after this script, so hand the watcher back on a short delay:
// restoring it here would re-register every binding while the palette is still on screen. The
// figures either side of this one point the watcher back themselves, so a slow shot cannot leave
// them describing this scratch file.
Thread {
    Thread.sleep(2500)
    afield.UserKeybindingsManager.watchFile(null)
}.also { it.isDaemon = true }.start()
```

The `?` card is the third route, and the shortest one for the twenty or so shortcuts you
will use every day.

## When you get it wrong

Field would rather tell you than guess.

- **A spec it cannot read** is reported with the reason, as a notification and in the log —
  and the other lines in the file still apply, so one bad shortcut does not cost you the rest:

    ```diffstatic {data-lang=text}
    keybindings.yaml: 'cmd+k cmd+s' (for 'open-anything') is not a shortcut
      — a shortcut is one key with modifiers, Field has no two-keystroke chords
    ```

    **The command keeps its default.** A line Field cannot read is ignored, not obeyed
    halfway and not taken as an unbind: `open-anything` above is still on `cmd+p`. Keep that
    apart from an empty value, which means something quite different: `""` is an unbind you
    asked for (below). If a rejected spec also produced "no shortcut", a typo and a decision
    would look identical, and the error message would be describing a shortcut you had just
    lost without saying so.

- **A command id it does not recognize** — usually a typo — is logged as a warning naming
  the id, and the binding is kept anyway rather than dropped. It has to be: the
  selection-only commands genuinely are not registered most of the time, and silently
  discarding a shortcut that works would be worse than a warning you can ignore.
- **YAML that does not parse** leaves your previous shortcuts in force and says where the
  file is broken. A stray bracket cannot lock you out of your own keyboard.
- **Two commands on one chord** logs which two, and warns you that only one of them will
  win. Field checks this whenever your file is involved; the defaults are our problem, not
  yours.

If a shortcut silently does nothing at all and there is no complaint anywhere, the usual
cause is scope: a canvas-only command bound to a chord you are pressing inside an editor.

### Asking Field directly

When the file says one thing and your fingers report another, a box can read the resolved
bindings, the same ones the keypresses themselves consult, and settle it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/keybindings/inspect.png" wait="1.0" wait-output=true wait-output-kind="display" capture="box" open-outputs="true" padding="16" embed=true name="keybindings-inspect" macro-file="../../dev_docs/agents/transcripts/docs_capture/keybindings_inspect.yaml" capture-box="agent_keybindings_inspect" alt="A Field box listing the shortcut currently bound to five commands, read from the live keybinding layer"}
// Point the watcher at ~/.afield/keybindings.yaml — a no-op unless something moved it,
// which the rebinding figure further up this page does.
afield.UserKeybindingsManager.watchFile(null)

val live = afield.UserKeybindingsManager.resolved
val ids = listOf("new-box", "open-command-palette", "show-cheatsheet", "execute-fragment", "connect")
// A command with no binding yet is the interesting row, so it says so in a role rather than
// looking like any other cell.
val bound = Table(listOf("command", "bound to"))
    .rows(ids.map { id ->
        listOf(id, (live.effective[id] ?: live.bindings[id])?.joinToString(" or ") ?: "not registered yet")
    })
    .roleBy("bound to") { if (it == "not registered yet") CellRole.WARN else null }

markdown("main", """
    {muted:${live.path.md}}

    {muted:${live.problems.size} problem(s) in it}

    ${bound.h}
""", width = 560)
```

`effective` is the answer for every command Field has seen this run, override or default
already applied; `bindings` is only what your file changed. A command in neither, such as a
selection-only one with nothing selected, is reported as dormant instead of unbound, which is
why the `?` card leaves that legend at its written default rather than claiming the key is
dead.

## Limits

- **One key per shortcut.** Field has no two-keystroke chords, so there is no `cmd+k cmd+s`.
  A spec with two keys in it is refused with that explanation rather than half-obeyed.
- **`f13`–`f24` and some rarer keys** are accepted on the canvas but may have no
  counterpart in the editor, where the binding is Monaco's to make. If a shortcut works on
  the canvas and not in an editor, that is the likely reason.
- **One file per user**, not per document or per workspace, and it is not synced anywhere.
- **The gestures and multi-taps** listed at the top are not in it, and neither are Monaco's
  built-in editor bindings.

## The defaults, in full

This is the same inventory that writes your file, read out of a running Field, so it is
exactly what a fresh `keybindings.yaml` says.

<!-- BEGIN generated: default bindings -->

**107 rebindable commands**, 50 of them with a shortcut out of the box. Regenerate this table against a running Field with `tools/keybindings_defaults_table.py`.

### Global

| Command id | Default | Where | What it does |
| --- | --- | --- | --- |
| `open-anything` | `cmd+p` | both | Open Anything |
| `open-command-palette` | `cmd+shift+p` or `f1` | both | Command Palette |
| `show-cheatsheet` | `?` | both | Show Cheatsheet |
| `toggle-docs` | `cmd+/` | both | Toggle Documentation |

### Canvas

| Command id | Default | Where | What it does |
| --- | --- | --- | --- |
| `-copy` | *(none)* | both | Duplicate box(es) |
| `-layout-boxes` | *(none)* | both | Lay out selection |
| `-layout-document` | *(none)* | both | Lay out whole document |
| `-update-check` | *(none)* | both | Check for updates |
| `add-document-note` | *(none)* | both | Add Note to This Document |
| `add-editor-to-output` | *(none)* | both | Add Editor to Output Area |
| `align` | *(none)* | both | Align boxes |
| `connect` | `g` | canvas | Connect boxes |
| `copy-box-guide-link` | *(none)* | both | Copy Markdown Link to Box or Line |
| `copy-selection` | `cmd+c` | canvas | Copy Selection |
| `delete-box` | `cmd+backspace` or `cmd+delete` | both | Delete Box(es) |
| `disconnect` | `u` | canvas | Disconnect boxes |
| `edit-hashtag` | `cmd+h` | both | Edit hashtag |
| `export-document-edn` | *(none)* | both | Export document (.edn) |
| `export-ipynb` | *(none)* | both | Export notebook (.ipynb) |
| `export-selection-edn` | *(none)* | both | Export selection (.edn) |
| `fmt-kotlin` | *(none)* | both | Format Kotlin |
| `fmt-property` | *(none)* | both | Format |
| `insert-from-workspace` | `cmd+t` | both | Insert template |
| `macro-transcript-panel` | *(none)* | both | Macro: Recorder Panel |
| `macro-transcript-run` | *(none)* | both | Macro: Run on Selection |
| `macro-transcript-save` | *(none)* | both | Macro: Mark End & Save |
| `macro-transcript-start` | *(none)* | both | Macro: Mark Start |
| `macro-transcript-undo` | *(none)* | both | Macro: Undo on Selection |
| `move-forward` | *(none)* | both | Move selected boxes forward |
| `nest-into` | *(none)* | both | Nest selection into a box |
| `new-box` | `n` | canvas | New Box |
| `normalize-nested-pan` | *(none)* | both | Normalize Nested contents |
| `open-user-keybindings` | *(none)* | both | Open Keybindings |
| `open-user-theme` | *(none)* | both | Open User Theme |
| `paste-selection` | `cmd+v` | canvas | Paste Selection |
| `provisional-accept-all` | *(none)* | both | Accept All Provisional (Everywhere) |
| `provisional-reject-all` | *(none)* | both | Reject All Provisional (Everywhere) |
| `record-selected-box-mp4` | *(none)* | both | Record Selected Box MP4 |
| `record-selected-editor-mp4` | *(none)* | both | Record Selected Editor MP4 |
| `record-window-mp4` | *(none)* | both | Record Window MP4 |
| `reload-user-keybindings` | *(none)* | both | Reload Keybindings |
| `reloadPersonal` | *(none)* | both | Reload personal/ classes |
| `reset` | *(none)* | both | Reset Kotlin Environment for Box |
| `route` | *(none)* | both | Route (position-guided) |
| `save-as-template` | *(none)* | both | Save selection as template |
| `search-docs-and-code` | `cmd+shift+d` | both | Search Documentation & Code |
| `select-all-boxes` | *(none)* | both | Select All Boxes |
| `set-main-language` | *(none)* | both | Set Main Language |
| `set-main-language-clojure` | *(none)* | both | Set Main Language: Clojure |
| `set-main-language-datalog` | *(none)* | both | Set Main Language: Datalog |
| `set-main-language-fieldml` | *(none)* | both | Set Main Language: FieldML |
| `set-main-language-forth` | *(none)* | both | Set Main Language: ForthField |
| `set-main-language-kotlin` | *(none)* | both | Set Main Language: Kotlin |
| `set-main-language-kotlinjs` | *(none)* | both | Set Main Language: Kotlin → head |
| `set-main-language-morpho` | *(none)* | both | Set Main Language: MorphoHDL |
| `set-main-language-nano` | *(none)* | both | Set Main Language: Nano |
| `set-main-language-python` | *(none)* | both | Set Main Language: Python |
| `set-main-language-remotejs` | *(none)* | both | Set Main Language: Javascript |
| `show-all-layers` | *(none)* | both | Show All Layers |
| `show-document-guide` | *(none)* | both | Show Document Guide |
| `show-document-history` | *(none)* | both | Show Document History |
| `show-profiling-timeline` | *(none)* | both | Show Profiling Timeline |
| `snap_tidy` | *(none)* | both | Snap + tidy boxes |
| `solo-next-layer` | `alt+]` | both | Solo Next Layer |
| `solo-previous-layer` | `alt+[` | both | Solo Previous Layer |
| `switch-to-docs-iframe` | *(none)* | both | Read the Field Documentation |
| `switch-to-monaco-editor` | *(none)* | both | Switch to Monaco Editor |
| `toggle-keypress-display` | *(none)* | both | Toggle Keypress Display |
| `toggle-notes-markdown-preview` | `cmd+shift+m` | both | Toggle Markdown Preview |
| `un-nest` | *(none)* | both | Un-nest selection |

### Editor

| Command id | Default | Where | What it does |
| --- | --- | --- | --- |
| `ai-assist` | `alt+o` | editor | Apply Intelligence |
| `ai-assist-error` | `alt+shift+o` | editor | Apply Intelligence to Error message |
| `ai-chat` | `cmd+3` | editor | AI Chat in editor |
| `ai-chat-panel` | `cmd+4` | editor | AI Chat (panel) |
| `back-next-property` | `cmd+,` | editor | Previous Property |
| `continue-all` | `alt+shift+up` | editor | Continue Box |
| `execute-all` | `alt+0` | editor | Run Box |
| `execute-fragment` | `alt+enter` | editor | Run Line / Selection |
| `forward-next-property` | `cmd+.` | editor | Next Property |
| `history-lens-selection` | *(none)* | editor | Show History Lens for Line / Selection |
| `launch-all` | `alt+up` | editor | Launch Box |
| `launch-fragment` | `alt+1` | editor | Launch Line / Selection |
| `paired-shader-definition` | `f12` | editor | Go to Paired Shader Definition |
| `provisional-accept-current` | *(none)* | editor | Accept All Provisional (This Editor) |
| `provisional-reject-current` | *(none)* | editor | Reject All Provisional (This Editor) |
| `send-to-preamble` | `alt+shift+p` | editor | Move code to preamble |
| `stop-all` | `alt+down` or `alt+shift+down` | editor | Stop Box |
| `toggle-fold-current-line` | `cmd+'` | editor | Toggle Fold on Current Line |
| `update-shader` | `alt+shift+enter` | editor | Update Shader |

### Bookmarks

| Command id | Default | Where | What it does |
| --- | --- | --- | --- |
| `restore-bookmark-1` | `shift+f1` | both | Restore Bookmark 1 |
| `restore-bookmark-2` | `shift+f2` | both | Restore Bookmark 2 |
| `restore-bookmark-3` | `shift+f3` | both | Restore Bookmark 3 |
| `restore-bookmark-4` | `shift+f4` | both | Restore Bookmark 4 |
| `restore-bookmark-5` | `shift+f5` | both | Restore Bookmark 5 |
| `restore-bookmark-6` | `shift+f6` | both | Restore Bookmark 6 |
| `restore-bookmark-7` | `shift+f7` | both | Restore Bookmark 7 |
| `restore-bookmark-8` | `shift+f8` | both | Restore Bookmark 8 |
| `restore-bookmark-9` | `shift+f9` | both | Restore Bookmark 9 |
| `save-bookmark-1` | `cmd+shift+f1` | both | Save Bookmark 1 |
| `save-bookmark-2` | `cmd+shift+f2` | both | Save Bookmark 2 |
| `save-bookmark-3` | `cmd+shift+f3` | both | Save Bookmark 3 |
| `save-bookmark-4` | `cmd+shift+f4` | both | Save Bookmark 4 |
| `save-bookmark-5` | `cmd+shift+f5` | both | Save Bookmark 5 |
| `save-bookmark-6` | `cmd+shift+f6` | both | Save Bookmark 6 |
| `save-bookmark-7` | `cmd+shift+f7` | both | Save Bookmark 7 |
| `save-bookmark-8` | `cmd+shift+f8` | both | Save Bookmark 8 |
| `save-bookmark-9` | `cmd+shift+f9` | both | Save Bookmark 9 |

<!-- END generated: default bindings -->

## See also

- [Survival Guide](survivalGuide.md) — the shortcuts worth learning first.
- [Feature Overview](funFeatures.md) — what the commands in that table actually do.
