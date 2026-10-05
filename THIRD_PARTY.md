# TeraTTS for Android — компоненты и лицензии

- Приложение: форк Supertonic TTS (DevGitPit → davnozdu), GPL-3.0. Изменения TeraTTS — тоже GPL-3.0.
- Голосовая модель TeraTTSv2 (TeraSpace) — не входит в APK, скачивается при первом запуске
  с Hugging Face (TeraSpace/TeraTTSv2, зафиксированная версия). Права — у авторов модели.
- Голоса Supertonic 3 (Supertone, OpenRAIL-M) — скачиваются с Hugging Face при первом запуске.
- Разрешатель омографов silero-stress v1.5 (Silero Team, MIT) — app/src/main/assets/homosolver,
  сконвертирован в ONNX.
- Словари ударений автора форка — на основе RUAccent (MIT) / словаря Зализняка.
