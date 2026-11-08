@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.automata

import afield.graphics.vulkan.util.Window
import afield.media.image.util.PngEncoder
import afield.simulation.particles.MlpActivation
import afield.simulation.particles.MlpWeights
import afield.simulation.particles.glsl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random

/**
 * A Growing Neural Cellular Automaton: a grid of cells, each holding 16 numbers, that grows a picture
 * from a single seed cell and grows it back after it is damaged.
 *
 * This is the model from Mordvintsev, Randazzo, Niklasson and Levin, *Growing Neural Cellular
 * Automata*, Distill 2020 (<https://distill.pub/2020/growing-ca>, CC-BY 4.0). Every cell runs the same
 * small network:
 *
 * - **State.** 16 channels per cell: red, green, blue, alpha (premultiplied), then 12 hidden channels
 *   the network is free to use for signalling.
 * - **Perception.** Each channel is seen through three fixed 3 x 3 filters, identity, Sobel-x and
 *   Sobel-y (each Sobel divided by 8), giving 48 numbers per cell, ordered identity[16], x[16], y[16].
 * - **Update.** A per-cell network 48 -> 128 (ReLU) -> 16 proposes a change that is *added* to the
 *   state, but only in a random half of the cells each step (the "fire rate"), so there is no global
 *   clock for the cells to rely on.
 * - **Alive masking.** A cell with no neighbour (itself included) whose alpha exceeds 0.1, before and
 *   after the update, is zeroed. Empty space stays empty.
 *
 * The network is an [MlpWeights], so the same weights drive [step] here and the GLSL returned by
 * [computeShader], which `MlpWeights.glsl` writes. Weights come from
 * `tools/nca/train_growing_nca.py` (PyTorch, BPTT with a sample pool and damage); [pretrained]
 * loads the ones that ship with Field.
 *
 * ```kotlin
 * val nca = GrowingNca.pretrained("flower")
 * repeat(200) { nca.step() }
 * display("main", nca.png(scale = 6))
 * ```
 */
