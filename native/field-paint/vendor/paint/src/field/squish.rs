//! (Field) Gestures on wet paint that move it without a brush: folding the sheet so wet paint is
//! pressed onto its mirror image and pulled apart again (decalcomania, a Rorschach blot), pressing
//! and peeling a flat plate (a monotype pull), and rubbing a pad across it (a thumb smear).
//!
//! The method follows Dan Fountain's description of the paint in "Fold & Squish"
//! (https://danfountain.com/blog/paint-that-squishes): paint that is wet and thick enough to touch
//! the other surface transfers; pressure spreads the pressed film outward, thick paint flowing
//! faster than thin; pulling the surfaces apart splits the film unevenly, in ridges that run with
//! the peel. None of its code is used (none is published); these are Field's own kernels over the
//! crate's wet layer.
//!
//! Every operation moves wet paint and never makes or loses any, except a plate's pull, which
//! takes what the plate lifts off the canvas and reports it. Dry paint does not move: only the wet
//! layer (`Wet::vol`, its pigment latent and properties) is touched, and paint that has started to
//! cure transfers and spreads less.

use crate::canvas::Canvas;
use crate::drying::GEL;
use crate::wet::{LAT, Latent, Prop, mix_into};

/// The gap (coats) between two surfaces brought together with no pressure: paint thinner than this
/// does not touch the other side. Full pressure closes it.
const GAP_COATS: f32 = 3.0;

/// A smooth value noise in 0..1 from the crate's hash, at (x, y) in noise cells.
fn value(x: f32, y: f32, seed: u64) -> f32 {
    let (xi, yi) = (x.floor(), y.floor());
    let (fx, fy) = (x - xi, y - yi);
    let (sx, sy) = (fx * fx * (3.0 - 2.0 * fx), fy * fy * (3.0 - 2.0 * fy));
    let h = |dx: i64, dy: i64| crate::rng::hash2(xi as i64 + dx, yi as i64 + dy, seed);
    let a = h(0, 0) + (h(1, 0) - h(0, 0)) * sx;
    let b = h(0, 1) + (h(1, 1) - h(0, 1)) * sx;
    a + (b - a) * sy
}

/// The ridged tack pattern a peel leaves at pixel (x, y), in -1..1: fingers `scale` pixels apart
/// across the peel direction (`ux`, `uy`) and three times longer along it, branching (three
/// octaves of ridged noise).
fn ridges(x: f32, y: f32, ux: f32, uy: f32, scale: f32, seed: u64) -> f32 {
    let along = (x * ux + y * uy) / (3.0 * scale);
    let across = (-x * uy + y * ux) / scale;
    let (mut sum, mut amp, mut f, mut norm) = (0.0, 1.0, 1.0, 0.0);
    for o in 0..3u64 {
        let n = value(along * f, across * f, seed.wrapping_add(o * 7919));
        let r = 1.0 - (2.0 * n - 1.0).abs();
        sum += amp * r * r;
        norm += amp;
        amp *= 0.5;
        f *= 2.1;
    }
    // thin crests (the fingers of paint) against the hollows between them, sharpened so a full peel
    // leaves paint on one side or the other rather than a little less on both
    ((sum / norm - 0.4) * 8.0).clamp(-1.0, 1.0)
}

/// What a gesture did, in mm³ of paint: moved from one place (or surface) to another, and lifted
/// off the canvas altogether (a plate's pull).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Squished {
    pub moved_mm3: f64,
    pub lifted_mm3: f64,
}

impl Canvas {
    /// All the wet paint on the canvas, mm³.
    pub fn wet_mm3(&self) -> f64 {
        self.wet.vol.iter().map(|&v| v as f64).sum::<f64>() * self.mm3_per_coat_px()
    }

    /// How open the wet paint at buffer pixel `i` still is, 0.1..1: fresh paint 1, paint near its gel
    /// point barely moves.
    fn openness(&self, i: usize) -> f32 {
        match self.wet.clock.px.get(i) {
            Some(p) => (1.0 - p.cure / GEL).clamp(0.1, 1.0),
            None => 1.0,
        }
    }

