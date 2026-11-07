// ============================================================================
// Homograph resolution — port of silero-stress v1.5 `HomoSolver` (MIT).
//
// Pure logic: finding homographs, building the context window exactly like
// silero's `_clean_text`, phrase rules, BERT WordPiece tokenization and
// writing the chosen stress back. The two ONNX graphs (encoder + head) are
// run by helper.rs. Data files come from export_homosolver.py.
//
// Difference from the original: words that already carry stress marks are
// still resolved (marks are stripped for lookup), so a context decision
// overrides a fixed dictionary choice ("за́мок" → "замо́к" on a door).
// ============================================================================

use anyhow::{Context, Result};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::path::Path;

fn is_cyr_letter(c: char) -> bool {
    matches!(c, 'а'..='я' | 'А'..='Я' | 'ё' | 'Ё')
}

fn lower(s: &str) -> String {
    s.chars().flat_map(char::to_lowercase).collect()
}

/// One homograph occurrence.
#[derive(Debug, Clone)]
pub struct Candidate {
    /// byte range of the word (including any '+') in the input text
    pub start: usize,
    pub end: usize,
    /// the word without stress marks, original casing
    pub word: String,
    pub word_lower: String,
    /// "<left> [HOMO] word [/HOMO] <right>"
    pub raw_mark: String,
    /// decided by a phrase rule (no model call needed)
    pub phrase_choice: Option<String>,
}

pub struct HomoData {
    homodict: HashMap<String, Vec<String>>, // variants, sorted (as Python `sorted`)
    phrases: HashMap<String, Vec<(String, Vec<Vec<char>>)>>, // word → [(variant, phrases longest-first)]
    vocab: HashMap<String, i64>,
    never_split: HashSet<String>,
    unk_id: i64,
    cls_id: i64,
    sep_id: i64,
    pub homo_start_id: i64,
    pub homo_end_id: i64,
    window: usize,
    pub hidden_size: usize,
}

impl HomoData {
    pub fn load(dir: &Path) -> Result<Self> {
        let read = |name: &str| -> Result<Value> {
            let p = dir.join(name);
            let s = std::fs::read_to_string(&p).with_context(|| format!("read {}", p.display()))?;
            Ok(serde_json::from_str(&s)?)
        };
        let meta = read("meta.json")?;
        let num = |k: &str| meta[k].as_i64().with_context(|| format!("meta.json: {k}"));

        let mut homodict = HashMap::new();
        for (w, vs) in read("homodict.json")?.as_object().context("homodict.json")? {
            let mut v: Vec<String> = vs.as_array().context("homodict variants")?
                .iter().filter_map(|x| x.as_str().map(String::from)).collect();
            v.sort(); // codepoint order == Python sorted()
            homodict.insert(w.clone(), v);
        }

        // phrases.json keeps the original variant order (serde_json preserve_order)
        let mut phrases = HashMap::new();
        for (w, variants) in read("phrases.json")?.as_object().context("phrases.json")? {
            let mut list = Vec::new();
            for (variant, ps) in variants.as_object().context("phrase variants")? {
                let mut ps: Vec<String> = ps.as_array().context("phrase list")?
                    .iter().filter_map(|x| x.as_str().map(String::from)).collect();
                // Python: sorted(phrases, key=len, reverse=True) — stable, by char count
                ps.sort_by(|a, b| b.chars().count().cmp(&a.chars().count()));
                list.push((variant.clone(), ps.iter().map(|p| lower(p).chars().collect()).collect()));
            }
            phrases.insert(w.clone(), list);
        }

        let vocab: HashMap<String, i64> = serde_json::from_value(read("vocab.json")?)?;
        let never_split = meta["never_split"].as_array().map(|a| {
            a.iter().filter_map(|x| x.as_str().map(String::from)).collect()
        }).unwrap_or_default();

        Ok(HomoData {
            homodict,
            phrases,
            vocab,
            never_split,
            unk_id: num("unk_id")?,
            cls_id: num("cls_id")?,
            sep_id: num("sep_id")?,
            homo_start_id: num("homo_start_id")?,
            homo_end_id: num("homo_end_id")?,
            window: num("window_size")? as usize,
            hidden_size: num("hidden_size")? as usize,
        })
    }

    pub fn variants(&self, word_lower: &str) -> Option<&Vec<String>> {
        self.homodict.get(word_lower)
    }

    // ---- silero `_clean_text` ---------------------------------------------

