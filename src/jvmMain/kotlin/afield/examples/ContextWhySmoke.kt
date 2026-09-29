package afield.examples

/*
 * `why` on a live three-box graph: a child with two parents at the same distance.
 *
 * WhyA holds `speed` twice — a property set through the backdoor (`"fast"`) and a Kotlin variable
 * (`1.0`) its code left behind; WhyB holds only a variable (`2.0`). An undeclared name resolves as
 * `fromContextWithNameOrNull("speed")` would: properties before variables in each box, nearer boxes
 * first, equidistant ones in `parents` order. So WhyA's property answers; WhyA's own variable is
 * shadowed by it; WhyB, as near and holding a different value, is reported as competing — the
 * ambiguity `ContextResolution` warns about — and is also among the shadowed. `why` fails unless all
 * of that is so.
 *
 * The `FIELD_TEST` block is parsed by a YAML subset that splits a value on `": "`, so no command line
 * below may contain a colon followed by a space.
 */

/*
FIELD_TEST
id: context.why.tie-and-shadow
runner: backdoor-box
tags: [smoke, context, backdoor]
timeoutSec: 180
execution: launch
boxName: WhyChild
w: 240
h: 80
beforeStop:
  - "new-box --name WhyA --alias why-a --x 0 --y -160 --w 160 --h 60"
  - "run why-a --value 'val speed = 1.0'"
  - "set why-a speed fast"
  - "new-box --name WhyB --alias why-b --x 220 --y -160 --w 160 --h 60"
  - "run why-b --value 'val speed = 2.0'"
  - "connect why-a WhyChild"
  - "connect why-b WhyChild"
  - "why WhyChild speed --expect-supplier why-a --expect-competing why-b --expect-shadowed why-a --expect-shadowed why-b"
  - "why WhyChild --type java.lang.Double --expect-supplier why-a"
*/
private val CONTEXT_WHY_SNIPPET = """
val here = "the child asks; its parents answer"
"""
