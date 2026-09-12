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
import tempfile
import unittest
from pathlib import Path

# `run_field_tests.py` imports its siblings by bare name, the way it does when run as a script
# from `tools/`. Put that directory on the path before importing it under its package name.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools.run_field_tests import (  # noqa: E402  (path set up just above)
    FIELD_JVM_MARKERS,
    _attribute_command,
    _checkout_scope_prefix,
    _command_belongs_to,
    _describe_candidate,
    classify_field_jvm_lines,
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

REAL_SIDECAR_COMMAND = (
    JAVA + " "
    "-Dapple.awt.UIElement=true -Dfile.encoding=UTF-8 "
    "-cp {root}/tools/code-use-semantic-sidecar/build/classes/java/main:"
    "{root}/tools/code-use-semantic-sidecar/build/classes/kotlin/main:"
    "{root}/tools/code-use-semantic-sidecar/build/resources/main:"
    "/Users/marc/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/"
    "kotlin-stdlib/2.4.20-Beta1/kotlin-stdlib-2.4.20-Beta1.jar "
    "afield.tools.codeuse.CodeUseSemanticSidecarKt --port 17969 --workspace {root}"
)

PRIMARY = Path("/Users/marc/field4")
MINE = Path("/Users/marc/field4-worktrees/2026-07-30-2-transcript-race-and-harness-scoping")
SIBLING = Path("/Users/marc/field4-worktrees/2026-07-30-1-typography-geometry-family")
# A real nested checkout, observed on 2026-09-12: a Claude session's own worktree, created
# *inside* the primary checkout rather than beside it. `lsof` gave this as pid 654's cwd.
NESTED = PRIMARY / ".claude" / "worktrees" / "serene-panini-93b3ca"


def command_from(root: Path) -> str:
    return REAL_RUNJVM_COMMAND.format(root=str(root))


def sidecar_command_from(root: Path) -> str:
    return REAL_SIDECAR_COMMAND.format(root=str(root))


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

    def test_the_semantic_sidecar_is_attributed_to_its_own_checkout_only(self) -> None:
        """The sidecar is a nested Gradle build, not part of the root build output.

        This shape comes from a live leaked sidecar process. Its main class identifies the
        process type, while its only checkout ownership signal is the nested tool classpath.
        Both conditions are required for the preflight reaper to clean it up without touching
        a sibling worktree's completion process.
        """
        sidecar = sidecar_command_from(MINE)
        rest = sidecar.partition(" ")[2]
        self.assertTrue(any(marker in rest for marker in FIELD_JVM_MARKERS))
        self.assertTrue(_command_belongs_to(sidecar, MINE))
        self.assertFalse(_command_belongs_to(sidecar, SIBLING))
        self.assertFalse(_command_belongs_to(sidecar, PRIMARY))

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


class NestedCheckoutTests(unittest.TestCase):
    """The family the trailing-separator trick cannot separate: a checkout *inside* this one.

    Measured, not hypothesised. At 02:2x on 2026-09-12 a run from the primary tree printed

        Reaping 2 orphaned Field JVM(s) from earlier runs in /Users/marc/field4:
          pid 654 (age 16:14:54, rss 2405M), pid 61668 (age 04:55:23, rss 61M)
          escalated to SIGKILL for 2 JVM(s) that ignored SIGINT

    pid 654 was a live Field 16 hours old holding 2.4 GB whose `lsof` cwd was
    `/Users/marc/field4/.claude/worktrees/serene-panini-93b3ca` -- another session's nested
    worktree. `/Users/marc/field4/.claude/...` starts with `/Users/marc/field4/`, separator and
    all, so the sibling-worktree fix of 2026-07-30 never applied to it.
    """

    def test_the_primary_checkout_does_not_claim_a_nested_checkouts_jvm(self) -> None:
        self.assertFalse(_command_belongs_to(command_from(NESTED), PRIMARY))
        self.assertFalse(_command_belongs_to(sidecar_command_from(NESTED), PRIMARY))

    def test_the_nested_checkout_still_owns_its_own_jvm(self) -> None:
        # Tightening the anchor must not orphan the nested checkout's own reaper.
        self.assertTrue(_command_belongs_to(command_from(NESTED), NESTED))
        self.assertTrue(_command_belongs_to(sidecar_command_from(NESTED), NESTED))

    def test_the_separator_trick_alone_could_not_have_saved_pid_654(self) -> None:
        # Pins *why* a second fix was needed: the scope prefix is a genuine prefix of the
        # nested path, which is exactly what the sibling case relied on not being.
        self.assertTrue(str(NESTED).startswith(_checkout_scope_prefix(PRIMARY)))
        self.assertIn(f"-Dafield.aot.cache.path={_checkout_scope_prefix(PRIMARY)}", command_from(NESTED))

    def test_the_build_anchor_is_what_separates_them(self) -> None:
        self.assertFalse(str(NESTED).startswith(str(PRIMARY / "build") + "/"))


