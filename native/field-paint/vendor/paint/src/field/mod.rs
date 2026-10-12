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

impl crate::Canvas {
    /// Key the next stroke's noise (the hand's shake, the knife's tears, stiff paint's clumping) on
    /// `noise` instead of on its place in the order of strokes, so that a stroke painted with the same
    /// noise on two canvases (a stereo pair) shakes, tears and clumps alike on both. The next drag,
    /// touch, knife pull or segmented stroke takes it.
    pub fn set_stroke_noise(&mut self, noise: u32) {
        self.stroke_noise = Some(noise);
    }
}
