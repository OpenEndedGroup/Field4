<!--
AI4 lessons: read into AI4's instructions whenever the box-graph tools are wired (every chat path and
every agent task), capped at 2400 chars, curated bullets first. Kill switch: AFIELD_AI4_LESSONS=off.
"## Curated" is for people. "## Distilled" is rewritten by `agent-eval --distil` from the misuse in
that run's tool calls (AgentLessons.distil); review it with git diff like any other change.
-->

version: 5
distilled: 2026-09-30T04:07:36.258513Z from agent-eval mode=live openai gpt-6-astra, 47 task(s): primes,fix-compile-error,parent-variable,html-table,stop-ticker,wire-two,fix-runtime-error,swatch-colour,draw-sun,bars-visible,primes,fix-compile-error,parent-variable,html-table,stop-ticker,wire-two,fix-runtime-error,swatch-colour,draw-sun,bars-visible,stop-ticker,wire-two,fix-runtime-error,draw-sun,bars-visible,bars-sorted-red,stop-ticker,wire-two,fix-runtime-error,draw-sun,bars-visible,bars-sorted-red,stop-ticker,wire-two,fix-runtime-error,draw-sun,bars-visible,bars-sorted-red,stop-ticker,wire-two,fix-runtime-error,draw-sun,bars-visible,bars-sorted-red,bars-one-row,bars-one-row,bars-one-row; 8 misuse call(s)

## Curated

## Distilled

- `CaptureRenderWindowRequest` failed 8 times in 3 tasks (html-table, draw-sun, bars-sorted-red) answering "No capturable afield.four Window is visible from this box.". No later call of it in those tasks worked.
