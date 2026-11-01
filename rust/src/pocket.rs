//! Shtorm PocketTTS RU (ArtShtorm, CC BY 4.0; based on Kyutai Pocket TTS) — the second engine.
//!
//! The model writes speech frame by frame (80 ms each) like a language model: a transformer
//! (`flow_lm_main`, with its key/value memory passed in and out as state) gives a conditioning
//! vector and an end-of-speech score; one flow step (`flow_lm_flow`) turns noise into the next
//! 32-number latent; the Mimi decoder (`mimi_decoder`, stateful too) turns latents into 24 kHz
//! audio. The voice is a short recording (`mimi_encoder` makes its conditioning). Stress: the
//! model was trained on text with U+0301 after the stressed vowel and follows it.
//!
//! The generation loop follows PocketTTS.cpp (VolgaGerm, MIT) — the runtime MyTTS uses on
//! Android; the tokenizer is SentencePiece BPE (`tokenizer.model`), reimplemented here.

use anyhow::{Context, Result};
use ndarray::{Array, Array3, IxDyn};
use ort::session::{Session, SessionInputValue};
use ort::tensor::TensorElementType;
use ort::value::{DynValue, Tensor, ValueType};
use rand_distr::{Distribution, Normal};
use std::borrow::Cow;
use std::collections::HashMap;
use std::path::Path;
use unicode_normalization::UnicodeNormalization;

/// The model's own sample rate.
pub const POCKET_RATE: u32 = 24_000;
/// Text per call, characters (the author: 120–180; longer pieces fade in volume).
pub const POCKET_MAX_CHUNK_CHARS: usize = 150;

/// Liveliness from the settings slider: the noise of each frame (0.3 as MyTTS; lower is steadier,
/// higher is livelier but stumbles more).
static TEMPERATURE_BITS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0x3E99_999A); // 0.3

pub fn set_temperature(v: f32) {
    let v = if v.is_finite() { v.clamp(0.05, 1.0) } else { 0.3 };
    TEMPERATURE_BITS.store(v.to_bits(), std::sync::atomic::Ordering::Relaxed);
}

pub fn temperature() -> f32 {
    f32::from_bits(TEMPERATURE_BITS.load(std::sync::atomic::Ordering::Relaxed))
}

/// Brightness from the settings slider: dB added above ~3 kHz. Shtorm's codec (24 kHz) softens the
/// highs, and an own voice came out "глуховато"; +8 dB gave it back its clarity (tolyos, by ear).
static BRIGHTNESS_BITS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0x4100_0000); // 8.0

pub fn set_brightness(db: f32) {
    let v = if db.is_finite() { db.clamp(0.0, 12.0) } else { 8.0 };
    BRIGHTNESS_BITS.store(v.to_bits(), std::sync::atomic::Ordering::Relaxed);
}

pub fn brightness() -> f32 {
    f32::from_bits(BRIGHTNESS_BITS.load(std::sync::atomic::Ordering::Relaxed))
}

/// A high shelf (RBJ cookbook, S = 1): [gain_db] above [f0] Hz, the lows as they are.
pub fn high_shelf(x: &mut [f32], gain_db: f32, f0: f32, fs: f32) {
    if gain_db.abs() < 0.05 || x.is_empty() { return; }
    let a = 10f64.powf(gain_db as f64 / 40.0);
    let w0 = 2.0 * std::f64::consts::PI * f0 as f64 / fs as f64;
    let (cw, sw) = (w0.cos(), w0.sin());
    let alpha = sw / 2.0 * 2f64.sqrt();
    let sa = 2.0 * a.sqrt() * alpha;
    let b0 = a * ((a + 1.0) + (a - 1.0) * cw + sa);
    let b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw);
    let b2 = a * ((a + 1.0) + (a - 1.0) * cw - sa);
    let a0 = (a + 1.0) - (a - 1.0) * cw + sa;
    let a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw);
    let a2 = (a + 1.0) - (a - 1.0) * cw - sa;
    let (b0, b1, b2, a1, a2) = (b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    let (mut x1, mut x2, mut y1, mut y2) = (0f64, 0f64, 0f64, 0f64);
    for v in x.iter_mut() {
        let xi = *v as f64;
        let y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = xi; y2 = y1; y1 = y;
        *v = y as f32;
    }
    // the lifted highs must not clip: a phrase whose peaks went over is brought down as a whole
    let peak = x.iter().fold(0f32, |m, v| m.max(v.abs()));
    if peak > 0.98 { let k = 0.98 / peak; for v in x.iter_mut() { *v *= k; } }
}

