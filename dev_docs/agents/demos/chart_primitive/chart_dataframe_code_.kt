import org.jetbrains.kotlinx.dataframe.api.*

// A frame already renders as a table — that is the right compact form, because a frame
// is rows and columns first. This is the other question a reader has about a numeric
// column: what shape is it.
val runs = dataFrameOf("iteration", "residual", "step")(
    0, 1.00, 0.50,
    1, 0.62, 0.44,
    2, 0.31, 0.39,
    3, 0.18, 0.35,
    4, 0.09, 0.31,
    5, 0.05, 0.28,
    6, 0.02, 0.25,
)

// No column named: the first numeric column becomes the x axis and the rest become
// series, each with a pickable points mark in the line's own colour.
showChart(runs, title = "a frame, charting its own columns")

// ...and the last expression is still the frame, so the table is right underneath it.
runs
