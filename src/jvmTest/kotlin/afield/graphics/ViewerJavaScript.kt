package afield.graphics

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import java.io.File

/**
 * Runs a marked region of `threejs_viewer.html`'s JavaScript, as text, inside the JVM.
 *
 * The three.js viewer is not only a renderer, it is a **second implementation**. `editable = true`
 * re-triangulates client-side, because a dragged point must not wait for a server round-trip to
 * re-fill, so the coplanar-grouping rule in `afield.graphics.PlanarTessellation` is transliterated
 * into `tessellateCoplanarGroups`. Written twice, deliberately — but until now not *tested* twice:
 * `ThreeJsEditableFillParityTest` computes both sides of its assertion in Kotlin, so the JavaScript
 * never ran, and the two defects the human found within hours of that seam landing
 * (`d3d81fee1`, `bf65e10e3`) were both in its neighbourhood.
 *
 * This is the seam that makes the JavaScript executable from a test, and it is deliberately generic:
 * the *next* subsystem that duplicates logic into the viewer marks a region and gets a differential
 * test for free.
 *
 * ### How a region is marked
 *
 * Two comments in the HTML, and nothing else:
 *
 * ```
 * // [viewer-region planar-tessellation] BEGIN
 * …
 * // [viewer-region planar-tessellation] END
 * ```
 *
 * [regionSource] returns the text between them and **throws if either sentinel is missing**, so the
 * seam cannot be quietly deleted: removing the markers turns a passing test into a failing one rather
 * than into a vacuous one, which was exactly the failure mode of the test this replaces.
 *
 * ### What the region may use
 *
 * No DOM, and no `THREE` beyond `Vector2` and `ShapeUtils.triangulateShape`. Both rules are asserted
 * by [ViewerJavaScriptRegionTest] rather than merely requested, because a region that stops being
 * liftable silently stops being tested.
 *
 * ### Why `ShapeUtils.triangulateShape` is the Kotlin triangulator
 *
 * The shim's `triangulateShape` is [FloatEarcut] — the same triangulator the Kotlin oracle uses.
 * That is a *stronger* comparison than the real thing would give, not a weaker one:
 *
 *  * the duplicated logic is the clustering, the projected frame, the hemisphere, the nesting and the
 *    `refs` index bookkeeping. Earcut is upstream on both sides and is not what drifts;
 *  * holding the triangulator fixed makes the two outputs comparable **triangle for triangle**, so
 *    the assertion is exact rather than a triangle count;
 *  * the shim can *check* a claim the viewer states in a comment and nothing verified — that the
 *    rings are pre-sanitised, so three.js's `removeDupEndPts` finds nothing to pop and cannot shift
 *    `refs` indices out from under the caller. [Bridge.earcut] asserts it on every ring it is handed.
 *
 * What this does not cover, said once so it is not oversold: three's `Earcut` is float64 where
 * [FloatEarcut] is float32, and `triangulateShape` does a little bookkeeping of its own. Those stay
 * covered by the real browser captures in
 * `dev_docs/agents/artifacts/tessellate_facing/multi_plane_fill_demo/`.
 *
 * Note that three.js *is* now loadable in-repo — `src/commonMain/resources/vendor/three/0.184.0/`,
 * vendored when the mesh examples moved onto this viewer and a CDN stopped being an acceptable
 * dependency for ~68 docs captures. So a shim using the real `THREE.ShapeUtils` has become possible.
 * It is deliberately still not what runs here, for the three reasons above: substituting the real one
 * would weaken the assertion from triangle-for-triangle to a count, not strengthen it.
 *
 * GraalJS rather than node because `org.graalvm.polyglot:js` is already a first-party dependency
 * (`build.gradle.kts:979`), `afield.morpho.MorphoRuntime` is the established idiom for it, and a
 * subprocess would buy nothing except a `node` on the PATH to go missing.
 */
object ViewerJavaScript {

    /** The shipped viewer, read from the source tree the way `Server.kt:4453` serves it. */
    val VIEWER: File = File("src/commonMain/resources/threejs_viewer.html")

