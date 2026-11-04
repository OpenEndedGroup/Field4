# ktfmt on Kotlin 2.5

Field formats box code with ktfmt, which parses with the same Kotlin compiler Field runs. From
Kotlin 2.5.0-Beta1 no released ktfmt can do that: 0.64 (2026-06-24, the last `com.facebook:ktfmt`)
fails in `CompatibilityUtilsKt.<clinit>` with

    NoSuchFieldError: KtStubElementTypes does not have member field
      'KtPlaceHolderStubElementType CONTEXT_PARAMETER_LIST'

because the 2.5 PSI declares that field (and `CONTEXT_RECEIVER`) as `KtNodeType`. JVM field links
match name and type. The first function in a box throws `ExceptionInInitializerError`; after that
the class stays dead for the life of the JVM (`NoClassDefFoundError`).

ktfmt now lives at [Kotlin/ktfmt](https://github.com/Kotlin/ktfmt) (group `org.jetbrains`, package
`org.jetbrains.ktfmt`). It keeps one `CompatibilityUtils.kt` per compiler line under
`core/src/main/kotlin-<major.minor>` and, as of 2026-09-30, has sets up to `kotlin-2.4` and nothing
published under the new group.

## What `libs/ktfmt-0.65-SNAPSHOT-kotlin-2.5.jar` is

`Kotlin/ktfmt` at `c223b51121d3a6b13e528c890b0d0f2eea40c25b` plus
[`ktfmt-kotlin-2.5.patch`](ktfmt-kotlin-2.5.patch):

- `kotlin = "2.5.0-Beta1"` in `gradle/libs.versions.toml`;
- `core/src/main/kotlin-2.5/.../CompatibilityUtils.kt`, the `kotlin-2.4` file plus
  `@file:OptIn(KtImplementationDetail::class)` (2.5 marks `KtStubElementTypes` as PSI-internal);
- in `core/build.gradle.kts`, opt-ins to `MessageCollectorAccess` and `CoreEnvironmentDeprecation`
  when building for 2.5. `Parser.kt` is shared by every compiler line, so these 2.5-only markers
  cannot be named in its source;
- in `core/.../format/visitor/PsiUtils.kt`, `isBlockLikeCall` also accepts a Kotlin 2.5 collection
  literal (`isBlockLikeCollectionLiteral`: a trailing comma, or a block-like element), except inside
  an annotation entry. On the kotlinlang experimental engine, which Field uses, `val xs = [` ... `]`
  then formats exactly like `val xs = listOf(` ... `)`; before, the literal broke after `=` and its
  elements sat a continuation indent deeper. The default engine is unchanged. Cases are under
  `core/src/test/resources/cases/kotlinlang/collectionLiterals/` (`.output` for the default engine,
  `.new.output` for the experimental one);
- in `core/.../format/visitor/CallFormatter.kt`, `formatChainedBlockLikeCall` (experimental engine
  only) keeps the first call chained onto a block-like root on the root's closing bracket line,
  `].forEach { ... }` / `).map { ... }`, and puts every later link on its own line at +4. A root
  that fits on one line is not block-like and keeps its one-line chain; `foo(a) { ... }.bar()` is
  not block-like either and keeps the engine's old layout. The `new_codestyle/calls/gh633` and
  `gh633-2` expected outputs changed from `)` / `.bar()` on two lines to `).bar()`, which is this
  rule's intent. Cases: `cases/kotlinlang/chainedBlockLikeRoot/`.

Built with `./gradlew :ktfmt:jar`. sha256 `3bc86cf6b91d02acd6b916fd22aca6dbf8e965cde36972431a81523260585a71`.

Verified on 2.5.0-Beta1: ktfmt's own suite runs 973 tests (927 before the collection-literal and chain cases)
with 1 failure, `NativeImageSubstitutionTest`. With the `PsiUtils.kt` change reverted, the
experimental-engine variants of four collection-literal cases fail (8 tests); with the
`CallFormatter.kt` change reverted, 16 tests fail (5 new chain cases, `chainedForEach`, gh633,
gh633-2). That test checks the GraalVM native-image build's substitution of
`KotlinCoreEnvironment.registerApplicationExtensionPointsAndExtensionsFrom`, whose signature moved in
2.5, and it does not affect JVM use. The context-parameter format case
(`cases/format/misc/contextParameters`) was confirmed to run and pass by a negative control:
corrupting its expected output failed exactly that case.

## Field's side

- ktfmt uses its own `Parser`. Field used to carry a copy under ktfmt's class name
  (`com.facebook.ktfmt.format.Parser`), meant to replace it on the classpath so the environment
  would have `extensionsStorage` set for the forked compiler. It never replaced it: ktfmt's jar
  won, and the copy only appeared to work because 0.64's `Parser.parse(String)` had the same
  signature. Under the new jar that surfaced as `NoSuchMethodError` inside a
  `catch (t: Throwable)`, which silently turned the comment pass off. The copy is now Field's own
  `afield.runtime.kotlin.FieldKtParser` (`afield/internal/FieldKtParser.kt`), used by the comment pass, the
  RemoteJS instrumenter and the pattern-rewrite smoke. Nothing shadows ktfmt.
- ktfmt's stock parser runs fine beside the forked compiler: checked in a live Field, where
  `-format-property` formatted and synced a box after that box had compiled and run.
- `formatKotlinCode` passes `FileType.SCRIPT`: box code has top-level statements. It formats with
  `KOTLINLANG_FORMAT.copy(maxWidth = 120, experimentalEngine = true)`; `KotlinFormatterCollectionLiteralTest`
  fails on the stock engine or on a jar without the collection-literal change.
- A local jar has no POM, so `build.gradle.kts` names ktfmt's runtime dependencies
  (google-java-format 1.23.0, guava 33.5.0-jre, ec4j 1.2.0), at the versions Field already resolved.

When `org.jetbrains:ktfmt` publishes a release that supports 2.5, replace the `files(...)` line and
those three with that coordinate, and delete the jar, the patch and this note.