class GrowingNca(
    /** The per-cell network: 48 inputs, one hidden layer of 128 ReLU units, 16 outputs. */
    val mlp: MlpWeights,
    /** Cells along each side. Need not be the size the rule was trained at. */
    val size: Int = 64,
    /** The probability that a cell updates on a given step. Training used 0.5. */
    var fireRate: Float = 0.5f,
    /** The source of the per-cell update mask. */
    val random: Random = Random(0),
) {
    init {
        require(mlp.inputs == PERCEPTION && mlp.outputs == CHANNELS) {
            "a growing NCA needs a $PERCEPTION -> ... -> $CHANNELS network, got $mlp"
        }
    }

    /** The cell state, `[y][x][channel]`, 16 floats per cell. */
    var state = FloatArray(size * size * CHANNELS)
        private set
    private var next = FloatArray(size * size * CHANNELS)
    private val perception = FloatArray(PERCEPTION)
    private val hiddenRow = FloatArray(mlp.hidden[0])
    private val alive = BooleanArray(size * size)

    /** How many steps have run since the last [seed]. */
    var steps = 0
        private set

    init {
        seed()
    }

    /** Clears the grid and plants one seed: alpha and every hidden channel set to 1 at ([x], [y]). */
    fun seed(x: Int = size / 2, y: Int = size / 2) {
        state.fill(0f)
        for (c in 3 until CHANNELS) state[(y * size + x) * CHANNELS + c] = 1f
        steps = 0
    }

    /** Zeroes every cell within [radius] cells of ([x], [y]) — the damage the rule was trained to repair. */
    fun damage(x: Double, y: Double, radius: Double) {
        for (j in 0 until size) for (i in 0 until size) {
            val dx = i + 0.5 - x
            val dy = j + 0.5 - y
            if (dx * dx + dy * dy < radius * radius) state.fill(0f, (j * size + i) * CHANNELS, (j * size + i + 1) * CHANNELS)
        }
    }

    private fun computeAlive(s: FloatArray) {
        for (y in 0 until size) for (x in 0 until size) {
            var a = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue
                if (s[(yy * size + xx) * CHANNELS + 3] > ALIVE_THRESHOLD) {
                    a = true
                    break@loop
                }
            }
            alive[y * size + x] = a
        }
    }

    /** Advances the automaton one step. */
    fun step() {
        val s = state
        val w = mlp.flat
        val nHidden = hiddenRow.size
        val w1 = mlp.weightOffset(0)
        val b1 = mlp.biasOffset(0)
        val w2 = mlp.weightOffset(1)
        val b2 = mlp.biasOffset(1)
        computeAlive(s)
        val preAlive = alive.copyOf()
        System.arraycopy(s, 0, next, 0, s.size)
        for (y in 0 until size) for (x in 0 until size) {
            if (random.nextFloat() > fireRate) continue
            // A dead cell with no live neighbour can only stay dead, so skip its network.
            if (!preAlive[y * size + x]) continue
            perceive(s, x, y)
            for (o in 0 until nHidden) {
                var sum = w[b1 + o]
                val row = w1 + o * PERCEPTION
                for (i in 0 until PERCEPTION) sum += w[row + i] * perception[i]
                hiddenRow[o] = if (sum > 0f) sum else 0f
            }
            val base = (y * size + x) * CHANNELS
            for (c in 0 until CHANNELS) {
                var sum = w[b2 + c]
                val row = w2 + c * nHidden
                for (h in 0 until nHidden) sum += w[row + h] * hiddenRow[h]
                next[base + c] += sum
            }
        }
        computeAlive(next)
        for (cell in 0 until size * size) if (!(preAlive[cell] && alive[cell])) next.fill(0f, cell * CHANNELS, (cell + 1) * CHANNELS)
        val t = state
        state = next
        next = t
        steps++
    }

    /** Runs [n] steps. */
    fun step(n: Int) = repeat(n) { step() }

    private fun perceive(s: FloatArray, x: Int, y: Int) {
        perception.fill(0f)
        for (dy in -1..1) {
            val yy = y + dy
            if (yy < 0 || yy >= size) continue
            for (dx in -1..1) {
                val xx = x + dx
                if (xx < 0 || xx >= size) continue
                // Sobel-x weights (dx, 2 dx, dx)/8 down the rows; Sobel-y is its transpose.
                val sx = dx * (if (dy == 0) 2f else 1f) / 8f
                val sy = dy * (if (dx == 0) 2f else 1f) / 8f
                val base = (yy * size + xx) * CHANNELS
                for (c in 0 until CHANNELS) {
                    val v = s[base + c]
                    if (dx == 0 && dy == 0) perception[c] = v
                    perception[CHANNELS + c] += sx * v
                    perception[2 * CHANNELS + c] += sy * v
                }
            }
        }
    }

    /** Channel [c] of the cell at ([x], [y]). */
    operator fun get(x: Int, y: Int, c: Int): Float = state[(y * size + x) * CHANNELS + c]

    /** The visible picture as ARGB pixels on a white ground, [scale] pixels per cell. */
    fun argb(scale: Int = 1): IntArray {
        val w = size * scale
        val px = IntArray(w * w)
        for (y in 0 until w) for (x in 0 until w) {
            val base = ((y / scale) * size + x / scale) * CHANNELS
            val a = state[base + 3].coerceIn(0f, 1f)
            fun ch(c: Int) = ((1f - a + state[base + c].coerceIn(0f, 1f)).coerceIn(0f, 1f) * 255f).toInt()
            px[y * w + x] = (0xff shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return px
    }

    /** [argb] as PNG bytes; `display("main", nca.png())` shows it in a box's output. */
    fun png(scale: Int = 4): ByteArray = PngEncoder.encode(argb(scale), size * scale, size * scale)

    /** Mean squared difference between this grid's RGBA and [target] (`[y][x][rgba]`, premultiplied). */
    fun loss(target: FloatArray): Double {
        var sum = 0.0
        for (cell in 0 until size * size) for (c in 0 until 4) {
            val d = state[cell * CHANNELS + c] - target[cell * 4 + c]
            sum += d * d
        }
        return sum / (size * size * 4)
    }

    /**
     * A compute shader that runs this rule on the GPU, one invocation per cell, in 8 x 8 workgroups.
     *
     * The push constants are `cells` (one buffer of `2 * size * size * 4` vec4s: two whole grids),
     * `nw` (the [MlpWeights.flat] array in a buffer), `size`, `parity` (0 reads the first grid and
     * writes the second, 1 the reverse), a per-step `seed` for the update mask, and `brush` (`xy` in
     * cells, `z` radius, `w > 0` to erase). Both grids live in one buffer, selected by `parity`,
     * because a buffer-reference push constant is resolved once and then reused: swapping two
     * pointers between dispatches leaves the shader reading the first grid for ever. The network itself is `MlpWeights.glsl`'s output,
     * so the GPU and [step] evaluate one set of weights in one order.
     */
    fun computeShader(): String = buildString {
        append("#version 450\n")
        append(mlp.glsl("ncaNet"))
        append(
            """
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(std430, buffer_reference) buffer NcaState { vec4 v[]; };
            layout(push_constant) uniform pcs {
                NcaState cells;
                ncaNetWeights nw;
                uint size;
                uint parity;
                uint seed;
                vec4 brush;
            } pc;

            float cellAlpha(int x, int y) {
                if (x < 0 || y < 0 || x >= int(pc.size) || y >= int(pc.size)) return 0.0;
                return pc.cells.v[int(pc.parity * pc.size * pc.size * 4u) + (y * int(pc.size) + x) * 4].w;
            }
            float hash(uvec3 v) {
                v = v * 1664525u + 1013904223u;
                v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
                v ^= v >> 16u;
                v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
                return float(v.x & 0xffffffu) / 16777216.0;
            }

            void main() {
                int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y), n = int(pc.size);
                if (x >= n || y >= n) return;
                int src = int(pc.parity) * n * n * 4, dst = (1 - int(pc.parity)) * n * n * 4;
                int base = (y * n + x) * 4;
                float s[16];
                for (int k = 0; k < 4; ++k) { vec4 q = pc.cells.v[src + base + k]; s[4*k] = q.x; s[4*k+1] = q.y; s[4*k+2] = q.z; s[4*k+3] = q.w; }

                bool pre = false;
                for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) pre = pre || cellAlpha(x + dx, y + dy) > $ALIVE_THRESHOLD;

                if (pre && hash(uvec3(x, y, pc.seed)) <= $fireRate) {
                    float p[48];
                    for (int c = 0; c < 48; ++c) p[c] = 0.0;
                    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue;
                        float sx = float(dx) * (dy == 0 ? 2.0 : 1.0) / 8.0;
                        float sy = float(dy) * (dx == 0 ? 2.0 : 1.0) / 8.0;
                        int nb = (yy * n + xx) * 4;
                        for (int k = 0; k < 4; ++k) {
                            vec4 q = pc.cells.v[src + nb + k];
                            for (int j = 0; j < 4; ++j) {
                                int c = 4 * k + j;
                                p[16 + c] += sx * q[j];
                                p[32 + c] += sy * q[j];
                            }
                        }
                    }
                    for (int c = 0; c < 16; ++c) p[c] = s[c];
                    float ds[16];
                    ncaNet(pc.nw, p, ds);
                    for (int c = 0; c < 16; ++c) s[c] += ds[c];
                }
                // The second half of alive masking needs the neighbours' *updated* alpha, which this
                // pass cannot see; the draw pass and the next step's pre-check apply it instead
                // (see dev_docs/GROWING_NCA.md).
                if (!pre) for (int c = 0; c < 16; ++c) s[c] = 0.0;
                if (pc.brush.w > 0.0 && distance(vec2(x, y) + 0.5, pc.brush.xy) < pc.brush.z) for (int c = 0; c < 16; ++c) s[c] = 0.0;
                for (int k = 0; k < 4; ++k) pc.cells.v[dst + base + k] = vec4(s[4*k], s[4*k+1], s[4*k+2], s[4*k+3]);
            }
            """.trimIndent()
        )
        append("\n")
    }

    companion object {
        /** State channels per cell. */
        const val CHANNELS = 16

        /** Inputs to the per-cell network: three filters over every channel. */
        const val PERCEPTION = 48

        /** A cell is alive when it or a neighbour has alpha above this. */
        const val ALIVE_THRESHOLD = 0.1f

        /**
         * Reads the JSON `tools/nca/train_growing_nca.py` writes: `w1` (128 x 48, row-major), `b1`
         * (128), `w2` (16 x 128). The output layer has no bias in the article's model, so its bias
         * is zero here.
         */
        fun weightsFromJson(text: String): MlpWeights {
            val o = Json.parseToJsonElement(text).jsonObject
            fun floats(k: String) = o.getValue(k).jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val hidden = o["hidden"]?.jsonPrimitive?.int ?: 128
            val flat = floats("w1") + floats("b1") + floats("w2") + FloatArray(CHANNELS)
            return MlpWeights(PERCEPTION, CHANNELS, intArrayOf(hidden), MlpActivation.RELU, flat)
        }

        /** The names [pretrained] accepts. */
        val pretrainedNames = listOf("flower")

        /**
         * A rule that ships with Field, trained to grow [name] (one of [pretrainedNames]) from one
         * seed and regrow it after damage.
         */
        fun pretrained(name: String = "flower", size: Int = 64, random: Random = Random(0)): GrowingNca {
            val text = checkNotNull(GrowingNca::class.java.getResourceAsStream("/afield/alife/nca_$name.json")) {
                "no pretrained growing NCA called '$name'; try one of $pretrainedNames"
            }.bufferedReader().use { it.readText() }
            return GrowingNca(weightsFromJson(text), size, random = random)
        }

        /**
         * The picture [name] was trained to grow, `[y][x][rgba]` premultiplied, at the training grid
         * size (64), for [loss].
         */
        fun pretrainedTarget(name: String = "flower"): FloatArray {
            val text = checkNotNull(GrowingNca::class.java.getResourceAsStream("/afield/alife/nca_$name.json")) {
                "no pretrained growing NCA called '$name'; try one of $pretrainedNames"
            }.bufferedReader().use { it.readText() }
            val o = Json.parseToJsonElement(text).jsonObject
            return o.getValue("targetRgba").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }

        /** A rule from a JSON file written by `tools/nca/train_growing_nca.py`. */
        fun load(file: File, size: Int = 64, random: Random = Random(0)): GrowingNca =
            GrowingNca(weightsFromJson(file.readText()), size, random = random)
    }
}

/**
 * A left-button brush over a native [Window], reported as fractions of the window (0..1, y down)
 * while the button is held. Installing again under the same [key] replaces the earlier brush, so a
 * box that installs one can be re-run without stacking listeners.
 *
 * The growing NCA demo uses it to erase cells under the mouse; nothing in it is NCA-specific.
 */
object WindowBrush {
    private val removers = mutableMapOf<String, () -> Unit>()

    /** Calls [paint] with the cursor's window fractions on press and on every move while pressed. */
    fun install(window: Window, key: String, paint: (x: Double, y: Double) -> Unit) {
        synchronized(removers) { removers.remove(key)?.invoke() }
        var down = false
        var x = 0.0
        var y = 0.0
        fun emit() {
            val r = window.size()
            if (down && r.w > 0 && r.h > 0) paint(x / r.w, y / r.h)
        }
        val a = window.addCursorListener { cx, cy -> x = cx; y = cy; emit() }
        val b = window.addMouseButtonListener { button, action, _ ->
            if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                down = action != org.lwjgl.glfw.GLFW.GLFW_RELEASE
                emit()
            }
        }
        synchronized(removers) { removers[key] = { a(); b() } }
    }

    /** Removes the brush installed under [key], if any. */
    fun remove(key: String) = synchronized(removers) { removers.remove(key)?.invoke() }
}
