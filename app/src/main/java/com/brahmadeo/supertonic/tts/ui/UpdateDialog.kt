package com.brahmadeo.supertonic.tts.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.utils.Updater
import kotlinx.coroutines.launch
import com.brahmadeo.supertonic.tts.R as AppR

/**
 * "A new version is out": what is new, its size; «Обновить» downloads it (with progress) and opens
 * Android's installer; «Позже» — next start; «Пропустить» — not this version again. Put on any screen:
 * it shows only when an update was found.
 */
@Composable
fun UpdateDialogHost() {
    val r = Updater.found.value ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember(r) { mutableStateOf<Float?>(null) }
    var error by remember(r) { mutableStateOf<String?>(null) }
    var apk by remember(r) { mutableStateOf<java.io.File?>(null) }
    fun install() {
        val f = apk ?: return
        val a = context as? Activity ?: return
        if (!Updater.canInstall(a)) { error = context.getString(AppR.string.update_allow); Updater.askInstallPermission(a); return }
        try { Updater.install(a, f) } catch (e: Exception) { error = e.message }
    }
    AlertDialog(
        onDismissRequest = { if (progress == null) Updater.later(context) },
        title = { Text(stringResource(AppR.string.update_title, r.version) + if (r.beta) " " + stringResource(AppR.string.update_beta_mark) else "") },
        text = {
            Column {
                if (r.notes.isNotEmpty()) {
                    Text(r.notes, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                }
                if (r.size > 0) Text(stringResource(AppR.string.update_size, (r.size / 1_048_576.0).toFloat()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        // all three buttons one under another, at the right (with a large font the two slots of the
        // dialog laid them out apart, the last one cut off below the screen)
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (apk != null) TextButton(onClick = { install() }) { Text(stringResource(AppR.string.update_install)) }
                else TextButton(enabled = progress == null, onClick = {
                    error = null; progress = 0f
                    scope.launch {
                        try {
                            apk = Updater.download(context, r) { p -> progress = p }
                            progress = null
                            install()
                        } catch (e: Exception) {
                            progress = null
                            error = context.getString(AppR.string.update_failed, e.message ?: "?")
                        }
                    }
                }) { Text(stringResource(AppR.string.update_now)) }
                if (progress == null) {
                    TextButton(onClick = { Updater.later(context) }) { Text(stringResource(AppR.string.update_later)) }
                    TextButton(onClick = { Updater.skip(context, r.version); Updater.found.value = null }) {
                        Text(stringResource(AppR.string.update_skip))
                    }
                }
            }
        }
    )
}
