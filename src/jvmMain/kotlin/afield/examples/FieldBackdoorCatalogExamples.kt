package afield.examples

/*
FIELD_TEST
id: examples.backdoor.println-basic
runner: backdoor-box
tags: [examples, backdoor, kotlin, print]
timeoutSec: 30
expect:
  outputContains:
    - "catalog source hello"
  eventKinds:
    - output
*/
private val PRINTLN_BASIC_SNIPPET = """
println("catalog source hello")
""".trimIndent()

/*
FIELD_TEST
id: examples.backdoor.display-basic
runner: backdoor-box
tags: [examples, backdoor, display, visual]
timeoutSec: 45
withPlaywright: true
waitSec: 0.75
expect:
  outputContains:
    - "catalog visual hello"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
*/
private val DISPLAY_BASIC_SNIPPET = """
display("main", "<div style=\"padding:24px;background:#d9f0ff;border:1px solid #7aa7d9\">catalog visual hello</div>")
""".trimIndent()

/*
FIELD_TEST
id: examples.backdoor.inline-histogram-editor
runner: backdoor-box
tags: [examples, backdoor, editor, visual, histogram]
timeoutSec: 45
waitSec: 1.0
expect:
  outputContains:
    - "catalog editor histogram ready"
  eventKinds:
    - output
  screenshot:
    kind: editor
    padding: 10
*/
private val INLINE_HISTOGRAM_EDITOR_SNIPPET = """
val hist = __m("catalog-hist")
listOf(0.14, 0.36, 0.49, 0.81, 0.63, 0.52, 0.27, 0.71, 0.58).forEach { value ->
    hist.histogram(value, dur = 24)
}
println("catalog editor histogram ready")
""".trimIndent()
