package ru.bodro.music.ui.screens.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bodro.music.R
import ru.bodro.music.data.reco.Section
import ru.bodro.music.ui.app.LocalAppActions

/**
 * Вкладка «Главная» — только «Моя волна» на весь экран. Чарты, избранное, недавнее, исполнители и плейлисты —
 * на вкладке «Чарты» ([ru.bodro.music.ui.screens.charts.ChartsTabScreen]).
 */
@Composable
fun HomeScreen(onOpenSearch: () -> Unit = {}, viewModel: HomeViewModel = hiltViewModel()) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trackInfo by viewModel.trackInfo.collectAsStateWithLifecycle()
    val serverModes by viewModel.modes.collectAsStateWithLifecycle()
    val artistCovers by viewModel.artistCovers.collectAsStateWithLifecycle()
    val coverage by viewModel.coverage.collectAsStateWithLifecycle()
    val modeCards = rememberModeCards(serverModes, artistCovers)
    val findMoreStarted = stringResource(R.string.wave_find_more_started)
    val actions = LocalAppActions.current
    var waveSettings by rememberSaveable { mutableStateOf(false) }

    WaveHome(
        player = player,
        settings = settings,
        // Текст — о треке, который сейчас на экране (из волны или нет).
        blurbs = trackInfo.blurbs.takeIf { trackInfo.songId == player.currentSong?.id }
            ?: Section.Empty,
        modes = modeCards,
        onTogglePlay = viewModel::togglePlay,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        onSelectMode = viewModel::setMode,
        onOpenSettings = { waveSettings = true },
        onOpenSearch = onOpenSearch,
        modifier = Modifier.fillMaxSize().testTag("home-screen"),
        lowCoverage = coverage?.let { it.lowCoverage && it.mode == settings.waveModeId } == true,
        onFindMore = { viewModel.findMore { ok -> if (ok) actions.showMessage(findMoreStarted) } },
        drawBackground = false,
    )
    if (waveSettings) {
        WaveSettingsSheet(
            settings,
            onCharacter = viewModel::setCharacter,
            onMood = viewModel::setMood,
            onLanguage = viewModel::setLanguage,
            onDismiss = { waveSettings = false },
        )
    }
}
