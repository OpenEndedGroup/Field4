#!/usr/bin/env python3
"""Rank public/docs pages by the faults Marc actually names, not by lexical tics.

The predecessor scorer (documentation-language-improvement/score_prose.py) scored the four
pages Marc complained about on 2026-09-13 at 16, 12, 19 and 3 — all of it either false
positives (`seam` naming a real signature, `gate` naming a real Faust control) or em-dash
density. Every fault he named was invisible to it:

    "### The reading is total; the *parameterisation* is where the refusals live"
    "## Two ways the numbers will surprise you"
    "The left panel is the sanity check, and it carries a trap."
    !!! warning "Mirrors, and where a TRS triple loses one"   (30 lines, mid-flow)
    "~0.9 KB per voice per block is real GC pressure ... That is the next thing to do here."
    "There's a lot of measuring here of things I don't really care about."

So this scorer looks at headings, slide labels, plan language, methodology defences,
oversized admonitions, numbers-in-bullets, and measurement mass. Lexical tics are kept only
where they have never been a false positive.

    python3 score_voice.py public/docs              # ranked table
    python3 score_voice.py public/docs --json       # machine-readable
    python3 score_voice.py public/docs --why PAGE   # every hit on one page, with line numbers
    python3 score_voice.py public/docs --calibrate  # check against known-good and known-bad
    python3 score_voice.py public/docs --assets     # the hand-authored demo front pages

CALIBRATION IS PART OF THE TOOL. Marc's own pages (index, survivalGuide, funFeatures,
kotlin_overview) are the target voice and must stay near zero; a change that lifts them is
wrong however good it looks on a bad page. `--calibrate` asserts that.
"""
import re
import sys
import json
import pathlib
import collections

# --------------------------------------------------------------------------------------
# Marc's voice: never edit these, and use them as the zero point.
# --------------------------------------------------------------------------------------
MARCS_OWN = {"index.md", "survivalGuide.md", "funFeatures.md", "kotlin_overview.md"}

# --------------------------------------------------------------------------------------
# A. Heading faults. The single highest-yield detector: a bad heading is a bad section.
# --------------------------------------------------------------------------------------
HEADING_TELLS = [
    # "Mirrors, and where a TRS triple loses one" / "A steal clicks, and by how much"
    ("head-comma-clause", r",\s+(and\s+)?(where|how|why|what|whether|by how much|when|which)\b", 8),
    # The same fault with a pronoun instead of an interrogative, which head-comma-clause's word
    # list cannot reach: "A radius-one blur is the usual choice, and it is the wrong one here",
    # "The two marchers disagree about genus, and that is the finding". The 2026-09-14 admonition
    # sweep named this as the cheapest win left and did not take it because it was uncalibrated.
    # Calibrated now: on a 131-page tree the bare form fires four times, three of them real
    # (chain_life3d_render x2, tetgen x1) and one not -- puppet_browser.md's "First, it is simply
    # a browser", where the comma is a sentence-opening adverbial and not a subject split. Hence
    # the anchored negative lookahead, and the {6,} that wants a real subject before the comma
    # rather than one short word. Marc's own four pages stay where they were.
    ("head-pronoun-clause",
     r"^(?!\s*(first|second|third|then|next|finally|also|instead|so|afterwards|meanwhile)\b)"
     r"[^,]{6,},\s+(and\s+)?(it is|that is|this is)\b", 8),
    # NOT a bare count: "Three things you'll want to know" is Marc's own, on survivalGuide.md.
    # It is the promise of stakes that is the fault, so "Two ways the numbers will surprise you"
    # is caught by head-stakes below and a plain "Three kinds of refusal" is left alone.
    # "will surprise you", "the trap", "the payoff", "what goes wrong"
    ("head-stakes", r"\b(surprise\s+you|surprising|the trap|the payoff|the catch|the twist|"
                    r"goes wrong|pay attention to|gets? interesting|nobody (guesses|expects))\b", 9),
    # "The reading is total; the parameterisation is where the refusals live"
    ("head-semicolon", r";", 8),
    # "And therefore: inverse design" / "A third reading: the derivative"
    ("head-sequence", r"^\s*(and (therefore|so|then)\b|a (second|third|fourth|fifth) "
                      r"(reading|backend|way|take|pass)\b)", 7),
    # an evidence frame instead of a subject
    ("head-evidence", r"\b(measured rather than|rather than (claimed|assumed|asserted)|"
                      r"and what it does not|and why there are|stated because|"
                      r"is not a|isn't a)\b", 7),
    # "What this does not do yet", "Where X loses one"
    # Weak on its own — "When it does not look right" is a good troubleshooting heading. It only
    # matters when it also trips head-comma-clause ("What the repairs do, and what they do not fix").
    ("head-negspace", r"\b(does not|doesn't|cannot|can't|never) \w+", 2),
]

# Families that must not fire on a page's `# H1` title. Narrowed 2026-09-17 after hit-checking
# `head-negspace` over the whole tree: it fires six times, and two are the page's own title --
# `# Solids that cannot fail: Manifold in Field` and
# `# State estimation: tracking what you cannot measure`. Both are *subjects*: a negation in a
# title names what the software is for ("solids that cannot fail" is the selling point of
# Manifold; "what you cannot measure" is what a state estimator is *for*). The fault the family
# exists for is section-level evasion -- `## What this does not do`, sitting where a subject
# should be -- and a page cannot evade its own content in its title.
#
# Deliberately narrow: only this family, and only level 1. A title with a genuine fault still
# scores, because `# Faust in Field, and what it does not do` trips head-comma-clause and
# head-evidence, neither of which is exempt here.
H1_EXEMPT = {"head-negspace"}