// ---- SentencePiece BPE ----------------------------------------------------------------------

/// SentencePiece BPE (model type 2) as `spm.encode` does it: NFKC, spaces as '▁' with one in
/// front, then the highest-scored merge of two neighbours again and again (leftmost on a tie).
pub struct Bpe {
    ids: HashMap<String, (u32, f32)>,
    unk: u32,
}

impl Bpe {
    pub fn load(path: &Path) -> Result<Self> {
        let bytes = std::fs::read(path).with_context(|| format!("tokenizer {}", path.display()))?;
        let mut ids = HashMap::new();
        let mut unk = 0u32;
        let mut index = 0u32;
        let mut p = 0usize;
        // ModelProto: field 1 = repeated SentencePiece {1: piece, 2: score (float), 3: type}
        while p < bytes.len() {
            let (key, np) = varint(&bytes, p)?;
            p = np;
            let (field, wire) = (key >> 3, key & 7);
            match wire {
                0 => { p = varint(&bytes, p)?.1; }
                1 => { p += 8; }
                5 => { p += 4; }
                2 => {
                    let (len, np) = varint(&bytes, p)?;
                    let (start, end) = (np, np + len as usize);
                    anyhow::ensure!(end <= bytes.len(), "tokenizer: truncated");
                    if field == 1 {
                        let (piece, score, kind) = piece_of(&bytes[start..end])?;
                        if kind == 2 { unk = index; }
                        // normal pieces (1) take part in merges; control/unknown ones never match text
                        if kind == 1 { ids.insert(piece, (index, score)); }
                        index += 1;
                    }
                    p = end;
                }
                _ => anyhow::bail!("tokenizer: wire type {wire}"),
            }
        }
        anyhow::ensure!(!ids.is_empty(), "tokenizer: no pieces");
        Ok(Bpe { ids, unk })
    }

    /// The normalisation of `nmt_nfkc` with add_dummy_prefix / remove_extra_whitespaces.
    pub fn normalize(text: &str) -> String {
        let t: String = text.nfkc().map(|c| if c.is_whitespace() { ' ' } else { c }).collect();
        let words: Vec<&str> = t.split(' ').filter(|w| !w.is_empty()).collect();
        let mut out = String::with_capacity(t.len() + 8);
        for w in words {
            out.push('▁');
            out.push_str(w);
        }
        out
    }

    pub fn encode(&self, text: &str) -> Vec<i64> {
        let norm = Self::normalize(text);
        let mut sym: Vec<String> = norm.chars().map(|c| c.to_string()).collect();
        loop {
            let mut best: Option<(usize, f32)> = None;
            for i in 0..sym.len().saturating_sub(1) {
                let joined = format!("{}{}", sym[i], sym[i + 1]);
                if let Some(&(_, score)) = self.ids.get(&joined) {
                    if best.map_or(true, |(_, s)| score > s) {
                        best = Some((i, score));
                    }
                }
            }
            let Some((i, _)) = best else { break };
            let right = sym.remove(i + 1);
            sym[i].push_str(&right);
        }
        // as SentencePiece: a run of unknown pieces becomes one <unk>
        let mut out: Vec<i64> = Vec::with_capacity(sym.len());
        for s in &sym {
            let id = self.ids.get(s).map_or(self.unk, |&(id, _)| id) as i64;
            if id == self.unk as i64 && out.last() == Some(&(self.unk as i64)) { continue; }
            out.push(id);
        }
        out
    }
}

fn varint(b: &[u8], mut p: usize) -> Result<(u64, usize)> {
    let mut v = 0u64;
    let mut shift = 0;
    loop {
        anyhow::ensure!(p < b.len() && shift < 64, "tokenizer: bad varint");
        let byte = b[p];
        p += 1;
        v |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 { return Ok((v, p)); }
        shift += 7;
    }
}

