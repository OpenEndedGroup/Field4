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
//! The pattern is computed, not painted: callers use the cells the air reached to decide where paint
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
}

impl Hele {
    pub fn new(w: usize, h: usize) -> Self {
        let n = w * h;
        Hele { w, h, domain: vec![false; n], air: vec![false; n], sink: vec![false; n], k: vec![1.0; n], q: vec![0.0; n], p: vec![0.0; n] }
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

    /// How branched the air that invaded the paint is: the edges between invaded cells and paint
    /// that is left, per invaded cell. A compact blob of `n` cells has about `4 / sqrt(n)` of them;
    /// a tree of one-cell-wide fingers about 2.
    pub fn branching(&self, invaded: &[usize]) -> f32 {
        if invaded.is_empty() {
            return 0.0;
        }
        let edges: usize = invaded.iter().map(|&i| self.nbrs(i).filter(|&j| self.domain[j] && !self.air[j]).count()).sum();
        edges as f32 / invaded.len() as f32
    }
}

/// What a blow did: mm³ of paint moved, and how branched the tendrils it drove are (`Hele::branching`,
/// averaged over the jet's stations).
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

    /// Move all but `film` coats of the wet paint in coarse cell (`ax`, `ay`) to the same places in
    /// cell (`bx`, `by`), cells `g` pixels across starting at buffer pixel (`ox`, `oy`).
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn shift_cell(&mut self, ox: usize, oy: usize, g: usize, a: (usize, usize), b: (usize, usize), film: f32) {
        let (w, h) = (self.f.w, self.f.h);
        for dy in 0..g {
            for dx in 0..g {
                let (sx, sy) = (ox + a.0 * g + dx, oy + a.1 * g + dy);
                let (tx, ty) = (ox + b.0 * g + dx, oy + b.1 * g + dy);
                if sx >= w || sy >= h || tx >= w || ty >= h {
                    continue;
                }
                let (s, t) = (sy * w + sx, ty * w + tx);
                let v = (self.wet.vol[s] - film).max(0.0);
                self.move_paint(s, t, v);
            }
        }
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
        let g = (tendril * s).round().max(1.0) as usize;
        let reach = 6.0 * r;
        let pressure = pressure.clamp(0.0, 1.0);
        let before = self.wet.vol.clone();
        let mut out = Blown::default();
        let mut bsum = 0.0f32;
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
            // paint right under the jet is blown out of it first, away from the jet's centre
            let film = 0.15f32;
            let ctr = |l: usize| -> (f32, f32) {
                (((ox + (l % gw) * g) as f32 + 0.5 * g as f32 - c.0), ((oy + (l / gw) * g) as f32 + 0.5 * g as f32 - c.1))
            };
            let mut under: Vec<usize> = (0..gw * gh).filter(|&l| hs.air[l] && vol[l] > film).collect();
            under.sort_by(|&a, &b| {
                let (da, db) = (ctr(a), ctr(b));
                (db.0 * db.0 + db.1 * db.1).partial_cmp(&(da.0 * da.0 + da.1 * da.1)).unwrap()
            });
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
            let n = ((dwell * pressure * 1.5 * (r / g as f32).powi(2)).round() as usize).clamp(1, paint_cells);
            let invaded = hs.grow(n, eta, Some(&bias), seed.wrapping_add(si as u64 * 0x9E37));
            bsum += hs.branching(&invaded);
            out.stations += 1;
            // displace: each cell the air reached sends its paint on to its neighbour that holds
            // the most pressure (the paint ahead), keeping a thin film; jet cells first, outermost
            // first, then the fingers in the order they grew
            for &l in &under {
                let (dx, dy) = ctr(l);
                let d = (dx * dx + dy * dy).sqrt();
                let (ex_, ey_) = if d > 1e-3 { (dx / d, dy / d) } else { (u.0, u.1) };
                let (mut px, mut py) = ((l % gw) as f32 + 0.5, (l / gw) as f32 + 0.5);
                let mut target = None;
                for _ in 0..(2 * gw + 2 * gh) {
                    px += ex_;
                    py += ey_;
                    if px < 0.0 || py < 0.0 || px >= gw as f32 || py >= gh as f32 {
                        break;
                    }
                    let j = py as usize * gw + px as usize;
                    if !hs.air[j] {
                        target = Some(j);
                        break;
                    }
                }
                if let Some(j) = target {
                    self.shift_cell(ox, oy, g, (l % gw, l / gw), (j % gw, j / gw), film);
                }
            }
            for &l in &invaded {
                let (lx, ly) = (l % gw, l / gw);
                let (dx, dy) = ctr(l);
                let mut best: Option<(usize, f32)> = None;
                for (nx, ny) in [(lx.wrapping_sub(1), ly), (lx + 1, ly), (lx, ly.wrapping_sub(1)), (lx, ly + 1)] {
                    if nx >= gw || ny >= gh {
                        continue;
                    }
                    let j = ny * gw + nx;
                    if hs.air[j] {
                        continue;
                    }
                    let (jx, jy) = ctr(j);
                    let outward = (jx * jx + jy * jy).sqrt() - (dx * dx + dy * dy).sqrt();
                    let score = hs.p[j] + 0.02 * outward;
                    if best.is_none_or(|b| score > b.1) {
                        best = Some((j, score));
                    }
                }
                if let Some((j, _)) = best {
                    self.shift_cell(ox, oy, g, (lx, ly), (j % gw, j / gw), film);
                    if !self.wet.solv.is_empty() {
                        for yy in 0..g {
                            for xx in 0..g {
                                let (x, y) = (ox + lx * g + xx, oy + ly * g + yy);
                                if x < w && y < h {
                                    self.wet.solv[y * w + x] *= 1.0 - 0.5 * pressure;
                                }
                            }
                        }
                    }
                }
            }
            bx0 = bx0.min(ox);
            by0 = by0.min(oy);
            bx1 = bx1.max(ex);
            by1 = by1.max(ey);
        }
        if bx0 < bx1 && by0 < by1 {
            self.wet.touch(bx0, by0, bx1, by1);
            self.surf_gen += 1;
        }
        out.branching = if out.stations > 0 { bsum / out.stations as f32 } else { 0.0 };
        out.moved_mm3 = 0.5 * self.wet.vol.iter().zip(&before).map(|(a, b)| (a - b).abs() as f64).sum::<f64>() * self.mm3_per_coat_px();
        out
    }
}

