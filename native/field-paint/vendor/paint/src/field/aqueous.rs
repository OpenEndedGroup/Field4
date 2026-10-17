//! Water on an absorbent sheet, and the pigment it carries: watercolour and ink.
//!
//! This is Field's model, not upstream's; it shares the canvas's pixel grid and writes the paper's
//! relief into the canvas's surface height, so a pencil, an oil brush and the relief light feel the
//! paper too. Everything else here is state of its own, kept beside the canvas (`Water`).
//!
//! # State, per pixel, with units
//!
//! - `sw`, **surface water**: µm of water standing on the sheet.
//! - `aw`, **absorbed water**: µm of water held in the sheet's pores, at most `cap` (the water the
//!   sheet holds there when saturated, from its local grammage).
//! - For each pigment species `k`, mass per area in µg/mm² (= g/m²): `ms` **suspended** in the
//!   surface water and moving with it; `ma` **carried** in the absorbed water and moving with it;
//!   `md` **deposited** on and among the fibres, loose, the sediment of water still on or in the sheet
//!   (water flowing through carries it on); `mg` **glued**, deposited and dried in place, its gum arabic
//!   set (rewetting lifts it slowly, a brush faster); `mb` **bound** (stained into the fibres: adsorbed
//!   from the absorbed water, or dried there), which does not lift.
//!
//! # What is physical, and on what basis
//!
//! - **Surface flow** is a thin film in the lubrication limit: the volume flux per unit width is
//!   `ρ g w³ / (3 μ) · ∇η`, `η` the water's surface (the paper's relief plus the water, times
//!   cos(tilt)) minus the drop of the tilted sheet along its slope. Gravity and the sheet's relief drive
//!   it; surface tension is not modelled, so shapes finer than water's capillary length (2.7 mm) are
//!   not resolved. For stability the exchange across an edge in one step is limited to a quarter of
//!   the head difference, and steps are at least 10 ms: water deeper than about `85 µm × (pixel /
//!   0.2 mm)^(2/3)` (0.2 mm on a studio canvas's 0.8 mm pixels) spreads more slowly than it would,
//!   at the speed of water that deep.
//! - **Wetting** is contact-angle hysteresis reduced to one number: water advances onto dry paper
//!   only from a film deeper than the paper's `pin_um` (less on damp paper, three times as much on
//!   paper sealed by oil paint). Without it a film would spread without limit.
//! - **Absorption** draws surface water into the pores at the paper's sizing rate `absorb_um_s`
//!   times the share of the pores still empty (a linear stand-in for Washburn's slowing uptake).
//! - **Wicking** moves absorbed water within the sheet by a capillary diffusion whose diffusivity
//!   rises with saturation (`wick · s²`), faster along the fibres (`anisotropy`), so wet fronts stay
//!   sharp and run along the grain.
//! - **Evaporation** takes `evap_um_s` from the surface water, more where the water's edge is near
//!   (vapour leaves the rim of a wet patch fastest; R. D. Deegan et al., "Capillary flow as the cause
//!   of ring stains from dried liquid drops", Nature 389, 827, 1997), and from the absorbed water once
//!   the surface is dry. Water flowing out to replace the rim's loss carries pigment there: the dark
//!   edge of a dried wash is that flow, not a rule.
//! - **Pigment** moves with the water it is in. Suspended particles settle at their Stokes velocity
//!   `2/9 · Δρ g r² / μ` from their density and size, so dense coarse pigments (ultramarine, earths)
//!   settle out within a minute, where the water laid them, and fine ones (phthalo, carbon) stay
//!   suspended until the water is gone, diffusing to the fibres (Stokes–Einstein) and sticking there.
//!   A wash's surface lies level, so it stands deeper over the sheet's hollows and leaves more of
//!   either there: the paper's grain in a wash. Granulation proper, coarse pigment gathering in the
//!   hollows beyond that, is not modelled. As water is absorbed, particles larger than the pores are
//!   filtered at the surface (`filter`); the rest enter the sheet, where staining pigments adsorb
//!   onto the fibres (`stain`, per second). Sediment that has not dried is entrained by water flowing
//!   through its pixel, at `ENTRAIN` times the rate the water there turns over (so clean water flooding a
//!   damp wash pushes its colour ahead: a backrun's pale centre and dark edge). A pixel that dries
//!   glues its sediment down; water standing on a glued deposit lifts it back into suspension at the
//!   pigment's `lift` rate (its gum arabic redissolving), faster under a moving brush.
//! - **Marangoni flow**: paint's gum arabic and wetting agents lower the water's surface tension, by
//!   up to `marangoni_n_m` (0.008 N/m; an estimate) as the paint's concentration rises (the pigment
//!   stands for the additives that go with it), by Szyszkowski's law up to the agents' critical
//!   micelle concentration and no further, so a wash of paint is evenly tensioned inside. Cleaner, higher-tension water pulls the surface
//!   toward it, a film flux of `h² / (2 μ) · ∇σ`, so paint touched into a wet wash spreads into it:
//!   wet-in-wet's blooms. The agents are their own field (`agent`), laid with the paint, carried by
//!   the water and spreading themselves; the surface moves at `h / (2 μ) · ∇σ` (capped at 10 mm/s),
//!   carrying the agents with it, and the suspended pigment, mixed through the film's depth, at half
//!   that speed. The agents also adsorb onto the fibres
//!   they wet (`AGENT_ADSORB_S`, 0.2 per second; an estimate), so the drive dies away within seconds
//!   and a bloom stays where it reached. The water itself is not moved by the flow.
//! - **Dispersion** (an approximation, off by default, `Water::dispersion_mm2_s`): a diffusion of the
//!   pigment's concentration through standing water, for mixing the model does not resolve.
//! - **Optics** are Kubelka–Munk: each species has absorption and scattering per µg/mm², the layer's
//!   K and S are the sums, and it is laid over the canvas's own picture.
//!
//! What is an approximation for the look and not a simulation: the paper's structure is generated
//! (flocs of fibre and the felt's knots, `Paper::form`), not made by a simulated papermaking; the
//! darkening of wet paper is a fixed optical factor; every number for a paper or a pigment is an
//! estimate within the ranges they are reported in, not a measurement of a particular product.
//!
//! # Accounting
//!
//! Every volume and mass is counted in `Ledger`: water added by tools, taken up by tools, absorbed,
//! evaporated and run off the sheet's edge; pigment added, taken up, run off and sealed into the
//! picture under later paint. What is on and in the sheet is always what was added less what left,
//! to float rounding (`Water::check`).

use crate::Rgb;
use rayon::prelude::*;

thread_local! {
    /// Seconds spent in each phase of `Water::step` on this thread (profiling): flow, Marangoni and
    /// the rest of the surface, wicking, per pixel.
    pub static PROFILE: std::cell::RefCell<[f64; 4]> = const { std::cell::RefCell::new([0.0; 4]) };
}
fn prof(k: usize, since: std::time::Instant) {
    PROFILE.with(|p| p.borrow_mut()[k] += since.elapsed().as_secs_f64());
}

/// ρ g / (3 μ) for water at 20 °C: 998 kg/m³ × 9.81 m/s² / (3 × 1.0 mPa s), per m per s.
const GRAVITY_FILM: f64 = 998.0 * 9.81 / (3.0 * 1.0e-3);
/// Water viscosity, Pa s (20 °C).
const MU: f64 = 1.0e-3;
/// Water density, kg/m³.
const RHO_W: f64 = 998.0;
/// k T at 20 °C, J.
const BOLTZMANN_T: f64 = 1.380649e-23 * 293.15;
/// The time over which a wash's particles diffuse to the paper, s: about how long a wash stays wet
/// (an estimate; the boundary layer's depth goes as its square root).
const DIFFUSE_REF_S: f32 = 100.0;
/// Surface water below this, µm, is a dry pixel (what is left wets the pores or evaporates).
const W_DRY: f32 = 0.05;
/// How much more the rim of a wet patch evaporates than its middle: × 1 + RIM × the share of the
/// sheet within VAPOUR_MM that is dry (vapour diffuses away from a rim into dry air on its open side;
/// Deegan et al. 1997 find the flux rising steeply toward a drying drop's edge).
const RIM: f32 = 2.0;
const VAPOUR_MM: f32 = 1.0;
/// Absorbed water evaporates from the sheet's surface this much slower than open water.
const PORE_EVAP: f32 = 0.6;
/// The falling-rate period of drying: below this saturation the pores lose water at this share of
/// their saturated rate, not less, so a sheet dries in finite time (an estimate; real paper's
/// falling rate depends on its thickness and fibre).
const PORE_FLOOR: f32 = 0.2;
/// How much wet paper darkens what is seen through it (refractive-index matching), at saturation.
/// An optical approximation, not simulated.
const WET_DARKEN: f32 = 0.12;
/// The shortest step taken for the sake of the surface flow, s; and the longest while there is any.
const MIN_FLOW_DT: f64 = 1e-2;
const MAX_FLOW_DT: f64 = 0.5;
/// The largest change of surface water depth, µm, a flow step may make before steps shrink.
const FLOW_CHANGE_UM: f32 = 2.0;
/// The largest change of the pores' saturation a wicking step may make before steps shrink.
const WICK_CHANGE: f32 = 0.05;
/// Steps between recomputing where the sheet is wet (the wet region grows a pixel a step at most).
const BOUNDS_EVERY: usize = 16;
/// The longest step once no water stands on the sheet, s.
const MAX_PORE_DT: f64 = 5.0;
/// How far paint lowers water's surface tension at most, N/m (gum arabic and wetting agents: clean
/// water is 0.072; an estimate), and the paint concentration that does half of it, µg/mm³.
pub const MARANGONI_N_M: f32 = 0.008;
/// Wetting agent and gum laid with each µg of pigment, µg (a proxy: tube watercolour carries about as
/// much binder and additive as pigment).
const AGENT_PER_UG: f32 = 1.0;
/// The rate the wetting agents adsorb onto the fibres they wet, leaving the surface, per second, while
/// the fibres have room (an estimate: blooms stop spreading within seconds, though the wash stays wet
/// for minutes).
const AGENT_ADSORB_S: f32 = 0.2;
/// How much wetting agent the fibres of a pixel hold once saturated, µg/mm² (Langmuir adsorption, an
/// estimate): a bloom spreading onto paper wetted only by clean water loses its agents to the fibres
/// and stops, while inside a wash the paper is soon saturated and the paint keeps its agents, so its
/// tension stays even.
const AGENT_FIBRE_UG_MM2: f32 = 0.05;
/// A film pulled along by its surface moves with a velocity falling linearly from the surface's to
/// nothing at the paper, so pigment mixed through its depth travels at half the surface's speed,
/// while the agents, at the surface, travel at all of it.
const MARANGONI_PIGMENT_SHARE: f32 = 0.5;
/// The fastest the Marangoni flow moves the surface, mm/s: the film law gives far more at a pixel-sharp
/// front, which real fronts are not; surface speeds of a few mm/s to cm/s are what blooms show (an
/// estimate).
const MARANGONI_MAX_MM_S: f32 = 10.0;
/// How the wetting agents lower the water's surface tension with their concentration c (µg/mm³):
/// Szyszkowski's law, Δσ ∝ ln(1 + c / A), up to the critical micelle concentration, past which more
/// agent forms micelles and the tension stays where it is. So inside a wash of paint (well above it)
/// the tension is even and drives nothing; it pulls only where paint meets cleaner water, a bloom.
/// Estimates: the CMC of the gum and wetting agents in paint, and Szyszkowski's A.
const AGENT_CMC_UG_MM3: f32 = 0.5;
const SZYSZKOWSKI_A_UG_MM3: f32 = 0.05;
/// Sediment entrainment: the share of a pixel's sediment taken up per turnover of the water there
/// (an estimate of the order of one).
const ENTRAIN: f32 = 1.0;
/// The pores' saturation below which a pixel with no water standing on it has dried enough for the
/// gum arabic to set (an estimate).
const GUM_SET: f32 = 0.3;
/// The default pigment dispersion, mm²/s (`Water::dispersion_mm2_s`).
pub const DISPERSION_MM2_S: f32 = 0.0;
/// Cells per parallel task: a small wet patch steps in one task, a wide wash on all the cores.
const CHUNK: usize = 8192;
/// Most pigment species one sheet holds.
pub const MAX_SPECIES: usize = 8;