    /// mm³ in one buffer pixel holding `coats` of wet paint.
    fn mm3_per_coat_px(&self) -> f64 {
        let mm_px = self.mm_per_unit as f64 / self.f.scale as f64;
        // a coat is the crate's unit layer (`surface::COAT_UM` µm)
        mm_px * mm_px * crate::surface::COAT_UM as f64 * 1e-3
    }

    /// Move `v` coats of the wet paint at `from` (its own mixture) onto `to`.
    fn move_paint(&mut self, from: usize, to: usize, v: f32) {
        if v <= 0.0 {
            return;
        }
        let (lat, hide) = (self.wet.lat[from], self.wet.hide[from]);
        let (mut rv, mut rl, mut rh) = (self.wet.vol[to], self.wet.lat[to], self.wet.hide[to]);
        mix_into(&mut rv, &mut rl, &mut rh, v, &lat, hide);
        self.wet.vol[to] = rv;
        self.wet.lat[to] = rl;
        self.wet.hide[to] = rh;
        self.wet.cover[to] = 1.0;
        self.wet.vol[from] = (self.wet.vol[from] - v).max(0.0);
    }

    /// Let pressed paint spread for `steps` steps where `contact` (per buffer pixel, 0..1) says
    /// something presses on it: a thin-film flow from thick to thin, faster in thicker, more open
    /// and more fluid paint. Moves paint, never makes or loses it. Returns the coats moved.
    fn spread_pressed(&mut self, contact: &[f32], steps: usize, rate: f32, b: (usize, usize, usize, usize)) -> f64 {
        let w = self.f.w;
        let (x0, y0, x1, y1) = b;
        let mut moved = 0.0f64;
        let mut fx = vec![0.0f32; w * self.f.h];
        let mut fy = vec![0.0f32; w * self.f.h];
        for _ in 0..steps {
            let vol = self.wet.vol.clone();
            let lat = self.wet.lat.clone();
            let hide = self.wet.hide.clone();
            let mob = |i: usize| -> f32 {
                let m = vol[i];
                let fluid = 1.0 - 0.7 * hide[i][1].clamp(0.0, 1.0);
                (m * m / (4.0 + m * m)) * fluid * self.openness(i) * contact[i]
            };
            // flux across the right and lower edge of each pixel, positive out of it
            for y in y0..y1 {
                for x in x0..x1 {
                    let i = y * w + x;
                    fx[i] = 0.0;
                    fy[i] = 0.0;
                    if x + 1 < x1 {
                        let j = i + 1;
                        let d = vol[i] - vol[j];
                        let m = mob(if d > 0.0 { i } else { j }).min(mob(i).max(mob(j)));
                        fx[i] = rate * d * m;
                    }
                    if y + 1 < y1 {
                        let j = i + w;
                        let d = vol[i] - vol[j];
                        let m = mob(if d > 0.0 { i } else { j }).min(mob(i).max(mob(j)));
                        fy[i] = rate * d * m;
                    }
                }
            }
            for y in y0..y1 {
                for x in x0..x1 {
                    let i = y * w + x;
                    // outflows (positive) and inflows (negative) on the four edges
                    let mut edges: [(f32, usize); 4] = [(0.0, i); 4];
                    edges[0] = (fx[i], i + 1);
                    edges[1] = (fy[i], i + w);
                    if x > x0 {
                        edges[2] = (-fx[i - 1], i - 1);
                    }
                    if y > y0 {
                        edges[3] = (-fy[i - w], i - w);
                    }
                    let out: f32 = edges.iter().map(|e| e.0.max(0.0)).sum();
                    let (mut rv, mut rl, mut rh): (f32, Latent, Prop) = ((vol[i] - out).max(0.0), lat[i], hide[i]);
                    let mut inflow = 0.0;
                    for &(f, j) in &edges {
                        if f < 0.0 {
                            mix_into(&mut rv, &mut rl, &mut rh, -f, &lat[j], hide[j]);
                            inflow -= f;
                        }
                    }
                    if inflow > 0.0 {
                        self.wet.cover[i] = 1.0;
                    }
                    moved += out as f64;
                    self.wet.vol[i] = rv;
                    self.wet.lat[i] = rl;
                    self.wet.hide[i] = rh;
                }
            }
        }
        moved
    }

