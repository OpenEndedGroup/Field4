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