/// A sheet's physical make-up. Numbers are estimates in the ranges reported for each kind of paper.
#[derive(Clone, Debug)]
pub struct Paper {
    pub name: String,
    /// Colour of the dry sheet, linear RGB.
    pub color: Rgb,
    /// Water the sheet holds when saturated, µm of water (≈ g/m² ÷ 1000 … its absorbency).
    pub cap_um: f32,
    /// Rate dry paper takes up standing water, µm/s: high for unsized paper, low for well-sized.
    pub absorb_um_s: f32,
    /// In-plane capillary diffusivity of the absorbed water at saturation, mm²/s.
    pub wick_mm2_s: f32,
    /// How much faster water wicks along the fibres than across them.
    pub anisotropy: f32,
    /// Typical fibre length, mm (sets the size of the sheet's flocs and how far the grain runs).
    pub fibre_mm: f32,
    /// How uneven the sheet's grammage is (formation): standard deviation relative to the mean.
    pub formation: f32,
    /// Surface relief: standard deviation of the height, µm (estimates: peak to valley is four to five
    /// times this, some 50 µm on cold press and 150 µm on rough). A wash's free surface is level, so
    /// it stands deeper over the hollows, and what it leaves follows: this is what decides how much a
    /// wash shows the paper's texture.
    pub roughness_um: f32,
    /// Size of the relief's features (the felt's knots, the mould's laid lines), mm.
    pub texture_mm: f32,
    /// Depth of water needed to advance onto dry paper, µm: contact-angle pinning. A front advances
    /// once the water behind it stands deeper than a puddle can on that surface, 2 ℓc sin(θa / 2) for
    /// water's capillary length ℓc = 2.7 mm and the advancing contact angle θa. Sized cotton sheets
    /// hold about a millimetre (θa about 20°: an estimate; a 15 µl drop stands as a dome about 4 mm
    /// across, and a bead gathers along the lower edge of a wash on a tilted board before it runs), so
    /// a stroke stays where the brush laid it; unsized papers drink the water before it can stand.
    pub pin_um: f32,
}

impl Paper {
    /// 300 g/m² cotton watercolour paper, cold pressed: a moderate tooth, internally and gelatin
    /// sized, so a wash stands on it for minutes before it sinks in. (Hard-sized paper's Cobb60, the
    /// water it takes up in a minute under standing water, is some 20–30 g/m²: about 0.4 µm/s.)
    pub fn cold_press() -> Paper {
        Paper { name: "cold press".into(), color: [0.86, 0.85, 0.80], cap_um: 160.0, absorb_um_s: 0.4, wick_mm2_s: 0.08,
            anisotropy: 1.3, fibre_mm: 2.5, formation: 0.08, roughness_um: 11.0, texture_mm: 1.4, pin_um: 1000.0 }
    }
    /// The same sheet hot pressed: smooth, a little harder sized.
    pub fn hot_press() -> Paper {
        Paper { name: "hot press".into(), roughness_um: 3.0, texture_mm: 0.8, absorb_um_s: 0.3, pin_um: 900.0, ..Paper::cold_press() }
    }
    /// Rough: a deep tooth that a dry brush skips across.
    pub fn rough() -> Paper {
        Paper { name: "rough".into(), roughness_um: 32.0, texture_mm: 2.6, pin_um: 1100.0, ..Paper::cold_press() }
    }
    /// Thin unsized xuan (rice) paper of long bast fibres, for ink: it drinks water at once and
    /// wicks it far along its grain.
    pub fn xuan() -> Paper {
        Paper { name: "xuan".into(), color: [0.88, 0.86, 0.80], cap_um: 45.0, absorb_um_s: 120.0, wick_mm2_s: 3.0,
            anisotropy: 2.0, fibre_mm: 5.0, formation: 0.18, roughness_um: 8.0, texture_mm: 1.0, pin_um: 3.0 }
    }
    /// Thick unsized blotting paper: it takes up a great deal of water fast, and wicks it evenly.
    pub fn blotting() -> Paper {
        Paper { name: "blotting".into(), color: [0.9, 0.89, 0.86], cap_um: 350.0, absorb_um_s: 60.0, wick_mm2_s: 1.2,
            anisotropy: 1.1, fibre_mm: 1.5, formation: 0.06, roughness_um: 15.0, texture_mm: 0.6, pin_um: 4.0 }
    }
    pub fn named(name: &str) -> Option<Paper> {
        Some(match name {
            "cold press" | "cold_press" => Paper::cold_press(),
            "hot press" | "hot_press" => Paper::hot_press(),
            "rough" => Paper::rough(),
            "xuan" | "rice" => Paper::xuan(),
            "blotting" => Paper::blotting(),
            _ => return None,
        })
    }

    /// The sheet over a `w` × `h` grid of `dx_mm` pixels: surface height (µm, zero mean), water
    /// capacity (µm) and fibre direction (radians) per pixel.
    ///
    /// Generated, not simulated: grammage is the sum of fibre flocs (anisotropic blobs about a fibre
    /// length long, oriented along a slowly turning grain, with a machine-direction bias); the surface
    /// is the felt's or mould's knots plus the thicker places standing proud. It is the input the
    /// water runs on, as the crate's linen weave is.
    pub fn form(&self, w: usize, h: usize, dx_mm: f32, seed: u64) -> (Vec<f32>, Vec<f32>, Vec<f32>, Vec<f32>) {
        let n = w * h;
        let mut rng = crate::Rng::new(seed ^ 0x9A9E_5EED);
        let area_mm2 = (w * h) as f32 * dx_mm * dx_mm;
        // the grain: a slowly turning direction, mostly along x (the machine direction)
        let grain_cells = ((self.fibre_mm * 6.0 / dx_mm).max(4.0)) as usize;
        let gw = w / grain_cells + 2;
        let gh = h / grain_cells + 2;
        let g: Vec<f32> = (0..gw * gh).map(|_| rng.normal() * 0.5).collect();
        let grain = |x: usize, y: usize| -> f32 {
            let (fx, fy) = (x as f32 / grain_cells as f32, y as f32 / grain_cells as f32);
            let (x0, y0) = (fx as usize, fy as usize);
            let (tx, ty) = (fx - x0 as f32, fy - y0 as f32);
            let at = |a: usize, b: usize| g[b.min(gh - 1) * gw + a.min(gw - 1)];
            let top = at(x0, y0) * (1.0 - tx) + at(x0 + 1, y0) * tx;
            let bot = at(x0, y0 + 1) * (1.0 - tx) + at(x0 + 1, y0 + 1) * tx;
            top * (1.0 - ty) + bot * ty
        };
        let fdir: Vec<f32> = (0..n).map(|i| grain(i % w, i / w)).collect();
        // grammage: flocs of fibre, each a fibre length long and a third as wide
        let mut gram = vec![0.0f32; n];
        let floc_l = (self.fibre_mm / dx_mm).max(1.0);
        let floc_w = (floc_l / 3.0).max(0.7);
        let flocs = (area_mm2 / (self.fibre_mm * self.fibre_mm / 3.0) * 1.5) as usize;
        for _ in 0..flocs {
            let (cx, cy) = (rng.f() * w as f32, rng.f() * h as f32);
            let a = fdir[(cy as usize).min(h - 1) * w + (cx as usize).min(w - 1)] + rng.normal() * 0.3;
            let (ca, sa) = (a.cos(), a.sin());
            let mass = 0.5 + rng.f();
            let r = floc_l.ceil() as isize + 1;
            for j in -r..=r {
                for i in -r..=r {
                    let (x, y) = (cx as isize + i, cy as isize + j);
                    if x < 0 || y < 0 || x >= w as isize || y >= h as isize {
                        continue;
                    }
                    let (u, v) = ((i as f32 + 0.5 - cx.fract()) * ca + (j as f32 + 0.5 - cy.fract()) * sa,
                        -(i as f32 + 0.5 - cx.fract()) * sa + (j as f32 + 0.5 - cy.fract()) * ca);
                    let q = (u / floc_l).powi(2) + (v / floc_w).powi(2);
                    if q < 4.0 {
                        gram[y as usize * w + x as usize] += mass * (-q).exp();
                    }
                }
            }
        }
        normalize(&mut gram, 1.0, self.formation);
        // the surface: knots of the felt (or the mould), plus the thicker places of the sheet
        let mut relief = vec![0.0f32; n];
        let knot = (self.texture_mm / dx_mm).max(0.6);
        let knots = (area_mm2 / (self.texture_mm * self.texture_mm) * 1.2) as usize;
        for _ in 0..knots {
            let (cx, cy) = (rng.f() * w as f32, rng.f() * h as f32);
            let rr = knot * (0.6 + 0.8 * rng.f());
            let amp = if rng.f() < 0.5 { 1.0 } else { -0.7 };
            let r = (rr * 1.5).ceil() as isize;
            for j in -r..=r {
                for i in -r..=r {
                    let (x, y) = (cx as isize + i, cy as isize + j);
                    if x < 0 || y < 0 || x >= w as isize || y >= h as isize {
                        continue;
                    }
                    let d2 = ((i as f32 + 0.5 - cx.fract()).powi(2) + (j as f32 + 0.5 - cy.fract()).powi(2)) / (rr * rr);
                    if d2 < 2.25 {
                        relief[y as usize * w + x as usize] += amp * (1.0 - d2 / 2.25).powi(2);
                    }
                }
            }
        }
        // the thicker places of the sheet stand a little proud, but the felt makes the tooth
        for i in 0..n {
            relief[i] += 0.35 * (gram[i] - 1.0) / self.formation.max(1e-3);
        }
        normalize(&mut relief, 0.0, self.roughness_um);
        let cap: Vec<f32> = gram.iter().map(|&g| self.cap_um * g.max(0.2)).collect();
        // permeability: water wicks fastest along the fibres themselves, so the sheet's fibres are
        // laid in as streaks a fibre long along the grain, over the formation (mean 1)
        let mut perm: Vec<f32> = gram.iter().map(|&g| 0.4 * g.max(0.2) * g.max(0.2)).collect();
        let streak_l = (self.fibre_mm / dx_mm).max(1.0);
        let streaks = (area_mm2 / (self.fibre_mm * dx_mm.max(0.05)) * 0.6) as usize;
        for _ in 0..streaks {
            let (mut x, mut y) = (rng.f() * w as f32, rng.f() * h as f32);
            let mut a = fdir[(y as usize).min(h - 1) * w + (x as usize).min(w - 1)] + rng.normal() * 0.4;
            let len = streak_l * (0.5 + rng.f());
            let mut t = 0.0;
            while t < len {
                if x < 0.0 || y < 0.0 || x >= w as f32 || y >= h as f32 {
                    break;
                }
                perm[y as usize * w + x as usize] += 1.0;
                x += a.cos() * 0.7;
                y += a.sin() * 0.7;
                a += rng.normal() * 0.05;
                t += 0.7;
            }
        }
        let m = perm.iter().map(|&v| v as f64).sum::<f64>() / n.max(1) as f64;
        perm.iter_mut().for_each(|v| *v /= m as f32);
        (relief, cap, fdir, perm)
    }
}

