// ============================================================================
// TeraTTS front-end: text preparation, constants, .npy loading.
//
// Mirrors TeraSpace/TeraTTSv2 `teratts.py` (revision f05ea799…). Verified
// against the Python reference `ref_infer.py`. Pure logic, no ONNX — unit
// tests run on the host with `cargo test`.
//
// Division of labour in the app:
//   Kotlin  — lexicon, Russian numbers → words, accent dictionary ('+' marks)
//   Rust    — this module (spacing, vocabulary filter, <ru>/<en> tags, NFKD)
//             + the four ONNX graphs in helper.rs
// ============================================================================

use anyhow::{bail, Context, Result};
use once_cell::sync::Lazy;
use regex::Regex;
use unicode_normalization::UnicodeNormalization;

pub const SAMPLE_RATE: i32 = 44_100;
/// `SAMPLES_PER_COMPRESSED_FRAME` in teratts.py
pub const SAMPLES_PER_FRAME: usize = 3_072;
pub const LATENT_CHANNELS: usize = 144;
/// Global duration correction: duration = raw * scale / SPEED
pub const SPEED: f32 = 1.05;
/// Baked into the distilled sampler; the graph still takes the input.
pub const GUIDANCE: f32 = 3.0;
/// The model was trained on ≤25 s utterances; ~200 chars keeps well inside.
pub const MAX_CHUNK_CHARS: usize = 200;

/// int8 sampler: the Loop unrolled, 1x1 Conv1d -> MatMul, MatMul quantized to int8
/// (tools/tera_graph_tools.py). ~3.5x faster on ARM with i8mm, 256 MB -> 72 MB.
pub const SAMPLER_FILE: &str = "sampler_distilled_int8.onnx";

/// Parenthetical asides ("(как будто фамилия должна была что-то сказать)") are
/// voiced as their own phrase: a pause around them, slightly faster and quieter.
pub const ASIDE_PAUSE_S: f32 = 0.25;
pub const ASIDE_SPEED: f32 = 1.08;
pub const ASIDE_GAIN: f32 = 0.85;
/// Silence after punctuation, seconds. The model's own pauses are weak and the
/// audio is cut at the predicted duration, so pauses are added explicitly.
/// Ratios follow reading-aloud practice (comma < dash < ; < : < . < …).
pub const PAUSE_COMMA: f32 = 0.15;
pub const PAUSE_DASH: f32 = 0.25;
pub const PAUSE_SEMICOLON: f32 = 0.30;
pub const PAUSE_COLON: f32 = 0.35;
pub const PAUSE_SENTENCE: f32 = 0.50;
pub const PAUSE_ELLIPSIS: f32 = 0.65;

/// Scene break ("* * *", "***", "⁂", "— — —"): a longer stop, like a narrator's.
pub const PAUSE_SCENE: f32 = 1.3;
/// Kotlin marks the last sentence of a paragraph with U+2029 PARAGRAPH SEPARATOR.
pub const PARAGRAPH_MARK: char = '\u{2029}';

/// Pause after a paragraph, seconds (settings slider; default 0.9).
static PARAGRAPH_PAUSE_BITS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0x3F66_6666);

pub fn set_paragraph_pause(v: f32) {
    let v = if v.is_finite() { v.clamp(0.0, 3.0) } else { 0.9 };
    PARAGRAPH_PAUSE_BITS.store(v.to_bits(), std::sync::atomic::Ordering::Relaxed);
}

pub fn paragraph_pause() -> f32 {
    f32::from_bits(PARAGRAPH_PAUSE_BITS.load(std::sync::atomic::Ordering::Relaxed))
}

/// The whole text is a scene-break ornament.
pub fn is_scene_break(text: &str) -> bool {
    let t = text.trim();
    if t.is_empty() {
        return false;
    }
    let marks = t.chars().filter(|c| !c.is_whitespace()).count();
    let only_ornament = t.chars().all(|c| c.is_whitespace() || matches!(c, '*' | '⁂' | '#' | '~' | '—' | '–' | '-' | '_' | '=' | '•' | '·' | '◆' | '◇' | '❖' | '§'));
    only_ornament && (marks >= 3 || t.contains('⁂') || t.contains('❖'))
}

/// Kotlin marks the last sentence of an utterance with U+2063 (invisible separator):
/// the reader will only hand over its next text after this audio has nearly played,
/// and synthesizing that next text's first phrase is silence anyway. So the pause
/// owed here is shortened by that expected latency instead of adding up with it.
pub const UTTERANCE_END_MARK: char = '\u{2063}';

/// Moving average of how long the first chunk of a call takes to synthesize.
static FIRST_CHUNK_LATENCY_BITS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

pub fn note_first_chunk_latency(seconds: f32) {
    if !seconds.is_finite() || seconds <= 0.0 {
        return;
    }
    let old = first_chunk_latency();
    let new = if old == 0.0 { seconds } else { 0.7 * old + 0.3 * seconds };
    FIRST_CHUNK_LATENCY_BITS.store(new.min(5.0).to_bits(), std::sync::atomic::Ordering::Relaxed);
}

pub fn first_chunk_latency() -> f32 {
    f32::from_bits(FIRST_CHUNK_LATENCY_BITS.load(std::sync::atomic::Ordering::Relaxed))
}

/// Pause to emit at the end of an utterance: the intended pause minus the part
/// that the next utterance's synthesis will produce on its own. Android keeps
/// ~0.5 s of audio queued, so only latency beyond that turns into silence.
pub fn utterance_end_pause(intended: f32) -> f32 {
    let hidden = (first_chunk_latency() - 0.5).max(0.0);
    (intended - hidden).max(0.0)
}

/// User multiplier for all added pauses (settings slider), 1.0 = defaults above.
static PAUSE_SCALE_BITS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0x3F80_0000);

pub fn set_pause_scale(v: f32) {
    let v = if v.is_finite() { v.clamp(0.0, 3.0) } else { 1.0 };
    PAUSE_SCALE_BITS.store(v.to_bits(), std::sync::atomic::Ordering::Relaxed);
}

