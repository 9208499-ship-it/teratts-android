use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject, JValue};
use jni::sys::{jlong, jint, jfloat, jbyteArray};

/// Multiplier for the pauses the engine adds at punctuation (settings slider).
#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_setPauseScale(
    _env: JNIEnv,
    _class: JClass,
    scale: jfloat,
) {
    crate::tera::set_pause_scale(scale);
}

/// Pause after a paragraph, seconds (settings slider).
#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_setParagraphPause(
    _env: JNIEnv,
    _class: JClass,
    seconds: jfloat,
) {
    crate::tera::set_paragraph_pause(seconds);
}
use android_logger::Config;
use log::LevelFilter;
use std::time::Instant;

mod helper;
mod tera;
mod homo;
mod stretch;
mod thermal;

use helper::{load_text_to_speech, load_voice_style, load_and_mix_voice_styles, TextToSpeech};
use thermal::{UnifiedThermalManager, SocClass};

use std::panic;

/// Transparent below 0.9, then a smooth knee instead of hard clipping
/// (hard clipping is what makes speech sound metallic and buzzy).
#[inline]
fn soft_limit(x: f32) -> f32 {
    const T: f32 = 0.9;
    let a = x.abs();
    if a <= T {
        x
    } else {
        x.signum() * (T + (1.0 - T) * ((a - T) / (1.0 - T)).tanh())
    }
}