/// Shift and scale `v` to mean `mean` and standard deviation `sd`.
fn normalize(v: &mut [f32], mean: f32, sd: f32) {
    let n = v.len().max(1) as f64;
    let m = v.iter().map(|&x| x as f64).sum::<f64>() / n;
    let s = (v.iter().map(|&x| (x as f64 - m).powi(2)).sum::<f64>() / n).sqrt().max(1e-12);
    for x in v.iter_mut() {
        *x = mean + ((*x as f64 - m) / s) as f32 * sd;
    }
}

/// A pigment as water carries it. Optical constants are per µg/mm² of pigment; the physical ones
/// decide how it moves. Estimates, as `Paper`'s.
#[derive(Clone, Debug, PartialEq)]
pub struct AqPigment {
    pub name: String,
    /// Kubelka–Munk absorption per µg/mm², linear RGB channels.
    pub k: Rgb,
    /// Kubelka–Munk scattering per µg/mm² (flat across channels).
    pub s: f32,
    /// Particle density, g/cm³.
    pub density: f32,
    /// Particle diameter, µm.
    pub diameter_um: f32,
    /// Rate it adsorbs onto fibres from water in the sheet (staining), per second.
    pub stain: f32,
    /// Rate a deposit lifts back into still water standing on it, per second (a moving brush lifts
    /// it up to 200 times faster, `Water::agitate`).
    pub lift: f32,
}

impl AqPigment {
    /// A pigment whose thick layer (masstone) is `masstone` (linear RGB), tinting with absorption
    /// `strength` per µg/mm² (mean over the channels), `opacity` its scattering relative to that
    /// absorption (0 a dye, 1 an opaque body colour, more for more).
    pub fn new(name: &str, masstone: Rgb, strength: f32, opacity: f32, density: f32, diameter_um: f32, stain: f32, lift: f32) -> AqPigment {
        // K/S of each channel from the masstone (R∞ = 1 + K/S − √((K/S)² + 2K/S), inverted)
        let ks: Rgb = std::array::from_fn(|c| {
            let r = masstone[c].clamp(0.002, 0.995);
            (1.0 - r) * (1.0 - r) / (2.0 * r)
        });
        let mean_ks = (ks[0] + ks[1] + ks[2]) / 3.0;
        // flat S: chosen so that the mean K is `strength`; a dye scatters next to nothing
        // (opacity above 1 is a paint more opaque than its absorption alone gives: allowed)
        let s = (strength / mean_ks.max(1e-4)) * opacity.max(0.002);
        let k = std::array::from_fn(|c| ks[c] * strength / mean_ks.max(1e-4));
        AqPigment { name: name.into(), k, s, density, diameter_um, stain, lift }
    }
    /// Stokes settling velocity in water, µm/s.
    /// Brownian diffusivity in water at 20 °C, µm²/s (Stokes–Einstein: kT / 3π μ d).
    pub fn diffusivity_um2_s(&self) -> f32 {
        let d = self.diameter_um as f64 * 1e-6;
        (BOLTZMANN_T / (3.0 * std::f64::consts::PI * MU * d.max(1e-9)) * 1e12) as f32
    }
    pub fn settling_um_s(&self) -> f32 {
        let r = self.diameter_um as f64 * 0.5e-6;
        let dr = (self.density as f64 * 1000.0 - RHO_W).max(0.0);
        (2.0 / 9.0 * dr * 9.81 * r * r / MU * 1e6) as f32
    }
    /// Share of the particles the sheet's pores hold back at its surface as water sinks in.
    pub fn filter(&self) -> f32 {
        crate::smoothstep(0.05, 2.0, self.diameter_um)
    }
    /// Some watercolour pigments, by their common names. Estimates of each one's particle size,
    /// density and staining from the ranges reported for it; the colours are masstones.
    pub fn named(name: &str) -> Option<AqPigment> {
        Some(match name {
            // PB29: coarse, dense, lifts readily
            "ultramarine" => AqPigment::new("ultramarine", [0.035, 0.05, 0.42], 0.9, 0.03, 2.35, 2.0, 0.002, 0.005),
            // PB15:3: very fine, organic, staining
            "phthalo blue" => AqPigment::new("phthalo blue", [0.005, 0.03, 0.14], 3.0, 0.01, 1.6, 0.08, 0.15, 0.0004),
            // PBr7: an iron oxide, fine but dense
            "burnt sienna" => AqPigment::new("burnt sienna", [0.26, 0.07, 0.025], 1.0, 0.08, 3.6, 0.4, 0.01, 0.003),
            // PV19
            "quinacridone rose" => AqPigment::new("quinacridone rose", [0.5, 0.02, 0.12], 1.6, 0.02, 1.5, 0.1, 0.12, 0.0006),
            // PY35: dense, semi-opaque
            "cadmium yellow" => AqPigment::new("cadmium yellow", [0.78, 0.48, 0.02], 0.6, 0.35, 4.3, 0.8, 0.005, 0.003),
            // PG18: coarse
            "viridian" => AqPigment::new("viridian", [0.02, 0.22, 0.13], 0.7, 0.05, 3.3, 1.2, 0.004, 0.004),
            // PBk6 in animal glue, as sumi ink: very fine, fixed by its glue once dry
            "sumi" | "sumi ink" => AqPigment::new("sumi", [0.012, 0.012, 0.013], 4.0, 0.02, 1.8, 0.12, 0.08, 0.0001),
            _ => return None,
        })
    }
    pub fn names() -> &'static [&'static str] {
        &["ultramarine", "phthalo blue", "burnt sienna", "quinacridone rose", "cadmium yellow", "viridian", "sumi"]
    }
}

/// Every volume (mm³) and mass (µg) that came onto or left the sheet.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Ledger {
    pub water_added: f64,
    pub water_taken: f64,
    pub water_evaporated: f64,
    pub water_runoff: f64,
    /// Surface water that went into the sheet (an internal transfer, counted for inspection).
    pub water_absorbed: f64,
    pub pigment_added: Vec<f64>,
    pub pigment_taken: Vec<f64>,
    pub pigment_runoff: Vec<f64>,
    /// Pigment sealed into the dry picture under later paint (it left the water's layer).
    pub pigment_sealed: Vec<f64>,
}

/// Totals on and in the sheet now, mm³ and µg.
#[derive(Clone, Debug, Default)]
pub struct Totals {
    pub surface_water: f64,
    pub absorbed_water: f64,
    pub suspended: Vec<f64>,
    pub carried: Vec<f64>,
    pub deposited: Vec<f64>,
    pub glued: Vec<f64>,
    pub bound: Vec<f64>,
}

/// Water and pigment on and in an absorbent sheet (or a canvas), on the canvas's pixel grid.
#[derive(Clone, Debug)]
pub struct Water {
    pub w: usize,
    pub h: usize,
    /// Pixel size, mm.
    pub dx_mm: f32,
    pub paper: Paper,
    /// Paper relief, µm (zero mean): also written into the canvas's surface height.
    pub relief: Vec<f32>,
    /// Water capacity of the sheet per pixel, µm.
    pub cap: Vec<f32>,
    /// Fibre direction per pixel, radians.
    pub fdir: Vec<f32>,
    /// How readily water wicks through each pixel relative to the sheet's mean (its fibres), 1 on
    /// average.
    pub perm: Vec<f32>,
    /// How much of each pixel is sealed against water by paint over it, 0..1 (`seal`).
    pub sealed: Vec<f32>,
    pub sw: Vec<f32>,
    pub aw: Vec<f32>,
    /// The paint's wetting agents and gum on the surface, µg/mm² (`AGENT_PER_UG` for each µg of
    /// pigment laid): what lowers the surface tension and drives the Marangoni flow. It moves with the
    /// surface water, spreads itself, and stays when the water dries (to redissolve when rewetted).
    pub agent: Vec<f32>,
    /// Wetting agent adsorbed onto the fibres, µg/mm², up to `AGENT_FIBRE_UG_MM2`: paper wetted by
    /// paint has taken what it can, and water arriving there keeps its agents.
    pub fibre_agent: Vec<f32>,
    pub species: Vec<AqPigment>,
    /// Pigment by species, each a Vec over pixels (µg/mm²), allocated when the species arrives.
    pub ms: Vec<Vec<f32>>,
    pub ma: Vec<Vec<f32>>,
    pub md: Vec<Vec<f32>>,
    pub mg: Vec<Vec<f32>>,
    pub mb: Vec<Vec<f32>>,
    /// Sheet tilt from flat, degrees, and the direction it falls toward, degrees (90: down the canvas).
    pub tilt_deg: f32,
    pub tilt_dir_deg: f32,
    /// Evaporation of open water, µm/s (20 °C, 50 % RH, still air ≈ 0.15; an estimate).
    pub evap_um_s: f32,
    /// Pigment dispersion through standing water, mm²/s: an approximation for the look (see `step`),
    /// standing for flows the model does not resolve. 0 is the flow alone.
    pub dispersion_mm2_s: f32,
    /// How far paint in the water lowers its surface tension, N/m (`MARANGONI_N_M`); 0 turns the
    /// Marangoni flow off.
    pub marangoni_n_m: f32,
    /// Seconds simulated.
    pub clock_s: f64,
    /// The last step taken, s, and the largest change of saturation wicking made in it: the next
    /// step in the pores grows while that change stays small (part of the state, so a restored
    /// sheet steps as the original would).
    pub last_dt: f64,
    pub last_wick_change: f32,
    /// The largest change of surface water depth the flow made in the last step, µm.
    pub last_flow_change: f32,
    /// The fastest Marangoni surface speed of the last step, mm/s (steps shorten while it is high).
    pub last_marangoni_mm_s: f32,
    /// Steps taken so far (for diagnostics; not saved).
    pub steps_taken: u64,
    pub ledger: Ledger,
}