pub fn pause_scale() -> f32 {
    f32::from_bits(PAUSE_SCALE_BITS.load(std::sync::atomic::Ordering::Relaxed))
}

/// Shorter parentheses ("(1)", "(с)", "(ок)") stay inline, without the brackets.
const ASIDE_MIN_LETTERS: usize = 4;

const CYR: &str = "А-Яа-яЁё";

static LAT_RUN: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"[A-Za-z][A-Za-z0-9'\-]*(?:[ ,.\-]+[A-Za-z][A-Za-z0-9'\-]*)*").unwrap()
});
static CYR_RUN: Lazy<Regex> = Lazy::new(|| {
    Regex::new(&format!(
        r"[{c}][{c}0-9'\-+]*(?:[ ,.\-]+[{c}+][{c}0-9'\-+]*)*",
        c = CYR
    ))
    .unwrap()
});
static WS: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s+").unwrap());
static DASH_SPACED: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s+[—–]\s+").unwrap());
static DASH_LEADING: Lazy<Regex> = Lazy::new(|| Regex::new(r"(^|\n)\s*[—–]\s*").unwrap());
static COMMA_RUN: Lazy<Regex> = Lazy::new(|| Regex::new(r",(\s*,)+").unwrap());
static STOP_COMMA: Lazy<Regex> = Lazy::new(|| Regex::new(r"([.!?;:])\s*,").unwrap());

const RU_VOWELS: &str = "аеёиоуыэюяАЕЁИОУЫЭЮЯ";

/// '+' is a stress mark only right before a Russian vowel; any other '+'
/// ("C++", "2+2") would confuse the model, so it is dropped.
pub fn clean_plus(s: &str) -> String {
    let ch: Vec<char> = s.chars().collect();
    ch.iter()
        .enumerate()
        .filter(|&(i, &c)| c != '+' || ch.get(i + 1).map_or(false, |n| RU_VOWELS.contains(*n)))
        .map(|(_, &c)| c)
        .collect()
}

fn is_cyr(c: char) -> bool {
    matches!(c, 'А'..='я' | 'Ё' | 'ё')
}

/// Stress marks in every form people actually type or copy, turned into
/// TeraTTS notation ("зам+ок"):
///  - a combining acute after the vowel ("замо́к" — accent dictionaries, the
///    user lexicon, stress-marked books), or a grave (NFC makes ѐ/ѝ of it);
///  - a Latin look-alike with an accent inside a Russian word ("замóк", "тáк" —
///    copied from dictionary sites);
///  - "+" after the vowel ("замо+к", as some other TTS engines write it): a "+"
///    before a consonant means nothing else, so it marks the vowel before it.
/// Stray accents are dropped.
pub fn acute_to_plus(s: &str) -> String {
    let src: Vec<char> = s.chars().collect();
    // 1) look-alikes and Cyrillic letters with a grave → vowel + combining acute
    let mut ch: Vec<char> = Vec::with_capacity(src.len() + 8);
    for (i, &c) in src.iter().enumerate() {
        let near_cyr = (i > 0 && is_cyr(src[i - 1])) || src.get(i + 1).map_or(false, |&n| is_cyr(n));
        let latin = match c {
            'á' => Some('а'), 'Á' => Some('А'), 'é' => Some('е'), 'É' => Some('Е'),
            'ó' => Some('о'), 'Ó' => Some('О'), 'ý' => Some('у'), 'Ý' => Some('У'),
            _ => None,
        };
        let cyr = match c {
            'ѐ' => Some('е'), 'Ѐ' => Some('Е'), 'ѝ' => Some('и'), 'Ѝ' => Some('И'),
            _ => None,
        };
        match (latin, cyr) {
            (Some(b), _) if near_cyr => { ch.push(b); ch.push('\u{301}'); }
            (_, Some(b)) => { ch.push(b); ch.push('\u{301}'); }
            _ => ch.push(if c == '\u{300}' { '\u{301}' } else { c }),
        }
    }
    // 2) acute after a vowel → "+" before it; "+" after a vowel → before it
    let mut out: Vec<char> = Vec::with_capacity(ch.len() + 8);
    let mut i = 0;
    while i < ch.len() {
        let c = ch[i];
        if c == '\u{301}' {
            i += 1;
            continue;
        }
        if c == '+' {
            let next_vowel = ch.get(i + 1).map_or(false, |n| RU_VOWELS.contains(*n));
            let prev = out.last().copied();
            if !next_vowel && prev.map_or(false, |p| RU_VOWELS.contains(p)) {
                let at = out.len() - 1;
                if at == 0 || out[at - 1] != '+' {
                    out.insert(at, '+');
                }
                i += 1;
                continue;
            }
        }
        if ch.get(i + 1) == Some(&'\u{301}') && RU_VOWELS.contains(c) && out.last() != Some(&'+') {
            out.push('+');
        }
        out.push(c);
        i += 1;
    }
    out.into_iter().collect()
}

/// Dialogue dashes and spaced dashes become pauses (commas) — the model has
/// no reliable pause semantics for U+2014 / U+2013.
pub fn dashes_to_pauses(s: &str) -> String {
    let t = DASH_LEADING.replace_all(s, "$1");
    let t = DASH_SPACED.replace_all(&t, ", ");
    let t = t.replace(['—', '–'], "-");
    let t = COMMA_RUN.replace_all(&t, ",");
    STOP_COMMA.replace_all(&t, "$1").into_owned()
}

fn is_letter_ru_en(c: char) -> bool {
    c.is_ascii_alphabetic() || matches!(c, 'А'..='я' | 'Ё' | 'ё')
}

fn has_word(s: &str) -> bool {
    s.chars().any(|c| c.is_ascii_digit() || is_letter_ru_en(c))
}