# A''. Titles whose subject is the *document* rather than the software. Found by reading all 53
# always-open admonitions on 2026-09-14, after Marc demoted `!!! note "The repository records a
# different ladder"` on physics.md: `admon-oversized` only counts a `!!!` over 14 lines, and 51 of
# the 53 are under it, so this whole genre was invisible. It is the "AI leaving notes for itself /
# showing it's working" complaint, one level in from the page body.
#
# Scored only on an always-open `!!!`, for the same reason as admon-oversized: a collapsed `???`
# costs a reader nothing and demoting is the fix, so scoring both would punish the repair.
SELF_REFERENTIAL_TITLE = [
    # "Why there is no figure of the header" / "...of the renderer's own spans" — a box whose
    # subject is the page's own inability to photograph something.
    ("admon-no-figure", r"^why (there is|this page has|the page has) n(o|ot|othing)\b"
                        r"|^why (this|the) page\b", 8),
    # A measurement caveat or a reconciliation of two sets of numbers, which is `dev_docs`
    # material by the disposal table. "Treat every millisecond on this page as an observation,
    # not a guarantee"; "A calibration, because the statistic has a known bias"; "The repository
    # records a different ladder" — the one Marc demoted by hand, which is the calibration case
    # for this pattern.
    #
    # A first draft of this used a bare `on this page`, which reads like the right signal and is
    # not: it scored 1 true positive against 1 false, firing on
    # `!!! warning "The four figures on this page are placeholder cards"`, a limitation a reader
    # must have *before* trusting a picture. A title being about the document is not the fault.
    # Being about the *measurement* is.
    ("admon-measurement-caveat", r"\bnot a guarantee\b|\bas an observation\b"
                                 r"|^(a|the) calibration\b|\brecords a different\b", 7),
]

