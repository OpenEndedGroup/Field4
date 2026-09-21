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
    jep_python_candidates,
    resolve_jep_python,
)

FENCE = (
    '```diffstatic_runner {data-lang=kotlin screenshot="assets/p/kkc.png" '
    'wait="6.0" capture="box" requires="ceres"}\n:::static\nval x = 1\n```\n'
)


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

    Kept in step with `afield.tetgen.TetgenLibrary.searchedPaths` by hand, so the point of
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


if __name__ == "__main__":
    unittest.main()