    pub fn clean_text(text: &str, is_start: bool) -> String {
        if text.is_empty() {
            return String::new();
        }
        // remove_extra: keep a-zA-Zа-яА-ЯёЁ0-9 \s . ! ? , -
        let t: String = text.chars().filter(|&c| {
            c.is_ascii_alphanumeric() || is_cyr_letter(c) || c.is_whitespace() || ".!?,-".contains(c)
        }).collect();
        let t = collapse_ws(&t);
        let t = replace_dash_runs(&t);           // -{2,} → " - "
        let t = collapse_repeat_punct(&t);       // ([.!?])\1+ → \1
        let t = collapse_commas(&t);             // ,{2,} → ,
        let t = remove_space_before_punct(&t);   // \s+([.,!?]) → \1
        let t = collapse_repeat_punct(&t);
        let t = collapse_commas(&t);
        let t = add_space_after_punct(&t);       // ([.,!?])(?=\S) → "\1 "
        let mut t = collapse_ws(&t).trim().to_string();
        if is_start {
            t = t.trim_start_matches(|c| " .,!?-".contains(c)).to_string();
            if let Some(first) = t.chars().next() {
                let rest: String = t.chars().skip(1).collect();
                t = format!("{}{}", first.to_uppercase(), lower(&rest));
            }
        } else {
            if let Some(first) = t.chars().next() {
                let rest: String = t.chars().skip(1).collect();
                t = format!("{}{}", first, lower(&rest));
            }
            if !t.is_empty() && !t.ends_with(['.', '!', '?']) {
                t.push('.');
            }
        }
        t
    }

    // ---- finding homographs ------------------------------------------------

    /// Words = runs of Cyrillic letters and '+'. A word is a candidate if its
    /// unmarked lowercase form is in the homograph dictionary or phrase rules.
    pub fn find(&self, text: &str) -> Vec<Candidate> {
        let mut out = Vec::new();
        let mut i = 0;
        let bytes_len = text.len();
        while i < bytes_len {
            let c = text[i..].chars().next().unwrap();
            if !(is_cyr_letter(c) || c == '+') {
                i += c.len_utf8();
                continue;
            }
            let start = i;
            while i < bytes_len {
                let c = text[i..].chars().next().unwrap();
                if is_cyr_letter(c) || c == '+' { i += c.len_utf8(); } else { break; }
            }
            let end = i;
            let word: String = text[start..end].chars().filter(|&c| c != '+').collect();
            if word.is_empty() {
                continue;
            }
            let word_lower = lower(&word);
            let in_dict = self.homodict.contains_key(&word_lower);
            let in_phr = self.phrases.contains_key(&word_lower);
            if !(in_dict || in_phr) {
                continue;
            }
            let strip = |s: &str| s.replace('+', "");
            let left = Self::clean_text(&strip(&text[..start]), true);
            let right = Self::clean_text(&strip(&text[end..]), false);
            let half = self.window / 2;
            let lc: Vec<char> = left.chars().collect();
            let left: String = lc[lc.len().saturating_sub(half)..].iter().collect();
            let right: String = right.chars().take(half).collect();
            let raw_mark = format!("{left} [HOMO] {word_lower} [/HOMO] {right}").trim().to_string();
            let phrase_choice = if in_phr { self.match_phrase(&word_lower, &raw_mark) } else { None };
            if phrase_choice.is_none() && !in_dict {
                continue;
            }
            out.push(Candidate { start, end, word, word_lower, raw_mark, phrase_choice });
        }
        out
    }

    /// Phrase rules: earliest match in the text wins; at one position the
    /// variants are tried in file order, phrases longest first. Matches must
    /// not touch Cyrillic letters or '-' on either side (case-insensitive).
    fn match_phrase(&self, word_lower: &str, raw_mark: &str) -> Option<String> {
        let rules = self.phrases.get(word_lower)?;
        let text: Vec<char> = lower(raw_mark).chars().collect();
        let blocked = |c: Option<&char>| c.map_or(false, |&c| is_cyr_letter(c) || c == '-');
        for pos in 0..text.len() {
            if pos > 0 && blocked(text.get(pos - 1)) {
                continue;
            }
            for (variant, phrases) in rules {
                for p in phrases {
                    if text.len() - pos >= p.len() && text[pos..pos + p.len()] == p[..] && !blocked(text.get(pos + p.len())) {
                        return Some(variant.clone());
                    }
                }
            }
        }
        None
    }

    // ---- BERT tokenizer (SimpleBertTokenizer.encode) ----------------------

