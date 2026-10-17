# Follow-up prose

- dev_docs/FN_REWRITE_SELF_BOX_REENTRY.md and the introductory FunctionRewriteKotlin.kt KDoc
  describe the older second-hydration guard. SourceBackedFnRewriteNumberExpression.hydrate now
  first checks BoxBodyRun.isRunning and throws SourceBackedBlockDuringOwnBody. Add a current
  contract section while preserving the older measured history as dated evidence. This batch's
  real replay verifies the immediate refusal and keeps the two-box host example working.
- The pat_fn_graph_fit description should describe reversed high/low atom values with differing
  amplitudes; its two seeds are not an exact permutation. The loaded code comment is corrected.

## Tool throughput observation

The ordinary merge_regen main path discards the redundant pure-drift set returned by regenerate;
regen_since already uses it. Its staged guard path also does not apply the parsed --skip option.
The graphics merge therefore checked freshly generated reference/transcript/completion outputs
again. Any optimization should use the same pure-drift-only rule and retain citation/behavior
checks, with throwaway-repository tests. No tool behavior was changed in this code batch.