/// Character is representable: every NFKD codepoint exists in the indexer.
pub fn is_known(table: &[i64], ch: char) -> bool {
    let mut any = false;
    for c in std::iter::once(ch).nfkd() {
        any = true;
        let u = c as usize;
        if u >= table.len() || table[u] < 0 {
            return false;
        }
    }
    any
}

/// `_add_punctuation_spaces`: space after , . ! ? ; : … unless followed by
/// whitespace or '<', and never inside decimals like 3.5 / 3,5.
pub fn add_punct_spaces(s: &str) -> String {
    let ch: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len() + 16);
    for i in 0..ch.len() {
        let c = ch[i];
        out.push(c);
        if !matches!(c, ',' | '.' | '!' | '?' | ';' | ':' | '…') {
            continue;
        }
        let Some(&next) = ch.get(i + 1) else { continue };
        // keep punctuation runs ("...", "?!", "!..") together: one pause, not three
        if next.is_whitespace() || next == '<' || matches!(next, ',' | '.' | '!' | '?' | ';' | ':' | '…') {
            continue;
        }
        let prev_digit = i > 0 && ch[i - 1].is_numeric();
        if matches!(c, '.' | ',') && prev_digit && next.is_numeric() {
            continue;
        }
        out.push(' ');
    }
    out
}

/// `NUMBER_NEEDS_SPACE`: "21год" → "21 год".
pub fn add_number_spaces(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    let mut prev: Option<char> = None;
    for c in s.chars() {
        if let Some(p) = prev {
            if p.is_numeric() && is_letter_ru_en(c) {
                out.push(' ');
            }
        }
        out.push(c);
        prev = Some(c);
    }
    out
}

pub fn skip_unknown(s: &str, table: &[i64]) -> String {
    s.chars().filter(|&c| is_known(table, c)).collect()
}

