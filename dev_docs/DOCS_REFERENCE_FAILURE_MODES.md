# The ways a docs reference breaks — four found, a fifth added

Companion to [DOCS_SITE_PREFIX_REFS.md](DOCS_SITE_PREFIX_REFS.md), which explains the *prefix*
family and the guard that found the rest. That guard found four genuinely distinct failure modes
and fixed only the one it was built for. This page is the other three plus the first, each with
its mechanism, its located instance, and the guard that now covers it.

Written 2026-09-17 from `nightly/2026-09-17/3-docs-prefix-failure-modes`, base `d6d164822`. Every
number below was measured in that worktree that night, and every number carries the population it
was measured over. Where a measurement contradicts an earlier note, the earlier note is named.

They have almost nothing in common. A reader gets a 404 or a dead link; that is the whole of the
resemblance. Two of the four were not broken references at all.

| | mode | mechanism | fixed at | guard |
|---|---|---|---|---|
| 1 | indent-lost code block | a sample renders as prose, and markdown eats its content | the KDoc | `check_doc_site_links.py` (empty ledger) + regeneration |
| 2 | server-absolute and case-mismatched | both halves of a link ignore the site prefix | the page and the guard that dictates it | `check_doc_offline_links.py` |
| 3 | dead by nature | the path is served by a running Field, not by the docs site | the page's prose | none needed — it was never a live ref |
| 4 | code read as markup | the extractor reads attribute text out of `<pre>`/`<code>` | the extractor | the mask's own over-reach check |
| 5 | the page is at 200 and the anchor is gone | a brace in a heading's own text eats its `attr_list` block | `SourcePageGenerator` | `verifyPage`'s anchor invariant |

Mode 5 is not from the backlog. It came out of looking for a fifth, once the other four were
closed, in the one place no guard in the tree was looking: the **fragment**. Every existing guard
strips `#…` before resolving, so a reference whose page is at 200 and whose anchor does not exist
is invisible to all of them, and it is a dead link to a reader.

---

## Mode 1 — a code sample stops being code, and markdown eats it

**Instance.** `public/docs/reference/patterns.md:2520` published

```text
      val groove = pat("[bd cp](3,8) hh!2")
```

as a paragraph, and markdown read `[bd cp](3,8)` as an inline link to `../3,8`. The reference
that 404s is a drum pattern.

**Mechanism.** Three off-by-ones stack.

1. `StdlibReferenceGenerator.stripKDocMarkers` trims each KDoc line, drops the `*`, then drops
   **exactly one** following space. So ` *` plus eight spaces arrives in the generated markdown as
   seven.
2. The sample sits inside an ordered-list item (`3. Schedule into seconds:`). Python-Markdown —
   which is what zensical renders with (`markdown>=3.7`; 3.10.2 installed) — uses a fixed
   `tab_length` of **4** for list content, regardless of marker width. So a code block inside a
   list item needs **8**, not the 7 that CommonMark would accept for a `3. ` marker.
3. Seven is therefore one short, every sample in that file overview was published as a paragraph,
   and the one sample whose text contains a markdown construct also lost it.

Measured on the real engine rather than reasoned about:

```text
indent 7 → <p>val groove = pat("<a href="3,8">bd cp</a> hh!2")</p>
indent 8 → <pre><code>val groove = pat("[bd cp](3,8) hh!2")</code></pre>
```

**Fixed at** `src/jvmMain/kotlin/afield/four/synth/TidalPatterns.kt` — one space added to each of
55 sample lines, the whole numbered list, not just the line that broke. Plus 3 lines in
`PointController.kt`. Never in the generated page.

`PatCondense.kt:586-593` writes the same `[bd cp](3,8)` figure and is immune, because it writes it
in **inline backticks, in prose**. That is the immune form, but copying it here would mean turning
samples into prose; adding the missing space keeps them samples.

**The generalising question, with predicate and population.** Over **2219** tracked `*.kt` files,
of which **1335** contain `/**`, holding **20739** KDoc blocks:

- **1007** KDoc lines are indented past their enclosing list's content indent but under the `+4`
  code-block threshold. Distribution of that relative indent: 1 → 121, 2 → 826, 3 → **60**.
- Of those 1007, **exactly one** also contains a markdown inline link (`](` outside inline
  backticks): the `TidalPatterns.kt` line above. After the fix that count is **0**.
- The 60 at relative indent 3 — the "clearly meant as a sample" shape — live in **five** files:
  `TidalPatterns.kt` **36**, `PatComposition.kt` 10, `PatAlgebra.kt` 7, `PatSignals.kt` 5,
  `PointController.kt` 2.

