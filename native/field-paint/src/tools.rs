//! Upstream's later tools behind the C ABI: engine choice and raw cloth, configurable brushes,
//! thinned, oiled and double-loaded paint, spatter, the painting knife, the rag, the raking-light
//! look, and exact snapshots of a tool's state.

use crate::{guard, points, Studio};
use paint::rag::Rag;
use paint::{Canvas, Held, Knife, Paint, Part, Spatter, Style, Tool};

/// Bytes handed to the JVM; free with `field_paint_free_bytes`.
pub(crate) fn hand_bytes(v: Vec<u8>, out: *mut *mut u8, out_len: *mut i64) {
    let mut b = v.into_boxed_slice();
    unsafe {
        *out_len = b.len() as i64;
        *out = b.as_mut_ptr();
    }
    std::mem::forget(b);
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_free_bytes(p: *mut u8, len: i64) {
    if !p.is_null() {
        unsafe { drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(p, len as usize))) }
    }
}

fn engine_of(engine: i32) -> Result<u32, String> {
    match engine {
        0 => Ok(paint::ENGINE),
        e if e >= 1 && e as u32 <= paint::ENGINE => Ok(e as u32),
        e => Err(format!("unknown paint engine {e}: this build paints engines 1 to {}", paint::ENGINE)),
    }
}

/// `field_paint_canvas_new` with the engine to paint with (`engine` 0: the newest) and two more
/// styles: 4 raw cotton duck and 5 raw linen, bare cloth with no ground (upstream's soak-stain
/// canvas, engine 3 and later), whose colour is the fabric's own.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_new_v2(
    width_px: i32, aspect: f32, style: i32, seed: u64, r: f32, g: f32, b: f32, size_mm: f32, engine: i32,
) -> *mut Studio {
    guard(std::ptr::null_mut(), || {
        if width_px < 8 || !(aspect.is_finite() && aspect > 0.0) {
            return Err(format!("bad canvas size: width {width_px}, aspect {aspect}"));
        }
        let e = engine_of(engine)?;
        let w = width_px as usize;
        let sized = |st: Style| if size_mm > 0.0 { Style { width_mm: size_mm, ..st } } else { st };
        let mm = if size_mm > 0.0 { size_mm } else { 700.0 };
        let c = match style {
            0 => Canvas::new(w, aspect, [r, g, b]).with_size_mm(mm).with_engine(e),
            1 => sized(Style::oil()).prepare(w, aspect, seed).with_engine(e),
            2 => sized(Style::oil_red_ground()).prepare(w, aspect, seed).with_engine(e),
            3 => Canvas::new(w, aspect, [r, g, b]).with_size_mm(mm).with_linen(paint::Linen::fine(seed)).with_engine(e),
            4 | 5 => {
                if e < 3 {
                    return Err(format!("a raw canvas needs engine 3 or later, not {e}"));
                }
                let fabric = if style == 4 { paint::Fabric::cotton_duck() } else { paint::Fabric::linen() };
                let mut c = Canvas::new(w, aspect, fabric.color).with_size_mm(mm).with_linen(paint::Linen::fine(seed)).with_engine(e);
                c.raw_canvas(fabric, seed);
                c
            }
            s => return Err(format!("unknown canvas style {s}")),
        };
        Ok(Box::into_raw(Box::new(Studio::new(c))))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_engine(c: *const Studio) -> i32 {
    guard(-1, || Ok(unsafe { &*c }.engine() as i32))
}

/// The ground just primed is absorbent (`absorbent` 0..1: a chalk and glue ground that draws oil
/// out of paint laid on it and dries matte) or an oil ground (0). Engine 4 and later.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_ground_finish(c: *mut Studio, absorbent: f32) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        if absorbent > 0.0 && c.engine() < 4 {
            return Err(format!("an absorbent ground needs engine 4 or later; this canvas paints with engine {}", c.engine()));
        }
        c.ground_finish(absorbent);
        Ok(0)
    })
}

// ---- brushes -----------------------------------------------------------------------------------

