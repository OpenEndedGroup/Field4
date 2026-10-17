//! Watercolour and ink behind the C ABI: paper canvases, water on any canvas, the watercolour brush,
//! drops and clean-water washes, tilt and time, the ledger, and maps of the water's state.
//!
//! A pigment crosses the boundary as 8 floats — K (r, g, b) and S per µg/mm², density (g/cm³),
//! diameter (µm), staining and lift rates (per second) — and a name; paint as a list of pigments and
//! their concentrations, µg per mm³ of water.

use crate::tools::{number, within};
use crate::{guard, region, Studio};
use paint::field::aqueous::{AqPigment, Paper, Water};
use paint::field::watercolor::{self as wc, Sample, WaterBrush};
use paint::Canvas;
use std::ffi::{c_char, CStr};

pub(crate) fn cstr<'a>(p: *const c_char) -> Result<&'a str, String> {
    unsafe { CStr::from_ptr(p) }.to_str().map_err(|e| e.to_string())
}

fn engine_of(engine: i32) -> Result<u32, String> {
    match engine {
        0 => Ok(paint::ENGINE),
        e if e >= 1 && e as u32 <= paint::ENGINE => Ok(e as u32),
        e => Err(format!("unknown paint engine {e}")),
    }
}

/// `n` pigments from `params` (8 floats each) and `names` (newline separated).
fn pigments(names: *const c_char, params: *const f32, n: i32) -> Result<Vec<AqPigment>, String> {
    if n <= 0 {
        return Ok(vec![]);
    }
    let names: Vec<&str> = cstr(names)?.split('\n').collect();
    let v = unsafe { std::slice::from_raw_parts(params, 8 * n as usize) };
    if names.len() != n as usize {
        return Err(format!("{} pigment names for {n} pigments", names.len()));
    }
    v.chunks_exact(8).zip(names).map(|(c, name)| {
        if c.iter().any(|x| !x.is_finite()) || c[..4].iter().any(|&x| x < 0.0) {
            return Err(format!("pigment {name}: its constants must be numbers, K and S not negative"));
        }
        Ok(AqPigment { name: name.into(), k: [c[0], c[1], c[2]], s: c[3], density: c[4], diameter_um: c[5].max(0.0), stain: c[6].max(0.0), lift: c[7].max(0.0) })
    }).collect()
}

fn units_per_px(c: &Canvas) -> f32 {
    1.0 / c.frame().scale
}

/// The water on a canvas, made for it the first time water touches it.
fn water_of(s: &mut Studio, seed: u64) -> &mut Water {
    if s.water.is_none() {
        s.water = Some(Box::new(wc::water_for_canvas(&s.canvas, seed)));
    }
    s.water.as_mut().unwrap()
}

/// A paper canvas: a sheet of `paper` ("cold press", "hot press", "rough", "xuan", "blotting")
/// `size_mm` wide (0: 560 mm, a half imperial sheet), its relief in the canvas's surface.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_new_paper(width_px: i32, aspect: f32, paper: *const c_char, seed: u64, size_mm: f32, engine: i32) -> *mut Studio {
    guard(std::ptr::null_mut(), || {
        if width_px < 8 || !(aspect.is_finite() && aspect > 0.0) {
            return Err(format!("bad canvas size: width {width_px}, aspect {aspect}"));
        }
        let name = cstr(paper)?;
        let pp = Paper::named(name).ok_or_else(|| format!("no paper called {name:?}: cold press, hot press, rough, xuan or blotting"))?;
        let mm = if size_mm > 0.0 { size_mm } else { 560.0 };
        let mut c = Canvas::new(width_px as usize, aspect, pp.color).with_size_mm(mm).with_engine(engine_of(engine)?);
        let f = c.frame();
        let w = Water::new(pp, f.w, f.h, c.px_mm(), seed);
        wc::emboss(&mut c, &w.relief);
        Ok(Box::into_raw(Box::new(Studio { canvas: c, water: Some(Box::new(w)), last_contact: None })))
    })
}

