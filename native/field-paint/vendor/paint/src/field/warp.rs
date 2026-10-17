//! A stereo pair's warp (Field): where each place on this canvas is on the reference canvas, the
//! other eye's (or a cyclopean one). Two eyes see a painted surface at different places on their
//! canvases; texture keyed on canvas position (the ground's weave and priming, the sheet's fibres, a
//! pencil's grain, tacky paint's grab) lands at the same place on both and so reads at the screen's
//! depth, not the surface's. Looked up through the warp, it lands where the surface is in each.
//!
//! The warp is a grid of reference positions (whole-canvas pixels) every `cell` pixels, read
//! bilinearly; outside the grid it holds its edge displacement. `Canvas::warp_ground` resamples a
//! freshly made ground (and a watercolour sheet) through it: generated in the reference frame,
//! pulled back into this one.

use crate::Canvas;

#[derive(Clone, Debug)]
pub struct Warp {
    /// Grid nodes across and down.
    pub gw: usize,
    pub gh: usize,
    /// Pixels between nodes.
    pub cell: f32,
    /// The reference position (whole-canvas px) of each node, row major.
    pub at: Vec<[f32; 2]>,
}

impl Warp {
    /// The grid's nodes, `gw × gh` of them `cell` px apart, each at `at` (whole-canvas px): `None`
    /// if the grid is degenerate or any position not finite.
    pub fn new(gw: usize, gh: usize, cell: f32, at: Vec<[f32; 2]>) -> Option<Warp> {
        (gw >= 2 && gh >= 2 && cell.is_finite() && cell > 0.0 && at.len() == gw * gh && at.iter().all(|p| p[0].is_finite() && p[1].is_finite()))
            .then_some(Warp { gw, gh, cell, at })
    }

    /// Where whole-canvas pixel position (`x`, `y`) is on the reference canvas.
    pub fn map(&self, x: f32, y: f32) -> (f32, f32) {
        let (u, v) = (x / self.cell, y / self.cell);
        let (mu, mv) = ((self.gw - 1) as f32, (self.gh - 1) as f32);
        let (cu, cv) = (u.clamp(0.0, mu), v.clamp(0.0, mv));
        let (i, j) = ((cu.floor() as usize).min(self.gw - 2), (cv.floor() as usize).min(self.gh - 2));
        let (fu, fv) = (cu - i as f32, cv - j as f32);
        let p = |a: usize, b: usize| self.at[b * self.gw + a];
        let (p00, p10, p01, p11) = (p(i, j), p(i + 1, j), p(i, j + 1), p(i + 1, j + 1));
        let l = |k: usize| (p00[k] * (1.0 - fu) + p10[k] * fu) * (1.0 - fv) + (p01[k] * (1.0 - fu) + p11[k] * fu) * fv;
        // past the grid's edge, the edge's displacement carried on
        (l(0) + (u - cu) * self.cell, l(1) + (v - cv) * self.cell)
    }
}