fn tool_of(kind: i32, width: f32) -> Result<Tool, String> {
    Ok(match kind {
        0 => Tool::round_sable(width),
        1 => Tool::hog_flat(width),
        2 => Tool::filbert(width),
        3 => Tool::fan(width),
        4 => Tool::rigger(width),
        5 => Tool::badger(width),
        6 => Tool::stippler(width),
        k => return Err(format!("unknown brush kind {k}")),
    })
}

/// The number of construction values `field_paint_brush_new_v2` reads and `field_paint_brush_config`
/// writes after kind and width: bristles, length, stiffness, hair, run, lay, pickup, push, splay,
/// ragged, point.
pub const BRUSH_CONFIG: usize = 11;

/// A brush built from preset `kind` (0..5 as `field_paint_brush_new`, 6 the stippler) at `width`,
/// then any of its construction changed: `cfg` holds `BRUSH_CONFIG` values, NaN keeping the
/// preset's. `engine` is the canvas's (it decides what the brush's state records).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_new_v2(kind: i32, width: f32, seed: u64, engine: i32, cfg: *const f32) -> *mut Held {
    guard(std::ptr::null_mut(), || Ok(Box::into_raw(Box::new(held_from(kind, width, seed, engine, cfg)?))))
}

/// A held brush of preset `kind` at `width`, its construction changed by `cfg` (`BRUSH_CONFIG`
/// values, NaN keeping the preset's), as the canvas of `engine` (0: the newest) records it.
pub(crate) fn held_from(kind: i32, width: f32, seed: u64, engine: i32, cfg: *const f32) -> Result<Held, String> {
    if !(width.is_finite() && width > 0.0) {
        return Err(format!("bad brush width {width}"));
    }
    let mut t = tool_of(kind, width)?;
    let v = unsafe { std::slice::from_raw_parts(cfg, BRUSH_CONFIG) };
    let set = |x: f32, into: &mut f32| {
        if x.is_finite() {
            *into = x;
        }
    };
    if v[0].is_finite() {
        // (a guard on time and memory: 20 000 bristles take about 1.5 s a stroke)
        if !(1.0..=100_000.0).contains(&v[0]) {
            return Err(format!("a brush has 1 to 100 000 bristles, not {}", v[0]));
        }
        t.bristles = v[0].round() as usize;
    }
    set(v[1], &mut t.length);
    set(v[2], &mut t.stiffness);
    set(v[3], &mut t.hair);
    set(v[4], &mut t.run);
    set(v[5], &mut t.lay);
    set(v[6], &mut t.pickup);
    set(v[7], &mut t.push);
    set(v[8], &mut t.splay);
    set(v[9], &mut t.ragged);
    set(v[10], &mut t.point);
    t.validate()?;
    Ok(Held::new(t, seed).with_engine(engine_of(engine)?))
}

/// The brush's construction into `out`: kind, width, then the `BRUSH_CONFIG` values.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_config(h: *const Held, out: *mut f32) -> i32 {
    guard(-1, || {
        let t = &unsafe { &*h }.tool;
        let kind = match t.kind {
            paint::Kind::Round => 0.0,
            paint::Kind::Flat => 1.0,
            paint::Kind::Filbert => 2.0,
            paint::Kind::Fan => 3.0,
            paint::Kind::Rigger => 4.0,
            paint::Kind::Blender => 5.0,
        };
        let v = [kind, t.width, t.bristles as f32, t.length, t.stiffness, t.hair, t.run, t.lay, t.pickup, t.push, t.splay, t.ragged, t.point];
        unsafe { std::slice::from_raw_parts_mut(out, v.len()) }.copy_from_slice(&v);
        Ok(0)
    })
}

/// `v` when it lies in `range`, else an error naming `what`: for quantities that mean nothing outside
/// it (a share, a contrast ratio), which are refused rather than quietly clamped.
pub(crate) fn within(v: f32, range: std::ops::RangeInclusive<f32>, what: &str) -> Result<f32, String> {
    if range.contains(&v) { Ok(v) } else { Err(format!("{what} is {} to {}, not {v}", range.start(), range.end())) }
}

