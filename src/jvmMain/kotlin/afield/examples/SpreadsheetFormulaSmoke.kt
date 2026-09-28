package afield.examples

/*
FIELD_TEST
id: examples.spreadsheet.formulas
runner: backdoor-box
tags: [examples, spreadsheet, kotlin, smoke, regression]
timeoutSec: 180
expect:
  outputContains:
    - "SPREADSHEET_FORMULAS_OK"
*/
/**
 * Spreadsheet formulas, evaluated through the real `SpreadsheetEvaluator` in a live session.
 *
 * Every formula is compiled by a Kotlin REPL engine inside a `run { … }` that declares the sheet
 * helpers (`cell`, `range`, `sum`, `max`, …) as local functions, with `self` bound to the box. The
 * expectations were recorded on the K1 engine on 2026-09-28, before the spreadsheet moved to K2, so
 * this is the check that the move changed nothing a sheet can see: cell and range references, the
 * `self` binding, units, a Kotlin compile failure, and a runtime failure. One deliberate change: a
 * formula that fails to compile now carries the compiler's first error (C1); under K1 it read only
 * "Formula compilation failed".
 */
private val SPREADSHEET_FORMULAS_SNIPPET = """
setPropertyRaw("sheetFactor", 7.0, doResync = false)
editSpreadsheet(broadcast = false) {
    clearAll()
    cell("A1", 2)
    cell("A2", 3)
    cell("A3", 5)
    formula("B1", "A1 * 10")
    formula("B2", "sum(A1:A3)")
    formula("B3", "max(A1:A3)!! + min(A1:A3)!!")
    formula("B4", "sqrt(16.0) + PI")
    formula("B5", "self[\"sheetFactor\"] * A2")
    formula("B6", "B1 + B2")
    formula("B7", "\"text\".uppercase()")
    formula("B8", "count(A1:A3)")
    formula("B9", "3 | m")
    formula("C1", "this is not ( kotlin")
    formula("C2", "cell(\"Z99\")")
}
val expected = listOf(
    "B1" to "20", "B2" to "10", "B3" to "7", "B4" to "7.1416", "B5" to "21", "B6" to "30",
    "B7" to "TEXT", "B8" to "3", "B9" to "3 m", "C1" to "#ERR", "C2" to "#ERR",
)
val snap = getSpreadsheetSnapshot()
val problems = mutableListOf<String>()
for ((ref, want) in expected) {
    val c = snap.cell(ref)
    println(ref + " = " + c?.display + (c?.errorDetail?.let { " (" + it + ")" } ?: ""))
    if (c?.display != want) problems += ref + ": expected " + want + ", got " + c?.display + " " + c?.errorDetail
}
val c1 = snap.cell("C1")?.errorDetail.orEmpty()
if (!c1.startsWith("Formula compilation failed: ")) problems += "C1: a formula that does not compile must say why, got " + c1
if (snap.cell("C2")?.errorDetail?.contains("outside the sheet") != true) problems += "C2: expected the out-of-range error, got " + snap.cell("C2")?.errorDetail
if (problems.isNotEmpty()) error("spreadsheet formulas disagree: " + problems.joinToString("; "))
println("SPREADSHEET_FORMULAS_OK")
""".trimIndent()