# --------------------------------------------------------------------------------------
# B. Slide labels in body prose. A heading's fault, one level down.
# --------------------------------------------------------------------------------------
BODY_TELLS = [
    # "the whole story" is Marc's own idiom (survivalGuide.md:7) and is excluded on purpose.
    # `the` is required before point/argument: "is a point cloud" and "is a point on the
    # surface" are ordinary geometry prose and matched "is a point" when this said (the|a).
    # `the` does not screen off `point`'s literal senses either. "These four are the point at
    # which it stops doing that" is temporal, and "`onElement` is the point on the element
    # itself" is geometric; neither is the evaluative label. Both are excluded by lookahead.
    # "the point of/for/behind X" is deliberately NOT excluded: "The middle panel is the point
    # of the family" (spatial_geometry_examples.md:222) is a real hit.
    #
    # CALIBRATION, 2026-09-17 (eighteenth firing). The *subject* discriminates, and nothing else
    # does. Marc's complaint was an evaluative label bolted onto a concrete thing -- "The left
    # panel is the sanity check", "The middle panel is the point of the family". When the subject
    # is instead an anaphor (`which`/`that`/`it`/`this`) the construction is not labelling a
    # thing, it is naming which of the facts just stated is the load-bearing one, and every
    # scored instance of that form in the tree was doing real work: "which is the point: a groove
    # you cannot reproduce is not a groove", "which is the point of the flag being opt-in",
    # "That is the point: one engine, several genomes". Six hits, six false positives. The four
    # lookbehinds below are each fixed-width, which Python's `re` requires.
    #
    # `of no return` joined the `point` lookahead on 2026-09-19, found by `in_fences.py` on
    # `physics.md:403` -- "100 % strain -- a spring at twice its rest length -- is the point of no
    # return here". That is a threshold named by a fixed English idiom, not an evaluative label,
    # and the idiom has no form in which it could be this fault. One instance in the whole tree,
    # so this buys a single true negative; it costs nothing and it cannot mask a real hit.
    ("slide-label", r"(?<!which )(?<!that )(?<!it )(?<!this )"
                    r"\b(is|are) the (sanity check|payoff|"
                    r"point(?!\s+(at which|where|when|on|of no return)\b)|argument|"
                    r"interesting (part|bit)|real (trick|insight)|one that matters|one to (have|watch))\b"
                    r"|\b(is|are) an? (sanity check|payoff|real (trick|insight))\b", 7),
    # `X is the whole design` -- the stakes label bolted onto a concrete fact, the same move as
    # `is the sanity check`. Added 2026-09-18 after finding SIX in public/docs, every one the bare
    # declarative and every one a true positive: petri_nets.md, chain_pattern_particles.md, and
    # four KDoc file overviews (MorphoGenome, PythonInterface, PatSynth, GlslAdSurrogate). Three
    # were near-identical -- "N parts/types, and the division/split between them is the whole
    # design" -- which is why it was findable only by grepping the shape across the whole tree,
    # never within one page.
    #
    # Scoped to `design`, NOT to `point`/`story`, deliberately. `is the whole story` in exactly
    # this bare declarative form is MARC'S OWN VOICE, twice: survivalGuide.md:7 and
    # funFeatures.md:304 ("[Clojure in a box](clojure.md) is the whole story."). Widening the noun
    # set fires the detector on the target voice, which is the one thing --calibrate forbids. The
    # `which is/that is the whole point of X` form is ordinary English -- but that is an argument
    # about `point`, not about `design`, so slide-label's four lookbehinds are deliberately NOT
    # reused here. `petri_nets.md`'s "That sounds like an implementation note. It is the whole
    # design." is pronoun-led and is the *purest* instance of the fault; `(?<!it )` hid it, and the
    # family found 1 of its 6 until the lookbehinds came off. There is no benign pronoun-led form
    # of "is the whole design" anywhere in the tree.
    #
    # Zero hits in public/docs after tonight, and kept anyway: eight more live in unpublished KDoc
    # and code comments (SolidExact.kt, RectSLPattern.kt, TidalPatterns.kt, pointing_pointer.kt
    # among them), and the sibling KDoc task promotes member KDoc to published file overviews
    # weekly. The fault is seeded, not extinct.
    ("whole-design", r"\b(is|are) the (whole|entire) design\b", 7),
    # The doc assigning INTEREST or TRUST instead of stating the fact -- "The interesting half
    # gives you Pearson, Spearman, Kendall", "Smoothing is the more interesting one", "it is the
    # reason to trust the result". Same family as `is the sanity check`: an evaluative label
    # standing where a subject or a reason belongs. Added 2026-09-18 (twenty-fourth firing).
    #
    # THIS EXISTS BECAUSE slide-label HAD A STRUCTURAL BLIND SPOT, and it is the more useful half
    # of tonight's finding. slide-label has carried `interesting (part|bit)` since the eighteenth
    # firing, and it matched NONE of the six faults fixed tonight, because it only ever matches
    # the label in PREDICATE position -- `(is|are) the interesting part`. Four of the six were in
    # SUBJECT position -- "The interesting part is the range policy", "The important part is to
    # keep transform intent in the scene graph" -- which is the same fault with the clause order
    # reversed and was invisible to every detector for twenty-three firings. A family that names
    # a label must match it on both sides of the copula.
    #
    # Two scoping decisions, both measured rather than assumed:
    #  - `part|bit` appear in the SUBJECT arm only. Their predicate form is slide-label's, whose
    #    four lookbehinds are calibrated on `point` (6 hits, 6 false positives) and must not be
    #    disturbed. The predicate arm here takes `half|one|thing|case`, which slide-label never
    #    covered, and takes NO lookbehinds: "it is the more interesting half"
    #    (spatial_geometry_examples.md:701) and "it is the reason to trust the result"
    #    (path_tracing.md:484) are both pronoun-led and both true positives. Third firing running
    #    to find that a lookbehind inherited from a neighbouring family hides that family's
    #    purest instances -- see whole-design above, and note the two families reached the same
    #    conclusion independently.
    #  - `important|crucial|key` earn their place on exactly one hit (fabricate_renderer.md:54)
    #    and zero false positives. Kept because the fault is the construction, not the adjective.
    #
    # Measured over all three surfaces before adding: 12 hits, ZERO on Marc's four pages and ZERO
    # under assets/. Ten read as true positives. The two standing keeps are figure captions
    # pointing at a region of a picture, which is a caption doing its job:
    # graph_connectivity_examples.md:80 and dough_dsp_sound.md:16.
    ("reader-verdict",
     r"\bthe (more |most )?(interesting|important|crucial|key) (part|half|one|thing|bit|case)\b"
     r"|\b(is|are) the (more |most )?(interesting|important|crucial|key) (half|one|thing|case)\b"
     r"|\bthe reason to trust\b", 7),
    # A snippet caption written as a test assertion rather than as documentation --
    # "This should produce a quadratic curve over the default domain". That is the voice of
    # someone checking their own snippet, and it is the purest form of Marc's "AI leaving notes
    # for itself / showing it's working" complaint. A reader wants the fact: "Produces a
    # quadratic curve". Added 2026-09-18 (twenty-fifth firing).
    #
    # FOUND BY A SHAPE GREP ACROSS A WHOLE PAGE, which is the route the task file names and the
    # scorer cannot take: `functions_examples.md` carried ONE HUNDRED of these, one per entry of
    # its expanded reference, and scored 0. Every detector saw a page of short factual captions.
    # The tell is only visible in bulk, and only if you count the shape rather than read a
    # section. A repeated-sentence-opening census over the tree is what surfaced it; it is worth
    # re-running, and the script is in the night-log entry.
    #
    # THE MODAL SENSE IS EXCLUDED, and that exclusion is the whole of the calibration. `should`
    # as an obligation on the *caller* is correct reference prose and there is no other word for
    # it: `shaders.kt:1668` "This should only appear at macro expansion sites; calling it
    # directly throws" and `ImageReducer.kt:704` "This should be called after GPU execution has
    # completed". Both are real KDoc, both would publish, and `be|only` skips them. The fault is
    # `This should <bare transitive verb>` describing what the *snippet* does.
    #
    # Measured over every `.md`, `.kt` and `.py` in the repository at `main` before adding:
    # 102 hits in 3 files. 100 are the fault, all on the one page. The other two are
    # `doc.kt:91` (a parenthesised design TODO) and `optional/kratos/tests/test_worker.py:334`
    # (a test comment), neither on a scored surface and neither the harness voice. 100 true / 0
    # false. Zero on Marc's four pages and zero under `assets/`.
    #
    # Unanchored on purpose. `^` would be the obvious anchor and would be wrong: the family is
    # matched line by line, so a caption that starts mid-line would be invisible, and the
    # mid-sentence form ("..., and this should produce...") is the same fault.
    ("harness-voice",
     r"\bthis should (?!be\b|not\b|never\b|only\b|always\b|also\b)[a-z]+\b", 7),
    ("carries-a-trap", r",\s*and\s+(it|that|this)\s+(carries|hides|holds)\s+a\b", 8),
    ("note-what-is", r"\bNote (what|that) (is|are)\s+(not|missing|absent)\b", 6),
    # `so here is the (picture|number|table)` was the second memorised-list miss found on
    # 2026-09-18, the same bug as `methodology` below: the three nouns are the three some earlier
    # firing happened to delete. `morpho_examples.md:284` read "A still can always be accused of a
    # lucky camera, so here is the camera moving" -- the identical move, invisible because the noun
    # was `camera`. Widened to `\w+`. Measured over every .md/.kt/.py in the repository: 3 hits,
    # **0 on any scored surface** (two `.kt` test comments and one `dev_docs/` note), so the
    # widening costs nothing and would have caught the one instance that existed.
    ("claim-about-a", r"\b(that|this) is a claim about\b|\bso here (is|are) the \w+\b", 7),
    ("look-at-the", r"^\s*(Look at|Watch|Notice|Consider) (the|what|how)\b", 3),
    # pre-emptive defence of a correct result — five on one page, once per section
    ("predefence", r"\b(that is|and that's|which is) (the right answer|correct),? (not|rather than)\b"
                   r"|\bis not a bug\b|\bnot a (shortfall|flaw|failure) (to be|that)\b"
                   r"|\brather than a rendering artefact\b|\bNothing is wrong\b", 7),
    # methodology narration: the page explaining why its own measurement is fair
    # `is here because` was dropped from this alternation on 2026-09-17: its only two hits in the
    # tree explain why an *API member* exists ("`SAMPLER` is here because a sampler is a value the
    # emitter needs", "`sgd` with momentum is here because it is sometimes what you want
    # artistically"). That is reference documentation doing its job, not a measurement defending
    # itself. The rest of the alternation is untouched.
    ("methodology", r"\b(exists for exactly this reason|"
                    r"the like-for-like comparison|median of \w+ (timed )?windows|"
                    r"is what makes (the|this) claim|rather than eyeballing|"
                    r"every row of it asserted|reads as a pass|would make the \w+ free|"
                    r"there is no bug that makes)\b", 7),
    # `share no line of code` RETIRED on 2026-09-18 (twenty-seventh firing), for the opposite of
    # the usual reason. Generalising it the way `methodology-general` generalised its siblings
    # gives `shares? no (line of )?code` + `no code in common` + `independent implementations?`,
    # which measures **65 hits across the repository** and is the house idiom for saying a
    # cross-check is independent -- `SolidAgreement.kt`, `ManifoldTest.kt`, `RectSLFn.kt`, ten
    # `public/docs` pages, and `dev_docs/SOLIDSL_AGREEMENT.md`'s own section heading. It names a
    # real property of a real oracle and there is no other phrase for it: the `quietly` /
    # `by-design` case again.
    #
    # The literal branch meanwhile fired on nothing. `share no line of code` occurs only under
    # `dev_docs/` and in `.kt`, never on a scored surface, so it scored zero while implying its
    # general form was a fault. Retiring it costs no coverage and removes the precedent.
    # A memorised branch is not always a family waiting to be found -- sometimes it is a house
    # idiom that one deleted sentence happened to contain, and only the measurement tells you which.
    # `methodology` above is a MEMORISED LIST, not a family: every branch of it is the literal
    # sentence some earlier firing deleted. It therefore cannot generalise, and on 2026-09-18 that
    # was measured rather than suspected -- `imaging_examples.md` carried forty lines of benchmark
    # methodology ("Read the intervals before reading the ratios: these are laptop numbers",
    # "two arms whose intervals overlap are a tie") and scored **0 for twenty-five firings**. Its
    # own `median of \w+ (timed )?windows` branch even misses `tetgen.md`'s "median of three runs",
    # because it hard-codes the noun the one page it was cut from happened to use.
    #
    # This is the general form: the vocabulary of defending a benchmark's *conduct* to a reader who
    # did not question it. Measured over every .md/.kt/.py in the repository before adding --
    # **7 hits, 0 false positives on any scored surface**: 6 on `imaging_examples.md`, 1 on
    # `tetgen.md`, and 1 in `dev_docs/FAUST_MEASUREMENTS.md`, which is the correct destination and
    # is not a scored surface. Zero on Marc's four pages, zero under `assets/`.
    #
    # Two exclusions are load-bearing and were both found by measuring:
    #   - `median of the WINDOW` (singular) is a rolling-median filter, a real API -- `timeseries.kt`
    #     and `reference/math.md` both document one. Only the plural nouns are scored.
    #   - bare `warm-up passes` is a CAPABILITY ("warm-up passes available so the first buffer is
    #     not cold", `reference/synthesis.md`), not a defence. Dropped from the alternation.
    ("methodology-general",
     r"\b(laptop numbers|scheduler noise"
     r"|median of (?:a |the )?(?:\w+ )?(?:timed )?(?:runs|passes|timings|repeats|windows)\b"
     r"|read the (?:intervals|error bars|ratios) (?:before|as)"
     r"|intervals overlap|can be re-?measured|so the claim can be"
     r"|noise rather than a real)\b", 7),
    # notes to self: a plan, not a limitation. A stated limitation is fine and is not matched.
    ("plan-in-docs", r"\b(the next thing to do here|next thing to do is|that is the next step|"
                     r"is the next thing|not wired up yet|for when it does|"
                     r"is what we should|TODO|FIXME|remains to be done|"
                     r"has not been done yet|would be nice to)\b", 9),
    # the effort brag, which survives from the old scorer because it never false-positived
    ("effort-brag", r"\b(hard[- ]won|meticulous|painstaking|non[- ]trivial|"
                    r"surprisingly (hard|tricky|subtle)|took (several|many) \w+ to|"
                    r"worth doing|a lot of work)\b", 6),
    # the tics with a clean record
    ("load-bearing", r"load[- ]bearing", 8),
    ("honest", r"\bhonest(y|ly)?\b", 6),
    ("worth-knowing", r"\bworth (internalis|internaliz|knowing|remembering|saying|sitting with)", 5),
    # `quietly` and `by-design` were retired on 2026-09-17 -- see RETIRED for the hit counts.
    ("the-trick-is", r"\b(the trick (is|here)|the key insight|the real (trick|insight))\b", 5),
    # `get-out-of-way` and `in-as-many-words` were AUDITED on 2026-09-18 as suspected
    # memorised lists and both come back CLEAN AS THEY ARE -- keep them narrow.
    #   - widening to bare `out of the way` measures 10 hits and every one is the *physical*
    #     sense: "parked out of the way", "keeps the box out of the way", "gets the one heavy
    #     compile out of the way". 0 true.
    #   - widening `in as many words` to the family it looks like it belongs to
    #     (`(does not|never) says? (so|as much)`, `without saying so`, `not spelled out`)
    #     measures 28 hits, of which `without saying so` is 24 and is the standard term of art
    #     for a silent failure -- "falls back ... without saying so", "will rot without saying
    #     so". Warning a reader that a mistake will not announce itself is the `quietly` case,
    #     already retired for exactly this. 0 true. The bare phrase itself never occurs on a
    #     scored surface at all; all three hits are `.kt` KDoc describing a guard's message.
    # `note-what-is` was audited the same night: its general form (any of Note/Notice/Observe +
    # what/which + is/are + not/missing/absent) finds the SAME two hits as the literal, one of
    # them inside a fence. The form is genuinely rare, not narrowly spelled.
    ("get-out-of-way", r"(one fact to get out|out of the way (first|before))", 6),
    ("in-as-many-words", r"in as many words", 5),
    # The page narrating its own act of verifying, as opposed to reporting what it found.
    # `chain_photo_to_solid.md` scored **0 for its whole life** while carrying four of these in
    # one section: "This page takes that seriously and turns it into one", "a recorded claim put
    # back on the bench", "the table above is the measurement", "The chain re-measures that on
    # plaques". Marc's "a lot of measuring here of things I don't really care about" is this.
    #
    # Measured over every .md/.kt/.py in the repository before adding: 4 hits, all four on that
    # one page, 0 false positives. `box` is deliberately NOT in the subject alternation and
    # `re-runs` not in the verb alternation -- "when the box re-runs" is a real lifecycle event
    # and occurs on two scored surfaces (`afield_four_graphics.md`, `reference/data.md`) plus
    # `JSONProcess.kt`. Firing on one page is the honest state of a *form*, not a memorised list:
    # the grammar is general even though today's incidence is one.
    ("self-verification-narration",
     r"\b(this|the) (page|chain|section|guide) "
     r"(takes|puts|re-?measures|re-?checks|proves|confirms|settles)\b"
     r"|\btakes? (that|this|it) seriously\b"
     r"|\bput back on the bench\b"
     r"|\b(the|that) (table|figure|box|clip|panel) above is the "
     r"(measurement|evidence|proof|check)\b", 7),
    # The page congratulating itself on its own epistemics: "measured rather than assumed",
    # "checked rather than admired", "demonstrated rather than asserted". Marc's complaint that
    # more than half the Faust docs was "AI ... showing it's working" is this move in bulk.
    #
    # CALIBRATION, 2026-09-16 (seventeenth firing). The *broad* pattern `\brather than\b` is a
    # false-positive generator and must not be added -- see `rather than` in RETIRED. The narrow
    # pair below is a different move: both sides describe the *documentation's* virtue, not the
    # code's behaviour. It fired 9 times across the whole tree on first run and 0 times on
    # Marc's four pages. Three of the nine were on generated `reference/` pages, which is what
    # prompted --generated.
    #
    # WIDENED 2026-09-18 (twenty-seventh firing): the two halves had to be ADJACENT, so any
    # qualifier between them hid the move. `chain_photo_to_solid.md` read "with the number of
    # split vertices measured on this object rather than assumed" and scored 0 on it. Allowing
    # 1..5 words in the gap measures **9 further hits and 0 false positives**. None of the nine
    # is on a scored surface today: eight are `.kt` KDoc and test prose, which is where that
    # sentence belongs, and the ninth is `rectsl_inverse_animation.md:341` -- which is a code
    # comment INSIDE a `diffstatic_runner` fence, so `split_prose` strips it and the fence rule
    # forbids editing it anyway. The widening is therefore prophylactic: its one demonstrated
    # catch is the `chain_photo_to_solid.md` sentence, fixed the same night it was found.
    # The gap is bounded at 5 and `[ ,]` rather than `.*` on purpose: unbounded, it starts
    # joining two unrelated clauses across a comma.
    ("epistemic-boast",
     r"\b(measured|checked|demonstrated|shown|proved|proven|tested|verified|asserted|counted)\s+"
     r"(?:\w+[ ,]+){0,5}"
     r"rather\s+than\s+"
     r"(inventoried|written|asserted|claimed|described|admired|assumed|guessed|remembered|"
     r"imagined|hoped|believed|stated|invented|eyeballed)\b", 6),
]

