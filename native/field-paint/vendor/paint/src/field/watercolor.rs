//! A watercolour brush, and what ties a sheet's water (`aqueous::Water`) to the canvas it lies on.
//!
//! # The brush
//!
//! A brush's belly is a reservoir: `water_mm3` of water, at most `capacity_mm3`, and µg of each
//! pigment in it. Where it touches the sheet, water flows between the belly and the sheet by
//! capillary suction, from the wetter to the drier: `exchange_um_s × (fill − wetness) × contact`
//! µm per second of contact, `fill` the belly's share of its capacity and `wetness` the sheet's
//! (surface water over `WET_REF`, or the pores' saturation). Flowing out, the water carries the
//! belly's pigment at its concentration; flowing in (a thirsty brush), it carries the surface's
//! suspended pigment back up. How long each pixel is touched is how long the moving footprint
//! stays over it: a fast stroke lays less than a slow one, by the same law.
//!
//! **Contact** is where the brush reaches the paper: its tip rests on the highest relief under the
//! footprint and reaches down into the hollows by a depth that grows with pressure (the hair) and,
//! with the square of how full it is, with the belly's liquid bridging the hollows (a full brush's
//! meniscus fills them; a nearly dry one touches only the tops). Water already standing on the paper wets the hair wherever it is. A light, fast stroke of
//! a nearly dry brush on rough paper therefore touches only the tooth's peaks: drybrush comes out of
//! the paper's relief, not a texture.
//!
//! The footprint here is a disc whose radius grows with pressure: a whole brush, not hairs. The
//! segmented bristles of `field::bristles` (stage 3) replace it with each hair's own contact.
//!
//! # Paint over watercolour
//!
//! Oil paint, graphite and a knife's slab laid over the sheet seal it: `Studio`'s caller finds the
//! pixels a canvas operation changed (`snapshot` and `changed`), takes the water's pigment there out
//! of the water (`Water::seal`, counted in the ledger as sealed) and lays it into the canvas's dry
//! picture under the paint (`bake`). Those pixels no longer take up water.

use super::aqueous::{km_layer, AqPigment, Paper, Water};
use crate::{Canvas, Rgb};

/// Standing water a brush's liquid can meet and mix with, µm.
const W_DRY_TOUCH: f32 = 2.0;
/// Surface water at which a pixel counts as fully wet to a brush, µm.
pub const WET_REF: f32 = 80.0;
/// Capillary exchange between a brush and the sheet, µm/s at full contact and full imbalance.
pub const EXCHANGE_UM_S: f32 = 600.0;
/// How far below the highest point under it a brush's hair reaches into the paper's hollows at
/// pressure 1, µm (an estimate of a soft brush's give: the hair's depth is the same on any paper,
/// so a light touch reaches every hollow of a smooth sheet and only the peaks of a rough one).
pub const HAIR_REACH_UM: f32 = 25.0;
/// How far a full belly's liquid reaches into the hollows beyond the hair, µm.
pub const MENISCUS_UM: f32 = 250.0;
/// Where the belly's liquid and the water on the sheet touch they mix: this much of each, µm per
/// second of full contact, trades places (no net water moves; pigment goes from the richer to the
/// leaner). It is how a loaded brush charges a wet wash, and how a damp brush takes colour back.
pub const MIX_UM_S: f32 = 300.0;
/// A moving brush wipes its liquid onto whatever its hair touches, as a film drawn off a moving
/// surface is: µm laid per pass at full contact from a full brush (falls as fill^1.5). This is what
/// lays paint on the peaks in drybrush, where capillary exchange in a fast pass is negligible.
pub const WIPE_UM: f32 = 35.0;

/// A watercolour brush: its size, its reservoir and the paint in it.
#[derive(Clone, Debug)]
pub struct WaterBrush {
    /// Footprint diameter at full pressure, canvas units.
    pub width: f32,
    /// Water the belly holds full, mm³.
    pub capacity_mm3: f32,
    pub water_mm3: f32,
    /// µg of each pigment in the belly.
    pub pigment: Vec<(AqPigment, f32)>,
    /// How hard the hair scrubs a deposit back into the water under it, per second of contact (a few
    /// seconds of scrubbing lift most of a loose deposit).
    pub scrub: f32,
}

