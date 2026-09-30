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
  cannot be named in its source.

Built with `./gradlew :ktfmt:jar`. sha256 `5f334deaf5bd3667e6ef0f17da58bcf270b47c57d5e19c1a2807cb09c079f605`.

Verified on 2.5.0-Beta1: ktfmt's own suite runs 927 tests with 1 failure,
`NativeImageSubstitutionTest`. That test checks the GraalVM native-image build's substitution of
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
  `afield.FieldKtParser` (`afield/internal/FieldKtParser.kt`), used by the comment pass, the
  RemoteJS instrumenter and the pattern-rewrite smoke. Nothing shadows ktfmt.
- ktfmt's stock parser runs fine beside the forked compiler: checked in a live Field, where
  `-format-property` formatted and synced a box after that box had compiled and run.
- `formatKotlinCode` passes `FileType.SCRIPT`: box code has top-level statements.
- A local jar has no POM, so `build.gradle.kts` names ktfmt's runtime dependencies
  (google-java-format 1.23.0, guava 33.5.0-jre, ec4j 1.2.0), at the versions Field already resolved.

When `org.jetbrains:ktfmt` publishes a release that supports 2.5, replace the `files(...)` line and
those three with that coordinate, and delete the jar, the patch and this note.
