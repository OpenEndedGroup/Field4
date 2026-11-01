# Decide how the function conversation's recorded revisions survive package moves

- **Handoff:** `d70f5875-0082-4f81-a9b0-915ead7fec26` (project)
- **Filed from:** https://openendedgroup.com/field4/function_conversation/
- **Filed by:** human, 2026-10-31 16:57
- **Close with:** `python tools/docs_notes.py handoff-close d70f5875 "what happened"`

## What happened

Recapturing `saved-chat.png` for `public/docs/function_conversation.md` (2026-10-31), the worked example
`dev_docs/agents/demos/function_conversation_example` no longer opens its study: `ObserveFunctionStudy` refuses
with `Revision source changed on disk: r9-82456e1e48ac`. `SourceStudy.revision` (`src/jvmMain/kotlin/afield/study/source/SourceStudy.kt`)
requires each revision file's SHA-256 to start with the hash in its name.

All nine recorded revisions (`studies/function_source/r1..r9-*.kt`) fail that check. The bytes that match their
names are the originals at `ed47f922b`, but those import `afield.stdlib.Functions` etc., which no longer exist.
Two later commits rewrote them: `03f48991b` (package cutover: imports to `afield.math.functions`) and `f8cd87437`
(corpus formatter). The second also removed the starter's imports, which `compileStudy` needed; that part is now
fixed in code (`compileStudy` imports `afield.math.functions.*` first; `FunctionStudyTest` pins the starter).

## The decision

1. **Re-key**: give the rewritten sources new revision ids (new hashes), renaming the `.kt`/`.json`/plot files and
   every reference (the page's plot images and revision names, the saved chat, `metadata.edn`). The recorded chat
   would then name revisions that differ from the ones it talks about.
2. **Restore and translate**: put back the original bytes (hashes valid again) and teach the revision loader a
   recorded package move (`afield.stdlib.X` to its new home) at compile time, so history stays byte-exact.
3. **Relax the check** for demo documents (accept a mismatch with a warning). The simplest, and it weakens what a
   revision id promises.

Earlier ruling on this page ("history not rewritten") points at 2. The stacksl, rectsl and stackauto examples may
have the same damage; check them with the same hash test before choosing.

## Then

Re-run `tools/capture_function_conversation.py`, then `tools/package_function_conversation.py`, and close
handoff 5a20a546 (the saved-chat capture).
