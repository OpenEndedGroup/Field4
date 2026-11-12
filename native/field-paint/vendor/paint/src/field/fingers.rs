//! (Field) Viscous fingering: a less viscous fluid (air, or thin paint) pushing into a more viscous
//! one (paint) in the thin gap between two surfaces does not advance as a flat front. The
//! Saffman–Taylor instability makes any bulge in the front draw the flow toward itself, so the front
//! breaks into fingers that split and branch; the more viscous the displaced fluid is than the one
//! displacing it, the more the fingers branch, until at large contrast they grow as a fractal tree
//! (diffusion-limited aggregation's shape).
//!
//! The model is the dielectric breakdown model (Niemeyer, Pietronero and Wiesmann 1984) on a coarse
//! grid laid over the wet paint: the pressure in the paint obeys Darcy's law in a Hele-Shaw gap,
//! `∇·(k ∇p) = -q`, with `k` the paint's local mobility (thinner, more open paint flows more easily),
//! `q` the suction of a gap that is opening (a peeled plate or a fold pulled apart; zero for a jet
//! pushing paint), `p = 0` in the air and `p = 1` where the paint is held (the far edge of a jet's
//! reach). The front advances one cell at a time, each interface cell chosen with probability
//! proportional to the local pressure gradient raised to `eta`: `eta` 0 grows a compact, rough blob
//! (no contrast, Eden growth), 1 is DLA, and larger is sparser still. `eta` comes from the viscosity
//! contrast `M` as `1.5 (1 - 1/M)`.
//!
//! Used by `Canvas::blow`. The pattern is computed, not painted: callers use the cells the air reached to decide where paint
//! goes, and move it (they never make or lose any).

use crate::canvas::Canvas;
use crate::rng::Rng;

/// `eta` of the dielectric breakdown model for a viscosity contrast `m` (displaced over displacing).
pub fn eta_for_contrast(m: f32) -> f32 {
    if !(m > 1.0) {
        0.0
    } else {
        1.5 * (1.0 - 1.0 / m)
    }
}

/// A coarse grid over which a front of air grows into paint.
pub struct Hele {
    pub w: usize,
    pub h: usize,
    /// Paint cells the air may invade.
    pub domain: Vec<bool>,
    /// Air (pressure 0): sources, and every cell invaded so far.
    pub air: Vec<bool>,
    /// Held paint (pressure 1).
    pub sink: Vec<bool>,
    /// Mobility per cell, > 0.
    pub k: Vec<f32>,
    /// Suction per cell (an opening gap), >= 0.
    pub q: Vec<f32>,
    /// Pressure.
    pub p: Vec<f32>,
    /// The air cell each invaded cell grew from (`usize::MAX` for the first air).
    pub parent: Vec<usize>,
    /// Keys each cell's off-centre point (`pos`).
    pub jitter_seed: u64,
}

impl Hele {
    pub fn new(w: usize, h: usize) -> Self {
        let n = w * h;
        Hele { w, h, domain: vec![false; n], air: vec![false; n], sink: vec![false; n], k: vec![1.0; n], q: vec![0.0; n], p: vec![0.0; n], parent: vec![usize::MAX; n], jitter_seed: 0 }
    }

    /// Where in cell `l` a finger passes, in cell units: its centre moved by up to 0.42 of a cell
    /// either way, so fingers joining these points run at every angle, not along the grid.
    pub fn pos(&self, l: usize) -> (f32, f32) {
        let (x, y) = ((l % self.w) as i64, (l / self.w) as i64);
        let jx = crate::rng::hash2(x, y, self.jitter_seed ^ 0xA5A5) - 0.5;
        let jy = crate::rng::hash2(x, y, self.jitter_seed ^ 0x5A5A) - 0.5;
        (x as f32 + 0.5 + 0.84 * jx, y as f32 + 0.5 + 0.84 * jy)
    }

    /// The fingers as segments (cell units) from each invaded cell's parent to it, in growth order.
    pub fn segments(&self, invaded: &[usize]) -> Vec<((f32, f32), (f32, f32))> {
        invaded.iter().filter(|&&c| self.parent[c] != usize::MAX).map(|&c| (self.pos(self.parent[c]), self.pos(c))).collect()
    }

