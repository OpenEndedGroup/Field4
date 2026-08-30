package drowning

/**
 * The force programs, in StackSL.
 *
 * This is the point of the whole exercise. The force compute shader has four `{{ stack.auto.* }}`
 * slots; a box binds each one to a string, and Field compiles the string to GLSL, patches the pipeline
 * in the background, and leaves the previous binding alone if the string does not compile. So a force
 * law is a **value the CPU sends to the particles**, not code that has to be recompiled and reloaded:
 *
 * The setter is on the *module*, not on the mesh — a `stack.auto` slot is a property of the compiled
 * shader, and the mesh only carries push constants.
 *
 * ```kotlin
 * forceModule.edgeForce = "?gEdgeLen ?gRestLen - 12 *"                    // Hooke, stiffness 12
 * forceModule.edgeForce = "?gEdgeLen ?gRestLen - dup abs 0.35 |c0.3 * 8 *"  // and now it breathes
 * ```
 *
 * ## What a program can see
 *
 * A `stack.auto` slot compiles to a *free function*, `vec4 edgeForce(vec4 edgeForce_in)`, which cannot
 * see any local of its caller. So everything a program might want is a module-scope global in
 * [DustShaders.DUST_LIB], reachable with StackSL's host escape `?name`:
 *
 * | escape | is |
 * |---|---|
 * | `?gP` | position, `w` = 1/mass |
 * | `?gV` | velocity, `pos - prev` |
 * | `?gR` | rest position, `w` = stiffness scale |
 * | `?gA` | the four free channels — writable with `->?gA` |
 * | `?gHash` | the stable per-particle random, 0..1 |
 * | `?gTime` | seconds |
 * | `?gMeanLen` | mean rest length of this particle's edges — the network's own length scale |
 * | `?gIndex`, `?gDegree` | index, and how many edge slots it uses |
 * | `?gEdgeDir`, `?gEdgeLen`, `?gRestLen`, `?gEdgeSlot`, `?gOther` | the current edge, inside `edgeForce` only |
 * | `?pc.knobA` … `?pc.knobC` | three free `vec4`s from [DustLook] |
 * | `?pc.edgeGain`, `?pc.time`, … | anything in the push block |
 *
 * Plus the one `vec4` argument, which is set to something useful per slot so that StackSL's own
 * vocabulary works without any escapes at all. `noiseForce` gets `vec4(pos.xyz, time)`, so `uv` is
 * `pos.xy`, and `fbm` / `noise` (which read `coord.xy + coord.zw * 0.07`) come out genuinely
 * three-dimensional and time-varying for free — which is why the default noise program below is four
 * words long.
 *
 * ## What a program must not do
 *
 * No `sample`, `gather`, `blur`, `prev` or any other texture word: the compute shader declares no
 * sampler, so a program that uses one compiles to GLSL referencing an undeclared `source` and fails at
 * *shader* compile time with no hint of the StackSL behind it. `DustWorkout` checks every program here
 * against that.
 */
object DustPrograms {

    // ---- edgeForce ------------------------------------------------------------------------------
    //
    // Argument: vec4(len - rest, rest, hash, time). Returns `.x` as a signed force along `gEdgeDir`,
    // which points from this particle *toward* the neighbour — so a POSITIVE result pulls them together
    // and a negative one pushes them apart.
    //
    // `input.x` is `len - rest`, positive when stretched, so every restoring program below has a
    // positive gain. Flip one and the network pushes hardest exactly where it is already too long: it
    // does not wobble, it detonates, and what is left is every particle pinned against the bounding box
    // with zero velocity. `DustReference.contractsUnder` is the assertion that pins this.

    /**
     * Hooke's law: force proportional to extension, pulling toward the rest length.
     *
     * `input x` is `len - rest`; `12 *` makes it a restoring force of stiffness 12. The whole program is
     * three words, which is the useful baseline to change *from*.
     */
    val SPRING = "input x 12 *"

    /**
     * A spring that stiffens as it stretches — cubic in the extension.
     *
     * `<e <e * <e *` is `e * e * e`, which keeps the sign — the first multiply squares it and the second
     * restores it — so a compressed edge still pushes out. Physically this is a rope rather than a
     * spring: slack near the rest length and very hard once extended, which is what makes a network of
     * them hold a shape instead of wobbling.
     *
     * The mark is `=e` and not `=x` on purpose. `x` is also a *swizzle* word, and a mark that shadows a
     * word is a program that reads correctly and does something else.
     */
    val ROPE = "input x =e <e <e * <e * 160 *"