impl WaterBrush {
    /// A round watercolour brush `width` units across on a canvas of `mm_per_unit`: its belly holds
    /// about 0.6 d³ mm³ for a d mm brush (an estimate: a 6 mm round holds some 0.13 ml).
    pub fn new(width: f32, mm_per_unit: f32) -> WaterBrush {
        let d = width * mm_per_unit;
        WaterBrush { width, capacity_mm3: 0.6 * d * d * d, water_mm3: 0.0, pigment: vec![], scrub: 5.0 }
    }

    /// Dip it: `wipe` (0..1) of what is in the belly is squeezed out first, then it takes up water
    /// to `fill` of its capacity, with `paint` dissolved at its concentrations (µg per mm³ of water).
    pub fn load(&mut self, paint: &[(AqPigment, f32)], fill: f32, wipe: f32) {
        let keep = 1.0 - wipe.clamp(0.0, 1.0);
        self.water_mm3 *= keep;
        self.pigment.iter_mut().for_each(|p| p.1 *= keep);
        let add = (fill * self.capacity_mm3 - self.water_mm3).max(0.0);
        self.water_mm3 += add;
        for (p, conc) in paint {
            let m = add * conc.max(0.0);
            match self.pigment.iter_mut().find(|q| q.0 == *p) {
                Some(q) => q.1 += m,
                None => self.pigment.push((p.clone(), m)),
            }
        }
        self.pigment.retain(|p| p.1 > 0.0);
    }

    /// Touch it to a towel: the belly loses `share` of its water and the pigment in it.
    pub fn blot(&mut self, share: f32) {
        let k = 1.0 - share.clamp(0.0, 1.0);
        self.water_mm3 *= k;
        self.pigment.iter_mut().for_each(|p| p.1 *= k);
    }

    /// The belly's share of its capacity.
    pub fn fill(&self) -> f32 {
        if self.capacity_mm3 > 0.0 { self.water_mm3 / self.capacity_mm3 } else { 0.0 }
    }
}

/// One sample of a brush's movement: where (units), when (s since the stroke began), how hard (0..).
#[derive(Clone, Copy, Debug)]
pub struct Sample {
    pub x: f32,
    pub y: f32,
    pub t: f32,
    pub pressure: f32,
}

/// What a stroke did, mm³ and µg.
#[derive(Clone, Debug, Default)]
pub struct StrokeReport {
    pub water_laid: f64,
    pub water_taken: f64,
    pub pigment_laid: f64,
    pub pigment_taken: f64,
    pub seconds: f64,
}

