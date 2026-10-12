//! The segmented brush behind the C ABI (`paint::field::bristles`): a brush whose hairs move,
//! driven by timed gestures of the handle (position, pressure, tilt, azimuth, roll).
//!
//! A gesture crosses as 7 floats a sample: x, y (canvas units), t (seconds), pressure, tilt,
//! azimuth, roll (radians).

use crate::tools::{hand_bytes, number};
use crate::{guard, Studio};
use paint::field::bristles::{self as bri, Bristles, Medium, Pose};
use paint::field::watercolor::WaterBrush;
use paint::Held;

/// A segmented brush of preset `kind` (as `field_paint_brush_new_v2`, with its `cfg`) `width`
/// canvas units wide, simulated with `hairs` guide hairs of `segs` segments. It carries oil in
/// upstream's dense bristles until it is loaded with watercolour.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_new(c: *const Studio, kind: i32, width: f32, cfg: *const f32, hairs: i32, segs: i32, seed: u64) -> *mut Bristles {
    guard(std::ptr::null_mut(), || {
        let held = crate::tools::held_from(kind, width, seed, 0, cfg)?;
        if !(1..=1024).contains(&hairs) || !(2..=32).contains(&segs) {
            return Err(format!("a segmented brush has 1 to 1024 guide hairs of 2 to 32 segments, not {hairs} of {segs}"));
        }
        let c = unsafe { &*c };
        let held = held.with_engine(c.engine());
        let b = Bristles::new(&held.tool, c.mm_per_unit(), hairs as usize, segs as usize, seed).with_held(held);
        Ok(Box::into_raw(Box::new(b)))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_free(b: *mut Bristles) {
    if !b.is_null() {
        unsafe { drop(Box::from_raw(b)) }
    }
}

/// Set how the hairs behave; NaN keeps a value: static and kinetic friction against the surface,
/// the bend (radians) past which a joint yields, how fast a yielded joint keeps its bend and how fast
/// rest bends recover (per second), and wet cohesion (1 a full brush's tips drawn to a point; more,
/// harder; negative, pushed apart).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_set(b: *mut Bristles, mu_s: f32, mu_k: f32, yield_rad: f32, plastic: f32, recover: f32, cohesion: f32) -> i32 {
    guard(-1, || {
        let b = unsafe { &mut *b };
        let set = |v: f32, into: &mut f32, what: &str| -> Result<(), String> {
            if v.is_finite() {
                if v < 0.0 {
                    return Err(format!("{what} can't be negative"));
                }
                *into = v;
            }
            Ok(())
        };
        set(mu_s, &mut b.friction.0, "static friction")?;
        set(mu_k, &mut b.friction.1, "kinetic friction")?;
        set(yield_rad, &mut b.yield_rad, "yield")?;
        set(plastic, &mut b.plastic, "plasticity")?;
        set(recover, &mut b.recover, "recovery")?;
        if cohesion.is_finite() {
            // any value: the bridges' pull is capped where it would overshoot (`field::bristles`)
            b.cohesion = cohesion;
        }
        Ok(0)
    })
}

/// Vary one of the hairs' behaviours along the next stroke, by name (`bristles::VARY_NAMES`): its
/// value at each of the stroke's `n` samples. The brush's own value is put back after the stroke.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_vary(b: *mut Bristles, name: *const std::ffi::c_char, values: *const f32, n: i32) -> i32 {
    guard(-1, || {
        let b = unsafe { &mut *b };
        let name = crate::water::cstr(name)?;
        let which = bri::VARY_NAMES.iter().position(|v| *v == name).ok_or_else(|| format!("a stroke can't vary {name:?}: {}", bri::VARY_NAMES.join(", ")))?;
        let v = unsafe { std::slice::from_raw_parts(values, n.max(0) as usize) }.to_vec();
        for x in &v {
            number(*x, name)?;
        }
        b.vary.retain(|(w, _)| *w != which);
        b.vary.push((which, v));
        Ok(0)
    })
}

