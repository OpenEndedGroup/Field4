//! A brush whose hairs move: segmented bristles driven by a timed gesture of the handle.
//!
//! Upstream's brush (`bristle::drag_on`) places each bristle by rule: its root follows the path, its
//! tip trails behind by a first-order lag. This one simulates a modest number of **guide hairs** as
//! chains of segments, and lets the dense bristles upstream paints with follow them.
//!
//! # The hairs (what is simulated)
//!
//! - Each guide hair is a chain of `segs` segments (lengths in mm) from a root on the ferrule's mouth.
//!   The first segment is held by the ferrule along the handle's axis; the rest move under
//!   position-based dynamics (Verlet integration and constraint projection, in substeps of
//!   `SUBSTEP_S`): **stretch** (segments keep their length) and **bend** (each joint is pulled toward
//!   its rest angle, more strongly the stiffer the hair; a wet hair is softer).
//! - **Contact**: a node below the surface under it — the paper's relief, dry paint films and wet paint,
//!   as the canvas holds them — is put back on it. **Friction** is Coulomb's, as position-based
//!   dynamics treats it: a contact node sticks while its sideways slip in a substep is under
//!   `friction.0` times how far it was pushed out of the surface, and slides, held back by
//!   `friction.1` times that, beyond.
//! - **Retained deformation**: where a joint is bent further than `yield_rad` from its rest angle (by
//!   default where the fibre's surface is strained past keratin's yield, `YIELD_STRAIN`), the
//!   rest angle itself moves toward the bend (`plastic`, per second) — a hair pressed hard and dragged
//!   sideways stays splayed or hooked after the stroke — and recovers toward straight only slowly
//!   (`recover`, per second). The rest angles are part of the brush's state.
//! - **Clumping**: in a wet brush the tips of neighbouring hairs are drawn together by the liquid
//!   between them (`cohesion` times how full the brush is).
//! - The handle: its position, how far it is pressed down (pressure 1 lowers the ferrule by
//!   `PRESS_DEPTH` of the hairs' length below where the tips would just touch), its tilt from upright,
//!   which way it leans (azimuth) and its roll about its own axis, interpolated through the gesture's
//!   samples.
//!
//! # Paint (whose model it is)
//!
//! - **Oil**: the brush holds an upstream `Held` with its own dense bristles (paint per bristle,
//!   pickup, deposit, mixing, Kubelka–Munk — all upstream's `exchange`). Each dense bristle follows the
//!   guide hair nearest its root: it lies where that hair touches the canvas, offset as its root is,
//!   and presses with that hair's share of its length lying on the paper. Field computes where and how
//!   hard; upstream decides what the paint does.
//! - **Watercolour**: the brush holds a belly (`WaterBrush`); every hair node touching the sheet trades
//!   with it through `watercolor::exchange_cells`, over a disc a clump of hairs wide.
//!
//! # What is approximated
//!
//! Guide hairs stand for many: 16–64 are simulated where a real brush has thousands, and the dense
//! bristles copy their guide's contact rather than having their own dynamics. Hairs do not collide
//! with each other except through clumping. The liquid's own mechanics inside the brush (capillary
//! flow along hairs) is the belly's single reservoir, or upstream's per-bristle load.

use super::aqueous::Water;
use super::watercolor::{self as wc, StrokeReport, WaterBrush};
use crate::bristle::{exchange, feed, Held, Kind, Tool};
use crate::Canvas;

/// Substep of the hair simulation, s.
pub const SUBSTEP_S: f32 = 0.001;
/// Constraint iterations per substep.
const ITERATIONS: usize = 6;
/// Pressure 1 lowers the ferrule by this share of the hairs' length below just touching.
pub const PRESS_DEPTH: f32 = 0.6;
/// A dense bristle moves this many pixels before it trades paint again.
const EXCHANGE_PX: f32 = 0.75;

/// Keratin (hair and hog bristle) bends elastically up to about this strain, then yields.
const YIELD_STRAIN: f32 = 0.02;

/// A fibre's radius, mm, from the brush's stiffness: about 35 µm for soft sable or squirrel hair (0),
/// 105 µm for stiff hog bristle (1). Estimates.
fn fibre_radius_mm(stiffness: f32) -> f32 {
    0.035 + 0.07 * stiffness.max(0.0)
}

/// The bend of a joint between segments `seg_mm` long past which a fibre yields, radians: a fibre of
/// radius r bent through θ over a segment s is strained r θ / s at its surface.
fn yield_rad(seg_mm: f32, stiffness: f32) -> f32 {
    YIELD_STRAIN * seg_mm / fibre_radius_mm(stiffness)
}

/// One moment of the handle: where (canvas units), how far pressed (0 the tips just touch, 1 pressed
/// by `PRESS_DEPTH` of their length; below 0 lifted), tilt from upright, azimuth (which way the handle
/// leans, in the canvas: 0 toward +x) and roll (radians).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pose {
    pub x: f32,
    pub y: f32,
    pub t: f32,
    pub press: f32,
    pub tilt: f32,
    pub azimuth: f32,
    pub roll: f32,
}

/// A guide hair: its root on the ferrule's mouth (mm, across and through the brush), its length (mm),
/// its nodes now and a substep ago (mm, z up from the canvas's base), and each joint's rest bend (in
/// the brush's frame: across, through, along).
#[derive(Clone, Debug)]
pub struct Hair {
    pub root: [f32; 2],
    pub len: f32,
    pub p: Vec<[f32; 3]>,
    pub q: Vec<[f32; 3]>,
    pub rest: Vec<[f32; 3]>,
    /// How far each node was pushed out of the surface in the last substep, mm (0: not touching).
    pub depth: Vec<f32>,
}