/// The paper's make-up into `out[0..13]`: colour (linear r, g, b), cap µm, absorb µm/s, wick mm²/s,
/// anisotropy, fibre mm, formation, roughness µm, texture mm, pin µm, and 1 if it is paper (0: the
/// canvas's own ground, or no water yet, -1).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_paper(c: *const Studio, out: *mut f32) -> i32 {
    guard(-1, || {
        let Some(w) = &unsafe { &*c }.water else { return Ok(0) };
        let p = &w.paper;
        let v = [p.color[0], p.color[1], p.color[2], p.cap_um, p.absorb_um_s, p.wick_mm2_s, p.anisotropy, p.fibre_mm, p.formation, p.roughness_um, p.texture_mm, p.pin_um];
        unsafe { std::slice::from_raw_parts_mut(out, v.len()) }.copy_from_slice(&v);
        Ok(1)
    })
}

/// Tilt the sheet `degrees` from flat, falling toward `direction` degrees (0 right, 90 down the
/// canvas). Upright is 90°.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_tilt(c: *mut Studio, degrees: f32, direction: f32, seed: u64) -> i32 {
    guard(-1, || {
        let w = water_of(unsafe { &mut *c }, seed);
        w.tilt_deg = number(degrees, "tilt")?;
        w.tilt_dir_deg = number(direction, "tilt direction")?;
        Ok(0)
    })
}

/// How fast open water evaporates, µm/s (still air at 20 °C and 50 % humidity ≈ 0.15; a warm draught
/// several times that, a damp room less).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_evaporation(c: *mut Studio, um_s: f32, seed: u64) -> i32 {
    guard(-1, || {
        let v = number(um_s, "evaporation")?;
        if v < 0.0 {
            return Err("evaporation can't be negative".into());
        }
        water_of(unsafe { &mut *c }, seed).evap_um_s = v;
        Ok(0)
    })
}

/// Let `seconds` pass for the water and the oil alike.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wait_seconds(c: *mut Studio, seconds: f32) -> i32 {
    guard(-1, || {
        let s = number(seconds, "seconds")?;
        if s < 0.0 {
            return Err("time doesn't run backwards".into());
        }
        unsafe { &mut *c }.wait(s / 60.0);
        Ok(0)
    })
}

// ---- the brush -----------------------------------------------------------------------------------

/// A watercolour brush `width` units across, its belly sized for this canvas's scale (`capacity_mm3`
/// > 0 sets it instead).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_new(c: *const Studio, width: f32, capacity_mm3: f32) -> *mut WaterBrush {
    guard(std::ptr::null_mut(), || {
        if !(width.is_finite() && width > 0.0) {
            return Err(format!("bad brush width {width}"));
        }
        let mut b = WaterBrush::new(width, unsafe { &*c }.mm_per_unit());
        if capacity_mm3 > 0.0 {
            b.capacity_mm3 = capacity_mm3;
        }
        Ok(Box::into_raw(Box::new(b)))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_free(b: *mut WaterBrush) {
    if !b.is_null() {
        unsafe { drop(Box::from_raw(b)) }
    }
}

/// Dip the brush: squeeze out `wipe` (0..1) of what it holds, then fill it to `fill` of its capacity
/// (above 1 overloads it, dripping) with water carrying `n` pigments at `conc` µg/mm³.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_load(b: *mut WaterBrush, names: *const c_char, params: *const f32, conc: *const f32, n: i32, fill: f32, wipe: f32) -> i32 {
    guard(-1, || {
        let ps = pigments(names, params, n)?;
        let cs: &[f32] = if n > 0 { unsafe { std::slice::from_raw_parts(conc, n as usize) } } else { &[] };
        let paint: Vec<(AqPigment, f32)> = ps.into_iter().zip(cs.iter().copied()).collect();
        if paint.iter().any(|p| !p.1.is_finite() || p.1 < 0.0) {
            return Err("a concentration is a number, 0 or more".into());
        }
        let fill = number(fill, "fill")?.max(0.0);
        unsafe { &mut *b }.load(&paint, fill, within(wipe, 0.0..=1.0, "wipe (the share squeezed out)")?);
        Ok(0)
    })
}

/// Touch the brush to a towel: it loses `share` of its water.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_blot(b: *mut WaterBrush, share: f32) -> i32 {
    guard(-1, || {
        unsafe { &mut *b }.blot(within(share, 0.0..=1.0, "the share blotted")?);
        Ok(0)
    })
}

