#!/usr/bin/env python3
"""Live scenarios for `propUp` / `propDown` / `propUpDown` (afield.ReactiveRuns).

Each scenario builds boxes in a fresh document through `tools/field_backdoor.py`, runs one of them,
and then runs a *check* box: Kotlin that reads `ReactiveRuns.recentEvents()` (every start, end,
pull, cascade and cap, in order) and the boxes' `result` properties, and throws when the order or
the values are wrong. A scenario passes when every step's verdict is what it expected.

    uv run --project . --group backdoor python tools/reactive_props_scenarios.py            # all
    uv run --project . --group backdoor python tools/reactive_props_scenarios.py up_chain   # some

The startup scenario restarts Field (a second port) to see autorun on a document that was saved
with its values missing.
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKDOOR = ROOT / "tools" / "field_backdoor.py"

# Shared by every check box: the event log without its sequence numbers, and assertions on it.
CHECK_PRELUDE = r'''
val ev = afield.ReactiveRuns.recentEvents().map { it.substringAfter(' ') }
val evText = ev.joinToString("\n")
fun at(e: String): Int = ev.indexOf(e).also { check(it >= 0) { "missing event '$e' in:\n$evText" } }
fun before(a: String, b: String) = check(at(a) < at(b)) { "'$a' should come before '$b' in:\n$evText" }
fun times(prefix: String) = ev.count { it.startsWith(prefix) }
fun none(prefix: String) = check(times(prefix) == 0) { "expected no '$prefix' in:\n$evText" }
fun result(box: String) = thisBox.named(box).getPropertyRaw("result")
fun expectResult(box: String, v: Any) = check(result(box) == v) { "result of $box was ${result(box)}, expected $v\n$evText" }
check(afield.ReactiveRuns.isQuiet()) { "a cascade is still running:\n$evText" }
'''


@dataclass
class Step:
    command: str
    expect_ok: bool = True
    expect_error: str | None = None
    expect_failed: bool = False


@dataclass
class Scenario:
    name: str
    doc: str = ""
    steps: list[Step] = field(default_factory=list)
    _x: float = 0.0

    def p(self, alias: str) -> str:
        return f"{self.name}_{alias}"

    def box(self, *aliases: str) -> "Scenario":
        for a in aliases:
            self.steps.append(Step(shlex.join([
                "new-box", "--name", self.p(a), "--alias", self.p(a),
                "--x", str(self._x), "--y", str(len(self.steps) * 10.0), "--w", "140", "--h", "40"])))
            self._x += 160
        return self

    def connect(self, parent: str, *children: str) -> "Scenario":
        self.steps.append(Step(shlex.join(["connect", "@" + self.p(parent)] + ["@" + self.p(c) for c in children])))
        return self

    def run(self, alias: str, code: str, ok: bool = True, error: str | None = None, launch: bool = False) -> "Scenario":
        argv = ["launch" if launch else "run", "@" + self.p(alias), "--value", code]
        if not ok:
            # The backdoor then reports ok, and the box's errors are what is checked.
            argv.append("--no-fail-on-error")
        self.steps.append(Step(shlex.join(argv), expect_ok=True, expect_error=error if error else None,
                               expect_failed=not ok))
        return self

    def code(self, alias: str, code: str) -> "Scenario":
        """Set [alias]'s code without running it."""
        self.steps.append(Step(shlex.join(["set", "@" + self.p(alias), "code", code])))
        return self

    def ctl(self, code: str) -> "Scenario":
        """Run [code] in this scenario's control box (made on first use, connected to nothing)."""
        if not any(f"--alias {self.p('ctl')}" in s.command for s in self.steps):
            self.box("ctl")
        return self.run("ctl", code)

    def clear(self) -> "Scenario":
        return self.ctl("afield.ReactiveRuns.clearEvents()")

    def sleep(self, seconds: float) -> "Scenario":
        self.steps.append(Step(f"sleep {seconds}"))
        return self

    def stop(self, alias: str) -> "Scenario":
        self.steps.append(Step(shlex.join(["stop", "@" + self.p(alias), "--best-effort"])))
        return self

    def check(self, body: str) -> "Scenario":
        names = "\n".join(f'val {a} = "{self.name}_{a}"' for a in "ABCDEFGHIJK")
        self.box("check")
        return self.run("check", names + "\n" + CHECK_PRELUDE + "\n" + body)