/// `v` if it is a number, else an error naming `what`.
pub(crate) fn number(v: f32, what: &str) -> Result<f32, String> {
    if v.is_finite() { Ok(v) } else { Err(format!("{what} must be a number, not {v}")) }
}

/// A paint from its parts. `scatter` (Kubelka–Munk scattering per coat) wins over `hiding` when
/// it is a number. `thinner` is engine 3's solvent beside the paint, `turps` engine 4's solvent
/// share of it (it evaporates as it is laid), `oil` its richness in oil relative to tube paint.
///
/// Only what would break the simulation is refused: `stiff` and `oil` may go far past tube paint
/// (an 11 is a stiffer, oilier paint than any tube), `hiding` and `turps` are shares and must be
/// within them, and `drying` is kept just above 0, where an open time would divide by it.
#[allow(clippy::too_many_arguments)]
fn paint_of(r: f32, g: f32, b: f32, hiding: f32, scatter: f32, stiff: f32, drying: f32, thinner: f32, turps: f32, oil: f32) -> Result<Paint, String> {
    let stiff = number(stiff, "stiffness")?;
    let base = if scatter.is_finite() {
        Paint::km([number(r, "red")?, number(g, "green")?, number(b, "blue")?], scatter.max(1e-6), stiff)
    } else {
        Paint::new([number(r, "red")?, number(g, "green")?, number(b, "blue")?], number(hiding, "hiding")?, stiff)
    };
    let oil = if oil.is_finite() { oil } else { 1.0 };
    Ok(Paint {
        solvent: if turps.is_finite() { turps } else { 0.0 },
        oil,
        ..base.with_drying(number(drying, "drying")?.max(1e-4)).with_thinner(if thinner.is_finite() { thinner } else { 0.0 })
    })
}

/// Dip the brush (`field_paint_brush_load`, more fully): the paint as `paint_of`, `amount` of a full
/// load after wiping `wipe` of what is there. When `share` < 1, `side` ≠ 0 or `streak` > 0 only
/// part of the brush is dipped (upstream's `Held::load_part`): the edge `side` (-1 or 1) and the
/// share of the width from it, taken up evenly or in streaks.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_load_v2(
    h: *mut Held, r: f32, g: f32, b: f32, hiding: f32, scatter: f32, stiff: f32, drying: f32,
    amount: f32, wipe: f32, thinner: f32, turps: f32, oil: f32, side: f32, share: f32, streak: f32, part_seed: u64,
) -> i32 {
    guard(-1, || {
        let h = unsafe { &mut *h };
        if wipe > 0.0 {
            h.wipe(within(wipe, 0.0..=1.0, "wipe (the share wiped off)")?);
        }
        let p = paint_of(r, g, b, hiding, scatter, stiff, drying, thinner, turps, oil)?;
        // more than a full load overloads the brush, as a dip into a deep pile does
        let amount = number(amount, "amount")?;
        let share = if share.is_finite() { within(share, 0.0..=1.0, "the dipped share of the brush")? } else { 1.0 };
        let side = if side.is_finite() { within(side, -1.0..=1.0, "the dipped side (-1 one edge, 1 the other)")? } else { 0.0 };
        let streak = if streak.is_finite() { within(streak, 0.0..=1.0, "streak")? } else { 0.0 };
        if share < 1.0 || side != 0.0 || streak > 0.0 {
            h.load_part(p, amount, &Part { side, share, streak, seed: part_seed });
        } else {
            h.load(p, amount);
        }
        Ok(0)
    })
}

/// Wipe `frac` of the paint (and solvent) out of the brush.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_wipe(h: *mut Held, frac: f32) -> i32 {
    guard(-1, || {
        unsafe { &mut *h }.wipe(within(frac, 0.0..=1.0, "the share wiped off")?);
        Ok(0)
    })
}

