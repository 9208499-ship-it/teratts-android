package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.brahmadeo.supertonic.tts.ui.HistoryScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme

class HistoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.brahmadeo.supertonic.tts.utils.NoAutofill.apply(this)
        setContent {
            SupertonicTheme {
                HistoryScreen(
                    onBackClick = { finish() }
                ) { item ->
                    val resultIntent = Intent()
                    resultIntent.putExtra("selected_text", com.brahmadeo.supertonic.tts.utils.TextHandoff.wrap(this, item.text))
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        }
    }
}