SCENARIOS: dict[str, Scenario] = {}


def scenario(fn):
    s = Scenario(fn.__name__)
    fn(s)
    SCENARIOS[s.name] = s
    return fn


# --- Up ------------------------------------------------------------------------------------------

@scenario
def up_chain(s: Scenario):
    """Fresh boxes, every one declaring what it reads; run only the leaf. C -> B -> A unwinds in order."""
    s.box("A", "B", "C").connect("A", "B").connect("B", "C").clear()
    # Give A and B their code without running them: `set` writes the property only.
    s.code("A", "propUp<Int>(a)\na = 1")
    s.code("B", "propUp<Int>(a)\npropUp<Int>(b)\nb = a + 1")
    s.run("C", "propUp<Int>(b)\nsetPropertyRaw(\"result\", b + 1)")
    s.check('''
expectResult(C, 3)
check(times("start $A") == 1) { "A ran ${times("start $A")} times\\n$evText" }
check(times("start $B") == 1) { "B ran ${times("start $B")} times\\n$evText" }
before("pull $B for $C.b", "start $B launch")
before("start $B launch", "pull $A for $B.a")
before("pull $A for $B.a", "start $A launch")
before("end $A ok", "end $B ok")
before("end $B ok", "end $C ok")
''')


@scenario
def up_chain_launch(s: Scenario):
    """The same chain, the leaf launched instead of run (it goes through the launch queue)."""
    s.box("A", "B", "C").connect("A", "B").connect("B", "C").clear()
    s.code("A", "propUp<Int>(a)\na = 1")
    s.code("B", "propUp<Int>(a)\npropUp<Int>(b)\nb = a + 1")
    s.run("C", "propUp<Int>(b)\nsetPropertyRaw(\"result\", b + 1)", launch=True)
    s.check('''
expectResult(C, 3)
check(times("start $A") == 1 && times("start $B") == 1) { evText }
before("end $A ok", "end $B ok")
before("end $B ok", "end $C ok")
''')


@scenario
def up_from_preamble(s: Scenario):
    """Children declare nothing: the declarations reach them through the gathered preamble. Run
    everything once, delete the values, run the leaf."""
    s.box("A", "B", "C").connect("A", "B").connect("B", "C")
    s.run("A", "propUp<Int>(a)\na = 1")
    s.run("B", "propUp<Int>(b)\nb = a + 1")
    s.run("C", "setPropertyRaw(\"result\", b + 1)")
    s.ctl(f'thisBox.named("{s.p("A")}").removePropertyRaw("a")\nthisBox.named("{s.p("B")}").removePropertyRaw("b")\n'
          'afield.ReactiveRuns.clearEvents()')
    s.run("C", "setPropertyRaw(\"result\", b + 1)")
    s.check('''
expectResult(C, 3)
check(times("start $A") == 1 && times("start $B") == 1) { evText }
before("pull $B for $C.b", "pull $A for $B.a")
before("end $A ok", "end $B ok")
''')


@scenario
def up_present_value_no_pull(s: Scenario):
    """A value that is there is read, not pulled: nothing upstream runs."""
    s.box("A", "B").connect("A", "B")
    s.run("A", "propUp<Int>(a)\na = 4")
    s.clear()
    s.run("B", "propUp<Int>(a)\nsetPropertyRaw(\"result\", a)")
    s.check('''
expectResult(B, 4)
none("pull")
none("start $A")
''')


@scenario
def up_supplier_fails(s: Scenario):
    s.box("A", "B").connect("A", "B").clear()
    s.code("A", "propUp<Int>(a)\nerror(\"boom\")")
    s.run("B", "propUp<Int>(a)\nsetPropertyRaw(\"result\", a)", ok=False, error="ran and failed")
    s.check('''
at("pulled $A for $B.a failed")
check(result(B) == null) { evText }
''')


@scenario
def up_supplier_does_not_set(s: Scenario):
    s.box("A", "B").connect("A", "B").clear()
    s.code("A", "propUp<Int>(a)\nprintln(\"not setting it\")")
    s.run("B", "propUp<Int>(a)\nsetPropertyRaw(\"result\", a)", ok=False, error="did not set 'a'")
    s.check('at("pulled $A for $B.a ok")')


@scenario
def up_no_supplier(s: Scenario):
    s.box("B").clear()
    s.run("B", "propUp<Int>(nothing)\nsetPropertyRaw(\"result\", nothing)", ok=False,
          error="no box upstream declares 'nothing'")
    s.check('at("pull none for $B.nothing")')