    fn mark_worked(&mut self, b: (usize, usize, usize, usize)) {
        self.wet.touch(b.0, b.1, b.2, b.3);
        self.surf_gen += 1;
    }

    /// Fold the canvas along the line through (`px`, `py`) running toward (`dx`, `dy`) (canvas
    /// units), pressing the half to the left of that direction onto the other half at `pressure`
    /// (0..1), then pull it open again.
    ///
    /// Where the wet paint on a point and on its mirror image together is thicker than the gap the
    /// pressure leaves, the two films touch: each gives its mirror `transfer` (0..1; 0.5 swaps half,
    /// so both sides end up the same mixture) of its paint, less as the paint cures. Pressure then
    /// spreads the pressed paint outward, for `spread` (0..1) of the time it takes to level. Pulling
    /// the halves apart splits the film between them: evenly with `peel` 0, in ridges running away
    /// from the fold, `ridge` units apart, with `peel` toward 1 (wholly past 2/3) — the paint one side keeps on a ridge
    /// is what its mirror lost there. Moves paint and never makes or loses any.
    #[allow(clippy::too_many_arguments)]
    pub fn fold_press(
        &mut self, px: f32, py: f32, dx: f32, dy: f32, pressure: f32, transfer: f32, spread: f32, peel: f32, ridge: f32,
        seed: u64,
    ) -> Squished {
        let (w, h) = (self.f.w, self.f.h);
        let s = self.f.scale;
        let len = (dx * dx + dy * dy).sqrt();
        if !(len > 0.0) {
            return Squished::default();
        }
        let (ux, uy) = (dx / len, dy / len);
        // the flap's side: left of the direction, in y-down canvas coordinates
        let (nx, ny) = (uy, -ux);
        let (cx, cy) = (px * s - self.f.x0 as f32, py * s - self.f.y0 as f32);
        let pressure = pressure.clamp(0.0, 1.0);
        let gap = GAP_COATS * (1.0 - pressure);
        let transfer = transfer.clamp(0.0, 0.5);
        let peel = peel.clamp(0.0, 1.0);
        let ridge_px = (ridge * s).max(2.0);
        let mut contact = vec![0.0f32; w * h];
        let mut pairs: Vec<(usize, usize)> = Vec::new();
        let mut moved = 0.0f64;
        for y in 0..h {
            for x in 0..w {
                let (fx, fy) = (x as f32 + 0.5 - cx, y as f32 + 0.5 - cy);
                let d = fx * nx + fy * ny;
                if d <= 0.0 {
                    continue;
                }
                let (mx, my) = (x as f32 + 0.5 - 2.0 * d * nx, y as f32 + 0.5 - 2.0 * d * ny);
                if mx < 0.0 || my < 0.0 || mx >= w as f32 || my >= h as f32 {
                    continue;
                }
                let (i, j) = (y * w + x, my as usize * w + mx as usize);
                if i == j {
                    continue;
                }
                let (va, vb) = (self.wet.vol[i], self.wet.vol[j]);
                let c = ((va + vb - gap) / (0.5 + 0.5 * gap)).clamp(0.0, 1.0);
                if c <= 0.0 {
                    continue;
                }
                contact[i] = contact[i].max(c);
                contact[j] = contact[j].max(c);
                pairs.push((i, j));
                let k = transfer * c * self.openness(i).min(self.openness(j));
                let (ga, gb) = (k * va, k * vb);
                // exchange through a snapshot of both, so neither side's gift includes what it received
                let (la, ha, lb, hb) = (self.wet.lat[i], self.wet.hide[i], self.wet.lat[j], self.wet.hide[j]);
                let (mut rva, mut rla, mut rha) = (va - ga, la, ha);
                mix_into(&mut rva, &mut rla, &mut rha, gb, &lb, hb);
                let (mut rvb, mut rlb, mut rhb) = (vb - gb, lb, hb);
                mix_into(&mut rvb, &mut rlb, &mut rhb, ga, &la, ha);
                self.wet.vol[i] = rva;
                self.wet.lat[i] = rla;
                self.wet.hide[i] = rha;
                self.wet.vol[j] = rvb;
                self.wet.lat[j] = rlb;
                self.wet.hide[j] = rhb;
                if rva > 0.0 {
                    self.wet.cover[i] = 1.0;
                }
                if rvb > 0.0 {
                    self.wet.cover[j] = 1.0;
                }
                moved += (ga + gb) as f64;
            }
        }
        if pairs.is_empty() {
            return Squished::default();
        }
        let steps = (spread.clamp(0.0, 1.0) * (4.0 + 20.0 * pressure)).round() as usize;
        moved += self.spread_pressed(&contact, steps, 0.2, (0, 0, w, h));
        if peel > 0.0 {
            for &(i, j) in &pairs {
                let c = contact[i].min(contact[j]);
                let t = self.wet.vol[i] + self.wet.vol[j];
                if t <= 0.0 {
                    continue;
                }
                let (x, y) = ((i % w) as f32, (i / w) as f32);
                // the fingers run away from the fold: across the fold line
                let r = ridges(x, y, nx, ny, ridge_px, seed);
                // past peel 2/3 the film splits wholly: crests stay on one side, hollows are bare
                let share = (0.5 + 0.5 * (1.5 * peel * c).min(1.0) * r).clamp(0.0, 1.0);
                let want = share * t;
                let have = self.wet.vol[i];
                if have > want {
                    self.move_paint(i, j, have - want);
                } else {
                    self.move_paint(j, i, want - have);
                }
                moved += (have - want).abs() as f64;
            }
        }
        self.mark_worked((0, 0, w, h));
        Squished { moved_mm3: moved * self.mm3_per_coat_px(), lifted_mm3: 0.0 }
    }

