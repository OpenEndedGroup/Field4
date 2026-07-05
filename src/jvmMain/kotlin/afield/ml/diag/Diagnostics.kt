package afield.ml.diag

data class SourcePos(
    val index: Int,
    val line: Int,
    val column: Int,
)

data class SourceRange(
    val start: SourcePos,
    val end: SourcePos,
) {
    companion object {
        fun spanning(first: SourceRange, second: SourceRange): SourceRange =
            SourceRange(first.start, second.end)
    }
}

data class Diagnostic(
    val severity: Severity,
    val message: String,
    val range: SourceRange?,
)

enum class Severity {
    Error,
    Warning,
    Info,
}

class CompileAbort(val diagnostic: Diagnostic) : RuntimeException(diagnostic.message)