/// Drag `brush` through `samples` over `water`, lettting the sheet's water move as the stroke goes.
/// `units_per_px` is the canvas's units per pixel.
pub fn stroke(water: &mut Water, brush: &mut WaterBrush, samples: &[Sample], units_per_px: f32) -> StrokeReport {
    let mut rep = StrokeReport::default();
    if samples.is_empty() {
        return rep;
    }
    let (w, h) = (water.w, water.h);
    // pigment species of the brush, as the sheet numbers them
    let mut ids: Vec<usize> = Vec::new();
    for (p, _) in &brush.pigment {
        match water.species_index(p) {
            Ok(i) => ids.push(i),
            Err(_) => ids.push(usize::MAX),
        }
    }
    let mut since_flow = 0.0f32;
    // walk the path in steps of half a pixel
    let step_units = 0.5 * units_per_px;
    let mut pts: Vec<Sample> = vec![samples[0]];
    for s in samples.windows(2) {
        let (a, b) = (s[0], s[1]);
        let d = ((b.x - a.x).powi(2) + (b.y - a.y).powi(2)).sqrt();
        let n = (d / step_units).ceil().max(1.0) as usize;
        for k in 1..=n {
            let u = k as f32 / n as f32;
            pts.push(Sample { x: a.x + (b.x - a.x) * u, y: a.y + (b.y - a.y) * u, t: a.t + (b.t - a.t) * u, pressure: a.pressure + (b.pressure - a.pressure) * u });
        }
    }
    for k in 0..pts.len() {
        let s = pts[k];
        let dt = if k == 0 { 0.0 } else { (s.t - pts[k - 1].t).max(0.0) };
        let p = s.pressure.max(0.0);
        if p <= 0.0 {
            since_flow += dt;
            continue;
        }
        let r_px = 0.5 * brush.width / units_per_px * (0.35 + 0.65 * p.powf(0.6));
        let (cx, cy) = (s.x / units_per_px, s.y / units_per_px);
        // how long the footprint dwells over a pixel: its diameter over its speed
        let speed = if dt > 0.0 && k > 0 {
            ((s.x - pts[k - 1].x).powi(2) + (s.y - pts[k - 1].y).powi(2)).sqrt() / units_per_px / dt
        } else {
            0.0
        };
        // a step's share of the dwell: the footprint advances half a pixel, so each pixel under it
        // gets this step's time
        let tau = if dt > 0.0 { dt } else if speed == 0.0 { 0.02 } else { 0.0 };
        let moving = k > 0 && speed > 0.0;
        let step_px = if k > 0 { ((s.x - pts[k - 1].x).powi(2) + (s.y - pts[k - 1].y).powi(2)).sqrt() / units_per_px } else { 0.0 };
        let ri = r_px.ceil() as isize + 1;
        let (x0, y0) = (cx as isize, cy as isize);
        let mut top = f32::MIN;
        for j in -ri..=ri {
            for i in -ri..=ri {
                let (x, y) = (x0 + i, y0 + j);
                if x < 0 || y < 0 || x >= w as isize || y >= h as isize {
                    continue;
                }
                if (x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2) <= r_px * r_px {
                    top = top.max(water.relief[y as usize * w + x as usize]);
                }
            }
        }
        if top == f32::MIN {
            since_flow += dt;
            continue;
        }
        let reach = reach_um(p, brush.fill());
        // the pixels under the footprint, each with how fully the brush touches it and the share of
        // the footprint's passage this step is
        let pass = if r_px > 0.0 { (step_px / (2.0 * r_px)).min(1.0) } else { 0.0 };
        let mut cells: Vec<(usize, f32, f32)> = Vec::new();
        for j in -ri..=ri {
            for i in -ri..=ri {
                let (x, y) = (x0 + i, y0 + j);
                if x < 0 || y < 0 || x >= w as isize || y >= h as isize {
                    continue;
                }
                let d2 = (x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2);
                if d2 > r_px * r_px {
                    continue;
                }
                let idx = y as usize * w + x as usize;
                // soft rim: the footprint's edge touches less
                let rim = 1.0 - crate::smoothstep(0.7, 1.0, (d2.sqrt() / r_px.max(1e-3)).min(1.0));
                let contact = rim * touch(water, idx, top, reach);
                if contact > 0.0 {
                    cells.push((idx, contact, pass));
                }
            }
        }
        exchange_cells(water, brush, &mut ids, &cells, tau, moving, &mut rep);
        since_flow += dt;
        // the water on the sheet keeps moving while the brush goes on
        if since_flow >= 0.02 {
            water.advance(since_flow as f64);
            since_flow = 0.0;
        }
    }
    if since_flow > 0.0 {
        water.advance(since_flow as f64);
    }
    rep.seconds = (pts.last().unwrap().t - pts[0].t) as f64;
    rep
}

/// How far below the highest relief under it a brush at pressure `p` and `fill` reaches, µm: the
/// hair deeper pressed harder, and a full belly's liquid bridging the hollows whatever the hair
/// touches (fill²: a nearly dry brush has no meniscus to speak of).
pub fn reach_um(p: f32, fill: f32) -> f32 {
    HAIR_REACH_UM * (0.3 + 3.0 * p) * (0.25 + 0.75 * fill.min(1.0).sqrt()) + MENISCUS_UM * fill.min(1.0).powi(2)
}

/// How fully a brush whose lowest reach is `reach` µm below `top` (the highest relief under it)
/// touches pixel `idx`: water already standing there wets the hair wherever it is.
pub fn touch(water: &Water, idx: usize, top: f32, reach: f32) -> f32 {
    if water.sw[idx] > water.paper.pin_um {
        return 1.0;
    }
    ((water.relief[idx] - (top - reach)) / (0.3 * reach).max(1.0)).clamp(0.0, 1.0)
}

