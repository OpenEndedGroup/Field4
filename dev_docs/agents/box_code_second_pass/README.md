# Second pass checkpoint

This is the edit/verification pass following the 568-group first review and helper triage.
The [saved finding IDs](../box_code_first_pass/findings.json) are the worklist; unlisted findings
remain open. Progress comes from reviewed diffs and evidence, not elapsed time or tool activity.

| Batch | Status |
| --- | --- |
| [Graphics, lifecycle, mesh and first Loris box](../box_code_adoption_batch_1/README.md) | Committed/enqueued as `ffa7ab7fb`; collection-style correction `e97a8c4a9`. Seven findings addressed. |
| [Remaining Loris and Field Audio pipelines](../box_code_adoption_batch_2/README.md) | Two more findings addressed; Field Audio adapter implemented with fresh-service evidence still pending. |
| [Traces, stage snapshots, image timings and spatial grids](../box_code_adoption_batch_3/README.md) | Eight more findings addressed; real before/after results and captures verified. |

| [Faust, OSC, slices, Syphon and RectSL](../box_code_adoption_batch_4/README.md) | Six more findings addressed; actual audio buffers, native geometry/rendering and lifecycle checks passed. |

| [Demo comment corrections](../box_code_adoption_batch_5/README.md) | Twelve more findings addressed; source/API checks, unchanged shader tokens and real executable comparisons. |

| [Transcript comments and collection style](../box_code_adoption_batch_6/README.md) | Twenty more findings addressed; 17-transcript compile/replay and formatter regression tests pass. |

| [Documentation code explanations and layout](../box_code_adoption_batch_7/README.md) | Sixteen more findings addressed; 407 Kotlin states and four GLSL states mechanically checked. |

| [Drawing paths and grid helpers](../box_code_adoption_batch_8/README.md) | Twenty more findings addressed, plus three upstream logging fixes accounted for; real geometry and launch lifecycle checks pass. |

| [Exact landing and immutable solve requests](../box_code_adoption_batch_9/README.md) | Eight more findings addressed; actual 20-cell landing, positioned graphs, renderer-free Frames and request freshness checks. Optional Kratos worker acceptance remains unexercised. |

| [Shader contracts and comment-safe formatting](../box_code_adoption_batch_10/README.md) | Nine more findings addressed; seven formatter regressions, five actual programs, three compiled shaders, four document refreshes and remote transcript compilation pass. |

| [Graphics dispatch, inheritance and streaming](../box_code_adoption_batch_11/README.md) | Five more findings addressed; actual GPU boundary readbacks, particle captures, native streamer decoding and buffer-adapter regression checks pass. |

| [Tutorial explanations and current refusals](../box_code_adoption_batch_12/README.md) | Fifteen more findings addressed; 65 Kotlin bodies, 18 shader properties, 33 collection literals, eight transcript routes and saved-feedback fallback verified. |

| [Reconstruction shader contracts](../box_code_adoption_batch_13/README.md) | Eleven more findings addressed; 14 compiled/validated shader occurrences, 15 Kotlin bodies, three transcripts and 16 import/reconstruction contract tests pass. |

| [Drawing helpers and explicit path starts](../box_code_adoption_batch_14/README.md) | Eleven more findings addressed; 80 Kotlin bodies, four transcripts, actual geometry comparisons and browser captures pass. |

| [Current contracts and formatter coverage](../box_code_adoption_batch_15/README.md) | Seventeen more findings addressed; fourteen transcript routes, empty/constant scalar variants, shader validation and quoted-key formatter regressions verified. |

| [Live control previews](../box_code_adoption_batch_16/README.md) | Five findings resolved; actual browser callbacks/rerun persistence, implicit-receiver distinction and the regenerated docs capture verified. |

| [Drawing and painting path helpers](../box_code_adoption_batch_17/README.md) | Eleven more findings addressed; seventy code bodies, three transcripts and actual geometry checks verified; native paint rendering explicitly unavailable. |

| [Typed shader-source reads](../box_code_adoption_batch_18/README.md) | Five more findings addressed; 21 code bodies, six compiled graphs/full examples and live inherited-source reads verified. |

[Machine checkpoint](checkpoint.json): **191 of 405 findings addressed**, one implemented with
external-service verification pending; all other findings stay open. These counts do not claim
that every body in the corpus has been edited or run. Mechanical-only bodies and helper adoption
without an original finding are described in each batch rather than inflating this count.

Every batch includes code smells/idioms, useful comments, Kotlin/GLSL layout, import/ktfmt handling,
explicit collection-literal audit, fragment/reset preservation, and the smallest meaningful runtime
checks. Save concrete verification limits when a device or service is unavailable.
