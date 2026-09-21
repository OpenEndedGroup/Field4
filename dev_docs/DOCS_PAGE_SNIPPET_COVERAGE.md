# Which fences on a docs page execute, and what stands behind the ones that do not

Displaced from `public/docs/onnx.md` and `public/docs/python.md`. Both carried a
`## Which code on this page runs` section, and both had grown a second half that was about the
docs build rather than about the API the reader came for. The pages keep the part a reader acts on
— which blocks are illustration, and that an illustration block can drift — and this note keeps
the per-test census and the capture mechanics.

## `onnx.md`

Two of the five Kotlin blocks are `diffstatic_runner` fences and are executed by the docs build
every time, imports and all: the depth figure and the CLIP figure. The other three are illustration.

| block | why it is not executed | what stands behind it |
|---|---|---|
| the orientation snippet | `rgbaBytes` is left undefined | every call in it, via both smokes |
| the session-cache do/don't | half of it is an anti-example that *must* not run | `OnnxSmokeTest.testSessionIsCached` |
| the worker + `StreamingTexture` | needs a Vulkan device and a webcam | the ONNX half, via `testWorkerPublishesAFrame`; the buffer stride, via `testFp16BufferMatchesVulkanStride` |

Read the third column narrowly: it says the calls are exercised somewhere, not that these snippets
still compile. That is why only the two capture fences count as run, and why
`StreamingTexture(...)` and `recordUpload(...)` — which appear in no test — are the two calls the
page flags to its readers.

## `python.md`

The four Python blocks run in a real Field, as real Python boxes: a root box with
`mainLanguage = python`, and the block's text set as a child box's `code`.

All four need JEP, so each fence carries `requires="jep"`. A full recapture without a resolvable
JEP stops in a second rather than failing at the capture, which is the behaviour to expect on a
machine that has never built JEP.

The one shell block is the install recipe. Running it during a docs build would compile a C
extension, so it is marked illustration rather than made a runner fence.

## Open

Neither census is asserted anywhere. A test renamed or deleted leaves the `onnx.md` table above
stale with no guard; `grep` for the method names before trusting a row.
