"""Single source of truth for the fabricate docs scene registry.

Each entry maps a ``fabricate-scene`` fence keyword to the
``StandardRendererDocsHarness`` render method that produces its screenshot and
the ready message the generated harness code prints once the capture finishes.

Both consumers import this module so the two never drift:

* ``public/docs_hooks.py`` — the mkdocs docs-build hook. It rejects any
  fabricate-scene fence that is not in this map, and the build parses every
  page, so a scene present in a page but absent here crashes the whole build.
* ``tools/discover_field_tests.py`` — the FIELD_TEST catalog generator.

This module lives in ``public/`` (already on ``sys.path`` when mkdocs loads the
docs hook) so the docs-build import graph stays a clean leaf import with no
``sys.path`` manipulation on the hot path.
"""

from __future__ import annotations

import re
from pathlib import Path

# scene keyword -> (StandardRendererDocsHarness method, harness ready message)
FABRICATE_DOCS_SCENES: dict[str, tuple[str, str]] = {
    "default-scene": (
        "renderDefaultScene",
        "fabricate default scene ready",
    ),
    "shiny-torii-checkerboard": (
        "renderShinyToriCheckerboard",
        "fabricate shiny checkerboard scene ready",
    ),
    "camera-depth-study": (
        "renderCheckerboardCameraDepthStudy",
        "fabricate camera depth study ready",
    ),
    "shader-generated-checkerboard": (
        "renderShaderGeneratedCheckerboard",
        "fabricate shader checkerboard scene ready",
    ),
    "metalness": (
        "renderMetalnessComparison",
        "fabricate metalness comparison ready",
    ),
    "shadows": (
        "renderShadowComparison",
        "fabricate shadow comparison ready",
    ),
    "ambient-occlusion": (
        "renderAoComparison",
        "fabricate ao comparison ready",
    ),
    "blended-transparency": (
        "renderBlendedTransparency",
        "fabricate blended transparency ready",
    ),
    "post-stack-vignette": (
        "renderPostStackVignette",
        "fabricate post stack vignette ready",
    ),
    "toy-car-gltf": (
        "renderToyCarGltf",
        "fabricate toy car gltf ready",
    ),
    "multi-light-skybox": (
        "renderMultiLightSkybox",
        "fabricate multi light skybox ready",
    ),
    "tonemap-bloom": (
        "renderToneMapBloom",
        "fabricate tonemap bloom ready",
    ),
    # Not a StandardRenderer scene: this delegates to StackSLGalleryHarness and
    # writes the whole StackSL gallery (many PNGs) in one graphics context. The
    # method still lives on StandardRendererDocsHarness so it flows through the
    # existing fabricate-scene generator unchanged.
    "stacksl-gallery": (
        "renderStackSLGallery",
        "stacksl gallery ready",
    ),
    "stacksl-animations": (
        "renderStackSLAnimations",
        "stacksl animations ready",
    ),
    # RectSL twins of the StackSL scenes: delegate to RectSLGalleryHarness and
    # write the whole RectSL gallery (stills) / animations (mp4s) in one graphics
    # context. See the note above on stacksl-gallery.
    "rectsl-gallery": (
        "renderRectSLGallery",
        "rectsl gallery ready",
    ),
    "rectsl-animations": (
        "renderRectSLAnimations",
        "rectsl animations ready",
    ),
    "rectsl-fit": (
        "renderRectSLFit",
        "rectsl fit ready",
    ),
}

HARNESS_RELATIVE_PATH = "src/jvmMain/kotlin/afield/four/fabricated/StandardRendererDocsHarness.kt"

_HARNESS_METHOD_RE = re.compile(r"\bfun\s+(render[A-Za-z0-9_]*)\s*\(")


def validate_scenes_against_harness(repo_root: Path) -> None:
    """Fail fast if any scene references a render method the harness lacks.

    Catches typos (e.g. a new scene added here or a fence renamed against a
    method that does not exist) with a clear message before the docs build
    reaches the far slower capture stage. Silently returns when the harness
    source is absent (e.g. a partial checkout) — that is not this map's problem.
    """
    harness_path = (repo_root / HARNESS_RELATIVE_PATH).resolve()
    try:
        source = harness_path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return
    available = set(_HARNESS_METHOD_RE.findall(source))
    missing = {
        scene: method
        for scene, (method, _ready) in FABRICATE_DOCS_SCENES.items()
        if method not in available
    }
    if missing:
        details = ", ".join(f"{scene!r} -> {method}()" for scene, method in sorted(missing.items()))
        raise RuntimeError(
            "fabricate docs scene(s) reference StandardRendererDocsHarness methods that do not "
            f"exist in {HARNESS_RELATIVE_PATH}: {details}. Update FABRICATE_DOCS_SCENES in "
            "public/fabricate_docs_scenes.py or add the missing render method to the harness."
        )
