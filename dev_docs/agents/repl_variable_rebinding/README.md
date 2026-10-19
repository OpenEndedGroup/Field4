# Declaration edits reach running Kotlin code

A successful redeclaration of a plain `var` resets older mutable backing fields of the same name
and Kotlin type within that engine. The update happens after evaluation, once per declaration.
The fields remain separate storage afterwards. `val` snapshots, delegated properties, incompatible
types and failed initializers keep their existing behavior.

K2's rerun cache can reload historical classes into another loader. Mutable declarations and their
readers now compile afresh to preserve the older captured slots. Immutable preambles and independent
code still use the cache; the existing cache regression verifies its hits and body execution.

## Verification

- `ReplVariableRebindingSmokeTest` uses real K2 engines and retained closures: repeated resets,
  multiple older slots, indirect readers, private names, nullable values, type changes, resource
  snapshots, delegates, engine isolation and failed initialization.
- `ReplRerunCompileCacheSmokeTest` passes unchanged. The exact tested runtime hashes and entrypoints
  are in `runtime-verification.json`.
- The parsed audit includes authored source, docs fences, inline fallbacks, generated documents,
  mirrors and capture drivers. All 39 redundant authored declaration/reset pairs were removed.
  Five later assignments remain intentionally: three intermediate mesh replacements and two
  counter resets between measurements. See `audit-review.json`.
- The actual import/ktfmt formatter checked all edited box-code bodies. Field's document codec
  refreshed two saved documents, proving graph IDs and unselected properties unchanged. The matching
  document mirror and generated transcript starting points were refreshed.
- The normal docs runner recaptured `first_steps.md`, including `first-look.mp4`. Its real editor
  changes `var petals = 5` to `var petals = 8`, executes that declaration with Alt-Enter while the
  original drawing runs, relaunches and prints eight, then stops. `welcome-verification.json`
  records the final asset and inspected frames.

The scripts here preserve the inventory, review and cleanup procedure. Detailed transient before/after
receipts and runtime logs are under `build/repl-variable-rebinding/`; the authoritative patch is Git.
Full integration guards and final regeneration run through `tools/land.py`.