/// `buf` (`w × h`, the frame at `x0`, `y0`) read bilinearly at whole-canvas pixel position
/// (`x`, `y`), the nearest edge pixel past its edges.
fn sample<T: Copy>(buf: &[T], w: usize, h: usize, x0: usize, y0: usize, x: f32, y: f32, mix: impl Fn(T, T, f32) -> T) -> T {
    // pixel centres are at +0.5
    let (fx, fy) = ((x - x0 as f32 - 0.5).clamp(0.0, (w - 1) as f32), (y - y0 as f32 - 0.5).clamp(0.0, (h - 1) as f32));
    let (i, j) = ((fx.floor() as usize).min(w.saturating_sub(2)), (fy.floor() as usize).min(h.saturating_sub(2)));
    let (i1, j1) = ((i + 1).min(w - 1), (j + 1).min(h - 1));
    let (u, v) = (fx - i as f32, fy - j as f32);
    let top = mix(buf[j * w + i], buf[j * w + i1], u);
    let bot = mix(buf[j1 * w + i], buf[j1 * w + i1], u);
    mix(top, bot, v)
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// Resample `buf` (`w × h` at `x0`, `y0`) through `warp`: each pixel takes the value at its place on
/// the reference canvas.
pub(crate) fn resample<T: Copy + Send + Sync>(buf: &mut Vec<T>, w: usize, h: usize, x0: usize, y0: usize, warp: &Warp, mix: impl Fn(T, T, f32) -> T + Sync) {
    if buf.len() != w * h || w == 0 || h == 0 {
        return;
    }
    use rayon::prelude::*;
    let src = std::mem::take(buf);
    *buf = (0..w * h).into_par_iter().map(|i| {
        let (x, y) = warp.map((i % w + x0) as f32 + 0.5, (i / w + y0) as f32 + 0.5);
        sample(&src, w, h, x0, y0, x, y, &mix)
    }).collect();
}

impl Canvas {
    /// Look up position-keyed texture through `warp` from now on (`None`: this canvas is the
    /// reference).
    pub fn set_warp(&mut self, warp: Option<Warp>) {
        self.warp = warp.map(Box::new);
    }

    /// Pull this canvas's ground (its colour, relief, size film, gloss, absorbency and raw cloth)
    /// back through its warp, as if made in the reference frame: for a fresh canvas, before any paint
    /// (wet paint is not moved).
    pub fn warp_ground(&mut self) {
        let Some(warp) = self.warp.clone() else { return };
        let f = self.f;
        let (w, h, x0, y0) = (f.w, f.h, f.x0, f.y0);
        let rgb = |a: [f32; 3], b: [f32; 3], t: f32| [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
        resample(&mut self.px, w, h, x0, y0, &warp, rgb);
        resample(&mut self.height, w, h, x0, y0, &warp, lerp);
        resample(&mut self.film, w, h, x0, y0, &warp, lerp);
        resample(&mut self.gloss, w, h, x0, y0, &warp, lerp);
        resample(&mut self.absorb, w, h, x0, y0, &warp, lerp);
        if let Some(s) = self.soak.as_mut() {
            resample(&mut s.weave, w, h, x0, y0, &warp, lerp);
        }
        self.surf_gen += 1;
        self.base = None;
    }
}

impl super::aqueous::Water {
    /// Pull the sheet (its relief, capacity, fibres and wicking) back through `warp`, as
    /// `Canvas::warp_ground` does the canvas's surface: before any water.
    pub fn warp_sheet(&mut self, warp: &Warp, x0: usize, y0: usize) {
        let (w, h) = (self.w, self.h);
        resample(&mut self.relief, w, h, x0, y0, warp, lerp);
        resample(&mut self.cap, w, h, x0, y0, warp, lerp);
        resample(&mut self.perm, w, h, x0, y0, warp, lerp);
        // fibres lie along a direction, not toward one: blend them as doubled angles
        let mut d: Vec<[f32; 2]> = self.fdir.iter().map(|a| [(2.0 * a).cos(), (2.0 * a).sin()]).collect();
        resample(&mut d, w, h, x0, y0, warp, |a, b, t| [lerp(a[0], b[0], t), lerp(a[1], b[1], t)]);
        self.fdir = d.iter().map(|v| 0.5 * v[1].atan2(v[0])).collect();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shift(dx: f32, dy: f32) -> Warp {
        let (gw, gh, cell) = (5, 5, 64.0);
        Warp::new(gw, gh, cell, (0..gw * gh).map(|i| [(i % gw) as f32 * cell + dx, (i / gw) as f32 * cell + dy]).collect()).unwrap()
    }

    #[test]
    fn a_shift_maps_everywhere_even_past_the_grid() {
        let w = shift(7.0, -3.0);
        for (x, y) in [(0.0, 0.0), (100.5, 37.25), (256.0, 256.0), (900.0, -40.0)] {
            let (rx, ry) = w.map(x, y);
            assert!((rx - x - 7.0).abs() < 1e-3 && (ry - y + 3.0).abs() < 1e-3, "{x},{y} -> {rx},{ry}");
        }
    }

    #[test]
    fn a_whole_pixel_shift_moves_a_buffer_exactly() {
        let (w, h) = (40, 30);
        let src: Vec<f32> = (0..w * h).map(|i| crate::rng::hash2((i % w) as i64, (i / w) as i64, 9)).collect();
        let mut b = src.clone();
        resample(&mut b, w, h, 0, 0, &shift(3.0, 2.0), lerp);
        for y in 0..h - 2 {
            for x in 0..w - 3 {
                assert!((b[y * w + x] - src[(y + 2) * w + x + 3]).abs() < 1e-5);
            }
        }
    }

    #[test]
    fn degenerate_grids_are_refused() {
        assert!(Warp::new(1, 4, 8.0, vec![[0.0; 2]; 4]).is_none());
        assert!(Warp::new(2, 2, 8.0, vec![[0.0, f32::NAN]; 4]).is_none());
        assert!(Warp::new(2, 2, 0.0, vec![[0.0; 2]; 4]).is_none());
    }
}