@scenario
def up_nullable_pulls_too(s: Scenario):
    """A nullable propUp still pulls before settling for null."""
    s.box("A", "B").connect("A", "B").clear()
    s.code("A", "propUp<Int?>(a)\na = 6")
    s.run("B", "propUp<Int?>(a)\nsetPropertyRaw(\"result\", a ?: -1)")
    s.check('expectResult(B, 6)')


@scenario
def up_cycle(s: Scenario):
    """A reads B's q; B's q needs A's p, and A is waiting: a cycle, refused, not a hang."""
    s.box("A", "B").connect("A", "B").clear()
    s.code("B", "propUp<Int>(p)\npropUp<Int>(q)\nq = p + 1")
    s.run("A", f"propUp<Int>(p)\npropUp<Int>(q)\nval q0 = thisBox.named(\"{s.p('B')}\").q\np = q0 + 1",
          ok=False, error="ran and failed")
    s.check('''
at("cycle $B.p <- $A")
at("pulled $B for $A.q failed")
''')


@scenario
def up_frames_supplier(s: Scenario):
    """A supplier that sets its value and then animates: the pull returns at the end of its setup,
    and the animation keeps going."""
    s.box("A", "B").connect("A", "B").clear()
    s.code("A", "propUp<Int>(a)\na = 5\nvar n = 0\nframes { while (frame()) { n++; setPropertyRaw(\"ticks\", n) } }")
    s.run("B", "propUp<Int>(a)\nsetPropertyRaw(\"result\", a)")
    s.sleep(1.0)
    s.check('''
expectResult(B, 5)
val ticks = thisBox.named(A).getPropertyRaw("ticks") as? Int ?: 0
check(ticks > 0) { "A's frames never ran: ticks=$ticks\\n$evText" }
''')
    s.stop("A")


@scenario
def up_pump_keeps_others_alive(s: Scenario):
    """While B is blocked in a pull, another box's animation still advances (or we learn it does not)."""
    s.box("F", "A", "B").connect("A", "B")
    s.run("F", "var n = 0\nframes { while (frame()) { n++; setPropertyRaw(\"ticks\", n) } }", launch=True)
    s.sleep(0.5)
    s.clear()
    s.code("A", "propUp<Int>(a)\na = 1")
    s.run("B", f"val t0 = thisBox.named(\"{s.p('F')}\").getPropertyRaw(\"ticks\") as Int\n"
               "propUp<Int>(a)\nval v = a\n"
               f"val t1 = thisBox.named(\"{s.p('F')}\").getPropertyRaw(\"ticks\") as Int\n"
               "setPropertyRaw(\"result\", t1 - t0)\nprintln(\"F advanced ${t1 - t0} frames during the pull\")")
    s.check('''
val advanced = result(B) as Int
println("F advanced $advanced frames while B waited for A")
check(advanced > 0) { "the other box's animation froze during the pull ($advanced)\\n$evText" }
''')
    s.stop("F")


@scenario
def up_skips_queued_launch(s: Scenario):
    """B and A queued in that order (as autorun can): B pulls A, and A's own queued launch is
    skipped rather than setting A up a second time."""
    s.box("A", "B", "Q").connect("A", "B")
    s.code("A", "propUp<Int>(a)\na = 8\nsetPropertyRaw(\"runs\", ((getPropertyRaw(\"runs\") as? Int) ?: 0) + 1)")
    s.code("B", "propUp<Int>(a)\nsetPropertyRaw(\"result\", a)")
    s.clear()
    s.run("Q", f'thisBox.named("{s.p("B")}").begin()\nthisBox.named("{s.p("A")}").begin()')
    s.sleep(4.0)
    s.check('''
expectResult(B, 8)
check(thisBox.named(A).getPropertyRaw("runs") == 1) { "A ran ${thisBox.named(A).getPropertyRaw("runs")} times\\n$evText" }
at("skip queued launch of $A: a pull already ran it")
''')


@scenario
def up_queued_in_order_no_pull(s: Scenario):
    """The mirror: A queued before B, so A has run by the time B reads; no pull, no skip."""
    s.box("A", "B", "Q").connect("A", "B")
    s.code("A", "propUp<Int>(a)\na = 8")
    s.code("B", "propUp<Int>(a)\nsetPropertyRaw(\"result\", a)")
    s.clear()
    s.run("Q", f'thisBox.named("{s.p("A")}").begin()\nthisBox.named("{s.p("B")}").begin()')
    s.sleep(4.0)
    s.check('''
expectResult(B, 8)
none("pull")
none("skip")
''')