/// mm³ of paint and of solvent a brush carries, on this canvas's physical scale.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_carried_mm3(c: *const Studio, h: *const Held, out: *mut f64) -> i32 {
    guard(-1, || {
        let k = coats_to_mm3(unsafe { &*c });
        let (p, s) = unsafe { &*h }.carried();
        unsafe { std::slice::from_raw_parts_mut(out, 2) }.copy_from_slice(&[p * k, s * k]);
        Ok(0)
    })
}

/// mm³ in one coat over one square unit: what turns the crate's coats × units² into volume.
pub(crate) fn coats_to_mm3(c: &Canvas) -> f64 {
    let mmu = c.mm_per_unit() as f64;
    paint::COAT_UM as f64 / 1000.0 * mmu * mmu
}

/// The paint in the brush as one colour (linear RGB into `out[0..3]`, mixed as paint mixes) and
/// `out[3]` its volume in mm³ on this canvas's scale; returns 0 for a clean brush (out untouched), 1
/// otherwise.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_paint(c: *const Studio, h: *const Held, out: *mut f32) -> i32 {
    guard(-1, || {
        let Some((rgb, vol)) = unsafe { &*h }.carried_paint() else { return Ok(0) };
        let v = [rgb[0], rgb[1], rgb[2], (vol as f64 * coats_to_mm3(unsafe { &*c })) as f32];
        unsafe { std::slice::from_raw_parts_mut(out, 4) }.copy_from_slice(&v);
        Ok(1)
    })
}

/// The paint on the knife's blade, as `field_paint_brush_paint`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_paint(c: *const Studio, k: *const Knife, out: *mut f32) -> i32 {
    guard(-1, || {
        let k = unsafe { &*k };
        let Some(p) = k.paint() else { return Ok(0) };
        let vol = k.fullness() * k.full() * (1.0 - p.thinner);
        let v = [p.color[0], p.color[1], p.color[2], (vol as f64 * coats_to_mm3(unsafe { &*c })) as f32];
        unsafe { std::slice::from_raw_parts_mut(out, 4) }.copy_from_slice(&v);
        Ok(1)
    })
}

/// Flick the brush (upstream's `Spatter`): from (x, y) toward (tx, ty) relative, the length how far
/// the paint carries; `spread` is half the cone's angle (radians), `force` 0..1. Returns the
/// droplets that landed.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_spatter(c: *mut Studio, h: *mut Held, x: f32, y: f32, tx: f32, ty: f32, spread: f32, force: f32, seed: u64) -> i32 {
    guard(-1, || {
        let sp = Spatter { at: (x, y), toward: (tx, ty), spread, force, seed };
        sp.validate()?;
        Ok(unsafe { &mut *c }.oil(|c| c.spatter(unsafe { &mut *h }, &sp, None)) as i32)
    })
}

