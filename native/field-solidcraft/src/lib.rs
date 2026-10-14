//! A deliberately small C ABI over SolidCraft's geometry, document, and I/O crates.
//! Rust owns designs and meshes. The JVM copies returned buffers before freeing them.

use std::cell::RefCell;
use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use solidcraft_doc::{Document, Model};
use solidcraft_geom::Mesh;
use solidcraft_io::Format;

const ABI_VERSION: u32 = 1;

pub struct Design {
    doc: Document,
    model: Model,
}

pub struct MeshData {
    positions: Vec<f64>,
    triangles: Vec<u32>,
    face_ids: Vec<u32>,
}

pub struct Bytes(Vec<u8>);

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::new("").unwrap());
}

fn error(message: impl AsRef<str>) {
    let clean = message.as_ref().replace('\0', " ");
    LAST_ERROR.with(|slot| *slot.borrow_mut() = CString::new(clean).unwrap_or_default());
}

fn boundary<T: Copy>(fallback: T, f: impl FnOnce() -> Result<T, String>) -> T {
    error("");
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            error(message);
            fallback
        }
        Err(_) => {
            error("SolidCraft panicked inside the native bridge");
            fallback
        }
    }
}

unsafe fn input<'a>(ptr: *const u8, len: usize) -> Result<&'a [u8], String> {
    if ptr.is_null() {
        return Err("null input".into());
    }
    if len > 256 << 20 {
        return Err("input exceeds 256 MiB".into());
    }
    Ok(unsafe { std::slice::from_raw_parts(ptr, len) })
}

unsafe fn design<'a>(ptr: *mut Design) -> Result<&'a mut Design, String> {
    unsafe { ptr.as_mut() }.ok_or_else(|| "null design handle".into())
}

unsafe fn cstr<'a>(ptr: *const c_char) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err("null string".into());
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_str()
        .map_err(|_| "string is not UTF-8".into())
}

