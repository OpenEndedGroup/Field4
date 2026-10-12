// THE ESCAPE HATCH, AND WHERE IT STOPS.
//
// `hostMacroExpand` runs any registered macro's expansion against this box and
// hands the rewrite back as a typed record — so a macro's generated Kotlin is
// readable from FieldML even when it is not runnable. `.declarations` below is
// the exact text `prop<Double>(speed)` would have put in a Kotlin box.
let asProp = hostMacroExpand "prop" ("<Double>", "(speed)")

// `sourceBlock` is the honest bound. It *is* call-shaped, and still unreachable:
// the callee is a constructor taking a lambda whose body is the original Kotlin.
// So `.call` is empty, and the reason is visible in `.here` rather than asserted
// in a comment.
let asBlock = hostMacroExpand "sourceBlock" "{ 2 + 2 }"

{
  propGenerates = asProp.declarations
  propCallSiteBecomes = asProp.here
  blockCallSiteBecomes = asBlock.here
  blockIsNotCallable = asBlock.call == ""
}