fn piece_of(b: &[u8]) -> Result<(String, f32, u64)> {
    let (mut piece, mut score, mut kind) = (String::new(), 0f32, 1u64);
    let mut p = 0;
    while p < b.len() {
        let (key, np) = varint(b, p)?;
        p = np;
        match (key >> 3, key & 7) {
            (1, 2) => {
                let (len, np) = varint(b, p)?;
                piece = String::from_utf8_lossy(&b[np..np + len as usize]).into_owned();
                p = np + len as usize;
            }
            (2, 5) => { score = f32::from_le_bytes([b[p], b[p + 1], b[p + 2], b[p + 3]]); p += 4; }
            (3, 0) => { let (v, np) = varint(b, p)?; kind = v; p = np; }
            (_, 0) => { p = varint(b, p)?.1; }
            (_, 1) => { p += 8; }
            (_, 5) => { p += 4; }
            (_, 2) => { let (len, np) = varint(b, p)?; p = np + len as usize; }
            (_, w) => anyhow::bail!("tokenizer: wire type {w}"),
        }
    }
    Ok((piece, score, kind))
}

// ---- stateful graphs ------------------------------------------------------------------------

/// The `state_*` inputs of a graph and the `out_state_*` outputs that replace them.
struct States {
    names: Vec<String>,
    init: Vec<(TensorElementType, Vec<usize>)>,
    values: Vec<DynValue>,
}

impl States {
    fn of(session: &Session) -> Result<Self> {
        let mut names = Vec::new();
        let mut init = Vec::new();
        for i in session.inputs() {
            if !i.name().starts_with("state_") { continue; }
            let ValueType::Tensor { ty, shape, .. } = i.dtype() else { anyhow::bail!("{}: not a tensor", i.name()) };
            names.push(i.name().to_string());
            init.push((*ty, shape.iter().map(|&d| if d > 0 { d as usize } else { 0 }).collect()));
        }
        let mut s = States { names, init, values: Vec::new() };
        s.reset()?;
        Ok(s)
    }

    fn reset(&mut self) -> Result<()> {
        self.values.clear();
        for (ty, shape) in &self.init {
            let n: usize = shape.iter().product();
            let sh = IxDyn(shape);
            let v: DynValue = match ty {
                TensorElementType::Float32 => Tensor::from_array(Array::from_shape_vec(sh, vec![0f32; n])?)?.into_dyn(),
                TensorElementType::Float16 => Tensor::from_array(Array::from_shape_vec(sh, vec![half::f16::ZERO; n])?)?.into_dyn(),
                TensorElementType::Int64 => Tensor::from_array(Array::from_shape_vec(sh, vec![0i64; n])?)?.into_dyn(),
                TensorElementType::Bool => Tensor::from_array(Array::from_shape_vec(sh, vec![false; n])?)?.into_dyn(),
                other => anyhow::bail!("state type {other:?} not supported"),
            };
            self.values.push(v);
        }
        Ok(())
    }

    /// Runs [session] with [inputs] plus the states; the new states are kept, the other
    /// outputs (in graph order) returned as float vectors.
    fn run(&mut self, session: &mut Session, inputs: Vec<(String, DynValue)>) -> Result<Vec<Vec<f32>>> {
        let mut feed: Vec<(Cow<str>, SessionInputValue)> = inputs.into_iter()
            .map(|(k, v)| (Cow::Owned(k), SessionInputValue::from(v))).collect();
        for (name, v) in self.names.iter().zip(self.values.drain(..)) {
            feed.push((Cow::Owned(name.clone()), SessionInputValue::from(v)));
        }
        let out_names: Vec<String> = session.outputs().iter().map(|o| o.name().to_string()).collect();
        let mut outputs = session.run(feed)?;
        let mut plain = Vec::new();
        let mut states = Vec::with_capacity(self.names.len());
        for name in &out_names {
            let v = outputs.remove(name.as_str()).with_context(|| format!("no output {name}"))?;
            if name.starts_with("out_state_") {
                states.push(v);
            } else {
                plain.push(v.try_extract_tensor::<f32>()?.1.to_vec());
            }
        }
        anyhow::ensure!(states.len() == self.names.len(), "state count {} != {}", states.len(), self.names.len());
        self.values = states;
        Ok(plain)
    }
}

