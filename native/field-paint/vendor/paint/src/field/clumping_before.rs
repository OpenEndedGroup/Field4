//! What a brush of moving hairs paints at `clumping` 0 (Field): pinned to the pixels and wet paint
//! it painted before liquid bridges were added (`field::bristles`), so that the default clumping
//! stays the tip-pair model it was.
#[cfg(test)]
mod tests {
    use crate::bristle::{Held, Tool};
    use crate::field::bristles::{stroke, Bristles, Pose};
    use crate::{Canvas, Paint};

    #[test]
    fn clumping_zero_paints_as_brushes_did_before_liquid_bridges() {
        let mut out = String::new();
        for (kind, splay) in [(0u8, 0.0f32), (0, 2.0), (1, 1.0)] {
            let mut c = Canvas::new(240, 1.0, [0.95, 0.94, 0.9]);
            let mut tool = if kind == 0 { Tool::hog_flat(60.0) } else { Tool::round_sable(40.0) };
            tool.splay = splay;
            let mut held = Held::new(tool, 5).with_engine(c.engine());
            held.load(Paint::body([0.1, 0.08, 0.06]), 1.0);
            let mut b = Bristles::new(&held.tool, c.mm_per_unit(), 32, 5, 3).with_held(held);
            let samples: Vec<Pose> = (0..=40).map(|i| { let u = i as f32 / 40.0; Pose { x: 150.0 + 700.0 * u, y: 500.0 + 150.0 * (u * 6.0).sin(), t: 1.5 * u, press: 0.9, tilt: 0.4, azimuth: 0.0, roll: 1.57 } }).collect();
            stroke(&mut c, None, &mut b, &samples, None);
            let mut h: u64 = 0xcbf29ce484222325;
            for v in c.pixels().iter().flatten().chain(c.wet.vol.iter()) { h ^= v.to_bits() as u64; h = h.wrapping_mul(0x100000001b3); }
            out += &format!("{h:016x} ");
        }
        // (digests of three strokes painted by the brushes of before liquid bridges: a hog flat, the
        // same splayed, a round sable)
        assert_eq!(out.trim(), "c44622f40e8b7595 11b85d1c8f008738 7f586d6cd966674d", "a moving brush at clumping 0 no longer paints as it did");
    }
}
