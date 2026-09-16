package afield.examples.chain

import afield.manifold.Solid
import afield.manifold.toSolid
import afield.onnx.DepthAnything
import afield.stdlib.Img
import afield.stdlib.TriMesh
import afield.stdlib.gaussianBlur
import afield.stdlib.imgFromFile
import afield.stdlib.AxisAlignedBoundingBox3D
import afield.stdlib.Vec3
import afield.stdlib.reliefMesh
import afield.stdlib.resize
import afield.stdlib.saveStl
import afield.stdlib.scaleBy
import afield.stdlib.toReliefMesh
import afield.stdlib.topology
import afield.stdlib.volume
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/*
FIELD_TEST
id: chain.photo-to-solid
runner: jvm-main
entrypoint: afield.examples.chain.PhotoToSolidChain
tags: [smoke, chain, onnx, ml, depth, manifold, mesh, cpu, network]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "photo-to-solid chain passed"
*/
/**
 * A photograph becomes a printable solid, and every hop owes the next one a number.
 *
 * The route is monocular depth ([DepthAnything], ONNX) into a depth map, the map into a relief
 * plaque ([Img.toReliefMesh]), the plaque into an exact solid ([TriMesh.toSolid], Manifold over the
 * C ABI), and the solid through a Boolean against a picture frame. Four families that do not import
 * one another, and the point of running them in a line is that a wrong assumption anywhere shows up
 * as a violated identity rather than as an ugly picture.
 *
 * The oracles here are identities, not thresholds:
 *
 *  * **the plaque is closed, exactly.** `χ = 2`, no boundary loops, and the vertex and triangle
 *    counts match [reliefMesh]'s closed form. This is the hop that did not exist before; see
 *    [reliefMesh] for why capping the heightfield's own rim is a different shape.
 *  * **two independent constructions of the same solid agree on its volume.** The grid
 *    triangulation and a marched level set of the same depth field are written by different code in
 *    different subsystems, and they are compared against each other rather than against a constant.
 *  * **inclusion–exclusion survives the Boolean.** `vol(A ∪ B) + vol(A ∩ B) = vol(A) + vol(B)`
 *    holds for any two solids, so it is checked rather than "the union got bigger".
 *  * **the frame's genus is 1 and the mounted plaque's is 0** — the plaque plugs the frame's window,
 *    and that is a statement about holes that a volume cannot make.
 *  * **`g = 1 − k` for k disjoint parts**, re-measured here on plaques rather than on the cubes the
 *    claim was first made about.
 *
 * Whatever the model predicts about a sofa, all five hold. That is the useful property: the geometry
 * is right or wrong independently of whether the depth is any good.
 */
object PhotoToSolidChain {

    /** A real photograph that already ships in the repository: a Vision Pro passthrough frame. */
    const val PHOTO = "public/docs/assets/vision/fline-viewer.jpg"

    /** Plaque footprint along x, in Field units. The depth map's aspect ratio sets the other one. */
    private const val WIDTH = 2.0

    /** How far the relief stands off the base plane at its nearest point. */
    private const val RELIEF = 0.35

    /** How thick the plaque is where the image is black — the number that decides it survives print. */
    private const val BASE = 0.12

    private var failures = 0

    private fun check(label: String, ok: Boolean, detail: String) {
        if (ok) {
            println("  ok   $label — $detail")
        } else {
            failures++
            println("  FAIL $label — $detail")
        }
    }

    private fun near(a: Double, b: Double, tolerance: Double) = abs(a - b) <= tolerance