/// `[width, capacity mm³, water mm³, µg of pigment in all]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_state(b: *const WaterBrush, out: *mut f32) -> i32 {
    guard(-1, || {
        let b = unsafe { &*b };
        let v = [b.width, b.capacity_mm3, b.water_mm3, b.pigment.iter().map(|p| p.1).sum()];
        unsafe { std::slice::from_raw_parts_mut(out, 4) }.copy_from_slice(&v);
        Ok(0)
    })
}

/// Drag the brush through `n` samples `[x, y, t, pressure]` (units, seconds from the stroke's start,
/// 0..), the sheet's water moving as it goes. `out` gets `[mm³ laid, mm³ taken up, µg laid, µg taken
/// up, seconds]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_stroke(c: *mut Studio, b: *mut WaterBrush, xytp: *const f32, n: i32, seed: u64, out: *mut f64) -> i32 {
    guard(-1, || {
        if n < 1 {
            return Err("a stroke needs at least one sample".into());
        }
        let v = unsafe { std::slice::from_raw_parts(xytp, 4 * n as usize) };
        let samples: Vec<Sample> = v.chunks_exact(4).map(|s| Sample { x: s[0], y: s[1], t: s[2], pressure: s[3] }).collect();
        if samples.iter().any(|s| !(s.x.is_finite() && s.y.is_finite() && s.t.is_finite() && s.pressure.is_finite())) {
            return Err("a stroke's samples must be numbers".into());
        }
        if samples.windows(2).any(|p| p[1].t < p[0].t) {
            return Err("a stroke's times must not go backwards".into());
        }
        let s = unsafe { &mut *c };
        let upp = units_per_px(&s.canvas);
        let secs = (samples.last().unwrap().t - samples[0].t).max(0.0);
        let rep = wc::stroke(water_of(s, seed), unsafe { &mut *b }, &samples, upp);
        // the oil keeps its own clock with the hand's
        s.canvas.wait(secs / 60.0);
        let o = [rep.water_laid, rep.water_taken, rep.pigment_laid, rep.pigment_taken, rep.seconds];
        unsafe { std::slice::from_raw_parts_mut(out, 5) }.copy_from_slice(&o);
        Ok(0)
    })
}

/// Let a drop of `volume_mm3` (µl) fall at (x, y) with `n` pigments at `conc` µg/mm³.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_water_drop(c: *mut Studio, x: f32, y: f32, volume_mm3: f32, names: *const c_char, params: *const f32, conc: *const f32, n: i32, seed: u64) -> i32 {
    guard(-1, || {
        let ps = pigments(names, params, n)?;
        let cs: &[f32] = if n > 0 { unsafe { std::slice::from_raw_parts(conc, n as usize) } } else { &[] };
        let paint: Vec<(AqPigment, f32)> = ps.into_iter().zip(cs.iter().copied()).collect();
        let v = number(volume_mm3, "volume")?;
        if v <= 0.0 {
            return Err("a drop has some volume".into());
        }
        let s = unsafe { &mut *c };
        let upp = units_per_px(&s.canvas);
        wc::drop(water_of(s, seed), number(x, "x")?, number(y, "y")?, v, &paint, upp)?;
        Ok(0)
    })
}