impl Water {
    /// Water on `paper` over a `w` × `h` grid of `dx_mm` pixels.
    pub fn new(paper: Paper, w: usize, h: usize, dx_mm: f32, seed: u64) -> Water {
        let (relief, cap, fdir, perm) = paper.form(w, h, dx_mm, seed);
        let n = w * h;
        Water {
            w, h, dx_mm, paper, relief, cap, fdir, perm, sealed: vec![0.0; n], sw: vec![0.0; n], aw: vec![0.0; n], agent: vec![0.0; n], fibre_agent: vec![0.0; n],
            species: vec![], ms: vec![], ma: vec![], md: vec![], mg: vec![], mb: vec![],
            tilt_deg: 0.0, tilt_dir_deg: 90.0, evap_um_s: 0.15, dispersion_mm2_s: DISPERSION_MM2_S, marangoni_n_m: MARANGONI_N_M, clock_s: 0.0, last_dt: 0.0, last_wick_change: 0.0, last_flow_change: 0.0, last_marangoni_mm_s: 0.0, steps_taken: 0, ledger: Ledger::default(),
        }
    }

    /// mm² of one pixel.
    pub fn px_area(&self) -> f64 {
        (self.dx_mm as f64) * (self.dx_mm as f64)
    }

    /// The index of `p` among the sheet's species, adding it (and its pixels) on first use.
    pub fn species_index(&mut self, p: &AqPigment) -> Result<usize, String> {
        if let Some(i) = self.species.iter().position(|q| q == p) {
            return Ok(i);
        }
        if self.species.len() >= MAX_SPECIES {
            return Err(format!("a sheet holds at most {MAX_SPECIES} pigments"));
        }
        let n = self.w * self.h;
        self.species.push(p.clone());
        for v in [&mut self.ms, &mut self.ma, &mut self.md, &mut self.mg, &mut self.mb] {
            v.push(vec![0.0; n]);
        }
        for v in [&mut self.ledger.pigment_added, &mut self.ledger.pigment_taken, &mut self.ledger.pigment_runoff, &mut self.ledger.pigment_sealed] {
            v.push(0.0);
        }
        Ok(self.species.len() - 1)
    }

    /// Whether any water or pigment is on or in the sheet.
    pub fn is_empty(&self) -> bool {
        self.sw.iter().all(|&v| v <= 0.0) && self.aw.iter().all(|&v| v <= 0.0) && !self.has_pigment()
    }

    pub fn has_pigment(&self) -> bool {
        [&self.ms, &self.ma, &self.md, &self.mg, &self.mb].iter().any(|s| s.iter().any(|v| v.iter().any(|&m| m > 0.0)))
    }

    /// Pixels with water on or in them, as a bounding box `(x0, y0, x1, y1)` (end-exclusive) grown by
    /// `pad`; None when the sheet is dry.
    fn wet_bounds(&self, pad: usize) -> Option<(usize, usize, usize, usize)> {
        let (w, h) = (self.w, self.h);
        let rows: Vec<(usize, usize)> = (0..h)
            .into_par_iter()
            .filter_map(|y| {
                let row = y * w;
                let first = (0..w).find(|&x| self.sw[row + x] > 0.0 || self.aw[row + x] > 0.0)?;
                let last = (0..w).rev().find(|&x| self.sw[row + x] > 0.0 || self.aw[row + x] > 0.0)?;
                Some((y, first * w + last))
            })
            .collect();
        if rows.is_empty() {
            return None;
        }
        let (y0, y1) = (rows.first().unwrap().0, rows.last().unwrap().0);
        let x0 = rows.iter().map(|r| r.1 / w).min().unwrap();
        let x1 = rows.iter().map(|r| r.1 % w).max().unwrap();
        Some((x0.saturating_sub(pad), y0.saturating_sub(pad), (x1 + 1 + pad).min(w), (y1 + 1 + pad).min(h)))
    }

    /// Lay `um` µm of water carrying `masses[k]` µg/mm² of species `species[k]` on pixel `i`, as a
    /// tool does. Counted in the ledger as added.
    pub fn add(&mut self, i: usize, um: f32, masses: &[(usize, f32)]) {
        if um <= 0.0 && masses.iter().all(|m| m.1 <= 0.0) {
            return;
        }
        let a = self.px_area();
        self.sw[i] += um.max(0.0);
        self.ledger.water_added += um.max(0.0) as f64 * a * 1e-3;
        for &(k, m) in masses {
            if m > 0.0 {
                self.ms[k][i] += m;
                self.agent[i] += AGENT_PER_UG * m;
                self.ledger.pigment_added[k] += m as f64 * a;
            }
        }
    }

    /// Take `share` (0..1) of pixel `i`'s surface water, with its suspended pigment, as a thirsty
    /// tool does: returns µm of water and µg/mm² of each species taken. Counted as taken.
    pub fn take(&mut self, i: usize, share: f32) -> (f32, Vec<f32>) {
        let s = share.clamp(0.0, 1.0);
        let a = self.px_area();
        let um = self.sw[i] * s;
        self.sw[i] -= um;
        self.agent[i] -= self.agent[i] * s;
        self.ledger.water_taken += um as f64 * a * 1e-3;
        let ms: Vec<f32> = (0..self.species.len())
            .map(|k| {
                let m = self.ms[k][i] * s;
                self.ms[k][i] -= m;
                self.ledger.pigment_taken[k] += m as f64 * a;
                m
            })
            .collect();
        (um, ms)
    }

    /// A moving brush working pixel `i` for `secs`: deposits lift into the water there faster
    /// (`agitate`, per second, times each pigment's own readiness to lift).
    pub fn agitate(&mut self, i: usize, rate: f32, secs: f32) {
        if self.sw[i] <= W_DRY {
            return;
        }
        for k in 0..self.species.len() {
            // sediment comes up at once under the hair; a glued deposit as readily as its gum lets it
            let wet = 1.0 - (-(rate * secs)).exp();
            let glued = 1.0 - (-(rate * secs * (self.species[k].lift * 200.0).min(1.0))).exp();
            let (a, b) = (self.md[k][i] * wet, self.mg[k][i] * glued);
            self.md[k][i] -= a;
            self.mg[k][i] -= b;
            self.ms[k][i] += a + b;
        }
    }

    /// Let `secs` pass: flow, absorption, wicking, evaporation, settling, staining, lifting.
    pub fn advance(&mut self, secs: f64) {
        let mut left = secs.max(0.0);
        let mut bounds = None;
        let mut steps = 0usize;
        while left > 1e-9 {
            if steps % BOUNDS_EVERY == 0 {
                bounds = self.wet_bounds(2 + BOUNDS_EVERY);
            }
            let Some(b) = bounds else {
                self.clock_s += left;
                return;
            };
            let dt = self.stable_dt(b).min(left);
            self.step(b, dt as f32);
            self.last_dt = dt;
            self.steps_taken += 1;
            self.clock_s += dt;
            left -= dt;
            steps += 1;
        }
    }

    /// Evaporate everything: dry the sheet, settling every pigment where its water left it.
    pub fn dry_out(&mut self) {
        // as `advance`, but with nothing left to wait for once the surface is gone
        for _ in 0..200_000 {
            if self.wet_bounds(0).is_none() {
                return;
            }
            self.advance(5.0);
        }
    }

    /// A step that keeps the explicit wicking stable and lets the flux limiter do the rest.
    fn stable_dt(&self, (x0, y0, x1, y1): (usize, usize, usize, usize)) -> f64 {
        let (mut w_max, mut s_max) = (0.0f32, 0.0f32);
        for y in y0..y1 {
            for x in x0..x1 {
                let i = y * self.w + x;
                w_max = w_max.max(self.sw[i]);
                s_max = s_max.max(self.aw[i] / self.cap[i].max(1e-3));
            }
        }
        let dx2 = (self.dx_mm * self.dx_mm) as f64;
        // the diffusivity is wick · s², so a drying sheet allows longer steps
        let s2 = (s_max.min(1.0) as f64).powi(2).max(1e-4);
        let wick = self.paper.wick_mm2_s as f64 * self.paper.anisotropy.max(1.0) as f64 * s2;
        // in the pores alone nothing moves fast: evaporation and staining are exact at any step
        let mut dt = if w_max > 0.0 { MAX_FLOW_DT } else { MAX_PORE_DT };
        if wick > 0.0 {
            let explicit = 0.2 * dx2 / wick;
            // in the pores only, a step grows while wicking changes little in it: the flux limiter
            // keeps any step stable, and changes of at most WICK_CHANGE keep it accurate
            let adapt = if w_max <= 0.0 && self.last_dt > 0.0 {
                (self.last_dt * (WICK_CHANGE as f64 / (self.last_wick_change as f64).max(1e-6)).clamp(0.5, 2.0)).max(explicit)
            } else {
                explicit
            };
            dt = dt.min(adapt);
        }
        if w_max > 0.0 {
            // the film's own diffusivity, ρ g w³ / 3μ, in mm²/s: a step it keeps exact, but not below
            // MIN_FLOW_DT, where the flux limiter holds thick water back instead (it stays stable at
            // any step: what thick water loses is speed, not sense)
            let d = GRAVITY_FILM * (w_max.min(300.0) as f64 * 1e-6).powi(3) * 1e6;
            let exact = (0.22 * dx2 / d.max(1e-12)).max(MIN_FLOW_DT);
            // water at rest (a puddle sinking in) changes little a step: let the step grow while the
            // largest change of depth in one stays under FLOW_CHANGE_UM, as the limiter keeps it stable
            let adapt = if self.last_dt > 0.0 && self.last_flow_change >= 0.0 {
                (self.last_dt * (FLOW_CHANGE_UM as f64 / (self.last_flow_change as f64).max(1e-6)).clamp(0.5, 2.0)).max(exact)
            } else {
                exact
            };
            dt = dt.min(adapt).min(MAX_FLOW_DT);
            // absorption: a step no longer than the paper takes to drink a few µm
            dt = dt.min((5.0 / (self.paper.absorb_um_s as f64).max(1e-6)).max(MIN_FLOW_DT));
        }
        if self.last_marangoni_mm_s > 0.05 && w_max > 0.0 {
            // a bloom is spreading: steps of at most a few passes
            dt = dt.min((4.0 * 0.25 * self.dx_mm as f64 / self.last_marangoni_mm_s as f64).max(MIN_FLOW_DT * 0.25));
        }
        dt.clamp(1e-4, MAX_PORE_DT)
    }