/// The brush's frame for a pose: the axis from ferrule to tips, the wide axis, and their cross.
#[derive(Clone, Copy, Debug)]
struct Frame {
    d: [f32; 3],
    e1: [f32; 3],
    e2: [f32; 3],
}

fn add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[0] + b[0], a[1] + b[1], a[2] + b[2]] }
fn sub(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[0] - b[0], a[1] - b[1], a[2] - b[2]] }
fn mul(a: [f32; 3], k: f32) -> [f32; 3] { [a[0] * k, a[1] * k, a[2] * k] }
fn dot(a: [f32; 3], b: [f32; 3]) -> f32 { a[0] * b[0] + a[1] * b[1] + a[2] * b[2] }
fn len(a: [f32; 3]) -> f32 { dot(a, a).sqrt() }
fn norm(a: [f32; 3]) -> [f32; 3] { let l = len(a).max(1e-9); mul(a, 1.0 / l) }
fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]] }

impl Frame {
    fn of(p: &Pose) -> Frame {
        // the handle leans toward the azimuth; the hairs point from the ferrule away from it and down
        let (st, ct) = p.tilt.sin_cos();
        let (sa, ca) = p.azimuth.sin_cos();
        let d = [-st * ca, -st * sa, -ct];
        // the wide axis: the roll's direction in the canvas, made perpendicular to the axis
        let (sr, cr) = p.roll.sin_cos();
        let guess = [cr, sr, 0.0];
        let mut e1 = sub(guess, mul(d, dot(guess, d)));
        if len(e1) < 1e-4 {
            e1 = sub([0.0, 1.0, 0.0], mul(d, d[1]));
        }
        let e1 = norm(e1);
        let e2 = cross(d, e1);
        Frame { d, e1, e2 }
    }
    fn to_world(&self, v: [f32; 3]) -> [f32; 3] {
        add(add(mul(self.e1, v[0]), mul(self.e2, v[1])), mul(self.d, v[2]))
    }
    fn to_brush(&self, v: [f32; 3]) -> [f32; 3] {
        [dot(v, self.e1), dot(v, self.e2), dot(v, self.d)]
    }
}

/// A brush of segmented hairs.
#[derive(Clone, Debug)]
pub struct Bristles {
    pub kind: Kind,
    /// Width across the ferrule's mouth, mm.
    pub width_mm: f32,
    /// Hair length out of the ferrule, mm.
    pub length_mm: f32,
    /// 0 soft (sable) .. 1 stiff (hog).
    pub stiffness: f32,
    pub segs: usize,
    pub hairs: Vec<Hair>,
    /// Coulomb friction against the surface: static, kinetic.
    pub friction: (f32, f32),
    /// Bend beyond which a joint yields, radians.
    pub yield_rad: f32,
    /// How fast a yielded joint's rest angle follows its bend, per second.
    pub plastic: f32,
    /// How fast rest angles recover toward straight, per second.
    pub recover: f32,
    /// How strongly the liquid in a full brush draws neighbouring tips together, 0..1.
    pub cohesion: f32,
    /// Velocity kept per substep (1: none lost).
    pub damping: f32,
    /// The brush's handle as it was last (None: not yet held over the canvas).
    pub pose: Option<Pose>,
    /// Oil: upstream's dense bristles and their paint.
    pub held: Option<Held>,
    /// Watercolour: the belly.
    pub belly: Option<WaterBrush>,
    /// Dense bristle → guide hair, and its root's offset from that hair's (mm).
    pub assign: Vec<(usize, [f32; 2])>,
    /// Pairs of guide hairs near each other at the root (for clumping).
    pub pairs: Vec<(usize, usize, f32)>,
}

impl Bristles {
    /// A brush of `kind` `width_mm` wide with hairs `length_mm` long, `hairs` guide hairs of `segs`
    /// segments, as a tool `tool` describes it (its stiffness and shape). `seed` places the hairs.
    pub fn new(tool: &Tool, mm_per_unit: f32, hairs: usize, segs: usize, seed: u64) -> Bristles {
        let width_mm = tool.width * mm_per_unit;
        let length_mm = (tool.length * mm_per_unit).max(width_mm * 0.6).max(1.0);
        let mut rng = crate::Rng::new(seed ^ 0xB415_7135);
        let n = hairs.max(1);
        let golden = std::f32::consts::PI * (3.0 - 5f32.sqrt());
        let roots: Vec<[f32; 2]> = (0..n).map(|i| {
            let t = (i as f32 + 0.5) / n as f32;
            let (rx, ry) = match tool.kind {
                Kind::Round | Kind::Rigger | Kind::Blender => { let r = t.sqrt(); let a = i as f32 * golden; (r * a.cos(), r * a.sin()) }
                Kind::Filbert => { let r = t.sqrt(); let a = i as f32 * golden; (r * a.cos(), r * a.sin() * 0.3) }
                Kind::Flat => (rng.range(-1.0, 1.0), rng.range(-0.18, 0.18)),
                Kind::Fan => { let a = rng.range(-0.95, 0.95); (a.sin(), (a.cos() - 1.0) * 0.35) }
            };
            [rx * width_mm * 0.5, ry * width_mm * 0.5]
        }).collect();
        let hairs: Vec<Hair> = roots.iter().enumerate().map(|(i, &root)| {
            // a pointed round's outer hairs are shorter; every hair a little uneven
            let r2 = (root[0] * root[0] + root[1] * root[1]) / (0.25 * width_mm * width_mm).max(1e-6);
            let shape = match tool.kind { Kind::Round | Kind::Rigger => 1.0 - 0.25 * r2 * (0.4 + 0.6 * tool.point), _ => 1.0 };
            let l = length_mm * shape * (1.0 + 0.06 * tool.ragged * crate::rng::hash2(i as i64, 7, seed));
            let _ = i;
            Hair { root, len: l, p: vec![[0.0; 3]; segs + 1], q: vec![[0.0; 3]; segs + 1], rest: vec![[0.0; 3]; segs + 1], depth: vec![0.0; segs + 1] }
        }).collect();
        // neighbours at the root, for clumping
        let spacing = width_mm / (n as f32).sqrt().max(1.0);
        let mut pairs = Vec::new();
        for a in 0..n {
            for b in a + 1..n {
                let d = ((roots[a][0] - roots[b][0]).powi(2) + (roots[a][1] - roots[b][1]).powi(2)).sqrt();
                if d < 1.6 * spacing {
                    pairs.push((a, b, d));
                }
            }
        }
        let fan = matches!(tool.kind, Kind::Fan);
        Bristles {
            kind: tool.kind,
            width_mm,
            length_mm,
            stiffness: tool.stiffness,
            segs: segs.max(2),
            hairs,
            friction: (0.35, 0.25),
            yield_rad: yield_rad(length_mm / segs.max(2) as f32, tool.stiffness),
            // a set comes slowly: a few seconds of hard scrubbing splay a brush a little, hours bend
            // it for good (estimates)
            plastic: 0.3,
            recover: if fan { 0.01 } else { 0.02 },
            cohesion: if matches!(tool.kind, Kind::Round | Kind::Rigger) { 0.6 } else { 0.35 },
            damping: 0.985,
            pose: None,
            held: None,
            belly: None,
            assign: vec![],
            pairs,
        }
    }

