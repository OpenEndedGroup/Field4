# Rank at corpus scale — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the three boxes left to right. There is no GPU, no window and no sidecar involved: everything
here reads the checked-in recording and calls the live server-side classifier in process.

| box | what it does | what you should notice |
| --- | --- | --- |
| **1 — the corpus** | reads `src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz` and reports the population, plus a plot of payload size per caret | 578 real carets, 101 receivers, and the long right tail past 80 rows — every caret out there is one where ranking decides what a person can see |
| **2 — category + prior** | calls the *real* `KotlinSourceIndex.getCompletionCategoryForSymbol` with the real recorded `declaredIn`, and `CommonMemberPrior.rankOf`, over one caret's rows | `FLine`'s eight `@CommonMembers` carry a prior and land in `Graphics`; most rows carry a category the popup scores 0. Change `RECEIVER` to `java.lang.Math` and re-run |
| **3 — re-run the decision** | shells out to `tools/rank_weight_lab.py`, which re-scores all 578 carets through the shipped `fieldSuggest.js` with one ranking constant rewritten in a temp directory | R2 teaches `CATEGORY_WEIGHT` the whole server vocabulary and moves visible recall by **0.000**; R1 does it hard and costs **5.5 points** of bare-dot recall. That is the refusal, checkable in a minute |

Box 3 needs the spec the test drops. If it says so, run once:

    ./gradlew jvmTest --tests 'afield.FieldSuggestPresentationTest'

The written-up version, with the full sweep and the falsification behind it, is
`dev_docs/completion_rank_at_scale.md`.

The loose `*_code_.kt` files are what Field loads, while `metadata.edn` carries portable inline
fallbacks. `./gradlew checkAgentDemos` requires those copies to be byte-identical. After editing a
loose file, refresh the canonical document with `./gradlew regenerateAgentDemos`; that task uses
Field's own EDN codec rather than rewriting the serialization by hand.