// ---- the knife ---------------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_new(width: f32) -> *mut Knife {
    guard(std::ptr::null_mut(), || {
        if !(width.is_finite() && (1.0..=1000.0).contains(&width)) {
            return Err(format!("a knife's blade is 1 to 1000 units long, not {width}"));
        }
        Ok(Box::into_raw(Box::new(Knife::new(width))))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_free(k: *mut Knife) {
    if !k.is_null() {
        unsafe { drop(Box::from_raw(k)) }
    }
}

/// Pick up `amount` (0..1 of a full bead) of paint (as `paint_of`) onto the blade, after wiping it
/// clean when `wipe` ≠ 0.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_load(
    k: *mut Knife, r: f32, g: f32, b: f32, hiding: f32, scatter: f32, stiff: f32, drying: f32,
    amount: f32, wipe: i32, thinner: f32, turps: f32, oil: f32,
) -> i32 {
    guard(-1, || {
        let k = unsafe { &mut *k };
        if wipe != 0 {
            k.wipe();
        }
        // upstream takes at most a full bead per dip (and ignores more): more goes on in full dips
        let p = paint_of(r, g, b, hiding, scatter, stiff, drying, thinner, turps, oil)?;
        let mut left = number(amount, "amount")?.max(0.0);
        if left > 10_000.0 {
            return Err(format!("{left} full beads is more paint than a canvas holds"));
        }
        while left > 0.0 {
            k.load(p, left.min(1.0));
            left -= 1.0;
        }
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_wipe(k: *mut Knife) -> i32 {
    guard(-1, || {
        unsafe { &mut *k }.wipe();
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_fullness(k: *const Knife) -> f32 {
    guard(-1.0, || Ok(unsafe { &*k }.fullness()))
}

/// Pull the knife along `n` points, pressed p0 → p1, the blade across the path or at `angle`
/// (radians; NaN: across). `lay` ≠ 0 lays paint from the bead; 0 only scrapes. `lift` is the share
/// of the bead left where the blade lifts.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_drag(c: *mut Studio, k: *mut Knife, xy: *const f32, n: i32, p0: f32, p1: f32, angle: f32, lay: i32, lift: f32) -> i32 {
    guard(-1, || {
        let pts = points(xy, n)?;
        let c = unsafe { &mut *c };
        let mm = c.frame().scale;
        Knife::check_path(&pts, mm).map_err(|e| e.to_string())?;
        if !(p0.is_finite() && p1.is_finite() && lift.is_finite()) {
            return Err("knife pressure and lift must be numbers".into());
        }
        let angle = if angle.is_finite() { Some(angle) } else { None };
        let lift = within(lift, 0.0..=1.0, "lift (the share of the bead left)")?;
        c.oil(|c| c.knife(unsafe { &mut *k }, &pts, (p0.max(0.0), p1.max(0.0)), angle, lay != 0, lift));
        Ok(0)
    })
}

// ---- the rag -----------------------------------------------------------------------------------

/// A clean rag bunched into a pad `width` units across.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_new(width: f32, seed: u64) -> *mut Rag {
    guard(std::ptr::null_mut(), || {
        if !(width.is_finite() && width > 0.0) {
            return Err(format!("bad rag width {width}"));
        }
        Ok(Box::into_raw(Box::new(Rag::new(width, seed))))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_free(g: *mut Rag) {
    if !g.is_null() {
        unsafe { drop(Box::from_raw(g)) }
    }
}

/// Dip the rag's face in spirits, `amount` 0..1 (a light dip about 0.5).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_dip(c: *mut Studio, g: *mut Rag, amount: f32) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        let now = c.now_min();
        unsafe { &mut *g }.dip(amount, now, c.tally_mut());
        Ok(0)
    })
}

/// Turn a cleaner face of the rag outward.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_refold(c: *mut Studio, g: *mut Rag) -> i32 {
    guard(-1, || {
        unsafe { &mut *g }.refold(unsafe { &mut *c }.tally_mut());
        Ok(0)
    })
}

/// Wipe the rag along `n` points at the pressures in `pressure` (spread along the path; none is
/// 0.5). One point is a blot. Returns the mm³ of paint lifted: negative where a loaded rag left
/// more than it took up, smearing paint it carried onto paint that is setting; NaN on failure.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_wipe(c: *mut Studio, g: *mut Rag, xy: *const f32, n: i32, pressure: *const f32, pn: i32, seed: u64) -> f64 {
    // (NaN on failure: a loaded rag on setting paint can leave more than it lifts, a negative lift)
    guard(f64::NAN, || {
        if n < 1 {
            return Err("a wipe needs at least one point".into());
        }
        let xy = unsafe { std::slice::from_raw_parts(xy, 2 * n as usize) };
        let pts: Vec<(f32, f32)> = xy.chunks_exact(2).map(|p| (p[0], p[1])).collect();
        if pts.iter().any(|p| !(p.0.is_finite() && p.1.is_finite())) {
            return Err("non-finite point in wipe".into());
        }
        let pr: Vec<f32> = if pn > 0 { unsafe { std::slice::from_raw_parts(pressure, pn as usize) }.iter().map(|p| p.max(0.0)).collect() } else { vec![] };
        if pr.iter().any(|p| !p.is_finite()) {
            return Err("a wipe's pressure must be a number".into());
        }
        Ok(unsafe { &mut *c }.rag_wipe(unsafe { &mut *g }, &pts, &pr, seed))
    })
}

/// How loaded the rag's face is, how much the whole cloth has soaked up and how damp the face is
/// with spirits now, into `out[0..3]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_state(c: *const Studio, g: *const Rag, out: *mut f32) -> i32 {
    guard(-1, || {
        let g = unsafe { &*g };
        let damp = g.damp_at(unsafe { &*c }.now_min());
        unsafe { std::slice::from_raw_parts_mut(out, 3) }.copy_from_slice(&[g.load, g.soaked, damp]);
        Ok(0)
    })
}

// ---- looking -----------------------------------------------------------------------------------

/// The canvas under a raking light (upstream's `seen_lit`): lit from `azimuth` degrees (0 from the
/// right, 90 from the top, 135 the upper left) at `elevation` degrees, relief shadows cast along
/// it, wet paint with an oily sheen. `dried` ≠ 0 looks at a copy dried to touch-dry instead, the
/// way the painting will look; 0 looks at it as it is, wet films at their thickness.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_render_lit_argb(c: *const Studio, azimuth: f32, elevation: f32, strength: f32, dried: i32, out: *mut i32, capacity: i32) -> i32 {
    guard(-1, || {
        if !(azimuth.is_finite() && elevation.is_finite() && strength.is_finite()) {
            return Err("the light needs a finite azimuth, elevation and strength".into());
        }
        let c = unsafe { &*c };
        crate::write_argb(&c.lit(azimuth, elevation, strength, dried != 0), out, capacity)
    })
}

/// `field_paint_stats` and the wet volume: `[clock min, hand min, open, setting, tacky, dry,
/// wet paint mm³, solvent mm³, engine]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_stats_v2(c: *const Studio, out: *mut f64) -> i32 {
    guard(-1, || {
        let c = unsafe { &*c };
        let s = c.stage_shares();
        let k = coats_to_mm3(c);
        let v = [c.clock(), c.tally().minutes(), s[0], s[1], s[2], s[3], c.wet_total() * k, c.solvent_total() * k, c.engine() as f64];
        unsafe { std::slice::from_raw_parts_mut(out, v.len()) }.copy_from_slice(&v);
        Ok(0)
    })
}

