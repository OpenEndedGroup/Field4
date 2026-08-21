import org.jetbrains.kotlinx.dataframe.api.*

// MEASURED LIMIT — this box is here to be read, not run. Executing it fails with
//   <empty compile time schema>
//   Unresolved reference 'age' on receiver of type 'DataFrame<Any>'
//
// The plugin rewrites a refined call into `run { class Token; ...; call() as
// DataFrame<Token> }` with LOCAL classes, because its extension point works at
// body-resolve and cannot create top-level declarations. A local class cannot appear in
// the type of a snippet's property, so the refinement is erased at the boundary.
//
// Naming a @DataSchema does not rescue it either: the type then survives as
// DataFrame<Person> with a known schema, but TopLevelExtensionsGenerator finds schemas
// through K2's predicate-based provider, which is source-only — so a schema compiled
// elsewhere generates no accessors. The designed route is the plugin's `schemasPath`
// option plus ImportedSchemasGenerator, which Field does not wire yet.
val df = dataFrameOf("name", "age")("Alice", 15)
// ... then, in a LATER execution of this same box:
// df.age.toList()      // <- does not compile
