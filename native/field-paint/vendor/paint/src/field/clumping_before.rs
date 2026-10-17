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

#[cfg(test)]
mod tooth_tests {
    use crate::bristle::{Gesture, Held, Tool};
    use crate::{Canvas, Paint};

    /// A hog flat loaded lightly and pressed hard along a line, with `tooth`, on linen (200 mm at
    /// 1200 px, fine enough to see its weave): how far the paint in the dry end of its track follows
    /// the weave (the correlation of the film's thickness with the ground's height: 0 none, 1 paint
    /// on the peaks only), and how evenly it lies on a smooth ground (the film's spread over its mean).
    fn dry_end(tooth: f32, linen: bool) -> f32 {
        dry_end_with(tooth, linen, None, None)
    }

    fn dry_end_with(tooth: f32, linen: bool, push: Option<f32>, pickup: Option<f32>) -> f32 {
        let mut c = Canvas::new(1200, 1.0, [0.95, 0.94, 0.9]).with_size_mm(200.0);
        if linen {
            c = c.with_linen(crate::surface::Linen::fine(3));
        }
        let mut tool = Tool::hog_flat(60.0);
        tool.tooth = tooth;
        tool.run = 200.0;
        if let Some(p) = push { tool.push = p; }
        if let Some(p) = pickup { tool.pickup = p; }
        let mut h = Held::new(tool, 5).with_engine(c.engine());
        h.load(Paint::body([0.1, 0.08, 0.06]), 0.25);
        c.drag(&mut h, &Gesture::line((100.0, 500.0), (900.0, 500.0)).pressure(1.0, 1.0).shake(0.0), None);
        let w = c.f.w;
        let idx: Vec<usize> = (w * 47 / 100..w * 53 / 100).flat_map(|y| (w * 6 / 10..w * 85 / 100).map(move |x| y * w + x)).collect();
        let v: Vec<f32> = idx.iter().map(|&i| c.wet.vol[i]).collect();
        let mv = v.iter().sum::<f32>() / v.len() as f32;
        let sv = (v.iter().map(|x| (x - mv) * (x - mv)).sum::<f32>() / v.len() as f32).sqrt();
        if !linen {
            return sv / mv.max(1e-9);
        }
        let g: Vec<f32> = idx.iter().map(|&i| c.height[i]).collect();
        let mg = g.iter().sum::<f32>() / g.len() as f32;
        let sg = (g.iter().map(|x| (x - mg) * (x - mg)).sum::<f32>() / g.len() as f32).sqrt();
        v.iter().zip(&g).map(|(a, b)| (a - mv) * (b - mg)).sum::<f32>() / v.len() as f32 / (sv * sg).max(1e-12)
    }

    #[test]
    #[ignore]
    fn print_tooth_push_pickup() {
        for (push, pickup) in [(None, None), (Some(0.0), None), (None, Some(0.0)), (Some(0.0), Some(0.0))] {
            let r: Vec<String> = [0.0f32, 1.0, 2.0].iter().map(|&t| format!("tooth {t}: {:.2}", dry_end_with(t, true, push, pickup))).collect();
            eprintln!("push {push:?} pickup {pickup:?}: {}", r.join(", "));
        }
    }

    #[test]
    fn a_dry_bristle_rides_the_tooth_on_linen_and_thins_evenly_on_a_smooth_ground() {
        let (l0, l1) = (dry_end(0.0, true), dry_end(1.0, true));
        let (s0, s1) = (dry_end(0.0, false), dry_end(1.0, false));
        eprintln!("the dry end of a hard-pressed stroke: on linen paint follows the weave {l0:.2} -> {l1:.2} with tooth; on a smooth ground its unevenness {s0:.2} -> {s1:.2}");
        assert!(l1 > l0 + 0.2, "on linen a dry bristle catches the tooth: correlation with the weave {l0:.2} -> {l1:.2}");
        assert!(s1 < s0 * 1.3 + 0.05, "on a smooth ground it lays as evenly as before: {s0:.2} -> {s1:.2}");
    }
}
