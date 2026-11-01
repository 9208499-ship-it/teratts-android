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

/**
 * Where synthesis may run right now, for the log ("тормоза вечером при выключенном экране"):
 * the cpuset the system put the app in (top-app / foreground / background…), the CPUs this thread
 * may use, the CPU it is on and that CPU's clock now / at most (MHz, "?" if the system hides it).
 */
pub fn where_now() -> String {
    let cpuset = std::fs::read_to_string("/proc/self/cpuset").unwrap_or_default().trim().to_string();
    let status = std::fs::read_to_string("/proc/thread-self/status")
        .or_else(|_| std::fs::read_to_string("/proc/self/status")).unwrap_or_default();
    let allowed = status.lines().find(|l| l.starts_with("Cpus_allowed_list:"))
        .and_then(|l| l.split_whitespace().nth(1)).unwrap_or("?").to_string();
    let mut cpu: libc::c_uint = 0;
    // getcpu(2) directly: the libc wrapper is not in every Android libc binding
    let cpu = if unsafe { libc::syscall(libc::SYS_getcpu, &mut cpu as *mut libc::c_uint, std::ptr::null_mut::<libc::c_uint>(), std::ptr::null_mut::<libc::c_void>()) } == 0 { cpu as i64 } else { -1 };
    let mhz = |f: &str| std::fs::read_to_string(format!("/sys/devices/system/cpu/cpu{cpu}/cpufreq/{f}")).ok()
        .and_then(|s| s.trim().parse::<u64>().ok()).map(|k| (k / 1000).to_string()).unwrap_or_else(|| "?".into());
    // the priority (nice: -20 highest … 19 lowest) of this thread and the span over all the app's threads
    let me = unsafe { libc::getpriority(libc::PRIO_PROCESS as _, 0) };
    let mut nices: Vec<i64> = Vec::new();
    if let Ok(rd) = std::fs::read_dir("/proc/self/task") {
        for e in rd.flatten() {
            if let Ok(st) = std::fs::read_to_string(e.path().join("stat")) {
                // after "(comm)": state is field 3, nice is field 19
                if let Some(rest) = st.rsplit_once(')').map(|(_, r)| r) {
                    if let Some(v) = rest.split_whitespace().nth(16).and_then(|v| v.parse::<i64>().ok()) { nices.push(v); }
                }
            }
        }
    }
    let span = match (nices.iter().min(), nices.iter().max()) { (Some(a), Some(b)) => format!("{a}..{b}"), _ => "?".into() };
    format!("cpuset={cpuset} allowed={allowed} cpu={cpu} mhz={}/{} nice={me} nice_all={span}", mhz("scaling_cur_freq"), mhz("scaling_max_freq"))
}

#[cfg(test)]
mod where_tests {
    #[test]
    fn where_now_reads() {
        let w = super::where_now();
        assert!(w.contains("allowed=") && w.contains("cpu="), "{w}");
    }
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