    /// Give the brush upstream's dense bristles to carry oil paint, each following its nearest hair.
    pub fn with_held(mut self, held: Held) -> Bristles {
        let half = self.width_mm * 0.5;
        self.assign = held.bristles.iter().map(|b| {
            let r = [b.rx * half, b.ry * half];
            let g = (0..self.hairs.len()).min_by(|&a, &c| {
                let da = (self.hairs[a].root[0] - r[0]).powi(2) + (self.hairs[a].root[1] - r[1]).powi(2);
                let dc = (self.hairs[c].root[0] - r[0]).powi(2) + (self.hairs[c].root[1] - r[1]).powi(2);
                da.total_cmp(&dc)
            }).unwrap_or(0);
            (g, [r[0] - self.hairs[g].root[0], r[1] - self.hairs[g].root[1]])
        }).collect();
        self.held = Some(held);
        self
    }

    /// How full of liquid the brush is, 0..1 (its oil paint or its belly's water).
    pub fn fill(&self) -> f32 {
        if let Some(b) = &self.belly { return b.fill().min(1.0); }
        self.held.as_ref().map(|h| h.fullness().min(1.0)).unwrap_or(0.0)
    }

    /// Where the ferrule's mouth is for a pose, over a surface `z0` mm high: the belly of the brush (60
    /// % down the hairs) is over the pose's point.
    fn ferrule(&self, p: &Pose, f: &Frame, mm_per_unit: f32, z0: f32) -> [f32; 3] {
        let l = self.length_mm;
        let above = l * (-f.d[2]) * (1.0 - PRESS_DEPTH * p.press);
        [p.x * mm_per_unit - 0.6 * l * f.d[0], p.y * mm_per_unit - 0.6 * l * f.d[1], z0 + above]
    }

    /// Lay every hair straight (along its rest bend) from the ferrule at `pose`.
    fn place(&mut self, pose: &Pose, mm_per_unit: f32, z0: f32) {
        let f = Frame::of(pose);
        let fer = self.ferrule(pose, &f, mm_per_unit, z0);
        let segs = self.segs;
        for h in &mut self.hairs {
            let seg = h.len / segs as f32;
            let mut at = add(fer, f.to_world([h.root[0], h.root[1], 0.0]));
            let mut dir = f.d;
            h.p[0] = at;
            for k in 1..=segs {
                if k >= 2 {
                    dir = norm(add(dir, f.to_world(h.rest[k - 1])));
                }
                at = add(at, mul(dir, seg));
                h.p[k] = at;
            }
            h.q = h.p.clone();
            h.depth.iter_mut().for_each(|d| *d = 0.0);
        }
        self.pose = Some(*pose);
    }