    fn step(&mut self, (x0, y0, x1, y1): (usize, usize, usize, usize), dt: f32) {
        let tq = std::time::Instant::now();
        let (w, h) = (self.w, self.h);
        let dx = self.dx_mm;
        let nk = self.species.len();
        let area = self.px_area();
        // ---- surface flow --------------------------------------------------------------------
        let cos_t = self.tilt_deg.to_radians().cos();
        let sin_t = self.tilt_deg.to_radians().sin();
        let (dirx, diry) = (self.tilt_dir_deg.to_radians().cos(), self.tilt_dir_deg.to_radians().sin());
        // µm the sheet drops per pixel along x and y
        let (slx, sly) = (sin_t * dirx * dx * 1000.0, sin_t * diry * dx * 1000.0);
        // coefficient: Δw (µm) = c · d³ (µm³) · Δη (µm), c = G dt / dx² × 1e-12 (dx in mm)
        let c = (GRAVITY_FILM * dt as f64 / (dx as f64 * dx as f64) * 1e-12) as f32;
        let pin = self.paper.pin_um;
        let bw = x1 - x0;
        let bh = y1 - y0;
        let n = bw * bh;
        // box cell j is pixel at(j); its right neighbour j + 1, the one below j + bw
        let at = |j: usize| (y0 + j / bw) * w + x0 + j % bw;
        let (sw, ms) = (&self.sw, &self.ms);
        let eta: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
            let i = at(j);
            cos_t * (self.relief[i] + sw[i]) - slx * (i % w) as f32 - sly * (i / w) as f32
        }).collect();
        // the volume (µm over a pixel) that would cross the edge from cell ja to jb: gravity and the
        // relief pull it downhill
        let flux = |ja: usize, jb: usize| -> f32 {
            let (a, b) = (at(ja), at(jb));
            let d_eta = eta[ja] - eta[jb];
            let g_src = if d_eta > 0.0 { a } else { b };
            // never more than a quarter of the head difference
            let q = if sw[g_src] > W_DRY { (c * sw[g_src].min(300.0).powi(3) * d_eta.abs()).min(d_eta.abs() * 0.25) * d_eta.signum() } else { 0.0 };
            if q == 0.0 {
                return 0.0;
            }
            let (src, dst) = if q > 0.0 { (a, b) } else { (b, a) };
            let d = sw[src];
            if d <= W_DRY {
                return 0.0;
            }
            if sw[dst] <= W_DRY {
                // advancing onto dry paper: only from a film deep enough to wet it
                let damp = (self.aw[dst] / self.cap[dst].max(1e-3)).clamp(0.0, 1.0);
                let need = pin * (1.0 - 0.8 * damp) * (1.0 + 2.0 * self.sealed[dst]);
                if d < need {
                    return 0.0;
                }
            }
            // never more than a quarter of the water the source has (a steep tilt, a strong gradient)
            q.clamp(-0.25 * d, 0.25 * d)
        };
        // fx[j]: from cell j to j + 1; fy[j]: from j to j + bw (0 at the box's last column and row)
        let fx: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j % bw + 1 < bw { flux(j, j + 1) } else { 0.0 }).collect();
        let fy: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j / bw + 1 < bh { flux(j, j + bw) } else { 0.0 }).collect();
        // no cell gives more than it has: scale each source's outflows to fit
        let scale: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
            let mut out = fx[j].max(0.0) + fy[j].max(0.0);
            if j % bw > 0 { out += (-fx[j - 1]).max(0.0); }
            if j / bw > 0 { out += (-fy[j - bw]).max(0.0); }
            let have = sw[at(j)];
            if out > have && out > 0.0 { have / out } else { 1.0 }
        }).collect();
        let lim = |q: f32, j: usize, jn: usize| -> f32 { if q > 0.0 { q * scale[j] } else { q * scale[jn] } };
        let lx: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j % bw + 1 < bw { lim(fx[j], j, j + 1) } else { 0.0 }).collect();
        let ly: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j / bw + 1 < bh { lim(fy[j], j, j + bw) } else { 0.0 }).collect();
        // µm/s of water through each cell: half of all that crossed its edges
        let through: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
            let mut t = lx[j].abs() + ly[j].abs();
            if j % bw > 0 { t += lx[j - 1].abs(); }
            if j / bw > 0 { t += ly[j - bw].abs(); }
            0.5 * t / dt.max(1e-6)
        }).collect();
        // each cell gathers what crossed its four edges, with the pigment at the source's concentration
        let conc = |i: usize, k: usize| -> f32 { if sw[i] > 0.0 { ms[k][i] / sw[i] } else { 0.0 } };
        // (an edge with no flow has no source: at the box's last column and row its far side is outside)
        let carried = |q: f32, j: usize, jn: usize, k: usize| -> f32 { if q == 0.0 { 0.0 } else { q * conc(at(if q > 0.0 { j } else { jn }), k) } };
        let gathered: Vec<(f32, [f32; MAX_SPECIES])> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
            let mut dw = -lx[j] - ly[j];
            let mut dm = [0.0f32; MAX_SPECIES];
            for k in 0..nk {
                dm[k] -= carried(lx[j], j, j + 1, k) + carried(ly[j], j, j + bw, k);
            }
            if j % bw > 0 {
                dw += lx[j - 1];
                for k in 0..nk { dm[k] += carried(lx[j - 1], j - 1, j, k); }
            }
            if j / bw > 0 {
                dw += ly[j - bw];
                for k in 0..nk { dm[k] += carried(ly[j - bw], j - bw, j, k); }
            }
            (dw, dm)
        }).collect();
        self.last_flow_change = gathered.par_iter().map(|g| g.0.abs()).reduce(|| 0.0f32, f32::max);
        // the wetting agent travels with the water too
        let agent = &self.agent;
        let carried_agent = |q: f32, j: usize, jn: usize| -> f32 {
            if q == 0.0 { return 0.0; }
            let src = at(if q > 0.0 { j } else { jn });
            if sw[src] > 0.0 { q * agent[src] / sw[src] } else { 0.0 }
        };
        let dagent: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
            let mut d = -carried_agent(lx[j], j, j + 1) - carried_agent(ly[j], j, j + bw);
            if j % bw > 0 { d += carried_agent(lx[j - 1], j - 1, j); }
            if j / bw > 0 { d += carried_agent(ly[j - bw], j - bw, j); }
            d
        }).collect();
        for (j, (dw, dm)) in gathered.iter().enumerate() {
            let i = at(j);
            self.agent[i] = (self.agent[i] + dagent[j]).max(0.0);
            self.sw[i] = (self.sw[i] + dw).max(0.0);
            for k in 0..nk {
                self.ms[k][i] = (self.ms[k][i] + dm[k]).max(0.0);
            }
        }
        prof(0, tq);
        let tq = std::time::Instant::now();
        // Marangoni flow: where the wetting agent lowers the surface tension unevenly, the surface
        // moves toward the higher tension at h / (2 μ) · ∇σ (capped at MARANGONI_MAX_MM_S), carrying
        // the agent and the suspended pigment with it. The surface's own motion is not followed into
        // the water's depth: the water stays, its solutes move. In passes short enough that no edge
        // moves more than a quarter of a pixel's contents.
        self.last_marangoni_mm_s = 0.0;
        if self.marangoni_n_m > 0.0 {
            let dsig = self.marangoni_n_m;
            let sigma_of = |agent_ug_mm2: f32, sw_um: f32| -> f32 {
                let c = (agent_ug_mm2 * 1000.0 / sw_um.max(1e-3)).min(AGENT_CMC_UG_MM3);
                -dsig * (1.0 + c / SZYSZKOWSKI_A_UG_MM3).ln() / (1.0 + AGENT_CMC_UG_MM3 / SZYSZKOWSKI_A_UG_MM3).ln()
            };
            let edge_speed = |sa: f32, sb: f32, h: f32| -> f32 {
                // mm/s: (h m) / (2 μ) × (Δσ / dx m), toward the higher tension
                let u = (h * 1e-6) / (2.0 * MU as f32) * (sb - sa) / (dx * 1e-3) * 1000.0;
                u.clamp(-MARANGONI_MAX_MM_S, MARANGONI_MAX_MM_S)
            };
            for pass in 0..64 {
                let (sw, ms, agent) = (&self.sw, &self.ms, &self.agent);
                let sig: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
                    let i = at(j);
                    if sw[i] > W_DRY { sigma_of(agent[i], sw[i]) } else { 0.0 }
                }).collect();
                // mm/s across each edge, + toward j + 1 (or j + bw)
                let edge = |ja: usize, jb: usize| -> f32 {
                    let (a, b) = (at(ja), at(jb));
                    if sw[a] <= W_DRY || sw[b] <= W_DRY || sig[ja] == sig[jb] { 0.0 } else { edge_speed(sig[ja], sig[jb], sw[a].min(sw[b])) }
                };
                let ux: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j % bw + 1 < bw { edge(j, j + 1) } else { 0.0 }).collect();
                let uy: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j / bw + 1 < bh { edge(j, j + bw) } else { 0.0 }).collect();
                let u_max = ux.par_iter().chain(uy.par_iter()).map(|u| u.abs()).reduce(|| 0.0f32, f32::max);
                if pass == 0 {
                    self.last_marangoni_mm_s = u_max;
                }
                if u_max < 1e-3 {
                    break;
                }
                // this pass's share of the step: no edge moves more than a quarter of a pixel
                let sub = (0.25 * dx / u_max).min(dt);
                // the share of its source's surface layer that crosses the edge from cell ja to jb at
                // u (+ toward jb): never more than a quarter of what evens the agent out between them
                let frac = |u: f32, ja: usize, jb: usize| -> f32 {
                    let (a, b) = (at(ja), at(jb));
                    let src = if u > 0.0 { a } else { b };
                    if agent[src] <= 0.0 {
                        return 0.0;
                    }
                    let (ca, cb) = (agent[a] / sw[a], agent[b] / sw[b]);
                    let even = 0.25 * (ca - cb).abs() * sw[a].min(sw[b]) / agent[src];
                    (u.abs() * sub / dx).min(0.25).min(even)
                };
                let fx: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if ux[j] != 0.0 { frac(ux[j], j, j + 1) * ux[j].signum() } else { 0.0 }).collect();
                let fy: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if uy[j] != 0.0 { frac(uy[j], j, j + bw) * uy[j].signum() } else { 0.0 }).collect();
                // each cell gathers the solutes that crossed its edges
                let moved: Vec<(f32, [f32; MAX_SPECIES])> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
                    let i = at(j);
                    let mut da = 0.0f32;
                    let mut dm = [0.0f32; MAX_SPECIES];
                    let mut cross = |f: f32, src: usize, sign: f32| {
                        da += sign * f * agent[src];
                        for k in 0..nk { dm[k] += sign * f * MARANGONI_PIGMENT_SHARE * ms[k][src]; }
                    };
                    // out across j's own edges, in across its neighbours'
                    for (f, jn) in [(fx[j], j + 1), (fy[j], j + bw)] {
                        if f > 0.0 { cross(f, i, -1.0); } else if f < 0.0 { cross(-f, at(jn), 1.0); }
                    }
                    if j % bw > 0 {
                        let f = fx[j - 1];
                        if f > 0.0 { cross(f, at(j - 1), 1.0); } else if f < 0.0 { cross(-f, i, -1.0); }
                    }
                    if j / bw > 0 {
                        let f = fy[j - bw];
                        if f > 0.0 { cross(f, at(j - bw), 1.0); } else if f < 0.0 { cross(-f, i, -1.0); }
                    }
                    (da, dm)
                }).collect();
                for (j, (da, dm)) in moved.iter().enumerate() {
                    let i = at(j);
                    self.agent[i] = (self.agent[i] + da).max(0.0);
                    for k in 0..nk {
                        self.ms[k][i] = (self.ms[k][i] + dm[k]).max(0.0);
                    }
                }
                if sub >= dt {
                    break;
                }
                // (the passes stand for the whole step only once their time adds up to it)
                if (pass + 1) as f32 * sub >= dt {
                    break;
                }
            }
        }
        // the agents adsorb onto the fibres they wet, out of the surface, while the fibres have room:
        // a bloom's drive lasts seconds, and paper the paint has wetted keeps no more
        let take = 1.0 - (-AGENT_ADSORB_S * dt).exp();
        for j in 0..n {
            let i = at(j);
            if self.sw[i] > W_DRY && self.agent[i] > 0.0 {
                let room = (AGENT_FIBRE_UG_MM2 - self.fibre_agent[i]).max(0.0);
                let a = (self.agent[i] * take * room / AGENT_FIBRE_UG_MM2).min(room);
                self.agent[i] -= a;
                self.fibre_agent[i] += a;
            }
        }
        // dispersion, the dial: a spreading of pigment through standing water for mixing the model
        // does not resolve (0 by default)
        if self.dispersion_mm2_s > 0.0 && nk > 0 {
            let kd = self.dispersion_mm2_s * dt / (dx * dx);
            let (sw, ms) = (&self.sw, &self.ms);
            let mflux = |ja: usize, jb: usize, k: usize| -> f32 {
                let (a, b) = (at(ja), at(jb));
                let (da, db) = (sw[a], sw[b]);
                if da <= W_DRY || db <= W_DRY {
                    return 0.0;
                }
                let (ca, cb) = (ms[k][a] / da, ms[k][b] / db);
                let depth = da.min(db);
                (kd * (ca - cb) * depth).clamp(-0.25 * (ca - cb).abs() * depth, 0.25 * (ca - cb).abs() * depth)
            };
            let moved: Vec<[f32; MAX_SPECIES]> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
                let mut dm = [0.0f32; MAX_SPECIES];
                for k in 0..nk {
                    if j % bw + 1 < bw { dm[k] -= mflux(j, j + 1, k); }
                    if j / bw + 1 < bh { dm[k] -= mflux(j, j + bw, k); }
                    if j % bw > 0 { dm[k] += mflux(j - 1, j, k); }
                    if j / bw > 0 { dm[k] += mflux(j - bw, j, k); }
                }
                dm
            }).collect();
            for (j, dm) in moved.iter().enumerate() {
                let i = at(j);
                for k in 0..nk {
                    self.ms[k][i] = (self.ms[k][i] + dm[k]).max(0.0);
                }
            }
        }
        // a tilted sheet sheds the water that runs to its downhill edge
        if self.tilt_deg > 0.5 {
            let mut runoff = 0.0f64;
            let mut runoff_k = vec![0.0f64; nk];
            let shed = (c * sin_t * dx * 1000.0).min(0.25);
            let mut edge = Vec::new();
            if dirx > 0.2 && x1 == w { edge.extend((y0..y1).map(|y| y * w + w - 1)); }
            if dirx < -0.2 && x0 == 0 { edge.extend((y0..y1).map(|y| y * w)); }
            if diry > 0.2 && y1 == h { edge.extend((x0..x1).map(|x| (h - 1) * w + x)); }
            if diry < -0.2 && y0 == 0 { edge.extend(x0..x1); }
            for i in edge {
                let d = self.sw[i];
                if d <= pin {
                    continue;
                }
                let q = ((d - pin) * (shed * d.min(300.0).powi(2)).min(0.25)).max(0.0);
                for k in 0..nk {
                    let m = q * self.ms[k][i] / d;
                    self.ms[k][i] -= m;
                    runoff_k[k] += m as f64 * area;
                }
                self.sw[i] -= q;
                runoff += q as f64 * area * 1e-3;
            }
            self.ledger.water_runoff += runoff;
            for k in 0..nk {
                self.ledger.pigment_runoff[k] += runoff_k[k];
            }
        }
        prof(1, tq);
        let tq = std::time::Instant::now();
        // ---- wicking within the sheet ----------------------------------------------------------
        let wick = self.paper.wick_mm2_s * dt / (dx * dx);
        let an = self.paper.anisotropy;
        if wick > 0.0 {
            let (aw, ma) = (&self.aw, &self.ma);
            let wflux = |ja: usize, jb: usize, along_x: bool| -> f32 {
                let (a, b) = (at(ja), at(jb));
                let (sa, sb) = (aw[a] / self.cap[a].max(1e-3), aw[b] / self.cap[b].max(1e-3));
                let ds = sa - sb;
                if ds.abs() < 1e-7 {
                    return 0.0;
                }
                let s_up = sa.max(sb).min(1.0);
                let ang = 0.5 * (self.fdir[a] + self.fdir[b]);
                let (ca, sa2) = (ang.cos().powi(2), ang.sin().powi(2));
                let dir = if along_x { an * ca + sa2 } else { an * sa2 + ca };
                let dir = dir * (self.perm[a] * self.perm[b]).sqrt();
                let capm = 0.5 * (self.cap[a] + self.cap[b]);
                // µm moved: never more than a quarter of what evens the two out
                (wick * dir * s_up * s_up * ds * capm).clamp(-ds.abs() * capm * 0.25, ds.abs() * capm * 0.25)
            };
            let gx: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j % bw + 1 < bw { wflux(j, j + 1, true) } else { 0.0 }).collect();
            let gy: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if j / bw + 1 < bh { wflux(j, j + bw, false) } else { 0.0 }).collect();
            self.last_wick_change = gx.par_iter().chain(gy.par_iter()).map(|q| q.abs()).reduce(|| 0.0f32, f32::max) / self.paper.cap_um.max(1e-3);
            let cconc = |i: usize, k: usize| -> f32 { if aw[i] > 0.0 { ma[k][i] / aw[i] } else { 0.0 } };
            let carried = |q: f32, j: usize, jn: usize, k: usize| -> f32 { if q == 0.0 { 0.0 } else { q * cconc(at(if q > 0.0 { j } else { jn }), k) } };
            let gathered: Vec<(f32, [f32; MAX_SPECIES])> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
                let mut dw = -gx[j] - gy[j];
                let mut dm = [0.0f32; MAX_SPECIES];
                for k in 0..nk {
                    dm[k] -= carried(gx[j], j, j + 1, k) + carried(gy[j], j, j + bw, k);
                }
                if j % bw > 0 {
                    dw += gx[j - 1];
                    for k in 0..nk { dm[k] += carried(gx[j - 1], j - 1, j, k); }
                }
                if j / bw > 0 {
                    dw += gy[j - bw];
                    for k in 0..nk { dm[k] += carried(gy[j - bw], j - bw, j, k); }
                }
                (dw, dm)
            }).collect();
            for (j, (dw, dm)) in gathered.iter().enumerate() {
                let i = at(j);
                self.aw[i] = (self.aw[i] + dw).max(0.0);
                for k in 0..nk {
                    self.ma[k][i] = (self.ma[k][i] + dm[k]).max(0.0);
                }
            }
        }
        prof(2, tq);
        let tq = std::time::Instant::now();
        // ---- per pixel: absorption, evaporation, settling, staining, lifting --------------------
        let settle: Vec<f32> = self.species.iter().map(|p| p.settling_um_s()).collect();
        let diffuse: Vec<f32> = self.species.iter().map(|p| p.diffusivity_um2_s()).collect();
        let filter: Vec<f32> = self.species.iter().map(|p| p.filter()).collect();
        let stain: Vec<f32> = self.species.iter().map(|p| p.stain).collect();
        let lift: Vec<f32> = self.species.iter().map(|p| p.lift).collect();
        let absorb = self.paper.absorb_um_s;
        let evap = self.evap_um_s;
        // the rim: share of each pixel's 8 neighbours that are dry
        // the rim: the share of the sheet within VAPOUR_MM that is dry (a separable box count)
        let rr = ((VAPOUR_MM / dx).round() as usize).max(1);
        let dry: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| if self.sw[at(j)] <= W_DRY { 1.0 } else { 0.0 }).collect();
        let rows: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
            let (cx, cy) = (j % bw, j / bw);
            let (a, b) = (cx.saturating_sub(rr), (cx + rr).min(bw - 1));
            (a..=b).map(|x| dry[cy * bw + x]).sum::<f32>() / (b - a + 1) as f32
        }).collect();
        let rim: Vec<f32> = (0..n).into_par_iter().with_min_len(CHUNK).map(|j| {
            if dry[j] > 0.0 {
                return 0.0;
            }
            let (cx, cy) = (j % bw, j / bw);
            let (a, b) = (cy.saturating_sub(rr), (cy + rr).min(bh - 1));
            (a..=b).map(|y| rows[y * bw + cx]).sum::<f32>() / (b - a + 1) as f32
        }).collect();
        // each pixel's own physics touches only that pixel: rows in parallel, each pixel's state
        // copied out through raw pointers, worked by `pixel`, and copied back
        struct Cells { sw: *mut f32, aw: *mut f32, m: [Vec<*mut f32>; 5] }
        // SAFETY: only shared between the row tasks below, each of which touches its own pixels
        unsafe impl Sync for Cells {}
        let cells = Cells {
            sw: self.sw.as_mut_ptr(), aw: self.aw.as_mut_ptr(),
            m: [&mut self.ms, &mut self.ma, &mut self.md, &mut self.mb, &mut self.mg].map(|s| s.iter_mut().map(|v| v.as_mut_ptr()).collect()),
        };
        let k = Constants { nk, dt, absorb, evap, settle: &settle, diffuse: &diffuse, filter: &filter, stain: &stain, lift: &lift };
        let (cap_v, sealed_v) = (&self.cap, &self.sealed);
        let (absorbed, evaporated) = (0..bh).into_par_iter().with_min_len((CHUNK / bw.max(1)).max(1)).map(|cy| {
            let mut sums = (0.0f64, 0.0f64);
            let c = &cells;
            for cx in 0..bw {
                let i = (y0 + cy) * w + x0 + cx;
                // SAFETY: pixel i lies in row y0 + cy of the box, which no other task touches
                unsafe {
                    let mut px = Px { sw: *c.sw.add(i), aw: *c.aw.add(i), m: [[0.0; MAX_SPECIES]; 5] };
                    for (state, ptrs) in px.m.iter_mut().zip(&c.m) {
                        for q in 0..nk { state[q] = *ptrs[q].add(i); }
                    }
                    let (a, e) = pixel(&mut px, cap_v[i] * (1.0 - sealed_v[i]), cap_v[i], rim[cy * bw + cx], through[cy * bw + cx], &k);
                    *c.sw.add(i) = px.sw;
                    *c.aw.add(i) = px.aw;
                    for (state, ptrs) in px.m.iter().zip(&c.m) {
                        for q in 0..nk { *ptrs[q].add(i) = state[q]; }
                    }
                    sums.0 += a;
                    sums.1 += e;
                }
            }
            sums
        }).reduce(|| (0.0, 0.0), |a, b| (a.0 + b.0, a.1 + b.1));
        self.ledger.water_absorbed += absorbed * area * 1e-3;
        self.ledger.water_evaporated += evaporated * area * 1e-3;
        prof(3, tq);
    }

    /// What is on and in the sheet now.
    pub fn totals(&self) -> Totals {
        let a = self.px_area();
        let sum = |v: &[f32]| v.par_iter().map(|&x| x as f64).sum::<f64>() * a;
        Totals {
            surface_water: sum(&self.sw) * 1e-3,
            absorbed_water: sum(&self.aw) * 1e-3,
            suspended: self.ms.iter().map(|v| sum(v)).collect(),
            carried: self.ma.iter().map(|v| sum(v)).collect(),
            deposited: self.md.iter().map(|v| sum(v)).collect(),
            glued: self.mg.iter().map(|v| sum(v)).collect(),
            bound: self.mb.iter().map(|v| sum(v)).collect(),
        }
    }

    /// How far the books are from balancing: water (mm³), and the worst species (µg), each as
    /// `(on the sheet, added less what left)`.
    pub fn check(&self) -> ((f64, f64), (f64, f64)) {
        let t = self.totals();
        let l = &self.ledger;
        let water = (t.surface_water + t.absorbed_water, l.water_added - l.water_taken - l.water_evaporated - l.water_runoff);
        let mut worst = (0.0, 0.0);
        for k in 0..self.species.len() {
            let on = t.suspended[k] + t.carried[k] + t.deposited[k] + t.glued[k] + t.bound[k];
            let books = l.pigment_added[k] - l.pigment_taken[k] - l.pigment_runoff[k] - l.pigment_sealed[k];
            if (on - books).abs() >= (worst.0 - worst.1 as f64).abs() {
                worst = (on, books);
            }
        }
        (water, worst)
    }

    /// Kubelka–Munk optical thickness of all the pigment at pixel `i`: `(K per channel, S)`.
    fn optics(&self, i: usize) -> (Rgb, f32) {
        let mut k = [0.0f32; 3];
        let mut s = 0.0f32;
        for (q, p) in self.species.iter().enumerate() {
            let m = self.ms[q][i] + self.ma[q][i] + self.md[q][i] + self.mg[q][i] + self.mb[q][i];
            if m <= 0.0 {
                continue;
            }
            for c in 0..3 {
                k[c] += p.k[c] * m;
            }
            s += p.s * m;
        }
        (k, s)
    }

    /// The pigment layer over `under` (linear RGB per pixel), as Kubelka–Munk layers. `wet` also
    /// darkens what is seen through wet paper (an optical approximation).
    pub fn over(&self, under: &[Rgb], wet: bool) -> Vec<Rgb> {
        (0..under.len())
            .into_par_iter()
            .map(|i| {
                let (k, s) = self.optics(i);
                let mut g = under[i];
                if wet {
                    let wetness = ((self.aw[i] / self.cap[i].max(1e-3)).min(1.0) + (self.sw[i] / 50.0).min(1.0)).min(1.0);
                    let f = 1.0 - WET_DARKEN * wetness;
                    g = [g[0] * f, g[1] * f, g[2] * f];
                }
                std::array::from_fn(|c| km_layer(k[c], s, g[c]))
            })
            .collect()
    }

    /// Mark pixels sealed by paint laid over the sheet (1 where `covered`), and take the pigment
    /// there out of the water's layer: the caller lays it into the picture under the paint.
    /// Returns the pixels' pigment optics, `(i, K, S)`, for the caller to bake.
    pub fn seal(&mut self, covered: &[bool]) -> Vec<(usize, Rgb, f32)> {
        let a = self.px_area();
        let mut out = Vec::new();
        for i in 0..covered.len() {
            if !covered[i] {
                continue;
            }
            self.sealed[i] = 1.0;
            let (k, s) = self.optics(i);
            if k.iter().all(|&v| v <= 0.0) {
                continue;
            }
            out.push((i, k, s));
            for q in 0..self.species.len() {
                let m = self.ms[q][i] + self.ma[q][i] + self.md[q][i] + self.mg[q][i] + self.mb[q][i];
                self.ledger.pigment_sealed[q] += m as f64 * a;
                self.ms[q][i] = 0.0;
                self.ma[q][i] = 0.0;
                self.md[q][i] = 0.0;
                self.mg[q][i] = 0.0;
                self.mb[q][i] = 0.0;
            }
        }
        out
    }
}

