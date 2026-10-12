//! Field's additions to the crate (see `VENDORED.md` beside `Cargo.toml`). The crate itself is
//! claude-paint's `paint`, by alice (https://github.com/aliceisjustplaying/claude-paint, MIT); what
//! is in this module is Field's.
//!
//! Everything here is new code, not upstream's: it uses the crate's internals where it has to
//! (a held brush's bristles are crate-private), and otherwise only touches upstream files through
//! the hooks `VENDORED.md` lists.

pub mod state;
pub mod aqueous;
pub mod watercolor;
pub mod bristles;
pub mod warp;

impl crate::Canvas {
    /// Key the next stroke's noise (the hand's shake, the knife's tears, stiff paint's clumping) on
    /// `noise` instead of on its place in the order of strokes, so that a stroke painted with the same
    /// noise on two canvases (a stereo pair) shakes, tears and clumps alike on both. The next drag,
    /// touch, knife pull or segmented stroke takes it.
    pub fn set_stroke_noise(&mut self, noise: u32) {
        self.stroke_noise = Some(noise);
    }

    /// Give the next stroke its length along itself in the world, mm, at each of its input points
    /// (non-decreasing): see `Canvas::stroke_arc`.
    pub fn set_stroke_arc(&mut self, arc_mm: Vec<f32>) {
        self.stroke_arc = Some(arc_mm);
    }

    /// The next stroke's world arc, taken, as pixels of this canvas, if it has one point for each of
    /// the stroke's `n` input points.
    pub(crate) fn take_arc_px(&mut self, n: usize) -> Option<Vec<f32>> {
        let s = self.f.scale;
        self.take_arc_units(n).map(|a| a.iter().map(|v| v * s).collect())
    }

    /// The next stroke's world arc, taken, as canvas units, if it has one point for each of the
    /// stroke's `n` input points.
    pub fn take_arc_units(&mut self, n: usize) -> Option<Vec<f32>> {
        let a = self.stroke_arc.take()?;
        if a.len() != n || a.iter().any(|v| !v.is_finite()) {
            return None;
        }
        let k = 1.0 / self.mm_per_unit;
        Some(a.iter().map(|v| v * k).collect())
    }
}

/// `y` at `x` on the piecewise-linear curve through (`xs`, `ys`) (`xs` non-decreasing), held at its
/// ends.
pub(crate) fn interp(xs: &[f32], ys: &[f32], x: f32) -> f32 {
    let n = xs.len();
    if n == 0 {
        return x;
    }
    if x <= xs[0] {
        return ys[0];
    }
    if x >= xs[n - 1] {
        return ys[n - 1];
    }
    let i = xs.partition_point(|&v| v <= x).max(1);
    let (x0, x1) = (xs[i - 1], xs[i]);
    let f = if x1 > x0 { (x - x0) / (x1 - x0) } else { 0.0 };
    ys[i - 1] + (ys[i] - ys[i - 1]) * f
}
