//! `paint` (claude-paint) behind a flat C ABI, for `afield.paint` (Kotlin FFM).
//!
//! Every entry point catches panics: a Rust panic must not unwind into the JVM. A caught panic
//! returns a negative status and leaves its message for `field_paint_last_error`.
//!
//! Coordinates are paint's "units": the canvas is 1000 units wide and `1000 / aspect` tall, y down.
//! Colours cross the boundary as linear-light RGB in 0..1.
//!
//! The crate is vendored (`vendor/paint/VENDORED.md`): upstream's engine, plus Field's additions
//! under `paint::field`.

use paint::{Canvas, Gesture, Held, Orient, Paint, Palette, Style, Tool};

pub use studio::Studio;
use std::cell::RefCell;
use std::ffi::{c_char, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

mod dynamic;
mod studio;
mod tools;
mod water;

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

pub(crate) fn guard<T>(fallback: T, f: impl FnOnce() -> Result<T, String>) -> T {
    let r = catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|p| {
        Err(p
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| p.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "panic".into()))
    });
    match r {
        Ok(v) => v,
        Err(e) => {
            LAST_ERROR.with(|l| *l.borrow_mut() = CString::new(e.replace('\0', " ")).unwrap_or_default());
            fallback
        }
    }
}

/// The last error on this thread (empty when there was none). Valid until the next call that fails.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_last_error() -> *const c_char {
    LAST_ERROR.with(|l| l.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_version() -> *const c_char {
    static V: &str = concat!("field_paint_jvm ", env!("CARGO_PKG_VERSION"), ", paint engine\0");
    V.as_ptr() as *const c_char
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_engine() -> u32 {
    paint::ENGINE
}

/// A new canvas `width_px` wide. `style` 0 is bare ground of colour (r,g,b); 1 is paint's primed
/// oil canvas (`Style::oil().prepare`: linen, size and ground, which is why it takes a seed); 2 the
/// red-ground style; 3 bare linen of colour (r,g,b). `size_mm` > 0 sets the physical width, which
/// is what every mark's real size (a 0.3 mm pencil point, a crack's µm opening) is measured against.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_new(width_px: i32, aspect: f32, style: i32, seed: u64, r: f32, g: f32, b: f32, size_mm: f32) -> *mut Studio {
    guard(std::ptr::null_mut(), || {
        if width_px < 8 || !(aspect.is_finite() && aspect > 0.0) {
            return Err(format!("bad canvas size: width {width_px}, aspect {aspect}"));
        }
        let w = width_px as usize;
        // a physical width (mm) when given, else each style's own (700 mm bare, the styles' theirs)
        let sized = |st: Style| if size_mm > 0.0 { Style { width_mm: size_mm, ..st } } else { st };
        let mm = if size_mm > 0.0 { size_mm } else { 700.0 };
        let c = match style {
            0 => Canvas::new(w, aspect, [r, g, b]).with_size_mm(mm),
            1 => sized(Style::oil()).prepare(w, aspect, seed),
            2 => sized(Style::oil_red_ground()).prepare(w, aspect, seed),
            // bare linen: the weave and nothing on it, the colour of raw flax
            3 => Canvas::new(w, aspect, [r, g, b]).with_size_mm(mm).with_linen(paint::Linen::fine(seed)),
            s => return Err(format!("unknown canvas style {s}")),
        };
        Ok(Box::into_raw(Box::new(Studio::new(c))))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_free(c: *mut Studio) {
    if !c.is_null() {
        unsafe { drop(Box::from_raw(c)) }
    }
}

/// Pixel size of the canvas.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_size(c: *const Studio, w: *mut i32, h: *mut i32) {
    let f = unsafe { &*c }.frame();
    unsafe {
        *w = f.w as i32;
        *h = f.h as i32;
    }
}

/// A held brush. `kind`: 0 round sable, 1 hog flat, 2 filbert, 3 fan, 4 rigger, 5 badger (blender).
/// `point` > 0 gives the tuft a fine point (paint's `Tool::point`).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_new(kind: i32, width: f32, point: f32, seed: u64) -> *mut Held {
    guard(std::ptr::null_mut(), || {
        if !(width.is_finite() && width > 0.0) {
            return Err(format!("bad brush width {width}"));
        }
        let mut t = match kind {
            0 => Tool::round_sable(width),
            1 => Tool::hog_flat(width),
            2 => Tool::filbert(width),
            3 => Tool::fan(width),
            4 => Tool::rigger(width),
            5 => Tool::badger(width),
            k => return Err(format!("unknown brush kind {k}")),
        };
        t.point = point.clamp(0.0, 1.0);
        t.validate()?;
        Ok(Box::into_raw(Box::new(Held::new(t, seed))))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_free(h: *mut Held) {
    if !h.is_null() {
        unsafe { drop(Box::from_raw(h)) }
    }
}

/// Dip the brush: `amount` (0..1 of a full load) of a paint of masstone (r,g,b), one coat hiding
/// `hiding` (0.05 glaze .. 0.92 body), stiffness `stiff` (0 fluid .. 1 tube paint).
/// `wipe` first removes that fraction of what is already in the brush.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_load(h: *mut Held, r: f32, g: f32, b: f32, hiding: f32, stiff: f32, drying: f32, amount: f32, wipe: f32) -> i32 {
    guard(-1, || {
        let h = unsafe { &mut *h };
        if wipe > 0.0 {
            h.wipe(wipe);
        }
        h.load(Paint::new([r, g, b], hiding.clamp(0.001, 0.999), stiff.clamp(0.0, 1.0)).with_drying(drying.max(0.01)), amount.clamp(0.0, 1.0));
        Ok(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_fullness(h: *const Held) -> f32 {
    guard(-1.0, || Ok(unsafe { &*h }.fullness()))
}

/// Drag the brush along `n` points (`xy` interleaved, canvas units). `orient` 0 across, 1 along.
/// `swell`/`swell_n`: pressure multipliers at evenly spaced knots (may be empty).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_drag(
    c: *mut Studio, h: *mut Held, xy: *const f32, n: i32,
    p0: f32, p1: f32, shake: f32, attack: f32, release: f32, orient: i32,
    swell: *const f32, swell_n: i32,
) -> i32 {
    guard(-1, || {
        if n < 2 {
            return Err(format!("a stroke needs two points, got {n}"));
        }
        let xy = unsafe { std::slice::from_raw_parts(xy, 2 * n as usize) };
        let pts: Vec<(f32, f32)> = xy.chunks_exact(2).map(|p| (p[0], p[1])).collect();
        if pts.iter().any(|p| !(p.0.is_finite() && p.1.is_finite())) {
            return Err("non-finite point in stroke".into());
        }
        // Field clamped pressure to 1 before engine 5; an engine-2 canvas still does, so its calls paint
        // as they did. Later engines press as hard as asked.
        let c = unsafe { &mut *c };
        let press = |p: f32| if c.engine() <= 2 { p.clamp(0.0, 1.0) } else { p.max(0.0) };
        let mut g = Gesture::new(pts)
            .pressure(press(p0), press(p1))
            .shake(shake.max(0.0))
            .ramps(attack.clamp(0.0, 1.0), release.clamp(0.0, 1.0))
            .orient(if orient == 1 { Orient::Along } else { Orient::Across });
        if swell_n > 0 {
            g = g.swell(unsafe { std::slice::from_raw_parts(swell, swell_n as usize) }.to_vec());
        }
        c.oil(|c| c.drag(unsafe { &mut *h }, &g, None));
        Ok(0)
    })
}

/// Let `minutes` of drying time pass.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_wait(c: *mut Studio, minutes: f32) -> i32 {
    guard(-1, || {
        if !minutes.is_finite() {
            return Err(format!("wait needs finite minutes, got {minutes}"));
        }
        unsafe { &mut *c }.wait(minutes);
        Ok(0)
    })
}

/// Dry every film on the canvas (touch-dry), compositing it into the picture.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_dry(c: *mut Studio) -> i32 {
    guard(-1, || {
        unsafe { &mut *c }.dry();
        Ok(0)
    })
}

/// The picture as 8-bit sRGB ARGB (`0xAARRGGBB`, one int per pixel, row major) into `out`, which
/// holds `w * h` ints. paint keeps wet paint apart from the dry picture, so this renders a copy
/// dried to touch-dry, leaving the canvas itself wet. `relief` > 0 lights the impasto from the
/// upper left (paint's `Canvas::relief`, strength ~0.3–1), `gloss` adds varnish sheen.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_render_argb(c: *const Studio, relief: f32, gloss: f32, out: *mut i32, capacity: i32) -> i32 {
    guard(-1, || {
        write_argb(&unsafe { &*c }.picture(relief, gloss), out, capacity)
    })
}

/// Linear RGB pixels as 8-bit sRGB ARGB (`0xAARRGGBB`) into `out`, which holds `capacity` ints.
pub(crate) fn write_argb(px: &[paint::Rgb], out: *mut i32, capacity: i32) -> Result<i32, String> {
    if px.len() > capacity as usize {
        return Err(format!("render buffer holds {capacity} pixels, canvas has {}", px.len()));
    }
    let out = unsafe { std::slice::from_raw_parts_mut(out, px.len()) };
    let to8 = |v: f32| (paint::color::linear_to_srgb(v) * 255.0).round().clamp(0.0, 255.0) as u32;
    for (o, p) in out.iter_mut().zip(px) {
        *o = (0xff00_0000u32 | to8(p[0]) << 16 | to8(p[1]) << 8 | to8(p[2])) as i32;
    }
    Ok(px.len() as i32)
}

/// Save a dried copy as paint's own dithered sRGB PNG.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_save_png(c: *const Studio, path: *const c_char) -> i32 {
    guard(-1, || {
        let p = unsafe { std::ffi::CStr::from_ptr(path) }.to_str().map_err(|e| e.to_string())?;
        let s = unsafe { &*c };
        let mut copy = s.canvas.clone();
        copy.dry();
        if let Some(w) = &s.water {
            paint::field::watercolor::lay_over(&mut copy, w);
        }
        copy.save(p).map_err(|e| e.to_string())?;
        Ok(0)
    })
}

// ---- regions -----------------------------------------------------------------------------------

/// A mask from closed polygons (even-odd), `xy` interleaved canvas units, `ring_sizes` points per
/// ring. `feather` > 0 blurs its edge by that many units.
pub(crate) fn region(c: &Canvas, xy: *const f32, ring_sizes: *const i32, rings: i32, feather: f32) -> Result<paint::Mask, String> {
    if rings < 1 {
        return Err("a region needs at least one closed ring".into());
    }
    let sizes = unsafe { std::slice::from_raw_parts(ring_sizes, rings as usize) };
    let total: usize = sizes.iter().map(|&n| n.max(0) as usize).sum();
    let xy = unsafe { std::slice::from_raw_parts(xy, 2 * total) };
    let mut polys: Vec<Vec<(f32, f32)>> = Vec::new();
    let mut at = 0;
    for &n in sizes {
        let n = n.max(0) as usize;
        if n >= 3 {
            polys.push(xy[2 * at..2 * (at + n)].chunks_exact(2).map(|p| (p[0], p[1])).collect());
        }
        at += n;
    }
    if polys.is_empty() {
        return Err("a region ring needs at least three points".into());
    }
    let inside = |x: f32, y: f32| {
        let mut odd = false;
        for p in &polys {
            let mut j = p.len() - 1;
            for i in 0..p.len() {
                let (a, b) = (p[i], p[j]);
                if (a.1 > y) != (b.1 > y) && x < (b.0 - a.0) * (y - a.1) / (b.1 - a.1) + a.0 {
                    odd = !odd;
                }
                j = i;
            }
        }
        if odd { 1.0 } else { 0.0 }
    };
    let m = paint::Mask::from_fn(c.frame(), inside);
    Ok(if feather > 0.0 { m.blur(feather) } else { m })
}

// ---- graphite and chalk ------------------------------------------------------------------------

/// `medium` 0 graphite of softness `soft` (2H = -2, HB = 0, 4B = 4), 1 black chalk.
fn lead(medium: i32, soft: f32) -> Result<paint::Lead, String> {
    match medium {
        0 => Ok(paint::Lead::graphite(soft)),
        1 => Ok(paint::Lead::chalk()),
        m => Err(format!("unknown drawing medium {m}")),
    }
}

/// Softness of a pencil grade name ("2H", "HB", "4B"); NaN when it is not one.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_pencil_softness(grade: *const c_char) -> f32 {
    guard(f32::NAN, || {
        let g = unsafe { std::ffi::CStr::from_ptr(grade) }.to_str().map_err(|e| e.to_string())?;
        Ok(paint::graphite::softness(g).unwrap_or(f32::NAN))
    })
}

pub(crate) fn points<'a>(xy: *const f32, n: i32) -> Result<Vec<(f32, f32)>, String> {
    if n < 2 {
        return Err(format!("a line needs two points, got {n}"));
    }
    let xy = unsafe { std::slice::from_raw_parts(xy, 2 * n as usize) };
    let pts: Vec<(f32, f32)> = xy.chunks_exact(2).map(|p| (p[0], p[1])).collect();
    if pts.iter().any(|p| !(p.0.is_finite() && p.1.is_finite())) {
        return Err("non-finite point in line".into());
    }
    Ok(pts)
}

/// Draw one hand line through `n` points: pressure from `profile` (evenly spaced stations),
/// `tremor` units of sideways wobble, Catmull-Rom smoothed unless `ruler`. `worn_mm` is how far
/// the point has drawn since it was sharpened. Returns the mm this line drew (negative on error).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_draw_line(
    c: *mut Studio, medium: i32, soft: f32, worn_mm: f32,
    xy: *const f32, n: i32, profile: *const f32, profile_n: i32,
    tremor: f32, smooth: i32, ruler: i32, seed: u64,
) -> f32 {
    guard(-1.0, || {
        let l = lead(medium, soft)?;
        let pts = points(xy, n)?;
        let prof = if profile_n > 0 { unsafe { std::slice::from_raw_parts(profile, profile_n as usize) }.to_vec() } else { vec![0.6] };
        let world = unsafe { &mut *c }.canvas.take_arc_units(pts.len());
        let mark = paint::graphite::hand_line_world(&pts, world.as_deref(), &prof, smooth != 0, ruler != 0, tremor.max(0.0), seed);
        Ok(unsafe { &mut *c }.oil(|c| c.draw(&l, &mark, worn_mm.max(0.0), seed)))
    })
}