    /**
     * A spring whose stiffness breathes with time, per particle.
     *
     * `?gHash 6.283 *` phases the oscillation by the particle's own seed, so the field does not pulse
     * in unison — the thing that separates a field with life in it from a field with a sine wave on it.
     */
    val BREATHING = "input x  1 0.6 ?gHash 6.283 * 0.7 * cos * +  * 14 *"

    /**
     * Only *pulls*: an extended edge contracts, a compressed one does nothing.
     *
     * `0 max` clips the compressed half away, leaving only the stretched half to act. A network of
     * pull-only edges collapses unless something holds it out — the rest tether, or `noiseForce` — and
     * what it collapses into is the interesting part, because the orthant network's short edges win and
     * the long ones go slack.
     */
    val PULL_ONLY = "input x 0 max 20 *"

    /**
     * Extension relative to rest length rather than absolute, so every edge is equally stiff whatever
     * its length.
     *
     * `/` is StackSL's safe divide, which is doing real work here: an orthant network on a random cloud
     * has rest lengths spanning more than a decade, and the shortest edge divided by nothing would be
     * the one that blows up.
     */
    val UNIFORM_STRAIN = "input x input y / 10 *"

    // ---- noiseForce -----------------------------------------------------------------------------
    //
    // Argument: vec4(pos.xyz, time). Returns `.xyz` as a force. `uv` is pos.xy and `hash` is pos.z, so
    // the spatial noise words are three-dimensional without any help; `?gHash` is the per-particle seed.

    /** Nothing. The starting state, so that the field settles before it is pushed. */
    val STILL = "0"

    /**
     * A genuinely three-dimensional fbm, sampled at three offset points to make a vector out of a
     * scalar field.
     *
     * ## StackSL's noise words are 2D, and that shows
     *
     * `fbm` and `noise` lower to `fbm(vec2)` over `coord.xy + coord.zw * 0.07`. Feeding them
     * `vec4(pos.xyz, time) * 2.2` — the obvious thing — gives a coordinate of
     * `(2.2·x + 0.154·z, 2.2·y + 0.154·t)`. So `x` enters at fourteen times the weight of `z`, and `z`
     * enters only as a *shear on the x axis* rather than as a third dimension. The field is essentially
     * `f(x, y)`, and on a 3D cloud viewed down z that reads as a **fixed stripy pattern imprinted at
     * every depth** — most visible in the edge strain, where it looks like structure and is flat noise.
     *
     * `fbm3` sums three 2D slices — `f(x,y) + f(y,z) + f(z,x)`, each with its own time rate so the
     * field does not pulse in unison — which varies along all three axes for three times the cost of
     * one. That is what every shader claiming "3D noise" over a 2D hash does underneath.
     *
     * ## `>c` and not `=c`, and why the first version was badly broken
     *
     * A definition must have a net stack effect of **minus one plus one**: consume its input, leave its
     * result. `=name` *copies* the top and leaves the stack unchanged (`a -- a`); `>name` *moves* it
     * (`a --`). The first version of this program opened `fbm3` with `=c`, so the definition left its
     * input sitting under its result and every call pushed two values instead of one.
     *
     * The consequences were not subtle and were not reported. Three `fbm3` calls plus a `vec3` came out
     * as: the first call's result **pruned away entirely**, and the `y` channel of the force taking a
     * raw *coordinate* off the stack instead of a noise value —
     *
     * ```glsl
     * vec4 _v53 = (_v2 + vec4(3.9, 21.7, 14.3, 1.0));    // a position, not a noise sample
     * vec4 _v76 = vec4(_v51.x, _v53.x, _v75.x, 1.0);     // and it lands in channel y
     * ```
     *
     * — so `y` carried a constant of about `2 * (3.9 - 0.5) ≈ 6.8`, a large steady force along one
     * axis. Turning the output gain up translated the whole field into a wall instead of agitating it,
     * which is exactly how it was spotted.
     *
     * **StackSL emitted no diagnostic.** It prunes unused values silently. What it does report is
     * `analysis.functions[].finalStackDepth`, which was `2` for `fbm3` and `6` for the root where both
     * should be `1`, with `prunedValues = 15`. `DustWorkout.everyProgramLeavesACleanStack` now asserts
     * that, because a stack-arity mistake in a stack language is the mistake to expect.
     *
     * The mark is `>c` and not `>p` for the separate reason that **`p` is a word** — it pushes
     * `vec4(uv.xy - 0.5, 0, 1)`. It resolved to the mark here, but a mark that shadows a word is a
     * program that reads correctly and does something else. Same reason [ROPE] marks `e` and not `x`.
     *
     * ## Centring
     *
     * `fbm`'s five octaves have amplitudes `0.5, 0.25, 0.125, 0.0625, 0.03125`, which sum to
     * **0.96875, not 1**. So a raw `fbm` runs `0 .. 0.96875` with a mean near `0.484`, and the usual
     * `0.5 - 2 *` leaves a bias of about `-0.03` — small, but a *constant* force, which integrates into
     * a drift rather than averaging out. The `1.032258 *` (`1 / 0.96875`) normalises the range first so
     * `0.5 -` actually centres it.
     *
     * A residual remains: `hash21` is only approximately uniform, so the mean is only approximately
     * `0.5`. If a program needs *exactly* zero mean, take a difference of two samples at different
     * offsets — `f(a) - f(b)` is zero-mean for any `f` — at twice the cost.
     */
    val DRIFT = """
        : fbm3
          >c
          <c xy  ?gTime 0.25 * +  fbm x
          <c yz  ?gTime 0.19 * +  fbm x
          <c zx  ?gTime 0.31 * +  fbm x
          + + 0.33333 *
          1.032258 *
        ;

        input 2.2 * =q
        <q                  fbm3
        <q [17.3,5.1,9.7]  + fbm3
        <q [3.9,21.7,14.3] + fbm3
        vec3
        0.5 - 2 *
    """.trimIndent()