So the answer to "is there another one?" is **no, one instance tree-wide**, and the answer to "is
there another *sample* published as prose?" is **yes**.

After this branch: 971 lines match (a)+(b), **25** at relative indent 3 — `PatComposition.kt` 10,
`PatAlgebra.kt` 7, `PatSignals.kt` 5, `TidalPatterns.kt` 3. **22 of those 25 are real and 3 are a
false positive of the survey**, and the difference was settled by reading the rendered page rather
than the predicate: the classifier's list context is *sticky* (it resets on a list marker, not on
a return to column 0), so `TidalPatterns.kt`'s last three sample lines — the `data class Cue`
block, which sits after the numbered list ended — are scored as relative indent 3 while markdown
reads them at the top level. In the built page they are `<pre><code>`. The other 22 are `<p>`:
spot-checked in `reference/patterns/index.html`, `iter(4)`, `voiceLead(center`, `val grid  =
struct` and `shuffle(4)` all render inside a paragraph. The three `Pat*.kt` files were held by the
live branch `docs/voice-2026-09-17` on the night this landed and were deliberately left to it.

That correction is worth its own line, because it is the same mistake this page keeps documenting
in other people's work: an earlier draft of this section said `TidalPatterns.kt` 16 and "43 more
lines in three files", both taken from a survey that printed only its first 40 rows. The commit
messages of `edb159372` and `36c53969f` still carry those two numbers. **A number read off a
truncated listing is a number about the listing.**

**A cousin, found by regenerating.** KDoc reads `[k]` as a symbol reference, so the generator
published `PointController.kt`'s recurrence as

```text
x[k+1] - 2x`k` + x[k-1] = dt^2 (b F`k` + c v`k` + d)
```

`[k]` resolved to nothing and came out as a code span; `[k+1]` and `[k-1]` survived because they
are not identifier-shaped. Promoted to a code block by the mode 1 fix it would have published
literal backticks. Parenthesised indices are standard discrete-time notation and immune.
Population: of **1124** KDoc lines indented 4 or more, **66** contain an identifier-shaped
`[Ref]`; read by hand, all but one are hanging list-continuation prose where the reference is
deliberate, and the one exception (`GaussianSplatRenderer.kt:1094`, a GLSL `palette[vSource]`) is
inside a fence, which the generator does not rewrite.

**Guard.** The rendered-HTML guard is the guard: no markdown reader would ever flag
`[bd cp](3,8)` as a broken reference, because in `pat` notation it is not one. With the
grandfather entry deleted, a recurrence gates. A source-side lint on the relative indent was
considered and not written: its true positive rate over this tree is 1 in 1007, and the three
files holding the remaining 43 lines belong to someone else this week.

---

## Mode 2 — server-absolute, in the wrong prefix, in the wrong case

**Instance.** `[Open ↗](/offline.html?file=/field4/assets/…/metadata.edn)`.

Population: **137** occurrences on **17** pages, out of **513** tracked markdown files under
`public/docs` (`git ls-files public/docs | grep '\.md$'`). `agent_transcripts.md` carries 111,
`fieldml_host_bridge.md` 7, `vision_geometry.md` 5, and 14 pages one each. 130 are markdown links;
7 are raw-HTML `<iframe src>`.

The grandfather note said "134 pages". It was 137 occurrences on 17 pages. The `+136 more page(s)`
the built-site guard printed alongside it is the same 137.

**Mechanism — two independent 404s.** `public/zensical.yml` line **361** is
`site_url: http://openendedgroup.com/Field4/` and line **362** is `site_dir: field4`.

- The **link** half: `/offline.html` ignores the prefix, so it 404s on the published host.
- The **query** half: the viewer fetches the `?file=` value itself. Its candidate ladder
  (`src/commonMain/resources/offline-viewer.js:ednFileCandidates`) tries the value, the value
  minus its first path segment, and the value relative to the viewer's own URL. Under `/Field4/`
  all three miss for `/field4/assets/x`: wrong case, no prefix, and `/Field4/field4/assets/x`.

The two spellings differ **only in case**. That is why nothing local could reproduce it: preview
serves the site *at* `site_dir`, and macOS is case-insensitive anyway. It 404s on a case-sensitive
host and nowhere else.

**Fixed at** the pages, and at `AgentTranscriptIndexGuard.kt:222`, which prints the link to write
when one is missing and printed the broken shape. One rule with two halves — **no leading slash on
either**:

```text
[Open ↗](offline.html?file=assets/agent_transcripts/NAME/metadata.edn)
```

