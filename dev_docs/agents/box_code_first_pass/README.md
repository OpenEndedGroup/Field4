# First box-code readability sweep — complete review

Reviewed source: `eb6b34e4fbec4cc228bcfab66027a285995c01e3`.
Completed 2026-10-16 in `codex/box-code-first-pass`.

**568 / 568 current groups reviewed: 302 keep, 265 revise, 1 needs context.**
There are **405 source-reading findings** and **23 consolidated helper families**:
12 new decisions, eight previously approved families, three existing operations to reuse.

This completes the first review/proposal sweep. Teaching sources have not been changed by this
sweep. Helper decisions and the second edit/verification sweep remain. A finding is a proposed
change or suspected defect, not evidence that the affected program was run.

## Deliverables

- [Helper triage](HELPER_TRIAGE.md): twelve new decisions with recommendations and boundaries.
- [Group index](groups.tsv): every current group, disposition, body count and summary.
- [Findings](findings.json): stable finding IDs, source anchors, proposed edits and required verification.
- [Helper evidence](helper-proposals.json): consolidated before/after sketches and existing-API checks.
- [Scope](scope.json): exact occurrence IDs and source hashes; [coverage checkpoint](checkpoint.json).
- [Reviews](reviews/): seven-lens observations for each group, plus retained historical scope records.
- [Mechanical summary](mechanical-summary.json), [detailed results](mechanical-results.jsonl.gz),
  [formatter candidate patches](formatter-candidates.tar.gz).

The original 577-group census became 568 after correcting language inheritance and stage-specific
shader suffixes. The final scope has **3,516 Kotlin/KotlinJS/GLSL occurrences** and **2,861 distinct
language/body pairs**. Generated copies and history are inventoried separately. Eight saved review
records now lie outside the corrected language scope and are retained for context; they do not
inflate the 568-group completion count. The coverage validator reports zero missing groups,
duplicates, invalid records, mismatched review hashes or changed sources.

## Mechanical ktfmt, imports and GLSL

The actual Field editor formatting task ran on private extracted copies. Deterministic import
cleanup ran before ktfmt, without K2; library/star imports without resolution evidence remain.
The GLSL formatter processed shader properties, including extracted shader bodies. Embedded
GLSL strings also received source review; Kotlin formatting alone does not format their contents.

| Outcome | Distinct bodies | Meaning |
| --- | ---: | --- |
| Already formatted | 608 | Formatter produced no material layout change. |
| Candidate | 653 | Layout/import candidate passed the conservative token/fragment check. |
| Candidate with trailing comma layout | 507 | Same check after normalizing trailing commas; still review before applying. |
| Parse review | 822 | Formatter declined a body: includes partial signatures, fragments and pseudocode as well as potentially broken code. |
| Preservation review | 258 | Conservative check found token/string or fragment differences; inspect patches before accepting. Optional semicolon changes can also trigger this result. |
| Language review | 13 | KotlinJS heads or ambiguous-language content; no Kotlin formatter claim. |
| **Total** | **2,861** | Every current distinct body has a recorded outcome. |

401 bodies have import changes in the formatter output. This is a count across candidate and
preservation outcomes, not 401 automatically safe edits. Candidate patches and detailed results
are persisted here rather than depending on ignored build output. **No candidate was applied.**
Formatting/preservation checks establish neither compilation nor runtime equivalence.

## Findings to address first

The findings include behavior risks that should be resolved before polishing their examples:

- Dispatch truncation (100,000 particles / 64 threads) leaves 32 outputs unprocessed; simply
  rounding up also requires shader bounds checks.
- Async progress, futures and WebSocket watches can outlive the run that started them.
  Cleanup must act on the captured registration/resource and preserve a newer replacement.
- Native allocations, render chains and cancellation paths need ownership checks; cancellation
  between note-on and note-off can leave notes sounding.
- `error("cancelled")` in a box may resolve to the output API returning `Unit`, rather than
  terminating execution. Resolve the actual call before rewriting a guard.
- Reversed `smoothstep` edges, packed shader layouts, coordinate-space labels and buffer parity
  deserve targeted shader review and real rendering evidence.
- Raw property escape hatches should become typed/inherited properties where the normal API
  supports the task. Introspection and deliberate low-level fixtures need specific context.

Readability work includes useful units and stage comments, readable shader mathematics, moving
creative controls into view, ordinary block layout and existing helper reuse. Preserve authored
`//%-` boundaries and duplicate declaration/assignment reset points. Negative controls, completion
prefixes, signatures and historical transcripts should retain their teaching/test role.

The sole needs-context group is `templates/advanced_remote`: JavaScript is stored in a `.kt`
sidecar with ambiguous runtime metadata. Resolve the language before applying Kotlin edits.

## Verification and delivery evidence

- Inventory regression suite: **12 tests passed**, including language inheritance and GLSL suffixes.
- Complete saved coverage validated against current source bytes and recorded review hashes.
- Full idiom scan completed cleanly before review; human findings extend beyond its patterns.
- Actual formatter task completed sequentially, including the supplemental bodies discovered by
  the corrected inventory. Teaching source hashes remain unchanged.
- Earlier pilot/tooling branch landed as `17e588f27`; the second rehearsal landed as `f35ba0ecb`.
  Those runtime results belong to the rehearsals, not to this read-only corpus sweep.

Rebuild readable indexes with `uv run python dev_docs/agents/box_code_first_pass/consolidate.py`.
Check saved coverage with `uv run python dev_docs/agents/box_code_first_pass/checkpoint.py`.
Source changes after this snapshot intentionally invalidate its coverage. Second-pass edits need
compile/replay checks and genuine GPU/audio/UI evidence as appropriate; no full-corpus runtime
pass is claimed here.

## Coordination lesson

Large reviewer contexts repeatedly hit rate limits and made elapsed time look like progress.
The successful recovery used saved per-group records and bounded assignments (at most ten groups
or 45,000 code characters, with oversized groups alone). Future work should checkpoint after each
group, derive progress from validated files, and stop/reassign a failing batch without replaying an
entire reviewer history. The extended stalls and repeated status handoffs were a process failure.