/// Wet a region (rings, as the other regions) with `um` µm of clean water — or, with pigments, a
/// flat wash of that paint — laid at once, as a wide mop would.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_water_wash(
    c: *mut Studio, xy: *const f32, ring_sizes: *const i32, rings: i32, feather: f32, um: f32,
    names: *const c_char, params: *const f32, conc: *const f32, n: i32, seed: u64,
) -> i32 {
    guard(-1, || {
        let um = number(um, "water depth")?;
        if um < 0.0 {
            return Err("a wash lays water, not takes it".into());
        }
        let ps = pigments(names, params, n)?;
        let cs: Vec<f32> = if n > 0 { unsafe { std::slice::from_raw_parts(conc, n as usize) }.to_vec() } else { vec![] };
        let s = unsafe { &mut *c };
        let m = region(&s.canvas, xy, ring_sizes, rings, feather)?;
        let w = water_of(s, seed);
        let ids: Vec<usize> = ps.iter().map(|p| w.species_index(p)).collect::<Result<_, _>>()?;
        for (i, &v) in m.data.iter().enumerate() {
            if v > 0.0 {
                let depth = um * v;
                let masses: Vec<(usize, f32)> = ids.iter().zip(&cs).map(|(&k, &cc)| (k, cc * depth / 1000.0)).collect();
                w.add(i, depth, &masses);
            }
        }
        Ok(0)
    })
}

// ---- looking -------------------------------------------------------------------------------------

/// The water's ledger and totals into `out` (which holds `cap` doubles); returns how many were
/// written: `[clock s, surface mm³, absorbed mm³, added, taken, evaporated, runoff, absorbed in all,
/// species]`, then per species `[suspended, carried, deposited (sediment), glued, bound, added, taken,
/// runoff, sealed]` µg. 0 when the canvas has no water.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_water_stats(c: *const Studio, out: *mut f64, cap: i32) -> i32 {
    guard(-1, || {
        let Some(w) = &unsafe { &*c }.water else { return Ok(0) };
        let t = w.totals();
        let l = &w.ledger;
        let mut v = vec![w.clock_s, t.surface_water, t.absorbed_water, l.water_added, l.water_taken, l.water_evaporated, l.water_runoff, l.water_absorbed, w.species.len() as f64];
        for k in 0..w.species.len() {
            v.extend([t.suspended[k], t.carried[k], t.deposited[k], t.glued[k], t.bound[k], l.pigment_added[k], l.pigment_taken[k], l.pigment_runoff[k], l.pigment_sealed[k]]);
        }
        if v.len() > cap as usize {
            return Err(format!("water stats need {} doubles, given {cap}", v.len()));
        }
        unsafe { std::slice::from_raw_parts_mut(out, v.len()) }.copy_from_slice(&v);
        Ok(v.len() as i32)
    })
}

/// The names of the sheet's pigment species, newline separated (free with `field_paint_free_string`).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_water_species(c: *const Studio) -> *mut c_char {
    guard(std::ptr::null_mut(), || {
        let names = unsafe { &*c }.water.as_ref().map(|w| w.species.iter().map(|p| p.name.clone()).collect::<Vec<_>>().join("\n")).unwrap_or_default();
        Ok(std::ffi::CString::new(names).map_err(|e| e.to_string())?.into_raw())
    })
}

/// A map of the water's state, one float per pixel into `out`: 0 surface water µm, 1 absorbed water
/// µm, 2 the pores' saturation, 3 all pigment µg/mm², 4 suspended, 5 deposited (loose), 6 bound,
/// 7 the paper's relief µm, 8 sealed share, 9 carried in the pores, 10 glued (dried in place; 5 is
/// all the loose deposit, sediment and glued). Returns the pixel count (0: no
/// water).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_water_map(c: *const Studio, which: i32, out: *mut f32, cap: i32) -> i32 {
    guard(-1, || {
        let Some(w) = &unsafe { &*c }.water else { return Ok(0) };
        let n = w.w * w.h;
        if n > cap as usize {
            return Err(format!("map buffer holds {cap}, the sheet has {n} pixels"));
        }
        let o = unsafe { std::slice::from_raw_parts_mut(out, n) };
        let sum = |sets: &[&Vec<Vec<f32>>], i: usize| -> f32 { sets.iter().map(|s| s.iter().map(|v| v[i]).sum::<f32>()).sum() };
        for i in 0..n {
            o[i] = match which {
                0 => w.sw[i],
                1 => w.aw[i],
                2 => w.aw[i] / w.cap[i].max(1e-3),
                3 => sum(&[&w.ms, &w.ma, &w.md, &w.mg, &w.mb], i),
                4 => sum(&[&w.ms], i),
                5 => sum(&[&w.md, &w.mg], i),
                6 => sum(&[&w.mb], i),
                7 => w.relief[i],
                8 => w.sealed[i],
                9 => sum(&[&w.ma], i),
                10 => sum(&[&w.mg], i),
                k => return Err(format!("unknown water map {k}")),
            };
        }
        Ok(n as i32)
    })
}