Not `/Field4/offline.html?file=/Field4/…`, which only works under this exact prefix. Verified on a
throwaway zensical project with `site_url: …/Field4/` and `use_directory_urls`: a markdown link
**and** a raw-HTML `src` written `offline.html?file=assets/a/b.edn` on a top-level page both
render as `../offline.html?file=assets/a/b.edn`, with no build warning, even though `offline.html`
is not in the docs source tree — the build syncs it into `site_dir` afterwards
(`build_zensical_docs.py:705`). The link lands on `/Field4/offline.html`, and the viewer resolves
`assets/…` against its own URL to `/Field4/assets/…`. The same pair works at the server root.

**Guard.** `tools/check_doc_offline_links.py`, because the built-site guard structurally cannot
see the query half: `resolve()` strips `?…` before resolving a path. It selects **137 references
on 17 of 513 tracked pages** and prints that on every run. It:

- gates on shape — no leading slash on either half, no `../`, no `site_dir` segment
  (case-insensitively), and the `?file=` target must be committed under `public/docs`;
- asserts its own premise: the prefix and `site_dir` still differ, and differ only in case;
- treats the occurrence count as a **floor**, so a regex or scan root that stops matching fails
  rather than reading as a clean tree;
- holds `CARRIER_PAGES` as a **bidirectional set-equality ledger**, so a removed carrier fails as
  loudly as a new one. Page set, not per-page counts: adding a transcript to a page already on the
  list is routine, a *new* page growing an offline link is the moment to check the shape.

14 unittests in `tools/test_check_doc_offline_links.py`.

---

## Mode 3 — dead by nature, and not a reference at all

**Instance.** `ar_reference.md` carries `https://FIELD_HOST:9092/extensions/remote_ar/index.html`
at `:6` and `<iframe src="/extensions/remote_ar/index.html?debug=1">` at `:45`. Both are paths
served by a **running Field**, not by the docs site.

**What the guard actually saw, which is not what the backlog said.** `:6` starts with `https://`
and is skipped by `SKIP_PREFIXES` — it was never in any report. `:45` is inside a `diffstatic`
fence and is rendered **escaped**: `&lt;iframe src="/extensions/…"`. No element is created and no
browser ever requests it. The only reason it appeared on the guard's report is mode 4. So the
grandfather entry's anchor (`:45`) was correct, and there was no dead link on the page to fix.

**Fixed at** the page's prose, which is the only thing it lacked. And it lacked less than it
looked: the page already said "connects back to the Field server over WebSocket" and "Use Field's
HTTPS URL on a phone or headset". What it did not say, and now says, is that `FIELD_HOST:9092` is
the machine running Field and that every `/extensions/…` path on the page is served by a running
Field and by nothing else — there is no host to substitute and no page to fetch until Field is up
— and that the leading slash in the `display("remote-ar", …)` sample is relative to *Field's*
server rather than to this site.

**The wrong fix**, explicitly: editing the URL into something that resolves. It would make the
guard pass and the instruction false.

**Guard.** None, and that is the finding. Once mode 4 is fixed there is nothing here for a
reference guard to check, because there is no reference. What protects it is that the page says so
in prose, and that the guard no longer invents the ref.

---

## Mode 4 — the extractor reading displayed code as markup

**Instance.** On a full build, `check_doc_site_links.py` reported eleven refs that are Kotlin
string templates and Python f-string debris — `../${src}`, `quote=True)`, `?root=$token` — inside
rendered `reference/source/**` pages, plus the one on `ar_reference.md` above.

**Mechanism, re-derived.** `d6d164822` made these a named, counted, non-gating category and said
the cause was that a source building HTML in a string literal "is emitted live rather than
escaped, so an unexpanded `$src` reaches the browser as a real anchor", with the fix belonging in
`generate_source_pages`. **The count was right and the cause was not.** Read back out of the built
site, `reference/source/jvmMain/afield/outputs/index.html` contains

```text
<pre class="code-diff-fence-static" …><code>…
&lt;iframe … src="/keyframe_browser.html?root=$token"
```

The `<` is escaped. The element does not exist. What survives unescaped is the **attribute text**,
quotes and all, so a regex over the raw HTML matches `src="…"` inside a code block. Nothing was
reaching the browser; the extractor was reading displayed source. `generate_source_pages` — which
is `afield.docs.SourcePageGenerator`, emitting `diffstatic` fences — needs no escaping change.

This is also why mode 3 and mode 4 are one mechanism on two page families.

**Fixed at** the extractor. `CODE_REGION` masks `<pre>`/`<code>` interiors, with a backreference
so a `<pre>` is not closed by a `</code>`.

