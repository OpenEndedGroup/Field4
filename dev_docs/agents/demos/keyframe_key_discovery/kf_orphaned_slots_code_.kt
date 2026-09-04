// A saved slot that applies to nowhere.
//
// The browser has always drawn one half of this: a slot the scope watches that no key holds — the
// "you have never saved this" row. The other half was silent. A slot a *key* holds that the scope no
// longer watches renders as an ordinary row, with its real saved range across every key, and
// `KeyframeScope.loadKey` is explicit about what happens next: "a file whose values have since left
// the scope loads fine — the extra names simply have nowhere to go on apply."
//
// So a rename half-done looks exactly like a working slot, and Apply quietly drops it.

import afield.stdlib.KeyframeCollection
import afield.stdlib.KeyframeScope
import afield.stdlib.everyKeyGlob
import afield.four.buffers.Structure
import java.nio.file.Files

val dir = Files.createTempDirectory("keyframe-orphaned-slots")

// Three keys saved back when the pass had a `blur` as well as a `radius`.
run {
    val old = Structure()
    val past = KeyframeScope()
    past.target("glow@radius", old)
    past.target("glow@blur", old)
    listOf(1f to 4f, 6f to 5f, 11f to 6f).forEachIndexed { i, (r, b) ->
        old.bind("radius") { r }
        old.bind("blur") { b }
        past.capture().save(dir.resolve(listOf("a", "b", "c")[i]), "look ${listOf("a", "b", "c")[i]}")
    }
}

// The pass as it is today: `blur` is gone, and a `feather` has arrived that nothing has ever saved.
val glow = Structure()
glow.bind("radius") { 7f }
glow.bind("feather") { 0.5f }
val kfs = KeyframeScope()
kfs.target("glow@radius", glow)
kfs.target("glow@feather", glow)

val c = KeyframeCollection.load(dir, everyKeyGlob, kfs)

println("slots in view:      " + c.slotNames)
println("watched by the scope: " + c.scopeSlots.sorted())
println("saved, and wired to nothing: " + c.orphanedSlots + "   <- Apply drops these")
println()
c.slots().forEach { s ->
    val tag = when {
        s.orphaned -> "ORPHANED  (saved in ${s.presentIn}/${s.total} keys, applies to nowhere)"
        s.inScope && s.presentIn == 0 -> "never saved (the scope watches it, no key holds it)"
        else -> "live"
    }
    println("  ${s.name.padEnd(14)} ${tag}")
}
println()
// The flag is not just "scopeSlots is empty". A read-only browse cannot know which slots are live,
// and must not accuse every row; an attached scope watching nothing must accuse every row. Both
// leave scopeSlots empty, so `scopeAttached` is what tells them apart.
val readOnly = KeyframeCollection.load(dir, everyKeyGlob, scope = null)
println("read-only browse: scopeAttached=${readOnly.scopeAttached}, orphaned=${readOnly.orphanedSlots}")
println("  (no scope, so no accusation — this is why scopeAttached exists as its own flag)")

// `glow@blur` is struck through and named in the header; `glow@radius` is plain; `glow@feather` is
// the amber "never saved" row this browser already had.
keyframeBrowser(dir, channel = "saved, but wired to nothing", scope = kfs, height = 400)
