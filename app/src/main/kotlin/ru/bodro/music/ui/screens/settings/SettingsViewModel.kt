package ru.bodro.music.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.Clock
import ru.bodro.music.playback.PlayerCache

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val cache: PlayerCache,
    private val clock: Clock,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings

    private val _cacheUsed = MutableStateFlow<Long?>(null)
    val cacheUsed: StateFlow<Long?> = _cacheUsed.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { _cacheUsed.value = runCatching { cache.usedBytes() }.getOrNull() }
    }

    fun clearCache() {
        viewModelScope.launch {
            runCatching { cache.clear() }
            refreshCacheSize()
        }
    }

    fun resetSkipStats() = update { it.copy(skipStatsResetAt = clock.now()) }
}