    /**
     * Both ONNX models want tightly packed RGBA8 and an [Img] is interleaved floats — this is the
     * whole adapter, and it is the same one `onnx.md` shows. Nothing in `afield.onnx` knows `Img`.
     */
    fun toRgba8(img: Img): ByteBuffer {
        val out = ByteBuffer.allocateDirect(img.width * img.height * 4).order(ByteOrder.nativeOrder())
        for (p in 0 until img.pixelCount) {
            for (c in 0 until 3) {
                val v = (img.data[p * img.channels + c].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                out.put(p * 4 + c, v.toByte())
            }
            out.put(p * 4 + 3, 255.toByte())
        }
        return out
    }

    /**
     * The depth map, as a single-channel [Img] in `[0, 1]` with **near = high**.
     *
     * `adaptation = 1f` normalises against this frame's own range, which is what a single still
     * wants. The blur is not cosmetic: the model's output is piecewise-smooth with hard depth
     * discontinuities, and an unsmoothed discontinuity becomes a vertical cliff in the plaque — legal
     * geometry, but a cliff a printer cannot bridge and a Boolean has to retriangulate around.
     *
     * @param longestSide passed to [DepthAnything.sizeFor], which snaps it to a multiple of 14.
     */
    fun depthMapOf(photo: Img, longestSide: Int = 266, blurSigma: Double = 1.2): Pair<Img, DepthAnything> {
        val model = DepthAnything.forSource(photo.width, photo.height, longestSide)
        model.adaptation = 1f
        val stats = model.infer(toRgba8(photo), photo.width, photo.height)
        println(
            "  depth ${model.width}x${model.height} on ${model.activeProvider}, " +
                "${model.lastInferenceMs}ms, raw window [%.4f, %.4f]".format(stats.rawMin, stats.rawMax)
        )
        // depthRaw is in the model's own arbitrary units; rescale to [0,1] against the window the
        // model just reported, so the plaque's height has a defined meaning.
        val span = (stats.rawMax - stats.rawMin).coerceAtLeast(1e-6f)
        val raw = Img(model.width, model.height, 1, FloatArray(model.width * model.height) {
            ((model.depthRaw.get(it) - stats.rawMin) / span).coerceIn(0f, 1f)
        })
        return raw.gaussianBlur(blurSigma) to model
    }

    /**
     * A picture frame: a slab with a rectangular window, which is a torus and therefore genus 1.
     *
     * Sized to overlap the plaque's border by [overlap] on every side, and only as tall as the base
     * slab — so the plaque's floor blocks the window and the union is genus 0.
     */
    fun frameAround(footprintX: Double, footprintZ: Double, overlap: Double = 0.08): Solid {
        val outerX = footprintX + overlap
        val outerZ = footprintZ + overlap
        val outer = Solid.cube(outerX, BASE, outerZ, center = true)
        val window = Solid.cube(footprintX - overlap, BASE * 4, footprintZ - overlap, center = true)
        return (outer - window).translated(0.0, -BASE / 2, 0.0)
    }

    /**
     * The same plaque as [Img.toReliefMesh] builds, but marched out of a field instead — the
     * independent second construction the volume agreement is checked against.
     *
     * `parallel = false` on purpose for the volume agreement, and [reproducibilityCensus] measures
     * whether that caution is warranted on *this* field rather than repeating the recorded claim.
     *
     * @param floor the plane the solid is cut off at. `-BASE` is the plaque. Raise it and the plaque
     *   erodes into islands — only the parts of the photograph nearer than [floor] survive — which is
     *   the knob that turns one component into dozens without changing anything else.
     */
    fun marchedPlaque(
        depth: Img,
        footprintZ: Double,
        edgeLength: Double,
        parallel: Boolean,
        floor: Double = -BASE,
    ): Solid {
        val halfX = WIDTH / 2
        val halfZ = footprintZ / 2
        val margin = edgeLength * 2
        val bounds = AxisAlignedBoundingBox3D(
            Vec3((-halfX - margin).toFloat(), (floor - margin).toFloat(), (-halfZ - margin).toFloat()),
            Vec3((halfX + margin).toFloat(), (RELIEF + margin).toFloat(), (halfZ + margin).toFloat()),
        )
        // Positive inside, which is Manifold's convention for a level set: below the relief surface,
        // above the floor, and inside the footprint, all at once.
        return Solid.levelSet(bounds, edgeLength, parallel = parallel) { x, y, z ->
            val u = ((x / WIDTH) + 0.5).coerceIn(0.0, 1.0)
            val v = ((z / footprintZ) + 0.5).coerceIn(0.0, 1.0)
            val px = (u * (depth.width - 1)).toInt().coerceIn(0, depth.width - 1)
            val py = (v * (depth.height - 1)).toInt().coerceIn(0, depth.height - 1)
            val surface = depth.at(px, py) * RELIEF
            min(
                min(surface - y, y - floor),
                min(halfX - abs(x), halfZ - abs(z)),
            )
        }
    }

    /**
     * [runs] runs of [Solid.levelSet] each way, reported as the number of *distinct* answers.
     *
     * The recorded claim is that `parallel = true` is not reproducible and `parallel = false` is
     * identical to the last digit — first seen on a field that marched to 46, 47 or 48 components.
     * This re-runs it on a depth map, at several [floor]s, because a component count near a
     * threshold is the obvious candidate for what actually varies: the number is the *last* thing a
     * marcher decides, and the volume is a sum that does not care what order it was added in.
     */
    fun reproducibilityCensus(
        depth: Img,
        footprintZ: Double,
        edgeLength: Double,
        floor: Double,
        runs: Int = 8,
    ): String {
        fun census(parallel: Boolean): Set<String> = (1..runs).map {
            Solid.scope {
                val s = marchedPlaque(depth, footprintZ, edgeLength, parallel, floor)
                "tri=${s.triangleCount} parts=${s.decompose().size} vol=%.12f".format(s.volume)
            }
        }.toSet()
        val par = census(true)
        val seq = census(false)
        println("  floor=%+.2f edgeLength=%.4f".format(floor, edgeLength))
        println("    parallel=true : $runs runs, ${par.size} distinct")
        par.forEach { println("        $it") }
        println("    parallel=false: $runs runs, ${seq.size} distinct")
        seq.forEach { println("        $it") }
        return "parallel=${par.size}/$runs distinct, sequential=${seq.size}/$runs distinct"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val photoFile = File(PHOTO)
        require(photoFile.isFile) { "run this from the repository root; $PHOTO is not there" }
        println("photo-to-solid chain: $PHOTO")

        // ---- hop 1: a photograph becomes a depth map ------------------------------------------
        val photo = imgFromFile(PHOTO, 3).scaleBy(0.45)
        println("  photo ${photo.width}x${photo.height}, ${photo.channels} channels")
        val (depth, model) = depthMapOf(photo)
        check(
            "depth map shape", depth.width == model.width && depth.height == model.height && depth.channels == 1,
            "${depth.width}x${depth.height}x${depth.channels} from a ${photo.width}x${photo.height} photograph",
        )
        check(
            "depth is a multiple of 14", model.width % 14 == 0 && model.height % 14 == 0,
            "${model.width}x${model.height}; the backbone is ViT-S/14",
        )

        // ---- hop 2: the depth map becomes a closed relief plaque ------------------------------
        // One vertex per pixel at 266 is 70k grid points and a Boolean over that is minutes, so the
        // map is resampled first. The resample is where the resolution decision belongs: it is
        // visible, it is one number, and it happens before any topology exists to get wrong.
        val grid = 128
        val small = depth.resize(grid, (grid * depth.height) / depth.width)
        val footprintZ = WIDTH * small.height / small.width
        val plaque = small.toReliefMesh(scale = RELIEF, width = WIDTH, baseThickness = BASE)
        val topology = plaque.topology()
        val nx = small.width - 1
        val nz = small.height - 1
        val perimeter = 2 * nx + 2 * nz
        check(
            "plaque is closed", topology.isWatertight && topology.isManifold && topology.eulerCharacteristic == 2,
            "watertight=${topology.isWatertight} manifold=${topology.isManifold} χ=${topology.eulerCharacteristic} " +
                "loops=${topology.boundaryLoops.size}",
        )
        check(
            "plaque counts are the closed form",
            plaque.vertexCount == (nx + 1) * (nz + 1) + perimeter + 1 &&
                plaque.triangleCount == 2 * nx * nz + 3 * perimeter,
            "${plaque.vertexCount} vertices, ${plaque.triangleCount} triangles at ${nx}x$nz, P=$perimeter",
        )

        Solid.scope {
            // ---- hop 3: the plaque becomes an exact solid -------------------------------------
            val solid = plaque.toSolid().checked("the plaque")
            check(
                "Manifold and the mesh agree on the volume",
                near(solid.volume, plaque.volume(), 1e-9),
                "Manifold %.9f, TriMesh.volume %.9f".format(solid.volume, plaque.volume()),
            )
            check(
                "the solid is one genus-0 part", solid.genus == 0 && solid.decompose().size == 1,
                "genus=${solid.genus}, parts=${solid.decompose().size}",
            )

            // Marching the same depth field is an independent construction of the same object, so
            // the two volumes are compared against each other. The tolerance is the marcher's, not
            // a fudge: a level set resolves the surface to about its edge length, and the relief's
            // slope times that edge length is the volume a cell can be wrong by.
            val edgeLength = WIDTH / grid
            val marched = marchedPlaque(small, footprintZ, edgeLength, parallel = false)
            val agreement = abs(marched.volume - solid.volume) / solid.volume
            check(
                "an independently marched plaque has the same volume",
                agreement < 0.02,
                "grid %.9f, marched %.9f, %.3f%% apart at edgeLength %.4f"
                    .format(solid.volume, marched.volume, agreement * 100, edgeLength),
            )

            // ---- hop 4: the Boolean --------------------------------------------------------
            val frame = frameAround(WIDTH, footprintZ).checked("the frame")
            check(
                "a picture frame is genus 1", frame.genus == 1 && frame.decompose().size == 1,
                "genus=${frame.genus}, parts=${frame.decompose().size} — a slab with a window is a torus",
            )
            val mounted = (solid + frame).checked("the mounted plaque")
            val overlap = (solid and frame).checked("the overlap")
            check(
                "inclusion–exclusion survives the union",
                near(mounted.volume + overlap.volume, solid.volume + frame.volume, 1e-7),
                "∪ %.9f + ∩ %.9f = %.9f against %.9f + %.9f = %.9f".format(
                    mounted.volume, overlap.volume, mounted.volume + overlap.volume,
                    solid.volume, frame.volume, solid.volume + frame.volume,
                ),
            )
            check(
                "the plaque plugs the frame's window",
                mounted.genus == 0 && mounted.decompose().size == 1,
                "the frame was genus 1, the union is genus ${mounted.genus} in ${mounted.decompose().size} part",
            )
            val cut = (frame - solid).checked("the frame minus the plaque")
            check(
                "difference is union minus intersection, in volume",
                near(cut.volume, frame.volume - overlap.volume, 1e-7),
                "%.9f against %.9f - %.9f = %.9f".format(
                    cut.volume, frame.volume, overlap.volume, frame.volume - overlap.volume,
                ),
            )

            // ---- the census: g = 1 - k, re-measured on plaques -----------------------------
            val spread = WIDTH * 1.5
            val census = (1..4).map { k ->
                Solid.scope {
                    val parts = (0 until k).map { solid.translated(it * spread, 0.0, 0.0) }
                    val composed = Solid.compose(parts)
                    Triple(k, composed.genus, composed.decompose().size)
                }
            }
            check(
                "g = 1 - k for k disjoint parts",
                census.all { (k, g, parts) -> g == 1 - k && parts == k },
                census.joinToString("; ") { (k, g, parts) -> "k=$k → genus $g, $parts parts" },
            )

            // ---- printable ----------------------------------------------------------------
            val stl = File(System.getProperty("java.io.tmpdir"), "photo-to-solid-plaque.stl")
            mounted.toTriMesh(normals = true).saveStl(stl)
            check(
                "the mounted plaque writes an STL", stl.length() > 0,
                "${stl.length()} bytes at ${stl.absolutePath}",
            )
        }

        // The recorded claim was first seen on a field that marched to 46, 47 or 48 components, so
        // testing it needs that regime and not just this photograph's one solid block. `floor` is
        // the knob: raise it and only the nearest parts of the picture survive, as islands. The
        // sharp (unblurred) map fragments far harder than the blurred one, so scan both and then
        // spend the runs where the component count is actually high.
        val sharp = depth.resize(small.width, small.height)
        val edge = WIDTH / 64.0
        println("levelSet component scan, one run each, to find the fragmented regime:")
        val regimes = mutableListOf<Triple<String, Img, Double>>()
        for ((label, field) in listOf("blurred" to small, "sharp" to sharp)) {
            for (floor in listOf(-BASE, 0.02, 0.06, 0.10, 0.14, 0.18)) {
                val parts = Solid.scope { marchedPlaque(field, footprintZ, edge, false, floor).decompose().size }
                println("  %-7s floor=%+.2f → %d component(s)".format(label, floor, parts))
                if (parts >= 2) regimes.add(Triple("$label floor=%+.2f".format(floor), field, floor))
            }
        }

        // Always include the un-eroded plaque, which is one solid block, alongside the fragmented
        // regimes. Comparing the two is the whole point: if the block reproduces and the fragments
        // do not, then "parallel is not reproducible" is a statement about the *field* and not about
        // the flag, and that is a different thing to have to remember.
        println("levelSet reproducibility, 8 runs each way:")
        val chosen = listOf(Triple("the plaque itself (one block)", small, -BASE)) + regimes.takeLast(2)
        val verdicts = chosen.map { (label, field, floor) ->
            label to reproducibilityCensus(field, footprintZ, edge, floor)
        }
        verdicts.forEach { (label, verdict) -> println("  %-30s → %s".format(label, verdict)) }

        if (failures == 0) println("photo-to-solid chain passed") else {
            println("photo-to-solid chain FAILED with $failures problem(s)")
            kotlin.system.exitProcess(1)
        }
    }
}
