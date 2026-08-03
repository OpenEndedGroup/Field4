"""Regression guard for the smoke runner's JVM reaper being scoped to one checkout.

`run_field_tests.py` sweeps away Field JVMs orphaned by earlier runs before booting its own.
Until 2026-07-30 it recognised them by main class alone (`FIELD_JVM_MARKERS`), which matches
*every* Field JVM on the machine -- so parallel agents working in sibling git worktrees
SIGKILLed each other's tests. The victim's run died mid-suite, or finished against a
half-dead server and reported a green. `--no-reap` was no defence: it protects everyone else
from you, and nobody from them.

`REAL_RUNJVM_COMMAND` below is not invented. It is the command line of an actual
`PORT=9540 ./gradlew runJvm` process (pid 13173, 69,556 characters as `ps -eo command=`
printed it), with its worktree path replaced by `{root}` and ~200 unrelated Gradle-cache jars
dropped from the classpath. Everything that the classifier looks at -- argv[0], the
`-Dafield.aot.*` block, the `-cp` entries under the checkout's `build/`, the trailing
`afield.Server` -- is verbatim.

Run with:  python -m unittest tools.test_run_field_tests_reaper
"""

import sys
import unittest
from pathlib import Path

# `run_field_tests.py` imports its siblings by bare name, the way it does when run as a script
# from `tools/`. Put that directory on the path before importing it under its package name.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools.run_field_tests import (  # noqa: E402  (path set up just above)
    FIELD_JVM_MARKERS,
    _checkout_scope_prefix,
    _command_belongs_to,
)

JAVA = (
    "/Users/marc/.gradle/jdks/graalvm_community-25-aarch64-os_x.2/"
    "graalvm-community-openjdk-25.0.2+10.1/Contents/Home/bin/java"
)

REAL_RUNJVM_COMMAND = (
    JAVA + " "
    "-Dableton.link.library={root}/libs/libabl_link_jvm.dylib "
    "-Dafield.aot.attempted=false "
    "-Dafield.aot.autoGenerate=false "
    "-Dafield.aot.cache.path={root}/build/aot/field.aot "
    "-Dafield.aot.detail=No AOT cache was requested. Start with ./gradlew runDesktop "
    "-PfieldAotCache=true to generate and use it. "
    "-Dafield.aot.state=not-attempted "
    "-Dafield.aot.summary=not attempted "
    "-Dafield.ceres.library={root}/build/ken-ceres-redistributable/libken_ceres_jvm.dylib "
    "-Dorg.lwjgl.system.stackSize=256 -XstartOnFirstThread "
    "--add-opens=java.base/java.net=ALL-UNNAMED "
    "--enable-native-access=ALL-UNNAMED -Xdock:name=Field4 "
    "-Xdock:icon={root}/electron/icon-1024.png "
    "--add-modules jdk.incubator.vector -XX:+EnableJVMCI -XX:-UseJVMCICompiler -Xmx8g "
    "-Dfile.encoding=UTF-8 "
    "-cp {root}/build/classes/kotlin/jvm/main:{root}/build/classes/java/jvmMain:"
    "{root}/build/processedResources/jvm/main:{root}/libs/nashorn-core-15.6.jar:"
    "/Users/marc/.gradle/caches/modules-2/files-2.1/io.ktor/ktor-server-netty-jvm/3.3.0/"
    "3b54f1a0d37fbe1571c2ec8358416d0cc5e7e9b3/ktor-server-netty-jvm-3.3.0.jar "
    "afield.Server"
)

PRIMARY = Path("/Users/marc/field4")
MINE = Path("/Users/marc/field4-worktrees/2026-07-30-2-transcript-race-and-harness-scoping")
SIBLING = Path("/Users/marc/field4-worktrees/2026-07-30-1-typography-geometry-family")


def command_from(root: Path) -> str:
    return REAL_RUNJVM_COMMAND.format(root=str(root))


class ScopePrefixTests(unittest.TestCase):
    def test_prefix_ends_in_exactly_one_separator(self) -> None:
        self.assertEqual(_checkout_scope_prefix(PRIMARY), "/Users/marc/field4/")
        self.assertEqual(_checkout_scope_prefix(Path("/Users/marc/field4/")), "/Users/marc/field4/")

    def test_the_separator_is_what_stops_the_prefix_collision(self) -> None:
        # This is the whole bug in one assertion: the primary checkout's path IS a string
        # prefix of every worktree's path, and only the separator separates them.
        self.assertTrue(str(MINE).startswith(str(PRIMARY)))
        self.assertFalse(str(MINE).startswith(_checkout_scope_prefix(PRIMARY)))