/// A named watercolour pigment's 8 constants into `out`; 0 if there is none by that name. With a null
/// name, writes the names (newline separated) into a string (free with `field_paint_free_string`).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_aq_pigment(name: *const c_char, out: *mut f32) -> i32 {
    guard(-1, || {
        let Some(p) = AqPigment::named(cstr(name)?) else { return Ok(0) };
        let v = [p.k[0], p.k[1], p.k[2], p.s, p.density, p.diameter_um, p.stain, p.lift];
        unsafe { std::slice::from_raw_parts_mut(out, 8) }.copy_from_slice(&v);
        Ok(1)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_aq_pigment_names() -> *mut c_char {
    guard(std::ptr::null_mut(), || Ok(std::ffi::CString::new(AqPigment::names().join("\n")).map_err(|e| e.to_string())?.into_raw()))
}

/// A pigment from its masstone (linear), strength (K per µg/mm²), opacity, density, diameter, stain
/// and lift, as `AqPigment::new` makes it, into `out[0..8]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_aq_pigment_make(r: f32, g: f32, b: f32, strength: f32, opacity: f32, density: f32, diameter_um: f32, stain: f32, lift: f32, out: *mut f32) -> i32 {
    guard(-1, || {
        for (v, what) in [(r, "red"), (g, "green"), (b, "blue"), (strength, "strength"), (opacity, "opacity"), (density, "density"), (diameter_um, "diameter")] {
            number(v, what)?;
        }
        let p = AqPigment::new("custom", [r, g, b], strength.max(0.0), opacity.max(0.0), density, diameter_um.max(0.0), stain.max(0.0), lift.max(0.0));
        let v = [p.k[0], p.k[1], p.k[2], p.s, p.density, p.diameter_um, p.stain, p.lift];
        unsafe { std::slice::from_raw_parts_mut(out, 8) }.copy_from_slice(&v);
        Ok(0)
    })
}

/// The brush, exactly, as bytes (`FPW1`): width, capacity, water, scrub and every pigment in it.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_state_bytes(b: *const WaterBrush, out: *mut *mut u8, out_len: *mut i64) -> i32 {
    guard(-1, || {
        crate::tools::hand_bytes(wc::write_wbrush(unsafe { &*b }), out, out_len);
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wbrush_from_state(bytes: *const u8, len: i64) -> *mut WaterBrush {
    guard(std::ptr::null_mut(), || {
        let b = unsafe { std::slice::from_raw_parts(bytes, len.max(0) as usize) };
        Ok(Box::into_raw(Box::new(wc::read_wbrush(b)?)))
    })
}

/// Millimetres per canvas unit (the canvas is 1000 units wide).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_mm_per_unit(c: *const Studio) -> f32 {
    guard(-1.0, || Ok(unsafe { &*c }.mm_per_unit()))
}

/// Set the pigment dispersion through standing water, mm²/s (an approximation for the look: 0 is the
/// flow alone; see `paint::field::aqueous`).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dispersion(c: *mut Studio, mm2_s: f32, seed: u64) -> i32 {
    guard(-1, || {
        let v = number(mm2_s, "dispersion")?;
        if v < 0.0 {
            return Err("dispersion can't be negative".into());
        }
        water_of(unsafe { &mut *c }, seed).dispersion_mm2_s = v;
        Ok(0)
    })
}

/// Set how far paint lowers the water's surface tension, N/m (0.008 by default; 0 turns the
/// Marangoni spreading off, more spreads paint into a wet wash faster).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_marangoni(c: *mut Studio, n_m: f32, seed: u64) -> i32 {
    guard(-1, || {
        let v = number(n_m, "surface tension drop")?;
        if v < 0.0 {
            return Err("paint lowers surface tension: the drop can't be negative".into());
        }
        water_of(unsafe { &mut *c }, seed).marangoni_n_m = v;
        Ok(0)
    })
}
