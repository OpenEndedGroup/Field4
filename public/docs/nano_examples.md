# Nano examples

Nano has 32 bytes of RAM, and `load` copies your whole program into them starting at address 0. Code and data therefore share one address space (a store past the end of the program writes into the same bytes the instructions came out of, and reads wrap round at 32), which is why one screenshot of a finished run holds the entire machine. There are four registers, `r0` to `r3`, and a handful of arithmetic and branch instructions; the eight-deep stack that `call` and `ret` use is the only piece kept outside the 32 bytes.

A RAM cell's border says what happened to that byte, and where two things are true of the same cell the earlier case in this list wins. Brightest orange with a fill is the cell the last step wrote. Cyan with a fill is the program counter. Plain orange is a byte that changed earlier in the run, cyan is a program byte still holding the value it was loaded with, and dim is a byte no program ever occupied. The run of dim cells at the end of the grid is where RAM stops being code.

The embedded viewer's "Open in Field4" button opens a prepared Nano document with a Nano root already set up.

<iframe
    src="offline.html?file=assets/nano_examples/metadata.edn"
    title="Nano examples (offline viewer)"
    style="width:100%; height:680px; border:1px solid #444; border-radius:8px; background:#111;"
    loading="lazy"
    allowfullscreen>
</iframe>

In a fresh document, Nano boxes live under a parent box that is a language root: run **Set Main Language: Nano** on the parent and its children become Nano boxes. The older `isNanoRoot` property is still honoured, so historical documents keep working, but new ones should use the action. The examples below are written as Nano assembly, and each was assembled and executed by Field; the state you see under it is what came out.

## Registers, output, and the first RAM write

The smallest useful Nano sketch does a piece of arithmetic, emits it with `out`, and parks it in RAM so you can see the machine mutate.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/registers_output_first_write.png" wait="1.0" capture="box" embed=true name="nano-registers-output-first-write" alt="Nano state panel: steps 6, pc 0x08, state halted. R0 holds 0x10 (16), R1 holds 0x09, and the output stream's one entry is 16. In the 32-byte RAM grid the nine program bytes 0x00 to 0x08 are outlined in cyan with 0x08 filled as the program counter, and 0x18 is the single orange cell, holding 0x10. The disassembly lists ldi, ldi, add, sta, out and a highlighted hlt."}
:::static
ldi r0, 7
ldi r1, 9
add r0, r1
sta r0, 24
out r0
hlt
```

`r0` starts as `7`, `r1` starts as `9`, and `add r0, r1` leaves `16` in `r0`. The program assembles to nine bytes, `0x00` through `0x08`, so the orange cell at `0x18` is the first byte this sketch writes outside its own code.

## A loop that paints a strip of RAM

`str rD, rS` writes `rS` into `RAM[rD]`, which is the only indirect store Nano has. So the loop below uses `r0` as an address cursor, `r1` as the value, and `r2` as the countdown, and walks a run of consecutive values into memory.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/fill_a_strip_of_ram.png" wait="1.0" capture="box" embed=true name="nano-fill-a-strip-of-ram" alt="Nano state panel: steps 45, pc 0x0d, halted, zero flag set. R0 has walked to 0x1c and R1 to 0x09, and the output stream's one entry is 9. Cells 0x14 through 0x1b are an unbroken orange run holding 01 02 03 04 05 06 07 08, the eight-cell strip the loop wrote; 0x1c onward is dim."}
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

There is no array abstraction here, only an address register walking forward, and `ldi r2, 8` sets how far: the orange run is exactly eight cells, `0x14` to `0x1b`.

## Summation by counting down to zero

Every instruction that computes a value into a register sets the zero flag, `ldr` and `and` included; `mov` and the two stores are the ones that leave it alone. `jnz` reads whatever the last of them left behind, so the countdown idiom is a matter of *ordering* rather than of `dec` being special: put `dec` immediately before the branch and the loop tests the counter.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/summation_with_loop.png" wait="1.0" capture="box" embed=true name="nano-summation-with-loop" alt="Nano state panel: steps 35, pc 0x0b, halted, zero flag set. R0 has counted down to 0x00 and R1 holds 0x37, 55 in decimal, which is also the output stream's one entry. A single orange cell at 0x1c holds 0x37. The disassembly shows the loop body at L04 as add r1 r0, dec r0, jnz back four bytes."}
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

This sums `10 + 9 + ... + 1` into `r1`, leaving `55` in both the output stream and RAM address `0x1c`. Note that `add r1, r0` sets the flag too — it is only because `dec r0` runs after it that `jnz` sees the counter rather than the accumulator.

## `.data` turns the tail of the program into a lookup table

Nano has no separate ROM section. If you want a table of bytes, you put those bytes in the program stream and load them relative to the current instruction pointer.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/data_lookup_table.png" wait="1.0" capture="box" embed=true name="nano-data-lookup-table" alt="Nano state panel: steps 8, pc 0x0b, halted. R0 holds 0x1a (26), R1 holds 0x08 and R2 holds 0x0d, the three table bytes 5, 8 and 13 summed. A single orange cell at 0x1d holds 0x1a. RAM 0x0c to 0x0e hold 05 08 0d, and the disassembly carries straight past the highlighted hlt to decode those same three bytes as inc r1, dec r0 and not r1."}
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

`ldrr rX, rel` reads `RAM[PC + rel]`, and the program is already sitting in RAM, so the `.data` bytes are reachable by counting forward from the instruction that loads them. There is no symbol table to look them up in: the three offsets `12`, `11` and `10` are hand-computed distances, and moving any instruction above them changes all three. Nothing marks those bytes as data either, so the disassembly under the run carries on past `hlt` and reports `5 8 13` as `inc r1`, `dec r0`, `not r1`. That is the disassembler being literal about where the program ends, not the machine executing anything after it halts.

## `call` and `ret` let you factor repeated work

`call` and `ret` package a tiny operation for reuse. Return addresses go on a separate eight-deep stack, not into RAM, and a ninth nested `call` halts the machine with `Call stack overflow`.

```diffstatic_runner {data-lang=nano screenshot="assets/nano_examples/call_and_ret.png" wait="1.0" capture="box" embed=true name="nano-call-and-ret" alt="Nano state panel: steps 10, pc 0x09, halted. R0 holds 0x18, 24 in decimal, and so does the output stream's one entry. A single orange cell at 0x1e holds 0x18. The disassembly shows two call L0A instructions at 0x02 and 0x04 with offsets +6 and +4, and the two-instruction subroutine, add r0 r0 then ret, sitting after the highlighted hlt."}
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

Starting from `6`, one call to `dbl` gives `12`, and the second gives `24`.

## When a sketch misbehaves

The panels under each run answer different questions. The register cards and RAM grid say what the machine ended up holding. The disassembly says what the assembler thought you wrote, which is where a mis-counted `ldrr` offset or a branch that landed one byte off shows up. And the `state` chip reads either `halted` or `running`, where `running` means the step budget ran out before any `hlt` did — a VM error such as `Call stack overflow` gets its own orange banner above the registers instead.
