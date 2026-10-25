package ru.bodro.music.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.bodro.music.R
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.ui.screens.settings.selectedCharacter
import ru.bodro.music.ui.screens.settings.waveCharacterLabel

/** Настройки волны (кнопка слева сверху): характер и настроение — сочетаются с режимом. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WaveSettingsSheet(
    settings: AppSettings,
    onCharacter: (WaveCharacter) -> Unit,
    onMood: (WaveMood) -> Unit,
    onLanguage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.wave_settings), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_wave_character), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WaveCharacter.entries.forEach { character ->
                    FilterChip(
                        selected = selectedCharacter(settings.waveCharacter, settings.waveDiscoveryShare) == character,
                        onClick = { onCharacter(character) },
                        label = { Text(stringResource(waveCharacterLabel(character)), maxLines = 1, softWrap = false) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_wave_mood), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WaveMood.entries.forEach { mood ->
                    FilterChip(selected = settings.waveMood == mood, onClick = {
                        onMood(mood)
                    }, label = { Text(stringResource(moodLabel(mood))) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.wave_language), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "" to R.string.wave_language_any,
                    "ru" to R.string.wave_language_ru,
                    "en" to R.string.wave_language_en,
                    "none" to R.string.wave_language_none,
                )
                    .forEach { (code, label) ->
                        FilterChip(selected = settings.waveLanguage == code, onClick = {
                            onLanguage(code)
                        }, label = { Text(stringResource(label)) })
                    }
            }
            Text(
                stringResource(R.string.wave_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@StringRes
fun moodLabel(mood: WaveMood): Int = when (mood) {
    WaveMood.Any -> R.string.wave_mood_any
    WaveMood.Energetic -> R.string.wave_mood_energetic
    WaveMood.Calm -> R.string.wave_mood_calm
    WaveMood.Happy -> R.string.wave_mood_happy
    WaveMood.Sad -> R.string.wave_mood_sad
}
