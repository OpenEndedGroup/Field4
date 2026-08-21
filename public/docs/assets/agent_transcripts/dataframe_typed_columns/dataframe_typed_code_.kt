import org.jetbrains.kotlinx.dataframe.api.*

// No @DataSchema, no generated accessors checked into the project. The plugin reads this
// call at compile time and types the result by the columns it produces.
val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)

println("names: " + people.name.toList())
println("ages: " + people.age.toList())
println("mean age: " + people.age.toList().average())