/// Trade water and pigment between a brush's belly and the pixels it touches for `tau` seconds:
/// `cells` are (pixel, how fully touched 0..1, the share of the brush's passage over the pixel this
/// is). Capillary exchange by time, the hair's wipe by distance (when `moving`), mixing where the
/// belly's liquid meets standing water, scrubbing of the deposit under a moving brush. `ids` maps the
/// brush's pigments to the sheet's species (grown as the brush takes up new ones).
pub fn exchange_cells(water: &mut Water, brush: &mut WaterBrush, ids: &mut Vec<usize>, cells: &[(usize, f32, f32)], tau: f32, moving: bool, rep: &mut StrokeReport) {
    let area = water.px_area();
    while ids.len() < brush.pigment.len() {
        let p = brush.pigment[ids.len()].0.clone();
        ids.push(water.species_index(&p).unwrap_or(usize::MAX));
    }
    let fill = brush.fill();
    let water_conc: Vec<f32> = brush.pigment.iter().map(|q| if brush.water_mm3 > 0.0 { q.1 / brush.water_mm3 } else { 0.0 }).collect();
    let mut out_mm3 = 0.0f32;
    let mut out_pig = vec![0.0f32; brush.pigment.len()];
    let mut in_mm3 = 0.0f32;
    let mut in_pig: Vec<(usize, f32)> = Vec::new();
    // first what every touched pixel would exchange, then the belly's limit on it
    let mut ex: Vec<(usize, f32, f32)> = Vec::with_capacity(cells.len());
    let mut ask = 0.0f32;
    for &(idx, contact, pass) in cells {
        let wetness = (water.sw[idx] / WET_REF).max(water.aw[idx] / water.cap[idx].max(1e-3)).min(1.0);
        let wipe = if moving { WIPE_UM * fill.min(1.0).powf(1.5) * contact * pass } else { 0.0 };
        let j_um = EXCHANGE_UM_S * (fill - wetness) * contact * tau + wipe;
        if j_um > 0.0 {
            ask += j_um * area as f32 * 1e-3;
        }
        ex.push((idx, contact, j_um));
    }
    // the belly gives no more than it holds
    let give = if ask > brush.water_mm3 && ask > 0.0 { brush.water_mm3 / ask } else { 1.0 };
    // mixing where the belly's liquid meets standing water: equal volumes trade places
    let mut mix_out = vec![0.0f32; brush.pigment.len()];
    let mut mix_in: Vec<(usize, f32)> = Vec::new();
    if brush.water_mm3 > 0.0 {
        for &(idx, contact, _) in &ex {
            let standing = water.sw[idx];
            if standing <= W_DRY_TOUCH {
                continue;
            }
            // µm traded: no more than either side can spare
            let v = (MIX_UM_S * contact * tau).min(0.5 * standing).min(0.5 * brush.water_mm3 / area as f32 * 1e3);
            if v <= 0.0 {
                continue;
            }
            let mm3 = v * area as f32 * 1e-3;
            rep.water_laid += mm3 as f64;
            rep.water_taken += mm3 as f64;
            // out: the belly's pigment at its concentration
            let masses: Vec<(usize, f32)> = ids.iter().zip(&water_conc).enumerate()
                .filter(|(_, (id, _))| **id != usize::MAX)
                .map(|(q, (&id, &c))| {
                    let m = c * mm3 / area as f32;
                    mix_out[q] += m * area as f32;
                    (id, m)
                })
                .collect();
            // in: the same volume of the standing water, with what floats in it
            let (_, taken) = water.take(idx, v / standing);
            water.add(idx, v, &masses);
            for (sp, m) in taken.into_iter().enumerate() {
                if m > 0.0 {
                    mix_in.push((sp, m * area as f32));
                }
            }
        }
    }
    for (idx, contact, j_um) in ex {
        if j_um > 0.0 {
            let um = j_um * give;
            let mm3 = um * area as f32 * 1e-3;
            out_mm3 += mm3;
            let masses: Vec<(usize, f32)> = ids.iter().zip(&water_conc).enumerate()
                .filter(|(_, (id, _))| **id != usize::MAX)
                .map(|(q, (&id, &c))| {
                    let m = c * mm3 / area as f32;
                    out_pig[q] += m * area as f32;
                    (id, m)
                })
                .collect();
            water.add(idx, um, &masses);
        } else if j_um < 0.0 && water.sw[idx] > 0.0 {
            let share = (-j_um / water.sw[idx]).min(1.0);
            let (um, ms) = water.take(idx, share);
            in_mm3 += um * area as f32 * 1e-3;
            for (sp, m) in ms.into_iter().enumerate() {
                if m > 0.0 {
                    in_pig.push((sp, m * area as f32));
                }
            }
        }
        // a moving brush works the deposit under it back into the water
        if moving {
            water.agitate(idx, brush.scrub * contact, tau);
        }
    }
    brush.water_mm3 = (brush.water_mm3 - out_mm3 + in_mm3).max(0.0);
    for (q, m) in out_pig.iter().enumerate() {
        brush.pigment[q].1 = (brush.pigment[q].1 - m - mix_out[q]).max(0.0);
    }
    rep.pigment_laid += mix_out.iter().map(|&m| m as f64).sum::<f64>();
    for (sp, m) in in_pig.into_iter().chain(mix_in) {
        let pig = water.species[sp].clone();
        match brush.pigment.iter_mut().find(|q| q.0 == pig) {
            Some(q) => q.1 += m,
            None => {
                brush.pigment.push((pig, m));
                ids.push(sp);
            }
        }
        rep.pigment_taken += m as f64;
    }
    rep.water_laid += out_mm3 as f64;
    rep.water_taken += in_mm3 as f64;
    rep.pigment_laid += out_pig.iter().map(|&m| m as f64).sum::<f64>();
}