# Lexical patterns retired from the old scorer, with the reason. Do not re-add without
# checking hit by hit: each of these was verified to be a false positive across the tree.
RETIRED = {
    "seam": "names a real signature in solidsl/rectsl/sound; 42 of sound_paths.md's 43",
    "gate": "names a real Faust control and a real Gradle task",
    "used to": "legitimately describes a prior state of a *shape*, not a changelog",
    "in practice": "ordinary English, 1 point, no signal",
    "under the hood": "never fired on a real fault in 28 runs",
    "rather than": "the bare form. Marc 0.27-1.29 per 1k vs a tree median of 2.2 and a top of "
                   "7.5, so the density gap is real -- but the hits are overwhelmingly good "
                   "documentation ('dropped rather than clamped'). Only the narrow "
                   "epistemic-boast pair is scored",
    "quietly": "9 hits in the whole tree and 9 of them are the standard term of art for a "
               "silent failure -- 'fails quietly', 'quietly wrong', 'quietly becoming wrong'. "
               "Warning a reader that a mistake will not announce itself is documentation doing "
               "its job, and there is no other word for it",
    "by-design": "66 hits, checked bare and reasoned. Every one is a design fact that stops a "
                 "reader filing a bug -- 'Deliberately `Float`, not `Double`: GLSL `float` is "
                 "what is being mirrored', 'carry NO normals on purpose', 'half a path on "
                 "purpose'. This is the 'Why X answered with a design fact' the task file says "
                 "to keep, and the bare-form hits are table cells and code strings",
    "is here because": "dropped from the `methodology` alternation, not from the family. Both "
                       "hits explain why an API member exists, which is reference prose",
}

