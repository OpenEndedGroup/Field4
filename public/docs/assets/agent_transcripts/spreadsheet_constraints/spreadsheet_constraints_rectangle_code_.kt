/*
Width and height are variables; area and perimeter are ordinary formulas over them.
The two assertions hold the area at 40 and the width at no more than 6.
Type 8 into the width: the sheet stops it at 6 and the height takes up the area.
*/
sheet(height = 260) {
    rows = 6
    cols = 2
    columnWidth("A", 140)
    this["A1"] = "width"
    formula("B1", "free(4)")
    this["A2"] = "height"
    formula("B2", "free(5)")
    this["A3"] = "area"
    formula("B3", "B1 * B2")
    this["A4"] = "perimeter"
    formula("B4", "2 * (B1 + B2)")
    this["A5"] = "area is 40"
    formula("B5", "B3 eq 40")
    this["A6"] = "no wider than 6"
    formula("B6", "B1 le 6")
}
