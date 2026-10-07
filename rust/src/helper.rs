// ============================================================================ 
// TTS Helper Module - All utility functions and structures
// ============================================================================ 

use ndarray::{Array, Array3};
use serde::{Deserialize, Serialize};
use serde_json;
use std::fs::File;
use std::io::BufReader;
use std::path::Path;
use anyhow::{Result, Context};
use unicode_normalization::UnicodeNormalization;
use hound::{WavWriter, WavSpec, SampleFormat};
use rand_distr::{Distribution, Normal};
use regex::Regex;
use crate::tera;
use crate::homo::HomoData;

// ============================================================================ 
// Configuration Structures
// ============================================================================ 

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub ae: AEConfig,
    pub ttl: TTLConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AEConfig {
    pub sample_rate: i32,
    pub base_chunk_size: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TTLConfig {
    pub chunk_compress_factor: i32,
    pub latent_dim: i32,
}

/// Load configuration from JSON file
pub fn load_cfgs<P: AsRef<Path>>(onnx_dir: P) -> Result<Config> {
    let cfg_path = onnx_dir.as_ref().join("tts.json");
    let file = File::open(cfg_path)?;
    let reader = BufReader::new(file);
    let cfgs: Config = serde_json::from_reader(reader)?;
    Ok(cfgs)
}

// ============================================================================ 
// Voice Style Data Structure
// ============================================================================ 

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceStyleData {
    pub style_ttl: StyleComponent,
    pub style_dp: StyleComponent,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StyleComponent {
    pub data: Vec<Vec<Vec<f32>>>,
    pub dims: Vec<usize>,
    #[serde(rename = "type")]
    pub dtype: String,
}

// ============================================================================ 
// Unicode Text Processor
// ============================================================================ 

pub struct UnicodeProcessor {
    indexer: Vec<i64>,
}

impl UnicodeProcessor {
    pub fn table(&self) -> &[i64] {
        &self.indexer
    }

    pub fn new<P: AsRef<Path>>(unicode_indexer_json_path: P) -> Result<Self> {
        let file = File::open(unicode_indexer_json_path)?;
        let reader = BufReader::new(file);
        let indexer: Vec<i64> = serde_json::from_reader(reader)?;
        Ok(UnicodeProcessor { indexer })
    }

    pub fn call(&self, text_list: &[String], lang_list: &[String]) -> Result<(Vec<Vec<i64>>, Array3<f32>)> {
        let mut processed_texts: Vec<String> = Vec::new();
        for (text, lang) in text_list.iter().zip(lang_list.iter()) {
            processed_texts.push(preprocess_text(text, lang)?);
        }

        let text_ids_lengths: Vec<usize> = processed_texts
            .iter()
            .map(|t| t.chars().count())
            .collect();

        let max_len = *text_ids_lengths.iter().max().unwrap_or(&0);

        let mut text_ids = Vec::new();
        for text in &processed_texts {
            let mut row = vec![0i64; max_len];
            let unicode_vals = text_to_unicode_values(text);
            for (j, &val) in unicode_vals.iter().enumerate() {
                if val < self.indexer.len() {
                    let id = self.indexer[val];
                    row[j] = if id == -1 { 0 } else { id };
                } else {
                    row[j] = 0; // Default to space for unknown
                }
            }
            text_ids.push(row);
        }

        let text_mask = get_text_mask(&text_ids_lengths);

        Ok((text_ids, text_mask))
    }
}

pub fn preprocess_text(text: &str, lang: &str) -> Result<String> {
    // Revert to NFKD normalization as required for Korean Jamo decomposition
    let mut text: String = text.nfkd().collect();

    // ---- Safe-for-all-languages normalization ----
    // Dashes & typographic quotes confuse the multilingual model regardless of
    // language; in particular, an em-dash inside Russian or other Slavic text
    // is often pronounced as "и" because the raw U+2014 codepoint has no
    // pause semantics in unicode_indexer. Mapping it to a comma forces a
    // natural pause.
    let universal_replacements = [
        ("–", "-"),              // en dash
        ("‑", "-"),              // non-breaking hyphen
        ("—", ", "),             // em dash -> comma + space (natural pause)
        ("\u{2013}", "-"),       // explicit en dash (same as – above, kept for clarity)
        ("\u{2014}", ", "),      // explicit em dash
        ("\u{201C}", "\""),      // left double quote
        ("\u{201D}", "\""),      // right double quote
        ("\u{2018}", "'"),       // left single quote
        ("\u{2019}", "'"),       // right single quote (NB: also used as Cyrillic-Latin apostrophe)
        ("«", "\""),             // guillemet open
        ("»", "\""),             // guillemet close
    ];
    for (from, to) in &universal_replacements {
        text = text.replace(from, to);
    }

    if lang == "en" {
        // Remove emojis (wide Unicode range)
    let emoji_pattern = Regex::new(r"[\x{1F600}-\x{1F64F}\x{1F300}-\x{1F5FF}\x{1F680}-\x{1F6FF}\x{1F700}-\x{1F77F}\x{1F780}-\x{1F7FF}\x{1F800}-\x{1F8FF}\x{1F900}-\x{1F9FF}\x{1FA00}-\x{1FA6F}\x{1FA70}-\x{1FAFF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}]+").unwrap();
    text = emoji_pattern.replace_all(&text, "").to_string();

    // English-specific symbol replacements. Kept narrow so we don't drop
    // characters that might be meaningful in other scripts.
    let replacements = [
        ("_", " "),      // underscore
        ("´", "'"),      // acute accent (free-standing only — combining U+0301 over a vowel is left intact to convey stress)
        ("`", "'"),      // grave accent
        ("[", " "),      // left bracket
        ("]", " "),      // right bracket
        ("|", " "),      // vertical bar
        ("/", " "),      // slash
        ("#", " "),      // hash
        ("→", " "),      // right arrow
        ("←", " "),      // left arrow
    ];

    for (from, to) in &replacements {
        text = text.replace(from, to);
    }

    // Remove special symbols
    let special_symbols = ["♥", "☆", "♡", "©", "\\"];
    for symbol in &special_symbols {
        text = text.replace(symbol, "");
    }

    // Replace known expressions
    let expr_replacements = [
        ("@", " at "),
        ("e.g.,", "for example, "),
        ("i.e.,", "that is, "),
    ];

    for (from, to) in &expr_replacements {
        text = text.replace(from, to);
    }

    // Fix spacing around punctuation
    text = Regex::new(r" , ").unwrap().replace_all(&text, ",").to_string();
    text = Regex::new(r" \. ").unwrap().replace_all(&text, ".").to_string();
    text = Regex::new(r" ! ").unwrap().replace_all(&text, "!").to_string();
    text = Regex::new(r" \? ").unwrap().replace_all(&text, "?").to_string();
    text = Regex::new(r" ; ").unwrap().replace_all(&text, ";").to_string();
    text = Regex::new(r" : ").unwrap().replace_all(&text, ":").to_string();
    text = Regex::new(r" ' ").unwrap().replace_all(&text, "'").to_string();

    // Remove duplicate quotes
    while text.contains("\"\"") {
        text = text.replace("\"\"", "\"");
    }
    while text.contains("''") {
        text = text.replace("''", "'");
    }
    while text.contains("``") {
        text = text.replace("``", "`");
    }

    // Remove extra spaces
    text = Regex::new(r"\s+").unwrap().replace_all(&text, " ").to_string();
    text = text.trim().to_string();

    // If text doesn't end with punctuation, quotes, or closing brackets, add a period
    if !text.is_empty() {
        let ends_with_punct = Regex::new(r#"[.!?;:,'"\u{201C}\u{201D}\u{2018}\u{2019})\\]}…。」』】〉》›»]$"#).unwrap();
        if !ends_with_punct.is_match(&text) {
            text.push('.');
        }
    }
    } else {
        // For non-English languages: just collapse whitespace. We deliberately
        // leave the combining acute accent U+0301 alone so users can mark
        // stress (e.g. "замо́к" vs "за́мок") via the lexicon.
        text = Regex::new(r"\s+").unwrap().replace_all(&text, " ").to_string();
        text = text.trim().to_string();
    }

    // Supertonic 3 is fully multilingual: language is conveyed only via
    // <lang>...</lang> tags (no separate lang embedding in the ONNX graph),
    // so every language including English must be wrapped. Unknown codes
    // fall back to the model's <na> token.
    let tag = if SUPPORTED_LANGS.contains(&lang) { lang } else { "na" };
    text = format!("<{}>{}</{}>", tag, text, tag);

    Ok(text)
}

const SUPPORTED_LANGS: &[&str] = &[
    "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es",
    "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv",
    "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk",
    "vi", "na",
];

pub fn text_to_unicode_values(text: &str) -> Vec<usize> {
    text.chars().map(|c| c as usize).collect()
}

pub fn length_to_mask(lengths: &[usize], max_len: Option<usize>) -> Array3<f32> {
    let bsz = lengths.len();
    let max_len = max_len.unwrap_or_else(|| *lengths.iter().max().unwrap_or(&0));

    let mut mask = Array3::<f32>::zeros((bsz, 1, max_len));
    for (i, &len) in lengths.iter().enumerate() {
        for j in 0..len.min(max_len) {
            mask[[i, 0, j]] = 1.0;
        }
    }
    mask
}

pub fn get_text_mask(text_ids_lengths: &[usize]) -> Array3<f32> {
    let max_len = *text_ids_lengths.iter().max().unwrap_or(&0);
    length_to_mask(text_ids_lengths, Some(max_len))
}

/// Sample noisy latent from normal distribution and apply mask
pub fn sample_noisy_latent(
    duration: &[f32],
    sample_rate: i32,
    base_chunk_size: i32,
    chunk_compress: i32,
    latent_dim: i32,
) -> (Array3<f32>, Array3<f32>) {
    let bsz = duration.len();
    let max_dur = duration.iter().fold(0.0f32, |a, &b| a.max(b));

    let wav_len_max = (max_dur * sample_rate as f32) as usize;
    let wav_lengths: Vec<usize> = duration
        .iter()
        .map(|&d| (d * sample_rate as f32) as usize)
        .collect();

    let chunk_size = (base_chunk_size * chunk_compress) as usize;
    let latent_len = (wav_len_max + chunk_size - 1) / chunk_size;
    let latent_dim_val = (latent_dim * chunk_compress) as usize;

    let mut noisy_latent = Array3::<f32>::zeros((bsz, latent_dim_val, latent_len));

    // Reduced temperature (0.667) improves stability and reduces word skipping/hallucinations
    let normal = Normal::new(0.0, 0.667).unwrap();
    let mut rng = rand::thread_rng();

    for b in 0..bsz {
        for d in 0..latent_dim_val {
            for t in 0..latent_len {
                noisy_latent[[b, d, t]] = normal.sample(&mut rng);
            }
        }
    }

    let latent_lengths: Vec<usize> = wav_lengths
        .iter()
        .map(|&len| (len + chunk_size - 1) / chunk_size)
        .collect();

    let latent_mask = length_to_mask(&latent_lengths, Some(latent_len));

    // Apply mask
    for b in 0..bsz {
        for d in 0..latent_dim_val {
            for t in 0..latent_len {
                noisy_latent[[b, d, t]] *= latent_mask[[b, 0, t]];
            }
        }
    }

    (noisy_latent, latent_mask)
}

// ============================================================================
// WAV File I/O
// ============================================================================

#[allow(dead_code)]
pub fn write_wav_file<P: AsRef<Path>>(
    filename: P,
    audio_data: &[f32],
    sample_rate: i32,
) -> Result<()> {
    let spec = WavSpec {
        channels: 1,
        sample_rate: sample_rate as u32,
        bits_per_sample: 16,
        sample_format: SampleFormat::Int,
    };

    let mut writer = WavWriter::create(filename, spec)?;

    for &sample in audio_data {
        let clamped = sample.max(-1.0).min(1.0);
        let val = (clamped * 32767.0) as i16;
        writer.write_sample(val)?;
    }

    writer.finalize()?;
    Ok(())
}

// ============================================================================ 
// Text Chunking
// ============================================================================ 

const MAX_CHUNK_LENGTH: usize = 500;

const ABBREVIATIONS: &[&str] = &[
    "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
    "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
    "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D.",
];

/// Split text into chunks of at most `max_len` CHARACTERS, keeping the original order.
///
/// Two bugs of the original are fixed here: lengths were counted in bytes (a
/// Cyrillic letter is 2 bytes, so Russian chunks were half as long as meant),
/// and an over-long comma part was pushed out before the shorter parts already
/// collected — so a sentence was spoken start → end → middle.
pub fn chunk_text(text: &str, max_len: Option<usize>) -> Vec<String> {
    let max_len = max_len.unwrap_or(MAX_CHUNK_LENGTH);
    let text = text.trim();
    if text.is_empty() {
        return vec![String::new()];
    }
    let clen = |s: &str| s.chars().count();

    let para_re = Regex::new(r"\n\s*\n").unwrap();
    let mut chunks: Vec<String> = Vec::new();

    for para in para_re.split(text) {
        let para = para.trim();
        if para.is_empty() {
            continue;
        }
        if clen(para) <= max_len {
            chunks.push(para.to_string());
            continue;
        }

        let mut current = String::new();
        // flush what has been collected, so everything leaves in reading order
        let flush = |current: &mut String, chunks: &mut Vec<String>| {
            if !current.trim().is_empty() {
                chunks.push(current.trim().to_string());
            }
            current.clear();
        };

        for sentence in split_sentences(para) {
            let sentence = sentence.trim();
            if sentence.is_empty() {
                continue;
            }
            if clen(sentence) > max_len {
                flush(&mut current, &mut chunks);
                // long sentence: comma parts, packed in order
                let parts: Vec<&str> = sentence.split(',').map(|p| p.trim()).filter(|p| !p.is_empty()).collect();
                let last_part = parts.len().saturating_sub(1);
                for (k, part) in parts.iter().enumerate() {
                    // keep the comma: it is read as a short pause
                    let piece = if k < last_part { format!("{},", part) } else { part.to_string() };
                    if clen(&piece) > max_len {
                        // the collected shorter parts go FIRST — this was the reordering bug
                        flush(&mut current, &mut chunks);
                        let mut words_chunk = String::new();
                        for word in piece.split_whitespace() {
                            if !words_chunk.is_empty() && clen(&words_chunk) + 1 + clen(word) > max_len {
                                chunks.push(words_chunk.clone());
                                words_chunk.clear();
                            }
                            if !words_chunk.is_empty() {
                                words_chunk.push(' ');
                            }
                            words_chunk.push_str(word);
                        }
                        if !words_chunk.is_empty() {
                            chunks.push(words_chunk);
                        }
                        continue;
                    }
                    if !current.is_empty() && clen(&current) + 1 + clen(&piece) > max_len {
                        flush(&mut current, &mut chunks);
                    }
                    if !current.is_empty() {
                        current.push(' ');
                    }
                    current.push_str(&piece);
                }
                flush(&mut current, &mut chunks);
                continue;
            }
            if !current.is_empty() && clen(&current) + 1 + clen(sentence) > max_len {
                flush(&mut current, &mut chunks);
            }
            if !current.is_empty() {
                current.push(' ');
            }
            current.push_str(sentence);
        }
        flush(&mut current, &mut chunks);
    }

    if chunks.is_empty() {
        vec![String::new()]
    } else {
        chunks
    }
}

fn split_sentences(text: &str) -> Vec<String> {
    // Rust's regex doesn't support lookbehind, so we use a simpler approach
    // Split on sentence boundaries (., !, ?, ;) followed by optional quote/bracket and space
    // Added support for:
    // - Semicolon (;) as splitter
    // - Optional closing quotes/brackets after punctuation
    let re = Regex::new(r"([.!?]['\u{2019}\u{201D}\u{0022}\)\}\]]?)\s+").unwrap();

    // Find all matches
    let matches: Vec<_> = re.find_iter(text).collect();
    if matches.is_empty() {
        return vec![text.to_string()];
    }
    
    let mut sentences = Vec::new();
    let mut last_end = 0;
    
    for m in matches {
        // Get the text before the punctuation
        let before_punc = &text[last_end..m.start()];
        
        // Check if this ends with an abbreviation
        let mut is_abbrev = false;
        for abbrev in ABBREVIATIONS {
            let combined = format!("{}{}", before_punc.trim(), &text[m.start()..m.start()+1]);
            if combined.ends_with(abbrev) {
                is_abbrev = true;
                break;
            }
        }
        
        if !is_abbrev {
            // This is a real sentence boundary
            sentences.push(text[last_end..m.end()].to_string());
            last_end = m.end();
        }
    }
    
    // Add the remaining text
    if last_end < text.len() {
        sentences.push(text[last_end..].to_string());
    }
    
    if sentences.is_empty() {
        vec![text.to_string()]
    } else {
        sentences
    }
}

// ============================================================================
// Utility Functions
// ============================================================================

#[allow(dead_code)]
pub fn timer<F, T>(name: &str, f: F) -> Result<T>
where
    F: FnOnce() -> Result<T>,
{
    let start = std::time::Instant::now();
    println!("{}...", name);
    let result = f()?;
    let elapsed = start.elapsed().as_secs_f64();
    println!("  -> {} completed in {:.2} sec", name, elapsed);
    Ok(result)
}

#[allow(dead_code)]
pub fn sanitize_filename(text: &str, max_len: usize) -> String {
    // Take first max_len characters (Unicode code points, not bytes)
    text.chars()
        .take(max_len)
        .map(|c| {
            // is_alphanumeric() works with all Unicode letters and digits
            if c.is_alphanumeric() {
                c
            } else {
                '_'
            }
        })
        .collect()
}

// ============================================================================ 
// ONNX Runtime Integration
// ============================================================================ 

use ort::{
    session::{builder::GraphOptimizationLevel, Session},
    value::Value,
};

#[cfg(feature = "xnnpack")]
use ort::execution_providers::{CPUExecutionProvider, XNNPACKExecutionProvider};

#[derive(Clone)]
pub struct Style {
    pub ttl: Array3<f32>,
    pub dp: Array3<f32>,
}

/// silero-stress homograph resolver: BERT encoder + head (ONNX) and its data.
pub struct HomoRuntime {
    data: HomoData,
    enc: Session,
    head: Session,
}

impl HomoRuntime {
    pub fn load(dir: &Path, threads: usize) -> Result<Self> {
        let data = HomoData::load(dir)?;
        let enc = create_session(&dir.join("homo_encoder.onnx").to_string_lossy(), false, threads, 0)?;
        let head = create_session(&dir.join("homo_head.onnx").to_string_lossy(), false, 1, 0)?;
        Ok(HomoRuntime { data, enc, head })
    }

    /// Put context-dependent stress on homographs ("замок" → "з+амок"/"зам+ок").
    pub fn resolve(&mut self, text: &str) -> Result<String> {
        let candidates = self.data.find(text);
        if candidates.is_empty() {
            return Ok(text.to_string());
        }
        let mut decided = Vec::with_capacity(candidates.len());
        for c in candidates {
            // stress already written in the text (a stress-marked book, the user's
            // lexicon) is final; pinned words (tera::FIXED_STRESS) also win
            if text[c.start..c.end].contains('+') || crate::tera::is_pinned(&c.word_lower) {
                continue;
            }
            let variant = match c.phrase_choice.clone() {
                Some(v) => v,
                None => {
                    let Some(vars) = self.data.variants(&c.word_lower).cloned() else { continue };
                    let ids = self.data.encode(&c.raw_mark);
                    let (Some(st), Some(en)) = (
                        ids.iter().position(|&x| x == self.data.homo_start_id),
                        ids.iter().position(|&x| x == self.data.homo_end_id),
                    ) else { continue };
                    if en <= st + 1 || vars.is_empty() {
                        continue;
                    }
                    let n = ids.len();
                    let ids_value = Value::from_array(Array::from_shape_vec((1, n), ids)?)?;
                    let features = {
                        let out = self.enc.run(ort::inputs!{ "input_ids" => &ids_value })?;
                        let (shape, hidden) = out["hidden"].try_extract_tensor::<f32>()?;
                        let h = shape[2] as usize;
                        let row = |t: usize| &hidden[t * h..(t + 1) * h];
                        // [marker embedding ; mean of the word's sub-tokens]
                        let mut f = Vec::with_capacity(2 * h);
                        f.extend_from_slice(row(st));
                        let cnt = (en - st - 1) as f32;
                        for k in 0..h {
                            f.push((st + 1..en).map(|t| row(t)[k]).sum::<f32>() / cnt);
                        }
                        f
                    };
                    let dim = features.len();
                    let feat_value = Value::from_array(Array::from_shape_vec((1, dim), features)?)?;
                    let out = self.head.run(ort::inputs!{ "features" => &feat_value })?;
                    let logit = out["logits"].try_extract_tensor::<f32>()?.1[0];
                    // torch.round(sigmoid(x)): 1 only when x > 0
                    vars[((logit > 0.0) as usize).min(vars.len() - 1)].clone()
                }
            };
            decided.push((c, variant));
        }
        Ok(HomoData::apply(text, &decided))
    }
}

pub struct TextToSpeech {
    homo: Option<HomoRuntime>,
    text_processor: UnicodeProcessor,
    dp_ort: Session,
    text_enc_ort: Session,
    sampler_ort: Session,
    vocoder_ort: Session,
    pub sample_rate: i32,
}

impl TextToSpeech {
    pub fn new(
        homo: Option<HomoRuntime>,
        text_processor: UnicodeProcessor,
        dp_ort: Session,
        text_enc_ort: Session,
        sampler_ort: Session,
        vocoder_ort: Session,
    ) -> Self {
        TextToSpeech {
            homo,
            text_processor,
            dp_ort,
            text_enc_ort,
            sampler_ort,
            vocoder_ort,
            sample_rate: tera::SAMPLE_RATE,
        }
    }

    /// One utterance through the TeraTTS graphs (spec: teratts.py `_generate_latent`).
    /// `_total_step` is ignored: the distilled sampler has its 8-step Euler
    /// schedule baked in. `speed` > 1 speaks faster (duration_scale = 1/speed).
    fn _infer(
        &mut self,
        text_list: &[String],
        lang_list: &[String],
        style: &Style,
        _total_step: usize,
        speed: f32,
    ) -> Result<(Vec<f32>, Vec<f32>)> {
        if text_list.len() != 1 {
            anyhow::bail!("TeraTTS runtime synthesizes one utterance at a time");
        }

        // 0. Homographs by context (overrides a fixed dictionary stress)
        let lang = lang_list.first().map(String::as_str).unwrap_or("ru").to_string();
        let mut source = tera::pre_normalize(&text_list[0]);
        if lang.starts_with("ru") {
            if let Some(h) = self.homo.as_mut() {
                match h.resolve(&source) {
                    Ok(t) => source = t,
                    Err(e) => log::warn!("homograph pass failed: {e:?}"),
                }
            }
        }

        // 1. Text → token ids (with '+' for the encoder, without for durations)
        let (ids, dids) = {
            let table = self.text_processor.table();
            let Some(model_text) = tera::prepare(&source, &lang, table) else {
                return Ok((Vec::new(), vec![0.0]));
            };
            let dur_text = tera::strip_stress(&model_text);
            (tera::token_ids(&model_text, table)?, tera::token_ids(&dur_text, table)?)
        };
        let (n_ids, n_dids) = (ids.len(), dids.len());

        let ids_value = Value::from_array(Array::from_shape_vec((1, n_ids), ids)?)?;
        let mask_value = Value::from_array(Array3::<f32>::ones((1, 1, n_ids)))?;
        let dids_value = Value::from_array(Array::from_shape_vec((1, n_dids), dids)?)?;
        let dmask_value = Value::from_array(Array3::<f32>::ones((1, 1, n_dids)))?;
        let style_ttl_value = Value::from_array(style.ttl.clone())?;
        let style_dp_value = Value::from_array(style.dp.clone())?;

        // 2. Text encoder
        let enc_outputs = self.text_enc_ort.run(ort::inputs!{
            "text_ids" => &ids_value,
            "style_ttl" => &style_ttl_value,
            "text_mask" => &mask_value
        })?;
        let (emb_shape, emb_data) = enc_outputs["output"].try_extract_tensor::<f32>()?;
        let text_emb = Array3::from_shape_vec(
            (emb_shape[0] as usize, emb_shape[1] as usize, emb_shape[2] as usize),
            emb_data.to_vec(),
        )?;

        // 3. Duration (seconds)
        let dp_outputs = self.dp_ort.run(ort::inputs!{
            "text_ids" => &dids_value,
            "style_dp" => &style_dp_value,
            "text_mask" => &dmask_value
        })?;
        let raw = dp_outputs["output"].try_extract_tensor::<f32>()?.1[0];
        let duration = raw / speed.max(0.1) / tera::SPEED;
        if !duration.is_finite() || duration <= 0.0 {
            anyhow::bail!("duration predictor returned {raw}");
        }

        // 4. Sampler: N(0,1) noise → latent in one call
        let frames = tera::latent_len(duration);
        let normal = Normal::new(0.0f32, 1.0f32).unwrap();
        let mut rng = rand::thread_rng();
        let noise = Array3::<f32>::from_shape_fn((1, tera::LATENT_CHANNELS, frames), |_| normal.sample(&mut rng));
        let noise_value = Value::from_array(noise)?;
        let emb_value = Value::from_array(text_emb)?;
        let lmask_value = Value::from_array(Array3::<f32>::ones((1, 1, frames)))?;
        let guidance_value = Value::from_array(Array::from_elem(1, tera::GUIDANCE))?;
        let sampler_outputs = self.sampler_ort.run(ort::inputs!{
            "initial_latent" => &noise_value,
            "text_emb" => &emb_value,
            "style_ttl" => &style_ttl_value,
            "latent_mask" => &lmask_value,
            "text_mask" => &mask_value,
            "guidance" => &guidance_value
        })?;
        let (lat_shape, lat_data) = sampler_outputs["latent"].try_extract_tensor::<f32>()?;
        let latent = Array3::from_shape_vec(
            (lat_shape[0] as usize, lat_shape[1] as usize, lat_shape[2] as usize),
            lat_data.to_vec(),
        )?;

        // 5. Vocoder, trimmed to the predicted duration
        let latent_value = Value::from_array(latent)?;
        let vocoder_outputs = self.vocoder_ort.run(ort::inputs!{
            "latent" => &latent_value
        })?;
        let wav_all = vocoder_outputs["output"].try_extract_tensor::<f32>()?.1;
        let keep = ((duration * tera::SAMPLE_RATE as f32).round() as usize).min(wav_all.len());

        Ok((wav_all[..keep].to_vec(), vec![duration]))
    }

    pub fn call<F>(
        &mut self,
        text: &str,
        lang: &str,
        style: &Style,
        total_step: usize,
        speed: f32,
        silence_duration: f32,
        mut callback: F,
    ) -> Result<(Vec<f32>, f32)> 
    where F: FnMut(usize, usize, Option<&[f32]>) -> bool {
        let max_len = tera::MAX_CHUNK_CHARS;
        let sr = self.sample_rate as f32;
        // Pauses follow the reading speed: at 2× a colon pause is half as long.
        let scale = tera::pause_scale() / speed.max(0.1);

        // Scene break ornament ("* * *"): just a long stop.
        if tera::is_scene_break(text) {
            let pause = vec![0.0f32; (tera::PAUSE_SCENE * scale * sr) as usize];
            if !callback(0, 1, None) || !callback(0, 1, Some(&pause)) {
                return Err(anyhow::anyhow!("Synthesis cancelled by user"));
            }
            callback(1, 1, None);
            let dur = pause.len() as f32 / sr;
            return Ok((pause, dur));
        }
        // Kotlin marks the end of a paragraph with U+2029 and the end of the
        // reader's utterance with U+2063.
        let paragraph_end = text.contains(tera::PARAGRAPH_MARK);
        let utterance_end = text.contains(tera::UTTERANCE_END_MARK);
        let text_owned = text.replace(tera::PARAGRAPH_MARK, " ").replace(tera::UTTERANCE_END_MARK, " ");
        let text = text_owned.as_str();

        // (chunk, is_aside, pause_after): phrases split at punctuation that needs a
        // real pause (. ! ? … : ; —); text in round brackets is its own phrase.
        let mut chunks: Vec<(String, bool, f32)> = Vec::new();
        for (segment, aside) in tera::split_asides(text) {
            for (phrase, pause) in tera::pause_split(&segment) {
                let parts = chunk_text(&phrase, Some(max_len));
                let n = parts.len();
                for (k, c) in parts.into_iter().enumerate() {
                    chunks.push((c, aside, if k + 1 == n { pause } else { 0.0 }));
                }
            }
        }
        let num_chunks = chunks.len();

        let mut wav_cat: Vec<f32> = Vec::new();
        let mut dur_cat: f32 = 0.0;

        // Silence goes out through the same callback as speech.
        let emit_silence = |seconds: f32, i: usize, wav_cat: &mut Vec<f32>, dur_cat: &mut f32,
                                callback: &mut F| -> bool {
            if seconds <= 0.0 {
                return true;
            }
            let pause = vec![0.0f32; (seconds * sr) as usize];
            wav_cat.extend_from_slice(&pause);
            *dur_cat += seconds;
            callback(i, num_chunks, Some(&pause))
        };

        for i in 0..num_chunks {
            let (chunk, aside, _) = (&chunks[i].0, chunks[i].1, chunks[i].2);
            if !callback(i, num_chunks, None) {
                return Err(anyhow::anyhow!("Synthesis cancelled by user"));
            }

            // Gap before this chunk: what the previous phrase owes, at least the
            // aside pause when entering or leaving brackets, at least the tiny
            // inter-chunk silence.
            if i > 0 {
                let (prev_aside, prev_pause) = (chunks[i - 1].1, chunks[i - 1].2);
                let mut gap = prev_pause * scale;
                if aside != prev_aside {
                    gap = gap.max(tera::ASIDE_PAUSE_S * scale);
                }
                gap = gap.max(silence_duration);
                if !emit_silence(gap, i, &mut wav_cat, &mut dur_cat, &mut callback) {
                    return Err(anyhow::anyhow!("Synthesis cancelled by user"));
                }
            }

            let chunk_speed = if aside { speed * tera::ASIDE_SPEED } else { speed };
            // The model speaks cleanly only within ~0.75–1.5×; beyond that it
            // swallows syllables, so the rest of the speed-up is a pitch-preserving
            // time stretch of the finished audio.
            let (model_speed, stretch_rate) = crate::stretch::split_speed(chunk_speed);
            let started = std::time::Instant::now();
            let (mut wav, duration) = self._infer(&[chunk.clone()], &[lang.to_string()], style, total_step, model_speed)?;
            if i == 0 {
                tera::note_first_chunk_latency(started.elapsed().as_secs_f32());
            }

            // Truncate audio based on predicted duration to remove trailing silence
            let sample_count = ((duration[0] * sr) as usize).min(wav.len());
            wav.truncate(sample_count);
            if (stretch_rate - 1.0).abs() > 0.01 {
                wav = crate::stretch::time_stretch(&wav, stretch_rate, self.sample_rate as u32);
            }
            let dur = wav.len() as f32 / sr;
            if aside {
                for x in wav.iter_mut() {
                    *x *= tera::ASIDE_GAIN;
                }
            }

            if !callback(i, num_chunks, Some(&wav)) {
                return Err(anyhow::anyhow!("Synthesis cancelled by user"));
            }
            wav_cat.extend_from_slice(&wav);
            dur_cat += dur;
        }

        // The pause owed after the last phrase: separates this call from the next
        // sentence / utterance (readers send text sentence by sentence).
        if let Some(last) = chunks.last() {
            let tail = if paragraph_end { last.2.max(tera::paragraph_pause()) } else { last.2 };
            let tail = if utterance_end { tera::utterance_end_pause(tail * scale) } else { tail * scale };
            if !emit_silence(tail, num_chunks.saturating_sub(1), &mut wav_cat, &mut dur_cat, &mut callback) {
                return Err(anyhow::anyhow!("Synthesis cancelled by user"));
            }
        }
        callback(num_chunks, num_chunks, None);

        Ok((wav_cat, dur_cat))
    }

    #[allow(dead_code)]
    pub fn batch(
        &mut self,
        text_list: &[String],
        lang_list: &[String],
        style: &Style,
        total_step: usize,
        speed: f32,
    ) -> Result<(Vec<f32>, Vec<f32>)> {
        self._infer(text_list, lang_list, style, total_step, speed)
    }
}

// ============================================================================ 
// Component Loading Functions
// ============================================================================ 

/// Load voice style from JSON files
/// TeraTTS voice: a directory with style_ttl.npy (1×50×256) and style_dp.npy (1×8×16).
fn load_npy_style_dir(dir: &Path) -> Result<Style> {
    let read = |name: &str, want: [usize; 3]| -> Result<Array3<f32>> {
        let path = dir.join(name);
        let bytes = std::fs::read(&path).with_context(|| format!("read {}", path.display()))?;
        let (shape, data) = tera::read_npy_f32(&bytes)?;
        if shape != want {
            anyhow::bail!("{}: shape {:?}, expected {:?}", path.display(), shape, want);
        }
        Ok(Array3::from_shape_vec((want[0], want[1], want[2]), data)?)
    };
    Ok(Style {
        ttl: read("style_ttl.npy", [1, 50, 256])?,
        dp: read("style_dp.npy", [1, 8, 16])?,
    })
}

pub fn load_voice_style(voice_style_paths: &[String], verbose: bool) -> Result<Style> {
    if voice_style_paths.len() == 1 && Path::new(&voice_style_paths[0]).is_dir() {
        return load_npy_style_dir(Path::new(&voice_style_paths[0]));
    }
    let bsz = voice_style_paths.len();

    // Read first file to get dimensions
    let first_file = File::open(&voice_style_paths[0])
        .context("Failed to open voice style file")?;
    let first_reader = BufReader::new(first_file);
    let first_data: VoiceStyleData = serde_json::from_reader(first_reader)?;

    let ttl_dims = &first_data.style_ttl.dims;
    let dp_dims = &first_data.style_dp.dims;

    let ttl_dim1 = ttl_dims[1];
    let ttl_dim2 = ttl_dims[2];
    let dp_dim1 = dp_dims[1];
    let dp_dim2 = dp_dims[2];

    // Pre-allocate arrays with full batch size
    let ttl_size = bsz * ttl_dim1 * ttl_dim2;
    let dp_size = bsz * dp_dim1 * dp_dim2;
    let mut ttl_flat = vec![0.0f32; ttl_size];
    let mut dp_flat = vec![0.0f32; dp_size];

    // Fill in the data
    for (i, path) in voice_style_paths.iter().enumerate() {
        let file = File::open(path).context("Failed to open voice style file")?;
        let reader = BufReader::new(file);
        let data: VoiceStyleData = serde_json::from_reader(reader)?;

        // Flatten TTL data
        let ttl_offset = i * ttl_dim1 * ttl_dim2;
        let mut idx = 0;
        for batch in &data.style_ttl.data {
            for row in batch {
                for &val in row {
                    ttl_flat[ttl_offset + idx] = val;
                    idx += 1;
                }
            }
        }

        // Flatten DP data
        let dp_offset = i * dp_dim1 * dp_dim2;
        idx = 0;
        for batch in &data.style_dp.data {
            for row in batch {
                for &val in row {
                    dp_flat[dp_offset + idx] = val;
                    idx += 1;
                }
            }
        }
    }

    let ttl_style = Array3::from_shape_vec((bsz, ttl_dim1, ttl_dim2), ttl_flat)?;
    let dp_style = Array3::from_shape_vec((bsz, dp_dim1, dp_dim2), dp_flat)?;

    if verbose {
        println!("Loaded {} voice styles\n", bsz);
    }

    Ok(Style {
        ttl: ttl_style,
        dp: dp_style,
    })
}

/// Load and mix two voice styles
pub fn load_and_mix_voice_styles(path1: &str, path2: &str, alpha: f32) -> Result<Style> {
    let s1 = load_voice_style(&[path1.to_string()], false)?;
    let s2 = load_voice_style(&[path2.to_string()], false)?;

    if s1.ttl.dim() != s2.ttl.dim() || s1.dp.dim() != s2.dp.dim() {
        anyhow::bail!("Voice style dimensions mismatch");
    }

    let ttl = &s1.ttl * (1.0 - alpha) + &s2.ttl * alpha;
    let dp = &s1.dp * (1.0 - alpha) + &s2.dp * alpha;

    Ok(Style { ttl, dp })
}

/// Create an ONNX session with the specified execution providers
fn create_session(model_path: &str, use_xnnpack: bool, ort_threads: usize, _xnn_threads: usize) -> Result<Session> {
    #[allow(unused_mut)]
    let mut builder = Session::builder()?
        .with_optimization_level(GraphOptimizationLevel::Level3)?
        // OPTIMIZATION: Disable spinning to save battery and reduce heat on Android.
        // This ensures that when one thread pool is idle (e.g., ORT pool while XNNPACK is working),
        // it doesn't consume any CPU cycles.
        .with_config_entry("session.intra_op.allow_spinning", "0")?
        .with_config_entry("session.inter_op.allow_spinning", "0")?
        .with_intra_threads(ort_threads)?;

    if use_xnnpack {
        #[cfg(feature = "xnnpack")]
        {
            if let Some(xnn_threads_nz) = std::num::NonZeroUsize::new(_xnn_threads) {
                builder = builder.with_execution_providers([
                    XNNPACKExecutionProvider::default()
                        .with_intra_op_num_threads(xnn_threads_nz)
                        .build(),
                    CPUExecutionProvider::default().build(),
                ])?;
            } else {
                builder = builder.with_execution_providers([
                    XNNPACKExecutionProvider::default()
                        .with_intra_op_num_threads(std::num::NonZeroUsize::MIN)
                        .build(),
                    CPUExecutionProvider::default().build(),
                ])?;
            }
        }
    }

    builder.commit_from_file(model_path).context(format!("Failed to load model: {}", model_path))
}

/// Load TTS components
pub fn load_text_to_speech(onnx_dir: &str, use_gpu: bool, use_xnnpack: bool, ort_threads: usize, xnn_threads: usize) -> Result<TextToSpeech> {
    if use_gpu {
        anyhow::bail!("GPU mode is not supported yet");
    }
    
    if use_xnnpack {
        log::info!("Using XNNPACK ({}) with ORT ({}) threads", xnn_threads, ort_threads);
    } else {
        log::info!("Using CPU for inference with {} threads", ort_threads);
    }

    let dp_path = format!("{}/duration_predictor.onnx", onnx_dir);
    let text_enc_path = format!("{}/text_encoder.onnx", onnx_dir);
    let sampler_path = format!("{}/{}", onnx_dir, tera::SAMPLER_FILE);
    let vocoder_path = format!("{}/vocoder.onnx", onnx_dir);

    let dp_ort = create_session(&dp_path, use_xnnpack, ort_threads, xnn_threads)?;
    let text_enc_ort = create_session(&text_enc_path, use_xnnpack, ort_threads, xnn_threads)?;
    let sampler_ort = create_session(&sampler_path, use_xnnpack, ort_threads, xnn_threads)?;
    let vocoder_ort = create_session(&vocoder_path, use_xnnpack, ort_threads, xnn_threads)?;

    let unicode_indexer_path = format!("{}/unicode_indexer.json", onnx_dir);
    let text_processor = UnicodeProcessor::new(&unicode_indexer_path)?;

    let homo_dir = Path::new(onnx_dir).join("homo");
    let homo = if homo_dir.join("homo_encoder.onnx").exists() {
        match HomoRuntime::load(&homo_dir, 1) {
            Ok(h) => {
                log::info!("Homograph resolver loaded");
                Some(h)
            }
            Err(e) => {
                log::warn!("Homograph resolver unavailable: {e:?}");
                None
            }
        }
    } else {
        log::info!("No homograph resolver at {}", homo_dir.display());
        None
    };

    Ok(TextToSpeech::new(
        homo,
        text_processor,
        dp_ort,
        text_enc_ort,
        sampler_ort,
        vocoder_ort,
    ))
}
