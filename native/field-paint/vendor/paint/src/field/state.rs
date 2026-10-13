//! Exact snapshots of what a painter holds — a brush with paint in each bristle, a knife's bead, a
//! rag — so a studio can save its tools with the canvas and put them back as they were.
//!
//! The format is little-endian binary: a four-byte tag (`FPB1` brush, `FPK1` knife, `FPR1` rag),
//! then every field in declaration order. Nothing is rounded: a restored tool continues exactly as
//! the original would have.

use crate::bristle::{Bristle, Held, Kind, Knife, Tool};
use crate::rag::{Mobile, Rag};
use crate::wet::{LAT, Latent, Prop};

struct W(Vec<u8>);

impl W {
    fn f(&mut self, v: f32) {
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn d(&mut self, v: f64) {
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn u(&mut self, v: u64) {
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn b(&mut self, v: bool) {
        self.0.push(v as u8);
    }
    fn fs(&mut self, v: &[f32]) {
        v.iter().for_each(|&x| self.f(x));
    }
}

struct R<'a>(&'a [u8], usize);

impl R<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], String> {
        let s = self.0.get(self.1..self.1 + n).ok_or("truncated tool state")?;
        self.1 += n;
        Ok(s)
    }
    fn f(&mut self) -> Result<f32, String> {
        Ok(f32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn d(&mut self) -> Result<f64, String> {
        Ok(f64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn u(&mut self) -> Result<u64, String> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn b(&mut self) -> Result<bool, String> {
        Ok(self.take(1)?[0] != 0)
    }
    fn lat(&mut self) -> Result<Latent, String> {
        let mut l = [0.0; LAT];
        for v in &mut l {
            *v = self.f()?;
        }
        Ok(l)
    }
    fn prop(&mut self) -> Result<Prop, String> {
        Ok([self.f()?, self.f()?, self.f()?, self.f()?, self.f()?])
    }
    fn tag(&mut self, t: &[u8; 4]) -> Result<(), String> {
        let got = self.take(4)?;
        if got != t {
            return Err(format!("not a {} state (tag {:?})", String::from_utf8_lossy(t), String::from_utf8_lossy(got)));
        }
        Ok(())
    }
    fn end(&self) -> Result<(), String> {
        if self.1 != self.0.len() {
            return Err(format!("{} bytes after the tool state", self.0.len() - self.1));
        }
        Ok(())
    }
}

fn kind_code(k: Kind) -> u64 {
    match k {
        Kind::Round => 0,
        Kind::Flat => 1,
        Kind::Filbert => 2,
        Kind::Fan => 3,
        Kind::Rigger => 4,
        Kind::Blender => 5,
    }
}

fn kind_of(c: u64) -> Result<Kind, String> {
    Ok(match c {
        0 => Kind::Round,
        1 => Kind::Flat,
        2 => Kind::Filbert,
        3 => Kind::Fan,
        4 => Kind::Rigger,
        5 => Kind::Blender,
        k => return Err(format!("unknown brush kind {k} in tool state")),
    })
}

fn write_tool(w: &mut W, t: &Tool) {
    w.u(kind_code(t.kind));
    w.f(t.width);
    w.u(t.bristles as u64);
    w.fs(&[t.length, t.stiffness, t.hair, t.run, t.lay, t.pickup, t.push, t.splay, t.ragged, t.point]);
}

fn read_tool(r: &mut R) -> Result<Tool, String> {
    let kind = kind_of(r.u()?)?;
    let width = r.f()?;
    let bristles = r.u()? as usize;
    Ok(Tool {
        kind,
        width,
        bristles,
        length: r.f()?,
        stiffness: r.f()?,
        hair: r.f()?,
        run: r.f()?,
        lay: r.f()?,
        pickup: r.f()?,
        push: r.f()?,
        splay: r.f()?,
        ragged: r.f()?,
        point: r.f()?,
        tooth: 0.0,
    })
}

impl Held {
    /// The brush, bristle by bristle (`FPB1`).
    pub fn write_state(&self) -> Vec<u8> {
        let mut w = W(b"FPB1".to_vec());
        write_tool(&mut w, &self.tool);
        w.b(self.shows_solvent);
        w.b(self.shows_oil);
        w.u(self.bristles.len() as u64);
        for b in &self.bristles {
            w.fs(&[b.rx, b.ry, b.len, b.thresh, b.bend.0, b.bend.1]);
            w.u(b.seed);
            for p in b.prev {
                w.b(p.is_some());
                let (x, y) = p.unwrap_or((0.0, 0.0));
                w.fs(&[x, y]);
            }
            w.f(b.vol);
            w.fs(&b.lat);
            w.fs(&b.hide);
            w.f(b.cure);
            w.f(b.solvent);
        }
        if self.tool.tooth != 0.0 {
            w.f(self.tool.tooth);
        }
        w.0
    }

    /// A brush written by `write_state`.
    pub fn read_state(bytes: &[u8]) -> Result<Held, String> {
        let mut r = R(bytes, 0);
        r.tag(b"FPB1")?;
        let tool = read_tool(&mut r)?;
        let shows_solvent = r.b()?;
        let shows_oil = r.b()?;
        let n = r.u()? as usize;
        if n > 1_000_000 {
            return Err(format!("{n} bristles in tool state"));
        }
        let mut bristles = Vec::with_capacity(n);
        for _ in 0..n {
            let (rx, ry, len, thresh) = (r.f()?, r.f()?, r.f()?, r.f()?);
            let bend = (r.f()?, r.f()?);
            let seed = r.u()?;
            let mut prev = [None, None];
            for p in &mut prev {
                let some = r.b()?;
                let xy = (r.f()?, r.f()?);
                *p = some.then_some(xy);
            }
            bristles.push(Bristle { rx, ry, len, thresh, bend, seed, prev, vol: r.f()?, lat: r.lat()?, hide: r.prop()?, cure: r.f()?, solvent: r.f()? });
        }
        // (the tool's tooth, written after the bristles where it isn't 0: a brush saved before it
        // was kept, or without it, has none)
        let mut tool = tool;
        if r.1 < r.0.len() {
            tool.tooth = r.f()?;
        }
        r.end()?;
        tool.validate()?;
        Ok(Held { tool, bristles, shows_solvent, shows_oil })
    }
}

impl Knife {
    /// The blade and its bead (`FPK1`).
    pub fn write_state(&self) -> Vec<u8> {
        let mut w = W(b"FPK1".to_vec());
        w.fs(&[self.width, self.vol, self.solvent]);
        w.fs(&self.lat);
        w.fs(&self.hide);
        w.f(self.cure);
        w.0
    }

    /// A knife written by `write_state`.
    pub fn read_state(bytes: &[u8]) -> Result<Knife, String> {
        let mut r = R(bytes, 0);
        r.tag(b"FPK1")?;
        let k = Knife { width: r.f()?, vol: r.f()?, solvent: r.f()?, lat: r.lat()?, hide: r.prop()?, cure: r.f()? };
        r.end()?;
        Ok(k)
    }
}

/// A rag (`FPR1`). Every field of a `Rag` is public; this is here so all tools save one way.
pub fn write_rag(g: &Rag) -> Vec<u8> {
    let mut w = W(b"FPR1".to_vec());
    w.fs(&[g.width, g.load, g.soaked, g.damp]);
    w.d(g.wet_at);
    w.u(g.fold as u64);
    w.u(g.seed);
    w.d(g.solvent_mm3);
    let m = &g.mobile;
    w.f(m.mm3);
    w.fs(&m.lat);
    w.fs(&m.hide);
    w.fs(&[m.cure, m.solv_mm3]);
    w.d(m.at_min);
    w.d(m.at_secs);
    w.0
}

/// A rag written by `write_rag`.
pub fn read_rag(bytes: &[u8]) -> Result<Rag, String> {
    let mut r = R(bytes, 0);
    r.tag(b"FPR1")?;
    let (width, load, soaked, damp) = (r.f()?, r.f()?, r.f()?, r.f()?);
    let wet_at = r.d()?;
    let fold = r.u()? as u32;
    let seed = r.u()?;
    let solvent_mm3 = r.d()?;
    let mobile = Mobile { mm3: r.f()?, lat: r.lat()?, hide: r.prop()?, cure: r.f()?, solv_mm3: r.f()?, at_min: r.d()?, at_secs: r.d()? };
    r.end()?;
    Ok(Rag { width, load, soaked, damp, wet_at, fold, seed, solvent_mm3, mobile })
}

impl Held {
    /// The paint the brush carries, as one colour (linear RGB, mixed by volume across the bristles
    /// the way paint mixes) and its volume in the crate's units (coats × square units); None when
    /// the brush is clean.
    pub fn carried_paint(&self) -> Option<(crate::Rgb, f32)> {
        let vol: f32 = self.bristles.iter().map(|b| b.vol).sum();
        if vol <= 1e-9 {
            return None;
        }
        let mut lat = [0.0f32; LAT];
        for b in &self.bristles {
            for (l, &v) in lat.iter_mut().zip(&b.lat) {
                *l += v * b.vol / vol;
            }
        }
        Some((mixbox::latent_to_linear_float_rgb(&lat), vol))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Canvas, Gesture, Paint};

    #[test]
    fn a_dirty_brush_restored_paints_exactly_as_the_original() {
        let mut c = Canvas::new(200, 1.0, [1.0, 1.0, 1.0]);
        let mut h = Held::new(Tool::filbert(30.0), 9).with_engine(c.engine());
        h.load(Paint::body([0.6, 0.1, 0.05]), 1.0);
        c.drag(&mut h, &Gesture::new(vec![(100.0, 300.0), (800.0, 600.0)]), None);
        let mut copy = Held::read_state(&h.write_state()).unwrap();
        assert_eq!(format!("{h:?}"), format!("{copy:?}"));
        let (mut a, mut b) = (c.clone(), c);
        let g = Gesture::new(vec![(100.0, 700.0), (900.0, 200.0)]);
        a.drag(&mut h, &g, None);
        b.drag(&mut copy, &g, None);
        assert_eq!(a.seen(), b.seen());
    }

    #[test]
    fn knife_and_rag_round_trip() {
        let mut k = Knife::new(40.0);
        k.load(Paint::body([0.2, 0.3, 0.6]), 0.5);
        let k2 = Knife::read_state(&k.write_state()).unwrap();
        assert_eq!(format!("{k:?}"), format!("{k2:?}"));
        let mut g = Rag::new(50.0, 3);
        g.load = 0.3;
        g.mobile.mm3 = 0.2;
        assert_eq!(read_rag(&write_rag(&g)).unwrap(), g);
        assert!(Held::read_state(&write_rag(&g)).is_err());
    }
}
