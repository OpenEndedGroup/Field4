package afield.examples

/*
 * `propUpDown` end to end, in the smoke catalog: running only the leaf of A -> B -> C pulls B, which
 * pulls A (c=3); then changing A reruns B and C in order (c=7). The full matrix — failures, cycles,
 * joins, skips, diamonds, startup — is `tools/reactive_props_scenarios.py`; this is the one CI keeps.
 *
 * The `FIELD_TEST` block is parsed by a YAML subset that splits a value on `": "`, so no command line
 * below may contain a colon followed by a space. `sleep` records no events, so the cascade's output
 * is collected with `output` afterwards.
 */

/*
FIELD_TEST
id: stdlib.reactive-props.up-then-down
runner: backdoor-box
tags: [smoke, stdlib, context, regression]
timeoutSec: 240
execution: launch
boxName: ReactiveDriver
beforeStop:
  - "new-box --name ReactiveA --alias ra --x 0 --y 120 --w 140 --h 40"
  - "new-box --name ReactiveB --alias rb --x 0 --y 200 --w 140 --h 40"
  - "new-box --name ReactiveC --alias rc --x 0 --y 280 --w 140 --h 40"
  - "connect ra rb"
  - "connect rb rc"
  - "set ra code 'propUpDown<Int>(a); a = 1'"
  - "set rb code 'propUpDown<Int>(a); propUpDown<Int>(b); b = a + 1'"
  - "run rc --value 'propUpDown<Int>(b); println(\"reactive chain c=${b + 1}\")'"
  - "run ra --value 'propUpDown<Int>(a); a = 5'"
  - "sleep 5"
  - "output rc --kind all"
expect:
  outputContains:
    - "reactive chain c=3"
    - "reactive chain c=7"
*/
private val REACTIVE_PROPS_DRIVER_SNIPPET = """
println("reactive props driver")
"""