fn f32_tensor(shape: Vec<usize>, data: Vec<f32>) -> Result<DynValue> {
    Ok(Tensor::from_array(Array::from_shape_vec(IxDyn(&shape), data)?)?.into_dyn())
}

// ---- the engine -----------------------------------------------------------------------------

pub struct Pocket {
    encoder: Session,
    text: Session,
    main: Session,
    flow: Session,
    decoder: Session,
    main_states: States,
    decoder_states: States,
    bpe: Bpe,
    voices: HashMap<String, (std::time::SystemTime, Array3<f32>)>,
    /// Noise of each frame: MyTTS settles on 0.3 (the upstream default 0.7 is livelier, less steady).
    pub temperature: f32,
    pub eos_threshold: f32,
}

/// The frames a phrase may take when read normally: its letters at 11 a second (book prose is
/// read at 13–25) plus a second, 12.5 frames a second.
fn speech_limit_frames(prepared: &str) -> usize {
    let letters = prepared.trim().chars().filter(|&c| c != '\u{301}').count();
    ((letters as f32 / 11.0 + 1.0) * 12.5).ceil() as usize
}

/// Is [dir] a Shtorm model folder?
pub fn is_pocket_dir(dir: &str) -> bool {
    Path::new(dir).join(format!("{MAIN_INT8}.onnx")).exists() || Path::new(dir).join("flow_lm_main.onnx").exists()
}

/// The main graph with its weights in 8 bits (dynamic quantization, 76 MB instead of 302): reading
/// them from memory for every frame is what holds Shtorm back on phones. Not told apart by ear.
const MAIN_INT8: &str = "flow_lm_main_int8";

impl Pocket {
    pub fn load(dir: &str, threads: usize) -> Result<Self> {
        let open = |name: &str| -> Result<Session> {
            let path = Path::new(dir).join(format!("{name}.onnx"));
            let t = std::time::Instant::now();
            // no optimised copy on disk (the main graph alone is 300 MB)
            let s = crate::helper::session_builder(ort::session::builder::GraphOptimizationLevel::Level3, false, threads, 0)?
                .commit_from_file(&path).with_context(|| format!("load {}", path.display()))?;
            log::info!("load {name}: {} ms", t.elapsed().as_millis());
            Ok(s)
        };
        let (encoder, text, main, flow, decoder) = (open("mimi_encoder")?, open("text_conditioner")?,
            open(if Path::new(dir).join(format!("{MAIN_INT8}.onnx")).exists() { MAIN_INT8 } else { "flow_lm_main" })?,
            open("flow_lm_flow")?, open("mimi_decoder")?);
        let main_states = States::of(&main)?;
        let decoder_states = States::of(&decoder)?;
        let bpe = Bpe::load(&Path::new(dir).join("tokenizer.model"))?;
        Ok(Pocket { encoder, text, main, flow, decoder, main_states, decoder_states, bpe,
            voices: HashMap::new(), temperature: 0.3, eos_threshold: -4.0 })
    }

    /// The voice's conditioning from its recording (first 30 s; cached until the file changes).
    fn voice(&mut self, path: &str) -> Result<Array3<f32>> {
        let stamp = std::fs::metadata(path).and_then(|m| m.modified()).with_context(|| format!("voice {path}"))?;
        if let Some((s, c)) = self.voices.get(path) {
            if *s == stamp { return Ok(c.clone()); }
        }
        let audio = read_voice(path)?;
        let n = audio.len();
        let out = self.encoder.run(ort::inputs!{ "audio" => Tensor::from_array(Array::from_shape_vec((1, 1, n), audio)?)? })?;
        let (shape, data) = out["conditioning"].try_extract_tensor::<f32>()?;
        let dims: Vec<usize> = shape.iter().map(|&d| d as usize).collect();
        let frames = dims[dims.len() - 2];
        let c = Array3::from_shape_vec((1, frames, 1024), data.to_vec())?;
        drop(out);
        if self.voices.len() > 16 { self.voices.clear(); }
        self.voices.insert(path.to_string(), (stamp, c.clone()));
        Ok(c)
    }