/// Set one of the hairs' behaviours by name, any value: "clumping" (`Bristles::clumping`), "splay",
/// "splay scatter", "splay fan" or "cohesion".
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_param(b: *mut Bristles, name: *const std::ffi::c_char, value: f32) -> i32 {
    guard(-1, || {
        let b = unsafe { &mut *b };
        let name = crate::water::cstr(name)?;
        let v = number(value, name)?;
        match name {
            "clumping" => b.clumping = v,
            "splay" => b.splay = v,
            "splay scatter" => b.splay_scatter = v,
            "splay fan" => b.splay_fan = v,
            "cohesion" => b.cohesion = v,
            other => return Err(format!("a brush of moving hairs has no {other:?}: clumping, splay, splay scatter, splay fan or cohesion")),
        }
        Ok(0)
    })
}

/// `[width mm, length mm, stiffness, hairs, segs, mu_s, mu_k, yield, plastic, recover, cohesion,
/// fill, medium (0 oil, 1 watercolour), retained bend (mean |rest| over joints, radians)]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_info(b: *const Bristles, out: *mut f32) -> i32 {
    guard(-1, || {
        let b = unsafe { &*b };
        let joints: Vec<f32> = b.hairs.iter().flat_map(|h| h.rest.iter().map(|r| (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).sqrt())).collect();
        let bend = if joints.is_empty() { 0.0 } else { joints.iter().sum::<f32>() / joints.len() as f32 };
        let v = [b.width_mm, b.length_mm, b.stiffness, b.hairs.len() as f32, b.segs as f32, b.friction.0, b.friction.1, b.yield_rad, b.plastic, b.recover,
            b.cohesion, b.fill(), if b.medium() == Medium::Water { 1.0 } else { 0.0 }, bend];
        unsafe { std::slice::from_raw_parts_mut(out, v.len()) }.copy_from_slice(&v);
        Ok(0)
    })
}

/// Load oil paint into the brush's bristles (as `field_paint_brush_load_v2`); its watercolour, if
/// any, is squeezed out.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_load(
    b: *mut Bristles, r: f32, g: f32, bl: f32, hiding: f32, scatter: f32, stiff: f32, drying: f32,
    amount: f32, wipe: f32, thinner: f32, turps: f32, oil: f32, side: f32, share: f32, streak: f32, part_seed: u64,
) -> i32 {
    guard(-1, || {
        let b = unsafe { &mut *b };
        b.belly = None;
        let held: &mut Held = b.held.as_mut().ok_or("this brush has no bristles for oil")?;
        let st = crate::tools::field_paint_brush_load_v2(held, r, g, bl, hiding, scatter, stiff, drying, amount, wipe, thinner, turps, oil, side, share, streak, part_seed);
        if st < 0 {
            return Err(unsafe { std::ffi::CStr::from_ptr(crate::field_paint_last_error()) }.to_string_lossy().into_owned());
        }
        Ok(0)
    })
}

/// Load the brush with watercolour, as `field_paint_wbrush_load` (its belly sized for its width on
/// canvas `c`). From now on it paints watercolour.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_load_water(
    c: *const Studio, b: *mut Bristles, names: *const std::ffi::c_char, params: *const f32, conc: *const f32, n: i32, fill: f32, wipe: f32,
) -> i32 {
    guard(-1, || {
        let b = unsafe { &mut *b };
        let mmu = unsafe { &*c }.mm_per_unit();
        let belly = b.belly.get_or_insert_with(|| WaterBrush::new(b.width_mm / mmu, mmu));
        let st = crate::water::field_paint_wbrush_load(belly, names, params, conc, n, fill, wipe);
        if st < 0 {
            return Err(unsafe { std::ffi::CStr::from_ptr(crate::field_paint_last_error()) }.to_string_lossy().into_owned());
        }
        Ok(0)
    })
}