    /// Press a flat plate `radius` units across onto the wet paint at (`x`, `y`) at `pressure`
    /// (0..1), let the pressed paint spread for `spread` (0..1), and peel the plate off toward
    /// (`dx`, `dy`): it takes `take` (0..1) of the film it touched, unevenly in ridges `ridge` units
    /// apart running with the peel, `peel` (0..1) deep. Returns the paint moved across the canvas
    /// and lifted off it with the plate.
    #[allow(clippy::too_many_arguments)]
    pub fn press_peel(
        &mut self, x: f32, y: f32, radius: f32, pressure: f32, spread: f32, dx: f32, dy: f32, take: f32, peel: f32,
        ridge: f32, seed: u64,
    ) -> Squished {
        let (w, h) = (self.f.w, self.f.h);
        let s = self.f.scale;
        let (cx, cy, r) = (x * s - self.f.x0 as f32, y * s - self.f.y0 as f32, (radius * s).max(1.0));
        let pressure = pressure.clamp(0.0, 1.0);
        let gap = GAP_COATS * (1.0 - pressure);
        let x0 = (cx - r - 2.0).floor().max(0.0) as usize;
        let y0 = (cy - r - 2.0).floor().max(0.0) as usize;
        let x1 = ((cx + r + 2.0).ceil().max(0.0) as usize).min(w);
        let y1 = ((cy + r + 2.0).ceil().max(0.0) as usize).min(h);
        if x0 >= x1 || y0 >= y1 {
            return Squished::default();
        }
        let len = (dx * dx + dy * dy).sqrt();
        let (ux, uy) = if len > 0.0 { (dx / len, dy / len) } else { (1.0, 0.0) };
        let mut contact = vec![0.0f32; w * h];
        for yy in y0..y1 {
            for xx in x0..x1 {
                let d = ((xx as f32 + 0.5 - cx).powi(2) + (yy as f32 + 0.5 - cy).powi(2)).sqrt();
                let edge = (r - d).clamp(0.0, 1.0);
                let i = yy * w + xx;
                contact[i] = edge * ((self.wet.vol[i] - gap) / (0.5 + 0.5 * gap)).clamp(0.0, 1.0);
            }
        }
        let steps = (spread.clamp(0.0, 1.0) * (4.0 + 20.0 * pressure)).round() as usize;
        let moved = self.spread_pressed(&contact, steps, 0.2, (x0, y0, x1, y1));
        // the spread paint may now reach the plate where it did not before
        let (take, peel, ridge_px) = (take.clamp(0.0, 1.0), peel.clamp(0.0, 1.0), (ridge * s).max(2.0));
        let mut lifted = 0.0f64;
        for yy in y0..y1 {
            for xx in x0..x1 {
                let i = yy * w + xx;
                let d = ((xx as f32 + 0.5 - cx).powi(2) + (yy as f32 + 0.5 - cy).powi(2)).sqrt();
                let edge = (r - d).clamp(0.0, 1.0);
                let c = edge * ((self.wet.vol[i] - gap) / (0.5 + 0.5 * gap)).clamp(0.0, 1.0);
                if c <= 0.0 {
                    continue;
                }
                let rr = ridges(xx as f32 + self.f.x0 as f32, yy as f32 + self.f.y0 as f32, ux, uy, ridge_px, seed);
                let share = (take * c * self.openness(i) * (1.0 + peel * rr)).clamp(0.0, 1.0);
                let v = self.wet.vol[i] * share;
                self.wet.vol[i] -= v;
                if !self.wet.solv.is_empty() {
                    self.wet.solv[i] *= 1.0 - share;
                }
                lifted += v as f64;
            }
        }
        self.mark_worked((x0, y0, x1, y1));
        let k = self.mm3_per_coat_px();
        Squished { moved_mm3: moved * k, lifted_mm3: lifted * k }
    }