class AttributionTests(unittest.TestCase):
    def test_a_real_runjvm_is_attributed_to_its_own_checkout(self) -> None:
        self.assertTrue(_command_belongs_to(command_from(MINE), MINE))
        self.assertTrue(_command_belongs_to(command_from(PRIMARY), PRIMARY))

    def test_a_sibling_worktrees_jvm_is_not_ours(self) -> None:
        for mine, theirs in ((MINE, SIBLING), (SIBLING, MINE), (MINE, PRIMARY)):
            with self.subTest(mine=mine.name, theirs=theirs.name):
                self.assertFalse(_command_belongs_to(command_from(theirs), mine))

    def test_the_primary_checkout_does_not_claim_a_worktrees_jvm(self) -> None:
        # The regression that mattered: `/Users/marc/field4` must not swallow
        # `/Users/marc/field4-worktrees/...` by plain prefix.
        self.assertFalse(_command_belongs_to(command_from(MINE), PRIMARY))
        self.assertFalse(_command_belongs_to(command_from(SIBLING), PRIMARY))

    def test_the_marker_test_alone_cannot_tell_them_apart(self) -> None:
        # Pins *why* the scoping is needed: the old test said yes to all of them.
        for root in (MINE, SIBLING, PRIMARY):
            rest = command_from(root).partition(" ")[2]
            self.assertTrue(any(marker in rest for marker in FIELD_JVM_MARKERS), root)

    def test_the_classpath_signal_carries_it_when_the_aot_property_is_empty(self) -> None:
        # `-PfieldAotCache=false` resolves the cache file to null and build.gradle.kts emits
        # `-Dafield.aot.cache.path=` with nothing after it.
        emptied = command_from(MINE).replace(
            f"-Dafield.aot.cache.path={MINE}/build/aot/field.aot", "-Dafield.aot.cache.path="
        )
        self.assertIn("-Dafield.aot.cache.path= ", emptied)
        self.assertTrue(_command_belongs_to(emptied, MINE))
        self.assertFalse(_command_belongs_to(emptied, SIBLING))

    def test_the_jvm_main_launcher_has_no_aot_property_at_all(self) -> None:
        """Observed live, not hypothesised.

        While this change was being verified, a sibling agent's smoke run appeared on the
        machine as pid 23545: `afield.AgentJvmMainLauncher ... afield.examples.DemoTranscriptExecSmokeTest`,
        68,496 characters of argv, **no `-Dafield.aot.cache.path` argument whatsoever**, and its
        only checkout fingerprint the `-cp` entries under
        `/Users/marc/field4-worktrees/2026-07-30-1-typography-geometry-family/build/`. So the
        classpath fallback is the signal that spares the jvm-main path, and it is load-bearing
        rather than defensive -- without it this checkout's reaper would have SIGKILLed another
        agent's in-flight `demo-transcript.exec`, which is the exact failure being fixed.
        """
        launcher = (
            f"{JAVA} -Xmx8g "
            f"-cp {MINE}/build/classes/kotlin/jvm/main:{MINE}/libs/jep-4.3.1.jar "
            "afield.AgentJvmMainLauncher afield.examples.DemoTranscriptExecSmokeTest"
        )
        self.assertNotIn("-Dafield.aot.cache.path", launcher)
        self.assertTrue(_command_belongs_to(launcher, MINE))
        self.assertFalse(_command_belongs_to(launcher, SIBLING))
        self.assertFalse(_command_belongs_to(launcher, PRIMARY))

    def test_the_aot_signal_carries_it_without_a_classpath(self) -> None:
        # The jvm-main launcher path, and anything launched with a `@argfile` classpath.
        aot_only = (
            f"{JAVA} -Dafield.aot.cache.path={MINE}/build/aot/field.aot "
            "afield.AgentJvmMainLauncher"
        )
        self.assertTrue(_command_belongs_to(aot_only, MINE))
        self.assertFalse(_command_belongs_to(aot_only, SIBLING))

    def test_an_unattributable_field_jvm_is_not_claimed(self) -> None:
        # A Field JVM launched by hand, out of a jar, with the AOT property off and no
        # classpath under any build/ tree: nobody may reap it. Failing to reap someone
        # else's orphan costs memory; reaping it destroys their run.
        stray = f"{JAVA} -Dafield.aot.cache.path= -cp /opt/field/all.jar afield.Server"
        for root in (MINE, SIBLING, PRIMARY):
            self.assertFalse(_command_belongs_to(stray, root), root)

    def test_a_bare_mention_of_the_checkout_path_is_not_enough(self) -> None:
        # A worktree's gitdir lives under the primary checkout
        # (`<primary>/.git/worktrees/<name>`), so "the root appears somewhere in argv" would
        # have re-introduced the cross-checkout kill by another route.
        gitdir = (
            f"{JAVA} -Dafield.aot.cache.path={MINE}/build/aot/field.aot "
            f"-Dgit.dir={PRIMARY}/.git/worktrees/{MINE.name} "
            f"-cp {MINE}/build/classes/kotlin/jvm/main afield.Server"
        )
        self.assertTrue(_command_belongs_to(gitdir, MINE))
        self.assertFalse(_command_belongs_to(gitdir, PRIMARY))


if __name__ == "__main__":
    unittest.main()
