# Describe box-reference parameters in AI4 tool request classes

- **Handoff:** `d6dcda56-771c-4474-b0d4-65829564eb22` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_tools/
- **Filed by:** agent:claude, 2026-11-22 10:26
- **Close with:** `python tools/docs_notes.py handoff-close d6dcda56 "what happened"`

## The notes that led here

- `191b3933` (public/docs/ai4_tools.md:945): somebody needs to ask if having blank descriptions is ever ok
  > What each tool tells the model

## What was asked

On ai4_tools.md, "What each tool tells the model", the human asked whether blank parameter
descriptions are ever acceptable. Their answer (2026-11-22): blanks are fine when the parameter
needs no description given the tool alone, but not where a general convention is implied — e.g.
a `String` that is really a box reference: from the tool alone it is not clear how to name a box
(uid? name?).

## Why this is bigger than a docs edit

The table on the page is generated from the request classes. `ConnectBoxesRequest(val parent:
String, val child: String)` (`src/jvmMain/kotlin/afield/ai/agent/FieldGraphTools.kt:115#ConnectBoxesRequest`)
carries no per-parameter description, so the model sees only `string`. Fixing it means editing the
tool definitions in `FieldGraphTools.kt`, `TextPassageTools.kt`, `NotationTools.kt`, `AgentTask.kt`
and others, and the descriptions change what AI4 sends every chat.

## Proposed approach

1. Audit every request-class parameter that takes a box reference (and other convention-bound
   strings: property names, channels, paths) and give it a description saying what it accepts,
   e.g. "box uid or unique box name".
2. Alternatively, introduce a shared type label (a `BoxRef` typealias/annotation) that the schema
   generator renders as "box uid or name", so the convention lives in one place.
3. Optionally, a generator check that fails on blank descriptions for box-reference parameters only.

## Questions for a human

- Per-parameter descriptions or a shared `BoxRef` type label?
- Which other string conventions (property name, channel) deserve the same treatment?
- Mind the tool prompt budget (~14k tokens for 101 tools): how long may each description be?

## What the page needs afterward

Nothing by hand: regenerate the tool reference so the new descriptions appear.
