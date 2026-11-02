# Fix weak or wrong captures on autodiff_examples.md (exact_fit.png)

- **Handoff:** `873ec1ba-093f-4775-b9ec-39c65e7432b8` (project)
- **Filed from:** https://openendedgroup.com/field4/autodiff_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 873ec1ba "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/autodiff_examples/exact_fit.png` — weak-illustration, medium

**Problem.** The loss panel 'RMSE per Adam step - same optimiser, different gradient' draws finite differences and exact reverse mode on top of each other from step 0 to 400, so the curves are indistinguishable. The real difference, 6801 against 801 loss evaluations, shows only in the text below. The target and fitted plots above are also identical, which is the intent, but the capture is 3540 px tall, so at column width everything shrinks.

**Fix.** Plot RMSE against loss evaluations instead of Adam steps, or use a log y axis, so the 8.5x cost gap shows in the figure. Split the target/fitted pair into a single overlay plot to cut the height.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page autodiff_examples.md` and look at each new asset at column width before closing.