/// A drop of `volume_mm3` landing at (x, y) units with `paint` in it (µg/mm³ of water): it spreads
/// on impact to a puddle about 0.4 mm deep, then the sheet and gravity take it.
pub fn drop(water: &mut Water, x: f32, y: f32, volume_mm3: f32, paint: &[(AqPigment, f32)], units_per_px: f32) -> Result<(), String> {
    let r_mm = (volume_mm3.max(0.0) / (std::f32::consts::PI * 0.4)).sqrt();
    let r_px = (r_mm / water.dx_mm).max(0.5);
    let ids: Vec<(usize, f32)> = paint.iter().map(|(p, c)| water.species_index(p).map(|i| (i, *c))).collect::<Result<_, _>>()?;
    let (cx, cy) = (x / units_per_px, y / units_per_px);
    let mut cells = Vec::new();
    let ri = r_px.ceil() as isize + 1;
    for j in -ri..=ri {
        for i in -ri..=ri {
            let (px, py) = (cx as isize + i, cy as isize + j);
            if px < 0 || py < 0 || px >= water.w as isize || py >= water.h as isize {
                continue;
            }
            let d2 = (px as f32 + 0.5 - cx).powi(2) + (py as f32 + 0.5 - cy).powi(2);
            if d2 <= r_px * r_px {
                // a dome: deepest in the middle
                cells.push((py as usize * water.w + px as usize, 1.0 - d2 / (r_px * r_px)));
            }
        }
    }
    if cells.is_empty() {
        return Err("the drop landed off the sheet".into());
    }
    let wsum: f32 = cells.iter().map(|c| c.1 + 0.2).sum();
    let area = water.px_area() as f32;
    for (i, wt) in cells {
        let mm3 = volume_mm3 * (wt + 0.2) / wsum;
        let um = mm3 / area * 1000.0;
        let masses: Vec<(usize, f32)> = ids.iter().map(|&(k, c)| (k, c * mm3 / area)).collect();
        water.add(i, um, &masses);
    }
    Ok(())
}

// ---- the canvas under the water ------------------------------------------------------------------

/// Put the paper's relief into the canvas's surface (µm, raised so it stays above zero), so that
/// pencil, oil brushes and the relief light all feel it.
pub fn emboss(c: &mut Canvas, relief: &[f32]) {
    let lo = relief.iter().copied().fold(f32::MAX, f32::min);
    for (h, &r) in c.height.iter_mut().zip(relief) {
        *h += r - lo;
    }
    c.surf_gen += 1;
}

/// Water for a canvas that is not paper: its own ground decides what the water does. A raw cloth
/// soaks it up; an absorbent chalk ground takes a little; an oil ground or paint takes none, and the
/// water beads on it. The relief is the canvas's own (water gathers in the weave).
pub fn water_for_canvas(c: &Canvas, seed: u64) -> Water {
    let f = c.frame();
    let mut paper = if let Some(fabric) = c.fabric() {
        Paper { name: format!("raw {}", fabric.name), color: fabric.color, cap_um: fabric.cap_um, absorb_um_s: 40.0, wick_mm2_s: 1.0,
            anisotropy: 1.0 + fabric.warp_bias.max(0.0), roughness_um: 0.0, ..Paper::blotting() }
    } else if c.absorb_any {
        Paper { name: "chalk ground".into(), cap_um: 30.0, absorb_um_s: 8.0, wick_mm2_s: 0.05, roughness_um: 0.0, ..Paper::hot_press() }
    } else {
        Paper { name: "sealed ground".into(), cap_um: 0.0, absorb_um_s: 0.0, wick_mm2_s: 0.0, roughness_um: 0.0, pin_um: 40.0, ..Paper::hot_press() }
    };
    paper.formation = paper.formation.max(1e-3);
    let mut w = Water::new(Paper { roughness_um: 1.0, ..paper.clone() }, f.w, f.h, c.px_mm(), seed);
    w.paper = paper;
    // the canvas's own relief, and its paint sealing it
    let mean = c.height.iter().map(|&v| v as f64).sum::<f64>() / c.height.len().max(1) as f64;
    w.relief = c.height.iter().map(|&v| v - mean as f32).collect();
    w.cap.iter_mut().for_each(|v| *v = w.paper.cap_um);
    w.perm.iter_mut().for_each(|v| *v = 1.0);
    for i in 0..w.sealed.len() {
        if c.film[i] > 0.05 || c.wet.vol[i] > 1e-4 {
            w.sealed[i] = 1.0;
        }
    }
    w
}