    fn nbrs(&self, i: usize) -> impl Iterator<Item = usize> + '_ {
        let (x, y, w, h) = (i % self.w, i / self.w, self.w, self.h);
        [(x > 0).then(|| i - 1), (x + 1 < w).then(|| i + 1), (y > 0).then(|| i - w), (y + 1 < h).then(|| i + w)].into_iter().flatten()
    }

    /// Relax the pressure for `iters` sweeps of over-relaxed Gauss–Seidel. Cells that are neither
    /// paint, air nor sink are walls (no flow through them).
    pub fn relax(&mut self, iters: usize) {
        for _ in 0..iters {
            for i in 0..self.w * self.h {
                if self.air[i] {
                    self.p[i] = 0.0;
                    continue;
                }
                if self.sink[i] {
                    self.p[i] = 1.0;
                    continue;
                }
                if !self.domain[i] {
                    continue;
                }
                let (mut num, mut den) = (self.q[i], 0.0f32);
                for j in self.nbrs(i) {
                    if !(self.domain[j] || self.air[j] || self.sink[j]) {
                        continue;
                    }
                    let kj = if self.domain[j] { self.k[j] } else { self.k[i] };
                    let kk = 2.0 * self.k[i] * kj / (self.k[i] + kj);
                    num += kk * self.p[j];
                    den += kk;
                }
                if den > 0.0 {
                    let target = num / den;
                    self.p[i] += 1.8 * (target - self.p[i]);
                    if self.p[i] < 0.0 {
                        self.p[i] = 0.0;
                    }
                }
            }
        }
    }

    /// Paint cells next to air: where the front can advance.
    fn front(&self) -> Vec<usize> {
        (0..self.w * self.h).filter(|&i| self.domain[i] && !self.air[i] && !self.sink[i] && self.nbrs(i).any(|j| self.air[j])).collect()
    }

    /// Grow the air into the paint by up to `n` cells with exponent `eta`; `bias` (per cell, >= 0)
    /// multiplies each cell's chance. Returns the invaded cells in the order the front reached them.
    pub fn grow(&mut self, n: usize, eta: f32, bias: Option<&[f32]>, seed: u64) -> Vec<usize> {
        let mut rng = Rng::new(seed ^ 0xF1A6_E125);
        self.jitter_seed = seed;
        let mut order = Vec::with_capacity(n);
        if n == 0 {
            return order;
        }
        // normalise suction so the pressure has a scale the exponent can work on
        self.relax(300);
        let batch = (n / 160).max(1);
        while order.len() < n {
            let front = self.front();
            if front.is_empty() {
                break;
            }
            let pmax = front.iter().map(|&i| self.p[i]).fold(0.0f32, f32::max);
            if !(pmax > 0.0) {
                break;
            }
            let mut wts: Vec<f32> = front
                .iter()
                .map(|&i| (self.p[i] / pmax).max(1e-6).powf(eta) * bias.map_or(1.0, |b| b[i]))
                .collect();
            for _ in 0..batch.min(front.len()) {
                let total: f32 = wts.iter().sum();
                if !(total > 0.0) {
                    break;
                }
                let mut r = rng.f() * total;
                let mut pick = wts.len() - 1;
                for (k, &wt) in wts.iter().enumerate() {
                    if r < wt {
                        pick = k;
                        break;
                    }
                    r -= wt;
                }
                wts[pick] = 0.0;
                let c = front[pick];
                let ps: Vec<usize> = self.nbrs(c).filter(|&j| self.air[j]).collect();
                if !ps.is_empty() {
                    self.parent[c] = ps[(rng.f() * ps.len() as f32) as usize % ps.len()];
                }
                self.air[c] = true;
                self.p[c] = 0.0;
                order.push(c);
                if order.len() >= n {
                    break;
                }
            }
            self.relax(12);
        }
        order
    }

    /// How far the air that invaded the paint reaches for its area: the radius of gyration of the
    /// invaded cells over that of a round blot of as many cells. A compact blot is about 1; fingers
    /// that branch out reach further, 2 or more for a DLA-like tree.
    pub fn branching(&self, invaded: &[usize]) -> f32 {
        let n = invaded.len();
        if n < 2 {
            return 0.0;
        }
        let (mut sx, mut sy) = (0.0f64, 0.0f64);
        for &i in invaded {
            sx += (i % self.w) as f64;
            sy += (i / self.w) as f64;
        }
        let (cx, cy) = (sx / n as f64, sy / n as f64);
        let rg2 = invaded.iter().map(|&i| ((i % self.w) as f64 - cx).powi(2) + ((i / self.w) as f64 - cy).powi(2)).sum::<f64>() / n as f64;
        // a disc of n cells: radius sqrt(n / pi), radius of gyration that over sqrt(2)
        let disc2 = n as f64 / std::f64::consts::PI / 2.0;
        (rg2 / disc2).sqrt() as f32
    }
}