/// Sketch along `n` points: `passes` light strokes, each wandering ~`wander` units off the path and
/// broken into pieces. Returns the mm drawn.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_sketch_line(
    c: *mut Studio, medium: i32, soft: f32, worn_mm: f32,
    xy: *const f32, n: i32, pressure: f32, passes: i32, wander: f32, tremor: f32, seed: u64,
) -> f32 {
    guard(-1.0, || {
        let l = lead(medium, soft)?;
        let pts = points(xy, n)?;
        let world = unsafe { &mut *c }.canvas.take_arc_units(pts.len());
        let marks = paint::graphite::sketch_marks_world(&pts, world.as_deref(), pressure.clamp(0.0, 1.0), passes.max(1) as usize, wander.max(0.0), true, tremor.max(0.0), seed);
        let mut worn = worn_mm.max(0.0);
        unsafe { &mut *c }.oil(|c| {
            for (k, m) in marks.iter().enumerate() {
                worn += c.draw(&l, m, worn, seed.wrapping_add(k as u64 * 7919));
            }
        });
        Ok(worn - worn_mm.max(0.0))
    })
}

/// Hatch a region with short parallel strokes at `angle` (radians), `spacing` units apart, each
/// at most `length` units. Returns the mm drawn.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_hatch(
    c: *mut Studio, medium: i32, soft: f32, worn_mm: f32,
    xy: *const f32, ring_sizes: *const i32, rings: i32,
    angle: f32, spacing: f32, length: f32, pressure: f32, seed: u64,
) -> f32 {
    guard(-1.0, || {
        let l = lead(medium, soft)?;
        if !(spacing > 0.0 && length > 0.0) {
            return Err(format!("hatching needs positive spacing and length, got {spacing} and {length}"));
        }
        let c = unsafe { &mut *c };
        let m = region(c, xy, ring_sizes, rings, 0.0)?;
        let marks = paint::graphite::hatch_marks(&m, angle, spacing, length, pressure.clamp(0.0, 1.0), seed);
        let mut worn = worn_mm.max(0.0);
        c.oil(|c| {
            for (k, mk) in marks.iter().enumerate() {
                worn += c.draw(&l, mk, worn, seed.wrapping_add(k as u64 * 7919));
            }
        });
        Ok(worn - worn_mm.max(0.0))
    })
}

