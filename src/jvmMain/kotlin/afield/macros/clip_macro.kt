package afield.macros

import afield.Box
import afield.RunsAt
import afield.attic
import afield.logger.lprintln
import afield.macroRegistration
import afield.macros.MacroRegistry.MacroExpansionProblem
import afield.macros.MacroRegistry.callRewrite
import afield.onnx.Clip
import afield.onnx.ClipText
import afield.onnx.ClipVector
import java.security.MessageDigest

/**
 * `clip("…")` — a prompt, lifted out of frame time and out of runtime altogether.
 *
 * ```kotlin
 * val stormy = clip("calm") axisTo clip("storm")
 * val frame  = clipImage.encode(webcamRgba, 640, 480)
 * displacement = frame.project(stormy)          // a knob whose units are meaning
 * ```
 *
 * A CLIP prompt in source is a *constant*: the same string always produces the same 512 floats. So the
 * text tower has no business being present when the box runs. This macro encodes the prompt once, at
 * macro-expansion time, writes the result into the box's attic as a base64 literal, and replaces the
 * call site with a reference to it. What reaches the Kotlin compiler is
 *
 * ```kotlin
 * __clip_a1b2c3d4e5f6                                      // at the call site
 * val __clip_a1b2c3d4e5f6 = ClipVector.fromBase64("…")     // in the attic
 * ```
 *
 * and the 242MB text encoder is never loaded again — not on the next run, and not in the next session,
 * because the attic is saved with the document.
 *
 * ## Why the attic, and why keyed
 *
 * This is deliberately the same shape as [macro_ai]: an expensive, deterministic, out-of-band
 * computation cached in the attic under a stable key, so a cache hit costs a string comparison. The
 * differences from `ai()` are that the result here is *exactly* reproducible, so a stale entry can only
 * come from changing the prompt, and that the key includes the model id — a different checkpoint means a
 * different embedding space and must not silently reuse a vector from the old one.
 *
 * A distinct `@clip-gen:` marker is used rather than `ai()`'s `@ai-gen:` for one concrete reason:
 * [stripAiGenMetadata] feeds the surviving attic text to a language model as context, and 2.7KB of
 * base64 per prompt is expensive noise to send.
 *
 * ## The literal-only restriction
 *
 * Only a string literal can be baked, because baking happens before the program runs and a variable has
 * no value yet. `clip(someString)` is a clear error pointing at [afield.onnx.clipEncode], which does the
 * same work at runtime. This is the honest boundary of the technique rather than a limitation to
 * apologise for: it is precisely the set of prompts whose value is knowable at compile time.
 *
 * ## Preview safety
 *
 * Editor macro-preview expansion runs as the caret moves, over half-typed source. Encoding there would
 * bake `clip("s")`, `clip("st")`, `clip("sto")` into the attic and, worse, could trigger a 242MB
 * download from a keystroke. So on [MacroRegistry.isPreviewExpansion] this macro resolves from cache or
 * declines to rewrite at all.
 */

// -- Attic block format --
//
// // @clip-gen: __clip_a1b2c3d4e5f6 | model: clip-vit-base-patch32-text | prompt: storm
// val __clip_a1b2c3d4e5f6: afield.onnx.ClipVector = afield.onnx.ClipVector.fromBase64("…")
// // @clip-gen-end: __clip_a1b2c3d4e5f6

private val clipGenHeaderRegex =
    Regex("""^// @clip-gen:\s*(\S+)\s*\|\s*model:\s*(.*?)\s*\|\s*prompt:\s*(.*)$""")
private val clipGenEndRegex = Regex("""^// @clip-gen-end:\s*(\S+)\s*$""")

/** True if [attic] already carries a baked block for [valName]. */
fun atticHasClipBlock(attic: String, valName: String): Boolean =
    attic.lines().any { line ->
        val m = clipGenHeaderRegex.matchEntire(line.trim())
        m != null && m.groupValues[1] == valName
    }

