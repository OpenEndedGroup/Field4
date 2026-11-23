// A bill priced by the rates in the box above. Edit the cells in the output, or the numbers here.
sheet(height = 320) {
    rows = 9
    cols = 4
    this["A1"] = "Item"
    this["B1"] = "Price"
    this["C1"] = "Qty"
    this["D1"] = "Net"
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
    this["A6"] = "Discount"
    this["B6"] = discountRate
    this["A7"] = "Tax"
    this["B7"] = taxRate
    this["A8"] = "Discounted"
    formula("D8", "D5 * B6")
    this["A9"] = "With tax"
    formula("D9", "round(D8 * B7 * 100) / 100")
}