    /// Rub a pad `radius` units across along `pts` (canvas units) at `pressure` (0..1): a thumb or a
    /// rag-wrapped finger dragged through wet paint. It rides on the wet paint, picks up what stands
    /// above the film a light touch leaves, and lays it down further along, so the paint is dragged out
    /// and thins as it goes, in streaks where the pad's skin presses harder. It never picks up what it
    /// laid itself on this pass. What the pad still holds when it lifts is reported as lifted (it is on
    /// the pad); nothing else is made or lost.
    pub fn rub(&mut self, pts: &[(f32, f32)], radius: f32, pressure: f32, seed: u64) -> Squished {
        let (w, h) = (self.f.w, self.f.h);
        let s = self.f.scale;
        let r = (radius * s).max(1.0);
        let pressure = pressure.clamp(0.0, 1.0);
        let floor = 0.6 * (1.0 - pressure);
        let pick = 0.1 + 0.35 * pressure;
        let lay = 0.22;
        let (mut rv, mut rl, mut rh): (f32, Latent, Prop) = (0.0, [0.0; LAT], [0.0, 0.5, 1.0, 0.0, 1.0]);
        let mut laid = vec![0.0f32; w * h];
        let mut moved = 0.0f64;
        let mut bx = (usize::MAX, usize::MAX, 0usize, 0usize);
        // pad centres every third of a radius, with the direction of travel there
        let mut centres: Vec<((f32, f32), (f32, f32))> = Vec::new();
        for seg in pts.windows(2) {
            let a = (seg[0].0 * s - self.f.x0 as f32, seg[0].1 * s - self.f.y0 as f32);
            let b = (seg[1].0 * s - self.f.x0 as f32, seg[1].1 * s - self.f.y0 as f32);
            let l = ((b.0 - a.0).powi(2) + (b.1 - a.1).powi(2)).sqrt();
            if l <= 0.0 {
                continue;
            }
            let u = ((b.0 - a.0) / l, (b.1 - a.1) / l);
            let n = (l / (0.35 * r).max(1.0)).ceil().max(1.0) as usize;
            for k in 0..n {
                let t = k as f32 / n as f32;
                centres.push(((a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t), u));
            }
        }
        let weight = |c: (f32, f32), u: (f32, f32), x: usize, y: usize| {
            let (ox, oy) = (x as f32 + 0.5 - c.0, y as f32 + 0.5 - c.1);
            let d2 = (ox * ox + oy * oy) / (r * r);
            if d2 >= 1.0 {
                return 0.0;
            }
            // the skin's ridges: streaks that keep their place across the pad as it travels
            let across = -ox * u.1 + oy * u.0;
            let streak = value(across / (0.12 * r).max(1.0) + 100.0, 0.5, seed);
            (1.0 - d2) * (0.25 + 1.5 * streak * streak)
        };
        for &(c, u) in &centres {
            let x0 = (c.0 - r).floor().max(0.0) as usize;
            let y0 = (c.1 - r).floor().max(0.0) as usize;
            let x1 = ((c.0 + r).ceil().max(0.0) as usize + 1).min(w);
            let y1 = ((c.1 + r).ceil().max(0.0) as usize + 1).min(h);
            if x0 >= x1 || y0 >= y1 {
                continue;
            }
            bx = (bx.0.min(x0), bx.1.min(y0), bx.2.max(x1), bx.3.max(y1));
            let sumw: f32 = (y0..y1).flat_map(|y| (x0..x1).map(move |x| (x, y))).map(|(x, y)| weight(c, u, x, y)).sum();
            if sumw <= 0.0 {
                continue;
            }
            // lay a share of what the pad holds, heavier on the trailing half of the pad
            let total = lay * rv;
            if total > 0.0 {
                let trail = |x: usize, y: usize| if (x as f32 + 0.5 - c.0) * u.0 + (y as f32 + 0.5 - c.1) * u.1 < 0.0 { 1.6 } else { 0.4 };
                let tw: f32 = (y0..y1).flat_map(|y| (x0..x1).map(move |x| (x, y))).map(|(x, y)| weight(c, u, x, y) * trail(x, y)).sum();
                for y in y0..y1 {
                    for x in x0..x1 {
                        let v = total * weight(c, u, x, y) * trail(x, y) / tw;
                        if v <= 0.0 {
                            continue;
                        }
                        let i = y * w + x;
                        let (mut v0, mut l0, mut h0) = (self.wet.vol[i], self.wet.lat[i], self.wet.hide[i]);
                        mix_into(&mut v0, &mut l0, &mut h0, v, &rl, rh);
                        self.wet.vol[i] = v0;
                        self.wet.lat[i] = l0;
                        self.wet.hide[i] = h0;
                        self.wet.cover[i] = 1.0;
                        laid[i] += v;
                    }
                }
                rv -= total;
                moved += total as f64;
            }
            // pick up what stands above the floor (and was not laid on this pass), as open as it is
            for y in y0..y1 {
                for x in x0..x1 {
                    let i = y * w + x;
                    let wt = (weight(c, u, x, y) / 1.75).min(1.0);
                    let v = pick * wt * self.openness(i) * (self.wet.vol[i] - laid[i] - floor).max(0.0);
                    if v <= 0.0 {
                        continue;
                    }
                    let (lat, hide) = (self.wet.lat[i], self.wet.hide[i]);
                    mix_into(&mut rv, &mut rl, &mut rh, v, &lat, hide);
                    self.wet.vol[i] -= v;
                }
            }
        }
        if bx.0 < bx.2 {
            self.mark_worked(bx);
        }
        let k = self.mm3_per_coat_px();
        Squished { moved_mm3: moved * k, lifted_mm3: rv.max(0.0) as f64 * k }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Gesture, Held, Paint, Tool};

