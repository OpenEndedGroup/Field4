"""Pin the ray-tracing page to its harness without requiring Vulkan ray tracing.

Discovered by checkPythonTools (and therefore docsGates/check). These are source
contracts, not a claim that the shaders compile or render correctly. Mutation
fixtures exercise both sides of the contract using the real page and harness.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAGE = ROOT / "public/docs/ray_tracing_examples.md"
HARNESS = ROOT / "src/jvmMain/kotlin/afield/four/fabricated/RayTracingDocsHarness.kt"


def tokens(code):
    """Ignore GLSL comments and layout, but retain token boundaries and operators."""
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", code, flags=re.S)
    return re.findall(r"\w+|[^\w\s]", code)


def shader(source, name):
    found = re.findall(r'\bval\s+' + re.escape(name) + r'\s*=\s*"""(.*?)"""\.trimIndent\(\)', source, re.S)
    if len(found) != 1:
        raise AssertionError(f"harness must define exactly one literal shader {name}")
    return found[0]


def one(pattern, text, label):
    found = re.findall(pattern, text, re.S)
    if len(found) != 1:
        raise AssertionError(f"{label}: expected one match, found {len(found)}")
    return found[0]


def count(value):
    words = "zero one two three four five six seven eight nine ten".split()
    return int(value) if value.isdecimal() else words.index(value)


def check_contract(page, source):
    blocks = re.findall(r'^```diffstatic\s+\{data-lang=glsl\}\s*\n(.*?)^```', page, re.M | re.S)
    # These four are unabridged shader excerpts (the triangle raygen omits only
    # its #version/#extension preamble). The sphere examples explicitly simplify
    # the production shader and are deliberately not compared byte-for-byte.
    excerpts = {
        "UV_GRADIENT_RAYGEN": "imageStore(outputImage, px, vec4(uv, 0.2, 1.0))",
        "TRIANGLE_RAYGEN": "rayPayloadEXT vec3 payload",
        "TRIANGLE_CLOSESTHIT": "float w =",
        "SHADER_TILES_CLOSESTHIT": "float tileScale =",
    }
    for name, witness in excerpts.items():
        found = [block for block in blocks if witness in block]
        if len(found) != 1:
            raise AssertionError(f"{name}: missing or duplicated published excerpt")
        excerpt, actual = tokens(found[0]), tokens(shader(source, name))
        if not any(actual[i:i + len(excerpt)] == excerpt for i in range(len(actual) - len(excerpt) + 1)):
            raise AssertionError(f"{name}: published GLSL has drifted from the harness")

    mirror = shader(source, "MIRROR_BALLS_RAYGEN")
    summary = [block for block in blocks if "/* origin, right, up, forward, fov, aspect */" in block]
    if len(summary) != 1:
        raise AssertionError("camera bindings: missing or duplicated binding summary")
    binding = r"layout\([^)]*\)\s+uniform\s+(?:image2D\s+outputImage|accelerationStructureEXT\s+topLevelAS|RayCamera)"
    declared = re.findall(binding, summary[0])
    actual = re.findall(binding, mirror)
    if len(declared) != 3 or list(map(tokens, declared)) != list(map(tokens, actual)):
        raise AssertionError("camera bindings: published bindings differ from MIRROR_BALLS_RAYGEN")

    bounces = int(one(r"private const val MIRROR_BALLS_REFLECTION_BOUNCES\s*=\s*(\d+)", source, "bounce constant"))
    one(r"payload\.bouncesLeft\s*=\s*(\$MIRROR_BALLS_REFLECTION_BOUNCES)\s*;", mirror, "raygen bounce initializer")
    # Both the still and live scene must keep the relationship the prose teaches.
    depths = re.findall(r"maxRayRecursionDepth\s*=\s*([^,\n]+)", source)
    if len(depths) != 2 or any(tokens(expr) != tokens("MIRROR_BALLS_REFLECTION_BOUNCES + 1") for expr in depths):
        raise AssertionError("harness recursion depth must be reflection bounces + primary ray in both scenes")
    depth = bounces + 1
    published_depth = int(one(r"maxRayRecursionDepth\s*=\s*(\d+)", page, "published recursion parameter"))
    if published_depth != depth:
        raise AssertionError(f"published recursion parameter: {published_depth}, harness requires {depth}")
    claims = (
        (r"payload with (\w+) bounces in hand", bounces, "payload bounce prose"),
        (r"requests recursion depth (\d+)", depth, "recursion depth prose"),
        (r"recursion depth \d+ — (\w+) reflections plus the primary ray", bounces, "reflection prose"),
        (r"procedural sphere hit group, and (\w+) reflected bounces", bounces, "scene summary"),
    )
    for pattern, expected, label in claims:
        value = count(one(pattern, page, label))
        if value != expected:
            raise AssertionError(f"{label}: {value}, harness requires {expected}")


class RayTracingDocsContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.page = PAGE.read_text()
        cls.source = HARNESS.read_text()

    def test_published_excerpts_and_numbers_match_harness(self):
        check_contract(self.page, self.source)

    def test_original_camera_regression_is_rejected(self):
        stale = self.page.replace("vec3(0.0, 2.0, -2.0)", "vec3(0.0, 0.0, -2.0)")
        self.assertNotEqual(stale, self.page)
        with self.assertRaisesRegex(AssertionError, "TRIANGLE_RAYGEN"):
            check_contract(stale, self.source)

    def test_changed_harness_camera_is_rejected(self):
        stale = self.source.replace("vec3(0.0, 2.0, -2.0)", "vec3(0.0, 3.0, -2.0)")
        self.assertNotEqual(stale, self.source)
        with self.assertRaisesRegex(AssertionError, "TRIANGLE_RAYGEN"):
            check_contract(self.page, stale)

    def test_changed_bounce_constant_is_rejected(self):
        stale = self.source.replace("MIRROR_BALLS_REFLECTION_BOUNCES = 2", "MIRROR_BALLS_REFLECTION_BOUNCES = 3")
        self.assertNotEqual(stale, self.source)
        with self.assertRaisesRegex(AssertionError, "recursion parameter"):
            check_contract(self.page, stale)

    def test_each_published_bounce_claim_is_checked(self):
        for before, after in (
            ("maxRayRecursionDepth = 3", "maxRayRecursionDepth = 2"),
            ("with two bounces in hand", "with one bounces in hand"),
            ("requests recursion depth 3", "requests recursion depth 2"),
            ("— two reflections plus", "— one reflections plus"),
            ("and two reflected bounces", "and one reflected bounces"),
        ):
            with self.subTest(claim=before):
                stale = self.page.replace(before, after)
                self.assertNotEqual(stale, self.page)
                with self.assertRaises(AssertionError):
                    check_contract(stale, self.source)

    def test_missing_or_changed_camera_binding_is_rejected(self):
        line = next(line for line in self.page.splitlines() if "uniform RayCamera" in line)
        for replacement in ("", line.replace("binding = 2", "binding = 3")):
            with self.subTest(replacement=replacement), self.assertRaisesRegex(AssertionError, "camera bindings"):
                check_contract(self.page.replace(line, replacement), self.source)

    def test_changed_harness_binding_is_rejected(self):
        stale = self.source.replace("binding = 2) uniform RayCamera", "binding = 3) uniform RayCamera")
        self.assertNotEqual(stale, self.source)
        with self.assertRaisesRegex(AssertionError, "camera bindings"):
            check_contract(self.page, stale)

    def test_missing_excerpt_is_rejected(self):
        stale = re.sub(r'^```diffstatic\s+\{data-lang=glsl\}\s*\n.*?^```', '', self.page, flags=re.M | re.S)
        with self.assertRaisesRegex(AssertionError, "missing.*excerpt"):
            check_contract(stale, self.source)

    def test_harness_depth_relationship_and_initializer_are_checked(self):
        for before, after in (
            ("maxRayRecursionDepth = MIRROR_BALLS_REFLECTION_BOUNCES + 1", "maxRayRecursionDepth = 3"),
            ("payload.bouncesLeft = $MIRROR_BALLS_REFLECTION_BOUNCES", "payload.bouncesLeft = 2"),
        ):
            with self.subTest(source=before):
                stale = self.source.replace(before, after)
                self.assertNotEqual(stale, self.source)
                with self.assertRaises(AssertionError):
                    check_contract(self.page, stale)

    def test_whitespace_and_comments_do_not_change_shader_contract(self):
        source = self.source.replace("gl_LaunchIDEXT.xy", "gl_LaunchIDEXT /* pixel */ . xy")
        check_contract(self.page, source)


if __name__ == "__main__":
    unittest.main()
