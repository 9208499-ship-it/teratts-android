//! Cleaning of Pocket TTS's finished speech with a speech-enhancement network: DPDFNet
//! (Ceva, Apache-2.0, `dpdfnet2_48khz_hr.onnx`, 48 kHz, 2.6 M parameters), the same model and the
//! same steps as sherpa-onnx's offline denoiser (`offline-speech-denoiser-dpdfnet-impl.h`).
//!
//! What it does to Pocket's speech: smooths what the codec leaves, the voice stays. Chosen by ear
//! (tolyos, after 1.24.1: of five variants — more steps, full precision, this — this sounded best).
//!
//! The steps: 48 kHz → STFT (960 points, hop 480, Vorbis window, centred with a reflected edge) →
//! the network, frame by frame, carrying its state (the first part of the state holds two running
//! normalisations, started from the values in the model's metadata) → the enhanced spectrum mixed
//! with the original one 4 frames earlier (the attenuation limit: how far a sound may be pushed
//! down) → inverse STFT (overlap-add, divided by the summed squared window) → 2 windows earlier
//! (the network answers 4 hops late).

use anyhow::{Context, Result};
use ort::session::Session;
use ort::value::Tensor;
use rustfft::num_complex::Complex32;
use rustfft::{Fft, FftPlanner};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub const RATE: u32 = 48_000;
pub const MODEL_FILE: &str = "dpdfnet2_48khz_hr.onnx";
const N: usize = 960;
const HOP: usize = 480;
const BINS: usize = N / 2 + 1;
/// The original sound kept under the cleaned one: −12 dB (what was chosen by ear).
pub const ATTENUATION_LIMIT_DB: f32 = 12.0;

/// On / off from the settings («Очистка звука нейросетью»).
static ON: AtomicBool = AtomicBool::new(false);

pub fn set_enabled(on: bool) { ON.store(on, Ordering::Relaxed); }
pub fn enabled() -> bool { ON.load(Ordering::Relaxed) }

pub struct Denoiser {
    session: Session,
    state0: Vec<f32>,
    window: Vec<f32>,
    fwd: Arc<dyn Fft<f32>>,
    inv: Arc<dyn Fft<f32>>,
}

fn meta_num(session: &Session, key: &str) -> Result<f64> {
    let m = session.metadata()?;
    let v = m.custom(key).with_context(|| format!("denoiser: no {key}"))?;
    v.trim().parse::<f64>().with_context(|| format!("denoiser: {key} = {v}"))
}

fn meta_list(session: &Session, key: &str) -> Result<Vec<f32>> {
    let m = session.metadata()?;
    let v = m.custom(key).with_context(|| format!("denoiser: no {key}"))?;
    v.split(',').map(|s| s.trim().parse::<f32>().with_context(|| format!("denoiser: {key}"))).collect()
}

/// The Vorbis window (sherpa-onnx `MakeVorbisWindow`).
pub fn vorbis(n: usize) -> Vec<f32> {
    let half = n as f64 / 2.0;
    (0..n).map(|i| {
        let s = (0.5 * std::f64::consts::PI * (i as f64 + 0.5) / half).sin();
        (0.5 * std::f64::consts::PI * s * s).sin() as f32
    }).collect()
}

impl Denoiser {
    pub fn load(path: &Path, threads: usize) -> Result<Self> {
        let t = std::time::Instant::now();
        let session = crate::helper::session_builder(ort::session::builder::GraphOptimizationLevel::Level3, false, threads.max(1), 0)?
            .commit_from_file(path).with_context(|| format!("load {}", path.display()))?;
        anyhow::ensure!(meta_num(&session, "sample_rate")? as u32 == RATE
            && meta_num(&session, "n_fft")? as usize == N
            && meta_num(&session, "hop_length")? as usize == HOP, "denoiser: unexpected model");
        let size = meta_num(&session, "state_size")? as usize;
        let erb_size = meta_num(&session, "erb_norm_state_size")? as usize;
        let erb = meta_list(&session, "erb_norm_init")?;
        let spec = meta_list(&session, "spec_norm_init")?;
        anyhow::ensure!(erb.len() <= erb_size && erb_size + spec.len() <= size, "denoiser: bad state layout");
        let mut state0 = vec![0f32; size];
        state0[..erb.len()].copy_from_slice(&erb);
        state0[erb_size..erb_size + spec.len()].copy_from_slice(&spec);
        let mut planner = FftPlanner::<f32>::new();
        let (fwd, inv) = (planner.plan_fft_forward(N), planner.plan_fft_inverse(N));
        log::info!("load denoiser: {} ms", t.elapsed().as_millis());
        Ok(Denoiser { session, state0, window: vorbis(N), fwd, inv })
    }