    /// One substep of the hairs with the handle at `pose`, over `surface(x mm, y mm) → z mm`.
    pub fn step(&mut self, pose: &Pose, h: f32, mm_per_unit: f32, surface: &dyn Fn(f32, f32) -> f32) {
        let f = Frame::of(pose);
        let z0 = surface(pose.x * mm_per_unit, pose.y * mm_per_unit);
        if self.pose.is_none() {
            self.place(pose, mm_per_unit, z0);
        }
        let fer = self.ferrule(pose, &f, mm_per_unit, z0);
        let segs = self.segs;
        let wet = self.fill();
        // the bend constraint's correction per iteration. Coupled to the stretch and contact
        // constraints it diverges (the hairs fly apart into NaN) past about 1.4, and below about
        // -0.7 (measured: 1.33 holds and 1.42 fails painting; dry, -0.6 holds and -0.75 fails), so it
        // stops just short of those: a dry brush past stiffness 2.5 is as rigid as a hair gets, and
        // below -1.3 (joints driven away from their rest bend) as wild. Everything between is free.
        let kb = ((0.04 + 0.5 * self.stiffness) * (1.0 - 0.35 * wet)).clamp(-0.6, 1.3);
        let (mu_s, mu_k) = self.friction;
        // move: the held nodes with the ferrule, the free ones by their own momentum
        for hr in &mut self.hairs {
            let seg = hr.len / segs as f32;
            let root = add(fer, f.to_world([hr.root[0], hr.root[1], 0.0]));
            hr.q[0] = hr.p[0];
            hr.q[1] = hr.p[1];
            hr.p[0] = root;
            hr.p[1] = add(root, mul(f.d, seg));
            for k in 2..=segs {
                let v = mul(sub(hr.p[k], hr.q[k]), self.damping);
                hr.q[k] = hr.p[k];
                hr.p[k] = add(hr.p[k], v);
            }
            hr.depth.iter_mut().for_each(|d| *d = 0.0);
        }
        for _ in 0..ITERATIONS {
            for hr in &mut self.hairs {
                let seg = hr.len / segs as f32;
                // bend: each free node toward where its joint's rest angle puts it
                for k in 2..=segs {
                    let dir_prev = norm(sub(hr.p[k - 1], hr.p[k - 2]));
                    let target_dir = norm(add(dir_prev, f.to_world(hr.rest[k - 1])));
                    let target = add(hr.p[k - 1], mul(target_dir, seg));
                    hr.p[k] = add(hr.p[k], mul(sub(target, hr.p[k]), kb));
                }
                // stretch: each segment its length, the node nearer the ferrule fixed while it is held
                for k in 2..=segs {
                    let d = sub(hr.p[k], hr.p[k - 1]);
                    let l = len(d).max(1e-9);
                    let corr = mul(d, (l - seg) / l);
                    if k == 2 {
                        hr.p[k] = sub(hr.p[k], corr);
                    } else {
                        hr.p[k] = sub(hr.p[k], mul(corr, 0.5));
                        hr.p[k - 1] = add(hr.p[k - 1], mul(corr, 0.5));
                    }
                }
                // contact and friction
                for k in 2..=segs {
                    let zs = surface(hr.p[k][0], hr.p[k][1]);
                    let pen = zs - hr.p[k][2];
                    if pen > 0.0 {
                        hr.p[k][2] = zs;
                        hr.depth[k] = hr.depth[k].max(pen);
                        let slip = [hr.p[k][0] - hr.q[k][0], hr.p[k][1] - hr.q[k][1]];
                        let s = (slip[0] * slip[0] + slip[1] * slip[1]).sqrt();
                        if s < mu_s * pen {
                            hr.p[k][0] = hr.q[k][0];
                            hr.p[k][1] = hr.q[k][1];
                        } else if s > 0.0 {
                            let keep = 1.0 - (mu_k * pen / s).min(1.0);
                            hr.p[k][0] = hr.q[k][0] + slip[0] * keep;
                            hr.p[k][1] = hr.q[k][1] + slip[1] * keep;
                        }
                    }
                }
            }
            // clumping: a wet brush's neighbouring tips drawn together
            if wet > 0.05 && self.cohesion > 0.0 {
                let k = self.cohesion * wet * 0.2;
                for &(a, b, rest) in &self.pairs {
                    let (pa, pb) = (self.hairs[a].p[segs], self.hairs[b].p[segs]);
                    let d = sub(pb, pa);
                    let l = len(d);
                    let want = rest * (1.0 - self.cohesion * wet);
                    if l > want && l > 1e-6 {
                        let corr = mul(d, k * (l - want) / l * 0.5);
                        self.hairs[a].p[segs] = add(pa, corr);
                        self.hairs[b].p[segs] = sub(pb, corr);
                    }
                }
            }
        }
        // retained deformation: joints bent past yield take some of the bend as their own
        let take = 1.0 - (-self.plastic * h).exp();
        let relax = (-self.recover * h).exp();
        for hr in &mut self.hairs {
            for k in 2..=segs {
                let dir_prev = norm(sub(hr.p[k - 1], hr.p[k - 2]));
                let dir = norm(sub(hr.p[k], hr.p[k - 1]));
                let bend = f.to_brush(sub(dir, dir_prev));
                let r = &mut hr.rest[k - 1];
                let diff = [bend[0] - r[0], bend[1] - r[1], bend[2] - r[2]];
                let m = (diff[0] * diff[0] + diff[1] * diff[1] + diff[2] * diff[2]).sqrt();
                if m > self.yield_rad {
                    let a = take * (1.0 - self.yield_rad / m);
                    for c in 0..3 { r[c] += diff[c] * a; }
                }
                for c in 0..3 { r[c] *= relax; }
            }
        }
        self.pose = Some(*pose);
    }

    /// Each hair's contact with the surface in the last substep: where (mm) and how much of its length
    /// lies on the paper (0..1). None for a hair that doesn't touch.
    pub fn contacts(&self) -> Vec<Option<([f32; 2], f32)>> {
        let segs = self.segs;
        self.hairs.iter().map(|hr| {
            let (mut wx, mut wy, mut w, mut n) = (0.0, 0.0, 0.0, 0);
            for k in 2..=segs {
                if hr.depth[k] > 0.0 {
                    let wt = hr.depth[k] + 1e-4;
                    wx += hr.p[k][0] * wt;
                    wy += hr.p[k][1] * wt;
                    w += wt;
                    n += 1;
                }
            }
            if n == 0 { None } else { Some(([wx / w, wy / w], (n as f32 / (segs - 1) as f32).min(1.0))) }
        }).collect()
    }

    /// The hairs as polylines (canvas units for x and y, mm for z), for drawing the brush.
    pub fn polylines(&self, mm_per_unit: f32) -> Vec<Vec<[f32; 3]>> {
        self.hairs.iter().map(|h| h.p.iter().map(|p| [p[0] / mm_per_unit, p[1] / mm_per_unit, p[2]]).collect()).collect()
    }
}

