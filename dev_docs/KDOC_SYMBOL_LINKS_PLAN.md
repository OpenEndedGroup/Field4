# Plan: make KDoc `[Symbol]` references link

Status: proposed 2026-10-17, after the KDoc overview blast. Phase 1 done 2026-10-17 (see below).

## The problem

`tools/docs_voice/check_kdoc_mirror.py --sweep` counts the `[Symbol]` references written in published
file overviews: **4975, of which 2482 (50%) publish as plain text across 624 files**. A reader sees a
code name they can't click.

A classification of the 2468 that map to a single source file (scratch script, rerunnable):

| Kind | Count | Example |
| --- | ---: | --- |
| Public top-level fun/val, unlinked | 1260 | `[polarization]` in `alife.kt` |
| Member or extension reference `Owner.member` | 1102 | `[Renderer.renderList]`, `[Box.animateEvolution]` |
| Not declared in `src/` (stdlib, JDK, enum constants, typos) | 69 | `[kotlin.random.Random]`, `[LinkedHashMap]` |
| `internal` only | 26 | `[sonicPiActivityDecorations]` |
| `private` only | 11 | `[ISO_CROSSING_EPSILON]` |

**95% are not a writing problem.** `StdlibReferenceGenerator.buildTypeAnchorIndex` indexes *types
only*. Function, property and member rows get a table row that links to source, but no anchor, so
`TypeAnchors.resolveReference` has nothing to resolve a `[fun]` or `[Type.member]` to. The prose is
correct, and the generator drops the link.

## So this is a generator change first, and a small blast second

### Phase 1 — generator (one agent, one branch, no fan-out)

1. Give every published symbol row a stable anchor: top-level functions/properties, members and
   extensions. Derive it from the same `typeAnchor(doc, symbol)` scheme, e.g.
   `#<file>-kt-<owner>-<name>`. Overloads share one anchor (the first row), which is what a reader
   linking `[name]` wants.
2. Extend the index from types to all symbols, keeping both existing safety rules:
   - a simple name defined in two files is unlinked from a third (ambiguity);
   - a qualified `[Owner.member]` resolves only when the member lives where `Owner` does
     (the TUIO `Frame` rule in the `TypeAnchors` KDoc). Extensions: an `Owner` that is the
     extension's receiver counts, so `[Box.animateEvolution]` resolves to the extension's row.
3. Never link a `private` symbol (it has no row); `internal` follows whatever the generator already
   does for internal rows.
4. Keep the generator's self-validation green, and add a test beside the existing
   `TypeAnchors` cases: a top-level fun, a member, an extension, an overload, and an ambiguous name.
5. Re-measure with the sweep. **Expect a large rise in "resolved into a link" and a residue of
   roughly 100–300**: ambiguous names, references to non-published files, and the 106 in the bottom
   three rows of the table.

Risk to watch: tens of thousands of new anchors change every reference page's markup, so the
diff is huge but mechanical. Check page weight, and check that anchor slugs are stable across
regeneration (an anchor that changes on reorder breaks inbound links). `check_kdoc_mirror.py`'s
`undecorate` must still treat a newly linked line as a mirror.

### Phase 1 result (2026-10-17)

- Every published row now carries `<a id="…">` at the start of its declaration cell:
  `<file>-kt--<name>` at top level, `<file>-kt--<owner>--<name>` for members (the `--` keeps them
  apart from type headings `<file>-kt-<type>`). One anchor per `(owner, name)` per file, on the first
  row; a slug collision gets `-2`, `-3` in source order.
- `TypeAnchors` indexes types, top-level funs/vals, members and extensions (receiver parsed from the
  signature by `receiverOf`). Bare names resolve corpus-wide only for top-level declarations; a bare
  member name resolves only in its own file, and not when two types there share it. `[Owner.member]`
  / `[Receiver.ext]` resolve through a qualified index (ambiguous across files → unlinked). The
  qualifier-owner rule is kept, and an unknown *capitalised* qualifier (`List.add`) no longer borrows
  a top-level function; lowercase (package) qualifiers fall back as before.
- Tests: `src/jvmTest/kotlin/afield/tools/docs/SymbolAnchorTest.kt`.
- Sweep: **resolved into a link 2507 / 4975 (50%) → 4715 / 4975 (95%)**; unlinked 2468 in 619 files →
  260 in 156 files; MISMATCH 0. `check_kdoc_mirror.py` needed no change (a linked line already
  undecorates to its mirror).
- Residue by kind: member-ref 86, public-but-unlinked 70 (ambiguous simple names such as `Box`,
  `Vec3`, plus JDK/Kotlin names that collide), not-declared-in-src 67, internal-only 26,
  private-only 11.
- Reference pages: 18,507,073 → 20,311,168 bytes (+1.80 MB, +9.7%). Two regenerations diff empty;
  no duplicate ids on any page.

**Follow-up (branch `docs/kdoc-symbol-links-2`): scoped resolution of ambiguous names.** Nested
types already stayed out of the corpus-wide bare-name index (they carry an owner) and typealiases are
indexed as types; the `Box` and `Vec3` collisions were between *top-level* declarations
(`Boxes.kt` vs steamaudio `Geometry.kt`; `linalg.kt` vs `CeresGeometry.kt`). A bare name still
ambiguous corpus-wide now resolves from the referencing file's own package, then its explicit
imports, then its star imports, linking only when a step picks exactly one (`SymbolAnchorScopeTest`).

- Sweep: 4715 → **4748 / 4975 (95%)** resolved; unlinked 260 in 156 files → 227 in 135; MISMATCH 0.
- `Box` / `Vec3`: 12 unlinked overview references (4 + 8) → 0; linked `` [`Box`] ``/`` [`Vec3`] `` on
  the reference pages 14 → 49.
- Residue by kind: member-ref 85, not-declared-in-src 67, public-but-unlinked 38, internal-only 26,
  private-only 11.
- Pages 20,315,024 bytes; two regenerations diff empty.

### Phase 2 — the residue blast (only after Phase 1 lands)

Rerun the classification on the new tree; only references still unlinked go to agents. Expected
fixes, all in KDoc comments only:

- **Not in `src/`**: JDK/stdlib → backticks (`` `LinkedHashMap` ``), since the reference doesn't
  emit external links; typos and renamed symbols → correct the name.
- **`private` / `internal` in a published overview**: rewrite the sentence so it doesn't name a
  symbol the reader can't use (this is the PLAYBOOK §6 visibility rule).
- **Ambiguous names**: qualify (`[Owner.name]`) so the generator's qualifier rule picks the target.

Size it from the measured residue. If it's under ~150 references it's one or two agents, not a
workflow. Use the same harness as the overview blast: one shared worktree, disjoint files per unit,
`blast/units/*.done` + `verdicts/*.json` markers so a rate-limit cutoff resumes, no Gradle or commits
in agents, and one `generateCodebaseReference` + `docsGates` at the end.

## Done when

- `check_kdoc_mirror.py --sweep` reports **≥ 95% resolved**, with every remaining unlinked reference
  either a deliberate backticked name or listed as accepted.
- Ideally the sweep becomes a gate on the unlinked count (no regressions), retiring the "check
  every `[Symbol]`" paragraph in the routine's PLAYBOOK §6, per AGENTS.md's rule that a corrective
  paragraph stays only until a tool enforces it.