struct SupertonicEngine {
    tts: TextToSpeech,
    thermal: UnifiedThermalManager,
    last_rtf: f32,
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_init(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    _lib_path: JString, // Kept for signature compatibility
    ort_threads: jint,
    xnn_threads: jint,
) -> jlong {
    android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info),
    );

    // VULN-002 fix: don't log panic payload (may contain user text/PII)
    panic::set_hook(Box::new(|panic_info| {
        let location = panic_info.location()
            .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
            .unwrap_or_else(|| "unknown location".to_string());
        log::error!("RUST PANIC at {}", location);
    }));

    let model_path: String = env.get_string(&model_path).expect("Couldn't get java string!").into();
    
    log::info!("Initializing Supertonic Engine (ORT: {}, XNN: {}) with model path: {}", ort_threads, xnn_threads, model_path);

    if !ort::init().with_name("supertonic-tts").commit() {
        log::warn!("ORT environment already initialized");
    }

    // XNNPACK only when compiled in (cargo feature) and requested (xnn_threads > 0)
    let use_xnnpack = cfg!(feature = "xnnpack") && xnn_threads > 0;
    log::info!("XNNPACK: {}", if use_xnnpack { "on" } else { "off" });
    let tts = match load_text_to_speech(&model_path, false, use_xnnpack, ort_threads as usize, xnn_threads as usize) {
        Ok(t) => t,
        Err(e) => {
            log::error!("Failed to load TTS: {:?}", e);
            return 0;
        }
    };

    let thermal = UnifiedThermalManager::new();

    let engine = SupertonicEngine {
        tts,
        thermal,
        last_rtf: 1.0,
    };

    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_synthesize(
    mut env: JNIEnv,
    instance: JObject,
    ptr: jlong,
    text: JString,
    lang: JString,
    style_path: JString,
    speed: jfloat,
    buffer_seconds: jfloat,
    steps: jint,
    gain: jfloat,
) -> jbyteArray {
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    
    let text: String = env.get_string(&text).expect("Couldn't get java string!").into();
    let lang: String = env.get_string(&lang).expect("Couldn't get java string!").into();
    let style_path: String = env.get_string(&style_path).expect("Couldn't get java string!").into();

    engine.thermal.update(buffer_seconds, engine.last_rtf);

    let style = if style_path.contains(';') {
        let parts: Vec<&str> = style_path.split(';').collect();
        if parts.len() == 3 {
            let p1 = parts[0];
            let p2 = parts[1];
            let alpha = parts[2].parse::<f32>().unwrap_or(0.5);
            match load_and_mix_voice_styles(p1, p2, alpha) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("Failed to mix voice styles: {:?}", e);
                    return env.new_byte_array(0).unwrap().into_raw();
                }
            }
        } else {
            log::error!("Invalid mix format. Expected: path1;path2;alpha");
            return env.new_byte_array(0).unwrap().into_raw();
        }
    } else {
        match load_voice_style(&[style_path], false) {
            Ok(s) => s,
            Err(e) => {
                log::error!("Failed to load voice style: {:?}", e);
                return env.new_byte_array(0).unwrap().into_raw();
            }
        }
    };

    let start = Instant::now();
    
    // Create a progress callback
    let mut last_progress_call = Instant::now();
    // 0.02 s silence between within-sentence chunks: just enough to mask the
    // vocoder boundary, but inaudible as a "pause" to a listener. The old
    // 0.1 s value was audible as choppy delivery on long Russian paragraphs.
    let result = engine.tts.call(&text, &lang, &style, steps as usize, speed, 0.02, |curr, total, audio_chunk| {
        // Check for cancellation
        let is_cancelled = env.call_method(&instance, "isCancelled", "()Z", &[]).unwrap().z().unwrap();
        if is_cancelled {
            return false;
        }

        // Send audio chunk if available
        if let Some(audio) = audio_chunk {
            let mut pcm_data = Vec::with_capacity(audio.len() * 2);
            for &sample in audio {
                let val = (soft_limit(sample * gain) * 32767.0) as i16;
                pcm_data.extend_from_slice(&val.to_le_bytes());
            }
            
            let output = env.new_byte_array(pcm_data.len() as i32).unwrap();
            env.set_byte_array_region(&output, 0, pcm_data.iter().map(|&b| b as i8).collect::<Vec<_>>().as_slice()).unwrap();
            
            let _ = env.call_method(
                &instance,
                "notifyAudioChunk",
                "([B)V",
                &[JValue::Object(&output)],
            );
        }

        // Only call Progress JNI every 100ms or at start/end/chunk
        if curr == 0 || curr == total || audio_chunk.is_some() || last_progress_call.elapsed().as_millis() > 100 {
            let _ = env.call_method(
                &instance,
                "notifyProgress",
                "(II)V",
                &[JValue::Int(curr as i32), JValue::Int(total as i32)],
            );
            last_progress_call = Instant::now();
        }
        true
    });

    match result {
        Ok((wav_data, duration)) => {
            let elapsed = start.elapsed().as_secs_f32();
            if duration > 0.0 {
                engine.last_rtf = duration / elapsed;
                log::info!("Inference RTF: {:.2}x ({}s audio in {}s)", engine.last_rtf, duration, elapsed);
            }

            let mut pcm_data = Vec::with_capacity(wav_data.len() * 2);
            for &sample in &wav_data {
                let val = (soft_limit(sample * gain) * 32767.0) as i16;
                pcm_data.extend_from_slice(&val.to_le_bytes());
            }

            let output = env.new_byte_array(pcm_data.len() as i32).unwrap();
            env.set_byte_array_region(&output, 0, pcm_data.iter().map(|&b| b as i8).collect::<Vec<_>>().as_slice()).unwrap();
            output.into_raw()
        }
        Err(e) => {
            log::error!("Synthesis failed: {:?}", e);
            env.new_byte_array(0).unwrap().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_getSocClass(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr == 0 { return -1; }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    match engine.thermal.get_soc_class() {
        SocClass::Flagship => 3,
        SocClass::HighEnd => 2,
        SocClass::MidRange => 1,
        SocClass::LowEnd => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_getSampleRate(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr == 0 { return 24000; }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    engine.tts.sample_rate as jint
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_reset(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
        // Reset thermal state or other buffers if needed
        engine.last_rtf = 1.0;
        log::info!("Engine state reset (JNI Handshake)");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_isXnnpackEnabled(
    _env: JNIEnv,
    _class: JClass,
) -> bool {
    #[cfg(feature = "xnnpack")]
    {
        true
    }
    #[cfg(not(feature = "xnnpack"))]
    {
        false
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_close(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            let _ = Box::from_raw(ptr as *mut SupertonicEngine);
        }
    }
}