/// Sample the gesture at time `t`.
pub fn pose_at(samples: &[Pose], t: f32) -> Pose {
    if t <= samples[0].t {
        return samples[0];
    }
    for w in samples.windows(2) {
        if t <= w[1].t {
            let span = (w[1].t - w[0].t).max(1e-9);
            let u = (t - w[0].t) / span;
            let l = |a: f32, b: f32| a + (b - a) * u;
            // angles the short way round: a heading from 179° to -179° turns 2°, not 358°
            let la = |a: f32, b: f32| {
                let tau = std::f32::consts::TAU;
                let d = (b - a + std::f32::consts::PI).rem_euclid(tau) - std::f32::consts::PI;
                a + d * u
            };
            return Pose { x: l(w[0].x, w[1].x), y: l(w[0].y, w[1].y), t, press: l(w[0].press, w[1].press), tilt: l(w[0].tilt, w[1].tilt),
                azimuth: la(w[0].azimuth, w[1].azimuth), roll: la(w[0].roll, w[1].roll) };
        }
    }
    *samples.last().unwrap()
}

/// What a stroke of the segmented brush did.
#[derive(Clone, Debug, Default)]
pub struct DynReport {
    /// Substeps simulated, and seconds of gesture.
    pub substeps: u64,
    pub seconds: f64,
    /// Hair-node contacts summed over substeps, and the largest share of a hair lying on the paper.
    pub contacts: u64,
    pub max_contact: f32,
    /// Watercolour: what the belly laid and took.
    pub water: StrokeReport,
}

/// The surface the hairs feel at (x mm, y mm): the canvas's height with its wet paint, mm.
fn surface_of(c: &Canvas) -> impl Fn(f32, f32) -> f32 + '_ {
    let f = c.f;
    let mmu = c.mm_per_unit;
    let s = f.scale;
    move |x: f32, y: f32| -> f32 {
        let (px, py) = ((x / mmu * s) as isize, (y / mmu * s) as isize);
        let (px, py) = (px.clamp(f.x0 as isize, (f.x0 + f.w) as isize - 1) as usize - f.x0, py.clamp(f.y0 as isize, (f.y0 + f.h) as isize - 1) as usize - f.y0);
        let i = py * f.w + px;
        (c.height[i] + c.wet.vol[i] * crate::surface::COAT_UM) * 1e-3
    }
}

