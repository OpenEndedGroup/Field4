import com.facebook.ktfmt.format.Formatter

fun formatKotlinCode(code: String) = Formatter.format(Formatter.KOTLINLANG_FORMAT.copy(maxWidth = 200), code)
