package com.brahmadeo.supertonic.tts.service;

interface IPlaybackListener {
    oneway void onStateChanged(boolean isPlaying, boolean hasContent, boolean isSynthesizing);
    oneway void onProgress(int current, int total);
    oneway void onPlaybackStopped();
    oneway void onExportComplete(boolean success, String path);
    /** The text changed by itself (the next chapter started); the new text is in prefs "last_text". */
    oneway void onTextChanged();
    /** What is being HEARD: phrase index and the share of it already played (0..1), ~8 times a second. */
    oneway void onSpokenPosition(int index, float fraction);
}