RUNS = '\nsetPropertyRaw("runs", ((getPropertyRaw("runs") as? Int) ?: 0) + 1)'


@scenario
def up_diamond(s: Scenario):
    """C needs b (from B) and d (from D), and both need a (from A): A runs once, for whichever asks first."""
    s.box("A", "B", "D", "C").connect("A", "B", "D").connect("B", "C").connect("D", "C").clear()
    s.code("A", "propUp<Int>(a)\na = 1" + RUNS)
    s.code("B", "propUp<Int>(a)\npropUp<Int>(b)\nb = a + 10")
    s.code("D", "propUp<Int>(a)\npropUp<Int>(d)\nd = a + 100")
    s.run("C", 'propUp<Int>(b)\npropUp<Int>(d)\nsetPropertyRaw("result", b + d)')
    s.check('''
expectResult(C, 112)
check(thisBox.named(A).getPropertyRaw("runs") == 1) { "A ran ${thisBox.named(A).getPropertyRaw("runs")} times\\n$evText" }
check(times("start $B") == 1 && times("start $D") == 1) { evText }
''')


@scenario
def up_deep_chain(s: Scenario):
    """Six suppliers deep, from the leaf: six nested pumps, unwinding in order."""
    chain = list("ABCDEF")
    s.box(*chain, "G")
    for parent, child in zip(chain, chain[1:] + ["G"]):
        s.connect(parent, child)
    s.clear()
    s.code("A", "propUp<Int>(v0)\nv0 = 0")
    for i, a in enumerate(chain[1:], start=1):
        s.code(a, f"propUp<Int>(v{i - 1})\npropUp<Int>(v{i})\nv{i} = v{i - 1} + 1")
    s.run("G", 'propUp<Int>(v5)\nsetPropertyRaw("result", v5)')
    s.check('''
expectResult(G, 5)
listOf(A, B, C, D, E, F).map { "end $it ok" }.zipWithNext().forEach { (x, y) -> before(x, y) }
listOf(A, B, C, D, E, F).forEach { check(times("start $it") == 1) { "$it ran ${times("start $it")} times\\n$evText" } }
''')


@scenario
def up_concurrent_join(s: Scenario):
    """While C is pulling A, B's animation (running inside C's pump) asks for the same value: B joins
    C's pull instead of starting A a second time."""
    s.box("A", "B", "C").connect("A", "B", "C")
    s.code("A", "propUp<Int>(a)\na = 3" + RUNS)
    s.ctl("// control")
    go = f'thisBox.named("{s.p("ctl")}")'
    s.run("B", "propUp<Int>(a)\nframes { while (frame()) { if (" + go +
          '.getPropertyRaw("go") == true && getPropertyRaw("result") == null) setPropertyRaw("result", a) } }',
          launch=True)
    s.clear()
    s.run("C", go + '.setPropertyRaw("go", true)\npropUp<Int>(a)\nsetPropertyRaw("result", a)')
    s.sleep(1.5)
    s.check('''
expectResult(C, 3)
expectResult(B, 3)
check(thisBox.named(A).getPropertyRaw("runs") == 1) { "A ran ${thisBox.named(A).getPropertyRaw("runs")} times\\n$evText" }
''')
    s.stop("B")


@scenario
def up_rerun_held_during_pull(s: Scenario):
    """A run of B asked for while B is blocked in a pull waits for the pull to return, then runs.
    T's animation frame, running inside B's pump while A compiles, sends the execute-all."""
    s.box("A", "B", "T").connect("A", "B")
    s.ctl("// control")
    go = f'thisBox.named("{s.p("ctl")}")'
    target = f'thisBox.named("{s.p("B")}")'
    s.code("A", "propUp<Int>(a)\na = 2")
    s.run("T", "var fired = false\nframes { while (frame()) { if (!fired && " + go + '.getPropertyRaw("go") == true) {\n'
               "fired = true\nval t = " + target + "\nafield.thisSessionInfo.set(t.inside)\n"
               'afield.handle(afield.Message("execute-all").payload(afield.Execute(t.id, "code", 0, t.getPropertyRaw("code") as String, false)))\n'
               "} } }", launch=True)
    s.clear()
    s.run("B", go + '.setPropertyRaw("go", true)\npropUp<Int>(a)\nsetPropertyRaw("result", a)' + RUNS)
    s.sleep(3.0)
    s.check('''
expectResult(B, 2)
at("held run of $B until its pull returns")
check(thisBox.named(B).getPropertyRaw("runs") == 2) { "B ran ${thisBox.named(B).getPropertyRaw("runs")} times\\n$evText" }
before("pulled $A for $B.a ok", "end $B ok")
''')
    s.stop("T")