**What the mask selects, and what it costs.** On the full build (source mirror present, 613
pages), of **146724** `href`/`src`/`poster`/`srcset` attribute matches in the raw HTML, **51** sit
inside a code element: 39 already skipped as external or in-page, 12 reported as broken
references, and **0 that resolved to a real file**. So the mask costs zero coverage.

**Guard.** The mask's own over-reach check, which is the part usually left out. The masked refs are
still resolved, and any that lands on a real file inside the prefix now **fails** — because a
`src=` inside a `<pre>` that names a real file is not displayed source, and a mask that starts
hiding real references must not be what makes the guard pass.

With this, `KNOWN_UNRESOLVED` is **empty**: all three 2026-09-16 entries fixed at source. The
`mirror_code` category is deleted; it was weaker than the mask, excusing a bogus ref whenever
every referrer happened to be a mirror page, fence or no fence. `stale_known_unresolved` stays,
and its test now runs against a synthetic ledger — an empty dict made the old test pass vacuously,
which is how it broke when the list emptied.

---

## Mode 5 — the page resolves, the anchor does not

**How it was found.** Every guard in this tree strips `#…` before resolving, so nothing checks
whether the fragment exists on the page it lands on. Surveying it over the 613-page build, with
code regions masked: **37757** local refs carry a fragment (23704 of them same-page), **37755**
fragments were present on their target page, and **2** were absent.

Two out of 37757 is the point. This mode does not produce a broken-looking page; it produces a
link that opens the right page at the top instead of at the thing it promised, which is the
failure a reader is least likely to report.

**Instance.** `public/field4/reference/imaging/index.html` carried a citation link
`../source/jvmMain/afield/stdlib/imaging_filters/#L68`, and the source page it points at emitted
64 `id="L…"` anchors, of which `L68` was not one — while `anchors.json` listed 65 lines including
68. (The other of the two is `404.html`'s `#__skip`, the theme's own skip-to-content link on a
page with no skip target. Theme output, authored by nobody here, left alone.)

**Mechanism.** `SourcePageGenerator` writes a heading that ends in its own anchor block —
`### <code…>…</code> { #L68 data-search-exclude="true" }` — and Python-Markdown's `attr_list`
finds that block with `[ ]+\{\:?[ ]*([^\}\n]*)[ ]*\}[ ]*$`, searched **leftmost**. A *balanced*
brace pair earlier in the heading's own text swallows the search, and the real block is left as
literal text and slugified into the id. Isolated on python-markdown 3.10.2:

```text
### a { b } c { #L1 }   ->  <h3>a { b } c { #L1 }</h3>       no id at all
### a }}) b { #L1 }     ->  <h3 id="L1">a }}) b</h3>         fine
### a } b { #L1 }       ->  <h3 id="L1">a } b</h3>           fine
```

So it takes an opening brace *and* a later closing one. `imaging_filters.kt:68` is
`override fun toString(): String = "Kernel1D(${taps.joinToString(", ") { "%.4f".format(it) }})"`,
whose body carries exactly that.

Note what the two halves of the contract do: `anchors.json` is written from
`page.declarationLines`, which is appended to at the moment the heading is *emitted* — so the
generator's own bookkeeping was correct and complete. The anchor was lost downstream, in the
markdown renderer, which is why no amount of checking the generator against itself would have
found it.

**Fixed at** `SourcePageGenerator.attrListSafe`: braces in the heading's own text become `&#123;`
and `&#125;`, which render identically. Escaping rather than stripping, because the brace is real
Kotlin and must still be shown; and at the heading only, because a brace in a fence or a table
cell is harmless.

**Guard.** `verifyPage` now asserts, per page, that

* no heading line carries a literal brace before its `{ #L… }` block, and
* the set of `{ #L… }` headings the page emits equals the set of lines `anchors.json` will claim —
  **both directions**, so a claimed-but-unanchored line and an anchored-but-unclaimed line each
  fail with their own message.

The predicate is deliberately *stricter* than the failure: 681 headings carry a brace, and only
one of them lost its anchor. Rejecting every brace is mechanical and exact; modelling `attr_list`'s
leftmost search inside this generator would be a second copy of someone else's regex.

**Teeth, proved by counterfactual.** With `attrListSafe` stubbed to the identity function,
`checkSourcePages` reports **681 problems**; with it in place, `BUILD SUCCESSFUL`. After the fix
the survey reads **0 of 454** source files with a claimed-but-unemitted anchor (was 1) and
**37756 of 37757** fragments present site-wide, the remaining one being `404.html`'s.