    /**
     * The flat version of [DRIFT], kept because a deliberately planar force is a real look and because
     * the comparison is the clearest way to see what the three-slice trick buys.
     *
     * This is a field in `(x, y)` alone: on a cloud viewed down z it prints the same pattern at every
     * depth and barely moves. Bind it to `noiseForce` beside [DRIFT] and the difference is not subtle.
     */
    val DRIFT_FLAT = """
        input 2.2 * =q
        <q                  fbm x
        <q [17.3,5.1,9.7]  + fbm x
        <q [3.9,21.7,14.3] + fbm x
        vec3
        0.5 - 2 *
    """.trimIndent()

    /**
     * A slow swirl about the y axis, strongest at the middle radius and falling off outside it.
     *
     * The tangent of a circle about y at a point `(x, y, z)` is `(z, 0, -x)`, which is one swizzle and
     * one multiply: `zyx` gives `(z, y, x)` and `[1,0,-1] *` zeroes the middle and flips the last.
     *
     * Deliberately **not** normalised. `|(z, 0, -x)|` grows with the distance from the axis, which is
     * rigid rotation — constant angular velocity — and it is the profile you want; the `pulse` then
     * shapes where it applies. Normalising would give constant *speed*, so the middle would spin faster
     * than the rim and shear the network apart.
     *
     * The first version reached for a stack selector, `[2z,1y,0x]`, on the theory that selectors are for
     * building a vector out of channels. They are not: a selector indexes the **stack**, so `2` means
     * "three values down" and that one consumed three values where two existed. StackSL warned —
     * `Word '*' needed 2 values but found 1; reusing wrapped stack values` — as a *warning*, which is
     * why `DustWorkout` now prints every severity instead of filtering to errors.
     */
    val VORTEX = """
        input >c
        <c zyx [1,0,-1] *
        <c length2 1.4 0.9 pulse *
        1.2 *
    """.trimIndent()

    /**
     * Noise whose *rate* is modulated per particle, so nearby particles gradually fall out of step.
     *
     * `?gHash` scales the time term, which means two particles that started together diverge linearly
     * rather than staying locked — the difference between a field that shimmers and a field that
     * shivers as one sheet.
     *
     * Deliberately **planar**: `input xy` throws the depth away, so this is a field in `(x, y)` printed
     * at every z. Unlike [DRIFT]'s old form that is a choice rather than an accident — the per-particle
     * rate is what keeps it from reading as a flat sheet — but it is worth knowing which of these
     * sixteen programs are 3D and which are not. See the note on [DRIFT].
     */
    val SHIMMER = """
        input xy 5 * =q
        <q ?gHash 3 * ?gTime * 0.4 * + noise x
        <q 11.1 + ?gTime 0.31 * + noise x
        <q 4.7  + ?gTime 0.17 * - noise x
        vec3
        0.5 - 3 *
    """.trimIndent()

    /**
     * Push outward from the origin, with the strength quantised into shells.
     *
     * `q6` quantises the falloff to six levels, which turns a smooth radial force into a set of nested
     * spheres the particles pile up against. A good demonstration that a *modifier* — one character —
     * changes the physics here, not just the colour.
     */
    val SHELLS = "input =p <p normalize <p length3 |q6 * 0.8 *"

    // ---- restForce ------------------------------------------------------------------------------
    //
    // Argument: vec4(rest - pos, time). Returns `.xyz` as a force. `length3` of the argument is the
    // displacement from rest, which is the quantity every program here is shaped around.

