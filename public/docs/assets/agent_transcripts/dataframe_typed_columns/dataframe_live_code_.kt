import org.jetbrains.kotlinx.dataframe.api.*

// The plugin's inferred type cannot outlive the expression that made it: it is built from
// LOCAL classes, which cannot appear in the type of a snippet's property, so on its own
// `people` would come back as DataFrame<Any> the moment it crossed a compile boundary.
//
// `typed()` fixes that by reading the frame's RUNTIME columns — the data is already
// loaded, so they are exactly right — and writing a real schema into this box's preamble:
//   @DataSchema interface Peach { val name: String; val age: Int }
val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
).typed("Peach")

// `//%-` is what makes this work in ONE execution. Field splits the box there and runs the
// halves in order, re-running the preamble setup before compiling each one (the
// per-fragment runLaunchSetupBlocks in execution.kt), so `Peach` is declared by the time
// the half below is compiled. Delete the marker and this box stops compiling: with a
// single fragment the schema is written only after that fragment was already compiled.
//
// The same thing works across separate executions — load once here, then select and run
// the lines below as often as you like without reloading.
//%-
val p = people.cast<Peach>()
println("ages: " + p.age.toList())
println("adults: " + p.filter { age >= 18 }.name.toList())
