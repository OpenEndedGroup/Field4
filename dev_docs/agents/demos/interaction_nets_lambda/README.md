# λ-calculus on interaction nets

**Open this to see a λ-term compiled to an interaction net, reduced, and read back — checked against an ordinary substitution evaluator that shares nothing.**

```bash
./field.sh dev_docs/agents/demos -d interaction_nets_lambda
```

Three boxes, none of which needs a GPU, a network or any setup. Run them in any order; nothing is shared between them.

| box | what it does |
|---|---|
| **Normalise a term, and check it against the oracle** | `LambdaTerms.normalise` on eight terms, each one's answer compared to `referenceNormalOrder` |
| **One reduction, one panel per stage** | `(λx. x x) (λy. y)` drawn as five panels with a summary card each: β, `dup-lam`, β, `fan-fan` |
| **The whole corpus against the oracle, and four schedulers** | all 25 named terms, with the agreement and confluence counts at the bottom |

## What the third box says when it runs

```
agreed with the oracle : 25 of 25
confluent across 4 schedulers: 25 of 25
net reactions          : 228 over 25 terms at FIRST_FOUND
```

The same three numbers `stdlib.lambda-terms.smoke` asserts. The population is 25 named closed terms — Church numerals with `succ`, `add`, `mul` and `pred`, Church booleans with `if`, `S`/`K`/`I`, four terms whose reduction copies a redex, and two that erase an argument entirely — and `LambdaTerms.corpus()` is the list.

228 net reactions is **not** comparable to the oracle's 117 β-steps: a net reaction is any rule firing, a β or a duplication or an erasure or a fan meeting, and the two are different units. Neither number is the word *optimal*. There are no brackets and no croissants here, so the sharing is correct but nothing bounds how many times a fan may be copied.

## The one surprising thing

Look at the `agents` column against the `normal form` column on the `church-exp-two-cubed` row. Ten agents left standing, for a term that needs eleven nodes to write down. The net is smaller than its own answer, because it never stopped being shared — the unfolding happens in the **readback**, which carries a stack of fan choices and treats a fan as a bracket on the path through the term rather than as a node of it. That is what `Lambda.FAN_LABEL` is for, and why `fan-fan` annihilates on equal labels: a matched pair.

Which way that comparison goes depends on the term. `church-mul-2-3` finishes at ten agents against a nine-node term, and `church-mul-3-3` comes out even.

## Where the code is

- `src/jvmMain/kotlin/afield/stdlib/lambdaterms.kt` — the front end, the oracle and the corpus
- `src/jvmMain/kotlin/afield/stdlib/interactionnets.kt` — `Lambda.rules()`, including `fan-fan`, and the `Reducer` that chases wires through a vanishing pair
- `dev_docs/INTERACTION_NETS_LAMBDA.md` — what was built, what is verified and how, and what is not
- `public/docs/interaction_nets_examples.md` — the published page, with the committed figure