# --------------------------------------------------------------------------------------
# C. Layout and mass. These are what "I'd much rather just read about the cool language"
#    measures, and no lexical scorer can see them.
# --------------------------------------------------------------------------------------
# A table header naming residuals, timings or byte counts is a measurement table.
MEASUREMENT_HEADER = re.compile(
    r"\b(rel\b|err\b|error|residual|dev/|µs|us\b|ms\b|ns\b|bytes?|allocat|ratio|"
    r"predicted|measured|asserted|volume rel|tris? @|@ h=|sagitta|iters?\b|"
    r"per 1k|per block|realtime|delta|rmse?|stddev|p50|p95)\b", re.I)

NUMBERISH = re.compile(r"(\d[\d,]*\.?\d*(e[-+]?\d+)?\s*(%|µs|us|ms|ns|kb|mb|gb|bytes?|×|x)?)")

ADMONITION = re.compile(r"^(!!!|\?\?\?\+?)\s+\w+")

OVERSIZED_ADMONITION_LINES = 14   # a !!! block longer than this interrupts rather than aside
BULLET_NUMBERS_MIN = 3            # this many number-heavy bullets in one run wants a table


def split_prose(text):
    """Return (prose_lines, table_lines, admonition_runs, bullet_runs), fences removed.

    prose_lines: [(lineno, text)] outside fences, outside tables
    table_lines: [(lineno, text, is_measurement)] markdown table rows
    """
    lines = text.split("\n")
    in_fence = False
    in_html_comment = False
    prose, table, heads = [], [], []
    admon_runs, bullet_runs = [], []
    cur_admon = None
    cur_bullets = []
    pending_header = None

    for i, ln in enumerate(lines, 1):
        stripped = ln.lstrip()

        # HTML comment blocks are not prose. `ray_tracing_examples.md` carries four FIELD_TEST
        # YAML declarations inside them, whose `#` comment lines read as headings and scored
        # that page 104 on 675 words — the whole of it an artefact. Check this before fences:
        # a comment block can contain a ``` line and desynchronise the fence state.
        if in_html_comment:
            if "-->" in ln:
                in_html_comment = False
            continue
        if stripped.startswith("<!--") and "-->" not in ln:
            in_html_comment = True
            continue

        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue

        # admonition runs: opener plus its indented body
        if ADMONITION.match(stripped):
            if cur_admon:
                admon_runs.append(cur_admon)
            cur_admon = {"line": i, "title": stripped, "body": 0}
            continue
        if cur_admon is not None:
            if ln.startswith("    ") or not ln.strip():
                cur_admon["body"] += 1 if ln.strip() else 0
                # fall through: indented admonition content is still prose
            else:
                admon_runs.append(cur_admon)
                cur_admon = None

        if stripped.startswith("#"):
            heads.append((i, stripped))
            continue

        if stripped.startswith("|"):
            # a separator row tells us the row above was the header
            if re.match(r"^\|[\s:|-]+\|?\s*$", stripped) and table:
                pending_header = table[-1][1]
            is_meas = bool(MEASUREMENT_HEADER.search(pending_header or stripped))
            table.append((i, stripped, is_meas))
            continue
        pending_header = None

        # bullet runs
        if re.match(r"^[-*+]\s|^\d+\.\s", stripped):
            cur_bullets.append((i, stripped))
        else:
            if len(cur_bullets) >= 2:
                bullet_runs.append(cur_bullets)
            cur_bullets = []

        prose.append((i, ln))

    if cur_admon:
        admon_runs.append(cur_admon)
    if len(cur_bullets) >= 2:
        bullet_runs.append(cur_bullets)
    return prose, table, heads, admon_runs, bullet_runs


