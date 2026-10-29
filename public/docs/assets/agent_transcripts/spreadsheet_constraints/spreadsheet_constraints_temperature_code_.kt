// B1 and B2 are variables; B4 says how they are related.
// Type 37 into B1 and Fahrenheit follows; type 451 into B2 and Celsius follows.
sheet(height = 200) {
    rows = 4
    cols = 2
    columnWidth("A", 140)
    this["A1"] = "Celsius"
    formula("B1", "free(100)")
    this["A2"] = "Fahrenheit"
    formula("B2", "free(212)")
    this["A3"] = "Kelvin"
    formula("B3", "B1 + 273.15")
    this["A4"] = "F from C"
    formula("B4", "B2 eq B1 * 9.0 / 5.0 + 32.0")
}