class AttributionReasonTests(unittest.TestCase):
    """A kill decision must be reconstructible after the process is gone."""

    def test_the_aot_signal_reports_the_value_it_matched(self) -> None:
        attribution = _attribute_command(command_from(MINE), MINE)
        self.assertTrue(attribution.ours)
        self.assertEqual(attribution.signal, "aot-cache-path")
        self.assertEqual(attribution.detail, f"{MINE}/build/aot/field.aot")

    def test_the_classpath_signal_reports_the_entry_it_matched(self) -> None:
        launcher = (
            f"{JAVA} -Xmx8g "
            f"-cp {MINE}/build/classes/kotlin/jvm/main:{MINE}/libs/jep-4.3.1.jar "
            "afield.AgentJvmMainLauncher afield.examples.DemoTranscriptExecSmokeTest"
        )
        attribution = _attribute_command(launcher, MINE)
        self.assertTrue(attribution.ours)
        self.assertEqual(attribution.signal, "classpath-entry")
        self.assertEqual(attribution.detail, f"{MINE}/build/classes/kotlin/jvm/main")

    def test_a_spared_jvm_names_the_checkout_it_looks_like(self) -> None:
        # The line that would have made the 2026-09-12 misattribution obvious on sight.
        attribution = _attribute_command(command_from(NESTED), PRIMARY)
        self.assertFalse(attribution.ours)
        self.assertEqual(attribution.signal, "unattributed")
        self.assertEqual(attribution.detail, f"looks like {NESTED}")

    def test_a_spared_sidecar_names_the_checkout_not_the_subproject(self) -> None:
        # Observed on this machine while verifying: the hint for a sibling's leaked sidecar read
        # `.../2026-09-12-3-profiling-measurement-model/tools/code-use-semantic-sidecar`, which
        # is a nested Gradle build rather than the checkout anyone would go looking for.
        attribution = _attribute_command(sidecar_command_from(SIBLING), PRIMARY)
        self.assertEqual(attribution.detail, f"looks like {SIBLING}")

    def test_an_unattributable_jvm_says_so_rather_than_naming_a_path(self) -> None:
        stray = f"{JAVA} -Dafield.aot.cache.path= -cp /opt/field/all.jar afield.Server"
        attribution = _attribute_command(stray, PRIMARY)
        self.assertFalse(attribution.ours)
        self.assertIn("no argument names a checkout build directory", attribution.detail)

    def test_an_empty_aot_property_does_not_absorb_the_next_argument(self) -> None:
        # `-PfieldAotCache=false` emits the property empty; splitting the remainder would have
        # reported the *following* argument as this one's value.
        emptied = command_from(MINE).replace(
            f"-Dafield.aot.cache.path={MINE}/build/aot/field.aot", "-Dafield.aot.cache.path="
        )
        attribution = _attribute_command(emptied, MINE)
        self.assertEqual(attribution.signal, "classpath-entry")

    def test_the_printed_description_carries_the_evidence(self) -> None:
        described = _describe_candidate(654, _attribute_command(command_from(NESTED), PRIMARY))
        self.assertIn("pid 654", described)
        self.assertIn(str(NESTED), described)