/// Wrap text in <ru>/<en>: the base language plus runs of the other script.
pub fn wrap_tags(s: &str, base: &str) -> String {
    let (other, run): (&str, &Regex) = if base == "ru" { ("en", &LAT_RUN) } else { ("ru", &CYR_RUN) };
    let mut parts: Vec<(&str, &str)> = Vec::new();
    let mut pos = 0;
    for m in run.find_iter(s) {
        if m.start() > pos {
            parts.push((base, &s[pos..m.start()]));
        }
        parts.push((other, m.as_str()));
        pos = m.end();
    }
    if pos < s.len() {
        parts.push((base, &s[pos..]));
    }

    let mut merged: Vec<(&str, String)> = Vec::new();
    for (lang, t) in parts {
        let mut t = t.to_string();
        // leading punctuation belongs to the previous span
        let lead_len: usize = t
            .chars()
            .take_while(|c| c.is_whitespace() || matches!(c, ',' | '.' | ';' | ':' | '!' | '?'))
            .map(|c| c.len_utf8())
            .sum();
        if lead_len > 0 && !merged.is_empty() {
            // keep the whitespace that followed the punctuation
            let lead: String = t[..lead_len].trim_start().to_string();
            let last = merged.last_mut().unwrap();
            last.1 = format!("{}{}", last.1.trim_end(), lead);
            t = t[lead_len..].to_string();
        }
        if !has_word(&t) {
            if let Some(last) = merged.last_mut() {
                last.1.push_str(&t);
            }
            continue;
        }
        match merged.last_mut() {
            Some(last) if last.0 == lang => last.1.push_str(&t),
            _ => merged.push((lang, t)),
        }
    }
    merged
        .iter()
        .filter_map(|(l, t)| {
            let t = t.trim();
            (!t.is_empty()).then(|| format!("<{l}>{t}</{l}>"))
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Words that dictionaries list as homographs although the second reading is
/// vanishingly rare ("один" the number vs "Один" the god). silero-stress keeps
/// them out of its homograph model for the same reason; we pin the common stress.
const FIXED_STRESS: &[(&str, &str)] = &[
    ("один", "од+ин"), ("одна", "одн+а"), ("одно", "одн+о"), ("одни", "одн+и"),
    ("после", "п+осле"), ("лет", "л+ет"), ("кому", "ком+у"), ("какая", "как+ая"),
    ("времени", "вр+емени"), ("тому", "том+у"), ("части", "ч+асти"), ("нем", "н+ём"),
    ("написать", "напис+ать"), ("написал", "напис+ал"), ("летом", "л+етом"),
    ("небе", "н+ебе"), ("неба", "н+еба"), ("метод", "м+етод"), ("любая", "люб+ая"),
    ("отчего", "отчег+о"), ("берет", "бер+ёт"), ("голубой", "голуб+ой"),
    ("выходит", "вых+одит"), ("выходят", "вых+одят"), ("черных", "ч+ёрных"),
    // the short adjective "до́лги" ("ночи долги") is rare; the noun is everywhere
    ("долги", "долг+и"),
];

/// Pinned words are final: the homograph resolver leaves them alone.
pub fn is_pinned(word_lower: &str) -> bool {
    FIXED_STRESS.iter().any(|(w, _)| *w == word_lower)
}

/// Apply FIXED_STRESS to every matching word that has no stress mark yet
/// (a mark already in the text — a stress-marked book, the lexicon — is kept).
pub fn pin_fixed_stress(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 8);
    let mut word = String::new();
    let flush = |word: &mut String, out: &mut String| {
        if word.is_empty() {
            return;
        }
        let plain: String = word.chars().filter(|&c| c != '+').collect();
        let lower: String = plain.chars().flat_map(char::to_lowercase).collect();
        let already_marked = word.contains('+');
        match FIXED_STRESS.iter().find(|(w, _)| *w == lower && !already_marked) {
            Some((_, stressed)) => {
                // keep the original casing, letter by letter
                let src: Vec<char> = plain.chars().collect();
                let mut i = 0;
                for c in stressed.chars() {
                    if c == '+' {
                        out.push('+');
                    } else {
                        let cased = match src.get(i) {
                            Some(s) if s.is_uppercase() => c.to_uppercase().collect::<String>(),
                            _ => c.to_string(),
                        };
                        out.push_str(&cased);
                        i += 1;
                    }
                }
            }
            None => out.push_str(word),
        }
        word.clear();
    };
    for c in text.chars() {
        if matches!(c, 'А'..='я' | 'Ё' | 'ё' | '+') {
            word.push(c);
        } else {
            flush(&mut word, &mut out);
            out.push(c);
        }
    }
    flush(&mut word, &mut out);
    out
}

/// NFC + acute→plus + pinned stress: the form in which homographs are looked up.
pub fn pre_normalize(text: &str) -> String {
    let t: String = text.nfc().collect();
    pin_fixed_stress(&acute_to_plus(&t))
}

// ---- leftover digits → words ------------------------------------------------
// Kotlin spells Russian numbers before the engine, but anything it misses
// ("3.14", unusual formats) would be silently dropped: TeraTTS's vocabulary has
// no digits. This safety net speaks them instead.

const RU_ONES_M: [&str; 10] = ["", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"];
const RU_ONES_F: [&str; 10] = ["", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"];
const RU_TEENS: [&str; 10] = ["десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
    "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"];
const RU_TENS: [&str; 10] = ["", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
    "семьдесят", "восемьдесят", "девяносто"];
const RU_HUNDREDS: [&str; 10] = ["", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот",
    "семьсот", "восемьсот", "девятьсот"];
const RU_DIGITS: [&str; 10] = ["ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"];
const EN_DIGITS: [&str; 10] = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"];

fn ru_plural<'a>(n: u64, one: &'a str, few: &'a str, many: &'a str) -> &'a str {
    let (d, h) = (n % 10, n % 100);
    if (11..=14).contains(&h) { many } else if d == 1 { one } else if (2..=4).contains(&d) { few } else { many }
}

fn ru_triad(n: u64, feminine: bool, out: &mut Vec<&'static str>) {
    let (h, t, o) = ((n / 100) as usize, (n / 10 % 10) as usize, (n % 10) as usize);
    if h > 0 { out.push(RU_HUNDREDS[h]); }
    if t == 1 {
        out.push(RU_TEENS[o]);
        return;
    }
    if t > 1 { out.push(RU_TENS[t]); }
    if o > 0 { out.push(if feminine { RU_ONES_F[o] } else { RU_ONES_M[o] }); }
}

/// 0 ..= 999 999 999 999 in words (masculine), e.g. 2024 → "две тысячи двадцать четыре".
pub fn spell_ru(n: u64) -> String {
    if n == 0 {
        return "ноль".into();
    }
    let mut out: Vec<&'static str> = Vec::new();
    let groups = [
        (1_000_000_000u64, false, ("миллиард", "миллиарда", "миллиардов")),
        (1_000_000, false, ("миллион", "миллиона", "миллионов")),
        (1_000, true, ("тысяча", "тысячи", "тысяч")),
    ];
    let mut rest = n;
    for (unit, fem, (one, few, many)) in groups {
        let k = rest / unit;
        if k > 0 {
            ru_triad(k, fem, &mut out);
            out.push(ru_plural(k, one, few, many));
            rest %= unit;
        }
    }
    ru_triad(rest, false, &mut out);
    out.join(" ")
}

static DIGIT_RUN: Lazy<Regex> = Lazy::new(|| Regex::new(r"(\d+)(?:([.,])(\d+))?").unwrap());

fn spell_run(run: &str, base: &str) -> String {
    let digit_by_digit = base != "ru" || run.len() > 12 || (run.len() > 1 && run.starts_with('0'));
    if digit_by_digit {
        let names = if base == "ru" { &RU_DIGITS } else { &EN_DIGITS };
        return run.bytes().map(|b| names[(b - b'0') as usize]).collect::<Vec<_>>().join(" ");
    }
    spell_ru(run.parse().unwrap_or(0))
}

/// "3.14" → "три точка четырнадцать", "05" → "ноль пять", "42" → "сорок два".
pub fn spell_leftover_digits(s: &str, base: &str) -> String {
    DIGIT_RUN.replace_all(s, |c: &regex::Captures| {
        let mut w = format!(" {} ", spell_run(&c[1], base));
        if let (Some(sep), Some(frac)) = (c.get(2), c.get(3)) {
            let word = match (base, sep.as_str()) {
                ("ru", ".") => "точка",
                ("ru", _) => "запятая",
                _ => "point",
            };
            w = format!(" {} {} {} ", spell_run(&c[1], base), word, spell_run(frac.as_str(), base));
        }
        w
    }).into_owned()
}

// ---- Latin capitals in Russian text --------------------------------------------
// "IQ" inside Russian would go to an <en> span and the model stumbles over a
// two-letter "word". Russian readers say acronyms letter by letter ("ай-кью"),
// a few as words ("НАТО"), and Roman numerals are numbers ("Глава IV").

const LETTER_NAMES: [&str; 26] = ["эй", "би", "си", "ди", "и", "эф", "джи", "эйч", "ай", "джей", "кей", "эл",
    "эм", "эн", "оу", "пи", "кью", "ар", "эс", "ти", "ю", "ви", "дабл-ю", "экс", "уай", "зед"];

const WORD_ACRONYMS: &[(&str, &str)] = &[
    ("OK", "ок+ей"), ("NASA", "н+аса"), ("NATO", "н+ато"), ("UNESCO", "юн+еско"), ("FIFA", "ф+ифа"),
    ("UEFA", "у+ефа"), ("IKEA", "ик+еа"), ("LEGO", "л+его"), ("BMW", "бэ-эм-в+э"), ("WiFi", "вайф+ай"),
    ("WIFI", "вайф+ай"), ("LED", "л+ед"), ("RAM", "р+ам"), ("ROM", "р+ом"), ("SIM", "с+им"),
    ("ISO", "+исо"), ("USA", "ю-эс-+эй"), ("PIN", "п+ин"), ("CAPS", "к+апс"),
];

/// Acronyms that happen to be valid Roman numerals.
const NOT_ROMAN: &[&str] = &["CD", "DC", "MC", "MD", "CV", "CL", "DM", "LCD", "MIC", "MIX", "DIV", "CIV", "LI", "MI", "DI", "MM", "CC"];

fn roman_value(s: &str) -> Option<u64> {
    let val = |c: char| match c { 'I' => 1, 'V' => 5, 'X' => 10, 'L' => 50, 'C' => 100, 'D' => 500, 'M' => 1000, _ => 0 };
    if s.is_empty() || s.chars().any(|c| val(c) == 0) {
        return None;
    }
    let (mut total, mut prev) = (0i64, 0i64);
    for c in s.chars().rev() {
        let v = val(c);
        if v < prev { total -= v } else { total += v; prev = v; }
    }
    // canonical form only ("IV" yes, "IIII"/"VX" no)
    let mut n = total;
    let mut canon = String::new();
    for (v, r) in [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"), (50, "L"),
                   (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")] {
        while n >= v { canon.push_str(r); n -= v; }
    }
    (total > 0 && total < 4000 && canon == s).then_some(total as u64)
}

fn spell_acronym(token: &str) -> String {
    let names: Vec<&str> = token.bytes().map(|b| LETTER_NAMES[(b.to_ascii_uppercase() - b'A') as usize]).collect();
    let mut out = names.join("-");
    // stress on the last letter name, as in "ай-кь+ю", "ю-эс-б+и"
    if let Some(pos) = out.char_indices().rev().find(|(_, c)| RU_VOWELS.contains(*c)).map(|(i, _)| i) {
        out.insert(pos, '+');
    }
    out
}

static LATIN_WORD: Lazy<Regex> = Lazy::new(|| Regex::new(r"\b[A-Za-z]+\b").unwrap());

/// Is the nearest letter on either side of [start, end) Cyrillic (within a few chars)?
fn cyrillic_neighbour(s: &str, start: usize, end: usize) -> bool {
    let near = |it: &mut dyn Iterator<Item = char>| -> Option<bool> {
        for c in it.take(4) {
            if c.is_alphabetic() {
                return Some(matches!(c, 'А'..='я' | 'Ё' | 'ё'));
            }
        }
        None
    };
    let left = near(&mut s[..start].chars().rev());
    let right = near(&mut s[end..].chars());
    left == Some(true) || right == Some(true) || (left.is_none() && right.is_none())
}

/// Russian-context Latin capitals → speakable Russian (base language "ru" only).
pub fn latin_caps_to_russian(s: &str) -> String {
    LATIN_WORD.replace_all(s, |c: &regex::Captures| {
        let m = c.get(0).unwrap();
        let tok = m.as_str();
        if !cyrillic_neighbour(s, m.start(), m.end()) {
            return tok.to_string();
        }
        if let Some((_, w)) = WORD_ACRONYMS.iter().find(|(a, _)| *a == tok) {
            return w.to_string();
        }
        let all_caps = tok.bytes().all(|b| b.is_ascii_uppercase());
        if !all_caps || tok.len() > 6 {
            return tok.to_string();
        }
        if !NOT_ROMAN.contains(&tok) && (tok.len() >= 2 || matches!(tok, "I" | "V" | "X")) {
            if let Some(n) = roman_value(tok) {
                return n.to_string(); // spelled by spell_leftover_digits
            }
        }
        if tok.len() >= 2 { spell_acronym(tok) } else { tok.to_string() }
    }).into_owned()
}

// ---- pauses at punctuation ----------------------------------------------------

/// Abbreviations after which "." does not end a sentence ("г. Москва", "т. е.").
const ABBREV: &[&str] = &["г", "гг", "т", "д", "др", "пр", "им", "ул", "стр", "рис", "см", "ср", "тыс", "млн",
    "млрд", "руб", "коп", "св", "ст", "проф", "акад", "доц", "ок", "mr", "mrs", "ms", "dr", "vs", "etc", "e", "i"];

/// Pause owed after a phrase that ends like `s`.
pub fn trailing_pause(s: &str) -> f32 {
    let t = s.trim_end_matches(|c: char| c.is_whitespace() || matches!(c, '»' | '"' | '“' | '”' | '\'' | ')'));
    if t.ends_with("...") || t.ends_with('…') {
        return PAUSE_ELLIPSIS;
    }
    match t.chars().last() {
        Some('.' | '!' | '?') => PAUSE_SENTENCE,
        Some(':') => PAUSE_COLON,
        Some(';') => PAUSE_SEMICOLON,
        Some('—' | '–') => PAUSE_DASH,
        Some(',') => PAUSE_COMMA,
        _ => 0.0,
    }
}

/// The word right before position `dot` is an initial ("А.") or an abbreviation.
fn abbrev_before(ch: &[char], dot: usize) -> bool {
    let mut b = dot;
    while b > 0 && ch[b - 1].is_alphabetic() {
        b -= 1;
    }
    let word: String = ch[b..dot].iter().collect();
    if word.is_empty() {
        return false;
    }
    if word.chars().count() == 1 && word.chars().all(char::is_uppercase) {
        return true; // initial
    }
    let lw: String = word.chars().flat_map(char::to_lowercase).collect();
    ABBREV.contains(&lw.as_str())
}

/// Split at sentence ends, colons, semicolons and spaced dashes. Each piece keeps
/// its punctuation (except a spaced dash, which becomes the pause itself) and
/// carries the pause owed after it. Commas are left to the model.
pub fn pause_split(text: &str) -> Vec<(String, f32)> {
    let ch: Vec<char> = text.chars().collect();
    let mut out: Vec<(String, f32)> = Vec::new();
    let push = |piece: String, pause: f32, out: &mut Vec<(String, f32)>| {
        let t = piece.trim().to_string();
        if t.chars().any(|c| c.is_alphanumeric()) {
            out.push((t, pause));
        } else if let Some(last) = out.last_mut() {
            last.1 = last.1.max(pause); // stray punctuation: only its pause counts
        }
    };
    let mut start = 0;
    let mut i = 0;
    while i < ch.len() {
        let c = ch[i];
        let ws_after = ch.get(i + 1).map_or(false, |n| n.is_whitespace());
        match c {
            ':' | ';' if ws_after => {
                let piece: String = ch[start..=i].iter().collect();
                let p = trailing_pause(&piece);
                push(piece, p, &mut out);
                start = i + 1;
            }
            '—' | '–' | '-' if i > 0 && ch[i - 1].is_whitespace() && ws_after => {
                let piece: String = ch[start..i].iter().collect();
                let p = trailing_pause(&piece).max(PAUSE_DASH);
                push(piece, p, &mut out);
                start = i + 1;
            }
            '.' | '!' | '?' | '…' => {
                let mut j = i;
                while j + 1 < ch.len() && matches!(ch[j + 1], '.' | '!' | '?' | '…' | '»' | '"' | '”') {
                    j += 1;
                }
                let mut k = j + 1;
                while k < ch.len() && ch[k].is_whitespace() {
                    k += 1;
                }
                let boundary = k > j + 1 && k < ch.len()
                    && (ch[k].is_uppercase() || ch[k].is_numeric() || matches!(ch[k], '—' | '–' | '-' | '«' | '"' | '“'));
                let is_abbrev = c == '.' && j == i && abbrev_before(&ch, i);
                if boundary && !is_abbrev {
                    let piece: String = ch[start..=j].iter().collect();
                    let p = trailing_pause(&piece);
                    push(piece, p, &mut out);
                    start = j + 1;
                }
                i = j;
            }
            _ => {}
        }
        i += 1;
    }
    let tail: String = ch[start..].iter().collect();
    let p = trailing_pause(&tail);
    push(tail, p, &mut out);
    out
}

// ---- parenthetical asides -------------------------------------------------------

/// Split text into (segment, is_aside). Round brackets mark asides; nested
/// brackets stay inside the outer aside; an unclosed "(" runs to the end.
pub fn split_asides(text: &str) -> Vec<(String, bool)> {
    let mut out: Vec<(String, bool)> = Vec::new();
    let mut normal = String::new();
    let mut aside = String::new();
    let mut depth = 0usize;
    let push = |out: &mut Vec<(String, bool)>, s: &str, is_aside: bool| {
        let t = s.trim();
        if t.is_empty() {
            return;
        }
        match out.last_mut() {
            Some(last) if last.1 == is_aside => {
                last.0.push(' ');
                last.0.push_str(t);
            }
            _ => out.push((t.to_string(), is_aside)),
        }
    };
    let close_aside = |out: &mut Vec<(String, bool)>, normal: &mut String, aside: &mut String| {
        let letters = aside.chars().filter(|c| c.is_alphabetic()).count();
        if letters >= ASIDE_MIN_LETTERS {
            push(out, normal, false);
            normal.clear();
            push(out, aside, true);
        } else {
            // short: keep inline, brackets dropped
            normal.push_str(aside);
        }
        aside.clear();
    };
    for c in text.chars() {
        match c {
            '(' => {
                if depth > 0 {
                    aside.push(' ');
                }
                depth += 1;
            }
            ')' if depth > 0 => {
                depth -= 1;
                if depth == 0 {
                    close_aside(&mut out, &mut normal, &mut aside);
                } else {
                    aside.push(' ');
                }
            }
            ')' => {}
            _ if depth > 0 => aside.push(c),
            _ => normal.push(c),
        }
    }
    if depth > 0 {
        close_aside(&mut out, &mut normal, &mut aside);
    }
    push(&mut out, &normal, false);
    out
}

/// Signs whose Unicode compatibility form is Latin letters ("№" → "No") would be
/// read as English after NFKD; spell them out first.
pub fn spell_signs(s: &str, base: &str) -> String {
    if base != "ru" {
        return s.replace("№№", " numbers ").replace('№', " number ");
    }
    s.replace("№№", " номера ").replace('№', " номер ")
        .replace('℃', " градусов Цельсия ").replace('§', " параграф ")
}

/// Full front-end: returns the exact string for the text encoder (with '+'),
/// or None if nothing speakable is left.
pub fn prepare(text: &str, lang: &str, table: &[i64]) -> Option<String> {
    let t: String = text.nfc().collect();
    let t = t.replace(['<', '>'], " ");
    let base0 = if lang.to_lowercase().starts_with("en") { "en" } else { "ru" };
    let t = spell_signs(&t, base0);
    let t = acute_to_plus(&t);
    let t = clean_plus(&t);
    let t = dashes_to_pauses(&t);
    let t = add_punct_spaces(&t);
    let t = add_number_spaces(&t);
    let base = if lang.to_lowercase().starts_with("en") { "en" } else { "ru" };
    let t = if base == "ru" { latin_caps_to_russian(&t) } else { t };
    let t = spell_leftover_digits(&t, base);
    let t = skip_unknown(&t, table);
    let t = COMMA_RUN.replace_all(&t, ",");
    let t = WS.replace_all(&t, " ").trim().to_string();
    if !has_word(&t) {
        return None;
    }
    Some(wrap_tags(&t, base).nfkd().collect())
}

/// The duration predictor sees the text without stress marks.
pub fn strip_stress(s: &str) -> String {
    s.replace('+', "")
}

pub fn latent_len(duration_s: f32) -> usize {
    ((duration_s * SAMPLE_RATE as f32 / SAMPLES_PER_FRAME as f32).ceil() as usize).max(1)
}

pub fn token_ids(text: &str, table: &[i64]) -> Result<Vec<i64>> {
    text.chars()
        .map(|c| {
            let u = c as usize;
            match table.get(u) {
                Some(&id) if id >= 0 => Ok(id),
                _ => bail!("character outside vocabulary: U+{:04X}", u),
            }
        })
        .collect()
}

// ----------------------------------------------------------------------------
// .npy (NumPy) reader — float32/float64, C order. Used for voice styles.
// ----------------------------------------------------------------------------

pub fn read_npy_f32(bytes: &[u8]) -> Result<(Vec<usize>, Vec<f32>)> {
    if bytes.len() < 10 || &bytes[..6] != b"\x93NUMPY" {
        bail!("not a .npy file");
    }
    let major = bytes[6];
    let (hlen, hstart) = if major == 1 {
        (u16::from_le_bytes([bytes[8], bytes[9]]) as usize, 10)
    } else {
        if bytes.len() < 12 {
            bail!("truncated .npy header");
        }
        (u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]) as usize, 12)
    };
    let header = std::str::from_utf8(bytes.get(hstart..hstart + hlen).context("truncated .npy header")?)?;
    if header.contains("'fortran_order': True") {
        bail!("Fortran-ordered .npy not supported");
    }
    let descr = header
        .split("'descr':")
        .nth(1)
        .and_then(|r| r.split('\'').nth(1))
        .context("no descr in .npy header")?;
    let shape_src = header
        .split("'shape':")
        .nth(1)
        .and_then(|r| r.split('(').nth(1))
        .and_then(|r| r.split(')').next())
        .context("no shape in .npy header")?;
    let shape: Vec<usize> = shape_src
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| s.parse::<usize>())
        .collect::<std::result::Result<_, _>>()?;
    let n: usize = shape.iter().product();
    let data = &bytes[hstart + hlen..];
    let values: Vec<f32> = match descr {
        "<f4" => {
            if data.len() < n * 4 { bail!("truncated .npy data"); }
            data.chunks_exact(4).take(n).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect()
        }
        "<f8" => {
            if data.len() < n * 8 { bail!("truncated .npy data"); }
            data.chunks_exact(8)
                .take(n)
                .map(|b| f64::from_le_bytes(b.try_into().unwrap()) as f32)
                .collect()
        }
        other => bail!("unsupported .npy dtype {other}"),
    };
    Ok((shape, values))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn punct_and_numbers() {
        assert_eq!(add_punct_spaces("Цена:100 рублей!Он"), "Цена: 100 рублей! Он");
        assert_eq!(add_punct_spaces("3,5 и 3.5."), "3,5 и 3.5.");
        assert_eq!(add_punct_spaces("Ну...Да?!Нет"), "Ну... Да?! Нет");
        assert_eq!(add_number_spaces("21год"), "21 год");
    }

    #[test]
    fn plus_and_dashes() {
        assert_eq!(clean_plus("C++ и з+амок, 2+2"), "C и з+амок, 22");
        assert_eq!(dashes_to_pauses("— Привет, — сказал он. — Как дела?"), "Привет, сказал он. Как дела?");
        assert_eq!(dashes_to_pauses("Москва — столица, 1941–1945"), "Москва, столица, 1941-1945");
    }

    #[test]
    fn acute() {
        assert_eq!(acute_to_plus("Замо́к и молоко́. Ёлка"), "Зам+ок и молок+о. Ёлка");
        assert_eq!(acute_to_plus("x\u{301}y"), "xy");
        // other ways to mark stress
        assert_eq!(acute_to_plus("замо+к, молоко+. дома+"), "зам+ок, молок+о. дом+а");
        assert_eq!(acute_to_plus("зам+ок з+амок 2+2"), "зам+ок з+амок 2+2");
        assert_eq!(acute_to_plus("замóк, ЗАМÓК, сáмый, у́же"), "зам+ок, ЗАМ+ОК, с+амый, +уже");
        assert_eq!(acute_to_plus("Café и ó"), "Café и ó");
        assert_eq!(acute_to_plus("за\u{300}мок"), "з+амок");
        assert_eq!(pre_normalize("за\u{300}мок"), "з+амок");
        assert_eq!(acute_to_plus("зам+о\u{301}к"), "зам+ок");
        // the whole front-end keeps the converted marks
        let mut table = vec![-1i64; 65536];
        for (i, c) in "замокЗи. <>/ru+".chars().enumerate() { table[c as usize] = i as i64 + 1; }
        assert_eq!(prepare("Замо́к", "ru", &table).unwrap(), "<ru>Зам+ок</ru>");
    }

    #[test]
    fn fixed_stress() {
        // unmarked words are pinned; a mark already in the text is kept
        assert_eq!(pin_fixed_stress("Глава один. Один из нас, ОДИН!"), "Глава од+ин. Од+ин из нас, ОД+ИН!");
        assert_eq!(pin_fixed_stress("Глава +один"), "Глава +один");
        assert_eq!(pin_fixed_stress("он нем и п+осле"), "он н+ём и п+осле");
        assert_eq!(pin_fixed_stress("д+олги ночи"), "д+олги ночи"); // marked: kept
        assert_eq!(pin_fixed_stress("одинокий"), "одинокий");
        assert_eq!(pin_fixed_stress("вернуть все свои долги."), "вернуть все свои долг+и.");
        assert!(is_pinned("долги") && !is_pinned("замок"));
    }

    #[test]
    fn digits() {
        assert_eq!(spell_ru(2024), "две тысячи двадцать четыре");
        assert_eq!(spell_ru(1_000_001), "один миллион один");
        assert_eq!(spell_ru(21_000), "двадцать одна тысяча");
        assert_eq!(spell_ru(512_115), "пятьсот двенадцать тысяч сто пятнадцать");
        assert_eq!(spell_leftover_digits("Пи 3.14!", "ru").split_whitespace().collect::<Vec<_>>().join(" "),
                   "Пи три точка четырнадцать !");
        let ws = |s: String| s.split_whitespace().collect::<Vec<_>>().join(" ");
        assert_eq!(ws(spell_leftover_digits("код 007", "ru")), "код ноль ноль семь");
        assert_eq!(ws(spell_leftover_digits("room 42", "en")), "room four two");
    }

    #[test]
    fn latin_caps() {
        let f = |x: &str| latin_caps_to_russian(x);
        assert_eq!(f("Мой IQ высокий"), "Мой ай-кь+ю высокий");
        assert_eq!(f("порт USB и GPS-приёмник"), "порт ю-эс-б+и и джи-пи-+эс-приёмник");
        assert_eq!(f("Глава IV. В XIX веке Пётр I"), "Глава 4. В 19 веке Пётр 1");
        assert_eq!(f("купил CD диск, витамин C"), "купил си-д+и диск, витамин C");
        assert_eq!(f("в NATO и OK"), "в н+ато и ок+ей");
        assert_eq!(f("открыл iPhone и Windows"), "открыл iPhone и Windows");
        assert_eq!(f("He said: my IQ is high"), "He said: my IQ is high");
        assert_eq!(roman_value("IIII"), None);
        assert_eq!(roman_value("MCMXCIX"), Some(1999));
    }

    #[test]
    fn asides() {
        let a = split_asides("В сфере изучения мозга доктора Наумова (как будто фамилия должна была что-то сказать) работали многие.");
        assert_eq!(a, vec![
            ("В сфере изучения мозга доктора Наумова".to_string(), false),
            ("как будто фамилия должна была что-то сказать".to_string(), true),
            ("работали многие.".to_string(), false),
        ]);
        let b = split_asides("Пункт (1) и знак (с) остаются.");
        assert_eq!(b, vec![("Пункт 1 и знак с остаются.".to_string(), false)]);
        let c = split_asides("Текст (вставка (вложенная) тут) конец");
        assert_eq!(c[1], ("вставка  вложенная  тут".to_string(), true));
        let d = split_asides("Обрыв (без закрывающей скобки");
        assert_eq!(d.len(), 2);
        assert!(d[1].1);
        assert_eq!(split_asides("(целиком в скобках)"), vec![("целиком в скобках".to_string(), true)]);
    }

    #[test]
    fn pauses() {
        let p = pause_split("Он сказал: завтра будет дождь. А. С. Пушкин жил в г. Москва; это все знают — правда? Да...");
        let texts: Vec<&str> = p.iter().map(|x| x.0.as_str()).collect();
        assert_eq!(texts, vec!["Он сказал:", "завтра будет дождь.", "А. С. Пушкин жил в г. Москва;",
                               "это все знают", "правда?", "Да..."]);
        let pauses: Vec<f32> = p.iter().map(|x| x.1).collect();
        assert_eq!(pauses, vec![PAUSE_COLON, PAUSE_SENTENCE, PAUSE_SEMICOLON, PAUSE_DASH, PAUSE_SENTENCE, PAUSE_ELLIPSIS]);
        // dialogue dash at the start is not a split point; "т. е." is not a sentence end
        let d = pause_split("— Привет, — сказал он, т. е. наш гость.");
        assert_eq!(d.iter().map(|x| x.0.as_str()).collect::<Vec<_>>(), vec!["— Привет,", "сказал он, т. е. наш гость."]);
        assert_eq!(d[0].1, PAUSE_DASH);
        assert_eq!(pause_split("Время 3.14 и 12:05 тут")[0].0, "Время 3.14 и 12:05 тут");
    }

    #[test]
    fn pause_scale_roundtrip() {
        set_pause_scale(1.5);
        assert_eq!(pause_scale(), 1.5);
        set_pause_scale(f32::NAN);
        assert_eq!(pause_scale(), 1.0);
        set_pause_scale(9.0);
        assert_eq!(pause_scale(), 3.0);
        set_pause_scale(1.0);
    }

    #[test]
    fn scene_and_paragraph() {
        for t in ["* * *", "***", "⁂", "— — —", "  *  *  *  ", "###", "~~~"] {
            assert!(is_scene_break(t), "{t}");
        }
        for t in ["*", "—", "Глава", "* текст *", "-", ""] {
            assert!(!is_scene_break(t), "{t}");
        }
        assert!((paragraph_pause() - 0.9).abs() < 1e-6);
        set_paragraph_pause(0.5);
        assert_eq!(paragraph_pause(), 0.5);
        set_paragraph_pause(0.9);
    }

    #[test]
    fn signs() {
        let ws = |s: String| s.split_whitespace().collect::<Vec<_>>().join(" ");
        assert_eq!(ws(spell_signs("Дом №5, квартиры №№ 3-4", "ru")), "Дом номер 5, квартиры номера 3-4");
        let mut table = vec![-1i64; 65536];
        for (i, c) in "домерномер<>/ru+ Дн".chars().enumerate() { table[c as usize] = i as i64 + 1; }
        let out = prepare("Дом №", "ru", &table).unwrap();
        assert!(out.contains("номер") && !out.contains("No"), "{out}");
    }

    #[test]
    fn utterance_latency() {
        assert_eq!(utterance_end_pause(0.3), 0.3); // nothing measured yet
        note_first_chunk_latency(0.7);
        assert!((utterance_end_pause(0.3) - 0.1).abs() < 1e-5);
        note_first_chunk_latency(2.0); // ema 0.7*0.7+0.3*2 = 1.09
        assert_eq!(utterance_end_pause(0.3), 0.0);
    }

    #[test]
    fn latent() {
        assert_eq!(latent_len(1.0), 15); // 44100/3072 = 14.36 → 15
        assert_eq!(latent_len(0.0), 1);
    }

    #[test]
    fn npy_roundtrip() {
        let mut b = b"\x93NUMPY\x01\x00".to_vec();
        let h = "{'descr': '<f4', 'fortran_order': False, 'shape': (1, 2, 3), }";
        let mut hp = h.to_string();
        while (10 + hp.len() + 1) % 64 != 0 { hp.push(' '); }
        hp.push('\n');
        b.extend_from_slice(&(hp.len() as u16).to_le_bytes());
        b.extend_from_slice(hp.as_bytes());
        for v in [1.0f32, 2.0, 3.0, 4.0, 5.0, 6.5] { b.extend_from_slice(&v.to_le_bytes()); }
        let (s, v) = read_npy_f32(&b).unwrap();
        assert_eq!(s, vec![1, 2, 3]);
        assert_eq!(v[5], 6.5);
    }
}