    /// The text as the model was trained on (PocketTTS.cpp `prepare_text`): no quotes, a capital
    /// first letter, a final stop; under five words padded with 8 spaces. Returns the frames to
    /// let it say after it signals the end (5 for a short reply, 3 otherwise).
    pub fn prepare(text: &str) -> (String, usize) {
        let t: String = text.chars().filter(|c| !matches!(c, '"' | '`' | '“' | '”' | '«' | '»' | '„')).collect();
        let t = t.split_whitespace().collect::<Vec<_>>().join(" ");
        let words = t.split_whitespace().count();
        let mut t = t;
        if let Some(c) = t.chars().next() {
            if c.is_lowercase() {
                let up: String = c.to_uppercase().collect();
                t = up + &t[c.len_utf8()..];
            }
        }
        if t.chars().last().map_or(false, |c| c.is_alphanumeric() || c == '\u{301}') {
            t.push('.');
        }
        let extra = if words <= 4 { 5 } else { 3 };
        if words < 5 {
            t = format!("        {t}");
        }
        (t, extra)
    }

    /// Speech for [text] (stress as U+0301) in the voice of the recording [voice], 24 kHz.
    pub fn say(&mut self, text: &str, voice: &str) -> Result<Vec<f32>> {
        let (prepared, eos_extra) = Self::prepare(text);
        let ids = self.bpe.encode(&prepared);
        anyhow::ensure!(!ids.is_empty(), "empty text");
        let n = ids.len();
        let emb = {
            let out = self.text.run(ort::inputs!{ "token_ids" => Tensor::from_array(Array::from_shape_vec((1, n), ids)?)? })?;
            out["embeddings"].try_extract_tensor::<f32>()?.1.to_vec()
        };
        let cond_voice = self.voice(voice)?;
        // at most what the text could need (upstream: 3 tokens a second + 2 s), 12.5 frames a second
        let max_frames = (((n as f32 / 3.0) + 2.0) * 12.5).ceil() as usize;
        let limit = speech_limit_frames(&prepared).min(max_frames);

        // Now and then the end-of-speech signal comes close but does not cross the threshold:
        // the model then fills the time left by repeating the last words. Read normally, a phrase
        // ends well before [limit] (book prose: 13–25 letters a second); if it has not, it is
        // said again with other noise — that one almost always ends. The second try is taken
        // as it is (no worse than before).
        let mut latents = Vec::new();
        for attempt in 0..2 {
            let cap = if attempt == 0 { limit + eos_extra + 1 } else { max_frames };
            let (l, ended) = self.generate(&cond_voice, &emb, n, cap, eos_extra)?;
            latents = l;
            if ended { break; }
            if attempt == 0 {
                log::info!("Shtorm: no end of speech in {:.1} s, saying it again: {}", limit as f32 / 12.5,
                    text.chars().take(60).collect::<String>());
            }
        }
        // the decoder, 15 frames at a time (as the streaming runtime does)
        self.decoder_states.reset()?;
        let frames = latents.len() / 32;
        let mut audio = Vec::with_capacity(frames * 1920);
        for start in (0..frames).step_by(15) {
            let k = (frames - start).min(15);
            let chunk = latents[start * 32..(start + k) * 32].to_vec();
            let out = self.decoder_states.run(&mut self.decoder, vec![("latent".into(), f32_tensor(vec![1, k, 32], chunk)?)])?;
            audio.extend_from_slice(&out[0]);
        }
        Ok(audio)
    }