No repo drift: `reference/source/**` is the one generated docs tree that is not committed, so this
changes 681 headings per build and nothing in git.

---

## The population question, settled

Last night's report left `607 of 739` against `155 of 287` as an open question. They were never in
conflict. They are the two build flags, and the source mirror is generated per build and is by far
the largest page family, so it decides.

| build | pages checked | `*.html` in site | mirror pages | local refs | distinct targets | resolving |
|---|---|---|---|---|---|---|
| default | **613** | 745 | 455 | 121655 | 1575 | **1575** |
| `--no-source-pages` | **158** | 290 | 0 | 41270 | 1483 | 1120 (+360 un-generated) |

Both measured on 2026-09-17 in one worktree, same commit, same night. The `--no-source-pages`
figure omits the only page family that was failing, so a population quoted without the flag says
nothing. The guard now prints a `build shape:` line naming the mirror's presence and its page
count, and a `displayed code masked:` line, so neither number can be quoted bare again.

---

## Traps this run added

- **A backlog entry can carry a correct measurement and a wrong cause.** Mode 4's count was right
  in `d6d164822` and its mechanism was wrong, and the proposed fix would have gone in the wrong
  file. Re-derive the explanation, not just the number.
- **Check what a page already says before filing it as not saying it.** `ar_reference.md` already
  disclosed most of mode 3.
- **A guard's grandfather list emptying can break its own tests vacuously.** An empty dict makes a
  "stale entry is reported" test pass while covering nothing. Test the mechanism against a
  synthetic ledger and assert the real one is empty separately.
- **A KDoc-only edit does not stand alone.** `checkCodebaseReference` is a pre-commit gate, and the
  generated-artifact guard refuses to verify a staged snapshot while relevant working-tree changes
  are unstaged — so a KDoc change obliges `./gradlew generateCodebaseReference` in the same tree as
  anything else touching `public/docs`. Grade the drift: this branch's KDoc change moved exactly
  two pages, `patterns.md` (whitespace-only under `git diff -w`) and `simulation.md`, and did not
  move `dev_docs/kdoc_worklist.md`.
- **Python-Markdown is not CommonMark on list indentation.** `tab_length` is 4 whatever the marker
  width, so an ordered-list item's code block needs 8 where CommonMark would take 7. A KDoc
  written to the CommonMark rule renders as prose.
- **A survey that caps its own output will be quoted by its cap.** `sel[:40]` printed 40 of 60
  rows and the per-file breakdown taken from it was wrong for two commits. Aggregate first, print
  second.
- **No guard in this tree checks a fragment.** Every one of them strips `#…` before resolving, so
  a reference whose page is at 200 and whose anchor is gone is invisible to all of them. That is
  where mode 5 was hiding, and it is still the right place to look for mode 6.
- **Do not edit the working tree while `docsGates` runs.** `checkTestTreeWrites` takes a baseline
  and compares, and cannot tell your edit from a test write — it says so in its own failure
  message. One `docsGates` run here failed for exactly that and nothing else.
- **A default docs build in a fresh worktree recaptures.** Ten fences carry `force=true` across
  four pages, and a plain build honours them: the first attempt here rewrote 20 committed assets,
  one of them from 9 MB to 26 KB. `FIELD_DOCS_RUNNER_MODE=never` (or `--page <one-page>`, which
  sets runner mode `never` for everything else) gives a full build with
  `regenerated=0, reused_existing=640`.

## Where the pieces are

| | |
|---|---|
| `tools/check_doc_site_links.py` | the built-site guard; `CODE_REGION`, the empty `KNOWN_UNRESOLVED`, the `build shape:` line |
| `tools/test_check_doc_site_links.py` | 33 tests (was 25) |
| `tools/check_doc_offline_links.py` | the offline-viewer link shape lint; `CARRIER_PAGES` |
| `tools/test_check_doc_offline_links.py` | 14 tests |
| `src/jvmMain/kotlin/afield/four/synth/TidalPatterns.kt` | mode 1's origin |
| `src/jvmMain/kotlin/afield/four/synth/PatCondense.kt` | the immune form: inline backticks |
| `src/jvmMain/kotlin/afield/docs/AgentTranscriptIndexGuard.kt` | mode 2's dictating guard |
| `src/commonMain/resources/offline-viewer.js` | `ednFileCandidates`, why a relative `?file=` works |
| `src/jvmMain/kotlin/afield/docs/SourcePageGenerator.kt` | mode 5: `attrListSafe`, and `verifyPage`'s anchor invariant |
| `dev_docs/DOCS_SITE_PREFIX_REFS.md` | the prefix family and the guard's design |
