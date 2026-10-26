# Spreadsheets

A box can hold a spreadsheet. The sheet is shown in the box's output area, you type into it the
way you type into any spreadsheet, and a cell that starts with `=` is a formula. Formulas are
Kotlin, compiled by the same engine as box code, so a cell can hold a number, a string, a vector,
a quantity with units or a drawing, and a formula can read the variables of any box in the
document. The sheet is saved with the box.

## A sheet in a box

`sheet { ... }` shows a sheet in the box's `spreadsheet` output and fills it the first time it
runs. Inside the braces, `this["A1"] = ...` sets a cell, and `formula("C2", "...")` sets a
formula (the leading `=` is optional). Here is a small bill: prices, quantities, a line total
per row and a sum.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spreadsheets/bill.png" alt="A Field box whose spreadsheet output shows a five-row sheet: Item, Price, Qty and Total headers; paper 4.5 times 3 is 13.5, ink 12 times 2 is 24, pens 1.25 times 10 is 12.5, and a Total row reading 50" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="spreadsheet" wait="12" timeout="180" embed=true name="sheet-bill" w="620" h="380" padding="12"}
sheet(height = 280) {
    rows = 6
    cols = 4
    this["A1"] = "Item";  this["B1"] = "Price"; this["C1"] = "Qty"; this["D1"] = "Total"
    this["A2"] = "paper"; this["B2"] = 4.5;     this["C2"] = 3
    this["A3"] = "ink";   this["B3"] = 12;      this["C3"] = 2
    this["A4"] = "pens";  this["B4"] = 1.25;    this["C4"] = 10
    formula("D2", "B2 * C2")
    formula("D3", "B3 * C3")
    formula("D4", "B4 * C4")
    this["A5"] = "Total"
    formula("D5", "sum(D2:D4)")
}
```

Run it and edit the sheet: click a cell, or move with the arrow keys and Tab, and type. Enter or
F2 edits the cell's input in the bar above the grid; Escape leaves it. Change a price and the
totals follow.

The braces only fill an empty sheet. Once the sheet exists, what you type into it is what the box
keeps, and running the box again does not overwrite it. To replace cells from code, use
`editSheet { ... }`, which takes the same `this["A1"] = ...` and `formula(...)` lines and applies
them to the sheet as it stands; `clear("B3")` and `clearAll()` empty cells.

## Formulas are Kotlin

A formula is a Kotlin expression. `A1` stands for the value in A1, `A1:B3` for the values in a
range, and `$A$1` pins a reference when the formula is copied. Ranges go to `sum`, `product`,
`avg` (also `mean`), `min`, `max` and `count`; each takes any number of cells, ranges and numbers,
as in `max(A1, A2)` or `sum(A1:A3, C1)`. The usual `kotlin.math` functions take cells directly:
`sqrt(A1)`, `abs`, `round`, `floor`, `ceil`, `exp`, `ln`, `log10`, `sin`, `cos`, `tan`,
`atan2(A1, A2)` and `pow(A1, 2)`. Everything else Kotlin has is there too: `if`, `when`, string
templates, `PI`.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spreadsheets/kotlin.png" alt="A spreadsheet with temperatures 12, 31 and 22 in column A; column B labels them cold, hot and mild with a when expression; column C shows the square roots 3.4641, 5.5678 and 4.6904; and row 5 holds their average, 21.6667, and their range, 12 to 31" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="spreadsheet" wait="12" timeout="180" embed=true name="sheet-kotlin" w="620" h="380" padding="12"}
sheet(height = 280) {
    rows = 6
    cols = 4
    this["A1"] = "°C"; this["B1"] = "feels"; this["C1"] = "sqrt"
    this["A2"] = 12; this["A3"] = 31; this["A4"] = 22
    for (row in 2..4) {
        formula("B$row", "when { A$row > 28 -> \"hot\"; A$row < 15 -> \"cold\"; else -> \"mild\" }")
        formula("C$row", "sqrt(A$row)")
    }
    formula("A5", "avg(A2:A4)")
    formula("B5", "\"\${min(A2:A4).toInt()} to \${max(A2:A4).toInt()}\"")
}
```

An empty cell counts as 0 in arithmetic. A range skips empty cells, so `avg(A1:A10)` averages
only the cells that hold something. A number typed into a cell is a number; anything else that
does not start with `=` is text.

## Values that carry units, vectors and drawings

