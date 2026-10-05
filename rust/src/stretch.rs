// ============================================================================
// Time stretching without pitch change — WSOLA (waveform-similarity overlap-add).
//
// TeraTTS speaks cleanly up to ~1.5× / down to ~0.75× via its duration scale;
// pushed further it starts swallowing syllables. Beyond that range the model
// speaks at its limit and the audio is stretched here, the way audiobook
// players speed up recordings: every sound is kept, only time is compressed.
// ============================================================================

/// The model's own duration scale stays inside this speed range.
pub const MODEL_MIN_SPEED: f32 = 0.75;
pub const MODEL_MAX_SPEED: f32 = 1.5;

/// Split a requested speed into (speed for the model, extra stretch rate).
pub fn split_speed(speed: f32) -> (f32, f32) {
    let speed = if speed.is_finite() && speed > 0.0 { speed } else { 1.0 };
    let model = speed.clamp(MODEL_MIN_SPEED, MODEL_MAX_SPEED);
    (model, speed / model)
}

/// Stretch `x` so it plays `rate` times faster (rate > 1 = shorter), pitch kept.
pub fn time_stretch(x: &[f32], rate: f32, sample_rate: u32) -> Vec<f32> {
    if !(rate.is_finite()) || (rate - 1.0).abs() < 0.01 || x.len() < 4096 {
        return if (rate - 1.0).abs() < 0.01 || !rate.is_finite() {
            x.to_vec()
        } else {
            naive_resample_time(x, rate) // too short for WSOLA; tiny clips only
        };
    }
    let sr = sample_rate as f32;
    let n = ((0.030 * sr) as usize) & !1; // 30 ms frame
    let hop_out = n / 2; // 50 % overlap
    let hop_in = hop_out as f32 * rate;
    let tol = (0.010 * sr) as usize; // ±10 ms search
    let overlap = n - hop_out;

    // Hann window: with 50 % overlap the shifted copies sum to 1.
    let win: Vec<f32> = (0..n)
        .map(|i| 0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / n as f32).cos())
        .collect();

    let out_len = (x.len() as f32 / rate) as usize;
    let mut out = vec![0.0f32; out_len + n];
    let mut norm = vec![0.0f32; out_len + n];

    let get = |i: isize| -> f32 { if i >= 0 && (i as usize) < x.len() { x[i as usize] } else { 0.0 } };

    let mut prev_pos: isize = 0; // input position of the previous chosen frame
    let mut k = 0usize;
    loop {
        let out_pos = k * hop_out;
        if out_pos >= out_len {
            break;
        }
        let nominal = (k as f32 * hop_in) as isize;
        let pos = if k == 0 {
            0
        } else {
            // The natural continuation of the previous frame starts at prev_pos + hop_out;
            // pick the offset around `nominal` whose waveform matches it best.
            let target = prev_pos + hop_out as isize;
            let mut best = nominal;
            let mut best_score = f32::MIN;
            let mut d = -(tol as isize);
            while d <= tol as isize {
                let cand = nominal + d;
                let mut score = 0.0f32;
                let mut j = 0;
                while j < overlap {
                    score += get(cand + j as isize) * get(target + j as isize);
                    j += 2; // decimated correlation: 2× cheaper, same choice in practice
                }
                if score > best_score {
                    best_score = score;
                    best = cand;
                }
                d += 2;
            }
            best.max(0)
        };
        for i in 0..n {
            let w = win[i];
            out[out_pos + i] += get(pos + i as isize) * w;
            norm[out_pos + i] += w;
        }
        prev_pos = pos;
        k += 1;
    }
    for i in 0..out.len() {
        if norm[i] > 1e-3 {
            out[i] /= norm[i];
        }
    }
    out.truncate(out_len);
    out
}

/// Fallback for very short clips: linear interpolation in time (changes pitch
/// slightly, inaudible on a few tens of milliseconds).
fn naive_resample_time(x: &[f32], rate: f32) -> Vec<f32> {
    let out_len = ((x.len() as f32) / rate) as usize;
    (0..out_len)
        .map(|i| {
            let p = i as f32 * rate;
            let a = p.floor() as usize;
            let f = p - a as f32;
            let s0 = x.get(a).copied().unwrap_or(0.0);
            let s1 = x.get(a + 1).copied().unwrap_or(s0);
            s0 + (s1 - s0) * f
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(f0: f32, secs: f32, sr: u32) -> Vec<f32> {
        // harmonic "voice": f0 plus overtones, slow amplitude envelope
        let n = (secs * sr as f32) as usize;
        (0..n)
            .map(|i| {
                let t = i as f32 / sr as f32;
                let env = 0.6 + 0.4 * (2.0 * std::f32::consts::PI * 3.0 * t).sin();
                env * (0..5).map(|h| (2.0 * std::f32::consts::PI * f0 * (h + 1) as f32 * t).sin() / (h + 1) as f32).sum::<f32>() * 0.3
            })
            .collect()
    }

    fn crossings_per_sec(x: &[f32], sr: u32) -> f32 {
        let c = x.windows(2).filter(|w| (w[0] < 0.0) != (w[1] < 0.0)).count();
        c as f32 / (x.len() as f32 / sr as f32)
    }

    #[test]
    fn split() {
        assert_eq!(split_speed(1.0), (1.0, 1.0));
        let (m, r) = split_speed(2.0);
        assert_eq!(m, MODEL_MAX_SPEED);
        assert!((m * r - 2.0).abs() < 1e-5);
        let (m, r) = split_speed(0.5);
        assert_eq!(m, MODEL_MIN_SPEED);
        assert!((m * r - 0.5).abs() < 1e-5);
    }

    #[test]
    fn keeps_pitch_changes_length() {
        let sr = 44_100;
        let x = tone(140.0, 3.0, sr);
        for rate in [1.5f32, 2.0, 0.7] {
            let y = time_stretch(&x, rate, sr);
            let want = x.len() as f32 / rate;
            assert!((y.len() as f32 - want).abs() < 0.01 * want, "length at {rate}");
            let (cx, cy) = (crossings_per_sec(&x, sr), crossings_per_sec(&y, sr));
            assert!((cy / cx - 1.0).abs() < 0.08, "pitch drift at {rate}: {cx} vs {cy}");
            assert!(y.iter().all(|v| v.is_finite() && v.abs() < 1.5));
        }
    }
}
