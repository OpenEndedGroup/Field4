# AI4 as a cold reader (2026-11-04)

The 2026-10-30/31 and 11-01/03 cold-reader passes (`COLD_READER_*.md`) were run by external agents.
This moves the reader inside Field: the `ai4_cold_reader` starting point
(`dev_docs/agents/transcripts/ai4_cold_reader.yaml`) registers two box tools, `cold_read_page` and
`report_stall`, and AI4 is driven through the ordinary `agent-task` path. No new harness type.

## Method

- Pages: `installation.md`, `staging.md`, `sound.md`, `kotlin_overview.md`, read at `0ea33f01e`, the
  commit the 10-30 ledger was written at (so its line numbers and stalls are present).
- Prompt: `ai4_cold_reader/goal_template.txt` (the ledger's category list plus `false-claim`; PAGE
  substituted). One live `agent-task` per page, headless (not `--chat-box`), `--max-rounds 48`.
- Model: OpenAI `gpt-6.1-sol` (`AFIELD_AI_PROVIDER=openai`; the configured `local` provider at
  100.110.104.26 was unreachable). One run per page.
- Raw stalls: `ai4_cold_reader/stalls_2026-11-04_gpt-6.1-sol.jsonl` (47 rows).
- Matching rule, fixed before the first run (code: `tools/cold_reader_score.py`, tests
  `tools/test_cold_reader_score.py`): same page, and either normalised quotes overlap (containment or
  a shared run of >= 4 words) or lines within 1 with the same category; one-to-one greedy, quote
  matches first.

## Numbers (population: 4 pages, 47 AI4 stalls, 38 ledger rows)

| page | AI4 | ledger | matched (by quote) | precision | recall |
| --- | --- | --- | --- | --- | --- |
| installation.md | 10 | 6 | 1 (1) | 0.10 | 0.17 |
| staging.md | 8 | 12 | 6 (6) | 0.75 | 0.50 |
| sound.md | 13 | 10 | 2 (1) | 0.15 | 0.20 |
| kotlin_overview.md | 16 | 10 | 5 (5) | 0.31 | 0.50 |
| pooled | 47 | 38 | 14 (13) | 0.30 | 0.37 |

Category agreed on 7 of the 14 matches. AI4 leans hard on `undefined-term` (27 of 47).

Hand judgement, per stall in `ai4_cold_reader/stalls_2026-11-04_gpt-6.1-sol.judged.jsonl`
(judge: Opus 5.5, approved by Marc without a per-stall review): of the 20 AI4 stalls on
installation.md and sound.md the ledger lacks, 17 are real (JEP, `personalJar`/`reloadPersonal`,
"register", "onset recall", `respondAhead`, an undescribed OSC editor; the sound.md line 279
contradiction checks out at `0ea33f01e`), 2 are weak (page-meta complaints on lines 150 and 297), and
1 is wrong (a `??? aside` flagged as page-meta, which is where such text belongs). Ledger-relative
precision is therefore a floor; the two readers mostly stop in different places. Recall misses
cluster on page openings and Tour links (sound.md lines 4, 22; installation.md lines 3, 13).

Cost: 2-9 rounds, 49k-158k input tokens, 33-48 s per page.

## Control: the same model without Field (2026-11-04)

`tools/cold_reader_naked.py` gives `gpt-6.1-sol` the same goal text, the same two tools (same
descriptions and parameters) and the same numbered page text through the bare Responses API at
medium effort (AI4's setting), with no Field system prompt or AI4 loop. Two runs; raw stalls in
`ai4_cold_reader/stalls_2026-11-04_naked-gpt-6.1-sol_run{1,2}.jsonl`.

| arm | stalls | matched (by quote) | precision | recall | category agreed |
| --- | --- | --- | --- | --- | --- |
| AI4 | 47 | 14 (13) | 0.30 | 0.37 | 7 |
| naked, run 1 | 46 | 14 (14) | 0.30 | 0.37 | 7 |
| naked, run 2 | 44 | 14 (13) | 0.32 | 0.37 | 5 |

Scored against each other with the same rule, naked run 1 finds 38 of AI4's 47 stalls (0.83 / 0.81),
and naked run 2 finds 38 of run 1's 46 (0.86 / 0.83). AI4 and the bare model differ by no more than
the bare model differs from itself. For a docs pass, AI4 adds nothing measurable over the model it
calls; what it adds is that the pass runs inside Field (a box, a chat, stalls in an output). The
bare runs took 11-17 rounds and 45-80 s per page, against AI4's 2-9 rounds and 33-48 s; the bare
loop makes one `report_stall` call per round, which accounts for the difference.

## Traps

- Box tools live for one backdoor invocation: run the transcript and the `agent-task` in the same
  `field_backdoor.py` process, or `agent-task` sees "No tool registered".
- `--check tool-used:report_stall` passed on a scripted run where every `report_stall` call had
  failed ("No tool registered"). The check tests that the tool was called, not that it succeeded;
  read `toolCalls[].ok`. Worth a `tool-ok:` check kind.
- Quotes from the model often span a hard line break (`\n` inside `exact`); the normaliser collapses
  whitespace, so matching is unaffected.

## Not done

- Stretch (AI4 proposing the edit per stall, compared with the external fix commits
  `fa010301d`/`540646ec2`/`9422b14dd`): not attempted.
- Repeat runs (variance) and a second model.

