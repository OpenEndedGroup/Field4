//! What a canvas handle points at: the crate's canvas and, once water has touched it, the water on
//! and in its sheet (`paint::field::aqueous`).
//!
//! The two are kept in order. Oil paint, graphite and a knife's slab laid over watercolour seal it:
//! `oil` finds the pixels an operation changed and lays the watercolour's pigment there into the
//! canvas's dry picture, under the new paint. Whole-canvas operations (prime, crack) seal it all
//! first, and a glaze its region. Watercolour laid over paint stays above it, in the water's layer,
//! which every render puts over the canvas.

use paint::field::aqueous::Water;
use paint::field::watercolor::{bake, changed, lay_over, snapshot};
use paint::Canvas;
use std::io::{Read, Write};

#[derive(Clone)]
pub struct Studio {
    pub canvas: Canvas,
    pub water: Option<Box<Water>>,
}

impl std::ops::Deref for Studio {
    type Target = Canvas;
    fn deref(&self) -> &Canvas {
        &self.canvas
    }
}

impl std::ops::DerefMut for Studio {
    fn deref_mut(&mut self) -> &mut Canvas {
        &mut self.canvas
    }
}

/// A file holding a canvas and its water (a plain canvas is written as the crate writes it).
const MAGIC: &[u8; 8] = b"FPSTUDI1";

impl Studio {
    pub fn new(canvas: Canvas) -> Studio {
        Studio { canvas, water: None }
    }

    fn has_pigment(&self) -> bool {
        self.water.as_ref().is_some_and(|w| w.has_pigment())
    }

    /// Run a canvas operation that lays paint or graphite, sealing the watercolour it covers.
    pub fn oil<T>(&mut self, f: impl FnOnce(&mut Canvas) -> T) -> T {
        if !self.has_pigment() && self.water.is_none() {
            return f(&mut self.canvas);
        }
        let before = snapshot(&self.canvas);
        let out = f(&mut self.canvas);
        let covered = changed(&self.canvas, &before);
        if let Some(w) = self.water.as_mut() {
            let sealed = w.seal(&covered);
            bake(&mut self.canvas, &sealed);
        }
        out
    }

    /// Seal the watercolour at `covered` pixels (all of it when `None`) before an operation that
    /// paints over them in the dry picture itself (a glaze, a ground, cracks).
    pub fn seal_first(&mut self, covered: Option<Vec<bool>>) {
        if let Some(w) = self.water.as_mut() {
            let covered = covered.unwrap_or_else(|| vec![true; w.w * w.h]);
            let sealed = w.seal(&covered);
            bake(&mut self.canvas, &sealed);
        }
    }

    /// Let `minutes` pass for the oil and the water alike.
    pub fn wait(&mut self, minutes: f32) {
        self.canvas.wait(minutes);
        if let Some(w) = self.water.as_mut() {
            w.advance(minutes as f64 * 60.0);
        }
    }

    /// Dry everything: the oil to touch-dry, the water evaporated.
    pub fn dry(&mut self) {
        if let Some(w) = self.water.as_mut() {
            w.dry_out();
        }
        self.canvas.dry();
    }

    /// The finished picture: a copy dried to touch-dry, the watercolour laid into it, lit as asked.
    pub fn picture(&self, relief: f32, gloss: f32) -> Vec<paint::Rgb> {
        let mut copy = self.canvas.clone();
        copy.dry();
        if let Some(w) = &self.water {
            // the pigment as it lies now: drying it first would move it, and a render changes nothing
            lay_over(&mut copy, w);
        }
        if relief > 0.0 {
            copy.relief(relief, gloss.max(0.0));
        }
        copy.pixels().to_vec()
    }

    /// The canvas as it looks now: wet paint at its thickness, the water's pigment over it, wet paper
    /// a little darker.
    pub fn seen_now(&self) -> Vec<paint::Rgb> {
        let base = self.canvas.seen();
        match &self.water {
            Some(w) => w.over(&base, true),
            None => base,
        }
    }

    /// Under a light: the relief of the dried copy (`dried`) or of the canvas now.
    pub fn lit(&self, azimuth: f32, elevation: f32, strength: f32, dried: bool) -> Vec<paint::Rgb> {
        if dried {
            let mut copy = self.canvas.clone();
            copy.dry();
            if let Some(w) = &self.water {
                lay_over(&mut copy, w);
            }
            copy.seen_lit(azimuth, elevation, strength)
        } else {
            let base = self.canvas.seen_lit(azimuth, elevation, strength);
            match &self.water {
                Some(w) => w.over(&base, true),
                None => base,
            }
        }
    }

    pub fn write_state(&self, out: &mut impl Write) -> std::io::Result<()> {
        match &self.water {
            None => self.canvas.write_state(out, "field-paint"),
            Some(w) => {
                let mut canvas = Vec::new();
                self.canvas.write_state(&mut canvas, "field-paint")?;
                let water = paint::field::watercolor::write_water(w);
                out.write_all(MAGIC)?;
                out.write_all(&(canvas.len() as u64).to_le_bytes())?;
                out.write_all(&canvas)?;
                out.write_all(&(water.len() as u64).to_le_bytes())?;
                out.write_all(&water)
            }
        }
    }

    pub fn read_state(input: &mut impl Read) -> Result<Studio, String> {
        let mut all = Vec::new();
        input.read_to_end(&mut all).map_err(|e| e.to_string())?;
        if all.len() >= 8 && &all[..8] == MAGIC {
            let len = |at: usize| -> Result<usize, String> {
                Ok(u64::from_le_bytes(all.get(at..at + 8).ok_or("truncated studio state")?.try_into().unwrap()) as usize)
            };
            let n = len(8)?;
            let canvas_bytes = all.get(16..16 + n).ok_or("truncated studio state")?;
            let (canvas, _) = Canvas::read_state(&mut &canvas_bytes[..]).map_err(|e| e.to_string())?;
            let m = len(16 + n)?;
            let water = paint::field::watercolor::read_water(all.get(24 + n..24 + n + m).ok_or("truncated studio state")?)?;
            return Ok(Studio { canvas, water: Some(Box::new(water)) });
        }
        let (canvas, _) = Canvas::read_state(&mut &all[..]).map_err(|e| e.to_string())?;
        Ok(Studio::new(canvas))
    }
}