A value keeps what it is as it moves through the sheet. `| m` attaches a unit, and arithmetic on
quantities works out the units of the result. `vec(1.0, 2.0)` is a vector, and arithmetic with a
number applies to each component; `.x`, `.y`, `.z` and `.w` read one. An `FLine` is a drawing:
adding a vector moves it, multiplying scales it, `.x` and `.y` give the corner of its bounds, and
`.width` and `.height` their size. Where a single number is needed, a vector stands for its
length and a line for its diagonal.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spreadsheets/promotion.png" alt="A spreadsheet of promoted values. Row 1: 3 m, 6 m from doubling it, and 1.5 m/s from dividing by 2 s. Row 2: vec2(1, 2) and vec2(3, 6), each drawn as a small arrow, and 2.2361, the first vector's length. Row 3: two FLines of 2 nodes, 30 by 40, each drawn as a diagonal stroke, the second moved by vec(10, 10), and 30, its width" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="spreadsheet" wait="12" timeout="180" embed=true name="sheet-promotion" w="680" h="540" padding="12"}
sheet(height = 440) {
    rows = 3
    cols = 3
    columnWidth("A", 220); columnWidth("B", 220)
    rowHeight(2, 125); rowHeight(3, 180)
    formula("A1", "3 | m")
    formula("B1", "A1 * 2")
    formula("C1", "A1 / (2 | s)")
    formula("A2", "vec(1.0, 2.0)")
    formula("B2", "A2 * 3")
    formula("C2", "abs(A2)")
    formula("A3", "FLine().moveTo(0.0, 0.0).lineTo(30.0, 40.0)")
    formula("B3", "A3 + vec(10.0, 10.0)")
    formula("C3", "B3.width")
}
```

A formula's result can be anything Kotlin can make. Numbers, units and short strings show as
text; a vector, a list or a line shows the way Field shows that value elsewhere, here as a small
drawing.

## Reading other boxes

`self["name"]` reads a variable or property of the box that holds the sheet, and
`box("name")["x"]` reads one from another box, found by its name. The sheet remembers which
values its formulas read, and while it is open it checks them once a frame: when one changes, the
sheet recomputes and shows the new values on the next frame. Here the box's own `rate` feeds the
sheet.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spreadsheets/self.png" alt="A spreadsheet reading the box's own variable: A1 reads rate and B1 shows 1.08; A2 to A4 hold net prices 100, 250 and 40, and B2 to B4 show them with the rate applied, 108, 270 and 43.2" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="spreadsheet" wait="12" timeout="180" embed=true name="sheet-self" w="620" h="360" padding="12"}
val rate = 1.08

sheet(height = 250) {
    rows = 5
    cols = 2
    this["A1"] = "rate"
    formula("B1", "self[\"rate\"]")
    this["A2"] = 100; this["A3"] = 250; this["A4"] = 40
    formula("B2", "A2 * \$B\$1")
    formula("B3", "A3 * \$B\$1")
    formula("B4", "A4 * \$B\$1")
}
```

Change `rate` to `1.2` and run the box again: the sheet keeps its cells, and column B follows.
The same happens when another box's variable changes, whether you ran it or code changed it.
`self.prop("name")` and `box("name").prop("x")` read only the box property, and `.variable(...)`
only the variable; `box("name").keys()` lists what a box has.

## Reading the sheet from code

The box that holds a sheet can read its values. `sheetCell("D5")` is the cell's value, with the
same `.d` (as a `Double`), `.v2`, `.v3`, `.x` and friends that formulas use; `sheetCellValue("D5")`
is the plain Kotlin value. `sheetSnapshot()` gives every cell at once, each with its `ref`,
`input`, the `display` text and an `errorDetail` when it failed.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spreadsheets/read.png" alt="A box with two outputs: a sheet of three amounts, 3, 4 and 5, summed to 12 in B1, and below it a markdown note reading 'B1 is 12, and the sheet has 4 cells: A1, A2, A3, B1'" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="spreadsheet" wait="12" timeout="180" embed=true name="sheet-read" w="620" h="380" padding="12"}
sheet(height = 220) {
    rows = 4
    cols = 2
    this["A1"] = 3; this["A2"] = 4; this["A3"] = 5
    formula("B1", "sum(A1:A3)")
}

val total = sheetCell("B1").d
val refs = sheetSnapshot().cells.map { it.ref }
markdown("B1 is ${total.toInt()}, and the sheet has ${refs.size} cells: ${refs.joinToString()}")
```

## Errors

A formula that fails shows `#ERR`; hover the cell, or select it, to read why. A formula that
reads a failed cell fails too, so a mistake shows everywhere it reaches rather than as a quiet
wrong number. Cells that refer to each other in a loop all show `#CYCLE`.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spreadsheets/errors.png" alt="A spreadsheet with a typo in A1, which shows #ERR, and B1, which adds 1 to A1, also #ERR. A2 and B2 refer to each other and both show #CYCLE, and C2, which doubles A2, shows #ERR" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="spreadsheet" wait="12" timeout="180" embed=true name="sheet-errors" w="620" h="320" padding="12"}
sheet(height = 200) {
    rows = 3
    cols = 3
    formula("A1", "sqrtt(2.0)")
    formula("B1", "A1 + 1")
    formula("A2", "B2 + 1")
    formula("B2", "A2 + 1")
    formula("C2", "A2 * 2")
}
```

## Editing the sheet

| Key | What it does |
| --- | --- |
| arrows, Tab | move between cells; Shift with an arrow extends the selection |
| typing, Enter, F2 | edit the cell |
| Escape | stop editing, clear the selection |
| Delete, Backspace | empty the selected cells |
| Cmd/Ctrl+D | fill the top cell of the selection down through it |
| Cmd/Ctrl+R | fill the left cell of the selection across it |
| Cmd/Ctrl+C, X, V | copy, cut and paste; a pasted formula's references move with it, so `=A1 * 10` copied from B1 to B2 becomes `=A2 * 10`; one copied cell pasted over a selection fills it |
| Cmd/Ctrl+Space | complete cell references, functions and box names while editing a formula |

While you edit a formula, clicking another cell inserts its reference, and the cells and boxes the
formula reads are highlighted. Drag a column or row edge to resize it.

## Sheets as values

A sheet is stored as a `SpreadsheetStoredState`, which `sheetState { ... }` builds with the same
lines as `sheet { ... }`; `columnWidth("A", 200)` and `rowHeight(1, 40)` set sizes. The sheet's output is as wide as its columns, up to 960 pixels, and as tall as `height =`; `width =` sets it. `sheet(property =
"budget") { ... }` keeps a second sheet in the same box under another property, and every function
here takes the same `property =`. `setSpreadsheetState(state)` replaces a sheet outright, and
`getSpreadsheetState()` reads its cells' inputs without evaluating them. The full list is in the
[reference for `spreadsheet.kt`](reference/data.md#spreadsheet-kt).

[Open the spreadsheet demo ↗](offline.html?file=assets/agent_transcripts/spreadsheet_tour/metadata.edn)
