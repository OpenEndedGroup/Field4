import org.jetbrains.kotlinx.dataframe.api.*

// The shape that works: derive and consume inside a single execution, so the refined
// type never has to outlive the expression that created it.
val summary = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)
    .add("adult") { age >= 18 }
    .let { d -> d.name.toList().zip(d.adult.toList()) }

println(summary)