/// Drag the brush through `samples` on canvas `c`, painting in oil with its dense bristles, or (with
/// `water`) in watercolour from its belly. `contact` gets seconds of hair contact per pixel added.
pub fn stroke(c: &mut Canvas, water: Option<&mut Water>, br: &mut Bristles, samples: &[Pose], contact: Option<&mut Vec<f32>>) -> DynReport {
    let mut rep = DynReport::default();
    if samples.is_empty() {
        return rep;
    }
    let mmu = c.mm_per_unit;
    let t0 = samples[0].t;
    let t1 = samples.last().unwrap().t;
    let n = (((t1 - t0) / SUBSTEP_S).ceil() as usize).max(1);
    let h = if n > 0 { (t1 - t0) / n as f32 } else { SUBSTEP_S };
    let h = if h <= 0.0 { SUBSTEP_S } else { h };
    // a fresh stroke: the brush comes down from where the gesture starts
    br.pose = None;
    let id = c.next_stroke_ids(1);
    let pts: Vec<(f32, f32)> = samples.iter().map(|p| (p.x, p.y)).collect();
    let tool_for_tally = br.held.as_ref().map(|hd| hd.tool.clone()).unwrap_or_else(|| Tool::round_sable((br.width_mm / mmu).max(0.1)));
    c.tally.stroke(&tool_for_tally, &pts, mmu);
    let f = c.f;
    let s = f.scale;
    let mut contact = contact;
    let mut water = water;
    let mut scratch = Vec::new();
    let mut bounds: crate::bristle::Bounds = None;
    let mut last_px: Vec<Option<(f32, f32)>> = br.held.as_ref().map(|hd| vec![None; hd.bristles.len()]).unwrap_or_default();
    let mut water_ids: Vec<usize> = Vec::new();
    let mut water_tau = 0.0f32;
    let mut water_cells: Vec<(usize, f32, f32)> = Vec::new();
    let mut since_flow = 0.0f32;
    let mut last_hair_px: Vec<Option<(f32, f32)>> = vec![None; br.hairs.len()];
    for k in 0..=n {
        let t = t0 + k as f32 * h;
        let pose = pose_at(samples, t);
        {
            let surf = surface_of(c);
            br.step(&pose, h, mmu, &surf);
        }
        rep.substeps += 1;
        let cts = br.contacts();
        for ct in cts.iter().flatten() {
            rep.contacts += 1;
            rep.max_contact = rep.max_contact.max(ct.1);
        }
        if let Some(acc) = contact.as_deref_mut() {
            for hr in &br.hairs {
                for kk in 2..=br.segs {
                    if hr.depth[kk] > 0.0 {
                        let (px, py) = ((hr.p[kk][0] / mmu * s) as isize - f.x0 as isize, (hr.p[kk][1] / mmu * s) as isize - f.y0 as isize);
                        if px >= 0 && py >= 0 && (px as usize) < f.w && (py as usize) < f.h {
                            acc[py as usize * f.w + px as usize] += h;
                        }
                    }
                }
            }
        }
        // oil: each dense bristle trades paint where its hair touches
        if let Some(held) = br.held.as_mut() {
            let tool = held.tool.clone();
            let full = held.full();
            let rb = (tool.hair_radius() * s).max(if tool.point > 0.0 { 0.02 } else { 0.55 });
            let fr = Frame::of(&pose);
            let lim = (0usize, 0usize, f.full_w, f.full_h);
            let sf = c.surf();
            for (bi, b) in held.bristles.iter_mut().enumerate() {
                let (g, off) = br.assign[bi];
                match cts[g] {
                    None => {
                        b.prev[0] = None;
                        last_px[bi] = None;
                    }
                    Some((xy, share)) => {
                        let o = fr.to_world([off[0], off[1], 0.0]);
                        let cur = ((xy[0] + o[0]) / mmu * s, (xy[1] + o[1]) / mmu * s);
                        let reach = (share * 1.25).min(1.3);
                        match last_px[bi] {
                            Some(prev) if (cur.0 - prev.0).abs() + (cur.1 - prev.1).abs() < EXCHANGE_PX => {}
                            prev => {
                                let from = prev.unwrap_or(cur);
                                // SAFETY: the canvas is exclusively ours for the stroke, one brush
                                unsafe { exchange(sf, b, &tool, from, cur, rb, reach, full, None, 1.0, None, id, &mut scratch, &mut bounds, lim) };
                                last_px[bi] = Some(cur);
                                b.prev[0] = Some(cur);
                            }
                        }
                    }
                }
            }
            if tool.point > 0.0 {
                feed(&mut held.bristles, tool.point * 0.02);
            }
        }
        // watercolour: every touching hair trades with the sheet over a clump's disc
        if let (Some(wt), Some(belly)) = (water.as_deref_mut(), br.belly.as_mut()) {
            let r_px = (0.5 * br.width_mm / (br.hairs.len() as f32).sqrt().max(1.0) / wt.dx_mm).max(0.7);
            for (gi, hr) in br.hairs.iter().enumerate() {
                let fill = belly.fill();
                for kk in 2..=br.segs {
                    if hr.depth[kk] <= 0.0 {
                        continue;
                    }
                    let (cx, cy) = (hr.p[kk][0] / wt.dx_mm, hr.p[kk][1] / wt.dx_mm);
                    // the clump rests on the highest tooth under it, bridging the hollows between;
                    // it and the liquid on it reach down into them only as far as its press and the
                    // belly's fullness let them
                    let ri = r_px.ceil() as isize;
                    let mut top = f32::MIN;
                    for j in -ri..=ri {
                        for i in -ri..=ri {
                            let (x, y) = (cx as isize + i, cy as isize + j);
                            if x < 0 || y < 0 || x >= wt.w as isize || y >= wt.h as isize { continue; }
                            if ((x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2)).sqrt() <= r_px {
                                top = top.max(wt.relief[y as usize * wt.w + x as usize]);
                            }
                        }
                    }
                    let reach = wc::reach_um(pose.press.max(0.0), fill);
                    let moved = last_hair_px[gi].map(|(lx, ly)| ((cx - lx).powi(2) + (cy - ly).powi(2)).sqrt()).unwrap_or(0.0);
                    let pass = (moved / (2.0 * r_px)).min(1.0);
                    for j in -ri..=ri {
                        for i in -ri..=ri {
                            let (x, y) = (cx as isize + i, cy as isize + j);
                            if x < 0 || y < 0 || x >= wt.w as isize || y >= wt.h as isize { continue; }
                            let d = ((x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2)).sqrt();
                            if d > r_px { continue; }
                            let idx = y as usize * wt.w + x as usize;
                            let w8 = (1.0 - crate::smoothstep(0.6 * r_px, r_px, d)) * wc::touch(wt, idx, top, reach);
                            if w8 > 0.0 {
                                water_cells.push((idx, w8 / (br.segs - 1) as f32, pass));
                            }
                        }
                    }
                }
                let lowest = (2..=br.segs).rev().find(|&kk| hr.depth[kk] > 0.0);
                last_hair_px[gi] = lowest.map(|kk| (hr.p[kk][0] / wt.dx_mm, hr.p[kk][1] / wt.dx_mm));
            }
            water_tau += h;
            if water_tau >= 0.005 || k == n {
                // merge the touches per pixel (most contact, summed passage) and trade
                water_cells.sort_by_key(|c| c.0);
                let mut merged: Vec<(usize, f32, f32)> = Vec::with_capacity(water_cells.len());
                for cell in water_cells.drain(..) {
                    match merged.last_mut() {
                        Some(m) if m.0 == cell.0 => { m.1 = (m.1 + cell.1).min(1.0); m.2 = (m.2 + cell.2).min(1.0); }
                        _ => merged.push(cell),
                    }
                }
                let moving = merged.iter().any(|c| c.2 > 0.0);
                wc::exchange_cells(wt, belly, &mut water_ids, &merged, water_tau, moving, &mut rep.water);
                since_flow += water_tau;
                water_tau = 0.0;
                if since_flow >= 0.02 {
                    wt.advance(since_flow as f64);
                    since_flow = 0.0;
                }
            }
        }
    }
    if let (Some(wt), true) = (water, since_flow > 0.0) {
        wt.advance(since_flow as f64);
    }
    if let Some((x0, y0, x1, y1)) = bounds {
        c.wet.touch(x0 - f.x0, y0 - f.y0, x1 - f.x0, y1 - f.y0);
    }
    rep.seconds = (t1 - t0) as f64;
    rep
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Paint;

    fn line(x0: f32, x1: f32, y: f32, secs: f32, press: f32, tilt: f32, az: f32) -> Vec<Pose> {
        vec![Pose { x: x0, y, t: 0.0, press, tilt, azimuth: az, roll: std::f32::consts::FRAC_PI_2 },
             Pose { x: x1, y, t: secs, press, tilt, azimuth: az, roll: std::f32::consts::FRAC_PI_2 }]
    }

    #[test]
    fn hairs_bend_back_from_a_moving_handle_and_rest_on_the_surface() {
        let c = Canvas::new(200, 1.0, [1.0; 3]);
        let mut b = Bristles::new(&Tool::round_sable(40.0), c.mm_per_unit(), 24, 5, 3);
        let surf = |_: f32, _: f32| 0.0f32;
        let samples = line(200.0, 800.0, 500.0, 1.0, 0.8, 0.5, 0.0);
        for k in 0..=1000 {
            b.step(&pose_at(&samples, k as f32 * 0.001), 0.001, c.mm_per_unit(), &surf);
        }
        let tips: Vec<[f32; 3]> = b.hairs.iter().map(|h| h.p[5]).collect();
        assert!(tips.iter().all(|t| t[2] >= -1e-4), "no hair under the paper");
        let ferrule_x = 800.0 * c.mm_per_unit();
        let behind = tips.iter().filter(|t| t[0] < ferrule_x).count();
        assert!(behind > tips.len() / 2, "the hairs trail behind the handle: {behind} of {}", tips.len());
        assert!(b.contacts().iter().filter(|c| c.is_some()).count() > tips.len() / 2, "pressed down, most hairs touch");
    }

    #[test]
    fn a_hard_pressed_brush_keeps_some_of_its_bend() {
        let c = Canvas::new(200, 1.0, [1.0; 3]);
        let mut b = Bristles::new(&Tool::hog_flat(40.0), c.mm_per_unit(), 24, 5, 3);
        let surf = |_: f32, _: f32| 0.0f32;
        let samples = line(200.0, 800.0, 500.0, 1.0, 1.5, 0.3, 0.0);
        for k in 0..=1000 {
            b.step(&pose_at(&samples, k as f32 * 0.001), 0.001, c.mm_per_unit(), &surf);
        }
        let set: f32 = b.hairs.iter().flat_map(|h| h.rest.iter()).map(|r| (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).sqrt()).sum();
        assert!(set > 0.1, "rest angles moved: {set}");
        // lifted clear, the hairs keep part of it rather than springing straight
        let lifted = line(800.0, 800.0, 500.0, 0.5, -1.0, 0.0, 0.0);
        for k in 0..=500 {
            b.step(&pose_at(&lifted, k as f32 * 0.001), 0.001, c.mm_per_unit(), &surf);
        }
        let after: f32 = b.hairs.iter().flat_map(|h| h.rest.iter()).map(|r| (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).sqrt()).sum();
        assert!(after > 0.8 * set, "retained: {after} of {set}");
    }

    /// Timing, not a check: `cargo test --release --lib bench_a_watercolour -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn bench_a_watercolour_stroke() {
        use super::super::aqueous::{AqPigment, Paper};
        let mut c = Canvas::new(900, 1.25, [0.9; 3]).with_size_mm(300.0);
        let f = c.frame();
        let mut w = Water::new(Paper::rough(), f.w, f.h, c.px_mm(), 3);
        wc::emboss(&mut c, &w.relief);
        let mut b = Bristles::new(&Tool::round_sable(65.0), c.mm_per_unit(), 32, 5, 3);
        let mut belly = WaterBrush::new(65.0, c.mm_per_unit());
        belly.load(&[(AqPigment::named("burnt sienna").unwrap(), 0.6)], 0.95, 1.0);
        b.belly = Some(belly);
        let samples: Vec<Pose> = (0..=30).map(|i| Pose { x: 100.0 + 25.0 * i as f32, y: 450.0, t: i as f32 * 0.04, press: 0.6, tilt: 0.4, azimuth: 0.0, roll: 1.57 }).collect();
        let t = std::time::Instant::now();
        let rep = stroke(&mut c, Some(&mut w), &mut b, &samples, None);
        eprintln!("1.2 s watercolour gesture on 900 px: {:.2} s wall, laid {:.1} mm³", t.elapsed().as_secs_f64(), rep.water.water_laid);
    }

    #[test]
    fn hairs_stay_finite_at_any_stiffness() {
        let c = Canvas::new(200, 1.0, [1.0; 3]);
        let surf = |_: f32, _: f32| 0.0f32;
        for st in [-1000.0f32, -10.0, -1.0, 0.0, 1.0, 2.6, 10.0, 1000.0] {
            let mut tool = Tool::hog_flat(40.0);
            tool.stiffness = st;
            let mut b = Bristles::new(&tool, c.mm_per_unit(), 24, 5, 3);
            let samples = line(200.0, 800.0, 500.0, 1.0, 1.0, 0.5, 0.0);
            for k in 0..=1000 {
                b.step(&pose_at(&samples, k as f32 * 0.001), 0.001, c.mm_per_unit(), &surf);
            }
            assert!(b.hairs.iter().all(|h| h.p.iter().flatten().all(|v| v.is_finite())), "stiffness {st}: the hairs flew apart");
        }
    }

    #[test]
    fn a_dynamic_oil_stroke_lays_paint_along_its_path() {
        let mut c = Canvas::new(200, 1.0, [1.0; 3]);
        let mut held = Held::new(Tool::filbert(40.0), 5).with_engine(c.engine());
        held.load(Paint::body([0.6, 0.1, 0.05]), 1.0);
        let mut b = Bristles::new(&held.tool, c.mm_per_unit(), 24, 5, 3).with_held(held);
        let mut acc = vec![0.0f32; 200 * 200];
        let rep = stroke(&mut c, None, &mut b, &line(150.0, 850.0, 500.0, 0.8, 0.7, 0.4, 0.0), Some(&mut acc));
        assert!(rep.contacts > 0);
        assert!(c.wet_total() > 0.0, "paint was laid");
        let row = 100 * 200;
        assert!(acc[row + 100] > 0.0 && acc[row + 5] == 0.0, "contact along the path, not off it");
        assert!(b.held.as_ref().unwrap().fullness() < 1.0);
    }
}