    /// The latent frames of one try, at most [cap]; true if the model ended the speech itself.
    fn generate(&mut self, voice: &Array3<f32>, emb: &[f32], n: usize, cap: usize, eos_extra: usize) -> Result<(Vec<f32>, bool)> {
        self.main_states.reset()?;
        let empty_seq = || f32_tensor(vec![1, 0, 32], Vec::new());
        // voice, then text, into the transformer's memory
        let vf = voice.shape()[1];
        self.main_states.run(&mut self.main, vec![("sequence".into(), empty_seq()?),
            ("text_embeddings".into(), f32_tensor(vec![1, vf, 1024], voice.iter().copied().collect())?)])?;
        self.main_states.run(&mut self.main, vec![("sequence".into(), empty_seq()?),
            ("text_embeddings".into(), f32_tensor(vec![1, n, 1024], emb.to_vec())?)])?;

        let normal = Normal::new(0.0f32, self.temperature.max(0.0).sqrt()).unwrap();
        let mut rng = rand::thread_rng();
        let mut prev = vec![f32::NAN; 32]; // the first frame starts from "nothing"
        let mut latents: Vec<f32> = Vec::with_capacity(cap * 32);
        let (mut eos, mut extra) = (false, 0usize);
        for _ in 0..cap {
            let out = self.main_states.run(&mut self.main, vec![("sequence".into(), f32_tensor(vec![1, 1, 32], prev.clone())?),
                ("text_embeddings".into(), f32_tensor(vec![1, 0, 1024], Vec::new())?)])?;
            let (cond, eos_logit) = (&out[0], out[1][0]);
            if !eos && eos_logit > self.eos_threshold { eos = true; }
            if eos {
                extra += 1;
                if extra > eos_extra { break; }
            }
            // one flow step from noise (s = 0 → t = 1)
            let x: Vec<f32> = (0..32).map(|_| if self.temperature > 0.0 { normal.sample(&mut rng) } else { 0.0 }).collect();
            let flow = self.flow.run(ort::inputs!{
                "c" => Tensor::from_array(Array::from_shape_vec(1024, cond.clone())?)?,
                "s" => Tensor::from_array(Array::from_shape_vec((1, 1), vec![0f32])?)?,
                "t" => Tensor::from_array(Array::from_shape_vec((1, 1), vec![1f32])?)?,
                "x" => Tensor::from_array(Array::from_shape_vec((1, 32), x.clone())?)?
            })?;
            let dir = flow["flow_dir"].try_extract_tensor::<f32>()?.1.to_vec();
            prev = x.iter().zip(&dir).map(|(a, d)| a + d).collect();
            latents.extend_from_slice(&prev);
        }
        Ok((latents, eos))
    }
}

/// A voice recording: 16-bit or float WAV, any rate and channels → mono 24 kHz, first 30 s.
pub fn read_voice(path: &str) -> Result<Vec<f32>> {
    let mut r = hound::WavReader::open(path).with_context(|| format!("voice {path}"))?;
    let spec = r.spec();
    let ch = spec.channels.max(1) as usize;
    let raw: Vec<f32> = match spec.sample_format {
        hound::SampleFormat::Float => r.samples::<f32>().collect::<std::result::Result<_, _>>()?,
        hound::SampleFormat::Int => {
            let scale = 1.0 / (1u64 << (spec.bits_per_sample - 1)) as f32;
            r.samples::<i32>().map(|s| s.map(|v| v as f32 * scale)).collect::<std::result::Result<_, _>>()?
        }
    };
    let mono: Vec<f32> = raw.chunks(ch).map(|c| c.iter().sum::<f32>() / ch as f32).collect();
    let x = if spec.sample_rate == POCKET_RATE { mono } else { resample(&mono, spec.sample_rate, POCKET_RATE) };
    Ok(x[..x.len().min(30 * POCKET_RATE as usize)].to_vec())
}