    /// Cleaned speech, 48 kHz in and out, the same length.
    pub fn run(&mut self, x: &[f32], attenuation_db: f32) -> Result<Vec<f32>> {
        let len = x.len();
        if len < N { return Ok(x.to_vec()); }
        // the network answers 4 hops late: room after the end, so the last of the speech is not lost
        let mut src = x.to_vec();
        src.extend(std::iter::repeat(0f32).take(2 * N));
        // centred frames: half a window reflected at each edge
        let half = N / 2;
        let n = src.len();
        let mut p = Vec::with_capacity(n + N);
        for i in (1..=half).rev() { p.push(src[i]); }
        p.extend_from_slice(&src);
        for i in 0..half { p.push(src[n - 2 - i]); }
        let frames = 1 + (p.len() - N) / HOP;

        let mut noisy = vec![Complex32::new(0.0, 0.0); frames * BINS];
        let mut clean = vec![Complex32::new(0.0, 0.0); frames * BINS];
        let mut buf = vec![Complex32::new(0.0, 0.0); N];
        let mut state = self.state0.clone();
        let mut spec_in = vec![0f32; BINS * 2];
        for f in 0..frames {
            for k in 0..N { buf[k] = Complex32::new(p[f * HOP + k] * self.window[k], 0.0); }
            self.fwd.process(&mut buf);
            for k in 0..BINS {
                noisy[f * BINS + k] = buf[k];
                spec_in[2 * k] = buf[k].re;
                spec_in[2 * k + 1] = buf[k].im;
            }
            let out = self.session.run(ort::inputs![
                "spec" => Tensor::from_array(([1usize, 1, BINS, 2], spec_in.clone()))?,
                "state_in" => Tensor::from_array(([state.len()], state))?
            ])?;
            let (_, e) = out["spec_e"].try_extract_tensor::<f32>()?;
            for k in 0..BINS { clean[f * BINS + k] = Complex32::new(e[2 * k], e[2 * k + 1]); }
            let (_, s) = out["state_out"].try_extract_tensor::<f32>()?;
            state = s.to_vec();
        }
        // the attenuation limit: some of the original (4 frames earlier, where the network's answer
        // for it lies) under the cleaned sound
        if attenuation_db > 0.0 && attenuation_db.is_finite() {
            let a = 10f32.powf(-attenuation_db / 20.0);
            for f in 0..frames {
                for k in 0..BINS {
                    let orig = if f >= 4 { noisy[(f - 4) * BINS + k] } else { Complex32::new(0.0, 0.0) };
                    clean[f * BINS + k] = orig * a + clean[f * BINS + k] * (1.0 - a);
                }
            }
        }
        // inverse: each frame's full spectrum (mirrored), windowed again, overlapped and divided by
        // the summed squared window
        let total = N + (frames - 1) * HOP;
        let mut y = vec![0f32; total];
        let mut den = vec![0f32; total];
        for f in 0..frames {
            for k in 0..BINS { buf[k] = clean[f * BINS + k]; }
            buf[0].im = 0.0;
            buf[N / 2].im = 0.0;
            for k in 1..N / 2 { buf[N - k] = buf[k].conj(); }
            self.inv.process(&mut buf);
            for k in 0..N {
                let w = self.window[k];
                y[f * HOP + k] += buf[k].re / N as f32 * w;
                den[f * HOP + k] += w * w;
            }
        }
        for i in 0..total { if den[i] > 0.0 { y[i] /= den[i]; } }
        // the centring undone, then 2 windows earlier (4 hops): back in time with the input
        let shift = 2 * N;
        let start = half + shift;
        let mut out: Vec<f32> = y.get(start..).map(|s| s.to_vec()).unwrap_or_default();
        out.resize(len, 0.0);
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vorbis_window_is_power_complementary() {
        // with hop N/2 the squared windows add up to 1 (what makes the overlap-add exact)
        let w = vorbis(N);
        for i in 0..HOP {
            let s = w[i] * w[i] + w[i + HOP] * w[i + HOP];
            assert!((s - 1.0).abs() < 1e-5, "{i}: {s}");
        }
    }

    /// DENOISER=…/dpdfnet2_48khz_hr.onnx IN=… (48 kHz f32 raw) OUT=… ORT_DYLIB_PATH=…
    /// cargo test --release --lib denoise_file -- --ignored --nocapture
    #[test]
    #[ignore]
    fn denoise_file() {
        let Ok(m) = std::env::var("DENOISER") else { return };
        let input = std::fs::read(std::env::var("IN").unwrap()).unwrap();
        let x: Vec<f32> = input.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect();
        let mut d = Denoiser::load(Path::new(&m), 1).unwrap();
        let t = std::time::Instant::now();
        let y = d.run(&x, ATTENUATION_LIMIT_DB).unwrap();
        eprintln!("{:.2} s of sound in {:.2} s", x.len() as f32 / RATE as f32, t.elapsed().as_secs_f32());
        assert_eq!(y.len(), x.len());
        let bytes: Vec<u8> = y.iter().flat_map(|v| v.to_le_bytes()).collect();
        std::fs::write(std::env::var("OUT").unwrap(), bytes).unwrap();
    }
}