    fn is_punct(c: char) -> bool {
        let cp = c as u32;
        (33..=47).contains(&cp) || (58..=64).contains(&cp) || (91..=96).contains(&cp) || (123..=126).contains(&cp)
            || matches!(c, '«' | '»' | '—' | '–' | '…' | '„' | '“' | '”' | '‘' | '’')
    }

    fn basic_tokenize(&self, text: &str) -> Vec<String> {
        let cleaned: String = text.chars().filter_map(|c| {
            if c == '\0' || c == '\u{fffd}' || (c.is_control() && !"\t\n\r".contains(c)) {
                None
            } else if c.is_whitespace() {
                Some(' ')
            } else {
                Some(c)
            }
        }).collect();
        let mut out = Vec::new();
        for tok in cleaned.split_whitespace() {
            if self.never_split.contains(tok) {
                out.push(tok.to_string());
                continue;
            }
            let mut cur = String::new();
            for c in tok.chars() {
                if Self::is_punct(c) {
                    if !cur.is_empty() { out.push(std::mem::take(&mut cur)); }
                    out.push(c.to_string());
                } else {
                    cur.push(c);
                }
            }
            if !cur.is_empty() { out.push(cur); }
        }
        out
    }

    fn wordpiece(&self, token: &str, out: &mut Vec<i64>) {
        let chars: Vec<char> = token.chars().collect();
        if chars.len() > 100 {
            out.push(self.unk_id);
            return;
        }
        let mut pieces = Vec::new();
        let mut start = 0;
        while start < chars.len() {
            let mut end = chars.len();
            let mut found = None;
            while start < end {
                let mut sub: String = chars[start..end].iter().collect();
                if start > 0 { sub = format!("##{sub}"); }
                if let Some(&id) = self.vocab.get(&sub) {
                    found = Some(id);
                    break;
                }
                end -= 1;
            }
            match found {
                Some(id) => { pieces.push(id); start = end; }
                None => { out.push(self.unk_id); return; }
            }
        }
        out.extend(pieces);
    }

    pub fn encode(&self, text: &str) -> Vec<i64> {
        let mut ids = vec![self.cls_id];
        for tok in self.basic_tokenize(text) {
            match self.vocab.get(&tok) {
                Some(&id) if self.never_split.contains(&tok) => ids.push(id),
                _ => self.wordpiece(&tok, &mut ids),
            }
        }
        ids.push(self.sep_id);
        ids
    }

    // ---- writing the decision back ----------------------------------------

    /// `variant` like "зам+ок": copy the casing of `word`, keep one '+'.
    pub fn render(word: &str, variant: &str) -> String {
        let stress_idx = variant.chars().position(|c| c == '+');
        let plain: Vec<char> = variant.chars().filter(|&c| c != '+').collect();
        let src: Vec<char> = word.chars().collect();
        let mut cased: Vec<char> = Vec::with_capacity(plain.len() + 1);
        for (i, &p) in plain.iter().enumerate() {
            // Python zip() truncates to the shorter word
            let Some(&s) = src.get(i) else { break };
            if s.is_lowercase() { cased.extend(p.to_lowercase()); } else { cased.extend(p.to_uppercase()); }
        }
        if let Some(k) = stress_idx {
            if k <= cased.len() { cased.insert(k, '+'); }
        }
        cased.into_iter().collect()
    }

    /// Replace each candidate in `text` with its rendered choice.
    pub fn apply(text: &str, decided: &[(Candidate, String)]) -> String {
        let mut out = String::with_capacity(text.len() + decided.len());
        let mut pos = 0;
        for (c, variant) in decided {
            out.push_str(&text[pos..c.start]);
            out.push_str(&Self::render(&c.word, variant));
            pos = c.end;
        }
        out.push_str(&text[pos..]);
        out
    }
}

fn collapse_ws(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev_ws = false;
    for c in s.chars() {
        if c.is_whitespace() {
            if !prev_ws { out.push(' '); }
            prev_ws = true;
        } else {
            out.push(c);
            prev_ws = false;
        }
    }
    out
}

fn replace_dash_runs(s: &str) -> String {
    let ch: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < ch.len() {
        if ch[i] == '-' {
            let mut j = i;
            while j < ch.len() && ch[j] == '-' { j += 1; }
            if j - i >= 2 { out.push_str(" - "); } else { out.push('-'); }
            i = j;
        } else {
            out.push(ch[i]);
            i += 1;
        }
    }
    out
}