/** Append a baked block. Assumes [valName] is not already present. */
fun appendClipBlock(attic: String, valName: String, model: String, prompt: String, base64: String): String {
    val header = "// @clip-gen: $valName | model: $model | prompt: ${prompt.replace("\n", "\\n")}"
    val body = "val $valName: afield.onnx.ClipVector = afield.onnx.ClipVector.fromBase64(\"$base64\")"
    val footer = "// @clip-gen-end: $valName"
    val block = "$header\n$body\n$footer"
    return if (attic.isBlank()) block else "${attic.trimEnd()}\n\n$block"
}

/**
 * A short, stable identifier for (model, prompt).
 *
 * Hashed rather than derived from the prompt text because a prompt is arbitrary user text and a Kotlin
 * identifier is not; 12 hex characters of SHA-256 is 48 bits, which is far beyond collision range for
 * the number of prompts a document could hold.
 */
internal fun clipValName(model: String, prompt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(model.toByteArray())
    digest.update(0)
    digest.update(prompt.toByteArray())
    val hex = digest.digest().joinToString("") { "%02x".format(it) }
    return "__clip_${hex.take(12)}"
}

/**
 * Unescape the contents of a Kotlin string literal.
 *
 * Only the escapes a prompt plausibly contains. Anything else is left as written rather than guessed at,
 * since a wrong guess would silently change the embedding.
 */
private fun unescapeKotlinString(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (val next = raw[i + 1]) {
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                else -> { out.append(c).append(next); i += 2 }
            }
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * The `clip("…")` expansion.
 *
 * @param args PSI siblings of the `clip` identifier, which joined look like `("some prompt")`
 * @return an [Insert] replacing the call with the attic val's name, or null to leave the source alone
 */
fun Box.macro_clip(args: List<String>): Insert? {
    val rawArgs = args.joinToString("").trim()
    val match = Regex("""^\(\s*"(.*)"\s*\)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(rawArgs)
        ?: throw MacroExpansionProblem(
            "clip() bakes a prompt at compile time, so it needs a literal string — got `clip$rawArgs`.\n" +
                "  For a prompt that is only known at runtime, use clipEncode(text) instead."
        )
    val prompt = unescapeKotlinString(match.groupValues[1])
    if (prompt.isBlank()) {
        throw MacroExpansionProblem("clip() needs a non-empty prompt")
    }

    val model = Clip.TEXT_MODEL.id
    val valName = clipValName(model, prompt)
    val attic = this.attic

    // Cache hit: the whole point. A string comparison, no tokenizer, no session, no model file.
    if (atticHasClipBlock(attic, valName)) return Insert(here = valName)

    // Preview expansion fires on caret movement over half-written code. Never encode (and so never
    // download) from there; leave the call untouched so the preview shows the source as written.
    if (MacroRegistry.isPreviewExpansion) return null

    val vector: ClipVector = try {
        if (!Clip.TEXT_MODEL.isPresent) {
            lprintln("info,clip", "clip(\"$prompt\") needs the CLIP text encoder — fetching ~242MB once")
        }
        ClipText.encode(prompt)
    } catch (t: Throwable) {
        throw MacroExpansionProblem(
            "clip(\"$prompt\") could not be baked: ${t.message ?: t.javaClass.name}"
        )
    }

    // Write `attic` but NOT `_attic`, so checkAtticAndExecute sees the change and runs the new
    // declaration on this very run -- the same handshake resolvePatternRewriteAi relies on.
    setPropertyRaw("attic", appendClipBlock(attic, valName, model, prompt, vector.toBase64()), doResync = true)
    lprintln("info,clip", "baked clip(\"$prompt\") into ${this.id} as $valName")

    return Insert(here = valName)
}

@RunsAt(macroRegistration)
fun registerClipMacro() {
    callRewrite["clip"] = { box, _, args ->
        box.macro_clip(args)
    }
}
