# Editable frame updates and current import behavior

Baseline `68c5d6fa3`. Seven findings addressed: optical-flow configuration and reconstruction cuts
now expose one state change per line. GPU learning names its training sample count and preview
angular step, with a short explanation of endpoint sampling. Tool-pool actions capture `thisBox`
for later ownership checks; formatting separates declarations and validation/publication steps.
The optical-flow interface and nested renderer bundle use explicit imports without the obsolete
warning about imports being lost by prop rewriting. The bundle builder copy is updated together.

Actual import/ktfmt processing covers ten bodies across seven source files and converts two
collection factories. The source proof protects reviewed tokens, fragment boundaries and raw
strings. The selected idiom audit is clean. Six full graphs/bodies compile in the actual normal
box compiler, including both imported prop types and the complete frame-history builder.
A real Kotlin check compares 100001 old/new training and preview phases exactly. Saved bundle
metadata is refreshed by Field's own codec with per-box non-source properties and graph IDs
checked unchanged; its Markdown mirror/digest is refreshed too.

A temporary formatting-path collision between several `example.kt` files was caught by the
source proof. The harness now uses unique indexed paths; original reviewed edits were restored,
formatted again, proved and the full compile check repeated. Earlier failed proof/logs remain
in the evidence. No GPU training quality, RR availability or new optical-flow numerical quality
is claimed by these source/layout changes. Existing lifecycle findings remain open separately.

See `changes.json`, `verification.json`, reusable harnesses and `evidence.tar.gz`.