/// One pixel's water and pigment (`m`: suspended, carried, deposited, bound, glued, by species).
struct Px {
    sw: f32,
    aw: f32,
    m: [[f32; MAX_SPECIES]; 5],
}

/// What every pixel of a step shares.
struct Constants<'a> {
    nk: usize,
    dt: f32,
    absorb: f32,
    evap: f32,
    settle: &'a [f32],
    diffuse: &'a [f32],
    filter: &'a [f32],
    stain: &'a [f32],
    lift: &'a [f32],
}

/// A step of one pixel's own physics: absorption, evaporation, settling, staining and lifting.
/// `cap` is the pores' room as sealing leaves it, `cap_full` the sheet's own; `rim` the share of the
/// sheet nearby that is dry; `through` µm/s of water flowing through the pixel. Returns µm absorbed
/// and µm evaporated.
fn pixel(p: &mut Px, cap: f32, cap_full: f32, rim: f32, through: f32, k: &Constants) -> (f64, f64) {
    const S: usize = 0; // suspended
    const C: usize = 1; // carried
    const D: usize = 2; // deposited: sediment, not yet dried
    const B: usize = 3; // bound
    const G: usize = 4; // glued: dried in place
    let (mut absorbed, mut evaporated) = (0.0f64, 0.0f64);
    let dt = k.dt;
    // absorption, carrying pigment; what the pores hold back is filtered at the surface
    if p.sw > 0.0 && cap > p.aw {
        let a = (k.absorb * (1.0 - p.aw / cap.max(1e-3)) * dt).min(p.sw).min(cap - p.aw).max(0.0);
        if a > 0.0 {
            let share = a / p.sw;
            for q in 0..k.nk {
                let m = p.m[S][q] * share;
                p.m[S][q] -= m;
                p.m[D][q] += m * k.filter[q];
                p.m[C][q] += m * (1.0 - k.filter[q]);
            }
            p.sw -= a;
            p.aw += a;
            absorbed += a as f64;
        }
    }
    // evaporation: the surface first (faster at the rim), then the pores
    if p.sw > 0.0 {
        let e = (k.evap * (1.0 + RIM * rim) * dt).min(p.sw);
        p.sw -= e;
        evaporated += e as f64;
        if p.sw <= W_DRY {
            // the last of the surface water: into the pores if they have room, else into the air;
            // what it carried stays where it dried
            let rest = p.sw;
            let into = rest.min((cap - p.aw).max(0.0));
            p.aw += into;
            absorbed += into as f64;
            evaporated += (rest - into) as f64;
            p.sw = 0.0;
            for q in 0..k.nk {
                p.m[D][q] += p.m[S][q];
                p.m[S][q] = 0.0;
            }
        }
    } else if p.aw > 0.0 {
        let e = (k.evap * PORE_EVAP * (p.aw / cap_full.max(1e-3)).clamp(PORE_FLOOR, 1.0) * dt).min(p.aw);
        p.aw -= e;
        evaporated += e as f64;
        if p.aw < 1e-3 {
            // dry: what the pores carried dries into the fibres
            evaporated += p.aw as f64;
            p.aw = 0.0;
            for q in 0..k.nk {
                p.m[B][q] += p.m[C][q];
                p.m[C][q] = 0.0;
            }
        }
    }
    // settling out of the surface water; sediment entrained by water flowing through; a glued
    // deposit lifting as its gum redissolves
    if p.sw > W_DRY {
        let entrain = 1.0 - (-ENTRAIN * through / p.sw.max(1.0) * dt).exp();
        for q in 0..k.nk {
            // settling, and Brownian diffusion to the fibres, where the particles stick: the flux
            // D c / δ over a boundary layer δ as deep as the water or as far as the particles diffuse
            // in DIFFUSE_REF_S, whichever is less. Where the water stands deeper than that, every part
            // of a wash gains the same pigment per area however deep it is: it evens a wash of fine
            // pigment over the paper's tooth a little.
            let h = p.sw.max(1.0);
            let reach = (std::f32::consts::PI * k.diffuse[q] * DIFFUSE_REF_S).sqrt();
            let diffuse = k.diffuse[q] / (h * h.min(reach).max(1e-3));
            let down = p.m[S][q] * (1.0 - (-(k.settle[q] / h + diffuse) * dt).exp());
            let up = p.m[D][q] * entrain;
            let lifted = p.m[G][q] * (1.0 - (-k.lift[q] * dt).exp());
            p.m[S][q] += up + lifted - down;
            p.m[D][q] += down - up;
            p.m[G][q] -= lifted;
        }
    } else if p.aw < GUM_SET * cap_full {
        // the pixel has dried enough for the gum to set: its sediment is glued down
        for q in 0..k.nk {
            p.m[G][q] += p.m[D][q];
            p.m[D][q] = 0.0;
        }
    }
    // staining: pigment in the pores adsorbs onto the fibres
    if p.aw > 0.0 {
        for q in 0..k.nk {
            let m = p.m[C][q] * (1.0 - (-k.stain[q] * dt).exp());
            p.m[C][q] -= m;
            p.m[B][q] += m;
        }
    }
    (absorbed, evaporated)
}