/// The point of segment `a`-`b` nearest `p`, and the distance to it.
fn nearest(p: (f32, f32), a: (f32, f32), b: (f32, f32)) -> ((f32, f32), f32) {
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let l2 = dx * dx + dy * dy;
    let t = if l2 > 0.0 { (((p.0 - a.0) * dx + (p.1 - a.1) * dy) / l2).clamp(0.0, 1.0) } else { 0.0 };
    let q = (a.0 + dx * t, a.1 + dy * t);
    (q, ((p.0 - q.0).powi(2) + (p.1 - q.1).powi(2)).sqrt())
}

impl Canvas {
    /// Push the wet paint within `hw` pixels of segment `a`-`b` (buffer pixels) out of it, each pixel's
    /// paint (all but `film` coats) to the place just past the segment's edge straight out from its
    /// nearest point: the air opening a finger shoves paint to its sides and tip, into rims.
    pub(crate) fn push_out(&mut self, a: (f32, f32), b: (f32, f32), hw: f32, film: f32, dry: f32) {
        let (w, h) = (self.f.w as i64, self.f.h as i64);
        let x0 = (a.0.min(b.0) - hw - 1.0).floor().max(0.0) as i64;
        let y0 = (a.1.min(b.1) - hw - 1.0).floor().max(0.0) as i64;
        let x1 = ((a.0.max(b.0) + hw + 1.0).ceil() as i64).min(w);
        let y1 = ((a.1.max(b.1) + hw + 1.0).ceil() as i64).min(h);
        let (sx, sy) = (b.0 - a.0, b.1 - a.1);
        let sl = (sx * sx + sy * sy).sqrt().max(1e-6);
        for y in y0..y1 {
            for x in x0..x1 {
                let p = (x as f32 + 0.5, y as f32 + 0.5);
                let (q, d) = nearest(p, a, b);
                if d > hw {
                    continue;
                }
                // straight out from the finger; on its axis, to one side by the pixel's parity
                let (ux, uy) = if d > 0.3 { ((p.0 - q.0) / d, (p.1 - q.1) / d) } else if (x + y) % 2 == 0 { (-sy / sl, sx / sl) } else { (sy / sl, -sx / sl) };
                let (tx, ty) = ((q.0 + ux * (hw + 1.0)).floor() as i64, (q.1 + uy * (hw + 1.0)).floor() as i64);
                if tx < 0 || ty < 0 || tx >= w || ty >= h {
                    continue;
                }
                let (i, t) = ((y * w + x) as usize, (ty * w + tx) as usize);
                if i == t {
                    continue;
                }
                let v = (self.wet.vol[i] - film).max(0.0);
                self.move_paint(i, t, v);
                if !self.wet.solv.is_empty() {
                    self.wet.solv[i] *= 1.0 - dry;
                }
            }
        }
    }
}

/// What a blow did: mm³ of paint moved, and how far its tendrils reach for their area
/// (`Hele::branching`, averaged over the jet's stations).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Blown {
    pub moved_mm3: f64,
    pub branching: f32,
    pub stations: u32,
}

impl Canvas {
    /// The wet paint's mobility at buffer pixel `i`: thinner and more open paint flows more easily.
    pub(crate) fn mobility(&self, i: usize) -> f32 {
        let fluid = 1.0 - 0.7 * self.wet.hide[i][1].clamp(0.0, 1.0);
        let open = match self.wet.clock.px.get(i) {
            Some(p) => (1.0 - p.cure / crate::drying::GEL).clamp(0.1, 1.0),
            None => 1.0,
        };
        (fluid * open).max(0.05)
    }

