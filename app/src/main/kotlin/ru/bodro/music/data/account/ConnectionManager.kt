package ru.bodro.music.data.account

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import ru.bodro.music.core.model.Account
import ru.bodro.music.core.model.ServerKind
import ru.bodro.music.core.subsonic.ConnectionConfig
import ru.bodro.music.core.subsonic.ConnectionProvider
import ru.bodro.music.core.subsonic.ServerSelection
import ru.bodro.music.core.subsonic.ServerSelector
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.AppScope

sealed interface SessionState {
    data object Loading : SessionState

    data object NoAccount : SessionState

    /** Пароль не расшифровался (ключ потерян) — нужен повторный вход. */
    data class NeedsLogin(val account: Account) : SessionState

    data class Active(val account: Account) : SessionState
}

/**
 * Активная учётка + выбранный адрес сервера (LAN/WAN). Переопределяет адрес при старте и при смене сети.
 * Отдаёт [ConnectionConfig] для [ru.bodro.music.core.subsonic.SubsonicClient].
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val selector: Lazy<ServerSelector>,
    private val log: Lazy<LogRepository>,
    @AppScope private val scope: CoroutineScope,
) : ConnectionProvider {
    private data class Active(val account: Account, val password: String)

    @Volatile
    private var active: Active? = null

    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _selection = MutableStateFlow<ServerSelection?>(null)
    val selection: StateFlow<ServerSelection?> = _selection.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val selectMutex = Mutex()
    private var reselectJob: Job? = null
    private var started = false

    val activeAccountId: Long? get() = active?.account?.id

    val activeAccount: Account? get() = active?.account

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.settings.map { it.activeAccountId }.distinctUntilChanged().collect { id -> activate(id) }
        }
        registerNetworkCallback()
    }

    private suspend fun activate(accountId: Long?) {
        val account = accountId?.let { accounts.get(it) }
        if (account == null) {
            active = null
            _selection.value = null
            _session.value = SessionState.NoAccount
            return
        }
        val password = accounts.password(account.id)
        if (password == null) {
            active = null
            _session.value = SessionState.NeedsLogin(account)
            return
        }
        active = Active(account, password)
        // Пока проба не завершилась — используем внешний (или локальный) адрес, чтобы не блокировать UI.
        _selection.value = fallbackSelection(account)
        _session.value = SessionState.Active(account)
        reselect()
    }

    /** Обновить данные учётки (адреса изменились в настройках). */
    suspend fun refreshAccount() = activate(settings.current.activeAccountId)

    /** Перепроверить адреса: локальный с коротким таймаутом, иначе внешний. */
    suspend fun reselect(): ServerSelection? = selectMutex.withLock {
        val account = active?.account ?: return null
        _checking.value = true
        try {
            val previous = _selection.value
            val selected = selector.get().select(account.localUrl, account.externalUrl) ?: return null
            if (active?.account?.id != account.id) return null
            _selection.value = selected
            if (previous?.kind != selected.kind || previous.reachable != selected.reachable) {
                val where = if (selected.kind == ServerKind.Local) "LAN" else "WAN"
                log.get().event(
                    PlayerEvent.ServerSwitched,
                    "Адрес сервера: $where ${selected.baseUrl}" + if (selected.reachable) "" else " (не отвечает)",
                    isError = !selected.reachable,
                )
            }
            selected
        } finally {
            _checking.value = false
        }
    }

    private fun scheduleReselect() {
        reselectJob?.cancel()
        reselectJob = scope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            reselect()
        }
    }

    override fun current(): ConnectionConfig? {
        val current = active ?: return null
        val selected = _selection.value ?: fallbackSelection(current.account) ?: return null
        return ConnectionConfig(selected.baseUrl, selected.kind, current.account.username, current.password)
    }

    /** Для логов: к какому адресу учётки относится URL. */
    fun serverKindOf(url: HttpUrl): ServerKind? {
        val account = active?.account ?: return null
        fun matches(raw: String?) = raw?.let(ConnectionConfig::normalizeBaseUrl)?.let { it.host == url.host && it.port == url.port } == true
        return when {
            matches(account.localUrl) -> ServerKind.Local
            matches(account.externalUrl) -> ServerKind.External
            else -> null
        }
    }

    val isOnUnmeteredNetwork: Boolean
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

    private fun fallbackSelection(account: Account): ServerSelection? {
        val external = account.externalUrl?.let(ConnectionConfig::normalizeBaseUrl)
        val local = account.localUrl?.let(ConnectionConfig::normalizeBaseUrl)
        return when {
            external != null -> ServerSelection(external, ServerKind.External, reachable = false)
            local != null -> ServerSelection(local, ServerKind.Local, reachable = false)
            else -> null
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleReselect()

                override fun onLost(network: Network) = scheduleReselect()

                private var lastTransport: String? = null

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // Смена Wi-Fi ↔ мобильная приходит как изменение возможностей — реагируем только на смену транспорта.
                    val transport = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        else -> "other"
                    }
                    if (transport != lastTransport) {
                        lastTransport = transport
                        scheduleReselect()
                    }
                }
            },
        )
    }

    private companion object {
        const val NETWORK_DEBOUNCE_MS = 1_500L
    }
}
