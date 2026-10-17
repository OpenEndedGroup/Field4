import org.jetbrains.kotlinx.dataframe.api.*

// typed() reads the loaded columns and writes a named schema into this box's preamble:
// @DataSchema interface Peach { val name: String; val age: Int }
// That named type lets later fragments use the columns as Kotlin properties.
val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
).typed("Peach")

// Keep this break: the first fragment writes Peach, then Field reloads the preamble
// before compiling the fragment below. Once loaded, select and run the lines below
// repeatedly to query the same frame without reloading its data.
//%-
val p = people.cast<Peach>()
println("ages: " + p.age.toList())
println("adults: " + p.filter { age >= 18 }.name.toList())