/// Drag the brush through `n` samples (7 floats each), painting oil or watercolour as it was last
/// loaded. `out` gets `[substeps, seconds, hair contacts, most of a hair on the paper (0..1), water
/// laid mm³, water taken mm³, pigment laid µg, pigment taken µg]`. The stroke's contact (seconds of
/// hair per pixel) is kept for `field_paint_contact_map`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_stroke(c: *mut Studio, b: *mut Bristles, s: *const f32, n: i32, seed: u64, out: *mut f64) -> i32 {
    guard(-1, || {
        if n < 1 {
            return Err("a gesture needs at least one sample".into());
        }
        let v = unsafe { std::slice::from_raw_parts(s, 7 * n as usize) };
        let mut samples = Vec::with_capacity(n as usize);
        for p in v.chunks_exact(7) {
            for (x, what) in p.iter().zip(["x", "y", "t", "pressure", "tilt", "azimuth", "roll"]) {
                number(*x, what)?;
            }
            samples.push(Pose { x: p[0], y: p[1], t: p[2], press: p[3], tilt: p[4], azimuth: p[5], roll: p[6] });
        }
        if samples.windows(2).any(|w| w[1].t < w[0].t) {
            return Err("a gesture's times must not go backwards".into());
        }
        if samples.last().unwrap().t - samples[0].t > 600.0 {
            return Err("a gesture longer than ten minutes".into());
        }
        let st = unsafe { &mut *c };
        let b = unsafe { &mut *b };
        let f = st.canvas.frame();
        let mut contact = vec![0.0f32; f.w * f.h];
        let rep = match b.medium() {
            Medium::Oil => st.oil(|cv| bri::stroke(cv, None, b, &samples, Some(&mut contact))),
            Medium::Water => {
                if st.water.is_none() {
                    st.water = Some(Box::new(paint::field::watercolor::water_for_canvas(&st.canvas, seed)));
                }
                let secs = samples.last().unwrap().t - samples[0].t;
                let Studio { canvas, water, .. } = st;
                let rep = bri::stroke(canvas, water.as_deref_mut(), b, &samples, Some(&mut contact));
                canvas.wait(secs / 60.0);
                rep
            }
        };
        st.last_contact = Some(contact);
        let o = [rep.substeps as f64, rep.seconds, rep.contacts as f64, rep.max_contact as f64, rep.water.water_laid, rep.water.water_taken, rep.water.pigment_laid, rep.water.pigment_taken];
        unsafe { std::slice::from_raw_parts_mut(out, 8) }.copy_from_slice(&o);
        Ok(0)
    })
}

/// The hairs now, as floats handed out (free with `field_paint_free_floats`): `[hairs, nodes per hair,
/// then x, y (canvas units), z (mm above the canvas's base) for every node]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_hairs(c: *const Studio, b: *const Bristles, out: *mut *mut f32, out_len: *mut i64) -> i32 {
    guard(-1, || {
        let lines = unsafe { &*b }.polylines(unsafe { &*c }.mm_per_unit());
        let mut v = vec![lines.len() as f32, lines.first().map_or(0, |l| l.len()) as f32];
        for l in &lines {
            for p in l {
                v.extend_from_slice(p);
            }
        }
        crate::hand_out(v, out, out_len);
        Ok(0)
    })
}

/// Seconds of hair contact per pixel in the last segmented stroke, into `out`; the pixel count (0:
/// none yet).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_contact_map(c: *const Studio, out: *mut f32, cap: i32) -> i32 {
    guard(-1, || {
        let Some(m) = &unsafe { &*c }.last_contact else { return Ok(0) };
        if m.len() > cap as usize {
            return Err(format!("map buffer holds {cap}, the canvas has {} pixels", m.len()));
        }
        unsafe { std::slice::from_raw_parts_mut(out, m.len()) }.copy_from_slice(m);
        Ok(m.len() as i32)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_state(b: *const Bristles, out: *mut *mut u8, out_len: *mut i64) -> i32 {
    guard(-1, || {
        hand_bytes(unsafe { &*b }.write_state(), out, out_len);
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dbrush_from_state(bytes: *const u8, len: i64) -> *mut Bristles {
    guard(std::ptr::null_mut(), || {
        let b = unsafe { std::slice::from_raw_parts(bytes, len.max(0) as usize) };
        Ok(Box::into_raw(Box::new(Bristles::read_state(b)?)))
    })
}