    /**
     * The no-op perturbation.
     *
     * Distinguishable *by identity*, so [Session.open] can tell "I meant to run the shipped source"
     * from "my rewrite matched nothing" — which is the difference between a counterfactual and a
     * vacuous test.
     */
    val UNPERTURBED: (String) -> String = { it }

    /**
     * The text between `// [viewer-region <name>] BEGIN` and `// [viewer-region <name>] END`.
     *
     * @throws IllegalStateException if either sentinel is absent, or if there is more than one of
     * either — a duplicated marker means the region is ambiguous, and silently taking the first would
     * be another test that guards nothing.
     */
    fun regionSource(name: String, file: File = VIEWER): String {
        require(file.isFile) { "no viewer at ${file.absolutePath} — run this test from the project root" }
        val text = file.readText()
        fun only(marker: String): Int {
            val first = text.indexOf(marker)
            check(first >= 0) {
                "`$marker` is missing from ${file.path}. The region is executed under test by " +
                        "ViewerJavaScript; if it moved, move the sentinel with it."
            }
            check(text.indexOf(marker, first + 1) < 0) { "`$marker` appears more than once in ${file.path}" }
            return first
        }
        val begin = only("// [viewer-region $name] BEGIN")
        val end = only("// [viewer-region $name] END")
        check(begin < end) { "the `$name` sentinels are the wrong way round in ${file.path}" }
        return text.substring(text.indexOf('\n', begin) + 1, end)
    }

    /**
     * A region's source with its comments removed, for guards that ask "does this *reach for* X".
     *
     * Added 2026-08-11 because the `inspector-explode` region explains itself by quoting the human's
     * workspace document, and the word "document" in a sentence is not a DOM access. The alternative
     * was to write the comments around the guard, which would have made a *prose* rule out of a
     * *behaviour* rule — and these regions carry a lot of prose on purpose.
     *
     * Deliberately a simple stripper: block comments, and `//` to end of line, with no string- or
     * regex-literal awareness. That direction is safe for a guard — a naive stripper can only ever
     * remove *more* than it should, never less, so it cannot hide a `document.getElementById` that a
     * careful one would find. [ViewerJavaScriptRegionTest.aRealDomAccessIsStillCaughtAfterCommentsAreStripped]
     * pins that claim rather than leaving it as an argument.
     */
    fun withoutComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    /**
     * The host side of the shim: the one thing the guest cannot do for itself.
     *
     * Public members are reachable from the guest because the context is built with
     * [HostAccess.ALL]; the annotations are here to say which ones are the contract.
     */
    class Bridge {
        /**
         * `THREE.ShapeUtils.triangulateShape`, backed by [FloatEarcut].
         *
         * @param coordinates the contour then each hole, flattened to `[x0, y0, x1, y1, …]`.
         * @param holeStarts the *vertex* index each hole begins at, which is earcut's own convention.
         * @return flat triples of vertex indices, exposed to the guest as a JS array-like.
         */
        @HostAccess.Export
        fun earcut(coordinates: Value, holeStarts: Value): IntArray {
            val flat = FloatArray(coordinates.arraySize.toInt()) {
                coordinates.getArrayElement(it.toLong()).asDouble().toFloat()
            }
            val holes = IntArray(holeStarts.arraySize.toInt()) {
                holeStarts.getArrayElement(it.toLong()).asInt()
            }

            // The viewer claims (`threejs_viewer.html`, `fillCoplanarGroup`) that the rings are
            // pre-sanitised, so three.js's `removeDupEndPts` finds nothing to pop and cannot shift
            // the `refs` indices. `removeDupEndPts` pops on an *exact* Vector2 equality, so that is
            // the test. Nothing checked it before; a repeated final point would silently misindex
            // every triangle of the ring that carried it.
            val bounds = (listOf(0) + holes.toList() + listOf(flat.size / 2)).zipWithNext()
            bounds.forEach { (from, to) ->
                if (to - from > 2) {
                    val sameX = flat[from * 2] == flat[(to - 1) * 2]
                    val sameY = flat[from * 2 + 1] == flat[(to - 1) * 2 + 1]
                    check(!(sameX && sameY)) {
                        "ring [$from, $to) repeats its first point as its last, so three.js's " +
                                "removeDupEndPts would pop it and shift every `refs` index after it"
                    }
                }
            }

            return FloatEarcut.earcut(flat, if (holes.isEmpty()) null else holes, 2).toIntArray()
        }
    }

