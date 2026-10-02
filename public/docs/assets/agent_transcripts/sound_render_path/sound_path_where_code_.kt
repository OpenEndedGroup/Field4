// The probe order, and what it found here. Dirt-Samples ships as a SuperCollider quark, so
// anyone who has run Tidal already has it — hence auto-discovery rather than asking you.
// ~400MB of third-party audio with mixed per-folder licensing, so it is NOT vendored.
val root = DirtRender.dirtSampleRoot()
println(if (root != null) "found: $root" else "not found — set ${DirtRender.ENV_VAR} to a copy of Dirt-Samples")