/// A kneaded eraser over a region: lifts `strength` (0..1) of the loose drawing there.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_erase(c: *mut Studio, xy: *const f32, ring_sizes: *const i32, rings: i32, feather: f32, strength: f32) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        let m = region(c, xy, ring_sizes, rings, feather)?;
        c.erase(&m, strength);
        Ok(0)
    })
}

/// Fixative over the whole canvas: the drawing can no longer be erased.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_fix_drawing(c: *mut Studio) -> i32 {
    guard(-1, || {
        unsafe { &mut *c }.fix_drawing(None);
        Ok(0)
    })
}

// ---- stipple -----------------------------------------------------------------------------------

/// Stipple a region with touches of a stippling brush `size` units wide: `coverage` touches per
/// point on average, paint of masstone (r,g,b) with `hiding`/`stiff`, peak pressure in p0..p1,
/// `cluster` 0 even .. 1 clumped, `drag` units of drift per touch, a dip every `dip_every` touches.
/// `feather` softens the region's edge (units), so coverage thins out towards it.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_stipple(
    c: *mut Studio, xy: *const f32, ring_sizes: *const i32, rings: i32, feather: f32,
    size: f32, coverage: f32, r: f32, g: f32, b: f32, hiding: f32, stiff: f32,
    p0: f32, p1: f32, cluster: f32, drag: f32, dip_every: i32, seed: u64,
) -> i32 {
    guard(-1, || {
        if !(size.is_finite() && size > 0.0) {
            return Err(format!("bad stipple size {size}"));
        }
        let c = unsafe { &mut *c };
        let m = region(c, xy, ring_sizes, rings, feather)?;
        let cov = coverage.max(0.0);
        let sp = paint::Stipple::new(Tool::stippler(size))
            .coverage(move |_, _| cov)
            .color(move |_, _| [r, g, b])
            .paint(hiding.clamp(0.001, 0.999), stiff.clamp(0.0, 1.0))
            .pressure(p0.clamp(0.0, 1.0), p1.clamp(0.0, 1.0))
            .cluster(cluster.clamp(0.0, 1.0), None)
            .drag(drag.max(0.0), None)
            .dips(dip_every.max(1) as usize, 0.5, 0.5);
        c.oil(|c| c.stipple(&m, &sp, seed));
        Ok(0)
    })
}

