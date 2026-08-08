# Keeping the agent transcript index true

`public/docs/agent_transcripts.md` is the published index of the transcript corpus in
`dev_docs/agents/transcripts/`. This note is about the contract that keeps it accurate, and about why
that contract is a guard rather than a generator.

## What broke, and the shape of the failure

On 2026-08-06 the page listed 28 of 52 transcripts while its opening paragraph asserted that "every
one of them is exercised end-to-end in CI". Neither number was right: 24 transcripts had no entry, and
`DemoTranscriptExecSmokeTest` runs only 35 of them end to end.

The instructive part is which half of the same system was fine. The *generated* artifact next door —
`public/docs/assets/agent_transcripts/`, the openable starting points — is guarded byte-for-byte by
`AgentTranscriptMaterializer` and `checkAgentTranscripts`, and had stayed current for months. The
hand-written page beside it, which nothing checked, fell behind the first time somebody added a
transcript without remembering the page existed. A guarded artifact stays true; an unguarded one
decays at exactly the rate people forget.

## Why the page is not generated

The obvious fix is to generate the page from the corpus, the way `public/docs/reference/` is generated
from KDoc. That would be worse, and it is worth being explicit about why, because the argument
generalises.

The entries that earn this page its place quote a number a box prints and say why that number is the
interesting one — `0.985 forwards, 0.246 backwards on a parabola`; `473 diagonal breaks against 0`;
"at radius 2 the *kernel* is the faster of the two". None of that is in the yaml. The yaml carries op
verbs, box ids, rects and Kotlin source, so a generator over it can produce a name, a box count and
maybe a first line of code. The page would become complete and useless in the same commit.

So the trade taken here is `checkKDocAttachment`'s, not `checkCodebaseReference`'s: keep the prose
hand-written, and make its *incompleteness* a build failure. A guard that forces a human to write a
sentence is the right tool whenever the artifact's value is the writing rather than the enumeration.

## The contract

`afield.docs.AgentTranscriptIndexGuard`, run by `./gradlew checkAgentTranscriptIndex` and wired into
`check`. It reads text files only — no boxes, no runloop, no GPU — and takes about a second, unlike
its heavyweight sibling `checkAgentTranscripts` (~25 s, because that one builds real boxes).

**An entry** is a line at column zero of the form:

```
**transcript_name** — prose…
```

with an em dash. One per transcript, no more. Fenced code blocks are skipped before any of this is
parsed, so a `##` line or an entry-shaped line inside a fence is not read as page structure.
Consequence worth knowing: do not start a line with a single bold lowercase word followed by an em
dash unless it is an entry — that shape is reserved, and the guard will read it as an entry for a
transcript that does not exist. Bold mid-sentence is fine.

**A section** is a `##` heading, followed within four lines by a marker declaring how CI verifies the
scenes beneath it:

```
## Pure-compute scenes

<!-- transcript-index: verified-by = execute -->
```

The value is a comma-separated subset of `execute`, `compile` and `kotlinjs`, naming
`DemoTranscriptExecSmokeTest.EXECUTE`, `.COMPILE_ONLY` and `.KOTLINJS` — which remain the single
source of truth for classification, and are `internal` rather than private for this reason. Every
entry's transcript must be in one of its section's declared sets. A section with two levels is allowed
(the visionOS section has one, because four of its six scenes are Kotlin/JS and two are JVM Kotlin)
but should be rare, and its prose has to name the exception; the whole point of the marker is that
"these build and run headless" is then a true statement about *every* entry beneath it.

**The totals paragraph** sits under its own marker and must quote four numbers — the corpus size and
the size of each of the three sets:

```
<!-- transcript-index: totals -->
All ⟨corpus⟩ transcripts are checked on every build … ⟨execute⟩ of them are replayed end to end …
```

The placeholders are deliberate. This example carried real numbers twice and was stale both times —
it is prose in a dev doc, so no guard sees it, while the paragraph it illustrates *is* guarded and
therefore always right. Read the live numbers off `public/docs/agent_transcripts.md`, never off here.

Adding a transcript makes exactly one of those wrong until somebody looks, which is the intent.

**Starting-point links.** A transcript is offered as an openable document exactly when
`AgentTranscriptMaterializer` ships it, i.e. when it is not in that object's `EXCLUDE` set. So an
entry must carry an `assets/agent_transcripts/<name>/metadata.edn` link when its transcript is
materialized, must not when it is excluded, must not link a *different* transcript's document, and the
committed bundle directory has to exist. This is the check that stops the page advertising a document
the welcome screen cannot open.

## Adding a transcript

1. Write the yaml in `dev_docs/agents/transcripts/`.
2. Add its name to exactly one of `EXECUTE`, `COMPILE_ONLY` or `KOTLINJS` in
   `DemoTranscriptExecSmokeTest`, with a comment saying why that one. An unclassified transcript fails
   that test on purpose.
3. If it is not a self-contained scene — it needs an external asset, a user gesture, or a renderer
   from a box it does not contain — add it to `AgentTranscriptMaterializer.EXCLUDE` and say so in its
   entry's prose instead of linking a starting point.
4. `./gradlew regenerateAgentTranscriptStartingPoints`, and commit the new bundle directory.
5. Write the entry on `public/docs/agent_transcripts.md`, under the section whose `verified-by` marker
   matches step 2, and bump the totals paragraph.
6. `./gradlew checkAgentTranscriptIndex checkAgentTranscripts`.

## Figures

The index is meant to be illustrated, and there is a hard rule about how: **never** synthesize,
hand-draw or mock up a capture. Every figure on it is a capture of the real code running, taken for the
walkthrough page linked beside the entry; all but one come from a `diffstatic_runner` fence on that
page, and the exception (`assets/imgui_vr_panel/imgui-vr-panel.png`) is a real screenshot of the live
demo rather than a fence's output, which is worth knowing if you go looking for the fence that made it.
The materialized bundles
under `public/docs/assets/agent_transcripts/<name>/` are *not* a source of figures: they hold
`metadata.edn`, `document.md` and one `.kt` per box property, and no images at all. Most entries
therefore carry no figure, which is correct: a scene whose output is a native Vulkan window or a
headset has no still a build can honestly produce, and an honest gap beats a fake.
