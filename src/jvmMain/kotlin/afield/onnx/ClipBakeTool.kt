package afield.onnx

import afield.macros.appendClipBlock
import afield.macros.clipValName

/**
 * Print the attic block a set of `clip("…")` prompts bakes to.
 *
 * ```
 * run-jvm-main afield.onnx.ClipBakeTool --arg "a person sitting at a desk" --arg "an empty room"
 * ```
 *
 * Authoring aid, for documents that are *shipped* rather than typed: a bundled starting point or
 * template can carry its baked constants in its `attic` property, so opening it costs nothing and
 * compiling it in CI needs neither the network nor the 242MB text encoder — `macro_clip` finds the block
 * already present and returns a cache hit. Without this the corpus guard would download CLIP the first
 * time it compiled such a box.
 *
 * The output is the exact text `macro_clip` would have written, so the keys match and a later live edit
 * to a prompt re-bakes only that one.
 */
object ClipBakeTool {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: ClipBakeTool <prompt> [<prompt> …]")
            kotlin.system.exitProcess(2)
        }
        val model = Clip.TEXT_MODEL.id
        var attic = ""
        args.forEach { prompt ->
            attic = appendClipBlock(
                attic,
                clipValName(model, prompt),
                model,
                prompt,
                ClipText.encode(prompt).toBase64()
            )
        }
        println("----- BEGIN ATTIC -----")
        println(attic)
        println("----- END ATTIC -----")
        OnnxRuntime.closeAll()
    }
}