// ---- touches, glazes, worked regions ----------------------------------------------------------

/// One touch of the brush tip at (x, y): pressed with `pressure`, drifting (dx, dy) while down,
/// the handle rolled `twist` radians, the wide axis at `angle` radians.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_touch(c: *mut Studio, h: *mut Held, x: f32, y: f32, pressure: f32, dx: f32, dy: f32, twist: f32, angle: f32) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        let p = if c.engine() <= 2 { pressure.clamp(0.0, 1.0) } else { pressure.max(0.0) };
        let t = paint::Touch::at(x, y).pressure(p).drag(dx, dy).twist(twist).angle(angle);
        c.oil(|c| c.touch(unsafe { &mut *h }, &t, None));
        Ok(0)
    })
}

/// A Kubelka-Munk glaze of (r,g,b) at `hiding`, `coats` thick, over a region (rings = 0: the whole
/// canvas). It dries at once; the paint under it should be dry.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_glaze(
    c: *mut Studio, xy: *const f32, ring_sizes: *const i32, rings: i32, feather: f32,
    r: f32, g: f32, b: f32, hiding: f32, coats: f32,
) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        let m = if rings > 0 { Some(region(c, xy, ring_sizes, rings, feather)?) } else { None };
        let pig = paint::Pigment::with_hiding([r, g, b], hiding.clamp(0.001, 0.999));
        let k = coats.max(0.0);
        // a glaze lays into the dry picture itself: the watercolour under it is sealed first
        c.seal_first(m.as_ref().map(|m| m.data.iter().map(|&v| v > 0.0).collect()));
        c.glaze(&pig, m.as_ref(), move |_, _| k);
        Ok(0)
    })
}