/// The air's fingers as a field over buffer pixels in -1..1 for a peel: -1 where the air reached
/// between the surfaces, +1 where the paint held, 0 outside the pressed film. `contact` (per buffer
/// pixel) marks the film; `opening` (per buffer pixel, >= 0) is how fast the gap opens there, which
/// is where the air is drawn in from; `fraction` of the film is invaded; fingers are `cell` pixels
/// wide. Used by the fold's and the plate's peel in place of a noise texture.
pub(crate) fn peel_fingers(
    canvas: &Canvas, contact: &[f32], bounds: (usize, usize, usize, usize), opening: &dyn Fn(usize, usize) -> f32, fraction: f32,
    cell: usize, eta: f32, seed: u64,
) -> Vec<f32> {
    let (w, _h) = (canvas.f.w, canvas.f.h);
    let (x0, y0, x1, y1) = bounds;
    let g = cell.max(1);
    let mut field = vec![0.0f32; contact.len()];
    if x0 >= x1 || y0 >= y1 {
        return field;
    }
    // one cell of air all round, where the air comes in from
    let (gw, gh) = ((x1 - x0).div_ceil(g) + 2, (y1 - y0).div_ceil(g) + 2);
    let mut hs = Hele::new(gw, gh);
    for cy in 1..gh - 1 {
        for cx in 1..gw - 1 {
            let (mut sc, mut sk, mut sq, mut n) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
            for dy in 0..g {
                for dx in 0..g {
                    let (x, y) = (x0 + (cx - 1) * g + dx, y0 + (cy - 1) * g + dy);
                    if x < x1 && y < y1 {
                        let i = y * w + x;
                        sc += contact[i];
                        sk += canvas.mobility(i);
                        sq += opening(x, y);
                        n += 1.0;
                    }
                }
            }
            let l = cy * gw + cx;
            let n = n.max(1.0);
            hs.domain[l] = sc / n > 0.25;
            hs.k[l] = sk / n;
            hs.q[l] = 0.05 * sq / n;
        }
    }
    // air comes in only where the gap has opened: not across a fold's hinge, nor past a plate's
    // leading edge (walls there)
    for l in 0..gw * gh {
        if hs.domain[l] {
            continue;
        }
        let (cx, cy) = ((l % gw) as i64 - 1, (l / gw) as i64 - 1);
        let x = (x0 as i64 + cx * g as i64 + g as i64 / 2).clamp(0, canvas.f.w as i64 - 1) as usize;
        let y = (y0 as i64 + cy * g as i64 + g as i64 / 2).clamp(0, canvas.f.h as i64 - 1) as usize;
        hs.air[l] = opening(x, y) > 0.25;
    }
    let cells = hs.domain.iter().filter(|d| **d).count();
    let n = (fraction.clamp(0.0, 1.0) * cells as f32).round() as usize;
    hs.grow(n, eta, None, seed);
    // sample the cell pattern at pixels, bilinearly, so the fingers' edges are not staircases
    let val = |cx: i64, cy: i64| -> f32 {
        if cx < 0 || cy < 0 || cx >= gw as i64 || cy >= gh as i64 {
            return -1.0;
        }
        let l = cy as usize * gw + cx as usize;
        if hs.domain[l] && !hs.air[l] { 1.0 } else { -1.0 }
    };
    for y in y0..y1 {
        for x in x0..x1 {
            let i = y * w + x;
            if contact[i] <= 0.0 {
                continue;
            }
            let fx = (x - x0) as f32 / g as f32 + 1.0 - 0.5;
            let fy = (y - y0) as f32 / g as f32 + 1.0 - 0.5;
            let (ix, iy) = (fx.floor() as i64, fy.floor() as i64);
            let (tx, ty) = (fx - ix as f32, fy - iy as f32);
            let a = val(ix, iy) + (val(ix + 1, iy) - val(ix, iy)) * tx;
            let b = val(ix, iy + 1) + (val(ix + 1, iy + 1) - val(ix, iy + 1)) * tx;
            field[i] = ((a + (b - a) * ty) * 2.0).clamp(-1.0, 1.0);
        }
    }
    field
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
