//! "Fast cores only": pin synthesis to the fast CPU cluster.
//!
//! The list of fast cores comes from the app (setFastCores). Worker threads of
//! ONNX Runtime are created while the engine loads and inherit the creating
//! thread's CPU mask, so the loading thread is pinned for that moment; the
//! synthesis calling thread is pinned for the duration of a call. If the system
//! does not allow a mask (e.g. a background cpuset without these cores), the
//! thread simply stays as it was.

use once_cell::sync::Lazy;
use std::sync::Mutex;

static FAST: Lazy<Mutex<Vec<usize>>> = Lazy::new(|| Mutex::new(Vec::new()));

const WORDS: usize = 16; // 1024 CPUs, as cpu_set_t

pub fn set(cores: Vec<usize>) {
    if let Ok(mut f) = FAST.lock() {
        *f = cores;
    }
}

pub fn get() -> Vec<usize> {
    FAST.lock().map(|f| f.clone()).unwrap_or_default()
}

fn get_mask() -> Option<[u64; WORDS]> {
    let mut m = [0u64; WORDS];
    let r = unsafe { libc::syscall(libc::SYS_sched_getaffinity, 0, std::mem::size_of_val(&m), m.as_mut_ptr()) };
    if r < 0 { None } else { Some(m) }
}

fn set_mask(m: &[u64; WORDS]) -> bool {
    unsafe { libc::syscall(libc::SYS_sched_setaffinity, 0, std::mem::size_of_val(m), m.as_ptr()) == 0 }
}

/// Pins the current thread to the fast cores until dropped (no-op if none are set).
pub struct Pin {
    old: Option<[u64; WORDS]>,
}

impl Drop for Pin {
    fn drop(&mut self) {
        if let Some(old) = self.old.take() {
            set_mask(&old);
        }
    }
}

pub fn pin_current() -> Pin {
    let cores = get();
    if cores.len() < 2 {
        return Pin { old: None };
    }
    let old = match get_mask() {
        Some(m) => m,
        None => return Pin { old: None },
    };
    let mut m = [0u64; WORDS];
    for c in cores {
        if c < WORDS * 64 {
            m[c / 64] |= 1u64 << (c % 64);
        }
    }
    if set_mask(&m) { Pin { old: Some(old) } } else { Pin { old: None } }
}