# --- Down ----------------------------------------------------------------------------------------

@scenario
def down_diamond(s: Scenario):
    """A -> B, A -> C, B and C -> D: change A, and D reruns once, after both."""
    s.box("A", "B", "C", "D").connect("A", "B", "C").connect("B", "D").connect("C", "D")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("B", "propDown<Int>(y)\ny = x * 10")
    s.run("C", "propDown<Int>(z)\nz = x * 100")
    s.run("D", "setPropertyRaw(\"result\", y + z)")
    s.clear()
    s.run("A", "propDown<Int>(x)\nx = 2")
    s.sleep(4.0)
    s.check('''
expectResult(D, 220)
check(times("start $D") == 1) { "D ran ${times("start $D")} times\\n$evText" }
check(times("start $B") == 1 && times("start $C") == 1) { evText }
before("end $B ok", "start $D launch")
before("end $C ok", "start $D launch")
''')


@scenario
def down_no_glitch(s: Scenario):
    """D reads x from A and y from B, which also reads x: B must rerun before D, and D once."""
    s.box("A", "B", "D").connect("A", "B", "D").connect("B", "D")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("B", "propDown<Int>(y)\ny = x + 1")
    s.run("D", "setPropertyRaw(\"result\", x * 100 + y)")
    s.clear()
    s.run("A", "propDown<Int>(x)\nx = 2")
    s.sleep(4.0)
    s.check('''
expectResult(D, 203)
check(times("start $D") == 1) { "D ran ${times("start $D")} times\\n$evText" }
before("end $B ok", "start $D launch")
''')


@scenario
def down_unchanged_value(s: Scenario):
    """Writing the value that is already there changes nothing downstream."""
    s.box("A", "B").connect("A", "B")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("B", "setPropertyRaw(\"result\", x)")
    s.clear()
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.sleep(1.5)
    s.check('''
none("changed")
none("cascade")
none("start $B")
''')


@scenario
def down_cycle_capped(s: Scenario):
    """A reads B's y, B reads A's x, each recomputes from the other: the cascade stops at the cap."""
    s.box("A", "B").connect("A", "B")
    a_code = f"propDown<Int>(x)\npropDown<Int?>(y)\nx = (thisBox.named(\"{s.p('B')}\").y ?: 0) + 1"
    s.run("A", a_code)
    s.run("B", "propDown<Int?>(y)\ny = x + 1")
    s.clear()
    s.run("A", a_code)
    s.sleep(8.0)
    s.check('''
check(times("cap ") >= 1) { "no cap:\n$evText" }
check(times("cascade $A") <= 3 && times("cascade $B") <= 3) { evText }
''')


@scenario
def down_reader_pulls_up(s: Scenario):
    """A cascade rerun that itself needs a missing value pulls it."""
    s.box("A", "C", "B").connect("A", "B").connect("C", "B")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("C", "propUp<Int>(y)\ny = 7")
    s.run("B", "setPropertyRaw(\"result\", x + y)")
    s.ctl(f'thisBox.named("{s.p("C")}").removePropertyRaw("y")\nafield.ReactiveRuns.clearEvents()')
    s.run("A", "propDown<Int>(x)\nx = 2")
    s.sleep(4.0)
    s.check('''
expectResult(B, 9)
before("cascade $B", "pull $C for $B.y")
before("end $C ok", "end $B ok")
''')