// ---- saving ---------------------------------------------------------------------------------------

/// What a brush paints with.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Medium {
    Oil,
    Water,
}

impl Bristles {
    /// What the brush was last loaded with: watercolour if its belly holds any water, else oil.
    pub fn medium(&self) -> Medium {
        if self.belly.as_ref().is_some_and(|b| b.water_mm3 > 0.0 || !b.pigment.is_empty()) { Medium::Water } else { Medium::Oil }
    }

    /// The whole brush, exactly (`FPDB1`): construction, every hair's nodes and rest bends, the handle,
    /// and its oil bristles' and belly's contents.
    pub fn write_state(&self) -> Vec<u8> {
        let mut w: Vec<u8> = b"FPDB1\0\0\0".to_vec();
        let f = |w: &mut Vec<u8>, v: f32| w.extend_from_slice(&v.to_le_bytes());
        let u = |w: &mut Vec<u8>, v: u64| w.extend_from_slice(&v.to_le_bytes());
        let kind = match self.kind { Kind::Round => 0, Kind::Flat => 1, Kind::Filbert => 2, Kind::Fan => 3, Kind::Rigger => 4, Kind::Blender => 5 };
        u(&mut w, kind);
        for v in [self.width_mm, self.length_mm, self.stiffness, self.friction.0, self.friction.1, self.yield_rad, self.plastic, self.recover, self.cohesion, self.damping] { f(&mut w, v); }
        u(&mut w, self.segs as u64);
        u(&mut w, self.hairs.len() as u64);
        for h in &self.hairs {
            f(&mut w, h.root[0]); f(&mut w, h.root[1]); f(&mut w, h.len);
            for arr in [&h.p, &h.q, &h.rest] { for p in arr.iter() { for c in p { f(&mut w, *c); } } }
            for d in &h.depth { f(&mut w, *d); }
        }
        match &self.pose {
            None => u(&mut w, 0),
            Some(p) => { u(&mut w, 1); for v in [p.x, p.y, p.t, p.press, p.tilt, p.azimuth, p.roll] { f(&mut w, v); } }
        }
        u(&mut w, self.assign.len() as u64);
        for (g, o) in &self.assign { u(&mut w, *g as u64); f(&mut w, o[0]); f(&mut w, o[1]); }
        u(&mut w, self.pairs.len() as u64);
        for (a, b, d) in &self.pairs { u(&mut w, *a as u64); u(&mut w, *b as u64); f(&mut w, *d); }
        let held = self.held.as_ref().map(|h| h.write_state()).unwrap_or_default();
        u(&mut w, held.len() as u64);
        w.extend_from_slice(&held);
        let belly = self.belly.as_ref().map(super::watercolor::write_wbrush).unwrap_or_default();
        u(&mut w, belly.len() as u64);
        w.extend_from_slice(&belly);
        w
    }

