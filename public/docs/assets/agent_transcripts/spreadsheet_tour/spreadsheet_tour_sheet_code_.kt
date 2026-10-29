sheet(height = 320) {
    rows = 8
    cols = 5
    this["A1"] = "Item"
    this["B1"] = "Price"
    this["C1"] = "Qty"
    this["D1"] = "Net"
    this["E1"] = "Width"
    this["A2"] = "paper"
    this["B2"] = 4.5
    this["C2"] = 3
    this["A3"] = "ink"
    this["B3"] = 12
    this["C3"] = 2
    this["A4"] = "pens"
    this["B4"] = 1.25
    this["C4"] = 10
    formula("D2", "B2 * C2")
    formula("D3", "B3 * C3")
    formula("D4", "B4 * C4")
    this["A5"] = "Subtotal"
    formula("D5", "sum(D2:D4)")
    this["A6"] = "Discounted"
    formula("D6", "D5 * box(\"Rates · run me first\")[\"discount\"]")
    this["A7"] = "With tax"
    formula("D7", "round(D6 * box(\"Rates · run me first\")[\"tax\"] * 100) / 100")
    formula("E2", "0.21 | m")
    formula("E3", "E2 * C2")
}
