import afield.stdlib.Functions
import afield.stdlib.StudyComposition
import afield.stdlib.StudyChoice

// Three empty channels. Describe the function you want in this box's AI4 chat.
with(Functions) {
    StudyComposition(
        channels = listOf("x", "y", "z").map { name ->
            fn(0.0..3600.0, name) { 0.0 }
        },
        choices = listOf(
            StudyChoice("One hour, three zero-valued channels: a starting point to replace", "starter assumption")
        )
    )
}