fn validate(model: &Model) -> Result<(), String> {
    if let Some((name, problem)) = model.param_errors.iter().next() {
        return Err(format!("parameter {name}: {problem}"));
    }
    if let Some(result) = model
        .results
        .iter()
        .find(|r| !r.skipped && r.error.is_some())
    {
        return Err(format!(
            "feature {}: {}",
            result.id,
            result.error.as_deref().unwrap_or_default()
        ));
    }
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "C" fn field_solidcraft_abi_version() -> u32 {
    ABI_VERSION
}

/// Valid until the next bridge call on the same thread; copy it immediately.
#[unsafe(no_mangle)]
pub extern "C" fn field_solidcraft_error() -> *const c_char {
    LAST_ERROR.with(|slot| slot.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_open(data: *const u8, len: usize) -> *mut Design {
    boundary(ptr::null_mut(), || {
        let doc =
            solidcraft_io::read_design(unsafe { input(data, len)? }).map_err(|e| e.to_string())?;
        let mut model = Model::new();
        model.evaluate(&doc);
        validate(&model)?;
        Ok(Box::into_raw(Box::new(Design { doc, model })))
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_close(ptr: *mut Design) {
    if !ptr.is_null() {
        unsafe { drop(Box::from_raw(ptr)) };
    }
}

/// An invalid edit leaves the design and its evaluated model unchanged.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_set_parameter(
    ptr: *mut Design,
    name: *const c_char,
    expression: *const c_char,
) -> i32 {
    boundary(0, || {
        let d = unsafe { design(ptr)? };
        let mut doc = d.doc.clone();
        doc.set_param(
            unsafe { cstr(name)? },
            unsafe { cstr(expression)? },
            None,
            None,
        )
        .map_err(|e| e.to_string())?;
        let mut model = d.model.clone();
        model.evaluate(&doc);
        validate(&model)?;
        model.adopt_solved_sketches(&mut doc);
        d.doc = doc;
        d.model = model;
        Ok(1)
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_summary(ptr: *mut Design) -> *mut Bytes {
    boundary(ptr::null_mut(), || {
        let d = unsafe { design(ptr)? };
        let state = d.model.state();
        let bodies: Result<Vec<_>, String> = state
            .bodies
            .iter()
            .map(|b| {
                let m = solidcraft_kernel::measure(&b.body).map_err(|e| e.to_string())?;
                Ok(serde_json::json!({
                    "name": b.name, "volume": m.volume, "area": m.area,
                    "bounds": {"min": [m.bbox.min.x, m.bbox.min.y, m.bbox.min.z],
                               "max": [m.bbox.max.x, m.bbox.max.y, m.bbox.max.z]},
                    "faces": m.faces, "edges": m.edges, "vertices": m.vertices
                }))
            })
            .collect();
        let value = serde_json::json!({"name": d.doc.name, "bodies": bodies?,
            "recomputed": d.model.last_recomputed, "restored": d.model.last_restored});
        Ok(Box::into_raw(Box::new(Bytes(
            value.to_string().into_bytes(),
        ))))
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_mesh(
    ptr: *mut Design,
    index: usize,
    tolerance: f64,
) -> *mut MeshData {
    boundary(ptr::null_mut(), || {
        if !tolerance.is_finite() || tolerance <= 0.0 {
            return Err("mesh tolerance must be positive and finite".into());
        }
        let d = unsafe { design(ptr)? };
        let state = d.model.state();
        let body = state
            .bodies
            .get(index)
            .ok_or_else(|| format!("no body at index {index}"))?;
        let Mesh {
            positions,
            triangles,
            tri_face,
            ..
        } = body.body.tessellate(tolerance).map_err(|e| e.to_string())?;
        if tri_face.len() != triangles.len() {
            return Err(format!(
                "tessellation returned {} face IDs for {} triangles",
                tri_face.len(),
                triangles.len()
            ));
        }
        let positions = positions
            .into_iter()
            .flat_map(|p| [p.x, p.y, p.z])
            .collect();
        let triangles = triangles.into_iter().flatten().collect();
        Ok(Box::into_raw(Box::new(MeshData {
            positions,
            triangles,
            face_ids: tri_face,
        })))
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_mesh_close(ptr: *mut MeshData) {
    if !ptr.is_null() {
        unsafe { drop(Box::from_raw(ptr)) };
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_mesh_vertex_count(ptr: *const MeshData) -> usize {
    unsafe { ptr.as_ref() }.map_or(0, |m| m.positions.len() / 3)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_mesh_triangle_count(ptr: *const MeshData) -> usize {
    unsafe { ptr.as_ref() }.map_or(0, |m| m.triangles.len() / 3)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_mesh_positions(ptr: *const MeshData) -> *const f64 {
    unsafe { ptr.as_ref() }.map_or(ptr::null(), |m| m.positions.as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_mesh_triangles(ptr: *const MeshData) -> *const u32 {
    unsafe { ptr.as_ref() }.map_or(ptr::null(), |m| m.triangles.as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_mesh_face_ids(ptr: *const MeshData) -> *const u32 {
    unsafe { ptr.as_ref() }.map_or(ptr::null(), |m| m.face_ids.as_ptr())
}

/// 1=STEP, 2=binary STL, 3=SolidCraft design JSON.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_export(ptr: *mut Design, format: u32) -> *mut Bytes {
    boundary(ptr::null_mut(), || {
        let d = unsafe { design(ptr)? };
        let bytes = match format {
            1 => solidcraft_io::export(&d.model.state(), &[], Format::Step, &d.doc.name)
                .map_err(|e| e.to_string())?,
            2 => solidcraft_io::export(&d.model.state(), &[], Format::StlBinary, &d.doc.name)
                .map_err(|e| e.to_string())?,
            3 => solidcraft_io::write_design(&d.doc),
            _ => return Err(format!("unsupported export format {format}")),
        };
        Ok(Box::into_raw(Box::new(Bytes(bytes))))
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_bytes_len(ptr: *const Bytes) -> usize {
    unsafe { ptr.as_ref() }.map_or(0, |b| b.0.len())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_bytes_ptr(ptr: *const Bytes) -> *const u8 {
    unsafe { ptr.as_ref() }.map_or(ptr::null(), |b| b.0.as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn field_solidcraft_bytes_close(ptr: *mut Bytes) {
    if !ptr.is_null() {
        unsafe { drop(Box::from_raw(ptr)) };
    }
}
