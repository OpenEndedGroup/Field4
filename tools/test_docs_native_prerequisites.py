import os
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import subprocess
import contextlib
import io

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_zensical_docs import (  # noqa: E402
    NATIVE_PREREQUISITES,
    ceres_library_path,
    check_native_prerequisites,
    tetgen_library_path,
    paint_library_path,
    dirt_samples_root_path,
    libpd_library_path,
    csound_library_path,
    jep_python_candidates,
    resolve_jep_python,
    loris_executable_path,
    solidcraft_library_path,
)

FENCE = (
    '```diffstatic_runner {data-lang=kotlin screenshot="assets/p/kkc.png" '
    'wait="6.0" capture="box" requires="ceres"}\n:::static\nval x = 1\n```\n'
)


class LorisPrerequisiteTests(unittest.TestCase):
    def test_loris_is_optional_and_has_an_explicit_build_command(self):
        resolve, command = NATIVE_PREREQUISITES["loris"]
        self.assertIs(resolve, loris_executable_path)
        self.assertEqual(command, "./gradlew buildLorisNative")

    def test_override_and_default_match_kotlin_discovery(self):
        with patch.dict(os.environ, {"AFIELD_LORIS_EXECUTABLE": "/custom/field_loris"}):
            self.assertEqual(loris_executable_path(Path("/repo")), Path("/custom/field_loris"))
        with patch.dict(os.environ, {"AFIELD_LORIS_EXECUTABLE": ""}):
            self.assertEqual(loris_executable_path(Path("/repo")).parent, Path("/repo/build/field-loris/build"))

    def test_multiconfiguration_release_executable_is_found(self):
        with TemporaryDirectory() as tmp, patch.dict(os.environ, {"AFIELD_LORIS_EXECUTABLE": ""}):
            root = Path(tmp)
            name = "field_loris.exe" if sys.platform == "win32" else "field_loris"
            binary = root / "build/field-loris/build/Release" / name
            binary.parent.mkdir(parents=True)
            binary.touch()
            self.assertEqual(loris_executable_path(root), binary)


class PythonPrerequisiteTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)

    def check(self, requirement, *, mode="always", filters=()):
        docs = FakeDocs(self.stack, {"python.md": FENCE.replace('requires="ceres"', f'requires="{requirement}"')})
        check_native_prerequisites(public_dir=docs.public_dir, repo_root=docs.repo_root,
                                   runner_mode=mode, page_filters=filters)

    def test_import_uses_resolved_jep_interpreter_and_module_case(self):
        with patch("build_zensical_docs.resolve_jep_python", return_value=(Path("/chosen/python"), Path("/jep"))), \
             patch("build_zensical_docs.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "", "")) as run:
            self.check("python:SomePackage.submodule")
        self.assertEqual(run.call_args.args[0][0], "/chosen/python")
        self.assertEqual(run.call_args.args[0][-1], "SomePackage.submodule")

    def test_import_error_names_page_interpreter_and_remedy(self):
        with patch("build_zensical_docs.resolve_jep_python", return_value=(Path("/chosen/python"), Path("/jep"))), \
             patch("build_zensical_docs.subprocess.run", return_value=subprocess.CompletedProcess([], 1, "", "ImportError: numpy missing")):
            with self.assertRaisesRegex(RuntimeError, "numpy missing") as caught:
                self.check("python:numpy")
        for evidence in ("python.md", "/chosen/python", "uv sync --group docs"):
            self.assertIn(evidence, str(caught.exception))

    def test_timeout_is_an_actionable_preflight_failure(self):
        with patch("build_zensical_docs.resolve_jep_python", return_value=(Path("/chosen/python"), Path("/jep"))), \
             patch("build_zensical_docs.subprocess.run", side_effect=subprocess.TimeoutExpired("import", 30)):
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                self.check("python:numpy")

    def test_unresolved_python_does_not_use_the_builder_interpreter(self):
        with patch("build_zensical_docs.resolve_jep_python", return_value=None), \
             patch("build_zensical_docs.subprocess.run") as run:
            with self.assertRaisesRegex(RuntimeError, "No JEP-capable"):
                self.check("python:numpy")
            run.assert_not_called()

    def test_cached_modes_warn_never_and_filtered_pages_do_not_probe(self):
        with patch("build_zensical_docs.resolve_jep_python", return_value=None) as resolve:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                self.check("python:numpy", mode="incremental")
            self.assertIn("Warning", stderr.getvalue())
            resolve.reset_mock()
            self.check("python:numpy", mode="never")
            self.check("python:numpy", filters=("other.md",))
            resolve.assert_not_called()

    def test_module_is_not_a_python_expression(self):
        with self.assertRaisesRegex(RuntimeError, "not a known"):
            self.check("python:os;quit()")

    def test_candidate_order_matches_field_not_ambient_uv(self):
        root = Path(self.stack.enter_context(TemporaryDirectory()))
        (root / ".afield").mkdir()
        (root / ".afield/python.properties").write_text("kind=executable\npath=/persisted/python\n")
        with patch("build_zensical_docs.Path.home", return_value=root), \
             patch.dict(os.environ, {"FIELD_PYTHON_VENV": "/explicit", "FIELD_PYTHON_EXECUTABLE": "/explicit/python", "VIRTUAL_ENV": "/ambient", "CONDA_PREFIX": "/conda"}, clear=True), \
             patch("build_zensical_docs.shutil.which", return_value=None):
            candidates = list(jep_python_candidates())
        bin_path = "Scripts/python.exe" if os.name == "nt" else "bin/python3"
        self.assertEqual(candidates, [Path("/explicit") / bin_path, Path("/explicit/python"),
                                     Path("/persisted/python"), Path("/ambient") / bin_path,
                                     Path("/conda") / bin_path])

    def test_resolver_skips_failed_candidates_but_keeps_python_with_its_library(self):
        with patch("build_zensical_docs.jep_python_candidates", return_value=iter([Path("/bad"), Path("/good")])), \
             patch("build_zensical_docs.subprocess.run", side_effect=[
                 subprocess.CompletedProcess([], 1, "", "bad"),
                 subprocess.CompletedProcess([], 0, '["/resolved/python", "/resolved/libjep"]', "")]):
            self.assertEqual(resolve_jep_python(), (Path("/resolved/python"), Path("/resolved/libjep")))


class RefreshNativePrerequisiteTests(unittest.TestCase):
    """A stale artifact passes the existence check, so the needed Gradle tasks run first."""

    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.docs = FakeDocs(self.stack, {"p.md": FENCE.replace('requires="ceres"', 'requires="ceres,paint,manifold"')})
        (self.docs.repo_root / "gradlew").touch()
        self.stack.enter_context(patch("verification_runtime.pinned_graalvm_args", return_value=[]))
        self.run = self.stack.enter_context(
            patch("build_zensical_docs.subprocess.run", return_value=subprocess.CompletedProcess([], 0)))
        self.stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
        self.stack.enter_context(contextlib.redirect_stdout(io.StringIO()))

    def check(self):
        check_native_prerequisites(public_dir=self.docs.public_dir, repo_root=self.docs.repo_root,
                                   runner_mode="incremental", page_filters=())

    def test_runs_the_gradle_task_of_each_needed_prerequisite_once(self):
        self.check()
        argv = self.run.call_args.args[0]
        # manifold's remedy is `brew install`, not a task, so it is not in the list.
        self.assertEqual(argv[-2:], ["buildCeresRedistributable", "buildPaintNative"])
        self.assertEqual(self.run.call_count, 1)

    def test_opt_out(self):
        with patch.dict(os.environ, {"FIELD_DOCS_REFRESH_NATIVES": "0"}):
            self.check()
        self.run.assert_not_called()

    def test_without_a_gradlew_nothing_runs(self):
        (self.docs.repo_root / "gradlew").unlink()
        self.check()
        self.run.assert_not_called()


class FakeDocs:
    """A throwaway public/ tree with one page, so the scan has something to find."""

    def __init__(self, stack, pages: dict[str, str]):
        root = Path(stack.enter_context(TemporaryDirectory()))
        (root / "public" / "docs").mkdir(parents=True)
        for name, body in pages.items():
            (root / "public" / "docs" / name).write_text(body, encoding="utf-8")
        self.repo_root = root
        self.public_dir = root / "public"


class CeresLibraryPathTests(unittest.TestCase):
    def test_the_environment_override_wins(self):
        os.environ["AFIELD_CERES_LIBRARY"] = "/somewhere/libken_ceres_jvm.dylib"
        self.addCleanup(os.environ.pop, "AFIELD_CERES_LIBRARY", None)
        self.assertEqual(
            ceres_library_path(Path("/repo")), Path("/somewhere/libken_ceres_jvm.dylib")
        )

    def test_the_default_is_where_buildCeresRedistributable_writes(self):
        os.environ.pop("AFIELD_CERES_LIBRARY", None)
        resolved = ceres_library_path(Path("/repo"))
        self.assertEqual(resolved.parent, Path("/repo/build/ken-ceres-redistributable"))
        self.assertTrue(resolved.name.startswith("ken_ceres_jvm") or resolved.name.startswith("libken_ceres_jvm"))


class TetgenLibraryPathTests(unittest.TestCase):
    """The same two properties for TetGen, whose resolver is Ceres' with a second candidate.

    Kept in step with `afield.geometry.mesh.tetgen.TetgenLibrary.searchedPaths` by hand, so the point of
    these is to make a drift between the two a test failure rather than a docs build that
    reports "no artifact" while the JVM happily finds one a directory away.
    """

    def test_the_environment_override_wins(self):
        os.environ["AFIELD_TETGEN_LIBRARY"] = "/somewhere/libfield_tetgen_jvm.dylib"
        self.addCleanup(os.environ.pop, "AFIELD_TETGEN_LIBRARY", None)
        self.assertEqual(
            tetgen_library_path(Path("/repo")), Path("/somewhere/libfield_tetgen_jvm.dylib")
        )

    def test_the_default_is_where_build_tetgen_native_writes(self):
        os.environ.pop("AFIELD_TETGEN_LIBRARY", None)
        resolved = tetgen_library_path(Path("/repo"))
        self.assertEqual(resolved.parent, Path("/repo/build/field-tetgen/build"))
        self.assertIn("field_tetgen_jvm", resolved.name)

    def test_it_is_registered_so_a_fence_may_declare_it(self):
        self.assertIn("tetgen", NATIVE_PREREQUISITES)
        resolve, how_to_build = NATIVE_PREREQUISITES["tetgen"]
        self.assertIs(resolve, tetgen_library_path)
        self.assertEqual("./gradlew buildTetgenNative", how_to_build)


class SolidCraftLibraryPathTests(unittest.TestCase):
    def test_capture_uses_the_optional_build_artifact_or_override(self):
        os.environ.pop("AFIELD_SOLIDCRAFT_LIBRARY", None)
        resolved = solidcraft_library_path(Path("/repo"))
        self.assertEqual(resolved.parent, Path("/repo/build/native/solidcraft"))
        self.assertIn("field_solidcraft_jvm", resolved.name)
        os.environ["AFIELD_SOLIDCRAFT_LIBRARY"] = "/custom/solidcraft.so"
        self.addCleanup(os.environ.pop, "AFIELD_SOLIDCRAFT_LIBRARY", None)
        self.assertEqual(solidcraft_library_path(Path("/repo")), Path("/custom/solidcraft.so"))
        self.assertEqual(NATIVE_PREREQUISITES["solidcraft"][1], "./gradlew buildSolidCraftNative")


class PaintLibraryPathTests(unittest.TestCase):
    """TetGen's three properties for the paint wrapper, kept in step with
    `afield.graphics.paint.PaintLibrary.searchedPaths` by hand."""

    def test_the_environment_override_wins(self):
        os.environ["AFIELD_PAINT_LIBRARY"] = "/somewhere/libfield_paint_jvm.dylib"
        self.addCleanup(os.environ.pop, "AFIELD_PAINT_LIBRARY", None)
        self.assertEqual(paint_library_path(Path("/repo")), Path("/somewhere/libfield_paint_jvm.dylib"))

    def test_the_default_is_where_build_paint_native_writes(self):
        os.environ.pop("AFIELD_PAINT_LIBRARY", None)
        resolved = paint_library_path(Path("/repo"))
        self.assertEqual(resolved.parent, Path("/repo/build/field-paint/release"))
        self.assertIn("field_paint_jvm", resolved.name)

    def test_it_is_registered_so_a_fence_may_declare_it(self):
        resolve, how_to_build = NATIVE_PREREQUISITES["paint"]
        self.assertIs(resolve, paint_library_path)
        self.assertEqual("./gradlew buildPaintNative", how_to_build)


class LibpdLibraryPathTests(unittest.TestCase):
    """TetGen's two properties for libpd, kept in step with `afield.audio.pd.PdLibrary.searchedPaths`."""

    def test_the_environment_override_wins(self):
        os.environ["AFIELD_PD_LIBRARY"] = "/somewhere/libpd-multi.dylib"
        self.addCleanup(os.environ.pop, "AFIELD_PD_LIBRARY", None)
        self.assertEqual(libpd_library_path(Path("/repo")), Path("/somewhere/libpd-multi.dylib"))

    def test_the_default_is_where_build_libpd_native_writes(self):
        os.environ.pop("AFIELD_PD_LIBRARY", None)
        resolved = libpd_library_path(Path("/repo"))
        self.assertEqual(resolved.parent, Path("/repo/build/field-libpd/build/libs"))
        self.assertIn("libpd-multi", resolved.name)

    def test_it_is_registered_so_a_fence_may_declare_it(self):
        resolve, how_to_build = NATIVE_PREREQUISITES["libpd"]
        self.assertIs(resolve, libpd_library_path)
        self.assertEqual("./gradlew buildLibpdNative", how_to_build)


class CsoundLibraryPathTests(unittest.TestCase):
    """libpd's properties for libcsound, kept in step with `afield.audio.csound.CsoundLibrary.searchedPaths`."""

    def test_the_environment_override_wins(self):
        os.environ["AFIELD_CSOUND_LIBRARY"] = "/somewhere/libcsound64.so"
        self.addCleanup(os.environ.pop, "AFIELD_CSOUND_LIBRARY", None)
        self.assertEqual(csound_library_path(Path("/repo")), Path("/somewhere/libcsound64.so"))

    def test_the_default_is_where_build_csound_native_writes(self):
        os.environ.pop("AFIELD_CSOUND_LIBRARY", None)
        resolved = csound_library_path(Path("/nonexistent-repo"))
        if sys.platform.startswith("win"):
            return
        self.assertTrue(str(resolved).startswith("/nonexistent-repo/build/field-csound/build/"), resolved)

    def test_it_is_registered_so_a_fence_may_declare_it(self):
        resolve, how_to_build = NATIVE_PREREQUISITES["csound"]
        self.assertIs(resolve, csound_library_path)
        self.assertEqual("./gradlew buildCsoundNative", how_to_build)


class NativePrerequisiteTests(unittest.TestCase):
    def setUp(self):
        import contextlib

        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        os.environ["AFIELD_CERES_LIBRARY"] = "/definitely/not/here/libken_ceres_jvm.dylib"
        self.addCleanup(os.environ.pop, "AFIELD_CERES_LIBRARY", None)

    def docs(self, pages):
        return FakeDocs(self.stack, pages)

    def check(self, docs, *, runner_mode, page_filters=()):
        check_native_prerequisites(
            public_dir=docs.public_dir,
            repo_root=docs.repo_root,
            page_filters=page_filters,
            runner_mode=runner_mode,
        )

    def test_a_full_recapture_fails_immediately_and_names_the_build_command(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        with self.assertRaises(RuntimeError) as caught:
            self.check(docs, runner_mode="always")
        message = str(caught.exception)
        self.assertIn("./gradlew buildCeresRedistributable", message)
        self.assertIn("polytope_examples.md", message)

    def test_incremental_only_warns_because_the_committed_asset_may_cover_it(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        self.check(docs, runner_mode="incremental")

    def test_runner_mode_never_does_not_scan_at_all(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        self.check(docs, runner_mode="never")

    def test_a_page_filter_that_excludes_the_page_excludes_its_prerequisite(self):
        docs = self.docs({"polytope_examples.md": FENCE, "rectsl_examples.md": "no fences\n"})
        self.check(docs, runner_mode="always", page_filters=("rectsl_examples",))

    def test_a_present_artifact_passes(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        artifact = docs.repo_root / "build" / "ken-ceres-redistributable" / "lib.dylib"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"")
        os.environ["AFIELD_CERES_LIBRARY"] = str(artifact)
        self.check(docs, runner_mode="always")

    def test_an_unknown_key_is_an_authoring_error_not_a_silent_pass(self):
        docs = self.docs({"p.md": FENCE.replace('requires="ceres"', 'requires="oiled-bearings"')})
        with self.assertRaises(RuntimeError) as caught:
            self.check(docs, runner_mode="always")
        self.assertIn("oiled-bearings", str(caught.exception))

    def test_a_fence_without_requires_declares_nothing(self):
        docs = self.docs({"p.md": FENCE.replace(' requires="ceres"', "")})
        self.check(docs, runner_mode="always")

    def test_the_word_requires_in_prose_is_not_a_declaration(self):
        docs = self.docs({"p.md": 'This capture requires="ceres" in spirit only.\n'})
        self.check(docs, runner_mode="always")


class DirtSamplesRootPathTests(unittest.TestCase):
    """The pinned Dirt-Samples subset counts as present only when every pinned file verifies."""

    def test_an_empty_cache_is_absent_and_a_verified_one_is_present(self):
        import fetch_dirt_samples
        with TemporaryDirectory() as cache, patch.dict(os.environ, {"AFIELD_NATIVE_DOWNLOAD_CACHE": cache}):
            self.assertFalse(dirt_samples_root_path(Path(".")).exists())
            manifest = fetch_dirt_samples.load_manifest()
            root = fetch_dirt_samples.pinned_root(manifest)
            # One file present with the wrong bytes is still absent.
            first = root / manifest["files"][0]["path"]
            first.parent.mkdir(parents=True)
            first.write_bytes(b"not a wav")
            self.assertFalse(dirt_samples_root_path(Path(".")).exists())
            self.assertIn("wrong digest", fetch_dirt_samples.problems_in(root, manifest)[0])

    def test_the_key_is_registered_with_the_gradle_remedy(self):
        resolve, remedy = NATIVE_PREREQUISITES["dirt-samples"]
        self.assertIs(resolve, dirt_samples_root_path)
        self.assertIn("fetchDocsDirtSamples", remedy)


if __name__ == "__main__":
    unittest.main()