/// What a canvas operation can change that the water lies under or over: the dry picture, the wet
/// film and the surface.
pub struct Snapshot {
    px: Vec<Rgb>,
    vol: Vec<f32>,
    height: Vec<f32>,
}

pub fn snapshot(c: &Canvas) -> Snapshot {
    Snapshot { px: c.px.clone(), vol: c.wet.vol.clone(), height: c.height.clone() }
}

/// The pixels `c` changed since `s`.
pub fn changed(c: &Canvas, s: &Snapshot) -> Vec<bool> {
    (0..c.px.len()).map(|i| c.px[i] != s.px[i] || c.wet.vol[i] != s.vol[i] || c.height[i] != s.height[i]).collect()
}

/// Lay pigment optics (from `Water::seal`) into the canvas's dry picture, under whatever wet paint
/// is on it.
pub fn bake(c: &mut Canvas, sealed: &[(usize, Rgb, f32)]) {
    for &(i, k, s) in sealed {
        let p = c.px[i];
        c.px[i] = std::array::from_fn(|ch| km_layer(k[ch], s, p[ch]));
    }
}

/// Lay the water's pigment over the canvas's dry picture (for a render's copy: the canvas itself
/// keeps its watercolour in the water).
pub fn lay_over(c: &mut Canvas, water: &Water) {
    c.px = water.over(&c.px, false);
}

// ---- saving ---------------------------------------------------------------------------------------

struct W(Vec<u8>);
impl W {
    fn f(&mut self, v: f32) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn d(&mut self, v: f64) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn u(&mut self, v: u64) { self.0.extend_from_slice(&v.to_le_bytes()); }
    fn s(&mut self, v: &str) { self.u(v.len() as u64); self.0.extend_from_slice(v.as_bytes()); }
    fn fs(&mut self, v: &[f32]) { self.u(v.len() as u64); v.iter().for_each(|&x| self.f(x)); }
    fn ds(&mut self, v: &[f64]) { self.u(v.len() as u64); v.iter().for_each(|&x| self.d(x)); }
}
struct R<'a>(&'a [u8], usize);
impl R<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], String> {
        let s = self.0.get(self.1..self.1 + n).ok_or("truncated water state")?;
        self.1 += n;
        Ok(s)
    }
    fn f(&mut self) -> Result<f32, String> { Ok(f32::from_le_bytes(self.take(4)?.try_into().unwrap())) }
    fn d(&mut self) -> Result<f64, String> { Ok(f64::from_le_bytes(self.take(8)?.try_into().unwrap())) }
    fn u(&mut self) -> Result<u64, String> { Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap())) }
    fn s(&mut self) -> Result<String, String> { let n = self.u()? as usize; Ok(String::from_utf8_lossy(self.take(n)?).into_owned()) }
    fn fs(&mut self, expect: Option<usize>) -> Result<Vec<f32>, String> {
        let n = self.u()? as usize;
        if expect.is_some_and(|e| e != n) || n > 1 << 30 {
            return Err(format!("water state: an array of {n} where {expect:?} were expected"));
        }
        let b = self.take(4 * n)?;
        Ok(b.chunks_exact(4).map(|c| f32::from_le_bytes(c.try_into().unwrap())).collect())
    }
    fn ds(&mut self) -> Result<Vec<f64>, String> { let n = self.u()? as usize; (0..n).map(|_| self.d()).collect() }
}

fn write_pigment(w: &mut W, p: &AqPigment) {
    w.s(&p.name);
    p.k.iter().for_each(|&v| w.f(v));
    for v in [p.s, p.density, p.diameter_um, p.stain, p.lift] { w.f(v); }
}
fn read_pigment(r: &mut R) -> Result<AqPigment, String> {
    Ok(AqPigment { name: r.s()?, k: [r.f()?, r.f()?, r.f()?], s: r.f()?, density: r.f()?, diameter_um: r.f()?, stain: r.f()?, lift: r.f()? })
}

