# Current contracts, measured captions and formatter coverage

Baseline `9a13fabda`. Seventeen findings addressed across thirteen transcripts, plus a formatting
coverage correction to sorting networks. [Reviewed drafts](changes.json), [source proof](verification.json),
and [execution evidence](evidence.tar.gz) retain the edits and results.

The examples now explain the current FieldML renderer prerequisite, point custom ImGui panel
placement to its actual construction code, capture plain Structure values without a false null
warning, use imported property types, and put GLSL member comments beside the members they describe.
Rewrite guards explain termination in both stored and executed bodies. Suggested polytope edits
require a realization when geometry is displayed and a strictly positive hole size. Kerning output
reports measured width change without inferring whether a font contains pairs from that total.
Bore comments distinguish edge breakthrough from bore-to-bore contact.

The simulation example explains seconds versus milliseconds and its deliberate profiling workload,
expands its SVG, and publishes cleanup through prop<Int>(simulationCleanup). Its replay still checks
the actual stored count after Stop. Empty scalar deployments have a readable caption; a constant
scalar range uses the palette midpoint instead of dividing by zero. Existing nonempty range and
missing-attribute styling are retained.

## Mechanical coverage and repairs

The editor import pass and ktfmt cover fifty Kotlin bodies, with three FieldML bodies reported as
parse skips and left unchanged. Twenty-one eligible collection factories become literals. A typed
unsupportedNotes list resolves the Key example's heterogeneous pair inference without a factory.
Source proofs preserve execution boundaries, untrimmed raw strings, graph metadata and shader
comments/tokens. The explicit fourteen-file collection/idiom audit passes.

BoxCodeFormat now recognizes matching quoted YAML keys as well as bare keys. Two focused regression
tests cover quoted/bare formatting, chomp/adjacent-property preservation, idempotence and malformed
quotes; both pass with the test-tree guard clean. The four sorting-network bodies from batch 14
were then actually formatted and replayed, correcting its previously incomplete YAML formatting
claim. Its reviewed body-token proof still passes. The documentation extraction harness now returns
without invoking the corpus formatter when its selected pages contain no Kotlin states.

## Execution

Thirteen of fourteen selected transcript routes pass in one invocation. The Key example initially
fails on collection-literal inference; after its typed-list repair, the targeted full keyframer
transcript passes. This is final-source aggregate evidence, not a claim of one all-green fourteen-
transcript invocation. Both the failure and repaired result are retained. Hardware transcripts
follow their compile-only classification; no new headset, browser-video or GPU acceptance is claimed.

The actual polytope parameter-field body also runs with an empty selection and a constant scalar
function in the private Field JVM. Both assertions pass and every emitted color component is finite.
The changed pattern compute shader compiles with glslc and validates with spirv-val for Vulkan 1.2.
The normal typography, keyframer, simulation, rewrite and geometry transcripts execute in the guard.
