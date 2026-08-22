# Nano examples

Nano is deliberately tiny: 4 registers (`r0`-`r3`), 32 bytes of RAM, a handful of arithmetic and branch instructions, and no room to hide. That is why it is interesting. You can usually *see* the whole machine at once.

The screenshots keep that machine state visible. Blue RAM cells are the initial program bytes; amber cells are bytes that changed during execution; the green ring is the final program counter.

The embedded viewer's "Open in Field4" button opens a prepared Nano document with a Nano root already set up.

<iframe
    src="/offline.html?file=/field4/assets/nano_examples/metadata.edn"
    title="Nano examples (offline viewer)"
    style="width:100%; height:680px; border:1px solid #444; border-radius:8px; background:#111;"
    loading="lazy"
    allowfullscreen>
</iframe>

In a fresh document, Nano boxes live under a parent box whose `isNanoRoot` property is `true`. The examples below are written as Nano assembly, and each was assembled and executed by Field; the state you see under it is what came out.

## Registers, output, and the first RAM write

The smallest useful Nano sketch does a piece of arithmetic, emits it with `out`, and parks it in RAM so you can see the machine mutate.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/registers_output_first_write.png" wait="1.0" capture="box" embed=true name="nano-registers-output-first-write"}
:::static
ldi r0, 7
ldi r1, 9
add r0, r1
sta r0, 24
out r0
hlt
```

`r0` starts as `7`, `r1` starts as `9`, and `add r0, r1` leaves `16` in `r0`. The important visual detail is the amber cell at address `0x18`: the first place this program leaves a footprint in RAM beyond its own code bytes.

## A loop that paints a strip of RAM

Nano gets interesting the moment it repeats itself. Here the loop uses `r0` as an address cursor, `r1` as the value being written, and `r2` as the countdown. The result is a visible run of consecutive values written into memory.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/fill_a_strip_of_ram.png" wait="1.0" capture="box" embed=true name="nano-fill-a-strip-of-ram"}
:::static
ldi r0, 20
ldi r1, 1
ldi r2, 8

loop:
    str r0, r1
    inc r0
    inc r1
    dec r2
    jnz loop

out r1
hlt
```

This is the Nano version of a for-loop writing into an array. There is no array abstraction, only an address register walking forward. The amber run from `0x14` onward makes the write pattern concrete.

## Summation by counting down to zero

`dec` is useful because it sets the zero flag. That gives countdown loops a natural terminator and makes them one of Nano's cleanest idioms.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/summation_with_loop.png" wait="1.0" capture="box" embed=true name="nano-summation-with-loop"}
:::static
ldi r0, 10
ldi r1, 0

loop:
    add r1, r0
    dec r0
    jnz loop

sta r1, 28
out r1
hlt
```

This sums `10 + 9 + ... + 1` into `r1`, leaving `55` in both the output stream and RAM address `0x1c`. The pattern matters: one register is the shrinking counter, one register is the accumulator, and the branch rides the flag left behind by the decrement.

## `.data` turns the tail of the program into a lookup table

Nano has no separate ROM section. If you want a table of bytes, you put those bytes in the program stream and load them relative to the current instruction pointer.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/data_lookup_table.png" wait="1.0" capture="box" embed=true name="nano-data-lookup-table"}
:::static
ldrr r0, 12
ldrr r1, 11
ldrr r2, 10
add r0, r1
add r0, r2
sta r0, 29
out r0
hlt

.data 5 8 13
```

Those three `ldrr` offsets force you to remember that Nano is loading bytes relative to the current program counter, not from a named symbol table. Once the machine state is rendered, the offsets become legible: code bytes, table bytes, and final sum all sit in one view.

## `call` and `ret` let you factor repeated work

Even on a machine this small, repetition wants a name. `call` and `ret` give you enough structure to package a tiny operation and reuse it.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/call_and_ret.png" wait="1.0" capture="box" embed=true name="nano-call-and-ret"}
:::static
ldi r0, 6
call dbl
call dbl
sta r0, 30
out r0
hlt

dbl:
    add r0, r0
    ret
```

Starting from `6`, one call to `dbl` gives `12`, and the second gives `24`. The example is small enough that the subroutine call, return, output, and final RAM write stay visible at once.

## What to pay attention to

Nano is useful here because its computations stay small enough to inspect. When a sketch behaves strangely, the RAM view, register cards, and disassembly are often enough to explain why.

That makes Nano a good medium for artificial-life sketches, tiny interpreters, and systems where local state matters. The machine is not hidden under abstraction. It is the thing you are composing with.