    /** The guest program: a `THREE` shim, then the region verbatim, then its exports. */
    private fun program(region: String, exports: List<String>): String = """
        (function (__bridge) {
            'use strict';
            const THREE = {
                Vector2: function (x, y) { this.x = x || 0; this.y = y || 0; },
                ShapeUtils: {
                    triangulateShape: function (contour, holes) {
                        const coordinates = [];
                        const holeStarts = [];
                        for (let i = 0; i < contour.length; i += 1) {
                            coordinates.push(contour[i].x, contour[i].y);
                        }
                        const holeList = holes || [];
                        for (let h = 0; h < holeList.length; h += 1) {
                            holeStarts.push(coordinates.length / 2);
                            for (let i = 0; i < holeList[h].length; i += 1) {
                                coordinates.push(holeList[h][i].x, holeList[h][i].y);
                            }
                        }
                        const flat = __bridge.earcut(coordinates, holeStarts);
                        const faces = [];
                        for (let i = 0; i + 2 < flat.length; i += 3) {
                            faces.push([flat[i], flat[i + 1], flat[i + 2]]);
                        }
                        return faces;
                    }
                }
            };

$region

            return {
                fromJson: function (json) { return JSON.parse(json); },
                ${exports.joinToString(",\n                ") { "$it: $it" }}
            };
        })
    """.trimIndent()