/// The whole of a sheet's water, exactly (`FPWATER1`).
pub fn write_water(water: &Water) -> Vec<u8> {
    let mut w = W(b"FPWATER1".to_vec());
    w.u(water.w as u64);
    w.u(water.h as u64);
    w.f(water.dx_mm);
    let p = &water.paper;
    w.s(&p.name);
    p.color.iter().for_each(|&v| w.f(v));
    for v in [p.cap_um, p.absorb_um_s, p.wick_mm2_s, p.anisotropy, p.fibre_mm, p.formation, p.roughness_um, p.texture_mm, p.pin_um] { w.f(v); }
    for v in [&water.relief, &water.cap, &water.fdir, &water.perm, &water.sealed, &water.sw, &water.aw, &water.agent] { w.fs(v); }
    w.u(water.species.len() as u64);
    for k in 0..water.species.len() {
        write_pigment(&mut w, &water.species[k]);
        for v in [&water.ms[k], &water.ma[k], &water.md[k], &water.mg[k], &water.mb[k]] { w.fs(v); }
    }
    for v in [water.tilt_deg, water.tilt_dir_deg, water.evap_um_s, water.dispersion_mm2_s, water.marangoni_n_m] { w.f(v); }
    w.d(water.clock_s);
    w.d(water.last_dt);
    w.f(water.last_wick_change);
    w.f(water.last_flow_change);
    w.f(water.last_marangoni_mm_s);
    let l = &water.ledger;
    for v in [l.water_added, l.water_taken, l.water_evaporated, l.water_runoff, l.water_absorbed] { w.d(v); }
    for v in [&l.pigment_added, &l.pigment_taken, &l.pigment_runoff, &l.pigment_sealed] { w.ds(v); }
    w.0
}