fn collapse_repeat_punct(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev: Option<char> = None;
    for c in s.chars() {
        if ".!?".contains(c) && prev == Some(c) { continue; }
        out.push(c);
        prev = Some(c);
    }
    out
}

fn collapse_commas(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev_comma = false;
    for c in s.chars() {
        if c == ',' && prev_comma { continue; }
        out.push(c);
        prev_comma = c == ',';
    }
    out
}

fn remove_space_before_punct(s: &str) -> String {
    let ch: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < ch.len() {
        if ch[i].is_whitespace() {
            let mut j = i;
            while j < ch.len() && ch[j].is_whitespace() { j += 1; }
            if j < ch.len() && ".,!?".contains(ch[j]) {
                i = j;
                continue;
            }
            out.extend(&ch[i..j]);
            i = j;
        } else {
            out.push(ch[i]);
            i += 1;
        }
    }
    out
}

fn add_space_after_punct(s: &str) -> String {
    let ch: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len() + 8);
    for i in 0..ch.len() {
        out.push(ch[i]);
        if ".,!?".contains(ch[i]) && ch.get(i + 1).map_or(false, |n| !n.is_whitespace()) {
            out.push(' ');
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean() {
        assert_eq!(HomoData::clean_text("  Старинный   каменный ", true), "Старинный каменный");
        assert_eq!(HomoData::clean_text(" на горе", false), "на горе.");
        assert_eq!(HomoData::clean_text("Ну...  Да!!,,нет", true), "Ну. да! , нет");
        assert_eq!(HomoData::clean_text("— Привет, — сказал он. ", true), "Привет, сказал он.");
        assert_eq!(HomoData::clean_text("a--b", false), "a - b.");
    }

    #[test]
    fn render_case() {
        assert_eq!(HomoData::render("Замок", "зам+ок"), "Зам+ок");
        assert_eq!(HomoData::render("ВСЕ", "вс+ё"), "ВС+Ё");
        assert_eq!(HomoData::render("уже", "уж+е"), "уж+е");
    }

    /// Full check against vectors exported by export_homosolver.py:
    ///   HOMO_DIR=~/teratts-books/homosolver_onnx cargo test homo -- --nocapture
    #[test]
    fn against_python_vectors() {
        let Ok(dir) = std::env::var("HOMO_DIR") else { return };
        let dir = std::path::PathBuf::from(dir);
        let data = HomoData::load(&dir).expect("load homosolver data");
        let vectors: Value = serde_json::from_str(&std::fs::read_to_string(dir.join("test_vectors.json")).unwrap()).unwrap();
        let sentences = [
            "Старинный замок на горе.", "Ржавый замок на двери.",
            "Он долго не мог открыть замок.", "Мы осматривали замок короля.",
            "Мука для блинов закончилась.", "Это была настоящая мука.",
            "Все сестры стоят на берегу.", "Все уже знают об этом.", "Я уже готов.",
            "Дорога шла через лес.", "Эта вещь мне очень дорога.",
            "В церкви играл орган.", "Печень — важный орган.",
            "Плачу деньги за квартиру.", "Я плачу от счастья.",
            "Мы пили чай.", "Атлас мира лежал на столе.", "Платье из атласа.",
            "Белки собирали орехи.", "В яйце есть белки и жиры.",
            "Сколько это стоит?", "Я стою у окна.", "Видишь, пора домой.",
            "Он взял кружки с чаем.", "Мы вышли из кружка любителей.",
            "Старинный каменный замок на вершине утёса молчаливо взирал на долину. \
             Дубовая дверь, окованная железом, оказалась заперта на ржавый замок.",
        ];
        let mut ours = Vec::new();
        for s in sentences {
            // export_homosolver.py records every candidate that is in homodict
            ours.extend(data.find(s).into_iter().filter(|c| data.variants(&c.word_lower).is_some()));
        }
        let vs = vectors.as_array().unwrap();
        let (mut bad_mark, mut bad_ids) = (0, 0);
        for (i, v) in vs.iter().enumerate() {
            let want_mark = v["raw_mark"].as_str().unwrap();
            let want_ids: Vec<i64> = serde_json::from_value(v["ids"].clone()).unwrap();
            let got_ids = data.encode(want_mark);
            if got_ids != want_ids {
                bad_ids += 1;
                println!("ids ❌ {want_mark}\n   want {want_ids:?}\n   got  {got_ids:?}");
            }
            match ours.get(i) {
                Some(c) if c.raw_mark == want_mark => {}
                other => {
                    bad_mark += 1;
                    println!("mark ❌ want: {want_mark}\n        got:  {:?}", other.map(|c| &c.raw_mark));
                }
            }
        }
        println!("{} векторов: окно контекста ❌{bad_mark}, токены ❌{bad_ids}", vs.len());
        assert_eq!((bad_mark, bad_ids), (0, 0));
    }
}

// ---- the case of a feminine noun in -ы/-и, by its neighbours (rules, no network) ----
//
// "сестры": сестры́ (of the sister, one) or сёстры (sisters)? The network chose the plural nearly
// always ("Красота сестры…" was read "сёстры": tolyos). What stands right before the word
// decides it in most sentences: a preposition of the genitive ("у сестры"), "нет", a feminine
// adjective in -ой/-ей ("младшей сестры"), a noun ("красота сестры") — one sister; a plural
// word ("мои", "младшие") — sisters. Where none of this is clear, the network decides as before.

/// Feminine nouns whose one-sister form (genitive) is stressed on the ending, the plural on the stem.
const GEN_OR_PLURAL: &[&str] = &[
    "беды", "блохи", "бороды", "борозды", "вдовы", "величины", "версты", "весны", "воды", "войны", "волны",
    "высоты", "глубины", "головы", "горы", "грозы", "губы", "десны", "длины", "доски", "дуги", "души", "дыры",
    "жены", "звезды", "земли", "зимы", "змеи", "иглы", "игры", "козы", "копны", "косы", "красоты", "лисы",
    "лозы", "луны", "метлы", "ноги", "норы", "нужды", "овцы", "орды", "осы", "пилы", "плиты", "полосы", "пчелы",
    "реки", "росы", "руки", "свечи", "свиньи", "семьи", "сестры", "скалы", "слезы", "смолы", "совы", "сосны",
    "спины", "среды", "стены", "стороны", "страны", "стрелы", "стрельбы", "строки", "строфы", "струны",
    "судьбы", "судьи", "травы", "трубы", "тюрьмы", "цены", "щеки",
];

const GEN_PREPS: &[&str] = &[
    "у", "для", "без", "от", "до", "из", "из-за", "из-под", "около", "возле", "после", "кроме", "вместо",
    "среди", "против", "напротив", "вокруг", "мимо", "вдоль", "ради", "позади", "впереди", "внутри", "вне",
    "сверх", "подле", "близ", "вроде", "насчёт", "насчет", "посреди", "накануне", "с", "со", "со стороны",
];
const PLURAL_WORDS: &[&str] = &[
    "мои", "твои", "свои", "наши", "ваши", "эти", "те", "все", "такие", "какие", "другие", "иные", "многие",
    "некоторые", "чьи", "сами",
];
/// Words in -ой/-ей that are not a feminine adjective before the noun.
const NOT_FEM_ADJ: &[&str] = &[
    "мой", "твой", "свой", "ей", "домой", "порой", "зимой", "весной", "тобой", "собой", "мной", "мною",
    "какой", "такой", "никакой", "другой", "иной", "простой", "постой", "стой", "налей", "пожалей", "сей",
];
/// Words after which the case cannot be told (the network decides).
const UNCLEAR: &[&str] = &[
    "и", "а", "но", "или", "да", "не", "ни", "же", "ли", "бы", "как", "что", "чтобы", "где", "когда", "тоже",
    "также", "уже", "ещё", "еще", "очень", "только", "даже", "вот", "это", "этот", "там", "тут", "здесь",
    "теперь", "потом", "сейчас", "тогда", "всегда", "никогда", "иногда", "снова", "опять", "вдруг", "сразу",
    "вскоре", "утром", "вечером", "днём", "днем", "ночью", "летом", "осенью", "потому", "поэтому", "хотя",
    "если", "пока", "чем", "он", "она", "оно", "они", "мы", "вы", "я", "ты", "мне", "тебе", "ему", "им", "нам",
    "вам", "себе", "кто", "есть", "будто", "словно", "точно", "лишь", "либо", "почти", "совсем", "затем",
    "два", "две", "три", "четыре", "оба", "обе", "полторы", "полтора", "где-то", "куда", "откуда", "зато",
    "сначала", "наконец", "всё", "все-таки", "всё-таки", "так", "там-то", "тут-то", "никак", "уж", "ведь",
    "разве", "неужели", "ещё", "тоже", "просто", "именно", "вообще",
    // prepositions of other cases: "под ноги", "на руки", "за руки", "в воды"
    "в", "во", "на", "под", "подо", "за", "про", "через", "сквозь", "о", "об", "обо", "по", "к", "ко", "над",
    "надо", "перед", "передо", "при", "между", "меж", "сквозь",
];

fn is_word_char(c: char) -> bool { is_cyr_letter(c) || c == '-' }

/// The words right before byte [at] of [text], nearest first, while only spaces stand between
/// (a comma, a dash or a full stop ends them).
fn words_before(text: &str, at: usize, n: usize) -> Vec<String> {
    let mut out = Vec::new();
    let mut s = &text[..at];
    for _ in 0..n {
        let t = s.trim_end_matches(|c: char| c == ' ' || c == '\u{a0}' || c == '+' || c == '\u{301}');
        if t.len() == s.len() && !out.is_empty() { break }
        if t.is_empty() { break }
        let last = t.chars().last().unwrap();
        if !is_cyr_letter(last) { break }
        let start = t.char_indices().rev().take_while(|&(_, c)| is_word_char(c) || c == '+' || c == '\u{301}').last().map(|(i, _)| i).unwrap_or(0);
        out.push(lower(&t[start..]).replace(['+', '\u{301}'], ""));
        s = &t[..start];
    }
    out
}

fn ends_any(w: &str, ends: &[&str]) -> bool { ends.iter().any(|e| w.ends_with(e)) }

/// The variant chosen by the neighbours (or None: the network decides).
pub fn case_rule(text: &str, c: &Candidate, vars: &[String]) -> Option<String> {
    if vars.len() != 2 || !GEN_OR_PLURAL.contains(&c.word_lower.as_str()) { return None }
    let gen = vars.iter().find(|v| v.ends_with("+ы") || v.ends_with("+и"))?.clone();
    let plural = vars.iter().find(|v| **v != gen)?.clone();
    // "…сестры не было дома", "сестры нет" — of the one (missing) sister, whatever stands before
    let after: Vec<String> = text[c.end..].trim_start_matches(|ch: char| ch == ' ' || ch == '\u{a0}')
        .split(|ch: char| !is_word_char(ch) && ch != '+' && ch != '\u{301}').take(2).map(|x| lower(x).replace(['+', '\u{301}'], "")).collect();
    let a0 = after.first().map(String::as_str).unwrap_or("");
    let a1 = after.get(1).map(String::as_str).unwrap_or("");
    if a0 == "нет" || (a0 == "не" && ["было", "будет", "стало", "оказалось"].contains(&a1)) { return Some(gen) }
    let w = words_before(text, c.start, 2);
    // "какие-то волны": the word without -то, -либо, -нибудь
    let bare = |x: &str| x.trim_end_matches("-то").trim_end_matches("-либо").trim_end_matches("-нибудь").to_string();
    let p0 = bare(w.first()?);
    let p = p0.as_str();
    let pp0 = w.get(1).map(|x| bare(x)).unwrap_or_default();
    let pp = pp0.as_str();
    let is = |list: &[&str], x: &str| list.contains(&x);
    if is(GEN_PREPS, p) { return Some(gen) }
    if p == "нет" || (pp == "не" && is(&["было", "будет", "стало", "осталось", "оказалось"], p)) { return Some(gen) }
    if is(&["его", "её", "ее", "их"], p) { return if is(GEN_PREPS, pp) { Some(gen) } else { None } }
    if is(PLURAL_WORDS, p) || p.ends_with("ые") || ends_any(p, &["кие", "гие", "хие", "шие", "жие", "чие", "щие"]) {
        return Some(plural)
    }
    if p.chars().count() >= 3 && (p.ends_with("ой") || p.ends_with("ей")) && !is(NOT_FEM_ADJ, p) { return Some(gen) }
    if is(UNCLEAR, p) || p.chars().count() < 3 { return None }
    // a verb, an adjective of another case or gender: the network
    if ends_any(p, &["ть", "ться", "ти", "чь", "л", "ла", "ло", "ли", "лся", "лась", "лось", "лись", "ет", "ёт",
        "ит", "ют", "ут", "ат", "ят", "ем", "ём", "им", "ешь", "ёшь", "ишь", "ете", "ите", "ся", "сь",
        "ый", "ий", "ая", "яя", "ое", "ее", "ую", "юю", "ом", "ым", "ою", "ею",
        // a gerund ("потерев руки"), an adverb ("эмпатически волны")
        "в", "вши", "вшись", "ски", "цки"]) { return None }
    // a noun before it: "красота сестры", "руки сестры" — of the one sister
    Some(gen)
}