@scenario
def up_down_no_double_run(s: Scenario):
    """propUpDown: the pull's write is read by the puller, so it does not also rerun it."""
    s.box("A", "B").connect("A", "B").clear()
    s.code("A", "propUpDown<Int>(x)\nx = 1")
    s.run("B", "propUpDown<Int>(x)\nsetPropertyRaw(\"result\", x)")
    s.sleep(1.5)
    s.ctl('check(afield.ReactiveRuns.recentEvents().none { it.contains("cascade") }) { afield.ReactiveRuns.recentEvents().joinToString("\\n") }')
    s.run("A", "propUpDown<Int>(x)\nx = 2")
    s.sleep(3.0)
    s.check('''
expectResult(B, 2)
check(times("start $B") == 2) { "B ran ${times("start $B")} times\\n$evText" }
''')


@scenario
def down_from_animation(s: Scenario):
    """Writes from a frames loop (outside any run) cascade too, and do not pile up."""
    s.box("A", "B").connect("A", "B")
    s.run("A", "propDown<Int>(x)\nx = 0\nvar i = 0\nframes { while (frame()) { i++; if (i % 30 == 0) x = i } }", launch=True)
    s.run("B", "setPropertyRaw(\"result\", x)\n"
               "setPropertyRaw(\"runs\", ((getPropertyRaw(\"runs\") as? Int) ?: 0) + 1)")
    s.sleep(3.0)
    s.stop("A")
    s.sleep(1.0)
    s.check('''
val runs = thisBox.named(B).getPropertyRaw("runs") as Int
val r = result(B) as Int
println("B ran $runs times; last x=$r")
check(runs >= 3) { "B reran only $runs times\\n$evText" }
check(r % 30 == 0 && r > 0) { "last x=$r\\n$evText" }
''')



@scenario
def down_two_values_one_rerun(s: Scenario):
    """One run changing two values its reader reads reruns the reader once."""
    s.box("A", "B").connect("A", "B")
    s.run("A", "propDown<Int>(x)\npropDown<Int>(y)\nx = 1\ny = 1")
    s.run("B", 'setPropertyRaw("result", x + y)')
    s.clear()
    s.run("A", "propDown<Int>(x)\npropDown<Int>(y)\nx = 2\ny = 2")
    s.sleep(3.0)
    s.check('''
expectResult(B, 4)
check(times("start $B") == 1) { evText }
''')


@scenario
def down_chain(s: Scenario):
    """A -> B -> C -> D, each recomputing from the one above: one change, three reruns, in order."""
    s.box("A", "B", "C", "D").connect("A", "B").connect("B", "C").connect("C", "D")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("B", "propDown<Int>(y)\ny = x + 1")
    s.run("C", "propDown<Int>(z)\nz = y + 1")
    s.run("D", 'setPropertyRaw("result", z)')
    s.clear()
    s.run("A", "propDown<Int>(x)\nx = 10")
    s.sleep(5.0)
    s.check('''
expectResult(D, 12)
listOf(B, C, D).forEach { check(times("start $it") == 1) { "$it ran ${times("start $it")} times\\n$evText" } }
before("end $B ok", "start $C launch")
before("end $C ok", "start $D launch")
''')


@scenario
def down_reader_fails(s: Scenario):
    """A reader that fails on rerun is reported, not retried, and the others still rerun."""
    s.box("A", "B", "C").connect("A", "B", "C")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("B", 'if (x == 2) error("no")\nsetPropertyRaw("result", x)')
    s.run("C", 'setPropertyRaw("result", x)')
    s.clear()
    s.run("A", "propDown<Int>(x)\nx = 2")
    s.sleep(4.0)
    s.check('''
expectResult(C, 2)
at("cascade $B failed")
check(times("start $B") == 1) { evText }
''')


@scenario
def down_deleted_reader(s: Scenario):
    """A deleted reader is not rerun."""
    s.box("A", "B", "C").connect("A", "B", "C")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("B", 'setPropertyRaw("result", x)')
    s.run("C", 'setPropertyRaw("result", x)')
    s.steps.append(Step(shlex.join(["delete-box", "@" + s.p("B")])))
    s.clear()
    s.run("A", "propDown<Int>(x)\nx = 2")
    s.sleep(3.0)
    s.check('''
expectResult(C, 2)
none("cascade $B")
none("start $B")
''')


# --- Startup -------------------------------------------------------------------------------------

STARTUP = "startup"