// ---- tool state --------------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_state(h: *const Held, out: *mut *mut u8, out_len: *mut i64) -> i32 {
    guard(-1, || {
        hand_bytes(unsafe { &*h }.write_state(), out, out_len);
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_from_state(bytes: *const u8, len: i64) -> *mut Held {
    guard(std::ptr::null_mut(), || {
        let b = unsafe { std::slice::from_raw_parts(bytes, len.max(0) as usize) };
        Ok(Box::into_raw(Box::new(Held::read_state(b)?)))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_state(k: *const Knife, out: *mut *mut u8, out_len: *mut i64) -> i32 {
    guard(-1, || {
        hand_bytes(unsafe { &*k }.write_state(), out, out_len);
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_knife_from_state(bytes: *const u8, len: i64) -> *mut Knife {
    guard(std::ptr::null_mut(), || {
        let b = unsafe { std::slice::from_raw_parts(bytes, len.max(0) as usize) };
        Ok(Box::into_raw(Box::new(Knife::read_state(b)?)))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_state_bytes(g: *const Rag, out: *mut *mut u8, out_len: *mut i64) -> i32 {
    guard(-1, || {
        hand_bytes(paint::field::state::write_rag(unsafe { &*g }), out, out_len);
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_rag_from_state(bytes: *const u8, len: i64) -> *mut Rag {
    guard(std::ptr::null_mut(), || {
        let b = unsafe { std::slice::from_raw_parts(bytes, len.max(0) as usize) };
        Ok(Box::into_raw(Box::new(paint::field::state::read_rag(b)?)))
    })
}