/// Windowed-sinc resampling (16 taps each side, Hann), cut-off below the lower Nyquist.
pub fn resample(x: &[f32], from: u32, to: u32) -> Vec<f32> {
    if from == to || x.is_empty() { return x.to_vec(); }
    let ratio = to as f64 / from as f64;
    let cutoff = ratio.min(1.0) * 0.95;
    const HALF: i64 = 16;
    let n_out = (x.len() as f64 * ratio).round() as usize;
    let mut y = Vec::with_capacity(n_out);
    let span = (HALF as f64 / cutoff).ceil() as i64;
    for n in 0..n_out {
        let t = n as f64 / ratio;
        let c = t.floor() as i64;
        let mut acc = 0f64;
        let mut norm = 0f64;
        for k in (c - span + 1)..=(c + span) {
            let d = t - k as f64;
            let arg = d * cutoff;
            if arg.abs() >= HALF as f64 { continue; }
            let sinc = if arg.abs() < 1e-9 { 1.0 } else { (std::f64::consts::PI * arg).sin() / (std::f64::consts::PI * arg) };
            let w = 0.5 + 0.5 * (std::f64::consts::PI * arg / HALF as f64).cos();
            let h = sinc * w;
            norm += h;
            if k >= 0 && (k as usize) < x.len() { acc += x[k as usize] as f64 * h; }
        }
        y.push(if norm.abs() > 1e-9 { (acc / norm) as f32 } else { 0.0 });
    }
    y
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speech_limit() {
        // 54 characters: 4.9 s + 1 s → 74 frames; stress marks and the padding do not count
        assert_eq!(speech_limit_frames("Она открыла окно посмотрела на улицу и тихо вздохнула."), 74);
        assert_eq!(speech_limit_frames("        Ну?"), speech_limit_frames("Ну?"));
        assert_eq!(speech_limit_frames("Он у\u{301}шёл."), speech_limit_frames("Он ушёл."));
    }

    #[test]
    fn high_shelf_lifts_the_highs_only() {
        let fs = 24_000f32;
        // quiet tones (0.1): the lifted 9 kHz one stays below the clip guard
        let tone = |f: f32| -> Vec<f32> { (0..fs as usize).map(|i| 0.1 * (2.0 * std::f32::consts::PI * f * i as f32 / fs).sin()).collect() };
        let rms = |x: &[f32]| -> f32 { let t = &x[4000..]; 10.0 * (t.iter().map(|v| v * v).sum::<f32>() / t.len() as f32).sqrt() * 2f32.sqrt() };
        let mut low = tone(200.0);
        high_shelf(&mut low, 8.0, 3000.0, fs);
        assert!((rms(&low) - 1.0).abs() < 0.05, "200 Hz kept: {}", rms(&low));
        let mut high = tone(9000.0);
        high_shelf(&mut high, 8.0, 3000.0, fs);
        let db = 20.0 * rms(&high).log10();
        assert!((db - 8.0).abs() < 1.0, "9 kHz lifted by 8 dB: {db}");
        // a loud one is brought down as a whole, not clipped
        let mut loud: Vec<f32> = tone(9000.0).iter().map(|v| v * 9.0).collect();
        high_shelf(&mut loud, 8.0, 3000.0, fs);
        assert!(loud.iter().all(|v| v.abs() <= 0.981));
        set_brightness(f32::NAN);
        assert_eq!(brightness(), 8.0);
    }

    #[test]
    fn temperature_is_clamped() {
        set_temperature(2.0);
        assert_eq!(temperature(), 1.0);
        set_temperature(f32::NAN);
        assert!((temperature() - 0.3).abs() < 1e-6);
        set_temperature(0.3);
    }

    #[test]
    fn prepare_like_upstream() {
        assert_eq!(Pocket::prepare("ну?"), ("        Ну?".to_string(), 5));
        assert_eq!(Pocket::prepare("«Он у\u{301}лыбнулся» и кивну\u{301}л, вышел из ко\u{301}мнаты").0,
            "Он у\u{301}лыбнулся и кивну\u{301}л, вышел из ко\u{301}мнаты.");
        assert_eq!(Pocket::prepare("Он ушёл, а мы остались ждать").1, 3);
    }

    #[test]
    fn resample_keeps_a_tone() {
        let x: Vec<f32> = (0..24000).map(|i| (2.0 * std::f32::consts::PI * 440.0 * i as f32 / 24000.0).sin()).collect();
        let y = resample(&x, 24000, 44100);
        assert_eq!(y.len(), 44100);
        // zero crossings of a 440 Hz tone over a second: ~880
        let z = y.windows(2).filter(|w| w[0] <= 0.0 && w[1] > 0.0).count();
        assert!((z as i32 - 440).abs() <= 2, "{z}");
        let peak = y[1000..43000].iter().cloned().fold(0f32, |a, b| a.max(b.abs()));
        assert!((peak - 1.0).abs() < 0.02, "{peak}");
    }

    #[test]
    fn normalize_spaces() {
        assert_eq!(Bpe::normalize("  Он  ушёл\n"), "▁Он▁ушёл");
    }
}

#[cfg(test)]
mod real_model {
    use super::*;
    fn dir() -> Option<String> { std::env::var("SHTORM_DIR").ok() }