    fn canvas_with_blob() -> Canvas {
        let mut c = Canvas::new(200, 1.0, [0.9, 0.9, 0.88]);
        let mut b = Held::new(Tool::filbert(60.0), 1);
        b.load(Paint::body([0.7, 0.05, 0.05]).with_stiff(0.4), 4.0);
        c.drag(&mut b, &Gesture::new(vec![(120.0, 300.0), (380.0, 520.0)]).pressure(1.0, 1.0), None);
        let mut b2 = Held::new(Tool::filbert(50.0), 2);
        b2.load(Paint::body([0.05, 0.1, 0.6]).with_stiff(0.4), 4.0);
        c.drag(&mut b2, &Gesture::new(vec![(200.0, 700.0), (420.0, 640.0)]).pressure(1.0, 1.0), None);
        c
    }

    #[test]
    fn a_fold_moves_paint_but_conserves_it() {
        let mut c = canvas_with_blob();
        let before = c.wet_total();
        assert!(before > 0.0);
        let r = c.fold_press(500.0, 0.0, 0.0, 1.0, 0.9, 0.5, 0.6, 0.7, 25.0, 3);
        assert!(r.moved_mm3 > 0.0, "{r:?}");
        let after = c.wet_total();
        assert!(((after - before) / before).abs() < 1e-4, "{before} -> {after}");
    }

