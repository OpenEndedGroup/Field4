# Format the frozen Kotlin and GLSL corpus with preservation receipts

Frozen findings: `691b5d822671-03`.

Apply the real Field editor import/ktfmt and GLSL formatters across the current frozen corpus. The hash-fenced inventory covers 567 extant frozen groups, 3,541 authoritative source occurrences, 3,548 states and 2,953 distinct language/body pairs. One frozen page, capture_limits.md, no longer contributes a program. Current groups outside the frozen scope are not silently added.

The actual formatter audit finds 1,823 bodies already formatted and 1,107 format candidates. Every Kotlin candidate preserves executable tokens apart from formatter punctuation/import handling; GLSL preserves all executable tokens and punctuation. Thirty-seven raw literals are restored byte-for-byte. Authored fragment/fold boundaries, particle rows and live reset pairs remain intact. Actual source-span application changes 519 files; source-only metadata updates and matching mirrors bring the owned total to 575 files.

The real Field document codec updates selected source properties in 43 saved documents, preserving box IDs and every unselected property. Existing sidecar drift outside selected sources is retained. Fifteen matching document mirrors and their existing digests are refreshed. The lambda-oracle mirror in interaction_nets_lambda already disagreed with its sidecar and is retained explicitly, rather than replacing its different program.

A second inventory reparses every edited container, including saved fallbacks and mirrors. All 2,702 parsed programs preserve executable content: 2,057 Kotlin, 384 GLSL and 261 foreign-language occurrences (unchanged). Foreign languages include Csound, Python, FieldML, Pd, StackSL, KotlinJS, JavaScript, Slang and RectSL. The complete original bodies, formatter logs, import decisions, source/metadata/mirror before-after receipts and codec verdict are compressed in formatter-receipts.tar.gz.

Retained cases are explicit: twelve KotlinJS bodies and the ambiguous JavaScript-in-.kt body are outside the Kotlin/JVM formatter; ten documentation snippets are visibly schematic (ellipses, incomplete declarations, a match arm or mathematical notation). They retain their authored text. This batch resolves the learned_from_module whitespace finding only; lifecycle and helper findings remain open.

Focused verification is parsing/token/string/boundary preservation and actual codec round-trip checks, plus Python syntax compilation for the reusable harnesses. This mechanical batch claims no new GPU/audio/browser run and replaces no public capture. The lander owns the complete guard run. The checkpoint retains the unresolved browser cleanup diagnostic from batch 53 and the remaining semantic work.

Checkpoint: 342/405 addressed, 63 open. Mechanical coverage: 575 edited files, 2702 parsed programs preserved; 23 retained language/schematic bodies and one pre-existing mirror drift documented above. Full guards are delegated to the lander.

## Landing repair

The full guard passed, then conversation packaging rejected a comment anchor changed in recorded FunctionStudy revisions. Batch 57 restores all 13 touched files of the recorded function example byte-for-byte to the pre-format baseline and teaches the inventory to retain that provenance. The live blank starter remains editable. The corrected formatter diff covers 562 files. Batch 57 also makes the stack.auto packager accept assignment whitespace while still requiring the exact stored HEAD string. All four packagers extract their real source blocks after that repair; the lander owns regeneration and the final guard.