/// Reflectance of a Kubelka–Munk layer of absorption `k` and scattering `s` (already times its
/// thickness) over a background of reflectance `rg`.
pub fn km_layer(k: f32, s: f32, rg: f32) -> f32 {
    if k <= 0.0 && s <= 0.0 {
        return rg;
    }
    if s < 1e-5 {
        // a pure absorber: light crosses it twice
        return rg * (-2.0 * k).exp();
    }
    let a = 1.0 + k / s;
    let b = (a * a - 1.0).max(0.0).sqrt();
    let bs = (b * s).min(30.0);
    let coth = 1.0 / bs.tanh().max(1e-9);
    ((1.0 - rg * (a - b * coth)) / (a - rg + b * coth)).clamp(0.0, 1.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sheet(paper: Paper) -> Water {
        Water::new(paper, 120, 90, 0.5, 3)
    }

    /// Lay `um` µm of water over a disc, with `pig` = (species, µg/mm²) in it.
    fn disc(w: &mut Water, cx: f32, cy: f32, r: f32, um: f32, pig: Option<(usize, f32)>) {
        for y in 0..w.h {
            for x in 0..w.w {
                if (x as f32 - cx).powi(2) + (y as f32 - cy).powi(2) < r * r {
                    let i = y * w.w + x;
                    let m: Vec<(usize, f32)> = pig.into_iter().collect();
                    w.add(i, um, &m);
                }
            }
        }
    }

    /// Timing, not a check: `cargo test --release --lib bench_drying -- --ignored --nocapture` prints
    /// the clock, steps and wall time as a studio-sized sheet dries, and where `step` spent it.
    #[test]
    #[ignore]
    fn bench_drying_a_wet_sheet() {
        // a studio sheet: 900 × 720 px of rough paper 300 mm wide, wet all over with two pigments
        let mut w = Water::new(Paper::rough(), 900, 720, 300.0 / 900.0, 3);
        let u = w.species_index(&AqPigment::named("ultramarine").unwrap()).unwrap();
        let y = w.species_index(&AqPigment::named("cadmium yellow").unwrap()).unwrap();
        for i in 0..w.w * w.h {
            w.add(i, 70.0, &[(u, 0.02), (y, 0.04)]);
        }
        let t = std::time::Instant::now();
        let mut last = (0.0f64, 0u64);
        for _ in 0..40 {
            if w.wet_bounds(0).is_none() { break; }
            w.advance(30.0);
            let t2 = w.totals();
            eprintln!("clock {:6.0} s: surface {:.0} µm·px, absorbed {:.0}; steps {} (+{}), last dt {:.3}; wall {:.1} s",
                w.clock_s, t2.surface_water, t2.absorbed_water, w.steps_taken, w.steps_taken - last.1, w.last_dt, t.elapsed().as_secs_f64() - last.0);
            last = (t.elapsed().as_secs_f64(), w.steps_taken);
        }
        PROFILE.with(|p| eprintln!("phases: flow {:.1} s, marangoni+rest {:.1} s, wick {:.1} s, pixel {:.1} s; total {:.1} s", p.borrow()[0], p.borrow()[1], p.borrow()[2], p.borrow()[3], t.elapsed().as_secs_f64()));
    }

    #[test]
    fn the_books_balance_through_flow_absorption_and_drying() {
        for paper in [Paper::cold_press(), Paper::xuan()] {
            let mut w = sheet(paper.clone());
            let k = w.species_index(&AqPigment::named("ultramarine").unwrap()).unwrap();
            disc(&mut w, 60.0, 45.0, 12.0, 120.0, Some((k, 0.36)));
            w.tilt_deg = 30.0;
            for _ in 0..20 {
                w.advance(1.0);
                let ((on, books), (pon, pbooks)) = w.check();
                // (f32 state: rounding leaves the books about 1e-6 to 1e-5 of the water out, the more the
                // deeper it stands: a pinned puddle 400 µm deep through hundreds of short steps)
                assert!((on - books).abs() < 3e-5 * books.abs().max(1e-3), "{}: water {on} vs {books}", paper.name);
                assert!((pon - pbooks).abs() < 1e-5 * pbooks.abs().max(1e-3), "{}: pigment {pon} vs {pbooks}", paper.name);
            }
            w.dry_out();
            let t = w.totals();
            assert!(t.surface_water < 1e-9 && t.absorbed_water < 1e-9, "{}: dry", paper.name);
            let ((on, books), _) = w.check();
            // (the state is f32: thousands of steps of rounding leave the books 1e-5 of the water out)
            assert!(on.abs() < 1e-9 && books.abs() < 1e-4 * w.ledger.water_added, "{}: all the water left: {books}", paper.name);
        }
    }

    #[test]
    fn paint_touched_into_clean_water_blooms_but_a_wash_keeps_its_tension_even() {
        // a drop of paint into clean wet paper: how far its pigment reaches after 10 s
        let reach = |marangoni: f32| -> f32 {
            let mut w = Water::new(Paper::cold_press(), 200, 150, 0.3, 4);
            w.marangoni_n_m = marangoni;
            let k = w.species_index(&AqPigment::named("quinacridone rose").unwrap()).unwrap();
            for i in 0..w.w * w.h { w.add(i, 100.0, &[]); }
            disc(&mut w, 100.0, 75.0, 8.0, 30.0, Some((k, 0.5)));
            w.advance(10.0);
            let m = |i: usize| w.ms[k][i] + w.ma[k][i] + w.md[k][i] + w.mg[k][i] + w.mb[k][i];
            let total: f32 = (0..w.w * w.h).map(m).sum();
            // the radius holding 90 % of it
            let mut by_r: Vec<(f32, f32)> = (0..w.w * w.h).map(|i| ((((i % 200) as f32 - 100.0).powi(2) + ((i / 200) as f32 - 75.0).powi(2)).sqrt(), m(i))).collect();
            by_r.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap());
            let mut acc = 0.0;
            by_r.iter().find(|(_, v)| { acc += v; acc >= 0.9 * total }).unwrap().0
        };
        let (still, bloom) = (reach(0.0), reach(MARANGONI_N_M));
        assert!(bloom > 1.3 * still, "the paint blooms out into the clean water: {bloom} px vs {still} px without");
        // a wash of one paint: the same evenness with the tension's pull or without it
        let unevenness = |marangoni: f32| -> f32 {
            let mut w = Water::new(Paper::cold_press(), 160, 120, 0.3, 2);
            w.marangoni_n_m = marangoni;
            let k = w.species_index(&AqPigment::named("burnt sienna").unwrap()).unwrap();
            for y in 20..100 { for x in 20..140 { w.add(y * 160 + x, 70.0, &[(k, 0.18)]); } }
            w.dry_out();
            let v: Vec<f32> = (30..90).flat_map(|y| (30..130).map(move |x| y * 160 + x)).map(|i| w.md[k][i] + w.mg[k][i] + w.mb[k][i]).collect();
            let mean = v.iter().sum::<f32>() / v.len() as f32;
            (v.iter().map(|m| (m - mean).powi(2)).sum::<f32>() / v.len() as f32).sqrt() / mean
        };
        let (plain, pulled) = (unevenness(0.0), unevenness(MARANGONI_N_M));
        assert!(pulled < 1.1 * plain, "the paint's own tension is even: cv {pulled} vs {plain} without");
    }

    #[test]
    fn coarse_pigment_settles_and_fine_stays_suspended() {
        let u = AqPigment::named("ultramarine").unwrap();
        let p = AqPigment::named("phthalo blue").unwrap();
        assert!(u.settling_um_s() > 1.0 && u.settling_um_s() < 20.0, "ultramarine {} µm/s", u.settling_um_s());
        assert!(p.settling_um_s() < 0.05, "phthalo {} µm/s", p.settling_um_s());
        let mut w = sheet(Paper { absorb_um_s: 0.0, ..Paper::hot_press() });
        w.evap_um_s = 0.0;
        let (ku, kp) = (w.species_index(&u).unwrap(), w.species_index(&p).unwrap());
        disc(&mut w, 60.0, 45.0, 20.0, 80.0, Some((ku, 0.16)));
        disc(&mut w, 60.0, 45.0, 20.0, 0.0, Some((kp, 0.16)));
        w.advance(20.0);
        let t = w.totals();
        assert!(t.deposited[ku] > 0.4 * (t.deposited[ku] + t.suspended[ku]), "ultramarine has largely settled");
        assert!(t.deposited[kp] < 0.05 * t.suspended[kp], "phthalo is still suspended");
    }

    #[test]
    fn a_tilted_sheet_runs_downhill() {
        let mut w = sheet(Paper::hot_press());
        disc(&mut w, 60.0, 20.0, 10.0, 250.0, None);
        w.tilt_deg = 40.0;
        w.tilt_dir_deg = 90.0;
        let cy = |w: &Water| {
            let (mut m, mut s) = (0.0f64, 0.0f64);
            for i in 0..w.sw.len() {
                m += w.sw[i] as f64 * (i / w.w) as f64;
                s += w.sw[i] as f64;
            }
            m / s.max(1e-9)
        };
        let before = cy(&w);
        w.advance(2.0);
        assert!(cy(&w) > before + 3.0, "the water's centre moved down: {before} -> {}", cy(&w));
    }

    #[test]
    fn unsized_paper_drinks_and_wicks_along_its_grain() {
        let mut w = sheet(Paper { anisotropy: 4.0, ..Paper::xuan() });
        w.fdir.iter_mut().for_each(|a| *a = 0.0);
        disc(&mut w, 60.0, 45.0, 3.0, 40.0, None);
        w.advance(2.0);
        assert!(w.sw.iter().all(|&v| v < 1.0), "it all sank in");
        let row = 45 * w.w;
        let along = (0..w.w).filter(|&x| w.aw[row + x] > 1.0).count();
        let across = (0..w.h).filter(|&y| w.aw[y * w.w + 60] > 1.0).count();
        assert!(along > across + 2, "wicked further along the grain ({along}) than across ({across})");
    }

    #[test]
    fn km_layers() {
        assert_eq!(km_layer(0.0, 0.0, 0.7), 0.7);
        assert!((km_layer(0.5, 0.0, 1.0) - (-1.0f32).exp()).abs() < 1e-6);
        assert!(km_layer(0.1, 50.0, 0.0) > 0.85, "a thick scattering layer hides black");
        assert!(km_layer(2.0, 0.5, 0.9) < km_layer(0.2, 0.5, 0.9));
    }
}