/// Work a region with many strokes, the way the crate's painters block in an area. `preset`:
/// 0 broad, 1 body, 2 detail, 3 hatch, 4 glaze, 5 scumble, 6 blend (a clean blender that only
/// moves the wet paint already there). Overrides are ignored when NaN (or < 0 for the ints):
/// stroke length range, coverage, pressure range, `cross` angle. `order` 0 passages, 1 sweep
/// along `sweep`, 2 scatter; `clip` 1 stops bristles at the region's edge.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_work(
    c: *mut Studio, xy: *const f32, ring_sizes: *const i32, rings: i32, feather: f32,
    preset: i32, r: f32, g: f32, b: f32, hiding: f32, stiff: f32, angle: f32, angle_jitter: f32,
    len0: f32, len1: f32, coverage: f32, p0: f32, p1: f32, cross: f32,
    order: i32, sweep: f32, clip: i32, width: f32, seed: u64,
) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        let m = region(c, xy, ring_sizes, rings, feather)?;
        let style = Style::oil();
        let mut hd = match preset {
            0 => style.broad(),
            1 => style.body(),
            2 => style.detail(),
            3 => style.hatch(),
            4 => style.glaze(),
            5 => style.scumble(),
            6 => style.blend().ok_or("this style has no blender")?,
            p => return Err(format!("unknown handling preset {p}")),
        };
        if width.is_finite() && width > 0.0 {
            let scale = width / hd.tool.width;
            hd.tool.width = width;
            hd.tool.length *= scale;
        }
        hd = hd.color(move |_, _| [r, g, b]).paint(hiding.clamp(0.001, 0.999), stiff.clamp(0.0, 1.0));
        if angle.is_finite() {
            hd = hd.angle(move |_, _| angle);
        }
        if angle_jitter.is_finite() {
            hd = hd.angle_jitter(angle_jitter);
        }
        if len0.is_finite() && len1.is_finite() {
            hd = hd.length(len0.max(0.5), len1.max(len0.max(0.5)));
        }
        if coverage.is_finite() {
            hd = hd.coverage(coverage.max(0.0));
        }
        if p0.is_finite() && p1.is_finite() {
            hd = hd.pressure(p0.clamp(0.0, 1.0), p1.clamp(0.0, 1.0));
        }
        if cross.is_finite() {
            hd = hd.cross(cross);
        }
        match order {
            0 => hd = hd.order(paint::Order::Passages),
            1 => hd = hd.sweep(sweep),
            2 => hd = hd.order(paint::Order::Scatter),
            _ => {}
        }
        if clip >= 0 {
            hd = hd.clip(clip != 0);
        }
        c.oil(|c| c.work(&m, &hd, seed));
        Ok(0)
    })
}