    /** Linear pull back to the rest position. Critically boring, and what keeps everything else stable. */
    val TETHER = "input xyz 6 *"

    /**
     * A tether that only engages beyond a radius: inside it the particle is free.
     *
     * `smoothstep` over the displacement gives a soft cage rather than a hard one, and `?pc.knobA x` is
     * its radius — so the cage is a live slider without touching this string.
     */
    val LEASH = """
        input xyz =d
        <d
        0 ?pc.knobA x smoothstep
        input length3 0 ?pc.knobA x smoothstep *
        14 *
    """.trimIndent()

    /**
     * Pull back toward rest, but *rotated* — the field settles into a twisted copy of itself rather
     * than back onto its own starting positions.
     *
     * `rotate` takes `point angle -- point` and works on `xy`, so this is a twist about the z axis whose
     * angle grows with height. The reason to want it: a rest tether that reproduces the start exactly
     * makes the network invisible, because nothing is ever under load.
     */
    val TWIST = """
        input xyz =d
        <d  ?gR z 0.7 *  rotate
        5 *
    """.trimIndent()

    // ---- velocityShape --------------------------------------------------------------------------
    //
    // Argument: vec4(velocity.xyz, time), after damping and before the position update. Returns `.xyz`
    // as the velocity to use. The place for anything that is a property of *motion* rather than a force.

    /** Pass the velocity through unchanged. */
    val PASS = "input xyz"

    /**
     * Terminal velocity: speeds above `?pc.knobB x` are clamped, below it nothing changes.
     *
     * Different from [DustLook.maxStep], which is a safety net that fires on anything: this is a
     * deliberate look, and it applies before the step clamp rather than instead of it.
     */
    val TERMINAL = """
        input xyz =v
        <v normalize
        <v length3 0 ?pc.knobB x clamp
        *
    """.trimIndent()

    /**
     * Bleed the velocity toward the *edge-aligned* component, so motion runs along the network rather
     * than across it.
     *
     * Not available inside `velocityShape` — `gEdgeDir` is only set during an `edgeForce` call — so this
     * uses `?gV` and the rest direction instead, which is the honest approximation and reads the same.
     */
    val ALONG_REST = """
        input xyz =v
        ?gR xyz ?gP xyz - normalize =n
        <v <n dot <n *
        <v 0.4 mix
    """.trimIndent()

    /**
     * The four slots as a box would bind them for the calm default look: a rope network, a slow drift,
     * a plain tether, and no velocity shaping.
     *
     * A `Map` and not four constants so that a box can write one loop and `DustWorkout` can check every
     * combination it ships without a list of its own.
     */
    val DEFAULTS: Map<String, String> = linkedMapOf(
        "edgeForce" to ROPE,
        "noiseForce" to DRIFT,
        "restForce" to TETHER,
        "velocityShape" to PASS
    )

    /** The same, turned up: a pull-only network shimmering against a soft leash. */
    val WILD: Map<String, String> = linkedMapOf(
        "edgeForce" to PULL_ONLY,
        "noiseForce" to SHIMMER,
        "restForce" to LEASH,
        "velocityShape" to TERMINAL
    )

    /**
     * Every program here, by name, for the workout to compile and for a box to offer as a menu.
     *
     * The key is `slot/name`, so the slot a program belongs in travels with it — binding a `restForce`
     * program into `edgeForce` compiles fine and then behaves strangely, which is exactly the kind of
     * mistake a naming convention should make hard.
     */
    val ALL: Map<String, String> = linkedMapOf(
        "edgeForce/spring" to SPRING,
        "edgeForce/rope" to ROPE,
        "edgeForce/breathing" to BREATHING,
        "edgeForce/pullOnly" to PULL_ONLY,
        "edgeForce/uniformStrain" to UNIFORM_STRAIN,
        "noiseForce/still" to STILL,
        "noiseForce/drift" to DRIFT,
        "noiseForce/driftFlat" to DRIFT_FLAT,
        "noiseForce/vortex" to VORTEX,
        "noiseForce/shimmer" to SHIMMER,
        "noiseForce/shells" to SHELLS,
        "restForce/tether" to TETHER,
        "restForce/leash" to LEASH,
        "restForce/twist" to TWIST,
        "velocityShape/pass" to PASS,
        "velocityShape/terminal" to TERMINAL,
        "velocityShape/alongRest" to ALONG_REST
    )

    /** The programs that belong in one slot, by short name. `DustPrograms.forSlot("edgeForce")`. */
    fun forSlot(slot: String): Map<String, String> =
        ALL.filterKeys { it.startsWith("$slot/") }.mapKeys { it.key.substringAfter('/') }
}
