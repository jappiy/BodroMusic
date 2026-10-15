package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.Account
import ru.bodro.music.core.model.ServerKind
import ru.bodro.music.core.subsonic.ConnectionConfig
import ru.bodro.music.core.subsonic.ServerSelection
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.AccountRepository
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.network.CleartextPolicy
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

/** Результат «Проверить подключение» для одного адреса. */
data class CheckResult(val kind: ServerKind, val ok: Boolean, val message: String)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val connection: ConnectionManager,
    private val client: SubsonicClient,
    settings: SettingsRepository,
) : ViewModel() {
    val list: StateFlow<List<Account>> = accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeId: StateFlow<Long?> = settings.settings.map { it.activeAccountId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.activeAccountId)
    val selection: StateFlow<ServerSelection?> = connection.selection

    private val _checks = MutableStateFlow<Map<Long, List<CheckResult>>>(emptyMap())
    val checks: StateFlow<Map<Long, List<CheckResult>>> = _checks.asStateFlow()

    private val _checking = MutableStateFlow<Long?>(null)
    val checking: StateFlow<Long?> = _checking.asStateFlow()

    fun switchTo(id: Long) {
        viewModelScope.launch { accounts.setActive(id) }
    }

    fun logout(id: Long) {
        viewModelScope.launch { accounts.remove(id) }
    }

    fun updateAddresses(id: Long, local: String, external: String) {
        viewModelScope.launch {
            accounts.updateAddresses(
                id,
                local.trim().takeIf { it.isNotEmpty() }?.let(ConnectionConfig::normalizeBaseUrl)?.toString(),
                external.trim().takeIf { it.isNotEmpty() }?.let(ConnectionConfig::normalizeBaseUrl)?.toString(),
            )
            if (id == connection.activeAccountId) connection.refreshAccount()
        }
    }

    /** Проверка каждого адреса учётки: ping + расширения. */
    fun check(account: Account, context: android.content.Context) {
        viewModelScope.launch {
            _checking.value = account.id
            val password = accounts.password(account.id)
            val results = listOf(
                ServerKind.Local to account.localUrl,
                ServerKind.External to account.externalUrl,
            ).mapNotNull { (kind, url) ->
                val config = url?.let { ConnectionConfig.of(it, kind, account.username, password.orEmpty()) } ?: return@mapNotNull null
                if (!CleartextPolicy.isAllowed(config.baseUrl)) {
                    return@mapNotNull CheckResult(kind, false, context.getString(R.string.error_cleartext))
                }
                runCatching { client.handshake(config) }.fold(
                    { info ->
                        accounts.updateServerInfo(account.id, info)
                        CheckResult(kind, true, context.getString(R.string.check_ok, info.serverVersion ?: info.apiVersion))
                    },
                    { CheckResult(kind, false, it.toUiError().text(context)) },
                )
            }
            _checks.update { it + (account.id to results) }
            _checking.value = null
            if (account.id == connection.activeAccountId) connection.reselect()
        }
    }
}

@Composable
fun AccountsScreen(onBack: () -> Unit, onAddAccount: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val accounts by viewModel.list.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var loggingOut by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = { BodroTopBar(stringResource(R.string.settings_accounts), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAccount,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.accounts_add)) },
                modifier = Modifier.testTag("add-account"),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(accounts, key = { it.id }) { account ->
                val active = account.id == activeId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("account-${account.username}"),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(account.username, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            if (active) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.accounts_active),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        account.localUrl?.let {
                            Text(stringResource(R.string.accounts_local, it), style = MaterialTheme.typography.bodyMedium)
                        }
                        account.externalUrl?.let {
                            Text(stringResource(R.string.accounts_external, it), style = MaterialTheme.typography.bodyMedium)
                        }
                        account.serverVersion?.let {
                            Text(stringResource(R.string.about_navidrome_version, it), style = MaterialTheme.typography.bodySmall)
                        }
                        if (active) {
                            selection?.let { s ->
                                Text(
                                    stringResource(
                                        R.string.accounts_current_address,
                                        stringResource(if (s.kind == ServerKind.Local) R.string.server_lan else R.string.server_wan),
                                        s.baseUrl.toString(),
                                    ) + if (s.reachable) "" else " — " + stringResource(R.string.server_unreachable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        checks[account.id]?.forEach { result ->
                            Text(
                                (if (result.kind == ServerKind.Local) "LAN" else "WAN") + ": " + result.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            if (!active) {
                                OutlinedButton(onClick = {
                                    viewModel.switchTo(account.id)
                                }) { Text(stringResource(R.string.accounts_switch)) }
                            }
                            OutlinedButton(onClick = { viewModel.check(account, context) }, enabled = checking != account.id) {
                                Text(stringResource(if (checking == account.id) R.string.accounts_checking else R.string.accounts_check))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = account.id }) { Text(stringResource(R.string.accounts_edit_addresses)) }
                            TextButton(onClick = {
                                loggingOut = account.id
                            }) { Text(stringResource(R.string.accounts_logout), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    editing?.let { id ->
        val account = accounts.firstOrNull { it.id == id } ?: return@let
        var local by rememberSaveable(id) { mutableStateOf(account.localUrl.orEmpty()) }
        var external by rememberSaveable(id) { mutableStateOf(account.externalUrl.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.accounts_edit_addresses)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(local, { local = it }, label = { Text(stringResource(R.string.login_local_url)) }, singleLine = true)
                    OutlinedTextField(external, {
                        external = it
                    }, label = { Text(stringResource(R.string.login_external_url)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateAddresses(id, local, external)
                    editing = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    loggingOut?.let { id ->
        AlertDialog(
            onDismissRequest = { loggingOut = null },
            title = { Text(stringResource(R.string.accounts_logout_confirm)) },
            text = { Text(stringResource(R.string.accounts_logout_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout(id)
                    loggingOut = null
                }) { Text(stringResource(R.string.accounts_logout)) }
            },
            dismissButton = { TextButton(onClick = { loggingOut = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
