package ru.bodro.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.ui.app.BodroRoot
import ru.bodro.music.ui.app.ExternalRequest
import ru.bodro.music.ui.app.MainViewModel
import ru.bodro.music.ui.image.CoverArtUrls

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var coverArtUrls: CoverArtUrls

    private val viewModel: MainViewModel by viewModels()
    private val externalRequests = Channel<ExternalRequest>(Channel.BUFFERED)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.session.value == SessionState.Loading }
        enableEdgeToEdge()
        // После поворота экрана intent тот же — действие из уведомления не повторяем.
        if (savedInstanceState == null) handleIntent(intent)
        requestNotificationPermission()
        setContent {
            BodroRoot(viewModel, coverArtUrls, externalRequests.receiveAsFlow())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val songId = intent.getStringExtra(EXTRA_PLAY_SONG_ID)
        when {
            intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false) -> externalRequests.trySend(ExternalRequest.OpenPlayer)
            songId != null -> externalRequests.trySend(ExternalRequest.PlaySong(songId))
            intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) -> externalRequests.trySend(ExternalRequest.OpenDownloads)
        }
    }

    /** Android 13+: без разрешения уведомление плеера не показывается. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "ru.bodro.music.OPEN_PLAYER"
        const val EXTRA_PLAY_SONG_ID = "ru.bodro.music.PLAY_SONG_ID"
        const val EXTRA_OPEN_DOWNLOADS = "ru.bodro.music.OPEN_DOWNLOADS"
    }
}
