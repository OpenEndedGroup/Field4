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
//! - **Splay**: a hair pressed into the surface is pushed outward from the brush's middle, by the
//!   tool's `splay` times how far it is pressed in, against the same grip as any slip: where the
//!   pressure and the splay overcome the friction the hairs fan out, the outer ones furthest.
//! - **Clumping**, two models blended by `clumping`. At 0 (the default) the tips of hairs that are
//!   neighbours at the root are drawn together by the liquid between them (`cohesion` times how full
//!   the brush is). At 1, in its place, liquid bridges between hairs: wherever two hairs' nodes at
//!   the same joint come
//!   within about the hairs' spacing at the root, and both hairs are wet, a bridge forms between them
//!   and pulls them together (more toward the tips, more the wetter and thinner the paint and the
//!   larger `cohesion`). A bridge holds until it is stretched past its rupture length, which is
//!   longer the more liquid it holds, and then snaps. Neighbours are found as the hairs move, not
//!   fixed at the root, so hairs pushed apart by splay tear the brush into clumps, and hairs pushed
//!   together from across the brush join. Negative `cohesion` pushes nearby hairs apart. Between 0
//!   and 1 the two pull together, weighted; past 1 the bridges pull harder and reach further; below 0
//!   the tip pull is doubled at -1 and the bridges push instead of pull.
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
//! with each other except through their bridges. The liquid's own mechanics inside the brush (capillary
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

/// A hair holds liquid enough to bridge to another at this share of a full load.
const BRIDGE_WET: f32 = 0.02;
/// Liquid bridges a node holds at most (its nearest).
const BRIDGES_PER_NODE: u8 = 6;
/// New bridges are looked for every this many substeps (broken ones every substep).
const BRIDGE_EVERY: u64 = 4;
/// The behaviours a stroke can vary along itself (`Bristles::vary`).
pub const VARY_NAMES: [&str; 5] = ["splay", "splay scatter", "splay fan", "cohesion", "clumping"];

impl Bristles {
    fn knob(&mut self, which: usize) -> &mut f32 {
        match which {
            0 => &mut self.splay,
            1 => &mut self.splay_scatter,
            2 => &mut self.splay_fan,
            3 => &mut self.cohesion,
            _ => &mut self.clumping,
        }
    }
}