    /**
     * One GraalJS context with the region evaluated in it.
     *
     * @param perturbation applied to the region's *text* before evaluation. This is how the
     * counterfactuals are measured — the pre-fix fan, the mirrored projection pair — without a broken
     * viewer ever being committed. [Session.open] refuses a perturbation that did not change the
     * source, so a counterfactual test cannot pass by silently rewriting nothing.
     */
    class Session private constructor(
        private val context: Context,
        private val exports: Value,
    ) : AutoCloseable {

        /** `JSON.parse`, so a Kotlin caller can hand the guest a real JS array rather than a host list. */
        fun fromJson(json: String): Value = exports.getMember("fromJson").execute(json)

        /** An exported member of the region — a function, or one of the tolerance constants. */
        fun member(name: String): Value =
            checkNotNull(exports.getMember(name)) { "the region does not export `$name`" }

        /** Call an exported function of the region. */
        fun call(name: String, vararg arguments: Any?): Value = member(name).execute(*arguments)

        override fun close() = context.close()

        companion object {
            fun open(
                regionName: String = "planar-tessellation",
                exports: List<String> = DEFAULT_EXPORTS,
                perturbation: (String) -> String = UNPERTURBED,
            ): Session {
                val original = regionSource(regionName)
                val region = perturbation(original)
                check(region != original || perturbation === UNPERTURBED) {
                    "the perturbation left the `$regionName` region unchanged, so whatever it was " +
                            "meant to break has moved. A counterfactual that rewrites nothing " +
                            "measures nothing."
                }
                val context = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.ALL)
                    .build()
                return try {
                    val factory = context.eval("js", program(region, exports))
                    Session(context, factory.execute(Bridge()))
                } catch (failure: Throwable) {
                    context.close()
                    throw failure
                }
            }
        }
    }

    /**
     * Every marked region, and what each one exports.
     *
     * Kept here rather than only in the tests so that the *inventory* is one thing: adding a region
     * means adding a line here, and [ViewerJavaScriptRegionTest] then holds the new region to the same
     * rules as the old ones — found, unique, liftable, evaluable — without anyone having to remember to
     * write those four tests again.
     */
    val KNOWN_REGIONS: Map<String, List<String>> = mapOf(
        "panel-pointer" to listOf("panelPointerAt"),
        "planar-tessellation" to listOf(
            "COPLANAR_SINE_OF_ANGLE",
            "COPLANAR_OFFSET_FRACTION",
            "RING_SANITIZE_EPS",
            "sanitizeRing3D",
            "ringNewellNormal",
            "tessellateCoplanarGroups",
        ),
        "cubic-sampling" to listOf("EDGE_SAMPLES", "cubicPoint", "sampleCubic"),
        "fline-hit-picking" to listOf(
            "FLINE_PICK_CUBIC_SAMPLES",
            "flinePickSegmentAt",
            "flinePickNearestOnSegment",
            "flinePickFaceFrame",
            "flinePickToPlane",
            "flinePickPointInPolygon",
            "flinePickFaceCoords",
            "flinePickClassify",
        ),
        "inspector-measure" to listOf(
            "MEASURE_EPS",
            "measureElement",
            "measureEdges",
            "measureNearestOnSegment",
            "measurePolygonFrame",
            "measureNearestOnElement",
            "measureSegmentSegment",
            "measurePierce",
            "measureClosestApproach",
            "measureAnnotation",
            "measureFormat",
        ),
        "inspector-explode" to listOf(
            "EXPLODE_EPS",
            "explodeBoundsCentre",
            "explodeGroupCentroids",
            "explodeAxisRanks",
            "explodeOffsets",
            "explodeApply",
        ),
        "interactive-entry" to listOf(
            "INTERACTIVE_ENTRY_FIELDS",
            "parseFLineTopology",
            "verticesForLine",
            "interactiveEntry",
            "surfaceEntry",
        ),
        "surface-hit-picking" to listOf(
            "SURFACE_PICK_EPS",
            "SURFACE_PICK_SNAP_FRACTION",
            "surfacePickTriangle",
            "surfacePickVertexIndices",
            "surfacePickBarycentric",
            "surfacePickNearestOnEdge",
            "surfacePickLongestEdge",
            "surfacePickTolerance",
            "surfacePickFrontFacing",
            "surfacePickClassify",
            "surfacePickElementPoints",
        ),
        "inspector-section" to listOf(
            "SECTION_EPS",
            "sectionPlane",
            "sectionSignedDistance",
            "sectionCutTriangles",
            "sectionCutSegments",
            "sectionCutLength",
        ),
        "camera-preset" to listOf("cameraPresetEye"),
        "pick-thresholds" to listOf(
            "PICK_LINE_THRESHOLD_MIN",
            "PICK_POINT_THRESHOLD_MIN",
            "PICK_LINE_THRESHOLD_FRACTION",
            "PICK_POINT_THRESHOLD_FRACTION",
            "pickThresholdDiagonal",
            "pickLineThreshold",
            "pickPointThreshold",
        ),
    )

    /**
     * The text the server actually serves for a region, which is not automatically the text on disk.
     *
     * `Server.kt`'s `/threejs` route responds with `filter(File("./src/commonMain/resources/threejs_viewer.html").readText())`,
     * and [afield.filter] is a `{{ … }}` templating pass. So "the differential tests run the shipped
     * source" is a claim with a condition attached — that no region contains a template placeholder —
     * and [ViewerJavaScriptRegionTest.theTextLiftedIsTheTextTheServerServes] asserts it rather than
     * assuming it. Today no placeholder appears anywhere in the viewer; the day one does, inside a
     * region, every differential test silently starts measuring a different program from the one a
     * browser runs.
     */
    fun servedRegionSource(name: String, file: File = VIEWER): String =
        afield.filter(regionSource(name, file))

    /** What the `planar-tessellation` region exports, in the order the viewer defines them. */
    val DEFAULT_EXPORTS: List<String> = listOf(
        "COPLANAR_SINE_OF_ANGLE",
        "COPLANAR_OFFSET_FRACTION",
        "RING_SANITIZE_EPS",
        "sanitizeRing3D",
        "ringNewellNormal",
        "tessellateCoplanarGroups",
    )

    /** Open the default region with no perturbation. */
    fun open(): Session = Session.open(perturbation = UNPERTURBED)

    /** Open the default region with its text rewritten, for a counterfactual. */
    fun perturbed(perturbation: (String) -> String): Session = Session.open(perturbation = perturbation)
}