class CwdCrossCheckTests(unittest.TestCase):
    """The spare-only second opinion, against a real (temporary) pair of checkouts.

    Built on disk rather than out of string paths because the check asks the filesystem which
    checkout a directory belongs to: a git worktree's `.git` is a file, the primary's is a
    directory, and both must count.
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.primary = Path(self._tmp.name) / "field4"
        (self.primary / ".git").mkdir(parents=True)
        (self.primary / "build").mkdir()
        self.nested = self.primary / ".claude" / "worktrees" / "serene-panini-93b3ca"
        self.nested.mkdir(parents=True)
        (self.nested / ".git").write_text(f"gitdir: {self.primary}/.git/worktrees/serene\n")

    def lines(self, root: Path) -> list[tuple[int, str]]:
        return [(654, command_from(root))]

    def test_a_foreign_cwd_demotes_an_otherwise_attributed_jvm(self) -> None:
        # Suppose the command line does say the primary tree -- a relocated AOT cache, a future
        # build-layout change, any re-opening of the pid-654 hole. The cwd is an independent
        # witness, and it says nested.
        ours, foreign = classify_field_jvm_lines(
            self.lines(self.primary),
            root=self.primary,
            protected=set(),
            cwd_lookup=lambda _pid: str(self.nested),
        )
        self.assertEqual(ours, [])
        self.assertEqual(len(foreign), 1)
        self.assertEqual(foreign[0][1].signal, "foreign-cwd")
        self.assertIn(str(self.nested), foreign[0][1].detail)

    def test_our_own_cwd_is_not_an_objection(self) -> None:
        ours, foreign = classify_field_jvm_lines(
            self.lines(self.primary),
            root=self.primary,
            protected=set(),
            cwd_lookup=lambda _pid: str(self.primary),
        )
        self.assertEqual([pid for pid, _ in ours], [654])
        self.assertEqual(foreign, [])

    def test_an_unreadable_cwd_is_no_opinion_rather_than_a_veto(self) -> None:
        # `lsof` may be absent, or refuse another user's process. Abstaining leaves the command
        # line's verdict standing; it must not silently spare everything.
        ours, foreign = classify_field_jvm_lines(
            self.lines(self.primary), root=self.primary, protected=set(), cwd_lookup=lambda _pid: None,
        )
        self.assertEqual([pid for pid, _ in ours], [654])
        self.assertEqual(foreign, [])

    def test_a_deleted_nested_checkout_falls_back_to_the_enclosing_one(self) -> None:
        # An honest limitation, pinned rather than hidden: once the nested worktree is removed,
        # walking up from its path finds the primary checkout's `.git` and the cwd stops
        # objecting. The `<root>/build/` anchor is the load-bearing fix; this is the backstop.
        import shutil

        shutil.rmtree(self.nested)
        ours, _ = classify_field_jvm_lines(
            self.lines(self.primary),
            root=self.primary,
            protected=set(),
            cwd_lookup=lambda _pid: str(self.nested),
        )
        self.assertEqual([pid for pid, _ in ours], [654])

    def test_the_cross_check_never_promotes_a_foreign_jvm(self) -> None:
        # A cwd inside our checkout cannot make a sibling's JVM ours.
        ours, foreign = classify_field_jvm_lines(
            self.lines(SIBLING),
            root=self.primary,
            protected=set(),
            cwd_lookup=lambda _pid: str(self.primary),
        )
        self.assertEqual(ours, [])
        self.assertEqual(len(foreign), 1)

    def test_protected_pids_are_never_classified(self) -> None:
        ours, foreign = classify_field_jvm_lines(
            self.lines(self.primary),
            root=self.primary,
            protected={654},
            cwd_lookup=lambda _pid: str(self.nested),
        )
        self.assertEqual((ours, foreign), ([], []))

    def test_a_non_java_argv0_is_still_ignored(self) -> None:
        # The guard that once reaped the shell running the reaper.
        ours, foreign = classify_field_jvm_lines(
            [(99, f"/bin/zsh -c 'grep afield.Server {self.primary}/build/x'")],
            root=self.primary,
            protected=set(),
        )
        self.assertEqual((ours, foreign), ([], []))


if __name__ == "__main__":
    unittest.main()
