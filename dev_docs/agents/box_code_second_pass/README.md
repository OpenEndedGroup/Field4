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

| [Drawing and painting path helpers](../box_code_adoption_batch_17/README.md) | Eleven more findings addressed; seventy code bodies, three transcripts and actual geometry checks verified; native paint rendering subsequently verified with the existing library. |

| [Typed shader-source reads](../box_code_adoption_batch_18/README.md) | Five more findings addressed; 21 code bodies, six compiled graphs/full examples and live inherited-source reads verified. |

| [Flow resets and shader probes](../box_code_adoption_batch_19/README.md) | Twelve findings addressed; GPU count/canary/statistics checks, reset outcomes, temporal validity and actual docs recaptures verified. |

| [Editable frame updates and current imports](../box_code_adoption_batch_20/README.md) | Seven more findings addressed; full graph compilation, exact phase schedule and saved-source refresh verified. |

| [Bounded histories and safe reruns](../box_code_adoption_batch_21/README.md) | Four more findings addressed; real reader reruns/descriptor checks, invalid inputs, launched histories and browser gate callbacks verified. |

| [Exact view ownership on Stop](../box_code_adoption_batch_22/README.md) | Three more findings addressed; native rendering, reruns, exact-resource closure and restored renderer attachments verified. |

| [FLine and animation path helpers](../box_code_adoption_batch_23/README.md) | Four more findings addressed; 125 states inspected, all 73 runnable bodies compiled, geometry comparisons and four forced docs captures verified. |

| [Current teaching-code comments](../box_code_adoption_batch_24/README.md) | Nine more findings addressed; unchanged executable-token proof, Kotlin/GLSL formatting and actual path-table/mesh-viewer checks verified. |

| [Correct color, chirp and null-reference math](../box_code_adoption_batch_25/README.md) | Four more findings addressed; 99 GPU ramp values, real FFT windows, 200 held-out null windows and forced docs captures verified. |

| [Failure diagnostics and live callback state](../box_code_adoption_batch_26/README.md) | Five more findings addressed; native Ceres, glyph rejection, concurrent logging, real ranking child failures and browser slideshow callbacks verified. |

| [Shader inputs and WFC edge layout](../box_code_adoption_batch_27/README.md) | Three more findings addressed; seeded WFC grids/atlas pixels match, and actual native triangle/spark rendering and rerun are inspected. |

| [Shared paint spline and tool limits/effects](../box_code_adoption_batch_28/README.md) | Three more findings addressed; actual spline comparisons, registered scribble calls and native progress PNG verified. |

| [Typed saved fluid sources](../box_code_adoption_batch_29/README.md) | Three more findings addressed; actual native advection attachment, Stop/rerun and parent replacement target ownership verified. |

| [SolidSL measurements and native ownership](../box_code_adoption_batch_30/README.md) | Five more findings addressed; actual native handles close on both exits, exact/fallback viewers and inverse fit captions verified. |

| [Shared-memory overlay clearing and quad layout](../box_code_adoption_batch_31/README.md) | Four more findings addressed; actual shared buffers, native line/empty/resume, pixel widths and relaunch verified. |

| [Typed polytope push and KotlinJS sum](../box_code_adoption_batch_32/README.md) | Three more findings addressed; identical native RGB captures, advancing time and actual KotlinJS arithmetic verified. |

| [Typed studies and kit sources](../box_code_adoption_batch_33/README.md) | Three more findings addressed; saved/reopened sources, inherited handles, real kit migration/refusal and ten standalone docs states verified. |

[Machine checkpoint](checkpoint.json): **263 of 405 findings addressed**, one implemented with
external-service verification pending; all other findings stay open. These counts do not claim
that every body in the corpus has been edited or run. Mechanical-only bodies and helper adoption
without an original finding are described in each batch rather than inflating this count.

Every batch includes code smells/idioms, useful comments, Kotlin/GLSL layout, import/ktfmt handling,
explicit collection-literal audit, fragment/reset preservation, and the smallest meaningful runtime
checks. Save concrete verification limits when a device or service is unavailable.