// ---- outlines ----------------------------------------------------------------------------------

fn character(ch: i32, amount: f32) -> Result<paint::Character, String> {
    let c = match ch {
        0 => paint::Character::firm(),
        1 => paint::Character::searching(),
        2 => paint::Character::broken(),
        3 => paint::Character::soft(),
        k => return Err(format!("unknown outline character {k}")),
    };
    Ok(if amount.is_finite() { c.amount(amount.max(0.0)) } else { c })
}

/// Floats handed to the JVM; free with `field_paint_free_floats`.
pub(crate) fn hand_out(v: Vec<f32>, out: *mut *mut f32, out_len: *mut i64) {
    let mut b = v.into_boxed_slice();
    unsafe {
        *out_len = b.len() as i64;
        *out = b.as_mut_ptr();
    }
    std::mem::forget(b);
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_free_floats(p: *mut f32, len: i64) {
    if !p.is_null() {
        unsafe { drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(p, len as usize))) }
    }
}

/// A hand-drawn outline through `n` rough points (`closed`, corners decided by angle) with
/// character `ch` (0 firm, 1 searching, 2 broken, 3 soft) scaled by `amount` (NaN: as is). If `h`
/// is not null, the brush is dragged along the outline's strokes at `pressure`. The strokes come
/// back in `out` as `[count, n_0 .. n_{count-1}, x, y, ...]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_outline(
    c: *mut Studio, h: *mut Held, xy: *const f32, n: i32, closed: i32, ch: i32, amount: f32,
    pressure: f32, size: f32, seed: u64, out: *mut *mut f32, out_len: *mut i64,
) -> i32 {
    guard(-1, || {
        let pts = points(xy, n)?;
        let o = paint::Outline::draw(&pts, &[], closed != 0, character(ch, amount)?, seed, if size > 0.0 { Some(size) } else { None });
        if !h.is_null() {
            unsafe { &mut *c }.oil(|c| o.paint(c, unsafe { &mut *h }, pressure.clamp(0.0, 1.0), None));
        }
        let strokes: Vec<&Vec<(f32, f32)>> = o.strokes.iter().map(|s| &s.pts).filter(|p| p.len() >= 2).collect();
        let mut v = vec![strokes.len() as f32];
        v.extend(strokes.iter().map(|p| p.len() as f32));
        for p in &strokes {
            for &(x, y) in p.iter() {
                v.push(x);
                v.push(y);
            }
        }
        hand_out(v, out, out_len);
        Ok(0)
    })
}

