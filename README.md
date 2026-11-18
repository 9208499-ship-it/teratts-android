# Supertonic 3 Android (fork)

Fork of [DevGitPit/supertonic-android](https://github.com/DevGitPit/supertonic-android) upgraded from Supertonic 2 (5 languages) to **Supertonic 3 (31 languages + `na` fallback)** using the [supertonic-3 ONNX weights from Hugging Face](https://huggingface.co/Supertone/supertonic-3).

The on-device inference pipeline (Rust + ONNX Runtime + XNNPACK) is unchanged — Supertonic 3 reuses the same four-stage graph (`text_encoder` → `duration_predictor` → `vector_estimator` → `vocoder`) and the same tensor shapes as v2. The fork therefore replaces only the asset bundle, the language tagging, and the surrounding UI; all inference code paths are identical.

## What changed vs. the upstream fork

| Area | Upstream (v1 + v2) | This fork (v3) |
|---|---|---|
| Languages | 5 (en, ko, es, pt, fr) | 31 + `na` fallback |
| Models | Two parallel dirs (`v1`, `v2`), bundled English + downloadable multilingual | Single Supertonic 3 download (~400 MB), one-time |
| Language tagging | `<lang>` only for non-English | Always wrapped (the model has no separate lang embedding) |
| Dash handling | Em-dash → comma only in English path | Em-dash → comma in every language (fixes Russian "Москва — столица" mis-reading) |
| System TTS settings | Reachable only via Android Settings | Direct shortcut from the in-app menu |
| Lexicon | Hand-edited rules (regex / whole word) | Same, plus **bulk accent dictionary import** for tens of thousands of stress-marked words |

## Install

Grab the signed APK from the **[Releases](https://github.com/9208499-ship-it/teratts-android/releases)** page.

1. On the phone: Settings → Apps → Special access → Install unknown apps → allow your file manager / browser.
2. Tap the APK to install. If `adb install` returns `INSTALL_FAILED_VERIFICATION_FAILURE`, disable **Verify apps over USB** in Developer Options.
3. First launch downloads ~400 MB of ONNX models from Hugging Face — needs Wi-Fi once.

Public TeraTTS releases use the same signing key. GitHub Actions produces an unsigned release artifact for inspection. The maintainer signs the arm64 APK on the Mac before publishing it. Beta builds are GitHub pre-releases and are installed manually.

## Use as the system TTS engine

After installation:

1. Open Supertonic TTS → menu → **System TTS Settings** (or Android Settings → Accessibility → Text-to-speech output).
2. Set **Preferred engine** to *Supertonic TTS*.
3. Pick any of the 31 languages and test with *Listen to an example*.

Every app that uses Android's TTS API (Voice Aloud, TalkBack, reader apps, navigation, Tasker etc.) will now use Supertonic.

## Pronunciation control

Two layers of user rules, both applied before the text reaches the model:

1. **Lexicon** (menu → Lexicon) — small set of hand-edited rules with regex or whole-word matching. Highest priority.
2. **Accent dictionary** (menu → Lexicon → Import accent dictionary…) — bulk JSON map for stress / pronunciation, e.g. open-source Russian stress dictionaries. Indexed by word, so a 50 000-entry dictionary still runs in milliseconds.

Expected accent dictionary shape:

```json
{
  "замок": "замо́к",
  "Москва": "Москва́",
  "молоко": "молоко́"
}
```

Stress is marked with the combining acute accent **U+0301** placed *after* the stressed vowel. Whether the model actually pronounces the marked syllable as stressed depends on its training data — try a short test through Lexicon first before importing a large file.

Ready-to-import Russian dictionaries (962 K and 615 K entries) live under [`dictionaries/`](dictionaries/) — download the JSON from the release assets, import via the menu.

## Build

CI builds (`.github/workflows/ci.yml`) reproduce locally:

```bash
# Requirements: Android SDK + NDK r29, JDK 17, Rust stable with Android targets
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # unsigned release APK; sign separately with apksigner
```

The Rust crate under `rust/` produces `libsupertonic_tts.so` for `aarch64`, `armv7`, `i686`, `x86_64`. ONNX Runtime is linked dynamically via `onnxruntime-android` from Maven.

## Credits

- [Supertone](https://github.com/supertone-inc/supertonic) — Supertonic 3 model weights, training, and the reference Python pipeline.
- [DevGitPit/supertonic-android](https://github.com/DevGitPit/supertonic-android) — upstream Android app with Compose UI, Rust JNI bridge, F-Droid metadata, and thermal management. This fork is a thin layer on top.

## License

Same as upstream. The Supertonic model weights are released under [OpenRAIL-M](https://huggingface.co/Supertone/supertonic-3) and are downloaded at runtime; they are not bundled in the APK.