    /// SHTORM_DIR=… TOK_CORPUS=… TOK_EXPECT=… cargo test --lib tokenizer_matches -- --ignored
    #[test]
    #[ignore]
    fn tokenizer_matches_sentencepiece() {
        let Some(d) = dir() else { return };
        let bpe = Bpe::load(&Path::new(&d).join("tokenizer.model")).unwrap();
        let corpus = std::fs::read_to_string(std::env::var("TOK_CORPUS").unwrap()).unwrap();
        let expect = std::fs::read_to_string(std::env::var("TOK_EXPECT").unwrap()).unwrap();
        let (mut ok, mut bad) = (0, 0);
        for (line, exp) in corpus.lines().zip(expect.lines()) {
            let text = line.replace("\\n", "\n").replace("\\t", "\t");
            let got: Vec<String> = bpe.encode(&text).iter().map(|i| i.to_string()).collect();
            if got.join(" ") == exp { ok += 1 } else {
                bad += 1;
                if bad <= 5 { eprintln!("MISMATCH {text:?}\n  got {}\n  exp {exp}", got.join(" ")); }
            }
        }
        eprintln!("tokenizer: {ok} match, {bad} differ");
        assert_eq!(bad, 0);
    }

    /// SHTORM_DIR=… ORT_DYLIB_PATH=… cargo test --lib speaks -- --ignored --nocapture
    #[test]
    #[ignore]
    fn speaks() {
        let Some(d) = dir() else { return };
        let mut p = Pocket::load(&d, 4).unwrap();
        if std::env::var("TEMP0").is_ok() {
            p.temperature = 0.0;
            let a = p.say("Стре\u{301}лки часо\u{301}в показывали по\u{301}лночь.", &format!("{d}/alba.wav")).unwrap();
            let bytes: Vec<u8> = a.iter().flat_map(|v| v.to_le_bytes()).collect();
            std::fs::write(std::env::temp_dir().join("rust_t0.f32"), bytes).unwrap();
            return;
        }
        let voice = format!("{d}/alba.wav");
        for text in ["Стре\u{301}лки часо\u{301}в показывали по\u{301}лночь, а стрелки\u{301} засты\u{301}ли на поста\u{301}х.", "Ну? Хорошо\u{301}."] {
            let t = std::time::Instant::now();
            let a = p.say(text, &voice).unwrap();
            let secs = a.len() as f32 / POCKET_RATE as f32;
            let peak = a.iter().cloned().fold(0f32, |m, v| m.max(v.abs()));
            eprintln!("{secs:.2} s audio in {:.2} s, peak {peak:.2}", t.elapsed().as_secs_f32());
            assert!(secs > 0.5 && secs < 12.0 && peak > 0.05 && a.iter().all(|v| v.is_finite()));
            let mut w = hound::WavWriter::create(format!("{}/rust_{}.wav", std::env::temp_dir().display(), text.len()),
                hound::WavSpec { channels: 1, sample_rate: 44100, bits_per_sample: 16, sample_format: hound::SampleFormat::Int }).unwrap();
            for s in resample(&a, POCKET_RATE, 44100) { w.write_sample((s.clamp(-1.0, 1.0) * 32767.0) as i16).unwrap(); }
            w.finalize().unwrap();
        }
    }
}

#[cfg(test)]
mod engine_path {
    /// SHTORM_DIR=… ORT_DYLIB_PATH=… cargo test --release --lib through_engine -- --ignored --nocapture
    #[test]
    #[ignore]
    fn through_engine() {
        let Ok(d) = std::env::var("SHTORM_DIR") else { return };
        let mut tts = crate::helper::load_text_to_speech(&d, false, false, 4, 0).unwrap();
        assert_eq!(tts.kind, crate::helper::ModelKind::Pocket);
        tts.voice_path = format!("{d}/alba.wav");
        let style = crate::helper::Style { ttl: ndarray::Array3::zeros((1, 50, 256)), dp: ndarray::Array3::zeros((1, 8, 16)) };
        for speed in [1.0f32, 1.4] {
            let t = std::time::Instant::now();
            let (wav, dur) = tts.call("Стр+елки час+ов показ+ывали п+олночь. А стрелк+и заст+ыли на пост+ах, — сказал он, — и ждал+и рассв+ета!",
                "ru", &style, 8, speed, 0.02, |_, _, _| true).unwrap();
            eprintln!("speed {speed}: {dur:.2} s audio ({} samples at 44.1k) in {:.2} s", wav.len(), t.elapsed().as_secs_f32());
            assert!(dur > 1.5 && wav.iter().all(|v| v.is_finite()));
        }
    }
}