def score(text):
    prose, table, heads, admons, bullets = split_prose(text)
    hits = collections.Counter()
    detail = collections.defaultdict(list)

    def hit(name, weight, line, snippet):
        hits[name] += weight
        detail[name].append((line, snippet[:90]))

    # A. headings
    for lineno, h in heads:
        level = len(h) - len(h.lstrip("#"))
        title = re.sub(r"^#+\s*", "", h)
        title = re.sub(r"\{#[\w-]+\}", "", title).strip()
        if title.endswith("?"):
            hit("head-question", 3, lineno, title)
        for name, pat, w in HEADING_TELLS:
            if name in H1_EXEMPT and level == 1:
                continue
            if re.search(pat, title, re.I):
                hit(name, w, lineno, title)

    # A'. admonition titles carry the same faults, and Marc reads them as headings
    for a in admons:
        title = re.sub(r'^(!!!|\?\?\?\+?)\s+\w+\s*"?', "", a["title"]).rstrip('"')
        for name, pat, w in HEADING_TELLS:
            if re.search(pat, title, re.I):
                hit("admon-" + name, w, a["line"], title)
        # BODY_TELLS never looked at an admonition title, so
        # `!!! note "The measurement layer is the interesting part"` carried a textbook
        # slide-label and scored 0. A title is read as a heading; score it as one.
        for name, pat, w in BODY_TELLS:
            if re.search(pat, title, re.I):
                hit("admon-" + name, w, a["line"], title)
        if a["title"].startswith("!!!"):
            for name, pat, w in SELF_REFERENTIAL_TITLE:
                if re.search(pat, title, re.I):
                    hit(name, w, a["line"], title)
        # Only `!!!` counts. A `???` is collapsed by default, so length costs a reader nothing
        # and demoting an oversized `!!!` to a `???` is exactly the fix; scoring both would
        # punish the repair. This is the "should be a margin note, not a huge admonishment in
        # the middle of how to load a file" complaint, made machine-visible.
        if a["body"] >= OVERSIZED_ADMONITION_LINES and a["title"].startswith("!!!"):
            hit("admon-oversized", 8, a["line"],
                f'{a["body"]} paragraphs/lines, always open: {title}')

    # B. body prose — replace inline code spans before matching so that phrases inside
    # `file.kt:N#The two plugins are located differently on purpose.` citations don't fire.
    # The replacement is a sentinel, not nothing: deleting a span joins the words either side
    # and can *manufacture* a tic that nobody wrote. "and that `Fn` carries a real graph node"
    # became "and that  carries a" and scored agent_transcripts.md 8 for carries-a-trap.
    prose_text_lines = [(i, re.sub(r"`[^`]+`", "§", ln)) for i, ln in prose]
    for name, pat, w in BODY_TELLS:
        rx = re.compile(pat, re.I)
        for i, ln in prose_text_lines:
            for m in rx.finditer(ln):
                hit(name, w, i, m.group(0))

    # C1. number-heavy bullets that want a table
    for run in bullets:
        heavy = [b for b in run if len(NUMBERISH.findall(b[1])) >= 3]
        if len(heavy) >= BULLET_NUMBERS_MIN:
            hit("bullets-want-a-table", 6, heavy[0][0],
                f"{len(heavy)} number-heavy bullets in one list")

    # C2. measurement mass: how much of the page is residual/timing tables
    meas_rows = sum(1 for _, _, m in table if m)
    words = len(re.findall(r"[A-Za-z][A-Za-z'’-]+", "\n".join(l for _, l in prose)))
    if meas_rows >= 12:
        hit("measurement-heavy", min(20, meas_rows // 3), table[0][0] if table else 1,
            f"{meas_rows} measurement table rows on {words} prose words")

    # C3. em-dash density in running sentences only (list and table dashes are layout)
    sentence = [(i, ln) for i, ln in prose
                if not re.match(r"\s*([-*+]|\d+\.)\s", ln) and not ln.lstrip().startswith("|")]
    joined = "\n".join(ln for _, ln in sentence)
    em = joined.count("—")
    sw = len(re.findall(r"[A-Za-z][A-Za-z'’-]+", joined))
    em_per_1k = round(em * 1000 / sw, 1) if sw else 0.0
    # Marc's own pages run 1.4-14.3 em/1k, so anything under 15 carries no signal at all.
    # The machine band that started this work was 13-15 and the previous pass over-corrected
    # 35 pages to exactly 0.0, which is its own tell. Do not lower this.
    #
    # The SCORE, though, is the count of dashes over that allowance -- not the rate's excess in
    # points. The rate is the trigger; dashes are the magnitude. 2026-09-18: scored in rate
    # points, `templates/WORKSPACE_README.md` came out at 24 -- the highest-scoring hand-written
    # prose in reach, above Marc's own `kotlin_overview.md` at 8 -- on SIX dashes, because its
    # 219 sentence-words make one dash worth 4.6/1k and the old `(rate - 15) * 2` turned that
    # into 9 points. 365 of this tree's 514 pages are under 400 sentence-words, so the
    # quantisation was not an edge case; the 116 seeded copies of that very file were the only
    # thing keeping it out of a ranking. The two forms agree exactly at 2000 sentence-words
    # (about the length of `kotlin_overview.md`), which is what makes this a rescaling of the
    # calibrated threshold rather than a new one: old = (em*1000/sw - 15)*2, new = em - 15*sw/1000,
    # and new = old * sw/2000. Do not restore the rate form; it cannot compare two lengths.
    if em_per_1k > 15:
        allowance = 15 * sw / 1000
        over = int(em - allowance)
        if over > 0:
            hits["em-dash-density"] = over
            # Without this, --why printed a bare header for a page whose whole score was em
            # density -- an empty hit list on a score of 24, which reads as a broken detector
            # and sends a firing hunting for a fault that is not there. Name the arithmetic and
            # point at the lines.
            detail["em-dash-density"].append(
                (sentence[0][0] if sentence else 1,
                 f"{em} dashes on {sw} sentence-words = {em_per_1k}/1k; "
                 f"{allowance:.1f} allowed at 15/1k, so {over} over"))
            for i, ln in sentence:
                if "—" in ln:
                    detail["em-dash-density"].append((i, ln.strip()))

    return sum(hits.values()), words, em_per_1k, hits, detail, meas_rows


def is_generated(text):
    # `DO NOT EDIT` is the codebase-reference generator's banner and was the only one this knew
    # about. It is not the only banner in the tree: `tools/gen_canonical_graphics_index.py` writes
    # "Do not hand-edit: regenerate with that script", and on 2026-09-17 that put a *generated*
    # page onto the new --assets surface as a candidate to hand-edit -- which is the one thing
    # every firing is told not to do. Case-folded, and still only over the first 400 characters:
    # `kotlin_in_the_head.md:104` carries the same phrase about one interpolated block, and a
    # hand-written page with a generated *section* is still a hand-written page.
    #
    # The third shape has no imperative at all, and the surface found it the same night:
    # `assets/ai_tool_demo/session.md` opens "Generated by afield.examples.AgentToolDemoEvidence"
    # and is emitted whole by that object. Checked over the tree before adding: exactly one page
    # says "generated by" in its first 400 characters and it is that one, so this costs no false
    # positive today. A page that acquires the phrase in an opening *sentence* about something
    # else would be a false positive, and the right repair then is to read it, not to widen this.
    head = text[:400].upper()
    return ("DO NOT EDIT" in head or "DO NOT HAND-EDIT" in head
            or "GENERATED BY " in head)


# The three seeded shapes under `public/docs/assets/`. Keyed on the first line, because that is
# what the seeder controls and an author replaces: `ensureAgentGuides` writes the two workspace
# welcomes verbatim into every demo folder, and `document.md` is written beside `metadata.edn` by
# the document writer. Measured 2026-09-17: 351 of 359 asset markdown files match one of these,
# and the 8 that do not are hand-authored demo front pages totalling 5188 words.
#
# 2026-09-18 -- THE REASON THIS SKIP EXISTS WAS WRONG, AND THE SKIP IS STILL RIGHT.
# The recorded reason was "editing those is reverted by the seeder, exactly as editing a
# reference/ page is reverted by the generator". `ensureAgentGuides` in `saving.kt:1725` copies
# `if (sourceFile.exists() && !destFile.exists())` -- it NEVER overwrites. So a hand-edit to a
# seeded copy survives forever, and the analogy to the codebase-reference generator is false in
# the one direction that matters: nothing will ever propagate a fix TO these files either.
#
# The true reason to skip them is arithmetic, not authority: measured today, 117 of 122 asset
# `README.md` and 117 of 117 asset `AGENTS.md` are BYTE-IDENTICAL to `templates/WORKSPACE_README.md`
# and `templates/WORKSPACE_AGENTS.md`. Scoring 234 copies of two texts ranks one fault 234 times.
# Score the source once -- which is what TEMPLATE_SOURCES below now does -- and then remember that
# fixing the source fixes no existing copy. A firing that edits a template owes a copy sweep.
SEEDED_FIRST_LINES = (
    "# A Field workspace",
    "# You're in a Field workspace",
    "# Field Document:",
)

# The two texts the 234 seeded copies are copies of. Outside `public/docs`, so no surface reached
# them: the nineteenth through twenty-first firings each found one surface a detector skipped, and
# this is the fourth -- a surface reached only through its source. --assets scores these alongside
# the hand-authored asset pages, because the fix for a seeded copy is always here.
TEMPLATE_SOURCES = (
    "templates/WORKSPACE_README.md",
    "templates/WORKSPACE_AGENTS.md",
)


def is_seeded(text):
    first = text.lstrip().split("\n", 1)[0].strip()
    return any(first.startswith(p) for p in SEEDED_FIRST_LINES)


# A generated reference page is laid out as one `## <File>.kt { #... }` section per source file,
# so the source that owns a hit is the nearest such heading above it. That is what makes
# --generated actionable: the fix is never in the .md, and without this you get a line number in
# a file you must not edit.
KT_SECTION = re.compile(r"^#{2,3}\s+([A-Za-z0-9_]+\.kt)\b", re.M)


def owner_of(text, line_no):
    owners = [(text[:m.start()].count("\n") + 1, m.group(1))
              for m in KT_SECTION.finditer(text)]
    owner = None
    for start, name in owners:
        if start <= line_no:
            owner = name
        else:
            break
    return owner


GENERATED_ASSETS = []


def collect(root, generated=False, assets=False):
    rows = []
    for p in sorted(root.rglob("*.md")):
        s = str(p)
        if "/generated/" in s:
            continue
        if ("/assets/" in s) != assets:
            # The third instance of the skip-a-surface bug the nineteenth and twentieth firings
            # each found once: `/assets/` was skipped wholesale, and 359 markdown files under
            # `public/docs/assets/` -- 285k words -- had never been looked at by any detector.
            #
            # Skipping most of them is right, and for a reason worth stating: 351 of the 359 are
            # machine-seeded. `ensureAgentGuides` writes the same `AGENTS.md` and `README.md`
            # welcome into every workspace, and `document.md` is a generated narrative mirror of
            # `metadata.edn`. Editing any of those is reverted by the seeder, exactly as editing
            # a `reference/` page is reverted by the generator. `is_seeded()` is what tells them
            # apart, and it reads the *content*, not the filename -- a hand-authored README under
            # assets is an ordinary page that a reader opens, and the demo READMEs are linked
            # from published pages. See SEEDED_FIRST_LINES for why that reason is not the real
            # one, and TEMPLATE_SOURCES for the surface it was hiding.
            continue
        t = p.read_text(errors="replace")
        if assets and is_seeded(t):
            continue
        if assets and is_generated(t):
            # Not dropped silently: main() names these, because a generated page falling off
            # every surface is how a fault hides. `canonical_graphics/LESSONS.md` is one, and
            # it is the reason is_generated() learned "do not hand-edit" at all.
            GENERATED_ASSETS.append(str(p))
            continue
        if is_generated(t) != generated:
            # Default run: generated pages are excluded, because the fix is in the KDoc and a
            # row here would be unactionable. --generated is the other half, and it exists
            # because for sixteen firings nothing ever read the KDoc prose at all: on
            # 2026-09-16 `PatSpatial.kt` was still publishing the exact TED-slide sentence Marc
            # named on 2026-09-13, three days and eight firings later, invisible to this tool.
            continue
        total, words, em, hits, detail, meas = score(t)
        row = dict(path=str(p), score=total, words=words, em=em,
                   measurement_rows=meas, mine=p.name in MARCS_OWN,
                   generated=generated,
                   hits=dict(hits),
                   detail={k: v for k, v in detail.items()})
        if generated:
            row["owners"] = {name: sorted({owner_of(t, line) or "?" for line, _ in sites})
                             for name, sites in detail.items()}
        rows.append(row)

    if assets:
        # The two template sources live outside `public/docs`, so `root.rglob` never saw them,
        # and every copy of them is filtered by is_seeded() above. That combination is what kept
        # them unscored for twenty-one firings. Relative to the repo root, which is the cwd every
        # firing runs from.
        for rel in TEMPLATE_SOURCES:
            src = pathlib.Path(rel)
            if not src.exists():
                continue
            t = src.read_text(errors="replace")
            total, words, em, hits, detail, meas = score(t)
            rows.append(dict(path=rel, score=total, words=words, em=em,
                             measurement_rows=meas, mine=False, generated=False,
                             hits=dict(hits),
                             detail={k: v for k, v in detail.items()}))

    rows.sort(key=lambda r: -r["score"])
    return rows


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "public/docs")
    gen = "--generated" in sys.argv
    assets = "--assets" in sys.argv
    rows = collect(root, generated=gen, assets=assets)

    if assets and "--calibrate" in sys.argv:
        # Decline rather than pretend, as moves.py --calibrate --generated does: MARCS_OWN names
        # four hand-written pages and none of them is under assets, so there is no zero point to
        # assert here. Calibrate the default surface; it shares every detector with this one.
        print("--calibrate does not apply to --assets: MARCS_OWN is hand-written pages only,\n"
              "and the detectors are shared. Run `--calibrate` without --assets.")
        return

    if assets and "--why" not in sys.argv and "--json" not in sys.argv:
        print("Hand-authored markdown under public/docs/assets — the demo front pages a reader\n"
              "opens with the document. Machine-seeded workspace guides and `document.md`\n"
              "mirrors are excluded by is_seeded(); editing those is reverted by the seeder.\n"
              "\nNOTE ON PUBLICATION: build_zensical_docs.py's WORKSPACE_MARKDOWN_FILENAMES\n"
              "excludes README.md / AGENTS.md / document.md from the built site, so most rows\n"
              "here are NOT site pages. They are still the first prose a reader meets on opening\n"
              "the demo, which is why they are scored. Anything else here (session.md,\n"
              "ATTRIBUTION.md, LESSONS.md) IS published and linked from a page.\n")
        if GENERATED_ASSETS:
            print("Generated, so excluded — the fix is in the generator, not the .md:")
            for g in GENERATED_ASSETS:
                print(f"    {g}")
            print()

    if gen and "--why" not in sys.argv and "--json" not in sys.argv:
        print("Generated pages. THE FIX IS IN THE KDoc, NOT THE .md — editing these is "
              "reverted by the next\n`./gradlew generateCodebaseReference`, and the guard "
              "will catch you. Owners are the source files.\n")
        print(f"{'score':>6} {'em/1k':>6} {'words':>6}  path")
        for r in rows:
            if r["score"] == 0:
                continue
            print(f"{r['score']:>6} {r['em']:>6} {r['words']:>6}  {r['path']}")
            for name, owners in sorted(r["owners"].items()):
                print(f"           {name} ({r['hits'][name]}) <- {', '.join(owners)}")
        nz = sum(1 for r in rows if r["score"])
        print(f"\npages={len(rows)}  nonzero={nz}  sum={sum(r['score'] for r in rows)}")
        return

    if "--calibrate" in sys.argv:
        bad = [r for r in rows if r["mine"] and r["score"] > 12]
        print("Marc's own pages (the target voice):")
        for r in rows:
            if r["mine"]:
                print(f"  {r['score']:>5}  {r['em']:>5}  {r['path']}")
        if bad:
            print("\nFAIL: a detector fires on the target voice. Fix the detector, "
                  "not the page:")
            for r in bad:
                print(f"  {r['path']}: {r['hits']}")
            sys.exit(1)
        print("\nOK: every target-voice page is at or under 12.")
        print("\nRetired patterns (do not re-add without a hit-by-hit check):")
        for k, v in RETIRED.items():
            print(f"  {k}: {v}")
        return

    why = None
    if "--why" in sys.argv:
        why = sys.argv[sys.argv.index("--why") + 1]
    if why:
        for r in rows:
            if why in r["path"]:
                print(f"{r['path']}  score {r['score']}  em/1k {r['em']}  "
                      f"{r['measurement_rows']} measurement rows  {r['words']} prose words\n")
                for name, sites in sorted(r["detail"].items(),
                                          key=lambda kv: -len(kv[1])):
                    print(f"  {name} ({r['hits'][name]})")
                    for line, snip in sites:
                        print(f"      :{line}  {snip}")
        return

    if "--json" in sys.argv:
        print(json.dumps(rows, indent=1))
        return

    print(f"{'score':>6} {'em/1k':>6} {'meas':>5} {'words':>6}  path")
    for r in rows:
        if r["score"] == 0:
            continue
        mark = "  <- MARC'S OWN, DO NOT EDIT" if r["mine"] else ""
        print(f"{r['score']:>6} {r['em']:>6} {r['measurement_rows']:>5} "
              f"{r['words']:>6}  {r['path']}{mark}")
    nz = sum(1 for r in rows if r["score"])
    print(f"\npages={len(rows)}  nonzero={nz}  sum={sum(r['score'] for r in rows)}")


if __name__ == "__main__":
    main()