/// A sheet's water written by `write_water`.
pub fn read_water(bytes: &[u8]) -> Result<Water, String> {
    let mut r = R(bytes, 0);
    if r.take(8)? != b"FPWATER1" {
        return Err("not a water state".into());
    }
    let (w, h) = (r.u()? as usize, r.u()? as usize);
    let dx_mm = r.f()?;
    let n = w * h;
    let paper = Paper { name: r.s()?, color: [r.f()?, r.f()?, r.f()?], cap_um: r.f()?, absorb_um_s: r.f()?, wick_mm2_s: r.f()?, anisotropy: r.f()?,
        fibre_mm: r.f()?, formation: r.f()?, roughness_um: r.f()?, texture_mm: r.f()?, pin_um: r.f()? };
    let (relief, cap, fdir, perm) = (r.fs(Some(n))?, r.fs(Some(n))?, r.fs(Some(n))?, r.fs(Some(n))?);
    let (sealed, sw, aw, agent) = (r.fs(Some(n))?, r.fs(Some(n))?, r.fs(Some(n))?, r.fs(Some(n))?);
    let ns = r.u()? as usize;
    let (mut species, mut ms, mut ma, mut md, mut mg, mut mb) = (vec![], vec![], vec![], vec![], vec![], vec![]);
    for _ in 0..ns.min(super::aqueous::MAX_SPECIES) {
        species.push(read_pigment(&mut r)?);
        ms.push(r.fs(Some(n))?);
        ma.push(r.fs(Some(n))?);
        md.push(r.fs(Some(n))?);
        mg.push(r.fs(Some(n))?);
        mb.push(r.fs(Some(n))?);
    }
    let (tilt_deg, tilt_dir_deg, evap_um_s, dispersion_mm2_s, marangoni_n_m) = (r.f()?, r.f()?, r.f()?, r.f()?, r.f()?);
    let clock_s = r.d()?;
    let (last_dt, last_wick_change, last_flow_change, last_marangoni_mm_s) = (r.d()?, r.f()?, r.f()?, r.f()?);
    let ledger = super::aqueous::Ledger {
        water_added: r.d()?, water_taken: r.d()?, water_evaporated: r.d()?, water_runoff: r.d()?, water_absorbed: r.d()?,
        pigment_added: r.ds()?, pigment_taken: r.ds()?, pigment_runoff: r.ds()?, pigment_sealed: r.ds()?,
    };
    Ok(Water { w, h, dx_mm, paper, relief, cap, fdir, perm, sealed, sw, aw, agent, species, ms, ma, md, mg, mb, tilt_deg, tilt_dir_deg, evap_um_s, dispersion_mm2_s, marangoni_n_m, clock_s, last_dt, last_wick_change, last_flow_change, last_marangoni_mm_s, steps_taken: 0, ledger })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn brush_on(paper: Paper) -> (Water, WaterBrush, AqPigment) {
        let w = Water::new(paper, 160, 100, 0.5, 7);
        let mut b = WaterBrush::new(20.0, 0.35);
        let u = AqPigment::named("ultramarine").unwrap();
        b.load(&[(u.clone(), 2.0)], 1.0, 1.0);
        (w, b, u)
    }

    fn line(x0: f32, x1: f32, y: f32, secs: f32, p: f32) -> Vec<Sample> {
        vec![Sample { x: x0, y, t: 0.0, pressure: p }, Sample { x: x1, y, t: secs, pressure: p }]
    }

    #[test]
    fn a_stroke_moves_water_and_pigment_from_the_brush_to_the_sheet_and_the_books_balance() {
        let (mut w, mut b, _) = brush_on(Paper::cold_press());
        let (water0, pig0) = (b.water_mm3, b.pigment[0].1);
        // units: the canvas is 160 px for 1000 units here, 6.25 units a pixel
        let rep = stroke(&mut w, &mut b, &line(100.0, 900.0, 300.0, 1.0, 0.7), 6.25);
        assert!(rep.water_laid > 0.0 && rep.pigment_laid > 0.0);
        assert!((b.water_mm3 as f64 - (water0 as f64 - rep.water_laid + rep.water_taken)).abs() < 1e-3, "the belly lost what it laid");
        assert!((w.ledger.water_added - rep.water_laid).abs() < 1e-3 * rep.water_laid);
        let ((on, books), (pon, pbooks)) = w.check();
        assert!((on - books).abs() < 1e-4 * books.max(1e-3), "water {on} vs {books}");
        assert!((pon - pbooks).abs() < 1e-4 * pbooks.max(1e-3), "pigment {pon} vs {pbooks}");
        assert!(b.pigment[0].1 < pig0);
    }

    #[test]
    fn a_fast_stroke_lays_less_than_a_slow_one() {
        let laid = |secs: f32| {
            let (mut w, mut b, _) = brush_on(Paper::cold_press());
            stroke(&mut w, &mut b, &line(100.0, 900.0, 300.0, secs, 0.7), 6.25).water_laid
        };
        assert!(laid(4.0) > 1.5 * laid(0.4), "slow {} vs fast {}", laid(4.0), laid(0.4));
    }

    #[test]
    fn a_nearly_dry_light_stroke_touches_only_the_tooth_on_rough_paper() {
        // a 10 mm brush (150 units at 6.25 units a 0.5 mm pixel) dragged lightly across the sheet
        let wetted = |paper: Paper, fill: f32| {
            let mut w = Water::new(paper, 160, 100, 0.5, 7);
            let mut b = WaterBrush::new(150.0, 0.0667);
            let u = AqPigment::named("ultramarine").unwrap();
            b.load(&[(u, 2.0)], fill, 1.0);
            stroke(&mut w, &mut b, &line(100.0, 900.0, 300.0, 0.5, 0.25), 6.25);
            // the band the footprint swept, away from its soft rim
            let (y0, y1) = ((300.0 / 6.25) as usize - 3, (300.0 / 6.25) as usize + 3);
            let (x0, x1) = (30, 130);
            let mut n = 0;
            for y in y0..y1 {
                for x in x0..x1 {
                    if w.sw[y * w.w + x] + w.aw[y * w.w + x] > 0.0 { n += 1; }
                }
            }
            n as f32 / ((y1 - y0) * (x1 - x0)) as f32
        };
        let dry = wetted(Paper::rough(), 0.1);
        let full = wetted(Paper::rough(), 1.0);
        assert!(full > 0.95, "a full brush wets the whole band: {full}");
        assert!(dry < 0.7 * full, "a nearly dry brush skips the hollows: {dry} of the band vs {full}");
        let smooth = wetted(Paper::hot_press(), 0.1);
        assert!(smooth > dry, "and skips fewer on smooth paper: {smooth} vs {dry}");
    }

    #[test]
    fn water_state_round_trips_exactly() {
        let (mut w, mut b, _) = brush_on(Paper::xuan());
        stroke(&mut w, &mut b, &line(100.0, 900.0, 300.0, 1.0, 0.7), 6.25);
        let back = read_water(&write_water(&w)).unwrap();
        assert_eq!(write_water(&back), write_water(&w));
        let (mut a, mut c) = (w.clone(), back);
        a.advance(3.0);
        c.advance(3.0);
        assert_eq!(a.sw, c.sw);
        assert_eq!(a.md, c.md);
    }
}
