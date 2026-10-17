let Math = hostClass "java.lang.Math"
let Strings = hostClass "java.lang.String"
let Collections = hostClass "java.util.Collections"

// Every line here is a call the previous bridge could not make.
{
  // Math.max is declared four times; the argument widths choose one.
  intMax = hostStaticCall "max" (3, 7) Math
  floatMax = hostStaticCall "max" (3.5, 7.25) Math

  // A Java vararg, filled from the spread tuple.
  formatted = hostStaticCall "format" ("%s has %d", "the bridge", 3) Strings

  // A FieldML function as a java.util.Comparator. The result is an erased
  // generic, so it stays Host until hostAsInt says otherwise.
  smallest = hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections |> hostAsInt
}