    #[test]
    fn an_even_fold_leaves_a_mirror_image() {
        let mut c = canvas_with_blob();
        // a vertical fold on a pixel boundary (x = 500 units = pixel 100 of 200), split evenly
        c.fold_press(500.0, 0.0, 0.0, 1.0, 1.0, 0.5, 0.0, 0.0, 25.0, 3);
        let w = c.f.w;
        let mut worst = 0.0f32;
        let mut mass = 0.0f32;
        for y in 0..c.f.h {
            for x in 0..w / 2 {
                let (a, b) = (c.wet.vol[y * w + x], c.wet.vol[y * w + (w - 1 - x)]);
                // at full pressure two films touch fully once together they are half a coat thick
                if a + b < 0.5 {
                    continue;
                }
                worst = worst.max((a - b).abs());
                mass += a;
            }
        }
        assert!(mass > 0.0);
        assert!(worst < 1e-4, "largest left/right difference {worst} coats");
    }

    #[test]
    fn a_peel_leaves_complementary_ridges() {
        let mut c = canvas_with_blob();
        c.fold_press(500.0, 0.0, 0.0, 1.0, 1.0, 0.5, 0.0, 1.0, 20.0, 3);
        let w = c.f.w;
        let (mut differ, mut both) = (0, 0);
        for y in 0..c.f.h {
            for x in 0..w / 2 {
                let (a, b) = (c.wet.vol[y * w + x], c.wet.vol[y * w + (w - 1 - x)]);
                if a + b > 1.0 {
                    both += 1;
                    if (a - b).abs() > 0.2 * (a + b) {
                        differ += 1;
                    }
                }
            }
        }
        let zeros = (0..c.f.h).flat_map(|y| (0..w / 2).map(move |x| (x, y))).filter(|&(x, y)| { let (a, b) = (c.wet.vol[y * w + x], c.wet.vol[y * w + (w - 1 - x)]); a + b > 1.0 && a.min(b) < 0.05 }).count();
        assert!(both > 100 && differ * 5 > both, "{differ} of {both} pressed pairs split unevenly");
        assert!(zeros * 5 > both, "{zeros} of {both} pressed pairs left one side bare");
    }

    #[test]
    fn a_rub_conserves_paint_and_moves_it_along() {
        let mut c = canvas_with_blob();
        let before = c.wet_mm3();
        let r = c.rub(&[(250.0, 400.0), (700.0, 400.0)], 30.0, 0.8, 1);
        assert!(r.moved_mm3 > 0.0);
        let after = c.wet_mm3() + r.lifted_mm3;
        assert!(((after - before) / before).abs() < 1e-4, "{before} -> {after}");
        assert!(c.wet_um(650.0, 400.0) > 0.0, "nothing dragged past the blob");
    }

    #[test]
    fn a_plate_lifts_what_it_reports() {
        let mut c = canvas_with_blob();
        let before = c.wet_total();
        let r = c.press_peel(250.0, 410.0, 120.0, 0.9, 0.5, 1.0, 0.0, 0.5, 0.8, 15.0, 4);
        let after = c.wet_total();
        assert!(r.lifted_mm3 > 0.0);
        let lifted_units = (before - after) * c.mm3_per_coat_px() * (c.f.scale as f64).powi(2);
        assert!(((lifted_units - r.lifted_mm3) / r.lifted_mm3).abs() < 1e-3, "{lifted_units} vs {:?}", r);
    }
}