    /// A brush written by `write_state`.
    pub fn read_state(b: &[u8]) -> Result<Bristles, String> {
        let mut at = 0usize;
        let mut take = |n: usize| -> Result<&[u8], String> { let s = b.get(at..at + n).ok_or("truncated brush state")?; at += n; Ok(s) };
        if take(8)? != b"FPDB1\0\0\0" {
            return Err("not a segmented brush's state".into());
        }
        macro_rules! f { () => { f32::from_le_bytes(take(4)?.try_into().unwrap()) } }
        macro_rules! u { () => { u64::from_le_bytes(take(8)?.try_into().unwrap()) } }
        let kind = match u!() { 0 => Kind::Round, 1 => Kind::Flat, 2 => Kind::Filbert, 3 => Kind::Fan, 4 => Kind::Rigger, 5 => Kind::Blender, k => return Err(format!("unknown brush kind {k}")) };
        let (width_mm, length_mm, stiffness) = (f!(), f!(), f!());
        let friction = (f!(), f!());
        let (yield_rad, plastic, recover, cohesion, damping) = (f!(), f!(), f!(), f!(), f!());
        let segs = u!() as usize;
        let nh = u!() as usize;
        if segs > 64 || nh > 4096 {
            return Err(format!("a brush state of {nh} hairs of {segs} segments"));
        }
        let mut hairs = Vec::with_capacity(nh);
        for _ in 0..nh {
            let root = [f!(), f!()];
            let len = f!();
            let mut arrs: Vec<Vec<[f32; 3]>> = Vec::new();
            for _ in 0..3 {
                let mut v = Vec::with_capacity(segs + 1);
                for _ in 0..=segs { v.push([f!(), f!(), f!()]); }
                arrs.push(v);
            }
            let mut depth = Vec::with_capacity(segs + 1);
            for _ in 0..=segs { depth.push(f!()); }
            let rest = arrs.pop().unwrap();
            let q = arrs.pop().unwrap();
            let p = arrs.pop().unwrap();
            hairs.push(Hair { root, len, p, q, rest, depth });
        }
        let pose = if u!() == 1 { Some(Pose { x: f!(), y: f!(), t: f!(), press: f!(), tilt: f!(), azimuth: f!(), roll: f!() }) } else { None };
        let na = u!() as usize;
        let mut assign = Vec::with_capacity(na.min(1 << 20));
        for _ in 0..na { let g = u!() as usize; assign.push((g, [f!(), f!()])); }
        let np = u!() as usize;
        let mut pairs = Vec::with_capacity(np.min(1 << 20));
        for _ in 0..np { let a = u!() as usize; let b2 = u!() as usize; pairs.push((a, b2, f!())); }
        let hl = u!() as usize;
        let held = if hl > 0 { Some(Held::read_state(take(hl)?)?) } else { None };
        let bl = u!() as usize;
        let belly = if bl > 0 { Some(super::watercolor::read_wbrush(take(bl)?)?) } else { None };
        if assign.iter().any(|a| a.0 >= nh) || pairs.iter().any(|p| p.0 >= nh || p.1 >= nh) {
            return Err("a brush state whose bristles name hairs it doesn't have".into());
        }
        Ok(Bristles { kind, width_mm, length_mm, stiffness, segs, hairs, friction, yield_rad, plastic, recover, cohesion, damping, pose, held, belly, assign, pairs })
    }
}
