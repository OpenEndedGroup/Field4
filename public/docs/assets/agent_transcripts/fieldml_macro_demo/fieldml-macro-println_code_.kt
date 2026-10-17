// FIELD'S OWN `println` MACRO, RUN FROM FIELDML.
//
// `println(x)` in a Kotlin box is a source macro too: it rewrites to
// `_printHere(__line__+1, x)` — an ordinary `Box` extension function. So FieldML
// does not need a Kotlin compiler to reach it. It needs the *expansion* (which
// is where a macro does its work) and then a reflective call, and the host
// bridge already had the second half.
//
// The line below really goes through Field's macro. The number is the line the
// output claims to come from — change it and watch the gutter marker move.
let _ = hostMacro "println" "(x)" (4, "this line came out of Field's println macro")

// A record of arguments is a *named* call, which is the only shape that can
// leave a Kotlin default unwritten. `_printlnHere(line, vararg a)` with only
// `line` named prints an empty line.
let _ = hostMacro "println" "(x)" {line = 5}

// And the expansion itself, which is what routed both of those.
let expansion = hostMacroExpand "println" "(x)"
{ rewritesTo = expansion.call, callSite = expansion.here }