// ---- age ---------------------------------------------------------------------------------------

/// Craquelure: `kind` 0 aged (a hierarchy of cracks, patchy, with grime), 1 even (one uniform
/// network). Fitted to the canvas's ground thickness. Dries the canvas first.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_crack(c: *mut Studio, kind: i32, seed: u64) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        c.dry();
        c.seal_first(None);
        let k = match kind {
            0 => paint::Cracks::aged(seed),
            1 => paint::Cracks::even(seed),
            k => return Err(format!("unknown crack kind {k}")),
        };
        let um = c.ground_um().max(60.0);
        c.crack(&k.fit(um));
        Ok(0)
    })
}

/// A ground layer over everything: `um` micrometres of (r,g,b) at `hiding`, body `stiff`, roughened
/// by `texture` before it levels.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_prime(c: *mut Studio, r: f32, g: f32, b: f32, hiding: f32, um: f32, stiff: f32, texture: f32, seed: u64) -> i32 {
    guard(-1, || {
        let c = unsafe { &mut *c };
        c.seal_first(None);
        c.prime([r, g, b], hiding.clamp(0.001, 0.999), um.max(0.0), stiff.clamp(0.0, 1.0), texture.clamp(0.0, 1.0), seed);
        Ok(0)
    })
}

// ---- tube paints and mixing --------------------------------------------------------------------

/// The tube catalogue as text, one tube per line:
/// `name \t pigment \t r \t g \t b \t hiding \t stiff \t strength \t drying` (linear RGB).
/// Then a line `boxes \t name \t name ...` naming the built-in palettes. Free it with
/// `field_paint_free_string`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_tubes() -> *mut c_char {
    guard(std::ptr::null_mut(), || {
        let mut s = String::new();
        for t in paint::palette::catalog() {
            s += &format!("{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n", t.name, t.pigment, t.color[0], t.color[1], t.color[2], t.hiding, t.stiff, t.strength, t.drying);
        }
        s += "boxes";
        for b in Palette::box_names() {
            s += "\t";
            s += b;
        }
        Ok(CString::new(s).map_err(|e| e.to_string())?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn field_paint_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe { drop(CString::from_raw(p)) }
    }
}

/// Mix tubes the way the palette does (Mixbox, weighted by tinting strength): `names` separated
/// by newlines, `parts` by volume. Writes the pile's masstone (linear), scatter, stiffness and
/// drying rate into `out[0..6]` after thinning with `medium` (0..1).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_mix_tubes(names: *const c_char, parts: *const f32, n: i32, medium: f32, out: *mut f32) -> i32 {
    guard(-1, || {
        let names = unsafe { std::ffi::CStr::from_ptr(names) }.to_str().map_err(|e| e.to_string())?;
        let parts = unsafe { std::slice::from_raw_parts(parts, n.max(0) as usize) };
        let names: Vec<&str> = names.split('\n').collect();
        if names.len() != parts.len() || names.is_empty() {
            return Err(format!("{} tube names for {} parts", names.len(), parts.len()));
        }
        let pal = Palette::new("field", paint::palette::catalog());
        let mut ps = Vec::new();
        for (nm, &f) in names.iter().zip(parts) {
            let i = pal.tubes.iter().position(|t| t.name == *nm).ok_or_else(|| format!("no tube named {nm:?}"))?;
            ps.push((i, f.max(0.0)));
        }
        let sum: f32 = ps.iter().map(|p| p.1).sum();
        if sum <= 0.0 {
            return Err("tube parts sum to zero".into());
        }
        ps.iter_mut().for_each(|p| p.1 /= sum);
        let paint = pal.pile(ps).laid(medium.clamp(0.0, 1.0));
        let o = unsafe { std::slice::from_raw_parts_mut(out, 6) };
        o.copy_from_slice(&[paint.color[0], paint.color[1], paint.color[2], paint.scatter, paint.stiff, paint.drying]);
        Ok(0)
    })
}