def run_startup(port: int, stamp: str, verbose: bool) -> tuple[bool, str]:
    """Save a document whose values are missing, restart Field, and let autorun order it."""
    doc = f"reactive_{STARTUP}_{stamp}"
    s = Scenario(STARTUP, doc=doc)
    s.box("A", "B").connect("A", "B")
    s.run("A", "propUp<Int>(a)\na = 3")
    s.run("B", "setPropertyRaw(\"result\", a * 2)")
    # Through the backdoor's `set`, which sends text: this once stored the string "true", whose read
    # threw and ended autorun for the whole document (AutorunPropertyTest has both halves of the fix).
    s.steps.append(Step(shlex.join(["set", "@" + s.p("A"), "autorun", "true"])))
    s.steps.append(Step(shlex.join(["set", "@" + s.p("B"), "autorun", "true"])))
    s.ctl(f'check(thisBox.named("{s.p("A")}").getPropertyRaw("autorun") == true) {{ "autorun was stored as text" }}\n'
          f'thisBox.named("{s.p("A")}").removePropertyRaw("a")\nthisBox.named("{s.p("B")}").removePropertyRaw("result")')
    # The autosave writes only when a sidecar-backed property changed; an edit makes it save.
    s.code("ctl", "// saved")
    s.sleep(7.0)  # past an autosave
    ok, detail = execute(s, port, verbose)
    if not ok:
        return False, "setup: " + detail
    # Stop that Field (it holds the document), then a fresh JVM on another port opens it and
    # autoruns it.
    stop_field(port)
    after = Scenario(STARTUP, doc=doc)
    after.sleep(8.0)
    after.check('''
expectResult(B, 6)
// Whichever autorun the launch queue reaches first, A runs once: if B goes first it pulls A, and
// A's own queued autorun is then skipped.
check(times("start $A") == 1) { "A ran ${times("start $A")} times\\n$evText" }
println("A ran ${times("start $A")} time(s) at startup; events:\\n$evText")
''')
    try:
        return execute(after, port + 1, verbose, reopen=True)
    finally:
        stop_field(port + 1)


def run_down_after_restart(port: int, stamp: str, verbose: bool) -> tuple[bool, str]:
    """The readers a cascade reruns come from the saved record (dependencies.edn) after a restart."""
    name = "down_after_restart"
    doc = f"reactive_{name}_{stamp}"
    s = Scenario(name, doc=doc)
    s.box("A", "B").connect("A", "B")
    s.run("A", "propDown<Int>(x)\nx = 1")
    s.run("B", 'setPropertyRaw("result", x * 2)')
    s.ctl("// saved: running this edits a sidecar, which is what makes the autosave write")
    s.sleep(7.0)
    ok, detail = execute(s, port, verbose)
    if not ok:
        return False, "setup: " + detail
    stop_field(port)
    after = Scenario(name, doc=doc)
    # Aliases do not outlive the backdoor process that made them; reach A by name.
    after.box("T")
    after.run("T", f'val a = thisBox.named("{s.p("A")}")\n'
                   'a.setPropertyRaw("code", "propDown<Int>(x)\\nx = 5")\na.begin()')
    after.sleep(5.0)
    after.check('''
expectResult(B, 10)
at("cascade $B")
''')
    try:
        return execute(after, port + 1, verbose, reopen=True)
    finally:
        stop_field(port + 1)


RESTARTS = {"startup": run_startup, "down_after_restart": run_down_after_restart}


def stop_field(port: int) -> None:
    pids = subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"], capture_output=True, text=True).stdout.split()
    for pid in pids:
        subprocess.run(["kill", "-INT", pid])
    deadline = time.time() + 20
    while time.time() < deadline and subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
                                                    capture_output=True, text=True).stdout.strip():
        time.sleep(0.5)


# --- Driver --------------------------------------------------------------------------------------

def parse_results(text: str) -> list[dict]:
    dec = json.JSONDecoder()
    out, i = [], 0
    while i < len(text):
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text):
            break
        obj, i = dec.raw_decode(text, i)
        out.append(obj)
    return out


