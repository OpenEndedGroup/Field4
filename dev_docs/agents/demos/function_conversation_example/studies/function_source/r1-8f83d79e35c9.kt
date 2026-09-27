import afield.stdlib.Functions
import afield.stdlib.StudyComposition
import afield.stdlib.StudyChoice

// A blank musical page: three constant channels. Ask AI4 to grow this Kotlin.
// The final expression returns a candidate; the tools box compiles and checks it.
with(Functions) {
    StudyComposition(
        channels = listOf("x", "y", "z").map { name -> fn(0.0..3600.0, name) { 0.5 } },
        choices = listOf(StudyChoice("Initial constant background is a disposable starting point", "agent assumption"))
    )
}
