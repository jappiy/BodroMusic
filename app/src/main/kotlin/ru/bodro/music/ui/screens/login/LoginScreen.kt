package ru.bodro.music.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import ru.bodro.music.R
import ru.bodro.music.core.model.Account
import ru.bodro.music.core.model.ServerInfo
import ru.bodro.music.core.model.ServerKind
import ru.bodro.music.core.subsonic.ConnectionConfig
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.core.subsonic.SubsonicError
import ru.bodro.music.core.subsonic.SubsonicException
import ru.bodro.music.data.account.AccountRepository
import ru.bodro.music.data.network.CleartextPolicy
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.toUiError

data class LoginUi(
    val loading: Boolean = false,
    val error: UiError? = null,
    val done: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val client: SubsonicClient,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUi())
    val state: StateFlow<LoginUi> = _state.asStateFlow()

    fun login(localUrl: String, externalUrl: String, username: String, password: String) {
        val local = localUrl.trim().takeIf { it.isNotEmpty() }
        val external = externalUrl.trim().takeIf { it.isNotEmpty() }
        val localParsed = local?.let(ConnectionConfig::normalizeBaseUrl)
        val externalParsed = external?.let(ConnectionConfig::normalizeBaseUrl)
        val error = validate(local, localParsed, external, externalParsed, username, password)
        if (error != null) {
            _state.value = LoginUi(error = error)
            return
        }
        _state.value = LoginUi(loading = true)
        viewModelScope.launch {
            val candidates = listOfNotNull(
                localParsed?.let { ConnectionConfig(it, ServerKind.Local, username.trim(), password) },
                externalParsed?.let { ConnectionConfig(it, ServerKind.External, username.trim(), password) },
            )
            var lastError: Throwable = IllegalStateException("Нет адресов для проверки")
            var info: ServerInfo? = null
            val remaining = ArrayDeque(candidates)
            while (info == null && remaining.isNotEmpty()) {
                val result = runCatching { client.handshake(remaining.removeFirst()) }
                info = result.getOrNull()
                result.exceptionOrNull()?.let { error ->
                    lastError = error
                    // Неверный логин/пароль — пробовать другой адрес бессмысленно.
                    if ((error as? SubsonicException)?.error is SubsonicError.Api) remaining.clear()
                }
            }
            if (info == null) {
                _state.value = LoginUi(error = lastError.toUiError())
                return@launch
            }
            accounts.addOrUpdate(
                username = username.trim(),
                password = password,
                localUrl = localParsed?.toString(),
                externalUrl = externalParsed?.toString(),
                serverInfo = info,
            )
            _state.value = LoginUi(done = true)
        }
    }

    private fun validate(
        local: String?,
        localUrl: HttpUrl?,
        external: String?,
        externalUrl: HttpUrl?,
        username: String,
        password: String,
    ): UiError? = when {
        local == null && external == null -> UiError(R.string.login_error_no_address)
        local != null && localUrl == null -> UiError(R.string.login_error_bad_address)
        external != null && externalUrl == null -> UiError(R.string.login_error_bad_address)
        localUrl != null && !CleartextPolicy.isAllowed(localUrl) -> UiError(R.string.error_cleartext)
        externalUrl != null && !CleartextPolicy.isAllowed(externalUrl) -> UiError(R.string.error_cleartext)
        username.isBlank() || password.isEmpty() -> UiError(R.string.login_error_credentials_empty)
        else -> null
    }
}

/** Вход в учётку Navidrome. [prefill] — повторный вход (ключ шифрования потерян). */
@Composable
fun LoginScreen(
    prefill: Account?,
    onBack: (() -> Unit)? = null,
    onDone: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var local by rememberSaveable { mutableStateOf(prefill?.localUrl.orEmpty()) }
    var external by rememberSaveable { mutableStateOf(prefill?.externalUrl.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(prefill?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(topBar = { if (onBack != null) BodroTopBar(stringResource(R.string.accounts_add), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (onBack == null) Modifier.safeDrawingPadding() else Modifier)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("login-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack == null) {
                Icon(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(120.dp),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(if (prefill != null) R.string.login_relogin else R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            val fieldModifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)
            OutlinedTextField(
                value = local,
                onValueChange = { local = it },
                label = { Text(stringResource(R.string.login_local_url)) },
                placeholder = { Text("http://192.168.1.10:4533") },
                supportingText = { Text(stringResource(R.string.login_local_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = fieldModifier.testTag("login-local"),
            )
            OutlinedTextField(
                value = external,
                onValueChange = { external = it },
                label = { Text(stringResource(R.string.login_external_url)) },
                placeholder = { Text("https://music.example.ru") },
                supportingText = { Text(stringResource(R.string.login_external_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = fieldModifier.testTag("login-external"),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = fieldModifier.testTag("login-username"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = stringResource(R.string.login_show_password),
                        )
                    }
                },
                modifier = fieldModifier.testTag("login-password"),
            )
            state.error?.let { error ->
                Text(
                    stringResource(error.message) + (error.details?.let { "\n$it" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("login-error"),
                )
            }
            Button(
                onClick = { viewModel.login(local, external, username, password) },
                enabled = !state.loading,
                modifier = fieldModifier.height(52.dp).testTag("login-submit"),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.login_submit))
                }
            }
        }
    }
}
