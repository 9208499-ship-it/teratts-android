package com.brahmadeo.supertonic.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.brahmadeo.supertonic.tts.utils.AssetManager
import java.util.ArrayList

/**
 * Activity that handles the CHECK_TTS_DATA intent.
 * This is required by some apps (like Tasker) to verify that the TTS engine is functional
 * and to discover which languages are supported.
 */
class CheckDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ready = AssetManager.isReady(this)
        val availableVoices = ArrayList<String>()
        val unavailableVoices = ArrayList<String>()

        if (ready) {
            SUPPORTED_TTS_LOCALES.forEach { availableVoices.add(it) }
        } else {
            SUPPORTED_TTS_LOCALES.forEach { unavailableVoices.add(it) }
        }

        val result = if (availableVoices.isNotEmpty()) {
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
        } else {
            TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
        }

        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices)

        setResult(result, returnIntent)
        finish()
    }

    companion object {
        // BCP-47 / ISO-639 codes for all languages supported by Supertonic 3.
        // The country part is intentionally generic; finer locales are advertised by SupertonicTextToSpeechService.
        private val SUPPORTED_TTS_LOCALES = listOf("rus-RUS", "eng-USA")
    }
}