/// Hairs this many root spacings apart buckle about alike in the splay's scatter.
const SCATTER_SPACINGS: f32 = 4.0;
/// The splay's scatter drifts over about this long, s.
const SCATTER_S: f32 = 0.4;
/// Where a hair touches the paper, a bridge pulls it at this share of its strength in the air.
const CONTACT_PULL: f32 = 0.15;
/// Passes of the bridges and the hairs' shape a wet brush settles through in the air at a stroke's
/// start.
const SETTLE_PASSES: usize = 120;
/// The dense bristles are drawn in to their hair's clump over about this long, s.
const SQUEEZE_S: f32 = 0.03;
/// Bridges form at most this many root spacings apart.
const BRIDGE_FORM_MAX: f32 = 6.0;
/// Bridged hairs are drawn no closer than this share of the root spacing.
const BRIDGE_CLOSEST: f32 = 0.08;

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
    /// How strongly the liquid between wet hairs draws them together (1 a full brush's tips drawn to a
    /// point; more, harder and tighter; negative, nearby hairs pushed apart).
    pub cohesion: f32,
    /// Which clumping: 0 the neighbouring tips drawn together (as brushes were made before liquid
    /// bridges), 1 liquid bridges in its place, between a blend of the two (each pulling by its
    /// share), past 1 the bridges stronger and reaching further, below 0 the reverse (the tip pull
    /// stronger, the bridges pushing).
    pub clumping: f32,
    /// How far a hair pressed into the surface is pushed out from the brush's middle, per mm it
    /// is pressed in (the tool's `splay`): against the surface's grip, so a brush fans out where
    /// it is pressed harder than its hairs hold. 0 none.
    pub splay: f32,
    /// How irregularly the hairs splay: 0 each straight out from the brush's middle, more each turned
    /// off that by its own angle (up to a half turn at 1) and pushed by its own amount (from none to
    /// twice), both drifting slowly through a stroke, the way pressed hairs buckle each their own way.
    pub splay_scatter: f32,
    /// How the splay grows across the brush: 0 every pressed hair pushed out alike, 1 each in
    /// proportion to how far out its root is (the same on average), so that the brush fans open and
    /// every neighbour moves away from the next, stretching the liquid between them.
    pub splay_fan: f32,
    /// The hairs' mean distance from the brush's middle at the root, mm (`spaced`).
    pub root_mean: f32,
    /// Values the next stroke takes at each of its samples, eased between them through it: which
    /// behaviour (`VARY_NAMES`) and its value at every sample. Taken by `stroke`, which puts the
    /// brush's own values back after.
    pub vary: Vec<(usize, Vec<f32>)>,
    /// The stroke's noise identity, for the splay's scatter (`stroke` sets it).
    pub scatter_seed: u64,
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
    /// Pairs of guide hairs near each other at the root (the clumping before liquid bridges; kept so
    /// that saved brushes read back).
    pub pairs: Vec<(usize, usize, f32)>,
    /// The liquid bridges standing now: two hairs and the joint they join at. Made and broken as the
    /// hairs move (`bridge`); a stroke starts with none.
    pub bridges: Vec<(u32, u32, u8)>,
    /// The hairs' spacing at the root, mm (`root_spacing`): the scale the bridges work at.
    pub spacing: f32,
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
            clumping: 0.0,
            splay: tool.splay,
            splay_scatter: 0.0,
            splay_fan: 0.0,
            root_mean: 0.0,
            vary: vec![],
            scatter_seed: 0,
            damping: 0.985,
            pose: None,
            held: None,
            belly: None,
            assign: vec![],
            pairs,
            bridges: vec![],
            spacing: 0.0,
        }.spaced()
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

    /// How wet each guide hair is, 0..1 (more past a full load), and how thin the paint is, 0..1 (the
    /// solvent's share of the liquid): oil from the dense bristles that follow each hair, watercolour
    /// the belly's fill for every hair (and thin).
    fn wetness(&self) -> (Vec<f32>, f32) {
        let n = self.hairs.len();
        if let Some(b) = &self.belly {
            return (vec![b.fill().max(0.0); n], 1.0);
        }
        let Some(held) = &self.held else { return (vec![0.0; n], 0.0) };
        let full = held.full().max(1e-9);
        let (mut liq, mut count) = (vec![0.0f32; n], vec![0u32; n]);
        let (mut paint, mut solvent) = (0.0f32, 0.0f32);
        for (b, &(g, _)) in held.bristles.iter().zip(&self.assign) {
            if g < n {
                liq[g] += b.vol.max(0.0) + b.solvent.max(0.0);
                count[g] += 1;
            }
            paint += b.vol.max(0.0);
            solvent += b.solvent.max(0.0);
        }
        let wet = liq.iter().zip(&count).map(|(l, &c)| if c > 0 { l / (full * c as f32) } else { held.fullness().max(0.0) }).collect();
        (wet, solvent / (paint + solvent).max(1e-9))
    }

    /// The brush with its `spacing` measured.
    fn spaced(mut self) -> Bristles {
        self.spacing = self.root_spacing();
        let n = self.hairs.len().max(1) as f32;
        self.root_mean = self.hairs.iter().map(|h| (h.root[0] * h.root[0] + h.root[1] * h.root[1]).sqrt()).sum::<f32>() / n;
        self
    }

    /// The hairs' spacing at the root, mm: the median distance from each to its nearest neighbour.
    fn root_spacing(&self) -> f32 {
        let n = self.hairs.len();
        if n < 2 {
            return self.width_mm.max(0.1);
        }
        let mut nn: Vec<f32> = (0..n).map(|a| {
            let ra = self.hairs[a].root;
            (0..n).filter(|&b| b != a).map(|b| {
                let rb = self.hairs[b].root;
                ((ra[0] - rb[0]).powi(2) + (ra[1] - rb[1]).powi(2)).sqrt()
            }).fold(f32::INFINITY, f32::min)
        }).collect();
        nn.sort_by(f32::total_cmp);
        nn[n / 2].max(self.width_mm * 1e-3).max(1e-3)
    }

    /// Break the liquid bridges stretched past their rupture length (a few times their forming
    /// distance, more the more liquid they hold), and (`form`) make new ones between wet hairs whose
    /// nodes at a joint have come within their forming distance: at most `BRIDGES_PER_NODE` a node,
    /// nearest first.
    fn bridge(&mut self, wet: &[f32], thin: f32, s0: f32, form: bool) {
        let segs = self.segs;
        // how near two hairs' nodes at a joint come before a bridge forms between them, for a bridge
        // holding `v` (the drier hair's wetness): the root spacing dry or without cohesion, and
        // further the wetter and the more cohesion, so that clumps go on drawing in their
        // neighbours (pairs join pairs) until they lie further apart than this; at most
        // `BRIDGE_FORM_MAX` spacings, where finding who is near would grow costly
        let cohesion = (self.cohesion * self.clumping).max(0.0);
        let formed = move |v: f32| s0 * (1.0 + 1.5 * cohesion * v.clamp(0.0, 1.5)).min(BRIDGE_FORM_MAX);
        let hairs = &self.hairs;
        let reach = |v: f32| formed(v) * (1.3 + 1.5 * v.max(0.0).cbrt()) * (1.0 + 0.5 * thin);
        let gone = |a: usize, b: usize, k: usize| {
            let v = wet[a].min(wet[b]);
            v < BRIDGE_WET || len(sub(hairs[a].p[k], hairs[b].p[k])) > reach(v)
        };
        self.bridges.retain(|&(a, b, k)| !gone(a as usize, b as usize, k as usize));
        if !form {
            return;
        }
        let n = hairs.len();
        let have: std::collections::HashSet<(u32, u32, u8)> = self.bridges.iter().copied().collect();
        let mut held = vec![0u8; n * (segs + 1)];
        for &(a, b, k) in &self.bridges {
            held[a as usize * (segs + 1) + k as usize] += 1;
            held[b as usize * (segs + 1) + k as usize] += 1;
        }
        let wmax = wet.iter().cloned().fold(0.0f32, f32::max);
        let cell = formed(wmax).max(1e-4);
        let key = |p: [f32; 3]| ((p[0] / cell).floor() as i32, (p[1] / cell).floor() as i32, (p[2] / cell).floor() as i32);
        let mut grid: std::collections::HashMap<(i32, i32, i32), Vec<u32>> = std::collections::HashMap::new();
        let mut near: Vec<(f32, u32, u32)> = Vec::new();
        for k in 2..=segs {
            grid.clear();
            for a in 0..n {
                if wet[a] >= BRIDGE_WET {
                    grid.entry(key(hairs[a].p[k])).or_default().push(a as u32);
                }
            }
            near.clear();
            for a in 0..n {
                if wet[a] < BRIDGE_WET {
                    continue;
                }
                let pa = hairs[a].p[k];
                let (cx, cy, cz) = key(pa);
                for dx in -1..=1 {
                    for dy in -1..=1 {
                        for dz in -1..=1 {
                            let Some(cs) = grid.get(&(cx + dx, cy + dy, cz + dz)) else { continue };
                            for &b in cs {
                                if b as usize > a {
                                    let d = len(sub(hairs[b as usize].p[k], pa));
                                    if d < formed(wet[a].min(wet[b as usize])) {
                                        near.push((d, a as u32, b));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            near.sort_by(|x, y| x.0.total_cmp(&y.0));
            for &(_, a, b) in &near {
                let (ia, ib) = (a as usize * (segs + 1) + k, b as usize * (segs + 1) + k);
                if held[ia] < BRIDGES_PER_NODE && held[ib] < BRIDGES_PER_NODE && !have.contains(&(a, b, k as u8)) {
                    self.bridges.push((a, b, k as u8));
                    held[ia] += 1;
                    held[ib] += 1;
                }
            }
        }
    }

    /// One pass of the liquid bridges: each draws its two hairs together toward lying close (a
    /// wetter bridge, thinner paint and more cohesion closer and harder, toward the tips most);
    /// negative cohesion pushes them apart to the root spacing. Where either hair touches the paper
    /// the pull is along the paper and `on_paper` of its strength: the wet paint drags at hairs
    /// sliding over it. The pull per pass stops short of 0.45, past which the corrections overshoot
    /// and the hairs ring.
    fn pull_bridges(&mut self, hair_wet: &[f32], thin: f32, s0: f32, on_paper: f32) {
        // the bridges pull by cohesion times their share of the clumping (pushing where negative)
        let c = self.cohesion * self.clumping;
        let segs = self.segs;
        for &(a, b, k) in &self.bridges {
            let (a, b, k) = (a as usize, b as usize, k as usize);
            let v = hair_wet[a].min(hair_wet[b]).min(1.5);
            let along = ((k - 1) as f32 / (segs - 1).max(1) as f32).sqrt();
            let mut pull = (0.2 * c.abs() * v * (1.0 + thin) * along).min(0.45);
            let (pa, pb) = (self.hairs[a].p[k], self.hairs[b].p[k]);
            let d = sub(pb, pa);
            let l = len(d);
            if l < 1e-6 {
                continue;
            }
            let touching = self.hairs[a].depth[k] > 0.0 || self.hairs[b].depth[k] > 0.0;
            if touching {
                pull *= on_paper;
            }
            let want = if c > 0.0 { s0 * (1.0 - c * v).max(BRIDGE_CLOSEST) } else { s0 };
            if (c > 0.0 && l > want) || (c < 0.0 && l < want) {
                // the pull moves the node and where it was a substep ago alike: bridged hairs slide
                // together (friction measures slip from the old place, and would otherwise put
                // them back), and the pull adds no speed
                let mut corr = mul(d, pull * (l - want) / l * 0.5);
                // a clump lies on the paper: where either hair touches, the pull is along it
                if touching {
                    corr[2] = 0.0;
                }
                self.hairs[a].p[k] = add(pa, corr);
                self.hairs[b].p[k] = sub(pb, corr);
                self.hairs[a].q[k] = add(self.hairs[a].q[k], corr);
                self.hairs[b].q[k] = sub(self.hairs[b].q[k], corr);
            }
        }
    }

    /// Let a wet brush's hairs gather into their clumps in the air, as a loaded brush does lifted
    /// from the paint before it touches: `SETTLE_PASSES` passes of the bridges, each followed by the
    /// hairs' own bend and stretch, in the frame `f`.
    fn settle(&mut self, f: &Frame) {
        if self.cohesion * self.clumping <= 0.0 {
            return;
        }
        let (hair_wet, thin) = self.wetness();
        let s0 = self.spacing;
        let segs = self.segs;
        let kb = ((0.04 + 0.5 * self.stiffness) * (1.0 - 0.35 * self.fill())).clamp(-0.6, 1.3);
        for pass in 0..SETTLE_PASSES {
            if pass % BRIDGE_EVERY as usize == 0 {
                self.bridge(&hair_wet, thin, s0, true);
            }
            self.pull_bridges(&hair_wet, thin, s0, 1.0);
            for hr in &mut self.hairs {
                let seg = hr.len / segs as f32;
                for k in 2..=segs {
                    let dir_prev = norm(sub(hr.p[k - 1], hr.p[k - 2]));
                    let target = add(hr.p[k - 1], mul(norm(add(dir_prev, f.to_world(hr.rest[k - 1]))), seg));
                    hr.p[k] = add(hr.p[k], mul(sub(target, hr.p[k]), kb));
                }
                for k in 2..=segs {
                    let d = sub(hr.p[k], hr.p[k - 1]);
                    let l = len(d).max(1e-9);
                    let corr = mul(d, (l - seg) / l);
                    if k == 2 { hr.p[k] = sub(hr.p[k], corr); } else { hr.p[k] = sub(hr.p[k], mul(corr, 0.5)); hr.p[k - 1] = add(hr.p[k - 1], mul(corr, 0.5)); }
                }
            }
        }
        for hr in &mut self.hairs {
            hr.q = hr.p.clone();
        }
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
        self.bridges.clear();
        self.settle(&f);
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
        // the liquid bridges: broken where stretched too far every substep, made where hairs meet
        // every few (finding who is near is the costly part)
        let (hair_wet, thin) = self.wetness();
        let s0 = self.spacing;
        if self.cohesion * self.clumping != 0.0 {
            let form = self.bridges.is_empty() || (pose.t / SUBSTEP_S).round() as u64 % BRIDGE_EVERY == 0;
            self.bridge(&hair_wet, thin, s0, form);
        } else {
            self.bridges.clear();
        }
        // the bend constraint's correction per iteration. Coupled to the stretch and contact
        // constraints it diverges (the hairs fly apart into NaN) past about 1.4, and below about
        // -0.7 (measured: 1.33 holds and 1.42 fails painting; dry, -0.6 holds and -0.75 fails), so it
        // stops just short of those: a dry brush past stiffness 2.5 is as rigid as a hair gets, and
        // below -1.3 (joints driven away from their rest bend) as wild. Everything between is free.
        let kb = ((0.04 + 0.5 * self.stiffness) * (1.0 - 0.35 * wet)).clamp(-0.6, 1.3);
        let (mu_s, mu_k) = self.friction;
        let splay = self.splay;
        let (scatter, scatter_seed) = (self.splay_scatter, self.scatter_seed);
        let (fan, root_mean, spacing) = (self.splay_fan, self.root_mean, self.spacing);
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
            for (hair_index, hr) in self.hairs.iter_mut().enumerate() {
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
                // contact and friction; a hair pressed in is pushed out from the brush's middle
                // (splay), and the grip below resists that as it resists any slip
                let out = {
                    let o = f.to_world([hr.root[0], hr.root[1], 0.0]);
                    let l = (o[0] * o[0] + o[1] * o[1]).sqrt();
                    if l > 1e-6 { [o[0] / l, o[1] / l] } else { [0.0, 0.0] }
                };
                // (scattered: turned and scaled by this hair's own slow noise)
                // (fanned: in proportion to how far out the root is)
                let out = if fan != 0.0 {
                    let r = (hr.root[0] * hr.root[0] + hr.root[1] * hr.root[1]).sqrt() / root_mean.max(1e-6);
                    let k = 1.0 - fan + fan * r;
                    [out[0] * k, out[1] * k]
                } else {
                    out
                };
                let out = if scatter != 0.0 && splay != 0.0 {
                    let hi = hair_index as i64;
                    let tu = pose.t / SCATTER_S;
                    // keyed on where the root is across the brush, at `SCATTER_SPACINGS` root spacings:
                    // neighbours buckle alike, so the hairs go in bundles, each its own way
                    let at = (hr.root[0] + 0.71 * hr.root[1]) / (SCATTER_SPACINGS * spacing).max(1e-6);
                    let turn = scatter * std::f32::consts::PI * (2.0 * crate::surface::vnoise(at, tu, scatter_seed ^ 0x5C47) - 1.0);
                    let amount = (1.0 + scatter * (2.0 * crate::surface::vnoise(at + 50.0, tu, scatter_seed ^ 0xA770) - 1.0)).max(0.0);
                    // a hair at the very middle has no outward: it gets its own way out
                    let base = if out == [0.0, 0.0] { let a = std::f32::consts::TAU * crate::rng::hash2(hi, 3, scatter_seed); [a.cos(), a.sin()] } else { out };
                    let (sn, cs) = turn.sin_cos();
                    [(base[0] * cs - base[1] * sn) * amount, (base[0] * sn + base[1] * cs) * amount]
                } else {
                    out
                };
                for k in 2..=segs {
                    let zs = surface(hr.p[k][0], hr.p[k][1]);
                    let pen = zs - hr.p[k][2];
                    if pen > 0.0 {
                        hr.p[k][2] = zs;
                        hr.depth[k] = hr.depth[k].max(pen);
                        if splay != 0.0 {
                            hr.p[k][0] += out[0] * splay * pen;
                            hr.p[k][1] += out[1] * splay * pen;
                        }
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
            // clumping: a wet brush's neighbouring tips drawn together, by (1 - clumping) of their
            // pull (at clumping 0 exactly as brushes clumped before liquid bridges; the pull stops
            // short of 0.45, and the tips are drawn no closer than touching, where a cohesion past 1
            // would otherwise overshoot)
            // (past clumping 1 its share is negative: the tips are pushed apart, but only back to
            // their spacing at the root, past which nothing pushes)
            let tips = 1.0 - self.clumping;
            if wet > 0.05 && self.cohesion > 0.0 && tips != 0.0 {
                let k = (self.cohesion * wet * 0.2 * tips.abs()).min(0.45);
                for &(a, b, rest) in &self.pairs {
                    let (pa, pb) = (self.hairs[a].p[segs], self.hairs[b].p[segs]);
                    let d = sub(pb, pa);
                    let l = len(d);
                    let want = if tips > 0.0 { (rest * (1.0 - self.cohesion * wet)).max(0.0) } else { rest };
                    if l > 1e-6 && ((tips > 0.0 && l > want) || (tips < 0.0 && l < want)) {
                        let corr = mul(d, k * (l - want) / l * 0.5);
                        self.hairs[a].p[segs] = add(pa, corr);
                        self.hairs[b].p[segs] = sub(pb, corr);
                    }
                }
            }
            // and the liquid bridges, by clumping's share of theirs
            if !self.bridges.is_empty() {
                self.pull_bridges(&hair_wet, thin, s0, CONTACT_PULL);
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

    /// How tightly each hair is drawn in to its clump, 0..1: the nearest that a hair bridged to it
    /// lies, over the root spacing, along the hair's outer half (1 for a hair with no bridge there,
    /// or one no nearer than the spacing). The dense bristles that follow a hair lie that much closer
    /// to it, so a clump's bristles bundle as its hairs do.
    pub fn squeeze(&self) -> Vec<f32> {
        let mut sq = vec![1.0f32; self.hairs.len()];
        let s0 = self.spacing.max(1e-6);
        for &(a, b, k) in &self.bridges {
            let (a, b, k) = (a as usize, b as usize, k as usize);
            if 2 * k >= self.segs {
                let r = (len(sub(self.hairs[a].p[k], self.hairs[b].p[k])) / s0).clamp(BRIDGE_CLOSEST, 1.0);
                sq[a] = sq[a].min(r);
                sq[b] = sq[b].min(r);
            }
        }
        sq
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
    // (the caller's noise identity for this stroke, if it gave one: `Canvas::stroke_noise`)
    let noise = c.stroke_noise.take();
    br.scatter_seed = noise.unwrap_or(id) as u64;
    // (the behaviours this stroke varies along itself, one value a sample; the brush's own after)
    let vary: Vec<(usize, Vec<f32>)> = std::mem::take(&mut br.vary).into_iter().filter(|(w, v)| *w < VARY_NAMES.len() && v.len() == samples.len()).collect();
    let own: Vec<(usize, f32)> = vary.iter().map(|(w, _)| (*w, *br.knob(*w))).collect();
    let window = |t: f32| -> (usize, f32) {
        if samples.len() < 2 { return (0, 0.0) }
        let i = samples.windows(2).position(|p| t <= p[1].t).unwrap_or(samples.len() - 2);
        let span = (samples[i + 1].t - samples[i].t).max(1e-9);
        (i, ((t - samples[i].t) / span).clamp(0.0, 1.0))
    };
    // (and its length in the world, `Canvas::stroke_arc`: the load runs down by it, so a stereo pair's
    // eyes run out at the same place along a movement they see at different lengths)
    let warc = c.take_arc_units(samples.len());
    let travel_at = |t: f32| -> f32 {
        let Some(w) = &warc else { return 1.0 };
        let i = samples.windows(2).position(|p| t <= p[1].t).unwrap_or(samples.len().saturating_sub(2));
        // the nearest sample window that moved, from this one back, then forward
        let moved = |j: usize| {
            let (a, b) = (&samples[j], &samples[j + 1]);
            let d = (b.x - a.x).hypot(b.y - a.y);
            (d > 1e-6).then(|| ((w[j + 1] - w[j]) / d).max(0.0))
        };
        if samples.len() < 2 { return 1.0 }
        (0..=i).rev().chain(i + 1..samples.len() - 1).find_map(moved).unwrap_or(1.0)
    };
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
    // how tightly each hair's dense bristles are drawn in to it (`Bristles::squeeze`), eased over
    // `SQUEEZE_S` so that bridges forming and snapping don't jolt the bristles about
    let mut squeezed = vec![1.0f32; br.hairs.len()];
    let mut water_ids: Vec<usize> = Vec::new();
    let mut water_tau = 0.0f32;
    let mut water_cells: Vec<(usize, f32, f32)> = Vec::new();
    let mut since_flow = 0.0f32;
    let mut last_hair_px: Vec<Option<(f32, f32)>> = vec![None; br.hairs.len()];
    for k in 0..=n {
        let t = t0 + k as f32 * h;
        let pose = pose_at(samples, t);
        if !vary.is_empty() {
            let (i, u) = window(t);
            for (w, v) in &vary {
                let j = (i + 1).min(v.len() - 1);
                *br.knob(*w) = v[i] + (v[j] - v[i]) * u;
            }
        }
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
        // oil: each dense bristle trades paint where its hair touches, drawn in toward it as far as
        // the hair is drawn in to its clump
        let before = squeezed.clone();
        let m = br.clumping;
        for (now, want) in squeezed.iter_mut().zip(br.squeeze()) {
            // (by clumping's share: past 1 drawn in further, below 0 spread out)
            let want = (1.0 - m * (1.0 - want)).clamp(BRIDGE_CLOSEST, 2.0);
            *now += (want - *now) * (h / SQUEEZE_S).min(1.0);
        }
        if let Some(held) = br.held.as_mut() {
            let tool = held.tool.clone();
            let full = held.full();
            let rb = (tool.hair_radius() * s).max(if tool.point > 0.0 { 0.02 } else { 0.55 });
            let fr = Frame::of(&pose);
            let lim = (0usize, 0usize, f.full_w, f.full_h);
            let mut sf = c.surf();
            sf.noise = noise;
            sf.travel = travel_at(t);
            for (bi, b) in held.bristles.iter_mut().enumerate() {
                let (g, off) = br.assign[bi];
                match cts[g] {
                    None => {
                        b.prev[0] = None;
                        last_px[bi] = None;
                    }
                    Some((xy, share)) => {
                        let o = fr.to_world([off[0] * squeezed[g], off[1] * squeezed[g], 0.0]);
                        let cur = ((xy[0] + o[0]) / mmu * s, (xy[1] + o[1]) / mmu * s);
                        // a bristle drawn in toward its hair moves with it, not over the paper: its
                        // last place moves too, so the gathering isn't travel that lays paint
                        if let Some(p) = last_px[bi].as_mut() {
                            let was = fr.to_world([off[0] * before[g], off[1] * before[g], 0.0]);
                            p.0 += (o[0] - was[0]) / mmu * s;
                            p.1 += (o[1] - was[1]) / mmu * s;
                        }
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
    for (w, v) in own {
        *br.knob(w) = v;
    }
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
    fn splay_fans_a_pressed_brush_out_and_none_leaves_it_as_it_was() {
        let c = Canvas::new(200, 1.0, [1.0; 3]);
        let surf = |_: f32, _: f32| 0.0f32;
        let spread = |splay: f32| -> f32 {
            let mut tool = Tool::hog_flat(40.0);
            tool.splay = splay;
            let mut b = Bristles::new(&tool, c.mm_per_unit(), 24, 5, 3);
            let samples = line(200.0, 800.0, 500.0, 1.0, 1.2, 0.3, 0.0);
            for k in 0..=1000 {
                b.step(&pose_at(&samples, k as f32 * 0.001), 0.001, c.mm_per_unit(), &surf);
            }
            // the tips' spread across the stroke (y), mm
            let ys: Vec<f32> = b.hairs.iter().map(|h| h.p[5][1]).collect();
            ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min)
        };
        let (none, some, lots) = (spread(0.0), spread(1.0), spread(4.0));
        assert!(some > none * 1.05 && lots > some, "splay fans the tips out: {none} {some} {lots} mm");
        // a brush saved before splay was kept reads back with none
        let mut tool = Tool::hog_flat(40.0);
        tool.splay = 2.0;
        let b = Bristles::new(&tool, c.mm_per_unit(), 8, 4, 3);
        let mut b = b;
        b.clumping = 0.5;
        let mut st = b.write_state();
        assert_eq!(Bristles::read_state(&st).unwrap().splay, 2.0);
        assert_eq!(Bristles::read_state(&st).unwrap().clumping, 0.5);
        // one saved before the splay's fan or scatter was kept reads back with none, and one saved
        // before clumping was kept with the tip-pair clumping (0)
        st.truncate(st.len() - 4);
        assert_eq!(Bristles::read_state(&st).unwrap().splay_fan, 0.0);
        st.truncate(st.len() - 4);
        assert_eq!(Bristles::read_state(&st).unwrap().splay_scatter, 0.0);
        assert_eq!(Bristles::read_state(&st).unwrap().clumping, 0.5);
        st.truncate(st.len() - 4);
        assert_eq!(Bristles::read_state(&st).unwrap().clumping, 0.0);
        st.truncate(st.len() - 4);
        assert_eq!(Bristles::read_state(&st).unwrap().splay, 0.0);
    }

    /// Clumps among the wet hairs' tips: groups joined by liquid bridges at the last joint.
    fn tip_clumps(b: &Bristles) -> usize {
        let n = b.hairs.len();
        let mut up: Vec<usize> = (0..n).collect();
        fn root(up: &mut Vec<usize>, mut i: usize) -> usize { while up[i] != i { up[i] = up[up[i]]; i = up[i]; } i }
        for &(a, c, k) in &b.bridges {
            if k as usize == b.segs {
                let (ra, rc) = (root(&mut up, a as usize), root(&mut up, c as usize));
                up[ra] = rc;
            }
        }
        (0..n).filter(|&i| root(&mut up, i) == i).count()
    }

    /// A loaded hog flat of 48 hairs, with `splay` and `cohesion`, pressed hard along a stroke for a
    /// second.
    fn pressed(splay: f32, cohesion: f32, loaded: bool) -> Bristles {
        pressed_with(splay, cohesion, 1.0, 0.0, loaded)
    }

    /// `pressed`, with `clumping` and `scatter` (the splay's scatter) too.
    fn pressed_with(splay: f32, cohesion: f32, clumping: f32, scatter: f32, loaded: bool) -> Bristles {
        pressed_fan(splay, cohesion, clumping, scatter, 0.0, loaded)
    }

    /// `pressed_with`, the splay fanned by `fan`.
    fn pressed_fan(splay: f32, cohesion: f32, clumping: f32, scatter: f32, fan: f32, loaded: bool) -> Bristles {
        let c = Canvas::new(200, 1.0, [1.0; 3]);
        let surf = |_: f32, _: f32| 0.0f32;
        let mut tool = Tool::hog_flat(40.0);
        tool.splay = splay;
        let mut b = Bristles::new(&tool, c.mm_per_unit(), 48, 5, 3);
        if loaded {
            let mut held = Held::new(tool.clone(), 5).with_engine(c.engine());
            held.load(Paint::body([0.1, 0.1, 0.1]), 1.0);
            b = b.with_held(held);
        }
        b.cohesion = cohesion;
        b.clumping = clumping;
        b.splay_scatter = scatter;
        b.splay_fan = fan;
        let samples = line(200.0, 800.0, 500.0, 1.0, 1.2, 0.3, 0.0);
        for k in 0..=1000 {
            b.step(&pose_at(&samples, k as f32 * 0.001), 0.001, c.mm_per_unit(), &surf);
        }
        b
    }

    #[test]
    fn a_wet_brush_bridges_its_hairs_and_splay_tears_it_into_clumps() {
        let (dry, none) = (pressed(2.0, 1.0, false), pressed(2.0, 0.0, true));
        assert!(dry.bridges.is_empty() && none.bridges.is_empty(), "a dry brush, or no cohesion, makes no bridges");
        let (held, torn) = (pressed(0.0, 1.0, true), pressed(4.0, 1.0, true));
        let (ch, ct) = (tip_clumps(&held), tip_clumps(&torn));
        eprintln!("tip clumps of 48 hairs: {ch} unsplayed ({} bridges), {ct} at splay 4 ({} bridges)", held.bridges.len(), torn.bridges.len());
        assert!(!held.bridges.is_empty(), "a wet brush's hairs are bridged");
        assert!(ct > ch && ct < 48, "splay tears the tips into clumps: {ch} clumps unsplayed, {ct} splayed");
    }

    #[test]
    fn a_scattered_splay_tears_a_wet_brush_into_more_clumps() {
        // fanned, so that neighbours part everywhere; scattered, so that bundles go each their own way
        let (straight, scattered) = (pressed_fan(4.0, 4.0, 1.0, 0.0, 1.0, true), pressed_fan(4.0, 4.0, 1.0, 1.0, 1.0, true));
        let (cs, cx) = (tip_clumps(&straight), tip_clumps(&scattered));
        eprintln!("tip clumps of 48 hairs at splay 4, cohesion 4, fanned: {cs} straight, {cx} scattered");
        assert!(cx > cs, "hairs splaying each their own way tear into more clumps: {cs} straight, {cx} scattered");
    }

    #[test]
    fn a_stroke_varies_a_behaviour_along_itself_and_the_brush_keeps_its_own() {
        let paint = |vary: Option<f32>, splay: f32| {
            let mut c = Canvas::new(200, 1.0, [1.0; 3]);
            let mut tool = Tool::hog_flat(60.0);
            tool.splay = splay;
            let mut held = Held::new(tool, 5).with_engine(c.engine());
            held.load(Paint::body([0.1, 0.1, 0.1]), 1.0);
            let mut b = Bristles::new(&held.tool, c.mm_per_unit(), 24, 5, 3).with_held(held);
            let samples = line(150.0, 850.0, 500.0, 1.0, 1.0, 0.4, 0.0);
            if let Some(v) = vary {
                b.vary = vec![(0, vec![v; samples.len()])];
            }
            stroke(&mut c, None, &mut b, &samples, None);
            (c.wet.vol.clone(), b.splay)
        };
        // the same value at every sample paints as the brush made with it, and the brush is given back
        // its own splay after
        let (varied, kept) = paint(Some(3.0), 0.0);
        let (made, _) = paint(None, 3.0);
        assert_eq!(kept, 0.0);
        assert!(varied == made, "a stroke varying splay to 3 throughout paints as a brush of splay 3");
        assert!(varied != paint(None, 0.0).0, "and not as the brush's own splay");
    }

    #[test]
    fn a_bridge_forms_within_the_spacing_holds_past_it_and_snaps_beyond_its_reach() {
        let c = Canvas::new(200, 1.0, [1.0; 3]);
        let mut b = Bristles::new(&Tool::hog_flat(40.0), c.mm_per_unit(), 2, 4, 3);
        // (no cohesion: bridges form within the root spacing)
        b.cohesion = 0.0;
        let s0 = b.spacing;
        let wet = [1.0, 1.0];
        let apart = |b: &mut Bristles, d: f32| for k in 0..=b.segs { b.hairs[0].p[k] = [0.0, 0.0, k as f32]; b.hairs[1].p[k] = [d, 0.0, k as f32]; };
        apart(&mut b, 0.5 * s0);
        b.bridge(&wet, 0.0, s0, true);
        assert_eq!(b.bridges.len(), b.segs - 1, "bridged at every free joint");
        apart(&mut b, 2.0 * s0);
        b.bridge(&wet, 0.0, s0, true);
        assert_eq!(b.bridges.len(), b.segs - 1, "stretched to twice the spacing, the bridges hold");
        apart(&mut b, 3.0 * s0);
        b.bridge(&wet, 0.0, s0, true);
        assert!(b.bridges.is_empty(), "past their reach (2.8 spacings, full) they snap");
        apart(&mut b, 2.0 * s0);
        b.bridge(&wet, 0.0, s0, true);
        assert!(b.bridges.is_empty(), "and none forms again until the hairs come within the spacing");
    }

    /// How unevenly the tips lie across the stroke: the spread of the gaps between neighbours (sorted
    /// across) over their mean. Even hairs near 0; hairs gathered in clumps, with gaps between, more.
    fn clumpiness(b: &Bristles) -> f32 {
        let mut ys: Vec<f32> = b.hairs.iter().map(|h| h.p[b.segs][1]).collect();
        ys.sort_by(f32::total_cmp);
        let gaps: Vec<f32> = ys.windows(2).map(|w| w[1] - w[0]).collect();
        let m = gaps.iter().sum::<f32>() / gaps.len() as f32;
        (gaps.iter().map(|g| (g - m) * (g - m)).sum::<f32>() / gaps.len() as f32).sqrt() / m.max(1e-6)
    }

    #[test]
    #[ignore]
    fn print_scatter() {
        for co in [0.35f32, 1.0, 4.0] {
            let row: Vec<String> = [(0.0f32, 0.0f32), (0.0, 1.0), (0.3, 1.0), (1.0, 1.0)].iter().map(|&(sc, fan)| {
                let b = pressed_fan(4.0, co, 1.0, sc, fan, true);
                let ys: Vec<f32> = b.hairs.iter().map(|h| h.p[b.segs][1]).collect();
                let spread = ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min);
                format!("scatter {sc} fan {fan}: {} clumps, clumpiness {:.2}, spread {:.1} mm, {} bridges", tip_clumps(&b), clumpiness(&b), spread, b.bridges.len())
            }).collect();
            eprintln!("cohesion {co} (spacing {:.2} mm): {}", pressed_with(0.0, 0.0, 0.0, 0.0, true).spacing, row.join("; "));
        }
    }

    #[test]
    #[ignore]
    fn print_clumpiness() {
        for sp in [0.0f32, 2.0] {
            let row: Vec<String> = [0.0f32, 0.35, 1.0, 4.0, 16.0].iter().map(|&c| { let b = pressed(sp, c, true); format!("c{c}: {:.2} ({} clumps)", clumpiness(&b), tip_clumps(&b)) }).collect();
            eprintln!("splay {sp}: {}", row.join(", "));
        }
    }

    #[test]
    #[ignore]
    fn print_cohesion_paint() {
        for co in [0.0f32, 1.0, 4.0, 16.0] {
            let mut c = Canvas::new(300, 1.0, [1.0; 3]);
            let mut held = Held::new(Tool::hog_flat(60.0), 5).with_engine(c.engine());
            held.load(Paint::body([0.1, 0.1, 0.1]), 1.0);
            let mut b = Bristles::new(&held.tool, c.mm_per_unit(), 48, 6, 3).with_held(held);
            b.cohesion = co;
            b.clumping = 1.0;
            let rep = stroke(&mut c, None, &mut b, &line(150.0, 850.0, 500.0, 2.0, 1.0, 0.5, 0.0), None);
            let sq = b.squeeze();
            let w = c.f.w;
            let bands: Vec<String> = (0..7).map(|k| { let (x0, x1) = (w * (3 + 2 * k) / 20, w * (5 + 2 * k) / 20); format!("{:.0}", c.wet.vol.iter().enumerate().filter(|(i, _)| (i % w) >= x0 && (i % w) < x1).map(|(_, v)| *v).sum::<f32>()) }).collect();
            eprintln!("  along the stroke: {}", bands.join(" "));
            let vols: Vec<f32> = c.wet.vol.iter().copied().filter(|&v| v > 0.02).collect();
            let thick = c.wet.vol.iter().filter(|&&v| v > 1.0).count();
            eprintln!("  covered px {} (thick>1: {}), mean film {:.2}, max {:.2}", vols.len(), thick, vols.iter().sum::<f32>() / vols.len().max(1) as f32, vols.iter().cloned().fold(0.0, f32::max));
            eprintln!("cohesion {co}: laid {:.4}, contacts {}, max contact {:.2}, brush left {:.2}, bridges {}, mean squeeze {:.2}, tip clumps {}",
                c.wet_total(), rep.contacts, rep.max_contact, b.fill(), b.bridges.len(), sq.iter().sum::<f32>() / sq.len() as f32, tip_clumps(&b));
        }
    }

    #[test]
    fn hairs_stay_finite_at_any_cohesion() {
        for (co, cl) in [-1000.0f32, -10.0, -1.0, 0.0, 1.0, 10.0, 1000.0].into_iter().flat_map(|co| [-10.0f32, -1.0, 0.0, 0.5, 1.0, 2.0, 10.0].map(move |cl| (co, cl))) {
            let b = pressed_with(2.0, co, cl, 1.0, true);
            assert!(b.hairs.iter().all(|h| h.p.iter().flatten().all(|v| v.is_finite())), "cohesion {co}, clumping {cl}: the hairs flew apart");
        }
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
        // (added later, last: a state without it is a brush with no splay, as it was made)
        f(&mut w, self.splay);
        // (and later still: without it, the tip-pair clumping it was made with; and the splay's
        // scatter, without it none)
        f(&mut w, self.clumping);
        f(&mut w, self.splay_scatter);
        f(&mut w, self.splay_fan);
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
        let splay = take(4).map(|x| f32::from_le_bytes(x.try_into().unwrap())).unwrap_or(0.0);
        let clumping = take(4).map(|x| f32::from_le_bytes(x.try_into().unwrap())).unwrap_or(0.0);
        let splay_scatter = take(4).map(|x| f32::from_le_bytes(x.try_into().unwrap())).unwrap_or(0.0);
        let splay_fan = take(4).map(|x| f32::from_le_bytes(x.try_into().unwrap())).unwrap_or(0.0);
        if assign.iter().any(|a| a.0 >= nh) || pairs.iter().any(|p| p.0 >= nh || p.1 >= nh) {
            return Err("a brush state whose bristles name hairs it doesn't have".into());
        }
        Ok(Bristles { kind, width_mm, length_mm, stiffness, segs, hairs, friction, yield_rad, plastic, recover, cohesion, clumping, splay, splay_scatter, splay_fan, root_mean: 0.0, vary: vec![], scatter_seed: 0, damping, pose, held, belly, assign, pairs, bridges: vec![], spacing: 0.0 }.spaced())
    }
}