    /// Blow air through a straw or an airbrush along `pts` (canvas units) at `pressure` (0..1), the jet
    /// `radius` units across. Where the jet meets wet paint, the air pushes into it and the paint
    /// flows away from the jet ahead of it, along the direction of travel more than back; the air
    /// advances into the paint as fingers (`fingers` module) whose branching rises with the viscosity
    /// `contrast` between the paint and the air (`None`: from the paint, thinner paint branching more),
    /// `tendril` units wide. Paint the air passes is pushed to the tendrils' sides and tips, leaving a
    /// thin film behind and thick rims at the edges, and the solvent in the film the air passed over
    /// evaporates faster. Moves paint, never makes or loses any.
    #[allow(clippy::too_many_arguments)]
    pub fn blow(&mut self, pts: &[(f32, f32)], radius: f32, pressure: f32, contrast: Option<f32>, tendril: f32, seed: u64) -> Blown {
        let (w, h) = (self.f.w, self.f.h);
        let s = self.f.scale;
        let r = (radius * s).max(1.0);
        let g = (1.5 * tendril * s).round().max(2.0) as usize;
        let reach = 6.0 * r;
        let pressure = pressure.clamp(0.0, 1.0);
        let before = self.wet.vol.clone();
        let mut out = Blown::default();
        let mut bsum = 0.0f32;
        let mut bn = 0u32;
        let mut stations: Vec<((f32, f32), (f32, f32))> = Vec::new();
        for seg in pts.windows(2) {
            let a = (seg[0].0 * s - self.f.x0 as f32, seg[0].1 * s - self.f.y0 as f32);
            let b = (seg[1].0 * s - self.f.x0 as f32, seg[1].1 * s - self.f.y0 as f32);
            let l = ((b.0 - a.0).powi(2) + (b.1 - a.1).powi(2)).sqrt();
            if l <= 0.0 {
                continue;
            }
            let u = ((b.0 - a.0) / l, (b.1 - a.1) / l);
            let n = (l / (0.8 * r).max(1.0)).ceil().max(1.0) as usize;
            for k in 0..n {
                let t = k as f32 / n as f32;
                stations.push(((a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t), u));
            }
        }
        if pts.len() == 1 {
            stations.push(((pts[0].0 * s - self.f.x0 as f32, pts[0].1 * s - self.f.y0 as f32), (0.0, 0.0)));
        }
        let (mut bx0, mut by0, mut bx1, mut by1) = (w, h, 0usize, 0usize);
        for (si, &(c, u)) in stations.iter().enumerate() {
            let ox = (c.0 - reach).floor().max(0.0) as usize;
            let oy = (c.1 - reach).floor().max(0.0) as usize;
            let ex = ((c.0 + reach).ceil().max(0.0) as usize).min(w);
            let ey = ((c.1 + reach).ceil().max(0.0) as usize).min(h);
            if ox >= ex || oy >= ey {
                continue;
            }
            let (gw, gh) = ((ex - ox).div_ceil(g), (ey - oy).div_ceil(g));
            let mut hs = Hele::new(gw, gh);
            let mut vol = vec![0.0f32; gw * gh];
            let mut fluid = (0.0f32, 0.0f32);
            for cy in 0..gh {
                for cx in 0..gw {
                    let (mut sv, mut sk, mut n) = (0.0f32, 0.0f32, 0.0f32);
                    for dy in 0..g {
                        for dx in 0..g {
                            let (x, y) = (ox + cx * g + dx, oy + cy * g + dy);
                            if x < w && y < h {
                                let i = y * w + x;
                                sv += self.wet.vol[i];
                                sk += self.mobility(i);
                                n += 1.0;
                            }
                        }
                    }
                    let l = cy * gw + cx;
                    vol[l] = sv / n.max(1.0);
                    hs.k[l] = sk / n.max(1.0);
                    let (px, py) = ((ox + cx * g) as f32 + 0.5 * g as f32 - c.0, (oy + cy * g) as f32 + 0.5 * g as f32 - c.1);
                    let d = (px * px + py * py).sqrt();
                    hs.domain[l] = vol[l] > 0.3 && d < reach;
                    if d <= r {
                        hs.air[l] = true;
                    } else if hs.domain[l] && (cx == 0 || cy == 0 || cx + 1 == gw || cy + 1 == gh || d > reach - 1.5 * g as f32) {
                        // the paint beyond the jet's reach holds it
                        hs.domain[l] = false;
                        hs.sink[l] = true;
                    }
                    if hs.domain[l] {
                        fluid.0 += hs.k[l];
                        fluid.1 += 1.0;
                    }
                }
            }
            // bare cells joined to the jet through bare cells (the channels it already blew) carry
            // its pressure; other bare canvas is open air beyond the paint, where the paint can go
            let mut stack: Vec<usize> = (0..gw * gh).filter(|&l| hs.air[l]).collect();
            while let Some(l) = stack.pop() {
                let (lx, ly) = (l % gw, l / gw);
                for (nx, ny) in [(lx.wrapping_sub(1), ly), (lx + 1, ly), (lx, ly.wrapping_sub(1)), (lx, ly + 1)] {
                    if nx < gw && ny < gh {
                        let j = ny * gw + nx;
                        if !hs.air[j] && !hs.domain[j] && !hs.sink[j] {
                            hs.air[j] = true;
                            stack.push(j);
                        }
                    }
                }
            }
            // flood fill stops at paint; what is left bare and not reached is open air
            for l in 0..gw * gh {
                if !hs.air[l] && !hs.domain[l] {
                    hs.sink[l] = true;
                }
            }
            let paint_cells = hs.domain.iter().zip(&hs.air).filter(|(d, a)| **d && !**a).count();
            if paint_cells == 0 {
                continue;
            }
            let ctr = |l: usize| -> (f32, f32) {
                (((ox + (l % gw) * g) as f32 + 0.5 * g as f32 - c.0), ((oy + (l / gw) * g) as f32 + 0.5 * g as f32 - c.1))
            };
            let m = contrast.unwrap_or_else(|| 1.0 + 30.0 * fluid.0 / fluid.1.max(1.0));
            let eta = eta_for_contrast(m);
            let bias: Vec<f32> = (0..gw * gh)
                .map(|l| {
                    let (dx, dy) = ctr(l);
                    let d = (dx * dx + dy * dy).sqrt().max(1e-3);
                    1.0 + 3.0 * ((dx * u.0 + dy * u.1) / d).max(0.0)
                })
                .collect();
            // the air a station blows in: about the jet's own area per jet radius travelled (more,
            // held in one place), so a pass clears a track a few jet widths across, tendrils and all
            let dwell = if stations.len() == 1 { 10.0 } else { 1.0 };
            let n = ((dwell * pressure * 1.5 * (r / g as f32).powi(2)).round() as usize).clamp(1, (paint_cells / 4).max(1));
            let invaded = hs.grow(n, eta, Some(&bias), seed.wrapping_add(si as u64 * 0x9E37));
            // a station that moved one cell has no shape to measure
            if invaded.len() >= 2 {
                bsum += hs.branching(&invaded);
                bn += 1;
            }
            out.stations += 1;
            // displace, pixel by pixel: the jet first clears what is under it, straight out from its
            // centre, then each finger, from where it grew to where it reached, shoves the paint in its
            // way to its sides and tip (`push_out`), so the fingers are `tendril` wide whatever the
            // cell, and run at any angle
            let film = 0.15f32;
            self.push_out(c, c, r, film, 0.5 * pressure);
            let hw = 0.5 * tendril * s;
            let to_px = |q: (f32, f32)| (ox as f32 + q.0 * g as f32, oy as f32 + q.1 * g as f32);
            for (pa, pb) in hs.segments(&invaded) {
                self.push_out(to_px(pa), to_px(pb), hw.max(0.7), film, 0.5 * pressure);
            }
            bx0 = bx0.min(ox);
            by0 = by0.min(oy);
            bx1 = bx1.max(ex);
            by1 = by1.max(ey);
        }
        if bx0 < bx1 && by0 < by1 {
            // the displaced paint slumps a little as the air passes: a short levelling flow (thick to
            // thin, conservative) rounds the cell-sized steps of the fingers into rims
            let contact = vec![1.0f32; w * h];
            self.spread_pressed(&contact, 3, 0.2, (bx0, by0, bx1, by1));
            self.wet.touch(bx0, by0, bx1, by1);
            self.surf_gen += 1;
        }
        out.branching = if bn > 0 { bsum / bn as f32 } else { 0.0 };
        out.moved_mm3 = 0.5 * self.wet.vol.iter().zip(&before).map(|(a, b)| (a - b).abs() as f64).sum::<f64>() * self.mm3_per_coat_px();
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Air pushed from the centre of a uniform disc of paint: how branched the invaded set is, for
    /// a viscosity contrast `m`, averaged over seeds.
    fn branching_at(m: f32) -> f32 {
        let mut total = 0.0;
        for seed in 0..4u64 {
            let n = 81;
            let mut hs = Hele::new(n, n);
            for y in 0..n {
                for x in 0..n {
                    let (dx, dy) = (x as f32 - 40.0, y as f32 - 40.0);
                    let d = (dx * dx + dy * dy).sqrt();
                    let l = y * n + x;
                    hs.domain[l] = d < 39.0;
                    hs.air[l] = d < 1.5;
                    hs.sink[l] = hs.domain[l] && d > 37.5;
                }
            }
            let inv = hs.grow(700, eta_for_contrast(m), None, seed);
            total += hs.branching(&inv);
        }
        total / 4.0
    }

    #[test]
    fn fingers_branch_more_as_the_viscosity_contrast_rises() {
        let (lo, mid, hi) = (branching_at(1.0), branching_at(3.0), branching_at(100.0));
        assert!(lo < mid && mid < hi, "branching {lo} {mid} {hi}");
        assert!(hi > 1.5 * lo, "branching {lo} -> {hi}");
    }
}