/// Load the brush with paint given by its Kubelka-Munk scattering rather than hiding (what
/// `field_paint_mix_tubes` returns).
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_brush_load_km(h: *mut Held, r: f32, g: f32, b: f32, scatter: f32, stiff: f32, drying: f32, amount: f32, wipe: f32) -> i32 {
    guard(-1, || {
        let h = unsafe { &mut *h };
        if wipe > 0.0 {
            h.wipe(wipe);
        }
        h.load(Paint::km([r, g, b], scatter.max(1e-4), stiff.clamp(0.0, 1.0)).with_drying(drying.max(0.01)), amount.clamp(0.0, 1.0));
        Ok(0)
    })
}

/// Spectral (per-wavelength Kubelka-Munk) mix of `n` linear colours by weight, into `out[0..3]`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_mix_spectral(rgb: *const f32, weights: *const f32, n: i32, out: *mut f32) -> i32 {
    guard(-1, || {
        if n < 1 {
            return Err("nothing to mix".into());
        }
        let rgb = unsafe { std::slice::from_raw_parts(rgb, 3 * n as usize) };
        let w = unsafe { std::slice::from_raw_parts(weights, n as usize) };
        let cs: Vec<([f32; 3], f32)> = rgb.chunks_exact(3).zip(w).map(|(c, &k)| ([c[0], c[1], c[2]], k.max(0.0))).collect();
        let m = paint::spectral::mix_ks(&cs);
        unsafe { std::slice::from_raw_parts_mut(out, 3) }.copy_from_slice(&m);
        Ok(0)
    })
}

// ---- state -------------------------------------------------------------------------------------

/// An independent copy of the canvas, wet paint and all.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_canvas_copy(c: *const Studio) -> *mut Studio {
    guard(std::ptr::null_mut(), || Ok(Box::into_raw(Box::new(unsafe { &*c }.clone()))))
}

/// The canvas as it looks now, wet paint included at its laid thickness, as sRGB ARGB.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_render_wet_argb(c: *const Studio, out: *mut i32, capacity: i32) -> i32 {
    guard(-1, || {
        write_argb(&unsafe { &*c }.seen_now(), out, capacity)
    })
}

/// Clock, wet volume and drying: `out` gets `[clock minutes, hand minutes, open, setting, tacky,
/// dry]`, the last four the share of the canvas at each stage.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_stats(c: *const Studio, out: *mut f64) -> i32 {
    guard(-1, || {
        let c = unsafe { &*c };
        let s = c.stage_shares();
        unsafe { std::slice::from_raw_parts_mut(out, 6) }.copy_from_slice(&[c.clock(), c.tally().minutes(), s[0], s[1], s[2], s[3]]);
        Ok(0)
    })
}

/// Write the whole canvas state, wet paint included, to `path`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_save_state(c: *const Studio, path: *const c_char) -> i32 {
    guard(-1, || {
        let p = unsafe { std::ffi::CStr::from_ptr(path) }.to_str().map_err(|e| e.to_string())?;
        let mut f = std::io::BufWriter::new(std::fs::File::create(p).map_err(|e| format!("{p}: {e}"))?);
        unsafe { &*c }.write_state(&mut f).map_err(|e| e.to_string())?;
        Ok(0)
    })
}

/// Read a canvas written by `field_paint_save_state`.
#[unsafe(no_mangle)]
pub extern "C" fn field_paint_load_state(path: *const c_char) -> *mut Studio {
    guard(std::ptr::null_mut(), || {
        let p = unsafe { std::ffi::CStr::from_ptr(path) }.to_str().map_err(|e| e.to_string())?;
        let mut f = std::io::BufReader::new(std::fs::File::open(p).map_err(|e| format!("{p}: {e}"))?);
        Ok(Box::into_raw(Box::new(Studio::read_state(&mut f)?)))
    })
}