def execute(s: Scenario, port: int, verbose: bool, reopen: bool = False) -> tuple[bool, str]:
    open_cmd = f"open {s.doc}" if reopen else f"open {s.doc} --create"
    commands = [open_cmd] + [st.command for st in s.steps]
    argv = [sys.executable, str(BACKDOOR), "--json", "--port", str(port), "--document", s.doc]
    for c in commands:
        argv += ["--command", c]
    started = time.time()
    proc = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True)
    if verbose:
        print(proc.stderr[-4000:])
    results = parse_results(proc.stdout)
    expectations = [Step(open_cmd)] + s.steps
    for i, step in enumerate(expectations):
        if i >= len(results):
            return False, f"stopped before step {i}: {step.command[:120]!r}\n{proc.stderr[-2000:]}"
        r = results[i]
        errors = json.dumps((r.get("data") or {}).get("errors") or r.get("message") or "")
        if bool(r.get("ok")) != step.expect_ok:
            brief = {k: v for k, v in r.items() if k != "events"}
            tail = [(e.get("name"), json.dumps(e.get("payload"))[:300]) for e in (r.get("events") or [])][-15:]
            return False, (f"step {i} {step.command[:120]!r}: ok={r.get('ok')}, expected {step.expect_ok}\n"
                           f"{errors[:3000]}\n{json.dumps(brief)[:3000]}\nlast events: {tail}")
        if step.expect_failed and not (r.get("data") or {}).get("errors"):
            return False, f"step {i} {step.command[:120]!r}: expected the run to fail, and it reported no errors"
        if step.expect_error and step.expect_error not in errors:
            return False, f"step {i} {step.command[:120]!r}: error did not mention {step.expect_error!r}:\n{errors[:3000]}"
    return True, f"{len(expectations)} steps in {time.time() - started:.0f}s"


DIAG_DIR = ROOT / "build" / "reactive-scenarios"


def diagnose(s: Scenario, port: int) -> str:
    """After a failure: the whole event log and each box's state, from the same Field, plus the tail of
    its log. Written under build/reactive-scenarios/ and summarised in the return value."""
    DIAG_DIR.mkdir(parents=True, exist_ok=True)
    out = DIAG_DIR / f"{s.doc}.txt"
    code = (
        'val sb = StringBuilder()\n'
        'sb.append("events:\\n")\n'
        'afield.ReactiveRuns.recentEvents().forEach { sb.append(it).append("\\n") }\n'
        'sb.append("quiet=" + afield.ReactiveRuns.isQuiet() + "\\nboxes:\\n")\n'
        'thisBox.root.children.forEach { b ->\n'
        '  sb.append(b.name + " running=" + afield.ReactiveRuns.isRunning(b) + " states=" + b.currentRunningStates + '
        '" result=" + b.getPropertyRaw("result") + " reports=" + b.getPropertyRaw("_executionReports") + "\\n")\n'
        '}\n'
        f'java.io.File("{out}").writeText(sb.toString())'
    )
    argv = [sys.executable, str(BACKDOOR), "--json", "--port", str(port), "--document", s.doc,
            "--command", f"open {s.doc}", "--command", "new-box --name diag --alias diag",
            "--command", shlex.join(["run", "@diag", "--value", code, "--no-fail-on-error"]),
            "--command", "status"]
    proc = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True)
    # A reused Field keeps writing to the log of the invocation that started it: the newest one.
    logs = sorted((ROOT / ".field-backdoor" / "logs").glob("*.managed-server.log"), key=lambda p: p.stat().st_mtime)
    log_tail = ""
    if logs:
        lines = logs[-1].read_text(errors="replace").splitlines()
        keep = [l for l in lines if re.search(r"error,|warning,|Exception|^\s+at afield\.|timed? ?out", l)]
        log_tail = f"{logs[-1]}\n" + "\n".join(keep[-80:])
    report = out.read_text() if out.is_file() else f"(diagnostic run failed)\n{proc.stdout[-3000:]}"
    out.write_text(report + "\nserver log (errors):\n" + log_tail)
    return f"diagnostics: {out}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("names", nargs="*", help=f"scenarios to run (default all): {', '.join(list(SCENARIOS) + list(RESTARTS))}")
    ap.add_argument("--port", type=int, default=9497)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    names = args.names or list(SCENARIOS) + list(RESTARTS)
    stamp = time.strftime("%H%M%S")
    failures = []
    for name in names:
        if name in RESTARTS:
            ok, detail = RESTARTS[name](args.port, stamp, args.verbose)
        else:
            s = SCENARIOS[name]
            s.doc = f"reactive_{name}_{stamp}"
            ok, detail = execute(s, args.port, args.verbose)
            if not ok:
                detail += "\n" + diagnose(s, args.port)
        print(f"{'PASS' if ok else 'FAIL'} {name}: {detail}", flush=True)
        if not ok:
            failures.append(name)
    print(f"\n{len(names) - len(failures)}/{len(names)} passed" + (f"; failed: {', '.join(failures)}" if failures else ""))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
