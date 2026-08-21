import org.jetbrains.kotlinx.dataframe.api.*

// `decade` is not in the source data and is not a string lookup: `add` returns a NEW
// schema, and the plugin worked out what that schema is. `age` resolving inside both
// lambdas is the same mechanism one step earlier.
val adults = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)
    .filter { age >= 18 }
    .add("decade") { age / 10 }

println("kept: " + adults.name.toList())
println("decade: " + adults.decade.toList())
